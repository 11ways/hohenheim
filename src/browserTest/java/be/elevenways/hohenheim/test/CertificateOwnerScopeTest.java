package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.zenit.auth.CapabilityScopes;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.ApiKeyService;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.data.RecordSourceQuery;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The certificate read scope answers through the WALK ALONE -- the falsifier for the
 * removed hand-written owner disjunct in {@code ManagePanel.certificateScope}.
 *
 * That disjunct ({@code REQUESTED_BY_USER_ID.eq(principalId)}) was a second, WIDER
 * spelling of the walk's owner row: it consulted no credential scope, so an API key
 * narrowed to an unrelated capability -- whose {@code principalId()} is its OWNING
 * USER -- could enumerate every certificate that user ever requested through the open
 * {@code /zn/records/.../query} endpoint. The walk's own owner row demands
 * {@code Principal.coversCapability} first, which is exactly what these journeys pin:
 * the interactive session keeps its requested certificates, the narrowed key gets
 * ZERO rows, and a key that DOES cover the capability keeps them -- so the narrowing,
 * not a blanket key ban, is what decides.
 */
class CertificateOwnerScopeTest extends HohenheimTestBase {

    private static final String OWN_NAME = "Owner scope own certificate";
    private static final String FOREIGN_NAME = "Owner scope foreign certificate";

    private static Integer tenantId;
    private static TestSession tenant;
    private static Integer ownCertId;
    private static Integer foreignCertId;

    /** Scoped to an UNRELATED capability: site#manage covers nothing on certificates. */
    private static String narrowedKey;

    /** Scoped to certificate#view: the positive anchor proving coverage is the axis. */
    private static String coveringKey;

    @BeforeAll
    static void seed() {
        Row user = AuthModels.users().createEmptyRow();
        user.set(UserModel.EMAIL, "cert-owner-scope@hohenheim.local");
        user.set(UserModel.DISPLAY_NAME, "Cert Owner Scope Tenant");
        user.set(UserModel.ENABLED, true);
        user.set(UserModel.CREATED_AT, Instant.now());
        user.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(user);
        tenantId = user.get(UserModel.ID);

        tenant = sessionFor(tenantId);

        Model certs = Models.get(CertificateModel.class);
        Row own = certs.createEmptyRow();
        own.set(CertificateModel.NICE_NAME, OWN_NAME);
        own.set(CertificateModel.PROVIDER, CertificateModel.PROVIDER_CUSTOM);
        own.set(CertificateModel.STATUS, CertificateModel.STATUS_ACTIVE);
        own.set(CertificateModel.REQUESTED_BY_USER_ID, tenantId);
        certs.save(own);
        ownCertId = own.get(CertificateModel.ID);

        Row foreign = certs.createEmptyRow();
        foreign.set(CertificateModel.NICE_NAME, FOREIGN_NAME);
        foreign.set(CertificateModel.PROVIDER, CertificateModel.PROVIDER_CUSTOM);
        foreign.set(CertificateModel.STATUS, CertificateModel.STATUS_ACTIVE);
        certs.save(foreign);
        foreignCertId = foreign.get(CertificateModel.ID);

        narrowedKey = ApiKeyService.create(tenantId, "cert-scope-narrowed",
            List.of(CapabilityScopes.format(SiteModel.MODEL_ID, HohenheimAccess.MANAGE)),
            null).plaintext();
        coveringKey = ApiKeyService.create(tenantId, "cert-scope-covering",
            List.of(CapabilityScopes.format(CertificateModel.MODEL_ID, HohenheimAccess.VIEW)),
            null).plaintext();
    }

    @AfterAll
    static void cleanUp() {
        Model certs = Models.get(CertificateModel.class);
        if (ownCertId != null) {
            certs.delete(ownCertId);
        }
        if (foreignCertId != null) {
            certs.delete(foreignCertId);
        }
    }

    /**
     * The interactive lane is UNCHANGED by the disjunct's removal: the walk's owner row
     * enumerates the requested certificate for a full-authority session.
     */
    @Test
    void theRequesterKeepsSeeingTheirCertificateInteractively() throws Exception {
        HttpResponse<String> queried = sessionQuery();
        assertThat(queried.statusCode())
            .as("step 1: the certificate source answers the requester's session")
            .isEqualTo(200);
        assertThat(queried.body())
            .as("step 1: with the certificate they requested -- the walk's owner row,"
                + " no hand-written disjunct needed")
            .contains(OWN_NAME);
        assertThat(queried.body())
            .as("step 1: and never a certificate they neither requested nor were granted")
            .doesNotContain(FOREIGN_NAME);

        assertThat(sessionGet("/zn/records/hohenheim.certificate/item/" + ownCertId)
                .statusCode())
            .as("step 2: the requested certificate resolves by id too").isEqualTo(200);
        assertThat(sessionGet("/zn/records/hohenheim.certificate/item/" + foreignCertId)
                .statusCode())
            .as("step 2: while the foreign one reads as missing").isEqualTo(404);
    }

    /**
     * THE LEAK, refused: a key narrowed to an unrelated capability enumerates NOTHING,
     * even though its principal id is the very user whose requested certificates the
     * old disjunct would have matched. The covering key right beside it proves the
     * refusal is the SCOPE and not the credential type.
     */
    @Test
    void aNarrowedApiKeyEnumeratesNoRequestedCertificates() throws Exception {
        // 1. The key-reachable lane is the GET item route (the POST query rides CSRF,
        //    which a cookie-less key client cannot satisfy -- asserted in step 3). The
        //    old disjunct answered this probe 200 with the certificate's fields; the
        //    walk's owner row refuses it, because the key's scope does not cover
        //    certificate#view.
        assertThat(keyGet(narrowedKey,
                "/zn/records/hohenheim.certificate/item/" + ownCertId).statusCode())
            .as("step 1: the certificate its OWNING USER requested reads as MISSING"
                + " for a key narrowed to an unrelated capability")
            .isEqualTo(404);
        assertThat(keyGet(narrowedKey,
                "/zn/records/hohenheim.certificate/item/" + foreignCertId).statusCode())
            .as("step 1: exactly like one the user never requested -- one uniform answer,"
                + " no oracle")
            .isEqualTo(404);

        // 2. POSITIVE ANCHOR: a key whose scope covers certificate#view keeps the
        //    owner-implied certificate, so step 1 measured the NARROWING and not a
        //    blanket key ban.
        assertThat(keyGet(coveringKey,
                "/zn/records/hohenheim.certificate/item/" + ownCertId).statusCode())
            .as("step 2: the covering key resolves the requested certificate")
            .isEqualTo(200);
        assertThat(keyGet(coveringKey,
                "/zn/records/hohenheim.certificate/item/" + foreignCertId).statusCode())
            .as("step 2: and still never the foreign one")
            .isEqualTo(404);

        // 3. The set-wise POST lane is CSRF-refused for a bare key client before any
        //    scope question -- defense in depth this test records rather than relies on.
        assertThat(keyQuery(narrowedKey).statusCode())
            .as("step 3: a cookie-less key POST to the query lane is CSRF-refused")
            .isEqualTo(403);
    }

    // -- transport (the shared base helpers, specialized to this source) ---------

    private HttpResponse<String> sessionQuery() throws Exception {
        return httpPostDry("/zn/records/hohenheim.certificate/query",
            Zenit.DRY.stringify(RecordSourceQuery.matchAll()), tenant.token(), tenant.csrf());
    }

    private HttpResponse<String> sessionGet(String path) throws Exception {
        return httpGet(path, tenant.token());
    }

    private HttpResponse<String> keyQuery(String key) throws Exception {
        return keyPostDry(key, "/zn/records/hohenheim.certificate/query",
            Zenit.DRY.stringify(RecordSourceQuery.matchAll()));
    }
}
