package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.BooleanField;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.migration.FrozenModel;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * The schema changes after M008 for the storage screens (board FO128, Jelle 2026-11-28): a storage's private-address
 * switch, whether everyone may use it and who added it, and a database's own storage and number of dumps kept.
 *
 * AIDEV-NOTE: OPEN until the next live deploy applies it (then PUBLISHED is raised and it is pinned). Every storage
 * that exists at the upgrade had been declared by an operator before the switch existed and reached private addresses
 * under the boot's opt-in, so its data step turns the switch on for each of them: nothing that connects today stops.
 * A tenant's row valued so is never read as such (a tenant's reach is the public internet, whatever the column says).
 * The databases' table is managed_databases (DatabaseModel). added_by holds a PrincipalRef key (kind and id in one
 * text, the RecordCodeModel.ASSIGNED_BY form), null for every row older than the column.
 * managed_databases.backup_target_id has no foreign key on purpose: SQLite refuses to drop a
 * column a foreign key names, so down() could not undo it; BackupTargetModel's removal hook refuses deleting a storage
 * a database names instead. down() drops exactly the five columns.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class M009_StorageChoices extends HohenheimMigration {

    public M009_StorageChoices() {
        super("2026_11_28_000001", "storage_choices");
    }

    @Override
    public void up(@NonNull MigrationBuilder builder) {
        builder.alterTable("backup_targets", table -> {
            table.addColumn("private_addresses_allowed", ColumnType.BOOLEAN,
                column -> column.nullable(true).defaultValue(false));
            table.addColumn("shared", ColumnType.BOOLEAN, column -> column.nullable(true).defaultValue(false));
            table.addColumn("added_by", ColumnType.STRING, column -> column.nullable(true).maxLength(191));
        });
        builder.alterTable("managed_databases", table -> {
            table.addColumn("backup_target_id", ColumnType.INTEGER, column -> column.nullable(true));
            table.addColumn("dumps_kept", ColumnType.INTEGER, column -> column.nullable(true));
        });
        builder.data("let every storage declared before the switch reach private addresses as it did", "1",
            M009_StorageChoices::keepPrivateReach);
    }

    @Override
    public void down(@NonNull MigrationBuilder builder) {
        builder.alterTable("managed_databases", table -> {
            table.dropColumn("dumps_kept");
            table.dropColumn("backup_target_id");
        });
        builder.alterTable("backup_targets", table -> {
            table.dropColumn("added_by");
            table.dropColumn("shared");
            table.dropColumn("private_addresses_allowed");
        });
    }

    /** Turns the switch on for every storage that exists now; they are few (tens at most). */
    private static void keepPrivateReach(@NonNull Datasource datasource) {
        Db.run(datasource, () -> {
            IntegerField id = IntegerField.builder().name("id").build();
            BooleanField allowed = BooleanField.builder("private_addresses_allowed").nullable(true).build();
            FrozenModel storages = new FrozenModel("backup_targets", id, allowed);
            for (Row storage : storages.find().all()) {
                Row written = storages.createEmptyRow();
                written.set(id, storage.get(id));
                written.set(allowed, true);
                storages.updateExisting(written);
            }
        });
    }
}
