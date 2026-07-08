package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.Migration;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

public class M030_AddNotificationEvents extends Migration {

    public M030_AddNotificationEvents() {
        super("2026_07_08_000030", "Add per-event subscriptions to notification_channels");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.alterTable("notification_channels", table ->
            table.addColumn("events", ColumnType.JSON, col -> col.nullable(true)));
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("notification_channels", table -> table.dropColumn("events"));
    }
}
