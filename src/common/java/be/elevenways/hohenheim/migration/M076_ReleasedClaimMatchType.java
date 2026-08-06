package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

/**
 * Remember WHAT KIND of hostname a released route claim was, so the quarantine can judge a
 * released regex against a later concrete claim.
 *
 * AIDEV-NOTE: no backfill. The claim key omits match type by design, and a hostname that
 * looks like a pattern is not evidence that its row was one -- guessing would invent
 * refusals. Pre-M076 rows stay null and are judged on the hostname alone, which is exactly
 * what they were judged on before.
 */
public class M076_ReleasedClaimMatchType extends HohenheimMigration {

    public M076_ReleasedClaimMatchType() {
        super("2026_08_21_100000", "Released claim match type");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.alterTable("released_route_claims", table -> table.addColumn("match_type",
            ColumnType.STRING, column -> column.maxLength(16).nullable(true).ifNotExists()));
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("released_route_claims", table -> table.dropColumn("match_type"));
    }
}
