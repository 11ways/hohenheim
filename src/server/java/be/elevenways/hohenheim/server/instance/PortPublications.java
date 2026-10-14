package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.PortAllocationModel;
import be.elevenways.hohenheim.model.HostMode;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.server.ports.PortHolders;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import be.elevenways.hohenheim.server.runtime.InstanceStatus;
import be.elevenways.hohenheim.server.runtime.PortPublication;
import be.elevenways.hohenheim.server.util.PortProbe;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The control-plane half of instance port publications: the pre-allocation strategy's
 * claim-before-create step, and the after-start verification that the daemon bound what
 * the declaration said (the "reports published while bound to loopback" defect, hunted
 * by name).
 *
 * AIDEV-NOTE: pre-allocation does not remove the TOCTOU, it adds a second window seconds
 * wide with an image pull inside it. Its honesty conditions
 * are exactly two and both live here: the claim is written BEFORE the container is
 * created (so a crash inside the window leaves a row the reconciler can judge, never an
 * unclaimed bound port), and the daemon's OWN binding is read back after start and
 * compared against the claim -- a mismatch stops the workload rather than reporting a
 * deploy that "succeeded" on a bind nothing declared.
 */
public final class PortPublications {

    private PortPublications() {
    }

    /**
     * Ensure the ledger state matches the spec's declared publication, BEFORE the
     * container is created: a pre-allocation-shaped publication gets its host port
     * claimed (reusing the owner's existing reservation when the shape still matches),
     * a record-after publication asks for the number it was last observed on, and
     * reservations the declaration no longer wants are parked for the reconciler.
     *
     * @return the spec to create the container from (carries the claimed host port)
     * @throws Violations naming the conflict or the exhausted window
     */
    static @NonNull InstanceSpec ensureClaimed(InstanceService.@NonNull Resolved resolved,
                                               int instanceId) {
        InstanceSpec spec = resolved.spec();
        // The reusable-claim scan below matches ONE publication against the owner's
        // existing reservations, so a multi-publication spec resolves them in declaration
        // order and each claim is consumed at most once (two publications of the same
        // shape must never both resolve to the same reserved number).
        List<PortPublication> claimed = new ArrayList<>();
        Set<Integer> consumed = new HashSet<>();
        List<Row> existing = PortLedger.preallocatedClaimsOf(InstanceModel.MODEL_ID, instanceId);
        List<Row> observed = new ArrayList<>(PortLedger.claimsOf(InstanceModel.MODEL_ID, instanceId));
        observed.removeIf(PortLedger::isPreallocated);
        Set<Integer> remembered = new HashSet<>();
        for (PortPublication publication : spec.publications()) {
            claimed.add(publication.requiresPreallocation()
                ? claimOne(resolved.serverId(), instanceId, publication, existing, consumed)
                : remembered(resolved, publication, observed, remembered));
        }
        // Reservations no publication resolved to end OBSERVED, never silently deleted.
        for (Row claim : existing) {
            Integer id = claim.get(PortAllocationModel.ID);
            if (id != null && !consumed.contains(id)) {
                PortLedger.parkClaim(claim);
            }
        }
        return spec.withPreallocatedPorts(claimed);
    }

    /**
     * A record-after publication asking for the host port its owner was last observed on, so a restart, a backup's
     * stop and start or a redeploy keeps the number; the kernel picks a new one only when that number is gone.
     *
     * AIDEV-NOTE: the observed claim outlives a settled stop (InstanceService.stopNow) for exactly this, and its
     * number lies in the kernel's ephemeral range, so while the workload is down the daemon may hand it to another
     * container: a local host probes it first (only when this record holds no live workload, whose own bind would
     * read as taken), and that other owner's record-after write supersedes the stale row (PortLedger.recordObservedAll).
     * A remote host has the ledger only; a number taken there fails the start loudly and the failure parks the claim.
     */
    private static @NonNull PortPublication remembered(InstanceService.@NonNull Resolved resolved,
                                                       @NonNull PortPublication publication,
                                                       @NonNull List<Row> observed, @NonNull Set<Integer> taken) {
        for (Row claim : observed) {
            Integer id = claim.get(PortAllocationModel.ID);
            Integer port = claim.get(PortAllocationModel.PORT);
            if (id == null || port == null || taken.contains(id) || PortLedger.isReleasing(claim)
                    || !Objects.equals(claim.get(PortAllocationModel.SERVER_ID), resolved.serverId())
                    || !publication.protocol().equals(claim.get(PortAllocationModel.PROTOCOL))
                    || !publication.ledgerBindAddress().equals(claim.get(PortAllocationModel.HOST_IP))) {
                continue;
            }
            taken.add(id);
            boolean live = InstanceModel.SERVABLE_STATUSES.contains(resolved.row().get(InstanceModel.STATUS));
            if (!live && isLocalServer(resolved.serverId())
                    && !PortProbe.isFree(publication.ledgerBindAddress(), port, publication.protocol())) {
                Blast.log("PORTS: host port", port, "of instance", resolved.row().get(InstanceModel.ID),
                    "was taken while it was down; the daemon picks a new one");
                return publication;
            }
            return publication.withPreallocatedPort(port);
        }
        return publication;
    }

    /**
     * Claims the host port of every publication of an instance that has not started yet, on the host it was placed
     * on, through the same claim the deploy makes: an operator-chosen port is refused when another owner holds it, an
     * open one takes the first free port of the window. The deploy's {@link #ensureClaimed} then reuses each claim.
     *
     * @return the publications, each carrying the host port it was given, in the same order
     * @throws Violations naming a taken port's holder, or the exhausted window
     */
    public static @NonNull List<PortPublication> reserve(int instanceId, int serverId,
                                                         @NonNull List<PortPublication> publications) {
        // Chosen ports first: an open port must never take, from the window, a number a later entry chose.
        PortPublication[] claimed = new PortPublication[publications.size()];
        Set<Integer> consumed = new HashSet<>();
        List<Row> existing = PortLedger.preallocatedClaimsOf(InstanceModel.MODEL_ID, instanceId);
        for (boolean chosen : new boolean[] {true, false}) {
            for (int index = 0; index < claimed.length; index++) {
                PortPublication publication = publications.get(index);
                if ((publication.declaredHostPort() != null) == chosen) {
                    claimed[index] = claimOne(serverId, instanceId, publication, existing, consumed);
                }
            }
        }
        return List.of(claimed);
    }

    /**
     * The refusal a new instance's chosen public host port meets on this host, asked before anything is created; the
     * claim itself is {@link #reserve}'s and refuses the same way when a rival got there in between.
     *
     * @return the refusal naming the port (and its holder to a reader who may know it), or null when it is free
     */
    public static @Nullable Violations takenRefusal(int serverId, int port, @NonNull String protocol) {
        Row rival = PortLedger.rivalHolder(serverId, "", port, protocol, null, null);
        return rival == null ? null : conflictRefusal(port, rival, PortLedger.describeHolder(rival));
    }

    /** Resolve ONE pre-allocation-shaped publication to a held claim. */
    private static @NonNull PortPublication claimOne(int serverId,
                                                     int instanceId,
                                                     @NonNull PortPublication publication,
                                                     @NonNull List<Row> existing,
                                                     @NonNull Set<Integer> consumed) {
        String bind = publication.ledgerBindAddress();
        String protocol = publication.protocol();
        Row reusable = null;
        for (Row claim : existing) {
            Integer id = claim.get(PortAllocationModel.ID);
            if (id == null || consumed.contains(id)) {
                continue;
            }
            Integer port = claim.get(PortAllocationModel.PORT);
            boolean matches = reusable == null
                && Objects.equals(claim.get(PortAllocationModel.SERVER_ID), serverId)
                && protocol.equals(claim.get(PortAllocationModel.PROTOCOL))
                && bind.equals(claim.get(PortAllocationModel.HOST_IP))
                && (publication.declaredHostPort() == null
                    || publication.declaredHostPort().equals(port));
            if (matches) {
                reusable = claim;
                consumed.add(id);
            }
        }
        if (reusable != null) {
            int port = reusable.get(PortAllocationModel.PORT);
            // Re-claim (replaces the own row, flipping a releasing park back to held).
            PortLedger.claimPreallocated(serverId, bind, port, protocol,
                InstanceModel.MODEL_ID, instanceId, null);
            return publication.withPreallocatedPort(port);
        }

        boolean localHost = isLocalServer(serverId);
        if (publication.declaredHostPort() != null) {
            int port = publication.declaredHostPort();
            claimOrRefuse(serverId, bind, port, protocol, instanceId, localHost);
            return publication.withPreallocatedPort(port);
        }

        int first = windowFirst();
        int count = windowCount();
        for (int port = first; port < first + count; port++) {
            if (PortLedger.conflictingHolder(serverId, bind, port, protocol) != null) {
                continue;
            }
            // AIDEV-NOTE: the OS probe runs AFTER the ledger check and only for the LOCAL
            // daemon -- isPortFree binds a LOCAL socket, so probing for a REMOTE host
            // answers a question about the controller. On a
            // remote host the ledger is the only pre-create evidence; a lie is caught by
            // the daemon's own EADDRINUSE at start, which surfaces as a loud deploy
            // refusal, never a silent success.
            if (localHost && !PortProbe.isFree(bind, port, protocol)) {
                continue;
            }
            try {
                PortLedger.claimPreallocated(serverId, bind, port, protocol,
                    InstanceModel.MODEL_ID, instanceId, null);
            } catch (PortLedger.PortConflict lost) {
                continue;   // a rival writer took it between the read and the insert
            }
            return publication.withPreallocatedPort(port);
        }
        throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("port_window_exhausted")
            .withArg("first", first)
            .withArg("count", count));
    }

    /**
     * Assert the DAEMON's binding matches the declaration, off its own inspect report:
     * the declared exposure's bind address, and (pre-allocated) the claimed number. A
     * mismatch STOPS the workload -- a loopback-declared workload publicly bound must
     * not keep serving -- and throws.
     *
     * @throws IOException naming exactly what the daemon bound versus what was declared
     */
    static void verifyPublished(InstanceService.@NonNull Resolved resolved,
                                @NonNull InstanceSpec spec, @NonNull InstanceStatus status)
            throws IOException {
        // EVERY declared publication is verified, not just the first: a spec that binds
        // two ports and gets one is a half-published workload reporting success.
        for (PortPublication publication : spec.publications()) {
            verifyOne(resolved, spec, status, publication);
        }
    }

    private static void verifyOne(InstanceService.@NonNull Resolved resolved,
                                  @NonNull InstanceSpec spec, @NonNull InstanceStatus status,
                                  @NonNull PortPublication publication) throws IOException {
        // A single-publication spec reads the FIRST binding, exactly as before: a status
        // synthesized without container-port identity (fakes, reused releases) has none to
        // match on, and demanding one would refuse deploys that used to work.
        InstanceStatus.PublishedPort observed = spec.publications().size() == 1
            ? (status.publishedPorts().isEmpty() ? null : status.publishedPorts().get(0))
            : status.publishedFor(publication.containerPort(), publication.protocol());

        String failure = null;
        if (observed == null) {
            failure = "the daemon reports NO published host port";
        } else if (publication.preallocatedPort() != null
                && publication.preallocatedPort() != observed.hostPort()) {
            failure = "the daemon bound host port " + observed.hostPort()
                + " instead of the pre-allocated " + publication.preallocatedPort();
        } else if (!bindMatches(publication, observed.bind())) {
            failure = "the daemon bound address '" + observed.bind()
                + "' instead of the declared "
                + (publication.publicExposure() ? "public (0.0.0.0)" : "loopback (127.0.0.1)")
                + " exposure";
        }
        if (failure == null) {
            return;
        }
        try {
            resolved.runtime().stop(spec.handle(), 5);
        } catch (IOException ignored) {
            // the refusal below is the outcome; a still-running mis-bound container is
            // exactly what the thrown message tells the operator to go look at
        }
        throw new IOException("Publication of '" + spec.handle() + "' ("
            + publication.containerPort() + "/" + publication.protocol() + ") failed"
            + " verification: " + failure + ". The workload was stopped.");
    }

    private static boolean bindMatches(@NonNull PortPublication publication,
                                       @Nullable String reported) {
        if (reported == null) {
            return false;
        }
        if (publication.publicExposure()) {
            return "0.0.0.0".equals(reported) || "::".equals(reported) || reported.isEmpty();
        }
        return "127.0.0.1".equals(reported);
    }

    // ORDER (the ledger doctrine): ledger first (a held claim gives the NAMED
    // refusal), OS probe second (sees consumers no ledger contains), claim last (the
    // unique key backstops the read-claim race with the same named refusal).
    private static void claimOrRefuse(int serverId, @NonNull String bind, int port,
                                      @NonNull String protocol, int instanceId,
                                      boolean localHost) {
        // The RIVAL is asked for by owner, never read off the first overlapping row: the
        // instance's own row can come back first while a rival's overlapping row survives.
        Row rival = PortLedger.rivalHolder(serverId, bind, port, protocol,
            InstanceModel.MODEL_ID, instanceId);
        if (rival != null) {
            throw conflictRefusal(port, rival, PortLedger.describeHolder(rival));
        }
        boolean reclaimingOwnRow =
            PortLedger.conflictingHolder(serverId, bind, port, protocol) != null;
        if (localHost && !reclaimingOwnRow && !PortProbe.isFree(bind, port, protocol)) {
            throw Violations.ofField("settings.host_port", port,
                HohenheimMicrocopy.VIOLATIONS.of("port_bound_on_host").withArg("port", port));
        }
        try {
            PortLedger.claimPreallocated(serverId, bind, port, protocol,
                InstanceModel.MODEL_ID, instanceId, null);
        } catch (PortLedger.PortConflict conflict) {
            throw conflictRefusal(port, conflict.getHolderClaim(), conflict.getHolder());
        }
    }

    /**
     * The refusal of a port another owner holds, naming that owner only to a caller who may read it
     * ({@link PortHolders#describedTo}); the full diagnostic goes to the log otherwise.
     */
    private static Violations conflictRefusal(int port, @Nullable Row holderClaim, @NonNull String diagnostic) {
        String holder = holderClaim == null ? null : PortHolders.describedTo(holderClaim);
        if (holder == null) {
            Blast.log("PORTS: host port", port, "is held by", diagnostic,
                "- refused without naming it to a caller who may not read that owner");
            return Violations.ofField("settings.host_port", port,
                HohenheimMicrocopy.VIOLATIONS.of("port_conflict_unnamed").withArg("port", port));
        }
        return Violations.ofField("settings.host_port", port,
            HohenheimMicrocopy.VIOLATIONS.of("port_conflict")
                .withArg("port", port)
                .withArg("holder", holder));
    }

    private static boolean isLocalServer(int serverId) {
        Row server = Models.get(ServerModel.class).findById(serverId);
        return HostMode.LOCAL.declaredBy(server);
    }

    /** The configured public window's first port, or the built-in 30000 when unusable. */
    /**
     * The configured first port of the pre-allocation window.
     *
     * AIDEV-NOTE: NO substitution any more. A privileged first port used to be swapped for
     * 30000 (said out loud on every allocation), which gave the operator a window they never
     * asked for: DNS and firewall rules written against their number pointed nowhere. The
     * SettingsRule on HohenheimSettings.Instances now refuses a missing, privileged or
     * overflowing window at every save and at boot, so an out-of-range value cannot reach
     * this read; if one somehow does, the allocation fails loudly instead of guessing.
     *
     * @throws IllegalStateException when the stored value is outside what the rule admits
     */
    private static int windowFirst() {
        Integer first = Zenit.SETTINGS_VALUES.getValue(
            HohenheimSettings.Instances.PUBLIC_PORT_FIRST);
        if (first == null || first <= 1024 || first > HohenheimSettings.Instances.MAX_PORT) {
            throw new IllegalStateException("instances.public_port_first = " + first
                + " is outside 1025-" + HohenheimSettings.Instances.MAX_PORT
                + ", which the settings rule refuses at save and at boot");
        }
        return first;
    }

    /**
     * The configured size of the pre-allocation window; see {@link #windowFirst} for why an
     * out-of-range value fails instead of being replaced.
     *
     * @throws IllegalStateException when the stored value is outside what the rule admits
     */
    private static int windowCount() {
        Integer count = Zenit.SETTINGS_VALUES.getValue(
            HohenheimSettings.Instances.PUBLIC_PORT_COUNT);
        if (count == null || count < 1) {
            throw new IllegalStateException("instances.public_port_count = " + count
                + " is not a positive window size, which the settings rule refuses at save"
                + " and at boot");
        }
        return count;
    }

}
