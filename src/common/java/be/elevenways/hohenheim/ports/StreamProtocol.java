package be.elevenways.hohenheim.ports;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.field.EnumField;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Which transport a TCP/UDP stream forwards: the one vocabulary its setting, its ledger claims and its relays read.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public enum StreamProtocol {

    /** Connections: SSH, databases, most game servers. */
    TCP,

    /** Datagrams: voice, DNS, many game servers' own traffic. */
    UDP,

    /** Both on the same port number, as a game server that speaks either. */
    BOTH;

    /** The settings key a stream declares this vocabulary under. */
    public static final String SETTING = "protocol";

    /** @return the stored setting value */
    public @NonNull String token() {
        return switch (this) {
            case TCP -> PublishedPort.TCP;
            case UDP -> PublishedPort.UDP;
            case BOTH -> "both";
        };
    }

    /** Whether connections are forwarded. */
    public boolean forwardsTcp() {
        return switch (this) {
            case TCP, BOTH -> true;
            case UDP -> false;
        };
    }

    /** Whether datagrams are forwarded. */
    public boolean forwardsUdp() {
        return switch (this) {
            case UDP, BOTH -> true;
            case TCP -> false;
        };
    }

    /** @return the transports forwarded, each one a claim of its own in the port ledger */
    public @NonNull List<String> protocols() {
        List<String> protocols = new ArrayList<>();
        if (this.forwardsTcp()) {
            protocols.add(PublishedPort.TCP);
        }
        if (this.forwardsUdp()) {
            protocols.add(PublishedPort.UDP);
        }
        return List.copyOf(protocols);
    }

    /** @return the choice in words */
    public @NonNull Microcopy label() {
        return HohenheimMicrocopy.PORT_PROTOCOL.of("stream_protocol_" + this.token());
    }

    /** @return the protocol a stored token names, null for one this vocabulary does not know (fail closed) */
    public static @Nullable StreamProtocol ofToken(@Nullable Object token) {
        for (StreamProtocol protocol : values()) {
            if (protocol.token().equals(token == null ? null : String.valueOf(token).trim())) {
                return protocol;
            }
        }
        return null;
    }

    /** The settings field carrying this vocabulary, TCP by default. */
    public static @NonNull EnumField field() {
        EnumField.Builder builder = EnumField.builder(SETTING);
        for (StreamProtocol protocol : values()) {
            builder.value(protocol.token(), protocol.name(), protocol.label());
        }
        return builder.defaultValue(TCP.token())
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("stream_protocol"))
            .help(HohenheimMicrocopy.HELP.of("stream_protocol"))
            .build();
    }
}
