package fr.eternom.eterJob.api;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/** Un joueur vient de gagner un niveau de métier (thread principal), avec son rang (et s'il vient d'en changer). */
public class JobLevelUpEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String job;
    private final int level;
    private final String rank;
    private final boolean newRank;

    public JobLevelUpEvent(Player player, String job, int level, String rank, boolean newRank) {
        super(player);
        this.job = job;
        this.level = level;
        this.rank = rank;
        this.newRank = newRank;
    }

    public String getJob() {
        return job;
    }

    public int getLevel() {
        return level;
    }

    /** apprenti, compagnon, artisan, expert ou maitre. */
    public String getRank() {
        return rank;
    }

    /** true si ce niveau fait passer un nouveau rang. */
    public boolean isNewRank() {
        return newRank;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
