package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.auth.server.PasswordHasher;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.common.edit.FieldOption;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.OptionSource;
import be.elevenways.zenit.common.edit.Select;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Map;

/**
 * IP allow/deny lists plus optional single-credential basic auth, attachable to sites.
 */
public final class AccessListResource extends RowResource {

    private static final List<FieldOption<String>> SATISFY_OPTIONS = List.of(
        FieldOption.of(AccessListModel.SATISFY_ANY,
            Microcopy.of("any").withFilter("scope", "access_satisfy")),
        FieldOption.of("all",
            Microcopy.of("all").withFilter("scope", "access_satisfy")));

    private final FormSpec formSpec = FormSpec.builder()
        .add(AccessListModel.NAME)
        .add(Select.of(AccessListModel.SATISFY).options(OptionSource.of(SATISFY_OPTIONS)).build())
        .add(AccessListModel.BASIC_AUTH_USER)
        .add(AccessListModel.BASIC_AUTH_PASS)
        .add(AccessListModel.ALLOWED_IPS)
        .add(AccessListModel.DENIED_IPS)
        .build();

    // AIDEV-NOTE: an explicit spec, because the derived one has no room for the two
    // columns this list exists for. "Which list holds 10.0.0.5" was previously only
    // answerable by opening every record.
    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(AccessListModel.NAME).filterable()
            .subtext("basic_auth_user").build())
        .column(ColumnSpec.fromField(AccessListModel.BASIC_AUTH_USER).hidden().build())
        .column(ColumnSpec.fromField(AccessListModel.SATISFY).filterable().build())
        .column(ColumnSpec.fromField(AccessListModel.ALLOWED_IPS).build())
        .column(ColumnSpec.fromField(AccessListModel.DENIED_IPS).build())
        .column(ColumnSpec.fromField(AccessListModel.CREATED_AT).build())
        .filter(FilterSpec.forField(AccessListModel.NAME, FilterSpec.Kind.TEXT)
            .label(FieldLabels.labelFor(AccessListModel.NAME)).build())
        .filter(FilterSpec.forField(AccessListModel.SATISFY, FilterSpec.Kind.TEXT)
            .label(FieldLabels.labelFor(AccessListModel.SATISFY)).build())
        .build();

    /** The rule bodies are searchable BECAUSE the question is always "who allows this address". */
    @Override
    public @NonNull List<Field<?, ?>> searchFields() {
        return List.of(AccessListModel.NAME, AccessListModel.ALLOWED_IPS,
            AccessListModel.DENIED_IPS, AccessListModel.BASIC_AUTH_USER);
    }

    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "access_list"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "access_list"); }
    @Override public @NonNull String slug() { return "access-lists"; }
    @Override public @NonNull Model model() { return Models.get(AccessListModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.NETWORK_GROUP; }
    @Override public int navOrder() { return 30; }

    @Override
    public @Nullable Microcopy description() {
        return Microcopy.of("nav_hint").withFilter("scope", "access_list");
    }
    @Override public @NonNull Icon icon() { return Icon.of("shield-halved"); }

    @Override
    public void applyValuesToRow(@NonNull Row row, @NonNull Map<String, Object> coerced) {
        super.applyValuesToRow(row, coerced);
        String password = row.get(AccessListModel.BASIC_AUTH_PASS);
        if (password != null && !password.isBlank() && !password.startsWith("$argon2")) {
            row.set(AccessListModel.BASIC_AUTH_PASS, PasswordHasher.hash(password));
        }
    }

}
