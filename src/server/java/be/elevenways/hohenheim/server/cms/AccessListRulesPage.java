package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.access.AccessRuleOption;
import be.elevenways.hohenheim.access.AccessRuleView;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.model.SiteAuthProviderModel;
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
        vars.put("addTarget", HohenheimEndpoints.ACCESS_RULES_ADD
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
                declared != null && declared.getColor() != null ? declared.getColor() : "gray",
                summaryOf(rule, type),
                Boolean.TRUE.equals(rule.get(AccessRuleModel.ENABLED)),
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

    /** What the node decides, in one localized line. */
    private static @NonNull Microcopy summaryOf(@NonNull Row rule, @Nullable String type) {
        Map<String, Object> data = AccessRuleModel.dataOf(rule);
        return switch (type == null ? "" : type) {
            case AccessRuleModel.TYPE_GROUP -> ruleText(
                AccessListModel.SATISFY_ALL.equals(
                    AccessRuleModel.text(data.get(AccessRuleModel.GROUP_SATISFY.getName())))
                    ? "summary_group_all" : "summary_group_any");
            case AccessRuleModel.TYPE_IP_ALLOW, AccessRuleModel.TYPE_IP_DENY -> ruleText("summary_network")
                .withArg("network", blank(data.get(AccessRuleModel.NETWORK.getName())));
            case AccessRuleModel.TYPE_BASIC_AUTH -> ruleText("summary_basic_auth")
                .withArg("username", blank(data.get(AccessRuleModel.BASIC_AUTH_USERNAME.getName())));
            case AccessRuleModel.TYPE_AUTH_PROVIDER -> {
                String permission = AccessRuleModel.text(
                    data.get(AccessRuleModel.PROVIDER_REQUIRED_PERMISSION.getName()));
                Microcopy summary = ruleText(permission == null
                    ? "summary_auth_provider" : "summary_auth_provider_permission")
                    .withArg("provider", providerName(data.get(AccessRuleModel.PROVIDER_ID.getName())));
                yield permission == null ? summary : summary.withArg("permission", permission);
            }
            default -> ruleText("summary_unknown");
        };
    }

    private static @NonNull String providerName(@Nullable Object providerId) {
        if (!(providerId instanceof Number number)) {
            return "";
        }
        Row provider = Models.get(SiteAuthProviderModel.class).find()
            .where(SiteAuthProviderModel.ID.eq(number.intValue())).first();
        return provider != null ? blank(provider.get(SiteAuthProviderModel.NAME)) : "";
    }

    private static @NonNull String blank(@Nullable Object value) {
        return value == null ? "" : String.valueOf(value);
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
        return Microcopy.of(key).withFilter("scope", "access_rule");
    }
}
