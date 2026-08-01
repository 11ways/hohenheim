# Final verification gate — 2026-08-01

Verifier: independent gate agent. Every result below was CAUSED by this agent
unless explicitly labelled "prior evidence". Per-agent green claims from the
day's reports were treated as hypotheses, not evidence.

## VERDICT

**The workspace is NOT fully green. One repo cannot be built from its own
committed tree: `zenit-ai`.** It is not a regression from today's commits — it
is a pre-existing fresh-clone break that today's gate is the first thing ever
to detect. Two more repos (`zenit-forms`, `zenit-media`) cannot be built from a
fresh clone either, for a different reason.

Nothing found is a functional defect in shipped code. Everything found is a
"this tree does not build from a clean checkout" defect. That still blocks CI
and any release built anywhere other than this machine.

- MUST NOT SHIP AS-IS (build-from-clean-clone is broken): zenit-ai.
- SHOULD BE FIXED WITH IT (same class, different cause): zenit-forms, zenit-media.
- Everything else compiled and tested green under this agent's own runs.

---

## 1. HEAD compiles everywhere

### The shortcut that made the first run worthless

`zenit-dev verify-head --all` (13:48, exit 0) reported `ok` for 27 repos. **26
of those 27 were the clean-worktree fast path** — "worktree matches HEAD, the
ordinary build already builds this commit's content". That is a freshness
ASSUMPTION, not a compile. Since today's chain build is itself a fingerprint
no-op (section 2), the `--all` run proved essentially nothing beyond `thoth`.

### Forcing a real compile

Planted an untracked probe file (`.zd-verify-probe`) in each repo before
running `verify-head <repo>`. Untracked counts as dirty on purpose, and
`git archive HEAD` excludes the probe — so verify-head extracts and compiles
the exact committed tree, with `--no-build-cache`, under the new
`BlastCompileGuard`. Probe removed immediately after each run. No tracked file
was touched, nothing was committed.

### Results (all repos that received a commit today, plus 3 extras)

| repo | HEAD | forced verify-head |
| --- | --- | --- |
| protoblast | 04e3c9c | ok (assemble testClasses browserTestClasses, 27s) |
| hawkeye | 84587988 | ok (40s) |
| zenit | 660d671 | ok (55s) |
| plumage | fa5138a | ok (83s) |
| textum | e7deb1e | ok (14s) |
| zenit-microcopy | 171fa77 | ok (18s) |
| zenit-oidc | e9b2ce5 | ok (17s) |
| zenit-widget | 8be1cef | ok (30s) |
| zenit-flow | 03e0914 | ok (48s) |
| zenit-cms | 0e14d2e | ok (56s) |
| zenit-auth | e27ba75 | ok (38s) |
| **zenit-ai** | **7b70e74** | **FAIL — does not compile** |
| zenit-comms | dcab507 | ok (18s) |
| orcono/mvp-v01 | f6e3756 | ok (50s) |
| proteus | 107d498 | ok (37s) |
| quirkyquarters | a0729b7 | ok (105s) |
| spamservice | 1603dc0 | ok (114s) |
| thoth | 1770c2b | ok (43s) — the only repo the unforced run actually compiled |
| hohenheim | 27447e0 | ok (54s) |
| zenit-forms (unchanged today) | 59f6f57 | **FAIL — infra/hygiene, see below** |
| zenit-media (unchanged today) | (HEAD) | **FAIL — same cause as zenit-forms** |

### BLOCKER: zenit-ai HEAD does not compile from its own committed tree

    src/server/java/be/elevenways/zenit/ai/server/AiDrySerializers.java:3:
      error: package be.elevenways.hawkeye.generated.zenitai does not exist
    ...:37: error: cannot find symbol

Root cause, proven by a controlled A/B on one identical extracted tree:

1. `zenit-ai/build.gradle` declares `hawkeye { templateDirs = ['src/common/templates'] }`.
2. That directory is **empty and untracked**. Git cannot store empty
   directories, so it is absent from `git archive HEAD` and from every fresh
   clone.
3. The hawkeye task registration is directory-existence conditional. Without
   the directory, `preCompileCommonHawkeyeClasses` and
   `compileCommonHawkeyeTemplates` are **absent from the task graph entirely**
   (verified against the task list of both runs).
4. Those tasks are what generate `be.elevenways.hawkeye.generated.zenitai.HawkeyeClassSerializers`,
   which `AiDrySerializers.java:3` imports unconditionally.

Controlled experiment (identical tree, single variable):

    git archive HEAD -> /tmp/.../cf/zenit-ai      (templates dir absent: confirmed)
    mkdir -p src/common/templates
    zenit-dev build --skip-deps  ->  ok, 2s
        (task graph contains preCompileCommonHawkeyeClasses + compileCommonHawkeyeTemplates)
    rm -rf src/common/templates build
    zenit-dev build --skip-deps  ->  FAIL, same two javac errors

So zenit-ai builds on this machine only by filesystem accident: the empty
directory was created locally on 2026-07-16 and has been carried by the
worktree ever since. **A fresh clone of zenit-ai has never been buildable.**

Not caused by today's commit: `7b70e74` is a one-line `Locale.ROOT` fold;
`AiDrySerializers.java` has not been touched since `3a3287a`.

Workspace sweep for the same shape (empty untracked `templateDirs` +
`hawkeye.generated` import in Java):

| repo | empty untracked templateDirs | imports hawkeye.generated | fresh clone builds |
| --- | --- | --- | --- |
| zenit-ai | yes | yes (1 file) | **NO** |
| arcana (excluded from CI) | yes | yes (1 file) | **NO** (same shape, not re-tested) |
| zenit-a2ui | yes | no | yes (proven) |
| zenit-comms | yes | no | yes (proven) |
| zenit-microcopy | yes | no | yes (proven) |
| zenit-oidc | yes | no | yes (proven) |

The four "no" rows are armed traps, not current breaks: the moment any of them
gains a `@HawkeyeClass` reference they break the same way.

**Not fixed.** The one-line fix (`.gitkeep`) and the real fix (register the
hawkeye tasks regardless of directory existence, which would disarm all six
repos at once) are a genuine design fork, and choosing between them is the
owner's call, not the verification gate's.

### zenit-forms and zenit-media: gradle.properties is gitignored

Both fail forced verify-head with:

    Execution failed for task ':compileCommonHawkeyeTemplates'
    Gradle build daemon has been stopped: since the JVM garbage collector is thrashing

Cause: `gradle.properties` (which carries `org.gradle.jvmargs=-Xmx2g ...`) is
**gitignored and untracked** in exactly these two repos. Every other repo in the
workspace tracks it (26/28 checked). A fresh checkout therefore runs the
hawkeye template compile on Gradle's default heap and GC-thrashes to death.

Counterfactual proving the HEADs themselves are fine:

    git archive HEAD -> /tmp/.../cf2/zenit-forms   (gradle.properties absent: confirmed)
    cp <worktree>/gradle.properties .
    zenit-dev build --skip-deps  ->  ok, 14s

So: zenit-forms `59f6f57` and zenit-media HEAD compile correctly. The repos are
simply not clonable-and-buildable. Neither changed today.

### Tooling gaps this exposed

- `verify-head --all` walks `CI_LEVELS`, which does **not contain hohenheim**
  (it lives outside the javaweb workspace root). Run from its own directory it
  works — and passes — but the sweep never covers it.
- `zenit-dev install-guards` likewise missed hohenheim: it is the **only repo
  in the workspace with no pre-commit guard hook installed** (checked all 28;
  the other 27 all carry the identical hook, md5 prefix 428b093a). hohenheim
  took 4 commits today with no staged-then-edited protection.
- The clean-worktree fast path is sound only while the ordinary build is
  trusted. Today the ordinary build was a 0s fingerprint no-op, so the fast
  path degenerated to "trust the fingerprint that trusts the build that never
  ran". Worth a flag to force the archive path.

---

## 2. Clean dependency-chain build with caching

    $ cd zenit && time zenit-dev build
    ...  All dependencies are fresh
    ok   zenit is up to date (content unchanged since last build)
    ok   Build completed in 0s
    real 0m0.169s

**Wall clock: 0.17s.** Honest reporting: this is a fingerprint no-op and it
verifies nothing today. It confirms the freshness bookkeeping agrees that every
worktree matches its last publish — it does not confirm that anything compiles.
The forced verify-head sweep in section 1 is the real evidence and it is the
result that should be quoted.

---

## 3. zenit unit suite, forced fresh

Exact command:

    cd /home/skerit/projects/javaweb/zenit
    zenit-dev test --unit --no-fail-fast --rerun

| metric | value |
| --- | --- |
| total | 2145 (2070 executed + 75 skipped) |
| passed | **2070** |
| failed | **0** |
| skipped | 75 |
| test classes | 282 of 282 |
| duration | **284s (4m44s)** |
| started | 14:12:02 |
| finished | 14:16:46 |

- **Not cached.** `--rerun` was used and the run printed real per-test events
  (`ClassName / method / ms`), not the `(cached — task UP-TO-DATE,
  re-reporting the run saved at ...)` banner. This mattered: the protoblast
  guard test's first invocation today DID come back cached from 13:08, which
  predates the 13:22 commit it certifies — the exact trap.
- **Postdates the final commit.** zenit's last commit is `660d671` at 13:22:18.
  The run started at 14:12:02, 50 minutes later.
- **Five-minute budget: met.** 284s, 16s under.
- **No exit-code-disagreement banner.** The run ended `✓ RESULT: PASSED —
  2070 unit passed`. I read the banner's implementation (`zenit-dev:3262-3278`)
  to confirm what its absence means: it fires when every test event passes but
  Gradle exits non-zero, printing `▓▓ EXIT CODE DISAGREES WITH TEST COUNTS` and
  naming the failed non-test task. Nothing like it appeared, so exit code and
  counts agree.
- Growth vs the 10:48 baseline (2056 total / 1981 passed) is explained by tests
  added during the day.

---

## 4. Targeted suites for the other repos

All runs forced fresh (`--rerun`) with `--no-fail-fast`, sequential, one suite
machine-wide at a time. **Zero failures anywhere.**

### Unit suites (`zenit-dev test --unit --no-fail-fast --rerun`)

| repo | passed | failed | window |
| --- | --- | --- | --- |
| protoblast | 2819 | 0 | 14:24:39-14:25:15 |
| hawkeye | 2692 | 0 | 14:25:15-14:26:23 |
| zenit-auth | 154 | 0 | 14:26:23-14:30:19 |
| zenit-cms | 534 | 0 | 14:30:19-14:31:07 |
| proteus | 58 | 0 | 14:31:07-14:32:05 |
| plumage | 1 | 0 | 14:32:05-14:32:16 |
| zenit-widget | 237 | 0 | 14:32:16-14:32:29 |
| textum | 388 | 0 | 14:32:29-14:32:39 |
| quirkyquarters | 1463 | 0 | 14:32:39-14:35:33 |
| spamservice | 129 | 0 | 14:35:33-14:36:14 |
| zenit-ai | 138 | 0 | 14:36:14-14:36:35 |
| zenit-comms | 34 | 0 | 14:36:35-14:37:04 |
| zenit-microcopy | 131 | 0 | 14:37:04-14:37:52 |
| zenit-oidc | 64 | 0 | 14:37:52-14:38:32 |
| thoth | 10 | 0 | 14:38:32-14:39:01 |
| orcono/mvp-v01 | 121 | 0 | 14:39:01-14:40:05 |

### The unit pass alone would have been a false negative

plumage returned **1** unit test — its real coverage is browser-side. Checking
what today's commits actually changed showed that three of the day's key test
additions live in browser source sets and **the unit sweep did not execute a
single one of them**:

- plumage `fa5138a` -> `PermissionsEditorTest` (browserTest)
- hawkeye `d8e4294` -> `ListDirectiveBrowserTest` (browser)
- orcono `f6e3756` -> `EditorSoftNavLifecycleBrowserTest` (browser)

And hohenheim has **no `src/test` at all** — `zenit-dev test --unit` answered
`this project has no unit tests (no src/test)`; both of `27447e0`'s new test
classes are under `src/browserTest`. So a second, browser-targeted pass was
required to verify today's work at all:

| repo | class | passed | failed |
| --- | --- | --- | --- |
| hawkeye | ListDirectiveBrowserTest | 2 | 0 |
| plumage | PermissionsEditorTest | 3 | 0 |
| orcono/mvp-v01 | EditorSoftNavLifecycleBrowserTest | 2 | 0 |
| hohenheim | TlsHostnameFoldingTest, DnsRecordCodecTest | 8 | 0 |

**Totals across everything this agent ran: 11,043 unit + 15 browser tests,
0 failures, 75 skipped.**

### Deliberately NOT run, and why

- **zenit-flow** — its only commit today (`03e0914`) touches `CLAUDE.md` and
  nothing else. Running a suite would have been pure ritual.
- **Full browser suites** for plumage, hawkeye, quirkyquarters, zenit-cms,
  orcono. Their unit suites ran in full; the browser side was targeted at the
  classes today's commits actually touched. Full browser sweeps across six
  repos is exactly the completeness ritual the workspace rules forbid.
- **hohenheim's full two-bucket suite** (~7 min, over budget). Targeted at the
  two classes `27447e0` added.
- **`zenit --all` / `--slow` / `--datasources all`.** No commit today touched
  the datasource or query-translation layer; the widened backend tier is the
  guard for that layer and nothing invoked it. The one commit near test
  infrastructure (`3e38756`, worker-namespace collection) is covered by the
  default SQLite+PostgreSQL tier plus the dialect-sensitive tests that always
  run on all 8.

---

## 5. Commit hygiene audit

All 59 commits made today were audited across 19 repos (counts: zenit 19,
hawkeye 5, protoblast 5, hohenheim 4, orcono 3, proteus 3, zenit-cms 3,
plumage 2, quirkyquarters 2, textum 2, zenit-auth 2, zenit-widget 2,
spamservice 1, thoth 1, zenit-ai 1, zenit-comms 1, zenit-flow 1,
zenit-microcopy 1, zenit-oidc 1). `zenit-forms` — named in the task list — took
**no** commits today; its last is 59f6f57 from 07-31.

- **Gitmoji**: 59/59 start with a real Unicode emoji codepoint. No shortcode
  text anywhere. (Verified by decoding the first codepoint of each subject:
  U+1F41B, U+1F512, U+1F6E1, U+2705, U+267B, U+2728, U+1F4DD, ... all genuine.)
- **Subject under 72 chars**: 55/59 pass. Longest passing is proteus `35f28ae`
  at 71.
- **Subject and body on separate lines**: 55/59 pass.

### VIOLATIONS — 4 commits, all the same defect, all in one 02:17 batch

The heredoc newline-loss failure mode recurred. Subject and body are collapsed
onto one line, so the "subject" is the whole message and the body is empty:

| repo | hash | time | subject length |
| --- | --- | --- | --- |
| zenit | `8b6a60b` | 02:17:14 | 262 |
| zenit-widget | `7ffe1de` | 02:17:18 | 203 |
| zenit-cms | `5408bcd` | 02:17:25 | 255 |
| hohenheim | `f38c8d9` | 02:17:28 | 197 |

Important context: all four landed within 14 seconds of each other at 02:17,
and `8b6a60b` / `5408bcd` are recorded in ORCHESTRATION.md as the **starting
HEADs** of today's re-review session. They are the tail of the previous
(07-31) arc that spilled past midnight, not output of today's agents. Every
commit made after the session began (03:00 onward, 55 of them) is correctly
formatted — the practice held all day.

**Not amended.** Rewriting four published commits needs the owner's explicit
instruction.

---

## 6. Loose ends

### thoth's two dirty files ARE committed generated output

`public/thoth-client.js` (3.36 MB) and `public/thoth-client.js.map` (1.09 MB)
are tracked in git and are TeaVM compiler output.

- `build.gradle:456` sets `targetFileName = "thoth-client.js"` for the TeaVM
  bundle; the Hawkeye plugin's auto-registered `copyClientJs` task publishes it
  into `public/`. Both files' mtimes are 12:15 today — written by a build, not
  by a human.
- Current diff: 8617 insertions / 8590 deletions. The bundle is built with
  `obfuscated = true`, so identifier assignment shifts between builds and the
  file churns on essentially every rebuild even when the source is unchanged.
  The committed copy dates from `4cdb2b6` (07-31 13:19); only one source
  commit has landed since (`1770c2b`, today's Locale fold).
- `thoth` is the **outlier**: orcono keeps `public/` untracked entirely;
  spamservice and herald track only `public/.gitkeep`; quirkyquarters tracks
  the CSS and its map but not the JS bundle. Every one of those apps serves
  its client bundle fine without the repo copy, so thoth's committed bundle is
  not load-bearing for serving — `ServerMain.java:56` only sets the URL path
  (`/thoth-client.js`), which `copyClientJs` satisfies from the build.
- **Verdict: unintended, and a real design smell.** Multi-megabyte,
  non-deterministic build output in version control, guaranteeing a dirty
  worktree after every build, in exactly one of six sibling apps. It also means
  thoth is the one repo where the clean-worktree fast path of verify-head can
  never engage. Named, not fixed.

### Every other worktree is clean

All 28 repos checked with `git status --porcelain`. thoth's two generated files
are the **only** dirt in the entire workspace. No agent left uncommitted work
behind anywhere.

Two leftover processes from an earlier agent are still polling
`~/.local/share/zenit-dev/journal.jsonl` in `until` loops (pids 1003400/1003401).
They are harmless shell watchers that die with the session, not daemons, and I
did not create or leave any scheduled job, timer or cron entry.

---

## 7. The three relayed claims

### 7a. BlastCompileGuard — CONFIRMED, and confirmed twice over

Independent evidence I caused:

1. **The permanent test, forced fresh.** First run returned a CACHED pass saved
   at 13:08 — which **predates commit `04e3c9c` (13:22)**, the exact
   "cached green predating the commit it certifies" trap. Re-ran with `--rerun`:

       $ cd protoblast && zenit-dev test --unit --class BlastCompileGuardTest --no-fail-fast --rerun
       BlastCompileGuardTest
         guardRestoresErrorsThatManifoldSwallows(Path)  3422ms  PASSED
       (started 13:52:42, 30 minutes after the commit it certifies)

   The test is a 6-step journey over real javac + real Manifold jars: plain
   javac rejects the bad source; **Manifold + an annotation processor turns the
   identical input green and emits the class** (the upstream bug, asserted
   green on purpose as a retirement tripwire); the guard restores the failure;
   a clean source stays green under Manifold+guard (**the false-positive
   control**); the guard alone neither hides nor invents errors; extraction is
   idempotent.

2. **A real Gradle build, not a unit test.** `/tmp/zenit-dev-build.1227387.log`
   (13:32) contains the guard firing in an actual zenit-cms build:

       ...SettingsEditorBackend.java:144: error: type annotation @...NonNull is not expected here
       [BlastCompileGuard] javac reported 1 error(s) that a javac plugin (Manifold)
           dropped from the compiler's error count; failing the compilation.
       1 error
       FAILURE: Build failed with an exception.

**The TeaVM/@JSBody negative control — CONFIRMED, with a caveat about how.**
The guard cannot false-positive on `@JSBody` noise by construction: it counts
`JCDiagnostic` objects of `Kind.ERROR` inside javac and acts only when the
javac `Log` was replaced and javac's own count is zero. TeaVM's
`generateJavaScript` is not a javac invocation at all, so its "Error in
@JSBody..." stdout is text the guard can never observe. Empirically: 20 forced
verify-head compiles ran under the guard today with zero guard banners and zero
false failures, and a full TeaVM bundle regeneration (`zenit-ai` clean build,
plus the earlier plumage/thoth bundle builds recorded in the journal) completed
green with the guard active. Caveat on scope: verify-head deliberately does NOT
run the TeaVM JS bundle task, so the 20 forced compiles are javac-only
evidence; the TeaVM-generation evidence comes from the ordinary builds.
**Nothing suggests TeaVM builds are affected. They are not.**

### 7b. precommit-guard — CONFIRMED, tested end to end in a throwaway repo

Scratch git repo, real hook copied from `zenit/.git/hooks/pre-commit`, never
against real history:

| step | action | result |
| --- | --- | --- |
| A | stage a file, commit unchanged | **PASSED** (exit 0) — no false positive |
| B | `git add`, then edit the file, then commit | **REFUSED**, exit 1, names the file and the fc89eaf class |
| C | same, with `git commit --no-verify` | allowed, exit 0 |
| D | same, with `ZENIT_DEV_ALLOW_SPLIT_COMMIT=1` | allowed with a warning telling you to run verify-head |

Refusal text verbatim:

    fail  COMMIT REFUSED: 1 staged file(s) differ from the worktree
        a.txt
    The tree you are committing is NOT the tree your builds and tests ran
    against (staged, then edited, then committed — the fc89eaf class).

**Installation: 27 of 28 repos. hohenheim has no hook** (see section 1).

### 7c. zenit-cms 0e14d2e — CONFIRMED in all three parts

1. **It was a genuine javac error.** Reproduced from scratch with plain javac
   (no Manifold, no Gradle): a `TYPE_USE` annotation before a qualified
   static-nested type gives

       error: type annotation @p.NN is not expected here
         (to annotate a qualified type, write Outer.@p.NN Inner)

   and the corrected spelling compiles clean. `0e14d2e` makes exactly that
   change: `@NonNull SettingsEditor.Rejection` -> `SettingsEditor.@NonNull Rejection`.
2. **It was latent since 07-24.** `git blame` on the pre-fix line resolves to
   `8451cfc`, 2026-07-24, "Settings backends + structured list editing" — 8 days.
3. **It was really being swallowed.** The guard's banner in the 13:32 build log
   (quoted above) is the moment it stopped being invisible, and zenit-cms is
   the one repo that published artifacts built from a swallowed-error compile.

---

## Testcontainer infra classifier — barely exercised, and one real gap

**It never fired, because it never had to.** Across ~11,000 tests in 21 suite
runs there were zero container failures, so the pid-owned namespaces, the
preflight eviction and the infra-vs-code classifier all stayed silent. That is
the good outcome, but it means today's runs are NOT evidence that the
classifier works — it remains as unexercised as it was this morning. The only
positive signal is indirect: the zenit suite ran 282/282 classes clean where
four runs earlier in the week were red with Couchbase/Mapped-port cascades.

**Where the tooling did NOT tell me a failure was infrastructure:**
`verify-head` on zenit-forms and zenit-media failed with

    Gradle build daemon has been stopped: since the JVM garbage collector is thrashing

and verify-head reported that as:

    fail  zenit-forms: HEAD 59f6f57 DOES NOT COMPILE — the committed tree differs
          from the worktree your builds ran against
    fail  1 repo(s) have a HEAD that does not build. A commit shipped content
          that was never compiled (staged-then-edited partial git add?).

That is a **misdiagnosis with a confident, specific, and wrong accusation**. A
daemon OOM is not a staged-then-edited commit. I only found the real cause by
opening the raw Gradle log. The infra-vs-code classifier that exists for test
containers has no counterpart on the build/verify path, and this is exactly the
failure shape it should catch: "Gradle build daemon has been stopped",
`OutOfMemoryError`, GC-thrash and daemon-disappeared messages should be
classified INFRASTRUCTURE and reported as such rather than as a broken commit.
Recommended as a follow-up, not fixed here.

---

## Summary of everything found, by severity

| # | finding | severity |
| --- | --- | --- |
| 1 | zenit-ai HEAD does not compile from a fresh clone (empty untracked `src/common/templates` de-registers the hawkeye generation tasks) | **BLOCKER** |
| 2 | arcana has the identical shape (excluded from CI, not re-tested) | high, latent |
| 3 | zenit-forms + zenit-media: `gradle.properties` gitignored -> fresh clone builds on default heap and GC-thrashes | high |
| 4 | 4 commits at 02:17 (tail of the 07-31 arc) have subject+body collapsed onto one line | medium, cosmetic, not amended |
| 5 | hohenheim has no pre-commit guard hook (only repo of 28) and is absent from `verify-head --all`'s repo list | medium |
| 6 | thoth commits 4.4 MB of obfuscated TeaVM build output; churns every build; unique among six sibling apps | medium, design smell |
| 7 | verify-head misreports Gradle daemon OOM as "committed tree does not compile" | medium, tooling |
| 8 | `verify-head`'s clean-worktree fast path silently skipped 26/27 repos; no flag exists to force the archive path | medium, tooling |
| 9 | zenit-a2ui / zenit-comms / zenit-microcopy / zenit-oidc carry the same empty untracked template dir; harmless today, armed | low |

Nothing in this list is a defect in the behaviour of shipped code. The day's 59
commits are, as far as 11,058 executed tests and 20 forced committed-tree
compiles can establish, correct.

