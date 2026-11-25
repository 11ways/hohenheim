package be.elevenways.hohenheim.server.proxy;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One line of the access log as {@link AccessLog} writes it, read back.
 *
 * AIDEV-NOTE: lines written before the time taken and the site were added carry neither; such a line belongs to a
 * site by its hostname alone (AccessLogReader). The client-controlled fields stay as LogFile escaped them except the
 * two escapes a reader would trip over ({@code \"} and {@code \\}): a control character keeps its {@code \xNN}.
 *
 * @param at        when the response was sent
 * @param host      the hostname the request asked for
 * @param visitor   the visitor's address
 * @param path      the path the client sent, with its query
 * @param status    the HTTP status answered
 * @param bytes     the body bytes sent
 * @param referrer  the Referer, null when none was sent
 * @param userAgent the User-Agent, null when none was sent
 * @param millis    the milliseconds the request took, null on a line from before they were logged
 * @param siteId    the site whose route answered, null on a line from before it was logged
 * @param raw       the line as written
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public record AccessLogLine(@NonNull Instant at, @NonNull String host, @NonNull String visitor,
                            @NonNull String method, @NonNull String path, @NonNull String protocol, int status,
                            long bytes, @Nullable String referrer, @Nullable String userAgent, @Nullable Long millis,
                            @Nullable Integer siteId, @NonNull String raw) {

    private static final String QUOTED = "\"((?:[^\"\\\\]|\\\\.)*)\"";

    private static final Pattern LINE = Pattern.compile("^(\\S+): (\\S+) - - \\[([^\\]]+)\\] \"(\\S+) (\\S+) (\\S+)\" "
        + "(\\d{3}) (\\d+) " + QUOTED + " " + QUOTED + "(?: " + AccessLog.TIME_FIELD + "=(\\d+))?(?: "
        + AccessLog.SITE_FIELD + "=(\\d+))?$");

    /** @return the line read, or null when it is no access-log line (a torn last line, another program's text) */
    public static @Nullable AccessLogLine parse(@NonNull String raw) {
        Matcher matched = LINE.matcher(raw);
        if (!matched.matches()) {
            return null;
        }
        try {
            Instant at = OffsetDateTime.parse(matched.group(3), AccessLog.TIMESTAMP).toInstant();
            return new AccessLogLine(at, matched.group(1), matched.group(2), matched.group(4), matched.group(5),
                matched.group(6), Integer.parseInt(matched.group(7)), Long.parseLong(matched.group(8)),
                quoted(matched.group(9)), quoted(matched.group(10)),
                matched.group(11) == null ? null : Long.valueOf(matched.group(11)),
                matched.group(12) == null ? null : Integer.valueOf(matched.group(12)), raw);
        } catch (DateTimeParseException | NumberFormatException unreadable) {
            return null;
        }
    }

    /** @return when a line was written, without reading the rest of it; null when it is no access-log line */
    static @Nullable Instant timeOf(@NonNull String raw) {
        int open = raw.indexOf(" - - [");
        int close = open < 0 ? -1 : raw.indexOf(']', open);
        if (close < 0) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw.substring(open + 6, close), AccessLog.TIMESTAMP).toInstant();
        } catch (DateTimeParseException unreadable) {
            return null;
        }
    }

    /** @return a quoted field's value with its quote and backslash escapes undone, null for the "-" of none */
    private static @Nullable String quoted(@NonNull String field) {
        if (field.equals("-")) {
            return null;
        }
        if (field.indexOf('\\') < 0) {
            return field;
        }
        StringBuilder out = new StringBuilder(field.length());
        for (int i = 0; i < field.length(); i++) {
            char c = field.charAt(i);
            char next = i + 1 < field.length() ? field.charAt(i + 1) : 0;
            if (c == '\\' && (next == '"' || next == '\\')) {
                out.append(next);
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
