package be.elevenways.hohenheim.server.security;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimViolations;
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
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

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
            // The sshd tail is ENFORCEMENT-tier too: it exists to produce bans, and a
            // node that does not enforce them has no reason to read another daemon's log.
            if (SshAuthWatcher.isConfigured()) {
                SshAuthWatcher.INSTANCE.start();
            }
        } else {
            Blast.slog("hohenheim.role_disabled", java.util.Map.of(
                "role", HohenheimRoles.Role.FIREWALL.token(),
                "skipped", "ban enforcement (cache warmup, nftables, auto-bans)"));
        }
    }

    /**
     * The ban's reason is what tipped it, in words, resolved in the installation's content locale like every stored
     * reason ("Tried 26 names this server does not serve"), never the score that only the scorer can read.
     */
    private static void onThresholdCrossed(String ip, String type, int score, int events) {
        if (!HohenheimRoles.enabled(HohenheimRoles.Role.FIREWALL)) {
            return;   // scoring stays observability; the BAN is enforcement
        }
        BanService.INSTANCE.autoBan(ip, type, HohenheimViolations.textOf(causeOf(type, events)));
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
    static final Map<String, Microcopy> EVENT_LABELS;

    /** The scope of the ban causes. */
    private static final String CAUSE_SCOPE = "ban_cause";

    /**
     * What tipped an automatic ban for each type, by the same copy key as its label: a sentence counting the
     * events ("Tried {$count} names this server does not serve"). Drift-tested beside the labels.
     */
    static final Map<String, Microcopy> EVENT_CAUSES;

    static {
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put(SecurityEventTypes.DOMAIN_MISS, "domain_miss");
        keys.put(SecurityEventTypes.AUTH_LOGIN_FAILED, "login_failed");
        keys.put(SecurityEventTypes.AUTH_LOGIN_SUCCEEDED, "login_succeeded");
        keys.put(SecurityEventTypes.AUTH_LOCKOUT, "lockout");
        keys.put(SecurityEventTypes.RATE_LIMITED, "rate_limited");
        keys.put(SecurityEventTypes.CSRF_FAILURE, "csrf_failure");
        keys.put(SecurityEventTypes.PERMISSION_DENIED, "permission_denied");
        keys.put(SecurityEventTypes.WS_ORIGIN_REFUSED, "ws_origin_refused");
        keys.put(SecurityEventTypes.WS_AUTH_REFUSED, "ws_auth_refused");
        keys.put(SecurityEventTypes.SSH_INVALID_USER, "ssh_invalid_user");
        keys.put(SecurityEventTypes.SSH_PASSWORD_FAILED, "ssh_password_failed");
        keys.put(SecurityEventTypes.SSH_PUBLICKEY_FAILED, "ssh_publickey_failed");
        keys.put(SecurityEventTypes.SSH_PREAUTH_ABORT, "ssh_preauth_abort");
        keys.put(SecurityEventTypes.SSH_MAX_ATTEMPTS, "ssh_max_attempts");
        keys.put(SecurityEventTypes.SSH_PROTOCOL_ABUSE, "ssh_protocol_abuse");
        Map<String, Microcopy> labels = new LinkedHashMap<>();
        Map<String, Microcopy> causes = new LinkedHashMap<>();
        keys.forEach((type, key) -> {
            labels.put(type, label(key));
            causes.put(type, Microcopy.of(key).withFilter("scope", CAUSE_SCOPE));
        });
        EVENT_LABELS = Map.copyOf(labels);
        EVENT_CAUSES = Map.copyOf(causes);
    }

    /**
     * What tipped an automatic ban, in words: how many events of this type the actor set off inside the scoring
     * window. A type this application describes nowhere names its own dotted spelling.
     */
    static @NonNull Microcopy causeOf(@Nullable String type, int events) {
        Microcopy cause = type == null ? null : EVENT_CAUSES.get(type);
        if (cause != null) {
            return cause.withArg("count", events);
        }
        return Microcopy.of("other_event").withFilter("scope", CAUSE_SCOPE).withArg("count", events)
            .withArg("event", labelOf(type));
    }

    /** @return the event type in words: this application's label, else a description registered for it, else itself */
    private static @NonNull Microcopy labelOf(@Nullable String type) {
        Microcopy own = type == null ? null : EVENT_LABELS.get(type);
        Microcopy described = own != null || type == null ? own : KnownSecurityEvents.descriptionOf(type);
        return described != null ? described : Microcopy.literal(String.valueOf(type));
    }

    /**
     * The reason automatic bans stored before the reason named its cause, which only the scorer could read.
     *
     * AIDEV-NOTE: such rows still exist (an auto ban's history outlives its expiry); {@link #legacyCause} reads
     * them as their event type in words. Nothing writes this shape any more.
     */
    private static final Pattern LEGACY_REASON = Pattern.compile("score \\d+ over threshold");

    /** @return a stored automatic-ban reason in words when it is the legacy score line, null when it is not */
    public static @Nullable Microcopy legacyCause(@Nullable String reason, @Nullable String type) {
        if (reason == null || !LEGACY_REASON.matcher(reason).matches()) {
            return null;
        }
        return Microcopy.of("legacy").withFilter("scope", CAUSE_SCOPE).withArg("event", labelOf(type));
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
            scorer.recordEvent(event.remoteIp(), event.type(), event.detail());
        }
    }
}
