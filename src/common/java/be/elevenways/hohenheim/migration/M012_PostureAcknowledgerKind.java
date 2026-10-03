package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;
import be.elevenways.zenit.common.orm.migration.PrincipalColumns;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * A host's posture acknowledger stored as its principal reference: {@code acknowledged_by_kind} beside the id.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public class M012_PostureAcknowledgerKind extends HohenheimMigration {

    public M012_PostureAcknowledgerKind() {
        super("012", "Posture acknowledger kind");
    }

    @Override
    public void up(@NonNull MigrationBuilder schema) {
        schema.alterTable("servers", table -> PrincipalColumns.addKindColumn(table, "acknowledged_by_kind"));
        // Every decimal acknowledger stored before kinds existed was an account's; any other token stays kindless.
        schema.data("store every posture acknowledger with its kind", "1", PrincipalColumns.stampAccountKinds(
            "servers", () -> IntegerField.builder().name("id").build(),
            () -> StringField.builder().name("acknowledged_by").build(), "acknowledged_by_kind", true));
    }

    @Override
    public void down(@NonNull MigrationBuilder schema) {
        schema.alterTable("servers", table -> table.dropColumn("acknowledged_by_kind"));
    }
}
