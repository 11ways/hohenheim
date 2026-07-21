package be.elevenways.hohenheim.dns;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import org.checkerframework.checker.nullness.qual.Nullable;

/** One DNS record on the public peer API wire. */
@HawkeyeClass
public record DnsRecordDto(
    Integer id,
    String name,
    String type,
    @Nullable Integer ttl,
    String value,
    @Nullable Integer priority,
    @Nullable Integer weight,
    @Nullable Integer port,
    boolean enabled,
    @Nullable String managed_by
) {
}
