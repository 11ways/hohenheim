package be.elevenways.hohenheim.server.orm;

import be.elevenways.zenit.common.orm.datasource.context.RemoveFromDatasource;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.relation.Relation;
import be.elevenways.zenit.common.orm.query.QueryContext;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The delete a remove hook is asked about, as CRITERIA another model correlates against.
 *
 * AIDEV-NOTE: a remove context carries criteria, never rows. A cascade or a refusal spelled
 * as {@code Criteria.related} over these criteria never reads the doomed rows at all,
 * removes a whole level in one statement and terminates on a count. When the rows
 * themselves are needed (a per-row refusal message, a ledger entry), read them through
 * {@code RemoveFromDatasource.doomedRows()} -- once per delete, shared by every hook -- and
 * when something must happen AFTER the delete, register the pair through
 * {@code DoomedRows.handOver}. Never a private re-read of the criteria.
 */
public final class PendingDeletes {

    private PendingDeletes() {
    }

    /** @return the criteria the pending delete runs on, or null when it removes every row */
    public static @Nullable Criteria criteria(@NonNull RemoveFromDatasource context) {
        QueryContext queryContext = context.getQueryContext();
        return queryContext != null ? queryContext.getCriteria() : null;
    }

    /**
     * Rows whose {@code relation} points at a row the pending delete removes.
     *
     * @param relation a relation declared on the DEPENDENT model, targeting the doomed one
     */
    public static @NonNull Criteria dependents(@NonNull Relation<?, ?> relation,
                                               @NonNull RemoveFromDatasource context) {
        Criteria doomed = criteria(context);
        return doomed == null ? Criteria.related(relation) : Criteria.related(relation, doomed);
    }

    /**
     * Delete every row of {@code model} whose {@code relation} points at a doomed row.
     *
     * AIDEV-NOTE: the count comes first because a self-referencing cascade (a rule tree)
     * recurses through this very hook: each level's criteria is structurally non-empty
     * forever, so asking whether any row matches is what ends it, one query per level.
     */
    public static void deleteDependents(@NonNull Model model, @NonNull Relation<?, ?> relation,
                                        @NonNull RemoveFromDatasource context) {
        Criteria scope = dependents(relation, context);
        if (model.find().where(scope).count() == 0) {
            return;
        }
        model.find().where(scope).delete();
    }
}
