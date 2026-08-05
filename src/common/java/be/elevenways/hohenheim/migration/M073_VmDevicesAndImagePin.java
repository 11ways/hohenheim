package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

import java.util.List;

/**
 * Phase 8 slice 1: attachable instance devices (disk/NIC rows under the reservation
 * ledger), the per-owner disk/NIC quota override columns, and the pinned resolved
 * image fingerprint on instances (mutable aliases are not deployment identities).
 */
public class M073_VmDevicesAndImagePin extends HohenheimMigration {

    public M073_VmDevicesAndImagePin() {
        super("2026_08_13_100000", "VM devices and image pin");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.createTable("instance_devices", table -> {
            table.id();
            table.foreignId("instance_id", "instances");
            // "disk" or "nic"; the vocabulary lives on InstanceDeviceModel.
            table.string("type", 20);
            // The daemon device name; unique per instance so one name answers once.
            table.string("name", 63);
            table.addColumn("size_gb", ColumnType.INTEGER, column -> column.nullable(true));
            // The ledger bucket this row's reservation was charged to (stamped by the
            // reserve hook, consulted by the release -- the instances.quota_bucket shape).
            table.addColumn("quota_bucket", ColumnType.STRING,
                column -> column.maxLength(191).nullable(true));
            table.timestamps();
            table.unique("instance_devices_name_unique", List.of("instance_id", "name"));
            table.addIndex("instance_id");
        });

        // The RESOLVED image fingerprint the workload actually runs (volatile.base_image
        // for incus): recorded at deploy, reused when an absent workload is recreated,
        // cleared when the declared image changes.
        schema.alterTable("instances", table -> table.addColumn("image_fingerprint",
            ColumnType.STRING, column -> column.maxLength(191).nullable(true).ifNotExists()));

        // Per-owner overrides for the two new quota dimensions (the max_instances shape:
        // null = no override, 0 = nothing allowed).
        schema.alterTable("instance_quotas", table -> table.addColumn("max_disk_gb",
            ColumnType.INTEGER, column -> column.nullable(true).ifNotExists()));
        schema.alterTable("instance_quotas", table -> table.addColumn("max_nics",
            ColumnType.INTEGER, column -> column.nullable(true).ifNotExists()));
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("instance_quotas", table -> table.dropColumn("max_nics"));
        schema.alterTable("instance_quotas", table -> table.dropColumn("max_disk_gb"));
        schema.alterTable("instances", table -> table.dropColumn("image_fingerprint"));
        schema.dropTable("instance_devices");
    }
}
