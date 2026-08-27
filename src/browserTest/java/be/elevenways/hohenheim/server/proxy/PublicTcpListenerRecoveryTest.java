package be.elevenways.hohenheim.server.proxy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Aug 04 2026 incident mechanism at listener level: {@code accept()} throwing a
 * transient IOException (fd exhaustion shape) must NOT kill the listener permanently.
 * Pre-fix, the first accept failure escalated to the failure handler and closed the
 * socket forever; post-fix a burst is retried with capped backoff and only SUSTAINED
 * failure escalates. One behaviour journey, per the suite convention.
 */
class PublicTcpListenerRecoveryTest {

    /** 12 bytes so the PROXY v2 signature probe never blocks waiting for more input. */
    private static final byte[] CLIENT_PAYLOAD = "hello-proxy!".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BACKEND_REPLY = "pong".getBytes(StandardCharsets.UTF_8);

    private ServerSocket backend;
    private PublicTcpListener listener;

    @AfterEach
    void tearDown() throws IOException {
        if (listener != null) listener.close();
        if (backend != null) backend.close();
    }

    @Test
    @Timeout(30)
    void acceptLoopSurvivesTransientFailuresAndEscalatesSustainedOnes() throws Exception {
        // A tiny echo backend: read the 12-byte payload, answer "pong", close.
        backend = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            while (!backend.isClosed()) {
                try (Socket socket = backend.accept()) {
                    socket.getInputStream().readNBytes(CLIENT_PAYLOAD.length);
                    socket.getOutputStream().write(BACKEND_REPLY);
                    socket.getOutputStream().flush();
                } catch (IOException ignored) {
                    return;
                }
            }
        });

        // failNext > 0 makes the NEXT accept() calls throw the incident's exception.
        AtomicInteger failNext = new AtomicInteger();
        // Re-armed per accept: incremented right BEFORE delegating to super.accept() and
        // again when that call leaves, so an ODD value means the accept loop is parked
        // inside accept() having ALREADY passed the failNext check. Arming failNext while
        // it is parked therefore cannot affect that in-flight accept.
        AtomicInteger acceptState = new AtomicInteger();
        AtomicReference<IOException> escalated = new AtomicReference<>();
        listener = new PublicTcpListener("127.0.0.1", 0, 5_000,
            new InternalListenerRouter(() -> (InetSocketAddress) backend.getLocalSocketAddress()),
            List::of, new ConnectionIdentities(), ip -> false, 100, 100, escalated::set);
        listener.setAcceptFailurePolicyForTesting(5, 5, 20);
        listener.setServerSocketFactoryForTesting(() -> new ServerSocket() {
            @Override
            public Socket accept() throws IOException {
                if (failNext.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
                    throw new IOException("Too many open files in system");
                }
                acceptState.incrementAndGet();
                try {
                    return super.accept();
                } finally {
                    acceptState.incrementAndGet();
                }
            }
        });
        listener.start();
        int port = listener.getLocalAddress().getPort();

        // Step 1: a healthy round-trip through the listener.
        assertThat(exchange(port))
            .as("step 1: a healthy listener relays to the backend and back")
            .isEqualTo("pong");

        // Step 2: THE INCIDENT -- a transient burst of accept failures. The accept call
        // already blocked when failNext is set has passed the check, so this exchange
        // succeeds and ARMS the burst; the loop then eats 3 consecutive failures. Wait for
        // that parked accept to be OBSERVED rather than assumed: under CPU starvation the
        // loop can still be between iterations, in which case arming would fail the very
        // next accept instead of the one after it.
        assertThat(awaitParkedInAccept(acceptState))
            .as("step 2: the accept loop is parked inside accept() before the burst is armed")
            .isTrue();
        failNext.set(3);
        assertThat(exchange(port))
            .as("step 2: the pre-armed accept still serves while the burst arms")
            .isEqualTo("pong");
        // Poll rather than sleep: the backoffs are 5/10/20ms, but the first failure log
        // can pay one-time logging-init costs, so a fixed sleep is a race.
        for (int i = 0; i < 400 && failNext.get() > 0; i++) {
            Thread.sleep(25);
        }

        // Step 3: the listener SURVIVED the burst. Pre-fix this assertion fails: the very
        // first accept IOException called the failure handler and closed the socket forever.
        assertThat(failNext.get())
            .as("step 3: the transient burst was actually consumed by retries"
                + " (a leftover count means the accept loop stopped retrying)")
            .isZero();
        assertThat(exchange(port))
            .as("step 3: the listener survives a transient accept burst and keeps serving")
            .isEqualTo("pong");
        assertThat(escalated.get())
            .as("step 3: a transient burst below the threshold never escalates")
            .isNull();

        // Step 4: SUSTAINED failure (5 consecutive, the test policy threshold) escalates
        // to the failure handler and closes the listener -- recovery is then the
        // ProxyServer supervisor's job, not this loop's. Same observed-state rule as step 2:
        // the "one last exchange" below is only true of an accept that is ALREADY parked.
        assertThat(awaitParkedInAccept(acceptState))
            .as("step 4: the accept loop is parked inside accept() before sustained failure is armed")
            .isTrue();
        failNext.set(1_000);
        assertThat(exchange(port))
            .as("step 4: the pre-armed accept serves one last time while sustained failure arms")
            .isEqualTo("pong");
        for (int i = 0; i < 200 && escalated.get() == null; i++) {
            Thread.sleep(25);
        }
        assertThat(escalated.get())
            .as("step 4: sustained accept failure escalates to the listener failure handler")
            .isNotNull();
        boolean refused = false;
        for (int i = 0; i < 200 && !refused; i++) {
            try (Socket probe = new Socket()) {
                probe.connect(new InetSocketAddress("127.0.0.1", port), 500);
            } catch (IOException e) {
                refused = true;
            }
            if (!refused) Thread.sleep(25);
        }
        assertThat(refused)
            .as("step 4: after escalation the server socket is closed, so connects are refused")
            .isTrue();
    }

    /**
     * Waits for the accept loop to signal it is parked inside {@code accept()} (an odd
     * state), which is stable until a client connects because the test is the only client.
     *
     * @return false when the loop never parked within the wait budget
     */
    private static boolean awaitParkedInAccept(AtomicInteger acceptState) throws InterruptedException {
        for (int i = 0; i < 200 && acceptState.get() % 2 == 0; i++) {
            Thread.sleep(25);
        }
        return acceptState.get() % 2 != 0;
    }

    private static String exchange(int port) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
            socket.setSoTimeout(5_000);
            OutputStream out = socket.getOutputStream();
            out.write(CLIENT_PAYLOAD);
            out.flush();
            InputStream in = socket.getInputStream();
            byte[] reply = in.readNBytes(BACKEND_REPLY.length);
            return new String(reply, StandardCharsets.UTF_8);
        }
    }
}
