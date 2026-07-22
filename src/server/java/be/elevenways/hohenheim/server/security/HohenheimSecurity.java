package be.elevenways.hohenheim.server.security;

import be.elevenways.hohenheim.server.task.UpdateSystemIpAddresses;
import be.elevenways.hohenheim.server.util.Json;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.security.KnownSecurityEvents;
import be.elevenways.zenit.common.security.SecurityEventTypes;
import be.elevenways.zenit.server.security.SecurityEvent;
import be.elevenways.zenit.server.security.SecurityEvents;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.time.Instant;

/**
 * Boot wiring for the native security engine: the process-wide
 * {@link ThreatScorer} (shared by the proxy dispatcher and the ingest
 * endpoint), its auto-ban hookup into {@link BanService} (budgeted per
 * reporter), and the in-process {@link SecurityEvents} sink that lands
 * Hohenheim's OWN events in the same analytics table remote reporters feed
 * (reporter_id null).
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
            describeEventTypes();
        }
        ensureLocalAddresses();
        BanService.INSTANCE.boot();
    }

    private static void onThresholdCrossed(String ip, String type, int score, String reporter) {
        if (reporter != null && !ReporterBanBudget.tryConsume(reporter)) {
            // Budget exhaustion already slogged loudly by the budget itself.
            return;
        }
        BanService.INSTANCE.autoBan(ip, type, score, reporter);
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
     * The in-process sink: every event this JVM reports through the core
     * funnel is upserted with reporter_id null. Only NON-domain-miss types
     * additionally feed the scorer -- the dispatcher already scores domain
     * misses directly, and double-feeding would double their weight.
     */
    private static void acceptLocalEvent(@NonNull SecurityEvent event) {
        Instant at = Instant.ofEpochMilli(event.timestampMs());
        String detail = event.detail() != null ? Json.stringify(event.detail()) : null;
        SecurityEventStore.record(null, event.type(), event.remoteIp(), 1, at, at, detail);

        // Only literal IPs are scoreable ("local" and friends stay analytics-only).
        if (!SecurityEventTypes.DOMAIN_MISS.equals(event.type())
                && IpLiterals.isLiteral(event.remoteIp())) {
            SCORER.recordEvent(event.remoteIp(), event.type(), 1);
        }
    }
}
