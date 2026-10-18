package be.elevenways.hohenheim.server.devtunnel;

import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.StoredRows;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.context.SaveToDatasource;
import be.elevenways.zenit.common.orm.model.DoomedRows;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessChanged;
import be.elevenways.zenit.server.security.AccessChanges;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Announces every committed write of a site or of its domains as an access change of that site, so a live connection
 * whose authorization reads the site (a dev tunnel registered with the site's token, under its wildcard domain) is
 * asked again at once instead of at its next periodic check.
 *
 * AIDEV-NOTE: rides the two models' hooks, so every lane announces (operations, CMS forms, a direct model save, a
 * soft or hard delete). A domain row moved to another site names both: its stored site is captured before the write.
 * A site update whose row names no id announces to everyone (fail closed). Any write announces, not only a token
 * rotation, a disable or a domain change: a re-check that still passes costs one check.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class SiteAccessChanges {

    /** The context attribute holding the sites a domain row named before its write. */
    private static final String DOMAIN_SITES_BEFORE = "hohenheim.site_access_changes.domain_sites_before";

    private static boolean installed;

    private SiteAccessChanges() {
    }

    /** Registers the announcements on every site and domain write; idempotent. */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        SiteModel.SCHEMA.addAfterSaveHook(SiteAccessChanges::siteSaved);
        DoomedRows.handOver(SiteModel.SCHEMA, context -> {
            Set<Object> sites = new LinkedHashSet<>(context.doomedPrimaryKeys());
            return sites.isEmpty() ? null : sites;
        }, (context, sites) -> announce(context.resolvedDatasource(), sites));
        SiteDomainModel.SCHEMA.addBeforeWriteHook(context -> {
            Row stored = context.isCreate() ? null : StoredRows.of(Models.get(SiteDomainModel.class), context.getRow());
            if (stored != null && stored.get(SiteDomainModel.SITE_ID) != null) {
                context.setAttribute(DOMAIN_SITES_BEFORE, stored.get(SiteDomainModel.SITE_ID));
            }
        });
        SiteDomainModel.SCHEMA.addAfterSaveHook(SiteAccessChanges::domainSaved);
        DoomedRows.handOver(SiteDomainModel.SCHEMA, context -> {
            Set<Object> sites = new LinkedHashSet<>();
            context.eachDoomedRow(domain -> {
                Integer site = domain.get(SiteDomainModel.SITE_ID);
                if (site != null) {
                    sites.add(site);
                }
            });
            return sites.isEmpty() ? null : sites;
        }, (context, sites) -> announce(context.resolvedDatasource(), sites));
    }

    private static void siteSaved(@NonNull SaveToDatasource context) {
        if (context.isCreate()) {
            return;
        }
        Integer site = context.getRow().get(SiteModel.ID);
        if (site == null) {
            AccessChanges.announce(context.resolvedDatasource(), AccessChanged.everyone());
            return;
        }
        announce(context.resolvedDatasource(), Set.of(site));
    }

    private static void domainSaved(@NonNull SaveToDatasource context) {
        Set<Object> sites = new LinkedHashSet<>();
        Object before = context.getAttribute(DOMAIN_SITES_BEFORE);
        if (before != null) {
            sites.add(before);
        }
        Row row = context.getRow();
        Integer after = row.get(SiteDomainModel.SITE_ID);
        if (after == null && !context.isCreate()) {
            Row stored = StoredRows.of(Models.get(SiteDomainModel.class), row);
            after = stored == null ? null : stored.get(SiteDomainModel.SITE_ID);
        }
        if (after != null) {
            sites.add(after);
        }
        announce(context.resolvedDatasource(), sites);
    }

    private static void announce(@NonNull Datasource datasource, @NonNull Set<Object> sites) {
        AccessChanges.announce(datasource, AccessChanged.records(SiteModel.MODEL_ID, sites));
    }
}
