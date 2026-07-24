package be.elevenways.hohenheim.server.security;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.task.UpdateSystemIpAddresses;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.thread.JobRunner;
import be.elevenways.zenit.common.security.KnownSecurityEvents;
import be.elevenways.zenit.common.security.SecurityEventTypes;
import be.elevenways.zenit.server.security.SecurityEvent;
import be.elevenways.zenit.server.security.SecurityEvents;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Boot wiring for the native security engine: the process-wide
 * {@link ThreatScorer} (fed by the proxy dispatcher and the in-process
 * {@link SecurityEvents} sink) and its auto-ban hookup into
 * {@link BanService}. Event ANALYTICS live in spamservice: Hohenheim's own
 * events travel there through the sink installed by {@code SpamserviceManager};
 * nothing is stored locally.
 */
public final class HohenheimSecurity {

    private static final ThreatScorer SCORER = new ThreatScorer();
    private static volatile boolean booted = false;

    static {
        SCORER.setAutoBanTrigger(HohenheimSecurity::onThresholdCrossed);
    }

    private HohenheimSecurity() {
    }

    /** The shared threat scorer feeding automatic bans. */
    public static @NonNull ThreatScorer scorer() {
        return SCORER;
    }

    /**
     * Idempotent boot: install the local event sink, describe the event-type
     * vocabulary, make sure the own-IP ban guard has addresses, and bring
     * nftables plus the ban cache in line with the database.
     */
    public static synchronized void boot() {
        if (!booted) {
            booted = true;
            SecurityEvents.addSink(HohenheimSecurity::acceptLocalEvent);
            HohenheimSettings.Security.NEVER_BAN.addChangeListener((context, next, previous) ->
                JobRunner.startVirtualThread(NeverBanHostnames.INSTANCE::refresh));
            describeEventTypes();
        }
        ensureLocalAddresses();
        BanService.INSTANCE.boot();
    }

    private static void onThresholdCrossed(String ip, String type, int score) {
        BanService.INSTANCE.autoBan(ip, type, "score " + score + " over threshold");
    }

    /**
     * The own-public-IP ban guard reads {@code UpdateSystemIpAddresses}; its
     * scheduled boot run is asynchronous, so populate the list synchronously
     * BEFORE enforcement starts (an empty list would leave the server's own
     * address bannable at the first request).
     */
    private static void ensureLocalAddresses() {
        if (!UpdateSystemIpAddresses.getLocalAddresses().isEmpty()) {
            return;
        }
        try {
            UpdateSystemIpAddresses.discover();
        } catch (RuntimeException e) {
            Blast.log("SECURITY: local-address discovery failed at boot -", e.getMessage());
        }
    }

    /** Describe the event types the admin surfaces display (labels resolve via microcopy). */
    private static void describeEventTypes() {
        KnownSecurityEvents.register(SecurityEventTypes.DOMAIN_MISS);
        describe(SecurityEventTypes.DOMAIN_MISS, "domain_miss");
        describe(SecurityEventTypes.AUTH_LOGIN_FAILED, "login_failed");
        describe(SecurityEventTypes.AUTH_LOCKOUT, "lockout");
        describe(SecurityEventTypes.RATE_LIMITED, "rate_limited");
        describe(SecurityEventTypes.CSRF_FAILURE, "csrf_failure");
        describe(SecurityEventTypes.WS_ORIGIN_REFUSED, "ws_origin_refused");
        describe(SecurityEventTypes.WS_AUTH_REFUSED, "ws_auth_refused");
    }

    private static void describe(@NonNull String type, @NonNull String key) {
        KnownSecurityEvents.describe(type,
            Microcopy.of(key).withFilter("scope", "security_event_type"));
    }

    /**
     * The in-process sink: events this JVM reports through the core funnel
     * feed the local scorer for immediate banning (storage/analytics is
     * spamservice's job via the core remote sink). NON-domain-miss types
     * only -- the dispatcher already scores domain misses directly, and
     * double-feeding would double their weight.
     */
    private static void acceptLocalEvent(@NonNull SecurityEvent event) {
        acceptLocalEvent(event, SCORER);
    }

    /** Testable local-scoring gate: positive signals remain analytics-only. */
    static void acceptLocalEvent(@NonNull SecurityEvent event, @NonNull ThreatScorer scorer) {
        // Only literal IPs are scoreable ("local" and friends are never bannable).
        if (!SecurityEventTypes.DOMAIN_MISS.equals(event.type())
                && !SecurityEventClassification.isPositive(event.type())
                && IpLiterals.isLiteral(event.remoteIp())) {
            scorer.recordEvent(event.remoteIp(), event.type(), 1);
        }
    }
}
