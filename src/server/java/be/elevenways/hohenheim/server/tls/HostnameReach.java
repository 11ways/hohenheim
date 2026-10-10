package be.elevenways.hohenheim.server.tls;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.net.Hostnames;
import be.elevenways.hohenheim.server.security.IpLiterals;
import be.elevenways.hohenheim.server.task.UpdateSystemIpAddresses;
import be.elevenways.protoblast.common.cache.Cache;
import be.elevenways.protoblast.common.thread.JobRunner;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.net.AddressScope;
import be.elevenways.zenit.server.net.OutboundNetwork;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Whether a hostname points at this proxy: its addresses compared with this host's own, the public addresses the
 * controller's host declares and the addresses this machine holds.
 *
 * AIDEV-NOTE: a pre-check, never authority. Let's Encrypt's HTTP-01 validation is what decides; this only turns "the
 * order failed" into "this name points somewhere else" before an order is placed. A name resolving to an address this
 * machine holds points here whatever is declared (Starfleet declared nothing, holds its public IPv4 on eth0, and
 * every name read Unknown). Only when nothing is declared and the machine holds no public address (it sits behind
 * NAT) is a name that resolves elsewhere UNKNOWN: its public address may be the one the name resolves to. Resolution
 * goes through core's installed outbound network (the system resolver; an OutboundFixture in a test), the one every
 * outbound fetch resolves with.
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
        /** The name resolves elsewhere, but this host sits behind a private address and declares no public one. */
        UNKNOWN,
        /** The lookup has not answered within the reader's wait; it keeps running and fills the cache. */
        CHECKING,
        /** No answer and no lookup running: every lookup place was taken, so none was started for this name. */
        NOT_CHECKED
    }

    /**
     * @param verdict   the answer
     * @param addresses what the name resolved to, in resolver order; empty unless it resolved
     * @param declared  whether the host DECLARES a public address: only then does the certificate pre-check refuse
     *                  (POINTS_ELSEWHERE, UNRESOLVED), as it did before held addresses counted (a host holding a public
     *                  address behind a CDN or a floating address must not lose its orders)
     */
    public record Reach(@NonNull Verdict verdict, @NonNull List<String> addresses, boolean declared) {
    }

    /** How long a looked-up answer is reused: long enough for a form's next step, short enough to see a DNS fix. */
    private static final long RECENT_MS = 60_000;

    /** The longest one reader waits for one name: the system resolver has no time limit of its own. */
    public static final long LOOKUP_WAIT_MS = 1_500;

    /** Lookups running at once; a name past it reads CHECKING until a running lookup frees a place. */
    private static final int MAX_IN_FLIGHT = 64;

    /** The answers of the last minute, by lower-cased name (protoblast's Cache reads its clock from {@code Now}). */
    private static final Cache<String, Reach> RECENT = new Cache<>(512, RECENT_MS);

    /** The lookup running for a name, so readers of the same name share one resolution. */
    private static final ConcurrentHashMap<String, CompletableFuture<Reach>> IN_FLIGHT = new ConcurrentHashMap<>();

    private static final Reach CHECKING = new Reach(Verdict.CHECKING, List.of(), false);

    private static final Reach NOT_CHECKED = new Reach(Verdict.NOT_CHECKED, List.of(), false);

    private HostnameReach() {
    }

    /**
     * Where {@code hostname} points, reusing an answer from the last minute, waiting at most {@link #LOOKUP_WAIT_MS}.
     *
     * AIDEV-NOTE: for what an operator READS (a wizard summary, a list cell), where a lookup per render would resolve
     * DNS on every page. A decision that acts on the answer (the certificate order's pre-check) calls {@link #of}.
     */
    public static @NonNull Reach recent(@NonNull String hostname) {
        return recent(hostname, LOOKUP_WAIT_MS);
    }

    /**
     * Where {@code hostname} points, waiting at most {@code waitMs} for an answer not in the cache.
     *
     * AIDEV-NOTE: a lookup that outlives the wait is NOT abandoned: it runs on a virtual thread, lands in the cache
     * when the resolver answers, and the next reader gets it. A resolver that never answers holds one virtual thread
     * per name (single-flight) and at most {@link #MAX_IN_FLIGHT} in total; past that a name reads NOT_CHECKED without
     * starting another.
     *
     * @return the answer, a {@link Verdict#CHECKING} reach when none arrived within the wait, or a
     *         {@link Verdict#NOT_CHECKED} one when no lookup could start
     */
    public static @NonNull Reach recent(@NonNull String hostname, long waitMs) {
        String name = hostname.trim().toLowerCase(Locale.ROOT);
        Reach cached = RECENT.get(name);
        if (cached != null) {
            return cached;
        }
        CompletableFuture<Reach> lookup = IN_FLIGHT.get(name);
        if (lookup == null) {
            if (IN_FLIGHT.size() >= MAX_IN_FLIGHT) {
                return NOT_CHECKED;
            }
            CompletableFuture<Reach> started = new CompletableFuture<>();
            lookup = IN_FLIGHT.putIfAbsent(name, started);
            if (lookup == null) {
                lookup = started;
                JobRunner.startVirtualThread(() -> resolveInto(name, started));
            }
        }
        if (waitMs <= 0) {
            return lookup.isDone() ? lookup.getNow(CHECKING) : CHECKING;
        }
        try {
            return lookup.get(waitMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException stillResolving) {
            return CHECKING;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return CHECKING;
        } catch (ExecutionException failed) {
            // The lookup itself failed (not an unanswered name, which of() answers): the name did not resolve.
            return new Reach(Verdict.UNRESOLVED, List.of(), false);
        }
    }

    /** Resolves one name, caches the answer and releases the name's in-flight slot. */
    private static void resolveInto(@NonNull String name, @NonNull CompletableFuture<Reach> lookup) {
        try {
            Reach reach = of(name);
            RECENT.set(name, reach);
            lookup.complete(reach);
        } catch (RuntimeException failed) {
            lookup.completeExceptionally(failed);
        } finally {
            IN_FLIGHT.remove(name, lookup);
        }
    }

    /** The label a sample name puts where a wildcard pattern takes any label. */
    static final String SAMPLE_LABEL = "hohenheim-check";

    /**
     * One name a wildcard pattern answers, to ask the DNS whether the names it catches point here: every {@code *}
     * and {@code **} becomes {@link #SAMPLE_LABEL}, every {@code ?} an {@code x}.
     *
     * AIDEV-NOTE: a catch-all's names reach this proxy through a wildcard DNS record (Starfleet's zone carries
     * {@code *} to its own address), so one name under it answers for all of them. The sample is looked up and cached
     * like any name; nothing is routed or ordered for it.
     *
     * @return the sample name, null when the pattern yields no valid hostname
     */
    public static @Nullable String sampleOf(@NonNull String glob) {
        String sample = glob.trim().toLowerCase(Locale.ROOT).replace("**", SAMPLE_LABEL).replace("*", SAMPLE_LABEL)
            .replace('?', 'x');
        return Hostnames.isValidHostname(sample) ? sample : null;
    }

    /** Drops the remembered answer for {@code hostname}, so the next reader looks it up again (a "Check again"). */
    public static void forget(@NonNull String hostname) {
        RECENT.remove(hostname.trim().toLowerCase(Locale.ROOT));
    }

    /** @return where {@code hostname} points, judged against this host's own addresses */
    public static @NonNull Reach of(@NonNull String hostname) {
        Own own = ownAddresses();
        InetAddress[] resolved;
        try {
            resolved = OutboundNetwork.SEAM.require().resolver().resolve(hostname);
        } catch (IOException unresolved) {
            return new Reach(Verdict.UNRESOLVED, List.of(), own.declared());
        }
        List<String> addresses = new ArrayList<>();
        boolean here = false;
        for (InetAddress address : resolved) {
            String text = address.getHostAddress();
            addresses.add(text);
            here |= own.addresses().contains(text);
        }
        if (addresses.isEmpty()) {
            return new Reach(Verdict.UNRESOLVED, List.of(), own.declared());
        }
        Verdict verdict = here ? Verdict.POINTS_HERE
            : own.comparable() ? Verdict.POINTS_ELSEWHERE : Verdict.UNKNOWN;
        return new Reach(verdict, List.copyOf(addresses), own.declared());
    }

    /**
     * @param addresses  every address this host answers on, in canonical spelling
     * @param declared   whether the host declares a public address
     * @param comparable whether a name resolving to none of them points elsewhere: a public address is declared or
     *                   held, so the host is not behind an address translation that hides its public one
     */
    private record Own(@NonNull Set<String> addresses, boolean declared, boolean comparable) {
    }

    /** The controller host's declared public IPv4 and IPv6 and the addresses this machine holds, loopback aside. */
    private static @NonNull Own ownAddresses() {
        Set<String> own = new LinkedHashSet<>();
        Row local = Models.get(ServerModel.class).findById(ServerModel.localServerId());
        if (local != null) {
            for (String declared : new String[] {local.get(ServerModel.PUBLIC_IPV4),
                    local.get(ServerModel.PUBLIC_IPV6)}) {
                if (declared != null && !declared.isBlank()) {
                    InetAddress literal = literal(declared);
                    // ServerModel refuses a non-literal on save; an older row reads as undeclared.
                    if (literal != null) {
                        own.add(literal.getHostAddress());
                    }
                }
            }
        }
        boolean declared = !own.isEmpty();
        boolean comparable = declared;
        for (String held : UpdateSystemIpAddresses.ensureDiscovered()) {
            // An interface's IPv6 carries its zone ("%eth0"); a resolver answer never does.
            int zone = held.indexOf('%');
            InetAddress literal = literal(zone < 0 ? held : held.substring(0, zone));
            if (literal == null || literal.isLoopbackAddress() || literal.isLinkLocalAddress()) {
                continue;
            }
            own.add(literal.getHostAddress());
            comparable |= AddressScope.of(literal.getAddress()).isPublic();
        }
        return new Own(own, declared, comparable);
    }

    /** @return the IP literal {@code text} spells, null when it is no literal (never a DNS lookup) */
    private static @Nullable InetAddress literal(@NonNull String text) {
        byte[] bytes = IpLiterals.parse(text);
        if (bytes == null) {
            return null;
        }
        try {
            return InetAddress.getByAddress(bytes);
        } catch (IOException impossible) {
            return null;
        }
    }
}
