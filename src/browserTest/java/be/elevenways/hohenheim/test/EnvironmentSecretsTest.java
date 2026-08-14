package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.activity.ActivityModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.revision.RevisionModel;
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
 * The Phase 0 "Secrets" gate for the LARGEST plaintext surface: a REAL site's
 * {@code environment_variables} (a DATABASE_PASSWORD-grade value typed through
 * the REAL admin form) must be absent from every new revision snapshot and
 * activity delta, while ordinary settings changes stay visible; a doctored
 * legacy snapshot holding plaintext env must not reactivate it on restore; and
 * the form keeps env EDITABLE (keys visible, per-key keep-on-blank values).
 */
class EnvironmentSecretsTest extends HohenheimTestBase {

    private static final String PASSWORD_V1 = "pw-v1-hunter2-9f8e7d6c5b4a";
    private static final String PASSWORD_V2 = "pw-v2-tr0ub4dor-3c2b1a0f9e8d";
    private static final String LEGACY_PASSWORD = "legacy-plaintext-pw-0102030405";

    private HttpResponse<String> postForm(String path, String body) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + path))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionToken)
            .header("X-Csrf-Token", csrfToken)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getPage(String path) throws Exception {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
            .send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionToken)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void realPasswordNeverEntersDerivedSurfacesAndLegacyRestoreCannotRevive() throws Exception {
        SiteModel sites = Models.get(SiteModel.class);

        // 1. A REAL admin creates a REAL node site with a database password in its
        //    environment, through the real form transport.
        var created = postForm("/admin/sites/new",
            "name=Env+Secret+Site&site_type=hohenheim%3Anode&source=local"
            + "&settings.script=&settings.use_ports=true"
            + "&settings.environment_variables.0.key=DATABASE_PASSWORD"
            + "&settings.environment_variables.0.value=" + PASSWORD_V1
            + "&settings.environment_variables.1.key=NODE_ENV"
            + "&settings.environment_variables.1.value=production-mode");
        assertThat(created.statusCode())
            .as("1. the create submit must be accepted").isIn(200, 302, 303);
        Row site = sites.find().where(SiteModel.NAME.eq("Env Secret Site")).first();
        assertThat(site).as("1. the site must exist").isNotNull();
        int siteId = site.get(SiteModel.ID);
        assertThat(envOf(siteId))
            .as("1. the values must be stored AT REST unchanged (redaction is derived-surface only)")
            .containsEntry("DATABASE_PASSWORD", PASSWORD_V1)
            .containsEntry("NODE_ENV", "production-mode");

        // 2. An update rotates the password and flips an ordinary setting.
        var updated = postForm("/admin/sites/" + siteId,
            "name=Env+Secret+Site+Renamed&site_type=hohenheim%3Anode&source=local"
            + "&settings.script=&settings.use_ports=false"
            + "&settings.environment_variables.0.key=DATABASE_PASSWORD"
            + "&settings.environment_variables.0.value=" + PASSWORD_V2
            + "&settings.environment_variables.1.key=NODE_ENV"
            + "&settings.environment_variables.1.value=staging-mode");
        assertThat(updated.statusCode())
            .as("2. the update submit must be accepted").isIn(200, 302, 303);
        assertThat(envOf(siteId))
            .as("2. the rotated values must be stored at rest")
            .containsEntry("DATABASE_PASSWORD", PASSWORD_V2)
            .containsEntry("NODE_ENV", "staging-mode");

        // 3. NO revision snapshot of this record may contain either password, and the
        //    snapshot's settings map must OMIT environment_variables while keeping
        //    ordinary settings keys.
        List<Row> revisions = revisionRows(siteId);
        assertThat(revisions).as("3. both saves must have appended a revision")
            .hasSizeGreaterThanOrEqualTo(2);
        for (Row revision : revisions) {
            String snapshotText = revision.get(RevisionModel.SNAPSHOT);
            assertThat(snapshotText)
                .as("3. revision %s must not contain any password plaintext",
                    revision.get(RevisionModel.REVISION))
                .doesNotContain(PASSWORD_V1)
                .doesNotContain(PASSWORD_V2);
        }
        Map<String, Object> latestSnapshot = SiteModel.REVISIONABLE.snapshotOf(
            sites, siteId, revisions.get(revisions.size() - 1).get(RevisionModel.REVISION));
        @SuppressWarnings("unchecked")
        Map<String, Object> snapshotSettings = (Map<String, Object>) latestSnapshot.get("settings");
        assertThat(snapshotSettings)
            .as("3. the snapshot's settings map must omit the secret env map entirely")
            .doesNotContainKey("environment_variables");
        assertThat(snapshotSettings)
            .as("3. ...while ordinary settings keys stay snapshotted")
            .containsKey("use_ports");

        // 4. NO activity delta may contain a password; the update delta must still
        //    SHOW that the environment changed ([redacted] pair) and keep the
        //    ordinary changes readable.
        List<Row> activity = activityRows(siteId);
        assertThat(activity).as("4. create + update must both be logged (ALL policy)")
            .hasSizeGreaterThanOrEqualTo(2);
        for (Row entry : activity) {
            String deltaText = entry.get(ActivityModel.DELTA);
            if (deltaText == null) {
                continue;
            }
            assertThat(deltaText)
                .as("4. activity delta of action '%s' must not contain any password plaintext",
                    entry.get(ActivityModel.ACTION))
                .doesNotContain(PASSWORD_V1)
                .doesNotContain(PASSWORD_V2);
        }
        Row updateEntry = activity.stream()
            .filter(entry -> "update".equals(entry.get(ActivityModel.ACTION)))
            .reduce((first, second) -> second).orElse(null);
        assertThat(updateEntry).as("4. the update activity entry must exist").isNotNull();
        Map<?, ?> delta = (Map<?, ?>) Zenit.DRY.parse(updateEntry.get(ActivityModel.DELTA));
        Map<?, ?> settingsChange = (Map<?, ?>) delta.get("settings");
        assertThat(settingsChange).as("4. the delta must record that settings changed").isNotNull();
        Map<?, ?> settingsAfter = (Map<?, ?>) settingsChange.get("after");
        assertThat(settingsAfter.get("environment_variables"))
            .as("4. the delta must collapse the env map to the [redacted] marker, keeping the CHANGE visible")
            .isEqualTo("[redacted]");
        assertThat(((Map<?, ?>) settingsChange.get("before")).get("environment_variables"))
            .as("4. ...on the before side too")
            .isEqualTo("[redacted]");
        assertThat(settingsAfter.get("use_ports"))
            .as("4. ordinary settings changes stay readable per key in the same delta")
            .isEqualTo(false);
        Map<?, ?> nameChange = (Map<?, ?>) delta.get("name");
        assertThat(nameChange.get("after"))
            .as("4. ordinary top-level changes stay readable")
            .isEqualTo("Env Secret Site Renamed");

        // 5. The form stays USABLE: keys render, values never echo, and a submit that
        //    leaves the values blank keeps the stored ones (per-key keep-on-blank).
        HttpResponse<String> form = getPage("/admin/sites/" + siteId);
        assertThat(form.statusCode()).as("5. the edit form must render").isEqualTo(200);
        assertThat(form.body())
            .as("5. the env KEYS must stay visible so the map is editable")
            .contains("DATABASE_PASSWORD");
        assertThat(form.body())
            .as("5. the env VALUES must never echo into the form")
            .doesNotContain(PASSWORD_V2)
            .doesNotContain("staging-mode");
        var blankResubmit = postForm("/admin/sites/" + siteId,
            "name=Env+Secret+Site+Renamed&site_type=hohenheim%3Anode&source=local"
            + "&settings.script=&settings.use_ports=false"
            + "&settings.environment_variables.0.key=DATABASE_PASSWORD"
            + "&settings.environment_variables.0.value="
            + "&settings.environment_variables.1.key=NODE_ENV"
            + "&settings.environment_variables.1.value=");
        assertThat(blankResubmit.statusCode())
            .as("5. the blank-values submit must be accepted").isIn(200, 302, 303);
        assertThat(envOf(siteId))
            .as("5. blank-submitted values must keep the stored secrets, not wipe them")
            .containsEntry("DATABASE_PASSWORD", PASSWORD_V2)
            .containsEntry("NODE_ENV", "staging-mode");

        // 6. A LEGACY pre-redaction snapshot (doctored to carry plaintext env, the
        //    shape old installs still have in zenit_revisions) must NOT reactivate
        //    that env on restore: the current values win, the ghost key vanishes.
        doctorRevisionWithLegacyEnv(siteId);
        SiteModel.REVISIONABLE.restore(sites, siteId, 1);
        Row restored = sites.findById(siteId);
        assertThat((String) restored.get(SiteModel.NAME))
            .as("6. the restore itself must apply (non-secret fields rewind)")
            .isEqualTo("Env Secret Site");
        Map<String, String> envAfterRestore = envOf(siteId);
        assertThat(envAfterRestore)
            .as("6. restore must keep the CURRENT env, never the legacy plaintext")
            .containsEntry("DATABASE_PASSWORD", PASSWORD_V2)
            .containsEntry("NODE_ENV", "staging-mode");
        assertThat(envAfterRestore)
            .as("6. a key that only exists in the legacy snapshot must not resurrect")
            .doesNotContainKey("GHOST_ONLY");
        assertThat(envAfterRestore.values())
            .as("6. the legacy password must be gone from the restored row")
            .doesNotContain(LEGACY_PASSWORD);

        // 7. The rendered history page (DiffRendering) shows the redaction marker,
        //    never a password -- even though the doctored legacy snapshot is still
        //    stored with plaintext.
        navigateToApp("/admin/sites/" + siteId + "/page/history");
        waitForHydration();
        waitForSelector(".cms-record-history-page");
        String history = page.content();
        assertThat(history)
            .as("7. the history feed must never render a password")
            .doesNotContain(PASSWORD_V1)
            .doesNotContain(PASSWORD_V2)
            .doesNotContain(LEGACY_PASSWORD);
    }

    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, String> envOf(int siteId) {
        Object settings = Models.get(SiteModel.class).findById(siteId).get(SiteModel.SETTINGS);
        Object env = settings instanceof Map<?, ?> map ? map.get("environment_variables") : null;
        return env instanceof Map<?, ?> map ? (Map<String, String>) map : Map.of();
    }

    private static List<Row> revisionRows(int siteId) {
        RevisionModel revisions = new RevisionModel(
            Models.get(SiteModel.class).getResolvedDatasource());
        return revisions.find()
            .where(RevisionModel.MODEL.eq(SiteModel.MODEL_ID.toString()))
            .where(RevisionModel.RECORD_ID.eq(String.valueOf(siteId)))
            .all();
    }

    private static List<Row> activityRows(int siteId) {
        ActivityModel activity = new ActivityModel(
            Models.get(SiteModel.class).getResolvedDatasource());
        return activity.find()
            .where(ActivityModel.MODEL.eq(SiteModel.MODEL_ID.toString()))
            .where(ActivityModel.RECORD_ID.eq(String.valueOf(siteId)))
            .all();
    }

    /** Rewrites revision 1's stored snapshot to the pre-redaction shape: plaintext env inside settings. */
    @SuppressWarnings("unchecked")
    private static void doctorRevisionWithLegacyEnv(int siteId) {
        RevisionModel revisions = new RevisionModel(
            Models.get(SiteModel.class).getResolvedDatasource());
        Row revision = revisions.find()
            .where(RevisionModel.MODEL.eq(SiteModel.MODEL_ID.toString()))
            .where(RevisionModel.RECORD_ID.eq(String.valueOf(siteId)))
            .where(RevisionModel.REVISION.eq(1))
            .first();
        assertThat(revision).as("the doctored revision must exist").isNotNull();

        Map<String, Object> snapshot = (Map<String, Object>)
            Zenit.DRY.parse(revision.get(RevisionModel.SNAPSHOT));
        Map<String, Object> fields = (Map<String, Object>) snapshot.get("fields");
        Map<String, Object> settings = new LinkedHashMap<>(
            (Map<String, Object>) fields.get("settings"));
        Map<String, Object> legacyEnv = new LinkedHashMap<>();
        legacyEnv.put("DATABASE_PASSWORD", LEGACY_PASSWORD);
        legacyEnv.put("GHOST_ONLY", "ghost-value");
        settings.put("environment_variables", legacyEnv);
        fields.put("settings", settings);

        revision.set(RevisionModel.SNAPSHOT, Zenit.DRY.stringify(snapshot));
        revisions.save(revision);
    }
}
