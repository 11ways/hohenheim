package be.elevenways.hohenheim.server.incus;

import be.elevenways.protoblast.common.thread.JobRunner;
import be.elevenways.zenit.test.support.RawWebSocketUpgrade;
import be.elevenways.zenit.test.support.WebSocketPeerFrames;
import be.elevenways.zenit.test.support.WebSocketPeerFrames.ClientFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The Incus websocket lane over the local unix socket, against a fake daemon: the upgrade, a message each way, a
 * fragmented message with a ping between its fragments, and the daemon's close, all through zenit's
 * {@code WebSocketStream} under {@link StreamIncusTransport}.
 *
 * @author Jelle De Loecker
 * @since 0.10.0
 */
class StreamIncusTransportWebSocketTest {

    private static final Pattern KEY = Pattern.compile("(?im)^Sec-WebSocket-Key:\\s*(\\S+)\\s*$");

    @TempDir
    Path dir;

    @Test
    void theWebSocketLaneSpeaksRfc6455OverTheUnixSocketJourney() throws Exception {
        Path socketPath = this.dir.resolve("incus.socket");
        JobRunner jobs = JobRunner.create("fake-incus-websocket");
        try (ServerSocketChannel daemon = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            daemon.bind(UnixDomainSocketAddress.of(socketPath));
            UnixIncusTransport transport = new UnixIncusTransport(socketPath.toString());

            // 1. A refused upgrade is an IOException naming the daemon and the status, never a socket.
            CompletableFuture<Peer> refusing = serve(jobs, daemon,
                head -> ascii("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n"));
            Throwable refused = catchThrowable(() -> transport.openWebSocket("/1.0/operations/op-0/websocket", 5_000));
            assertThat(refused).as("step 1: the refusal").isInstanceOf(IOException.class)
                .hasMessageContaining("unix://" + socketPath).hasMessageContaining("status 403");
            try (Peer peer = refusing.get(10, TimeUnit.SECONDS)) {
                assertThat(peer.in.read()).as("step 1: the refused channel was closed").isEqualTo(-1);
            }

            // 2. The upgrade: the daemon's first frame rides in the same write as its 101 head and is not lost.
            CompletableFuture<Peer> accepting = serve(jobs, daemon, head -> {
                ByteArrayOutputStream answer = new ByteArrayOutputStream();
                answer.writeBytes(ascii(WebSocketPeerFrames.upgradeHead(keyOf(head)) + "\r\n"));
                answer.writeBytes(WebSocketPeerFrames.frame(true, WebSocketPeerFrames.TEXT, utf8("{\"ready\":true}")));
                return answer.toByteArray();
            });
            IncusWebSocket socket = transport.openWebSocket("/1.0/operations/op-1/websocket?secret=s3cret", 5_000);
            try (Peer peer = accepting.get(10, TimeUnit.SECONDS)) {
                assertThat(peer.head).as("step 2: the upgrade request")
                    .startsWith("GET /1.0/operations/op-1/websocket?secret=s3cret HTTP/1.1\r\n")
                    .contains("Host: incus\r\n").containsIgnoringCase("upgrade: websocket")
                    .contains("Sec-WebSocket-Version: 13");

                // 3. A text frame from the daemon arrives as its bytes; a send reaches it as one masked binary frame.
                assertThat(socket.receive()).as("step 3: the daemon's text").isEqualTo(utf8("{\"ready\":true}"));
                socket.send(utf8("ls -la\n"));
                ClientFrame sent = WebSocketPeerFrames.readClientFrame(peer.in);
                assertThat(sent.opcode()).as("step 3: a binary frame").isEqualTo(WebSocketPeerFrames.BINARY);
                assertThat(sent.fin()).as("step 3: one whole frame").isTrue();
                assertThat(sent.payload()).as("step 3: its payload").isEqualTo(utf8("ls -la\n"));

                // 4. A fragmented message with a ping between its fragments: one pong, one joined message.
                peer.send(WebSocketPeerFrames.frame(false, WebSocketPeerFrames.BINARY, utf8("con")));
                peer.send(WebSocketPeerFrames.frame(true, WebSocketPeerFrames.PING, utf8("still there?")));
                peer.send(WebSocketPeerFrames.frame(true, WebSocketPeerFrames.CONTINUATION, utf8("sole")));
                assertThat(socket.receive()).as("step 4: the joined message").isEqualTo(utf8("console"));
                ClientFrame pong = WebSocketPeerFrames.readClientFrame(peer.in);
                assertThat(pong.opcode()).as("step 4: the ping was answered").isEqualTo(WebSocketPeerFrames.PONG);
                assertThat(pong.payload()).as("step 4: with its payload").isEqualTo(utf8("still there?"));

                // 5. The daemon closes: the close is echoed, the pull ends in order and the channel is released.
                peer.send(WebSocketPeerFrames.frame(true, WebSocketPeerFrames.CLOSE, new byte[] {0x03, (byte) 0xE8}));
                assertThat(socket.receive()).as("step 5: an orderly end").isNull();
                ClientFrame echo = WebSocketPeerFrames.readClientFrame(peer.in);
                assertThat(echo.closeCode()).as("step 5: the close echoed").isEqualTo(1000);
                assertThat(peer.in.read()).as("step 5: the channel closed").isEqualTo(-1);
                assertThat(socket.receive()).as("step 5: still ended").isNull();
                socket.close();
            }
        } finally {
            jobs.shutdownNow();
        }
    }

    /** One accepted connection of the fake daemon, after it read the request head and wrote its answer. */
    private record Peer(SocketChannel channel, InputStream in, OutputStream out, String head)
        implements AutoCloseable {

        void send(byte[] bytes) throws IOException {
            this.out.write(bytes);
            this.out.flush();
        }

        @Override
        public void close() throws IOException {
            this.channel.close();
        }
    }

    private static CompletableFuture<Peer> serve(JobRunner jobs, ServerSocketChannel daemon,
                                                 Function<String, byte[]> answer) {
        CompletableFuture<Peer> peer = new CompletableFuture<>();
        jobs.runOnThread(() -> {
            try {
                SocketChannel accepted = daemon.accept();
                InputStream in = Channels.newInputStream(accepted);
                OutputStream out = Channels.newOutputStream(accepted);
                String head = RawWebSocketUpgrade.readHead(in);
                out.write(answer.apply(head));
                out.flush();
                peer.complete(new Peer(accepted, in, out, head));
            } catch (IOException | RuntimeException failed) {
                peer.completeExceptionally(failed);
            }
        });
        return peer;
    }

    private static String keyOf(String head) {
        Matcher key = KEY.matcher(head.replace("\r\n", "\n"));
        assertThat(key.find()).as("the request carries a Sec-WebSocket-Key").isTrue();
        return key.group(1);
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
