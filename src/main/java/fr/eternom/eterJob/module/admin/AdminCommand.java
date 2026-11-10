package fr.eternom.eterJob.module.admin;

import com.google.gson.JsonObject;
import fr.eternom.eterJob.module.job.JobRepository;
import fr.eternom.eterJob.module.job.JobService;
import fr.eternom.eterJob.module.job.Levels;
import fr.eternom.eterJob.module.job.JobQuests;
import fr.eternom.eterQuest.api.QuestInstance;
import fr.eternom.eterJob.module.job.QuestCatalog;
import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.cache.NetworkBus;
import fr.eternom.eterLib.helper.economy.Money;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * /eterjob (staff, eterjob.admin ; aussi depuis la console) :
 * - player <joueur> : métier, délai, série, colis, niveau de chaque métier, quêtes en cours ;
 * - setjob <joueur> <métier> · cooldown <joueur> · clearquests <joueur> · reroll <joueur> · remove <joueur> confirm ;
 * - setlevel <joueur> <métier> <niveau> · addxp <joueur> <métier> <xp> · setstory <joueur> <métier> <étapes> ;
 * - quests <métier> · reload (config et quests/, tout le réseau) · stats [jours] ;
 * - event <métier|all> <multiplicateur> <durée> · event stop.
 * Chaque changement est noté dans la console, et le serveur où joue le joueur relit ses données.
 */
public class AdminCommand implements TabExecutor {

    private static final List<String> ACTIONS = List.of("player", "setjob", "cooldown", "clearquests", "reroll", "remove", "setlevel",
            "addxp", "setstory", "quests", "reload", "stats", "event");
    private static final Pattern DURATION = Pattern.compile("(\\d+)\\s*([dhm])", Pattern.CASE_INSENSITIVE);

    private final JobService service;
    private final Messages messages;
    private final NetworkBus bus;
    private final Runnable reload;

    public AdminCommand(JobService service, NetworkBus bus, Runnable reload) {
        this.service = service;
        this.messages = service.messages();
        this.bus = bus;
        this.reload = reload;
        bus.on("refresh", data -> refreshLocal(UUID.fromString(data.get("uuid").getAsString())));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String action = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        String target = args.length > 1 ? args[1] : null;
        switch (action) {
            case "player" -> withPlayer(sender, target, (uuid, name) -> info(sender, uuid, name));
            case "setjob" -> {
                String job = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "";
                if (!service.settings().exists(job)) {
                    usage(sender);
                    return true;
                }
                change(sender, target, "métier " + job, (uuid, name) -> service.repository().setJob(uuid, job, System.currentTimeMillis()));
            }
            case "cooldown" -> change(sender, target, "délai de changement remis à zéro", (uuid, name) -> service.repository().resetCooldown(uuid));
            case "clearquests" -> change(sender, target, "quêtes effacées", (uuid, name) -> service.quests().clear(uuid, JobQuests.OWNER));
            case "reroll" -> change(sender, target, "changement de quête rendu", (uuid, name) -> service.repository().clearReroll(uuid));
            case "remove" -> {
                if (args.length < 3 || !args[2].equalsIgnoreCase("confirm")) {
                    messages.send(sender, "admin.remove-confirm", "player", target == null ? "?" : target);
                    return true;
                }
                change(sender, target, "métier retiré", (uuid, name) -> service.repository().removeMember(uuid));
            }
            case "setlevel", "addxp", "setstory" -> {
                String job = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "";
                int value = args.length > 3 ? parse(args[3]) : Integer.MIN_VALUE;
                if (!service.settings().exists(job) || value == Integer.MIN_VALUE) {
                    usage(sender);
                    return true;
                }
                Levels levels = service.levels();
                change(sender, target, action + " " + job + " " + value, (uuid, name) -> {
                    switch (action) {
                        case "setlevel" -> service.repository().setXp(uuid, job, levels.xpFor(Math.max(1, value)));
                        case "addxp" -> service.repository().addXp(uuid, job, value, false);
                        default -> service.repository().setStory(uuid, job, value);
                    }
                });
            }
            case "quests" -> quests(sender, target == null ? "" : target.toLowerCase(Locale.ROOT));
            case "reload" -> {
                reload.run();
                bus.publish("reload", new JsonObject());
                messages.send(sender, "admin.reloaded");
            }
            case "stats" -> stats(sender, target == null ? 7 : Math.max(1, parse(target)));
            case "event" -> event(sender, args);
            default -> usage(sender);
        }
        return true;
    }

    private void info(CommandSender sender, UUID uuid, String name) {
        var member = service.repository().member(uuid);
        Map<String, JobRepository.JobLevel> levels = service.repository().levels(uuid);
        List<QuestInstance> quests = service.quests().instances(uuid, JobQuests.OWNER);
        int box = service.quests().boxCount(uuid);
        int rank = service.repository().completedRank(uuid);
        Bukkit.getScheduler().runTask(service.plugin(), () -> {
            messages.send(sender, "admin.info.title", "player", name, "job",
                    member.map(found -> service.jobName(sender, found.job())).orElse("-"), "rank", String.valueOf(rank));
            member.ifPresent(found -> messages.send(sender, "admin.info.member",
                    "cooldown", EterLib.get().formatDuration(sender, service.cooldownSeconds(found)),
                    "streak", String.valueOf(found.streak()), "box", String.valueOf(box)));
            Levels curve = service.levels();
            levels.values().forEach(level -> {
                int lvl = curve.level(level.xp());
                messages.send(sender, "admin.info.level", "job", service.jobName(sender, level.job()), "level", String.valueOf(lvl),
                        "rank", service.rankName(sender, curve.rank(lvl)), "xp", String.valueOf(level.xp()),
                        "count", String.valueOf(level.completed()), "story", String.valueOf(level.story()));
            });
            long now = System.currentTimeMillis();
            for (QuestInstance quest : quests) {
                messages.send(sender, "admin.info.quest", "slot", quest.slot(), "tier", JobQuests.tier(quest).id(),
                        "objectives", quest.quest() + " #" + (quest.stage() + 1),
                        "progress", quest.progress().toString(), "reward", Money.format(JobQuests.reward(quest)),
                        "state", quest.done() ? "✔" : quest.expired(now) ? "✗" : "…");
            }
        });
    }

    private void quests(CommandSender sender, String job) {
        if (!service.settings().exists(job)) {
            usage(sender);
            return;
        }
        QuestCatalog.JobQuests quests = service.catalog().of(job);
        for (QuestCatalog.Kind kind : QuestCatalog.Kind.values()) {
            messages.send(sender, "admin.quests.kind", "kind", kind.name().toLowerCase(Locale.ROOT), "count", String.valueOf(quests.of(kind).size()));
            quests.of(kind).forEach(template -> messages.send(sender, "admin.quests.line", "tier", template.tier().id(),
                    "reward", Money.format(template.reward()), "rank", template.story() != null ? template.story().id() : template.minRank().id(),
                    "objectives", template.id() + " (" + template.quest().stages().size() + " étape(s))"));
        }
    }

    private void stats(CommandSender sender, int days) {
        Tasks.async(service.plugin(), () -> {
            var stats = service.repository().stats(System.currentTimeMillis() - days * 86_400_000L);
            var members = service.repository().membersByJob();
            Bukkit.getScheduler().runTask(service.plugin(), () -> {
                messages.send(sender, "admin.stats.title", "days", String.valueOf(days));
                members.forEach((job, count) -> messages.send(sender, "admin.stats.members", "job", job, "count", String.valueOf(count)));
                stats.forEach((day, byJob) -> byJob.forEach((job, values) -> messages.send(sender, "admin.stats.line", "day", day,
                        "job", job, "quests", String.valueOf((long) values[0]), "money", Money.format(values[1]))));
            });
        }, "Statistiques des métiers non lues");
    }

    private void event(CommandSender sender, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("stop")) {
            Tasks.async(service.plugin(), () -> {
                service.repository().clearEvents();
                service.refreshEvents();
                Bukkit.getScheduler().runTask(service.plugin(), () -> {
                    bus.publish("events", new JsonObject());
                    messages.send(sender, "admin.event-stopped");
                });
            }, "Évènements des métiers non arrêtés");
            return;
        }
        String job = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        double multiplier;
        try {
            multiplier = args.length > 2 ? Double.parseDouble(args[2].replace(',', '.')) : 0;
        } catch (NumberFormatException e) {
            multiplier = 0;
        }
        Duration duration = args.length > 3 ? duration(args[3]) : null;
        if (!job.equals("all") && !service.settings().exists(job) || multiplier <= 1 || multiplier > 10 || duration == null) {
            messages.send(sender, "admin.event-usage");
            return;
        }
        double chosen = multiplier;
        Tasks.async(service.plugin(), () -> {
            service.repository().addEvent(new JobRepository.Event(job.equals("all") ? null : job, chosen,
                    System.currentTimeMillis() + duration.toMillis(), sender.getName()));
            service.refreshEvents();
            Bukkit.getScheduler().runTask(service.plugin(), () -> {
                bus.publish("events", new JsonObject());
                JsonObject data = new JsonObject();
                data.addProperty("job", job);
                data.addProperty("multiplier", chosen);
                data.addProperty("minutes", duration.toMinutes());
                bus.publish("event-start", data);
                announceEvent(job, chosen, duration.toMinutes());
                messages.send(sender, "admin.event-started");
            });
        }, "Évènement des métiers non lancé");
    }

    /** Annonce d'un évènement aux joueurs de ce serveur. */
    public void announceEvent(String job, double multiplier, long minutes) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            messages.send(player, job.equals("all") ? "event.start-all" : "event.start",
                    "job", job.equals("all") ? "" : service.jobName(player, job), "multiplier", String.valueOf(multiplier),
                    "time", EterLib.get().formatDuration(player, minutes * 60));
        }
    }

    // ---------- Outils ----------

    /** Retrouve le joueur (connecté ou non), puis action en tâche de fond. */
    private void withPlayer(CommandSender sender, String name, BiConsumer<UUID, String> action) {
        if (name == null) {
            usage(sender);
            return;
        }
        Tasks.async(service.plugin(), () -> {
            var found = EterLib.get().getPlayers().find(name);
            if (found.isEmpty()) {
                Bukkit.getScheduler().runTask(service.plugin(), () -> messages.send(sender, "player.unknown", "player", name));
                return;
            }
            action.accept(found.get().uuid(), found.get().name());
        }, "Commande des métiers en erreur pour " + name);
    }

    /** Un changement sur un joueur : noté dans la console, son serveur relit ses données. */
    private void change(CommandSender sender, String name, String what, BiConsumer<UUID, String> change) {
        withPlayer(sender, name, (uuid, found) -> {
            change.accept(uuid, found);
            service.plugin().getLogger().info("[Métiers] " + sender.getName() + " : " + what + " pour " + found);
            Bukkit.getScheduler().runTask(service.plugin(), () -> {
                refreshLocal(uuid);
                JsonObject data = new JsonObject();
                data.addProperty("uuid", uuid.toString());
                bus.publish("refresh", data);
                messages.send(sender, "admin.done", "player", found, "what", what);
            });
        });
    }

    private void refreshLocal(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            service.progress().reload(player);
        }
    }

    private void usage(CommandSender sender) {
        messages.send(sender, "admin.usage", "jobs", String.join(", ", service.settings().icons().keySet()));
    }

    static Duration duration(String text) {
        Matcher matcher = DURATION.matcher(text);
        Duration total = Duration.ZERO;
        while (matcher.find()) {
            long value = Long.parseLong(matcher.group(1));
            total = total.plus(switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
                case "d" -> Duration.ofDays(value);
                case "h" -> Duration.ofHours(value);
                default -> Duration.ofMinutes(value);
            });
        }
        return total.isZero() ? null : total;
    }

    private static int parse(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return Integer.MIN_VALUE;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return filter(ACTIONS.stream(), args[0]);
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            return switch (action) {
                case "quests" -> filter(service.settings().icons().keySet().stream(), args[1]);
                case "event" -> filter(Stream.concat(Stream.of("all", "stop"), service.settings().icons().keySet().stream()), args[1]);
                case "reload", "stats" -> List.of();
                default -> EterLib.get().getOnlineNames().complete(args[1]);
            };
        }
        if (args.length == 3) {
            return switch (action) {
                case "setjob", "setlevel", "addxp", "setstory" -> filter(service.settings().icons().keySet().stream(), args[2]);
                case "remove" -> filter(Stream.of("confirm"), args[2]);
                case "event" -> List.of("1.5", "2");
                default -> List.of();
            };
        }
        if (args.length == 4 && action.equals("event")) {
            return List.of("2h", "1d");
        }
        return List.of();
    }

    private static List<String> filter(Stream<String> values, String start) {
        String prefix = start.toLowerCase(Locale.ROOT);
        return values.filter(value -> value.startsWith(prefix)).toList();
    }
}
