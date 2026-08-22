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
 * The Phase 0 "Secrets" gate for a {@code .secret()} leaf inside a site's polymorphic
 * settings map: a REAL credential typed through the REAL admin form must be absent from
 * every new revision snapshot and activity delta, while ordinary settings changes stay
 * visible; a doctored legacy snapshot holding the plaintext must not reactivate it on
 * restore; and the form stays EDITABLE (keep-on-blank).
 *
 * AIDEV-NOTE: the subject was a node site's {@code environment_variables} StringMapField
 * until the upstream rename (phase-0 design section 3) deleted every site type that ran a
 * workload -- an env map is a property of the INSTANCE now, and instances are not
 * revisionable. The dev-namespace {@code registration_token} is the surviving secret leaf
 * in {@code sites.settings}, and it exercises the same FieldRedaction walk over revisions,
 * activity deltas and the history feed. What is NOT covered here any more is the per-KEY
 * keep-on-blank of a secret MAP; that moves with the env surface in a later phase-0 step.
 */
class EnvironmentSecretsTest extends HohenheimTestBase {

    private static final String TOKEN_V1 = "pw-v1-hunter2-9f8e7d6c5b4a";
    private static final String TOKEN_V2 = "pw-v2-tr0ub4dor-3c2b1a0f9e8d";
    private static final String LEGACY_TOKEN = "legacy-plaintext-pw-0102030405";

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
    void aStoredSecretNeverEntersDerivedSurfacesAndLegacyRestoreCannotRevive() throws Exception {
        SiteModel sites = Models.get(SiteModel.class);

        // 1. A REAL admin creates a REAL site whose settings carry a credential, through
        //    the real form transport.
        var created = postForm("/admin/sites/new",
            "name=Env+Secret+Site&upstream_kind=hohenheim%3Adev_namespace"
            + "&settings.registration_token=" + TOKEN_V1
            + "&description=production-mode");
        assertThat(created.statusCode())
            .as("1. the create submit must be accepted").isIn(200, 302, 303);
        Row site = sites.find().where(SiteModel.NAME.eq("Env Secret Site")).first();
        assertThat(site).as("1. the site must exist").isNotNull();
        int siteId = site.get(SiteModel.ID);
        assertThat(tokenOf(siteId))
            .as("1. the value must be stored AT REST unchanged (redaction is derived-surface only)")
            .isEqualTo(TOKEN_V1);

        // 2. An update rotates the password and flips an ordinary setting.
        var updated = postForm("/admin/sites/" + siteId,
            "name=Env+Secret+Site+Renamed&upstream_kind=hohenheim%3Adev_namespace"
            + "&settings.registration_token=" + TOKEN_V2
            + "&description=staging-mode");
        assertThat(updated.statusCode())
            .as("2. the update submit must be accepted").isIn(200, 302, 303);
        assertThat(tokenOf(siteId))
            .as("2. the rotated value must be stored at rest").isEqualTo(TOKEN_V2);

        // 3. NO revision snapshot of this record may contain either password, and the
        //    snapshot's settings map must OMIT the secret leaf while keeping
        //    ordinary settings keys.
        List<Row> revisions = revisionRows(siteId);
        assertThat(revisions).as("3. both saves must have appended a revision")
            .hasSizeGreaterThanOrEqualTo(2);
        for (Row revision : revisions) {
            String snapshotText = revision.get(RevisionModel.SNAPSHOT);
            assertThat(snapshotText)
                .as("3. revision %s must not contain any credential plaintext",
                    revision.get(RevisionModel.REVISION))
                .doesNotContain(TOKEN_V1)
                .doesNotContain(TOKEN_V2);
        }
        Map<String, Object> latestSnapshot = SiteModel.REVISIONABLE.snapshotOf(
            sites, siteId, revisions.get(revisions.size() - 1).get(RevisionModel.REVISION));
        @SuppressWarnings("unchecked")
        Map<String, Object> snapshotSettings = (Map<String, Object>) latestSnapshot.get("settings");
        assertThat(snapshotSettings)
            .as("3. the snapshot's settings map must omit the secret leaf entirely")
            .doesNotContainKey("registration_token");

        // 4. NO activity delta may contain the credential; the update delta must still
        //    SHOW that the settings changed ([redacted] pair) and keep the ordinary
        //    changes readable.
        List<Row> activity = activityRows(siteId);
        assertThat(activity).as("4. create + update must both be logged (ALL policy)")
            .hasSizeGreaterThanOrEqualTo(2);
        for (Row entry : activity) {
            String deltaText = entry.get(ActivityModel.DELTA);
            if (deltaText == null) {
                continue;
            }
            assertThat(deltaText)
                .as("4. activity delta of action '%s' must not contain any credential plaintext",
                    entry.get(ActivityModel.ACTION))
                .doesNotContain(TOKEN_V1)
                .doesNotContain(TOKEN_V2);
        }
        Row updateEntry = activity.stream()
            .filter(entry -> "update".equals(entry.get(ActivityModel.ACTION)))
            .reduce((first, second) -> second).orElse(null);
        assertThat(updateEntry).as("4. the update activity entry must exist").isNotNull();
        Map<?, ?> delta = (Map<?, ?>) Zenit.DRY.parse(updateEntry.get(ActivityModel.DELTA));
        Map<?, ?> settingsChange = (Map<?, ?>) delta.get("settings");
        assertThat(settingsChange).as("4. the delta must record that settings changed").isNotNull();
        Map<?, ?> settingsAfter = (Map<?, ?>) settingsChange.get("after");
        assertThat(settingsAfter.get("registration_token"))
            .as("4. the delta must collapse the secret to the [redacted] marker, keeping the CHANGE visible")
            .isEqualTo("[redacted]");
        assertThat(((Map<?, ?>) settingsChange.get("before")).get("registration_token"))
            .as("4. ...on the before side too")
            .isEqualTo("[redacted]");
        Map<?, ?> descriptionChange = (Map<?, ?>) delta.get("description");
        assertThat(descriptionChange.get("after"))
            .as("4. ordinary changes stay readable in the same delta")
            .isEqualTo("staging-mode");
        Map<?, ?> nameChange = (Map<?, ?>) delta.get("name");
        assertThat(nameChange.get("after"))
            .as("4. ordinary top-level changes stay readable")
            .isEqualTo("Env Secret Site Renamed");

        // 5. The form stays USABLE: the field renders, the value never echoes, and a
        //    submit that leaves it blank keeps the stored one (keep-on-blank).
        HttpResponse<String> form = getPage("/admin/sites/" + siteId);
        assertThat(form.statusCode()).as("5. the edit form must render").isEqualTo(200);
        assertThat(form.body())
            .as("5. the secret field must stay in the form so it is editable")
            .contains("settings.registration_token");
        assertThat(form.body())
            .as("5. the VALUE must never echo into the form")
            .doesNotContain(TOKEN_V2);
        var blankResubmit = postForm("/admin/sites/" + siteId,
            "name=Env+Secret+Site+Renamed&upstream_kind=hohenheim%3Adev_namespace"
            + "&description=staging-mode"
            + "&settings.registration_token=");
        assertThat(blankResubmit.statusCode())
            .as("5. the blank-value submit must be accepted").isIn(200, 302, 303);
        assertThat(tokenOf(siteId))
            .as("5. a blank-submitted secret keeps the stored value, it does not wipe it")
            .isEqualTo(TOKEN_V2);

        // 6. A LEGACY pre-redaction snapshot (doctored to carry plaintext env, the
        //    shape old installs still have in zenit_revisions) must NOT reactivate
        //    that env on restore: the current values win, the ghost key vanishes.
        doctorRevisionWithLegacySecret(siteId);
        SiteModel.REVISIONABLE.restore(sites, siteId, 1);
        Row restored = sites.findById(siteId);
        assertThat((String) restored.get(SiteModel.NAME))
            .as("6. the restore itself must apply (non-secret fields rewind)")
            .isEqualTo("Env Secret Site");
        assertThat(tokenOf(siteId))
            .as("6. restore must keep the CURRENT secret, never the legacy plaintext")
            .isEqualTo(TOKEN_V2);
        assertThat(settingsOf(siteId))
            .as("6. a key that only exists in the legacy snapshot must not resurrect")
            .doesNotContainKey("ghost_only");

        // 7. The rendered history page (DiffRendering) shows the redaction marker,
        //    never a password -- even though the doctored legacy snapshot is still
        //    stored with plaintext.
        navigateToApp("/admin/sites/" + siteId + "/page/history");
        waitForHydration();
        waitForSelector(".cms-record-history-page");
        String history = page.content();
        assertThat(history)
            .as("7. the history feed must never render the credential")
            .doesNotContain(TOKEN_V1)
            .doesNotContain(TOKEN_V2)
            .doesNotContain(LEGACY_TOKEN);
    }

    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> settingsOf(int siteId) {
        Object settings = Models.get(SiteModel.class).findById(siteId).get(SiteModel.SETTINGS);
        return settings instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String tokenOf(int siteId) {
        Object token = settingsOf(siteId).get("registration_token");
        return token == null ? null : String.valueOf(token);
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

    /** Rewrites revision 1's stored snapshot to the pre-redaction shape: plaintext inside settings. */
    @SuppressWarnings("unchecked")
    private static void doctorRevisionWithLegacySecret(int siteId) {
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
        settings.put("registration_token", LEGACY_TOKEN);
        settings.put("ghost_only", "ghost-value");
        fields.put("settings", settings);

        revision.set(RevisionModel.SNAPSHOT, Zenit.DRY.stringify(snapshot));
        revisions.save(revision);
    }
}
