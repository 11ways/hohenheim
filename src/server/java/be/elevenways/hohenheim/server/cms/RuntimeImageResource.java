package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.RuntimeImageModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.common.edit.FormSection;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * Runtime images ("yolks"): the base images workspaces and applications run inside.
 * The built-in rows are CODE-OWNED truth -- {@code RuntimeImageSeeder} re-asserts them
 * on every boot -- so they render read-only instead of offering an edit whose save
 * reverts at the next restart; operator-authored variants stay fully editable.
 */
public class RuntimeImageResource extends RowResource {

    private final FormSpec formSpec = FormSpec.builder()
        .add(RuntimeImageModel.NAME)
        .add(RuntimeImageModel.DESCRIPTION)
        .add(RuntimeImageModel.DOCKER_IMAGE)
        .add(RuntimeImageModel.INCUS_IMAGE)
        .add(RuntimeImageModel.DEFAULT_COMMAND)
        .add(RuntimeImageModel.DEFAULT_PORT)
        .add(RuntimeImageModel.DEFAULT_BUILD_COMMAND)
        .add(RuntimeImageModel.WORKDIR)
        .add(RuntimeImageModel.SHELL)
        .add(RuntimeImageModel.ENABLED)
        // A catalog entry is a name and the two image references it resolves to; the
        // defaults it lends an instance are refinements of that.
        .section(FormSection.advanced(
            RuntimeImageModel.DEFAULT_COMMAND.getName(),
            RuntimeImageModel.DEFAULT_PORT.getName(),
            RuntimeImageModel.DEFAULT_BUILD_COMMAND.getName(),
            RuntimeImageModel.WORKDIR.getName(),
            RuntimeImageModel.SHELL.getName()))
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(RuntimeImageModel.NAME).filterable()
            .subtext("description").build())
        .column(ColumnSpec.fromField(RuntimeImageModel.DESCRIPTION).hidden().build())
        .column(ColumnSpec.fromField(RuntimeImageModel.DOCKER_IMAGE).copyable().build())
        .column(ColumnSpec.fromField(RuntimeImageModel.INCUS_IMAGE).build())
        .column(ColumnSpec.fromField(RuntimeImageModel.DEFAULT_PORT).build())
        .column(ColumnSpec.fromField(RuntimeImageModel.BUILTIN).build())
        .column(ColumnSpec.fromField(RuntimeImageModel.ENABLED).filterable().build())
        .filter(FilterSpec.forField(RuntimeImageModel.NAME, FilterSpec.Kind.TEXT)
            .label(FieldLabels.labelFor(RuntimeImageModel.NAME)).build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "runtime_image"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "runtime_image"); }
    @Override public @Nullable Microcopy recordLabel() { return Microcopy.of("singular").withFilter("scope", "runtime_image"); }
    @Override public @NonNull String slug() { return "runtime-images"; }
    @Override public @NonNull Model model() { return Models.get(RuntimeImageModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }

    /**
     * A short, mostly read-only catalog: no saved views, no rule builder, no column
     * gear -- search alone finds anything in a list this size.
     */
    @Override public @NonNull ListChrome listChrome() { return ListChrome.MINIMAL; }

    @Override
    public @NonNull List<Field<?, ?>> searchFields() {
        return List.of(RuntimeImageModel.NAME, RuntimeImageModel.DESCRIPTION);
    }

    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.DEPLOY_GROUP; }
    @Override public int navOrder() { return 35; }
    @Override public @NonNull Icon icon() { return Icon.of("layer-group"); }

    @Override
    public @Nullable Microcopy description() {
        return Microcopy.of("nav_hint").withFilter("scope", "runtime_image");
    }

    /**
     * A built-in row is read-only: the seeder re-asserts it on every boot, so an edit
     * would report success and silently revert -- the exact shape this panel bans.
     * The detail form still opens as the readers' view (the honest way to see what a
     * built-in image ships).
     */
    @Override
    public boolean updatableBy(@NonNull Row record, @NonNull AccessContext accessContext) {
        return !Boolean.TRUE.equals(record.get(RuntimeImageModel.BUILTIN))
            && super.updatableBy(record, accessContext);
    }

    /** Built-ins cannot be deleted either -- the seeder would recreate them. */
    @Override
    public boolean deletableBy(@NonNull Row record, @NonNull AccessContext accessContext) {
        return !Boolean.TRUE.equals(record.get(RuntimeImageModel.BUILTIN))
            && super.deletableBy(record, accessContext);
    }
}
