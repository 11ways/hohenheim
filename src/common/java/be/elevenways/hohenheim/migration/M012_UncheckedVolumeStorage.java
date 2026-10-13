package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.migration.FrozenModel;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * A host's volume storage reads "not checked" until its probe ran: the column default becomes {@code unchecked}, and
 * every stored host whose probe never ran (no probe time) moves from the {@code none} the old default stored.
 *
 * AIDEV-NOTE: appended instead of edited into M011, which starfleet and daystrom had applied: an edited M011 makes
 * their next rehearsal refuse ("modified after being applied"), and re-recording its digest by hand would never run
 * this step there. down() is exact: a never-probed {@code unchecked} row is what M011's default stored as {@code none}.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public class M012_UncheckedVolumeStorage extends HohenheimMigration {

    /** A host's volume storage before its first probe, as VolumeBackend.UNCHECKED spells it today. */
    static final String VOLUME_UNCHECKED = "unchecked";

    /** The volume storage the previous default stored before any probe, as production stored it. */
    static final String VOLUME_NONE = "none";

    public M012_UncheckedVolumeStorage() {
        super("012", "Unchecked volume storage");
    }

    @Override
    public void up(@NonNull MigrationBuilder schema) {
        schema.alterTable("servers", table -> table.changeColumn("volume_backend", ColumnType.STRING,
            column -> column.nullable(true).maxLength(32).defaultValue(VOLUME_UNCHECKED)));
        schema.data("mark every never-probed host's volume storage not checked", "1",
            M012_UncheckedVolumeStorage::uncheckNeverProbedHosts);
    }

    @Override
    public void down(@NonNull MigrationBuilder schema) {
        schema.data("store none again for every never-probed host", "1", datasource ->
            moveNeverProbedHosts(datasource, VOLUME_UNCHECKED, VOLUME_NONE));
        schema.alterTable("servers", table -> table.changeColumn("volume_backend", ColumnType.STRING,
            column -> column.nullable(true).maxLength(32).defaultValue(VOLUME_NONE)));
    }

    /** The data step marking every host whose probe never ran (no probe time) not checked instead of none. */
    public static void uncheckNeverProbedHosts(@NonNull Datasource datasource) {
        moveNeverProbedHosts(datasource, VOLUME_NONE, VOLUME_UNCHECKED);
    }

    private static void moveNeverProbedHosts(@NonNull Datasource datasource, @NonNull String from,
                                             @NonNull String to) {
        Db.run(datasource, () -> {
            IntegerField id = IntegerField.builder().name("id").build();
            StringField backend = StringField.builder().name("volume_backend").build();
            DateTimeField probedAt = DateTimeField.builder().name("volume_probed_at").build();
            FrozenModel servers = new FrozenModel("servers", id, backend, probedAt);
            servers.find().where(backend.eq(from)).where(probedAt.isNull()).assign(backend, to).updateAll();
        });
    }
}
