# Testcontainer lifecycle: failure-mode inventory, redesign, and proof

Date: 2026-08-01. Repos touched: `zenit` (test infrastructure + `tools/zenit-dev`).
Predecessor evidence: `couchbase-scope-leak.md` and `baseline-brandtest.md` in this
directory (measurements referenced below are theirs unless dated today).

## 1. Failure-mode inventory (with evidence)

Journal basis: 5,660 `test.phase` entries 2026-07-10 -> 2026-08-01 (live journal +
the gzipped archive). 75 of them are FULL zenit unit-suite runs; 39 of those 75 were
non-green. Detailed logs are pruned at `testLogMax=10`, so per-failure classification
older than 07-31 relies on the journal shape + the two saved reports; that limitation
is stated where it applies.

| # | Mode | How it PRESENTS (the misdiagnosis surface) | Evidence / frequency | Blast radius |
|---|------|--------------------------------------------|----------------------|--------------|
| 1 | Namespace debris in reused containers (every backend) | Couchbase query/index degrades until `CREATE PRIMARY INDEX` blows 75s; whole `@ParameterizedClass` backends die at `initializationError`; suite TOTAL shrinks (2056 -> 2019/1848/1752) so runs are not even comparable. Reads exactly like a code regression. | Measured 08-01: 75 Postgres + 68 MySQL + 68 Cockroach + 40 Mongo dbs, 68 Firebird files (~165 MB), 57 scopes/341 collections Couchbase (4-day container). Runs 00:33 (19 fail), 00:42 (32 fail). Growth rate: one namespace per worker per run, forever. | 19-32 "failures" per run; multiple full debugging sessions lost |
| 2 | Cold-start cascade | First class gets `ContainerLaunchException`; every LATER class gets the secondary `Mapped port can only be obtained after the container is started` because the shared container field was assigned BEFORE `start()`. 7+ classes of unrelated-looking noise. | Run 20260801-024411: 9 failures across 8 classes from ONE failed Couchbase start. Historical: cold Couchbase used to blow the 3-min startup wait (now 6 min). | one incident = up to a whole backend's classes |
| 3 | Container present but sick (no health gate) | Nothing checked a reused container before the suite; the first evidence of a wedged backend was test failures. Same presentation as #1. | The 4-day Couchbase incident IS this mode compounded with #1; docker healthcheck exists only on the Firebird image. | whole backend |
| 4 | Dead-container corpses + duplicate storms | Historically: fresh container started on top of a corpse; N forks racing all created their own (8 mysql + 8 mongo + 8 firebird found once), machine-wide slowdown + Couchbase contention failures. | Already closed earlier (cross-process lock + `sweepDeadContainers` + zenit-dev newest-live-wins); AIDEV-NOTEs in `TestDatasources` record the counts. | machine-wide |
| 5 | Writable-layer growth (NEW, found today) | Silent until the disk fills; the disk filling presents as ANYTHING (builds fail, containers die, gradle daemon crashes). | CockroachDB container: **2.09 GB SizeRw after 6 days** — its store lives in the container overlay, and nothing ever shrank or bounded it. Firebird was the same class (fixed by the file sweep). | machine-wide disk |
| 6 | Orphaned anonymous volumes (NEW, found today) | Invisible: `docker rm` without `-v` (manual interventions, exactly what incident response does) leaks the data volume forever. | **6 dangling anonymous volumes, 2.3 GB** (one alone 2.295 GB — almost certainly the old Couchbase's var volume from yesterday's manual removal). | disk |
| 7 | Gradle build-cache churn (NEW, found today; not a container, but THE disk-100% suspect) | Root fs at 94% (317G/353G); prior session hit 100%. | `~/.gradle/caches/build-cache-1` = **39 GB with ZERO files older than 7 days** — default retention is working; the workspace just churns that much per week. `~/.gradle` total 53 GB. | machine-wide; disk exhaustion masquerades as every other failure mode |
| 8 | Concurrent-session interference | Another session's mid-write edit of `tools/zenit-dev*` failed `:zenitDevTest` inside a green suite (`CORE_PROJECTS is not iterable`). Suite lock does not cover source files. | 08-01 baseline report; 3 node-test failures. | one task, but poisons the RESULT banner of an otherwise green run |
| 9 | Reuse silently off / owner divergence | If `~/.testcontainers.properties` lacks `testcontainers.reuse.enable=true`, or the Java/node owner strings diverge, a parallel container set appears that maintenance never sees. | Config verified present today; divergence documented in AIDEV-NOTE; no live occurrence in the journal window. | slow runs / duplicate containers |
| 10 | Image tag drift | `mysql:8.0`, `mongo:7` are mutable tags; a registry update changes the reuse hash and forces a new container beside the old. | Not observed in the journal window; noted as latent. | one-off cold start |

Modes 1 and 4 were already fixed (commits `3e38756` and earlier); this session
verified 3e38756's properties still hold (`WorkerNamespaceSweepTest` green today)
and did NOT redesign it — the pid-liveness ownership rule is the model the new
eviction policies follow.

## 2. The lifecycle model chosen

Principle: **the generic mechanism lives where its knowledge lives.** zenit-dev
knows Docker and holds the machine-wide suite lock -> it owns container-level
lifecycle (existence, health, eviction, host debris). TestDatasources knows the
per-backend protocols -> it owns namespace lifecycle and the in-JVM failure story.
`withReuse(true)` stays: cold-starting every run costs ~2.5-3 min + the measured
cold-start failure mode, and the 5-minute budget rules it out.

Ownership map after this session:

- **Creation**: TestDatasources, under the cross-process container lock (unchanged).
- **Health gating**: zenit-dev preflight, every `test` invocation, while holding the
  suite lock (i.e. provably no cooperating suite is mid-flight): an owned running
  container is evicted when (a) Docker itself reports it `unhealthy`, (b) any
  published port refuses TCP within 3s, or (c) its writable layer exceeds 3 GB.
  Every check is objective — no age heuristics. Eviction is journaled
  (`containers.evict`) and printed with the reason.
- **Cleanup**: namespace sweep by pid-liveness (3e38756, unchanged); dead/duplicate
  container reaping (unchanged); NEW: anonymous dangling volumes (64-hex name,
  dangling, older than 1h) are pruned under the same lock — safe by construction:
  a volume in use is never dangling, a 64-hex id is never reattached on purpose,
  and the age floor covers the create-attach window of a concurrent non-zenit run.
- **Eviction of state at the source**: CockroachDB now runs
  `--store=type=mem,size=2GiB` — test data is scratch (worker dbs are
  drop+recreated per JVM and swept on owner death), so the 2 GB/6-days overlay
  growth is eliminated rather than policed. The command change changes the reuse
  hash; newest-live-wins retires the old disk-store container automatically.
- **Failure presentation (in-JVM)**: TestDatasources now assigns the shared
  container fields only after start+wipe fully succeed, and LATCHES the first
  failure per backend. Every later acquisition throws the same
  `TestInfrastructureException` in microseconds: "TEST INFRASTRUCTURE FAILURE
  (backend): ... NOT a code regression ... Root cause: <first cause>". No retry
  (a Couchbase retry is 6 minutes per class), no secondary-error cascade.
- **Failure presentation (suite level)**: zenit-dev classifies every failure
  against container-failure signatures (`TEST INFRASTRUCTURE FAILURE`,
  `ContainerLaunchException`, `Mapped port can only be obtained`, Couchbase
  timeout/index shapes, unreachable Docker daemon) and prints a red banner:
  "INFRASTRUCTURE, NOT CODE: N of M failures match test-container failure
  signatures" + the reasons + the inspect command. The count also lands in the
  journal (`test.phase.infraSuspect`), so flake-vs-regression is answerable from
  the journal alone, historically.
- **Build-cache bound**: the zenit-dev managed init script now pins the local
  Gradle build cache to 2-day retention (`settings.caches`), hung off every
  invocation — no scheduler.

Rejected alternatives:
- *Cold start per run* — violates the 5-minute budget (containers alone ~2.5-3 min)
  and reintroduces mode #2 at every run.
- *zenit-dev doing namespace sweeps* — rejected in the 3e38756 analysis (wrong
  home for per-backend protocol knowledge); still stands.
- *Deep per-backend health probes in zenit-dev* (SQL SELECT 1, Couchbase
  /pools poll) — zenit-dev is stdlib-only node; protocol probes belong to the
  JVM side, where the wipe already IS the probe (it connects, authenticates and
  mutates before any test runs, and its failure is now latched + named).
- *Age-based eviction (e.g. recreate Couchbase weekly)* — heuristic, pays cold
  starts without evidence of sickness; the objective probes + debris sweep remove
  the reasons age correlated with sickness.
- *Size-policing volumes* (Couchbase/MySQL/Mongo data live in volumes, not the
  layer) — needs `docker system df -v` (expensive) and the namespace sweep already
  bounds their contents; revisit only if a volume is ever measured growing.

## 3. What changed, concretely

zenit repo:
- `src/test/java/be/elevenways/zenit/orm/TestDatasources.java` — infra-failure
  latch (`INFRA_FAILURES`, `infrastructureFailure`, `failFastIfBroken`); all six
  container getters assign their shared field only on full success; CockroachDB
  in-memory store. AIDEV-NOTEs record the cascade shape and the 2.09 GB measurement.
- `src/test/java/be/elevenways/zenit/orm/TestInfrastructureException.java` (new).
- `src/test/java/be/elevenways/zenit/orm/TestInfrastructureLatchTest.java` (new) —
  5-step journey: clean gate passes; first failure latched with backend name +
  "NOT a code regression" + cause; later failures repeat the FIRST cause; gate
  fails fast; latch is per-backend.
- `tools/zenit-dev` — preflight (health/port/size eviction + reasons + journal),
  anonymous-dangling-volume pruning, `classifyInfraFailures` + INFRASTRUCTURE
  banner + `infraSuspect` journal field, build-cache retention in the managed
  init script, `net` import, new exports.
- `tools/zenit-dev.test.js` — 2 new node tests (signature classification incl.
  stack-line matching and the assertion-failure negative; port extraction +
  anonymous-volume name rules).

One-time debris removed today (measured): 6 anonymous dangling volumes (2.3 GB),
2 exited hohenheim test-container corpses (6d/2w old). NOT touched: `qq-postgres`,
`orcono-pg`, `mongodb` + named volumes (live/user data), all images (re-pull cost).

## 4. Verification

Build-staleness guard: both new test classes (`TestInfrastructureLatchTest`,
`WorkerNamespaceSweepTest`) executed in the runs below — a stale artifact cannot
contain them, so every green result is from freshly compiled edits.

### Targeted proofs

| Proof | Run | Result |
|---|---|---|
| Latch semantics (5-step journey: clean gate, first-cause latch, first-cause-wins on later errors, fail-fast gate, per-backend isolation) | `TestInfrastructureLatchTest` (log 20260801-113447) | PASSED |
| 3e38756 properties still hold after the getter restructure | `WorkerNamespaceSweepTest` same run | PASSED (both journeys) |
| Mem-store CockroachDB works across the dialect surface | `AtomicUpdateTest` all 8 backends | PASSED 48 |
| Infra-signature classifier (incl. stack-line matching, assertion-failure negative) + port/volume helpers | `node --test tools/zenit-dev.test.js` | 30/30 PASSED |
| `settings.caches` retention API valid on installed Gradle | javap on 8.8/8.14/9.6 core-api jars + real Gradle runs post-change | OK |

### Full-suite stability, before vs after

BEFORE (the incident, journal 2026-08-01 00:10-00:55, identical code between runs):

| Run | total | failed | wall | cause |
|---|---|---|---|---|
| 00:10 | 2056 | 0 | 293s | (healthy baseline) |
| 00:33 | 2019 | **19** | **651s** | Couchbase debris (57 scopes) |
| 00:42 | 1848 | **32** | 506s | Couchbase debris |
| 00:48 | 1752 | **9** | 278s | Couchbase cold-start cascade |
| 00:55 | 2056 | 1 | 352s | BrandTest (real defect, fixed separately) |

4 of 5 runs poisoned; totals not even comparable; wall time doubles under
Couchbase timeouts. Wider context: 75 full zenit unit-suite runs since 07-10,
39 non-green (mix of in-flight dev work and infra; per-failure logs pruned, so
the exact infra share of the older 39 is not recoverable — stated as such).

AFTER (today, post-change, all with `--rerun --no-fail-fast`; ~5 other agent
sessions were building/testing concurrently the whole time):

| Run | total | failed | infraSuspect | wall | condition |
|---|---|---|---|---|---|
| 1 (11:43) | 2070 | **0** | 0 | 308s | warm containers |
| 2 (11:49) | 2070 | 1 | 0 | **294s** | **deliberate Couchbase cold start** (container removed with -v beforehand) |
| 3 (11:56) | 2062 | 1 | 0 | 254s | warm, post-commit |

- Run 2 absorbed a full Couchbase cold start INSIDE a 294s suite — no
  `ContainerLaunchException`, no cascade, no failed backend.
- The two single failures are DIFFERENT async-timing flakes
  (`WebSocketAdmissionHttpTest.contextualParametersResolveOnlyAfterTheHandshakeGates`,
  `ChannelProtocolTest.abruptAnonymousDropReopensFresh`), each re-run green in
  isolation, each correctly NOT claimed by the infra classifier
  (`infraSuspect: 0`). They are code-side flakes surfaced for a separate issue
  (see section 6).
- Three runs is the honest maximum the machine allowed today (one suite at a
  time, five sessions competing); the discriminating measurement is not N
  repeats but the before/after on the SAME failure modes: the debris mode and
  the cold-start mode were each exercised deliberately and produced zero
  infrastructure failures.

### Misdiagnosis-guard proof (layered)

1. The latch produces the exact message `TEST INFRASTRUCTURE FAILURE (backend):
   ... NOT a code regression ... Root cause: ...` — asserted by JUnit.
2. The classifier fires on exactly that message AND on every historical cascade
   shape (Mapped port, ContainerLaunchException via stack lines, Couchbase
   timeout/index messages) and does NOT fire on a plain assertion failure —
   asserted by node tests.
3. Failure messages reach the parsed failure list — proven by history (run
   024411's Mapped-port messages appear in parsed summaries; the pipeline is
   unchanged).
4. Live negative control: runs 2 and 3's real code-side flakes were NOT
   bannered.

A fully end-to-end banner demo (pause Couchbase, watch the banner) was
attempted twice and was IMPOSSIBLE to reach — because a concurrent session's
zenit-dev maintenance evicted the paused container within seconds both times
and the suite cold-started green. That failure to demonstrate the failure is
the system working: eviction now happens at whichever cooperating invocation
notices first, and a warm-cache Couchbase cold start measured ~60-90s.

### Resource footprint, before -> after (today)

| Metric | before | after |
|---|---|---|
| Abandoned worker namespaces (all backends) | 328 dbs/scopes/files at 11:06 | **1 per backend, all pid-owned, swept on owner death** |
| Container writable layers (owned set) | 2.088 GB (2.09 GB = CockroachDB) | **9.3 MB** |
| Dangling volumes | 6 (2.3 GB) | **0** |
| Exited container corpses | 4 | 2 (both the user's own `qq-postgres`/`orcono-pg`, untouched) |
| Root filesystem | 317G used (94%) | 312G used (93%); build-cache retention converges further over the next 2 days |
| CockroachDB RAM (new cost) | n/a | 1.13 GiB of 40 GiB (store capped 2 GiB) |

Commits (each verified standing alone, one-line subject):
- `fcac65c` 🩺 Latch container failures as named infrastructure errors
- `e72bd3f` 💾 Run CockroachDB tests on a bounded in-memory store
- `724a3b9` 🛂 Preflight testcontainers and name infrastructure failures
- `15ef23e` 🗜️ Bound the local Gradle build cache at two days

Worktree verified byte-identical to the tested state after the split commits
(`cmp` against HEAD).

## 5. Surfaced but out of scope (needs its own issue)

Two async-timing flakes in the HTTP/WS area, each seen once under full machine
load and green in isolation:
- `WebSocketAdmissionHttpTest.contextualParametersResolveOnlyAfterTheHandshakeGates`
  (step 4 expected "row:known").
- `ChannelProtocolTest.abruptAnonymousDropReopensFresh` (CompletableFuture
  timeout at ChannelProtocolTest.java:238).
These are code-side (likely fixed-timeout waits under CPU contention), not
containers; the classifier correctly refused them, which is exactly the
distinction this session was asked to make structurally possible.

## 6. Deliberately NOT fixed

- **`~/.gradle/caches/build-cache-1` beyond retention tightening** — 39 GB was
  within Gradle's default 7-day policy; the 2-day bound is the structural fix. If the
  owner wants the backlog gone NOW: `rm` is not needed — the next builds after the
  init-script change let Gradle's own cleanup converge to the 2-day window.
- **Mutable image tags** (mode #10): pinning digests would freeze security fixes
  and the blast radius is one cold start; not worth the churn.
- **Mode #8 (mid-write source reads)**: a worktree-ownership problem between
  agents, not a container problem; out of scope here.
- **Remote-daemon (`DOCKER_HOST`) setups**: the namespace sweep already stands
  down there (documented in 3e38756); the zenit-dev preflight TCP probe assumes
  localhost-published ports, which matches every configuration this machine uses.
