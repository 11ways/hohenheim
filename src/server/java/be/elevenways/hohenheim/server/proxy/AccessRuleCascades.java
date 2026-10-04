package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.server.orm.PendingDeletes;
import be.elevenways.zenit.common.orm.model.Models;

/**
 * A rule cannot outlive its list: deleting an access list takes its rules. (Deleting a group rule takes the subtree
 * under it through the model's own TreeBehaviour, {@code AccessRuleModel.TREE}, never a hook here.)
 *
 * The rules a departed list leaves behind are not inert debris -- they stay listed in
 * {@code /admin/access-rules} naming a list id nothing resolves, and the next list to be
 * created can be handed that id by the datasource, at which point a policy nobody wrote
 * starts gating live traffic.
 *
 * AIDEV-NOTE: the cascade is expressed as a CORRELATED criteria over the pending delete's
 * own criteria ({@code Criteria.related}), never as a materialized id list: a remove hook
 * sees a criteria-only context, and re-reading the doomed rows to collect their ids is the
 * fifth private copy of that idiom in this repo. It also means a list's rules are removed by the
 * datasource in one statement, which the rule tree's own remove hook then sees whole (every
 * doomed parent's children are doomed too, so nothing is left to cascade).
 *
 * AIDEV-NOTE: a dependent cascade terminates on the COUNT, not on the criteria: a nested level's
 * criteria is structurally non-empty forever (it nests one more EXISTS), so a hook that simply
 * issued the next delete would recurse until the stack ran out. That count lives in
 * {@link PendingDeletes#deleteDependents}, which every cascade in this repo shares; the list
 * cascade here is a single level.
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
    }
}
