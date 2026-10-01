package be.elevenways.hohenheim.server.preview;

import be.elevenways.hohenheim.model.PreviewDeploymentModel;
import be.elevenways.hohenheim.preview.PreviewOperations;
import be.elevenways.zenit.common.operation.ZenitPlacementSurface;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.server.operation.OperationCall;
import be.elevenways.zenit.server.operation.OperationHandlers;
import be.elevenways.zenit.server.task.record.SchedulePlacements;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * The handler of the preview expiry and its schedule placement, attached once per JVM.
 *
 * AIDEV-NOTE: attached in a static initializer like InstanceOperationHandlers; {@link #init()} only forces the class
 * to load before boot verifies every operation has its handler.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class PreviewOperationHandlers {

    /** The reason the reclaim records for an expiry. */
    static final String EXPIRED = "expired";

    /** The reason the reclaim records for a person's click, as the admin row action recorded it. */
    static final String OPERATOR = "operator";

    static {
        OperationHandlers.attach(PreviewOperations.EXPIRE).handle(PreviewOperationHandlers::expire);
        SchedulePlacements.place(PreviewOperations.EXPIRE);
    }

    private PreviewOperationHandlers() {
    }

    /** Loads the class, attaching the handler and placement; idempotent. */
    public static void init() {
        // The static initializer did the work.
    }

    /** The deadline's step reclaims as expired; every other surface is a person's click. */
    private static @NonNull String expire(@NonNull OperationCall<Row, Void> call) {
        Integer id = call.subject().get(PreviewDeploymentModel.ID);
        String reason = call.surface() == ZenitPlacementSurface.SCHEDULE_STEP ? EXPIRED : OPERATOR;
        PreviewDeployments.destroy(id, reason);
        return reason;
    }
}
