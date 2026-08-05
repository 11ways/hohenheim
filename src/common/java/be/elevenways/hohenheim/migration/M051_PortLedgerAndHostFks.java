package be.elevenways.hohenheim.migration;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.SchemaField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ONE port ledger and ONE host key: creates {@code port_allocations} (the persistent,
 * cross-authority port claim table, UNIQUE on the derived claim key), folds the three
 * host-name spellings ({@code stacks.server_name}, {@code managed_databases.server_name},
 * docker sites' {@code settings["server"]}) onto a real {@code servers.id} FK, and
 * backfills the ledger from the stacks tier's declared host ports.
 *
 * Heal-then-constrain, reported: the live bug was the local daemon spelled both {@code ""}
 * and {@code "local"} across three separate normalisations, which would have given the
 * ledger two disjoint claim sets for one machine while every unique constraint held. The
 * heal resolves every legacy spelling through {@link ServerModel#canonicalSpelling}, creates
 * a placeholder server row for a name that references no inventory row (visible and
 * unreachable rather than silently redirected to local), logs every count, and the claims
 * backfill keeps the lowest service id per contested tuple (the M045 stance -- losers were
 * already colliding at deploy time and their next edit gets the real conflict message).
 */
public class M051_PortLedgerAndHostFks extends HohenheimMigration {

    public M051_PortLedgerAndHostFks() {
        super("2026_08_03_000051", "Port allocation ledger and canonical server FKs");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.createTable("port_allocations", table -> {
            table.id();
            table.addColumn("server_id", ColumnType.INTEGER,
                col -> col.references("servers", "id"));
            table.string("host_ip", 64);
            table.integer("port");
            table.string("protocol", 8);
            table.string("claim_key", 128);
            table.addColumn("owner_model", ColumnType.STRING,
                col -> col.maxLength(128).nullable(true));
            table.addColumn("owner_id", ColumnType.INTEGER, col -> col.nullable(true));
            table.addColumn("note", ColumnType.STRING, col -> col.maxLength(256).nullable(true));
            table.timestamps();
            table.unique("claim_key");
        });

        schema.alterTable("stacks", table -> table.addColumn("server_id", ColumnType.INTEGER,
            col -> col.nullable(true).references("servers", "id")));
        schema.alterTable("managed_databases", table -> table.addColumn("server_id",
            ColumnType.INTEGER, col -> col.nullable(true).references("servers", "id")));

        schema.data("Fold every host-name spelling onto the servers FK", "1",
            M051_PortLedgerAndHostFks::healHostKeys);
        schema.data("Claim every stack service's declared host ports in the ledger", "1",
            PortLedger::backfill);

        schema.alterTable("stacks", table -> table.dropColumn("server_name"));
        schema.alterTable("managed_databases", table -> table.dropColumn("server_name"));
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("managed_databases", table -> table.addColumn("server_name",
            ColumnType.STRING, col -> col.maxLength(128).nullable(true).defaultValue("local")));
        schema.alterTable("stacks", table -> table.addColumn("server_name",
            ColumnType.STRING, col -> col.maxLength(128).nullable(true)));
        schema.alterTable("managed_databases", table -> table.dropColumn("server_id"));
        schema.alterTable("stacks", table -> table.dropColumn("server_id"));
        schema.dropTable("port_allocations");
    }

    /**
     * The host-key heal, exposed for the migration-integrity test.
     *
     * @return the number of placeholder server rows created for names no inventory row backed
     */
    public static int healHostKeys(Datasource datasource) {
        int[] created = {0};
        Db.run(datasource, () -> {
            // AIDEV-NOTE: the ERA-FROZEN server shape, never the live ServerModel: the
            // live schema has since grown columns (M056 posture/admission/...) whose
            // create-time defaults would be written into a table that does not have
            // them yet at M051 time -- the frozen-migration-meaning trap.
            LegacyServer servers = new LegacyServer();
            Map<String, Integer> idByName = new HashMap<>();
            for (Row server : servers.find().all()) {
                idByName.put(String.valueOf(server.get(LegacyServer.NAME)),
                    server.get(LegacyServer.ID));
            }

            int stacks = relink(new LegacyStack(), servers, idByName, created);
            int databases = relink(new LegacyDatabase(), servers, idByName, created);
            int sites = rewriteDockerSites(servers, idByName, created);

            Blast.log("HOSTS: canonical server FK heal:", stacks, "stack(s),", databases,
                "database(s),", sites, "docker site(s) relinked;", created[0],
                "placeholder server row(s) created for unknown names");
        });
        return created[0];
    }

    /** Resolve one canonical spelling to a server id, creating a placeholder when unknown. */
    private static int resolveOrCreate(String spelling, LegacyServer servers,
                                       Map<String, Integer> idByName, int[] created) {
        Integer known = idByName.get(spelling);
        if (known != null) {
            return known;
        }
        Row row = servers.createEmptyRow();
        row.set(LegacyServer.NAME, spelling);
        // A previously deleted/never-registered remote: keep the claim visible (and the
        // host unreachable until an operator supplies its ssh target) instead of silently
        // redirecting the workload to the local daemon. The local row itself is created
        // in local mode -- ServerModel.canonicalSpelling already folded blanks onto it.
        row.set(LegacyServer.MODE, ServerModel.MODE_LOCAL.equals(spelling)
            ? ServerModel.MODE_LOCAL : ServerModel.MODE_SSH);
        servers.save(row);
        created[0]++;
        idByName.put(spelling, row.get(LegacyServer.ID));
        return row.get(LegacyServer.ID);
    }

    private static int relink(LegacyServerNamed legacy, LegacyServer servers,
                              Map<String, Integer> idByName, int[] created) {
        int relinked = 0;
        for (Row row : legacy.find().all()) {
            String spelling = ServerModel.canonicalSpelling(row.get(legacy.serverName()));
            row.set(legacy.serverId(), resolveOrCreate(spelling, servers, idByName, created));
            legacy.save(row);
            relinked++;
        }
        return relinked;
    }

    @SuppressWarnings("unchecked")
    private static int rewriteDockerSites(LegacyServer servers, Map<String, Integer> idByName,
                                          int[] created) {
        LegacySite sites = new LegacySite();
        int rewritten = 0;
        for (Row site : sites.find().where(LegacySite.SITE_TYPE.eq("docker")).all()) {
            // The repo-wide spelling for a raw SchemaField read (SiteResource, SiteDispatcher).
            Map<String, Object> settings = (Map<String, Object>) site.get(LegacySite.SETTINGS);
            Object raw = settings != null ? settings.get("server") : null;
            if (raw == null || String.valueOf(raw).isBlank()) {
                continue;   // absent means local by derivation; nothing stored to rewrite
            }
            int serverId = resolveOrCreate(ServerModel.canonicalSpelling(raw),
                servers, idByName, created);
            Map<String, Object> updated = new LinkedHashMap<>(settings);
            updated.put("server", ServerModel.registryKeyOf(serverId));
            site.set(LegacySite.SETTINGS, updated);
            sites.save(site);
            rewritten++;
        }
        return rewritten;
    }

    // -- legacy shapes: the pre-FK columns, free of hooks and behaviours ------

    /** A model that still carries the legacy {@code server_name} column plus the new FK. */
    private abstract static class LegacyServerNamed extends Model {
        abstract StringField serverName();
        abstract IntegerField serverId();
    }

    private static final class LegacyStack extends LegacyServerNamed {
        static final Schema SCHEMA = new Schema();
        static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
        static final StringField SERVER_NAME = SCHEMA.addField(
            StringField.builder().name("server_name").build());
        static final IntegerField SERVER_ID = SCHEMA.addField(
            IntegerField.builder().name("server_id").build());

        @Override StringField serverName() { return SERVER_NAME; }
        @Override IntegerField serverId() { return SERVER_ID; }
        @Override public Identifier getModelId() { return Identifier.of("hohenheim", "m051_stack"); }
        @Override public Field<?, ?> getPrimaryKeyField() { return ID; }
        @Override public String getModelName() { return "M051Stack"; }
        @Override public String getTableName() { return "stacks"; }
        @Override public Schema getSchema() { return SCHEMA; }
    }

    private static final class LegacyDatabase extends LegacyServerNamed {
        static final Schema SCHEMA = new Schema();
        static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
        static final StringField SERVER_NAME = SCHEMA.addField(
            StringField.builder().name("server_name").build());
        static final IntegerField SERVER_ID = SCHEMA.addField(
            IntegerField.builder().name("server_id").build());

        @Override StringField serverName() { return SERVER_NAME; }
        @Override IntegerField serverId() { return SERVER_ID; }
        @Override public Identifier getModelId() { return Identifier.of("hohenheim", "m051_database"); }
        @Override public Field<?, ?> getPrimaryKeyField() { return ID; }
        @Override public String getModelName() { return "M051Database"; }
        @Override public String getTableName() { return "managed_databases"; }
        @Override public Schema getSchema() { return SCHEMA; }
    }

    /** The M051-era servers shape: id/name/mode only, no live-model defaults. */
    private static final class LegacyServer extends Model {
        static final Schema SCHEMA = new Schema();
        static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
        static final StringField NAME = SCHEMA.addField(
            StringField.builder().name("name").build());
        static final StringField MODE = SCHEMA.addField(
            StringField.builder().name("mode").build());

        @Override public Identifier getModelId() { return Identifier.of("hohenheim", "m051_server"); }
        @Override public Field<?, ?> getPrimaryKeyField() { return ID; }
        @Override public String getModelName() { return "M051Server"; }
        @Override public String getTableName() { return "servers"; }
        @Override public Schema getSchema() { return SCHEMA; }
    }

    private static final class LegacySite extends Model {
        static final Schema SCHEMA = new Schema();
        static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
        static final StringField SITE_TYPE = SCHEMA.addField(
            StringField.builder().name("site_type").build());
        static final SchemaField SETTINGS = SCHEMA.addField(SchemaField.builder("settings").build());

        @Override public Identifier getModelId() { return Identifier.of("hohenheim", "m051_site"); }
        @Override public Field<?, ?> getPrimaryKeyField() { return ID; }
        @Override public String getModelName() { return "M051Site"; }
        @Override public String getTableName() { return "sites"; }
        @Override public Schema getSchema() { return SCHEMA; }
    }
}
