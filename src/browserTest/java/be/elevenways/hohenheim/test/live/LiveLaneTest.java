package be.elevenways.hohenheim.test.live;

import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.DockerTransport;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.test.live.LiveLane.Need;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The live lane's own mechanism, with no daemon and no host: the gate names what is
 * missing, the declared policy turns a named skip into a FAILURE, the image gate PULLS
 * instead of skipping on a cold cache, the report renders what a run actually skipped, and
 * the namespace reaper removes a dead jvm's Docker debris without touching a live one's.
 *
 * AIDEV-NOTE: this class is the reason the rest of the suite may be trusted to REPORT its
 * skips, so the gate/report/ledger halves are hermetic on purpose -- a live-lane gate that
 * only worked on a host with a daemon would be the exact failure it exists to prevent. The
 * ONE exception is {@link #theReapRemovesOnlyItsOwnNamespacesResources}, which asks a real
 * daemon whether a network and a volume are really gone; it gates through {@link LiveLane}
 * like every other live test, and the reaper's SAFETY property is proved daemon-free
 * beside it.
 */
@Tag("slow") // live lane: needs a real daemon/host/image; runs via `zenit-dev test --all`
class LiveLaneTest {

    /**
     * The gate, the marker and the policy as one journey. The marker is not decoration:
     * it is the only channel by which the report learns WHY a test did not run.
     */
    @Test
    void theGateNamesWhatIsMissingAndADeclaredHostTurnsThatSkipIntoAFailure() {
        // 1. A satisfied need is a no-op -- the positive anchor. A gate that aborted
        //    unconditionally would satisfy every assertion below.
        LiveLane.require(Need.NETNS, true, "there is a netns");

        // 2. An unsatisfied need aborts, carrying BOTH the machine-readable need and the
        //    caller's own reason verbatim. JUnit reports an abort as SKIPPED, which is
        //    exactly the outcome that used to read as green.
        Throwable aborted = catchThrowable(
            () -> LiveLane.require(Need.DOCKER_SOCKET, false, "Docker socket not present"));
        assertThat(aborted)
            .as("step 2: an unmet need aborts the test rather than failing it")
            .isInstanceOf(TestAbortedException.class);
        assertThat(aborted.getMessage())
            .as("step 2: and names the need and the reason, in that order")
            .isEqualTo("[live:docker-socket] Docker socket not present");

        // 3. A host that DECLARES it can satisfy the need turns the very same skip into a
        //    failure -- the verdict-legible half. Without this, "report only" would be the
        //    only setting and no CI could ever insist.
        Throwable[] captured = new Throwable[1];
        LiveLane.withRequired(Set.of(Need.DOCKER_SOCKET), () -> captured[0] = catchThrowable(
            () -> LiveLane.require(Need.DOCKER_SOCKET, false, "Docker socket not present")));
        assertThat(captured[0])
            .as("step 3: a declared-but-unsatisfied need FAILS the run")
            .isInstanceOf(AssertionError.class)
            .isNotInstanceOf(TestAbortedException.class);
        assertThat(captured[0].getMessage())
            .as("step 3: and says which need the host promised")
            .contains("REQUIRED").contains("[live:docker-socket]");

        // 4. The policy is per-need, never all-or-nothing: declaring one need leaves every
        //    other one reporting. A host with docker but no Incus is the normal case.
        LiveLane.withRequired(Set.of(Need.DOCKER_SOCKET), () -> assertThat(catchThrowable(
                () -> LiveLane.require(Need.INCUS_HOST, false, "no live incus host")))
            .as("step 4: an undeclared need still merely skips")
            .isInstanceOf(TestAbortedException.class));

        // 5. STATE: the policy is restored afterwards, so one journey cannot leak its
        //    declaration into the classes that share this JVM.
        assertThat(LiveLane.required())
            .as("step 5: withRequired restores the JVM's own policy")
            .isEmpty();
    }

    /**
     * The image gate: a cold image cache on a working daemon is a PULL, not a skip. That
     * category was ~36 classes reporting green on a host that could have run them.
     */
    @Test
    void aColdImageCacheIsPulledAndOnlyAPullThatAchievedNothingSkips() {
        // 1. Present: no pull is attempted at all.
        ScriptedDaemon present = new ScriptedDaemon(true, true);
        LiveLane.requireImage(new DockerClient(present), "alpine:latest");
        assertThat(present.calls)
            .as("step 1: an image already there is inspected once and never pulled")
            .containsExactly("GET /images/alpine:latest/json");

        // 2. Absent, and the pull works: the test RUNS ON -- no abort, and the daemon
        //    really was asked to create the image.
        ScriptedDaemon cold = new ScriptedDaemon(false, true);
        LiveLane.requireImage(new DockerClient(cold), "alpine:latest");
        assertThat(cold.calls)
            .as("step 2: a cold cache is pulled and then re-verified, never skipped")
            .contains("POST /images/create?fromImage=alpine&tag=latest")
            .endsWith("GET /images/alpine:latest/json");

        // 3. The self-guard: a pull that reports success while the image stays absent is a
        //    SKIP, not a pass. This gate exists to stop a step that does less than it
        //    claims, so it must not be one itself.
        ScriptedDaemon liar = new ScriptedDaemon(false, false);
        Throwable aborted = catchThrowable(
            () -> LiveLane.requireImage(new DockerClient(liar), "alpine:latest"));
        assertThat(aborted)
            .as("step 3: a pull that achieved nothing does not let the test proceed")
            .isInstanceOf(TestAbortedException.class);
        assertThat(aborted.getMessage())
            .as("step 3: and says so, rather than blaming the cache")
            .isEqualTo("[live:docker-image]"
                + " alpine:latest is still absent after a pull that reported success");
    }

    /**
     * The report: every abort is classified by its need, an unmarked abort still shows up,
     * and a run that skipped nothing prints nothing.
     */
    @Test
    void theReportShowsEverySkipIncludingOnesNoGateNamed() {
        // 1. A run with no skips says nothing -- a report that always printed would be
        //    noise, and noise is how a real skip goes unread.
        assertThat(new LiveLaneReport().render())
            .as("step 1: nothing skipped, nothing reported").isEmpty();

        // 2. Marked aborts are grouped under their need, with the test names.
        LiveLaneReport report = new LiveLaneReport();
        report.record("DatabaseServiceTest", "[live:docker-socket] Docker socket not present");
        report.record("BinaryBackupTest", "[live:docker-socket] Docker socket not present");
        report.record("IncusVmLiveTest", "[live:incus-host] no live incus host enrolled");

        // 3. An abort NO gate named still appears: the report's completeness must not
        //    depend on every call site having been migrated.
        report.record("SomeOlderTest", "a bare assumeTrue nobody classified");
        String text = report.render();
        assertThat(text)
            .as("step 3: every need, reason and test name is on the page")
            .contains("docker-socket  (2 skipped in this jvm)")
            .contains("Docker socket not present")
            .contains("- DatabaseServiceTest")
            .contains("- BinaryBackupTest")
            .contains("incus-host  (1 skipped in this jvm)")
            .contains("unclassified  (1 skipped in this jvm)")
            .contains("a bare assumeTrue nobody classified");
        assertThat(text)
            .as("step 3: and it tells the reader how to make these fail instead")
            .contains("-Dhohenheim.live.require=docker-socket,incus-host,unclassified");
    }

    /**
     * The truncation half: a JVM torn down with tests unaccounted for names them, split
     * into killed-in-flight and never-started, and a settled run renders NOTHING -- the
     * banner only means something if a completed run can never print it.
     */
    @Test
    void aTruncatedRunNamesItsUnfinishedTestsAndACompletedRunStaysSilent() {
        // 1. Nothing tracked, nothing rendered -- the positive anchor.
        LiveLaneReport report = new LiveLaneReport();
        assertThat(report.renderTruncation())
            .as("step 1: an empty tracker renders no truncation").isEmpty();

        // 2. A plan of three tests: one finishes, one is killed mid-flight, one never
        //    starts. Exactly the shape a Gradle task timeout produces.
        report.trackPlanned("[test:done]", "FinishedTest.ok");
        report.trackPlanned("[test:killed]", "RestoreCapacityLiveTest.theRealPool");
        report.trackPlanned("[test:unstarted]", "NeverStartedTest.later");
        report.trackStarted("[test:done]", "FinishedTest.ok");
        report.trackFinished("[test:done]");
        report.trackStarted("[test:killed]", "RestoreCapacityLiveTest.theRealPool");
        String text = report.renderTruncation();
        assertThat(text)
            .as("step 2: the killed test is named as IN FLIGHT, never as a mere skip")
            .contains("KILLED IN FLIGHT")
            .contains("- RestoreCapacityLiveTest.theRealPool");
        assertThat(text)
            .as("step 2: the never-started test is named separately")
            .contains("never started in this jvm")
            .contains("- NeverStartedTest.later");
        assertThat(text)
            .as("step 2: the settled test does not appear as truncated")
            .doesNotContain("FinishedTest.ok");
        assertThat(text)
            .as("step 2: and the banner says what a reader must conclude")
            .contains("NO verdict").contains("finally blocks");

        // 3. Settling the remaining tests silences the banner completely: only a run
        //    that actually died mid-flight can ever produce it.
        report.trackFinished("[test:killed]");
        report.trackFinished("[test:unstarted]");
        assertThat(report.renderTruncation())
            .as("step 3: a fully settled run renders no truncation").isEmpty();
    }

    /**
     * The namespace reaper's SAFETY property, with no daemon: a namespace is reaped only
     * when the JVM that minted it is provably gone, and a LIVE session's ledger survives
     * untouched -- two agents run live lanes concurrently and one may never sweep the
     * other's networks.
     */
    @Test
    void theNamespaceReaperConsumesADeadJvmsLedgerAndNeverALiveOnes() throws Exception {
        Path dir = Files.createTempDirectory("hohenheim-ns-ledger");
        String previous = System.getProperty(LiveNamespaces.DIR_PROPERTY);
        System.setProperty(LiveNamespaces.DIR_PROPERTY, dir.toString());
        long self = ProcessHandle.current().pid();
        try {
            // 1. Minting a namespace leaves a reapable trace on disk IMMEDIATELY, which is
            //    the whole SIGKILL story: a shutdown hook never runs, a file already does.
            LiveNamespaces.note("zzlivea1");
            Path ours = dir.resolve("jvm-" + self + ".txt");
            assertThat(Files.readString(ours))
                .as("step 1: this jvm's ledger names the namespace it just minted")
                .contains("zzlivea1");

            // 2. A ledger written by a pid that no longer exists is claimed and consumed.
            long dead = deadPid();
            Path abandoned = dir.resolve("jvm-" + dead + ".txt");
            Files.writeString(abandoned, dead + " 1\nzzdeadb2\n");
            LiveNamespaces.sweepAbandoned();
            assertThat(abandoned)
                .as("step 2: the dead jvm's ledger is consumed, so its namespace is reaped"
                    + " exactly once")
                .doesNotExist();

            // 3. THE ANCHOR that makes step 2 mean anything: this jvm is alive, so the
            //    same sweep left OUR ledger completely alone. A reaper that consumed
            //    everything would have passed step 2 while destroying a concurrent
            //    session's running workloads.
            assertThat(ours)
                .as("step 3: a LIVE jvm's ledger is never claimed by another fork's sweep")
                .exists();
            assertThat(Files.readString(ours))
                .as("step 3: and still carries its namespace").contains("zzlivea1");

            // 4. The owning jvm's own plan-finish sweep is what drops it.
            LiveNamespaces.sweepOwn();
            assertThat(ours).as("step 4: a finished plan retires its own ledger")
                .doesNotExist();
        } finally {
            if (previous == null) {
                System.clearProperty(LiveNamespaces.DIR_PROPERTY);
            } else {
                System.setProperty(LiveNamespaces.DIR_PROPERTY, previous);
            }
        }
    }

    /**
     * The reap itself, against a REAL daemon: everything of the named namespace goes --
     * network AND volume -- and the same two shapes of another namespace stay. Asserted
     * from the daemon's own listing, never from the reaper's return value: a reaper that
     * reported names it never removed is precisely the "reports success" shape this lane
     * exists to catch.
     *
     * AIDEV-NOTE: the volume half is asserted here rather than trusted, because it is the
     * one place {@link LiveNamespaces} deliberately parts company with the DockerReclaim
     * rule that refuses to delete volumes. A deletion that broad has to be shown to be
     * narrow.
     */
    @Test
    void theReapRemovesOnlyItsOwnNamespacesResources() throws Exception {
        LiveLane.require(Need.DOCKER_SOCKET,
            Files.exists(Path.of(DockerClient.DEFAULT_SOCKET)), "Docker socket not present");
        DockerClient docker = new DockerClient();
        String mine = "zzreap" + Long.toHexString(System.nanoTime() & 0xffff);
        String theirs = "zzkeep" + Long.toHexString(System.nanoTime() & 0xffff);
        String ourNetwork = "hohenheim-" + mine + "-instance-1-net";
        String theirNetwork = "hohenheim-" + theirs + "-instance-1-net";
        String ourVolume = "hohenheim-" + mine + "-instance-1-vol-data";
        String theirVolume = "hohenheim-" + theirs + "-instance-1-vol-data";
        try {
            docker.createNetwork(ourNetwork,
                Map.of(OwnerLabels.CONTROLLER, mine), null, null, false);
            docker.createNetwork(theirNetwork,
                Map.of(OwnerLabels.CONTROLLER, theirs), null, null, false);
            docker.createVolume(ourVolume, Map.of(OwnerLabels.CONTROLLER, mine));
            docker.createVolume(theirVolume, Map.of(OwnerLabels.CONTROLLER, theirs));

            // 1. The reap removes the network of the namespace it was asked about.
            LiveNamespaces.reap(mine);
            assertThat(docker.findNetworkByName(ourNetwork))
                .as("step 1: the namespace's own network is gone from the daemon").isNull();

            // 2. And its named volume, which no operator sweep would touch -- a dead test
            //    namespace's scratch is not tenant data.
            assertThat(volumeExists(docker, ourVolume))
                .as("step 2: the namespace's own volume is gone too").isFalse();

            // 3. THE ANCHOR: another namespace -- a concurrent session's, in the shape
            //    that actually happens -- keeps both.
            assertThat(docker.findNetworkByName(theirNetwork))
                .as("step 3: a foreign namespace's network survives the reap").isNotNull();
            assertThat(volumeExists(docker, theirVolume))
                .as("step 3: and so does its volume").isTrue();
        } finally {
            for (String network : List.of(ourNetwork, theirNetwork)) {
                try {
                    docker.removeNetwork(network);
                } catch (IOException absent) {
                    // step 1 already removed one of them; the other is this cleanup's job
                }
            }
            for (String volume : List.of(ourVolume, theirVolume)) {
                try {
                    docker.removeVolume(volume, true);
                } catch (IOException absent) {
                    // as above: the reaped one is already gone
                }
            }
        }
    }

    /** Whether the daemon still holds a named volume. */
    private static boolean volumeExists(DockerClient docker, String name) throws IOException {
        try {
            return docker.inspectVolume(name) != null;
        } catch (IOException absent) {
            return false;
        }
    }

    /** A pid no live process holds, so a ledger written under it is provably abandoned. */
    private static long deadPid() {
        for (long pid = 4_000_000L; pid > 100_000L; pid--) {
            if (ProcessHandle.of(pid).isEmpty()) {
                return pid;
            }
        }
        throw new IllegalStateException("every pid on this machine is in use");
    }

    /**
     * A minimal Docker daemon over the raw transport: it answers image inspects present or
     * absent and records every path, which is all the image gate reads.
     */
    private static final class ScriptedDaemon implements DockerTransport {

        private final List<String> calls = new ArrayList<>();
        private boolean present;
        private final boolean pullWorks;

        ScriptedDaemon(boolean present, boolean pullWorks) {
            this.present = present;
            this.pullWorks = pullWorks;
        }

        @Override
        public byte[] roundTrip(byte[] request, long timeoutMs) {
            String line = new String(request, StandardCharsets.UTF_8).split("\r\n", 2)[0];
            String[] parts = line.split(" ");
            String call = parts[0] + " " + parts[1];
            this.calls.add(call);
            if (call.startsWith("POST /images/create")) {
                this.present = this.pullWorks;
                return http(200, "{\"status\":\"Downloaded\"}\n");
            }
            if (call.startsWith("GET /images/json")) {
                return http(200, "[]");
            }
            if (call.startsWith("GET /images/") && call.endsWith("/json")) {
                return this.present
                    ? http(200, "{\"Id\":\"sha256:abc\"}")
                    : http(404, "{\"message\":\"No such image\"}");
            }
            return http(404, "{\"message\":\"unhandled " + call + "\"}");
        }

        @Override
        public byte[] roundTrip(byte[] request, long timeoutMs, long maxResponseBytes) {
            return roundTrip(request, timeoutMs);
        }

        private static byte[] http(int status, String body) {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            String head = "HTTP/1.1 " + status + " x\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + payload.length + "\r\n"
                + "Connection: close\r\n\r\n";
            byte[] header = head.getBytes(StandardCharsets.UTF_8);
            byte[] whole = new byte[header.length + payload.length];
            System.arraycopy(header, 0, whole, 0, header.length);
            System.arraycopy(payload, 0, whole, header.length, payload.length);
            return whole;
        }
    }
}
