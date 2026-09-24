package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * A delegated tenant writes exactly what the /manage site surface offers -- name, enabled,
 * description -- on EVERY writer, and removes a domain row only of a site it manages.
 *
 * AIDEV-NOTE: before this, sites were the one tenant-writable model with no column
 * allow-list in TenantWrites: the /manage resource's field bindings were the only thing
 * standing between a direct save (revision restore, a future API lane, any model.save in a
 * tenant request) and the site's gate, instance and routing columns. Domain removals had no
 * write-pipeline gate at all. Every refusal below is a direct model write, which no form
 * or resource method sees.
 */
class TenantSiteColumnsTest extends HohenheimTestBase {

    private static int ownSite;
    private static int foreignSite;
    private static UserPrincipal tenant;
    private static UserPrincipal admin;

    @BeforeAll
    static void seed() {
        int tenantId = ApiSupport.user("site-columns@hohenheim.local", "Site Columns Tenant");
        tenant = new UserPrincipal(tenantId, "Site Columns Tenant");
        Row adminRow = AuthModels.users().find()
            .where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        admin = new UserPrincipal(adminRow.get(UserModel.ID), "Test Admin");

        ownSite = site("site-columns-own");
        foreignSite = site("site-columns-foreign");
        RecordGrants.grant(GrantSubjectType.USER, tenantId, SiteModel.MODEL_ID, ownSite,
            HohenheimAccess.MANAGE, true);
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

    private static int domain(int siteId, String hostname) {
        Model model = Models.get(SiteDomainModel.class);
        Row row = model.createEmptyRow();
        row.set(SiteDomainModel.SITE_ID, siteId);
        row.set(SiteDomainModel.HOSTNAME, hostname);
        row.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        row.set(SiteDomainModel.FORCE_SSL, false);
        model.save(row);
        return row.get(SiteDomainModel.ID);
    }

    /** @return the first refusal key of a tenant write of one column, or null when it saved */
    private static String tenantWrite(Field<?, ?> field, Object value) {
        Violations refused = catchThrowableOfType(() -> TenantConduits.as(tenant, () -> {
            Model model = Models.get(SiteModel.class);
            Row row = model.findById(ownSite);
            row.set(field.getName(), value);
            model.save(row);
        }), Violations.class);
        return refused == null ? null : refused.all().get(0).message().key();
    }

    @Test
    void aTenantWritesOnlyTheDelegatedSiteColumns() {
        Model model = Models.get(SiteModel.class);

        // 1. Every operator column is frozen: the gate (access list, auth provider), what the
        //    site runs (instance, kind), its identity (slug) and its quota charge.
        Map<Field<?, ?>, Object> operatorColumns = Map.of(
            SiteModel.ACCESS_LIST_ID, 999_999,
            SiteModel.AUTH_PROVIDER_ID, 999_999,
            SiteModel.INSTANCE_ID, 999_999,
            SiteModel.SLUG, "site-columns-renamed-slug",
            SiteModel.QUOTA_BUCKET, "forged-bucket");
        for (Map.Entry<Field<?, ?>, Object> column : operatorColumns.entrySet()) {
            assertThat(tenantWrite(column.getKey(), column.getValue()))
                .as("step 1: %s is operator authority", column.getKey().getName())
                .isEqualTo("tenant_field_frozen");
        }
        assertThat((String) model.findById(ownSite).get(SiteModel.SLUG))
            .as("step 1: the refused writes left the row untouched")
            .isEqualTo("site-columns-own");

        // 2. Re-kinding the upstream is refused too (a static root is a host path).
        assertThat(tenantWrite(SiteModel.UPSTREAM_KIND, "hohenheim:address"))
            .as("step 2: the upstream kind is frozen").isNotNull();

        // 3. The delegated columns still save, so the rule is a freeze and not a wall.
        assertThat(tenantWrite(SiteModel.NAME, "Site Columns (renamed)"))
            .as("step 3: name is delegated").isNull();
        assertThat(tenantWrite(SiteModel.DESCRIPTION, "delegated description"))
            .as("step 3: description is delegated").isNull();
        assertThat(tenantWrite(SiteModel.ENABLED, false)).as("step 3: enabled is delegated").isNull();

        // 4. The same operator column as an operator goes through.
        assertThatCode(() -> TenantConduits.as(admin, () -> {
            Row row = model.findById(ownSite);
            row.set(SiteModel.SLUG, "site-columns-operator-slug");
            model.save(row);
        })).as("step 4: an operator is unconstrained").doesNotThrowAnyException();
    }

    @Test
    void aTenantRemovesDomainRowsOnlyOfSitesItManages() {
        Model domains = Models.get(SiteDomainModel.class);
        int foreignDomain = domain(foreignSite, "foreign.site-columns.test");
        int ownDomain = domain(ownSite, "own.site-columns.test");

        // 1. A direct delete of another site's row is refused and leaves it in place.
        Violations byId = catchThrowableOfType(() -> TenantConduits.as(tenant,
            () -> domains.delete(domains.findById(foreignDomain))), Violations.class);
        assertThat((Throwable) byId).as("step 1: a foreign domain row is not the tenant's").isNotNull();
        assertThat(byId.all().get(0).message().key()).as("step 1: refused as an unmanaged site")
            .isEqualTo("tenant_site_not_managed");
        assertThat(domains.findById(foreignDomain)).as("step 1: the row survived").isNotNull();

        // 2. So is a CRITERIA delete reaching it, the shape no resource method ever sees.
        Violations byCriteria = catchThrowableOfType(() -> TenantConduits.as(tenant,
            () -> domains.find().where(SiteDomainModel.SITE_ID.eq(foreignSite)).delete()),
            Violations.class);
        assertThat((Throwable) byCriteria).as("step 2: a criteria delete is judged per doomed row")
            .isNotNull();
        assertThat(domains.findById(foreignDomain)).as("step 2: the row survived").isNotNull();

        // 3. Its own row deletes fine.
        assertThatCode(() -> TenantConduits.as(tenant,
            () -> domains.delete(domains.findById(ownDomain))))
            .as("step 3: a tenant may unbind its own hostname").doesNotThrowAnyException();
        assertThat(domains.findById(ownDomain)).as("step 3: the own row is gone").isNull();

        // 4. The operator removes anything.
        assertThatCode(() -> TenantConduits.as(admin,
            () -> domains.delete(domains.findById(foreignDomain))))
            .as("step 4: an operator is unconstrained").doesNotThrowAnyException();
    }
}
