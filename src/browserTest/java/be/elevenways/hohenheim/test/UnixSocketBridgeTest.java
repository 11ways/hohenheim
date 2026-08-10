package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.server.sitetype.UnixSocketBridgeConnection;
import be.elevenways.hohenheim.server.proxy.UnixSocketBridge;
import be.elevenways.hohenheim.server.proxy.UnixSocketListenerBridge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the loopback TCP-to-AF_UNIX bridge: an HTTP request to the bridge's loopback port reaches
 * an AF_UNIX HTTP server and the response is spliced back verbatim.
 */
class UnixSocketBridgeTest {

    /**
     * A bridge to a path with no listener must FAIL construction rather than bind a loopback
     * listener + accept thread that {@code ProxySiteType.bridgeFor} then caches forever.
     *
     * AIDEV-NOTE: this is the counterfactual for the leak the proxy wave named. Pre-fix the
     * constructor never dialed the AF_UNIX path, so this returned a live (dead) bridge that was
     * cached permanently -- one leaked listener + accept thread per distinct, Host-derived path.
     */
    @Test
    @Timeout(15)
    void aNonexistentSocketPathFailsConstructionInsteadOfCachingADeadBridge() throws Exception {
        Path missing = Files.createTempDirectory("hh-bridge-missing").resolve("nope.sock");
        assertThat(Files.exists(missing)).as("the socket path does not exist").isFalse();

        // verifyReachable=true is the mode ProxySiteType.bridgeFor uses before caching.
        assertThatThrownBy(() -> new UnixSocketBridge(missing.toString(), true))
            .as("an unreachable upstream fails construction, so no dead bridge is ever cached")
            .isInstanceOf(IOException.class);

        // The unverified constructor (process-lifecycle caller) still tolerates a not-yet-bound
        // path -- it must not fail a spawn whose child is still binding. It is closed at once.
        UnixSocketBridge lenient = new UnixSocketBridge(missing.toString());
        lenient.close();
    }

    @Test
    @Timeout(15)
    void bridgesHttpFromLoopbackTcpToAfUnix() throws Exception {
        Path sockPath = Files.createTempFile("hh-bridge-test", ".sock");
        Files.delete(sockPath);   // bind() requires the path not to exist
        sockPath.toFile().deleteOnExit();

        String body = "hello-from-afunix";
        String httpResponse = "HTTP/1.1 200 OK\r\n"
            + "Content-Length: " + body.length() + "\r\n"
            + "Connection: close\r\n\r\n"
            + body;

        ServerSocketChannel unixServer = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        unixServer.bind(UnixDomainSocketAddress.of(sockPath.toString()));

        // AF_UNIX HTTP server: loop-accept and answer each connection. The bridge now makes a
        // reachability PROBE connection at construction (closed immediately), so a single-accept
        // server would leave the real request with nothing to accept.
        Thread.ofVirtual().start(() -> {
            while (true) {
                try {
                    SocketChannel conn = unixServer.accept();
                    Thread.ofVirtual().start(() -> {
                        try (conn) {
                            conn.read(ByteBuffer.allocate(4096));   // consume the request
                            conn.write(ByteBuffer.wrap(httpResponse.getBytes(StandardCharsets.UTF_8)));
                        } catch (IOException ignored) {
                        }
                    });
                } catch (IOException closed) {
                    return;   // server closed at teardown
                }
            }
        });

        UnixSocketBridgeConnection conn = new UnixSocketBridgeConnection(sockPath.toString(), false);
        try {
            int port = conn.connectUri().getPort();
            assertThat(port).isGreaterThan(0);

            try (Socket client = new Socket()) {
                client.connect(new InetSocketAddress("127.0.0.1", port), 5000);
                OutputStream out = client.getOutputStream();
                out.write("GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.UTF_8));
                out.flush();

                String response = new String(client.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertThat(response).contains("200 OK").contains(body);
            }
        } finally {
            conn.close();
            unixServer.close();
        }
    }

    @Test
    @Timeout(15)
    void bridgesHttpFromAfUnixToLoopbackTcp() throws Exception {
        Path sockPath = Files.createTempFile("hh-front-bridge-test", ".sock");
        Files.delete(sockPath);

        String body = "hello-from-loopback";
        String response = "HTTP/1.1 200 OK\r\nContent-Length: " + body.length()
            + "\r\nConnection: close\r\n\r\n" + body;
        ServerSocketChannel tcpServer = ServerSocketChannel.open();
        tcpServer.bind(new InetSocketAddress("127.0.0.1", 0));
        int port = ((InetSocketAddress) tcpServer.getLocalAddress()).getPort();

        Thread.ofVirtual().start(() -> {
            try (SocketChannel conn = tcpServer.accept()) {
                conn.read(ByteBuffer.allocate(4096));
                conn.write(ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8)));
            } catch (IOException ignored) {
            }
        });

        UnixSocketListenerBridge bridge = new UnixSocketListenerBridge(sockPath, port, "0660");
        assertThat(Files.getPosixFilePermissions(sockPath)).containsExactlyInAnyOrder(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_WRITE);
        try (SocketChannel client = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            client.connect(UnixDomainSocketAddress.of(sockPath));
            client.write(ByteBuffer.wrap(
                "GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.UTF_8)));
            ByteBuffer buffer = ByteBuffer.allocate(4096);
            while (client.read(buffer) != -1 && buffer.hasRemaining()) {
                // Read until the bridge closes the connection or the buffer fills.
            }
            buffer.flip();
            String received = StandardCharsets.UTF_8.decode(buffer).toString();
            assertThat(received).contains("200 OK").contains(body);
        } finally {
            bridge.close();
            tcpServer.close();
        }
        assertThat(sockPath).doesNotExist();
    }

    @Test
    @Timeout(15)
    void clientHalfCloseStillReceivesTheFullResponse() throws Exception {
        Path sockPath = Files.createTempFile("hh-halfclose-test", ".sock");
        Files.delete(sockPath);

        String body = "response-after-half-close";
        String response = "HTTP/1.1 200 OK\r\nContent-Length: " + body.length()
            + "\r\nConnection: close\r\n\r\n" + body;
        ServerSocketChannel tcpServer = ServerSocketChannel.open();
        tcpServer.bind(new InetSocketAddress("127.0.0.1", 0));
        int port = ((InetSocketAddress) tcpServer.getLocalAddress()).getPort();

        // The backend only answers AFTER it has seen the client's EOF, so the
        // response can only arrive if the bridge propagates the half-close
        // instead of tearing the whole splice down.
        Thread.ofVirtual().start(() -> {
            try (SocketChannel conn = tcpServer.accept()) {
                ByteBuffer sink = ByteBuffer.allocate(4096);
                while (conn.read(sink) != -1) {
                    sink.clear();
                }
                conn.write(ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8)));
            } catch (IOException ignored) {
            }
        });

        UnixSocketListenerBridge bridge = new UnixSocketListenerBridge(sockPath, port, "0660");
        try (SocketChannel client = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            client.connect(UnixDomainSocketAddress.of(sockPath));
            client.write(ByteBuffer.wrap(
                "GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.UTF_8)));
            client.shutdownOutput();
            ByteBuffer buffer = ByteBuffer.allocate(4096);
            while (client.read(buffer) != -1 && buffer.hasRemaining()) {
                // Read until the bridge closes the connection or the buffer fills.
            }
            buffer.flip();
            String received = StandardCharsets.UTF_8.decode(buffer).toString();
            assertThat(received).contains("200 OK").contains(body);
        } finally {
            bridge.close();
            tcpServer.close();
        }
    }
}
