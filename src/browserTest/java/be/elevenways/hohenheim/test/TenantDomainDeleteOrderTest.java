package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.ReleasedRouteClaimModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * A tenant's refused domain delete has NO consequence: the authority check (TenantWrites) is
 * registered before the route invariant's remove hook, so a refused delete never ledgers a
 * released-claim quarantine row for a hostname it was not allowed to release.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
class TenantDomainDeleteOrderTest extends HohenheimTestBase {

    private static final String HOST = "refused-delete-order.example.com";

    @Test
    void aRefusedTenantDomainDeleteLedgersNoReleasedClaim() {
        var sites = Models.get(SiteModel.class);
        var domains = Models.get(SiteDomainModel.class);
        var ledger = Models.get(ReleasedRouteClaimModel.class);

        // An operator site serving the hostname live, and a tenant who manages ANOTHER site.
        Row victim = site("Delete Order Victim", "delete-order-victim");
        Row own = site("Delete Order Own", "delete-order-own");
        Row domain = domains.createEmptyRow();
        domain.set(SiteDomainModel.SITE_ID, victim.get(SiteModel.ID));
        domain.set(SiteDomainModel.HOSTNAME, HOST);
        domain.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        domain.set(SiteDomainModel.FORCE_SSL, false);
        domains.save(domain);
        int domainId = domain.get(SiteDomainModel.ID);
        int tenantId = ApiSupport.user("delete-order-tenant@hohenheim.local", "Delete Order Tenant");
        UserPrincipal tenant = new UserPrincipal(tenantId, "Delete Order Tenant");
        RecordGrants.grant(GrantSubjectType.USER, tenantId, SiteModel.MODEL_ID, own.get(SiteModel.ID),
            HohenheimAccess.MANAGE, true);
        try {
            // 1. The tenant deletes the victim's live domain row straight at the model: refused.
            Violations refused = catchThrowableOfType(() -> TenantConduits.as(tenant, () ->
                domains.find().where(SiteDomainModel.ID.eq(domainId)).delete()), Violations.class);
            assertThat((Throwable) refused).as("step 1: the tenant may not delete a domain of a site it "
                + "does not manage").isNotNull();
            assertThat(refused.all().get(0).message().key())
                .as("step 1: refused by the authority check").isEqualTo("tenant_site_not_managed");

            // 2. And the refusal had no consequence: the row stands and no quarantine was ledgered.
            assertThat(domains.findById(domainId)).as("step 2: the domain row still stands").isNotNull();
            assertThat(ledger.find().where(ReleasedRouteClaimModel.HOSTNAME.eq(HOST)).count())
                .as("step 2: a refused delete released nothing").isZero();

            // 3. Positive control: the operator's delete of the same row DOES ledger the release,
            //    so step 2 measured the ordering, not a ledger that records nothing.
            domains.find().where(SiteDomainModel.ID.eq(domainId)).delete();
            assertThat(ledger.find().where(ReleasedRouteClaimModel.HOSTNAME.eq(HOST)).count())
                .as("step 3: an authorized delete ledgers the released claim").isEqualTo(1);
        } finally {
            RecordGrants.revoke(GrantSubjectType.USER, tenantId, SiteModel.MODEL_ID, own.get(SiteModel.ID),
                HohenheimAccess.MANAGE);
            domains.find().where(SiteDomainModel.ID.eq(domainId)).delete();
            HardDeletes.row(sites, victim);
            HardDeletes.row(sites, own);
            ledger.find().where(ReleasedRouteClaimModel.HOSTNAME.eq(HOST)).delete();
        }
    }

    private static Row site(String name, String slug) {
        var sites = Models.get(SiteModel.class);
        Row row = sites.createEmptyRow();
        row.set(SiteModel.NAME, name);
        row.set(SiteModel.SLUG, slug);
        row.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        row.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        row.set(SiteModel.STATUS, "active");
        row.set(SiteModel.ENABLED, true);
        sites.save(row);
        return row;
    }
}
