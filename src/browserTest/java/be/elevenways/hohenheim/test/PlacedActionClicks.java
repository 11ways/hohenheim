package be.elevenways.hohenheim.test;

import be.elevenways.protoblast.testsupport.time.Poll;
import be.elevenways.zenit.cms.common.action.CmsPlacementSurface;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationResult;
import be.elevenways.zenit.common.operation.OperationRunStatus;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.server.operation.OperationPipeline;
import be.elevenways.zenit.server.operation.OperationRequest;
import be.elevenways.zenit.server.operation.OperationRun;
import be.elevenways.zenit.server.operation.OperationRuns;
import be.elevenways.zenit.test.support.TestAccessContexts;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.time.Duration;
import java.util.List;

/**
 * A panel's placed row operation run as the invoke route runs it (surface {@code zenit:admin_action}, an admin caller),
 * without HTTP or markup, for tests that assert what a click records rather than how it is drawn.
 *
 * AIDEV-NOTE: a background placement answers before its handler ran, so a click waits for the run it started to end:
 * what the click records is then on the record, as it is once the run page reads finished.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class PlacedActionClicks {

    private PlacedActionClicks() {
    }

    /** @return the resource's placed action whose id path is {@code path} */
    public static @NonNull PanelAction<Row> placed(@NonNull PanelResource<Row> resource, @NonNull String path) {
        return placed(resource.slug(), resource.actions(), path);
    }

    private static PanelAction<Row> placed(String slug, List<PanelAction<Row>> actions, String path) {
        for (PanelAction<Row> action : actions) {
            if (path.equals(action.id().getPath())) {
                return action;
            }
        }
        throw new AssertionError(slug + " places no " + path);
    }

    /** Runs the placed operation over one row, as an admin whose every check passes, until its run (if any) ends. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static @NonNull OperationResult<?> click(@NonNull PanelAction<Row> action, @NonNull Row row) {
        Operation operation = action.operation();
        if (operation == null) {
            throw new AssertionError(action.id() + " places no operation");
        }
        OperationRequest request = OperationRequest.of(operation, CmsPlacementSurface.ADMIN_ACTION)
            .caller(TestAccessContexts.allAllowed())
            .subjects(List.of(row));
        OperationResult<?> result = OperationPipeline.invoke(action.presets() ? request.preset(action.presetFor(row))
            : request);
        Long run = result.run();
        if (run != null) {
            Poll.value(action.id() + ": run " + run + " ends", Duration.ofSeconds(20), Duration.ofMillis(20), () -> {
                OperationRun state = OperationRuns.of(run, TestAccessContexts.allAllowed());
                return state != null && state.status() != OperationRunStatus.RUNNING ? state : null;
            });
        }
        return result;
    }
}
