package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.instance.ReadinessKind;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.cms.test.support.PanelSurfaceComparer;
import be.elevenways.zenit.cms.test.support.PanelSurfaces;
import be.elevenways.zenit.cms.test.support.SurfaceBaselines;
import be.elevenways.zenit.cms.test.support.SurfaceCase;
import be.elevenways.zenit.cms.test.support.TwinCorrespondence;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.security.AccessContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The slice-three entries' surfaces, admin and tenant twins, stored before the move and compared exactly after it;
 * each tenant twin against its admin twin through its explicit difference table (stage 4 contract 4.9, journey "the
 * tenant panel offers exactly what it offers today", DECIDED D2-B07/B08).
 *
 * AIDEV-NOTE: the stored set ({@code /panel-surfaces/manage-slice-three.txt}) is today's behaviour, captured once on
 * the stage 4 branch before SiteResource, SiteDomainResource and the template resources move onto parts; a failing
 * comparison is a changed /manage or /admin surface, never a file to refresh. A twin table entry states one
 * deliberate tenant difference; an unlisted difference fails closed.
 */
class ManagePanelSurfacesBrowserTest extends HohenheimTestBase {

    private static final String PREFIX = "surfaces-";
    private static final String ADMIN = HohenheimSlugs.ADMIN;
    private static final String MANAGE = HohenheimSlugs.MANAGE;
    private static final String SITES = HohenheimSlugs.SITES;
    private static final String DOMAINS = "domains";
    private static final String TEMPLATES = HohenheimSlugs.INSTANCE_TEMPLATES;

    private static String siteId;
    private static String domainId;
    private static String approvedTemplateId;
    private static String unapprovedTemplateId;
    private static AccessContext operator;
    private static AccessContext tenantOne;
    private static AccessContext tenantEmpty;

    @BeforeAll
    static void seed() {
        int oneId = ApiSupport.user(PREFIX + "one@hohenheim.local", "Surfaces Tenant One");
        int emptyId = ApiSupport.user(PREFIX + "empty@hohenheim.local", "Surfaces Tenant Empty");
        int site = site(PREFIX + "site");
        siteId = String.valueOf(site);
        domainId = String.valueOf(domain(site, PREFIX + "site.surfaces.test"));
        approvedTemplateId = String.valueOf(template(PREFIX + "approved", true));
        unapprovedTemplateId = String.valueOf(template(PREFIX + "unapproved", false));
        RecordGrants.grant(GrantSubjectType.USER, oneId, SiteModel.MODEL_ID, site, HohenheimAccess.MANAGE, true);
        operator = access(operatorPrincipal());
        tenantOne = access(new UserPrincipal(oneId, "Surfaces Tenant One"));
        tenantEmpty = access(new UserPrincipal(emptyId, "Surfaces Tenant Empty"));
    }

    @Test
    void theSliceThreeEntriesOfferWhatTheyOfferedBeforeTheMove() {
        SurfaceBaselines stored = SurfaceBaselines.load(ManagePanelSurfacesBrowserTest.class,
            "/panel-surfaces/manage-slice-three.txt");

        // 1. The admin entries for the operator, record-less and on each record; a tenant is refused the panel.
        for (String entry : List.of(SITES, DOMAINS, TEMPLATES)) {
            stored.check(capture(SurfaceCase.of(ADMIN, entry, "operator", operator)));
            stored.check(capture(SurfaceCase.of(ADMIN, entry, "tenant-one", tenantOne)
                .refusedFor(ZenitRefusalReason.FORBIDDEN)));
        }
        stored.check(capture(SurfaceCase.of(ADMIN, SITES, "operator", operator)
            .onRecord(siteId, "site")));
        stored.check(capture(SurfaceCase.of(ADMIN, DOMAINS, "operator", operator)
            .onRecord(domainId, "domain")));
        stored.check(capture(SurfaceCase.of(ADMIN, TEMPLATES, "operator", operator)
            .onRecord(approvedTemplateId, "approved")));
        stored.check(capture(SurfaceCase.of(ADMIN, TEMPLATES, "operator", operator)
            .onRecord(unapprovedTemplateId, "unapproved")));

        // 2. The /manage twins for a one-site tenant, record-less and on each record; the panel refuses a tenant
        //    holding nothing.
        for (String entry : List.of(SITES, DOMAINS, TEMPLATES)) {
            stored.check(capture(SurfaceCase.of(MANAGE, entry, "tenant-one", tenantOne)));
            stored.check(capture(SurfaceCase.of(MANAGE, entry, "tenant-empty", tenantEmpty)
                .refusedFor(ZenitRefusalReason.FORBIDDEN)));
        }
        stored.check(capture(SurfaceCase.of(MANAGE, SITES, "tenant-one", tenantOne)
            .onRecord(siteId, "site")));
        stored.check(capture(SurfaceCase.of(MANAGE, DOMAINS, "tenant-one", tenantOne)
            .onRecord(domainId, "domain")));
        stored.check(capture(SurfaceCase.of(MANAGE, TEMPLATES, "tenant-one", tenantOne)
            .onRecord(approvedTemplateId, "approved")));
        stored.check(capture(SurfaceCase.of(MANAGE, TEMPLATES, "tenant-one", tenantOne)
            .onRecord(unapprovedTemplateId, "unapproved")));

        // 3. Each tenant twin against its admin twin on the same record, through its explicit difference table.
        List<AssertionError> twins = new ArrayList<>();
        twin(twins, stored, MANAGE + "." + SITES + ".tenant-one.site", ADMIN + "." + SITES + ".operator.site",
            sitesTable());
        twin(twins, stored, MANAGE + "." + DOMAINS + ".tenant-one.domain", ADMIN + "." + DOMAINS + ".operator.domain",
            domainsTable());
        twin(twins, stored, MANAGE + "." + TEMPLATES + ".tenant-one.approved",
            ADMIN + "." + TEMPLATES + ".operator.approved", templatesTable());

        // 4. Every stored case matched exactly, and every twin difference is listed.
        List<String> failures = new ArrayList<>();
        try {
            stored.finish();
        } catch (AssertionError mismatch) {
            failures.add(mismatch.getMessage());
        }
        twins.forEach(twin -> failures.add(twin.getMessage()));
        if (!failures.isEmpty()) {
            throw new AssertionError(String.join("\n\n", failures));
        }
    }

    /** A capture whose domain listen_on Select lists the host's own addresses, which no stored set can pin. */
    private static PanelSurfaces capture(SurfaceCase fixture) {
        return PanelSurfaces.capture(fixture).withHostOptions("listen_on");
    }

    private static void twin(List<AssertionError> twins, SurfaceBaselines stored, String tenantCase,
                             String adminCase, TwinCorrespondence table) {
        try {
            PanelSurfaceComparer.assertNarrower(stored.captured(tenantCase), stored.captured(adminCase), table);
        } catch (AssertionError difference) {
            twins.add(difference);
        }
    }

    /** The /manage site twin's deliberate differences from the admin site resource. */
    private static TwinCorrespondence sitesTable() {
        return TwinCorrespondence.between(MANAGE + "/" + SITES, ADMIN + "/" + SITES)
            // ListChrome.MINIMAL, no trash on /manage.
            .own("chrome - views=false advanced=false search=true columns=false rail=false selection=false"
                + " export=false trash=false tree=false searchable=true")
            // Its own plain name column (no slug subtext, no filter) and a shown enabled column.
            .own("column name shown=true hidden=false sortable=false filterable=false copyable=false subtext="
                + " relation=false")
            .own("column enabled shown=true hidden=false sortable=false filterable=false copyable=false subtext="
                + " relation=false");
    }

    /** The /manage domain twin's deliberate differences from the admin domain resource. */
    private static TwinCorrespondence domainsTable() {
        return TwinCorrespondence.between(MANAGE + "/" + DOMAINS, ADMIN + "/" + DOMAINS);
    }

    /** The /manage template twin's deliberate differences from the admin template resource. */
    private static TwinCorrespondence templatesTable() {
        return TwinCorrespondence.between(MANAGE + "/" + TEMPLATES, ADMIN + "/" + TEMPLATES)
            // Its own list: name with a description subtext, a hidden description source and a shown version.
            .own("column name shown=true hidden=false sortable=false filterable=false copyable=false"
                + " subtext=description relation=false")
            .own("column description shown=false hidden=true sortable=false filterable=false copyable=false subtext="
                + " relation=false")
            .own("column version shown=true hidden=false sortable=false filterable=false copyable=false subtext="
                + " relation=false")
            // A tenant reads an approved template's form; it never edits it.
            .own("control EDIT%20name kind=zenitforms:form/plain required=true readonly=true options=")
            .own("control EDIT%20description kind=zenitforms:form/plain required=false readonly=true options=")
            .own("control EDIT%20version kind=zenitforms:form/plain required=false readonly=true options=");
    }

    private static AccessContext access(UserPrincipal principal) {
        return AccessContext.of(TenantConduits.stubFor(principal));
    }

    private static UserPrincipal operatorPrincipal() {
        Row admin = AuthModels.users().find().where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        return new UserPrincipal(admin.get(UserModel.ID), "Test Admin");
    }

    private static int site(String slug) {
        Model sites = Models.get(SiteModel.class);
        Row row = sites.createEmptyRow();
        row.set(SiteModel.NAME, slug);
        row.set(SiteModel.SLUG, slug);
        row.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        row.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        row.set(SiteModel.STATUS, "active");
        row.set(SiteModel.ENABLED, true);
        sites.save(row);
        return row.get(SiteModel.ID);
    }

    private static int domain(int site, String hostname) {
        Model domains = Models.get(SiteDomainModel.class);
        Row row = domains.createEmptyRow();
        row.set(SiteDomainModel.SITE_ID, site);
        row.set(SiteDomainModel.HOSTNAME, hostname);
        row.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        row.set(SiteDomainModel.FORCE_SSL, false);
        domains.save(row);
        return row.get(SiteDomainModel.ID);
    }

    private static int template(String name, boolean approved) {
        Model templates = Models.get(InstanceTemplateModel.class);
        Row row = templates.createEmptyRow();
        row.set(InstanceTemplateModel.NAME, name);
        row.set(InstanceTemplateModel.DESCRIPTION, "surfaces fixture");
        row.set(InstanceTemplateModel.KIND, "hohenheim:docker_container");
        row.set(InstanceTemplateModel.VERSION, 1);
        row.set(InstanceTemplateModel.READINESS_KIND, ReadinessKind.CONSOLE_LINE.token());
        row.set(InstanceTemplateModel.READINESS_LINE, "ready");
        row.set(InstanceTemplateModel.STOP_COMMAND, "stop");
        if (approved) {
            row.set(InstanceTemplateModel.APPROVED_AT, Now.instant());
        }
        templates.save(row);
        return row.get(InstanceTemplateModel.ID);
    }
}
