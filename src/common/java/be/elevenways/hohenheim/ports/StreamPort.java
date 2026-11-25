package be.elevenways.hohenheim.ports;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.zenit.common.edit.JsonSchemaType;
import be.elevenways.zenit.common.orm.field.MapEntries;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One public port a TCP/UDP stream site listens on, as its settings' {@code ports} map declares it: the public port's
 * number to {@code forward port[/protocol][ proxy-v2]} ({@code "2525/tcp proxy-v2"}).
 *
 * AIDEV-NOTE: THE one spelling of a stream's port, read by the relay, the port ledger, the lists and the tools alike;
 * a stream with one port is a map of one (M008_StreamPortLists converted every single-port stream). What is the same
 * for every port of one site (the address it forwards to, the connect timeout, the access list) stays on the site.
 *
 * @param listenPort       the public port on the proxy's host
 * @param forwardPort      the port at the site's forward address
 * @param protocol         the transports forwarded on it
 * @param proxyProtocolV2  whether each TCP connection starts with a PROXY v2 header
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public record StreamPort(int listenPort, int forwardPort, @NonNull StreamProtocol protocol, boolean proxyProtocolV2) {

    /** The settings key holding the map. */
    public static final String FIELD = "ports";

    /** The word after the protocol that switches the PROXY v2 header on. */
    public static final String PROXY_V2 = "proxy-v2";

    private static final String LISTEN_PORT = "listen_port";
    private static final String FORWARD_PORT = "forward_port";
    private static final String PROTOCOL = "protocol";
    private static final String PROXY_PROTOCOL_V2 = "proxy_protocol_v2";

    private static final PortMap<StreamPort> MAP = new PortMap<>(FIELD, StreamPort::parse,
        port -> String.valueOf(port.listenPort()), StreamPort::spec);

    /** @throws IllegalArgumentException for a PROXY v2 header over UDP, which never carries one */
    public StreamPort {
        if (proxyProtocolV2 && protocol.forwardsUdp()) {
            throw new IllegalArgumentException("a PROXY v2 header is sent on TCP connections only");
        }
    }

    /**
     * @return every port the settings declare, in declaration order
     * @throws Violations naming the first entry that is not {@code forward port[/protocol][ proxy-v2]}
     */
    public static @NonNull List<StreamPort> of(@NonNull Map<String, Object> settings) {
        return MAP.of(settings);
    }

    /**
     * @return the port its key and spelling describe
     * @throws Violations when the key is no port in 1-65535, the spelling is not {@code forward port[/protocol][
     *         proxy-v2]}, or it asks a PROXY v2 header on a port forwarding UDP
     */
    public static @NonNull StreamPort parse(@NonNull String listen, @NonNull String spec) {
        String key = listen.trim();
        Integer listenPort = number(key);
        List<String> words = Arrays.stream(spec.trim().split("\\s+")).filter(word -> !word.isEmpty()).toList();
        // The key must be the number's own spelling, so "025" and "25" never name one port twice in one map.
        if (listenPort == null || !String.valueOf(listenPort).equals(key) || words.isEmpty() || words.size() > 2
                || (words.size() == 2 && !PROXY_V2.equals(words.get(1)))) {
            throw invalid(key, spec);
        }
        String target = words.getFirst();
        int slash = target.indexOf('/');
        StreamProtocol protocol = slash < 0 ? StreamProtocol.TCP : StreamProtocol.ofToken(target.substring(slash + 1));
        Integer forwardPort = number(slash < 0 ? target : target.substring(0, slash));
        if (forwardPort == null || protocol == null) {
            throw invalid(key, spec);
        }
        boolean proxy = words.size() == 2;
        if (proxy && protocol.forwardsUdp()) {
            throw Violations.ofField(MAP.path(), spec, HohenheimMicrocopy.VIOLATIONS
                .of("stream_port_proxy_protocol_tcp_only").withArg("port", key));
        }
        return new StreamPort(listenPort, forwardPort, protocol, proxy);
    }

    /** @return the spelling stored under this port's number */
    public @NonNull String spec() {
        return this.forwardPort + "/" + this.protocol.token() + (this.proxyProtocolV2 ? " " + PROXY_V2 : "");
    }

    /** @return the settings map value for these ports, keyed by public port */
    public static @NonNull Map<String, String> toSettings(@NonNull List<StreamPort> ports) {
        return MAP.toSettings(ports);
    }

    /** @return the public port as a list names it: its number, with its transport unless it is TCP alone */
    public @NonNull String label() {
        return this.protocol == StreamProtocol.TCP ? String.valueOf(this.listenPort)
            : this.listenPort + "/" + this.protocol.token();
    }

    /**
     * @return the typed entries a JSON caller writes the map in, keyed by public port, each joined into the stored
     *     spelling and judged by {@link #parse} as a typed spelling is
     */
    public static @NonNull MapEntries entries() {
        List<String> protocols = Arrays.stream(StreamProtocol.values()).map(StreamProtocol::token).toList();
        return MAP.entries(MapEntries.keyedBy(LISTEN_PORT, JsonSchemaType.INTEGER,
                    HohenheimMicrocopy.HELP.of("stream_port_listen"))
                .member(FORWARD_PORT, JsonSchemaType.INTEGER, HohenheimMicrocopy.HELP.of("stream_port_forward"))
                .member(PROTOCOL, protocols, HohenheimMicrocopy.HELP.of("stream_port_protocol"))
                .member(PROXY_PROTOCOL_V2, JsonSchemaType.BOOLEAN, HohenheimMicrocopy.HELP.of("stream_port_proxy")),
            values -> {
                String forward = Objects.requireNonNullElse(PortMap.wholeNumber(values.get(FORWARD_PORT)), "");
                String protocol = values.get(PROTOCOL) == null ? StreamProtocol.TCP.token()
                    : String.valueOf(values.get(PROTOCOL));
                boolean proxy = Boolean.TRUE.equals(values.get(PROXY_PROTOCOL_V2));
                return forward + "/" + protocol + (proxy ? " " + PROXY_V2 : "");
            });
    }

    /** @return the port a text names, null for one that is no whole number in 1-65535 */
    private static @Nullable Integer number(@NonNull String text) {
        try {
            int port = Integer.parseInt(text.trim());
            return port >= 1 && port <= 65535 ? port : null;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static @NonNull Violations invalid(@NonNull String listen, @NonNull String spec) {
        return Violations.ofField(MAP.path(), spec, HohenheimMicrocopy.VIOLATIONS.of("stream_port_invalid")
            .withArg("port", listen).withArg("spec", spec));
    }
}
