# Guard coverage matrix + the inert-marker fix

Date: 2026-08-01

## The defect, confirmed and closed

`textum/locale-folds.guard` was INERT: textum applies only
`be.elevenways.protoblast.publish`, and only `ProtoblastGradlePlugin` (the full
plugin) wired `configureLocaleFoldGuard`/`configureNulByteGuard`. Proven
empirically BEFORE the fix (planted no-arg `toLowerCase()` in
`textum/src/common/.../Textum.java`, built with the committed marker present):

```
── Building textum (dependencies republished) ──
  ok  textum
  ok  Build completed in 54s
```

Green, with a live violation in a production source set. After the fix, the
SAME plant:

```
Execution failed for task ':checkLocaleFolds' (registered by plugin 'be.elevenways.protoblast.publish').
> Locale-sensitive case fold(s) found. [...]
    /home/skerit/projects/javaweb/textum/src/common/java/be/elevenways/textum/common/Textum.java:18: return input.toLowerCase();
```

Plant reverted; textum rebuilt green with BOTH markers armed.

## Coverage matrix (repo x guard)

Legend: **ENF** = enforcing (marker present AND wired, or always-on) /
**avail** = task registered, marker deliberately absent (opt-in not armed; NOT
misleading — no marker, no claim) / **n/a** = mechanism cannot run there
(no protoblast plugin) / (E) = empirically confirmed today by a planted
violation or a real refused commit, everything else code-read from the plugin
wiring. State is AFTER my fixes; the one pre-fix inert cell is called out below
the table.

| Repo | Plugin shape | checkLocaleFolds | checkNulBytes | Locale drift test | Precommit hook (NUL + split + inert-marker) | Dup-FQN / BlastCompileGuard / patch-lane |
| --- | --- | --- | --- | --- | --- | --- |
| protoblast (root) | none (cannot apply own plugin) | n/a | n/a | ENF (LocaleFoldingTest + LocaleFoldGuardTest) | ENF | root: hand-wired equivalents per build.gradle comments; gradle-plugin subproject n/a |
| hawkeye / hawkeye-core | `.publish` (subproject) | **ENF (E)** — marker added today, planted fold failed `:hawkeye-core:checkLocaleFolds` | ENF — marker added today | ENF (LocaleFoldGuardTest, also covers lsp+compile) | ENF | ENF (RuntimeClasspathGuard + BlastCompileGuard via .publish) |
| hawkeye root / -compile / -lsp | none | n/a (drift test covers) | n/a (hook covers) | ENF (same drift test) | ENF | n/a |
| textum | `.publish` only | **ENF (E)** — was INERT pre-fix (E), now fires | **ENF (E)** — marker added today, planted NUL failed `:checkNulBytes` | none | ENF (E: consistency probe passed) | ENF |
| zenit (root, alias spelling) | full | ENF | ENF | none | ENF (E: probe) | ENF |
| zenit-auth | full | ENF | ENF | none | ENF (E: probe) | ENF |
| proteus | full | ENF | ENF | none | ENF (E: probe) | ENF |
| plumage | full | ENF | ENF | none | ENF (E: probe) | ENF |
| zenit-widget | full | ENF | ENF | none | ENF (E: probe) | ENF |
| zenit-comms | full | ENF | ENF | none | ENF (E: probe) | ENF |
| zenit-microcopy | full | **ENF (E)** — planted fold failed | **ENF (E)** — planted NUL failed | none | ENF (E: probe) | ENF |
| quirkyquarters (kts) | full | ENF | ENF | none | ENF (E: probe) | ENF |
| herald | full | avail | ENF | none | ENF (E: probe) | ENF |
| spamservice | full | avail | ENF | none | ENF (E: probe) | ENF |
| thoth | full | avail | ENF | none | ENF (E: probe) | ENF |
| zenit-a2ui | full | avail | ENF | none | ENF (E: probe) | ENF |
| zenit-ai | full | avail | ENF | none | ENF (E: probe) | ENF |
| zenit-cms | full | avail | ENF | none | ENF (E: probe) | ENF |
| zenit-flow | full | avail | ENF | none | ENF (E: probe) | ENF |
| zenit-forms | full | avail | ENF | none | ENF (E: probe) | ENF |
| zenit-media | full | avail | ENF | none | ENF (E: probe) | ENF |
| zenit-oidc | full | avail | ENF | none | ENF (E: probe) | ENF |
| zenit-pages | full | avail | ENF | none | ENF (E: probe) | ENF |
| orcono (mvp-v01 subdir) | full (in mvp-v01) | avail | ENF (marker in mvp-v01, correct Gradle root) | none | ENF (E: probe) | ENF |
| duiventil | none (IS a git repo) | n/a | n/a | none | **ENF (E)** — real commit with staged NUL refused; real commit with planted marker refused | n/a |
| emberglyph | none | n/a (2 known Tier-3 fold sites remain) | n/a | none | ENF | n/a |
| janeway | none | n/a (6 known Tier-3 fold sites remain) | n/a | none | ENF | n/a |
| hohenheim (external) | full | avail | avail (no marker) | none | ENF (E: probe passed, no markers to lie) | ENF |

Non-git dirs (alchemy, arcana, references, resources, node-editor-research,
zenit-console-design, zenit-auth-test-support, testbeds top-level) carry no
markers; testbeds/js-framework-benchmark is git but marker-free.

**Pre-fix inert cells: exactly one** — textum x checkLocaleFolds. Full-repo
`find -name '*.guard'` found 29 markers; all other 28 sat at a Gradle project
root applying the full plugin. No NUL marker was inert. No marker had a typo'd
name.

## Fixes landed

1. **Plugin wiring (protoblast 4a9d4c6)** — extracted the two guard
   configurations verbatim into `SourceGuardInstaller.install(Project)`
   (new class, `protoblast-gradle-plugin`), idempotent via an ext-properties
   flag (a project applying both plugin ids registers the tasks once). Called
   from BOTH `ProtoblastGradlePlugin.apply` and `PublishModulePlugin.apply`.
   Within the plugin family, "marker present but plugin does not wire it" is
   now structurally impossible.
2. **Commit-time consistency check (zenit 35eae9b)** —
   `zenit-dev precommit-guard` gained `checkGuardMarkerConsistency`: every
   `*.guard` file in the INDEX must (a) have a known name
   (`locale-folds.guard`, `nul-bytes.guard`) and (b) sit beside a
   `build.gradle(.kts)` that applies a protoblast plugin (`id '...'`,
   `id("...")`, `apply plugin:`, or `alias(libs.plugins.protoblast)`).
   Violation = COMMIT REFUSED naming the marker and why. Runs whole-repo on
   every commit, so drift is caught at the next commit in that repo.
3. **textum (2cddd9b)** — `nul-bytes.guard` added (it was excluded from the
   20-repo rollout precisely because the gate could not run there; now it can).
   Its locale marker needed no change — it went from inert to live via fix 1.
4. **hawkeye (d7bbbf5e, branch type-system-v1)** — both markers added to
   `hawkeye-core` (the other `.publish` consumer), planted-violation proven.
   The repo-wide drift test stays: it also covers hawkeye-lsp and
   hawkeye-compile, which the marker cannot.

## The structural choice, argued

Chosen: **both plugins wire every guard** (kills the wrong-plugin shape at the
root) + **commit-boundary marker-vs-wiring validation** (kills the shapes no
plugin can see). Rejected alternatives:

- *Opt-out inversion (guard on by default)*: today's precedent is explicit —
  the first unconditional locale wiring escaped via auto-publish and turned the
  workspace red. Worse, inversion cannot close the class: a plugin-less repo's
  marker (or opt-out file) is still consulted by nothing, so the lie survives.
  Also janeway/emberglyph still carry real fold sites; default-on would go red
  the moment they ever applied a plugin.
- *Plugin-side validation only*: the plugin never RUNS in a plugin-less repo or
  at the root of a multi-project build (the hawkeye shape) — the exact places a
  marker rots. The commit boundary is the only universal choke point, the same
  argument that put the NUL check there.
- The typo shape (`locale-fold.guard`) is only caught by the commit-time check
  (unknown-name refusal); no plugin-side design can see a file it never looks
  for.

## Proof: the anti-inert mechanism, verbatim

Real `git commit` in duiventil (plugin-less) with a planted
`locale-folds.guard`:

```
  fail  COMMIT REFUSED: 1 guard marker(s) that no build enforces
      locale-folds.guard: sibling build.gradle applies no protoblast plugin — the marker gates NOTHING
  [...] (the textum incident, 2026-08-01).
exit=1
```

And the pre-existing NUL half re-proven live (real commit, duiventil):

```
  fail  COMMIT REFUSED: raw NUL byte(s) in 1 staged location(s)
      planted-nul.md:1
exit=1
```

## False-positive sweep

The REAL `zenit-dev precommit-guard` was executed (dummy staged file) in all 21
marker-bearing repos plus hohenheim: 22/22 PASS. Covers all four plugin
application spellings in the wild: quoted id, kts `id("...")`, subdirectory
project (orcono/mvp-v01), and zenit's `alias(libs.plugins.protoblast)`.

## Corrections carried

- **JS is not Java**: `CheckLocaleFoldsTask.scanTree` and both drift tests
  filter `endsWith(".java")`; the precommit NUL check is byte-based with no
  locale semantics. `zenit/tools/zenit-dev`'s 5 no-arg `toLowerCase()` calls
  (locale-INdependent in JavaScript) are untouched and unflagged by every
  guard. Confirmed by reading all three scanners.
- **duiventil IS a git repo** and is handled as a plugin-less shape (hook-only
  coverage, both refusals proven on real commits there).

## Tests added/updated

- `zenit/tools/zenit-dev.test.js`: new 6-step journey "precommit guard refuses
  guard markers that no build enforces (the inert-marker class)" — no sibling
  build, plugin-less sibling build, publish-plugin pass, kts-subproject +
  alias-spelling passes, root-marker-over-subproject-plugin refusal, typo'd
  name refusal. Full suite: 40/40 pass.

## Verification performed

- Baseline (guard inert) and post-fix (guard fires) builds in textum, verbatim above.
- Planted-violation build failures: textum locale (via `.publish`), textum NUL
  (via `.publish`), zenit-microcopy locale + NUL (via full plugin, post-refactor),
  hawkeye-core locale (via `.publish`). All reverted; all repos rebuilt green.
- Real-commit refusals in duiventil: staged NUL, planted inert marker.
- protoblast-gradle-plugin unit+functional tests: 12 + 7 pass
  (ProtoblastPluginFunctionalTest, BundleSizeBudgetFunctionalTest,
  ProtoblastApplicationDistFunctionalTest, MergeResourceFilesTaskTest,
  BlastCompileGuardTest).
- `node --test zenit-dev.test.js`: 40/40.
- Chain green with the new plugin: protoblast -> hawkeye -> zenit -> plumage/
  microcopy/widget/forms/media/pages -> zenit-cms full `zenit-dev build` (290s,
  ok), plus textum and hawkeye green with markers armed.
- All 4 commits passed the live pre-commit hooks (including the new check) and
  were verified with `git log -1`: gitmoji first char, subject < 72 chars,
  subject/body on separate lines, <= 3 lines.

## Commits (4, nothing pushed)

- protoblast `4a9d4c6` — 🛡️ Wire the marker guards into every protoblast plugin
- zenit `35eae9b` — 🛡️ Refuse commits carrying guard markers no build enforces
- textum `2cddd9b` — 🛡️ Opt into the checkNulBytes compile gate
- hawkeye `d7bbbf5e` (type-system-v1) — 🛡️ Arm both marker guards in hawkeye-core

## Known limitations / notes

- The Gradle gates stay per-Gradle-project: a multi-project root's marker only
  gates the root project. The commit-time check refuses a marker at a root
  whose own build.gradle applies no protoblast plugin (tested, step 5 of the
  new journey), so the misleading variant of that shape cannot land.
- Plugin-less repos still have NO locale enforcement (duiventil, emberglyph,
  janeway) — honest, marker-free gaps; janeway (6) and emberglyph (2) carry
  known Tier-3 fold sites and cannot arm until fixed. protoblast + hawkeye
  compensate with drift tests.
- The consistency check reads the sibling build file textually; a protoblast
  plugin applied through an exotic indirection (script plugin, convention
  plugin) would false-refuse. No such spelling exists in the workspace or
  hohenheim today; the refusal message says exactly what to do if one appears.
- Collateral during verification: a concurrent hohenheim chain build hit my
  planted hawkeye violation once (journal 14:29:16, exit=1). Transient — the
  plant was reverted within a minute and the chain rebuilt green.
