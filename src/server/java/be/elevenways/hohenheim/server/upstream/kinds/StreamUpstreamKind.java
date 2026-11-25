package be.elevenways.hohenheim.server.upstream.kinds;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.app.PutOnlineReach;
import be.elevenways.hohenheim.ports.PublishedPort;
import be.elevenways.hohenheim.ports.StreamPort;
import be.elevenways.hohenheim.server.sitetype.StreamProvider;
import be.elevenways.hohenheim.server.sitetype.StreamTarget;
import be.elevenways.hohenheim.server.upstream.TenantUpstreams;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.field.StringMapField;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.text.Texts;
import be.elevenways.zenit.common.ui.BadgeColor;
import be.elevenways.zenit.common.ui.ColorHue;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.ValidationContext;
import be.elevenways.zenit.common.validation.ValidationResult;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.common.validation.validator.Range;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A TCP/UDP stream (npmplus's "streams"): public ports of its own on the proxy's host, each forwarded unread to a port
 * of one upstream address. Nothing runs on a host for it.
 *
 * AIDEV-NOTE: relayed by the proxy (StreamRelays), never kernel-forwarded. Measured on daystrom: Docker owns the
 * forward hook (`table ip filter`, chain FORWARD, policy drop), so a DNAT to any other host is dropped unless
 * Hohenheim writes into Docker's DOCKER-USER chain, an object it does not own; and an upstream on the proxy host's
 * loopback cannot be DNATed at all (route_localnet 0). The relay reaches every upstream the proxy can dial, the same
 * way TLS passthrough already does, and the access list is judged on the connecting address before anything is
 * dialled. On a host with IPv6 each public port listens on every address of both families (one dual-stack socket per
 * transport, see StreamRelays), so IPv6 clients reach it as IPv4 ones do. The upstream sees the proxy's address as
 * the client, as it would behind npmplus, unless a port says {@code proxy-v2}: each TCP connection on it then starts
 * with a PROXY v2 header carrying the client's real address and port and the public address it connected to (TCP4
 * or TCP6, the client's own family), which the upstream must be set to expect (a mail server behind it learns who
 * connected). UDP never carries the header: a port forwarding datagrams refuses it ({@link StreamPort#parse}).
 *
 * AIDEV-NOTE: ONE site holds every port of one service (a mail server's 25, 465, 587, 143 and 993): the ports are a
 * list ({@link StreamPort}, {@link #PORTS}), what every port shares (the address, the connect timeout, the access
 * list, the switch) is the site's. A stream with one port is a list of one; M008_StreamPortLists converted the
 * single-port shape, and nothing here reads it: a site revision stored before it reads through
 * SinglePortStreamSettings.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class StreamUpstreamKind implements StreamProvider {

    public static final Identifier ID = HohenheimIds.id("stream");
    public static final Schema SETTINGS_SCHEMA = new Schema();

    public static final StringField FORWARD_HOST = SETTINGS_SCHEMA.addField(
        StringField.builder().name("forward_host").required()
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("stream_forward_host"))
            .help(HohenheimMicrocopy.HELP.of("stream_forward_host"))
            .placeholder("10.0.0.20")
            .build());

    /** The public ports, each with where it goes at {@link #FORWARD_HOST} and how ({@link StreamPort}). */
    public static final StringMapField PORTS = SETTINGS_SCHEMA.addField(
        StringMapField.builder(StreamPort.FIELD).required()
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("stream_ports"))
            .help(HohenheimMicrocopy.HELP.of("stream_ports"))
            .keyLabel(HohenheimMicrocopy.HOHENHEIM_FIELD.of("listen_port"))
            .valueLabel(HohenheimMicrocopy.HOHENHEIM_FIELD.of("stream_forward_port"))
            .entries(StreamPort.entries())
            .validator(StreamUpstreamKind::refuseUnreadablePorts)
            .build());

    public static final IntegerField CONNECT_TIMEOUT = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("connect_timeout").defaultValue(10).suffix("s")
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

    /** One claim per public port and transport, each forwarding to its own port upstream. */
    @Override
    public @NonNull List<PublishedPort> proxyPorts(@NonNull Map<String, Object> settings) {
        List<PublishedPort> claims = new ArrayList<>();
        for (StreamPort port : portsOf(settings)) {
            for (String transport : port.protocol().protocols()) {
                claims.add(new PublishedPort(transport, port.listenPort(), port.forwardPort(), transport, false));
            }
        }
        return claims;
    }

    /** The public ports and where they go: {@code 25, 465 -> mail.internal}. */
    @Override
    public String upstreamSummary(Map<String, Object> settings) {
        String host = Texts.trimmedOrNull(settings.get(FORWARD_HOST.getName()));
        List<StreamPort> ports = portsOf(settings);
        if (host == null || ports.isEmpty()) {
            return null;
        }
        return labels(ports) + " -> " + host;
    }

    @Override
    public @NonNull List<StreamTarget> streamTargets(Row site, Map<String, Object> settings) {
        String host = Texts.trimmedOrNull(settings.get(FORWARD_HOST.getName()));
        Integer timeout = RawValues.parsedInt(settings.get(CONNECT_TIMEOUT.getName()));
        if (host == null) {
            throw new IllegalArgumentException("the upstream host is missing");
        }
        List<StreamPort> ports;
        try {
            ports = StreamPort.of(settings);
        } catch (Violations unreadable) {
            // An unreadable stored port forwards nothing rather than a guessed one.
            throw new IllegalArgumentException("a public port is not written as forward port/protocol: "
                + settings.get(StreamPort.FIELD), unreadable);
        }
        if (ports.isEmpty()) {
            throw new IllegalArgumentException("the stream has no public port");
        }
        boolean publicOnly = TenantUpstreams.publicOnly(site);
        if (publicOnly && Boolean.FALSE.equals(TenantUpstreams.literalIsPublic(host))) {
            throw new IllegalArgumentException("a tenant-owned stream may only forward to a public address");
        }
        int millis = (timeout == null ? 10 : Math.max(1, Math.min(300, timeout))) * 1000;
        List<StreamTarget> targets = new ArrayList<>();
        for (StreamPort port : ports) {
            targets.add(new StreamTarget(port.listenPort(), port.protocol(), host, port.forwardPort(), millis,
                publicOnly, port.proxyProtocolV2()));
        }
        return targets;
    }

    /** @return the site's public ports, none when their spelling is refused (its save refuses it first) */
    public static @NonNull List<StreamPort> portsOf(@NonNull Map<String, Object> settings) {
        try {
            return StreamPort.of(settings);
        } catch (Violations unreadable) {
            return List.of();
        }
    }

    /** @return the ports as a list names them: {@code 25, 465, 53/udp} */
    public static @NonNull String labels(@NonNull List<StreamPort> ports) {
        return String.join(", ", ports.stream().map(StreamPort::label).toList());
    }

    /** A stream forwards at least one public port, each written as {@link StreamPort} reads it. */
    private static @NonNull ValidationResult refuseUnreadablePorts(@Nullable Map<String, String> ports,
                                                                   @NonNull ValidationContext context) {
        if (ports == null || ports.isEmpty()) {
            return ValidationResult.invalid(HohenheimMicrocopy.VIOLATIONS.of("stream_ports_required"));
        }
        try {
            StreamPort.of(Map.of(StreamPort.FIELD, ports));
        } catch (Violations refused) {
            return ValidationResult.invalid(refused.all().getFirst().message());
        }
        return ValidationResult.valid();
    }
}
