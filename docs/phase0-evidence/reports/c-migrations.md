All seven issues implemented, proven with observed pre-fix failures, and committed per repo (nothing pushed).

> SUPERSEDED (2026-08-13): the migration chain this report audits no longer exists.
> M003..M092 were folded into one `InitialMigration`; every class name, the golden
> checksum file and `RETIRED_MIGRATION_VERSIONS` are history. Kept as the dated
> evidence it is. See `docs/migration-consolidation-2026-08-13.md`.

---

```text
Issue: C1 — Applied original M007 never runs the new orphan purge
User-facing behavior changed: An install that already recorded M007 (starfleet) now
  executes the orphan-grant purge on its next boot instead of carrying dangling grants
  forever. A reused record id can no longer inherit a dead record's authority on an
  upgraded install; fresh and upgraded installs converge on the same DATA invariant.
Architectural responsibility changed: Data remediation is no longer owned by an
  already-applied migration version. M007 is schema-only; the purge is M009
  (2026_07_31_000001), a version every upgraded install still has to run.
  OrphanRecordGrantPruneTask stays defence in depth, not the upgrade mechanism.
  M007 now declares TWO superseded digests (original + first revision), documented.
Files changed:
  /home/skerit/projects/javaweb/zenit-auth/src/server/java/be/elevenways/zenit/auth/server/migration/M009_PurgeOrphanRecordGrants.java (new)
  /home/skerit/projects/javaweb/zenit-auth/src/server/java/be/elevenways/zenit/auth/server/migration/M007_HardenGrantSchemas.java
Tests added or changed:
  StarfleetUpgradeTest.aLiveInstallThatAppliedTheOriginalM007BootsCleanAndConverges —
    now plants a real orphan grant + a live-record grant before simulating the original
    M007, and asserts step 5a. simulateOriginalM007 unchanged; fixture tables are
    acknowledged via the new RecordCapabilityFixtures.TABLES_VERSION.
  StarfleetUpgradeTest.reconstructionProcedureMatchesTheRealChecksumImplementation —
    asserts the superseded set contains the original digest and has exactly 2 entries.
  RecordGrantOrphanPurgeTest.theUpgradeChainPurgesOrphansThatWereAlreadyInTheDatabase
    (renamed from m007Purges...) — asserts M007 alone touches NO grant data, M009 purges.
Observed pre-fix failure (purge put back inside M007, M009 emptied):
  step 5a: the orphan grant must have been purged by the upgrade ==> expected: <0> but was: <1>
Verification command and result:
  zenit-dev test --unit --no-fail-fast (zenit-auth) -> 146 unit passed
Backend/browser coverage: SQLite (in-memory), the only backend the affected live install
  uses. The purge itself is backend-agnostic (ORM data step, no raw SQL).
Known limitations or decisions: M009.down() is a deliberate no-op — a purge of grants
  whose targets no longer exist has nothing to restore. An install that applied the
  FIRST revision already ran the purge; M009 re-runs it, which is safe because the sweep
  is idempotent by construction.
Commit and push state: zenit-auth 128a60a, not pushed.
```

```text
Issue: C2 — Interrupted original MySQL M007 cannot recover
User-facing behavior changed: A MySQL install left holding the committed prefix of the
  aborted original M007 now migrates to the converged schema instead of dying on
  "Duplicate column name" before M008 ever runs.
Architectural responsibility changed: None. Both addColumn calls declare .ifNotExists(),
  which ColumnDefinition already models and MigrationChecksum already treats as
  NON-structural (MigrationChecksum.java columnSignature) — a checksum-safe remedy. No
  DDL error suppression, no checksum weakening.
Files changed:
  zenit-auth/.../migration/M007_HardenGrantSchemas.java
Tests added or changed:
  GrantMigrationChainMySqlTest.everyCommittedPrefixOfTheInterruptedOriginalRecovers —
    models the original operation order (ADD granted_by, ADD expires_at, then the
    65-char CREATE INDEX that raised 1059), so the reachable committed prefixes are
    {}, {granted_by}, {granted_by, expires_at}. Each prefix is replayed against a wiped
    real MySQL schema, then the full discovered chain must succeed and land the columns
    exactly once plus the reconciled check-path index.
  GrantMigrationChainMySqlTest gained resetDatabase()/discoveredWithout() helpers; the
    existing whole-chain test now resets first.
Observed pre-fix failure (.ifNotExists() removed):
  Migration 2026_07_29_000001 failed: Failed to apply migration operations: Failed to
  execute SQL: ALTER TABLE `auth_record_grants` ADD COLUMN `granted_by` VARCHAR(128)
  (1 failed, 0 applied; later migrations were not run)
  Caused by: java.sql.SQLSyntaxErrorException
Verification command and result:
  zenit-dev test --unit --class GrantMigrationChainMySqlTest -> 2 passed (24.5s + 8.5s)
Backend/browser coverage: REAL MySQL 8.0 (Testcontainers), the only backend where the
  original could commit a partial prefix (non-transactional DDL + error 1059).
Known limitations or decisions: A prefix that includes the index is unreachable on MySQL
  — that statement is the one that failed — and is therefore not tested. G4 preserved:
  MySQL still uses plain INSERT + typed duplicate handling; no ON DUPLICATE KEY UPDATE.
Commit and push state: zenit-auth 128a60a, not pushed.
```

```text
Issue: C8 — Helper index renames changed checksums of shipped migrations
User-facing behavior changed: Every install that applied a consumer migration built on
  createTranslationsTableFor / createSchemaTableFor before the 2026-07-30 rename boots
  again in strict integrity mode instead of reporting "modified after being applied", and
  its physical index converges on the current name.
Architectural responsibility changed: The equivalence is declared ONCE by the framework
  that renamed the identifier, not by N consumer repos pinning digests. MigrationBuilder
  records the pre-rename derived name on the AddIndexOperation
  (AddIndexOperation.superseding); MigrationChecksum's single legacy-ifNotExists digest
  generalised into a SET of historical digests recomputed from the migration's OWN
  current operations, so an author's real edit still moves every variant. Consumer repos
  own only the PHYSICAL reconcile.
  DELIBERATE DEVIATION from the ledger's "declare exact superseded checksums per
  migration": that spelling requires each of 4+ repos to reconstruct and pin a digest,
  cannot cover consumers outside this workspace, and would leave migrations written
  AFTER the rename carrying bogus supersessions. Evidence it is exact: the historical
  digest is derived from the same operation list, and MigrationIntegrityTest step 4
  proves an edited migration is still refused.
Inventory of affected shipped migrations (verified at HEAD):
  zenit-microcopy M002_FiltersChildTable:29 — reconciled (M003).
  zenit-media AddMediaAltMigration:22 — reconciled (ReconcileMediaTranslationsIndexMigration).
  spamservice M001_CreateSpamserviceTables:81-82 — reconciled (M004, both child tables).
  quirkyquarters InitialMigration:78,241,316,343,559 — NO change. quirkyquarters/CLAUDE.md
    line 72: "QQ has NO deployed installations: InitialMigration IS the schema". With no
    install, no recorded checksum and no physical index to converge exist; the framework
    mechanism covers it if one ever appears.
  zenit-pages CreatePagesMigration:18 — VERIFIED false positive. It spells its DDL out
    explicitly and mentions the helper only in an AIDEV-NOTE. Unaffected.
Files changed:
  zenit: common/orm/migration/MigrationBuilder.java (supersedeDerivedIndexName),
    operation/AddIndexOperation.java, TableBuilder.java (addIndexIfNotExists,
    uniqueIfNotExists), server/orm/migration/MigrationChecksum.java (historical digest
    set + public canonicalText for diagnostics),
    server/orm/DuckDbMigrationOperationVisitor.java (native CREATE INDEX IF NOT EXISTS),
    CLAUDE.md, docs/skills/zenit-framework/SKILL.md
  zenit-media: ReconcileMediaTranslationsIndexMigration.java (2026_07_31_000002)
  zenit-microcopy: M003_ReconcileFiltersOrderIndex.java (2026_07_31_000003)
  spamservice: M004_ReconcileSampleChildIndexes.java (2026_07_31_000004)
Tests added or changed:
  zenit MigrationIntegrityTest.derivedIndexRenameChecksumJourney (5 steps: superseded
    name recorded, pre-rename digest accepted, unrelated digest still refused, EDITED
    migration still refused, end-to-end fail-mode boot + history repair).
  zenit-media MediaTranslationsIndexReconcileTest, zenit-microcopy
    FiltersOrderIndexReconcileTest, spamservice SampleChildIndexReconcileTest — each
    reproduces a pre-rename install, runs the full chain, asserts the legacy name is gone
    and a fresh install is a no-op rather than a duplicate-name failure.
Observed pre-fix failures:
  (supersedeDerivedIndexName calls removed)
  step 1: the pre-rename derivation must be recorded as superseded ==>
    expected: <rename_probes_translations_rename_probe_id_locale_unique> but was: <null>
  (zenit-media reconcile up() emptied)
  step 2: the legacy index name must be gone ==> expected: <0> but was: <1>
Verification command and result:
  zenit: 140 targeted unit passed; full suite 2023/2024 (one unrelated flake, below)
  zenit-media 50 unit passed; zenit-microcopy 131 unit passed; spamservice 128 unit passed
Backend/browser coverage: strict integrity + digest acceptance on SQLite; consumer
  reconciles on SQLite; the underlying guarded drop proven on SQLite AND PostgreSQL (C9).
Known limitations or decisions: migration versions are GLOBAL across modules — the
  runner caught a collision between zenit-auth M009 and the zenit-media reconcile
  (both 2026_07_31_000001); zenit-media moved to ...000002.
Commit and push state: zenit 1d18d89, zenit-media ecde56d, zenit-microcopy 3ab242c,
  spamservice 2f43b7c. Not pushed.
```

```text
Issue: C9 — M008 leaves PostgreSQL's truncated legacy index
User-facing behavior changed: A guarded index drop now removes the index under the name
  the backend PHYSICALLY stored. On PostgreSQL/CockroachDB, M008's legacy drop used to be
  a silent no-op and the 63-byte-truncated legacy index survived the migration written to
  remove it.
Architectural responsibility changed: Fixed in the framework, not per migration.
  SqlMigrationOperationVisitor.findIndexName resolves the requested name OR its
  MAX_INDEX_NAME_LENGTH-byte truncation from JDBC metadata, and visitDropIndex drops the
  RESOLVED name. indexExists delegates to it. New shared helper
  common/orm/migration/IndexNames (byteLength, truncateToBytes, never splits a code
  point). M008 needs no second hardcoded spelling; its AIDEV-NOTE was updated in place.
Files changed:
  zenit: server/.../sql/SqlMigrationOperationVisitor.java, common/orm/migration/IndexNames.java
  zenit-auth: M008_ReconcileCheckPathIndex.java (AIDEV-NOTE only)
Tests added or changed:
  zenit LegacyIndexReconcileTest (new, parameterized SQLite + PostgreSQL): creates the
  65-char index by raw SQL, asserts PostgreSQL truncated it and SQLite did not, runs a
  reconcile migration, asserts neither spelling survives and the short name exists, then
  reruns for idempotency.
  IndexNameLimitTest.truncationNeverSplitsACodePoint pins the truncation helper.
Observed pre-fix failure (findIndexName matching the exact name only):
  [PostgreSQL] step 3: the legacy index must be gone ==> expected: <0> but was: <1>
  (SQLite passed in the same run — exactly the asymmetry claimed)
Verification command and result:
  zenit-dev test --unit --class LegacyIndexReconcileTest -> 2 passed (SQLite, PostgreSQL)
Backend/browser coverage: REAL PostgreSQL 17 (Testcontainers) and SQLite. DuckDB keeps
  its native DROP INDEX IF EXISTS override (it does not truncate); MySQL never created an
  over-long name (error 1059).
Known limitations or decisions: The truncated spelling is GENERATED from the requested
  name, never guessed from the catalogue, so this cannot drop an unrelated index.
Commit and push state: zenit 1d18d89, zenit-auth 128a60a. Not pushed.
```

```text
Issue: C10 — Index-name guard checks characters, not bytes
User-facing behavior changed: A non-ASCII index name that fits in 63 Java chars but
  exceeds 63 UTF-8 bytes is now refused at build time instead of being silently truncated
  by PostgreSQL into a name nobody wrote down.
Architectural responsibility changed: AddIndexOperation measures IndexNames.byteLength;
  the message reports bytes, and both measurements when they differ. OrmConstants'
  docblock and the guard now agree (it always documented BYTES).
Files changed:
  zenit: common/orm/migration/operation/AddIndexOperation.java,
    common/orm/OrmConstants.java, common/orm/migration/IndexNames.java
Tests added or changed:
  IndexNameLimitTest.indexNamesAreMeasuredInBytesNotCharacters — 40 accented characters
  = 79 bytes is refused; 4-byte code points count as 4 (15 emoji = 60 bytes build, 16 =
  64 do not); ASCII still reports a single measurement so existing verdicts are unchanged.
Observed pre-fix failure (byteLength swapped back to String.length()):
  step 1: a name over the BYTE limit must be refused even when it fits in characters ==>
  Expected java.lang.IllegalArgumentException to be thrown, but nothing was thrown.
Verification command and result:
  zenit-dev test --unit --class IndexNameLimitTest -> 4 passed
Backend/browser coverage: build-time guard, backend-independent by design (that is the
  point of the AIDEV-NOTE it carries). byteLength is computed arithmetically, not via
  String.getBytes, so it stays TeaVM-safe in common code.
Known limitations or decisions: Byte length is never smaller than character length, so
  the byte check subsumes the character-counting backends (Firebird 63, MySQL 64).
Commit and push state: zenit 1d18d89, not pushed.
```

```text
Issue: C11 — Hohenheim M045 is not covered by the checksum golden (+ data-body versioning)
User-facing behavior changed: (a) Editing any migration in
  be.elevenways.hohenheim.server.migration after it shipped now fails the golden test
  instead of surfacing on a live install's integrity check. (b) Rewriting a data step's
  REMEDIATION LOGIC now moves the migration's checksum.
Architectural responsibility changed:
  (a) The golden filter and alterOnlyMigrations() now scope by REPO
      (be.elevenways.hohenheim.), not by one package. Upstream zenit migrations remain
      excluded, which was the documented intent.
  (b) DataOperation carries an author-declared bodyVersion alongside its description;
      MigrationChecksum signs "data:<description>@<bodyVersion>". MigrationBuilder.data
      is now 3-arg with NO 2-arg overload, so every call site is a compile-time forced
      decision. No lambda is serialized. This identity is used by C1's M009 as well.
Files changed:
  zenit: common/orm/migration/MigrationBuilder.java,
    common/orm/migration/operation/DataOperation.java,
    server/orm/migration/MigrationChecksum.java
  hohenheim: server/migration/M045_SiteDomainRouteClaims.java,
    browserTest/.../migration/MigrationIntegrityTest.java,
    browserTest/resources/migration-checksums.txt
  zenit-auth: M007 heal step and M009 purge step declare body version "1"
Tests added or changed:
  hohenheim MigrationIntegrityTest.everyShippedMigrationChecksumStaysPinned now covers
    M045 (golden gained 2026_07_30_000045 M045_SiteDomainRouteClaims 5b91f7e5...).
  zenit MigrationDataStepTest.aDataStepsDeclaredIdentityIsItsChecksumSignature — 4 steps:
    stable, description move, body-version move, and the two are independent signatures.
Observed pre-fix failures:
  (golden filter unchanged) computed content contained
    "2026_07_30_000045 M045_SiteDomainRouteClaims 5b91f7e5..." while the golden jumped
    2026_07_29_000044 M044_SystemUserClaims -> 2026_07_30_000046 M046_DyndnsTokenIndex
  (data signature reverted to description-only)
    step 3: a bumped data-step body version must move the structural checksum ==>
    expected: not equal but was: <1efb44b76f0a3cb659fac7ae068ec707949a1041d5a057e5c8b4b9f4b4094f89>
Verification command and result:
  hohenheim: zenit-dev test --browser --class MigrationIntegrityTest -> 6 browser passed
  zenit: MigrationDataStepTest 2 passed
Backend/browser coverage: hohenheim is SQLite-only; the golden is pure computation.
Known limitations or decisions: No other hohenheim checksum moved (verified against the
  full computed listing), so no supersession was needed there. M045 was never pinned and
  post-dates the last known starfleet deploy (which recorded the 2026-07-29 original
  M007), so no install recorded its pre-change digest; declaring one would be noise.
Commit and push state: zenit 1d18d89, zenit-auth 128a60a, hohenheim d45b344. Not pushed.
```

```text
Issue: C12 — MySQL duplicate-key attribution mistakes index names for columns
User-facing behavior changed: A MySQL UNIQUE refusal on an explicitly named or composite
  index no longer reports the index name as a field. Form layers stop attaching a
  violation to a column that does not exist; the typed DuplicateKeyException and its
  constraint name are unchanged.
Architectural responsibility changed: Attribution is now evidence-based.
  DuplicateKeyException.from takes the written table's known columns; mysqlColumn returns
  the "for key" suffix only when it case-insensitively matches one. No column list (a
  set-based UPDATE, a child table) means no attribution. SqlDatasource.translateSaveError
  carries the column set; new SqlDatasource.columnNamesOf(Model) expands MultiColumnField
  into its physical columns. Backends that name a real COLUMN (PostgreSQL "Key (email)=",
  SQLite, DuckDB, Firebird) are untouched by the gate.
Files changed:
  zenit: common/orm/datasource/DuplicateKeyException.java,
    server/.../sql/SqlDatasource.java, server/orm/MySqlDatasource.java,
    server/orm/SqliteDatasource.java
Tests added or changed:
  DuplicateKeyMessageTest (new, no database): derived single-column index still resolves;
    explicit composite index attributes nothing; no column list attributes nothing;
    PRIMARY attributes nothing; PostgreSQL still names its column and constraint.
  DuplicateKeyTest — the fixture table gained tenant/code plus an explicitly named
    composite unique, and anIndexNameIsNeverReportedAsAColumn asserts the
    backend-independent invariant (a reported column must be a real column) plus the
    MySQL-specific null.
Observed pre-fix failure (mysqlColumn reverted to returning the suffix):
  step 2: an index name that is not a column must attribute no field ==>
  expected: <null> but was: <unique_things_pair_unique>
Verification command and result:
  zenit-dev test --unit --class DuplicateKeyTest,DuplicateKeyMessageTest,InsertIfAbsentTest
  -> 18 + 1 + 32 passed
Backend/browser coverage: DuplicateKeyTest runs on all 6 SQL backends with real
  containers — SQLite, PostgreSQL, MySQL, DuckDB, CockroachDB, Firebird.
Known limitations or decisions: SQLite's message for a COMPOSITE unique names the first
  column, so it attributes that column. That is pre-existing, is a real column (so the
  contract holds), and is outside C12's MySQL scope — flagged, not changed.
Commit and push state: zenit 1d18d89, not pushed.
```

**Cross-cutting notes**

- Full `zenit-dev test --unit --no-fail-fast` on zenit: **2023 of 2024 passed**. The single failure, `ChannelProtocolTest.broadcastReachesEveryOpenLink` (`java.util.concurrent.TimeoutException` after 10s), is a load-related WebSocket flake unrelated to this batch — it passes in isolation in 7ms. Named risk justifying the full run: the checksum API and the save-error translation path are used by every migration and all 8 backends.
- New public framework surface, each with a wired consumer: `IndexNames`, `TableBuilder.addIndexIfNotExists` / `uniqueIfNotExists`, `AddIndexOperation.asIfNotExists` / `superseding` / `getSupersededIndexName` / `isIfNotExists`, `MigrationChecksum.canonicalText`.
- Docs updated: `zenit/CLAUDE.md` and `zenit/docs/skills/zenit-framework/SKILL.md` (byte-based index limit, the if-not-exists index pair, the guarded drop resolving the physical name, `data(description, bodyVersion, action)`, and the rule that data remediation goes in a NEW version).
- Two ledger claims falsified with proof: zenit-pages `CreatePagesMigration` is unaffected (explicit DDL, helper named only in a comment), and quirkyquarters needs no supersession or reconcile (`quirkyquarters/CLAUDE.md:72` — no deployed installations).
- Nothing pushed. hohenheim had no unrelated dirty files at commit time; only my three were staged.