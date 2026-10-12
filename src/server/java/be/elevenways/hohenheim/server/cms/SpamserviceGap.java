package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.server.spamservice.SpamserviceManager;
import be.elevenways.protoblast.common.i18n.Microcopy;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * What keeps the local Spamservice from answering its management API, each gap worded for the lifecycle verbs and for
 * the settings page.
 *
 * AIDEV-NOTE: the members are declared in the order the installation climbs them (configured, enabled, running,
 * connected), and {@link #of} answers the FIRST missing step; {@link #within} reads that order.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
enum SpamserviceGap {

    NOT_CONFIGURED("not_configured", "settings_not_configured"),
    NOT_ENABLED("not_enabled", "settings_not_enabled"),
    NOT_RUNNING("not_running", "settings_not_running"),
    NOT_CONNECTED("not_connected", "settings_not_connected");

    private final String verbKey;
    private final String settingsKey;

    SpamserviceGap(@NonNull String verbKey, @NonNull String settingsKey) {
        this.verbKey = verbKey;
        this.settingsKey = settingsKey;
    }

    /** @return the first step the installation misses, or null when its management API is connected */
    static @Nullable SpamserviceGap of(@NonNull SpamserviceManager manager) {
        SpamserviceManager.Snapshot snapshot = manager.snapshot();
        if (!snapshot.configured()) {
            return NOT_CONFIGURED;
        }
        if (!snapshot.enabled()) {
            return NOT_ENABLED;
        }
        if (snapshot.pid() == null) {
            return NOT_RUNNING;
        }
        return manager.client() == null ? NOT_CONNECTED : null;
    }

    /** @return whether this gap is {@code last} or a step before it */
    boolean within(@NonNull SpamserviceGap last) {
        return compareTo(last) <= 0;
    }

    /** Why a lifecycle verb cannot run, as its disabled control says it. */
    @NonNull Microcopy verbReason() {
        return HohenheimMicrocopy.SPAMSERVICE.of(this.verbKey);
    }

    /** Why the settings page cannot read the service's settings, and where to fix it. */
    @NonNull Microcopy settingsReason() {
        return HohenheimMicrocopy.SPAMSERVICE.of(this.settingsKey);
    }
}
