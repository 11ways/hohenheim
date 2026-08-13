# Migration consolidation, 2026-08-13

The M003..M092 chain (90 migrations across `src/common/.../migration/` and
`src/server/.../server/migration/`) was folded into ONE migration,
`be.elevenways.hohenheim.migration.InitialMigration`, which creates the final
schema directly. Hohenheim has no installations, so backwards compatibility is
not a concern and every backfill/heal in the old chain became nothing.

From now on a schema change EDITS `InitialMigration` in place. It never gets a
sibling. `database.migration_integrity` is `fail`, so a dev database that
predates the edit refuses to boot -- drop and recreate it, never downgrade the
setting.

## Method of proof

1. The old chain was run against a fresh SQLite file through the ordinary
   `MigrationRunner` auto-discovery, and the resulting schema was dumped in a
   canonical, order-independent form (every table, every column with its type /
   notnull / default / pk flag, every index with its uniqueness and columns,
   every foreign key with its actions), sorted so no listing order can hide or
   invent a difference. That dump is `schema-before.txt` below.
2. The consolidated migration was GENERATED, not hand-written: a temporary tool
   replayed every migration's `up()` into a `MigrationBuilder` and folded the
   resulting operation stream (create/drop table, add/drop column, add/drop
   index) into a final schema model, then emitted the `MigrationBuilder` calls
   for it. Folding the OPERATIONS rather than reading the SQLite file back keeps
   the declared `ColumnType`, max lengths, defaults and foreign-key actions
   exactly as the chain declared them, instead of guessing them back out of
   SQLite's weaker type system.
3. One thing the fold could not see: `M026_MigrateAuditLogToActivity` dropped
   `audit_log` through `execute("DROP TABLE audit_log")`, and raw SQL carries no
   inspectable structure. The first diff caught it (the fold had kept the
   table); `audit_log` was removed from the consolidated migration. It is the
   ONLY raw-SQL DDL the old chain contained -- every other `execute()` in it was
   an UPDATE or an INSERT.
4. The consolidated migration was then run the same way and dumped the same
   way (`schema-after.txt`), and the two dumps were diffed.

## Result

The diff is exactly the two schema-level fixes that were folded in
DELIBERATELY (pending review findings, see the AIDEV-NOTEs at those two
`createTable` calls). Nothing else differs -- not a column, not a type, not a
default, not a nullability flag, not a foreign key, not an index.

```diff
@@ -137,6 +137,7 @@
   COLUMN settings type=TEXT notnull=0 default=null pk=0
   COLUMN updated_at type=TIMESTAMP notnull=0 default=null pk=0
   INDEX <auto> unique=1 origin=u columns=[id]
+  INDEX backup_targets_name_unique unique=1 origin=c columns=[name]
 TABLE bans
   COLUMN active type=INTEGER notnull=0 default=true pk=0
   COLUMN created_at type=TIMESTAMP notnull=0 default=null pk=0
@@ -315,7 +316,7 @@
   COLUMN token_digest type=VARCHAR(96) notnull=0 default=null pk=0
   COLUMN updated_at type=TIMESTAMP notnull=0 default=null pk=0
   INDEX <auto> unique=1 origin=u columns=[id]
-  INDEX dns_dyndns_credentials_record_id_index unique=0 origin=c columns=[record_id]
+  INDEX dns_dyndns_credentials_record_id_unique unique=1 origin=c columns=[record_id]
   INDEX dns_dyndns_credentials_token_digest_index unique=0 origin=c columns=[token_digest]
 TABLE dns_peers
   COLUMN api_key type=VARCHAR(200) notnull=0 default=null pk=0
```

- `backup_targets.name` gained a UNIQUE index: every backup row and schedule
  refers to a target by NAME, so two targets sharing one made every reference
  ambiguous (review finding against `M058_SnapshotsAndBackups` /
  `BackupTargetModel`).
- `dns_dyndns_credentials.record_id` was upgraded from a plain index to a UNIQUE
  one: the resolver takes the first row it finds for a record, so a second
  credential silently decided which token authenticates (review finding against
  `M091_TypedDnsRecordData`).

Both are pinned by `MigrationIntegrityTest.theSchemaRefusesTheDuplicatesTheCodeAssumesCannotExist`.

## What else moved

- `M041`'s raw `INSERT` of the Spamservice singleton stub became
  `SpamserviceInstallationSeeder` (`SeedContext.ensure`, SEED boot stage) --
  default records are the Seeder's job, never a migration's. Consequence: the
  stub now appears at BOOT, not at migrate time, so a test that only migrates
  has to create it itself.
- `SpamserviceInstallationModel.SYSTEM_USER_ID` lost its unconditional
  `.required()`: the requirement is conditional (only an ENABLED installation
  needs a system user, enforced in `SpamserviceManager`'s installation store),
  and declaring it unconditionally made the stub -- which by definition has no
  user yet -- unrepresentable through the validating save path.
- `RouteClaims.backfill` and its era-frozen `LegacySite` shape were deleted:
  their only callers were `M045`/`M079`.
- `HohenheimDatabase.RETIRED_MIGRATION_VERSIONS` and
  `acknowledgeRetiredVersions` were deleted. They acknowledged history rows of
  three migrations retired in the zenit-auth cutover; with the whole chain gone
  no database that could carry those rows can boot this schema anyway.
- `src/browserTest/resources/migration-checksums.txt` (the golden checksum
  ledger) was deleted along with the test that pinned it. It guarded against
  editing an ALREADY-SHIPPED migration, which is now the sanctioned workflow.
- The SQLite-only boot guard STAYS, with a different justification: it was
  argued from raw SQL inside the chain, and is now argued from what actually
  outlives that -- `RouteClaims` refuses OVERLAPPING route claims inside one
  serialized write transaction, and no unique index can express that overlap.
- `ControllerIdentity.token()` now calls `Blast.ensureAutoLoaded()` explicitly.
  It used to work by ACCIDENT: `HohenheimDatabase.init` ran a chain whose members
  called `Blast.slog`, and loading `Blast` is what ran the generated
  `BlastAutoLoadInit` that registers every model singleton. One Blast-free
  migration turned the next line into "No Model instance registered for
  ControllerIdentityModel" on a cold JVM (`RoleRestrictedBootTest`, in the solo
  lane only). A latent fragility the consolidation exposed, not created.

## Test surface

- Rewritten: `MigrationIntegrityTest` (fresh install + re-migrate + strict
  integrity, the uniqueness the code assumes, a full down()/up() round trip, and
  a standing "exactly ONE hohenheim migration" invariant).
- Trimmed: `EncryptedSecretsAtRestTest` lost its M047 heal journey and keeps the
  at-rest/round-trip contract; `DyndnsTokenIndexTest` lost its M046-index step
  and keeps the index-and-planner steps; `SqliteOnlyDatabaseGuardTest` now
  asserts the guard's real justification.
- Rewritten: `SpamserviceInstallationModelTest.theSeederCreatesTheDisabledFixedSingletonExactlyOnce`.
- Deleted: `DnsRecordDataBackfillTest`, `RecordScheduleBackfillTest`,
  `migration-checksums.txt`.
