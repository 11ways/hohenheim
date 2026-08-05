package be.elevenways.hohenheim.server.host;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;

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
     * @throws Violations {@code host_not_admitted} or {@code host_posture_refuses}
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
        String posture = server.get(ServerModel.POSTURE);
        if (posture == null || ServerModel.POSTURE_TRUSTED_ONLY.equals(posture)) {
            throw Violations.ofForm(violation("host_posture_refuses")
                .withArg("name", String.valueOf((Object) server.get(ServerModel.NAME))));
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
        String pinned = server.get(ServerModel.HOST_KEY);
        if (pinned == null || pinned.isBlank()
            || !Boolean.TRUE.equals(server.get(ServerModel.HOST_KEY_VERIFIED))) {
            throw Violations.ofForm(violation("host_key_unverified")
                .withArg("name", String.valueOf((Object) server.get(ServerModel.NAME))));
        }
        // A confirmed pin the machine has since CONTRADICTED is not a verified identity.
        // The ssh client would refuse the connection anyway; refusing here means the
        // refusal is ours, named, and arrives before any work is spent on it.
        if (HostPins.isQuarantined(server)) {
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
     * AIDEV-NOTE: an SSH host specifically, because {@code host_key} is OVERLOADED by
     * runtime -- it holds an ssh key line for a docker/ssh host and an Incus server
     * certificate PEM for an incus/https one. Handing the latter to the ssh known_hosts
     * writer would materialise a line ssh can never match, i.e. a pin that silently
     * verifies nothing, so the mode check is load-bearing and not a formality. That
     * overloading is a pre-existing smell worth splitting into two columns one day.
     *
     * @throws Violations {@code host_not_ssh}, {@code host_key_unverified} or
     *         {@code host_quarantined}
     */
    public static void requireBackupDestination(@NonNull Row server) {
        if (!ServerModel.MODE_SSH.equals(server.get(ServerModel.MODE))
                || ServerModel.isIncus(server)) {
            // The local daemon has no wire identity to pin at all: a directory on the
            // controller is the FILESYSTEM kind, which says out loud that it shares the
            // controller's failure domain instead of pretending to be off-host.
            throw Violations.ofForm(violation("host_not_ssh")
                .withArg("name", String.valueOf((Object) server.get(ServerModel.NAME))));
        }
        requireVerifiedIdentity(server);
    }

    /**
     * The admit transition's own gate: only a host whose LAST preflight passed -- and
     * whose identity an operator confirmed -- may be admitted, so admission can never
     * outrun verification.
     *
     * @throws Violations {@code host_key_unverified} or {@code admit_needs_preflight}
     */
    public static void requireAdmittable(@NonNull Row server) {
        requireVerifiedIdentity(server);
        if (!Boolean.TRUE.equals(server.get(ServerModel.PREFLIGHT_OK))) {
            throw Violations.ofForm(violation("admit_needs_preflight")
                .withArg("name", String.valueOf((Object) server.get(ServerModel.NAME))));
        }
    }

    private static Microcopy violation(String key) {
        return Microcopy.of(key).withFilter("scope", "violations");
    }
}
