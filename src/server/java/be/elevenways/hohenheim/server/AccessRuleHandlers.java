package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.server.auth.AccessRuleNodes;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.cms.HohenheimFlash;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.security.AccessContext;

import java.util.Map;

/**
 * Adding a node to an access list's rule tree, from the list's Rules tab.
 *
 * The birth itself is {@link AccessRuleNodes}, shared with the automation API; what is
 * here is the panel's half -- the per-list authority walk, the flash copy and where the
 * operator lands afterwards.
 */
final class AccessRuleHandlers {

    private AccessRuleHandlers() {
    }

    static void init() {
        HohenheimEndpoints.ACCESS_RULES_ADD.setHandler(conduit ->
            handleAdd(conduit, HandlerSupport.ADMIN));
        HohenheimEndpoints.MANAGE_ACCESS_RULES_ADD.setHandler(conduit ->
            handleAdd(conduit, HandlerSupport.MANAGE));
    }

    /**
     * One add lane, two panels. The endpoint permission is only the panel gate; the
     * record-level question is {@code manage} on the LIST (the walk's admin row answers
     * ALL for an operator), and the write pipeline (TenantWrites) re-asks it as the gate.
     */
    private static ActionResult<Object> handleAdd(Conduit conduit, String panel) {
        Integer listId = conduit.getParameter(HohenheimEndpoints.ACCESS_LIST_ID);
        Row list = Models.get(AccessListModel.class).find()
            .where(AccessListModel.ID.eq(listId)).first();
        // Absence and out-of-scope are ONE answer, like the resource's 404.
        if (list == null || !HohenheimAccess.reachesRecord(AccessContext.of(conduit),
                AccessListModel.MODEL_ID, listId, HohenheimAccess.MANAGE)) {
            return HandlerSupport.redirect(CmsRoutes.list(panel, "access-lists"));
        }

        var target = CmsRoutes.subpage(panel, "access-lists", listId, "rules");
        Map<String, String> form = HandlerSupport.formMap(conduit);
        String type = form.getOrDefault("type", "").trim();
        if (!AccessRuleModel.ALL_TYPES.contains(type)) {
            HohenheimFlash.error(conduit, ruleText("unknown_type"));
            return HandlerSupport.redirect(target);
        }

        Row rule = AccessRuleNodes.add(listId,
            AccessRuleNodes.parentIn(form.get("parent_id"), listId), type);

        // A group has nothing to fill in; a leaf does, so the operator lands on its
        // form with a way back to the tree.
        if (AccessRuleModel.TYPE_GROUP.equals(type)) {
            HohenheimFlash.success(conduit, ruleText("added_group"));
            return HandlerSupport.redirect(target);
        }
        return HandlerSupport.redirect(be.elevenways.zenit.server.http.ReturnTarget.bind(
            CmsRoutes.detail(panel, "access-rules", rule.get(AccessRuleModel.ID)),
            target.toUrl()));
    }

    private static Microcopy ruleText(String key) {
        return Microcopy.of(key).withFilter("scope", "access_rule");
    }
}
