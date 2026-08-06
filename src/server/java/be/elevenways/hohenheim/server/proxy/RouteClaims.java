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
 * AIDEV-NOTE: route ownership is a transactionally SERIALIZED claim registry. Every
 * claim-writing path runs its conflict scan and its claim write inside ONE write
 * transaction (SiteModel.save and SiteDomainModel.save declare the wrap; restamp runs
 * inside the site save's transaction), and hohenheim's engine -- SQLite only, enforced by
 * HohenheimDatabase.requireSqlite, BEGIN IMMEDIATE single-writer since the datasource
 * rework -- serializes write transactions, so a scan can never go stale between read and
 * write. That serialization is what refuses OVERLAPPING claims, in BOTH dimensions a key
 * cannot spell: listener sets (an empty restriction listens everywhere) and hostname sets
 * (an exact host falling under another row's wildcard -- see HostnamePatterns). Overlap-
 * but-not-equal claims spell DIFFERENT keys, so no unique index can refuse them, only the
 * serialized scan can; that is the WHOLE guarantee, and it is why the scan may never move
 * out of the write transaction. The UNIQUE index on this
 * column (M045_SiteDomainRouteClaims) stays as the equality backstop: identical keys are
 * refused by storage itself even if a future writer bypasses the transaction discipline.
 * Route-claim transactions must stay well under SQLite's 5s busy_timeout or queued rivals
 * fail with SQLITE_BUSY. The scans also produce the specific, localized refusal an
 * operator can act on: they are diagnosis AND, under serialization, the overlap guarantee.
 *
 * AIDEV-NOTE: the port ledger (PortLedger/port_allocations) deliberately did NOT reuse
 * this claim-key-column shape: a managed-process port is owned by a process INSTANCE,
 * not a record, so there is no row to hang the column on -- the second spelling of an
 * exclusive claim in this repo is a decided fork, not drift.
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
     *
     * AIDEV-NOTE: this key spells the LITERAL hostname, so it can only ever arbitrate
     * IDENTICAL routes. Rows whose hostname sets merely INTERSECT (foo.example.com under
     * *.example.com) spell different keys on purpose -- they are refused by the serialized
     * conflict scan in SiteDomainResource, never by this key or its unique index.
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
            // RELEASE PATH 1 of 3 (the other two are in SiteDomainResource): the site stops
            // routing -- soft delete, disable, or a hard delete cascading through.
            // AIDEV-NOTE: the ledger write rides THIS hook (beforeWrite) and may never move
            // to afterSave: zenit-auth's RecordGrantCleanup revokes the deleted site's
            // grants from an afterSave hook, so an afterSave writer would capture an EMPTY
            // owner set and quarantine nothing. See ReleasedClaims. Rows already holding no
            // claim record nothing, which is what makes a re-save of an already-trashed row
            // (which re-fires grant cleanup) incapable of overwriting the real owner.
            ReleasedClaims.recordReleaseOfSite(siteId);
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
        return partOf(key, 0);
    }

    /**
     * The canonical route path a claim key was built from, null for the catch-all -- so a
     * released claim can be compared to a pending one on the two components that are not
     * the hostname (the hostname itself is a SET question, see HostnamePatterns).
     */
    public static @Nullable String pathOf(@NonNull String key) {
        String path = partOf(key, 1);
        return path.isEmpty() ? null : path;
    }

    /** The listener restriction a claim key was built from; empty means every address. */
    public static @NonNull List<String> listenersOf(@NonNull String key) {
        return ListenerAddressMatcher.parse(partOf(key, 2));
    }

    private static @NonNull String partOf(@NonNull String key, int index) {
        String[] parts = key.split(SEPARATOR, -1);
        return index < parts.length ? parts[index] : "";
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
