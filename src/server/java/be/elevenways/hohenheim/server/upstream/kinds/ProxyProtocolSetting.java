package be.elevenways.hohenheim.server.upstream.kinds;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.zenit.common.orm.field.BooleanField;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * The one declaration of the upstream-kind setting that makes the proxy's relay send a PROXY protocol v2 header
 * (the original client and destination) before any other byte, on a TLS passthrough site; a TCP/UDP stream says it
 * per public port instead ({@code proxy-v2}, StreamPort).
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
final class ProxyProtocolSetting {

    /** The stored settings key, the label and the help of the setting alike. */
    static final String NAME = "proxy_protocol_v2";

    private ProxyProtocolSetting() {
    }

    /** @return the setting's builder, off by default, so a kind can add the validators only it needs */
    static BooleanField.@NonNull Builder builder() {
        return BooleanField.builder(NAME).defaultValue(false)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of(NAME))
            .help(HohenheimMicrocopy.HELP.of(NAME));
    }
}
