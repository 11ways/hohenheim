package be.elevenways.hohenheim.server.security;

import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.spamservice.SpamserviceManager;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-site spamservice env injection: lazy key minting on the site row,
 * idempotent re-provisioning on every injection, background recovery after
 * provisioning errors, and no injection while spamservice is unconfigured. The ensure call
 * is stubbed through the Provisioner seam -- no HTTP.
 */
class SecurityReportEnvTest {

    private static boolean initialized = false;

    /** One recorded ensure call: "name|trusted|rawKey". */
    private final List<String> ensured = Collections.synchronizedList(new ArrayList<>());
    private final List<Runnable> retries = Collections.synchronizedList(new ArrayList<>());

    @BeforeAll
    static void initDb() throws Exception {
        if (initialized) return;
        initialized = true;
        TestDatabases.freshDatabase();
        HohenheimTestRuntime.ensureBooted();
    }

    @AfterEach
    void reset() {
        SecurityReportEnv.setProvisioner(null);
        SecurityReportEnv.setRetryScheduler(null);
        SecurityReportEnv.setBaseUrlSource(null);
        SpamserviceHealth.setActive(null);
    }

    private void configureSpamservice() {
        SecurityReportEnv.setBaseUrlSource(() -> "https://spam.example.com/");
    }

    private void recordEnsures() {
        SecurityReportEnv.setProvisioner((externalId, name, trusted, rawKey) ->
            ensured.add(externalId + "|" + name + "|" + trusted + "|" + rawKey));
    }

    private void captureRetries() {
        SecurityReportEnv.setRetryScheduler((task, delayMs) -> retries.add(task));
    }

    private int createSite(String name) {
        SiteModel sites = Models.get(SiteModel.class);
        Row site = sites.createEmptyRow();
        site.set(SiteModel.NAME, name);
        site.set(SiteModel.SLUG, name.toLowerCase().replaceAll("[^a-z0-9]+", "-"));
        site.set(SiteModel.SITE_TYPE, "hohenheim:dead");
        site.set(SiteModel.ENABLED, true);
        sites.save(site);
        return site.get(SiteModel.ID);
    }

    @Test
    void noSpamserviceUrlMeansNoInjectionAndNoProvisioning() {
        recordEnsures();
        int siteId = createSite("Env No Base");
        assertThat(SecurityReportEnv.forSite(siteId)).isEmpty();
        assertThat(ensured).isEmpty();
        // And no key was minted onto the site row either.
        Row site = Models.get(SiteModel.class).findById(siteId);
        assertThat(site.get(SiteModel.SECURITY_REPORT_TOKEN)).isNull();
    }

    @Test
    void firstCallMintsAKeyProvisionsItAndInjectsTheEventsUrl() {
        configureSpamservice();
        recordEnsures();
        int siteId = createSite("Env Mint");

        Map<String, String> env = SecurityReportEnv.forSite(siteId);
        assertThat(env.get(SecurityReportEnv.URL_VAR))
            .isEqualTo("https://spam.example.com/v1/events");
        String key = env.get(SecurityReportEnv.TOKEN_VAR);
        assertThat(key).isNotBlank();

        // The raw key is kept on the site row for the next spawn.
        Row site = Models.get(SiteModel.class).findById(siteId);
        assertThat(site.get(SiteModel.SECURITY_REPORT_TOKEN)).isEqualTo(key);

        // And it was provisioned as a TRUSTED client named after the site slug.
        assertThat(ensured).containsExactly("hohenheim:site:" + siteId
            + "|site:env-mint|true|" + key);
    }

    @Test
    void repeatCallsReuseTheKeyAndReEnsureEveryTime() {
        configureSpamservice();
        recordEnsures();
        int siteId = createSite("Env Reuse");

        Map<String, String> first = SecurityReportEnv.forSite(siteId);
        Map<String, String> second = SecurityReportEnv.forSite(siteId);
        assertThat(second).isEqualTo(first);

        // Idempotent ensure runs on EVERY injection (self-heals a wiped spamservice).
        assertThat(ensured).hasSize(2);
        assertThat(ensured.get(0)).isEqualTo(ensured.get(1));
    }

    @Test
    void concurrentFirstCallsConvergeOnOnePersistedKey() throws Exception {
        configureSpamservice();
        recordEnsures();
        int siteId = createSite("Env Concurrent");
        var executor = Executors.newFixedThreadPool(8);
        try {
            List<java.util.concurrent.Future<Map<String, String>>> calls = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                calls.add(executor.submit(() -> SecurityReportEnv.forSite(siteId)));
            }
            String canonical = null;
            for (var call : calls) {
                String key = call.get(5, TimeUnit.SECONDS).get(SecurityReportEnv.TOKEN_VAR);
                if (canonical == null) canonical = key;
                assertThat(key).isEqualTo(canonical);
            }
            String canonicalKey = canonical;
            assertThat(Models.get(SiteModel.class).findById(siteId)
                .get(SiteModel.SECURITY_REPORT_TOKEN)).isEqualTo(canonicalKey);
            assertThat(ensured).allSatisfy(value -> assertThat(value).endsWith("|" + canonicalKey));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void readyRuntimeReconcilesPersistedReportersWithoutRespawningSites() throws Exception {
        configureSpamservice();
        recordEnsures();
        int siteId = createSite("Env Reconcile");
        String key = SecurityReportEnv.forSite(siteId).get(SecurityReportEnv.TOKEN_VAR);
        ensured.clear();

        SecurityReportEnv.reconcilePersistedReporters();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        String expectedPrefix = SpamserviceManager.siteExternalId(siteId) + "|";
        while (ensured.stream().noneMatch(value -> value.startsWith(expectedPrefix))
                && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(ensured).anySatisfy(value -> assertThat(value)
            .startsWith(expectedPrefix).endsWith("|" + key));
    }

    @Test
    void provisioningFailureInjectsAndRetriesWithoutBlocking() {
        configureSpamservice();
        captureRetries();
        SecurityReportEnv.setProvisioner((externalId, name, trusted, rawKey) -> {
            throw new IllegalStateException("spamservice unreachable");
        });
        int siteId = createSite("Env Fail Soft");

        Map<String, String> initial = SecurityReportEnv.forSite(siteId);
        assertThat(initial.get(SecurityReportEnv.URL_VAR))
            .isEqualTo("https://spam.example.com/v1/events");
        assertThat(initial.get(SecurityReportEnv.TOKEN_VAR)).isNotBlank();
        assertThat(SecurityReportEnv.forSite(siteId)).isEqualTo(initial);
        assertThat(retries).hasSize(1);

        // After spamservice recovers, the queued retry provisions the SAME minted key.
        Row before = Models.get(SiteModel.class).findById(siteId);
        String minted = before.get(SiteModel.SECURITY_REPORT_TOKEN);
        assertThat(minted).isNotBlank();

        retries.remove(0).run();
        assertThat(retries).hasSize(1);

        recordEnsures();
        retries.remove(0).run();
        assertThat(ensured).containsExactly("hohenheim:site:" + siteId
            + "|site:env-fail-soft|true|" + minted);
    }

    @Test
    void provisioningOutcomesFeedTheOutageDetector() {
        configureSpamservice();
        captureRetries();
        java.util.concurrent.atomic.AtomicLong now =
            new java.util.concurrent.atomic.AtomicLong(1_000_000_000L);
        List<String> sent = new ArrayList<>();
        SpamserviceHealth.setActive(new SpamserviceHealth(now::get,
            (event, subject, message) -> sent.add(event)));

        SecurityReportEnv.setProvisioner((externalId, name, trusted, rawKey) -> {
            throw new IllegalStateException("spamservice unreachable");
        });
        int siteId = createSite("Env Outage");

        // Sustained provisioning failure (>= 5 spanning >= 5 min) notifies ONCE.
        for (int i = 0; i < 8; i++) {
            SecurityReportEnv.forSite(siteId);
            now.addAndGet(90_000);
        }
        assertThat(sent).containsExactly("spamservice_outage");

        // The first success notifies recovery ONCE.
        recordEnsures();
        SecurityReportEnv.forSite(siteId);
        SecurityReportEnv.forSite(siteId);
        assertThat(sent).containsExactly("spamservice_outage", "spamservice_recovered");
    }

    @Test
    void unknownSiteInjectsNothing() {
        configureSpamservice();
        recordEnsures();
        assertThat(SecurityReportEnv.forSite(999_999)).isEmpty();
        assertThat(ensured).isEmpty();
    }
}
