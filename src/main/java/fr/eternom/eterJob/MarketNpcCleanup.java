package fr.eternom.eterJob;

import fr.eternom.eterNpc.api.NpcApi;

/**
 * EterJob 2.0.0 : les emplacements des anciens PNJ d'EterMarket (etermarket:*) chez EterNpc. Classe à part, chargée
 * seulement si EterNpc tourne. Retirée en 2.0.1.
 */
final class MarketNpcCleanup {

    private MarketNpcCleanup() {
    }

    /** Bloquant. */
    static int removeMarketPlacements() {
        return NpcApi.get().map(api -> api.deletePlacementsByPrefix("etermarket:")).orElse(0);
    }
}
