package be.elevenways.hohenheim.server.traffic;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.field.StorageKeyed;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * THE vocabulary of what an app's outgoing connection went to, as Hohenheim knows it without a lookup per packet:
 * every member carries the key a destination key stores, whether it stays inside the server, and its words.
 *
 * AIDEV-NOTE: the kind is decided when the counters are read (AppDestinations) and stored in the destination's key,
 * so a later reading of the totals names it as it was then, never as whatever holds that address today. A key of a
 * kind this build does not know reads as its address alone.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public enum DestinationKind implements StorageKeyed {

    /** Any other address, named by the address alone. */
    INTERNET("internet", false),
    /** This server's own proxy, on a web port of its own address. */
    PROXY("proxy", false),
    /** A stream site of this server (its mail server's submission port, for one), on its own address. */
    STREAM("stream", false),
    /** Another port of this server's own address. */
    SERVER("server", false),
    /** Another app of this server, by its container's address. */
    APP("app", false),
    /** The app's own containers: another release or service of it. */
    OWN("own", true),
    /** The app's own database link. */
    DATABASE("database", true);

    private final @NonNull String key;
    private final boolean internal;

    DestinationKind(@NonNull String key, boolean internal) {
        this.key = key;
        this.internal = internal;
    }

    @Override
    public @NonNull String storageKey() {
        return this.key;
    }

    /** @return whether the traffic never leaves the server: internal, shown as its own line, no destination */
    public boolean internal() {
        return this.internal;
    }

    /** @return the line under a destination's name that says what it is */
    public @NonNull Microcopy note() {
        return HohenheimMicrocopy.APP_NETWORK.of(switch (this) {
            case INTERNET -> "destination_internet";
            case PROXY -> "destination_proxy";
            case STREAM -> "destination_stream";
            case SERVER -> "destination_server";
            case APP -> "destination_app";
            case OWN -> "destination_own";
            case DATABASE -> "destination_database";
        });
    }

    /** @return what a destination of this kind is called when it names no record the reader may view */
    public @NonNull Microcopy unnamed() {
        return HohenheimMicrocopy.APP_NETWORK.of(switch (this) {
            case INTERNET -> "destination_internet_unnamed";
            case PROXY -> "destination_proxy_unnamed";
            case STREAM -> "destination_stream_unnamed";
            case SERVER -> "destination_server_unnamed";
            case APP -> "destination_app_unnamed";
            case OWN -> "destination_own_unnamed";
            case DATABASE -> "destination_database_unnamed";
        });
    }
}
