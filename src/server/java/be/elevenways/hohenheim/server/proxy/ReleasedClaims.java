package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.ReleasedRouteClaimModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.zenit.auth.server.PermissionResolver;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.context.RemoveFromDatasource;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.QueryBuilder;
import be.elevenways.zenit.common.orm.query.QueryContext;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.security.Accountability;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * THE release quarantine: remembers which owner gave up a route claim, and refuses a
 * DIFFERENT owner's claim on the same route for {@code security.release_quarantine_days}.
 *
 * AIDEV-NOTE: this exists because a released hostname is not a free hostname. A tenant that
 * served shop.example.com through hohenheim points a CNAME at us from a zone WE DO NOT HOST;
 * deleting the site does not retract that CNAME. If the next claimant is somebody else, the
 * still-live CNAME delivers the first tenant's visitors to the second tenant's content --
 * ordinary subdomain takeover. Refusing the claim is the ONLY lever available, because the
 * dangling pointer is outside our control.
 *
 * AIDEV-NOTE: every ledger write MUST happen on a beforeWrite/beforeRemove tier, never
 * afterSave. zenit-auth's RecordGrantCleanup revokes a deleted record's grants from an
 * AFTER_SAVE hook (RecordGrantCleanup.java:69, fired by Schema at :707); the route restamp
 * is a beforeWrite hook (SiteResource.java:200, fired at :693). Writing the ledger after the
 * save therefore races grant cleanup on pure registration order, captures an EMPTY subject
 * set, and every released hostname looks operator-owned -- the quarantine then never fires
 * for the exact tenant case it exists for, with every test still green. A test that asserts
 * only the refusal cannot see this; assert the STORED subject set.
 *
 * AIDEV-NOTE: re-saving a still-trashed row RE-FIRES grant cleanup and a restore never
 * resurrects grants, so the owner set is only knowable on the FIRST release. {@link #record}
 * is therefore idempotent-on-first-write (an active row wins) and rows are INSERT-only --
 * there is no update path that could overwrite a real owner with an empty set.
 */
public final class ReleasedClaims {

    private ReleasedClaims() {
    }

    /** @return the configured quarantine window in days; 0 or less means disabled */
    public static int windowDays() {
        Integer days = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Security.RELEASE_QUARANTINE_DAYS);
        return days != null ? days : 0;
    }

    /**
     * Record that a domain row gave up the claim it was holding.
     *
     * Rows that hold NO claim record nothing: that is what makes every release path
     * idempotent on re-entry (a second save of an already-trashed site finds null keys) and
     * what keeps the three internal, same-owner releases out of the ledger -- RouteClaims'
     * intra-site swap and same-site duplicate loser both run on the claim-writing side, and
     * the migration backfill writes claims rather than releasing them.
     *
     * @param domain the STORED domain row as it was before the release
     */
    public static void recordReleaseOf(@Nullable Row domain) {
        if (domain == null) {
            return;
        }
        String key = domain.get(SiteDomainModel.LIVE_ROUTE_KEY);
        Integer siteId = domain.get(SiteDomainModel.SITE_ID);
        if (key == null || siteId == null) {
            return;
        }
        record(key, String.valueOf(domain.get(SiteDomainModel.HOSTNAME)), siteId);
    }

    /** Record the release of every claim currently held by one site's domain rows. */
    public static void recordReleaseOfSite(int siteId) {
        for (Row domain : Models.get(SiteDomainModel.class).find()
                .where(SiteDomainModel.SITE_ID.eq(siteId))
                .and(SiteDomainModel.LIVE_ROUTE_KEY.isNotNull()).all()) {
            recordReleaseOf(domain);
        }
    }

    /**
     * Record the release of every claim the rows a pending DELETE will remove are holding.
     *
     * AIDEV-NOTE: a remove hook fires ONCE for the whole delete with a criteria-only context
     * whose row is null (RemoveFromDatasource's docblock; PortLedger.captureDoomedOwners is
     * the in-repo reference), so the doomed rows are re-read from the criteria here. This is
     * a beforeRemove hook and needs no after-hook partner: it only READS what the delete is
     * about to remove, and its ledger insert rolls back with the delete's transaction.
     */
    public static void recordReleaseOfDoomedRows(@NonNull RemoveFromDatasource context) {
        Model model = context.getModel();
        if (model == null) {
            return;
        }
        QueryContext queryContext = context.getQueryContext();
        Criteria criteria = queryContext != null ? queryContext.getCriteria() : null;
        QueryBuilder<Row> builder = model.find();
        if (criteria != null) {
            builder.where(criteria);
        }
        for (Row domain : builder.all()) {
            recordReleaseOf(domain);
        }
    }

    /**
     * Write one ledger row, unless an ACTIVE quarantine already covers the key -- the
     * first release inside a window is the authoritative one.
     */
    private static void record(@NonNull String key, @NonNull String hostname, int siteId) {
        if (windowDays() <= 0) {
            // Quarantine disabled: recording releases nobody will ever read is not free,
            // and a re-enabled window must not suddenly quarantine on stale history.
            return;
        }
        if (activeClaimFor(key) != null) {
            return;
        }
        Set<String> subjects = HohenheimAccess.manageSubjectsOf(siteId);
        Model ledger = Models.get(ReleasedRouteClaimModel.class);
        Row row = ledger.createEmptyRow();
        row.set(ReleasedRouteClaimModel.CLAIM_KEY, key);
        row.set(ReleasedRouteClaimModel.HOSTNAME, hostname);
        row.set(ReleasedRouteClaimModel.FORMER_SITE_ID, siteId);
        // Unreadable grants become an UNMATCHABLE owner marker, never an empty set: an
        // empty set reads as "operator-owned" and would hand the hostname to anyone.
        row.set(ReleasedRouteClaimModel.FORMER_SUBJECTS,
            subjects != null ? join(subjects) : "unknown:unreadable");
        row.set(ReleasedRouteClaimModel.RELEASED_AT, Instant.now());
        ledger.save(row);
    }

    /**
     * The quarantine decision for a claim.
     *
     * AIDEV-NOTE: an EMPTY claimant set means "no manage grants YET", which is both the
     * operator case and a tenant whose fresh site has not been granted anything yet (a
     * site's grant is applied AFTER the record exists). It may therefore never satisfy
     * "different owner" on its own -- the ACTOR is checked against the former subjects too,
     * so a tenant re-claiming through a new site is allowed while a stranger is not. Without
     * that lane, tenant self-service site creation would lock every tenant out of its own
     * released hostnames.
     *
     * @return the ledger row refusing this claim, or null when the claim is allowed
     */
    public static @Nullable Row refusalFor(@NonNull String claimKey, int claimantSiteId) {
        Row active = activeClaimFor(claimKey);
        if (active == null) {
            return null;
        }
        Set<String> former = parse(active.get(ReleasedRouteClaimModel.FORMER_SUBJECTS));
        Set<String> claimant = HohenheimAccess.manageSubjectsOf(claimantSiteId);
        if (claimant != null && claimant.equals(former)) {
            return null;
        }
        if ((claimant == null || claimant.isEmpty()) && actorIsAmong(former)) {
            return null;
        }
        return active;
    }

    /** @return whole days left on a quarantine row, at least 1 while it is still active */
    public static long remainingDays(@NonNull Row claim) {
        Instant releasedAt = claim.get(ReleasedRouteClaimModel.RELEASED_AT);
        if (releasedAt == null) {
            return windowDays();
        }
        long left = Duration.between(Instant.now(), releasedAt.plus(Duration.ofDays(windowDays())))
            .toDays();
        return Math.max(1, left + 1);
    }

    /**
     * The newest ledger row still inside the window for a key -- an INDEXED lookup on the
     * claim key, never a scan (the two conflict scans beside this already walk every site
     * and every domain row per write; this must not add a third).
     */
    public static @Nullable Row activeClaimFor(@NonNull String claimKey) {
        int days = windowDays();
        if (days <= 0) {
            return null;
        }
        return Models.get(ReleasedRouteClaimModel.class).find()
            .where(ReleasedRouteClaimModel.CLAIM_KEY.eq(claimKey))
            .and(ReleasedRouteClaimModel.RELEASED_AT.gte(Instant.now().minus(Duration.ofDays(days))))
            .orderBy(ReleasedRouteClaimModel.RELEASED_AT, SortOrder.DESC)
            .orderBy(ReleasedRouteClaimModel.ID, SortOrder.DESC)
            .first();
    }

    /** Whether the principal performing this write is one of the former owner's subjects. */
    private static boolean actorIsAmong(@NonNull Set<String> former) {
        if (former.isEmpty()) {
            return false;
        }
        String actor = Accountability.current().actor();
        if (actor == null) {
            // System/unattended work has no identity to match; it is never "the former owner".
            return false;
        }
        int userId;
        try {
            userId = Integer.parseInt(actor);
        } catch (NumberFormatException notAUserId) {
            return false;
        }
        try {
            for (PermissionResolver.Subject subject
                    : PermissionResolver.expandSubjects("user", userId)) {
                if (former.contains(subject.type() + ":" + subject.id())) {
                    return true;
                }
            }
        } catch (RuntimeException unreadable) {
            // No auth layer (tools, minimal tests) or an unreadable grant store: fail closed.
            return false;
        }
        return false;
    }

    /** The ONE subject-set packing, shared with the quota bucket keys (HohenheimAccess). */
    private static @NonNull String join(@NonNull Set<String> subjects) {
        return HohenheimAccess.packSubjects(subjects);
    }

    private static @NonNull Set<String> parse(@Nullable Object stored) {
        return HohenheimAccess.parseSubjects(stored);
    }
}
