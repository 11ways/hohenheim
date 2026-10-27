package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.instance.InstallMediaFetchState;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.instance.InstallMediaView;
import be.elevenways.hohenheim.model.InstallMediaFetchModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.cms.ServerMediaTab;
import be.elevenways.hohenheim.server.instance.InstallMedia;
import be.elevenways.hohenheim.server.instance.InstallMediaFetches;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.protoblast.testsupport.time.Poll;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.channel.ChannelHub;
import be.elevenways.zenit.common.live.LiveChannel;
import be.elevenways.zenit.common.live.LiveFeed;
import be.elevenways.zenit.common.orm.model.ModelChanges;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.lease.Lease;
import be.elevenways.zenit.common.orm.lease.Leases;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.live.LiveInvalidations;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The install-media fetch runs OFF the request thread, and its stored row is the status lane the
 * Install media tab renders: pending, downloading with a fraction, importing, then ready, failed with
 * its reason, or interrupted as soon as no live job holds its lease. The transfer is a fake so the lane is
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

        // 3. The tab renders the running fetch inside its live region, and no timer element that
        //    would re-navigate the whole tab is left anywhere.
        String tab = adminGet("/admin/servers/" + hostId + "/page/install-media").body();
        assertThat(tab).as("step 3: the running fetch is listed")
            .contains("data-fetch-name=\"" + PREFIX + "iso\"");
        assertThat(tab).as("step 3: inside the live region that follows it").contains("<hh-install-media");
        assertThat(tab).as("step 3: and never through a reload timer").doesNotContain("hh-refresh-after");

        // 4. Released, the job imports and settles READY with a finish stamp.
        release.countDown();
        Row ready = awaitEnding(fetchId);
        assertThat((String) ready.get(InstallMediaFetchModel.STATE))
            .as("step 4: the fetch ended ready").isEqualTo(InstallMediaFetchState.READY.token());
        assertThat((Double) ready.get(InstallMediaFetchModel.PROGRESS)).as("step 4: at 100%").isEqualTo(1.0);
        assertThat(ready.get(InstallMediaFetchModel.FINISHED_AT)).as("step 4: stamped finished").isNotNull();

        // 5. A settled tab lists the ending, and the live re-read answers the SAME view the tab
        //    rendered: the region can never draw a fetch differently after an update.
        String settled = adminGet("/admin/servers/" + hostId + "/page/install-media").body();
        assertThat(settled).as("step 5: the ending stays listed")
            .contains("data-fetch-name=\"" + PREFIX + "iso\"");
        InstallMediaView view = ServerMediaTab.view(host);
        assertThat(view.fetches().stream().filter(fetch -> (PREFIX + "iso").equals(fetch.name())).toList())
            .as("step 5: the view carries the fetch as settled and ready")
            .singleElement()
            .satisfies(fetch -> {
                assertThat(fetch.active()).as("step 5: no longer active").isFalse();
                assertThat(fetch.state()).as("step 5: labelled ready")
                    .isEqualTo(InstallMediaFetchState.READY.label());
                assertThat(fetch.percent()).as("step 5: a settled fetch draws no bar").isNull();
            });
        assertThat(adminGet("/admin/servers/" + hostId + "/page/install-media").statusCode())
            .as("step 5: the page the live part is a piece of answers the admin").isEqualTo(200);
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

        // 1b. A download past the install-media cap ends FAILED in the refusal's own words, its cap worded as a size.
        int tooLargeId = InstallMediaFetches.start(host, PREFIX + "huge", progress -> {
            throw InstallMedia.downloadTooLarge();
        });
        assertThat((String) awaitEnding(tooLargeId).get(InstallMediaFetchModel.ERROR))
            .as("step 1b: the cap's refusal reads as its words")
            .isEqualTo("The download is larger than the 16 GB an install medium may hold");

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

        // 4. A FRESH in-flight row nobody's job holds the lease of is a corpse at once: a crashed
        //    controller's fetch no longer waits hours for a clock bound to give up on it.
        int orphanId = inFlightRow(PREFIX + "orphan");
        assertThat(InstallMediaFetches.stateOf(onTab(orphanId)))
            .as("step 4: an unowned fetch reads as interrupted without any wait")
            .isEqualTo(InstallMediaFetchState.INTERRUPTED);

        // 5. The same fresh row while ANOTHER controller's job holds its lease (a rival coordinator,
        //    exactly like a second process over this database) is live: it stays in flight, and a
        //    second start of that name is refused -- the lease, not this process's rows, decides.
        String contested = PREFIX + "contested";
        int contestedId = inFlightRow(contested);
        Leases rival = Leases.independent(Models.get(InstallMediaFetchModel.class).getResolvedDatasource());
        Lease rivalJob = rival.tryAcquire(InstallMediaFetches.leaseKey(hostId, contested));
        assertThat(rivalJob).as("step 5: the rival controller's job holds the fetch lease").isNotNull();
        try {
            assertThat(InstallMediaFetches.stateOf(onTab(contestedId)))
                .as("step 5: a fetch another controller runs stays in flight")
                .isEqualTo(InstallMediaFetchState.DOWNLOADING);
            assertThat(keyOf(catchThrowable(() -> InstallMediaFetches.start(host, contested, progress -> { }))))
                .as("step 5: and its name cannot be fetched twice").isEqualTo("media_fetch_running");
        } finally {
            rivalJob.release();
        }

        // 6. The rival's job ends without writing (its process died): the next read settles it.
        assertThat(InstallMediaFetches.stateOf(onTab(contestedId)))
            .as("step 6: once no job holds the lease, the row reads as interrupted")
            .isEqualTo(InstallMediaFetchState.INTERRUPTED);
    }

    /**
     * The live part's server half: its feed hears the host's own fetch rows and pool behind the tab's own gate, and
     * a host the tab refuses has no part to read.
     */
    @Test
    void theTabsLivePartHearsItsOwnHostBehindTheTabsOwnGate() throws Exception {
        // 1. The part's feed is the fetch model and the pool, keyed by this host.
        LiveFeed feed = ServerMediaTab.PART.feedFor(partOf(hostId));
        assertThat(feed).as("step 1: the install-media part has a feed").isNotNull();
        assertThat(feed.models()).as("step 1: over the fetch model and the pool")
            .contains(InstallMediaFetchModel.MODEL_ID);
        assertThat(feed.changeKeys(InstallMediaFetchModel.MODEL_ID, AccessContext.anonymous()))
            .as("step 1: heard for this host's fetch rows only")
            .containsExactly(ModelChanges.keyOf(InstallMediaFetchModel.SERVER_ID, hostId));

        // 1b. One host has one part: another spelling of its id names no part, so hears nothing.
        assertThat(ServerMediaTab.PART.feedFor(HohenheimSlugs.ADMIN + ",0" + hostId + ",1"))
            .as("step 1b: a zero-padded id names no part").isNull();
        assertThat(ServerMediaTab.PART.feedFor(HohenheimSlugs.ADMIN + "," + hostId + ",0"))
            .as("step 1b: page 0 names no part").isNull();

        // 2. A viewer the tab refuses never hears a fetch move.
        assertThat(feed.mayWatch(AccessContext.anonymous()))
            .as("step 2: an anonymous viewer may not watch").isFalse();

        // 3. A Docker host has no Install media tab, so no page the part could be a piece of.
        Row docker = Models.get(ServerModel.class).createEmptyRow();
        docker.set(ServerModel.NAME, PREFIX + "docker");
        docker.set(ServerModel.RUNTIME, ServerModel.RUNTIME_DOCKER);
        docker.set(ServerModel.MODE, ServerModel.MODE_LOCAL);
        docker.set(ServerModel.POSTURE, ServerModel.POSTURE_TRUSTED_ONLY);
        Models.get(ServerModel.class).save(docker);
        int dockerId = docker.get(ServerModel.ID);
        try {
            assertThat(adminGet("/admin/servers/" + dockerId + "/page/install-media").statusCode())
                .as("step 3: a Docker host's tab is not found, so is its part").isEqualTo(404);
        } finally {
            Models.get(ServerModel.class).delete(dockerId);
        }
    }

    /**
     * The open tab follows a running fetch IN PLACE: the region updates to the ending while the
     * document, the history and a half-typed form stay exactly as they were.
     */
    @Test
    void anOpenTabFollowsARunningFetchInPlaceWithoutNavigating() throws Exception {
        String name = PREFIX + "live";
        CountDownLatch reported = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        int fetchId = InstallMediaFetches.start(host, name, progress -> {
            progress.reportProgress(0.25);
            reported.countDown();
            awaitQuietly(release);
            progress.importing();
        });
        assertThat(reported.await(10, TimeUnit.SECONDS))
            .as("step 1: the transfer ran and parked at a quarter").isTrue();

        try {
            // 1. Open the tab: the running fetch is drawn with its progress bar.
            String row = "hh-install-media [data-fetch-name=\"" + name + "\"]";
            navigateToApp("/admin/servers/" + hostId + "/page/install-media");
            waitForHydration();
            assertThat(countElements(row + " pl-progress"))
                .as("step 1: the running fetch shows its progress").isEqualTo(1);

            // 2. Pin the document, the history depth and a half-typed fetch form.
            page.evaluate("() => { window.__mediaTabProbe = 'alive'; }");
            Object historyBefore = page.evaluate("() => history.length");
            String urlBefore = page.url();
            page.fill("#media-name", "half-typed");

            // 3. The part's follow reached the server: a subscriber on this host's fetch rows.
            Poll.until("step 3: the tab's live part subscribed to its host's fetch rows", Duration.ofSeconds(10),
                () -> ChannelHub.subscriberCount(LiveChannel.CHANNEL, LiveInvalidations.topicOf(
                    InstallMediaFetchModel.MODEL_ID,
                    ModelChanges.keyOf(InstallMediaFetchModel.SERVER_ID, hostId))) > 0);

            // 4. The fetch finishes on the server; the open region follows it to its ending.
            release.countDown();
            awaitEnding(fetchId);
            waitForPageFunction("() => { const r = document.querySelector('" + row
                + "'); return !!r && r.querySelector('pl-progress') === null; }");
            assertThat(countElements(row + " pl-progress"))
                .as("step 4: the settled fetch no longer draws a bar").isZero();
            assertThat(getTextContent(row))
                .as("step 4: and the row now names the ending (the ready label's English text)")
                .contains("Available");

            // 5. Nothing navigated: the same document, no new history entry, the URL and the typed
            //    value untouched.
            assertThat(page.evaluate("() => window.__mediaTabProbe"))
                .as("step 5: the same document, no reload").isEqualTo("alive");
            assertThat(page.evaluate("() => history.length"))
                .as("step 5: no history entry was pushed").isEqualTo(historyBefore);
            assertThat(page.url()).as("step 5: the URL did not move").isEqualTo(urlBefore);
            assertThat(page.inputValue("#media-name"))
                .as("step 5: the half-typed fetch form survived the update").isEqualTo("half-typed");
        } finally {
            // A parked fetch left behind would hold one of the two installation-wide slots.
            release.countDown();
        }
    }

    /**
     * An upload into the host's pool redraws the open tab's part IN PLACE: the new medium appears while the document,
     * the history, the forms beside the part and a half-typed fetch form stay exactly as they were.
     */
    @Test
    void anUploadRedrawsTheOpenTabsMediaInPlace() throws Exception {
        String name = "uploaded.iso";
        Path iso = Files.createTempFile("hohenheim-upload-test-", ".iso");
        Files.writeString(iso, "not really an iso");
        try (FakeIncusSocket incus = FakeIncusSocket.start()) {
            int socketHostId = socketHost(incus);
            try {
                // 1. Open the tab: the pool holds nothing yet.
                String medium = "hh-install-media [data-medium-name=\"" + name + "\"]";
                navigateToApp("/admin/servers/" + socketHostId + "/page/install-media");
                waitForHydration();
                assertThat(countElements(medium)).as("step 1: the pool holds no upload yet").isZero();

                // 2. Pin the document, the history depth, the fetch form element and a half-typed fetch form.
                page.evaluate("() => { window.__mediaTabProbe = 'alive';"
                    + " document.querySelector('#media-fetch-form').__kept = 'kept'; }");
                Object historyBefore = page.evaluate("() => history.length");
                String urlBefore = page.url();
                page.fill("#media-name", "half-typed");

                // 3. The part's follow reached the server: a subscriber on this host's pool.
                Poll.until("step 3: the tab's live part subscribed to its host's pool", Duration.ofSeconds(10),
                    () -> ChannelHub.subscriberCount(LiveChannel.CHANNEL, LiveInvalidations.topicOf(
                        InstallMediaFetchModel.MODEL_ID,
                        ModelChanges.keyOf(InstallMediaFetchModel.SERVER_ID, socketHostId))) > 0);

                // 4. Upload through the tab's own form: the daemon takes the medium and the part draws it.
                page.fill("#media-upload-name", name);
                page.setInputFiles("#media-upload-file", iso);
                page.click("#media-upload-form pl-button[type=submit]");
                waitForPageFunction("() => !!document.querySelector('" + medium + "')");
                assertThat(incus.daemon.customVolumes).as("step 4: the daemon holds the upload").containsKey(name);
                assertThat(countElements(medium)).as("step 4: the part draws the new medium").isEqualTo(1);

                // 5. Only the part redrew: the same document, no new history entry, the URL, the fetch form element
                //    and its typed value untouched.
                assertThat(page.evaluate("() => window.__mediaTabProbe"))
                    .as("step 5: the same document, no reload").isEqualTo("alive");
                assertThat(page.evaluate("() => history.length"))
                    .as("step 5: no history entry was pushed").isEqualTo(historyBefore);
                assertThat(page.url()).as("step 5: the URL did not move").isEqualTo(urlBefore);
                assertThat(page.evaluate("() => document.querySelector('#media-fetch-form').__kept"))
                    .as("step 5: the fetch form beside the part was never re-rendered").isEqualTo("kept");
                assertThat(page.inputValue("#media-name"))
                    .as("step 5: the half-typed fetch form survived the update").isEqualTo("half-typed");
            } finally {
                Models.get(ServerModel.class).delete(socketHostId);
            }
        } finally {
            Files.deleteIfExists(iso);
        }
    }

    // -- plumbing -------------------------------------------------------------

    /** A DOWNLOADING row written just now, as a job would have left it; no lease is taken for it. */
    private static int inFlightRow(String name) {
        Row row = Models.get(InstallMediaFetchModel.class).createEmptyRow();
        row.set(InstallMediaFetchModel.SERVER_ID, hostId);
        row.set(InstallMediaFetchModel.NAME, name);
        row.set(InstallMediaFetchModel.STATE, InstallMediaFetchState.DOWNLOADING.token());
        Models.get(InstallMediaFetchModel.class).save(row);
        return row.get(InstallMediaFetchModel.ID);
    }

    /** @return the install-media part parameter of a host on the admin panel */
    private static String partOf(int serverId) {
        return HohenheimSlugs.ADMIN + "," + serverId + ",1";
    }

    /** A host whose daemon is the fake on a unix socket, reached through the production client lane. */
    private static int socketHost(FakeIncusSocket incus) {
        Row socketHost = Models.get(ServerModel.class).createEmptyRow();
        socketHost.set(ServerModel.NAME, PREFIX + "socket-host");
        socketHost.set(ServerModel.RUNTIME, ServerModel.RUNTIME_INCUS);
        socketHost.set(ServerModel.MODE, ServerModel.MODE_LOCAL);
        socketHost.set(ServerModel.INCUS_URL, incus.url());
        socketHost.set(ServerModel.ADMISSION, ServerModel.ADMISSION_ADMITTED);
        socketHost.set(ServerModel.POSTURE, ServerModel.POSTURE_SHARED_CONTAINER);
        Models.get(ServerModel.class).save(socketHost);
        HostFixtures.acknowledgePosture(socketHost);
        return socketHost.get(ServerModel.ID);
    }

    /** The row as the tab reads it (settling included). */
    private static Row onTab(int fetchId) {
        return InstallMediaFetches.shownFor(hostId).stream()
            .filter(row -> fetchId == (Integer) row.get(InstallMediaFetchModel.ID))
            .findFirst()
            .orElseThrow(() -> new AssertionError("fetch " + fetchId + " is not on the tab"));
    }

    private static Row row(int fetchId) {
        Row row = Models.get(InstallMediaFetchModel.class).findById(fetchId);
        assertThat(row).as("fetch row %s exists", fetchId).isNotNull();
        return row;
    }

    /** Poll the stored row until the job has written its ending (a bounded wait, never a sleep-and-hope). */
    private static Row awaitEnding(int fetchId) {
        return Poll.value("fetch " + fetchId + " reaching an ending", Duration.ofSeconds(10), () -> {
            Row row = row(fetchId);
            return InstallMediaFetches.stateOf(row).active() ? null : row;
        });
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
