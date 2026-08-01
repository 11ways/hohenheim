# Re-review orchestration (2026-08-01)

Goal: verify and close the 14 findings from the independent re-review of the
2026-07-31 remediation. Findings file: REVIEW-FINDINGS.md (same directory).
Prior session's evidence: prior/ (copied from the ephemeral 07-31 scratchpad).

Starting HEADs (all worktrees clean):
zenit 8b6a60b, zenit-auth af25fa6, hawkeye ab61cb43, protoblast c76381a,
plumage 37bde67, zenit-cms 5408bcd, zenit-forms 59f6f57, textum 6ae80d5,
orcono ecd8707.

## Doctrine for this run
- Verify before fixing. The 07-31 remediation falsified ~6 of the original
  ledger's claims; this reviewer can be wrong the same way. A falsified finding
  with proof is a valid, valuable outcome.
- Per-agent green claims are NOT sufficient evidence. On 07-31 an agent verified
  against a stale build artifact and committed code that did not compile. The
  final chain build is a hard gate, not ceremony.
- One test suite machine-wide at a time; implementation serialized per repo.
  Read-only recon may run in parallel.
- Every fix carries a RECORDED pre-fix failure.
- Commits: real Unicode gitmoji first char, subject and body on separate lines
  (heredoc newline loss collapsed 8 commits on 07-31), max 3 lines.

## Wave 1 - recon (read-only, parallel)
| id | scope | findings | model | state |
| -- | ----- | -------- | ----- | ----- |
| R1 | zenit core | F1 vocabulary canonicalization, F6 gate-loss diagnostic, F8 E4 pre-auth DB work, F11 CSP acceptsMatch | opus | DONE - F1 falsified/other gap, F6+F8+F11 real |
| R2 | zenit-auth | F2 createDirectGrant guard, F3 role self-pinning | opus | DONE - F2 FALSIFIED, F3 REAL |
| R3 | tooling + hohenheim | F5 textum deps, F4 wildcard overlap, F13 teavm exemption, F14 evidence, A-wave proof-gap triage | opus | running |
| R4 | frontend/compiler | F9 reactive disabled, F10 locale toLowerCase sweep, F12 detached save, small F5/F9/F15 gaps | opus | running |

## Wave 1 - independent work already dispatched
| id | scope | model | state |
| -- | ----- | ----- | ----- |
| BASE | F7: BrandTest order-dependence fix + definitive green zenit baseline | opus | DONE - zenit 9e8b95f, suite 2056/1981/0 |
| CBSC | worker-namespace leak - ALL SIX container backends, not just Couchbase | opus | DONE - zenit 3e38756 |
| EVID | F14: durable evidence + manifest reference/tier honesty | fable | running (also probes whether the Fable spend limit is still exhausted) |

## Wave 2 - fixes
| id | scope | model | state |
| -- | ----- | ----- | ----- |
| F3FIX | zenit-auth role self-pinning; invariant = actor may not widen authority of any subject in expandSubjects(actor); root bypass preserved | fable | running |
| F6FIX | zenit RecordSourceRegistry: report gate DIFFERENCE not weakness + cross-workspace register-site sweep | fable | running |
| T1 | zenit-dev F5 + A9 | opus | DONE - zenit 2567e13, f9c1b88 |
| T2 | F13 + A8 | fable | DONE - protoblast d5b6938 + 5a9adbe, hawkeye 8458798, proteus 107d498 |
| L1 | locale core: BlastString slug/camelize (tier 0), Uri, Totp, email identity, hawkeye diagnostics + durable guard | fable | running |
| L2 | locale SSL/DNS/app: hohenheim DNS family, ACME/CertificateStore, zenit-oidc RedirectUriMatcher, zenit-ai, spamservice seed | opus | running |
| FE1 | F12 + F9 | opus | DONE - orcono f6e3756, hawkeye d8e4294 + 7e9ada1, plumage fa5138a |
| CORE3 | DONE - zenit cb3750a (F8), b9acefe (F11), cdffae3 (F1). Old row: zenit F8 (always-defer contextual match + opt-in), F11 (CSP honours acceptsMatch), F1 (FieldRedaction refusal decision) - 3 separate commits | opus | running |

### More verdicts
- **F1 FALSIFIED as stated.** D1's bug was a check/use MISMATCH; the vocabulary
  facet has no check to mismatch with - `Vocabulary.Builder.add` applies no
  secret/filterable test, so a declaration can already put the schema's OWN
  canonical secret field into a vocabulary and get the identical oracle. Forging
  buys zero privilege and canonicalization would close nothing. The real gap is
  a missing `FieldRedaction` refusal on explicit vocabulary variables - which is
  not what the reviewer asked for. Zero production vocabulary call sites, so the
  refusal would ship unwired; CORE3 owns the judgement call.
- **F6 REAL, implied fix IMPOSSIBLE.** "Weaker permission" is not decidable:
  `Permission` is an opaque dotted string with no lattice, the only ordering
  (wildcards) orders GRANTS not permissions, and the question needs the live
  grant graph which does not exist at registration time. Replaced with
  difference-reporting + `override(...)` as the acceptance hatch. Blind spots
  confirmed: different permission, different accessCriteria lambda, createPermission
  (falls back to permission(), so one swap moves two gates), editUrl/inline-create.
  Only item with live blast radius - can newly fail boot in several apps.
- **F8 REAL but latent.** Ordering confirmed (resolveEndpoint at HttpConduit:444,
  middleware at :465-476). The vulnerable shape exists in hohenheim
  (SITES_DEPLOY/ROLLBACK/PROCESS_KILL take SITE_ID, gated only by baseline("/")),
  but SITE_ID is a plain param, and ModelParam has zero production consumers -
  so the trap is armed for the next person, not sprung.
- **F11 REAL but unreachable.** `matchWhen` has 18 production consumers and
  `claimingRoutesOf` exactly one, and they never intersect. Impact if reached is
  a wrong CSP header, not privilege. One-line fix.
- Stale line numbers in the review: F6 `145-167` -> method opens at 141;
  F11 `548-553` -> 543.
- **F4 FALSIFIED.** `SiteDispatcher` resolves in strict tiers - exact, then
  wildcard, then regex, longest-path within tier, returning on first hit. An
  exact host coexisting with a covering wildcard is the nginx/Caddy carve-out
  semantic: deterministic, nothing dropped. The genuine single-route case is an
  exact row plus a WILDCARD-TYPED row spelling the same literal (a glob-free
  pattern is fully shadowed by tier 1) - which is exactly what the test asserts,
  so the test-quality claim is falsified too. Minor real residual: the
  dispatcher's own duplicate guard builds claimKey WITH `kind` while
  `RouteClaims.keyOf` deliberately omits match type - two disagreeing spellings
  of route identity, defence-in-depth only. NOT dispatched; loose end.
- **F5 REAL but latent.** A5's DAG seeds every node from the old hardcoded table
  (`const strong = [...baseDepsFor(name)]`), and the scanner only tests names in
  `OPTIONAL_LIBRARIES`, which contains no core project - so the missing edge
  cannot be recovered. textum genuinely declares hawkeye in 5 configurations but
  uses it in ZERO java files and ships no .hwk, so no artifact can be wrong
  today. `CI_LEVELS` accidentally orders textum after hawkeye, so `ci` was never
  broken. Sweep of every build root: textum is the ONLY under-captured repo.
- **F13 PARTIALLY REAL.** The lane is sanctioned, but measured against the real
  jars only 10 FQNs actually duplicate (TUUID, TConcurrentHashMap, and 8 JSO
  event classes) while the wildcard exempts ~3,800. Both TeaVM 0.13.0 and 0.15.0
  are in the gradle cache, so a two-version classpath would today be silently
  exempted wholesale. Nothing pins that the patched copy wins.
- **F9 REAL and correctly documented - do NOT build the mechanism.** The two
  writers are provably independent (per-attribute reactive method vs separate
  render-lane method, neither observes the other), but `DominoElement` is a flat
  string map with no claims/layers, and element attachments are disqualified
  because they do not serialize (which is why the shipped fix uses an attribute
  marker). Production reachability is ZERO across all 10 use:List call sites.
  TWO real sub-findings: the leak is SYMMETRIC and only one direction is
  documented, and the fixture's reactive `disabled` sits on `use:List.remove`
  whose render half passes `disabled=false` unconditionally - so the directive
  never claims ownership and the assertion CANNOT fail (third vacuous fixture
  found in this arc).
- **F10 REAL and much bigger than reported.** 250 no-arg sites, 180 non-test.
  Tier 0 is `BlastString.slug/camelize` using the bare form INSIDE the class that
  documents `lower()` as the fix - common code, so a Turkish-locale JVM and TeaVM
  produce DIFFERENT SLUGS for the same input, breaking hawkeye's identical-DOM
  invariant, and slug() is the identity of cms saved views, devtunnel slugs and
  hub project slugs. Tier 1 includes `Totp` upper-casing a base32 secret
  (`i` -> `İ` under tr = 2FA permanently broken, fail-closed DoS), Uri host/scheme
  folding, OIDC redirect matching, ACME/cert lookup, and a deterministic seed
  UUID. `StringFunctions` is deliberate and must be left alone.
- **F12 REAL, exactly one site** (`EditorSession.java:679-681` catch lane).
  Guard doctrine confirmed correct everywhere else: `disposed` tombstone, zero
  `isConnected` uses. One apparent sibling FALSIFIED - `provideMentionResults`
  is unguarded but safe because Textum's TypeaheadPlugin owns its own tombstone.

- **F7 CLOSED.** zenit unit suite: total 2056 / passed 1981 / failed 0 /
  skipped 75, 288s. Forced fresh (`--rerun`, real per-test events, not cached)
  and started 11s AFTER the commit it certifies (9e8b95f). Yesterday's
  infrastructure diagnosis is now EXPERIMENTALLY confirmed rather than merely
  plausible: across the four red runs there was exactly ONE real defect
  (BrandTest); every other failure was Couchbase collection/index timeouts or
  cold-start `Mapped port` cascades.
  BrandTest's root cause was NOT what anyone assumed - the leaked `/brand.css`
  entry is planted by a STATIC INITIALIZER in production wiring
  (`BrandEndpoints.java:36-38`), not by a test, so "make the polluter clean up"
  was impossible and a registry reset seam would NOT have fixed it (a faithful
  snapshot/restore preserves the entry; a destructive clear would permanently
  delete a registration no code can recreate). The actual defect was the
  ASSERTION: it selected entries by href prefix (`/b`), and `/brand.css` matched.
  Now scoped by identifier namespace, which no registration by any owner can
  perturb. Pre-fix failure recorded under a deliberately perturbed fork order.
  Reported-not-fixed from its sweep: four tests call `Models.registerInstance`
  in `@BeforeAll` with no unregister (no collision today, nothing enforces it),
  and `SitemapsTest` leaks a provider+endpoint into `Registries` (benign now,
  would poison any future exact-list sitemap assertion).

- **F5 + A9 CLOSED** (zenit 2567e13, f9c1b88). The scanner's vocabulary is now
  `[...CORE_PROJECTS, ...OPTIONAL_LIBRARIES]`. `baseDepsFor` was KEPT
  deliberately as a documented floor, on measured grounds: the scanner reads ROOT
  build files only, and protoblast/emberglyph/hawkeye root build.gradle contain
  no `be.elevenways:` coordinate at all (hawkeye's protoblast dep lives in
  hawkeye-core/), so deriving the core chain from scanning alone would have LOST
  edges - the same bug wearing different clothes. What actually stops silent
  under-reporting is the new GENERAL pin: for every CI repo, every coordinate in
  its own build files (root + submodules, with `libs.*` catalog aliases resolved,
  since zenit spells every dependency that way) must land in its computed
  closure, and an artifact owned by no known project also fails.
  The general pin immediately caught something the recon missed: the protoblast
  gradle plugin declares `be.elevenways:hawkeye-compile`. That one is legitimate
  (the plugin bundles hawkeye's CompilerStage; a DAG edge would be a cycle, and
  freshness already rides `pluginPackagingFingerprint`), so it is now one named
  exception with its reason written down rather than an invisible hole.
  A9: discovery recurses, stopping AT a build root, bounded at depth 3 (measured:
  the only sub-depth-2 build files that exist are the two testbeds). Both
  testbeds EXCLUDED with stated reasons - demo-only, publish nothing, and both
  carry hardcoded pre-catalog plugin versions, the same shape that excludes
  arcana. `detectProject`'s todomvc/7gui branches were dead and are deleted.

- **F8 / F11 / F1 CLOSED** (zenit cb3750a, b9acefe, cdffae3).
  F8: `Endpoint.getParameterMatches` now ALWAYS defers contextual matching,
  converging with `WebSocketEndpoint` (which already deferred unconditionally -
  its spelling was reused rather than a second one invented).
  `resolveContextualDuringMatch()` is the explicit opt-in. Exactly one test
  broke, and it was pinning the OLD default; its fixture now declares the opt-in
  and a second fixture pins the new structural default, so both lanes are
  covered where only one was.
  F1 DECISION - the refusal was implemented, but NOT as the reviewer framed it
  and not as speculative scaffolding. The gap turned out to be stronger than
  "no refusal on explicit vocabularies": `SchemaVocabulary.of`,
  `RecordSource.deriveVocabulary` AND `RelationRules.define` all already
  pre-check `isFilterable()` - only the shared derivation `FieldRules.define`
  did not. So the fix MOVES the gate into the one place a Field becomes a rule
  variable, killing a triplicated policy and shipping with three existing
  consumers that already agree. The escape hatch survives: `vocabulary(...)`
  still accepts any definition and the `Compiler` lane is untouched. Honest
  limit in the AIDEV-NOTE - a hand-written `Compiler` lambda can still emit
  criteria over a secret field and no field-level check can inspect a lambda,
  so this closes only the half reached by accident.
  NOTE: that agent also edited `zenit/CLAUDE.md` in three places (one hunk per
  commit) because it documented the two contracts changed. Outside its assigned
  set; worth a glance at the end for conflicting edits.

- **Worker-namespace leak CLOSED** (zenit 3e38756) and it was FAR bigger than the
  Couchbase symptom. Every backend's once-per-JVM wipe dropped only its OWN
  namespace, and Gradle worker ids climb for the daemon's whole life, so every
  run abandoned its predecessor's band forever. Measured live: Couchbase 9 scopes
  / 59 collections, PostgreSQL 75 abandoned databases, MySQL 68, CockroachDB 68,
  MongoDB 40, Firebird 68 files / ~165 MB. Only SQLite and DuckDB were bounded.
  Couchbase merely surfaced first because its debris is the expensive kind.
  Design: ownership is encoded in the namespace name as the owning JVM's PID
  (`w7_p31337`) and the sweep collects any namespace whose owner is not a running
  process; a pre-ownership `wN` name can only come from pre-fix code, so the
  backlog is unconditionally collectable. Safe by construction - a live sibling's
  pid is alive, so there is no timing window, and pid reuse can only make the
  sweep SKIP debris.
  Both rejected alternatives were rejected on measured grounds: a per-run token
  answers "which run" not "is that run alive" (a crashed run is indistinguishable
  from a live sibling), and it would have to be minted by the build where
  `Test.systemProperties` is an `@Input`, making the test task permanently
  out-of-date and defeating zenit-dev's content-based freshness. The zenit-dev
  sweep was the right POSITION (it holds the suite lock) but the wrong home - it
  is a stdlib-only node CLI and this needs JDBC to five dialects, the Couchbase
  SDK and the Firebird image's file layout, all already in `TestDatasources`.
  Proven both directions on all 8 backends; post-run inventory shows every
  backend down to exactly one namespace, Firebird 165 MB -> 3.8 MB.

### RECURRENCE WORTH NAMING
A committed `CouchbaseDatasource.java:1056` carried `@NonNull` in an illegal
TYPE_USE position on a qualified nested type and broke EVERY zenit build in the
workspace for ~10 minutes. This is the SAME bug class as the 07-31 E2 incident
(`@NonNull ZenitHttpServer.SerialExecutor`, which must be spelled
`ZenitHttpServer.@NonNull SerialExecutor`). Twice in two days means the lesson
did not stick as a comment. Also confirms the standing rule: per-agent green
claims are not evidence, the chain build is the gate.

### Falsified sub-claims from the reviewer's "proof gaps" list
- A1, A3, A11: PEDANTRY. reconcileOutput is wired through the version-checked
  bridge with a live journey; the A3 fingerprint is content-based, not mtime,
  and a live run verified the edited string inside the repackaged jar; the A11
  artifact shape was re-verified directly (maven is not installed).
- A8: PARTIALLY REAL (repo never wiped) - folded into T2.
- A9: PARTIALLY REAL (depth-2 discovery, two unclassified testbed roots) - folded into T1.
- F5-formmethod/formnovalidate: FALSIFIED - both ride the native
  `requestSubmit(submitter)` carrier the fix restored; asserting them would test
  hawkeye, not the fix.
- F9-focus/caret: PARTIALLY REAL, proof shape only - no reorder control exists
  and neither add nor remove moves a surviving row, so there is no blur to
  survive. Cheap assertion still worth adding.
- F12-hohenheim guidance drift: FALSIFIED - every surviving dead-API mention is
  inside the archived evidence record quoting the pre-fix state AS the finding.
  Rewriting it would falsify the audit trail.
- F15-alchemy: occurrence REAL, defect FALSIFIED - that file is a Hawkejs
  (Node.js) template, not hawkeye; the rule is a Java/hawkeye typing rule and
  `"" + value` is idiomatic JS. Needs a permanent scope note so a fourth review
  does not re-raise it.

### Verdicts so far
- **F2 FALSIFIED as a security finding.** The literal statement is true
  (`createDirectGrant` contains no `AdministratorGuard` call - deliberately, per
  the B2 report) but unreachable unguarded. Only two production callers:
  `AuthGrantsBinding.applyDiff` (both its callers sit lexically inside
  `inMutationTransaction(() -> AdministratorGuard.enforce(...))`) and
  `AutoProvisioningSink` (hardcoded value=true on a 4-lines-earlier-created user,
  which cannot lower the administrator count). Everything else is test fixture.
  No CMS resource over GrantModel, no behaviours so no revision replay, no
  seeder/import/API writer. The implied fix would be a REGRESSION three ways:
  it would not even cover the delete half of a diff (that path never touches
  GrantService), it would refuse a legitimate `*` -> three-explicit-permissions
  swap on an intermediate state, and it re-walks per entry inside an
  already-guarded transaction. Residual noted for the fix agent to judge:
  GrantService is public API, so a guarded `applyGrants(...)` composing the
  primitive could be worth it IF an existing consumer can be routed through it.
- **F3 REAL.** Not a widening at write time (containment already requires the
  actor to hold the permission and for it to be delegable) but a TEMPORAL
  escalation: it relocates authority onto a source the revoking admin will not
  inspect. The class's own AIDEV-NOTE names pinning as what the self-refusal
  exists to stop, so it is a gap against the mechanism's stated invariant. Assignment rule from the operator: Fable for the harder
work, Opus for straightforward fixes, and NOT Fable for anything SSL/DNS-shaped
(so F4's hostname/route work goes to Opus regardless of difficulty).

## Testcontainer overhaul (owner-requested, beyond the 14 findings)
DONE - zenit fcac65c, e72bd3f, 724a3b9, 15ef23e.
Journal-mined 5,660 test phases since 07-10: 75 full zenit unit runs, 39 non-green.
Newly discovered unbounded growth nobody had noticed:
- CockroachDB's store lived in the container WRITABLE LAYER: 2.09 GB in 6 days on
  a 94%-full disk. Now `--store=type=mem,size=2GiB`; owned writable layers total
  9.3 MB, RAM cost 1.13 GiB of 40.
- 6 orphaned anonymous volumes, 2.3 GB, leaked by manual `docker rm` without `-v`
  during earlier incident response.
- `~/.gradle/caches/build-cache-1` at 39 GB with ZERO files past the 7-day
  default retention - the workspace churns that much weekly. Most probable cause
  of the disk-100% session. Now bounded at 2-day retention via a managed init
  script (NOTE: this writes to ~/.gradle - a machine-level config change).
Misdiagnosis is now structurally prevented at two levels: TestDatasources
latches the first failure per backend and fails later classes in microseconds
with an explicit "TEST INFRASTRUCTURE FAILURE ... NOT a code regression"
message instead of a 7-class `Mapped port` cascade; and zenit-dev gained a
preflight under the suite lock that evicts unhealthy/oversized owned containers
on objective probes, prunes dangling volumes >1h, and classifies failures
against infra signatures with a red banner + `infraSuspect` journal field.
Before: 4 of 5 full runs poisoned (19/32/9 infra failures, wall up to 651s).
After: 3 full runs - 2070/0 warm, 2069/1 with a DELIBERATE Couchbase cold start
absorbed inside 294s (the exact mode that produced 9 failures), 2061/1 warm.
Both single failures are distinct code-side async flakes, green in isolation,
and were correctly NOT claimed by the classifier - the negative control.
Surfaced not fixed: two async flakes (`WebSocketAdmissionHttpTest`,
`ChannelProtocolTest`), mutable image tags.

## Loose ends discovered during this re-review (not part of the 14 findings)
Dispatch after the main waves land; none is a release blocker.
1. Route identity has two spellings: `SiteDispatcher` builds claimKey WITH
   `kind`, `RouteClaims.keyOf` deliberately omits match type. Defence-in-depth
   only, but two disagreeing definitions of the same identity.
2. orcono: a byId guard bypasses the both-orders comparison (found by the F6 sweep).
3. spamservice: wrong-separator `byToken` guards (found by the F6 sweep).
4. Test isolation: four tests call `Models.registerInstance` in `@BeforeAll` with
   no unregister; `SitemapsTest` leaks a provider + endpoint into `Registries`.
   Benign today, would poison any future exact-list assertion.
5. `@NonNull` in an illegal TYPE_USE position on a qualified nested type has now
   broken the build TWICE in two days. Needs something durable, not a comment.
6. F15/alchemy needs a permanent scope note (Hawkejs vs hawkeye) so a fourth
   review does not re-raise it. alchemy is not a git repo - decide where it goes.
7. Twelve commits from 07-31 have subject+body collapsed onto one line. Unpushed,
   safe to rebase. NEEDS THE OWNER'S WORD - do not amend unprompted.
8. `resources/`, `references/`, `alchemy/`, `arcana/` are not git repos, so fixes
   there are uncommittable. Standing owner decision.
9. `zenit/CLAUDE.md` was edited by the CORE3 agent outside its assigned file set
   (one hunk per commit, documenting contracts it changed). Check for conflicting
   concurrent edits during final verification.

## Still open for the owner from 07-31 (unchanged, not re-litigated here)
D7 historical plaintext, D9 at-rest encryption scope, E11 CSRF-exempt Origin
check, B6 `hohenheim.sites.manage_all` (recommend deleting the roadmap line),
B8 capability-scoped keys (recommend wiring the deploy endpoint, ~10 lines).

## Endgame
1. Clean chain build with caching (hard gate).
2. Definitive zenit unit suite, forced fresh, timestamp-verified against HEAD.
3. Targeted suites for each touched repo.
4. Close-out: per-finding verdict, what changed, what was falsified, what is
   still open for the owner.
