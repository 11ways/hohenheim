package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.activity.OperationSentences;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.server.auth.BasicCredentials;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.PanelAction;
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
import be.elevenways.zenit.cms.server.render.table.TableStateTranslator;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.Nested;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationCommand;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationGate;
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
 * a node MEANS: its type, that type's own fields and whether it counts.
 *
 * AIDEV-NOTE: a rule answers to its LIST: writing one (the form, a move, the toggle, the delete) demands
 * {@code manage} on the list it belongs to, asked by each operation's authorizer and the parts' authority alike.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class AccessRuleParts {
    private static final OperationCommand COMMAND = OperationCommand.perSubject();

    /** The entry slug both twins share, which the Rules tab's links and the add lane's landing name. */
    public static final String SLUG = "access-rules";

    /** The virtual column holding the rule's localized one-line summary. */
    static final String RULE_COLUMN = "rule";

    private static final SubjectType<Row> SUBJECT = SubjectType.record(AccessRuleModel.MODEL_ID);

    /** Moves a rule one place up or down among its own siblings; offered dead at either end of its group. */
    public static final TreeOrderOperations ORDER = TreeOrderOperations.declare(HohenheimIds.id("access_rule"),
        AccessRuleModel.class, OperationGate.open(), (rule, input, access) -> writeRefusal(rule, access));

    /** Switches a rule on or off; switching one on runs the model's completeness hook. */
    public static final Operation<Row, Void, Void> TOGGLE = Operation.declare(HohenheimIds.id("access_rule_toggle"))
        .happened(OperationSentences.of("access_rule_toggle"))
        .label(Microcopy.of("toggle").withFilter("scope", "access_rule"))
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
            .label(Microcopy.of("delete").withFilter("scope", "cms"))
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
    }

    private AccessRuleParts() {
    }

    /** @return the admin access-rule resource */
    public static @NonNull PanelResource<Row> admin() {
        return entry("access_rule")
            .tabs(ResourceTabs.<Row>none().withHistory().withContributions())
            .build();
    }

    /** @return the /manage twin: the rules of the lists the caller manages */
    public static @NonNull PanelResource<Row> manage() {
        return entry("manage_access_rule")
            .scope(TenantScopes.ACCESS_RULES)
            // The contributed tabs only: the admin activity and revision history stays off the delegated surface.
            .tabs(ResourceTabs.<Row>none().withContributions())
            .build();
    }

    /** The identity, list, form, reads, writes, actions, parent and authority both twins share. */
    private static PanelResource.@NonNull Builder<Row> entry(@NonNull String id) {
        // A rule has no name: it is what it decides, so its first column is that summary. search_text is DATA (it
        // opens with the raw type token), so it stays a search field and is never shown.
        TableSpec<Row> table = TableSpec.<Row>builder()
            .column(ColumnSpec.virtual(RULE_COLUMN, Microcopy.of("rule").withFilter("scope", "access_rule")).build())
            .column(ColumnSpec.fromField(AccessRuleModel.TYPE).filterable().build())
            .column(ColumnSpec.fromField(AccessRuleModel.ACCESS_LIST_ID)
                .relation(RelationPick.of(AccessRuleModel.ACCESS_LIST_ID, AccessListModel.MODEL_ID).build())
                .build())
            // The SAME on/off pill the Rules tab shows: a boolean cell would say Yes/No, which is not the word the
            // toggle or the tab uses for the same fact.
            .column(ColumnSpec.virtual(AccessRuleModel.ENABLED.getName(), FieldLabels.labelFor(AccessRuleModel.ENABLED))
                .renderer(TableStateTranslator.ENUM_BADGE_RENDERER).build())
            .build();
        FormSpec form = FormSpec.builder()
            .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(AccessRuleModel.TYPE))
            .add(Nested.of(AccessRuleModel.DATA).schemaFrom("type").build())
            .add(AccessRuleModel.ENABLED)
            .build();
        return PanelResource.builder(HohenheimIds.id(id), SLUG, SUBJECT)
            .label(Microcopy.of("plural").withFilter("scope", "access_rule"))
            .recordLabel(Microcopy.of("singular").withFilter("scope", "access_rule"))
            .icon(Icon.of("shield-halved"))
            .navGroup(HohenheimPanel.NETWORK_GROUP)
            .navOrder(31)
            .showInNav(false)
            .standsUnder(HohenheimSlugs.ACCESS_LISTS)
            .parent(ResourceParent.of(HohenheimSlugs.ACCESS_LISTS, AccessRuleModel.ACCESS_LIST_ID).tab("rules"))
            .reads(ResourceReads.<Row>rows().title(AccessRuleSummaries::titleOf))
            .list(ResourceList.rows(table).chrome(ListChrome.MINIMAL).facets().ruleFilters()
                .search(AccessRuleModel.SEARCH_TEXT)
                .computed(Objects.requireNonNull(table.column(RULE_COLUMN)),
                    (rule, request) -> AccessRuleSummaries.titleOf(rule))
                .computed(Objects.requireNonNull(table.column(AccessRuleModel.ENABLED.getName())),
                    (rule, request) -> AccessRuleSummaries.enabledBadge(rule))
                .build())
            .form(ResourceForm.<Row>of(form).build())
            // No create: a rule is born inside a tree, through the Rules tab's add form; a generic create would
            // produce a node belonging to no list and enforcing nothing.
            .writes(ResourceMutations.rows().update().beforeSave(AccessRuleParts::hashPassword).delete(DELETE).build())
            .actions(Stream.concat(PanelTreeOrderActions.of(ORDER).stream(), Stream.of(toggleAction())).toList())
            .authority(ResourceAuthority.<Row>builder()
                .write(null, (rule, access) -> writeRefusal(rule, access) == null)
                .build());
    }

    /**
     * The password is typed in plaintext and stored as an argon2 hash, through the ONE credential home the
     * request-time gate verifies with. A blank submit arrives as the stored hash (the secret field restores it),
     * which {@code hashIfNeeded} leaves alone: keep-blank keeps the password.
     */
    private static void hashPassword(@NonNull RowSave save) {
        Row rule = save.row();
        Map<String, Object> data = AccessRuleModel.dataOf(rule);
        Object password = data.get(AccessRuleModel.BASIC_AUTH_PASSWORD.getName());
        if (password instanceof String plain && !plain.isBlank()) {
            Map<String, Object> hashed = new LinkedHashMap<>(data);
            hashed.put(AccessRuleModel.BASIC_AUTH_PASSWORD.getName(), BasicCredentials.hashIfNeeded(plain));
            rule.set(AccessRuleModel.DATA, hashed);
        }
    }

    /** @return null when the caller may write the rule (manage on its list), else the not-found refusal */
    private static @Nullable DomainRefusal writeRefusal(@NonNull Row rule, @NonNull AccessContext access) {
        return HohenheimAccess.reachesRecord(access, AccessListModel.MODEL_ID,
            rule.get(AccessRuleModel.ACCESS_LIST_ID), HohenheimAccess.MANAGE) ? null
            : new DomainRefusal(ZenitRefusalReason.NOT_FOUND, "rule " + rule.get(AccessRuleModel.ID)
                + " is not reachable");
    }

    private static boolean switchedOn(@NonNull Row rule) {
        Row stored = Models.get(AccessRuleModel.class).findById(rule.get(AccessRuleModel.ID));
        return stored != null && Boolean.TRUE.equals(stored.get(AccessRuleModel.ENABLED));
    }

    private static @NonNull PanelAction<Row> toggleAction() {
        return PanelAction.<Row, Void>places(TOGGLE, ActionPlacement.ROW, (request, result) ->
                // The toast names the state the rule landed in, read back from the store.
                CmsActionResult.refreshWithToast(Microcopy.of(switchedOn(request.subject())
                    ? "turned_on" : "turned_off").withFilter("scope", "access_rule")))
            // The button says what the CLICK does, not what the field is called.
            .dynamicLabel(rule -> Microcopy.of(Boolean.TRUE.equals(rule.get(AccessRuleModel.ENABLED))
                ? "switch_off" : "switch_on").withFilter("scope", "access_rule"))
            .description(Microcopy.of("toggle_hint").withFilter("scope", "access_rule"))
            .build();
    }
}
