package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

/**
 * Backs the stack editor's uniqueness checks with real constraints: the app-level
 * read-then-write checks lose a concurrent-submit race, and a duplicate service name
 * means two containers claiming one name and DNS alias.
 *
 * Self-healing: pre-existing duplicates are renamed out of the way before the index
 * is created, so an install carrying them upgrades instead of failing to boot.
 */
public class M043_StackUniqueKeys extends HohenheimMigration {

    // AIDEV-NOTE: This migration used to open with schema.assertUnique(...), which threw a
    // DataSourceException on the first duplicate, became a failed MigrationResult and died in
    // HohenheimDatabase's requireSuccess(). That is an AVAILABILITY bug, not hygiene: the
    // operator was told to "renumber the conflicting history" from a control plane that no
    // longer boots. Healing first is the only outcome an operator can actually act on.
    //
    // The rename suffix is the row's own primary key, which is unique per table, so two losers
    // can never collide with each other. The one residual collision is a row that ALREADY
    // carries the exact healed name (a service literally called "web__dup17" beside a duplicated
    // "web" whose losing row is id 17). What happens then depends on id order, because SQLite
    // evaluates the EXISTS against the table's in-statement state as rows are visited by rowid:
    // if the pre-named row's id is HIGHER than the loser's, the loser's rename makes the
    // pre-named row match the predicate later in the same statement and it is SILENTLY renamed
    // too ("web__dup17" -> "web__dup17__dup20") -- a mutation of a non-duplicate row, not an
    // error; only when the pre-named row's id is LOWER do both rows end up as "web__dup17" and
    // the unique-index creation fail loudly. Accepted as-is: a collision-proof suffix would
    // change this raw SQL and move the recorded checksum of an already-applied migration
    // (manufactured drift finding) for an astronomically unlikely case that is non-lossy either
    // way. assertUnique was removed rather than kept as a second gate: after the heal it can
    // only fire on that residual case, where it would brick boot again with worse advice.
    //
    // Raw SQL because MigrationBuilder has no data-transform operation and no Java hook; `||`
    // concatenation is SQLite/Postgres syntax, and hohenheim is SQLite-only (see M025).

    public M043_StackUniqueKeys() {
        super("2026_07_27_000043", "Unique keys for stack services and files");
    }

    @Override
    public void up(MigrationBuilder schema) {
        // The lowest id of each duplicate group keeps its name, so every loser always still
        // sees a same-named earlier row: the predicate stays true however the rows are visited.
        schema.execute("""
            UPDATE stack_services SET name = name || '__dup' || id
            WHERE EXISTS (SELECT 1 FROM stack_services o
                          WHERE o.stack_id = stack_services.stack_id
                            AND o.name = stack_services.name
                            AND o.id < stack_services.id)""");
        schema.alterTable("stack_services", table -> table.unique("stack_id", "name"));

        schema.execute("""
            UPDATE stack_files SET container_path = container_path || '__dup' || id
            WHERE EXISTS (SELECT 1 FROM stack_files o
                          WHERE o.stack_service_id = stack_files.stack_service_id
                            AND o.container_path = stack_files.container_path
                            AND o.id < stack_files.id)""");
        schema.alterTable("stack_files", table -> table.unique("stack_service_id", "container_path"));
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("stack_files", table ->
            table.dropIndex("stack_files_stack_service_id_container_path_unique"));
        schema.alterTable("stack_services", table ->
            table.dropIndex("stack_services_stack_id_name_unique"));
    }
}
