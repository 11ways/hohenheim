package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.zenit.common.orm.datasource.context.RemoveFromDatasource;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.relation.Relation;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.QueryContext;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

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
 * any row matches is what ends it, one query per level of nesting.
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
        AccessListModel.SCHEMA.addBeforeRemoveHook(context ->
            deleteRulesRelatedTo(AccessRuleModel.ACCESS_LIST, doomCriteria(context)));

        // The subtree under a doomed group rule, one level per pass.
        AccessRuleModel.SCHEMA.addBeforeRemoveHook(context ->
            deleteRulesRelatedTo(AccessRuleModel.PARENT, doomCriteria(context)));
    }

    /**
     * Delete every rule whose {@code relation} points at a row the pending delete removes.
     *
     * @param doomed the pending delete's criteria, or null when it removes every row
     */
    private static void deleteRulesRelatedTo(@NonNull Relation<?, ?> relation,
                                             @Nullable Criteria doomed) {
        Model rules = Models.get(AccessRuleModel.class);
        Criteria scope = doomed == null
            ? Criteria.related(relation)
            : Criteria.related(relation, doomed);
        if (rules.find().where(scope).count() == 0) {
            return;
        }
        rules.find().where(scope).delete();
    }

    /** @return the criteria the pending delete runs on, or null when it removes every row */
    private static @Nullable Criteria doomCriteria(@NonNull RemoveFromDatasource context) {
        QueryContext queryContext = context.getQueryContext();
        return queryContext != null ? queryContext.getCriteria() : null;
    }
}
