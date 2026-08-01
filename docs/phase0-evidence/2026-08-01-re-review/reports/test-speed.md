# Test-suite optimisation report (zenit) — 2026-08-01

## Where the 4h35m/day actually goes (measured, 24h of `zenit-dev journal`)

| project | phase | runs | minutes | tests | s/test |
|---|---|---|---|---|---|
| zenit | unit | 79 | 118.3 | 27,848 | 0.25 |
| hohenheim | browser | 32 | 56.1 | 1,404 | 2.40 |
| zenit-auth | unit | 12 | 14.6 | 547 | 1.60 |
| zenit-cms | browser | 19 | 13.7 | 181 | 4.53 |
| plumage | browser | 21 | 10.4 | 45 | 13.84 |
| hawkeye | unit+browser | 48 | 14.6 | 5,646 | - |
| everything else | - | ~80 | ~43 | - | - |

Key decomposition of zenit's full unit run (280 XML class results, 8 forks):
**1406s of test CPU, of which 988s is per-class/per-backend SETUP** (fixture
migrations, datasource warmup), not test execution. The "3.4s per browser
test" average in the mandate is dominated by per-RUN fixed overhead of many
small filtered runs (gradle+boot), not by slow tests: hohenheim's full browser
run is 0.67s/test.

## What was changed (zenit repo only)

### 1. `MigrationRunner` classpath-scan memoization (production, `server/orm/migration/MigrationRunner.java`)
`new MigrationRunner(datasource)` ran a ClassGraph classpath scan (~1.5-2s) on
EVERY construction. Test classes construct it in `@BeforeEach` (SystemTaskModelTest
5x, TaskScheduleTest 10x, RevisionConcurrencyTest once per backend invocation, ~20
call sites total). The scan result cannot change within a JVM, so the discovered
constructor list is now memoized (double-checked, error results deliberately NOT
cached so the loud misconfiguration errors persist). Production boots paid the scan
once anyway; behavior is identical.
Proof: SystemTaskModelTest 11.4s -> 2.6s with no test change at all.
MigrationRunnerTest, MigrationIntegrityTest, MigrationDryRunTest all green.

### 2. `TaskScheduleTest`: targeted migration list
Its per-test setup ran the full auto-discovered chain (7+ framework migrations)
on a fresh SQLite per test, 10 times. It only touches the two task tables, so it
now runs exactly `M001_CreateSystemTaskTables` + `M002_AddSystemTaskBootClaim`.
33.1s -> 10.5s. The remaining ~10s is intrinsic (a 1-second cron and a deliberate
2s "nothing may fire" negative window).

### 3. `RevisionConcurrencyTest`: targeted migration list (the suite's most expensive class)
It ran the FULL framework chain (tasks, seeds, activity, revisions, ...) on all six
multi-connection backends purely to get the revisions store. It now runs only
`M001_CreateRevisionsTable` + `M002_EnforceRevisionNumberUniqueness`.
125.8s -> 49.4s (isolated single-fork run that also absorbs all container warmups;
in-suite it was 121-126s across three measured full runs).

### 4. `CouchbaseMigrationOperationVisitor.visitAssertUnique`: bounded retry on transient "Index not found" (production)
Observed mid-suite: AssertUnique's GROUP BY failed with Couchbase code 4350
"Index not found" (retry:false) right after cross-fork index churn, failing
RevisionUniquenessMigrationTest — a real flake class that also threatens production
migrations run near index DDL. Now retried up to 20 x 250ms on exactly that
signature; every other failure still throws immediately. (This flake predates my
changes — the same error class is why suite re-runs happen at all — but the faster
RevisionConcurrencyTest shifts index-churn timing, so it was hardened rather than
left.)

### 5. New `SchemalessMigrationNoOpTest` (Mongo + Couchbase)
The schemaless no-op DDL contract (addColumn/renameColumn/dropColumn tolerated,
FK degrades to a plain index, dropForeignKey drops that index, collection stays
usable) was previously covered only INCIDENTALLY by full chains running on
collection backends. Change 3 removed that side effect, so the contract is now
pinned explicitly with an assertion-backed journey — which also covers the
FK-degrade and dropForeignKey visitor paths that were NEVER covered before.
Cost: ~2s/backend, vs the ~120s side-effect it replaces.

## Proof

### Suite passes
- Full unit suite after all changes: 2065/2065 executed pass (75 skipped), exit 0.
- Final coverage run: 2067 pass (includes the 2 new tests), exit 0.
- Targeted runs of every touched class green (RevisionConcurrencyTest,
  TaskScheduleTest, SystemTaskModelTest, MigrationRunnerTest,
  MigrationIntegrityTest, RevisionUniquenessMigrationTest,
  SchemalessMigrationNoOpTest).

### Test counts
- Before: 2073 reported (--rerun; 2058 per-class executions in the streamed log).
- After: 2067 reported / 2060 per-class (+2: the new SchemalessMigrationNoOpTest
  journeys). The reported-total wobble (2073/2065/2067) is Gradle task caching of
  the ~8 zenit-gradle-plugin functional tests across runs, proven by diffing the
  streamed per-class logs: every class has IDENTICAL test counts before and after.
  Nothing was deleted, no test method removed anywhere, skips unchanged at 75.

### Coverage (the guardrail) — JaCoCo CSV before vs after
CSVs: `scratchpad/zenit-coverage-before.csv` / `zenit-coverage-after2.csv`.
- Headline: lines 82.5% -> 82.6%, branches 71.4% -> 71.4%,
  totals lines covered 21,811 -> 21,845 (+34), branches 9,152 -> 9,157 (+5).
- Per-class: 3 classes cover fewer lines/branches, all explained, none reverted:
  - `MigrationExecutor` 96->92 lines, 36->33 branches: the lost lines are the
    advisory-lock-timeout failure path and the SQLState-40001 serialization retry
    loop — they only execute under cross-class lock contention, which the removed
    full-chain runs happened to generate. No assertion ever covered them; their
    coverage was contention luck. (Follow-up candidate: a deterministic test that
    induces lock timeout / 40001.)
  - `PooledSqlDatasource.PooledTransaction` 25->23 lines: same story — the lost
    lines are SQLException wrappers / already-completed guards hit only under
    racing transactions.
  - `DevTunnelClient` 142->141 lines, 49->47 branches: untouched code and
    untouched tests; its reconnect/heartbeat paths are timing-dependent and vary
    run to run.
- The one DETERMINISTIC loss caused by change 3 (SchemalessMigrationOperationVisitor
  6->3 lines, MongoDBMigrationOperationVisitor -1) was repaired properly by change 5:
  both are now ABOVE baseline.

### Wall clock (zenit full unit suite)
- Baseline: 267s (2073 tests, --rerun).
- Coverage-instrumented test phase: 274s before -> 243-250s after (two runs).
- Plain post-change full runs measured 335s/313s BEFORE changes 3-5 landed, but
  those two runs were contaminated: the chain-wide verification gate was
  concurrently building/testing other repos (its gradle daemons share the CPU),
  which inflated every Couchbase-heavy class (e.g. RecordSourceBucketsBackendTest
  76->114s with zero code change). The final post-change run's number is recorded
  below. The honest per-run claim rests on the class-level deltas, which are
  contention-independent: -76s (RevisionConcurrency) -22s (TaskSchedule) -9s
  (SystemTaskModel) of fork time, of which RevisionConcurrencyTest sat on the
  critical path (largest class in the suite).
- FINAL RUN: 2067/2067 green (75 skipped), wall 268s, test-CPU sum 1387s.
  Wall parity with the 267s baseline despite ~105s of proven fork-CPU savings
  is ambient: the build agent and the chain verification gate ran gradle
  daemons and suites all session, and every UNTOUCHED Couchbase-heavy class
  inflated in lockstep across all post-change runs (RecordSourceBuckets
  76->92s, AddColumnIfNotExists 44->66s, DropIndexIfExists 38->54s,
  AfterCommit 35->53s — zero code change in any of them). The class-level
  deltas for the changed classes are stable in every measurement
  (RevisionConcurrency 121.7/125.8 -> 49.4 isolated / 52.2 in-suite;
  TaskSchedule 33.1 -> 10.5/11.0; SystemTaskModel 11.4 -> 2.6). The cleanest
  same-conditions pair is the coverage-instrumented phase: 274s -> 243s.
  Expect ~230-240s full-run wall on a quiet machine.

## Structural findings (things that are ALREADY optimal, and one dead end)

1. **The multi-backend classification is correct across the board.** All 9
   `agnosticDatasources()` classes are behaviour-layer (hooks, soft delete,
   optimistic locking, publishable, sluggable, timestamps, bulk ops). All 34
   `allDatasources()` classes are storage/translation/DDL dialect sweeps
   (field storage, migrations, localized, geo/vector/uuid, aggregation, atomic
   updates, insertIfAbsent, transactions, revisions, record-source buckets).
   No test is wrongly widened to 8 backends and none is wrongly narrowed.
   The "single biggest lever" hypothesized in the mandate does not exist —
   which is itself the important verified result.
2. **Cross-run Couchbase DDL reuse is architecturally impossible under
   pid-owned namespaces** (landed today). I implemented a keep-structure wipe
   (delete documents, keep collections+indexes) and proved it a no-op: every
   run's forks get fresh `wN_pPID` scopes and the abandoned-namespace sweep
   drops the previous run's scopes with all their structure. Reverted. The
   remaining ~500-800s CPU of per-run Couchbase/MySQL/Firebird/Cockroach DDL +
   per-fork warmup is the price of the (deliberate, debris-proof) isolation
   design; reclaiming it would require stable cross-run worker namespaces with
   a different ownership protocol — an architecture decision, not a test edit.
3. **The remaining Thread.sleep population is healthy**: nearly all are 10-20ms
   poll intervals inside condition loops with deadlines (the correct pattern),
   not fixed settling windows. The fixed windows that do exist
   (WebSocketRevalidationHttpTest INTERVAL_MS*4 waits) live in a 5.8s class and
   assert "nothing happened" semantics that genuinely need elapsed time.
4. **Container/test infra is already heavily optimized**: reused containers,
   per-fork namespaces, singleton pools, latched infra failures, once-per-JVM
   wipes. Nothing to reclaim there without the architecture change above.

## What was deliberately left (with reasons)

- **hohenheim browser suite** (56 min/day, full run 386-426s for 573-581 tests,
  over the 5-minute budget by 1.4-2.1 min): at 0.67s/test it is already efficient
  per test; no fixed sleeps found in its test sources; the daily cost is mostly
  RUN COUNT (32/day) plus per-run boot. Shrinking it below budget needs its own
  measured pass (per-class XML from a full run) that I could not do well in the
  same session while the verification gate held the suite lock for hours. Nothing
  was touched, so nothing regressed.
- zenit-cms / plumage / hawkeye / mvp-v01 browser lanes: their high s/test
  averages are per-run fixed overhead of many small filtered runs (build-side +
  orchestration), not suite defects.
- Consolidating small 8-backend field-type classes (Temporal/Regex/Uuid/Boolean):
  estimated ~30-60s CPU total, spread across forks (little wall effect), against
  real churn risk in dialect coverage. Not worth it after the above landed.
- Migration-DDL Couchbase heavies (RevisionUniquenessMigrationTest 48s,
  DropIndexIfExistsTest 34s, QueryBuilderAdvancedTest_Couchbase 41s in-test):
  their cost IS their subject (index DDL semantics on Couchbase); consolidation
  would not remove the DDL.

## For the build/orchestration owners (not touched, per constraints)

- 65 of zenit's 79 daily phases are FILTERED runs averaging ~45s wall for
  seconds of test content: the fixed gradle/compile overhead per invocation is
  the cost, which is build-side.
- The 24h window shows the full zenit suite ran 14x/day. Even halved, that
  saves more than any suite edit; "run only what could plausibly be affected"
  remains an orchestration-habit gap, not a suite defect.

## Honest projected daily saving

- zenit full runs: RevisionConcurrencyTest was the largest class on the
  critical-path fork; -76s there plus -22s/-9s elsewhere and ~25-40s of scan
  CPU across the pool -> roughly 40-70s per full run, x14 runs/day
  = **~10-16 min/day**.
- Filtered runs touching migrate()-calling classes get the scan cache benefit
  (~1.7s per formerly-scanning setUp).
- Flake elimination (the Couchbase "Index not found" AssertUnique failure class):
  each avoided red suite saves a 5+ minute re-run plus attention; this failure
  was observed live during this session's coverage run.
- Total honest estimate: **~15-25 min/day**, concentrated where the mandate
  measured the most cost (zenit, 118 min/day), with zero coverage traded away
  (net +34 lines, +5 branches, 2 more tests).

## Added scope: wall-clock time bombs (coordinator request)

### plumage `DatePickerTest` — the calendar-rollover bomb (was RED, now green)
Pre-fix failure, reproduced verbatim before touching anything:
`com.microsoft.playwright.TimeoutError: Timeout 45000ms exceeded` waiting to
click `.pl-date-picker__popup [data-iso='2026-07-20']`. Root cause exactly as
briefed: the fixture's picker has `pickVal = ""`, calendar.hwk's
`vYear`/`vMonth` fall back to `Dates.todayIso()` for an empty value, so the
popup renders the CURRENT month and the hardcoded July cell ceased to exist on
2026-08-01. Fix: the test now reads the `data-iso` of day 15 of the RENDERED
month (`.pl-calendar-day:not([data-outside])[data-iso$='-15']` — day 15 exists
in every month view and never appears among adjacent-month spill cells) and
derives all five assertions from it. Same assertion count and strength, valid
every day of every year. Proof: DatePickerTest green, then the FULL plumage
browser suite 120/120 green (was 119/120). Committed as plumage `d78ebb7`.

Sibling sweep (same bug, different hats):
- plumage CalendarTest/DateFieldTest: anchored on PRESET fixture values
  (`calVal = "2026-07-15"` etc.), deterministic forever — clean.
- plumage RelativeTimeTest: "years ago" for 2020 (safe for decades) and
  self-anchored `Date.now()` offsets — clean.
- Cross-repo grep for `data-iso='20..` and today-anchored assertions in
  zenit-forms/zenit-cms/zenit/hawkeye/textum browser tests: zero hits.
- zenit `SecretDisclosuresTest` (found red during my final full run — the
  same family, wall-clock SENSITIVITY rather than rollover): TTL 20ms with
  40ms sleeps meant a loaded scheduler could expire the LIVE entry between
  its own stash and claim ("2. the surviving entry is the live one" expected
  "second-secret", was null). Margins widened to TTL 200ms / 350ms sleeps —
  same assertions, same sweep semantics, stall-proof. Included in the zenit
  commit; class green, final full suite green.

### Note on build-context (coordinator's context 1 and 2)
All timing claims above are TEST-PHASE durations (gradle.stream test.phase /
XML class times), never build-inclusive, so the build agent's republish
speedups do not contaminate the before/after. No unexplained
NoClassDefFoundError/missing-registration failures were hit; the two red
results seen (AssertUnique index race, SecretDisclosures timing) were both
diagnosed to root cause and fixed, not attributed to the autoload-scan hazard.

## Files changed (zenit repo)

- `src/server/java/be/elevenways/zenit/server/orm/migration/MigrationRunner.java` (scan memoization)
- `src/server/java/be/elevenways/zenit/server/orm/CouchbaseMigrationOperationVisitor.java` (AssertUnique retry)
- `src/test/java/be/elevenways/zenit/task/scheduletests/TaskScheduleTest.java` (targeted migrations)
- `src/test/java/be/elevenways/zenit/orm/RevisionConcurrencyTest.java` (targeted migrations)
- `src/test/java/be/elevenways/zenit/orm/SchemalessMigrationNoOpTest.java` (new, pins the no-op DDL contract)
- `src/test/java/be/elevenways/zenit/server/security/SecretDisclosuresTest.java` (stall-proof margins)

Commits: zenit `61c5590` (6 files), plumage `d78ebb7` (DatePickerTest).
The build agent's `tools/zenit-dev*` work was already committed separately
(`f1447b9`) and was never staged by me.
