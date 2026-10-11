package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceTemplateDatabaseModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.InstanceTemplateVariableModel;
import be.elevenways.hohenheim.model.InstanceTemplateVolumeModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.StoredRows;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.instance.InstanceTemplates.VolumeDeclaration;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static be.elevenways.hohenheim.RawValues.trimmed;

/**
 * Authoring invariants for template declarations, enforced by model saves on every writer.
 *
 * AIDEV-NOTE: file path/mode validation is the separately shared instance/template file save hook, not a rule here.
 * Names and prefixes are identifiers, not localized content; refusals use the existing Hohenheim vocabulary.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class TemplateDeclarationGuards {
    static {
        InstanceTemplateVariableModel.SCHEMA.addBeforeValidateHook(context -> {
            if (context.getRow() != null) variable(context.getRow());
        });
        InstanceTemplateVolumeModel.SCHEMA.addBeforeValidateHook(context -> {
            if (context.getRow() != null) volume(context.getRow());
        });
        InstanceTemplateDatabaseModel.SCHEMA.addBeforeValidateHook(context -> {
            if (context.getRow() != null) database(context.getRow());
        });
    }
    private TemplateDeclarationGuards() {}
    public static void init() {}

    private static void variable(@NonNull Row row) {
        Integer id = row.get(InstanceTemplateVariableModel.ID);
        Row stored = StoredRows.of(Models.get(InstanceTemplateVariableModel.class), row);
        Integer template = row.afterWrite(InstanceTemplateVariableModel.TEMPLATE_ID, stored);
        String key = row.afterWrite(InstanceTemplateVariableModel.KEY, stored);
        if (template == null || key == null) return;
        for (Row sibling : Models.get(InstanceTemplateVariableModel.class).find()
                .where(InstanceTemplateVariableModel.TEMPLATE_ID.eq(template))
                .where(InstanceTemplateVariableModel.KEY.eq(key)).all()) {
            if (!Objects.equals(id, sibling.get(InstanceTemplateVariableModel.ID))) {
                throw Violations.ofField("key", key, HohenheimMicrocopy.VIOLATIONS.of("variable_key_taken")
                    .withArg("key", key));
            }
        }
    }

    private static void volume(@NonNull Row row) {
        Integer id = row.get(InstanceTemplateVolumeModel.ID);
        Row stored = StoredRows.of(Models.get(InstanceTemplateVolumeModel.class), row);
        Row draft = new Row(stored == null ? row : stored);
        if (row.has(InstanceTemplateVolumeModel.TEMPLATE_ID.getName())) draft.set(
                InstanceTemplateVolumeModel.TEMPLATE_ID,
            row.get(InstanceTemplateVolumeModel.TEMPLATE_ID));
        if (row.has(InstanceTemplateVolumeModel.NAME.getName())) draft.set(InstanceTemplateVolumeModel.NAME,
            row.get(InstanceTemplateVolumeModel.NAME));
        if (row.has(InstanceTemplateVolumeModel.CONTAINER_PATH.getName())) draft.set(
                InstanceTemplateVolumeModel.CONTAINER_PATH,
            row.get(InstanceTemplateVolumeModel.CONTAINER_PATH));
        if (row.has(InstanceTemplateVolumeModel.QUOTA_BYTES.getName())) draft.set(
                InstanceTemplateVolumeModel.QUOTA_BYTES,
            row.get(InstanceTemplateVolumeModel.QUOTA_BYTES));
        if (row.has(InstanceTemplateVolumeModel.EXCLUSIVE.getName())) draft.set(InstanceTemplateVolumeModel.EXCLUSIVE,
            row.get(InstanceTemplateVolumeModel.EXCLUSIVE));
        Integer templateId = draft.get(InstanceTemplateVolumeModel.TEMPLATE_ID);
        if (templateId == null) throw Violations.ofField("template_id", null,
                HohenheimMicrocopy.VIOLATIONS.of("template_required"));
        Row template = Models.get(InstanceTemplateModel.class).findById(templateId);
        if (template == null) throw Violations.ofField("template_id", templateId,
                HohenheimMicrocopy.VIOLATIONS.of("template_missing"));
        List<VolumeDeclaration> declared = new ArrayList<>();
        declared.add(VolumeDeclaration.of(draft));
        for (Row sibling : Models.get(InstanceTemplateVolumeModel.class).findByTemplateId(templateId)) {
            if (!Objects.equals(id, sibling.get(InstanceTemplateVolumeModel.ID))) declared.add(
                    VolumeDeclaration.of(sibling));
        }
        InstanceTemplates.requireVolumesDeclarable(
                InstanceKinds.getHandler(template.get(InstanceTemplateModel.KIND)), declared);
    }

    private static void database(@NonNull Row row) {
        Integer id = row.get(InstanceTemplateDatabaseModel.ID);
        Row stored = StoredRows.of(Models.get(InstanceTemplateDatabaseModel.class), row);
        Integer templateId = row.afterWrite(InstanceTemplateDatabaseModel.TEMPLATE_ID, stored);
        if (templateId == null) throw Violations.ofField("template_id", null,
                HohenheimMicrocopy.VIOLATIONS.of("template_required"));
        Row template = Models.get(InstanceTemplateModel.class).findById(templateId);
        if (template == null) throw Violations.ofField("template_id", templateId,
                HohenheimMicrocopy.VIOLATIONS.of("template_missing"));
        String kind = template.get(InstanceTemplateModel.KIND);
        InstanceKindHandler handler = InstanceKinds.getHandler(kind);
        if (handler == null || !handler.supportedRuntimes().contains(ServerModel.RUNTIME_DOCKER)) {
            throw Violations.ofField("template_id", templateId,
                HohenheimMicrocopy.VIOLATIONS.of("instance_kind_no_injection").withArg("kind", String.valueOf(kind)));
        }
        String engine = row.afterWrite(InstanceTemplateDatabaseModel.ENGINE, stored);
        if (engine == null) engine = "";
        if (ManagedDatabase.Engine.forToken(engine) == null) throw Violations.ofField("engine", engine,
            HohenheimMicrocopy.VIOLATIONS.of("unknown_engine").withArg("engine", engine));
        String rawPrefix = row.afterWrite(InstanceTemplateDatabaseModel.ENV_PREFIX, stored);
        String prefix = trimmed(rawPrefix).toUpperCase(Locale.ROOT);
        if (!prefix.matches(InstanceDatabaseModel.PREFIX_PATTERN)) throw Violations.ofField("env_prefix", prefix,
                HohenheimMicrocopy.VIOLATIONS.of("prefix_format"));
        for (Row sibling : Models.get(InstanceTemplateDatabaseModel.class).findByTemplateId(templateId)) {
            String other = sibling.get(InstanceTemplateDatabaseModel.ENV_PREFIX);
            if (!Objects.equals(id, sibling.get(InstanceTemplateDatabaseModel.ID))
                    && other != null && other.equalsIgnoreCase(prefix)) {
                throw Violations.ofField("env_prefix", prefix,
                    HohenheimMicrocopy.VIOLATIONS.of("template_database_prefix_taken").withArg("prefix", prefix));
            }
        }
        if (row.has(InstanceTemplateDatabaseModel.ENV_PREFIX.getName())) row.set(
                InstanceTemplateDatabaseModel.ENV_PREFIX, prefix);
    }
}
