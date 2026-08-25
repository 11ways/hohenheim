package be.elevenways.hohenheim.server.security;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.hohenheim.server.task.UpdateSystemIpAddresses;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.thread.JobRunner;
import be.elevenways.zenit.common.security.KnownSecurityEvents;
import be.elevenways.zenit.common.security.SecurityEventTypes;
import be.elevenways.zenit.server.security.SecurityEvent;
import be.elevenways.zenit.server.security.SecurityEvents;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.LinkedHashMap;
import java.util.Map;

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
     *
     * AIDEV-NOTE: only the ENFORCEMENT half (BanService: ban cache, nftables,
     * auto-bans) is behind roles.firewall. The event sink, the vocabulary and
     * the local-address discovery are observability every install wants -- a
     * DNS appliance still reports its security events to spamservice.
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
        if (HohenheimRoles.enabled(HohenheimRoles.Role.FIREWALL)) {
            BanService.INSTANCE.boot();
        } else {
            Blast.slog("hohenheim.role_disabled", java.util.Map.of(
                "role", HohenheimRoles.Role.FIREWALL.token(),
                "skipped", "ban enforcement (cache warmup, nftables, auto-bans)"));
        }
    }

    private static void onThresholdCrossed(String ip, String type, int score) {
        if (!HohenheimRoles.enabled(HohenheimRoles.Role.FIREWALL)) {
            return;   // scoring stays observability; the BAN is enforcement
        }
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

    /**
     * THE label of every security event type this application shows an operator, keyed by
     * the stored dotted type.
     *
     * AIDEV-NOTE: a MAP rather than a run of describe() calls, because it is the only
     * thing that can be drift-tested: {@code SecurityEventTypeLabelsTest} asserts it
     * covers {@link SecurityEventTypes#builtIns()} and that every key it names resolves
     * in en AND nl. A type described nowhere renders as its raw dotted token in the ban
     * list, which is the state the F6(c) finding reported for {@code proxy.domain_miss}.
     */
    static final Map<String, Microcopy> EVENT_LABELS = eventLabels();

    private static @NonNull Map<String, Microcopy> eventLabels() {
        Map<String, Microcopy> labels = new LinkedHashMap<>();
        labels.put(SecurityEventTypes.DOMAIN_MISS, label("domain_miss"));
        labels.put(SecurityEventTypes.AUTH_LOGIN_FAILED, label("login_failed"));
        labels.put(SecurityEventTypes.AUTH_LOGIN_SUCCEEDED, label("login_succeeded"));
        labels.put(SecurityEventTypes.AUTH_LOCKOUT, label("lockout"));
        labels.put(SecurityEventTypes.RATE_LIMITED, label("rate_limited"));
        labels.put(SecurityEventTypes.CSRF_FAILURE, label("csrf_failure"));
        labels.put(SecurityEventTypes.WS_ORIGIN_REFUSED, label("ws_origin_refused"));
        labels.put(SecurityEventTypes.WS_AUTH_REFUSED, label("ws_auth_refused"));
        return Map.copyOf(labels);
    }

    /** Describe the event types the admin surfaces display (labels resolve via microcopy). */
    private static void describeEventTypes() {
        KnownSecurityEvents.register(SecurityEventTypes.DOMAIN_MISS);
        for (Map.Entry<String, Microcopy> label : EVENT_LABELS.entrySet()) {
            KnownSecurityEvents.describe(label.getKey(), label.getValue());
        }
    }

    private static @NonNull Microcopy label(@NonNull String key) {
        return Microcopy.of(key).withFilter("scope", "security_event_type");
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
