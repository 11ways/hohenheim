package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimChannels;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.cms.TrafficActivityHandler;
import be.elevenways.hohenheim.server.traffic.HohenheimTraffic;
import be.elevenways.hohenheim.traffic.ActivityAsk;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.common.channel.ChannelException;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.test.support.FakeChannelLink;
import be.elevenways.zenit.traffic.common.LiveHit;
import be.elevenways.zenit.traffic.common.LiveHits;
import be.elevenways.zenit.traffic.common.LiveSource;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.ReducedMotion;
import be.elevenways.protoblast.common.time.Now;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The server Traffic page's Activity tab: requests sent through the test proxy fly in the live picture, its line and
 * its screen reader summary count them, the legend names every class in words, Pause holds the picture, reduced motion
 * stills it; and the live channel gives a site's manager his own site's frames only, never another site's or every
 * site's.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class TrafficActivityJourneyTest extends HohenheimTestBase {

    private static final Duration WAIT = Duration.ofSeconds(15);

    @Test
    void anAdministratorWatchesEverySitesRequestsLive() throws Exception {
        Deque<Runnable> cleanUp = new ArrayDeque<>();
        try (SiteAccessLogJourneyTest.Fixture fixture = SiteAccessLogJourneyTest.Fixture.start("traffic-activity")) {
            TrafficCountingJourneyTest.countUnder(cleanUp, "activity-" + fixture.tag);
            String activity = CmsRoutes.list(HohenheimSlugs.ADMIN, HohenheimSlugs.TRAFFIC_ACTIVITY).toUrl();

            // 1. The tab: Report, Activity, Settings; the legend names each class in words; nothing heard yet, or a
            //    quiet minute.
            page.setViewportSize(1280, 1000);
            navigateToApp(activity);
            waitForHydration();
            List<String> tabs = page.locator("[data-hh-traffic-tabs] pl-nav-item").allTextContents();
            assertThat(tabs).as("step 1: three tabs").hasSize(3);
            assertThat(tabs.get(1)).as("step 1: Activity between Report and Settings").contains("Activity");
            assertThat(page.locator("[data-hh-activity-legend]").textContent()).as("step 1: the classes in words")
                .contains("2xx answered").contains("3xx").contains("4xx").contains("5xx");
            page.waitForFunction("() => document.querySelector('[data-hh-activity-line]').textContent"
                + ".includes('in the last minute')", null, new Page.WaitForFunctionOptions().setTimeout(WAIT.toMillis()));

            // 2. Three requests through the proxy, one refused: the line counts them within the minute, the picture
            //    draws, and its frame time is read where the host writes it.
            fixture.request(fixture.siteA, "/ok");
            fixture.request(fixture.siteA, "/missing");
            fixture.request(fixture.siteB, "/other-site");
            // Every site's minute: another test's requests may be in it too, so the line counts at least these.
            int counted = this.awaitLined(3);
            page.waitForFunction("() => (document.querySelector('[data-hh-activity-surface]').frames3d || 0) > 0",
                null, new Page.WaitForFunctionOptions().setTimeout(WAIT.toMillis()));
            assertThat(page.locator("[data-hh-activity-surface]").getAttribute("data-3d-gl"))
                .as("step 2: the picture draws through the shared surface").isNotNull();
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("build/traffic-activity.png"))
                .setFullPage(true));
            Object tick = page.evaluate("() => document.querySelector('[data-hh-activity-surface]').tickP95Ms3d");
            System.out.println("[traffic-activity] tickP95Ms3d=" + tick);

            // 3. A screen reader is told the last minute every quarter minute, politely, never per request.
            page.waitForFunction("() => document.querySelector('[data-hh-activity-spoken]').textContent"
                + ".includes('In the last minute')", null, new Page.WaitForFunctionOptions().setTimeout(20_000));
            assertThat(page.locator("[data-hh-activity-spoken]").getAttribute("politeness"))
                .as("step 3: politely").isNotEqualTo("assertive");

            // 4. Pause holds the picture, never the counting: the button says Resume, and a request sent meanwhile
            //    is in the line all the same.
            click("[data-hh-activity-pause]");
            assertText("[data-hh-activity-pause]", "Resume", "step 4: paused");
            fixture.request(fixture.siteB, "/while-paused");
            this.awaitLined(counted + 1);
            click("[data-hh-activity-pause]");
            assertText("[data-hh-activity-pause]", "Pause", "step 4: moving again");

            // 5. Reduced motion: the surface follows the preference live and draws the still bars.
            page.emulateMedia(new Page.EmulateMediaOptions().setReducedMotion(ReducedMotion.REDUCE));
            page.waitForFunction("() => document.querySelector('[data-hh-activity-surface]')"
                + ".hasAttribute('data-3d-reduced-motion')", null,
                new Page.WaitForFunctionOptions().setTimeout(WAIT.toMillis()));
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("build/traffic-activity-still.png"))
                .setFullPage(true));
            page.emulateMedia(new Page.EmulateMediaOptions().setReducedMotion(ReducedMotion.NO_PREFERENCE));
        } finally {
            while (!cleanUp.isEmpty()) cleanUp.pop().run();
        }
    }

    /** @return the requests the line counts once it counts at least {@code least} */
    private int awaitLined(int least) {
        page.waitForFunction("least => { const m = /(\\d+) requests? in the last minute/.exec(document.querySelector("
            + "'[data-hh-activity-line]').textContent); return m !== null && Number(m[1]) >= least; }", least,
            new Page.WaitForFunctionOptions().setTimeout(WAIT.toMillis()));
        String line = page.locator("[data-hh-activity-line]").textContent();
        Matcher matched = Pattern.compile("(\\d+) requests? in the last minute")
            .matcher(line);
        assertThat(matched.find()).as("the line counts the minute's requests: " + line).isTrue();
        return Integer.parseInt(matched.group(1));
    }

    @Test
    void aSiteManagerHearsHisOwnSiteOnlyAndIsRefusedTheRest() throws Exception {
        Deque<Runnable> cleanUp = new ArrayDeque<>();
        try (SiteAccessLogJourneyTest.Fixture fixture = SiteAccessLogJourneyTest.Fixture.start("traffic-live")) {
            TrafficCountingJourneyTest.countUnder(cleanUp, "live-" + fixture.tag);
            UUID customer = ApiSupport.user("traffic-live-" + fixture.tag + "@hohenheim.local", "Live customer");
            cleanUp.push(() -> Models.get(UserModel.class).delete(customer));
            RecordGrants.grant(GrantSubjectType.USER, customer, SiteModel.MODEL_ID, fixture.siteA,
                HohenheimCapabilities.MANAGE, true);
            String own = HohenheimTraffic.sourceOf(fixture.siteA);
            String other = HohenheimTraffic.sourceOf(fixture.siteB);
            FakeChannelLink<Object, LiveHits> link = new FakeChannelLink<>(HohenheimChannels.TRAFFIC_LIVE,
                "traffic-live-customer").principal(new UserPrincipal(customer, "Live customer"));
            cleanUp.push(link::close);

            // 1. Every site, another's site, a TV ask without a link and nonsense are refused alike.
            for (Object asked : new Object[] {ActivityAsk.every().spelled(), ActivityAsk.site(other).spelled(),
                    ActivityAsk.tv("znsl_nothing").spelled(), "nonsense", 12}) {
                Throwable refused = catchThrowable(() -> new TrafficActivityHandler(link).onOpen(asked));
                assertThat(ChannelException.reasonOf(refused)).as("step 1: " + asked + " is refused")
                    .isEqualTo(ZenitRefusalReason.PERMISSION_DENIED);
            }

            // 2. His own site: its frames carry his site's hits with their visitors, and nothing of the other site.
            new TrafficActivityHandler(link).onOpen(ActivityAsk.site(own).spelled());
            Instant began = Now.instant();
            fixture.request(fixture.siteA, "/mine");
            fixture.request(fixture.siteB, "/theirs");
            List<LiveHits> frames = link.awaitSubmitted(frame -> frame.sources().stream()
                .anyMatch(source -> !source.hits().isEmpty()), 1, WAIT);
            assertThat(frames).as("step 2: his site's second arrived").isNotEmpty();
            for (LiveHits frame : link.submitted()) {
                assertThat(frame.sources()).as("step 2: only his site, every second").extracting(LiveSource::source)
                    .containsOnly(own);
                for (LiveSource source : frame.sources()) {
                    assertThat(source.hits()).as("step 2: never the other site's paths").extracting(LiveHit::path)
                        .doesNotContain("/theirs");
                }
            }
            LiveHits heard = link.submitted().stream().filter(frame -> frame.sources().stream()
                .anyMatch(source -> !source.hits().isEmpty())).findFirst().orElseThrow();
            LiveHit mine = heard.sources().getFirst().hits().getFirst();
            assertThat(mine.path()).as("step 2: his own request").isEqualTo("/mine");
            assertThat(mine.visitor()).as("step 2: with its visitor, which a manager may read").isNotBlank();
            assertThat(heard.second()).as("step 2: stamped with its second")
                .isGreaterThanOrEqualTo(began.getEpochSecond() - 1);

            // 3. The Activity tab itself is an administrator's page.
            HttpResponse<String> tab = httpGet(CmsRoutes.list(HohenheimSlugs.MANAGE, HohenheimSlugs.TRAFFIC_ACTIVITY)
                .toUrl(), sessionFor(customer).token());
            assertThat(tab.statusCode()).as("step 3: no page of his").isIn(403, 404);
        } finally {
            while (!cleanUp.isEmpty()) cleanUp.pop().run();
        }
    }
}
