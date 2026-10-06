package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.protoblast.common.time.Now;

/**
 * The fail2ban domain-miss log of the Node original, written beside the native security event; best-effort.
 *
 * AIDEV-NOTE: the line is the original's ({@code <iso> DOMAIN_MISS ip=.. domain=.. path=.. user_agent=".."}), so
 * an existing jail with {@code failregex = ^.*DOMAIN_MISS ip=<HOST> domain=.* path=.* user_agent=.*$} keeps
 * banning. The rewrite had stopped writing it, which silently disarmed such jails (parity inventory clause 19).
 * Only threshold-crossing misses reach it, the same gate the security event uses.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
final class DomainMissLog {

    private static final LogFile FILE = new LogFile();

    private DomainMissLog() {}

    /** Append one domain-miss line when the setting asks for the file. */
    static void record(String clientIp, String domain, String path, String userAgent) {
        if (!LogFile.enabled(HohenheimSettings.Logging.DOMAIN_MISSES_TO_FILE)) {
            return;
        }
        try {
            FILE.appendTo(HohenheimSettings.Logging.DOMAIN_MISSES_PATH, line(clientIp, domain, path, userAgent));
        } catch (RuntimeException ignored) {
            // Logging never breaks request handling.
        }
    }

    /** One domain-miss line; every client-controlled field is escaped (no spaces can split a key=value). */
    static String line(String clientIp, String domain, String path, String userAgent) {
        return Now.instant() + " DOMAIN_MISS ip=" + LogFile.escape(clientIp)
            + " domain=" + LogFile.escape(domain).replace(' ', '_')
            + " path=" + LogFile.escape(path).replace(' ', '_')
            + " user_agent=\"" + LogFile.escape(userAgent == null ? "" : userAgent) + "\"";
    }
}
