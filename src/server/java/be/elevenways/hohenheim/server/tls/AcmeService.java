package be.elevenways.hohenheim.server.tls;

import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import org.shredzone.acme4j.*;
import org.shredzone.acme4j.challenge.Http01Challenge;
import org.shredzone.acme4j.util.CSRBuilder;
import org.shredzone.acme4j.util.KeyPairUtils;

import java.io.StringReader;
import java.io.StringWriter;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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

    private final CertificateStore certificateStore;
    private final ScheduledExecutorService scheduler;

    /**
     * Pending HTTP-01 challenges: token -> challenge entry with authorization and valid hostnames.
     */
    private final ConcurrentHashMap<String, ChallengeEntry> pendingChallenges = new ConcurrentHashMap<>();

    private Account account;
    private KeyPair accountKeyPair;

    private record ChallengeEntry(String authorization, Set<String> validHostnames) {}

    /**
     * Result of an ACME certificate order.
     */
    private record OrderResult(String certPem, String keyPem, Instant expiresAt) {}

    public AcmeService(CertificateStore certificateStore) {
        this.certificateStore = certificateStore;
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
    public int requestCertificate(List<String> hostnames, String niceName) {
        var ds = HohenheimDatabase.datasource();
        var certModel = Models.get(CertificateModel.class);

        Row certRow = certModel.createEmptyRow();
        certRow.set(CertificateModel.NICE_NAME, niceName);
        certRow.set(CertificateModel.PROVIDER, "letsencrypt");
        certRow.set(CertificateModel.STATUS, "pending");
        certRow.set(CertificateModel.DOMAIN_NAMES_TEXT, String.join(",", hostnames));
        certModel.save(certRow);
        int certId = certRow.get(CertificateModel.ID);

        try {
            OrderResult result = performAcmeOrder(hostnames);

            certRow.set(CertificateModel.CERTIFICATE_PEM, result.certPem);
            certRow.set(CertificateModel.PRIVATE_KEY_PEM, result.keyPem);
            certRow.set(CertificateModel.STATUS, "active");
            certRow.set(CertificateModel.EXPIRES_ON, result.expiresAt);
            certRow.set(CertificateModel.ISSUED_ON, Instant.now());
            certRow.set(CertificateModel.RENEWAL_ERROR, null);
            certModel.save(certRow);

            certificateStore.loadFromDatabase();
            Blast.log("ACME: certificate issued for", String.join(", ", hostnames));
            return certId;

        } catch (Exception e) {
            Blast.log("ACME: certificate request failed for", String.join(", ", hostnames), "-", e.getMessage());

            certRow.set(CertificateModel.STATUS, "error");
            certRow.set(CertificateModel.RENEWAL_ERROR, e.getMessage());
            certModel.save(certRow);

            return -1;
        }
    }

    /**
     * Check all Let's Encrypt certificates for upcoming expiry and renew them.
     */
    private void checkRenewals() {
        try {
            var ds = HohenheimDatabase.datasource();
            var certModel = Models.get(CertificateModel.class);

            Instant cutoff = Instant.now().plus(RENEWAL_THRESHOLD_DAYS, ChronoUnit.DAYS);
            List<Row> expiring = certModel.find()
                .where(CertificateModel.PROVIDER.eq("letsencrypt"))
                .where(CertificateModel.STATUS.eq("active"))
                .where(CertificateModel.AUTO_RENEW.eq(true))
                .where(CertificateModel.EXPIRES_ON.lte(cutoff))
                .all();

            if (expiring.isEmpty()) return;

            Blast.log("ACME: found", expiring.size(), "certificates due for renewal");

            for (Row cert : expiring) {
                renewCertificate(cert, certModel);
            }
        } catch (Exception e) {
            Blast.log("ACME: renewal check failed:", e.getMessage());
        }
    }

    private void renewCertificate(Row certRow, CertificateModel certModel) {
        String domainsText = certRow.get(CertificateModel.DOMAIN_NAMES_TEXT);
        String niceName = certRow.get(CertificateModel.NICE_NAME);
        if (domainsText == null || domainsText.isEmpty()) return;

        List<String> hostnames = Arrays.asList(domainsText.split(","));

        try {
            OrderResult result = performAcmeOrder(hostnames);

            certRow.set(CertificateModel.CERTIFICATE_PEM, result.certPem);
            certRow.set(CertificateModel.PRIVATE_KEY_PEM, result.keyPem);
            certRow.set(CertificateModel.STATUS, "active");
            certRow.set(CertificateModel.EXPIRES_ON, result.expiresAt);
            certRow.set(CertificateModel.ISSUED_ON, Instant.now());
            certRow.set(CertificateModel.RENEWAL_ERROR, null);
            certModel.save(certRow);

            certificateStore.loadFromDatabase();
            Blast.log("ACME: renewed certificate", niceName);

        } catch (Exception e) {
            Blast.log("ACME: renewal failed for", niceName, "-", e.getMessage());
            certRow.set(CertificateModel.STATUS, "error");
            certRow.set(CertificateModel.RENEWAL_ERROR, e.getMessage());
            certModel.save(certRow);
        }
    }

    // -----------------------------------------------------------------------
    // Core ACME order flow (shared by request and renewal)
    // -----------------------------------------------------------------------

    private OrderResult performAcmeOrder(List<String> hostnames) throws Exception {
        ensureAccount();

        KeyPair domainKeyPair = KeyPairUtils.createKeyPair(2048);

        OrderBuilder orderBuilder = account.newOrder();
        for (String hostname : hostnames) {
            orderBuilder.domain(hostname);
        }
        Order order = orderBuilder.create();

        Set<String> hostnameSet = new HashSet<>();
        for (String h : hostnames) hostnameSet.add(h.toLowerCase());

        for (Authorization auth : order.getAuthorizations()) {
            if (auth.getStatus() == Status.VALID) continue;
            completeHttpChallenge(auth, hostnameSet);
        }

        CSRBuilder csrBuilder = new CSRBuilder();
        for (String hostname : hostnames) {
            csrBuilder.addDomain(hostname);
        }
        csrBuilder.sign(domainKeyPair);
        order.execute(csrBuilder.getEncoded());

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
            privateKeyToPem(domainKeyPair),
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

    private synchronized void ensureAccount() throws Exception {
        if (account != null) return;

        String email = HohenheimSettings.VALUES.getValue(HohenheimSettings.Ssl.LETSENCRYPT_EMAIL);
        boolean staging = Boolean.TRUE.equals(
            HohenheimSettings.VALUES.getValue(HohenheimSettings.Ssl.LETSENCRYPT_STAGING));

        String serverUri = staging
            ? "acme://letsencrypt.org/staging"
            : "acme://letsencrypt.org";

        accountKeyPair = loadOrCreateAccountKeyPair();

        Session session = new Session(serverUri);
        AccountBuilder builder = new AccountBuilder()
            .agreeToTermsOfService()
            .useKeyPair(accountKeyPair);

        if (email != null && !email.isEmpty()) {
            builder.addEmail(email);
        }

        account = builder.create(session);
        Blast.log("ACME: account ready (" + (staging ? "staging" : "production") + ")");
    }

    private KeyPair loadOrCreateAccountKeyPair() throws Exception {
        var ds = HohenheimDatabase.datasource();
        var certModel = Models.get(CertificateModel.class);

        Row accountRow = certModel.find()
            .where(CertificateModel.PROVIDER.eq("acme_account"))
            .first();

        if (accountRow != null) {
            String keyPem = accountRow.get(CertificateModel.PRIVATE_KEY_PEM);
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
        newRow.set(CertificateModel.NICE_NAME, "ACME Account Key");
        newRow.set(CertificateModel.PROVIDER, "acme_account");
        newRow.set(CertificateModel.PRIVATE_KEY_PEM, sw.toString());
        newRow.set(CertificateModel.STATUS, "active");
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
