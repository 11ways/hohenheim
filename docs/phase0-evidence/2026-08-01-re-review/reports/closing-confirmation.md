# Closing confirmation — 2026-08-01

Closing agent. Every result below was CAUSED by this agent unless labelled
"journal" (read from `zenit-dev journal` / saved test logs) or "prior evidence".
Nothing was committed. No source file was left modified.

---

## VERDICT

**GREEN. Nothing found must be blocked from shipping.**

All four post-gate commits are confirmed good at their final HEADs:

- **protoblast `2791d27`** (autoload scan validation, base of chain) — **zero
  false refusals anywhere.** A forced rebuild of the entire 11-repo dependency
  chain plus zenit-cms, a clean orcono rebuild, and six other repos' builds all
  passed with no refusal message. TeaVM bundles regenerated green (`cms.js`
  18,679,044 bytes, `orcono-client.js`). A planted probe proves a legitimately
  ABSENT optional dependency is dropped silently while a PRESENT one is emitted.
- **zenit `61c5590`** — unit suite re-run fresh AFTER the commit: 2075 passed,
  0 failed, 284 of 284 classes. Total went UP, not down.
- **plumage `d78ebb7`** — browser suite now **120/120** (was 119/120).
- **zenit `f1447b9`** (lean dep republishes) — live and correct; `lean` flags
  present in the publish state, self builds promote them.

Consumer loaders are COMPLETE: zenit-cms browserTest — the exact file that
carried the incident (widget=0) — is **128 entries, widget=17**, and its suite
is 49/49.

### Things to know, none of them ship blockers

1. **`zenit-dev test-log --run N` reports the LATEST run's numbers under an
   OLDER run's metadata.** This is a false-green generator of exactly the class
   that bit today. Proof below (section 3a). Tooling only; nothing shipped.
2. **`zenit-dev test --class` cannot reach the `protoblast-compile` module at
   all** — including `BlastAutoLoadScanToleranceTest`, the new tolerance
   journeys. They DO run in the full suite (proven from JUnit XML), but
   "targeted tests ARE the verification" has a whole-module blind spot here.
3. The premise "no other agent is running" is **false**: the second `claude`
   session (PID 2509244) is still alive, 1d09h old. It was idle throughout —
   every repo HEAD and worktree is byte-identical before and after my run — but
   it is not gone.
4. A **leaked Gradle test worker from 2026-07-31 01:23** (PID 2330253, zenit-auth
   browserTest, 1d18h elapsed) and its daemon (PID 2328661, 217 MB RSS) are still
   resident. Not touched (no deletions permitted); worth reaping by hand.
5. `verify-head` still prints `ok  zenit-auth-test-support: not a git repository`
   where it should print `skip`. Noted as instructed, **not fixed**.

---

## 1. `zenit-dev verify-head --all` — PASS, exit 0, 1079s

Started 19:15:45, finished 19:33:44. Journal: `verify-head --all  1079s  exit 0`.

28 targets. **27 carry a real per-repo compile duration**; the 28th is the known
cosmetic non-git case. No `ok` without a duration other than that one.

| repo | HEAD | tasks | time |
| --- | --- | --- | --- |
| protoblast | 2791d27 | assemble testClasses browserTestClasses | 27s |
| emberglyph | 4994deb | assemble testClasses | 9s |
| hawkeye | d7bbbf5e | assemble testClasses browserTestClasses | 41s |
| janeway | fd170c9 | assemble testClasses | 7s |
| zenit | 61c5590 | assemble testClasses browserTestClasses | 56s |
| plumage | d78ebb7 | assemble testClasses browserTestClasses | 70s |
| textum | 2cddd9b | assemble testClasses browserTestClasses | 14s |
| zenit-microcopy | 419e489 | assemble testClasses | 27s |
| zenit-oidc | fd14805 | assemble testClasses | 26s |
| zenit-forms | 9225953 | assemble testClasses | 40s |
| zenit-widget | 99a7961 | assemble testClasses | 31s |
| zenit-media | 086f2ba | assemble testClasses browserTestClasses | 41s |
| zenit-a2ui | 9be0592 | assemble testClasses | 20s |
| zenit-flow | f458b2c | assemble testClasses browserTestClasses | 52s |
| zenit-cms | 86413d2 | assemble testClasses browserTestClasses | 56s |
| zenit-pages | a0f2181 | assemble testClasses | 26s |
| zenit-auth | 9022082 | assemble testClasses browserTestClasses | 38s |
| zenit-ai | f2e4c07 | assemble testClasses | 25s |
| zenit-comms | 22f0bbe | assemble testClasses | 29s |
| **zenit-auth-test-support** | — | — | **`ok ... not a git repository` (gate wording defect, not fixed)** |
| duiventil | 9ee22a7 | assemble testClasses | 9s |
| orcono/mvp-v01 | 56b1ada | assemble testClasses browserTestClasses | 50s |
| proteus | 57a8a87 | assemble testClasses | 39s |
| herald | 2b9786d | assemble testClasses | 34s |
| quirkyquarters | 4041c6e | assemble testClasses browserTestClasses | 106s |
| spamservice | 8e5f1cd | assemble testClasses | 119s |
| thoth | 2ee1ee4 | assemble testClasses | 44s |
| hohenheim | 4a4c745 | assemble testClasses browserTestClasses | 39s |

Every HEAD listed matches the HEAD I recorded before the run and after it.

## 2. Clean dependency-chain build — REAL WORK, 155s

`cd zenit-cms && zenit-dev build --clean --force`, 19:34:02 → 19:36:37, wall
**155s**, exit 0. `--force` was deliberate: the whole chain sat on `lean`
publish records, so a plain `build` would have been a fingerprint no-op and
would have proven nothing about the new scan validation.

    ── Dependencies (protoblast → emberglyph → hawkeye → janeway → zenit →
       plumage → zenit-microcopy → zenit-widget → zenit-forms → zenit-media →
       zenit-pages) [forced] ──
      ok  (all 11)
      ...  protoblast-gradle-plugin: hawkeye-compile republished ... repackaging
    ── Building zenit-cms ──
      ...  Clearing generated template sources
      ...  Removing stale TeaVM JS: cms.js
      ok  Build completed in 155s

Real work, verified by artifact: `zenit-cms/public/cms.js` = 18,679,044 bytes,
mtime **19:36**. Loaders regenerated at 19:36.

**Zero false refusals across the chain.** No occurrence of `Missing classpath
entry`, `Unreadable classpath entry`, `Classpath changed while`, or `silently
omit` in any build output — this one, orcono's two clean rebuilds, or textum's.

TeaVM coverage, stated honestly: `f1447b9` makes DEPENDENCY republishes lean, so
a dep repo's own TeaVM bundle is no longer built on the dep path. TeaVM is
therefore covered by the SELF builds instead, and it is covered: zenit-cms
(`cms.js`), orcono (`orcono-client.js`), plus the browser suites in section 4
(protoblast 14, plumage 120, zenit-cms 49, orcono 38 — all run against
TeaVM-compiled bundles).

## 3. zenit unit suite, forced fresh — PASS

`cd zenit && zenit-dev test --unit --no-fail-fast --rerun`
Started **19:37:00**, completed **303s**, wall 304s, exit 0.

Postdates every commit of the day: zenit `61c5590` (18:55:25), plumage
`d78ebb7` (18:55:34), protoblast `2791d27` (19:13:18).

- **total 2150 = passed 2075, failed 0, skipped 75**
- **284 of 284 test classes**
- no exit-code-disagreement banner, no INFRASTRUCTURE banner
- `--rerun` forced the Gradle test task; per-test durations present

Against the last known-good baseline **2148 / 2073 / 0 / 267s (283 classes)**:
**+2 tests, +1 class**, zero failures. The added class is
`SchemalessMigrationNoOpTest` (2 parameterized cases, MongoDB + Couchbase),
introduced by `61c5590`. **No drop in total.**

Wall time is 303s vs the 267s baseline. This run followed a forced full-chain
rebuild (cold daemons, cold containers), so it is not evidence against the
commit's stated speed win; it is also not evidence for it. I did not re-measure
under warm conditions.

### 3a. Reconciling the test agent's "2067/2067" — and a real tooling defect

The test agent's own final run is journal entry `20260801-185024`
(`zenit --unit --no-fail-fast --rerun`, 268s). Read from the RAW saved log:

    ✓ 2067 unit tests passed
      (75 skipped, 282 of 284 test classes)

So that run was **282 of 284 classes** — two classes discovered but not
executed, 8 fewer tests. It also **predates both** zenit `61c5590` (18:55:25)
and protoblast `2791d27` (19:13:18) by 5 and 23 minutes. My run executes
**284 of 284** and is a strict superset. The difference is fully explained: the
agent's run was the incomplete one; nothing is missing now.

Per-test text lines in the two logs are IDENTICAL (2060 lines, same 281 classes,
byte-for-byte on the diff), so the 8-test / 2-class delta lives in results
streamed via NDJSON from the auxiliary Gradle subprojects rather than in the
per-test console lines. I could not attribute the two classes by name from the
saved log alone; my run's console output shows `ZenitGradlePluginFunctionalTest`,
`ArtifactPublicationContractTest` and `DocumentationContractTest` executing.

**The tooling defect found while doing this reconciliation:**
`zenit-dev test-log --run N` prints run N's metadata but the parsed results of
the MOST RECENT run.

| command | metadata printed | results printed | raw log actually says |
| --- | --- | --- | --- |
| `test-log --run 8` | zenit, `--unit --rerun`, 18:50:24, 268s | `2075 ... 284 of 284` | `2067 ... 282 of 284` |
| `test-log --run 2` | mvp-v01, `--browser`, 19:11:56, 62s | `2075 unit ... 284 of 284` | `38 browser tests passed` |

Anyone auditing history with `--run N` gets today's numbers stamped on an older
run. This is precisely the false-green class the day has been fighting. It
changes nothing that ships, but it is a live trap in the verification tooling.

## 4. Targeted suites for the four moved repos

Sequential, one suite at a time (machine-wide suite lock respected).

| repo | command | result | wall |
| --- | --- | --- | --- |
| protoblast | `test --no-fail-fast` | **PASS 2825 unit + 14 browser** | 71s |
| plumage | `test --browser --no-fail-fast` | **PASS 120 / 120** (84 of 85 classes) | 104s |
| zenit-cms | `test --browser --no-fail-fast` | **PASS 49 / 49** (29 of 29 classes) | 89s |
| orcono | `build --clean` then `test --browser --no-fail-fast` | **PASS 38 / 38** | 63s + 60s |

- **protoblast 2825** vs the gate's 2820 = **+5**: the 4 new
  `BlastAutoLoadScanToleranceTest` journeys plus
  `AutoloadScanToleranceFunctionalTest.truncatedDependencyJarFailsTheScanLoudly`.
  Because `--class` cannot reach that module (finding 2), I verified execution
  from the JUnit XML instead:
  `protoblast-compile/build/test-results/test/TEST-....BlastAutoLoadScanToleranceTest.xml`,
  mtime 19:44, `tests="4" skipped="0" failures="0" errors="0"`, cases
  `scanIsLoudAboutUnreadableEntriesAndTolerantOfAbsentDirectories`,
  `archiveReplacedMidScanRetriesOnceThenFailsLoud`,
  `missingIndexFileReadsLoudNotEmpty`,
  `presenceProbeIsLoudAboutMissingArchivesAndTolerantOfAbsentDirectories`.
  The functional test ran and passed under an explicit `--class` filter (5038ms).
- **plumage 120/120** confirms `d78ebb7`. `84 of 85 classes` is correct, not a
  drop: `ShowcaseTest` is `@Tag("slow")` and excluded by default.

### 4a. Loader completeness — the incident signature, re-measured

Counted per contributing module in every generated `BlastAutoLoadInit.java`
after the rebuilds above (all mtimes 19:36–19:51, i.e. after protoblast
`2791d27`):

| repo | source set | total | widget | forms | media | cms | auth | pages | plumage |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| zenit-cms | server | 100 | **17** | 5 | 5 | 8 | 0 | 0 | 1 |
| zenit-cms | test | 108 | **17** | 5 | 5 | 9 | 0 | 5 | 1 |
| zenit-cms | **browserTest** | **128** | **17** | 5 | 5 | 27 | 0 | 5 | 1 |
| zenit-cms | browserTestCommon | 63 | 15 | 3 | 3 | 7 | 0 | 0 | 1 |
| zenit-cms | common | 6 | 0 | 0 | 0 | 5 | 0 | 0 | 0 |
| orcono | server | 127 | **17** | 5 | 5 | 8 | 7 | 0 | 1 |
| orcono | test | 127 | **17** | 5 | 5 | 8 | 7 | 0 | 1 |
| orcono | browserTest | 127 | **17** | 5 | 5 | 8 | 7 | 0 | 1 |
| orcono | client | 85 | 15 | 3 | 3 | 5 | 0 | 0 | 1 |
| orcono | common | 14 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |

`zenit-cms/browserTest` is the file the incident corrupted (108 entries,
**widget=0**). It is now **128 entries, widget=17** — matching the known-good
shape, and its suite is 49/49. The 15-vs-17 widget counts in the TeaVM-facing
`client` / `browserTestCommon` sets are the expected browser subset, unchanged
from the pre-run baseline. `common` legitimately carries no widget entries.
Every count is IDENTICAL to the baseline I captured before starting.

## 5. Guards — all four fire or defer correctly; every probe reverted

`git status` on the probed repos was EMPTY before each plant and EMPTY after
each removal, checked inline every time. Every probe was a NEW UNTRACKED file.
No tracked file was touched. Nothing was committed.

### 5a. Build-time guards (textum, a leaf nothing depends on)

**Locale-fold guard — FIRES**

    Execution failed for task ':checkLocaleFolds' (registered by plugin
      'be.elevenways.protoblast.publish').
    > Locale-sensitive case fold(s) found. ...
        .../textum/src/common/java/.../FgLocaleProbe.java:6: return input.toLowerCase();
    BUILD FAILED in 796ms          (zenit-dev exit 1)

**NUL-byte guard — FIRES**

    Execution failed for task ':checkNulBytes' ...
    > Raw NUL byte(s) (0x00) found in text sources. ...
        .../FgNulProbe.java:5: raw NUL byte at offset 148
    BUILD FAILED in 751ms          (zenit-dev exit 1)

**Compile guard — FIRES, with a correction worth recording.** My first compile
probe (`@NonNull Nested` written unqualified) **built GREEN, exit 0** — it is
simply not a javac error, so that attempt proved nothing. Only the QUALIFIED
form reproduces the diagnostic:

    /home/.../FgCompileProbe.java:10: error: type annotation
      @org.checkerframework...NonNull is not expected here
        public static @NonNull FgCompileProbe.Nested make() {
      (to annotate a qualified type, write FgCompileProbe.@...NonNull Nested)
    [BlastCompileGuard] javac reported 1 error(s) that a javac plugin (Manifold)
      dropped from the compiler's error count; failing the compilation.
    Execution failed for task ':compileCommonJava'
    BUILD FAILED in 907ms          (zenit-dev exit 1)

**Green control:** textum with no probes → `ok textum`, `Build completed in 7s`,
exit 0. `git status` empty.

### 5b. The autoload validation does NOT false-refuse an absent optional dep

**A probe planted in textum would have been VACUOUS and I discarded it.** textum
applies only `be.elevenways.protoblast.publish`, generates no
`BlastAutoLoadInit` at all, and "passed" in 2 seconds having scanned nothing.
Re-run in **orcono** (also a leaf — an app; nothing in the chain depends on it)
which does generate loaders, with a matched pair so that "absent" is a real
decision rather than a dead path:

| probe | `whenPresent` gate | expected | observed |
| --- | --- | --- | --- |
| `FgAbsentDepProbe` | `be.elevenways.nosuchmodule.common.DefinitelyAbsentClass` | dropped, build green | **dropped, 0 occurrences, exit 0** |
| `FgPresentDepProbe` (control) | `be.elevenways.zenit.widget.common.WidgetTree` | emitted | **emitted, server=1, client=1** |

    zenit-dev build --skip-deps   EXIT=0   WALL=83s
      client absent=0 present=1 total=86  widget=15
      server absent=0 present=1 total=128 widget=17
      common absent=0 present=0 total=14  widget=0
    refusal messages: 0

Totals rose by exactly the one control entry (server 127→128, client 85→86) and
`widget` stayed at 17/15 — no collateral drops. After removing both probes and
rebuilding: server 127 / widget 17, client 85 / widget 15, `Fg*Probe`
occurrences **0** — byte-identical to the pre-run baseline.

Workspace-wide sweep for `Fg*Probe*.java` across javaweb and hohenext
(excluding my scratchpad): **no results**.

## 6. Final workspace state — unchanged

All 26 workspace repos plus hohenheim: `dirty=0`, and every HEAD identical to
the value recorded before I started.

    protoblast 2791d27   zenit 61c5590   plumage d78ebb7   hawkeye d7bbbf5e
    zenit-cms 86413d2    orcono 56b1ada  hohenheim 4a4c745  ... (all 27 clean)

Nothing was committed by this agent.

## 7. Infrastructure classifiers

**No infrastructure failure occurred in any of my runs**, so the classifiers
were exercised only as negative controls — and they behaved: no INFRASTRUCTURE
banner appeared anywhere, and nothing I hit was infrastructure. The one build
failure I did not plant deliberately (the first compile probe, which built
green) was correctly reported as a plain success, not misclassified.

The build-path classifier reported all three planted guard failures with the
real task name, the real message and the real file:line, with no guessed cause —
which is exactly what let me confirm each guard in one step. The test-path
classifier reported the `--class` filter miss as a filter miss
(`the filter for class 'BlastAutoLoadScanToleranceTest' matched zero unit
tests`) rather than as a test failure or as infrastructure, which is the correct
call and is how I found finding 2.

## 8. Reported, not fixed

1. `zenit-dev test-log --run N` prints the latest run's parsed results under an
   older run's metadata (section 3a). False-green generator in the audit path.
2. `zenit-dev test --class` matches nothing in the `protoblast-compile` module —
   `BlastAutoLoadScanToleranceTest` and `BlastAutoLoadIndexTest` are both
   unreachable, though they run in the full suite.
3. `verify-head` prints `ok` for the non-git target `zenit-auth-test-support`
   (should be `skip`). Known, left alone as instructed.
4. Leaked Gradle test worker PID 2330253 + daemon PID 2328661 from 2026-07-31
   01:23, still resident (217 MB RSS). Not touched.
5. The second `claude` session (PID 2509244) is still alive. It was idle
   throughout this run — proven by unchanged HEADs and clean worktrees — but the
   "machine is yours" premise was not literally true.
