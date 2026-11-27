package be.elevenways.hohenheim.test;

import be.elevenways.hawkeye.testSupport.PristineBrowserContext;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.ControllerIdentity;
import be.elevenways.hohenheim.server.cms.AppsSendingReads;
import be.elevenways.hohenheim.server.cms.TrafficTvLinkLevel;
import be.elevenways.hohenheim.server.mcp.HohenheimMcpModule;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.hohenheim.server.traffic.AppDestinations;
import be.elevenways.hohenheim.traffic.ActivityAsk;
import be.elevenways.hohenheim.traffic.AppsSendingView;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServer;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServers;
import be.elevenways.zenit.ai.server.mcp.host.McpHttpSession;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.auth.server.ShareLinks;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.security.AccessContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.ReducedMotion;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static be.elevenways.hohenheim.server.security.FlowAccounting.Counter.SENT;
import static be.elevenways.hohenheim.server.security.FlowAccounting.Via.THROUGH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The Activity tab's "Apps sending" picture, from a fake of the host's counter listing read by the real reader: the
 * app and where it sent under the requests, a mark on each band, the one Pause holding it while a new reading is
 * re-read and Resume drawing that reading, nothing reloaded, reduced motion stilling it; the TV page, signed out,
 * draws it beside the requests and re-reads it with the link's token; a customer reads only his own app, a stranger
 * nothing, and traffic_read names the destinations as app_network_read does.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class AppsSendingJourneyTest extends HohenheimTestBase {

    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final String REQUESTS = "[data-hh-activity-surface]";
    private static final String APPS = "[data-hh-apps-sending-surface]";
    private static final String BROWSER = "User-Agent: Mozilla/5.0 (X11; Linux x86_64; rv:133.0) Gecko/20100101"
        + " Firefox/133.0";

    @Test
    @PristineBrowserContext
    void anAdministratorWatchesWhereTheAppsSendBesideTheRequests() throws Exception {
        Deque<Runnable> cleanUp = new ArrayDeque<>();
        try (SiteAccessLogJourneyTest.Fixture fixture = SiteAccessLogJourneyTest.Fixture.start("apps-sending")) {
            TrafficCountingJourneyTest.countUnder(cleanUp, "apps-sending-" + fixture.tag);
            String sender = "sender-" + fixture.tag;
            String neighbour = "neighbour-" + fixture.tag;
            int app = AppNetworkJourneyTest.application(cleanUp, sender);
            int other = AppNetworkJourneyTest.application(cleanUp, neighbour);
            DestinationKernel kernel = AppDestinationsJourneyTest.counted(cleanUp, app, other);
            String own = WorkloadNetworkPolicy.chainKey("hohenheim-journey-" + app + "-net");
            String activity = CmsRoutes.list(HohenheimSlugs.ADMIN, HohenheimSlugs.TRAFFIC_ACTIVITY).toUrl();

            // 1. The Activity tab under its own clock: the requests, then where the apps send. The picture names
            //    the app and each place it sent to: the internet by address, the other app by name, the proxy, its
            //    database link; the line says when the counters were read.
            context.clock().install();
            page.setViewportSize(1280, 1500);
            navigateToApp(activity);
            waitForHydration();
            String picture = this.awaitPicture(page, sender + " sends");
            assertThat(picture).as("step 1: where it sent, each place named").contains("93.184.216.34")
                .contains(neighbour).contains("Hohenheim's proxy").contains("Its database");
            assertThat(page.locator("[data-hh-apps-sending-line]").textContent()).as("step 1: when it was read")
                .contains("Read at");
            assertThat((Boolean) page.evaluate("() => { const r = document.querySelector('" + REQUESTS + "');"
                + " const a = document.querySelector('" + APPS + "');"
                + " return r.getBoundingClientRect().bottom <= a.getBoundingClientRect().top; }"))
                .as("step 1: under the requests, as the board draws it").isTrue();

            // 2. Held a second on: a mark travels each of its four bands; the screenshot of both pictures.
            page.waitForFunction("() => document.querySelector('" + APPS + "').travellingMarks3d >= 4", null,
                new Page.WaitForFunctionOptions().setTimeout(WAIT.toMillis()));
            fixture.request(fixture.siteA, "/ok", BROWSER);
            fixture.request(fixture.siteB, "/other", BROWSER);
            page.waitForFunction("() => /\\d+ requests? in the last minute/.test(document.querySelector("
                + "'[data-hh-activity-line]').textContent)", null,
                new Page.WaitForFunctionOptions().setTimeout(WAIT.toMillis()));
            context.clock().pauseAt(((Number) page.evaluate("() => Date.now()")).longValue() + 250);
            context.clock().runFor(1_000);
            assertThat(((Number) page.evaluate("() => document.querySelector('" + APPS + "').travellingMarks3d"))
                .intValue()).as("step 2: a mark on each band, held").isGreaterThanOrEqualTo(4);
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("build/traffic-activity-apps.png"))
                .setFullPage(true));

            // 3. Both pictures running under a stream of requests: the host's frame time of each.
            context.clock().resume();
            Number requestsFrom = (Number) page.evaluate("() => document.querySelector('" + REQUESTS + "').frames3d");
            Number appsFrom = (Number) page.evaluate("() => document.querySelector('" + APPS + "').frames3d");
            for (int second = 0; second < 4; second++) {
                for (int i = 0; i < 15; i++) {
                    fixture.request(i % 2 == 0 ? fixture.siteA : fixture.siteB, "/stream-" + i, BROWSER);
                }
            }
            page.waitForFunction("least => { const r = document.querySelector('" + REQUESTS + "');"
                + " const a = document.querySelector('" + APPS + "');"
                + " return a.frames3d >= least[1] && typeof a.tickP95Ms3d === 'number'"
                + " && r.frames3d >= least[0] && typeof r.tickP95Ms3d === 'number'; }",
                List.of(requestsFrom.intValue() + 30, appsFrom.intValue() + 240),
                new Page.WaitForFunctionOptions().setTimeout(WAIT.toMillis()));
            System.out.println("[apps-sending] both pictures: requests tickP95Ms3d="
                + page.evaluate("() => document.querySelector('" + REQUESTS + "').tickP95Ms3d")
                + ", apps tickP95Ms3d=" + page.evaluate("() => document.querySelector('" + APPS + "').tickP95Ms3d")
                + ", apps frames " + appsFrom + " to "
                + page.evaluate("() => document.querySelector('" + APPS + "').frames3d"));

            // 4. Pause, one control for both pictures: the apps picture stands while the open page re-reads its part
            //    for a new reading (the app sent 30 000 bytes more to the internet); Resume draws that reading,
            //    nothing reloaded.
            String pause = "[data-hh-activity-pause]";
            page.locator(pause).click();
            page.waitForFunction("p => document.querySelector(p).textContent.includes('Resume')", pause,
                new Page.WaitForFunctionOptions().setTimeout(WAIT.toMillis()));
            page.evaluate("() => new Promise(done => requestAnimationFrame(() => requestAnimationFrame(done)))");
            Number held = (Number) page.evaluate("() => document.querySelector('" + APPS + "').frames3d");
            page.waitForResponse(response -> response.url().contains("/zn/live/hohenheim.apps_sending/"), () -> {
                kernel.element(THROUGH, SENT, own, "172.31.1.2", "93.184.216.34", "tcp", 443, 60, 31_500);
                assertThat(AppDestinations.readAll()).as("step 4: the reading that grew").isEqualTo(1);
            });
            page.evaluate("() => new Promise(done => requestAnimationFrame(() => requestAnimationFrame(done)))");
            assertThat(((Number) page.evaluate("() => document.querySelector('" + APPS + "').frames3d")).intValue())
                .as("step 4: paused, the picture is not drawn again, the reading heard too").isEqualTo(held.intValue());
            String before = picture;
            assertThat(page.locator(APPS).getAttribute("aria-label")).as("step 4: paused, it says the old reading")
                .isEqualTo(before);
            page.locator(pause).click();
            page.waitForFunction("before => document.querySelector('" + APPS + "').getAttribute('aria-label')"
                + " !== before", before, new Page.WaitForFunctionOptions().setTimeout(WAIT.toMillis()));
            picture = page.locator(APPS).getAttribute("aria-label");
            assertThat(picture).as("step 4: resumed, the new reading, to the internet alone")
                .contains(sender + " sends").contains("93.184.216.34").doesNotContain("Hohenheim's proxy");

            // 5. Reduced motion: nothing travels; the bands stand over a bar per reading heard.
            page.emulateMedia(new Page.EmulateMediaOptions().setReducedMotion(ReducedMotion.REDUCE));
            page.waitForFunction("() => { const a = document.querySelector('" + APPS + "');"
                + " return a.hasAttribute('data-3d-reduced-motion') && a.travellingMarks3d === 0; }", null,
                new Page.WaitForFunctionOptions().setTimeout(WAIT.toMillis()));
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("build/traffic-activity-apps-still.png"))
                .setFullPage(true));
            page.emulateMedia(new Page.EmulateMediaOptions().setReducedMotion(ReducedMotion.NO_PREFERENCE));

            // 6. The TV page, signed out: the apps picture beside the requests, as the board draws it, every app of
            //    the link's every-site ask by name; a new reading reaches it through its re-read presenting the
            //    link's token, never refused for want of a sign-in.
            AccessContext operator = TenantConduits.operator();
            ShareLinks.Minted minted = ShareLinks.create(operator, TrafficTvLinkLevel.LEVEL,
                ControllerIdentity.recordId(), null, ActivityAsk.every().spelled());
            cleanUp.push(() -> ShareLinks.revokeAt(operator, TrafficTvLinkLevel.LEVEL, ControllerIdentity.recordId(),
                each -> each.id().equals(minted.link().id())));
            page.navigate("about:blank");
            page.context().clearCookies();
            page.setViewportSize(1920, 1080);
            page.navigate(baseUrl() + "/tv/" + minted.token());
            page.waitForSelector(REQUESTS, new Page.WaitForSelectorOptions().setTimeout(WAIT.toMillis()));
            String onTv = this.awaitPicture(page, sender + " sends");
            assertThat(onTv).as("step 6: the apps by name, where they sent").contains(neighbour)
                .contains("93.184.216.34");
            assertThat((Boolean) page.evaluate("() => { const r = document.querySelector('" + REQUESTS + "');"
                + " const a = document.querySelector('" + APPS + "');"
                + " return r.getBoundingClientRect().right <= a.getBoundingClientRect().left; }"))
                .as("step 6: beside the requests, as the board's TV screen draws it").isTrue();
            assertThat(page.locator("hh-apps-sending[tv] pl-card-title").count()).as("step 6: no card heading there")
                .isZero();
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("build/traffic-tv-apps.png")));
            page.waitForResponse(response -> response.url().contains("/zn/live/hohenheim.apps_sending/")
                && response.status() == 200, () -> {
                kernel.element(THROUGH, SENT, own, "172.31.1.2", "93.184.216.34", "tcp", 443, 90, 61_500);
                assertThat(AppDestinations.readAll()).as("step 6: another reading").isEqualTo(1);
            });
            page.waitForFunction("before => document.querySelector('" + APPS + "').getAttribute('aria-label')"
                + " !== before", onTv, new Page.WaitForFunctionOptions().setTimeout(WAIT.toMillis()));
        } finally {
            while (!cleanUp.isEmpty()) cleanUp.pop().run();
        }
    }

    /** @return the picture's words once they say {@code says} */
    private String awaitPicture(Page on, String says) {
        on.waitForFunction("says => { const s = document.querySelector('" + APPS + "');"
            + " return s !== null && (s.getAttribute('aria-label') || '').includes(says); }", says,
            new Page.WaitForFunctionOptions().setTimeout(WAIT.toMillis()));
        return on.locator(APPS).getAttribute("aria-label");
    }

    @Test
    void aCustomerReadsHisOwnAppsAndTrafficReadNamesTheDestinations() throws Exception {
        Deque<Runnable> cleanUp = new ArrayDeque<>();
        try {
            String tag = HohenheimMcpTest.letters();
            TrafficCountingJourneyTest.countUnder(cleanUp, "apps-sending-read-" + tag);
            String sender = "sender-" + tag;
            String neighbour = "neighbour-" + tag;
            int app = AppNetworkJourneyTest.application(cleanUp, sender);
            int other = AppNetworkJourneyTest.application(cleanUp, neighbour);
            AppDestinationsJourneyTest.counted(cleanUp, app, other);

            // 1. An administrator: the app first, every place it sent to; the other app by name.
            AppsSendingView all = Objects.requireNonNull(AppsSendingReads.at(TenantConduits.operator(),
                ActivityAsk.every()).value());
            assertThat(all.apps().getFirst().key()).as("step 1: the one that sent first").isEqualTo(String.valueOf(app));
            assertThat(all.places()).as("step 1: four places").hasSize(4);
            assertThat(all.places()).extracting(place -> place.name().resolve(LocaleChain.empty(), null))
                .as("step 1: the other app by name").contains(neighbour);

            // 2. A customer who manages the app: his app alone, the other app by its kind's words and address.
            UUID customer = ApiSupport.user("apps-sending-" + tag + "@hohenheim.local", "Sending customer");
            cleanUp.push(() -> Models.get(UserModel.class).delete(customer));
            RecordGrants.grant(GrantSubjectType.USER, customer, InstanceModel.MODEL_ID, app,
                HohenheimCapabilities.MANAGE, true);
            AccessContext his = AccessContext.detached(new UserPrincipal(customer, "Sending customer"));
            AppsSendingView own = Objects.requireNonNull(AppsSendingReads.at(his, ActivityAsk.every()).value());
            assertThat(own.apps()).as("step 2: his app alone").extracting(AppsSendingView.App::key)
                .containsExactly(String.valueOf(app));
            assertThat(own.places()).extracting(place -> place.name().resolve(LocaleChain.empty(), null))
                .as("step 2: never the other customer's app's name").doesNotContain(neighbour);
            assertThat(own.places()).extracting(place -> place.name().key())
                .as("step 2: it is an app of this server, by those words").contains("destination_app_unnamed");

            // 3. Nobody signed in, and a TV ask without a link, are refused.
            assertThat(catchThrowable(() -> AppsSendingReads.at(AccessContext.anonymous(), ActivityAsk.every())))
                .as("step 3: nobody").isInstanceOf(DomainRefusal.class);
            assertThat(catchThrowable(() -> AppsSendingReads.at(TenantConduits.operator(),
                ActivityAsk.tv("znsl_nothing")))).as("step 3: a TV ask").isInstanceOf(DomainRefusal.class);

            // 4. traffic_read's apps_sending names each destination as app_network_read does; with a site it is
            //    refused by its argument.
            URI endpoint = URI.create(baseUrl() + "/zn/mcp/" + HohenheimMcpModule.SERVER_NAME);
            McpHostServer server = Objects.requireNonNull(McpHostServers.routed(HohenheimMcpModule.SERVER_NAME));
            McpHttpSession admin = HohenheimMcpTest.presetSession(server, endpoint,
                TenantConduits.operatorUser().get(UserModel.ID), "Test Admin");
            McpHttpSession.Answer read = admin.call("traffic_read", Map.of("top", "apps_sending"));
            assertThat(read.error()).as("step 4: " + read.text()).isFalse();
            assertThat(read.text()).as("step 4: THE ASSERTION, by app and where to")
                .contains(sender + " 93.184.216.34 93.184.216.34:443/tcp")
                .contains(sender + " " + neighbour + " 172.31.2.2:8080/tcp")
                .contains(sender + " Hohenheim's proxy 203.0.113.10:443/tcp");
            McpHttpSession.Answer network = admin.call("app_network_read", Map.of("code",
                HohenheimMcpTest.code(InstanceModel.MODEL_ID, app, TenantConduits.operator()), "top", "destinations"));
            assertThat(network.text()).as("step 4: the same name").contains("app " + neighbour + " 172.31.2.2");
        } finally {
            while (!cleanUp.isEmpty()) cleanUp.pop().run();
        }
    }
}
