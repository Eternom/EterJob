package fr.eternom.eterJob.module.job;

import com.google.gson.JsonObject;
import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.cache.NetworkBus;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterQuest.api.QuestInstance;
import fr.eternom.eterQuest.model.Quest;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ce qu'EterJob suit des joueurs connectés : métier, niveau et rang (conditions, étiquettes <tag_job> et
 * <tag_job_rank>), marqueur « ! » au-dessus du donneur de quêtes quand une quête est prête à lui rendre, nouvelles
 * quêtes à minuit, annonce d'un nouveau maître. La progression des quêtes, la barre de boss, la sidebar et les rappels
 * sont ceux d'EterQuest.
 */
public class JobProgress implements Listener {

    private static final long MINUTE_TICKS = 60 * 20;
    private static final long MARKER_TICKS = 5 * 20;
    private static final String JOB_TAG = "job";
    private static final String RANK_TAG = "job_rank";

    /** Métier, niveau et rang d'un joueur connecté. */
    private record Active(String job, int level, Rank rank) {
    }

    private final JavaPlugin plugin;
    private final JobService service;
    private final Messages messages;
    private final Map<UUID, Active> active = new HashMap<>();
    private NetworkBus bus;
    private NpcMarkers markers;
    private long lastDay;

    JobProgress(JavaPlugin plugin, JobService service, Messages messages) {
        this.plugin = plugin;
        this.service = service;
        this.messages = messages;
    }

    public void start(NetworkBus bus, NpcMarkers markers) {
        this.bus = bus;
        this.markers = markers;
        this.lastDay = service.today();
        bus.on("master", data -> Bukkit.getOnlinePlayers().forEach(player -> messages.send(player, "rank.master-announce",
                "player", data.get("player").getAsString(), "job", service.jobName(player, data.get("job").getAsString()))));
        Bukkit.getScheduler().runTaskTimer(plugin, this::minute, MINUTE_TICKS, MINUTE_TICKS);
        Bukkit.getScheduler().runTaskTimer(plugin, this::refreshMarkers, MARKER_TICKS, MARKER_TICKS);
        Bukkit.getOnlinePlayers().forEach(this::join); // /reload
    }

    // ---------- Connexion ----------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        join(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        active.remove(event.getPlayer().getUniqueId());
    }

    /** Relit le métier et tire les quêtes qu'il faut ; paie les quêtes finies ailleurs sans l'être. */
    public void join(Player player) {
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, player, () -> service.board(uuid).orElse(null), board -> {
            if (board == null) {
                active.remove(uuid);
            } else {
                int level = service.levels().level(board.level().xp());
                active.put(uuid, new Active(board.job(), level, service.levels().rank(level)));
            }
            service.quests().refresh(player);
            publishJob(player);
            service.claimPending(player);
        }, () -> { });
    }

    /** Thread principal : métier ou quêtes changés, on relit tout. */
    public void reload(Player player) {
        join(player);
    }

    /** Les quêtes de métier connues ici (progression à jour). */
    public List<QuestInstance> quests(Player player) {
        return service.quests().live(player, JobQuests.OWNER);
    }

    // ---------- Chaque minute : nouveau jour, étiquettes ----------

    private void minute() {
        long today = service.today();
        boolean newDay = today != lastDay;
        lastDay = today;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (newDay && active.containsKey(player.getUniqueId())) {
                // Minuit : les nouvelles quêtes tout de suite, sans repasser au donneur de quêtes
                join(player);
                if (!service.quests().isQuiet(player)) {
                    messages.send(player, "quest.new-day");
                }
                continue;
            }
            publishJob(player);
        }
    }

    /** Une quête prête à rendre au donneur de quêtes (pas chez un PNJ du village) ? */
    private boolean readyForGiver(Player player, QuestInstance quest) {
        Quest definition = service.quests().quest(quest.quest()).orElse(null);
        return definition != null && definition.stage(quest.stage()).accepts("giver") && service.quests().ready(player, quest);
    }

    /** « ! » au-dessus du donneur de quêtes du métier du joueur quand une quête est prête à lui rendre (EterNpc). */
    private void refreshMarkers() {
        if (markers == null) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            Active state = active.get(player.getUniqueId());
            String npc = state == null ? null : service.settings().questGivers().get(state.job());
            if (npc == null) {
                continue;
            }
            boolean ready = quests(player).stream().anyMatch(quest -> readyForGiver(player, quest));
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
        long now = System.currentTimeMillis();
        long available = quests(player).stream().filter(q -> q.live(now) && (JobQuests.isDaily(q) || JobQuests.isBonus(q))).count();
        String format = messages.raw(player, "job.sidebar");
        String job = MiniMessage.miniMessage().escapeTags(service.jobName(player, state.job()));
        EterLib.get().getPlayerTags().set(player, JOB_TAG, format == null ? "" : format.replace("<job>", job)
                .replace("<count>", String.valueOf(available)).replace("<level>", String.valueOf(state.level())));
        String title = messages.raw(player, "rank.title." + state.rank().id());
        if (title != null) {
            EterLib.get().getPlayerTags().set(player, RANK_TAG, title.replace("<job>", job));
        }
    }

    /** Le joueur a-t-il un métier (connu ici) ? */
    public boolean hasJob(UUID player) {
        return active.containsKey(player);
    }

    public Rank rankOf(UUID player) {
        Active state = active.get(player);
        return state == null ? null : state.rank();
    }

    public String jobOf(UUID player) {
        Active state = active.get(player);
        return state == null ? null : state.job();
    }
}
