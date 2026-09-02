package be.elevenways.hohenheim.server.database;

import be.elevenways.hohenheim.model.DatabaseEngineModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.server.cms.CmsSupport;
import be.elevenways.hohenheim.server.orm.PendingDeletes;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;

import java.util.List;

/**
 * The write-funnel half of shared engines: an engine still hosting a managed database
 * refuses to go on EVERY delete lane (admin form, API, criteria delete), and a database
 * row can never bind to an engine of another kind or another host.
 *
 * AIDEV-NOTE: {@link DatabaseEngines#destroy} refuses too, before it touches the daemon;
 * this hook is the gate the model funnel enforces for a delete that never went through
 * the service, exactly like InstanceDatabaseLinks guards the database side.
 */
public final class DatabaseEngineGuards {

    private static boolean installed;

    private DatabaseEngineGuards() {
    }

    /** Install the guards (MODULES stage); idempotent. */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        DatabaseEngineModel.SCHEMA.addBeforeRemoveHook(context -> {
            List<Row> hosted = Models.get(DatabaseModel.class).find()
                .where(PendingDeletes.dependents(DatabaseModel.DATABASE_ENGINE, context))
                .all();
            if (hosted.isEmpty()) {
                return;
            }
            throw Violations.ofForm(CmsSupport.violationText("database_engine_in_use")
                .withArg("name", "")
                .withArg("databases", DatabaseEngines.names(hosted)));
        });
        // Placement and its binding are ONE fact: a shared record names its engine, a
        // dedicated one names none, and a tmpfs database is its own container by
        // definition. AIDEV-NOTE: here and not on the model's static block on purpose --
        // HohenheimWriteHooks installs this AFTER TenantWrites, so a tenant's
        // tenant_field_frozen refusal is the one a frozen-column attack sees, never this
        // invariant's (hook order is registration order).
        DatabaseModel.SCHEMA.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row == null) {
                return;
            }
            boolean shared = DatabaseModel.isShared(row);
            Object bound = row.get(DatabaseModel.ENGINE_ID);
            if (shared && bound == null) {
                throw Violations.ofField(DatabaseModel.PLACEMENT.getName(),
                    DatabaseModel.PLACEMENT_SHARED,
                    CmsSupport.violationText("database_shared_without_engine"));
            }
            if (!shared && bound != null) {
                throw Violations.ofField(DatabaseModel.ENGINE_ID.getName(), bound,
                    CmsSupport.violationText("database_engine_on_dedicated"));
            }
            if (shared && Boolean.TRUE.equals(row.get(DatabaseModel.EPHEMERAL))) {
                throw Violations.ofField(DatabaseModel.EPHEMERAL.getName(), true,
                    CmsSupport.violationText("database_ephemeral_shared"));
            }
        });
        DatabaseModel.SCHEMA.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row == null || !DatabaseModel.isShared(row)) {
                return;
            }
            Integer engineId = row.get(DatabaseModel.ENGINE_ID);
            Row engine = engineId == null ? null
                : Models.get(DatabaseEngineModel.class).findById(engineId);
            if (engine == null) {
                throw Violations.ofField(DatabaseModel.ENGINE_ID.getName(), engineId,
                    CmsSupport.violationText("database_engine_kind_mismatch")
                        .withArg("engine", String.valueOf((Object) row.get(DatabaseModel.ENGINE))));
            }
            Object kind = row.get(DatabaseModel.ENGINE);
            if (kind != null && !kind.equals(engine.get(DatabaseEngineModel.ENGINE))) {
                throw Violations.ofField(DatabaseModel.ENGINE_ID.getName(), engineId,
                    CmsSupport.violationText("database_engine_kind_mismatch")
                        .withArg("engine", String.valueOf(kind)));
            }
            Object server = row.get(DatabaseModel.SERVER_ID);
            if (server != null && !server.equals(engine.get(DatabaseEngineModel.SERVER_ID))) {
                throw Violations.ofField(DatabaseModel.ENGINE_ID.getName(), engineId,
                    CmsSupport.violationText("database_engine_host_mismatch")
                        .withArg("name", String.valueOf((Object) engine.get(DatabaseEngineModel.NAME)))
                        .withArg("server", String.valueOf(engine.get(DatabaseEngineModel.SERVER_ID)))
                        .withArg("database_server", String.valueOf(server)));
            }
        });
    }
}
