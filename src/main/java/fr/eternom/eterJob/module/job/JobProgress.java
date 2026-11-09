package fr.eternom.eterJob.module.job;

import com.google.gson.JsonObject;
import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.cache.NetworkBus;
import fr.eternom.eterLib.helper.economy.Money;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.ranking.RankingSource;
import fr.eternom.eterLib.helper.sidebar.SidebarOverrides;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterJob.module.job.JobRepository.Member;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.block.Biome;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Les quêtes des joueurs connectés : progression des actions, sidebar (quête suivie, ou toutes les quêtes), barre de
 * boss pendant une action, rappels, marqueur « ! » au-dessus du donneur de quêtes, et nouvelles quêtes à minuit.
 *
 * L'état vit sur le thread principal. Les progrès partent en base par paquets (toutes les 30 s, à la déconnexion, avant
 * chaque validation, et au changement de serveur) en s'AJOUTANT : deux serveurs ne s'écrasent jamais. La clé d'un
 * progrès contient les objectifs : un progrès d'une quête changée entre-temps ne compte pas pour la nouvelle.
 */
public class JobProgress {

    private static final String SIDEBAR_OWNER = "EterJob";
    private static final long FLUSH_TICKS = 30 * 20;
    private static final long MINUTE_TICKS = 60 * 20;
    private static final long MARKER_TICKS = 5 * 20;
    private static final long EXPLORE_TICKS = 2 * 20;
    private static final long BOSSBAR_MILLIS = 5000;
    private static final String JOB_TAG = "job";
    private static final String RANK_TAG = "job_rank";
    /** Quête suivie « toutes » : la sidebar montre le résumé des quêtes. */
    static final int TRACK_ALL = -2;

    /** Un progrès pas encore écrit en base. */
    private record Key(UUID player, int slot, long day, String objectives, int action, int target) {
    }

    /** Métier, quêtes, réglages et état d'un joueur connecté. */
    private static final class Active {
        final String job;
        final List<Quest> quests;
        boolean summary;
        boolean quiet;
        boolean playtimeOk = true;
        int level;
        Rank rank;
        long lastReminder;
        BossBar bar;
        long barUntil;

        Active(String job, List<Quest> quests) {
            this.job = job;
            this.quests = new ArrayList<>(quests);
        }
    }

    private final JavaPlugin plugin;
    private final JobRepository repository;
    private final JobService service;
    private final Messages messages;
    private final QuestTexts texts;
    private final Map<UUID, Active> active = new HashMap<>();
    private final Map<Key, Integer> pending = new ConcurrentHashMap<>();
    /** Validations en cours (pas deux à la fois pour un joueur). */
    private final Set<UUID> locks = ConcurrentHashMap.newKeySet();
    private NetworkBus bus;
    private NpcMarkers markers;
    private long lastDay;

    JobProgress(JavaPlugin plugin, JobRepository repository, JobService service, Messages messages, QuestTexts texts) {
        this.plugin = plugin;
        this.repository = repository;
        this.service = service;
        this.messages = messages;
        this.texts = texts;
    }

    public void start(NetworkBus bus, NpcMarkers markers) {
        this.bus = bus;
        this.markers = markers;
        this.lastDay = service.today();
        bus.on("master", data -> Bukkit.getOnlinePlayers().forEach(player -> messages.send(player, "rank.master-announce",
                "player", data.get("player").getAsString(), "job", service.jobName(player, data.get("job").getAsString()))));
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::flushAll, FLUSH_TICKS, FLUSH_TICKS);
        Bukkit.getScheduler().runTaskTimer(plugin, this::minute, MINUTE_TICKS, MINUTE_TICKS);
        Bukkit.getScheduler().runTaskTimer(plugin, this::refreshMarkers, MARKER_TICKS, MARKER_TICKS);
        Bukkit.getScheduler().runTaskTimer(plugin, this::explore, EXPLORE_TICKS, EXPLORE_TICKS);
        Bukkit.getOnlinePlayers().forEach(this::join); // /reload
    }

    /** Arrêt du serveur : écrit les derniers progrès (bloquant, comme tout onDisable). */
    public void stop() {
        flushAll();
    }

    // ---------- Connexion ----------

    public void join(Player player) {
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, player, () -> {
            var board = service.board(uuid);
            long played = playtime(uuid);
            return new Object[]{board.orElse(null), played};
        }, result -> {
            JobService.Board board = (JobService.Board) result[0];
            if (board == null) {
                active.remove(uuid);
                publishJob(player);
                return;
            }
            update(player, board);
            Active state = active.get(uuid);
            if (state != null) {
                state.playtimeOk = (long) result[1] >= service.settings().minPlaytimeMinutes();
            }
        }, () -> { });
    }

    /** Temps de jeu actif (minutes, EterSuccess par EterLib) ; Long.MAX_VALUE sans source. Bloquant. */
    private static long playtime(UUID player) {
        try {
            return RankingSource.find("playtime_total").map(source -> source.of(player)[1]).orElse(Long.MAX_VALUE);
        } catch (RuntimeException e) {
            return Long.MAX_VALUE;
        }
    }

    public void quit(Player player) {
        UUID uuid = player.getUniqueId();
        Active state = active.remove(uuid);
        if (state != null && state.bar != null) {
            player.hideBossBar(state.bar);
        }
        EterLib.get().getSidebars().clear(uuid, SIDEBAR_OWNER);
        locks.remove(uuid);
        Tasks.async(plugin, () -> flush(uuid), "Progression des quêtes non enregistrée");
    }

    /** Thread principal : métier ou quêtes changés, on relit tout en base. */
    public void reload(Player player) {
        join(player);
    }

    /** Thread principal : remplace l'état, en gardant les progrès pas encore écrits et la quête suivie choisie ici. */
    void update(Player player, JobService.Board board) {
        UUID uuid = player.getUniqueId();
        Active previous = active.get(uuid);
        List<Quest> merged = new ArrayList<>();
        for (Quest quest : board.quests()) {
            Quest updated = quest;
            List<Objective> actions = quest.actions();
            for (int action = 0; action < actions.size(); action++) {
                Integer waiting = pending.get(key(uuid, quest, action));
                if (waiting != null) {
                    updated = updated.withProgress(action, Math.min(actions.get(action).amount(), quest.progress(action) + waiting));
                }
            }
            if (previous != null && previous.job.equals(board.job())) {
                boolean tracked = previous.quests.stream().anyMatch(q -> q.tracked() && q.slot() == quest.slot() && q.day() == quest.day());
                updated = updated.withTracked(tracked && !updated.done());
            }
            merged.add(updated);
        }
        Active state = new Active(board.job(), merged);
        state.quiet = board.member().quiet();
        state.level = service.levels().level(board.level().xp());
        state.rank = service.levels().rank(state.level);
        if (previous != null) {
            state.summary = previous.summary;
            state.playtimeOk = previous.playtimeOk;
            state.lastReminder = previous.lastReminder;
            state.bar = previous.bar;
        }
        active.put(uuid, state);
        refreshSidebar(player);
        publishJob(player);
    }

    /** Thread principal : la quête telle que ce serveur la connaît (progression à jour), sinon celle donnée. */
    public Quest current(UUID player, Quest quest) {
        Active state = active.get(player);
        if (state == null) {
            return quest;
        }
        return state.quests.stream().filter(q -> q.slot() == quest.slot() && q.day() == quest.day()).findFirst().orElse(quest);
    }

    /** Les quêtes connues ici (menus) ; vide si le joueur n'a pas de métier. */
    public List<Quest> quests(UUID player) {
        Active state = active.get(player);
        return state == null ? List.of() : List.copyOf(state.quests);
    }

    boolean lock(UUID player) {
        return locks.add(player);
    }

    void unlock(UUID player) {
        locks.remove(player);
    }

    void markDone(Player player, Quest quest) {
        replace(player.getUniqueId(), quest.slot(), Quest::withDone);
        // La bonus, l'étape d'histoire suivante... : relues en base
        reload(player);
    }

    // ---------- Actions ----------

    /**
     * Thread principal : le joueur a fait amount fois une action ; compte pour chaque action de ses quêtes qui
     * correspond (une même créature peut compter pour « tuer des zombies » et « tuer des monstres »).
     */
    public void record(Player player, Objective.Kind kind, Predicate<Objective> matches, int amount) {
        Active state = active.get(player.getUniqueId());
        if (state == null || !state.playtimeOk) {
            return;
        }
        long today = service.today();
        long week = service.week();
        List<Quest> quests = state.quests;
        for (int i = 0; i < quests.size(); i++) {
            Quest quest = quests.get(i);
            boolean expired = (quest.isDaily() || quest.isBonus()) && quest.day() != today || quest.isWeekly() && quest.day() != week;
            if (quest.done() || expired) {
                continue;
            }
            List<Objective> actions = quest.actions();
            for (int action = 0; action < actions.size(); action++) {
                Objective objective = actions.get(action);
                int before = quest.progress(action);
                if (objective.kind() != kind || before >= objective.amount() || !matches.test(objective)) {
                    continue;
                }
                int progress = Math.min(objective.amount(), before + amount);
                pending.merge(key(player.getUniqueId(), quest, action), progress - before, Integer::sum);
                quest = quest.withProgress(action, progress);
                if (service.settings().autoTrack() && !state.summary && quests.stream().noneMatch(Quest::tracked)) {
                    quest = quest.withTracked(true);
                    int slot = quest.slot();
                    UUID uuid = player.getUniqueId();
                    Tasks.async(plugin, () -> repository.track(uuid, slot), "Quête suivie non enregistrée");
                }
                if (progress >= objective.amount()) {
                    if (!state.quiet) {
                        messages.send(player, quest.actionComplete() ? "quest.action-done" : "quest.step-done");
                    }
                    player.playSound(player, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
                } else if (!state.quiet) {
                    showProgress(player, state, objective, progress);
                }
            }
            quests.set(i, quest);
        }
    }

    /** La progression : barre de boss quelques secondes (ou action bar). */
    private void showProgress(Player player, Active state, Objective objective, int progress) {
        Component text = texts.objective(player, objective, progress);
        if (!service.settings().bossBar()) {
            player.sendActionBar(text);
            return;
        }
        float ratio = Math.clamp(progress / (float) objective.amount(), 0f, 1f);
        if (state.bar == null) {
            state.bar = BossBar.bossBar(text, ratio, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
            player.showBossBar(state.bar);
        } else {
            state.bar.name(text);
            state.bar.progress(ratio);
            player.showBossBar(state.bar);
        }
        state.barUntil = System.currentTimeMillis() + BOSSBAR_MILLIS;
        UUID uuid = player.getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Active now = active.get(uuid);
            Player online = Bukkit.getPlayer(uuid);
            if (now != null && now.bar != null && online != null && System.currentTimeMillis() >= now.barUntil) {
                online.hideBossBar(now.bar);
            }
        }, BOSSBAR_MILLIS / 50 + 2);
    }

    /** Explorer : toutes les 2 s, le biome de chaque joueur qui a une quête d'exploration. */
    private void explore() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Active state = active.get(player.getUniqueId());
            if (state == null || state.quests.stream().noneMatch(q -> !q.done()
                    && q.actions().stream().anyMatch(o -> o.kind() == Objective.Kind.EXPLORE))) {
                continue;
            }
            Biome biome = player.getLocation().getBlock().getBiome();
            record(player, Objective.Kind.EXPLORE, objective -> ObjectiveTargets.matchesBiome(objective, biome), 1);
        }
    }

    /** Bloquant : écrit les progrès en attente d'un joueur (avant de lire ou valider ses quêtes). */
    public void flush(UUID player) {
        for (Key key : pending.keySet()) {
            if (key.player().equals(player)) {
                write(key);
            }
        }
    }

    private void flushAll() {
        pending.keySet().forEach(this::write);
    }

    private void write(Key key) {
        Integer delta = pending.remove(key);
        if (delta != null && delta > 0) {
            repository.addProgress(key.player(), key.slot(), key.day(), key.objectives(), key.action(), delta, key.target());
        }
    }

    private static Key key(UUID player, Quest quest, int action) {
        return new Key(player, quest.slot(), quest.day(), Objective.format(quest.objectives()), action, quest.actions().get(action).amount());
    }

    // ---------- Chaque minute : nouveau jour, rappels, étiquettes ----------

    private void minute() {
        long today = service.today();
        boolean newDay = today != lastDay;
        lastDay = today;
        for (Player player : Bukkit.getOnlinePlayers()) {
            Active state = active.get(player.getUniqueId());
            if (newDay && state != null) {
                // Minuit : les nouvelles quêtes tout de suite, sans repasser au donneur de quêtes
                join(player);
                if (!state.quiet) {
                    messages.send(player, "quest.new-day");
                }
                continue;
            }
            publishJob(player);
            remind(player, state);
        }
    }

    /** Une quête prête à valider : rappel au plus toutes les ready-reminder-minutes. */
    private void remind(Player player, Active state) {
        int minutes = service.settings().reminderMinutes();
        if (state == null || state.quiet || minutes <= 0 || System.currentTimeMillis() - state.lastReminder < minutes * 60_000L) {
            return;
        }
        long ready = state.quests.stream().filter(quest -> QuestTexts.ready(player, quest)).count();
        if (ready > 0) {
            state.lastReminder = System.currentTimeMillis();
            messages.send(player, "quest.ready-reminder", "count", String.valueOf(ready));
        }
    }

    /** « ! » au-dessus du donneur de quêtes du métier du joueur quand une quête est prête à livrer (EterNpc). */
    private void refreshMarkers() {
        if (markers == null) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            Active state = active.get(player.getUniqueId());
            String npc = state == null ? null : service.settings().questGivers().get(state.job);
            if (npc == null) {
                continue;
            }
            boolean ready = state.quests.stream().anyMatch(quest -> QuestTexts.ready(player, quest));
            markers.mark(player, npc, ready ? messages.get(player, "quest.marker") : null);
        }
    }

    /** Annonce d'un nouveau maître sur les autres serveurs. */
    void announceMaster(String player, String job) {
        if (bus == null) {
            return;
        }
        JsonObject data = new JsonObject();
        data.addProperty("player", player);
        data.addProperty("job", job);
        bus.publish("master", data);
    }

    // ---------- Sidebar et étiquettes ----------

    /**
     * Étiquettes d'EterLib : <tag_job> (métier et quêtes dispo, sous l'argent) et <tag_job_rank> (« Artisan mineur »),
     * pour la sidebar et le tab d'EterTab.
     */
    void publishJob(Player player) {
        Active state = active.get(player.getUniqueId());
        if (state == null) {
            EterLib.get().getPlayerTags().set(player, JOB_TAG, messages.raw(player, "job.sidebar-none"));
            EterLib.get().getPlayerTags().remove(player, RANK_TAG);
            return;
        }
        long today = service.today();
        long available = state.quests.stream().filter(q -> !q.done() && (q.isDaily() || q.isBonus()) && q.day() == today).count();
        String format = messages.raw(player, "job.sidebar");
        String job = MiniMessage.miniMessage().escapeTags(service.jobName(player, state.job));
        EterLib.get().getPlayerTags().set(player, JOB_TAG, format == null ? "" : format.replace("<job>", job)
                .replace("<count>", String.valueOf(available)).replace("<level>", String.valueOf(state.level)));
        String title = messages.raw(player, "rank.title." + state.rank.id());
        if (title != null) {
            EterLib.get().getPlayerTags().set(player, RANK_TAG, title.replace("<job>", job));
        }
    }

    /** Thread principal : suit cette quête dans la sidebar, ou arrête ; TRACK_ALL : le résumé de toutes les quêtes. */
    public void toggleTrack(Player player, int slot) {
        UUID uuid = player.getUniqueId();
        Active state = active.get(uuid);
        if (state == null) {
            return;
        }
        int chosen;
        if (slot == TRACK_ALL) {
            state.summary = !state.summary;
            state.quests.replaceAll(q -> q.withTracked(false));
            chosen = -1;
            messages.send(player, state.summary ? "quest.summary-on" : "quest.untracked");
        } else {
            boolean track = state.quests.stream().noneMatch(q -> q.slot() == slot && q.tracked());
            state.summary = false;
            state.quests.replaceAll(q -> q.withTracked(track && q.slot() == slot));
            chosen = track ? slot : -1;
            messages.send(player, track ? "quest.tracked" : "quest.untracked");
        }
        Tasks.async(plugin, () -> repository.track(uuid, chosen), "Quête suivie non enregistrée");
        refreshSidebar(player);
    }

    /** Messages de progression coupés ou non (option du joueur). */
    public void toggleQuiet(Player player) {
        Active state = active.get(player.getUniqueId());
        boolean quiet = state == null || !state.quiet;
        if (state != null) {
            state.quiet = quiet;
        }
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, () -> repository.setQuiet(uuid, quiet), "Option des messages non enregistrée");
        messages.send(player, quiet ? "quest.quiet-on" : "quest.quiet-off");
    }

    public boolean isQuiet(UUID player) {
        Active state = active.get(player);
        return state != null && state.quiet;
    }

    private void refreshSidebar(Player player) {
        Active state = active.get(player.getUniqueId());
        if (state != null && (state.summary || tracked(player.getUniqueId()) != null)) {
            EterLib.get().getSidebars().show(player, SIDEBAR_OWNER, this::sidebar);
        } else {
            EterLib.get().getSidebars().clear(player.getUniqueId(), SIDEBAR_OWNER);
        }
    }

    private Quest tracked(UUID player) {
        Active state = active.get(player);
        if (state == null) {
            return null;
        }
        return state.quests.stream().filter(q -> q.tracked() && !q.done()).findFirst().orElse(null);
    }

    /** Appelée par EterTab à chaque rafraîchissement : la quête suivie (détail) ou le résumé de toutes. */
    private SidebarOverrides.Content sidebar(Player player) {
        Active state = active.get(player.getUniqueId());
        if (state == null) {
            return null;
        }
        Component title = messages.get(player, "quest.sidebar.title", "job", service.jobName(player, state.job));
        List<Component> lines = new ArrayList<>();
        lines.add(Component.empty());
        if (state.summary) {
            for (Quest quest : state.quests) {
                String key = quest.done() ? "quest.sidebar.summary-done" : QuestTexts.ready(player, quest)
                        ? "quest.sidebar.summary-ready" : "quest.sidebar.summary-todo";
                lines.add(messages.get(player, key, TagResolver.resolver(Placeholder.component("quest", texts.name(player, quest)))));
            }
            lines.add(Component.empty());
            return new SidebarOverrides.Content(title, lines);
        }
        Quest quest = tracked(player.getUniqueId());
        if (quest == null) {
            return null; // quête faite : retour à la sidebar habituelle
        }
        lines.add(texts.name(player, quest));
        lines.add(Component.empty());
        for (Objective objective : quest.objectives()) {
            lines.add(texts.objective(player, objective, QuestTexts.have(player, quest, objective)));
        }
        lines.add(Component.empty());
        lines.add(messages.get(player, "quest.sidebar.reward", "reward", Money.format(quest.reward())));
        lines.add(messages.get(player, QuestTexts.ready(player, quest) ? "quest.sidebar.ready" : "quest.sidebar.todo"));
        return new SidebarOverrides.Content(title, lines);
    }

    private void replace(UUID player, int slot, UnaryOperator<Quest> change) {
        Active state = active.get(player);
        if (state != null) {
            state.quests.replaceAll(q -> q.slot() == slot ? change.apply(q) : q);
        }
    }

    /** Le joueur a-t-il un métier (connu ici) ? */
    public boolean hasJob(UUID player) {
        return active.containsKey(player);
    }

    /** Membre lu en dernier (rang et niveau), pour les menus et conditions. */
    public Rank rankOf(UUID player) {
        Active state = active.get(player);
        return state == null ? null : state.rank;
    }

    public String jobOf(UUID player) {
        Active state = active.get(player);
        return state == null ? null : state.job;
    }

    /** Pour JobAdmin : un membre relu. */
    static boolean isMember(Member member, String job) {
        return member != null && member.job().equals(job);
    }
}
