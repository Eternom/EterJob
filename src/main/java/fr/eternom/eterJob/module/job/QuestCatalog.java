package fr.eternom.eterJob.module.job;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Le répertoire des quêtes, un fichier par métier (plugins/EterJob/quests/<métier>.yml, copié s'il manque) : quêtes du
 * jour, de la semaine, rares, et l'histoire (une étape par rang). Relu par /eterjob reload. Au chargement, un rapport
 * par métier et un avertissement par objectif invalide ou niveau vide.
 */
public final class QuestCatalog {

    /** Ce qu'une quête du répertoire est. */
    public enum Kind { DAILY, WEEKLY, RARE, STORY }

    /**
     * Une quête possible. xp : XP de métier (0 : celle du niveau, config) ; minRank : tirée à partir de ce rang ;
     * story : le rang de l'étape d'histoire, son titre et son texte (lore).
     */
    public record Template(Kind kind, Tier tier, double reward, int xp, Rank minRank, List<Objective> objectives,
                           Rank story, String title, List<String> text) {

        public Template {
            objectives = List.copyOf(objectives);
            text = List.copyOf(text);
        }

        /** Clé de la quête (ses objectifs), pour ne pas tirer deux fois la même. */
        public String key() {
            return Objective.format(objectives);
        }
    }

    /** Les quêtes d'un métier, par sorte. */
    public record JobQuests(Map<Kind, List<Template>> byKind) {

        public List<Template> of(Kind kind) {
            return byKind.getOrDefault(kind, List.of());
        }

        /** L'étape d'histoire de ce rang, s'il y en a une. */
        public java.util.Optional<Template> story(Rank rank) {
            return of(Kind.STORY).stream().filter(template -> template.story() == rank).findFirst();
        }
    }

    private final Map<String, JobQuests> byJob;

    private QuestCatalog(Map<String, JobQuests> byJob) {
        this.byJob = byJob;
    }

    public JobQuests of(String job) {
        return byJob.getOrDefault(job, new JobQuests(Map.of()));
    }

    public static QuestCatalog load(JavaPlugin plugin, Set<String> jobs) {
        File folder = new File(plugin.getDataFolder(), "quests");
        Logger logger = plugin.getLogger();
        Map<String, JobQuests> byJob = new LinkedHashMap<>();
        for (String job : jobs) {
            File file = new File(folder, job + ".yml");
            if (!file.exists() && plugin.getResource("quests/" + job + ".yml") != null) {
                plugin.saveResource("quests/" + job + ".yml", false);
            }
            if (!file.exists()) {
                logger.warning("quests/" + job + ".yml absent : le métier " + job + " n'a aucune quête");
                continue;
            }
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            Map<Kind, List<Template>> byKind = new EnumMap<>(Kind.class);
            for (Kind kind : Kind.values()) {
                byKind.put(kind, read(yaml, kind, "quests/" + job + ".yml", logger));
            }
            JobQuests quests = new JobQuests(byKind);
            byJob.put(job, quests);
            report(job, quests, logger);
        }
        return new QuestCatalog(byJob);
    }

    private static List<Template> read(YamlConfiguration yaml, Kind kind, String where, Logger logger) {
        List<Template> templates = new ArrayList<>();
        int index = 0;
        for (Map<?, ?> raw : yaml.getMapList(kind.name().toLowerCase(java.util.Locale.ROOT))) {
            index++;
            ConfigurationSection entry = new YamlConfiguration().createSection("q", raw);
            String place = where + " > " + kind.name().toLowerCase(java.util.Locale.ROOT) + " n°" + index;
            List<Objective> objectives = new ArrayList<>();
            for (String text : entry.getStringList("objectives")) {
                var objective = Objective.parseOne(text);
                if (objective.isEmpty() || !ObjectiveTargets.valid(objective.get())) {
                    logger.warning(place + " : objectif invalide ignoré : " + text);
                } else {
                    objectives.add(objective.get());
                }
            }
            objectives = Objective.merge(objectives);
            long actions = objectives.stream().filter(objective -> objective.kind().isAction()).count();
            if (objectives.isEmpty()) {
                logger.warning(place + " : aucun objectif valable, quête ignorée");
                continue;
            }
            if (actions > JobRepository.MAX_ACTIONS) {
                logger.warning(place + " : plus de " + JobRepository.MAX_ACTIONS + " actions, quête ignorée");
                continue;
            }
            Tier tier = Tier.of(entry.getString("tier", kind == Kind.WEEKLY || kind == Kind.STORY ? "hard" : "normal"));
            Rank minRank = Rank.of(entry.getString("min-rank", "apprenti"));
            Rank story = kind == Kind.STORY ? Rank.of(entry.getString("rank")) : null;
            if (tier == null || minRank == null || kind == Kind.STORY && story == null) {
                logger.warning(place + " : niveau ou rang inconnu, quête ignorée");
                continue;
            }
            templates.add(new Template(kind, tier, Math.max(0, entry.getDouble("reward")), Math.max(0, entry.getInt("xp")), minRank,
                    objectives, story, entry.getString("title", ""), entry.getStringList("text")));
        }
        return List.copyOf(templates);
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
