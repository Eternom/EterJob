package fr.eternom.eterJob.module.job;

import fr.eternom.eterNpc.api.NpcApi;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Le marqueur « ! » au-dessus du donneur de quêtes (EterNpc, en softdepend : cette classe n'est créée que s'il tourne
 * ici). Seuls les changements sont envoyés à EterNpc. Thread principal.
 */
public final class NpcMarkers {

    private final NpcApi npcs;
    /** Joueur -> PNJ|marqueur déjà posé. */
    private final Map<UUID, String> shown = new HashMap<>();

    private NpcMarkers(NpcApi npcs) {
        this.npcs = npcs;
    }

    /** null si EterNpc ne tourne pas ici. */
    public static NpcMarkers create() {
        return NpcApi.get().map(NpcMarkers::new).orElse(null);
    }

    void mark(Player player, String npc, Component marker) {
        String state = marker == null ? null : npc + "|" + marker.hashCode();
        String previous = shown.get(player.getUniqueId());
        if (Objects.equals(state, previous)) {
            return;
        }
        if (previous != null && !previous.startsWith(npc + "|")) {
            npcs.marker(player, previous.substring(0, previous.indexOf('|')), null);
        }
        npcs.marker(player, npc, marker);
        if (state == null) {
            shown.remove(player.getUniqueId());
        } else {
            shown.put(player.getUniqueId(), state);
        }
    }
}
