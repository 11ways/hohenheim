package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

import java.util.List;

/**
 * Indexes the dyndns token digest column, which an AUTHENTICATION path looks up on
 * every router poll.
 *
 * {@code DynamicDnsService.authenticate} finds the record by digest; without an index
 * that is a full scan of dns_records on every /nic/update, and routers poll constantly.
 * The comment there claimed an "indexed lookup" that did not exist.
 *
 * Deliberately NOT unique: hohenheim is live, and refusing to boot because two records
 * were handed the same token (an operator can paste one into several records) would be a
 * worse failure than the ambiguity. The lookup takes the first match and then re-checks
 * it with a constant-time comparison, so a shared token is resolvable, not a hole.
 *
 * @author Jelle De Loecker
 */
public class M046_DyndnsTokenIndex extends HohenheimMigration {

    /** ONE name for the index, so up() and down() can never drift apart. */
    private static final String DYNDNS_TOKEN_INDEX = "dns_records_dyndns_token_index";

    /** Explicitly named rather than derived: a derived name can exceed the 63-char limit. */
    private static final String DYNDNS_TOKEN_COLUMN = "dyndns_token";

    public M046_DyndnsTokenIndex() {
        super("2026_07_30_000046", "Index the dyndns token digest lookup");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.alterTable("dns_records", table ->
            table.addIndex(DYNDNS_TOKEN_INDEX, List.of(DYNDNS_TOKEN_COLUMN)));
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("dns_records", table -> table.dropIndex(DYNDNS_TOKEN_INDEX));
    }
}
