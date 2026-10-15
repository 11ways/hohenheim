package be.elevenways.hohenheim.server.upstream.kinds;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.app.PutOnlineReach;
import be.elevenways.hohenheim.ports.PublishedPort;
import be.elevenways.hohenheim.ports.StreamProtocol;
import be.elevenways.hohenheim.server.sitetype.StreamProvider;
import be.elevenways.hohenheim.server.sitetype.StreamTarget;
import be.elevenways.hohenheim.server.upstream.TenantUpstreams;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.text.Texts;
import be.elevenways.zenit.common.ui.BadgeColor;
import be.elevenways.zenit.common.ui.ColorHue;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.validator.Range;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A TCP/UDP stream (npmplus's "streams"): a public port of its own on the proxy's host, forwarded unread to an
 * upstream address and port. Nothing runs on a host for it.
 *
 * AIDEV-NOTE: relayed by the proxy (StreamRelays), never kernel-forwarded. Measured on daystrom: Docker owns the
 * forward hook (`table ip filter`, chain FORWARD, policy drop), so a DNAT to any other host is dropped unless
 * Hohenheim writes into Docker's DOCKER-USER chain, an object it does not own; and an upstream on the proxy host's
 * loopback cannot be DNATed at all (route_localnet 0). The relay reaches every upstream the proxy can dial, the same
 * way TLS passthrough already does, and the access list is judged on the connecting address before anything is
 * dialled. The upstream sees the proxy's address as the client, as it would behind npmplus.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class StreamUpstreamKind implements StreamProvider {

    public static final Identifier ID = HohenheimIds.id("stream");
    public static final Schema SETTINGS_SCHEMA = new Schema();

    /** The public port clients connect to, on the proxy's own host. */
    public static final IntegerField LISTEN_PORT = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("listen_port").required()
            .validator(Range.of(1, 65535))
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("listen_port"))
            .help(HohenheimMicrocopy.HELP.of("listen_port"))
            .build());

    public static final EnumField PROTOCOL = SETTINGS_SCHEMA.addField(StreamProtocol.field());

    public static final StringField FORWARD_HOST = SETTINGS_SCHEMA.addField(
        StringField.builder().name("forward_host").required()
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("stream_forward_host"))
            .help(HohenheimMicrocopy.HELP.of("stream_forward_host"))
            .placeholder("10.0.0.20")
            .build());

    public static final IntegerField FORWARD_PORT = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("forward_port").required()
            .validator(Range.of(1, 65535))
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("stream_forward_port"))
            .help(HohenheimMicrocopy.HELP.of("stream_forward_port"))
            .build());

    public static final IntegerField CONNECT_TIMEOUT = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("connect_timeout").defaultValue(10).required().suffix("s")
            .validator(Range.of(1, 300))
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("connect_timeout"))
            .help(HohenheimMicrocopy.HELP.of("stream_connect_timeout"))
            .build());

    @Override public Identifier typeId() { return ID; }
    @Override public String getDisplayName() { return "TCP/UDP stream"; }
    @Override public Icon getIcon() { return Icon.of("arrow-right-arrow-left"); }
    @Override public BadgeColor color() { return ColorHue.ORANGE; }
    @Override public Schema getSchema() { return SETTINGS_SCHEMA; }

    @Override
    public @NonNull PutOnlineReach putOnlineReach() {
        return PutOnlineReach.PORTS;
    }

    /** One claim per transport, on the public port, forwarding to the upstream's port. */
    @Override
    public @NonNull List<PublishedPort> proxyPorts(@NonNull Map<String, Object> settings) {
        Integer listen = RawValues.parsedInt(settings.get(LISTEN_PORT.getName()));
        Integer forward = RawValues.parsedInt(settings.get(FORWARD_PORT.getName()));
        StreamProtocol protocol = protocolOf(settings);
        if (listen == null || protocol == null) {
            return List.of();
        }
        List<PublishedPort> ports = new ArrayList<>();
        for (String transport : protocol.protocols()) {
            ports.add(new PublishedPort(transport, listen, forward == null ? listen : forward, transport, false));
        }
        return ports;
    }

    /** The public port and where it goes. */
    @Override
    public String upstreamSummary(Map<String, Object> settings) {
        Object listen = settings.get(LISTEN_PORT.getName());
        Object host = settings.get(FORWARD_HOST.getName());
        Object port = settings.get(FORWARD_PORT.getName());
        if (listen == null || host == null || String.valueOf(host).isBlank()) {
            return null;
        }
        StreamProtocol protocol = protocolOf(settings);
        return listen + "/" + (protocol == null ? "?" : protocol.token()) + " -> " + host + ":" + port;
    }

    @Override
    public StreamTarget streamTarget(Row site, Map<String, Object> settings) {
        Integer listen = RawValues.parsedInt(settings.get(LISTEN_PORT.getName()));
        Integer forward = RawValues.parsedInt(settings.get(FORWARD_PORT.getName()));
        String host = Texts.trimmedOrNull(settings.get(FORWARD_HOST.getName()));
        StreamProtocol protocol = protocolOf(settings);
        Integer timeout = RawValues.parsedInt(settings.get(CONNECT_TIMEOUT.getName()));
        if (listen == null || listen < 1 || listen > 65535) {
            throw new IllegalArgumentException("the public port is missing or outside 1-65535");
        }
        if (forward == null || forward < 1 || forward > 65535) {
            throw new IllegalArgumentException("the upstream port is missing or outside 1-65535");
        }
        if (host == null) {
            throw new IllegalArgumentException("the upstream host is missing");
        }
        if (protocol == null) {
            // An unknown stored token forwards nothing rather than a guessed transport.
            throw new IllegalArgumentException("the protocol '" + settings.get(StreamProtocol.SETTING)
                + "' is not tcp, udp or both");
        }
        boolean publicOnly = TenantUpstreams.publicOnly(site);
        if (publicOnly && Boolean.FALSE.equals(TenantUpstreams.literalIsPublic(host))) {
            throw new IllegalArgumentException("a tenant-owned stream may only forward to a public address");
        }
        int seconds = timeout == null ? 10 : Math.max(1, Math.min(300, timeout));
        return new StreamTarget(listen, protocol, host, forward, seconds * 1000, publicOnly);
    }

    /** @return the stream's protocol, TCP when the setting is absent, null for a token the vocabulary does not know */
    static StreamProtocol protocolOf(@NonNull Map<String, Object> settings) {
        Object token = settings.get(StreamProtocol.SETTING);
        return token == null || String.valueOf(token).isBlank() ? StreamProtocol.TCP : StreamProtocol.ofToken(token);
    }
}
