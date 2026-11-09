package fr.eternom.eterJob.api;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/**
 * Une quête de métier vient d'être validée (thread principal), une seule fois par quête : la base l'a déjà enregistrée
 * et la récompense est payée. Pour les autres plugins (XP du pass d'EterReward, succès d'EterSuccess...).
 * <pre>
 *     // compileOnly("com.github.Eternom:EterJob:&lt;tag&gt;") ; plugin.yml : softdepend: [EterJob]
 *     // Écouteur dans une classe à part, enregistrée seulement si EterJob est présent
 * </pre>
 */
public class JobQuestCompleteEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String job;
    private final String tier;
    private final boolean bonus;
    private final String kind;

    public JobQuestCompleteEvent(Player player, String job, String tier, boolean bonus, String kind) {
        super(player);
        this.job = job;
        this.tier = tier;
        this.bonus = bonus;
        this.kind = kind;
    }

    /** Métier du joueur (identifiant de config.yml > jobs.list). */
    public String getJob() {
        return job;
    }

    /** Difficulté : easy, normal ou hard. */
    public String getTier() {
        return tier;
    }

    /** true pour la quête bonus (celle qui s'ouvre une fois les quêtes du jour finies). */
    public boolean isBonus() {
        return bonus;
    }

    /** Sorte de quête : daily, bonus, weekly, story (et rare pour une quête du jour rare). */
    public String getKind() {
        return kind;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
