package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimFigureFunctions;
import be.elevenways.hohenheim.HohenheimStatsFunctions;
import be.elevenways.hohenheim.instance.LiveFigureState;
import be.elevenways.hohenheim.instance.LiveFigureView;
import be.elevenways.hohenheim.instance.StatusProbe;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceStatus;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.ports.PortPurpose;
import be.elevenways.hohenheim.ports.PublishedPort;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.instance.FakeMinecraftServer;
import be.elevenways.protoblast.common.thread.JobRunner;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.protoblast.common.time.Pace;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The live figure hub's sessions stay honest under what a real service and real viewers do: a hostile answer is a
 * silent figure and the session keeps asking, a changed port or probe is followed and a deleted instance or removed
 * probe ends the session, a subscribe racing the last viewer's leave gets a live session, a stopped session hangs
 * up a probe still in flight, and an instance whose own state says stopped says so without ever being asked.
 *
 * AIDEV-NOTE: the waits between probes are skipped by moving Now's offset (Pace honours it), never by a shorter
 * constant; every wait here is a bound on an event, never a fixed sleep.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class LiveFigureSessionTest extends HohenheimTestBase {

    private static final Duration WAIT = Duration.ofSeconds(15);

    private static final String PREFIX = "live-figure-session-";

    @Test
    void aSessionSurvivesHostileAnswersAndFollowsItsInstanceUntilItGoes() throws Exception {
        Duration offset = Now.offset();
        List<Row> rows = new ArrayList<>();
        FakeMinecraftServer first = FakeMinecraftServer.start(5, 10);
        FakeMinecraftServer second = FakeMinecraftServer.start(7, 30);
        try {
            // 1. A server whose answer overflows its length prefix reads as silent, and the session keeps asking.
            first.rawAnswer(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x07, 0x00, 0x01});
            Row moving = gameServer(rows, PREFIX + "moving", first.port());
            int movingId = moving.get(InstanceModel.ID);
            LinkedBlockingQueue<InstanceFigures.Figure> figures = new LinkedBlockingQueue<>();
            InstanceFigures.Subscription subscription = InstanceFigures.subscribe(List.of(moving), figures::add)
                .get(movingId);
            assertThat(subscription).as("step 1: the instance is followed").isNotNull();
            await(figures, figure -> figure.state() == LiveFigureState.SILENT, "step 1: a hostile answer is silent");
            assertThat(InstanceFigures.asking(movingId)).as("step 1: and the session is still asking").isTrue();

            // 2. Deeply nested JSON is silent too, and the session still asks.
            String nested = "{\"players\":" + "[".repeat(100_000) + "]".repeat(100_000) + "}";
            int answeredBefore = first.answered();
            first.rawAnswer(FakeMinecraftServer.frame(nested.getBytes(StandardCharsets.UTF_8)));
            figures.clear();
            await(figures, figure -> figure.state() == LiveFigureState.SILENT && first.answered() > answeredBefore,
                "step 2: deeply nested JSON is silent");
            assertThat(InstanceFigures.asking(movingId)).as("step 2: and the session is still asking").isTrue();

            // 3. The server answers honestly again: the same session reads it on its next probe.
            first.rawAnswer(null);
            await(figures, figure -> figure.state() == LiveFigureState.ANSWERING && figure.online() == 5,
                "step 3: the honest answer is read");

            // 4. The instance moves to another port: the next probe asks the new one, without a new subscribe.
            Map<String, Object> settings = settingsOf(movingId);
            figures.clear();
            settings.put(PublishedPort.FIELD, Map.of(PortPurpose.GAME.token(),
                second.port() + ":25565/" + PublishedPort.TCP));
            save(movingId, settings);
            await(figures, figure -> figure.state() == LiveFigureState.ANSWERING && figure.online() == 7,
                "step 4: the moved port is followed");

            // 5. Its probe is removed: the session says there is no figure and ends.
            figures.clear();
            settings.remove(StatusProbe.SETTING);
            save(movingId, settings);
            await(figures, figure -> figure.state() == LiveFigureState.SILENT, "step 5: a removed probe is silent");
            awaitEnded(movingId, "step 5: and its session ends");
            subscription.close();

            // 6. A deleted instance ends its session the same way.
            Row doomed = gameServer(rows, PREFIX + "doomed", second.port());
            int doomedId = doomed.get(InstanceModel.ID);
            LinkedBlockingQueue<InstanceFigures.Figure> doomedFigures = new LinkedBlockingQueue<>();
            InstanceFigures.Subscription doomedSubscription = InstanceFigures.subscribe(List.of(doomed),
                doomedFigures::add).get(doomedId);
            await(doomedFigures, figure -> figure.state() == LiveFigureState.ANSWERING,
                "step 6: the instance is read");
            HardDeletes.row(Models.get(InstanceModel.class), doomed);
            await(doomedFigures, figure -> figure.state() == LiveFigureState.SILENT,
                "step 6: a deleted instance is silent");
            awaitEnded(doomedId, "step 6: and its session ends");
            doomedSubscription.close();
        } finally {
            Now.setOffset(offset);
            InstanceFigures.shutdown();
            first.close();
            second.close();
            cleanup(rows);
        }
    }

    @Test
    void aSubscribeRacingTheLastLeaveGetsALiveSession() throws Exception {
        List<Row> rows = new ArrayList<>();
        FakeMinecraftServer server = FakeMinecraftServer.start(4, 8);
        try {
            Row row = gameServer(rows, PREFIX + "race", server.port());
            int instanceId = row.get(InstanceModel.ID);

            // 1. Viewer A follows the instance: its session asks.
            LinkedBlockingQueue<InstanceFigures.Figure> seenByA = new LinkedBlockingQueue<>();
            InstanceFigures.Subscription viewerA = InstanceFigures.subscribe(List.of(row), seenByA::add)
                .get(instanceId);
            await(seenByA, figure -> figure.state() == LiveFigureState.ANSWERING, "step 1: A reads the figure");

            // 2. Viewer B's subscribe finds that session and is held before attaching to it.
            CountDownLatch found = new CountDownLatch(1);
            CountDownLatch go = new CountDownLatch(1);
            AtomicBoolean armed = new AtomicBoolean(true);
            InstanceFigures.beforeAttach = id -> {
                if (id == instanceId && armed.compareAndSet(true, false)) {
                    found.countDown();
                    try {
                        go.await(WAIT.toSeconds(), TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            };
            LinkedBlockingQueue<InstanceFigures.Figure> seenByB = new LinkedBlockingQueue<>();
            CompletableFuture<InstanceFigures.Subscription> viewerB = new CompletableFuture<>();
            JobRunner.startVirtualThread(() -> {
                try {
                    viewerB.complete(InstanceFigures.subscribe(List.of(row), seenByB::add).get(instanceId));
                } catch (RuntimeException failed) {
                    viewerB.completeExceptionally(failed);
                }
            });
            assertThat(found.await(WAIT.toSeconds(), TimeUnit.SECONDS)).as("step 2: B found the session").isTrue();

            // 3. A, the last viewer, leaves meanwhile: that session stops for good.
            viewerA.close();
            assertThat(InstanceFigures.asking(instanceId)).as("step 3: the session A left is stopped").isFalse();

            // 4. B attaches: the stopped session refuses it, and B gets a fresh session that asks.
            go.countDown();
            InstanceFigures.Subscription attached = viewerB.get(WAIT.toSeconds(), TimeUnit.SECONDS);
            assertThat(attached).as("step 4: B is subscribed").isNotNull();
            assertThat(InstanceFigures.asking(instanceId)).as("step 4: to a live session").isTrue();
            assertThat(InstanceFigures.viewers(instanceId)).as("step 4: whose one viewer is B").isEqualTo(1);
            await(seenByB, figure -> figure.state() == LiveFigureState.ANSWERING, "step 4: B reads the figure");
            attached.close();
        } finally {
            InstanceFigures.beforeAttach = null;
            InstanceFigures.shutdown();
            server.close();
            cleanup(rows);
        }
    }

    @Test
    void aStoppedSessionHangsUpTheProbeInFlight() throws Exception {
        List<Row> rows = new ArrayList<>();
        CountDownLatch accepted = new CountDownLatch(1);
        CountDownLatch hungUp = new CountDownLatch(1);
        // A port that accepts the probe and never answers, so the probe waits on its read.
        ServerSocket silent = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        JobRunner.startVirtualThread(() -> {
            try (Socket client = silent.accept()) {
                accepted.countDown();
                InputStream in = client.getInputStream();
                while (in.read() >= 0) {
                    // the probe's request, then nothing until it hangs up
                }
            } catch (IOException gone) {
                // a reset is a hang-up too
            }
            hungUp.countDown();
        });
        try {
            Row row = gameServer(rows, PREFIX + "inflight", silent.getLocalPort());
            int instanceId = row.get(InstanceModel.ID);

            // 1. The probe connects and waits for an answer that never comes.
            InstanceFigures.Subscription subscription = InstanceFigures.subscribe(List.of(row), figure -> { })
                .get(instanceId);
            assertThat(accepted.await(WAIT.toSeconds(), TimeUnit.SECONDS)).as("step 1: the probe connected").isTrue();

            // 2. The last viewer leaves: the probe's connection closes well inside the probe's own wait.
            subscription.close();
            assertThat(hungUp.await(InstanceFigures.PROBE_WAIT.toMillis() / 3, TimeUnit.MILLISECONDS))
                .as("step 2: the stopped session hung up its probe at once").isTrue();
        } finally {
            InstanceFigures.shutdown();
            silent.close();
            cleanup(rows);
        }
    }

    @Test
    void aSessionStoppedBeforeItsAskingBeganStillEndsForItsViewers() throws Exception {
        List<Row> rows = new ArrayList<>();
        FakeMinecraftServer server = FakeMinecraftServer.start(3, 6);
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Row row = gameServer(rows, PREFIX + "early-stop", server.port());
            int instanceId = row.get(InstanceModel.ID);

            // 1. A viewer subscribes; the session's asking is held before it looks whether it was stopped.
            InstanceFigures.beforeAsk = id -> {
                if (id == instanceId) {
                    reached.countDown();
                    try {
                        go.await(WAIT.toSeconds(), TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            };
            CountDownLatch ended = new CountDownLatch(1);
            InstanceFigures.subscribe(List.of(row), figure -> { }, id -> ended.countDown());
            assertThat(reached.await(WAIT.toSeconds(), TimeUnit.SECONDS)).as("step 1: the asking started").isTrue();

            // 2. The hub shuts down before the asking began: the viewer still hears its session end, and the port
            //    was never asked.
            InstanceFigures.shutdown();
            go.countDown();
            assertThat(ended.await(WAIT.toSeconds(), TimeUnit.SECONDS))
                .as("step 2: the viewer heard the end of a session stopped before it asked").isTrue();
            assertThat(InstanceFigures.asking(instanceId)).as("step 2: and nothing asks the instance").isFalse();
            assertThat(server.answered()).as("step 2: the port was never asked").isZero();

            // 3. A host whose server row is gone is never probed on the controller's own loopback, where the same
            //    port number belongs to another workload: nothing answers there.
            PublishedPortProbe gone = PublishedPortProbe.forServer(Integer.MAX_VALUE);
            assertThat(gone.accepts(server.port())).as("step 3: a missing host accepts nothing").isFalse();
            assertThat(gone.exchange(server.port(), MinecraftStatusPing.request(server.port()),
                    MinecraftStatusPing::complete, MinecraftStatusPing.MAX_ANSWER, InstanceFigures.PROBE_WAIT))
                .as("step 3: and answers nothing").isEmpty();
            assertThat(server.answered()).as("step 3: the local workload on that port was never asked").isZero();
        } finally {
            InstanceFigures.beforeAsk = null;
            InstanceFigures.shutdown();
            server.close();
            cleanup(rows);
        }
    }

    @Test
    void aFigureLinkThatEndedReadsAsNoFigureAndOutlastsEveryHeldOne() {
        // 1. An ended link's last figure claims no count: it reads as not answering.
        Map<String, Object> ended = HohenheimFigureFunctions.ended(42);
        assertThat(HohenheimFigureFunctions.state(ended)).as("step 1: the ended figure is silent")
            .isEqualTo(LiveFigureState.SILENT.token());
        assertThat(ended).as("step 1: and carries no count").doesNotContainKey(HohenheimFigureFunctions.ONLINE_KEY);

        // 2. It replaces whatever the element held, however recent, so a stale count never reads as live.
        Map<String, Object> held = new InstanceFigures.Figure(Now.millis(), 42, StatusProbe.MINECRAFT_JAVA,
            LiveFigureState.ANSWERING, 5, 10, null).toMap();
        assertThat(HohenheimStatsFunctions.newer(ended, held)).as("step 2: the end outdates the held figure").isTrue();
    }

    @Test
    void anInstanceNotMeantToRunSaysItsOwnStateAndIsNeverAsked() throws Exception {
        Duration offset = Now.offset();
        List<Row> rows = new ArrayList<>();
        FakeMinecraftServer server = FakeMinecraftServer.start(4, 10);
        try {
            // 1. A stopped instance is seeded with its own state, in that state's words.
            int instanceId = gameServer(rows, PREFIX + "stopped", server.port()).get(InstanceModel.ID);
            Row stopped = status(instanceId, InstanceModel.STATUS_STOPPED);
            LiveFigureView view = InstanceFigures.viewOf(stopped);
            assertThat(view).as("step 1: a stopped instance that declares a probe still has a figure").isNotNull();
            assertThat(view.seed()).as("step 1: seeded with its own state")
                .containsEntry(HohenheimFigureFunctions.STATE_KEY, LiveFigureState.NOT_RUNNING.token())
                .containsEntry(HohenheimFigureFunctions.STATUS_KEY, InstanceModel.STATUS_STOPPED);
            assertThat(HohenheimFigureFunctions.words(view.seed())).as("step 1: which reads Stopped")
                .isEqualTo(InstanceStatus.STOPPED.label());

            // 2. Followed, it says its state round after round, and its port is never asked.
            LinkedBlockingQueue<InstanceFigures.Figure> figures = new LinkedBlockingQueue<>();
            InstanceFigures.Subscription subscription = InstanceFigures.subscribe(List.of(stopped), figures::add)
                .get(instanceId);
            assertThat(subscription).as("step 2: the instance is followed").isNotNull();
            await(figures, figure -> figure.state() == LiveFigureState.NOT_RUNNING
                && figure.status() == InstanceStatus.STOPPED, "step 2: the figure says stopped");
            figures.clear();
            await(figures, figure -> figure.state() == LiveFigureState.NOT_RUNNING,
                "step 2: and again a round later, read from the row");
            assertThat(server.answered()).as("step 2: the stopped instance's port was never asked").isZero();

            // 3. It starts: the next round asks its port.
            status(instanceId, InstanceModel.STATUS_RUNNING);
            await(figures, figure -> figure.state() == LiveFigureState.ANSWERING && figure.online() == 4,
                "step 3: a started instance is asked");

            // 4. It stops again: the figure says so and the port is asked no more.
            status(instanceId, InstanceModel.STATUS_STOPPED);
            await(figures, figure -> figure.state() == LiveFigureState.NOT_RUNNING, "step 4: stopped again");
            int answered = server.answered();
            figures.clear();
            await(figures, figure -> figure.state() == LiveFigureState.NOT_RUNNING, "step 4: a round later");
            assertThat(server.answered()).as("step 4: no probe since it stopped").isEqualTo(answered);
            assertThat(InstanceFigures.viewOf(status(instanceId, InstanceModel.STATUS_STOPPED)).seed())
                .as("step 4: a page drawn now is seeded with the state, never a count from before")
                .containsEntry(HohenheimFigureFunctions.STATE_KEY, LiveFigureState.NOT_RUNNING.token());
            subscription.close();
        } finally {
            Now.setOffset(offset);
            InstanceFigures.shutdown();
            server.close();
            cleanup(rows);
        }
    }

    /**
     * Waits for a figure {@code wanted} accepts, skipping each wait between probes by moving Now's offset; the bound
     * counts polls, since a Now deadline would pass with the very offset it moves.
     */
    static void await(LinkedBlockingQueue<InstanceFigures.Figure> figures,
                      Predicate<InstanceFigures.Figure> wanted, String step) throws InterruptedException {
        List<InstanceFigures.Figure> seen = new ArrayList<>();
        for (long polls = WAIT.toMillis() / 100; polls > 0; polls--) {
            InstanceFigures.Figure figure = figures.poll(100, TimeUnit.MILLISECONDS);
            if (figure == null) {
                Now.setOffset(Now.offset().plus(Duration.ofMinutes(2)));
                continue;
            }
            seen.add(figure);
            if (wanted.test(figure)) {
                return;
            }
        }
        assertThat(seen).as(step).anyMatch(wanted);
    }

    private static void awaitEnded(int instanceId, String step) throws InterruptedException {
        Now.Deadline deadline = Now.deadline(WAIT);
        while (InstanceFigures.asking(instanceId) && !deadline.passed()) {
            Pace.sleep(Duration.ofMillis(20));
        }
        assertThat(InstanceFigures.asking(instanceId)).as(step).isFalse();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> settingsOf(int instanceId) {
        Row row = Models.get(InstanceModel.class).findById(instanceId);
        return new LinkedHashMap<>((Map<String, Object>) row.get(InstanceModel.SETTINGS));
    }

    static void save(int instanceId, Map<String, Object> settings) {
        var instances = Models.get(InstanceModel.class);
        Row row = instances.findById(instanceId);
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(settings));
        instances.save(row);
    }

    /** @return the instance as saved with {@code status} */
    static Row status(int instanceId, String status) {
        var instances = Models.get(InstanceModel.class);
        Row row = instances.findById(instanceId);
        row.set(InstanceModel.STATUS, status);
        instances.save(row);
        return row;
    }

    /** A running Minecraft server instance on the local host, its game port published on {@code port}. */
    static Row gameServer(List<Row> rows, String name, int port) {
        var instances = Models.get(InstanceModel.class);
        Row row = instances.createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", "itzg/minecraft-server");
        settings.put(PublishedPort.FIELD, Map.of(PortPurpose.GAME.token(), port + ":25565/" + PublishedPort.TCP));
        settings.put(StatusProbe.SETTING, StatusProbe.MINECRAFT_JAVA.token());
        row.set(InstanceModel.SETTINGS, settings);
        row.set(InstanceModel.STATUS, InstanceModel.STATUS_RUNNING);
        row.set(InstanceModel.SERVER_ID, ServerModel.localServerId());
        instances.save(row);
        rows.add(row);
        return row;
    }

    static void cleanup(List<Row> rows) {
        for (Row row : rows) {
            HardDeletes.byId(Models.get(InstanceModel.class), row.get(InstanceModel.ID));
        }
    }
}
