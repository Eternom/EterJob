package fr.eternom.eterJob.module.menu;

import fr.eternom.eterJob.module.job.Levels;
import fr.eternom.eterLib.helper.gui.Frame;
import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

import java.util.List;
import java.util.UUID;

/** Les 10 meilleurs d'un métier (niveau et rang), sur tout le réseau. */
class RankingMenu implements Menu {

    /** Une ligne du classement. */
    record Line(UUID player, String name, int xp) {
    }

    private static final int[] SLOTS = {13, 21, 23, 29, 30, 31, 32, 33, 39, 41};
    private static final int BACK = 49;
    private static final int MINE = 4;

    private final JobGui gui;
    private final Inventory inventory;

    RankingMenu(JobGui gui, Player viewer, String job, List<Line> lines, long[] mine) {
        this.gui = gui;
        Messages messages = gui.messages();
        this.inventory = Bukkit.createInventory(this, 54, messages.get(viewer, "ranking.title", "job", gui.service().jobName(viewer, job)));
        Frame.draw(inventory, Material.ORANGE_STAINED_GLASS_PANE);
        Levels levels = gui.service().levels();
        for (int i = 0; i < lines.size() && i < SLOTS.length; i++) {
            Line line = lines.get(i);
            int level = levels.level(line.xp());
            inventory.setItem(SLOTS[i], Items.head(Bukkit.createProfile(line.player(), line.name()),
                    messages.get(viewer, "ranking.line", "place", String.valueOf(i + 1), "player", line.name()),
                    List.of(messages.get(viewer, "ranking.level", "rank", gui.service().rankName(viewer, levels.rank(level)),
                            "level", String.valueOf(level), "xp", String.valueOf(line.xp())))));
        }
        // Sa propre place, même hors du top 10
        Levels own = gui.service().levels();
        inventory.setItem(MINE, Items.head(viewer.getPlayerProfile(), mine[0] > 0
                ? messages.get(viewer, "ranking.mine", "place", String.valueOf(mine[0]))
                : messages.get(viewer, "ranking.mine-none"), mine[0] > 0
                ? List.of(messages.get(viewer, "ranking.level", "rank", gui.service().rankName(viewer, own.rank(own.level((int) mine[1]))),
                "level", String.valueOf(own.level((int) mine[1])), "xp", String.valueOf(mine[1])))
                : List.of()));
        if (lines.isEmpty()) {
            inventory.setItem(22, Items.item(Material.PAPER, messages.get(viewer, "ranking.empty"), List.of()));
        }
        inventory.setItem(BACK, Items.item(Material.OAK_DOOR, messages.get(viewer, "perks.back"), List.of()));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (slot == BACK) {
            Sounds.page(player);
            gui.openGuild(player);
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
