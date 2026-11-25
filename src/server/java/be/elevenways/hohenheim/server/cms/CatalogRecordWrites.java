package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.instance.InstanceAttachmentOperations;
import be.elevenways.hohenheim.instance.InstanceTemplateOperations;
import be.elevenways.hohenheim.model.InstanceDeviceModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.RuntimeImageModel;
import be.elevenways.zenit.common.operation.SubjectType;

import java.util.List;

/**
 * The create and change of an app's devices, of runtime images and of templates, each a
 * {@link PanelRecordWrites} over the panel resource whose form the panel posts.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class CatalogRecordWrites {

    /** The argument naming the app a device is attached to. */
    public static final String APP = "app";

    /** The argument naming a device's kind, a disk or a network card. */
    public static final String KIND = "kind";

    /** A disk or network card of an app: made once under its app, only a disk's size changing after. */
    public static final PanelRecordWrites DEVICES = PanelRecordWrites.of("device", InstanceDeviceModel.MODEL_ID,
            InstanceAttachmentOperations.DEVICE, InstanceDeviceModel.ID, HohenheimSlugs.INSTANCE_DEVICES)
        // The app is the device's fixed parent: the device is attached under it, as from that app's Devices tab.
        .parent(APP, HohenheimMicrocopy.HELP.of("device_app"), InstanceDeviceModel.INSTANCE_ID,
            InstanceModel.MODEL_ID)
        .createOnlyByWord(KIND, InstanceDeviceModel.TYPE)
        .createOnly(List.of(InstanceDeviceModel.NAME))
        .settings(List.of(InstanceDeviceModel.SIZE_GB))
        // Attaching and resizing reach the host's daemon under its lease, outside the receipt's transaction.
        .createsOutsideTransaction().changesAs(CmsCommands.EXTERNAL)
        .build();

    /** A runtime image of the operator's own; a built-in one refuses every change (RuntimeImageParts). */
    public static final PanelRecordWrites RUNTIME_IMAGES = PanelRecordWrites.of("runtime_image",
            RuntimeImageModel.MODEL_ID, SubjectType.record(RuntimeImageModel.REF), RuntimeImageModel.ID,
            HohenheimSlugs.RUNTIME_IMAGES)
        .settings(List.of(RuntimeImageModel.NAME, RuntimeImageModel.DESCRIPTION, RuntimeImageModel.DOCKER_IMAGE,
            RuntimeImageModel.INCUS_IMAGE, RuntimeImageModel.DEFAULT_COMMAND, RuntimeImageModel.DEFAULT_PORT,
            RuntimeImageModel.DEFAULT_BUILD_COMMAND, RuntimeImageModel.WORKDIR, RuntimeImageModel.SHELL,
            RuntimeImageModel.ENABLED))
        .build();

    /**
     * A template's recipe (its kind, scripts, readiness and stop), while its kind's settings, questions, files,
     * databases and volumes are written by their own tools.
     *
     * AIDEV-NOTE: the catalog version (InstanceTemplateModel.VERSION) is left out: its input name is the common
     * version argument's, which on every other tool names the version a read showed.
     */
    public static final PanelRecordWrites TEMPLATES = PanelRecordWrites.of("template",
            InstanceTemplateModel.MODEL_ID, InstanceTemplateOperations.TEMPLATE, InstanceTemplateModel.ID,
            HohenheimSlugs.INSTANCE_TEMPLATES)
        .settings(List.of(InstanceTemplateModel.NAME, InstanceTemplateModel.DESCRIPTION))
        .byWord(InstanceTemplateModel.KIND)
        .settings(List.of(InstanceTemplateModel.INSTALL_IMAGE, InstanceTemplateModel.INSTALL_SCRIPT,
            InstanceTemplateModel.UPDATE_SCRIPT, InstanceTemplateModel.REINSTALL_POLICY,
            InstanceTemplateModel.READINESS_KIND, InstanceTemplateModel.READINESS_LINE,
            InstanceTemplateModel.READINESS_TARGET, InstanceTemplateModel.STOP_COMMAND))
        .build();

    private CatalogRecordWrites() {
    }
}
