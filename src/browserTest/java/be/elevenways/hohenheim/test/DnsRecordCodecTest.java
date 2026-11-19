package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.server.dns.DnsNames;
import be.elevenways.hohenheim.server.dns.DnsRecordCodec;
import be.elevenways.hohenheim.server.dns.DnsValueException;
import org.junit.jupiter.api.Test;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.TLSARecord;
import org.xbill.DNS.TXTRecord;

import java.util.HexFormat;
import java.util.List;
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

    /** TLSA: the RFC 6698 presentation form converts, is stored canonical, and every malformed part is refused. */
    @Test
    void tlsaValuesParseUsageSelectorMatchingTypeAndDigest() throws Exception {
        String digest = "0A1B2C3D".repeat(8);

        // 1. A DANE-EE SPKI SHA-256 record converts to the wire type with every field in place.
        TLSARecord record = (TLSARecord) convert("_25._tcp.mx", DnsRecordModel.TYPE_TLSA, "3 1 1 " + digest,
            null, null, null);
        assertThat(record.getCertificateUsage()).as("step 1: usage").isEqualTo(3);
        assertThat(record.getSelector()).as("step 1: selector").isEqualTo(1);
        assertThat(record.getMatchingType()).as("step 1: matching type").isEqualTo(1);
        assertThat(HexFormat.of().formatHex(record.getCertificateAssociationData()))
            .as("step 1: the association data").isEqualTo(digest.toLowerCase(Locale.ROOT));

        // 2. The stored spelling is canonical: single spaces, lower-case hex, a split digest joined.
        assertThat(DnsRecordCodec.storedValue(DnsRecordModel.TYPE_TLSA,
                " 3  1 1 " + digest.substring(0, 32) + " " + digest.substring(32)))
            .as("step 2: one spelling per record").isEqualTo("3 1 1 " + digest.toLowerCase(Locale.ROOT));
        assertThat(DnsRecordCodec.storedValue(DnsRecordModel.TYPE_TXT, " Kept As Typed "))
            .as("step 2: every other type keeps its value").isEqualTo(" Kept As Typed ");

        // 3. Out-of-range fields, bad hex and a digest of the wrong length are refused.
        for (String bad : List.of("4 1 1 " + digest, "3 2 1 " + digest, "3 1 3 " + digest, "255 1 1 " + digest,
                "3 1 1", "3 1 1 xyz0", "3 1 1 abc")) {
            assertThatThrownBy(() -> convert("_25._tcp.mx", DnsRecordModel.TYPE_TLSA, bad, null, null, null))
                .as("step 3: '%s' is refused", bad)
                .isInstanceOf(DnsValueException.class)
                .hasFieldOrPropertyWithValue("microcopyKey", "dns_tlsa_format");
        }
        assertThatThrownBy(() -> convert("_25._tcp.mx", DnsRecordModel.TYPE_TLSA, "3 1 2 " + digest,
                null, null, null))
            .as("step 3: a SHA-512 match needs 64 bytes")
            .isInstanceOf(DnsValueException.class)
            .hasFieldOrPropertyWithValue("microcopyKey", "dns_tlsa_length");
        assertThat(convert("_443._tcp.www", DnsRecordModel.TYPE_TLSA, "3 0 0 " + "ff".repeat(5), null, null, null))
            .as("step 3: a full-certificate match takes any length").isNotNull();
    }

    /** The one rule for underscore names: an attribute leaf answers to its host, and a TLSA owner has one shape. */
    @Test
    void attributeLeavesAnswerToTheHostTheyHangOff() {
        assertThat(DnsNames.attributeHost("_25._tcp.MX.example.com.")).isEqualTo("mx.example.com");
        assertThat(DnsNames.attributeHost("_dmarc.example.com")).isEqualTo("example.com");
        assertThat(DnsNames.attributeHost("_x.www.example.com")).isEqualTo("www.example.com");
        assertThat(DnsNames.attributeHost("www.example.com")).isEqualTo("www.example.com");
        assertThat(DnsNames.attributeHost("_only")).isEmpty();

        assertThat(DnsNames.isServicePortName("_25._tcp.mx.example.com")).isTrue();
        assertThat(DnsNames.isServicePortName("_443._udp.example.com")).isTrue();
        assertThat(DnsNames.isServicePortName("_5060._sctp.sip.example.com")).isTrue();
        assertThat(DnsNames.isServicePortName("mx.example.com")).as("no prefix").isFalse();
        assertThat(DnsNames.isServicePortName("_smtp._tcp.mx.example.com")).as("a service, not a port").isFalse();
        assertThat(DnsNames.isServicePortName("_25._quic.mx.example.com")).as("an unknown transport").isFalse();
        assertThat(DnsNames.isServicePortName("_70000._tcp.mx.example.com")).as("past the port range").isFalse();
        assertThat(DnsNames.isServicePortName("_25._tcp._x.example.com")).as("no host after the prefix")
            .isFalse();
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
