package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.GrantService;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.data.DataItem;
import be.elevenways.zenit.common.data.DataPage;
import be.elevenways.zenit.common.data.RecordSourceQuery;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.KnownCapabilities;
import be.elevenways.zenit.common.security.KnownCapability;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.Objects;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the live DNS read wires across the EDIT-implies-VIEW declaration in a0217c80.
 *
 * @author Jelle De Loecker
 * @since 0.10.0
 */
class DnsEditGrantReadWireTest extends HohenheimTestBase {

    private static final String SOURCE = "/zn/records/hohenheim.dns_record";

    @Test
    void anEditOnlyRecordGrantReadsExactlyItsRecordAndNeverTheAdminZoneList() throws Exception {
        int account = ApiSupport.user("dns-edit-wire@fixture.test", "DNS edit wire reader");
        int zone = DnsFixtures.createZone("dns-edit-wire.fixture.test");
        int editable = DnsFixtures.record(zone, "editable", DnsRecordModel.TYPE_A, "192.0.2.41");
        int hidden = DnsFixtures.record(zone, "hidden", DnsRecordModel.TYPE_A, "192.0.2.42");
        KnownCapability currentView = Objects.requireNonNull(
            KnownCapabilities.get(DnsRecordModel.MODEL_ID, HohenheimCapabilities.VIEW));
        try {
            // 1. Admit the account to the delegated surface, with exactly one record grant: EDIT, never VIEW.
            // No site or hostname authority is granted, so the source cannot admit this record by another path.
            GrantService.createDirectGrant(GrantSubjectType.USER, account, HohenheimSources.MANAGE_ACCESS.value(), true);
            RecordGrants.grant(GrantSubjectType.USER, account, DnsRecordModel.MODEL_ID, editable,
                HohenheimCapabilities.EDIT, true);
            TestSession caller = sessionFor(account);
            String query = Zenit.DRY.stringify(RecordSourceQuery.matchAll());
            assertThat(currentView.impliedBy()).as("step 1: current VIEW is implied by EDIT")
                .containsExactly(HohenheimCapabilities.EDIT);

            // 2. Replay the parent declaration of a0217c80 through the canonical test replacement seam. Its only
            // change was adding VIEW.impliedBy(EDIT); keep every other vocabulary fact and the HTTP transport fixed.
            KnownCapability parentView = new KnownCapability(currentView.capability(), currentView.label(),
                currentView.description(), currentView.sensitivity(), currentView.delegable(),
                currentView.ownerImplied(), currentView.grantable(), Set.of());
            KnownCapabilities.replace(DnsRecordModel.MODEL_ID, parentView);
            HttpResponse<String> beforeZones = httpGet("/api/v1/dns/zones", caller.token());
            HttpResponse<String> beforeQuery = httpPostDry(SOURCE + "/query", query, caller.token(), caller.csrf());
            HttpResponse<String> beforeItem = httpGet(SOURCE + "/item/" + editable, caller.token());
            assertThat(beforeZones.statusCode()).as("step 2: the parent wire's zone list is admin-only")
                .isEqualTo(403);
            assertThat(beforeQuery.statusCode()).as("step 2: the parent query is reachable").isEqualTo(200);
            DataPage parentPage = Zenit.DRY.fromJson(beforeQuery.body(), DataPage.class);
            assertThat(parentPage.items()).as("step 2: EDIT alone disclosed no records before a0217c80").isEmpty();
            assertThat(parentPage.total()).as("step 2: the parent total disclosed no records").isZero();
            assertThat(beforeItem.statusCode()).as("step 2: the parent item read concealed the editable record")
                .isEqualTo(404);

            // 3. The shipped declaration admits the same record on both real HTTP source transports.
            KnownCapabilities.replace(DnsRecordModel.MODEL_ID, currentView);
            HttpResponse<String> afterZones = httpGet("/api/v1/dns/zones", caller.token());
            HttpResponse<String> afterQuery = httpPostDry(SOURCE + "/query", query, caller.token(), caller.csrf());
            HttpResponse<String> afterItem = httpGet(SOURCE + "/item/" + editable, caller.token());
            assertThat(afterZones.statusCode()).as("step 3: EDIT still cannot read the admin zone list")
                .isEqualTo(beforeZones.statusCode());
            assertThat(afterQuery.statusCode()).as("step 3: the current query is reachable").isEqualTo(200);
            DataPage currentPage = Zenit.DRY.fromJson(afterQuery.body(), DataPage.class);
            assertThat(currentPage.total()).as("step 3: only the one EDIT-granted record is visible").isEqualTo(1);
            assertThat(currentPage.items()).as("step 3: query returns exactly the editable record")
                .extracting(DataItem::value).containsExactly(String.valueOf(editable));
            assertThat(afterItem.statusCode()).as("step 3: the current item read admits the editable record")
                .isEqualTo(200);
            DataItem item = Zenit.DRY.fromJson(afterItem.body(), DataItem.class);
            assertThat(item).as("step 3: item and query disclose the exact same projection")
                .isEqualTo(currentPage.items().getFirst());
            assertThat(item.values()).as("step 3: DNS record visibility never discloses zone trust secrets")
                .doesNotContainKeys("api_key", "tsig_secret", "dnssec_private_key");

            // 4. A sibling with no grant remains concealed. Removing EDIT removes both read paths again.
            assertThat(httpGet(SOURCE + "/item/" + hidden, caller.token()).statusCode())
                .as("step 4: an ungranted sibling stays hidden").isEqualTo(404);
            RecordGrants.revoke(GrantSubjectType.USER, account, DnsRecordModel.MODEL_ID, editable,
                HohenheimCapabilities.EDIT);
            HttpResponse<String> revoked = httpPostDry(SOURCE + "/query", query, caller.token(), caller.csrf());
            assertThat(Zenit.DRY.fromJson(revoked.body(), DataPage.class).items())
                .as("step 4: revocation empties the current query").isEmpty();
            assertThat(httpGet(SOURCE + "/item/" + editable, caller.token()).statusCode())
                .as("step 4: revocation conceals the current item").isEqualTo(404);

            System.out.println("REVIEW30 L5 BEFORE query=" + beforeQuery.body()
                + "; item=" + beforeItem.statusCode() + " " + beforeItem.body()
                + "; zones=" + beforeZones.statusCode());
            System.out.println("REVIEW30 L5 AFTER query=" + afterQuery.body()
                + "; item=" + afterItem.statusCode() + " " + afterItem.body()
                + "; zones=" + afterZones.statusCode());
        } finally {
            KnownCapabilities.replace(DnsRecordModel.MODEL_ID, currentView);
            Models.get(DnsZoneModel.class).delete(zone);
            Models.get(UserModel.class).delete(account);
        }
    }
}
