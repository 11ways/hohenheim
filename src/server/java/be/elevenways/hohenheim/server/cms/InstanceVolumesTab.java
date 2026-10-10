package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceVolumeModel;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.instance.InstanceVolumes;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.resource.RecordTab;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.routing.RouteTarget;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.text.ByteText;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Volumes tab on a volume-mounting instance: each declared host directory with its
 * container path, quota, observed usage and exclusivity, linking into the (nav-hidden)
 * volume resource forms -- the InstanceDevicesPage shape over {@link InstanceVolumes}.
 */
public final class InstanceVolumesTab implements RecordTab.Rendered<Row> {

    @Override public @NonNull Identifier id() { return HohenheimIds.id("instance_volumes"); }
    @Override public @NonNull Microcopy label() { return HohenheimMicrocopy.INSTANCE_VOLUME.of("plural"); }
    /**
     * Housekeeping, not an everyday destination: the tab lives in the strip's "More"
     * menu so the visible strip stays the handful of tabs an operator opens daily.
     */
    @Override public boolean secondaryTab() { return true; }
    @Override public @NonNull String slug() { return HohenheimSlugs.Tab.VOLUMES; }
    @Override public @NonNull Icon icon() { return Icon.of("database"); }

    /**
     * Only kinds that MOUNT declared volumes get the tab; for the rest a declared row
     * would ride no deploy and the surface could only mislead (the InstanceDevicesPage
     * stance, read off the same kind of declaration).
     */
    @Override
    public boolean visibleFor(@NonNull Row record, @NonNull AccessContext access) {
        return volumeCapable(record);
    }

    static boolean volumeCapable(@NonNull Row record) {
        InstanceKindHandler handler = InstanceKinds.handlerOf(record);
        return handler != null && handler.supportsVolumes();
    }

    @Override
    public @NonNull ActionResult<?> render(@NonNull PanelRequest request, @NonNull Row instance) {
        return body(request.conduit(), request.access(), instance, request.panelSlug());
    }

    @NonNull ActionResult<?> body(@NonNull Conduit conduit, @NonNull AccessContext accessContext,
                                  @NonNull Row instance, @NonNull String panel) {
        Integer instanceId = instance.get(InstanceModel.ID);

        List<Map<String, Object>> volumes = new ArrayList<>();
        for (Row volume : InstanceVolumes.declaredFor(instanceId)) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("id", volume.get(InstanceVolumeModel.ID));
            entry.put("name", volume.get(InstanceVolumeModel.NAME));
            entry.put("containerPath", volume.get(InstanceVolumeModel.CONTAINER_PATH));
            Long quota = volume.get(InstanceVolumeModel.QUOTA_BYTES);
            Long used = volume.get(InstanceVolumeModel.USED_BYTES);
            entry.put("quotaText", quota != null ? ByteText.human(quota) : "");
            entry.put("usedText", used != null ? ByteText.human(used) : "");
            entry.put("usedBytes", used);
            entry.put("quotaBytes", quota);
            entry.put("exclusive",
                Boolean.TRUE.equals(volume.get(InstanceVolumeModel.EXCLUSIVE)));
            Object observedAt = volume.get(InstanceVolumeModel.OBSERVED_AT);
            entry.put("observedAtIso", observedAt != null ? observedAt.toString() : "");
            entry.put("editTarget", CmsRoutes.detail(panel, HohenheimSlugs.INSTANCE_VOLUMES,
                volume.get(InstanceVolumeModel.ID)));
            volumes.add(entry);
        }

        Map<String, Object> vars = new HashMap<>();
        vars.put("title", CmsSupport.pageTitle(conduit, HohenheimMicrocopy.INSTANCE_VOLUME,
            instance.get(InstanceModel.NAME)));
        vars.put("instanceId", instanceId);
        vars.put("instanceName", instance.get(InstanceModel.NAME));
        vars.put("volumes", volumes);
        boolean canEdit = HohenheimAccess.isAdmin(accessContext)
            || HohenheimAccess.hasInstanceCapability(
                accessContext, instanceId, HohenheimCapabilities.CONFIG);
        // Gated on the SAME boolean the template's {% if %} uses: a declared template
        // variable is serialized into the hydration payload whether or not any element
        // renders it (the InstanceDevicesPage lesson).
        vars.put("addVolumeTarget", canEdit ? newVolumeTarget(panel, instanceId) : null);
        vars.put("head", recordHead(conduit));
        return new RenderTemplateResult(HohenheimTemplateIds.INSTANCE_VOLUMES, vars);
    }

    /** The volume create form, opened with its owning instance prefilled. */
    private static @NonNull RouteTarget newVolumeTarget(@NonNull String panel,
                                                        @NonNull Integer instanceId) {
        return CmsEndpoints.CREATE_FORM
            .with(CmsEndpoints.PANEL_PARAM, panel)
            .with(CmsEndpoints.RESOURCE_PARAM, HohenheimSlugs.INSTANCE_VOLUMES)
            .with(HohenheimParams.INSTANCE_ID_PREFILL, instanceId);
    }
}
