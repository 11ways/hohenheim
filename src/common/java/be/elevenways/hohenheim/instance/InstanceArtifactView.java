package be.elevenways.hohenheim.instance;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.zenit.cms.common.render.action.InvokeActionState;
import be.elevenways.zenit.cms.common.render.table.EnumBadgeState;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * One snapshot or backup row, as the per-instance tabs render it.
 *
 * {@code invokes} carries THAT resource's own row actions for THIS row and viewer,
 * already permission- and visibleFor-filtered, so restore keeps the typed confirmation
 * and the operator-only refusal it declares on its own resource. {@code recordUrl} points
 * at the generated record page, which stays the one place a row is deleted -- this tab is
 * a scoped VIEW of that resource, never a second UI over the same records.
 *
 * @param error the stored failure text, blank when there is none
 */
@HawkeyeClass
public record InstanceArtifactView(
    int id,
    @NonNull EnumBadgeState status,
    @NonNull String note,
    long sizeBytes,
    @Nullable String createdAtIso,
    @NonNull String error,
    @NonNull String recordUrl,
    @NonNull List<InvokeActionState> invokes
) {
}
