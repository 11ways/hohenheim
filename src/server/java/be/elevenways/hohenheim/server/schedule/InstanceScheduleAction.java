package be.elevenways.hohenheim.server.schedule;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.server.task.record.RecordScheduleActionHandler;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Base of every instance-targeting record-schedule action: applies to instance rows
 * and, like every consumer wiring, mutates instances ONLY through the fenced service
 * funnels (InstanceService / InstanceConsoles / InstanceBackups / InstanceSnapshots)
 * -- never a direct row write.
 */
public abstract class InstanceScheduleAction implements RecordScheduleActionHandler {

    @Override
    public @NonNull Identifier appliesTo() {
        return InstanceModel.MODEL_ID;
    }
}
