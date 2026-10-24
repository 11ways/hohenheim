package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.protoblast.testsupport.time.Poll;
import be.elevenways.zenit.cms.common.action.CmsPlacementSurface;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.common.Zenit;
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
 * what the click records is then on the record, as it is once the run page reads finished. The wait is bounded by
 * {@link #runBound()}, derived from the longest wait a placed run makes here, never a fixed number.
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

    /** What a clicked run may take beyond the waits {@link #runBound()} names: the work around them on fake daemons. */
    private static final Duration SLACK = Duration.ofSeconds(10);

    /**
     * How long a clicked run may take: the release health gate (its probe timeout, then its drain), the one wait a
     * placed run makes in this suite's lane, read from the settings in force so a test that shortens the gate shortens
     * this too, plus {@link #SLACK}.
     *
     * AIDEV-NOTE: no build is in the bound: a build needs a git source and a real daemon, the slow lane, and no clicking
     * test has one (the deploys clicked here run a fake native kind or an image-only application).
     */
    private static @NonNull Duration runBound() {
        Integer probe = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Releases.PROBE_TIMEOUT_SECONDS);
        Integer drain = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Releases.DRAIN_SECONDS);
        return Duration.ofSeconds((probe != null ? probe : 0) + (drain != null ? drain : 0)).plus(SLACK);
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
            Poll.value(action.id() + ": run " + run + " ends", runBound(), Duration.ofMillis(20), () -> {
                OperationRun state = OperationRuns.of(run, TestAccessContexts.allAllowed());
                return state != null && state.status() != OperationRunStatus.RUNNING ? state : null;
            });
        }
        return result;
    }
}
