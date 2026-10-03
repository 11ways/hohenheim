package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.cms.CertificateParts;
import be.elevenways.zenit.cms.common.render.table.SynthesizedRowActions;
import be.elevenways.zenit.cms.test.support.PlacedOperationMoves;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.cms.test.support.PanelSurfaces;
import be.elevenways.zenit.cms.test.support.SurfaceBaselines;
import be.elevenways.zenit.cms.test.support.SurfaceCase;
import be.elevenways.zenit.cms.test.support.SurfaceFact;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.PrincipalRef;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Immutable before/after surface evidence for the certificate administration and its status-only tenant twin.
 *
 * @author Jelle De Loecker
 * @since 0.9.0
 */
class CertificateSurfacesBrowserTest extends HohenheimTestBase {

    private static final String ENTRY = HohenheimSlugs.CERTIFICATES;
    private static final String ADMIN = HohenheimSlugs.ADMIN;
    private static final String MANAGE = HohenheimSlugs.MANAGE;
    private static String customId;
    private static String issuedId;
    private static String accountId;
    private static AccessContext operator;
    private static AccessContext tenant;

    @BeforeAll
    static void seed() {
        int tenantId = ApiSupport.user("certificate-surfaces@hohenheim.local", "Certificate Surfaces Tenant");
        // A certificate requester enters /manage through an actual site grant, not installation authority.
        Model sites = Models.get(SiteModel.class);
        Row site = sites.createEmptyRow();
        site.set(SiteModel.NAME, "certificate-surfaces");
        site.set(SiteModel.SLUG, "certificate-surfaces");
        site.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        site.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        site.set(SiteModel.STATUS, "active");
        site.set(SiteModel.ENABLED, true);
        sites.save(site);
        RecordGrants.grant(GrantSubjectType.USER, tenantId, SiteModel.MODEL_ID, site.get(SiteModel.ID),
            HohenheimAccess.MANAGE, true);
        operator = TenantConduits.operator();
        tenant = AccessContext.of(TenantConduits.stubFor(new UserPrincipal(tenantId, "Certificate Surfaces Tenant")));
        customId = certificate("surface-custom", CertificateModel.PROVIDER_CUSTOM, "custom.surfaces.test", tenantId);
        issuedId = certificate("surface-issued", CertificateModel.PROVIDER_LETSENCRYPT,
            "issued.surfaces.test, *.issued.surfaces.test", null);
        accountId = certificate("surface-account", CertificateModel.PROVIDER_ACME_ACCOUNT, "", null);
    }

    @Test
    void theCertificateTwinsKeepTheirCapturedSurfaces() {
        SurfaceBaselines before = SurfaceBaselines.load(CertificateSurfacesBrowserTest.class,
            "/panel-surfaces/certificates.txt").placedOperations(PlacedOperationMoves.NONE
                .synthesized(ENTRY, SynthesizedRowActions.DELETE, CertificateParts.DELETE.id()));

        // 1. The operator has the full list/form; a tenant cannot enter installation administration.
        before.check(capture(SurfaceCase.of(ADMIN, ENTRY, "operator", operator)));
        before.check(capture(SurfaceCase.of(ADMIN, ENTRY, "tenant", tenant)
            .refusedFor(ZenitRefusalReason.FORBIDDEN)));
        before.check(capture(SurfaceCase.of(ADMIN, ENTRY, "operator", operator).onRecord(customId, "custom")));
        before.check(capture(SurfaceCase.of(ADMIN, ENTRY, "operator", operator).onRecord(issuedId, "issued")));
        before.check(capture(SurfaceCase.of(ADMIN, ENTRY, "operator", operator)
            .selecting(List.of(customId, issuedId), "selection")));

        // 2. The internal account row is never a certificate offered by the resource.
        assertMissing(before.check(capture(SurfaceCase.of(ADMIN, ENTRY, "operator", operator)
            .onRecord(accountId, "account"))));

        // 3. The tenant reads status only: no PEM, upload, edit, delete or download is restored by the conversion.
        before.check(capture(SurfaceCase.of(MANAGE, ENTRY, "tenant", tenant)));
        before.check(capture(SurfaceCase.of(MANAGE, ENTRY, "tenant", tenant).onRecord(customId, "custom")));
        assertMissing(before.check(capture(SurfaceCase.of(MANAGE, ENTRY, "tenant", tenant)
            .onRecord(issuedId, "foreign"))));
        before.check(capture(SurfaceCase.of(MANAGE, ENTRY, "operator", operator).onRecord(customId, "custom")));

        // 4. Every before case and every after case participates in the exact comparison.
        before.finish();
    }

    private static void assertMissing(PanelSurfaces surfaces) {
        assertThat(surfaces.factsOf(SurfaceFact.Kind.RECORD))
            .as("the admitted panel does not widen the record read scope for %s", surfaces.caseName())
            .containsExactly(SurfaceFact.of(SurfaceFact.Kind.RECORD, "-", false));
    }
    private static PanelSurfaces capture(SurfaceCase fixture) {
        return PanelSurfaces.capture(fixture.key(ENTRY, "custom", customId).key(ENTRY, "issued", issuedId)
            .key(ENTRY, "account", accountId).key("cert_id", "custom", customId)
            .key("cert_id", "issued", issuedId));
    }

    private static String certificate(String name, String provider, String domains, Integer requester) {
        Model model = Models.get(CertificateModel.class);
        Row certificate = model.createEmptyRow();
        certificate.set(CertificateModel.NICE_NAME, name);
        certificate.set(CertificateModel.PROVIDER, provider);
        certificate.set(CertificateModel.STATUS, CertificateModel.STATUS_ACTIVE);
        certificate.set(CertificateModel.DOMAIN_NAMES_TEXT, domains);
        if (requester != null) {
            CertificateModel.setRequester(certificate, PrincipalRef.account(requester));
        }
        model.save(certificate);
        return String.valueOf((Integer) certificate.get(CertificateModel.ID));
    }
}
