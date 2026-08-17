package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.server.auth.TenantWrites;
import be.elevenways.hohenheim.server.dns.DnsRecordCodec;
import org.junit.jupiter.api.Test;
import org.xbill.DNS.Name;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE binding between the DNS record-type vocabulary and every satellite that decides
 * something per type: nothing may know about a type the model does not declare, and no
 * declared type may reach a satellite that has no verdict for it.
 *
 * AIDEV-NOTE: adding a record type used to mean finding four places by memory -- the
 * model's ALL_TYPES list beside its own enum, the codec switch, TenantWrites' tenant
 * allow-list and the API's flat field list. Three of those failed SILENTLY for a type
 * they had not heard of (unauthorable, undeclared on the wire, its extras dropped); only
 * the codec refused loudly. This test is the thing that now fails first, naming the type.
 */
class DnsRecordTypeVocabularyTest {

    /** A minimally valid record per type, so the codec can be asked about every one. */
    private record Sample(String value, Integer priority, Integer weight, Integer port) {}

    private static final Map<String, Sample> SAMPLES = samples();

    @Test
    void everyDeclaredRecordTypeIsAnsweredByEverySatellite() throws Exception {
        // 1. The vocabulary IS the enum: ALL_TYPES is derived, not a parallel list, so a
        //    new .value(...) on TYPE is the only edit the vocabulary itself needs.
        List<String> types = DnsRecordModel.ALL_TYPES;
        assertThat(types)
            .as("step 1: ALL_TYPES is exactly the TYPE enum's declared keys, in order")
            .containsExactlyElementsOf(DnsRecordModel.TYPE.getValues().keySet());
        assertThat(types)
            .as("step 1: and it still carries the eight types this suite reasons about")
            .contains(DnsRecordModel.TYPE_A, DnsRecordModel.TYPE_AAAA,
                DnsRecordModel.TYPE_CNAME, DnsRecordModel.TYPE_NS, DnsRecordModel.TYPE_MX,
                DnsRecordModel.TYPE_TXT, DnsRecordModel.TYPE_CAA, DnsRecordModel.TYPE_SRV);

        // 2. THE CODEC: every declared type converts to a wire record. The codec's own
        //    default arm throws dns_type_unknown, so a type nobody taught it fails here.
        Name origin = Name.fromString("vocab.example.test.");
        for (String type : types) {
            Sample sample = SAMPLES.get(type);
            assertThat(sample)
                .as("step 2: record type '" + type + "' has no sample in this test -- add"
                    + " one so the new type is actually put through the codec")
                .isNotNull();
            assertThat(DnsRecordCodec.toRecord(origin, "www", type, 300, sample.value(),
                    sample.priority(), sample.weight(), sample.port()))
                .as("step 2: the codec converts '" + type + "' instead of refusing it as"
                    + " an unknown type")
                .isNotNull();
        }

        // 3. THE PEER API: every field any type's sub-schema declares must be carried on
        //    the flat wire, which is why RECORD_FIELDS is derived from those sub-schemas.
        assertThat(DnsRecordModel.DATA_FIELD_NAMES)
            .as("step 3: the per-type extras are read off the sub-schemas themselves")
            .containsExactlyInAnyOrder("priority", "weight", "port");
        assertThat(DnsRecordApiHandlers.RECORD_FIELDS)
            .as("step 3: and every one of them reaches the wire, alongside the columns")
            .containsAll(DnsRecordModel.DATA_FIELD_NAMES)
            .contains(DnsRecordModel.NAME.getName(), DnsRecordModel.TYPE.getName(),
                DnsRecordModel.VALUE.getName(), DnsRecordModel.TTL.getName(),
                DnsRecordModel.ENABLED.getName());

        // 4. TENANT AUTHORITY: every declared type is either tenant-writable or REFUSED on
        //    purpose. The allow-list already fails closed, so this catches the softer bug:
        //    a new type silently unauthorable because nobody decided.
        Set<String> decided = new LinkedHashSet<>(TenantWrites.RECORD_TYPES);
        decided.addAll(TenantWrites.REFUSED_RECORD_TYPES);
        assertThat(decided)
            .as("step 4: every record type carries a tenant verdict -- allow it in"
                + " TenantWrites.RECORD_TYPES or refuse it in REFUSED_RECORD_TYPES with"
                + " the reason")
            .containsExactlyInAnyOrderElementsOf(types);
        assertThat(TenantWrites.RECORD_TYPES)
            .as("step 4: and no type is both allowed and refused")
            .doesNotContainAnyElementsOf(TenantWrites.REFUSED_RECORD_TYPES);

        // 5. The refusals are the ones the threat model names, not an accident of order.
        assertThat(TenantWrites.REFUSED_RECORD_TYPES)
            .as("step 5: NS delegates a subtree, CAA controls issuance, MX repoints mail")
            .containsExactlyInAnyOrder(DnsRecordModel.TYPE_NS, DnsRecordModel.TYPE_CAA,
                DnsRecordModel.TYPE_MX);
    }

    private static Map<String, Sample> samples() {
        Map<String, Sample> samples = new LinkedHashMap<>();
        samples.put(DnsRecordModel.TYPE_A, new Sample("192.0.2.10", null, null, null));
        samples.put(DnsRecordModel.TYPE_AAAA, new Sample("2001:db8::10", null, null, null));
        samples.put(DnsRecordModel.TYPE_CNAME, new Sample("target.example.test.", null, null, null));
        samples.put(DnsRecordModel.TYPE_NS, new Sample("ns1.example.test.", null, null, null));
        samples.put(DnsRecordModel.TYPE_MX, new Sample("mail.example.test.", 10, null, null));
        samples.put(DnsRecordModel.TYPE_TXT, new Sample("v=spf1 -all", null, null, null));
        samples.put(DnsRecordModel.TYPE_CAA, new Sample("0 issue \"letsencrypt.org\"", null, null, null));
        samples.put(DnsRecordModel.TYPE_SRV, new Sample("sip.example.test.", 10, 5, 5060));
        return samples;
    }
}
