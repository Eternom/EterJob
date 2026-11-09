package fr.eternom.eterJob.module.job;

import fr.eternom.eterClan.api.ClanApi;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Le terrain des clans (EterClan, en softdepend : cette classe n'est chargée que s'il tourne ici) : une action ne compte
 * pas dans le terrain d'un clan où le joueur ne peut pas construire.
 */
final class ClanLand {

    private ClanLand() {
    }

    static boolean canBuild(Player player, Location location) {
        return ClanApi.get().map(api -> api.canBuild(player, location)).orElse(true);
    }
}
