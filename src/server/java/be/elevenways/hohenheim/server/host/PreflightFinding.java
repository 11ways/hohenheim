package be.elevenways.hohenheim.server.host;

import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.protoblast.common.i18n.Microcopy;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * THE vocabulary of what a preflight check found: every detail a battery stores is one of these, worded by the
 * catalog entry {@code finding_<token>} (scope {@code host_check}) beside the check's name and fix.
 *
 * AIDEV-NOTE: the token is the member's name in lower case and is STORED on each check entry (with its arguments),
 * so renaming a member makes rows written before read as their stored text, never as another finding. The arguments
 * are the probe's evidence (a version, a uid map, an error message) and travel verbatim, except the argument named
 * {@value #FAILURE_ARG}, which is a {@link HostProbe.FailureKind} token and reads as that failure's words.
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
    ACL_REFUSED;

    /** The stored check entry's key holding the finding's token. */
    public static final String TOKEN_KEY = "finding";

    /** The stored check entry's key holding the finding's arguments. */
    public static final String ARGS_KEY = "finding_args";

    /** The argument that is a probe failure token, read as that failure's words. */
    public static final String FAILURE_ARG = "failure";

    /** @return the token stored on a check entry */
    public @NonNull String token() {
        return this.name().toLowerCase(Locale.ROOT);
    }

    /** @return this finding's catalog entry, without its arguments */
    public @NonNull Microcopy copy() {
        return Microcopy.of("finding_" + this.token()).withFilter("scope", "host_check");
    }

    /**
     * @param args name/value pairs, in that order
     * @return this finding with its evidence
     */
    public @NonNull Found with(@NonNull Object... args) {
        Map<String, String> named = new LinkedHashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            named.put(String.valueOf(args[i]), String.valueOf(args[i + 1]));
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
        Map<String, String> named = new LinkedHashMap<>();
        if (args instanceof Map<?, ?> map) {
            map.forEach((name, value) -> named.put(String.valueOf(name), String.valueOf(value)));
        }
        return new Found(finding, named).words();
    }

    /**
     * One finding with the evidence it was found on.
     *
     * @param args the evidence by argument name, verbatim
     */
    public record Found(@NonNull PreflightFinding finding, @NonNull Map<String, String> args) {

        public Found {
            args = Collections.unmodifiableMap(new LinkedHashMap<>(args));
        }

        /** @return the finding in words, its evidence filled in */
        public @NonNull Microcopy words() {
            Microcopy words = this.finding.copy();
            for (Map.Entry<String, String> arg : this.args.entrySet()) {
                words = words.withArg(arg.getKey(), FAILURE_ARG.equals(arg.getKey())
                    ? HostProbe.FailureKind.labelOf(arg.getValue()) : arg.getValue());
            }
            return words;
        }

        /** @return the finding in the installation's default content locale, the text a stored detail carries */
        public @NonNull String text() {
            return HohenheimViolations.textOf(this.words());
        }
    }
}
