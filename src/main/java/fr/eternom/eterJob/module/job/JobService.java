package fr.eternom.eterJob.module.job;

import fr.eternom.eterEconomy.api.EconomyApi;
import fr.eternom.eterJob.api.JobChangeEvent;
import fr.eternom.eterJob.api.JobLevelUpEvent;
import fr.eternom.eterJob.api.JobPerks;
import fr.eternom.eterJob.api.JobQuestCompleteEvent;
import fr.eternom.eterJob.module.job.JobRepository.JobLevel;
import fr.eternom.eterJob.module.job.JobRepository.Member;
import fr.eternom.eterJob.module.job.QuestCatalog.Template;
import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.economy.Money;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * La guilde des métiers. Un métier à la fois (le premier est gratuit, en changer coûte change-cost et n'est possible
 * qu'une fois par change-cooldown-days), mais le niveau de CHAQUE métier pratiqué est gardé. Chaque jour : une quête par
 * niveau de « daily » (une petite chance qu'elle soit rare), jamais une d'hier, adaptées au rang du joueur (plus grandes,
 * mieux payées) ; puis la bonus ; une quête de la semaine ; et l'histoire du métier, une étape par rang.
 *
 * Valider : les actions doivent être faites, le donneur prend les objets (simples), la base marque la quête faite
 * seulement si c'est toujours la même (une seule fois, même sur deux serveurs), puis la récompense (bonus du niveau,
 * évènement, série, métier le moins pratiqué ; plafond par jour), l'XP de métier, et les rangs atteints sont payés.
 * Après une validation réussie, plus rien n'est rendu (pas de duplication).
 */
public class JobService {

    /** Ce que montre le donneur de quêtes (ou /job) : métier, niveau, quêtes, changement de quête, série, colis. */
    public record Board(String job, Member member, JobLevel level, List<Quest> quests, boolean canReroll, boolean rerollFree,
                        int streak, int boxCount) {
    }

    /** Ce que donne une validation (thread principal ensuite). */
    record Delivered(String job, Quest quest, double paid, boolean capped, int xp, int levelBefore, int levelAfter, int streak,
                     List<Rank> newRanks, boolean dailyDone) {
    }

    private final JavaPlugin plugin;
    private final JobRepository repository;
    private final Messages messages;
    private final ZoneId zone;
    private final String server;
    private volatile JobSettings settings;
    private volatile QuestCatalog catalog;
    private final QuestTexts texts;
    private final JobProgress progress;
    private volatile List<JobRepository.Event> events = List.of();
    private volatile String weakestJob;
    /** Métier et niveau des joueurs pour l'API (avantages d'EterResource...) : 30 s. */
    private final Map<UUID, CachedPerks> perksCache = new ConcurrentHashMap<>();

    private record CachedPerks(Optional<String> job, JobPerks perks, long at) {
    }

    private static final long CACHE_MILLIS = 30_000;

    public JobService(JavaPlugin plugin, JobRepository repository, JobSettings settings, QuestCatalog catalog, Messages messages,
                      ZoneId zone) {
        this.plugin = plugin;
        this.repository = repository;
        this.settings = settings;
        this.catalog = catalog;
        this.messages = messages;
        this.zone = zone;
        this.server = EterLib.get().getServerName();
        this.texts = new QuestTexts(messages);
        this.progress = new JobProgress(plugin, repository, this, messages, texts);
    }

    /** Évènements relus, métier le moins pratiqué recalculé, journal purgé : régulièrement (tâche de fond). */
    public void start() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            try {
                refreshEvents();
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Évènements des métiers non relus : " + e.getMessage());
            }
        }, 20, 20 * 60);
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            try {
                refreshWeakest();
                repository.purgeLog();
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Statistiques des métiers non relues : " + e.getMessage());
            }
        }, 40, 20 * 60 * 60);
    }

    /** /eterjob reload : nouveaux réglages et quêtes (thread principal). */
    public void reload(JobSettings fresh, QuestCatalog freshCatalog) {
        this.settings = fresh;
        this.catalog = freshCatalog;
        perksCache.clear();
    }

    // ---------- Accès ----------

    public JobSettings settings() {
        return settings;
    }

    public QuestCatalog catalog() {
        return catalog;
    }

    public Levels levels() {
        return settings.levels();
    }

    public JobProgress progress() {
        return progress;
    }

    public JobRepository repository() {
        return repository;
    }

    public QuestTexts texts() {
        return texts;
    }

    public Messages messages() {
        return messages;
    }

    public JavaPlugin plugin() {
        return plugin;
    }

    public long today() {
        return LocalDate.now(zone).toEpochDay();
    }

    /** Le lundi de la semaine en cours (jour julien) : la quête de la semaine change ce jour-là. */
    public long week() {
        return LocalDate.now(zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay();
    }

    public long secondsUntilTomorrow() {
        ZonedDateTime now = ZonedDateTime.now(zone);
        return Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(zone)).toSeconds();
    }

    public long secondsUntilNextWeek() {
        ZonedDateTime now = ZonedDateTime.now(zone);
        return Duration.between(now, LocalDate.ofEpochDay(week()).plusWeeks(1).atStartOfDay(zone)).toSeconds();
    }

    public String jobName(CommandSender viewer, String job) {
        return messages.raw(viewer, "job.name." + job) == null ? job : messages.plain(viewer, "job.name." + job);
    }

    public String rankName(CommandSender viewer, Rank rank) {
        return messages.plain(viewer, "rank." + rank.id());
    }

    // ---------- Tableau du joueur ----------

    /** Bloquant : ce que montre le donneur de quêtes de ce métier ; vide si le joueur n'en est pas membre. */
    public Optional<Board> board(UUID player, String job) {
        Optional<Member> member = repository.member(player);
        if (member.isEmpty() || !member.get().job().equals(job)) {
            return Optional.empty();
        }
        return Optional.of(board(player, member.get()));
    }

    /** Bloquant : le tableau du métier du joueur, s'il en a un. */
    public Optional<Board> board(UUID player) {
        return repository.member(player).map(member -> board(player, member));
    }

    private Board board(UUID player, Member member) {
        progress.flush(player);
        JobLevel level = repository.level(player, member.job());
        List<Quest> quests = ensureQuests(player, member, level);
        Rank rank = levels().rank(levels().level(level.xp()));
        return new Board(member.job(), member, level, quests,
                settings.rerollEnabled() && member.rerollDay() != today(), rank.atLeast(settings.rerollFreeRank()),
                JobRepository.activeStreak(member, today()), repository.boxCount(player));
    }

    /**
     * Les quêtes du joueur, tirées ce qu'il faut : quêtes du jour d'un autre jour (jamais celles d'hier), la bonus quand
     * elles sont faites, la quête de la semaine, l'étape d'histoire de son rang. Bloquant.
     */
    List<Quest> ensureQuests(UUID player, Member member, JobLevel jobLevel) {
        String job = member.job();
        long today = today();
        int level = levels().level(jobLevel.xp());
        Rank rank = levels().rank(level);
        QuestCatalog.JobQuests quests = catalog.of(job);
        List<Quest> current = new ArrayList<>(repository.quests(player));
        List<Quest> daily = current.stream().filter(Quest::isDaily).toList();
        if (daily.isEmpty() || daily.getFirst().day() != today) {
            // Nouveau jour : les quêtes d'hier sont retenues pour ne pas retomber dessus
            Set<String> recent = new HashSet<>(daily.stream().map(Quest::key).toList());
            if (member.recent() != null && daily.isEmpty()) {
                recent.addAll(Arrays.asList(member.recent().split("\\|")));
            }
            List<Quest> drawn = drawDaily(quests, today, rank, level, recent);
            repository.replaceDaily(player, drawn);
            repository.setRecent(player, String.join("|", drawn.stream().map(Quest::key).toList()));
            current.removeIf(quest -> quest.slot() <= JobSettings.BONUS_SLOT);
            current.addAll(drawn);
        }
        List<Quest> todays = current.stream().filter(Quest::isDaily).toList();
        boolean dailyDone = !todays.isEmpty() && todays.stream().allMatch(Quest::done);
        if (settings.bonusQuest() && dailyDone && current.stream().noneMatch(Quest::isBonus)) {
            Set<String> taken = new HashSet<>(current.stream().map(Quest::key).toList());
            pick(quests.of(QuestCatalog.Kind.DAILY), settings.bonusTier(), rank, taken).ifPresent(template -> {
                Quest bonus = fromTemplate(JobSettings.BONUS_SLOT, today, template, rank, level, false);
                repository.saveQuest(player, bonus);
                current.add(bonus);
            });
        }
        Optional<Quest> weekly = current.stream().filter(Quest::isWeekly).findFirst();
        if (settings.weekly() && (weekly.isEmpty() || weekly.get().day() != week())) {
            List<Template> choices = quests.of(QuestCatalog.Kind.WEEKLY).stream().filter(t -> rank.atLeast(t.minRank())).toList();
            current.removeIf(Quest::isWeekly);
            if (!choices.isEmpty()) {
                Quest drawn = fromTemplate(JobSettings.WEEKLY_SLOT, week(), choices.get(ThreadLocalRandom.current().nextInt(choices.size())),
                        rank, level, false);
                repository.saveQuest(player, drawn);
                current.add(drawn);
            } else {
                repository.deleteQuest(player, JobSettings.WEEKLY_SLOT);
            }
        }
        if (current.stream().noneMatch(Quest::isStory) && jobLevel.story() < Rank.values().length) {
            Rank step = Rank.values()[jobLevel.story()];
            if (rank.atLeast(step)) {
                quests.story(step).ifPresent(template -> {
                    Quest story = fromTemplate(JobSettings.STORY_SLOT, 0, template, Rank.APPRENTI, 1, false);
                    repository.saveQuest(player, story);
                    current.add(story);
                });
            }
        }
        current.sort((a, b) -> Integer.compare(a.slot(), b.slot()));
        return current;
    }

    /** Une quête par niveau de daily, différentes, jamais d'hier ; chacune a rare-chance d'être une rare. */
    private List<Quest> drawDaily(QuestCatalog.JobQuests quests, long today, Rank rank, int level, Set<String> recent) {
        Set<String> taken = new HashSet<>(recent);
        List<Quest> drawn = new ArrayList<>();
        List<Template> rares = new ArrayList<>(quests.of(QuestCatalog.Kind.RARE).stream().filter(t -> rank.atLeast(t.minRank())).toList());
        Collections.shuffle(rares);
        for (Tier tier : settings.daily()) {
            if (!rares.isEmpty() && ThreadLocalRandom.current().nextDouble() < settings.rareChance()) {
                Template rare = rares.removeFirst();
                taken.add(rare.key());
                drawn.add(fromTemplate(drawn.size(), today, rare, rank, level, true));
                continue;
            }
            Optional<Template> template = pick(quests.of(QuestCatalog.Kind.DAILY), tier, rank, taken);
            if (template.isEmpty()) {
                template = pick(quests.of(QuestCatalog.Kind.DAILY), tier, rank, new HashSet<>(drawn.stream().map(Quest::key).toList()));
            }
            template.ifPresent(found -> {
                taken.add(found.key());
                drawn.add(fromTemplate(drawn.size(), today, found, rank, level, false));
            });
        }
        return drawn;
    }

    /** Au hasard parmi celles du niveau et du rang ; à défaut (niveau vide), parmi toutes celles du rang. */
    private static Optional<Template> pick(List<Template> catalog, Tier tier, Rank rank, Set<String> taken) {
        List<Template> free = catalog.stream().filter(t -> rank.atLeast(t.minRank()) && !taken.contains(t.key())).toList();
        List<Template> sameTier = new ArrayList<>(free.stream().filter(t -> t.tier() == tier).toList());
        List<Template> choices = sameTier.isEmpty() ? new ArrayList<>(free) : sameTier;
        if (choices.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(choices.get(ThreadLocalRandom.current().nextInt(choices.size())));
    }

    /**
     * Recopie une quête du répertoire, à la taille du rang (quantités et récompense × scale), avec le bonus du niveau ;
     * la bonus × bonus-multiplier, une rare × rare-multiplier.
     */
    Quest fromTemplate(int slot, long day, Template template, Rank rank, int level, boolean rare) {
        double scale = levels().scale(rank);
        List<Objective> objectives = template.objectives().stream().map(objective -> objective.scaled(scale)).toList();
        double reward = template.reward() * scale * levels().rewardMultiplier(level);
        if (slot == JobSettings.BONUS_SLOT) {
            reward *= settings.bonusMultiplier();
        }
        if (rare) {
            reward *= settings.rareMultiplier();
        }
        int xp = template.xp() > 0 ? template.xp() : switch (slot) {
            case JobSettings.BONUS_SLOT -> settings.bonusXp();
            case JobSettings.WEEKLY_SLOT -> settings.weeklyXp();
            case JobSettings.STORY_SLOT -> settings.storyXp();
            default -> settings.xpOf(template.tier());
        };
        if (rare) {
            xp = (int) Math.round(xp * settings.rareMultiplier());
        }
        return new Quest(slot, day, template.tier(), objectives, Math.round(reward), xp, rare, List.of(0, 0, 0), false, false,
                template.key());
    }

    // ---------- Choisir ou changer de métier ----------

    /** Bloquant : métier actuel et délai avant de pouvoir en changer (secondes). */
    public long cooldownSeconds(Member member) {
        return Math.max(0, member.changedAt() + settings.changeCooldown().toMillis() - System.currentTimeMillis()) / 1000;
    }

    /** Thread principal : rejoindre ce métier (gratuit la première fois, sinon payant et limité dans le temps). */
    public void join(Player player, String job, Runnable after) {
        EconomyApi economy = EconomyApi.get().orElse(null);
        UUID uuid = player.getUniqueId();
        double cost = settings.changeCost();
        Tasks.async(plugin, player, () -> {
            Optional<Member> current = repository.member(uuid);
            if (current.isPresent()) {
                if (current.get().job().equals(job)) {
                    return new String[]{"job.already", ""};
                }
                if (cooldownSeconds(current.get()) > 0) {
                    return new String[]{"job.change-too-soon", ""};
                }
                if (cost > 0 && (economy == null || !economy.withdraw(uuid, cost, "EterJob · changement de métier"))) {
                    return new String[]{economy == null ? "economy.unavailable" : "job.change-not-enough", ""};
                }
            }
            repository.setJob(uuid, job, System.currentTimeMillis());
            perksCache.remove(uuid);
            return new String[]{current.isPresent() ? "job.changed" : "job.joined", current.map(Member::job).orElse("")};
        }, result -> {
            messages.send(player, result[0], "job", jobName(player, job), "price", Money.format(cost));
            if (result[0].equals("job.joined") || result[0].equals("job.changed")) {
                player.playSound(player, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.2f);
                Bukkit.getPluginManager().callEvent(new JobChangeEvent(player, result[1], job));
                progress.reload(player);
                after.run();
            } else {
                Sounds.refuse(player);
            }
        }, () -> messages.send(player, "error.generic"));
    }

    // ---------- Valider ----------

    /**
     * Thread principal : valider une quête. remote : depuis /job, seulement une quête sans objet à livrer. after est
     * lancé ensuite (menu rouvert), qu'elle soit validée ou non.
     */
    public void deliver(Player player, Quest shown, boolean remote, Runnable after) {
        EconomyApi economy = EconomyApi.get().orElse(null);
        if (economy == null) {
            messages.send(player, "economy.unavailable");
            return;
        }
        UUID uuid = player.getUniqueId();
        if (!progress.lock(uuid)) {
            return; // une validation est déjà en cours (double clic)
        }
        Quest quest = progress.current(uuid, shown);
        if (remote && (!quest.items().isEmpty() || !settings.remoteDeliver())) {
            progress.unlock(uuid);
            messages.send(player, "quest.remote-items");
            Sounds.refuse(player);
            return;
        }
        if (!quest.actionComplete()) {
            progress.unlock(uuid);
            messages.send(player, "quest.action-missing");
            Sounds.refuse(player);
            return;
        }
        List<Objective> items = quest.items();
        if (items.stream().anyMatch(item -> count(player.getInventory(), item) < item.amount())) {
            progress.unlock(uuid);
            messages.send(player, "quest.missing");
            Sounds.refuse(player);
            return;
        }
        items.forEach(item -> take(player.getInventory(), item));
        String name = player.getName();
        AtomicBoolean validated = new AtomicBoolean(false);
        Tasks.async(plugin, player, () -> {
            progress.flush(uuid);
            Optional<Quest> stored = repository.complete(uuid, quest);
            if (stored.isEmpty()) {
                return null; // déjà validée, changée ou périmée
            }
            validated.set(true);
            // Validée : les objets sont livrés. Une erreur après ce point ne les rend plus (pas de duplication)
            try {
                return pay(uuid, name, stored.get(), economy);
            } catch (RuntimeException e) {
                plugin.getLogger().severe("Quête validée pour " + name + " mais suite incomplète : " + e);
                return new Delivered("", stored.get(), 0, false, 0, 1, 1, 0, List.of(), false);
            }
        }, result -> {
            progress.unlock(uuid);
            if (result == null) {
                giveBack(player, items);
                messages.send(player, "quest.already-done");
                after.run();
                return;
            }
            delivered(player, result);
            after.run();
        }, () -> {
            progress.unlock(uuid);
            if (!validated.get()) {
                giveBack(player, items);
            }
            messages.send(player, "error.generic");
        }, result -> {
            progress.unlock(uuid);
            if (result == null) {
                // Pas validée, et le joueur est parti : ses objets attendent dans sa boîte
                Tasks.async(plugin, () -> repository.deposit(uuid, itemsText(items)), "Objets de quête non mis de côté pour " + uuid);
            }
        });
    }

    /** Bloquant, après la validation : récompense, XP, série, histoire, rangs, journal. */
    private Delivered pay(UUID uuid, String name, Quest quest, EconomyApi economy) {
        String job = repository.member(uuid).map(Member::job).orElse("");
        long today = today();
        double multiplier = eventMultiplier(job);
        if (job.equals(weakestJob)) {
            multiplier *= 1 + settings.weakestBonus();
        }
        int streak = repository.member(uuid).map(member -> JobRepository.activeStreak(member, today)).orElse(0);
        multiplier *= 1 + Math.min(settings.streakMax(), streak * settings.streakBonus());
        double reward = Math.round(quest.reward() * multiplier);
        double allowed = repository.addEarned(uuid, today, reward, settings.dailyMoneyCap());
        boolean capped = reward > allowed;
        double paid = Math.min(reward, allowed);
        if (paid > 0) {
            economy.deposit(uuid, paid, "EterJob · " + job);
        }
        int xp = (int) Math.round(quest.xp() * eventMultiplier(job));
        JobLevel before = repository.level(uuid, job);
        int xpAfter = repository.addXp(uuid, job, xp, true);
        int levelBefore = levels().level(before.xp());
        int levelAfter = levels().level(xpAfter);
        boolean dailyDone = false;
        if (quest.isDaily()) {
            List<Quest> quests = repository.quests(uuid);
            dailyDone = quests.stream().filter(Quest::isDaily).allMatch(Quest::done);
            if (dailyDone) {
                streak = repository.advanceStreak(uuid, today);
            }
        }
        if (quest.isStory() && repository.advanceStory(uuid, job, before.story())) {
            repository.deleteQuest(uuid, JobSettings.STORY_SLOT); // la prochaine étape arrive avec le rang suivant
        }
        List<Rank> newRanks = new ArrayList<>();
        Rank rankAfter = levels().rank(levelAfter);
        for (Rank rank : Rank.values()) {
            if (rank != Rank.APPRENTI && rankAfter.atLeast(rank) && repository.markRankRewarded(uuid, job, rank.ordinal())) {
                newRanks.add(rank);
                JobSettings.RankReward rankReward = settings.rankRewards().get(rank);
                if (rankReward != null && rankReward.money() > 0) {
                    economy.deposit(uuid, rankReward.money(), "EterJob · rang " + rank.id());
                }
            }
        }
        repository.log(new JobRepository.Entry(uuid, job, quest.kind(), quest.tier().id(), paid, xp, server, System.currentTimeMillis()));
        perksCache.remove(uuid);
        return new Delivered(job, quest, paid, capped, xp, levelBefore, levelAfter, streak, newRanks, dailyDone);
    }

    /** Thread principal : messages, sons, niveaux, rangs, événements. */
    private void delivered(Player player, Delivered result) {
        Quest quest = result.quest();
        progress.markDone(player, quest);
        messages.send(player, quest.isBonus() ? "quest.bonus-done" : quest.isStory() ? "quest.story-done"
                        : quest.isWeekly() ? "quest.weekly-done" : "quest.done",
                "reward", Money.format(result.paid()), "xp", String.valueOf(result.xp()));
        if (result.capped()) {
            messages.send(player, "quest.capped", "cap", Money.format(settings.dailyMoneyCap()));
        }
        if (result.dailyDone() && result.streak() > 1) {
            messages.send(player, "quest.streak", "days", String.valueOf(result.streak()),
                    "bonus", String.valueOf(Math.round(Math.min(settings.streakMax(), result.streak() * settings.streakBonus()) * 100)));
        }
        if (result.dailyDone() && settings.bonusQuest()) {
            messages.send(player, "quest.bonus-unlocked");
        }
        player.playSound(player, Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.3f);
        Bukkit.getPluginManager().callEvent(new JobQuestCompleteEvent(player, result.job(), quest.tier().id(), quest.isBonus(), quest.kind()));
        if (result.levelAfter() > result.levelBefore()) {
            levelUp(player, result);
        }
        for (Rank rank : result.newRanks()) {
            rankUp(player, result.job(), rank);
        }
    }

    private void levelUp(Player player, Delivered result) {
        Rank rank = levels().rank(result.levelAfter());
        boolean newRank = !result.newRanks().isEmpty();
        if (!newRank) {
            player.showTitle(Title.title(messages.get(player, "level.title", "level", String.valueOf(result.levelAfter())),
                    messages.get(player, "level.subtitle", "job", jobName(player, result.job())),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(2), Duration.ofMillis(600))));
            player.playSound(player, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
        }
        messages.send(player, "level.up", "level", String.valueOf(result.levelAfter()), "job", jobName(player, result.job()));
        Bukkit.getPluginManager().callEvent(new JobLevelUpEvent(player, result.job(), result.levelAfter(), rank.id(), newRank));
        progress.publishJob(player);
    }

    /** Un rang atteint : titre en grand, récompense (argent déjà versé, objets ici ou en boîte), annonce réseau du maître. */
    private void rankUp(Player player, String job, Rank rank) {
        player.showTitle(Title.title(messages.get(player, "rank.up-title", "rank", rankName(player, rank)),
                messages.get(player, "rank.up-subtitle", "job", jobName(player, job)),
                Title.Times.times(Duration.ofMillis(400), Duration.ofSeconds(3), Duration.ofMillis(800))));
        player.playSound(player, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        player.playSound(player, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 0.8f, 1f);
        JobSettings.RankReward reward = settings.rankRewards().get(rank);
        messages.send(player, "rank.up", "rank", rankName(player, rank), "job", jobName(player, job),
                "money", Money.format(reward == null ? 0 : reward.money()));
        if (reward != null && !reward.items().isEmpty()) {
            List<ItemStack> left = new ArrayList<>();
            for (Map.Entry<Material, Integer> item : reward.items()) {
                left.addAll(player.getInventory().addItem(ItemStack.of(item.getKey(), item.getValue())).values());
            }
            if (!left.isEmpty()) {
                UUID uuid = player.getUniqueId();
                String text = itemsText(left.stream().map(stack -> new Objective(Objective.Kind.ITEM, stack.getType().name(),
                        stack.getAmount())).toList());
                Tasks.async(plugin, () -> repository.deposit(uuid, text), "Récompense de rang non mise de côté");
                messages.send(player, "box.added");
            }
        }
        if (rank == Rank.MAITRE && settings.announceMaster()) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                messages.send(online, "rank.master-announce", "player", player.getName(), "job", jobName(online, job));
            }
            progress.announceMaster(player.getName(), job);
        }
    }

    // ---------- Changer une quête ----------

    /** Thread principal : changer une quête du jour pas encore faite contre une autre du même niveau (une fois par jour). */
    public void reroll(Player player, Quest quest, Runnable after) {
        if (!settings.rerollEnabled() || quest.done() || !quest.isDaily()) {
            return;
        }
        EconomyApi economy = EconomyApi.get().orElse(null);
        UUID uuid = player.getUniqueId();
        long today = today();
        Tasks.async(plugin, player, () -> {
            Optional<Member> member = repository.member(uuid);
            if (member.isEmpty() || quest.day() != today) {
                return "quest.reroll-old";
            }
            JobLevel jobLevel = repository.level(uuid, member.get().job());
            int level = levels().level(jobLevel.xp());
            Rank rank = levels().rank(level);
            double cost = rank.atLeast(settings.rerollFreeRank()) ? 0 : settings.rerollCost();
            Set<String> taken = new HashSet<>(repository.quests(uuid).stream().map(Quest::key).toList());
            List<Template> choices = catalog.of(member.get().job()).of(QuestCatalog.Kind.DAILY).stream()
                    .filter(t -> t.tier() == quest.tier() && rank.atLeast(t.minRank()) && !taken.contains(t.key())).toList();
            if (choices.isEmpty()) {
                return "quest.reroll-none";
            }
            if (!repository.claimReroll(uuid, today)) {
                return "quest.reroll-used";
            }
            if (cost > 0 && (economy == null || !economy.withdraw(uuid, cost, "EterJob · changement de quête"))) {
                repository.releaseReroll(uuid, today);
                return "quest.reroll-not-enough";
            }
            Quest replacement = fromTemplate(quest.slot(), today, choices.get(ThreadLocalRandom.current().nextInt(choices.size())),
                    rank, level, false);
            if (!repository.swapQuest(uuid, replacement)) {
                if (cost > 0) {
                    economy.deposit(uuid, cost, "EterJob · changement de quête (remboursé)");
                }
                repository.releaseReroll(uuid, today);
                return "quest.reroll-done";
            }
            return "quest.rerolled";
        }, result -> {
            messages.send(player, result, "price", Money.format(settings.rerollCost()));
            if (result.equals("quest.rerolled")) {
                player.playSound(player, Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 1f);
                progress.reload(player);
            }
            after.run();
        }, () -> messages.send(player, "error.generic"));
    }

    // ---------- Boîte de récupération ----------

    /** Thread principal : prend les colis qui tiennent dans l'inventaire ; les autres restent. */
    public void takeBox(Player player, Runnable after) {
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, player, () -> repository.box(uuid), box -> {
            if (box.isEmpty()) {
                messages.send(player, "box.empty");
                after.run();
                return;
            }
            for (Map.Entry<Long, String> parcel : box.entrySet()) {
                List<ItemStack> stacks = stacks(parseItems(parcel.getValue()));
                if (!fits(player.getInventory(), stacks)) {
                    messages.send(player, "box.full");
                    break;
                }
                long id = parcel.getKey();
                Tasks.async(plugin, player, () -> repository.take(uuid, id), taken -> {
                    if (taken) {
                        stacks.forEach(stack -> player.getInventory().addItem(stack));
                        messages.send(player, "box.taken");
                    }
                }, () -> { }, taken -> {
                    if (taken) {
                        Tasks.async(plugin, () -> repository.deposit(uuid, parcel.getValue()), "Colis non remis dans la boîte");
                    }
                });
            }
            after.run();
        }, () -> messages.send(player, "error.generic"));
    }

    // ---------- Évènements, métier le moins pratiqué, avantages ----------

    /** Le multiplicateur des évènements en cours pour ce métier (le plus grand). */
    public double eventMultiplier(String job) {
        long now = System.currentTimeMillis();
        return events.stream().filter(event -> event.until() > now && (event.job() == null || event.job().equals(job)))
                .mapToDouble(JobRepository.Event::multiplier).max().orElse(1);
    }

    public List<JobRepository.Event> events() {
        long now = System.currentTimeMillis();
        return events.stream().filter(event -> event.until() > now).toList();
    }

    /** Bloquant. */
    public void refreshEvents() {
        events = repository.events();
    }

    /** Bloquant : le métier le moins pratiqué des 7 derniers jours (sa récompense est plus haute). */
    private void refreshWeakest() {
        Map<String, Integer> counts = repository.completedByJob(System.currentTimeMillis() - 7L * 86_400_000L);
        weakestJob = settings.icons().keySet().stream().min((a, b) -> Integer.compare(counts.getOrDefault(a, 0), counts.getOrDefault(b, 0)))
                .orElse(null);
    }

    public String weakestJob() {
        return weakestJob;
    }

    /** Avantages du joueur (API, EterResource) : métier actuel et rang, gardés 30 s. Bloquant. */
    public JobPerks perks(UUID player) {
        return cached(player).perks();
    }

    /** Métier du joueur (API), gardé 30 s. Bloquant. */
    public Optional<String> jobOf(UUID player) {
        return cached(player).job();
    }

    private CachedPerks cached(UUID player) {
        CachedPerks cached = perksCache.get(player);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.at() < CACHE_MILLIS) {
            return cached;
        }
        Optional<String> job = repository.member(player).map(Member::job).filter(settings::exists);
        JobPerks perks = job.map(found -> settings.perks(found, levels().rank(levels().level(repository.level(player, found).xp()))))
                .orElse(JobPerks.NONE);
        CachedPerks fresh = new CachedPerks(job, perks, now);
        perksCache.put(player, fresh);
        return fresh;
    }

    // ---------- Objets ----------

    /** Objets simples (sans nom ni enchantement) d'un objectif ITEM dans l'inventaire, main secondaire comprise. */
    static int count(PlayerInventory inventory, Objective item) {
        int count = 0;
        for (ItemStack stack : contents(inventory)) {
            if (stack != null && plain(stack) && ObjectiveTargets.matchesItem(item, stack.getType())) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    private static List<ItemStack> contents(PlayerInventory inventory) {
        List<ItemStack> all = new ArrayList<>(Arrays.asList(inventory.getStorageContents()));
        all.add(inventory.getItemInOffHand());
        return all;
    }

    private static boolean plain(ItemStack stack) {
        return stack.isSimilar(ItemStack.of(stack.getType()));
    }

    private static void take(PlayerInventory inventory, Objective item) {
        int remaining = item.amount();
        ItemStack[] storage = inventory.getStorageContents();
        for (int i = 0; i < storage.length && remaining > 0; i++) {
            ItemStack stack = storage[i];
            if (stack != null && plain(stack) && ObjectiveTargets.matchesItem(item, stack.getType())) {
                int taken = Math.min(remaining, stack.getAmount());
                stack.setAmount(stack.getAmount() - taken);
                remaining -= taken;
                storage[i] = stack.getAmount() == 0 ? null : stack;
            }
        }
        inventory.setStorageContents(storage);
        ItemStack off = inventory.getItemInOffHand();
        if (remaining > 0 && plain(off) && ObjectiveTargets.matchesItem(item, off.getType())) {
            int taken = Math.min(remaining, off.getAmount());
            off.setAmount(off.getAmount() - taken);
            inventory.setItemInOffHand(off.getAmount() == 0 ? null : off);
        }
    }

    /** Rend les objets d'une quête refusée ; ce qui ne tient pas va dans la boîte (jamais au sol). */
    private void giveBack(Player player, List<Objective> items) {
        List<ItemStack> left = new ArrayList<>();
        for (ItemStack stack : stacks(items)) {
            left.addAll(player.getInventory().addItem(stack).values());
        }
        if (!left.isEmpty()) {
            UUID uuid = player.getUniqueId();
            String text = itemsText(left.stream().map(stack -> new Objective(Objective.Kind.ITEM, stack.getType().name(),
                    stack.getAmount())).toList());
            Tasks.async(plugin, () -> repository.deposit(uuid, text), "Objets non mis de côté pour " + player.getName());
            messages.send(player, "box.added");
        }
    }

    /** Les objets en piles normales (un groupe « #fish » : la morue). */
    static List<ItemStack> stacks(List<Objective> items) {
        List<ItemStack> stacks = new ArrayList<>();
        for (Objective item : items) {
            Material material = item.isGroup() ? Material.COD : Material.matchMaterial(item.target());
            if (material == null) {
                continue;
            }
            int remaining = item.amount();
            while (remaining > 0) {
                int size = Math.min(remaining, material.getMaxStackSize());
                stacks.add(ItemStack.of(material, size));
                remaining -= size;
            }
        }
        return stacks;
    }

    static String itemsText(List<Objective> items) {
        return String.join(";", items.stream().map(item -> item.target() + " " + item.amount()).toList());
    }

    static List<Objective> parseItems(String text) {
        List<Objective> items = new ArrayList<>();
        for (String part : text.split(";")) {
            String[] fields = part.trim().split("\\s+");
            if (fields.length == 2) {
                try {
                    items.add(new Objective(Objective.Kind.ITEM, fields[0], Integer.parseInt(fields[1])));
                } catch (NumberFormatException ignored) {
                    // entrée abîmée : ignorée
                }
            }
        }
        return items;
    }

    private static boolean fits(PlayerInventory inventory, List<ItemStack> stacks) {
        org.bukkit.inventory.Inventory copy = Bukkit.createInventory(null, 36);
        copy.setContents(Arrays.copyOf(inventory.getStorageContents(), 36));
        for (ItemStack stack : stacks) {
            if (!copy.addItem(stack.clone()).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** Pour les menus : un consommateur qui ne fait rien. */
    static <T> Consumer<T> nothing() {
        return value -> { };
    }
}
