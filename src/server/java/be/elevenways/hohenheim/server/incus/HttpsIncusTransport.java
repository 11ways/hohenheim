package be.elevenways.hohenheim.server.incus;

import org.checkerframework.checker.nullness.qual.NonNull;

import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * The remote Incus lane: HTTPS on the daemon's {@code core.https_address}, presenting
 * the per-host CLIENT certificate (the enrolled node identity) and accepting only the
 * PINNED server certificate. One fresh TLS connection per exchange, exactly the
 * Docker transports' connection discipline.
 */
final class HttpsIncusTransport extends StreamIncusTransport {

    private final @NonNull String host;
    private final int port;
    private final @NonNull String identityCertPem;
    private final @NonNull String identityKeyPem;
    private final @NonNull String pinnedCertPem;

    HttpsIncusTransport(@NonNull String host, int port, @NonNull String identityCertPem,
                        @NonNull String identityKeyPem, @NonNull String pinnedCertPem) {
        this.host = host;
        this.port = port;
        this.identityCertPem = identityCertPem;
        this.identityKeyPem = identityKeyPem;
        this.pinnedCertPem = pinnedCertPem;
    }

    @Override
    protected @NonNull Channel open(long connectTimeoutMs) throws IOException {
        SSLSocket socket = IncusTls.connectPinned(this.host, this.port, this.identityCertPem,
            this.identityKeyPem, this.pinnedCertPem,
            (int) Math.min(connectTimeoutMs, Integer.MAX_VALUE));
        return new Channel() {
            @Override
            public @NonNull InputStream in() throws IOException {
                return socket.getInputStream();
            }

            @Override
            public @NonNull OutputStream out() throws IOException {
                return socket.getOutputStream();
            }

            @Override
            public void close() throws IOException {
                socket.close();
            }
        };
    }

    @Override
    protected @NonNull String hostHeader() {
        return this.host + ":" + this.port;
    }

    @Override
    public @NonNull String describe() {
        return "https://" + this.host + ":" + this.port;
    }
}
