package fr.eternom.eterJob.module.job;

import fr.eternom.eterJob.api.JobPerks;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Réglages d'EterJob (config.yml), relus par /eterjob reload.
 *
 * @param icons          métiers : identifiant -> icône (nom affiché : lang/ job.name.<métier>)
 * @param rerollFreeRank changer une quête est gratuit à partir de ce rang
 * @param tierXp         XP de métier par niveau de quête (quand la quête n'en donne pas)
 * @param rareChance     chance qu'une quête du jour soit remplacée par une rare (0,08 = 8 %)
 * @param rankRewards    récompense de chaque rang atteint
 * @param perks          avantages dans le monde ressource : métier -> rang -> avantages (cumulés jusqu'au rang du joueur)
 * @param streakBonus    bonus de récompense par jour de série (toutes les quêtes du jour faites), au plus streakMax
 * @param weakestBonus   bonus du métier le moins pratiqué de la semaine (0,2 = +20 %)
 * @param dailyMoneyCap  gains des quêtes par jour au plus (0 : pas de plafond)
 * @param questGivers    métier -> PNJ donneur de quêtes (marqueur « ! » quand une quête est prête à livrer)
 * @param remoteDeliver  les quêtes sans objet à livrer se valident à distance (/job)
 */
public record JobSettings(Map<String, Material> icons, double changeCost, Duration changeCooldown, List<Tier> daily,
                          boolean bonusQuest, Tier bonusTier, double bonusMultiplier, double rerollCost, Rank rerollFreeRank,
                          Levels levels, Map<Tier, Integer> tierXp, int bonusXp, int weeklyXp, int storyXp, double rareChance,
                          double rareMultiplier, boolean weekly, Map<Rank, RankReward> rankRewards,
                          Map<String, Map<Rank, JobPerks>> perks, double streakBonus, double streakMax, double weakestBonus,
                          double dailyMoneyCap, boolean trialSpawners, boolean fishOnlyFish, int minPlaytimeMinutes,
                          int rateAlertPerMinute, Map<String, String> questGivers, int reminderMinutes, boolean bossBar,
                          boolean autoTrack, boolean remoteDeliver, boolean announceMaster) {

    /** Emplacements des quêtes d'un joueur : 0 à 2 du jour, 3 la bonus, 4 la semaine, 5 l'histoire. */
    public static final int BONUS_SLOT = 3;
    public static final int WEEKLY_SLOT = 4;
    public static final int STORY_SLOT = 5;

    /** Ce que donne un rang atteint : argent et objets. */
    public record RankReward(double money, List<Map.Entry<Material, Integer>> items) {
    }

    public static JobSettings load(ConfigurationSection config, Logger logger) {
        ConfigurationSection jobs = section(config, "jobs");
        Map<String, Material> icons = new LinkedHashMap<>();
        ConfigurationSection list = jobs.getConfigurationSection("list");
        if (list != null) {
            for (String job : list.getKeys(false)) {
                Material icon = Material.matchMaterial(list.getString(job, "PAPER"));
                if (icon == null || !icon.isItem()) {
                    logger.warning("jobs.list." + job + " : icône inconnue, PAPER utilisée");
                    icon = Material.PAPER;
                }
                icons.put(job.toLowerCase(Locale.ROOT), icon);
            }
        }
        List<Tier> daily = new ArrayList<>();
        for (String id : jobs.isList("daily") ? jobs.getStringList("daily") : List.of("easy", "normal", "hard")) {
            Tier tier = Tier.of(id);
            if (tier == null) {
                logger.warning("jobs.daily : niveau inconnu « " + id + " » (easy, normal ou hard)");
            } else if (daily.size() < BONUS_SLOT) {
                daily.add(tier);
            }
        }
        if (daily.isEmpty()) {
            daily = List.of(Tier.EASY, Tier.NORMAL, Tier.HARD);
        }

        ConfigurationSection levels = section(config, "levels");
        Map<Rank, Integer> rankLevels = new EnumMap<>(Rank.class);
        Map<Rank, Double> rankScale = new EnumMap<>(Rank.class);
        Map<Rank, RankReward> rankRewards = new EnumMap<>(Rank.class);
        ConfigurationSection ranks = section(config, "ranks");
        for (Rank rank : Rank.values()) {
            ConfigurationSection entry = ranks.getConfigurationSection(rank.id());
            if (entry == null) {
                continue;
            }
            rankLevels.put(rank, entry.getInt("level", Levels.DEFAULT.levelOf(rank)));
            rankScale.put(rank, entry.getDouble("scale", Levels.DEFAULT.scale(rank)));
            List<Map.Entry<Material, Integer>> items = new ArrayList<>();
            for (String item : entry.getStringList("reward.items")) {
                String[] parts = item.trim().split("\\s+");
                Material material = Material.matchMaterial(parts[0]);
                if (material != null && material.isItem()) {
                    items.add(Map.entry(material, parts.length > 1 ? parseInt(parts[1], 1) : 1));
                } else {
                    logger.warning("ranks." + rank.id() + ".reward.items : objet invalide : " + item);
                }
            }
            rankRewards.put(rank, new RankReward(Math.max(0, entry.getDouble("reward.money")), List.copyOf(items)));
        }
        Levels curve = new Levels(levels.getInt("max", 50), levels.getInt("base-xp", 100), levels.getInt("growth-xp", 20),
                rankLevels, rankScale, levels.getDouble("reward-bonus-per-level", 0.005), levels.getDouble("max-reward-bonus", 0.25));

        Map<Tier, Integer> tierXp = new EnumMap<>(Tier.class);
        ConfigurationSection xp = section(config, "xp");
        tierXp.put(Tier.EASY, xp.getInt("easy", 20));
        tierXp.put(Tier.NORMAL, xp.getInt("normal", 35));
        tierXp.put(Tier.HARD, xp.getInt("hard", 55));

        ConfigurationSection anti = section(config, "anti-cheat");
        ConfigurationSection display = section(config, "display");
        Map<String, String> givers = new HashMap<>();
        ConfigurationSection giverSection = config.getConfigurationSection("quest-givers");
        for (String job : icons.keySet()) {
            givers.put(job, giverSection == null ? "eternpc:" + job : giverSection.getString(job, "eternpc:" + job));
        }

        return new JobSettings(icons,
                Math.max(0, jobs.getDouble("change-cost", 2000)),
                Duration.ofDays(Math.max(0, jobs.getInt("change-cooldown-days", 7))),
                List.copyOf(daily),
                jobs.getBoolean("bonus-quest", true),
                orDefault(Tier.of(jobs.getString("bonus-tier", "normal")), Tier.NORMAL),
                Math.max(1, jobs.getDouble("bonus-multiplier", 1.3)),
                jobs.getDouble("reroll-cost", 150),
                orDefault(Rank.of(jobs.getString("reroll-free-rank", "artisan")), Rank.ARTISAN),
                curve, Map.copyOf(tierXp),
                xp.getInt("bonus", 45), xp.getInt("weekly", 200), xp.getInt("story", 150),
                Math.clamp(jobs.getDouble("rare-chance", 0.08), 0, 1),
                Math.max(1, jobs.getDouble("rare-multiplier", 1.5)),
                jobs.getBoolean("weekly-quest", true),
                Map.copyOf(rankRewards),
                readPerks(config.getConfigurationSection("perks"), icons.keySet(), logger),
                Math.max(0, section(config, "streak").getDouble("bonus-per-day", 0.02)),
                Math.max(0, section(config, "streak").getDouble("max-bonus", 0.2)),
                Math.max(0, jobs.getDouble("weakest-job-bonus", 0.2)),
                Math.max(0, jobs.getDouble("daily-money-cap", 0)),
                anti.getBoolean("count-trial-spawners", false),
                anti.getBoolean("fish-only-fish", true),
                Math.max(0, anti.getInt("min-playtime-minutes", 30)),
                Math.max(0, anti.getInt("break-alert-per-minute", 300)),
                Map.copyOf(givers),
                Math.max(0, display.getInt("ready-reminder-minutes", 60)),
                display.getBoolean("boss-bar", true),
                display.getBoolean("auto-track", true),
                jobs.getBoolean("remote-deliver", true),
                display.getBoolean("announce-master", true));
    }

    /**
     * perks.<métier>.<rang> : effects ["haste:1"], et des valeurs (ore-drop-bonus: 0.3, tree-felling: true...). Les rangs
     * se cumulent : un rang garde les avantages des rangs d'avant (une valeur redonnée remplace la précédente).
     */
    private static Map<String, Map<Rank, JobPerks>> readPerks(ConfigurationSection section, java.util.Set<String> jobs, Logger logger) {
        Map<String, Map<Rank, JobPerks>> perks = new HashMap<>();
        if (section == null) {
            return perks;
        }
        for (String job : section.getKeys(false)) {
            ConfigurationSection byRank = section.getConfigurationSection(job);
            if (byRank == null || !jobs.contains(job)) {
                continue;
            }
            Map<Rank, JobPerks> cumulative = new EnumMap<>(Rank.class);
            Map<String, Integer> effects = new LinkedHashMap<>();
            Map<String, Double> values = new LinkedHashMap<>();
            for (Rank rank : Rank.values()) {
                ConfigurationSection entry = byRank.getConfigurationSection(rank.id());
                if (entry != null) {
                    for (String effect : entry.getStringList("effects")) {
                        String[] parts = effect.split(":");
                        effects.put(parts[0].trim().toLowerCase(Locale.ROOT), parts.length > 1 ? parseInt(parts[1].trim(), 0) : 0);
                    }
                    for (String key : entry.getKeys(false)) {
                        if (key.equals("effects")) {
                            continue;
                        }
                        Object value = entry.get(key);
                        if (value instanceof Boolean flag) {
                            values.put(key, flag ? 1.0 : 0.0);
                        } else if (value instanceof Number number) {
                            values.put(key, number.doubleValue());
                        } else {
                            logger.warning("perks." + job + "." + rank.id() + "." + key + " : valeur ignorée (nombre ou true/false)");
                        }
                    }
                }
                cumulative.put(rank, new JobPerks(job, rank.id(), Map.copyOf(effects), Map.copyOf(values)));
            }
            perks.put(job, cumulative);
        }
        return perks;
    }

    public boolean exists(String job) {
        return icons.containsKey(job);
    }

    public Material icon(String job) {
        return icons.getOrDefault(job, Material.PAPER);
    }

    /** Changer une quête : désactivé si reroll-cost est négatif. */
    public boolean rerollEnabled() {
        return rerollCost >= 0;
    }

    public int xpOf(Tier tier) {
        return tierXp.getOrDefault(tier, 30);
    }

    public JobPerks perks(String job, Rank rank) {
        return perks.getOrDefault(job, Map.of()).getOrDefault(rank, new JobPerks(job, rank.id(), Map.of(), Map.of()));
    }

    private static ConfigurationSection section(ConfigurationSection config, String path) {
        ConfigurationSection section = config.getConfigurationSection(path);
        return section != null ? section : config.createSection(path);
    }

    private static <T> T orDefault(T value, T fallback) {
        return value == null ? fallback : value;
    }

    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
