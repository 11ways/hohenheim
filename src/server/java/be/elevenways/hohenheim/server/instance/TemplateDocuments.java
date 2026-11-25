package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.activity.OperationSentences;
import be.elevenways.hohenheim.instance.InstanceTemplateOperations;
import be.elevenways.hohenheim.model.InstanceTemplateFileModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.InstanceTemplateVariableModel;
import be.elevenways.hohenheim.server.instance.variable.SecretVariableType;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.OptionSource;
import be.elevenways.zenit.common.edit.Plain;
import be.elevenways.zenit.common.edit.Select;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationCommand;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.field.TextField;
import be.elevenways.zenit.common.orm.history.Change;
import be.elevenways.zenit.common.orm.history.History;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.edit.FieldOption;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.operation.OperationHandlers;
import be.elevenways.zenit.server.operation.RecordCodes;
import be.elevenways.hohenheim.RawValues;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A template as a portable document: exported for another Hohenheim and imported from one, or from the community
 * catalog shipped with this one, as operations the import page and an assistant both reach.
 *
 * AIDEV-NOTE: the export download stays the panel's own endpoint: it hands an administrator the document whatever it
 * holds, while the operation refuses a template holding a secret value, since an assistant is never answered one.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class TemplateDocuments {

    /** The argument carrying an exported document. */
    public static final String DOCUMENT = "document";

    /** The argument naming where a document came from, kept on the new template. */
    public static final String SOURCE = "source";

    /** The argument naming an app of the shipped community catalog. */
    public static final String CATALOG_APP = "catalog_app";

    private static final TextField DOCUMENT_FIELD = TextField.builder().name(DOCUMENT)
        .help(HohenheimMicrocopy.HELP.of("template_import_document")).build();

    private static final StringField SOURCE_FIELD = StringField.builder(SOURCE)
        .help(HohenheimMicrocopy.HELP.of("template_import_source")).build();

    private static final StringField CATALOG_FIELD = StringField.builder(CATALOG_APP)
        .help(HohenheimMicrocopy.HELP.of("template_import_catalog_app")).build();

    /** Imports a template, never approved, answering its id. */
    public static final Operation<Void, OperationInput.Values, Integer> IMPORT =
            Operation.declare(HohenheimIds.id("import_template"))
        .label(HohenheimMicrocopy.HOHENHEIM.of("import_template"))
        .description(HohenheimMicrocopy.HOHENHEIM.of("import_template_description"))
        .happened(OperationSentences.of("import_template"))
        .noSubject()
        .gate(HohenheimSources.OPERATOR)
        .input(OperationInput.derived(FormSpec.builder()
            .add(Plain.of(DOCUMENT_FIELD))
            .add(Plain.of(SOURCE_FIELD))
            .add(Select.of(CATALOG_FIELD).options(OptionSource.supplied(TemplateDocuments::catalogOptions)).build())
            .build()))
        .result(Integer.class)
        .createdRecord(InstanceTemplateModel.MODEL_ID, id -> id)
        .facts(OperationFact.IDEMPOTENT)
        .command(OperationCommand.perVerb().onDatasource("default"))
        .register();

    /** Answers a template as the document its import reads, refused for one holding a secret value. */
    public static final Operation<Row, Void, String> EXPORT = Operation.declare(HohenheimIds.id("export_template"))
        .label(HohenheimMicrocopy.HOHENHEIM.of("export_template"))
        .description(HohenheimMicrocopy.HOHENHEIM.of("export_template_description"))
        .happened(OperationSentences.of("export_template"))
        .one(InstanceTemplateOperations.TEMPLATE)
        .gate(HohenheimSources.OPERATOR)
        .result(String.class)
        .register();

    static {
        OperationHandlers.attach(IMPORT).handle(call -> {
            OperationInput.Values input = Objects.requireNonNull(call.input());
            String document = text(input.get(DOCUMENT));
            String catalogApp = text(input.get(CATALOG_APP));
            String source = text(input.get(SOURCE));
            if (document.isEmpty() == catalogApp.isEmpty()) {
                throw Violations.ofField(DOCUMENT, null, HohenheimMicrocopy.VIOLATIONS.of("template_import_one"));
            }
            if (!catalogApp.isEmpty() && !source.isEmpty()) {
                throw Violations.ofField(SOURCE, source,
                    HohenheimMicrocopy.VIOLATIONS.of("template_import_source_document"));
            }
            return imported(catalogApp, document, source.isEmpty() ? "import" : source);
        });
        OperationHandlers.attach(EXPORT).handle(call -> {
            Row template = call.subject();
            if (holdsSecret(template)) {
                String code = RecordCodes.codeUnjudged(InstanceTemplateModel.MODEL_ID,
                    String.valueOf(template.get(InstanceTemplateModel.ID)));
                throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("template_export_secret")
                    .withArg("code", code == null ? "This template" : code));
            }
            return new TemplatePortability().export(template);
        });
    }

    private TemplateDocuments() {
    }

    /**
     * Imports a catalog app when one is named, else the document, and records where the new template came from.
     *
     * @param source where the document came from, blank for a pasted one
     * @return the new template's id
     * @throws Violations for a document or catalog app the import refuses
     */
    public static int imported(@NonNull String catalogApp, @NonNull String document, @NonNull String source) {
        int templateId;
        String from;
        if (!catalogApp.isEmpty()) {
            // The vendored community-scripts catalog: pinned content is copied into a NEW unapproved row; the
            // operator's gate is what keeps script introduction an operator act.
            templateId = CommunityScripts.importApp(catalogApp);
            from = "vendored catalog: " + catalogApp;
        } else {
            templateId = new TemplatePortability().importDocument(document, source);
            from = source.isEmpty() ? "paste" : source;
        }
        History.event(Models.get(InstanceTemplateModel.class), templateId, Change.fact(
            HohenheimActivityAction.IMPORTED, Map.of(HohenheimActivityAction.SOURCE_KEY, from)));
        return templateId;
    }

    /**
     * @return whether the export of the template would carry a secret value: a secret setting of its kind, a secret
     *     question's default or a config file, whose content is stored encrypted
     */
    static boolean holdsSecret(@NonNull Row template) {
        Integer id = template.get(InstanceTemplateModel.ID);
        InstanceKindHandler kind = InstanceKinds.getHandler(template.get(InstanceTemplateModel.KIND));
        Schema schema = kind == null ? null : kind.getSchema();
        Map<String, Object> settings = RawValues.map(template.get(InstanceTemplateModel.SETTINGS));
        if (schema != null) {
            for (Field<?, ?> field : schema.getFields().values()) {
                if (field.isSecret() && !text(settings.get(field.getName())).isEmpty()) return true;
            }
        }
        for (Row variable : Models.get(InstanceTemplateVariableModel.class).find()
                .where(InstanceTemplateVariableModel.TEMPLATE_ID.eq(id))
                .where(InstanceTemplateVariableModel.TYPE.eq(SecretVariableType.ID.toString())).all()) {
            if (!text(variable.get(InstanceTemplateVariableModel.DEFAULT_VALUE)).isEmpty()) return true;
        }
        return Models.get(InstanceTemplateFileModel.class).find()
            .where(InstanceTemplateFileModel.TEMPLATE_ID.eq(id)).count() > 0;
    }

    /** @return the shipped catalog's apps, each its own label */
    private static @NonNull List<FieldOption<String>> catalogOptions() {
        List<FieldOption<String>> options = new ArrayList<>();
        for (String app : CommunityScripts.catalogApps()) options.add(FieldOption.of(app, Microcopy.literal(app)));
        return options;
    }

    private static @NonNull String text(@Nullable Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
