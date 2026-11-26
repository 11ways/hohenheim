package be.elevenways.hohenheim.traffic;

import be.elevenways.hohenheim.traffic.ActivityModel.Flight;
import be.elevenways.hohenheim.traffic.ActivityModel.Origin;
import be.elevenways.hohenheim.traffic.ActivityModel.OriginKind;
import be.elevenways.hohenheim.traffic.ActivityModel.SiteKind;
import be.elevenways.hohenheim.traffic.ActivityModel.SiteRow;
import be.elevenways.zenit.traffic.common.LiveHit;
import be.elevenways.zenit.traffic.common.LiveHits;
import be.elevenways.zenit.traffic.common.LiveSource;
import be.elevenways.zenit.traffic.common.StatusClass;
import be.elevenways.zenit3d.common.surface.SurfaceOptions;
import be.elevenways.zenit3d.common.text.TextStyle;
import be.elevenways.zenit3d.server.OffscreenSurface;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The live Activity picture's model and driver: which row a hit's dot leaves from and flies to, the frame bound said
 * as not drawn, a missed frame read as a second not heard (never as a quiet one), and reduced motion drawing the bars
 * of the minute with nothing flying.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class ActivityModelTest {

    private static final String UNSERVED = "unserved";
    private static final long SECOND = 1_800_000_000L;

    @Test
    void eachDotLeavesItsVisitorsRowAndFliesToItsSitesRow() {
        ActivityModel model = new ActivityModel(true, UNSERVED, List.of("1", "2"));

        // 1. Before any frame: a row for each site the viewer may see, the kinds of visitor rows, nothing heard.
        assertEquals(List.of(new SiteRow(SiteKind.SITE, "1"), new SiteRow(SiteKind.SITE, "2")), model.sites(),
            "step 1: the viewer's sites stand ready");
        assertEquals(Long.MIN_VALUE, model.latest(), "step 1: nothing heard yet");

        // 2. One second: site 2 answers a person three times and a bot and a script once; site 1 refuses another
        //    person; the unserved names are asked once.
        List<LiveHit> two = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            two.add(hit(SECOND * 1000 + 100 * i, "/a", 200, "chrome", "203.0.113.1"));
        }
        two.add(hit(SECOND * 1000 + 400, "/robots.txt", 200, "googlebot", "66.249.66.1"));
        two.add(hit(SECOND * 1000 + 500, "/health", 200, "curl", "198.51.100.9"));
        model.accept(new LiveHits(SECOND, List.of(source("2", two, two.size()),
            source("1", List.of(hit(SECOND * 1000 + 900, "/x", 404, "firefox", "203.0.113.2")), 1),
            source(UNSERVED, List.of(hit(SECOND * 1000, "/", 404, "curl", "192.0.2.4")), 1))));

        List<Origin> origins = model.origins();
        assertEquals(List.of(new Origin(OriginKind.ADDRESS, "203.0.113.1"), new Origin(OriginKind.ADDRESS, "203.0.113.2"),
            new Origin(OriginKind.BOTS, null), new Origin(OriginKind.TOOLS, null), new Origin(OriginKind.OTHERS, null)),
            origins, "step 2: the busiest visitors by address, then bots, scripts and everyone else");
        assertEquals(List.of(new SiteRow(SiteKind.SITE, "2"), new SiteRow(SiteKind.SITE, "1"),
            new SiteRow(SiteKind.UNSERVED, UNSERVED)), model.sites(), "step 2: the busier site first, then the "
            + "names nobody serves");

        List<Flight> flights = model.takeBirths();
        assertEquals(7, flights.size(), "step 2: a dot for every hit the frame carries");
        assertEquals(new Flight(0, 0, StatusClass.SUCCESS, 0), flights.get(0), "step 2: the person to site 2");
        assertEquals(new Flight(0, 0, StatusClass.SUCCESS, 200), flights.get(2),
            "step 2: born where in its second the hit was recorded");
        assertEquals(new Flight(2, 0, StatusClass.SUCCESS, 400), flights.get(3), "step 2: the bot from its row");
        assertEquals(new Flight(3, 0, StatusClass.SUCCESS, 500), flights.get(4), "step 2: the script from its row");
        assertEquals(new Flight(1, 1, StatusClass.CLIENT_ERROR, 900), flights.get(5),
            "step 2: the refused person to site 1, in the refused class");
        assertEquals(new Flight(3, 2, StatusClass.CLIENT_ERROR, 0), flights.get(6),
            "step 2: a name nobody serves to its own row");
        assertTrue(model.takeBirths().isEmpty(), "step 2: a dot is handed over once");

        // 3. The figures of the minute, the busiest path per site and the bot's name.
        assertEquals(7, model.requests(), "step 3: every request of the minute");
        assertEquals(2, model.count(StatusClass.CLIENT_ERROR), "step 3: the refused ones");
        assertEquals("/a", model.busiestPath("2"), "step 3: site 2's busiest path");
        assertEquals(List.of("Googlebot"), model.families(OriginKind.BOTS), "step 3: the bot by its family's name");
        assertEquals(5, model.requests(model.sites().get(0)), "step 3: a site row's rate is its requests");
    }

    @Test
    void theFrameBoundIsSaidAsNotDrawnAndAMissedFrameIsAGap() {
        ActivityModel model = new ActivityModel(false, UNSERVED, List.of("1"));

        // 1. A busy second: 200 requests, 60 of them carried.
        List<LiveHit> carried = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            carried.add(hit(SECOND * 1000 + i, "/busy", 200, "chrome", null));
        }
        model.accept(new LiveHits(SECOND, List.of(source("1", carried, 200))));
        assertEquals(200, model.requests(), "step 1: the counts are the whole second");
        assertEquals(140, model.notDrawn(), "step 1: what the frame left out is said, never drawn");
        assertEquals(60, model.takeBirths().size(), "step 1: a dot for every carried hit");
        assertEquals(new Origin(OriginKind.OTHERS, null), model.origins().getFirst(),
            "step 1: without addresses every person is one row");

        // 2. The next frame comes two seconds later: the second between was not heard, never a quiet second.
        model.accept(new LiveHits(SECOND + 2, List.of(source("1", List.of(), 0))));
        assertEquals(1, model.missed(), "step 2: one second not heard");
        List<long[]> seconds = model.perSecond();
        assertEquals(ActivityModel.WINDOW_SECONDS, seconds.size(), "step 2: a minute of seconds");
        assertNotNull(seconds.get(seconds.size() - 1), "step 2: a quiet second that was heard is a bar of its own");
        assertEquals(0, sum(seconds.get(seconds.size() - 1)), "step 2: and it says zero");
        assertNull(seconds.get(seconds.size() - 2), "step 2: the missed second is a gap");
        assertEquals(200, sum(seconds.get(seconds.size() - 3)), "step 2: the busy second stands as it was");
        assertEquals(200, model.requests(), "step 2: a gap adds nothing to the minute");

        // 3. A minute later the busy second has left the window.
        model.accept(new LiveHits(SECOND + 2 + ActivityModel.WINDOW_SECONDS, List.of(source("1", List.of(), 0))));
        assertEquals(0, model.requests(), "step 3: the minute is the last minute only");
    }

    @Test
    void underReducedMotionNothingFliesAndTheBarsStandStill() {
        ActivityModel model = new ActivityModel(true, UNSERVED, List.of("1"));
        ActivityDriver driver = new ActivityDriver(model, (copy, count) -> copy.key() + (count == null ? "" : count),
            new ActivityDriver.Colours(0x202020FF, 0x404040FF, 0x303030FF, 0xFFFFFFFF, 0xAAAAAAFF,
                List.of(1, 2, 3, 4, 5, 6)), TextStyle.DEFAULT, Map.of("1", "site.test"), false);
        OffscreenSurface surface = new OffscreenSurface(320, 200, SurfaceOptions.TRANSPARENT);
        driver.attach(surface);
        try {
            // 1. Motion allowed: a frame's hits fly as dots.
            driver.offer(new LiveHits(SECOND, List.of(source("1",
                List.of(hit(SECOND * 1000, "/", 200, "chrome", "203.0.113.1")), 1))));
            surface.setFrameMs(1_000);
            driver.frame(surface);
            assertEquals(1, driver.frames(), "step 1: the frame is drawn");
            surface.setFrameMs(1_500);
            driver.frame(surface);
            assertEquals(2, driver.frames(), "step 1: a flying dot draws every frame");

            // 2. Reduced motion: the next hits fly nowhere; the minute's bars are drawn once and the picture rests.
            surface.setReducedMotion(true);
            driver.offer(new LiveHits(SECOND + 1, List.of(source("1",
                List.of(hit((SECOND + 1) * 1000, "/", 500, "chrome", "203.0.113.1")), 1))));
            surface.setFrameMs(2_000);
            driver.frame(surface);
            assertEquals(3, driver.frames(), "step 2: the still picture is drawn");
            assertTrue(model.takeBirths().isEmpty(), "step 2: nothing waits to fly");
            // A label made in a frame is painted as the next one begins (TextSheet), which draws it once more.
            surface.setFrameMs(2_050);
            driver.frame(surface);
            assertEquals(4, driver.frames(), "step 2: the bars' heading is painted");
            surface.setFrameMs(2_100);
            driver.frame(surface);
            assertEquals(4, driver.frames(), "step 2: nothing moves, so the picture rests");
            assertEquals(2_000, surface.rested(), "step 2: a still picture looks again after STILL_MS");

            // 3. Paused: what arrives is counted, never flown, and the picture stands as it was.
            surface.setReducedMotion(false);
            driver.frame(surface);
            int drawn = driver.frames();
            driver.pause(true);
            driver.frame(surface);
            driver.offer(new LiveHits(SECOND + 2, List.of(source("1",
                List.of(hit((SECOND + 2) * 1000, "/", 200, "chrome", "203.0.113.1")), 1))));
            driver.frame(surface);
            assertEquals(drawn + 1, driver.frames(), "step 3: paused, the picture is drawn once and then stands");
            assertEquals(3, model.requests(), "step 3: the paused second is still counted");
            assertTrue(driver.paused() && model.takeBirths().isEmpty(), "step 3: nothing waits to fly");
        } finally {
            driver.detach();
            surface.dispose();
        }
    }

    @Test
    void theLegendNamesEveryStatusClassOnce() {
        assertEquals(EnumSet.allOf(StatusClass.class), EnumSet.copyOf(ActivityPalette.LEGEND),
            "every class has a colour and words");
        assertEquals(StatusClass.values().length, ActivityPalette.LEGEND.size(), "each once");
    }

    private static LiveHit hit(long at, String path, int status, String agent, String visitor) {
        return new LiveHit(at, path, status, 100, agent, visitor);
    }

    private static LiveSource source(String source, List<LiveHit> hits, long requests) {
        List<Long> classes = new ArrayList<>();
        for (StatusClass statusClass : StatusClass.values()) {
            long count = 0;
            for (LiveHit hit : hits) {
                if (hit.statusClass() == statusClass) count++;
            }
            classes.add(statusClass == StatusClass.SUCCESS ? count + requests - hits.size() : count);
        }
        return new LiveSource(source, requests, requests * 100, classes, hits, List.of(), 0);
    }

    private static long sum(long[] counts) {
        long sum = 0;
        for (long count : counts) sum += count;
        return sum;
    }
}
