package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.dns.DnsZoneStore;
import be.elevenways.hohenheim.server.dns.DynamicDnsService;
import be.elevenways.hohenheim.server.dns.DynamicDnsService.Status;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.RecordGrantModel;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.Test;
import org.xbill.DNS.Name;
import org.xbill.DNS.Type;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The DNS half of hostname release: a name whose last live covering domain row goes away
 * stops being SERVED, and the dyndns token minted under it stops WORKING -- the counterpart
 * of the certificate tier's orphan sweeper, and the fix for a token that was permanent DNS
 * write authority outliving the claim it was derived from.
 */
class ReleasedHostnameDnsTest extends HohenheimTestBase {

    private final DynamicDnsService service = new DynamicDnsService(DnsZoneStore.INSTANCE);

    /**
     * THE attack, made to succeed pre-fix: a tenant serves a dyndns name, gives up the site,
     * and its token keeps rewriting the authoritative record. The assertions are on the
     * SERVED answer, not just a column -- a released name that still resolves is the bug.
     */
    @Test
    void aSoftDeletedSitesDyndnsRecordStopsBeingServedAndItsTokenStopsWorking() {
        String origin = "release-dyn.example";
        int zoneId = zone(origin);
        int siteId = site("release-owner", "home." + origin);
        Row record = dynamicRecord(zoneId, "home", "10.0.0.1");
        int recordId = record.get(DnsRecordModel.ID);
        String token = mint(record);
        DnsZoneStore.INSTANCE.reload();

        // 1. Baseline: the token rewrites the record and the nameserver SERVES the new value.
        assertThat(service.update(token, null, "203.0.113.7", null).status())
            .as("1. the token drives a live update").isEqualTo(Status.GOOD);
        assertThat(servedA(origin, "home"))
            .as("1. the authoritative nameserver serves the updated address")
            .containsExactly("203.0.113.7");

        // 2. Release the claim: the owning site is soft-deleted. The record must leave the
        //    serving snapshot in the same breath.
        softDeleteSite(siteId);
        assertThat(servedA(origin, "home"))
            .as("2. our own nameserver no longer directs traffic to the departed tenant")
            .isEmpty();

        // 3. The token is dead: the credential did not outlive the claim.
        assertThat(service.update(token, null, "203.0.113.99", null).status())
            .as("3. a released name's token no longer authenticates").isEqualTo(Status.BADAUTH);

        // 4. And it is dead because the credential row was DELETED, not merely because
        //    serving dropped it: record disabled, credential gone -- the stored proof.
        Row after = Models.get(DnsRecordModel.class).findById(recordId);
        assertThat(after).as("4. the row is kept for the operator, not deleted").isNotNull();
        assertThat((Boolean) after.get(DnsRecordModel.ENABLED)).as("4. disabled").isFalse();
        assertThat(DynamicDnsService.credentialFor(recordId))
            .as("4. the bearer credential row is deleted").isNull();
    }

    /**
     * The trigger is loss of the LAST covering row, not any release: deleting one domain row
     * releases exactly the name it uniquely covered, while a name a SECOND live domain row
     * (here another exact row on the SAME site -- two live sites cannot share one exact
     * route) still covers is untouched.
     */
    @Test
    void onlyTheReleasedNameIsDisabledAndAStillCoveredNameSurvives() {
        String origin = "release-scope.example";
        int zoneId = zone(origin);
        int siteId = site("release-scope-owner", "a." + origin);
        // A second domain row on the SAME live site covers "shared"; releasing the "a" row
        // must not disable "shared" because that row still covers it.
        bindDomain(siteId, "shared." + origin);

        Row nameA = enabledRecord(zoneId, "a", "10.0.0.1");
        Row shared = enabledRecord(zoneId, "shared", "10.0.0.2");
        DnsZoneStore.INSTANCE.reload();

        Model domains = Models.get(SiteDomainModel.class);
        Row aDomain = domains.find().where(SiteDomainModel.HOSTNAME.eq("a." + origin))
            .where(SiteDomainModel.SITE_ID.eq(siteId)).first();
        domains.delete(aDomain);

        assertThat((Boolean) Models.get(DnsRecordModel.class).findById(nameA.get(DnsRecordModel.ID))
            .get(DnsRecordModel.ENABLED)).as("the released name is disabled").isFalse();
        assertThat((Boolean) Models.get(DnsRecordModel.class).findById(shared.get(DnsRecordModel.ID))
            .get(DnsRecordModel.ENABLED))
            .as("a name a surviving live domain row still covers keeps serving").isTrue();
        assertThat(servedA(origin, "shared"))
            .as("and it is still in the serving snapshot").containsExactly("10.0.0.2");
    }

    /**
     * Renaming a domain row releases the name it is LEAVING, not the one it arrives at.
     */
    @Test
    void renamingADomainRowReleasesTheDepartingName() {
        String origin = "release-rename.example";
        int zoneId = zone(origin);
        int siteId = site("release-rename-owner", "old." + origin);
        Row oldRecord = enabledRecord(zoneId, "old", "10.0.0.1");
        Row keepRecord = enabledRecord(zoneId, "keep", "10.0.0.9");
        DnsZoneStore.INSTANCE.reload();

        Model domains = Models.get(SiteDomainModel.class);
        Row domain = domains.find().where(SiteDomainModel.HOSTNAME.eq("old." + origin)).first();
        domain.set(SiteDomainModel.HOSTNAME, "new." + origin);
        domains.save(domain);

        assertThat((Boolean) Models.get(DnsRecordModel.class).findById(oldRecord.get(DnsRecordModel.ID))
            .get(DnsRecordModel.ENABLED))
            .as("the departing name is released").isFalse();
        assertThat((Boolean) Models.get(DnsRecordModel.class).findById(keepRecord.get(DnsRecordModel.ID))
            .get(DnsRecordModel.ENABLED))
            .as("an unrelated name in the zone is untouched").isTrue();
    }

    /**
     * Record grants on a released row are revoked in the same transaction: a per-record
     * capability that outlives the claim is the same laundering as a surviving token.
     */
    @Test
    void releasingANameRevokesItsRecordGrants() {
        String origin = "release-grant.example";
        int zoneId = zone(origin);
        int siteId = site("release-grant-owner", "g." + origin);
        Row record = enabledRecord(zoneId, "g", "10.0.0.1");
        int recordId = record.get(DnsRecordModel.ID);

        Row user = AuthModels.users().createEmptyRow();
        user.set(UserModel.EMAIL, "release-grant@hohenheim.local");
        user.set(UserModel.DISPLAY_NAME, "Grant Holder");
        user.set(UserModel.ENABLED, true);
        user.set(UserModel.CREATED_AT, Instant.now());
        user.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(user);
        int userId = user.get(UserModel.ID);
        RecordGrants.grant(GrantSubjectType.USER, userId, DnsRecordModel.MODEL_ID, recordId,
            HohenheimAccess.EDIT, true);
        assertThat(grantCount(recordId)).as("the grant landed").isGreaterThan(0);

        softDeleteSite(siteId);

        assertThat(grantCount(recordId))
            .as("the record's grants die with the claim").isZero();
    }

    /**
     * The dyndns columns are grant-gated on the MODEL write pipeline, not merely omitted
     * from a form: a tenant with plain hostname authority and no {@code dyndns} grant cannot
     * arm a token by a direct save. This is finding 3's counterfactual -- the freeze had to
     * live below the form, because a direct POST carries whatever it likes.
     */
    @Test
    void hostnameAuthorityAloneCannotArmADyndnsToken() {
        String origin = "release-arm.example";
        int zoneId = zone(origin);
        int siteId = site("release-arm-owner", "arm." + origin);
        Row record = enabledRecord(zoneId, "arm", "10.0.0.1");
        int recordId = record.get(DnsRecordModel.ID);
        DnsZoneStore.INSTANCE.reload();

        Row tenant = AuthModels.users().createEmptyRow();
        tenant.set(UserModel.EMAIL, "release-arm-tenant@hohenheim.local");
        tenant.set(UserModel.DISPLAY_NAME, "Arm Tenant");
        tenant.set(UserModel.ENABLED, true);
        tenant.set(UserModel.CREATED_AT, Instant.now());
        tenant.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(tenant);
        int tenantId = tenant.get(UserModel.ID);
        // The tenant MANAGES the site, so it has hostname authority over "arm." -- exactly
        // the authority the /manage DNS surface grants, and the one that used to reach the
        // dynamic columns because only the form omitted them.
        RecordGrants.grant(GrantSubjectType.USER, tenantId, SiteModel.MODEL_ID, siteId,
            HohenheimAccess.MANAGE, true);
        UserPrincipal tenantPrincipal = new UserPrincipal(tenantId, "Arm Tenant");
        Model model = Models.get(DnsRecordModel.class);

        // 1. Arming dynamic via hostname authority alone is refused, and nothing is armed
        //    (arming is now a CREDENTIAL row write, guarded on every lane).
        assertThatThrownBy(() -> TenantConduits.as(tenantPrincipal,
            () -> DynamicDnsService.mintFor(recordId)))
            .as("1. hostname authority is not dyndns authority").isInstanceOf(Violations.class);
        assertThat(DynamicDnsService.credentialFor(recordId))
            .as("1. nothing was armed").isNull();

        // 2. An ordinary value edit under the same hostname authority still saves -- the gate
        //    is on the dynamic columns, not a blanket wall.
        assertThatCode(() -> TenantConduits.as(tenantPrincipal, () -> {
            Row row = model.findById(recordId);
            row.set(DnsRecordModel.VALUE, "10.0.0.2");
            model.save(row);
        })).as("2. a plain value edit is still allowed").doesNotThrowAnyException();

        // 3. WITH a dyndns grant it is allowed -- the counter-proof the refusal is about the
        //    missing capability, not the value.
        RecordGrants.grant(GrantSubjectType.USER, tenantId, DnsRecordModel.MODEL_ID, recordId,
            HohenheimAccess.DYNDNS, true);
        assertThatCode(() -> TenantConduits.as(tenantPrincipal,
            () -> DynamicDnsService.mintFor(recordId)))
            .as("3. the dyndns holder may arm the token").doesNotThrowAnyException();
        assertThat(DynamicDnsService.credentialFor(recordId))
            .as("3. the credential row exists").isNotNull();
    }

    // --- helpers ----------------------------------------------------------------------

    private static int zone(String origin) {
        DnsZoneModel zones = Models.get(DnsZoneModel.class);
        Row zone = zones.createEmptyRow();
        zone.set(DnsZoneModel.ORIGIN, origin);
        zone.set(DnsZoneModel.SOA_PRIMARY_NS, "ns1." + origin);
        zone.set(DnsZoneModel.SOA_CONTACT, "hostmaster@" + origin);
        zone.set(DnsZoneModel.ENABLED, true);
        zones.save(zone);
        return zone.get(DnsZoneModel.ID);
    }

    private static int site(String slug, String hostname) {
        Model siteModel = Models.get(SiteModel.class);
        Row site = siteModel.createEmptyRow();
        site.set(SiteModel.NAME, slug);
        site.set(SiteModel.SLUG, slug);
        site.set(SiteModel.SITE_TYPE, "hohenheim:static");
        site.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        site.set(SiteModel.STATUS, "active");
        site.set(SiteModel.ENABLED, true);
        siteModel.save(site);
        int siteId = site.get(SiteModel.ID);
        bindDomain(siteId, hostname);
        return siteId;
    }

    private static void bindDomain(int siteId, String hostname) {
        Model domains = Models.get(SiteDomainModel.class);
        Row domain = domains.createEmptyRow();
        domain.set(SiteDomainModel.SITE_ID, siteId);
        domain.set(SiteDomainModel.HOSTNAME, hostname);
        domain.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        domains.save(domain);
    }

    private static Row enabledRecord(int zoneId, String name, String value) {
        Model model = Models.get(DnsRecordModel.class);
        Row row = model.createEmptyRow();
        row.set(DnsRecordModel.ZONE_ID, zoneId);
        row.set(DnsRecordModel.NAME, name);
        row.set(DnsRecordModel.TYPE, DnsRecordModel.TYPE_A);
        row.set(DnsRecordModel.VALUE, value);
        row.set(DnsRecordModel.TTL, 300);
        row.set(DnsRecordModel.ENABLED, true);
        model.save(row);
        return row;
    }

    private static Row dynamicRecord(int zoneId, String name, String value) {
        Row row = enabledRecord(zoneId, name, value);
        DynamicDnsService.mintFor(row.get(DnsRecordModel.ID));
        return row;
    }

    private static String mint(Row record) {
        return DynamicDnsService.mintFor(record.get(DnsRecordModel.ID));
    }

    /**
     * Soft-delete a site the way the runtime does: stamp deleted_at and save. That is the
     * exact transition SiteResource.deleteRow performs after teardown (which needs docker),
     * and the one InstanceService.destroy uses -- a plain save(), which is what fires the
     * release hooks (there is no remove hook on the soft-delete path).
     */
    private static void softDeleteSite(int siteId) {
        Model siteModel = Models.get(SiteModel.class);
        Row site = siteModel.findById(siteId);
        site.set(SiteModel.DELETED_AT, Instant.now());
        siteModel.save(site);
    }

    private static List<String> servedA(String origin, String owner) {
        var zone = DnsZoneStore.INSTANCE.getZone(origin);
        if (zone == null) {
            return List.of();
        }
        try {
            var rrset = zone.getRrset(Name.fromString(owner + "." + origin + "."), Type.A);
            return rrset == null ? List.of()
                : rrset.stream().map(r -> ((org.xbill.DNS.ARecord) r).getAddress().getHostAddress())
                    .toList();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static long grantCount(int recordId) {
        return AuthModels.recordGrants().find()
            .where(RecordGrantModel.MODEL.eq(DnsRecordModel.MODEL_ID.toString()))
            .where(RecordGrantModel.RECORD_ID.eq(String.valueOf(recordId)))
            .count();
    }
}
