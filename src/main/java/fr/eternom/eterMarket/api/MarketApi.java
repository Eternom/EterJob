package fr.eternom.eterMarket.api;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Ce qu'EterMarket offre aux autres plugins : le métier d'un joueur (guilde des métiers) et le stock commun (l'entrepôt
 * que vident les boutiques et que remplissent les quêtes). Personne d'autre ne
 * lit les tables etermarket_* : on demande ici.
 * <pre>
 *     // compileOnly("com.github.Eternom:EterMarket:&lt;tag&gt;") ; plugin.yml : softdepend: [EterMarket]
 *     MarketApi.get().flatMap(market -> market.job(uuid))   // hors du thread principal
 * </pre>
 */
public interface MarketApi {

    /** L'API d'EterMarket si le plugin tourne sur ce serveur. */
    static Optional<MarketApi> get() {
        return Optional.ofNullable(Bukkit.getServicesManager().load(MarketApi.class));
    }

    /** Les métiers de la guilde (identifiants de config.yml > jobs.list). */
    List<String> jobs();

    /** Nom affiché d'un métier dans la langue de viewer. */
    String jobName(CommandSender viewer, String job);

    /** Le métier du joueur, vide s'il n'en a pas. Bloquant (base) : hors du thread principal. */
    Optional<String> job(UUID player);

    /** Quantité de cette matière dans le stock commun. Bloquant (base). */
    long stock(Material material);

    /** Ajoute au stock commun (une seule requête). Bloquant (base). */
    void addStock(Material material, long amount);

    /** Retire du stock commun s'il y en a assez (une seule requête conditionnelle) ; false sinon. Bloquant (base). */
    boolean takeStock(Material material, long amount);

    /** Un joueur et sa valeur dans un classement. */
    record Ranked(UUID player, long value) {
    }

    /** Les joueurs qui ont validé le plus de quêtes de métier, du premier au dernier. Bloquant (base). */
    List<Ranked> topQuests(int limit);

    /** Place du joueur dans ce classement (1 = premier), 0 s'il n'a jamais validé de quête. Bloquant (base). */
    int questRank(UUID player);
}
