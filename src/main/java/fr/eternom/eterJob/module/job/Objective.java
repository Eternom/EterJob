package fr.eternom.eterJob.module.job;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Un objectif de quête (logique pure, testée) : « TYPE:CIBLE:QUANTITÉ ».
 * - ITEM : objets à livrer au donneur de quêtes (pris au moment de valider) ;
 * - les actions, comptées pendant le jeu : BREAK, HARVEST, KILL, FISH, CRAFT, SMELT, BREED, EXPLORE, ENCHANT, TRADE.
 * CIBLE : une matière, une créature, un biome (EXPLORE), ANY, ou un groupe « #ores ». La correspondance avec ce que fait
 * le joueur est dans ObjectiveTargets (elle touche au serveur). Au plus JobRepository.MAX_ACTIONS actions par quête.
 */
public record Objective(Kind kind, String target, int amount) {

    public enum Kind {
        ITEM, BREAK, HARVEST, KILL, FISH, CRAFT, SMELT, BREED, EXPLORE, ENCHANT, TRADE;

        public boolean isAction() {
            return this != ITEM;
        }

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static final String ANY = "ANY";

    public Objective {
        target = target.toUpperCase(Locale.ROOT).replace(' ', '_');
        if (target.startsWith("#")) {
            target = target.toLowerCase(Locale.ROOT);
        }
    }

    public boolean isAny() {
        return target.equals(ANY);
    }

    public boolean isGroup() {
        return target.startsWith("#");
    }

    /** La même cible, quantité multipliée (rang du joueur), au moins 1. */
    public Objective scaled(double factor) {
        if (kind == Kind.EXPLORE || factor == 1) {
            return this; // un biome se visite une fois
        }
        return new Objective(kind, target, Math.max(1, (int) Math.round(amount * factor)));
    }

    /** Lit « BREAK:STONE:128;ITEM:COAL:16 » (ou une seule) ; un morceau illisible est ignoré. Les doublons s'additionnent. */
    public static List<Objective> parse(String text) {
        List<Objective> objectives = new ArrayList<>();
        if (text == null) {
            return objectives;
        }
        for (String part : text.split(";")) {
            parseOne(part).ifPresent(objectives::add);
        }
        return merge(objectives);
    }

    public static java.util.Optional<Objective> parseOne(String part) {
        String[] fields = part.trim().split(":");
        if (fields.length != 3) {
            return java.util.Optional.empty();
        }
        try {
            Kind kind = Kind.valueOf(fields[0].trim().toUpperCase(Locale.ROOT));
            int amount = Integer.parseInt(fields[2].trim());
            String target = fields[1].trim();
            if (amount < 1 || target.isEmpty()) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(new Objective(kind, target, amount));
        } catch (IllegalArgumentException e) {
            return java.util.Optional.empty();
        }
    }

    public static String format(List<Objective> objectives) {
        return objectives.stream().map(Objective::format).collect(Collectors.joining(";"));
    }

    public String format() {
        return kind + ":" + target + ":" + amount;
    }

    /** Regroupe les objectifs identiques (deux fois le même objet = une seule ligne, quantités additionnées). */
    static List<Objective> merge(List<Objective> objectives) {
        Map<String, Objective> merged = new LinkedHashMap<>();
        for (Objective objective : objectives) {
            merged.merge(objective.kind() + ":" + objective.target(), objective,
                    (a, b) -> new Objective(a.kind(), a.target(), a.amount() + b.amount()));
        }
        return new ArrayList<>(merged.values());
    }
}
