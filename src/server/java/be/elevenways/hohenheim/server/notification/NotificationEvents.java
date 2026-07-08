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

    public static final List<String> ALL = List.of(
        CERT_RENEWAL_FAILED, CERT_EXPIRING, DEPLOY_FAILED, BACKUP_FAILED, PROCESS_CRASH_LOOP);

    private NotificationEvents() {}

    public static boolean isKnown(String event) {
        return ALL.contains(event);
    }
}
