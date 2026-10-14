package be.elevenways.hohenheim.server.instance;

/**
 * How a record of a kind deploys the git source its settings carry: the one fact the scheduled check, the forge
 * webhook and the push deploy read instead of a list of kind names.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public enum GitSourceLane {

    /** The kind carries no git source. */
    NONE,

    /** A push builds an image in the sandbox and converges a new release (an application, a static site). */
    RELEASE,

    /** A push checks out and builds inside the record's own container (a workspace). */
    IN_PLACE;

    /** @return whether a record of the kind carries a git source at all */
    public boolean carriesSource() {
        return switch (this) {
            case RELEASE, IN_PLACE -> true;
            case NONE -> false;
        };
    }
}
