package be.elevenways.hohenheim.server.migration;

import be.elevenways.hohenheim.migration.HohenheimMigration;
import be.elevenways.hohenheim.model.DnsDyndnsCredentialModel;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.server.dns.DynamicDnsService;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

import java.util.List;
import java.util.Map;

/**
 * Fields follow the record's TYPE: MX/SRV extras (priority/weight/port) move from flat
 * columns every record carried into the type-schema'd {@code data} JSON column, and the
 * dyndns flag+token columns become rows in their own {@code dns_dyndns_credentials}
 * table (a credential row IS the dynamic flag).
 *
 * AIDEV-NOTE: records that never used the moved fields (every A/AAAA/CNAME/NS/TXT/CAA
 * row, including the live starfleet zone) migrate with data = NULL and no credential
 * row -- their served RRs are byte-identical. Only dynamic=1 rows with a token become
 * credentials: an inert token on a non-dynamic row never authenticated
 * (authenticate() required the flag), so carrying it over would ARM a dead credential.
 * Legacy plaintext tokens are hashed during the copy; the client's plaintext still
 * matches its digest.
 */
public class M091_TypedDnsRecordData extends HohenheimMigration {

    /** The M046 index on the doomed column; SQLite refuses dropping an indexed column. */
    private static final String OLD_TOKEN_INDEX = "dns_records_dyndns_token_index";

    public M091_TypedDnsRecordData() {
        super("2026_09_07_100000", "Type-schema'd DNS record data and dyndns credential table");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.alterTable("dns_records", table ->
            table.addColumn("data", ColumnType.JSON, col -> col.nullable(true).ifNotExists()));

        schema.createTable("dns_dyndns_credentials", table -> {
            table.id();
            table.integer("record_id");
            table.addColumn("token_digest", ColumnType.STRING, col -> col.maxLength(96));
            table.timestamps();
            table.addIndex("record_id");
            // Deliberately NOT unique (the M046 rationale): duplicate digests are
            // resolvable via the constant-time re-check, never a boot refusal.
            table.addIndex("token_digest");
        });

        schema.data("Move MX/SRV extras into data and dyndns tokens into credentials", "1",
            M091_TypedDnsRecordData::backfill);

        schema.alterTable("dns_records", table -> table.dropIndex(OLD_TOKEN_INDEX));
        schema.alterTable("dns_records", table -> {
            table.dropColumn("priority");
            table.dropColumn("weight");
            table.dropColumn("port");
            table.dropColumn("dynamic");
            table.dropColumn("dyndns_token");
        });
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("dns_records", table -> {
            table.addColumn("priority", ColumnType.INTEGER, col -> col.nullable(true).ifNotExists());
            table.addColumn("weight", ColumnType.INTEGER, col -> col.nullable(true).ifNotExists());
            table.addColumn("port", ColumnType.INTEGER, col -> col.nullable(true).ifNotExists());
            table.addColumn("dynamic", ColumnType.BOOLEAN, col -> col.defaultValue(false).ifNotExists());
            table.addColumn("dyndns_token", ColumnType.STRING,
                col -> col.maxLength(96).nullable(true).ifNotExists());
            table.addIndex(OLD_TOKEN_INDEX, List.of("dyndns_token"));
            table.dropColumn("data");
        });
        schema.dropTable("dns_dyndns_credentials");
    }

    /** Reads the doomed columns raw (they are gone from the model) and writes typed shapes. */
    public static void backfill(Datasource datasource) {
        SqlDatasource sql = datasource.unwrap(SqlDatasource.class);
        List<Row> carriers = sql.rawQuery(
            "SELECT id, type, priority, weight, port, dynamic, dyndns_token FROM dns_records"
            + " WHERE priority IS NOT NULL OR weight IS NOT NULL OR port IS NOT NULL"
            + " OR (dynamic = 1 AND dyndns_token IS NOT NULL)");

        Db.run(datasource, () -> {
            DnsRecordModel records = new DnsRecordModel();
            DnsDyndnsCredentialModel credentials = new DnsDyndnsCredentialModel();
            int typed = 0;
            int armed = 0;

            for (Row carrier : carriers) {
                Integer id = intOf(carrier.get("id"));
                if (id == null) {
                    continue;
                }
                String type = carrier.get("type") != null ? String.valueOf(carrier.get("type")) : null;
                Map<String, Object> data = DnsRecordModel.dataFor(type,
                    intOf(carrier.get("priority")), intOf(carrier.get("weight")),
                    intOf(carrier.get("port")));
                if (data != null) {
                    Row record = records.findById(id);
                    if (record != null) {
                        record.set(DnsRecordModel.DATA, data);
                        records.save(record);
                        typed++;
                    }
                }

                Object token = carrier.get("dyndns_token");
                boolean dynamic = Boolean.TRUE.equals(carrier.get("dynamic"))
                    || Integer.valueOf(1).equals(intOf(carrier.get("dynamic")));
                if (dynamic && token != null && !String.valueOf(token).isBlank()) {
                    String stored = String.valueOf(token);
                    Row credential = credentials.createEmptyRow();
                    credential.set(DnsDyndnsCredentialModel.RECORD_ID, id);
                    credential.set(DnsDyndnsCredentialModel.TOKEN_DIGEST,
                        DynamicDnsService.isDigest(stored) ? stored : DynamicDnsService.digest(stored));
                    credentials.save(credential);
                    armed++;
                }
            }

            Blast.slog("hohenheim.migration.dns_record_data_typed",
                Map.of("typed", typed, "credentials", armed));
        });
    }

    private static Integer intOf(Object value) {
        if (value instanceof Integer number) {
            return number;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
