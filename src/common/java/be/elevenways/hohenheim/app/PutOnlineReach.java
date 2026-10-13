package be.elevenways.hohenheim.app;

/**
 * How what is put online is reached from outside, which decides the steps "Put something online" asks: a website an
 * address and HTTPS, a service its published ports, a machine neither.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public enum PutOnlineReach {

    /** Visitors type an address; Hohenheim's proxy serves it, with a certificate. */
    WEBSITE,

    /** Clients connect to ports published on the host (a game server, any service that does not speak HTTP). */
    PORTS,

    /** A machine reached on its own network; neither an address nor ports are asked when it is put online. */
    NONE;

    /** Whether putting it online asks for an address and its HTTPS. */
    public boolean asksAddress() {
        return switch (this) {
            case WEBSITE -> true;
            case PORTS, NONE -> false;
        };
    }

    /** Whether putting it online asks which host ports its declared ports are published on. */
    public boolean asksPorts() {
        return switch (this) {
            case PORTS -> true;
            case WEBSITE, NONE -> false;
        };
    }
}
