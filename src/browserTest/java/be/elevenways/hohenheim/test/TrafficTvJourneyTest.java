package be.elevenways.hohenheim.test;

import be.elevenways.hawkeye.testSupport.PristineBrowserContext;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimChannels;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.ControllerIdentity;
import be.elevenways.hohenheim.server.cms.TrafficActivityHandler;
import be.elevenways.hohenheim.server.cms.TrafficTvLinkLevel;
import be.elevenways.hohenheim.server.mcp.HohenheimMcpModule;
import be.elevenways.hohenheim.server.traffic.HohenheimTraffic;
import be.elevenways.hohenheim.traffic.ActivityAsk;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServer;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServers;
import be.elevenways.zenit.ai.server.mcp.host.McpHttpSession;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.ShareLinkModel;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.auth.server.ShareLink;
import be.elevenways.zenit.auth.server.ShareLinks;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.common.channel.ChannelException;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.server.operation.RecordCodes;
import be.elevenways.zenit.test.support.FakeChannelLink;
import be.elevenways.zenit.traffic.common.LiveHit;
import be.elevenways.zenit.traffic.common.LiveHits;
import be.elevenways.zenit.traffic.common.LiveSource;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The TV page by revocable link: an administrator makes a link on the Activity tab and is shown its address once; the
 * page opens signed out, with no panel around it, the dots flying under a held clock and no visitor's address in its
 * words or in the frames it hears; its picture is sized from the screen; ending the link stops the open page; a
 * wrong or ended token is the ordinary not-found. The tools make and end links on the same operations, and a
 * customer is refused both.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class TrafficTvJourneyTest extends HohenheimTestBase {

    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final String SURFACE = "[data-hh-activity-surface]";
    private static final String BROWSER = "User-Agent: Mozilla/5.0 (X11; Linux x86_64; rv:133.0) Gecko/20100101"
        + " Firefox/133.0";
    private static final Pattern ADDRESS = Pattern.compile("https?://\\S+/tv/(znsl_[A-Za-z0-9_-]+)");
    /** Every visitor of the test proxy calls from here: the address no TV page may carry. */
    private static final String VISITOR = "127.0.0.1";

    @Test
    @PristineBrowserContext
    void aTvLinkOpensTheLivePictureSignedOutUntilItIsEnded() throws Exception {
        Deque<Runnable> cleanUp = new ArrayDeque<>();
        try (SiteAccessLogJourneyTest.Fixture fixture = SiteAccessLogJourneyTest.Fixture.start("traffic-tv")) {
            TrafficCountingJourneyTest.countUnder(cleanUp, "tv-" + fixture.tag);
            String activity = CmsRoutes.list(HohenheimSlugs.ADMIN, HohenheimSlugs.TRAFFIC_ACTIVITY).toUrl();

            // 1. The Activity tab's TV page card: the administrator makes a link of every site; its address is shown
            //    once, the list names what it shows and who made it, and the page drawn again shows no address.
            navigateToApp(activity);
            waitForHydration();
            // The make asks only its form: its submit is the confirm, no dialog.
            page.locator("[data-hh-tv-make] [data-cms-page-action-submit]").click();
            page.waitForSelector("[data-hh-tv-address]", new Page.WaitForSelectorOptions().setTimeout(WAIT.toMillis()));
            Matcher shown = ADDRESS.matcher(page.locator("[data-hh-tv-address]").textContent());
            assertThat(shown.find()).as("step 1: the address, shown once").isTrue();
            String token = shown.group(1);
            ShareLink link = Objects.requireNonNull(TrafficTvLinkLevel.open(token), "step 1: a live TV link");
            cleanUp.push(() -> ShareLinks.revokeAt(TenantConduits.operator(), TrafficTvLinkLevel.LEVEL,
                ControllerIdentity.recordId(), each -> each.id().equals(link.id())));
            String code = RecordCodes.codeUnjudged(ShareLinkModel.MODEL_ID, link.id().toString());
            String row = "[data-hh-tv-link='" + code + "']";
            assertThat(page.locator(row).textContent()).as("step 1: what it shows and who made it")
                .contains("Every site").contains("Test");
            navigateToApp(activity);
            waitForHydration();
            assertThat(page.locator("[data-hh-tv-address]").count()).as("step 1: never shown again").isZero();
            assertThat(page.content()).as("step 1: nor anywhere in the page").doesNotContain(token);

            // 2. Signed out, at 1920x1080 under the page's clock: the TV page, no panel chrome, no way into the
            //    panel, the kiosk staged and its screen held awake.
            List<String> frames = new CopyOnWriteArrayList<>();
            page.navigate("about:blank");
            page.context().clearCookies();
            Page tv = page.context().newPage();
            tv.onWebSocket(socket -> socket.onFrameReceived(frame -> frames.add(String.valueOf(frame.text()))));
            context.clock().install();
            tv.setViewportSize(1920, 1080);
            tv.navigate(baseUrl() + "/tv/" + token);
            tv.waitForSelector("hh-traffic-activity[tv] pl-kiosk[data-staged]",
                new Page.WaitForSelectorOptions().setTimeout(WAIT.toMillis()));
            assertThat(tv.locator("[data-cms-shell], cms-sidebar, nav").count()).as("step 2: no panel around it")
                .isZero();
            assertThat(tv.locator("a[href*='/" + HohenheimSlugs.ADMIN + "']").count())
                .as("step 2: no way into the panel").isZero();
            assertThat(tv.locator("[data-hh-activity-legend]").textContent()).as("step 2: the legend in words, the"
                + " everyday classes alone while nothing rarer was heard").contains("2xx answered").contains("5xx")
                .doesNotContain("1xx").doesNotContain("Other status");
            assertThat(tv.locator("hh-traffic-activity[tv] pl-kiosk[data-tone='dark']").count())
                .as("step 2: a wall screen is dark, its look's dark answer").isEqualTo(1);

            // 3. Three requests from one browser: once the picture drew them the clock is held, the dots in flight;
            //    the visitor reads as "Visitor 1", never by address, in the words, the picture's name and the frames.
            fixture.request(fixture.siteA, "/ok", BROWSER);
            fixture.request(fixture.siteA, "/missing", BROWSER);
            fixture.request(fixture.siteB, "/other-site", BROWSER);
            int counted = this.awaitLined(tv, 3);
            context.clock().pauseAt(((Number) tv.evaluate("() => Date.now()")).longValue() + 250);
            context.clock().runFor(1_000);
            String picture = tv.locator(SURFACE).getAttribute("aria-label");
            assertThat(picture).as("step 3: the visitor by number").contains("Visitor 1");
            assertThat(((Number) tv.evaluate("() => document.querySelector('" + SURFACE + "').flyingDots3d"))
                .intValue()).as("step 3: the dots in flight, held").isGreaterThanOrEqualTo(1);
            String words = (String) tv.evaluate("() => document.body.innerText");
            assertThat(words + picture).as("step 3: no visitor address in the page").doesNotContain(VISITOR);
            assertThat(tv.content()).as("step 3: nor in its markup").doesNotContain(VISITOR);
            assertThat(frames).as("step 3: frames arrived").anyMatch(frame -> frame.contains("/other-site"));
            assertThat(frames).as("step 3: no frame carries an address").noneMatch(frame -> frame.contains(VISITOR));
            tv.screenshot(new Page.ScreenshotOptions().setPath(Path.of("build/traffic-tv-1080.png")));

            // 4. A steady stream at 1080p (fifteen requests a second for four seconds) under the running clock: the
            //    host writes the picture's frame time over its last 600 frames, read once 240 were drawn since.

            context.clock().resume();
            Number resumed = (Number) tv.evaluate("() => document.querySelector('" + SURFACE + "').frames3d");
            int most = 0;
            for (int second = 0; second < 4; second++) {
                for (int i = 0; i < 15; i++) {
                    fixture.request(i % 2 == 0 ? fixture.siteA : fixture.siteB, "/stream-" + i, BROWSER);
                }
                this.awaitLined(tv, counted + 15 * (second + 1));
                most = Math.max(most, ((Number) tv.evaluate("() => document.querySelector('" + SURFACE
                    + "').flyingDots3d")).intValue());
            }
            tv.waitForFunction("least => { const s = document.querySelector('" + SURFACE + "'); return "
                + "s.frames3d >= least && typeof s.tickP95Ms3d === 'number'; }", resumed.intValue() + 240,
                new Page.WaitForFunctionOptions().setTimeout(WAIT.toMillis()));
            System.out.println("[traffic-tv] tickP95Ms3d=" + tv.evaluate("() => document.querySelector('" + SURFACE
                + "').tickP95Ms3d") + " at 1920x1080 with at most " + most + " dots in flight");
            assertThat(most).as("step 4: the stream kept dots in flight").isPositive();
            assertThat(frames).as("step 4: still no address").noneMatch(frame -> frame.contains(VISITOR));

            // 5. A 4K screen opening the same link: its type about twice the 1080p one, and the picture drawn from
            //    that type, its rows filling the screen, a dot in flight.
            double small = this.typeOf(tv);
            Page big = page.context().newPage();
            big.setViewportSize(3840, 2160);
            big.navigate(baseUrl() + "/tv/" + token);
            big.waitForSelector("hh-traffic-activity[tv] pl-kiosk[data-staged]",
                new Page.WaitForSelectorOptions().setTimeout(WAIT.toMillis()));
            fixture.request(fixture.siteA, "/on-a-big-screen", BROWSER);
            this.awaitLined(big, 1);
            big.waitForFunction("() => document.querySelector('" + SURFACE + "').flyingDots3d > 0", null,
                new Page.WaitForFunctionOptions().setTimeout(WAIT.toMillis()));
            big.screenshot(new Page.ScreenshotOptions().setPath(Path.of("build/traffic-tv-2160.png")));
            assertThat(this.typeOf(big)).as("step 5: never tiny on a 4K screen").isGreaterThanOrEqualTo(small * 1.8);
            big.close();

            // 6. The administrator ends the link on the Activity tab: the framework's dialog asks once, saying what
            //    ending does; confirmed, the row is gone and the page stands whole; the open TV page stops and says so.
            navigateToApp(activity);
            waitForHydration();
            page.locator(row + " [data-cms-page-action-submit]").click();
            Locator ok = page.locator("[data-cms-confirm-ok]:visible");
            ok.waitFor(new Locator.WaitForOptions().setTimeout(WAIT.toMillis()));
            assertThat(tv.locator("hh-traffic-activity[data-ended]").count()).as("step 6: nothing ended before the yes")
                .isZero();
            assertThat(page.locator("pl-alert-dialog-description:visible").textContent())
                .as("step 6: the dialog says what ending does").contains("a TV page showing it stops");
            ok.click();
            page.waitForCondition(() -> page.locator(row).count() == 0);
            assertThat(page.locator("[data-hh-tv-none]").count()).as("step 6: no link open").isEqualTo(1);
            assertThat(page.locator(SURFACE).count()).as("step 6: the page intact, its picture too").isEqualTo(1);
            // The host draws no box of its own (its staged kiosk is fixed over the window): attached, not visible.
            tv.waitForSelector("hh-traffic-activity[data-ended]", new Page.WaitForSelectorOptions()
                .setState(WaitForSelectorState.ATTACHED).setTimeout(WAIT.toMillis()));
            assertThat(tv.locator("[data-hh-activity-line]").textContent()).as("step 6: the page says so")
                .contains("This TV link was ended");

            // 7. An ended token and a wrong one are the ordinary not-found, never a hint that a link existed.
            HttpResponse<String> ended = httpGet("/tv/" + token, null);
            HttpResponse<String> wrong = httpGet("/tv/znsl_nothing" + fixture.tag, null);
            assertThat(ended.statusCode()).as("step 7: ended").isEqualTo(404);
            assertThat(wrong.statusCode()).as("step 7: wrong").isEqualTo(404);
            assertThat(ended.body().replace(token, "T")).as("step 7: one answer for both")
                .isEqualTo(wrong.body().replace("znsl_nothing" + fixture.tag, "T"));
            assertThat(ended.body().toLowerCase()).as("step 7: no word of a link").doesNotContain("tv link")
                .doesNotContain("ended");
            tv.close();
        } finally {
            while (!cleanUp.isEmpty()) cleanUp.pop().run();
        }
    }

    @Test
    void aTvLinksFramesCarryNoAddressAndEndWithTheLink() throws Exception {
        Deque<Runnable> cleanUp = new ArrayDeque<>();
        try (SiteAccessLogJourneyTest.Fixture fixture = SiteAccessLogJourneyTest.Fixture.start("traffic-tv-live")) {
            TrafficCountingJourneyTest.countUnder(cleanUp, "tv-live-" + fixture.tag);
            AccessContext operator = TenantConduits.operator();
            String source = HohenheimTraffic.sourceOf(fixture.siteA);
            ShareLinks.Minted minted = ShareLinks.create(operator, TrafficTvLinkLevel.LEVEL,
                ControllerIdentity.recordId(), null, ActivityAsk.site(source).spelled());
            cleanUp.push(() -> ShareLinks.revokeAt(operator, TrafficTvLinkLevel.LEVEL, ControllerIdentity.recordId(),
                each -> each.id().equals(minted.link().id())));
            FakeChannelLink<Object, LiveHits> link = new FakeChannelLink<>(HohenheimChannels.TRAFFIC_LIVE,
                "traffic-tv-holder");
            cleanUp.push(link::close);

            // 1. A wrong token, and a token of another kind of link, are refused alike.
            for (String token : List.of("znsl_nothing", "nonsense")) {
                Throwable refused = catchThrowable(() -> new TrafficActivityHandler(link)
                    .onOpen(ActivityAsk.tv(token).spelled()));
                assertThat(ChannelException.reasonOf(refused)).as("step 1: " + token)
                    .isEqualTo(ZenitRefusalReason.PERMISSION_DENIED);
            }

            // 2. The link of one site, held by nobody signed in: that site's hits, never an address, each visitor
            //    told apart by a mark; nothing of the other site.
            TrafficActivityHandler handler = new TrafficActivityHandler(link);
            handler.onOpen(ActivityAsk.tv(minted.token()).spelled());
            fixture.request(fixture.siteA, "/mine");
            fixture.request(fixture.siteB, "/theirs");
            link.awaitSubmitted(frame -> frame.sources().stream().anyMatch(each -> !each.hits().isEmpty()), 1, WAIT);
            for (LiveHits frame : link.submitted()) {
                assertThat(frame.sources()).as("step 2: only its site").extracting(LiveSource::source)
                    .containsOnly(source);
                for (LiveSource each : frame.sources()) {
                    assertThat(each.hits()).as("step 2: no address").extracting(LiveHit::visitor).containsOnlyNulls();
                    assertThat(each.hits()).as("step 2: never the other site").extracting(LiveHit::path)
                        .doesNotContain("/theirs");
                }
            }
            assertThat(TrafficTvLinkLevel.live(minted.link().id())).as("step 2: live while the link is").isTrue();

            // 3. Ended, the link is asked again and refused.
            ShareLinks.revokeAt(operator, TrafficTvLinkLevel.LEVEL, ControllerIdentity.recordId(),
                each -> each.id().equals(minted.link().id()));
            assertThat(TrafficTvLinkLevel.live(minted.link().id())).as("step 3: ended with its link").isFalse();
            assertThat(ShareLinks.bearer(minted.link().id())).as("step 3: its token opens nothing").isNull();
        } finally {
            while (!cleanUp.isEmpty()) cleanUp.pop().run();
        }
    }

    @Test
    void anAssistantMakesAndEndsTvLinksAndACustomerIsRefused() throws Exception {
        Deque<Runnable> cleanUp = new ArrayDeque<>();
        try (SiteAccessLogJourneyTest.Fixture fixture = SiteAccessLogJourneyTest.Fixture.start("traffic-tv-tool")) {
            URI endpoint = URI.create(baseUrl() + "/zn/mcp/" + HohenheimMcpModule.SERVER_NAME);
            McpHostServer server = Objects.requireNonNull(McpHostServers.routed(HohenheimMcpModule.SERVER_NAME));
            UUID adminId = TenantConduits.operatorUser().get(UserModel.ID);
            McpHttpSession admin = HohenheimMcpTest.presetSession(server, endpoint, adminId, "Test Admin");
            String siteCode = HohenheimMcpTest.code(SiteModel.MODEL_ID, fixture.siteA, TenantConduits.operator());
            String activity = CmsRoutes.list(HohenheimSlugs.ADMIN, HohenheimSlugs.TRAFFIC_ACTIVITY).toUrl();

            // 1. A link of one site: answered by its code and where its address waits, never the address.
            String request = UUID.randomUUID().toString();
            McpHttpSession.Answer made = admin.call("tv_link_set", Map.of("site", siteCode, "request", request));
            assertThat(made.error()).as("step 1: " + made.text()).isFalse();
            assertThat(made.text()).as("step 1: where the address waits").contains("Activity tab")
                .doesNotContain("znsl_").doesNotContain("/tv/");
            String code = HohenheimAppPartToolsTest.codeIn(made.text(), "SL");
            UUID linkId = UUID.fromString(Objects.requireNonNull(RecordCodes.keyOfCode(ShareLinkModel.MODEL_ID, code)));
            cleanUp.push(() -> ShareLinks.revokeAt(TenantConduits.operator(), TrafficTvLinkLevel.LEVEL,
                ControllerIdentity.recordId(), each -> each.id().equals(linkId)));
            ShareLink stored = Objects.requireNonNull(ShareLinks.find(linkId));
            assertThat(stored.scope()).as("step 1: it shows the one site")
                .isEqualTo(ActivityAsk.site(HohenheimTraffic.sourceOf(fixture.siteA)).spelled());
            McpHttpSession.Answer again = admin.call("tv_link_set", Map.of("site", siteCode, "request", request));
            assertThat(again.text()).as("step 1: the same request answers the first result").contains(code);
            assertThat(ShareLinks.listAt(TrafficTvLinkLevel.LEVEL, ControllerIdentity.recordId()))
                .as("step 1: made once").filteredOn(each -> each.scope() != null
                    && each.scope().equals(stored.scope())).hasSize(1);

            // 2. The address waits on the Activity tab for the account behind the call, shown once.
            HttpResponse<String> tab = adminGet(activity);
            assertThat(ADDRESS.matcher(tab.body()).find()).as("step 2: the address on the tab").isTrue();
            assertThat(ADDRESS.matcher(adminGet(activity).body()).find()).as("step 2: once").isFalse();
            // The list names the link by its code, what it shows and who made it; neither it nor the read says the
            // address.
            String siteName = String.valueOf((Object) Objects.requireNonNull(Models.get(SiteModel.class)
                .findById(fixture.siteA)).get(SiteModel.NAME));
            String listed = admin.call("list", Map.of("kind", "share_link")).text();
            assertThat(listed).as("step 2: listed").contains(code).contains(siteName + ", made by")
                .doesNotContain("znsl_").doesNotContain("/tv/");
            String read = admin.call("read", Map.of("codes", List.of(code))).text();
            assertThat(read).as("step 2: read").contains(code).contains(siteName)
                .doesNotContain("znsl_").doesNotContain("/tv/");

            // 3. A wrong call is refused by the argument it names.
            McpHttpSession.Answer nowhere = admin.call("tv_link_set", Map.of("site", "S999999"));
            assertThat(nowhere.error()).as("step 3: " + nowhere.text()).isTrue();
            assertThat(nowhere.text()).as("step 3: the refusal names the code it could not read").contains("S999999");
            McpHttpSession.Answer nameless = admin.call("tv_link_end", Map.of());
            assertThat(nameless.error()).as("step 3: " + nameless.text()).isTrue();
            assertThat(nameless.text()).as("step 3: the refusal names it").contains("code");

            // 4. A customer managing the site is refused both, and the link stays open.
            UUID customer = ApiSupport.user("traffic-tv-" + fixture.tag + "@hohenheim.local", "Tv customer");
            cleanUp.push(() -> Models.get(UserModel.class).delete(customer));
            RecordGrants.grant(GrantSubjectType.USER, customer, SiteModel.MODEL_ID, fixture.siteA,
                HohenheimCapabilities.MANAGE, true);
            McpHttpSession his = HohenheimMcpTest.presetSession(server, endpoint, customer, "Tv customer");
            assertThat(his.call("tv_link_set", Map.of("site", siteCode)).error()).as("step 4: no link of his").isTrue();
            assertThat(his.call("tv_link_end", Map.of("code", code)).error()).as("step 4: nor an end").isTrue();
            assertThat(ShareLinks.bearer(linkId)).as("step 4: the link stays open").isNotNull();

            // 5. The administrator ends it: its address opens nothing, and ending it again is refused.
            McpHttpSession.Answer ended = admin.call("tv_link_end", Map.of("code", code));
            assertThat(ended.error()).as("step 5: " + ended.text()).isFalse();
            assertThat(ShareLinks.bearer(linkId)).as("step 5: ended").isNull();
            assertThat(admin.call("tv_link_end", Map.of("code", code)).error()).as("step 5: once").isTrue();
            assertThat(admin.call("list", Map.of("kind", "share_link")).text()).as("step 5: no longer listed")
                .doesNotContain(code);
        } finally {
            while (!cleanUp.isEmpty()) cleanUp.pop().run();
        }
    }

    /** @return the requests the page's line counts once it counts at least {@code least} */
    private int awaitLined(Page tv, int least) {
        tv.waitForFunction("least => { const m = /(\\d+) requests? in the last minute/.exec(document.querySelector("
            + "'[data-hh-activity-line]').textContent); return m !== null && Number(m[1]) >= least; }", least,
            new Page.WaitForFunctionOptions().setTimeout(WAIT.toMillis()));
        Matcher counted = Pattern.compile("(\\d+) requests? in the last minute")
            .matcher(tv.locator("[data-hh-activity-line]").textContent());
        assertThat(counted.find()).as("the line counts the minute's requests").isTrue();
        return Integer.parseInt(counted.group(1));
    }

    /** @return the TV picture's type size in CSS pixels */
    private double typeOf(Page tv) {
        return ((Number) tv.evaluate("() => parseFloat(getComputedStyle(document.querySelector('" + SURFACE
            + "')).fontSize)")).doubleValue();
    }
}
