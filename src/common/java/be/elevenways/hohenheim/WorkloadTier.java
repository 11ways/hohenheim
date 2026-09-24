package be.elevenways.hohenheim;

import be.elevenways.hawkeye.common.annotation.HawkeyeAutoLoad;
import be.elevenways.protoblast.common.dry.BlastDrySerializers;
import be.elevenways.protoblast.common.i18n.Microcopy;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Which population a workload on a host belongs to: the declaring home of the host workload-tier vocabulary.
 *
 * AIDEV-NOTE: WorkloadView carries the MEMBER, and the host overview's workloads card reads its label
 * off it ({@code workload.tier.label}), so no template branches over tier literals. The member crosses
 * the web boundary as its own name (the static initializer registers the DRY pair and the autoload
 * annotation forces TeaVM to run it), the HostState shape.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
@HawkeyeAutoLoad
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

    static {
        BlastDrySerializers.registerNameEnum(WorkloadTier.class);
    }
}
