package be.elevenways.hohenheim.dns;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.zenit.common.routing.RouteTarget;
import org.checkerframework.checker.nullness.qual.Nullable;

/** One DNS record projected for the local or remote records table. */
@HawkeyeClass
public record DnsRecordView(
    String id,
    String name,
    String type,
    String ttl,
    String value,
    boolean enabled,
    boolean managed,
    @Nullable RouteTarget editTarget
) {
}
