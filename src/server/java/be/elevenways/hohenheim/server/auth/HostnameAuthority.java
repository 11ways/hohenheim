package be.elevenways.hohenheim.server.auth;

import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.proxy.HostnamePatterns;
import be.elevenways.protoblast.common.util.BlastString;
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
 * a tenant DNS write asks, and two copies of it would be two answers. The "all of them, not
 * one" rule is deliberate: a name two sites both claim is a name two owners both answer for.
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
    }

    /**
     * Whether the context answers for a hostname: at least one live domain row must cover it
     * (this installation has to serve the name at all) and the context must hold
     * {@link HohenheimAccess#MANAGE} on EVERY covering row's site.
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
        List<Row> covering = snapshot.covering(hostname);
        if (covering.isEmpty()) {
            return false;
        }
        for (Row domain : covering) {
            Integer siteId = domain.get(SiteDomainModel.SITE_ID);
            if (siteId == null || !HohenheimAccess.canManageSite(ctx, siteId)) {
                return false;
            }
        }
        return true;
    }
}
