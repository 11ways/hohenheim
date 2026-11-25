package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.instance.InstanceTemplateOperations;
import be.elevenways.hohenheim.instance.VolumeOperations;
import be.elevenways.hohenheim.model.InstanceFileModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateFileModel;
import be.elevenways.hohenheim.model.InstanceVolumeModel;
import be.elevenways.hohenheim.model.StackFileModel;
import be.elevenways.zenit.common.operation.SubjectType;

import java.util.List;

/**
 * The parts an app is given beside its settings, as operations through their panel resources
 * ({@link PanelRecordWrites}): its volumes, and the config files Hohenheim writes into the containers of an app, a
 * template or a stack service.
 *
 * AIDEV-NOTE: a volume's name is its directory on the host, so only a create takes it, and its app is its fixed
 * parent; a config file is named by its owner and path, set by one operation per owner kind whose arguments are the
 * same, so one tool routes by the owner's code.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class AppPartWrites {

    /** The argument naming the app, template or stack service a part belongs to. */
    public static final String OWNER = "owner";

    /** An app's volume: made under its app, its mount, size limit and exclusivity changing after. */
    public static final PanelRecordWrites VOLUMES = PanelRecordWrites.of("volume", InstanceVolumeModel.MODEL_ID,
            SubjectType.record(InstanceVolumeModel.REF), InstanceVolumeModel.ID, HohenheimSlugs.INSTANCE_VOLUMES)
        .parent(OWNER, HohenheimMicrocopy.HELP.of("volume_owner"), InstanceVolumeModel.INSTANCE_ID,
            InstanceModel.MODEL_ID)
        .createOnly(List.of(InstanceVolumeModel.NAME))
        .settings(List.of(InstanceVolumeModel.CONTAINER_PATH, VolumeOperations.QUOTA_MB,
            InstanceVolumeModel.EXCLUSIVE))
        .build();

    /** An app's config file, by its path. */
    public static final PanelRecordWrites APP_FILES = PanelRecordWrites.of("app_config_file",
            InstanceFileModel.MODEL_ID, SubjectType.record(InstanceFileModel.REF), InstanceFileModel.ID,
            HohenheimSlugs.INSTANCE_FILES)
        .keyedUnder(InstanceOperations.INSTANCE, InstanceFileModel.INSTANCE_ID,
            List.of(InstanceFileModel.CONTAINER_PATH))
        .settings(List.of(InstanceFileModel.CONTENT, InstanceFileModel.MODE))
        .removable(HohenheimMicrocopy.HELP.of("config_file_remove"))
        .build();

    /** A template's config file, by its path: apps made from the template after it get it. */
    public static final PanelRecordWrites TEMPLATE_FILES = PanelRecordWrites.of("template_config_file",
            InstanceTemplateFileModel.MODEL_ID, SubjectType.record(InstanceTemplateFileModel.REF),
            InstanceTemplateFileModel.ID, HohenheimSlugs.INSTANCE_TEMPLATE_FILES)
        .keyedUnder(InstanceTemplateOperations.TEMPLATE, InstanceTemplateFileModel.TEMPLATE_ID,
            List.of(InstanceTemplateFileModel.CONTAINER_PATH))
        .settings(List.of(InstanceTemplateFileModel.CONTENT, InstanceTemplateFileModel.MODE))
        .removable(HohenheimMicrocopy.HELP.of("config_file_remove"))
        .build();

    /** A stack service's config file, by its path; one under a volume mount is refused (StackParts). */
    public static final PanelRecordWrites STACK_FILES = PanelRecordWrites.of("stack_config_file",
            StackFileModel.MODEL_ID, SubjectType.record(StackFileModel.REF), StackFileModel.ID,
            HohenheimSlugs.STACK_FILES)
        .keyedUnder(StackOperations.SERVICE, StackFileModel.STACK_SERVICE_ID, List.of(StackFileModel.CONTAINER_PATH))
        .settings(List.of(StackFileModel.CONTENT, StackFileModel.MODE))
        .removable(HohenheimMicrocopy.HELP.of("config_file_remove"))
        .build();

    private AppPartWrites() {
    }
}
