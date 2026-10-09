package be.elevenways.hohenheim.server.security;

import be.elevenways.hohenheim.server.notification.Alerts;
import be.elevenways.hohenheim.server.notification.NotificationEvents;
import be.elevenways.protoblast.common.i18n.Microcopy;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Operator-notification seam for the security engine (default wiring is {@link #ALERTS}); exists so tests can record
 * instead of deliver.
 */
@FunctionalInterface
public interface SecurityNotifier {

    /** The production sink: every security alert is about the installation's own protection, one subject per event. */
    SecurityNotifier ALERTS = (event, subject, message) -> Alerts.send(event, Alerts.INSTALLATION, subject, message);

    void send(@NonNull NotificationEvents event, @NonNull Microcopy subject, @Nullable Microcopy message);
}
