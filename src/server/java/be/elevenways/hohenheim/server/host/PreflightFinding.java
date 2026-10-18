package be.elevenways.hohenheim.server.host;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.protoblast.common.i18n.Microcopy;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * THE vocabulary of what a preflight check found: every detail a battery stores is one of these, worded by the
 * catalog entry {@code finding_<token>} (scope {@code host_check}) beside the check's name and fix.
 *
 * AIDEV-NOTE: the token is the member's name in lower case and is STORED on each check entry (with its arguments),
 * so renaming a member makes rows written before read as their stored text, never as another finding. The arguments
 * are the probe's evidence and are stored typed: a count or a size as its whole number (the catalog words it, a size
 * through {@code :bytes}), everything else as text (a version, a uid map, an error message), except the argument
 * named {@value #FAILURE_ARG}, which is a {@link HostProbe.FailureKind} token and reads as that failure's words. A row
 * that stored an argument named in {@link #NUMBER_ARGS} as text (written before evidence was typed) reads as its
 * stored detail, never as a number guessed from the text.
 * HostCheckAndAdmitJourneyTest binds every member to copy in en and nl, so a new finding fails the build until it is
 * worded.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public enum PreflightFinding {

    DAEMON_UNREACHABLE,
    DOCKER_REACHABLE,
    INCUS_REACHABLE,
    API_VERSION,
    API_VERSION_UNREADABLE,
    PROBE_IMAGE_UNOBTAINABLE,
    KERNEL_PROBE_EXEC_FAILED,
    KERNEL_PROBE_ANSWERED,
    PROBE_CONTAINER_FAILED,
    PROBE_INSTANCE_FAILED,
    PROBE_UNANSWERED,
    USERNS_UNREADABLE,
    USERNS_REMAPPED,
    USERNS_IDENTITY,
    USERNS_IDENTITY_INCUS,
    LSM_CONFINED,
    LSM_NONE,
    LSM_UNCONFINED,
    PIDS_DELEGATED,
    PIDS_NOT_DELEGATED,
    PIDS_LIMIT,
    SECCOMP_FILTERING,
    SECCOMP_OFF,
    NO_NEW_PRIVS_SET,
    NO_NEW_PRIVS_UNSET,
    NETWORK_HEADROOM,
    NETWORK_HEADROOM_LOW,
    NETWORK_EXHAUSTED,
    NFT_APPLIED,
    NFT_REFUSED,
    NFT_NOT_READ_BACK,
    NFT_PROBE_FAILED,
    KERNEL_LANE_NO_SSH,
    KERNEL_LANE_UNBUILDABLE,
    RESOURCES_MEASURED,
    RESOURCES_NO_MEMORY,
    RESOURCES_UNREADABLE,
    TRUSTED,
    NOT_TRUSTED,
    DRIVERS,
    DRIVERS_NO_LXC,
    STORAGE_POOLS,
    STORAGE_NONE_CREATED,
    STORAGE_UNLISTED,
    BRIDGE_PRESENT,
    BRIDGE_MISSING,
    NETWORKS_UNLISTED,
    ACL_ENFORCEABLE,
    ACL_NOT_READ_BACK,
    ACL_REFUSED,
    LAN_PARENT_ADMITTED,
    LAN_PARENT_ABSENT,
    LAN_PARENT_MANAGED,
    LAN_PARENT_UNSUPPORTED,
    LAN_PARENT_DOWN,
    LAN_PARENT_UNREADABLE;

    /** The stored check entry's key holding the finding's token. */
    public static final String TOKEN_KEY = "finding";

    /** The stored check entry's key holding the finding's arguments. */
    public static final String ARGS_KEY = "finding_args";

    /** The argument that is a probe failure token, read as that failure's words. */
    public static final String FAILURE_ARG = "failure";

    /** The arguments that are whole numbers (a count, a size in bytes), which the catalog words in the reader's form. */
    public static final Set<String> NUMBER_ARGS = Set.of("count", "cpus", "memory");

    /** @return the token stored on a check entry */
    public @NonNull String token() {
        return this.name().toLowerCase(Locale.ROOT);
    }

    /** @return this finding's catalog entry, without its arguments */
    public @NonNull Microcopy copy() {
        return HohenheimMicrocopy.HOST_CHECK.of("finding_" + this.token());
    }

    /**
     * @param args name/value pairs, in that order; a value under a {@link #NUMBER_ARGS} name is its whole number, or
     *             null when the probe did not measure it (the argument is then left out)
     * @return this finding with its evidence
     * @throws IllegalArgumentException when a number argument is not a whole number
     */
    public @NonNull Found with(@NonNull Object... args) {
        Map<String, Object> named = new LinkedHashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            String name = String.valueOf(args[i]);
            Object value = args[i + 1];
            if (NUMBER_ARGS.contains(name)) {
                if (value == null) {
                    continue;
                }
                if (!(value instanceof Long || value instanceof Integer)) {
                    throw new IllegalArgumentException("Evidence '" + name + "' of " + this
                        + " is a whole number, got " + value);
                }
                named.put(name, ((Number) value).longValue());
            } else {
                named.put(name, String.valueOf(value));
            }
        }
        return new Found(this, named);
    }

    /** @return the member stored as this token, or null for a token this build does not know */
    public static @Nullable PreflightFinding ofToken(@Nullable Object token) {
        for (PreflightFinding finding : values()) {
            if (finding.token().equals(token)) {
                return finding;
            }
        }
        return null;
    }

    /**
     * A stored check's detail in words.
     *
     * @param token  the stored finding token, null on a hand-written or pre-finding entry
     * @param args   the stored arguments
     * @param stored the stored detail text, read verbatim when the token names no finding this build declares
     */
    public static @NonNull Microcopy wordsOf(@Nullable Object token, @Nullable Object args, @NonNull String stored) {
        PreflightFinding finding = ofToken(token);
        if (finding == null) {
            return Microcopy.literal(stored);
        }
        Map<String, Object> named = new LinkedHashMap<>();
        if (args instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> arg : map.entrySet()) {
                String name = String.valueOf(arg.getKey());
                if (!NUMBER_ARGS.contains(name)) {
                    named.put(name, String.valueOf(arg.getValue()));
                } else if (arg.getValue() instanceof Number number) {
                    named.put(name, number.longValue());
                } else {
                    // Stored before evidence was typed: its detail text is what it said.
                    return Microcopy.literal(stored);
                }
            }
        }
        return new Found(finding, named).words();
    }

    /**
     * One finding with the evidence it was found on.
     *
     * @param args the evidence by argument name: a whole number under a {@link #NUMBER_ARGS} name, else text
     */
    public record Found(@NonNull PreflightFinding finding, @NonNull Map<String, Object> args) {

        public Found {
            args = Collections.unmodifiableMap(new LinkedHashMap<>(args));
        }

        /** @return the finding in words, its evidence filled in */
        public @NonNull Microcopy words() {
            Microcopy words = this.finding.copy();
            for (Map.Entry<String, Object> arg : this.args.entrySet()) {
                String name = arg.getKey();
                String text = String.valueOf(arg.getValue());
                words = FAILURE_ARG.equals(name) ? words.withArg(name, HostProbe.FailureKind.labelOf(text))
                    : arg.getValue() instanceof Number number ? words.withArg(name, number)
                    : words.withArg(name, text);
            }
            return words;
        }

        /** @return the finding in the installation's default content locale, the text a stored detail carries */
        public @NonNull String text() {
            return HohenheimViolations.textOf(this.words());
        }
    }
}
