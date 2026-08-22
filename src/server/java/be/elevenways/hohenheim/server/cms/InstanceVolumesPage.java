package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceVolumeModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.instance.InstanceVolumes;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
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
public final class InstanceVolumesPage implements RecordScopedPage<Row> {

    public static final String SLUG = "volumes";

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "instance_volumes"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "instance_volume"); }
    /**
     * Housekeeping, not an everyday destination: the tab lives in the strip's "More"
     * menu so the visible strip stays the handful of tabs an operator opens daily.
     */
    @Override public boolean secondaryTab() { return true; }
    @Override public @NonNull String slug() { return SLUG; }
    @Override public @NonNull Icon icon() { return Icon.of("database"); }

    /**
     * Only kinds that MOUNT declared volumes get the tab; for the rest a declared row
     * would ride no deploy and the surface could only mislead (the InstanceDevicesPage
     * stance, read off the same kind of declaration).
     */
    @Override
    public boolean visibleFor(@NonNull Row record) {
        InstanceKindHandler handler = InstanceKinds.getHandler(record.get(InstanceModel.KIND));
        return handler != null && handler.supportsVolumes();
    }

    @Override
    public @NonNull ActionResult<?> render(@NonNull Conduit conduit,
                                           @NonNull AccessContext accessContext,
                                           @NonNull Row instance) {
        Integer instanceId = instance.get(InstanceModel.ID);
        String panel = CmsSupport.panelSlug(conduit);

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
            entry.put("editTarget", CmsRoutes.detail(panel, "instance-volumes",
                volume.get(InstanceVolumeModel.ID)));
            volumes.add(entry);
        }

        Map<String, Object> vars = new HashMap<>();
        vars.put("title", CmsSupport.pageTitle(conduit, "instance_volume",
            instance.get(InstanceModel.NAME)));
        vars.put("instanceId", instanceId);
        vars.put("instanceName", instance.get(InstanceModel.NAME));
        vars.put("volumes", volumes);
        boolean canEdit = HohenheimAccess.isAdmin(accessContext)
            || HohenheimAccess.hasInstanceCapability(
                accessContext, instanceId, HohenheimAccess.CONFIG);
        // Gated on the SAME boolean the template's {% if %} uses: a declared template
        // variable is serialized into the hydration payload whether or not any element
        // renders it (the InstanceDevicesPage lesson).
        vars.put("addVolumeTarget", canEdit ? newVolumeTarget(panel, instanceId) : null);
        vars.put("recordTabs", recordTabs(conduit));
        return new RenderTemplateResult(Identifier.of("hohenheim", "cms/instance-volumes"), vars);
    }

    /** The volume create form, opened with its owning instance prefilled. */
    private static @NonNull RouteTarget newVolumeTarget(@NonNull String panel,
                                                        @NonNull Integer instanceId) {
        return CmsEndpoints.CREATE_FORM
            .with(CmsEndpoints.PANEL_PARAM, panel)
            .with(CmsEndpoints.RESOURCE_PARAM, "instance-volumes")
            .with(HohenheimParams.INSTANCE_ID_PREFILL, instanceId);
    }
}
