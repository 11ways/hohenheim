package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.Migration;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

/**
 * Daemon-side snapshot names on instance snapshot rows: the Incus driver's snapshots
 * live in the instance's own storage pool instead of a controller directory.
 */
public class M071_NativeSnapshots extends Migration {

    public M071_NativeSnapshots() {
        super("2026_08_11_100000", "Native snapshots");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.alterTable("instance_snapshots", table ->
            table.addColumn("native_name", ColumnType.STRING,
                column -> column.maxLength(255).nullable().ifNotExists()));
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("instance_snapshots", table -> table.dropColumn("native_name"));
    }
}
