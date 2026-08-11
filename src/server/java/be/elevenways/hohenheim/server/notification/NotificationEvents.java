package be.elevenways.hohenheim.server.notification;

import java.util.List;

/**
 * Event vocabulary for platform notifications. A channel with an empty
 * subscription list receives every event; otherwise only the listed ones.
 */
public final class NotificationEvents {

    public static final String CERT_RENEWAL_FAILED = "cert_renewal_failed";
    public static final String CERT_EXPIRING = "cert_expiring";
    public static final String DEPLOY_FAILED = "deploy_failed";
    public static final String BACKUP_FAILED = "backup_failed";
    public static final String PROCESS_CRASH_LOOP = "process_crash_loop";
    public static final String INSTANCE_CRASH_LOOP = "instance_crash_loop";
    // StackRuntime raises this on active -> degraded|failed; without it here a channel
    // with a subscription list could never route it (plan Phase 3 "fix in passing").
    public static final String STACK_HEALTH = "stack_health";
    /** HostProbe raises this the first time a host stops answering, never on every retry. */
    public static final String HOST_UNREACHABLE = "host_unreachable";
    public static final String AUTO_BAN_BUDGET_EXHAUSTED = "auto_ban_budget_exhausted";
    public static final String SPAMSERVICE_OUTAGE = "spamservice_outage";
    public static final String SPAMSERVICE_RECOVERED = "spamservice_recovered";
    /** ProxyServer supervision raises this ONCE per outage, after a supervised retry also failed. */
    public static final String PROXY_LISTENER_DOWN = "proxy_listener_down";

    public static final List<String> ALL = List.of(
        CERT_RENEWAL_FAILED, CERT_EXPIRING, DEPLOY_FAILED, BACKUP_FAILED, PROCESS_CRASH_LOOP,
        INSTANCE_CRASH_LOOP, STACK_HEALTH, HOST_UNREACHABLE, AUTO_BAN_BUDGET_EXHAUSTED,
        SPAMSERVICE_OUTAGE, SPAMSERVICE_RECOVERED, PROXY_LISTENER_DOWN);

    private NotificationEvents() {}

    public static boolean isKnown(String event) {
        return ALL.contains(event);
    }
}
