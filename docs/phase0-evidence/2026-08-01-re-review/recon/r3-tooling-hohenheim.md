# R3 recon: F5, F4, F13, F14 + A-wave triage

All line references are at CURRENT HEAD: zenit `8b6a60b`, protoblast `c76381a`,
hawkeye `ab61cb43`, textum `6ae80d5`, hohenheim `b1de080`. All worktrees clean.

---

## F5 - textum's hawkeye edge is invisible to zenit-dev

### VERDICT: REAL (as a tooling defect). Impact today is LATENT, not live.

`baseDepsFor` is still authoritative. A5 did NOT replace it - A5 added a
declaration-derived DAG *on top of* it, and the DAG seeds every node from
`baseDepsFor`:

`zenit/tools/zenit-dev:1008-1021`
```js
function directDepsFor(name, projectRoot) {
    ...
        const scanned = scanOptionalDeps(projectRoot, name);
        const strong = [...baseDepsFor(name)];        // <-- hardcoded seed
        for (const lib of scanned.strong) {
            if (!strong.includes(lib)) strong.push(lib);
        }
```

`zenit/tools/zenit-dev:937-950`
```js
        case 'textum': return ['protoblast'];
        default: return [...CORE_PROJECTS];
```

The scanner cannot recover the edge, because it only tests names in
`OPTIONAL_LIBRARIES` (`zenit-dev:997-999`), and that list
(`zenit-dev:930-935`) contains no core project:
`['plumage','textum','zenit-microcopy','zenit-comms','zenit-auth','zenit-oidc',
'zenit-auth-test-support','zenit-widget','zenit-forms','zenit-media','zenit-ai',
'zenit-a2ui','zenit-cms','zenit-flow','zenit-pages','spamservice']`.

Textum really does consume hawkeye - `textum/build.gradle:103,112,122,163,179`
(`commonCompileOnly hawkeye-common`, `serverImplementation hawkeye-server`,
`browserImplementation hawkeye-client`, `browserTestCompileOnly hawkeye-client`,
`teavm hawkeye-client`).

### Actual freshness behaviour

`effectiveFingerprintFor` (`zenit-dev:495-500`) hashes
`upstreamFingerprint(depsForProject(project)) + own worktree`. For textum the
dep list is `['protoblast']`, so **a hawkeye-only edit does not move textum's
effective fingerprint**: `zenit-dev status`/`build` report textum FRESH and
never republish it. Confirmed by reading the code path end to end; no other
mechanism recovers the edge (the m2-mtime path at `zenit-dev:3460` also walks
`PROJECT_DEPS`).

Two concrete consequences:
1. **Build order / resolution**: `zenit-dev build` run inside textum builds
   protoblast only. Hawkeye is never built, so textum compiles against whatever
   `hawkeye-*` happens to sit in `~/.m2` - or fails resolution on a clean
   machine.
2. **Stale linkage**: after a hawkeye API change, consumers (arcana,
   orcono/mvp-v01, testbeds/todomvc/skeritcom - the only three declaring
   textum) rebuild (their own dep list *does* contain hawkeye) but link a
   textum jar compiled against the old hawkeye.

### Why the impact is LATENT today
`grep -rli hawkeye textum/src --include=*.java` -> **0 files**. Textum declares
hawkeye in five configurations but no textum source references it, and textum
ships no `.hwk` templates. So today a hawkeye change genuinely cannot change
textum's output. The defect is a live *tooling* lie that becomes a live
*artifact* bug the moment textum uses the API its build file already
provisions. `CI_LEVELS` (`zenit-dev:3971-3984`) happens to place textum
(level 5) after hawkeye (level 2), so `zenit-dev ci` is unaffected - and note
the "CI levels are topological over strong dependency edges" test at
`zenit-dev.test.js:894` passes only because it consults the same blind scanner.

### Systematic sweep of every build root in the workspace
I compared every `build.gradle`/`build.gradle.kts` under `/home/skerit/projects/javaweb`
(depth<=4, build dirs excluded) against what `baseDepsFor` + `scanOptionalDeps`
derive. Full script: `<scratchpad>/recon/sweep.js`. Result:

**textum is the ONLY repo whose real dependencies are under-captured.**

Every other project either matches exactly or is *over*-captured, because
`baseDepsFor`'s `default` branch hands out all five `CORE_PROJECTS`. Notable
over-captures (harmless - they only cause extra invalidation):
`duiventil` (declares janeway+protoblast, gets all 5), `arcana` (declares
hawkeye/protoblast/textum/zenit, gets all 5 + textum),
`hawkeye/hawkeye-*` and `protoblast/protoblast-*` submodules (get the whole
core chain). Repos outside FRAMEWORK_ROOT (hohenheim) are not covered by any of
this; it is a pinned CI exclusion (`zenit-dev:3990-3994`).

### Exact fix (extension of the existing mechanism, not a new one)
The mechanism is already "declaration-derived edges over a name vocabulary".
The vocabulary is simply missing the core projects. Two coordinated edits:

1. Make the scanner's vocabulary the FULL artifact set, not just the optional
   libraries: scan against `[...CORE_PROJECTS, ...OPTIONAL_LIBRARIES]` in
   `scanOptionalDeps` (`zenit-dev:997`), keeping `OPTIONAL_LIBRARIES` as the
   ordering seed.
2. Reduce `baseDepsFor` to what genuinely cannot be scanned. `protoblast` has
   no build-file deps at all and every core project's `build.gradle` in fact
   declares its upstreams (verified: `janeway` declares emberglyph+protoblast,
   `zenit` declares hawkeye+janeway+protoblast, `hawkeye-core` declares
   protoblast). The `default: [...CORE_PROJECTS]` fallback for consumer apps
   should stay (it is a safe over-approximation), but the per-core cases
   become derivable. Minimum-risk variant: leave `baseDepsFor` alone and just
   fix the vocabulary - textum then picks hawkeye up from its own build file.

### Counterfactual that must fail before and pass after
Extend `zenit-dev.test.js`'s "dependency scanning is declaration-based and
scope-aware" with a workspace-real assertion (no fixture needed - this is a
pure function over the real tree):

```js
assert.ok(depsForProject('textum', path.join(frameworkRoot, 'textum'))
    .includes('hawkeye'),
    'textum declares hawkeye-common/server/client; a hawkeye-only change must mark it stale');
```
Fails today (`['protoblast']`), passes after. Add the general guard too: for
every repo in `CI_LEVELS`, every `be.elevenways:<artifact>` coordinate in its
build file must map into `depsForProject(repo)` - that turns the sweep I ran by
hand into a permanent pin, and is the piece that stops the next textum.

**Confidence: high** on the mechanism and the sweep; **high** that the current
production impact is nil because textum has zero hawkeye source references.

---

## F4 - hohenheim wildcard/exact hostname overlap

### VERDICT: FALSIFIED as a defect. The test the reviewer criticises is testing the right case.

The cited code is accurate. `SiteDomainResource.java:257-263`:
```java
            String candidateHostname = SiteDomainModel.canonicalHostname(
                candidate.get(SiteDomainModel.HOSTNAME), candidate.get(SiteDomainModel.MATCH_TYPE));
            if (!Objects.equals(canonicalHostname, candidateHostname)
                || !Objects.equals(path, normalizedPath(candidate.get(SiteDomainModel.PATH)))
                || !listenersOverlap(listenOn,
                    ListenerAddressMatcher.parse(candidate.get(SiteDomainModel.LISTEN_ON)))) {
                continue;
            }
```
and `canonicalHostname` only lowercases (`SiteDomainModel.java:36-42`), and
`RouteClaims.keyOf` (`RouteClaims.java:89-101`) omits match type. So yes:
exact `foo.example.com` and wildcard `*.example.com` produce different claim
keys and can coexist.

**But that coexistence is correct and intentional.** The proxy resolves in
strict tiers with deterministic precedence -
`SiteDispatcher.java:911-960`:
- `// 1. Exact match: bucket is pre-sorted longest path first` (911) -> returns
  on the first listener-accepting, path-matching entry;
- `// 2. Wildcard match: collect every matching route, pick the longest path`
  (923) -> only reached when no exact entry matched;
- `// 3. Regex match` (939) -> only reached when neither of the above matched.

Exact beats wildcard beats regex; longest path wins inside a tier. That is the
nginx/Caddy semantic and it is exactly what an operator wants: `*.example.com`
is a catch-all for the zone and `foo.example.com` is the carve-out. Nothing is
dropped, nothing is ambiguous. **The precedence rule the reviewer asks me to
"state" already holds and is already implemented.**

The one case that genuinely *is* one route is an exact row and a wildcard-typed
row spelling the **same literal** hostname: a wildcard pattern with no glob
character matches precisely the exact route's host set and is fully shadowed by
tier 1, so the second site would be silently dark. That is the case
`RouteClaims.keyOf`'s match-type-blindness exists to refuse
(`RouteClaims.java:81-88` says so in prose) - and it is exactly what
`RouteOwnershipInvariantTest.wildcardShadowingRestoreAndFailedWritesKeepRouteStorageExact`
step 2 tests (test file is
`src/browserTest/java/be/elevenways/hohenheim/test/RouteOwnershipInvariantTest.java:390-396`,
not the path the reviewer quoted):
```java
        Row shadower = site("Identity Shadower", "identity-shadower", true);
        assertThatThrownBy(() -> domain(shadower, hostname, SiteDomainModel.MATCH_WILDCARD, null))
            .as("step 2: a wildcard row shadowing a live exact row is refused")
```
The reviewer's "genuinely overlapping wildcard" is not a conflict, so a test of
it would assert *acceptance*, not refusal. The test-quality complaint is
therefore also falsified.

### The one real (small, separate) finding in this area
The two route-identity definitions disagree on match type. The **dispatcher's**
in-memory duplicate guard includes it (`SiteDispatcher.java:515-521`):
```java
                String claimKey = kind + "|"
                    + SiteDomainModel.canonicalHostname(hostname, matchType) + "|"
                    + (entry.path != null ? entry.path : "") + "|" + entry.listenOnAddresses;
```
while the **persisted** claim deliberately excludes it. Consequence: for a
same-literal exact/wildcard pair that reached storage without going through the
serialized scan (direct DB write, or a row predating M045), the persistence
layer would have refused it but the dispatcher's own guard does not - it
installs both, tier 1 wins, and the loser is dark with no
`SiteDispatcher: DUPLICATE route` log. Narrow, defence-in-depth only (the M045
backfill releases such losers), but if you want the two definitions to agree,
the dispatcher's `claimKey` should drop `kind +` and reuse `RouteClaims.keyOf`
so there is ONE spelling of route identity.

**Counterfactual for that (optional) fix**: two live sites, one exact
`x.example.com`, one wildcard-typed literal `x.example.com`, inserted straight
into the domain table bypassing the model hooks; assert the dispatcher logs a
DUPLICATE and installs one route. Fails today (two routes, no log).

**Confidence: high.**

---

## F13 - `org/teavm/**` blanket exemption in RuntimeClasspathGuard

### VERDICT: PARTIALLY REAL. The lane IS sanctioned; the exemption is ~380x wider than the lane and nothing pins that the patched copy wins.

The lane is real and documented:
`RuntimeClasspathGuard.java:44-49` (class AIDEV-NOTE) and
`RuntimeClasspathGuard.java:236-241`:
```java
    private static boolean isExemptEntry(String name) {
        return name.startsWith("META-INF/")
                || name.startsWith("org/teavm/")
                || name.endsWith("module-info.class")
                || name.endsWith("package-info.class");
    }
```
`TeaVmPatchLane.java:16-20` states the order dependency, and the lane has real
fat-jar consumers: `spamservice/build.gradle:241,244` and
`thoth/build.gradle:217,220`.

### Exactly which TeaVM classes are patched
36 source files under `protoblast/src/teavm/java/org/teavm/`, producing 41
`.class` entries in `protoblast-client-0.1.0-SNAPSHOT.jar`:
- `org/teavm/classlib/java/util/TUUID`
- `org/teavm/classlib/java/util/concurrent/` : `TCompletableFuture` (+6 inner
  classes: `AltResult, Canceller, DelayedCompleter, Delayer, MinimalStage,
  Timeout`), `TCompletionException`, `TCompletionStage`, `TCompletionStages`,
  `TConcurrentHashMap`, `TCountDownLatch`, `TDelayed`, `TExecutorService`,
  `TFuture`, `TScheduledExecutorService`, `TScheduledFuture`, `TTimeoutException`
- `org/teavm/jso/dom/events/` : `ClipboardEvent, CompositionEvent, DataTransfer,
  DragEvent, Event, ExtendedInputEvent, FocusEvent, InputEvent, KeyboardEvent,
  MessageEvent, MouseEvent, PointerEvent, ProgressEvent, Touch, TouchEvent,
  UIEvent, WheelEvent`
- `org/teavm/jso/dom/geometry/DOMRect`
- `org/teavm/jso/dom/html/ElementMatchesHelper`
- `org/teavm/jso/dom/selection/` : `Range, Selection, StaticRange`

No other repo in the workspace ships `org/teavm` sources.

### The actual duplicate set the exemption is covering
I diffed `protoblast-client`'s `org/teavm` `.class` entries against every
`org.teavm:*:0.15.0` jar in the Gradle cache:
- **vs `teavm-classlib-0.15.0`: 2** - `TUUID.class`, `TConcurrentHashMap.class`
- **vs `teavm-jso-apis-0.15.0`: 8** - `Event, InputEvent, KeyboardEvent,
  MessageEvent, MouseEvent, Touch, TouchEvent, WheelEvent`
- **vs every other teavm 0.15.0 jar: 0**
- **pairwise among upstream teavm 0.15.0 jars themselves: 0** (no upstream
  jar duplicates another)

(The remaining 31 patched classes are *additions*, not shadows - most of
`teavm-classlib`/`teavm-jso-apis` ships `.java` sources, not `.class`, so they
never enter the `.class`-only uniqueness scan at all.)

So **10 FQNs need exemption; the wildcard exempts ~3,800** (`teavm-core` 1721 +
`teavm-classlib` 1343 + `teavm-jso-apis` 260 + the rest).

### Concrete failure mode
Both `0.13.0` and `0.15.0` of every TeaVM artifact are present in the Gradle
cache on this machine, and `arcana` is CI-excluded precisely for its
"pre-catalog build shape (hardcoded plugin versions)"
(`zenit-dev:3990-3991`). A classpath that ends up carrying two TeaVM versions -
or a `teavm-core` shaded into a plugin fat jar beside the real one - produces
thousands of duplicate `org/teavm` FQNs and the guard stays silent, restoring
exactly the "silent classpath-order lottery" the guard's own AIDEV-NOTE says it
replaced. Nothing anywhere asserts that protoblast's copy of `Event.class`
precedes `teavm-jso-apis`'s on any classpath.

### Exact fix (extension of the existing mechanism)
`TeaVmPatchLane` already knows how to enumerate the lane by CONTENT
(`patchedEntries(Collection<File>)`, `isPatchCarrier(File)`). Make
`isExemptEntry` consult that set instead of a prefix:
1. In `checkUniqueness`, compute the patch set once from the classpath via
   `TeaVmPatchLane.patchedEntries(classpath.getFiles())`, and exempt an
   `org/teavm/...` name only if it is in that set. Anything else under
   `org/teavm/` becomes a normal duplicate failure.
2. Add the missing half of the guarantee: when a patched FQN IS duplicated,
   assert the patch carrier comes FIRST in classpath order and fail loudly
   otherwise. Order is observable inside `checkUniqueness` (it already
   iterates `classpath.getFiles()` in order) - today it just discards that
   information.
3. Drop `isExemptEntry`'s `org/teavm/` prefix branch; keep `META-INF/`,
   `module-info`, `package-info`.

### Counterfactual that must fail before and pass after
Two Gradle-plugin functional tests:
- (a) a `Test` task whose classpath carries `teavm-core:0.13.0` and
  `teavm-core:0.15.0`. Must fail the build with a duplicate-FQN message.
  Passes silently today.
- (b) a `Test` task carrying `protoblast-client` AFTER `teavm-jso-apis`. Must
  fail naming `org.teavm.jso.dom.events.Event` and the wrong order. Passes
  silently today. Reordering makes it green - that is the pin the lane has
  never had.

**Confidence: high** on the enumeration and the duplicate counts (measured from
the actual jars); **medium-high** that a two-version classpath is reachable in
practice (arcana is the plausible carrier and it is CI-excluded, so nobody
would notice).

---

## F14 - durable review evidence

### VERDICT: REAL, and fully confirmed.

`hohenheim/docs/phase0-red-team-manifest.md` references **13 files that do not
exist anywhere in the hohenheim repository** (nor in
`/home/skerit/projects/javaweb`). Exact set:

| Referenced path | Cited at (manifest line) |
| --- | --- |
| `reports/wave-g-audit.md` | 396 |
| `reports/e4-e5-e6-e8.md` | 432 |
| `reports/e3-e7-e10.md` | 436, 446 |
| `reports/f1-f4-f11.md` | 441 |
| `reports/e1-e2.md` | 485, 494 |
| `reports/b1-b2-b3-b9.md` | 489 |
| `reports/d1-d5-d6.md` | 509 |
| `reports/e9-f6.md` | 533 |
| `reports/d3-d4.md` | 670 |
| `OWNER-DECISIONS.md` | 685, 725 |
| `reports/a1-a10.md`, `a2-a5-a9.md`, `a3-a4-a7-a8.md`, `a6-a11.md` | 753 |

`docs/reports/` does not exist; `OWNER-DECISIONS.md` exists at neither
`hohenheim/` nor `hohenheim/docs/`; `git ls-files` in hohenheim matches nothing.
The only copies live in the ephemeral scratchpad
(`<scratchpad>/prior/reports/*.md`, `<scratchpad>/prior/OWNER-DECISIONS.md`).
The manifest's own line 368 also points at
`/home/skerit/projects/javaweb/REMEDIATION-2026-07-31.md`, which DOES exist but
is in a different repo than the manifest.

### Where the evidence should durably live
The manifest's references are all **relative to itself** (`reports/x.md`, not
`docs/reports/x.md` - it is a `docs/`-relative link written from inside
`docs/`). So the zero-rewrite resolution is:

- `hohenheim/docs/reports/*.md` <- the 13 per-issue reports (verbatim copies of
  `<scratchpad>/prior/reports/`), and
- `hohenheim/docs/OWNER-DECISIONS.md` <- the owner-decision record.

That makes every existing link in the manifest resolve as written, keeps the
evidence in the repo whose gate (`instance-tier-plan.md` Phase 0.A) demands it,
and puts it under version control so a later hash can be diffed against the
counterfactuals it claims. The cross-repo `REMEDIATION-2026-07-31.md` reference
should either be copied in beside them or restated as "the javaweb workspace
ledger, not part of this repo" - a machine-specific absolute path in a
committed doc is itself a durability bug.

**Confidence: very high** (pure file-existence facts).

---

## Triage of the A-wave "proof gaps"

Ranked by "could the fix actually be wrong in production".

### 1. A9 - "workspace inventory scan misses deeper nested build roots" - PARTIALLY REAL (highest of the five, still low)
The pin test at `zenit-dev.test.js:853-892` discovers build roots **two levels
deep only**, and stops descending as soon as a directory has its own build file:
```js
        if (hasBuildFile(dir)) { known.push(entry.name); continue; }
        for (const child of fs.readdirSync(dir, { withFileTypes: true })) {
            if (child.isDirectory() && hasBuildFile(path.join(dir, child.name))) {
```
`testbeds/` has no build file and `testbeds/todomvc/` has none either, so
**`testbeds/todomvc/skeritcom` and `testbeds/todomvc/todomvc-zenit` are real
build roots invisible to both `CI_LEVELS` and `CI_EXCLUDED`**, and
`zenit-auth/examples/basic-auth-app` is skipped by the `continue`. The pin's
own promise ("a new repo can no longer be silently forgotten") is therefore
false for anything nested deeper than one level. Also latent: `detectProject`
(`zenit-dev:901-910`) still resolves `todomvc` and `7gui` at `FRAMEWORK_ROOT`,
where they no longer live.
Fix: bounded recursive discovery (stop descending into a dir that has a build
file, i.e. keep the multi-module convention) + explicit `CI_EXCLUDED` entries
for the testbeds and the auth example. Counterfactual: the pin test must fail
today naming `testbeds/todomvc/skeritcom`.
**Real weakness in the proof, not in a shipped behaviour.** Cheap.

### 2. A8 - "persistent build-directory Maven repository" - PARTIALLY REAL, low
The isolation IS correct: `protoblast/build.gradle:228-229` defines the
`functionalTest` repo at `build/functional-test-repo`,
`protoblast-gradle-plugin/build.gradle:57-58` threads it in, and the repo
currently contains only `be/elevenways/protoblast-server` - the retired
`be.elevenways:protoblast` coordinate is gone. The counterfactual the agent ran
(restore the old coordinate -> `Could not find ... Searched in
.../functional-test-repo/...`) is a genuine proof of isolation.
The residual is exactly the bug class A8 fixed, one scope down: the directory is
never wiped, so a coordinate published by an *earlier* build persists as a local
orphan and would keep a retired coordinate green. `--clean` is the only cure and
the workspace rules discourage it.
Fix: make the `functionalTest` publish task delete the repo dir first (or have
the test task assert the repo's artifact set equals the current publication
set). Counterfactual: publish a bogus coordinate into the repo, run the suite,
assert it fails / is absent.
**Fix is right; the guard against its own recurrence is missing.**

### 3. A1 - "tests ProjectCompiler directly, not the real Gradle path" - PEDANTRY
`reconcileOutput` IS wired into the real plugin task:
`hawkeye-compile/src/main/groovy/.../HawkeyeCompile.groovy:257`
`List<String> staleOutputs = bridge.reconcileOutput(outputDir.toPath())`,
reached through the version-checked bridge
(`CompilerBridge.groovy:30 EXPECTED_BRIDGE_VERSION = 3`,
`ProjectCompiler.java:50 GRADLE_BRIDGE_VERSION = 3`, eager resolution at
`CompilerBridge.groovy:124`). The A1 report also documents a live 3-phase
journey through `zenit-dev compile-templates` on zenit's real
`browserTemplates` pipeline. The only genuinely un-proven bit is the
build-CACHE *restore*, simulated as a copy of declared outputs - which is
Gradle's documented pack model. **The fix cannot be wrong in the way the gap
implies. Do not spend effort.**

### 4. A3 - "fake timestamp JAR, not changed compiler contents" - PEDANTRY
The mechanism is content-based, not timestamp-based:
`pluginPackagingFingerprint` (`zenit-dev:2005-2013`) hashes
`cachedFingerprint(protoblast worktree) + hawkeye's PUBLISH-state effective
fingerprint`; `pluginRepackageStaleReason` (`zenit-dev:2015-2025`) compares that
hash, and only *additionally* checks `jarMtime` to catch out-of-workspace jar
replacement. The A3 report records a live double run in which the repackaged fat
jar was verified to contain the just-edited `CompilerBridge` string in the
bundled `.class`. The fixture's fake jar is a harness detail. **Do not spend
effort.**

### 5. A11 - "no actual Maven consumer-resolution test" - PEDANTRY
Maven is not installed on this machine; the claim is proven by deterministic
artifact shape, which I re-verified:
`~/.m2/.../hawkeye-common/0.1.0-SNAPSHOT/` contains only
`hawkeye-common-0.1.0-SNAPSHOT-compile-only.jar`, `.module`, `.pom`, and the POM
carries `<packaging>pom</packaging>`. A default Maven `<dependency>` cannot
resolve a jar that is not published. One cosmetic note: `textum-common`'s local
dir still holds the old classifier-less `textum-common-0.1.0-SNAPSHOT.jar`
beside the new compile-only one (its POM is already `packaging=pom`), i.e. a
machine-local residue, not a published-artifact problem. **Do not spend effort.**

---

## Summary table

| Item | Verdict | Actionable? |
| --- | --- | --- |
| F5 textum/hawkeye edge | REAL (latent impact) | YES - one-line vocabulary fix + a permanent sweep pin |
| F4 wildcard/exact overlap | FALSIFIED | No (optional: unify dispatcher `claimKey` with `RouteClaims.keyOf`) |
| F13 `org/teavm/**` exemption | PARTIALLY REAL | YES - narrow to the 10 measured FQNs + an order pin |
| F14 durable evidence | REAL | YES - copy 13 reports to `hohenheim/docs/reports/` + `docs/OWNER-DECISIONS.md` |
| A9 nested build roots | PARTIALLY REAL | YES (cheap) |
| A8 functional-test repo | PARTIALLY REAL | Maybe (cheap) |
| A1 / A3 / A11 | PEDANTRY | No |
