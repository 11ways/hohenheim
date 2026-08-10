package be.elevenways.hohenheim.server.dns;

import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.orm.RecordStamp;
import be.elevenways.hohenheim.server.proxy.HostnamePatterns;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.util.BlastString;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.context.RemoveFromDatasource;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.QueryBuilder;
import be.elevenways.zenit.common.orm.query.QueryContext;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The DNS half of hostname release: when a name loses its last live covering domain row,
 * the authoritative records under it stop being SERVED and every credential that could
 * keep rewriting them stops WORKING.
 *
 * The certificate tier already draws this line ({@code CleanOrphanCertificates} deletes a
 * released name's certificates daily); until this class the DNS tier did not, so our own
 * nameserver kept directing traffic to a departed tenant, and a dyndns token minted under
 * a claim survived the claim -- the exact laundering {@code HohenheimAccess} declares
 * DYNDNS non-delegable to prevent.
 *
 * Semantics, decided deliberately: released records are DISABLED, never deleted -- the
 * rows stay visible to an operator (and to a site restore) with an activity-log entry per
 * row, but leave the serving snapshot in the same transaction as the release. The dyndns
 * columns are cleared ({@code dynamic=false}, token null) and every record grant on the
 * row is revoked, because a bearer credential or a capability that outlives the claim it
 * was derived from is a permanent authority laundered out of a revocable one.
 *
 * AIDEV-NOTE: liveness is enforced at RELEASE time, not per dyndns update. A per-update
 * coverage predicate ("the FQDN must be covered by a live domain row") looks stronger but
 * would break the feature's primary use: operator dyndns names (a home router's
 * {@code home.example.com}) are deliberately NOT sites and are covered by nothing, so
 * that predicate answers nohost for every legitimate operator record. Killing the
 * credential when the claim dies protects exactly the names that were ever claimed and
 * nothing else.
 *
 * AIDEV-NOTE: the trigger is AUTHORITY loss ({@code HostnameAuthority.covering}: site
 * soft-deleted, domain row removed or renamed away), NOT route-claim loss. A merely
 * DISABLED site keeps its DNS exactly like it keeps its certificates ("only soft-DELETED
 * sites orphan their certs"), and its tenant still holds hostname authority while
 * disabled. A name still covered by ANOTHER live domain row is not released at all.
 *
 * AIDEV-NOTE: rows carrying attribution ({@code generated_by}) are out of scope on
 * purpose -- their declaring tiers reconcile them (GameDomains by exact attribution,
 * PreviewDomains with the preview, ACME rows by exact tuple), and sweeping them here
 * would race those owners. Site RESTORE does not re-enable anything, matching grants
 * ("a restore never resurrects grants"): re-enabling is an explicit operator act.
 */
public final class DnsClaimReleases {

    /** Activity-log verb stamped on every record this class disables. */
    public static final String ACTIVITY_RELEASED = "released_hostname_disabled";

    private static volatile boolean installed;

    private DnsClaimReleases() {
    }

    /** Install the release hooks; idempotent, called at the MODULES boot stage. */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;

        // A site's soft delete releases every name its domain rows were covering. The
        // hook fires BEFORE the write, so the departing site still reads as live and its
        // rows are excluded explicitly. There is no hard site delete outside tests.
        SiteModel.SCHEMA.addBeforeWriteHook(context -> {
            Row row = context.getRow();
            if (row == null || !row.has(SiteModel.DELETED_AT.getName())
                    || row.get(SiteModel.DELETED_AT) == null
                    || !row.has(SiteModel.ID.getName())) {
                return;
            }
            Object idValue = row.get(SiteModel.ID);
            if (!(idValue instanceof Integer siteId)) {
                return;
            }
            Row stored = Models.get(SiteModel.class).findById(siteId);
            if (stored == null || stored.get(SiteModel.DELETED_AT) != null) {
                return; // a re-save of an already-trashed site releases nothing new
            }
            List<Row> domains = Models.get(SiteDomainModel.class).find()
                .where(SiteDomainModel.SITE_ID.eq(siteId)).all();
            Set<Integer> leaving = new HashSet<>();
            for (Row domain : domains) {
                leaving.add(domain.get(SiteDomainModel.ID));
            }
            for (Row domain : domains) {
                releaseName(domain.get(SiteDomainModel.HOSTNAME),
                    domain.get(SiteDomainModel.MATCH_TYPE), leaving);
            }
        });

        // Renaming a domain row releases the name it is leaving. Judged on EFFECTIVE
        // values, because a CMS update stages only the changed columns.
        SiteDomainModel.SCHEMA.addBeforeWriteHook(context -> {
            Row row = context.getRow();
            if (row == null || !row.has(SiteDomainModel.ID.getName())
                    || row.get(SiteDomainModel.ID) == null) {
                return;
            }
            Row stored = Models.get(SiteDomainModel.class)
                .findById(row.get(SiteDomainModel.ID));
            if (stored == null) {
                return;
            }
            String storedHostname = stored.get(SiteDomainModel.HOSTNAME);
            Object nextHostname = SiteDomainModel.effective(row, SiteDomainModel.HOSTNAME);
            if (storedHostname == null || Objects.equals(
                    BlastString.lower(storedHostname),
                    BlastString.lower(nextHostname != null ? String.valueOf(nextHostname) : null))) {
                return;
            }
            releaseName(storedHostname, stored.get(SiteDomainModel.MATCH_TYPE),
                Set.of(stored.get(SiteDomainModel.ID)));
        });

        // Deleting the domain row itself -- the most ordinary way a tenant releases one
        // hostname while keeping the site. Criteria-only context, so re-query the doomed
        // rows (the ReleasedClaims/TenantWrites idiom).
        SiteDomainModel.SCHEMA.addBeforeRemoveHook(context -> {
            List<Row> doomed = doomedRows(context);
            Set<Integer> leaving = new HashSet<>();
            for (Row domain : doomed) {
                leaving.add(domain.get(SiteDomainModel.ID));
            }
            for (Row domain : doomed) {
                releaseName(domain.get(SiteDomainModel.HOSTNAME),
                    domain.get(SiteDomainModel.MATCH_TYPE), leaving);
            }
        });
    }

    /**
     * Disable every non-generated record whose FQDN the released pattern covers and no
     * OTHER live domain row still covers, revoking its dyndns credential and its record
     * grants, then bump the affected zones so the serving snapshot drops the rows.
     */
    private static void releaseName(@Nullable String hostname, @Nullable String matchType,
                                    @NonNull Set<Integer> leavingDomainIds) {
        if (hostname == null || hostname.isBlank()) {
            return;
        }
        Map<Integer, String> origins = zoneOrigins();
        if (origins.isEmpty()) {
            return;
        }
        List<Row> survivors = survivingLiveDomains(leavingDomainIds);
        DnsRecordModel model = Models.get(DnsRecordModel.class);
        Set<Integer> touchedZones = new LinkedHashSet<>();

        for (Row record : model.find().where(DnsRecordModel.GENERATED_BY.isNull()).all()) {
            boolean armed = Boolean.TRUE.equals(record.get(DnsRecordModel.ENABLED))
                || Boolean.TRUE.equals(record.get(DnsRecordModel.DYNAMIC))
                || record.get(DnsRecordModel.DYNDNS_TOKEN) != null;
            if (!armed) {
                continue;
            }
            String origin = origins.get(record.get(DnsRecordModel.ZONE_ID));
            String owner = record.get(DnsRecordModel.NAME);
            if (origin == null || owner == null) {
                continue;
            }
            String fqdn = BlastString.lower(DnsNames.absolute(origin, owner));
            if (!HostnamePatterns.covers(hostname, matchType, fqdn)
                    || isStillCovered(fqdn, survivors)) {
                continue;
            }
            RecordStamp.on(model, record)
                .set(DnsRecordModel.ENABLED, false)
                .set(DnsRecordModel.DYNAMIC, false)
                .set(DnsRecordModel.DYNDNS_TOKEN, null)
                .write();
            ActivityLog.record(model, record.get(DnsRecordModel.ID), ACTIVITY_RELEASED, fqdn);
            RecordGrants.revokeAllForRecord(DnsRecordModel.MODEL_ID,
                record.get(DnsRecordModel.ID));
            touchedZones.add(record.get(DnsRecordModel.ZONE_ID));
            Blast.log("DNS: released hostname disabled record", fqdn,
                "(" + record.get(DnsRecordModel.TYPE) + ")");
        }

        for (Integer zoneId : touchedZones) {
            DnsZoneStore.INSTANCE.bumpSerialAndReload(zoneId);
        }
    }

    /** @return true when any surviving live domain row's pattern covers the fqdn */
    private static boolean isStillCovered(@NonNull String fqdn, @NonNull List<Row> survivors) {
        for (Row domain : survivors) {
            if (HostnamePatterns.covers(domain.get(SiteDomainModel.HOSTNAME),
                    domain.get(SiteDomainModel.MATCH_TYPE), fqdn)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The domain rows that still convey authority after the release: live (non-deleted)
     * sites' rows minus the departing ones. The departing site is excluded by ROW ids,
     * because at hook time its deleted_at is not written yet and it still reads as live.
     */
    private static @NonNull List<Row> survivingLiveDomains(@NonNull Set<Integer> leavingDomainIds) {
        Set<Integer> liveSiteIds = new HashSet<>();
        for (Row site : Models.get(SiteModel.class).find()
                .where(SiteModel.DELETED_AT.isNull()).all()) {
            liveSiteIds.add(site.get(SiteModel.ID));
        }
        List<Row> survivors = new ArrayList<>();
        for (Row domain : Models.get(SiteDomainModel.class).find().all()) {
            if (leavingDomainIds.contains(domain.get(SiteDomainModel.ID))) {
                continue;
            }
            Integer siteId = domain.get(SiteDomainModel.SITE_ID);
            if (siteId != null && liveSiteIds.contains(siteId)) {
                survivors.add(domain);
            }
        }
        return survivors;
    }

    private static @NonNull Map<Integer, String> zoneOrigins() {
        Map<Integer, String> origins = new HashMap<>();
        for (Row zone : Models.get(DnsZoneModel.class).find().all()) {
            origins.put(zone.get(DnsZoneModel.ID), zone.get(DnsZoneModel.ORIGIN));
        }
        return origins;
    }

    /** The rows a criteria delete is about to remove (the shared re-query idiom). */
    private static @NonNull List<Row> doomedRows(@NonNull RemoveFromDatasource context) {
        Model model = context.getModel();
        QueryContext queryContext = context.getQueryContext();
        Criteria criteria = queryContext != null ? queryContext.getCriteria() : null;
        if (model == null) {
            return List.of();
        }
        QueryBuilder<Row> builder = model.find();
        if (criteria != null) {
            builder.where(criteria);
        }
        return builder.all();
    }
}
