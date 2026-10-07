package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.model.ControllerIdentityModel;
import be.elevenways.hohenheim.model.InstallMediaFetchModel;
import be.elevenways.hohenheim.model.InstanceQuotaModel;
import be.elevenways.hohenheim.model.PortAllocationModel;
import be.elevenways.hohenheim.model.ReconcileFindingModel;
import be.elevenways.hohenheim.model.ReleasedRouteClaimModel;
import be.elevenways.hohenheim.model.SiteSessionModel;
import be.elevenways.hohenheim.model.SystemUserModel;
import be.elevenways.hohenheim.model.WebhookDeliveryModel;
import be.elevenways.protoblast.common.annotation.BlastAutoLoad;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.activity.ActivityVisibility;

import java.util.List;

/**
 * The Hohenheim models whose activity is plumbing (claims, quotas, sessions, discovery, deliveries): still recorded
 * and searchable, hidden from every activity surface until a reader asks for internal records.
 *
 * AIDEV-NOTE: a model whose PEOPLE write it too (servers, zones, sites, instances) is never listed here, because the
 * visibility is per model; its bookkeeping writers (heartbeats, probes, discovery sync) run under
 * {@link ActivityLog#suppressed} instead, so the model keeps showing what operators did.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
@BlastAutoLoad
public final class HohenheimActivity {

    /** The plumbing models, each declared {@link ActivityVisibility#INTERNAL} once at class load. */
    public static final List<Identifier> INTERNAL = List.of(
        PortAllocationModel.MODEL_ID,
        InstanceQuotaModel.MODEL_ID,
        ReleasedRouteClaimModel.MODEL_ID,
        ReconcileFindingModel.MODEL_ID,
        SiteSessionModel.MODEL_ID,
        ControllerIdentityModel.MODEL_ID,
        InstallMediaFetchModel.MODEL_ID,
        WebhookDeliveryModel.MODEL_ID,
        SystemUserModel.MODEL_ID);

    static {
        for (Identifier model : INTERNAL) {
            ActivityLog.setVisibility(model, ActivityVisibility.INTERNAL);
        }
    }

    private HohenheimActivity() {
    }
}
