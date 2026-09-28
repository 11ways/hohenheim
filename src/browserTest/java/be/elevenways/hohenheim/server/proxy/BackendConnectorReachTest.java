package be.elevenways.hohenheim.server.proxy;

import be.elevenways.zenit.server.net.OutboundNetwork;
import be.elevenways.zenit.test.support.OutboundFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A TLS passthrough route is vetted by zenit's outbound guard and dials only the addresses it
 * vetted: a tenant-owned route reaches public addresses only, an operator route keeps reaching
 * loopback, an address that refuses falls through to the next vetted one, and a verdict is never
 * answered under another outbound network. An operator route dials any name its resolver
 * accepts (a Docker-style backend_1 included) and never remembers a failed lookup.
 */
class BackendConnectorReachTest {

    @Test
    @Timeout(20)
    void aRouteDialsOnlyVettedAddressesAndFallsThroughARefusingOne() throws Exception {
        try (ServerSocket backend = listening(); ServerSocket other = listening()) {
            int port = backend.getLocalPort();

            // 1. An operator route (publicOnly=false) reaches the loopback backend.
            try (Socket operator = BackendConnector.connect("127.0.0.1", port, 2000, 1, false);
                 Socket ignored = backend.accept()) {
                assertThat(operator.isConnected())
                    .as("step 1: an operator route connects to loopback").isTrue();
            }

            // 2. The same target for a tenant route is refused before any connect.
            assertThatThrownBy(() -> BackendConnector.connect("127.0.0.1", port, 2000, 1, true))
                .as("step 2: a tenant route never dials loopback")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("refused");

            // 3. A NAME resolving to loopback is judged on its resolved addresses too.
            assertThatThrownBy(() -> BackendConnector.connect("localhost", port, 2000, 1, true))
                .as("step 3: a name resolving to loopback is refused for a tenant route")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("refused");

            // 4. A public name whose first vetted address refuses the connection: the next vetted
            //    address is dialed, and it is the backend that accepts.
            int refusing = closedPort();
            try (OutboundFixture fixture = OutboundFixture.route("passthrough.example.test", refusing, port)) {
                try (Socket tenant = BackendConnector.connect(fixture.host(), 443, 2000, 1, true);
                     Socket accepted = backend.accept()) {
                    assertThat(tenant.isConnected()).as("step 4: the tenant route connected").isTrue();
                    assertThat(accepted.getPort()).as("step 4: through the second vetted address")
                        .isEqualTo(tenant.getLocalPort());
                }
            }

            // 5. The same name routed by the next fixture to another backend right after: the verdict
            //    judged under the first network is not answered under this one.
            try (OutboundFixture next = OutboundFixture.route("passthrough.example.test", other.getLocalPort());
                 Socket tenant = BackendConnector.connect(next.host(), 443, 2000, 1, true);
                 Socket accepted = other.accept()) {
                assertThat(accepted.getPort()).as("step 5: the new fixture's backend accepted")
                    .isEqualTo(tenant.getLocalPort());
            }

            // 6. A Docker-style name with an underscore, which no URL host admits: an operator
            //    route dials whatever the resolver answers, a tenant route still refuses it.
            try (OutboundFixture docker = OutboundFixture.route("backend_1", port);
                 Socket operator = BackendConnector.connect("backend_1", 443, 2000, 1, false);
                 Socket accepted = backend.accept()) {
                assertThat(accepted.getPort()).as("step 6: the operator route reached backend_1")
                    .isEqualTo(operator.getLocalPort());
                assertThatThrownBy(() -> BackendConnector.connect(docker.host(), 443, 2000, 1, true))
                    .as("step 6: a tenant route keeps the guard's refusal")
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("refused");
            }

            // 7. Under ONE outbound network, a name that does not resolve yet fails, and the next
            //    connection after it starts resolving reaches the backend: the failure was not kept.
            Map<String, InetAddress> names = new ConcurrentHashMap<>();
            OutboundNetwork previous = OutboundNetwork.SEAM.require();
            OutboundNetwork.SEAM.install(new OutboundNetwork(name -> {
                InetAddress known = names.get(name);
                if (known == null) {
                    throw new UnknownHostException(name);
                }
                return new InetAddress[] {known};
            }, OutboundNetwork.DIRECT_ROUTE));
            try {
                assertThatThrownBy(() -> BackendConnector.connect("late_backend", port, 2000, 1, false))
                    .as("step 7: an unresolvable name fails the connection")
                    .isInstanceOf(UnknownHostException.class);
                names.put("late_backend", InetAddress.getByAddress("late_backend", new byte[] {127, 0, 0, 1}));
                try (Socket operator = BackendConnector.connect("late_backend", port, 2000, 1, false);
                     Socket accepted = backend.accept()) {
                    assertThat(accepted.getPort()).as("step 7: the name resolves now and is dialed")
                        .isEqualTo(operator.getLocalPort());
                }
            } finally {
                OutboundNetwork.SEAM.install(previous);
            }
        }
    }

    private static ServerSocket listening() throws IOException {
        ServerSocket socket = new ServerSocket();
        socket.bind(new InetSocketAddress("127.0.0.1", 0));
        return socket;
    }

    private static int closedPort() throws IOException {
        try (ServerSocket closed = listening()) {
            return closed.getLocalPort();
        }
    }
}
