package fr.eternom.eterJob.module.menu;

import fr.eternom.eterJob.module.job.JobRepository;
import fr.eternom.eterJob.module.job.JobService;
import fr.eternom.eterJob.module.job.Levels;
import fr.eternom.eterJob.module.job.Rank;
import fr.eternom.eterLib.helper.economy.Money;
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
import java.util.Map;
import java.util.Optional;

/**
 * Le chef de guilde, 6 lignes : les métiers (son niveau et son rang dans chacun, les membres, le bonus de la semaine),
 * clic = le choisir (ou ses quêtes si c'est le sien), clic droit = son classement, Maj + clic = ses avantages.
 * <pre>
 *  ▣ ▣ ▣ ▣ ☺ ▣ ▣ ▣ ▣     ☺ = son métier, délai avant de pouvoir en changer, prix
 *  ▣ · · · · · · · ▣
 *  ▣ · m m m m m · ▣     les métiers
 *  ▣ · · · · · · · ▣
 *  ▣ · · ✧ · ☰ · · ▣     ✧ = avantages de son métier · ☰ = historique
 *  ▣ ▣ ▣ ▣ « ▣ ▣ ▣ ▣
 * </pre>
 */
class GuildMenu implements Menu {

    /** Ce que montre le chef de guilde. */
    record View(Optional<JobRepository.Member> member, Map<String, JobRepository.JobLevel> levels, Map<String, Integer> members,
                String weakest) {
    }

    private static final int INFO = 4;
    private static final int[] JOB_SLOTS = {20, 21, 22, 23, 24, 19, 25, 29, 30, 31, 32, 33};
    private static final int PERKS = 39;
    private static final int HISTORY = 41;
    private static final int BACK = 49;

    private final JobGui gui;
    private final JobService service;
    private final Messages messages;
    private final Player viewer;
    private final View view;
    private final List<String> jobs;
    private final Inventory inventory;

    GuildMenu(JobGui gui, Player viewer, View view) {
        this.gui = gui;
        this.service = gui.service();
        this.messages = gui.messages();
        this.viewer = viewer;
        this.view = view;
        this.jobs = new ArrayList<>(service.settings().icons().keySet());
        this.inventory = Bukkit.createInventory(this, 54, messages.get(viewer, "guild.title"));
        render();
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        for (int i = 0; i < jobs.size() && i < JOB_SLOTS.length; i++) {
            if (JOB_SLOTS[i] != slot) {
                continue;
            }
            String job = jobs.get(i);
            if (click.isRightClick()) {
                Sounds.page(player);
                gui.openRanking(player, job);
            } else if (click.isShiftClick()) {
                Sounds.page(player);
                gui.openPerks(player, job);
            } else if (view.member().map(member -> member.job().equals(job)).orElse(false)) {
                Sounds.page(player);
                gui.openQuests(player, job, false);
            } else if (view.member().map(member -> service.cooldownSeconds(member) > 0).orElse(false)) {
                Sounds.refuse(player);
                messages.send(player, "job.change-too-soon");
            } else {
                Sounds.click(player);
                gui.confirmJoin(player, job, view.member());
            }
            return;
        }
        switch (slot) {
            case PERKS -> {
                Sounds.page(player);
                gui.openPerks(player, null);
            }
            case HISTORY -> {
                Sounds.page(player);
                gui.openHistory(player);
            }
            case BACK -> gui.backButton().click(player);
            default -> {
            }
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private void render() {
        Frame.draw(inventory, Material.ORANGE_STAINED_GLASS_PANE);
        List<Component> info = new ArrayList<>();
        view.member().ifPresentOrElse(member -> {
            info.add(text("guild.current", "job", service.jobName(viewer, member.job())));
            long cooldown = service.cooldownSeconds(member);
            info.add(cooldown > 0 ? text("guild.cooldown", "time", gui.time(viewer, cooldown))
                    : text("guild.can-change", "price", Money.format(service.settings().changeCost())));
        }, () -> info.add(text("guild.no-job")));
        info.add(text("guild.kept"));
        inventory.setItem(INFO, Items.head(viewer.getPlayerProfile(), text("guild.player", "player", viewer.getName()), info));

        Levels levels = service.levels();
        for (int i = 0; i < jobs.size() && i < JOB_SLOTS.length; i++) {
            String job = jobs.get(i);
            JobRepository.JobLevel level = view.levels().get(job);
            boolean mine = view.member().map(member -> member.job().equals(job)).orElse(false);
            List<Component> lore = new ArrayList<>();
            lore.add(text("job.join.description." + job));
            lore.add(Component.empty());
            if (level != null && level.xp() > 0) {
                int lvl = levels.level(level.xp());
                Rank rank = levels.rank(lvl);
                lore.add(text("guild.job.level", "rank", service.rankName(viewer, rank), "level", String.valueOf(lvl),
                        "count", String.valueOf(level.completed())));
            } else {
                lore.add(text("guild.job.new"));
            }
            lore.add(text("guild.job.members", "count", String.valueOf(JobGui.members(view.members(), job))));
            if (job.equals(view.weakest())) {
                lore.add(text("guild.job.weakest", "bonus", String.valueOf(Math.round(service.settings().weakestBonus() * 100))));
            }
            lore.add(Component.empty());
            lore.add(text(mine ? "guild.job.mine" : view.member().isEmpty() ? "guild.job.join-free" : "guild.job.join"));
            lore.add(text("guild.job.more"));
            inventory.setItem(JOB_SLOTS[i], Items.item(service.settings().icon(job), text(mine ? "guild.job.name-mine" : "guild.job.name",
                    "job", service.jobName(viewer, job)), lore, mine));
        }
        inventory.setItem(PERKS, Items.item(Material.NETHER_STAR, text("quest.menu.perks"), List.of(text("quest.menu.perks-lore"))));
        inventory.setItem(HISTORY, Items.item(Material.BOOK, text("quest.menu.history"), List.of(text("quest.menu.history-lore"))));
        inventory.setItem(BACK, gui.backButton().item(viewer));
    }

    private Component text(String key, String... placeholders) {
        return messages.get(viewer, key, placeholders);
    }
}
