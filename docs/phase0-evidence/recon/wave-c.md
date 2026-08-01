# Wave C Reconnaissance Report (current HEAD)

Repos: `/home/skerit/projects/javaweb` (zenit workspace), `/home/skerit/projects/hohenext/hohenheim`.

---

## C1 — Applied original M007 never runs the new orphan purge
**Verdict: REAL**

- Purge lives inside M007's `up()`: `/home/skerit/projects/javaweb/zenit-auth/src/server/java/be/elevenways/zenit/auth/server/migration/M007_HardenGrantSchemas.java:79-80` — `builder.data("purge orphan record grants", datasource -> RecordGrantOrphans.purge(datasource));`
- Runner skips any applied version outright: `/home/skerit/projects/javaweb/zenit/src/server/java/be/elevenways/zenit/server/orm/migration/MigrationRunner.java:284-286` — `if (appliedVersions.contains(migration.getVersion())) { continue; }`
- Supersession only rewrites the *history row*, never re-executes: `MigrationRunner.java:348-359` (`datasource.updateMigrationChecksum(...)`, "rewrote compatible historical checksum"), driven by `M007_HardenGrantSchemas.java:61` `supersedesChecksums(ORIGINAL_2026_07_29_CHECKSUM)`.
- M008 touches only the index: `M008_ReconcileCheckPathIndex.java:48-53` (dropIndexIfExists legacy + addIndex).
- `StarfleetUpgradeTest` seeds history/schema only, never orphan grant rows: `/home/skerit/projects/javaweb/zenit-auth/src/test/java/be/elevenways/zenit/auth/server/StarfleetUpgradeTest.java:205-251` (`simulateOriginalM007`), assertions at `:253-302` are schema/checksum only.
- Backstop exists but is scheduled, not upgrade-path: `zenit-auth/.../OrphanRecordGrantPruneTask.java:45-46`.

**Mechanism note:** a new M009-style migration calling `RecordGrantOrphans.purge` is the natural fix; `builder.data(...)` steps checksum by description only (see C11).

---

## C2 — Interrupted original MySQL M007 cannot recover
**Verdict: REAL**

- Revised M007 adds columns with no `ifNotExists`: `M007_HardenGrantSchemas.java:71-74`
  ```java
  table.addColumn("granted_by", ColumnType.STRING, col -> col.maxLength(128).nullable());
  table.addColumn("expires_at", ColumnType.DATETIME, col -> col.nullable());
  ```
- `ColumnDefinition.isIfNotExists()` exists and is deliberately **non-structural** for checksums: `MigrationChecksum.java:255-259`, so adding `.ifNotExists()` here would NOT move the checksum (important: it is a checksum-safe remedy).
- MySQL DDL is non-transactional; the original op order was addColumn(granted_by), addColumn(expires_at), then the 65-char index (which aborted with 1059) — see the narrative in `M008_ReconcileCheckPathIndex.java:14-21` and the MySQL rationale in `GrantMigrationChainMySqlTest.java:29-39`.

**Test infra:** `zenit-auth/src/test/java/.../GrantMigrationChainMySqlTest.java` is a `@Testcontainers` class with its own `MySQLContainer<>("mysql:8.0")` (`:41-58`), URL appended `preserveInstants=false`. This is the place to model committed prefixes.

---

## C3 — Same-value deny can lose to concurrent revoke + allow
**Verdict: REAL**

- `/home/skerit/projects/javaweb/zenit-auth/src/server/java/be/elevenways/zenit/auth/server/RecordGrants.java:238-245`:
  ```java
  boolean valueChanged = !Boolean.FALSE.equals(current);
  boolean expiryChanged = !Objects.equals(grant.get(RecordGrantModel.EXPIRES_AT), expiresAt);
  if (!valueChanged && !expiryChanged) { return grant; }   // no write at all
  ```
- The write-then-recreate loop below (`:249-276`) and `recreateAsDeny` (`:284-298`) are only reached when something changed, so the read-stale case escapes entirely.
- Contrast: the allow path re-checks in the statement WHERE (`:202-216`).

**Existing barrier infra (reusable verbatim):** `zenit-auth/src/test/java/be/elevenways/zenit/auth/server/RecordGrantWriteBarrier.java` — a `Schema.addBeforeWriteHook` seam plus `BarrieredSqliteDatasource extends SqliteDatasource` overriding `updateAll` (`:61-77`) because set-based updates skip row hooks; a `RaceCoordinator` dedupes per thread; tests must call `clear()`. Consumers: `RecordGrantsTest.java:206,228,260` (`denySurvivesARacingAllow`, etc.) and `:305 aDenyWhoseRowIsRevokedMidWriteStillEndsUpStored` with an inline `RevokingDatasource extends SqliteDatasource` (`:343-356`). PostgreSQL race coverage: `RecordGrantsPostgresTest.java` (Testcontainers `postgres:17-alpine`, uses a `pg_sleep` BEFORE INSERT trigger at `:47-60` to widen the window).

---

## C4 — Couchbase cannot provide the sticky-deny guarantee
**Verdict: REAL (self-documented)**

- `/home/skerit/projects/javaweb/zenit/src/server/java/be/elevenways/zenit/server/orm/CouchbaseDatasource.java:970-982` doc: *"N1QL UPDATE is per-document atomic but its read-modify-write has no CAS retry loop, so two concurrent increments of the SAME document can conflict or lose an update — weaker than the SQL backends and Mongo."*
- Implementation `updateAll` at `CouchbaseDatasource.java:982-1034`: builds one `UPDATE ... SET ... WHERE ... RETURNING RAW META(t).id`, counts rows; no CAS, no retry.
- Note `CouchbaseDatasource.createIfAbsent` (`:783-818`) IS safe (KV `insert` → `DocumentExistsException`), and it refuses to run inside an SDK transaction (`:791-795`).
- zenit-auth has **no** Couchbase test class; the only mentions are a comment in `RecordGrantsTest.java:160`.

---

## C5 — Grant-store outage latches cleanup off permanently
**Verdict: REAL**

- `/home/skerit/projects/javaweb/zenit-auth/src/server/java/be/elevenways/zenit/auth/server/RecordGrantStore.java:84-87`:
  ```java
  if (probedDatasource == datasource) { return probedAvailable; }   // cached false, forever
  ```
  The re-probe inside the sync block (`:89-99`) only runs when the datasource *identity* differs. `markUnavailable` (`:104-112`) pins `probedDatasource = datasource; probedAvailable = false`.
- The log claims otherwise: `:132` `"record-grant lifecycle cleanup is a NO-OP until the store answers again"`.
- Only escape is `resetAvailabilityProbe()` (`:78-84`, documented "Test hook") or a new Datasource instance.

**Tests:** `RecordGrantCleanupTest.java:287 anUnavailableGrantStoreDegradesTheCleanupAndTheOrphanSweepIsTheBackstop`, `:325 aRealGrantStoreFailureIsNotSwallowedByTheAvailabilityGate`, using `FlakyAuthSqliteDatasource extends SqliteDatasource` (`:357-395`).

---

## C6 — Orphan sweep misses custom soft deletes; no subject sweep
**Verdict: REAL (both halves)**

- Liveness = "a row comes back from `target.find()`": `/home/skerit/projects/javaweb/zenit-auth/src/server/java/be/elevenways/zenit/auth/server/RecordGrantOrphans.java:145` `for (Row row : target.find().where(untypedIn(primaryKey, batch)).all())`. No soft-delete notion anywhere in the file.
- Hohenheim soft-deletes sites by hand: `SiteModel` declares `DELETED_AT` (`/home/skerit/projects/hohenext/hohenheim/src/common/java/be/elevenways/hohenheim/model/SiteModel.java:107`) but attaches only `RevisionableBehaviour` (`:111-112`) — no `SoftDeleteBehaviour`. The delete path stamps it manually: `hohenheim/src/server/java/be/elevenways/hohenheim/server/cms/SiteResource.java:329` `existing.set(SiteModel.DELETED_AT, Instant.now());`
- Subject orphans are outside the sweep: `RecordGrantOrphans.purge` groups only by `(MODEL, RECORD_ID)` (`:83-105`). Subject cleanup is forward-only via `RecordGrantCleanup.revokeSubjectAuthority` (`RecordGrantCleanup.java:152-168`) and is itself gated by `RecordGrantStore.maintain` (so skippable during an outage — the C5 latch compounds this).
- Existing coverage: `RecordGrantOrphanPurgeTest.java:55,99,118` and `RecordGrantCleanupTest.java:98 handRolledSoftDeleteThroughSaveRevokesGrants`, `:130 softDeleteBehaviourDeleteRevokesGrants`, `:397 legacyGrantsOnAnUndeclaredModelAreSkippedByCleanupButSweptByTheBackstop`.

---

## C7 — `insertIfAbsent` conflates PK and secondary unique conflicts
**Verdict: REAL for SQL + Mongo; false for Couchbase**

- Contract: `zenit/src/common/java/be/elevenways/zenit/common/orm/model/Model.java:376` `@return true when THIS caller inserted the row, false when a concurrent writer won`.
- SQL atomic path has **no conflict target**: `zenit/src/server/java/be/elevenways/zenit/common/orm/datasource/sql/SqlDatasource.java:562-563`
  ```java
  return buildInsertSqlCore("INSERT INTO", tableName, columnNames) + " ON CONFLICT DO NOTHING";
  ```
  → any unique index swallows to `executeUpdate() == 0` → `false` (`:640`).
- SQL fallback path (MySQL, `buildInsertIfAbsentSql` returns null at `MySqlDatasource.java:105-107`): `SqlDatasource.java:626-630` `catch (DuplicateKeyException lost) { return false; }` — untyped by constraint.
- Mongo: `MongoDBDatasource.java:872-877` `if (e.getCode() == 11000) { ... return false; }` — any duplicate key.
- Couchbase is PK-keyed KV insert (`CouchbaseDatasource.java:812-817`), so it cannot mis-attribute — **not** part of the bug.

**Test infra:** `/home/skerit/projects/javaweb/zenit/src/test/java/be/elevenways/zenit/orm/InsertIfAbsentTest.java` — `@ParameterizedClass @MethodSource("datasources")` over `TestDatasources.allDatasources()` (all 8), `@BeforeParameterizedClassInvocation(injectArguments = true)` applies the table migration. The table has a NOT NULL `tag` column as the "non-duplicate failure" probe (`:86-88`) but **no secondary unique column** — the counterexample C7 asks for does not exist yet. Existing tests: `:115, :152 (concurrent, CyclicBarrier), :207, :250`.

---

## C8 — Helper index renames changed shipped migration checksums
**Verdict: REAL**

- Index names ARE structural: `zenit/src/server/java/be/elevenways/zenit/server/orm/migration/MigrationChecksum.java:173-177` `"add_index:" + tableName + ":" + indexName + "(" + cols + ")"`.
- The renames: `zenit/src/common/java/be/elevenways/zenit/common/orm/migration/MigrationBuilder.java:233-234` (`<translations table>_locale_unique`, replacing `<child>_<fk>_locale_unique`) and `:290` (`childTable + "_order_index"`, replacing `<child>_<fk>_order_key_index`). Both AIDEV-NOTEs (`:221-231`, `:284-289`) confirm the old derived names shipped and PostgreSQL truncated them silently.
- **Only** `M007_HardenGrantSchemas.java:61` declares a supersession anywhere in the workspace (`grep supersedesChecksums`). None of the affected consumers do.
- Affected shipped consumers at HEAD:
  - `zenit-microcopy/src/server/java/be/elevenways/zenit/microcopy/server/migration/M002_FiltersChildTable.java:29`
  - `zenit-media/src/common/java/be/elevenways/zenit/media/common/migration/AddMediaAltMigration.java:22`
  - `spamservice/src/server/java/be/elevenways/spamservice/server/migration/M001_CreateSpamserviceTables.java:81-82`
  - `quirkyquarters/src/main/java/be/elevenways/quirkyquarters/storage/zenit/migration/InitialMigration.java:78, 241, 316, 343, 559`
  - `zenit-pages/.../CreatePagesMigration.java:18` is a **comment only** (spells its DDL out) — not affected.
- No module besides hohenheim pins a checksum golden (`grep MigrationChecksum` over consumer modules returns nothing outside zenit itself).

**Test infra:** `zenit/src/test/java/be/elevenways/zenit/orm/MigrationIntegrityTest.java` (`:157 checksumComputationJourney`, `:191 ifNotExistsChecksumJourney`, `:237 integrityFindingsJourney`, `:306 transitionalChecksumJourney`, `:564 supersededChecksumJourney`) and `TranslationsIndexNameTest.java:129 translationsIndexNameJourney` (pins `OLD_DERIVED_NAME` length 67 at `:68,135`).

---

## C9 — M008 leaves PostgreSQL's truncated legacy index
**Verdict: REAL (low)**

- M008 drops exactly one spelling: `M008_ReconcileCheckPathIndex.java:39-40` (65-char `LEGACY_CHECK_PATH_INDEX`) then `:50` `table.dropIndexIfExists(LEGACY_CHECK_PATH_INDEX)`. The class comment at `:18-21` explicitly acknowledges the missing 63-char truncated variant.
- The guard is an exact metadata name match: `zenit/src/server/java/be/elevenways/zenit/common/orm/datasource/sql/SqlMigrationOperationVisitor.java:618-621` + `:641` `if (indexName.equalsIgnoreCase(rs.getString("INDEX_NAME")))`, so a 63-char truncated name is invisible to the 65-char drop and the drop silently no-ops.

---

## C10 — Index-name guard checks characters, not bytes
**Verdict: REAL (low)**

- Doc: `zenit/src/common/java/be/elevenways/zenit/common/orm/OrmConstants.java:105-114` — *"PostgreSQL/CockroachDB and Firebird 4 stop at 63 **bytes**"*, `MAX_INDEX_NAME_LENGTH = 63`.
- Enforcement: `zenit/src/common/java/be/elevenways/zenit/common/orm/migration/operation/AddIndexOperation.java:72` `if (indexName.length() <= OrmConstants.MAX_INDEX_NAME_LENGTH) return;` and the message at `:80-83` says "characters".
- Tests: `zenit/src/test/java/be/elevenways/zenit/orm/IndexNameLimitTest.java:29, :85` — ASCII only, and `:34` asserts the constant equals 63.

---

## C11 — Hohenheim M045 not covered by the checksum golden
**Verdict: REAL**

- Golden filter: `/home/skerit/projects/hohenext/hohenheim/src/browserTest/java/be/elevenways/hohenheim/test/migration/MigrationIntegrityTest.java:242` `if (migration.getClass().getName().startsWith("be.elevenways.hohenheim.migration."))`.
- M045 lives in `be.elevenways.hohenheim.server.migration` (`M045_SiteDomainRouteClaims.java:1`, package chosen deliberately, see its AIDEV-NOTE `:30-35`), so it is excluded.
- The golden file confirms the hole: `src/browserTest/resources/migration-checksums.txt` (41 lines) jumps `2026_07_29_000044 M044_SystemUserClaims` → `2026_07_30_000046 M046_DyndnsTokenIndex`; no M045 line.
- The same filter also gates `alterOnlyMigrations()` (`:64`).
- Data-body checksum weakness confirmed: `zenit/src/server/java/be/elevenways/zenit/server/orm/migration/MigrationChecksum.java:234-236` `return "data:" + operation.getDescription();` — so `RouteClaims::backfill` (`M045:55-56`) and `RecordGrantOrphans::purge` (`M007:79-80`) can change arbitrarily without moving a checksum.

---

## C12 — MySQL duplicate-key attribution mistakes index names for columns
**Verdict: REAL (low)**

- `/home/skerit/projects/javaweb/zenit/src/common/java/be/elevenways/zenit/common/orm/datasource/DuplicateKeyException.java:135-147`:
  ```java
  String name = key.group(1);           // MYSQL_KEY = "Duplicate entry .+ for key '([^']+)'"
  int dot = name.lastIndexOf('.');
  if (dot >= 0) name = name.substring(dot + 1);
  return "PRIMARY".equals(name) ? null : name;   // index name returned as a column
  ```
  Doc at `:130-134` admits the equivalence only holds "for single-column unique indexes".
- Test gap: `zenit/src/test/java/be/elevenways/zenit/orm/DuplicateKeyTest.java:73-76` creates only `email ... .unique()` (derived single-column) and asserts `assertEquals("email", duplicate.getColumnName())` at `:103`. Backend list at `:54-63` is the 6 SQL backends (SQLite, PostgreSQL, MySQL, DuckDB, CockroachDB, Firebird) — explicit, not `allDatasources()`.

---

## C13 — SQLite thread-local transactions share one JDBC connection
**Verdict: REAL**

File: `/home/skerit/projects/javaweb/zenit/src/server/java/be/elevenways/zenit/server/orm/SqliteDatasource.java` (693 lines).

**Architecture, precisely:**
- Fields `:54-56`: `private final String jdbcUrl; private @Nullable Connection realConnection; private final ThreadLocal<SqliteTransaction> currentTransaction = new ThreadLocal<>();`
- `getConnection()` `:275-287`: lazily `DriverManager.getConnection(jdbcUrl)` into the **single** `realConnection` (unsynchronized null/closed check — itself a race), returns `NonClosingConnection.wrap(realConnection)`.
- `NonClosingConnection` (`zenit/src/server/java/be/elevenways/zenit/server/orm/NonClosingConnection.java:26-41`): a `java.lang.reflect.Proxy` implementing `Connection`, making `close()` a no-op and delegating everything else (including `setAutoCommit`, `commit`, `rollback`) to the one shared target. Rationale at `:271-274` of SqliteDatasource: closing a `:memory:` connection destroys the database.
- `withTransaction` `:517-570`: reads the ThreadLocal; if a transaction exists it joins as `Transaction.nested(existing)` (`:521-527` — this is the nested-in-one-thread support). Otherwise `conn.setAutoCommit(false)` (`:534`), constructs `SqliteTransaction(conn)` (`:535`), stores in ThreadLocal (`:536`), and in `finally` (`:549-560`) `currentTransaction.remove()` + `conn.setAutoCommit(true)`.
- `hasActiveTransaction()` `:511-514` reads the same ThreadLocal.
- `SqliteTransaction` `:638-692`: holds the connection, `active`/`committed` flags, `commit()` → `connection.commit()`, `rollback()` → `connection.rollback()`, `close()` rolls back if active. No locking, no ownership check.
- **The defect:** ownership state (`currentTransaction`, `SqliteTransaction.active`) is per-thread, but `setAutoCommit`/`commit`/`rollback` all land on the one shared `realConnection`. Thread B doing a plain write while A is mid-transaction runs inside A's transaction; B entering `withTransaction` flips autoCommit and commits A's buffered work; A's `finally` resets autoCommit under B.
- In-memory vs file: identical code path; the URL alone differs. `TestDatasources.sqlite()` uses `"jdbc:sqlite::memory:"` (`zenit/src/test/java/be/elevenways/zenit/orm/TestDatasources.java:713`); `RecordGrantWriteBarrier.BarrieredSqliteDatasource` also `:memory:`; hohenheim always file-backed: `hohenheim/src/server/java/be/elevenways/hohenheim/server/HohenheimDatabase.java:41` `url = "jdbc:sqlite:" + dbPath;` with `requireSqlite(engine, url)` at `:47`. No shared-cache / `file::memory:?cache=shared` / busy-timeout configuration anywhere.

**Existing awareness + test infra:** `hohenheim/src/browserTest/java/be/elevenways/hohenheim/test/SiteEnableWriteBarrier.java:22-25` explicitly works around this: *"Parking before any write is also what keeps this safe on SQLite, whose datasource shares ONE JDBC connection between threads: a parked worker holds no uncommitted rows, so the other worker's commit cannot carry half of it along."* Transaction semantics contract test: `zenit/src/test/java/be/elevenways/zenit/orm/TransactionContractTest.java` (`@ParameterizedClass` over `TestDatasources.allDatasources()`, `:36-48`) — single-threaded today; plus `TransactionHooksTest.java`.

---

## C14 — Route claim uniqueness does not cover overlapping listener sets
**Verdict: REAL (self-documented residual gap)**

**Storage schema:**
- Column `site_domains.live_route_key`, `STRING maxLength(1024) nullable ifNotExists` — `/home/skerit/projects/hohenext/hohenheim/src/server/java/be/elevenways/hohenheim/server/migration/M045_SiteDomainRouteClaims.java:53-54`; unique index `site_domains_live_route_key_unique` on that single column (`:42`, `:57-58`); backfill data step `RouteClaims::backfill` (`:55-56`) between the two.
- Field declaration + doctrine: `hohenheim/src/common/java/be/elevenways/hohenheim/model/SiteDomainModel.java:102-116` — derived, never operator-editable, NULL = no claim, relies on NULLs-are-distinct unique semantics.
- Key format: `RouteClaims.keyOf` (`hohenheim/src/server/java/be/elevenways/hohenheim/server/proxy/RouteClaims.java:88-100`) = `canonicalHostname \n normalizedPath \n String.join(",", ListenerAddressMatcher.parse(listenOn))`. Match type deliberately excluded (`:83-86`). `keyOfPendingWrite` (`:113-118`) reads through `SiteDomainModel.effective`.
- Liveness: `RouteClaims.isLive` (`:74-78`) = `ENABLED == TRUE && DELETED_AT == null`.
- Stamping: `RouteClaims.restamp(siteId, live)` (`:129-162`) — bulk NULL on not-live; otherwise release-then-claim in two passes (`:149-161`), lowest-id wins within a site (`:143-145`); `write()` (`:164-172`) converts `DuplicateKeyException` → `ClaimConflict`. Backfill (`:203-232`) mirrors the same lowest-id heal.
- Per-write stamping hook: `SiteDomainResource.installRouteInvariant()` `beforeWriteHook` at `hohenheim/src/server/java/be/elevenways/hohenheim/server/cms/SiteDomainResource.java:172-183` (`row.set(LIVE_ROUTE_KEY, RouteClaims.isLive(site) ? RouteClaims.keyOfPendingWrite(row) : null)`).

**The defect, verbatim from the source** (`RouteClaims.java:33-38`):
> *"Residual gap, deliberately accepted: the key holds the listener restriction LITERALLY, while route identity treats listener SETS as conflicting when they OVERLAP (an empty restriction listens everywhere). An all-interfaces row and a single-address row on the same hostname therefore hold DIFFERENT keys and both fit in the index... Set overlap is not expressible as a unique index; the app-level scan still refuses that pair whenever the two writes are not simultaneous."*

**The routing conflict logic it feeds (advisory pre-scan):**
- `SiteDomainResource.refuseRouteConflicts` `:201-274` — `beforeValidateHook` (`:165-171`, also `canonicalizePath` `:189-199`). Loads every site and every domain row (`:234-236`, `:245`), skips cross-site candidates unless both sites are live (`:253-255`), compares canonical hostname + normalized path + `listenersOverlap` (`:259-263`), throws `route_taken_other_site` / `hostname_taken` / `route_taken`.
- Site-enable side: `refuseEnableRouteConflicts(int siteId)` `:290-323`, same comparison, `RouteClaims.isLive` gate at `:309`.
- Overlap predicate: `listenersOverlap` `:333-338` — empty set overlaps everything; else `ListenerAddressMatcher.intersection(...) != null`.
- Canonical listener parsing: `hohenheim/src/server/java/be/elevenways/hohenheim/server/proxy/ListenerAddressMatcher.java:20-35` (`"any"` collapses to `List.of()`, dedup + sort), `intersection` `:56-63`, `specificity` `:65-67`, `matches` `:37-48`.

**Test infra:** `hohenheim/src/browserTest/java/be/elevenways/hohenheim/test/RouteOwnershipInvariantTest.java` (extends `HohenheimTestBase`): `:107 twoSitesEnablingOneHostnameAtTheSameInstantLeaveExactlyOneOwner` (drives `SiteEnableWriteBarrier.Coordinator`, `:132-137`), `:169 aDeletedSitesHostnameBecomesClaimableAgain`, `:214 theDomainRouteRefusalFiresOnAWriteThatNeverTouchesTheResource`. Also `PathRoutingTest.java`. Barrier util: `SiteEnableWriteBarrier.java` (static one-per-JVM `beforeValidate` hook + volatile coordinator, `CountDownLatch` park/release, `clear()` in `@AfterEach` at `RouteOwnershipInvariantTest.java:34-36`).

---

## Test-infrastructure map (cross-cutting)

**Multi-backend parameterization (zenit only).**
- `/home/skerit/projects/javaweb/zenit/src/test/java/be/elevenways/zenit/orm/TestDatasources.java`
  - `allDatasources()` `:476-489` — all 8 named backends (SQLite, PostgreSQL, MySQL, DuckDB, CockroachDB, Firebird, MongoDB, Couchbase). Pooled backends return singletons; SQLite/DuckDB/Mongo return fresh instances.
  - `agnosticDatasources()` `:495-504` — SQLite + PostgreSQL, widening to `allDatasources()` when `System.getProperty("zenit.test.datasources").equals("all")`.
  - Per-Gradle-worker DB isolation via `WORKER` + `workerJdbcUrl`/`withDatabase` (`:509-528`); reused testcontainers.
  - `getConnection(Datasource)` helper `:461-464` (via migration visitor).
- Declaration pattern: `@ParameterizedClass(name = "[{index}] {0}") @MethodSource("datasources") @TestInstance(PER_CLASS)`, field `@Parameter Datasource ds`, static `@BeforeParameterizedClassInvocation(injectArguments = true)` applying a throwaway `Migration` via `MigrationExecutor` + `MigrationTestSupport.assertCompleted`. Canonical examples: `InsertIfAbsentTest.java:58-97`, `TransactionContractTest.java:36-70`, `DuplicateKeyTest.java:44-87` (explicit 6-backend SQL-only list).
- `--datasources all` plumbing: `/home/skerit/projects/javaweb/zenit/build.gradle:807-812` (`-Pdatasources` → `systemProperty 'zenit.test.datasources'`). `maxParallelForks` at `:806`, forks parallel / within-fork serial (`:798-805`). CLI doc: `CLAUDE.md:36-38` (`zenit-dev test --datasources all`). **This passthrough exists only in `zenit/build.gradle`** — `zenit-auth/build.gradle` has no equivalent, so zenit-auth tests are hand-rolled per backend.

**Migration tests.**
- zenit: `zenit/src/test/java/be/elevenways/zenit/orm/MigrationIntegrityTest.java` (checksum/ifNotExists/integrity/transitional/superseded/replay/repair journeys), `MigrationRunnerTest.java`, `MigrationDataStepTest.java`, `MigrationDryRunTest.java`, `TranslationsIndexNameTest.java`, `IndexNameLimitTest.java`, `SchemaDriftCheckerTest.java`.
- zenit-auth: `StarfleetUpgradeTest.java` (SQLite in-memory, reconstructs the original M007 signature set, `withIntegrityMode`-style setting toggling via `ServerSettings.Database.MIGRATION_INTEGRITY`), `GrantHealMigrationTest.java` (`:112 migrationChecksumIsPinned`), `RecordGrantMigrationTest.java`, `RecordGrantOrphanPurgeTest.java`, `GrantMigrationChainMySqlTest.java` (real MySQL).
- hohenheim: `src/browserTest/java/be/elevenways/hohenheim/test/migration/MigrationIntegrityTest.java` + golden `src/browserTest/resources/migration-checksums.txt`; helper `emptyDatabase(label)` creates a **temp-file** SQLite db (`:288-293`), `withIntegrityMode` (`:295-303`), `M042_FROZEN_CHECKSUM` pin (`:44-45`, test at `:279`).

**Record-grant tests.** All in `/home/skerit/projects/javaweb/zenit-auth/src/test/java/be/elevenways/zenit/auth/server/`: `RecordGrantsTest`, `RecordGrantCleanupTest`, `RecordGrantOrphanPurgeTest`, `RecordCapabilityCheckerTest` (+ `RecordCapabilityFixtures`), `GrantAuthorizationPolicyTest`, `PermissionResolverTest`, `RecordGrantsPostgresTest` (Testcontainers), `GrantMigrationChainMySqlTest` (Testcontainers). Barrier helper: `RecordGrantWriteBarrier`.

**Barrier/concurrency utilities in the workspace** (`CyclicBarrier`/`Phaser`/latch-driven):
- `zenit-auth/.../RecordGrantWriteBarrier.java` (hook + barriered SQLite datasource + RaceCoordinator)
- `zenit/src/test/java/be/elevenways/zenit/orm/InsertIfAbsentTest.java` (`CyclicBarrier`, `:152`)
- `hohenheim/src/browserTest/java/be/elevenways/hohenheim/test/SiteEnableWriteBarrier.java` (CountDownLatch park/release)
- `quirkyquarters/src/test/java/be/elevenways/quirkyquarters/storage/zenit/{ZenitPairingCodeStoreTest,ZenitMemoryStoreTest}.java`
- `RecordGrantsPostgresTest` uses a server-side `pg_sleep` trigger instead of a client barrier.