package be.elevenways.hohenheim.server.stack;

import be.elevenways.hohenheim.model.InstanceFileModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.model.StackServiceModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.OwnedInstances;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.server.runtime.DockerInstanceRuntime;
import be.elevenways.hohenheim.server.runtime.InstanceStatus;
import be.elevenways.hohenheim.server.runtime.LinkNetworkSupport;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The wiring between the STACK tier and the canonical runtime-resource contract: every
 * enabled stack service IS an owned {@code hohenheim:stack_service} instance, deployed and
 * destroyed through {@link InstanceService}. The stack records keep the product half --
 * compose-shaped authoring, the dependency graph, deployment history and rollback
 * snapshots -- and no longer talk to the daemon about their own containers.
 *
 * What the tier GAINED by lowering, none of which it had before: fenced outcome writes (a
 * stale controller's deploy cannot stick), the host lease, host capacity booking
 * (charge == cap -- the pre-lowering tier booked NOTHING, so a host could be overcommitted
 * by stacks invisibly), the port ledger's claim-before-create with its {@code releasing}
 * park and its after-start verification against the daemon's OWN binding, host placement,
 * the reconciler's instance classification, {@code InstanceService}'s verified destroy and
 * {@code InstanceOperationGuard}, backups/snapshots and {@code WorkloadLiveness}.
 *
 * WHAT STAYED IN THE PRODUCT TIER, and why: multi-service ORDERING and its condition
 * gating ({@link StackRuntime}). An instance answers "is this workload running/healthy";
 * "may this workload start yet" is a statement ABOUT OTHER RECORDS, which is exactly the
 * shape the database tier left in the product tier when it kept {@code awaitReady} out of
 * {@code InstanceService}.
 *
 * AIDEV-NOTE: the shared per-stack network is a LINK network -- the mechanism the
 * game-domain and site-database lanes already use -- owned by the STACK record, carrying
 * the same verified kernel policy, and joined between container create and start. Each
 * service ALSO keeps its own private per-workload network, so the lowering ends up with
 * MORE isolation than the pre-lowering single shared bridge, not less.
 */
public final class StackInstances {

    /** The GeneratedRows source token, and the Accountability origin of every write here. */
    public static final String SOURCE = "stack";

    /**
     * The PRE-LOWERING ownership label: the stack NAME, stamped on containers, networks
     * and volumes by the deleted {@code StackDeployer}.
     *
     * AIDEV-NOTE: kept for exactly two readers -- {@link #retireLegacyContainer} (which
     * needs to find such a container) and the reconciler (which must still classify one
     * that has not been adopted yet). Nothing this class CREATES carries it; a lowered
     * stack service container is an instance container with instance owner labels.
     */
    public static final String LEGACY_LABEL_STACK = "be.elevenways.hohenheim.stack";

    private StackInstances() {
    }

    /** Install the shared owned-instance funnel (MODULES stage); idempotent. */
    public static void install() {
        OwnedInstances.install();
    }

    // -- naming ---------------------------------------------------------------

    /** The link handle of the stack's shared network (the {@code -net} suffix is the driver's). */
    public static @NonNull String networkHandle(@NonNull String stackName) {
        return ControllerScope.handle(ControllerScope.KIND_STACK, stackName);
    }

    /** THE materialized name of one declared mount: stack-scoped, so it outlives any instance row. */
    public static @NonNull String volumeName(@NonNull String stackName,
                                             StackSpec.@NonNull MountSpec mount) {
        return mount.externalName() != null ? mount.externalName()
            : networkHandle(stackName) + "-" + mount.name();
    }

    /** The pre-lowering container name of one service, for retirement only. */
    static @NonNull String legacyContainerName(@NonNull String stackName,
                                               @NonNull String service) {
        return networkHandle(stackName) + "-" + service;
    }

    // -- lookups --------------------------------------------------------------

    /** The service record's owned instance, or null before it has one. */
    public static @Nullable Row owned(int serviceId) {
        return OwnedInstances.soleOwnedBy(StackServiceModel.MODEL_ID, serviceId);
    }

    /** Every live instance owned by any service of the stack, service id to instance row. */
    public static @NonNull Map<Integer, Row> ownedByStack(int stackId) {
        Map<Integer, Row> owned = new LinkedHashMap<>();
        for (Row service : Models.get(StackServiceModel.class).findByStackId(stackId)) {
            Integer serviceId = service.get(StackServiceModel.ID);
            if (serviceId == null) {
                continue;
            }
            Row instance = owned(serviceId);
            if (instance != null) {
                owned.put(serviceId, instance);
            }
        }
        return owned;
    }

    /** Live status of one service's workload; ABSENT when it owns no instance. */
    public static @NonNull InstanceStatus liveStatus(int serviceId) {
        Row instance = owned(serviceId);
        if (instance == null) {
            return new InstanceStatus(ContainerState.ABSENT, null);
        }
        try {
            return new InstanceService().liveStatus(instance.get(InstanceModel.ID));
        } catch (RuntimeException unresolvable) {
            // An unaskable host is UNREACHABLE, never "gone": absent and unreachable stay
            // distinct identities, and only one of them means "nothing is running".
            return new InstanceStatus(ContainerState.UNREACHABLE, null);
        }
    }

    /**
     * Per-server shared-network link handles of every stack that currently owns at least
     * one instance -- the isolation sweep's inventory of stack networks whose kernel
     * chains must exist (a host reboot erases them while the containers restart).
     */
    public static @NonNull Map<Integer, List<String>> liveLinkHandles() {
        Map<Integer, List<String>> byServer = new LinkedHashMap<>();
        for (Row stack : Models.get(StackModel.class).find().all()) {
            Integer stackId = stack.get(StackModel.ID);
            String name = stack.get(StackModel.NAME);
            if (stackId == null || name == null || name.isBlank()
                    || ownedByStack(stackId).isEmpty()) {
                continue;
            }
            byServer.computeIfAbsent(ServerModel.canonicalServerId(
                    stack.get(StackModel.SERVER_ID)), key -> new ArrayList<>())
                .add(networkHandle(name));
        }
        return byServer;
    }

    // -- convergence ----------------------------------------------------------

    /**
     * Converge ONE service's owned instance onto the spec and deploy it, retiring the
     * pre-lowering {@code hohenheim-{token}-stack-{stack}-{service}} container once.
     *
     * @return the deployed workload's live status
     * @throws IOException when the deploy is refused (quota, capacity, fence, daemon)
     */
    public static @NonNull InstanceStatus deploy(@NonNull StackSpec spec,
                                                 StackSpec.@NonNull ServiceSpec service)
            throws IOException {
        int serviceId = service.serviceId();
        if (serviceId <= 0) {
            throw new IOException("Service '" + service.name() + "' of stack '" + spec.name()
                + "' carries no record id; a snapshot from before the lowering cannot be"
                + " re-deployed onto the instance contract");
        }
        if (Models.get(StackServiceModel.class).findById(serviceId) == null) {
            throw new IOException("Service '" + service.name() + "' of stack '" + spec.name()
                + "' no longer exists; nothing owns the instance it would create");
        }
        int serverId = ServerModel.canonicalServerId(
            ServerModel.canonicalServerId(spec.serverName()));
        try {
            int instanceId = OwnedInstances.inScope(SOURCE, StackServiceModel.MODEL_ID,
                serviceId, () -> {
                    Row instance = owned(serviceId);
                    if (instance == null) {
                        instance = Models.get(InstanceModel.class).createEmptyRow();
                    }
                    instance.set(InstanceModel.NAME, spec.name() + "-" + service.name());
                    instance.set(InstanceModel.KIND, StackServiceKind.ID.toString());
                    instance.set(InstanceModel.SERVER_ID, serverId);
                    // The compose restart policy lowers onto the instance tier's CRASH
                    // policy, which is strictly better: it has flap protection and it
                    // re-runs the whole fenced deploy instead of letting the daemon
                    // restart a container whose ledger claims nobody re-verified.
                    instance.set(InstanceModel.CRASH_POLICY,
                        "no".equals(service.restartPolicy())
                            ? InstanceModel.CRASH_NONE : InstanceModel.CRASH_RESTART);
                    instance.set(InstanceModel.SETTINGS, desiredSettings(spec, service));
                    Models.get(InstanceModel.class).save(instance);
                    return (int) (Integer) instance.get(InstanceModel.ID);
                });

            // THE capability refusal, at the runtime funnel: a declaration outside the
            // closed allow-list refuses the DEPLOY by name, before any container exists.
            // The resolved spec degrades to the bare baseline instead of throwing, so the
            // same row stays stoppable and deletable (see StackServiceKind).
            StackServiceKind.hardeningFor(service.name(), service.capabilities());
            syncConfigFiles(instanceId, serviceId, service);
            retireLegacyContainer(spec, service.name(), serviceId);

            return OwnedInstances.inScope(SOURCE, StackServiceModel.MODEL_ID, serviceId,
                () -> new InstanceService().deploy(instanceId));
        } catch (IOException failed) {
            throw failed;
        } catch (Violations refused) {
            throw new IOException("Service '" + service.name() + "' could not be deployed: "
                + refused.getMessage(), refused);
        } catch (RuntimeException | Error unchecked) {
            throw unchecked;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    /** Stop one service's workload; a service without an instance is a no-op. */
    public static void stop(int serviceId) throws IOException {
        Row instance = owned(serviceId);
        if (instance == null) {
            return;
        }
        int instanceId = instance.get(InstanceModel.ID);
        if (!InstanceModel.STATUS_RUNNING.equals(instance.get(InstanceModel.STATUS))
                && !InstanceModel.STATUS_STARTING.equals(instance.get(InstanceModel.STATUS))) {
            return;
        }
        run(serviceId, () -> new InstanceService().stop(instanceId));
    }

    /**
     * Verified end of life of ONE service's workload, called explicitly (the instance
     * soft-deletes, so nothing else would ever clean it up).
     *
     * @throws IOException when the daemon cannot confirm the teardown
     */
    public static void destroyFor(int serviceId) throws IOException {
        Row instance = owned(serviceId);
        if (instance == null) {
            return;
        }
        int instanceId = instance.get(InstanceModel.ID);
        run(serviceId, () -> new InstanceService().destroy(instanceId));
    }

    private interface Work {
        void run();
    }

    private static void run(int serviceId, @NonNull Work work) throws IOException {
        try {
            OwnedInstances.inScope(SOURCE, StackServiceModel.MODEL_ID, serviceId, () -> {
                work.run();
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

    // -- the shared stack network ---------------------------------------------

    /**
     * Between container CREATE and START of a stack service: ensure the stack's shared
     * link network and join this workload to it under its SERVICE NAME, which is the DNS
     * alias a sibling dials. A deploy recreates the container and drops every non-primary
     * attachment, so this runs on every deploy.
     *
     * @throws IOException when the link network cannot be enforced -- a service that
     *         cannot reach its siblings must not start pretending it can
     */
    public static void attachLinksBeforeStart(InstanceService.@NonNull Resolved resolved,
                                              int instanceId) throws IOException {
        Row row = resolved.row();
        if (!StackServiceModel.MODEL_ID.toString().equals(
                row.get(InstanceModel.GENERATED_FOR_MODEL))) {
            return;
        }
        Map<String, Object> settings = settingsOf(row);
        String handle = str(settings.get("stack_network"));
        String alias = str(settings.get("service_name"));
        Object stackId = settings.get("stack_id");
        if (handle.isEmpty() || alias.isEmpty() || !(stackId instanceof Number number)) {
            throw new IOException("Instance " + instanceId + " is a stack service but its"
                + " settings name no stack network, service alias or stack id");
        }
        if (!(resolved.runtime() instanceof LinkNetworkSupport support)) {
            throw new IOException("Instance " + instanceId + " is a stack service but runs"
                + " on a driver without link networks");
        }
        support.ensureLinkNetwork(handle,
            OwnerLabels.of(StackModel.MODEL_ID, number.intValue()), StackServiceKind.EGRESS);
        support.connectToLinkNetwork(handle, resolved.spec().handle(), List.of(alias));
    }

    /**
     * Remove the stack's shared link network and its kernel chains, once every member is
     * gone. Called by the stack destroy path only: the network belongs to the STACK
     * record, not to any one service, so a single service's destroy never takes it down.
     */
    static void removeNetwork(@NonNull String serverName, @NonNull String stackName)
            throws IOException {
        runtimeFor(serverName).removeLinkNetwork(networkHandle(stackName));
    }

    /** A driver over one host, used for the network half (no instance record involved). */
    static @NonNull DockerInstanceRuntime runtimeFor(@NonNull String serverName) {
        return new DockerInstanceRuntime(new ServerService().clientFor(serverName),
            WorkloadNetworkPolicy.forServer(serverName));
    }

    // -- volumes ---------------------------------------------------------------

    /**
     * Remove every volume this stack's NAME scopes, once its containers are gone.
     * IRREVERSIBLE: this is the one daemon resource whose contents cannot be re-fetched,
     * so callers guard it (the admin action makes the operator type the stack's name).
     *
     * Volumes declared with an EXTERNAL name are skipped by construction -- the sweep is
     * by the controller-scoped stack prefix, which an adopted volume never carries, so we
     * still never remove what we did not create.
     *
     * @throws IOException when the daemon refuses a removal (a volume still attached)
     */
    static void removeOwnedVolumes(@NonNull String serverName, @NonNull String stackName)
            throws IOException {
        DockerClient docker = new ServerService().clientFor(serverName);
        String prefix = networkHandle(stackName) + "-";
        for (Object entry : docker.listVolumes()) {
            if (!(entry instanceof Map<?, ?> volume)
                    || !(volume.get("Name") instanceof String name)
                    || !name.startsWith(prefix)) {
                continue;
            }
            docker.removeVolume(name, true);
            Blast.log("STACK: removed volume", name);
        }
    }

    // -- the pre-lowering shape ------------------------------------------------

    /**
     * Retire one service's pre-lowering container exactly once: removed if the daemon
     * still attributes it to this service record, and the record's own ledger claims are
     * released -- observed when the removal was verified, parked otherwise.
     *
     * AIDEV-NOTE: the SiteInstances/DatabaseInstances retirement shape, including its
     * refusal discipline -- a same-named container the daemon does NOT attribute to this
     * record is never force-removed, it surfaces through the reconciler as an explicit
     * operator decision. The pre-lowering deployer stamped the OWNER pair of the STACK
     * (not the service), so that is the attribution asked for here.
     */
    private static void retireLegacyContainer(@NonNull StackSpec spec, @NonNull String service,
                                              int serviceId) {
        try {
            DockerClient docker = new ServerService().clientFor(spec.serverName());
            boolean removed = OwnerLabels.removeIfOwnedBy(docker,
                legacyContainerName(spec.name(), service), StackModel.MODEL_ID, spec.stackId());
            if (removed) {
                PortLedger.releaseOwnerObserved(StackServiceModel.MODEL_ID, serviceId);
                Blast.log("STACK: retired the pre-lowering container of service", service);
            } else if (!PortLedger.claimsOf(StackServiceModel.MODEL_ID, serviceId).isEmpty()) {
                // Claims without a container: unverifiable, park them for the reconciler.
                PortLedger.releaseOwner(StackServiceModel.MODEL_ID, serviceId);
            }
        } catch (IOException e) {
            // A foreign same-named container or an unreachable daemon: the deploy that
            // follows fails loudly on its own collision/daemon checks.
            Blast.log("STACK: could not retire the legacy container of service", service,
                "-", e.getMessage());
        }
    }

    /**
     * Retire the pre-lowering SHARED NETWORK of a stack, once every service has an owned
     * instance on its own network. Removed only when the daemon still attributes it to
     * this stack record; a network that is not ours is left alone.
     */
    static void retireLegacyNetwork(@NonNull StackSpec spec) {
        String name = networkHandle(spec.name());
        try {
            DockerClient docker = new ServerService().clientFor(spec.serverName());
            Map<String, Object> existing = docker.findNetworkByName(name);
            if (existing == null) {
                return;
            }
            Object labels = existing.get("Labels");
            OwnerLabels.Owner owner = labels instanceof Map<?, ?> map
                ? OwnerLabels.parse(map) : null;
            boolean ours = owner != null && owner.model().equals(StackModel.MODEL_ID)
                && owner.id().equals(String.valueOf(spec.stackId()));
            if (!ours) {
                return;   // adopted or foreign: we never remove what we did not create
            }
            WorkloadNetworkPolicy.forServer(spec.serverName()).remove(name);
            docker.removeNetwork(name);
            Blast.log("STACK: retired the pre-lowering shared network", name);
        } catch (IOException e) {
            Blast.log("STACK: could not retire the pre-lowering network", name,
                "-", e.getMessage());
        }
    }

    /**
     * Converge the owned instance's config files onto the ones the SPEC declares.
     *
     * AIDEV-NOTE: from the SPEC, never from the {@code stack_files} rows, and that is what
     * keeps ROLLBACK honest -- a rollback re-deploys a stored snapshot, whose file contents
     * are the ones that shipped with that deployment, not whatever the rows say today.
     * {@link InstanceFileModel} is documented as "the StackFileModel mechanism
     * generalized", so the stack rows stay the AUTHORING surface (encrypted at rest, admin
     * editable) and the instance rows are the derived runtime shape, exactly like the
     * service row's env is the author and {@code instances.settings} the derived copy.
     * They carry the GeneratedRows attribution of their stack service, so nothing outside
     * this scope can author one and the sweep can tell them from hand-written files.
     */
    private static void syncConfigFiles(int instanceId, int serviceId,
                                        StackSpec.@NonNull ServiceSpec service) {
        InstanceFileModel files = Models.get(InstanceFileModel.class);
        Map<String, Row> existing = new LinkedHashMap<>();
        for (Row file : files.findByInstanceId(instanceId)) {
            existing.put(String.valueOf((Object) file.get(InstanceFileModel.CONTAINER_PATH)), file);
        }
        for (StackSpec.FileSpec declared : service.files()) {
            Row row = existing.remove(declared.containerPath());
            if (row == null) {
                row = files.createEmptyRow();
                row.set(InstanceFileModel.INSTANCE_ID, instanceId);
                row.set(InstanceFileModel.CONTAINER_PATH, declared.containerPath());
            }
            row.set(InstanceFileModel.CONTENT, declared.content());
            row.set(InstanceFileModel.MODE, declared.mode());
            files.save(row);
        }
        // A file the spec no longer declares must not keep being staged into the
        // container on every deploy; the rows are the desired state, not an append log.
        for (Row stale : existing.values()) {
            files.delete(stale.get(InstanceFileModel.ID));
        }
    }

    /**
     * The documented migration of pre-lowering stacks (instance-tier-plan Phase 7, binding
     * property "no data migration may lose a running workload"): every ENABLED stack whose
     * services own no instances yet is re-deployed under the contract, onto the SAME
     * stack-scoped volumes, and its pre-lowering containers are retired one by one -- each
     * only when the daemon still attributes it to that stack record.
     *
     * Idempotent and safe to run on every boot: a stack whose services already own
     * instances is skipped entirely, and a host that cannot answer leaves the stack for the
     * next pass rather than abandoning a running container.
     *
     * @return how many stacks were adopted in this pass
     */
    public static int adoptExisting() {
        int adopted = 0;
        for (Row stack : Models.get(StackModel.class).find()
                .where(StackModel.ENABLED.eq(true)).all()) {
            Integer stackId = stack.get(StackModel.ID);
            String name = stack.get(StackModel.NAME);
            if (stackId == null || name == null || !ownedByStack(stackId).isEmpty()) {
                continue;
            }
            try {
                StackRuntime.get().deploy(stackId, "adoption");
                adopted++;
                Blast.log("STACK: adopted stack", name, "onto the instance runtime contract");
            } catch (Exception e) {
                Blast.log("STACK: could not adopt stack", name,
                    "- it keeps its pre-lowering containers and will be retried:",
                    e.getMessage());
            }
        }
        return adopted;
    }

    // -- settings --------------------------------------------------------------

    /** Map one resolved SERVICE onto the stack_service kind settings. */
    private static @NonNull Map<String, Object> desiredSettings(@NonNull StackSpec spec,
                                                                StackSpec.@NonNull ServiceSpec service) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", service.image());
        settings.put("command", List.copyOf(service.command()));
        settings.put("environment_variables", Map.copyOf(service.environment()));
        settings.put("capabilities", List.copyOf(service.capabilities()));

        Map<String, String> volumes = new LinkedHashMap<>();
        List<String> tmpfs = new ArrayList<>();
        for (StackSpec.MountSpec mount : service.mounts()) {
            if (StackServiceModel.MOUNT_TMPFS.equals(mount.type())) {
                tmpfs.add(mount.containerPath());
            } else {
                volumes.put(volumeName(spec.name(), mount), mount.containerPath());
            }
        }
        settings.put("volumes", volumes);
        settings.put("tmpfs_paths", tmpfs);

        List<Map<String, Object>> ports = new ArrayList<>();
        for (StackSpec.PortSpec port : service.ports()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("container_port", port.containerPort());
            entry.put("host_port", port.hostPort());
            entry.put("protocol", port.protocol());
            entry.put("host_ip", port.hostIp());
            ports.add(entry);
        }
        settings.put("ports", ports);

        if (service.healthCmd() != null) {
            settings.put("health_cmd", service.healthCmd());
            settings.put("health_interval_seconds", service.healthIntervalSeconds());
            settings.put("health_timeout_seconds", service.healthTimeoutSeconds());
            settings.put("health_retries", service.healthRetries());
            settings.put("health_start_period_seconds", service.healthStartPeriodSeconds());
        }
        if (service.memoryLimitMb() != null) {
            settings.put("memory_limit_mb", service.memoryLimitMb());
        }
        if (service.cpuLimit() != null) {
            settings.put("cpu_limit", service.cpuLimit());
        }
        settings.put("stack_id", spec.stackId());
        settings.put("stack_network", networkHandle(spec.name()));
        settings.put("service_name", service.name());
        return settings;
    }

    static @NonNull Map<String, Object> settingsOf(@NonNull Row instance) {
        Object stored = instance.get(InstanceModel.SETTINGS);
        if (stored instanceof Map<?, ?> map) {
            Map<String, Object> cast = new LinkedHashMap<>();
            map.forEach((key, value) -> cast.put(String.valueOf(key), value));
            return cast;
        }
        return Map.of();
    }

    private static @NonNull String str(@Nullable Object value) {
        return value == null ? "" : value.toString().trim();
    }
}
