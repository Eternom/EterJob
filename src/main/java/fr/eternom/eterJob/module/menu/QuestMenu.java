package fr.eternom.eterJob.module.menu;

import fr.eternom.eterJob.module.job.JobService;
import fr.eternom.eterJob.module.job.JobService.Board;
import fr.eternom.eterJob.module.job.Levels;
import fr.eternom.eterJob.module.job.Objective;
import fr.eternom.eterJob.module.job.ObjectiveTargets;
import fr.eternom.eterJob.module.job.Quest;
import fr.eternom.eterJob.module.job.QuestTexts;
import fr.eternom.eterJob.module.job.Rank;
import fr.eternom.eterLib.helper.economy.Money;
import fr.eternom.eterLib.helper.gui.Frame;
import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
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
 * Sur une quête : clic = valider, clic droit = la suivre dans la sidebar, Maj + clic = la changer (une par jour).
 */
class QuestMenu implements Menu {

    private static final int INFO = 4;
    private static final List<Integer> DAILY_SLOTS = List.of(20, 22, 24);
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
    private final Messages messages;
    private final QuestTexts texts;
    private final Player viewer;
    private final Board board;
    private final boolean remote;
    private final Inventory inventory;
    private final Map<Integer, Quest> questAt = new HashMap<>();

    QuestMenu(JobGui gui, Player viewer, Board board, boolean remote) {
        this.gui = gui;
        this.service = gui.service();
        this.messages = gui.messages();
        this.texts = service.texts();
        this.viewer = viewer;
        this.board = board;
        this.remote = remote;
        this.inventory = Bukkit.createInventory(this, 54, text("quest.menu.title", "job", service.jobName(viewer, board.job())));
        render();
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        Quest quest = questAt.get(slot);
        if (quest != null) {
            Quest current = service.progress().current(player.getUniqueId(), quest);
            if (current.done()) {
                Sounds.refuse(player);
            } else if (click.isShiftClick()) {
                if (!current.isDaily() || !board.canReroll()) {
                    Sounds.refuse(player);
                    return;
                }
                Sounds.click(player);
                gui.confirmReroll(player, board, current, remote);
            } else if (click.isRightClick()) {
                Sounds.click(player);
                service.progress().toggleTrack(player, current.slot());
                gui.openQuests(player, board.job(), remote);
            } else {
                Sounds.click(player);
                service.deliver(player, current, remote, () -> gui.openQuests(player, board.job(), remote));
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
                service.progress().toggleTrack(player, -2);
            }
            case QUIET -> {
                Sounds.click(player);
                service.progress().toggleQuiet(player);
                gui.openQuests(player, board.job(), remote);
            }
            default -> {
            }
        }
    }

    /** Valide l'une après l'autre les quêtes prêtes (à distance : seulement celles sans objet à livrer). */
    private void deliverAll(Player player) {
        List<Quest> ready = questAt.values().stream()
                .map(quest -> service.progress().current(player.getUniqueId(), quest))
                .filter(quest -> QuestTexts.ready(player, quest) && (!remote || quest.items().isEmpty()))
                .sorted((a, b) -> Integer.compare(a.slot(), b.slot())).toList();
        if (ready.isEmpty()) {
            Sounds.refuse(player);
            messages.send(player, "quest.none-ready");
            return;
        }
        Sounds.click(player);
        player.closeInventory();
        deliverNext(player, ready, 0);
    }

    private void deliverNext(Player player, List<Quest> ready, int index) {
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
        List<Quest> daily = board.quests().stream().filter(Quest::isDaily).toList();
        for (Quest quest : daily) {
            if (quest.slot() < DAILY_SLOTS.size()) {
                place(DAILY_SLOTS.get(quest.slot()), quest);
            }
        }
        board.quests().stream().filter(Quest::isWeekly).findFirst().ifPresentOrElse(quest -> place(WEEKLY, quest),
                () -> inventory.setItem(WEEKLY, Items.item(Material.GRAY_DYE, text("quest.weekly.none"), List.of())));
        board.quests().stream().filter(Quest::isBonus).findFirst().ifPresentOrElse(quest -> place(BONUS, quest), () -> {
            if (service.settings().bonusQuest()) {
                inventory.setItem(BONUS, Items.item(Material.GRAY_DYE, text("quest.bonus.locked"), List.of(text("quest.bonus.locked-lore"))));
            }
        });
        board.quests().stream().filter(Quest::isStory).findFirst().ifPresentOrElse(quest -> place(STORY, quest),
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
        boolean quiet = service.progress().isQuiet(viewer.getUniqueId());
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
        List<Quest> daily = board.quests().stream().filter(Quest::isDaily).toList();
        lore.add(text("quest.menu.progress", "done", String.valueOf(daily.stream().filter(Quest::done).count()),
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

    private void place(int slot, Quest shown) {
        Quest quest = service.progress().current(viewer.getUniqueId(), shown);
        questAt.put(slot, quest);
        boolean ready = QuestTexts.ready(viewer, quest);
        List<Component> lore = new ArrayList<>();
        for (Objective objective : quest.objectives()) {
            Component line = quest.done() ? texts.label(viewer, objective)
                    : texts.objective(viewer, objective, QuestTexts.have(viewer, quest, objective));
            lore.add(text("quest.item.objective").append(line));
        }
        lore.add(text("quest.item.reward", "reward", Money.format(quest.reward()), "xp", String.valueOf(quest.xp())));
        if (quest.done()) {
            lore.add(text("quest.item.done"));
        } else {
            quest.actions().stream().findFirst().ifPresent(action -> lore.add(text("quest.hint." + action.kind().id())));
            if (quest.tracked()) {
                lore.add(text("quest.item.tracked"));
            }
            lore.add(Component.empty());
            if (remote && !quest.items().isEmpty()) {
                lore.add(text("quest.item.deliver-npc"));
            } else {
                lore.add(text(ready ? "quest.item.deliver" : "quest.item.gather"));
            }
            lore.add(text(quest.tracked() ? "quest.item.untrack" : "quest.item.track"));
            if (quest.isDaily() && board.canReroll()) {
                lore.add(text("quest.item.reroll", "price", board.rerollFree() || service.settings().rerollCost() <= 0
                        ? messages.plain(viewer, "quest.reroll.free") : Money.format(service.settings().rerollCost())));
            }
        }
        Material icon = quest.done() ? Material.LIME_DYE : ObjectiveTargets.icon(quest.objectives().getFirst());
        inventory.setItem(slot, Items.item(icon, texts.name(viewer, quest), lore, ready || quest.tracked() || quest.rare()));
    }

    private static Component bar(int done, int total) {
        int filled = total <= 0 ? 20 : Math.min(20, done * 20 / total);
        return Component.text("|".repeat(filled), NamedTextColor.GREEN).append(Component.text("|".repeat(20 - filled), NamedTextColor.DARK_GRAY));
    }

    private Component text(String key, String... placeholders) {
        return messages.get(viewer, key, placeholders);
    }
}
