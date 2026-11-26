package be.elevenways.hohenheim.test;

import be.elevenways.hawkeye.testSupport.PristineBrowserContext;
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
 * The server Traffic page's Activity tab: requests sent through the test proxy fly in the live picture, held mid-flight
 * under the page's clock; its line, its rows and its rates say one count; the legend names every class in words; a
 * screen reader is told the minute; Pause holds the picture and its figures; reduced motion stills it; and the live
 * channel gives a site's manager his own site's frames only, never another site's or every site's.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class TrafficActivityJourneyTest extends HohenheimTestBase {

    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final String SURFACE = "[data-hh-activity-surface]";
    private static final String BROWSER = "User-Agent: Mozilla/5.0 (X11; Linux x86_64; rv:133.0) Gecko/20100101"
        + " Firefox/133.0";
    private static final Pattern RATE = Pattern.compile("(\\d+) requests? a minute");

    @Test
    @PristineBrowserContext
    void anAdministratorWatchesEverySitesRequestsLive() throws Exception {
        Deque<Runnable> cleanUp = new ArrayDeque<>();
        try (SiteAccessLogJourneyTest.Fixture fixture = SiteAccessLogJourneyTest.Fixture.start("traffic-activity")) {
            TrafficCountingJourneyTest.countUnder(cleanUp, "activity-" + fixture.tag);
            String activity = CmsRoutes.list(HohenheimSlugs.ADMIN, HohenheimSlugs.TRAFFIC_ACTIVITY).toUrl();

            // 1. The tab under the page's own clock: Report, Activity, Settings, no trail over the title; the legend
            //    names each class in words; nothing heard yet, or a quiet minute.
            context.clock().install();
            page.setViewportSize(1280, 1000);
            navigateToApp(activity);
            waitForHydration();
            List<String> tabs = page.locator("[data-hh-traffic-tabs] pl-nav-item").allTextContents();
            assertThat(tabs).as("step 1: three tabs").hasSize(3);
            assertThat(tabs.get(1)).as("step 1: Activity between Report and Settings").contains("Activity");
            assertThat(page.locator("[data-hh-traffic-activity] [data-cms-trail]").count())
                .as("step 1: no trail over the title, as on the Report tab").isZero();
            assertThat(page.locator("[data-hh-activity-legend]").textContent()).as("step 1: the classes in words")
                .contains("2xx answered").contains("3xx").contains("4xx").contains("5xx").doesNotContain("1xx")
                .doesNotContain("Other status");
            assertThat(page.locator("pl-kiosk[data-tone]").count()).as("step 1: the tab wears the panel's theme")
                .isZero();
            page.waitForFunction("() => document.querySelector('[data-hh-activity-line]').textContent"
                + ".includes('in the last minute')", null, new Page.WaitForFunctionOptions().setTimeout(WAIT.toMillis()));

            // 2. Three requests from one browser, one refused, to two sites: once the picture drew them, the clock is
            //    held a second on, while all three are in flight. The picture's rows name the visitor and each site's
            //    rate, and the line counts what the rates add up to: one count.
            fixture.request(fixture.siteA, "/ok", BROWSER);
            fixture.request(fixture.siteA, "/missing", BROWSER);
            fixture.request(fixture.siteB, "/other-site", BROWSER);
            this.awaitLined(3);
            // A quarter second ahead: the page's clock runs on while the hold is asked, and is never held in its past.
            context.clock().pauseAt(((Number) page.evaluate("() => Date.now()")).longValue() + 250);
            context.clock().runFor(1_000);
            String picture = page.locator(SURFACE).getAttribute("aria-label");
            int counted = this.lined();
            assertThat(picture).as("step 2: the visitor's own row, by its address")
                .containsPattern("127\\.0\\.0\\.1: \\d+ requests");
            assertThat(picture).as("step 2: site a's rate").contains(
                "Access a " + fixture.tag + ": 2 requests a minute");
            assertThat(picture).as("step 2: site b's rate").contains(
                "Access b " + fixture.tag + ": 1 request a minute");
            int rates = 0;
            for (Matcher rate = RATE.matcher(picture); rate.find(); ) {
                rates += Integer.parseInt(rate.group(1));
            }
            assertThat(rates).as("step 2: the line and the rates are one count: " + picture).isEqualTo(counted);
            Number flying = (Number) page.evaluate("() => document.querySelector('" + SURFACE + "').flyingDots3d");
            assertThat(flying.intValue()).as("step 2: the three dots in flight, held").isGreaterThanOrEqualTo(3);
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("build/traffic-activity.png"))
                .setFullPage(true));
            // In its place the picture is as tall as its rows fit (never under 14rem), not a fixed box.
            assertThat((Boolean) page.evaluate("() => { const s = document.querySelector('" + SURFACE + "');"
                + " const fit = parseFloat(s.style.getPropertyValue('--hh-activity-fit'));"
                + " const least = 14 * parseFloat(getComputedStyle(document.documentElement).fontSize);"
                + " return fit > 0 && Math.abs(s.getBoundingClientRect().height - Math.max(fit, least)) < 2; }"))
                .as("step 2: the picture fits its rows").isTrue();

            // 2b. One browser asks site a six times at once: six dots, spread across the row and along the curve
            //     (ActivityModelTest pins the spread), all in flight under the held clock.
            context.clock().resume();
            fixture.requests(fixture.siteA, "/burst", 6, BROWSER);
            counted = this.awaitLined(counted + 6);
            context.clock().pauseAt(((Number) page.evaluate("() => Date.now()")).longValue() + 250);
            context.clock().runFor(1_100);
            assertThat(((Number) page.evaluate("() => document.querySelector('" + SURFACE + "').flyingDots3d"))
                .intValue()).as("step 2b: six dots of one moment in flight").isGreaterThanOrEqualTo(6);
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("build/traffic-activity-burst.png"))
                .setFullPage(true));

            // 3. The clock runs again under a steady stream (fifteen requests a second for four seconds): the host
            //    writes the picture's frame time over its last 600 frames, read once 240 frames were drawn since.
            context.clock().resume();
            Number resumed = (Number) page.evaluate("() => document.querySelector('" + SURFACE + "').frames3d");
            int most = 0;
            for (int second = 0; second < 4; second++) {
                for (int i = 0; i < 15; i++) {
                    fixture.request(i % 2 == 0 ? fixture.siteA : fixture.siteB, "/stream-" + i, BROWSER);
                }
                this.awaitLined(counted + 15 * (second + 1));
                most = Math.max(most, ((Number) page.evaluate("() => document.querySelector('" + SURFACE
                    + "').flyingDots3d")).intValue());
            }
            page.waitForFunction("least => { const s = document.querySelector('" + SURFACE + "'); return "
                + "s.frames3d >= least && typeof s.tickP95Ms3d === 'number'; }", resumed.intValue() + 240,
                new Page.WaitForFunctionOptions().setTimeout(WAIT.toMillis()));
            Object tick = page.evaluate("() => document.querySelector('" + SURFACE + "').tickP95Ms3d");
            System.out.println("[traffic-activity] tickP95Ms3d=" + tick + " with at most " + most
                + " dots in flight, frames " + resumed + " to "
                + page.evaluate("() => document.querySelector('" + SURFACE + "').frames3d"));
            assertThat(most).as("step 3: the stream kept dots in flight").isPositive();

            // 4. A screen reader is told the last minute every quarter minute, politely, never per request.
            page.waitForFunction("() => document.querySelector('[data-hh-activity-spoken]').textContent"
                + ".includes('In the last minute')", null, new Page.WaitForFunctionOptions().setTimeout(20_000));
            assertThat(page.locator("[data-hh-activity-spoken]").getAttribute("politeness"))
                .as("step 4: politely").isNotEqualTo("assertive");

            // 5. Pause holds the picture and its figures as they stand, never the counting: the button says
            //    Resume, and a request sent meanwhile is in the line once the picture moves again.
            click("[data-hh-activity-pause]");
            assertText("[data-hh-activity-pause]", "Resume", "step 5: paused");
            String held = page.locator("[data-hh-activity-line]").textContent();
            counted = this.lined();
            fixture.request(fixture.siteB, "/while-paused", BROWSER);
            assertThat(page.locator("[data-hh-activity-line]").textContent()).as("step 5: the line holds")
                .isEqualTo(held);
            click("[data-hh-activity-pause]");
            assertText("[data-hh-activity-pause]", "Pause", "step 5: moving again");
            this.awaitLined(counted + 1);

            // 6. Reduced motion: the surface follows the preference live and draws the rows still over the bars of
            //    the minute, the picture grown to fit the bars too; the frame after that draws at the grown size.
            Number before = (Number) page.evaluate("() => document.querySelector('" + SURFACE + "').frames3d");
            page.emulateMedia(new Page.EmulateMediaOptions().setReducedMotion(ReducedMotion.REDUCE));
            page.waitForFunction("before => { const s = document.querySelector('" + SURFACE + "');"
                + " const fit = parseFloat(s.style.getPropertyValue('--hh-activity-fit'));"
                + " const least = 14 * parseFloat(getComputedStyle(document.documentElement).fontSize);"
                + " return s.hasAttribute('data-3d-reduced-motion') && s.frames3d > before"
                + " && Math.abs(s.getBoundingClientRect().height - Math.max(fit, least)) < 2; }", before.intValue(),
                new Page.WaitForFunctionOptions().setTimeout(WAIT.toMillis()));
            Number grown = (Number) page.evaluate("() => document.querySelector('" + SURFACE + "').frames3d");
            page.waitForFunction("grown => document.querySelector('" + SURFACE + "').frames3d > grown",
                grown.intValue(), new Page.WaitForFunctionOptions().setTimeout(WAIT.toMillis()));
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
        return this.lined();
    }

    /** @return the requests the line counts now */
    private int lined() {
        String line = page.locator("[data-hh-activity-line]").textContent();
        Matcher matched = Pattern.compile("(\\d+) requests? in the last minute").matcher(line);
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
