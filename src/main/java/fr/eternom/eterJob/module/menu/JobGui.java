package fr.eternom.eterJob.module.menu;

import fr.eternom.eterJob.module.job.JobRepository;
import fr.eternom.eterJob.module.job.JobService;
import fr.eternom.eterJob.module.job.QuestCatalog;
import fr.eternom.eterQuest.api.QuestInstance;
import fr.eternom.eterJob.module.job.Rank;
import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.economy.Money;
import fr.eternom.eterLib.helper.gui.BackButton;
import fr.eternom.eterLib.helper.gui.Dialogs;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Les menus d'EterJob, ouverts par les actions des PNJ (eterjob:guild, eterjob:quests...) et par /job : données lues en
 * tâche de fond, menus sur le thread principal.
 * - le chef de guilde (GuildMenu) : choisir ou changer de métier, son niveau dans chacun, les avantages, le classement ;
 * - le donneur de quêtes (QuestMenu) : les quêtes du métier, valider, suivre, changer ; depuis /job, les quêtes sans
 *   objet à livrer se valident à distance ;
 * - les avantages par rang, l'historique, le classement, l'histoire du métier.
 */
public class JobGui {

    private final JavaPlugin plugin;
    private final JobService service;
    private final Messages messages;
    private final BackButton backButton;

    public JobGui(JavaPlugin plugin, JobService service, BackButton backButton) {
        this.plugin = plugin;
        this.service = service;
        this.messages = service.messages();
        this.backButton = backButton;
    }

    /**
     * Les quêtes : job donné (donneur de quêtes de ce métier) ou null (/job : son métier). remote : ouvert à distance
     * (/job), où seules les quêtes sans objet à livrer se valident.
     */
    public void openQuests(Player player, String job, boolean remote) {
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, player, () -> job == null ? service.board(uuid) : service.board(uuid, job), board -> {
            if (board.isPresent()) {
                player.openInventory(new QuestMenu(this, player, board.get(), remote).getInventory());
            } else if (job != null && service.progress().hasJob(uuid)) {
                // Un autre métier : le donneur l'envoie au chef de guilde
                messages.send(player, "quest.other-job", "job", service.jobName(player, job));
                openGuild(player);
            } else {
                messages.send(player, "job.none");
                openGuild(player);
            }
        }, () -> messages.send(player, "error.generic"));
    }

    /** Le chef de guilde : les métiers, son niveau dans chacun, son métier actuel et le délai de changement. */
    public void openGuild(Player player) {
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, player, () -> new GuildMenu.View(service.repository().member(uuid), service.repository().levels(uuid),
                        service.repository().membersByJob(), service.weakestJob()),
                view -> player.openInventory(new GuildMenu(this, player, view).getInventory()),
                () -> messages.send(player, "error.generic"));
    }

    /** Les avantages d'un métier par rang (le sien si job est null). */
    public void openPerks(Player player, String job) {
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, player, () -> {
            String chosen = job != null ? job : service.repository().member(uuid).map(JobRepository.Member::job).orElse(null);
            return chosen == null ? null : new Object[]{chosen, service.repository().level(uuid, chosen)};
        }, data -> {
            if (data == null) {
                messages.send(player, "job.none");
                return;
            }
            JobRepository.JobLevel level = (JobRepository.JobLevel) data[1];
            player.openInventory(new PerksMenu(this, player, (String) data[0], service.levels().rank(service.levels().level(level.xp())))
                    .getInventory());
        }, () -> messages.send(player, "error.generic"));
    }

    public void openHistory(Player player) {
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, player, () -> service.repository().history(uuid, System.currentTimeMillis() - 7L * 86_400_000L, 28),
                entries -> player.openInventory(new HistoryMenu(this, player, entries).getInventory()),
                () -> messages.send(player, "error.generic"));
    }

    /** Classement d'un métier (niveau), avec le pseudo de chacun. */
    public void openRanking(Player player, String job) {
        Tasks.async(plugin, player, () -> {
            List<RankingMenu.Line> lines = new ArrayList<>();
            for (var ranked : service.repository().topOfJob(job, 10)) {
                String name = EterLib.get().getPlayers().get(ranked.player()).map(found -> found.name()).orElse("?");
                lines.add(new RankingMenu.Line(ranked.player(), name, (int) ranked.value()));
            }
            return lines;
        }, lines -> player.openInventory(new RankingMenu(this, player, job, lines).getInventory()),
                () -> messages.send(player, "error.generic"));
    }

    /** L'histoire du métier : le lore de l'étape en cours dans le chat, puis les quêtes. */
    public void openStory(Player player, String job) {
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, player, () -> service.repository().member(uuid).filter(member -> member.job().equals(job))
                .map(member -> service.repository().level(uuid, job)), level -> {
            if (level.isEmpty()) {
                messages.send(player, "quest.other-job", "job", service.jobName(player, job));
                openGuild(player);
                return;
            }
            int step = level.get().story();
            Optional<QuestCatalog.Template> template = step < Rank.values().length
                    ? service.catalog().of(job).story(Rank.values()[step]) : Optional.empty();
            if (template.isEmpty()) {
                messages.send(player, "story.finished", "job", service.jobName(player, job));
            } else {
                player.sendMessage(Component.empty());
                player.sendMessage(messages.render(template.get().quest().name(), TagResolver.empty()));
                template.get().quest().description().forEach(line -> player.sendMessage(messages.render(line, TagResolver.empty())));
                Rank needed = template.get().story();
                Rank rank = service.levels().rank(service.levels().level(level.get().xp()));
                if (!rank.atLeast(needed)) {
                    messages.send(player, "story.locked", "rank", service.rankName(player, needed));
                }
                player.sendMessage(Component.empty());
            }
            openQuests(player, job, false);
        }, () -> messages.send(player, "error.generic"));
    }

    // ---------- Confirmations ----------

    void confirmJoin(Player player, String job, Optional<JobRepository.Member> current) {
        player.closeInventory();
        String name = service.jobName(player, job);
        Component body = current.isEmpty()
                ? messages.get(player, "job.join.body-first", "job", name)
                : messages.get(player, "job.join.body-change", "job", name, "current", service.jobName(player, current.get().job()),
                "price", Money.format(service.settings().changeCost()),
                "days", String.valueOf(service.settings().changeCooldown().toDays()));
        DialogBase base = DialogBase.builder(messages.get(player, "job.join.title", "job", name))
                .body(List.of(DialogBody.plainMessage(body))).build();
        Dialogs.show(plugin, player, base, messages.get(player, "job.join.confirm"), messages.get(player, "dialog.cancel"),
                response -> service.join(player, job, () -> openQuests(player, job, false)), () -> openGuild(player));
    }

    void confirmReroll(Player player, JobService.Board board, QuestInstance quest, boolean remote) {
        player.closeInventory();
        String price = board.rerollFree() || service.settings().rerollCost() <= 0 ? messages.plain(player, "quest.reroll.free")
                : Money.format(service.settings().rerollCost());
        DialogBase base = DialogBase.builder(messages.get(player, "quest.reroll.title"))
                .body(List.of(DialogBody.plainMessage(messages.get(player, "quest.reroll.body", "price", price)))).build();
        Dialogs.show(plugin, player, base, messages.get(player, "quest.reroll.confirm"), messages.get(player, "dialog.cancel"),
                response -> service.reroll(player, quest, () -> openQuests(player, board.job(), remote)),
                () -> openQuests(player, board.job(), remote));
    }

    // ---------- Accès ----------

    JobService service() {
        return service;
    }

    Messages messages() {
        return messages;
    }

    BackButton backButton() {
        return backButton;
    }

    JavaPlugin plugin() {
        return plugin;
    }

    String time(Player viewer, long seconds) {
        return EterLib.get().formatDuration(viewer, seconds);
    }

    /** Membres par métier, pour le menu de la guilde. */
    static int members(Map<String, Integer> counts, String job) {
        return counts.getOrDefault(job, 0);
    }
}
