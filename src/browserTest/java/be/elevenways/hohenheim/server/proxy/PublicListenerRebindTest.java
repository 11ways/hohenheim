package be.elevenways.hohenheim.server.proxy;

import be.elevenways.protoblast.common.thread.JobRunner;
import be.elevenways.protoblast.testsupport.time.Poll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * A public port closed while its thread blocks in accept or receive binds again the moment close returns, as a
 * stream's rebind (TCP to TCP and UDP) needs.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
// AIDEV-NOTE: the JDK closes a socket a thread is blocked on only once that thread has left the call, after close()
// returned. The first test holds the accept thread inside the call through the server-socket seam, so it proves the
// close waits without depending on scheduling; the round tests rebind real ports, where an unjoined close lost the
// port about one round in seven in a standalone experiment but not reliably inside a test JVM.
class PublicListenerRebindTest {

    private static final int ROUNDS = 100;
    private static final Duration PARKED = Duration.ofSeconds(5);
    private static final Duration TICK = Duration.ofMillis(1);

    private final JobRunner closers = JobRunner.create("listener-rebind-closer");

    @AfterEach
    void tearDown() {
        closers.shutdownNow();
    }

    @Test
    @Timeout(30)
    void closeReturnsOnlyOnceTheBlockedAcceptLeftTheCall() throws Exception {
        // Odd while the accept loop is inside accept(), even once it left.
        AtomicInteger acceptState = new AtomicInteger();
        CountDownLatch leave = new CountDownLatch(1);
        PublicTcpListener listener = PublicTcpListener.forStream("127.0.0.1", 0,
            (input, source, destination) -> ConnectionRouter.Decision.reject(), 1, failure -> { });
        listener.setServerSocketFactoryForTesting(() -> new ServerSocket() {
            @Override
            public Socket accept() throws IOException {
                acceptState.incrementAndGet();
                try {
                    return super.accept();
                } finally {
                    // Stands for the JDK still holding the port: the call has not returned yet.
                    awaitUninterruptibly(leave);
                    acceptState.incrementAndGet();
                }
            }
        });
        try {
            listener.start();
            Poll.until("step 1: the accept loop is parked in accept()", PARKED, TICK,
                () -> acceptState.get() % 2 != 0);

            Thread closer = closers.startThread(listener::close);
            Poll.until("step 2: close() either waits or has returned", PARKED, TICK,
                () -> closer.getState() == Thread.State.WAITING || closer.getState() == Thread.State.TIMED_WAITING
                    || closer.getState() == Thread.State.TERMINATED);
            assertThat(closer.getState())
                .as("step 2: close() waits for the accept call instead of returning while it holds the port")
                .isNotEqualTo(Thread.State.TERMINATED);
            assertThat(acceptState.get() % 2).as("step 2: the accept call has not left yet").isEqualTo(1);

            leave.countDown();
            closer.join();
            assertThat(acceptState.get() % 2).as("step 3: close() returned after the accept call left").isZero();
        } finally {
            // First let the held accept call leave, then close: a failed step must not leave the thread parked.
            leave.countDown();
            listener.close();
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                // close() interrupts the listener's threads; the test decides when the call leaves.
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @Timeout(60)
    void aClosedTcpListenerFreesItsPortBeforeCloseReturns() {
        int port = 0;
        for (int round = 0; round < ROUNDS; round++) {
            // Odd while the accept loop is inside accept(), even once it left.
            AtomicInteger acceptState = new AtomicInteger();
            PublicTcpListener listener = PublicTcpListener.forStream("127.0.0.1", port,
                (input, source, destination) -> ConnectionRouter.Decision.reject(), 1, failure -> { });
            listener.setServerSocketFactoryForTesting(() -> new ServerSocket() {
                @Override
                public Socket accept() throws IOException {
                    acceptState.incrementAndGet();
                    try {
                        return super.accept();
                    } finally {
                        acceptState.incrementAndGet();
                    }
                }
            });
            try {
                assertThatCode(listener::start)
                    .as("round %d: the port the previous listener closed binds again", round)
                    .doesNotThrowAnyException();
                InetSocketAddress bound = listener.getLocalAddress();
                assertThat(bound).isNotNull();
                port = bound.getPort();
                Poll.until("round " + round + ": the accept loop is parked in accept()", PARKED, TICK,
                    () -> acceptState.get() % 2 != 0);
            } finally {
                listener.close();
            }
        }
    }

    @Test
    @Timeout(60)
    void aClosedUdpRelayFreesItsPortBeforeCloseReturns() throws IOException {
        int port = 0;
        try (DatagramSocket client = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            for (int round = 0; round < ROUNDS; round++) {
                AtomicInteger routed = new AtomicInteger();
                PublicUdpRelay relay = new PublicUdpRelay("127.0.0.1", port, from -> {
                    routed.incrementAndGet();
                    return null;
                });
                try {
                    assertThatCode(relay::start)
                        .as("round %d: the port the previous relay closed binds again", round)
                        .doesNotThrowAnyException();
                    InetSocketAddress bound = relay.getLocalAddress();
                    assertThat(bound).isNotNull();
                    port = bound.getPort();
                    // A routed datagram only shows the receive loop runs; whether close() then finds it back inside
                    // receive() is up to scheduling, so these rounds prove real rebinds, not close-during-receive.
                    client.send(new DatagramPacket(new byte[] {1}, 1, bound));
                    Poll.until("round " + round + ": the receive loop routed the datagram", PARKED, TICK,
                        () -> routed.get() > 0);
                } finally {
                    relay.close();
                }
            }
        }
    }
}
