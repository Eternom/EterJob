package fr.eternom.eterJob.module.job;

import fr.eternom.eterLib.helper.message.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;

/**
 * Textes communs aux menus, à la sidebar, à la barre de boss et à l'action bar : nom d'une quête (sa sorte et son
 * niveau) et ligne d'un objectif avec sa progression (« Lingot de fer 12/16 », « Tuer Zombie 4/20 »). Les noms d'objets,
 * de créatures et de biomes sont traduits par le client ; ceux des groupes sont dans lang/ (quest.group.<groupe>).
 */
public class QuestTexts {

    private final Messages messages;

    QuestTexts(Messages messages) {
        this.messages = messages;
    }

    public Component tier(Player viewer, Tier tier) {
        return messages.get(viewer, "quest.tier." + tier.id());
    }

    /** « Quête facile », « ★ Quête bonus (normale) », « ✦ Quête rare », « Quête de la semaine », « Histoire ». */
    public Component name(Player viewer, Quest quest) {
        String key = quest.isBonus() ? "quest.item.name-bonus" : quest.isWeekly() ? "quest.item.name-weekly"
                : quest.isStory() ? "quest.item.name-story" : quest.rare() ? "quest.item.name-rare" : "quest.item.name";
        return messages.get(viewer, key, TagResolver.resolver(Placeholder.component("tier", tier(viewer, quest.tier()))));
    }

    /** Une ligne par objectif, have = ce que le joueur a (objets sur lui, ou progression de l'action). */
    public Component objective(Player viewer, Objective objective, int have) {
        String progressKey = have >= objective.amount() ? "quest.objective.progress-done" : "quest.objective.progress";
        return line(viewer, objective, messages.get(viewer, progressKey, "have", String.valueOf(Math.min(have, objective.amount())),
                "amount", String.valueOf(objective.amount())));
    }

    /** L'objectif sans progression (« Tuer Zombie »), pour une quête déjà faite. */
    public Component label(Player viewer, Objective objective) {
        return line(viewer, objective, Component.empty());
    }

    private Component line(Player viewer, Objective objective, Component progress) {
        Component target = objective.isGroup() ? messages.get(viewer, "quest.group." + objective.target().substring(1))
                : ObjectiveTargets.name(objective);
        String key = "quest.objective." + objective.kind().id() + (target == null ? "-any" : "");
        return messages.get(viewer, key, TagResolver.resolver(Placeholder.component("progress", progress),
                Placeholder.component("item", target == null ? Component.empty() : target)));
    }

    /** Ce que le joueur a pour cet objectif : objets simples sur lui, ou progression de l'action de la quête. */
    public static int have(Player player, Quest quest, Objective objective) {
        return objective.kind() == Objective.Kind.ITEM
                ? JobService.count(player.getInventory(), objective)
                : quest.progressOf(objective);
    }

    /** Tous les objectifs sont remplis : il ne reste qu'à valider. */
    public static boolean ready(Player player, Quest quest) {
        return !quest.done() && quest.objectives().stream().allMatch(o -> have(player, quest, o) >= o.amount());
    }
}
