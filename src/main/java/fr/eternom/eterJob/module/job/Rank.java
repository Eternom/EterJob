package fr.eternom.eterJob.module.job;

import java.util.Locale;

/**
 * Les rangs d'un métier, atteints par niveau (config.yml > ranks : niveau de chaque rang). Le nom affiché est dans lang/
 * (rank.<id>, et rank.title.<id> : « Maître mineur »).
 */
public enum Rank {
    APPRENTI, COMPAGNON, ARTISAN, EXPERT, MAITRE;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** apprenti ... maitre ; null si inconnu. */
    public static Rank of(String id) {
        if (id == null) {
            return null;
        }
        try {
            return valueOf(id.trim().toUpperCase(Locale.ROOT).replace("Î", "I"));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public boolean atLeast(Rank other) {
        return ordinal() >= other.ordinal();
    }

    public Rank next() {
        return this == MAITRE ? null : values()[ordinal() + 1];
    }
}
