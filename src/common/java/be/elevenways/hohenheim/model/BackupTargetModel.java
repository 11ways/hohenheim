package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.backup.BackupTargetRegistry;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.context.RemoveFromDatasource;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * A configured destination for instance backup exports (the target seam's data half):
 * kind (filesystem or ssh now) plus per-kind settings via the registry-driven
 * type-enum pattern. Whether a target is genuinely OFF-HOST is a deployment fact the
 * operator owns -- a filesystem target on the controller host is not.
 */
public class BackupTargetModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "backup_target");
    public static final Schema SCHEMA = new Schema();

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());

    public static final StringField NAME = SCHEMA.addField(StringField.builder().name("name")
        .label(HohenheimFormCopy.label("name"))
        .build());

    public static final EnumField KIND = SCHEMA.addField(
        RegistryEnumField.builder("kind")
            .registry(BackupTargetRegistry.REGISTRY)
            .label(HohenheimFormCopy.label("kind"))
            .help(HohenheimFormCopy.help("backup_target_kind"))
            .build());

    public static final SchemaField SETTINGS = SCHEMA.addField(
        SchemaField.builder("settings")
            .schemaFrom("kind")
            .label(HohenheimFormCopy.label("settings"))
            .build());

    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());

    static {
        SCHEMA.setDisplayFields(NAME);
        // Deleting a target used to succeed while COMPLETE backup rows still pointed at
        // it, so the restore failed at USE (backup_target_missing, long after the
        // decision) instead of the delete failing at the point of decision. Refusal over
        // cascade, deliberately: a COMPLETE backup row is the user's recovery asset, and
        // a config-page delete must never silently convert it to non-restorable. A dead
        // target is a config row -- REPAIR it (edit its settings) rather than deleting
        // it out from under its rows. Schema hook, so it runs on EVERY delete path
        // (admin resource, model.delete, criteria delete) -- the ServerModel rule.
        SCHEMA.addBeforeRemoveHook(BackupTargetModel::refuseRemovalWhileReferenced);
    }

    /**
     * Refuse deleting a target that backup rows still point at, that a live instance
     * declares as its destination, or that the control-plane backup setting names.
     *
     * AIDEV-NOTE: FAILED backup rows do NOT count. They are evidence rows whose error
     * text already carries the possibly-surviving key; counting them would make a target
     * with only junk rows undeletable for nothing. UPLOADING and COMPLETE rows do count
     * -- those are (or are becoming) restorable artifacts.
     *
     * @throws Violations {@code backup_target_in_use} or
     *                    {@code backup_target_control_plane}
     */
    static void refuseRemovalWhileReferenced(@NonNull RemoveFromDatasource context) {
        Model model = context.getModel();
        if (model == null) {
            return;
        }
        var builder = model.find();
        var queryContext = context.getQueryContext();
        if (queryContext != null && queryContext.getCriteria() != null) {
            builder.where(queryContext.getCriteria());
        }
        String controlPlane = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Database.CONTROL_PLANE_BACKUP_TARGET);
        for (Row doomed : builder.all()) {
            Integer targetId = doomed.get(ID);
            if (targetId == null) {
                continue;
            }
            String name = String.valueOf((Object) doomed.get(NAME));
            if (controlPlane != null && !controlPlane.isBlank()
                    && controlPlane.equals(name)) {
                throw Violations.ofForm(Microcopy.of("backup_target_control_plane")
                    .withFilter("scope", "violations")
                    .withArg("name", name));
            }
            long backups = Models.get(InstanceBackupModel.class).find()
                .where(InstanceBackupModel.TARGET_ID.eq(targetId))
                .where(Criteria.or(
                    InstanceBackupModel.STATUS.eq(InstanceBackupModel.STATUS_COMPLETE),
                    InstanceBackupModel.STATUS.eq(InstanceBackupModel.STATUS_UPLOADING)))
                .count();
            long instances = Models.get(InstanceModel.class).find()
                .where(InstanceModel.BACKUP_TARGET_ID.eq(targetId))
                .where(InstanceModel.DELETED_AT.isNull())
                .count();
            if (backups > 0 || instances > 0) {
                throw Violations.ofForm(Microcopy.of("backup_target_in_use")
                    .withFilter("scope", "violations")
                    .withArg("name", name)
                    .withArg("backups", backups)
                    .withArg("instances", instances));
            }
        }
    }

    @Override public Identifier getModelId() { return MODEL_ID; }
    @Override public Field<?, ?> getPrimaryKeyField() { return ID; }
    @Override public String getModelName() { return "BackupTarget"; }
    @Override public String getTableName() { return "backup_targets"; }
    @Override public Schema getSchema() { return SCHEMA; }
}
