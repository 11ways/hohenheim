package be.elevenways.hohenheim.server.task;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.task.ScheduledTask;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * The one home of Hohenheim's task label convention: a task's label is keyed by its id's path.
 *
 * AIDEV-NOTE: each task still overrides {@code label()} to call {@link #label}: zenit's {@code ScheduledTask} has no
 * module-scoped default, and {@code TaskLabelCoverage} (DeclaredMicrocopyKeysTest) only sees classes that extend
 * {@code ScheduledTask} directly, so a Hohenheim base class would hide every task from it.
 *
 * @author Jelle De Loecker
 * @since 0.10.0
 */
public final class HohenheimTasks {

    private HohenheimTasks() {
    }

    /** @return the task's worded name, its id's path under the hohenheim scope's task_label target */
    public static @NonNull Microcopy label(@NonNull ScheduledTask task) {
        return HohenheimMicrocopy.HOHENHEIM.of(task.id().getPath()).withFilter("target", "task_label");
    }
}
