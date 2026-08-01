Reconnaissance complete. Report below.

---

# Wave A recon report (A1, A3, A4, A6, A7, A8, A10, A11)

Repo HEADs: hawkeye `cd993fa4`, protoblast `8b66d50`, zenit `d721844`. Line numbers below are current-HEAD verified.

---

## A1 — Stale Hawkeye generated classes survive tag removal/rename

**Verdict: REAL.** All four sub-claims hold; line numbers drifted slightly.

Evidence anchors:
- `/home/skerit/projects/javaweb/hawkeye/hawkeye-core/src/server/java/be/elevenways/hawkeye/server/compiler/ProjectCompiler.java:121-131` — `scanAll` clears **every** registry (`tagDefiningFiles.clear()`, `expectedGeneratedClasses.clear()`, `writtenFiles.clear()`) and rebuilds from the *new* source only. No record of the previous corpus survives.
- `ProjectCompiler.java:155-176` — `tagDefiningFiles.add(...)` is populated purely from post-edit IR.
- `ProjectCompiler.java:632-638` (doc said 626-638) — `hasTagDefinitionChanges` is `tagDefiningFiles.contains(file.getAbsolutePath())`: "does this file define a tag **after** the edit". A file that *removed* its last tag is no longer in the set, so the tag-dependency full recompile is not even triggered.
- `/home/skerit/projects/javaweb/hawkeye/hawkeye-compile/src/main/groovy/be/elevenways/hawkeye/compile/HawkeyeCompile.groovy:236-243` — only `ChangeType.REMOVED` sets `removedTemplates`.
- `HawkeyeCompile.groovy:246-256` — the clean-and-full-recompile branch is gated on `removedTemplates` only: `if (!nonIncremental && removedTemplates) { project.delete(outputDir) ... }`.
- `HawkeyeCompile.groovy:258-264` — the tag-definition-change branch sets `forceFullRecompile = true` **without any `project.delete(outputDir)`**. Confirms "a tag rename forces a full compile but does not clean the output directory".
- `ProjectCompiler.java:320-360` — `requireCompleteOutput` iterates `expectedGeneratedClasses` for *missing* files and `writtenFiles` for files that vanished. There is no reverse walk of `outputDir` for *unexpected* files. Its own AIDEV-NOTE (`:298-317`) says it refuses "an INCOMPLETE generated-source tree" — completeness only, never exactness.

Mechanisms a fix must extend (do not invent a parallel one):
- `expectedGeneratedClasses` (`ProjectCompiler.java:83`, filled at `:199-223`) is already the exact projection of the current corpus, derived from the same IR the generators consume — it is the natural input for an "exact output" reconciliation (walk `outputDir`, delete `.java` not in the expected set + `writtenFiles`).
- `writtenFiles` (`:87`, written at `:277`) is the per-run actual-output ledger.
- The completeness gate is a **bridged** method: any signature change must bump `ProjectCompiler.GRADLE_BRIDGE_VERSION` (`ProjectCompiler.java:50`, currently `2`) **and** `CompilerBridge.EXPECTED_BRIDGE_VERSION` (`CompilerBridge.groovy:30`) in the same commit; the eager resolution list is `CompilerBridge.groovy:106-124`. Adding a new bridged method is the same rule.
- Two callers of the doctrine, both must stay in step: `HawkeyeCompile.groovy:407-415` (`bridge.requireCompleteOutput(outputDir.toPath())`) and hawkeye-core's own hand-rolled block at `/home/skerit/projects/javaweb/hawkeye/hawkeye-core/build.gradle:500-507`.
- Service-file reconciliation precedent already inside the task: `HawkeyeCompile.groovy:400-403` deletes `serviceFile` when no templates exist; `:345` deletes `conditionalIndexFile`; `:378` deletes `scssDir`.

Test locations: hawkeye-core `src/test/java/be/elevenways/hawkeye/` — see `WriteTagSourcesTest.java`, `CrossProjectTagInheritanceTest.java`, `TemplateWiringAdvisorTest.java` for the temp-dir + `Files.writeString` fixture style. A Gradle-level incremental journey needs a TestKit fixture; nearest existing pattern is the protoblast plugin functional tests (see A8).

---

## A3 — Hawkeye compiler changes do not trigger Protoblast plugin repackaging

**Verdict: REAL, and currently observable on this machine.**

Evidence anchors:
- `/home/skerit/projects/javaweb/protoblast/protoblast-gradle-plugin/build.gradle:26-42` — dependencies block; `:34-36`:
  ```
  implementation ('be.elevenways:hawkeye-compile:0.1.0-SNAPSHOT') { changing = true }
  ```
  The edge is a **mavenLocal changing coordinate**, not a project/build-graph edge.
- `protoblast-gradle-plugin/build.gradle:48-52` — `shadowJar { archiveClassifier '' ; mergeServiceFiles() }` with no `configurations` narrowing, so the whole runtimeClasspath (incl. hawkeye-compile) is bundled. Verified: the published fat jar contains **97** `be/elevenways/hawkeye/compile/*` entries.
- `/home/skerit/projects/javaweb/hawkeye/hawkeye-compile/src/main/groovy/be/elevenways/hawkeye/compile/CompilerBridge.groovy:33-37` — `REBUILD_HINT` documents the manual order as the remedy: *"build hawkeye (publishes hawkeye-server + hawkeye-compile), then protoblast (repackages the plugin fat jar), then retry"*. This is exactly the "manual rebuild instruction as the solution" the ledger forbids.
- `CompilerBridge.groovy:92-104` — the version check that fires as the *symptom* of the drift.
- Live drift proof at recon time:
  - `~/.m2/.../hawkeye-compile-0.1.0-SNAPSHOT.jar` — **2026-07-31 09:40:25**
  - `~/.m2/.../protoblast-gradle-plugin-0.1.0-SNAPSHOT.jar` — **2026-07-31 00:35:01**
  i.e. the shipped plugin bundles a 9-hour-older copy of hawkeye-compile. (CRCs of `CompilerBridge.class`/`HawkeyeCompile.class` happen to match today because no bridged signature changed since; the packaging edge is nonetheless absent.)

Note: per your instruction I did **not** open `zenit/tools/zenit-dev`, so the `:937-949` / CI-ordering claim is unverified here. The Gradle-side half of the claim is fully confirmed.

Mechanism to extend: the shadow packaging in `protoblast-gradle-plugin/build.gradle` plus the versioned bridge contract (`GRADLE_BRIDGE_VERSION` / `EXPECTED_BRIDGE_VERSION`). Cycle-avoidance note already in the file: `protoblast-gradle-plugin/build.gradle:31-33` explicitly forbids extending the bundling pattern further down the stack.

---

## A4 — TeaVM classpaths carry duplicate common/platform classes

**Verdict: REAL.** Both the guard gap and a concrete duplicate-FQN classpath exist.

Guard gap:
- `/home/skerit/projects/javaweb/protoblast/protoblast-gradle-plugin/src/main/java/be/elevenways/protoblast/gradle/RuntimeClasspathGuard.java:79-92` — `install()` wires **only** `withType(Test.class)` and `withType(JavaExec.class)`. No TeaVM task type, no configuration-level check.
- The plugin already knows how to match TeaVM tasks without a compile dependency: `ProtoblastGradlePlugin.java:286-297`, `isConfiguredTeaVMGenerateTask` matches `org.teavm.gradle.tasks.Generate*` by class-name walk. That is the seam to reuse.

Concrete duplicate:
- `/home/skerit/projects/javaweb/zenit-cms/build.gradle:285-286`
  ```
  teavmLibs ('be.elevenways:hawkeye-client:0.1.0-SNAPSHOT') { changing = true }
  teavmLibs ('be.elevenways:hawkeye-common:0.1.0-SNAPSHOT') { changing = true }
  ```
- `/home/skerit/projects/javaweb/zenit-media/build.gradle:289-290` — identical shape.
- Measured: `hawkeye-client-0.1.0-SNAPSHOT.jar` and `hawkeye-common-0.1.0-SNAPSHOT.jar` share **433 identical class paths** (both carry 460 `be/elevenways/hawkeye/common/**` entries). So `teavmLibs` genuinely resolves 433 FQNs twice, browser-fold vs. fold-neutral. The TeaVM classpath is `files(browserTestJar.archiveFile) + configurations.teavmLibs` — `zenit-cms/build.gradle:430-432`, `zenit-media/build.gradle:410`.
- `hawkeye/hawkeye-core/build.gradle:189-190` (`teavm files(sourceSets.browser.output.classesDirs)` + `teavm files(sourceSets.common.output.classesDirs)`) and `textum/build.gradle:168-179` are the *stacked-source-set* shape of the same risk; hawkeye-core's own source sets are disjoint (`browser` srcDirs = `src/browser/java` only, `hawkeye-core/build.gradle:29-37`), so those two lines are the ordering-fragile pattern rather than a proven duplicate today. The proven duplicates are the two `-common`-beside-`-client` lines above.
- Also note `hawkeye-core/build.gradle:532-535` already rewrites every `GenerateJavaScriptTask.classpath` (filters `.java` files) — proof that mutating/inspecting the TeaVM classpath from a `configureEach` is an established move.

---

## A6 — Spamservice deployable contains divergent duplicate entries

**Verdict: REAL, and the "36 duplicate paths" number reproduces exactly.**

Evidence anchors:
- `/home/skerit/projects/javaweb/spamservice/build.gradle:161-197` — `task serverJar(type: ShadowJar)`; `:183-185`:
  ```
  duplicatesStrategy = DuplicatesStrategy.INCLUDE
  failOnDuplicateEntries = false
  mergeServiceFiles()
  ```
- `spamservice/build.gradle:198-208` — the post-build check counts copies of exactly **one** class (`be/elevenways/protoblast/common/dry/BlastDrySerializers.class`).

Inspection of the checked-in build output `spamservice/build/libs/spamservice-0.1.0-SNAPSHOT-server.jar`: **36** duplicate entry paths. Breakdown:
- **Divergent duplicate classes**: `org/teavm/jso/dom/events/{Event,InputEvent,KeyboardEvent,MessageEvent,MouseEvent,Touch,TouchEvent,WheelEvent}.class` — e.g. `Event.class` appears twice with CRCs `23aeb59d` and `4a3fa55e` (different bytes). Plus `module-info.class`, `META-INF/versions/9/module-info.class`.
- **Repeated mergeable resources with different content**: `META-INF/microcopy/en.json` ×7 (CRCs `006f00ff`, `1c8f0a4b`, `8cdb6cb2`, `74d6f371`, `7ed792ff`, `6bdfd604`, …), `META-INF/microcopy/keys.txt` ×8, `META-INF/microcopy/nl.json`, `META-INF/hawkeye/tag-sources.tsv` ×6, `META-INF/hawkeye/template-blocks.tsv` ×6.
- **Build-internal leakage**: `previous-compilation-data.bin` ×2 (218 KB + 79 KB of Gradle incremental-compile state shipped in the deployable).
- No `be/elevenways` duplicates in this particular build, which is why the existing one-class check passes.

Canonical merge mechanisms a fix must use (all already exist — do not invent):
1. **Service files** — `/home/skerit/projects/javaweb/protoblast/protoblast-gradle-plugin/src/main/java/be/elevenways/protoblast/gradle/MergeServiceFilesTask.java` (concatenates + dedupes `META-INF/services/*`; its class doc `:30-42` explains that Shadow's `mergeServiceFiles()` does **not** process `zipTree` copy specs, so `mergeServiceFiles()` at `spamservice/build.gradle:185` is largely inert for the zipTree'd deps).
2. **The quirkyquarters browserJar precedent** — `/home/skerit/projects/javaweb/quirkyquarters/build.gradle.kts:224-250`: `MergeServiceFilesTask` registered as `mergeBrowserServiceFiles`, then the fat jar excludes `META-INF/services/**` and `BlastAutoLoadInit.class` from every raw copy spec and adds `from(mergeBrowserServiceFiles)`. Spamservice's own **clientJar** already follows this (`spamservice/build.gradle:139-150`, `mergeClientServiceFiles`); only `serverJar` does not.
3. **Microcopy** — there is currently **no build-time merge**; instead a *runtime* compensator: `/home/skerit/projects/javaweb/zenit-microcopy/src/server/java/be/elevenways/zenit/microcopy/server/ClasspathResources.java:19-53` reads *every* occurrence of a same-path resource inside one jar via the central directory, precisely because `DuplicatesStrategy.INCLUDE` fat jars hide all but the first. Consumers: `MicrocopyManifest.java:58-66` and `DefaultCatalogLoader.java:128-133`.
   **Implementation warning:** simply switching `serverJar` off `INCLUDE` without adding a build-time microcopy merge will silently drop every dependency module's catalog except one. Either add a merge task on the `MergeServiceFilesTask` model for `META-INF/microcopy/**` (and `META-INF/hawkeye/*.tsv`), or keep the duplicate-read path and make the duplication deliberate + class-free.

---

## A7 — Runtime duplicate guard ignores non-`be.elevenways` packages

**Verdict: REAL.**

- `RuntimeClasspathGuard.java:50` — `private static final String CLASS_PREFIX = "be/elevenways/";`
- Used as the sole filter in both scanners: `:204` (`new File(dir, CLASS_PREFIX)` — the directory walk *starts* inside `be/elevenways`, so nothing else is even enumerated) and `:236` (`if (name.startsWith(CLASS_PREFIX) && name.endsWith(".class"))`).
- Failure message hardcodes the prefix too: `:194` `"Duplicate be.elevenways classes on the runtime classpath of "`. A package-independent guard must not break the existing assertion without updating the fixture (below).

Fixture to extend: `/home/skerit/projects/javaweb/protoblast/protoblast-gradle-plugin/src/test/java/be/elevenways/protoblast/gradle/ProtoblastPluginFunctionalTest.java:208-253`, `duplicateFqnOnTestRuntimeClasspathFailsTheBuild` — TestKit, `@TempDir`, writes `src/main/java/be/elevenways/dupsample/Dupe.java`, plants a second copy as a raw 2-byte file at `extra-classes/be/elevenways/dupsample/Dupe.class` on `testRuntimeOnly files('extra-classes')`, then `runner("test").buildAndFail()`. A `com.example` fixture is a near-verbatim clone with the package swapped. Note the guard compares *entry listings, never bytes* (comment at `:245-246`).

Install sites (both must keep working): `ProtoblastGradlePlugin.java:55` and `PublishModulePlugin.java:32` (the publish-only plugin installs it too, for hawkeye-core/textum).

---

## A8 — Plugin functional tests depend on an obsolete Maven artifact

**Verdict: REAL, and provably passing only off a stale cache entry.**

Cited coordinates (`be.elevenways:protoblast:0.1.0-SNAPSHOT:server`) at HEAD:
- `ProtoblastPluginFunctionalTest.java:70` (`blastAutoLoadMarkerIsDiscovered`)
- `ProtoblastPluginFunctionalTest.java:134` (`writeConditionalSample`, feeding two tests)
- `ProtoblastApplicationDistFunctionalTest.java:47` (`installDistKeepsLoaderInTheAppsOwnJar`)

Current publication is `protoblast-server` — `/home/skerit/projects/javaweb/protoblast/build.gradle:215`:
```
publishModule('server', 'protoblast-server', serverJar, ...)
```
with the header comment at `:196` reading *"separate -client / -server Maven modules (**NOT classifiers**)"*.

Cache proof:
- `~/.m2/.../protoblast/0.1.0-SNAPSHOT/protoblast-0.1.0-SNAPSHOT-server.jar` — **2026-06-26 14:50** (orphan, no longer produced by any build)
- `~/.m2/.../protoblast-server/0.1.0-SNAPSHOT/protoblast-server-0.1.0-SNAPSHOT.jar` — **2026-07-31 00:35**

Fixture structure to follow (all three test classes share it): `@TempDir Path projectDir`; `writeSettings(...)` → `settings.gradle` with `pluginManagement { repositories { mavenLocal(); gradlePluginPortal() } }`; `writeBuild(...)` → `build.gradle` with `repositories { mavenLocal(); mavenCentral() }`; `runner(...)` = `GradleRunner.create().withProjectDir(...).withArguments(...).withPluginClasspath().forwardOutput()` (`ProtoblastPluginFunctionalTest.java:255-262`). Sibling file `BundleSizeBudgetFunctionalTest.java` is the third example of the same shape. Isolating the repo means replacing `mavenLocal()` with a build-populated repo dir and threading it through all three classes (the `pluginManagement` block resolves the plugin marker too — `withPluginClasspath()` covers the plugin itself, but `be.elevenways.protoblast` is also declared via `plugins { id ... version '0.1.0-SNAPSHOT' }`).

---

## A10 — Stylesheet registration generation retains stale classes

**Verdict: REAL.**

- `/home/skerit/projects/javaweb/zenit/zenit-gradle-plugin/src/main/groovy/be/elevenways/zenit/gradle/GenerateStylesheetRegistrationTask.groovy:41-47` — the class name is derived from the namespace:
  ```
  String className = ns.substring(0, 1).toUpperCase() + ns.substring(1) + 'StylesheetRegistration'
  ...
  packageDir.mkdirs()
  ```
  `:48` writes `new File(packageDir, className + '.java').text = ...`. Nothing enumerates or deletes the existing contents of `getOutputDirectory()`. A namespace rename leaves `OldnsStylesheetRegistration.java` beside the new one; both are `@ZenitAutoLoad` (`:63`) and both get scanned out of the published jar, so the retired href keeps registering in `Stylesheets`.
- The `@OutputFile` contribution (`:78-81`) is at a fixed path and *is* overwritten correctly — the defect is confined to the `@OutputDirectory` at `:32-33`.

Wiring context (for the rename test): `ZenitGradlePlugin.groovy:44-70` — namespace comes from `hawkeye.namespace`, output dir is `build/generated-sources/zenit-stylesheets/java`, contribution is `build/generated-sources/zenit-stylesheets/<sourceSet>.autoload.txt`, and `sourceSet.java.srcDir(task.map { it.outputDirectory })` at `:67` is what makes the stale file compile.

Doctrine to mirror: the Hawkeye clean-on-full-generation pattern (`HawkeyeCompile.groovy:166-172` deletes `outputDir` + `serviceFile`) plus the exact-projection idea from A1.

Test location / fixture: `/home/skerit/projects/javaweb/zenit/zenit-gradle-plugin/src/test/java/be/elevenways/zenit/gradle/ZenitGradlePluginFunctionalTest.java`. `writeFixture(register, withAutoloadTask, output)` at `:90-121` fabricates a fake `hawkeye` extension via `Expando` with `namespace: objects.property(String).convention('sample')` — a rename test parameterises that namespace, runs `generateZenitStylesheetRegistration` twice, and asserts `SampleStylesheetRegistration.java` is gone after the second run. Helpers `generatedSource()` / `contributionFile()` at `:123-131` hardcode `Sample`; they will need a namespace argument.

---

## A11 — Maven consumers can still receive `-common` at runtime

**Verdict: REAL.** The Gradle side is airtight; the POM side carries no signal at all.

Publication mechanism: `/home/skerit/projects/javaweb/protoblast/protoblast-gradle-plugin/src/main/resources/be/elevenways/protoblast/gradle/publish-module.gradle:63-85`, `ext.publishCompileOnlyModule`. It creates **one** consumable configuration `${pubName}PubApiElements` with `Usage.JAVA_API` (`:68`), attaches the jar (`:74`), adds deps (`:76`), and maps the single variant with `it.mapToMavenScope('compile')` (`:79`). Contrast `publishModule` at `:23-54`, which creates both api and runtime elements.

**What the generated metadata actually says today** (verified against `~/.m2/repository/be/elevenways/` for `hawkeye-common`, `zenit-media-common`, `textum-common`, `plumage-common` — all identical in shape):

Gradle Module Metadata (`hawkeye-common-0.1.0-SNAPSHOT.module`):
```json
"variants": [ { "name": "commonPubApiElements",
  "attributes": { "org.gradle.category": "library",
                  "org.gradle.dependency.bundling": "external",
                  "org.gradle.jvm.version": 25,
                  "org.gradle.libraryelements": "jar",
                  "org.gradle.usage": "java-api" },
  "dependencies": [ { "group": "org.checkerframework", "module": "checker-qual",
                      "version": { "requires": "4.2.0" } } ],
  "files": [ { "name": "hawkeye-common-0.1.0-SNAPSHOT.jar", ... } ] } ]
```
Exactly one variant, `java-api` only. A Gradle `runtimeClasspath` request finds no `java-runtime` variant and fails resolution loudly — the contract holds.

Generated POM (`hawkeye-common-0.1.0-SNAPSHOT.pom`), in full substance:
```xml
<!-- do_not_remove: published-with-gradle-metadata -->
<modelVersion>4.0.0</modelVersion>
<groupId>be.elevenways</groupId>
<artifactId>hawkeye-common</artifactId>
<version>0.1.0-SNAPSHOT</version>
<dependencies>
  <dependency>
    <groupId>org.checkerframework</groupId><artifactId>checker-qual</artifactId>
    <version>4.2.0</version><scope>compile</scope>
  </dependency>
</dependencies>
```
Notably absent: any `<packaging>` element (so it defaults to `jar`), any `<scope>provided</scope>` / `<optional>` marker on the module itself, and any relocation or `<properties>` hint. The `hawkeye-common-0.1.0-SNAPSHOT.jar` **is** the POM's main artifact. A Maven consumer writing a plain `<dependency>` on it gets the default `compile` scope → jar on the compile **and** the runtime classpath, alongside `hawkeye-client`/`hawkeye-server`, producing exactly the duplicate-FQN wrong-fold state that `RuntimeClasspathGuard` exists to prevent. The `do_not_remove: published-with-gradle-metadata` marker is a Gradle-only hint; Maven ignores it entirely. Nothing anywhere in the POM expresses the no-runtime-variant contract.

The prose contract that the POM fails to encode lives at `publish-module.gradle:56-62` and is restated in `hawkeye/hawkeye-core/build.gradle:651-655` and `zenit-media/build.gradle:482`.

Affected artifacts (every `publishCompileOnlyModule` caller): `hawkeye-common`, `zenit-common`, `zenit-media-common`, `zenit-forms-common`, `plumage-common`, `textum-common`, `zenit-auth-common`, `zenit-ai-common`, `zenit-a2ui-common`, and siblings — grep `publishCompileOnlyModule` across the workspace for the exact list.

---

## Cross-cutting notes for implementers

- Bridged-signature rule (touches A1): any change to a `ProjectCompiler` method reachable from `CompilerBridge` requires bumping **both** `ProjectCompiler.GRADLE_BRIDGE_VERSION` (`ProjectCompiler.java:50`) and `CompilerBridge.EXPECTED_BRIDGE_VERSION` (`CompilerBridge.groovy:30`) in one commit — and, because of A3, republishing protoblast after hawkeye or every consumer hits the mismatch error at `CompilerBridge.groovy:99-104`.
- The two functional-test suites to extend are `protoblast/protoblast-gradle-plugin/src/test/java/be/elevenways/protoblast/gradle/` (A4, A7, A8) and `zenit/zenit-gradle-plugin/src/test/java/be/elevenways/zenit/gradle/` (A10). Both use TestKit + `@TempDir` + `withPluginClasspath()`; neither uses a shared base class, so a new fixture is a self-contained method.
- A4 and A7 are the same guard and should almost certainly be one change: package-independent scanning (A7) plus TeaVM task/configuration coverage (A4), reusing `ProtoblastGradlePlugin.isConfiguredTeaVMGenerateTask` (`ProtoblastGradlePlugin.java:291-297`) for task matching.
- A6 and A11 both concern "a `-common`/duplicated artifact reaching a runtime surface"; the fixes are independent, but the A6 fix must not regress the runtime microcopy duplicate-read path (`ClasspathResources`) without replacing it with a build-time merge.