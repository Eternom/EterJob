package fr.eternom.eterJob.module.job;

import java.util.EnumMap;
import java.util.Map;

/**
 * Niveaux et rangs d'un métier (logique pure, testée). L'XP pour passer du niveau n au suivant vaut
 * base + growth × (n − 1). Le rang dépend du niveau (rankLevels) ; il agrandit les quêtes (scale : quantités et
 * récompense) ; chaque niveau ajoute rewardPerLevel à la récompense (au plus maxRewardBonus).
 */
public record Levels(int maxLevel, int base, int growth, Map<Rank, Integer> rankLevels, Map<Rank, Double> rankScale,
                     double rewardPerLevel, double maxRewardBonus) {

    private static final int[] DEFAULT_LEVELS = {1, 10, 20, 35, 50};

    public static final Levels DEFAULT = new Levels(50, 100, 20,
            Map.of(Rank.APPRENTI, 1, Rank.COMPAGNON, 10, Rank.ARTISAN, 20, Rank.EXPERT, 35, Rank.MAITRE, 50),
            Map.of(Rank.APPRENTI, 1.0, Rank.COMPAGNON, 1.1, Rank.ARTISAN, 1.2, Rank.EXPERT, 1.35, Rank.MAITRE, 1.5),
            0.005, 0.25);

    public Levels {
        maxLevel = Math.max(1, maxLevel);
        base = Math.max(1, base);
        growth = Math.max(0, growth);
        Map<Rank, Integer> levels = new EnumMap<>(Rank.class);
        Map<Rank, Double> scales = new EnumMap<>(Rank.class);
        for (Rank rank : Rank.values()) {
            levels.put(rank, Math.clamp(rankLevels.getOrDefault(rank, DEFAULT_LEVELS[rank.ordinal()]), 1, maxLevel));
            scales.put(rank, Math.max(0.1, rankScale.getOrDefault(rank, 1.0)));
        }
        rankLevels = Map.copyOf(levels);
        rankScale = Map.copyOf(scales);
        rewardPerLevel = Math.max(0, rewardPerLevel);
        maxRewardBonus = Math.max(0, maxRewardBonus);
    }

    /** XP totale pour atteindre ce niveau (niveau 1 : 0). */
    public int xpFor(int level) {
        int n = Math.clamp(level, 1, maxLevel) - 1;
        return n * base + growth * n * (n - 1) / 2;
    }

    /** Niveau atteint avec cette XP (1 à maxLevel). */
    public int level(int xp) {
        int level = 1;
        while (level < maxLevel && xp >= xpFor(level + 1)) {
            level++;
        }
        return level;
    }

    /** XP dans le niveau en cours et XP du niveau entier ; {0, 0} au niveau maximum. */
    public int[] progress(int xp) {
        int level = level(xp);
        if (level >= maxLevel) {
            return new int[]{0, 0};
        }
        int from = xpFor(level);
        return new int[]{xp - from, xpFor(level + 1) - from};
    }

    /** Le rang de ce niveau : le plus haut dont le niveau est atteint. */
    public Rank rank(int level) {
        Rank rank = Rank.APPRENTI;
        for (Rank candidate : Rank.values()) {
            if (level >= rankLevels.get(candidate)) {
                rank = candidate;
            }
        }
        return rank;
    }

    public int levelOf(Rank rank) {
        return rankLevels.get(rank);
    }

    /** Multiplicateur de récompense de ce niveau. */
    public double rewardMultiplier(int level) {
        return 1 + Math.min(maxRewardBonus, (Math.max(1, level) - 1) * rewardPerLevel);
    }

    /** Agrandissement des quêtes à ce rang (quantités et récompense). */
    public double scale(Rank rank) {
        return rankScale.get(rank);
    }
}
