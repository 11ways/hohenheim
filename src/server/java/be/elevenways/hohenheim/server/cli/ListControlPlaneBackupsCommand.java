package be.elevenways.hohenheim.server.cli;

import be.elevenways.hohenheim.server.database.ControlPlaneBackups;
import be.elevenways.zenit.server.cli.OfflineCommand;
import be.elevenways.zenit.server.cli.OfflineCommandContext;
import be.elevenways.zenit.server.cli.OfflineCommandException;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.util.List;

/**
 * Break-glass: list the control-plane recovery archives the configured target holds, so an
 * operator can name one for {@code --restore-control-plane}.
 *
 * @author Jelle De Loecker
 * @since 0.7.0
 */
public final class ListControlPlaneBackupsCommand implements OfflineCommand {

    public static final String FLAG = "--list-control-plane-backups";

    @Override
    public @NonNull String flag() {
        return FLAG;
    }

    @Override
    public @NonNull String describe() {
        return "list the control-plane archives on the configured backup target, newest first";
    }

    @Override
    public void run(@NonNull OfflineCommandContext context) {
        SoleOfflineFlag.require(context, FLAG);

        List<String> keys;
        try {
            keys = ControlPlaneBackups.listBackups();
        } catch (IOException error) {
            throw new OfflineCommandException(
                "Cannot list control-plane backups: " + error.getMessage());
        }
        if (keys.isEmpty()) {
            // Loud, because an empty listing and an unreachable target must never read the
            // same: one means "no archive exists", the other means "we could not look".
            context.print("The configured backup target holds NO control-plane archive.");
            return;
        }
        for (String key : keys) {
            context.print("control-plane archive: " + key);
        }
    }
}
