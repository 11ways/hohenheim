package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.PortAllocationModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import be.elevenways.hohenheim.server.runtime.InstanceStatus;
import be.elevenways.hohenheim.server.runtime.PortPublication;
import be.elevenways.hohenheim.server.util.PortProbe;
import be.elevenways.protoblast.common.i18n.Microcopy;
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
 * wide with an image pull inside it (the plan's fork 2, verbatim). Its honesty conditions
 * are exactly two and both live here: the claim is written BEFORE the container is
 * created (so a crash inside the window leaves a row the reconciler can judge, never an
 * unclaimed bound port), and the daemon's OWN binding is read back after start and
 * compared against the claim -- a mismatch stops the workload rather than reporting a
 * deploy that "succeeded" on a bind nothing declared.
 */
final class PortPublications {

    private PortPublications() {
    }

    /**
     * Ensure the ledger state matches the spec's declared publication, BEFORE the
     * container is created: a pre-allocation-shaped publication gets its host port
     * claimed (reusing the owner's existing reservation when the shape still matches),
     * and reservations the declaration no longer wants are parked for the reconciler.
     *
     * @return the spec to create the container from (carries the claimed host port)
     * @throws Violations naming the conflict or the exhausted window
     */
    static @NonNull InstanceSpec ensureClaimed(InstanceService.@NonNull Resolved resolved,
                                               int instanceId) {
        InstanceSpec spec = resolved.spec();
        boolean anyPreallocated = false;
        for (PortPublication publication : spec.publications()) {
            anyPreallocated |= publication.requiresPreallocation();
        }
        if (!anyPreallocated) {
            // The declaration no longer wants a reservation; end any leftover one the
            // OBSERVED way (the reconciler deletes each row once it sees the port free).
            PortLedger.parkPreallocatedClaims(InstanceModel.MODEL_ID, instanceId);
            return spec;
        }
        // The reusable-claim scan below matches ONE publication against the owner's
        // existing reservations, so a multi-publication spec resolves them in declaration
        // order and each claim is consumed at most once (two publications of the same
        // shape must never both resolve to the same reserved number).
        List<PortPublication> claimed = new ArrayList<>();
        Set<Integer> consumed = new HashSet<>();
        List<Row> existing = PortLedger.preallocatedClaimsOf(InstanceModel.MODEL_ID, instanceId);
        for (PortPublication publication : spec.publications()) {
            claimed.add(publication.requiresPreallocation()
                ? claimOne(resolved, instanceId, publication, existing, consumed)
                : publication);
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

    /** Resolve ONE pre-allocation-shaped publication to a held claim. */
    private static @NonNull PortPublication claimOne(InstanceService.@NonNull Resolved resolved,
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
                && Objects.equals(claim.get(PortAllocationModel.SERVER_ID), resolved.serverId())
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
            PortLedger.claimPreallocated(resolved.serverId(), bind, port, protocol,
                InstanceModel.MODEL_ID, instanceId, null);
            return publication.withPreallocatedPort(port);
        }

        boolean localHost = isLocalServer(resolved.serverId());
        if (publication.declaredHostPort() != null) {
            int port = publication.declaredHostPort();
            claimOrRefuse(resolved.serverId(), bind, port, protocol, instanceId, localHost);
            return publication.withPreallocatedPort(port);
        }

        int first = windowFirst();
        int count = windowCount();
        for (int port = first; port < first + count; port++) {
            if (PortLedger.conflictingHolder(resolved.serverId(), bind, port, protocol) != null) {
                continue;
            }
            // AIDEV-NOTE: the OS probe runs AFTER the ledger check and only for the LOCAL
            // daemon -- isPortFree binds a LOCAL socket, so probing for a REMOTE host
            // answers a question about the controller (the plan's fork 2, verbatim). On a
            // remote host the ledger is the only pre-create evidence; a lie is caught by
            // the daemon's own EADDRINUSE at start, which surfaces as a loud deploy
            // refusal, never a silent success.
            if (localHost && !PortProbe.isFree(bind, port, protocol)) {
                continue;
            }
            try {
                PortLedger.claimPreallocated(resolved.serverId(), bind, port, protocol,
                    InstanceModel.MODEL_ID, instanceId, null);
            } catch (PortLedger.PortConflict lost) {
                continue;   // a rival writer took it between the read and the insert
            }
            return publication.withPreallocatedPort(port);
        }
        throw Violations.ofForm(violationText("port_window_exhausted")
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
        // match on, and demanding one would refuse deploys that were fine for two waves.
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

    // ORDER (the PortAllocator doctrine): ledger first (a held claim gives the NAMED
    // refusal), OS probe second (sees consumers no ledger contains), claim last (the
    // unique key backstops the read-claim race with the same named refusal).
    private static void claimOrRefuse(int serverId, @NonNull String bind, int port,
                                      @NonNull String protocol, int instanceId,
                                      boolean localHost) {
        Row holder = PortLedger.conflictingHolder(serverId, bind, port, protocol);
        if (holder != null && !PortLedger.isOwnedBy(holder, InstanceModel.MODEL_ID, instanceId)) {
            throw conflictRefusal(port, PortLedger.describeHolder(holder));
        }
        boolean reclaimingOwnRow = holder != null;
        if (localHost && !reclaimingOwnRow && !PortProbe.isFree(bind, port, protocol)) {
            throw Violations.ofField("settings.host_port", port,
                violationText("port_bound_on_host").withArg("port", port));
        }
        try {
            PortLedger.claimPreallocated(serverId, bind, port, protocol,
                InstanceModel.MODEL_ID, instanceId, null);
        } catch (PortLedger.PortConflict conflict) {
            throw conflictRefusal(port, conflict.getHolder());
        }
    }

    private static Violations conflictRefusal(int port, @NonNull String holder) {
        return Violations.ofField("settings.host_port", port,
            violationText("port_conflict")
                .withArg("port", port)
                .withArg("holder", holder));
    }

    private static boolean isLocalServer(int serverId) {
        Row server = Models.get(ServerModel.class).findById(serverId);
        return server != null && ServerModel.MODE_LOCAL.equals(server.get(ServerModel.MODE));
    }

    private static int windowFirst() {
        Integer first = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Instances.PUBLIC_PORT_FIRST);
        return first == null || first <= 1024 ? 30000 : first;
    }

    private static int windowCount() {
        Integer count = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Instances.PUBLIC_PORT_COUNT);
        return count == null || count <= 0 ? 2000 : count;
    }

    private static Microcopy violationText(String key) {
        return Microcopy.of(key).withFilter("scope", "violations");
    }
}
