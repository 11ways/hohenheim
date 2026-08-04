package be.elevenways.hohenheim.server.preview;

import be.elevenways.hohenheim.model.PreviewDeploymentModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.server.task.record.RecordScheduleActionContext;
import be.elevenways.zenit.server.task.record.RecordScheduleActionHandler;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The one-shot expiry action behind a preview's bounded lifetime: fired by the
 * record-schedule sweeper when the armed deadline is reached, it runs the full
 * verified reclaim. Armed with system authority (run_as null) by the deploy lane;
 * a destroy the daemon cannot confirm throws, so the step records the failure and
 * retries per its policy instead of lying.
 */
public class PreviewExpireAction implements RecordScheduleActionHandler {

    public static final Identifier ID = Identifier.of("hohenheim", "expire_preview");

    @Override public @NonNull Identifier typeId() { return ID; }
    @Override public @NonNull Identifier appliesTo() { return PreviewDeploymentModel.MODEL_ID; }
    @Override public @NonNull String getDisplayName() { return "Expire preview"; }
    @Override public @Nullable Schema getSchema() { return null; }
    @Override public @Nullable Icon getIcon() { return Icon.of("hourglass-end"); }

    @Override
    public @NonNull Microcopy getLabel() {
        return Microcopy.of("expire_preview").withFilter("scope", "schedule_action");
    }

    /** Expiry destroys the preview; manage is the capability that may schedule that. */
    @Override
    public @NonNull String requiredCapability() {
        return HohenheimAccess.MANAGE;
    }

    @Override
    public void execute(@NonNull RecordScheduleActionContext context) {
        PreviewDeployments.destroy(context.recordIdAsInt(), "expired");
    }
}
