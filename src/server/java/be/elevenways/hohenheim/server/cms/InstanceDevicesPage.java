package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.InstanceDeviceModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceDevices;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.HohenheimParams;
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
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Devices tab on an instance: its attached disks and extra NICs, linking into the
 * (nav-hidden) device resource forms. This tab plus that resource are the whole reason
 * InstanceDevices is reachable by a human at all.
 */
public final class InstanceDevicesPage implements RecordScopedPage<Row> {

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "instance_devices"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "instance_device"); }
    /**
     * Housekeeping, not an everyday destination: the tab lives in the strip's "More"
     * menu so the visible strip stays the handful of tabs an operator opens daily.
     */
    @Override public boolean secondaryTab() { return true; }
    @Override public @NonNull String slug() { return "devices"; }
    @Override public @NonNull Icon icon() { return Icon.of("hard-drive"); }

    /**
     * Kinds whose driver can attach devices only; the tab hides AND 404s for the rest.
     *
     * AIDEV-NOTE: the InstanceFramebufferPage shape, for the same reason. Without it a
     * DOCKER instance rendered this tab plus Add-disk and Add-NIC buttons whose every POST
     * could only ever answer {@code devices_unsupported} -- DockerInstanceRuntime does not
     * implement {@code DeviceAttachSupport} and {@code InstanceDevices.requireSupport}
     * refuses before anything else runs. An affordance that can only refuse is worse than
     * an absent one: it reads as a broken product rather than as a tier that does not have
     * the feature. The predicate is DECLARED on the kind
     * ({@code InstanceKindHandler.supportsDevices}) rather than spelled as a kind list
     * here, so a later kind answers for itself.
     */
    @Override
    public boolean visibleFor(@NonNull Row record) {
        InstanceKindHandler handler = InstanceKinds.getHandler(record.get(InstanceModel.KIND));
        return handler != null && handler.supportsDevices();
    }

    @Override
    public @NonNull ActionResult<?> render(@NonNull Conduit conduit,
                                           @NonNull AccessContext accessContext,
                                           @NonNull Row instance) {
        Integer instanceId = instance.get(InstanceModel.ID);
        String panel = CmsSupport.panelSlug(conduit);

        List<Map<String, Object>> devices = new ArrayList<>();
        for (Row device : new InstanceDevices().rowsFor(instanceId)) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("id", device.get(InstanceDeviceModel.ID));
            entry.put("name", device.get(InstanceDeviceModel.NAME));
            entry.put("type", device.get(InstanceDeviceModel.TYPE));
            entry.put("disk", InstanceDeviceModel.TYPE_DISK.equals(
                device.get(InstanceDeviceModel.TYPE)));
            entry.put("sizeGb", device.get(InstanceDeviceModel.SIZE_GB));
            entry.put("cdrom", InstanceDeviceModel.TYPE_CDROM.equals(
                device.get(InstanceDeviceModel.TYPE)));
            entry.put("sourceMedia", device.get(InstanceDeviceModel.SOURCE_MEDIA));
            entry.put("editTarget", CmsRoutes.detail(panel, "instance-devices",
                device.get(InstanceDeviceModel.ID)));
            devices.add(entry);
        }

        Map<String, Object> vars = new HashMap<>();
        vars.put("title", CmsSupport.pageTitle(conduit, "instance_device",
            instance.get(InstanceModel.NAME)));
        vars.put("instanceId", instanceId);
        vars.put("instanceName", instance.get(InstanceModel.NAME));
        vars.put("devices", devices);
        boolean canEdit = HohenheimAccess.isAdmin(accessContext)
            || HohenheimAccess.hasInstanceCapability(
                accessContext, instanceId, HohenheimAccess.CONFIG);
        vars.put("canEdit", canEdit);
        // Create form + two prefill query parameters: composed off CmsEndpoints because
        // CmsRoutes.create returns the RouteTarget interface, which has no with(...).
        // AIDEV-NOTE: gated on the SAME boolean the template's {% if %} uses. A declared
        // template variable is serialized into the hydration payload whether or not any
        // element renders it, so an ungated target would publish an editor route to a
        // viewer who may not edit (the certificates-request leak SiteDomainsPage hit).
        vars.put("addDiskTarget", canEdit
            ? newDeviceTarget(panel, InstanceDeviceModel.TYPE_DISK, instanceId) : null);
        vars.put("addNicTarget", canEdit
            ? newDeviceTarget(panel, InstanceDeviceModel.TYPE_NIC, instanceId) : null);
        // Install media is OPERATOR-ONLY (InstanceDevices.attachCdrom refuses a tenant
        // with the uniform refusal) and VM-only -- both gates repeated here so the
        // affordance is offered exactly where the funnel would accept it.
        InstanceKindHandler handler = InstanceKinds.getHandler(instance.get(InstanceModel.KIND));
        boolean canAttachMedia = handler != null && handler.supportsInstallMedia()
            && HohenheimAccess.isAdmin(accessContext);
        vars.put("addMediaTarget", canAttachMedia
            ? newDeviceTarget(panel, InstanceDeviceModel.TYPE_CDROM, instanceId) : null);
        vars.put("recordTabs", recordTabs(conduit));
        return new RenderTemplateResult(Identifier.of("hohenheim", "cms/instance-devices"), vars);
    }

    /** The device create form, opened with its kind and owning instance prefilled. */
    private static @NonNull RouteTarget newDeviceTarget(@NonNull String panel,
                                                        @NonNull String type,
                                                        @NonNull Integer instanceId) {
        return CmsEndpoints.CREATE_FORM
            .with(CmsEndpoints.PANEL_PARAM, panel)
            .with(CmsEndpoints.RESOURCE_PARAM, "instance-devices")
            .with(HohenheimParams.DEVICE_TYPE_PREFILL, type)
            .with(HohenheimParams.INSTANCE_ID_PREFILL, instanceId);
    }
}
