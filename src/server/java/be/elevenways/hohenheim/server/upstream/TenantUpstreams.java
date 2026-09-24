package be.elevenways.hohenheim.server.upstream;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.net.AddressScope;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.server.net.OutboundUrlGuard;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.net.InetAddress;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * THE dial-time reach of a tenant-owned site: only the public internet, never a unix socket,
 * a host path, loopback, a private, link-local or CGNAT address.
 *
 * AIDEV-NOTE: the write-time gate (TenantWrites) judges the STORED setting, textually. This is
 * the half that judges what a request actually reaches, because three things slip past a
 * textual check on forward_host: a unix socket setting overrides forward_host outright, a DNS
 * name can resolve to 127.0.0.1 or 169.254.169.254, and rows written before the gate existed
 * were never judged at all. Ownership is {@link HohenheimAccess#manageSubjectsOf}, THE owner
 * identity; unreadable grants FAIL CLOSED to tenant-owned. Operator-owned sites (no manage
 * grants) keep reaching the LAN and loopback, the reverse-proxy use case the product ships, and
 * so does a tenant-owned site whose operator marked its upstream trusted ({@link #publicOnly}).
 * Address classification is zenit's {@link AddressScope} through {@link OutboundUrlGuard},
 * never a private range table.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
public final class TenantUpstreams {

    /** How long one host's verdict is reused before the name is resolved and judged again. */
    private static final long VERDICT_TTL_MILLIS = 30_000;

    /** Bound on remembered verdicts; past it a lookup is simply not cached. */
    private static final int VERDICT_CACHE_MAX = 4096;

    private static final ConcurrentHashMap<String, CachedVerdict> VERDICTS = new ConcurrentHashMap<>();

    private record CachedVerdict(OutboundUrlGuard.@NonNull Verdict verdict, long judgedAt) {
    }

    private TenantUpstreams() {}

    /**
     * Whether a site answers to a delegated tenant rather than to the operator.
     *
     * @return true when anyone holds manage on it, or when that cannot be read (fail closed)
     */
    public static boolean isTenantOwned(@Nullable Row site) {
        Integer siteId = site != null ? site.get(SiteModel.ID) : null;
        if (siteId == null) {
            return false;
        }
        Set<String> subjects = HohenheimAccess.manageSubjectsOf(siteId);
        return subjects == null || !subjects.isEmpty();
    }

    /**
     * THE dial-time question every upstream kind asks: whether this site may reach only the
     * public internet.
     *
     * AIDEV-NOTE: an operator's {@link SiteModel#TRUSTED_UPSTREAM} lifts the restriction for a
     * tenant-owned site, so a site the operator pointed at a LAN backend keeps being served. A
     * row that does not carry the column (a partial read) counts as untrusted: fail closed.
     *
     * @return true for a tenant-owned site the operator has not marked trusted
     */
    public static boolean publicOnly(@Nullable Row site) {
        return isTenantOwned(site) && !Boolean.TRUE.equals(site.get(SiteModel.TRUSTED_UPSTREAM));
    }

    /**
     * Judge one upstream a tenant-owned site would dial, resolving a name at most once per
     * {@link #VERDICT_TTL_MILLIS}. May block on DNS: call it off the I/O thread.
     *
     * @return the vetted addresses, or the refusal
     */
    public static OutboundUrlGuard.@NonNull Verdict vet(@NonNull String scheme, @NonNull String host) {
        String url = scheme + "://" + (host.indexOf(':') >= 0 && !host.startsWith("[")
            ? "[" + host + "]" : host);
        long now = Now.millis();
        CachedVerdict cached = VERDICTS.get(url);
        if (cached != null && now - cached.judgedAt() < VERDICT_TTL_MILLIS) {
            return cached.verdict();
        }
        OutboundUrlGuard.Verdict verdict = OutboundUrlGuard.PUBLIC_INTERNET.check(url);
        if (VERDICTS.size() < VERDICT_CACHE_MAX || cached != null) {
            VERDICTS.put(url, new CachedVerdict(verdict, now));
        }
        return verdict;
    }

    /**
     * Judge a literal address without DNS.
     *
     * @return null when {@code host} is not a literal; otherwise whether it is public
     */
    public static @Nullable Boolean literalIsPublic(@Nullable String host) {
        AddressScope scope = AddressScope.ofLiteral(host);
        return scope == null ? null : scope.isPublic();
    }

    /** Whether one resolved address may be dialed for a tenant-owned site. */
    public static boolean isPublic(@NonNull InetAddress address) {
        return AddressScope.of(address.getAddress()).isPublic();
    }
}
