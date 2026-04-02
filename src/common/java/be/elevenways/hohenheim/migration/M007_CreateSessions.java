package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.migration.Migration;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

public class M007_CreateSessions extends Migration {

    public M007_CreateSessions() {
        super("2026_04_02_000007", "Create sessions table");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.createTable("sessions", table -> {
            table.id();
            table.string("token", 255);
            table.foreignId("user_id", "users");
            table.datetime("created_at");
            table.datetime("expires_at");
            table.unique("token");
            table.addIndex("user_id");
        });
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.dropTable("sessions");
    }
}
