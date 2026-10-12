package be.elevenways.hohenheim.server.runtime;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * An optional driver lane a record surface shows only where the workload's driver offers it: the record's tabs and
 * overview cards ask this, never a kind's name.
 *
 * AIDEV-NOTE: each member names the capability interface its driver implements, so the fact is DERIVED from the
 * driver class ({@code InstanceKindHandler.driverOn}) and can never disagree with what the driver can do; asking needs
 * no daemon. The capability check where the lane is used ({@code InstanceFiles}, {@code InstanceStats}) stays the
 * enforcement.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public enum WorkloadLane {

    /** Browsing and editing the files inside the workload. */
    FILES(InstanceFileSupport.class),

    /** Configuration files written into the workload before it starts (a template's or the operator's). */
    CONFIG_FILES(FileStagingSupport.class),

    /** Live CPU, memory and network samples while somebody watches. */
    LIVE_STATS(StatsStreamSupport.class),

    /** The addresses the guest holds on its networks, as the daemon reports them. */
    GUEST_ADDRESSES(GuestAddressSupport.class);

    private final @NonNull Class<?> capability;

    WorkloadLane(@NonNull Class<?> capability) {
        this.capability = capability;
    }

    /** @return whether a driver of this class offers the lane; an unknown (null) driver offers none */
    public boolean offeredBy(@Nullable Class<? extends InstanceRuntime> driver) {
        return driver != null && this.capability.isAssignableFrom(driver);
    }
}
