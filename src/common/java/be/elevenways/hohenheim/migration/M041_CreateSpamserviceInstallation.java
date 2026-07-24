package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.ForeignKeyAction;
import be.elevenways.zenit.common.orm.migration.Migration;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

/** Creates the fixed-row singleton backing table for Hohenheim's local Spamservice runtime. */
public class M041_CreateSpamserviceInstallation extends Migration {

    public M041_CreateSpamserviceInstallation() {
        super("2026_07_23_000041", "Create the Spamservice installation table");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.createTable("spamservice_installations", table -> {
            table.id();
            table.addColumn("enabled", ColumnType.BOOLEAN, column -> column.defaultValue(false));
            table.addColumn("port", ColumnType.INTEGER, column -> column.defaultValue(8095));
            table.addColumn("system_user_id", ColumnType.INTEGER, column -> column
                .nullable(true)
                .references("system_users", "id").onDelete(ForeignKeyAction.RESTRICT));
            table.addColumn("max_heap_mb", ColumnType.INTEGER, column -> column.defaultValue(512));
            table.addColumn("controller_key", ColumnType.STRING, column -> column
                .maxLength(256).nullable(true));
            table.timestamps();
        });
        // Structural singleton stub: every mutation updates id=1, so concurrent first saves cannot insert twins.
        schema.execute("INSERT INTO spamservice_installations (id, enabled, port, max_heap_mb)"
            + " VALUES (1, FALSE, 8095, 512)");
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.dropTable("spamservice_installations");
    }
}
