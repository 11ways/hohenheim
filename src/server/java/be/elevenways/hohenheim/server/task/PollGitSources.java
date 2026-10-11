package be.elevenways.hohenheim.server.task;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.hohenheim.server.source.GitSourcePolls;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.security.ExecutionIdentity;
import be.elevenways.zenit.common.task.ScheduleDeclaration;
import be.elevenways.zenit.common.task.ScheduledTask;
import be.elevenways.zenit.common.task.TaskContext;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

/**
 * Asks every git source whose interval has passed for its branch's head, and deploys a new commit
 * ({@link GitSourcePolls}). Each source keeps its own interval; this only wakes often enough to honour the shortest.
 */
public class PollGitSources extends ScheduledTask {

    @Override
    public @NonNull Identifier id() {
        return HohenheimIds.id("poll_git_sources");
    }

    @Override
    public @NonNull Microcopy label() {
        return this.labelIn(HohenheimMicrocopy.HOHENHEIM.copy());
    }

    @Override
    public @NonNull PollGitSources newTask() {
        return new PollGitSources();
    }

    /** On the nodes that run instances, where their deploys run. */
    @Override
    public @NonNull List<ScheduleDeclaration> schedules() {
        return HohenheimRoles.schedulesWhen(
            List.of(ScheduleDeclaration.fallback("* * * * *")),
            HohenheimRoles.Role.INSTANCES);
    }

    @Override
    public @NonNull Microcopy description() {
        return this.descriptionIn(HohenheimMicrocopy.HOHENHEIM.copy());
    }

    /** The deploys a new commit starts are the system's own work, as the webhook's are. */
    @Override
    public void executor(TaskContext ctx) {
        ExecutionIdentity.runAsSystem("git-poll", GitSourcePolls::checkDue);
    }
}
