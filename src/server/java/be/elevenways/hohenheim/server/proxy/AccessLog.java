package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.protoblast.common.Blast;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.time.Instant;

/**
 * Proxy access logging: the optional combined-format access log and the fail2ban-style domain-miss
 * log. Both are best-effort -- a logging failure never breaks request handling.
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

                // Combined log format
                String line = clientIp + " - - [" + Instant.now() + "] \""
                    + method + " " + path + (query != null && !query.isEmpty() ? "?" + query : "")
                    + " " + ex.getProtocol() + "\" " + status + " " + size
                    + " \"" + (hostname != null ? hostname : "-") + "\""
                    + " \"" + (ua != null ? ua : "-") + "\"";

                appendToLogFile(logPath, line);
            } catch (Exception ignored) {
                // Don't let logging break request handling
            }
            next.proceed();
        });
    }

    /** Append a CRLF-stripped domain-miss line to the fail2ban log once the IP crosses the threshold. */
    public void logDomainMiss(String ip, String hostname, int misses) {
        boolean logEnabled = HohenheimSettings.VALUES.getValue(HohenheimSettings.Security.LOG_DOMAIN_MISSES);
        int threshold = HohenheimSettings.VALUES.getValue(HohenheimSettings.Security.DOMAIN_MISS_THRESHOLD);
        if (!logEnabled || misses < threshold) {
            return;
        }

        // Strip newlines to prevent log injection
        String safeHost = hostname.replace("\n", "").replace("\r", "");
        String safeIp = ip.replace("\n", "").replace("\r", "");
        String line = Instant.now() + " DOMAIN_MISS ip=" + safeIp + " domain=" + safeHost + " misses=" + misses;
        Blast.log(line);

        String logPath = HohenheimSettings.VALUES.getValue(HohenheimSettings.Security.DOMAIN_MISSES_LOG_PATH);
        if (logPath != null && !logPath.isEmpty()) {
            appendToLogFile(logPath, line);
        }
    }

    private static void appendToLogFile(String logPath, String line) {
        try {
            File logFile = new File(logPath);
            File parent = logFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();

            try (Writer writer = new BufferedWriter(new FileWriter(logFile, true))) {
                writer.write(line);
                writer.write('\n');
            }
        } catch (IOException ignored) {
            // Don't let log writing failures break request handling
        }
    }
}
