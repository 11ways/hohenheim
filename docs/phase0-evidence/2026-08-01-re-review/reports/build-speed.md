# Build-speed report (2026-08-01)

Owner mandate: cut the ~4h38m/day of building (dep.build 3h18m + build.self 1h20m),
without verifying less. Journal source: ~/.local/share/zenit-dev/journal.jsonl,
24h window ending 17:26. Analysis script:
scratchpad/analysis/journal-analyze.js (rerunnable).

## 1. Ranked cost table — BEFORE (last 24h, journal)

| Bucket | n | total | avg | notes |
|---|---|---|---|---|
| gradle.run (all build gradle invocations) | 660 | 4h48m | 26s | parent bucket of the three below |
| dep.build (dependency republishes) | 433 | 3h19m | 28s | + 53 skipped published-by-other-session |
| build.self | 118 | 1h22m | 42s | |
| verify.head | 84 | 1h01m | 43s | untouched (mandate) |
| plugin.repackage | 30 | 9m | 18s | hawkeye-compile fat jar, correct edge |
| deps.check (freshness decisions) | 340 | 47s | 0.1s | freshness checking itself is ~free |

dep.build per repo (top): zenit 43x/35m43s (avg 50s), plumage 42x/28m42s (41s),
spamservice 13x/21m (98s), zenit-media 37x/15m (24s), zenit-forms 39x/14m (21s),
zenit-widget 40x/12m43s (19s), zenit-cms 28x/11m (23s), hawkeye 26x/10m34s (24s),
zenit-microcopy 36x/9m41s (16s), zenit-auth 24x/9m30s, zenit-pages 30x/9m13s.

Where a single dep republish's time went (task-level, --profile, warm daemon,
forced full rebuild; representative because real dep rebuilds re-execute almost
the whole pipeline once an upstream jar changed — verified against real logs:
:serverJar executed 283x, :generateJavaScript 83x, :compileCommonHawkeyeTemplates 281x):

- zenit-widget (22.0s of tasks): serverJar (UNPUBLISHED fat ShadowJar) 6.3-16s,
  compileCommonHawkeyeTemplates 9.0s, javac x3 3.2s, scans/loaders ~1.5s.
- plumage: generateJavaScript (TeaVM, library-INTERNAL bundle) 22s,
  compileBrowserTestHawkeyeTemplates 12.4s, compileCommonHawkeyeTemplates 11.8s,
  serverJar 7s. assemble -> serverJar -> copyClientJs -> generateJavaScript ->
  browserTestJar pulls the whole browser-TEST bundle into every dep republish.
- Fixed overhead: warm gradle run ~1.5s; COLD daemon start 8-25s. 582 cold
  starts counted in retained logs (~75% of runs); 2-min idletimeout + the
  stale-jar daemon kills make chain propagation all-cold by design.

## 2. Root cause of the 485 dep.builds

Not fingerprint waste: freshness is content-based and correct (every rebuild
reason was a real content change; cross-session dedup fired 53x; deps.check
costs 47s/day total). The waste was WHAT each republish built: zenit-dev ran
`assemble publishToMavenLocal` (zenit: + t01Verification), and `assemble`
builds artifacts NO consumer can observe:
- fat server ShadowJars, explicitly "NOT published" per every build.gradle;
- library-internal TeaVM bundles + browser-test jars (plumage);
- zenit's t01Verification contract chain incl. generatedAppSmoke
  (62 smokeapp installDist runs/day, 10m) on EVERY dep republish of zenit —
  and 20 of 71 zenit gradle runs failed AFTER the publish had already
  succeeded, i.e. a t01V flake broke unrelated consumers' builds.

Everything a consumer observes from a dep is its published m2 artifacts (plus
the separately managed hawkeye-compile plugin repackage). Verified: dep assets
(css) ride the published jars; AssetMiddleware serves own public/ + classpath;
consumers never read a dep's build/ or public/.

## 3. Changes shipped (zenit repo, commit f1447b9)

Files: zenit/tools/zenit-dev, zenit/tools/zenit-dev.test.js.

1) Lean dependency republishes: the two dep-build call sites now run
   `publishToMavenLocal` only (getGradleDepBuildTask). Self builds, ci and
   verify-head keep their full task lists — nothing verifies less where
   verification was the point.
2) Lean records promote: a dep publish writes `lean: true`; a later
   `zenit-dev build` IN that repo treats the lean record as self-stale, runs
   the full list once (incl. zenit's t01V) and clears the flag. Proven live:
   zenit-widget self build reported "last publish was a lean dependency
   republish", ran full assemble, then no-oped.
3) Dropped `--refresh-dependencies` from the upstreamChanged consumer
   self-build: measured +9.4s pure overhead per build (1.5s vs 10.9s no-op
   assemble). It only revalidates REMOTE repos; mavenLocal (file repo) is
   exempt from Gradle's module cache and the same branch already forces a
   fresh JVM (daemon kill / --no-daemon). Production evidence: the dep-loop
   path never passed the flag and upstream propagation has demonstrably
   worked there for months.

Tests: new behaviour journey "dependency republishes run lean tasks and a
self build promotes the lean record" (4 steps, fixture workspace, asserts
task strings, lean flags, promotion, convergence). Full CLI suite 41/41 pass
(3 runs during development + final).

## 4. Measured before/after

Forced full-rebuild A/B, same warm daemon, same content (assemble
publishToMavenLocal vs publishToMavenLocal):

| repo | before | after | delta |
|---|---|---|---|
| zenit-widget | 23.2s | 13.2s | -43% |
| zenit-forms | 47.2s | 27.0s | -43% |
| hawkeye | 44.1s | 16.3s | -63% |
| plumage | 124.7s | 50.3s | -60% |

Real production events after the change went live (journal):
- zenit dep republish: 16.2s lean vs 31.4s full earlier the same hour
  (day avg was 50s incl. t01V + nested smoke runs).
- plumage lean: 23.2s (day avg 41s); warm lean: 9.4s.
- Spurious "m2 jar replaced" zenit republish: 1.6s (was a full 30-60s run).
- Warm no-op chain build (zenit-cms, 11 deps): 0.3s.
- Leaf self build (zenit-widget, lean promotion): 15.0s full; converged next.
- Cross-session dedup intact: multiple published-by-other-session skips during
  three concurrent propagations.

Cold full-chain: NOT run as a monolithic A/B — the final verification gate
held the suite lock and gradle slots all session, and a --force chain would
have serialized every other agent behind my locks twice. The chain delta is
the sum of the per-repo A/Bs above (the four repo shapes measured cover the
chain; the dep portion of a cold chain drops 43-63%).

Honest caveat: journal dep.build averages in the first post-change hour read
HIGHER (40s avg over 22 builds) because three sessions propagated
concurrently while the gate ran browser suites — dep.build duration includes
lock/slot waiting. The same-conditions A/Bs and per-gradle.run durations are
the valid comparison; expect the daily aggregate to settle at the A/B ratios.

## 5. Projected saving per working day (today's actual mix)

- dep.build graph trim: per-repo count x measured delta ≈ 80-85 min/day
  (zenit ~21m, plumage ~18m, hawkeye ~6.5m, widget/forms/media/microcopy/
  cms/auth/pages ~30m combined, misc ~4m). That is ~40-45% of the 3h19m bucket.
- --refresh-dependencies removal: ~60-80 upstreamChanged self builds/day x 9.4s
  ≈ 10-12 min/day.
- Offset: lean-record promotions add one extra full self build when an agent
  starts working in a repo that was last touched as a dep (~-5 min/day).
- Net ≈ 1h25m-1h30m/day off the 4h38m build total, verification unchanged.
- Second-order (unmeasured, real): fewer smokeapp child runs and shorter
  t01V-flake blast radius; fewer daemon-heap-heavy fat-jar zips.

## 6. Tried / evaluated and NOT shipped (and why)

- Configuration cache: the recorded blocker is protoblast/build.gradle
  onlyIf { !isTestOnly } (script-state capture; lines 52/98/120/160/186) —
  fixable per se, but CC needs a chain-wide script+plugin audit including the
  third-party org.teavm 0.15 plugin. MEASURED potential: warm configuration
  is 1.4-1.9s per run on every big repo (zenit/cms/hawkeye/plumage) — CC saves
  at most ~1s x 660 runs ≈ 10 min/day and does NOT touch the cold-daemon tax.
  Poor ROI vs risk; declined. (Confirms the 2026-07-04 memory verdict with
  numbers.)
- Daemon kill / 2-min idletimeout: cold starts cost 8-25s and ~75% of runs
  are cold, but the kills are the ZipFile-LOC-header correctness defence
  during propagation and the idletimeout is the deliberate multi-repo memory
  guard (35GB machine, test suites take 4-6GB). Raising idletimeout only
  helps same-repo rebuilds >2min apart and risks daemon-heap pileup; the
  kills themselves are required exactly when reuse would pay. No cheaper
  correct answer found that I could prove today; left alone.
- Gradle slots (2) / per-repo serialization: lock waiting was ~zero in the
  24h baseline; not the bottleneck; untouched per mandate.
- verify.head (1h01m/day): untouched. assemble+testClasses+browserTestClasses
  at head IS the check (TeaVM bundle compile and shadow merge are real
  failure surfaces); trimming it would verify less.
- Freshness system: audited for self-invalidation (untracked-mtime hashing,
  build-generated files) — found none; gitignored build outputs never enter
  the fingerprint; rapid same-dep rebuilds in the journal were genuine
  content changes by concurrent agents, already deduped across sessions.

## 7. Surfaced smells (report-only, not touched)

1. plumage assemble wires the browser-TEST bundle into the product path:
   serverJar -> copyClientJs -> generateJavaScript -> browserTestJar ->
   compileBrowserTestHawkeyeTemplates. Test artifacts riding assemble is a
   wiring smell; it still costs plumage SELF builds ~35s.
2. plumage's publishToMavenLocal graph includes
   compileBrowserTestHawkeyeTemplates (~12s) — apparently via the scss fold
   consuming ALL template-compile outputs. If browser-test fixture styles
   are not meant to be in the published plumage.css, that is both a size and
   a time bug. Hawkeye-compile owner should look.
3. protoblast's isTestOnly/isBuildTask task-name sniffing (the CC blocker)
   is fragile: task-name string matching decides whether client compilation
   happens.
4. Test-side (for the test-optimisation owner): gradle.stream test runs pay
   the same cold-daemon tax; and the zenit unit suite's t01Verification
   child (generatedAppSmoke) spawns nested gradle runs that occupy slots.

## 8. Verification performed

- zenit-dev CLI suite: 41/41 pass (includes the new 4-step lean journey).
- Live production traffic on the new code observed in the journal doing lean
  republishes, correct cross-session skips, lean promotion and convergence.
- No-op chain build 0.3s; converged repo no-ops verified.
- Commit f1447b9 in zenit (gitmoji subject, 3 lines), only my two files staged.
