package fr.eternom.eterJob.module.job;

import fr.eternom.eterEconomy.api.EconomyApi;
import fr.eternom.eterJob.api.JobChangeEvent;
import fr.eternom.eterJob.api.JobLevelUpEvent;
import fr.eternom.eterJob.api.JobPerks;
import fr.eternom.eterJob.api.JobQuestCompleteEvent;
import fr.eternom.eterJob.module.job.JobRepository.JobLevel;
import fr.eternom.eterJob.module.job.JobRepository.Member;
import fr.eternom.eterJob.module.job.QuestCatalog.Template;
import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.economy.Money;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterQuest.api.QuestApi;
import fr.eternom.eterQuest.api.QuestCompleteEvent;
import fr.eternom.eterQuest.api.QuestInstance;
import fr.eternom.eterQuest.api.TurnInResult;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * La guilde des métiers. Un métier à la fois (le premier est gratuit, en changer coûte change-cost et n'est possible
 * qu'une fois par change-cooldown-days), mais le niveau de CHAQUE métier pratiqué est gardé. Chaque jour : une quête par
 * niveau de « daily » (une petite chance qu'elle soit rare), jamais une d'hier, adaptées au rang du joueur (plus grandes,
 * mieux payées) ; puis la bonus ; une quête de la semaine ; et l'histoire du métier, une étape par rang.
 *
 * Les quêtes elles-mêmes sont celles d'EterQuest (propriétaire « eterjob ») : EterJob les tire et les donne (assign), les
 * fait rendre au donneur de quêtes (turnIn), et paie à la fin (QuestCompleteEvent, puis claim : une seule fois, même
 * sur deux serveurs) : récompense (bonus du niveau, évènement, série, métier le moins pratiqué ; plafond par jour), XP
 * de métier, rangs atteints.
 */
public class JobService implements Listener {

    /** Ce que montre le donneur de quêtes (ou /job) : métier, niveau, quêtes, changement de quête, série, colis. */
    public record Board(String job, Member member, JobLevel level, List<QuestInstance> quests, boolean canReroll, boolean rerollFree,
                        int streak, int boxCount) {
    }

    /** Ce que donne une validation (thread principal ensuite). */
    record Delivered(String job, QuestInstance quest, double paid, boolean capped, int xp, int levelBefore, int levelAfter, int streak,
                     List<Rank> newRanks, boolean dailyDone) {
    }

    private final JavaPlugin plugin;
    private final JobRepository repository;
    private final Messages messages;
    private final ZoneId zone;
    private final String server;
    private final QuestApi quests;
    private volatile JobSettings settings;
    private volatile QuestCatalog catalog;
    private final JobProgress progress;
    private volatile List<JobRepository.Event> events = List.of();
    private volatile String weakestJob;
    /** Métier et niveau des joueurs pour l'API (avantages d'EterResource...) : 30 s. */
    private final Map<UUID, CachedPerks> perksCache = new ConcurrentHashMap<>();

    private record CachedPerks(Optional<String> job, JobPerks perks, long at) {
    }

    private static final long CACHE_MILLIS = 30_000;

    public JobService(JavaPlugin plugin, JobRepository repository, JobSettings settings, QuestCatalog catalog, Messages messages,
                      ZoneId zone, QuestApi quests) {
        this.plugin = plugin;
        this.repository = repository;
        this.settings = settings;
        this.catalog = catalog;
        this.messages = messages;
        this.zone = zone;
        this.quests = quests;
        this.server = EterLib.get().getServerName();
        this.progress = new JobProgress(plugin, this, messages);
    }

    /** Évènements relus, métier le moins pratiqué recalculé, journal purgé : régulièrement (tâche de fond). */
    public void start() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            try {
                refreshEvents();
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Évènements des métiers non relus : " + e.getMessage());
            }
        }, 20, 20 * 60);
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            try {
                refreshWeakest();
                repository.purgeLog();
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Statistiques des métiers non relues : " + e.getMessage());
            }
        }, 40, 20 * 60 * 60);
        // Rappel doux : 30 min de connexion sans métier (reminder-minutes, 0 : jamais), une fois par connexion
        long remindAfter = plugin.getConfig().getLong("reminder-minutes", 30) * 60_000;
        if (remindAfter > 0) {
            java.util.Set<UUID> reminded = java.util.concurrent.ConcurrentHashMap.newKeySet();
            Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                long now = System.currentTimeMillis();
                reminded.removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
                for (org.bukkit.entity.Player online : Bukkit.getOnlinePlayers()) {
                    UUID uuid = online.getUniqueId();
                    if (now - online.getLastLogin() < remindAfter || !reminded.add(uuid)) {
                        continue;
                    }
                    Tasks.async(plugin, online, () -> jobOf(uuid).isEmpty(), none -> {
                        if (none) {
                            messages.send(online, "job.reminder");
                        }
                    }, () -> { });
                }
            }, 20 * 60, 20 * 60 * 5);
        }
        // Les colis de l'ancienne boîte d'EterJob passent dans celle d'EterQuest (une fois)
        Tasks.async(plugin, () -> {
            List<Map.Entry<UUID, String>> parcels = repository.drainOldBox();
            parcels.forEach(parcel -> quests.deposit(parcel.getKey(), parcel.getValue()));
            if (!parcels.isEmpty()) {
                plugin.getLogger().info(parcels.size() + " colis de l'ancienne boîte passés dans celle d'EterQuest");
            }
        }, "Ancienne boîte de récupération non reprise");
    }

    /** /eterjob reload : nouveaux réglages et quêtes (thread principal). */
    public void reload(JobSettings fresh, QuestCatalog freshCatalog) {
        this.settings = fresh;
        this.catalog = freshCatalog;
        perksCache.clear();
    }

    // ---------- Accès ----------

    public JobSettings settings() {
        return settings;
    }

    public QuestCatalog catalog() {
        return catalog;
    }

    public QuestApi quests() {
        return quests;
    }

    public Levels levels() {
        return settings.levels();
    }

    public JobProgress progress() {
        return progress;
    }

    public JobRepository repository() {
        return repository;
    }

    public Messages messages() {
        return messages;
    }

    public JavaPlugin plugin() {
        return plugin;
    }

    public long today() {
        return LocalDate.now(zone).toEpochDay();
    }

    /** Le lundi de la semaine en cours (jour julien) : la quête de la semaine change ce jour-là. */
    public long week() {
        return LocalDate.now(zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay();
    }

    public long secondsUntilTomorrow() {
        ZonedDateTime now = ZonedDateTime.now(zone);
        return Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(zone)).toSeconds();
    }

    public long secondsUntilNextWeek() {
        ZonedDateTime now = ZonedDateTime.now(zone);
        return Duration.between(now, LocalDate.ofEpochDay(week()).plusWeeks(1).atStartOfDay(zone)).toSeconds();
    }

    /** Minuit (fin de validité des quêtes du jour), en millisecondes. */
    private long endOfDay(long day) {
        return LocalDate.ofEpochDay(day + 1).atStartOfDay(zone).toInstant().toEpochMilli();
    }

    public String jobName(CommandSender viewer, String job) {
        return messages.raw(viewer, "job.name." + job) == null ? job : messages.plain(viewer, "job.name." + job);
    }

    public String rankName(CommandSender viewer, Rank rank) {
        return messages.plain(viewer, "rank." + rank.id());
    }

    // ---------- Tableau du joueur ----------

    /** Bloquant : ce que montre le donneur de quêtes de ce métier ; vide si le joueur n'en est pas membre. */
    public Optional<Board> board(UUID player, String job) {
        Optional<Member> member = repository.member(player);
        if (member.isEmpty() || !member.get().job().equals(job)) {
            return Optional.empty();
        }
        return Optional.of(board(player, member.get()));
    }

    /** Bloquant : le tableau du métier du joueur, s'il en a un. */
    public Optional<Board> board(UUID player) {
        return repository.member(player).map(member -> board(player, member));
    }

    private Board board(UUID player, Member member) {
        JobLevel level = repository.level(player, member.job());
        List<QuestInstance> current = ensureQuests(player, member, level);
        Rank rank = levels().rank(levels().level(level.xp()));
        return new Board(member.job(), member, level, current,
                settings.rerollEnabled() && member.rerollDay() != today(), rank.atLeast(settings.rerollFreeRank()),
                JobRepository.activeStreak(member, today()), quests.boxCount(player));
    }

    /**
     * Les quêtes du joueur, tirées ce qu'il faut : quêtes du jour d'un autre jour (jamais celles d'hier), la bonus quand
     * elles sont faites, la quête de la semaine, l'étape d'histoire de son rang. Bloquant.
     */
    List<QuestInstance> ensureQuests(UUID player, Member member, JobLevel jobLevel) {
        String job = member.job();
        long today = today();
        int level = levels().level(jobLevel.xp());
        Rank rank = levels().rank(level);
        QuestCatalog.JobQuests pool = catalog.of(job);
        List<QuestInstance> current = new ArrayList<>(quests.instances(player, JobQuests.OWNER));
        // Une quête d'un autre métier (changement fait ailleurs) : retirée
        for (QuestInstance quest : List.copyOf(current)) {
            if (!quest.meta("job", job).equals(job)) {
                quests.remove(player, JobQuests.OWNER, quest.slot());
                current.remove(quest);
            }
        }
        List<QuestInstance> daily = JobQuests.dailies(current);
        if (daily.isEmpty() || JobQuests.day(daily.getFirst()) != today) {
            // Nouveau jour : les quêtes d'hier sont retenues pour ne pas retomber dessus
            Set<String> recent = new HashSet<>(daily.stream().map(QuestInstance::quest).toList());
            if (member.recent() != null && daily.isEmpty()) {
                recent.addAll(Arrays.asList(member.recent().split("\\|")));
            }
            current.removeIf(quest -> JobQuests.isDaily(quest) || JobQuests.isBonus(quest));
            quests.remove(player, JobQuests.OWNER, JobQuests.BONUS);
            List<QuestInstance> drawn = drawDaily(player, pool, today, rank, level, recent);
            for (int i = drawn.size(); i < settings.daily().size(); i++) {
                quests.remove(player, JobQuests.OWNER, JobQuests.daily(i));
            }
            repository.setRecent(player, String.join("|", drawn.stream().map(QuestInstance::quest).toList()));
            current.addAll(drawn);
        }
        List<QuestInstance> todays = JobQuests.dailies(current);
        boolean dailyDone = !todays.isEmpty() && todays.stream().allMatch(QuestInstance::done);
        if (settings.bonusQuest() && dailyDone && current.stream().noneMatch(JobQuests::isBonus)) {
            Set<String> taken = new HashSet<>(current.stream().map(QuestInstance::quest).toList());
            pick(player, pool.of(QuestCatalog.Kind.DAILY), settings.bonusTier(), rank, taken).ifPresent(template ->
                    current.add(assign(player, JobQuests.BONUS, template, rank, level, false, today, endOfDay(today), job)));
        }
        Optional<QuestInstance> weekly = current.stream().filter(JobQuests::isWeekly).findFirst();
        if (settings.weekly() && (weekly.isEmpty() || JobQuests.day(weekly.get()) != week())) {
            List<Template> choices = pool.of(QuestCatalog.Kind.WEEKLY).stream()
                    .filter(t -> rank.atLeast(t.minRank()) && quests.canStart(player, t.quest())).toList();
            current.removeIf(JobQuests::isWeekly);
            if (!choices.isEmpty()) {
                Template template = choices.get(ThreadLocalRandom.current().nextInt(choices.size()));
                long end = LocalDate.ofEpochDay(week()).plusWeeks(1).atStartOfDay(zone).toInstant().toEpochMilli();
                current.add(assign(player, JobQuests.WEEKLY, template, rank, level, false, week(), end, job));
            } else {
                quests.remove(player, JobQuests.OWNER, JobQuests.WEEKLY);
            }
        }
        if (current.stream().noneMatch(JobQuests::isStory) && jobLevel.story() < Rank.values().length) {
            Rank step = Rank.values()[jobLevel.story()];
            if (rank.atLeast(step)) {
                pool.story(step).ifPresent(template ->
                        current.add(assign(player, JobQuests.STORY, template, Rank.APPRENTI, 1, false, 0, 0, job)));
            }
        }
        current.sort((a, b) -> order(a) - order(b));
        return current;
    }

    private static int order(QuestInstance quest) {
        return JobQuests.isDaily(quest) ? JobQuests.dailyIndex(quest) : JobQuests.isBonus(quest) ? 10
                : JobQuests.isWeekly(quest) ? 11 : 12;
    }

    /** Une quête par niveau de daily, différentes, jamais d'hier ; chacune a rare-chance d'être une rare. */
    private List<QuestInstance> drawDaily(UUID player, QuestCatalog.JobQuests pool, long today, Rank rank, int level, Set<String> recent) {
        Set<String> taken = new HashSet<>(recent);
        List<QuestInstance> drawn = new ArrayList<>();
        List<Template> rares = new ArrayList<>(pool.of(QuestCatalog.Kind.RARE).stream()
                .filter(t -> rank.atLeast(t.minRank()) && !recent.contains(t.id()) && quests.canStart(player, t.quest())).toList());
        Collections.shuffle(rares);
        String job = pool.byKind().values().stream().flatMap(List::stream).findFirst().map(t -> t.quest().meta("job", "")).orElse("");
        for (Tier tier : settings.daily()) {
            String slot = JobQuests.daily(drawn.size());
            if (!rares.isEmpty() && ThreadLocalRandom.current().nextDouble() < settings.rareChance()) {
                Template rare = rares.removeFirst();
                taken.add(rare.id());
                drawn.add(assign(player, slot, rare, rank, level, true, today, endOfDay(today), job));
                continue;
            }
            Optional<Template> template = pick(player, pool.of(QuestCatalog.Kind.DAILY), tier, rank, taken);
            if (template.isEmpty()) {
                template = pick(player, pool.of(QuestCatalog.Kind.DAILY), tier, rank,
                        new HashSet<>(drawn.stream().map(QuestInstance::quest).toList()));
            }
            if (template.isPresent()) {
                taken.add(template.get().id());
                drawn.add(assign(player, slot, template.get(), rank, level, false, today, endOfDay(today), job));
            }
        }
        return drawn;
    }

    /** Au hasard parmi celles du niveau et du rang (conditions remplies) ; à défaut, parmi toutes celles du rang. */
    private Optional<Template> pick(UUID player, List<Template> pool, Tier tier, Rank rank, Set<String> taken) {
        List<Template> free = pool.stream().filter(t -> rank.atLeast(t.minRank()) && !taken.contains(t.id())
                && quests.canStart(player, t.quest())).toList();
        List<Template> sameTier = new ArrayList<>(free.stream().filter(t -> t.tier() == tier).toList());
        List<Template> choices = sameTier.isEmpty() ? new ArrayList<>(free) : sameTier;
        if (choices.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(choices.get(ThreadLocalRandom.current().nextInt(choices.size())));
    }

    /**
     * Donne une quête du répertoire, à la taille du rang (quantités × scale par EterQuest, récompense × scale), avec le
     * bonus du niveau ; la bonus × bonus-multiplier, une rare × rare-multiplier. Bloquant.
     */
    private QuestInstance assign(UUID player, String slot, Template template, Rank rank, int level, boolean rare, long day, long expires,
                                 String job) {
        Map<String, String> meta = meta(slot, template, rank, level, rare, day, job);
        double scale = slot.equals(JobQuests.STORY) ? 1 : levels().scale(rank);
        return quests.assign(player, JobQuests.OWNER, slot, template.id(), scale, expires, meta);
    }

    private Map<String, String> meta(String slot, Template template, Rank rank, int level, boolean rare, long day, String job) {
        double scale = slot.equals(JobQuests.STORY) ? 1 : levels().scale(rank);
        double reward = template.reward() * scale * levels().rewardMultiplier(level);
        if (slot.equals(JobQuests.BONUS)) {
            reward *= settings.bonusMultiplier();
        }
        if (rare) {
            reward *= settings.rareMultiplier();
        }
        int xp = template.xp() > 0 ? template.xp() : switch (slot) {
            case JobQuests.BONUS -> settings.bonusXp();
            case JobQuests.WEEKLY -> settings.weeklyXp();
            case JobQuests.STORY -> settings.storyXp();
            default -> settings.xpOf(template.tier());
        };
        if (rare) {
            xp = (int) Math.round(xp * settings.rareMultiplier());
        }
        Map<String, String> meta = new HashMap<>();
        meta.put("job", job.isEmpty() ? template.quest().meta("job", "") : job);
        meta.put("kind", template.kind().name().toLowerCase(java.util.Locale.ROOT));
        meta.put("tier", template.tier().id());
        meta.put("reward", String.valueOf(Math.round(reward)));
        meta.put("xp", String.valueOf(xp));
        meta.put("rare", String.valueOf(rare));
        meta.put("day", String.valueOf(day));
        return meta;
    }

    // ---------- Choisir ou changer de métier ----------

    /** Bloquant : métier actuel et délai avant de pouvoir en changer (secondes). */
    public long cooldownSeconds(Member member) {
        return Math.max(0, member.changedAt() + settings.changeCooldown().toMillis() - System.currentTimeMillis()) / 1000;
    }

    /** Thread principal : rejoindre ce métier (gratuit la première fois, sinon payant et limité dans le temps). */
    public void join(Player player, String job, Runnable after) {
        EconomyApi economy = EconomyApi.get().orElse(null);
        UUID uuid = player.getUniqueId();
        double cost = settings.changeCost();
        Tasks.async(plugin, player, () -> {
            Optional<Member> current = repository.member(uuid);
            if (current.isPresent()) {
                if (current.get().job().equals(job)) {
                    return new String[]{"job.already", ""};
                }
                if (cooldownSeconds(current.get()) > 0) {
                    return new String[]{"job.change-too-soon", ""};
                }
                if (cost > 0 && (economy == null || !economy.withdraw(uuid, cost, "EterJob · changement de métier"))) {
                    return new String[]{economy == null ? "economy.unavailable" : "job.change-not-enough", ""};
                }
            }
            repository.setJob(uuid, job, System.currentTimeMillis());
            quests.clear(uuid, JobQuests.OWNER); // les quêtes de l'ancien métier
            perksCache.remove(uuid);
            return new String[]{current.isPresent() ? "job.changed" : "job.joined", current.map(Member::job).orElse("")};
        }, result -> {
            messages.send(player, result[0], "job", jobName(player, job), "price", Money.format(cost));
            if (result[0].equals("job.joined") || result[0].equals("job.changed")) {
                player.playSound(player, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.2f);
                Bukkit.getPluginManager().callEvent(new JobChangeEvent(player, result[1], job));
                progress.reload(player);
                after.run();
            } else {
                Sounds.refuse(player);
            }
        }, () -> messages.send(player, "error.generic"));
    }

    // ---------- Valider ----------

    /**
     * Thread principal : rendre l'étape en cours d'une quête (la dernière : la quête est validée, payée par
     * onComplete). remote : depuis /job (une étape sans objet à remettre). after ensuite, validée ou non.
     */
    public void deliver(Player player, QuestInstance shown, boolean remote, Runnable after) {
        if (EconomyApi.get().isEmpty()) {
            messages.send(player, "economy.unavailable");
            return;
        }
        if (remote && !settings.remoteDeliver()) {
            messages.send(player, "quest.remote-items");
            Sounds.refuse(player);
            return;
        }
        // Avant : la fin (QuestCompleteEvent) arrive avant le résultat, et c'est le paiement qui rouvre le menu
        UUID uuid = player.getUniqueId();
        pendingAfter.put(uuid, after);
        quests.turnIn(player, JobQuests.OWNER, shown.slot(), remote ? "remote" : "giver", result -> {
            switch (result) {
                case DONE, ADVANCED, BUSY -> {
                }
                case NOT_READY -> refuse(player, "quest.action-missing");
                case MISSING_ITEMS -> refuse(player, "quest.missing");
                case REMOTE_ITEMS -> refuse(player, "quest.remote-items");
                case NOT_HERE -> refuse(player, "quest.not-here");
                case EXPIRED -> refuse(player, "quest.expired");
                case ALREADY -> refuse(player, "quest.already-done");
                case ERROR -> refuse(player, "error.generic");
            }
            if (result != TurnInResult.DONE) {
                Runnable reopen = pendingAfter.remove(uuid);
                if (reopen != null) {
                    reopen.run();
                }
            }
        });
    }

    /** Menu à rouvrir après le paiement d'une quête validée (le paiement arrive par onComplete). */
    private final Map<UUID, Runnable> pendingAfter = new ConcurrentHashMap<>();

    private void refuse(Player player, String key) {
        messages.send(player, key);
        Sounds.refuse(player);
    }

    /** Une quête de la guilde vient d'être finie (ici, au donneur, ou chez un PNJ du village) : on paie, une fois. */
    @EventHandler
    public void onComplete(QuestCompleteEvent event) {
        if (!event.instance().owner().equals(JobQuests.OWNER)) {
            return;
        }
        claimAndPay(event.getPlayer(), event.instance());
    }

    /** Arrivée : les quêtes finies sur un serveur sans EterJob (ou pendant une coupure) sont payées maintenant. */
    public void claimPending(Player player) {
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, player, () -> quests.unclaimed(uuid, JobQuests.OWNER), pending -> pending.forEach(quest -> claimAndPay(player, quest)),
                () -> { });
    }

    private void claimAndPay(Player player, QuestInstance quest) {
        EconomyApi economy = EconomyApi.get().orElse(null);
        UUID uuid = player.getUniqueId();
        Runnable after = pendingAfter.remove(uuid);
        if (economy == null) {
            messages.send(player, "economy.unavailable"); // payée plus tard (claimPending), rien n'est perdu
            return;
        }
        String name = player.getName();
        Tasks.async(plugin, player, () -> {
            if (!quests.claim(uuid, quest.owner(), quest.slot(), quest.token())) {
                return null; // déjà payée (ailleurs)
            }
            try {
                return pay(uuid, name, quest, economy);
            } catch (RuntimeException e) {
                plugin.getLogger().severe("Quête validée pour " + name + " mais paiement incomplet : " + e);
                return new Delivered("", quest, 0, false, 0, 1, 1, 0, List.of(), false);
            }
        }, result -> {
            if (result != null) {
                delivered(player, result);
            }
            if (after != null) {
                after.run();
            }
        }, () -> messages.send(player, "error.generic"));
    }

    /** Bloquant, après la validation : récompense, XP, série, histoire, rangs, journal. */
    private Delivered pay(UUID uuid, String name, QuestInstance quest, EconomyApi economy) {
        String job = quest.meta("job", repository.member(uuid).map(Member::job).orElse(""));
        long today = today();
        double multiplier = eventMultiplier(job);
        if (job.equals(weakestJob)) {
            multiplier *= 1 + settings.weakestBonus();
        }
        int streak = repository.member(uuid).map(member -> JobRepository.activeStreak(member, today)).orElse(0);
        multiplier *= 1 + Math.min(settings.streakMax(), streak * settings.streakBonus());
        double reward = Math.round(JobQuests.reward(quest) * multiplier);
        double allowed = repository.addEarned(uuid, today, reward, settings.dailyMoneyCap());
        boolean capped = reward > allowed;
        double paid = Math.min(reward, allowed);
        if (paid > 0) {
            economy.deposit(uuid, paid, "EterJob · " + job);
        }
        int xp = (int) Math.round(JobQuests.xp(quest) * eventMultiplier(job));
        JobLevel before = repository.level(uuid, job);
        int xpAfter = repository.addXp(uuid, job, xp, true);
        int levelBefore = levels().level(before.xp());
        int levelAfter = levels().level(xpAfter);
        boolean dailyDone = false;
        if (JobQuests.isDaily(quest)) {
            List<QuestInstance> dailies = JobQuests.dailies(quests.instances(uuid, JobQuests.OWNER));
            dailyDone = !dailies.isEmpty() && dailies.stream().allMatch(QuestInstance::done);
            if (dailyDone) {
                streak = repository.advanceStreak(uuid, today);
            }
        }
        if (JobQuests.isStory(quest) && repository.advanceStory(uuid, job, before.story())) {
            quests.remove(uuid, JobQuests.OWNER, JobQuests.STORY); // la prochaine étape arrive avec le rang suivant
        }
        List<Rank> newRanks = new ArrayList<>();
        Rank rankAfter = levels().rank(levelAfter);
        for (Rank rank : Rank.values()) {
            if (rank != Rank.APPRENTI && rankAfter.atLeast(rank) && repository.markRankRewarded(uuid, job, rank.ordinal())) {
                newRanks.add(rank);
                JobSettings.RankReward rankReward = settings.rankRewards().get(rank);
                if (rankReward != null && rankReward.money() > 0) {
                    economy.deposit(uuid, rankReward.money(), "EterJob · rang " + rank.id());
                }
            }
        }
        repository.log(new JobRepository.Entry(uuid, job, JobQuests.kind(quest), JobQuests.tier(quest).id(), paid, xp, server,
                System.currentTimeMillis()));
        perksCache.remove(uuid);
        return new Delivered(job, quest, paid, capped, xp, levelBefore, levelAfter, streak, newRanks, dailyDone);
    }

    /** Thread principal : messages, sons, niveaux, rangs, événements. */
    private void delivered(Player player, Delivered result) {
        QuestInstance quest = result.quest();
        messages.send(player, JobQuests.isBonus(quest) ? "quest.bonus-done" : JobQuests.isStory(quest) ? "quest.story-done"
                        : JobQuests.isWeekly(quest) ? "quest.weekly-done" : "quest.done",
                "reward", Money.format(result.paid()), "xp", String.valueOf(result.xp()));
        if (result.capped()) {
            messages.send(player, "quest.capped", "cap", Money.format(settings.dailyMoneyCap()));
        }
        if (result.dailyDone() && result.streak() > 1) {
            messages.send(player, "quest.streak", "days", String.valueOf(result.streak()),
                    "bonus", String.valueOf(Math.round(Math.min(settings.streakMax(), result.streak() * settings.streakBonus()) * 100)));
        }
        if (result.dailyDone() && settings.bonusQuest()) {
            messages.send(player, "quest.bonus-unlocked");
        }
        player.playSound(player, Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.3f);
        Bukkit.getPluginManager().callEvent(new JobQuestCompleteEvent(player, result.job(), JobQuests.tier(quest).id(),
                JobQuests.isBonus(quest), JobQuests.kind(quest)));
        if (result.levelAfter() > result.levelBefore()) {
            levelUp(player, result);
        }
        for (Rank rank : result.newRanks()) {
            rankUp(player, result.job(), rank);
        }
        // La bonus, l'étape d'histoire suivante... : tirées à la relecture
        progress.reload(player);
    }

    private void levelUp(Player player, Delivered result) {
        Rank rank = levels().rank(result.levelAfter());
        boolean newRank = !result.newRanks().isEmpty();
        if (!newRank) {
            player.showTitle(Title.title(messages.get(player, "level.title", "level", String.valueOf(result.levelAfter())),
                    messages.get(player, "level.subtitle", "job", jobName(player, result.job())),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(2), Duration.ofMillis(600))));
            player.playSound(player, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
        }
        messages.send(player, "level.up", "level", String.valueOf(result.levelAfter()), "job", jobName(player, result.job()));
        Bukkit.getPluginManager().callEvent(new JobLevelUpEvent(player, result.job(), result.levelAfter(), rank.id(), newRank));
    }

    /** Un rang atteint : titre en grand, récompense (argent déjà versé, objets ici ou en boîte), annonce réseau du maître. */
    private void rankUp(Player player, String job, Rank rank) {
        player.showTitle(Title.title(messages.get(player, "rank.up-title", "rank", rankName(player, rank)),
                messages.get(player, "rank.up-subtitle", "job", jobName(player, job)),
                Title.Times.times(Duration.ofMillis(400), Duration.ofSeconds(3), Duration.ofMillis(800))));
        player.playSound(player, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        player.playSound(player, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 0.8f, 1f);
        JobSettings.RankReward reward = settings.rankRewards().get(rank);
        messages.send(player, "rank.up", "rank", rankName(player, rank), "job", jobName(player, job),
                "money", Money.format(reward == null ? 0 : reward.money()));
        if (reward != null && !reward.items().isEmpty()) {
            List<ItemStack> left = new ArrayList<>();
            for (Map.Entry<Material, Integer> item : reward.items()) {
                left.addAll(player.getInventory().addItem(ItemStack.of(item.getKey(), item.getValue())).values());
            }
            if (!left.isEmpty()) {
                UUID uuid = player.getUniqueId();
                String text = String.join(";", left.stream().map(stack -> stack.getType().name() + " " + stack.getAmount()).toList());
                Tasks.async(plugin, () -> quests.deposit(uuid, text), "Récompense de rang non mise de côté");
                messages.send(player, "box.added");
            }
        }
        if (rank == Rank.MAITRE && settings.announceMaster()) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                messages.send(online, "rank.master-announce", "player", player.getName(), "job", jobName(online, job));
            }
            progress.announceMaster(player.getName(), job);
        }
    }

    // ---------- Changer une quête ----------

    /** Thread principal : changer une quête du jour pas encore faite contre une autre du même niveau (une fois par jour). */
    public void reroll(Player player, QuestInstance quest, Runnable after) {
        if (!settings.rerollEnabled() || quest.done() || !JobQuests.isDaily(quest)) {
            return;
        }
        EconomyApi economy = EconomyApi.get().orElse(null);
        UUID uuid = player.getUniqueId();
        long today = today();
        Tasks.async(plugin, player, () -> {
            Optional<Member> member = repository.member(uuid);
            if (member.isEmpty() || JobQuests.day(quest) != today) {
                return "quest.reroll-old";
            }
            JobLevel jobLevel = repository.level(uuid, member.get().job());
            int level = levels().level(jobLevel.xp());
            Rank rank = levels().rank(level);
            double cost = rank.atLeast(settings.rerollFreeRank()) ? 0 : settings.rerollCost();
            Set<String> taken = new HashSet<>(quests.instances(uuid, JobQuests.OWNER).stream().map(QuestInstance::quest).toList());
            Tier tier = JobQuests.tier(quest);
            List<Template> choices = catalog.of(member.get().job()).of(QuestCatalog.Kind.DAILY).stream()
                    .filter(t -> t.tier() == tier && rank.atLeast(t.minRank()) && !taken.contains(t.id()) && quests.canStart(uuid, t.quest()))
                    .toList();
            if (choices.isEmpty()) {
                return "quest.reroll-none";
            }
            if (!repository.claimReroll(uuid, today)) {
                return "quest.reroll-used";
            }
            if (cost > 0 && (economy == null || !economy.withdraw(uuid, cost, "EterJob · changement de quête"))) {
                repository.releaseReroll(uuid, today);
                return "quest.reroll-not-enough";
            }
            Template replacement = choices.get(ThreadLocalRandom.current().nextInt(choices.size()));
            Map<String, String> meta = meta(quest.slot(), replacement, rank, level, false, today, member.get().job());
            if (quests.replaceIfActive(uuid, JobQuests.OWNER, quest.slot(), quest.token(), replacement.id(), levels().scale(rank),
                    endOfDay(today), meta).isEmpty()) {
                if (cost > 0) {
                    economy.deposit(uuid, cost, "EterJob · changement de quête (remboursé)");
                }
                repository.releaseReroll(uuid, today);
                return "quest.reroll-done";
            }
            return "quest.rerolled";
        }, result -> {
            messages.send(player, result, "price", Money.format(settings.rerollCost()));
            if (result.equals("quest.rerolled")) {
                player.playSound(player, Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 1f);
                progress.reload(player);
            }
            after.run();
        }, () -> messages.send(player, "error.generic"));
    }

    // ---------- Boîte de récupération (celle d'EterQuest) ----------

    public void takeBox(Player player, Runnable after) {
        quests.takeBox(player, after);
    }

    // ---------- Évènements, métier le moins pratiqué, avantages ----------

    /** Le multiplicateur des évènements en cours pour ce métier (le plus grand). */
    public double eventMultiplier(String job) {
        long now = System.currentTimeMillis();
        return events.stream().filter(event -> event.until() > now && (event.job() == null || event.job().equals(job)))
                .mapToDouble(JobRepository.Event::multiplier).max().orElse(1);
    }

    public List<JobRepository.Event> events() {
        long now = System.currentTimeMillis();
        return events.stream().filter(event -> event.until() > now).toList();
    }

    /** Bloquant. */
    public void refreshEvents() {
        events = repository.events();
    }

    /** Bloquant : le métier le moins pratiqué des 7 derniers jours (sa récompense est plus haute). */
    private void refreshWeakest() {
        Map<String, Integer> counts = repository.completedByJob(System.currentTimeMillis() - 7L * 86_400_000L);
        weakestJob = settings.icons().keySet().stream().min((a, b) -> Integer.compare(counts.getOrDefault(a, 0), counts.getOrDefault(b, 0)))
                .orElse(null);
    }

    public String weakestJob() {
        return weakestJob;
    }

    /** Avantages du joueur (API, EterResource) : métier actuel et rang, gardés 30 s. Bloquant. */
    public JobPerks perks(UUID player) {
        return cached(player).perks();
    }

    /** Métier du joueur (API), gardé 30 s. Bloquant. */
    public Optional<String> jobOf(UUID player) {
        return cached(player).job();
    }

    private CachedPerks cached(UUID player) {
        CachedPerks cached = perksCache.get(player);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.at() < CACHE_MILLIS) {
            return cached;
        }
        Optional<String> job = repository.member(player).map(Member::job).filter(settings::exists);
        JobPerks perks = job.map(found -> settings.perks(found, levels().rank(levels().level(repository.level(player, found).xp()))))
                .orElse(JobPerks.NONE);
        CachedPerks fresh = new CachedPerks(job, perks, now);
        perksCache.put(player, fresh);
        return fresh;
    }

    /** Pour les menus : un consommateur qui ne fait rien. */
    static <T> Consumer<T> nothing() {
        return value -> { };
    }
}
