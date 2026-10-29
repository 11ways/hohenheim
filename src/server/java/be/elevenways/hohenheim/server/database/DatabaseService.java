package be.elevenways.hohenheim.server.database;

import be.elevenways.hohenheim.server.HohenheimSystemPurposes;
import be.elevenways.hohenheim.server.Secrets;
import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.hohenheim.model.DatabaseEngineModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.auth.TenantWrites;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.InstanceDatabaseNetworks;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.instance.ConsoleRedaction;
import be.elevenways.hohenheim.server.instance.InstanceVariables;
import be.elevenways.hohenheim.server.instance.InstanceCapacity;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.UnfinishedDestroys;
import be.elevenways.hohenheim.server.instance.InstanceOperationLock;
import be.elevenways.hohenheim.server.notification.Alerts;
import be.elevenways.hohenheim.server.notification.NotificationEvents;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.server.runtime.WorkloadLiveness;
import be.elevenways.hohenheim.server.util.DatasourceScoped;
import be.elevenways.hohenheim.server.util.UtcStamp;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.protoblast.common.thread.JobRunnerGroup;
import be.elevenways.zenit.common.bootstrap.RuntimeJobs;
import be.elevenways.zenit.common.security.ExecutionIdentity;
import be.elevenways.zenit.common.security.KnownValueRedactor;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.history.Change;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.KeyedRows;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Lifecycle entry point for managed databases: ties the persisted {@link DatabaseModel}
 * record (desired config) to the RUNTIME. That runtime is
 * an owned instance driven by {@link DatabaseInstances}: EITHER
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

    /**
     * The provisioning pool (image pull + container start can take tens of seconds), shared because handlers construct
     * DatabaseService per request.
     *
     * AIDEV-NOTE: a runtime member, so the runtime's stop waits for a running job before it closes the datasources the
     * job still writes. A job that converges ONE record takes that record's content lease and re-reads it inside
     * ({@link #provisionInBackground}), so a destroy never runs beside it.
     */
    private static final JobRunnerGroup.Member PROVISIONING =
        RuntimeJobs.GROUP.member("db-provision", JobRunnerGroup.StopMode.DRAIN);

    /**
     * Run {@code work} on the provisioning pool as system work on the submitting caller's behalf.
     *
     * AIDEV-NOTE: the runner carries the submitting thread's context and the job raises it to system authority:
     * whoever asked was gated before the hand-off (an allocation, a move claim) and the engine containers it converges
     * are operator-owned, but a tenant's allocation stays the tenant's action in the activity log. A pool thread
     * declaring the identity itself recorded every such write as SYSTEM.
     *
     * @return done once the work ended, failed with what it threw
     * @throws IllegalStateException when the runtime stopped
     */
    static @NonNull CompletableFuture<Void> submit(@NonNull Runnable work) {
        return PROVISIONING.get().runAsync(
            () -> ExecutionIdentity.runAsSystem(HohenheimSystemPurposes.DATABASE_PROVISION, work));
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
        return new ManagedDatabase(new ServerService().clientFor(serverId));
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
        return create(name, engine, image, user, password, database, ephemeral, ServerService.LOCAL_HOST_NAME);
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
        return provisionInserted(insertRecord(name, engine, image, user, password, database, ephemeral,
            serverName, limits, DatabaseModel.STATUS_PROVISIONING, placement, null));
    }

    /**
     * Provisions the runtime of a record {@link #insertRecord} just persisted as provisioning, and marks it active.
     *
     * AIDEV-NOTE: split from {@link #create} so a caller that must undo ITS OWN create (a restore into a new database)
     * holds the inserted record's id before anything can fail, and never has to find what it created by name.
     *
     * @throws IOException when the provision fails; the record is then marked failed, never deleted
     */
    public ManagedDatabase.Connection provisionInserted(@NonNull Row created) throws IOException {
        int recordId = created.get(DatabaseModel.ID);
        try {
            ManagedDatabase.Connection connection = provisionRuntime(created);
            setStatus(recordId, DatabaseModel.STATUS_ACTIVE, null);
            return connection;
        } catch (IOException e) {
            setStatus(recordId, DatabaseModel.STATUS_FAILED, e.getMessage());
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
     * AIDEV-NOTE: the credentials are re-read INSIDE the engine lock a password change stores under
     * ({@link DatabaseEngines#underLock}). The row this job was handed can be older than a rotation that landed since,
     * and re-crediting the engine with that stale password would leave the record naming one the engine refuses.
     *
     * @return the engine's published loopback port
     */
    private int provisionLogical(Row row) throws IOException {
        EngineHost host = EngineHost.serving(row);
        Integer recordId = row.get(DatabaseModel.ID);
        DatabaseEngines.underLock(host.ownerId(), () -> {
            DatabaseEngines.ensureRunning(host.ownerId());
            String handle = DatabaseInstances.handleOf(host);
            if (handle == null) {
                throw new IOException("Shared engine '" + host.name() + "' is up but owns no instance");
            }
            // Settled first, under the lock a change holds: a drifted change's pending password the engine took is
            // adopted before the re-credential, so the record and the engine end on the password the record stores.
            Row stored = recordId == null ? null : model().findById(recordId);
            Row current = stored != null ? settlePending(stored).row() : row;
            runLogical(host, handle, host.engine().createLogicalCommand(host.rootUser(),
                current.get(DatabaseModel.DB_NAME), current.get(DatabaseModel.DB_USER)),
                host.engine().logicalEnv(host.rootPassword(), current.get(DatabaseModel.DB_PASSWORD)),
                "create logical database '" + current.get(DatabaseModel.DB_NAME) + "'");
            if (recordId != null && current.get(DatabaseModel.PENDING_PASSWORD) != null) {
                // The engine now holds the stored password, whatever a drifted change left it on.
                clearPending(recordId, ConsoleRedaction.secretsOfOwner(DatabaseModel.MODEL_ID, recordId));
            }
            return null;
        });
        ManagedDatabase.LiveStatus live = DatabaseInstances.liveStatus(host);
        if (!live.running() || live.port() == null) {
            throw new IOException("Shared engine '" + host.name() + "' publishes no port");
        }
        return live.port();
    }

    /**
     * Run one logical-database command on an engine host and refuse a non-zero exit by name.
     *
     * AIDEV-NOTE: logical DDL gets the long-operation bound: DROP DATABASE of a large database (and CREATE from a big
     * template) can outlast the client's request deadline, and a cut-off drop leaves the record kept as destroy_failed.
     */
    private void runLogical(EngineHost host, String handle, List<String> command,
                            List<String> env, String what) throws IOException {
        DockerClient.ExecResult result = new ServerService().clientFor(host.serverId()).exec(handle, command, env,
            DockerClient.LONG_OP_TIMEOUT_MS);
        if (result.exitCode() != 0) {
            throw new IOException("Could not " + what + " on engine '" + host.name() + "' (exit "
                + result.exitCode() + "): " + (result.stderr() + " " + result.stdout()).trim());
        }
    }

    /**
     * Set a record's password inside the engine serving it, then store it on the record: a SHARED record's user
     * through the provisioning re-credential command ({@link ManagedDatabase.Engine#createLogicalCommand}), a
     * DEDICATED engine's own credential through {@link ManagedDatabase.Engine#rotateOwnCommand}, whose engine
     * instance's stored environment is then rewritten so its next container create starts from the new password.
     * Either way the store bumps the record's credential generation, which attached workloads compare against.
     *
     * AIDEV-NOTE: the engine goes first, so a refusal BEFORE the command is dispatched (no serving engine, no handle)
     * stores nothing. Once it is dispatched, a non-zero exit may leave the engine holding the new password: the
     * scripts run several statements and the password statement comes first, so a later CREATE DATABASE or REVOKE
     * refused does not mean the engine kept the old one. That failure is stamped on the record as an active record's
     * failure reason (DatabaseAttention's "last operation failed" item) and refused, never swallowed; a later
     * successful change clears it in the same save. After a clean exit the record and a dedicated engine instance's
     * stored environment are written in ONE transaction, so a failing store writes neither, and the same command then
     * sets the engine back to the password the record keeps; only when that fails too is the drift stamped, and for a
     * dedicated record it says a restart is unsafe (the stored environment names the old password while the data
     * directory, or redis's running process, holds the new one). Every failure text is redacted through the record's
     * stored secrets plus the new password, which no stored row names yet; the unredacted cause is never chained.
     *
     * AIDEV-NOTE: the new password is stored as the record's PENDING password inside the claim before the engine is
     * asked, and cleared when the record and the engine agree again (stored, or set back). A change that drifted keeps
     * it, and that is what makes the drift recoverable: the stored password is the one the next credential script
     * authenticates with, which an engine holding the new one refuses, so every later use of the credential (this
     * change retried, a dump, a restore, a move) first runs {@link #settlePending}, which adopts the pending password
     * when the engine admits it.
     *
     * AIDEV-NOTE: no capture lane carries a dedicated engine's data, so no restore can put an older credential back
     * under a record holding a newer one: the engine's data volume is the kind's {@code data_volume}, never a declared
     * {@code volumes} entry, so a snapshot of the engine instance is refused ({@code snapshot_no_volumes}) and a backup
     * captures none of its data, and a restore-to-new of the generated-only kind is refused. The database's own dump
     * and restore move data, never the engine's users. A capture lane that ever covers that volume must record the
     * credential generation and the password with it and put both back on restore.
     *
     * AIDEV-NOTE: the exec and the store run under ONE claim, the record re-read and re-validated inside it, so two
     * concurrent changes (or a change and a provisioning re-credential, or a change and a restart of the engine)
     * cannot end with the engine holding one password and the record another. A shared record holds its engine's lock
     * ({@link DatabaseEngines#underLock}), the one provisioning re-credentials under; a dedicated record holds its
     * engine instance's operation claim ({@link InstanceOperationLock}), the one every deploy, stop and restart of
     * that container holds, queueing behind a running one.
     *
     * AIDEV-NOTE: the store runs in {@link TenantWrites#inAuthorizedOperation}: the operation already demanded
     * {@code manage} on this very record, and the tenant write invariant freezes every database column for a tenant,
     * so without the scope the owner's own rotation would be refused as a column write. The engine instance's
     * environment is the system's mirror of that record, written as the provisioning lane writes it.
     *
     * @param password the new password, held here to {@link #requireNewLogicalCredentials} or
     *                 {@link #requireNewOwnCredentials}
     * @throws Violations a credential refusal, or the engine instance's claim refused
     * @throws IOException naming why, redacted
     */
    public void changePassword(int recordId, @NonNull String password) throws IOException {
        Row row = requireRecord(recordId);
        if (DatabaseModel.isShared(row)) {
            Integer engineId = row.get(DatabaseModel.ENGINE_ID);
            if (engineId == null) {
                throw new IOException("Database '" + row.get(DatabaseModel.NAME) + "' is shared but names no engine");
            }
            DatabaseEngines.underLock(engineId, () -> {
                rotateLocked(recordId, engineId, password);
                return null;
            });
            return;
        }
        Row instance = query(() -> DatabaseInstances.owned(recordId));
        if (instance == null) {
            throw new IOException("Managed database '" + row.get(DatabaseModel.NAME)
                + "' is served by no engine instance yet; provision it before changing its password");
        }
        underInstanceClaim(instance.get(InstanceModel.ID), () -> {
            try {
                rotateLocked(recordId, null, password);
            } catch (IOException failed) {
                throw new UncheckedIOException(failed);
            }
        });
    }

    /**
     * The body of {@link #changePassword}, holding the claim of the engine the record named when it was read.
     *
     * @param engineId the shared engine whose lock is held, null for a dedicated record holding its instance's claim
     */
    private void rotateLocked(int recordId, @Nullable Integer engineId, @NonNull String password) throws IOException {
        Row read = requireRecord(recordId);
        String name = read.get(DatabaseModel.NAME);
        boolean shared = DatabaseModel.isShared(read);
        if (shared != (engineId != null) || (shared && !Objects.equals(read.get(DatabaseModel.ENGINE_ID), engineId))) {
            throw new IOException("Database '" + name + "' moved to another engine while its password change waited;"
                + " change it again");
        }
        if (shared) {
            requireNewLogicalCredentials(read.get(DatabaseModel.DB_USER), password, read.get(DatabaseModel.DB_NAME));
        } else {
            requireNewOwnCredentials(engineOf(read), read.get(DatabaseModel.DB_USER), password);
        }
        // A change that failed half-way left its password pending: the engine may hold it instead of the stored one.
        Settlement settled = settlePending(read);
        if (!shared && settled.neitherAdmitted()) {
            // A dedicated rotation authenticates with the stored password, which the engine just refused: asking it
            // would fail, and storing this password as pending would replace the one the engine may hold.
            throw new IOException("The engine of '" + name + "' accepts neither the stored nor the pending password,"
                + " so no change can authenticate to it; nothing was changed or stored. Manual recovery is needed: set"
                + " the engine's own password back to the stored one inside its container, then change it again");
        }
        Row row = settled.row();
        ConsoleRedaction.Secrets secrets = query(() -> ConsoleRedaction.secretsOfOwner(DatabaseModel.MODEL_ID,
            recordId)).and(credentialSecrets(password));
        EngineHost host;
        String handle;
        List<String> command;
        List<String> env;
        try {
            host = query(() -> EngineHost.serving(row));
            handle = handleOf(row, host);
            if (shared) {
                command = host.engine().createLogicalCommand(host.rootUser(), row.get(DatabaseModel.DB_NAME),
                    row.get(DatabaseModel.DB_USER));
                env = host.engine().logicalEnv(host.rootPassword(), password);
            } else {
                command = host.engine().rotateOwnCommand(host.rootUser());
                env = host.engine().rotateEnv(host.rootPassword(), password);
            }
            // Before the engine is asked: a failure past this point leaves the record naming what the engine may hold.
            TenantWrites.inAuthorizedOperation(() -> exec(() -> {
                Row fresh = Objects.requireNonNull(model().findById(recordId), "the database record is gone");
                fresh.set(DatabaseModel.PENDING_PASSWORD, password);
                model().save(fresh);
            }));
        } catch (IOException | RuntimeException refused) {
            throw new IOException(ConsoleRedaction.redactWhole(HohenheimViolations.reasonOf(refused), secrets));
        }
        String what = shared
            ? "change the password of logical database '" + row.get(DatabaseModel.DB_NAME) + "'"
            : "change the password of database '" + name + "'";
        try {
            runLogical(host, handle, command, env, what);
        } catch (IOException | RuntimeException failed) {
            throw drifted(recordId, row, secrets, "Changing the password of '" + name + "' failed after the engine was"
                + " asked to take it (" + ConsoleRedaction.redactWhole(HohenheimViolations.reasonOf(failed), secrets)
                + "); the engine may now hold the new password, which the record keeps as pending: retrying the"
                + " rotation, or the next use of the credential (a dump, a restore, a move), asks the engine which"
                + " password it admits and stores the pending one if the engine took it");
        }
        try {
            TenantWrites.inAuthorizedOperation(() -> exec(() -> model().getResolvedDatasource().withTransaction(
                transaction -> storeRotated(recordId, shared, password))));
        } catch (RuntimeException unstored) {
            String why = ConsoleRedaction.redactWhole(HohenheimViolations.reasonOf(unstored), secrets);
            String current = Objects.toString(row.get(DatabaseModel.DB_PASSWORD), "");
            List<String> undo = shared ? host.engine().logicalEnv(host.rootPassword(), current)
                : host.engine().rotateEnv(password, current);
            try {
                runLogical(host, handle, command, undo, "set the password of '" + name + "' back");
            } catch (IOException | RuntimeException notUndone) {
                throw drifted(recordId, row, secrets, "The engine of '" + name + "' took the new password but storing"
                    + " it failed (" + why + "), and setting the engine back failed too ("
                    + ConsoleRedaction.redactWhole(HohenheimViolations.reasonOf(notUndone), secrets) + "): the"
                    + " engine holds the new password while the record keeps the old one"
                    + (shared ? "" : " and so does the engine instance's stored environment, so restarting the"
                        + " database is unsafe")
                    + " until the record stores it. The record keeps it as pending: retrying the rotation, or the next"
                    + " use of the credential (a dump, a restore, a move), finds the engine admitting it and stores it");
            }
            clearPending(recordId, secrets);
            throw new IOException("Changing the password of '" + name + "' failed while storing it (" + why
                + "); the engine was set back to the password the record keeps, so nothing changed");
        }
    }

    /**
     * Settle a record a password change left a pending password on, for a caller holding the claim that change holds:
     * the engine is asked whether it admits the stored password and, only when it refuses it, the pending one. The
     * stored one admitted clears the pending one (and the drift it left); the pending one admitted becomes the stored
     * one through {@link #storeRotated}. Neither admitted, or no engine to ask, leaves the record as it is; neither
     * admitted is said in the result and logged with each check's answer, redacted.
     *
     * AIDEV-NOTE: only an admission is evidence. A refusal and a check that could not run look alike
     * ({@link ManagedDatabase.Engine#credentialCheckCommand}), so nothing is ever cleared or adopted on a failure.
     *
     * @return the record as it stands after, and whether the engine was asked and admitted neither password
     */
    private Settlement settlePending(@NonNull Row row) throws IOException {
        String pending = row.get(DatabaseModel.PENDING_PASSWORD);
        Integer recordId = row.get(DatabaseModel.ID);
        if (pending == null || recordId == null) {
            return new Settlement(row, false);
        }
        boolean shared = DatabaseModel.isShared(row);
        String stored = Objects.toString(row.get(DatabaseModel.DB_PASSWORD), "");
        ConsoleRedaction.Secrets secrets = query(() -> ConsoleRedaction.secretsOfOwner(DatabaseModel.MODEL_ID,
            recordId));
        EngineHost host;
        String handle;
        List<String> command;
        try {
            host = hostOf(row);
            handle = query(() -> DatabaseInstances.handleOf(host));
            command = host.engine().credentialCheckCommand(row.get(DatabaseModel.DB_USER),
                row.get(DatabaseModel.DB_NAME), shared);
        } catch (RuntimeException unresolved) {
            Blast.log("DB: the pending password of", row.get(DatabaseModel.NAME), "could not be checked -",
                ConsoleRedaction.redactWhole(HohenheimViolations.reasonOf(unresolved), secrets));
            return new Settlement(row, false);
        }
        if (handle == null) {
            return new Settlement(row, false);
        }
        DockerClient docker = new ServerService().clientFor(host.serverId());
        String storedRefused = checkRefusal(docker, handle, command, host.engine().credentialCheckEnv(stored));
        if (storedRefused == null) {
            clearPending(recordId, secrets);
            return new Settlement(requireRecord(recordId), false);
        }
        String pendingRefused = checkRefusal(docker, handle, command, host.engine().credentialCheckEnv(pending));
        if (pendingRefused != null) {
            Blast.log("DB: the engine of", row.get(DatabaseModel.NAME),
                "admits neither the stored nor the pending password - the stored one:",
                ConsoleRedaction.redactWhole(storedRefused, secrets), "- the pending one:",
                ConsoleRedaction.redactWhole(pendingRefused, secrets));
            return new Settlement(row, true);
        }
        try {
            TenantWrites.inAuthorizedOperation(() -> exec(() -> model().getResolvedDatasource().withTransaction(
                transaction -> storeRotated(recordId, shared, pending))));
        } catch (RuntimeException unstored) {
            Blast.log("DB: the engine of", row.get(DatabaseModel.NAME),
                "admits the pending password of a failed change, but storing it failed -",
                ConsoleRedaction.redactWhole(HohenheimViolations.reasonOf(unstored), secrets));
            return new Settlement(row, false);
        }
        Blast.log("DB: the engine of", row.get(DatabaseModel.NAME),
            "admits the pending password of a failed change; the record stores it now");
        return new Settlement(requireRecord(recordId), false);
    }

    /**
     * What {@link #settlePending} found.
     *
     * @param neitherAdmitted whether the engine was asked and admitted neither the stored nor the pending password
     */
    private record Settlement(@NonNull Row row, boolean neitherAdmitted) {
    }

    /**
     * @return null when the engine answered the credential check with success, else why not (the exit and what the
     *         check printed, or why it could not run), unredacted
     */
    private static @Nullable String checkRefusal(@NonNull DockerClient docker, @NonNull String handle,
                                                 @NonNull List<String> command, @NonNull List<String> env) {
        try {
            DockerClient.ExecResult result = docker.exec(handle, command, env);
            return result.exitCode() == 0 ? null
                : "exit " + result.exitCode() + ": " + (result.stderr() + " " + result.stdout()).trim();
        } catch (IOException unasked) {
            return "the check could not run: " + HohenheimViolations.reasonOf(unasked);
        }
    }

    /**
     * Clear a record's pending password once the record and the engine agree, and the drift a failed change stamped
     * with it ({@link CredentialStamp}); a clear that fails is logged, never masking the outcome it follows (the stored
     * password is the one tried first, so a stale pending one is harmless).
     */
    private void clearPending(int recordId, ConsoleRedaction.@NonNull Secrets secrets) {
        try {
            TenantWrites.inAuthorizedOperation(() -> exec(() -> {
                Row fresh = model().findById(recordId);
                if (fresh == null || fresh.get(DatabaseModel.PENDING_PASSWORD) == null) {
                    return;
                }
                fresh.set(DatabaseModel.PENDING_PASSWORD, null);
                CredentialStamp.clearFrom(fresh);
                model().save(fresh);
            }));
        } catch (RuntimeException uncleared) {
            Blast.log("DB: the pending password of database", recordId, "could not be cleared -",
                ConsoleRedaction.redactWhole(HohenheimViolations.reasonOf(uncleared), secrets));
        }
    }

    /**
     * Store a password the engine took: the record one credential generation further and, for a dedicated record, its
     * engine instance's stored environment. The caller holds ONE transaction around both, so a failure of either
     * leaves neither written.
     */
    private static void storeRotated(int recordId, boolean shared, @NonNull String password) {
        DatabaseModel model = model();
        Row fresh = Objects.requireNonNull(model.findById(recordId), "the database record is gone");
        fresh.set(DatabaseModel.DB_PASSWORD, password);
        fresh.set(DatabaseModel.CREDENTIAL_GENERATION, DatabaseModel.credentialGenerationOf(fresh) + 1);
        if (fresh.get(DatabaseModel.PENDING_PASSWORD) != null) {
            fresh.set(DatabaseModel.PENDING_PASSWORD, null);
        }
        CredentialStamp.clearFrom(fresh);
        model.save(fresh);
        if (!shared) {
            EngineHost own = EngineHost.dedicated(fresh);
            ExecutionIdentity.runAsSystem(HohenheimSystemPurposes.DATABASE_PROVISION,
                () -> DatabaseInstances.storeEngineEnvironment(own));
        }
    }

    /**
     * Stamp a password change's drift on the record as its failure reason ({@link CredentialStamp}) and hand back the
     * refusal saying it; a stamp that fails too is logged, never masking the refusal.
     */
    private IOException drifted(int recordId, @NonNull Row row, ConsoleRedaction.@NonNull Secrets secrets,
                                @NonNull String reason) {
        String stamped = CredentialStamp.of(reason);
        try {
            TenantWrites.inAuthorizedOperation(() -> setStatus(recordId,
                Objects.toString(row.get(DatabaseModel.STATUS), DatabaseModel.STATUS_ACTIVE), stamped));
        } catch (RuntimeException unstamped) {
            Blast.log("DB: the password drift of", row.get(DatabaseModel.NAME), "could not be stamped either -",
                ConsoleRedaction.redactWhole(HohenheimViolations.reasonOf(unstamped), secrets));
        }
        return new IOException(stamped);
    }

    /**
     * THE failure reasons a record's credential leaves on it when it is not settled (a password change that drifted,
     * a redis engine left without a password it could start with): the only failure reasons a credential that settles
     * clears, so a provisioning or destroy failure stamped beside one survives a password change.
     *
     * AIDEV-NOTE: a stamp is recognised by its lead, which every credential stamp starts with and no other writer of
     * {@link DatabaseModel#FAILURE_REASON} spells; redaction never reaches it, since it names no secret.
     */
    static final class CredentialStamp {

        /** What every credential stamp starts with. */
        static final String LEAD = "Password not settled: ";

        private CredentialStamp() {
        }

        /** @return {@code reason} as a credential stamp */
        static @NonNull String of(@NonNull String reason) {
            return LEAD + reason;
        }

        /** @return whether a stored failure reason is a credential stamp */
        static boolean is(@Nullable String failureReason) {
            return failureReason != null && failureReason.startsWith(LEAD);
        }

        /** Clear the record's failure reason when it is a credential stamp; any other is left as it is. */
        static void clearFrom(@NonNull Row record) {
            if (is(record.get(DatabaseModel.FAILURE_REASON))) {
                record.set(DatabaseModel.FAILURE_REASON, null);
            }
        }
    }

    /** @return a credential and its URL-encoded spelling as redactable secrets; a value too short to redact is left out */
    private static ConsoleRedaction.@NonNull Secrets credentialSecrets(@NonNull String credential) {
        Set<String> values = new LinkedHashSet<>();
        for (String spelling : List.of(credential, DatabaseEnvInjection.urlEncoded(credential))) {
            if (KnownValueRedactor.accepts(spelling)) {
                values.add(spelling);
            }
        }
        return new ConsoleRedaction.Secrets(Set.copyOf(values), true);
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
    interface ThrowingSupplier<T> {
        T get() throws IOException;
    }

    /** Provision asynchronously on the local host; see the server-aware overload. */
    public Row createAsync(String name, ManagedDatabase.Engine engine, String image,
                           String user, String password, String database, boolean ephemeral) {
        return createAsync(name, engine, image, user, password, database, ephemeral,
            ServerService.LOCAL_HOST_NAME);
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
            serverName, limits, DatabaseModel.STATUS_PROVISIONING, placement, engineId);
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
     * AIDEV-NOTE: the name check, the engine resolution and the save run in ONE
     * transaction. The database quota is reserved in a before-write hook and the shared
     * engine's instance row is reserved inline, and zenit's Quotas ride the ambient
     * transaction: two concurrent creates of one name used to both pass the check, both
     * reserve, and the loser's unique-index failure left its quota slot spent forever. Now
     * the loser either waits for the winner's commit (SQLite write transactions are
     * IMMEDIATE, so the check itself serializes) and is refused by name, or rolls its
     * reservations back with its failed insert. A caller's outer transaction is joined.
     *
     * @param placement a {@link DatabaseModel#PLACEMENT} token, or null for the default
     * @param engineId  an explicit shared engine, or null to resolve the host's
     * @throws Violations {@code database_name_taken}, {@code database_placement_unsupported},
     *         {@code database_logical_identifier}, {@code database_logical_password}, {@code database_shared_limits},
     *         {@code database_engine_host_mismatch}, {@code database_image_engine_mismatch}
     * @return the row that was created
     */
    public Row insertRecord(String name, ManagedDatabase.Engine engine, String image, String user,
                            String password, String database, boolean ephemeral, String serverName,
                            ResourceLimits limits, String status, @Nullable String placement,
                            @Nullable Integer engineId) {
        return query(() -> {
            Row[] inserted = new Row[1];
            model().getResolvedDatasource().withTransaction(transaction -> inserted[0] =
                insertInTransaction(name, engine, image, user, password, database, ephemeral,
                    serverName, limits, status, placement, engineId));
            return inserted[0];
        });
    }

    /** The body of {@link #insertRecord}, run inside its transaction. */
    private static Row insertInTransaction(String name, ManagedDatabase.Engine engine, String image,
                                           String user, String password, String database,
                                           boolean ephemeral, String serverName, ResourceLimits limits,
                                           String status, @Nullable String placement,
                                           @Nullable Integer engineId) {
        DatabaseModel model = model();
        if (model.findByName(name) != null) {
            throw Violations.ofField(DatabaseModel.NAME.getName(), name,
                HohenheimMicrocopy.VIOLATIONS.of("database_name_taken")
                    .withArg("name", name));
        }
        String resolvedPlacement = placement == null || placement.isBlank()
            ? defaultPlacement(engine, ephemeral) : placement.trim();
        int serverId = ServerModel.canonicalServerId(serverName);
        Row engineRow = null;
        if (DatabaseModel.PLACEMENT_SHARED.equals(resolvedPlacement)) {
            requireNewLogicalCredentials(user, password, database);
            if (limits.memoryMb() != null || limits.cpus() != null) {
                throw Violations.ofField(DatabaseModel.MEMORY_LIMIT_MB.getName(),
                    limits.memoryMb(), HohenheimMicrocopy.VIOLATIONS.of("database_shared_limits"));
            }
            engineRow = engineId != null ? explicitEngine(engineId, serverId, engine, image)
                : DatabaseEngines.findOrCreateShared(serverId, engine, image);
            requireLogicalFree(engineRow.get(DatabaseEngineModel.ID), database, user, null);
        } else if (!DatabaseModel.PLACEMENT_DEDICATED.equals(resolvedPlacement)) {
            throw Violations.ofField(DatabaseModel.PLACEMENT.getName(), resolvedPlacement,
                HohenheimMicrocopy.VIOLATIONS.of("database_placement_unknown")
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
                HohenheimMicrocopy.VIOLATIONS.of("database_engine_kind_mismatch")
                    .withArg("engine", engine.token()));
        }
        int engineServer = ServerModel.canonicalServerId(engineRow.get(DatabaseEngineModel.SERVER_ID));
        if (engineServer != serverId) {
            throw Violations.ofField(DatabaseModel.ENGINE_ID.getName(), engineId,
                HohenheimMicrocopy.VIOLATIONS.of("database_engine_host_mismatch")
                    .withArg("name", String.valueOf((Object) engineRow.get(DatabaseEngineModel.NAME)))
                    .withArg("server", ServerModel.nameOf(engineServer))
                    .withArg("database_server", ServerModel.nameOf(serverId)));
        }
        DatabaseEngines.requireImageMatch(engineRow, image);
        return engineRow;
    }

    /**
     * The rule for STORED logical credentials (a move onto a shared engine carries the record's own): what the
     * logical-database scripts can spell, nothing about length beyond it.
     *
     * @throws Violations {@code database_logical_identifier} on the first of user,
     *         password, name that the logical-database scripts cannot carry
     */
    static void requireLogicalIdentifiers(String user, String password, String database) {
        requireLogicalNames(user, database);
        if (!ManagedDatabase.Engine.isLogicalIdentifier(password)) {
            throw Violations.ofField(DatabaseModel.DB_PASSWORD.getName(), "", logicalIdentifierRefusal());
        }
    }

    /**
     * THE rule for NEW logical credentials, at a create and at a password change alike: the names as
     * {@link #requireLogicalIdentifiers} holds them, the password also at least
     * {@link ManagedDatabase.Engine#LOGICAL_PASSWORD_MIN} long so every log redacts it. A stored shorter password keeps
     * working; only a new value is held to the minimum.
     *
     * @throws Violations {@code database_logical_identifier} for the name or user, {@code database_logical_password}
     */
    static void requireNewLogicalCredentials(String user, String password, String database) {
        requireLogicalNames(user, database);
        requireNewPassword(password);
    }

    /**
     * The rule for a DEDICATED engine's new password: the password as {@link #requireNewLogicalCredentials} holds it,
     * and the user the rotation spells into SQL or JavaScript as a logical identifier where the engine names one
     * ({@link ManagedDatabase.Engine#credentialNamesUser}).
     *
     * @throws Violations {@code database_logical_identifier} for the user, {@code database_logical_password}
     */
    static void requireNewOwnCredentials(ManagedDatabase.@NonNull Engine engine, String user, String password) {
        if (engine.credentialNamesUser() && !ManagedDatabase.Engine.isLogicalIdentifier(user)) {
            throw Violations.ofField(DatabaseModel.DB_USER.getName(), user, logicalIdentifierRefusal());
        }
        requireNewPassword(password);
    }

    /** @throws Violations {@code database_logical_password} for a password a new credential may not be */
    private static void requireNewPassword(String password) {
        if (!ManagedDatabase.Engine.isNewLogicalPassword(password)) {
            throw Violations.ofField(DatabaseModel.DB_PASSWORD.getName(), "",
                HohenheimMicrocopy.VIOLATIONS.of("database_logical_password")
                    .withArg("minimum", ManagedDatabase.Engine.LOGICAL_PASSWORD_MIN)
                    .withArg("maximum", ManagedDatabase.Engine.LOGICAL_IDENTIFIER_MAX));
        }
    }

    /** @throws Violations {@code database_logical_identifier} on the first of name, user the scripts cannot carry */
    private static void requireLogicalNames(String user, String database) {
        if (!ManagedDatabase.Engine.isLogicalIdentifier(database)) {
            throw Violations.ofField(DatabaseModel.DB_NAME.getName(), database, logicalIdentifierRefusal());
        }
        if (!ManagedDatabase.Engine.isLogicalIdentifier(user)) {
            throw Violations.ofField(DatabaseModel.DB_USER.getName(), user, logicalIdentifierRefusal());
        }
    }

    private static @NonNull Microcopy logicalIdentifierRefusal() {
        return HohenheimMicrocopy.VIOLATIONS.of("database_logical_identifier")
            .withArg("maximum", ManagedDatabase.Engine.LOGICAL_IDENTIFIER_MAX);
    }

    /**
     * Converge an ALREADY PERSISTED record's runtime on the provisioning pool, flipping
     * its status to active or failed. The half of {@link #createAsync} that talks to the
     * daemon, for callers ({@link TenantDatabases}) that persisted the record and reserved
     * its engine instance row themselves and only owe the container work.
     *
     * AIDEV-NOTE: the job runs holding the record's content lease and reads the record inside it. A destroy takes the
     * same lease: one that holds it first leaves the job to find the record gone, and one asked while the job runs is
     * refused with {@code database_busy}, so a destroyed record never gets an engine instance on its host afterwards.
     * The job queues behind whatever else holds the lease (a destroy, a move's dump); only the whole queue wait spent
     * marks the record failed.
     *
     * @param recordId the COMMITTED record; everything the container needs (engine,
     *                 credentials, limits) is read off the row on the pool thread
     * @return done once the job ended, the record converged, failed or found gone
     */
    public @NonNull CompletableFuture<Void> provisionInBackground(int recordId) {
        return submit(() -> {
            try {
                DatabaseContentLock.exclusive(recordId, InstanceOperationLock.Contention.QUEUE, () -> {
                    provisionStored(recordId);
                    return null;
                });
            } catch (IOException | DomainRefusal refused) {
                // The lease stayed taken for the whole queue wait: the record must not stay "provisioning" for good.
                String reason = setStatus(recordId, DatabaseModel.STATUS_FAILED, HohenheimViolations.reasonOf(refused));
                Blast.log("DB: provisioning of database record", recordId, "never got its turn -", reason);
            }
        });
    }

    /** The body of {@link #provisionInBackground}, run holding the record's content lease. */
    private void provisionStored(int recordId) {
        Row row = query(() -> model().findById(recordId));
        if (row == null) {
            // Destroyed before its turn: loud, never a silent no-op.
            Blast.log("DB: provisioning skipped, database record", recordId,
                "is gone before its container was provisioned");
            return;
        }
        String name = row.get(DatabaseModel.NAME);
        try {
            provisionRuntime(row);
            setStatus(recordId, DatabaseModel.STATUS_ACTIVE, null);
        } catch (Exception | Error e) {
            // TERMINAL and visible: the status is what the list badge, the detail
            // page and DatabaseAttention.failedDatabases read; the reason rides the
            // record so the operator learns WHY without the journal. An Error marks the record failed too (a record
            // left "provisioning" is wedged for good), then propagates: the pool decides what an Error means.
            String reason = setStatus(recordId, DatabaseModel.STATUS_FAILED, HohenheimViolations.reasonOf(e));
            Blast.log("DB: provisioning failed for", name, "-", reason);
            if (e instanceof Error error) {
                throw error;
            }
        }
    }

    /**
     * Boot repair: give each dedicated redis engine whose stored environment names no usable password one before its
     * next start, which its start script refuses without one ({@link ManagedDatabase.Engine#isUsablePassword}).
     *
     * AIDEV-NOTE: no known lane stored such an engine: every create since the first form demanded or generated a
     * password, and every deploy writes the environment from the record. This is the guard behind that claim, for a
     * record or a stored environment that an older controller or a hand edit left without one. A record with a
     * password has its environment written from it again. A record without one gets a generated password: through
     * the rotation when its engine runs (CONFIG SET, authenticating with nothing, which a redis demanding no password
     * takes), stored with its environment when it does not, since a stopped redis holds its password nowhere but its
     * start configuration. Either store is the record's own, one credential generation further, in its history. A
     * repair that fails is stamped as the record's failure reason ({@link CredentialStamp}), which the dashboard raises.
     *
     * @return how many engines were repaired
     */
    public int repairRedisPasswords() {
        int repaired = 0;
        List<Row> redis = query(() -> model().find()
            .where(DatabaseModel.ENGINE.eq(ManagedDatabase.Engine.REDIS.token())).all());
        for (Row row : redis) {
            Integer recordId = row.get(DatabaseModel.ID);
            if (recordId != null && repairRedisPassword(recordId)) {
                repaired++;
            }
        }
        return repaired;
    }

    /**
     * {@link #repairRedisPasswords} for one record; a repair that fails is stamped and logged, never thrown.
     *
     * @return whether the record is a dedicated redis whose served engine instance needed a password and got one
     */
    public boolean repairRedisPassword(int recordId) {
        Row row = query(() -> model().findById(recordId));
        if (row == null || DatabaseModel.isShared(row)
                || !ManagedDatabase.Engine.REDIS.token().equals(row.get(DatabaseModel.ENGINE))) {
            return false;
        }
        Row instance = query(() -> DatabaseInstances.owned(recordId));
        if (instance == null) {
            // Not served yet: its first deploy writes the environment from the record.
            return false;
        }
        int instanceId = instance.get(InstanceModel.ID);
        String stored = query(() -> new InstanceVariables().valuesFor(instanceId))
            .get(ManagedDatabase.Engine.REDIS_PASSWORD);
        if (ManagedDatabase.Engine.isUsablePassword(stored)) {
            return false;
        }
        String name = row.get(DatabaseModel.NAME);
        // Generated before the attempt, so a failure after the engine or the record took it still redacts it.
        String generated = Secrets.generatePassword();
        try {
            repairRedisPassword(recordId, instanceId, generated);
            return true;
        } catch (IOException | RuntimeException failed) {
            ConsoleRedaction.Secrets secrets = query(() -> ConsoleRedaction.secretsOfOwner(DatabaseModel.MODEL_ID,
                recordId)).and(new ConsoleRedaction.Secrets(Set.of(generated), true));
            String reason = CredentialStamp.of("The redis engine of '" + name + "' has no password in its stored"
                + " environment, so it cannot start again, and giving it one failed ("
                + ConsoleRedaction.redactWhole(HohenheimViolations.reasonOf(failed), secrets) + "). Change its"
                + " password once the engine runs, or give the record one by hand");
            try {
                TenantWrites.inAuthorizedOperation(() -> setStatus(recordId,
                    Objects.toString(row.get(DatabaseModel.STATUS), DatabaseModel.STATUS_ACTIVE), reason));
            } catch (RuntimeException unstamped) {
                Blast.log("DB: the failed redis password repair of", name, "could not be stamped either -",
                    ConsoleRedaction.redactWhole(HohenheimViolations.reasonOf(unstamped), secrets));
            }
            Blast.log("DB:", reason);
            return false;
        }
    }

    /**
     * The repair of {@link #repairRedisPassword(int)} for an engine instance whose stored environment names none.
     *
     * @param generated the password the engine gets when the record names no usable one either
     */
    private void repairRedisPassword(int recordId, int instanceId, @NonNull String generated) throws IOException {
        Row row = requireRecord(recordId);
        String name = row.get(DatabaseModel.NAME);
        if (ManagedDatabase.Engine.isUsablePassword(row.get(DatabaseModel.DB_PASSWORD))) {
            underInstanceClaim(instanceId, () -> TenantWrites.inAuthorizedOperation(() -> exec(() -> {
                Row fresh = Objects.requireNonNull(model().findById(recordId), "the database record is gone");
                ExecutionIdentity.runAsSystem(HohenheimSystemPurposes.DATABASE_PROVISION,
                    () -> DatabaseInstances.storeEngineEnvironment(EngineHost.dedicated(fresh)));
            })));
            Blast.log("DB: the redis engine of", name,
                "had no password in its stored environment; it names the record's again");
            return;
        }
        if (query(() -> DatabaseInstances.liveStatus(recordId)).running()) {
            changePassword(recordId, generated);
        } else {
            underInstanceClaim(instanceId, () -> TenantWrites.inAuthorizedOperation(() -> exec(() ->
                model().getResolvedDatasource().withTransaction(transaction -> storeRotated(recordId, false,
                    generated)))));
        }
        Blast.log("DB: the redis engine of", name, "had no password; it has a generated one now");
    }

    /**
     * Run {@code work} holding the operation claim of an engine instance, queueing behind a running deploy or stop; an
     * {@link UncheckedIOException} the work throws is rethrown as its cause.
     */
    private static void underInstanceClaim(int instanceId, @NonNull Runnable work) throws IOException {
        try {
            InstanceOperationLock.production().exclusive(instanceId, InstanceOperationLock.Contention.QUEUE, work);
        } catch (UncheckedIOException failed) {
            throw failed.getCause();
        }
    }

    /**
     * Stamp the provisioning outcome; a reason is stored on failure and cleared otherwise. THE write of a database's
     * failure reason: daemon or engine text can quote its password or its engine's, so it is stored redacted.
     *
     * AIDEV-NOTE: a two-column atomic update, never a load-mutate-save. A failed move stamps this after its credential
     * claim ended, while a password change that queued on that claim may already be storing its password; saving the
     * whole row read here wrote the old password back over the change's.
     *
     * @return the reason as stored (redacted); null for none
     */
    private @Nullable String setStatus(int recordId, String status, @Nullable String failureReason) {
        return query(() -> {
            String stored = ConsoleRedaction.redactedReason(DatabaseModel.MODEL_ID, recordId, failureReason);
            model().find()
                .where(DatabaseModel.ID.eq(recordId))
                .assign(DatabaseModel.STATUS, status)
                .assign(DatabaseModel.FAILURE_REASON, stored)
                .updateAll();
            return stored;
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
        return status != null ? status : DatabaseModel.STATUS_ACTIVE;   // records predating the status column
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
     * Run {@code work} with the record and its host read under the claim a password change of that record holds,
     * when the credential the work authenticates with is the record's own: a DEDICATED record's engine instance claim,
     * queueing behind a running change (or deploy, or restart), so a dump or a restore never authenticates with a
     * password a rotation is replacing.
     *
     * AIDEV-NOTE: a SHARED record's dump and restore authenticate as the engine's root, whose password no record's
     * change touches, so they take no claim: holding the engine's lock for a dump's whole length would stall every
     * tenant's provisioning on that engine. What serializes one record's dumps and restores, shared or dedicated, is
     * the record's own {@link DatabaseContentLock}, which every dump and restore lane takes before this.
     *
     * AIDEV-NOTE: the record read under the claim is first settled ({@link #settlePending}): a password change that
     * drifted left the engine maybe holding the pending password, which the work would otherwise fail against. A
     * shared record with a pending password is settled under its engine's lock, the one its changes hold, and only
     * then works unlocked.
     */
    private <T> T underCredentialClaim(@NonNull Row row, InstanceOperationLock.@NonNull Contention contention,
                                       @NonNull CredentialWork<T> work) throws IOException {
        Integer recordId = row.get(DatabaseModel.ID);
        Row instance = DatabaseModel.isShared(row) || recordId == null ? null
            : query(() -> DatabaseInstances.owned(recordId));
        if (instance == null) {
            // Shared, or not served yet (the handle lookup refuses that by name).
            Integer engineId = row.get(DatabaseModel.ENGINE_ID);
            Row current = recordId == null || engineId == null || row.get(DatabaseModel.PENDING_PASSWORD) == null
                || !DatabaseModel.isShared(row) ? row
                : DatabaseEngines.underLock(engineId, () -> settlePending(requireRecord(recordId)).row());
            return work.run(current, hostOf(current));
        }
        try {
            return InstanceOperationLock.production().exclusive((Integer) instance.get(InstanceModel.ID),
                contention, () -> {
                    try {
                        Row current = settlePending(requireRecord(recordId)).row();
                        return work.run(current, hostOf(current));
                    } catch (IOException failed) {
                        throw new UncheckedIOException(failed);
                    }
                });
        } catch (UncheckedIOException failed) {
            throw failed.getCause();
        }
    }

    /** Work on a record with the host serving it, read under {@link #underCredentialClaim}. */
    @FunctionalInterface
    private interface CredentialWork<T> {
        T run(@NonNull Row row, @NonNull EngineHost host) throws IOException;
    }

    /**
     * Back up a persisted database into {@code directory}, naming the file {@code baseName} plus
     * the engine's dump extension, and return the written path. Handles text (SQL) and binary
     * (RDB / mongodump archive) engines. A shared record dumps ONE logical database out of its
     * engine with the engine's root credentials. Refused at once while another dump or restore of it,
     * or another operation on its engine instance, runs.
     */
    public Path backupToFile(String name, Path directory, String baseName) throws IOException {
        return backupToFile(name, directory, baseName, InstanceOperationLock.Contention.REFUSE);
    }

    /** {@link #backupToFile(String, Path, String)}, meeting a running dump, restore or operation as told. */
    public Path backupToFile(String name, Path directory, String baseName,
                             InstanceOperationLock.@NonNull Contention contention) throws IOException {
        Row row = requireWith(name, HohenheimCapabilities.BACKUPS);
        return DatabaseContentLock.exclusive(row.get(DatabaseModel.ID), contention, () -> underCredentialClaim(row,
            contention, (current, host) -> dumpToFile(current, host, directory, baseName)));
    }

    /**
     * The dump of {@link #backupToFile}, for a caller already holding the record's content lease and
     * {@link #underCredentialClaim}.
     *
     * @throws IllegalStateException when the calling thread does not hold the record's {@link DatabaseContentLock}
     */
    private Path dumpToFile(Row current, EngineHost host, Path directory, String baseName) throws IOException {
        Integer recordId = current.get(DatabaseModel.ID);
        if (recordId == null || !DatabaseContentLock.isHeldHere(recordId)) {
            throw new IllegalStateException("Managed database " + current.get(DatabaseModel.NAME)
                + " is dumped outside its content lease");
        }
        Files.createDirectories(directory);
        Path target = directory.resolve(baseName + "." + host.engine().dumpExtension());
        managedFor(host.serverId()).backupToFile(handleOf(current, host), host.engine(),
            host.rootUser(), host.rootPassword(), current.get(DatabaseModel.DB_NAME), target);
        return target;
    }

    /**
     * A dump ready to stream to the browser: filename, MIME type, the open stream and its
     * exact size in bytes (a long: a dump may exceed 2 GiB).
     *
     * AIDEV-NOTE: the file behind {@code content} is ALREADY UNLINKED when this is handed
     * out -- the open descriptor keeps the bytes readable and the space is freed when the
     * stream closes. That is what lets the HTTP layer serve it after this method returned
     * without a temp file outliving a client that hung up, and without the whole dump in
     * the heap (the old byte[] shape was reachable by any tenant with the backups
     * capability and turned a large database into a controller OOM). Closing it is the
     * caller's job; zenit's ServeStreamResult always does.
     */
    public record BackupStream(@NonNull String filename, @NonNull String contentType,
                               @NonNull InputStream content, long size) implements Closeable {

        @Override
        public void close() throws IOException {
            this.content.close();
        }
    }

    /**
     * Back up a persisted database by name into a downloadable, STREAMED artifact (SQL text
     * or the engine's native binary dump). Nothing of the dump is held in memory.
     *
     * @throws IOException when the dump fails; nothing is left on disk
     */
    public BackupStream backupStream(String name) throws IOException {
        Row row = requireWith(name, HohenheimCapabilities.BACKUPS);
        // AIDEV-TODO: the download still QUEUEs on a request thread; refusing needs its handler to tell a busy claim
        // from the absence it answers 404 for (DatabaseHandlers), which it cannot yet.
        InstanceOperationLock.Contention contention = InstanceOperationLock.Contention.QUEUE;
        return DatabaseContentLock.exclusive(row.get(DatabaseModel.ID), contention, () -> underCredentialClaim(row,
                contention, (current, host) -> {
            Path directory = Files.createTempDirectory("hohenheim-backup");
            Path dump = directory.resolve(name + "." + host.engine().dumpExtension());
            try {
                managedFor(host.serverId()).backupToFile(handleOf(current, host), host.engine(),
                    host.rootUser(), host.rootPassword(), current.get(DatabaseModel.DB_NAME), dump);
                long size = Files.size(dump);
                InputStream content = Files.newInputStream(dump);
                return new BackupStream(dump.getFileName().toString(),
                    host.engine().dumpContentType(), content, size);
            } finally {
                // Unlinked while (on success) still open: see BackupStream.
                Files.deleteIfExists(dump);
                Files.deleteIfExists(directory);
            }
        }));
    }

    /**
     * Restore a dump file (text or binary) into a persisted database by name; refused at once while another dump or
     * restore of it, or another operation on its engine instance, runs.
     */
    public void restoreFromFile(String name, Path source) throws IOException {
        restoreFromFile(name, source, InstanceOperationLock.Contention.REFUSE);
    }

    /**
     * {@link #restoreFromFile(String, Path)}, meeting a running dump, restore or operation as {@code contention} says.
     *
     * AIDEV-NOTE: a SHARED record restores with the engine's root credentials but under
     * the record's own role, so what the dump creates is the tenant's and not root's (a
     * root-owned table is one the tenant's own login cannot read). A dedicated engine's
     * root IS the record's user, so it restores as itself.
     */
    public void restoreFromFile(String name, Path source, InstanceOperationLock.@NonNull Contention contention)
            throws IOException {
        // No `restore` capability exists: an uploaded dump is arbitrary SQL run as the
        // engine superuser and no delegated surface offers it, so this stays an operator
        // act. A tenant-originated call is refused outright rather than silently allowed.
        Row row = requireWith(name, null);
        DatabaseContentLock.exclusive(row.get(DatabaseModel.ID), contention, () -> underCredentialClaim(row,
                contention, (current, host) -> {
            managedFor(host.serverId()).restoreFromFile(handleOf(current, host), host.engine(),
                host.rootUser(), host.rootPassword(), current.get(DatabaseModel.DB_NAME), source,
                host.shared() ? current.get(DatabaseModel.DB_USER) : null);
            return null;
        }));
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
        destroy(name, removeData, UnfinishedDestroys.Policy.AUTOMATIC);
    }

    /**
     * {@link #destroy(String, boolean)}, a dedicated record's engine instance destroyed under {@code policy}: the
     * record's requested Delete is the one way to retry an engine instance whose destroy was left unfinished.
     */
    public void destroy(String name, boolean removeData, UnfinishedDestroys.@NonNull Policy policy)
            throws IOException {
        Row row = query(() -> model().findByName(name));
        if (row != null) {
            destroyRecord(row, removeData, policy);
        }
    }

    /**
     * {@link #destroy(String, boolean)} of the record with this id, for a caller that must remove exactly the record it
     * created and never another holding its name; a record already gone destroys nothing.
     */
    public void destroyById(int recordId, boolean removeData) throws IOException {
        Row row = query(() -> model().findById(recordId));
        if (row != null) {
            destroyRecord(row, removeData, UnfinishedDestroys.Policy.AUTOMATIC);
        }
    }

    /**
     * The teardown of {@link #destroy(String, boolean, UnfinishedDestroys.Policy)}, then the record by its id.
     *
     * AIDEV-NOTE: under the database's content lease, refusing at once ({@code database_busy}) while a dump or restore
     * of it runs: a teardown beside a running load or dump left the record gone and the dump's ledger row or the
     * half-loaded engine behind. Re-entrant, so a restore's own cleanup of the record it created still runs.
     */
    private void destroyRecord(@NonNull Row row, boolean removeData, UnfinishedDestroys.@NonNull Policy policy)
            throws IOException {
        int recordId = row.get(DatabaseModel.ID);
        String name = row.get(DatabaseModel.NAME);
        HohenheimAccess.requireDatabaseCapability(recordId, HohenheimCapabilities.DESTROY);
        DatabaseContentLock.exclusive(recordId, InstanceOperationLock.Contention.REFUSE, () -> {
            try {
                scoped(() -> {
                    if (DatabaseModel.isShared(row)) {
                        // The dedicated engine a move left behind goes with the record, never orphaned by it.
                        finishDedicatedLeftover(row, removeData, policy);
                        dropLogical(row, removeData);
                    } else {
                        DatabaseInstances.destroyFor(row, removeData, policy);
                    }
                    return null;
                });
            } catch (IOException e) {
                // The status stamp is the service's own bookkeeping inside an
                // operation whose destroy gate already ran above; a tenant-originated
                // caller must not see its refusal rewritten as "status is frozen".
                TenantWrites.inAuthorizedOperation(
                    () -> setStatus(recordId, DatabaseModel.STATUS_DESTROY_FAILED, e.getMessage()));
                throw new IOException("Destroy of '" + name + "' could not verify its teardown"
                    + " (record kept): " + e.getMessage(), e);
            }
            exec(() -> model().find().where(DatabaseModel.ID.eq(recordId)).delete());
            return null;
        });
    }

    /**
     * Finishes removing the dedicated engine instance a SHARED database still owns after its move onto a shared engine
     * could not destroy it; the database, its logical data and its user stay. A database without one is left alone.
     *
     * AIDEV-NOTE: the move's own cleanup stays AUTOMATIC (budgeted, UnfinishedDestroys); this is the operator's
     * REQUESTED retry of a held leftover, the database's FINISH_OLD_ENGINE action, so it never goes through Delete,
     * which on a shared database would drop the live logical database to reach the leftover.
     *
     * @throws IOException when the teardown could not be confirmed; the old instance stays
     */
    public void finishOldEngine(@NonNull String name, UnfinishedDestroys.@NonNull Policy policy) throws IOException {
        Row row = query(() -> model().findByName(name));
        if (row == null || !DatabaseModel.isShared(row)) {
            return;
        }
        scoped(() -> {
            finishDedicatedLeftover(row, false, policy);
            return null;
        });
    }

    /** Destroys the dedicated engine instance a shared record still owns, when it owns one, as the move would have. */
    private static void finishDedicatedLeftover(@NonNull Row shared, boolean removeData,
                                                UnfinishedDestroys.@NonNull Policy policy) throws IOException {
        if (DatabaseInstances.ownedBy(EngineHost.dedicated(shared)) != null) {
            DatabaseInstances.destroyFor(shared, removeData, policy);
        }
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
            // Recorded as forced: the delete's own change says the teardown was skipped past a failure.
            model().find().where(DatabaseModel.NAME.eq(name)).delete(Change.fact(HohenheimActivityAction.FORCED));
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
        runMove(claimMove(name));
    }

    /**
     * Take the move for one record, atomically: the eligibility check, then a CONDITIONAL
     * update that flips the record to provisioning only while it is still active and
     * dedicated. Exactly one of two concurrent claims wins.
     *
     * AIDEV-NOTE: the check and the status stamp used to be two separate steps, so a
     * double-submitted panel action (or the API beside it) could start two moves of one
     * record: two dumps, two restores into the same logical database, two switches. The
     * loser now matches no row and is refused before any daemon work.
     *
     * @return the record as it was read before the claim
     * @throws IOException naming the refusal key when the record may not move or another
     *         operation claimed it first; the record is untouched
     */
    private Row claimMove(String name) throws IOException {
        Row row = query(() -> model().findByName(name));
        if (row == null) {
            throw new IOException("No managed database named '" + name + "'");
        }
        int recordId = row.get(DatabaseModel.ID);
        Microcopy refusal = query(() -> moveRefusal(row));
        if (refusal != null) {
            // The KEY, not a re-spelled sentence: this is the last-line guard on a
            // background thread, and the surfaces that face a human (the row action's
            // visibility, the API's typed refusal) render the same key's Microcopy.
            throw new IOException("Database '" + name + "' cannot move onto a shared engine ("
                + refusal.key() + ")");
        }
        query(() -> {
            requireLogicalIdentifiers(row.get(DatabaseModel.DB_USER),
                row.get(DatabaseModel.DB_PASSWORD), row.get(DatabaseModel.DB_NAME));
            return null;
        });
        int claimed = query(() -> model().find()
            .where(DatabaseModel.ID.eq(recordId))
            .where(DatabaseModel.STATUS.eq(DatabaseModel.STATUS_ACTIVE))
            .where(Criteria.or(DatabaseModel.PLACEMENT.isNull(),
                DatabaseModel.PLACEMENT.ne(DatabaseModel.PLACEMENT_SHARED)))
            .assign(DatabaseModel.STATUS, DatabaseModel.STATUS_PROVISIONING)
            .assign(DatabaseModel.FAILURE_REASON, null)
            .updateAll());
        if (claimed != 1) {
            throw new IOException("Database '" + name + "' cannot move onto a shared engine"
                + " (database_not_active): another operation claimed it first");
        }
        return row;
    }

    /** What a move switched between, for the work after its credential claim: the hosts and the dump kept. */
    private record Moved(@NonNull EngineHost old, @NonNull EngineHost target, @NonNull Path dump) {
    }

    /**
     * The move itself, for a record {@link #claimMove} already flipped to provisioning.
     *
     * AIDEV-NOTE: everything that reads the record's password, from the dump through the logical user created with it
     * to the switch that makes the record shared, runs under {@link #underCredentialClaim} with the record re-read
     * inside it: the claim a dedicated record's password change holds. A change running when the move starts finishes
     * first and the move carries its password; a change asked for during the move waits, then finds the record shared
     * and refuses, or (after a failed move) rotates the dedicated engine it still has. The claimed row's password was
     * read before that wait, so it is never used.
     */
    private void runMove(Row row) throws IOException {
        String name = row.get(DatabaseModel.NAME);
        int recordId = row.get(DatabaseModel.ID);
        ManagedDatabase.Engine engine = engineOf(row);
        int serverId = ServerModel.canonicalServerId(row.get(DatabaseModel.SERVER_ID));

        List<Integer> stopped = new ArrayList<>();
        // The shared engine a failed move was landing on: its root password can be quoted before the switch.
        int[] targetEngine = {-1};
        try {
            // 1. Stop the consumers: their injected address is about to change.
            for (Row instance : query(() -> InstanceDatabaseLinks.liveInstances(recordId))) {
                int instanceId = instance.get(InstanceModel.ID);
                exec(() -> new InstanceService().stop(instanceId));
                stopped.add(instanceId);
            }

            // 2-5. Under the content lease and then the credential claim: dump, land on the shared engine, verify,
            //      switch the record. The lease keeps a backup or restore of the record from running beside the move's
            //      dump and load, and refuses the move at once while one runs; the claim queues as a password change's.
            Path backupRoot = Path.of(Zenit.SETTINGS_VALUES.getValue(
                HohenheimSettings.Database.BACKUP_PATH));
            Moved moved = DatabaseContentLock.exclusive(recordId, InstanceOperationLock.Contention.REFUSE,
                () -> underCredentialClaim(row, InstanceOperationLock.Contention.QUEUE, (current, old) -> {
                String storedUser = current.get(DatabaseModel.DB_USER);
                String password = current.get(DatabaseModel.DB_PASSWORD);
                String database = current.get(DatabaseModel.DB_NAME);
                query(() -> {
                    requireLogicalIdentifiers(storedUser, password, database);
                    return null;
                });

                // 2. Dump the dedicated engine; the file is the rollback.
                Path dump = dumpToFile(current, old, backupRoot.resolve("moves").resolve(name), UtcStamp.now());
                String before = fingerprint(old, handleOf(current, old), database);

                // 3. The shared engine, up. Its reservation may exceed the host budget by what
                //    the dedicated container below is about to give back: a full host must not
                //    refuse the engine for the very memory this move frees.
                Row oldInstance = query(() -> DatabaseInstances.ownedBy(old));
                long releasing = oldInstance == null ? 0
                    : InstanceCapacity.bookedOfInstance(oldInstance.get(InstanceModel.ID));
                Row engineRow = InstanceCapacity.withPendingRelease(serverId, releasing,
                    () -> query(() -> DatabaseEngines.findOrCreateShared(serverId, engine,
                        current.get(DatabaseModel.IMAGE))));
                int engineId = engineRow.get(DatabaseEngineModel.ID);
                targetEngine[0] = engineId;
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
                String targetHandle = handleOf(current, target);

                // 4. Logical database + user, restore, verify. A logical database already holding anything is
                //    refused first: the dump carries --clean, so loading it there would replace what is there.
                boolean created = requireLogicalEmpty(target, targetHandle, database);
                try {
                    runLogical(target, targetHandle, engine.createLogicalCommand(target.rootUser(),
                        database, user), engine.logicalEnv(target.rootPassword(), password),
                        "create logical database '" + database + "'");
                    managedFor(serverId).restoreFromFile(targetHandle, engine, target.rootUser(),
                        target.rootPassword(), database, dump, user);
                    String after = fingerprint(target, targetHandle, database);
                    if (!before.equals(after)) {
                        throw new IOException("Content fingerprint differs after the restore into engine '"
                            + target.name() + "' (dedicated: " + before + "; shared: " + after
                            + "); nothing was switched");
                    }
                    requireOwnedBy(target, targetHandle, database, user);

                    // 5. Switch the record; a password change waiting on the claim now finds it shared.
                    exec(() -> {
                        Row fresh = model().findById(recordId);
                        fresh.set(DatabaseModel.PLACEMENT, DatabaseModel.PLACEMENT_SHARED);
                        fresh.set(DatabaseModel.ENGINE_ID, engineId);
                        fresh.set(DatabaseModel.DB_USER, user);
                        fresh.set(DatabaseModel.MEMORY_LIMIT_MB, null);
                        fresh.set(DatabaseModel.CPU_LIMIT, null);
                        fresh.set(DatabaseModel.STATUS, DatabaseModel.STATUS_ACTIVE);
                        fresh.set(DatabaseModel.FAILURE_REASON, null);
                        model().save(fresh);
                    });
                } catch (IOException | RuntimeException | Error failed) {
                    if (created) {
                        dropMoveTarget(target, targetHandle, database, user, failed);
                    }
                    throw failed;
                }
                return new Moved(old, target, dump);
            }));
            EngineHost old = moved.old();
            EngineHost target = moved.target();
            Path dump = moved.dump();

            // 6. The old instance, the networks, the consumers.
            try {
                scoped(() -> {
                    DatabaseInstances.destroyFor(old, false);
                    return null;
                });
            } catch (IOException e) {
                // The switch stands (the shared copy is verified); the old container
                // still runs and still holds its port, memory booking and data. A log
                // line is not visibility: the operator is ALERTED, by name.
                String leftover = query(() -> ConsoleRedaction.reasonOf(DatabaseModel.MODEL_ID, recordId, e));
                Blast.log("DB-MOVE: the old dedicated engine of", name,
                    "could not be destroyed after the move -", leftover);
                Alerts.trySend(NotificationEvents.DATABASE_MOVE_LEFTOVER, Alerts.about(DatabaseModel.MODEL_ID, name),
                    HohenheimMicrocopy.ALERT.of("database_move_leftover_subject")
                        .withArg("name", name),
                    HohenheimMicrocopy.ALERT.of("database_move_leftover_body")
                        .withArg("name", name).withArg("engine", target.name())
                        .withArg("reason", leftover));
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
            String reason = query(() -> ConsoleRedaction.redactWhole(HohenheimViolations.reasonOf(failed),
                ConsoleRedaction.secretsOfOwner(DatabaseModel.MODEL_ID, recordId).and(targetEngine[0] < 0
                    ? ConsoleRedaction.Secrets.NONE
                    : ConsoleRedaction.secretsOfOwner(DatabaseEngineModel.MODEL_ID, targetEngine[0]))));
            Blast.log("DB-MOVE: moving", name, "failed -", reason);
            // The record goes back to ACTIVE before its consumers are redeployed: a deploy
            // resolves the injected credentials off the record and REFUSES a database that
            // is not active (database_not_ready), so the other order left every workload
            // stopped after a refused move -- observed live on 2026-09-02 (invulassistent).
            setStatus(recordId, DatabaseModel.STATUS_ACTIVE, "Move to a shared engine failed: " + reason);
            for (int instanceId : stopped) {
                try {
                    exec(() -> new InstanceService().deploy(instanceId));
                } catch (RuntimeException e) {
                    Blast.log("DB-MOVE: could not restart workload", instanceId, "after the failed move -",
                        query(() -> ConsoleRedaction.reasonOf(InstanceModel.MODEL_ID, instanceId, e)));
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
        return moveRefusals(List.of(row)).get(0);
    }

    /**
     * {@link #moveRefusal} for many records at once (the row action's applicability over a page): their hosts, those
     * hosts' shared engines and the records already on those engines are each read ONCE, never once per row.
     *
     * @return per record, in record order: null where the move would be accepted, else the named reason it is not
     * @throws IllegalArgumentException when a record names a host that does not exist
     */
    public static @NonNull List<@Nullable Microcopy> moveRefusals(@NonNull List<? extends Row> rows) {
        List<Microcopy> refusals = new ArrayList<>(rows.size());
        List<Row> candidates = new ArrayList<>();
        for (Row row : rows) {
            Microcopy own = ownMoveRefusal(row);
            refusals.add(own);
            if (own == null) {
                candidates.add(row);
            }
        }
        if (candidates.isEmpty()) {
            return refusals;
        }
        // The host's shared engine, if one exists yet, must not already hold a logical
        // database of this name: that is another record's data, and the restore would
        // land inside it.
        List<Integer> stored = new ArrayList<>();
        Set<String> tokens = new LinkedHashSet<>();
        for (Row row : candidates) {
            stored.add(row.get(DatabaseModel.SERVER_ID));
            tokens.add(Objects.requireNonNull(ManagedDatabase.Engine.forToken(row.get(DatabaseModel.ENGINE))).token());
        }
        Map<Integer, Integer> hosts = ServerModel.canonicalServerIds(stored);
        Map<String, Row> engines = new HashMap<>();
        for (Row engine : Models.get(DatabaseEngineModel.class).find()
                .where(DatabaseEngineModel.SERVER_ID.in(new LinkedHashSet<>(hosts.values())))
                .where(DatabaseEngineModel.ENGINE.in(tokens))
                .orderBy(DatabaseEngineModel.ID, SortOrder.ASC).all()) {
            engines.putIfAbsent(engine.get(DatabaseEngineModel.SERVER_ID) + "/" + engine.get(DatabaseEngineModel.ENGINE),
                engine);
        }
        Map<Integer, List<Row>> held = KeyedRows.byAsked(Models.get(DatabaseModel.class).find()
                .orderBy(DatabaseModel.ID, SortOrder.ASC), DatabaseModel.ENGINE_ID,
            engines.values().stream().map(engine -> (Integer) engine.get(DatabaseEngineModel.ID)).toList());
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (refusals.get(i) != null) {
                continue;
            }
            String token = Objects.requireNonNull(ManagedDatabase.Engine.forToken(row.get(DatabaseModel.ENGINE)))
                .token();
            Row existing = engines.get(hosts.get((Integer) row.get(DatabaseModel.SERVER_ID)) + "/" + token);
            if (existing == null) {
                continue;
            }
            Row holder = holderAmong(held.getOrDefault((Integer) existing.get(DatabaseEngineModel.ID), List.of()),
                DatabaseModel.DB_NAME, row.get(DatabaseModel.DB_NAME), row.get(DatabaseModel.ID));
            if (holder != null) {
                refusals.set(i, HohenheimMicrocopy.VIOLATIONS.of("database_logical_name_taken")
                    .withArg("database", row.get(DatabaseModel.DB_NAME))
                    .withArg("engine", existing.get(DatabaseEngineModel.NAME))
                    .withArg("record", holder.get(DatabaseModel.NAME)));
            }
        }
        return refusals;
    }

    /** @return why this record cannot move by its own state alone, null when only its host's engine can refuse it */
    private static @Nullable Microcopy ownMoveRefusal(@NonNull Row row) {
        if (DatabaseModel.isShared(row)) {
            return HohenheimMicrocopy.VIOLATIONS.of("database_already_shared")
                .withArg("name", row.get(DatabaseModel.NAME));
        }
        if (!DatabaseModel.STATUS_ACTIVE.equals(row.get(DatabaseModel.STATUS))) {
            return HohenheimMicrocopy.VIOLATIONS.of("database_not_active")
                .withArg("name", row.get(DatabaseModel.NAME))
                .withArg("status", String.valueOf((Object) row.get(DatabaseModel.STATUS)));
        }
        ManagedDatabase.Engine engine =
            ManagedDatabase.Engine.forToken(row.get(DatabaseModel.ENGINE));
        if (engine == null || !engine.supportsLogicalDatabases()) {
            return HohenheimMicrocopy.VIOLATIONS.of("database_placement_unsupported")
                .withArg("engine", String.valueOf((Object) row.get(DatabaseModel.ENGINE)));
        }
        if (Boolean.TRUE.equals(row.get(DatabaseModel.EPHEMERAL))) {
            return HohenheimMicrocopy.VIOLATIONS.of("database_ephemeral_shared");
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
                HohenheimMicrocopy.VIOLATIONS.of("database_logical_name_taken")
                    .withArg("database", database).withArg("engine", engineName)
                    .withArg("record", nameHolder.get(DatabaseModel.NAME)));
        }
        Row userHolder = holderOf(engineId, DatabaseModel.DB_USER, user, excludeRecordId);
        if (userHolder != null) {
            throw Violations.ofField(DatabaseModel.DB_USER.getName(), user,
                HohenheimMicrocopy.VIOLATIONS.of("database_logical_user_taken")
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
        return value == null ? null
            : holderAmong(DatabaseEngines.databasesOn(engineId), field, value, excludeRecordId);
    }

    /** @return the first of these records on one engine, other than the excluded one, holding this value */
    private static @Nullable Row holderAmong(@NonNull List<Row> onEngine, @NonNull StringField field,
                                             @Nullable String value, @Nullable Integer excludeRecordId) {
        if (value == null) {
            return null;
        }
        for (Row other : onEngine) {
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

    /**
     * {@link #moveToSharedEngine} on the provisioning pool, for the panel action and the API.
     * The CLAIM is taken synchronously, so a refused or lost claim reaches the caller.
     *
     * @throws Violations naming why the record cannot move (already shared, not active --
     *         including "another move claimed it first" -- unsupported engine, ...)
     */
    public void moveToSharedEngineInBackground(String name) {
        Row claimed;
        try {
            claimed = claimMove(name);
        } catch (IOException refused) {
            Row fresh = query(() -> model().findByName(name));
            Microcopy reason = fresh == null ? null : query(() -> moveRefusal(fresh));
            throw Violations.ofForm(reason != null ? reason
                : HohenheimMicrocopy.VIOLATIONS.of("database_not_active").withArg("name", name)
                    .withArg("status", DatabaseModel.STATUS_PROVISIONING));
        }
        submit(() -> {
            try {
                runMove(claimed);
            } catch (IOException e) {
                // Already stamped on the record by the move itself.
            } catch (RuntimeException | Error e) {
                // An executor swallows what a task throws; the move already compensated,
                // but a controller-level failure must reach the journal by name.
                Blast.log("DB-MOVE: moving", name, "died with", e.toString());
            }
        });
    }

    /**
     * One database's content fingerprint on one host, trimmed for comparison.
     *
     * AIDEV-NOTE: the long-operation bound: the fingerprint reads every row of every table, twice per move.
     */
    private String fingerprint(EngineHost host, String handle, String database) throws IOException {
        DockerClient.ExecResult result = new ServerService().clientFor(host.serverId()).exec(handle,
            host.engine().fingerprintCommand(host.rootUser(), database),
            host.engine().logicalEnv(host.rootPassword(), null), DockerClient.LONG_OP_TIMEOUT_MS);
        if (result.exitCode() != 0) {
            throw new IOException("Could not fingerprint '" + database + "' on engine '"
                + host.name() + "' (exit " + result.exitCode() + "): " + result.stderr().trim());
        }
        return result.stdout().trim();
    }

    /**
     * Refuses a logical database that already exists on the engine holding any object, so a move never loads over
     * contents of its own; an absent or empty one passes.
     *
     * AIDEV-NOTE: fail CLOSED: a probe that exits non-zero or prints anything but a count or {@code absent} refuses as
     * well. Such a database is what a destroy that kept the data leaves behind under a name no record describes.
     *
     * @return whether the database was absent, so the move that creates it may drop it again when it fails
     * @throws IOException naming the database, the engine and what it holds; nothing was switched
     */
    private boolean requireLogicalEmpty(EngineHost host, String handle, String database) throws IOException {
        DockerClient.ExecResult result = new ServerService().clientFor(host.serverId()).exec(handle,
            host.engine().logicalObjectsCommand(host.rootUser(), database),
            host.engine().logicalEnv(host.rootPassword(), null));
        String answer = result.stdout().trim();
        if (result.exitCode() == 0 && "absent".equals(answer)) {
            return true;
        }
        if (result.exitCode() != 0 || !answer.matches("[0-9]+")) {
            throw new IOException("Could not tell whether logical database '" + database + "' on engine '"
                + host.name() + "' is empty (exit " + result.exitCode() + "): "
                + (result.stderr() + " " + answer).trim() + "; nothing was switched");
        }
        if (!"0".equals(answer.replaceFirst("^0+(?=.)", ""))) {
            throw new IOException("Logical database '" + database + "' already exists on engine '" + host.name()
                + "' holding " + answer + " objects; a move never loads over existing contents, so nothing was"
                + " switched (remove or rename that database on the engine first)");
        }
        return false;
    }

    /**
     * Drops the logical database and user a failed move created on its target engine, so a retry finds it absent.
     *
     * AIDEV-NOTE: only for a database the move's own probe found ABSENT: one that already existed (empty) is never
     * dropped, whatever the failed load left in it. A drop that fails is logged and suppressed onto the move's own
     * failure, which stays the one thrown; the record is still dedicated, so the leftover blocks only a retry.
     */
    private void dropMoveTarget(EngineHost target, String handle, String database, String user, Throwable failed) {
        try {
            runLogical(target, handle, target.engine().dropLogicalCommand(target.rootUser(), database, user, true),
                target.engine().logicalEnv(target.rootPassword(), null),
                "drop logical database '" + database + "' the failed move created");
        } catch (IOException | RuntimeException dropFailed) {
            failed.addSuppressed(dropFailed);
            Blast.log("DB-MOVE: could not drop the logical database", database, "the failed move created on engine",
                target.name(), "-", query(() -> ConsoleRedaction.reasonOf(target.ownerModel(), target.ownerId(),
                    dropFailed)));
        }
    }

    /**
     * Refuse a restored database any of whose objects another role owns (Postgres) or
     * another user defines (MySQL views, routines, triggers, events).
     *
     * AIDEV-NOTE: the content fingerprint compares ROWS and is blind to who owns them, so a
     * restore that handed every table to another record's role (the renamed-user case)
     * fingerprinted equal and the move switched. A MySQL definer naming another user runs
     * with THAT user's privileges, which on a shared engine is another tenant's database.
     *
     * @throws IOException naming the count; nothing was switched
     */
    private void requireOwnedBy(EngineHost host, String handle, String database, String user)
            throws IOException {
        List<String> command = host.engine().foreignOwnershipCommand(host.rootUser(), database, user);
        if (command == null) {
            return;
        }
        DockerClient.ExecResult result = new ServerService().clientFor(host.serverId()).exec(handle, command,
            host.engine().logicalEnv(host.rootPassword(), null));
        String answer = result.stdout().trim();
        if (result.exitCode() != 0 || !"0".equals(answer)) {
            throw new IOException("Ownership check failed after the restore into engine '"
                + host.name() + "': " + (result.exitCode() != 0
                    ? "exit " + result.exitCode() + ": " + result.stderr().trim()
                    : (answer.isEmpty() ? "role '" + user + "' does not exist"
                        : answer + " object(s) of '" + database + "' belong to a role other than '"
                            + user + "'"))
                + "; nothing was switched");
        }
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
                throw HohenheimViolations.databaseNotPermitted();
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

    private Row requireRecord(int recordId) throws IOException {
        Row row = query(() -> model().findById(recordId));
        if (row == null) {
            throw new IOException("No managed database with id " + recordId);
        }
        return row;
    }

    private static ManagedDatabase.Engine engineOf(Row row) {
        return ManagedDatabase.engineOf(row);
    }
}
