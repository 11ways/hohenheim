package be.elevenways.hohenheim.server.security;

import be.elevenways.protoblast.testsupport.time.Poll;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.spamservice.SpamserviceManager;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.protoblast.testsupport.thread.Racers;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.revision.RevisionModel;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

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
        site.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
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

        // A later save of the site writes a revision, and no revision snapshot holds the reporting key: it is a
        // credential, never a value a restore could put back.
        Row saved = Models.get(SiteModel.class).findById(siteId);
        saved.set(SiteModel.NAME, "Env Mint renamed");
        Models.get(SiteModel.class).save(saved);
        List<Row> revisions = new RevisionModel(Models.get(SiteModel.class).getResolvedDatasource()).find().noCache()
            .where(RevisionModel.MODEL.eq(SiteModel.MODEL_ID.toString()))
            .and(RevisionModel.RECORD_ID.eq(String.valueOf(siteId))).all();
        assertThat(revisions).as("the rename wrote a revision").isNotEmpty();
        assertThat(revisions).allSatisfy(revision -> assertThat(String.valueOf((Object) revision.get(
            RevisionModel.SNAPSHOT))).doesNotContain(key));
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
        // Eight first calls released together: every one answers the same persisted key.
        List<Map<String, String>> envs = Racers.race("eight first calls for one site's report key",
            Duration.ofSeconds(30), 8, slot -> SecurityReportEnv.forSite(siteId)).values();
        String canonicalKey = envs.getFirst().get(SecurityReportEnv.TOKEN_VAR);
        for (Map<String, String> env : envs) {
            assertThat(env.get(SecurityReportEnv.TOKEN_VAR)).isEqualTo(canonicalKey);
        }
        assertThat(Models.get(SiteModel.class).findById(siteId)
            .get(SiteModel.SECURITY_REPORT_TOKEN)).isEqualTo(canonicalKey);
        assertThat(ensured).allSatisfy(value -> assertThat(value).endsWith("|" + canonicalKey));
    }

    @Test
    void readyRuntimeReconcilesPersistedReportersWithoutRespawningSites() throws Exception {
        configureSpamservice();
        recordEnsures();
        int siteId = createSite("Env Reconcile");
        String key = SecurityReportEnv.forSite(siteId).get(SecurityReportEnv.TOKEN_VAR);
        ensured.clear();

        SecurityReportEnv.reconcilePersistedReporters();
        String expectedPrefix = SpamserviceManager.siteExternalId(siteId) + "|";
        Poll.until("the persisted reporter of the site is ensured", Duration.ofSeconds(5),
            Duration.ofMillis(20), () -> ensured.stream().anyMatch(value -> value.startsWith(expectedPrefix)));
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
            (event, subject, message) -> sent.add(event.token())));

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
