package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.instance.InstallMediaFetchState;
import be.elevenways.hohenheim.model.InstallMediaFetchModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.instance.InstallMediaFetches;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The install-media fetch runs OFF the request thread, and its stored row is the status lane the
 * Install media tab renders: pending, downloading with a fraction, importing, then ready, failed with
 * its reason, or interrupted when no live job can still own it. The transfer is a fake so the lane is
 * proven hermetically; the download itself stays the pinned fetcher's.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class InstallMediaFetchLaneTest extends HohenheimTestBase {

    private static final String PREFIX = "media-lane-";

    private static Integer hostId;
    private static Row host;

    @BeforeAll
    static void seedHost() {
        host = Models.get(ServerModel.class).createEmptyRow();
        host.set(ServerModel.NAME, PREFIX + "host");
        host.set(ServerModel.RUNTIME, ServerModel.RUNTIME_INCUS);
        host.set(ServerModel.MODE, ServerModel.MODE_LOCAL);
        host.set(ServerModel.ADMISSION, ServerModel.ADMISSION_ADMITTED);
        host.set(ServerModel.POSTURE, ServerModel.POSTURE_SHARED_CONTAINER);
        Models.get(ServerModel.class).save(host);
        HostFixtures.acknowledgePosture(host);
        hostId = host.get(ServerModel.ID);
    }

    @AfterAll
    static void removeHost() {
        if (hostId != null) {
            // The fetch rows cascade with their host.
            Models.get(ServerModel.class).delete(hostId);
            hostId = null;
        }
    }

    /** A fetch walks pending, downloading with a stored fraction, importing and ready; the tab follows. */
    @Test
    void aFetchIsAStoredStatusTheTabRendersAndRefreshes() throws Exception {
        CountDownLatch reported = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        // 1. The start returns at once with a stored row; the job reports a quarter and parks.
        int fetchId = InstallMediaFetches.start(host, PREFIX + "iso", progress -> {
            progress.reportProgress(0.25);
            reported.countDown();
            awaitQuietly(release);
            progress.importing();
        });
        assertThat(reported.await(10, TimeUnit.SECONDS))
            .as("step 1: the transfer ran on its background job").isTrue();
        Row running = row(fetchId);
        assertThat((String) running.get(InstallMediaFetchModel.STATE))
            .as("step 1: the row says downloading").isEqualTo(InstallMediaFetchState.DOWNLOADING.token());
        assertThat((Double) running.get(InstallMediaFetchModel.PROGRESS))
            .as("step 1: with the reported fraction").isEqualTo(0.25);

        // 2. A second fetch of the same name while the first runs is refused by name.
        Throwable twice = catchThrowable(() -> InstallMediaFetches.start(host, PREFIX + "iso", progress -> { }));
        assertThat(keyOf(twice)).as("step 2: one fetch per name at a time").isEqualTo("media_fetch_running");

        // 3. The tab renders the running fetch and carries the element that reloads it.
        String tab = adminGet("/admin/servers/" + hostId + "/page/install-media").body();
        assertThat(tab).as("step 3: the running fetch is listed")
            .contains("data-fetch-name=\"" + PREFIX + "iso\"");
        assertThat(tab).as("step 3: and the tab refreshes itself while it runs").contains("hh-refresh-after");

        // 4. Released, the job imports and settles READY with a finish stamp.
        release.countDown();
        Row ready = awaitEnding(fetchId);
        assertThat((String) ready.get(InstallMediaFetchModel.STATE))
            .as("step 4: the fetch ended ready").isEqualTo(InstallMediaFetchState.READY.token());
        assertThat((Double) ready.get(InstallMediaFetchModel.PROGRESS)).as("step 4: at 100%").isEqualTo(1.0);
        assertThat(ready.get(InstallMediaFetchModel.FINISHED_AT)).as("step 4: stamped finished").isNotNull();

        // 5. A settled tab lists the ending but no longer polls.
        String settled = adminGet("/admin/servers/" + hostId + "/page/install-media").body();
        assertThat(settled).as("step 5: the ending stays listed")
            .contains("data-fetch-name=\"" + PREFIX + "iso\"");
        assertThat(settled).as("step 5: and nothing reloads a settled tab").doesNotContain("hh-refresh-after");
    }

    /** A failing transfer stores its reason; the cap refuses a third fetch; a corpse reads as interrupted. */
    @Test
    void failuresCapsAndCorpsesAreStoredEndings() throws Exception {
        // 1. A transfer that throws ends FAILED with its message as the reason, shown on the tab.
        int failedId = InstallMediaFetches.start(host, PREFIX + "broken", progress -> {
            throw new IOException("download answered HTTP 404");
        });
        Row failed = awaitEnding(failedId);
        assertThat((String) failed.get(InstallMediaFetchModel.STATE))
            .as("step 1: the fetch ended failed").isEqualTo(InstallMediaFetchState.FAILED.token());
        assertThat((String) failed.get(InstallMediaFetchModel.ERROR))
            .as("step 1: with the transfer's reason").isEqualTo("download answered HTTP 404");
        assertThat(adminGet("/admin/servers/" + hostId + "/page/install-media").body())
            .as("step 1: the reason reaches the tab").contains("download answered HTTP 404");

        // 2. Two fetches in flight are the installation-wide cap: a third is refused.
        CountDownLatch release = new CountDownLatch(1);
        int first = InstallMediaFetches.start(host, PREFIX + "one", progress -> awaitQuietly(release));
        int second = InstallMediaFetches.start(host, PREFIX + "two", progress -> awaitQuietly(release));
        Throwable third = catchThrowable(() -> InstallMediaFetches.start(host, PREFIX + "three", progress -> { }));
        assertThat(keyOf(third)).as("step 2: the third concurrent fetch is refused").isEqualTo("media_fetch_busy");
        release.countDown();
        awaitEnding(first);
        awaitEnding(second);

        // 3. An in-flight row older than any live job can run has no owner left: it reads as
        //    INTERRUPTED and no longer holds a slot of the cap.
        Row corpse = Models.get(InstallMediaFetchModel.class).createEmptyRow();
        corpse.set(InstallMediaFetchModel.SERVER_ID, hostId);
        corpse.set(InstallMediaFetchModel.NAME, PREFIX + "corpse");
        corpse.set(InstallMediaFetchModel.STATE, InstallMediaFetchState.DOWNLOADING.token());
        Models.get(InstallMediaFetchModel.class).save(corpse);
        int corpseId = corpse.get(InstallMediaFetchModel.ID);
        Models.get(InstallMediaFetchModel.class).find()
            .where(InstallMediaFetchModel.ID.eq(corpseId))
            .assign(InstallMediaFetchModel.CREATED_AT, Now.instant().minus(Duration.ofHours(12)))
            .updateAll();
        List<Row> shown = InstallMediaFetches.shownFor(hostId);
        assertThat(shown.stream().filter(row -> corpseId == (Integer) row.get(InstallMediaFetchModel.ID))
                .map(InstallMediaFetches::stateOf).toList())
            .as("step 3: the corpse reads as interrupted")
            .containsExactly(InstallMediaFetchState.INTERRUPTED);
        assertThat((String) row(corpseId).get(InstallMediaFetchModel.STATE))
            .as("step 3: and that ending is stored").isEqualTo(InstallMediaFetchState.INTERRUPTED.token());
    }

    // -- plumbing -------------------------------------------------------------

    private static Row row(int fetchId) {
        Row row = Models.get(InstallMediaFetchModel.class).findById(fetchId);
        assertThat(row).as("fetch row %s exists", fetchId).isNotNull();
        return row;
    }

    /** Poll the stored row until the job has written its ending (a bounded wait, never a sleep-and-hope). */
    private static Row awaitEnding(int fetchId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Row row = row(fetchId);
            if (!InstallMediaFetches.stateOf(row).active()) {
                return row;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("fetch " + fetchId + " never reached an ending");
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String keyOf(Throwable thrown) {
        assertThat(thrown).isInstanceOf(Violations.class);
        return ((Violations) thrown).all().get(0).message().key();
    }
}
