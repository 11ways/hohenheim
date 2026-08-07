package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.InstanceDeviceModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceDevices;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
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
    @Override public @NonNull String slug() { return "devices"; }
    @Override public @NonNull Icon icon() { return Icon.of("hard-drive"); }

    @Override
    public @NonNull ActionResult<?> render(@NonNull Conduit conduit,
                                           @NonNull AccessContext accessContext,
                                           @NonNull Row instance) {
        Integer instanceId = instance.get(InstanceModel.ID);
        String basePath = CmsSupport.panelBase(conduit);

        List<Map<String, Object>> devices = new ArrayList<>();
        for (Row device : new InstanceDevices().rowsFor(instanceId)) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("id", device.get(InstanceDeviceModel.ID));
            entry.put("name", device.get(InstanceDeviceModel.NAME));
            entry.put("type", device.get(InstanceDeviceModel.TYPE));
            entry.put("disk", InstanceDeviceModel.TYPE_DISK.equals(
                device.get(InstanceDeviceModel.TYPE)));
            entry.put("sizeGb", device.get(InstanceDeviceModel.SIZE_GB));
            entry.put("editUrl", basePath + "/instance-devices/"
                + device.get(InstanceDeviceModel.ID));
            devices.add(entry);
        }

        Map<String, Object> vars = new HashMap<>();
        vars.put("title", instance.get(InstanceModel.NAME) + " - Devices");
        vars.put("instanceId", instanceId);
        vars.put("instanceName", instance.get(InstanceModel.NAME));
        vars.put("devices", devices);
        vars.put("basePath", basePath);
        vars.put("canEdit", HohenheimAccess.isAdmin(accessContext)
            || HohenheimAccess.hasInstanceCapability(
                accessContext, instanceId, HohenheimAccess.CONFIG));
        vars.put("recordTabs", recordTabs(conduit));
        return new RenderTemplateResult(Identifier.of("hohenheim", "cms/instance-devices"), vars);
    }
}
