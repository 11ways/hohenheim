package be.elevenways.hohenheim.server.schedule;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.server.task.record.RecordScheduleActionContext;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Scheduled power action: start (deploy), stop, or restart -- always through
 * {@link InstanceService}, whose stop path marks the exit observed (crash policy must
 * not fire on a scheduled stop) and whose writes are fence-guarded.
 */
public class InstancePowerAction extends InstanceScheduleAction {

    public static final Identifier ID = Identifier.of("hohenheim", "power");

    public static final String OP_START = "start";
    public static final String OP_STOP = "stop";
    public static final String OP_RESTART = "restart";

    static final Schema PAYLOAD = new Schema();
    static final EnumField OPERATION = PAYLOAD.addField(
            EnumField.builder("operation")
                    .value(OP_START, "Start")
                    .value(OP_STOP, "Stop")
                    .value(OP_RESTART, "Restart")
                    .defaultValue(OP_RESTART)
                    .label(HohenheimFormCopy.label("power_operation"))
                    .build());

    @Override public @NonNull Identifier typeId() { return ID; }
    @Override public @NonNull String getDisplayName() { return "Power action"; }

    @Override
    public @NonNull Microcopy getLabel() {
        return Microcopy.of("power").withFilter("scope", "schedule_action");
    }

    @Override public @Nullable Schema getSchema() { return PAYLOAD; }
    @Override public @Nullable Icon getIcon() { return Icon.of("power-off"); }

    /** Power is gated on manage today (no separate power capability exists yet). */
    @Override
    public @NonNull String requiredCapability() {
        return HohenheimAccess.MANAGE;
    }

    @Override
    public void execute(@NonNull RecordScheduleActionContext context) {
        int instanceId = context.recordIdAsInt();
        Object operation = context.payload().get("operation");
        String op = operation instanceof String value && !value.isBlank() ? value : OP_RESTART;

        InstanceService instances = new InstanceService();

        switch (op) {
            case OP_START -> instances.deploy(instanceId);
            case OP_STOP -> instances.stop(instanceId);
            case OP_RESTART -> {
                // stop() is idempotent when already stopped; deploy is create+start.
                instances.stop(instanceId);
                instances.deploy(instanceId);
            }
            default -> throw new IllegalStateException("unknown power operation: " + op);
        }
    }
}
