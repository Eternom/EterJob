package fr.eternom.eterJob.module.job;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Une quête d'un joueur (recopiée du répertoire au tirage, adaptée à son rang). slot : 0 à 2 du jour, 3 la bonus,
 * 4 la semaine, 5 l'histoire. day : jour julien du tirage (la semaine : son lundi ; l'histoire : 0, elle ne périme pas).
 * progress : un compteur par action, dans l'ordre de actions(). key : les objectifs du répertoire (avant adaptation au
 * rang), pour ne pas retirer la même quête le lendemain.
 */
public record Quest(int slot, long day, Tier tier, List<Objective> objectives, double reward, int xp, boolean rare,
                    List<Integer> progress, boolean done, boolean tracked, String key) {

    public Quest {
        objectives = List.copyOf(objectives);
        progress = List.copyOf(progress);
    }

    public boolean isBonus() {
        return slot == JobSettings.BONUS_SLOT;
    }

    public boolean isWeekly() {
        return slot == JobSettings.WEEKLY_SLOT;
    }

    public boolean isStory() {
        return slot == JobSettings.STORY_SLOT;
    }

    public boolean isDaily() {
        return slot < JobSettings.BONUS_SLOT;
    }

    /** daily, bonus, weekly, story (les autres plugins). */
    public String kind() {
        return isBonus() ? "bonus" : isWeekly() ? "weekly" : isStory() ? "story" : rare ? "rare" : "daily";
    }

    /** Les objectifs d'action, au plus MAX_ACTIONS (un compteur en base pour chacun). */
    public List<Objective> actions() {
        return objectives.stream().filter(o -> o.kind().isAction()).limit(JobRepository.MAX_ACTIONS).toList();
    }

    public List<Objective> items() {
        return objectives.stream().filter(o -> o.kind() == Objective.Kind.ITEM).toList();
    }

    public int progress(int action) {
        return action < progress.size() ? progress.get(action) : 0;
    }

    public int progressOf(Objective objective) {
        int action = actions().indexOf(objective);
        return action < 0 ? 0 : progress(action);
    }

    /** Quantité à atteindre pour chaque compteur (0 pour un compteur inutilisé). */
    public List<Integer> targets() {
        List<Objective> actions = actions();
        return IntStream.range(0, JobRepository.MAX_ACTIONS).mapToObj(i -> i < actions.size() ? actions.get(i).amount() : 0).toList();
    }

    public boolean actionComplete() {
        List<Objective> actions = actions();
        return IntStream.range(0, actions.size()).allMatch(i -> progress(i) >= actions.get(i).amount());
    }

    public Quest withProgress(int action, int value) {
        List<Integer> next = new ArrayList<>(IntStream.range(0, JobRepository.MAX_ACTIONS).mapToObj(this::progress).toList());
        next.set(action, value);
        return new Quest(slot, day, tier, objectives, reward, xp, rare, next, done, tracked, key);
    }

    public Quest withDone() {
        return new Quest(slot, day, tier, objectives, reward, xp, rare, progress, true, false, key);
    }

    public Quest withTracked(boolean value) {
        return new Quest(slot, day, tier, objectives, reward, xp, rare, progress, done, value, key);
    }
}
