package be.elevenways.hohenheim.server.tls;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.hohenheim.test.tls.FakeAcmeServer;
import be.elevenways.hohenheim.test.tls.RecordingTxtPublisher;
import be.elevenways.protoblast.common.thread.JobRunner;
import be.elevenways.protoblast.common.thread.ScheduledJob;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.PrincipalRef;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Re-issuing a stored certificate IN PLACE: adding a hostname, switching HTTP-01 to DNS-01,
 * and what a failed re-issue must NOT do. Runs against {@link FakeAcmeServer}, the same
 * in-JVM RFC 8555 CA the issuance contract uses; nothing here reaches Let's Encrypt.
 *
 * The load-bearing property is asymmetric: a SUCCESSFUL re-issue rewrites the row's
 * certificate AND the names/challenge it was ordered for, while a FAILED one leaves the row
 * byte-identical -- because the row is what renewal re-orders from, and a row carrying names
 * no certificate was ever issued for would renew into that same failure forever.
 *
 * This lives in the service's own package because the renewal entry point is package-private
 * and "renewal thereafter uses the NEW names" is the assertion that makes the same-row write
 * mean anything; the service's timers are fired through its package-private lane seam.
 */
class AcmeReissueContractTest {

    /** Every hostname here ends in this, so no other class in the shared fork covers it. */
    private static final String ZONE = "reissue.test";

    private static SqlDatasource datasource;
    private static FakeAcmeServer ca;
    private static AcmeService acme;
    private static RecordingTxtPublisher publisher;
    private static String savedDirectory;
    private static Integer savedPropagation;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = TestDatabases.freshDatasource();
        HohenheimTestRuntime.ensureBooted();

        ca = new FakeAcmeServer();
        savedDirectory = Zenit.SETTINGS_VALUES.getValue(
            HohenheimSettings.Ssl.ACME_DIRECTORY_URL);
        savedPropagation = Zenit.SETTINGS_VALUES.getValue(
            HohenheimSettings.Ssl.DNS_PROPAGATION_SECONDS);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Ssl.ACME_DIRECTORY_URL,
            ca.directoryUrl());
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Ssl.DNS_PROPAGATION_SECONDS, 0);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Ssl.LETSENCRYPT_EMAIL,
            "reissue-test@example.com");

        acme = new AcmeService(new CertificateStore());
        publisher = new RecordingTxtPublisher("recording_reissue_txt");
        DnsTxtPublishers.INSTANCE.register(publisher);
    }

    @AfterAll
    static void tearDown() {
        if (ca != null) {
            ca.close();
            ca = null;
        }
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Ssl.ACME_DIRECTORY_URL,
            savedDirectory);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Ssl.DNS_PROPAGATION_SECONDS,
            savedPropagation);
    }

    /**
     * The whole re-issue journey on ONE row: add a name, prove renewal follows the new set,
     * switch the challenge type, then fail an order and prove the row did not move.
     */
    @Test
    void aReissueRewritesTheRowOnSuccessAndNeverOnFailure() {
        Db.run(datasource, () -> {
            var certModel = Models.get(CertificateModel.class);
            int siteId = site("reissue-owned");
            domain(siteId, "one." + ZONE);
            domain(siteId, "two." + ZONE);
            domain(siteId, "three." + ZONE);
            answerHttpChallenges();

            // 1. A perfectly ordinary certificate exists first: one name, HTTP-01.
            AcmeService.RequestOutcome initial = acme.requestCertificate(List.of("one." + ZONE),
                "Reissue subject", null, CertificateAuthority.Requester.SYSTEM);
            assertThat(initial.issued()).as("step 1: the initial order produced a row").isTrue();
            int certId = initial.certificateId();
            Row cert = certModel.findById(certId);
            String firstPem = cert.get(CertificateModel.CERTIFICATE_PEM);
            // A stored requester the re-issue must overwrite with the actor that re-issued.
            CertificateModel.setRequester(cert, PrincipalRef.account(4242));
            certModel.save(cert);

            // 2. RE-ISSUE WITH AN ADDED NAME: same row, new material, new name list.
            AcmeService.ReissueResult added = acme.reissueCertificate(certModel.findById(certId),
                List.of("one." + ZONE, "two." + ZONE), null, CertificateModel.CHALLENGE_HTTP,
                null, CertificateAuthority.Requester.SYSTEM);
            assertThat(added.issued())
                .as("step 2: the re-issue succeeded (%s)", added.failureReason()).isTrue();
            assertThat(added.failureReason())
                .as("step 2: a successful re-issue reports no reason").isNull();

            Row afterAdd = certModel.findById(certId);
            assertThat(afterAdd).as("step 2: the SAME row is what was updated").isNotNull();
            assertThat(subjectAltNames(leafOf(afterAdd.get(CertificateModel.CERTIFICATE_PEM))))
                .as("step 2: the stored certificate covers both names")
                .containsExactlyInAnyOrder("one." + ZONE, "two." + ZONE);
            assertThat((String) afterAdd.get(CertificateModel.CERTIFICATE_PEM))
                .as("step 2: and is genuinely new material, not the old chain")
                .isNotEqualTo(firstPem);
            assertThat((String) afterAdd.get(CertificateModel.DOMAIN_NAMES_TEXT))
                .as("step 2: the row's own name list moved with it")
                .isEqualTo("one." + ZONE + ",two." + ZONE);
            assertThat((String) afterAdd.get(CertificateModel.STATUS))
                .as("step 2: and the row is active").isEqualTo(CertificateModel.STATUS_ACTIVE);
            assertThat(CertificateModel.requesterOf(afterAdd))
                .as("step 2: the re-issuing actor is who renewal re-authorizes as from now on")
                .isNull();
            assertThat((String) afterAdd.get(CertificateModel.REQUESTED_BY_KIND))
                .as("step 2: and its kind went with the id").isNull();

            // 3. RENEWAL FOLLOWS: the sweep re-orders the NEW set, not the one the row was
            //    created with. This is the whole point of writing the names on success.
            Row toRenew = certModel.findById(certId);
            acme.renewCertificate(toRenew, certModel);
            Row renewed = certModel.findById(certId);
            assertThat((String) renewed.get(CertificateModel.STATUS))
                .as("step 3: the renewal succeeded (%s)",
                    renewed.get(CertificateModel.RENEWAL_ERROR))
                .isEqualTo(CertificateModel.STATUS_ACTIVE);
            assertThat(subjectAltNames(leafOf(renewed.get(CertificateModel.CERTIFICATE_PEM))))
                .as("step 3: the renewed certificate carries the RE-ISSUED name set")
                .containsExactlyInAnyOrder("one." + ZONE, "two." + ZONE);

            // 4. CHALLENGE SWITCH http -> dns: the publisher is really used and recorded.
            ca.validateDnsWith((token, identifier) ->
                publisher.valueOf("_acme-challenge." + identifier + ".") != null);
            publisher.published.clear();
            AcmeService.ReissueResult switched = acme.reissueCertificate(
                certModel.findById(certId), List.of("one." + ZONE, "two." + ZONE), null,
                CertificateModel.CHALLENGE_DNS, publisher.id(),
                CertificateAuthority.Requester.SYSTEM);
            assertThat(switched.issued())
                .as("step 4: the DNS-01 re-issue succeeded (%s)", switched.failureReason())
                .isTrue();
            assertThat(publisher.published)
                .as("step 4: a TXT record was published for every name")
                .hasSize(2);
            Row afterSwitch = certModel.findById(certId);
            assertThat((String) afterSwitch.get(CertificateModel.CHALLENGE_TYPE))
                .as("step 4: the row now says DNS-01")
                .isEqualTo(CertificateModel.CHALLENGE_DNS);
            assertThat((String) afterSwitch.get(CertificateModel.DNS_PUBLISHER))
                .as("step 4: with the publisher that answered for it")
                .isEqualTo(publisher.id());

            // 5. THE FAILURE PATH. A refused order must leave the row untouched -- every
            //    column, not just the certificate: the old certificate keeps serving and
            //    keeps renewing against the names it was actually issued for.
            Map<String, Object> before = snapshot(certModel.findById(certId));
            ca.refuseValidation(true);
            AcmeService.ReissueResult failed = acme.reissueCertificate(certModel.findById(certId),
                List.of("one." + ZONE, "two." + ZONE, "three." + ZONE), null,
                CertificateModel.CHALLENGE_HTTP, null, CertificateAuthority.Requester.SYSTEM);
            ca.refuseValidation(false);
            assertThat(failed.issued()).as("step 5: the re-issue reports failure").isFalse();
            assertThat(failed.failureReason())
                .as("step 5: and reports the CA's own refusal, which names the identifier "
                    + "it could not validate (the CA fails the first authorization it walks, "
                    + "so that is not necessarily the ADDED name)")
                .isNotNull()
                .contains("Challenge failed for")
                .contains(ZONE);
            assertThat(snapshot(certModel.findById(certId)))
                .as("step 5: the row is byte-identical: no error state, no new names, "
                    + "no lost certificate")
                .isEqualTo(before);

            // 6. And it is still a healthy, renewable certificate afterwards.
            answerHttpChallenges();
            Row survivor = certModel.findById(certId);
            acme.renewCertificate(survivor, certModel);
            Row renewedAgain = certModel.findById(certId);
            assertThat((String) renewedAgain.get(CertificateModel.STATUS))
                .as("step 6: the surviving certificate renews (%s)",
                    renewedAgain.get(CertificateModel.RENEWAL_ERROR))
                .isEqualTo(CertificateModel.STATUS_ACTIVE);
            assertThat(subjectAltNames(leafOf(renewedAgain.get(CertificateModel.CERTIFICATE_PEM))))
                .as("step 6: on the name set the LAST SUCCESSFUL order used")
                .containsExactlyInAnyOrder("one." + ZONE, "two." + ZONE);
        });
    }

    /**
     * The refusals a re-issue owes, none of which the UI may be trusted to enforce: the FULL
     * new name set is authorized (never "the old ones were once allowed"), a manual upload
     * has no order to repeat, and the manual DNS lane cannot write into an existing row.
     */
    @Test
    void aReissueRefusesUnauthorizedNamesAndNonAcmeRows() {
        Db.run(datasource, () -> {
            var certModel = Models.get(CertificateModel.class);
            int siteId = site("reissue-refused");
            domain(siteId, "served." + ZONE);
            answerHttpChallenges();

            AcmeService.RequestOutcome initial = acme.requestCertificate(List.of("served." + ZONE),
                "Reissue refusals", null, CertificateAuthority.Requester.SYSTEM);
            assertThat(initial.issued()).as("precondition: there is a certificate to re-issue")
                .isTrue();
            int certId = initial.certificateId();
            Map<String, Object> before = snapshot(certModel.findById(certId));

            // 1. AN ADDED NAME THIS INSTALLATION DOES NOT SERVE is refused, even though the
            //    row's existing name is authorized -- authority is decided over the WHOLE
            //    new set, at re-issue time, never inherited from the stored row.
            assertThatThrownBy(() -> acme.reissueCertificate(certModel.findById(certId),
                    List.of("served." + ZONE, "unserved." + ZONE), null,
                    CertificateModel.CHALLENGE_HTTP, null,
                    CertificateAuthority.Requester.SYSTEM))
                .as("step 1: the unserved name is refused")
                .isInstanceOf(CertificateAuthority.Refused.class)
                .hasMessageContaining("unserved." + ZONE);
            assertThat(snapshot(certModel.findById(certId)))
                .as("step 1: a refused re-issue never touches the row either")
                .isEqualTo(before);

            // 2. MANUAL DNS cannot re-issue in place: that lane mints its own row by
            //    construction, so accepting it here would silently orphan this one.
            assertThatThrownBy(() -> acme.reissueCertificate(certModel.findById(certId),
                    List.of("served." + ZONE), null, CertificateModel.CHALLENGE_DNS,
                    CertificateModel.DNS_PUBLISHER_MANUAL,
                    CertificateAuthority.Requester.SYSTEM))
                .as("step 2: manual DNS-01 re-issue is refused")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Manual");

            // 3. A CUSTOM UPLOAD IS NOT AN ORDER. The row action hides itself for these and
            //    the handler refuses them; this is the layer that answers for both.
            Row uploaded = certModel.createEmptyRow();
            uploaded.set(CertificateModel.NICE_NAME, "Reissue uploaded");
            uploaded.set(CertificateModel.PROVIDER, CertificateModel.PROVIDER_CUSTOM);
            uploaded.set(CertificateModel.STATUS, CertificateModel.STATUS_ACTIVE);
            uploaded.set(CertificateModel.DOMAIN_NAMES_TEXT, "served." + ZONE);
            certModel.save(uploaded);
            assertThatThrownBy(() -> acme.reissueCertificate(uploaded, List.of("served." + ZONE),
                    null, CertificateModel.CHALLENGE_HTTP, null,
                    CertificateAuthority.Requester.SYSTEM))
                .as("step 3: a manual upload cannot be re-ordered")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Let's Encrypt");
        });
    }

    /**
     * The service's lane carries its two timers: the renewal sweep every six hours at a fixed rate, and a manual
     * DNS-01 order's expiry thirty minutes after it was prepared. Each fires here on the test's word, never after
     * real time passed.
     */
    @Test
    void theRenewalSweepAndTheManualDnsExpiryFireFromTheServiceLane() {
        Db.run(datasource, () -> {
            var certModel = Models.get(CertificateModel.class);
            RecordedTimers timers = new RecordedTimers();
            AcmeService timed = new AcmeService(new CertificateStore(), timers);
            ca.validateHttpWith((token, identifier) -> timed.getChallengeResponse(token, identifier) != null);
            int siteId = site("timer-owned");
            domain(siteId, "renewed." + ZONE);
            domain(siteId, "manual." + ZONE);
            try {
                // 1. Starting the service arms one sweep, repeating at a fixed rate every six hours.
                timed.start();
                assertThat(timers.armed()).as("step 1: the one renewal sweep")
                    .containsExactly(new Armed(TimeUnit.HOURS.toMillis(6), true));

                // 2. A certificate inside the renewal window is renewed when the sweep fires.
                AcmeService.RequestOutcome ordered = timed.requestCertificate(List.of("renewed." + ZONE),
                    "Timer renewal", null, CertificateAuthority.Requester.SYSTEM);
                assertThat(ordered.issued()).as("step 2: there is a certificate to renew").isTrue();
                int renewedId = ordered.certificateId();
                Row due = certModel.findById(renewedId);
                String firstPem = due.get(CertificateModel.CERTIFICATE_PEM);
                Instant dueOn = Now.instant().plus(1, ChronoUnit.DAYS);
                due.set(CertificateModel.EXPIRES_ON, dueOn);
                certModel.save(due);
                timers.fire(0);
                Row renewed = certModel.findById(renewedId);
                String renewedPem = renewed.get(CertificateModel.CERTIFICATE_PEM);
                Instant renewedUntil = renewed.get(CertificateModel.EXPIRES_ON);
                assertThat(renewedPem).as("step 2: the sweep renewed it").isNotEqualTo(firstPem);
                assertThat(renewedUntil).as("step 2: past its old expiry").isAfter(dueOn);

                // 3. Preparing a manual DNS-01 order arms its expiry thirty minutes out.
                AcmeService.ManualDnsRequest manual = prepareManual(timed, "Timer manual");
                assertThat(timers.armed()).as("step 3: the sweep and the order's expiry")
                    .containsExactly(new Armed(TimeUnit.HOURS.toMillis(6), true),
                        new Armed(TimeUnit.MINUTES.toMillis(30), false));
                assertThat(timed.manualDnsRequestFor(manual.certificateId())).as("step 3: the order waits")
                    .isNotNull();

                // 4. When the expiry fires the order is gone, its row says why, and the names are free again.
                timers.fire(1);
                assertThat(timed.manualDnsRequestFor(manual.certificateId())).as("step 4: the order expired")
                    .isNull();
                Row expired = certModel.findById(manual.certificateId());
                String status = expired.get(CertificateModel.STATUS);
                String reason = expired.get(CertificateModel.RENEWAL_ERROR);
                assertThat(status).as("step 4: the row failed").isEqualTo(CertificateModel.STATUS_ERROR);
                assertThat(reason).as("step 4: saying it expired").contains("Manual DNS challenge expired");
                assertThat(prepareManual(timed, "Timer manual again"))
                    .as("step 4: the expired order no longer holds its names").isNotNull();
            } finally {
                timed.stop();
            }
        });
    }

    // -- fixture plumbing -----------------------------------------------------

    /** A manual DNS-01 order for {@code manual.<ZONE>} that the fake CA is expected to prepare. */
    private static AcmeService.ManualDnsRequest prepareManual(AcmeService acme, String niceName) {
        try {
            return acme.prepareManualDnsCertificate(List.of("manual." + ZONE), niceName, null,
                CertificateAuthority.Requester.SYSTEM);
        } catch (Exception refused) {
            throw new AssertionError("the manual DNS-01 order could not be prepared", refused);
        }
    }

    /** One timer a {@link RecordedTimers} holds: its delay or period, and whether it repeats. */
    private record Armed(long delayMs, boolean fixedRate) {}

    /**
     * A lane that runs nothing by itself: it holds every timer armed on it, in arming order, until the test fires
     * one on its own thread. Only the timer entry points AcmeService uses exist; any other refuses.
     */
    private static final class RecordedTimers extends JobRunner {

        private final List<Armed> armed = new ArrayList<>();
        private final List<Runnable> jobs = new ArrayList<>();
        private boolean shutdown;

        RecordedTimers() {
            super("recorded-acme-timers");
        }

        synchronized List<Armed> armed() {
            return List.copyOf(this.armed);
        }

        /** Run the timer armed at this index once, as its due time would. */
        void fire(int index) {
            Runnable job;
            synchronized (this) {
                job = this.jobs.get(index);
            }
            job.run();
        }

        private synchronized ScheduledJob arm(Runnable job, long delayMs, boolean fixedRate) {
            this.armed.add(new Armed(delayMs, fixedRate));
            this.jobs.add(job);
            return () -> { };
        }

        @Override
        protected ScheduledJob doSchedule(Runnable bound, long delayMs) {
            return this.arm(bound, delayMs, false);
        }

        @Override
        protected ScheduledJob doScheduleAtFixedRate(Runnable bound, long periodMs) {
            return this.arm(bound, periodMs, true);
        }

        @Override
        protected ScheduledJob doScheduleRepeating(Runnable bound, long intervalMs) {
            throw new UnsupportedOperationException("AcmeService arms no fixed-delay timer");
        }

        @Override
        protected CompletableFuture<Void> doRunAsync(Runnable bound) {
            throw new UnsupportedOperationException("AcmeService queues no work on its lane");
        }

        @Override
        protected void doFireAndForget(Runnable bound) {
            throw new UnsupportedOperationException("AcmeService queues no work on its lane");
        }

        @Override
        protected Thread doStartThread(Runnable bound) {
            throw new UnsupportedOperationException("AcmeService starts no thread on its lane");
        }

        @Override
        protected Thread doStartBlockingThread(Runnable bound) {
            throw new UnsupportedOperationException("AcmeService starts no thread on its lane");
        }

        @Override
        public synchronized void shutdown() {
            this.shutdown = true;
        }

        @Override
        public void flushAndShutdown() {
            this.shutdown();
        }

        @Override
        public void shutdownNow() {
            this.shutdown();
        }

        @Override
        public synchronized boolean isShutdown() {
            return this.shutdown;
        }

        @Override
        public boolean awaitTermination(long timeoutMs) {
            return true;
        }

        @Override
        public boolean ownsCurrentThread() {
            return false;
        }
    }


    /** The CA validates by asking the product's own HTTP-01 responder. */
    private static void answerHttpChallenges() {
        ca.validateHttpWith((token, identifier) ->
            acme.getChallengeResponse(token, identifier) != null);
    }

    /** Every stored column of a certificate row, which is what "untouched" is asserted on. */
    private static Map<String, Object> snapshot(Row row) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String name : CertificateModel.SCHEMA.getFields().keySet()) {
            values.put(name, String.valueOf(row.get(name)));
        }
        return values;
    }

    private static int site(String slug) {
        Row site = Models.get(SiteModel.class).createEmptyRow();
        site.set(SiteModel.NAME, "ACME re-issue " + slug);
        site.set(SiteModel.SLUG, slug);
        site.set(SiteModel.UPSTREAM_KIND, "hohenheim:redirect");
        site.set(SiteModel.SETTINGS, Map.of("target", "https://example.com"));
        site.set(SiteModel.STATUS, "active");
        site.set(SiteModel.ENABLED, true);
        Models.get(SiteModel.class).save(site);
        return site.get(SiteModel.ID);
    }

    private static void domain(int siteId, String hostname) {
        Row domain = Models.get(SiteDomainModel.class).createEmptyRow();
        domain.set(SiteDomainModel.SITE_ID, siteId);
        domain.set(SiteDomainModel.HOSTNAME, hostname);
        domain.set(SiteDomainModel.MATCH_TYPE, hostname.startsWith("*.")
            ? "wildcard" : "exact");
        domain.set(SiteDomainModel.FORCE_SSL, false);
        Models.get(SiteDomainModel.class).save(domain);
    }

    private static X509Certificate leafOf(String pem) {
        try {
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(
                    pem.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception malformed) {
            throw new IllegalStateException("the stored chain is not a certificate", malformed);
        }
    }

    private static List<String> subjectAltNames(X509Certificate certificate) {
        List<String> names = new ArrayList<>();
        try {
            var alternatives = certificate.getSubjectAlternativeNames();
            if (alternatives != null) {
                for (List<?> entry : alternatives) {
                    if (entry.size() > 1) {
                        names.add(String.valueOf(entry.get(1)));
                    }
                }
            }
        } catch (Exception unreadable) {
            throw new IllegalStateException(unreadable);
        }
        return names;
    }
}
