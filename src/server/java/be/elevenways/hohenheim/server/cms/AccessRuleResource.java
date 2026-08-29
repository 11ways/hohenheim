package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.server.auth.BasicCredentials;
import be.elevenways.protoblast.common.http.Uri;
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
import be.elevenways.zenit.cms.server.render.table.TableStateTranslator;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.Nested;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.server.http.ReturnTarget;
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
 *
 * AIDEV-NOTE: deleting a group takes its whole subtree with it -- an orphaned rule is a
 * policy the proxy cannot reconstruct, and {@code AccessRuleTree} refuses (denies) a list
 * that carries one. The subtree walk lives in {@code AccessRuleCascades}, on the model
 * funnel, so a rule deleted by anything but this form (a list delete, a direct save, the
 * peer API) takes its children too.
 *
 * AIDEV-NOTE: this resource carries NO proxy reload of its own any more. Every write here
 * (save, delete, toggle, move) goes through the model, and {@code ProxyReloadHooks} now
 * carries {@code AccessRuleModel} -- one reload path, after the transaction commits,
 * whatever wrote the row.
 */
public class AccessRuleResource extends RowResource {

    private final FormSpec formSpec = FormSpec.builder()
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(AccessRuleModel.TYPE))
        .add(Nested.of(AccessRuleModel.DATA).schemaFrom("type").build())
        .add(AccessRuleModel.ENABLED)
        .build();

    /** The virtual column holding the rule's localized one-line summary. */
    private static final String RULE_COLUMN = "rule";

    // AIDEV-NOTE: this list answers "which access list holds 10.0.0.5", which the flat
    // shape answered with a column on the LIST. The address lives in per-type JSON now,
    // so the searchable half is the derived search_text column -- which is DATA (it
    // starts with the raw type token) and was rendering as this list's name cell. The
    // name cell is the summary instead, and search_text keeps its job as a search field
    // that is never shown.
    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.virtual(RULE_COLUMN,
            Microcopy.of("rule").withFilter("scope", "access_rule")).build())
        .column(ColumnSpec.fromField(AccessRuleModel.TYPE).filterable().build())
        .column(ColumnSpec.fromField(AccessRuleModel.ACCESS_LIST_ID)
            .relation(RelationPick.of(AccessRuleModel.ACCESS_LIST_ID, AccessListModel.MODEL_ID).build())
            .build())
        // The switch renders as the SAME on/off pill the rules tab shows, through the
        // shared enum-badge cell: a boolean cell would say Yes/No, which is not the word
        // the toggle action or the tab uses for the same fact.
        .column(ColumnSpec.fromField(AccessRuleModel.ENABLED)
            .renderer(TableStateTranslator.ENUM_BADGE_RENDERER).build())
        .build();

    @Override
    public @NonNull List<be.elevenways.zenit.common.orm.field.Field<?, ?>> searchFields() {
        return List.of(AccessRuleModel.SEARCH_TEXT);
    }

    /**
     * The rule's own line: its declared type label and what it decides, both localized
     * through the shared {@link AccessRuleSummaries} home the Rules tab renders from.
     */
    @Override
    public @Nullable Object cellValue(@NonNull Row row, @NonNull ColumnSpec column) {
        if (RULE_COLUMN.equals(column.name())) {
            return AccessRuleSummaries.titleOf(row);
        }
        if (AccessRuleModel.ENABLED.getName().equals(column.name())) {
            return AccessRuleSummaries.enabledBadge(row);
        }
        return super.cellValue(row, column);
    }

    /**
     * A rule has no name: it is what it decides. The heading and every breadcrumb say
     * that instead of "AccessRule #7".
     */
    @Override
    public @NonNull String recordTitle(@NonNull Row record) {
        String title = AccessRuleSummaries.titleOf(record);
        return title != null && !title.isBlank() ? title : super.recordTitle(record);
    }

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "access_rule"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "access_rule"); }
    @Override public @Nullable Microcopy recordLabel() { return Microcopy.of("singular").withFilter("scope", "access_rule"); }
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

    @Override
    public @NonNull List<RowAction<Row>> rowActions() {
        List<RowAction<Row>> actions = new ArrayList<>(super.rowActions());
        actions.add(moveAction("access_rule_move_up", "move_up", "arrow-up", -1));
        actions.add(moveAction("access_rule_move_down", "move_down", "arrow-down", 1));
        actions.add(RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "access_rule_toggle"))
            .label(Microcopy.of("toggle").withFilter("scope", "access_rule"))
            // The button says what the CLICK does, not what the field is called: a single
            // "On or off" label left the operator to guess which way this row would move.
            .dynamicLabel(row -> Microcopy
                .of(Boolean.TRUE.equals(row.get(AccessRuleModel.ENABLED))
                    ? "switch_off" : "switch_on")
                .withFilter("scope", "access_rule"))
            .icon(Icon.of("power-off"))
            .description(Microcopy.of("toggle_hint").withFilter("scope", "access_rule"))
            .handler((row, ctx) -> {
                boolean enabled = Boolean.TRUE.equals(row.get(AccessRuleModel.ENABLED));
                row.set(AccessRuleModel.ENABLED, !enabled);
                // Enabling runs the model's completeness hook: a half-configured rule is
                // refused here rather than becoming a request-time FAIL on a live site.
                Models.get(AccessRuleModel.class).save(row);
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
            // AIDEV-NOTE: a delete cannot REFRESH the surface it was invoked from when that
            // surface is the record's own page -- the record is gone and the refresh lands
            // on a 404. The captured return target (the rules tab, which is where the
            // action is reached from) is followed when the page carries one; a tab-scoped
            // invoke carries its own tab, so nothing moves there.
            .handler((row, ctx) -> {
                deleteRow(row, ctx.access());
                Microcopy toast = Microcopy.of("deleted").withFilter("scope", "access_rule");
                Conduit conduit = ctx.access().conduit();
                String returnTo = conduit == null ? null : ReturnTarget.read(conduit);
                if (returnTo == null || returnTo.isEmpty()) {
                    return CmsActionResult.refreshWithToast(toast);
                }
                HohenheimFlash.success(conduit, toast);
                return CmsActionResult.redirect(new Uri(returnTo));
            })
            .build());
        return actions;
    }

    /**
     * @param direction -1 for up, 1 for down; a rule at that edge of its own sibling run is
     *                  offered the action DEAD, saying why -- the click used to be accepted
     *                  and change nothing, which reads as a broken button
     */
    private @NonNull RowAction<Row> moveAction(@NonNull String actionId, @NonNull String copyKey,
                                               @NonNull String icon, int direction) {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", actionId))
            .label(Microcopy.of(copyKey).withFilter("scope", "access_rule"))
            .icon(Icon.of(icon))
            .description(Microcopy.of(copyKey + "_hint").withFilter("scope", "access_rule"))
            .unavailableWhen((row, ctx) -> atEdge(row, direction)
                ? Microcopy.of(direction < 0 ? "already_first" : "already_last")
                    .withFilter("scope", "access_rule")
                : null)
            .handler((row, ctx) -> {
                move(row, direction);
                return CmsActionResult.refreshWithToast(
                    Microcopy.of("moved").withFilter("scope", "access_rule"));
            })
            .build();
    }

    /**
     * Whether this rule has no neighbour in {@code direction} among its OWN siblings --
     * the same run {@link #move} reorders, so the refusal and the move can never disagree
     * about what "first" means (a lone rule is at both edges at once).
     */
    private static boolean atEdge(@NonNull Row rule, int direction) {
        Integer listId = rule.get(AccessRuleModel.ACCESS_LIST_ID);
        if (listId == null) {
            return true;
        }
        List<Row> siblings = Models.get(AccessRuleModel.class)
            .findChildren(listId, rule.get(AccessRuleModel.PARENT_ID));
        int index = indexOf(siblings, rule);
        int target = index + direction;
        return index < 0 || target < 0 || target >= siblings.size();
    }

    /** @return the rule's position among {@code siblings}, or -1 when it is not among them */
    private static int indexOf(@NonNull List<Row> siblings, @NonNull Row rule) {
        for (int i = 0; i < siblings.size(); i++) {
            if (siblings.get(i).get(AccessRuleModel.ID).equals(rule.get(AccessRuleModel.ID))) {
                return i;
            }
        }
        return -1;
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
        int index = indexOf(siblings, rule);
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
