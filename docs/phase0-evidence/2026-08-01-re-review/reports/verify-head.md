# verify-head: closing the "nothing ever builds HEAD" gap

Companion to `never-compiled-forensics.md`. Everything below was proven against a
real reconstruction of the fc89eaf incident in a scratch clone of zenit
(`scratchpad/verify/zenit-clone`); no real history was rewritten and no
worktree of any live repo was mutated.

## What shipped (zenit repo, tools/zenit-dev + tools/zenit-dev.test.js)

1. **`zenit-dev verify-head [projects|--all] [--keep]`** — compiles the
   COMMITTED tree from a throwaway `git archive HEAD` checkout. Clean worktree
   (no tracked or untracked changes) = instant skip, because the worktree IS
   HEAD and the ordinary build already proved it.
2. **Pre-commit tripwire** — `zenit-dev precommit-guard` + a hook installer
   (`zenit-dev install-guards`). Refuses any commit containing a
   staged-then-edited file (staged content != worktree content), scoped to the
   files IN the commit so the workspace's many concurrently-dirty files never
   trip it. Bypass: `git commit --no-verify` or `ZENIT_DEV_ALLOW_SPLIT_COMMIT=1`
   (bypass output points at verify-head). Cost: two `git diff` calls, ~150 ms
   including node startup.
3. **Exit-code/counts disagreement banner** — a test run whose parsed events
   all pass while Gradle exits non-zero now prints a `▓▓ EXIT CODE DISAGREES
   WITH TEST COUNTS` banner NAMING the failed non-test task(s), stamps the
   disagreement into the RESULT line and the saved log, and journals
   `failedTasks` on the `test.phase` event.
4. **`zenit-dev ci`** runs a verify-head sweep over all participating repos
   after a fully green run (`--no-verify-head` to skip). Clean repos skip
   instantly.

## NEW toolchain finding (bigger than the brief expected)

**zenit's Manifold-plugged `compileServerJava` SWALLOWS the fc89eaf javac
error.** Forced execution on the reconstructed broken HEAD:

    > Task :compileServerJava
    .../CouchbaseDatasource.java:1056: error: type annotation
        @org.checkerframework.checker.nullness.qual.NonNull is not expected here
    1 error
    BUILD SUCCESSFUL in 3s        (exit 0, CouchbaseDatasource.class EMITTED)

A hard parse error (tested: mangled parameter list) fails the task correctly;
this attr-stage annotation-placement diagnostic does not. Two consequences:

- The forensic report's claim "the toolchain caught it immediately at ~11:01"
  needs a caveat: with `-Xplugin:Manifold` active, at least this diagnostic
  class produces a GREEN build over broken source. (The live 11:02-11:11
  failures were real, but whatever failed there was not a bare
  compileServerJava of this file — unresolved, see Loose ends.)
- Worse: the successful-but-erroring task is CACHED. A later build of the same
  content replays FROM-CACHE with no diagnostic at all.

verify-head therefore does NOT trust Gradle's exit code: it (a) scans the
build output for `*.java:N: error:` diagnostics and fails a "green" build that
printed any, and (b) runs with `--no-build-cache` so a poisoned cache entry
can never replay a broken HEAD to green (this closes the "first run fails,
second run flips green" retry trap). The verify task set never runs TeaVM JS
generation, so the known @JSBody noise cannot false-positive the scan.

**Recommended follow-up (not done here, unrelated-code rule):** the same
swallow affects ordinary worktree builds and publishes. `runGradle` could get
the same javacErrorLines guard, but that path DOES run TeaVM tasks in consumer
apps, so the false-positive risk needs its own assessment first.

## Design decisions and rejected alternatives

**Checkout mechanism: `git archive HEAD | tar -x`**, not `git worktree add`.
Read-only against the repo (no index.lock risk, no .git/worktrees metadata, no
interaction with four concurrent agents' git state), preserves file modes, and
archives from the repo TOPLEVEL so nested projects (orcono/mvp-v01) verify from
their subpath.

**Scope: compile-only** — `assemble testClasses` (+ `browserTestClasses` where
src/browserTest exists). Proves every published source set, test sources, and
(via assemble's template compilation) committed .hwk templates. Deliberately
excludes: publishToMavenLocal (m2 must stay untouched), TeaVM JS (cost +
JSBody noise), test EXECUTION (the bug class is "does the commit compile").

**Concurrency:** the throwaway dir is unique per run, so the per-directory
Gradle exclusion (taken automatically by the runGradleRaw funnel) never
contends with live builds; it holds one gradle slot like every bounded spawn;
it takes NO repo build lock because it publishes nothing. Passing the repo's
own org.gradle.jvmargs reuses the repo's daemon. `~/.m2` is read-only to it.

**Where it fires (the honest answer):**
- The pre-commit hook is the always-on catcher and would have stopped BOTH real
  incidents at `git commit` time, in milliseconds. This is the piece that
  actually fires, because it needs no one to remember anything. Installed via
  `zenit-dev install-guards` into the workspace repos (see below).
- `verify-head` fires (a) inside `zenit-dev ci` after green runs, (b) manually
  after a deliberate `--no-verify` split commit (the refusal/bypass text says
  so). A push gate was rejected: almost nothing is ever pushed here, so it
  would never run. A per-commit full verify was rejected: measured cost below
  makes it hostile to the way agents work; the ms tripwire covers commit time.

**Refuse vs warn for the tripwire:** refuse. A warning above a successful
commit scrolls past — that is how the incident survived twice. `git commit -a`
and `git commit <paths>` use a temporary index and never trip the guard;
interactive hunk staging is unsupported in this environment, so the legitimate
split-commit case is rare and has two explicit bypasses.

**Multi-repo:** per-repo command with explicit names or `--all` (CI_LEVELS
vocabulary); ci sweep covers the fleet. No new DAG machinery — verification
has no cross-repo ordering needs; upstream artifacts come from maven-local
exactly as live builds consume them. A repo whose deps were never published
fails with the normal unresolved-dependency error (build the chain first).

## Measured cost

- Pre-commit guard: ~0.15 s per commit (node startup dominates).
- verify-head fast path (clean worktree): < 1 s, no Gradle spawn.
- verify-head real run on zenit (largest repo, fresh archive checkout,
  `assemble testClasses browserTestClasses`):
  - with build cache (REJECTED design): 43 s — but provably unsafe (poisoned
    cache replays broken content green).
  - with `--no-build-cache` (shipped): 49 s (cold daemon) / 33 s (warm daemon) — barely worse than the cached 43 s, because configuration + non-compile tasks dominate — this is the honest
    price of "always compiles for real".
- ci sweep: clean repos ~0 s each; each dirty repo pays the full-compile price
  for its size. With most repos dirty this adds real minutes to ci; ci is the
  declared completeness ritual, and `--no-verify-head` exists.

## Verbatim counterfactuals (pre-fix CLI = commit 15ef23e)

**A. The fc89eaf reconstruction commits silently today.** In the clone:
illegal `@NonNull com.couchbase.client.java.Collection` staged, legal spelling
restored on disk (git status `MM`), then:

    $ git commit -m "🔒 repro of fc89eaf: staged-then-edited commit"
    [master 01f1abd] 🔒 repro of fc89eaf: staged-then-edited commit
     1 file changed, 1 insertion(+), 1 deletion(-)
    commit exit=0

    HEAD:     private boolean assignDocumentWithCas(@NonNull com.couchbase.client.java.Collection collection,
    worktree: private boolean assignDocumentWithCas(com.couchbase.client.java.@NonNull Collection collection,

**B. No command verifies HEAD today.**

    $ zenit-dev verify-head
      fail  Unknown command: verify-head

**C. Exit/counts disagreement (pre-fix output, fixture reproducing the
2026-08-01 shape — all tests pass, non-test task fails):**

    ── Unit tests (app) ──
      ✓ 3 unit tests passed
      fail  Gradle exited with code 1 — the unit task did not complete cleanly; results above may be partial (...)
      ✗ RESULT: FAILED — 3 unit passed

    (exit 1, but the counts line is green, the outcome detail literally reads
    "3 unit passed", the diagnosis "may be partial" is WRONG — results were
    complete — and the failing task is never named.)

## Post-fix proof (same reconstructions)

**A'. The guard refuses the exact incident:**

    $ git commit -m "🔒 repro of fc89eaf with guard installed"
      fail  COMMIT REFUSED: 1 staged file(s) differ from the worktree
          src/server/java/be/elevenways/zenit/server/orm/CouchbaseDatasource.java
      The tree you are committing is NOT the tree your builds and tests ran
      against (staged, then edited, then committed — the fc89eaf class).
      Fix:        git add src/.../CouchbaseDatasource.java   (commit what is actually on disk)
      Deliberate: git commit --no-verify  (or ZENIT_DEV_ALLOW_SPLIT_COMMIT=1),
                  then prove the commit compiles: zenit-dev verify-head
    commit exit=1

**B'. verify-head catches the broken HEAD (forced past the hook with
--no-verify), compiling zenit for real from the archive checkout:**

    $ zenit-dev verify-head            # in the clone, HEAD 947fba0 = illegal spelling
    ── Verifying that HEAD compiles (zenit-clone) ──
      fail  zenit-clone: HEAD 947fba0 DOES NOT COMPILE — Gradle exited 0 but javac
            reported 1 error(s) the build swallowed (Manifold); treat as broken
          .../CouchbaseDatasource.java:1056: error: type annotation
              @org.checkerframework.checker.nullness.qual.NonNull is not expected here
      fail  1 repo(s) have a HEAD that does not build. ...
    exit=1, 49 s (cold daemon), 33 s warm

    Positive control (fix committed, worktree dirtied with an untracked file):
      ok  zenit-clone: HEAD ea20290 compiles (assemble testClasses browserTestClasses, 33s)
    Clean-worktree fast path on the real zenit repo: 0.1 s, no Gradle spawn:
      ok  zenit: worktree matches HEAD — the ordinary build already builds this commit's content

**C'. Post-fix disagreement output (same fixture, now a permanent node test):**

    ✓ 3 unit tests passed
    ▓▓ EXIT CODE DISAGREES WITH TEST COUNTS — this run is NOT green
    ▓▓ All 3 unit tests passed, but Gradle exited 1
    ▓▓ because a NON-TEST task failed: :app:zenitDevTest
    ▓▓ Do NOT report this run as passing. Raw output: zenit-dev test-log --full
    ✗ RESULT: FAILED — 3 unit passed, all 3 unit tests passed but :app:zenitDevTest FAILED

## The 2056-run loose end: CONFIRMED in shape, exact child unrecoverable

Journal facts (all UTC):
- inv `20260801-104836-940691` (`zenit-dev test --unit --no-fail-fast --rerun`,
  zenit): gradle graph `test --rerun t01Verification --rerun`, `exit:1,
  total:2056, passed:1981, failed:0, skipped:75` (08:48:36 → 08:53:25).
- The exit 1 came from a t01Verification child (zero of 2056 test events
  failed). t01Verification = pluginContractTest + zenitDevTest +
  artifactContractTest + browserTestSupportConsumerCompile + generatedAppSmoke.
- The zenit-dev node suite EXECUTED inside that window: its dirlock test
  leaves a distinctive `gradle exclusion for repo (test)` lock.wait/acquired
  pair in the GLOBAL journal — one at 08:50:31 (inv 20260801-105026-946592)
  sits squarely inside the gradle run. So :zenitDevTest ran.
- The same t01Verification graph was GREEN at 08:47:48 (inv
  20260801-104718-937358 built zenit `assemble publishToMavenLocal
  t01Verification`, exit 0), and between 08:47:57 and 08:48:40 that same
  invocation republished plumage, zenit-forms, zenit-microcopy, zenit-widget,
  zenit-media, zenit-cms into the shared m2 — exactly the documented
  "m2 jar replaced outside this workspace" concurrent-republish trap for the
  CLI suite. Immediately after (08:53 → 09:00) an agent ran the node suite
  directly ~10 times while editing tools/zenit-dev (uncommitted edits, file
  mtimes 09:33/09:52 UTC).
- Verdict: the "t01Verification child failed, almost certainly :zenitDevTest
  under concurrent m2 republish and/or in-flux CLI edits" explanation is
  CONSISTENT with every journal fact and with the green run 47s earlier; the
  raw log that would name the child was rotated away (testLogMax=10, >10 runs
  since). Not refutable, not further confirmable. The new `failedTasks`
  journal field makes this class attributable from the journal alone next time.
- Note: even pre-fix, that run DID print `RESULT: FAILED` and exit 1 — the
  failure was legible as a failure; what was missing (and now fixed) is the
  loud statement that the counts DISAGREE with the exit and the NAME of the
  non-test task, which is what lets a counts-reading agent misdiagnose.

## Where things run / what was installed

- Hooks installed via `zenit-dev install-guards` into workspace repos:
  28 repos (protoblast, emberglyph, hawkeye,
  janeway, zenit, plumage, textum, zenit-microcopy, zenit-oidc, zenit-forms,
  zenit-widget, zenit-media, zenit-a2ui, zenit-flow, zenit-cms, zenit-pages,
  zenit-auth, zenit-ai, zenit-comms, duiventil, orcono/mvp-v01, proteus,
  herald, quirkyquarters, spamservice, thoth, testbeds/todomvc/skeritcom,
  testbeds/todomvc/todomvc-zenit). Skipped as not-git: zenit-auth-test-support,
  arcana. hohenheim lives outside the workspace root — run
  `zenit-dev install-guards <path>` there if wanted.
- install-guards never clobbers a foreign pre-commit hook (marker-checked,
  tested), resolves the real hooks dir via `git rev-parse --git-path hooks`,
  and dedupes repos that share a git dir.

## Tests

`tools/zenit-dev.test.js`: 30 pre-existing tests stay green; 5 added
(35 total, ~19 s):
- failedGradleTasks parses both log spellings.
- precommit guard journey (refuse divergent staged file scoped to the commit;
  env bypass; re-add passes; REAL `git commit` refused via installed hook;
  --no-verify escape; idempotent reinstall; foreign hook preserved).
- verify-head journey (broken HEAD fails while worktree is fixed; build runs
  only in a throwaway dir — asserted from the fake-gradlew invocation log;
  clean worktree skips with zero Gradle spawns; dirty-but-good HEAD passes;
  swallowed-javac-error green build FAILS).
- javacErrorLines recognition.
- full doTest run over a fixture reproducing the 2056-run shape asserts the
  disagreement banner, the named task, and exit 1.

## Commits (zenit)

- `c51e09c` 📢 Name failed tasks when exit code disagrees with test counts (failedGradleTasks helper, banner, RESULT-line detail, journal failedTasks, 2 tests)
- `7022bfb` 🛡️ Verify that HEAD compiles and refuse staged-then-edited commits (verify-head + javacErrorLines + --no-build-cache, precommit-guard, install-guards, ci sweep + --no-verify-head, help, 3 tests)

## Known limitations / follow-ups

- The Manifold javac-error swallow affects ordinary builds/publishes too;
  verify-head defends itself, but `runGradle`/`runGradleStreaming` still trust
  Gradle's exit. Needs a decision (TeaVM @JSBody noise makes a blanket scan
  risky there).
- What actually failed in the LIVE 11:02-11:11 builds is now genuinely open
  again (bare compileServerJava of the illegal file is provably green under
  Manifold); the hotfix commit's narrative should not be treated as the full
  story.
- verify-head trusts maven-local for upstream artifacts (same trust as live
  builds); it verifies each repo's HEAD against CURRENT upstream publishes,
  not against "HEAD of the whole chain".
- Root CLAUDE.md / zenit CLAUDE.md not updated (workspace root is not a git
  repo; left to the maintainer): suggested one-liner for the Git section —
  "After committing from a dirty worktree, run `zenit-dev verify-head`; the
  pre-commit guard refuses staged-then-edited files."
