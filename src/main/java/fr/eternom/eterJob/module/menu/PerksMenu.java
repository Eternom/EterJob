package fr.eternom.eterJob.module.menu;

import fr.eternom.eterJob.api.JobPerks;
import fr.eternom.eterJob.module.job.JobService;
import fr.eternom.eterJob.module.job.Rank;
import fr.eternom.eterLib.helper.gui.Frame;
import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Les avantages d'un métier dans le monde ressource, rang par rang (cumulés) : ce qui est débloqué (brillant) et ce qui
 * arrive aux rangs suivants. Ils ne s'appliquent que dans le monde ressource (EterResource).
 */
class PerksMenu implements Menu {

    private static final int[] RANK_SLOTS = {20, 21, 22, 23, 24};
    private static final int INFO = 4;
    private static final int BACK = 49;

    private final JobGui gui;
    private final JobService service;
    private final Messages messages;
    private final Player viewer;
    private final Inventory inventory;

    PerksMenu(JobGui gui, Player viewer, String job, Rank current) {
        this.gui = gui;
        this.service = gui.service();
        this.messages = gui.messages();
        this.viewer = viewer;
        this.inventory = Bukkit.createInventory(this, 54, messages.get(viewer, "perks.title", "job", service.jobName(viewer, job)));
        Frame.draw(inventory, Material.ORANGE_STAINED_GLASS_PANE);
        inventory.setItem(INFO, Items.item(service.settings().icon(job), messages.get(viewer, "perks.info", "job", service.jobName(viewer, job)),
                List.of(messages.get(viewer, "perks.info-lore"), messages.get(viewer, "perks.current", "rank", service.rankName(viewer, current)))));
        Rank[] ranks = Rank.values();
        for (int i = 0; i < ranks.length; i++) {
            Rank rank = ranks[i];
            boolean unlocked = current.atLeast(rank);
            List<Component> lore = new ArrayList<>();
            lore.add(messages.get(viewer, "perks.level", "level", String.valueOf(service.levels().levelOf(rank))));
            lore.add(Component.empty());
            JobPerks perks = service.settings().perks(job, rank);
            perks.effects().forEach((effect, amplifier) -> lore.add(messages.get(viewer, "perks.effect",
                    "effect", effectName(effect), "level", roman(amplifier + 1))));
            perks.values().forEach((key, value) -> {
                if (value > 0) {
                    lore.add(describe(key, value));
                }
            });
            if (perks.isEmpty()) {
                lore.add(messages.get(viewer, "perks.none"));
            }
            lore.add(Component.empty());
            lore.add(messages.get(viewer, unlocked ? "perks.unlocked" : "perks.locked"));
            inventory.setItem(RANK_SLOTS[i], Items.item(unlocked ? Material.LIME_STAINED_GLASS : Material.GRAY_STAINED_GLASS,
                    messages.get(viewer, "perks.rank", "rank", service.rankName(viewer, rank)), lore, unlocked && rank == current));
        }
        inventory.setItem(BACK, Items.item(Material.OAK_DOOR, messages.get(viewer, "perks.back"), List.of()));
    }

    /** « +30 % de minerais », « Abattage d'arbres », « Filons jusqu'à 8 blocs »... (lang : perks.value.<clé>). */
    private Component describe(String key, double value) {
        String langKey = "perks.value." + key;
        if (messages.raw(viewer, langKey) == null) {
            return Component.text("▸ " + key + " : " + value);
        }
        return messages.get(viewer, langKey, "percent", String.valueOf(Math.round(value * 100)),
                "value", value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value));
    }

    private String effectName(String effect) {
        String key = "perks.effects." + effect.toLowerCase(Locale.ROOT);
        return messages.raw(viewer, key) == null ? effect : messages.plain(viewer, key);
    }

    private static String roman(int level) {
        return switch (level) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            default -> String.valueOf(level);
        };
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
