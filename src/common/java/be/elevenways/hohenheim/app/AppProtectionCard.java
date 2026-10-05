package be.elevenways.hohenheim.app;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * The Protection card of an app overview.
 *
 * @param protectUrl where a path is protected, null when this reader has nowhere to do it
 * @author Jelle De Loecker
 * @since  0.1.0
 */
@HawkeyeClass
public record AppProtectionCard(@NonNull List<AppProtection> rows, @Nullable String protectUrl) {
}
