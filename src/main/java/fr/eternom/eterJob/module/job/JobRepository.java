package fr.eternom.eterJob.module.job;

import fr.eternom.eterJob.api.JobApi;
import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;
import fr.eternom.eterLib.helper.sql.Row;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Données de la guilde, communes au réseau. Appels bloquants : hors du thread principal.
 * - eterjob_members : le métier de chaque joueur, son dernier changement, son changement de quête du jour, sa série,
 *   ses quêtes d'hier (pour ne pas les retirer), ses gains du jour (plafond), messages coupés ;
 * - eterjob_levels : par joueur et par métier, l'XP, les quêtes accomplies, le dernier rang récompensé, l'histoire ;
 * - eterjob_box : l'ancienne boîte de récupération (reprise une fois par EterQuest, qui garde maintenant les quêtes) ;
 * - eterjob_log : le journal des validations (staff, historique, statistiques, métier le moins pratiqué) ;
 * - eterjob_events : évènements de métier (récompense et XP multipliées).
 * Chaque changement important est UNE requête qui vérifie ses conditions : deux serveurs ne paient jamais deux fois.
 */
public class JobRepository {

    private static final String MEMBERS = "members";
    private static final String LEVELS = "levels";
    private static final String BOX = "box";
    private static final String LOG = "log";
    private static final String EVENTS = "events";

    public record Member(String job, long changedAt, long rerollDay, int streak, long streakDay, String recent, boolean quiet,
                         long earnedDay, double earned) {
    }

    /** Un métier pratiqué par un joueur. story : étapes d'histoire faites ; rankRewarded : rangs déjà récompensés (ordinal). */
    public record JobLevel(String job, int xp, int completed, int rankRewarded, int story) {
    }

    /** Une validation du journal. */
    public record Entry(UUID player, String job, String kind, String tier, double reward, int xp, String server, long at) {
    }

    /** Un évènement en cours (job null : tous les métiers). */
    public record Event(String job, double multiplier, long until, String label) {
    }

    private final Database database;

    public JobRepository(Database database) {
        this.database = database;
        database.createTable(MEMBERS,
                Column.of("uuid", Column.Type.UUID).primaryKey(),
                Column.of("job", Column.Type.STRING).length(32).notNull(),
                Column.of("changed_at", Column.Type.LONG).notNull(),
                Column.of("reroll_day", Column.Type.LONG),
                Column.of("streak", Column.Type.INT),
                Column.of("streak_day", Column.Type.LONG),
                Column.of("recent", Column.Type.TEXT),
                Column.of("quiet", Column.Type.BOOLEAN),
                Column.of("earned_day", Column.Type.LONG),
                Column.of("earned", Column.Type.DOUBLE));
        database.createTable(LEVELS,
                Column.of("uuid", Column.Type.UUID).primaryKey(),
                Column.of("job", Column.Type.STRING).length(32).primaryKey(),
                Column.of("xp", Column.Type.INT).notNull(),
                Column.of("completed", Column.Type.INT).notNull(),
                Column.of("rank_rewarded", Column.Type.INT).notNull(),
                Column.of("story", Column.Type.INT).notNull());
        database.createTable(BOX,
                Column.of("id", Column.Type.LONG).autoIncrement(),
                Column.of("uuid", Column.Type.UUID).notNull(),
                Column.of("items", Column.Type.TEXT).notNull(),
                Column.of("created", Column.Type.LONG).notNull());
        database.createTable(LOG,
                Column.of("id", Column.Type.LONG).autoIncrement(),
                Column.of("uuid", Column.Type.UUID).notNull(),
                Column.of("job", Column.Type.STRING).length(32).notNull(),
                Column.of("kind", Column.Type.STRING).length(16).notNull(),
                Column.of("tier", Column.Type.STRING).length(16).notNull(),
                Column.of("reward", Column.Type.DOUBLE).notNull(),
                Column.of("xp", Column.Type.INT).notNull(),
                Column.of("server", Column.Type.STRING).length(64),
                Column.of("logged_at", Column.Type.LONG).notNull());
        database.createTable(EVENTS,
                Column.of("id", Column.Type.LONG).autoIncrement(),
                Column.of("job", Column.Type.STRING).length(32),
                Column.of("multiplier", Column.Type.DOUBLE).notNull(),
                Column.of("ends_at", Column.Type.LONG).notNull(),
                Column.of("label", Column.Type.STRING).length(128));
        database.execute("CREATE INDEX IF NOT EXISTS eterjob_log_uuid ON " + database.table(LOG) + " (uuid, logged_at)");
        database.execute("CREATE INDEX IF NOT EXISTS eterjob_log_at ON " + database.table(LOG) + " (logged_at)");
        database.execute("CREATE INDEX IF NOT EXISTS eterjob_box_uuid ON " + database.table(BOX) + " (uuid)");
    }

    // ---------- Membres ----------

    public Optional<Member> member(UUID player) {
        return database.getFirst(MEMBERS, Map.of("uuid", player)).map(row -> new Member(row.getString("job"), row.getLong("changed_at"),
                row.get("reroll_day") == null ? -1 : row.getLong("reroll_day"), row.getInt("streak"),
                row.get("streak_day") == null ? -1 : row.getLong("streak_day"), row.getString("recent"), row.getBoolean("quiet"),
                row.get("earned_day") == null ? -1 : row.getLong("earned_day"), row.getDouble("earned")));
    }

    /** Choisit ou change de métier (ses quêtes en cours sont effacées par JobService) ; son niveau reste. */
    public void setJob(UUID player, String job, long now) {
        database.execute("INSERT INTO " + database.table(MEMBERS) + " (uuid, job, changed_at) VALUES (?, ?, ?)"
                + " ON DUPLICATE KEY UPDATE job = ?, changed_at = ?, recent = NULL", player, job, now, job, now);
        ensureLevel(player, job);
    }

    public void resetCooldown(UUID player) {
        database.update(MEMBERS, Map.of("changed_at", 0L), Map.of("uuid", player));
    }

    public void removeMember(UUID player) {
        database.delete(MEMBERS, Map.of("uuid", player));
    }

    public void setQuiet(UUID player, boolean quiet) {
        database.execute("UPDATE " + database.table(MEMBERS) + " SET quiet = ? WHERE uuid = ?", quiet, player);
    }

    /** Réserve le changement de quête du jour : false s'il a déjà été utilisé aujourd'hui (même ailleurs). */
    public boolean claimReroll(UUID player, long today) {
        return database.execute("UPDATE " + database.table(MEMBERS) + " SET reroll_day = ? WHERE uuid = ?"
                + " AND (reroll_day IS NULL OR reroll_day <> ?)", today, player, today) > 0;
    }

    public void releaseReroll(UUID player, long today) {
        database.execute("UPDATE " + database.table(MEMBERS) + " SET reroll_day = NULL WHERE uuid = ? AND reroll_day = ?", player, today);
    }

    public void clearReroll(UUID player) {
        database.execute("UPDATE " + database.table(MEMBERS) + " SET reroll_day = NULL WHERE uuid = ?", player);
    }

    /** Quêtes du jour d'hier (clés), pour ne pas les retirer aujourd'hui. */
    public void setRecent(UUID player, String recent) {
        database.execute("UPDATE " + database.table(MEMBERS) + " SET recent = ? WHERE uuid = ?", recent, player);
    }

    /**
     * Toutes les quêtes du jour faites : la série avance d'un jour (une seule fois par jour), ou repart à 1 si hier a été
     * manqué. Renvoie la série du jour.
     */
    public int advanceStreak(UUID player, long today) {
        database.execute("UPDATE " + database.table(MEMBERS) + " SET streak = IF(streak_day = ?, COALESCE(streak, 0) + 1, 1),"
                + " streak_day = ? WHERE uuid = ? AND (streak_day IS NULL OR streak_day < ?)", today - 1, today, player, today);
        return member(player).map(Member::streak).orElse(0);
    }

    /** La série encore valable aujourd'hui (faite hier ou aujourd'hui), 0 sinon. */
    public static int activeStreak(Member member, long today) {
        return member.streakDay() >= today - 1 ? member.streak() : 0;
    }

    /** Ajoute aux gains du jour (plafond) ; renvoie ce qui reste permis avant ce gain (Double.MAX_VALUE sans plafond). */
    public double addEarned(UUID player, long today, double amount, double cap) {
        if (cap <= 0) {
            return Double.MAX_VALUE;
        }
        Member member = member(player).orElse(null);
        double before = member == null || member.earnedDay() != today ? 0 : member.earned();
        database.execute("UPDATE " + database.table(MEMBERS) + " SET earned = IF(earned_day = ?, COALESCE(earned, 0) + ?, ?),"
                + " earned_day = ? WHERE uuid = ?", today, amount, amount, today, player);
        return Math.max(0, cap - before);
    }

    // ---------- Niveaux ----------

    public void ensureLevel(UUID player, String job) {
        database.execute("INSERT IGNORE INTO " + database.table(LEVELS) + " (uuid, job, xp, completed, rank_rewarded, story)"
                + " VALUES (?, ?, 0, 0, 0, 0)", player, job);
    }

    public JobLevel level(UUID player, String job) {
        return database.getFirst(LEVELS, Map.of("uuid", player, "job", job)).map(JobRepository::toLevel)
                .orElse(new JobLevel(job, 0, 0, 0, 0));
    }

    /** Tous les métiers pratiqués par le joueur. */
    public Map<String, JobLevel> levels(UUID player) {
        Map<String, JobLevel> levels = new LinkedHashMap<>();
        database.get(LEVELS, Map.of("uuid", player)).forEach(row -> levels.put(row.getString("job"), toLevel(row)));
        return levels;
    }

    /** XP et quête accomplie d'un coup ; renvoie l'XP après. */
    public int addXp(UUID player, String job, int xp, boolean completed) {
        ensureLevel(player, job);
        database.execute("UPDATE " + database.table(LEVELS) + " SET xp = GREATEST(0, xp + ?), completed = completed + ?"
                + " WHERE uuid = ? AND job = ?", xp, completed ? 1 : 0, player, job);
        return level(player, job).xp();
    }

    public void setXp(UUID player, String job, int xp) {
        ensureLevel(player, job);
        database.update(LEVELS, Map.of("xp", Math.max(0, xp)), Map.of("uuid", player, "job", job));
    }

    public void setCompleted(UUID player, String job, int completed) {
        ensureLevel(player, job);
        database.update(LEVELS, Map.of("completed", Math.max(0, completed)), Map.of("uuid", player, "job", job));
    }

    /** Marque un rang récompensé ; true pour le seul appel qui le marque (il donne la récompense). */
    public boolean markRankRewarded(UUID player, String job, int rank) {
        return database.execute("UPDATE " + database.table(LEVELS) + " SET rank_rewarded = ? WHERE uuid = ? AND job = ?"
                + " AND rank_rewarded < ?", rank, player, job, rank) > 0;
    }

    /** Une étape d'histoire faite (une seule fois). */
    public boolean advanceStory(UUID player, String job, int from) {
        return database.execute("UPDATE " + database.table(LEVELS) + " SET story = ? WHERE uuid = ? AND job = ? AND story = ?",
                from + 1, player, job, from) > 0;
    }

    public void setStory(UUID player, String job, int story) {
        ensureLevel(player, job);
        database.update(LEVELS, Map.of("story", Math.max(0, story)), Map.of("uuid", player, "job", job));
    }

    /** Classement des quêtes validées (tous métiers), du premier au dernier. */
    public List<JobApi.Ranked> topCompleted(int limit) {
        return database.query("SELECT uuid, SUM(completed) AS total FROM " + database.table(LEVELS)
                        + " GROUP BY uuid HAVING total > 0 ORDER BY total DESC LIMIT " + Math.max(1, limit)).stream()
                .map(row -> new JobApi.Ranked(row.getUUID("uuid"), row.getLong("total"))).toList();
    }

    public int completedRank(UUID player) {
        long mine = levels(player).values().stream().mapToLong(JobLevel::completed).sum();
        if (mine <= 0) {
            return 0;
        }
        return (int) database.query("SELECT COUNT(*) AS above FROM (SELECT uuid, SUM(completed) AS total FROM "
                + database.table(LEVELS) + " GROUP BY uuid) t WHERE total > ?", mine).getFirst().getLong("above") + 1;
    }

    /** Classement d'un métier par XP (niveau). */
    public List<JobApi.Ranked> topOfJob(String job, int limit) {
        return database.query("SELECT uuid, xp FROM " + database.table(LEVELS) + " WHERE job = ? AND xp > 0 ORDER BY xp DESC LIMIT "
                + Math.max(1, limit), job).stream().map(row -> new JobApi.Ranked(row.getUUID("uuid"), row.getLong("xp"))).toList();
    }

    /** Membres de chaque métier (statistiques de la guilde). */
    public Map<String, Integer> membersByJob() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        database.query("SELECT job, COUNT(*) AS total FROM " + database.table(MEMBERS) + " GROUP BY job")
                .forEach(row -> counts.put(row.getString("job"), row.getInt("total")));
        return counts;
    }

    // ---------- Boîte de récupération (reprise unique vers EterQuest) ----------

    /**
     * Les colis d'avant EterQuest : rendus une fois (uuid, objets) puis effacés. La boîte est maintenant celle d'EterQuest
     * (/quests box, et le bouton du menu des quêtes).
     */
    public List<Map.Entry<UUID, String>> drainOldBox() {
        List<Map.Entry<UUID, String>> parcels = new java.util.ArrayList<>();
        for (Row row : database.query("SELECT id, uuid, items FROM " + database.table(BOX) + " ORDER BY id")) {
            if (database.delete(BOX, Map.of("id", row.getLong("id"))) > 0) {
                parcels.add(Map.entry(row.getUUID("uuid"), row.getString("items")));
            }
        }
        return parcels;
    }

    // ---------- Journal ----------

    public void log(Entry entry) {
        database.insert(LOG, Map.of("uuid", entry.player(), "job", entry.job(), "kind", entry.kind(), "tier", entry.tier(),
                "reward", entry.reward(), "xp", entry.xp(), "server", entry.server() == null ? "" : entry.server(), "logged_at", entry.at()));
    }

    /** Les validations d'un joueur depuis since (les plus récentes d'abord). */
    public List<Entry> history(UUID player, long since, int limit) {
        return database.query("SELECT * FROM " + database.table(LOG) + " WHERE uuid = ? AND logged_at >= ? ORDER BY logged_at DESC LIMIT "
                + Math.max(1, limit), player, since).stream().map(JobRepository::toEntry).toList();
    }

    /** Validations et argent versé par jour et par métier depuis since : jour -> métier -> {quêtes, argent}. */
    public Map<String, Map<String, double[]>> stats(long since) {
        Map<String, Map<String, double[]>> stats = new LinkedHashMap<>();
        for (Row row : database.query("SELECT FROM_UNIXTIME(logged_at / 1000, '%Y-%m-%d') AS day, job, COUNT(*) AS quests,"
                + " SUM(reward) AS money FROM " + database.table(LOG) + " WHERE logged_at >= ? GROUP BY day, job ORDER BY day DESC", since)) {
            stats.computeIfAbsent(row.getString("day"), day -> new LinkedHashMap<>())
                    .put(row.getString("job"), new double[]{row.getDouble("quests"), row.getDouble("money")});
        }
        return stats;
    }

    /** Validations par métier depuis since (le métier le moins pratiqué). */
    public Map<String, Integer> completedByJob(long since) {
        Map<String, Integer> counts = new HashMap<>();
        database.query("SELECT job, COUNT(*) AS total FROM " + database.table(LOG) + " WHERE logged_at >= ? GROUP BY job", since)
                .forEach(row -> counts.put(row.getString("job"), row.getInt("total")));
        return counts;
    }

    /** Le journal est gardé 60 jours. */
    public void purgeLog() {
        database.execute("DELETE FROM " + database.table(LOG) + " WHERE logged_at < ?", System.currentTimeMillis() - 60L * 86_400_000L);
    }

    // ---------- Évènements ----------

    public void addEvent(Event event) {
        Map<String, Object> values = new HashMap<>(Map.of("multiplier", event.multiplier(), "ends_at", event.until(),
                "label", event.label() == null ? "" : event.label()));
        values.put("job", event.job());
        database.insert(EVENTS, values);
    }

    public List<Event> events() {
        return database.query("SELECT * FROM " + database.table(EVENTS) + " WHERE ends_at > ?", System.currentTimeMillis()).stream()
                .map(row -> new Event(row.getString("job"), row.getDouble("multiplier"), row.getLong("ends_at"), row.getString("label")))
                .toList();
    }

    public void clearEvents() {
        database.execute("DELETE FROM " + database.table(EVENTS));
    }


    private static JobLevel toLevel(Row row) {
        return new JobLevel(row.getString("job"), row.getInt("xp"), row.getInt("completed"), row.getInt("rank_rewarded"),
                row.getInt("story"));
    }

    private static Entry toEntry(Row row) {
        return new Entry(row.getUUID("uuid"), row.getString("job"), row.getString("kind"), row.getString("tier"),
                row.getDouble("reward"), row.getInt("xp"), row.getString("server"), row.getLong("logged_at"));
    }
}
