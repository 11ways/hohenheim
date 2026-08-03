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
        String posture = server.get(ServerModel.POSTURE);
        if (posture == null || ServerModel.POSTURE_TRUSTED_ONLY.equals(posture)) {
            throw Violations.ofForm(violation("host_posture_refuses")
                .withArg("name", String.valueOf((Object) server.get(ServerModel.NAME))));
        }
    }

    /**
     * The admit transition's own gate: only a host whose LAST preflight passed may be
     * admitted, so admission can never outrun verification.
     *
     * @throws Violations {@code admit_needs_preflight}
     */
    public static void requireAdmittable(@NonNull Row server) {
        if (!Boolean.TRUE.equals(server.get(ServerModel.PREFLIGHT_OK))) {
            throw Violations.ofForm(violation("admit_needs_preflight")
                .withArg("name", String.valueOf((Object) server.get(ServerModel.NAME))));
        }
    }

    private static Microcopy violation(String key) {
        return Microcopy.of(key).withFilter("scope", "violations");
    }
}
