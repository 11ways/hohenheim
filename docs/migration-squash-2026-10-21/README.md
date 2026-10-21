# Migration squash, 2026-10-21

Hohenheim's own migration stream (`be.elevenways.hohenheim`), `InitialMigration`
(001) plus `M002`..`M019` (there never was an `M005`), was squashed into ONE
migration, `be.elevenways.hohenheim.migration.M001_HohenheimBaseline` (version
`2026_10_21_000001`, name `hohenheim_baseline`: the workspace's squash-baseline
convention, the squash date plus `_000001`). Zenit core and the `zenit-*` module
streams are untouched. The baseline is irreversible and declares no cross-stream
dependency (none of its tables references another stream's table).

The version sorts after every recorded `001`..`019` (text order, `"0" < "2"`);
a migration appended to this stream takes a later dated version
(`2026_10_22_...` onwards), never `021`, which would sort BEFORE the baseline.

## Method

The 2026-08-13 method (`../migration-consolidation-2026-08-13.md`), with a
throwaway tool that was not committed:

1. The current chain ran on a fresh SQLite file through the ordinary
   `MigrationRunner` auto-discovery (zenit core and every module stream
   included, so the dump is the real migrated schema) and the schema was dumped
   canonically: every table, every column with type / notnull / default / pk,
   every index with uniqueness, origin, partial flag and columns, every foreign
   key with its actions, views and triggers, sorted. That is `schema-before.txt`.
2. The baseline was GENERATED: every Hohenheim `up()` (001..019) was replayed
   into a `MigrationBuilder` and the operation stream (create table, add / change
   / drop / rename column, add / drop index) was folded into a final schema
   model, which emitted the `MigrationBuilder` calls. Folding operations keeps
   the declared `ColumnType`, lengths, defaults, foreign-key actions and index
   facets (`access_rules_tree_index` keeps `overUpdatedColumns`). The chain held
   no raw `execute(...)` SQL at all, so nothing was invisible to the fold.
3. With the chain deleted, the baseline alone ran and was dumped the same way:
   `schema-after.txt`. As a second check the fold of the baseline was compared
   with the fold of the chain (every `ColumnDefinition` facet, including those
   SQLite does not show); that diff was empty too.

## Result

`diff schema-before.txt schema-after.txt` is EMPTY (both 1743 lines).

## Dropped data steps

All of them vanish from a fresh-install baseline: a fresh install has no rows
for them to rewrite, and every live install (kuifje, robbedoes, starfleet,
daystrom) already applied 001..019, so already ran them.

- M011: `normalizeSqliteInstants` over the legacy instant scopes; hash every
  plaintext Basic auth password (with it went `BasicPasswordHashing`, the
  server half of M011's hashing seam); trust tenant-owned site upstreams;
  canonicalize access-rule networks; rename stored instance schedule steps;
  stamp certificate requester and posture acknowledger kinds; respell legacy
  host wildcards; restart claim fences; mark stored targets operator-set; start
  game-domain and volume lock versions at 0; densify access-rule positions;
  restart stored workloads on crash; keep stored git sources manual and
  unpolled; grant the system tier's holders every settings leaf.
- M012: mark never-probed hosts' volume storage not checked.
- M014: publish stack service ports as published ports.
- M015: the account / permission-group rekey (checks, adoptions of deleted ids,
  rekeys of id columns, owner tokens, bucket stamps and history). The swap
  columns it added and renamed fold into plain `UUID` columns.
- M016: move private keys out of the plain certificate text.
- M017: store workload stoppage / last start and certificate renewal failure
  from activity.
- M018: convert Hohenheim's plain row verbs in history.
- M019: no `up()` step at all (its `down()` was the data step).

## Existing installs

Installs that recorded 001..019 ADOPT the baseline without running it, through
zenit core's baseline mechanism: the baseline declares `replaces(...)` with the
complete old chain, so the next migrate records the baseline and deletes the old
rows in one transaction; a database holding only part of the chain is refused
with nothing written. `MigrationIntegrityTest` pins adoption and refusal.

## Backends

SQLite only. The other dialects are not applicable: Hohenheim boots SQLite only
(`SqliteOnlyDatabaseGuard`), so no other backend ever runs this baseline.

## Standing proof

These two dumps are a one-time proof. The standing schema proof is
`HohenheimBaselineSnapshotTest`: zenit core's `SchemaSnapshot` (testSupport)
reads the baseline's schema and asserts it equal to the old chain's schema as
`SchemaSnapshot` rendered it from a fresh database the old chain migrated
(hohenheim 718bedc3), pinned in `src/browserTest/resources/hohenheim-chain-019.schema`
and kept to the baseline's 59 tables. That rendering carries every fact of these
dumps except the foreign-key `match` clause (`NONE` everywhere here); it adds
each column's JDBC size and decimal digits.
