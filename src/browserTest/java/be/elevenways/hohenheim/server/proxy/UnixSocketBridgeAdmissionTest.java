package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.server.util.LoopbackPeers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The loopback bridge to an AF_UNIX upstream admits only sockets owned by this process's own
 * uid: another local account connecting to its loopback port is closed before a byte reaches
 * the socket, and the kernel-table reading that decides it refuses anything ambiguous.
 */
class UnixSocketBridgeAdmissionTest {

    @Test
    @Timeout(20)
    void onlyThisProcessUidIsSplicedOntoTheSocket() throws Exception {
        Path sockPath = Files.createTempFile("hh-bridge-admission", ".sock");
        Files.delete(sockPath);
        String body = "reached-the-socket";
        String httpResponse = "HTTP/1.1 200 OK\r\nContent-Length: " + body.length()
            + "\r\nConnection: close\r\n\r\n" + body;
        AtomicInteger requestsSeen = new AtomicInteger();

        ServerSocketChannel unixServer = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        unixServer.bind(UnixDomainSocketAddress.of(sockPath.toString()));
        Thread.ofVirtual().start(() -> {
            while (true) {
                try {
                    SocketChannel conn = unixServer.accept();
                    Thread.ofVirtual().start(() -> {
                        try (conn) {
                            ByteBuffer request = ByteBuffer.allocate(4096);
                            if (conn.read(request) > 0) {
                                requestsSeen.incrementAndGet();
                                conn.write(ByteBuffer.wrap(httpResponse.getBytes(StandardCharsets.UTF_8)));
                            }
                        } catch (IOException ignored) {
                        }
                    });
                } catch (IOException closed) {
                    return;
                }
            }
        });

        try {
            // Step 1: a bridge whose kernel reading names ANOTHER uid for the peer refuses it:
            // the connection closes and the socket never sees the request.
            UnixSocketBridge foreign = new UnixSocketBridge(sockPath.toString(), false,
                (peerPort, bridgePort) -> 4242, 1000);
            try {
                String answer = exchange(foreign.getPort());
                assertThat(answer).as("step 1: a foreign-uid peer gets nothing back").isEmpty();
                assertThat(requestsSeen.get())
                    .as("step 1: the refused request never reached the AF_UNIX upstream").isZero();
            } finally {
                foreign.close();
            }

            // Step 2: an unidentifiable peer (no table row) is refused the same way.
            UnixSocketBridge unknown = new UnixSocketBridge(sockPath.toString(), false,
                (peerPort, bridgePort) -> null, 1000);
            try {
                assertThat(exchange(unknown.getPort()))
                    .as("step 2: an unidentified peer is refused, never trusted").isEmpty();
                assertThat(requestsSeen.get()).as("step 2: still nothing reached the socket").isZero();
            } finally {
                unknown.close();
            }

            // Step 3: the same uid is spliced through.
            UnixSocketBridge own = new UnixSocketBridge(sockPath.toString(), false,
                (peerPort, bridgePort) -> 1000, 1000);
            try {
                assertThat(exchange(own.getPort()))
                    .as("step 3: this process's own uid reaches the upstream")
                    .contains("200 OK").contains(body);
                assertThat(requestsSeen.get()).as("step 3: exactly one request arrived").isEqualTo(1);
            } finally {
                own.close();
            }
        } finally {
            unixServer.close();
            Files.deleteIfExists(sockPath);
        }
    }

    @Test
    void theKernelTableReadingNamesTheOwnerOnlyWhenUnambiguous() {
        String header = "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt"
            + "   uid  timeout inode";
        // 127.0.0.1:40000 -> 127.0.0.1:5000, ESTABLISHED, uid 1000.
        String client = "   0: 0100007F:9C40 0100007F:1388 01 00000000:00000000 00:00000000 00000000"
            + "  1000        0 12345 1 0000000000000000 20 4 30 10 -1";
        // The accepted server-side twin (local 5000, remote 40000) must not be mistaken for it.
        String server = "   1: 0100007F:1388 0100007F:9C40 01 00000000:00000000 00:00000000 00000000"
            + "  1001        0 12346 1 0000000000000000 20 4 30 10 -1";

        // Step 1: the connecting socket's row names its owner.
        assertThat(LoopbackPeers.ownerIn(List.of(header, client, server), 40000, 5000))
            .as("step 1: the peer row (local=peer port, remote=bridge port) names uid 1000")
            .isEqualTo(1000);

        // Step 2: no row for the pair answers null (refuse), never a guess.
        assertThat(LoopbackPeers.ownerIn(List.of(header, server), 40000, 5000))
            .as("step 2: a missing row is unknown").isNull();

        // Step 3: a non-loopback row with the same ports is not a loopback peer.
        String remote = "   2: 0A000001:9C40 0100007F:1388 01 00000000:00000000 00:00000000 00000000"
            + "  1000        0 12347 1 0000000000000000 20 4 30 10 -1";
        assertThat(LoopbackPeers.ownerIn(List.of(header, remote), 40000, 5000))
            .as("step 3: only loopback rows count").isNull();

        // Step 4: two rows disagreeing about the owner are ambiguous and refused.
        String twin = "   3: 0200007F:9C40 0100007F:1388 01 00000000:00000000 00:00000000 00000000"
            + "  0        0 12348 1 0000000000000000 20 4 30 10 -1";
        assertThat(LoopbackPeers.ownerIn(List.of(header, client, twin), 40000, 5000))
            .as("step 4: disagreeing rows are ambiguous").isNull();
    }

    /** Send one request through the bridge; an empty string means the bridge closed on us. */
    private static String exchange(int port) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write("GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n"
                .getBytes(StandardCharsets.UTF_8));
            out.flush();
            InputStream in = socket.getInputStream();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (SocketException reset) {
            return "";
        }
    }
}
