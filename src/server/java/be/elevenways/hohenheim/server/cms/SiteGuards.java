package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.ProtectedPathModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * THE reading of which access lists guard a site: its whole-site list, then the lists of its protected paths.
 *
 * AIDEV-NOTE: two reads for any number of sites (the sites, then all their protected paths at once), never one per
 * site. A trashed site is no site here: its model's find hides it, and its paths with it.
 *
 * @author Jelle De Loecker
 * @since 0.10.0
 */
public final class SiteGuards {

    private SiteGuards() {
    }

    /** @return the access lists guarding the sites that serve one app, each once */
    public static @NonNull List<Integer> accessListsOfApp(@NonNull Integer instanceId) {
        return accessListsOf(Models.get(SiteModel.class).find().where(SiteModel.INSTANCE_ID.eq(instanceId))
            .orderBy(SiteModel.ID, SortOrder.ASC).all());
    }

    /** @return the access lists guarding one site, each once; empty for a site that is gone */
    public static @NonNull List<Integer> accessListsOfSite(@NonNull Integer siteId) {
        return accessListsOf(Models.get(SiteModel.class).find().where(SiteModel.ID.eq(siteId)).all());
    }

    /** @return the access lists guarding these sites, each once: a site's own list, then its paths' lists */
    public static @NonNull List<Integer> accessListsOf(@NonNull List<Row> sites) {
        Set<Integer> lists = new LinkedHashSet<>();
        if (sites.isEmpty()) {
            return List.of();
        }
        List<Integer> siteIds = new ArrayList<>(sites.size());
        for (Row site : sites) {
            siteIds.add(site.get(SiteModel.ID));
        }
        List<Row> paths = Models.get(ProtectedPathModel.class).find().where(ProtectedPathModel.SITE_ID.in(siteIds))
            .orderBy(ProtectedPathModel.ID, SortOrder.ASC).all();
        for (Row site : sites) {
            Integer siteList = site.get(SiteModel.ACCESS_LIST_ID);
            if (siteList != null) {
                lists.add(siteList);
            }
            Integer siteId = site.get(SiteModel.ID);
            for (Row path : paths) {
                Integer pathList = path.get(ProtectedPathModel.ACCESS_LIST_ID);
                if (pathList != null && siteId != null && siteId.equals(path.get(ProtectedPathModel.SITE_ID))) {
                    lists.add(pathList);
                }
            }
        }
        return List.copyOf(lists);
    }
}
