package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Test cleanup that hard-deletes an instance row after removing every row a declared foreign
 * key makes reference it, deepest first.
 *
 * AIDEV-NOTE: SQLite enforces foreign keys since zenit 8a86d3c2, so a bare
 * {@code instances.delete(id)} in a cleanup fails as soon as the instance collected a child
 * row (a managed database's engine secrets in instance_variables, a volume, a device).
 * Production never hard-deletes an instance -- destroy soft-deletes through deleted_at -- so
 * there is no model cascade to lean on and none is wanted: a cascade would silently drop
 * backup and snapshot history. The referencing tables are read from SQLite's own
 * foreign_key_list rather than listed here, so a migration adding a child table is covered
 * without an edit. The instance row itself still goes through its model -- via
 * {@link HardDeletes} (the behaviour's forceDelete), because a plain model delete of an
 * instance is a SOFT delete -- so its own remove hooks (the port-ledger release) run; the
 * children are removed with raw statements, which is acceptable ONLY because this is teardown
 * of rows the test created.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class InstanceRowCleanup {

    /** Every declared foreign key of the database: which child column points at which parent column. */
    private static final String FOREIGN_KEYS = "SELECT m.name AS child_table, p.\"from\" AS child_column,"
        + " p.\"table\" AS parent_table, p.\"to\" AS parent_column"
        + " FROM sqlite_master m JOIN pragma_foreign_key_list(m.name) p WHERE m.type = 'table'";

    /** One declared foreign key; a null parent column means the parent's rowid primary key. */
    private record ForeignKey(@NonNull String childTable, @NonNull String childColumn,
                              @NonNull String parentTable, @NonNull String parentColumn) {
    }

    private InstanceRowCleanup() {
    }

    /**
     * Hard-delete one instance and every row that references it; call it inside the owning
     * tier's {@code GeneratedRows} scope when the instance carries generated attribution.
     */
    public static void delete(int instanceId) {
        SqlDatasource datasource = datasource();
        List<ForeignKey> keys = foreignKeys(datasource);
        String table = Models.get(InstanceModel.class).getTableName();
        deleteReferencing(datasource, keys, table, InstanceModel.ID.getName(), instanceId);
        HardDeletes.byId(Models.get(InstanceModel.class), instanceId);
    }

    /** Remove, deepest first, every row whose foreign key points at a row of table where column = value. */
    private static void deleteReferencing(@NonNull SqlDatasource datasource, @NonNull List<ForeignKey> keys,
                                          @NonNull String table, @NonNull String column,
                                          @NonNull Object value) {
        for (ForeignKey key : keys) {
            if (!key.parentTable().equals(table)) {
                continue;
            }
            for (Row parent : datasource.rawQuery("SELECT " + quote(key.parentColumn()) + " AS referenced FROM "
                    + quote(table) + " WHERE " + quote(column) + " = ?", value)) {
                Object referenced = parent.get("referenced");
                if (referenced == null) {
                    continue;
                }
                deleteReferencing(datasource, keys, key.childTable(), key.childColumn(), referenced);
                datasource.rawUpdate("DELETE FROM " + quote(key.childTable()) + " WHERE "
                    + quote(key.childColumn()) + " = ?", referenced);
            }
        }
    }

    private static @NonNull List<ForeignKey> foreignKeys(@NonNull SqlDatasource datasource) {
        List<ForeignKey> keys = new ArrayList<>();
        for (Row row : datasource.rawQuery(FOREIGN_KEYS)) {
            Object parentColumn = row.get("parent_column");
            keys.add(new ForeignKey(String.valueOf(row.get("child_table")),
                String.valueOf(row.get("child_column")),
                String.valueOf(row.get("parent_table")),
                parentColumn == null ? "rowid" : String.valueOf(parentColumn)));
        }
        return keys;
    }

    private static @NonNull SqlDatasource datasource() {
        Datasource resolved = Models.resolveDatasource(InstanceModel.class);
        if (resolved instanceof SqlDatasource sql) {
            return sql;
        }
        throw new IllegalStateException("InstanceRowCleanup needs the SQLite control-plane datasource, got "
            + resolved);
    }

    private static @NonNull String quote(@NonNull String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
