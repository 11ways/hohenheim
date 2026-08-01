# Autoload scan silence — closed (2026-08-01)

The compile-time autoload scan (`PerformClassGraphScanTask` -> `BlastAutoLoadIndex.scanAndWrite`,
protoblast) silently tolerated unreadable/missing/mid-replacement classpath jars and emitted an
INCOMPLETE `BlastAutoLoadInit` in a GREEN build. Now every archive entry is validated before the
scan and fingerprint-checked after it; any read failure names the entry and the consequence and
fails the build. One bounded retry covers the routine concurrent-republish window.

## 1. The exact tolerance found (read from source, pre-fix)

Three silent lanes, all in protoblast:

1. `BlastAutoLoadIndex.scanAndWrite` (protoblast-compile) handed the classpath to ClassGraph
   with NO validation. ClassGraph 4.8.172 skips any classpath element it cannot open — missing
   file, truncated jar, corrupt central directory, permission error, jar replaced mid-read —
   without any error surfaced to the caller. `scan()` returns normally and the index is written
   minus everything those jars contribute. This is the lane that hit zenit-cms (browserTest index
   written 17:39:49 while `zenit-widget-server.jar` was replaced at 17:40:05 -> 0 widget entries)
   and orcono (loader referencing a class absent at run time -> `NoClassDefFoundError` from
   `BlastAutoLoadInit.<clinit>`).
2. `ClasspathPresence.containsResource` (protoblast-compile) answered `false` ("class absent")
   for a NONEXISTENT classpath entry of any kind. A jar vanished mid-republish therefore silently
   dropped every conditional (`whenPresent`) entry gated on it AND — via `BlastAutoLoadTask`'s
   off-runtime filter — every unconditional entry whose class lives in that jar. (An entry that
   exists but is an unreadable zip already threw; only absence was silent.)
3. `BlastAutoLoadIndex.read` returned an EMPTY map for a missing index file. Defensive silence;
   in practice shielded by Gradle's `@InputFile` validation, but the same disease shape.

`PreScanCompileTask` is best-effort BY DESIGN (documented AIDEV-NOTE: sources depending on
not-yet-generated code cannot resolve; the scan just sees fewer OWN classes, which the real
compile then covers). Left as is — it is not a silent-loss lane for dependency jars.

## 2. Pre-fix reproduction (verbatim)

### Unit level (`BlastAutoLoadScanToleranceTest`, defect-asserting version, run 20260801-185543,
protoblast suite PASSED 2823):

    [control] index entries: [sample.Holder]
    [defect:truncated] scanAndWrite SUCCEEDED over a truncated jar (1280 -> 512 bytes); index entries: []
    [defect:missing] scanAndWrite SUCCEEDED over a nonexistent jar path; index entries: []

### Build level (`AutoloadScanToleranceFunctionalTest`, TestKit, run 20260801-185715, PASSED):

A consumer with `implementation files('libs/dep.jar')` where dep.jar carries one autoload entry:
intact jar -> loader contains `sample.dep.Holder`. Jar truncated to 40% (central directory gone,
i.e. a jar mid-replacement), `generateBlastAutoLoadInit` re-run:

    [defect:build] outcome over truncated dep.jar: SUCCESS
    [defect:build] loader contains sample.dep.Holder: false

and from the forwarded Gradle output of that run:

    BUILD SUCCESSFUL in 7s
    13 actionable tasks: 6 executed, 7 up-to-date

That is the defect verbatim: BUILD SUCCESSFUL, loader silently incomplete.

## 3. Policy chosen, and the argument

**"Legitimately not there" vs "there but unreadable" are split by entry SHAPE:**

- A nonexistent DIRECTORY-like entry (no archive extension) is a lazily-created Gradle output
  dir (classes/resources dirs of empty source sets appear on classpaths before existing). These
  stay silently skipped — that is the legitimate case the old tolerance was covering.
- A nonexistent ARCHIVE path (`.jar`/`.zip`/`.war`) can only mean the resolved artifact vanished
  after Gradle resolution — a republish race or a broken cache. LOUD failure naming the entry.
- An EXISTING archive must be structurally readable: it is opened as a `ZipFile` and its full
  entry list walked (validates the central directory) BEFORE the scan. Truncation, corruption,
  permission errors -> LOUD failure naming the entry, the underlying zip error, and the
  consequence ("would silently omit every registration this jar contributes and emit an
  INCOMPLETE BlastAutoLoadInit in a green build").
- A legitimately absent OPTIONAL dependency never reaches the scan at all — optionality is
  expressed at resolution time (the dependency simply is not on the classpath) or via
  `whenPresent` conditions; both keep working (negative controls below).

**Concurrency (jar replaced mid-scan):** every archive is fingerprinted (size + mtime) before
the scan and re-checked after ClassGraph finishes, BEFORE the index is written — an untrusted
scan can never replace a good index. A change raises `ClasspathUnstableException`. Because
concurrent `~/.m2` republishes are routine here (parallel agents), the task grants exactly ONE
bounded retry, logged as a Gradle warning; the retry re-runs the FULL validation, so it can
never mask real corruption (a corrupt/missing jar fails validation immediately and never
retries). A second instability fails the build with the retry-able message.

**`ClasspathPresence`:** a missing archive on the presence classpath now throws (same message
discipline); a missing directory-like entry still answers "absent". This closes the second lane
(silent conditional drops + silent off-runtime drops).

**`BlastAutoLoadIndex.read`:** a missing index now throws `NoSuchFileException` — it is always
produced by a wired task, so absence is a build bug, never an empty result.

## 4. Completeness assertion — considered, and what was (not) built

The scan cannot non-circularly know what it "should" find: the only authority on which classes
in a jar carry autoload markers is reading that jar — which is exactly the operation being
guarded. A per-jar manifest of "my own entries" embedded at publish time would be the
complete-by-construction anchor, but (a) it only catches the residue "jar readable and valid yet
ClassGraph skipped entries inside it", which the structural zip validation + stability check
already reduce to malformed-classfile territory javac does not produce; (b) it cannot catch the
stale-but-valid-jar or missing-dependency shapes either (the manifest rides inside the same jar);
and (c) it would touch the packaging of every module in the chain for that residue. Per the
no-speculative-scaffolding rule I did not build it. What ships instead is element-level
completeness: every classpath element is either scanned, provably-legitimately absent (directory
shape), or the build fails — and the index is only written from a scan whose inputs were stable
end-to-end.

## 5. The fix (files)

- `/home/skerit/projects/javaweb/protoblast/protoblast-compile/src/main/java/be/elevenways/protoblast/compile/BlastAutoLoadIndex.java`
  — `validateClasspath` (missing-archive refusal, absent-dir skip, `validateArchive` full zip
  walk), `fingerprintArchives`/`verifyStable` around the scan, `ClasspathUnstableException`,
  bounded-retry `scanAndWrite(classpath, target, onRetry)` overload, loud `read`. Verification
  runs BEFORE the index write.
- `/home/skerit/projects/javaweb/protoblast/protoblast-compile/src/main/java/be/elevenways/protoblast/compile/ClasspathPresence.java`
  — missing archive throws; `looksLikeArchive` shared helper.
- `/home/skerit/projects/javaweb/protoblast/protoblast-gradle-plugin/src/main/java/be/elevenways/protoblast/gradle/PerformClassGraphScanTask.java`
  — calls the retrying variant, logs the one retry as a warning.

Tests:
- `/home/skerit/projects/javaweb/protoblast/protoblast-compile/src/test/java/be/elevenways/protoblast/compile/BlastAutoLoadScanToleranceTest.java`
  (4 journeys: loud truncated + loud missing + tolerant absent dir; mid-scan replacement retries
  once then fails loud / settles on retry with a COMPLETE index; loud missing index read; loud
  missing presence archive + tolerant absent presence dir).
- `/home/skerit/projects/javaweb/protoblast/protoblast-gradle-plugin/src/test/java/be/elevenways/protoblast/gradle/AutoloadScanToleranceFunctionalTest.java`
  (TestKit: intact-jar control, then truncated jar -> `buildAndFail`).

## 6. Post-fix proof (verbatim)

Protoblast suite run 20260801-190051: **PASSED — 2825 unit passed, 0 failed** (2820 baseline
+ 4 tolerance journeys + 1 functional). The build-level failure now reads:

    > java.io.IOException: Unreadable classpath entry /tmp/junit.../consumer/libs/dep.jar
      (java.util.zip.ZipException: zip END header not found). The autoload scan would silently
      omit every registration this jar contributes and emit an INCOMPLETE BlastAutoLoadInit in
      a green build; refusing. If the jar is being replaced concurrently, re-run the build.

Negative controls, same green run:
- `conditionalMarkerIsSkippedWhenRequiredClassIsAbsent` (legitimately absent optional
  dependency -> build still succeeds, conditional entry skipped by declared policy) — PASSED.
- `emptyProjectStillGeneratesBlastAutoLoadInit` — PASSED.
- `nonexistentClasspathEntriesAreSkipped` (directory-like absent presence entry) — PASSED.
- Step 1 of the tolerance journey: valid jar + nonexistent directory entry scans green.

## 7. Consumer verification (the two incident repos)

Chain rebuilt through `zenit-dev build` after republishing the fixed protoblast: zenit-cms chain
build green in 321s (protoblast -> emberglyph -> hawkeye -> janeway -> zenit -> plumage -> ...
-> zenit-cms incl. TeaVM cms.js), orcono build green in 137s. Every one of those builds ran the
new validated scan — zero false refusals at production scale (a second negative control).

**zenit-cms** — suite PASSED: 534 unit + 49/49 browser (the six false failures' exact suite).
Scan-index entry counts per contributing module (fresh, 19:07-19:08):

| source set | total | widget | cms | server | common | hawkeye | microcopy | pages | media | forms | generated | plumage |
|---|---|---|---|---|---|---|---|---|---|---|---|
| common | 63 | 15 | 5 | - | 18 | 10 | 5 | - | 3 | 3 | 4 | - |
| server | 99 | 17 | 8 | 21 | 18 | 12 | 7 | - | 5 | 5 | 5 | 1 |
| test | 107 | 17 | 9 | 21 | 18 | 14 | 7 | 5 | 5 | 5 | 5 | 1 |
| browserTestCommon | 62 | 15 | 7 | - | 18 | 10 | - | - | 3 | 3 | 5 | 1 |
| **browserTest** (the incident lane) | **127** | **17** | 27 | 21 | 18 | 16 | 7 | 5 | 5 | 5 | 5 | 1 |

The generated browserTest `BlastAutoLoadInit.java` itself carries 127 refs including all 17
zenit.widget entries (the incident loader had 0 of them in a green build).

**orcono** — browser suite PASSED 38/38 (the incident was 8/8 `initializationError` via
`NoClassDefFoundError` from `BlastAutoLoadInit.<clinit>`). Loader refs per source set:
common 13, client 84, server 126, test 126, browserTest 126 — server/test/browserTest are
byte-identical in module distribution (widget 17, orcono 14, hawkeye 16, auth 7, cms 8,
microcopy 7, media 5, forms 5, plumage/protoblast 1 each, zenit.common 47, zenit.server 22,
zenit.generated 7). The exact class the incident loader could not resolve —
`be.elevenways.hawkeye.generated.plumage.HawkeyeCustomElementRegistrations` — is present in the
browserTest loader and resolved at run time (all 38 tests boot through the `<clinit>`).

## 8. Commits

- protoblast `2791d2724e067d72710ee3f78114ffadd91a163d`
  "🛡️ Fail the autoload scan loudly on unreadable classpath entries" (5 files, +529/-7).
  protoblast worktree clean after commit.

No other repo was modified. The concurrent session's zenit worktree was never touched; it is now
clean at its own `61c5590`.

## Surfaced separately (not touched)

- `zenit-dev` routes a `--class`-filtered protoblast run only to `:test` or
  `:protoblast-gradle-plugin:test`; a class living in `protoblast-compile` matches nothing and
  the run fails ("no unit tests matched"). Worked around with full-suite runs (25-40s); worth a
  one-line routing addition in `zenit/tools/zenit-dev` (`getGradleTestTask`), not done here
  because the zenit worktree is mid-flight under another session.
