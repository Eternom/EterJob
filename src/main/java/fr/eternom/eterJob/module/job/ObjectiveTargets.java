package fr.eternom.eterJob.module.job;

import fr.eternom.eterJob.module.job.Objective.Kind;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Tag;
import org.bukkit.block.Biome;
import org.bukkit.entity.EntityType;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Ce qu'un objectif vise, côté jeu : est-il valable, correspond-il à ce bloc, cette créature, cet objet, ce biome ; son
 * icône et son nom traduit. Les groupes : #ores, #logs, #crops, #fish, #stone, #flowers. Un minerai et sa version des
 * abîmes comptent pareil.
 */
public final class ObjectiveTargets {

    private static final Set<Material> CROPS = EnumSet.of(Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS,
            Material.NETHER_WART, Material.COCOA, Material.SWEET_BERRY_BUSH, Material.TORCHFLOWER_CROP, Material.PITCHER_CROP);
    private static final Set<Material> FISH = EnumSet.of(Material.COD, Material.SALMON, Material.TROPICAL_FISH, Material.PUFFERFISH);
    private static final Set<Material> STONE = EnumSet.of(Material.STONE, Material.DEEPSLATE, Material.ANDESITE, Material.DIORITE,
            Material.GRANITE, Material.TUFF, Material.CALCITE, Material.NETHERRACK, Material.BLACKSTONE, Material.BASALT);
    private static final Set<Material> EXTRA_ORES = EnumSet.of(Material.NETHER_QUARTZ_ORE, Material.NETHER_GOLD_ORE,
            Material.ANCIENT_DEBRIS);
    /** Icônes des cultures en place (blocs sans objet). */
    private static final Map<Material, Material> CROP_ICONS = Map.of(Material.WHEAT, Material.WHEAT, Material.CARROTS, Material.CARROT,
            Material.POTATOES, Material.POTATO, Material.BEETROOTS, Material.BEETROOT, Material.COCOA, Material.COCOA_BEANS,
            Material.SWEET_BERRY_BUSH, Material.SWEET_BERRIES, Material.TORCHFLOWER_CROP, Material.TORCHFLOWER,
            Material.PITCHER_CROP, Material.PITCHER_PLANT);

    private ObjectiveTargets() {
    }

    /** L'objectif est-il valable sur ce serveur (cible connue, du bon genre) ? */
    public static boolean valid(Objective objective) {
        String target = objective.target();
        if (objective.isGroup()) {
            return switch (target) {
                case "#ores", "#logs", "#stone", "#flowers" -> objective.kind() == Kind.BREAK;
                case "#crops" -> objective.kind() == Kind.HARVEST;
                case "#fish" -> objective.kind() == Kind.FISH || objective.kind() == Kind.ITEM || objective.kind() == Kind.SMELT;
                default -> false;
            };
        }
        return switch (objective.kind()) {
            case ITEM -> !objective.isAny() && material(target) != null && material(target).isItem();
            case BREAK -> !objective.isAny() && material(target) != null && material(target).isBlock();
            case HARVEST -> objective.isAny() || CROPS.contains(material(target));
            case KILL, BREED -> objective.isAny() || entity(target) != null && entity(target).isAlive();
            case FISH, CRAFT, SMELT, ENCHANT, TRADE -> objective.isAny() || material(target) != null && material(target).isItem();
            case EXPLORE -> biome(target) != null;
        };
    }

    /** Ce bloc cassé (ou cette culture récoltée) compte-t-il ? */
    public static boolean matchesBlock(Objective objective, Material block) {
        if (objective.isAny()) {
            return true;
        }
        return switch (objective.target()) {
            case "#ores" -> isOre(block);
            case "#logs" -> Tag.LOGS.isTagged(block);
            case "#stone" -> STONE.contains(block);
            case "#flowers" -> Tag.FLOWERS.isTagged(block);
            case "#crops" -> CROPS.contains(block);
            default -> {
                Material wanted = material(objective.target());
                yield wanted != null && oreFamily(wanted) == oreFamily(block);
            }
        };
    }

    /** Cet objet (pêché, fabriqué, sorti du four, enchanté, acheté) compte-t-il ? */
    public static boolean matchesItem(Objective objective, Material item) {
        if (objective.isAny()) {
            return true;
        }
        if (objective.target().equals("#fish")) {
            return FISH.contains(item);
        }
        return material(objective.target()) == item;
    }

    /** Cette créature compte-t-elle ? ANY pour KILL : un monstre seulement ; pour BREED : n'importe quel animal. */
    public static boolean matchesEntity(Objective objective, EntityType type, boolean enemy) {
        if (objective.isAny()) {
            return objective.kind() != Kind.KILL || enemy;
        }
        return entity(objective.target()) == type;
    }

    public static boolean matchesBiome(Objective objective, Biome biome) {
        NamespacedKey key = Registry.BIOME.getKey(biome);
        return key != null && key.getKey().toUpperCase(Locale.ROOT).equals(objective.target());
    }

    public static boolean isFish(Material material) {
        return FISH.contains(material);
    }

    public static boolean isCrop(Material material) {
        return CROPS.contains(material);
    }

    static boolean isOre(Material material) {
        return Tag.COAL_ORES.isTagged(material) || Tag.IRON_ORES.isTagged(material) || Tag.COPPER_ORES.isTagged(material)
                || Tag.GOLD_ORES.isTagged(material) || Tag.REDSTONE_ORES.isTagged(material) || Tag.LAPIS_ORES.isTagged(material)
                || Tag.DIAMOND_ORES.isTagged(material) || Tag.EMERALD_ORES.isTagged(material) || EXTRA_ORES.contains(material);
    }

    /** DEEPSLATE_X_ORE -> X_ORE ; autre bloc : lui-même. */
    static Material oreFamily(Material material) {
        String name = material.name();
        if (name.startsWith("DEEPSLATE_") && name.endsWith("_ORE")) {
            Material ore = Material.getMaterial(name.substring("DEEPSLATE_".length()));
            return ore == null ? material : ore;
        }
        return material;
    }

    // ---------- Affichage ----------

    /**
     * Nom de la cible, traduit par le client (« Lingot de fer », « Zombie », « Grottes luxuriantes ») ; null pour ANY et
     * pour un groupe (son nom est dans lang/ : quest.group.<groupe>).
     */
    public static Component name(Objective objective) {
        if (objective.isAny() || objective.isGroup()) {
            return null;
        }
        return switch (objective.kind()) {
            case KILL, BREED -> {
                EntityType entity = entity(objective.target());
                yield entity == null ? Component.text(objective.target()) : Component.translatable(entity.translationKey());
            }
            case EXPLORE -> {
                Biome biome = biome(objective.target());
                yield biome == null ? Component.text(objective.target()) : Component.translatable(biome.translationKey());
            }
            default -> {
                Material material = material(objective.target());
                yield material == null ? Component.text(objective.target()) : Component.translatable(material.translationKey());
            }
        };
    }

    /** Icône de l'objectif dans les menus. */
    public static Material icon(Objective objective) {
        Material fallback = switch (objective.kind()) {
            case ITEM, BREAK -> Material.PAPER;
            case HARVEST -> Material.WHEAT;
            case KILL -> Material.IRON_SWORD;
            case FISH -> Material.FISHING_ROD;
            case CRAFT -> Material.CRAFTING_TABLE;
            case SMELT -> Material.FURNACE;
            case BREED -> Material.WHEAT;
            case EXPLORE -> Material.COMPASS;
            case ENCHANT -> Material.ENCHANTING_TABLE;
            case TRADE -> Material.EMERALD;
        };
        if (objective.isAny() || objective.isGroup()) {
            return switch (objective.target()) {
                case "#ores" -> Material.IRON_ORE;
                case "#logs" -> Material.OAK_LOG;
                case "#stone" -> Material.STONE;
                case "#flowers" -> Material.POPPY;
                case "#crops" -> Material.WHEAT;
                case "#fish" -> Material.COD;
                default -> fallback;
            };
        }
        if (objective.kind() == Kind.KILL || objective.kind() == Kind.BREED) {
            EntityType entity = entity(objective.target());
            Material egg = entity == null ? null : Bukkit.getItemFactory().getSpawnEgg(entity);
            return egg == null ? fallback : egg;
        }
        if (objective.kind() == Kind.EXPLORE) {
            return fallback;
        }
        Material material = material(objective.target());
        if (material == null) {
            return fallback;
        }
        if (!material.isItem()) {
            return CROP_ICONS.getOrDefault(material, fallback);
        }
        return material;
    }

    static Material material(String name) {
        return Material.matchMaterial(name);
    }

    static EntityType entity(String name) {
        try {
            return EntityType.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static Biome biome(String name) {
        return Registry.BIOME.get(NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT)));
    }
}
