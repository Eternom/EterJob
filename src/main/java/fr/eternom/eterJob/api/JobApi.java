package fr.eternom.eterJob.api;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Ce qu'EterJob offre aux autres plugins : le métier d'un joueur, son niveau et son rang, ses avantages dans le monde
 * ressource, et le classement des quêtes. Personne d'autre ne lit les tables eterjob_* : on demande ici.
 * <pre>
 *     // compileOnly("com.github.Eternom:EterJob:&lt;tag&gt;") ; plugin.yml : softdepend: [EterJob]
 *     JobApi.get().flatMap(jobs -> jobs.job(uuid))   // hors du thread principal
 * </pre>
 * Remplace MarketApi d'EterMarket (2.0.0).
 */
public interface JobApi {

    /** L'API d'EterJob si le plugin tourne sur ce serveur. */
    static Optional<JobApi> get() {
        return Optional.ofNullable(Bukkit.getServicesManager().load(JobApi.class));
    }

    /** Les métiers de la guilde (identifiants de config.yml > jobs.list). */
    List<String> jobs();

    /** Nom affiché d'un métier dans la langue de viewer. */
    String jobName(CommandSender viewer, String job);

    /** Le métier du joueur, vide s'il n'en a pas. Bloquant (base, avec un cache de 30 s). */
    Optional<String> job(UUID player);

    /** Niveau du joueur dans ce métier (1 s'il ne l'a jamais pratiqué). Bloquant. */
    int level(UUID player, String job);

    /** Rang du joueur dans ce métier : apprenti, compagnon, artisan, expert, maitre. Bloquant. */
    String rank(UUID player, String job);

    /** Nom affiché d'un rang dans la langue de viewer (« Artisan »). */
    String rankName(CommandSender viewer, String rank);

    /** Avantages du joueur dans le monde ressource (métier actuel et son rang), NONE sans métier. Bloquant (cache 30 s). */
    JobPerks perks(UUID player);

    /** Un joueur et sa valeur dans un classement. */
    record Ranked(UUID player, long value) {
    }

    /** Les joueurs qui ont validé le plus de quêtes de métier (tous métiers), du premier au dernier. Bloquant. */
    List<Ranked> topQuests(int limit);

    /** Place du joueur dans ce classement (1 = premier), 0 s'il n'a jamais validé de quête. Bloquant. */
    int questRank(UUID player);
}
