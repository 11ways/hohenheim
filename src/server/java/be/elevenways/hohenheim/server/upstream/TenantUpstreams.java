package be.elevenways.hohenheim.server.upstream;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.zenit.common.net.AddressScope;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.server.net.OutboundUrlGuard;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Duration;
import java.util.Set;

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
    private static final Duration VERDICT_TTL = Duration.ofSeconds(30);

    /** Bound on remembered verdicts per guard. */
    private static final int VERDICT_MEMORY = 4096;

    /**
     * The reach of a tenant-owned site: public addresses only.
     *
     * AIDEV-NOTE: the guards remember verdicts per installed OutboundNetwork (zenit's
     * {@link OutboundUrlGuard#remembering}), never in a map of their own: a test's OutboundFixture
     * routing a name another fixture routed a moment ago must get its own address.
     */
    private static final OutboundUrlGuard PUBLIC_REACH =
        OutboundUrlGuard.PUBLIC_INTERNET.remembering(VERDICT_TTL, VERDICT_MEMORY);

    /** The reach of an operator's (or operator-trusted) site: every address, still resolved once and pinned. */
    private static final OutboundUrlGuard ANY_REACH =
        OutboundUrlGuard.ANY_ADDRESS.remembering(VERDICT_TTL, VERDICT_MEMORY);

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
     * AIDEV-NOTE: neither ownership nor the flag widens reach unless the upstream was last set by the system tier
     * ({@link SiteModel#TARGET_TRUSTED}, stamped by OperatorTrustedWrites): a delegate's upstream on a tenant-owned
     * site stays public-only after the tenant's grant is revoked and the site becomes operator-owned.
     *
     * @return true unless the system tier set the upstream and the site is operator-owned or marked trusted
     */
    public static boolean publicOnly(@Nullable Row site) {
        if (site == null || !Boolean.TRUE.equals(site.get(SiteModel.TARGET_TRUSTED))) {
            return true;
        }
        return isTenantOwned(site) && !Boolean.TRUE.equals(site.get(SiteModel.TRUSTED_UPSTREAM));
    }

    /**
     * Judge one upstream a site would dial, resolving a name at most once per {@link #VERDICT_TTL}.
     * May block on DNS: call it off the I/O thread.
     *
     * @param publicOnly {@link #publicOnly} of the site: public addresses only, else any address
     * @return the vetted addresses, or the refusal
     */
    public static OutboundUrlGuard.@NonNull Verdict vet(@NonNull String scheme, @NonNull String host,
                                                        boolean publicOnly) {
        String url = scheme + "://" + (host.indexOf(':') >= 0 && !host.startsWith("[")
            ? "[" + host + "]" : host);
        return (publicOnly ? PUBLIC_REACH : ANY_REACH).check(url);
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
}
