package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.server.sitetype.UnixSocketBridgeConnection;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the loopback TCP-to-AF_UNIX bridge: an HTTP request to the bridge's loopback port reaches
 * an AF_UNIX HTTP server and the response is spliced back verbatim.
 */
class UnixSocketBridgeTest {

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

        // AF_UNIX HTTP server: accept one connection, read the request, write a fixed response.
        Thread.ofVirtual().start(() -> {
            try (SocketChannel conn = unixServer.accept()) {
                conn.read(ByteBuffer.allocate(4096));   // consume the request
                conn.write(ByteBuffer.wrap(httpResponse.getBytes(StandardCharsets.UTF_8)));
            } catch (IOException ignored) {
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
}
