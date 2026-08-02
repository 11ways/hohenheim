package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.server.dns.DnsZoneStore;
import be.elevenways.hohenheim.server.dns.GeneratedDnsRecords;
import be.elevenways.hohenheim.server.dns.InternalDnsTxtPublisher;
import be.elevenways.hohenheim.server.tls.DnsTxtRecord;
import be.elevenways.zenit.common.orm.activity.ActivityModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Attribution for DNS rows a system authored: derived in the write pipeline, refused when a
 * caller submits it, and carried by the real ACME challenge publisher.
 */
@TestMethodOrder(OrderAnnotation.class)
class GeneratedDnsRecordsTest extends HohenheimTestBase {

    private static final String ORIGIN = "generated.test";

    private static int zoneId;

    @BeforeAll
    static void seedZone() {
        var zoneModel = Models.get(DnsZoneModel.class);
        Row zone = zoneModel.createEmptyRow();
        zone.set(DnsZoneModel.ORIGIN, ORIGIN);
        zone.set(DnsZoneModel.ENABLED, true);
        zone.set(DnsZoneModel.DEFAULT_TTL, 3600);
        zone.set(DnsZoneModel.NEGATIVE_TTL, 300);
        zone.set(DnsZoneModel.SOA_REFRESH, 7200);
        zone.set(DnsZoneModel.SOA_RETRY, 3600);
        zone.set(DnsZoneModel.SOA_EXPIRE, 1209600);
        zoneModel.save(zone);
        zoneId = zone.get(DnsZoneModel.ID);
        DnsZoneStore.INSTANCE.reload();
    }

    /**
     * A caller cannot claim system ownership: submitting attribution is refused outright,
     * while the same write inside the system scope has it DERIVED.
     */
    @Test
    @Order(1)
    void attributionIsDerivedAndNeverSubmitted() throws Exception {
        var model = Models.get(DnsRecordModel.class);

        // 1. A hand-written marker is refused, and nothing lands.
        Row forged = txtRow("forged", "hand-written");
        forged.set(DnsRecordModel.GENERATED_BY, GeneratedDnsRecords.SOURCE_ACME);
        assertThatThrownBy(() -> model.save(forged))
            .describedAs("submitted attribution must be refused, not silently stripped")
            .isInstanceOf(Violations.class);
        assertThat(model.find().where(DnsRecordModel.NAME.eq("forged")).first())
            .describedAs("a refused write leaves no row behind")
            .isNull();

        // 2. The same is true for the OTHER three columns, so the refusal is not a
        //    single-column check that a caller can walk around.
        Row forgedAnchor = txtRow("forged-anchor", "hand-written");
        forgedAnchor.set(DnsRecordModel.GENERATED_FOR_ID, 1);
        assertThatThrownBy(() -> model.save(forgedAnchor)).isInstanceOf(Violations.class);

        // 3. An ordinary operator row saves fine and carries no attribution at all -- an
        //    unattributed row is the operator's, and is never a sweep candidate.
        Row operator = txtRow("operator", "plain");
        model.save(operator);
        Row storedOperator = model.findById(operator.get(DnsRecordModel.ID));
        assertThat((String) storedOperator.get(DnsRecordModel.GENERATED_BY))
            .describedAs("an operator write is never attributed to a system")
            .isNull();

        // 4. Inside the system scope, attribution is stamped from the SCOPE, and the value
        //    the caller staged is overwritten rather than trusted.
        Row generated = txtRow("generated", "system");
        generated.set(DnsRecordModel.GENERATED_BY, "someone-elses-token");
        GeneratedDnsRecords.as(new GeneratedDnsRecords.Attribution(
            GeneratedDnsRecords.SOURCE_ACME, "hohenheim:site_domain", 4242),
            () -> model.save(generated));

        Row storedGenerated = model.findById(generated.get(DnsRecordModel.ID));
        assertThat((String) storedGenerated.get(DnsRecordModel.GENERATED_BY))
            .describedAs("the scope decides the source, not the submitted value")
            .isEqualTo(GeneratedDnsRecords.SOURCE_ACME);
        assertThat((String) storedGenerated.get(DnsRecordModel.GENERATED_FOR_MODEL))
            .isEqualTo("hohenheim:site_domain");
        assertThat((Integer) storedGenerated.get(DnsRecordModel.GENERATED_FOR_ID))
            .describedAs("the declaring record is recorded, which is what a sweep checks")
            .isEqualTo(4242);
        assertThat((Instant) storedGenerated.get(DnsRecordModel.GENERATED_AT)).isNotNull();

        // 5. The scope leaves no residue: the very next plain write is refused again.
        Row after = txtRow("after-scope", "hand-written");
        after.set(DnsRecordModel.GENERATED_BY, GeneratedDnsRecords.SOURCE_ACME);
        assertThatThrownBy(() -> model.save(after))
            .describedAs("the ThreadLocal scope must not leak past its body")
            .isInstanceOf(Violations.class);
    }

    /** The real ACME publisher writes an attributed row under the system origin. */
    @Test
    @Order(2)
    void theAcmeChallengeRowIsAttributedAndSystemOriginated() throws Exception {
        var model = Models.get(DnsRecordModel.class);
        DnsTxtRecord challenge = new DnsTxtRecord("_acme-challenge." + ORIGIN, "digest-value");

        GeneratedDnsRecords.as(new GeneratedDnsRecords.Attribution(
            GeneratedDnsRecords.SOURCE_ACME, "hohenheim:site_domain", 7),
            () -> new InternalDnsTxtPublisher().publish(challenge));

        Row row = model.find()
            .where(DnsRecordModel.ZONE_ID.eq(zoneId))
            .where(DnsRecordModel.VALUE.eq("digest-value")).first();
        assertThat(row).describedAs("the publisher wrote its challenge row").isNotNull();
        assertThat((String) row.get(DnsRecordModel.MANAGED_BY))
            .describedAs("managed_by keeps its zone-file-import meaning")
            .isEqualTo(DnsRecordModel.MANAGED_BY_ACME);
        assertThat((String) row.get(DnsRecordModel.GENERATED_BY))
            .describedAs("generated_by is the enforcement column beside it")
            .isEqualTo(GeneratedDnsRecords.SOURCE_ACME);
        assertThat((Integer) row.get(DnsRecordModel.GENERATED_FOR_ID)).isEqualTo(7);

        // The write is attributed to the SYSTEM, not to whoever is logged in.
        Row activity = Models.get(ActivityModel.class).find()
            .where(ActivityModel.MODEL.eq(DnsRecordModel.MODEL_ID.toString()))
            .where(ActivityModel.RECORD_ID.eq(String.valueOf(row.get(DnsRecordModel.ID))))
            .orderBy(ActivityModel.ID, SortOrder.DESC).first();
        assertThat(activity).describedAs("the generated write is logged").isNotNull();
        assertThat((String) activity.get(ActivityModel.ORIGIN))
            .describedAs("a system write on a tenant's behalf attributes to the system")
            .isEqualTo(GeneratedDnsRecords.ORIGIN_ACME);
    }

    private static Row txtRow(String name, String value) {
        Row row = Models.get(DnsRecordModel.class).createEmptyRow();
        row.set(DnsRecordModel.ZONE_ID, zoneId);
        row.set(DnsRecordModel.NAME, name);
        row.set(DnsRecordModel.TYPE, DnsRecordModel.TYPE_TXT);
        row.set(DnsRecordModel.TTL, 60);
        row.set(DnsRecordModel.VALUE, value);
        row.set(DnsRecordModel.ENABLED, true);
        return row;
    }
}
