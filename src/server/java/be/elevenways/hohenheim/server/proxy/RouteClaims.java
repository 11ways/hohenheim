package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.DuplicateKeyException;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * THE live-route claim: the derived key a domain row holds in
 * {@link SiteDomainModel#LIVE_ROUTE_KEY} while its site actually routes traffic, and the
 * stamp/release pass that keeps that column honest.
 *
 * AIDEV-NOTE: the column carries a UNIQUE index (M045_SiteDomainRouteClaims), so this is
 * the only route-ownership check that survives concurrency. Every app-level scan in the
 * CMS reads first and writes second, which means two tenants enabling staged sites on one
 * hostname in the same instant both pass their scan; the index refuses the loser. The
 * scans stay because they produce the specific, localized refusal an operator can act on;
 * they are the diagnosis, this is the guarantee.
 *
 * Residual gap, deliberately accepted: the key holds the listener restriction LITERALLY,
 * while route identity treats listener SETS as conflicting when they OVERLAP (an empty
 * restriction listens everywhere). An all-interfaces row and a single-address row on the
 * same hostname therefore hold DIFFERENT keys and both fit in the index, even though the
 * dispatcher considers them one route. Set overlap is not expressible as a unique index;
 * the app-level scan still refuses that pair whenever the two writes are not simultaneous.
 *
 * @author Jelle De Loecker
 */
public final class RouteClaims {

    /**
     * Field separator inside the key. A newline can occur in no part of a route tuple
     * (hostnames, paths and listener lists are all single-line), so the parts can never
     * be re-cut into a different tuple that renders as the same key.
     */
    private static final String SEPARATOR = "\n";

    private RouteClaims() {
    }

    /**
     * A claim the unique index refused, carrying the contested key -- the driver
     * exception names only the column, and a refusal that cannot name the hostname and
     * the winning site is not a refusal an operator can act on.
     */
    public static final class ClaimConflict extends RuntimeException {

        private final @NonNull String key;

        ClaimConflict(@NonNull String key, @NonNull Throwable cause) {
            super("The live route " + key.replace(SEPARATOR, " | ") + " is already claimed", cause);
            this.key = key;
        }

        public @NonNull String getKey() {
            return this.key;
        }
    }

    /** @return true when this site's domain rows are in the dispatcher's live route table */
    public static boolean isLive(@Nullable Row site) {
        return site != null
            && Boolean.TRUE.equals(site.get(SiteModel.ENABLED))
            && site.get(SiteModel.DELETED_AT) == null;
    }

    /**
     * THE claim key: the route tuple spelled exactly as the dispatcher resolves it --
     * hostname per {@link SiteDomainModel#canonicalHostname}, path per
     * {@link SiteDispatcher#normalizeRoutePath}, listeners per
     * {@link ListenerAddressMatcher#parse}. Match type is deliberately absent: an exact
     * and a wildcard row spelling the same literal hostname shadow each other in the
     * tiered lookup, so they are one contested route, not two.
     */
    public static @NonNull String keyOf(@Nullable Object hostname, @Nullable Object matchType,
                                        @Nullable Object path, @Nullable Object listenOn) {
        String canonicalHostname = SiteDomainModel.canonicalHostname(
            hostname != null ? String.valueOf(hostname) : null,
            matchType != null ? String.valueOf(matchType) : null);
        String canonicalPath = SiteDispatcher.normalizeRoutePath(
            path != null ? String.valueOf(path) : null);
        List<String> listeners = ListenerAddressMatcher.parse(
            listenOn != null ? String.valueOf(listenOn) : null);
        return (canonicalHostname != null ? canonicalHostname : "")
            + SEPARATOR + (canonicalPath != null ? canonicalPath : "")
            + SEPARATOR + String.join(",", listeners);
    }

    /** The claim key of a FULLY loaded domain row. */
    public static @NonNull String keyOf(@NonNull Row domain) {
        return keyOf(domain.get(SiteDomainModel.HOSTNAME), domain.get(SiteDomainModel.MATCH_TYPE),
            domain.get(SiteDomainModel.PATH), domain.get(SiteDomainModel.LISTEN_ON));
    }

    /**
     * The claim key of a row about to be written, reading through
     * {@link SiteDomainModel#effective} so a partial CMS update is judged on the values it
     * will actually end up with.
     */
    public static @NonNull String keyOfPendingWrite(@NonNull Row domain) {
        return keyOf(SiteDomainModel.effective(domain, SiteDomainModel.HOSTNAME),
            SiteDomainModel.effective(domain, SiteDomainModel.MATCH_TYPE),
            SiteDomainModel.effective(domain, SiteDomainModel.PATH),
            SiteDomainModel.effective(domain, SiteDomainModel.LISTEN_ON));
    }

    /**
     * Claim or release every route of one site, as the atomic set-based update -- NEVER
     * {@code model.save}, which would re-enter the domain write hook and recompute the key
     * from the site's not-yet-written state.
     *
     * @param siteId The site whose domain rows are being restamped
     * @param live Whether the site will route traffic after the write that triggered this
     * @throws ClaimConflict when another live site already holds one of the routes
     */
    public static void restamp(int siteId, boolean live) {
        Model domains = Models.get(SiteDomainModel.class);
        if (!live) {
            domains.find().where(SiteDomainModel.SITE_ID.eq(siteId))
                .assign(SiteDomainModel.LIVE_ROUTE_KEY, null)
                .updateAll();
            return;
        }
        List<Row> rows = domains.find().where(SiteDomainModel.SITE_ID.eq(siteId))
            .orderBy(SiteDomainModel.ID, SortOrder.ASC).all();
        Set<String> claimed = new HashSet<>();
        Map<Integer, String> targets = new HashMap<>();
        for (Row domain : rows) {
            String key = keyOf(domain);
            // Lowest id wins a route contested WITHIN one site, exactly like the migration
            // heal: a same-site duplicate must not lock the site out of every future save.
            targets.put(domain.get(SiteDomainModel.ID), claimed.add(key) ? key : null);
        }
        // Release before claiming, in two passes: a site that SWAPS two hostnames would
        // otherwise collide with its own outgoing key halfway through the rewrite.
        for (Row domain : rows) {
            Integer id = domain.get(SiteDomainModel.ID);
            if (!Objects.equals(targets.get(id), domain.get(SiteDomainModel.LIVE_ROUTE_KEY))) {
                write(domains, id, null);
            }
        }
        for (Row domain : rows) {
            Integer id = domain.get(SiteDomainModel.ID);
            String target = targets.get(id);
            if (target != null && !Objects.equals(target, domain.get(SiteDomainModel.LIVE_ROUTE_KEY))) {
                write(domains, id, target);
            }
        }
    }

    private static void write(@NonNull Model domains, @NonNull Integer domainId, @Nullable String key) {
        try {
            domains.find().where(SiteDomainModel.ID.eq(domainId))
                .assign(SiteDomainModel.LIVE_ROUTE_KEY, key)
                .updateAll();
        } catch (DuplicateKeyException conflict) {
            throw new ClaimConflict(key != null ? key : "", conflict);
        }
    }

    /**
     * The site NAME currently holding a claim key, for the refusal message.
     *
     * @return the owning site's name, or null when the winner vanished between the
     *         refusal and this lookup
     */
    public static @Nullable String holderNameOf(@NonNull String key) {
        Row domain = Models.get(SiteDomainModel.class).find()
            .where(SiteDomainModel.LIVE_ROUTE_KEY.eq(key)).first();
        if (domain == null) {
            return null;
        }
        Row site = Models.get(SiteModel.class).findById(domain.get(SiteDomainModel.SITE_ID));
        return site != null ? String.valueOf(site.get(SiteModel.NAME)) : null;
    }

    /** The hostname a claim key was built from, for the refusal message. */
    public static @NonNull String hostnameOf(@NonNull String key) {
        int separator = key.indexOf(SEPARATOR);
        return separator < 0 ? key : key.substring(0, separator);
    }

    /**
     * Stamp the claims of every live site's domain rows against an EXPLICIT datasource,
     * for the migration backfill: the lowest-id row of a contested route keeps the claim
     * and the losers stay unclaimed.
     *
     * @return the number of rows left unclaimed because an earlier row already held their route
     */
    public static int backfill(@NonNull Datasource datasource) {
        // Fresh instances, not Models.get: a migration may run long before the model
        // singletons are registered (the zenit-auth M007 heal shape).
        Model sites = new SiteModel();
        Model domains = new SiteDomainModel();
        Map<Integer, Row> sitesById = new HashMap<>();
        for (Row site : sites.find().on(datasource).all()) {
            sitesById.put(site.get(SiteModel.ID), site);
        }
        Set<String> claimed = new HashSet<>();
        int released = 0;
        for (Row domain : domains.find().on(datasource)
                .orderBy(SiteDomainModel.ID, SortOrder.ASC).all()) {
            if (!isLive(sitesById.get(domain.get(SiteDomainModel.SITE_ID)))) {
                continue;
            }
            String key = keyOf(domain);
            if (!claimed.add(key)) {
                // The dispatcher already resolved this route FIRST-WINS, so the loser was
                // never reachable; leaving it unclaimed changes nothing an operator can
                // observe, and its next edit is refused with the real conflict message.
                released++;
                continue;
            }
            domains.find().on(datasource).where(SiteDomainModel.ID.eq(domain.get(SiteDomainModel.ID)))
                .assign(SiteDomainModel.LIVE_ROUTE_KEY, key)
                .updateAll();
        }
        return released;
    }
}
