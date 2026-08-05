package be.elevenways.hohenheim.server.migration;

import be.elevenways.hohenheim.server.proxy.RouteClaims;
import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.hohenheim.migration.HohenheimMigration;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

import java.util.List;

/**
 * Backs route ownership with a real constraint: the CMS route checks read the site and
 * domain tables and only then write, so two tenants enabling staged sites on one hostname
 * in the same instant both pass and both commit -- the dispatcher then awards the route
 * first-wins and tells neither tenant.
 *
 * The constrained value is a DERIVED column ({@code live_route_key}) rather than the raw
 * route columns, because ownership is conditional on state that lives in another table:
 * a route is only claimed while its site is enabled AND not soft-deleted. NULL means "no
 * claim", which keeps staged duplicates on disabled sites legal (the clone/draft
 * workflow), makes a soft-deleted site own nothing, and rides NULLs-are-distinct unique
 * index semantics -- SQLite, hohenheim's only backend, plus PostgreSQL, MySQL and
 * Firebird. A raw multi-column unique index cannot express any of that: it would refuse
 * the legal staged duplicate and would still count deleted sites as owners.
 *
 * Self-healing like M043: an install carrying an already-contested route keeps the
 * LOWEST-id claimant and leaves the losers unclaimed instead of failing to boot. The
 * losers were already unreachable (the dispatcher resolves first-wins), so nothing an
 * operator can observe changes; their next edit is refused with the real conflict.
 *
 * AIDEV-NOTE: lives in the server source set (the zenit-auth/zenit-ai/zenit-comms shape)
 * because the claim key must be spelled by the ONE authority the dispatcher uses --
 * RouteClaims, which reaches SiteDispatcher and ListenerAddressMatcher. A second
 * key-derivation in common would be free to drift from the routing definition, which is
 * the exact failure mode the whole route-identity design exists to prevent. The heal is a
 * portable {@code schema.data} step, not raw SQL, so the migration keeps every backend.
 *
 * @author Jelle De Loecker
 */
public class M045_SiteDomainRouteClaims extends HohenheimMigration {

    /** ONE name for the unique index, so up() and down() can never drift apart. */
    private static final String LIVE_ROUTE_KEY_INDEX = "site_domains_live_route_key_unique";

    /** The claim column; declared here too so the create and the drop share one spelling. */
    private static final String LIVE_ROUTE_KEY_COLUMN = "live_route_key";

    public M045_SiteDomainRouteClaims() {
        super("2026_07_30_000045", "Unique live-route claims for site domains");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.alterTable("site_domains", table -> table.addColumn(LIVE_ROUTE_KEY_COLUMN,
            ColumnType.STRING, column -> column.maxLength(1024).nullable(true).ifNotExists()));
        schema.data("Claim the live route of every enabled, non-deleted site's domains", "1",
            RouteClaims::backfill);
        schema.alterTable("site_domains", table ->
            table.unique(LIVE_ROUTE_KEY_INDEX, List.of(LIVE_ROUTE_KEY_COLUMN)));
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("site_domains", table -> table.dropIndex(LIVE_ROUTE_KEY_INDEX));
        schema.alterTable("site_domains", table -> table.dropColumn(LIVE_ROUTE_KEY_COLUMN));
    }
}
