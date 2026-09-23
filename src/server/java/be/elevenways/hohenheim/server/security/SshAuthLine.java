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
        if (line.contains("Invalid user ") || line.contains("Connection closed by invalid user")
                || line.contains("Connection reset by invalid user")) {
            return SecurityEventTypes.SSH_INVALID_USER;
        }
        if (line.contains("Connection closed by authenticating user")
                || line.contains("Connection reset by authenticating user")) {
            return SecurityEventTypes.SSH_PREAUTH_ABORT;
        }
        if (line.contains("banner exchange") && line.contains("invalid format")) {
            return SecurityEventTypes.SSH_PROTOCOL_ABUSE;
        }
        return null;
    }

    /**
     * The source address of a recognized line: the LAST {@code <literal> port <digits>}
     * triple on it, which covers both spellings sshd uses ("from IP port N" and the bare
     * "IP port N" of the connection-closed families).
     *
     * AIDEV-NOTE: anchored at the END on purpose. The username is attacker-controlled and
     * sshd logs it BEFORE the source ("Invalid user x from 1.2.3.4 port 1 from <real> port
     * N"), so the first literal, or the first "from" literal, is whatever the attacker typed
     * -- which turned the ban tier into a way to ban any address, including the operator's.
     * Everything sshd appends after the real source ("ssh2", "[preauth]", a key
     * fingerprint, ": invalid format") is its own text, so the last triple is the source.
     * There is deliberately no fallback to a bare literal: a line without the triple is
     * not trusted to name anyone.
     *
     * @return the literal v4 or v6 address, or null when the line carries no source triple
     */
    static @Nullable String extractIp(@NonNull String line) {
        String[] tokens = line.trim().split("\\s+");
        for (int i = tokens.length - 3; i >= 0; i--) {
            if (!"port".equals(tokens[i + 1]) || !isPortNumber(clean(tokens[i + 2]))) {
                continue;
            }
            String candidate = clean(tokens[i]);
            return candidate.isEmpty() || !IpLiterals.isLiteral(candidate) ? null : candidate;
        }
        return null;
    }

    /** Whether a token is a TCP port number as sshd prints one ("40222" or "40222:"). */
    private static boolean isPortNumber(@NonNull String raw) {
        String token = raw.endsWith(":") ? raw.substring(0, raw.length() - 1) : raw;
        if (token.isEmpty() || token.length() > 5) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return Integer.parseInt(token) <= 65535;
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
