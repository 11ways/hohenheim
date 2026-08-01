# BrandTest order-dependence + zenit unit-suite baseline

Date: 2026-08-01. Repo: `/home/skerit/projects/javaweb/zenit`.

## Half 1 — BrandTest order-dependence

### What actually leaks

`be.elevenways.zenit.common.ui.Stylesheets` is a process-global
`ConcurrentHashMap<Identifier, Entry>` with `register`/`unregister`/`entries`.

The leaking entry is **not** planted by a test. `BrandEndpoints`
(`zenit/src/common/java/be/elevenways/zenit/common/brand/BrandEndpoints.java:36-38`)
registers it from a **static initializer**:

```java
static {
    Stylesheets.register(Identifier.of("zenit", "brand"), "/brand.css", Stylesheets.WEIGHT_BRAND);
}
```

Any class that loads `BrandEndpoints` plants `/brand.css` for the rest of the JVM.
In the unit suite the loader is `be.elevenways.zenit.server.http.BrandCssHttpTest`
(`@BeforeAll` does `Object ignored = BrandEndpoints.BRAND_CSS;` to force endpoint
registration). The zenit `test` task runs `maxParallelForks = min(8, cpus-2)`, so
whether `BrandCssHttpTest` precedes `BrandTest` **in the same fork** is luck — which
is exactly why the failure came and went across yesterday's runs.

`BrandTest` itself was clean about cleanup (try/finally unregister). Its defect was
the **assertion**: it selected the entries to compare with

```java
.filter(href -> href.startsWith("/a") || href.startsWith("/b") || href.startsWith("/c"))
```

`/brand.css` starts with `/b`, and at `WEIGHT_BRAND` (900) it sorts after
`WEIGHT_APP` (700), so it appended itself to the tail of the expected list.

### Recorded pre-fix failure

From the pre-existing full-suite run `20260801-024914`
(`~/.local/share/zenit-dev/test-logs/20260801-024914.log`) — the only failure in that run:

```
BrandTest > The stylesheet registry orders by weight, then by registration order FAILED
    org.opentest4j.AssertionFailedError: expected: <[/b.css, /a.css, /c.css]> but was: <[/b.css, /a.css, /c.css, /brand.css]>
        at app//be.elevenways.zenit.common.BrandTest.stylesheetRegistryOrdersByWeightThenRegistration(BrandTest.java:114)
```

Deliberately reproduced this session with a temporary, reverted perturbation
(`maxParallelForks = 1` in `zenit/build.gradle` plus a temporary
`src/test/resources/junit-platform.properties` setting
`junit.jupiter.testclass.order.default=org.junit.jupiter.api.ClassOrderer$DisplayName`,
which puts `BrandCssHttpTest` before `BrandTest` in one JVM):

```
zenit-dev test --unit --class BrandCssHttpTest,BrandTest --no-fail-fast
  -> FAILED, 1 of 5, log 20260801-104549
     org.opentest4j.AssertionFailedError: expected: <[/b.css, /a.css, /c.css]> but was: <[/b.css, /a.css, /c.css, /brand.css]>
```

A plain unperturbed two-class run (log `20260801-104409`) passes, because Gradle put
the two classes in different forks — proof that this is fork/order luck, not a code path.

### The fix and why that altitude

Chosen: **the assertion is scoped to the entries the test owns**, selected by
identifier namespace (`brandtest`) instead of by href prefix.
(`zenit/src/test/java/be/elevenways/zenit/common/BrandTest.java`, commit `9e8b95f`.)

Rejected alternatives, with reasons:

- *The polluting class cleans up after itself* — impossible and wrong. The polluter is
  production wiring in a `static {}` block whose entire purpose is "loading this class
  contributes /brand.css". It runs once per JVM and there is no second chance to run it.
- *A test-scoped reset seam on `Stylesheets`* — **would not have fixed this failure**.
  The offending entry is already present before `BrandTest` starts, so a
  snapshot/restore (the shape `RecordSourceRegistry.snapshot()/restoreSnapshot()`
  already establishes in this repo, used by `RecordSourceTest`) faithfully preserves
  it. A destructive `clear()` seam *would* hide it, at the cost of permanently
  deleting a registration that can never be recreated in that JVM — a booby trap for
  every later class that renders a layout.
- Scoping by identity kills the whole class of problem for this test: no registration
  by any other owner, present or future, with any href, can perturb it. The assertion
  now also checks the entry **ids** in order, not just hrefs, so the ordering contract
  is asserted against identities rather than against strings that can collide.

An `AIDEV-NOTE` on the test records the trap so the href filter is not reintroduced.

### Verification

| Run | Command | Result |
| --- | --- | --- |
| pre-fix, perturbed order | `zenit-dev test --unit --class BrandCssHttpTest,BrandTest --no-fail-fast` | FAILED 1/5 (log 20260801-104549) |
| post-fix, same perturbed order | same | PASSED 5/5 (log 20260801-104643) |
| post-fix, isolation, perturbation reverted | `zenit-dev test --unit --class BrandTest --no-fail-fast` | PASSED 3/3 (log 20260801-104713) |

`build.gradle` and `src/test/resources/` were restored to their committed state
before the isolation run and before the suite run (`git diff` on `build.gradle` is empty).

### Sweep: other zenit tests touching process-global state

`Stylesheets` has exactly **one** test consumer in the whole workspace (`BrandTest`);
the only other users are `BrandEndpoints` and `StylesheetTemplateFunctions`. So there
is no second consumer that a shared seam would serve.

Wider sweep over `zenit/src/test` for mutations of process-global registries
(`Registries.*`, `Models.registerInstance`, `RecordSourceRegistry.INSTANCE`,
`Zenit.set*`, `*.setResolver`, `setPolicyResolver`, `setPersonalizationDetector`)
versus the presence of `@AfterEach`/`@AfterAll`. Reported, not fixed:

- `data/RecordSourceTest.java` — 37 mutation sites, no `@After*` hooks, **but it is
  correct**: it takes `RecordSourceRegistry.INSTANCE.snapshot()` in `@BeforeAll` and
  calls `restoreSnapshot` per test. This is the repo's established seam for this
  problem and the model the rest should follow.
- `orm/NestedEagerLoadBatchingTest.java`, `routing/ModelParamTest.java`,
  `data/RecordSourceEncryptionTest.java`, `data/RecordSourceBucketsBackendTest.java` —
  `Models.registerInstance(...)` in `@BeforeAll`, never unregistered. Model singletons
  are keyed by model id and stay for the JVM's life. No collision today (ids are
  namespaced `zenit_test:*` / distinct), but nothing enforces it: two classes claiming
  one model id with different datasources would silently cross-wire, and the loser
  would fail only in the fork where the other ran first — the same failure shape as
  BrandTest.
- `sitemap/SitemapsTest.java` — adds a provider to `Registries.SITEMAP_PROVIDERS` and
  an endpoint to `Registries.ENDPOINTS` without removing them. It guards with
  `contains(...)` and asserts with `anySatisfy`/`contains` rather than exact lists, so
  it neither breaks on repetition nor breaks on foreign entries. Leaks, but benign —
  note that any *other* test that ever asserts an exact sitemap entry list would be
  poisoned by it.
- `orm/EncryptedFieldTest.java` — its single `Models.registerInstance` is inside an
  `assertThrows`, i.e. it deliberately fails to register. Not a leak.
- Everything else in the sweep (`CsrfMiddlewareTest`, `WebSocketAuthHttpTest`,
  `ActivityLogTest`, `AccountabilityTest`, `AuthorizationMiddlewareTest`,
  `RateLimitHttpTest`, `AdmissionRateLimitHttpTest`, `ResponseCacheHttpTest`,
  `ChannelAdmissionTest`, `ChannelGatewayRevalidationHttpTest`, `ConduitCsrfTest`,
  `SitemapHttpTest`, `SecretRedactionJourneyTest`, `RecordSourceFieldTest`,
  `RecordSourceBucketsTest`, `RecordSourceBucketsPerformanceTest`,
  `RecordCapabilityTruthTableTest`, `WebSocketRevalidationHttpTest`,
  `WebSocketAdmissionHttpTest`, `RequestBodyHttpTest`, `MatchGuardHttpTest`) carries an
  `@AfterEach`/`@AfterAll` that restores what it set.

No further order-dependent assertion of the BrandTest shape (exact-list assertion over
a shared registry, selected by a value another owner can collide with) was found.

## Half 2 — zenit unit-suite baseline

### Classification of yesterday's failures (from the saved logs, no re-runs)

| Run | Failures | Classification |
| --- | --- | --- |
| `20260801-022237` (2019 total / 1932 passed / 19 failed / 68 skipped, 651s) | every one is `[8] Couchbase` / `Couchbase` — `initializationError`, `RelationLoadingTest` Couchbase cases, `CouchbaseUniqueIndexWarningTest` | **infrastructure** |
| `20260801-023406` (1848 / 1771 / 32 / 45) | 31 Couchbase (`DataSourceException: Failed to ensure collection exists: zenit_counters` -> `Failed to create primary index` -> `AmbiguousTimeoutException ... CREATE PRIMARY INDEX ... timeoutMs: 75000`), **plus** `BrandTest` | 31 **infrastructure**, 1 **code/test defect** (BrandTest) |
| `20260801-024411` (1752 / 1691 / 9 / 52) | `ContainerLaunchException: Container startup failed for image couchbase/server:community-7.6.1`, then cascading `IllegalStateException: Mapped port can only be obtained after the container is started` in 7 classes, plus `CollectionAdvisoryLockBackendTest > couchbaseLockRecordsFollowTheOwnerLeaseContract` | **infrastructure** (cold-start after the old container was removed) |
| `20260801-024914` (2056 / 1980 / 1 / 75) | `BrandTest` only | **code/test defect** — the one fixed above |

Yesterday's diagnosis is therefore **confirmed, not merely plausible**: every non-BrandTest
failure across all four runs is a Couchbase container symptom (query-service timeouts on
`CREATE PRIMARY INDEX`, container start failure, or a mapped-port access on a container
that never started). No other real code defect surfaced. The `1752 / 1848 / 2056` total
spread is itself a symptom: when the Couchbase container is unusable, whole
`@ParameterizedClass` backends fail at `initializationError` and their cases are never counted.

### Container state and the recurring debris problem

`docker ps` at the start of this session:

```
couchbase/server:community-7.6.1     Up 8 hours    (recreated after yesterday's removal)
mongo:7                              Up 9 hours
jacobalberty/firebird:v4.0           Up 6 days (healthy)
cockroachdb/cockroach:v23.1.11       Up 6 days
mysql:8.0                            Up 6 days
pgvector/pgvector:pg15               Up 6 days
```

Debris **had already re-accumulated** in the 8-hour-old Couchbase container:
**17 scopes / 102 collections** in bucket `testdb` (`w27`-`w33`, `w36`-`w43`, plus
`_default`/`_system`) — from a container that is 8 hours old and has served maybe five runs.

Root cause found: `TestDatasources.wipeCouchbase` (zenit `src/test/.../orm/TestDatasources.java:676`)
drops **only the current worker's own scope**:

```java
boolean present = manager.getAllScopes().stream().anyMatch(s -> s.name().equals(workerScope));
if (present) manager.dropScope(workerScope);
```

The scope name is `"w" + System.getProperty("org.gradle.test.worker")`, and Gradle's test
worker ids **increase monotonically for the life of the daemon**. So every run allocates a
fresh band of worker ids, creates a fresh band of scopes, and never drops the previous
band. Each scope carries a primary index per collection, and Couchbase's query service
degrades until `CREATE PRIMARY INDEX` blows the 75s client timeout — exactly the failure
signature in runs `022237` and `023406`.

Action taken this session: dropped the 15 stale `w*` scopes via the container's REST API
(`DELETE /pools/default/buckets/testdb/scopes/wNN`, all 200). Bucket now holds
2 scopes / 3 collections. The container itself was **not** removed — run `024411` shows
that a cold Couchbase start is its own failure mode.

**Permanent fix is still open and needs a decision, so it was not invented here.** The
naive "drop every foreign `w*` scope at wipe time" is wrong: sibling workers of the *same*
run are live concurrently and would be wiped mid-suite. Two safe shapes exist:
(a) include a per-run token in the scope name and sweep by token, or (b) sweep stale
scopes once per suite from `zenit-dev`, in the same place it already retains only the
newest owned live container per backend — that code runs once while holding the
machine-wide suite lock, which is precisely the moment when no worker scope is live.

### Final suite numbers

Command (run from `/home/skerit/projects/javaweb/zenit`):

```
zenit-dev test --unit --no-fail-fast --rerun
```

Result — journal entry `20260801-104836-940691`, log
`~/.local/share/zenit-dev/test-logs/20260801-104836.log`:

| total | passed | failed | skipped | duration |
| --- | --- | --- | --- | --- |
| **2056** | **1981** | **0** | **75** | 288s (4m48s wall for the whole `t01Verification` chain) |

278 of 280 test classes reported (the two silent ones are the two abstract dual-backend
bases that contribute only through their `_Sqlite`/`_Postgres` subclasses).

**Not cached, and it postdates HEAD.** `--rerun` was passed, so Gradle re-executed the
test task rather than reporting a saved baseline; the log shows real per-test events
(an UP-TO-DATE task emits none). HEAD (`9e8b95f`, the BrandTest fix) is timestamped
`2026-08-01T10:48:25+02:00`; the run id is `20260801-104836`, i.e. it started 11 seconds
after the commit. `BrandTest` appears in this log with all three cases PASSED.

Inside the 5-minute budget: 288s for the test phase.

**Zero Couchbase failures.** The same suite that produced 19 and 32 Couchbase failures
last night produced none after the stale scopes were dropped, on the same 8-hour-old
container. That is the direct experimental confirmation of the debris diagnosis.

### The one non-green task: `:zenitDevTest` — concurrent-session interference, not a zenit defect

`zenit-dev` reports `RESULT: FAILED` for this invocation with **zero failed tests**,
because the `t01Verification` chain also runs `:zenitDevTest` (the node test suite for
the `zenit-dev` CLI itself), and that task failed 3 of 28:

```
✖ every declared be.elevenways coordinate lands in the computed dependency closure
    TypeError: CORE_PROJECTS is not iterable  (tools/zenit-dev.test.js:872)
✖ textum depends on hawkeye because its build file says so
    AssertionError: textum declares hawkeye-common/client/server; a hawkeye-only change must mark textum stale
✖ CI levels pin every workspace repo as a deliberate inclusion or exclusion
    AssertionError: testbeds/todomvc/skeritcom is neither in CI_LEVELS nor deliberately excluded
```

Classification: **environment / concurrent work-in-progress, not a code defect in zenit
and not attributable to this session.** Evidence:

- `tools/zenit-dev` (+22/-5) and `tools/zenit-dev.test.js` (+106/-10) are **uncommitted
  worktree edits** owned by another agent session that was active throughout.
- Two of the three failing assertions exist **only** in the worktree copy and not in
  `HEAD:tools/zenit-dev.test.js`: `CORE_PROJECTS` (3 worktree hits, 0 committed) and
  `textum declares hawkeye` (1 / 0). The third assertion exists in both but passed in
  every earlier run today, so the worktree's `CI_LEVELS` change is what moved it.
- `tools/zenit-dev.test.js` has mtime `10:53:34`, i.e. it was being written **while**
  the `:zenitDevTest` task read it (`~10:53:0x`). `CORE_PROJECTS is not iterable` is the
  signature of reading a half-applied edit.
- No earlier full run today (`022237`, `023406`, `024411`, `024914`) contains
  `zenitDevTest FAILED`.

These files were deliberately left untouched: they are another agent's in-flight work.

### Honest limitation on this baseline

The zenit worktree is shared with other active sessions. At the moment this suite
started (10:48:36) the dirty zenit files were only `tools/zenit-dev*`. Four more files
were modified by other sessions **after** the suite began —
`src/common/.../routing/Endpoint.java` (10:54:20),
`src/test/.../rules/SchemaVocabularyTest.java` (10:50:06),
`src/test/.../http/AdmissionRateLimitHttpTest.java` (10:54:29),
`src/test/.../http/ScopedCspHttpTest.java` (10:49:42) — and another session's targeted
run at 10:50 (`20260801-105011`) reports 3 failures across exactly those three test
classes. Those failures belong to that session's in-flight change, not to this baseline.

So: **the green baseline is 2056 / 1981 passed / 0 failed / 75 skipped for the zenit
worktree as it stood at 10:48:36 plus commit `9e8b95f`.** It does not certify the
edits other sessions landed afterwards.

## Summary of classification

| Failure | Class |
| --- | --- |
| `BrandTest > The stylesheet registry orders by weight...` | **code/test defect** — fixed, commit `9e8b95f` |
| All Couchbase `initializationError` / `CREATE PRIMARY INDEX` timeouts / `RelationLoadingTest [8] Couchbase` / `CouchbaseUniqueIndexWarningTest` / `CollectionAdvisoryLockBackendTest` (runs 022237, 023406, 024411) | **infrastructure** — stale worker-scope debris in the reused Couchbase container; cleaned, 0 recurrence |
| `ContainerLaunchException` + `Mapped port can only be obtained after the container is started` (run 024411) | **infrastructure** — Couchbase cold start after the container was removed |
| 3 × `:zenitDevTest` node failures (this session's run) | **environment** — another session's uncommitted in-flight edit to `tools/zenit-dev*`, read mid-write |

## Open items (decisions, not work left undone)

1. **Couchbase scope debris needs a permanent sweep.** `TestDatasources.wipeCouchbase`
   only drops the current worker's scope, and Gradle worker ids climb for the life of
   the daemon, so every run leaves its previous band behind. Needs a decision between a
   per-run scope token and a once-per-suite sweep from `zenit-dev` under the machine-wide
   test lock. Not invented here because the naive version would wipe live sibling workers.
2. `Models.registerInstance` in test `@BeforeAll` without any unregister is the same
   process-global pattern that produced the BrandTest failure. No collision today; it is
   unenforced.

