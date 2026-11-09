package fr.eternom.eterJob.api;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/** Un joueur vient de choisir ou de changer de métier (thread principal). previous : vide pour un premier métier. */
public class JobChangeEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String previous;
    private final String job;

    public JobChangeEvent(Player player, String previous, String job) {
        super(player);
        this.previous = previous;
        this.job = job;
    }

    public String getPrevious() {
        return previous;
    }

    public String getJob() {
        return job;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
