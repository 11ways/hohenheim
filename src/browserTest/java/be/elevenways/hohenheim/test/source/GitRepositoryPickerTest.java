package be.elevenways.hohenheim.test.source;

import be.elevenways.hohenheim.model.GitProviderModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.auth.AuthKeys;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.ZenitAuth;
import be.elevenways.zenit.common.data.DataPage;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.session.Session;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.security.csrf.CsrfTokens;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The repository/branch picker surface: the admin-gated browse endpoints (refusal
 * identities, no third-party call on refusal), the reactive sibling-driven pickers
 * in the site form (provider -> repositories -> branches over a FAKE provider API),
 * and submit coercion into the stored source settings.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GitRepositoryPickerTest extends HohenheimTestBase {

    private static HttpServer fake;
    private static String fakeBase;
    private static Integer providerId;
    private static String tenantSession;
    private static volatile String lastFakeAuth;
    private static final AtomicInteger FAKE_HITS = new AtomicInteger();

    @BeforeAll
    static void seedProviderAndFakeApi() throws Exception {
        fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/", exchange -> {
            FAKE_HITS.incrementAndGet();
            lastFakeAuth = exchange.getRequestHeaders().getFirst("Authorization");
            String path = exchange.getRequestURI().getRawPath();
            String body = switch (path) {
                case "/api/v3/user/repos" -> "[{\"full_name\":\"acme/app\","
                    + "\"clone_url\":\"x\",\"default_branch\":\"main\"},"
                    + "{\"full_name\":\"acme/site\",\"clone_url\":\"x\","
                    + "\"default_branch\":\"main\"}]";
                case "/api/v3/repos/acme/app/branches" ->
                    "[{\"name\":\"main\"},{\"name\":\"dev\"}]";
                default -> null;
            };
            byte[] bytes = (body == null ? "{}" : body).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(body == null ? 404 : 200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
            exchange.close();
        });
        fake.start();
        fakeBase = "http://127.0.0.1:" + fake.getAddress().getPort();

        var model = Models.get(GitProviderModel.class);
        Row provider = model.createEmptyRow();
        provider.set(GitProviderModel.NAME, "Picker Provider");
        provider.set(GitProviderModel.KIND, GitProviderModel.KIND_GITHUB);
        provider.set(GitProviderModel.BASE_URL, fakeBase);
        provider.set(GitProviderModel.ACCESS_TOKEN, "picker-pat");
        model.save(provider);
        providerId = provider.get(GitProviderModel.ID);

        tenantSession = seedTenantSession();
    }

    @AfterAll
    static void stopFakeApi() {
        if (fake != null) {
            fake.stop(0);
        }
    }

    /** A logged-in user WITHOUT any grant: authenticated, not authorized. */
    private static String seedTenantSession() {
        Row user = AuthModels.users().createEmptyRow();
        user.set(UserModel.EMAIL, "picker-tenant@hohenheim.local");
        user.set(UserModel.DISPLAY_NAME, "Picker Tenant");
        user.set(UserModel.ENABLED, true);
        user.set(UserModel.CREATED_AT, Instant.now());
        user.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(user);
        Session session = Zenit.getSessionStore().create();
        session.set(AuthKeys.USER_ID, ((Integer) user.get(UserModel.ID)).longValue());
        session.set(CsrfTokens.TOKEN, ZenitAuth.randomToken());
        Zenit.getSessionStore().save(session);
        return session.token().secret();
    }

    private HttpResponse<String> get(String path, String session) throws Exception {
        return get(path, session, null);
    }

    private HttpResponse<String> get(String path, String session, String accept) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        HttpRequest.Builder request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + getServerPort() + path))
            .GET();
        if (session != null) {
            request.header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + session);
        }
        if (accept != null) {
            request.header("Accept", accept);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @Order(1)
    void browseEndpointsRefuseByPrincipalAndNeverTouchTheProviderOnRefusal() throws Exception {
        String path = "/admin/git-providers/" + providerId + "/repositories";

        // 1. Anonymous: bounced to login (the browser-shaped 302 of the auth
        //    baseline), and the third-party API was never called.
        int hitsBefore = FAKE_HITS.get();
        HttpResponse<String> anonymous = get(path, null);
        assertThat(anonymous.statusCode()).as("step 1: anonymous is redirected").isEqualTo(302);
        assertThat(anonymous.headers().firstValue("Location").orElse(""))
            .as("step 1: the redirect goes to the login flow").contains("login");
        assertThat(FAKE_HITS.get())
            .as("step 1: no provider API call happened for an anonymous request")
            .isEqualTo(hitsBefore);

        // 2. Authenticated but ungranted: the auth baseline's 403 forbidden page
        //    (its template identity distinguishes it from a CSRF 403 or a 404),
        //    and the provider API is STILL untouched -- a tenant cannot enumerate
        //    another admin's repositories, not even as a side effect.
        //    AIDEV-NOTE: gating is LAYERED here -- the /admin path baseline refuses
        //    before the endpoint's own requiresPermission, so mutating only the
        //    endpoint gate does not flip this step; both must drop for enumeration.
        HttpResponse<String> tenant = get(path, tenantSession, "application/json");
        assertThat(tenant.statusCode()).as("step 2: ungranted principal is 403").isEqualTo(403);
        assertThat(tenant.body()).as("step 2: the refusal is the authorization page, not CSRF")
            .contains("auth/forbidden");
        assertThat(FAKE_HITS.get())
            .as("step 2: the refusal happened before any third-party call")
            .isEqualTo(hitsBefore);

        // 3. Positive anchor: the admin session lists the repositories THROUGH the
        //    fake API, authenticated with the stored token.
        HttpResponse<String> admin = get(path, sessionToken);
        assertThat(admin.statusCode()).as("step 3: admin is served").isEqualTo(200);
        assertThat(admin.body()).as("step 3: the page carries the provider's repositories")
            .contains("acme/app").contains("acme/site");
        assertThat(FAKE_HITS.get()).as("step 3: the fake API answered").isGreaterThan(hitsBefore);
        assertThat(lastFakeAuth).as("step 3: the stored token authenticated the upstream call")
            .isEqualTo("Bearer picker-pat");

        HttpResponse<String> noMatches = get(path + "?text=absent", sessionToken);
        DataPage emptyPage = (DataPage) Zenit.DRY.parse(noMatches.body());
        assertThat(emptyPage.items()).as("step 3: unmatched provider searches are empty").isEmpty();
        assertThat(emptyPage.pageCount())
            .as("step 3: an empty provider result must not claim a page exists")
            .isZero();

        // 4. Branch listing rides the same gate and requires a repository.
        HttpResponse<String> noRepo = get("/admin/git-providers/" + providerId + "/branches",
            sessionToken);
        assertThat(noRepo.statusCode()).as("step 4: repository-less branch listing is a 400")
            .isEqualTo(400);
        HttpResponse<String> branches = get("/admin/git-providers/" + providerId
            + "/branches?repository=acme%2Fapp", sessionToken);
        assertThat(branches.statusCode()).isEqualTo(200);
        assertThat(branches.body()).as("step 4: branches listed for the repository")
            .contains("main").contains("dev");
    }

    // -- the reactive picker journey ------------------------------------------

    private String selectField(String name) {
        return "pl-select[name='" + name + "'] .pl-select-field";
    }

    /**
     * Open one pl-select and click an option by text. Options portal under
     * he-bottom (the overlay substrate); only one popup is open at a time, so
     * the option query needs no per-select scoping.
     */
    private void pickOption(String selectName, String optionText) {
        page.click(selectField(selectName));
        // Closed overlays stay mounted: only the OPEN popup's options count.
        page.waitForSelector("he-bottom [data-open] div[role='option']:has-text('" + optionText + "')")
            .click();
    }

    @Test
    @Order(2)
    void repositoryAndBranchPickersFollowTheirSiblingsReactively() {
        navigateToApp("/admin/sites/new");
        waitForHydration();

        // 1. Switching the source to git renders the git sub-form reactively.
        pickOption("source", "Git repository");
        page.waitForSelector("pl-select[name='source_settings.provider_id']");

        // 2. Before a provider is chosen there is NOTHING to list: repository and
        //    branch pickers render disabled.
        assertThat(page.locator("pl-select[name='source_settings.repository'][disabled]").count())
            .as("step 2: the repository picker is disabled until a provider is chosen")
            .isEqualTo(1);
        assertThat(page.locator("pl-select[name='source_settings.branch'][disabled]").count())
            .as("step 2: the branch picker is disabled until repository resolves")
            .isEqualTo(1);

        // 3. Choosing the provider enables the repository picker; opening it lists
        //    the FAKE provider's repositories through the admin endpoint.
        pickOption("source_settings.provider_id", "Picker Provider");
        page.waitForSelector("pl-select[name='source_settings.repository']:not([disabled])");
        pickOption("source_settings.repository", "acme/app");
        assertThat(page.locator(selectField("source_settings.repository")).textContent())
            .as("step 3: the picked repository is displayed").contains("acme/app");

        // 4. The branch picker follows provider AND repository; its options are the
        //    repository's real branches.
        page.waitForSelector("pl-select[name='source_settings.branch']:not([disabled])");
        pickOption("source_settings.branch", "dev");
        assertThat(page.locator(selectField("source_settings.branch")).textContent())
            .as("step 4: the picked branch is displayed").contains("dev");
    }

    // -- submit coercion -------------------------------------------------------

    @Test
    @Order(3)
    void submittedPickerValuesCoerceIntoTheStoredSourceSettings() throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        String body = "name=Picker+Coerce+Site&site_type=hohenheim%3Adead&enabled=true"
            + "&source=git"
            + "&source_settings.provider_id=" + providerId
            + "&source_settings.repository=+acme%2Fapp+"
            + "&source_settings.branch=dev";
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + getServerPort() + "/admin/sites/new"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionToken)
            .header("X-Csrf-Token", csrfToken)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isIn(200, 302, 303);

        Row site = Models.get(SiteModel.class).find()
            .where(SiteModel.NAME.eq("Picker Coerce Site"))
            .first();
        assertThat(site).as("the site was created").isNotNull();
        Object settings = site.get(SiteModel.SOURCE_SETTINGS);
        assertThat(settings).isInstanceOf(Map.class);
        Map<?, ?> stored = (Map<?, ?>) settings;
        assertThat(stored.get("provider_id"))
            .as("provider_id coerces to the integer id").isEqualTo(providerId);
        assertThat(stored.get("repository"))
            .as("the repository value is trimmed by the picker's coercion")
            .isEqualTo("acme/app");
        assertThat(stored.get("branch")).isEqualTo("dev");

        // Cleanup: keep the shared-server world tidy for later classes.
        Models.get(SiteModel.class).delete(site.get(SiteModel.ID));
    }
}
