package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.Migration;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

/**
 * The public-reachability columns: the port ledger's declared acquisition mode
 * (record-after rows keep a null mode; pre-allocated rows are stamped) and the
 * server-address authority DNS A/AAAA generation reads.
 */
public class M063_PublicPortsAndServerAddresses extends Migration {

    public M063_PublicPortsAndServerAddresses() {
        super("2026_08_04_130000", "Public ports and server addresses");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.alterTable("port_allocations", table ->
            table.addColumn("allocation_mode", ColumnType.STRING,
                column -> column.maxLength(20).nullable().ifNotExists()));
        schema.alterTable("servers", table -> {
            table.addColumn("public_ipv4", ColumnType.STRING,
                column -> column.maxLength(45).nullable().ifNotExists());
            table.addColumn("public_ipv6", ColumnType.STRING,
                column -> column.maxLength(45).nullable().ifNotExists());
        });
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("port_allocations", table -> table.dropColumn("allocation_mode"));
        schema.alterTable("servers", table -> {
            table.dropColumn("public_ipv6");
            table.dropColumn("public_ipv4");
        });
    }
}
