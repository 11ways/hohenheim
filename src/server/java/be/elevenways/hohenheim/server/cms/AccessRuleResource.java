package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.server.auth.BasicCredentials;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.ResourceParent;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.Nested;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One node of an access list's rule tree (nav-hidden; reached through the list's Rules
 * tab, which owns creation, nesting and ordering).
 *
 * The form edits only what a node MEANS -- its type, that type's own fields, and whether
 * it counts -- because where a node SITS is a property of the tree, not of the row: the
 * tab's add form chooses the parent and the move actions choose the order, so no operator
 * types a parent id into a text box.
 */
public final class AccessRuleResource extends RowResource {

    private final FormSpec formSpec = FormSpec.builder()
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(AccessRuleModel.TYPE))
        .add(Nested.of(AccessRuleModel.DATA).schemaFrom("type").build())
        .add(AccessRuleModel.ENABLED)
        .build();

    // AIDEV-NOTE: this list answers "which access list holds 10.0.0.5", which the flat
    // shape answered with a column on the LIST. The address lives in per-type JSON now,
    // so the searchable half is the derived search_text column.
    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(AccessRuleModel.SEARCH_TEXT).build())
        .column(ColumnSpec.fromField(AccessRuleModel.TYPE).filterable().build())
        .column(ColumnSpec.fromField(AccessRuleModel.ACCESS_LIST_ID)
            .relation(RelationPick.of(AccessRuleModel.ACCESS_LIST_ID, AccessListModel.MODEL_ID).build())
            .build())
        .column(ColumnSpec.fromField(AccessRuleModel.ENABLED).build())
        .build();

    @Override
    public @NonNull List<be.elevenways.zenit.common.orm.field.Field<?, ?>> searchFields() {
        return List.of(AccessRuleModel.SEARCH_TEXT);
    }

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "access_rule"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "access_rule"); }
    @Override public @NonNull String slug() { return "access-rules"; }
    @Override public @NonNull Model model() { return Models.get(AccessRuleModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull ListChrome listChrome() { return ListChrome.MINIMAL; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.NETWORK_GROUP; }
    @Override public int navOrder() { return 31; }
    @Override public boolean showInNav() { return false; }
    @Override public @NonNull Icon icon() { return Icon.of("shield-halved"); }

    /**
     * A rule is born inside a tree, so the tab's add form is its only birth: a generic
     * create form would produce a node belonging to no list and enforcing nothing.
     */
    @Override public boolean creatable() { return false; }

    @Override
    public @Nullable ResourceParent<Row> parent() {
        return ResourceParent.<Row>of("access-lists",
            row -> row.get(AccessRuleModel.ACCESS_LIST_ID)).tab("rules");
    }

    /**
     * The password is typed in plaintext and stored as an argon2 hash, through the ONE
     * credential home the request-time gate verifies with.
     *
     * A blank submit arrives here as the stored hash ({@code FormSecrets} restores secret
     * fields), which {@code hashIfNeeded} leaves alone -- keep-blank keeps the password.
     */
    @Override
    public void applyValuesToRow(@NonNull Row row, @NonNull Map<String, Object> coerced) {
        super.applyValuesToRow(row, coerced);
        Map<String, Object> data = AccessRuleModel.dataOf(row);
        Object password = data.get(AccessRuleModel.BASIC_AUTH_PASSWORD.getName());
        if (password instanceof String plain && !plain.isBlank()) {
            Map<String, Object> hashed = new LinkedHashMap<>(data);
            hashed.put(AccessRuleModel.BASIC_AUTH_PASSWORD.getName(),
                BasicCredentials.hashIfNeeded(plain));
            row.set(AccessRuleModel.DATA, hashed);
        }
    }

    /** Saving a rule changes who reaches a live site: the proxy reloads immediately. */
    @Override
    public void updateRow(@NonNull Row existing, @NonNull Map<String, Object> coerced,
                          @NonNull AccessContext accessContext) {
        super.updateRow(existing, coerced, accessContext);
        CmsSupport.reloadProxy();
    }

    /**
     * Deleting a group takes its whole subtree with it: an orphaned rule is a policy the
     * proxy cannot reconstruct, and {@code AccessRuleTree} refuses (denies) a list that
     * carries one.
     */
    @Override
    public void deleteRow(@NonNull Row existing, @NonNull AccessContext accessContext) {
        deleteSubtree(existing);
        CmsSupport.reloadProxy();
    }

    private static void deleteSubtree(@NonNull Row rule) {
        AccessRuleModel model = Models.get(AccessRuleModel.class);
        Integer listId = rule.get(AccessRuleModel.ACCESS_LIST_ID);
        Integer id = rule.get(AccessRuleModel.ID);
        if (listId != null && id != null) {
            for (Row child : model.findChildren(listId, id)) {
                deleteSubtree(child);
            }
        }
        model.delete(rule);
    }

    @Override
    public @NonNull List<RowAction<Row>> rowActions() {
        List<RowAction<Row>> actions = new ArrayList<>(super.rowActions());
        actions.add(moveAction("access_rule_move_up", "move_up", "arrow-up", -1));
        actions.add(moveAction("access_rule_move_down", "move_down", "arrow-down", 1));
        actions.add(RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "access_rule_toggle"))
            .label(Microcopy.of("toggle").withFilter("scope", "access_rule"))
            .icon(Icon.of("power-off"))
            .description(Microcopy.of("toggle_hint").withFilter("scope", "access_rule"))
            .handler((row, ctx) -> {
                boolean enabled = Boolean.TRUE.equals(row.get(AccessRuleModel.ENABLED));
                row.set(AccessRuleModel.ENABLED, !enabled);
                // Enabling runs the model's completeness hook: a half-configured rule is
                // refused here rather than becoming a request-time FAIL on a live site.
                Models.get(AccessRuleModel.class).save(row);
                CmsSupport.reloadProxy();
                return CmsActionResult.refreshWithToast(Microcopy
                    .of(enabled ? "turned_off" : "turned_on").withFilter("scope", "access_rule"));
            })
            .build());
        actions.add(RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "access_rule_delete"))
            .label(Microcopy.of("delete").withFilter("scope", "access_rule"))
            .icon(Icon.of("trash-can"))
            .description(Microcopy.of("delete_hint").withFilter("scope", "access_rule"))
            .confirmation(ConfirmationSpec.destructive(
                Microcopy.of("delete_confirm").withFilter("scope", "access_rule")))
            .handler((row, ctx) -> {
                deleteRow(row, ctx.access());
                return CmsActionResult.refreshWithToast(
                    Microcopy.of("deleted").withFilter("scope", "access_rule"));
            })
            .build());
        return actions;
    }

    private @NonNull RowAction<Row> moveAction(@NonNull String actionId, @NonNull String copyKey,
                                               @NonNull String icon, int direction) {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", actionId))
            .label(Microcopy.of(copyKey).withFilter("scope", "access_rule"))
            .icon(Icon.of(icon))
            .description(Microcopy.of(copyKey + "_hint").withFilter("scope", "access_rule"))
            .handler((row, ctx) -> {
                move(row, direction);
                CmsSupport.reloadProxy();
                return CmsActionResult.refreshWithToast(
                    Microcopy.of("moved").withFilter("scope", "access_rule"));
            })
            .build();
    }

    /**
     * Swap this rule with its neighbour among its OWN siblings; a rule at the edge of its
     * group stays put rather than escaping into the parent's order.
     */
    private static void move(@NonNull Row rule, int direction) {
        AccessRuleModel model = Models.get(AccessRuleModel.class);
        Integer listId = rule.get(AccessRuleModel.ACCESS_LIST_ID);
        if (listId == null) {
            return;
        }
        List<Row> siblings = model.findChildren(listId, rule.get(AccessRuleModel.PARENT_ID));
        int index = -1;
        for (int i = 0; i < siblings.size(); i++) {
            if (siblings.get(i).get(AccessRuleModel.ID).equals(rule.get(AccessRuleModel.ID))) {
                index = i;
                break;
            }
        }
        int target = index + direction;
        if (index < 0 || target < 0 || target >= siblings.size()) {
            return;
        }

        // Rewrite the whole sibling run: stored sort values may be equal (rows added in one
        // batch), and swapping two equal values would reorder nothing.
        List<Row> reordered = new ArrayList<>(siblings);
        reordered.set(index, siblings.get(target));
        reordered.set(target, siblings.get(index));
        for (int position = 0; position < reordered.size(); position++) {
            Row sibling = reordered.get(position);
            sibling.set(AccessRuleModel.SORT, position);
            model.save(sibling);
        }
    }
}
