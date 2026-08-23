package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.InstanceFileModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.ResourceParent;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-instance config files (seeded from the template, per-instance editable, staged
 * into the container before every start). Hidden from the sidebar -- reached through
 * an instance's Provisioning tab.
 */
public final class InstanceFileResource extends RowResource {

    private final FormSpec formSpec = FormSpec.builder()
        .add(RelationPick.of(InstanceFileModel.INSTANCE_ID, InstanceModel.MODEL_ID).build())
        .add(InstanceFileModel.CONTAINER_PATH)
        .add(InstanceFileModel.CONTENT)
        .add(InstanceFileModel.MODE)
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(InstanceFileModel.CONTAINER_PATH)
            .subtext("mode").copyable().build())
        .column(ColumnSpec.fromField(InstanceFileModel.MODE).hidden().build())
        // AIDEV-NOTE: a GENERATED row's write is refused by the model with a 422 that the
        // list gave no hint about, because nothing here said the row was generated.
        .column(ColumnSpec.fromField(InstanceFileModel.GENERATED_BY).build())
        .column(ColumnSpec.fromField(InstanceFileModel.INSTANCE_ID)
            .relation(RelationPick.of(InstanceFileModel.INSTANCE_ID, InstanceModel.MODEL_ID).build())
            .build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "instance_file"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "instance_file"); }
    @Override public @Nullable Microcopy recordLabel() { return Microcopy.of("singular").withFilter("scope", "instance_file"); }
    @Override public @NonNull String slug() { return "instance-files"; }
    @Override public @NonNull Model model() { return Models.get(InstanceFileModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull ListChrome listChrome() { return ListChrome.MINIMAL; }

    /** The path only: CONTENT is encrypted at rest, so a search over it would match ciphertext. */
    @Override
    public @NonNull List<Field<?, ?>> searchFields() {
        return List.of(InstanceFileModel.CONTAINER_PATH);
    }

    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.DEPLOY_GROUP; }
    @Override public int navOrder() { return 19; }
    @Override public @NonNull Icon icon() { return Icon.of("file-code"); }
    @Override public boolean showInNav() { return false; }

    @Override
    public @Nullable ResourceParent<Row> parent() {
        return ResourceParent.<Row>of("instances",
            row -> row.get(InstanceFileModel.INSTANCE_ID)).tab("provisioning");
    }

    /** The Provisioning tab links here with ?instance_id= so the pick arrives preselected. */
    @Override
    public @NonNull Map<String, Object> createValues(@NonNull Conduit conduit) {
        Map<String, Object> values = new LinkedHashMap<>(formSpec().defaultValues());
        String instanceId = conduit.getQueryParam("instance_id");
        if (instanceId != null && !instanceId.isEmpty()) {
            try {
                values.put("instance_id", Integer.parseInt(instanceId));
            } catch (NumberFormatException ignored) {
                // Malformed prefill: render the bare form.
            }
        }
        return Map.copyOf(values);
    }


    /**
     * The path a file lands on and the mode it lands with -- the two answers an operator
     * corrects while reading the list, and both staged into the container at the next
     * DEPLOY rather than at this click.
     *
     * AIDEV-NOTE: CONTENT is excluded and always will be: it is the file BODY, encrypted
     * at rest and routinely multi-line, so a one-line cell is the wrong instrument for
     * it. Both cells still run {@code validatePathAndMode}, which is written
     * containsKey-first and therefore correct on the one-entry map this lane sends.
     */
    @Override
    public @NonNull List<Field<?, ?>> inlineEditableFields() {
        return List.of(InstanceFileModel.CONTAINER_PATH, InstanceFileModel.MODE);
    }

    @Override
    public @NonNull Object persistRow(@NonNull Map<String, Object> coerced,
                                      @NonNull AccessContext accessContext) {
        return super.persistRow(
            InstanceTemplateFileResource.validatePathAndMode(coerced), accessContext);
    }

    @Override
    public void updateRow(@NonNull Row existing, @NonNull Map<String, Object> coerced,
                          @NonNull AccessContext accessContext) {
        super.updateRow(existing,
            InstanceTemplateFileResource.validatePathAndMode(coerced), accessContext);
    }
}
