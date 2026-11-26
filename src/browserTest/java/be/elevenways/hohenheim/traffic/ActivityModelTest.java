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
 * The live Activity picture's model and driver: which row a hit's dot leaves from and flies to, a new visitor's row at
 * once, the frame bound said as not drawn, a missed frame read as a second not heard (never as a quiet one), the page
 * hearing a minute only once the picture drew it, and reduced motion drawing the rows and the bars of the minute with
 * nothing flying.
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
        assertEquals(new Flight(0, 0, StatusClass.SUCCESS, 0, 0f, "/a"), flights.get(0), "step 2: the person to site 2");
        assertEquals(new Flight(0, 0, StatusClass.SUCCESS, 200, 0f, "/a"), flights.get(2),
            "step 2: born where in its second the hit was recorded, a tenth of a second apart: on the guide");
        assertEquals(new Flight(2, 0, StatusClass.SUCCESS, 400, 0f, "/robots.txt"), flights.get(3),
            "step 2: the bot from its row");
        assertEquals(new Flight(3, 0, StatusClass.SUCCESS, 500, 0f, "/health"), flights.get(4),
            "step 2: the script from its row");
        assertEquals(new Flight(1, 1, StatusClass.CLIENT_ERROR, 900, 0f, "/x"), flights.get(5),
            "step 2: the refused person to site 1, in the refused class");
        assertEquals(new Flight(3, 2, StatusClass.CLIENT_ERROR, 0, 0f, "/"), flights.get(6),
            "step 2: a name nobody serves to its own row");
        assertTrue(model.takeBirths().isEmpty(), "step 2: a dot is handed over once");

        // 3. The figures of the minute, the busiest path per site and the bot's name.
        assertEquals(7, model.requests(), "step 3: every request of the minute");
        assertEquals(2, model.count(StatusClass.CLIENT_ERROR), "step 3: the refused ones");
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
        Origin first = model.origins().getFirst();
        assertEquals(new Origin(OriginKind.ADDRESS, "#7"), first,
            "step 1: without addresses a person's row is named by the mark of their visitor");
        assertEquals(1, model.number(first), "step 1: and worded as a number, Visitor 1");

        // 2. The next frame comes two seconds later: the second between was not heard, never a quiet second.
        model.accept(new LiveHits(SECOND + 2, List.of(source("1", List.of(), 0))));
        assertEquals(1, model.missed(), "step 2: one second not heard");
        List<long[]> seconds = model.perSecond();
        assertEquals(3, seconds.size(), "step 2: the seconds since the first frame, none before it");
        assertNotNull(seconds.get(2), "step 2: a quiet second that was heard is a bar of its own");
        assertEquals(0, sum(seconds.get(2)), "step 2: and it says zero");
        assertNull(seconds.get(1), "step 2: the missed second is a gap");
        assertEquals(200, sum(seconds.get(0)), "step 2: the busy second stands as it was");
        assertEquals(200, model.requests(), "step 2: a gap adds nothing to the minute");

        // 3. A minute later the busy second has left the window.
        model.accept(new LiveHits(SECOND + 2 + ActivityModel.WINDOW_SECONDS, List.of(source("1", List.of(), 0))));
        assertEquals(0, model.requests(), "step 3: the minute is the last minute only");
        assertEquals(ActivityModel.WINDOW_SECONDS, model.perSecond().size(), "step 3: a whole minute of seconds");
    }

    @Test
    void thePageHearsAMinuteOnlyOnceThePictureDrewItAndANewVisitorGetsARowAtOnce() {
        ActivityModel model = new ActivityModel(true, UNSERVED, List.of("1", "2"));
        List<String> minutes = new ArrayList<>();
        int[] flying = {-1};
        ActivityDriver driver = driver(model, minutes, flying);
        OffscreenSurface surface = new OffscreenSurface(640, 400, SurfaceOptions.TRANSPARENT);
        driver.attach(surface);
        try {
            // 1. A quiet first frame, drawn: the page hears one minute, and no visitor has a row.
            driver.offer(new LiveHits(SECOND, List.of(source("1", List.of(), 0))));
            surface.setFrameMs(1_000);
            driver.frame(surface);
            assertEquals(1, minutes.size(), "step 1: the drawn minute");
            assertTrue(model.origins().stream().noneMatch(origin -> origin.kind() == OriginKind.ADDRESS),
                "step 1: no visitor yet");

            // 2. Two seconds later, well inside the ranking interval, a person asks site 1 twice and site 2 once:
            //    their address gets a row at once, and the page hears nothing until the picture draws it.
            long at = (SECOND + 2) * 1000;
            driver.offer(new LiveHits(SECOND + 2, List.of(
                source("1", List.of(hit(at + 100, "/a", 200, "firefox", "203.0.113.7"),
                    hit(at + 300, "/b", 404, "firefox", "203.0.113.7")), 2),
                source("2", List.of(hit(at + 500, "/c", 200, "firefox", "203.0.113.7")), 1))));
            assertEquals(new Origin(OriginKind.ADDRESS, "203.0.113.7"), model.origins().getFirst(),
                "step 2: the new visitor's row, at once");
            assertEquals(1, minutes.size(), "step 2: nothing heard before the picture drew it");
            surface.setFrameMs(3_000);
            driver.frame(surface);
            assertEquals(2, minutes.size(), "step 2: the minute, after the draw that shows it");
            assertEquals("wactivity_picture", minutes.getLast(), "step 2: the picture in words (the journey reads them)");
            assertEquals(2, model.requests(model.sites().get(0)), "step 2: site 1's rate");
            assertEquals(1, model.requests(model.sites().get(1)), "step 2: site 2's rate");

            // 3. Each request a dot, born where in its second it was recorded: none yet at the draw, all three on
            //    their way 0.6 s on, all arrived 3.2 s on.
            assertEquals(0, flying[0], "step 3: none left yet");
            surface.setFrameMs(3_600);
            driver.frame(surface);
            assertEquals(3, flying[0], "step 3: all three on their way");
            assertEquals(2, minutes.size(), "step 3: a frame that shows nothing new tells the page nothing");
            surface.setFrameMs(6_200);
            driver.frame(surface);
            assertEquals(0, flying[0], "step 3: all arrived");

            // 4. Paused, the picture and the page both hold what they showed; resumed, both show the new minute.
            driver.pause(true);
            driver.frame(surface);
            int held = minutes.size();
            driver.offer(new LiveHits(SECOND + 3, List.of(source("1",
                List.of(hit((SECOND + 3) * 1000, "/", 200, "firefox", "203.0.113.7")), 1))));
            driver.frame(surface);
            assertEquals(held, minutes.size(), "step 4: paused, the page holds the minute the picture shows");
            driver.pause(false);
            driver.frame(surface);
            assertEquals(held + 1, minutes.size(), "step 4: resumed, the page hears the minute drawn");
            assertEquals(4, model.requests(), "step 4: and it counts the paused second");
        } finally {
            driver.detach();
            surface.dispose();
        }
    }

    @Test
    void underReducedMotionNothingFliesAndTheBarsStandStill() {
        ActivityModel model = new ActivityModel(true, UNSERVED, List.of("1"));
        ActivityDriver driver = driver(model, new ArrayList<>(), new int[1]);
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

            // 2. Reduced motion: the next hits fly nowhere; the rows and the minute's bars are drawn, and once its
            //    labels are painted (a frame's paints are bounded) the picture rests for STILL_MS.
            surface.setReducedMotion(true);
            driver.offer(new LiveHits(SECOND + 1, List.of(source("1",
                List.of(hit((SECOND + 1) * 1000, "/", 500, "chrome", "203.0.113.1")), 1))));
            surface.setFrameMs(2_000);
            driver.frame(surface);
            assertEquals(3, driver.frames(), "step 2: the still picture is drawn");
            assertTrue(model.takeBirths().isEmpty(), "step 2: nothing waits to fly");
            for (int i = 1; i <= 3; i++) {
                surface.setFrameMs(2_000 + 50 * i);
                driver.frame(surface);
            }
            int settled = driver.frames();
            surface.setFrameMs(2_300);
            driver.frame(surface);
            assertEquals(settled, driver.frames(), "step 2: nothing moves, so the picture rests");
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

    @Test
    void requestsOfOneMomentAreSeveralDotsUpToTheRowsBound() {
        ActivityModel model = new ActivityModel(true, UNSERVED, List.of("1", "2"));

        // 1. One visitor asks site 1 five times within a few milliseconds: five dots, the first on the guide, the
        //    others each off it across the row and a little later along the curve, no two in one place.
        List<LiveHit> moment = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            moment.add(hit(SECOND * 1000 + 300 + i, "/burst", 200, "firefox", "203.0.113.5"));
        }
        model.accept(new LiveHits(SECOND, List.of(source("1", moment, 5))));
        List<Flight> flights = model.takeBirths();
        assertEquals(5, flights.size(), "step 1: a dot for every request");
        assertEquals(0f, flights.get(0).lane(), "step 1: the first on the guide");
        assertEquals(300, flights.get(0).offsetMs(), "step 1: when it was recorded");
        List<String> places = new ArrayList<>();
        for (Flight flight : flights) {
            assertTrue(Math.abs(flight.lane()) <= ActivityModel.LANE_SPREAD, "step 1: within its row: " + flight);
            assertTrue(flight.offsetMs() >= 300 && flight.offsetMs() <= 304 + ActivityModel.SPREAD_MS,
                "step 1: at most a quarter second later: " + flight);
            places.add(flight.lane() + "@" + flight.offsetMs());
        }
        assertEquals(5, places.stream().distinct().count(), "step 1: five places: " + places);

        // 2. The next second site 1 is asked 70 times, all carried, and site 2 five times: site 1's row takes 60
        //    dots, the other ten are counted as not drawn; site 2's row has its own 60.
        List<LiveHit> many = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            many.add(hit((SECOND + 1) * 1000 + 10 * i, "/many", 200, "firefox", "203.0.113.5"));
        }
        List<LiveHit> other = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            other.add(hit((SECOND + 1) * 1000 + 100 * i, "/other", 200, "firefox", "203.0.113.6"));
        }
        model.accept(new LiveHits(SECOND + 1, List.of(source("1", many, 70), source("2", other, 5))));
        flights = model.takeBirths();
        assertEquals(60, flights.stream().filter(flight -> flight.site() == 0).count(), "step 2: site 1's bound");
        assertEquals(5, flights.stream().filter(flight -> flight.site() == 1).count(), "step 2: site 2's own");
        assertEquals(10, model.notDrawn(), "step 2: the rest counted, never drawn");
        assertEquals(75, model.requests(model.sites().get(0)), "step 2: and every request in the rate");

        // 3. Each site's busiest path of the minute, which a still picture shows beside the row.
        assertEquals("/many", model.busiestPath(model.sites().get(0)), "step 3: site 1's");
        assertEquals("/other", model.busiestPath(model.sites().get(1)), "step 3: site 2's");
    }

    @Test
    void anArrivalLightsItsSiteRowAndShowsThePathAskedMost() {
        ActivityModel model = new ActivityModel(true, UNSERVED, List.of("1"));
        ActivityDriver driver = driver(model, new ArrayList<>(), new int[1]);
        OffscreenSurface surface = new OffscreenSurface(640, 400, SurfaceOptions.TRANSPARENT);
        driver.attach(surface);
        try {
            // 1. A request flies: nothing glows and no path shows before it arrives.
            driver.offer(new LiveHits(SECOND, List.of(source("1",
                List.of(hit(SECOND * 1000, "/arrive", 200, "chrome", "203.0.113.1")), 1))));
            surface.setFrameMs(1_000);
            driver.frame(surface);
            surface.setFrameMs(2_500);
            driver.frame(surface);
            assertEquals(0f, driver.glow(0, surface), "step 1: on its way, no glow");
            assertNull(driver.shownPath(0, surface), "step 1: nor a path");

            // 2. The first frame after it arrived draws, though no dot flies any more: the row glows at once and
            //    its path shows; the glow is gone after PULSE_MS, the path after PATH_MS.
            int drawn = driver.frames();
            surface.setFrameMs(2_650);
            driver.frame(surface);
            assertEquals(drawn + 1, driver.frames(), "step 2: the arrival is drawn");
            assertEquals(1f, driver.glow(0, surface), "step 2: the row glows");
            assertEquals("/arrive", driver.shownPath(0, surface), "step 2: the path beside it");
            surface.setFrameMs(2_650 + ActivityDriver.PULSE_MS + 10);
            driver.frame(surface);
            assertEquals(0f, driver.glow(0, surface), "step 2: the glow is brief");
            assertEquals("/arrive", driver.shownPath(0, surface), "step 2: the path a moment longer");
            surface.setFrameMs(2_650 + ActivityDriver.PATH_MS + 10);
            driver.frame(surface);
            assertNull(driver.shownPath(0, surface), "step 2: then gone");

            // 3. Three arrive close together, /b twice then /a: the busiest path wins over the latest.
            driver.offer(new LiveHits(SECOND + 2, List.of(source("1", List.of(
                hit((SECOND + 2) * 1000, "/b", 200, "chrome", "203.0.113.1"),
                hit((SECOND + 2) * 1000 + 100, "/b", 200, "chrome", "203.0.113.1"),
                hit((SECOND + 2) * 1000 + 200, "/a", 404, "chrome", "203.0.113.1")), 3))));
            surface.setFrameMs(5_000);
            driver.frame(surface);
            surface.setFrameMs(6_750);
            driver.frame(surface);
            assertEquals("/b", driver.shownPath(0, surface), "step 3: two arrived, both /b");
            surface.setFrameMs(6_850);
            driver.frame(surface);
            assertEquals("/b", driver.shownPath(0, surface), "step 3: /a arrived last, /b was asked most");

            // 4. Reduced motion: nothing glows, no path fades in; a still picture shows the minute's busiest path.
            surface.setReducedMotion(true);
            surface.setFrameMs(7_000);
            driver.frame(surface);
            assertEquals(0f, driver.glow(0, surface), "step 4: no glow");
            assertNull(driver.shownPath(0, surface), "step 4: no arrival's path");
            assertEquals("/b", model.busiestPath(model.sites().get(0)), "step 4: the minute's busiest path");
        } finally {
            driver.detach();
            surface.dispose();
        }
    }

    @Test
    void theRowsFitTheirPictureAndFillAWallScreen() {
        ActivityModel model = new ActivityModel(true, UNSERVED, List.of("1", "2"));
        float[] fit = {-1f};
        ActivityDriver driver = new ActivityDriver(model, copy -> "w" + copy.key(), new ActivityDriver.Colours(
            0x202020FF, 0x404040FF, 0x303030FF, 0xFFFFFFFF, 0xAAAAAAFF, List.of(1, 2, 3, 4, 5, 6)), TextStyle.DEFAULT,
            Map.of("1", "site.test", "2", "two.test"), false, new ActivityDriver.Shown() {
                @Override
                public void minute(String picture) {
                }

                @Override
                public void flying(int dots) {
                }

                @Override
                public void fits(float heightPx) {
                    fit[0] = heightPx;
                }
            });
        OffscreenSurface surface = new OffscreenSurface(640, 900, SurfaceOptions.TRANSPARENT);
        driver.attach(surface);
        try {
            // 1. The longer side is the visitors' three rows (bots, scripts, everyone else): the picture fits the
            //    header and those rows with their gaps at their own size.
            driver.offer(new LiveHits(SECOND, List.of(source("1", List.of(), 0))));
            surface.setFrameMs(1_000);
            driver.frame(surface);
            float unit = Math.max(1f, TextStyle.DEFAULT.sizePx() / ActivityDriver.TYPE_PX);
            int rows = model.origins().size();
            assertEquals((ActivityDriver.HEAD + rows * ActivityDriver.ROW + (rows - 1) * ActivityDriver.GAP) * unit,
                fit[0], 0.01f, "step 1: the height the rows fit");

            // 2. Under reduced motion the minute's bars stand under the rows: the fit makes room for them.
            float moving = fit[0];
            surface.setReducedMotion(true);
            surface.setFrameMs(2_000);
            driver.frame(surface);
            assertEquals(moving / ActivityDriver.ROWS_SHARE, fit[0], 0.01f, "step 2: rows and bars");
        } finally {
            driver.detach();
            surface.dispose();
        }
    }

    @Test
    void theLegendNamesTheRareClassesOnlyWhileTheMinuteHoldsOne() {
        ActivityModel model = new ActivityModel(true, UNSERVED, List.of("1"));
        List<StatusClass> everyday = List.of(StatusClass.SUCCESS, StatusClass.REDIRECTION, StatusClass.CLIENT_ERROR,
            StatusClass.SERVER_ERROR);

        // 1. Before anything is heard, and after a second of ordinary answers: the four everyday classes alone.
        assertEquals(everyday, classesOf(ActivitySummary.of(model, null, false)), "step 1: nothing heard");
        model.accept(new LiveHits(SECOND, List.of(source("1", List.of(hit(SECOND * 1000, "/", 200, "chrome",
            "203.0.113.1")), 1))));
        assertEquals(everyday, classesOf(ActivitySummary.of(model, null, false)), "step 1: ordinary answers");

        // 2. A 101 joins its class, then a status outside 100 to 599 the other one, in the legend's order.
        model.accept(new LiveHits(SECOND + 1, List.of(source("1", List.of(hit((SECOND + 1) * 1000, "/ws", 101,
            "chrome", "203.0.113.1")), 1))));
        assertEquals(List.of(StatusClass.SUCCESS, StatusClass.REDIRECTION, StatusClass.CLIENT_ERROR,
            StatusClass.SERVER_ERROR, StatusClass.INFORMATIONAL), classesOf(ActivitySummary.of(model, null, false)),
            "step 2: the informational class while held");
        model.accept(new LiveHits(SECOND + 2, List.of(source("1", List.of(hit((SECOND + 2) * 1000, "/odd", 999,
            "chrome", "203.0.113.1")), 1))));
        assertEquals(ActivityPalette.LEGEND, classesOf(ActivitySummary.of(model, null, false)),
            "step 2: and the unknown statuses");

        // 3. Once the minute no longer holds them, the legend is the everyday four again.
        model.accept(new LiveHits(SECOND + 70, List.of(source("1", List.of(hit((SECOND + 70) * 1000, "/", 200,
            "chrome", "203.0.113.1")), 1))));
        assertEquals(everyday, classesOf(ActivitySummary.of(model, null, false)), "step 3: gone with the minute");
    }

    /** @return the classes a summary's legend names, by their words */
    private static List<StatusClass> classesOf(ActivitySummary summary) {
        List<StatusClass> named = new ArrayList<>();
        for (ActivityPalette.Entry entry : summary.legend()) {
            for (StatusClass statusClass : StatusClass.values()) {
                if (statusClass.label().equals(entry.label())) named.add(statusClass);
            }
        }
        return named;
    }

    /** @return a driver of the model wording each copy by its key, telling the minutes and dots it shows */
    private static ActivityDriver driver(ActivityModel model, List<String> minutes, int[] flying) {
        return new ActivityDriver(model, copy -> "w" + copy.key(), new ActivityDriver.Colours(0x202020FF, 0x404040FF,
            0x303030FF, 0xFFFFFFFF, 0xAAAAAAFF, List.of(1, 2, 3, 4, 5, 6)), TextStyle.DEFAULT,
            Map.of("1", "site.test", "2", "two.test"), false, new ActivityDriver.Shown() {
                @Override
                public void minute(String picture) {
                    minutes.add(picture);
                }

                @Override
                public void flying(int dots) {
                    flying[0] = dots;
                }

                @Override
                public void fits(float heightPx) {
                }
            });
    }

    private static LiveHit hit(long at, String path, int status, String agent, String visitor) {
        // A stand-in for the live seam's mark (an HMAC it alone holds): equal for equal visitors, 7 for none named.
        return new LiveHit(at, path, status, 100, agent, visitor == null ? 7 : visitor.hashCode(), visitor);
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
