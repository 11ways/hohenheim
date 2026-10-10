package be.elevenways.hohenheim;

/**
 * THE record capability vocabulary: every capability name a Hohenheim grant row stores.
 *
 * AIDEV-NOTE: the values are STORED grant data (plain subject, model, record, capability tuples), so a constant's
 * value never changes. Which model carries which name, what it implies and whether it delegates is declared in
 * {@code HohenheimGrantPolicy}; {@code HohenheimAccess} answers the checks and declares no capability names.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class HohenheimCapabilities {

    /** Ownership of a record: a site's only capability, and an instance's umbrella over the first five verbs. */
    public static final String MANAGE = "manage";

    /** Read a record's own state: DNS record fields, certificate status (never key material). */
    public static final String VIEW = "view";

    /** Author a DNS record inside the delegated type allow-list. */
    public static final String EDIT = "edit";

    /** Mint and hold a DNS record's dyndns update token. */
    public static final String DYNDNS = "dyndns";

    // AIDEV-NOTE: there is deliberately no `request` capability on CertificateModel.
    // One was registered here until 2026-08-13 and NOTHING ever read it: authority to
    // order a certificate is decided by NAME COVERAGE in CertificateAuthority.authorize
    // (every requested name must be covered by a live domain row of a site the caller
    // holds `manage` on), which is a different question from a per-certificate grant --
    // the certificate the grant would sit on does not exist yet when the request is
    // made. Because zenit-auth's RecordAccessPage draws one grant column per REGISTERED
    // capability, the registration alone put a `request` checkbox in front of operators
    // that granted nothing while reporting success. Do not re-add it without a reader.

    /** Attach to the workload's own primary process (console, framebuffer), deliberately not {@link #EXEC}. */
    public static final String CONSOLE = "console";

    /** Start, stop and restart the workload: ordinary, it changes runtime state and never content. */
    public static final String POWER = "power";

    /** Change what the workload runs (fields, devices, schedules, app update): elevated, near to running anything. */
    public static final String CONFIG = "config";

    /** Tear the workload down and trash the record: elevated, but delegable since it reaches one instance only. */
    public static final String DESTROY = "destroy";

    /** Run any command as any user in the workload: admin-only, never delegable or implied by {@link #MANAGE}. */
    public static final String EXEC = "exec";

    /**
     * Open an interactive shell inside the workload as its non-root user: elevated and delegable, unlike {@link #EXEC}.
     *
     * AIDEV-NOTE: deliberately NOT listed in any {@code impliedBy}, {@link #MANAGE}
     * included. Implication is retroactive -- it changes what every ALREADY-STORED grant
     * row means -- so folding a shell into the manage umbrella would silently hand an
     * interactive terminal to every existing manage holder. Same reasoning that keeps
     * the file, snapshot and backup verbs out of that umbrella; an operator grants this one
     * deliberately, on the record, or it is not held.
     */
    public static final String SHELL = "shell";

    /** Read a managed database's plaintext credentials: elevated and deliberately separate from {@link #VIEW}. */
    public static final String CREDENTIALS = "credentials";

    /** Take and restore driver-level snapshots of an instance (data-destructive on restore). */
    public static final String SNAPSHOTS = "snapshots";

    /** Export instance backups and restore them to new instances. */
    public static final String BACKUPS = "backups";

    /** Browse, read and download an instance's own volume files: ordinary, not implied by {@link #FILES_WRITE}. */
    public static final String FILES_READ = "files.read";

    /** Write, upload, rename, delete and mkdir in an instance's volumes: elevated, apart from {@link #FILES_READ}. */
    public static final String FILES_WRITE = "files.write";

    /** Run an arbitrary non-template image on an instance: exec-equivalent, so elevated and never delegable. */
    public static final String IMAGE_ANY = "image_any";

    private HohenheimCapabilities() {
    }
}
