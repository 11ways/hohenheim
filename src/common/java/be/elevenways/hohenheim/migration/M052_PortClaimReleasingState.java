package be.elevenways.hohenheim.migration;

import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;

/**
 * Adds the port ledger's third state: {@code status} = {@code held} | {@code releasing}.
 * A claim whose teardown was not OBSERVED to succeed parks in {@code releasing} instead of
 * being deleted, and keeps blocking rival claims until an observer frees it (C6).
 */
public class M052_PortClaimReleasingState extends HohenheimMigration {

    public M052_PortClaimReleasingState() {
        // Sorts after zenit-auth's 2026_08_03_000130-class versions in the shared ordering.
        super("2026_08_03_000152", "Port claim releasing state");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.alterTable("port_allocations", table -> table.addColumn("status",
            ColumnType.STRING, col -> col.maxLength(16).nullable(true)));
        schema.data("Existing port claims start as held", "1",
            M052_PortClaimReleasingState::healStatuses);
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("port_allocations", table -> table.dropColumn("status"));
    }

    /**
     * The heal, exposed for the migration-integrity test.
     *
     * @return the number of pre-existing claim rows stamped {@code held}
     */
    public static int healStatuses(Datasource datasource) {
        int[] healed = {0};
        Db.run(datasource, () -> {
            LegacyAllocation ledger = new LegacyAllocation();
            for (Row claim : ledger.find().all()) {
                if (claim.get(LegacyAllocation.STATUS) == null) {
                    claim.set(LegacyAllocation.STATUS, "held");
                    ledger.save(claim);
                    healed[0]++;
                }
            }
        });
        return healed[0];
    }

    /** The ledger table free of hooks and future fields (the M051 legacy-shape stance). */
    private static final class LegacyAllocation extends Model {
        static final Schema SCHEMA = new Schema();
        static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
        static final StringField STATUS = SCHEMA.addField(
            StringField.builder().name("status").build());

        @Override public Identifier getModelId() {
            return Identifier.of("hohenheim", "m052_port_allocation");
        }
        @Override public Field<?, ?> getPrimaryKeyField() { return ID; }
        @Override public String getModelName() { return "M052PortAllocation"; }
        @Override public String getTableName() { return "port_allocations"; }
        @Override public Schema getSchema() { return SCHEMA; }
    }
}
