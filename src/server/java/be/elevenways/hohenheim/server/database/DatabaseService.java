package be.elevenways.hohenheim.server.database;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.DatabaseEngineModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.auth.TenantWrites;
import be.elevenways.hohenheim.server.cms.CmsSupport;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.InstanceDatabaseNetworks;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.instance.InstanceCapacity;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.server.runtime.WorkloadLiveness;
import be.elevenways.hohenheim.server.util.DatasourceScoped;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lifecycle entry point for managed databases: ties the persisted {@link DatabaseModel}
 * record (desired config) to the RUNTIME. Since the Phase 7 database wave that runtime is
 * an owned instance driven by {@link DatabaseInstances}; since 2026-09-02 it is EITHER
 * the record's own engine instance (a dedicated placement) OR a logical database on a
 * host-shared {@link DatabaseEngineModel} (a shared placement), and every operation
 * resolves which through {@link EngineHost#serving(Row)}. Backup and restore resolve the
 * engine, the ROOT credentials and the container handle from that host, so callers
 * still pass only a name.
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

    private static final DateTimeFormatter STAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    // Background pool for provisioning (image pull + container start can take tens of seconds);
    // shared because handlers construct DatabaseService per request. Bounded to limit load.
    private static final ExecutorService PROVISION_EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "db-provision");
        thread.setDaemon(true);
        return thread;
    });

    /** The provisioning pool, for the engine tier's own background work. */
    static void submit(@NonNull Runnable work) {
        PROVISION_EXECUTOR.submit(work);
    }

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

    /** The engine-operations client for a host's server. */
    private ManagedDatabase managedFor(int serverId) {
        return new ManagedDatabase(dockerFor(serverId));
    }

    private DockerClient dockerFor(int serverId) {
        return new ServerService().clientFor(query(() -> ServerModel.nameOf(serverId)));
    }

    /**
     * THE container handle serving a record, or a named failure: every exec-driven
     * operation (backup, restore, logical-database work) needs the serving instance.
     */
    private String handleOf(Row row, EngineHost host) throws IOException {
        String handle = query(() -> DatabaseInstances.handleOf(host));
        if (handle == null) {
            throw new IOException("Managed database '" + row.get(DatabaseModel.NAME)
                + "' is served by no engine instance yet; provision it before running this"
                + " operation");
        }
        return handle;
    }

    private static DatabaseModel model() {
        return Models.get(DatabaseModel.class);
    }

    /**
     * THE default placement of a new record: shared where the engine can host logical
     * databases and the data is persistent, dedicated otherwise.
     */
    public static @NonNull String defaultPlacement(ManagedDatabase.@NonNull Engine engine,
                                                   boolean ephemeral) {
        return engine.supportsLogicalDatabases() && !ephemeral
            ? DatabaseModel.PLACEMENT_SHARED : DatabaseModel.PLACEMENT_DEDICATED;
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

    /** Synchronous create with optional container resource caps and the default placement. */
    public ManagedDatabase.Connection create(String name, ManagedDatabase.Engine engine, String image,
                                             String user, String password, String database,
                                             boolean ephemeral, String serverName,
                                             ResourceLimits limits) throws IOException {
        return create(name, engine, image, user, password, database, ephemeral, serverName,
            limits, null);
    }

    /**
     * Synchronous create with an explicit placement. The record is persisted as
     * "provisioning" BEFORE any runtime exists (matching the async path), so an owned
     * instance is born attributed to it; a failed provision leaves a "failed" record
     * rather than nothing.
     *
     * @param placement a {@link DatabaseModel#PLACEMENT} token, or null for the default
     */
    public ManagedDatabase.Connection create(String name, ManagedDatabase.Engine engine, String image,
                                             String user, String password, String database,
                                             boolean ephemeral, String serverName,
                                             ResourceLimits limits, @Nullable String placement)
            throws IOException {
        Row created = insertRecord(name, engine, image, user, password, database, ephemeral,
            serverName, limits, STATUS_PROVISIONING, placement, null);
        int recordId = created.get(DatabaseModel.ID);
        try {
            ManagedDatabase.Connection connection = provisionRuntime(created);
            setStatus(recordId, STATUS_ACTIVE, null);
            return connection;
        } catch (IOException e) {
            setStatus(recordId, STATUS_FAILED, e.getMessage());
            throw e;
        }
    }

    /**
     * Converge the record's runtime and hand back its connection details.
     *
     * A dedicated record deploys its own engine instance. A shared record brings its
     * engine up if needed (serialized per engine) and then creates -- idempotently -- its
     * logical database and user inside it. Either way a deploy REPLACES the serving
     * container, so every attached workload rejoins the link networks, and joining a
     * running container re-allocates its published port, so the port is re-observed
     * AFTER the joins and the returned connection carries the final number.
     */
    private ManagedDatabase.Connection provisionRuntime(Row row) throws IOException {
        Integer recordId = row.get(DatabaseModel.ID);
        ManagedDatabase.Engine engine = engineOf(row);
        String user = row.get(DatabaseModel.DB_USER);
        String password = row.get(DatabaseModel.DB_PASSWORD);
        String database = row.get(DatabaseModel.DB_NAME);
        int port;
        if (DatabaseModel.isShared(row)) {
            port = scoped(() -> provisionLogical(row));
        } else {
            ResourceLimits limits = ResourceLimits.of(row.get(DatabaseModel.MEMORY_LIMIT_MB),
                row.get(DatabaseModel.CPU_LIMIT));
            port = scoped(() -> DatabaseInstances.deploy(row, limits));
        }
        // Every attached consumer rejoins -- an application's serving release included,
        // which InstanceDatabaseNetworks resolves off the owning record. A rejoin can move
        // the published port, so the re-observation below runs when one touched something.
        boolean rejoined = recordId != null
            && query(() -> InstanceDatabaseNetworks.reattachForDatabase(recordId));
        if (rejoined) {
            ManagedDatabase.LiveStatus fresh = query(() -> DatabaseInstances.liveStatus(recordId));
            if (fresh.running() && fresh.port() != null) {
                port = fresh.port();
            }
        }
        return new ManagedDatabase.Connection(engine, "127.0.0.1", port, user, password, database);
    }

    /**
     * The shared half of {@link #provisionRuntime}: engine up, logical database and user
     * created (or re-credentialed) with the record's own values.
     *
     * @return the engine's published loopback port
     */
    private int provisionLogical(Row row) throws IOException {
        EngineHost host = EngineHost.serving(row);
        DatabaseEngines.ensureRunning(host.ownerId());
        String handle = DatabaseInstances.handleOf(host);
        if (handle == null) {
            throw new IOException("Shared engine '" + host.name() + "' is up but owns no instance");
        }
        runLogical(host, handle, host.engine().createLogicalCommand(host.rootUser(),
            row.get(DatabaseModel.DB_NAME), row.get(DatabaseModel.DB_USER)),
            host.engine().logicalEnv(host.rootPassword(), row.get(DatabaseModel.DB_PASSWORD)),
            "create logical database '" + row.get(DatabaseModel.DB_NAME) + "'");
        ManagedDatabase.LiveStatus live = DatabaseInstances.liveStatus(host);
        if (!live.running() || live.port() == null) {
            throw new IOException("Shared engine '" + host.name() + "' publishes no port");
        }
        return live.port();
    }

    /** Run one logical-database command on an engine host and refuse a non-zero exit by name. */
    private void runLogical(EngineHost host, String handle, List<String> command,
                            List<String> env, String what) throws IOException {
        DockerClient.ExecResult result = dockerFor(host.serverId()).exec(handle, command, env);
        if (result.exitCode() != 0) {
            throw new IOException("Could not " + what + " on engine '" + host.name() + "' (exit "
                + result.exitCode() + "): " + (result.stderr() + " " + result.stdout()).trim());
        }
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

    /** Async create with optional container resource caps and the default placement. */
    public Row createAsync(String name, ManagedDatabase.Engine engine, String image,
                           String user, String password, String database, boolean ephemeral,
                           String serverName, ResourceLimits limits) {
        return createAsync(name, engine, image, user, password, database, ephemeral, serverName,
            limits, null, null);
    }

    /**
     * Async create with an explicit placement and (for a shared one) an explicit engine.
     *
     * AIDEV-NOTE: this RETURNS the row it inserted. Callers used to re-query by name to
     * find out what they had just created, which is an inference and not an observation:
     * on the pre-fix upsert path that lookup happily handed back somebody ELSE's record
     * and reported it as "created". Return what you wrote.
     *
     * @param placement a {@link DatabaseModel#PLACEMENT} token, or null for the default
     * @param engineId  the shared engine to live on, or null for the host's engine of
     *                  that kind (created on demand)
     * @throws Violations {@code database_name_taken} and the placement refusals
     * @return the persisted record, already stored as {@code provisioning}
     */
    public Row createAsync(String name, ManagedDatabase.Engine engine, String image,
                           String user, String password, String database, boolean ephemeral,
                           String serverName, ResourceLimits limits, @Nullable String placement,
                           @Nullable Integer engineId) {
        Row created = insertRecord(name, engine, image, user, password, database, ephemeral,
            serverName, limits, STATUS_PROVISIONING, placement, engineId);
        int recordId = created.get(DatabaseModel.ID);
        // AIDEV-NOTE: scheduled AFTER COMMIT, by id. The CMS create submit runs
        // persistRow inside the resource's mutation transaction (every create does,
        // unrestricted admins included), so a pool job submitted from here used to read
        // the row on its own connection BEFORE the request thread committed: "No managed
        // database named ..." 100 ms after a successful create, and the status stamp
        // that followed found nothing to stamp either, which is how the record stayed
        // "Provisioning" forever with no container and no error (F4, 2026-08-29).
        // Outside a transaction afterCommit runs the hook at once, so the tests' and
        // TenantDatabases' bare-call shape is unchanged.
        exec(() -> model().getResolvedDatasource().afterCommit(
            () -> provisionInBackground(recordId)));
        return created;
    }

    /** {@link #insertRecord} with the default placement. */
    public Row insertRecord(String name, ManagedDatabase.Engine engine, String image, String user,
                            String password, String database, boolean ephemeral, String serverName,
                            ResourceLimits limits, String status) {
        return insertRecord(name, engine, image, user, password, database, ephemeral, serverName,
            limits, status, null, null);
    }

    /**
     * Persist a BRAND NEW database record, refusing a name that is already taken, and --
     * for a shared placement -- bind it to its engine (resolved or created on the record's
     * host, its instance row reserved inline so a full host refuses on the caller's form).
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
     * @param placement a {@link DatabaseModel#PLACEMENT} token, or null for the default
     * @param engineId  an explicit shared engine, or null to resolve the host's
     * @throws Violations {@code database_name_taken}, {@code database_placement_unsupported},
     *         {@code database_logical_identifier}, {@code database_shared_limits},
     *         {@code database_engine_host_mismatch}, {@code database_image_engine_mismatch}
     * @return the row that was created
     */
    public Row insertRecord(String name, ManagedDatabase.Engine engine, String image, String user,
                            String password, String database, boolean ephemeral, String serverName,
                            ResourceLimits limits, String status, @Nullable String placement,
                            @Nullable Integer engineId) {
        return query(() -> {
            DatabaseModel model = model();
            if (model.findByName(name) != null) {
                throw Violations.ofField(DatabaseModel.NAME.getName(), name,
                    Microcopy.of("database_name_taken").withFilter("scope", "violations")
                        .withArg("name", name));
            }
            String resolvedPlacement = placement == null || placement.isBlank()
                ? defaultPlacement(engine, ephemeral) : placement.trim();
            int serverId = ServerModel.canonicalServerId(serverName);
            Row engineRow = null;
            if (DatabaseModel.PLACEMENT_SHARED.equals(resolvedPlacement)) {
                requireLogicalIdentifiers(user, password, database);
                if (limits.memoryMb() != null || limits.cpus() != null) {
                    throw Violations.ofField(DatabaseModel.MEMORY_LIMIT_MB.getName(),
                        limits.memoryMb(), CmsSupport.violationText("database_shared_limits"));
                }
                engineRow = engineId != null ? explicitEngine(engineId, serverId, engine, image)
                    : DatabaseEngines.findOrCreateShared(serverId, engine, image);
                requireLogicalFree(engineRow.get(DatabaseEngineModel.ID), database, user, null);
            } else if (!DatabaseModel.PLACEMENT_DEDICATED.equals(resolvedPlacement)) {
                throw Violations.ofField(DatabaseModel.PLACEMENT.getName(), resolvedPlacement,
                    CmsSupport.violationText("database_placement_unknown")
                        .withArg("placement", resolvedPlacement));
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
            row.set(DatabaseModel.SERVER_ID, serverId);
            row.set(DatabaseModel.PLACEMENT, resolvedPlacement);
            row.set(DatabaseModel.ENGINE_ID, engineRow == null ? null
                : engineRow.get(DatabaseEngineModel.ID));
            model.save(row);
            return row;
        });
    }

    /**
     * An explicitly chosen engine: it must exist, run this kind, and sit on the record's
     * host (a link network only exists on the daemon both share).
     */
    private static Row explicitEngine(int engineId, int serverId, ManagedDatabase.Engine engine,
                                      @Nullable String image) {
        Row engineRow = Models.get(DatabaseEngineModel.class).findById(engineId);
        if (engineRow == null || !engine.token().equals(engineRow.get(DatabaseEngineModel.ENGINE))) {
            throw Violations.ofField(DatabaseModel.ENGINE_ID.getName(), engineId,
                CmsSupport.violationText("database_engine_kind_mismatch")
                    .withArg("engine", engine.token()));
        }
        int engineServer = ServerModel.canonicalServerId(engineRow.get(DatabaseEngineModel.SERVER_ID));
        if (engineServer != serverId) {
            throw Violations.ofField(DatabaseModel.ENGINE_ID.getName(), engineId,
                CmsSupport.violationText("database_engine_host_mismatch")
                    .withArg("name", String.valueOf((Object) engineRow.get(DatabaseEngineModel.NAME)))
                    .withArg("server", ServerModel.nameOf(engineServer))
                    .withArg("database_server", ServerModel.nameOf(serverId)));
        }
        DatabaseEngines.requireImageMatch(engineRow, image);
        return engineRow;
    }

    /**
     * @throws Violations {@code database_logical_identifier} on the first of user,
     *         password, name that the logical-database scripts cannot carry
     */
    static void requireLogicalIdentifiers(String user, String password, String database) {
        if (!ManagedDatabase.Engine.isLogicalIdentifier(database)) {
            throw Violations.ofField(DatabaseModel.DB_NAME.getName(), database,
                CmsSupport.violationText("database_logical_identifier"));
        }
        if (!ManagedDatabase.Engine.isLogicalIdentifier(user)) {
            throw Violations.ofField(DatabaseModel.DB_USER.getName(), user,
                CmsSupport.violationText("database_logical_identifier"));
        }
        if (!ManagedDatabase.Engine.isLogicalIdentifier(password)) {
            throw Violations.ofField(DatabaseModel.DB_PASSWORD.getName(), "",
                CmsSupport.violationText("database_logical_identifier"));
        }
    }

    /**
     * Converge an ALREADY PERSISTED record's runtime on the provisioning pool, flipping
     * its status to active or failed. The half of {@link #createAsync} that talks to the
     * daemon, for callers ({@link TenantDatabases}) that persisted the record and reserved
     * its engine instance row themselves and only owe the container work.
     *
     * @param recordId the COMMITTED record; everything the container needs (engine,
     *                 credentials, limits) is read off the row on the pool thread
     */
    public void provisionInBackground(int recordId) {
        PROVISION_EXECUTOR.submit(() -> {
            Row row = query(() -> model().findById(recordId));
            if (row == null) {
                // The one shape left that can miss: the record was committed and then
                // deleted before the pool got to it. Loud, never a silent no-op.
                Blast.log("DB: provisioning skipped, database record", recordId,
                    "is gone before its container was provisioned");
                return;
            }
            String name = row.get(DatabaseModel.NAME);
            try {
                provisionRuntime(row);
                setStatus(recordId, STATUS_ACTIVE, null);
            } catch (Exception e) {
                // TERMINAL and visible: the status is what the list badge, the detail
                // page and AttentionCollector.failedDatabases read; the reason rides the
                // record so the operator learns WHY without the journal.
                String reason = e.getMessage() != null ? e.getMessage() : e.toString();
                setStatus(recordId, STATUS_FAILED, reason);
                Blast.log("DB: provisioning failed for", name, "-", reason);
            }
        });
    }

    /** Stamp the provisioning outcome; a reason is stored on failure and cleared otherwise. */
    private void setStatus(int recordId, String status, String failureReason) {
        exec(() -> {
            DatabaseModel model = model();
            Row row = model.findById(recordId);
            if (row != null) {
                row.set(DatabaseModel.STATUS, status);
                row.set(DatabaseModel.FAILURE_REASON, failureReason);
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
     *  {@code liveness} is whether the ENGINE inside a running container is still alive;
     *  {@code placement} and {@code engineName} say WHOSE container that is. */
    public record Summary(String name, String engine, String image, String database, String user,
                          boolean ephemeral, String server, String status, boolean running,
                          ContainerState containerState, Integer port,
                          WorkloadLiveness liveness, String placement, String engineName) {

        /** The container runs but the kernel killed the engine inside it. */
        public boolean workloadDead() {
            return running && liveness == WorkloadLiveness.WORKLOAD_DEAD;
        }
    }

    /** Full detail for one database, including the password (admin detail page only). */
    public record Detail(String name, String engine, String image, String database, String user,
                         String password, boolean ephemeral, String server, String status,
                         String failureReason, boolean running, ContainerState containerState,
                         Integer port, WorkloadLiveness liveness, String placement,
                         String engineName) {

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
        ManagedDatabase.LiveStatus live = liveStatus(row);
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
            row.get(DatabaseModel.FAILURE_REASON),
            live.running(),
            live.state(),
            live.port(),
            live.liveness(),
            placementOf(row),
            engineNameOf(row));
    }

    /** All databases with live status (running + published port), best-effort per record. */
    public List<Summary> summaries() {
        List<Summary> result = new ArrayList<>();
        for (Row row : query(() -> model().find().all())) {
            ManagedDatabase.Engine engine = engineOf(row);
            ManagedDatabase.LiveStatus live = liveStatus(row);
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
                live.liveness(),
                placementOf(row),
                engineNameOf(row)
            ));
        }
        return result;
    }

    // Live status of the instance SERVING the record; never throws. A host we cannot
    // even build a client for is UNREACHABLE, never "not running" -- absent and
    // unreachable stay distinct.
    private ManagedDatabase.LiveStatus liveStatus(Row row) {
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

    /** The placement token, reading rows older than the column as dedicated. */
    public static @NonNull String placementOf(Row row) {
        return DatabaseModel.isShared(row) ? DatabaseModel.PLACEMENT_SHARED
            : DatabaseModel.PLACEMENT_DEDICATED;
    }

    /** {@link MemoryCeiling#source()}: the record's own declared {@code memory_limit_mb}. */
    public static final String MEMORY_SOURCE_DECLARED = "declared";

    /** {@link MemoryCeiling#source()}: nothing was declared, so the kind's footprint was booked. */
    public static final String MEMORY_SOURCE_DEFAULT = "default";

    /** {@link MemoryCeiling#source()}: a shared record inherits its ENGINE's ceiling. */
    public static final String MEMORY_SOURCE_ENGINE = "engine";

    /**
     * The memory ceiling a managed database actually runs under, and where that number
     * came from.
     *
     * @param megabytes the booked ceiling (charge == cap, so this is also the cgroup cap)
     * @param source    one of {@link #MEMORY_SOURCE_DECLARED}, {@link #MEMORY_SOURCE_DEFAULT},
     *                  {@link #MEMORY_SOURCE_ENGINE}
     */
    public record MemoryCeiling(int megabytes, @NonNull String source) {
    }

    /**
     * THE effective memory ceiling of a managed database: its own declared limit, the
     * default the booking used when it declares none, or -- for a SHARED record, which
     * owns no container at all -- its engine's.
     *
     * AIDEV-NOTE: the number is not computed here. It is asked of the very handler the
     * capacity hook books through ({@code InstanceCapacity.footprintMbOf}) over the very
     * settings {@code DatabaseInstances} deploys, so the API, the panel and the host
     * ledger can never quote three different ceilings for one record. Only the SOURCE
     * label is this method's own.
     *
     * @return null when the record's engine host cannot be resolved (a shared record whose
     *         engine row is gone), which is a defect to surface as an absent field rather
     *         than a number to guess
     */
    public static @Nullable MemoryCeiling memoryCeilingOf(@NonNull Row database) {
        EngineHost host;
        try {
            host = EngineHost.serving(database);
        } catch (RuntimeException dangling) {
            return null;
        }
        InstanceKindHandler handler = InstanceKinds.getHandler(DatabaseContainerKind.ID.toString());
        if (handler == null) {
            return null;
        }
        String source = host.shared() ? MEMORY_SOURCE_ENGINE
            : host.limits().memoryMb() != null ? MEMORY_SOURCE_DECLARED : MEMORY_SOURCE_DEFAULT;
        return new MemoryCeiling(
            InstanceCapacity.footprintMbOf(handler, DatabaseInstances.desiredSettings(host)),
            source);
    }

    /** The shared engine's name, or null for a dedicated record. */
    private @Nullable String engineNameOf(Row row) {
        if (!DatabaseModel.isShared(row)) {
            return null;
        }
        Integer engineId = row.get(DatabaseModel.ENGINE_ID);
        Row engine = engineId == null ? null
            : query(() -> Models.get(DatabaseEngineModel.class).findById(engineId));
        return engine == null ? null : engine.get(DatabaseEngineModel.NAME);
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

    /** The host serving a row, resolved inside the service's scope. */
    private EngineHost hostOf(Row row) {
        return query(() -> EngineHost.serving(row));
    }

    /**
     * Back up a persisted database into {@code directory}, naming the file {@code baseName} plus
     * the engine's dump extension, and return the written path. Handles text (SQL) and binary
     * (RDB / mongodump archive) engines. A shared record dumps ONE logical database out of its
     * engine with the engine's root credentials.
     */
    public Path backupToFile(String name, Path directory, String baseName) throws IOException {
        Row row = requireWith(name, HohenheimAccess.BACKUPS);
        return backupRowToFile(row, directory, baseName);
    }

    private Path backupRowToFile(Row row, Path directory, String baseName) throws IOException {
        EngineHost host = hostOf(row);
        Files.createDirectories(directory);
        Path target = directory.resolve(baseName + "." + host.engine().dumpExtension());
        managedFor(host.serverId()).backupToFile(handleOf(row, host), host.engine(),
            host.rootUser(), host.rootPassword(), row.get(DatabaseModel.DB_NAME), target);
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
        Row row = requireWith(name, HohenheimAccess.BACKUPS);
        EngineHost host = hostOf(row);
        Path directory = Files.createTempDirectory("hohenheim-backup");
        Path dump = directory.resolve(name + "." + host.engine().dumpExtension());
        try {
            managedFor(host.serverId()).backupToFile(handleOf(row, host), host.engine(),
                host.rootUser(), host.rootPassword(), row.get(DatabaseModel.DB_NAME), dump);
            return new BackupDownload(dump.getFileName().toString(),
                host.engine().dumpContentType(), Files.readAllBytes(dump));
        } finally {
            Files.deleteIfExists(dump);
            Files.deleteIfExists(directory);
        }
    }

    /** Restore a dump file (text or binary) into a persisted database by name. */
    public void restoreFromFile(String name, Path source) throws IOException {
        // No `restore` capability exists: an uploaded dump is arbitrary SQL run as the
        // engine superuser and no delegated surface offers it, so this stays an operator
        // act. A tenant-originated call is refused outright rather than silently allowed.
        Row row = requireWith(name, null);
        restoreRowFromFile(row, source);
    }

    private void restoreRowFromFile(Row row, Path source) throws IOException {
        EngineHost host = hostOf(row);
        managedFor(host.serverId()).restoreFromFile(handleOf(row, host), host.engine(),
            host.rootUser(), host.rootPassword(), row.get(DatabaseModel.DB_NAME), source);
    }

    /**
     * Verified end of life. A dedicated record's owned engine instance is destroyed
     * through {@link InstanceService} (container removed or observed absent, ledger claims
     * released fully, instance row soft-deleted), the data volume follows when asked, and
     * only then is the record deleted. A shared record's logical database and user are
     * DROPPED on its engine (the data too when asked); the engine stays. A destroy that
     * cannot confirm its teardown keeps the record (it holds the only copy of
     * {@code db_password}), flips its status to {@code destroy_failed} and throws;
     * deleting the record then requires an explicit retry or the recorded force-destroy
     * action. Silent "success" while the container keeps running was this codebase's
     * worst instance of a step doing less than it claims.
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
            Integer recordId = row.get(DatabaseModel.ID);
            if (recordId != null) {
                HohenheimAccess.requireDatabaseCapability(recordId, HohenheimAccess.DESTROY);
            }
            try {
                scoped(() -> {
                    if (DatabaseModel.isShared(row)) {
                        dropLogical(row, removeData);
                    } else {
                        DatabaseInstances.destroyFor(row, removeData);
                    }
                    return null;
                });
            } catch (IOException e) {
                if (recordId != null) {
                    // The status stamp is the service's own bookkeeping inside an
                    // operation whose destroy gate already ran above; a tenant-originated
                    // caller must not see its refusal rewritten as "status is frozen".
                    TenantWrites.inAuthorizedOperation(
                        () -> setStatus(recordId, STATUS_DESTROY_FAILED, e.getMessage()));
                }
                throw new IOException("Destroy of '" + name + "' could not verify its teardown"
                    + " (record kept): " + e.getMessage(), e);
            }
        }
        exec(() -> model().find().where(DatabaseModel.NAME.eq(name)).delete());
    }

    /**
     * Drop a shared record's user (and data) on its engine.
     *
     * @throws IOException when the engine is not serving, so nothing can be confirmed
     *         dropped -- the record is kept exactly like a dedicated destroy that cannot
     *         reach its daemon
     */
    private void dropLogical(Row row, boolean dropData) throws IOException {
        EngineHost host = EngineHost.serving(row);
        ManagedDatabase.LiveStatus live = DatabaseInstances.liveStatus(host);
        if (live.state() == ContainerState.ABSENT) {
            // No engine container has ever run for this record's engine (a refused or
            // failed provision): there is no data and no user to drop, exactly as a
            // dedicated record whose instance was never deployed destroys as "observed
            // absent". A STOPPED or UNREACHABLE engine still HOLDS the data and refuses.
            Blast.log("DB: shared engine", host.name(), "owns no container; nothing to drop for",
                row.get(DatabaseModel.NAME));
            return;
        }
        if (!live.running()) {
            throw new IOException("Shared engine '" + host.name() + "' is " + live.state()
                + "; the logical database cannot be dropped until it serves again");
        }
        String handle = DatabaseInstances.handleOf(host);
        if (handle == null) {
            throw new IOException("Shared engine '" + host.name() + "' owns no instance");
        }
        runLogical(host, handle, host.engine().dropLogicalCommand(host.rootUser(),
            row.get(DatabaseModel.DB_NAME), row.get(DatabaseModel.DB_USER), dropData),
            host.engine().logicalEnv(host.rootPassword(), null),
            "drop logical database '" + row.get(DatabaseModel.DB_NAME) + "'");
    }

    /**
     * The recorded escape hatch for a genuinely unreachable host: delete the record
     * WITHOUT verifying any teardown. The container and volume (or, for a shared record,
     * the logical database on its engine) may survive on the host; the reconciler reports
     * a dedicated leftover as an orphan once it can see the host again. Never the default
     * path -- callers must have an explicit operator decision.
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

    // -- moving a dedicated database onto a shared engine -----------------------

    /**
     * Move a DEDICATED, active database onto its host's shared engine, blocking until
     * done. The record is {@code provisioning} while it works; on success it is
     * {@code shared} + {@code active} with every attached workload redeployed onto the
     * new address, on failure it is {@code active} on its untouched dedicated engine
     * with the reason in {@code failure_reason} and the workloads running again.
     *
     * The order is the one that loses no write: the workloads are STOPPED first, the
     * dedicated engine dumped, the logical database created and restored, the content
     * fingerprint compared on both sides, and only then the record flipped, the old
     * instance destroyed (its data volume KEPT as a second rollback) and the workloads
     * deployed. The dump stays under {@code <backup_path>/moves/<name>/} where the
     * nightly prune never looks.
     *
     * @throws IOException naming the step that failed; nothing was switched
     */
    public void moveToSharedEngine(String name) throws IOException {
        Row row = query(() -> model().findByName(name));
        if (row == null) {
            throw new IOException("No managed database named '" + name + "'");
        }
        int recordId = row.get(DatabaseModel.ID);
        Microcopy refusal = moveRefusal(row);
        if (refusal != null) {
            // The KEY, not a re-spelled sentence: this is the last-line guard on a
            // background thread, and the surfaces that face a human (the row action's
            // visibility, the API's typed refusal) render the same key's Microcopy.
            throw new IOException("Database '" + name + "' cannot move onto a shared engine ("
                + refusal.key() + ")");
        }
        ManagedDatabase.Engine engine = engineOf(row);
        String storedUser = row.get(DatabaseModel.DB_USER);
        String password = row.get(DatabaseModel.DB_PASSWORD);
        String database = row.get(DatabaseModel.DB_NAME);
        query(() -> {
            requireLogicalIdentifiers(storedUser, password, database);
            return null;
        });
        int serverId = ServerModel.canonicalServerId(row.get(DatabaseModel.SERVER_ID));

        setStatus(recordId, STATUS_PROVISIONING, null);
        List<Integer> stopped = new ArrayList<>();
        try {
            // 1. Stop the consumers: their injected address is about to change.
            for (Row instance : query(() -> InstanceDatabaseLinks.liveInstances(recordId))) {
                int instanceId = instance.get(InstanceModel.ID);
                exec(() -> new InstanceService().stop(instanceId));
                stopped.add(instanceId);
            }

            // 2. Dump the dedicated engine; the file is the rollback.
            Path backupRoot = Path.of(HohenheimSettings.VALUES.getValue(
                HohenheimSettings.Database.BACKUP_PATH));
            Path dump = backupRowToFile(row, backupRoot.resolve("moves").resolve(name),
                STAMP.format(Instant.now()));
            EngineHost old = hostOf(row);
            String oldHandle = handleOf(row, old);
            String before = fingerprint(old, oldHandle, database);

            // 3. The shared engine, up. Its reservation may exceed the host budget by what
            //    the dedicated container below is about to give back: a full host must not
            //    refuse the engine for the very memory this move frees.
            Row oldInstance = query(() -> DatabaseInstances.ownedBy(old));
            long releasing = oldInstance == null ? 0
                : InstanceCapacity.bookedOfInstance(oldInstance.get(InstanceModel.ID));
            Row engineRow = InstanceCapacity.withPendingRelease(serverId, releasing,
                () -> query(() -> DatabaseEngines.findOrCreateShared(serverId, engine,
                    row.get(DatabaseModel.IMAGE))));
            int engineId = engineRow.get(DatabaseEngineModel.ID);
            // The logical USER must be unique on the engine: MySQL and Postgres users are
            // engine-global, so a dedicated record's "app" landing beside another
            // record's "app" would re-credential that record and reach both databases
            // (observed 2026-09-02, the two WordPress records on robbedoes). A taken user
            // is renamed to the database name, which the name check above keeps unique.
            String user = query(() -> isLogicalUserTaken(engineId, storedUser, recordId))
                ? database : storedUser;
            if (!user.equals(storedUser)) {
                Blast.log("DB-MOVE:", name, "renames its user", storedUser, "to", user,
                    "- another record on the engine already owns that user");
            }
            scoped(() -> {
                DatabaseEngines.ensureRunning(engineId);
                return null;
            });
            EngineHost target = scoped(() -> EngineHost.ofEngine(DatabaseEngines.require(engineId)));
            String targetHandle = handleOf(row, target);

            // 4. Logical database + user, restore, verify.
            runLogical(target, targetHandle, engine.createLogicalCommand(target.rootUser(),
                database, user), engine.logicalEnv(target.rootPassword(), password),
                "create logical database '" + database + "'");
            managedFor(serverId).restoreFromFile(targetHandle, engine, target.rootUser(),
                target.rootPassword(), database, dump);
            String after = fingerprint(target, targetHandle, database);
            if (!before.equals(after)) {
                throw new IOException("Content fingerprint differs after the restore into engine '"
                    + target.name() + "' (dedicated: " + before + "; shared: " + after
                    + "); nothing was switched");
            }

            // 5. Switch: the record, the old instance, the networks, the consumers.
            exec(() -> {
                Row fresh = model().findById(recordId);
                fresh.set(DatabaseModel.PLACEMENT, DatabaseModel.PLACEMENT_SHARED);
                fresh.set(DatabaseModel.ENGINE_ID, engineId);
                fresh.set(DatabaseModel.DB_USER, user);
                fresh.set(DatabaseModel.MEMORY_LIMIT_MB, null);
                fresh.set(DatabaseModel.CPU_LIMIT, null);
                fresh.set(DatabaseModel.STATUS, STATUS_ACTIVE);
                fresh.set(DatabaseModel.FAILURE_REASON, null);
                model().save(fresh);
            });
            try {
                scoped(() -> {
                    DatabaseInstances.destroyFor(old, false);
                    return null;
                });
            } catch (IOException e) {
                // The switch stands (the shared copy is verified); the old container is
                // an orphan the reconciler will surface, and the operator learns here.
                Blast.log("DB-MOVE: the old dedicated engine of", name,
                    "could not be destroyed after the move -", e.getMessage());
            }
            query(() -> InstanceDatabaseNetworks.reattachForDatabase(recordId));
            for (int instanceId : stopped) {
                exec(() -> new InstanceService().deploy(instanceId));
            }
            Blast.log("DB-MOVE: moved", name, "onto shared engine", target.name(),
                "- dump kept at", dump);
        } catch (IOException | RuntimeException | Error failed) {
            // Error is caught ON PURPOSE: an OutOfMemoryError in the restore (2026-09-02,
            // a 5.3 GB archive) skipped this block entirely and left the record
            // "provisioning" and its workload stopped, with no line in any log. The
            // compensation runs for every failure; the Error is rethrown as itself.
            String reason = failed.getMessage() != null ? failed.getMessage() : failed.toString();
            Blast.log("DB-MOVE: moving", name, "failed -", reason);
            // The record goes back to ACTIVE before its consumers are redeployed: a deploy
            // resolves the injected credentials off the record and REFUSES a database that
            // is not active (database_not_ready), so the other order left every workload
            // stopped after a refused move -- observed live on 2026-09-02 (invulassistent).
            setStatus(recordId, STATUS_ACTIVE, "Move to a shared engine failed: " + reason);
            for (int instanceId : stopped) {
                try {
                    exec(() -> new InstanceService().deploy(instanceId));
                } catch (RuntimeException e) {
                    Blast.log("DB-MOVE: could not restart workload", instanceId,
                        "after the failed move -", e.getMessage());
                }
            }
            if (failed instanceof Error error) {
                throw error;
            }
            throw failed instanceof IOException io ? io : new IOException(reason, failed);
        }
    }

    /**
     * THE declaration of whether a record may move onto a shared engine, and why not.
     *
     * One home for four facts three surfaces need: the row action's visibility, the
     * automation API's typed refusal and {@link #moveToSharedEngine}'s own last-line
     * guard. A second copy is how a panel offers a button the lane can only refuse.
     *
     * @return null when the move would be accepted, else the named reason it is not
     */
    public static @Nullable Microcopy moveRefusal(@NonNull Row row) {
        if (DatabaseModel.isShared(row)) {
            return CmsSupport.violationText("database_already_shared")
                .withArg("name", row.get(DatabaseModel.NAME));
        }
        if (!STATUS_ACTIVE.equals(row.get(DatabaseModel.STATUS))) {
            return CmsSupport.violationText("database_not_active")
                .withArg("name", row.get(DatabaseModel.NAME))
                .withArg("status", String.valueOf((Object) row.get(DatabaseModel.STATUS)));
        }
        ManagedDatabase.Engine engine =
            ManagedDatabase.Engine.forToken(row.get(DatabaseModel.ENGINE));
        if (engine == null || !engine.supportsLogicalDatabases()) {
            return CmsSupport.violationText("database_placement_unsupported")
                .withArg("engine", String.valueOf((Object) row.get(DatabaseModel.ENGINE)));
        }
        if (Boolean.TRUE.equals(row.get(DatabaseModel.EPHEMERAL))) {
            return CmsSupport.violationText("database_ephemeral_shared");
        }
        // The host's shared engine, if one exists yet, must not already hold a logical
        // database of this name: that is another record's data, and the restore would
        // land inside it.
        int serverId = ServerModel.canonicalServerId(row.get(DatabaseModel.SERVER_ID));
        Row existing = Models.get(DatabaseEngineModel.class).findOnHost(serverId, engine.token());
        if (existing != null) {
            Integer recordId = row.get(DatabaseModel.ID);
            Row holder = holderOf(existing.get(DatabaseEngineModel.ID), DatabaseModel.DB_NAME,
                row.get(DatabaseModel.DB_NAME), recordId);
            if (holder != null) {
                return CmsSupport.violationText("database_logical_name_taken")
                    .withArg("database", row.get(DatabaseModel.DB_NAME))
                    .withArg("engine", existing.get(DatabaseEngineModel.NAME))
                    .withArg("record", holder.get(DatabaseModel.NAME));
            }
        }
        return null;
    }

    /**
     * Refuse a logical database name or user another record on the same engine already
     * holds: a shared name would restore one record's data into another's, and a shared
     * user (engine-global on MySQL and Postgres) re-credentials the other record and
     * reaches both databases.
     *
     * @param excludeRecordId the record being placed, when it already exists
     * @throws Violations {@code database_logical_name_taken}, {@code database_logical_user_taken}
     */
    static void requireLogicalFree(int engineId, @NonNull String database, @NonNull String user,
                                   @Nullable Integer excludeRecordId) {
        Row engineRow = Models.get(DatabaseEngineModel.class).findById(engineId);
        String engineName = engineRow == null ? String.valueOf(engineId)
            : engineRow.get(DatabaseEngineModel.NAME);
        Row nameHolder = holderOf(engineId, DatabaseModel.DB_NAME, database, excludeRecordId);
        if (nameHolder != null) {
            throw Violations.ofField(DatabaseModel.DB_NAME.getName(), database,
                CmsSupport.violationText("database_logical_name_taken")
                    .withArg("database", database).withArg("engine", engineName)
                    .withArg("record", nameHolder.get(DatabaseModel.NAME)));
        }
        Row userHolder = holderOf(engineId, DatabaseModel.DB_USER, user, excludeRecordId);
        if (userHolder != null) {
            throw Violations.ofField(DatabaseModel.DB_USER.getName(), user,
                CmsSupport.violationText("database_logical_user_taken")
                    .withArg("user", user).withArg("engine", engineName)
                    .withArg("record", userHolder.get(DatabaseModel.NAME)));
        }
    }

    /** @return whether another record on the engine already owns this logical user */
    static boolean isLogicalUserTaken(int engineId, @NonNull String user, @Nullable Integer excludeRecordId) {
        return holderOf(engineId, DatabaseModel.DB_USER, user, excludeRecordId) != null;
    }

    /** @return the other record on the engine whose {@code field} equals {@code value}, or null */
    private static @Nullable Row holderOf(int engineId, @NonNull StringField field,
                                          @Nullable String value, @Nullable Integer excludeRecordId) {
        if (value == null) {
            return null;
        }
        for (Row other : DatabaseEngines.databasesOn(engineId)) {
            Integer otherId = other.get(DatabaseModel.ID);
            if (excludeRecordId != null && excludeRecordId.equals(otherId)) {
                continue;
            }
            if (value.equals(other.get(field))) {
                return other;
            }
        }
        return null;
    }

    /** {@link #moveToSharedEngine} on the provisioning pool, for the panel action. */
    public void moveToSharedEngineInBackground(String name) {
        PROVISION_EXECUTOR.submit(() -> {
            try {
                moveToSharedEngine(name);
            } catch (IOException e) {
                // Already stamped on the record by the move itself.
            } catch (RuntimeException | Error e) {
                // An executor swallows what a task throws; the move already compensated,
                // but a controller-level failure must reach the journal by name.
                Blast.log("DB-MOVE: moving", name, "died with", e.toString());
            }
        });
    }

    /** One database's content fingerprint on one host, trimmed for comparison. */
    private String fingerprint(EngineHost host, String handle, String database) throws IOException {
        DockerClient.ExecResult result = dockerFor(host.serverId()).exec(handle,
            host.engine().fingerprintCommand(host.rootUser(), database),
            host.engine().logicalEnv(host.rootPassword(), null));
        if (result.exitCode() != 0) {
            throw new IOException("Could not fingerprint '" + database + "' on engine '"
                + host.name() + "' (exit " + result.exitCode() + "): " + result.stderr().trim());
        }
        return result.stdout().trim();
    }

    /**
     * The record, plus the capability gate a TENANT-ORIGINATED call must pass for this
     * operation. Operator and system work (the nightly backup task, the reconciler) never
     * reaches the gate; see {@code HohenheimAccess.requireDatabaseCapability}.
     *
     * @param capability the capability required, or null when NO tenant may perform this
     *        operation at all (there is no verb for it)
     */
    private Row requireWith(String name, @Nullable String capability) throws IOException {
        Row row = require(name);
        if (capability == null) {
            if (TenantWrites.isTenantOriginated()) {
                throw HohenheimAccess.databaseRefusal();
            }
            return row;
        }
        Integer recordId = row.get(DatabaseModel.ID);
        if (recordId != null) {
            HohenheimAccess.requireDatabaseCapability(recordId, capability);
        }
        return row;
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
