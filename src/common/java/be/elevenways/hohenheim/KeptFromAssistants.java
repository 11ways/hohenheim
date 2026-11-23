package be.elevenways.hohenheim;

/**
 * The reasons several of Hohenheim's operations and sources share for being kept from assistants' tools
 * ({@code notForAssistants}), each said once.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class KeptFromAssistants {

    /** An input zenit's tool schema cannot carry yet. */
    public static final String RELATION_PICK = "its form picks a record with a relation picker, which no tool input"
        + " projects yet; its tool comes once one does";

    /** A DNS record, which carries no code. */
    public static final String DNS_RECORD = "a DNS record has no code, as a never-reused number per record would be"
        + " wasted; its tool comes later, naming the record by its zone, name and type";

    /** A ban, which carries no code. */
    public static final String BAN = "a ban has no code, as a never-reused number per ban would be wasted; its tool"
        + " comes later, naming the ban by its address";

    /**
     * A background operation whose run another tenant could read: a run is read by every reader of its subject, and
     * this subject is read beyond its owner (a site, a backup or a dump states no readers of its own; a template is
     * every tenant's to choose).
     */
    public static final String RUN_READERS = "its run is read by every reader of its subject, and that subject is read"
        + " beyond its owner, so another tenant could read the run by its code; its tool comes once the subject states"
        + " who reads it";

    /** A deploy or a build, listed per app by its deploy list rather than across every app. */
    public static final String DEPLOY_HISTORY = "deploys and builds are listed per app, newest first, by the app's"
        + " deploy list, which names each by its code for a read";

    private KeptFromAssistants() {
    }
}
