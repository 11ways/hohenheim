package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.BooleanField;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.migration.FrozenModel;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.HashSet;
import java.util.Set;

/**
 * The schema changes after M008 for the storage screens (board FO128, Jelle 2026-11-28): a storage's private-address
 * switch, whether everyone may use it and who added it, and a database's own storage and number of dumps kept.
 *
 * AIDEV-NOTE: OPEN until the next live deploy applies it (then PUBLISHED is raised and it is pinned). Before the switch
 * existed every operator's storage reached private addresses under the boot's opt-in, so the data step turns it on
 * for each storage no manage grant names (auth_record_grants, read as zenit-auth stores it: the model id in plain
 * text, the record id as text): nothing that connects today stops. A storage a grant names, a live or an expired one,
 * was a customer's and keeps it off, so it never reaches a private address once its owner is gone.
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
        // The data step reads who holds each storage, so the grants table is there first.
        dependsOn("be.elevenways.zenit.auth.server.migration.M001_AuthBaseline");
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
        builder.data("let every operator's storage declared before the switch reach private addresses as it did", "2",
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

    /** The model id auth_record_grants names a storage by: BackupTargetModel.MODEL_ID as it was on 2026-11-28. */
    static final String STORAGE_MODEL = "hohenheim:backup_target";

    /** Turns the switch on for every storage no manage grant names; they are few (tens at most). */
    private static void keepPrivateReach(@NonNull Datasource datasource) {
        Db.run(datasource, () -> {
            StringField grantId = StringField.builder("id").build();
            StringField model = StringField.builder("model").build();
            StringField recordId = StringField.builder("record_id").build();
            StringField capability = StringField.builder("capability").build();
            BooleanField value = BooleanField.builder("value").build();
            Set<String> held = new HashSet<>();
            for (Row grant : new FrozenModel("auth_record_grants", grantId, model, recordId, capability, value).find()
                    .where(model.eq(STORAGE_MODEL)).where(capability.eq("manage")).all()) {
                if (Boolean.TRUE.equals(grant.get(value))) {
                    held.add(String.valueOf((Object) grant.get(recordId)));
                }
            }
            IntegerField id = IntegerField.builder().name("id").build();
            BooleanField allowed = BooleanField.builder("private_addresses_allowed").nullable(true).build();
            FrozenModel storages = new FrozenModel("backup_targets", id, allowed);
            for (Row storage : storages.find().all()) {
                if (held.contains(String.valueOf((Object) storage.get(id)))) {
                    continue;
                }
                Row written = storages.createEmptyRow();
                written.set(id, storage.get(id));
                written.set(allowed, true);
                storages.updateExisting(written);
            }
        });
    }
}
