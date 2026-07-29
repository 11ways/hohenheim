package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.server.process.ChildWrapper;
import be.elevenways.hohenheim.server.process.IpcChannel;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real {@link IpcChannel} against the real wrapper and a ONE-SHOT child: a hostile
 * neighbourhood saturates the pre-auth cap while the child boots, and the child's
 * single {@code ready} must still arrive exactly once after the stalls clear. The old
 * pair of tests could not observe this: the channel test's "child" was a raw Socket
 * (it wrote auth first because the test wrote it first), and the wrapper test's child
 * emitted ready every 200ms BECAUSE a message sent while disconnected was dropped --
 * the defect written down as the test's premise.
 */
class IpcChannelChildJourneyTest {

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

    private static Socket connect(IpcChannel channel) throws Exception {
        Socket socket = new Socket(InetAddress.getLoopbackAddress(), channel.getPort());
        socket.setSoTimeout(5_000);
        return socket;
    }

    private static void awaitCondition(String what, java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for: " + what);
    }

    /**
     * AIDEV-NOTE: the crisp counterfactual is the EIGHTH concurrent stall. With the
     * fix the attached child holds NO pre-auth slot, so all 8 stalls are held at
     * once; against the pre-fix code the attached child held a slot for its lifetime
     * (effective budget 7), so the eighth stall is dropped and this step fails.
     */
    @Test
    void aOneShotReadySurvivesASaturatedPreAuthCapAndTheAttachedChildHoldsNoSlot() throws Exception {
        List<Map<String, Object>> received = new CopyOnWriteArrayList<>();
        List<Socket> preStalls = new CopyOnWriteArrayList<>();
        List<Socket> postStalls = new CopyOnWriteArrayList<>();
        Process proc = null;

        try (IpcChannel channel = new IpcChannel()) {
            channel.setMessageHandler(received::add);
            channel.startAccepting();

            // 1. Eight stalling peers saturate the ENTIRE pre-auth cap before the
            //    child exists: every further accepted connection is dropped at once.
            for (int i = 0; i < 8; i++) {
                preStalls.add(connect(channel));
            }
            Thread.sleep(300);

            // 2. Launch the real wrapper around a ONE-SHOT child that emits ready
            //    exactly once, immediately, plus one delayed follow-up. Its early
            //    connects are refused by the saturated cap.
            Path childScript = Files.createTempFile("hh-cap-child", ".js");
            Files.writeString(childScript,
                "process.send({type:'ready'});"
                + "setTimeout(function(){process.send({type:'remcache_remove',key:'later'});},6000);"
                + "setTimeout(function(){process.exit(0);},30000);");
            childScript.toFile().deleteOnExit();
            ProcessBuilder pb = new ProcessBuilder("node", ChildWrapper.path().toString(),
                "--hh-exec", childScript.toString());
            pb.environment().put("HOHENHEIM_IPC_PORT", String.valueOf(channel.getPort()));
            pb.environment().put("HOHENHEIM_IPC_TOKEN", channel.getSecret());
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            proc = pb.start();

            // Long enough for the wrapper's first connect attempts to be refused
            // while the cap is full; the ready emitted here must not be lost.
            Thread.sleep(1_500);
            assertThat(received)
                .as("2. nothing reached the handler while the cap was saturated")
                .isEmpty();

            // 3. Release the stalls. The wrapper's bounded reconnect gets through,
            //    authenticates first, and replays the ONE-SHOT ready exactly once.
            for (Socket stall : preStalls) {
                stall.close();
            }
            awaitCondition("3. the one-shot ready arrived after the stalls cleared",
                () -> received.stream().anyMatch(msg -> "ready".equals(msg.get("type"))));
            Thread.sleep(500);
            assertThat(received.stream().filter(msg -> "ready".equals(msg.get("type"))).count())
                .as("3. the handler received exactly one ready")
                .isEqualTo(1);
            assertThat(channel.isConnected()).as("3. the real child is attached").isTrue();

            // 4. With the child attached, open 8 FRESH stalls and assert ALL EIGHT are
            //    held: the attached child consumes no pre-auth budget (see AIDEV-NOTE).
            for (int i = 0; i < 8; i++) {
                postStalls.add(connect(channel));
            }
            Thread.sleep(300);
            for (int i = 0; i < postStalls.size(); i++) {
                assertThat(stillHeld(postStalls.get(i)))
                    .as("4. stall #" + i + " is held, so the attached child holds no pre-auth slot")
                    .isTrue();
            }

            // 5. The attached child keeps being served under the full cap: its delayed
            //    follow-up arrives through the same authenticated connection.
            awaitCondition("5. the delayed follow-up arrived under a full cap",
                () -> received.stream().anyMatch(msg -> "remcache_remove".equals(msg.get("type"))));
            assertThat(received.stream().filter(msg -> "ready".equals(msg.get("type"))).count())
                .as("5. still exactly one ready after the follow-up")
                .isEqualTo(1);

            // 6. Stalls that never authenticate are reaped at the auth timeout instead
            //    of holding their slot forever (leave them open and wait it out).
            boolean anyReaped = false;
            for (int attempt = 0; attempt < 30 && !anyReaped; attempt++) {
                Thread.sleep(500);
                for (Socket stall : postStalls) {
                    if (!stillHeld(stall)) {
                        anyReaped = true;
                        break;
                    }
                }
            }
            assertThat(anyReaped)
                .as("6. stalling peers are reaped at the auth timeout")
                .isTrue();
        } finally {
            for (Socket stall : preStalls) {
                try { stall.close(); } catch (Exception ignored) {}
            }
            for (Socket stall : postStalls) {
                try { stall.close(); } catch (Exception ignored) {}
            }
            if (proc != null) {
                proc.destroy();
                if (!proc.waitFor(3, TimeUnit.SECONDS)) {
                    proc.destroyForcibly();
                }
            }
        }
    }
}
