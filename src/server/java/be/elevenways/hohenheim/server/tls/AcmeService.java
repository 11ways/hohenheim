package be.elevenways.hohenheim.server.tls;

import be.elevenways.hohenheim.server.HohenheimSystemPurposes;
import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimRefusalReason;
import be.elevenways.protoblast.common.async.AsyncFailures;
import be.elevenways.protoblast.common.thread.JobRunner;
import be.elevenways.protoblast.common.thread.JobRunnerGroup;
import be.elevenways.protoblast.common.time.Backoff;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.protoblast.common.time.Pace;
import be.elevenways.protoblast.common.util.BlastString;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.RefusalSubject;
import be.elevenways.zenit.common.security.ExecutionIdentity;
import be.elevenways.zenit.common.security.PrincipalRef;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.history.Change;
import be.elevenways.zenit.common.orm.history.History;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.net.Hostnames;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.server.dns.GeneratedDnsRecords;
import be.elevenways.hohenheim.server.notification.NotificationEvents;
import be.elevenways.hohenheim.server.notification.Alerts;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.server.http.HostPattern;
import be.elevenways.zenit.server.security.SecureTokens;
import be.elevenways.zenit.common.net.IpRanges;
import be.elevenways.zenit.server.ServerZenitRuntime;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.shredzone.acme4j.*;
import org.shredzone.acme4j.challenge.Challenge;
import org.shredzone.acme4j.challenge.Dns01Challenge;
import org.shredzone.acme4j.challenge.Http01Challenge;
import org.shredzone.acme4j.util.CSRBuilder;
import org.shredzone.acme4j.util.KeyPairUtils;

import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static be.elevenways.hohenheim.RawValues.trimmed;

/**
 * Manages Let's Encrypt certificate issuance and renewal via ACME protocol.
 * Uses acme4j for the ACME client and serves HTTP-01 challenges
 * through the proxy's SiteDispatcher.
 */
public class AcmeService {

    private static final long RENEWAL_CHECK_HOURS = 6;
    private static final int RENEWAL_THRESHOLD_DAYS = 30;
    private static final int MAX_POLL_ATTEMPTS = 40;
    static final Backoff RENEWAL_RETRY =
            Backoff.exponential(Duration.ofMinutes(30), 2, Duration.ofHours(32)).jittered(0.2);
    private static final Duration POLL_CADENCE = Duration.ofSeconds(3);
    /**
     * How long, in total, one order attempt waits for polls the CA paces beyond the cadence; a CA asking for longer
     * ends the attempt and RENEWAL_RETRY schedules the next instead of sleeping through it.
     */
    static final Duration POLL_BUDGET = Duration.ofMinutes(5);
    private static final Backoff POLL = Backoff.fixed(POLL_CADENCE).honouringUpTo(POLL_BUDGET);
    private static final long MANUAL_DNS_ORDER_MINUTES = 30;

    /** How long {@link #stop} waits for an interrupted renewal check to end before it gives up on it. */
    private static final long STOP_TIMEOUT_MS = 10_000L;

    private volatile Pace.Pause pollPause = Pace.Pause.REAL;

    /** Test seam: records each poll wait instead of sleeping through a CA delay. */
    public void setPollPauseForTesting(Pace.Pause pause) {
        this.pollPause = Objects.requireNonNull(pause);
    }

    /**
     * One order attempt's budget for the poll waits a CA names beyond the three-second cadence.
     *
     * AIDEV-NOTE: only a CA-named wait longer than the cadence spends the budget. Cadence polling keeps
     * MAX_POLL_ATTEMPTS per loop as its only bound, exactly as before CA pacing, so a multi-hostname HTTP-01
     * order whose CA validates slowly still issues; the budget only stops a CA from parking the flight.
     */
    public static final class PollBudget {
        private Duration left = POLL_BUDGET;

        /**
         * @param retryAt the CA's retry instant from the last poll, null when it named none
         * @return the wait before the next poll: the cadence, floored by the CA's instant and charged to this budget
         *         when the CA's wait is the longer
         * @throws IllegalStateException when the CA's wait outlasts the cadence and what is left of the budget,
         *                               which ends the order attempt
         */
        public synchronized Duration next(Instant now, @Nullable Instant retryAt) {
            Duration named = retryAt == null ? null : Duration.between(now, retryAt);
            Duration wait = POLL.delayAfter(1, named);
            if (named != null && wait.compareTo(POLL_CADENCE) > 0) {
                if (named.compareTo(this.left) > 0) {
                    throw new IllegalStateException("The CA asked for the next poll in " + named.toSeconds()
                        + "s, past the " + this.left.toSeconds() + "s left of this order attempt's "
                        + POLL_BUDGET.toMinutes() + "-minute polling budget for CA-paced waits; the attempt ends "
                        + "and the renewal retry schedules the next one");
                }
                this.left = this.left.minus(wait);
            }
            return wait;
        }
    }

    private final CertificateStore certificateStore;
    private final JobRunner scheduler;

    /**
     * Pending HTTP-01 challenges: token -> challenge entry with authorization and valid hostnames.
     */
    private final ConcurrentHashMap<String, ChallengeEntry> pendingChallenges = new ConcurrentHashMap<>();

    /** Same-account/SAN orders share one automated transaction or reserve one manual transaction. */
    private final ConcurrentHashMap<String, OrderFlight> inFlightOrders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PendingManualDnsOrder> manualDnsOrders = new ConcurrentHashMap<>();

    /**
     * Held while THE requester-key name-set rule ({@link #requesterCertificate}) is checked together with the write it
     * allows: a fresh order's pending row, a rotation's new names. Never held across a CA order.
     */
    private final Object nameSets = new Object();

    // ACME account sessions keyed by directory and normalized override email ("" = the global account),
    // each email with its own persisted key pair. Guarded by the synchronized ensureAccount.
    private final Map<String, Account> accounts = new HashMap<>();

    private record ChallengeEntry(String authorization, Set<String> validHostnames) {}

    /**
     * Result of an ACME certificate order: the chain, its expiry, and the signing whose key the chain certifies, which
     * is what a row keeps beside it ({@link Signing#keep}).
     */
    private record OrderResult(String certPem, Instant expiresAt, Signing signing) {}

    /** certificateId carries the leader's row id so joiners can share the row instead of minting a duplicate. */
    private record OrderFlight(boolean joinable, CompletableFuture<OrderResult> result, AtomicInteger certificateId) {
        OrderFlight(boolean joinable, CompletableFuture<OrderResult> result) {
            this(joinable, result, new AtomicInteger(-1));
        }
    }

    /** identifier = the ACME identifier domain, which drops a SAN's leading wildcard label. */
    private record DnsAuthorization(Authorization authorization, Dns01Challenge challenge,
                                    DnsTxtRecord record, String identifier) {}

    /** declaring maps every requested SAN to the domain row that authorized it. */
    private record DnsOrderContext(Order order, Signing signing, List<String> hostnames,
                                   List<DnsAuthorization> authorizations,
                                   Map<String, Integer> declaring) {}

    /**
     * Whose key an order certifies: Hohenheim's own, made fresh for the order, or the requester's, named by its signing
     * request.
     *
     * AIDEV-NOTE: the signing is part of the order key ({@link #orderKeyPart}), so an order for Hohenheim's key never
     * joins one for a requester's key over the same names (the joiner would adopt a chain for a key it does not hold),
     * and a requester-held certificate renews from its stored request, so its key never changes behind a DANE pin.
     */
    private sealed interface Signing permits OwnKey, RequesterKey {

        /** @return the PKCS#10 the CA's finalize receives */
        byte[] csr(List<String> hostnames) throws Exception;

        /** Stores, beside an issued chain, who holds its key: the key itself, or the request it was ordered from. */
        void keep(Row certRow) throws Exception;

        /** Stamps a not yet issued row with who will hold its key. */
        void pending(Row certRow);

        /** @throws IllegalStateException when the issued leaf certifies another key than this signing's */
        void requireCertifies(X509Certificate leaf);

        /** @return this signing's part of the order key; empty for Hohenheim's own fresh keys */
        String orderKeyPart();

        /**
         * Applies THE requester-key name-set rule to a fresh order of this signing; run under {@link #nameSets}.
         *
         * @param owner the account the order is stored under
         * @return the owner's certificate this order replays (the same key for the same names), null for a new order;
         *         always null for Hohenheim's own fresh keys, which no rule binds
         * @throws DomainRefusal {@link HohenheimRefusalReason#CERTIFICATE_NAMES_HELD} when the owner holds a
         *                       certificate for exactly these names from another key
         */
        @Nullable Integer replayOf(@Nullable PrincipalRef owner, List<String> hostnames);

        /**
         * Applies THE requester-key name-set rule to a rotation of certificate {@code except} onto these names; run
         * under {@link #nameSets}. A no-op for Hohenheim's own fresh keys.
         *
         * @throws DomainRefusal {@link HohenheimRefusalReason#CERTIFICATE_NAMES_HELD} when the owner holds another
         *                       certificate for exactly these names, from any key
         */
        void requireNamesFree(@Nullable PrincipalRef owner, List<String> hostnames, int except);
    }

    /** Hohenheim's own key, made for one order and stored with the certificate. */
    private record OwnKey(KeyPair pair) implements Signing {

        static OwnKey fresh() {
            return new OwnKey(KeyPairUtils.createKeyPair(2048));
        }

        @Override
        public byte[] csr(List<String> hostnames) throws Exception {
            CSRBuilder csrBuilder = new CSRBuilder();
            for (String hostname : hostnames) {
                csrBuilder.addDomain(hostname);
            }
            csrBuilder.sign(this.pair);
            return csrBuilder.getEncoded();
        }

        @Override
        public void keep(Row certRow) throws Exception {
            CertificateModel.keepKey(certRow, privateKeyToPem(this.pair));
        }

        @Override
        public void pending(Row certRow) {
        }

        @Override
        public void requireCertifies(X509Certificate leaf) {
            if (!leaf.getPublicKey().equals(this.pair.getPublic())) {
                throw new IllegalStateException("The CA issued a certificate for another key than the order's");
            }
        }

        @Override
        public String orderKeyPart() {
            return "";
        }

        @Override
        public @Nullable Integer replayOf(@Nullable PrincipalRef owner, List<String> hostnames) {
            return null;
        }

        @Override
        public void requireNamesFree(@Nullable PrincipalRef owner, List<String> hostnames, int except) {
        }
    }

    /** The requester's key, named by its signing request; Hohenheim never sees the key itself. */
    private record RequesterKey(SigningRequest request) implements Signing {

        @Override
        public byte[] csr(List<String> hostnames) {
            return this.request.der();
        }

        @Override
        public void keep(Row certRow) {
            CertificateModel.keepSigningRequest(certRow, this.request.pem());
        }

        @Override
        public void pending(Row certRow) {
            CertificateModel.keepSigningRequest(certRow, this.request.pem());
        }

        @Override
        public void requireCertifies(X509Certificate leaf) {
            if (!SigningRequest.publicKeyPin(leaf.getPublicKey()).equals(this.request.publicKeyPin())) {
                throw new IllegalStateException("The CA issued a certificate for another key than the signing request's");
            }
        }

        @Override
        public String orderKeyPart() {
            return "csr:" + this.request.publicKeyPin() + "@";
        }

        @Override
        public @Nullable Integer replayOf(@Nullable PrincipalRef owner, List<String> hostnames) {
            Row held = requesterCertificate(owner, hostnames, null);
            if (held == null) {
                return null;
            }
            if (!this.request.publicKeyPin().equals(storedRequestPin(held))) {
                throw namesHeld(held);
            }
            return held.get(CertificateModel.ID);
        }

        @Override
        public void requireNamesFree(@Nullable PrincipalRef owner, List<String> hostnames, int except) {
            Row held = requesterCertificate(owner, hostnames, except);
            if (held != null) {
                throw namesHeld(held);
            }
        }
    }

    /**
     * THE requester-key name-set rule: an owner holds at most ONE certificate from its own signing request per exact
     * name set, the one the renewal sweep renews. The same key for the same names again is that certificate (a
     * replay), and another key for them is its rotation, never a second certificate.
     *
     * AIDEV-NOTE: a key is compared by its request's public key pin, never by the request's bytes: a request signed
     * again over the same key (an ECDSA signature differs every time) asks for the same certificate, since the CA
     * writes everything but the key and the names itself. Names compare as a case-folded set, the way
     * {@link SigningRequest#read} matches a request to them. Every status counts (a failed first order is a row the
     * sweep retries). An order stored under no account ({@link CertificateAuthority.Requester#subject} null) has no
     * owner to compare and is never matched; Hohenheim's own keys are no part of the rule.
     *
     * @param except a certificate id to leave out (the one being rotated), or null
     * @return the owner's requester-key certificate for exactly these names, or null
     */
    private static @Nullable Row requesterCertificate(@Nullable PrincipalRef owner, List<String> hostnames,
                                                      @Nullable Integer except) {
        if (owner == null) {
            return null;
        }
        Set<String> asked = foldedNames(hostnames);
        var query = Models.get(CertificateModel.class).find()
            .where(CertificateModel.PROVIDER.eq(CertificateModel.PROVIDER_LETSENCRYPT))
            .and(CertificateModel.KEY_HOLDER.eq(CertificateModel.KEY_HOLDER_REQUESTER))
            .and(CertificateModel.REQUESTER.is(owner));
        if (except != null) {
            query.and(CertificateModel.ID.ne(except));
        }
        for (Row held : query.orderBy(CertificateModel.ID, SortOrder.ASC).all()) {
            if (foldedNames(CertificateCoverage.namesOf(held)).equals(asked)) {
                return held;
            }
        }
        return null;
    }

    private static Set<String> foldedNames(List<String> hostnames) {
        Set<String> folded = new HashSet<>();
        for (String hostname : hostnames) {
            folded.add(BlastString.lower(hostname.trim()));
        }
        return folded;
    }

    /** @return the key pin of a stored certificate's signing request, null when it no longer reads (never a match) */
    private static @Nullable String storedRequestPin(Row held) {
        try {
            return SigningRequest.read(held.get(CertificateModel.SIGNING_REQUEST_PEM),
                CertificateCoverage.namesOf(held)).publicKeyPin();
        } catch (SigningRequest.Refused unreadable) {
            return null;
        }
    }

    /** The refusal naming the certificate that already holds the names: its owner rotates that one instead. */
    private static DomainRefusal namesHeld(Row held) {
        int heldId = held.get(CertificateModel.ID);
        return new DomainRefusal(HohenheimRefusalReason.CERTIFICATE_NAMES_HELD,
            "Certificate " + heldId + " already holds these names for this requester", null, null,
            new RefusalSubject(CertificateModel.MODEL_ID, String.valueOf(heldId), held.get(CertificateModel.NICE_NAME)));
    }

    /**
     * The signing a stored certificate renews with: a fresh key of Hohenheim's for one whose key it holds, the stored
     * signing request for one whose requester holds it.
     *
     * @throws SigningRequest.Refused when the stored request no longer reads for the stored names
     * @throws IllegalStateException  for a key holder nobody declared, which renews with nothing
     */
    private static Signing signingOf(Row certRow, List<String> hostnames) {
        if (CertificateModel.keyHeldHere(certRow)) {
            return OwnKey.fresh();
        }
        if (CertificateModel.keyHeldByRequester(certRow)) {
            return new RequesterKey(SigningRequest.read(certRow.get(CertificateModel.SIGNING_REQUEST_PEM), hostnames));
        }
        throw new IllegalStateException("Unknown key holder: " + certRow.get(CertificateModel.KEY_HOLDER));
    }

    private record PendingManualDnsOrder(int certificateId, DnsOrderContext order, Instant createdAt,
                                         String orderKey, OrderFlight flight) {}

    /** Safe UI projection of an in-memory manual DNS order. */
    public record ManualDnsRequest(String token, int certificateId, List<DnsTxtRecord> records) {}

    /**
     * The outcome of a re-issue, which cannot be reported as a row id: the row already
     * existed, so "failed" has to be distinguishable from "issued" by something other than
     * the presence of an id.
     *
     * @param failureReason null exactly when the certificate was re-issued
     */
    public record ReissueResult(boolean issued, @Nullable String failureReason) {}

    /**
     * What a fresh certificate request came to.
     *
     * AIDEV-NOTE: the row id rides the outcome on failure too, so a caller reads the failure
     * reason off THAT row (its renewal_error) instead of guessing which error row is its own.
     * A request that joined an identical in-flight order of the same owner names the leader's
     * row, which is the row whose fate it shared. A replayed request names the row it already is (THE
     * requester-key name-set rule, {@link #requesterCertificate}), whatever that row's status.
     *
     * @param certificateId the certificate row the request wrote, joined or replayed, or null
     *                      when it reached no row (a manual order for the same names is running)
     * @param issued        whether this request issued that row's certificate
     * @param replayed      whether the row already existed and nothing was ordered
     */
    public record RequestOutcome(@Nullable Integer certificateId, boolean issued, boolean replayed) {

        static @NonNull RequestOutcome issued(int certificateId) {
            return new RequestOutcome(certificateId, true, false);
        }

        static @NonNull RequestOutcome failed(@Nullable Integer certificateId) {
            return new RequestOutcome(certificateId != null && certificateId > 0 ? certificateId : null, false, false);
        }

        /** The request named a certificate that already exists, and nothing was ordered. */
        static @NonNull RequestOutcome replayed(int certificateId) {
            return new RequestOutcome(certificateId, false, true);
        }
    }

    public AcmeService(CertificateStore certificateStore) {
        this(certificateStore, JobRunner.createDaemonSerial("acme-renewal"));
    }

    /** @param scheduler the lane renewal checks and manual DNS-01 expiry run on; a test fires its timers itself */
    AcmeService(CertificateStore certificateStore, JobRunner scheduler) {
        this.certificateStore = certificateStore;
        this.scheduler = scheduler;
    }

    /**
     * Declared SYSTEM work: renewals and challenge expiry are the installation's own, and the DNS-01 records they
     * publish would be judged and refused as work with no identity. Detached: never the scheduling caller's action.
     */
    private static Runnable asSystem(Runnable job) {
        return () -> ExecutionIdentity.runDetachedAsSystem(HohenheimSystemPurposes.ACME, job);
    }

    public void start() {
        scheduler.scheduleAtFixedRate(asSystem(this::checkRenewals), TimeUnit.HOURS.toMillis(RENEWAL_CHECK_HOURS));
        Blast.log("ACME renewal scheduler started (every", RENEWAL_CHECK_HOURS, "hours)");
    }

    /**
     * Stops renewals and returns once no renewal check still runs: the caller closes the datasource next, which a
     * check still writing its certificate rows would then fail on.
     */
    public void stop() {
        if (!scheduler.stop(JobRunnerGroup.StopMode.CANCEL, STOP_TIMEOUT_MS)) {
            Blast.log("ACME: a renewal check still ran", STOP_TIMEOUT_MS, "ms after the stop");
        }
        manualDnsOrders.forEach((token, pending) -> abandonManualDnsOrder(token, pending,
            "Manual DNS challenge interrupted by server shutdown"));
    }

    /**
     * Test seam: expose a pending HTTP-01 challenge without a real ACME order.
     */
    public void offerHttpChallenge(String token, String authorization, Set<String> validHostnames) {
        pendingChallenges.put(token, new ChallengeEntry(authorization, validHostnames));
    }

    /**
     * Lookup a pending HTTP-01 challenge response by token, validating the hostname.
     * Returns null if the token is not pending or the hostname is not authorized.
     */
    public String getChallengeResponse(String token, String hostname) {
        ChallengeEntry entry = pendingChallenges.get(token);
        if (entry == null) return null;

        // Validate that the requesting hostname is one we're issuing a cert for
        // AIDEV-NOTE: Locale.ROOT, not the default locale: the authorized set is folded from
        // the ordered hostnames while this side folds the Host header off the wire. Under tr
        // a mixed-case hostname on either side stops matching, the challenge 404s and
        // issuance retries forever. Same reason for isValidHostname and the order hostname set.
        if (hostname != null && !entry.validHostnames.contains(hostname.toLowerCase(Locale.ROOT))) {
            return null;
        }

        return entry.authorization;
    }

    /**
     * Request a new Let's Encrypt certificate for the given hostnames.
     * Blocks until the certificate is issued or an error occurs.
     *
     * @param requester the identity the order acts as; its authority over EVERY requested
     *                  name is decided here, before any row or CA order exists
     * @return the row the request wrote or joined, and whether it was issued
     * @throws CertificateAuthority.Refused when the requester may not obtain these names
     */
    public @NonNull RequestOutcome requestCertificate(List<String> hostnames, String niceName,
                                                      @Nullable String email,
                                                      CertificateAuthority.Requester requester) {
        return requestCertificate(hostnames, niceName, email,
            CertificateModel.CHALLENGE_HTTP, null, requester);
    }

    public @NonNull RequestOutcome requestCertificate(List<String> hostnames, String niceName,
                                                      @Nullable String email, String challengeType,
                                                      @Nullable String dnsPublisher,
                                                      CertificateAuthority.Requester requester) {
        return request(hostnames, niceName, email, challengeType, dnsPublisher, requester, OwnKey.fresh());
    }

    /**
     * Request a new Let's Encrypt certificate from the requester's own signing request: the requester keeps the key,
     * the row keeps the request (every renewal orders from it again) and no private key, so the proxy never loads it.
     * Blocks until the certificate is issued or an error occurs.
     *
     * @param request a signing request already judged for exactly {@code hostnames}
     * @return the row the request wrote or joined, and whether it was issued
     * @throws CertificateAuthority.Refused when the requester may not obtain these names
     */
    public @NonNull RequestOutcome requestFromSigningRequest(@NonNull SigningRequest request,
                                                             List<String> hostnames, String niceName,
                                                             String challengeType, @Nullable String dnsPublisher,
                                                             CertificateAuthority.Requester requester) {
        return request(hostnames, niceName, null, challengeType, dnsPublisher, requester, new RequesterKey(request));
    }

    private @NonNull RequestOutcome request(List<String> hostnames, String niceName, @Nullable String email,
                                            String challengeType, @Nullable String dnsPublisher,
                                            CertificateAuthority.Requester requester, Signing signing) {
        var certModel = Models.get(CertificateModel.class);

        // AIDEV-NOTE: authorization runs FIRST, before the order-key claim and before the
        // certificate row exists, so a refused request leaves no trace of an order that was
        // never placed. It lives here rather than in the HTTP handler on purpose: several
        // entry points reach the CA (this one, the manual DNS lane, the renewal sweep), and
        // a handler-layer check is the exact bypass shape SiteDomainRouteInvariant's route
        // invariant documents. Do NOT move it up into HohenheimHandlers.
        Map<String, Integer> declaring = CertificateAuthority.authorize(requester, hostnames);

        // Claim the order key BEFORE saving a row: a concurrent identical request of the
        // same owner shares the leader's certificate row instead of minting a duplicate
        // ACTIVE record for the same domains. The name-set rule, the claim and the pending
        // row are one step under nameSets, so a second request for a name set finds the
        // first one's row instead of racing it.
        String orderKey = certificateOrderKey(hostnames, email, null, signing, requester.subject());
        OrderFlight flight = new OrderFlight(true, new CompletableFuture<>());
        OrderFlight existing;
        Row certRow = null;
        synchronized (this.nameSets) {
            Integer replayed = signing.replayOf(requester.subject(), hostnames);
            if (replayed != null) {
                Blast.log("ACME: certificate", replayed, "already holds", String.join(", ", hostnames),
                    "from this signing request; nothing is ordered");
                return RequestOutcome.replayed(replayed);
            }
            // AIDEV-NOTE: the row is built BEFORE the claim. A claim nobody leads holds every later identical order on
            // its future forever, so nothing but the save (which releases it on failure) runs between taking a claim
            // and handing it a row; a joiner simply drops the row it built.
            Row pending = certModel.createEmptyRow();
            pending.set(CertificateModel.NICE_NAME, niceName);
            pending.set(CertificateModel.PROVIDER, CertificateModel.PROVIDER_LETSENCRYPT);
            pending.set(CertificateModel.STATUS, CertificateModel.STATUS_PENDING);
            pending.set(CertificateModel.DOMAIN_NAMES_TEXT, String.join(",", hostnames));
            pending.set(CertificateModel.CHALLENGE_TYPE, challengeType);
            pending.set(CertificateModel.DNS_PUBLISHER, dnsPublisher);
            CertificateModel.setRequester(pending, requester.subject());
            if (email != null && !email.isBlank()) {
                pending.set(CertificateModel.LETSENCRYPT_EMAIL, email.trim());
            }
            signing.pending(pending);
            existing = inFlightOrders.putIfAbsent(orderKey, flight);
            if (existing == null) {
                try {
                    certModel.save(pending);
                    flight.certificateId().set(pending.get(CertificateModel.ID));
                } catch (RuntimeException | Error unsaved) {
                    flight.result().completeExceptionally(unsaved);
                    inFlightOrders.remove(orderKey, flight);
                    throw unsaved;
                }
                certRow = pending;
            }
        }
        if (existing != null) {
            if (!existing.joinable()) {
                Blast.log("ACME: a manual order for", String.join(", ", hostnames), "is already in progress");
                return RequestOutcome.failed(null);
            }
            try {
                existing.result().get();
                return RequestOutcome.issued(existing.certificateId().get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return RequestOutcome.failed(existing.certificateId().get());
            } catch (Exception e) {
                return RequestOutcome.failed(existing.certificateId().get());
            }
        }
        int certId = certRow.get(CertificateModel.ID);

        try {
            OrderResult result = leadOrder(orderKey, flight, hostnames, email, challengeType,
                dnsPublisher, declaring, signing);

            applyIssuedMaterial(certRow, result);
            certModel.save(certRow);

            certificateStore.loadFromDatabase();
            Blast.log("ACME: certificate issued for", String.join(", ", hostnames));
            return RequestOutcome.issued(certId);

        } catch (Exception e) {
            String reason = failureReason(e);
            Blast.log("ACME: certificate request failed for", String.join(", ", hostnames), "-", reason);

            recordRenewalFailure(certRow, reason);
            certModel.save(certRow);

            return RequestOutcome.failed(certId);
        }
    }

    /**
     * Re-order an EXISTING Let's Encrypt certificate against a possibly different hostname
     * set, challenge type or DNS publisher, writing the result onto the SAME row.
     *
     * The row is touched ONLY on success. A failed re-issue leaves the stored certificate,
     * its domain list and its renewal bookkeeping byte-identical, so the old certificate
     * keeps serving and keeps auto-renewing against the names it was actually issued for --
     * the alternative (stamping the new names first) would point every later renewal at a
     * set no certificate was ever issued for.
     *
     * AIDEV-NOTE: the stored requester moves to the re-issuing actor, which is what renewal
     * re-authorizes as from here on. That is the correct accountability -- the names on the
     * certificate are now the ones THIS actor asked for -- but it does mean an operator
     * re-issuing a tenant's certificate takes over its renewal authority.
     *
     * @param certRow the stored certificate; must be a {@code letsencrypt} row whose key Hohenheim holds
     * @throws CertificateAuthority.Refused when the requester may not obtain the NEW names
     */
    public @NonNull ReissueResult reissueCertificate(@NonNull Row certRow, List<String> hostnames,
                                                     @Nullable String email, String challengeType,
                                                     @Nullable String dnsPublisher,
                                                     CertificateAuthority.Requester requester) {
        requireReissuable(certRow, challengeType, dnsPublisher);
        // A requester-held certificate re-issued with a key of Hohenheim's would change the key behind the
        // requester's pins and hand Hohenheim a certificate the requester asked to keep: it re-orders only from a new
        // signing request (reissueFromSigningRequest).
        if (!CertificateModel.keyHeldHere(certRow)) {
            throw new IllegalArgumentException("Its requester holds this certificate's key; it is re-ordered from a"
                + " new signing request");
        }
        return reissue(certRow, hostnames, email, challengeType, dnsPublisher, requester, OwnKey.fresh());
    }

    /**
     * Re-order a requester-held certificate from a NEW signing request, writing the result and the new request onto
     * the SAME row: the only way its key changes. The row is touched only on success, exactly as a re-issue.
     *
     * @param request a signing request already judged for exactly {@code hostnames}
     * @throws CertificateAuthority.Refused when the requester may not obtain these names
     * @throws IllegalArgumentException     when the row is no Let's Encrypt certificate whose requester holds the key
     * @throws DomainRefusal                {@link HohenheimRefusalReason#CERTIFICATE_NAMES_HELD} when the requester
     *                                      holds another certificate for exactly these names; the row is unchanged
     */
    public @NonNull ReissueResult reissueFromSigningRequest(@NonNull Row certRow, @NonNull SigningRequest request,
                                                            List<String> hostnames, String challengeType,
                                                            @Nullable String dnsPublisher,
                                                            CertificateAuthority.Requester requester) {
        requireReissuable(certRow, challengeType, dnsPublisher);
        if (!CertificateModel.keyHeldByRequester(certRow)) {
            throw new IllegalArgumentException("Hohenheim holds this certificate's key; a signing request never takes"
                + " it over");
        }
        return reissue(certRow, hostnames, certRow.get(CertificateModel.LETSENCRYPT_EMAIL), challengeType,
            dnsPublisher, requester, new RequesterKey(request));
    }

    /**
     * Visibility is not authorization: the row action hides itself for non-ACME rows and the handler refuses one, and
     * this refuses it a third time because this is the only layer every future caller must pass through.
     */
    private static void requireReissuable(@NonNull Row certRow, String challengeType, @Nullable String dnsPublisher) {
        if (!CertificateModel.PROVIDER_LETSENCRYPT.equals(certRow.get(CertificateModel.PROVIDER))) {
            throw new IllegalArgumentException("Only Let's Encrypt certificates can be re-issued");
        }
        if (CertificateModel.CHALLENGE_DNS.equals(challengeType)
                && CertificateModel.DNS_PUBLISHER_MANUAL.equals(dnsPublisher)) {
            // The manual lane is a two-step, in-memory order that has to survive a redirect;
            // it mints its own row by construction. Re-issuing INTO an existing row through
            // it is a separate mechanism, not a parameter of this one.
            throw new IllegalArgumentException("Manual DNS-01 re-issue is not supported");
        }
    }

    private @NonNull ReissueResult reissue(@NonNull Row certRow, List<String> hostnames, @Nullable String email,
                                           String challengeType, @Nullable String dnsPublisher,
                                           CertificateAuthority.Requester requester, Signing signing) {
        int certificateId = certRow.get(CertificateModel.ID);
        var certModel = Models.get(CertificateModel.class);

        // The FULL new name set is authorized, never the added ones: the stored row proves
        // an authority that existed when it was issued, and says nothing about this actor.
        Map<String, Integer> declaring = CertificateAuthority.authorize(requester, hostnames);
        synchronized (this.nameSets) {
            signing.requireNamesFree(requester.subject(), hostnames, certificateId);
        }

        // AIDEV-NOTE: the row id is part of the key. Without it a re-issue and a fresh
        // request for the same names coalesce onto one order, and the joiner adopts the
        // leader's material -- which for a re-issue means writing a certificate ordered
        // under a different row's request onto this row. Renewal deliberately keeps the
        // id-less key so an owner's SAN-identical renewals still share one order.
        String orderKey = certificateOrderKey(hostnames, email, certificateId, signing, requester.subject());
        OrderFlight flight = new OrderFlight(false, new CompletableFuture<>());
        if (inFlightOrders.putIfAbsent(orderKey, flight) != null) {
            return new ReissueResult(false, "A re-issue of this certificate is already in progress");
        }

        try {
            OrderResult result = leadOrder(orderKey, flight, hostnames, email, challengeType,
                dnsPublisher, declaring, signing);

            // The rule is asked again with the write: a fresh order for these names may have landed while this
            // one ran, and the chain just issued is then dropped rather than stored beside it.
            synchronized (this.nameSets) {
                signing.requireNamesFree(requester.subject(), hostnames, certificateId);
                applyIssuedMaterial(certRow, result);
                certRow.set(CertificateModel.DOMAIN_NAMES_TEXT, String.join(",", hostnames));
                certRow.set(CertificateModel.CHALLENGE_TYPE, challengeType);
                certRow.set(CertificateModel.DNS_PUBLISHER, dnsPublisher);
                certRow.set(CertificateModel.LETSENCRYPT_EMAIL,
                    email != null && !email.isBlank() ? email.trim() : null);
                CertificateModel.setRequester(certRow, requester.subject());
                certModel.save(certRow);
            }

            certificateStore.loadFromDatabase();
            Blast.log("ACME: certificate re-issued for", String.join(", ", hostnames));
            return new ReissueResult(true, null);

        } catch (DomainRefusal namesHeld) {
            Blast.log("ACME: re-issue for", String.join(", ", hostnames), "dropped:", namesHeld.getMessage());
            throw namesHeld;
        } catch (Exception e) {
            String reason = failureReason(e);
            Blast.log("ACME: re-issue failed for", String.join(", ", hostnames), "-", reason);
            return new ReissueResult(false, reason);
        }
    }

    /**
     * THE issuance pipeline every lane that leads an order shares (a fresh request, a re-issue,
     * a renewal): validate the names, place the order, and settle the in-flight claim whichever
     * way it ends.
     *
     * Nothing here touches a certificate row -- the row bookkeeping is the ONLY thing a
     * fresh request and a re-issue differ in, and it is what makes "leave the row alone on
     * failure" expressible at all.
     *
     * AIDEV-NOTE: an Error settles the flight too. The request and re-issue lanes used to
     * catch Exception only, so an Error left a joiner blocked on the flight's future forever.
     *
     * @param flight a claim this caller already won for {@code orderKey}
     */
    private OrderResult leadOrder(String orderKey, OrderFlight flight, List<String> hostnames,
                                  @Nullable String email, String challengeType,
                                  @Nullable String dnsPublisher,
                                  Map<String, Integer> declaring, Signing signing) throws Exception {
        try {
            requireValidHostnames(hostnames, CertificateModel.CHALLENGE_DNS.equals(challengeType));
            OrderResult result = performAcmeOrderUncoalesced(hostnames, email, challengeType,
                dnsPublisher, declaring, signing);
            flight.result().complete(result);
            return result;
        } catch (Exception | Error failure) {
            flight.result().completeExceptionally(failure);
            throw failure;
        } finally {
            inFlightOrders.remove(orderKey, flight);
        }
    }

    /**
     * @param allowWildcard whether one leading wildcard label is acceptable (DNS-01 only)
     * @throws IllegalArgumentException naming every invalid hostname
     */
    private static void requireValidHostnames(List<String> hostnames, boolean allowWildcard) {
        List<String> invalid = invalidHostnames(hostnames, allowWildcard);
        if (!invalid.isEmpty()) {
            throw new IllegalArgumentException("Invalid hostnames: " + String.join(", ", invalid));
        }
    }

    /**
     * Stamp freshly issued material onto a row; the four issuance lanes all write this. Who holds the key is written
     * with it ({@link Signing#keep}), so a chain is never stored beside another order's custody.
     */
    private static void applyIssuedMaterial(Row certRow, OrderResult result) throws Exception {
        certRow.set(CertificateModel.CERTIFICATE_PEM, result.certPem());
        result.signing().keep(certRow);
        certRow.set(CertificateModel.EXPIRES_ON, result.expiresAt());
        certRow.set(CertificateModel.ISSUED_ON, Now.instant());
        markRenewalSuccess(certRow);
    }

    /** Preserves nested transport diagnostics that generic ACME exceptions hide. */
    static String failureReason(Throwable failure) {
        StringJoiner reason = new StringJoiner(": ");
        AsyncFailures.forEachCause(failure, link -> {
            String message = link.getMessage();
            reason.add(message != null && !message.isBlank() ? message : link.getClass().getSimpleName());
        });
        return reason.toString();
    }

    /** Formats the structured diagnostic returned by the ACME server. */
    static String formatProblem(Problem problem) {
        StringJoiner details = new StringJoiner(", ");
        details.add("type=" + problem.getType());
        problem.getTitle().ifPresent(title -> details.add("title=" + title));
        problem.getDetail().ifPresent(detail -> details.add("detail=" + detail));
        problem.getIdentifier().ifPresent(identifier -> details.add("identifier=" + identifier));
        if (!problem.getSubProblems().isEmpty()) {
            details.add("subproblems=" + problem.getSubProblems().stream()
                .map(AcmeService::formatProblem)
                .collect(Collectors.joining("; ")));
        }
        return details.toString();
    }

    static RuntimeException challengeFailure(String domain, Challenge challenge) {
        String details = challenge.getError().map(AcmeService::formatProblem).orElse("unreported ACME error");
        return new RuntimeException("Challenge failed for " + domain + ": " + details);
    }

    static RuntimeException orderFailure(Order order) {
        String details = order.getError().map(AcmeService::formatProblem).orElse("unreported ACME error");
        return new RuntimeException("Order rejected by CA: " + details);
    }

    /**
     * Begin a DNS-01 order and return the TXT values the operator must publish.
     *
     * @throws CertificateAuthority.Refused when the requester may not obtain these names
     */
    public ManualDnsRequest prepareManualDnsCertificate(List<String> hostnames, String niceName,
                                                         @Nullable String email,
                                                         CertificateAuthority.Requester requester)
            throws Exception {
        // Same enforcement point as requestCertificate: before the row, before the CA.
        Map<String, Integer> declaring = CertificateAuthority.authorize(requester, hostnames);

        requireValidHostnames(hostnames, true);

        Signing signing = OwnKey.fresh();
        String orderKey = certificateOrderKey(hostnames, email, null, signing, requester.subject());
        OrderFlight flight = new OrderFlight(false, new CompletableFuture<>());
        var certModel = Models.get(CertificateModel.class);
        // Made before the claim: nothing but the try below, which releases it, runs while it is held.
        Row certRow = certModel.createEmptyRow();
        if (inFlightOrders.putIfAbsent(orderKey, flight) != null) {
            throw new IllegalStateException("A certificate order for these hostnames is already in progress");
        }

        try {
            certRow.set(CertificateModel.NICE_NAME, niceName);
            certRow.set(CertificateModel.PROVIDER, CertificateModel.PROVIDER_LETSENCRYPT);
            certRow.set(CertificateModel.STATUS, CertificateModel.STATUS_PENDING);
            certRow.set(CertificateModel.DOMAIN_NAMES_TEXT, String.join(",", hostnames));
            certRow.set(CertificateModel.CHALLENGE_TYPE, CertificateModel.CHALLENGE_DNS);
            certRow.set(CertificateModel.DNS_PUBLISHER, CertificateModel.DNS_PUBLISHER_MANUAL);
            certRow.set(CertificateModel.AUTO_RENEW, false);
            CertificateModel.setRequester(certRow, requester.subject());
            if (email != null && !email.isBlank()) {
                certRow.set(CertificateModel.LETSENCRYPT_EMAIL, email.trim());
            }
            certModel.save(certRow);

            DnsOrderContext order = prepareDnsOrder(hostnames, email, declaring, signing);
            String token = SecureTokens.randomToken();
            int certificateId = certRow.get(CertificateModel.ID);
            PendingManualDnsOrder pending = new PendingManualDnsOrder(
                certificateId, order, Now.instant(), orderKey, flight);
            manualDnsOrders.put(token, pending);
            scheduler.schedule(asSystem(() -> expireManualDnsOrder(token, pending)),
                TimeUnit.MINUTES.toMillis(MANUAL_DNS_ORDER_MINUTES));
            return manualRequest(token, manualDnsOrders.get(token));
        } catch (Exception | Error e) {
            flight.result().completeExceptionally(e);
            inFlightOrders.remove(orderKey, flight);
            recordRenewalFailure(certRow, failureReason(e));
            certModel.save(certRow);
            throw e;
        }
    }

    public @Nullable ManualDnsRequest manualDnsRequest(@Nullable String token) {
        PendingManualDnsOrder pending = token != null ? manualDnsOrders.get(token) : null;
        if (pending == null) {
            return null;
        }
        if (pending.createdAt().isBefore(Now.instant()
            .minus(MANUAL_DNS_ORDER_MINUTES, ChronoUnit.MINUTES))) {
            expireManualDnsOrder(token, pending);
            return null;
        }
        return manualRequest(token, pending);
    }

    /**
     * The manual DNS-01 order still waiting on a certificate row: the TXT records its operator must publish, or null
     * when none waits (never started, finished, expired or lost to a restart).
     */
    public @Nullable ManualDnsRequest manualDnsRequestFor(int certificateId) {
        for (Map.Entry<String, PendingManualDnsOrder> pending : manualDnsOrders.entrySet()) {
            if (pending.getValue().certificateId() == certificateId) {
                return manualDnsRequest(pending.getKey());
            }
        }
        return null;
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
            PollBudget polls = new PollBudget();
            completeDnsAuthorizations(pending.order().authorizations(), polls);
            OrderResult result = finalizeOrder(pending.order(), polls);
            applyIssuedMaterial(certRow, result);
            certModel.save(certRow);
            certificateStore.loadFromDatabase();
            pending.flight().result().complete(result);
            return pending.certificateId();
        } catch (Exception e) {
            pending.flight().result().completeExceptionally(e);
            recordRenewalFailure(certRow, failureReason(e));
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

    /**
     * RFC-1123 hostname check for HTTP-01, which cannot validate wildcards.
     *
     * AIDEV-NOTE: the body moved to {@link Hostnames}, the ONE syntax authority, because
     * this used to be the only such check in the repo and it was wired to the
     * certificate-request handler alone -- the site-domain write path stored whatever it
     * was given. Keep this as the ACME-facing name; do not re-inline a second grammar here.
     */
    public static boolean isValidHostname(String hostname) {
        return Hostnames.isValidHostname(hostname);
    }

    /** @return the subset of hostnames that fail {@link #isValidHostname} */
    public static List<String> invalidHostnames(List<String> hostnames) {
        return invalidHostnames(hostnames, false);
    }

    /**
     * The domain a wildcard SAN names its hosts under: ACME's wildcard is exactly one leading
     * label over a literal domain, which is HostPattern's one-label {@code *.}.
     *
     * @return the domain, null for an exact name or anything that is not such a wildcard
     */
    public static @Nullable String wildcardSanBase(@Nullable String san) {
        HostPattern pattern = HostPattern.tryParse(san);
        return pattern == null || pattern.port() != null || pattern.spansManyLabels() ? null : pattern.base();
    }

    /** @return invalid names, optionally accepting one leading wildcard label for DNS-01 */
    public static List<String> invalidHostnames(List<String> hostnames, boolean allowWildcard) {
        List<String> invalid = new ArrayList<>();
        for (String hostname : hostnames) {
            String candidate = hostname != null ? hostname.trim().toLowerCase(Locale.ROOT) : null;
            String base = allowWildcard ? wildcardSanBase(candidate) : null;
            if (base != null) {
                candidate = base;
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
            var certModel = Models.get(CertificateModel.class);

            checkExpiryAlerts(certModel, Now.instant());

            List<Row> due = findRenewalCandidates(certModel, Now.instant());
            if (due.isEmpty()) return;

            Blast.log("ACME: found", due.size(), "certificates due for renewal");

            // Stagger: randomize order so a fleet of instances doesn't hammer the CA
            // with the same sequence every sweep.
            Collections.shuffle(due);

            for (Row cert : due) {
                renewCertificate(cert, certModel);
            }
        } catch (Exception e) {
            Blast.log("ACME: renewal check failed:", failureReason(e));
        }
    }

    /** Days before expiry at which the expiring-soon alert fires and the dashboard raises the certificate. */
    public static final int EXPIRY_ALERT_DAYS = 14;

    /**
     * Alert once per expiry cycle for certificates expiring soon: custom uploads never
     * auto-renew, and a Let's Encrypt cert this close to expiry means renewal is stuck.
     * The dedup stamp self-re-arms -- a successful renewal moves expires_on forward,
     * which makes the stamp older than the new alert window.
     */
    public static void checkExpiryAlerts(CertificateModel certModel, Instant now) {
        Instant cutoff = now.plus(EXPIRY_ALERT_DAYS, ChronoUnit.DAYS);
        for (Row cert : certModel.findExpiringSoon(cutoff)) {
            Instant expiresOn = cert.get(CertificateModel.EXPIRES_ON);
            if (expiresOn == null) continue;
            Instant notifiedAt = cert.get(CertificateModel.EXPIRY_NOTIFIED_AT);
            Instant alertWindowStart = expiresOn.minus(EXPIRY_ALERT_DAYS, ChronoUnit.DAYS);
            if (notifiedAt != null && !notifiedAt.isBefore(alertWindowStart)) {
                continue;   // already alerted for this expiry cycle
            }
            String niceName = cert.get(CertificateModel.NICE_NAME);
            Alerts.trySend(NotificationEvents.CERT_EXPIRING,
                Alerts.about(CertificateModel.MODEL_ID, cert.get(CertificateModel.ID)),
                HohenheimMicrocopy.ALERT.of("cert_expiring_subject").withArg("name", String.valueOf(niceName))
                    .withArg("expiry", CertificateExpiry.inSentence(expiresOn)),
                HohenheimMicrocopy.ALERT.of("cert_expiring_body")
                    .withArg("date", expiresOn));
            cert.set(CertificateModel.EXPIRY_NOTIFIED_AT, now);
            certModel.save(cert);
        }
    }

    /**
     * How long a certificate may stay pending before the sweep takes it for an order that died with its process: far
     * past any one order, whose polling is bounded per authorization ({@link #MAX_POLL_ATTEMPTS}) and per CA-paced wait
     * ({@link #POLL_BUDGET}).
     */
    static final Duration STALE_PENDING = Duration.ofHours(2);

    /**
     * Active certificates nearing expiry, errored certificates whose retry backoff has elapsed (errored certs used to
     * be filtered out forever), and pending ones whose order died with an earlier process.
     *
     * AIDEV-NOTE: a pending row is written before its order and settled after it, so a process that stops in between
     * (a crash, a deploy) left it pending forever: nothing retried it and the requester saw "Issuing" for good. A
     * manual DNS-01 order is pending by design while its operator publishes records; it never auto-renews, so the
     * auto_renew clause keeps it out.
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

        due.addAll(certModel.find()
            .where(CertificateModel.PROVIDER.eq(CertificateModel.PROVIDER_LETSENCRYPT))
            .where(CertificateModel.STATUS.eq(CertificateModel.STATUS_PENDING))
            .where(CertificateModel.AUTO_RENEW.eq(true))
            .where(CertificateModel.CREATED_AT.lte(now.minus(STALE_PENDING)))
            .all());

        return due;
    }

    /**
     * Re-order a stored certificate, re-deciding its authority first.
     *
     * AIDEV-NOTE: a failed re-authorization REFUSES AND SURFACES; it deliberately does not
     * clear auto_renew. Refusal is what closes the hole (no new certificate is issued), and
     * the existing failure bookkeeping already escalates the retry backoff to ~32h, alerts
     * once and shows the reason on the certificates page. Disabling auto-renew adds nothing
     * to the security outcome and subtracts recoverability: an authority change that is
     * later undone (a re-granted site, a restored domain row) would silently expire the
     * certificate weeks later with no failure left to notice.
     *
     * @return whether a new certificate was issued; a failure is stored on the row, never thrown
     */
    public boolean renewCertificate(Row certRow, CertificateModel certModel) {
        String domainsText = certRow.get(CertificateModel.DOMAIN_NAMES_TEXT);
        String niceName = certRow.get(CertificateModel.NICE_NAME);
        if (domainsText == null || domainsText.isEmpty()) return false;

        List<String> hostnames = Arrays.asList(domainsText.split(","));

        try {
            // No stored requester is an unattended order; a stored one is re-decided as the pair it is.
            boolean unattended = certRow.get(CertificateModel.REQUESTED_BY_USER_ID) == null;
            Map<String, Integer> declaring = CertificateAuthority.authorize(unattended
                ? CertificateAuthority.Requester.SYSTEM
                : CertificateAuthority.Requester.ofSubject(CertificateModel.requesterOf(certRow)), hostnames);

            String challengeType = certRow.get(CertificateModel.CHALLENGE_TYPE);
            if (challengeType == null || challengeType.isBlank()) {
                challengeType = CertificateModel.CHALLENGE_HTTP;
            }
            OrderResult result = performAcmeOrder(hostnames,
                certRow.get(CertificateModel.LETSENCRYPT_EMAIL), challengeType,
                certRow.get(CertificateModel.DNS_PUBLISHER),
                certRow.get(CertificateModel.ID), CertificateModel.requesterOf(certRow), declaring,
                signingOf(certRow, hostnames));

            applyIssuedMaterial(certRow, result);
            certModel.save(certRow);

            certificateStore.loadFromDatabase();
            Blast.log("ACME: renewed certificate", niceName);
            return true;

        } catch (Exception e) {
            String reason = failureReason(e);
            Blast.log("ACME: renewal failed for", niceName, "-", reason);
            recordRenewalFailure(certRow, reason);
            certModel.save(certRow);
            notifyRenewalFailure(certRow, niceName, reason);
            return false;
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
        Alerts.trySend(NotificationEvents.CERT_RENEWAL_FAILED,
            Alerts.about(CertificateModel.MODEL_ID, certRow.get(CertificateModel.ID)),
            HohenheimMicrocopy.ALERT.of("cert_renewal_failed_subject").withArg("name", String.valueOf(niceName)),
            HohenheimMicrocopy.ALERT.of("cert_renewal_failed_body").withArg("reason", message == null ? "-" : message));
    }

    /** Reset error/backoff state after a successful issuance or renewal. */
    public static void markRenewalSuccess(Row certRow) {
        certRow.set(CertificateModel.STATUS, CertificateModel.STATUS_ACTIVE);
        certRow.set(CertificateModel.RENEWAL_ERROR, null);
        certRow.set(CertificateModel.ERROR_COUNT, 0);
        certRow.set(CertificateModel.NEXT_ATTEMPT_AT, null);
        certRow.set(CertificateModel.RENEWAL_FAILED_AT, null);
    }

    /**
     * Record a failed issuance/renewal: escalate the error count, schedule the retry, stamp the moment
     * {@link RenewalFailure} reads, and note the failure in the certificate's activity.
     */
    public static void recordRenewalFailure(Row certRow, String message) {
        Integer previous = certRow.get(CertificateModel.ERROR_COUNT);
        int errorCount = (previous != null ? previous : 0) + 1;

        certRow.set(CertificateModel.STATUS, CertificateModel.STATUS_ERROR);
        certRow.set(CertificateModel.RENEWAL_ERROR, message);
        certRow.set(CertificateModel.ERROR_COUNT, errorCount);
        Instant now = Now.instant();
        certRow.set(CertificateModel.NEXT_ATTEMPT_AT, computeNextAttempt(errorCount, now));
        certRow.set(CertificateModel.RENEWAL_FAILED_AT, now);
        Object id = certRow.get(CertificateModel.ID);
        if (id != null) {
            History.event(Models.get(CertificateModel.class), id, Change.fact(HohenheimActivityAction.RENEWAL_FAILED,
                Map.of(HohenheimActivityAction.REASON_KEY, String.valueOf(message))));
        }
    }

    /**
     * Escalating backoff: 15min * 2^min(count,7) with +/-20% jitter, so repeated CA failures
     * back off from ~30 minutes up to ~32 hours instead of retrying every sweep.
     */
    public static Instant computeNextAttempt(int errorCount, Instant now) {
        return now.plus(RENEWAL_RETRY.delayAfter(errorCount));
    }

    // -----------------------------------------------------------------------
    // Core ACME order flow (shared by request and renewal)
    // -----------------------------------------------------------------------

    /**
     * Place a renewal order, joining an identical order of the same owner already in flight instead of doubling it.
     *
     * @param owner the certificate's stored requester, null for an unattended one
     */
    private OrderResult performAcmeOrder(List<String> hostnames, @Nullable String email,
                                         String challengeType, @Nullable String dnsPublisher,
                                         int certificateId, @Nullable PrincipalRef owner,
                                         Map<String, Integer> declaring, Signing signing) throws Exception {
        String orderKey = certificateOrderKey(hostnames, email, null, signing, owner);
        OrderFlight leader = new OrderFlight(true, new CompletableFuture<>());
        leader.certificateId().set(certificateId);
        OrderFlight existing = inFlightOrders.putIfAbsent(orderKey, leader);
        if (existing != null) {
            if (!existing.joinable()) {
                throw new IllegalStateException(
                        "A manual certificate order for these hostnames is already in progress");
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
        return leadOrder(orderKey, leader, hostnames, email, challengeType, dnsPublisher, declaring, signing);
    }

    /**
     * The key identical orders coalesce on: one owner, one CA account, one key holder, one name set.
     *
     * AIDEV-NOTE: the owner is part of the key. Without it a second account ordering the same names (from the same
     * signing request, or with a fresh key of Hohenheim's) joined the first account's flight and was handed that
     * account's row: its id and status in the answer, and through the shared {@link OrderResult} its chain and key.
     *
     * @param certificateId the row an order writes INTO when that row already exists, which
     *                      makes the key unshareable with any other row's order; null for
     *                      the lanes that may legitimately share one order
     * @param signing       whose key the order certifies ({@link Signing#orderKeyPart})
     * @param owner         the account the order is stored under ({@link CertificateModel#REQUESTER}), null for
     *                      unattended work
     */
    private static String certificateOrderKey(List<String> hostnames, @Nullable String email,
                                              @Nullable Integer certificateId, Signing signing,
                                              @Nullable PrincipalRef owner) {
        return (certificateId != null ? "cert" + certificateId + "@" : "")
            + "owner:" + (owner != null ? owner.key() : "") + "@" + signing.orderKeyPart()
            + normalizeAccountEmail(email) + ":" + hostnames.stream()
            .map(name -> name.trim().toLowerCase(Locale.ROOT))
            .distinct()
            .sorted()
            .collect(Collectors.joining(","));
    }

    private OrderResult performAcmeOrderUncoalesced(List<String> hostnames, @Nullable String email,
                                                     String challengeType,
                                                     @Nullable String dnsPublisher,
                                                     Map<String, Integer> declaring,
                                                     Signing signing) throws Exception {

        if (CertificateModel.CHALLENGE_DNS.equals(challengeType)) {
            DnsTxtPublisher publisher = DnsTxtPublishers.INSTANCE.get(dnsPublisher);
            if (publisher == null) {
                throw new IllegalStateException("DNS-01 publisher is unavailable: " + dnsPublisher);
            }
            DnsOrderContext context = prepareDnsOrder(hostnames, email, declaring, signing);
            List<DnsAuthorization> published = new ArrayList<>();
            try {
                for (DnsAuthorization authorization : context.authorizations()) {
                    // The challenge row is a GENERATED row: it is attributed to the domain
                    // row that authorized this SAN, and written under the "acme" origin
                    // rather than whoever happens to be logged in.
                    GeneratedDnsRecords.as(attributionFor(context, authorization),
                        () -> publisher.publish(authorization.record()));
                    published.add(authorization);
                }
                int propagation = Zenit.SETTINGS_VALUES.getValue(
                    HohenheimSettings.Ssl.DNS_PROPAGATION_SECONDS);
                if (propagation > 0 && !publisher.servesImmediately()) {
                    Pace.sleep(Duration.ofSeconds(propagation));
                }
                PollBudget polls = new PollBudget();
                completeDnsAuthorizations(context.authorizations(), polls);
                return finalizeOrder(context, polls);
            } finally {
                Collections.reverse(published);
                for (DnsAuthorization authorization : published) {
                    try {
                        // Removing its own row is the same system scope as writing it.
                        GeneratedDnsRecords.as(attributionFor(context, authorization),
                            () -> publisher.cleanup(authorization.record()));
                    } catch (Exception cleanupFailure) {
                        Blast.log("ACME: DNS TXT cleanup failed for", authorization.record().name(),
                            "-", cleanupFailure.getMessage());
                    }
                }
            }
        }

        Account account = ensureAccount(normalizeAccountEmail(email));
        Order order = createOrder(account, hostnames);

        Set<String> hostnameSet = new HashSet<>();
        for (String h : hostnames) hostnameSet.add(h.toLowerCase(Locale.ROOT));

        PollBudget polls = new PollBudget();
        for (Authorization auth : order.getAuthorizations()) {
            if (auth.getStatus() == Status.VALID) continue;
            completeHttpChallenge(auth, hostnameSet, polls);
        }

        return finalizeOrder(new DnsOrderContext(order, signing, hostnames, List.of(),
            declaring), polls);
    }

    private static Order createOrder(Account account, List<String> hostnames) throws Exception {
        OrderBuilder orderBuilder = account.newOrder();
        for (String hostname : hostnames) {
            orderBuilder.domain(hostname);
        }
        return orderBuilder.create();
    }

    private DnsOrderContext prepareDnsOrder(List<String> hostnames, @Nullable String email,
                                            Map<String, Integer> declaring, Signing signing) throws Exception {
        Account account = ensureAccount(normalizeAccountEmail(email));
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
                new DnsTxtRecord(Dns01Challenge.toRRName(auth.getIdentifier()), challenge.getDigest()),
                auth.getIdentifier().getDomain()));
        }
        return new DnsOrderContext(order, signing, List.copyOf(hostnames),
            List.copyOf(authorizations), Map.copyOf(declaring));
    }

    /**
     * The declaring domain row for one authorization.
     *
     * AIDEV-NOTE: the ACME identifier of a wildcard SAN drops the leading wildcard label, so
     * the wildcard over example.com is authorized under identifier example.com -- both
     * spellings must be looked up or a wildcard challenge lands unattributed.
     */
    private static GeneratedDnsRecords.Attribution attributionFor(DnsOrderContext context,
                                                                  DnsAuthorization authorization) {
        String identifier = authorization.identifier() != null
            ? authorization.identifier().toLowerCase(Locale.ROOT) : "";
        Integer domainId = context.declaring().get(identifier);
        if (domainId == null) {
            for (Map.Entry<String, Integer> declared : context.declaring().entrySet()) {
                if (identifier.equals(wildcardSanBase(declared.getKey()))) {
                    domainId = declared.getValue();
                    break;
                }
            }
        }
        return new GeneratedDnsRecords.Attribution(GeneratedDnsRecords.SOURCE_ACME,
            SiteDomainModel.MODEL_ID.toString(), domainId);
    }

    private void completeDnsAuthorizations(List<DnsAuthorization> authorizations, PollBudget polls)
            throws Exception {
        for (DnsAuthorization pending : authorizations) {
            pending.challenge().trigger();
        }
        for (DnsAuthorization pending : authorizations) {
            awaitAuthorization(pending.authorization(), pending.challenge(), polls);
        }
    }

    // AIDEV-NOTE: poll BEFORE sleeping, in all three loops below. They used to sleep first,
    // so a CA that had already validated still cost a full POLL_CADENCE per authorization
    // -- 18 of AcmeIssuanceContractTest's 19.2s were this Thread.sleep against an in-process
    // fake that answers synchronously, and against a real CA it is a needless 3s on every
    // issuance. RFC 8555 polling starts immediately; the interval is the gap BETWEEN attempts.
    private void awaitAuthorization(Authorization auth, Dns01Challenge challenge, PollBudget polls) throws Exception {
        for (int i = 0; i < MAX_POLL_ATTEMPTS; i++) {
            Instant retryAt = auth.fetch().orElse(null);
            if (auth.getStatus() == Status.VALID) return;
            if (auth.getStatus() == Status.INVALID) {
                Challenge failed = auth.findChallenge(Dns01Challenge.class).orElse(challenge);
                throw challengeFailure(auth.getIdentifier().getDomain(), failed);
            }
            this.pollPause.pause(polls.next(Now.instant(), retryAt));
        }
        throw new RuntimeException("Challenge timed out for " + auth.getIdentifier().getDomain());
    }

    private OrderResult finalizeOrder(DnsOrderContext context, PollBudget polls) throws Exception {
        context.order().execute(context.signing().csr(context.hostnames()));

        Order order = context.order();
        // AIDEV-NOTE: getStatus can lazily fetch after execute; that hidden poll would discard its Retry-After.
        // Fetch explicitly first so every pending response earns its wait before the next request.
        for (int i = 0; i < MAX_POLL_ATTEMPTS; i++) {
            Instant retryAt = order.fetch().orElse(null);
            if (order.getStatus() == Status.INVALID) {
                throw orderFailure(order);
            }
            if (order.getStatus() == Status.VALID) {
                break;
            }
            this.pollPause.pause(polls.next(Now.instant(), retryAt));
        }

        if (order.getStatus() != Status.VALID) {
            throw new RuntimeException("Order did not complete in time");
        }

        Certificate acmeCert = order.getCertificate();
        List<X509Certificate> chain = acmeCert.getCertificateChain();
        X509Certificate leaf = chain.get(0);
        context.signing().requireCertifies(leaf);

        return new OrderResult(CertificatePem.pemOf(chain), leaf.getNotAfter().toInstant(), context.signing());
    }

    // -----------------------------------------------------------------------
    // HTTP-01 challenge handling
    // -----------------------------------------------------------------------

    private void completeHttpChallenge(Authorization auth, Set<String> validHostnames, PollBudget polls)
            throws Exception {
        Http01Challenge challenge = auth.findChallenge(Http01Challenge.class)
            .orElseThrow(() -> new RuntimeException(
                "No HTTP-01 challenge available for " + auth.getIdentifier().getDomain()));

        pendingChallenges.put(challenge.getToken(),
            new ChallengeEntry(challenge.getAuthorization(), validHostnames));

        try {
            challenge.trigger();

            for (int i = 0; i < MAX_POLL_ATTEMPTS; i++) {
                Instant retryAt = auth.fetch().orElse(null);
                if (auth.getStatus() == Status.VALID) return;
                if (auth.getStatus() == Status.INVALID) {
                    Challenge failed = auth.findChallenge(Http01Challenge.class).orElse(challenge);
                    throw challengeFailure(auth.getIdentifier().getDomain(), failed);
                }
                this.pollPause.pause(polls.next(Now.instant(), retryAt));
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
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return "";

        String global = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Ssl.LETSENCRYPT_EMAIL);
        if (global != null && normalized.equals(global.trim().toLowerCase(Locale.ROOT))) return "";

        return normalized;
    }

    /**
     * The ACME directory every order and account registration goes to.
     *
     * AIDEV-NOTE: THE fail-closed point of a test lane: every order and renewal reaches a CA through here, so a test
     * that left the directory at Let's Encrypt (or pointed it anywhere but a fake CA on the loopback, which is where
     * FakeAcmeServer listens) is refused before any network call, whatever path ordered.
     *
     * @param staging whether the Let's Encrypt staging directory is asked for; ignored
     *                when an explicit directory URL is configured
     * @throws IllegalStateException in a test lane, for a directory that is no loopback address
     */
    static String directoryUri(boolean staging) {
        String configured = Zenit.SETTINGS_VALUES.getValue(
            HohenheimSettings.Ssl.ACME_DIRECTORY_URL);
        String directory = configured != null && !configured.isBlank() ? configured.trim()
            : staging ? "acme://letsencrypt.org/staging" : "acme://letsencrypt.org";
        if (ServerZenitRuntime.isTestLane() && !onLoopback(directory)) {
            throw new IllegalStateException("A test orders from a fake certificate authority on the loopback only,"
                + " never from " + directory);
        }
        return directory;
    }

    /** @return whether the directory is an HTTP address on a loopback literal */
    private static boolean onLoopback(@NonNull String directory) {
        URI uri;
        try {
            uri = new URI(directory);
        } catch (URISyntaxException unreadable) {
            return false;
        }
        String host = uri.getHost();
        return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && host != null
            && IpRanges.matchesAnyLiteral(IpRanges.LOOPBACK, IpRanges.stripBrackets(host));
    }

    /**
     * The account for one email on the CA the settings name NOW.
     *
     * AIDEV-NOTE: cached per directory AND email. Keyed by email alone, a session opened on one directory served every
     * later order until a restart, so switching ssl.letsencrypt_staging (an operator setting with no restart) or the
     * directory URL kept ordering from the CA the process first spoke to. The account KEY stays per email: the same
     * key registers one account on each directory.
     */
    private synchronized Account ensureAccount(String normalizedEmail) throws Exception {
        boolean staging = HohenheimSettings.isOn(HohenheimSettings.Ssl.LETSENCRYPT_STAGING);

        String serverUri = directoryUri(staging);
        String accountKey = serverUri + " " + normalizedEmail;
        Account existing = accounts.get(accountKey);
        if (existing != null) return existing;

        String email = normalizedEmail.isEmpty()
            ? Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Ssl.LETSENCRYPT_EMAIL)
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
        accounts.put(accountKey, account);
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
        var certModel = Models.get(CertificateModel.class);

        // A handful of rows at most; match the email key in Java since NULL marks the global row.
        List<Row> accountRows = certModel.find()
            .where(CertificateModel.PROVIDER.eq(CertificateModel.PROVIDER_ACME_ACCOUNT))
            .all();
        for (Row row : accountRows) {
            String rowEmail = row.get(CertificateModel.LETSENCRYPT_EMAIL);
            String rowKey = trimmed(rowEmail).toLowerCase(Locale.ROOT);
            if (!rowKey.equals(normalizedEmail)) continue;

            String keyPem = row.get(CertificateModel.PRIVATE_KEY_PEM);
            if (keyPem != null) {
                try (var reader = new StringReader(keyPem)) {
                    return KeyPairUtils.readKeyPair(reader);
                }
            }
        }

        KeyPair keyPair = KeyPairUtils.createKeyPair(2048);
        String keyPem = privateKeyToPem(keyPair);

        Row newRow = certModel.createEmptyRow();
        newRow.set(CertificateModel.NICE_NAME, normalizedEmail.isEmpty()
            ? "ACME Account Key"
            : "ACME Account Key (" + normalizedEmail + ")");
        newRow.set(CertificateModel.PROVIDER, CertificateModel.PROVIDER_ACME_ACCOUNT);
        newRow.set(CertificateModel.PRIVATE_KEY_PEM, keyPem);
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

    private static String privateKeyToPem(KeyPair keyPair) throws Exception {
        StringWriter sw = new StringWriter();
        KeyPairUtils.writeKeyPair(keyPair, sw);
        return sw.toString();
    }
}
