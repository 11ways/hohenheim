package be.elevenways.hohenheim.dns;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/** The public peer API response for a zone's records. */
@HawkeyeClass
public record DnsRecordListResponse(
    String zone,
    @Nullable Integer serial,
    List<DnsRecordDto> records
) {
}
