# verify-head honesty fixes — 2026-08-01

Fixes for the three defects the final-verification audit found in the
verify-head gate built earlier today, plus the cached-pass-predates-commit
loose end. All work in `zenit/tools/zenit-dev` + `tools/zenit-dev.test.js`,
committed as zenit `00333f2`. Every reconstruction ran in scratch clones under
`scratchpad/verify/`; no real history was touched.

## DEFECT 1 — the clean-worktree fast path made the gate worthless

**Decision: the fast path is REMOVED entirely. verify-head always compiles.**
`ok` now means exactly one thing: THIS COMMAND extracted `git archive HEAD`
into a throwaway dir and compiled it with `--no-build-cache`, and it
succeeded.

Why removal rather than the two alternatives:

- "Honest skip" (report `skipped (assumed fresh)`, compile only under `--all`
  or a flag) still leaves the single-repo default proving nothing unless a
  flag is remembered — a gate that verifies only when asked politely. The
  proof requirement itself ("a plain verify-head run must catch a broken
  HEAD") is unsatisfiable with any default skip.
- "Skip on evidence that the deferred-to build compiled this exact commit" is
  unbuildable without lying: the ordinary build is worktree- and
  fingerprint-based; the only trustworthy evidence that HEAD's content
  compiled is compiling HEAD's content — which is the fast path's cost, so
  there is nothing left to skip.
- The measured cost is bounded and only paid when verify-head/ci is
  explicitly invoked (numbers below).

The `zenit-dev ci` post-green sweep uses the same function, so ci's HEAD
sweep now also compiles every repo for real; `--no-verify-head` remains the
opt-out and the help text states the per-repo cost.

### Before (CLI at 660d671) — journal, 13:48 sweep

`verify-head --all`: 27 repos reported `ok`, exit 0. 26 of 27 were
`"result":"clean-skip"` journal events (verbatim rows preserved in
`~/.local/share/zenit-dev/journal.jsonl`, inv `20260801-134859-1285721`);
only thoth compiled. The build it deferred to was itself a 0.17s fingerprint
no-op that day.

### Before/after on an identical broken-HEAD state (scratch clone, NO probe)

zenit-clone: the illegal `@NonNull com.couchbase.client.java.Collection`
spelling committed FULLY (worktree clean, HEAD 690a06b broken — the state the
old fast path was blind to):

    $ /tmp/claude-1000/old-zenit-dev verify-head        # CLI as of 660d671
    ── Verifying that HEAD compiles (zenit-clone) ──
      ok  zenit-clone: worktree matches HEAD — the ordinary build already builds this commit's content
    exit=0                                              # broken HEAD, "ok"

    $ zenit-dev verify-head                             # fixed CLI, same state
    ── Verifying that HEAD compiles (zenit-clone) ──
      fail  zenit-clone: HEAD 690a06b DOES NOT COMPILE — javac rejected the committed tree
          .../CouchbaseDatasource.java:1056: error: type annotation @org...NonNull is not expected here
      fail  1 repo(s) have a HEAD that does not compile: zenit-clone
            A commit shipped content that was never compiled (staged-then-edited
            partial git add?). Fix the source, commit the fix, and re-run: ...
    exit=1

No probe files, no trickery: a plain run catches it.

### Measured cost (after)

`verify-head --all` on the real workspace, 28 repos (now including
hohenheim via externalRepos):

    28/28 targets, every one REALLY compiled (per-repo `git archive HEAD`
    checkout, --no-build-cache), zero skips, exit 0.
    Wall clock: 20m03s sequential (gradle slot pool = 2; the run also shared
    slots with this session's scratch-clone proofs for part of its window).
    Per-repo range 8s (janeway, duiventil) to 146s (spamservice); typical
    library repo 20-50s. Contrast with the pre-fix sweep: 27 "ok" in ~44s of
    which 26 compiled nothing.

    Notable rows (full output: scratchpad/verify/all-after.txt):
      ok  zenit-forms: HEAD db9ade9 compiles (44s)   } the other agent's
      ok  zenit-media: HEAD adca0bf compiles (39s)   } gradle.properties fix,
      ok  zenit-ai:    HEAD 7b70e74 compiles (23s)   } now PROVEN by a real
                                                       compile, not assumed
      ok  hohenheim:   HEAD 27447e0 compiles (47s)   — first sweep ever to
                                                       cover it (externalRepos)
      ok  zenit-auth-test-support: not a git repository (honest no-git note)

    The sweep read zenit's HEAD before this session's commit landed, so a
    follow-up single-repo run closed the loop:
      ok  zenit: HEAD 00333f2 compiles (assemble testClasses browserTestClasses, 39s)

## DEFECT 2 — infrastructure failures accused humans of partial git adds

Added the BUILD-path twin of the test-path container classifier
(`BUILD_INFRA_SIGNATURES` / `classifyBuildInfraFailure`, same
signature-list-plus-banner shape as `INFRA_FAILURE_SIGNATURES`, deliberately
adjacent in the file). Verdict discipline in `verifyHeadForRepo`:

- **"DOES NOT COMPILE"** requires a SEEN javac diagnostic
  (`sawCompileError`: `*.java:N: error:` or Gradle's "Compilation failed"
  line). Only this verdict carries the staged-then-edited hypothesis.
- **Known environment deaths** (GC thrash, OOM, daemon stopped/disappeared,
  cache-lock timeout) → result `infra`, per-repo detail "could NOT be
  verified — INFRASTRUCTURE, NOT CODE: <reason>. The commit is unverified,
  not proven broken." plus a `▓▓ INFRASTRUCTURE, NOT CODE` summary banner.
  Exit is still non-zero (verification did not complete) but nobody is
  accused.
- **Anything else** → neutral "failed to build, but NO compile error appears
  in the output — read the log before attributing this to the commit."
- A seen javac error alongside infra noise stays a compile verdict (evidence
  in hand).

The journal records `result: infra` + `infraReason`; the ci sweep summary
distinguishes `INFRA — could not verify (…); NOT proven broken` from `FAIL`.

### Reproduced naturally (zenit-forms clone pinned to pre-fix 59f6f57, whose
HEAD lacks gradle.properties, so the archived tree runs on the default heap
and GC-thrashes — the exact audit shape; the live repo was fixed today by the
other agent, hence the pinned clone):

    BEFORE (CLI at 660d671, probe planted to defeat its fast path):
      fail  forms-clone: HEAD 59f6f57 DOES NOT COMPILE — the committed tree differs
            from the worktree your builds ran against
      fail  1 repo(s) have a HEAD that does not build. A commit shipped content
            that was never compiled (staged-then-edited partial git add?).
    (raw log confirms: "Gradle build daemon has been stopped: since the JVM
     garbage collector is thrashing" — the accusation was false)

    AFTER (fixed CLI, plain run):
      fail  forms-clone: HEAD 59f6f57 could NOT be verified — INFRASTRUCTURE, NOT
            CODE: the JVM GC is thrashing — heap too small (is gradle.properties
            with org.gradle.jvmargs missing from the tree?). The commit is
            unverified, not proven broken.
      ▓▓ INFRASTRUCTURE, NOT CODE: 1 repo(s) hit a build-environment failure
      ▓▓ These HEADs are UNVERIFIED, not proven broken. Do NOT report them as
      ▓▓ broken commits. Fix the environment (heap settings, daemon) and re-run.

No accusation of anyone.

## DEFECT 3 — hohenheim had no guard

Root cause: `install-guards`' no-args sweep resolves names through
`getProjectDir` = `FRAMEWORK_ROOT/<name>`; hohenheim lives at
`/home/skerit/projects/hohenext/hohenheim`, silently filtered out as
"missing".

Fix (mechanism, not a hardcoded path):

- New config key `externalRepos` (`~/.config/zenit-dev/config.json` or a
  workspace `.zenit-dev.json`): `{ "<name>": "/abs/path" }`. Machine-local
  paths stay in machine-local config, per convention.
- `getProjectDir` consults it, so external repos are addressable by NAME
  everywhere (verify-head, install-guards).
- `install-guards` (no args) sweeps CI_LEVELS + CI_EXCLUDED +
  externalRepos, and now WARNS loudly when an outside-the-workspace repo has
  no externalRepos entry instead of skipping silently.
- `verify-head --all` includes externalRepos (the audit also flagged that
  the sweep never covered hohenheim).

Installed on this machine: config entry added, `zenit-dev install-guards`
run → `ok /home/skerit/projects/hohenext/hohenheim: pre-commit guard
installed` (29 hooks total; previously 27/28 with hohenheim the hole).

### Proof in a hohenheim scratch clone (never real history)

    $ zenit-dev install-guards "$PWD"     # hohenheim-clone
      ok  .../hohenheim-clone: pre-commit guard installed
    $ echo "// staged" >> SpamserviceCmsContractTest.java && git add ... \
        && echo "// edited after staging" >> ... && git commit -m "repro"
      fail  COMMIT REFUSED: 1 staged file(s) differ from the worktree
          src/browserTest/java/.../SpamserviceCmsContractTest.java
      The tree you are committing is NOT the tree your builds and tests ran
      against (staged, then edited, then committed — the fc89eaf class).
    commit exit=1
    $ git commit --no-verify ...          # deliberate escape stays available
    no-verify exit=0

## ALSO FIXED — the cached-pass-predates-commit trap

**Where the 13:08 cached pass came from:** journal-traced. A real
`BlastCompileGuardTest` run at 13:08:22 (inv `20260801-130807-1161891`,
`tests:1`) saved the per-scope baseline; the auditor's 13:52:36 invocation
(inv `20260801-135234-1289215`) hit Gradle UP-TO-DATE (`tests:0`) and
re-reported that baseline. Gradle's UP-TO-DATE check is CONTENT-based, so the
cached pass was genuinely valid for the (unchanged, clean) worktree — i.e.
for 04e3c9c's content — but the report left the reader to do timestamp
arithmetic against a commit made at 13:22, which reads as the trap.

**Fix:** the cached re-report note now states the relationship explicitly.
When the saved run predates HEAD's commit time it prints, depending on
worktree state:

- clean: "this saved run PREDATES commit <h> (<time>); the worktree matches
  HEAD, so the content it covered IS <h>'s content. Force a fresh run:
  --rerun"
- dirty: "... the worktree does NOT match HEAD, so this cached pass certifies
  the WORKTREE only — do not quote it as evidence for <h>."

**Is verify-head itself immune?** After the fast-path removal: yes, by
construction. Every run extracts into a FRESH temp dir (no build/ outputs →
Gradle's incremental UP-TO-DATE has nothing to match), runs with
`--no-build-cache` (no cache replay), and verify-head never touches the test
baseline files. The one lane that could serve a result predating the commit
under test WAS the clean-worktree fast path (it re-served the freshness
bookkeeping's opinion) — removing it closes the class.

## Tests

`tools/zenit-dev.test.js`: 34 → 38, all green (`node --test`, ~22s).

- UPDATED `verify-head builds the COMMITTED tree in isolation and NEVER
  skips`: broken HEAD must print a javac error to earn DOES NOT COMPILE; a
  CLEAN worktree now provably spawns a real compile (asserted from the fake
  gradlew invocation log) and no skip text may appear.
- NEW `verify-head classifies infrastructure deaths and never accuses
  without a seen compile error`: the audit's daemon-OOM line → INFRA banner,
  no DOES NOT COMPILE, no partial-git-add accusation; unclassified failure →
  neutral verdict; javac error + infra noise → compile verdict.
- NEW `build-infrastructure signatures and compile-error evidence are
  recognized by shape`: unit coverage of `classifyBuildInfraFailure` and
  `sawCompileError` (error-shaped prose without file:line is NOT evidence).
- NEW `install-guards reaches repos outside the workspace root via
  externalRepos`: no-args sweep installs the hook into an external repo, and
  the external name resolves for verify-head.
- NEW `a cached test re-report that predates HEAD says exactly what it can
  and cannot certify`: clean-worktree wording vs dirty-worktree disclaimer.

## Files / commits

- `zenit/tools/zenit-dev` — EXTERNAL_REPOS config + getProjectDir,
  BUILD_INFRA_SIGNATURES/classifyBuildInfraFailure/sawCompileError,
  verifyHeadForRepo (fast path removed, three-way verdict),
  doVerifyHead (bucketed summary + external repos in --all),
  ci sweep messaging, install-guards sweep + loud external warning,
  cached-note HEAD comparison in doTest, help text, header config docs.
- `zenit/tools/zenit-dev.test.js` — tests above.
- zenit commit `00333f2` "🩺 Make verify-head honest: always compile,
  classify infra failures" (subject 69 chars, real gitmoji, stands alone).
  The pre-commit guard ran on this commit and passed silently — correct,
  since staged == worktree; nothing to fire on.
- `~/.config/zenit-dev/config.json` — added
  `"externalRepos": { "hohenheim": "/home/skerit/projects/hohenext/hohenheim" }`.
- Hook installed into the real hohenheim (`.git/hooks/pre-commit`, marker
  `# zenit-dev pre-commit guard`).

## Known limitations

- verify-head still trusts maven-local for upstream artifacts (unchanged,
  documented stance).
- BUILD_INFRA_SIGNATURES is a curated list like its test-path twin; an
  unlisted environment death lands in the neutral "no compile error seen —
  read the log" bucket, which is safe (no accusation) but not self-updating.
- The ci sweep's honest cost is real minutes across the fleet;
  `--no-verify-head` is the documented opt-out.
