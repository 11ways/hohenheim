package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.server.orm.PendingDeletes;
import be.elevenways.zenit.common.orm.model.Models;

/**
 * A rule cannot outlive what encloses it: deleting an access list takes its rules, and
 * deleting a group rule takes the subtree under it.
 *
 * The rules a departed list leaves behind are not inert debris -- they stay listed in
 * {@code /admin/access-rules} naming a list id nothing resolves, and the next list to be
 * created can be handed that id by the datasource, at which point a policy nobody wrote
 * starts gating live traffic.
 *
 * AIDEV-NOTE: the cascade is expressed as a CORRELATED criteria over the pending delete's
 * own criteria ({@code Criteria.related}), never as a materialized id list: a remove hook
 * sees a criteria-only context, and re-reading the doomed rows to collect their ids is the
 * fifth private copy of that idiom in this repo. It also means the whole subtree is removed
 * by the datasource in one statement per level.
 *
 * AIDEV-NOTE: the recursion terminates on the COUNT, not on the criteria. Each level's
 * criteria is structurally non-empty forever (it nests one more EXISTS), so a hook that
 * simply issued the next delete would recurse until the stack ran out; asking first whether
 * any row matches is what ends it, one query per level of nesting. That count lives in
 * {@link PendingDeletes#deleteDependents}, which every cascade in this repo now shares.
 */
public final class AccessRuleCascades {

    private static volatile boolean installed;

    private AccessRuleCascades() {
    }

    /** Install the cascade hooks; idempotent, called at the MODULES boot stage. */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;

        // Every rule of a doomed list, at any depth: they all carry access_list_id.
        AccessListModel.SCHEMA.addBeforeRemoveHook(context -> PendingDeletes.deleteDependents(
            Models.get(AccessRuleModel.class), AccessRuleModel.ACCESS_LIST, context));

        // The subtree under a doomed group rule, one level per pass.
        AccessRuleModel.SCHEMA.addBeforeRemoveHook(context -> PendingDeletes.deleteDependents(
            Models.get(AccessRuleModel.class), AccessRuleModel.PARENT, context));
    }
}
