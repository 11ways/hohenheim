package be.elevenways.hohenheim.migration;

import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.ports.SinglePortStreamSettings;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.SchemaField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.migration.FrozenModel;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A TCP/UDP stream site carries a list of public ports: every stream's single port becomes a list of one.
 *
 * AIDEV-NOTE: the old shape and both conversions are SinglePortStreamSettings', frozen, which SiteModel's revisions
 * read through too, so a revision stored before this upgrade restores as a list of one. It merges no sites: that is a
 * person's act (SitePortMoves). Trashed streams are converted too, so a restore brings back a stream the relay reads.
 * Down turns a list of one back into the single-port keys and refuses, naming them, while a stream holds several
 * ports, a trashed one included (a move leaves its ports on the emptied stream): splitting a merged site is no
 * rollback's to guess.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class M008_StreamPortLists extends HohenheimMigration {

    public M008_StreamPortLists() {
        super("2026_11_25_000001", "stream_port_lists");
    }

    @Override
    public void up(@NonNull MigrationBuilder builder) {
        builder.data("give every TCP/UDP stream site its single public port as a list of one", "1",
            M008_StreamPortLists::toLists);
    }

    @Override
    public void down(@NonNull MigrationBuilder builder) {
        builder.data("give every TCP/UDP stream site of one public port its single port back", "1",
            M008_StreamPortLists::toSinglePorts);
    }

    private static void toLists(@NonNull Datasource datasource) {
        Db.run(datasource, () -> {
            Sites sites = new Sites();
            for (Row site : sites.streams()) {
                Map<String, Object> settings = RawValues.storedMapCopy(site.get(sites.settings));
                if (settings != null && SinglePortStreamSettings.listOfOne(settings)) {
                    sites.write(site.get(sites.id), settings);
                }
            }
        });
    }

    private static void toSinglePorts(@NonNull Datasource datasource) {
        Db.run(datasource, () -> {
            Sites sites = new Sites();
            List<String> several = new ArrayList<>();
            for (Row site : sites.streams()) {
                Map<String, Object> settings = RawValues.storedMapCopy(site.get(sites.settings));
                Map<?, ?> ports = settings == null ? Map.of() : SinglePortStreamSettings.portsOf(settings);
                if (ports.size() > 1) {
                    several.add(site.get(sites.name) + (site.get(sites.deletedAt) == null ? "" : ", in the trash")
                        + " (" + String.join(", ", ports.keySet().stream().map(String::valueOf).toList()) + ")");
                }
            }
            if (!several.isEmpty()) {
                throw new IllegalStateException("These TCP/UDP streams forward several ports, which the single-port"
                    + " shape cannot hold; move their ports into sites of one port each first. A stream in the trash"
                    + " holding several ports (one a move emptied keeps them) blocks the rollback too: purging it"
                    + " lifts that. " + String.join("; ", several));
            }
            for (Row site : sites.streams()) {
                Map<String, Object> settings = RawValues.storedMapCopy(site.get(sites.settings));
                if (settings != null && SinglePortStreamSettings.singlePort(settings)) {
                    sites.write(site.get(sites.id), settings);
                }
            }
        });
    }

    /** The sites table at this schema point, trashed rows included (a frozen model reads every row). */
    private static final class Sites {
        final IntegerField id = IntegerField.builder().name("id").build();
        final StringField name = StringField.builder().name("name").build();
        final StringField kind = StringField.builder().name(SinglePortStreamSettings.UPSTREAM_KIND).build();
        final SchemaField settings = SchemaField.builder(SinglePortStreamSettings.SETTINGS).nullable(true).build();
        final DateTimeField deletedAt = DateTimeField.builder().name("deleted_at").nullable(true).build();
        final FrozenModel sites = new FrozenModel("sites", this.id, this.name, this.kind, this.settings,
            this.deletedAt);

        @NonNull List<Row> streams() {
            return this.sites.find().where(this.kind.eq(SinglePortStreamSettings.STREAM_KIND)).all();
        }

        void write(@Nullable Integer siteId, @NonNull Map<String, Object> written) {
            Row row = this.sites.createEmptyRow();
            row.set(this.id, siteId);
            row.set(this.settings, written);
            this.sites.updateExisting(row);
        }
    }
}
