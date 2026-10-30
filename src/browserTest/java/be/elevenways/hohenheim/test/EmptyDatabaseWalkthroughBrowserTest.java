package be.elevenways.hohenheim.test;

import be.elevenways.hawkeye.testSupport.HawkeyeBrowserTestBase;
import be.elevenways.hohenheim.HohenheimChannels;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.HohenheimSettingsBoot;
import be.elevenways.hohenheim.server.HohenheimSystemPurposes;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.http.HttpMethod;
import be.elevenways.protoblast.common.http.Uri;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.auth.AuthEndpoints;
import be.elevenways.zenit.auth.AuthSettings;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelCluster;
import be.elevenways.zenit.cms.common.panel.PanelEntry;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.common.render.panel.PanelNavStates;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.RecordTab;
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
import be.elevenways.zenit.test.support.TestSqliteFiles;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import org.assertj.core.api.SoftAssertions;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Crawls the real app's declared surfaces without copying a migrated fixture database or minting test sessions.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
// Solo: ServerZenitRuntime is process-global and cannot be reset; this journey proves the first production boot.
@Tag("solo")
class EmptyDatabaseWalkthroughBrowserTest extends HawkeyeBrowserTestBase {
    private static final String ADMIN_EMAIL = "admin@hohenheim.test";
    private static final String ADMIN_PASSWORD = "correct-horse-battery-staple";
    private static final String AUTH_FORM = "form.auth-form";
    private static final String AUTH_SUBMIT = AUTH_FORM + " button[type='submit']";

    /**
     * How many pages of the one signed-in context load documents at once.
     *
     * AIDEV-NOTE: the crawl is ~180 full page loads, and one load at a time left the server and the browser each
     * waiting on the other (~0.6s a page on Dalaran). Each tab still makes a full browser-initiated navigation and
     * is judged alone, by its own console and error lists; only the waiting overlaps. Each tab is a renderer
     * executing the whole bundle (~0.8 GB): 4 tabs took the solo fork from 2.5 to 5 GB and starved the fork plan
     * on darkshire (i2zy), so 2.
     */
    private static final int IN_FLIGHT = 2;

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
        ExecutionIdentity.runAsSystem(HohenheimSystemPurposes.BOOT, () -> {
            HohenheimSettingsBoot.forceDefinitions();
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Roles.PROXY, true);
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Roles.DNS, true);
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Roles.FIREWALL, true);
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Roles.STACKS, true);
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Roles.DATABASES, true);
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Roles.INSTANCES, true);
            HohenheimSettingsBoot.load();
            // Production's datasource over the new file, committing without a disk flush (TestSqliteFiles): the
            // boot's every migration and seed, and each visit's session write, would otherwise wait on the disk.
            Zenit.SETTINGS_VALUES.setValue(ServerSettings.Database.URL, TestSqliteFiles.url(database));
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
        List<Tab> tabs = new ArrayList<>();
        tabs.add(new Tab(page));
        Tab first = tabs.getFirst();

        // 2. Create only the real first-run administrator through the production setup form.
        visitNow(first, AuthEndpoints.GET_SETUP.toUrl(), "zenit-auth / first-run setup");
        page.fill(AUTH_FORM + " input[name='email']", ADMIN_EMAIL);
        page.fill(AUTH_FORM + " input[name='display_name']", "Hohenheim Administrator");
        page.fill(AUTH_FORM + " input[name='password']", ADMIN_PASSWORD);
        page.click(AUTH_SUBMIT);
        page.waitForURL(location -> !URI.create(location).getPath().equals(AuthEndpoints.GET_SETUP.toUrl()));
        assertThat(URI.create(page.url()).getPath()).as("step 2: the real setup gate accepted the administrator")
            .isNotEqualTo(AuthEndpoints.GET_SETUP.toUrl());

        // 3. Sign in through the real password form rather than injecting a synthetic session cookie.
        page.context().clearCookies();
        visitNow(first, AuthEndpoints.GET_LOGIN.toUrl(), "zenit-auth / password login");
        page.fill(AUTH_FORM + " input[name='email']", ADMIN_EMAIL);
        page.fill(AUTH_FORM + " input[name='password']", ADMIN_PASSWORD);
        page.click(AUTH_SUBMIT);
        page.waitForURL(location -> !URI.create(location).getPath().equals(AuthEndpoints.GET_LOGIN.toUrl()));
        assertThat(URI.create(page.url()).getPath()).as("step 3: password login established the admin session")
            .isNotEqualTo(AuthEndpoints.GET_LOGIN.toUrl());

        // 3a. The other tabs open in the same context, so every one of them carries that one real session.
        while (tabs.size() < IN_FLIGHT) {
            Page opened = context.newPage();
            configurePage(opened);
            tabs.add(new Tab(opened));
        }

        // 4. The registry, including contributed and non-navigation entries, supplies the complete page set.
        List<Panel> panels = PanelRegistry.all().stream().sorted(Comparator.comparing(Panel::slug)).toList();
        assertThat(panels).as("step 4: production boot registered panels to crawl").isNotEmpty();
        Deque<Visit> work = new ArrayDeque<>();
        for (Panel panel : panels) {
            for (PanelEntry entry : panel.entries()) {
                String owner = entry.id() + " / " + entry.getClass().getName();
                String list = CmsRoutes.list(panel.slug(), entry.slug()).toUrl();
                try {
                    if (entry instanceof PanelCluster cluster) {
                        // 5a. A cluster opens the first member this viewer may open, a choice made per viewer and so
                        //     never a static landing target: it lands on one of its members. The members are
                        //     entries of their own, so their records are crawled under them, never the cluster.
                        work.add(new Visit(list, clusterLandings(panel, cluster), owner, list, null));
                        continue;
                    }
                    boolean hasList = !(entry instanceof PanelResource<?> parts) || parts.list() != null;
                    boolean creates = entry instanceof PanelResource<?> parts
                        && parts.form() != null && parts.offers(ResourceVerb.CREATE);

                    // 5. Visit every declared list, page, singleton and settings page, even when the list is empty.
                    String landing = entry.landingTarget() == null ? null : entry.landingTarget().toUrl();
                    if (!hasList && landing != null) work.add(new Visit(landing, List.of(landing), owner, list, null));
                    if (hasList) {
                        work.add(new Visit(list, List.of(landing == null ? list : landing), owner, list,
                            opened -> openSeededRecord(opened, panel, entry, list, owner, work)));
                    }

                    // 7. Only resources declaring a create writer get a create-form visit; no form is submitted.
                    if (creates) {
                        String create = CmsRoutes.create(panel.slug(), entry.slug()).toUrl();
                        work.add(new Visit(create, List.of(create), owner + " / create", list, null));
                    }
                } catch (RuntimeException | AssertionError failure) {
                    red(list, owner, failure);
                }
            }
        }
        crawl(tabs, work);

        // 8. Account GET pages come from the endpoint registry; parameterized confirmations come from rendered links.
        //    They are crawled after the panels and one at a time on the first tab, as before: an account page acts
        //    on the one session (its sessions and confirmations), so nothing may race it.
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
        for (String path : List.copyOf(accountPages)) {
            work.add(accountVisit(path, accountRoutes, accountPages, work));
        }
        for (Tab tab : tabs.subList(1, tabs.size())) tab.retire(true);
        crawl(List.of(first), work);
        first.retire(false);

        // 9. Finish the complete crawl before failing, so one run reports every affected page to its owner.
        assertThat(visits).as("step 9: the crawl made actual HTTP page visits").isGreaterThan(panels.size());
        assertThat(reds).as("step 9: empty-database walkthrough reds:\n%s", String.join("\n\n", reds)).isEmpty();
    }

    /**
     * 6. Open a seeded overview through rendered links or keys, never invented fixture ids, read from the list page
     *    the tab just loaded.
     */
    private void openSeededRecord(Page opened, Panel panel, PanelEntry entry, String list, String owner,
                                  Deque<Visit> work) {
        String record = (String) opened.locator(
            ".cms-row-link, cms-inline-cell[href], pl-media-card-title a[data-row-link]").evaluateAll(
                "els => els.map(el => el.getAttribute('href')).find(href => !!href) || null");
        if (record == null && opened.locator("[data-row-key]").count() > 0) {
            String key = opened.locator("[data-row-key]").first().getAttribute("data-row-key");
            record = CmsRoutes.detail(panel.slug(), entry.slug(), key).toUrl();
        }
        if (record != null) {
            // A record opens on itself or one of its own declared tabs (its front door or read view), never
            // elsewhere; a row link may already name the tab.
            work.add(new Visit(record, recordLandings(panel, entry, record), owner + " / seeded record overview",
                list, null));
        } else {
            System.out.println("WALKTHROUGH_EMPTY page=" + list + " owner=" + owner);
        }
    }

    /** An account page visit whose rendered links add the account confirmations they lead to. */
    private Visit accountVisit(String path, List<EndpointRoute> accountRoutes, Set<String> accountPages,
                               Deque<Visit> work) {
        return new Visit(path, List.of(path), "zenit-auth / account page", path, opened -> {
            @SuppressWarnings("unchecked")
            List<String> links = (List<String>) opened.locator("a[href]").evaluateAll("els => els.map(el => el.href)");
            for (String link : links) {
                URI address = URI.create(link);
                if (!URI.create(url("/")).getAuthority().equals(address.getAuthority())) continue;
                for (EndpointRoute route : accountRoutes) {
                    if (route.getParameterMatches(new Uri(link)) != null) {
                        String found = address.getRawPath()
                            + (address.getRawQuery() == null ? "" : "?" + address.getRawQuery());
                        if (accountPages.add(found)) work.add(accountVisit(found, accountRoutes, accountPages, work));
                        break;
                    }
                }
            }
        });
    }

    /** Each member's own landing, the only places a cluster visit may land. */
    private static List<String> clusterLandings(Panel panel, PanelCluster cluster) {
        List<String> landings = new ArrayList<>();
        for (String slug : cluster.members()) {
            PanelEntry member = panel.entryBySlug(slug);
            if (member != null) landings.add(PanelNavStates.peerUrl(panel, member));
        }
        return landings;
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

    /**
     * One page visit: where it goes, where it may land, whose it is, the list page a failure is filed under, and
     * what the loaded document leads to when it answered 200.
     */
    private record Visit(String path, List<String> landings, String owner, String filedUnder,
                         @Nullable Consumer<Page> then) {
    }

    /**
     * One page of the signed-in context: the visit it is loading, and the errors of the document it shows, a ledger
     * every error leaves only by being reported against the visit whose document logged it.
     *
     * AIDEV-NOTE: a tab stays live after its checks while the other tab is judged, so its document can still log.
     * Such an error is reported when the document departs (the commit of the tab's next main-frame navigation; a
     * same-document URL change sends no navigation request and departs nothing) or when the tab is retired at the
     * end (closed, or the first tab sent to about:blank, and drained after that), against the visit it belongs to,
     * never dropped and never charged to the next visit. The document identity is the delivery order: Playwright
     * hands a page's events over in the order the browser sent them, and a document can send nothing once the
     * commit of its successor (or the page's close) was sent, so every error delivered before that boundary is the
     * departing document's and every one after it the next document's. Playwright's console and page-error events
     * carry no document of their own to key on.
     */
    private final class Tab {
        private final Page page;
        private final List<String> strictConsoleErrors = new CopyOnWriteArrayList<>();
        private @Nullable Visit visit;
        private @Nullable Response response;
        private @Nullable RuntimeException failure;
        private String step = "";
        /** The visit whose document the tab shows, and its step; null before the first commit. */
        private @Nullable Visit shown;
        private String shownStep = "";
        /** A main-frame navigation request went out: the next main-frame commit is a new document. */
        private boolean leaving;

        private Tab(Page page) {
            this.page = page;
            // The location names the failing resource of a "Failed to load resource" error, so a red says which.
            page.onConsoleMessage(message -> {
                if (!"error".equals(message.type())) return;
                strictConsoleErrors.add(message.text() + " @ " + message.location());
            });
            page.onRequest(request -> {
                if (request.isNavigationRequest() && request.frame() == page.mainFrame()) leaving = true;
            });
            page.onFrameNavigated(frame -> {
                if (frame != page.mainFrame() || !leaving) return;
                leaving = false;
                settle();
                // The new document belongs to the visit in flight; one a step's own form led to has no visit, and
                // is named after the step that left it.
                if (visit != null) {
                    shown = visit;
                    shownStep = step;
                } else {
                    shownStep = "the page " + frame.url() + " reached from " + shownStep;
                }
            });
        }

        /** Takes the console errors the shown document has logged so far, in order. */
        private @NonNull List<String> takeConsole() {
            List<String> taken = new ArrayList<>();
            for (String error : List.copyOf(strictConsoleErrors)) {
                strictConsoleErrors.remove(error);
                taken.add(error);
            }
            return taken;
        }

        /** Takes every error the shown document has logged so far: its console errors and its page errors. */
        private @NonNull List<String> take() {
            List<String> taken = new ArrayList<>();
            for (String error : takeConsole()) taken.add("console: " + error);
            for (String error : takeCollectedErrors(page)) taken.add("page/framework: " + error);
            return taken;
        }

        /** Reports what the shown document logged after its checks, against the visit it belongs to. */
        private void settle() {
            List<String> late = take();
            if (late.isEmpty()) return;
            Visit owner = shown;
            String path = owner == null ? page.url() : owner.path();
            red(path, owner == null ? "(no visit)" : owner.owner(),
                new AssertionError("step 5.3: errors after the checks for " + shownStep + ": " + late));
        }

        /**
         * Ends the tab's document, closing an extra tab or sending the first (the base's own page) to about:blank,
         * then reports what it logged after its checks: by then the browser has delivered all of its events.
         */
        private void retire(boolean close) {
            if (close) {
                page.close();
            } else {
                page.navigate("about:blank");
            }
            settle();
        }
    }

    /** Loads every queued visit, and the visits their documents lead to, keeping every tab loading one. */
    private void crawl(List<Tab> tabs, Deque<Visit> work) {
        boolean busy = true;
        while (busy || !work.isEmpty()) {
            busy = false;
            for (Tab tab : tabs) {
                if (tab.visit != null) finish(tab);
                Visit next = work.poll();
                if (next != null) start(tab, next);
                busy |= tab.visit != null;
            }
        }
    }

    /** {@link #start} and {@link #finish} one visit on one tab, for the steps that act on the page it loaded. */
    private void visitNow(Tab tab, String path, String owner) {
        start(tab, new Visit(path, List.of(path), owner, path, null));
        finish(tab);
    }

    /**
     * Starts a browser-initiated navigation and returns once its document is committed, leaving the tab to load
     * and hydrate it while the other tabs are judged.
     */
    private void start(Tab tab, Visit visit) {
        tab.visit = visit;
        tab.step = "page " + ++visits + " " + visit.path() + " owner=" + visit.owner();
        tab.response = null;
        tab.failure = null;
        try {
            tab.response = tab.page.navigate(URI.create(url("/")).resolve(visit.path()).toString(),
                new Page.NavigateOptions().setWaitUntil(WaitUntilState.COMMIT));
        } catch (RuntimeException failure) {
            tab.failure = failure;
        }
    }

    /** Waits for the tab's document to load and settle, judges it alone, then follows what it leads to. */
    private void finish(Tab tab) {
        Visit visit = tab.visit;
        tab.visit = null;
        if (visit == null) return;
        String step = tab.step;
        Page on = tab.page;
        Response response = tab.response;
        try {
            if (tab.failure != null) throw tab.failure;
            on.waitForLoadState(LoadState.LOAD);
            SoftAssertions checks = new SoftAssertions();
            // 5.1. A login card after an unexpected redirect must not masquerade as a successful admin page.
            checks.assertThat(response).as("step 5.1: HTTP response for " + step).isNotNull();
            if (response != null) {
                checks.assertThat(response.status()).as("step 5.1: HTTP 200 for %s; error body: %s", step,
                    response.status() == 200 ? "" : on.locator("body").innerText()).isEqualTo(200);
            }
            List<String> expected = visit.landings().stream()
                .map(landing -> URI.create(url("/")).resolve(landing).getPath()).toList();
            checks.assertThat(URI.create(on.url()).getPath()).as("step 5.1: only the declared landing for " + step)
                .isIn(expected);
            if (Boolean.TRUE.equals(on.evaluate("() => typeof window.__hawkeye_reactive_idle === 'boolean'"))) {
                try {
                    waitForReactiveIdle(on);
                } catch (RuntimeException | AssertionError failure) {
                    checks.fail("step 5.1: hydration did not settle for " + step + ": " + failure.getMessage());
                }
            }
            // 5.2. Containment can return HTTP 200 while replacing a broken region with an error badge.
            checks.assertThat(on.locator("pl-render-error").evaluateAll(
                "els => els.map(el => el.outerHTML)")).as("step 5.2: no render-error badge for " + step)
                .isEqualTo(List.of());
            // 5.3. Keep network console errors too; the usual browser base intentionally filters some of them. Both
            //      are taken from the tab's ledger: what this document logged up to now is reported here, once.
            checks.assertThat(tab.takeConsole()).as("step 5.3: no console errors for " + step).isEmpty();
            checks.assertThat(takeCollectedErrors(on)).as("step 5.3: no page/framework errors for " + step).isEmpty();
            // 5.4. Observe raw keys throughout render and hydration, including a flash that later heals.
            checks.assertThat(on.evaluate("() => window.__emptyWalkthroughRaw"))
                .as("step 5.4: no raw microcopy keys for " + step).isEqualTo(List.of());
            checks.assertAll();
            System.out.println("WALKTHROUGH_OK " + step);
        } catch (RuntimeException | AssertionError failure) {
            red(visit.path(), visit.owner(), failure);
        }
        if (visit.then() == null || response == null || response.status() != 200) return;
        try {
            visit.then().accept(on);
        } catch (RuntimeException | AssertionError failure) {
            red(visit.filedUnder(), visit.owner(), failure);
        }
    }

    /** Files one red under the page it was seen on and the owner it most likely belongs to. */
    private void red(String path, String owner, Throwable failure) {
        String red = "page=" + path + " | likely owner=" + owner + "\n" + failure.getMessage();
        reds.add(red);
        System.out.println("WALKTHROUGH_RED " + red);
    }
}
