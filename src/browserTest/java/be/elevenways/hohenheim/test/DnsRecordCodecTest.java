package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.server.dns.DnsNames;
import be.elevenways.hohenheim.server.dns.DnsRecordCodec;
import be.elevenways.hohenheim.server.dns.DnsValueException;
import org.junit.jupiter.api.Test;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.TXTRecord;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure validation/conversion tests for the row-to-record codec and the
 * owner-name normalizer; no runtime boot needed.
 */
class DnsRecordCodecTest {

    private static Name origin() throws Exception {
        return Name.fromString("example.test.");
    }

    private static Record convert(String owner, String type, String value,
                                  Integer priority, Integer weight, Integer port) throws Exception {
        return DnsRecordCodec.toRecord(origin(), owner, type, 300, value, priority, weight, port);
    }

    @Test
    void normalizationCanonicalizesOwnersAndOrigins() {
        assertThat(DnsNames.normalizeOrigin("Example.COM.")).isEqualTo("example.com");
        assertThat(DnsNames.normalizeOrigin("*.example.com")).isNull();
        assertThat(DnsNames.normalizeOrigin("bad_label.example.com")).isNull();
        assertThat(DnsNames.normalizeOwner("")).isEqualTo("@");
        assertThat(DnsNames.normalizeOwner("@")).isEqualTo("@");
        assertThat(DnsNames.normalizeOwner("WWW.")).isEqualTo("www");
        assertThat(DnsNames.normalizeOwner("*.dev")).isEqualTo("*.dev");
        assertThat(DnsNames.normalizeOwner("_acme-challenge")).isEqualTo("_acme-challenge");
        assertThat(DnsNames.normalizeOwner("a.*.b")).isNull();
        assertThat(DnsNames.normalizeOwner("-bad")).isNull();
        assertThat(DnsNames.relative("example.com", "www.example.com")).isEqualTo("www");
        assertThat(DnsNames.relative("example.com", "example.com")).isEqualTo("@");
        assertThat(DnsNames.relative("example.com", "www.other.com")).isNull();
    }

    /**
     * RFC 4343: DNS name comparison is ASCII case-insensitive, never locale-sensitive.
     * The whole journey runs with the JVM default locale forced to Turkish, where the
     * no-argument toLowerCase() maps 'I' to dotless 'ı'.
     */
    @Test
    void nameFoldingIsAsciiOnlyInEveryLocale() {
        Locale previous = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));

        try {
            // 1. A zone origin spelled in capitals is a valid origin.
            assertThat(DnsNames.normalizeOrigin("WIKI.EXAMPLE.COM"))
                .as("uppercase origin canonicalizes")
                .isEqualTo("wiki.example.com");

            // 2. So is an owner label.
            assertThat(DnsNames.normalizeOwner("API")).as("uppercase owner canonicalizes").isEqualTo("api");
            assertThat(DnsNames.normalizeOwner("*.API")).as("wildcard owner canonicalizes").isEqualTo("*.api");

            // 3. A qname arriving in any case still resolves to its owner inside the zone.
            assertThat(DnsNames.relative("wiki.example.com", "API.wiki.example.com"))
                .as("uppercase qname maps to its relative owner")
                .isEqualTo("api");
            assertThat(DnsNames.relative("wiki.example.com", "API.WIKI.EXAMPLE.COM"))
                .as("fully uppercase qname maps to its relative owner")
                .isEqualTo("api");

            // 4. Which is what zone containment -- AXFR authorization, NOTIFY matching --
            //    rides on.
            assertThat(DnsNames.zoneContains("wiki.example.com", "API.WIKI.EXAMPLE.COM"))
                .as("uppercase qname is inside the zone")
                .isTrue();
            assertThat(DnsNames.zoneContains("wiki.example.com", "api.other.com"))
                .as("a name outside the zone stays outside")
                .isFalse();
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void addressValuesMustMatchTheRecordFamily() throws Exception {
        assertThat(convert("www", DnsRecordModel.TYPE_A, "192.0.2.1", null, null, null)).isNotNull();
        assertThatThrownBy(() -> convert("www", DnsRecordModel.TYPE_A, "2001:db8::1", null, null, null))
            .isInstanceOf(DnsValueException.class)
            .hasFieldOrPropertyWithValue("microcopyKey", "dns_ipv4_format");
        assertThatThrownBy(() -> convert("www", DnsRecordModel.TYPE_AAAA, "192.0.2.1", null, null, null))
            .isInstanceOf(DnsValueException.class)
            .hasFieldOrPropertyWithValue("microcopyKey", "dns_ipv6_format");
        assertThatThrownBy(() -> convert("www", DnsRecordModel.TYPE_A, "not-an-ip", null, null, null))
            .isInstanceOf(DnsValueException.class);
    }

    @Test
    void structuralRulesAreEnforced() {
        assertThatThrownBy(() -> convert("@", DnsRecordModel.TYPE_CNAME, "target.example.test", null, null, null))
            .isInstanceOf(DnsValueException.class)
            .hasFieldOrPropertyWithValue("microcopyKey", "dns_cname_apex");
        assertThatThrownBy(() -> convert("*", DnsRecordModel.TYPE_NS, "ns.example.test", null, null, null))
            .isInstanceOf(DnsValueException.class)
            .hasFieldOrPropertyWithValue("microcopyKey", "dns_ns_wildcard");
        assertThatThrownBy(() -> convert("mail", DnsRecordModel.TYPE_MX, "mx.example.test", null, null, null))
            .isInstanceOf(DnsValueException.class)
            .hasFieldOrPropertyWithValue("microcopyKey", "dns_priority_required");
        assertThatThrownBy(() -> convert("_svc._tcp", DnsRecordModel.TYPE_SRV, "host.example.test", 10, 0, null))
            .isInstanceOf(DnsValueException.class)
            .hasFieldOrPropertyWithValue("microcopyKey", "dns_port_required");
    }

    @Test
    void caaValuesParseFlagsTagAndValue() throws Exception {
        Record record = convert("@", DnsRecordModel.TYPE_CAA, "0 issue \"letsencrypt.org\"", null, null, null);
        assertThat(record.rdataToString()).contains("issue").contains("letsencrypt.org");
        assertThatThrownBy(() -> convert("@", DnsRecordModel.TYPE_CAA, "0 bogus value", null, null, null))
            .isInstanceOf(DnsValueException.class)
            .hasFieldOrPropertyWithValue("microcopyKey", "dns_caa_tag");
        assertThatThrownBy(() -> convert("@", DnsRecordModel.TYPE_CAA, "issue letsencrypt.org", null, null, null))
            .isInstanceOf(DnsValueException.class)
            .hasFieldOrPropertyWithValue("microcopyKey", "dns_caa_format");
    }

    @Test
    void longTxtValuesChunkWithoutBreakingCharacters() throws Exception {
        String ascii = "a".repeat(300);
        TXTRecord record = (TXTRecord) convert("txt", DnsRecordModel.TYPE_TXT, ascii, null, null, null);
        assertThat(record.getStrings().size()).isEqualTo(2);
        assertThat(String.join("", record.getStrings())).isEqualTo(ascii);

        // A 2-byte character at the 255-byte boundary must move to the next
        // chunk whole (dnsjava escapes non-ASCII bytes when printing).
        String boundary = "a".repeat(254) + "é";
        TXTRecord multiByte = (TXTRecord) convert("txt", DnsRecordModel.TYPE_TXT, boundary, null, null, null);
        assertThat(multiByte.getStrings().size()).isEqualTo(2);
        assertThat(String.valueOf(multiByte.getStrings().get(0))).isEqualTo("a".repeat(254));
        assertThat(String.valueOf(multiByte.getStrings().get(1))).isEqualTo("\\195\\169");
    }

    @Test
    void ttlBoundsAreChecked() throws Exception {
        assertThat(DnsRecordCodec.resolveTtl(null, 3600)).isEqualTo(3600);
        assertThat(DnsRecordCodec.resolveTtl(60, 3600)).isEqualTo(60);
        assertThatThrownBy(() -> DnsRecordCodec.resolveTtl(-1, 3600))
            .isInstanceOf(DnsValueException.class);
        assertThatThrownBy(() -> DnsRecordCodec.resolveTtl(999999999, 3600))
            .isInstanceOf(DnsValueException.class);
    }
}
