package be.elevenways.hohenheim.server.database;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.model.DatabaseEngineModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.Secrets;
import be.elevenways.hohenheim.server.cms.DeleteImpact;
import be.elevenways.hohenheim.server.cms.ProvisionedRecords;
import be.elevenways.hohenheim.server.cms.WithheldFailure;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.activity.ZenitActivityAction;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;

import static be.elevenways.hohenheim.RawValues.trimmed;

/**
 * The operator's writes of managed databases and shared engines from submitted values: create, resize and the
 * verified (or forced) teardown, each refusing by name.
 *
 * AIDEV-NOTE: the name rule is {@link DatabaseModel#requireValidName}, the one rule the model hooks also enforce; a
 * create checks it before anything is provisioned, so a refused name never reaches a daemon (the admin forms
 * used to refuse uppercase, underscore and dot names the rule accepts).
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class DatabaseWrites {

    /** Where a dedicated record keeps the ceilings the shared resize lane reads and writes. */
    private static final ProvisionedRecords.Columns DATABASE_CEILINGS = new ProvisionedRecords.Columns(
        DatabaseModel.ID, DatabaseModel.MEMORY_LIMIT_MB, DatabaseModel.CPU_LIMIT,
        DatabaseModel.STATUS, DatabaseModel.FAILURE_REASON);

    /** Where an engine keeps the ceilings the shared resize lane reads and writes. */
    private static final ProvisionedRecords.Columns ENGINE_CEILINGS = new ProvisionedRecords.Columns(
        DatabaseEngineModel.ID, DatabaseEngineModel.MEMORY_LIMIT_MB, DatabaseEngineModel.CPU_LIMIT,
        DatabaseEngineModel.STATUS, DatabaseEngineModel.FAILURE_REASON);

    private DatabaseWrites() {
    }

    /**
     * The service persists the record itself (status provisioning) and provisions the container in the background;
     * it REFUSES a name that is already taken ({@code database_name_taken}) rather than converging onto it.
     *
     * AIDEV-NOTE: the returned id is the row the service inserted, never the answer to a re-query by name: that is
     * how a colliding create once reported success while it had overwritten someone else's database.
     *
     * @return the new record's id
     * @throws Violations a refused name, engine or database name, or the service's own refusals
     */
    public static @NonNull Object create(@NonNull Map<String, Object> values) {
        String name = trimmed(values.get(DatabaseModel.NAME.getName()));
        DatabaseModel.requireValidName(DatabaseModel.NAME.getName(), name);
        String engineToken = trimmed(values.get(DatabaseModel.ENGINE.getName())).toLowerCase(Locale.ROOT);
        ManagedDatabase.Engine engine = engineOf(DatabaseModel.ENGINE.getName(), engineToken);
        String database = trimmed(values.get(DatabaseModel.DB_NAME.getName()));
        if (database.isEmpty()) {
            throw Violations.ofField(DatabaseModel.DB_NAME.getName(), database,
                HohenheimMicrocopy.VIOLATIONS.of("database_name_required"));
        }
        String user = trimmed(values.get(DatabaseModel.DB_USER.getName()));
        if (user.isEmpty()) {
            user = "appuser";
        }
        String password = trimmed(values.get(DatabaseModel.DB_PASSWORD.getName()));
        if (password.isEmpty()) {
            password = Secrets.generatePassword();
        }
        String image = trimmed(values.get(DatabaseModel.IMAGE.getName()));
        boolean ephemeral = RawValues.isOn(values, DatabaseModel.EPHEMERAL);
        // The FK is canonical; the service API still speaks the (unique) server name.
        String server = ServerModel.nameOf(
            values.get(DatabaseModel.SERVER_ID.getName()) instanceof Integer serverId ? serverId : null);
        ResourceLimits limits = ResourceLimits.of(
            values.get(DatabaseModel.MEMORY_LIMIT_MB.getName()) instanceof Integer mb ? mb : null,
            values.get(DatabaseModel.CPU_LIMIT.getName()) instanceof Double cpus ? cpus : null);
        // A blank placement is the service's own default (shared where the engine can host logical databases and the
        // data is persistent), never a third placement here.
        String placement = trimmed(values.get(DatabaseModel.PLACEMENT.getName()));
        Integer engineId = values.get(DatabaseModel.ENGINE_ID.getName()) instanceof Integer id ? id : null;
        Row created = new DatabaseService().createAsync(name, engine, image.isEmpty() ? null : image, user, password,
            database, ephemeral, server, limits, placement.isEmpty() ? null : placement, engineId);
        return created.get(DatabaseModel.ID);
    }

    /**
     * THE resize: the two resource ceilings and nothing else, through the lane shared with the engines
     * ({@link ProvisionedRecords#resize}). The engine row's reservation runs INLINE, so a host without room refuses on
     * the form ({@code host_capacity_reached}). A ceiling the write does not carry keeps its stored value.
     *
     * @throws Violations {@code database_shared_limits} for a ceiling submitted on a shared record
     */
    public static void resize(@NonNull Row existing, @NonNull Map<String, Object> values) {
        if (DatabaseModel.isShared(existing)) {
            // The fields are HIDDEN on a shared record, so a submitted value did not come from the rendered form;
            // refuse it by name instead of booking a ceiling against a container this record does not own.
            if (ProvisionedRecords.carriesCeiling(values, DATABASE_CEILINGS)) {
                throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("database_shared_limits"));
            }
            return;
        }
        DatabaseService service = new DatabaseService();
        ProvisionedRecords.resize(Models.get(DatabaseModel.class), existing, values, DATABASE_CEILINGS,
            DatabaseInstances::reserveEngineRow, service::provisionInBackground);
    }

    /**
     * The verified teardown: a NAMED refusal, never a 500, when it is unconfirmed; the record is then kept (status
     * {@code destroy_failed}), the port claim parked, and the force delete is the recorded way out.
     *
     * @throws Violations {@code database_in_use} or a destroy failure
     */
    public static void destroy(@NonNull Row database) {
        String name = database.get(DatabaseModel.NAME);
        refuseWhileAttached(name, database.get(DatabaseModel.ID));
        try {
            new DatabaseService().destroy(name, true);
        } catch (IOException e) {
            String detail = WithheldFailure.operatorDetail(e);
            throw Violations.ofForm(detail == null
                ? HohenheimMicrocopy.VIOLATIONS.of("database_destroy_failed_tenant").withArg("name", name)
                : HohenheimMicrocopy.VIOLATIONS.of("database_destroy_failed")
                    .withArg("name", name)
                    .withArg("reason", detail));
        }
        // Links to soft-deleted owners are debris once the database is gone: the row delete inside destroy takes them
        // along through the model funnel (InstanceDatabaseLinks).
    }

    /**
     * The recorded escape hatch once a normal destroy failed: the record goes, the host may keep orphans.
     *
     * @throws Violations {@code database_in_use}
     */
    public static void forceDestroy(@NonNull Row database) {
        String name = database.get(DatabaseModel.NAME);
        // The same in-use refusal the delete makes, asked BEFORE the engine instance is abandoned: the funnel would
        // refuse the row delete anyway, but by then the abandon has already run.
        refuseWhileAttached(name, database.get(DatabaseModel.ID));
        ActivityLog.withAction(ZenitActivityAction.DELETE, "force-destroy",
            () -> new DatabaseService().forceDestroyRecord(name));
    }

    /**
     * Persist the engine as {@code provisioning}, reserve its instance row INLINE (so a host without room refuses on
     * the form) and bring the container up after commit.
     *
     * AIDEV-NOTE: there is deliberately no refusal of a second engine of the same kind on one host: running two major
     * versions side by side is why an engine can be created by hand at all. The allocation funnel resolves the FIRST.
     *
     * @return the new engine's id
     * @throws Violations a refused name or engine, or the host budget's refusal
     */
    public static @NonNull Object createEngine(@NonNull Map<String, Object> values) {
        String name = trimmed(values.get(DatabaseEngineModel.NAME.getName()));
        DatabaseModel.requireValidName(DatabaseEngineModel.NAME.getName(), name);
        String engineToken = trimmed(values.get(DatabaseEngineModel.ENGINE.getName())).toLowerCase(Locale.ROOT);
        ManagedDatabase.Engine engine = engineOf(DatabaseEngineModel.ENGINE.getName(), engineToken);
        if (!engine.supportsLogicalDatabases()) {
            // The placement's refusal, on its key: an engine with no per-database namespace can only ever serve one
            // database, which is a DEDICATED record and not this tier.
            throw Violations.ofField(DatabaseEngineModel.ENGINE.getName(), engineToken,
                HohenheimMicrocopy.VIOLATIONS.of("database_placement_unsupported").withArg("engine", engineToken));
        }
        String rootUser = trimmed(values.get(DatabaseEngineModel.ROOT_USER.getName()));
        if (rootUser.isEmpty()) {
            rootUser = "root";
        }
        String rootPassword = trimmed(values.get(DatabaseEngineModel.ROOT_PASSWORD.getName()));
        if (rootPassword.isEmpty()) {
            rootPassword = Secrets.generatePassword();
        }
        String image = trimmed(values.get(DatabaseEngineModel.IMAGE.getName()));
        ResourceLimits limits = ResourceLimits.of(
            values.get(DatabaseEngineModel.MEMORY_LIMIT_MB.getName()) instanceof Integer mb ? mb : null,
            values.get(DatabaseEngineModel.CPU_LIMIT.getName()) instanceof Double cpus ? cpus : null);

        Model model = Models.get(DatabaseEngineModel.class);
        Row row = model.createEmptyRow();
        row.set(DatabaseEngineModel.NAME, name);
        row.set(DatabaseEngineModel.ENGINE, engine.token());
        row.set(DatabaseEngineModel.IMAGE, image.isEmpty() ? null : image);
        row.set(DatabaseEngineModel.SERVER_ID,
            values.get(DatabaseEngineModel.SERVER_ID.getName()) instanceof Integer serverId
                ? serverId : ServerModel.localServerId());
        row.set(DatabaseEngineModel.ROOT_USER, rootUser);
        row.set(DatabaseEngineModel.ROOT_PASSWORD, rootPassword);
        row.set(DatabaseEngineModel.MEMORY_LIMIT_MB, limits.memoryMb());
        row.set(DatabaseEngineModel.CPU_LIMIT, limits.cpus());
        row.set(DatabaseEngineModel.STATUS, DatabaseModel.STATUS_PROVISIONING);
        model.save(row);

        // Books the engine against the host budget through the instance write hook, and refuses here (never on a pool
        // thread minutes later) when it does not fit.
        DatabaseEngines.reserveRow(row, limits);

        Integer engineId = row.get(DatabaseEngineModel.ID);
        if (engineId != null) {
            model.getResolvedDatasource().afterCommit(() -> DatabaseEngines.provisionInBackground(engineId));
        }
        return engineId;
    }

    /** An engine's resize: the two ceilings through the lane shared with the dedicated databases. */
    public static void resizeEngine(@NonNull Row existing, @NonNull Map<String, Object> values) {
        ProvisionedRecords.resize(Models.get(DatabaseEngineModel.class), existing, values, ENGINE_CEILINGS,
            DatabaseEngines::reserveRow, DatabaseEngines::redeployInBackground);
    }

    /**
     * Verified teardown: the container and its data volume go, and the row with them.
     *
     * @throws Violations {@code database_engine_destroy_failed} when the teardown is unconfirmed; the record is kept
     *                    (status destroy_failed) and the force delete is the recorded way out
     */
    public static void destroyEngine(@NonNull Row engine) {
        Integer engineId = engine.get(DatabaseEngineModel.ID);
        if (engineId == null) {
            return;
        }
        try {
            DatabaseEngines.destroy(engineId, true);
        } catch (IOException e) {
            throw engineDestroyFailed(engine, e);
        }
    }

    /**
     * The engine's recorded escape hatch once a normal destroy failed.
     *
     * @throws Violations {@code database_engine_destroy_failed} when even the forced teardown throws
     */
    public static void forceDestroyEngine(@NonNull Row engine) {
        Integer engineId = engine.get(DatabaseEngineModel.ID);
        ActivityLog.withAction(ZenitActivityAction.DELETE, "force-destroy", () -> {
            try {
                if (engineId != null) {
                    DatabaseEngines.forceDestroy(engineId);
                }
            } catch (IOException e) {
                throw engineDestroyFailed(engine, e);
            }
        });
    }

    /** @throws Violations {@code database_in_use} naming the workloads */
    private static void refuseWhileAttached(@Nullable String name, @Nullable Integer id) {
        String workloads = DeleteImpact.workloadsHolding(id);
        if (!workloads.isEmpty()) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("database_in_use")
                .withArg("name", name)
                .withArg("workloads", workloads));
        }
    }

    /** @throws Violations {@code unknown_engine} on the field for a token no engine declares */
    private static ManagedDatabase.@NonNull Engine engineOf(@NonNull String field, @NonNull String token) {
        ManagedDatabase.Engine engine = ManagedDatabase.Engine.forToken(token);
        if (engine == null) {
            throw Violations.ofField(field, token,
                HohenheimMicrocopy.VIOLATIONS.of("unknown_engine").withArg("engine", token));
        }
        return engine;
    }

    private static @NonNull Violations engineDestroyFailed(@NonNull Row engine, @NonNull IOException failure) {
        return Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("database_engine_destroy_failed")
            .withArg("name", String.valueOf((Object) engine.get(DatabaseEngineModel.NAME)))
            .withArg("reason", String.valueOf(failure.getMessage())));
    }
}
