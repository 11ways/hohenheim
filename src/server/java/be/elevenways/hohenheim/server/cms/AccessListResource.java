package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.QuickCreateSpec;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Named access policies attachable to sites: this record is the tree's implicit ROOT
 * group (its satisfy column is that group's mode) and the Rules tab holds the tree.
 */
public final class AccessListResource extends RowResource {

    private final FormSpec formSpec = FormSpec.builder()
        .add(AccessListModel.NAME)
        // The select derives from the SATISFY EnumField -- the vocabulary's one
        // declaring home (the old hand-built list here spelled "all" as a literal).
        // This IS the root group's mode; everything else lives in the Rules tab.
        .add(AccessListModel.SATISFY)
        .build();

    // AIDEV-NOTE: an explicit spec. The rules themselves live in their own table now, so
    // "which list holds 10.0.0.5" is answered by the rule search below, not by a column.
    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(AccessListModel.NAME).filterable().build())
        .column(ColumnSpec.fromField(AccessListModel.SATISFY).filterable().build())
        .column(ColumnSpec.fromField(AccessListModel.CREATED_AT).build())
        .filter(FilterSpec.forField(AccessListModel.NAME, FilterSpec.Kind.TEXT)
            .label(FieldLabels.labelFor(AccessListModel.NAME)).build())
        .filter(FilterSpec.forField(AccessListModel.SATISFY, FilterSpec.Kind.SELECT)
            .label(FieldLabels.labelFor(AccessListModel.SATISFY)).build())
        .build();

    @Override
    public @NonNull List<Field<?, ?>> searchFields() {
        return List.of(AccessListModel.NAME);
    }

    /** The rule tree is edited on its own tab: arbitrary nesting has no form shape. */
    @Override
    public @NonNull List<RecordScopedPage<Row>> subpages() {
        List<RecordScopedPage<Row>> pages = new ArrayList<>(List.of(new AccessListRulesPage()));
        pages.addAll(this.frameworkSubpages());
        return pages;
    }

    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull ListChrome listChrome() { return ListChrome.MINIMAL; }

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "access_list"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "access_list"); }
    @Override public @Nullable Microcopy recordLabel() { return Microcopy.of("singular").withFilter("scope", "access_list"); }
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

    /**
     * The list's quick-add bar: a NAME, and nothing else.
     *
     * AIDEV-NOTE: a list created empty is INERT, not a lockout. {@code AccessListGate}
     * short-circuits to ALLOW when a list carries no rules and no credential, so the row
     * this bar creates blocks nobody until an operator opens it and writes the rules --
     * which is why a one-field bar is safe here and would not be on a deny-by-default
     * gate.
     */
    @Override
    public @Nullable QuickCreateSpec quickCreate() {
        return QuickCreateSpec.of(AccessListModel.NAME.getName());
    }

    /**
     * The name only.
     *
     * AIDEV-NOTE: SATISFY is excluded because it is the AND/OR of the REQUEST-TIME gate
     * -- flipping it in a cell changes, on the next request, whether the root group's
     * rules must ALL pass or merely one of them. The same reasoning is why no rule field
     * is inline editable one level down: they are enforced per request, so a mistyped
     * cell locks out live traffic with no form and no refusal to explain it. A stored
     * credential is hashed and never leaves the form layer at all.
     */
    @Override
    public @NonNull List<Field<?, ?>> inlineEditableFields() {
        return List.of(AccessListModel.NAME);
    }

}
