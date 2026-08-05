package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

public class M019_CreateNotificationChannels extends HohenheimMigration {

    public M019_CreateNotificationChannels() {
        super("2026_05_26_000019", "Create notification_channels table");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.createTable("notification_channels", table -> {
            table.id();
            table.string("name", 128);
            table.string("kind", 16);     // webhook (covers slack/discord/generic for now)
            table.string("format", 16);   // slack | discord | generic -- shapes the JSON body
            table.string("url", 512);
            table.timestamps();
            table.unique("name");
        });
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.dropTable("notification_channels");
    }
}
