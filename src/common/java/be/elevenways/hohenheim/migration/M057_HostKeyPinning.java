package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

/**
 * The pin M056 left a hole for: the host's PUBLIC key we verify every SSH
 * connection against, the operator's out-of-band confirmation of it, the key a
 * changed host most recently OFFERED (evidence for the re-pin ceremony, never a
 * trusted value), and the per-host client identity that replaces the controller
 * account's ambient default key.
 *
 * AIDEV-NOTE: {@code identity_private_key} is declared {@code .encrypted()} on the
 * model. It is created empty here and only ever written through
 * {@code HostKeys.rotateIdentity}, so no plaintext generation of it exists to heal
 * -- declaring encryption over an already-populated column would make every read
 * throw from inside row hydration.
 */
public class M057_HostKeyPinning extends HohenheimMigration {

    public M057_HostKeyPinning() {
        super("2026_08_03_160000", "SSH host-key pin and per-host client identity");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.alterTable("servers", table -> {
            table.addColumn("host_key", ColumnType.TEXT, col -> col.nullable(true).ifNotExists());
            table.addColumn("host_key_verified", ColumnType.BOOLEAN,
                col -> col.defaultValue(false).ifNotExists());
            table.addColumn("host_key_pinned_at", ColumnType.DATETIME,
                col -> col.nullable(true).ifNotExists());
            table.addColumn("host_key_offered", ColumnType.TEXT, col -> col.nullable(true).ifNotExists());
            table.addColumn("identity_public_key", ColumnType.TEXT, col -> col.nullable(true).ifNotExists());
            table.addColumn("identity_private_key", ColumnType.TEXT, col -> col.nullable(true).ifNotExists());
        });
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("servers", table -> {
            table.dropColumn("identity_private_key");
            table.dropColumn("identity_public_key");
            table.dropColumn("host_key_offered");
            table.dropColumn("host_key_pinned_at");
            table.dropColumn("host_key_verified");
            table.dropColumn("host_key");
        });
    }
}
