package be.elevenways.hohenheim.server.task;

import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.hohenheim.server.docker.DockerReconciler;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.task.ScheduleDeclaration;
import be.elevenways.zenit.common.task.ScheduledTask;
import be.elevenways.zenit.common.task.TaskContext;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;
import java.util.Map;

/**
 * Attributes every Docker container, volume and network on every inventoried server
 * to its owning record and persists the findings (report-only; the dashboard reads
 * the stored result -- per-render daemon probing is refused by design).
 */
public class ReconcileDockerResources extends ScheduledTask {

    public static final String STATIC_DESCRIPTION =
        "Reconcile Docker resources against record ownership";

    @Override
    public @NonNull ReconcileDockerResources newTask() {
        return new ReconcileDockerResources();
    }

    @Override
    public @NonNull List<ScheduleDeclaration> schedules() {
        // Hourly (not boot-and-cron: a boot sweep would hit every inventoried
        // daemon on every start, tests included). The sweep is a handful of list
        // calls per daemon. Gated on every role that runs containers it attributes.
        return HohenheimRoles.schedulesWhen(
            List.of(ScheduleDeclaration.fallback("41 * * * *")),
            HohenheimRoles.Role.STACKS, HohenheimRoles.Role.DATABASES, HohenheimRoles.Role.PROXY,
            HohenheimRoles.Role.INSTANCES);
    }

    @Override
    public @NonNull String description() {
        return STATIC_DESCRIPTION;
    }

    @Override
    public void executor(TaskContext ctx) {
        Map<String, List<DockerReconciler.Finding>> results =
            DockerReconciler.sweepAll(new ServerService());
        for (Map.Entry<String, List<DockerReconciler.Finding>> entry : results.entrySet()) {
            long orphaned = entry.getValue().stream()
                .filter(f -> f.bucket() == DockerReconciler.Bucket.ORPHANED).count();
            long colliding = entry.getValue().stream()
                .filter(f -> f.bucket() == DockerReconciler.Bucket.FOREIGN_COLLIDING).count();
            if (orphaned > 0 || colliding > 0) {
                Blast.log("DOCKER RECONCILE:", entry.getKey(), "-", entry.getValue().size(),
                    "resources,", orphaned, "orphaned,", colliding, "colliding");
            }
        }
    }
}
