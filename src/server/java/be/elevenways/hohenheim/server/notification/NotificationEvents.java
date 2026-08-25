package be.elevenways.hohenheim.server.notification;

import be.elevenways.protoblast.common.i18n.Microcopy;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Arrays;
import java.util.List;

/**
 * Event vocabulary for platform notifications. A channel with an empty subscription list
 * receives every event; otherwise only the listed ones.
 *
 * AIDEV-NOTE: this was a list of String constants PLUS a hand-maintained {@code ALL} that
 * had to repeat every one of them. Declaring the constant and forgetting the list entry
 * compiles, sends fine, and is invisible: the event simply never appears in the admin's
 * subscription picker, and {@code isKnown} then REJECTS it if anyone types it -- so a
 * channel with a subscription list can never route it. Members of an enum cannot be
 * omitted from {@code values()}, so a new event is now exactly one edit.
 */
public enum NotificationEvents {

    CERT_RENEWAL_FAILED("cert_renewal_failed"),
    CERT_EXPIRING("cert_expiring"),
    DEPLOY_FAILED("deploy_failed"),
    BACKUP_FAILED("backup_failed"),
    PROCESS_CRASH_LOOP("process_crash_loop"),
    INSTANCE_CRASH_LOOP("instance_crash_loop"),
    /**
     * StackRuntime raises this on active to degraded|failed; without it here a channel
     * with a subscription list could never route it (plan Phase 3 "fix in passing").
     */
    STACK_HEALTH("stack_health"),
    /** HostProbe raises this the first time a host stops answering, never on every retry. */
    HOST_UNREACHABLE("host_unreachable"),
    AUTO_BAN_BUDGET_EXHAUSTED("auto_ban_budget_exhausted"),
    SPAMSERVICE_OUTAGE("spamservice_outage"),
    SPAMSERVICE_RECOVERED("spamservice_recovered"),
    /** ProxyServer supervision raises this ONCE per outage, after a supervised retry also failed. */
    PROXY_LISTENER_DOWN("proxy_listener_down"),
    /**
     * Either isolation sweep found a workload it had to CONTAIN, could not repair, or could
     * not confirm. One event for both tiers: an operator subscribes to "my tenants may not
     * be isolated from each other", not to a per-runtime sweep.
     */
    WORKLOAD_ISOLATION("workload_isolation");

    /** Every declared event token, in declaration order; DERIVED, never hand-listed. */
    public static final List<String> ALL =
        Arrays.stream(values()).map(NotificationEvents::token).toList();

    private final String token;

    NotificationEvents(String token) {
        this.token = token;
    }

    /** The stored subscription token, and the key the alert notification is identified by. */
    public @NonNull String token() {
        return this.token;
    }

    /** The subscription checkbox label for this event. */
    public @NonNull Microcopy label() {
        return Microcopy.of(this.token).withFilter("scope", "notification_event");
    }

    public static boolean isKnown(@Nullable String event) {
        return ALL.contains(event);
    }

    /**
     * The declared member behind a STORED token, so a surface that reads subscriptions
     * back out of the database can reach the member's own label instead of printing the
     * token. Fails closed: a token this build does not declare has no member and its
     * reader must degrade rather than invent a label.
     *
     * @return the member, or null when the token is not part of this vocabulary
     */
    public static @Nullable NotificationEvents byToken(@Nullable String token) {
        for (NotificationEvents event : values()) {
            if (event.token.equals(token)) {
                return event;
            }
        }
        return null;
    }
}
