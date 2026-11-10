package fr.eternom.eterJob.module.job;

import fr.eternom.eterQuest.api.QuestApi;
import fr.eternom.eterQuest.model.Quest;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Le répertoire des quêtes de métier : un fichier par métier, plugins/EterJob/quests/<métier>.yml (copié s'il manque),
 * au format d'EterQuest (QuestParser), envoyé à tout le réseau par EterQuest. Ce qu'EterJob lit de chaque quête (meta) :
 * kind (daily, weekly, rare, story), tier (easy, normal, hard), reward, xp, min-rank (tirée à partir de ce rang), rank
 * (l'étape d'histoire de ce rang). Un ancien fichier (EterJob 2.x) est mis de côté (.old) et remplacé.
 */
public final class QuestCatalog {

    /** Ce qu'une quête du répertoire est. */
    public enum Kind { DAILY, WEEKLY, RARE, STORY }

    /** Une quête possible : la quête d'EterQuest et ce qu'EterJob en lit. */
    public record Template(Quest quest, Kind kind, Tier tier, double reward, int xp, Rank minRank, Rank story) {

        public String id() {
            return quest.id();
        }
    }

    /** Les quêtes d'un métier, par sorte. */
    public record JobQuests(Map<Kind, List<Template>> byKind) {

        public List<Template> of(Kind kind) {
            return byKind.getOrDefault(kind, List.of());
        }

        /** L'étape d'histoire de ce rang, s'il y en a une. */
        public Optional<Template> story(Rank rank) {
            return of(Kind.STORY).stream().filter(template -> template.story() == rank).findFirst();
        }
    }

    private final Map<String, JobQuests> byJob;
    private final Map<String, Template> byId;

    private QuestCatalog(Map<String, JobQuests> byJob) {
        this.byJob = byJob;
        Map<String, Template> ids = new LinkedHashMap<>();
        byJob.values().forEach(quests -> quests.byKind().values().forEach(list -> list.forEach(t -> ids.put(t.id(), t))));
        this.byId = ids;
    }

    public JobQuests of(String job) {
        return byJob.getOrDefault(job, new JobQuests(Map.of()));
    }

    public Optional<Template> template(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** Bloquant (EterQuest envoie les quêtes en base). */
    public static QuestCatalog load(JavaPlugin plugin, Set<String> jobs, QuestApi api) {
        File folder = new File(plugin.getDataFolder(), "quests");
        Logger logger = plugin.getLogger();
        for (String job : jobs) {
            File file = new File(folder, job + ".yml");
            if (file.exists() && isOldFormat(file)) {
                File old = new File(folder, job + ".yml.old");
                if (old.exists() && !old.delete() || !file.renameTo(old)) {
                    logger.warning("quests/" + job + ".yml : ancien format (EterJob 2.x), à remplacer à la main");
                } else {
                    logger.warning("quests/" + job + ".yml : ancien format (EterJob 2.x) mis de côté en " + old.getName()
                            + ", remplacé par les quêtes d'EterQuest");
                }
            }
            if (!file.exists() && plugin.getResource("quests/" + job + ".yml") != null) {
                plugin.saveResource("quests/" + job + ".yml", false);
            }
        }
        List<Quest> quests = api.register(plugin, folder);
        Map<String, Map<Kind, List<Template>>> grouped = new LinkedHashMap<>();
        for (Quest local : quests) {
            // La quête en vigueur sur le réseau (une version plus récente ailleurs l'emporte)
            Quest quest = api.quest(local.id()).orElse(local);
            String job = quest.meta("job", "").toLowerCase(Locale.ROOT);
            if (!jobs.contains(job)) {
                logger.warning("Quête " + quest.id() + " : meta.job inconnu « " + job + " », ignorée par la guilde");
                continue;
            }
            Kind kind = kind(quest.meta("kind", ""));
            Tier tier = Tier.of(quest.meta("tier", kind == Kind.WEEKLY || kind == Kind.STORY ? "hard" : "normal"));
            Rank minRank = Rank.of(quest.meta("min-rank", "apprenti"));
            Rank story = kind == Kind.STORY ? Rank.of(quest.meta("rank", "")) : null;
            if (kind == null || tier == null || minRank == null || kind == Kind.STORY && story == null) {
                logger.warning("Quête " + quest.id() + " : meta kind / tier / min-rank / rank invalide, ignorée par la guilde");
                continue;
            }
            grouped.computeIfAbsent(job, key -> new EnumMap<>(Kind.class)).computeIfAbsent(kind, key -> new ArrayList<>())
                    .add(new Template(quest, kind, tier, Math.max(0, quest.metaNumber("reward", 0)), (int) Math.max(0, quest.metaNumber("xp", 0)),
                            minRank, story));
        }
        Map<String, JobQuests> byJob = new LinkedHashMap<>();
        grouped.forEach((job, byKind) -> {
            Map<Kind, List<Template>> frozen = new EnumMap<>(Kind.class);
            byKind.forEach((kind, list) -> frozen.put(kind, List.copyOf(list)));
            byJob.put(job, new JobQuests(frozen));
        });
        for (String job : jobs) {
            report(job, byJob.getOrDefault(job, new JobQuests(Map.of())), logger);
        }
        return new QuestCatalog(byJob);
    }

    private static Kind kind(String text) {
        try {
            return Kind.valueOf(text.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Un fichier d'EterJob 2.x : des listes daily / weekly / rare / story, sans « quests ». */
    private static boolean isOldFormat(File file) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        return !yaml.contains("quests") && (yaml.isList("daily") || yaml.isList("weekly"));
    }

    private static void report(String job, JobQuests quests, Logger logger) {
        for (Tier tier : Tier.values()) {
            if (quests.of(Kind.DAILY).stream().noneMatch(template -> template.tier() == tier)) {
                logger.warning("quests/" + job + ".yml : aucune quête du jour de niveau " + tier.id());
            }
        }
        logger.info("Métier " + job + " : " + quests.of(Kind.DAILY).size() + " quêtes du jour, " + quests.of(Kind.WEEKLY).size()
                + " de la semaine, " + quests.of(Kind.RARE).size() + " rares, " + quests.of(Kind.STORY).size() + " étapes d'histoire");
    }
}
