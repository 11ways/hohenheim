# Reused-container worker-namespace leak: diagnosis, fix, proof

Date: 2026-08-01. Repo: `/home/skerit/projects/javaweb/zenit`.
File: `src/test/java/be/elevenways/zenit/orm/TestDatasources.java` (+ new
`src/test/java/be/elevenways/zenit/orm/WorkerNamespaceSweepTest.java`).

## 1. The pre-fix counterfactual (verbatim)

The wipe is `TestDatasources.wipeCouchbase`, which drops only the CURRENT worker's scope:

```java
boolean present = manager.getAllScopes().stream().anyMatch(s -> s.name().equals(workerScope));
if (present) manager.dropScope(workerScope);
```

Live container state before anything was done this session (container up 8 hours, i.e.
re-accumulated AFTER the manual sweep earlier the same morning):

```
w22: 5 collections
w24: 4 collections
w26: 6 collections
w19: 5 collections
w21: 4 collections
w23: 8 collections
w25: 13 collections
w20: 11 collections
_default: 1 collections
_system: 2 collections
```

A simulated previous-run scope `w9101` was then planted through the container's REST API
(`POST /pools/default/buckets/testdb/scopes name=w9101` -> `{"uid":"129"} HTTP 200`) and the
PRE-FIX `wipeCouchbase` body was replayed verbatim against the live container (standalone
program, same SDK, same code, `workerScope=w9199` standing in for this run's own worker):

```
scopes BEFORE wipe: [_default, _system, w19, w20, w21, w22, w23, w24, w25, w26, w9101]
wipeCouchbase(workerScope=w9199) done, present=false
scopes AFTER  wipe: [_default, _system, w19, w20, w21, w22, w23, w24, w25, w26, w9101]
```

The wipe leaves every previous-run scope, including the planted one, exactly where it was.

## 2. Per-backend boundedness

The same per-worker-namespace scheme is used for EVERY reused container, and the wipe is
the same shape everywhere: it only touches its own namespace. Measured 2026-08-01 11:06
on the live containers (count of abandoned worker namespaces):

| Backend | Namespace | Wipe today | Bounded? | Measured debris |
| --- | --- | --- | --- | --- |
| SQLite | in-memory, per JVM | n/a | **yes** (dies with the JVM) | 0 |
| DuckDB | in-memory, per JVM | n/a | **yes** (dies with the JVM) | 0 |
| PostgreSQL | database `testdb_wN` | DROP+CREATE own | **no** | 75 databases |
| MySQL | database `testdb_wN` | DROP+CREATE own | **no** | 68 databases |
| CockroachDB | database `postgres_wN` | DROP+CREATE own | **no** | 68 databases |
| MongoDB | database `testdb_wN` | drop own | **no** | 40 databases |
| Firebird | database FILE `/firebird/data/testdb_wN` | table-by-table wipe of own | **no** | 68 files, ~165 MB |
| Couchbase | scope `wN` in bucket `testdb` | drop own scope | **no** | 9 scopes / 59 collections (8h old container) |

Couchbase surfaced first only because its debris is the most expensive kind: every
collection carries a primary index, and index/collection metadata operations degrade
until `CREATE PRIMARY INDEX` blows the 75s client timeout and whole classes fail at
`initializationError`. The other five are the identical bug in cheaper clothing.

## 3. The fix, and why this shape

**Chosen: ownership is encoded IN the namespace name, as the owning JVM's pid, and the
sweep collects every namespace whose owner is no longer running.**

`WORKER` becomes `"w" + gradleWorkerId + "_p" + ProcessHandle.current().pid()`, and a new
package-private classifier decides collectability:

```java
static boolean isAbandonedWorkerNamespace(String suffix) // "wN" -> true, "wN_pPID" -> !alive(PID), anything else -> false
```

Each backend's wipe now also runs a best-effort sweep, under the machine-wide container
lock the class already maintains, so parallel forks do not storm the backend with
duplicate drops.

Why this and not the two shapes named in the brief:

- **Per-run token (brief's shape 1).** It answers "which run made this", not "is that run
  still going", so a crashed or aborted run's namespaces are indistinguishable from a live
  sibling's and are never collectable. It also has to be MINTED by the build: a Gradle
  `Test.systemProperties` entry is an `@Input`, so a per-invocation random token would make
  the test task permanently out-of-date and defeat zenit-dev's content-based freshness (the
  workaround, an `@Internal` `CommandLineArgumentProvider`, is extra build machinery for a
  weaker guarantee). Pid ownership needs no build change at all, and OS process liveness is
  a fact rather than an inference.
- **Once-per-suite sweep from `zenit-dev` (brief's shape 2).** zenit-dev is genuinely in the
  right POSITION (it holds the machine-wide suite lock, the one moment when no worker
  namespace is live), but it is the wrong HOME: it is a stdlib-only node CLI that today
  knows only about Docker containers, and this sweep needs per-backend credentials and
  protocols (JDBC to five dialects, the Couchbase REST/SDK surface, the Firebird image's
  file layout). That knowledge already lives in `TestDatasources` and would have to be
  duplicated and kept in step. It would also only protect runs launched through zenit-dev.
  Decisive point: the exclusive-lock moment is only NEEDED if ownership cannot be decided;
  pid ownership decides it, so the privileged position buys nothing. (`tools/zenit-dev` and
  `tools/zenit-dev.test.js` are also under another agent's hand right now and were not
  touched.)

Safety argument for the direction that matters: a live sibling fork's namespace carries
that fork's pid, and that process is by construction alive, so `isAbandonedWorkerNamespace`
returns false for it. There is no timing window and no heuristic. Pid reuse can only make
the sweep SKIP debris (collected on a later run); it can never drop a live namespace. The
sweep is gated on a LOCAL Docker daemon (`DOCKER_HOST` unset or `unix://`), because local
pids say nothing about a remote host's namespaces. A `wN` name with no pid can only have
been written by pre-fix code, so it is unconditionally debris — that is what collects the
existing backlog.

Trade-off accepted: every run now creates a fresh namespace per worker instead of reusing
the previous run's, because the name changes with the pid. That is one CREATE DATABASE (or
one Firebird file, or one Couchbase scope) per worker per run — the wipe already dropped
and recreated that namespace anyway — and the sweep keeps the steady state flat.

## 4. Proof

Verification run (after two other sessions' in-flight breakage in `HttpConduit.java` and
`CouchbaseDatasource.java` was fixed by their owners — see "Interference" below):

```
zenit-dev test --unit --class WorkerNamespaceSweepTest,AtomicUpdateTest --no-fail-fast
  -> RESULT: PASSED — 50 unit passed (7 skipped), log 20260801-111222, 156s
     AtomicUpdateTest: 41 passed, 7 skipped · WorkerNamespaceSweepTest: 2 passed
```

`AtomicUpdateTest` is an `allDatasources()` class, so the renamed namespaces were exercised
on all 8 backends in the same run. `WorkerNamespaceSweepTest` did not exist before this
change, so its presence in the run is itself proof that the build recompiled the edit rather
than reporting a stale artifact.

### Direction A — a previous run's namespace IS collected

Container-level, from the run's own log (each line is a real sweep against a live container):

```
TestDatasources: dropped 75 abandoned worker database(s)                (PostgreSQL)
TestDatasources: dropped 68 abandoned worker database(s)                (MySQL)
TestDatasources: dropped 68 abandoned worker database(s)                (CockroachDB)
TestDatasources: removed 68 abandoned worker Firebird database file(s)
TestDatasources: dropped 40 abandoned worker MongoDB database(s)
TestDatasources: dropped 9 abandoned worker Couchbase scope(s)
```

The nine Couchbase scopes are `w19`-`w26` plus `w9101`, the scope planted for the pre-fix
counterfactual in section 1 — the same scope the pre-fix wipe demonstrably left behind.

Inventory before (11:06) and after (11:15) that single run:

| Backend | before | after |
| --- | --- | --- |
| Couchbase | 9 abandoned scopes / 59 collections | 0 (`_default`, `_system`, `w5_p1014132`) |
| PostgreSQL | 75 abandoned databases | 0 (`testdb_w5_p1014132`) |
| MySQL | 68 | 0 (`testdb_w5_p1014132`) |
| CockroachDB | 68 | 0 (`postgres_w5_p1014132`) |
| MongoDB | 40 | 0 (`testdb_w5_p1014132`) |
| Firebird | 68 files, ~165 MB | 0 (`testdb_w5_p1014132`), 3.8 MB total |

In every backend exactly one namespace remains: the one the run's own fork created.

### Direction B — a concurrent sibling's namespace is NOT collected

Asserted by `WorkerNamespaceSweepTest`, both at the classifier and against the real bucket:

- `namespaceOwnershipJourney` — this fork's own namespace and a `w4321_p<live pid>` sibling
  are both refused; a `w4321_p<exited pid>` and a pre-ownership `w7` are both collectable;
  `_default`, `_system`, `testdb`, `mysql`, `worker`, `w`, `wx_p1` are all refused.
- `couchbaseScopeSweepJourney` — plants two scopes in the real `testdb` bucket, one owned by
  an exited process and one owned by a live one, runs the REAL sweep
  (`TestDatasources.sweepAbandonedCouchbaseScopes`), and asserts the abandoned one is in the
  dropped list, the live one is not, only the live one remains, and a SECOND sweep still
  collects nothing. That second sweep is the line `dropped 1 abandoned worker Couchbase
  scope(s)` at log line 314.

The independent confirmation is the inventory itself: the sweep ran from inside a live fork
whose own scope was `w5_p1014132`, and that scope survived, after which the Couchbase cases
of `AtomicUpdateTest` ran green in the same JVM.

## 5. What changed, concretely

- `zenit/src/test/java/be/elevenways/zenit/orm/TestDatasources.java`
  - `WORKER` now carries the owning JVM's pid.
  - New `isAbandonedWorkerNamespace` (package-private, the whole ownership decision),
    `canSweepAbandonedNamespaces` (local-daemon gate), `sweepUnderContainerLock`
    (best-effort, serialized on the existing cross-process lock), `sweepAbandonedDatabases`
    (the shared SQL drop loop), `sweepAbandonedCouchbaseScopes`,
    `sweepAbandonedFirebirdDatabases`.
  - Sweeps wired into all six container backends' existing once-per-JVM wipes.
  - `FIREBIRD_DATA_DIR` constant replaces two hardcoded `/firebird/data` literals.
  - Two package-private accessors (`couchbaseContainer`, `workerNamespace`) for the test.
  - A class-level `AIDEV-NOTE` recording the leak, the rejected naive fix, and why pid
    liveness is the safe rule.
- `zenit/src/test/java/be/elevenways/zenit/orm/WorkerNamespaceSweepTest.java` (new) — the
  two behaviour journeys above.

No production code touched; this is test infrastructure only.

Commit: **`3e38756`** — `🧹 Collect test namespaces owned by dead worker JVMs`
(subject verified standing alone, 50 chars, one body line).

## 6. Known limitations

- Pid reuse can make the sweep skip one piece of debris; it is collected on a later run.
  It can never cause a live namespace to be dropped.
- The sweep stands down entirely when `DOCKER_HOST` points at a non-local daemon, because
  local pids say nothing about namespaces created on another machine. That configuration
  already collides on namespace names today, so nothing regresses.
- Inside a container/CI, pids are namespace-local; a per-job container makes this moot, and
  zenit-dev's `ci` exemption already skips container maintenance there.
- Each run now creates a fresh namespace per worker rather than reusing the previous run's.
  The steady state is flat because the sweep collects the old one in the same breath.

## 7. Interference from concurrent sessions (not attributable to this change)

The first verification attempt (log `20260801-110927`) failed at `:compileServerJava` with
four errors in two files this session never touched:
`server/http/HttpConduit.java` (an `import java.util.Locale` clashing with protoblast's
`Locale`, an uncommitted in-flight edit) and `server/orm/CouchbaseDatasource.java:1056`
(`@NonNull com.couchbase.client.java.Collection` — a TYPE_USE annotation in an illegal
position on a qualified type, reproduced standalone with javac 25.0.3 both with and without
the Manifold plugin). Both were repaired by their own sessions within three minutes and the
re-run was green. Neither file was edited here.

