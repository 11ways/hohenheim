package be.elevenways.hohenheim.server.security;

import be.elevenways.zenit.common.security.SecurityEventTypes;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The sshd journal line grammar: one message in, one weighted security event out, or
 * nothing.
 *
 * AIDEV-NOTE: a PURE function on purpose (no I/O, no state, no settings), because the
 * half of an SSH ban tier that can actually be wrong is the parsing -- a family that
 * silently stops matching after an sshd upgrade bans nobody and reports success.
 * {@link SshAuthWatcher} owns the process, this owns the words. Unmatched lines are
 * ignored SILENTLY: sshd's journal is mostly ordinary session chatter, and a log line
 * per unmatched line is a log flood, not a diagnosis.
 *
 * @author Jelle De Loecker
 * @since  0.3.0
 */
public final class SshAuthLine {

    /** One recognized authentication signal: the core event type plus its source address. */
    public record Signal(@NonNull String eventType, @NonNull String ip) {}

    private SshAuthLine() {
    }

    /**
     * @return the signal this line reports, or null when it is not an authentication
     *         failure this build recognizes or carries no literal source address
     */
    public static @Nullable Signal parse(@Nullable String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String type = classify(line);
        if (type == null) {
            return null;
        }
        String ip = extractIp(line);
        return ip == null ? null : new Signal(type, ip);
    }

    /**
     * The family ladder, most specific first: a "Failed password for invalid user" line is
     * a password failure, and sshd emits its own "Invalid user" line for the same attempt,
     * so a username sweep honestly scores twice.
     */
    private static @Nullable String classify(@NonNull String line) {
        if (line.contains("maximum authentication attempts exceeded")) {
            return SecurityEventTypes.SSH_MAX_ATTEMPTS;
        }
        if (line.contains("Failed password")) {
            return SecurityEventTypes.SSH_PASSWORD_FAILED;
        }
        if (line.contains("Failed publickey")) {
            return SecurityEventTypes.SSH_PUBLICKEY_FAILED;
        }
        if (line.contains("Invalid user ") || line.contains("Connection closed by invalid user")) {
            return SecurityEventTypes.SSH_INVALID_USER;
        }
        if (line.contains("Connection closed by authenticating user")) {
            return SecurityEventTypes.SSH_PREAUTH_ABORT;
        }
        if (line.contains("banner exchange") && line.contains("invalid format")) {
            return SecurityEventTypes.SSH_PROTOCOL_ABUSE;
        }
        return null;
    }

    /**
     * The source address of a recognized line, positionally rather than by family: sshd
     * spells it "from IP port N" in most families and bare "IP port N" in the
     * connection-closed ones, so both anchors are honoured before any fallback.
     *
     * @return the literal v4 or v6 address, or null when the line carries none
     */
    static @Nullable String extractIp(@NonNull String line) {
        String[] tokens = line.split("\\s+");
        String fallback = null;
        for (int i = 0; i < tokens.length; i++) {
            String candidate = clean(tokens[i]);
            if (candidate.isEmpty() || !IpLiterals.isLiteral(candidate)) {
                continue;
            }
            if (i > 0 && "from".equals(clean(tokens[i - 1]))) {
                return candidate;
            }
            if (i + 1 < tokens.length && "port".equals(clean(tokens[i + 1]))) {
                return candidate;
            }
            if (fallback == null) {
                fallback = candidate;
            }
        }
        return fallback;
    }

    /** Strip the punctuation sshd puts around a token ("from 1.2.3.4:" / "[1.2.3.4]"). */
    private static @NonNull String clean(@NonNull String token) {
        int start = 0;
        int end = token.length();
        while (start < end && isTrim(token.charAt(start))) {
            start++;
        }
        while (end > start && isTrim(token.charAt(end - 1))) {
            end--;
        }
        return token.substring(start, end);
    }

    private static boolean isTrim(char c) {
        return c == ',' || c == ';' || c == '[' || c == ']' || c == '(' || c == ')' || c == '.';
    }
}
