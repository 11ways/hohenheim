package be.elevenways.hohenheim.server.security;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.sitetype.ServedStreams;
import be.elevenways.hohenheim.server.sitetype.StreamTarget;
import be.elevenways.zenit.common.Zenit;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The ports Hohenheim's proxy answers the whole internet on, per transport: the only ports an app's own host lets it
 * reach (WorkloadNetworkPolicy).
 *
 * AIDEV-NOTE: the stream sites' ports count on the proxy's OWN host only. A stream site has no host column: its
 * ports are claimed on the controller's local host row (SitePortClaims) and bound by the proxy in this process
 * (StreamRelays), so a remote host serves none of them and its apps reach it on the web ports alone. The web ports go
 * first in the order the policy always wrote them, so a chain applied before streams counted stays exactly what
 * the policy writes on a host without streams.
 *
 * @param tcp the TCP ports, the web ports first
 * @param udp the UDP ports
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public record PublicPorts(@NonNull List<Integer> tcp, @NonNull List<Integer> udp) {

    public PublicPorts {
        tcp = List.copyOf(tcp);
        udp = List.copyOf(udp);
    }

    /** @return the web ports and every enabled stream site's ports: the proxy runs on this host */
    public static @NonNull PublicPorts ofProxyHost() {
        // An unserved site forwards nothing, so it opens nothing; the relays log it at every reload.
        return of(ServedStreams.enabled((name, refused) -> { }));
    }

    /** @return the web ports and the given streams' ports */
    public static @NonNull PublicPorts of(@NonNull List<ServedStreams.Served> streams) {
        Set<Integer> tcp = new LinkedHashSet<>(webPorts());
        Set<Integer> streamTcp = new TreeSet<>();
        Set<Integer> udp = new TreeSet<>();
        for (ServedStreams.Served served : streams) {
            for (StreamTarget target : served.targets()) {
                if (target.protocol().forwardsTcp()) {
                    streamTcp.add(target.listenPort());
                }
                if (target.protocol().forwardsUdp()) {
                    udp.add(target.listenPort());
                }
            }
        }
        tcp.addAll(streamTcp);
        return new PublicPorts(List.copyOf(tcp), List.copyOf(udp));
    }

    /** @return the web ports alone: a host no stream site listens on */
    public static @NonNull PublicPorts web() {
        return new PublicPorts(webPorts(), List.of());
    }

    /** @return the proxy's HTTP and HTTPS ports, each a valid port */
    private static @NonNull List<Integer> webPorts() {
        Set<Integer> ports = new LinkedHashSet<>();
        for (Integer port : new Integer[] {
                Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Proxy.HTTP_PORT),
                Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Proxy.HTTPS_PORT)}) {
            if (port != null && port >= 1 && port <= 65535) {
                ports.add(port);
            }
        }
        return List.copyOf(ports);
    }
}
