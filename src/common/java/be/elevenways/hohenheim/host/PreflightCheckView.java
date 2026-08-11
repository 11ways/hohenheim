package be.elevenways.hohenheim.host;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * One stored preflight check, exactly as {@code HostPreflight.store} persisted it.
 *
 * @param atIso when THIS verdict was produced -- can be older than {@code probed_at},
 *              because the store merges; null for a pre-stamp record
 */
@HawkeyeClass
public record PreflightCheckView(
    String name,
    String status,
    String statusVariant,
    boolean required,
    String detail,
    @Nullable String atIso
) {

    /** Build with the pl-badge variant derived from the stored status token. */
    public static PreflightCheckView of(String name, String status, boolean required,
                                        String detail, @Nullable String atIso) {
        String variant = switch (status) {
            case "pass" -> "success";
            case "warn" -> "warning";
            default -> "destructive";
        };
        return new PreflightCheckView(name, status, variant, required, detail, atIso);
    }
}
