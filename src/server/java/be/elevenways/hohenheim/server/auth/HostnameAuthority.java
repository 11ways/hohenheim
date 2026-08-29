package be.elevenways.hohenheim.server.auth;

import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.proxy.HostnamePatterns;
import be.elevenways.protoblast.common.key.IdentifierKey;
import be.elevenways.protoblast.common.util.BlastString;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * THE "does this caller answer for this hostname" predicate, shared by every tier that
 * derives authority from a name rather than from a record grant.
 *
 * AIDEV-NOTE: extracted from {@code CertificateAuthority.authorize}, which still owns the
 * certificate-specific half (Let's Encrypt exclusion, TLS passthrough). The walk itself --
 * live covering domain rows, then MANAGE on every one of their sites -- is the same question
 * a tenant DNS write asks, and two copies of it would be two answers.
 *
 * AIDEV-NOTE: authority MIRRORS ROUTING. Only the MOST SPECIFIC covering rows decide a name
 * (exact beats wildcard, a wildcard beats a broader wildcard -- {@link Snapshot#deciding}),
 * exactly the order {@code SiteDispatcher}/{@code RouteResolver} consults them in. Until
 * 2026-08-29 EVERY covering row had to be managed, and the moment an operator added a
 * {@code *.example.com} catch-all site no tenant could author DNS or order a certificate for
 * its OWN exact hostname any more ("a name two owners answer for"), while the proxy was
 * routing that name to the tenant's site all along. Within one tier the rule is still "all
 * of them": two rows of equal specificity are one contested name, and a name only a FOREIGN
 * wildcard covers is the wildcard owner's namespace, so a tenant adding a new hostname there
 * is refused (the same-tier check) rather than handed the name.
 *
 * @author Jelle De Loecker
 */
public final class HostnameAuthority {

    private HostnameAuthority() {
    }

    /**
     * One load of the live domain and site tables, so a batch decision pays for them once
     * instead of per name.
     */
    public static final class Snapshot {

        private final @NonNull List<Row> domains;
        private final @NonNull Map<Integer, Row> sitesById;

        private Snapshot(@NonNull List<Row> domains, @NonNull Map<Integer, Row> sitesById) {
            this.domains = domains;
            this.sitesById = sitesById;
        }

        public static @NonNull Snapshot load() {
            List<Row> domains = Models.get(SiteDomainModel.class).find().all();
            Map<Integer, Row> sitesById = new HashMap<>();
            for (Row site : Models.get(SiteModel.class).find().all()) {
                sitesById.put(site.get(SiteModel.ID), site);
            }
            return new Snapshot(domains, sitesById);
        }

        /** Request-scoped memo of {@link #load}, keyed on the conduit. */
        private static final IdentifierKey<Snapshot> MEMO =
            IdentifierKey.of("hohenheim", "hostname_authority_snapshot");

        /**
         * The READ-DECISION variant of {@link #load}: one snapshot per request, so a
         * per-row consumer (a list's updatableBy/deletableBy affordances) pays the two
         * table loads once instead of per row.
         *
         * AIDEV-NOTE: affordance and scope reads ONLY -- the WRITE lanes
         * (requireRecordAuthority, the delete hook) keep calling {@link #load} fresh, so
         * a domain moved mid-request can never be authorized against its old owner. The
         * memo makes the offered affordance at worst one request staler than the gate,
         * which is the same staleness the walk memo in HohenheimAccess already accepts.
         */
        public static @NonNull Snapshot memoized(@NonNull AccessContext ctx) {
            Conduit conduit = ctx.conduit();
            if (conduit == null) {
                return load();
            }
            Snapshot cached = conduit.getAttribute(MEMO);
            if (cached != null) {
                return cached;
            }
            Snapshot snapshot = load();
            try {
                conduit.setAttribute(MEMO, snapshot);
            } catch (UnsupportedOperationException attributeless) {
                // A conduit without attribute storage just pays the load each call.
            }
            return snapshot;
        }

        /** @return the site a domain row hangs off, or null when it is gone */
        public @Nullable Row siteOf(@NonNull Row domain) {
            Integer siteId = domain.get(SiteDomainModel.SITE_ID);
            return siteId != null ? this.sitesById.get(siteId) : null;
        }

        /**
         * The LIVE domain rows whose pattern covers the name. A soft-deleted site owns
         * nothing -- its grants do not survive its delete
         * ({@code HohenheimAccess.declareGrantableModels}), so neither may its hostnames.
         */
        public @NonNull List<Row> covering(@Nullable String hostname) {
            String needle = BlastString.lower(hostname != null ? hostname.trim() : "");
            List<Row> covering = new ArrayList<>();
            if (needle.isEmpty()) {
                return covering;
            }
            for (Row domain : this.domains) {
                Row site = this.siteOf(domain);
                if (site == null || site.get(SiteModel.DELETED_AT) != null) {
                    continue;
                }
                if (HostnamePatterns.covers(domain.get(SiteDomainModel.HOSTNAME),
                        domain.get(SiteDomainModel.MATCH_TYPE), needle)) {
                    covering.add(domain);
                }
            }
            return covering;
        }

        /**
         * The covering rows that DECIDE the name: the most specific tier among
         * {@link #covering}, which is the tier the dispatcher would route the name to.
         *
         * AIDEV-NOTE: specificity is the routing order and nothing finer -- an exact row
         * beats every wildcard, and among wildcards the one spelling MORE labels wins
         * ({@code *.a.example.com} over {@code *.example.com}). Two wildcards with the same
         * label count (an in-label glob beside a leading one) tie and decide TOGETHER, which
         * fails closed for a tenant. Regex rows never cover (HostnamePatterns.covers), so
         * they never decide either.
         *
         * @return the deciding rows, empty when nothing covers the name
         */
        public @NonNull List<Row> deciding(@Nullable String hostname) {
            List<Row> deciding = new ArrayList<>();
            int best = Integer.MIN_VALUE;
            for (Row domain : this.covering(hostname)) {
                int specificity = specificityOf(domain);
                if (specificity > best) {
                    deciding.clear();
                    best = specificity;
                }
                if (specificity == best) {
                    deciding.add(domain);
                }
            }
            return deciding;
        }

        /** An exact row outranks every wildcard; a wildcard ranks by the labels it spells. */
        private static int specificityOf(@NonNull Row domain) {
            return HostnameAuthority.specificityOf(domain.get(SiteDomainModel.HOSTNAME),
                domain.get(SiteDomainModel.MATCH_TYPE));
        }
    }

    /**
     * THE routing-specificity rank of a configured hostname: an exact row outranks every
     * wildcard, and a wildcard ranks by the labels it spells.
     *
     * AIDEV-NOTE: this is the ONE spelling of the tier order, shared by {@link
     * Snapshot#deciding} (which decides who answers for a name) and by the write-time
     * overlap scan in {@code SiteDomainResource} (which decides whether a foreign row may
     * refuse a claim). A regex pattern's reach is undecidable here, so it ranks with an
     * exact row and is therefore never LESS specific than anything -- the fail-closed
     * answer. Regex rows never cover a name ({@code HostnamePatterns.covers}), so they
     * never reach {@code deciding} and this costs that lane nothing.
     *
     * @return a comparable rank; higher is more specific
     */
    public static int specificityOf(@Nullable String hostname, @Nullable String matchType) {
        String pattern = SiteDomainModel.canonicalHostname(hostname, matchType);
        if (pattern == null) {
            return Integer.MIN_VALUE;
        }
        String kind = HostnamePatterns.effectiveKind(pattern, matchType);
        if (SiteDomainModel.MATCH_EXACT.equals(kind) || SiteDomainModel.MATCH_REGEX.equals(kind)) {
            return Integer.MAX_VALUE;
        }
        return pattern.split("\\.", -1).length;
    }

    /**
     * Whether the context answers for a hostname: at least one live domain row must cover it
     * (this installation has to serve the name at all) and the context must hold
     * {@link HohenheimAccess#MANAGE} on the site of EVERY row that {@link Snapshot#deciding
     * decides} it -- the most specific covering tier, as routing resolves it.
     *
     * @return false for an unserved name, so it fails closed on absence
     */
    public static boolean canManage(@NonNull AccessContext ctx, @Nullable String hostname) {
        return canManage(Snapshot.load(), ctx, hostname);
    }

    /** Snapshot-reusing variant for callers deciding several names at once. */
    public static boolean canManage(@NonNull Snapshot snapshot, @NonNull AccessContext ctx,
                                    @Nullable String hostname) {
        if (HohenheimAccess.isAdmin(ctx)) {
            return true;
        }
        List<Row> deciding = snapshot.deciding(hostname);
        if (deciding.isEmpty()) {
            return false;
        }
        for (Row domain : deciding) {
            Integer siteId = domain.get(SiteDomainModel.SITE_ID);
            if (siteId == null || !HohenheimAccess.canManageSite(ctx, siteId)) {
                return false;
            }
        }
        return true;
    }
}
