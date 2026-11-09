package fr.eternom.eterJob.module.job;

import java.util.Locale;

/** Difficulté d'une quête. Le nom affiché est dans lang/ (quest.tier.<id>). */
public enum Tier {
    EASY, NORMAL, HARD;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** easy / normal / hard ; null si inconnu. */
    public static Tier of(String id) {
        if (id == null) {
            return null;
        }
        try {
            return valueOf(id.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
