package be.elevenways.hohenheim.test.tls;

import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.ApiKeyService;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The line between "an admin-scoped API key may READ admin surfaces" (the accepted
 * residual) and "an admin-scoped API key may retrieve a CREDENTIAL" (never), driven from
 * the attacker's side: a long-lived non-interactive token asks for a TLS private key.
 *
 * Both halves are asserted in ONE journey on purpose. The refusal alone would pass
 * against a key that cannot reach anything, and the accepted residual alone would pass
 * against a build with no distinction at all -- it is the PAIR (same key, same
 * permission, one route refused and one allowed) that pins where the line actually is.
 */
class CertificateDownloadCredentialTest extends HohenheimTestBase {

    private static final String PREFIX = "certdl-";

    /** Recognisable, so "the body carries the key" is an assertion and not a guess. */
    private static final String KEY_PEM =
        "-----BEGIN PRIVATE KEY-----\n" + PREFIX + "PRIVATE-KEY-MATERIAL\n-----END PRIVATE KEY-----";

    private static final String CERT_PEM =
        "-----BEGIN CERTIFICATE-----\n" + PREFIX + "PUBLIC-CERTIFICATE\n-----END CERTIFICATE-----";

    private static Integer certificateId;
    private static Integer templateId;
    private static String adminKey;

    @BeforeAll
    static void seed() {
        Row admin = AuthModels.users().find()
            .where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        // The harness admin holds "*", so the key below is narrowed by its SCOPE alone --
        // exactly the credential an operator mints for automation. Both nodes are needed:
        // zenit-auth's own /admin prefix baseline demands auth.admin.access on top of the
        // endpoint's hohenheim.admin.access, and a key missing either would 403 for a
        // reason that has nothing to do with what this test is about.
        adminKey = ApiKeyService.create(admin.get(UserModel.ID), PREFIX + "automation",
            List.of("auth.admin.access", "hohenheim.admin.access"), null).plaintext();

        Model certificates = Models.get(CertificateModel.class);
        Row certificate = certificates.createEmptyRow();
        certificate.set(CertificateModel.NICE_NAME, PREFIX + "bundle");
        certificate.set(CertificateModel.PROVIDER, "custom");
        certificate.set(CertificateModel.STATUS, "active");
        certificate.set(CertificateModel.CERTIFICATE_PEM, CERT_PEM);
        certificate.set(CertificateModel.PRIVATE_KEY_PEM, KEY_PEM);
        certificates.save(certificate);
        certificateId = certificate.get(CertificateModel.ID);

        Model templates = Models.get(InstanceTemplateModel.class);
        Row template = templates.createEmptyRow();
        template.set(InstanceTemplateModel.NAME, PREFIX + "catalog-entry");
        template.set(InstanceTemplateModel.KIND, "hohenheim:docker_container");
        template.set(InstanceTemplateModel.SETTINGS, new LinkedHashMap<>(
            Map.of("image", "alpine", "tag", "latest")));
        templates.save(template);
        templateId = template.get(InstanceTemplateModel.ID);
    }

    @AfterAll
    static void cleanUp() {
        if (certificateId != null) {
            Models.get(CertificateModel.class).delete(certificateId);
        }
        if (templateId != null) {
            Models.get(InstanceTemplateModel.class).delete(templateId);
        }
    }

    @Test
    void aNonInteractiveKeyIsRefusedThePrivateKeyAndStillReadsTheCatalog() throws Exception {
        String download = "/certificates/" + certificateId + "/download";

        // 1. POSITIVE ANCHOR, so every refusal below means "this credential", never
        //    "this route is broken": the operator's own INTERACTIVE session downloads the
        //    bundle and the private key really is in it. Without this step a build that
        //    simply stopped serving the bundle would pass the whole test.
        HttpResponse<String> interactive = sessionGet(download);
        assertThat(interactive.statusCode())
            .as("step 1: an interactive operator still downloads the bundle").isEqualTo(200);
        assertThat(interactive.body())
            .as("step 1: and the bundle really does carry the PRIVATE KEY -- that is what"
                + " makes this route a credential rather than an admin page")
            .contains(KEY_PEM)
            .contains(CERT_PEM);

        // 2. THE ATTACK: the same authority behind a long-lived non-interactive token.
        //    Being a GET, no CSRF layer stands anywhere in its way, so the endpoint's own
        //    declaration is the only gate there is.
        HttpResponse<String> keyed = keyGet(download);
        assertThat(keyed.statusCode())
            .as("step 2: an API key is refused the certificate bundle outright")
            .isEqualTo(403);
        assertThat(keyed.body())
            .as("step 2: BY THE INTERACTIVE GATE and not by a scope miss -- naming the"
                + " enforcer is what stops this passing for the wrong reason")
            .contains("INTERACTIVE_LOGIN_REQUIRED");

        // 3. STATE OF THE BODY, not just the status: a refusal that still streamed the
        //    PEM would pass a status-only assertion.
        assertThat(keyed.body())
            .as("step 3: and no byte of the private key reaches it")
            .doesNotContain(KEY_PEM)
            .doesNotContain("PRIVATE KEY")
            .doesNotContain(CERT_PEM);

        // 4. The SAME key still reaches the admin data it is legitimately for. This is the
        //    accepted residual, asserted so nobody later reads step 2 as "API keys are
        //    banned from /admin" and closes the wrong things: a template export is
        //    operator-authored catalog content, not a credential.
        HttpResponse<String> export =
            keyGet("/admin/instance-templates/" + templateId + "/export");
        assertThat(export.statusCode())
            .as("step 4: the same key still exports a template -- the line is CREDENTIAL,"
                + " not admin-versus-not")
            .isEqualTo(200);
        assertThat(export.body())
            .as("step 4: and gets the real document")
            .contains(PREFIX + "catalog-entry");
    }

    // -- fixtures -------------------------------------------------------------

    private String baseUrl() {
        return "http://localhost:" + getServerPort();
    }

    private HttpResponse<String> keyGet(String path) throws Exception {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
            .send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("X-Api-Key", adminKey)
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sessionGet(String path) throws Exception {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
            .send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionToken)
                .build(), HttpResponse.BodyHandlers.ofString());
    }
}
