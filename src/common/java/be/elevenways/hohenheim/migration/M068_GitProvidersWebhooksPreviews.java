package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

import java.util.List;

/**
 * Git provider installations, the webhook replay ledger and preview deployments.
 * {@code site_domains} grows the GeneratedRows attribution columns so a preview's
 * generated hostname is SELF-SCOPED: the sweep removes exactly its own rows and a
 * hand-authored row with the same name is never adopted or deleted. No data heals:
 * nothing could have written any of this before it existed, and the new unique index
 * covers a freshly-created empty table only.
 */
public class M068_GitProvidersWebhooksPreviews extends HohenheimMigration {

    public M068_GitProvidersWebhooksPreviews() {
        super("2026_08_07_100000", "Git providers, webhooks and preview deployments");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.createTable("git_providers", table -> {
            table.id();
            table.string("name", 191);
            table.string("kind", 50);
            table.addColumn("base_url", ColumnType.STRING,
                column -> column.maxLength(500).nullable(true));
            // Encrypted envelopes are opaque text, never bounded strings.
            table.addColumn("access_token", ColumnType.TEXT, column -> column.nullable(true));
            table.addColumn("app_id", ColumnType.STRING,
                column -> column.maxLength(100).nullable(true));
            table.addColumn("app_installation_id", ColumnType.STRING,
                column -> column.maxLength(100).nullable(true));
            table.addColumn("app_private_key_pem", ColumnType.TEXT,
                column -> column.nullable(true));
            table.timestamps();
        });

        schema.createTable("webhook_deliveries", table -> {
            table.id();
            table.foreignId("site_id", "sites");
            table.string("delivery_key", 191);
            table.addColumn("event", ColumnType.STRING,
                column -> column.maxLength(100).nullable(true));
            table.addColumn("action", ColumnType.STRING,
                column -> column.maxLength(100).nullable(true));
            table.addColumn("received_at", ColumnType.DATETIME, column -> column.nullable(true));
            table.timestamps();
            table.unique("webhook_deliveries_site_key_unique",
                List.of("site_id", "delivery_key"));
        });

        schema.createTable("preview_deployments", table -> {
            table.id();
            table.foreignId("site_id", "sites");
            table.string("ref", 191);
            table.addColumn("pr_number", ColumnType.INTEGER, column -> column.nullable(true));
            table.addColumn("head_sha", ColumnType.STRING,
                column -> column.maxLength(100).nullable(true));
            table.addColumn("hostname", ColumnType.STRING,
                column -> column.maxLength(255).nullable(true));
            table.string("status", 50);
            table.addColumn("expires_at", ColumnType.DATETIME, column -> column.nullable(true));
            table.addColumn("instance_id", ColumnType.INTEGER, column -> column.nullable(true));
            table.addColumn("quota_bucket", ColumnType.STRING,
                column -> column.maxLength(191).nullable(true));
            table.addColumn("last_error", ColumnType.TEXT, column -> column.nullable(true));
            table.addColumn("deleted_at", ColumnType.DATETIME, column -> column.nullable(true));
            table.timestamps();
            table.addIndex("site_id");
            table.addIndex("expires_at");
        });

        schema.alterTable("site_domains", table -> {
            table.addColumn("generated_by", ColumnType.STRING,
                column -> column.maxLength(100).nullable().ifNotExists());
            table.addColumn("generated_for_model", ColumnType.STRING,
                column -> column.maxLength(100).nullable().ifNotExists());
            table.addColumn("generated_for_id", ColumnType.INTEGER,
                column -> column.nullable().ifNotExists());
            table.addColumn("generated_at", ColumnType.DATETIME,
                column -> column.nullable().ifNotExists());
            table.addIndexIfNotExists("idx_site_domains_generated_for_id",
                List.of("generated_for_id"));
        });
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("site_domains", table -> {
            table.dropColumn("generated_at");
            table.dropColumn("generated_for_id");
            table.dropColumn("generated_for_model");
            table.dropColumn("generated_by");
        });
        schema.dropTable("preview_deployments");
        schema.dropTable("webhook_deliveries");
        schema.dropTable("git_providers");
    }
}
