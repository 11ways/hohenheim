package be.elevenways.hohenheim.server.schedule;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceSnapshots;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.server.task.record.RecordScheduleActionContext;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Scheduled snapshot of one instance (cold volume copy or live native snapshot, per
 * the driver's lane; same-host capture -- a snapshot is NOT a backup and dies with
 * the host/pool).
 */
public class InstanceSnapshotAction extends InstanceScheduleAction {

    public static final Identifier ID = Identifier.of("hohenheim", "snapshot");

    static final Schema PAYLOAD = new Schema();
    static final StringField NOTE = PAYLOAD.addField(
            StringField.builder().name("note").label(HohenheimFormCopy.label("snapshot_note")).build());

    @Override public @NonNull Identifier typeId() { return ID; }
    @Override public @NonNull String getDisplayName() { return "Snapshot"; }

    @Override
    public @NonNull Microcopy getLabel() {
        return Microcopy.of("snapshot").withFilter("scope", "schedule_action");
    }

    @Override public @Nullable Schema getSchema() { return PAYLOAD; }
    @Override public @Nullable Icon getIcon() { return Icon.of("camera"); }

    @Override
    public @NonNull String requiredCapability() {
        return HohenheimAccess.SNAPSHOTS;
    }

    @Override
    public void execute(@NonNull RecordScheduleActionContext context) {
        Object note = context.payload().get("note");
        new InstanceSnapshots().create(context.recordIdAsInt(),
                note instanceof String text && !text.isBlank() ? text : "scheduled");
    }
}
