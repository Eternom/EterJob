package fr.eternom.eterJob.api;

import java.util.Map;

/**
 * Les avantages d'un joueur dans le monde ressource, selon son métier et son rang (config.yml d'EterJob > perks, cumulés
 * jusqu'à son rang). EterResource les applique, seulement dans le monde ressource.
 *
 * @param effects effets de potion : nom Minecraft (haste, night_vision...) -> amplificateur (0 = niveau I)
 * @param values  valeurs : tree-felling, crop-multiplier, mob-drop-bonus, ore-drop-bonus, vein-mining (blocs au plus),
 *                auto-smelt, replant, sapling-replant, apple-chance, hoe-area, treasure-bonus, lure, xp-multiplier,
 *                head-chance... (un oui/non vaut 1 ou 0)
 */
public record JobPerks(String job, String rank, Map<String, Integer> effects, Map<String, Double> values) {

    public static final JobPerks NONE = new JobPerks("", "", Map.of(), Map.of());

    public double value(String key) {
        return values.getOrDefault(key, 0.0);
    }

    public boolean has(String key) {
        return value(key) > 0;
    }

    public boolean isEmpty() {
        return effects.isEmpty() && values.isEmpty();
    }
}
