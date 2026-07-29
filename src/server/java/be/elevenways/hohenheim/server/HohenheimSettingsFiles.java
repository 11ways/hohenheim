package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.zenit.server.setting.DryFileSource;
import be.elevenways.zenit.server.setting.EnvSettingsSource;

import java.nio.file.Path;

/**
 * Loads Hohenheim's own settings context from its editable file plus
 * {@code HOHENHEIM__*} environment overrides (env wins per key).
 */
public final class HohenheimSettingsFiles {

    private HohenheimSettingsFiles() {
    }

    /**
     * The admin-editable settings file (the settings page persists here).
     * Overridable via {@code -Dhohenheim.settings} so tests never clobber
     * the developer's real file.
     */
    public static Path settingsFile() {
        return Path.of(System.getProperty("hohenheim.settings", "settings/hohenheim.dry"));
    }

    /**
     * Keys are RELATIVE to the {@code hohenheim} group root: the file keeps
     * the flat {@code proxy.http_port} shape, and
     * {@code HOHENHEIM__PROXY__HTTP_PORT} maps to the same setting.
     */
    public static void load() {
        forceDefinitions();
        HohenheimSettings.VALUES.loadFrom(
            new DryFileSource(settingsFile()),
            new EnvSettingsSource("HOHENHEIM"));
    }

    /**
     * Every nested settings class must define its groups BEFORE values load
     * (undefined keys are dropped at load time), and callers like the
     * migrate-only path run before the protoblast autoload loader is
     * guaranteed to have fired.
     */
    private static void forceDefinitions() {
        Object ignored;
        ignored = HohenheimSettings.Proxy.GROUP;
        ignored = HohenheimSettings.Ssl.GROUP;
        ignored = HohenheimSettings.Dns.GROUP;
        ignored = HohenheimSettings.Logging.GROUP;
        ignored = HohenheimSettings.Node.GROUP;
        ignored = HohenheimSettings.Storage.GROUP;
        ignored = HohenheimSettings.Database.GROUP;
        ignored = HohenheimSettings.Process.GROUP;
        ignored = HohenheimSettings.Security.GROUP;
        ignored = HohenheimSettings.AuthProteus.GROUP;
        ignored = HohenheimSettings.ProxyAuth.GROUP;
    }
}
