package be.elevenways.hohenheim.server.schedule;

import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceAppUpdates;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.server.task.record.RecordScheduleActionContext;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Run the template's in-place app update script (the nightly "keep the app current"
 * chain step). Funnels through {@link InstanceAppUpdates}, so a schedule and the row
 * action answer to one policy.
 */
public class InstanceAppUpdateAction extends InstanceScheduleAction {

    public static final Identifier ID = Identifier.of("hohenheim", "app_update");

    @Override public @NonNull Identifier typeId() { return ID; }
    @Override public @NonNull String getDisplayName() { return "App update"; }

    @Override
    public @NonNull Microcopy getLabel() {
        return Microcopy.of("app_update").withFilter("scope", "schedule_action");
    }

    @Override public @Nullable Schema getSchema() { return null; }
    @Override public @Nullable Icon getIcon() { return Icon.of("arrow-up-from-bracket"); }

    /** Mutating the installed app changes what runs: config. */
    @Override
    public @NonNull String requiredCapability() {
        return HohenheimAccess.CONFIG;
    }

    @Override
    public void execute(@NonNull RecordScheduleActionContext context) {
        new InstanceAppUpdates().update(context.recordIdAsInt());
    }
}
