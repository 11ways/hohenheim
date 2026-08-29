package be.elevenways.hohenheim.server;

import be.elevenways.zenit.comms.CommsSettings;
import be.elevenways.zenit.server.setting.DryFileSource;
import be.elevenways.zenit.server.setting.EnvSettingsSource;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads zenit-comms' settings context from its own editable file plus {@code COMMS__*}
 * environment overrides (env wins per key).
 *
 * AIDEV-NOTE: {@code CommsSettings.VALUES} is a context of its OWN, rooted at the
 * {@code comms} group, exactly like zenit-auth's {@code settings/auth.dry}. A
 * {@code comms.channels.mail_transports} key written into {@code local.dry} lands in
 * the FRAMEWORK context, which the dispatcher never reads, so it is accepted and inert;
 * before this loader existed hohenheim had no way to configure a transport chain at all
 * (the 2026-08-29 hub coupling found it). Keys are RELATIVE to the {@code comms} group:
 * the file keeps the flat {@code channels.mail_transports} shape herald's does.
 */
public final class HohenheimCommsSettings {

    private HohenheimCommsSettings() {
    }

    /**
     * The admin-editable comms settings file (the settings page's comms mount persists
     * here); overridable via {@code -Dhohenheim.comms.settings} so tests never clobber
     * the developer's real file.
     */
    public static @NonNull Path settingsFile() {
        return Path.of(System.getProperty("hohenheim.comms.settings", "settings/comms.dry"));
    }

    /**
     * Loads the file (creating an empty one so the editor has something to persist to)
     * and the environment overlay; runs before the MODULES boot stage builds the dispatcher.
     */
    public static void load() {
        forceDefinitions();
        Path file = settingsFile();
        try {
            if (!Files.exists(file)) {
                Path parent = file.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(file, "{}\n");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create the comms settings file " + file, e);
        }
        CommsSettings.VALUES.loadFrom(new DryFileSource(file), new EnvSettingsSource("COMMS"));
    }

    /**
     * Define every nested comms group BEFORE values load; an undefined key is dropped at
     * load time, and the nested classes initialize only when something references them.
     */
    private static void forceDefinitions() {
        Object channels = CommsSettings.Channels.MAIL_TRANSPORTS;
        Object delivery = CommsSettings.Delivery.MAX_ATTEMPTS;
        Object hub = CommsSettings.Hub.ENABLED;
    }
}
