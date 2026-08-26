package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.access.AccessRuleOption;
import be.elevenways.hohenheim.access.AccessRuleView;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.protoblast.common.http.Uri;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.render.action.InvokeActionState;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.cms.server.render.action.ActionStateTranslator;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.server.http.ReturnTarget;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rules tab on an access list: the rule TREE, an add form that chooses where a new node
 * lands, and every node's own move/toggle/delete actions.
 *
 * The tree renders as a depth-ordered FLAT list rather than through {@code pl-tree}: that
 * component is a selection surface whose item label is a plain String property, and every
 * row here carries badges, a summary and a row of action forms. Nesting is carried by an
 * indent depth and a dotted outline number, which is also how the add form's parent select
 * names a group.
 */
public final class AccessListRulesPage implements RecordScopedPage<Row> {

    private final AccessRuleResource resource = new AccessRuleResource();
    private final ActionStateTranslator actions = new ActionStateTranslator();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "access_list_rules"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "access_rule"); }
    @Override public @NonNull String slug() { return "rules"; }
    @Override public @NonNull Icon icon() { return Icon.of("sitemap"); }

    @Override
    public @NonNull ActionResult<?> render(@NonNull Conduit conduit,
                                           @NonNull AccessContext accessContext,
                                           @NonNull Row list) {
        Integer listId = list.get(AccessListModel.ID);
        String panel = CmsSupport.panelSlug(conduit);
        String pageUrl = CmsRoutes.subpage(panel, "access-lists", listId, this.slug()).toUrl();

        List<Row> rules = Models.get(AccessRuleModel.class).findForAccessList(listId);
        Map<Integer, List<Row>> childrenByParent = new LinkedHashMap<>();
        List<Row> roots = new ArrayList<>();
        for (Row rule : rules) {
            Integer parent = rule.get(AccessRuleModel.PARENT_ID);
            if (parent == null) {
                roots.add(rule);
            } else {
                childrenByParent.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(rule);
            }
        }

        List<AccessRuleView> views = new ArrayList<>();
        List<AccessRuleOption> parents = new ArrayList<>();
        parents.add(new AccessRuleOption("", ruleText("root_group")));
        flatten(roots, childrenByParent, 0, "", views, parents, accessContext, panel, pageUrl);

        Map<String, Object> vars = new HashMap<>();
        vars.put("title", CmsSupport.pageTitle(conduit, "access_rule", list.get(AccessListModel.NAME)));
        vars.put("listName", list.get(AccessListModel.NAME));
        vars.put("satisfy", list.get(AccessListModel.SATISFY));
        vars.put("rules", views);
        vars.put("parentOptions", parents);
        vars.put("typeOptions", typeOptions());
        // The add form posts to the lane of the panel it renders under: the admin lane is
        // admin-gated, the /manage lane is manage-gated plus the handler's per-list check.
        // The panel slug literal is the SiteDomainsPage precedent.
        vars.put("addTarget", ("admin".equals(panel)
            ? HohenheimEndpoints.ACCESS_RULES_ADD
            : HohenheimEndpoints.MANAGE_ACCESS_RULES_ADD)
            .with(HohenheimEndpoints.ACCESS_LIST_ID, listId));
        vars.put("recordTabs", recordTabs(conduit));
        return new RenderTemplateResult(Identifier.of("hohenheim", "cms/access-list-rules"), vars);
    }

    /** Walk one level in order, emitting a view per node and an option per group. */
    private void flatten(@NonNull List<Row> level,
                         @NonNull Map<Integer, List<Row>> childrenByParent,
                         int depth,
                         @NonNull String parentPath,
                         @NonNull List<AccessRuleView> views,
                         @NonNull List<AccessRuleOption> parents,
                         @NonNull AccessContext accessContext,
                         @NonNull String panel,
                         @NonNull String pageUrl) {
        int position = 0;
        for (Row rule : level) {
            position++;
            String path = parentPath.isEmpty() ? String.valueOf(position) : parentPath + "." + position;
            Integer id = rule.get(AccessRuleModel.ID);
            String type = rule.get(AccessRuleModel.TYPE);
            boolean isGroup = AccessRuleModel.TYPE_GROUP.equals(type);
            EnumField.EnumValue declared = AccessRuleModel.TYPE.getValues().get(type);

            views.add(new AccessRuleView(
                id != null ? id : 0,
                depth,
                "--hh-rule-depth: " + depth,
                path,
                type != null ? type : "",
                declared != null ? declared.getLabel() : ruleText("unknown_type"),
                declared != null && declared.getIcon() != null
                    ? declared.getIcon().name() : "circle-exclamation",
                AccessRuleSummaries.summaryOf(rule, type),
                AccessRuleSummaries.enabledBadge(rule),
                isGroup,
                CmsRoutes.detail(panel, this.resource.slug(), id),
                invokesFor(rule, accessContext, panel, id, pageUrl)));

            if (isGroup) {
                parents.add(new AccessRuleOption(String.valueOf(id),
                    ruleText("group_at").withArg("path", path)));
                flatten(childrenByParent.getOrDefault(id, List.of()), childrenByParent,
                    depth + 1, path, views, parents, accessContext, panel, pageUrl);
            }
        }
    }

    /** The rule resource's own actions for THIS row and viewer, targeting its invoke route. */
    private @NonNull List<InvokeActionState> invokesFor(@NonNull Row rule,
                                                        @NonNull AccessContext accessContext,
                                                        @NonNull String panel,
                                                        @Nullable Integer ruleId,
                                                        @NonNull String pageUrl) {
        ActionStateTranslator.RowActionPresentation presentation =
            this.actions.translateRowActionsForList(this.resource.rowActions(), rule,
                (actionId, row) -> new Uri(ReturnTarget.bind(
                    CmsRoutes.invokeRow(panel, this.resource.slug(), ruleId, actionId),
                    pageUrl).toUrl()),
                accessContext);
        List<InvokeActionState> invokes = new ArrayList<>(presentation.inlineInvokes());
        invokes.addAll(presentation.overflowInvokes());
        return invokes;
    }

    /** The add form's type choices, DERIVED from the model's type vocabulary. */
    private static @NonNull List<AccessRuleOption> typeOptions() {
        List<AccessRuleOption> options = new ArrayList<>();
        for (Map.Entry<String, EnumField.EnumValue> value : AccessRuleModel.TYPE.getValues().entrySet()) {
            options.add(new AccessRuleOption(value.getKey(), value.getValue().getLabel()));
        }
        return options;
    }

    private static @NonNull Microcopy ruleText(@NonNull String key) {
        return AccessRuleSummaries.ruleText(key);
    }
}
