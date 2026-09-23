package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.auth.HostnameAuthority;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * A tenant may not claim a NEW hostname at or under a name another owner holds -- another
 * tenant's exact hostname, the operator's own hostname, or a zone the operator hosts --
 * unless the operator delegated that namespace with a wildcard row on the tenant's site.
 *
 * AIDEV-NOTE: the counterfactual is the attack. Before this rule a tenant adding
 * {@code shop.victim...} to its own site became the only covering row for that name while
 * another tenant held {@code victim...}, and HostnameAuthority then handed it the authority
 * to write DNS in the victim's zone and to order certificates for the name. The positive
 * anchors (own parent, delegation, a free name, an operator write) prove the rule refuses
 * only foreign namespaces, and the grandfathered row proves no stored claim is revoked.
 */
class TenantHostnameClaimTest extends HohenheimTestBase {

    private static final String BASE = "claimtest.test";
    private static final String VICTIM = "victim." + BASE;
    private static final String OPERATOR_HOST = "ops." + BASE;
    private static final String ZONE = "zone." + BASE;
    private static final String DELEGATED = "a." + ZONE;
    private static final String LEGACY = "legacy." + ZONE;

    private static int siteA;
    private static int siteB;
    private static UserPrincipal tenantA;
    private static UserPrincipal tenantB;
    private static UserPrincipal admin;
    private static int legacyDomainId;

    @BeforeAll
    static void seed() {
        int userA = user("claim-a@hohenheim.local", "Claim Tenant A");
        int userB = user("claim-b@hohenheim.local", "Claim Tenant B");
        tenantA = new UserPrincipal(userA, "Claim Tenant A");
        tenantB = new UserPrincipal(userB, "Claim Tenant B");
        Row adminRow = AuthModels.users().find()
            .where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        admin = new UserPrincipal(adminRow.get(UserModel.ID), "Test Admin");

        siteA = site("claim-site-a");
        siteB = site("claim-site-b");
        int operatorSite = site("claim-site-operator");
        RecordGrants.grant(GrantSubjectType.USER, userA, SiteModel.MODEL_ID, siteA,
            HohenheimAccess.MANAGE, true);
        RecordGrants.grant(GrantSubjectType.USER, userB, SiteModel.MODEL_ID, siteB,
            HohenheimAccess.MANAGE, true);

        // Tenant B holds an apex, the operator holds a hostname of its own and hosts a zone.
        domain(siteB, VICTIM, SiteDomainModel.MATCH_EXACT);
        domain(operatorSite, OPERATOR_HOST, SiteDomainModel.MATCH_EXACT);
        DnsFixtures.createZone(ZONE);

        // The operator's DELEGATION of one namespace inside its zone to tenant A.
        domain(siteA, "*." + DELEGATED, SiteDomainModel.MATCH_WILDCARD);

        // A claim that predates the rule: stored by the system, never judged by it.
        legacyDomainId = domain(siteA, LEGACY, SiteDomainModel.MATCH_EXACT);
    }

    private static int user(String email, String name) {
        Row row = AuthModels.users().createEmptyRow();
        row.set(UserModel.EMAIL, email);
        row.set(UserModel.DISPLAY_NAME, name);
        row.set(UserModel.ENABLED, true);
        row.set(UserModel.CREATED_AT, Now.instant());
        row.set(UserModel.UPDATED_AT, Now.instant());
        AuthModels.users().save(row);
        return row.get(UserModel.ID);
    }

    private static int site(String slug) {
        Model model = Models.get(SiteModel.class);
        Row row = model.createEmptyRow();
        row.set(SiteModel.NAME, slug);
        row.set(SiteModel.SLUG, slug);
        row.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        row.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        row.set(SiteModel.STATUS, SiteModel.STATUS_ACTIVE);
        row.set(SiteModel.ENABLED, true);
        model.save(row);
        return row.get(SiteModel.ID);
    }

    private static int domain(int siteId, String hostname, String matchType) {
        Model model = Models.get(SiteDomainModel.class);
        Row row = model.createEmptyRow();
        row.set(SiteDomainModel.SITE_ID, siteId);
        row.set(SiteDomainModel.HOSTNAME, hostname);
        row.set(SiteDomainModel.MATCH_TYPE, matchType);
        row.set(SiteDomainModel.FORCE_SSL, false);
        model.save(row);
        return row.get(SiteDomainModel.ID);
    }

    /** A tenant-originated create, straight at the model: the write pipeline is the gate. */
    private static void claimAs(UserPrincipal principal, int siteId, String hostname) {
        TenantConduits.as(principal, () -> domain(siteId, hostname, SiteDomainModel.MATCH_EXACT));
    }

    /** @return the refusal key of a claim, or null when it landed */
    private static String refusalOf(UserPrincipal principal, int siteId, String hostname) {
        Violations refused = catchThrowableOfType(() -> claimAs(principal, siteId, hostname),
            Violations.class);
        return refused == null ? null : refused.all().get(0).message().key();
    }

    private static Row stored(String hostname) {
        return Models.get(SiteDomainModel.class).find()
            .where(SiteDomainModel.HOSTNAME.eq(hostname)).first();
    }

    @Test
    void aTenantCannotClaimUnderANameAnotherOwnerHoldsUnlessDelegated() {
        // 1. Under ANOTHER TENANT's apex: refused with the neutral sentence, nothing stored.
        assertThat(refusalOf(tenantA, siteA, "shop." + VICTIM))
            .as("step 1: a name under another tenant's hostname is not A's to claim")
            .isEqualTo(HostnameAuthority.HOSTNAME_UNAVAILABLE);
        assertThat(stored("shop." + VICTIM)).as("step 1: and nothing was stored").isNull();

        // 2. Under the OPERATOR's own hostname: refused the same way.
        assertThat(refusalOf(tenantA, siteA, "sub." + OPERATOR_HOST))
            .as("step 2: the operator's hostname is a foreign namespace too")
            .isEqualTo(HostnameAuthority.HOSTNAME_UNAVAILABLE);

        // 3. Under -- and at -- a zone the operator hosts: refused.
        assertThat(refusalOf(tenantA, siteA, "mail." + ZONE))
            .as("step 3: a name inside the operator's zone is refused")
            .isEqualTo(HostnameAuthority.HOSTNAME_UNAVAILABLE);
        assertThat(refusalOf(tenantA, siteA, ZONE))
            .as("step 3: and so is the zone apex itself")
            .isEqualTo(HostnameAuthority.HOSTNAME_UNAVAILABLE);

        // 4. Inside the namespace the operator DELEGATED to A with a wildcard row: allowed.
        assertThat(refusalOf(tenantA, siteA, "www." + DELEGATED))
            .as("step 4: a delegated namespace is A's to populate").isNull();
        assertThat(stored("www." + DELEGATED)).as("step 4: the claim landed").isNotNull();

        // 5. A name nobody holds any ancestor of: allowed, the rule is not a wall.
        assertThat(refusalOf(tenantA, siteA, "fresh.claimtest-free.test"))
            .as("step 5: a free name is still claimable").isNull();

        // 6. Under its OWN apex: B may populate what B holds.
        assertThat(refusalOf(tenantB, siteB, "sub." + VICTIM))
            .as("step 6: a tenant may claim under its own hostname").isNull();

        // 7. Moving a claim is a new claim: A cannot rename its delegated row into B's space.
        Row delegatedRow = stored("www." + DELEGATED);
        Violations moved = catchThrowableOfType(() -> TenantConduits.as(tenantA, () -> {
            Model model = Models.get(SiteDomainModel.class);
            Row row = model.findById(delegatedRow.get(SiteDomainModel.ID));
            row.set(SiteDomainModel.HOSTNAME, "moved." + VICTIM);
            model.save(row);
        }), Violations.class);
        assertThat((Throwable) moved).as("step 7: a rename into a foreign space is refused")
            .isNotNull();
        assertThat((String) Models.get(SiteDomainModel.class)
            .findById(delegatedRow.get(SiteDomainModel.ID)).get(SiteDomainModel.HOSTNAME))
            .as("step 7: and the row kept its name").isEqualTo("www." + DELEGATED);

        // 8. The operator is not judged by the tenant rule at all.
        assertThatCode(() -> TenantConduits.as(admin,
            () -> domain(siteA, "operator-added." + VICTIM, SiteDomainModel.MATCH_EXACT)))
            .as("step 8: an operator may still place any name on any site")
            .doesNotThrowAnyException();
    }

    @Test
    void aClaimStoredBeforeTheRuleKeepsResolvingAndKeepsSaving() {
        AccessContext ctx = AccessContext.of(TenantConduits.stubFor(tenantA));

        // 1. The rule would refuse this name as a NEW claim today (it sits in the zone)...
        assertThat(HostnameAuthority.mayClaim(siteA, LEGACY))
            .as("step 1: the legacy name is not claimable under the rule").isFalse();

        // 2. ...yet the stored claim still resolves: its holder keeps DNS/certificate authority.
        assertThat(HostnameAuthority.canManage(ctx, LEGACY))
            .as("step 2: a grandfathered claim is never revoked").isTrue();

        // 3. And re-saving it (a delegated column, no new claim) is not judged either.
        assertThatCode(() -> TenantConduits.as(tenantA, () -> {
            Model model = Models.get(SiteDomainModel.class);
            Row row = model.findById(legacyDomainId);
            row.set(SiteDomainModel.HSTS_ENABLED, true);
            model.save(row);
        })).as("step 3: editing a grandfathered row is not a new claim")
            .doesNotThrowAnyException();
    }
}
