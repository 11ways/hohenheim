package be.elevenways.hohenheim.test.docker;

import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.UnixSocketDockerTransport;
import be.elevenways.protoblast.common.thread.JobRunner;
import be.elevenways.protoblast.common.time.Now;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * An exec whose start the daemon never finishes ends at the exec's own deadline, over a REAL unix socket and the
 * production {@link UnixSocketDockerTransport}, and a readiness wait keeps its own overall deadline around it.
 *
 * AIDEV-NOTE: the defect this pins. Every exec read was bounded by the fixed 10-minute LONG_OP deadline, so one
 * mongo readiness probe wedged in a starting engine held BinaryBackupTest's ManagedDatabase create until the run
 * budget killed the run. The fake daemon here answers the exec create and the exit-code inspect, and leaves the
 * start either fully mute or with a stream head and then nothing, the two shapes a wedged container leaves.
 *
 * @author Jelle De Loecker
 * @since 0.10.0
 */
class DockerExecDeadlineTest {

    private static final long DEADLINE_MS = 1_500;

    /** How the fake daemon answers an exec start. */
    private enum StartAnswer { NORMAL, MUTE, HEAD_THEN_NOTHING }

    private Path directory;
    private ServerSocketChannel server;
    private volatile StartAnswer startAnswer = StartAnswer.NORMAL;
    private final List<SocketChannel> accepted = new CopyOnWriteArrayList<>();
    private final JobRunner daemons = JobRunner.create("fake-docker-exec");

    @BeforeEach
    void listen() throws IOException {
        this.directory = Files.createTempDirectory("hohenheim-fake-docker-exec");
        this.server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        this.server.bind(UnixDomainSocketAddress.of(this.directory.resolve("docker.sock")));
        this.daemons.startThread(this::acceptForever);
    }

    @AfterEach
    void shutDown() throws IOException {
        this.server.close();
        for (SocketChannel channel : this.accepted) {
            channel.close();
        }
        this.daemons.shutdownNow();
        Files.deleteIfExists(this.directory.resolve("docker.sock"));
        Files.deleteIfExists(this.directory);
    }

    @Test
    void aWedgedExecEndsAtItsDeadlineAndReadinessKeepsItsOwnJourney() throws Exception {
        DockerClient docker = new DockerClient(
            new UnixSocketDockerTransport(this.directory.resolve("docker.sock").toString()));

        // 1. The control: the fake daemon answers a whole exec, so what follows fails on the deadline alone.
        DockerClient.ExecResult answered = docker.exec("db-1", List.of("echo", "hi"), List.of(), DEADLINE_MS);
        assertThat(answered.exitCode()).as("step 1: the exit code comes from the inspect").isZero();
        assertThat(answered.stdout()).as("step 1: the stdout frame is decoded").isEqualTo("hi\n");

        // 2. A start the daemon accepts and never answers: a named deadline, not a hang.
        for (StartAnswer wedge : new StartAnswer[] {StartAnswer.MUTE, StartAnswer.HEAD_THEN_NOTHING}) {
            this.startAnswer = wedge;
            long started = Now.millis();
            Throwable failure = catchThrowable(() ->
                docker.exec("db-1", List.of("mongosh", "--eval", "ping"), List.of(), DEADLINE_MS));
            long took = Now.millis() - started;
            assertThat(failure)
                .as("step 2 (" + wedge + "): the timeout names the container, the command and the bound")
                .isInstanceOf(SocketTimeoutException.class)
                .hasMessageContaining("'db-1'")
                .hasMessageContaining("mongosh --eval ping")
                .hasMessageContaining("within " + DEADLINE_MS + "ms");
            assertThat(took)
                .as("step 2 (" + wedge + "): the blocked read was ended at its deadline (took %sms)", took)
                .isBetween(DEADLINE_MS - 100, DEADLINE_MS + 3_000);
        }

        // 3. A readiness wait whose every probe wedges: each probe is "not ready yet", bounded by what is left of
        //    the wait, so the wait ends at ITS deadline and names the probe's timeout.
        this.startAnswer = StartAnswer.MUTE;
        long readyBudget = 3_000;
        long started = Now.millis();
        Throwable notReady = catchThrowable(() -> ManagedDatabase.awaitReady(docker, "db-1",
            ManagedDatabase.Engine.REDIS, "app", "secret", "app", readyBudget));
        long took = Now.millis() - started;
        assertThat(notReady)
            .as("step 3: the wait gives up as not-ready, carrying the probe's named timeout")
            .isInstanceOf(IOException.class)
            .hasMessageContaining("Timed out waiting for")
            .hasMessageContaining("did not finish within");
        assertThat(took)
            .as("step 3: the wait kept its own %sms deadline (took %sms)", readyBudget, took)
            .isLessThan(readyBudget + 3_000);
    }

    private void acceptForever() {
        while (this.server.isOpen()) {
            SocketChannel channel;
            try {
                channel = this.server.accept();
            } catch (IOException closed) {
                return;
            }
            this.accepted.add(channel);
            this.daemons.startThread(() -> this.serve(channel));
        }
    }

    /** Answer one request by its route, or hold the connection silent for a wedged start. */
    private void serve(SocketChannel channel) {
        try (channel) {
            String head = readRequest(channel);
            String target = head.substring(0, head.indexOf("\r\n"));
            if (target.contains("/start ")) {
                StartAnswer answer = this.startAnswer;
                if (answer == StartAnswer.NORMAL) {
                    byte[] payload = "hi\n".getBytes(StandardCharsets.UTF_8);
                    ByteBuffer frame = ByteBuffer.allocate(8 + payload.length);
                    frame.put((byte) 1).put(new byte[3]).putInt(payload.length).put(payload).flip();
                    write(channel, ByteBuffer.wrap(("HTTP/1.1 200 OK\r\n"
                        + "Content-Type: application/vnd.docker.multiplexed-stream\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII)));
                    write(channel, frame);
                    return;
                }
                if (answer == StartAnswer.HEAD_THEN_NOTHING) {
                    write(channel, ByteBuffer.wrap(("HTTP/1.1 200 OK\r\n"
                        + "Content-Type: application/vnd.docker.multiplexed-stream\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII)));
                }
                // Wedged: never close from this side; the client's deadline is what ends it.
                ByteBuffer sink = ByteBuffer.allocate(256);
                while (channel.read(sink) != -1) {
                    sink.clear();
                }
                return;
            }
            String body = target.startsWith("POST") && target.contains("/exec ")
                ? "{\"Id\":\"exec-1\"}" : "{\"ExitCode\":0,\"Running\":false}";
            String status = target.startsWith("POST") ? "201 Created" : "200 OK";
            write(channel, ByteBuffer.wrap(("HTTP/1.1 " + status + "\r\nContent-Type: application/json\r\n"
                + "Content-Length: " + body.length() + "\r\nConnection: close\r\n\r\n" + body)
                .getBytes(StandardCharsets.US_ASCII)));
        } catch (IOException ended) {
            // the client gave up or the test tore the daemon down
        }
    }

    /** @return the request head; its Content-Length body is read and dropped */
    private static String readRequest(SocketChannel channel) throws IOException {
        StringBuilder head = new StringBuilder();
        ByteBuffer one = ByteBuffer.allocate(1);
        while (!head.toString().endsWith("\r\n\r\n")) {
            one.clear();
            if (channel.read(one) == -1) {
                throw new IOException("client closed before sending a request head");
            }
            head.append((char) (one.get(0) & 0xFF));
        }
        int length = 0;
        for (String line : head.toString().split("\r\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
            }
        }
        ByteBuffer body = ByteBuffer.allocate(length);
        while (body.hasRemaining()) {
            if (channel.read(body) == -1) {
                break;
            }
        }
        return head.toString();
    }

    private static void write(SocketChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }
}
