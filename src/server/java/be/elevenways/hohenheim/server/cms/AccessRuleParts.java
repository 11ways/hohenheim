package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.activity.OperationSentences;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.server.auth.AccessRuleNodes;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.CmsMicrocopy;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.render.CmsTemplateIds;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.ResourceAuthority;
import be.elevenways.zenit.cms.common.resource.ResourceForm;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.ResourceMutations;
import be.elevenways.zenit.cms.common.resource.ResourceParent;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.resource.ResourceTabs;
import be.elevenways.zenit.cms.common.resource.RowSave;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.cms.server.panel.PanelTreeOrderActions;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.Nested;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationCommand;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.server.operation.OperationHandlers;
import be.elevenways.zenit.server.operation.TreeOrderOperations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * The access rules' shared parts, and the admin rule resource and its /manage twin built from them: one node of an
 * access list's rule tree (nav-hidden; reached through the list's Rules tab, which owns creation and nesting).
 *
 * AIDEV-NOTE: where a node SITS is the tree's, never the form's: the model carries core's TreeBehaviour
 * ({@link AccessRuleModel#TREE}: dense sibling positions per list, a cycle guard, and a delete that takes the
 * subtree), so the move verbs are core's {@link #ORDER} over it, and the delete is {@link #DELETE}, the canonical
 * delete (the legacy resource's own {@code access_rule_delete} duplicated it and is gone). The form edits only what
 * a node MEANS: its type, that type's own fields and whether it counts; {@link #ADD} and {@link #ADD_INSIDE} ask the
 * SAME form, and say where the node lands by their subject (the list, or the group it goes inside).
 *
 * AIDEV-NOTE: a rule answers to its LIST: writing one (the form, an add, a move, the toggle, the delete) demands
 * {@code manage} on the list it belongs to, asked by each operation's authorizer and the parts' authority alike.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class AccessRuleParts {
    private static final OperationCommand COMMAND = OperationCommand.perSubject();

    /** The virtual column holding the rule's localized one-line summary. */
    static final String RULE_COLUMN = "rule";

    private static final SubjectType<Row> SUBJECT = SubjectType.record(AccessRuleModel.REF);

    private static final SubjectType<Row> LIST = SubjectType.record(AccessListModel.REF);

    /** What a rule MEANS, the one form its edit page and both add dialogs ask. */
    private static final FormSpec FORM = FormSpec.builder()
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(AccessRuleModel.TYPE))
        .add(Nested.of(AccessRuleModel.DATA).schemaFrom(AccessRuleModel.TYPE.getName()).build())
        .add(AccessRuleModel.ENABLED)
        .build();

    /**
     * A new rule as the add dialog answered it.
     *
     * @param type    one of the model's rule types
     * @param data    that type's own fields, a password still sealed
     * @param enabled whether the rule counts from the moment it is added
     */
    public record NewRule(@NonNull String type, @NonNull Map<String, Object> data, boolean enabled) {
    }

    private static final OperationInput<NewRule> INPUT = OperationInput.of(FORM, NewRule.class, values -> new NewRule(
        Objects.requireNonNull(values.get(AccessRuleModel.TYPE), "the rule form requires a type"),
        dataOf(values.get(AccessRuleModel.DATA.getName())),
        Boolean.TRUE.equals(values.get(AccessRuleModel.ENABLED))));

    /** Adds a rule at the top of a list's tree, answered with the new rule's id. */
    public static final Operation<Row, NewRule, Integer> ADD = Operation.declare(HohenheimIds.id("access_rule_add"))
        .happened(OperationSentences.of("access_rule_add"))
        .label(HohenheimMicrocopy.ACCESS_RULE.of("add_action"))
        .icon(Icon.of("plus"))
        .one(LIST)
        .gate(OperationGate.open())
        .input(INPUT)
        .result(Integer.class)
        .command(COMMAND)
        .register();

    /** Adds a rule inside a group rule, after the group's own rules, answered with the new rule's id. */
    public static final Operation<Row, NewRule, Integer> ADD_INSIDE =
        Operation.declare(HohenheimIds.id("access_rule_add_inside"))
            .happened(OperationSentences.of("access_rule_add_inside"))
            .label(HohenheimMicrocopy.ACCESS_RULE.of("add_inside"))
            .description(HohenheimMicrocopy.ACCESS_RULE.of("add_inside_title"))
            .icon(Icon.of("plus"))
            .one(SUBJECT)
            .gate(OperationGate.open())
            .input(INPUT)
            .result(Integer.class)
            .command(COMMAND)
            .register();

    /** Moves a rule one place up or down among its own siblings; offered dead at either end of its group. */
    public static final TreeOrderOperations ORDER = TreeOrderOperations.declare(HohenheimIds.id("access_rule"),
        AccessRuleModel.REF, OperationGate.open(), (rule, input, access) -> writeRefusal(rule, access));

    /** Switches a rule on or off; switching one on runs the model's completeness hook. */
    public static final Operation<Row, Void, Void> TOGGLE = Operation.declare(HohenheimIds.id("access_rule_toggle"))
        .happened(OperationSentences.of("access_rule_toggle"))
        .label(HohenheimMicrocopy.ACCESS_RULE.of("toggle"))
        .icon(Icon.of("power-off"))
        .one(SUBJECT)
        .gate(OperationGate.open())
        // Placed as a row action: a resubmitted click answers from the receipt instead of switching back.
        .command(COMMAND)
        .register();

    /** Deletes a rule and, for a group, the subtree under it (the tree's delete policy). */
    public static final Operation<Row, Void, Integer> DELETE =
        Operation.declare(HohenheimIds.id("delete_access_rule"))
            .happened(OperationSentences.of("delete_access_rule"))
            .label(CmsMicrocopy.of("delete"))
            .icon(Icon.TRASH)
            .one(SUBJECT)
            .gate(OperationGate.open())
            .facts(OperationFact.DESTRUCTIVE)
            .result(Integer.class)
            .command(COMMAND)
            .register();

    static {
        OperationHandlers.attach(TOGGLE)
            .authorize((rule, input, access) -> writeRefusal(rule, access))
            .handle(call -> {
                Row rule = call.subject();
                rule.set(AccessRuleModel.ENABLED, !Boolean.TRUE.equals(rule.get(AccessRuleModel.ENABLED)));
                // Enabling runs the model's completeness hook: a half-configured rule is refused here rather than
                // becoming a request-time FAIL on a live site.
                Models.get(AccessRuleModel.class).save(rule);
                return null;
            });
        OperationHandlers.attach(DELETE)
            .authorize((rule, input, access) -> writeRefusal(rule, access))
            .handle(call -> Models.get(AccessRuleModel.class).delete(call.subject()) ? 1 : 0);
        OperationHandlers.attach(ADD)
            .authorize((list, input, access) -> listRefusal(list.get(AccessListModel.ID), access))
            .handle(call -> added(call.subject().get(AccessListModel.ID), null, call.input()));
        // The subject IS the parent: a rule lands inside the group it was asked on, in that group's own list, so no
        // parent of another list (nor a leaf) can be named.
        OperationHandlers.attach(ADD_INSIDE)
            .authorize((group, input, access) -> AccessRuleModel.TYPE_GROUP.equals(group.get(AccessRuleModel.TYPE))
                ? writeRefusal(group, access) : notFound(group))
            .handle(call -> added(call.subject().get(AccessRuleModel.ACCESS_LIST_ID),
                call.subject().get(AccessRuleModel.ID), call.input()));
    }

    private AccessRuleParts() {
    }

    /** @return the admin access-rule resource */
    public static @NonNull PanelResource<Row> admin() {
        return entry(HohenheimIds.id("access_rule"))
            .tabs(ResourceTabs.<Row>none().withHistory().withContributions())
            .build();
    }

    /** @return the /manage twin: the rules of the lists the caller manages */
    public static @NonNull PanelResource<Row> manage() {
        return ManageTwin.reached(entry(ManageTwin.id("access_rule")), TenantScopes.ACCESS_RULES,
                ResourceTabs.<Row>none().withContributions())
            .build();
    }

    /** The identity, list, form, reads, writes, actions, parent and authority both twins share. */
    private static PanelResource.@NonNull Builder<Row> entry(@NonNull Identifier id) {
        // A rule has no name: it is what it decides, so its first column is that summary. search_text is DATA (it
        // opens with the raw type token), so it stays a search field and is never shown.
        TableSpec<Row> table = TableSpec.<Row>builder()
            .column(ColumnSpec.virtual(RULE_COLUMN, HohenheimMicrocopy.ACCESS_RULE.of("rule")).build())
            .column(ColumnSpec.fromField(AccessRuleModel.TYPE).filterable().build())
            .column(ColumnSpec.fromField(AccessRuleModel.ACCESS_LIST_ID)
                .relation(RelationPick.of(AccessRuleModel.ACCESS_LIST_ID, AccessListModel.MODEL_ID).build())
                .build())
            // The SAME on/off pill the Rules tab shows: a boolean cell would say Yes/No, which is not the word the
            // toggle or the tab uses for the same fact.
            .column(ColumnSpec.virtual(AccessRuleModel.ENABLED.getName(), FieldLabels.labelFor(AccessRuleModel.ENABLED))
                .renderer(CmsTemplateIds.CELL_ENUM_BADGE).build())
            .build();
        return PanelResource.builder(id, HohenheimSlugs.ACCESS_RULES, SUBJECT)
            .label(HohenheimMicrocopy.ACCESS_RULE.of("plural"))
            .recordLabel(HohenheimMicrocopy.ACCESS_RULE.of("singular"))
            .icon(Icon.of("shield-halved"))
            .navGroup(HohenheimPanel.NETWORK_GROUP)
            .navOrder(31)
            .showInNav(false)
            .parent(ResourceParent.of(HohenheimSlugs.ACCESS_LISTS, AccessRuleModel.ACCESS_LIST_ID)
            .tab(HohenheimSlugs.Tab.RULES))
            .reads(ResourceReads.<Row>rows().title(AccessRuleSummaries::titleOf))
            .list(ResourceList.rows(table).chrome(ListChrome.MINIMAL).facets().ruleFilters()
                .search(AccessRuleModel.SEARCH_TEXT)
                .computed(Objects.requireNonNull(table.column(RULE_COLUMN)),
                    (rule, request) -> AccessRuleSummaries.titleOf(rule))
                .computed(Objects.requireNonNull(table.column(AccessRuleModel.ENABLED.getName())),
                    (rule, request) -> AccessRuleSummaries.enabledBadge(rule))
                .build())
            .form(ResourceForm.<Row>of(FORM).build())
            // No create: a rule is born inside a tree, through ADD on the list's Rules tab or ADD_INSIDE on a group; a
            // generic create would produce a node belonging to no list and enforcing nothing.
            .writes(ResourceMutations.rows().update().beforeSave(AccessRuleParts::hashPassword).delete(DELETE).build())
            .actions(Stream.concat(PanelTreeOrderActions.of(ORDER).stream(),
                Stream.of(toggleAction(), addInsideAction())).toList())
            .authority(ResourceAuthority.<Row>builder()
                .write(null, (rule, access) -> writeRefusal(rule, access) == null)
                .build());
    }

    /** The rule form's typed password reaches the row hashed, through the one home the add operations use too. */
    private static void hashPassword(@NonNull RowSave save) {
        Row rule = save.row();
        Map<String, Object> data = AccessRuleModel.dataOf(rule);
        Map<String, Object> hashed = AccessRuleNodes.withHashedPassword(data);
        if (hashed != data) {
            rule.set(AccessRuleModel.DATA, hashed);
        }
    }

    /** @return the new rule's id, the rule landed in the list's tree under {@code parentId} (null: the top) */
    private static @NonNull Integer added(@NonNull Integer listId, @Nullable Integer parentId, @NonNull NewRule rule) {
        return AccessRuleNodes.add(listId, parentId, rule.type(), rule.data(), rule.enabled())
            .get(AccessRuleModel.ID);
    }

    /** @return the form's nested type fields as a map, empty when the form answered none */
    @SuppressWarnings("unchecked")
    private static @NonNull Map<String, Object> dataOf(@Nullable Object data) {
        return data instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
    }

    /** @return null when the caller may write the rule (manage on its list), else the not-found refusal */
    private static @Nullable DomainRefusal writeRefusal(@NonNull Row rule, @NonNull AccessContext access) {
        return listRefusal(rule.get(AccessRuleModel.ACCESS_LIST_ID), access) == null ? null : notFound(rule);
    }

    /** @return null when the caller manages the list, else the not-found refusal (never an existence oracle) */
    private static @Nullable DomainRefusal listRefusal(@Nullable Integer listId, @NonNull AccessContext access) {
        return HohenheimAccess.reachesRecord(access, AccessListModel.MODEL_ID, listId, HohenheimCapabilities.MANAGE)
            ? null : new DomainRefusal(ZenitRefusalReason.NOT_FOUND, "access list " + listId + " is not reachable");
    }

    private static @NonNull DomainRefusal notFound(@NonNull Row rule) {
        return new DomainRefusal(ZenitRefusalReason.NOT_FOUND, "rule " + rule.get(AccessRuleModel.ID)
            + " is not reachable");
    }

    private static boolean switchedOn(@NonNull Row rule) {
        Row stored = Models.get(AccessRuleModel.class).findById(rule.get(AccessRuleModel.ID));
        return stored != null && Boolean.TRUE.equals(stored.get(AccessRuleModel.ENABLED));
    }

    private static @NonNull PanelAction<Row> toggleAction() {
        return PanelAction.<Row, Void>places(TOGGLE, ActionPlacement.ROW, (request, result) ->
                // The toast names the state the rule landed in, read back from the store.
                CmsActionResult.refreshWithToast(HohenheimMicrocopy.ACCESS_RULE.of(switchedOn(request.subject())
                    ? "turned_on" : "turned_off")))
            // The button says what the CLICK does, not what the field is called.
            .dynamicLabel(rule -> HohenheimMicrocopy.ACCESS_RULE.of(
                Boolean.TRUE.equals(rule.get(AccessRuleModel.ENABLED)) ? "switch_off" : "switch_on"))
            .description(HohenheimMicrocopy.ACCESS_RULE.of("toggle_hint"))
            .build();
    }

    /**
     * The list's "Add rule": the rule form in a dialog titled by the list, landing the rule at the top of its tree,
     * placed by the list's Rules tab, which draws it once, in its Rules card.
     */
    static @NonNull PanelAction<Row> addAction() {
        return addPlacement(ADD)
            .style(ActionStyle.PRIMARY)
            .confirmation(addDialog(HohenheimMicrocopy.ACCESS_RULE.of("add_action")))
            .dynamicConfirmation(list -> addDialog(HohenheimMicrocopy.ACCESS_RULE.of("add_title")
                .withArg("list", String.valueOf((Object) list.get(AccessListModel.NAME)))))
            .build();
    }

    /** A group's "Add inside": the same dialog, landing the rule after the group's own rules; offered on groups only. */
    private static @NonNull PanelAction<Row> addInsideAction() {
        return addPlacement(ADD_INSIDE)
            .confirmation(addDialog(HohenheimMicrocopy.ACCESS_RULE.of("add_inside_title")))
            .hiddenWhen(rule -> !AccessRuleModel.TYPE_GROUP.equals(rule.get(AccessRuleModel.TYPE)))
            .build();
    }

    /**
     * Both adds as one placement: the dialog opens on a password rule, and the reader stays where it asked, the new
     * row in front of it and a toast saying it was added.
     */
    private static PanelAction.@NonNull OperationBuilder<Row, Integer> addPlacement(
            @NonNull Operation<Row, NewRule, Integer> operation) {
        return PanelAction.<Row, Integer>places(operation, ActionPlacement.ROW, (request, ruleId) ->
                CmsActionResult.refreshWithToast(HohenheimMicrocopy.ACCESS_RULE.of("added")))
            .inputValues((subject, request) ->
                Map.of(AccessRuleModel.TYPE.getName(), AccessRuleModel.TYPE_BASIC_AUTH));
    }

    private static @NonNull ConfirmationSpec addDialog(@NonNull Microcopy title) {
        return ConfirmationSpec.of(title, HohenheimMicrocopy.ACCESS_RULE.of("add_action"),
            HohenheimMicrocopy.ACCESS_RULE.of("add_lead"), ActionStyle.DEFAULT);
    }
}
