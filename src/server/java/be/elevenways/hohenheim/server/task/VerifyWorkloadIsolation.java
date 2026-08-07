package be.elevenways.hohenheim.server.task;

import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.docker.SiteDatabaseNetworks;
import be.elevenways.hohenheim.server.game.GameDomains;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.process.ManagedProcessSiteHandler;
import be.elevenways.hohenheim.server.security.ProcessNetworkPolicy;
import be.elevenways.hohenheim.server.sitetype.SiteHandlers;
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
 * applier, and CONTAINED when the repair does not take. The controller's own
 * managed-process sites ride the same sweep on their uid chains.
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
            HohenheimRoles.Role.DATABASES, HohenheimRoles.Role.PROXY,
            HohenheimRoles.Role.PROCESSES);
    }

    @Override
    public @NonNull String description() {
        return STATIC_DESCRIPTION;
    }

    @Override
    public void executor(TaskContext ctx) {
        for (HostOutcome outcome : sweep()) {
            if (!outcome.verifiable()) {
                Blast.log("WORKLOAD ISOLATION:", outcome.server(),
                    "cannot be kernel-verified; its workloads' isolation is UNCONFIRMED:",
                    outcome.errors());
                continue;
            }
            if (!outcome.repaired().isEmpty() || !outcome.contained().isEmpty()
                    || !outcome.errors().isEmpty()) {
                Blast.log("WORKLOAD ISOLATION:", outcome.server(), "- enforced",
                    outcome.enforced().size(), ", repaired", outcome.repaired(),
                    ", CONTAINED", outcome.contained(), ", errors", outcome.errors());
            }
        }
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
        HostOutcome processes = sweepProcesses(liveProcessSubjects(),
            ProcessNetworkPolicy.current());
        if (processes != null) {
            outcomes.add(processes);
        }
        return List.copyOf(outcomes);
    }

    // -- the managed-process tier -----------------------------------------------------

    /** How this tier's outcome names itself; it is a uid inventory, not a host inventory. */
    public static final String PROCESS_SUBJECT = "managed processes (this controller)";

    /** One live process site: the identity the kernel must carry, and how to contain it. */
    public record ProcessSubject(int uid, @NonNull String site, @NonNull Containment containment) {
    }

    /**
     * Every site with LIVE children and a run-as uid, deduplicated BY UID.
     *
     * AIDEV-NOTE: the key is the uid, not the site, because the chain is. Two sites sharing
     * a system user (possible only while {@code process.require_dedicated_user} is off, and
     * a finding {@code WorkloadIdentity.auditAll} already reports) share ONE chain, so
     * sweeping them separately would verify and repair the same chain twice and, worse,
     * contain the second site for the first one's divergence.
     */
    private static @NonNull List<ProcessSubject> liveProcessSubjects() {
        if (!HohenheimRoles.enabled(HohenheimRoles.Role.PROCESSES)) {
            return List.of();
        }
        Map<Integer, ProcessSubject> byUid = new LinkedHashMap<>();
        for (Row site : Models.get(SiteModel.class).find()
                .where(SiteModel.ENABLED.eq(true))
                .where(SiteModel.DELETED_AT.isNull()).all()) {
            Integer siteId = site.get(SiteModel.ID);
            ManagedProcessSiteHandler handler = SiteHandlers.managedProcess(siteId);
            if (handler == null || handler.runningProcessCount() == 0) {
                continue;   // nothing of ours is running as that identity right now
            }
            Integer uid = handler.runAsUid();
            if (uid == null) {
                continue;   // no dedicated identity: nothing to key a policy on (warned at spawn)
            }
            String name = String.valueOf(site.get(SiteModel.NAME));
            byUid.putIfAbsent(uid, new ProcessSubject(uid, name, () -> {
                handler.killAllProcesses();
                return "killed the child processes of site '" + name + "'";
            }));
        }
        return List.copyOf(byUid.values());
    }

    /**
     * The uid-keyed half of the sweep: every live process site's OUTPUT chain must be in
     * this controller's own kernel.
     *
     * AIDEV-NOTE: this tier's REBOOT window is already closed without a sweep -- every
     * child is spawned by us and every spawn re-applies -- so what is swept here is the
     * MID-LIFE divergence the container tiers share: something else on the host flushing
     * the table under a child that keeps running. Containment is therefore killing the
     * children rather than stopping a container: the respawn goes straight back through
     * the applier, so a host that still cannot enforce simply keeps the site down with a
     * named refusal in the log instead of running it unisolated.
     *
     * @return null when this controller runs no isolatable managed processes at all
     */
    public static @Nullable HostOutcome sweepProcesses(
            @NonNull List<ProcessSubject> subjects, @NonNull ProcessNetworkPolicy policy) {
        if (subjects.isEmpty()) {
            return null;
        }
        if (!policy.isEnabled()) {
            return new HostOutcome(PROCESS_SUBJECT, false, List.of(), List.of(), List.of(),
                List.of("per-process enforcement is off (security.nftables_enabled); "
                    + subjects.size() + " site process identity/identities can be neither"
                    + " verified nor repaired"));
        }
        List<String> enforced = new ArrayList<>();
        List<String> repaired = new ArrayList<>();
        List<String> contained = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (ProcessSubject subject : subjects) {
            String named = "site '" + subject.site() + "' (uid " + subject.uid() + ")";
            try {
                if (policy.isEnforced(subject.uid(), subject.site())) {
                    enforced.add(named);
                    continue;
                }
            } catch (IOException unreadable) {
                // An unreadable kernel is "unverifiable", never "unenforced".
                errors.add(named + ": kernel unreadable, isolation UNCONFIRMED: "
                    + unreadable.getMessage());
                continue;
            }
            try {
                policy.apply(subject.uid(), subject.site());
                repaired.add(named);
            } catch (IOException unrepairable) {
                errors.add(named + ": " + unrepairable.getMessage());
                try {
                    contained.add(subject.containment().contain());
                } catch (Exception containFailed) {
                    errors.add(named + ": containment failed: " + containFailed.getMessage());
                }
            }
        }
        return new HostOutcome(PROCESS_SUBJECT, true, List.copyOf(enforced),
            List.copyOf(repaired), List.copyOf(contained), List.copyOf(errors));
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
     * Docker-kind instance rows whose record claims a live workload (running OR
     * starting), site release containers and managed-database engines included. The
     * declared egress comes from the KIND's own driver, so a database engine's
     * {@link Egress#NONE} is enforced here with no per-tier collector.
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
                .where(InstanceModel.STATUS.in(
                    InstanceModel.STATUS_RUNNING, InstanceModel.STATUS_STARTING))
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
                    || !ServerModel.RUNTIME_DOCKER.equals(handler.requiredRuntime())) {
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
     * Link networks of both owners: site-to-database pairs (egress NONE, the database's
     * declaration must not widen through a second interface) and game-domain
     * proxy-to-backend pairs (egress OPEN, the proxy lane's declaration).
     */
    private static void collectLinks(@NonNull Map<Integer, List<Expected>> inventory) {
        if (HohenheimRoles.enabled(HohenheimRoles.Role.PROXY)) {
            addLinks(inventory, SiteDatabaseNetworks.liveLinkHandles(), Egress.NONE);
        }
        if (HohenheimRoles.enabled(HohenheimRoles.Role.INSTANCES)) {
            addLinks(inventory, GameDomains.liveLinkHandles(), Egress.OPEN);
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
