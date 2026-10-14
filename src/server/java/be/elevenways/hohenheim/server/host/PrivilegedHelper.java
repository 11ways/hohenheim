package be.elevenways.hohenheim.server.host;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * THE root surface of an unprivileged controller: the one helper {@code tools/install-host.sh}
 * installs and grants through sudoers, and the verbs the volume, Spamservice and nftables lanes
 * call it with.
 *
 * AIDEV-NOTE: this replaced {@code NOPASSWD: /usr/bin/chown, /usr/bin/chmod, /usr/bin/rm,
 * /usr/bin/mkdir, /usr/bin/btrfs} with no argument restriction, which was root for anyone who
 * could run a command as the service user ({@code chmod 4755 /bin/sh}). The helper validates
 * every path against the volume root (or the managed Spamservice root), refuses any symlink on
 * the way, pins its working directory to the verified parent before acting, and refuses a
 * volume owner below {@link #MIN_OWNER_UID}. The script itself lives in install-host.sh;
 * {@code PrivilegedHelperDriftTest} binds its path, its verbs and its uid floor to this class.
 * The nft verbs replaced a bare {@code NOPASSWD: /usr/sbin/nft}, whose {@code -f <file>} read
 * any file as root and loaded any ruleset: they take the ruleset on stdin only and refuse it
 * whole unless every command stays inside an {@code inet hohenheim_*} table.
 *
 * AIDEV-NOTE: every call site keeps the LEGACY command as a fallback for a host whose helper
 * does not answer the verb yet (the installer has not been re-run since the upgrade), so a new
 * controller keeps working on an old host until the installer swaps the broad sudoers line
 * for the narrow one -- which it does in the same run that installs the helper. The fallback is
 * decided per VERB ({@link #dispatchArm}), because an older helper is installed and executable
 * but answers a newer verb with its usage error.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class PrivilegedHelper {

    /** Where install-host.sh installs the helper; the sudoers grant names exactly this file. */
    public static final String PATH = "/usr/local/libexec/hohenheim/hohenheim-helper";

    /**
     * The lowest uid the helper hands a volume to: below it are the host's own accounts, and
     * a volume owned by one of them is a path into that account's files.
     */
    public static final int MIN_OWNER_UID = 100000;

    /** The helper's verbs; the token is the first argument it is called with. */
    public enum Verb {

        /** {@code <path>}: make the parent, then create the btrfs subvolume unless one exists. */
        VOLUME_CREATE("volume-create"),

        /** {@code <path> <bytes|none>}: enable quotas on the volume root, then limit the subvolume. */
        VOLUME_QUOTA("volume-quota"),

        /** {@code <path>}: print {@code btrfs qgroup show --raw -f} for the subvolume. */
        VOLUME_USAGE("volume-usage"),

        /** {@code <path> <target>}: a read-only snapshot under the volume root's snapshot tree. */
        VOLUME_SNAPSHOT("volume-snapshot"),

        /** {@code <path>}: delete the subvolume, or the plain directory a stray one left. */
        VOLUME_DESTROY("volume-destroy"),

        /** {@code <instance folder>}: remove it, and its snapshot-tree mirror, when they hold nothing. */
        VOLUME_PRUNE("volume-prune"),

        /** {@code <path> <uid>}: give the volume directory itself to the uid, mode 0700. */
        VOLUME_OWN("volume-own"),

        /** {@code <snapshot>}: write a tar of one snapshot-tree copy to stdout, numeric owners kept. */
        VOLUME_READ("volume-read"),

        /** {@code <path> <uid|keep>}: unpack a tar from stdin into the volume, then give every entry to the uid. */
        VOLUME_WRITE("volume-write"),

        /** {@code <path>}: give one managed Spamservice directory to the spamservice account. */
        SPAMSERVICE_OWN("spamservice-own"),

        /**
         * Apply the nft ruleset on stdin, one command per line, refused whole unless every line
         * adds, flushes or deletes a table, chain, set, rule or element of an {@code inet
         * hohenheim_*} table.
         */
        NFT_APPLY("nft-apply"),

        /** {@code table inet <t> | chain inet <t> <chain> | table bridge incus}: list one of them. */
        NFT_LIST("nft-list");

        private final String token;

        Verb(@NonNull String token) {
            this.token = token;
        }

        public @NonNull String token() {
            return this.token;
        }
    }

    private PrivilegedHelper() {
    }

    /**
     * The line of the helper's dispatch that answers one verb; an installed helper that lacks it
     * predates the verb (its installer was not re-run since the upgrade).
     */
    public static @NonNull String dispatchArm(@NonNull Verb verb) {
        return "    " + verb.token() + ")";
    }

    /** Whether the helper on the controller's own machine is installed and answers the verb. */
    public static boolean answersLocally(@NonNull Verb verb) {
        Path helper = Path.of(PATH);
        if (!Files.isExecutable(helper)) {
            return false;
        }
        try {
            return Files.readAllLines(helper).contains(dispatchArm(verb));
        } catch (IOException unreadable) {
            return false;
        }
    }

    /**
     * A shell snippet running one verb through {@code sudo -n} where the helper on the host the
     * snippet runs on answers it, and the legacy command otherwise.
     *
     * @param legacy the pre-helper script, already elevated with {@code sudo -n}
     * @param args   the verb's arguments, unquoted
     */
    public static @NonNull String snippet(@NonNull Verb verb, @NonNull String legacy,
                                          @NonNull String... args) {
        StringBuilder call = new StringBuilder("sudo -n ").append(HostShell.quote(PATH))
            .append(' ').append(verb.token());
        for (String arg : args) {
            call.append(' ').append(HostShell.quote(arg));
        }
        return "if [ -x " + HostShell.quote(PATH) + " ] && grep -qxF "
            + HostShell.quote(dispatchArm(verb)) + ' ' + HostShell.quote(PATH) + "; then " + call
            + "; else " + legacy + "; fi";
    }

    /** The argv of one verb through {@code sudo -n}, for a caller that spawns no shell. */
    public static @NonNull List<String> argv(@NonNull Verb verb, @NonNull String... args) {
        List<String> argv = new ArrayList<>(List.of("/usr/bin/sudo", "-n", "--", PATH, verb.token()));
        argv.addAll(List.of(args));
        return List.copyOf(argv);
    }
}
