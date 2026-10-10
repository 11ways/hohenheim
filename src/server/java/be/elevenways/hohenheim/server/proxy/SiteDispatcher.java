package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.auth.SiteAuthDecision;
import be.elevenways.hohenheim.net.Hostnames;
import be.elevenways.hohenheim.server.auth.SiteAuthGate;
import be.elevenways.hohenheim.server.security.BanService;
import be.elevenways.hohenheim.server.security.HohenheimSecurity;
import be.elevenways.hohenheim.server.security.IpLiterals;
import be.elevenways.hohenheim.server.security.ReputationBanPolicy;
import be.elevenways.hohenheim.server.security.ThreatScorer;
import be.elevenways.hohenheim.server.sitetype.ProbeableUpstream;
import be.elevenways.hohenheim.server.sitetype.SiteHealth;
import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.hohenheim.server.sitetype.UpstreamTarget;
import be.elevenways.hohenheim.server.source.GitWebhookHandler;
import be.elevenways.hohenheim.server.tls.AcmeService;
import be.elevenways.hohenheim.server.tls.CertificateStore;
import be.elevenways.hohenheim.server.tls.SniKeyManager;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.thread.JobRunner;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.security.SecurityEventTypes;
import be.elevenways.zenit.common.session.SessionStore;
import be.elevenways.zenit.server.http.TrustedProxies;
import be.elevenways.zenit.server.security.SecurityEvents;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.proxy.ProxyHandler;
import io.undertow.server.handlers.ResponseCodeHandler;
import io.undertow.util.Headers;
import io.undertow.util.AttachmentKey;
import io.undertow.util.HttpString;
import io.undertow.util.StatusCodes;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLContext;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Routes incoming proxy requests to site-type handlers based on hostname matching.
 * Handles loop detection, request header injection, HSTS, force-SSL, custom headers,
 * path-based routing, default site fallback, and IP reputation tracking.
 *
 * The pipeline's stages live in package collaborators: {@link RouteTableBuilder} builds a
 * route generation, {@link RequestPath} reads the path every later stage agrees on,
 * {@link RouteResolver} selects the route, {@link AccessListGate} enforces the access list,
 * {@link ForwardingHeaders} rewrites the upstream request, {@link ResponseMutations} rewrites
 * the response and {@link UpstreamProxyClient} dials the backend.
 */
public class SiteDispatcher implements HttpHandler {

    private static final HttpString STRICT_TRANSPORT_SECURITY = new HttpString("Strict-Transport-Security");

    private final String instanceId = UUID.randomUUID().toString().substring(0, 8);

    /**
     * Canonical route path: leading slash enforced, trailing slash stripped,
     * root/empty collapsed to null (= catch-all).
     *
     * AIDEV-NOTE: THE single definition of route-path identity. Domain uniqueness in
     * SiteDomainRouteInvariant must compare with this exact function, or the editor can accept two
     * rows ("api" and "/api") that collapse to one route here and lose one to first-wins.
     */
    public static @Nullable String normalizeRoutePath(@Nullable String raw) {
        if (raw == null) return null;
        // Iterated to a TRUE fixpoint: single-pass ordering leaves residue for inputs
        // like "/a /" (trailing-slash strip exposes a trailing space the earlier trim
        // already ran past), and a non-fixpoint canonical form is exactly the identity
        // drift the editor/dispatcher agreement cannot afford.
        String path = raw;
        while (true) {
            String before = path;
            path = path.trim();
            if (path.isEmpty()) return null;
            if (!path.startsWith("/")) path = "/" + path;
            while (path.contains("//")) {
                path = path.replace("//", "/");
            }
            while (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            if (path.equals("/")) return null;
            if (path.equals(before)) return path;
        }
    }

    private final TlsPassthroughRoutes tlsPassthroughRoutes = new TlsPassthroughRoutes();
    private final Object generationLock = new Object();
    private volatile RouteTable routes = RouteTable.empty();

    private static final HttpString HOST = Headers.HOST;

    // Threat scoring (hostname-scanning ban tracking; shared with the local
    // SecurityEvents sink so this instance's own events count too) and access logging.
    private final ThreatScorer threatScorer = HohenheimSecurity.scorer();
    private final AccessLog accessLog = new AccessLog();

    private static final String ACME_CHALLENGE_PREFIX = "/.well-known/acme-challenge/";

    // Proxy handlers per request-timeout value (ProxyHandler fixes maxRequestTime at build
    // time). Sites share one client; a handful of distinct timeouts at most.
    private final UpstreamProxyClient proxyClient;
    private final ConcurrentHashMap<Integer, ProxyHandler> proxyHandlers = new ConcurrentHashMap<>();

    private final JobRunner delayScheduler;
    private final JobRunner retirementRunner;

    // ACME service for Let's Encrypt challenge responses (nullable)
    private final AcmeService acmeService;
    private final CertificateStore certificates;

    // Proxy-auth session store (owned by ProxyServer), threaded into per-site auth gates.
    private final SessionStore proxySessionStore;

    private volatile boolean httpsAvailable;

    // Whether each site's upstream answered the last dial (true) or refused it (false): a visitor's request, or the
    // dispatcher's own probe of a fixed upstream (ProbeableUpstream), whichever came last.
    private final ConcurrentHashMap<Integer, Boolean> upstreamAnswered = new ConcurrentHashMap<>();

    /** How often fixed upstreams are probed without a visitor. */
    private static final long UPSTREAM_PROBE_INTERVAL_MS = 30_000;

    /** How long one probe waits for a TCP connect before calling the upstream down. */
    private static final int UPSTREAM_PROBE_TIMEOUT_MS = 2_000;

    // One probe round at a time: a round of slow upstreams must not pile up behind the next tick.
    private final AtomicBoolean probing = new AtomicBoolean();

    // The proxy route for unmatched hostnames, keyed by the fallback address it was built from.
    private volatile @Nullable FallbackRoute fallbackRoute;

    /** @param certificates the proxy's certificate store, whose loaded names the global force waits for */
    public SiteDispatcher(AcmeService acmeService, SessionStore proxySessionStore, CertificateStore certificates) {
        this.acmeService = acmeService;
        this.certificates = certificates;
        this.proxySessionStore = proxySessionStore;
        this.delayScheduler = JobRunner.createDaemonSerial("site-dispatch-delay");
        this.retirementRunner = JobRunner.createDaemonSerial("site-dispatch-retirement");
        this.proxyClient = new UpstreamProxyClient();
        // The round itself blocks on connects, so it runs on a virtual thread and never on the delay scheduler.
        this.delayScheduler.scheduleRepeating(() -> JobRunner.startVirtualThread(this::probeUpstreams),
            UPSTREAM_PROBE_INTERVAL_MS);
    }

    /**
     * Probe every fixed upstream of the serving routes once, recording whether it accepts a connection.
     *
     * AIDEV-NOTE: this is what makes a proxy whose target never answers read as down before its first visitor
     * (W5a2: a new Grafana app on a closed port showed green). It feeds the SAME record visitors' dials feed, so
     * healthOf stays the one verdict. A round on an older route table writes nothing.
     */
    public void probeUpstreams() {
        if (!this.probing.compareAndSet(false, true)) {
            return;
        }
        try {
            RouteTable generation = this.routes;
            Map<Integer, ProbeableUpstream> targets = new LinkedHashMap<>();
            for (RouteEntry entry : generation.entries()) {
                if (entry.siteId > 0 && entry.handler instanceof ProbeableUpstream probeable
                        && probeable.probeHost() != null) {
                    targets.putIfAbsent(entry.siteId, probeable);
                }
            }
            for (Map.Entry<Integer, ProbeableUpstream> target : targets.entrySet()) {
                boolean answered = accepts(target.getValue().probeHost(), target.getValue().probePort());
                if (this.routes == generation) {
                    upstreamAnswered.put(target.getKey(), answered);
                }
            }
        } finally {
            this.probing.set(false);
        }
    }

    /** Whether a TCP connect to the address succeeds within the probe timeout; an unresolvable name does not. */
    private static boolean accepts(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), UPSTREAM_PROBE_TIMEOUT_MS);
            return true;
        } catch (IOException | IllegalArgumentException refused) {
            return false;
        }
    }

    /**
     * @param maxRequestTimeMs absolute exchange lifetime; -1 = unlimited (streaming sites)
     */
    private ProxyHandler proxyHandlerFor(int maxRequestTimeMs) {
        // reuseXForwarded: ProxyHandler appends the connected peer to an existing XFF chain
        // instead of overwriting it -- the dispatcher seeds the chain for trusted remote proxies.
        return proxyHandlers.computeIfAbsent(maxRequestTimeMs, timeout -> ProxyHandler.builder()
            .setProxyClient(proxyClient)
            .setMaxRequestTime(timeout)
            .setNext(ResponseCodeHandler.HANDLE_404)
            .setRewriteHostHeader(false)
            .setReuseXForwarded(true)
            .build());
    }

    /**
     * Reload all routes from the database, creating handlers via the site type system.
     *
     * The new generation is built completely before it is swapped in, and a build that throws
     * leaves the serving generation untouched (the builder has already destroyed whatever it
     * created).
     */
    public synchronized void reloadRoutes() {
        RouteTable next = new RouteTableBuilder(proxySessionStore, tlsPassthroughRoutes, certificates).build();
        RouteTable previous;
        synchronized (generationLock) {
            previous = this.routes;
            this.routes = next;
            previous.retired = true;
        }
        // A reload may carry a fixed upstream: an old refusal must not keep calling it down until the next visitor.
        upstreamAnswered.clear();
        destroyIfUnused(previous, false);
        // A new or changed fixed upstream is checked now, not at the next tick.
        JobRunner.startVirtualThread(this::probeUpstreams);
    }

    /**
     * The enabled sites the last route load could not route as configured, in load order. An
     * attention surface reads this; each problem was also logged when it was recorded.
     */
    public List<RoutingProblem> routingProblems() {
        return this.routes.problems;
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {

        // Pin the client-facing scheme while the trust-boundary headers are still pristine:
        // this pipeline strips X-Hohenheim-Key and rewrites X-Forwarded-Proto further down.
        ProxyScheme.resolve(exchange);

        // AIDEV-NOTE: SOCKET FRONT MODE FAILS CLOSED, ahead of every other check including
        // ACME. The AF_UNIX peer has no address and the bridge registers no identity, so a
        // request that did not authenticate as the fronting proxy has NO client identity at
        // all -- and silently degrading it to the socket peer means 127.0.0.1, under which
        // bans are inert, denied_ips fail-open, allowed_ips refuses everyone and threat
        // scoring blames loopback. It is not merely degraded either: the bridge's loopback
        // TCP port is reachable by any local account on the host, so an unauthenticated
        // request there is a deliberate identity forgery. Refuse instead of trusting, and
        // read the key set LIVE so clearing proxy.trusted_proxy_keys closes the front
        // rather than quietly widening it.
        if (ProxyScheme.lacksRequiredProxyKey(exchange)) {
            exchange.setStatusCode(StatusCodes.FORBIDDEN);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
            exchange.getResponseSender().send("Forbidden");
            return;
        }

        // AIDEV-NOTE: a proxy-level refusal of an ambiguously framed message (both Content-Length
        // and Transfer-Encoding, RFC 7230 3.3.3) was tried here and REMOVED as redundant: this
        // server's Undertow HttpRequestParser already answers 400 and never invokes this handler
        // for such a request, so the check was unreachable. Proven by a counterfactual test that
        // passed against the un-patched tree; do not re-add without evidence Undertow forwards it.

        // --- The ONE reading of the path: refused when it carries a control character or a
        //     dot-segment, otherwise every later stage (ACME, routing, guards, strip_path)
        //     judges its canonical form and the upstream receives its raw form. ---
        RequestPath path = RequestPath.attach(exchange);
        if (path == null) {
            exchange.setStatusCode(StatusCodes.BAD_REQUEST);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
            exchange.getResponseSender().send("Bad Request");
            return;
        }

        // --- ACME HTTP-01 challenge: BEFORE ban enforcement, so certificate
        //     renewal survives a mistaken ban (serving a pending challenge
        //     response is harmless). ---
        String acmePath = path.canonical();
        if (acmePath.startsWith(ACME_CHALLENGE_PREFIX) && acmeService != null) {
            String token = acmePath.substring(ACME_CHALLENGE_PREFIX.length());
            String challengeHost = extractHostname(exchange);
            String response = acmeService.getChallengeResponse(token, challengeHost);
            if (response != null) {
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
                exchange.getResponseSender().send(response);
                return;
            }
        }

        // --- Ban enforcement: reject banned IPs early (plain HTTP; HTTPS is
        //     rejected earlier still, at the TLS handshake, via SniKeyManager). Uses the
        //     effective client IP so bans follow real clients through a trusted remote proxy.
        //     Vouched for at once: every later reader (a gate's rate limit, an identity provider's
        //     audit field, zenit's exchange lane) gets THIS client, never the socket peer or a
        //     forwarded header zenit's own trusted-proxy walk would believe from loopback. ---
        String clientIp = resolveClientIp(exchange);
        TrustedProxies.vouch(exchange, clientIp);
        if (isBanned(clientIp)) {
            exchange.setStatusCode(403);
            exchange.endExchange();
            return;
        }

        // Not banned: let the reputation policy consider a (throttled, async)
        // spamservice lookup -- an IP abusing hosted apps elsewhere gets a ban
        // row here before it does more damage. Never blocks this request.
        ReputationBanPolicy.INSTANCE.noteRequest(clientIp);

        // --- Git webhook intercept (before hostname routing) ---
        if (GitWebhookHandler.matches(exchange)) {
            GitWebhookHandler.handle(exchange);
            return;
        }

        // --- Loop detection: this dispatcher anywhere in the hop chain ---
        if (ForwardingHeaders.isLoop(exchange.getRequestHeaders(), instanceId)) {
            exchange.setStatusCode(508);
            exchange.getResponseSender().send("Loop Detected");
            return;
        }

        String hostname = extractHostname(exchange);

        RouteTable generation = acquireRoutes();
        AtomicBoolean generationReleased = new AtomicBoolean();
        Runnable releaseGeneration = () -> {
            if (generationReleased.compareAndSet(false, true)) releaseRoutes(generation);
        };
        exchange.addExchangeCompleteListener((completed, next) -> {
            if (completed.isUpgrade()) {
                var connection = completed.getConnection();
                connection.addCloseListener(closed -> releaseGeneration.run());
                if (!connection.isOpen()) releaseGeneration.run();
            } else {
                releaseGeneration.run();
            }
            next.proceed();
        });
        RouteResolution resolution = RouteResolver.resolve(exchange, hostname, path.canonical(),
            generation);
        RouteMatch match = resolution.match();
        RouteEntry entry = match != null ? match.entry() : null;

        if (match != null && match.groups() != null && !match.groups().isEmpty()) {
            exchange.putAttachment(MATCHED_GROUPS, match.groups());
        }
        if (entry != null && entry.hostPattern != null) {
            exchange.putAttachment(MATCHED_HOST_PATTERN, entry.hostPattern);
        }

        // --- Threat scoring: a known hostname with a wrong path is a plain 404,
        //     not a domain-scanning signal. ---
        if (entry != null || resolution.hostnameKnown()) {
            threatScorer.recordHit(clientIp);
        } else {
            int score = threatScorer.recordMiss(clientIp, hostname);
            int threshold = Zenit.SETTINGS_VALUES.getValue(
                HohenheimSettings.Security.DOMAIN_MISS_THRESHOLD);
            if (score >= threshold) {
                // Threshold-gated like the original fail2ban log line, which is written
                // too; the in-process sink (HohenheimSecurity) lands it in security_events.
                // The scorer was already fed above, so the sink skips domain misses.
                String userAgent = exchange.getRequestHeaders().getFirst(Headers.USER_AGENT);
                Map<String, String> detail = new LinkedHashMap<>();
                detail.put("domain", hostname);
                detail.put("path", path.raw());
                if (userAgent != null) {
                    detail.put("ua", userAgent);
                }
                SecurityEvents.report(SecurityEventTypes.DOMAIN_MISS, clientIp, detail);
                DomainMissLog.record(clientIp, hostname, path.raw(), userAgent);
            }
        }

        // --- Default fallback ---
        if (entry == null) {
            if (resolution.hostnameKnown()) {
                // The hostname IS configured, no path matched: a real 404,
                // never the catch-all fallback redirect.
                ErrorPages.send404(exchange, hostname);
                return;
            }
            // AIDEV-NOTE: the fallback address is PROXIED to, as the Node original did ("send everything else
            // to the Apache on localhost:8080"); a 302 sent browsers to an address only this host can reach.
            entry = fallbackRoute(Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Proxy.FALLBACK_ADDRESS));
            if (entry == null) {
                ErrorPages.send404(exchange, hostname);
                return;
            }
        }

        // --- Force SSL: redirect while HTTPS termination is up, REFUSE while it is not. ---
        // AIDEV-NOTE: force_ssl is a confidentiality control and fails CLOSED. This used to be
        // gated on httpsAvailable, so the moment the certificate store emptied (last cert
        // deleted, keystore load failure, passthrough-only reload) every force-SSL site
        // silently served cleartext with the checkbox still reading enabled. The ACME HTTP-01
        // path is answered BEFORE this gate, so a Let's Encrypt bootstrap or renewal still
        // completes while the site refuses -- the refusal self-heals. The global force_https
        // setting rides the same gate for MATCHED routes only: an unmatched hostname has no
        // content to protect and keeps its 404/fallback.
        // AIDEV-NOTE: the global setting waits for a working certificate on an exact name (RouteEntry.globalForce):
        // forcing a name no certificate covers sent every visitor of a new domain to an error page. "Working" is what
        // this proxy's certificate store loaded (CertificateCoverage.workingNames), the rule every HTTPS display
        // reads: an ACTIVE row whose material cannot load serves no handshake, so the setting leaves its name on plain
        // HTTP. An address's own force_ssl is untouched by this and keeps failing closed.
        if (forcesHttps(entry) && !ProxyScheme.isEffectivelyHttps(exchange)) {
            if (httpsAvailable) {
                redirectToHttps(exchange, hostname);
            } else {
                ErrorPages.sendHttpsRequired(exchange, hostname);
            }
            return;
        }

        // --- Per-site auth provider gate (identity-level; coexists with the IP/basic access list,
        //     which still runs afterwards). The gate may block (Argon2 verify, Proteus HTTP), so
        //     run it -- and the rest of the forwarding pipeline -- on a worker thread, off the I/O
        //     thread. Sites with no provider keep the zero-overhead inline path. ---
        SiteAuthGate gate = entry.authGate;
        if (gate != null) {
            final RouteEntry gatedEntry = entry;
            final String gatedHostname = hostname;
            final String gatedClientIp = clientIp;
            exchange.dispatch(() -> {
                try {
                    SiteAuthDecision decision = gate.evaluate(exchange);
                    if (decision != null) {
                        applySiteAuthDecision(exchange, decision);
                    } else {
                        continueAfterAuth(gatedEntry, exchange, gatedHostname, gatedClientIp);
                    }
                } catch (Exception e) {
                    // A gate must never leave the exchange hanging: fail closed.
                    Blast.log("SiteDispatcher: auth gate error for site", gatedEntry.siteName,
                        "-", e.getMessage());
                    if (!exchange.isResponseStarted()) {
                        applySiteAuthDecision(exchange, SiteAuthDecision.deny(502, "Authentication error"));
                    }
                }
            });
            return;
        }

        continueAfterAuth(entry, exchange, hostname, clientIp);
    }

    /**
     * The request pipeline after the auth gate: access list, path matching, header injection,
     * HSTS, access logging, upstream dispatch. Runs on a worker thread when a gate is present.
     */
    private void continueAfterAuth(RouteEntry entry, HttpServerExchange exchange,
                                   String hostname, String clientIp) {

        // An access list whose tree carries a credential leaf blocks while evaluating it
        // (argon2 verification, an identity provider's HTTP round trip), so it may not run
        // on the I/O thread. Address-only lists keep the zero-overhead inline path.
        if (entry.accessListBlocks() && exchange.isInIoThread()) {
            exchange.dispatch(() -> continueAfterAuth(entry, exchange, hostname, clientIp));
            return;
        }

        // --- Access list enforcement ---
        if (entry.hasAccessList() && !AccessListGate.allows(exchange, entry)) {
            return;
        }

        // --- Protected-path enforcement: ADDITIVE, so every guard whose prefix covers the
        //     request must also pass. Runs before strip_path on purpose -- guards are
        //     declared against the path the browser sees, exactly like route paths, and they
        //     judge the SAME canonical form route selection did (RequestPath), so no spelling
        //     of a path can pick one route and dodge that route's guard. ---
        RequestPath path = RequestPath.of(exchange);
        String canonicalPath = path != null ? path.canonical() : exchange.getRelativePath();
        for (RouteEntry.PathGuard guard : entry.pathGuards) {
            if (guard.covers(canonicalPath)
                    && !AccessListGate.allows(exchange, guard.tree())) {
                return;
            }
        }

        // --- Path matching (selection already guaranteed a match; kept as a safety net) ---
        if (entry.path != null) {
            if (!entry.matchesPath(canonicalPath)) {
                exchange.setStatusCode(404);
                exchange.getResponseSender().send("Not Found");
                return;
            }
            if (entry.stripPath) {
                // AIDEV-NOTE: ProxyHandler writes requestURI VERBATIM into the upstream request
                // line, so the stripped URI is cut from the RAW path and keeps the client's own
                // encoding. It used to be the DECODED remainder: %0d%0a became a header break
                // (request smuggling), %20 split the request line, %3F moved the query boundary
                // and %2525 was decoded twice. The decoded view handlers read (static files, a
                // redirect's path) is the canonical remainder.
                String rawRemainder = path != null ? path.rawRemainderAfter(entry.path) : null;
                if (rawRemainder == null) {
                    exchange.setStatusCode(StatusCodes.BAD_REQUEST);
                    exchange.getResponseSender().send("Bad Request");
                    return;
                }
                String stripped = canonicalPath.substring(entry.path.length());
                if (stripped.isEmpty()) stripped = "/";
                exchange.setRelativePath(stripped);
                exchange.setRequestPath(stripped);
                exchange.setRequestURI(rawRemainder, false);
            }
        }

        // --- Inject proxy headers (custom rules, hop-by-hop hygiene, trust boundary) ---
        ForwardingHeaders.applyRequestHeaders(exchange, entry, instanceId, hostname, clientIp);

        // RFC 6797 7.2: HSTS header MUST NOT be emitted over non-secure transport.
        if (entry.hstsEnabled && ProxyScheme.isEffectivelyHttps(exchange)) {
            String hstsValue = "max-age=31536000";
            if (entry.hstsSubdomains) hstsValue += "; includeSubDomains";
            exchange.getResponseHeaders().put(STRICT_TRANSPORT_SECURITY, hstsValue);
        }

        // --- Access logging ---
        accessLog.logAccess(exchange, hostname, clientIp);

        dispatchToRoute(entry, exchange);
    }

    /** Apply a non-null gate decision: redirect the browser, or deny with a status + body. */
    static void applySiteAuthDecision(HttpServerExchange exchange, SiteAuthDecision decision) {
        if (decision instanceof SiteAuthDecision.Redirect redirect) {
            exchange.setStatusCode(302);
            exchange.getResponseHeaders().put(Headers.LOCATION, redirect.url());
            exchange.endExchange();
        } else if (decision instanceof SiteAuthDecision.Deny deny) {
            exchange.setStatusCode(deny.statusCode());
            exchange.getResponseSender().send(deny.body());
        }
    }

    /** Named + numbered regex-host capture groups from the active route, if any. */
    public static final AttachmentKey<Map<String, String>> MATCHED_GROUPS =
        AttachmentKey.create(Map.class);

    /** The matched domain row's configured hostname pattern (exact, glob or regex source). */
    public static final AttachmentKey<String> MATCHED_HOST_PATTERN =
        AttachmentKey.create(String.class);

    /** Set by site types that want upstream Location redirects rewritten to the public host. */
    public static final AttachmentKey<Boolean> REWRITE_LOCATION =
        AttachmentKey.create(Boolean.class);

    // -----------------------------------------------------------------------
    // Resolution
    // -----------------------------------------------------------------------

    /**
     * The request's routing host: the Host header minus its port, case-folded and minus the
     * FQDN root dot.
     *
     * AIDEV-NOTE: the root-dot fold is the request half of SiteDomainModel.canonicalHostname
     * and may not be dropped from either side. Keeping the dot here made {@code victim.test.}
     * a route table entry of its own that no stored claim intersected, while the TLS
     * handshake for that same request could only ever carry {@code victim.test} in SNI
     * (RFC 6066 forbids the trailing dot) -- so the connection got the victim's certificate
     * and the raider's upstream, with no browser warning. Folding both sides means the two
     * spellings resolve to ONE route, which is also what DNS says they are.
     */
    private String extractHostname(HttpServerExchange exchange) {
        return Hostnames.fromHostHeader(exchange.getRequestHeaders().getFirst(HOST));
    }

    /** Public seam over {@link RouteResolver#isSuspiciousRegexHostname}. */
    public static boolean isSuspiciousRegexHostname(String hostname) {
        return RouteResolver.isSuspiciousRegexHostname(hostname);
    }

    private void dispatchToRoute(RouteEntry entry, HttpServerExchange exchange) {
        exchange.addResponseCommitListener(ex -> ResponseMutations.apply(entry, ex));

        ProxyHandler timedProxyHandler = proxyHandlerFor(entry.requestTimeoutMs);
        int siteId = entry.siteId;
        Runnable dispatch = () -> entry.handler.handleRequest(exchange, upstream -> {
            exchange.putAttachment(UpstreamProxyClient.UPSTREAM_URI, upstream);
            if (siteId > 0) {
                exchange.putAttachment(UpstreamProxyClient.DIAL_OUTCOME, answered -> upstreamAnswered.put(siteId, answered));
            }
            // Only the proxy path may commit early: ProxyHandler copies the upstream status
            // and headers before it acquires the response channel, so a flush can never
            // publish a half-built response. Handlers that build their OWN response (a
            // redirect's 302 + Location, a static file, an error page) must not be committed
            // out from under them.
            EagerResponseCommit.install(exchange);
            try {
                timedProxyHandler.handleRequest(exchange);
            } catch (Exception e) {
                Blast.log("SiteDispatcher: proxying failed for site", entry.siteName, "-", e.getMessage());
                ErrorPages.send502(exchange);
            }
        });

        if (entry.requestDelayMs <= 0) {
            dispatch.run();
            return;
        }

        exchange.dispatch();
        delayScheduler.schedule(() -> {
            if (!exchange.isComplete()) {
                exchange.dispatch(dispatch);
            }
        }, entry.requestDelayMs);
    }

    /**
     * The route unmatched hostnames take: a plain proxy to the fallback address, rebuilt only when the setting changes.
     *
     * @return null when no usable http(s) address is configured
     */
    private @Nullable RouteEntry fallbackRoute(@Nullable String address) {
        FallbackRoute cached = this.fallbackRoute;
        if (cached != null && Objects.equals(cached.address(), address)) {
            return cached.entry();
        }
        RouteEntry entry = null;
        URI upstream = fallbackUpstream(address);
        if (upstream != null) {
            UpstreamTarget target = new UpstreamTarget(upstream, false);
            entry = new RouteEntry((exchange, forwarder) -> forwarder.forwardTo(target), "fallback address", null,
                null, List.of(), null, null, null, false);
        } else if (address != null && !address.isBlank()) {
            Blast.log("SiteDispatcher: the fallback address is not an http(s) address, unmatched hosts get a 404:",
                address);
        }
        this.fallbackRoute = new FallbackRoute(address, entry);
        return entry;
    }

    /** @return the fallback address as an upstream origin, or null when it is not an absolute http(s) address */
    static @Nullable URI fallbackUpstream(@Nullable String address) {
        if (address == null || address.isBlank()) return null;
        try {
            URI parsed = new URI(address.trim());
            String scheme = parsed.getScheme() != null ? parsed.getScheme().toLowerCase(Locale.ROOT) : null;
            if (!"http".equals(scheme) && !"https".equals(scheme) || parsed.getHost() == null) return null;
            int port = parsed.getPort() > 0 ? parsed.getPort() : "https".equals(scheme) ? 443 : 80;
            return new URI(scheme, null, parsed.getHost(), port, "/", null, null);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private record FallbackRoute(@Nullable String address, @Nullable RouteEntry entry) {}

    public void setHttpsAvailable(boolean httpsAvailable) {
        this.httpsAvailable = httpsAvailable;
    }

    public boolean isHttpsAvailable() {
        return httpsAvailable;
    }

    /**
     * Distinct site names holding at least one force-SSL route, in name order -- the sites
     * that REFUSE plain HTTP while HTTPS termination is unavailable.
     */
    public List<String> forceSslSiteNames() {
        ForcedSites forced = forcedSites();
        Set<String> names = new TreeSet<>();
        for (RouteEntry entry : this.routes.entries()) {
            if (forced.own().contains(entry.siteId) || forced.bySetting().contains(entry.siteId)) {
                names.add(entry.siteName);
            }
        }
        return List.copyOf(names);
    }

    /**
     * The sites {@link #forcesHttps} sends to HTTPS, split by what sends them: an address's own Force HTTPS, or the
     * global Force HTTPS setting (a pattern, or a name a working certificate covers). A site with both is its own.
     *
     * AIDEV-NOTE: D10a's dashboard said "sites that force SSL" of a catch-all and a certified name that forced nothing
     * themselves: the setting (on by default) sent them. The reader names each by its cause, and names it by site id so
     * the panel can say the app's name (D13a: one app, one name), never the route's copy of the site's title.
     */
    public ForcedSites forcedSites() {
        Set<Integer> own = new TreeSet<>();
        Set<Integer> bySetting = new TreeSet<>();
        for (RouteEntry entry : this.routes.entries()) {
            if (entry.forceSsl) {
                own.add(entry.siteId);
            } else if (forcedBySetting(entry)) {
                bySetting.add(entry.siteId);
            }
        }
        bySetting.removeAll(own);
        return new ForcedSites(List.copyOf(own), List.copyOf(bySetting));
    }

    /**
     * @param own       sites with an address forcing HTTPS itself, by site id
     * @param bySetting sites only the global Force HTTPS setting sends to HTTPS, by site id
     */
    public record ForcedSites(List<Integer> own, List<Integer> bySetting) {

        /** @return whether no site is sent to HTTPS */
        public boolean isEmpty() {
            return own.isEmpty() && bySetting.isEmpty();
        }
    }

    /** @return whether this route refuses plain HTTP: its own force_ssl, or the global force_https where it applies */
    private static boolean forcesHttps(RouteEntry entry) {
        return entry.forceSsl || forcedBySetting(entry);
    }

    /** @return whether the global force_https setting sends this route to HTTPS (it waits for a working certificate) */
    private static boolean forcedBySetting(RouteEntry entry) {
        return entry.globalForce && HohenheimSettings.isOn(HohenheimSettings.Proxy.FORCE_HTTPS);
    }

    /** The proxy-auth session store, shared with every per-site auth gate. */
    SessionStore proxySessionStore() {
        return this.proxySessionStore;
    }

    private void redirectToHttps(HttpServerExchange exchange, String hostname) {
        String hostHeader = exchange.getRequestHeaders().getFirst(HOST);
        String authority = hostname;
        int httpsPort = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Proxy.HTTPS_PORT);

        if (hostHeader != null && !hostHeader.isBlank()) {
            authority = hostHeader.replaceFirst(":\\d+$", "");
        }

        if (httpsPort > 0 && httpsPort != 443) {
            authority = authority + ":" + httpsPort;
        }

        // The RAW path: the decoded one would turn an encoded %3F or %0a into live syntax
        // inside the Location header.
        RequestPath path = RequestPath.of(exchange);
        String redirectUrl = "https://" + authority
            + (path != null ? path.raw() : RequestPath.rawPathOf(exchange));
        String query = exchange.getQueryString();
        if (query != null && !query.isEmpty()) {
            redirectUrl += "?" + query;
        }

        exchange.setStatusCode(301);
        exchange.getResponseHeaders().put(Headers.LOCATION, redirectUrl);
        exchange.endExchange();
    }

    private static void destroyHandlers(RouteTable rt) {
        RouteTableBuilder.destroy(rt.ownedHandlers, rt.ownedGates);
    }

    private RouteTable acquireRoutes() {
        synchronized (generationLock) {
            RouteTable generation = routes;
            generation.users.incrementAndGet();
            return generation;
        }
    }

    private void releaseRoutes(RouteTable generation) {
        if (generation.users.decrementAndGet() == 0 && generation.retired) {
            destroyIfUnused(generation, true);
        }
    }

    private void destroyIfUnused(RouteTable generation, boolean asynchronous) {
        if (generation.users.get() != 0 || !generation.destructionStarted.compareAndSet(false, true)) return;
        if (!asynchronous) {
            destroyHandlers(generation);
            return;
        }
        if (!retirementRunner.offer(() -> destroyHandlers(generation))) {
            destroyHandlers(generation);
        }
    }

    // -----------------------------------------------------------------------
    // IP reputation
    // -----------------------------------------------------------------------

    /**
     * The effective client IP: the socket peer, unless a trusted remote proxy (valid
     * X-Hohenheim-Key) forwarded the original client in X-Real-IP.
     *
     * @throws IllegalStateException for an exchange without a socket peer
     */
    private String resolveClientIp(HttpServerExchange exchange) {
        String sourceIp = TrustedProxies.socketPeerIp(exchange);
        if (sourceIp == null) {
            throw new IllegalStateException("A proxy exchange always has a socket peer");
        }
        if (isTrustedRemoteProxy(exchange)) {
            String realIp = exchange.getRequestHeaders().getFirst(ForwardingHeaders.X_REAL_IP);
            byte[] address = IpLiterals.parse(realIp != null ? realIp.trim() : null);
            if (address != null) {
                try {
                    return InetAddress.getByAddress(address).getHostAddress();
                } catch (UnknownHostException ignored) {
                    // IpLiterals only returns the two lengths accepted by InetAddress.
                }
            }
        }
        return sourceIp;
    }

    private boolean isTrustedRemoteProxy(HttpServerExchange exchange) {
        return ProxyScheme.isTrustedRemoteProxy(exchange);
    }

    /**
     * Whether this source IP is currently banned. Ban ROWS are the only
     * enforcement truth (the scorer is purely a trigger that CREATES rows via
     * BanService.autoBan), so every refused IP has an auditable, liftable ban
     * row. Used both at the HTTP stage and, for HTTPS, at the TLS handshake
     * stage (via {@link SniKeyManager}) to drop bad IPs before a certificate is served.
     */
    public boolean isBanned(String ip) {
        return BanService.INSTANCE.isBanned(ip);
    }

    /** Test seam over {@link UpstreamProxyClient}'s trusted upstream SSL context. */
    public static void overrideTrustedUpstreamSslContextForTests(SSLContext context) {
        UpstreamProxyClient.overrideTrustedUpstreamSslContextForTests(context);
    }

    // -----------------------------------------------------------------------
    // Stats
    // -----------------------------------------------------------------------

    public int getExactRouteCount() {
        int count = 0;
        for (List<RouteEntry> bucket : routes.exactRoutes.values()) {
            count += bucket.size();
        }
        return count;
    }

    public int getWildcardRouteCount() {
        return routes.wildcardRoutes.size();
    }

    public int getRegexRouteCount() {
        return routes.regexRoutes.size();
    }

    public TlsPassthroughRoutes.Decision resolveTlsRoute(String serverName, String listenerIp) {
        RouteTable generation = routes;
        return tlsPassthroughRoutes.resolve(generation.tlsRoutes, serverName, listenerIp);
    }

    public boolean hasTlsPassthroughRoutes() {
        RouteTable generation = routes;
        return tlsPassthroughRoutes.hasPassthroughRoutes(generation.tlsRoutes);
    }

    /**
     * Find the active request handler for a given site ID.
     * Returns null if the site is not currently loaded.
     */
    public SiteRequestHandler findHandlerBySiteId(int siteId) {
        for (RouteEntry entry : this.routes.entries()) {
            if (entry.handler.getSiteId() == siteId) return entry.handler;
        }
        return null;
    }

    /**
     * What visitors of this site get: its handler's own health, DOWN when the upstream refused the last dial, a
     * visitor's or the dispatcher's own probe of a fixed upstream (a handler that never probes reports UP regardless).
     *
     * @return the site's health, null when no route serves it
     */
    public @Nullable SiteHealth healthOf(int siteId) {
        // By the route's own site id, not the handler's: a lambda handler reports -1 and would never be found.
        SiteRequestHandler handler = null;
        for (RouteEntry entry : this.routes.entries()) {
            if (entry.siteId == siteId) {
                handler = entry.handler;
                break;
            }
        }
        if (handler == null) {
            return null;
        }
        SiteHealth own = handler.getHealth();
        return own == SiteHealth.UP && Boolean.FALSE.equals(upstreamAnswered.get(siteId)) ? SiteHealth.DOWN : own;
    }

    /**
     * Full teardown, called from ProxyServer.stop(): destroys every routed handler
     * (reaping managed child processes) before stopping the internal schedulers.
     */
    public void shutdown() {
        RouteTable previous;
        synchronized (generationLock) {
            previous = routes;
            routes = RouteTable.empty();
            previous.retired = true;
        }
        destroyIfUnused(previous, false);
        retirementRunner.shutdown();
        try {
            while (!retirementRunner.awaitTermination(30_000L)) {
                Blast.log("SiteDispatcher: waiting for retired route handlers to stop");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        delayScheduler.shutdownNow();
    }
}
