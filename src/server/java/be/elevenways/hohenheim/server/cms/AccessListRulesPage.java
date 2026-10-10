package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.access.AccessRuleOption;
import be.elevenways.hohenheim.access.AccessRuleView;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.render.action.InvokeActionState;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.RecordTab;
import be.elevenways.zenit.cms.server.panel.PanelActionOffers;
import be.elevenways.zenit.cms.server.render.action.ActionStateTranslator;
import be.elevenways.zenit.cms.server.render.action.RowOffer;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.routing.ReturnPath;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.server.http.ReturnTarget;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

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
public final class AccessListRulesPage implements RecordTab.Rendered<Row> {

    @Override public @NonNull Identifier id() { return HohenheimIds.id("access_list_rules"); }
    @Override public @NonNull Microcopy label() { return HohenheimMicrocopy.ACCESS_RULE.of("plural"); }
    @Override public @NonNull String slug() { return HohenheimSlugs.Tab.RULES; }
    @Override public @NonNull Icon icon() { return Icon.of("sitemap"); }

    @Override
    public @NonNull ActionResult<?> render(@NonNull PanelRequest request, @NonNull Row list) {
        Conduit conduit = request.conduit();
        AccessContext accessContext = request.access();
        Integer listId = list.get(AccessListModel.ID);
        String panel = request.panelSlug();
        String pageUrl = CmsRoutes.subpage(panel, HohenheimSlugs.ACCESS_LISTS, listId, this.slug()).toUrl();

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

        // Each node's actions are the rule entry's own placed operations over the panel's twin, read in one batch the
        // way its list reads them, each invoke returning to this tab.
        Panel cmsPanel = request.panel();
        @SuppressWarnings("unchecked")
        PanelResource<Row> rulesEntry = (PanelResource<Row>) Objects.requireNonNull(
            cmsPanel.entryBySlug(HohenheimSlugs.ACCESS_RULES), "panel " + panel + " declares no access-rule entry");
        Function<Row, List<RowOffer>> offers = PanelActionOffers.rowsForRender(request, rulesEntry, null, rules,
            accessContext, ReturnPath.of(pageUrl));

        List<AccessRuleView> views = new ArrayList<>();
        List<AccessRuleOption> parents = new ArrayList<>();
        parents.add(new AccessRuleOption("", HohenheimMicrocopy.ACCESS_RULE.of("root_group")));
        flatten(roots, childrenByParent, 0, "", views, parents, accessContext, panel, pageUrl, offers);

        Map<String, Object> vars = new HashMap<>();
        vars.put("title",
            CmsSupport.pageTitle(conduit, HohenheimMicrocopy.ACCESS_RULE, list.get(AccessListModel.NAME)));
        vars.put("listName", list.get(AccessListModel.NAME));
        vars.put("satisfy", list.get(AccessListModel.SATISFY));
        vars.put("rules", views);
        vars.put("parentOptions", parents);
        vars.put("typeOptions", typeOptions());
        // The add form posts to the lane of the panel it renders under: the admin lane is
        // admin-gated, the /manage lane is manage-gated plus the handler's per-list check.
        // The panel slug literal is the site Domains tab precedent.
        vars.put("addTarget", (HohenheimSlugs.ADMIN.equals(panel)
            ? HohenheimEndpoints.ACCESS_RULES_ADD
            : HohenheimEndpoints.MANAGE_ACCESS_RULES_ADD)
            .with(HohenheimEndpoints.ACCESS_LIST_ID, listId));
        // Where the list is used, the "Protects" card: a whole app, or one path on an app.
        List<Microcopy> protects = new ArrayList<>();
        for (DeleteImpact.AccessListUse use : DeleteImpact.usesOfAccessList(listId)) {
            protects.add(use.path() == null
                ? HohenheimMicrocopy.ACCESS_LIST.of("protects_site").withArg("site", use.site())
                : HohenheimMicrocopy.ACCESS_LIST.of("protects_path").withArg("path", use.path())
                    .withArg("site", use.site()));
        }
        vars.put("protects", protects);
        vars.put("shared", Boolean.TRUE.equals(list.get(AccessListModel.SHARED)));
        vars.put("head", recordHead(conduit));
        return new RenderTemplateResult(HohenheimTemplateIds.ACCESS_LIST_RULES, vars);
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
                         @NonNull String pageUrl,
                         @NonNull Function<Row, List<RowOffer>> offers) {
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
                declared != null ? declared.getLabel() : HohenheimMicrocopy.ACCESS_RULE.of("unknown_type"),
                declared != null && declared.getIcon() != null
                    ? declared.getIcon().name() : "circle-exclamation",
                AccessRuleSummaries.summaryOf(rule, type),
                AccessRuleSummaries.enabledBadge(rule),
                isGroup,
                // The edit link carries the tab as its return target, like the invokes:
                // a rule's Cancel/Delete then come back here, not to the global rule list.
                ReturnTarget.bind(CmsRoutes.detail(panel, HohenheimSlugs.ACCESS_RULES, id), pageUrl),
                invokesFor(rule, accessContext, offers)));

            if (isGroup) {
                parents.add(new AccessRuleOption(String.valueOf(id),
                    HohenheimMicrocopy.ACCESS_RULE.of("group_at").withArg("path", path)));
                flatten(childrenByParent.getOrDefault(id, List.of()), childrenByParent,
                    depth + 1, path, views, parents, accessContext, panel, pageUrl, offers);
            }
        }
    }

    /** The rule entry's placed operations for THIS row and viewer, on their shared invoke route. */
    private @NonNull List<InvokeActionState> invokesFor(@NonNull Row rule,
                                                        @NonNull AccessContext accessContext,
                                                        @NonNull Function<Row, List<RowOffer>> offers) {
        return ActionStateTranslator.bandRowOffers(offers.apply(rule), 0).allInvokes();
    }

    /** The add form's type choices, DERIVED from the model's type vocabulary. */
    private static @NonNull List<AccessRuleOption> typeOptions() {
        List<AccessRuleOption> options = new ArrayList<>();
        for (Map.Entry<String, EnumField.EnumValue> value : AccessRuleModel.TYPE.getValues().entrySet()) {
            options.add(new AccessRuleOption(value.getKey(), value.getValue().getLabel()));
        }
        return options;
    }
}
