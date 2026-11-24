package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.UuidField;
import be.elevenways.zenit.common.orm.generation.StoredGenerations;
import be.elevenways.zenit.common.orm.migration.FrozenModel;
import be.elevenways.zenit.common.orm.migration.M001_ZenitBaseline;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

/**
 * The schema changes after M005: the stored generation of every kind a linked master keeps a record of, what the
 * master keeps of each linked server's own counts and of its last sync, and when each side's link was confirmed.
 *
 * AIDEV-NOTE: UNPUBLISHED: every further schema change edits this migration until a live deploy publishes it. The
 * kinds are spelled as literal model ids, frozen here like every migration value: a kind shared later is a new
 * migration's birth step. requiresTransaction=false, as StoredGenerations.birthStep asks, and it depends on core's
 * baseline that holds zenit_model_generations. The new columns start empty: an upgraded master reads its linked
 * servers' counts at the next check and takes their records at the next sync. Every link made before confirmed_at
 * existed was made in one go by both sides, so it is confirmed since it was made; a link made after starts
 * unconfirmed (LinkedServers). down() undoes exactly what up() did.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class M006_SharedRecords extends HohenheimMigration {

    /** Every kind a linked master keeps a record of, as its model id stood when this migration was written. */
    private static final List<String> SHARED = List.of("hohenheim:access_list", "hohenheim:access_rule",
        "hohenheim:backup_target", "hohenheim:build_hook", "hohenheim:database_engine", "hohenheim:database",
        "hohenheim:dns_dyndns_credential", "hohenheim:dns_peer", "hohenheim:dns_record", "hohenheim:dns_zone",
        "hohenheim:dns_zone_peer", "hohenheim:environment", "hohenheim:game_domain", "hohenheim:git_provider",
        "hohenheim:instance_database", "hohenheim:instance_device", "hohenheim:instance_file", "hohenheim:instance",
        "hohenheim:instance_quota", "hohenheim:instance_template", "hohenheim:instance_template_database",
        "hohenheim:instance_template_file", "hohenheim:instance_template_variable",
        "hohenheim:instance_template_volume", "hohenheim:instance_variable", "hohenheim:instance_volume",
        "hohenheim:notification_channel", "hohenheim:project", "hohenheim:protected_path", "hohenheim:runtime_image",
        "hohenheim:server", "hohenheim:site_auth_provider", "hohenheim:site_domain", "hohenheim:site",
        "hohenheim:site_redirect", "hohenheim:stack_file", "hohenheim:stack", "hohenheim:stack_service",
        "hohenheim:instance_backup", "hohenheim:database_dump");

    public M006_SharedRecords() {
        super("2026_11_23_000003", "shared_records", false);
        dependsOn(M001_ZenitBaseline.class);
    }

    @Override
    public void up(@NonNull MigrationBuilder builder) {
        builder.alterTable("linked_servers", table -> {
            table.addColumn("apps", ColumnType.INTEGER, column -> column.nullable(true));
            table.addColumn("sites", ColumnType.INTEGER, column -> column.nullable(true));
            table.addColumn("in_step_since", ColumnType.DATETIME, column -> column.nullable(true));
            table.addColumn("went_back", ColumnType.TEXT, column -> column.nullable(true));
            table.addColumn("restored_at", ColumnType.DATETIME, column -> column.nullable(true));
            table.addColumn("skipped", ColumnType.TEXT, column -> column.nullable(true));
            table.addColumn("confirmed_at", ColumnType.DATETIME, column -> column.nullable(true));
        });
        builder.data("confirm every link made before a link could wait for its confirmation", "1",
            M006_SharedRecords::confirmMadeLinks);
        for (String kind : SHARED) {
            StoredGenerations.birthStep(builder, kind);
        }
    }

    @Override
    public void down(@NonNull MigrationBuilder builder) {
        for (String kind : SHARED.reversed()) {
            StoredGenerations.unbirthStep(builder, kind);
        }
        builder.alterTable("linked_servers", table -> {
            table.dropColumn("confirmed_at");
            table.dropColumn("skipped");
            table.dropColumn("restored_at");
            table.dropColumn("went_back");
            table.dropColumn("in_step_since");
            table.dropColumn("sites");
            table.dropColumn("apps");
        });
    }

    /** Stamps every link already made as confirmed when it was made; its rows are few (one per linked server). */
    private static void confirmMadeLinks(@NonNull Datasource datasource) {
        Db.run(datasource, () -> {
            UuidField id = UuidField.builder("id").build();
            DateTimeField createdAt = DateTimeField.builder().name("created_at").build();
            DateTimeField confirmedAt = DateTimeField.builder().name("confirmed_at").build();
            FrozenModel links = new FrozenModel("linked_servers", id, createdAt, confirmedAt);
            for (Row link : links.find().where(confirmedAt.isNull()).all()) {
                Row written = links.createEmptyRow();
                written.set(id, link.get(id));
                written.set(confirmedAt, link.get(createdAt));
                links.updateExisting(written);
            }
        });
    }
}
