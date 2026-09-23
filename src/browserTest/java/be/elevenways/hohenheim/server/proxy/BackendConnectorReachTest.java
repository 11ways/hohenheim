package be.elevenways.hohenheim.server.proxy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A TLS passthrough route of a tenant-owned site dials only public addresses, judged on the very
 * addresses the connector then connects to; an operator route keeps reaching loopback.
 */
class BackendConnectorReachTest {

    @Test
    @Timeout(20)
    void aPublicOnlyRouteNeverConnectsToANonPublicAddress() throws Exception {
        try (ServerSocket backend = new ServerSocket()) {
            backend.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = backend.getLocalPort();

            // Step 1: an operator route (publicOnly=false) reaches the loopback backend.
            try (Socket operator = BackendConnector.connect("127.0.0.1", port, 2000, 1, false)) {
                assertThat(operator.isConnected())
                    .as("step 1: an operator route connects to loopback").isTrue();
            }

            // Step 2: the same target for a tenant route is refused before any connect.
            assertThatThrownBy(() -> BackendConnector.connect("127.0.0.1", port, 2000, 1, true))
                .as("step 2: a tenant route never dials loopback")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("non-public");

            // Step 3: a NAME resolving to loopback is judged on its resolved addresses too.
            assertThatThrownBy(() -> BackendConnector.connect("localhost", port, 2000, 1, true))
                .as("step 3: a name resolving to loopback is refused for a tenant route")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("non-public");
        }
    }
}
