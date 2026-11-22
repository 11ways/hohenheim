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

    /** An operation that runs in the background, which zenit-ai's tools cannot place yet. */
    public static final String BACKGROUND_RUN = "it runs in the background, and an assistant's tool places only an"
        + " operation that answers inside its request; its tool comes once tools place runs";

    private KeptFromAssistants() {
    }
}
