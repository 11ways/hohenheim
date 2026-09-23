package be.elevenways.hohenheim;

import be.elevenways.hawkeye.common.annotation.Arg;
import be.elevenways.hawkeye.common.annotation.HawkeyeFunction;
import be.elevenways.protoblast.common.i18n.Microcopy;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Which population a workload on a host belongs to: the declaring home of the host workload-tier vocabulary.
 *
 * AIDEV-NOTE: the host overview's workloads card reads the tier's label off the member
 * ({@code WorkloadTiers.of(workload.tier).label}), so no template branches over tier literals. The keys
 * are the strings WorkloadView.tier already carries; once that view carries the member itself the
 * template reads {@code workload.tier.label} and {@link #of} can go.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public enum WorkloadTier {

    INSTANCE("instance", Microcopy.of("tier_instance").withFilter("scope", "server_overview")),
    STACK("stack", Microcopy.of("tier_stack").withFilter("scope", "server_overview")),
    DATABASE("database", Microcopy.of("tier_database").withFilter("scope", "server_overview")),
    DATABASE_ENGINE("database_engine", Microcopy.of("tier_database_engine").withFilter("scope", "server_overview"));

    private final String key;
    private final Microcopy label;

    WorkloadTier(@NonNull String key, @NonNull Microcopy label) {
        this.key = key;
        this.label = label;
    }

    /** @return the tier's wire token */
    public @NonNull String key() {
        return this.key;
    }

    /** @return the tier's name in the workloads table */
    public @NonNull Microcopy label() {
        return this.label;
    }

    /**
     * The member a tier token names.
     *
     * @throws IllegalArgumentException for an unknown token: an unknown tier fails closed instead of
     *         rendering under a neighbour's name
     */
    @HawkeyeFunction(
        name = "of",
        namespace = "WorkloadTiers",
        description = "The workload tier a tier token names",
        returnType = WorkloadTier.class,
        returnsReference = false,
        arguments = {
            @Arg(name = "key", required = true, type = String.class, expectsReference = false,
                 description = "The tier token a workload view carries")
        }
    )
    public static @NonNull WorkloadTier of(@NonNull String key) {
        for (WorkloadTier tier : values()) {
            if (tier.key.equals(key)) {
                return tier;
            }
        }
        throw new IllegalArgumentException("Unknown workload tier: " + key);
    }
}
