package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * A managed database's credential generation and, on each attachment, the generation its workload was last deployed
 * with: a password change a running workload does not have yet is the difference between the two.
 *
 * AIDEV-NOTE: both columns are nullable and read as 0, so every row a live install holds reads "never rotated,
 * deployed with it"; no data step. That is a one-time false negative for a shared database whose password was changed
 * by a controller from before this migration (that change bumped no generation): a plain workload attached to it that
 * still runs the old password shows no "password not live" notice until its next restart, which then gives it the
 * stored one. down() drops exactly what up() added.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class M003_CredentialGenerations extends HohenheimMigration {

    public M003_CredentialGenerations() {
        super("2026_10_24_000001", "credential_generations");
    }

    @Override
    public void up(@NonNull MigrationBuilder builder) {
        builder.alterTable("managed_databases", table ->
            table.addColumn("credential_generation", ColumnType.INTEGER, column -> column.nullable(true)));
        builder.alterTable("instance_databases", table ->
            table.addColumn("deployed_generation", ColumnType.INTEGER, column -> column.nullable(true)));
    }

    @Override
    public void down(@NonNull MigrationBuilder builder) {
        builder.alterTable("instance_databases", table -> table.dropColumn("deployed_generation"));
        builder.alterTable("managed_databases", table -> table.dropColumn("credential_generation"));
    }
}
