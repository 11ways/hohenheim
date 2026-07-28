package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.server.process.IpcChannel;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The process IPC channel as a cross-tenant boundary: managed children run under
 * DISTINCT system users, so every local user can reach the loopback port. Only the
 * child holding HOHENHEIM_IPC_TOKEN may drive the channel, and no other peer can
 * wedge it.
 */
class IpcChannelAuthTest {

    private static Socket connect(IpcChannel channel) throws Exception {
        Socket socket = new Socket(InetAddress.getLoopbackAddress(), channel.getPort());
        socket.setSoTimeout(5_000);
        return socket;
    }

    private static void write(Socket socket, String line) throws Exception {
        OutputStream out = socket.getOutputStream();
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static String auth(String token) {
        return "{\"type\":\"auth\",\"token\":\"" + token + "\"}";
    }

    /** @return the next line, or null when the parent hung the connection up */
    private static String readLine(Socket socket) throws Exception {
        return new BufferedReader(new InputStreamReader(
            socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
    }

    private static void awaitCondition(String what, java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for: " + what);
    }

    /**
     * Walks one channel through a hostile local neighbourhood: a squatter that
     * connects first, an unauthenticated caller, the real child, and a second
     * caller that knows the token.
     */
    @Test
    void onlyTheTokenHolderDrivesTheChannelAndNoPeerCanWedgeIt() throws Exception {
        List<Map<String, Object>> received = new CopyOnWriteArrayList<>();

        try (IpcChannel channel = new IpcChannel()) {
            channel.setMessageHandler(received::add);
            channel.startAccepting();
            assertThat(channel.getSecret()).as("a per-channel secret exists").isNotBlank();

            // 1. Another tenant connects FIRST and says nothing. Under a single
            //    accept() this alone owned the channel forever.
            Socket squatter = connect(channel);

            // 2. ...and a second one tries the old unauthenticated protocol directly.
            //    It is refused: the parent hangs up and the message never lands.
            try (Socket forger = connect(channel)) {
                write(forger, "{\"type\":\"remcache_set\",\"key\":\"stolen\",\"value\":\"x\"}");
                assertThat(readLine(forger)).as("an unauthenticated peer is hung up on").isNull();
            }
            try (Socket forger = connect(channel)) {
                write(forger, auth("not-the-secret"));
                assertThat(readLine(forger)).as("a wrong token is hung up on").isNull();
            }
            assertThat(channel.isConnected()).as("no peer is attached yet").isFalse();
            assertThat(received).as("nothing an unauthenticated peer sent was handled").isEmpty();

            // 3. The real child connects with its token and the channel works, squatter
            //    and forgers notwithstanding.
            try (Socket child = connect(channel)) {
                write(child, auth(channel.getSecret()));
                assertThat(channel.waitForConnection(5_000))
                    .as("the token holder attaches").isTrue();

                write(child, "{\"type\":\"ready\"}");
                awaitCondition("the child's message arrived", () -> !received.isEmpty());
                assertThat(received.get(0)).containsEntry("type", "ready");

                // 4. A LATER peer holding the token cannot displace the attached child.
                try (Socket usurper = connect(channel)) {
                    write(usurper, auth(channel.getSecret()));
                    assertThat(readLine(usurper))
                        .as("a second peer never takes the attached channel").isNull();
                }

                // 5. The victim's channel is untouched in both directions.
                write(child, "{\"type\":\"remcache_remove\",\"key\":\"mine\"}");
                awaitCondition("the child is still served", () -> received.size() == 2);
                assertThat(received.get(1)).containsEntry("type", "remcache_remove");

                channel.send(Map.of("type", "broadcast"));
                assertThat(readLine(child))
                    .as("parent to child still flows").contains("broadcast");
                assertThat(channel.isConnected()).isTrue();
            }

            // 6. When the child goes away the channel accepts again, so a crashed or
            //    restarted child reconnects instead of finding a dead socket.
            awaitCondition("the closed peer was released", () -> !channel.isConnected());
            try (Socket replacement = connect(channel)) {
                write(replacement, auth(channel.getSecret()));
                write(replacement, "{\"type\":\"ready\"}");
                awaitCondition("the reconnecting child is served", () -> received.size() == 3);
                assertThat(received.get(2)).containsEntry("type", "ready");
            }

            squatter.close();
        }
    }
}
