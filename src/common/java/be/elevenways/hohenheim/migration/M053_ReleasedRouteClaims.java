package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.Migration;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

/**
 * The released-claim ledger ({@code released_route_claims}): what a route claim's former
 * owner was, so a hostname another tenant just gave up cannot be seized inside the
 * quarantine window (subdomain takeover through a still-dangling CNAME).
 *
 * AIDEV-NOTE: the claim key is INDEXED but deliberately NOT unique -- the check is an
 * indexed lookup per write, and the same hostname may legitimately be released more than
 * once over its life. There is no backfill: a claim released BEFORE this migration left no
 * evidence anywhere, so inventing ledger rows would be inventing owners.
 */
public class M053_ReleasedRouteClaims extends Migration {

    public M053_ReleasedRouteClaims() {
        super("2026_08_03_000153", "Released route claim quarantine ledger");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.createTable("released_route_claims", table -> {
            table.id();
            table.string("claim_key", 128);
            table.string("hostname", 255);
            table.addColumn("former_site_id", ColumnType.INTEGER, col -> col.nullable(true));
            table.text("former_subjects");
            table.datetime("released_at");
            table.addIndex("claim_key");
        });
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.dropTable("released_route_claims");
    }
}
