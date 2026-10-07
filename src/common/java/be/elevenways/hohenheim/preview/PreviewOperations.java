package be.elevenways.hohenheim.preview;

import be.elevenways.hohenheim.activity.OperationSentences;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.model.PreviewDeploymentModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationCommand;
import be.elevenways.zenit.common.orm.command.CommandExecution;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.ui.Icon;

/**
 * The preview deployment operations: today only the expiry its bounded lifetime arms.
 *
 * AIDEV-NOTE: the id is the legacy schedule action's own, {@code hohenheim:expire_preview}: it names no activity
 * member, so the one-shot schedules armed before this class existed run it unchanged. The deploy lane arms it with
 * system authority (no run_as), which the pipeline runs without admission; the gate binds everyone else. It is also
 * the admin's and /manage's "destroy now" (PreviewDeploymentResource), where manage on a preview is manage on its
 * application through the preview's capability rules (HohenheimGrantPolicy); a click reclaims as destroyed, the
 * deadline's step as expired.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class PreviewOperations {

    /** The subject of every preview operation: one preview deployment record. */
    public static final SubjectType<Row> PREVIEW = SubjectType.record(PreviewDeploymentModel.MODEL_ID);

    /** The full verified reclaim at the deadline; a destroy the daemon cannot confirm throws, so the step retries. */
    public static final Operation<Row, Void, String> EXPIRE = Operation.declare(HohenheimIds.id("expire_preview"))
        .happened(OperationSentences.of("expire_preview"))
        .label(Microcopy.of("expire_preview").withFilter("scope", "schedule_action").withFallback("Expire preview"))
        .icon(Icon.of("hourglass-end"))
        .one(PREVIEW)
        .gate(OperationGate.open().subjectCapability(HohenheimCapabilities.MANAGE))
        .result(String.class)
        .facts(OperationFact.REACHES_OUTSIDE, OperationFact.DESTRUCTIVE)
        .command(OperationCommand.perSubject()
            .execution(CommandExecution.OUTSIDE_TRANSACTION))
        .register();

    private PreviewOperations() {
    }
}
