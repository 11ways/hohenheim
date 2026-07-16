package be.elevenways.hohenheim.server.tls;

import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.notification.NotificationEvents;
import be.elevenways.hohenheim.server.notification.NotificationService;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.server.security.SecureTokens;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.shredzone.acme4j.*;
import org.shredzone.acme4j.challenge.Http01Challenge;
import org.shredzone.acme4j.challenge.Dns01Challenge;
import org.shredzone.acme4j.util.CSRBuilder;
import org.shredzone.acme4j.util.KeyPairUtils;

import java.io.StringReader;
import java.io.StringWriter;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Manages Let's Encrypt certificate issuance and renewal via ACME protocol.
 * Uses acme4j for the ACME client and serves HTTP-01 challenges
 * through the proxy's SiteDispatcher.
 */
public class AcmeService {

    private static final long RENEWAL_CHECK_HOURS = 6;
    private static final int RENEWAL_THRESHOLD_DAYS = 30;
    private static final int MAX_POLL_ATTEMPTS = 40;
    private static final long POLL_INTERVAL_MS = 3000;
    private static final long MANUAL_DNS_ORDER_MINUTES = 30;

    private final CertificateStore certificateStore;
    private final ScheduledExecutorService scheduler;

    /**
     * Pending HTTP-01 challenges: token -> challenge entry with authorization and valid hostnames.
     */
    private final ConcurrentHashMap<String, ChallengeEntry> pendingChallenges = new ConcurrentHashMap<>();

    /** Same-account/SAN orders share one automated transaction or reserve one manual transaction. */
    private final ConcurrentHashMap<String, OrderFlight> inFlightOrders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PendingManualDnsOrder> manualDnsOrders = new ConcurrentHashMap<>();

    // ACME account sessions keyed by normalized override email ("" = the global account),
    // each with its own persisted key pair. Guarded by the synchronized ensureAccount.
    private final Map<String, Account> accounts = new HashMap<>();

    private record ChallengeEntry(String authorization, Set<String> validHostnames) {}

    /**
     * Result of an ACME certificate order.
     */
    private record OrderResult(String certPem, String keyPem, Instant expiresAt) {}

    /** certificateId carries the leader's row id so joiners can share the row instead of minting a duplicate. */
    private record OrderFlight(boolean joinable, CompletableFuture<OrderResult> result, AtomicInteger certificateId) {
        OrderFlight(boolean joinable, CompletableFuture<OrderResult> result) {
            this(joinable, result, new AtomicInteger(-1));
        }
    }

    private record DnsAuthorization(Authorization authorization, Dns01Challenge challenge,
                                    DnsTxtRecord record) {}

    private record DnsOrderContext(Order order, KeyPair domainKeyPair, List<String> hostnames,
                                   List<DnsAuthorization> authorizations) {}

    private record PendingManualDnsOrder(int certificateId, DnsOrderContext order, Instant createdAt,
                                         String orderKey, OrderFlight flight) {}

    /** Safe UI projection of an in-memory manual DNS order. */
    public record ManualDnsRequest(String token, int certificateId, List<DnsTxtRecord> records) {}

    public AcmeService(CertificateStore certificateStore) {
        this.certificateStore = certificateStore;
        DnsTxtPublishers.INSTANCE.register(new CommandDnsTxtPublisher());
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "acme-renewal");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::checkRenewals, RENEWAL_CHECK_HOURS,
            RENEWAL_CHECK_HOURS, TimeUnit.HOURS);
        Blast.log("ACME renewal scheduler started (every", RENEWAL_CHECK_HOURS, "hours)");
    }

    public void stop() {
        scheduler.shutdownNow();
        manualDnsOrders.forEach((token, pending) -> abandonManualDnsOrder(token, pending,
            "Manual DNS challenge interrupted by server shutdown"));
    }

    /**
     * Lookup a pending HTTP-01 challenge response by token, validating the hostname.
     * Returns null if the token is not pending or the hostname is not authorized.
     */
    public String getChallengeResponse(String token, String hostname) {
        ChallengeEntry entry = pendingChallenges.get(token);
        if (entry == null) return null;

        // Validate that the requesting hostname is one we're issuing a cert for
        if (hostname != null && !entry.validHostnames.contains(hostname.toLowerCase())) {
            return null;
        }

        return entry.authorization;
    }

    /**
     * Request a new Let's Encrypt certificate for the given hostnames.
     * Blocks until the certificate is issued or an error occurs.
     *
     * @return the certificate database row ID, or -1 on failure
     */
    public int requestCertificate(List<String> hostnames, String niceName, @Nullable String email) {
        return requestCertificate(hostnames, niceName, email,
            CertificateModel.CHALLENGE_HTTP, null);
    }

    public int requestCertificate(List<String> hostnames, String niceName, @Nullable String email,
                                  String challengeType, @Nullable String dnsPublisher) {
        var certModel = Models.get(CertificateModel.class);

        // Claim the order key BEFORE creating a row: a concurrent identical
        // request shares the leader's certificate row instead of minting a
        // duplicate ACTIVE record for the same domains.
        String orderKey = certificateOrderKey(hostnames, email);
        OrderFlight flight = new OrderFlight(true, new CompletableFuture<>());
        OrderFlight existing = inFlightOrders.putIfAbsent(orderKey, flight);
        if (existing != null) {
            if (!existing.joinable()) {
                Blast.log("ACME: a manual order for", String.join(", ", hostnames), "is already in progress");
                return -1;
            }
            try {
                existing.result().get();
                return existing.certificateId().get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            } catch (Exception e) {
                return -1;
            }
        }

        Row certRow = certModel.createEmptyRow();
        certRow.set(CertificateModel.NICE_NAME, niceName);
        certRow.set(CertificateModel.PROVIDER, CertificateModel.PROVIDER_LETSENCRYPT);
        certRow.set(CertificateModel.STATUS, CertificateModel.STATUS_PENDING);
        certRow.set(CertificateModel.DOMAIN_NAMES_TEXT, String.join(",", hostnames));
        certRow.set(CertificateModel.CHALLENGE_TYPE, challengeType);
        certRow.set(CertificateModel.DNS_PUBLISHER, dnsPublisher);
        if (email != null && !email.isBlank()) {
            certRow.set(CertificateModel.LETSENCRYPT_EMAIL, email.trim());
        }
        certModel.save(certRow);
        int certId = certRow.get(CertificateModel.ID);
        flight.certificateId().set(certId);

        try {
            boolean dns = CertificateModel.CHALLENGE_DNS.equals(challengeType);
            List<String> invalid = invalidHostnames(hostnames, dns);
            if (!invalid.isEmpty()) {
                throw new IllegalArgumentException("Invalid hostnames: " + String.join(", ", invalid));
            }

            OrderResult result = performAcmeOrderUncoalesced(hostnames, email, challengeType, dnsPublisher);
            flight.result().complete(result);

            certRow.set(CertificateModel.CERTIFICATE_PEM, result.certPem);
            certRow.set(CertificateModel.PRIVATE_KEY_PEM, result.keyPem);
            certRow.set(CertificateModel.EXPIRES_ON, result.expiresAt);
            certRow.set(CertificateModel.ISSUED_ON, Instant.now());
            markRenewalSuccess(certRow);
            certModel.save(certRow);

            certificateStore.loadFromDatabase();
            Blast.log("ACME: certificate issued for", String.join(", ", hostnames));
            return certId;

        } catch (Exception e) {
            flight.result().completeExceptionally(e);
            Blast.log("ACME: certificate request failed for", String.join(", ", hostnames), "-", e.getMessage());

            recordRenewalFailure(certRow, e.getMessage());
            certModel.save(certRow);

            return -1;
        } finally {
            inFlightOrders.remove(orderKey, flight);
        }
    }

    /** Begin a DNS-01 order and return the TXT values the operator must publish. */
    public ManualDnsRequest prepareManualDnsCertificate(List<String> hostnames, String niceName,
                                                         @Nullable String email) throws Exception {
        List<String> invalid = invalidHostnames(hostnames, true);
        if (!invalid.isEmpty()) {
            throw new IllegalArgumentException("Invalid hostnames: " + String.join(", ", invalid));
        }

        String orderKey = certificateOrderKey(hostnames, email);
        OrderFlight flight = new OrderFlight(false, new CompletableFuture<>());
        if (inFlightOrders.putIfAbsent(orderKey, flight) != null) {
            throw new IllegalStateException("A certificate order for these hostnames is already in progress");
        }

        var certModel = Models.get(CertificateModel.class);
        Row certRow = certModel.createEmptyRow();
        try {
            certRow.set(CertificateModel.NICE_NAME, niceName);
            certRow.set(CertificateModel.PROVIDER, CertificateModel.PROVIDER_LETSENCRYPT);
            certRow.set(CertificateModel.STATUS, CertificateModel.STATUS_PENDING);
            certRow.set(CertificateModel.DOMAIN_NAMES_TEXT, String.join(",", hostnames));
            certRow.set(CertificateModel.CHALLENGE_TYPE, CertificateModel.CHALLENGE_DNS);
            certRow.set(CertificateModel.DNS_PUBLISHER, CertificateModel.DNS_PUBLISHER_MANUAL);
            certRow.set(CertificateModel.AUTO_RENEW, false);
            if (email != null && !email.isBlank()) {
                certRow.set(CertificateModel.LETSENCRYPT_EMAIL, email.trim());
            }
            certModel.save(certRow);

            DnsOrderContext order = prepareDnsOrder(hostnames, email);
            String token = SecureTokens.randomToken();
            int certificateId = certRow.get(CertificateModel.ID);
            PendingManualDnsOrder pending = new PendingManualDnsOrder(
                certificateId, order, Instant.now(), orderKey, flight);
            manualDnsOrders.put(token, pending);
            scheduler.schedule(() -> expireManualDnsOrder(token, pending),
                MANUAL_DNS_ORDER_MINUTES, TimeUnit.MINUTES);
            return manualRequest(token, manualDnsOrders.get(token));
        } catch (Exception e) {
            flight.result().completeExceptionally(e);
            inFlightOrders.remove(orderKey, flight);
            recordRenewalFailure(certRow, e.getMessage());
            certModel.save(certRow);
            throw e;
        }
    }

    public @Nullable ManualDnsRequest manualDnsRequest(@Nullable String token) {
        PendingManualDnsOrder pending = token != null ? manualDnsOrders.get(token) : null;
        if (pending == null) {
            return null;
        }
        if (pending.createdAt().isBefore(Instant.now()
            .minus(MANUAL_DNS_ORDER_MINUTES, ChronoUnit.MINUTES))) {
            expireManualDnsOrder(token, pending);
            return null;
        }
        return manualRequest(token, pending);
    }

    /** Trigger and finish a manual order after the operator confirms all TXT values exist. */
    public int completeManualDnsCertificate(String token) {
        PendingManualDnsOrder pending = manualDnsOrders.remove(token);
        if (pending == null) {
            return -1;
        }
        var certModel = Models.get(CertificateModel.class);
        Row certRow = certModel.findById(pending.certificateId());
        if (certRow == null) {
            IllegalStateException missing = new IllegalStateException("Pending certificate row no longer exists");
            pending.flight().result().completeExceptionally(missing);
            inFlightOrders.remove(pending.orderKey(), pending.flight());
            return -1;
        }
        try {
            completeDnsAuthorizations(pending.order().authorizations());
            OrderResult result = finalizeOrder(pending.order());
            certRow.set(CertificateModel.CERTIFICATE_PEM, result.certPem());
            certRow.set(CertificateModel.PRIVATE_KEY_PEM, result.keyPem());
            certRow.set(CertificateModel.EXPIRES_ON, result.expiresAt());
            certRow.set(CertificateModel.ISSUED_ON, Instant.now());
            markRenewalSuccess(certRow);
            certModel.save(certRow);
            certificateStore.loadFromDatabase();
            pending.flight().result().complete(result);
            return pending.certificateId();
        } catch (Exception e) {
            pending.flight().result().completeExceptionally(e);
            recordRenewalFailure(certRow, e.getMessage());
            certModel.save(certRow);
            return -1;
        } finally {
            inFlightOrders.remove(pending.orderKey(), pending.flight());
        }
    }

    private static ManualDnsRequest manualRequest(String token, PendingManualDnsOrder pending) {
        List<DnsTxtRecord> records = pending.order().authorizations().stream()
            .map(DnsAuthorization::record)
            .toList();
        return new ManualDnsRequest(token, pending.certificateId(), records);
    }

    private static void markManualOrderLost(PendingManualDnsOrder pending, String message) {
        Row cert = Models.get(CertificateModel.class).findById(pending.certificateId());
        if (cert != null) {
            recordRenewalFailure(cert, message);
            Models.get(CertificateModel.class).save(cert);
        }
    }

    private void expireManualDnsOrder(String token, PendingManualDnsOrder pending) {
        abandonManualDnsOrder(token, pending, "Manual DNS challenge expired; start a new request");
    }

    private void abandonManualDnsOrder(String token, PendingManualDnsOrder pending, String message) {
        if (!manualDnsOrders.remove(token, pending)) return;
        pending.flight().result().completeExceptionally(new IllegalStateException(message));
        inFlightOrders.remove(pending.orderKey(), pending.flight());
        markManualOrderLost(pending, message);
    }

    // -----------------------------------------------------------------------
    // Hostname validation
    // -----------------------------------------------------------------------

    private static final Pattern HOSTNAME_LABEL =
        Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");

    /** RFC-1123 hostname check for HTTP-01, which cannot validate wildcards. */
    public static boolean isValidHostname(String hostname) {
        if (hostname == null) return false;
        String h = hostname.trim().toLowerCase();
        if (h.isEmpty() || h.length() > 253 || !h.contains(".")) return false;
        for (String label : h.split("\\.", -1)) {
            if (!HOSTNAME_LABEL.matcher(label).matches()) return false;
        }
        return true;
    }

    /** @return the subset of hostnames that fail {@link #isValidHostname} */
    public static List<String> invalidHostnames(List<String> hostnames) {
        return invalidHostnames(hostnames, false);
    }

    /** @return invalid names, optionally accepting one leading wildcard label for DNS-01 */
    public static List<String> invalidHostnames(List<String> hostnames, boolean allowWildcard) {
        List<String> invalid = new ArrayList<>();
        for (String hostname : hostnames) {
            String candidate = hostname != null ? hostname.trim().toLowerCase(Locale.ROOT) : null;
            if (allowWildcard && candidate != null && candidate.startsWith("*.")) {
                candidate = candidate.substring(2);
            }
            if (!isValidHostname(candidate)) {
                invalid.add(hostname);
            }
        }
        return invalid;
    }

    /**
     * Check all Let's Encrypt certificates for upcoming expiry (plus errored ones whose
     * backoff elapsed) and renew them.
     */
    private void checkRenewals() {
        try {
            var ds = HohenheimDatabase.datasource();
            var certModel = Models.get(CertificateModel.class);

            checkExpiryAlerts(certModel, Instant.now());

            List<Row> due = findRenewalCandidates(certModel, Instant.now());
            if (due.isEmpty()) return;

            Blast.log("ACME: found", due.size(), "certificates due for renewal");

            // Stagger: randomize order so a fleet of instances doesn't hammer the CA
            // with the same sequence every sweep.
            Collections.shuffle(due);

            for (Row cert : due) {
                renewCertificate(cert, certModel);
            }
        } catch (Exception e) {
            Blast.log("ACME: renewal check failed:", e.getMessage());
        }
    }

    /** Days before expiry at which the expiring-soon alert fires. */
    static final int EXPIRY_ALERT_DAYS = 14;

    /**
     * Alert once per expiry cycle for certificates expiring soon: custom uploads never
     * auto-renew, and a Let's Encrypt cert this close to expiry means renewal is stuck.
     * The dedup stamp self-re-arms -- a successful renewal moves expires_on forward,
     * which makes the stamp older than the new alert window.
     */
    public static void checkExpiryAlerts(CertificateModel certModel, java.time.Instant now) {
        java.time.Instant cutoff = now.plus(EXPIRY_ALERT_DAYS, ChronoUnit.DAYS);
        for (Row cert : certModel.findExpiringSoon(cutoff)) {
            java.time.Instant expiresOn = cert.get(CertificateModel.EXPIRES_ON);
            if (expiresOn == null) continue;
            java.time.Instant notifiedAt = cert.get(CertificateModel.EXPIRY_NOTIFIED_AT);
            java.time.Instant alertWindowStart = expiresOn.minus(EXPIRY_ALERT_DAYS, ChronoUnit.DAYS);
            if (notifiedAt != null && !notifiedAt.isBefore(alertWindowStart)) {
                continue;   // already alerted for this expiry cycle
            }
            String niceName = cert.get(CertificateModel.NICE_NAME);
            try {
                new NotificationService().send(NotificationEvents.CERT_EXPIRING,
                    "Certificate expiring soon",
                    "Certificate '" + niceName + "' expires on " + expiresOn
                        + ". Renew or replace it before then.");
            } catch (Exception e) {
                Blast.log("ACME: could not send expiry notification -", e.getMessage());
            }
            cert.set(CertificateModel.EXPIRY_NOTIFIED_AT, now);
            certModel.save(cert);
        }
    }

    /**
     * Active certificates nearing expiry, plus errored certificates whose retry backoff
     * has elapsed (errored certs used to be filtered out forever).
     */
    public static List<Row> findRenewalCandidates(CertificateModel certModel, Instant now) {
        List<Row> due = new ArrayList<>();

        Instant cutoff = now.plus(RENEWAL_THRESHOLD_DAYS, ChronoUnit.DAYS);
        due.addAll(certModel.find()
            .where(CertificateModel.PROVIDER.eq(CertificateModel.PROVIDER_LETSENCRYPT))
            .where(CertificateModel.STATUS.eq(CertificateModel.STATUS_ACTIVE))
            .where(CertificateModel.AUTO_RENEW.eq(true))
            .where(CertificateModel.EXPIRES_ON.lte(cutoff))
            .all());

        List<Row> errored = certModel.find()
            .where(CertificateModel.PROVIDER.eq(CertificateModel.PROVIDER_LETSENCRYPT))
            .where(CertificateModel.STATUS.eq(CertificateModel.STATUS_ERROR))
            .where(CertificateModel.AUTO_RENEW.eq(true))
            .all();
        for (Row cert : errored) {
            Instant nextAttempt = cert.get(CertificateModel.NEXT_ATTEMPT_AT);
            if (nextAttempt == null || !nextAttempt.isAfter(now)) {
                due.add(cert);
            }
        }

        return due;
    }

    private void renewCertificate(Row certRow, CertificateModel certModel) {
        String domainsText = certRow.get(CertificateModel.DOMAIN_NAMES_TEXT);
        String niceName = certRow.get(CertificateModel.NICE_NAME);
        if (domainsText == null || domainsText.isEmpty()) return;

        List<String> hostnames = Arrays.asList(domainsText.split(","));

        try {
            String challengeType = certRow.get(CertificateModel.CHALLENGE_TYPE);
            if (challengeType == null || challengeType.isBlank()) {
                challengeType = CertificateModel.CHALLENGE_HTTP;
            }
            OrderResult result = performAcmeOrder(hostnames,
                certRow.get(CertificateModel.LETSENCRYPT_EMAIL), challengeType,
                certRow.get(CertificateModel.DNS_PUBLISHER),
                certRow.get(CertificateModel.ID));

            certRow.set(CertificateModel.CERTIFICATE_PEM, result.certPem);
            certRow.set(CertificateModel.PRIVATE_KEY_PEM, result.keyPem);
            certRow.set(CertificateModel.EXPIRES_ON, result.expiresAt);
            certRow.set(CertificateModel.ISSUED_ON, Instant.now());
            markRenewalSuccess(certRow);
            certModel.save(certRow);

            certificateStore.loadFromDatabase();
            Blast.log("ACME: renewed certificate", niceName);

        } catch (Exception e) {
            Blast.log("ACME: renewal failed for", niceName, "-", e.getMessage());
            recordRenewalFailure(certRow, e.getMessage());
            certModel.save(certRow);
            notifyRenewalFailure(certRow, niceName, e.getMessage());
        }
    }

    /**
     * Alert the configured notification channels the FIRST time a certificate's renewal
     * fails (the count resets on success, so a relapse alerts again). Subsequent retries
     * back off silently; the certificates page shows the live error state. Delivery is
     * best-effort -- a notification problem must never break the renewal bookkeeping.
     */
    private static void notifyRenewalFailure(Row certRow, String niceName, String message) {
        Integer errorCount = certRow.get(CertificateModel.ERROR_COUNT);
        if (errorCount == null || errorCount != 1) return;
        try {
            new NotificationService().send(NotificationEvents.CERT_RENEWAL_FAILED,
                "Certificate renewal failing",
                "Renewal of " + niceName + " failed: " + message
                    + "\nRetries continue with escalating backoff; see the certificates page.");
        } catch (Exception e) {
            Blast.log("ACME: could not send renewal-failure notification -", e.getMessage());
        }
    }

    /** Reset error/backoff state after a successful issuance or renewal. */
    public static void markRenewalSuccess(Row certRow) {
        certRow.set(CertificateModel.STATUS, CertificateModel.STATUS_ACTIVE);
        certRow.set(CertificateModel.RENEWAL_ERROR, null);
        certRow.set(CertificateModel.ERROR_COUNT, 0);
        certRow.set(CertificateModel.NEXT_ATTEMPT_AT, null);
    }

    /** Record a failed issuance/renewal: escalate the error count and schedule the retry. */
    public static void recordRenewalFailure(Row certRow, String message) {
        Integer previous = certRow.get(CertificateModel.ERROR_COUNT);
        int errorCount = (previous != null ? previous : 0) + 1;

        certRow.set(CertificateModel.STATUS, CertificateModel.STATUS_ERROR);
        certRow.set(CertificateModel.RENEWAL_ERROR, message);
        certRow.set(CertificateModel.ERROR_COUNT, errorCount);
        certRow.set(CertificateModel.NEXT_ATTEMPT_AT, computeNextAttempt(errorCount, Instant.now()));
    }

    /**
     * Escalating backoff: 15min * 2^min(count,7) with +/-20% jitter, so repeated CA failures
     * back off from ~30 minutes up to ~32 hours instead of retrying every sweep.
     */
    public static Instant computeNextAttempt(int errorCount, Instant now) {
        long baseSeconds = 15L * 60L * (1L << Math.min(errorCount, 7));
        double jitter = 0.8 + ThreadLocalRandom.current().nextDouble() * 0.4;
        return now.plusSeconds((long) (baseSeconds * jitter));
    }

    // -----------------------------------------------------------------------
    // Core ACME order flow (shared by request and renewal)
    // -----------------------------------------------------------------------

    private OrderResult performAcmeOrder(List<String> hostnames, @Nullable String email,
                                         String challengeType, @Nullable String dnsPublisher,
                                         int certificateId) throws Exception {
        boolean dns = CertificateModel.CHALLENGE_DNS.equals(challengeType);
        List<String> invalid = invalidHostnames(hostnames, dns);
        if (!invalid.isEmpty()) {
            throw new IllegalArgumentException("Invalid hostnames: " + String.join(", ", invalid));
        }

        String orderKey = certificateOrderKey(hostnames, email);
        OrderFlight leader = new OrderFlight(true, new CompletableFuture<>());
        leader.certificateId().set(certificateId);
        OrderFlight existing = inFlightOrders.putIfAbsent(orderKey, leader);
        if (existing != null) {
            if (!existing.joinable()) {
                throw new IllegalStateException("A manual certificate order for these hostnames is already in progress");
            }
            try {
                return existing.result().get();
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception exception) throw exception;
                if (cause instanceof Error error) throw error;
                throw new RuntimeException(cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
        }

        try {
            OrderResult result = performAcmeOrderUncoalesced(hostnames, email, challengeType, dnsPublisher);
            leader.result().complete(result);
            return result;
        } catch (Throwable failure) {
            leader.result().completeExceptionally(failure);
            if (failure instanceof Exception exception) throw exception;
            if (failure instanceof Error error) throw error;
            throw new RuntimeException(failure);
        } finally {
            inFlightOrders.remove(orderKey, leader);
        }
    }

    private static String certificateOrderKey(List<String> hostnames, @Nullable String email) {
        return normalizeAccountEmail(email) + ":" + hostnames.stream()
            .map(name -> name.trim().toLowerCase(Locale.ROOT))
            .distinct()
            .sorted()
            .collect(java.util.stream.Collectors.joining(","));
    }

    private OrderResult performAcmeOrderUncoalesced(List<String> hostnames, @Nullable String email,
                                                     String challengeType,
                                                     @Nullable String dnsPublisher) throws Exception {

        if (CertificateModel.CHALLENGE_DNS.equals(challengeType)) {
            DnsTxtPublisher publisher = DnsTxtPublishers.INSTANCE.get(dnsPublisher);
            if (publisher == null) {
                throw new IllegalStateException("DNS-01 publisher is unavailable: " + dnsPublisher);
            }
            DnsOrderContext context = prepareDnsOrder(hostnames, email);
            List<DnsTxtRecord> published = new ArrayList<>();
            try {
                for (DnsAuthorization authorization : context.authorizations()) {
                    publisher.publish(authorization.record());
                    published.add(authorization.record());
                }
                int propagation = HohenheimSettings.VALUES.getValue(
                    HohenheimSettings.Ssl.DNS_PROPAGATION_SECONDS);
                if (propagation > 0) {
                    Thread.sleep(TimeUnit.SECONDS.toMillis(propagation));
                }
                completeDnsAuthorizations(context.authorizations());
                return finalizeOrder(context);
            } finally {
                Collections.reverse(published);
                for (DnsTxtRecord record : published) {
                    try {
                        publisher.cleanup(record);
                    } catch (Exception cleanupFailure) {
                        Blast.log("ACME: DNS TXT cleanup failed for", record.name(), "-",
                            cleanupFailure.getMessage());
                    }
                }
            }
        }

        Account account = ensureAccount(normalizeAccountEmail(email));
        KeyPair domainKeyPair = KeyPairUtils.createKeyPair(2048);
        Order order = createOrder(account, hostnames);

        Set<String> hostnameSet = new HashSet<>();
        for (String h : hostnames) hostnameSet.add(h.toLowerCase());

        for (Authorization auth : order.getAuthorizations()) {
            if (auth.getStatus() == Status.VALID) continue;
            completeHttpChallenge(auth, hostnameSet);
        }

        return finalizeOrder(new DnsOrderContext(order, domainKeyPair, hostnames, List.of()));
    }

    private static Order createOrder(Account account, List<String> hostnames) throws Exception {
        OrderBuilder orderBuilder = account.newOrder();
        for (String hostname : hostnames) {
            orderBuilder.domain(hostname);
        }
        return orderBuilder.create();
    }

    private DnsOrderContext prepareDnsOrder(List<String> hostnames, @Nullable String email) throws Exception {
        Account account = ensureAccount(normalizeAccountEmail(email));
        KeyPair domainKeyPair = KeyPairUtils.createKeyPair(2048);
        Order order = createOrder(account, hostnames);
        List<DnsAuthorization> authorizations = new ArrayList<>();
        for (Authorization auth : order.getAuthorizations()) {
            if (auth.getStatus() == Status.VALID) {
                continue;
            }
            Dns01Challenge challenge = auth.findChallenge(Dns01Challenge.class)
                .orElseThrow(() -> new RuntimeException(
                    "No DNS-01 challenge available for " + auth.getIdentifier().getDomain()));
            authorizations.add(new DnsAuthorization(auth, challenge,
                new DnsTxtRecord(Dns01Challenge.toRRName(auth.getIdentifier()), challenge.getDigest())));
        }
        return new DnsOrderContext(order, domainKeyPair, List.copyOf(hostnames),
            List.copyOf(authorizations));
    }

    private void completeDnsAuthorizations(List<DnsAuthorization> authorizations) throws Exception {
        for (DnsAuthorization pending : authorizations) {
            pending.challenge().trigger();
        }
        for (DnsAuthorization pending : authorizations) {
            awaitAuthorization(pending.authorization());
        }
    }

    private void awaitAuthorization(Authorization auth) throws Exception {
        for (int i = 0; i < MAX_POLL_ATTEMPTS; i++) {
            Thread.sleep(POLL_INTERVAL_MS);
            auth.update();
            if (auth.getStatus() == Status.VALID) return;
            if (auth.getStatus() == Status.INVALID) {
                throw new RuntimeException("Challenge failed for " + auth.getIdentifier().getDomain());
            }
        }
        throw new RuntimeException("Challenge timed out for " + auth.getIdentifier().getDomain());
    }

    private OrderResult finalizeOrder(DnsOrderContext context) throws Exception {
        CSRBuilder csrBuilder = new CSRBuilder();
        for (String hostname : context.hostnames()) {
            csrBuilder.addDomain(hostname);
        }
        csrBuilder.sign(context.domainKeyPair());
        context.order().execute(csrBuilder.getEncoded());

        Order order = context.order();
        for (int i = 0; i < MAX_POLL_ATTEMPTS && order.getStatus() != Status.VALID; i++) {
            Thread.sleep(POLL_INTERVAL_MS);
            order.update();
            if (order.getStatus() == Status.INVALID) {
                throw new RuntimeException("Order rejected by CA");
            }
        }

        if (order.getStatus() != Status.VALID) {
            throw new RuntimeException("Order did not complete in time");
        }

        Certificate acmeCert = order.getCertificate();
        List<X509Certificate> chain = acmeCert.getCertificateChain();
        X509Certificate leaf = chain.get(0);

        return new OrderResult(
            certificateChainToPem(chain),
            privateKeyToPem(context.domainKeyPair()),
            leaf.getNotAfter().toInstant()
        );
    }

    // -----------------------------------------------------------------------
    // HTTP-01 challenge handling
    // -----------------------------------------------------------------------

    private void completeHttpChallenge(Authorization auth, Set<String> validHostnames) throws Exception {
        Http01Challenge challenge = auth.findChallenge(Http01Challenge.class)
            .orElseThrow(() -> new RuntimeException(
                "No HTTP-01 challenge available for " + auth.getIdentifier().getDomain()));

        pendingChallenges.put(challenge.getToken(),
            new ChallengeEntry(challenge.getAuthorization(), validHostnames));

        try {
            challenge.trigger();

            for (int i = 0; i < MAX_POLL_ATTEMPTS; i++) {
                Thread.sleep(POLL_INTERVAL_MS);
                auth.update();
                if (auth.getStatus() == Status.VALID) return;
                if (auth.getStatus() == Status.INVALID) {
                    throw new RuntimeException("Challenge failed for " + auth.getIdentifier().getDomain());
                }
            }

            throw new RuntimeException("Challenge timed out for " + auth.getIdentifier().getDomain());
        } finally {
            pendingChallenges.remove(challenge.getToken());
        }
    }

    // -----------------------------------------------------------------------
    // ACME account
    // -----------------------------------------------------------------------

    /**
     * Map an override email to its account key: "" for null, blank, or the global
     * setting's own email (no point registering a duplicate account for it).
     */
    public static String normalizeAccountEmail(@Nullable String email) {
        if (email == null) return "";
        String normalized = email.trim().toLowerCase();
        if (normalized.isEmpty()) return "";

        String global = HohenheimSettings.VALUES.getValue(HohenheimSettings.Ssl.LETSENCRYPT_EMAIL);
        if (global != null && normalized.equals(global.trim().toLowerCase())) return "";

        return normalized;
    }

    private synchronized Account ensureAccount(String normalizedEmail) throws Exception {
        Account existing = accounts.get(normalizedEmail);
        if (existing != null) return existing;

        boolean staging = Boolean.TRUE.equals(
            HohenheimSettings.VALUES.getValue(HohenheimSettings.Ssl.LETSENCRYPT_STAGING));

        String serverUri = staging
            ? "acme://letsencrypt.org/staging"
            : "acme://letsencrypt.org";

        String email = normalizedEmail.isEmpty()
            ? HohenheimSettings.VALUES.getValue(HohenheimSettings.Ssl.LETSENCRYPT_EMAIL)
            : normalizedEmail;

        KeyPair keyPair = loadOrCreateAccountKeyPair(normalizedEmail);

        Session session = new Session(serverUri);
        AccountBuilder builder = new AccountBuilder()
            .agreeToTermsOfService()
            .useKeyPair(keyPair);

        if (email != null && !email.isEmpty()) {
            builder.addEmail(email);
        }

        Account account = builder.create(session);
        accounts.put(normalizedEmail, account);
        Blast.log("ACME: account ready",
            normalizedEmail.isEmpty() ? "(global," : "(" + normalizedEmail + ",",
            staging ? "staging)" : "production)");
        return account;
    }

    /**
     * Each account key is its own provider='acme_account' row; the pre-existing global
     * row has letsencrypt_email NULL, per-email rows carry their email.
     */
    KeyPair loadOrCreateAccountKeyPair(String normalizedEmail) throws Exception {
        var ds = HohenheimDatabase.datasource();
        var certModel = Models.get(CertificateModel.class);

        // A handful of rows at most; match the email key in Java since NULL marks the global row.
        List<Row> accountRows = certModel.find()
            .where(CertificateModel.PROVIDER.eq(CertificateModel.PROVIDER_ACME_ACCOUNT))
            .all();
        for (Row row : accountRows) {
            String rowEmail = row.get(CertificateModel.LETSENCRYPT_EMAIL);
            String rowKey = rowEmail == null ? "" : rowEmail.trim().toLowerCase();
            if (!rowKey.equals(normalizedEmail)) continue;

            String keyPem = row.get(CertificateModel.PRIVATE_KEY_PEM);
            if (keyPem != null) {
                try (var reader = new StringReader(keyPem)) {
                    return KeyPairUtils.readKeyPair(reader);
                }
            }
        }

        KeyPair keyPair = KeyPairUtils.createKeyPair(2048);
        StringWriter sw = new StringWriter();
        KeyPairUtils.writeKeyPair(keyPair, sw);

        Row newRow = certModel.createEmptyRow();
        newRow.set(CertificateModel.NICE_NAME, normalizedEmail.isEmpty()
            ? "ACME Account Key"
            : "ACME Account Key (" + normalizedEmail + ")");
        newRow.set(CertificateModel.PROVIDER, CertificateModel.PROVIDER_ACME_ACCOUNT);
        newRow.set(CertificateModel.PRIVATE_KEY_PEM, sw.toString());
        newRow.set(CertificateModel.STATUS, CertificateModel.STATUS_ACTIVE);
        if (!normalizedEmail.isEmpty()) {
            newRow.set(CertificateModel.LETSENCRYPT_EMAIL, normalizedEmail);
        }
        certModel.save(newRow);

        return keyPair;
    }

    // -----------------------------------------------------------------------
    // PEM serialization
    // -----------------------------------------------------------------------

    private static String certificateChainToPem(List<X509Certificate> chain) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (X509Certificate cert : chain) {
            sb.append("-----BEGIN CERTIFICATE-----\n");
            sb.append(Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(cert.getEncoded()));
            sb.append("\n-----END CERTIFICATE-----\n");
        }
        return sb.toString();
    }

    private static String privateKeyToPem(KeyPair keyPair) throws Exception {
        StringWriter sw = new StringWriter();
        KeyPairUtils.writeKeyPair(keyPair, sw);
        return sw.toString();
    }
}
