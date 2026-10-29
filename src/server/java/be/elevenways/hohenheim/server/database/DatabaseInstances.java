package be.elevenways.hohenheim.server.database;

import be.elevenways.hohenheim.model.DatabaseEngineModel;
import be.elevenways.hohenheim.HohenheimActivityAction;
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
import be.elevenways.hohenheim.instance.InstanceKindFields;
import be.elevenways.hohenheim.server.instance.InstanceOperationLock;
import be.elevenways.hohenheim.server.instance.PlaintextEnvironments;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.InstanceVariables;
import be.elevenways.hohenheim.server.instance.OwnedInstances;
import be.elevenways.hohenheim.server.instance.UnfinishedDestroys;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * AIDEV-NOTE: the engine's environment never enters {@code instances.settings}: not the
 * PASSWORDS, and since 2026-09-24 not the root user or init database either. It rides the
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
     * Which SHARED databases still own the dedicated engine instance a move left being destroyed and then held
     * (UnfinishedDestroys): the leftover their FINISH_OLD_ENGINE action retries. One grouped read for all.
     *
     * @return per database, in order
     */
    public static @NonNull List<Boolean> holdOldEngines(@NonNull List<? extends Row> databases) {
        List<Integer> shared = new ArrayList<>();
        for (Row database : databases) {
            Integer id = database.get(DatabaseModel.ID);
            if (id != null && DatabaseModel.isShared(database)) {
                shared.add(id);
            }
        }
        Map<Integer, Row> owned = shared.isEmpty() ? Map.of()
            : OwnedInstances.soleOwnedAmong(DatabaseModel.MODEL_ID, shared);
        List<Boolean> held = new ArrayList<>(databases.size());
        for (Row database : databases) {
            Row instance = DatabaseModel.isShared(database) ? owned.get(database.get(DatabaseModel.ID)) : null;
            held.add(instance != null && InstanceModel.isTerminating(instance)
                && InstanceModel.stoppageCauseOf(instance) == HohenheimActivityAction.DESTROY_UNFINISHED);
        }
        return held;
    }

    /**
     * The instance SERVING a managed database (its own, or its shared engine's), or null
     * when there is none yet or its placement does not resolve ({@link EngineHost#placementsOf}).
     *
     * AIDEV-NOTE: this never throws, where {@link EngineHost#serving} does for a binding that
     * cannot resolve (an unknown engine token, or a shared record whose engine row is missing):
     * such a record is answered "no engine", and so are its {@link #handleOf(int)},
     * {@link #liveStatus(int)}, DatabaseLinkNetworks.refreshDatabasePort and
     * DatabaseEnvInjection, which read through here.
     */
    public static @Nullable Row owned(int databaseId) {
        Row database = Models.get(DatabaseModel.class).findById(databaseId);
        return database == null ? null : ownedAmong(List.of(database)).get(databaseId);
    }

    /**
     * {@link #owned(int)} for a page of database records at once: the instance serving each (its own for a dedicated
     * record, its shared engine's for a shared one) -- what a list asking every row's verdict reads, never a lookup
     * per row.
     *
     * @return database id to its serving instance; a database nothing serves yet is absent
     */
    public static @NonNull Map<Integer, Row> ownedAmong(@NonNull List<? extends Row> databases) {
        return servingAmong(EngineHost.placementsOf(databases)).instances();
    }

    /**
     * The instances serving placed host records, with the hosts that judged them.
     *
     * @param instances key to its serving instance; a key whose placement does not resolve, or whose owner owns no
     *                  instance, is absent
     * @param servers   stored host id (null: the local daemon) to its server row, for the placements' hosts and the
     *                  serving instances' own
     */
    public record Serving(@NonNull Map<Integer, Row> instances, @NonNull Map<@Nullable Integer, Row> servers) {

        /** @return the host row an engine instance runs on, null when it names no server */
        public @Nullable Row hostOf(@NonNull Row instance) {
            return this.servers.get(instance.get(InstanceModel.SERVER_ID));
        }
    }

    /**
     * THE resolution behind {@link #ownedAmong} and the database verdicts: the newest live instance each placement's
     * owner owns (one grouped read per owner model), then ONE read of the servers for both the placements' stored
     * hosts and those instances' own hosts; a placement that does not resolve ({@link
     * EngineHost.Placement#resolves}) keeps no instance.
     *
     * @param placements a key (a database or engine id) to its host's placement
     */
    public static @NonNull Serving servingAmong(@NonNull Map<Integer, EngineHost.Placement> placements) {
        Map<Identifier, Set<Integer>> byModel = new LinkedHashMap<>();
        for (EngineHost.Placement placement : placements.values()) {
            byModel.computeIfAbsent(placement.owner().model(), model -> new LinkedHashSet<>())
                .add(placement.owner().id());
        }
        Map<Identifier, Map<Integer, Row>> owned = new HashMap<>();
        byModel.forEach((model, ids) -> owned.put(model, OwnedInstances.soleOwnedAmong(model, ids)));
        Map<Integer, Row> candidates = new HashMap<>();
        List<Integer> serverIds = new ArrayList<>();
        placements.forEach((key, placement) -> {
            serverIds.add(placement.serverId());
            Row instance = owned.get(placement.owner().model()).get(placement.owner().id());
            if (instance != null) {
                candidates.put(key, instance);
                serverIds.add(instance.get(InstanceModel.SERVER_ID));
            }
        });
        Map<Integer, Row> servers = serverIds.isEmpty() ? new HashMap<>() : ServerModel.serversByStoredId(serverIds);
        Map<Integer, Row> instances = new HashMap<>();
        candidates.forEach((key, instance) -> {
            if (placements.get(key).resolves(servers)) {
                instances.put(key, instance);
            }
        });
        return new Serving(instances, servers);
    }

    /**
     * THE container handle serving a managed database, and (via Docker's embedded DNS
     * on any shared user-defined network) the hostname a joined container reaches it
     * under.
     *
     * @return null when nothing serves the database yet, and (unlike {@link EngineHost#serving},
     *         which throws) for a binding that cannot resolve too (an unknown engine token, a missing
     *         engine row; see {@link #owned(int)}) -- callers must treat that as "no engine to reach",
     *         never as a name to guess
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
            writeEngineEnvironment(instanceId, host);

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
            InstanceModel.saveConfiguration(instance);
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
        destroyFor(host, removeData, UnfinishedDestroys.Policy.AUTOMATIC);
    }

    /**
     * {@link #destroyFor(EngineHost, boolean)}, the engine instance destroyed under {@code policy}: the owner's
     * requested Delete is the one way to retry an engine instance whose destroy was left unfinished.
     */
    public static void destroyFor(@NonNull EngineHost host, boolean removeData,
                                  UnfinishedDestroys.@NonNull Policy policy) throws IOException {
        destroyFor(host, removeData, policy, () -> {});
    }

    /**
     * {@link #destroyFor(EngineHost, boolean, UnfinishedDestroys.Policy)} with a test seam: {@code beforeClaim} runs
     * between reading the owned instance and claiming it, the window another finish of its destroy can end it in.
     */
    public static void destroyFor(@NonNull EngineHost host, boolean removeData,
                                  UnfinishedDestroys.@NonNull Policy policy, @NonNull Runnable beforeClaim)
            throws IOException {
        String serverName = ServerModel.nameOf(host.serverId());
        Row instance = ownedBy(host);
        if (instance != null) {
            int instanceId = instance.get(InstanceModel.ID);
            beforeClaim.run();
            try {
                OwnedInstances.inScope(SOURCE, host.ownerModel(), host.ownerId(), () -> {
                    new InstanceService().destroy(instanceId, policy);
                    return null;
                });
            } catch (Violations refused) {
                // AIDEV-NOTE: an automatic finish of the same destroy (a move's cleanup) can end the instance between
                // the read above and this claim, which then refuses it as missing though the teardown asked for is
                // done. Only that refusal of an instance a fresh read finds no longer live counts as done.
                if (!InstanceOperationLock.refusedAsEnded(refused, instanceId)) {
                    throw new IOException(refused.getMessage(), refused);
                }
                Blast.log("DB: engine instance", instanceId, "of", host.name(),
                    "was ended by another finish of its destroy first");
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

    /** {@link #destroyFor(EngineHost, boolean, UnfinishedDestroys.Policy)} for a DEDICATED database record. */
    public static void destroyFor(@NonNull Row database, boolean removeData,
                                  UnfinishedDestroys.@NonNull Policy policy) throws IOException {
        destroyFor(EngineHost.dedicated(database), removeData, policy);
    }

    /**
     * Abandon the owned engine instance WITHOUT touching the daemon: the operator's
     * force-destroy decision. The row is SOFT-deleted through the model's delete (its
     * SoftDeleteBehaviour stamps deleted_at through save()) so the capacity and quota
     * releases riding the {@code deleted_at} transition fire, and the port claim is
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
            Models.get(InstanceModel.class).delete(instanceId);
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
     * The documented migration of pre-lowering databases (binding
     * property "no data migration may lose a running workload"): every DEDICATED
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
        // First the credential backfill: pure database work, and it reaches the engines
        // nobody redeploys (a deploy below writes its own rows sealed anyway).
        sealPlaintextEnvironments();
        int adopted = 0;
        for (Row database : Models.get(DatabaseModel.class).find().all()) {
            Integer recordId = database.get(DatabaseModel.ID);
            String name = database.get(DatabaseModel.NAME);
            // A provisioning record is its provisioning job's (a boot settle hands a stopped controller's one over
            // again): deploying it here as well ran two deploys of one record, the adoption's without its lease.
            if (recordId == null || name == null || DatabaseModel.isShared(database)
                    || DatabaseModel.STATUS_PROVISIONING.equals(database.get(DatabaseModel.STATUS))
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

    /**
     * Map the host onto the database_container kind settings.
     *
     * AIDEV-NOTE: package-visible so {@code DatabaseService.memoryCeilingOf} can price a
     * record through the SAME settings the deploy writes. Asking the kind handler what it
     * would book for these settings is the only answer that cannot drift from the booking.
     */
    static @NonNull Map<String, Object> desiredSettings(@NonNull EngineHost host) {
        ManagedDatabase.Engine engine = host.engine();
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("engine", engine.token());
        settings.put(InstanceKindFields.IMAGE, host.resolvedImage());
        settings.put("ephemeral", host.ephemeral());
        settings.put("shared", host.shared());
        settings.put("data_volume", host.ephemeral() ? "" : host.dataVolume());
        // No environment_variables: the engine's WHOLE environment (the root user and the
        // init database as well as the passwords) rides the secret lane, see
        // writeEngineEnvironment. No command either: the kind takes the engine's own
        // (ManagedDatabase.Engine.containerCommand), which no setting may carry a secret into.
        if (host.limits().memoryMb() != null) {
            settings.put(InstanceKindFields.MEMORY_LIMIT_MB, host.limits().memoryMb());
        }
        if (host.limits().cpus() != null) {
            settings.put(InstanceKindFields.CPU_LIMIT, host.limits().cpus());
        }
        return settings;
    }

    /**
     * Make the engine's WHOLE initialization environment the instance's variable set, every
     * value SECRET, and drop rows the engine no longer declares (an engine change must not
     * leave a stale credential behind).
     *
     * AIDEV-NOTE: the non-secret half ({@link ManagedDatabase.Engine#env}: the root user and
     * the init database) used to sit in {@code instances.settings}, a plain JSON column, so
     * the engine superuser's NAME was stored in the clear beside the encrypted password. It
     * now rides the same encrypted lane; {@link #sealPlaintextEnvironments} moves the rows an
     * older controller wrote.
     */
    private static void writeEngineEnvironment(int instanceId, @NonNull EngineHost host) {
        new InstanceVariables().storeSecretEnvironment(instanceId, engineEnvironment(host));
    }

    /**
     * Rewrite the stored environment of the host's engine instance from {@code host}: what its next container create
     * starts from. A password change of a dedicated engine calls it once the engine took the new password, so a
     * restart (which recreates the container) never brings back the old one; a deploy writes the same environment
     * itself from the record.
     *
     * @return false when the host owns no engine instance, so there is nothing to rewrite
     */
    public static boolean storeEngineEnvironment(@NonNull EngineHost host) {
        Row instance = ownedBy(host);
        if (instance == null) {
            return false;
        }
        writeEngineEnvironment(instance.get(InstanceModel.ID), host);
        return true;
    }

    /**
     * THE environment an engine instance's variables hold: the plain half, then the
     * password-bearing half.
     */
    public static @NonNull Map<String, String> engineEnvironment(@NonNull EngineHost host) {
        Map<String, String> environment = new LinkedHashMap<>(
            host.engine().env(host.rootUser(), host.initDatabase()));
        environment.putAll(host.engine().secretEnv(host.rootUser(), host.rootPassword()));
        return environment;
    }

    /**
     * Boot backfill: move the environment an older controller stored in a database engine
     * instance's settings into that instance's SECRET variables, and strip it.
     *
     * AIDEV-NOTE: the StackServiceSecrets / PreviewDeployments.sealPlaintextEnvironments shape,
     * a boot reconcile rather than a migration for the same reason: {@code instances.settings}
     * is a kind-discriminated field whose sub-schema lives in server code. Unlike those, an
     * engine instance ALREADY holds secret rows (the passwords), and they are the newer truth:
     * they win over the settings copy exactly as they do at deploy (applyToSettings), so the
     * container environment a redeploy produces is unchanged. Idempotent, trashed rows
     * included (their settings are credentials at rest too).
     *
     * @return how many engine instances were sealed in this pass
     */
    public static int sealPlaintextEnvironments() {
        return PlaintextEnvironments.seal("DB-RUNTIME", "database engine instance(s)",
            InstanceModel.KIND.eq(DatabaseContainerKind.ID.toString()), SOURCE, null, true);
    }

    /** The owner identity of a host, for callers that name it in a log or a refusal. */
    static @NonNull String describe(@NonNull EngineHost host) {
        Identifier model = host.ownerModel();
        return (host.shared() ? "engine '" : "database '") + host.name() + "' (" + model + " #"
            + host.ownerId() + ")";
    }
}
