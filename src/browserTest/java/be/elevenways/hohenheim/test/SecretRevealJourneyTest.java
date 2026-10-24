package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.StoredSecret;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.instance.ApplicationKind;
import be.elevenways.hohenheim.source.GitSourceSchema;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.cms.common.ZenitCmsActivityAction;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Response;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A stored secret is never in a page: the database password and the git webhook secret reach a reader only through
 * their reveal, an explicit press that fetches the value once, answers it uncached, records an activity entry naming
 * the field and never the value, refuses a reader the secret's gate refuses, feeds the copy button, and is rate limited.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class SecretRevealJourneyTest extends HohenheimTestBase {

    private static final String PREFIX = "revealjourney-";
    /** Characters URL encoding rewrites, so an encoded leak is a different string to look for. */
    private static final String PASSWORD = "Rv1+pw/reveal&key=Zq9x";
    private static final String WEBHOOK_SECRET = "whsec-reveal+journey/7Kd2";

    @Override
    protected void configureContext(BrowserContext context) {
        context.grantPermissions(List.of("clipboard-read", "clipboard-write"));
    }

    @Test
    void aStoredSecretIsRevealedOnlyOnAPressThatIsGatedRecordedUncachedAndRateLimited() throws Exception {
        Model databases = Models.get(DatabaseModel.class);
        Model instances = Models.get(InstanceModel.class);
        Row database = databases.createEmptyRow();
        database.set(DatabaseModel.NAME, PREFIX + "db");
        database.set(DatabaseModel.ENGINE, "postgres");
        database.set(DatabaseModel.DB_USER, "revealuser");
        database.set(DatabaseModel.DB_PASSWORD, PASSWORD);
        database.set(DatabaseModel.DB_NAME, "revealdb");
        database.set(DatabaseModel.STATUS, DatabaseModel.STATUS_ACTIVE);
        databases.save(database);
        int databaseId = database.get(DatabaseModel.ID);
        Map<String, Object> source = new LinkedHashMap<>();
        source.put(GitSourceSchema.REPOSITORY_URL, "https://git.example.test/acme/reveal.git");
        source.put(GitSourceSchema.WEBHOOK_SECRET, WEBHOOK_SECRET);
        Row app = instances.createEmptyRow();
        app.set(InstanceModel.NAME, PREFIX + "app");
        app.set(InstanceModel.KIND, ApplicationKind.ID.toString());
        app.set(InstanceModel.SETTINGS, source);
        app.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
        instances.save(app);
        int appId = app.get(InstanceModel.ID);
        try {
            UUID credentialed = ApiSupport.user(PREFIX + "credentials-" + UUID.randomUUID() + "@hohenheim.local",
                "Reveal Credentials Tenant");
            UUID viewer = ApiSupport.user(PREFIX + "viewer-" + UUID.randomUUID() + "@hohenheim.local",
                "Reveal View Tenant");
            for (UUID tenant : List.of(credentialed, viewer)) {
                RecordGrants.grant(GrantSubjectType.USER, tenant, DatabaseModel.MODEL_ID, databaseId,
                    HohenheimCapabilities.VIEW, true);
            }
            RecordGrants.grant(GrantSubjectType.USER, credentialed, DatabaseModel.MODEL_ID, databaseId,
                HohenheimCapabilities.CREDENTIALS, true);
            TestSession tenant = sessionFor(credentialed);
            TestSession viewOnly = sessionFor(viewer);

            // 1. Both readers load every page that used to carry the password, and the operator the Deploys tab that
            //    carried the webhook secret: each renders the reveal control, and no page source (its hydration state
            //    included) holds a secret, raw or URL-encoded.
            String admin = "/admin/" + HohenheimSlugs.DATABASES + "/" + databaseId;
            Map<String, HttpResponse<String>> pages = new LinkedHashMap<>();
            pages.put("operator overview", adminGet(admin + "/page/overview"));
            pages.put("operator restore", adminGet(admin + "/page/" + HohenheimSlugs.Tab.RESTORE));
            pages.put("operator list preview", adminGet("/admin/" + HohenheimSlugs.DATABASES + "?_preview="
                + databaseId));
            pages.put("tenant credentials", httpGet("/manage/" + HohenheimSlugs.DATABASES + "/" + databaseId
                + "/page/" + HohenheimSlugs.Tab.CREDENTIALS, tenant.token()));
            pages.put("operator deploys", adminGet("/admin/" + HohenheimSlugs.INSTANCES + "/" + appId
                + "/page/" + HohenheimSlugs.Tab.DEPLOYMENTS));
            for (Map.Entry<String, HttpResponse<String>> page : pages.entrySet()) {
                assertThat(page.getValue().statusCode()).as("step 1: the %s renders", page.getKey()).isEqualTo(200);
                assertThat(page.getValue().body()).as("step 1: the %s carries no secret", page.getKey())
                    .doesNotContain(PASSWORD).doesNotContain(encoded(PASSWORD))
                    .doesNotContain(WEBHOOK_SECRET).doesNotContain(encoded(WEBHOOK_SECRET));
            }
            for (String drawn : List.of("operator restore", "tenant credentials", "operator deploys")) {
                assertThat(pages.get(drawn).body()).as("step 1: the %s draws the reveal", drawn)
                    .contains("<hh-secret-reveal");
            }

            // 2. The operator presses Show value on the Restore tab: ONE fetch, answered no-store, shows the password
            //    and records one reveal naming the field, never the value.
            List<String> fetches = new ArrayList<>();
            page.onRequest(request -> {
                if (request.url().contains("/secrets/")) {
                    fetches.add(request.method() + " " + request.url());
                }
            });
            navigateToApp(admin + "/page/" + HohenheimSlugs.Tab.RESTORE);
            waitForHydration();
            assertThat(page.content()).as("step 2: the hydrated page holds no password").doesNotContain(PASSWORD);
            assertThat(fetches).as("step 2: nothing is fetched before the press").isEmpty();
            Response answer = page.waitForResponse(response -> response.url().contains("/secrets/"),
                () -> page.locator("[data-hh-database-password] [data-hh-secret-reveal]").click());
            assertThat(answer.status()).as("step 2: the reveal answered").isEqualTo(200);
            assertThat(answer.headers().get("cache-control")).as("step 2: and no cache keeps it").contains("no-store");
            page.locator("[data-hh-secret-value]").waitFor();
            assertThat(page.locator("[data-hh-secret-value]").textContent()).as("step 2: the press shows the password")
                .isEqualTo(PASSWORD);
            assertThat(fetches).as("step 2: one press, one fetch").hasSize(1);
            int operatorReveals = 1;
            List<RecordedChanges.Recorded> reveals = RecordedChanges.withFact(databases, databaseId,
                ZenitCmsActivityAction.SECRET_REVEALED);
            assertThat(reveals).as("step 2: the press is one recorded reveal").hasSize(1);
            assertThat(reveals.get(0).fact(ZenitCmsActivityAction.SECRET_REVEALED))
                .as("step 2: naming the field").isEqualTo(Map.of("field", StoredSecret.DATABASE_PASSWORD.field()));
            assertThat(reveals.get(0).facts() + " " + reveals.get(0).delta() + " " + reveals.get(0).change())
                .as("step 2: and never the value").doesNotContain(PASSWORD).doesNotContain(encoded(PASSWORD));

            // 3. A tenant without the credentials capability is refused, exactly as for a record that does not exist,
            //    and nothing is recorded as revealed; the tenant holding it reads it, recorded under their name.
            HttpResponse<String> refused = revealSecret(StoredSecret.DATABASE_PASSWORD, databaseId, viewOnly.token(),
                viewOnly.csrf());
            assertThat(refused.statusCode()).as("step 3: the view-only tenant is refused").isEqualTo(404);
            assertThat(refused.body()).as("step 3: and the refusal carries no password").doesNotContain(PASSWORD);
            assertThat(revealSecret(StoredSecret.DATABASE_PASSWORD, Integer.MAX_VALUE, viewOnly.token(),
                viewOnly.csrf()).statusCode()).as("step 3: the same answer as a missing record").isEqualTo(404);
            assertThat(revealSecret(StoredSecret.WEBHOOK_SECRET, appId, tenant.token(), tenant.csrf()).statusCode())
                .as("step 3: the webhook secret is the operator's alone").isEqualTo(404);
            assertThat(RecordedChanges.withFact(databases, databaseId, ZenitCmsActivityAction.SECRET_REVEALED))
                .as("step 3: no refusal is recorded as a reveal").hasSize(1);
            assertThat(revealedValue(revealSecret(StoredSecret.DATABASE_PASSWORD, databaseId, tenant.token(),
                tenant.csrf()))).as("step 3: the credentials tenant reads it").isEqualTo(PASSWORD);
            reveals = RecordedChanges.withFact(databases, databaseId, ZenitCmsActivityAction.SECRET_REVEALED);
            assertThat(reveals).as("step 3: their reveal is recorded too").hasSize(2);
            assertThat(reveals.get(1).actor()).as("step 3: under their name").isEqualTo(credentialed.toString());

            // 4. The copy button copies what the press fetched, and copying fetches nothing more.
            page.locator("[data-hh-secret-copy] button").click();
            page.waitForFunction("() => document.querySelector('[data-hh-secret-copy]')"
                + ".getAttribute('data-outcome') === 'copied'");
            assertThat(page.evaluate("() => navigator.clipboard.readText()")).as("step 4: the clipboard holds it")
                .isEqualTo(PASSWORD);
            assertThat(fetches).as("step 4: still the one fetch").hasSize(1);

            // The webhook secret takes the same lane: revealed for the operator, recorded on the app.
            assertThat(revealedValue(revealSecret(StoredSecret.WEBHOOK_SECRET, appId, sessionToken, csrfToken)))
                .as("step 4: the operator reveals the webhook secret").isEqualTo(WEBHOOK_SECRET);
            operatorReveals++;
            assertThat(RecordedChanges.withFact(instances, appId, ZenitCmsActivityAction.SECRET_REVEALED))
                .as("step 4: recorded on the app").hasSize(1);

            // 5. The budget: an operator walking reveals is cut off at the tenth in a minute, and a refused attempt
            //    records nothing and answers nothing.
            int limit = 10;
            for (int press = operatorReveals; press < limit; press++) {
                assertThat(revealSecret(StoredSecret.DATABASE_PASSWORD, databaseId, sessionToken, csrfToken)
                    .statusCode()).as("step 5: reveal %d of %d answers", press + 1, limit).isEqualTo(200);
            }
            int recorded = RecordedChanges.withFact(databases, databaseId, ZenitCmsActivityAction.SECRET_REVEALED)
                .size();
            HttpResponse<String> limited = revealSecret(StoredSecret.DATABASE_PASSWORD, databaseId, sessionToken,
                csrfToken);
            assertThat(limited.statusCode()).as("step 5: the next one is refused").isEqualTo(429);
            assertThat(limited.body()).as("step 5: without the password").doesNotContain(PASSWORD);
            assertThat(RecordedChanges.withFact(databases, databaseId, ZenitCmsActivityAction.SECRET_REVEALED))
                .as("step 5: and nothing recorded").hasSize(recorded);
            assertThat(revealedValue(revealSecret(StoredSecret.DATABASE_PASSWORD, databaseId, tenant.token(),
                tenant.csrf()))).as("step 5: the budget is per caller").isEqualTo(PASSWORD);
        } finally {
            HardDeletes.byId(instances, appId);
            HardDeletes.byId(databases, databaseId);
        }
    }

    private static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
