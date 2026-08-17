package be.elevenways.hohenheim.server.security;

import be.elevenways.hohenheim.server.notification.NotificationEvents;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Operator-notification seam for the security engine (default wiring is
 * {@code Alerts.send}); exists so tests can record instead of deliver.
 */
@FunctionalInterface
public interface SecurityNotifier {

    void send(@NonNull NotificationEvents event, @NonNull String subject, @Nullable String message);
}
