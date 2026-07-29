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

    /**
     * @return true when the parent is still HOLDING the peer (a short read times out
     *         because the parent is blocked awaiting the handshake), false when the
     *         parent has DROPPED it (the read hits EOF because the socket was closed)
     */
    private static boolean stillHeld(Socket socket) throws Exception {
        socket.setSoTimeout(300);
        try {
            return socket.getInputStream().read() != -1;
        } catch (java.net.SocketTimeoutException held) {
            return true;
        }
    }

    /**
     * Saturates the pre-auth cap with stalling peers WHILE a child is attached, the
     * case the happy-path test never exercises. It proves all three properties of
     * the fix: (i) an already-attached child keeps working under a full cap, (ii) a
     * newly starting child gets through once a stall is reaped, and (iii) the stalls
     * are reaped at the auth timeout rather than wedging the channel forever.
     *
     * AIDEV-NOTE: the crisp counterfactual is the EIGHTH concurrent stall. With the
     * fix the attached child holds NO pre-auth slot, so all 8 stalls are held at
     * once; against the pre-fix code the attached child held a slot for its lifetime
     * (effective budget 7), so the eighth stall is dropped and this step fails.
     */
    @Test
    void anAttachedChildSurvivesAFullPreAuthCapAndAReplacementGetsThroughAfterReaping() throws Exception {
        List<Map<String, Object>> received = new CopyOnWriteArrayList<>();
        List<Socket> stalls = new CopyOnWriteArrayList<>();

        try (IpcChannel channel = new IpcChannel()) {
            channel.setMessageHandler(received::add);
            channel.startAccepting();

            // 1. The real child attaches and is served.
            Socket child = connect(channel);
            write(child, auth(channel.getSecret()));
            assertThat(channel.waitForConnection(5_000)).as("1. the child attaches").isTrue();
            write(child, "{\"type\":\"ready\"}");
            awaitCondition("1. the child's ready arrived", () -> !received.isEmpty());

            // 2. Eight stalling peers connect and say NOTHING, saturating the entire
            //    pre-auth cap (8) alongside the attached child. Give the parent a
            //    moment to accept each into its handshake vthread.
            for (int i = 0; i < 8; i++) {
                stalls.add(connect(channel));
            }
            Thread.sleep(300);

            // 3. THE COUNTERFACTUAL: every one of the eight stalls is still held.
            //    Only possible when the attached child consumes no pre-auth budget.
            for (int i = 0; i < stalls.size(); i++) {
                assertThat(stillHeld(stalls.get(i)))
                    .as("3. stall #" + i + " is held, so the attached child holds no pre-auth slot")
                    .isTrue();
            }

            // 4. The attached child keeps working with every pre-auth slot occupied.
            write(child, "{\"type\":\"remcache_remove\",\"key\":\"mine\"}");
            awaitCondition("4. the attached child is still served under a full cap",
                () -> received.size() == 2);
            assertThat(received.get(1)).containsEntry("type", "remcache_remove");
            assertThat(channel.isConnected()).as("4. the child stays attached under a full cap").isTrue();

            // 5. The stalls are eventually REAPED at the auth timeout: a peer that
            //    never authenticates does not hold its slot forever. After the reap
            //    window, a fresh child (the bounded-reconnect wrapper's retry) gets
            //    through where it was refused under saturation.
            child.close();
            awaitCondition("5. the closed child released the channel", () -> !channel.isConnected());

            // Detect re-attach by a message ARRIVING (received grows to 3), not by
            // waitForConnection: that latch is one-shot and already fired for the
            // first child, so it would report success without a real re-attach.
            boolean reattached = false;
            for (int attempt = 0; attempt < 40 && !reattached; attempt++) {
                Socket replacement = null;
                try {
                    replacement = new Socket(InetAddress.getLoopbackAddress(), channel.getPort());
                    replacement.setSoTimeout(1_000);
                    write(replacement, auth(channel.getSecret()));
                    write(replacement, "{\"type\":\"ready\"}");
                    for (int poll = 0; poll < 12 && received.size() < 3; poll++) {
                        Thread.sleep(50);
                    }
                    reattached = received.size() == 3;
                } catch (Exception retryable) {
                    // refused while the cap is still saturated; the stalls reap shortly
                } finally {
                    if (replacement != null && !reattached) replacement.close();
                }
                if (!reattached) Thread.sleep(400);
            }
            assertThat(reattached)
                .as("5. a replacement child re-attaches once the stalls are reaped")
                .isTrue();
            assertThat(received.get(2)).containsEntry("type", "ready");

            // 6. Reaping is complete: the stalls the parent dropped are now closed.
            boolean anyReaped = false;
            for (Socket stall : stalls) {
                if (!stillHeld(stall)) {
                    anyReaped = true;
                    break;
                }
            }
            assertThat(anyReaped).as("6. stalling peers are eventually reaped").isTrue();
        } finally {
            for (Socket stall : stalls) {
                try { stall.close(); } catch (Exception ignored) {}
            }
        }
    }
}
