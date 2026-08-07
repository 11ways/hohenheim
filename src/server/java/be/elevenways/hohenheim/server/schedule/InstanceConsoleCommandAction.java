package be.elevenways.hohenheim.server.schedule;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceConsoles;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.server.task.record.RecordScheduleActionContext;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Send one console line to the instance's primary process (the "warn the players"
 * half of the Pterodactyl-parity chain). Funnels through {@link InstanceConsoles},
 * so a command equal to the template's stop_command still counts as an observed stop.
 */
public class InstanceConsoleCommandAction extends InstanceScheduleAction {

    public static final Identifier ID = Identifier.of("hohenheim", "console_command");

    static final Schema PAYLOAD = new Schema();
    static final StringField COMMAND = PAYLOAD.addField(
            StringField.builder().name("command").label(HohenheimFormCopy.label("console_line")).build());

    @Override public @NonNull Identifier typeId() { return ID; }
    @Override public @NonNull String getDisplayName() { return "Console command"; }

    @Override
    public @NonNull Microcopy getLabel() {
        return Microcopy.of("console_command").withFilter("scope", "schedule_action");
    }

    @Override public @Nullable Schema getSchema() { return PAYLOAD; }
    @Override public @Nullable Icon getIcon() { return Icon.of("terminal"); }

    @Override
    public @NonNull String requiredCapability() {
        return HohenheimAccess.CONSOLE;
    }

    @Override
    public void execute(@NonNull RecordScheduleActionContext context) {
        Object command = context.payload().get("command");

        if (!(command instanceof String line) || line.isBlank()) {
            throw new IllegalStateException("no console command configured on this step");
        }

        InstanceConsoles.sendCommand(context.recordIdAsInt(), line);
    }
}
