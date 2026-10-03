package be.elevenways.hohenheim.test;

import be.elevenways.hawkeye.testSupport.HawkeyeBrowserTestBase;
import be.elevenways.hohenheim.HohenheimChannels;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.HohenheimSettingsBoot;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.http.HttpMethod;
import be.elevenways.protoblast.common.http.Uri;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.auth.AuthEndpoints;
import be.elevenways.zenit.auth.AuthSettings;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelEntry;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.RecordTab;
import be.elevenways.zenit.cms.common.resource.Resource;
import be.elevenways.zenit.cms.common.resource.ResourceVerb;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.registry.Registries;
import be.elevenways.zenit.common.routing.Endpoint;
import be.elevenways.zenit.common.routing.EndpointRoute;
import be.elevenways.zenit.common.routing.PageEndpoint;
import be.elevenways.zenit.common.security.ExecutionIdentity;
import be.elevenways.zenit.server.ServerZenitRuntime;
import be.elevenways.zenit.server.http.ZenitHttpServer;
import be.elevenways.zenit.server.setting.ServerSettings;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Crawls the real app's declared surfaces without copying a migrated fixture database or minting test sessions.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
class EmptyDatabaseWalkthroughBrowserTest extends HawkeyeBrowserTestBase {
    private static final String ADMIN_EMAIL = "admin@hohenheim.test";
    private static final String ADMIN_PASSWORD = "correct-horse-battery-staple";
    private static final String AUTH_FORM = "form.auth-form";
    private static final String AUTH_SUBMIT = AUTH_FORM + " button[type='submit']";
    private final List<String> strictConsoleErrors = new CopyOnWriteArrayList<>();
    private final List<String> reds = new ArrayList<>();
    private ZenitHttpServer server;
    private int port;
    private int visits;

    @Override
    protected int startServer() throws Exception {
        // 1. A never-opened database runs the production declarations, migrations, modules and seeders.
        assertThat(ServerZenitRuntime.INSTANCE).as("step 1: start in a fresh JVM without a shared test server")
            .isNull();
        Path database = Files.createTempDirectory("hohenheim-empty-walkthrough").resolve("fresh.db");
        assertThat(database).as("step 1: the database does not exist before real boot").doesNotExist();
        String storage = Files.createTempDirectory("hohenheim-empty-storage").toString();
        ExecutionIdentity.runAsSystem("empty-database-walkthrough-boot", () -> {
            HohenheimSettingsBoot.forceDefinitions();
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Roles.PROXY, true);
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Roles.DNS, true);
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Roles.FIREWALL, true);
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Roles.STACKS, true);
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Roles.DATABASES, true);
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Roles.INSTANCES, true);
            HohenheimSettingsBoot.load();
            Zenit.SETTINGS_VALUES.setValue(ServerSettings.Database.URL, "jdbc:sqlite:" + database);
            Zenit.SETTINGS_VALUES.setValue(ServerSettings.Storage.LOCAL_ROOT, storage);
            Zenit.SETTINGS_VALUES.setValue(ServerSettings.Network.AUTO_START_HTTP, false);
            Zenit.SETTINGS_VALUES.setValue(ServerSettings.Debugging.DEBUG, true);
            HohenheimEndpoints.init();
            HohenheimChannels.init();
            HohenheimAccess.declareGrantableModels();
            HohenheimDatabase.init();
            Zenit.SETTINGS_VALUES.setValue(AuthSettings.CMS_AUTO_PANEL, false);
            ServerMain.installAuthBaselines();
            ServerZenitRuntime.init().join();
            server = ServerZenitRuntime.createServer(0);
            server.start();
            port = server.getPort();
        });
        return port;
    }

    @Override
    protected int getServerPort() {
        return port;
    }

    @Override
    protected void stopServer() {
        if (server != null) server.stop();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Override
    protected void configurePage(Page on) {
        on.onConsoleMessage(message -> {
            // The location names the failing resource of a "Failed to load resource" error, so a red says which.
            if ("error".equals(message.type())) strictConsoleErrors.add(message.text() + " @ " + message.location());
        });
        on.addInitScript("""
            (() => {
                window.__emptyWalkthroughRaw = [];
                const scan = () => {
                    document.querySelectorAll('%s[data-unresolved]').forEach(el => {
                        const key = el.getAttribute('key');
                        if (key && el.textContent.includes(key)) {
                            const miss = key + '=' + el.textContent;
                            if (!window.__emptyWalkthroughRaw.includes(miss)) window.__emptyWalkthroughRaw.push(miss);
                        }
                    });
                };
                new MutationObserver(scan).observe(document, {
                    subtree: true, childList: true, characterData: true, attributes: true
                });
            })();
            """.formatted(Microcopy.WRAPPER_TAG));
    }

    @Test
    void freshBootAdminCanWalkEveryDeclaredSurface() {
        // 2. Create only the real first-run administrator through the production setup form.
        visit(AuthEndpoints.GET_SETUP.toUrl(), null, "zenit-auth / first-run setup");
        page.fill(AUTH_FORM + " input[name='email']", ADMIN_EMAIL);
        page.fill(AUTH_FORM + " input[name='display_name']", "Hohenheim Administrator");
        page.fill(AUTH_FORM + " input[name='password']", ADMIN_PASSWORD);
        page.click(AUTH_SUBMIT);
        page.waitForURL(location -> !URI.create(location).getPath().equals(AuthEndpoints.GET_SETUP.toUrl()));
        assertThat(URI.create(page.url()).getPath()).as("step 2: the real setup gate accepted the administrator")
            .isNotEqualTo(AuthEndpoints.GET_SETUP.toUrl());

        // 3. Sign in through the real password form rather than injecting a synthetic session cookie.
        page.context().clearCookies();
        visit(AuthEndpoints.GET_LOGIN.toUrl(), null, "zenit-auth / password login");
        page.fill(AUTH_FORM + " input[name='email']", ADMIN_EMAIL);
        page.fill(AUTH_FORM + " input[name='password']", ADMIN_PASSWORD);
        page.click(AUTH_SUBMIT);
        page.waitForURL(location -> !URI.create(location).getPath().equals(AuthEndpoints.GET_LOGIN.toUrl()));
        assertThat(URI.create(page.url()).getPath()).as("step 3: password login established the admin session")
            .isNotEqualTo(AuthEndpoints.GET_LOGIN.toUrl());

        // 4. The registry, including contributed and non-navigation entries, supplies the complete page set.
        List<Panel> panels = PanelRegistry.all().stream().sorted(Comparator.comparing(Panel::slug)).toList();
        assertThat(panels).as("step 4: production boot registered panels to crawl").isNotEmpty();
        for (Panel panel : panels) {
            for (PanelEntry entry : panel.entries()) {
                String owner = entry.id() + " / " + entry.getClass().getName();
                String list = CmsRoutes.list(panel.slug(), entry.slug()).toUrl();
                try {
                    boolean hasList = !(entry instanceof PanelResource<?> parts) || parts.list() != null;
                    boolean creates = entry instanceof PanelResource<?> parts
                        ? parts.form() != null && parts.offers(ResourceVerb.CREATE)
                        : entry instanceof Resource<?> resource && resource.creatable();

                    // 5. Visit every declared list, page, singleton and settings page, even when the list is empty.
                    String landing = entry.landingTarget() == null ? null : entry.landingTarget().toUrl();
                    if (!hasList && landing != null) visit(landing, null, owner);
                    if (hasList && visit(list, landing, owner)) {
                        // 6. Open a seeded overview through rendered links or keys, never invented fixture ids.
                        String record = (String) page.locator(
                            ".cms-row-link, cms-inline-cell[href], pl-media-card-title a[data-row-link]").evaluateAll(
                                "els => els.map(el => el.getAttribute('href')).find(href => !!href) || null");
                        if (record == null && page.locator("[data-row-key]").count() > 0) {
                            String key = page.locator("[data-row-key]").first().getAttribute("data-row-key");
                            record = CmsRoutes.detail(panel.slug(), entry.slug(), key).toUrl();
                        }
                        if (record != null) {
                            // A record opens on itself or one of its own declared tabs (its front door or read view),
                            // never elsewhere; a row link may already name the tab.
                            visitLanding(record, recordLandings(panel, entry, record),
                                owner + " / seeded record overview");
                        } else {
                            System.out.println("WALKTHROUGH_EMPTY page=" + list + " owner=" + owner);
                        }
                    }

                    // 7. Only resources declaring a create writer get a create-form visit; no form is submitted.
                    if (creates) visit(CmsRoutes.create(panel.slug(), entry.slug()).toUrl(), null, owner + " / create");
                } catch (RuntimeException | AssertionError failure) {
                    String red = "page=" + list + " | likely owner=" + owner + "\n" + failure.getMessage();
                    reds.add(red);
                    System.out.println("WALKTHROUGH_RED " + red);
                }
            }
        }

        // 8. Account GET pages come from the endpoint registry; parameterized confirmations come from rendered links.
        List<EndpointRoute> accountRoutes = new ArrayList<>();
        Set<String> accountPages = new LinkedHashSet<>();
        String root = AuthEndpoints.GET_ACCOUNT.toUrl();
        for (Endpoint<?> endpoint : Registries.ENDPOINTS) {
            if (!(endpoint instanceof PageEndpoint)) continue;
            for (EndpointRoute route : endpoint.getRoutes()) {
                String pattern = route.toPattern();
                if (route.getMethod() == HttpMethod.GET && (pattern.equals(root) || pattern.startsWith(root + "/"))) {
                    accountRoutes.add(route);
                    if (route.getParameters().isEmpty()) accountPages.add(route.toUrl());
                }
            }
        }
        assertThat(accountPages).as("step 8: the real account pages are registered").contains(root);
        Set<String> checked = new LinkedHashSet<>();
        while (checked.size() < accountPages.size()) {
            String next = accountPages.stream().filter(path -> !checked.contains(path)).findFirst().orElseThrow();
            checked.add(next);
            if (!visit(next, null, "zenit-auth / account page")) continue;
            @SuppressWarnings("unchecked")
            List<String> links = (List<String>) page.locator("a[href]").evaluateAll("els => els.map(el => el.href)");
            for (String link : links) {
                URI address = URI.create(link);
                if (!URI.create(url("/")).getAuthority().equals(address.getAuthority())) continue;
                for (EndpointRoute route : accountRoutes) {
                    if (route.getParameterMatches(new Uri(link)) != null) {
                        accountPages.add(address.getRawPath()
                            + (address.getRawQuery() == null ? "" : "?" + address.getRawQuery()));
                        break;
                    }
                }
            }
        }

        // 9. Finish the complete crawl before failing, so one run reports every affected page to its owner.
        assertThat(visits).as("step 9: the crawl made actual HTTP page visits").isGreaterThan(panels.size());
        assertThat(reds).as("step 9: empty-database walkthrough reds:\n%s", String.join("\n\n", reds)).isEmpty();
    }

    private boolean visit(String path, String declaredRedirect, String owner) {
        return visitLanding(path, List.of(declaredRedirect == null ? path : declaredRedirect), owner);
    }

    /** The record's own path and each of its declared tabs, the only places a record visit may land. */
    private static List<String> recordLandings(Panel panel, PanelEntry entry, String record) {
        String path = URI.create(record).getPath();
        int tabAt = path.indexOf("/page/");
        String base = tabAt >= 0 ? path.substring(0, tabAt) : path;
        String key = base.substring(base.lastIndexOf('/') + 1);
        List<String> landings = new ArrayList<>(List.of(path, base));
        if (entry instanceof PanelResource<?> parts) {
            for (RecordTab<?> tab : parts.tabs().declared()) {
                landings.add(CmsRoutes.subpage(panel.slug(), entry.slug(), key, tab.slug()).toUrl());
            }
        }
        return landings;
    }

    private boolean visitLanding(String path, List<String> landings, String owner) {
        String step = "page " + ++visits + " " + path + " owner=" + owner;
        getCollectedErrors().clear();
        strictConsoleErrors.clear();
        Response response = null;
        try {
            response = page.navigate(URI.create(url("/")).resolve(path).toString());
            SoftAssertions checks = new SoftAssertions();
            // 5.1. A login card after an unexpected redirect must not masquerade as a successful admin page.
            checks.assertThat(response).as("step 5.1: HTTP response for " + step).isNotNull();
            if (response != null) {
                checks.assertThat(response.status()).as("step 5.1: HTTP 200 for %s; error body: %s", step,
                    response.status() == 200 ? "" : page.locator("body").innerText()).isEqualTo(200);
            }
            List<String> expected = landings.stream()
                .map(landing -> URI.create(url("/")).resolve(landing).getPath()).toList();
            checks.assertThat(URI.create(page.url()).getPath()).as("step 5.1: only the declared landing for " + step)
                .isIn(expected);
            if (Boolean.TRUE.equals(page.evaluate("() => typeof window.__hawkeye_reactive_idle === 'boolean'"))) {
                try {
                    waitForReactiveIdle();
                } catch (RuntimeException | AssertionError failure) {
                    checks.fail("step 5.1: hydration did not settle for " + step + ": " + failure.getMessage());
                }
            }
            // 5.2. Containment can return HTTP 200 while replacing a broken region with an error badge.
            checks.assertThat(page.locator("pl-render-error").evaluateAll(
                "els => els.map(el => el.outerHTML)")).as("step 5.2: no render-error badge for " + step)
                .isEqualTo(List.of());
            // 5.3. Keep network console errors too; the usual browser base intentionally filters some of them.
            checks.assertThat(strictConsoleErrors).as("step 5.3: no console errors for " + step).isEmpty();
            checks.assertThat(getCollectedErrors()).as("step 5.3: no page/framework errors for " + step).isEmpty();
            // 5.4. Observe raw keys throughout render and hydration, including a flash that later heals.
            checks.assertThat(page.evaluate("() => window.__emptyWalkthroughRaw"))
                .as("step 5.4: no raw microcopy keys for " + step).isEqualTo(List.of());
            checks.assertAll();
            System.out.println("WALKTHROUGH_OK " + step);
        } catch (RuntimeException | AssertionError failure) {
            String red = "page=" + path + " | likely owner=" + owner + "\n" + failure.getMessage();
            reds.add(red);
            System.out.println("WALKTHROUGH_RED " + red);
        }
        return response != null && response.status() == 200;
    }
}
