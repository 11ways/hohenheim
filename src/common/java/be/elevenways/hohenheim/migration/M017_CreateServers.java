package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.Migration;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

public class M017_CreateServers extends Migration {

    public M017_CreateServers() {
        super("2026_05_25_000017", "Create servers table for the multi-server inventory");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.createTable("servers", table -> {
            table.id();
            table.string("name", 128);
            table.string("mode", 16);                                       // local | ssh
            table.addColumn("ssh_target", ColumnType.STRING, col -> col.maxLength(256).nullable(true));
            table.timestamps();
            table.unique("name");
        });
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.dropTable("servers");
    }
}
