package be.elevenways.hohenheim.test.docker;

import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.ContainerStream;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.DockerStreamConnection;
import be.elevenways.hohenheim.server.docker.DockerStreamTransport;
import be.elevenways.hohenheim.server.docker.DockerTransport;
import be.elevenways.hohenheim.server.docker.ProcessDockerTransport;
import be.elevenways.hohenheim.server.runtime.ConsoleStream;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The streaming transport contract. The wire-parsing half (chunked decoding, hijacked
 * raw streams, stdout/stderr frame demultiplexing, the four-way typed termination) runs
 * against SCRIPTED connections so it always runs, daemon or not; the incremental-delivery
 * and attach-stdin halves run against the real local daemon (skipped without one); the
 * subprocess-leak half runs against real `cat` subprocesses and COUNTS process state.
 */
class DockerStreamingTest {

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);
    private static final String TEST_IMAGE = "alpine:latest";

    // -- wire parsing over scripted connections (always runs) ----------------

    /** One scripted read after another; an empty script yields EOF; close unblocks a BLOCK step. */
    private static final class ScriptedConnection implements DockerStreamConnection {

        static final byte[] BLOCK = new byte[0];

        private final Deque<byte[]> reads = new ArrayDeque<>();
        private final CountDownLatch closed = new CountDownLatch(1);
        private final ByteArrayOutputStream written = new ByteArrayOutputStream();
        private boolean failAtEnd;

        ScriptedConnection script(byte[]... steps) {
            for (byte[] step : steps) {
                this.reads.add(step);
            }
            return this;
        }

        ScriptedConnection thenFail() {
            this.failAtEnd = true;
            return this;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (this.closed.getCount() == 0) {
                throw new IOException("connection closed");
            }
            byte[] step = this.reads.poll();
            if (step == null) {
                if (this.failAtEnd) {
                    throw new IOException("connection reset by peer");
                }
                return -1;
            }
            if (step == BLOCK) {
                try {
                    this.closed.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new IOException("connection closed");
            }
            System.arraycopy(step, 0, buffer, offset, step.length);
            return step.length;
        }

        @Override
        public void write(byte[] data) {
            this.written.writeBytes(data);
        }

        @Override
        public void close() {
            this.closed.countDown();
        }

        @Override
        public boolean isReleased() {
            return this.closed.getCount() == 0;
        }

        @Override
        public String diagnostics() {
            return "";
        }
    }

    private static DockerStreamTransport transportOf(ScriptedConnection connection) {
        return (request, timeout) -> connection;
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    /** Docker's multiplexed frame: [type,0,0,0,size(4be)] + payload. */
    private static byte[] frame(int type, String payload) {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        byte[] framed = new byte[8 + body.length];
        framed[0] = (byte) type;
        framed[4] = (byte) (body.length >>> 24);
        framed[5] = (byte) (body.length >>> 16);
        framed[6] = (byte) (body.length >>> 8);
        framed[7] = (byte) body.length;
        System.arraycopy(body, 0, framed, 8, body.length);
        return framed;
    }

    private static byte[] chunk(byte[] payload) {
        byte[] head = bytes(Integer.toHexString(payload.length) + "\r\n");
        byte[] out = new byte[head.length + payload.length + 2];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(payload, 0, out, head.length, payload.length);
        out[out.length - 2] = '\r';
        out[out.length - 1] = '\n';
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    private static String drainText(ConsoleStream stream, StringBuilder stderrText) {
        StringBuilder stdout = new StringBuilder();
        ConsoleStream.Chunk chunk;
        while ((chunk = stream.next()) != null) {
            String text = new String(chunk.data(), StandardCharsets.UTF_8);
            (chunk.stderr() ? stderrText : stdout).append(text);
        }
        return stdout.toString();
    }

    /** A DockerClient over a transport whose streaming lane is the scripted connection. */
    private static final class ScriptedClient extends DockerClient {
        ScriptedClient(ScriptedConnection connection) {
            super(new ScriptedTransport(connection));
        }
    }

    private static final class ScriptedTransport implements DockerTransport, DockerStreamTransport {

        private final ScriptedConnection connection;

        ScriptedTransport(ScriptedConnection connection) {
            this.connection = connection;
        }

        @Override
        public byte[] roundTrip(byte[] request, long timeoutMs) {
            throw new UnsupportedOperationException("streaming test");
        }

        @Override
        public byte[] roundTrip(byte[] request, long timeoutMs, long maxResponseBytes) {
            throw new UnsupportedOperationException("streaming test");
        }

        @Override
        public DockerStreamConnection openStream(byte[] request, long connectTimeoutMs) {
            return this.connection;
        }
    }

    @Test
    void chunkedMultiplexedFramesDemuxAcrossHostileBoundaries() throws IOException {
        // 1. A chunked response whose chunk borders fall INSIDE frame headers and
        //    payloads: the demuxer must reassemble regardless.
        byte[] body = concat(frame(1, "out-one\n"), frame(2, "err-one\n"), frame(1, "out-two\n"));
        byte[] chunkA = chunk(java.util.Arrays.copyOfRange(body, 0, 5));      // splits a header
        byte[] chunkB = chunk(java.util.Arrays.copyOfRange(body, 5, 21));     // splits a payload
        byte[] chunkC = chunk(java.util.Arrays.copyOfRange(body, 21, body.length));
        ScriptedConnection connection = new ScriptedConnection().script(
            bytes("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"),
            chunkA, chunkB, chunkC,
            bytes("0\r\n\r\n"));

        ContainerStream stream = new ScriptedClient(connection).followLogs("c", 0);
        StringBuilder stderr = new StringBuilder();
        String stdout = drainText(stream, stderr);

        assertThat(stdout).as("step 1: stdout frames reassembled in order")
            .isEqualTo("out-one\nout-two\n");
        assertThat(stderr.toString()).as("step 1: stderr frames kept separate")
            .isEqualTo("err-one\n");
        assertThat(stream.termination()).as("step 1: last-chunk means ENDED")
            .isEqualTo(ConsoleStream.Termination.ENDED);
    }

    @Test
    void truncatedChunkedStreamIsTypedAsDaemonLost() throws IOException {
        // 2. EOF in the middle of a declared chunk is a lost daemon, never a quiet end.
        ScriptedConnection connection = new ScriptedConnection().script(
            bytes("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"),
            bytes("ff\r\n"), frame(1, "partial"));

        ContainerStream stream = new ScriptedClient(connection).followLogs("c", 0);
        drainText(stream, new StringBuilder());
        assertThat(stream.termination()).as("step 2: mid-chunk EOF is DAEMON_LOST")
            .isEqualTo(ConsoleStream.Termination.DAEMON_LOST);
    }

    @Test
    void hijackedRawStreamEndsAsEndedAndMidStreamErrorAsDaemonLost() throws IOException {
        // 3a. The attach shape: no Transfer-Encoding, frames raw after the head; EOF = ENDED.
        ScriptedConnection ended = new ScriptedConnection().script(
            concat(bytes("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.docker.raw-stream\r\n\r\n"),
                frame(1, "hello\n")));
        ContainerStream stream = new ScriptedClient(ended).attach("c");
        StringBuilder stderr = new StringBuilder();
        assertThat(drainText(stream, stderr)).as("step 3a: raw frame delivered")
            .isEqualTo("hello\n");
        assertThat(stream.termination()).as("step 3a: raw EOF is ENDED")
            .isEqualTo(ConsoleStream.Termination.ENDED);

        // 3b. A read error mid-stream is DAEMON_LOST.
        ScriptedConnection lost = new ScriptedConnection()
            .script(concat(bytes("HTTP/1.1 200 OK\r\n\r\n"), frame(1, "x")))
            .thenFail();
        ContainerStream lostStream = new ScriptedClient(lost).attach("c");
        drainText(lostStream, new StringBuilder());
        assertThat(lostStream.termination()).as("step 3b: read failure is DAEMON_LOST")
            .isEqualTo(ConsoleStream.Termination.DAEMON_LOST);
    }

    @Test
    void consumerCloseIsTypedAsConsumerClosedAndStdinWritesReachTheWire() throws Exception {
        // 4. Close from another thread while next() blocks: CONSUMER_CLOSED, and the
        //    stdin write landed on the connection before that.
        ScriptedConnection connection = new ScriptedConnection().script(
            concat(bytes("HTTP/1.1 200 OK\r\n\r\n"), frame(1, "up\n")),
            ScriptedConnection.BLOCK);
        ContainerStream stream = new ScriptedClient(connection).attach("c");

        assertThat(new String(stream.next().data(), StandardCharsets.UTF_8))
            .as("step 4: first frame arrives before any close").isEqualTo("up\n");
        stream.writeStdin(bytes("say hi\n"));
        assertThat(connection.written.toString(StandardCharsets.ISO_8859_1))
            .as("step 4: stdin write reached the wire").isEqualTo("say hi\n");

        Thread closer = new Thread(() -> {
            try {
                Thread.sleep(150);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            stream.close();
        });
        closer.start();
        assertThat(stream.next()).as("step 4: blocked next() unblocks to null").isNull();
        closer.join(2000);
        assertThat(stream.termination()).as("step 4: our close is CONSUMER_CLOSED, not a"
            + " daemon failure").isEqualTo(ConsoleStream.Termination.CONSUMER_CLOSED);
    }

    @Test
    void nonSuccessStatusRefusesWithApiException() {
        ScriptedConnection connection = new ScriptedConnection().script(
            bytes("HTTP/1.1 404 Not Found\r\n\r\n{\"message\":\"No such container\"}"));
        assertThatThrownBy(() -> new ScriptedClient(connection).attach("gone"))
            .as("a non-2xx stream open carries the daemon's status and reason")
            .isInstanceOf(DockerClient.ApiException.class)
            .hasMessageContaining("404")
            .hasMessageContaining("No such container");
    }

    // -- incremental delivery against the real daemon -------------------------

    @Test
    void followLogsDeliversOutputWhileTheContainerIsStillRunning() throws IOException {
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        DockerClient docker = new DockerClient();
        assumeTrue(imagePresent(docker, TEST_IMAGE), TEST_IMAGE + " not present");

        String name = "hohenheim-streamtest-" + System.nanoTime();
        String id = docker.createContainer(name, Map.of(
            "Image", TEST_IMAGE,
            "Cmd", List.of("sh", "-c", "echo first-line; sleep 60; echo second-line")
        ), ContainerHardening.STRICT);
        try {
            docker.startContainer(id);
            ContainerStream stream = docker.followLogs(id, 0);
            try {
                // 1. INCREMENTALITY: the first line arrives long before the container
                //    exits (it sleeps 60s). A buffer-to-EOF transport can never pass
                //    this: it would block here until the sleep ends.
                StringBuilder seen = new StringBuilder();
                long deadline = System.currentTimeMillis() + 15_000;
                while (seen.indexOf("first-line") < 0
                        && System.currentTimeMillis() < deadline) {
                    ConsoleStream.Chunk chunk = stream.next();
                    assertThat(chunk).as("step 1: stream must not end before the line").isNotNull();
                    seen.append(new String(chunk.data(), StandardCharsets.UTF_8));
                }
                assertThat(seen.toString()).as("step 1: the line arrived incrementally")
                    .contains("first-line");

                // 2. The container is STILL RUNNING at receipt -- the observation that
                //    makes step 1 mean "before exit", not "after".
                Map<String, Object> inspect = docker.inspectContainer(id);
                Object state = inspect.get("State");
                assertThat(state instanceof Map<?, ?> s && Boolean.TRUE.equals(s.get("Running")))
                    .as("step 2: container still running when the line was observed").isTrue();

                // 3. Closing OUR side is typed as CONSUMER_CLOSED.
                stream.close();
                while (stream.next() != null) {
                    // drain whatever raced the close
                }
                assertThat(stream.termination())
                    .as("step 3: consumer close is typed as CONSUMER_CLOSED")
                    .isEqualTo(ConsoleStream.Termination.CONSUMER_CLOSED);
            } finally {
                stream.close();
            }
        } finally {
            try {
                docker.removeContainer(id, true);
            } catch (IOException ignored) {
                // best-effort cleanup
            }
        }
    }

    @Test
    void attachCarriesStdinBothWaysAndEndsWhenTheContainerExits() throws IOException {
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        DockerClient docker = new DockerClient();
        assumeTrue(imagePresent(docker, TEST_IMAGE), TEST_IMAGE + " not present");

        String name = "hohenheim-attachtest-" + System.nanoTime();
        String id = docker.createContainer(name, Map.of(
            "Image", TEST_IMAGE,
            "OpenStdin", true,
            "StdinOnce", false,
            "Cmd", List.of("sh", "-c",
                "while read line; do echo \"got:$line\"; if [ \"$line\" = quit ]; then exit 0; fi; done")
        ), ContainerHardening.STRICT);
        try {
            // 1. Attach BEFORE start (docker run's own order): nothing can be missed.
            ContainerStream stream = docker.attach(id);
            try {
                docker.startContainer(id);

                // 2. Bidirectional: a write AFTER the request reaches the workload,
                //    and its answer comes back on the same stream.
                stream.writeStdin(bytes("hello\n"));
                StringBuilder seen = new StringBuilder();
                long deadline = System.currentTimeMillis() + 15_000;
                while (seen.indexOf("got:hello") < 0
                        && System.currentTimeMillis() < deadline) {
                    ConsoleStream.Chunk chunk = stream.next();
                    assertThat(chunk).as("step 2: stream must not end before the echo").isNotNull();
                    seen.append(new String(chunk.data(), StandardCharsets.UTF_8));
                }
                assertThat(seen.toString()).as("step 2: stdin reached the workload")
                    .contains("got:hello");

                // 3. The workload exiting ends the stream: typed ENDED, and the daemon
                //    confirms exit code 0.
                stream.writeStdin(bytes("quit\n"));
                while (stream.next() != null) {
                    // drain to the end
                }
                assertThat(stream.termination()).as("step 3: container exit ends as ENDED")
                    .isEqualTo(ConsoleStream.Termination.ENDED);
                Map<String, Object> inspect = docker.inspectContainer(id);
                Object state = inspect.get("State");
                assertThat(state instanceof Map<?, ?> s && Boolean.TRUE.equals(s.get("Running")))
                    .as("step 3: the container really exited").isFalse();
            } finally {
                stream.close();
            }
        } finally {
            try {
                docker.removeContainer(id, true);
            } catch (IOException ignored) {
                // best-effort cleanup
            }
        }
    }

    // -- lifecycle: no leaked subprocess, COUNTED -----------------------------

    @Test
    void closedProcessStreamsLeaveNoSubprocessBehind() throws Exception {
        // `cat` echoes its stdin, so the "request" we send IS the HTTP response the
        // stream parses -- the process lane end-to-end with no daemon and no ssh.
        byte[] fakeResponse = concat(bytes("HTTP/1.1 200 OK\r\n\r\n"), frame(1, "alive\n"));

        long before = countCatChildren();
        List<ContainerStream> streams = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ProcessDockerTransport transport = new ProcessDockerTransport(List.of("cat"));
            ContainerStream stream = ContainerStream.open(transport, fakeResponse, 10_000, true);
            assertThat(new String(stream.next().data(), StandardCharsets.UTF_8))
                .as("step 1: stream %d is live before close", i).isEqualTo("alive\n");
            streams.add(stream);
        }
        assertThat(countCatChildren())
            .as("step 2: five live streams = five cat subprocesses")
            .isEqualTo(before + 5);

        for (ContainerStream stream : streams) {
            stream.close();
        }
        long deadline = System.currentTimeMillis() + 10_000;
        while (countCatChildren() > before && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat(countCatChildren())
            .as("step 3: after close, ZERO cat subprocesses remain (counted, not eyeballed)")
            .isEqualTo(before);
        for (ContainerStream stream : streams) {
            long releaseDeadline = System.currentTimeMillis() + 5_000;
            while (!stream.isReleased() && System.currentTimeMillis() < releaseDeadline) {
                Thread.sleep(50);
            }
            assertThat(stream.isReleased())
                .as("step 4: every connection observes its own release").isTrue();
        }
    }

    private static long countCatChildren() {
        return ProcessHandle.current().descendants()
            .filter(handle -> handle.info().command().map(cmd -> cmd.endsWith("/cat")
                || cmd.equals("cat")).orElse(false))
            .count();
    }

    private static boolean imagePresent(DockerClient docker, String reference) {
        try {
            for (Object image : docker.listImages()) {
                Object tags = ((Map<?, ?>) image).get("RepoTags");
                if (tags instanceof List<?> list && list.contains(reference)) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }
}
