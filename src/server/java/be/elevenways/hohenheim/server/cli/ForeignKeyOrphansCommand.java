package be.elevenways.hohenheim.server.cli;

import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.server.cli.OfflineCommand;
import be.elevenways.zenit.server.cli.OfflineCommandContext;
import be.elevenways.zenit.server.cli.OfflineCommandException;
import be.elevenways.zenit.server.cli.ServerFlags;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Break-glass: list the control-plane rows that violate a declared foreign key and, for ONE
 * named table at a time, delete them.
 *
 * AIDEV-NOTE: the documented repair for the orphans a database accumulated while foreign
 * keys were not enforced (before zenit 8a86d3c2); CheckForeignKeys reports them, and nothing
 * deletes one without an operator naming its table here. Which orphans are safe to delete
 * is a judgement about the table, so the command never decides it: a row that only DESCRIBES
 * its gone parent (a port claim of a removed host, a peer link of a removed zone) can go; a
 * row that POINTS AT REAL DATA (an instance volume, a backup record, an instance whose
 * template is gone) should be re-parented through the admin instead, and --dry-run shows
 * exactly which rowids a delete would take. Deleting a row that other rows reference is
 * refused by the constraint itself, so a parent-level orphan is repaired children first.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class ForeignKeyOrphansCommand implements OfflineCommand {

    public static final String FLAG = "--foreign-key-orphans";

    /** A table name the PRAGMA reported; quoted, never a value, but still refused if odd. */
    private static final Pattern TABLE_NAME = Pattern.compile("[A-Za-z0-9_]+");

    @Override
    public @NonNull String flag() {
        return FLAG;
    }

    @Override
    public @NonNull String describe() {
        return "[table]  list rows that violate a foreign key; with a table name, delete that"
            + " table's orphaned rows (--dry-run lists what would go)";
    }

    @Override
    public boolean honours(ServerFlags.@NonNull ServerFlag modifier) {
        return modifier.equals(ServerFlags.DRY_RUN);
    }

    @Override
    public void run(@NonNull OfflineCommandContext context) {
        SqlDatasource datasource = HohenheimDatabase.datasource();
        List<HohenheimDatabase.ForeignKeyViolation> violations =
            HohenheimDatabase.foreignKeyViolations(datasource);
        String table = context.option(FLAG);
        if (table == null || table.isBlank()) {
            if (violations.isEmpty()) {
                context.print("No row violates a declared foreign key.");
                return;
            }
            for (String line : HohenheimDatabase.summarize(violations)) {
                context.print(line);
            }
            for (HohenheimDatabase.ForeignKeyViolation violation : violations) {
                context.print("  " + violation.table() + " rowid " + violation.rowId()
                    + " -> missing " + violation.parent());
            }
            context.print("Delete one table's orphans with " + FLAG + " <table> (add "
                + ServerFlags.DRY_RUN.flag() + " to preview).");
            return;
        }

        List<Long> rowIds = new ArrayList<>();
        for (HohenheimDatabase.ForeignKeyViolation violation : violations) {
            if (violation.table().equals(table) && violation.rowId() != null) {
                rowIds.add(violation.rowId());
            }
        }
        if (rowIds.isEmpty() || !TABLE_NAME.matcher(table).matches()) {
            throw new OfflineCommandException("Table '" + table + "' holds no row that violates a"
                + " foreign key; run " + FLAG + " without a table to list them.");
        }
        boolean dryRun = context.has(ServerFlags.DRY_RUN.flag());
        int deleted = 0;
        for (long rowId : rowIds) {
            if (dryRun) {
                context.print("would delete " + table + " rowid " + rowId);
                continue;
            }
            deleted += datasource.rawUpdate("DELETE FROM \"" + table + "\" WHERE rowid = ?", rowId);
            context.print("deleted " + table + " rowid " + rowId);
        }
        context.print(dryRun
            ? rowIds.size() + " orphaned row(s) of " + table + " would be deleted; nothing was changed."
            : deleted + " orphaned row(s) of " + table + " deleted.");
    }
}
