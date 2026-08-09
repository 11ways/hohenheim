package be.elevenways.hohenheim.test.live;

import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.DockerTransport;
import be.elevenways.hohenheim.test.live.LiveLane.Need;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The live lane's own mechanism, with no daemon and no host: the gate names what is
 * missing, the declared policy turns a named skip into a FAILURE, the image gate PULLS
 * instead of skipping on a cold cache, and the report renders what a run actually skipped.
 *
 * AIDEV-NOTE: this class is the reason the rest of the suite may be trusted to REPORT its
 * skips. Everything here is hermetic on purpose -- a live-lane gate that only worked on a
 * host with a daemon would be the exact failure it exists to prevent.
 */
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
