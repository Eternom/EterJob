package fr.eternom.eterJob.module.menu;

import fr.eternom.eterJob.module.job.JobRepository;
import fr.eternom.eterJob.module.job.JobService;
import fr.eternom.eterJob.module.job.Levels;
import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /job [guild | perks | history | top <métier> | messages | <joueur>] : ses quêtes (à distance : les quêtes
 * sans objet à livrer se valident d'ici), le chef de guilde, les avantages, l'historique, le classement d'un métier,
 * couper les messages de progression, le profil métier d'un autre joueur.
 */
public class JobCommand implements TabExecutor {

    private static final List<String> SUBS = List.of("guild", "perks", "history", "top", "messages");

    private final JobService service;
    private final JobGui gui;
    private final Messages messages;

    public JobCommand(JobService service, JobGui gui) {
        this.service = service;
        this.gui = gui;
        this.messages = service.messages();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "command.players-only");
            return true;
        }
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "" -> gui.openQuests(player, null, true);
            case "guild" -> gui.openGuild(player);
            case "perks" -> gui.openPerks(player, args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : null);
            case "history" -> gui.openHistory(player);
            case "top" -> {
                String job = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : service.progress().jobOf(player.getUniqueId());
                if (job == null || !service.settings().exists(job)) {
                    messages.send(player, "job.unknown", "jobs", String.join(", ", service.settings().icons().keySet()));
                } else {
                    gui.openRanking(player, job);
                }
            }
            case "messages" -> service.quests().toggleQuiet(player);
            default -> profile(player, args[0]);
        }
        return true;
    }

    /** /job <joueur> : son métier, son rang et son niveau dans chaque métier pratiqué. */
    private void profile(Player viewer, String name) {
        Tasks.async(service.plugin(), viewer, () -> EterLib.get().getPlayers().find(name).map(found -> new Object[]{found.name(),
                service.repository().member(found.uuid()), service.repository().levels(found.uuid())}), data -> {
            if (data.isEmpty()) {
                messages.send(viewer, "player.unknown", "player", name);
                return;
            }
            Object[] values = data.get();
            @SuppressWarnings("unchecked") var member = (java.util.Optional<JobRepository.Member>) values[1];
            @SuppressWarnings("unchecked") var levels = (Map<String, JobRepository.JobLevel>) values[2];
            messages.send(viewer, "profile.title", "player", (String) values[0],
                    "job", member.map(found -> service.jobName(viewer, found.job())).orElse(messages.plain(viewer, "profile.no-job")));
            Levels curve = service.levels();
            levels.values().stream().filter(level -> level.xp() > 0).forEach(level -> {
                int lvl = curve.level(level.xp());
                messages.send(viewer, "profile.line", "job", service.jobName(viewer, level.job()),
                        "rank", service.rankName(viewer, curve.rank(lvl)), "level", String.valueOf(lvl), "count", String.valueOf(level.completed()));
            });
        }, () -> messages.send(viewer, "error.generic"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            List<String> names = EterLib.get().getOnlineNames().complete(args[0]);
            return java.util.stream.Stream.concat(SUBS.stream().filter(sub -> sub.startsWith(args[0].toLowerCase(Locale.ROOT))),
                    names.stream()).toList();
        }
        if (args.length == 2 && List.of("top", "perks").contains(args[0].toLowerCase(Locale.ROOT))) {
            return service.settings().icons().keySet().stream().filter(job -> job.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }
}
