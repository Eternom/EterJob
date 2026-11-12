package fr.eternom.eterJob;

import fr.eternom.eterJob.api.JobApi;
import fr.eternom.eterJob.module.admin.AdminCommand;
import fr.eternom.eterJob.module.job.JobApiService;
import fr.eternom.eterJob.module.job.JobQuests;
import fr.eternom.eterJob.module.job.JobRepository;
import fr.eternom.eterJob.module.job.JobService;
import fr.eternom.eterJob.module.job.JobSettings;
import fr.eternom.eterJob.module.job.NpcMarkers;
import fr.eternom.eterJob.module.job.QuestCatalog;
import fr.eternom.eterJob.module.menu.JobActions;
import fr.eternom.eterJob.module.menu.JobCommand;
import fr.eternom.eterJob.module.menu.JobGui;
import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.cache.NetworkBus;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterQuest.api.QuestApi;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Objects;

/**
 * La guilde des métiers du réseau (ancien EterMarket, sans boutiques ni hôtel des ventes) : un métier par joueur, ses
 * quêtes du jour, de la semaine et son histoire, des niveaux et des rangs par métier (gardés quand on en change), et les
 * avantages du rang dans le monde ressource (appliqués par EterResource). Les PNJ sont ceux d'EterNpc : EterJob déclare
 * ses actions (eterjob:guild, eterjob:quests <métier>...) dans EterLib.
 */
public final class Main extends JavaPlugin {

    private static final String REQUIRED_ETERLIB = "1.25.0";
    private static final String TABLE_PREFIX = "eterjob_";

    private Messages messages;
    private JobService service;
    private QuestApi quests;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            if (!EterLib.requireVersion(this, REQUIRED_ETERLIB)) {
                return;
            }
        } catch (LinkageError tooOld) {
            getLogger().severe("EterLib " + REQUIRED_ETERLIB + " ou plus récent est nécessaire.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        EterLib lib = EterLib.get();
        messages = lib.messages(this, "en_us", "fr_fr");
        quests = QuestApi.get().orElse(null);
        if (quests == null) {
            getLogger().severe("EterQuest est nécessaire (le moteur des quêtes). Plugin désactivé.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        JobRepository repository = new JobRepository(lib.database(TABLE_PREFIX));
        JobSettings settings = JobSettings.load(getConfig(), getLogger());
        service = new JobService(this, repository, settings, QuestCatalog.load(this, settings.icons().keySet(), quests), messages, zone(),
                quests);
        // Le nom des quêtes de métier sans « name » : « Quête facile », « ✦ Quête rare », « Quête de la semaine »...
        quests.namer(JobQuests.OWNER, (viewer, quest) -> {
            String key = JobQuests.isBonus(quest) ? "quest.item.name-bonus" : JobQuests.isWeekly(quest) ? "quest.item.name-weekly"
                    : JobQuests.isStory(quest) ? "quest.item.name-story" : JobQuests.rare(quest) ? "quest.item.name-rare" : "quest.item.name";
            return messages.get(viewer, key, TagResolver.resolver(Placeholder.component("tier",
                    messages.get(viewer, "quest.tier." + JobQuests.tier(quest).id()))));
        });
        NetworkBus bus = lib.network(this, "eterjob", messages);
        bus.on("reload", data -> reloadLocal());
        bus.on("events", data -> Tasks.async(this, service::refreshEvents, "Évènements des métiers non relus"));

        JobGui gui = new JobGui(this, service, lib.backButton(getConfig().getString("menus.back-command", "")));
        JobActions.register(this, service, gui);
        AdminCommand admin = new AdminCommand(service, bus, this::reloadLocal);
        bus.on("event-start", data -> admin.announceEvent(data.get("job").getAsString(), data.get("multiplier").getAsDouble(),
                data.get("minutes").getAsLong()));
        bus.on("event-soon", data -> admin.announceSoon(data.get("job").getAsString(), data.get("multiplier").getAsDouble(),
                data.get("minutes").getAsLong(), data.get("in").getAsLong()));
        register("job", new JobCommand(service, gui));
        register("eterjob", admin);

        getServer().getServicesManager().register(JobApi.class, new JobApiService(service), this, ServicePriority.Normal);
        getServer().getPluginManager().registerEvents(service, this);
        getServer().getPluginManager().registerEvents(service.progress(), this);
        // Marqueur « ! » au-dessus du donneur de quêtes : seulement si EterNpc tourne ici
        NpcMarkers markers = getServer().getPluginManager().isPluginEnabled("EterNpc") ? NpcMarkers.create() : null;
        service.progress().start(bus, markers);
        service.start();
    }

    @Override
    public void onDisable() {
        // Les progrès sont gardés par EterQuest
    }

    /** /eterjob reload (ici, ou reçu d'un autre serveur). */
    private void reloadLocal() {
        reloadConfig();
        JobSettings settings = JobSettings.load(getConfig(), getLogger());
        Tasks.async(this, () -> {
            QuestCatalog catalog = QuestCatalog.load(this, settings.icons().keySet(), quests);
            getServer().getScheduler().runTask(this, () -> {
                service.reload(settings, catalog);
                getServer().getOnlinePlayers().forEach(service.progress()::reload);
            });
        }, "Quêtes des métiers non relues");
    }

    private void register(String name, TabExecutor executor) {
        PluginCommand command = Objects.requireNonNull(getCommand(name), "Commande absente du plugin.yml : " + name);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    /** Fuseau du changement de jour des quêtes (jobs.time-zone), Europe/Paris si invalide. */
    private ZoneId zone() {
        String zone = getConfig().getString("jobs.time-zone", "Europe/Paris");
        try {
            return ZoneId.of(zone);
        } catch (DateTimeException e) {
            getLogger().warning("jobs.time-zone invalide (" + zone + "), Europe/Paris utilisé");
            return ZoneId.of("Europe/Paris");
        }
    }

    public Messages getMessages() {
        return messages;
    }
}
