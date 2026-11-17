package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.access.AccessRuleView;
import be.elevenways.hohenheim.access.GuardedPlace;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.PanelAction;
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
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Rules tab on an access list: the one line naming what the list guards, then the rule TREE with the list's own
 * "Add rule" in its card's header, and every node's own add-inside/move/toggle/delete actions.
 *
 * The tree renders as a depth-ordered FLAT list rather than through {@code pl-tree}: that
 * component is a selection surface whose item label is a plain String property, and every
 * row here carries badges, a summary and a row of action forms. Nesting is carried by an
 * indent depth and a dotted outline number.
 *
 * AIDEV-NOTE: adding is a placed operation on the one invoke route, never a form of this page: "Add rule" is the
 * tab's own ROW action over the list ({@link AccessRuleParts#addAction}), drawn here once, and "Add inside" is the
 * rule resource's ROW action, offered on a group's row with the rest of that row's actions.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class AccessListRulesPage implements RecordTab.Rendered<Row> {

    /** The list's "Add rule"; one instance, so the invoke route binds the action this page drew. */
    private static final PanelAction<Row> ADD = AccessRuleParts.addAction();

    @Override public @NonNull Identifier id() { return HohenheimIds.id("access_list_rules"); }
    @Override public @NonNull Microcopy label() { return HohenheimMicrocopy.ACCESS_RULE.of("plural"); }
    @Override public @NonNull String slug() { return HohenheimSlugs.Tab.RULES; }
    @Override public @NonNull Icon icon() { return Icon.of("sitemap"); }
    @Override public @NonNull List<PanelAction<Row>> actions() { return List.of(ADD); }

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
        flatten(roots, childrenByParent, 0, "", views, panel, pageUrl, offers);

        Map<String, Object> vars = new HashMap<>();
        vars.put("title",
            CmsSupport.pageTitle(conduit, HohenheimMicrocopy.ACCESS_RULE, list.get(AccessListModel.NAME)));
        vars.put("satisfy", list.get(AccessListModel.SATISFY));
        vars.put("rules", views);
        vars.put("addRule", addRule(request, list, pageUrl));
        vars.put("guards", guards(listId, accessContext, panel));
        vars.put("shared", Boolean.TRUE.equals(list.get(AccessListModel.SHARED)));
        vars.put("head", recordHead(conduit));
        return new RenderTemplateResult(HohenheimTemplateIds.ACCESS_LIST_RULES, vars);
    }

    /** @return the list's "Add rule" as this viewer is offered it, returning to this tab; null when it is not */
    private @Nullable InvokeActionState addRule(@NonNull PanelRequest request, @NonNull Row list,
                                                @NonNull String pageUrl) {
        PanelResource<Row> lists = CmsSupport.rowEntry(request.panel(), HohenheimSlugs.ACCESS_LISTS);
        for (RowOffer offer : PanelActionOffers.tabOffers(List.of(ADD), lists, list, this.slug(), null, request,
                ReturnPath.of(pageUrl))) {
            if (offer.invoke() != null) {
                return offer.invoke();
            }
        }
        return null;
    }

    /**
     * Every place the list guards, in the order {@link DeleteImpact#usesOfAccessList} reads them, each leading to its
     * app's page when this viewer may open that page.
     */
    private static @NonNull List<GuardedPlace> guards(@NonNull Integer listId, @NonNull AccessContext access,
                                                      @NonNull String panel) {
        List<GuardedPlace> guards = new ArrayList<>();
        for (DeleteImpact.AccessListUse use : DeleteImpact.usesOfAccessList(listId)) {
            Integer siteId = use.siteId();
            var target = siteId != null && CmsSupport.opens(access, HohenheimSlugs.SITES, siteId, null)
                ? SiteParts.recordRoute(panel, siteId) : null;
            guards.add(use.path() == null
                ? new GuardedPlace(Microcopy.literal(use.site()),
                    HohenheimMicrocopy.ACCESS_LIST.of("guards_whole_app"), target)
                : new GuardedPlace(HohenheimMicrocopy.ACCESS_LIST.of("protects_path").withArg("path", use.path())
                    .withArg("site", use.site()), null, target));
        }
        return guards;
    }

    /** Walk one level in order, emitting a view per node. */
    private void flatten(@NonNull List<Row> level,
                         @NonNull Map<Integer, List<Row>> childrenByParent,
                         int depth,
                         @NonNull String parentPath,
                         @NonNull List<AccessRuleView> views,
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
                ActionStateTranslator.bandRowOffers(offers.apply(rule), 0).allInvokes()));

            if (isGroup) {
                flatten(childrenByParent.getOrDefault(id, List.of()), childrenByParent,
                    depth + 1, path, views, panel, pageUrl, offers);
            }
        }
    }
}
