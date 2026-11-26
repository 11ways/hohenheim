package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.common.Zenit;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An app's Network tab draws where the app sends data as the board does: each destination with its address, port and
 * protocol, what it sent and received, its connections and when last seen, the database link as an internal line;
 * an app with nothing counted says what appears and when, and the card says so while recording is off.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class AppDestinationsCardTest extends HohenheimTestBase {

    @Test
    void theNetworkTabDrawsWhereTheAppSendsDataAsTheBoardDoes() throws Exception {
        String tag = HohenheimMcpTest.letters();
        Deque<Runnable> cleanUp = new ArrayDeque<>();
        try {
            TrafficCountingJourneyTest.countUnder(cleanUp, "destinations-card-" + tag);
            int app = AppNetworkJourneyTest.application(cleanUp, "card-sender-" + tag);
            int neighbour = AppNetworkJourneyTest.application(cleanUp, "card-neighbour-" + tag);
            int quiet = AppNetworkJourneyTest.application(cleanUp, "card-quiet-" + tag);
            AppDestinationsJourneyTest.counted(cleanUp, app, neighbour);

            // 1. THE ASSERTION: the card lists each destination as the board draws it; the database link is an
            //    internal line that says so.
            page.setViewportSize(1280, 900);
            navigateToApp(tab(app));
            waitForHydration();
            assertThat(page.locator("[data-hh-app-network-destinations] pl-card-title").textContent())
                .as("step 1: the board's title").contains("Where it sends data");
            assertThat(page.locator("[data-hh-app-network-destination]").count()).as("step 1: four destinations")
                .isEqualTo(4);
            assertThat(row("internet||93.184.216.34|tcp|443")).as("step 1: the internet by address")
                .contains("93.184.216.34").contains("443/tcp").contains("by address");
            String database = "database|" + AppDestinationsJourneyTest.DATABASE + "|172.31.9.3|tcp|5432";
            assertThat(row(database)).as("step 1: the database link").contains("Its database")
                .contains("database link, internal").contains("5432/tcp");
            assertThat(page.locator(selector(database)).getAttribute("data-hh-internal")).as("step 1: internal")
                .isEqualTo("true");
            assertThat(page.locator(selector("internet||93.184.216.34|tcp|443")).getAttribute("data-hh-internal"))
                .as("step 1: the internet is not").isNull();
            assertThat(row("app|" + neighbour + "|" + AppDestinationsJourneyTest.NEIGHBOUR_ADDRESS + "|tcp|8080"))
                .as("step 1: the other app by name").contains("card-neighbour-" + tag).contains("app on this server");
            assertThat(row("proxy||203.0.113.10|tcp|443")).as("step 1: the proxy").contains("Hohenheim's proxy");
            page.locator("[data-hh-app-network-destinations]").screenshot(new Locator.ScreenshotOptions()
                .setPath(Path.of("build/app-network-destinations.png")));
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("build/app-network-destinations-tab.png"))
                .setFullPage(true));

            // 2. An app nothing was counted for says what appears and when.
            navigateToApp(tab(quiet));
            waitForHydration();
            assertThat(page.locator("[data-hh-app-network-no-destinations]").textContent())
                .as("step 2: what appears and when").contains("once a minute");

            // 3. Recording switched off: the card says so and where it is switched on.
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Traffic.RECORD_DESTINATIONS, false);
            cleanUp.push(() -> Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Traffic.RECORD_DESTINATIONS, true));
            navigateToApp(tab(quiet));
            waitForHydration();
            assertThat(page.locator("[data-hh-app-network-destinations-off]").textContent())
                .as("step 3: off, and where to switch it on").contains("does not record").contains("Traffic");
        } finally {
            while (!cleanUp.isEmpty()) cleanUp.pop().run();
        }
    }

    private String row(String key) {
        return page.locator(selector(key)).textContent();
    }

    private static String selector(String key) {
        return "[data-hh-app-network-destination='" + key + "']";
    }

    private static String tab(int app) {
        return CmsRoutes.subpage(HohenheimSlugs.ADMIN, HohenheimSlugs.INSTANCES, app, HohenheimSlugs.Tab.NETWORK)
            .toUrl();
    }
}
