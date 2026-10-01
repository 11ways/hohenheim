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

    private HohenheimCapabilities() {
    }
}
