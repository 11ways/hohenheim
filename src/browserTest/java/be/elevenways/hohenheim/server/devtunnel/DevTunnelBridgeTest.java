package be.elevenways.hohenheim.server.devtunnel;

import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.zenit.common.routing.ParameterDefinition;
import be.elevenways.zenit.common.websocket.WebSocketSession;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dev-tunnel bridge's loopback listener admits only this process's own uid.
 *
 * AIDEV-NOTE: the defect this pins. The bridge spliced whatever connected to its loopback
 * port onto the tunnel, so any local account on the controller host could reach a
 * developer's machine past the proxy's access rules. The owner lookup is injected here: a
 * second real uid is not available to a test, and the kernel-table reader itself is
 * UnixSocketBridge's, tested there.
 */
class DevTunnelBridgeTest {

    private static final int SELF = 4242;

    @BeforeAll
    static void boot() throws Exception {
        TestDatabases.freshDatasource();
        HohenheimTestRuntime.ensureBooted();
    }

    @Test
    void onlyThisProcesssUidReachesTheTunnel() throws Exception {
        // 1. A peer owned by ANOTHER uid is closed before any stream opens.
        RecordingSession foreignSession = new RecordingSession();
        DevTunnelServerHandler foreignHandler = new DevTunnelServerHandler(foreignSession);
        DevTunnelBridge refusing = new DevTunnelBridge(foreignHandler, (peer, local) -> 1001, SELF);
        try (Socket socket = new Socket("127.0.0.1", refusing.getPort())) {
            assertThat(readsEof(socket))
                .as("step 1: the foreign peer's connection is closed by the bridge").isTrue();
        } finally {
            refusing.close();
        }
        assertThat(foreignSession.texts)
            .as("step 1: and no tunnel stream was opened for it").isEmpty();

        // 2. A peer whose owner cannot be read is refused the same way: unknown fails closed.
        RecordingSession unknownSession = new RecordingSession();
        DevTunnelBridge unknown = new DevTunnelBridge(new DevTunnelServerHandler(unknownSession),
            (peer, local) -> null, SELF);
        try (Socket socket = new Socket("127.0.0.1", unknown.getPort())) {
            assertThat(readsEof(socket))
                .as("step 2: an unidentifiable peer is closed too").isTrue();
        } finally {
            unknown.close();
        }
        assertThat(unknownSession.texts)
            .as("step 2: and got no stream either").isEmpty();

        // 3. POSITIVE ANCHOR: a peer owned by this process's uid opens a tunnel stream, so
        //    steps 1 and 2 were the admission check and not a bridge that opens nothing.
        RecordingSession ownSession = new RecordingSession();
        DevTunnelServerHandler ownHandler = new DevTunnelServerHandler(ownSession);
        DevTunnelBridge admitting = new DevTunnelBridge(ownHandler, (peer, local) -> SELF, SELF);
        try (Socket socket = new Socket("127.0.0.1", admitting.getPort())) {
            assertThat(ownSession.opened.await(5, TimeUnit.SECONDS))
                .as("step 3: this process's own peer opened a tunnel stream").isTrue();
            assertThat(ownSession.texts.get(0))
                .as("step 3: announced to the tunnel client as an open frame").contains("open");
        } finally {
            admitting.close();
            ownHandler.onClose(1000, "test finished");
            foreignHandler.onClose(1000, "test finished");
        }
    }

    /** Whether the bridge closes the connection: EOF within the window, not a timeout. */
    private static boolean readsEof(Socket socket) throws IOException {
        socket.setSoTimeout(5_000);
        InputStream in = socket.getInputStream();
        try {
            return in.read() == -1;
        } catch (SocketTimeoutException stillOpen) {
            return false;
        } catch (IOException reset) {
            // A reset is the bridge closing too.
            return true;
        }
    }

    /** A session that records the control frames the handler sends. */
    private static final class RecordingSession implements WebSocketSession {

        final List<String> texts = new CopyOnWriteArrayList<>();
        final CountDownLatch opened = new CountDownLatch(1);

        @Override
        public <T> T getParameter(ParameterDefinition<T> parameter) {
            return null;
        }

        @Override
        public void sendText(String message) {
            this.texts.add(message);
            this.opened.countDown();
        }

        @Override
        public void sendBinary(byte[] data) {
        }

        @Override
        public void close() {
        }

        @Override
        public void close(int code, String reason) {
        }

        @Override
        public boolean isOpen() {
            return true;
        }
    }
}
