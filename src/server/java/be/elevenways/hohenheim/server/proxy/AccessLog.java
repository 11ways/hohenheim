package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.HohenheimSettings;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.time.Instant;

/**
 * Proxy access logging: the optional combined-format access log. Best-effort --
 * a logging failure never breaks request handling. (Domain misses are no longer
 * logged to a fail2ban file: the native security engine records them as
 * security events and bans natively.)
 *
 * @author  Jelle De Loecker
 * @since   0.1.0
 */
public final class AccessLog {

    /** Register a completion listener that appends one combined-log line once the response is sent. */
    public void logAccess(HttpServerExchange exchange, String hostname, String clientIp) {
        boolean logToFile = Boolean.TRUE.equals(
            HohenheimSettings.VALUES.getValue(HohenheimSettings.Logging.ACCESS_TO_FILE));
        if (!logToFile) {
            return;
        }

        exchange.addExchangeCompleteListener((ex, next) -> {
            try {
                String logPath = HohenheimSettings.VALUES.getValue(HohenheimSettings.Logging.ACCESS_PATH);
                if (logPath == null || logPath.isEmpty()) { next.proceed(); return; }

                int status = ex.getStatusCode();
                String method = ex.getRequestMethod().toString();
                String path = ex.getRelativePath();
                String query = ex.getQueryString();
                String ua = ex.getRequestHeaders().getFirst(Headers.USER_AGENT);
                long size = ex.getResponseBytesSent();

                // Combined log format. Quoted fields carry client-controlled text (Host, User-Agent),
                // so escape backslash and double-quote or a crafted value would break every
                // combined-format parser reading the file. (Undertow already terminates header
                // values at CR/LF, so newline injection is not reachable here.)
                String line = clientIp + " - - [" + Instant.now() + "] \""
                    + method + " " + path + (query != null && !query.isEmpty() ? "?" + query : "")
                    + " " + ex.getProtocol() + "\" " + status + " " + size
                    + " \"" + quote(hostname) + "\""
                    + " \"" + quote(ua) + "\"";

                appendToLogFile(logPath, line);
            } catch (Exception ignored) {
                // Don't let logging break request handling
            }
            next.proceed();
        });
    }

    /** Escape a combined-format quoted field: backslash and double-quote only; null becomes "-". */
    private static String quote(String value) {
        if (value == null || value.isEmpty()) {
            return "-";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // AIDEV-NOTE: serialize appends so concurrent request completions cannot interleave partial
    // lines in the file. This still opens/closes a FileWriter per line -- a known perf limitation,
    // acceptable because the access log is opt-in (Logging.ACCESS_TO_FILE) and off by default; a
    // persistent writer would need lifecycle ownership tied to ProxyServer shutdown.
    private static final Object WRITE_LOCK = new Object();

    private static void appendToLogFile(String logPath, String line) {
        try {
            File logFile = new File(logPath);
            File parent = logFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();

            synchronized (WRITE_LOCK) {
                try (Writer writer = new BufferedWriter(new FileWriter(logFile, true))) {
                    writer.write(line);
                    writer.write('\n');
                }
            }
        } catch (IOException ignored) {
            // Don't let log writing failures break request handling
        }
    }
}
