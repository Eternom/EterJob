package fr.eternom.eterJob;

import fr.eternom.eterJob.api.JobApi;
import fr.eternom.eterJob.module.admin.AdminCommand;
import fr.eternom.eterJob.module.job.JobApiService;
import fr.eternom.eterJob.module.job.JobProgressListener;
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
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/**
 * La guilde des métiers du réseau (ancien EterMarket, sans boutiques ni hôtel des ventes) : un métier par joueur, ses
 * quêtes du jour, de la semaine et son histoire, des niveaux et des rangs par métier (gardés quand on en change), et les
 * avantages du rang dans le monde ressource (appliqués par EterResource). Les PNJ sont ceux d'EterNpc : EterJob déclare
 * ses actions (eterjob:guild, eterjob:quests <métier>...) dans EterLib.
 */
public final class Main extends JavaPlugin {

    private static final String REQUIRED_ETERLIB = "1.22.0";
    private static final String TABLE_PREFIX = "eterjob_";

    private Messages messages;
    private JobService service;

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
        JobRepository repository = new JobRepository(lib.database(TABLE_PREFIX));
        cleanupMarket(repository);
        JobSettings settings = JobSettings.load(getConfig(), getLogger());
        service = new JobService(this, repository, settings, QuestCatalog.load(this, settings.icons().keySet()), messages, zone());
        NetworkBus bus = lib.network(this, "eterjob", messages);
        bus.on("reload", data -> reloadLocal());
        bus.on("events", data -> Tasks.async(this, service::refreshEvents, "Évènements des métiers non relus"));

        JobGui gui = new JobGui(this, service, lib.backButton(getConfig().getString("menus.back-command", "")));
        JobActions.register(this, service, gui);
        AdminCommand admin = new AdminCommand(service, bus, this::reloadLocal);
        bus.on("event-start", data -> admin.announceEvent(data.get("job").getAsString(), data.get("multiplier").getAsDouble(),
                data.get("minutes").getAsLong()));
        register("job", new JobCommand(service, gui));
        register("eterjob", admin);

        getServer().getServicesManager().register(JobApi.class, new JobApiService(service), this, ServicePriority.Normal);
        getServer().getPluginManager().registerEvents(new JobProgressListener(service), this);
        // Marqueur « ! » au-dessus du donneur de quêtes : seulement si EterNpc tourne ici
        NpcMarkers markers = getServer().getPluginManager().isPluginEnabled("EterNpc") ? NpcMarkers.create() : null;
        service.progress().start(bus, markers);
        service.start();
    }

    @Override
    public void onDisable() {
        if (service != null) {
            service.progress().stop();
        }
    }

    /** /eterjob reload (ici, ou reçu d'un autre serveur). */
    private void reloadLocal() {
        reloadConfig();
        JobSettings settings = JobSettings.load(getConfig(), getLogger());
        service.reload(settings, QuestCatalog.load(this, settings.icons().keySet()));
        getServer().getOnlinePlayers().forEach(service.progress()::reload);
    }

    /**
     * EterJob 2.0.0 : nettoyage UNIQUE de l'ancien EterMarket (boutiques, hôtel des ventes, stock, anciens métiers :
     * tables etermarket_*), et des emplacements de ses PNJ chez EterNpc (etermarket:*). Pas de reprise des données.
     * Retiré en 2.0.1.
     */
    private void cleanupMarket(JobRepository repository) {
        List<String> dropped = repository.dropMarketTables();
        if (!dropped.isEmpty()) {
            getLogger().warning("Nettoyage d'EterMarket : tables supprimées " + dropped);
        }
        if (getServer().getPluginManager().isPluginEnabled("EterNpc")) {
            Tasks.async(this, () -> {
                int removed = MarketNpcCleanup.removeMarketPlacements();
                if (removed > 0) {
                    getLogger().warning("Nettoyage d'EterMarket : " + removed + " emplacement(s) de PNJ etermarket:* supprimés");
                }
            }, "Emplacements des PNJ d'EterMarket non supprimés");
        }
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
