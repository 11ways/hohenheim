package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * The reverse "which sites expose this instance" question, and the destroy-time rule that
 * keeps a stranded site from serving a permanent 503 nobody was warned about.
 *
 * AIDEV-NOTE: a site's hostnames, certificates, access lists and auth provider are the
 * SITE's, never the instance's -- so destroying a workload may not delete the sites that
 * exposed it. It disables them instead: {@code enabled = false} is a first-class,
 * one-toggle-reversible state whose write pipeline already releases the live route claim
 * (RouteClaims.restamp), quarantines it against an instant steal (ReleasedClaims) and
 * rebuilds the proxy (ProxyReloadHooks). Nulling {@code instance_id} is not an option:
 * SiteModel's upstream invariant refuses an instance upstream with no instance.
 *
 * AIDEV-NOTE: the disable runs AFTER the destroy has settled, deliberately. A destroy that
 * the daemon cannot confirm KEEPS the record and the operator retries, and disabling first
 * would have taken working sites down for a workload that is still there. The cost is a
 * brief window in which the site 503s, which the destroy confirmation names up front.
 */
public final class InstanceExposure {

    /** The activity detail a site disabled by a destroy is renamed with. */
    public static final String ACTIVITY_STRANDED_DETAIL = "instance_destroyed";

    private InstanceExposure() {
    }

    /**
     * The live sites whose upstream resolves to this instance, by name.
     *
     * @return the sites that are routing traffic to it right now (enabled, not trashed)
     */
    public static @NonNull List<Row> liveSitesExposing(int instanceId) {
        return Models.get(SiteModel.class).find()
            .where(SiteModel.INSTANCE_ID.eq(instanceId))
            .where(SiteModel.ENABLED.eq(true))
            .where(SiteModel.DELETED_AT.isNull())
            .orderBy(SiteModel.NAME, SortOrder.ASC)
            .all();
    }

    /** The same sites' display names, for an operator-facing warning. */
    public static @NonNull List<String> liveSiteNamesExposing(int instanceId) {
        List<String> names = new ArrayList<>();
        for (Row site : liveSitesExposing(instanceId)) {
            names.add(String.valueOf((Object) site.get(SiteModel.NAME)));
        }
        return List.copyOf(names);
    }

    /**
     * Disable every live site that exposed a now-destroyed instance, so the guarantee
     * holds for the API and every other non-UI caller and not only for the button.
     *
     * A site that refuses to disable is LOGGED and left alone rather than aborting a
     * teardown that has already happened: the record is soft-deleted by the time this
     * runs, so throwing would report a completed destroy as failed.
     *
     * @return the names of the sites that were disabled
     */
    public static @NonNull List<String> disableForDestroyedInstance(int instanceId) {
        List<String> disabled = new ArrayList<>();
        for (Row site : liveSitesExposing(instanceId)) {
            String name = String.valueOf((Object) site.get(SiteModel.NAME));
            try {
                site.set(SiteModel.ENABLED, false);
                ActivityLog.withAction("disabled", ACTIVITY_STRANDED_DETAIL,
                    () -> Models.get(SiteModel.class).save(site));
                disabled.add(name);
            } catch (RuntimeException refused) {
                Blast.log("INSTANCE: site", name, "still exposes destroyed instance",
                    instanceId, "- it could not be disabled and will answer 503:",
                    refused.getMessage());
            }
        }
        if (!disabled.isEmpty()) {
            Blast.log("INSTANCE: destroyed", instanceId,
                "- disabled the sites that exposed it:", disabled);
        }
        return List.copyOf(disabled);
    }
}
