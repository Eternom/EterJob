package fr.eternom.eterJob.module.job;

import fr.eternom.eterJob.module.job.Objective.Kind;
import fr.eternom.eterLib.EterLib;
import io.papermc.paper.event.player.PlayerTradeEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Compte les actions des quêtes et charge les quêtes des joueurs qui arrivent. Contre la triche (le jeu plus dur, pas
 * plus facile) :
 * - tuer : créatures apparues naturellement (spawners de trial chamber : réglable), tuées PAR le joueur (le dernier coup
 *   vient de lui ou de son projectile : pas de chute, de noyade ni de piège) ;
 * - casser, récolter : pas un bloc posé par un joueur, une culture seulement mûre, pas dans le terrain d'un clan où le
 *   joueur ne peut pas construire ; une cadence anormale est signalée au staff ;
 * - pêcher : seuls les poissons comptent (réglable) ;
 * - rien en créatif ou spectateur, ni avant N minutes de jeu sur le serveur (JobProgress).
 */
public class JobProgressListener implements Listener {

    private static final Set<SpawnReason> NATURAL = EnumSet.of(SpawnReason.NATURAL, SpawnReason.JOCKEY, SpawnReason.MOUNT,
            SpawnReason.PATROL, SpawnReason.RAID, SpawnReason.REINFORCEMENTS, SpawnReason.SLIME_SPLIT, SpawnReason.VILLAGE_INVASION,
            SpawnReason.LIGHTNING);
    private static final long ALERT_COOLDOWN = 10 * 60_000L;

    private final JobProgress progress;
    private final JobService service;
    /** Blocs cassés dans la minute en cours, par joueur (cadence anormale). */
    private final Map<UUID, int[]> breaks = new HashMap<>();
    private final Map<UUID, Long> alerted = new HashMap<>();
    private long minute;

    public JobProgressListener(JobService service) {
        this.service = service;
        this.progress = service.progress();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        progress.join(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        progress.quit(event.getPlayer());
        breaks.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKill(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        Player killer = entity.getKiller();
        if (killer == null || !counts(killer) || entity instanceof Player || !natural(entity) || !killedBy(entity, killer)) {
            return;
        }
        boolean enemy = entity instanceof Enemy;
        progress.record(killer, Kind.KILL, objective -> ObjectiveTargets.matchesEntity(objective, entity.getType(), enemy), 1);
    }

    private boolean natural(LivingEntity entity) {
        SpawnReason reason = entity.getEntitySpawnReason();
        return NATURAL.contains(reason) || reason == SpawnReason.TRIAL_SPAWNER && service.settings().trialSpawners();
    }

    /** Le dernier coup vient du joueur (ou de sa flèche, de son animal apprivoisé) : pas d'un piège ni d'une chute. */
    private static boolean killedBy(LivingEntity entity, Player killer) {
        EntityDamageEvent last = entity.getLastDamageCause();
        if (!(last instanceof EntityDamageByEntityEvent byEntity)) {
            return false;
        }
        Entity damager = byEntity.getDamager();
        if (damager instanceof Projectile projectile) {
            return killer.equals(projectile.getShooter());
        }
        if (damager instanceof Tameable tamed) {
            return killer.equals(tamed.getOwner());
        }
        return killer.equals(damager);
    }

    /** HIGH : après les protections (claims), avant qu'EterLib retire la note « posé » (MONITOR). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        if (!counts(player) || EterLib.get().getPlacedBlocks().isPlaced(block) || !ownLand(player, block.getLocation())) {
            return;
        }
        watchRate(player);
        Material type = block.getType();
        if (block.getBlockData() instanceof Ageable crop) {
            if (crop.getAge() < crop.getMaximumAge()) {
                return; // pas mûre
            }
            if (ObjectiveTargets.isCrop(type)) {
                progress.record(player, Kind.HARVEST, objective -> ObjectiveTargets.matchesBlock(objective, type), 1);
            }
        }
        progress.record(player, Kind.BREAK, objective -> ObjectiveTargets.matchesBlock(objective, type), 1);
    }

    /** Pas de progrès dans le terrain d'un clan où le joueur ne peut pas construire (EterClan, s'il tourne). */
    private static boolean ownLand(Player player, Location location) {
        if (!Bukkit.getPluginManager().isPluginEnabled("EterClan")) {
            return true;
        }
        try {
            return ClanLand.canBuild(player, location);
        } catch (LinkageError e) {
            return true;
        }
    }

    /** Plus de break-alert-per-minute blocs comptés en une minute : le staff de ce serveur est prévenu (10 min au plus). */
    private void watchRate(Player player) {
        int limit = service.settings().rateAlertPerMinute();
        if (limit <= 0) {
            return;
        }
        long now = System.currentTimeMillis() / 60_000L;
        if (now != minute) {
            minute = now;
            breaks.clear();
        }
        int[] count = breaks.computeIfAbsent(player.getUniqueId(), uuid -> new int[1]);
        if (++count[0] == limit) {
            long last = alerted.getOrDefault(player.getUniqueId(), 0L);
            if (System.currentTimeMillis() - last > ALERT_COOLDOWN) {
                alerted.put(player.getUniqueId(), System.currentTimeMillis());
                service.plugin().getLogger().warning("[Métiers] cadence anormale : " + player.getName() + " a cassé " + limit
                        + " blocs en une minute");
                for (Player staff : Bukkit.getOnlinePlayers()) {
                    if (staff.hasPermission("eterjob.admin")) {
                        service.messages().send(staff, "admin.rate-alert", "player", player.getName(), "count", String.valueOf(limit));
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(event.getCaught() instanceof Item caught)
                || !counts(event.getPlayer())) {
            return;
        }
        Material type = caught.getItemStack().getType();
        int amount = caught.getItemStack().getAmount();
        boolean fish = ObjectiveTargets.isFish(type);
        progress.record(event.getPlayer(), Kind.FISH, objective -> (fish || !service.settings().fishOnlyFish() || !objective.isAny())
                && ObjectiveTargets.matchesItem(objective, type), amount);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !counts(player)) {
            return;
        }
        ItemStack result = event.getRecipe().getResult();
        int times = 1;
        if (event.isShiftClick()) {
            // Maj + clic : autant de fois que la grille le permet
            times = Integer.MAX_VALUE;
            for (ItemStack ingredient : event.getInventory().getMatrix()) {
                if (ingredient != null && !ingredient.getType().isAir()) {
                    times = Math.min(times, ingredient.getAmount());
                }
            }
            times = times == Integer.MAX_VALUE ? 1 : times;
        }
        Material type = result.getType();
        progress.record(player, Kind.CRAFT, objective -> ObjectiveTargets.matchesItem(objective, type), result.getAmount() * times);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSmelt(FurnaceExtractEvent event) {
        if (counts(event.getPlayer())) {
            Material type = event.getItemType();
            progress.record(event.getPlayer(), Kind.SMELT, objective -> ObjectiveTargets.matchesItem(objective, type), event.getItemAmount());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent event) {
        if (event.getBreeder() instanceof Player player && counts(player)) {
            progress.record(player, Kind.BREED, objective -> ObjectiveTargets.matchesEntity(objective, event.getEntity().getType(), false), 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        Player player = event.getEnchanter();
        if (counts(player)) {
            Material type = event.getItem().getType();
            progress.record(player, Kind.ENCHANT, objective -> ObjectiveTargets.matchesItem(objective, type), 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrade(PlayerTradeEvent event) {
        Player player = event.getPlayer();
        if (counts(player)) {
            Material type = event.getTrade().getResult().getType();
            progress.record(player, Kind.TRADE, objective -> ObjectiveTargets.matchesItem(objective, type), 1);
        }
    }

    private static boolean counts(Player player) {
        return player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE;
    }
}
