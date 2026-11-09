package fr.eternom.eterJob.module.menu;

import fr.eternom.eterJob.module.job.JobRepository;
import fr.eternom.eterLib.helper.economy.Money;
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

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Les quêtes validées des 7 derniers jours (28 au plus, les plus récentes d'abord), avec récompense et XP. */
class HistoryMenu implements Menu {

    private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43};
    private static final int BACK = 49;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM HH:mm");

    private final JobGui gui;
    private final Inventory inventory;

    HistoryMenu(JobGui gui, Player viewer, List<JobRepository.Entry> entries) {
        this.gui = gui;
        Messages messages = gui.messages();
        this.inventory = Bukkit.createInventory(this, 54, messages.get(viewer, "history.title"));
        Frame.draw(inventory, Material.ORANGE_STAINED_GLASS_PANE);
        ZoneId zone = ZoneId.systemDefault();
        for (int i = 0; i < entries.size() && i < SLOTS.length; i++) {
            JobRepository.Entry entry = entries.get(i);
            inventory.setItem(SLOTS[i], Items.item(gui.service().settings().icon(entry.job()),
                    messages.get(viewer, "history.kind." + entry.kind(), "tier", messages.plain(viewer, "quest.tier." + entry.tier())),
                    List.of(messages.get(viewer, "history.job", "job", gui.service().jobName(viewer, entry.job())),
                            messages.get(viewer, "history.reward", "reward", Money.format(entry.reward()), "xp", String.valueOf(entry.xp())),
                            messages.get(viewer, "history.date", "date", DATE.format(Instant.ofEpochMilli(entry.at()).atZone(zone))))));
        }
        if (entries.isEmpty()) {
            inventory.setItem(22, Items.item(Material.PAPER, messages.get(viewer, "history.empty"), List.of()));
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
