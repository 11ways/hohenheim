package be.elevenways.hohenheim.app;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * The Addresses card of an app overview.
 *
 * @param addUrl where another address is added, null when this reader has nowhere to add one
 * @author Jelle De Loecker
 * @since  0.1.0
 */
@HawkeyeClass
public record AppAddressesCard(@NonNull List<AppAddress> rows, @Nullable String addUrl) {
}
