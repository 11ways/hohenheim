package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.AttentionSeverity;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.host.HostState;
import be.elevenways.hohenheim.model.DatabaseEngineModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceStatus;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.database.DatabaseInstances;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

import static be.elevenways.hohenheim.server.cms.AttentionItems.copy;
import static be.elevenways.hohenheim.server.cms.AttentionItems.item;

/**
 * The DATABASES role's attention items: failed records and engines, and attached databases that
 * cannot serve the workload they are injected into.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class DatabaseAttention {

    private static final String ADMIN = HohenheimSlugs.ADMIN;

    private DatabaseAttention() {
    }

    /** Failed database records, and active ones a failed operation rolled back. */
    static void failedDatabases(List<AttentionItem> items) {
        List<Row> rows = Models.get(DatabaseModel.class).find()
            .where(DatabaseModel.STATUS.eq(DatabaseModel.STATUS_FAILED))
            .all();
        for (Row row : rows) {
            String reason = row.get(DatabaseModel.FAILURE_REASON);
            items.add(item(AttentionSeverity.ERROR, "database",
                copy("database", "attention_title", "name", row.get(DatabaseModel.NAME)),
                reason == null || reason.isBlank()
                    ? copy("provisioning_failed", "attention_detail")
                    : copy("provisioning_failed_reason", "attention_detail", "reason", reason),
                CmsRoutes.detail(ADMIN, "databases", row.get(DatabaseModel.ID))));
        }
        // An ACTIVE record carrying a reason is the one shape a status alone cannot show:
        // a failed move rolled the record back onto its untouched dedicated engine and
        // stamped WHY there, so without this the operator learns nothing happened only by
        // opening the record.
        for (Row row : Models.get(DatabaseModel.class).find()
                .where(DatabaseModel.STATUS.eq(DatabaseModel.STATUS_ACTIVE))
                .where(DatabaseModel.FAILURE_REASON.isNotNull())
                .all()) {
            String reason = row.get(DatabaseModel.FAILURE_REASON);
            if (reason == null || reason.isBlank()) {
                continue;
            }
            items.add(item(AttentionSeverity.WARNING, "database",
                copy("database", "attention_title", "name", row.get(DatabaseModel.NAME)),
                copy("database_operation_failed", "attention_detail", "reason", reason),
                CmsRoutes.detail(ADMIN, "databases", row.get(DatabaseModel.ID))));
        }
        failedDatabaseEngines(items);
    }

    /** A shared engine that could not be brought up serves every database on it nothing. */
    private static void failedDatabaseEngines(List<AttentionItem> items) {
        for (Row row : Models.get(DatabaseEngineModel.class).find()
                .where(DatabaseEngineModel.STATUS.eq(DatabaseModel.STATUS_FAILED))
                .all()) {
            String reason = row.get(DatabaseEngineModel.FAILURE_REASON);
            items.add(item(AttentionSeverity.ERROR, "server",
                copy("database_engine", "attention_title",
                    "name", row.get(DatabaseEngineModel.NAME)),
                reason == null || reason.isBlank()
                    ? copy("provisioning_failed", "attention_detail")
                    : copy("engine_provisioning_failed_reason", "attention_detail",
                        "reason", reason),
                CmsRoutes.detail(ADMIN, DatabaseEngineResource.SLUG,
                    row.get(DatabaseEngineModel.ID))));
        }
    }

    /**
     * Instances whose ATTACHED database cannot serve its injected credentials right now,
     * judged from STORED state only: the database record's status, the stored status of the
     * engine instance serving it, and the stored contact state of that instance's host.
     * Failed-record databases already surface above; this frames the WORKLOAD impact.
     *
     * AIDEV-NOTE: this used to ask the daemon, once per attachment per dashboard render
     * ({@code DatabaseService.detailOf} ends in {@code InstanceService.liveStatus}, an SSH or
     * HTTPS round trip for a remote host) -- the per-render probe the attention surface
     * forbids. {@code InstanceStatusReconciler} stores what the daemon answered on its own
     * cadence, so the engine instance's status column is the observation to read. The
     * OOM-killed engine inside a still-running container ({@code WorkloadLiveness.WORKLOAD_DEAD})
     * is stored by that same sweep as {@code instances.workload_killed_at}, so it keeps its own
     * sentence here without a daemon call.
     */
    public static void unavailableAttachedDatabases(List<AttentionItem> items) {
        var linkModel = Models.get(InstanceDatabaseModel.class);
        if (linkModel == null) {
            return;
        }
        List<Row> links = linkModel.find().all();
        if (links.isEmpty()) {
            return;
        }
        var instanceModel = Models.get(InstanceModel.class);
        var databaseModel = Models.get(DatabaseModel.class);
        for (Row link : links) {
            Row instance = instanceModel.find()
                .where(InstanceModel.ID.eq(link.get(InstanceDatabaseModel.INSTANCE_ID)))
                .first();
            if (instance == null) {
                continue;
            }
            Row database = databaseModel.find()
                .where(DatabaseModel.ID.eq(link.get(InstanceDatabaseModel.DATABASE_ID)))
                .first();
            if (database == null) {
                continue;   // dangling link; the tab shows it as (deleted)
            }
            Microcopy detail = unavailableDetail(database);
            if (detail != null) {
                items.add(item(AttentionSeverity.WARNING, "database",
                    copy("instance", "attention_title",
                        "name", instance.get(InstanceModel.NAME)),
                    detail,
                    InstanceResource.recordRoute(ADMIN, instance, InstanceDatabasesPage.SLUG)));
            }
        }
    }

    /**
     * Why an attached database cannot serve right now, from stored state.
     *
     * @return the detail sentence, or null when the stored state says it serves
     */
    private static @Nullable Microcopy unavailableDetail(@NonNull Row database) {
        Object name = database.get(DatabaseModel.NAME);
        String status = database.get(DatabaseModel.STATUS);
        if (!DatabaseModel.STATUS_ACTIVE.equals(status)) {
            return copy("database_status", "attention_detail", "name", name, "status", status);
        }
        Integer databaseId = database.get(DatabaseModel.ID);
        Row engine;
        try {
            engine = databaseId != null ? DatabaseInstances.owned(databaseId) : null;
        } catch (RuntimeException unresolvable) {
            engine = null;   // a shared record whose engine row is gone serves nothing
        }
        if (engine == null) {
            return copy("database_not_running", "attention_detail", "name", name);
        }
        // "Gone/stopped", "the host could not be asked" and "the last operation failed" are
        // different operator problems; conflating the first two was the C6 status defect.
        if (hostUnanswering(engine)) {
            return copy("database_unreachable", "attention_detail", "name", name);
        }
        InstanceStatus stored = InstanceStatus.forToken(engine.get(InstanceModel.STATUS));
        if (stored == null) {
            // An unknown stored token claims nothing about a serving engine: fail closed.
            return copy("database_not_running", "attention_detail", "name", name);
        }
        return switch (stored) {
            // The container runs, but the sweep saw the kernel kill the engine inside it:
            // "runs" is exactly what the status alone would wrongly vouch for.
            case STARTING, RUNNING, CAPTURING, RESTORING, MIGRATING ->
                engine.get(InstanceModel.WORKLOAD_KILLED_AT) != null
                    ? copy("database_workload_dead", "attention_detail", "name", name)
                    : null;
            case ERROR -> copy("database_failed", "attention_detail", "name", name);
            case CREATED, STOPPED -> copy("database_not_running", "attention_detail", "name", name);
        };
    }

    /**
     * Whether the engine's host last FAILED its probe, so its stored instance status is
     * unverified rather than evidence. Only a recorded probe failure counts: a host that is
     * merely silent or never probed has made no claim either way.
     */
    private static boolean hostUnanswering(@NonNull Row engine) {
        Row server;
        try {
            server = Models.get(ServerModel.class).findById(
                ServerModel.canonicalServerId(engine.get(InstanceModel.SERVER_ID)));
        } catch (RuntimeException unresolvable) {
            return true;
        }
        if (server == null) {
            return true;
        }
        HostState state = ServerResource.statusCellOf(server).state();
        return switch (state) {
            case ERROR -> true;
            case QUARANTINED, SILENT, NEVER_PROBED, OK -> false;
        };
    }
}
