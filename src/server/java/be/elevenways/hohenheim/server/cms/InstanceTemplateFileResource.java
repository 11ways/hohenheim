package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.InstanceTemplateFileModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.ResourceParent;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Config files of an instance template (StackFileResource generalized). Hidden from
 * the sidebar -- reached through a template's Contents tab. Content may carry
 * {@code {{KEY}}} variable placeholders, substituted at deploy-time upload.
 */
public final class InstanceTemplateFileResource extends RowResource {

    private final FormSpec formSpec = FormSpec.builder()
        .add(RelationPick.of(InstanceTemplateFileModel.TEMPLATE_ID,
            InstanceTemplateModel.MODEL_ID).build())
        .add(InstanceTemplateFileModel.CONTAINER_PATH)
        .add(InstanceTemplateFileModel.CONTENT)
        .add(InstanceTemplateFileModel.MODE)
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(InstanceTemplateFileModel.CONTAINER_PATH).build())
        .column(ColumnSpec.fromField(InstanceTemplateFileModel.MODE).build())
        .column(ColumnSpec.fromField(InstanceTemplateFileModel.TEMPLATE_ID)
            .relation(RelationPick.of(InstanceTemplateFileModel.TEMPLATE_ID,
                InstanceTemplateModel.MODEL_ID).build())
            .build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "instance_template_file"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "template_file"); }
    @Override public @NonNull String slug() { return "instance-template-files"; }
    @Override public @NonNull Model model() { return Models.get(InstanceTemplateFileModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.DEPLOY_GROUP; }
    @Override public int navOrder() { return 18; }
    @Override public @NonNull Icon icon() { return Icon.of("file-code"); }
    @Override public boolean showInNav() { return false; }

    @Override
    public @Nullable ResourceParent<Row> parent() {
        return ResourceParent.<Row>of("instance-templates",
            row -> row.get(InstanceTemplateFileModel.TEMPLATE_ID)).tab("contents");
    }

    /** The Contents tab links here with ?template_id= so the pick arrives preselected. */
    @Override
    public @NonNull Map<String, Object> createValues(@NonNull Conduit conduit) {
        Map<String, Object> values = new LinkedHashMap<>(formSpec().defaultValues());
        String templateId = conduit.getQueryParam("template_id");
        if (templateId != null && !templateId.isEmpty()) {
            try {
                values.put("template_id", Integer.parseInt(templateId));
            } catch (NumberFormatException ignored) {
                // Malformed prefill: render the bare form.
            }
        }
        return Map.copyOf(values);
    }

    @Override
    public @NonNull Object persistRow(@NonNull Map<String, Object> coerced,
                                      @NonNull AccessContext accessContext) {
        validatePathAndMode(coerced);
        return super.persistRow(coerced, accessContext);
    }

    @Override
    public void updateRow(@NonNull Row existing, @NonNull Map<String, Object> coerced,
                          @NonNull AccessContext accessContext) {
        validatePathAndMode(coerced);
        super.updateRow(existing, coerced, accessContext);
    }

    /** Absolute, traversal-free path; octal mode (the StackFileResource contract). */
    static void validatePathAndMode(@NonNull Map<String, Object> coerced) {
        if (coerced.containsKey("container_path")) {
            String path = String.valueOf(coerced.get("container_path")).trim();
            if (!path.startsWith("/") || path.contains("..")) {
                throw Violations.ofField("container_path", path,
                    CmsSupport.violationText("file_path_absolute"));
            }
            coerced.put("container_path", path);
        }
        Object mode = coerced.get("mode");
        if (mode != null && !String.valueOf(mode).isBlank()) {
            try {
                Integer.parseInt(String.valueOf(mode).trim(), 8);
            } catch (NumberFormatException error) {
                throw Violations.ofField("mode", mode,
                    CmsSupport.violationText("file_mode_format"));
            }
        }
    }
}
