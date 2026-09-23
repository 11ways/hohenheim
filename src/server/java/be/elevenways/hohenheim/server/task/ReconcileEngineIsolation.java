package be.elevenways.hohenheim.server.task;

import be.elevenways.hohenheim.model.DatabaseEngineModel;
import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.hohenheim.server.database.DatabaseEngines;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.task.ScheduleDeclaration;
import be.elevenways.zenit.common.task.ScheduledTask;
import be.elevenways.zenit.common.task.TaskContext;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.util.List;

/**
 * Re-asserts tenant isolation on every shared database engine, at boot and hourly.
 *
 * AIDEV-NOTE: the repair lane for engines that already existed when the isolation fixes of
 * 2026-09-23 landed -- a migration cannot reach inside an engine container, so databases
 * created before then keep PUBLIC's CONNECT (Postgres) or a wildcard grant (MySQL) until
 * something runs {@link DatabaseEngines#reconcileIsolation} against them. Boot is the first
 * run after an upgrade. Findings publish through {@link IsolationFindings}, the same
 * operator lane (alert on transition, failed run on the dashboard) the workload sweeps use:
 * a boundary between tenants that cannot be confirmed is the same class of finding.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public class ReconcileEngineIsolation extends ScheduledTask {

    public static final String STATIC_DESCRIPTION = "Re-assert tenant isolation on shared database engines";

    /** How this sweep names itself to an operator, in alerts and in the failure it throws. */
    public static final String SWEEP = "Database engine isolation";

    @Override
    public @NonNull ReconcileEngineIsolation newTask() {
        return new ReconcileEngineIsolation();
    }

    @Override
    public @NonNull List<ScheduleDeclaration> schedules() {
        return HohenheimRoles.schedulesWhen(
            List.of(ScheduleDeclaration.bootAndCron("23 * * * *")),
            HohenheimRoles.Role.DATABASES);
    }

    @Override
    public @NonNull String description() {
        return STATIC_DESCRIPTION;
    }

    @Override
    public void executor(TaskContext ctx) {
        sweep().publish();
    }

    /**
     * Repair every engine and collect what did not take; an engine that is not serving
     * holds no connectable database and is skipped, not reported.
     *
     * @return the findings, publishable by the caller
     */
    public static @NonNull IsolationFindings sweep() {
        IsolationFindings findings = new IsolationFindings(SWEEP);
        for (Row engine : Models.get(DatabaseEngineModel.class).find().all()) {
            int engineId = engine.get(DatabaseEngineModel.ID);
            String name = String.valueOf((Object) engine.get(DatabaseEngineModel.NAME));
            if (DatabaseEngines.databasesOn(engineId).isEmpty()) {
                continue;
            }
            try {
                List<String> failures = DatabaseEngines.reconcileIsolation(engineId);
                if (!failures.isEmpty()) {
                    findings.unconfirmed(name, failures);
                }
            } catch (IOException notServing) {
                // A stopped engine serves nobody; its isolation is re-asserted by the
                // (re)deploy that brings it back, before any tenant can connect again.
                continue;
            } catch (RuntimeException failed) {
                findings.unconfirmed(name, List.of(String.valueOf(failed.getMessage())));
            }
        }
        return findings;
    }
}
