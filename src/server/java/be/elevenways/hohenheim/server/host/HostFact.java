package be.elevenways.hohenheim.server.host;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.text.ByteText;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Locale;

/**
 * THE vocabulary of measured host facts: every fact a preflight battery stores is one of these, named by the catalog
 * entry {@code fact_<token>} (scope {@code host_check}) and read with its unit.
 *
 * AIDEV-NOTE: the token is the stored key in the host's capabilities and is read back by placement
 * ({@link #MEM_TOTAL}), so a member's token is part of the record format: never rename one. A stored key this build
 * does not declare (an older or newer controller wrote it) keeps its spelling and its raw value.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public enum HostFact {

    DOCKER_VERSION(Unit.TEXT),
    INCUS_VERSION(Unit.TEXT),
    API_VERSION(Unit.TEXT),
    KERNEL_VERSION(Unit.TEXT),
    OS(Unit.TEXT),
    OS_TYPE(Unit.TEXT),
    ARCHITECTURE(Unit.TEXT),
    NCPU(Unit.COUNT),
    /** The host's total memory in BYTES: the denominator of every placement decision on it. */
    MEM_TOTAL(Unit.BYTES),
    CGROUP_VERSION(Unit.TEXT),
    CGROUP_DRIVER(Unit.TEXT),
    CONTAINERS(Unit.COUNT),
    CONTAINERS_RUNNING(Unit.COUNT),
    IMAGES(Unit.COUNT),
    NETWORKS(Unit.COUNT),
    SERVER_NAME(Unit.TEXT),
    PROJECT(Unit.TEXT),
    DRIVER(Unit.TEXT),
    AUTH(Unit.TEXT),
    STORAGE_POOLS(Unit.TEXT),
    MANAGED_BRIDGE(Unit.TEXT),
    PROBE_IMAGE_FINGERPRINT(Unit.TEXT);

    /** How a fact's stored value reads. */
    public enum Unit {
        TEXT,
        COUNT,
        /** A byte count, read as a size through {@link ByteText}. */
        BYTES
    }

    private final Unit unit;

    HostFact(Unit unit) {
        this.unit = unit;
    }

    /** @return the stored key */
    public @NonNull String token() {
        return this.name().toLowerCase(Locale.ROOT);
    }

    public @NonNull Unit unit() {
        return this.unit;
    }

    /** @return the fact's name in words */
    public @NonNull Microcopy label() {
        return Microcopy.of("fact_" + this.token()).withFilter("scope", "host_check");
    }

    /** @return the stored value as a reader reads it */
    public @NonNull String valueText(@Nullable Object stored) {
        return switch (this.unit) {
            case BYTES -> stored instanceof Number ? ByteText.humanOf(stored) : String.valueOf(stored);
            case TEXT, COUNT -> String.valueOf(stored);
        };
    }

    /** @return the member stored under this key, or null for a key this build does not know */
    public static @Nullable HostFact ofToken(@Nullable String token) {
        for (HostFact fact : values()) {
            if (fact.token().equals(token)) {
                return fact;
            }
        }
        return null;
    }
}
