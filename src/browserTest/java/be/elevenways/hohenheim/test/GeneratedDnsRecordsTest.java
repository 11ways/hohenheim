package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.server.dns.DnsZoneStore;
import be.elevenways.hohenheim.server.dns.GeneratedDnsRecords;
import be.elevenways.hohenheim.server.dns.InternalDnsTxtPublisher;
import be.elevenways.hohenheim.server.tls.DnsTxtRecord;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.ApiKeyService;
import be.elevenways.zenit.auth.server.AuthModels;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;

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

    /**
     * A generated row is un-editable and un-deletable through every caller path, while the
     * system that authored it still removes its own row.
     */
    @Test
    @Order(3)
    void aGeneratedRowIsImmutableToCallersButNotToItsAuthor() throws Exception {
        var model = Models.get(DnsRecordModel.class);
        DnsTxtRecord challenge = new DnsTxtRecord("_acme-challenge." + ORIGIN, "immutable-value");
        GeneratedDnsRecords.as(new GeneratedDnsRecords.Attribution(
            GeneratedDnsRecords.SOURCE_ACME, "hohenheim:site_domain", 9),
            () -> new InternalDnsTxtPublisher().publish(challenge));

        Row generated = model.find()
            .where(DnsRecordModel.ZONE_ID.eq(zoneId))
            .where(DnsRecordModel.VALUE.eq("immutable-value")).first();
        int generatedId = generated.get(DnsRecordModel.ID);

        // 1. A direct edit is refused at the write funnel, whatever the caller changes.
        generated.set(DnsRecordModel.VALUE, "hijacked");
        assertThatThrownBy(() -> model.save(generated))
            .describedAs("an in-flight challenge row must not be editable")
            .isInstanceOf(Violations.class);

        // 2. A direct delete is refused at the remove funnel, criteria delete included.
        assertThatThrownBy(() -> model.find().where(DnsRecordModel.ID.eq(generatedId)).delete())
            .describedAs("an in-flight challenge row must not be deletable")
            .isInstanceOf(Violations.class);
        assertThat(model.findById(generatedId))
            .describedAs("the refused delete left the row in place")
            .isNotNull();

        // 3. The peer/automation API is the path that could kill an order mid-flight: it
        //    resolves a record by ZONE MEMBERSHIP alone, so an API key holding the admin
        //    permission reached every generated row in every hosted zone.
        String apiKey = ApiKeyService.create(
            AuthModels.users().find().where(UserModel.EMAIL.eq("test@hohenheim.local")).first()
                .get(UserModel.ID),
            "generated-dns-test", List.of("hohenheim.*"), null).plaintext();

        HttpResponse<String> edited = apiPost(apiKey,
            "/api/dns/zones/" + ORIGIN + "/records/" + generatedId, "value=192.0.2.9");
        assertThat(edited.statusCode())
            .describedAs("the API refuses the edit as a violation, not a 500")
            .isEqualTo(422);

        HttpResponse<String> deleted = apiPost(apiKey,
            "/api/dns/zones/" + ORIGIN + "/records/" + generatedId + "/delete", "");
        assertThat(deleted.statusCode()).isEqualTo(422);
        assertThat((String) model.findById(generatedId).get(DnsRecordModel.VALUE))
            .describedAs("neither API call moved the row")
            .isEqualTo("immutable-value");

        // 4. The author still cleans up after itself -- the guard is about CALLERS.
        GeneratedDnsRecords.as(new GeneratedDnsRecords.Attribution(
            GeneratedDnsRecords.SOURCE_ACME, "hohenheim:site_domain", 9),
            () -> new InternalDnsTxtPublisher().cleanup(challenge));
        assertThat(model.findById(generatedId))
            .describedAs("the system removes the row it published")
            .isNull();
    }

    private HttpResponse<String> apiPost(String apiKey, String path, String body) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).build();
        return client.send(HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + getServerPort() + path))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(), HttpResponse.BodyHandlers.ofString());
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
