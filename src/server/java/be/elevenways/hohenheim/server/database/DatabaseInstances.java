package be.elevenways.hohenheim.server.database;

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
 * contract: every database's engine IS an owned {@code hohenheim:database_container}
 * instance, deployed and destroyed through {@link InstanceService}. The database record
 * keeps the product half -- name, credentials, engine vocabulary, backups, restore and
 * site attachments -- and no longer talks to the daemon about its own container.
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

    /** The database record's owned engine instance, or null before it has one. */
    public static @Nullable Row owned(int databaseId) {
        return OwnedInstances.soleOwnedBy(DatabaseModel.MODEL_ID, databaseId);
    }

    /**
     * THE container handle of a managed database's engine, and (via Docker's embedded
     * DNS on any shared user-defined network) the hostname a joined container reaches it
     * under.
     *
     * @return null when the database owns no instance yet -- callers must treat that as
     *         "no engine to reach", never as a name to guess
     */
    public static @Nullable String handleOf(int databaseId) {
        Row instance = owned(databaseId);
        return instance == null ? null
            : ControllerScope.handle(ControllerScope.KIND_INSTANCE,
                instance.get(InstanceModel.ID));
    }

    /** Live engine state off the daemon; never throws (see {@link ContainerState}). */
    public static ManagedDatabase.@NonNull LiveStatus liveStatus(int databaseId) {
        Row instance = owned(databaseId);
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
     * Converge the database's owned engine instance onto the record and deploy it,
     * retiring the pre-lowering {@code hohenheim-{token}-db-{name}} container once.
     *
     * @return the published loopback host port the daemon handed the engine
     * @throws IOException when the deploy is refused (quota, capacity, fence, daemon) or
     *         the engine never reports ready; the caller flips the record to failed
     */
    public static int deploy(@NonNull Row database, @NonNull ResourceLimits limits)
            throws IOException {
        int recordId = database.get(DatabaseModel.ID);
        String name = database.get(DatabaseModel.NAME);
        int serverId = ServerModel.canonicalServerId(database.get(DatabaseModel.SERVER_ID));
        String serverName = ServerModel.nameOf(serverId);
        ManagedDatabase.Engine engine = ManagedDatabase.engineOf(database);
        String user = database.get(DatabaseModel.DB_USER);
        String password = database.get(DatabaseModel.DB_PASSWORD);
        String dbName = database.get(DatabaseModel.DB_NAME);

        try {
            int instanceId = OwnedInstances.inScope(SOURCE, DatabaseModel.MODEL_ID, recordId,
                () -> {
                    Row instance = owned(recordId);
                    if (instance == null) {
                        instance = Models.get(InstanceModel.class).createEmptyRow();
                    }
                    instance.set(InstanceModel.NAME, instanceNameOf(name));
                    instance.set(InstanceModel.KIND, DatabaseContainerKind.ID.toString());
                    instance.set(InstanceModel.SERVER_ID, serverId);
                    instance.set(InstanceModel.SETTINGS,
                        desiredSettings(database, engine, user, dbName, limits));
                    Models.get(InstanceModel.class).save(instance);
                    return (int) (Integer) instance.get(InstanceModel.ID);
                });

            // Secrets AFTER the row exists (they key on its id) and BEFORE the deploy
            // reads them: applyToSettings merges them into the container environment.
            writeEngineSecrets(instanceId, engine, password);

            DockerClient docker = new ServerService().clientFor(serverName);
            retireLegacyContainer(docker, name, recordId);

            InstanceStatus status = OwnedInstances.inScope(SOURCE, DatabaseModel.MODEL_ID,
                recordId, () -> new InstanceService().deploy(instanceId));
            Integer port = status.publishedPort();
            if (port == null) {
                throw new IOException("Database '" + name + "' deployed but the daemon"
                    + " reports no published host port for its engine");
            }
            // The engine READINESS gate stays a product-tier concern, exactly like the
            // site tier's HTTP health probe stays in SiteReleases: the instance contract
            // answers "is the workload running", never "can this engine serve queries".
            ManagedDatabase.awaitReady(docker,
                ControllerScope.handle(ControllerScope.KIND_INSTANCE, instanceId),
                engine, user, password, dbName, 60_000);
            return port;
        } catch (IOException failed) {
            throw failed;
        } catch (Violations refused) {
            throw new IOException("Database '" + name + "' could not be deployed: "
                + refused.getMessage(), refused);
        } catch (RuntimeException | Error unchecked) {
            throw unchecked;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    /**
     * Verified end of life, called explicitly by {@link DatabaseService#destroy} (the
     * GameDomains.deleteForInstance shape: the instance soft-deletes, so nothing else
     * would ever clean it up). The container is removed or observed absent, the ledger
     * claims are released fully, and the data volume follows when asked -- also verified.
     *
     * @throws IOException when the daemon cannot confirm a teardown; the caller KEEPS the
     *         record (it holds the only copy of {@code db_password}) and retries
     */
    public static void destroyFor(@NonNull Row database, boolean removeData)
            throws IOException {
        int recordId = database.get(DatabaseModel.ID);
        String name = database.get(DatabaseModel.NAME);
        String serverName = ServerModel.nameOf(
            ServerModel.canonicalServerId(database.get(DatabaseModel.SERVER_ID)));

        Row instance = owned(recordId);
        if (instance != null) {
            int instanceId = instance.get(InstanceModel.ID);
            try {
                OwnedInstances.inScope(SOURCE, DatabaseModel.MODEL_ID, recordId, () -> {
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
        // The pre-lowering container, if this database never re-deployed after the
        // lowering: removed only when the daemon still attributes it to this record.
        retireLegacyContainer(docker, name, recordId);
        if (removeData) {
            try {
                docker.removeVolume(dataVolumeOf(name), true);
            } catch (DockerClient.ApiException e) {
                if (!e.isNotFound()) {
                    throw e;   // refused: the data is NOT gone, so neither is the record
                }
            }
        }
    }

    /**
     * Abandon the owned engine instance WITHOUT touching the daemon: the operator's
     * force-destroy decision. The row is SOFT-deleted through save() so the capacity and
     * quota releases riding the {@code deleted_at} transition fire, and the port claim is
     * PARKED rather than deleted -- a container nobody could confirm may still hold it.
     */
    public static void abandonInstance(int databaseId) {
        Row instance = owned(databaseId);
        if (instance == null) {
            return;
        }
        int instanceId = instance.get(InstanceModel.ID);
        OwnedInstances.inScopeUnchecked(SOURCE, DatabaseModel.MODEL_ID, databaseId, () -> {
            PortLedger.releaseOwner(InstanceModel.MODEL_ID, instanceId);
            Row row = Models.get(InstanceModel.class).findById(instanceId);
            if (row != null) {
                row.set(InstanceModel.DELETED_AT, java.time.Instant.now());
                Models.get(InstanceModel.class).save(row);
            }
        });
        Blast.log("DB-RUNTIME: abandoned the engine instance of database", databaseId,
            "- its container may survive on the host and will surface in the reconciler");
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
     * binding property "no data migration may lose a running workload"): every database
     * record that owns no instance yet gets one, and any record whose engine container
     * still exists at the daemon is re-deployed under the contract -- onto the SAME data
     * volume, so the engine comes back on the same bytes.
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
            if (recordId == null || name == null || owned(recordId) != null) {
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

    /** The instance record's name: recognisable in the admin list, never an identity. */
    static @NonNull String instanceNameOf(@NonNull String databaseName) {
        return "db-" + databaseName;
    }

    /**
     * THE data volume of a managed database: keyed to the record's NAME, so it survives
     * any particular instance row and so a pre-lowering database's existing volume is
     * the one the lowered instance mounts.
     */
    public static @NonNull String dataVolumeOf(@NonNull String databaseName) {
        return ControllerScope.handle(ControllerScope.KIND_DB, databaseName) + "-data";
    }

    /** Map the DATABASE record onto the database_container kind settings. */
    private static @NonNull Map<String, Object> desiredSettings(@NonNull Row database,
                                                                ManagedDatabase.@NonNull Engine engine,
                                                                @Nullable String user,
                                                                @Nullable String dbName,
                                                                @NonNull ResourceLimits limits) {
        boolean ephemeral = Boolean.TRUE.equals(database.get(DatabaseModel.EPHEMERAL));
        String image = database.get(DatabaseModel.IMAGE);
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("engine", engine.token());
        settings.put("image", image == null || image.isBlank() ? engine.defaultImage : image);
        settings.put("ephemeral", ephemeral);
        settings.put("data_volume", ephemeral ? ""
            : dataVolumeOf(String.valueOf((Object) database.get(DatabaseModel.NAME))));
        settings.put("environment_variables", engine.env(user, dbName));
        String command = engine.containerCommandTemplate();
        if (command != null) {
            settings.put("command", command);
        }
        if (limits.memoryMb() != null) {
            settings.put("memory_limit_mb", limits.memoryMb());
        }
        if (limits.cpus() != null) {
            settings.put("cpu_limit", limits.cpus());
        }
        return settings;
    }

    /**
     * Write the engine's password-bearing variables onto the instance through the SECRET
     * lane, replacing any previous values, and remove secret rows the engine no longer
     * declares (an engine change must not leave a stale credential behind).
     */
    private static void writeEngineSecrets(int instanceId, ManagedDatabase.@NonNull Engine engine,
                                           @Nullable String password) {
        Map<String, String> secrets = engine.secretEnv(password == null ? "" : password);
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
}
