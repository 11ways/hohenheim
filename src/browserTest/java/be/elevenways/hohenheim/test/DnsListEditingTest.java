package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.cms.common.render.inline.InlineEditResult;
import be.elevenways.zenit.cms.common.render.inline.InlineEditState;
import be.elevenways.zenit.cms.common.render.inline.InlineEditSubmit;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.forms.common.choose.InlineCreateResult;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The DNS record list surfaces: the three write lanes (full form, quick-add bar,
 * inline cell) all funnelling through the record codec and the zone serial bump,
 * the zone's Records tab rendering the resource's own declarations, and what a
 * delegated tenant may reach on each lane.
 */
class DnsListEditingTest extends HohenheimTestBase {

    private static final String RECORD_SOURCE = "/zf/source/hohenheim.dns_record/create";

    /**
     * ONE walk down all three write lanes. Every lane submits the SAME invalid record
     * first: a lane that persisted it would be a lane that skipped
     * {@code DnsRecordEdits.validate}, and a lane that skipped the resource's own
     * write funnel would leave the zone serial behind (so DNS would keep serving the
     * old answer to every secondary).
     */
    @Test
    void threeWriteLanesFunnelThroughTheCodecAndBumpTheSerial() throws Exception {
        int zoneId = createZone("lanes.example");
        long serial = zoneSerial(zoneId);

        // 1. The full form refuses a value the codec cannot turn into an A record.
        adminForm("/admin/dns-records/new",
            "zone_id=" + zoneId + "&name=one&type=A&value=not-an-ip&ttl=300&enabled=true");
        assertThat(record(zoneId, "one")).as("step 1: the form lane refused the record").isNull();
        assertThat(zoneSerial(zoneId)).as("step 1: a refusal never bumps the serial").isEqualTo(serial);

        // 2. The same lane with a valid value writes, and the zone's serial moves so
        //    secondaries re-transfer.
        adminForm("/admin/dns-records/new",
            "zone_id=" + zoneId + "&name=one&type=A&value=192.0.2.1&ttl=300&enabled=true");
        assertThat(record(zoneId, "one")).as("step 2: the form lane created the record").isNotNull();
        assertThat(zoneSerial(zoneId)).as("step 2: the write bumped the serial").isGreaterThan(serial);
        serial = zoneSerial(zoneId);

        // 3. The quick-add bar rides the record source's create provider, which is the
        //    resource's own persistRow: the SAME codec refusal, on the field that caused it.
        InlineCreateResult refused = quickAdd(Map.of(
            "zone_id", zoneId, "type", "A", "name", "two", "value", "not-an-ip", "ttl", "300"));
        assertThat(refused.succeeded()).as("step 3: the bar refused the record").isFalse();
        assertThat(refused.violations()).as("step 3: the refusal names the value")
            .anyMatch(violation -> violation.path().equals("value"));
        assertThat(record(zoneId, "two")).as("step 3: nothing was written").isNull();
        assertThat(zoneSerial(zoneId)).as("step 3: nothing was announced").isEqualTo(serial);

        // 4. A valid quick add writes through the same funnel, serial bump included.
        InlineCreateResult added = quickAdd(Map.of(
            "zone_id", zoneId, "type", "A", "name", "two", "value", "192.0.2.2", "ttl", "300"));
        assertThat(added.succeeded()).as("step 4: the bar created the record: " + added.violations())
            .isTrue();
        assertThat(record(zoneId, "two")).as("step 4: the row exists").isNotNull();
        assertThat(zoneSerial(zoneId)).as("step 4: the write bumped the serial").isGreaterThan(serial);
        serial = zoneSerial(zoneId);

        // 5. The inline cell hands out the editor state: the fields the resource declared,
        //    a live concurrency token, and this principal's per-entry verdicts.
        int recordId = record(zoneId, "one").get(DnsRecordModel.ID);
        InlineEditState state = cellState("/admin/dns-records/" + recordId + "/cell-state");
        assertThat(state.fields().stream().map(field -> field.name()).toList())
            .as("step 5: the declared inline-editable entries, in declaration order")
            .containsExactly("name", "value", "ttl", "enabled");
        assertThat(state.editable()).as("step 5: an operator may write this record").isTrue();
        assertThat(state.editableFields())
            .as("step 5: and every declared entry is writable for them")
            .containsExactly("name", "value", "ttl", "enabled");
        assertThat(state.snapshot()).as("step 5: with a live concurrency token").isNotEmpty();

        // 6. The cell lane is a sibling of the form lane, never a bypass: the codec
        //    refuses the same value here.
        InlineEditResult badCell = cellSubmit(recordId,
            new InlineEditSubmit("value", "not-an-ip", state.snapshot()));
        assertThat(badCell.succeeded()).as("step 6: the cell lane refused the value").isFalse();
        assertThat(badCell.violations()).as("step 6: on the field that caused it")
            .anyMatch(violation -> violation.path().equals("value"));
        assertThat(record(zoneId, "one").get(DnsRecordModel.VALUE))
            .as("step 6: the stored value is untouched").isEqualTo("192.0.2.1");
        assertThat(zoneSerial(zoneId)).as("step 6: and nothing was announced").isEqualTo(serial);

        // 7. A valid cell commit writes exactly one column and bumps the serial.
        InlineEditResult savedValue = cellSubmit(recordId,
            new InlineEditSubmit("value", "192.0.2.9", state.snapshot()));
        assertThat(savedValue.succeeded())
            .as("step 7: the commit succeeded: " + savedValue.violations()).isTrue();
        Row updated = record(zoneId, "one");
        assertThat(updated.get(DnsRecordModel.VALUE)).as("step 7: the value moved")
            .isEqualTo("192.0.2.9");
        assertThat(((Number) updated.get(DnsRecordModel.TTL)).intValue())
            .as("step 7: and the columns the submit did not name did not").isEqualTo(300);
        assertThat(zoneSerial(zoneId)).as("step 7: the zone was re-announced").isGreaterThan(serial);
        serial = zoneSerial(zoneId);

        // 8. A TTL cell edit is a zone change too -- the resource's updateRow decides that,
        //    not the lane.
        InlineEditResult savedTtl = cellSubmit(recordId,
            new InlineEditSubmit("ttl", "60", savedValue.snapshot()));
        assertThat(savedTtl.succeeded())
            .as("step 8: the ttl commit succeeded: " + savedTtl.violations()).isTrue();
        assertThat(((Number) record(zoneId, "one").get(DnsRecordModel.TTL)).intValue())
            .as("step 8: the ttl moved").isEqualTo(60);
        assertThat(zoneSerial(zoneId)).as("step 8: the zone was re-announced").isGreaterThan(serial);

        // 9. The token that just wrote is stale, and a stale token is refused rather than
        //    silently overwriting whoever wrote in between.
        InlineEditResult stale = cellSubmit(recordId,
            new InlineEditSubmit("ttl", "120", savedValue.snapshot()));
        assertThat(stale.succeeded()).as("step 9: the stale commit was refused").isFalse();
        assertThat(stale.conflict()).as("step 9: as a CONFLICT, not a field violation").isTrue();
        assertThat(((Number) record(zoneId, "one").get(DnsRecordModel.TTL)).intValue())
            .as("step 9: a conflict writes nothing").isEqualTo(60);

        // 10. TYPE is deliberately NOT inline-editable (switching it swaps the DATA
        //     sub-schema), and an undeclared field is refused LOUDLY rather than dropped.
        InlineEditState fresh = cellState("/admin/dns-records/" + recordId + "/cell-state");
        InlineEditResult typed = cellSubmit(recordId,
            new InlineEditSubmit("type", "TXT", fresh.snapshot()));
        assertThat(typed.succeeded()).as("step 10: type is not an inline cell").isFalse();
        assertThat(record(zoneId, "one").get(DnsRecordModel.TYPE))
            .as("step 10: and the refusal wrote nothing").isEqualTo("A");
    }

    /**
     * The zone's Records tab renders the RESOURCE's declarations: its search box, its
     * quick-add bar (with this zone preset), its inline-editable cells and its typed
     * cells -- and the cells it offers agree with what the cell endpoint reports.
     */
    @Test
    void zoneRecordsTabRendersTheResourceSurfaces() throws Exception {
        int zoneId = createZone("tab.example");
        int recordId = createRecord(zoneId, "www", DnsRecordModel.TYPE_A, "192.0.2.20");
        createRecord(zoneId, "mail", DnsRecordModel.TYPE_A, "192.0.2.21");

        String path = "/admin/dns-zones/" + zoneId + "/page/records";
        HttpResponse<String> page = adminGet(path);
        assertThat(page.statusCode()).as("step 1: the tab renders").isEqualTo(200);
        String body = page.body();
        assertThat(body).as("step 1: with the framework search box, add bar and inline cells")
            .contains("<cms-list-search")
            .contains("<cms-quick-add")
            .contains("<cms-inline-cell");
        assertThat(body).as("step 1: the TYPE cell is the enum badge the model declares,"
                + " icon and colour included")
            .contains("location-dot");
        assertThat(body).as("step 1: and the value cell carries a copy chip")
            .contains("<pl-copy-button");

        // 2. The offered cells are exactly the declared inline-editable fields -- the same
        //    answer the cell endpoint gives for this record and principal (the affordance
        //    and the enforcement may never drift apart).
        InlineEditState state = cellState("/admin/dns-records/" + recordId + "/cell-state");
        for (String field : state.editableFields()) {
            assertThat(body).as("step 2: the page offers the '" + field + "' cell the endpoint allows")
                .contains("field=\"" + field + "\"");
        }

        // 3. The search box narrows the listing over the resource's declared search fields.
        String matching = adminGet(path + "?search=mail").body();
        assertThat(matching).as("step 3: the match is listed").contains("mail");
        assertThat(matching).as("step 3: and the rest is not")
            .doesNotContain("data-row-key=\"" + recordId + "\"");
        assertThat(matching).as("step 3: a narrowed listing is worth coming back to, so the"
                + " record links carry it as their return target")
            .contains("_return=");

        // 4. A term nothing matches reaches the filtered-empty state with its clear link,
        //    never the whole listing.
        String empty = adminGet(path + "?search=nothing-matches-this").body();
        assertThat(empty).as("step 4: the filtered empty state")
            .contains("data-cms-empty-state=\"filtered\"")
            .contains("data-cms-clear-filters");
    }

    /**
     * A delegated tenant on the /manage surface: authority over the RECORD decides every
     * lane, and it is decided per record rather than per zone.
     */
    @Test
    void tenantAuthorityDecidesEveryLanePerRecord() throws Exception {
        int zoneId = createZone("tenant.example");
        int ownName = createRecord(zoneId, "own", DnsRecordModel.TYPE_A, "192.0.2.30");
        int foreignName = createRecord(zoneId, "foreign", DnsRecordModel.TYPE_A, "192.0.2.31");

        Row user = AuthModels.users().createEmptyRow();
        user.set(UserModel.EMAIL, "dns-tenant@hohenheim.local");
        user.set(UserModel.DISPLAY_NAME, "Dns Tenant");
        user.set(UserModel.ENABLED, true);
        user.set(UserModel.CREATED_AT, Instant.now());
        user.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(user);
        int tenantId = user.get(UserModel.ID);

        // The tenant manages a site serving own.tenant.example, which is what gives it
        // hostname authority over that ONE name (and manage-panel access).
        int siteId = createSite("dns-tenant-site", "own.tenant.example");
        RecordGrants.grant(GrantSubjectType.USER, tenantId, SiteModel.MODEL_ID, siteId,
            HohenheimAccess.MANAGE, true);
        TestSession tenant = sessionFor(tenantId);

        // 1. The name it serves is editable in place: the state says so and the commit lands.
        InlineEditState own = cellState("/manage/dns-records/" + ownName + "/cell-state",
            tenant.token());
        assertThat(own.editable()).as("step 1: the served name is writable").isTrue();
        InlineEditResult saved = cellSubmit("/manage/dns-records/" + ownName + "/cell",
            new InlineEditSubmit("value", "192.0.2.40", own.snapshot()), tenant);
        assertThat(saved.succeeded()).as("step 1: and the commit lands: " + saved.violations())
            .isTrue();
        assertThat(Models.get(DnsRecordModel.class).findById(ownName).get(DnsRecordModel.VALUE))
            .as("step 1: the value moved").isEqualTo("192.0.2.40");

        // 2. A name in the same zone it does not serve is not even visible: an out-of-scope
        //    record reads as missing, never as forbidden.
        assertThat(httpGet("/manage/dns-records/" + foreignName + "/cell-state", tenant.token())
                .statusCode())
            .as("step 2: an unreachable record has no editor state").isEqualTo(404);

        // 3. With a VIEW grant the record becomes readable -- and the editor state says, in
        //    the same answer the write lane enforces, that it is still not writable.
        RecordGrants.grant(GrantSubjectType.USER, tenantId, DnsRecordModel.MODEL_ID, foreignName,
            HohenheimAccess.VIEW, true);
        InlineEditState foreign = cellState("/manage/dns-records/" + foreignName + "/cell-state",
            tenant.token());
        assertThat(foreign.editable()).as("step 3: viewing is not authoring").isFalse();
        assertThat(foreign.editableFields()).as("step 3: so no cell is offered").isEmpty();

        // 4. And the write lane refuses it outright: a denial is not a violation the operator
        //    could fix by typing something else.
        HttpResponse<String> refused = httpPostDry("/manage/dns-records/" + foreignName + "/cell",
            Zenit.DRY.stringify(new InlineEditSubmit("value", "10.0.0.1", foreign.snapshot())),
            tenant.token(), tenant.csrf());
        assertThat(refused.statusCode()).as("step 4: the commit is forbidden").isEqualTo(403);
        assertThat(Models.get(DnsRecordModel.class).findById(foreignName).get(DnsRecordModel.VALUE))
            .as("step 4: and wrote nothing").isEqualTo("192.0.2.31");

        // 5. The quick-add bar's create lane is operator-only: its form carries zone_id, so
        //    it would add into any zone at all. A tenant creates through its own form, which
        //    resolves the zone from the name it typed.
        HttpResponse<String> barred = httpPostDry(RECORD_SOURCE,
            Zenit.DRY.stringify(Map.of("zone_id", zoneId, "type", "A", "name", "sneaky",
                "value", "10.0.0.2", "ttl", "300")),
            tenant.token(), tenant.csrf());
        assertThat(barred.statusCode()).as("step 5: the tenant is refused the bar's create lane")
            .isEqualTo(403);
        assertThat(record(zoneId, "sneaky")).as("step 5: and nothing was written").isNull();
    }

    // --- transport --------------------------------------------------------------------

    private void adminForm(String path, String body) throws Exception {
        httpPostForm(path, body, sessionToken, csrfToken);
    }

    private InlineCreateResult quickAdd(Map<String, Object> values) throws Exception {
        HttpResponse<String> response = httpPostDry(RECORD_SOURCE,
            Zenit.DRY.stringify(values), sessionToken, csrfToken);
        assertThat(response.statusCode()).as("the inline-create lane answered").isEqualTo(200);
        return (InlineCreateResult) Zenit.DRY.parse(response.body());
    }

    private InlineEditState cellState(String path) throws Exception {
        return cellState(path, sessionToken);
    }

    private InlineEditState cellState(String path, String session) throws Exception {
        HttpResponse<String> response = httpGet(path, session);
        assertThat(response.statusCode()).as("the cell state answered").isEqualTo(200);
        return (InlineEditState) Zenit.DRY.parse(response.body());
    }

    private InlineEditResult cellSubmit(int recordId, InlineEditSubmit submit) throws Exception {
        return cellSubmit("/admin/dns-records/" + recordId + "/cell", submit,
            new TestSession(sessionToken, csrfToken));
    }

    private InlineEditResult cellSubmit(String path, InlineEditSubmit submit, TestSession session)
            throws Exception {
        HttpResponse<String> response = httpPostDry(path, Zenit.DRY.stringify(submit),
            session.token(), session.csrf());
        assertThat(response.statusCode()).as("the cell lane answered").isEqualTo(200);
        return (InlineEditResult) Zenit.DRY.parse(response.body());
    }

    // --- fixtures ---------------------------------------------------------------------

    private static int createZone(String origin) {
        DnsZoneModel zones = Models.get(DnsZoneModel.class);
        Row zone = zones.createEmptyRow();
        zone.set(DnsZoneModel.ORIGIN, origin);
        zone.set(DnsZoneModel.SOA_PRIMARY_NS, "ns1." + origin);
        zone.set(DnsZoneModel.SOA_CONTACT, "hostmaster@" + origin);
        zone.set(DnsZoneModel.ROLE, DnsZoneModel.ROLE_PRIMARY);
        zone.set(DnsZoneModel.ENABLED, true);
        zones.save(zone);
        return zone.get(DnsZoneModel.ID);
    }

    private static int createRecord(int zoneId, String name, String type, String value) {
        DnsRecordModel records = Models.get(DnsRecordModel.class);
        Row record = records.createEmptyRow();
        record.set(DnsRecordModel.ZONE_ID, zoneId);
        record.set(DnsRecordModel.NAME, name);
        record.set(DnsRecordModel.TYPE, type);
        record.set(DnsRecordModel.VALUE, value);
        record.set(DnsRecordModel.TTL, 300);
        record.set(DnsRecordModel.ENABLED, true);
        records.save(record);
        return record.get(DnsRecordModel.ID);
    }

    private static int createSite(String slug, String hostname) {
        SiteModel sites = Models.get(SiteModel.class);
        Row site = sites.createEmptyRow();
        site.set(SiteModel.NAME, slug);
        site.set(SiteModel.SLUG, slug);
        site.set(SiteModel.SITE_TYPE, "hohenheim:static");
        site.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp/" + slug));
        site.set(SiteModel.STATUS, "active");
        site.set(SiteModel.ENABLED, true);
        sites.save(site);
        int siteId = site.get(SiteModel.ID);

        Row domain = Models.get(SiteDomainModel.class).createEmptyRow();
        domain.set(SiteDomainModel.SITE_ID, siteId);
        domain.set(SiteDomainModel.HOSTNAME, hostname);
        domain.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        Models.get(SiteDomainModel.class).save(domain);
        return siteId;
    }

    private static Row record(int zoneId, String name) {
        return Models.get(DnsRecordModel.class).find()
            .where(DnsRecordModel.ZONE_ID.eq(zoneId))
            .where(DnsRecordModel.NAME.eq(name))
            .first();
    }

    private static long zoneSerial(int zoneId) {
        Integer serial = Models.get(DnsZoneModel.class).findById(zoneId).get(DnsZoneModel.SERIAL);
        return serial != null ? serial : 0;
    }
}
