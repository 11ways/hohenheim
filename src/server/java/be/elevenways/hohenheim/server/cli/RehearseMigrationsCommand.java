package be.elevenways.hohenheim.server.cli;

import be.elevenways.hohenheim.server.database.ControlPlaneBackups;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.migration.MigrationRunnerResult;
import be.elevenways.zenit.server.cli.OfflineCommand;
import be.elevenways.zenit.server.cli.OfflineCommandContext;
import be.elevenways.zenit.server.cli.OfflineCommandException;
import be.elevenways.zenit.server.orm.DatabaseEngine;
import be.elevenways.zenit.server.orm.DatasourceFactory;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Deploy rehearsal: run the pending migrations against a BYTE COPY of the database, so an
 * operator can prove a new jar's migration chain before touching the real file.
 *
 * AIDEV-NOTE: exists because the hand-rolled rehearsal was proven false on 2026-09-01. The
 * installer seeds an ABSOLUTE {@code database.path}, so "copy the tree to a scratch
 * directory and run {@code --run-migrations} from there" read the copied settings and then
 * migrated the LIVE file anyway -- the rehearsal reported "1 applied" while the real swap
 * reported "0 applied", which is exactly backwards. This lane takes the copy EXPLICITLY,
 * opens its own unregistered datasource on it, and REFUSES a target that resolves to the
 * configured live file (symlinks included), so pointing it at production is an error
 * message, never a silent live migration.
 *
 * @author Jelle De Loecker
 * @since 0.7.0
 */
public final class RehearseMigrationsCommand implements OfflineCommand {

    public static final String FLAG = "--rehearse-migrations";

    @Override
    public @NonNull String flag() {
        return FLAG;
    }

    @Override
    public @NonNull String describe() {
        return "<db-copy>  run the pending migrations against a byte copy of the database;"
            + " refuses the live file, never registers the copy as a datasource";
    }

    @Override
    public void run(@NonNull OfflineCommandContext context) {
        Path copy = pathOf(context.require(FLAG));
        if (!Files.isRegularFile(copy)) {
            throw new OfflineCommandException("Not a database file: " + copy
                + " -- make a byte copy first (sqlite3 <live> \".backup <copy>\").");
        }
        refuseTheLiveFile(copy);

        String url = "jdbc:sqlite:" + copy;
        SqlDatasource rehearsal = DatasourceFactory.create(
            DatabaseEngine.resolve("sqlite", url), url, null, null);
        try {
            MigrationRunnerResult result = new MigrationRunner(rehearsal).migrate();
            if (!result.isSuccess()) {
                result.getResults().forEach(r -> context.print("migration " + r.getVersion()
                    + (r.isSuccess() ? " OK" : " FAILED: " + r.getError())));
                throw new OfflineCommandException(
                    "Rehearsal FAILED against " + copy + "; the live database was not touched.");
            }
            context.print("Rehearsal complete: " + result.getAppliedCount()
                + " applied against " + copy + "; the live database was not touched.");
        } finally {
            rehearsal.close();
        }
    }

    /**
     * @throws OfflineCommandException when the copy IS the configured live file
     */
    private static void refuseTheLiveFile(@NonNull Path copy) {
        Path live = ControlPlaneBackups.databaseFile();
        try {
            if (Files.exists(live) && Files.isSameFile(copy, live)) {
                throw new OfflineCommandException("Refusing to rehearse against the LIVE"
                    + " database (" + live.toRealPath() + "); pass a byte copy instead.");
            }
        } catch (IOException unreadable) {
            throw new OfflineCommandException("Could not compare " + copy
                + " against the configured database file: " + unreadable.getMessage());
        }
    }

    private static @NonNull Path pathOf(@NonNull String pointer) {
        try {
            return Path.of(pointer);
        } catch (InvalidPathException notAPath) {
            throw new OfflineCommandException("Not a filesystem path: " + pointer);
        }
    }
}
