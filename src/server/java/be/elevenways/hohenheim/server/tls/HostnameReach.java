package be.elevenways.hohenheim.server.tls;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.net.OutboundNetwork;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Whether a hostname points at this proxy: its addresses compared with the public addresses the controller's own
 * host declares.
 *
 * AIDEV-NOTE: a pre-check, never authority. Let's Encrypt's HTTP-01 validation is what decides; this only turns "the
 * order failed" into "this name points somewhere else" before an order is placed. Without declared public addresses
 * nothing can be judged, so the answer is UNKNOWN and nobody is refused on it. Resolution goes through core's installed
 * outbound network (the system resolver; an OutboundFixture in a test), the one every outbound fetch resolves with.
 *
 * @author Jelle De Loecker
 * @since  0.2.0
 */
public final class HostnameReach {

    /** The answer for one name. */
    public enum Verdict {
        /** At least one of the name's addresses is this host's. */
        POINTS_HERE,
        /** The name resolves, to addresses none of which are this host's. */
        POINTS_ELSEWHERE,
        /** The name does not resolve (yet). */
        UNRESOLVED,
        /** This host declares no public address, so nothing can be compared. */
        UNKNOWN
    }

    /**
     * @param verdict   the answer
     * @param addresses what the name resolved to, in resolver order; empty unless it resolved
     */
    public record Reach(@NonNull Verdict verdict, @NonNull List<String> addresses) {
    }

    private HostnameReach() {
    }

    /** @return where {@code hostname} points, judged against the controller host's declared public addresses */
    public static @NonNull Reach of(@NonNull String hostname) {
        Set<String> own = ownAddresses();
        if (own.isEmpty()) {
            return new Reach(Verdict.UNKNOWN, List.of());
        }
        InetAddress[] resolved;
        try {
            resolved = OutboundNetwork.SEAM.require().resolver().resolve(hostname);
        } catch (IOException unresolved) {
            return new Reach(Verdict.UNRESOLVED, List.of());
        }
        List<String> addresses = new ArrayList<>();
        boolean here = false;
        for (InetAddress address : resolved) {
            String text = address.getHostAddress();
            addresses.add(text);
            here |= own.contains(text);
        }
        if (addresses.isEmpty()) {
            return new Reach(Verdict.UNRESOLVED, List.of());
        }
        return new Reach(here ? Verdict.POINTS_HERE : Verdict.POINTS_ELSEWHERE, List.copyOf(addresses));
    }

    /** The controller host's declared public IPv4 and IPv6, in their canonical spelling. */
    private static @NonNull Set<String> ownAddresses() {
        Row local = Models.get(ServerModel.class).findById(ServerModel.localServerId());
        Set<String> own = new LinkedHashSet<>();
        if (local == null) {
            return own;
        }
        for (String declared : new String[] {local.get(ServerModel.PUBLIC_IPV4), local.get(ServerModel.PUBLIC_IPV6)}) {
            if (declared == null || declared.isBlank()) {
                continue;
            }
            try {
                own.add(InetAddress.getByName(declared.trim()).getHostAddress());
            } catch (IOException notALiteral) {
                // ServerModel refuses a non-literal on save; an older row reads as undeclared.
            }
        }
        return own;
    }
}
