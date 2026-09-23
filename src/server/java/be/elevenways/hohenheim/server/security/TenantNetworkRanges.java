package be.elevenways.hohenheim.server.security;

import be.elevenways.zenit.common.net.AddressScope;
import be.elevenways.zenit.common.net.IpRanges;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * THE vocabulary of address ranges a tenant workload may not reach, shared by every
 * enforcement backend (the nftables applier for Docker networks, the Incus network ACL
 * for system containers) so the two can never drift apart.
 *
 * AIDEV-NOTE: the ranges are DERIVED from zenit's {@link AddressScope} members named in
 * {@link #TENANT_SCOPES}, never spelled here. The metadata range stays FIRST and
 * separately named because it is the classic escape: 169.254.169.254 hands out credentials for
 * the whole host on clouds, and its IPv6 twin (fd00:ec2::254) sits inside the ULA range
 * fc00::/7 which is denied wholesale. The RFC 1918 ranges cover every OTHER bridge on the same
 * daemon (Docker's default pools and Incus's managed bridges all allocate inside them), which is
 * what makes one static rule set per-workload isolation instead of per-network bookkeeping.
 *
 * AIDEV-NOTE: the text of every range is the spelling nft and Incus render back
 * ({@link IpLiterals#cidr}), because the appliers VERIFY by comparing their rules against the
 * kernel's own listing. A change to the nft-only lists is picked up by the Docker and process
 * isolation verifiers, which re-apply every live workload whose kernel state lacks a new rule;
 * a change to the tenant list is NOT safe that way (see {@link #TENANT_SCOPES}).
 */
public final class TenantNetworkRanges {

    /**
     * The scopes a tenant workload is cut off from.
     *
     * AIDEV-NOTE: deliberately NOT "every LOCAL_NETWORK reach" yet, which would add the RFC 6598
     * shared space 100.64/10 (tailnets, carrier NAT) and the deprecated site-local fec0::/10.
     * Widening this list is a production ROLLOUT, not an edit: the Incus verifier
     * (VerifyIncusIsolation / IncusKernelIsolation.missingRules) demands every range here in
     * the kernel, while the kernel rules come from the shared Incus ACL that only
     * IncusNetworkPolicy.ensure rewrites -- so on the first sweep after an upgrade every
     * Incus workload would read as unrepairable and be STOPPED. It needs the verifier to
     * re-ensure the ACL before inspecting; until then 100.64/10 is refused on the host input
     * hook only ({@link #HOST_DENIED_V4}).
     */
    private static final List<AddressScope> TENANT_SCOPES =
        List.of(AddressScope.LINK_LOCAL, AddressScope.PRIVATE, AddressScope.UNIQUE_LOCAL);

    /** The cloud instance-metadata range: credentials for the whole host, one HTTP GET away. */
    public static final String METADATA_V4 = IpLiterals.cidr(IpRanges.LINK_LOCAL_V4.get(0));

    /** Every tenant range of both families, the metadata range first. */
    private static final List<IpRanges.Range> DENIED = derive(TENANT_SCOPES::contains);

    /** The metadata range, then RFC 1918. */
    public static final List<String> DENIED_V4 = texts(DENIED, 4);

    /** Link-local and unique-local (container networks AND the v6 metadata address). */
    public static final List<String> DENIED_V6 = texts(DENIED, 16);

    /**
     * Every IPv4 range a workload may not reach ON THE HOST ITSELF: {@link #DENIED_V4} plus
     * the unspecified, loopback, shared (100.64/10), multicast, reserved, benchmarking and
     * IETF blocks.
     *
     * AIDEV-NOTE: the documentation ranges are the one non-public family deliberately left
     * reachable. TEST-NET addresses are what the isolation tests (and many a lab host) give
     * the host's public interface, and the input chain's public-listener exception must keep
     * working there; nothing routable lives in them, so refusing them protects nothing.
     */
    public static final List<String> HOST_DENIED_V4 = texts(derive(scope -> !scope.isPublic()
        && scope != AddressScope.DOCUMENTATION), 4);

    /**
     * Whether a literal address falls inside the denied vocabulary, so a consumer that must
     * decide "is THIS address one a tenant would be cut off from" (the process tier's
     * resolver carve-out) asks the vocabulary instead of re-listing the ranges.
     *
     * AIDEV-NOTE: literals only -- resolution is explicitly refused. This answers questions
     * about addresses read out of host configuration (resolv.conf), and a hostname there
     * would otherwise be resolved by the very resolver we are deciding about.
     *
     * @param literal an IPv4 or IPv6 address literal, or null
     * @return false for anything that is not a literal address
     */
    public static boolean denies(@Nullable String literal) {
        InetAddress address = parseLiteral(literal);
        return address != null && denies(address);
    }

    /** @return whether the address falls inside one of the denied ranges */
    public static boolean denies(@NonNull InetAddress address) {
        return IpRanges.matchesAny(DENIED, address.getAddress());
    }

    /**
     * @param literal an address literal, or null; a scope suffix ({@code fe80::1%eth0}) is
     *                accepted, because that is how a link-local resolver is written
     * @return the parsed address, or null when the text is not a literal of either family
     */
    public static @Nullable InetAddress parseLiteral(@Nullable String literal) {
        if (literal == null || literal.isBlank()) {
            return null;
        }
        // IpRanges drops the zone itself: it is a local interface selector, never part of the
        // address a rule matches on. The parse is DNS-free, so getByAddress never resolves.
        byte[] bytes = IpRanges.parseLiteral(literal.trim());
        if (bytes == null) {
            return null;
        }
        try {
            return InetAddress.getByAddress(bytes);
        } catch (UnknownHostException impossible) {
            return null;   // IpRanges only returns the two lengths getByAddress accepts
        }
    }

    /**
     * The ranges of every scope the predicate selects, the metadata range first, then the
     * tenant scopes, then the rest in declaration order, with every range a wider listed one
     * already contains left out.
     */
    private static @NonNull List<IpRanges.Range> derive(@NonNull Predicate<AddressScope> selected) {
        List<IpRanges.Range> ordered = new ArrayList<>(IpRanges.LINK_LOCAL_V4);
        for (AddressScope scope : TENANT_SCOPES) {
            if (selected.test(scope)) {
                ordered.addAll(scope.ranges());
            }
        }
        for (AddressScope scope : AddressScope.values()) {
            if (selected.test(scope) && !TENANT_SCOPES.contains(scope)) {
                ordered.addAll(scope.ranges());
            }
        }
        // Coverage is judged against the WHOLE list, not only earlier entries: nft renders a
        // redundant host range (255.255.255.255 inside 240/4) differently from how we would
        // spell it, and a rule the read-back cannot find refuses the deploy.
        List<IpRanges.Range> kept = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            IpRanges.Range candidate = ordered.get(i);
            boolean redundant = false;
            for (int j = 0; j < ordered.size() && !redundant; j++) {
                IpRanges.Range other = ordered.get(j);
                boolean contains = other.prefixLength() <= candidate.prefixLength()
                    && other.matches(candidate.network());
                boolean sameRange = other.prefixLength() == candidate.prefixLength() && contains;
                redundant = j != i && contains && (!sameRange || j < i);
            }
            if (!redundant) {
                kept.add(candidate);
            }
        }
        return List.copyOf(kept);
    }

    /** The range texts of one address family (4 or 16 bytes), in list order. */
    private static @NonNull List<String> texts(@NonNull List<IpRanges.Range> ranges, int familyBytes) {
        List<String> texts = new ArrayList<>();
        for (IpRanges.Range range : ranges) {
            if (range.network().length == familyBytes) {
                texts.add(IpLiterals.cidr(range));
            }
        }
        return List.copyOf(texts);
    }

    private TenantNetworkRanges() {}
}
