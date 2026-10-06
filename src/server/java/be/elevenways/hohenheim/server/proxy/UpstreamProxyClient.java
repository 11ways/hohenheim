package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.server.sitetype.UpstreamTarget;
import be.elevenways.hohenheim.server.tls.UpstreamTrust;
import be.elevenways.zenit.server.net.PinnedUpstreamDial;
import io.undertow.UndertowOptions;
import io.undertow.client.ClientCallback;
import io.undertow.client.ClientConnection;
import io.undertow.client.UndertowClient;
import io.undertow.protocols.ssl.UndertowXnioSsl;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.ServerConnection;
import io.undertow.server.handlers.proxy.ProxyCallback;
import io.undertow.server.handlers.proxy.ProxyClient;
import io.undertow.server.handlers.proxy.ProxyConnection;
import io.undertow.util.AttachmentKey;
import org.xnio.IoUtils;
import org.xnio.OptionMap;
import org.xnio.Xnio;
import org.xnio.ssl.XnioSsl;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Dials the upstream chosen for an exchange, picking the wire protocol and the TLS trust
 * posture the site type asked for.
 *
 * AIDEV-NOTE: a pinned target carries one dial per vetted address. A refused dial advances the
 * exchange's {@link DialPlan}, and ProxyHandler's own connection retry (it calls findTarget and
 * getConnection again) dials the next one; the plan raises ProxyHandler's retry count to the
 * number of further addresses. Every address was vetted, so falling through never leaves the set.
 */
final class UpstreamProxyClient implements ProxyClient {

    /** The upstream a site handler selected for this exchange. */
    static final AttachmentKey<UpstreamTarget> UPSTREAM_URI =
        AttachmentKey.create(UpstreamTarget.class);

    /** Where the exchange's dial outcome is reported, so the site's health knows its upstream stopped answering. */
    static final AttachmentKey<DialOutcome> DIAL_OUTCOME = AttachmentKey.create(DialOutcome.class);

    /** The exchange's dial progress, so a retry dials the next vetted address. */
    private static final AttachmentKey<DialPlan> DIAL_PLAN = AttachmentKey.create(DialPlan.class);

    /** Receives whether a dial reached the upstream; a retry that reaches the next address reports again. */
    @FunctionalInterface
    interface DialOutcome {
        void record(boolean answered);
    }

    /**
     * Test-only override for the trusted upstream SSL context, so HTTPS-upstream tests can
     * trust their own self-signed authority without weakening production validation.
     */
    private static volatile SSLContext trustedSslContextOverride;

    static void overrideTrustedUpstreamSslContextForTests(SSLContext context) {
        trustedSslContextOverride = context;
    }

    private final UndertowClient client = UndertowClient.getInstance();
    private final XnioSsl insecureSsl = createInsecureSsl();
    private final XnioSsl trustedSsl = createTrustedSsl();

    @Override
    public ProxyTarget findTarget(HttpServerExchange exchange) {
        DialPlan plan = exchange.getAttachment(DIAL_PLAN);
        if (plan == null) {
            UpstreamTarget target = exchange.getAttachment(UPSTREAM_URI);
            if (target == null) {
                return null;
            }
            plan = new DialPlan(target);
            exchange.putAttachment(DIAL_PLAN, plan);
        }
        return plan;
    }

    @Override
    public void getConnection(ProxyTarget target, HttpServerExchange exchange,
                              ProxyCallback<ProxyConnection> callback,
                              long timeout, TimeUnit timeUnit) {
        DialPlan plan = (DialPlan) target;
        UpstreamTarget upstreamTarget = plan.target;
        URI uri = dialUri(upstreamTarget, plan.uri());
        XnioSsl ssl = sslFor(upstreamTarget);

        // AIDEV-NOTE: Undertow's https/h2 providers force JDK endpoint identification
        // (the SAN hostname check) unless the caller overrides the option -- so
        // ignore_certificates must disable it too, or a trust-all context still
        // rejects certs whose SAN doesn't match the dial host. The target's own dial options
        // (a pinned dial's TLS host name) always ride along.
        OptionMap connectOptions = upstreamTarget.ignoreCertificates()
            ? OptionMap.builder().addAll(plan.options())
                .set(UndertowOptions.ENDPOINT_IDENTIFICATION_ALGORITHM, "").getMap()
            : plan.options();

        DialOutcome outcome = exchange.getAttachment(DIAL_OUTCOME);
        client.connect(new ClientCallback<ClientConnection>() {
            @Override
            public void completed(ClientConnection connection) {
                if (outcome != null) {
                    outcome.record(true);
                }
                ServerConnection serverConn = exchange.getConnection();
                serverConn.addCloseListener(sc -> IoUtils.safeClose(connection));

                String path = uri.getPath();
                if (path == null || path.isEmpty()) path = "/";

                // Streaming adapter: captures response trailers (e.g. grpc-status) that
                // the stock Undertow h2 client drops, and commits the upstream request
                // headers without waiting for a request body. Applied to EVERY upstream
                // connection so no protocol combination is left out.
                callback.completed(exchange, new ProxyConnection(
                    new StreamingProxyClientConnection(connection), path));
            }

            @Override
            public void failed(IOException e) {
                if (outcome != null) {
                    outcome.record(false);
                }
                plan.advance();
                callback.failed(exchange);
            }
        }, uri, exchange.getIoThread(), ssl,
           exchange.getConnection().getByteBufferPool(),
           connectOptions);
    }

    /**
     * The URI to hand Undertow's client, with the scheme mapped to the wire protocol:
     * h2c-prior (prior-knowledge cleartext HTTP/2) or h2 (ALPN) for H2 upstreams.
     */
    private URI dialUri(UpstreamTarget target, URI uri) {
        String dialScheme = target.protocol().dialScheme(uri.getScheme());
        if (dialScheme.equals(uri.getScheme())) {
            return uri;
        }
        try {
            return new URI(dialScheme, uri.getUserInfo(), uri.getHost(), uri.getPort(),
                uri.getPath(), uri.getQuery(), uri.getFragment());
        } catch (URISyntaxException e) {
            return uri;
        }
    }

    /**
     * TLS upstreams always get an SSL provider; Undertow rejects an https/h2 dial with a
     * null XnioSsl outright, so "no ssl" is only correct for cleartext upstreams.
     */
    private @Nullable XnioSsl sslFor(UpstreamTarget target) {
        if (!"https".equalsIgnoreCase(target.uri().getScheme())) {
            return null;
        }
        if (target.ignoreCertificates()) {
            return insecureSsl;
        }
        SSLContext override = trustedSslContextOverride;
        if (override != null) {
            return new UndertowXnioSsl(Xnio.getInstance(), OptionMap.EMPTY, override);
        }
        return trustedSsl;
    }

    private XnioSsl createTrustedSsl() {
        return new UndertowXnioSsl(Xnio.getInstance(), OptionMap.EMPTY,
            UpstreamTrust.defaultContext());
    }

    private XnioSsl createInsecureSsl() {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{new X509TrustManager() {
                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }

                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }
            }}, new SecureRandom());

            return new UndertowXnioSsl(Xnio.getInstance(), OptionMap.EMPTY, context);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize insecure proxy SSL", e);
        }
    }

    /** One exchange's way through its target's dials: the current one, advanced by a refusal. */
    private static final class DialPlan implements ProxyClient.MaxRetriesProxyTarget {

        final UpstreamTarget target;
        private volatile int next;

        DialPlan(UpstreamTarget target) {
            this.target = target;
        }

        /** @return the URI to dial now: the current vetted address, else the upstream as named */
        URI uri() {
            List<PinnedUpstreamDial> dials = this.target.dials();
            return dials.isEmpty() ? this.target.uri() : dials.get(this.next).uri();
        }

        /** @return the options the current dial must carry */
        OptionMap options() {
            List<PinnedUpstreamDial> dials = this.target.dials();
            return dials.isEmpty() ? OptionMap.EMPTY : dials.get(this.next).options();
        }

        /** Moves to the next vetted address; the last one is retried as ProxyHandler would. */
        void advance() {
            this.next = Math.min(this.next + 1, Math.max(0, this.target.dials().size() - 1));
        }

        @Override
        public int getMaxRetries() {
            return Math.max(0, this.target.dials().size() - 1);
        }
    }
}
