package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A converted parts entry whose tabs take no contributions still offers zenit-auth's Access tab, and sharing through
 * it grants the record: the access page rides every parts entry over a grantable model (decided 2026-10-03).
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
class ConvertedEntryAccessTabTest extends HohenheimTestBase {

    private static final String PREFIX = "converted-access-";

    @Test
    void aCertificateSharesThroughItsAccessTab() throws Exception {
        int tenantId = ApiSupport.user(PREFIX + "tenant@hohenheim.local", "Converted Access Tenant");
        int certificateId = certificate();
        try {
            // 1. The certificate entry declares no contributions, yet its record offers the Access tab.
            HttpResponse<String> tab = adminGet("/admin/certificates/" + certificateId + "/page/access");
            assertThat(tab.statusCode()).as("step 1: the converted entry's Access tab renders").isEqualTo(200);
            assertThat(tab.body()).as("step 1: as zenit-auth's sharing control").contains("<za-record-sharing");
            assertThat(tenantReaches(tenantId, certificateId))
                .as("step 1: the tenant does not see the certificate yet").isFalse();

            // 2. Saving a view share through the tab's PAGE placement grants the tenant the certificate.
            HttpResponse<String> saved = adminPostForm(
                "/admin/certificates/invoke/zenit.save_record_sharing?ids=" + certificateId,
                "_tab=access&_confirmed=1&access.0.type=user&access.0.id=" + tenantId
                    + "&access.0.caps.0.key=" + HohenheimCapabilities.VIEW + "&access.0.caps.0.value=allow");
            assertThat(saved.statusCode()).as("step 2: the share saves").isIn(302, 303);
            assertThat(tenantReaches(tenantId, certificateId))
                .as("step 2: the shared certificate is the tenant's to view").isTrue();

            // 3. The tab now lists the tenant as a subject of the record.
            assertThat(adminGet("/admin/certificates/" + certificateId + "/page/access").body())
                .as("step 3: the grant is a subject row of the tab").contains("data-subject=\"user:" + tenantId + "\"");
        } finally {
            RecordGrants.revoke(GrantSubjectType.USER, tenantId, CertificateModel.MODEL_ID, certificateId,
                HohenheimCapabilities.VIEW);
            HardDeletes.byId(Models.get(CertificateModel.class), certificateId);
        }
    }

    private static boolean tenantReaches(int tenantId, int certificateId) {
        AccessContext tenant = AccessContext.of(TenantConduits.stubFor(
            new UserPrincipal(tenantId, "Converted Access Tenant")));
        return HohenheimAccess.reachesRecord(tenant, CertificateModel.MODEL_ID, certificateId,
            HohenheimCapabilities.VIEW);
    }

    /** A custom certificate, the CertificateSurfacesBrowserTest fixture's shape. */
    private static int certificate() {
        Model model = Models.get(CertificateModel.class);
        Row certificate = model.createEmptyRow();
        certificate.set(CertificateModel.NICE_NAME, PREFIX + "custom");
        certificate.set(CertificateModel.PROVIDER, CertificateModel.PROVIDER_CUSTOM);
        certificate.set(CertificateModel.STATUS, CertificateModel.STATUS_ACTIVE);
        certificate.set(CertificateModel.DOMAIN_NAMES_TEXT, "converted-access.test");
        model.save(certificate);
        return certificate.get(CertificateModel.ID);
    }
}
