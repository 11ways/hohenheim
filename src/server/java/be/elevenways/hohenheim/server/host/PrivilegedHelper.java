package be.elevenways.hohenheim.server.host;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * THE root surface of an unprivileged controller: the one helper {@code tools/install-host.sh}
 * installs and grants through sudoers, and the verbs the volume and Spamservice lanes call it with.
 *
 * AIDEV-NOTE: this replaced {@code NOPASSWD: /usr/bin/chown, /usr/bin/chmod, /usr/bin/rm,
 * /usr/bin/mkdir, /usr/bin/btrfs} with no argument restriction, which was root for anyone who
 * could run a command as the service user ({@code chmod 4755 /bin/sh}). The helper validates
 * every path against the volume root (or the managed Spamservice root), refuses any symlink on
 * the way, pins its working directory to the verified parent before acting, and refuses a
 * volume owner below {@link #MIN_OWNER_UID}. The script itself lives in install-host.sh;
 * {@code PrivilegedHelperDriftTest} binds its path, its verbs and its uid floor to this class.
 *
 * AIDEV-NOTE: every call site keeps the LEGACY command as a fallback for a host whose helper
 * is not installed yet (the installer has not been re-run since the upgrade), so a new
 * controller keeps working on an old host until the installer swaps the broad sudoers line
 * for the narrow one -- which it does in the same run that installs the helper.
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

        /** {@code <path> <uid>}: give the volume directory itself to the uid, mode 0700. */
        VOLUME_OWN("volume-own"),

        /** {@code <path>}: give one managed Spamservice directory to the spamservice account. */
        SPAMSERVICE_OWN("spamservice-own");

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

    /** Whether the helper is installed on the controller's own machine. */
    public static boolean installedLocally() {
        return Files.isExecutable(Path.of(PATH));
    }

    /**
     * A shell snippet running one verb through {@code sudo -n} where the helper is installed on
     * the host the snippet runs on, and the legacy command otherwise.
     *
     * @param legacy the pre-helper script, already elevated with {@code sudo -n}
     * @param args   the verb's arguments, unquoted
     */
    static @NonNull String snippet(@NonNull Verb verb, @NonNull String legacy,
                                   @NonNull String... args) {
        StringBuilder call = new StringBuilder("sudo -n ").append(HostShell.quote(PATH))
            .append(' ').append(verb.token());
        for (String arg : args) {
            call.append(' ').append(HostShell.quote(arg));
        }
        return "if [ -x " + HostShell.quote(PATH) + " ]; then " + call + "; else " + legacy + "; fi";
    }

    /** The argv of one verb through {@code sudo -n}, for a caller that spawns no shell. */
    public static @NonNull List<String> argv(@NonNull Verb verb, @NonNull String... args) {
        List<String> argv = new ArrayList<>(List.of("/usr/bin/sudo", "-n", "--", PATH, verb.token()));
        argv.addAll(List.of(args));
        return List.copyOf(argv);
    }
}
