package be.elevenways.hohenheim.server.host;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.HostTrustSlot;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.incus.IncusKernelIsolation;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.time.Duration;
import java.time.Instant;

/**
 * The placement gate the host record exists for: NEW tenant-authored workloads
 * (the instance tier) land only on a host an operator ADMITTED after a passing
 * preflight, with a posture that accepts hostile containers. Stop/destroy are
 * never gated -- cleanup must always be possible.
 */
public final class HostAdmission {

    private HostAdmission() {
    }

    /**
     * Refuse instance placement on a host that is not admitted or whose posture does
     * not accept a hostile container workload.
     *
     * @throws Violations {@code host_not_admitted}, {@code host_posture_refuses},
     *         {@code host_contact_lapsed} or one of {@link #requireKernelTruth}'s refusals
     */
    public static void requireInstancePlacement(int serverId) {
        Row server = Models.get(ServerModel.class).findById(serverId);
        if (server == null) {
            throw Violations.ofForm(violation("host_not_admitted")
                .withArg("name", String.valueOf(serverId)));
        }
        String admission = server.get(ServerModel.ADMISSION);
        if (!ServerModel.ADMISSION_ADMITTED.equals(admission)) {
            throw Violations.ofForm(violation("host_not_admitted")
                .withArg("name", String.valueOf((Object) server.get(ServerModel.NAME))));
        }
        requireVerifiedIdentity(server);
        if (!ServerModel.acceptsTenantWorkloads(server)) {
            throw Violations.ofForm(violation("host_posture_refuses")
                .withArg("name", String.valueOf((Object) server.get(ServerModel.NAME))));
        }
        requireKernelTruth(server);
        requireRecentContact(server);
    }

    /**
     * A host that has not ANSWERED inside the declared bound takes no new workload.
     *
     * AIDEV-NOTE: the heartbeat had no reader with an age bound at all. Every sweep that
     * reaches a daemon stamps {@code last_seen_at} ({@code ReapIncusControllers} every 15
     * minutes, {@code DockerReconciler} hourly, plus preflight and the live overview) and
     * nothing anywhere compared it to a clock -- so a host that stopped answering stayed
     * {@code admitted} and kept receiving placements, with a status string no code read as
     * its only trace.
     *
     * REFUSE rather than deprioritise, and the reason it is not the outage it looks like:
     * this refuses ONE host, and {@link be.elevenways.hohenheim.server.instance.InstancePlacement}
     * walks every host, so a lapsed host moves the placement instead of failing it. The
     * deploy onto a silent daemon was going to fail anyway -- after a record existed and
     * capacity was booked -- and a named refusal at the chooser is strictly better than a
     * broken deploy. Deprioritising cannot deliver that: a soft score still lands the
     * workload there when it is the only host of its runtime, which is exactly the case
     * this exists for. The remaining risk -- a stopped sweeper lapsing every host at once
     * -- is bounded by a default three times the slowest write cadence and by 0 meaning
     * "no bound".
     *
     * A host that has NEVER been contacted is not lapsed: {@code last_seen_at} is null
     * there, and admission answers for it already ({@code admit_needs_preflight} cannot
     * pass without a preflight, and a passing preflight stamps the column).
     *
     * @throws Violations {@code host_contact_lapsed}
     */
    public static void requireRecentContact(@NonNull Row server) {
        Integer minutes = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Hosts.CONTACT_MAX_AGE_MINUTES);
        if (minutes == null || minutes <= 0) {
            return;
        }
        Instant lastSeen = server.get(ServerModel.LAST_SEEN_AT);
        if (lastSeen == null
                || lastSeen.isAfter(Instant.now().minus(Duration.ofMinutes(minutes)))) {
            return;
        }
        throw Violations.ofForm(violation("host_contact_lapsed")
            .withArg("name", String.valueOf((Object) server.get(ServerModel.NAME)))
            .withArg("minutes", Duration.between(lastSeen, Instant.now()).toMinutes()));
    }

    /**
     * A host that accepts tenant workloads must be able to PROVE its workload isolation in
     * the kernel it runs them on.
     *
     * AIDEV-NOTE: the decision (2026-08-07) that closes the last open Phase 8 item.
     * Kernel-truth verification was optional, so an Incus host could accept hostile
     * tenants while nothing ever read the daemon host's nftables -- and upstream's
     * failed-detach race means a workload's declared isolation and its actual isolation
     * genuinely disagree at a measured 2-in-34. Optional verification of a boundary that
     * demonstrably breaks is not a diagnostic gap, it is an unwatched boundary.
     *
     * AIDEV-NOTE: the gate is on ADMISSION (may tenant workloads land here), never on
     * trust. A storage-only or operator-only host still enrols, confirms and serves as a
     * backup destination with no lane at all -- that is the recorded backup-target
     * decision, and {@link #requireBackupDestination} deliberately does not call this.
     *
     * AIDEV-NOTE: it is checked LIVE at every placement rather than backfilled onto the
     * admission column. Silently cordoning an already-admitted production host at deploy
     * time is an availability decision no migration may take on an operator's behalf; a
     * live gate stops NEW tenant workloads by name while running ones and every cleanup
     * path stay untouched, the operator sees the reason on the host form
     * ({@code kernel_isolation_state}) and the next preflight turns the check REQUIRED and
     * flips the stored verdict. Nothing is silent and nothing is destroyed.
     *
     * AIDEV-NOTE: DOCKER hosts pass through. Their nft lane is proven by the preflight's
     * own required {@code nftables} check, which has always failed a host it cannot
     * transact on -- adding a second gate over the same evidence would be theater.
     *
     * @throws Violations {@code host_kernel_lane_missing} or {@code host_kernel_lane_unproven}
     */
    public static void requireKernelTruth(@NonNull Row server) {
        if (!ServerModel.isIncus(server) || !ServerModel.acceptsTenantWorkloads(server)) {
            return;
        }
        String name = String.valueOf((Object) server.get(ServerModel.NAME));
        if (!IncusKernelIsolation.laneAvailable(server)) {
            throw Violations.ofForm(violation("host_kernel_lane_missing").withArg("name", name));
        }
        if (!HostPreflight.STATUS_PASS.equals(
                HostPreflight.storedCheckStatus(server, IncusPreflight.KERNEL_LANE_CHECK))) {
            throw Violations.ofForm(violation("host_kernel_lane_unproven").withArg("name", name));
        }
    }

    /**
     * A remote host must have a pinned host key an operator CONFIRMED out of band.
     * Checked at placement as well as at admit: the admission column is a stored
     * decision, the pin is the thing that decides whether the machine we would be
     * talking to is the machine that decision was about.
     *
     * @throws Violations {@code host_key_unverified} or {@code host_quarantined}
     */
    public static void requireVerifiedIdentity(@NonNull Row server) {
        // Remote transports only: docker-over-ssh AND incus-over-https both pin; the two
        // local-socket shapes have no wire identity to verify.
        if (!ServerModel.requiresPinnedIdentity(server)) {
            return;
        }
        requireTrustedSlot(server, HostTrustSlot.transportOf(server));
    }

    /**
     * The gate one named trust relationship passes: pinned, operator-CONFIRMED and not
     * quarantined. An Incus host carries two of these -- the TLS transport it is driven
     * over and, optionally, the ssh admin lane kernel-truth verification reads through --
     * and each answers for itself.
     *
     * @throws Violations {@code host_key_unverified} or {@code host_quarantined}
     */
    public static void requireTrustedSlot(@NonNull Row server, @NonNull HostTrustSlot slot) {
        if (!slot.isConfirmed(server)) {
            throw Violations.ofForm(violation("host_key_unverified")
                .withArg("name", String.valueOf((Object) server.get(ServerModel.NAME))));
        }
        // A confirmed pin the machine has since CONTRADICTED is not a verified identity.
        // The ssh client would refuse the connection anyway; refusing here means the
        // refusal is ours, named, and arrives before any work is spent on it.
        if (HostPins.isQuarantined(server, slot)) {
            throw Violations.ofForm(violation("host_quarantined")
                .withArg("name", String.valueOf((Object) server.get(ServerModel.NAME))));
        }
    }

    /**
     * The gate a BACKUP DESTINATION passes: a host whose identity is pinned, confirmed
     * out of band and not quarantined -- and nothing else.
     *
     * AIDEV-NOTE: THE storage-host-versus-compute-host decision, and it needs no new
     * vocabulary because the host record already carries two INDEPENDENT axes. TRUST
     * (pin + operator confirmation + quarantine) answers "is this the machine we think
     * it is"; ADMISSION (preflight verdict + admit/cordon + posture) answers "may
     * tenant workloads land here". A backup destination is a question of trust only, so
     * a storage-only host never needs a container runtime, a passing compute preflight
     * or an admit -- it stays {@code blocked} forever and placement keeps skipping it
     * for exactly that reason. Adding a third {@code purpose}/role column would create a
     * SECOND authority over "may workloads land here" next to admission, which is the
     * very duplication this whole seam was rewritten to remove.
     *
     * AIDEV-NOTE: an SSH-LANE host specifically, and it is the ssh SLOT that is checked,
     * never the host's primary transport. Until M074 that read {@code MODE_SSH &&
     * !isIncus}, because {@code host_key} was overloaded by runtime and handing an Incus
     * certificate PEM to the known_hosts writer would materialise a line ssh can never
     * match -- a pin that silently verifies nothing. The columns are split now, so the
     * question is simply whether this host declares a shell we have pinned trust on; an
     * Incus host that also carries an admin lane is a legitimate destination.
     *
     * @throws Violations {@code host_not_ssh}, {@code host_key_unverified} or
     *         {@code host_quarantined}
     */
    public static void requireBackupDestination(@NonNull Row server) {
        if (!ServerModel.hasSshLane(server)) {
            // The local daemon has no wire identity to pin at all: a directory on the
            // controller is the FILESYSTEM kind, which says out loud that it shares the
            // controller's failure domain instead of pretending to be off-host.
            throw Violations.ofForm(violation("host_not_ssh")
                .withArg("name", String.valueOf((Object) server.get(ServerModel.NAME))));
        }
        requireTrustedSlot(server, HostTrustSlot.SSH);
    }

    /**
     * The admit transition's own gate: only a host whose LAST preflight passed -- and
     * whose identity an operator confirmed -- may be admitted, so admission can never
     * outrun verification.
     *
     * @throws Violations {@code host_key_unverified}, {@code host_kernel_lane_missing},
     *         {@code host_kernel_lane_unproven} or {@code admit_needs_preflight}
     */
    public static void requireAdmittable(@NonNull Row server) {
        requireVerifiedIdentity(server);
        // BEFORE the stored verdict: a failing kernel-lane check already sinks
        // preflight_ok, and "run preflight first" is the wrong thing to tell an operator
        // whose preflight ran and named this exact reason.
        requireKernelTruth(server);
        if (!Boolean.TRUE.equals(server.get(ServerModel.PREFLIGHT_OK))) {
            throw Violations.ofForm(violation("admit_needs_preflight")
                .withArg("name", String.valueOf((Object) server.get(ServerModel.NAME))));
        }
    }

    private static Microcopy violation(String key) {
        return Microcopy.of(key).withFilter("scope", "violations");
    }
}
