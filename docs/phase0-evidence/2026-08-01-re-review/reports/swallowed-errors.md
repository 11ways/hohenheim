# Swallowed javac errors: root cause, root fix, blast radius

## The mechanism, named exactly

**Manifold's `ManLog` Log replacement orphans javac's annotation-processing
`DeferredDiagnosticHandler`; the deferred errors are printed and counted on the
abandoned Log while javac's success check reads the replacement Log, whose
error count is 0.**

Proven step by step (all on javac 25.0.3, Manifold 2026.1.6):

1. Bare `javac` + `-Xplugin:Manifold` on the fc89eaf annotation error: exit 1,
   no classes. NOT the trigger. Duplicate `-Xplugin:Manifold` (the manifold
   Gradle plugin adds it and zenit adds it again): still exit 1.
2. JSR-199 (`task.call()`), with and without a DiagnosticListener: still fails
   correctly. Gradle's invocation style alone is NOT the trigger.
3. Add **any discovered annotation processor** (Gradle's real processorpath
   carries `teavm-extension-annotation-processor`, which registers a
   `javax.annotation.processing.Processor`; manifold-preprocessor does not):
   **error printed, NO "1 error" summary, exit 0, `Repro.class` emitted.**
   Minimal deterministic repro: 2 files, 1 processor, 1 flag.
4. Probe javac plugin proves the split-brain:
   `[GuardProbe] initLog=1546908073 endLog=371439501 endLog.nerrors=0
   initLog.nerrors=1 handlerSawErrors=1` — Manifold lazily swaps the context
   Log mid-compile (`ManLog_11.instance`/`reassignLog`); the deferred handler
   created for AP rounds belongs to the ORIGINAL Log, so the re-reported error
   increments `nerrors` there; `Main`/`JavaCompiler` consult the swapped-in
   ManLog (0) and declare success. A JSR-199 DiagnosticListener still RECEIVES
   the `Kind.ERROR` while `task.call()` returns `true`.
5. Conditionality explained: parse errors abort before AP rounds (never
   deferred) → still fail. That is why "a hard parse error fails, the
   attr-stage annotation error does not".
6. JDK version: on JDK 21 the same Manifold wiring NPEs javac outright
   (`DeferredDiagnosticHandler.getDiagnostics() is null`, exit 4 — loud). JDK
   25's restructured `Log.DeferredDiagnosticHandler` tolerates the corruption
   silently. This is a JDK-25-era Manifold regression.
7. Upstream: Manifold 2026.1.7 AND 2026.1.8 (latest on Maven Central,
   downloaded and tested) still swallow. No version-bump fix exists.

Not involved: Gradle `failOnError` (never set), `-proc:` flags, incremental
compilation, the build cache (it only REPLAYS the lie), zenit-gradle-plugin,
the protoblast compiler stages.

## The root fix

`BlastCompileGuard`, a ~90-line javac `Plugin` in **protoblast-gradle-plugin**
(`be.elevenways.protoblast.gradle.javac.BlastCompileGuard`):

- At init it captures the context Log and installs a `DiagnosticHandler` that
  counts ERROR diagnostics flowing through that Log's handler chain.
- At `TaskEvent.Kind.COMPILATION` finished, **iff the context Log was replaced
  and the effective Log's `nerrors` is 0** while errors were seen/stranded, it
  adds the stranded count back (`effectiveLog.nerrors += swallowed`) and prints
  one banner. javac then fails exactly as if it had counted honestly.
- Scoping: `BlastCompileGuardInstaller` wires the `-Xplugin` arg AND the
  guard's processorpath entry strictly together, per SOURCE-SET compile task
  (the only compiles that have AP rounds). Hawkeye preCompile tasks (no
  processors) and blast-loader compiles (`-proc:none`, `-Xplugin` stripped)
  are immune and untouched. Guard classes are materialized (write-if-different,
  atomic) into `<rootDir>/.gradle/protoblast/compile-guard` — outside buildDir
  so `clean` cannot race it.
- Entry points: `ProtoblastGradlePlugin.apply` (every downstream repo),
  `PublishModulePlugin.apply` (publish-only consumers: hawkeye-core, textum),
  and a direct wiring block in protoblast's root `build.gradle` (it cannot
  apply its own plugin — bootstrap).
- Degrades safely: no Manifold → Log never replaced → guard is a no-op; no
  javac-internals access → guard reports itself inactive and does nothing
  (that pathology needs the same access it just failed to get).

### Why this cannot false-positive on TeaVM @JSBody noise

The guard never reads text. It counts `JCDiagnostic` objects of
`Kind.ERROR` inside javac, and only acts when the javac Log was REPLACED and
javac's own count is zero. TeaVM's `generateJavaScript` is not a javac
invocation; its "Error in @JSBody ..." lines are tool stdout the guard can
never observe. (Current builds emit zero such lines anyway — checked every
retained build log.)

## Proof (verbatim)

**Pre-fix (reconstructed fc89eaf clone, HEAD 947fba0, forced, no cache):**

    > Task :compileServerJava
    .../CouchbaseDatasource.java:1056: error: type annotation
        @org.checkerframework.checker.nullness.qual.NonNull is not expected here
    1 error
    BUILD SUCCESSFUL in 17s
    11 actionable tasks: 11 executed
    (exit 0; CouchbaseDatasource.class emitted, 85606 bytes)

Negative control pre-fix: stripping `-Xplugin:Manifold` from the same task
made it FAIL correctly — Manifold isolated as the necessary ingredient.

**Post-fix (same clone, same commit, guarded plugin from m2):**

    .../CouchbaseDatasource.java:1056: error: type annotation ... not expected here
    [BlastCompileGuard] javac reported 1 error(s) that a javac plugin (Manifold)
        dropped from the compiler's error count; failing the compilation.
    1 error
    > Task :compileServerJava FAILED
    BUILD FAILED in 24s

**Positive control:** clone at fixed HEAD `ea20290`: BUILD SUCCESSFUL, no
guard banner.

**Negative controls, real workspace:** full chain rebuild
protoblast → emberglyph → hawkeye → janeway → zenit → plumage green with the
guard active on every compile, ZERO guard banners (`generateJavaScript`
executed in that run); plumage browser test `CssTokenIntegrityTest` PASSED;
zenit-cms chain rebuild green (zenit-forms, zenit-microcopy, zenit-widget,
zenit-media, zenit-cms recompiled under the guard).

**Permanent tests:** `BlastCompileGuardTest` (protoblast-gradle-plugin, real
javac + real Manifold jars, 6-step journey): plain-javac baseline fails; the
UPSTREAM SWALLOW IS ASSERTED GREEN on purpose (step 2 — when a Manifold
upgrade fixes it, that step fails and tells us the guard can retire); guard
restores the failure; clean source stays green with guard+Manifold; guard
alone neither hides nor invents errors; extraction is idempotent.
zenit-dev node suite: 34/34.

## verify-head simplification

verify-head's `javacErrorLines` output scan was the symptom-level twin of this
fix and is REMOVED (zenit `660d671`); verify-head trusts Gradle's exit again.
Its `--no-build-cache` STAYS: pre-guard poisoned cache entries within the
2-day retention replay broken content green without running javac, and a
verification that replays cache proves nothing. The node test now asserts the
inverse property: error-shaped text in a green build does NOT fail
verify-head (the @JSBody-immunity contract at that level). Residual gap,
documented in the AIDEV-NOTE: verifying a HISTORICAL commit that predates the
guarded plugin could still swallow; all current workspace HEADs resolve the
guarded SNAPSHOT plugin.

## The guard's first live catch: zenit-cms shipped a swallowed error for 8 days

The first guarded rebuild of **zenit-cms** FAILED:

    src/server/java/be/elevenways/zenit/cms/server/page/SettingsEditorBackend.java:144:
        error: type annotation @org.checkerframework.checker.nullness.qual.NonNull
        is not expected here

`refusalFor(@NonNull SettingsEditor.Rejection rejection)` — the same illegal
pattern as fc89eaf (TYPE_USE annotation before a STATIC-nested qualified
type), COMMITTED since `8451cfc` (2026-07-24, "Settings backends + structured
list editing"), worktree clean. Every zenit-cms build and publish for 8 days
compiled this file with the error swallowed and reported green. Fixed to
`SettingsEditor.@NonNull Rejection` (zenit-cms `0e14d2e`), rebuilt green.

Workspace sweep for the same pattern (`@NonNull/@Nullable <Qualified.Type>`
across every repo): one other textual hit, zenit `TaskSchedule.java:236`
`@Nullable TaskClaimManager.Claim` — verified LEGAL by direct javac test
(`Claim` is a non-static INNER class, where the leading-annotation spelling is
admissible; the static-nested analog errors). zenit's green is honest.

## Poisoned cache entries

- No remote/shared build cache exists (no `buildCache` block in any
  settings.gradle, no init scripts, no ~/.gradle config). Local only:
  `~/.gradle/caches/build-cache-1`, 15,635 entries, 2-day retention.
- Scanned all 12,083 entries younger than 2 days for
  `CouchbaseDatasource.class` (99 hits), hashed and classified every one
  against known-good compiles.
- Key forensic fact: a swallowed compile of the ILLEGAL source produces
  output **byte-identical** (md5 `65a834cb...`) to the legal-simple-spelling
  compile — javac records the type annotation before erroring on it. So the
  poisoned entry's PAYLOAD is not corrupt; its VERDICT ("this content
  compiles") is the lie, keyed on the illegal source.
- fc89eaf class: exactly ONE such entry existed:
  `7a39b503c2753c0d685d624f448d5aa5`, stored 2026-08-01 12:29:51 by the
  verify-head agent's cached reconstruction run. It would have replayed the
  broken fc89eaf tree as a green cache hit. **Deleted.** Every other matching
  entry corresponds to an honest compile of legal content.
- zenit-cms class (found after the guard's live catch): every cached
  zenit-cms compile containing `SettingsEditorBackend.class` and created
  BEFORE the fix build is keyed on the illegal 07-24 source — a green-verdict
  lie for trees that do not legally compile. 51 entries found; the 49
  pre-fix ones **deleted**, the 2 post-fix entries (13:35:10/:12, fixed
  content) kept. The failing 13:27 build cached nothing (failed tasks are
  never stored).
- Going forward the guard makes swallowed compiles FAIL, and Gradle never
  caches failed tasks — this poisoning class is closed at the source.

## Blast radius

- 16 repos compile with Manifold: protoblast, hawkeye(hawkeye-core), zenit,
  textum, zenit-{auth,pages,a2ui,ai,forms,media,microcopy,oidc,cms,flow,
  comms,widget}. Every one applies the protoblast plugin or the publish
  plugin (hawkeye-core), or is protoblast root (wired directly) → guard
  coverage == Manifold exposure. Apps (quirkyquarters, hohenheim, orcono,
  spamservice, thoth, herald, ...) do not use Manifold: not exposed.
- Are any published artifacts built from a swallowed-error compile?
  **YES — one: zenit-cms.** Every zenit-cms-server publish since 2026-07-24
  was built from a tree containing the illegal `SettingsEditorBackend`
  annotation with the error swallowed. The emitted bytecode is functionally
  intact (javac records the annotation before erroring — proven byte-identical
  behavior on the fc89eaf analog), so this shipped no broken bytes, but the
  repo's "compiles" verdict was false for 8 days and any additional attr-stage
  error introduced in that window would have shipped silently too. Fixed
  (`0e14d2e`) and republished green.
- Everything else: **clean, proven by guarded recompilation of every
  Manifold-using repo.** Post-guard rebuilds all green with zero guard
  banners: protoblast, emberglyph, hawkeye, janeway, zenit, plumage,
  zenit-microcopy, zenit-widget, zenit-forms, zenit-media, zenit-pages,
  zenit-cms (after the fix), zenit-auth, zenit-a2ui, zenit-ai, zenit-flow,
  zenit-comms, zenit-oidc, textum.

## The 11:02-11:11 builds: solved with raw logs

The raw failing logs survived in /tmp (`zenit-dev-build.1000401/1000780/
1001947/1004486.log`). `:compileServerJava FAILED` — but the failure was
driven by **enter-stage `Locale` errors in `HttpConduit.java`** from the
concurrent locale-folding work:

    HttpConduit.java:68: error: a type with the same simple name is already
        defined by the single-type-import of Locale
    HttpConduit.java:95: error: reference to Locale is ambiguous
    ...
    CouchbaseDatasource.java:1056: error: type annotation @...NonNull is not
        expected here

Import-clash errors are raised at ENTER, before/outside the deferred-AP path,
so they fail honestly — and with the compile failing, the annotation error got
reported and counted alongside. The hotfix narrative ("builds failed on the
annotation") is wrong in attribution: ALONE, the annotation error is provably
swallowed green under the pre-fix toolchain. What actually broke those builds
was the Locale import clash; both files were fixed on disk by 11:11:31 and the
11:12 build went green.

## Commits

- protoblast `04e3c9c` — "🛡️ Fail compiles whose javac errors Manifold
  swallowed" (guard plugin + installer + root wiring + service file +
  BlastCompileGuardTest; 8 files, +454).
- zenit `660d671` — "🧹 Trust Gradle exit in verify-head; swallow fixed in
  javac" (scan removed, comments updated, test inverted; +29/-54).
- zenit-cms `0e14d2e` — "🐛 Fix illegal @NonNull on qualified Rejection type"
  (the guard's first live catch; 1 line).

Both verified with `git log -1`; subjects stand alone under 72 chars; the
pre-commit guard ran and passed (staged == worktree — nothing to refuse, which
is correct behavior).

## Known limitations / notes

- The guard triggers only when the javac Log was replaced (the exact
  pathology); a hypothetical future swallow class that lies WITHOUT replacing
  the Log would need its own analysis. BlastCompileGuardTest step 2 is the
  tripwire for retiring the guard when Manifold fixes the bug upstream
  (2026.1.8 is still broken; worth filing upstream with the 2-file repro in
  scratchpad/manifold-repro).
- Verifying historical commits that predate the guarded plugin can still
  swallow (documented in verify-head's AIDEV-NOTE).
- The best-effort preScan compile also runs the guard; a swallowed error
  there only flips its already-tolerated best-effort flag, and the guard's
  banner goes through javac's Log error writer, which preScan routes to its
  diagnostics file — no console noise on green builds (verified: zero banners
  across all post-fix green builds).
