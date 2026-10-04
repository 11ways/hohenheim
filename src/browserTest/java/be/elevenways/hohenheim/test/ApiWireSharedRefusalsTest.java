package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.instance.ApplicationKind;
import be.elevenways.hohenheim.test.ApiWire.Caller;
import be.elevenways.zenit.auth.server.ApiKeyService;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The refusals every {@code /api/v1} route shares -- the uniform 404, no or an unknown key, a browser session, the
 * rate limit and an oversized body -- compared byte for byte to the java-rewrite capture through {@link ApiWire}.
 *
 * AIDEV-NOTE: the rate-limit exchanges spend the declared bucket of a key minted for them alone, so the sixth
 * backup call of the window is the 429 whatever ran before; the five before it are the uniform 404 of an absent
 * record, which the limiter admits first.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
class ApiWireSharedRefusalsTest extends HohenheimTestBase {

    private static final Instant T0 = Instant.parse("2026-01-02T03:04:05Z");

    /** The framework's default request body cap, which a form body one byte over is refused against. */
    private static final int BODY_LIMIT_BYTES = 20 * 1024 * 1024;

    private static Integer savedMaxUploadMb;

    private static Caller admin;
    private static Caller limited;
    private static Caller operatorSession;
    private static int siteId;

    @BeforeAll
    static void seed() throws Exception {
        TestDatabases.freshDatabase();
        savedMaxUploadMb = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Builds.MAX_UPLOAD_MB);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Builds.MAX_UPLOAD_MB, 1);

        int operatorId = ApiWire.operatorId();
        Row application = Models.get(InstanceModel.class).createEmptyRow();
        application.set(InstanceModel.NAME, "wire-refusals-app");
        application.set(InstanceModel.KIND, ApplicationKind.ID.toString());
        application.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "fake/app", "tag", "v1")));
        application.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
        application.set(InstanceModel.CREATED_AT, T0);
        Models.get(InstanceModel.class).save(application);
        Row site = Models.get(SiteModel.class).createEmptyRow();
        site.set(SiteModel.NAME, "wire-refusals");
        site.set(SiteModel.SLUG, "wire-refusals");
        site.set(SiteModel.UPSTREAM_KIND, "hohenheim:instance");
        site.set(SiteModel.ENABLED, false);
        site.set(SiteModel.INSTANCE_ID, application.get(InstanceModel.ID));
        Models.get(SiteModel.class).save(site);
        siteId = site.get(SiteModel.ID);

        admin = new Caller.Key(ApiKeyService.create(operatorId, "wire-refusals-admin", List.of("hohenheim.*"), null)
            .plaintext());
        limited = new Caller.Key(ApiKeyService.create(operatorId, "wire-refusals-limited", List.of("hohenheim.*"),
            null).plaintext());
        operatorSession = new Caller.Session(sessionCookieHeader(sessionFor(operatorId).token()));
    }

    @AfterAll
    static void tearDown() throws Exception {
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Builds.MAX_UPLOAD_MB, savedMaxUploadMb);
        // Later classes of this JVM find the seeded shared database they expect.
        freshSeededDatabase();
    }

    @Test
    void theSharedRefusalsAnswerWhatJavaRewriteAnswered() {
        ApiWire wire = new ApiWire("shared-refusals", this::requestTo, HohenheimTestBase::sendRequestBytes);

        // 1. The uniform 404: an absent record of a declared route, and a path no route declares.
        wire.get("an absent record", admin, "/api/v1/instances/999999");
        wire.post("a write to an absent record", admin, "/api/v1/sites/999999/delete", "");
        wire.get("a path no route declares", admin, "/api/v1/no-such-route");

        // 2. No key, an unknown key and a browser session.
        wire.get("a read without a key", new Caller.Anonymous(), "/api/v1/instances");
        wire.post("a write without a key", new Caller.Anonymous(), "/api/v1/sites", "name=wire-anonymous");
        wire.get("a read with an unknown key", new Caller.Key("znit_wire_unknown_key"), "/api/v1/instances");
        wire.get("a read as a browser session", operatorSession, "/api/v1/instances");
        wire.post("a write as a browser session", operatorSession, "/api/v1/sites", "name=wire-session");

        // 3. The rate limit: the backup route admits five calls of a key per window, the sixth is refused.
        ApiWire.withRateLimits(() -> {
            for (int call = 1; call <= 6; call++) {
                wire.post("backup call " + call + " of a five-call window", limited,
                    "/api/v1/instances/999999/backup", "");
            }
        });

        // 4. Oversized bodies: an artifact over the upload cap, and a form body over the framework's cap.
        byte[] artifact = new byte[1024 * 1024 + 1];
        Arrays.fill(artifact, (byte) 'a');
        wire.post("an artifact over the upload cap", admin, "/api/v1/sites/" + siteId + "/artifact", artifact,
            "application/octet-stream");
        byte[] form = new byte[BODY_LIMIT_BYTES + 1];
        Arrays.fill(form, (byte) 'a');
        System.arraycopy("name=".getBytes(StandardCharsets.UTF_8), 0, form, 0, 5);
        wire.post("a form body over the request cap", admin, "/api/v1/sites", form,
            "application/x-www-form-urlencoded");

        // 5. Every reply is the java-rewrite one.
        wire.assertGolden();
    }
}
