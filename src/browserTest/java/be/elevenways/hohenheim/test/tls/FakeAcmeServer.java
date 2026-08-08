package be.elevenways.hohenheim.test.tls;

import be.elevenways.hohenheim.server.util.Json;
import be.elevenways.protoblast.common.dry.Dry;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

/**
 * A REAL ACME (RFC 8555) certificate authority in this JVM: directory, nonces, account
 * registration, orders, authorizations, challenge validation, finalize and a certificate
 * chain signed by its own throwaway CA -- everything hohenheim's acme4j client actually
 * speaks, over plain HTTP on a loopback port.
 *
 * The point is that the CLIENT half is genuine: acme4j signs real JWS requests, the CSR is
 * a real PKCS#10 this server parses, and the answer is a real X.509 chain whose notAfter
 * the product reads. Only the CA's willingness is faked.
 *
 * AIDEV-NOTE: a Pebble container was the other candidate and was REJECTED, because it puts
 * this proof behind a Docker socket and a pre-pulled image -- the exact gate that made ACME
 * the highest-value unmeasured mechanism in the product in the first place. Faking the
 * DIRECTORY instead of the daemon costs the guarantee that our wire bytes satisfy a
 * third-party CA (see the test's own "cannot prove" list) and buys a proof that runs
 * everywhere, every time. The real Let's Encrypt is never reached: the test points
 * {@code ssl.acme_directory_url} here.
 *
 * EVERY FAKE MUST BE ABLE TO SAY NO. This one validates for real: an HTTP-01 challenge is
 * accepted only when hohenheim's own responder answers the token (and refuses to answer for
 * a hostname it was not offered), a DNS-01 challenge only when the value was really
 * published; {@link #refuseValidation} makes a well-formed order fail validation the way a
 * CA does, and {@link #refuseFinalize} rejects the order at finalize.
 */
public final class FakeAcmeServer implements AutoCloseable {

    /** How a challenge is validated: the token/identifier in, "did it pass" out. */
    public interface Validator extends BiFunction<String, String, Boolean> {
    }

    private final HttpServer server;
    private final String base;
    private final KeyPair caKeyPair;
    private final X509Certificate caCertificate;
    private final SecureRandom random = new SecureRandom();
    private final AtomicInteger counter = new AtomicInteger();

    /** orderId -> the order's state. */
    private final Map<String, Order> orders = new ConcurrentHashMap<>();

    /** authzId -> the authorization's state. */
    private final Map<String, Authz> authorizations = new ConcurrentHashMap<>();

    /** Every request path this CA served, in order; the assertable protocol trace. */
    private final List<String> requests = new ArrayList<>();

    private volatile @Nullable Validator httpValidator;
    private volatile @Nullable Validator dnsValidator;
    private volatile boolean refuseValidation;
    private volatile boolean refuseFinalize;
    private volatile int certificateDays = 90;

    private static final class Order {
        final List<String> identifiers = new ArrayList<>();
        final List<String> authzIds = new ArrayList<>();
        String status = "pending";
        @Nullable String certificateId;
        @Nullable String error;
    }

    private static final class Authz {
        String identifier = "";
        boolean wildcard;
        String token = "";
        String status = "pending";
        @Nullable String error;
    }

    public FakeAcmeServer() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        this.caKeyPair = generator.generateKeyPair();
        this.caCertificate = selfSignedCa(this.caKeyPair);
        this.server = HttpServer.create(
            new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        this.base = "http://127.0.0.1:" + this.server.getAddress().getPort();
        this.server.createContext("/", this::handle);
        this.server.start();
    }

    /** The directory URL to point {@code ssl.acme_directory_url} at. */
    public @NonNull String directoryUrl() {
        return this.base + "/directory";
    }

    /** Decide HTTP-01 challenges: (token, identifier) -> passed. */
    public void validateHttpWith(@NonNull Validator validator) {
        this.httpValidator = validator;
    }

    /** Decide DNS-01 challenges: (token, identifier) -> passed. */
    public void validateDnsWith(@NonNull Validator validator) {
        this.dnsValidator = validator;
    }

    /** Fail every authorization the way a CA that could not reach the host does. */
    public void refuseValidation(boolean refuse) {
        this.refuseValidation = refuse;
    }

    /** Reject the order at finalize, after its authorizations passed. */
    public void refuseFinalize(boolean refuse) {
        this.refuseFinalize = refuse;
    }

    /** Issue certificates that expire in {@code days}; the renewal window's input. */
    public void issueValidFor(int days) {
        this.certificateDays = days;
    }

    /** Every path this CA served, in order. */
    public @NonNull List<String> requests() {
        synchronized (this.requests) {
            return List.copyOf(this.requests);
        }
    }

    /** How many times one exact path was served. */
    public int requestCount(@NonNull String path) {
        int count = 0;
        for (String served : requests()) {
            if (served.equals(path)) {
                count++;
            }
        }
        return count;
    }

    @Override
    public void close() {
        this.server.stop(0);
    }

    // -- the protocol ---------------------------------------------------------

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        synchronized (this.requests) {
            this.requests.add(path);
        }
        try {
            route(exchange, path);
        } catch (RuntimeException | Error failure) {
            problem(exchange, 500, "urn:ietf:params:acme:error:serverInternal",
                String.valueOf(failure.getMessage()));
        } catch (Exception failure) {
            problem(exchange, 500, "urn:ietf:params:acme:error:serverInternal",
                String.valueOf(failure.getMessage()));
        } finally {
            exchange.close();
        }
    }

    private void route(HttpExchange exchange, String path) throws Exception {
        if (path.equals("/directory")) {
            Map<String, Object> directory = new LinkedHashMap<>();
            directory.put("newNonce", this.base + "/new-nonce");
            directory.put("newAccount", this.base + "/new-account");
            directory.put("newOrder", this.base + "/new-order");
            directory.put("revokeCert", this.base + "/revoke-cert");
            directory.put("keyChange", this.base + "/key-change");
            directory.put("meta", Map.of("termsOfService", this.base + "/terms"));
            json(exchange, 200, directory);
            return;
        }
        if (path.equals("/new-nonce")) {
            respond(exchange, 204, new byte[0], "application/json");
            return;
        }
        if (path.equals("/new-account")) {
            String account = this.base + "/account/1";
            exchange.getResponseHeaders().add("Location", account);
            json(exchange, 201, Map.of("status", "valid",
                "orders", this.base + "/account/1/orders"));
            return;
        }
        if (path.equals("/new-order")) {
            newOrder(exchange);
            return;
        }
        if (path.startsWith("/authz/")) {
            authorization(exchange, path.substring("/authz/".length()));
            return;
        }
        if (path.startsWith("/challenge/")) {
            triggerChallenge(exchange, path.substring("/challenge/".length()));
            return;
        }
        if (path.startsWith("/finalize/")) {
            finalizeOrder(exchange, path.substring("/finalize/".length()));
            return;
        }
        if (path.startsWith("/order/")) {
            order(exchange, path.substring("/order/".length()));
            return;
        }
        if (path.startsWith("/certificate/")) {
            certificate(exchange, path.substring("/certificate/".length()));
            return;
        }
        problem(exchange, 404, "urn:ietf:params:acme:error:malformed",
            "FakeAcmeServer: unhandled " + path);
    }

    @SuppressWarnings("unchecked")
    private void newOrder(HttpExchange exchange) throws Exception {
        Map<String, Object> payload = payloadOf(exchange);
        Order order = new Order();
        String orderId = "o" + this.counter.incrementAndGet();
        List<String> authzUrls = new ArrayList<>();
        Object identifiers = payload.get("identifiers");
        if (identifiers instanceof List<?> list) {
            for (Object entry : list) {
                String value = entry instanceof Map<?, ?> map
                    ? String.valueOf(map.get("value")) : String.valueOf(entry);
                order.identifiers.add(value);
                Authz authz = new Authz();
                // RFC 8555: a wildcard SAN is authorized under the BASE identifier with the
                // wildcard flag set; a server that echoed "*.example.com" back would let a
                // client bug through unnoticed.
                authz.wildcard = value.startsWith("*.");
                authz.identifier = authz.wildcard ? value.substring(2) : value;
                authz.token = base64url(randomBytes(16));
                String authzId = "a" + this.counter.incrementAndGet();
                this.authorizations.put(authzId, authz);
                order.authzIds.add(authzId);
                authzUrls.add(this.base + "/authz/" + authzId);
            }
        }
        this.orders.put(orderId, order);
        exchange.getResponseHeaders().add("Location", this.base + "/order/" + orderId);
        Map<String, Object> body = orderBody(orderId, order);
        body.put("authorizations", authzUrls);
        json(exchange, 201, body);
    }

    private void authorization(HttpExchange exchange, String authzId) throws Exception {
        Authz authz = this.authorizations.get(authzId);
        if (authz == null) {
            problem(exchange, 404, "urn:ietf:params:acme:error:malformed", "no such authz");
            return;
        }
        json(exchange, 200, authzBody(authzId, authz));
    }

    private void triggerChallenge(HttpExchange exchange, String challengeId) throws Exception {
        String[] parts = challengeId.split("/", 2);
        Authz authz = this.authorizations.get(parts[0]);
        if (authz == null) {
            problem(exchange, 404, "urn:ietf:params:acme:error:malformed", "no such authz");
            return;
        }
        payloadOf(exchange);   // the trigger's empty object; consumed for framing
        String type = parts.length > 1 ? parts[1] : "http-01";
        // THE VALIDATION. A CA that answered "valid" without asking anything would make
        // every challenge test vacuous, so this really interrogates the product's own
        // responder (HTTP-01) or the published TXT value (DNS-01).
        boolean passed;
        if (this.refuseValidation) {
            passed = false;
        } else {
            Validator validator = "dns-01".equals(type) ? this.dnsValidator : this.httpValidator;
            passed = validator != null && Boolean.TRUE.equals(
                validator.apply(authz.token, authz.identifier));
        }
        authz.status = passed ? "valid" : "invalid";
        if (!passed) {
            authz.error = "the CA could not validate " + authz.identifier;
        }
        Map<String, Object> body = challengeBody(parts[0], authz, type);
        body.put("status", authz.status);
        json(exchange, 200, body);
    }

    private void finalizeOrder(HttpExchange exchange, String orderId) throws Exception {
        Order order = this.orders.get(orderId);
        if (order == null) {
            problem(exchange, 404, "urn:ietf:params:acme:error:malformed", "no such order");
            return;
        }
        Map<String, Object> payload = payloadOf(exchange);
        for (String authzId : order.authzIds) {
            Authz authz = this.authorizations.get(authzId);
            if (authz == null || !"valid".equals(authz.status)) {
                order.status = "invalid";
                order.error = "an authorization is not valid";
                json(exchange, 200, orderBody(orderId, order));
                return;
            }
        }
        if (this.refuseFinalize) {
            order.status = "invalid";
            order.error = "the CA rejected the certificate signing request";
            json(exchange, 200, orderBody(orderId, order));
            return;
        }
        byte[] csr = base64urlDecode(String.valueOf(payload.get("csr")));
        String certificateId = "c" + this.counter.incrementAndGet();
        CERTIFICATES.put(certificateId, sign(csr, order.identifiers));
        order.certificateId = certificateId;
        order.status = "valid";
        json(exchange, 200, orderBody(orderId, order));
    }

    private void order(HttpExchange exchange, String orderId) throws Exception {
        Order order = this.orders.get(orderId);
        if (order == null) {
            problem(exchange, 404, "urn:ietf:params:acme:error:malformed", "no such order");
            return;
        }
        json(exchange, 200, orderBody(orderId, order));
    }

    private void certificate(HttpExchange exchange, String certificateId) throws Exception {
        String chain = CERTIFICATES.get(certificateId);
        if (chain == null) {
            problem(exchange, 404, "urn:ietf:params:acme:error:malformed", "no such certificate");
            return;
        }
        respond(exchange, 200, chain.getBytes(StandardCharsets.UTF_8),
            "application/pem-certificate-chain");
    }

    /** certificateId -> the issued PEM chain; static so a restart of the server keeps them. */
    private static final Map<String, String> CERTIFICATES = new ConcurrentHashMap<>();

    // -- bodies ---------------------------------------------------------------

    private Map<String, Object> orderBody(String orderId, Order order) {
        List<Object> identifiers = new ArrayList<>();
        for (String identifier : order.identifiers) {
            identifiers.add(Map.of("type", "dns", "value", identifier));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", order.status);
        body.put("expires", Instant.now().plus(1, ChronoUnit.DAYS).toString());
        body.put("identifiers", identifiers);
        body.put("finalize", this.base + "/finalize/" + orderId);
        List<Object> authzUrls = new ArrayList<>();
        for (String authzId : order.authzIds) {
            authzUrls.add(this.base + "/authz/" + authzId);
        }
        body.put("authorizations", authzUrls);
        if (order.certificateId != null) {
            body.put("certificate", this.base + "/certificate/" + order.certificateId);
        }
        if (order.error != null) {
            body.put("error", Map.of("type", "urn:ietf:params:acme:error:badCSR",
                "detail", order.error));
        }
        return body;
    }

    private Map<String, Object> authzBody(String authzId, Authz authz) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", authz.status);
        body.put("expires", Instant.now().plus(1, ChronoUnit.DAYS).toString());
        body.put("identifier", Map.of("type", "dns", "value", authz.identifier));
        if (authz.wildcard) {
            body.put("wildcard", true);
        }
        List<Object> challenges = new ArrayList<>();
        challenges.add(challengeBody(authzId, authz, "http-01"));
        challenges.add(challengeBody(authzId, authz, "dns-01"));
        body.put("challenges", challenges);
        return body;
    }

    private Map<String, Object> challengeBody(String authzId, Authz authz, String type) {
        Map<String, Object> challenge = new LinkedHashMap<>();
        challenge.put("type", type);
        challenge.put("url", this.base + "/challenge/" + authzId + "/" + type);
        challenge.put("token", authz.token);
        challenge.put("status", authz.status);
        if (authz.error != null) {
            challenge.put("error", Map.of("type", "urn:ietf:params:acme:error:unauthorized",
                "detail", authz.error));
        }
        return challenge;
    }

    // -- the CA ---------------------------------------------------------------

    private static X509Certificate selfSignedCa(KeyPair keyPair) throws Exception {
        X500Name name = new X500Name("CN=Hohenheim Test CA");
        Instant now = Instant.now();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(name,
            BigInteger.ONE, Date.from(now.minus(1, ChronoUnit.DAYS)),
            Date.from(now.plus(3650, ChronoUnit.DAYS)), name, keyPair.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        return new JcaX509CertificateConverter().getCertificate(builder.build(
            new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate())));
    }

    /** Sign the client's REAL PKCS#10 into a REAL chain; a stub PEM would prove nothing. */
    private String sign(byte[] csrDer, List<String> identifiers) throws Exception {
        JcaPKCS10CertificationRequest csr = new JcaPKCS10CertificationRequest(csrDer);
        Instant now = Instant.now();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
            new X500Name("CN=Hohenheim Test CA"),
            BigInteger.valueOf(this.random.nextInt(Integer.MAX_VALUE)),
            Date.from(now.minus(1, ChronoUnit.HOURS)),
            Date.from(now.plus(this.certificateDays, ChronoUnit.DAYS)),
            new X500Name("CN=" + identifiers.get(0)), csr.getPublicKey());
        List<GeneralName> names = new ArrayList<>();
        for (String identifier : identifiers) {
            names.add(new GeneralName(GeneralName.dNSName, identifier));
        }
        builder.addExtension(Extension.subjectAlternativeName, false,
            new GeneralNames(names.toArray(new GeneralName[0])));
        X509Certificate leaf = new JcaX509CertificateConverter().getCertificate(builder.build(
            new JcaContentSignerBuilder("SHA256withRSA").build(this.caKeyPair.getPrivate())));
        return pem(leaf) + pem(this.caCertificate);
    }

    private static String pem(X509Certificate certificate) throws Exception {
        return "-----BEGIN CERTIFICATE-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(certificate.getEncoded())
            + "\n-----END CERTIFICATE-----\n";
    }

    // -- wire plumbing --------------------------------------------------------

    /** The JWS payload of a POST, decoded; empty for a POST-as-GET. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> payloadOf(HttpExchange exchange) throws Exception {
        byte[] raw = exchange.getRequestBody().readAllBytes();
        if (raw.length == 0) {
            return Map.of();
        }
        Map<String, Object> jws = (Map<String, Object>) new Dry().parse(
            new String(raw, StandardCharsets.UTF_8));
        Object payload = jws.get("payload");
        if (payload == null || String.valueOf(payload).isEmpty()) {
            return Map.of();
        }
        String decoded = new String(base64urlDecode(String.valueOf(payload)),
            StandardCharsets.UTF_8);
        Object parsed = new Dry().parse(decoded);
        return parsed instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private void json(HttpExchange exchange, int status, Map<String, Object> body)
            throws IOException {
        respond(exchange, status, Json.stringify(body).getBytes(StandardCharsets.UTF_8),
            "application/json");
    }

    private void problem(HttpExchange exchange, int status, String type, String detail)
            throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("detail", detail);
        respond(exchange, status, Json.stringify(body).getBytes(StandardCharsets.UTF_8),
            "application/problem+json");
    }

    private void respond(HttpExchange exchange, int status, byte[] body, String contentType)
            throws IOException {
        Headers headers = exchange.getResponseHeaders();
        // EVERY response carries a fresh nonce; without it the client's next request has
        // nothing to sign against and the failure looks like a client bug.
        headers.add("Replay-Nonce", base64url(randomBytes(16)));
        headers.add("Content-Type", contentType);
        headers.add("Link", "<" + this.base + "/directory>;rel=\"index\"");
        if (body.length == 0) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        this.random.nextBytes(bytes);
        return bytes;
    }

    private static String base64url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] base64urlDecode(String text) {
        return Base64.getUrlDecoder().decode(text);
    }
}
