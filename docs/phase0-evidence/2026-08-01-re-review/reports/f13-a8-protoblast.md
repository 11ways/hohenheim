# F13 + A8 residual — protoblast (RuntimeClasspathGuard / functional-test repo)

Repo: protoblast, base HEAD c76381a. Commits: F13 = `d5b6938`, A8 = `5a9adbe`.

## Verified numbers (re-measured from the real jars, not taken from recon)

- `protoblast-client-0.1.0-SNAPSHOT.jar`: 41 `org/teavm/**.class` entries (36 sources).
- `protoblast-server-0.1.0-SNAPSHOT.jar`: ALSO 41 (compileJava folds `sourceSets.teavm.java`
  into the server output) — the recon missed that the SERVER artifact is a patch carrier too,
  which is why carrier detection had to be content-based over jars AND directories.
- Duplicated against upstream 0.15.0 jars: exactly **10 FQNs**
  - vs `teavm-classlib`: `TUUID`, `TConcurrentHashMap`
  - vs `teavm-jso-apis`: `Event, InputEvent, KeyboardEvent, MessageEvent, MouseEvent, Touch, TouchEvent, WheelEvent`
- Upstream teavm 0.15.0 jars pairwise: 0 duplicates (5545 entries, 5545 unique).
- The old wildcard exempted ~5,586 entries (5,545 upstream + 41 patched) to sanction 10.

## Item 1 (F13) — design

The exemption is no longer a name wildcard; it is **derived at check time from carrier
content on the actual classpath**. In `checkUniqueness`, `org/teavm/**` names are collected
per provider (in classpath order). For any org/teavm FQN with >1 provider:

- exactly 1 provider is a `TeaVmPatchLane.isPatchCarrier` entry AND exactly 1 is not
  → sanctioned, **but only if the carrier comes FIRST**; carrier-after-upstream throws a
  dedicated "TeaVM patch lane order violation" GradleException (the patched copy would
  silently lose the lottery — the assertion the lane never had);
- 0 carriers (the two-TeaVM-versions shape), 2+ carriers, or 3+ providers → a normal
  duplicate-classes failure, same as any other FQN.

`TeaVmPatchLane.isPatchCarrier` was generalized to classes DIRECTORIES (protoblast's own
`build/classes/java/server|client` are carriers); the fat-jar-facing `patchedEntries`
API is unchanged (spamservice/thoth call sites verified byte-identical in behavior for
jar inputs). `TEAVM_PREFIX` is now the single shared definition (package-visible).

**Drift handling — chose "derived, fails loud":** there is NO static list of patched FQNs
anywhere, so the patched set cannot drift from reality by construction.
- Patch added in protoblast → automatically sanctioned + order-checked (carrier content).
- Upstream stops shipping a patched class → no duplicate exists → nothing to enforce
  (the patched copy is the only copy; it trivially wins). This is the one silent case,
  and it is silent because there is genuinely nothing to check.
- Carrier ordered after upstream → LOUD (order violation).
- Any org/teavm duplicate not explainable as carrier-shadows-upstream → LOUD (duplicate).

Side fix: protoblast's own `sourceSets.test` classpath APPENDED the server classes dir
(carrier) after the upstream `teavm-jso-apis` jar — upstream won on protoblast's own JVM
test classpath. Now prepended. Note: protoblast applies no be.elevenways plugin to itself
(it builds it), so this order is convention there, guard-enforced everywhere downstream.

## Item 2 (A8 residual) — design

`wipeFunctionalTestRepo` (a `Delete` task) is a dependency of every
`*PublicationToFunctionalTestRepository` task — one shared wipe, not a per-publish
doFirst, so multiple publications in one invocation cannot clobber each other. Repo
content after any publish therefore equals the CURRENT publication set exactly.
Two permanent tests:
- `functionalRepoContainsOnlyTheCurrentPublications` — repo's `be/elevenways` children
  must equal `[protoblast-server]`; any orphan fails the suite naming it.
- `retiredCoordinateIsNotResolvableFromTheFunctionalRepo` — the historically retired
  `be.elevenways:protoblast:0.1.0-SNAPSHOT` must fail resolution from the repo
  (A8's original counterfactual made permanent).

Pre-fix the repo held 8 timestamped snapshot generations (accumulation confirmed);
post-fix exactly 1 (`protoblast-server-0.1.0-20260801.085959-1.jar`).

## Pre-fix failures (verbatim; full log at scratchpad/prefix-run.log)

Setup: 4 new tests written first; a retired-coordinate orphan
(`be/elevenways/protoblast/0.1.0-SNAPSHOT/` pom+jar) planted manually into
`build/functional-test-repo` to simulate an earlier build's leftover. Run against the
UNMODIFIED guard and build files (`zenit-dev test --unit --class
ProtoblastPluginFunctionalTest --no-fail-fast`, log 20260801-105642): 4 of 12 failed,
8 pre-existing passed.

1. `duplicateTeaVmFqnWithoutPatchCarrierFailsTheBuild` (two-TeaVM-version shape):
```
org.gradle.testkit.runner.UnexpectedBuildSuccess: Unexpected build execution success in /tmp/junit13652571010157962438 with arguments [test]
```
2. `patchedTeaVmClassMustWinByClasspathOrder` (upstream-first, patched copy loses):
```
org.gradle.testkit.runner.UnexpectedBuildSuccess: Unexpected build execution success in /tmp/junit694944979321936244 with arguments [test]
```
3. `functionalRepoContainsOnlyTheCurrentPublications`:
```
org.opentest4j.AssertionFailedError: The functional-test repo must hold ONLY the coordinates this build publishes (protoblast-server); stale orphans found: [protoblast, protoblast-server] ==> expected: <true> but was: <false>
```
4. `retiredCoordinateIsNotResolvableFromTheFunctionalRepo` — the planted orphan RESOLVED:
```
org.gradle.testkit.runner.UnexpectedBuildSuccess: Unexpected build execution success in /tmp/junit8738796419054946438 with arguments [resolveIt]
Output:
> Task :resolveIt
[/home/skerit/projects/javaweb/protoblast/build/functional-test-repo/be/elevenways/protoblast/0.1.0-SNAPSHOT/protoblast-0.1.0-SNAPSHOT.jar]
BUILD SUCCESSFUL in 296ms
```

## Post-fix verification

- `ProtoblastPluginFunctionalTest` 12/12 PASSED (log 20260801-105954). The planted orphan
  was physically wiped by the publish (verified on disk), and the order test's second half
  pins that carrier-first stays GREEN (the sanctioned lane still works).
- Full protoblast unit suite: **2818 passed** (includes all three functional-test classes
  = the isolated-repo consumers, MergeResourceFilesTaskTest, and the root :test task).
- `zenit-dev build` (protoblast): ok, published in 16s — the plugin was recompiled from
  the edited sources by the same invocations that ran the tests (withPluginClasspath =
  current compile output; no stale-artifact risk by construction).
- Consumer builds (spamservice `build.gradle:241`, thoth `:217`): call sites re-read —
  they use `isPatchCarrier`/`patchedEntries` on resolved JAR files only; behavior for
  jar inputs is unchanged. Live build status: see limitation below.

## Files changed

- `protoblast/protoblast-gradle-plugin/src/main/java/be/elevenways/protoblast/gradle/RuntimeClasspathGuard.java`
- `protoblast/protoblast-gradle-plugin/src/main/java/be/elevenways/protoblast/gradle/TeaVmPatchLane.java`
- `protoblast/protoblast-gradle-plugin/src/test/java/be/elevenways/protoblast/gradle/ProtoblastPluginFunctionalTest.java` (+4 tests)
- `protoblast/build.gradle` (wipe task; test-classpath carrier prepend)

## Consumer verification (final)

- spamservice `zenit-dev build`: **ok** (236s, fresh TeaVM bundle compiled under the guard).
- thoth `zenit-dev build`: **ok** (129s, fresh bundle).
- proteus `zenit-dev build`: **ok** (369s) after the fallout fix below.
- hawkeye CleanupDisposerBrowserTest: **2 PASSED** on the reordered (now-correct) bundle.

## Fallout triage (coordinator follow-up): two lanes the narrowed guard surfaced

1. **hawkeye-core `generateJavaScript` — case (c), real ordering dependency, now pinned.**
   Order violation, 2 FQNs: `TUUID` + `TConcurrentHashMap`, carrier
   `protoblast-client.jar` AFTER upstream `teavm-classlib-0.15.0.jar`. Verbatim:
   ```
   TeaVM patch lane order violation on the classpath of :hawkeye-core:generateJavaScript (2 total, showing 2).
       carrier  /home/skerit/.m2/repository/be/elevenways/protoblast-client/0.1.0-SNAPSHOT/protoblast-client-0.1.0-SNAPSHOT.jar
       upstream /home/skerit/.gradle/caches/modules-2/files-2.1/org.teavm/teavm-classlib/0.15.0/.../teavm-classlib-0.15.0.jar (comes first)
   ```
   Root cause: the org.teavm plugin resolves its OWN classlib dependency ahead of
   inherited project deps, so no declaration reorder can fix it. The upstream
   TUUID/TConcurrentHashMap had been silently winning in hawkeye's browser-test
   bundle — exactly the silent-dead-patch disease the order assertion exists for.
   Fix: hawkeye-core's existing GenerateJavaScriptTask classpath hook now moves
   TeaVmPatchLane carriers to the front (content-based, same mechanism as the
   fat-jar merge). Commit hawkeye `8458798`. NOT a guard widening.
   (The remaining ListDirectiveBrowserTest assertion failure on that lane is
   another agent's live WIP — `ListDirectives.java`/`ListDirectiveBrowserTest.java`
   are mid-edit in their worktree; the generate task itself is green.)

2. **proteus `generateJavaScript` — case (a), genuine pre-existing debris, NOT org/teavm.**
   3691 duplicate be.elevenways FQNs: `proteus-...-client.jar` (fat client jar) vs
   `zenit-cms-server-...jar` — the whole SERVER stack on the TeaVM classpath.
   Root cause: proteus still used the org.teavm-plugin-OWNED `teavmClasspath`
   configuration name, which the plugin extends from the main runtime classpath —
   and proteus widens main runtime with every server dep for installDist. thoth/
   spamservice/herald were already migrated to an app-owned `teavmInput` config
   (their AIDEV-NOTE names this exact trap); proteus was never migrated. Fixed by
   the same migration: `teavmInput` = clientJar FIRST, then the five teavm libs
   (carrier-first by construction). Commit proteus `107d498`. This duplicate never
   fired before only because A4's rollout verified proteus by static jar scan, not
   a rebuild. Not a guard defect — the two-fold roulette it was built to catch.

No second sanctioned lane exists; the derived exemption set needed no extension,
and no wildcard was reintroduced.

## Notes

- protoblast's HTMLTokenizerTest "probe" is vacuous for the guard (protoblast does not
  apply its own plugin); real guard coverage on the genuine lane comes from every
  downstream repo's Test/JavaExec/TeaVM tasks.
- Consumer verification was temporarily blocked mid-task by the concurrent locale-folds
  rollout (checkLocaleFolds task in the plugin fat jar vs not-yet-annotated repos);
  resolved by that agent, chain green afterwards.

## Commits

- protoblast `d5b6938` (F13 guard narrowing + order assertion), `5a9adbe` (A8 repo wipe + pins)
- hawkeye `8458798` (carrier-first TeaVM classpath), proteus `107d498` (teavmInput migration)
