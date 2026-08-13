package be.elevenways.hohenheim.server.cli;

import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.database.ControlPlaneBackups;
import be.elevenways.zenit.server.cli.OfflineCommand;
import be.elevenways.zenit.server.cli.OfflineCommandContext;
import be.elevenways.zenit.server.cli.OfflineCommandException;
import be.elevenways.zenit.server.orm.backup.RecoveryArchive;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Break-glass: restore a control-plane recovery archive over this host's database and
 * field-encryption keyring, named either by local path or by key on the configured target.
 *
 * AIDEV-NOTE: restore is OFFLINE BY DESIGN -- it REPLACES the SQLite file, so it must never
 * run while a server has it open, and the archive is fully checksum-verified before either
 * half is touched. That is why the local-path branch closes the datasource FIRST: the
 * offline lane opens one for every command, and letting a pool's close() checkpoint a WAL
 * onto a freshly restored file is exactly the corruption this ordering exists to prevent.
 * The target-key branch cannot do the same, and says so: resolving a target reads the
 * backup_targets row and its host record, so that lane NEEDS a readable database -- the
 * limitation ControlPlaneBackups.restoreFromTarget already states. Total loss is covered by
 * copying the artifact down by hand and restoring it by PATH.
 *
 * @author Jelle De Loecker
 * @since 0.7.0
 */
public final class RestoreControlPlaneCommand implements OfflineCommand {

    public static final String FLAG = "--restore-control-plane";

    @Override
    public @NonNull String flag() {
        return FLAG;
    }

    @Override
    public @NonNull String describe() {
        return "<path|archive key>  restore a control-plane archive over this host's database"
            + " and keyring (offline only, verified before anything is replaced)";
    }

    @Override
    public void run(@NonNull OfflineCommandContext context) {
        String pointer = context.require(FLAG);

        if (isLocalFile(pointer)) {
            HohenheimDatabase.closeDatasource();
            RecoveryArchive.Manifest manifest = ControlPlaneBackups.restore(Path.of(pointer));
            report(context, manifest);
            return;
        }

        RecoveryArchive.Manifest manifest;
        try {
            manifest = ControlPlaneBackups.restoreFromTarget(pointer);
        } catch (IOException error) {
            throw new OfflineCommandException(
                "Control-plane restore from target failed: " + error.getMessage());
        }
        report(context, manifest);
    }

    /** A pointer that is not a readable local file is treated as a key on the target. */
    private static boolean isLocalFile(@NonNull String pointer) {
        try {
            return Files.isRegularFile(Path.of(pointer));
        } catch (InvalidPathException notAPath) {
            return false;
        }
    }

    private static void report(@NonNull OfflineCommandContext context,
                               RecoveryArchive.@NonNull Manifest manifest) {
        context.print("Restored control-plane archive created " + manifest.created()
            + " (encryption keys " + String.join(",", manifest.keyIds()) + ").");
        context.print("Start the server to boot on the restored database.");
    }
}
