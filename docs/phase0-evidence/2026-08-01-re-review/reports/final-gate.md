# Final gate — 2026-08-01

Gate agent. Every result below was CAUSED by this agent unless labelled
"prior evidence". Earlier reports were read for method only.

---

## VERDICT

**The committed workspace is GREEN. Nothing found must be blocked from
shipping.** Every one of the 28 verify-head targets compiles from its own
committed tree, and every targeted suite passes once measured against a
settled workspace.

**BUT the gate's premise was false: this workspace was NOT quiescent.**
A second `claude` session (PID 2509244, running in the javaweb container since
2026-07-31 10:57, actively polling the zenit-dev journal since 11:09 today) was
editing, building and COMMITTING to `zenit` throughout my run. It landed
`zenit f1447b9` at 18:08:12, and as of 18:39 it still holds a dirty `zenit`
worktree (4 modified + 1 untracked file) that does not compile.

Three of my four red suites were caused by that concurrency, not by any commit.
They all pass on re-run. Details in section 4.

### The two REAL defects found, neither a ship blocker

1. **`plumage` `DatePickerTest` is a calendar-rollover time bomb** and has been
   red since 00:00 today, for reasons unrelated to every commit in this arc.
   Fails reproducibly in isolation. Not a ship blocker (test-only), but it is a
   standing red test.
2. **The Protoblast autoload scan silently tolerates a missing classpath entry**
   and emits an incomplete `BlastAutoLoadInit` while the build reports SUCCESS.
   This is what turned a concurrent republish into 6 false zenit-cms failures
   and 8 false orcono failures. Robustness gap worth closing; not a regression
   from today.

### Not verified, and why

The zenit unit suite and `verify-head --all` certify `zenit 5ca32d5`. The other
session's newer `zenit f1447b9` I verified compiles (`verify-head`, 99s) but did
NOT re-run the unit suite against — its worktree is mid-edit and its owner is
still working.

---

## 1. `zenit-dev verify-head --all` — PASS, exit 0, 1049s

Started 16:48:58, finished 17:06:27. Journal: `verify-head --all  1049s  exit 0`.

Every line carries a real per-repo compile duration, so nothing was skipped:

| repo | HEAD | tasks | time |
| --- | --- | --- | --- |
| protoblast | 19a4d95 | assemble testClasses browserTestClasses | 26s |
| emberglyph | 4994deb | assemble testClasses | 8s |
| hawkeye | d7bbbf5e | assemble testClasses browserTestClasses | 41s |
| janeway | fd170c9 | assemble testClasses | 7s |
| zenit | 5ca32d5 | assemble testClasses browserTestClasses | 56s |
| plumage | 4f12f51 | assemble testClasses browserTestClasses | 69s |
| textum | 2cddd9b | assemble testClasses browserTestClasses | 14s |
| zenit-microcopy | 419e489 | assemble testClasses | 26s |
| zenit-oidc | fd14805 | assemble testClasses | 26s |
| zenit-forms | 9225953 | assemble testClasses | 39s |
| zenit-widget | 99a7961 | assemble testClasses browserTestClasses | 34s |
| zenit-media | 086f2ba | assemble testClasses browserTestClasses | 40s |
| zenit-a2ui | 9be0592 | assemble testClasses | 20s |
| zenit-flow | f458b2c | assemble testClasses browserTestClasses | 51s |
| zenit-cms | 86413d2 | assemble testClasses browserTestClasses | 55s |
| zenit-pages | a0f2181 | assemble testClasses | 25s |
| zenit-auth | 9022082 | assemble testClasses browserTestClasses | 39s |
| **zenit-ai** | **f2e4c07** | assemble testClasses | **24s** |
| zenit-comms | 22f0bbe | assemble testClasses | 27s |
| duiventil | 9ee22a7 | assemble testClasses | 8s |
| orcono/mvp-v01 | 56b1ada | assemble testClasses browserTestClasses | 49s |
| proteus | 57a8a87 | assemble testClasses | 38s |
| herald | 2b9786d | assemble testClasses | 32s |
| quirkyquarters | 4041c6e | assemble testClasses browserTestClasses | 102s |
| spamservice | 8e5f1cd | assemble testClasses | 114s |
| thoth | 2ee1ee4 | assemble testClasses | 42s |
| hohenheim | 4a4c745 | assemble testClasses browserTestClasses | 38s |

`zenit-ai` — the previous audit's MUST-NOT-SHIP blocker — now compiles. The
`zenit-forms` / `zenit-media` fresh-clone breaks are closed too.

**One defect in the gate itself.** The 28th target prints
`ok  zenit-auth-test-support: not a git repository`. That is an `ok` for
something the command did not compile — precisely the class of weak-`ok` the
fast-path removal was meant to abolish. It is harmless here (that directory has
no history to verify) but the invariant "`ok` means THIS command compiled the
committed tree" is violated by the wording. Should print `skip` or `n/a`.

Later addition: `verify-head zenit` against the other session's newer
`f1447b9` — `ok`, 99s, 18:18:06→18:19:45. So the concurrent session has not
committed content that fails to compile; only its uncommitted worktree is
broken.

## 2. Clean dependency-chain build with caching — NOT a no-op, 76s

`cd zenit-cms && zenit-dev build`, 17:22:16 → 17:23:32, wall **1m16s**.

    ── Dependencies (protoblast → … → zenit-pages) ──
      ...  zenit-pages: content changed since last publish
      ok  zenit-pages
    ── Building zenit-cms (dependencies republished) ──
      ...  Clearing generated template sources
      ...  Removing stale TeaVM JS: cms.js
      ok  zenit-cms
      ok  Build completed in 76s

Real work: `zenit-pages` republished and `zenit-cms` fully rebuilt including a
TeaVM regeneration (`public/cms.js`, 18,679,064 bytes, 17:23). `zenit-dev status`
afterwards: every chain repo `m2: fresh`, client JS fresh. This is the opposite
of the sub-second fingerprint no-op the previous gate got.

## 3. zenit unit suite, forced fresh — PASS

    cd zenit && zenit-dev test --unit --no-fail-fast --rerun

Started **17:23:54**, completed 267s. The final commit of the day at that
moment was `hohenheim 4a4c745` (16:46:05); the final `zenit` commit was
`5ca32d5` (16:32:35). The run postdates both.

- total **2148** = passed **2073**, failed **0**, skipped **75**
- 283 of 283 test classes
- exit 0; **no exit-code-disagreement banner**, **no INFRASTRUCTURE banner**
- `--rerun` forces the Gradle test task, and the log carries real per-test
  durations, so this is not a re-reported cached run.

## 4. Targeted suites

Sweep script: `zenit-dev test --no-fail-fast` per repo, sequential,
17:28:44 → 18:14:28.

| repo | result | note |
| --- | --- | --- |
| protoblast | PASS 2820 unit + 14 browser | 52s |
| hawkeye | PASS 2692 unit + 342 browser | |
| zenit-auth | PASS 154 unit + 2 browser | |
| zenit-cms | **FAIL 6 of 49 browser** | contaminated — see below |
| plumage | **FAIL 1 of 120 browser** | REAL, see below |
| orcono | **FAIL 8 of 8 browser** | contaminated — see below |
| spamservice | PASS 130 unit | |
| thoth | PASS 10 unit | |
| textum | PASS 388 unit + 62 browser | |
| proteus | PASS 58 unit | |
| zenit-microcopy | PASS 131 unit | |
| duiventil | **build fail** | contaminated — see below |
| hohenheim | **build fail** | contaminated — see below |

### 4a. The contamination, proven

`duiventil` and `hohenheim` (18:14) did not fail a test — their dependency
build failed on
`zenit/src/server/.../CouchbaseMigrationOperationVisitor.java:117: error:
cannot find symbol`, from the other session's **uncommitted** worktree edits.

Re-run with `--skip-deps` after that build settled:

| repo | re-run | result |
| --- | --- | --- |
| zenit-cms browser (full) | 18:25:03 | **PASS 49/49** |
| orcono browser (full) | 18:22:55 | **PASS 38/38** |
| duiventil | 18:27:25 | **PASS 348 unit** |
| hohenheim | 18:31:54 | **PASS 581 browser** |

The mechanism behind the zenit-cms and orcono failures is worth naming, because
it is a real robustness gap and not just noise:

- zenit-cms's generated `browserTest` `BlastAutoLoadInit` contained **zero**
  zenit-widget autoload entries (108 entries, widget=0) while its `server` and
  `test` loaders had all 19. That produced exactly the six failures seen:
  `No form entry derivation registered for … WidgetTreeField` (500s in two
  tests), `action_failed` and a surface timeout (`SurfaceDry` /
  `SurfaceActionHandlers` absent), and a missing `zenitwidget:section` picker
  option (`SectionWidget.ID` absent).
- Timestamps place the cause: the browserTest scan wrote its index at
  **17:39:49**, and `~/.m2/…/zenit-widget-server-0.1.0-SNAPSHOT.jar` was
  replaced at **17:40:05**. The scan read a classpath entry that the other
  session was mid-republish.
- After a quiet rebuild the same index is **128 entries, widget=17**, and all
  49 tests pass.
- orcono is the mirror image: its loader referenced
  `be.elevenways.hawkeye.generated.plumage.HawkeyeCustomElementRegistrations`,
  which was absent at RUN time → `NoClassDefFoundError` from
  `BlastAutoLoadInit.<clinit>` → all 8 classes failed at `initializationError`.

**Finding (report-only): `PerformClassGraphScanTask` silently skips a
classpath entry it cannot read and still succeeds.** A green build can
therefore emit an autoload loader that is missing an entire module's
registrations. Here it only cost false test failures, but the same shape in a
`server` source set is a server that boots without a module's registrations and
says nothing. Worth a "every declared classpath entry must be readable or fail"
check.

### 4b. plumage `DatePickerTest` — REAL, and independent of every commit

`popupOpensDismissesOnEscapeAndFillsTheFieldOnPick` fails reproducibly in
isolation (`--browser --skip-deps --class DatePickerTest`, 18:19:54, 1/1 failed,
92s). Not order-dependence, not a flake.

    Timeout 45000ms exceeded
    - waiting for locator(".pl-date-picker__popup [data-iso='2026-07-20']")
    at DatePickerTest.popupOpensDismissesOnEscapeAndFillsTheFieldOnPick(:38)

Root cause, proven from source:

- the fixture declares `{% let pickVal = "" %}` (plumage
  `src/browserTest/templates/test/date-field-test.hwk:13`), so the picker opens
  with no value;
- `calendar.hwk:109-111` anchors the displayed month on
  `value != "" ? value : Dates.todayIso()`.

So the popup opens on **today's** month. The test clicks a hardcoded
`2026-07-20` cell. It passed every day up to 2026-07-31 and started failing at
midnight on 2026-08-01. Test-only, but plumage's suite has been red all day and
nobody noticed because no full plumage browser suite ran until mine.

The other 119 of 120 plumage browser tests pass.

### 4c. What I deliberately did NOT run

- `zenit --datasources all` — no change to the datasource/query-translation
  layer in this arc.
- Full suites for the repos that took only a locale fold or a NUL escape
  (`zenit-a2ui`, `zenit-ai`, `zenit-comms`, `zenit-flow`, `zenit-forms`,
  `zenit-media`, `zenit-oidc`, `zenit-pages`, `zenit-widget`, `herald`,
  `quirkyquarters`, `emberglyph`, `janeway`). Their HEADs compile; behaviour
  did not change.
- The zenit unit suite against the other session's `f1447b9`.

## 5. Guards — all verified empirically, every probe reverted

### 5a. Build-time guards (planted violations in `textum`, a leaf repo)

`textum` was chosen deliberately: nothing in the chain depends on it, so a
concurrent session could not be perturbed. Every plant was a NEW UNTRACKED file
deleted immediately after the build that observed it; no tracked file was
touched; nothing was committed.

**Locale-fold guard — the one that was INERT in textum until today. FIRES:**

    Execution failed for task ':checkLocaleFolds' (registered by plugin
      'be.elevenways.protoblast.publish').
    > Locale-sensitive case fold(s) found. …
        …/textum/src/common/java/…/FgLocaleProbe.java:7: return input.toLowerCase();
    BUILD FAILED in 13s

**NUL-byte guard — FIRES:**

    Execution failed for task ':checkNulBytes' (registered by plugin
      'be.elevenways.protoblast.publish').
    > Raw NUL byte(s) (0x00) found in text sources. …
        …/FgNulProbe.java:6: raw NUL byte at offset 181
    BUILD FAILED in 10s

**Compile guard (swallowed javac error) — FIRES:**

    …/FgCompileProbe.java:14: error: type annotation
      @org.checkerframework.checker.nullness.qual.NonNull is not expected here
      (to annotate a qualified type, write FgCompileProbe.@…NonNull Nested)
    [BlastCompileGuard] javac reported 1 error(s) that a javac plugin (Manifold)
      dropped from the compiler's error count; failing the compilation.
    1 error
    Execution failed for task ':compileCommonJava'
    BUILD FAILED in 12s

**Green control:** all three probes removed → `ok textum`, `Build completed in
17s`, exit 0. `git status` on textum before AND after: empty.

Both plugin ids are covered: textum applies only
`be.elevenways.protoblast.publish`, and all three guards fired through it —
that is the fix for the inert-marker defect, proven from the consumer side.

### 5b. TeaVM negative control — TeaVM builds still SUCCEED

- the chain build (section 2) regenerated `cms.js` (18.7 MB) green under the
  guard;
- hawkeye 342, plumage 119, textum 62, hohenheim 581, orcono 38, protoblast 14
  browser tests all ran against TeaVM-compiled bundles and passed;
- protoblast's own lane tests passed in my run:
  `duplicateFqnOnTeaVMClasspathFailsBeforeCompilation`,
  `patchedTeaVmClassMustWinByClasspathOrder`,
  `duplicateFqnWithoutPatchCarrierFailsTheBuild`.

No `[BlastCompileGuard]` banner appeared in any green build. The guard is not
false-positiving on TeaVM `@JSBody` noise.

### 5c. Permanent guard tests, run by me

In my protoblast suite (17:28, 2820 unit passed):

- `BlastCompileGuardTest.guardRestoresErrorsThatManifoldSwallows(Path)` — PASS
  (the 6-step journey whose step 2 asserts the upstream swallow is still real,
  so it self-retires when Manifold fixes it).
- `LocaleFoldGuardTest.productionSourcesMustNotUseLocaleSensitiveCaseFolds` — PASS.
- `LocaleFoldingTest` (slug / Uri / camelCase, Turkish-I) — 3/3 PASS.

### 5d. Pre-commit guard — all three refusals + two negative controls

Tested in a scratch repo (`scratchpad/GATE/hookrepo`) carrying a byte-for-byte
copy of the hook installed in `zenit`. **No real history was touched.**

| case | outcome |
| --- | --- |
| staged, then edited, then commit | REFUSED, exit 1, names `src/A.java`, no commit created |
| raw NUL in a staged `.java` | REFUSED, exit 1, names `src/B.java:1` |
| marker in a dir whose `build.gradle` applies no protoblast plugin | REFUSED, names `sub/locale-folds.guard: … the marker gates NOTHING` |
| unknown marker name `typo-folds.guard` | REFUSED, names the known set |
| **negative:** NUL in a non-text file (`blob.bin`) | ALLOWED, exit 0 |
| **negative:** `locale-folds.guard` + `nul-bytes.guard` beside a protoblast plugin | ALLOWED, exit 0 |

Hook installation audited across all 27 repos: present, executable, correct
marker, in every one — including hohenheim.

Marker inventory: 32 `*.guard` files, **all tracked, none untracked, none
orphaned**. Workspace-wide sweep of every tracked text file for raw NUL bytes:
**zero hits**.

## 6. Commit hygiene — 98 commits examined, 4 violations, all pre-session

Checked: first character is a real Unicode gitmoji (not a `:shortcode:`),
subject ≤ 72 chars, blank line between subject and body, body ≤ 3 lines.

| repo | hash | time | violation |
| --- | --- | --- | --- |
| zenit | `8b6a60be` | 02:17:14 | subject and body on one line |
| zenit-widget | `7ffe1dec` | 02:17:18 | subject and body on one line |
| zenit-cms | `5408bcd8` | 02:17:25 | subject and body on one line |
| hohenheim | `f38c8d93` | 02:17:28 | subject and body on one line |

These are exactly the 4 known 02:17 commits that predate this session.
**Zero violations among the ~94 commits made during it**, including the other
session's `f1447b9` at 18:08. Nothing amended or rebased.

## 7. Worktree cleanliness

At 18:39, of 27 repos, **26 are clean**. The one exception is `zenit`:

     M src/server/java/…/CouchbaseMigrationOperationVisitor.java
     M src/server/java/…/migration/MigrationRunner.java
     M src/test/java/…/orm/RevisionConcurrencyTest.java
     M src/test/java/…/task/scheduletests/TaskScheduleTest.java
    ?? src/test/java/…/orm/SchemalessMigrationNoOpTest.java

**This is not left-behind work — it is live, in-flight work by the concurrent
session**, which at 18:15 was running `zenit-dev build --skip-deps &&
zenit-dev coverage` in that directory. It does not currently compile. It should
not be touched or reverted.

`thoth`'s generated `public/thoth-client.js` is now gitignored (`.gitignore:10-11`)
and untracked; thoth's worktree is clean — confirmed.

No probe of mine survives: `find … -name 'Fg*Probe*'` returns nothing, and the
only scratch artifacts live under the scratchpad.

## 8. The two open items

### `Models.clearAll()` — confirmed unused, deletion candidate

Zero callers workspace-wide (all repos + hohenheim, excluding build output).
The only two references are its own declaration
(`zenit/src/common/java/be/elevenways/zenit/common/orm/model/Models.java:202`)
and the AIDEV-NOTE at :169 that names it as the thing NOT to simplify cleanup
to. `clearBindings()` — which `clearAll()` calls — has 9 live test callers in
quirkyquarters, zenit-comms and zenit-microcopy, so only `clearAll` itself is
dead.

### orcono / spamservice after the planted probe was removed

`PlantedProbe.java` is gone (workspace-wide `find` returns nothing).

- **spamservice**: HEAD `8e5f1cd` compiles under verify-head (114s); full suite
  **PASS, 130 unit** with a real dependency build (no `--skip-deps`), 17:59→18:05.
- **orcono**: HEAD `56b1ada` compiles under verify-head (49s); unit **PASS 122**
  with a real dependency build; browser **PASS 38/38** on the settled re-run.

Both are clean.

---

## Infrastructure classifiers

No testcontainer infrastructure failure occurred in any of my runs, so the
`infraSuspect` classifiers were exercised only as negative controls — and they
behaved correctly: every failure I hit was reported with `infraSuspect: 0` and
no INFRASTRUCTURE banner, and none of them was infrastructure. In particular
the zenit-cms and orcono failures — which WERE environmental — were correctly
NOT claimed as testcontainer infrastructure, because they are not; the
classifier did not over-reach.

The build-path classifier likewise did not mislabel the `duiventil` /
`hohenheim` dependency-build failure: it reported the actual javac error and
the file, which is what let me trace it to the concurrent session in one step.

## Reported, not fixed

1. `plumage` `DatePickerTest` date-dependence (red since midnight).
2. `PerformClassGraphScanTask` silently tolerating an unreadable classpath
   entry and emitting an incomplete autoload loader in a GREEN build.
3. `verify-head` printing `ok` for a non-git target (`zenit-auth-test-support`).
4. `Models.clearAll()` dead.
5. The concurrent session's in-flight `zenit` worktree — the owner's call.

Nothing was committed by this agent.
