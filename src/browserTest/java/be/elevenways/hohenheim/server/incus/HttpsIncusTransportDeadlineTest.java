package be.elevenways.hohenheim.server.incus;

import be.elevenways.hohenheim.test.TlsCertificateTest;
import be.elevenways.protoblast.common.thread.JobRunner;
import be.elevenways.protoblast.common.time.Now;
import org.junit.jupiter.api.Test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * An Incus answer the daemon starts and never finishes ends at the exchange's own deadline over the remote TLS lane:
 * a wedged {@code /wait} once held a controller's migration for the whole of a run.
 *
 * AIDEV-NOTE: the daemon here sends the head of a chunked answer and then nothing, the shape a live daemon left a
 * stop's operation wait in (IncusColdMigrationLiveTest, 2026-10-22). Both protocols run: a TLS 1.2 close waits for the
 * peer's close_notify, so a deadline that closes the TLS layer from another thread can block behind the very read it
 * is meant to end.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class HttpsIncusTransportDeadlineTest {

    private static final long DEADLINE_MS = 1_500;

    @Test
    void anAnswerTheDaemonNeverFinishesEndsAtTheExchangeDeadlineJourney() throws Exception {
        KeyPair serverKeys = TlsCertificateTest.generateKeyPair();
        X509Certificate serverCertificate = TlsCertificateTest.generateSelfSignedCert(serverKeys, "wedged-incus");
        KeyPair clientKeys = TlsCertificateTest.generateKeyPair();
        X509Certificate clientCertificate = TlsCertificateTest.generateSelfSignedCert(clientKeys, "hohenheim-client");
        JobRunner jobs = JobRunner.create("wedged-incus");
        try {
            Map<String, Long> took = new LinkedHashMap<>();
            for (String protocol : new String[] {"TLSv1.3", "TLSv1.2"}) {
                CountDownLatch release = new CountDownLatch(1);
                try (SSLServerSocket listener = listener(serverKeys, serverCertificate, protocol)) {
                    jobs.runOnThread(() -> serveHeadThenNothing(listener, release));
                    HttpsIncusTransport transport = new HttpsIncusTransport("127.0.0.1", listener.getLocalPort(),
                        TlsCertificateTest.certToPem(clientCertificate), TlsCertificateTest.keyToPem(clientKeys),
                        TlsCertificateTest.certToPem(serverCertificate));

                    // 1. An operation wait whose answer starts and never ends: the exchange gives up at its deadline.
                    AtomicReference<Throwable> failure = new AtomicReference<>();
                    long started = Now.millis();
                    CompletableFuture<?> exchange = jobs.runOnThread(() -> failure.set(catchThrowable(() ->
                        transport.exchange("GET", "/1.0/operations/op-1/wait?timeout=1", null, DEADLINE_MS))));
                    exchange.get(DEADLINE_MS + 10_000, TimeUnit.MILLISECONDS);
                    took.put(protocol, Now.millis() - started);
                    assertThat(failure.get())
                        .as("step 1 (" + protocol + "): the wedged answer is a named deadline, not a hang")
                        .isInstanceOf(IOException.class)
                        .hasMessageContaining("timed out after " + DEADLINE_MS + "ms");
                } finally {
                    release.countDown();
                }
            }
            assertThat(took.values())
                .as("step 2: each exchange ended near its deadline, on both protocols: " + took)
                .allSatisfy(ms -> assertThat(ms).isLessThan(DEADLINE_MS + 3_000));
        } finally {
            jobs.shutdownNow();
        }
    }

    /** A pinned-certificate daemon on loopback speaking only {@code protocol}, taking any client certificate. */
    private static SSLServerSocket listener(KeyPair keys, X509Certificate certificate, String protocol)
            throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        char[] password = "wedged".toCharArray();
        store.setKeyEntry("daemon", keys.getPrivate(), password, new Certificate[] {certificate});
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(store, password);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), new TrustManager[] {new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, null);
        SSLServerSocket listener = (SSLServerSocket) context.getServerSocketFactory()
            .createServerSocket(0, 4, InetAddress.getLoopbackAddress());
        listener.setEnabledProtocols(new String[] {protocol});
        listener.setWantClientAuth(true);
        return listener;
    }

    /** Answer one request with a chunked head and then hold the connection silent until released. */
    private static void serveHeadThenNothing(SSLServerSocket listener, CountDownLatch release) {
        try (SSLSocket connection = (SSLSocket) listener.accept()) {
            connection.startHandshake();
            InputStream in = connection.getInputStream();
            int matched = 0;
            byte[] end = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
            while (matched < end.length) {
                int read = in.read();
                if (read < 0) {
                    return;
                }
                matched = read == end[matched] ? matched + 1 : (read == end[0] ? 1 : 0);
            }
            OutputStream out = connection.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            release.await(30, TimeUnit.SECONDS);
        } catch (IOException ended) {
            // the client gave up: the point of the test
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
