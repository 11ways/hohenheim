package be.elevenways.hohenheim.dns;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import org.checkerframework.checker.nullness.qual.Nullable;

/** A field validation refusal from the public DNS peer API. */
@HawkeyeClass
public record DnsValidationErrorResponse(
    @Nullable String error,
    @Nullable String field,
    @Nullable String key,
    @Nullable String message
) {
}
