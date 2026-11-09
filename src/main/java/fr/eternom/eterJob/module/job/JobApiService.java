package fr.eternom.eterJob.module.job;

import fr.eternom.eterJob.api.JobApi;
import fr.eternom.eterJob.api.JobPerks;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** L'API d'EterJob (JobApi) : le plugin vu de l'extérieur. */
public class JobApiService implements JobApi {

    private final JobService service;

    public JobApiService(JobService service) {
        this.service = service;
    }

    @Override
    public List<String> jobs() {
        return List.copyOf(service.settings().icons().keySet());
    }

    @Override
    public String jobName(CommandSender viewer, String job) {
        return service.jobName(viewer, job);
    }

    @Override
    public Optional<String> job(UUID player) {
        return service.jobOf(player);
    }

    @Override
    public int level(UUID player, String job) {
        return service.levels().level(service.repository().level(player, job).xp());
    }

    @Override
    public String rank(UUID player, String job) {
        return service.levels().rank(level(player, job)).id();
    }

    @Override
    public String rankName(CommandSender viewer, String rank) {
        Rank found = Rank.of(rank);
        return found == null ? rank : service.rankName(viewer, found);
    }

    @Override
    public JobPerks perks(UUID player) {
        return service.perks(player);
    }

    @Override
    public List<Ranked> topQuests(int limit) {
        return service.repository().topCompleted(limit);
    }

    @Override
    public int questRank(UUID player) {
        return service.repository().completedRank(player);
    }
}
