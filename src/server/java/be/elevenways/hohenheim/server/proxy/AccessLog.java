package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.Zenit;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.Objects;

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
            Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Logging.ACCESS_TO_FILE));
        if (!logToFile) {
            return;
        }

        // The path the CLIENT asked for, captured now: strip_path rewrites the exchange's path
        // before the completion listener runs.
        RequestPath requestPath = RequestPath.of(exchange);
        String path = requestPath != null ? requestPath.raw() : RequestPath.rawPathOf(exchange);

        exchange.addExchangeCompleteListener((ex, next) -> {
            try {
                String logPath = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Logging.ACCESS_PATH);
                if (logPath == null || logPath.isEmpty()) { next.proceed(); return; }

                int status = ex.getStatusCode();
                String method = ex.getRequestMethod().toString();
                String query = ex.getQueryString();
                String ua = ex.getRequestHeaders().getFirst(Headers.USER_AGENT);
                long size = ex.getResponseBytesSent();

                // Combined log format. Every client-controlled field is escaped: backslash and
                // double-quote so a quoted field cannot end early, and every control character
                // so no field can start a forged line.
                String line = escape(clientIp) + " - - [" + Now.instant() + "] \""
                    + escape(method) + " " + escape(path)
                    + (query != null && !query.isEmpty() ? "?" + escape(query) : "")
                    + " " + escape(String.valueOf(ex.getProtocol())) + "\" " + status + " " + size
                    + " \"" + quote(hostname) + "\""
                    + " \"" + quote(ua) + "\"";

                append(Path.of(logPath), line);
            } catch (Exception ignored) {
                // Don't let logging break request handling
            }
            next.proceed();
        });
    }

    /** Escape a combined-format quoted field; null or empty becomes "-". */
    private static String quote(String value) {
        if (value == null || value.isEmpty()) {
            return "-";
        }
        return escape(value);
    }

    /**
     * Backslash, double-quote and every control character escaped.
     *
     * AIDEV-NOTE: the old comment claimed newline injection was unreachable because Undertow
     * ends header values at CR/LF. The PATH was logged decoded, where %0a IS a newline, so one
     * request could forge whole log lines. Escaping every field here is the rule that does not
     * depend on knowing which field can carry what.
     */
    static String escape(String value) {
        if (value == null) {
            return "-";
        }
        StringBuilder out = null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            String replacement = null;
            if (c == '\\') {
                replacement = "\\\\";
            } else if (c == '"') {
                replacement = "\\\"";
            } else if (c < 0x20 || c == 0x7f) {
                replacement = String.format(Locale.ROOT, "\\x%02x", (int) c);
            }
            if (replacement != null && out == null) {
                out = new StringBuilder(value.length() + 8).append(value, 0, i);
            }
            if (out != null) {
                if (replacement != null) {
                    out.append(replacement);
                } else {
                    out.append(c);
                }
            }
        }
        return out != null ? out.toString() : value;
    }

    // AIDEV-NOTE: ONE writer, kept open, serialized by WRITE_LOCK so concurrent completions
    // cannot interleave partial lines. It used to open and close a FileWriter per line.
    // Rotation-safe: before each line the file at the configured path is compared (by file key,
    // i.e. inode) with the one the writer holds, and a renamed or deleted file (logrotate) or a
    // changed setting reopens the path. Each line is flushed, so a crash loses nothing.
    private static final Object WRITE_LOCK = new Object();
    private static Writer writer;
    private static Path writerPath;
    private static Object writerFileKey;

    private static void append(Path logPath, String line) {
        synchronized (WRITE_LOCK) {
            try {
                Writer target = writerFor(logPath);
                target.write(line);
                target.write('\n');
                target.flush();
            } catch (IOException failure) {
                // Don't let log writing failures break request handling; reopen next time.
                closeWriter();
            }
        }
    }

    private static Writer writerFor(Path logPath) throws IOException {
        Object currentKey = fileKey(logPath);
        if (writer != null && logPath.equals(writerPath) && currentKey != null
                && Objects.equals(currentKey, writerFileKey)) {
            return writer;
        }
        closeWriter();
        Path parent = logPath.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
        writer = Files.newBufferedWriter(logPath, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        writerPath = logPath;
        writerFileKey = fileKey(logPath);
        return writer;
    }

    private static Object fileKey(Path path) {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class).fileKey();
        } catch (IOException missing) {
            return null;
        }
    }

    private static void closeWriter() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {
                // best effort
            }
        }
        writer = null;
        writerPath = null;
        writerFileKey = null;
    }
}
