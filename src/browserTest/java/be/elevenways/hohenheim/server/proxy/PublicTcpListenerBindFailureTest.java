package be.elevenways.hohenheim.server.proxy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A start whose bind fails closes the server socket it created.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
class PublicTcpListenerBindFailureTest {

    @Test
    void aFailedBindLeavesNoOpenSocketAndTheNextStartBinds() throws Exception {
        AtomicReference<ServerSocket> created = new AtomicReference<>();
        PublicTcpListener listener;
        int port;
        try (ServerSocket occupant = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            port = occupant.getLocalPort();
            listener = new PublicTcpListener("127.0.0.1", port, 5_000,
                new InternalListenerRouter(() -> new InetSocketAddress("127.0.0.1", 1)),
                List::of, new ConnectionIdentities(), ip -> false, 10, 10, failure -> { });
            listener.setServerSocketFactoryForTesting(() -> {
                ServerSocket socket = new ServerSocket();
                created.set(socket);
                return socket;
            });

            // Step 1: the port is taken, so the bind throws.
            assertThatThrownBy(listener::start).as("step 1: the bind fails").isInstanceOf(IOException.class);
            assertThat(created.get().isClosed()).as("step 1: the socket it created is closed").isTrue();
            assertThat(listener.getLocalAddress()).as("step 1: the listener holds no socket").isNull();
        }

        // Step 2: once the port is free, the same listener starts.
        try {
            listener.start();
            assertThat(listener.getLocalAddress().getPort()).as("step 2: the retry bound the port").isEqualTo(port);
        } finally {
            listener.close();
        }
    }
}
