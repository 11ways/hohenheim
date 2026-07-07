package be.elevenways.hohenheim.server.docker;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;

/**
 * Optional container resource caps: memory in MiB and CPUs as a decimal
 * (1.5 = one and a half cores). Null or non-positive members mean "unlimited".
 */
public record ResourceLimits(@Nullable Integer memoryMb, @Nullable Double cpus) {

    private static final ResourceLimits NONE = new ResourceLimits(null, null);

    public static ResourceLimits none() {
        return NONE;
    }

    public static ResourceLimits of(@Nullable Integer memoryMb, @Nullable Double cpus) {
        return new ResourceLimits(memoryMb, cpus);
    }

    /** Read {@code memory_limit_mb} / {@code cpu_limit} out of a site-settings map. */
    public static ResourceLimits fromSettings(Map<String, Object> settings) {
        return new ResourceLimits(
            asInteger(settings.get("memory_limit_mb")),
            asDouble(settings.get("cpu_limit")));
    }

    /** Stamp Docker HostConfig entries (Memory bytes, NanoCpus) for the active caps. */
    public void applyTo(Map<String, Object> hostConfig) {
        if (memoryMb != null && memoryMb > 0) {
            hostConfig.put("Memory", memoryMb * 1024L * 1024L);
        }
        if (cpus != null && cpus > 0) {
            hostConfig.put("NanoCpus", (long) (cpus * 1_000_000_000L));
        }
    }

    private static @Nullable Integer asInteger(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static @Nullable Double asDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
