package be.elevenways.hohenheim.server.task;

import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.InstanceDatabaseNetworks;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.game.GameDomains;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.runtime.DockerInstanceRuntime;
import be.elevenways.hohenheim.server.runtime.Egress;
import be.elevenways.hohenheim.server.runtime.NetworkPosture;
import be.elevenways.hohenheim.server.runtime.WorkloadNetworks;
import be.elevenways.hohenheim.server.security.WorkloadNetwork;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.hohenheim.server.stack.StackInstances;
import be.elevenways.hohenheim.server.stack.StackServiceKind;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.task.ScheduleDeclaration;
import be.elevenways.zenit.common.task.ScheduledTask;
import be.elevenways.zenit.common.task.TaskContext;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The reconciler that closes the Docker tiers' reboot window: every policied workload
 * network (instances, site releases, managed databases, stacks, link networks) is
 * re-checked against the daemon host's ACTUAL nftables, repaired through the verified
 * applier, and CONTAINED when the repair does not take.
 *
 * AIDEV-NOTE: renamed from VerifyDockerIsolation 2026-08-06 when the managed-process tier
 * joined it. The unit stopped being "a policied Docker network" -- a host process has no
 * network to key on, only its run-as uid -- and a class called ...Docker... sweeping host
 * processes is exactly the drift these notes exist to prevent. One mechanism, one schedule,
 * one repair-failure policy for every tier the Incus sweep does not own.
 *
 * AIDEV-NOTE: why a SWEEP and not only re-apply at start. Measured on daystrom
 * 2026-08-06 (real host reboot): Docker networks and their subnets survive a reboot,
 * nftables rules do not, and an unless-stopped container -- every stack service's
 * default -- is restarted by the DAEMON at boot, through no code path of ours. The
 * rebooted host served real HTTP from a stack container to a host-bound service while
 * {@code nft list table inet hohenheim_net} answered "No such file or directory".
 * Instances, site releases and managed databases carry NO restart policy, so they come
 * back stopped and re-apply on their next deploy/start -- but a sweep still covers them:
 * records saying RUNNING with chains missing is the same divergence one operator
 * {@code docker start} away from being live. A daemon restart alone does NOT open the
 * window (kernel state survives; also measured). Boot + five minutes, the
 * {@link VerifyIncusIsolation} shape: the window becomes bounded and self-closing
 * instead of lasting until the next deploy.
 *
 * AIDEV-NOTE: decided 2026-08-06 -- what happens when repair fails, per condition:
 * a host with enforcement OFF is reported unverifiable and nothing is stopped (the
 * pre-enforcement decision: deploy refuses, running workloads keep working); a kernel
 * that cannot be READ is reported unverifiable and nothing is stopped (refusing to
 * answer is not evidence of a leak, the Incus rule); only a workload whose divergence
 * is OBSERVED and whose re-apply is REFUSED is contained -- stacks and instances and
 * databases are stopped, link networks are severed by disconnecting their members
 * (both endpoints keep running on their own policied networks; the next deploy
 * re-attaches with the policy enforced). Repair runs first, so a transient failure
 * costs no availability; what is at stake otherwise is every other tenant on the host.
 */
public class VerifyWorkloadIsolation extends ScheduledTask {

    public static final String STATIC_DESCRIPTION =
        "Verify workload isolation in the host kernel";

    /** One host's outcome; every list names workloads, never a bare count. */
    public record HostOutcome(@NonNull String server, boolean verifiable,
                              @NonNull List<String> enforced, @NonNull List<String> repaired,
                              @NonNull List<String> contained, @NonNull List<String> errors) {
    }

    /** The escalation for one workload whose policy is diverged AND unrepairable. */
    public interface Containment {
        @NonNull String contain() throws Exception;
    }

    /** One policied network the kernel must carry, with its declared egress. */
    private record Expected(@NonNull String network, @NonNull Egress egress,
                            @NonNull String workload, @NonNull Containment containment) {
    }

    @Override
    public @NonNull VerifyWorkloadIsolation newTask() {
        return new VerifyWorkloadIsolation();
    }

    @Override
    public @NonNull List<ScheduleDeclaration> schedules() {
        // Boot included: the reboot IS the trigger this sweep exists for, and the
        // daemon restarts unless-stopped containers before our controller is up.
        return HohenheimRoles.schedulesWhen(
            List.of(ScheduleDeclaration.bootAndCron("*/5 * * * *")),
            HohenheimRoles.Role.INSTANCES, HohenheimRoles.Role.STACKS,
            HohenheimRoles.Role.DATABASES, HohenheimRoles.Role.PROXY);
    }

    @Override
    public @NonNull String description() {
        return STATIC_DESCRIPTION;
    }

    /** How this sweep names itself to an operator, in alerts and in the failure it throws. */
    public static final String SWEEP = "Workload isolation";

    @Override
    public void executor(TaskContext ctx) {
        report(sweep()).publish();
    }

    /**
     * Translate one sweep's outcomes into what an operator must be told.
     *
     * AIDEV-NOTE: the split is structural, never a string match on the error text. An
     * UNVERIFIABLE host is one whose kernel we could not read at all (enforcement switched
     * off, no Docker client) -- nothing was stopped and nothing is claimed, so it rides the
     * transition-only tier. On a VERIFIABLE host the sweep DID read the kernel, so anything
     * left in errors() is a workload it could not verify or could not repair while its
     * neighbours keep running, and contained() means one was actually cut off: both are the
     * security-consequential half and alert every run. Parsing the messages to tell them
     * apart would re-couple this to wording that changes.
     *
     * @return the findings, publishable by the caller
     */
    public static @NonNull IsolationFindings report(@NonNull List<HostOutcome> outcomes) {
        IsolationFindings findings = new IsolationFindings(SWEEP);
        for (HostOutcome outcome : outcomes) {
            if (!outcome.verifiable()) {
                Blast.log("WORKLOAD ISOLATION:", outcome.server(),
                    "cannot be kernel-verified; its workloads' isolation is UNCONFIRMED:",
                    outcome.errors());
                findings.unconfirmed(outcome.server(), outcome.errors());
                continue;
            }
            if (!outcome.repaired().isEmpty() || !outcome.contained().isEmpty()
                    || !outcome.errors().isEmpty()) {
                Blast.log("WORKLOAD ISOLATION:", outcome.server(), "- enforced",
                    outcome.enforced().size(), ", repaired", outcome.repaired(),
                    ", CONTAINED", outcome.contained(), ", errors", outcome.errors());
            }
            List<String> escalations = new ArrayList<>(outcome.contained());
            escalations.addAll(outcome.errors());
            if (!escalations.isEmpty()) {
                findings.escalated(outcome.server(), escalations);
            }
        }
        return findings;
    }

    /** Sweep every non-Incus host's workloads plus this controller's processes. */
    public static @NonNull List<HostOutcome> sweep() {
        Map<Integer, List<Expected>> inventory = new LinkedHashMap<>();
        Map<Integer, List<String>> inventoryErrors = new LinkedHashMap<>();
        collectInstances(inventory, inventoryErrors);
        collectLinks(inventory);

        List<HostOutcome> outcomes = new ArrayList<>();
        for (Integer serverId : allKeys(inventory, inventoryErrors)) {
            Row server = Models.get(ServerModel.class).findById(serverId);
            if (server != null && ServerModel.isIncus(server)) {
                continue;   // the Incus tier has its own kernel-truth sweep
            }
            outcomes.add(sweepHost(serverId,
                inventory.getOrDefault(serverId, List.of()),
                inventoryErrors.getOrDefault(serverId, List.of())));
        }
        return List.copyOf(outcomes);
    }

    // -- one host ---------------------------------------------------------------

    private static @NonNull HostOutcome sweepHost(int serverId, @NonNull List<Expected> expected,
                                                  @NonNull List<String> inventoryErrors) {
        String name = ServerModel.nameOf(serverId);
        WorkloadNetworkPolicy policy = WorkloadNetworkPolicy.forServer(name);
        if (!policy.isEnabled()) {
            // The pre-enforcement decision: deploys refuse, running workloads keep
            // working, and the sweep says ON THE RECORD that it cannot verify them.
            List<String> errors = new ArrayList<>(inventoryErrors);
            errors.add("per-workload enforcement is off (security.nftables_enabled); "
                + expected.size() + " workload network(s) can be neither verified nor repaired");
            return new HostOutcome(name, false, List.of(), List.of(), List.of(),
                List.copyOf(errors));
        }
        DockerClient docker;
        try {
            docker = new ServerService().clientFor(name);
        } catch (RuntimeException unreachable) {
            List<String> errors = new ArrayList<>(inventoryErrors);
            errors.add("no Docker client for this host: " + unreachable.getMessage());
            return new HostOutcome(name, false, List.of(), List.of(), List.of(),
                List.copyOf(errors));
        }

        List<String> enforced = new ArrayList<>();
        List<String> repaired = new ArrayList<>();
        List<String> contained = new ArrayList<>();
        List<String> errors = new ArrayList<>(inventoryErrors);
        for (Expected item : expected) {
            WorkloadNetwork network;
            try {
                if (docker.findNetworkByName(item.network()) == null) {
                    // No network at the daemon: nothing is attached to it, so there is
                    // nothing to protect; the next deploy recreates it WITH its policy.
                    continue;
                }
                network = WorkloadNetwork.fromInspect(docker.inspectNetwork(item.network()));
            } catch (IOException | RuntimeException daemonBroken) {
                errors.add(item.workload() + ": daemon state unreadable, isolation"
                    + " UNCONFIRMED: " + daemonBroken.getMessage());
                continue;
            }
            boolean enforcedNow;
            try {
                enforcedNow = policy.isEnforced(network, item.egress());
            } catch (IOException unreadable) {
                // An unreadable kernel is "unverifiable", never "unenforced": nothing
                // is contained on a host that merely refuses to answer.
                errors.add(item.workload() + ": kernel unreadable, isolation"
                    + " UNCONFIRMED: " + unreadable.getMessage());
                continue;
            }
            if (enforcedNow) {
                enforced.add(item.workload());
                continue;
            }
            try {
                policy.apply(network, item.egress());
                repaired.add(item.workload());
            } catch (IOException unrepairable) {
                // The declared refusal: observed diverged and unrepairable means the
                // workload does not stay reachable.
                errors.add(item.workload() + ": " + unrepairable.getMessage());
                try {
                    contained.add(item.containment().contain());
                } catch (Exception containFailed) {
                    errors.add(item.workload() + ": containment failed: "
                        + containFailed.getMessage());
                }
            }
        }
        return new HostOutcome(name, true, List.copyOf(enforced), List.copyOf(repaired),
            List.copyOf(contained), List.copyOf(errors));
    }

    // -- inventory ----------------------------------------------------------------

    /**
     * Docker-kind instance rows whose record claims a live workload, site release
     * containers and managed-database engines included. The declared egress comes from the
     * KIND's own driver, so a database engine's {@link Egress#NONE} is enforced here with
     * no per-tier collector.
     *
     * AIDEV-NOTE: the filter is {@link InstanceModel#LIVE_GUEST_STATUSES}, shared with
     * {@link VerifyIncusIsolation}. It gained {@code error} with that move: a readiness
     * timeout stamps error and stops nothing, so an errored record routinely names a
     * container that is still up and was never swept.
     */
    private static void collectInstances(@NonNull Map<Integer, List<Expected>> inventory,
                                         @NonNull Map<Integer, List<String>> errors) {
        boolean instancesRole = HohenheimRoles.enabled(HohenheimRoles.Role.INSTANCES);
        boolean proxyRole = HohenheimRoles.enabled(HohenheimRoles.Role.PROXY);
        boolean databasesRole = HohenheimRoles.enabled(HohenheimRoles.Role.DATABASES);
        if (!instancesRole && !proxyRole && !databasesRole) {
            return;
        }
        for (Row row : Models.get(InstanceModel.class).find()
                .where(InstanceModel.DELETED_AT.isNull())
                .where(InstanceModel.STATUS.in(InstanceModel.LIVE_GUEST_STATUSES))
                .all()) {
            Integer id = row.get(InstanceModel.ID);
            // An owned instance answers to the role of the tier that OWNS it: a site
            // release is the PROXY role's, a managed database's engine is the DATABASES
            // role's (which is what its own bespoke collector used to key on before the
            // database tier lowered onto this contract), everything else is INSTANCES'.
            String owner = row.get(InstanceModel.GENERATED_FOR_MODEL);
            boolean allowed;
            if (SiteModel.MODEL_ID.toString().equals(owner)) {
                allowed = proxyRole;
            } else if (DatabaseModel.MODEL_ID.toString().equals(owner)) {
                allowed = databasesRole;
            } else {
                allowed = instancesRole;
            }
            if (!allowed) {
                continue;
            }
            InstanceKindHandler handler = InstanceKinds.getHandler(
                row.get(InstanceModel.KIND));
            if (handler == null
                    || !handler.supportedRuntimes().contains(ServerModel.RUNTIME_DOCKER)) {
                continue;   // unknown kinds fail their own lanes; Incus has its own sweep
            }
            int serverId = ServerModel.canonicalServerId(row.get(InstanceModel.SERVER_ID));
            InstanceService.Resolved resolved;
            try {
                resolved = new InstanceService().resolve(id);
            } catch (RuntimeException unresolvable) {
                errors.computeIfAbsent(serverId, key -> new ArrayList<>())
                    .add("instance " + id + " cannot be resolved, isolation UNCONFIRMED: "
                        + unresolvable.getMessage());
                continue;
            }
            if (!(resolved.runtime() instanceof DockerInstanceRuntime runtime)
                    || runtime.posture() != NetworkPosture.PRIVATE) {
                continue;   // a SHARED_BRIDGE kind declared no per-workload network
            }
            String handle = resolved.spec().handle();
            inventory.computeIfAbsent(serverId, key -> new ArrayList<>()).add(new Expected(
                WorkloadNetworks.networkName(handle), runtime.egress(), handle,
                () -> {
                    new InstanceService().stop(id);
                    return "stopped instance " + handle;
                }));
        }
    }

    /**
     * Link networks of every owner: site-to-database and instance-to-database pairs
     * (egress NONE, the database's declaration must not widen through a second interface)
     * and game-domain proxy-to-backend pairs (egress OPEN, the proxy lane's declaration).
     */
    private static void collectLinks(@NonNull Map<Integer, List<Expected>> inventory) {
        if (HohenheimRoles.enabled(HohenheimRoles.Role.PROXY)) {
        }
        if (HohenheimRoles.enabled(HohenheimRoles.Role.INSTANCES)) {
            addLinks(inventory, GameDomains.liveLinkHandles(), Egress.OPEN);
            // Instance-to-database pairs: egress NONE for the same reason the site lane
            // is, and enumerated here or the sweep would read every one of them as an
            // unexpected network and sever a running workload from its database.
            addLinks(inventory, InstanceDatabaseNetworks.liveLinkHandles(), Egress.NONE);
        }
        // A lowered stack's shared network IS a link network, so it needs no lane of its
        // own: the per-service workload networks ride collectInstances, and severing this
        // one leaves every service running on its own (verified) network.
        if (HohenheimRoles.enabled(HohenheimRoles.Role.STACKS)) {
            addLinks(inventory, StackInstances.liveLinkHandles(), StackServiceKind.EGRESS);
        }
    }

    private static void addLinks(@NonNull Map<Integer, List<Expected>> inventory,
                                 @NonNull Map<Integer, List<String>> handlesByServer,
                                 @NonNull Egress egress) {
        for (Map.Entry<Integer, List<String>> entry : handlesByServer.entrySet()) {
            int serverId = entry.getKey();
            for (String handle : entry.getValue()) {
                String network = WorkloadNetworks.networkName(handle);
                inventory.computeIfAbsent(serverId, key -> new ArrayList<>()).add(new Expected(
                    network, egress, "link " + handle,
                    () -> severLink(serverId, network)));
            }
        }
    }

    // -- containment ---------------------------------------------------------------

    /**
     * Disconnect every member of an unrepairable link network: no member then holds an
     * address in the unpoliced subnet, both endpoints keep running on their own
     * (verified) networks, and the next deploy re-attaches with the policy enforced.
     */
    private static @NonNull String severLink(int serverId, @NonNull String network)
            throws IOException {
        DockerClient docker = new ServerService().clientFor(ServerModel.nameOf(serverId));
        Map<String, Object> inspect = docker.inspectNetwork(network);
        List<String> members = new ArrayList<>();
        if (inspect.get("Containers") instanceof Map<?, ?> attached) {
            for (Object value : attached.values()) {
                if (value instanceof Map<?, ?> member
                        && member.get("Name") instanceof String memberName
                        && !memberName.isEmpty()) {
                    members.add(memberName);
                }
            }
        }
        for (String member : members) {
            docker.disconnectContainerFromNetwork(network, member, true);
        }
        return "severed link network '" + network + "' (disconnected " + members + ")";
    }

    // -- helpers ---------------------------------------------------------------------

    private static @NonNull List<Integer> allKeys(@NonNull Map<Integer, List<Expected>> inventory,
                                                  @NonNull Map<Integer, List<String>> errors) {
        List<Integer> keys = new ArrayList<>(inventory.keySet());
        for (Integer key : errors.keySet()) {
            if (!keys.contains(key)) {
                keys.add(key);
            }
        }
        return keys;
    }
}
