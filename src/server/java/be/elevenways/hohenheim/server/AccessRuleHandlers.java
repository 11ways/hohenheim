package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.server.cms.CmsSupport;
import be.elevenways.hohenheim.server.cms.HohenheimFlash;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Adding a node to an access list's rule tree, from the list's Rules tab.
 *
 * The node is created DISABLED unless it is a group: a leaf is enforced per REQUEST, so a
 * rule that counted the moment it appeared -- before the operator typed its network or its
 * credential -- would refuse live traffic between two clicks. A group carries nothing that
 * can be half-typed (an empty group passes), so it starts on.
 */
final class AccessRuleHandlers {

    private AccessRuleHandlers() {
    }

    static void init() {
        HohenheimEndpoints.ACCESS_RULES_ADD.setHandler(conduit -> {
            Integer listId = conduit.getParameter(HohenheimEndpoints.ACCESS_LIST_ID);
            Row list = Models.get(AccessListModel.class).find()
                .where(AccessListModel.ID.eq(listId)).first();
            if (list == null) {
                return HandlerSupport.redirect(CmsRoutes.list(HandlerSupport.ADMIN, "access-lists"));
            }

            var target = CmsRoutes.subpage(HandlerSupport.ADMIN, "access-lists", listId, "rules");
            Map<String, String> form = HandlerSupport.formMap(conduit);
            String type = form.getOrDefault("type", "").trim();
            if (!AccessRuleModel.ALL_TYPES.contains(type)) {
                HohenheimFlash.error(conduit, ruleText("unknown_type"));
                return HandlerSupport.redirect(target);
            }

            AccessRuleModel model = Models.get(AccessRuleModel.class);
            Integer parentId = parentIn(form.get("parent_id"), listId, model);

            Row rule = model.createEmptyRow();
            rule.set(AccessRuleModel.ACCESS_LIST_ID, listId);
            rule.set(AccessRuleModel.PARENT_ID, parentId);
            rule.set(AccessRuleModel.TYPE, type);
            rule.set(AccessRuleModel.SORT, model.findChildren(listId, parentId).size());
            rule.set(AccessRuleModel.ENABLED, AccessRuleModel.TYPE_GROUP.equals(type));
            model.save(rule);

            ActivityLog.record(model, rule.get(AccessRuleModel.ID), "created", type);
            CmsSupport.reloadProxy();

            // A group has nothing to fill in; a leaf does, so the operator lands on its
            // form with a way back to the tree.
            if (AccessRuleModel.TYPE_GROUP.equals(type)) {
                HohenheimFlash.success(conduit, ruleText("added_group"));
                return HandlerSupport.redirect(target);
            }
            return HandlerSupport.redirect(be.elevenways.zenit.server.http.ReturnTarget.bind(
                CmsRoutes.detail(HandlerSupport.ADMIN, "access-rules", rule.get(AccessRuleModel.ID)),
                target.toUrl()));
        });
    }

    /**
     * @return the submitted parent, or null (the list's implicit root) when it is absent or
     *         names a row that is not a GROUP of THIS list -- a rule may not be parented
     *         onto another list's tree or onto a leaf
     */
    private static @Nullable Integer parentIn(@Nullable String submitted, int listId,
                                              AccessRuleModel model) {
        if (submitted == null || submitted.isBlank()) {
            return null;
        }
        int parentId;
        try {
            parentId = Integer.parseInt(submitted.trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
        List<Row> candidates = model.find()
            .where(AccessRuleModel.ID.eq(parentId))
            .and(AccessRuleModel.ACCESS_LIST_ID.eq(listId))
            .and(AccessRuleModel.TYPE.eq(AccessRuleModel.TYPE_GROUP))
            .all();
        return candidates.isEmpty() ? null : parentId;
    }

    private static Microcopy ruleText(String key) {
        return Microcopy.of(key).withFilter("scope", "access_rule");
    }
}
