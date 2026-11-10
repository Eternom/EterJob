package fr.eternom.eterJob.module.menu;

import fr.eternom.eterJob.module.job.JobService;
import fr.eternom.eterJob.module.job.Rank;
import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.module.action.Actions;
import fr.eternom.eterLib.module.action.Actions.Argument;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.List;

/**
 * Les actions d'EterJob, pour les PNJ d'EterNpc (et les menus, les commandes) :
 * <pre>
 *   eterjob:guild                  le chef de guilde (choisir, changer de métier, rangs, avantages, classement)
 *   eterjob:quests <métier>        le donneur de quêtes du métier (un joueur d'un autre métier est envoyé au chef de guilde)
 *   eterjob:menu                   /job : ses quêtes (à distance)
 *   eterjob:perks [métier]         les avantages par rang (PNJ du monde ressource)
 *   eterjob:story <métier>         le maître du métier : l'histoire, puis les quêtes
 *   eterjob:ranking <métier>       le classement du métier
 *   ?eterjob:has-job [métier]      condition : le joueur a ce métier (ou un métier)
 *   ?eterjob:rank <métier> <rang>  condition : le joueur a au moins ce rang dans ce métier (son métier actuel)
 * </pre>
 */
public final class JobActions {

    private JobActions() {
    }

    public static void register(JavaPlugin plugin, JobService service, JobGui gui) {
        Actions actions = EterLib.get().getActions();
        Argument job = Argument.required("métier", typed -> List.copyOf(service.settings().icons().keySet()));
        actions.register(plugin, "eterjob:guild", "Le chef de guilde : choisir ou changer de métier", List.of(),
                (player, args) -> gui.openGuild(player));
        actions.register(plugin, "eterjob:quests", "Le donneur de quêtes d'un métier", List.of(job),
                (player, args) -> gui.openQuests(player, args.getFirst().toLowerCase(), false));
        actions.register(plugin, "eterjob:menu", "Ses quêtes de métier (à distance, comme /job)", List.of(),
                (player, args) -> gui.openQuests(player, null, true));
        actions.register(plugin, "eterjob:perks", "Les avantages d'un métier par rang", List.of(new Argument("métier", false,
                typed -> List.copyOf(service.settings().icons().keySet()))),
                (player, args) -> gui.openPerks(player, args.isEmpty() ? null : args.getFirst().toLowerCase()));
        // Le relais de la guilde (camp du monde ressource...) : le donneur de quêtes de SON métier, objets compris
        actions.register(plugin, "eterjob:relay", "Le donneur de quêtes du métier du joueur (relais de la guilde)", List.of(),
                (player, args) -> gui.openQuests(player, null, false));
        actions.register(plugin, "eterjob:story", "L'histoire d'un métier (maître du métier)", List.of(job),
                (player, args) -> gui.openStory(player, args.getFirst().toLowerCase()));
        actions.register(plugin, "eterjob:ranking", "Le classement d'un métier", List.of(job),
                (player, args) -> gui.openRanking(player, args.getFirst().toLowerCase()));
        actions.registerCondition(plugin, "eterjob:has-job", "Le joueur a ce métier (ou un métier)", List.of(new Argument("métier", false,
                typed -> List.copyOf(service.settings().icons().keySet()))), (player, args) -> {
            String current = service.progress().jobOf(player.getUniqueId());
            return current != null && (args.isEmpty() || current.equalsIgnoreCase(args.getFirst()));
        });
        actions.registerCondition(plugin, "eterjob:rank", "Le joueur a au moins ce rang dans ce métier", List.of(job,
                Argument.required("rang", typed -> Arrays.stream(Rank.values()).map(Rank::id).toList())), (player, args) -> {
            String current = service.progress().jobOf(player.getUniqueId());
            Rank rank = service.progress().rankOf(player.getUniqueId());
            Rank wanted = Rank.of(args.get(1));
            return current != null && current.equalsIgnoreCase(args.getFirst()) && rank != null && wanted != null && rank.atLeast(wanted);
        });
    }
}
