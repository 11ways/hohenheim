package be.elevenways.hohenheim.server.runtime;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;

/**
 * The attribution half of the driver seam: who the daemon says a same-named workload
 * belongs to, decided from the owner labels stamped at create. This is the pre-flight
 * every cross-host move and every recovery deletion runs before touching a workload it
 * did not just create, and it is deliberately its OWN seam: a driver can answer the
 * ownership question without being able to export anything (the Docker driver), so
 * binding it to a snapshot capability made every non-Incus workload unmigratable.
 */
public interface WorkloadAttribution {

    /**
     * Who the daemon says the same-named workload belongs to. FOREIGN is the
     * handle-collision hazard made visible: a same-named workload another controller
     * owns must never be converged over or deleted.
     *
     * @throws IOException when the daemon cannot be asked (never folds to ABSENT)
     */
    @NonNull WorkloadClaim claimOf(@NonNull InstanceSpec spec) throws IOException;

    /** Attribution verdict of a same-named daemon workload. */
    enum WorkloadClaim { ABSENT, OURS, FOREIGN }
}
