package be.elevenways.hohenheim.server.tls;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.protoblast.common.cache.Cache;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.net.OutboundNetwork;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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

    /** How long a looked-up answer is reused: long enough for a form's next step, short enough to see a DNS fix. */
    private static final long RECENT_MS = 60_000;

    /** The answers of the last minute, by lower-cased name (protoblast's Cache reads its clock from {@code Now}). */
    private static final Cache<String, Reach> RECENT = new Cache<>(512, RECENT_MS);

    private HostnameReach() {
    }

    /**
     * Where {@code hostname} points, reusing an answer from the last minute.
     *
     * AIDEV-NOTE: for what an operator READS (a wizard summary, a list cell), where a lookup per render would resolve
     * DNS on every page. A decision that acts on the answer (the certificate order's pre-check) calls {@link #of}.
     */
    public static @NonNull Reach recent(@NonNull String hostname) {
        return RECENT.getOrCompute(hostname.trim().toLowerCase(Locale.ROOT), HostnameReach::of);
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
