package fr.eternom.eterJob.module.job;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LevelsTest {

    private final Levels levels = Levels.DEFAULT;

    @Test
    void xpCurveGrowsByLevel() {
        assertEquals(0, levels.xpFor(1));
        assertEquals(100, levels.xpFor(2));
        assertEquals(100 + 120, levels.xpFor(3));
        // Niveau 10 : 9 × 100 + 20 × 9 × 8 / 2
        assertEquals(900 + 720, levels.xpFor(10));
        assertEquals(levels.xpFor(50), levels.xpFor(80));
    }

    @Test
    void levelFromXp() {
        assertEquals(1, levels.level(0));
        assertEquals(1, levels.level(99));
        assertEquals(2, levels.level(100));
        assertEquals(10, levels.level(levels.xpFor(10)));
        assertEquals(50, levels.level(Integer.MAX_VALUE / 2));
    }

    @Test
    void progressInsideTheLevel() {
        assertArrayEquals(new int[]{50, 100}, levels.progress(50));
        assertArrayEquals(new int[]{0, 120}, levels.progress(100));
        assertArrayEquals(new int[]{0, 0}, levels.progress(levels.xpFor(50)));
    }

    @Test
    void ranksByLevel() {
        assertEquals(Rank.APPRENTI, levels.rank(1));
        assertEquals(Rank.APPRENTI, levels.rank(9));
        assertEquals(Rank.COMPAGNON, levels.rank(10));
        assertEquals(Rank.ARTISAN, levels.rank(20));
        assertEquals(Rank.EXPERT, levels.rank(49));
        assertEquals(Rank.MAITRE, levels.rank(50));
        assertTrue(Rank.MAITRE.atLeast(Rank.ARTISAN));
        assertEquals(Rank.COMPAGNON, Rank.APPRENTI.next());
    }

    @Test
    void rewardBonusIsCapped() {
        assertEquals(1, levels.rewardMultiplier(1), 1e-9);
        assertEquals(1.045, levels.rewardMultiplier(10), 1e-9);
        assertEquals(1.245, levels.rewardMultiplier(50), 1e-9);
        Levels capped = new Levels(100, 100, 20, Map.of(), Map.of(), 0.01, 0.25);
        assertEquals(1.25, capped.rewardMultiplier(100), 1e-9);
    }

    @Test
    void missingRanksTakeDefaults() {
        Levels partial = new Levels(50, 100, 20, Map.of(Rank.COMPAGNON, 5), Map.of(), 0, 0);
        assertEquals(Rank.COMPAGNON, partial.rank(5));
        assertEquals(Rank.ARTISAN, partial.rank(20));
        assertEquals(1.0, partial.scale(Rank.MAITRE), 1e-9);
    }

}
