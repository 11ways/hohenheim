package be.elevenways.hohenheim.server.database;

import be.elevenways.hohenheim.model.DatabaseEngineModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.InstanceVariables;
import be.elevenways.hohenheim.server.instance.OwnedInstances;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.server.runtime.InstanceStatus;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The wiring between the managed-DATABASE tier and the canonical runtime-resource
 * contract: every engine PROCESS ({@link EngineHost}: a dedicated database record's own
 * container, or a shared {@link DatabaseEngineModel}) IS an owned
 * {@code hohenheim:database_container} instance, deployed and destroyed through
 * {@link InstanceService}. The product records keep their halves -- name, credentials,
 * engine vocabulary, backups, restore, attachments -- and never talk to the daemon
 * about their own container.
 *
 * What the tier GAINED by lowering, none of which it had before: the fenced outcome
 * write (a stale controller's provision cannot stick), the host lease, the port ledger's
 * record-after claim under the INSTANCE owner with its {@code releasing} park, host
 * capacity booking (charge == cap), the reconciler's instance classification, the
 * kernel-verified isolation sweep's instance lane, and the verified-destroy discipline
 * with its named refusals.
 *
 * AIDEV-NOTE: entries here run inside {@code GeneratedRows.as(...)} for the same reason
 * SiteInstances does -- the database tier's own authority gate (the admin resource)
 * already judged the record write, and the runtime convergence that follows is system
 * work. Without the scope the instance-tier tenant gates would judge a system
 * consequence as a tenant write.
 *
 * AIDEV-NOTE: engine PASSWORDS never enter {@code instances.settings}. They ride the
 * instance-variable SECRET lane ({@link InstanceVariableModel#SECRET_VALUE}, the
 * statically declared encrypted column) and merge into the container environment at
 * deploy through {@code InstanceVariables.applyToSettings}. The pre-lowering shape put
 * the password straight into the container spec at provision time; putting it into the
 * settings JSON instead would have been a second PLAINTEXT copy of a credential the
 * database record itself stores encrypted.
 *
 * AIDEV-NOTE (2026-09-02): the database-id entry points ({@link #owned(int)},
 * {@link #handleOf(int)}, {@link #liveStatus(int)}) answer for the instance SERVING that
 * database: its own for a dedicated record, the shared engine's for a shared one. That
 * is the one resolution every consumer (injection, link networks, port refresh, the
 * admin list) needs, and none of them branch on the placement themselves.
 */
public final class DatabaseInstances {

    /** The GeneratedRows source token, and the Accountability origin of every write here. */
    public static final String SOURCE = "database";

    private DatabaseInstances() {
    }

    /** Install the shared owned-instance funnel (MODULES stage); idempotent. */
    public static void install() {
        OwnedInstances.install();
    }

    // -- lookups --------------------------------------------------------------

    /** The engine instance owned by one host record, or null before it has one. */
    public static @Nullable Row ownedBy(@NonNull EngineHost host) {
        return OwnedInstances.soleOwnedBy(host.ownerModel(), host.ownerId());
    }

    /**
     * The instance SERVING a managed database (its own, or its shared engine's), or null
     * when there is none yet.
     */
    public static @Nullable Row owned(int databaseId) {
        Row database = Models.get(DatabaseModel.class).findById(databaseId);
        return database == null ? null : ownedBy(EngineHost.serving(database));
    }

    /**
     * THE container handle serving a managed database, and (via Docker's embedded DNS
     * on any shared user-defined network) the hostname a joined container reaches it
     * under.
     *
     * @return null when nothing serves the database yet -- callers must treat that as
     *         "no engine to reach", never as a name to guess
     */
    public static @Nullable String handleOf(int databaseId) {
        return handleOf(owned(databaseId));
    }

    /** {@link #handleOf(int)} for a host record. */
    public static @Nullable String handleOf(@NonNull EngineHost host) {
        return handleOf(ownedBy(host));
    }

    private static @Nullable String handleOf(@Nullable Row instance) {
        return instance == null ? null
            : ControllerScope.handle(ControllerScope.KIND_INSTANCE,
                instance.get(InstanceModel.ID));
    }

    /** Live state of the instance serving a database; never throws (see {@link ContainerState}). */
    public static ManagedDatabase.@NonNull LiveStatus liveStatus(int databaseId) {
        return liveStatus(owned(databaseId));
    }

    /** {@link #liveStatus(int)} for a host record. */
    public static ManagedDatabase.@NonNull LiveStatus liveStatus(@NonNull EngineHost host) {
        return liveStatus(ownedBy(host));
    }

    private static ManagedDatabase.@NonNull LiveStatus liveStatus(@Nullable Row instance) {
        if (instance == null) {
            return new ManagedDatabase.LiveStatus(ContainerState.ABSENT, null);
        }
        try {
            InstanceStatus status = new InstanceService()
                .liveStatus(instance.get(InstanceModel.ID));
            return new ManagedDatabase.LiveStatus(status.state(), status.publishedPort(),
                status.liveness());
        } catch (RuntimeException unresolvable) {
            // An unaskable host (unknown server, untrusted SSH pin) is UNREACHABLE, never
            // "gone": absent and unreachable stay distinct identities.
            return new ManagedDatabase.LiveStatus(ContainerState.UNREACHABLE, null);
        }
    }

    // -- convergence ----------------------------------------------------------

    /**
     * Converge the host's owned engine instance and deploy it, retiring a dedicated
     * record's pre-lowering {@code hohenheim-{token}-db-{name}} container once.
     *
     * @return the published loopback host port the daemon handed the engine
     * @throws IOException when the deploy is refused (quota, capacity, fence, daemon) or
     *         the engine never reports ready; the caller flips the record to failed
     */
    public static int deploy(@NonNull EngineHost host) throws IOException {
        String serverName = ServerModel.nameOf(host.serverId());
        try {
            int instanceId = reserveEngineRow(host);

            // Secrets AFTER the row exists (they key on its id) and BEFORE the deploy
            // reads them: applyToSettings merges them into the container environment.
            writeEngineSecrets(instanceId, host.engine(), host.rootUser(), host.rootPassword());

            DockerClient docker = new ServerService().clientFor(serverName);
            if (!host.shared()) {
                retireLegacyContainer(docker, host.name(), host.ownerId());
            }

            InstanceStatus status = OwnedInstances.inScope(SOURCE, host.ownerModel(),
                host.ownerId(), () -> new InstanceService().deploy(instanceId));
            Integer port = status.publishedPort();
            if (port == null) {
                throw new IOException("Database engine '" + host.name() + "' deployed but the"
                    + " daemon reports no published host port for it");
            }
            // The engine READINESS gate stays a product-tier concern, exactly like the
            // site tier's HTTP health probe stays in SiteReleases: the instance contract
            // answers "is the workload running", never "can this engine serve queries".
            ManagedDatabase.awaitReady(docker,
                ControllerScope.handle(ControllerScope.KIND_INSTANCE, instanceId),
                host.engine(), host.rootUser(), host.rootPassword(), host.initDatabase(),
                60_000);
            return port;
        } catch (IOException failed) {
            throw failed;
        } catch (Violations refused) {
            throw new IOException("Database engine '" + host.name() + "' could not be deployed: "
                + refused.getMessage(), refused);
        } catch (RuntimeException | Error unchecked) {
            throw unchecked;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    /** {@link #deploy(EngineHost)} for a DEDICATED database record with these ceilings. */
    public static int deploy(@NonNull Row database, @NonNull ResourceLimits limits)
            throws IOException {
        return deploy(dedicatedWith(database, limits));
    }

    /**
     * Converge the host's owned engine INSTANCE ROW, and nothing else: no daemon call,
     * no image pull, no readiness wait. This is the half that is TRANSACTIONAL and can be
     * refused -- the owner's instance quota and the host's memory booking both charge on
     * this write -- which is why the allocation funnels run it INLINE while the
     * container work stays in the background.
     *
     * AIDEV-NOTE: splitting it out fixed a silent-success shape on the admin path too.
     * createAsync used to answer "created" and then discover the refusal on a pool thread,
     * so an operator whose host was out of memory got a record that quietly flipped to
     * FAILED instead of a named refusal on the form they were looking at.
     *
     * @return the id of the engine instance row
     * @throws Violations quota, capacity, fence or attribution refusals, unwrapped, so the
     *         funnel can render them on the field they belong to
     */
    public static int reserveEngineRow(@NonNull EngineHost host) throws Exception {
        return OwnedInstances.inScope(SOURCE, host.ownerModel(), host.ownerId(), () -> {
            Row instance = ownedBy(host);
            if (instance == null) {
                instance = Models.get(InstanceModel.class).createEmptyRow();
            }
            instance.set(InstanceModel.NAME, host.instanceName());
            instance.set(InstanceModel.KIND, DatabaseContainerKind.ID.toString());
            instance.set(InstanceModel.SERVER_ID, host.serverId());
            // A managed database that dies unobserved must come back: the workloads
            // attached to it are down until it does, and there is no operator "stop"
            // story where keeping a crashed engine down is the wanted outcome.
            instance.set(InstanceModel.CRASH_POLICY, InstanceModel.CRASH_RESTART);
            instance.set(InstanceModel.SETTINGS, desiredSettings(host));
            Models.get(InstanceModel.class).save(instance);
            return (int) (Integer) instance.get(InstanceModel.ID);
        });
    }

    /**
     * {@link #reserveEngineRow(EngineHost)} for a DEDICATED database record with these
     * ceilings (the resize lane reserves BEFORE it writes the new ceilings to the row).
     *
     * @throws IllegalStateException for a shared record: it owns no engine row, its
     *         engine's ceilings are resized on the engine
     */
    public static int reserveEngineRow(@NonNull Row database, @NonNull ResourceLimits limits)
            throws Exception {
        return reserveEngineRow(dedicatedWith(database, limits));
    }

    private static @NonNull EngineHost dedicatedWith(@NonNull Row database,
                                                     @NonNull ResourceLimits limits) {
        if (DatabaseModel.isShared(database)) {
            throw new IllegalStateException("Managed database '" + database.get(DatabaseModel.NAME)
                + "' is a logical database on a shared engine and owns no engine instance");
        }
        EngineHost own = EngineHost.dedicated(database);
        return new EngineHost(own.ownerModel(), own.ownerId(), own.name(), own.engine(),
            own.image(), own.ephemeral(), own.rootUser(), own.rootPassword(), own.initDatabase(),
            own.serverId(), limits, false);
    }

    /**
     * Verified end of life of a host's engine instance, called explicitly by the owning
     * tier's destroy (the GameDomains.deleteForInstance shape: the instance soft-deletes,
     * so nothing else would ever clean it up). The container is removed or observed
     * absent, the ledger claims are released fully, and the data volume follows when
     * asked -- also verified.
     *
     * @throws IOException when the daemon cannot confirm a teardown; the caller KEEPS the
     *         record (it holds the only copy of the credentials) and retries
     */
    public static void destroyFor(@NonNull EngineHost host, boolean removeData)
            throws IOException {
        String serverName = ServerModel.nameOf(host.serverId());
        Row instance = ownedBy(host);
        if (instance != null) {
            int instanceId = instance.get(InstanceModel.ID);
            try {
                OwnedInstances.inScope(SOURCE, host.ownerModel(), host.ownerId(), () -> {
                    new InstanceService().destroy(instanceId);
                    return null;
                });
            } catch (Violations refused) {
                throw new IOException(refused.getMessage(), refused);
            } catch (RuntimeException | Error unchecked) {
                throw unchecked;
            } catch (Exception e) {
                throw new IOException(e);
            }
        }

        DockerClient docker = new ServerService().clientFor(serverName);
        if (!host.shared()) {
            // The pre-lowering container, if this database never re-deployed after the
            // lowering: removed only when the daemon still attributes it to this record.
            retireLegacyContainer(docker, host.name(), host.ownerId());
        }
        if (removeData && !host.ephemeral()) {
            try {
                docker.removeVolume(host.dataVolume(), true);
            } catch (DockerClient.ApiException e) {
                if (!e.isNotFound()) {
                    throw e;   // refused: the data is NOT gone, so neither is the record
                }
            }
        }
    }

    /** {@link #destroyFor(EngineHost, boolean)} for a DEDICATED database record. */
    public static void destroyFor(@NonNull Row database, boolean removeData) throws IOException {
        destroyFor(EngineHost.dedicated(database), removeData);
    }

    /**
     * Abandon the owned engine instance WITHOUT touching the daemon: the operator's
     * force-destroy decision. The row is SOFT-deleted through save() so the capacity and
     * quota releases riding the {@code deleted_at} transition fire, and the port claim is
     * PARKED rather than deleted -- a container nobody could confirm may still hold it.
     */
    public static void abandonInstance(@NonNull EngineHost host) {
        Row instance = ownedBy(host);
        if (instance == null) {
            return;
        }
        int instanceId = instance.get(InstanceModel.ID);
        OwnedInstances.inScopeUnchecked(SOURCE, host.ownerModel(), host.ownerId(), () -> {
            PortLedger.releaseOwner(InstanceModel.MODEL_ID, instanceId);
            Row row = Models.get(InstanceModel.class).findById(instanceId);
            if (row != null) {
                row.set(InstanceModel.DELETED_AT, java.time.Instant.now());
                Models.get(InstanceModel.class).save(row);
            }
        });
        Blast.log("DB-RUNTIME: abandoned the engine instance of", host.ownerModel(),
            host.ownerId(), "- its container may survive on the host and will surface in"
            + " the reconciler");
    }

    /** {@link #abandonInstance(EngineHost)} for a DEDICATED database record by id. */
    public static void abandonInstance(int databaseId) {
        Row database = Models.get(DatabaseModel.class).findById(databaseId);
        if (database != null && !DatabaseModel.isShared(database)) {
            abandonInstance(EngineHost.dedicated(database));
        }
    }

    // -- the pre-lowering shape ------------------------------------------------

    /**
     * Retire the pre-lowering container exactly once: the name-keyed
     * {@code hohenheim-{token}-db-{name}} container is removed if the daemon still
     * attributes it to this database record, and the record's own ledger claims are
     * released -- observed when the removal was verified, parked otherwise.
     *
     * AIDEV-NOTE: the SiteInstances.retireLegacyContainer shape, including its refusal
     * discipline: a same-named container the daemon does NOT attribute to this record is
     * never force-removed, it surfaces through the reconciler as an explicit operator
     * decision. The DATA VOLUME is deliberately untouched -- it is exactly what the
     * lowered instance re-mounts, which is what makes this migration non-destructive.
     */
    private static void retireLegacyContainer(@NonNull DockerClient docker,
                                              @NonNull String name, int recordId) {
        try {
            boolean removed = OwnerLabels.removeIfOwnedBy(docker,
                ControllerScope.handle(ControllerScope.KIND_DB, name),
                DatabaseModel.MODEL_ID, recordId);
            if (removed) {
                PortLedger.releaseOwnerObserved(DatabaseModel.MODEL_ID, recordId);
                Blast.log("DB-RUNTIME: retired the pre-lowering container of database", name);
            } else if (!PortLedger.claimsOf(DatabaseModel.MODEL_ID, recordId).isEmpty()) {
                // Claims without a container: unverifiable, park them for the reconciler.
                PortLedger.releaseOwner(DatabaseModel.MODEL_ID, recordId);
            }
        } catch (IOException e) {
            // A foreign same-named container or an unreachable daemon: the deploy that
            // follows fails loudly on its own collision/daemon checks.
            Blast.log("DB-RUNTIME: could not retire the legacy container of database", name,
                "-", e.getMessage());
        }
    }

    /**
     * The documented migration of pre-lowering databases (instance-tier-plan Phase 7,
     * binding property "no data migration may lose a running workload"): every DEDICATED
     * database record that owns no instance yet gets one, and any record whose engine
     * container still exists at the daemon is re-deployed under the contract -- onto the
     * SAME data volume, so the engine comes back on the same bytes.
     *
     * Idempotent and safe to run on every boot: a database that already owns an instance
     * is skipped entirely, and a host that cannot answer leaves the record for the next
     * pass rather than abandoning a running container.
     *
     * @return how many databases were adopted in this pass
     */
    public static int adoptExisting() {
        int adopted = 0;
        for (Row database : Models.get(DatabaseModel.class).find().all()) {
            Integer recordId = database.get(DatabaseModel.ID);
            String name = database.get(DatabaseModel.NAME);
            if (recordId == null || name == null || DatabaseModel.isShared(database)
                    || ownedBy(EngineHost.dedicated(database)) != null) {
                continue;
            }
            try {
                deploy(database, ResourceLimits.of(database.get(DatabaseModel.MEMORY_LIMIT_MB),
                    database.get(DatabaseModel.CPU_LIMIT)));
                adopted++;
                Blast.log("DB-RUNTIME: adopted database", name,
                    "onto the instance runtime contract");
            } catch (Exception e) {
                Blast.log("DB-RUNTIME: could not adopt database", name,
                    "- it keeps its pre-lowering container and will be retried:",
                    e.getMessage());
            }
        }
        return adopted;
    }

    // -- settings --------------------------------------------------------------

    /**
     * THE data volume of a DEDICATED managed database: keyed to the record's NAME, so it
     * survives any particular instance row and so a pre-lowering database's existing
     * volume is the one the lowered instance mounts.
     */
    public static @NonNull String dataVolumeOf(@NonNull String databaseName) {
        return ControllerScope.handle(ControllerScope.KIND_DB, databaseName) + "-data";
    }

    /** Map the host onto the database_container kind settings. */
    private static @NonNull Map<String, Object> desiredSettings(@NonNull EngineHost host) {
        ManagedDatabase.Engine engine = host.engine();
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("engine", engine.token());
        settings.put("image", host.resolvedImage());
        settings.put("ephemeral", host.ephemeral());
        settings.put("shared", host.shared());
        settings.put("data_volume", host.ephemeral() ? "" : host.dataVolume());
        settings.put("environment_variables", engine.env(host.rootUser(), host.initDatabase()));
        String command = engine.containerCommandTemplate();
        if (command != null) {
            settings.put("command", command);
        }
        if (host.limits().memoryMb() != null) {
            settings.put("memory_limit_mb", host.limits().memoryMb());
        }
        if (host.limits().cpus() != null) {
            settings.put("cpu_limit", host.limits().cpus());
        }
        return settings;
    }

    /**
     * Write the engine's password-bearing variables onto the instance through the SECRET
     * lane, replacing any previous values, and remove secret rows the engine no longer
     * declares (an engine change must not leave a stale credential behind).
     */
    private static void writeEngineSecrets(int instanceId, ManagedDatabase.@NonNull Engine engine,
                                           @NonNull String rootUser, @Nullable String password) {
        Map<String, String> secrets = engine.secretEnv(rootUser, password == null ? "" : password);
        InstanceVariables variables = new InstanceVariables();
        for (Map.Entry<String, String> secret : secrets.entrySet()) {
            variables.setValue(instanceId, null, secret.getKey(),
                InstanceVariableModel.KIND_SECRET, secret.getValue());
        }
        List<Row> existing = Models.get(InstanceVariableModel.class).findByInstanceId(instanceId);
        for (Row row : existing) {
            String key = row.get(InstanceVariableModel.KEY);
            if (key != null && !secrets.containsKey(key)) {
                variables.removeValue(instanceId, null, key);
            }
        }
    }

    /** The owner identity of a host, for callers that name it in a log or a refusal. */
    static @NonNull String describe(@NonNull EngineHost host) {
        Identifier model = host.ownerModel();
        return (host.shared() ? "engine '" : "database '") + host.name() + "' (" + model + " #"
            + host.ownerId() + ")";
    }
}
