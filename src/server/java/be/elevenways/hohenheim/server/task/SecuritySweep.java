package be.elevenways.hohenheim.server.task;

import be.elevenways.hohenheim.server.security.BanService;
import be.elevenways.hohenheim.server.security.NeverBanHostnames;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.task.ScheduleDeclaration;
import be.elevenways.zenit.common.task.ScheduledTask;
import be.elevenways.zenit.common.task.TaskContext;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

/**
 * Deactivates expired ban rows (nftables already kernel-expired their
 * elements) and re-resolves never_ban hostname entries as an hourly safety net;
 * setting changes also refresh asynchronously. DNS never runs on a request/ban path.
 */
public class SecuritySweep extends ScheduledTask {

    public static final String STATIC_DESCRIPTION = "Expire bans";

    @Override
    public @NonNull SecuritySweep newTask() {
        return new SecuritySweep();
    }

    @Override
    public @NonNull List<ScheduleDeclaration> schedules() {
        return List.of(ScheduleDeclaration.bootAndCron("14 * * * *"));
    }

    @Override
    public @NonNull String description() {
        return STATIC_DESCRIPTION;
    }

    @Override
    public void executor(TaskContext ctx) {
        sweep();
    }

    /**
     * @throws RuntimeException on failure -- recorded by the task system as FAILED
     */
    public static void sweep() {
        // First: a failed expiry sweep must not delay hostname protection.
        NeverBanHostnames.INSTANCE.refresh();
        int expired = BanService.INSTANCE.deactivateExpired();
        if (expired > 0) {
            Blast.log("TASK: SecuritySweep deactivated", expired, "expired bans");
        }
    }
}
