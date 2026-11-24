package be.elevenways.hohenheim.migration;

import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.UuidField;
import be.elevenways.zenit.common.orm.migration.FrozenModel;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.time.Instant;

/**
 * The schema changes after M006: when each side's link to another Hohenheim was confirmed.
 *
 * AIDEV-NOTE: PUBLISHED: starfleet, robbedoes and kuifje applied it on 2026-11-24 (pinned in migration-pins.txt), so
 * it is frozen; never edit this one again: a schema change is a NEW migration. Every link made before confirmed_at
 * existed was made in one go by both sides, so its data step stamps it confirmed at the moment it was made
 * (created_at, or the migration's own moment for a row that never recorded one): a link left unconfirmed would be
 * undone by the joining side (LinkedServers). A link made after starts unconfirmed. down() drops the column, which is
 * all up() added.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class M007_LinkConfirmations extends HohenheimMigration {

    public M007_LinkConfirmations() {
        super("2026_11_24_000001", "link_confirmations");
    }

    @Override
    public void up(@NonNull MigrationBuilder builder) {
        builder.alterTable("linked_servers", table ->
            table.addColumn("confirmed_at", ColumnType.DATETIME, column -> column.nullable(true)));
        builder.data("confirm every link made before a link could wait for its confirmation", "1",
            M007_LinkConfirmations::confirmMadeLinks);
    }

    @Override
    public void down(@NonNull MigrationBuilder builder) {
        builder.alterTable("linked_servers", table -> table.dropColumn("confirmed_at"));
    }

    /** Stamps every link already made as confirmed when it was made; its rows are few (one per linked server). */
    private static void confirmMadeLinks(@NonNull Datasource datasource) {
        Db.run(datasource, () -> {
            UuidField id = UuidField.builder("id").build();
            DateTimeField createdAt = DateTimeField.builder().name("created_at").build();
            DateTimeField confirmedAt = DateTimeField.builder().name("confirmed_at").build();
            FrozenModel links = new FrozenModel("linked_servers", id, createdAt, confirmedAt);
            Instant now = Now.instant();
            for (Row link : links.find().where(confirmedAt.isNull()).all()) {
                Instant made = link.get(createdAt);
                Row written = links.createEmptyRow();
                written.set(id, link.get(id));
                written.set(confirmedAt, made == null ? now : made);
                links.updateExisting(written);
            }
        });
    }
}
