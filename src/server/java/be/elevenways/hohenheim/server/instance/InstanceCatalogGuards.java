package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateFileModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.InstanceTemplateVariableModel;
import be.elevenways.hohenheim.model.InstanceTemplateVolumeModel;
import be.elevenways.hohenheim.model.RuntimeImageModel;
import be.elevenways.hohenheim.server.cms.CmsSupport;
import be.elevenways.hohenheim.server.orm.PendingDeletes;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.context.RemoveFromDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.QueryBuilder;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * The instance CATALOG's delete invariants, on every delete lane: a template still named by
 * a live instance is refused, and one that is not takes its variables, files and volume
 * declarations with it; a runtime image still named by a live instance or a template is
 * refused.
 *
 * AIDEV-NOTE: refusal for the two catalog rows and cascade for the template's contents,
 * per the repo's split. A dangling {@code template_id} is silent (the instance keeps
 * running on the settings it copied) but a dangling {@code runtime_image_id} is
 * {@code runtime_image_unknown} at the NEXT deploy -- a refusal at use, hours after the
 * decision, which is the BackupTargetModel lesson. Template contents are meaningless
 * without their template and the files carry encrypted credential material, so they die
 * with it rather than lingering in tables no surface reaches.
 *
 * AIDEV-NOTE: {@code InstanceTemplateResource.deleteRow} used to carry the template
 * refusal alone, reachable from that one button; it now declares the same fact as a dead
 * delete WITH the reason, and this hook is the enforcement for every other writer.
 */
public final class InstanceCatalogGuards {

    private static volatile boolean installed;

    private InstanceCatalogGuards() {
    }

    /** Install the catalog hooks; idempotent, called at the MODULES boot stage. */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;

        InstanceTemplateModel.SCHEMA.addBeforeRemoveHook(context -> {
            refuseTemplateInUse(context);
            PendingDeletes.deleteDependents(Models.get(InstanceTemplateVariableModel.class),
                InstanceTemplateVariableModel.TEMPLATE, context);
            PendingDeletes.deleteDependents(Models.get(InstanceTemplateFileModel.class),
                InstanceTemplateFileModel.TEMPLATE, context);
            PendingDeletes.deleteDependents(Models.get(InstanceTemplateVolumeModel.class),
                InstanceTemplateVolumeModel.TEMPLATE, context);
        });

        RuntimeImageModel.SCHEMA.addBeforeRemoveHook(InstanceCatalogGuards::refuseImageInUse);
    }

    /** @throws Violations {@code template_in_use} naming the template and its live instance count */
    private static void refuseTemplateInUse(@NonNull RemoveFromDatasource context) {
        QueryBuilder<Row> live = liveInstances()
            .where(PendingDeletes.dependents(InstanceModel.TEMPLATE, context));
        long count = live.count();
        if (count == 0) {
            return;
        }
        Row template = live.first().get(InstanceModel.TEMPLATE);
        throw Violations.ofForm(CmsSupport.violationText("template_in_use")
            .withArg("name", template != null
                ? String.valueOf((Object) template.get(InstanceTemplateModel.NAME)) : "")
            .withArg("count", count));
    }

    /**
     * @throws Violations {@code runtime_image_in_use} naming the image and how many live
     *         instances and templates still run inside it
     */
    private static void refuseImageInUse(@NonNull RemoveFromDatasource context) {
        QueryBuilder<Row> instances = liveInstances()
            .where(PendingDeletes.dependents(InstanceModel.RUNTIME_IMAGE, context));
        QueryBuilder<Row> templates = Models.get(InstanceTemplateModel.class).find()
            .where(PendingDeletes.dependents(InstanceTemplateModel.RUNTIME_IMAGE, context));
        long instanceCount = instances.count();
        long templateCount = templates.count();
        if (instanceCount == 0 && templateCount == 0) {
            return;
        }
        Row image = instanceCount > 0
            ? instances.first().get(InstanceModel.RUNTIME_IMAGE)
            : templates.first().get(InstanceTemplateModel.RUNTIME_IMAGE);
        throw Violations.ofForm(CmsSupport.violationText("runtime_image_in_use")
            .withArg("name", image != null
                ? String.valueOf((Object) image.get(RuntimeImageModel.NAME)) : "")
            .withArg("instances", instanceCount)
            .withArg("templates", templateCount));
    }

    private static @NonNull QueryBuilder<Row> liveInstances() {
        return Models.get(InstanceModel.class).find().where(InstanceModel.DELETED_AT.isNull());
    }
}
