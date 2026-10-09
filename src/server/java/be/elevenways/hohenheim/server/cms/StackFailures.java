package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.StackDeploymentModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.protoblast.common.key.IdentifierKey;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.routing.RouteScope;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Why a stack reads "Failed": the newest deployment row's error, memoized per request.
 *
 * AIDEV-NOTE: the stacks LIST asks once per rendered row, so the newest deployment per
 * stack is kept ONCE per request in the panel's one request memo ({@link CmsSupport#memo})
 * rather than queried per row. A conduit-less caller reads the table.
 */
final class StackFailures {

    /** Request-scoped snapshot: stack id to its newest deployment row. */
    private static final IdentifierKey<Map<Integer, Row>> LATEST =
        IdentifierKey.of("hohenheim", "stack_latest_deployments");

    private StackFailures() {}

    /**
     * The reason a FAILED stack failed, or null when the stack is not failed or its newest
     * deployment did not fail (a monitor-observed failure has no deployment reason).
     */
    static @Nullable String reasonOf(@NonNull Row stack) {
        Row deployment = failedDeploymentOf(stack);
        String error = deployment == null ? null : deployment.get(StackDeploymentModel.ERROR);
        return error == null || error.isBlank() ? null : error;
    }

    /**
     * The newest deployment of a FAILED stack when that deployment failed, else null: a failed stack without one is
     * the status monitor's observation (its services stopped after a good deploy).
     */
    static @Nullable Row failedDeploymentOf(@NonNull Row stack) {
        if (!StackModel.STATUS_FAILED.equals(stack.get(StackModel.STATUS))) {
            return null;
        }
        Row deployment = latestDeploymentOf(stack.get(StackModel.ID));
        return deployment != null
            && StackDeploymentModel.STATUS_FAILED.equals(deployment.get(StackDeploymentModel.STATUS))
            ? deployment : null;
    }

    /**
     * The newest deployment of one stack, or null when it was never deployed. One
     * limit-1 query per DISTINCT stack per request (a whole-table scan would decrypt
     * every retained spec snapshot for stacks the page never shows).
     */
    static @Nullable Row latestDeploymentOf(@Nullable Integer stackId) {
        if (stackId == null) {
            return null;
        }
        Map<Integer, Row> memo = memo();
        if (memo != null && memo.containsKey(stackId)) {
            return memo.get(stackId);
        }
        List<Row> rows = Models.get(StackDeploymentModel.class).findByStackId(stackId, 1);
        Row latest = rows.isEmpty() ? null : rows.get(0);
        if (memo != null) {
            memo.put(stackId, latest);
        }
        return latest;
    }

    /** The request's memo, created on first use; null without a request (an attribute-less one keeps none). */
    private static @Nullable Map<Integer, Row> memo() {
        Conduit conduit = RouteScope.currentConduit();
        return conduit == null ? null : CmsSupport.memo(conduit, LATEST, HashMap::new);
    }
}
