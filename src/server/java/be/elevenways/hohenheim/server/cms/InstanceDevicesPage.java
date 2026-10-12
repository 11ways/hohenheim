package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.instance.DeviceDetail;
import be.elevenways.hohenheim.instance.DeviceType;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.instance.InstanceOperationHandlers;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.render.action.PageFormState;
import be.elevenways.zenit.cms.server.page.PageActions;
import be.elevenways.hohenheim.model.InstanceDeviceModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceDevices;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.cms.common.resource.RecordTab;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.routing.RouteTarget;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.widget.common.data.WidgetBadge;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Devices tab on an instance: its attached disks and extra NICs, linking into the
 * (nav-hidden) device resource forms. This tab plus that resource are the whole reason
 * InstanceDevices is reachable by a human at all.
 */
public final class InstanceDevicesPage implements RecordTab.Rendered<Row> {

    /** Install media into this tab's own record, its form the ISO pick of the record's host. */
    private static final PanelAction<Row> ATTACH_MEDIA = PanelAction.<Row, Void>places(
            InstanceOperations.ATTACH_INSTALL_MEDIA, ActionPlacement.PAGE,
            (request, result) -> CmsActionResult.refreshWithToast(HohenheimMicrocopy.INSTANCE_DEVICE
                .of("media_attached").withArg("name", request.subject().get(InstanceModel.NAME))))
        .confirmation(ConfirmationSpec.of(HohenheimMicrocopy.INSTANCE_DEVICE.of("add_media"),
            HohenheimMicrocopy.INSTANCE_DEVICE.of("attach_media"),
            HohenheimMicrocopy.INSTANCE_DEVICE.of("attach_media_lead"), ActionStyle.DEFAULT))
        .selectedByRoute(instance -> String.valueOf((Object) instance.get(InstanceModel.ID)))
        .build();

    /** Every ISO out of this tab's own record: offered while a drive holds one. */
    private static final PanelAction<Row> DETACH_MEDIA = PanelAction.<Row, Void>places(
            InstanceOperations.DETACH_INSTALL_MEDIA, ActionPlacement.PAGE,
            (request, result) -> CmsActionResult.refreshWithToast(HohenheimMicrocopy.INSTANCE_DEVICE
                .of("media_detached").withArg("name", request.subject().get(InstanceModel.NAME))))
        .confirmation(ConfirmationSpec.of(HohenheimMicrocopy.INSTANCE_DEVICE.of("remove_media"),
            HohenheimMicrocopy.INSTANCE_DEVICE.of("detach_media"),
            HohenheimMicrocopy.INSTANCE_DEVICE.of("detach_media_lead"), ActionStyle.DEFAULT))
        .selectedByRoute(instance -> String.valueOf((Object) instance.get(InstanceModel.ID)))
        .build();

    @Override public @NonNull Identifier id() { return HohenheimIds.id("instance_devices"); }
    @Override public @NonNull Microcopy label() { return HohenheimMicrocopy.INSTANCE_DEVICE.of("plural"); }
    /**
     * Housekeeping, not an everyday destination: the tab lives in the strip's "More"
     * menu so the visible strip stays the handful of tabs an operator opens daily.
     */
    @Override public @NonNull String slug() { return HohenheimSlugs.Tab.DEVICES; }
    @Override public @NonNull Icon icon() { return Icon.of("hard-drive"); }
    @Override public @NonNull Microcopy lead() { return HohenheimMicrocopy.INSTANCE_DEVICE.of("lead"); }

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
    public boolean visibleFor(@NonNull Row record, @NonNull AccessContext access) {
        InstanceKindHandler handler = InstanceKinds.handlerOf(record);
        return handler != null && handler.supportsDevices();
    }

    @Override
    public @NonNull List<PanelAction<Row>> actions() {
        return List.of(ATTACH_MEDIA, DETACH_MEDIA);
    }

    @Override
    public @NonNull ActionResult<?> render(@NonNull PanelRequest request, @NonNull Row instance) {
        Conduit conduit = request.conduit();
        AccessContext accessContext = request.access();
        Integer instanceId = instance.get(InstanceModel.ID);
        String panel = request.panelSlug();

        List<Row> rows = new InstanceDevices().rowsFor(instanceId);
        List<DeviceDetail> columns = detailColumns(rows);
        List<Map<String, Object>> devices = new ArrayList<>();
        for (Row device : rows) {
            Map<String, Object> entry = new HashMap<>();
            Object token = device.get(InstanceDeviceModel.TYPE);
            DeviceType type = DeviceType.parse(token);
            WidgetBadge.Colors colors = WidgetBadge.colorsOf(InstanceDeviceModel.TYPE, token);
            entry.put("id", device.get(InstanceDeviceModel.ID));
            entry.put("name", device.get(InstanceDeviceModel.NAME));
            entry.put("typeLabel", CmsSupport.enumLabel(InstanceDeviceModel.TYPE, token));
            entry.put("typeVariant", colors.variant());
            entry.put("typeColorSet", colors.colorSet());
            List<String> details = new ArrayList<>();
            for (DeviceDetail detail : columns) {
                details.add(detailText(type, detail, device));
            }
            entry.put("details", details);
            entry.put("editTarget", CmsRoutes.detail(panel, HohenheimSlugs.INSTANCE_DEVICES,
                device.get(InstanceDeviceModel.ID)));
            devices.add(entry);
        }
        List<String> detailHeads = new ArrayList<>();
        for (DeviceDetail detail : columns) {
            detailHeads.add(FieldLabels.labelFor(detail.field()).asString(conduit));
        }

        Map<String, Object> vars = new HashMap<>();
        vars.put("title", CmsSupport.pageTitle(conduit, HohenheimMicrocopy.INSTANCE_DEVICE,
            instance.get(InstanceModel.NAME)));
        vars.put("instanceId", instanceId);
        vars.put("instanceName", instance.get(InstanceModel.NAME));
        vars.put("devices", devices);
        vars.put("detailHeads", detailHeads);
        boolean canEdit = HohenheimAccess.isAdmin(accessContext)
            || HohenheimAccess.hasInstanceCapability(
                accessContext, instanceId, HohenheimCapabilities.CONFIG);
        vars.put("canEdit", canEdit);
        // AIDEV-NOTE: gated on the SAME boolean the template's {% if %} uses. A declared
        // template variable is serialized into the hydration payload whether or not any
        // element renders it, so an ungated target would publish an editor route to a
        // viewer who may not edit (the certificates-request leak the site Domains tab hit).
        vars.put("addDiskTarget", canEdit
            ? newDeviceTarget(panel, DeviceType.DISK, instanceId) : null);
        vars.put("addNicTarget", canEdit
            ? newDeviceTarget(panel, DeviceType.NIC, instanceId) : null);
        // Install media is OPERATOR-ONLY and VM-only, one ISO at a time: an empty drive offers the attach (its form
        // the ISO pick of this record's host), a drive holding one offers the detach. Each is drawn exactly where its
        // handler applies and authorizes, so the section shows only to whom may change the drive.
        PageFormState attachForm = InstanceOperationHandlers.offered(InstanceOperations.ATTACH_INSTALL_MEDIA,
            accessContext, instance) && PageActions.open(request, this, instance, ATTACH_MEDIA.id())
            instanceof PageActions.Form form ? form.state() : null;
        PageFormState detachForm = InstanceOperationHandlers.offered(InstanceOperations.DETACH_INSTALL_MEDIA,
            accessContext, instance) && PageActions.open(request, this, instance, DETACH_MEDIA.id())
            instanceof PageActions.Form form ? form.state() : null;
        boolean mediaSection = attachForm != null || detachForm != null;
        vars.put("mediaSection", mediaSection);
        vars.put("mediaForm", attachForm);
        vars.put("detachMediaForm", detachForm);
        List<String> inDrive = InstanceDevices.installMediaOf(instanceId);
        vars.put("mediaLead", !mediaSection ? "" : (inDrive.isEmpty()
            ? HohenheimMicrocopy.INSTANCE_DEVICE.of("media_drive_empty")
            : HohenheimMicrocopy.INSTANCE_DEVICE.of("media_in_drive").withArg("media", String.join(", ", inDrive)))
            .asString(conduit));
        Integer serverId = instance.get(InstanceModel.SERVER_ID);
        vars.put("mediaHostTarget", mediaSection && serverId != null
            ? CmsRoutes.subpage(panel, HohenheimSlugs.SERVERS, serverId, HohenheimSlugs.Tab.INSTALL_MEDIA) : null);
        vars.put("mediaHostLabel", mediaSection && serverId != null
            ? HohenheimMicrocopy.INSTANCE_DEVICE.of("manage_media").withArg("host", ServerModel.labelOf(serverId))
                .asString(conduit) : "");
        vars.put("head", recordHead(conduit));
        return new RenderTemplateResult(HohenheimTemplateIds.INSTANCE_DEVICES, vars);
    }

    /** @return every detail some listed device's type means, in the vocabulary's order: the tab's detail columns */
    private static @NonNull List<DeviceDetail> detailColumns(@NonNull List<Row> devices) {
        List<DeviceDetail> columns = new ArrayList<>();
        for (DeviceDetail detail : DeviceDetail.values()) {
            for (Row device : devices) {
                DeviceType type = DeviceType.parse(device.get(InstanceDeviceModel.TYPE));
                if (type != null && type.details().contains(detail)) {
                    columns.add(detail);
                    break;
                }
            }
        }
        return columns;
    }

    /**
     * @return the detail's text for the device: empty where its type does not mean the detail (an unknown type means
     *         none), "-" where it does but holds no value
     */
    private static @NonNull String detailText(@Nullable DeviceType type, @NonNull DeviceDetail detail,
                                              @NonNull Row device) {
        if (type == null || !type.details().contains(detail)) {
            return "";
        }
        Object value = device.get(detail.field().getName());
        return value == null ? "-" : String.valueOf(value);
    }

    /** The device create form, opened with its kind and owning instance prefilled. */
    private static @NonNull RouteTarget newDeviceTarget(@NonNull String panel,
                                                        @NonNull DeviceType type,
                                                        @NonNull Integer instanceId) {
        return CmsRoutes.create(panel, HohenheimSlugs.INSTANCE_DEVICES)
            .with(HohenheimParams.DEVICE_TYPE_PREFILL, type.token())
            .with(HohenheimParams.INSTANCE_ID_PREFILL, instanceId);
    }
}
