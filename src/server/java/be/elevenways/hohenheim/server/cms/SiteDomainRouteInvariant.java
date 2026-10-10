package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.model.ReleasedRouteClaimModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.StoredRows;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.auth.HostnameAuthority;
import be.elevenways.hohenheim.server.proxy.HostnamePatterns;
import be.elevenways.hohenheim.server.proxy.ListenerAddressMatcher;
import be.elevenways.hohenheim.server.proxy.ReleasedClaims;
import be.elevenways.hohenheim.server.proxy.RouteClaims;
import be.elevenways.hohenheim.server.proxy.SiteDispatcher;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static be.elevenways.hohenheim.RawValues.trimmed;

/**
 * THE domain route invariant on the SiteDomainModel write pipeline, and the site-enable side of the same route check.
 *
 * AIDEV-NOTE: a schema hook, not a resource part: every writer of a domain row passes it, whatever surface asked.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class SiteDomainRouteInvariant {

    private SiteDomainRouteInvariant() {
    }

    private static volatile boolean routeInvariantInstalled;

    /**
     * Install THE domain route invariant on the SiteDomainModel write pipeline: path
     * canonicalization, the hostname/route refusal and the live-route claim stamp all run
     * for every writer, not just for a CMS form submit.
     *
     * AIDEV-NOTE: this MUST live in the write pipeline, never in the resource layer --
     * the same reasoning as SiteEnableInvariant.install, which this mirrors.
     * These checks used to sit in the domain resource's row writes, where the ONLY thing keeping a
     * bypass hypothetical was that SiteDomainModel happens not to be revisionable today:
     * making it revisionable, or adding any second writer (a seeder, an import, an API
     * writeback, a site-scoped bulk edit, the clone action's direct model.save), would
     * silently reopen hostname takeover. A resource-layer invariant IS the bypass.
     * Do NOT move any of this back into a resource's writes part as a per-path check.
     *
     * The refusal lives on the beforeVALIDATE tier and the claim stamp on the beforeWRITE
     * tier of the SAME Schema.beforeWrite pass, and BOTH run inside the one write
     * transaction SiteDomainModel.save declares. That is deliberate: the serialized scan
     * is the authoritative refusal (it alone can judge listener-set OVERLAP, which no
     * unique key can spell -- see RouteClaims), and the claim stamp feeds the unique
     * index that refuses identical keys as the storage-level backstop.
     */
    public static synchronized void installRouteInvariant() {
        if (routeInvariantInstalled) {
            return;
        }
        routeInvariantInstalled = true;
        SiteDomainModel.SCHEMA.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row != null) {
                canonicalizePath(row);
                refuseRouteConflicts(row);
            }
        });
        SiteDomainModel.SCHEMA.addBeforeWriteHook(context -> {
            Row row = context.getRow();
            if (row == null) {
                return;
            }
            Row stored = StoredRows.of(Models.get(SiteDomainModel.class), row);
            Integer siteId = row.afterWrite(SiteDomainModel.SITE_ID, stored);
            Row site = Models.get(SiteModel.class).findById(siteId);
            String key = RouteClaims.isLive(site) ? RouteClaims.keyOfPendingWrite(row, stored) : null;
            // RELEASE PATH 2 of 3: editing the hostname, path or listener set of a LIVE row
            // frees the departing key with nothing else observing it -- the site write hook
            // never runs for a domain-only edit. The stored row is still readable here, and
            // (unlike a site delete) its grants are untouched, so the owner set is exact.
            if (stored != null && !Objects.equals(key, stored.get(SiteDomainModel.LIVE_ROUTE_KEY))) {
                ReleasedClaims.recordReleaseOf(stored);
            }
            row.set(SiteDomainModel.LIVE_ROUTE_KEY, key);
        });
        // RELEASE PATH 3 of 3: deleting the domain row itself. The domain resources' row delete
        // is the plain model.delete(), which runs no write hook at all --
        // and abandoning one hostname while keeping the site is the most ordinary way a
        // tenant releases a name. A ledger written at only SOME release points is worse
        // than none: it would quarantine some hostnames and silently free others.
        SiteDomainModel.SCHEMA.addBeforeRemoveHook(ReleasedClaims::recordReleaseOfDoomedRows);
    }

    /**
     * Store the path exactly as the dispatcher will route it, so a stored row cannot
     * spell a route differently than it resolves ("app" -> "/app", "/app/" -> "/app",
     * "/" -> null catch-all). An absent key is untouched, for partial updates.
     */
    private static void canonicalizePath(@NonNull Row row) {
        if (!row.has(SiteDomainModel.PATH.getName())) {
            return;
        }
        Object raw = row.get(SiteDomainModel.PATH);
        String canonical = SiteDispatcher.normalizeRoutePath(raw != null ? String.valueOf(raw) : null);
        if (!Objects.equals(raw, canonical)) {
            row.set(SiteDomainModel.PATH, canonical);
        }
    }

    /** Hostname required, a site required, and the route unclaimed. */
    private static void refuseRouteConflicts(@NonNull Row row) {
        Row stored = StoredRows.of(Models.get(SiteDomainModel.class), row);
        Object hostnameValue = row.afterWrite(SiteDomainModel.HOSTNAME, stored);
        String hostname = trimmed(hostnameValue);
        if (hostname.isEmpty()) {
            throw Violations.ofField("hostname", hostname, HohenheimMicrocopy.VIOLATIONS.of("hostname_required"));
        }
        Object siteIdValue = row.afterWrite(SiteDomainModel.SITE_ID, stored);
        if (!(siteIdValue instanceof Integer siteId)) {
            throw Violations.ofField("site_id", siteIdValue, HohenheimMicrocopy.VIOLATIONS.of("site_required"));
        }
        Row site = Models.get(SiteModel.class).findById(siteId);
        // Uniqueness compares CANONICAL route components: the hostname as the model hook
        // stores it (SiteDomainModel.canonicalHostname -- ONE definition), the path as
        // the dispatcher routes it (normalizeRoutePath), and listener restrictions as
        // ListenerAddressMatcher parses them. Two rows conflict when their listener sets
        // can OVERLAP (an empty set means every address); disjoint sets are genuinely
        // distinct routes. Match type is deliberately NOT part of the identity: an exact
        // and a wildcard row with the same literal hostname shadow each other in the
        // tiered lookup, which is a config mistake worth refusing, not two routes.
        //
        // AIDEV-NOTE: hostname conflict is a question about hostname SETS INTERSECTING
        // (HostnamePatterns), and the rule is OWNER-SCOPED -- never plain string equality.
        // An exact host falling under another row's wildcard is one contested host even
        // though the two spell different claim keys, and the dispatcher consults the exact
        // tier BEFORE the wildcard tier, so the exact row silently seizes traffic the
        // wildcard row was serving. Scoping: serving *.example.com broadly and carving
        // foo.example.com out to a different upstream is a legitimate, desirable
        // configuration -- and it NEEDS two sites, since an upstream is a site-level
        // setting, so the ownership unit here cannot be the site id. It is the site's
        // MANAGE-grant subjects (HohenheimAccess.sameOwner): operator-owned sites all
        // compare equal, and the moment a tenant is involved the same shape becomes a
        // takeover and is refused. IDENTICAL hostnames stay refused in every direction --
        // same-site duplicates are a config mistake and spell the same claim key anyway.
        // Do not "simplify" this back to Objects.equals; that is the defect it closes.
        //
        // The check is GLOBAL, not per-site: the dispatcher's route table spans every
        // enabled site and silently drops the loser of a duplicate claim (first-wins by
        // site name), so a same-route row on ANOTHER site is exactly as broken as one on
        // this site. Rows of DISABLED other sites (clones, staged drafts) are exempt --
        // they hold no routes; the conflict is refused again on the site-enable edit.
        String matchType = Objects.toString(row.afterWrite(SiteDomainModel.MATCH_TYPE, stored), null);
        String canonicalHostname = SiteDomainModel.canonicalHostname(hostname, matchType);
        String path = normalizedPath(row.afterWrite(SiteDomainModel.PATH, stored));
        List<String> listenOn = ListenerAddressMatcher.parse(
            Objects.toString(row.afterWrite(SiteDomainModel.LISTEN_ON, stored), null));
        Object ownId = row.has(SiteDomainModel.ID.getName()) ? row.get(SiteDomainModel.ID) : null;
        boolean introducesClaim = introducesClaim(stored, row, canonicalHostname, matchType);

        Map<Integer, Row> sitesById = new HashMap<>();
        for (Row candidateSite : Models.get(SiteModel.class).find().all()) {
            sitesById.put(candidateSite.get(SiteModel.ID), candidateSite);
        }
        // A row on a site that does not ROUTE holds no routes, in either direction:
        // staging a duplicate on a draft/clone site is legal, and only the site-enable
        // edit re-judges it against the live table. A soft-deleted site is exactly as
        // routeless as a disabled one -- RouteClaims.isLive is the ONE definition, so a
        // deleted site can never keep a hostname hostage. Same-site duplicates are
        // always a config mistake, live or not.
        boolean ownSiteLive = RouteClaims.isLive(site);

        for (Row candidate : Models.get(SiteDomainModel.class).find().all()) {
            if (ownId != null && ownId.equals(candidate.get(SiteDomainModel.ID))) {
                continue;
            }
            Integer candidateSiteId = candidate.get(SiteDomainModel.SITE_ID);
            boolean sameSite = Objects.equals(candidateSiteId, siteId);
            Row candidateSite = sitesById.get(candidateSiteId);
            if (!sameSite && (!RouteClaims.isLive(candidateSite) || !ownSiteLive)) {
                continue;
            }
            String candidateHostname = SiteDomainModel.canonicalHostname(
                candidate.get(SiteDomainModel.HOSTNAME), candidate.get(SiteDomainModel.MATCH_TYPE));
            if (!Objects.equals(path, normalizedPath(candidate.get(SiteDomainModel.PATH)))
                || !listenersOverlap(listenOn,
                    ListenerAddressMatcher.parse(candidate.get(SiteDomainModel.LISTEN_ON)))) {
                continue;
            }
            boolean identical = Objects.equals(canonicalHostname, candidateHostname);
            if (!identical) {
                if (sameSite || candidateSiteId == null
                    || !HostnamePatterns.intersect(canonicalHostname, matchType,
                        candidate.get(SiteDomainModel.HOSTNAME),
                        candidate.get(SiteDomainModel.MATCH_TYPE))
                    || HohenheimAccess.sameOwner(siteId, candidateSiteId)
                    || !overlapRefuses(canonicalHostname, matchType, candidate, introducesClaim)) {
                    continue;
                }
                // AIDEV-NOTE: the holder's pattern and name reach only a reader who may
                // manage the holder (ClaimRefusals); a tenant sees the neutral sentence,
                // identical to the identical-route case below, so probing this form can
                // never enumerate the installation's hostnames or site names.
                throw Violations.ofField("hostname", hostname,
                    ClaimRefusals.heldBy(candidateSiteId, candidateSite,
                        holder -> HohenheimMicrocopy.VIOLATIONS.of("route_overlaps_other_site")
                            .withArg("hostname", String.valueOf(candidateHostname))
                            .withArg("site", holder),
                        HohenheimMicrocopy.VIOLATIONS.of(HostnameAuthority.HOSTNAME_UNAVAILABLE)));
            }
            if (!sameSite) {
                throw Violations.ofField(path == null ? "hostname" : "path",
                    path == null ? hostname : path,
                    ClaimRefusals.heldBy(candidateSiteId, candidateSite,
                        holder -> HohenheimMicrocopy.VIOLATIONS.of("route_taken_other_site")
                            .withArg("site", holder),
                        HohenheimMicrocopy.VIOLATIONS.of(HostnameAuthority.HOSTNAME_UNAVAILABLE)));
            }
            if (path == null) {
                throw Violations.ofField("hostname", hostname, HohenheimMicrocopy.VIOLATIONS.of("hostname_taken"));
            }
            throw Violations.ofField("path", path, HohenheimMicrocopy.VIOLATIONS.of("route_taken"));
        }

        // The QUARANTINE tier, last: a live holder is the more actionable refusal and keeps
        // naming itself, so this only speaks when the route is genuinely unheld. It answers
        // from an INDEXED lookup on the claim key first, and judges hostname-set overlap
        // against the active ledger rows only if that let the claim through (a wildcard
        // swallows a released exact host while spelling a different key -- see
        // ReleasedClaims). Rows of a site that does not route are exempt here exactly like
        // they are above -- staging is legal, and the enable seam re-judges
        // (refuseEnableRouteConflicts), which is what closes the
        // stage-on-a-disabled-site-then-enable two-step.
        if (ownSiteLive) {
            Row quarantine = ReleasedClaims.refusalFor(RouteClaims.keyOfPendingWrite(row, stored),
                matchType, siteId);
            if (quarantine != null) {
                throw Violations.ofField("hostname", hostname, quarantineViolation(quarantine,
                    "route_quarantined"));
            }
        }
    }

    /**
     * THE overlap rule, shared by create, update and the site-enable seam: whether a
     * foreign row whose hostname set merely INTERSECTS this one may refuse the write.
     *
     * An overlap with a LESS SPECIFIC foreign row (a wildcard covering an exact host, a
     * broader wildcard covering a narrower one) is a takeover only while the write
     * INTRODUCES the claim. Routing already gives the more specific row precedence
     * ({@link HostnameAuthority#specificityOf}, the same rank {@code Snapshot.deciding}
     * uses), so a claim that already exists is decided by its own row and re-refusing it
     * would only lock its owner out of every later edit -- which is exactly what happened
     * to a tenant's exact row the moment an operator added a catch-all wildcard over it.
     * An IDENTICAL claim never reaches here, and a MORE (or equally) specific foreign row
     * still refuses in every direction, so staging a name on a routeless site and enabling
     * it stays closed: those rows hold no claim yet.
     *
     * @param introducesClaim whether the write hands the row a claim it does not hold live
     */
    private static boolean overlapRefuses(@Nullable String canonicalHostname,
                                          @Nullable String matchType,
                                          @NonNull Row candidate, boolean introducesClaim) {
        if (introducesClaim) {
            return true;
        }
        return HostnameAuthority.specificityOf(candidate.get(SiteDomainModel.HOSTNAME),
            candidate.get(SiteDomainModel.MATCH_TYPE))
            >= HostnameAuthority.specificityOf(canonicalHostname, matchType);
    }

    /**
     * Whether a pending write hands the row a route claim it does not already hold LIVE:
     * a create, a changed route tuple, a changed routing tier, or a row whose site is not
     * routing (its {@code live_route_key} is null, which is what the enable seam sees).
     *
     * AIDEV-NOTE: the claim key deliberately omits the match type (see RouteClaims), so
     * the TIER is compared separately -- flipping one literal hostname from exact to
     * wildcard spells the same key while claiming a whole namespace, and reading the key
     * alone would wave that through as "unchanged".
     */
    private static boolean introducesClaim(@Nullable Row stored, @NonNull Row row,
                                           @Nullable String canonicalHostname,
                                           @Nullable String matchType) {
        if (stored == null) {
            return true;
        }
        if (!RouteClaims.keyOfPendingWrite(row, stored)
                .equals(stored.get(SiteDomainModel.LIVE_ROUTE_KEY))) {
            return true;
        }
        String storedHostname = stored.get(SiteDomainModel.HOSTNAME);
        String storedMatchType = stored.get(SiteDomainModel.MATCH_TYPE);
        return !HostnamePatterns.effectiveKind(canonicalHostname, matchType).equals(
            HostnamePatterns.effectiveKind(
                SiteDomainModel.canonicalHostname(storedHostname, storedMatchType),
                storedMatchType));
    }

    /**
     * The quarantine refusal text.
     *
     * AIDEV-NOTE: it must NOT name the former owner. The refusals above name the holding
     * SITE because that site is live, visible and actionable for the operator reading the
     * message; a released claim's former owner is typically a DELETED tenant, and naming it
     * would leak one tenant's identity to the next -- a tenancy boundary none of the
     * existing refusals cross. Copying one of those messages here is the single most likely
     * way this leaks.
     */
    private static @NonNull Microcopy quarantineViolation(@NonNull Row quarantine,
                                                          @NonNull String key) {
        return HohenheimMicrocopy.VIOLATIONS.of(key)
            .withArg("hostname", String.valueOf(quarantine.get(ReleasedRouteClaimModel.HOSTNAME)))
            .withArg("days", String.valueOf(ReleasedClaims.remainingDays(quarantine)));
    }

    /**
     * The site-ENABLE side of the global route check: rows of a disabled site are
     * exempt while it stays disabled, so enabling it must re-run the comparison
     * against every other enabled site's rows.
     *
     * @param goLive how the site enters the route table, which words the refusal
     * @throws Violations anchored on {@code enabled} naming the conflicting site
     */
    static void refuseEnableRouteConflicts(int siteId, @NonNull SiteGoLive goLive) {
        Map<Integer, Row> sitesById = new HashMap<>();
        for (Row site : Models.get(SiteModel.class).find().all()) {
            sitesById.put(site.get(SiteModel.ID), site);
        }
        List<Row> allRows = Models.get(SiteDomainModel.class).find().all();
        for (Row own : allRows) {
            if (!Objects.equals(own.get(SiteDomainModel.SITE_ID), siteId)) {
                continue;
            }
            String ownHostname = SiteDomainModel.canonicalHostname(
                own.get(SiteDomainModel.HOSTNAME), own.get(SiteDomainModel.MATCH_TYPE));
            String ownMatchType = Objects.toString(own.get(SiteDomainModel.MATCH_TYPE), null);
            String ownPath = normalizedPath(own.get(SiteDomainModel.PATH));
            List<String> ownListen = ListenerAddressMatcher.parse(own.get(SiteDomainModel.LISTEN_ON));
            // The stored row IS the pending write here, so this asks whether the row
            // already holds its claim live -- false for every row of a site that is going
            // live, which is what keeps the stage-then-enable two-step refused.
            boolean introducesClaim = introducesClaim(own, own, ownHostname, ownMatchType);
            for (Row candidate : allRows) {
                Integer candidateSiteId = candidate.get(SiteDomainModel.SITE_ID);
                if (Objects.equals(candidateSiteId, siteId)) {
                    continue;
                }
                // RouteClaims.isLive, not a bare enabled check: a soft-deleted site keeps
                // enabled=true (deleteRow only stamps deleted_at), so an enabled-only
                // test let a DELETED site hold its hostname hostage forever, refusing
                // every later claimant in the name of a site that appears in no UI.
                Row candidateSite = sitesById.get(candidateSiteId);
                if (!RouteClaims.isLive(candidateSite)) {
                    continue;
                }
                String candidateHostname = SiteDomainModel.canonicalHostname(
                    candidate.get(SiteDomainModel.HOSTNAME), candidate.get(SiteDomainModel.MATCH_TYPE));
                if (!Objects.equals(ownPath, normalizedPath(candidate.get(SiteDomainModel.PATH)))
                    || !listenersOverlap(ownListen,
                        ListenerAddressMatcher.parse(candidate.get(SiteDomainModel.LISTEN_ON)))) {
                    continue;
                }
                // Every candidate here is on ANOTHER site, so a hostname-set overlap is a
                // takeover unless both sites answer to the same owner -- the same
                // owner-scoped rule refuseRouteConflicts documents.
                boolean identical = Objects.equals(ownHostname, candidateHostname);
                if (!identical && (!HostnamePatterns.intersect(
                        own.get(SiteDomainModel.HOSTNAME), ownMatchType,
                        candidate.get(SiteDomainModel.HOSTNAME),
                        candidate.get(SiteDomainModel.MATCH_TYPE))
                    || candidateSiteId == null
                    || HohenheimAccess.sameOwner(siteId, candidateSiteId)
                    || !overlapRefuses(ownHostname, ownMatchType, candidate, introducesClaim))) {
                    continue;
                }
                String ownName = String.valueOf(own.get(SiteDomainModel.HOSTNAME));
                throw Violations.ofField("enabled", true,
                    ClaimRefusals.heldBy(candidateSiteId, candidateSite,
                        site -> HohenheimMicrocopy.VIOLATIONS.of(
                                identical ? goLive.routeConflictKey() : goLive.routeOverlapKey())
                            .withArg("hostname", ownName)
                            .withArg("pattern", String.valueOf(candidateHostname))
                            .withArg("site", site),
                        HohenheimMicrocopy.VIOLATIONS.of(goLive.hostnameUnavailableKey())
                            .withArg("hostname", ownName)));
            }

            // The quarantine tier of the ENABLE seam. Omitting it here would leave the
            // whole mechanism bypassable by a two-step the code above documents as LEGAL:
            // stage the released hostname on a DISABLED site (exempt by design), then
            // enable it. Anchored on 'enabled', like every other refusal on this path.
            Row quarantine = ReleasedClaims.refusalFor(RouteClaims.keyOf(own), ownMatchType, siteId);
            if (quarantine != null) {
                throw Violations.ofField("enabled", true,
                    quarantineViolation(quarantine, goLive.routeQuarantinedKey()));
            }
        }
    }

    /** THE overlap rule lives with the matcher, so the quarantine judges it identically. */
    private static boolean listenersOverlap(@NonNull List<String> first, @NonNull List<String> second) {
        return ListenerAddressMatcher.overlap(first, second);
    }

    /** Canonical route path for uniqueness, delegated to the routing authority. */
    private static @Nullable String normalizedPath(@Nullable Object value) {
        return SiteDispatcher.normalizeRoutePath(value != null ? String.valueOf(value) : null);
    }

}
