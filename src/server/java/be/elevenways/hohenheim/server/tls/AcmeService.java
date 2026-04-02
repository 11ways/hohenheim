package be.elevenways.hohenheim.server.tls;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import org.shredzone.acme4j.*;
import org.shredzone.acme4j.challenge.Http01Challenge;
import org.shredzone.acme4j.util.CSRBuilder;
import org.shredzone.acme4j.util.KeyPairUtils;

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

    // Pending HTTP-01 challenges: token -> authorization content
    private final ConcurrentHashMap<String, String> pendingChallenges = new ConcurrentHashMap<>();

    // ACME account (lazily created)
    private Account account;
    private KeyPair accountKeyPair;

    public AcmeService(CertificateStore certificateStore) {
        this.certificateStore = certificateStore;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "acme-renewal");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Start the renewal scheduler.
     */
    public void start() {
        scheduler.scheduleAtFixedRate(this::checkRenewals, RENEWAL_CHECK_HOURS,
            RENEWAL_CHECK_HOURS, TimeUnit.HOURS);
        Blast.log("ACME renewal scheduler started (every", RENEWAL_CHECK_HOURS, "hours)");
    }

    /**
     * Lookup a pending HTTP-01 challenge response by token.
     * Called by SiteDispatcher when it intercepts /.well-known/acme-challenge/ requests.
     */
    public String getChallengeResponse(String token) {
        return pendingChallenges.get(token);
    }

    /**
     * Request a new Let's Encrypt certificate for the given hostnames.
     * Blocks until the certificate is issued or an error occurs.
     *
     * @return the certificate database row ID, or -1 on failure
     */
    public int requestCertificate(List<String> hostnames, String niceName) {
        var ds = HohenheimDatabase.datasource();
        var certModel = new CertificateModel(ds);

        // Create a pending certificate row
        Row certRow = certModel.createEmptyRow();
        certRow.set(CertificateModel.NICE_NAME, niceName);
        certRow.set(CertificateModel.PROVIDER, "letsencrypt");
        certRow.set(CertificateModel.STATUS, "pending");
        certModel.save(certRow);
        int certId = ((Number) certRow.get(CertificateModel.ID)).intValue();

        try {
            ensureAccount();

            // Create domain key pair
            KeyPair domainKeyPair = KeyPairUtils.createKeyPair(2048);

            // Order certificate
            OrderBuilder orderBuilder = account.newOrder();
            for (String hostname : hostnames) {
                orderBuilder.domain(hostname);
            }
            Order order = orderBuilder.create();

            // Complete challenges
            for (Authorization auth : order.getAuthorizations()) {
                if (auth.getStatus() == Status.VALID) continue;
                completeHttpChallenge(auth);
            }

            // Generate CSR and finalize
            CSRBuilder csrBuilder = new CSRBuilder();
            for (String hostname : hostnames) {
                csrBuilder.addDomain(hostname);
            }
            csrBuilder.sign(domainKeyPair);
            order.execute(csrBuilder.getEncoded());

            // Poll for completion
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

            // Download certificate
            Certificate acmeCert = order.getCertificate();
            List<X509Certificate> chain = acmeCert.getCertificateChain();
            X509Certificate leaf = chain.get(0);

            // Serialize to PEM
            String certPem = certificateChainToPem(chain);
            String keyPem = privateKeyToPem(domainKeyPair);

            // Update the database row
            certRow.set(CertificateModel.CERTIFICATE_PEM, certPem);
            certRow.set(CertificateModel.PRIVATE_KEY_PEM, keyPem);
            certRow.set(CertificateModel.STATUS, "active");
            certRow.set(CertificateModel.EXPIRES_ON, leaf.getNotAfter().toInstant());
            certRow.set(CertificateModel.ISSUED_ON, Instant.now());

            // Store domain names as comma-separated
            certRow.set(CertificateModel.DOMAIN_NAMES_TEXT, String.join(",", hostnames));
            certModel.save(certRow);

            // Load into the live certificate store
            certificateStore.addCertificateFromRow(certRow);

            Blast.log("ACME: certificate issued for", String.join(", ", hostnames));
            return certId;

        } catch (Exception e) {
            Blast.log("ACME: certificate request failed for", String.join(", ", hostnames), "-", e.getMessage());
            e.printStackTrace();

            // Update the row with error status
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
            var certModel = new CertificateModel(ds);

            Instant cutoff = Instant.now().plus(RENEWAL_THRESHOLD_DAYS, ChronoUnit.DAYS);
            List<Row> expiring = certModel.find()
                .where(CertificateModel.PROVIDER.eq("letsencrypt"))
                .and(CertificateModel.STATUS.eq("active"))
                .and(CertificateModel.EXPIRES_ON.lte(cutoff))
                .all();

            if (expiring.isEmpty()) return;

            Blast.log("ACME: found", expiring.size(), "certificates due for renewal");

            for (Row cert : expiring) {
                String domainsText = (String) cert.get(CertificateModel.DOMAIN_NAMES_TEXT);
                String niceName = (String) cert.get(CertificateModel.NICE_NAME);
                if (domainsText == null || domainsText.isEmpty()) continue;

                List<String> hostnames = Arrays.asList(domainsText.split(","));
                try {
                    renewCertificate(cert, hostnames);
                } catch (Exception e) {
                    Blast.log("ACME: renewal failed for", niceName, "-", e.getMessage());
                    cert.set(CertificateModel.STATUS, "error");
                    cert.set(CertificateModel.RENEWAL_ERROR, e.getMessage());
                    certModel.save(cert);
                }
            }
        } catch (Exception e) {
            Blast.log("ACME: renewal check failed:", e.getMessage());
        }
    }

    private void renewCertificate(Row certRow, List<String> hostnames) throws Exception {
        var ds = HohenheimDatabase.datasource();
        var certModel = new CertificateModel(ds);

        ensureAccount();

        KeyPair domainKeyPair = KeyPairUtils.createKeyPair(2048);

        OrderBuilder orderBuilder = account.newOrder();
        for (String hostname : hostnames) {
            orderBuilder.domain(hostname);
        }
        Order order = orderBuilder.create();

        for (Authorization auth : order.getAuthorizations()) {
            if (auth.getStatus() == Status.VALID) continue;
            completeHttpChallenge(auth);
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
                throw new RuntimeException("Renewal order rejected by CA");
            }
        }

        if (order.getStatus() != Status.VALID) {
            throw new RuntimeException("Renewal order did not complete in time");
        }

        Certificate acmeCert = order.getCertificate();
        List<X509Certificate> chain = acmeCert.getCertificateChain();
        X509Certificate leaf = chain.get(0);

        certRow.set(CertificateModel.CERTIFICATE_PEM, certificateChainToPem(chain));
        certRow.set(CertificateModel.PRIVATE_KEY_PEM, privateKeyToPem(domainKeyPair));
        certRow.set(CertificateModel.STATUS, "active");
        certRow.set(CertificateModel.EXPIRES_ON, leaf.getNotAfter().toInstant());
        certRow.set(CertificateModel.ISSUED_ON, Instant.now());
        certRow.set(CertificateModel.RENEWAL_ERROR, null);
        certModel.save(certRow);

        certificateStore.loadFromDatabase();

        String niceName = (String) certRow.get(CertificateModel.NICE_NAME);
        Blast.log("ACME: renewed certificate", niceName);
    }

    // -----------------------------------------------------------------------
    // HTTP-01 challenge handling
    // -----------------------------------------------------------------------

    private void completeHttpChallenge(Authorization auth) throws Exception {
        Http01Challenge challenge = auth.findChallenge(Http01Challenge.class)
            .orElseThrow(() -> new RuntimeException(
                "No HTTP-01 challenge available for " + auth.getIdentifier().getDomain()));

        // Provision the challenge response
        pendingChallenges.put(challenge.getToken(), challenge.getAuthorization());

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

        // Load or create account key pair
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
        var certModel = new CertificateModel(ds);

        // Look for existing account key in a special row
        Row accountRow = certModel.find()
            .where(CertificateModel.PROVIDER.eq("acme_account"))
            .first();

        if (accountRow != null) {
            String keyPem = (String) accountRow.get(CertificateModel.PRIVATE_KEY_PEM);
            if (keyPem != null) {
                try (var reader = new java.io.StringReader(keyPem)) {
                    return KeyPairUtils.readKeyPair(reader);
                }
            }
        }

        // Generate new key pair and store it
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
