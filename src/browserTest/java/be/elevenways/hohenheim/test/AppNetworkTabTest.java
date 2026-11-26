package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.traffic.AppCounters;
import be.elevenways.hohenheim.server.traffic.HohenheimTraffic;
import be.elevenways.protoblast.common.i18n.wording.ByteUnit;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.time.OfficeZone;
import be.elevenways.zenit.traffic.common.TrafficHit;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An app's Network tab as the board draws it: what the app received and sent over the span and what its sites answered
 * visitors as figures, received and sent per step as a chart, its site with the requests it brought; the span switch
 * reads another span; an app with nothing counted says what will appear and when; a customer who manages the app
 * reads it on /manage and is refused another's.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class AppNetworkTabTest extends HohenheimTestBase {

    private static final String FIGURE = "[data-hh-app-network-figure='%s']";

    @Test
    void anAppManagerReadsWhatItsAppReceivedAndSentOverTime() throws Exception {
        String tag = HohenheimMcpTest.letters();
        Deque<Runnable> cleanUp = new ArrayDeque<>();
        try {
            TrafficCountingJourneyTest.countUnder(cleanUp, "app-tab-" + tag);
            int app = AppNetworkJourneyTest.application(cleanUp, "network-tab-" + tag);
            int quiet = AppNetworkJourneyTest.application(cleanUp, "network-quiet-" + tag);
            long at = Now.instant().toEpochMilli();
            long megabyte = ByteUnit.MEGABYTE.factor();
            AppCounters.observe(app, app, AppNetworkJourneyTest.reading(at, 0, 0));
            AppCounters.observe(app, app, AppNetworkJourneyTest.reading(at + 1_000, 3 * megabyte, megabyte));
            Row site = ProxyTestSupport.setupInstanceSite("Network tab " + tag, "network-tab-" + tag, app);
            int siteId = site.get(SiteModel.ID);
            cleanUp.push(() -> HardDeletes.byId(Models.get(SiteModel.class), siteId));
            Objects.requireNonNull(HohenheimTraffic.store(HohenheimTraffic.Counted.WEB)).record(new TrafficHit(
                Now.instant(), HohenheimTraffic.sourceOf(siteId), "network.test", "/", "GET", 200, 2_048, 10, 1_000,
                "127.0.0.1", null, null));

            // 1. The tab draws the day per hour: the figures in sizes, received and sent as two lines, each hour named
            //    apart (the day's first hour and the same hour a day later too), the site with the request it brought
            //    and what it sent visitors.
            page.setViewportSize(1280, 900);
            navigateToApp(tab(HohenheimSlugs.ADMIN, app));
            waitForHydration();
            assertThat(figure("received")).as("step 1: received").contains("Received").contains("3 MB");
            assertThat(figure("sent")).as("step 1: sent").contains("Sent").contains("1 MB");
            assertThat(figure("to_visitors")).as("step 1: through the proxy").contains("2 KB");
            assertThat(page.locator("[data-hh-app-network-chart] pl-chart path.series-line").count())
                .as("step 1: received and sent, two lines").isEqualTo(2);
            assertThat(page.locator("[data-hh-app-network-chart] pl-chart .legend").textContent())
                .as("step 1: the legend names them").contains("Received").contains("Sent");
            assertThat(page.locator("[data-hh-app-network-chart] pl-card-description").textContent())
                .as("step 1: how the chart counts").contains("Per hour, in MB");
            assertThat(TrafficScreensJourneyTest.pointNames(page, "[data-hh-app-network-chart]"))
                .as("step 1: every hour named apart").hasSizeGreaterThan(23).doesNotHaveDuplicates();
            String row = page.locator("[data-hh-app-network-site='" + siteId + "']").textContent();
            assertThat(row).as("step 1: the site's line").contains("Network tab " + tag).contains("2 KB");
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("build/app-network-tab.png"))
                .setFullPage(true));

            // 2. The span switch reads the hour, per minute, and the week, per day, each day by its day before its
            //    month.
            page.locator("[data-hh-app-network-span='hour']").click();
            page.waitForCondition(() -> page.url().contains("period=hour"));
            page.waitForCondition(() -> page.locator("[data-hh-app-network-chart] pl-card-description").textContent()
                .contains("Per minute"));
            assertThat(figure("received")).as("step 2: the hour holds it too").contains("3 MB");
            page.locator("[data-hh-app-network-span='week']").click();
            page.waitForCondition(() -> page.url().contains("period=week"));
            page.waitForCondition(() -> page.locator("[data-hh-app-network-chart] pl-card-description").textContent()
                .contains("Per day"));
            List<String> days = TrafficScreensJourneyTest.pointNames(page, "[data-hh-app-network-chart]");
            assertThat(days).as("step 2: every day named apart").hasSizeGreaterThan(6).doesNotHaveDuplicates();
            assertThat(days.getLast()).as("step 2: today, its day first")
                .matches("0?" + Now.instant().atZone(OfficeZone.office()).getDayOfMonth() + "\\D.*");

            // 3. An app nothing was counted for says what will appear and when, and that no site points at it.
            navigateToApp(tab(HohenheimSlugs.ADMIN, quiet));
            waitForHydration();
            assertThat(page.locator("[data-hh-app-network-empty]").textContent())
                .as("step 3: what will appear and when").contains("within 5 minutes");
            assertThat(page.locator("[data-hh-app-network-no-sites]").count()).as("step 3: no site").isEqualTo(1);

            // 4. A customer who manages the app reads its tab on /manage; another app's tab is refused him.
            UUID customer = ApiSupport.user("network-tab-" + tag + "@hohenheim.local", "Network tab customer");
            cleanUp.push(() -> Models.get(UserModel.class).delete(customer));
            RecordGrants.grant(GrantSubjectType.USER, customer, InstanceModel.MODEL_ID, app,
                HohenheimCapabilities.MANAGE, true);
            String session = sessionFor(customer).token();
            navigateToAppAs(tab(HohenheimSlugs.MANAGE, app), session);
            waitForHydration();
            assertThat(figure("received")).as("step 4: his app's figures").contains("3 MB");
            HttpResponse<String> foreign = httpGet(tab(HohenheimSlugs.MANAGE, quiet), session);
            assertThat(foreign.statusCode()).as("step 4: another app's tab is no page of his").isIn(403, 404);
            assertThat(foreign.body()).as("step 4: and shows nothing of it").doesNotContain("network-quiet-" + tag);
        } finally {
            while (!cleanUp.isEmpty()) cleanUp.pop().run();
        }
    }

    private String figure(String key) {
        return page.locator(String.format(FIGURE, key)).textContent();
    }

    private static String tab(String panel, int app) {
        return CmsRoutes.subpage(panel, HohenheimSlugs.INSTANCES, app, HohenheimSlugs.Tab.NETWORK).toUrl();
    }
}
