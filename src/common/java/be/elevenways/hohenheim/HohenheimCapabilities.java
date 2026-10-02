package be.elevenways.hohenheim;

/**
 * The instance capabilities common code names: the gates of the instance operations.
 *
 * AIDEV-NOTE: the {@link HohenheimSlugs} pattern. Common code (an operation's gate) needs the constant, so it lives
 * here and {@code HohenheimAccess} aliases it; the server vocabulary stays complete in {@code HohenheimAccess}.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class HohenheimCapabilities {

    /** Start, stop and restart the workload. */
    public static final String POWER = "power";

    /** Take and restore driver-level snapshots of an instance. */
    public static final String SNAPSHOTS = "snapshots";

    /** Export instance backups and restore them to new instances. */
    public static final String BACKUPS = "backups";

    /** Send console commands to the workload's primary process. */
    public static final String CONSOLE = "console";

    /** Open an interactive shell inside the workload: arbitrary programs, as the workload's user. */
    public static final String SHELL = "shell";

    /** Change what the workload runs: its configuration and the installed app. */
    public static final String CONFIG = "config";

    /** Ownership of a record: view, edit and operate together. */
    public static final String MANAGE = "manage";

    private HohenheimCapabilities() {
    }
}
