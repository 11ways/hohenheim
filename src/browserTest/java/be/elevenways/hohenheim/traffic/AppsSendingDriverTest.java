package be.elevenways.hohenheim.traffic;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit3d.common.surface.SurfaceOptions;
import be.elevenways.zenit3d.common.text.TextStyle;
import be.elevenways.zenit3d.server.OffscreenSurface;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The "Apps sending" picture's driver: a reading's bands swell to its rates over the swell and no longer, a mark
 * travels each band, the page is told each reading once after the draw that shows it, and under reduced motion
 * nothing travels while the readings heard stand as bars under the rows.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class AppsSendingDriverTest {

    private static final Instant AT = Instant.parse("2026-11-27T10:00:00Z");

    @Test
    void aReadingsBandsSwellToItsRatesAndItsMarksTravelThem() {
        List<String> told = new ArrayList<>();
        int[] travelling = {-1};
        float[] fit = {-1f};
        AppsSendingDriver driver = new AppsSendingDriver(copy -> "w" + copy.key(), new AppsSendingDriver.Colours(
            new PictureInk(0x202020FF, 0x404040FF, 0x303030FF, 0xFFFFFFFF, 0xAAAAAAFF), Map.of("blue", 0x3366FFFF)),
            TextStyle.DEFAULT, new AppsSendingDriver.Shown() {
                @Override
                public void reading(String picture, String spoken) {
                    told.add(spoken);
                }

                @Override
                public void travelling(int marks) {
                    travelling[0] = marks;
                }

                @Override
                public void fits(float heightPx) {
                    fit[0] = heightPx;
                }
            });
        OffscreenSurface surface = new OffscreenSurface(900, 600, SurfaceOptions.TRANSPARENT);
        driver.attach(surface);
        try {
            // 1. Before any reading: nothing told, the rows of nothing fit their header and one row.
            surface.setFrameMs(1_000);
            driver.frame(surface);
            assertTrue(told.isEmpty(), "step 1: no reading, nothing told");

            // 2. A reading: two apps to two places. Its bands start thin and reach their rates at the swell's end;
            //    a mark travels each band; the page is told the reading once.
            driver.offer(reading(AT, 3_000, 1_000));
            surface.setFrameMs(2_000);
            driver.frame(surface);
            assertEquals(1, told.size(), "step 2: told once, after the draw that showed it");
            assertEquals(0f, driver.rate("1", "a", surface), 1f, "step 2: a new band starts from nothing");
            surface.setFrameMs(2_100);
            driver.frame(surface);
            assertEquals(3, travelling[0], "step 2: once the bands carry anything, a mark on each");
            surface.setFrameMs(2_000 + AppsSendingDriver.SWELL_MS / 2);
            driver.frame(surface);
            float halfway = driver.rate("1", "a", surface);
            assertTrue(halfway > 0f && halfway < 3_000f, "step 2: swelling halfway: " + halfway);
            surface.setFrameMs(2_000 + AppsSendingDriver.SWELL_MS + 1);
            driver.frame(surface);
            assertEquals(3_000f, driver.rate("1", "a", surface), 0.01f, "step 2: at its rate once swollen");
            assertEquals(1, told.size(), "step 2: the same reading is not told again");

            // 3. The next reading thins the band from where it stands; the page is told it.
            driver.offer(reading(AT.plusSeconds(60), 1_000, 1_000));
            surface.setFrameMs(5_000);
            driver.frame(surface);
            surface.setFrameMs(5_000 + AppsSendingDriver.SWELL_MS / 2);
            driver.frame(surface);
            float thinning = driver.rate("1", "a", surface);
            assertTrue(thinning > 1_000f && thinning < 3_000f, "step 3: thinning: " + thinning);
            assertEquals(2, told.size(), "step 3: the new reading told");

            // 4. Reduced motion: no mark travels, the bands stand at the reading, the two readings heard stand as
            //    bars under the rows, the picture grown to fit them.
            float moving = fit[0];
            surface.setReducedMotion(true);
            surface.setFrameMs(9_000);
            driver.frame(surface);
            assertEquals(0, travelling[0], "step 4: nothing travels");
            assertEquals(1_000f, driver.rate("1", "a", surface), 0.01f, "step 4: the band at its reading");
            assertEquals(2, driver.readingsKept(), "step 4: one bar per reading heard");
            assertEquals(moving / ActivityDriver.ROWS_SHARE, fit[0], 0.01f, "step 4: rows and bars");
        } finally {
            driver.detach();
            surface.dispose();
        }
    }

    /** @return a reading of app 1 sending to a and b, and app 2 to b */
    private static AppsSendingView reading(Instant at, long oneToA, long twoToB) {
        return new AppsSendingView(true, at, 60,
            List.of(new AppsSendingView.App("1", Microcopy.literal("api"), "blue", oneToA + 200, 0),
                new AppsSendingView.App("2", Microcopy.literal("worker"), "teal", twoToB, 0)),
            List.of(new AppsSendingView.Place("a", Microcopy.literal("files"), "10.89.0.11:9000"),
                new AppsSendingView.Place("b", Microcopy.literal("postgres"), "10.89.0.7:5432")),
            List.of(new AppsSendingView.Flow(0, 0, oneToA), new AppsSendingView.Flow(0, 1, 200),
                new AppsSendingView.Flow(1, 1, twoToB)));
    }
}
