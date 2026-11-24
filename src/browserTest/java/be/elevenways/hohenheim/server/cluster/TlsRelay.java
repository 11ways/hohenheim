package be.elevenways.hohenheim.server.cluster;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A loopback TLS front for a plain HTTP server: what an OutboundFixture TLS stub host is routed to, so a pinned,
 * certificate-checked https call of one Hohenheim reaches the other's plain listener.
 *
 * AIDEV-NOTE: test support of the two-server journey; production never terminates TLS here. The target port can move
 * (a restarted server listens on another port), {@link #accepted()} counts the connections that reached it, the
 * evidence that nothing was sent, and {@link #dropAnswers} loses what the target answers: the request arrives, the
 * caller is cut off at the first byte of the answer.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
final class TlsRelay implements AutoCloseable {

    private final ServerSocket listener;
    private final AtomicInteger accepted = new AtomicInteger();
    private volatile int target;
    private volatile @Nullable SSLContext context;
    private volatile boolean dropAnswers;

    private TlsRelay(@NonNull ServerSocket listener) {
        this.listener = listener;
    }

    /** @return a relay listening on a free loopback port, relaying nothing until {@link #start} */
    static @NonNull TlsRelay open() throws IOException {
        return new TlsRelay(new ServerSocket(0, 50, InetAddress.getLoopbackAddress()));
    }

    /** @return the loopback port the relay listens on */
    int port() {
        return this.listener.getLocalPort();
    }

    /** Serves TLS with {@code server} and relays every connection to {@code targetPort} on the loopback. */
    void start(@NonNull SSLContext server, int targetPort) {
        this.context = server;
        this.target = targetPort;
        Thread.ofVirtual().name("tls-relay-" + this.port()).start(this::acceptAll);
    }

    /** Relays later connections to {@code targetPort}. */
    void retarget(int targetPort) {
        this.target = targetPort;
    }

    /** Delivers later requests but cuts their caller off when the answer starts, instead of relaying it. */
    void dropAnswers(boolean drop) {
        this.dropAnswers = drop;
    }

    /** @return how many connections reached the relay so far */
    int accepted() {
        return this.accepted.get();
    }

    private void acceptAll() {
        while (!this.listener.isClosed()) {
            Socket raw;
            try {
                raw = this.listener.accept();
            } catch (IOException closed) {
                return;
            }
            this.accepted.incrementAndGet();
            Thread.ofVirtual().start(() -> this.relay(raw));
        }
    }

    private void relay(@NonNull Socket raw) {
        SSLContext server = this.context;
        if (server == null) {
            close(raw);
            return;
        }
        try {
            SSLSocket tls = (SSLSocket) server.getSocketFactory().createSocket(raw, null, raw.getPort(), true);
            tls.setUseClientMode(false);
            tls.startHandshake();
            Socket upstream;
            try {
                upstream = new Socket(InetAddress.getLoopbackAddress(), this.target);
            } catch (IOException down) {
                close(tls);
                return;
            }
            boolean drop = this.dropAnswers;
            Thread.ofVirtual().start(() -> {
                if (drop) {
                    loseAnswer(upstream, tls);
                } else {
                    pump(upstream, tls);
                }
            });
            pump(tls, upstream);
        } catch (IOException failed) {
            close(raw);
        }
    }

    private static void pump(@NonNull Socket from, @NonNull Socket to) {
        try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (IOException ended) {
            // Either side closed: the other follows.
        } finally {
            close(from);
            close(to);
        }
    }

    /** Reads the answer to its end and hands the caller none of it: the caller is cut off at its first byte. */
    private static void loseAnswer(@NonNull Socket from, @NonNull Socket caller) {
        try (InputStream in = from.getInputStream()) {
            byte[] buffer = new byte[8192];
            boolean cut = false;
            while (in.read(buffer) >= 0) {
                if (!cut) {
                    close(caller);
                    cut = true;
                }
            }
        } catch (IOException ended) {
            // Either side closed.
        } finally {
            close(from);
            close(caller);
        }
    }

    private static void close(@NonNull Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Closing a socket that is gone.
        }
    }

    @Override
    public void close() throws IOException {
        this.listener.close();
    }
}
