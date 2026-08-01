# Verification-tooling fixes — 2026-08-01

Commit: zenit `27b76bd` ("🩹 test-log: a run's counts come from its own log"),
files `zenit/tools/zenit-dev` + `zenit/tools/zenit-dev.test.js` only.
Node suite: **45/45 pass** (was 41; 4 regression tests added).

---

## Defect 1: `test-log --run N` printed the latest run's results under an older run's metadata — FIXED

### Root cause (read from code, not inferred)

`doTestLog` summary mode had TWO sources:

- metadata: header lines of the selected log (`resolveTestLog(runIndex)`) — correct;
- results: `loadSavedResults('test'/'browserTest')`, which reads
  `<PROJECT_ROOT>/build/zenit-test-results-<type>.json` — a per-PROJECT
  "latest saved run" file keyed on the CWD, with no run index and no project
  check.

So the counts were always the newest saved run of whatever project you stood
in — even across projects and phases (an orcono browser run displayed zenit's
unit totals). Not a caching bug, not a latest-pointer bug: a structural
two-source design.

### Fix (one source by construction)

Every run already appends its own `== Parsed <label> test results ==` block to
its log at run time. The summary now extracts and prints those blocks from the
selected log itself (`parsedResultSections`), and `loadSavedResults` is gone
from `doTestLog` entirely — counts and metadata cannot come from different
files anymore. A log with no parsed block (run died before parsing) says so
honestly instead of borrowing numbers. An AIDEV-NOTE at the parser forbids
reintroducing `loadSavedResults()` in `doTestLog`.

### Sibling paths audited

- `--full`, `--grep`, `--method`: **NOT affected** — they always read
  `resolveTestLog(runIndex)` directly. Verified `--grep` against a historical
  run post-fix (searched the selected file).
- **default no-argument invocation: WAS affected** — it is summary mode with
  run 1, so whenever log #1 was another project's run (or an unsaved/failed
  run), it printed the CWD project's stale saved numbers. Fixed by the same
  change. Proven: default invocation over a FAILED filtered run now prints
  "No parsed results were recorded", not protoblast's 2825.
- `--list`: header-only, never affected.
- `doStatus` still uses `loadSavedResults` — correct there: its semantic IS
  "this project's latest run".

### Verbatim proof (real historical logs, untouched)

BEFORE (cwd = zenit; zenit's saved file said 2075/284):

    ── Test log: 20260801-191156 ──        # mvp-v01, --browser, 62s
      ✓ 2075 unit tests passed
        (75 skipped, 284 of 284 test classes)     <- WRONG: raw log says "✓ 38 browser tests passed"

    ── Test log: 20260801-194408 ──        # protoblast, full, 71s
      ✓ 2075 unit tests passed
        (75 skipped, 284 of 284 test classes)     <- WRONG: raw log says 2825 unit + 14 browser

AFTER (same logs, same cwd):

    ── Test log: 20260801-191156 ──
      ✓ 38 browser tests passed
        (8 of 8 test classes)

    ── Test log: 20260801-194408 ──
      ✓ 2825 unit tests passed
        (2 skipped, 230 of 219 test classes)      <- as recorded in that log (see defect 2b)
      ✓ 14 browser tests passed
        (14 of 14 test classes)

The zenit run (193701) still shows its own 2075/284 — unchanged where the old
code happened to be accidentally right.

## Defect 2: `test --class` could not reach `protoblast-compile` — FIXED

### Root cause and scope

Submodule-to-task mapping was HAND-LISTED in three places
(`testTaskForClass`, `getGradleTestTask`, `getTestClassesDirs`): the lists
knew `hawkeye-lsp`, `protoblast-gradle-plugin`, `zenit-gradle-plugin` and
missed `protoblast-compile`, so its classes fell through to the root `:test`
task and matched zero. **Affected modules: exactly one — `protoblast-compile`**
(workspace sweep: the only repo submodules with test source sets are
hawkeye-core, hawkeye-lsp, protoblast-compile, protoblast-gradle-plugin,
zenit-gradle-plugin; all others were mapped). It was a hand-list gap, not a
general filtering-engine gap — but the hand-list design is what allowed it.

### Fix (derivation kills the class of bug)

New helpers `includedGradleModules(projectRoot)` (parses settings.gradle
`include(...)`) and `submoduleOwningTestClass(projectRoot, testType, cls)`
(locates `<module>/src/{test|browserTest}/**/<cls>.java`). All three former
hand-lists now ride them; a future submodule with tests is targetable with no
tool change. Root-source-set special cases kept (zenit `artifactContractTest`,
hawkeye's no-root-tests default, the `:test`-not-`test` no-match-poisoning
doctrine). Verified the previously mapped paths resolve identically via
derivation (hawkeye-lsp `DocumentAnalyzerTest`, hawkeye-core browser,
zenit-gradle-plugin functional test, protoblast-gradle-plugin
`BlastCompileGuardTest`; root-owned classes map to no module).

### Verbatim proof

BEFORE:

    $ zenit-dev test --unit --class BlastAutoLoadScanToleranceTest --no-fail-fast
      fail  no unit tests matched the filter [*.BlastAutoLoadScanToleranceTest] in any module
      ✗ RESULT: FAILED — no unit tests matched the filter

AFTER (both incident classes, actually executing, per-test lines shown):

    $ zenit-dev test --unit --class BlastAutoLoadScanToleranceTest,BlastAutoLoadIndexTest --no-fail-fast
      BlastAutoLoadIndexTest      ✓ x6
      BlastAutoLoadScanToleranceTest
        ✓ missingIndexFileReadsLoudNotEmpty ... ✓ archiveReplacedMidScanRetriesOnceThenFailsLoud
      ✓ 10 unit tests passed
        BlastAutoLoadIndexTest: 6 passed · BlastAutoLoadScanToleranceTest: 4 passed

### 2b. Bonus accuracy fix

`getTestClassesDirs` now derives submodule class dirs too, so protoblast full
runs stop reporting impossible denominators. Historical log recorded
"230 of 219 test classes"; a fresh full run post-fix reports
**2825 passed, 230 of 230** (30s, green) — which also proves the unfiltered
path end-to-end after the task-resolution changes.

## Cheap fix: `verify-head` skip wording — FIXED

BEFORE: `ok  zenit-auth-test-support: not a git repository`
AFTER:  `skip  zenit-auth-test-support: not a git repository — nothing compiled`

`verifyHeadForRepo` marks no-git/no-head returns `skipped: true`; `doVerifyHead`
prints them with a new `logSkip` label. `ok` now appears only with a real
compile verdict (invariant documented in an AIDEV-NOTE).

## Regression tests added (node suite, 41 -> 45, all green)

1. `test-log --run N reports the selected run from its OWN log, never the
   latest saved results` — fixture workspace, three logs (older unit run,
   newer browser run, newest failed run) + a poisoned
   `build/zenit-test-results-test.json` (9999s) that must never render;
   asserts per-run numbers, cross-run isolation, and the honest no-results
   message on the default invocation.
2. `submodule test ownership is DERIVED from settings.gradle, never
   hand-listed` — fixture repo with two modules + root tests, plus the exact
   regression pinned against the real workspace
   (`protoblast-compile` owns both incident classes).
3. `test --class routes a submodule-owned class to that module's test task` —
   end-to-end fixture run; asserts the gradle invocation carried
   `:engine:test --tests *.EngineTest` and the run reported the pass.
4. `verify-head prints skip, not ok, for a target it never compiled`.

## Environment notes

- Leaked Gradle test worker PID 2330253 (zenit-auth browserTest, 2026-07-31)
  and its wedged daemon PID 2328661 (217 MB RSS): **reaped**. SIGTERM was
  ignored by both (confirming the wedge); SIGKILL removed them. Parentage
  verified (worker's PPID was the daemon); no other suite or zenit-dev run
  was active. ~250 MB reclaimed.
- Testcontainers: untouched, per instruction.
- Evidence handling: no log was deleted or edited. My own test runs consumed
  rotation slots (testLogMax 10), so 190830 and 191156 rotated out AFTER
  their before/after evidence was captured verbatim above; rotation is the
  tool's own behavior on every run.

## Limitations / notes

- Historical logs print their numbers AS RECORDED (e.g. protoblast's
  "230 of 219") — the log is immutable evidence; only future runs get the
  corrected denominator.
- `includedGradleModules` parses `include('a', 'b')` string forms
  (settings.gradle and .kts); exotic programmatic includes would not be seen —
  none exist in the workspace, and the fallback is the old root-task behavior,
  never a wrong module.
- Commit `27b76bd` verified with `git log -1`: gitmoji subject, body on
  separate lines, 3 lines total.
