package be.elevenways.hohenheim.server.cli;

import be.elevenways.hohenheim.server.database.ControlPlaneBackups;
import be.elevenways.zenit.server.cli.OfflineCommand;
import be.elevenways.zenit.server.cli.OfflineCommandContext;
import be.elevenways.zenit.server.cli.OfflineCommandException;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;

/**
 * Break-glass: write a control-plane recovery archive (this host's own database plus the
 * field-encryption keyring, as one verified unit) to the configured off-host target.
 *
 * AIDEV-NOTE: was a hand-rolled {@code --backup-control-plane} branch in ServerMain until
 * 2026-08-13. The framework's own {@code OfflineCommands} note named this pair as the
 * migration it deferred; the branch had its own arg scan, its own settings load, its own
 * datasource open/close and its own idea of what an error looked like. All four now come
 * from {@link OfflineBoot} and the {@link OfflineCommand} contract, which is also what
 * puts it in {@code --offline-help}.
 *
 * @author Jelle De Loecker
 * @since 0.7.0
 */
public final class BackupControlPlaneCommand implements OfflineCommand {

    public static final String FLAG = "--backup-control-plane";

    @Override
    public @NonNull String flag() {
        return FLAG;
    }

    @Override
    public @NonNull String describe() {
        return "back up this host's control-plane database + encryption keyring to the"
            + " configured backup target";
    }

    @Override
    public void run(@NonNull OfflineCommandContext context) {
        ControlPlaneBackups.Archive archive;
        try {
            archive = ControlPlaneBackups.backupNow();
        } catch (IOException error) {
            throw new OfflineCommandException("Control-plane backup failed: " + error.getMessage());
        }
        context.print("Control-plane archive stored as " + archive.key()
            + " (sha256 " + archive.sha256() + ").");
    }
}
