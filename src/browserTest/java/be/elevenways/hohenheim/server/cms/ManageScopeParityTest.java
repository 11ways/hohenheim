package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.OwnedInstances;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.cms.common.access.AccessDecision;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.common.data.RecordSource;
import be.elevenways.zenit.common.data.RecordSourceRegistry;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.QueryBuilder;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.security.AccessContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The /manage list and the record source over the same model read ONE scope
 * ({@link TenantScopes}), so a picker can never name a row the list hides.
 *
 * AIDEV-NOTE: the two used to be spelled separately and had drifted -- the instance source
 * offered product-tier GENERATED instances the /manage list hid, and the domain source listed
 * the domains of soft-deleted sites. This compares the two READS row for row, for a tenant
 * and for an operator, over every model the panel projects through both faces.
 */
class ManageScopeParityTest extends HohenheimTestBase {

    private static final String PREFIX = "scope-parity-";

    private static int tenantId;
    private static int authoredInstanceId;
    private static int generatedInstanceId;
    private static int deletedSiteDomainId;
    private static int liveSiteDomainId;

    @BeforeAll
    static void seed() {
        tenantId = ApiSupport.user(PREFIX + "tenant@hohenheim.local", "Scope Parity Tenant");

        authoredInstanceId = instance(PREFIX + "authored");
        int[] generated = new int[1];
        OwnedInstances.inScopeUnchecked("site", SiteModel.MODEL_ID, 434343,
            () -> generated[0] = instance(PREFIX + "generated"));
        generatedInstanceId = generated[0];
        // The tenant holds view on BOTH, so the only thing that can keep the generated row
        // out of either read is the scope's own base -- which is the claim.
        RecordGrants.grant(GrantSubjectType.USER, tenantId, InstanceModel.MODEL_ID,
            authoredInstanceId, HohenheimAccess.VIEW, true);
        RecordGrants.grant(GrantSubjectType.USER, tenantId, InstanceModel.MODEL_ID,
            generatedInstanceId, HohenheimAccess.VIEW, true);

        int liveSite = site(PREFIX + "live", false);
        int deletedSite = site(PREFIX + "deleted", true);
        liveSiteDomainId = domain(liveSite, PREFIX + "live.parity.test");
        deletedSiteDomainId = domain(deletedSite, PREFIX + "deleted.parity.test");
        RecordGrants.grant(GrantSubjectType.USER, tenantId, SiteModel.MODEL_ID, liveSite,
            HohenheimAccess.MANAGE, true);
    }

    @Test
    void theManageListAndItsPickerSourceReadTheSameRows() {
        AccessContext tenant = AccessContext.of(TenantConduits.stubFor(
            new UserPrincipal(tenantId, "Scope Parity Tenant")));
        AccessContext operator = AccessContext.of(TenantConduits.stubFor(operatorPrincipal()));

        // 1. The instance pair, as the tenant: the authored instance is in both reads and the
        //    generated one -- granted just the same -- is in neither.
        ManageInstanceResource instances = new ManageInstanceResource();
        Set<Object> listed = resourceIds(instances, tenant);
        Set<Object> picked = sourceIds(instances, tenant);
        assertThat(listed)
            .as("step 1: the /manage instance list shows the authored instance")
            .contains(authoredInstanceId)
            .doesNotContain(generatedInstanceId);
        assertThat(picked)
            .as("step 1: the instance picker source no longer offers a generated instance")
            .contains(authoredInstanceId)
            .doesNotContain(generatedInstanceId);

        // 2. The domain pair, as the operator (whose walk is unconstrained, so only the
        //    scope's BASE can drop a row): a soft-deleted site's domain is in neither read.
        ManageDomainResource domains = new ManageDomainResource();
        assertThat(resourceIds(domains, operator))
            .as("step 2: the /manage domain list hides a soft-deleted site's domain")
            .contains(liveSiteDomainId)
            .doesNotContain(deletedSiteDomainId);
        assertThat(sourceIds(domains, operator))
            .as("step 2: and the domain source now hides it too")
            .contains(liveSiteDomainId)
            .doesNotContain(deletedSiteDomainId);

        // 3. Every model the panel projects through BOTH a list and a source: the two reads
        //    are the same set, for the tenant and for the operator.
        List<RowResource> paired = List.of(new ManageSiteResource(), new ManageDomainResource(),
            new ManageInstanceResource(), new ManageCertificateResource(),
            new ManageProtectedPathResource(), new ManageDnsRecordResource(),
            new ManageInstanceTemplateResource(), new ManageInstanceScheduleResource(),
            new ManageProjectResource(), new ManageDatabaseResource(),
            new ManageInstanceDeviceResource(), new ManageInstanceDatabaseResource(),
            new ManagePreviewDeploymentResource());
        for (RowResource resource : paired) {
            for (AccessContext ctx : List.of(tenant, operator)) {
                assertThat(sourceIds(resource, ctx))
                    .as("step 3: %s and its record source read the same rows for %s",
                        resource.id(), ctx.principalId())
                    .isEqualTo(resourceIds(resource, ctx));
            }
        }
    }

    /** The ids the resource's own access decision lets this context list. */
    private static Set<Object> resourceIds(RowResource resource, AccessContext ctx) {
        AccessDecision decision = resource.accessFunction().decide(ctx);
        assertThat(decision.isDenied()).as("%s must not deny outright", resource.id()).isFalse();
        QueryBuilder<Row> query = resource.model().find();
        if (decision.predicate() != null) {
            query.where(decision.predicate().criteria());
        }
        return idsOf(resource.model(), query.all());
    }

    /** The ids the model's registered default source serves this context. */
    private static Set<Object> sourceIds(RowResource resource, AccessContext ctx) {
        RecordSource<?> source = RecordSourceRegistry.INSTANCE
            .requireDefaultFor(resource.model().getModelId());
        return idsOf(resource.model(),
            source.buildQuery(null, null, null, SortOrder.ASC, null, ctx).all());
    }

    private static Set<Object> idsOf(Model model, List<Row> rows) {
        String primaryKey = model.getPrimaryKeyField().getName();
        Set<Object> ids = new LinkedHashSet<>();
        for (Row row : rows) {
            ids.add(row.get(primaryKey));
        }
        return ids;
    }

    private static UserPrincipal operatorPrincipal() {
        Row admin = AuthModels.users().find()
            .where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        return new UserPrincipal(admin.get(UserModel.ID), "Test Admin");
    }

    private static int instance(String name) {
        Model instances = Models.get(InstanceModel.class);
        Row row = instances.createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(
            Map.of("image", "alpine", "tag", "latest", "command", "sleep 300")));
        row.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
        instances.save(row);
        return row.get(InstanceModel.ID);
    }

    private static int site(String slug, boolean deleted) {
        Model sites = Models.get(SiteModel.class);
        Row row = sites.createEmptyRow();
        row.set(SiteModel.NAME, slug);
        row.set(SiteModel.SLUG, slug);
        row.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        row.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        row.set(SiteModel.STATUS, "active");
        row.set(SiteModel.ENABLED, !deleted);
        sites.save(row);
        if (deleted) {
            row.set(SiteModel.DELETED_AT, Now.instant());
            sites.save(row);
        }
        return row.get(SiteModel.ID);
    }

    private static int domain(int siteId, String hostname) {
        Model domains = Models.get(SiteDomainModel.class);
        Row row = domains.createEmptyRow();
        row.set(SiteDomainModel.SITE_ID, siteId);
        row.set(SiteDomainModel.HOSTNAME, hostname);
        row.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        row.set(SiteDomainModel.FORCE_SSL, false);
        domains.save(row);
        return row.get(SiteDomainModel.ID);
    }
}
