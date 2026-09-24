package be.elevenways.hohenheim.test.docker;

import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.UnixSocketDockerTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A streaming endpoint that answers with an ERROR over a kept-alive connection must refuse the
 * opener at once, over a REAL unix socket and the production {@link UnixSocketDockerTransport}.
 *
 * AIDEV-NOTE: the defect this pins. A stream request carries no {@code Connection: close}, so
 * the daemon answers a refused stream (the stats 404 of a vanished container is the common one)
 * and keeps the connection open. The error-body drain read to EOF in a BLOCKING read that its
 * own 2s deadline never interrupted, so every refused open cost the whole stream timeout (the
 * 60s default here; this test opens with 30s, so the defect reads as a ~30s step). A scripted
 * connection cannot show it: it returns EOF when its script runs out, which a daemon never does.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
class ContainerStreamErrorAnswerTest {

    /** The stream timeout the client opens with; the defect costs this much per refused open. */
    private static final long STREAM_TIMEOUT_MS = 30_000;

    /** A framed error must end by its framing: well inside the 2s error-body deadline. */
    private static final Duration FRAMED_BOUND = Duration.ofMillis(1_500);

    private Path directory;
    private ServerSocketChannel server;
    private final List<SocketChannel> accepted = new CopyOnWriteArrayList<>();

    @BeforeEach
    void listen() throws IOException {
        this.directory = Files.createTempDirectory("hohenheim-fake-docker");
        Path socket = this.directory.resolve("docker.sock");
        this.server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        this.server.bind(UnixDomainSocketAddress.of(socket));
    }

    @AfterEach
    void shutDown() throws IOException {
        for (SocketChannel channel : this.accepted) {
            channel.close();
        }
        this.server.close();
        Files.deleteIfExists(this.directory.resolve("docker.sock"));
        Files.deleteIfExists(this.directory);
    }

    @Test
    void anErrorAnswerOnAKeptAliveStreamRefusesTheOpenerAtOnce() throws Exception {
        DockerClient docker = new DockerClient(
            new UnixSocketDockerTransport(this.directory.resolve("docker.sock").toString()),
            STREAM_TIMEOUT_MS);

        // 1. The stats 404 of a vanished container: Content-Length framed, connection kept
        //    alive. The opener gets the daemon's status and reason as soon as the body is in.
        String notFound = "{\"message\":\"No such container: gone\"}\n";
        CountDownLatch firstReleased = this.answerOnce("HTTP/1.1 404 Not Found\r\n"
            + "Content-Type: application/json\r\n"
            + "Content-Length: " + notFound.getBytes(StandardCharsets.UTF_8).length + "\r\n"
            + "\r\n" + notFound);
        long started = System.nanoTime();
        Throwable refused = catchThrowable(() -> docker.followStats("gone"));
        Duration took = Duration.ofNanos(System.nanoTime() - started);
        assertThat(refused)
            .as("step 1: a 404 on a stream open is an ApiException")
            .isInstanceOf(DockerClient.ApiException.class)
            .hasMessageContaining("404")
            .hasMessageContaining("No such container: gone");
        assertThat(((DockerClient.ApiException) refused).isNotFound())
            .as("step 1: and it reads as not-found, so the stats lane can tell a gone container")
            .isTrue();
        assertThat(took)
            .as("step 1: the Content-Length ended the body; waiting on the kept-alive socket"
                + " is the defect (took %sms of a %sms stream timeout)", took.toMillis(), STREAM_TIMEOUT_MS)
            .isLessThan(FRAMED_BOUND);
        assertThat(firstReleased.await(5, TimeUnit.SECONDS))
            .as("step 1: the refused stream's connection is closed, never left open to the daemon")
            .isTrue();

        // 2. A CHUNKED error body with its last chunk, connection kept alive: the body ends at
        //    the last chunk and the message carries the decoded text, not the chunk sizes.
        String reason = "{\"message\":\"container is restarting\"}";
        String sizeLine = Integer.toHexString(reason.getBytes(StandardCharsets.UTF_8).length);
        CountDownLatch secondReleased = this.answerOnce("HTTP/1.1 409 Conflict\r\n"
            + "Transfer-Encoding: chunked\r\n"
            + "\r\n" + sizeLine + "\r\n" + reason + "\r\n0\r\n\r\n");
        started = System.nanoTime();
        refused = catchThrowable(() -> docker.followStats("busy"));
        took = Duration.ofNanos(System.nanoTime() - started);
        assertThat(refused)
            .as("step 2: a chunked 409 is an ApiException carrying the decoded reason")
            .isInstanceOf(DockerClient.ApiException.class)
            .hasMessageContaining("409")
            .hasMessageContaining("container is restarting")
            .hasMessageNotContaining(sizeLine + "\r\n");
        assertThat(((DockerClient.ApiException) refused).status())
            .as("step 2: the status survives").isEqualTo(409);
        assertThat(took)
            .as("step 2: the last chunk ended the body (took %sms)", took.toMillis())
            .isLessThan(FRAMED_BOUND);
        assertThat(secondReleased.await(5, TimeUnit.SECONDS))
            .as("step 2: and the connection is released").isTrue();

        // 3. An UNFRAMED error body on a connection that then goes mute: nothing says where the
        //    body ends, so the drain reads what arrives and is bounded by its own error-body
        //    deadline, never by the stream timeout.
        CountDownLatch thirdReleased = this.answerOnce("HTTP/1.1 500 Internal Server Error\r\n"
            + "\r\n{\"message\":\"daemon hiccup\"}");
        started = System.nanoTime();
        refused = catchThrowable(() -> docker.followStats("mute"));
        took = Duration.ofNanos(System.nanoTime() - started);
        assertThat(refused)
            .as("step 3: the unframed 500 still carries what the daemon said")
            .isInstanceOf(DockerClient.ApiException.class)
            .hasMessageContaining("500")
            .hasMessageContaining("daemon hiccup");
        assertThat(took)
            .as("step 3: a mute kept-alive connection costs the error-body deadline, not the"
                + " %sms stream timeout (took %sms)", STREAM_TIMEOUT_MS, took.toMillis())
            .isLessThan(Duration.ofSeconds(10));
        assertThat(thirdReleased.await(5, TimeUnit.SECONDS))
            .as("step 3: and the connection is released").isTrue();
    }

    /**
     * Accept ONE connection, read its request head, write {@code answer} and keep the connection
     * open like a keep-alive daemon.
     *
     * @return a latch released when the CLIENT closes that connection
     */
    private CountDownLatch answerOnce(String answer) {
        CountDownLatch released = new CountDownLatch(1);
        Thread daemon = new Thread(() -> {
            try {
                SocketChannel channel = this.server.accept();
                this.accepted.add(channel);
                readRequestHead(channel);
                ByteBuffer out = ByteBuffer.wrap(answer.getBytes(StandardCharsets.UTF_8));
                while (out.hasRemaining()) {
                    channel.write(out);
                }
                // Keep-alive: never close from this side. A read returning -1 is the client
                // hanging up, which is the release this test asserts.
                ByteBuffer sink = ByteBuffer.allocate(256);
                while (channel.read(sink) != -1) {
                    sink.clear();
                }
                released.countDown();
            } catch (IOException closed) {
                // the test tore the server down, or the client reset the connection
                released.countDown();
            }
        }, "fake-docker-keepalive");
        daemon.setDaemon(true);
        daemon.start();
        return released;
    }

    private static void readRequestHead(SocketChannel channel) throws IOException {
        StringBuilder head = new StringBuilder();
        ByteBuffer one = ByteBuffer.allocate(1);
        while (!head.toString().endsWith("\r\n\r\n")) {
            one.clear();
            if (channel.read(one) == -1) {
                throw new IOException("client closed before sending a request head");
            }
            head.append((char) (one.get(0) & 0xFF));
        }
    }
}
