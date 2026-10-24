package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.SchemaField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.migration.FrozenModel;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M002_AfterBaseline's backfill: the Minecraft servers made from the starter templates before the status probe existed
 * declare it too, so the live upgrade shows their players online without anyone editing them.
 *
 * AIDEV-NOTE: frozen literal facts, the way the seeds and StatusProbe spell them on 2026-10-24, and never a template
 * NAME: migrations run before seeders, so an install that has not yet run GameTemplateSeeder.upgradeStarters still
 * names the standalone server "Minecraft (Paper)" and the behind-Velocity backend "Minecraft server (Paper)". An
 * instance qualifies when its template is a starter (source {@code hohenheim:starter}), its own settings run one of
 * the two Minecraft-protocol images the starters players connect to ship (the standalone server's and the Velocity
 * proxy's, GameTemplateSeeder.declareStatusProbes), they publish a {@code game} port (the backend publishes none:
 * players reach it through the proxy), and they carry no {@code status_probe} key at all; a key present, cleared or
 * not, is an operator's choice and stays. Every instance the step writes is recorded in the {@link #LEDGER} table,
 * so the down removes the probe from exactly those, and only while it still reads {@code minecraft_java}, before the
 * migration drops the ledger. The descriptions and body versions are signed into the migration's checksum.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
final class StatusProbeBackfill {

    /** The table recording every instance the backfill wrote. */
    static final String LEDGER = "status_probe_backfills";

    private static final String STARTER_SOURCE = "hohenheim:starter";

    private static final List<String> PROBED_IMAGES = List.of("itzg/minecraft-server", "dockcenter/velocity");

    private static final String IMAGE_SETTING = "image";

    private static final String PORTS_SETTING = "published_ports";

    private static final String GAME_PORT = "game";

    private static final String PROBE_SETTING = "status_probe";

    private static final String MINECRAFT_JAVA = "minecraft_java";

    private StatusProbeBackfill() {
    }

    /** The ledger, then the backfill that fills it. */
    static void up(@NonNull MigrationBuilder builder) {
        builder.createTable(LEDGER, table ->
            table.addColumn("instance_id", ColumnType.INTEGER, column -> column.primaryKey()));
        builder.data("give the starter Minecraft servers made before it existed their status probe", "2",
            StatusProbeBackfill::backfill);
    }

    /** Takes back exactly what {@link #up} gave, then drops the ledger. */
    static void down(@NonNull MigrationBuilder builder) {
        builder.data("take the status probe back from the servers the backfill gave it to", "1",
            StatusProbeBackfill::takeBack);
        builder.dropTable(LEDGER);
    }

    private static void backfill(@NonNull Datasource datasource) {
        Db.run(datasource, () -> {
            Tables tables = new Tables();
            List<Integer> starters = new ArrayList<>();
            for (Row template : tables.templates.find().where(tables.templateSource.eq(STARTER_SOURCE)).all()) {
                starters.add(template.get(tables.templateId));
            }
            if (starters.isEmpty()) {
                return;
            }
            for (Row instance : tables.instances.find().where(tables.instanceTemplate.in(starters)).all()) {
                Map<String, Object> settings = settingsOf(instance.get(tables.settings));
                if (settings == null || !isProbedServer(settings)) {
                    continue;
                }
                settings.put(PROBE_SETTING, MINECRAFT_JAVA);
                Integer instanceId = instance.get(tables.instanceId);
                tables.write(instanceId, settings);
                Row touched = tables.ledger.createEmptyRow();
                touched.set(tables.ledgerInstance, instanceId);
                tables.ledger.insert(touched);
            }
        });
    }

    private static void takeBack(@NonNull Datasource datasource) {
        Db.run(datasource, () -> {
            Tables tables = new Tables();
            List<Integer> touched = new ArrayList<>();
            for (Row entry : tables.ledger.find().all()) {
                touched.add(entry.get(tables.ledgerInstance));
            }
            if (touched.isEmpty()) {
                return;
            }
            for (Row instance : tables.instances.find().where(tables.instanceId.in(touched)).all()) {
                Map<String, Object> settings = settingsOf(instance.get(tables.settings));
                if (settings != null && MINECRAFT_JAVA.equals(settings.get(PROBE_SETTING))) {
                    settings.remove(PROBE_SETTING);
                    tables.write(instance.get(tables.instanceId), settings);
                }
            }
        });
    }

    /** @return whether a starter's instance is a server players connect to that declares no probe yet */
    private static boolean isProbedServer(@NonNull Map<String, Object> settings) {
        return !settings.containsKey(PROBE_SETTING) && PROBED_IMAGES.contains(settings.get(IMAGE_SETTING))
            && settings.get(PORTS_SETTING) instanceof Map<?, ?> ports && ports.containsKey(GAME_PORT);
    }

    /** @return a mutable copy of the stored settings, null when the column holds no map */
    private static @Nullable Map<String, Object> settingsOf(@Nullable Object stored) {
        Object tree = stored instanceof String text ? Zenit.DRY.parseTree(text) : stored;
        if (!(tree instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> settings = new LinkedHashMap<>();
        map.forEach((key, value) -> settings.put(String.valueOf(key), value));
        return settings;
    }

    /** The tables at this schema point. */
    private static final class Tables {
        final IntegerField templateId = IntegerField.builder().name("id").build();
        final StringField templateName = StringField.builder().name("name").build();
        final StringField templateSource = StringField.builder().name("source").build();
        final FrozenModel templates = new FrozenModel("instance_templates", this.templateId, this.templateName,
            this.templateSource);

        final IntegerField instanceId = IntegerField.builder().name("id").build();
        final IntegerField instanceTemplate = IntegerField.builder().name("template_id").build();
        final SchemaField settings = SchemaField.builder("settings").nullable(true).build();
        final FrozenModel instances = new FrozenModel("instances", this.instanceId, this.instanceTemplate,
            this.settings);

        final IntegerField ledgerInstance = IntegerField.builder().name("instance_id").build();
        final FrozenModel ledger = new FrozenModel(LEDGER, this.ledgerInstance);

        void write(@NonNull Integer id, @NonNull Map<String, Object> settings) {
            Row written = this.instances.createEmptyRow();
            written.set(this.instanceId, id);
            written.set(this.settings, settings);
            this.instances.updateExisting(written);
        }
    }
}
