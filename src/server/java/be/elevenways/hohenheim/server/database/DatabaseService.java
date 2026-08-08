package be.elevenways.hohenheim.server.database;

import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.server.runtime.WorkloadLiveness;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.docker.SiteDatabaseNetworks;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.util.DatasourceScoped;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.protoblast.common.i18n.Microcopy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lifecycle entry point for managed databases: ties the persisted {@link DatabaseModel}
 * record (desired config) to the RUNTIME, which since the Phase 7 database wave is an
 * owned instance driven by {@link DatabaseInstances} rather than a container this class
 * asks the daemon about directly. Backup and restore resolve the engine, credentials and
 * container handle from the record, so callers still pass only a name.
 *
 * @author  Jelle De Loecker
 * @since   0.1.0
 */
public class DatabaseService extends DatasourceScoped {

    // The status vocabulary is owned by the model's EnumField declaration.
    public static final String STATUS_PROVISIONING = DatabaseModel.STATUS_PROVISIONING;
    public static final String STATUS_ACTIVE = DatabaseModel.STATUS_ACTIVE;
    public static final String STATUS_FAILED = DatabaseModel.STATUS_FAILED;
    public static final String STATUS_DESTROY_FAILED = DatabaseModel.STATUS_DESTROY_FAILED;

    // Background pool for provisioning (image pull + container start can take tens of seconds);
    // shared because handlers construct DatabaseService per request. Bounded to limit load.
    private static final ExecutorService PROVISION_EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "db-provision");
        thread.setDaemon(true);
        return thread;
    });

    public DatabaseService() {
        super(null);
    }

    /**
     * Tests: an isolated datasource. There is deliberately NO injectable Docker client any
     * more -- the runtime is resolved by {@code DatabaseContainerKind.runtimeFor} through
     * the host inventory, and a second client-resolution path here would be a way for a
     * test to exercise a daemon the production lane would never talk to.
     */
    public DatabaseService(Datasource datasource) {
        super(datasource);
    }

    /** The engine-operations client for a database record's target host. */
    private ManagedDatabase managedFor(String serverName) {
        return new ManagedDatabase(new ServerService().clientFor(serverName));
    }

    /**
     * THE engine container handle of a record, or a named failure: every exec-driven
     * operation (backup, restore) needs the owned instance to exist.
     */
    private String handleOf(Row row) throws IOException {
        Integer recordId = row.get(DatabaseModel.ID);
        String handle = recordId == null ? null : query(() -> DatabaseInstances.handleOf(recordId));
        if (handle == null) {
            throw new IOException("Managed database '" + row.get(DatabaseModel.NAME)
                + "' owns no engine instance yet; provision it before running this operation");
        }
        return handle;
    }

    private static DatabaseModel model() {
        return Models.get(DatabaseModel.class);
    }

    /** Provision synchronously on the local host; see the server-aware overload. */
    public ManagedDatabase.Connection create(String name, ManagedDatabase.Engine engine, String image,
                                             String user, String password, String database,
                                             boolean ephemeral) throws IOException {
        return create(name, engine, image, user, password, database, ephemeral, ServerService.LOCAL);
    }

    /**
     * Provision a database synchronously on {@code serverName} and persist its record as active.
     * Blocking -- intended for tests and scripted use; request handlers should use {@link #createAsync}.
     */
    public ManagedDatabase.Connection create(String name, ManagedDatabase.Engine engine, String image,
                                             String user, String password, String database,
                                             boolean ephemeral, String serverName) throws IOException {
        return create(name, engine, image, user, password, database, ephemeral, serverName,
            ResourceLimits.none());
    }

    /**
     * Synchronous create with optional container resource caps. The record is persisted
     * as "provisioning" BEFORE the engine instance exists (matching the async path), so
     * the instance is born attributed to it; a failed provision leaves a "failed" record
     * rather than nothing.
     */
    public ManagedDatabase.Connection create(String name, ManagedDatabase.Engine engine, String image,
                                             String user, String password, String database,
                                             boolean ephemeral, String serverName,
                                             ResourceLimits limits) throws IOException {
        insertRecord(name, engine, image, user, password, database, ephemeral,
            serverName, limits, STATUS_PROVISIONING);
        try {
            ManagedDatabase.Connection connection = provisionRuntime(name, engine, user,
                password, database, limits);
            setStatus(name, STATUS_ACTIVE);
            return connection;
        } catch (IOException e) {
            setStatus(name, STATUS_FAILED);
            throw e;
        }
    }

    /**
     * Converge the record's owned engine instance and hand back its connection details.
     *
     * A deploy REPLACES the container, so the fresh one must rejoin the link networks of
     * every Docker site attached to this record -- and joining a running container
     * re-allocates its published port, so the port is re-observed AFTER the joins and
     * the returned connection carries the final number.
     */
    private ManagedDatabase.Connection provisionRuntime(String name, ManagedDatabase.Engine engine,
                                                        String user, String password,
                                                        String database, ResourceLimits limits)
            throws IOException {
        Row row = require(name);
        Integer recordId = row.get(DatabaseModel.ID);
        int port = scoped(() -> DatabaseInstances.deploy(row, limits));
        if (recordId != null && query(() -> SiteDatabaseNetworks.reattachForDatabase(recordId))) {
            ManagedDatabase.LiveStatus fresh = query(() -> DatabaseInstances.liveStatus(recordId));
            if (fresh.running() && fresh.port() != null) {
                port = fresh.port();
            }
        }
        return new ManagedDatabase.Connection(engine, "127.0.0.1", port, user, password, database);
    }

    /** {@link #query} for a body that fails the way the daemon work inside it fails. */
    private <T> T scoped(ThrowingSupplier<T> body) throws IOException {
        Object[] result = new Object[1];
        IOException[] failure = new IOException[1];
        exec(() -> {
            try {
                result[0] = body.get();
            } catch (IOException e) {
                failure[0] = e;
            }
        });
        if (failure[0] != null) {
            throw failure[0];
        }
        @SuppressWarnings("unchecked")
        T value = (T) result[0];
        return value;
    }

    /** A datasource-scoped body that may fail the way a daemon operation fails. */
    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws IOException;
    }

    /** Provision asynchronously on the local host; see the server-aware overload. */
    public Row createAsync(String name, ManagedDatabase.Engine engine, String image,
                           String user, String password, String database, boolean ephemeral) {
        return createAsync(name, engine, image, user, password, database, ephemeral,
            ServerService.LOCAL);
    }

    /**
     * Persist the record immediately as "provisioning" and provision on {@code serverName} in the
     * background, flipping the status to active or failed when done -- so a slow image pull (or a
     * remote SSH round-trip) doesn't block the request.
     */
    public Row createAsync(String name, ManagedDatabase.Engine engine, String image,
                           String user, String password, String database, boolean ephemeral,
                           String serverName) {
        return createAsync(name, engine, image, user, password, database, ephemeral, serverName,
            ResourceLimits.none());
    }

    /**
     * Async create with optional container resource caps.
     *
     * AIDEV-NOTE: this RETURNS the row it inserted. Callers used to re-query by name to
     * find out what they had just created, which is an inference and not an observation:
     * on the pre-fix upsert path that lookup happily handed back somebody ELSE's record
     * and reported it as "created". Return what you wrote.
     *
     * @throws Violations {@code database_name_taken}
     * @return the persisted record, already stored as {@code provisioning}
     */
    public Row createAsync(String name, ManagedDatabase.Engine engine, String image,
                           String user, String password, String database, boolean ephemeral,
                           String serverName, ResourceLimits limits) {
        Row created = insertRecord(name, engine, image, user, password, database, ephemeral,
            serverName, limits, STATUS_PROVISIONING);
        PROVISION_EXECUTOR.submit(() -> {
            try {
                provisionRuntime(name, engine, user, password, database, limits);
                setStatus(name, STATUS_ACTIVE);
            } catch (Exception e) {
                setStatus(name, STATUS_FAILED);
                Blast.log("DB: provisioning failed for", name, "-", e.getMessage());
            }
        });
        return created;
    }

    /**
     * Persist a BRAND NEW database record, refusing a name that is already taken.
     *
     * AIDEV-NOTE: this was a find-by-name-then-overwrite ("upsertRecord"), and that made
     * create a SEIZURE primitive. {@code M015_CreateManagedDatabases} declares
     * {@code unique("name")}, but the index was never consulted because the write was an
     * UPDATE: creating "app-db" when one existed silently rewrote the victim's engine,
     * image, user, password and host, and {@link DatabaseInstances#dataVolumeOf} keys the
     * data volume on the record's NAME, so the following provision remounted the VICTIM'S
     * DATA under attacker-chosen credentials while the UI reported a successful create.
     * Admin-only today, so it read as an operator typo silently redeploying a production
     * database; it becomes cross-tenant data seizure the day tenant database allocation
     * ships. Neither create caller ever wanted upsert semantics -- there were exactly two,
     * both create lanes -- so there is no second entry point to preserve. A retry after a
     * FAILED provision is now an explicit destroy-then-create, not an accidental converge.
     *
     * AIDEV-NOTE: the refusal is on the stored name verbatim, so future per-owner
     * namespacing needs no change here: whatever spelling becomes the record's name is the
     * one this refuses to collide with.
     *
     * @throws Violations {@code database_name_taken}
     * @return the row that was created
     */
    private Row insertRecord(String name, ManagedDatabase.Engine engine, String image, String user,
                             String password, String database, boolean ephemeral, String serverName,
                             ResourceLimits limits, String status) {
        return query(() -> {
            DatabaseModel model = model();
            if (model.findByName(name) != null) {
                throw Violations.ofField(DatabaseModel.NAME.getName(), name,
                    Microcopy.of("database_name_taken").withFilter("scope", "violations")
                        .withArg("name", name));
            }
            Row row = model.createEmptyRow();
            row.set(DatabaseModel.NAME, name);
            row.set(DatabaseModel.ENGINE, engine.token());
            row.set(DatabaseModel.IMAGE, image);
            row.set(DatabaseModel.DB_USER, user);
            row.set(DatabaseModel.DB_PASSWORD, password);
            row.set(DatabaseModel.DB_NAME, database);
            row.set(DatabaseModel.EPHEMERAL, ephemeral);
            row.set(DatabaseModel.MEMORY_LIMIT_MB, limits.memoryMb());
            row.set(DatabaseModel.CPU_LIMIT, limits.cpus());
            row.set(DatabaseModel.STATUS, status);
            row.set(DatabaseModel.SERVER_ID, ServerModel.canonicalServerId(serverName));
            model.save(row);
            return row;
        });
    }

    private void setStatus(String name, String status) {
        exec(() -> {
            DatabaseModel model = model();
            Row row = model.findByName(name);
            if (row != null) {
                row.set(DatabaseModel.STATUS, status);
                model.save(row);
            }
        });
    }

    /** All persisted database records. */
    public List<Row> list() {
        return query(() -> model().find().all());
    }

    /** A persisted database plus its live container status, for the admin list.
     *  {@code status} is the provisioning lifecycle (provisioning/active/failed/destroy_failed);
     *  {@code containerState} is the daemon's answer (running/stopped/absent/unreachable --
     *  absent and unreachable are DISTINCT identities, see ContainerState);
     *  {@code liveness} is whether the ENGINE inside a running container is still alive. */
    public record Summary(String name, String engine, String image, String database, String user,
                          boolean ephemeral, String server, String status, boolean running,
                          ContainerState containerState, Integer port,
                          WorkloadLiveness liveness) {

        /** The container runs but the kernel killed the engine inside it. */
        public boolean workloadDead() {
            return running && liveness == WorkloadLiveness.WORKLOAD_DEAD;
        }
    }

    /** Full detail for one database, including the password (admin detail page only). */
    public record Detail(String name, String engine, String image, String database, String user,
                         String password, boolean ephemeral, String server, String status,
                         boolean running, ContainerState containerState,
                         Integer port, WorkloadLiveness liveness) {

        /** The container runs but the kernel killed the engine inside it. */
        public boolean workloadDead() {
            return running && liveness == WorkloadLiveness.WORKLOAD_DEAD;
        }
    }

    /** Full detail for one database by name with live status, or null if there is no such record. */
    public Detail detail(String name) {
        Row row = query(() -> model().findByName(name));
        if (row == null) {
            return null;
        }
        return detailOf(row);
    }

    /** Full detail for an already-loaded database row, including live container status. */
    public Detail detailOf(Row row) {
        ManagedDatabase.Engine engine = engineOf(row);
        ManagedDatabase.LiveStatus live = liveStatus(row, engine);
        String image = row.get(DatabaseModel.IMAGE);
        return new Detail(
            row.get(DatabaseModel.NAME),
            engine.token(),
            image != null ? image : "",
            row.get(DatabaseModel.DB_NAME),
            row.get(DatabaseModel.DB_USER),
            row.get(DatabaseModel.DB_PASSWORD),
            Boolean.TRUE.equals(row.get(DatabaseModel.EPHEMERAL)),
            serverOf(row),
            statusOf(row),
            live.running(),
            live.state(),
            live.port(),
            live.liveness());
    }

    /** All databases with live status (running + published port), best-effort per record. */
    public List<Summary> summaries() {
        List<Summary> result = new ArrayList<>();
        for (Row row : query(() -> model().find().all())) {
            ManagedDatabase.Engine engine = engineOf(row);
            ManagedDatabase.LiveStatus live = liveStatus(row, engine);
            String image = row.get(DatabaseModel.IMAGE);
            result.add(new Summary(
                row.get(DatabaseModel.NAME),
                engine.token(),
                image != null ? image : "",
                row.get(DatabaseModel.DB_NAME),
                row.get(DatabaseModel.DB_USER),
                Boolean.TRUE.equals(row.get(DatabaseModel.EPHEMERAL)),
                serverOf(row),
                statusOf(row),
                live.running(),
                live.state(),
                live.port(),
                live.liveness()
            ));
        }
        return result;
    }

    // Live status of the record's OWNED engine instance; never throws. A host we cannot
    // even build a client for is UNREACHABLE, never "not running" -- absent and
    // unreachable stay distinct.
    private ManagedDatabase.LiveStatus liveStatus(Row row, ManagedDatabase.Engine engine) {
        Integer recordId = row.get(DatabaseModel.ID);
        if (recordId == null) {
            return new ManagedDatabase.LiveStatus(ContainerState.ABSENT, null);
        }
        try {
            return query(() -> DatabaseInstances.liveStatus(recordId));
        } catch (Exception e) {
            return new ManagedDatabase.LiveStatus(ContainerState.UNREACHABLE, null);
        }
    }

    private static String statusOf(Row row) {
        String status = row.get(DatabaseModel.STATUS);
        return status != null ? status : STATUS_ACTIVE;   // records predating the status column
    }

    /**
     * THE canonical host key is the FK; the transport layer still speaks names.
     *
     * AIDEV-NOTE: NOT static, and scoped through {@link #query}: the pre-FK version read a
     * name straight off the row and needed no datasource, while resolving the FK is a real
     * servers lookup -- outside this service's scope it hits the DEFAULT datasource and
     * throws "No server with id N" on every isolated-datasource caller (destroy, backup,
     * restore). Every serverOf call site is already outside a scope, so the scope goes here.
     */
    private String serverOf(Row row) {
        return query(() -> ServerModel.nameOf(row.get(DatabaseModel.SERVER_ID)));
    }

    /**
     * Back up a persisted database into {@code directory}, naming the file {@code baseName} plus
     * the engine's dump extension, and return the written path. Handles text (SQL) and binary
     * (RDB / mongodump archive) engines.
     */
    public Path backupToFile(String name, Path directory, String baseName) throws IOException {
        Row row = require(name);
        ManagedDatabase.Engine engine = engineOf(row);
        Files.createDirectories(directory);
        Path target = directory.resolve(baseName + "." + engine.dumpExtension());
        managedFor(serverOf(row)).backupToFile(handleOf(row), engine,
            row.get(DatabaseModel.DB_USER), row.get(DatabaseModel.DB_PASSWORD),
            row.get(DatabaseModel.DB_NAME), target);
        return target;
    }

    /** A dump ready to stream to the browser: filename, MIME type, and the dump bytes. */
    public record BackupDownload(String filename, String contentType, byte[] content) {}

    /**
     * Back up a persisted database by name into a downloadable artifact (SQL text or the engine's
     * native binary dump). The whole dump is held in memory; streaming is a follow-up for large
     * databases.
     */
    public BackupDownload backupDownload(String name) throws IOException {
        Row row = require(name);
        ManagedDatabase.Engine engine = engineOf(row);
        Path directory = Files.createTempDirectory("hohenheim-backup");
        Path dump = directory.resolve(name + "." + engine.dumpExtension());
        try {
            managedFor(serverOf(row)).backupToFile(handleOf(row), engine,
                row.get(DatabaseModel.DB_USER), row.get(DatabaseModel.DB_PASSWORD),
                row.get(DatabaseModel.DB_NAME), dump);
            return new BackupDownload(dump.getFileName().toString(), engine.dumpContentType(),
                Files.readAllBytes(dump));
        } finally {
            Files.deleteIfExists(dump);
            Files.deleteIfExists(directory);
        }
    }

    /** Restore a dump file (text or binary) into a persisted database by name. */
    public void restoreFromFile(String name, Path source) throws IOException {
        Row row = require(name);
        String user = row.get(DatabaseModel.DB_USER);
        String password = row.get(DatabaseModel.DB_PASSWORD);
        String database = row.get(DatabaseModel.DB_NAME);
        managedFor(serverOf(row)).restoreFromFile(handleOf(row), engineOf(row), user, password,
            database, source);
    }

    /**
     * Verified end of life: the owned engine instance is destroyed through
     * {@link InstanceService} (container removed or observed absent, ledger claims
     * released fully, instance row soft-deleted), the data volume follows when asked,
     * and only then is the record deleted. A destroy that cannot confirm its teardown
     * keeps the record (it holds the only copy of {@code db_password}), flips its status
     * to {@code destroy_failed} and throws; deleting the record then requires an
     * explicit retry or the recorded force-destroy action. Silent "success" while the
     * container keeps running was this codebase's worst instance of a step doing less
     * than it claims.
     *
     * AIDEV-NOTE: the port claim is the INSTANCE's since the lowering, and a refused
     * destroy leaves it exactly as InstanceService left it -- parked releasing when the
     * daemon was asked and could not confirm, still HELD when the host could not be
     * addressed at all. Both keep a rival from taking the port; neither is a deletion.
     *
     * @throws IOException when the teardown could not be confirmed
     */
    public void destroy(String name, boolean removeData) throws IOException {
        Row row = query(() -> model().findByName(name));
        if (row != null) {
            try {
                scoped(() -> {
                    DatabaseInstances.destroyFor(row, removeData);
                    return null;
                });
            } catch (IOException e) {
                setStatus(name, STATUS_DESTROY_FAILED);
                throw new IOException("Destroy of '" + name + "' could not verify its teardown"
                    + " (record kept): " + e.getMessage(), e);
            }
        }
        exec(() -> model().find().where(DatabaseModel.NAME.eq(name)).delete());
    }

    /**
     * The recorded escape hatch for a genuinely unreachable host: delete the record
     * WITHOUT verifying any teardown. The container and volume may survive on the host
     * (the reconciler will report them as orphans once it can see the host again).
     * Never the default path -- callers must have an explicit operator decision.
     *
     * AIDEV-NOTE: the OWNED ENGINE INSTANCE has to be abandoned here explicitly. The
     * database record is hard-deleted, so nothing would ever reach the instance row
     * again: it would stay live forever, holding its port claim, its capacity booking
     * and its instance-quota slot, attributed to a record that no longer exists. The
     * abandon is a SOFT delete through save() precisely so those releases (which ride
     * the deleted_at transition hooks) actually fire -- the ledger claim is PARKED
     * rather than deleted, because a container we could not confirm may still hold the
     * port.
     */
    public void forceDestroyRecord(String name) {
        exec(() -> {
            Row row = model().findByName(name);
            Integer recordId = row == null ? null : row.get(DatabaseModel.ID);
            if (recordId != null) {
                DatabaseInstances.abandonInstance(recordId);
            }
            model().find().where(DatabaseModel.NAME.eq(name)).delete();
        });
    }

    private Row require(String name) throws IOException {
        Row row = query(() -> model().findByName(name));
        if (row == null) {
            throw new IOException("No managed database named '" + name + "'");
        }
        return row;
    }

    private static ManagedDatabase.Engine engineOf(Row row) {
        return ManagedDatabase.engineOf(row);
    }
}
