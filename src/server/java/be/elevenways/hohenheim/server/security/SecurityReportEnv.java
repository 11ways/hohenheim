package be.elevenways.hohenheim.server.security;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.thread.JobRunner;
import be.elevenways.hohenheim.server.spamservice.SpamserviceManager;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.security.SecureTokens;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Derives the ZENIT_SECURITY_REPORT_URL / ZENIT_SECURITY_REPORT_TOKEN
 * environment for a managed site's child processes: sites report their
 * security events DIRECTLY to Hohenheim's managed Spamservice runtime. The
 * site's client key is minted lazily, kept raw on the site row
 * ({@code sites.security_report_token}, it must be re-injected on every
 * spawn), and provisioned into spamservice as a trusted client on EVERY
 * injection -- the ensure call is idempotent and self-heals a runtime that
 * lost the client. A disabled manager means no injection; a configured manager
 * that is still starting supplies its desired endpoint so provisioning failures
 * keep the child configured while a
 * single retry chain per site repairs the remote registration.
 *
 * AIDEV-NOTE: this whole lane is INERT since c6bfab02 deleted the host-user process lane.
 * Nothing calls {@link #forSite} any more (only its own test does), and its private
 * key-minting is the ONLY writer of {@code sites.security_report_token}, so the still-wired
 * boot hook {@link #reconcilePersistedReporters} (SpamserviceManager) can only ever iterate
 * zero rows. Do not read the class as live plumbing: either a new spawner calls forSite, or
 * the lane is removed WHOLE -- this class, its test, SpamserviceManager's ReporterReconciler
 * seam, SiteModel.SECURITY_REPORT_TOKEN and its InitialMigration column, which is a schema
 * change on deployed installations and therefore a decision, not a cleanup.
 */
public final class SecurityReportEnv {

    public static final String URL_VAR = "ZENIT_SECURITY_REPORT_URL";
    public static final String TOKEN_VAR = "ZENIT_SECURITY_REPORT_TOKEN";

    /** The zenit-wire ingest route, appended to the spamservice base URL. */
    public static final String EVENTS_PATH = "/v1/events";
    public static final String HEALTH_CAPABILITY = "report provisioning";

    /** Provisioning seam: idempotently ensure a spamservice client for the key. */
    interface Provisioner {
        /** @throws RuntimeException when spamservice refuses or is unreachable */
        void ensure(@NonNull String externalId, @NonNull String name,
                    boolean trusted, @NonNull String rawKey);
    }

    /** Schedules one provisioning retry without blocking a child spawn. */
    interface RetryScheduler {
        void schedule(@NonNull Runnable task, long delayMs);
    }

    private static final Provisioner DEFAULT_PROVISIONER = SecurityReportEnv::ensureViaSpamservice;
    private static final JobRunner RETRIES = JobRunner.create("security-report-provisioning");
    private static final RetryScheduler DEFAULT_RETRY_SCHEDULER =
        (task, delayMs) -> RETRIES.schedule(task, delayMs);
    private static volatile Provisioner provisioner = DEFAULT_PROVISIONER;
    private static volatile RetryScheduler retryScheduler = DEFAULT_RETRY_SCHEDULER;
    private static volatile Supplier<String> baseUrlSource = () -> SpamserviceManager.get().reportingBaseUrl();
    private static final ConcurrentHashMap<Integer, Boolean> pendingRetries = new ConcurrentHashMap<>();
    private static final long INITIAL_RETRY_MS = 5_000;
    private static final long MAX_RETRY_MS = 60_000;

    private SecurityReportEnv() {
    }

    /** Fail-soft by design: a spawn must never die on reporter plumbing. */
    public static @NonNull Map<String, String> forSite(int siteId) {
        try {
            String base = baseUrlSource.get();
            if (base == null) {
                return Map.of();
            }

            SiteModel sites = Models.get(SiteModel.class);
            Row site = sites.find().where(SiteModel.ID.eq(siteId)).first();
            if (site == null) {
                return Map.of();
            }

            String raw = site.get(SiteModel.SECURITY_REPORT_TOKEN);
            if (raw == null || raw.isBlank()) {
                raw = assignCanonicalKey(siteId);
            }

            // Keep the child configured on failure: zenit's sink retains
            // batches, while the retry chain makes the key valid as soon as
            // spamservice returns.
            try {
                provisioner.ensure(SpamserviceManager.siteExternalId(siteId),
                    clientName(site, siteId), true, raw);
                SpamserviceHealth.active().recordSuccess(HEALTH_CAPABILITY);
            } catch (RuntimeException e) {
                SpamserviceHealth.active().recordFailure(HEALTH_CAPABILITY, e.getMessage());
                logThrottled(siteId, e.getMessage());
                scheduleRetry(siteId);
            }

            Map<String, String> env = new LinkedHashMap<>();
            env.put(URL_VAR, eventsUrl(base));
            env.put(TOKEN_VAR, raw);
            return env;
        } catch (RuntimeException e) {
            logThrottled(siteId, e.getMessage());
            return Map.of();
        }
    }

    static @NonNull String eventsUrl(@NonNull String base) {
        String trimmed = base.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + EVENTS_PATH;
    }

    /** The spamservice client name for a site: stable, slug-based. */
    static @NonNull String clientName(@NonNull Row site, int siteId) {
        String slug = site.get(SiteModel.SLUG);
        if (slug != null && !slug.isBlank()) {
            return "site:" + slug;
        }
        String name = site.get(SiteModel.NAME);
        if (name != null && !name.isBlank()) {
            return "site:" + name;
        }
        return "site:" + siteId;
    }

    private static void ensureViaSpamservice(@NonNull String externalId, @NonNull String name,
                                             boolean trusted, @NonNull String rawKey) {
        SpamserviceManager.get().requireClient()
            .ensureClient(externalId, name, trusted, rawKey);
    }

    private static @NonNull String assignCanonicalKey(int siteId) {
        String generated = SecureTokens.randomToken(32);
        // Targeted assign, not a full row save: the sites model is revisionable
        // and this bookkeeping write must not create revisions or clobber
        // concurrent admin edits.
        Models.get(SiteModel.class).find()
            .where(SiteModel.ID.eq(siteId))
            .assignIfNull(SiteModel.SECURITY_REPORT_TOKEN, generated)
            .bypassBehaviours()
            .updateAll();
        Row canonical = Models.get(SiteModel.class).find().where(SiteModel.ID.eq(siteId)).first();
        String raw = canonical != null ? canonical.get(SiteModel.SECURITY_REPORT_TOKEN) : null;
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("Could not persist the site's Spamservice reporting key");
        }
        return raw;
    }

    /** Re-provisions every persisted site reporter after a managed runtime generation becomes ready. */
    public static void reconcilePersistedReporters() {
        RETRIES.fireAndForget(() -> {
            for (Row site : Models.get(SiteModel.class).find()
                    .where(SiteModel.SECURITY_REPORT_TOKEN.isNotNull()).all()) {
                Integer siteId = site.get(SiteModel.ID);
                String raw = site.get(SiteModel.SECURITY_REPORT_TOKEN);
                if (siteId == null || raw == null || raw.isBlank()) {
                    continue;
                }
                try {
                    provisioner.ensure(SpamserviceManager.siteExternalId(siteId),
                        clientName(site, siteId), true, raw);
                    SpamserviceHealth.active().recordSuccess(HEALTH_CAPABILITY);
                } catch (RuntimeException e) {
                    SpamserviceHealth.active().recordFailure(HEALTH_CAPABILITY, e.getMessage());
                    logThrottled(siteId, e.getMessage());
                    scheduleRetry(siteId);
                }
            }
        });
    }

    private static void scheduleRetry(int siteId) {
        if (pendingRetries.putIfAbsent(siteId, Boolean.TRUE) == null) {
            scheduleAttempt(siteId, 1);
        }
    }

    private static void scheduleAttempt(int siteId, int attempt) {
        int shift = Math.min(Math.max(0, attempt - 1), 4);
        long delay = Math.min(MAX_RETRY_MS, INITIAL_RETRY_MS << shift);
        retryScheduler.schedule(() -> retryCurrentSite(siteId, attempt), delay);
    }

    private static void retryCurrentSite(int siteId, int attempt) {
        try {
            String base = baseUrlSource.get();
            if (base == null) {
                pendingRetries.remove(siteId);
                return;
            }

            Row site = Models.get(SiteModel.class).find().where(SiteModel.ID.eq(siteId)).first();
            if (site == null) {
                pendingRetries.remove(siteId);
                return;
            }
            String raw = site.get(SiteModel.SECURITY_REPORT_TOKEN);
            if (raw == null || raw.isBlank()) {
                pendingRetries.remove(siteId);
                return;
            }

            provisioner.ensure(SpamserviceManager.siteExternalId(siteId),
                clientName(site, siteId), true, raw);
            SpamserviceHealth.active().recordSuccess(HEALTH_CAPABILITY);
            pendingRetries.remove(siteId);
            Blast.log("SECURITY: spamservice report provisioning recovered for site", siteId);
        } catch (RuntimeException e) {
            SpamserviceHealth.active().recordFailure(HEALTH_CAPABILITY, e.getMessage());
            logThrottled(siteId, e.getMessage());
            scheduleAttempt(siteId, Math.min(attempt + 1, 5));
        }
    }

    // Per-site failure-log throttle so a crash-looping site cannot flood the log.
    private static final long FAILURE_LOG_THROTTLE_MS = 60_000;
    private static final ConcurrentHashMap<Integer, Long> failureLogTimes = new ConcurrentHashMap<>();

    private static void logThrottled(int siteId, @Nullable String message) {
        long now = System.currentTimeMillis();
        Long last = failureLogTimes.get(siteId);
        if (last != null && now - last < FAILURE_LOG_THROTTLE_MS) {
            return;
        }
        failureLogTimes.put(siteId, now);
        if (failureLogTimes.size() > 4096) {
            failureLogTimes.clear();
        }
        Blast.log("SECURITY: spamservice report provisioning deferred for site", siteId, "-", message);
    }

    /** Test seam: replace (null restores the real spamservice provisioner). */
    static void setProvisioner(@Nullable Provisioner replacement) {
        provisioner = replacement != null ? replacement : DEFAULT_PROVISIONER;
        failureLogTimes.clear();
    }

    /** Test seam: replace the delayed retry scheduler. */
    static void setRetryScheduler(@Nullable RetryScheduler replacement) {
        retryScheduler = replacement != null ? replacement : DEFAULT_RETRY_SCHEDULER;
        pendingRetries.clear();
    }

    /** Test seam: replace the managed runtime base URL source. */
    static void setBaseUrlSource(@Nullable Supplier<String> replacement) {
        baseUrlSource = replacement != null ? replacement : () -> SpamserviceManager.get().reportingBaseUrl();
    }
}
