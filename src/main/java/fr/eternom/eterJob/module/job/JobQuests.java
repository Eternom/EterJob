package fr.eternom.eterJob.module.job;

import fr.eternom.eterQuest.api.QuestInstance;

import java.util.List;

/**
 * Les quêtes de métier d'un joueur, gardées par EterQuest (propriétaire « eterjob ») dans des emplacements :
 * daily-0 à daily-2 (du jour), bonus, weekly (la semaine), story (l'histoire du métier). Ce qu'EterJob y attache (meta,
 * fixé au tirage) : kind (daily, bonus, weekly, story), tier, reward (déjà à la taille du rang), xp, rare, day (jour
 * julien du tirage ; la semaine : son lundi), job.
 */
public final class JobQuests {

    public static final String OWNER = "eterjob";
    public static final String BONUS = "bonus";
    public static final String WEEKLY = "weekly";
    public static final String STORY = "story";
    private static final String DAILY = "daily-";

    private JobQuests() {
    }

    public static String daily(int index) {
        return DAILY + index;
    }

    public static boolean isDaily(QuestInstance quest) {
        return quest.slot().startsWith(DAILY);
    }

    /** Numéro d'une quête du jour (0 à 2), -1 sinon. */
    public static int dailyIndex(QuestInstance quest) {
        try {
            return isDaily(quest) ? Integer.parseInt(quest.slot().substring(DAILY.length())) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static boolean isBonus(QuestInstance quest) {
        return quest.slot().equals(BONUS);
    }

    public static boolean isWeekly(QuestInstance quest) {
        return quest.slot().equals(WEEKLY);
    }

    public static boolean isStory(QuestInstance quest) {
        return quest.slot().equals(STORY);
    }

    public static Tier tier(QuestInstance quest) {
        Tier tier = Tier.of(quest.meta("tier", "normal"));
        return tier == null ? Tier.NORMAL : tier;
    }

    public static double reward(QuestInstance quest) {
        return quest.metaNumber("reward", 0);
    }

    public static int xp(QuestInstance quest) {
        return (int) quest.metaNumber("xp", 0);
    }

    public static boolean rare(QuestInstance quest) {
        return Boolean.parseBoolean(quest.meta("rare", "false"));
    }

    public static long day(QuestInstance quest) {
        return (long) quest.metaNumber("day", -1);
    }

    /** daily, bonus, weekly, story, rare (journal, événement JobQuestCompleteEvent). */
    public static String kind(QuestInstance quest) {
        return isBonus(quest) ? "bonus" : isWeekly(quest) ? "weekly" : isStory(quest) ? "story" : rare(quest) ? "rare" : "daily";
    }

    /** Les quêtes du jour (dans l'ordre). */
    public static List<QuestInstance> dailies(List<QuestInstance> quests) {
        return quests.stream().filter(JobQuests::isDaily).sorted((a, b) -> Integer.compare(dailyIndex(a), dailyIndex(b))).toList();
    }
}
