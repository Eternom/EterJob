package fr.eternom.eterJob.module.menu;

import fr.eternom.eterJob.module.job.JobQuests;
import fr.eternom.eterJob.module.job.JobService;
import fr.eternom.eterJob.module.job.JobService.Board;
import fr.eternom.eterJob.module.job.Levels;
import fr.eternom.eterJob.module.job.Rank;
import fr.eternom.eterLib.helper.economy.Money;
import fr.eternom.eterLib.helper.gui.Frame;
import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterQuest.api.QuestApi;
import fr.eternom.eterQuest.api.QuestInstance;
import fr.eternom.eterQuest.model.Quest;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Les quêtes d'un métier (donneur de quêtes, ou /job), 6 lignes :
 * <pre>
 *  ▣ ▣ ▣ ▣ ☺ ▣ ▣ ▣ ▣     ☺ = joueur : métier, rang, niveau (barre), série, temps avant les suivantes
 *  ▣ · · · · · · · ▣
 *  ▣ · 1 · 2 · 3 · ▣     quêtes du jour (facile, normale, difficile ; ✦ rare)
 *  ▣ · S · ★ · H · ▣     S = la semaine · ★ = la bonus · H = l'histoire du métier
 *  ▣ · · · · · · · ▣
 *  ⇊ ☰ ✧ ⚑ « ✉ ▤ ♪ ▣     tout valider · historique · avantages · guilde · retour · boîte · sidebar · messages
 * </pre>
 * Sur une quête : clic = la rendre (son étape en cours), clic droit = la suivre dans la sidebar, Maj + clic = la changer
 * (une par jour). Les quêtes sont celles d'EterQuest : une quête à étapes (ex : chez un PNJ du village) montre l'étape
 * en cours.
 */
class QuestMenu implements Menu {

    private static final List<Integer> DAILY_SLOTS = List.of(20, 22, 24);
    private static final int INFO = 4;
    private static final int WEEKLY = 29;
    private static final int BONUS = 31;
    private static final int STORY = 33;
    private static final int DELIVER_ALL = 45;
    private static final int HISTORY = 46;
    private static final int PERKS = 47;
    private static final int GUILD = 48;
    private static final int BACK = 49;
    private static final int BOX = 50;
    private static final int SIDEBAR = 51;
    private static final int QUIET = 52;

    private final JobGui gui;
    private final JobService service;
    private final QuestApi quests;
    private final Messages messages;
    private final Player viewer;
    private final Board board;
    private final boolean remote;
    private final Inventory inventory;
    private final Map<Integer, QuestInstance> questAt = new HashMap<>();

    QuestMenu(JobGui gui, Player viewer, Board board, boolean remote) {
        this.gui = gui;
        this.service = gui.service();
        this.quests = service.quests();
        this.messages = gui.messages();
        this.viewer = viewer;
        this.board = board;
        this.remote = remote;
        this.inventory = Bukkit.createInventory(this, 54, text("quest.menu.title", "job", service.jobName(viewer, board.job())));
        render();
    }

    /** La quête telle que ce serveur la connaît (progression à jour), sinon celle du tableau. */
    private QuestInstance current(QuestInstance shown) {
        return quests.live(viewer, JobQuests.OWNER).stream()
                .filter(q -> q.slot().equals(shown.slot()) && q.token() == shown.token()).findFirst().orElse(shown);
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        QuestInstance shown = questAt.get(slot);
        if (shown != null) {
            QuestInstance quest = current(shown);
            if (quest.done()) {
                Sounds.refuse(player);
            } else if (click.isShiftClick()) {
                if (!JobQuests.isDaily(quest) || !board.canReroll()) {
                    Sounds.refuse(player);
                    return;
                }
                Sounds.click(player);
                gui.confirmReroll(player, board, quest, remote);
            } else if (click.isRightClick()) {
                Sounds.click(player);
                quests.toggleTrack(player, JobQuests.OWNER, quest.slot());
                gui.openQuests(player, board.job(), remote);
            } else {
                Sounds.click(player);
                service.deliver(player, quest, remote, () -> gui.openQuests(player, board.job(), remote));
            }
            return;
        }
        switch (slot) {
            case DELIVER_ALL -> deliverAll(player);
            case HISTORY -> {
                Sounds.page(player);
                gui.openHistory(player);
            }
            case PERKS -> {
                Sounds.page(player);
                gui.openPerks(player, board.job());
            }
            case GUILD -> {
                Sounds.page(player);
                gui.openGuild(player);
            }
            case BACK -> gui.backButton().click(player);
            case BOX -> {
                Sounds.click(player);
                service.takeBox(player, () -> gui.openQuests(player, board.job(), remote));
            }
            case SIDEBAR -> {
                Sounds.click(player);
                quests.toggleTrack(player, null, null);
            }
            case QUIET -> {
                Sounds.click(player);
                quests.toggleQuiet(player);
                gui.openQuests(player, board.job(), remote);
            }
            default -> {
            }
        }
    }

    /** Rend l'une après l'autre les quêtes prêtes à rendre ici (à distance : seulement celles qui le permettent). */
    private void deliverAll(Player player) {
        String place = remote ? "remote" : "giver";
        List<QuestInstance> ready = questAt.values().stream().map(this::current)
                .filter(quest -> quests.ready(player, quest) && accepts(quest, place)).toList();
        if (ready.isEmpty()) {
            Sounds.refuse(player);
            messages.send(player, "quest.none-ready");
            return;
        }
        Sounds.click(player);
        player.closeInventory();
        deliverNext(player, ready, 0);
    }

    private boolean accepts(QuestInstance quest, String place) {
        Quest definition = quests.quest(quest.quest()).orElse(null);
        return definition != null && definition.stage(quest.stage()).accepts(place)
                && !(place.equals("remote") && definition.stage(quest.stage()).hasDeliveries());
    }

    private void deliverNext(Player player, List<QuestInstance> ready, int index) {
        if (index >= ready.size()) {
            gui.openQuests(player, board.job(), remote);
            return;
        }
        service.deliver(player, ready.get(index), remote, () -> deliverNext(player, ready, index + 1));
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private void render() {
        Frame.draw(inventory, Material.ORANGE_STAINED_GLASS_PANE);
        inventory.setItem(INFO, info());
        for (QuestInstance quest : JobQuests.dailies(board.quests())) {
            int index = JobQuests.dailyIndex(quest);
            if (index >= 0 && index < DAILY_SLOTS.size()) {
                place(DAILY_SLOTS.get(index), quest);
            }
        }
        board.quests().stream().filter(JobQuests::isWeekly).findFirst().ifPresentOrElse(quest -> place(WEEKLY, quest),
                () -> inventory.setItem(WEEKLY, Items.item(Material.GRAY_DYE, text("quest.weekly.none"), List.of())));
        board.quests().stream().filter(JobQuests::isBonus).findFirst().ifPresentOrElse(quest -> place(BONUS, quest), () -> {
            if (service.settings().bonusQuest()) {
                inventory.setItem(BONUS, Items.item(Material.GRAY_DYE, text("quest.bonus.locked"), List.of(text("quest.bonus.locked-lore"))));
            }
        });
        board.quests().stream().filter(JobQuests::isStory).findFirst().ifPresentOrElse(quest -> place(STORY, quest),
                () -> inventory.setItem(STORY, Items.item(Material.BOOK, text("quest.story.none"), List.of(text("quest.story.none-lore")))));
        inventory.setItem(DELIVER_ALL, Items.item(Material.HOPPER, text("quest.menu.deliver-all"), List.of(text(remote
                ? "quest.menu.deliver-all-remote" : "quest.menu.deliver-all-lore"))));
        inventory.setItem(HISTORY, Items.item(Material.BOOK, text("quest.menu.history"), List.of(text("quest.menu.history-lore"))));
        inventory.setItem(PERKS, Items.item(Material.NETHER_STAR, text("quest.menu.perks"), List.of(text("quest.menu.perks-lore"))));
        inventory.setItem(GUILD, Items.item(Material.WHITE_BANNER, text("quest.menu.guild"), List.of(text("quest.menu.guild-lore"))));
        inventory.setItem(BACK, gui.backButton().item(viewer));
        inventory.setItem(BOX, Items.item(board.boxCount() > 0 ? Material.CHEST_MINECART : Material.MINECART, text("quest.menu.box"),
                List.of(text("quest.menu.box-lore", "count", String.valueOf(board.boxCount()))), board.boxCount() > 0));
        inventory.setItem(SIDEBAR, Items.item(Material.MAP, text("quest.menu.sidebar"), List.of(text("quest.menu.sidebar-lore"))));
        boolean quiet = quests.isQuiet(viewer);
        inventory.setItem(QUIET, Items.item(quiet ? Material.GRAY_DYE : Material.BELL, text("quest.menu.quiet"),
                List.of(text(quiet ? "quest.menu.quiet-on" : "quest.menu.quiet-off"))));
    }

    private ItemStack info() {
        Levels levels = service.levels();
        int xp = board.level().xp();
        int level = levels.level(xp);
        Rank rank = levels.rank(level);
        List<Component> lore = new ArrayList<>();
        lore.add(text("quest.menu.rank", "rank", service.rankName(viewer, rank), "level", String.valueOf(level)));
        int[] progress = levels.progress(xp);
        if (progress[1] > 0) {
            lore.add(messages.get(viewer, "quest.menu.level-bar", TagResolver.resolver(Placeholder.component("bar", bar(progress[0], progress[1]))),
                    "xp", String.valueOf(progress[0]), "needed", String.valueOf(progress[1])));
        } else {
            lore.add(text("quest.menu.level-max"));
        }
        Rank next = rank.next();
        if (next != null) {
            lore.add(text("quest.menu.next-rank", "rank", service.rankName(viewer, next), "level", String.valueOf(levels.levelOf(next))));
        }
        lore.add(text("quest.menu.completed", "count", String.valueOf(board.level().completed())));
        List<QuestInstance> daily = JobQuests.dailies(board.quests());
        lore.add(text("quest.menu.progress", "done", String.valueOf(daily.stream().filter(QuestInstance::done).count()),
                "total", String.valueOf(daily.size())));
        if (board.streak() > 0) {
            lore.add(text("quest.menu.streak", "days", String.valueOf(board.streak())));
        }
        if (service.settings().rerollEnabled()) {
            lore.add(text(!board.canReroll() ? "quest.menu.reroll-used" : board.rerollFree() ? "quest.menu.reroll-free" : "quest.menu.reroll-ready"));
        }
        double event = service.eventMultiplier(board.job());
        if (event > 1) {
            lore.add(text("quest.menu.event", "multiplier", String.valueOf(event)));
        }
        if (board.job().equals(service.weakestJob())) {
            lore.add(text("quest.menu.weakest", "bonus", String.valueOf(Math.round(service.settings().weakestBonus() * 100))));
        }
        lore.add(Component.empty());
        long left = service.secondsUntilTomorrow();
        lore.add(text(left < 3600 ? "quest.menu.reset-soon" : "quest.menu.reset", "time", gui.time(viewer, left)));
        lore.add(text("quest.menu.reset-weekly", "time", gui.time(viewer, service.secondsUntilNextWeek())));
        if (remote) {
            lore.add(text("quest.menu.remote"));
        }
        return Items.head(viewer.getPlayerProfile(), text("quest.menu.player", "player", viewer.getName(),
                "job", service.jobName(viewer, board.job())), lore);
    }

    private void place(int slot, QuestInstance shown) {
        QuestInstance quest = current(shown);
        questAt.put(slot, quest);
        Quest definition = quests.quest(quest.quest()).orElse(null);
        boolean ready = quests.ready(viewer, quest);
        List<Component> lore = new ArrayList<>();
        if (definition != null) {
            definition.description().forEach(line -> lore.add(messages.render(line, TagResolver.empty())));
        }
        lore.addAll(quests.lore(viewer, quest));
        lore.add(text("quest.item.reward", "reward", Money.format(JobQuests.reward(quest)), "xp", String.valueOf(JobQuests.xp(quest))));
        if (quest.done()) {
            lore.add(text("quest.item.done"));
        } else {
            if (quest.tracked()) {
                lore.add(text("quest.item.tracked"));
            }
            lore.add(Component.empty());
            boolean giverHere = definition != null && accepts(quest, remote ? "remote" : "giver");
            if (!giverHere) {
                lore.add(text(remote && definition != null && definition.stage(quest.stage()).hasDeliveries()
                        ? "quest.item.deliver-npc" : "quest.item.elsewhere"));
            } else {
                lore.add(text(ready ? "quest.item.deliver" : "quest.item.gather"));
            }
            lore.add(text(quest.tracked() ? "quest.item.untrack" : "quest.item.track"));
            if (JobQuests.isDaily(quest) && board.canReroll()) {
                lore.add(text("quest.item.reroll", "price", board.rerollFree() || service.settings().rerollCost() <= 0
                        ? messages.plain(viewer, "quest.reroll.free") : Money.format(service.settings().rerollCost())));
            }
        }
        Material icon = quest.done() ? Material.LIME_DYE : quests.icon(quest);
        inventory.setItem(slot, Items.item(icon, quests.name(viewer, quest), lore, ready || quest.tracked() || JobQuests.rare(quest)));
    }

    private static Component bar(int done, int total) {
        int filled = total <= 0 ? 20 : Math.min(20, done * 20 / total);
        return Component.text("|".repeat(filled), NamedTextColor.GREEN).append(Component.text("|".repeat(20 - filled), NamedTextColor.DARK_GRAY));
    }

    private Component text(String key, String... placeholders) {
        return messages.get(viewer, key, placeholders);
    }
}
