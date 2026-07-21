package be.elevenways.hohenheim.dns;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;

/** String-valued DNS record fields prepared for an HTML form. */
@HawkeyeClass
public record DnsRecordFormView(
    String id,
    String name,
    String type,
    String ttl,
    String value,
    String priority,
    String weight,
    String port,
    String enabled
) {
    public static DnsRecordFormView empty() {
        return new DnsRecordFormView("", "", "A", "", "", "", "", "", "true");
    }
}
