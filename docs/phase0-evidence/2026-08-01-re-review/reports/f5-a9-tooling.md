# F5 + A9: zenit-dev dependency graph and workspace inventory

Repo: zenit. Files touched: ONLY `zenit/tools/zenit-dev` and
`zenit/tools/zenit-dev.test.js`. Commits:

- F5 `2567e13` `🔗 Scan core projects when deriving dependency edges`
- A9 `f9c1b88` `🔍 Classify build roots nested under grouping directories`

Baseline before any edit: 26 tests, 26 pass. After: 28 tests, 28 pass.

---

## ITEM 1 (F5) - textum's hawkeye edge

### Confirmed as described

`scanOptionalDeps` tested candidate names only against `OPTIONAL_LIBRARIES`,
which contains no core project, so a declared core artifact in a non-core
repo's build file was structurally invisible. `directDepsFor` then seeded
`strong` from `baseDepsFor`, whose `case 'textum': return ['protoblast']`
was the only surviving answer. `textum/build.gradle` declares
`hawkeye-common` (103), `hawkeye-server` (112), `hawkeye-client` (122, 163,
179). Impact today is a graph lie, not a wrong artifact:
`grep -rli hawkeye textum/src --include=*.java` returns zero files and textum
ships no `.hwk`, so no hawkeye change can currently alter textum's output. It
becomes a stale-artifact bug the first time textum uses the API its build file
already provisions.

### CI_LEVELS claim: CONFIRMED, `ci` was never broken

`CI_LEVELS` places hawkeye at level 2 and textum at level 5, so the CI wave
already built hawkeye first. The "CI levels are topological over strong
dependency edges" test passed before the fix only because it consulted the
same blind scanner; it also passes after.

### Pre-fix failures, verbatim

Both counterfactuals were written first and run against otherwise unmodified
code. The only change present for this run was exporting `CORE_PROJECTS` and
`OPTIONAL_LIBRARIES` from `module.exports` (test plumbing, no behaviour).

Specific pin:

```
✖ textum depends on hawkeye because its build file says so (8.943557ms)
  AssertionError [ERR_ASSERTION]: textum declares hawkeye-common/client/server; a hawkeye-only change must mark textum stale
      at TestContext.<anonymous> (/home/skerit/projects/javaweb/zenit/tools/zenit-dev.test.js:915:12)
    actual: false,
    expected: true,
```

General pin, first run (this one found something the recon had not):

```
✖ every declared be.elevenways coordinate lands in the computed dependency closure (6.485559ms)
  AssertionError [ERR_ASSERTION]: protoblast declares be.elevenways:hawkeye-compile in protoblast-gradle-plugin/build.gradle but hawkeye is missing from its computed dependency closure [protoblast]
```

That one is not a defect: `protoblast-gradle-plugin/build.gradle:38` bundles
hawkeye's `CompilerStage`, and hawkeye depends on protoblast, so a DAG edge
there would be a cycle. Its freshness rides `pluginPackagingFingerprint` /
`pluginRepackageStaleReason` instead. It is now a single named exception in
the test (`INVERTED_COORDINATES`, keyed `<repo> <build file> <artifact>`) with
that reason written down.

General pin, after excepting the inverted edge - the textum bug caught
generically:

```
✖ every declared be.elevenways coordinate lands in the computed dependency closure (19.996433ms)
  AssertionError [ERR_ASSERTION]: textum declares be.elevenways:hawkeye-common in build.gradle but hawkeye is missing from its computed dependency closure [protoblast, textum]
```

### The fix

`scanOptionalDeps` now scans against `SCANNABLE_PROJECTS =
[...CORE_PROJECTS, ...OPTIONAL_LIBRARIES]` (also the ordering source), with an
AIDEV-NOTE naming the failure mode it closes.

### Decision on `baseDepsFor`: KEPT, demoted in wording to a floor

The recon asked whether the hardcoded table should stop being authoritative.
It must stay, and the reason is measurable: the scanner reads a project's ROOT
build files only, and the core repos are multi-module.
`protoblast/build.gradle`, `emberglyph/build.gradle` and
`hawkeye/build.gradle` contain no `be.elevenways:` coordinate at all -
hawkeye's protoblast dependency lives in `hawkeye/hawkeye-core/build.gradle`.
Deriving the core chain from scanning would therefore have LOST edges, the
exact failure being fixed. So:

- `baseDepsFor` stays, now documented as a FLOOR that scanning is unioned on
  top of, never a ceiling, plus why scanning cannot replace it.
- `default: [...CORE_PROJECTS]` stays as the safe over-approximation for
  consumer apps.
- The guarantee that it cannot silently under-report again is the general pin,
  not the table: any repo in `CI_LEVELS` whose build files (root plus Gradle
  submodules) name a `be.elevenways:` coordinate - or a `libs.*` catalog alias
  resolving to one, which is how zenit's own build file spells every
  dependency - that does not land in `depsForProject`'s closure fails the
  suite. The pin also fails when an artifact maps to NO known project, which
  is how the next new repo missing from the vocabulary gets caught.

Artifact-to-project mapping is longest-name-wins, so `zenit-auth-test-support`
stays its own project rather than being read as `zenit-auth`, and
`protoblast-loader-stub` / `hawkeye-test-support` / `zenit-browser-test-support`
map to their owners.

### Sweep, before and after

Re-ran the recon's `sweep.js` (every build root under the workspace, depth <=
4) before, and a copy with only the vocabulary line widened after. Whole-file
diff of the two outputs:

```
79,80c79
<    zenit-dev direct  : protoblast
<    *** MISSING: hawkeye
---
>    zenit-dev direct  : hawkeye, protoblast
```

MISSING count: 1 before, 0 after. Textum is the only project whose computed
deps changed anywhere in the workspace; every other repo was already exact or
harmlessly over-captured through the `default` branch.

Live check through the real module:

```
textum           protoblast hawkeye
plumage          protoblast emberglyph hawkeye janeway zenit
zenit-cms        protoblast emberglyph hawkeye janeway zenit plumage zenit-microcopy zenit-widget zenit-forms zenit-media zenit-pages
orcono/mvp-v01   protoblast emberglyph hawkeye janeway zenit plumage textum zenit-microcopy zenit-forms zenit-widget zenit-media zenit-cms zenit-auth
janeway          protoblast emberglyph
hawkeye          protoblast
```

`zenit-dev status` inside textum still runs normally.

---

## ITEM 2 (A9) - workspace inventory pin

### Pre-fix failure, verbatim

Discovery rewritten to recurse first, run against otherwise unmodified code:

```
✖ CI levels pin every workspace repo as a deliberate inclusion or exclusion (5.756548ms)
  AssertionError [ERR_ASSERTION]: testbeds/todomvc/skeritcom is neither in CI_LEVELS nor deliberately excluded (add it to one, with a reason if excluded)
      at TestContext.<anonymous> (/home/skerit/projects/javaweb/zenit/tools/zenit-dev.test.js:967:16)
```

### Discovery

Now recursive: descend from `FRAMEWORK_ROOT`, stop AT a build root (everything
below it is that build's own submodules, which is what preserved the previous
behaviour for hawkeye/protoblast/zenit and for
`zenit-auth/examples/basic-auth-app`), bounded at depth 3.

Depth 3 justification, measured not guessed: a `find -maxdepth 5` over the
workspace with build/.git/node_modules pruned shows the only build files below
depth 2 are `testbeds/todomvc/skeritcom`, `testbeds/todomvc/todomvc-zenit`
(depth 3, the deepest grouping nesting that exists) and
`zenit-auth/examples/basic-auth-app`, which is inside a build root and stays
correctly pruned. Depth 3 is therefore exactly sufficient, and it keeps the
walk off large unrelated trees such as `testbeds/js-framework-benchmark`.

### Testbed classification: EXCLUDED, with reasons

Both are demo testbeds, not deployables; nothing in the workspace depends on
them and they publish nothing. Both also carry their own hardcoded plugin
versions outside the version catalog (skeritcom pins TeaVM 0.13.1 and shadow
8.3.0; todomvc-zenit pins its own TeaVM/shadow), i.e. the same shape that
already excludes `arcana`. Adding them to CI would mean adding two full
TeaVM builds on non-catalog toolchains to every CI wave, which nobody asked
for. So:

```js
'testbeds/todomvc/skeritcom': 'demo testbed on a pre-catalog build shape (own TeaVM 0.13.1 and'
    + ' shadow versions); nothing depends on it and it publishes nothing',
'testbeds/todomvc/todomvc-zenit': 'demo testbed with hardcoded plugin versions; nothing depends'
    + ' on it and it publishes nothing',
```

If they should be built, that is a deliberate follow-up decision, and it is
now visible rather than invisible.

### `detectProject`'s todomvc / 7gui branches: dead, deleted

Confirmed wrong: `FRAMEWORK_ROOT/todomvc` and `FRAMEWORK_ROOT/7gui` do not
exist (both testbeds moved under `testbeds/`), and the branches matched on
`realRoot === FRAMEWORK_ROOT/<name>` or a path under it, so neither could ever
fire. They also could not fire from the grouping directory itself, which has
no build file and so is never chosen as `PROJECT_ROOT`. Deleted; the generic
`else` branch already derives the identical `PROJECT_NAME` (build root's own
directory name) and `PROJECT_TYPE = 'consumer'`, and now carries an AIDEV-NOTE
recording what was removed and why.

---

## Verification

- `node --test zenit-dev.test.js`: 26/26 before the work, 28/28 after, with
  the two new tests and the rewritten discovery. Re-run green repeatedly.
- Each counterfactual was run against unmodified code first (outputs above),
  then after the fix.
- Both commits were made from a state whose full suite had just run green.

### The known flake, recognised not "fixed"

Two of my full-suite runs failed on `a hawkeye change repackages the protoblast
plugin fat jar before consumers build`:

```
  AssertionError [ERR_ASSERTION]: a fresh chain must not repackage or rebuild any dependency:
    ...  protoblast: m2 jar replaced outside this workspace
    ok  protoblast
  + [ 'protoblast' ]
  - []
```

This is the guard from the 07-31 note. I verified it is NOT caused by my
change: I extracted `tools/zenit-dev` and `tools/zenit-dev.test.js` at
`9e8b95f` (the commit before my work) into a scratch directory and ran that
single test six times - 4 failed, 2 passed, with the identical
`m2 jar replaced outside this workspace` message. At the time, five other
agents were running `zenit-dev test` across zenit, zenit-auth, plumage and
hohenheim, and `~/.m2/.../protoblast-server-0.1.0-SNAPSHOT.jar` had been
republished seconds earlier. Pre-existing, load-driven, left alone.

## Limitations / follow-ups

- The general pin covers repos in `CI_LEVELS` only. CI-excluded repos (arcana,
  hohenheim, the two testbeds) are not checked, by construction - they are
  outside the wave the DAG orders.
- `INVERTED_COORDINATES` has exactly one entry. It is a declared exception
  list, so a future entry is a decision someone has to write a reason for; it
  is not a silence mechanism.
- The pin resolves catalog aliases from `zenit/gradle/libs.versions.toml`
  only. An app declaring a be.elevenways artifact through a private catalog of
  its own would not be seen. No such app exists today.
- `zenit-auth/examples/basic-auth-app` remains deliberately invisible to both
  the pin and CI: it is a separate Gradle build (its own `settings.gradle`)
  inside a build root.
