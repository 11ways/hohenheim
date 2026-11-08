package be.elevenways.hohenheim.server.host;

import be.elevenways.hohenheim.AttentionSeverity;
import be.elevenways.protoblast.common.value.Percent;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * How full a disk is, as the attention items judge it: THE one home of the thresholds every disk warning uses.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public enum DiskFill {

    /** Below {@link #HIGH_FRACTION}: nothing to say. */
    ROOMY,

    /** Worth watching: backups start to fail soon. */
    HIGH,

    /** About to break rather than merely worth watching. */
    CRITICAL;

    /** From this fraction of the size in use a disk needs attention. */
    static final double HIGH_FRACTION = 0.85;

    /** From this fraction it is critical. */
    static final double CRITICAL_FRACTION = 0.95;

    /** @return the fill of {@code usedBytes} out of {@code totalBytes}; a disk of no known size is roomy */
    public static @NonNull DiskFill of(long usedBytes, long totalBytes) {
        if (totalBytes <= 0) {
            return ROOMY;
        }
        double fraction = (double) usedBytes / totalBytes;
        return fraction >= CRITICAL_FRACTION ? CRITICAL : fraction >= HIGH_FRACTION ? HIGH : ROOMY;
    }

    /** @return the share in use, rounded to a whole percent */
    public static @NonNull Percent percentUsed(long usedBytes, long totalBytes) {
        return Percent.of(totalBytes <= 0 ? 0 : Math.round(100.0 * usedBytes / totalBytes));
    }

    /** @return the severity an attention item about this disk raises, null when it raises none */
    public @Nullable AttentionSeverity severity() {
        return switch (this) {
            case ROOMY -> null;
            case HIGH -> AttentionSeverity.WARNING;
            case CRITICAL -> AttentionSeverity.ERROR;
        };
    }
}
