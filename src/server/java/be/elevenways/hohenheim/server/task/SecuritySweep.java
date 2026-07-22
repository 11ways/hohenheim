package be.elevenways.hohenheim.server.task;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.SecurityEventModel;
import be.elevenways.hohenheim.server.security.BanService;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.task.ScheduleDeclaration;
import be.elevenways.zenit.common.task.ScheduledTask;
import be.elevenways.zenit.common.task.TaskContext;

import java.util.List;

/**
 * Deactivates expired ban rows (nftables already kernel-expired their elements)
 * and prunes aggregated security events past the retention window. Runs hourly.
 */
public class SecuritySweep extends ScheduledTask {

    public static final String STATIC_DESCRIPTION = "Expire bans and prune security events";

    public static List<ScheduleDeclaration> defaultSchedules() {
        return List.of(ScheduleDeclaration.fallback("14 * * * *"));
    }

    @Override
    public void executor(TaskContext ctx) {
        sweep();
    }

    /**
     * @throws RuntimeException on failure -- recorded by the task system as FAILED
     */
    public static void sweep() {
        int expired = BanService.INSTANCE.deactivateExpired();
        if (expired > 0) {
            Blast.log("TASK: SecuritySweep deactivated", expired, "expired bans");
        }

        int retentionDays = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Security.EVENT_RETENTION_DAYS);
        RetentionSweep.clean("SecuritySweep", Models.get(SecurityEventModel.class),
            SecurityEventModel.CREATED_AT, retentionDays);
    }
}
