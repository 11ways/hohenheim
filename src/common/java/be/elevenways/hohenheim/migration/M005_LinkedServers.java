package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

/**
 * The schema changes after M004: the Hohenheim servers this one is linked to, and the join codes it issued.
 *
 * AIDEV-NOTE: PUBLISHED: starfleet, robbedoes and kuifje applied it on 2026-11-24 (pinned in migration-pins.txt), so
 * it is frozen; never edit this one again. Both tables start empty: an upgraded server is linked to nothing and
 * behaves as before. down() undoes exactly what up() did.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class M005_LinkedServers extends HohenheimMigration {

    public M005_LinkedServers() {
        super("2026_11_23_000002", "linked_servers");
    }

    @Override
    public void up(@NonNull MigrationBuilder builder) {
        builder.createTable("linked_servers", table -> {
            table.addColumn("id", ColumnType.UUID, column -> column.primaryKey());
            table.addColumn("name", ColumnType.STRING, column -> column.nullable(false).maxLength(100));
            table.addColumn("role", ColumnType.STRING, column -> column.nullable(false).maxLength(20));
            table.addColumn("base_url", ColumnType.STRING, column -> column.nullable(false).maxLength(500));
            table.addColumn("link_key", ColumnType.TEXT, column -> column.nullable(true));
            table.addColumn("link_key_digest", ColumnType.STRING, column -> column.nullable(true).maxLength(128));
            table.addColumn("remote_token", ColumnType.STRING, column -> column.nullable(true).maxLength(32));
            table.addColumn("build", ColumnType.STRING, column -> column.nullable(true).maxLength(100));
            table.addColumn("last_seen_at", ColumnType.DATETIME, column -> column.nullable(true));
            table.addColumn("failing_since", ColumnType.DATETIME, column -> column.nullable(true));
            table.addColumn("last_failure", ColumnType.STRING, column -> column.nullable(true).maxLength(32));
            table.addColumn("alerted_at", ColumnType.DATETIME, column -> column.nullable(true));
            table.timestamps();
            table.unique("linked_servers_link_key_digest_unique", List.of("link_key_digest"));
        });
        builder.createTable("linked_server_codes", table -> {
            table.addColumn("id", ColumnType.UUID, column -> column.primaryKey());
            table.addColumn("owner_id", ColumnType.UUID, column -> column.nullable(false));
            table.addColumn("code_digest", ColumnType.TEXT, column -> column.nullable(false));
            table.addColumn("expires_at", ColumnType.DATETIME, column -> column.nullable(false));
            table.addColumn("used_at", ColumnType.DATETIME, column -> column.nullable(true));
            table.timestamps();
            table.addIndex("linked_server_codes_owner_id_index", List.of("owner_id"));
        });
    }

    @Override
    public void down(@NonNull MigrationBuilder builder) {
        builder.dropTable("linked_server_codes");
        builder.dropTable("linked_servers");
    }
}
