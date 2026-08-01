# Cross-Repository Remediation Ledger - 2026-07-31

## Mission

Fix and prove every defect in this ledger across the Zenit Java web framework
ecosystem. This document is the handoff for a fresh assistant. It is deliberately
self-contained: do not rely on summaries, commit subjects, or prior-session claims
without checking the current code.

The work is not complete when the code compiles. Each defect needs a test that was
observed failing against the pre-fix implementation and passing after the fix. Record
the exact failure text. A green test with no observed counterfactual is not evidence in
this workspace.

## Workspace

- Framework repos: `/home/skerit/projects/javaweb`
- Hohenheim: `/home/skerit/projects/hohenext/hohenheim`
- Shared rules: `/home/skerit/projects/javaweb/AGENTS.md`
- Workspace guide: `/home/skerit/projects/javaweb/CLAUDE.md`
- Repo rules: each affected repository's `CLAUDE.md`
- Skills: the matching `<repository>/docs/skills/<name>/SKILL.md`
- Existing Phase 0 manifest:
  `/home/skerit/projects/hohenext/hohenheim/docs/phase0-red-team-manifest.md`
  This manifest is STALE and is not current evidence.

Dependency chain:

```text
consumer -> zenit modules -> zenit -> hawkeye -> protoblast
```

Important special cases:

- `protoblast-gradle-plugin` is built inside the Protoblast repository and bundles
  Hawkeye compiler classes.
- Hohenheim is SQLite-only at runtime.
- `common/` code must compile independently for JVM and TeaVM. Platform constants are
  intentionally folded so the opposite platform's code is tree-shaken.
- No runtime reflection in common/browser source sets.

## Source Of Truth

Use this priority when sources disagree:

1. Reproduced current behavior and current source code.
2. Repository and workspace guides.
3. The relevant skill document.
4. This ledger's evidence and references.
5. Commit subjects and old reports.

Treat every finding below as a claim until reproduced or conclusively traced. If a
finding is false, do not change code to match it. Record why it is false and the proof.

## Hard Rules

1. Use `zenit-dev` for builds, tests, template compilation, status, and logs. Never
   invoke raw Gradle.
2. Before a suite, run `pgrep -f gradle`. Wait for active suites. Never kill another
   session's build.
3. One test suite at a time machine-wide. Use `--no-fail-fast` for full suites.
4. Targeted tests are the normal verification. A full suite needs a named risk.
5. Before re-running to search output, inspect `zenit-dev journal` and use
   `zenit-dev test-log`.
6. Do not delete build directories. Use `zenit-dev build --clean` only after the
   clean/locking defect in Wave A is corrected.
7. Read every relevant file before editing it.
8. Before designing a mechanism, load the matching skill and cite the mechanism being
   reused or state why none fits.
9. The best fix is the smallest structural fix. No hacks, fallback paths, compatibility
   shims, order-dependent behavior, silent degradation, or parallel APIs.
10. Do not redesign mechanisms already judged sound. Wire or harden them instead.
11. Preserve source-set boundaries and TeaVM constraints.
12. Use `JobRunner` for cross-platform async work.
13. Use `PlatformSeam<T>` for platform seams. Do not add holders or bare static
   handlers.
14. Do not change unrelated code or revert work you did not create.
15. Counterfactuals must not publish reverted source into shared Maven local. Prefer a
   proof test written first and run against untouched code. If a temporary inverse hunk
   is unavoidable, use `--skip-deps`, restore immediately, and never publish.
16. Commit each repository separately unless files in one repository are inseparably
   shared by two fixes. Inspect status, diff, and recent log before committing.
17. The first character of every commit subject is a real Unicode gitmoji. Do not amend,
   force-push, or create new upstream branches without an explicit instruction.
18. ASCII only in code and reports except the required gitmoji commit prefix.

## Definition Of Done

For every issue:

- Re-verify the claim against current HEAD.
- State the user-facing failure.
- State the root cause and architectural owner.
- Add or strengthen a behavior test.
- Observe and record the pre-fix failure text.
- Implement the smallest correct fix.
- Run the targeted test through `zenit-dev`.
- Run the necessary downstream consumer test.
- Record limitations and remaining decisions.
- Update relevant `AIDEV-NOTE` comments and authoritative docs.

At the end:

- Run one clean dependency-chain build with caching enabled.
- Run only the broad suites justified by cross-cutting changes.
- Refresh `phase0-red-team-manifest.md` against final commit hashes.
- Ensure every repository is clean and pushed only when the operator requests it.

## Delivered Change Set Under Review

These commits were created by the prior remediation session and are the primary review
range. Inspect their full diffs, not only the files named in this ledger.

| Repository | Commits |
| --- | --- |
| protoblast | `8b66d50` |
| hawkeye | `e529b8cf`, `cd993fa4` |
| zenit | `26f1a6b`, `fc8b0bf`, `942c85a`, `eeb5bfd`, `425b3b4`, `d721844` |
| plumage | `64e8f14` |
| zenit-auth | `2d79c54`, `857fcb3` |
| zenit-cms | `98f4573` |
| zenit-forms | `b48dbd2`, `9fae394` |
| zenit-ai | `8a9c942`, `13b5248` |
| zenit-microcopy | `3709f57`, `0334a1d` |
| zenit-media | `befd2dc` |
| zenit-widget | `6451aaf` |
| zenit-flow | `f11b9da` |
| zenit-oidc | `1f005c8` |
| zenit-pages | `feb6aa5` |
| zenit-comms | `404cb45` |
| zenit-a2ui | `332f997` |
| textum | `f6b360a` |
| janeway | `fd170c9` |
| duiventil | `9fb69b7` |
| hohenheim | `13777b1`, `690ef94` |
| orcono | `176f856`, `c5e4cbe` |
| herald | `2ea85ae` |
| spamservice | `4165126` |
| proteus | `e60cc0e` |
| quirkyquarters | `b944bbd` |
| thoth | `0d57429` |

Relevant earlier mechanism commits that must be understood as baselines:

| Repository | Commits |
| --- | --- |
| zenit | `3180c23`, `77dc9e8`, `97c04ad`, `3ca4a7b`, `e50db78`, `83b4e29`, `d5332a8`, `2cea03b`, `f6f3e53` |
| zenit-auth | `18e2cc3`, `60d3526`, `6208824`, `869a71e` |
| zenit-cms | `f4c9431`, `bb826cf` |
| hawkeye | `da9be534` |

## Work Order

The order is mandatory because later proof depends on trustworthy builds and lower-level
artifacts.

1. Wave A: build and artifact integrity.
2. Wave B: immediate authorization regressions.
3. Wave C: migrations, grants, and route ownership.
4. Wave D: secret and revision boundaries.
5. Wave E: HTTP, CSP, and WebSocket behavior.
6. Wave F: compiler, template, browser lifecycle, accessibility, and docs.
7. Wave G: verify that retained fixes remain intact.
8. Final cross-repository verification and manifest refresh.

Do not run several agents that publish the same dependency chain concurrently. Parallelize
read-only analysis and non-overlapping leaf work only.

# Wave A - Build And Artifact Integrity

## A1. Stale Hawkeye generated classes survive tag removal or rename

Severity: High

Current evidence:

- `hawkeye-core/.../ProjectCompiler.java:121-174` rebuilds `tagDefiningFiles` from
  the new source.
- `ProjectCompiler.java:626-638` asks only whether the changed file defines a tag
  after the edit.
- `HawkeyeCompile.groovy:246-267` cleans on a removed template file, but a changed
  template that removes its last tag is not a removed file.
- A tag rename forces a full compile but does not clean the output directory.
- `requireCompleteOutput` checks missing expected files but not unexpected stale files.

Failure:

A removed tag interface/implementation and templates compiled against it can remain in the
generated tree and in published artifacts. Builds pass with behavior that no longer exists
in source.

Required outcome:

Generated output is an exact projection of the current template corpus. Removing or renaming
a tag/class/template must remove every stale generated file and rebuild dependants.

Proof required:

- Incremental journey: compile a tag plus a consumer, remove the tag while keeping the file,
  rerun, and assert the old files are absent and the consumer fails or recompiles correctly.
- Rename journey: assert old generated FQNs disappear.
- Prove cache restore does not reintroduce stale output.

## A2. `zenit-dev build --clean` deletes output before taking the directory lock

Severity: High

Current evidence:

- `zenit/tools/zenit-dev:1966-1985` deletes generated sources and TeaVM output.
- `zenit-dev:2048-2058` performs that deletion while holding only the repo build lock.
- The per-directory Gradle lock is acquired later inside `runGradle` at
  `zenit-dev:1074`.
- Test commands can hold the directory Gradle lock without holding the repo build lock.

Failure:

A concurrent `build --clean` can delete outputs under a live test/build, reproducing the
partial-output cache poisoning that the directory lock was intended to make impossible.

Required outcome:

Every mutation of Gradle-owned or generator-owned build output happens while holding the same
per-realpath directory exclusion lock as Gradle execution. There must be one lock mechanism,
not a second ordering convention.

Proof required:

Reproduce a live test plus concurrent clean build. Pre-fix, observe output deletion during the
first run. Post-fix, prove the clean waits before deleting anything.

## A3. Hawkeye compiler changes do not trigger Protoblast plugin repackaging

Severity: High

Current evidence:

- `protoblast/protoblast-gradle-plugin/build.gradle:30-36` bundles
  `hawkeye-compile` classes.
- `zenit/tools/zenit-dev:937-949` models Hawkeye as depending on Protoblast.
- The CI ordering builds Protoblast before Hawkeye.
- `CompilerBridge` documents the opposite republish order after a Hawkeye bridge change.

Failure:

Hawkeye compiler code can be published while the Protoblast plugin continues bundling the old
implementation. Consumers then run stale code or fail a bridge-version check unrelated to
their own source.

Required outcome:

The build graph represents the packaging edge. A Hawkeye compiler change must automatically
cause the plugin artifact to be rebuilt before any consumer is considered fresh. Avoid a
cycle by separating bootstrap/plugin packaging responsibilities if necessary; do not encode a
manual rebuild instruction as the solution.

Proof required:

Change a bridge-visible Hawkeye implementation in a fixture, run the sanctioned build path,
and prove the plugin artifact changes before a consumer compiles.

## A4. TeaVM classpaths still carry duplicate common/platform classes

Severity: High

Current evidence:

- `RuntimeClasspathGuard` covers only `Test` and `JavaExec` tasks.
- `hawkeye/hawkeye-core/build.gradle:189-190` adds browser and common outputs to TeaVM.
- `textum/build.gradle:168-179` does the same.
- `zenit-cms/build.gradle:278-292,430-432` places Hawkeye client and common artifacts
  beside one another in `teavmLibs`.
- zenit-media has the same shape.

Failure:

TeaVM classpath order can select the common/browser fold instead of the intended platform
class, reintroducing the wrong-platform constant folding the build overhaul was meant to
eliminate.

Required outcome:

No TeaVM compile classpath can contain the same FQN twice. Extend the structural variant and
duplicate guard to TeaVM tasks/configurations rather than fixing individual ordering.

Proof required:

- Add worst-order functional fixtures.
- Assert duplicate FQNs fail before TeaVM compilation.
- Inspect emitted browser code to prove server branches remain absent.

## A5. Optional dependency builds are flat, non-recursive, and non-topological

Severity: High

Current evidence:

- `zenit/tools/zenit-dev:924-969` detects optional dependencies by string scanning one
  build file and appends them in hardcoded order.
- `zenit-dev:1889-1949` builds that flat list once.
- The current order can build zenit-auth, zenit-ai, or zenit-comms before optional
  zenit-forms/zenit-cms dependencies and then record them as fresh.

Required outcome:

Dependency discovery produces a transitive DAG and builds stale nodes in topological order.
Freshness fingerprints must include each dependency's effective transitive inputs.

Proof required:

A functional fixture in which A optionally depends on B and B optionally depends on C. Change
C and prove `zenit-dev build` publishes C, B, then A exactly once.

## A6. Spamservice deployable contains divergent duplicate entries

Severity: High

Current evidence:

- `spamservice/build.gradle:161-185` uses `DuplicatesStrategy.INCLUDE` and disables
  duplicate-entry failure.
- The inspected server artifact contained 36 duplicate paths, including differing class
  bytes and repeated microcopy/service resources.
- The post-build check verifies only one Protoblast class.

Required outcome:

The deployable JAR contains exactly one byte-identical copy of each class and a deliberately
merged copy of mergeable resources. Service and microcopy merging must use their canonical
mechanisms. Any divergent duplicate class fails the build.

Proof required:

Inspect the resulting JAR as part of the build and assert zero duplicate class paths and the
expected merged resource contents.

## A7. Runtime duplicate guard ignores non-`be.elevenways` application packages

Severity: Medium

`RuntimeClasspathGuard.CLASS_PREFIX` is fixed to `be/elevenways/`. Generated apps may use
`com.example` or any other package. Make the guard package-independent or derive the package
set from classpath entries while excluding known harmless metadata classes.

Proof required: a plugin functional fixture under `com.example` with duplicate FQNs must fail.

## A8. Protoblast plugin functional tests depend on an obsolete Maven artifact

Severity: Medium

Tests request `be.elevenways:protoblast:0.1.0-SNAPSHOT:server`, while the current publication
is `protoblast-server`. They pass only because an old artifact exists in the developer's
Maven cache.

References:

- `ProtoblastPluginFunctionalTest.java:70,134`
- `ProtoblastApplicationDistFunctionalTest.java:47`

Required outcome: tests pass against an isolated empty Maven repository populated only by
the current build.

## A9. Workspace CI omits converted deployables

Severity: Medium

`zenit-dev` CI levels omit Herald, Spamservice, and Thoth despite build/publication changes.
Add all converted repositories at correct topological levels. Pin the list with a test that
compares known workspace repos to deliberate inclusions/exclusions.

## A10. Stylesheet registration generation retains stale classes

Severity: Low

`zenit/zenit-gradle-plugin/.../GenerateStylesheetRegistrationTask.groovy` does not clean or
reconcile its managed output. A namespace rename leaves the old generated class. Apply the
same exact-output doctrine as Hawkeye generators and add a rename test.

## A11. Maven consumers can still receive `-common` at runtime

Severity: Low/Design

Gradle module metadata correctly exposes common as compile-time-only, but generated POM scope
does not express the no-runtime-variant contract as strongly. Investigate Maven consumer
behavior and either publish a safe POM shape or fail unsupported Maven runtime use loudly.

# Wave B - Authorization And Admin Regressions

## B1. User grant editor allows self-escalation to wildcard admin

Severity: High, release blocker

Current evidence:

- `AuthUsersResource.java:51-55` includes `GrantsEditField` in the normal user form.
- The resource uses `auth.users.edit` for all writes at `:82-89`.
- `AuthGrantsBinding.applyDiff` writes arbitrary permission strings without checking the
  actor's authority at `AuthGrantsBinding.java:68-80`.
- `PermissionResolver.java:253-269` recognizes `*` as the wildcard permission.

Failure:

An interactive principal with `auth.admin.access` and `auth.users.edit`, but without wildcard
authority, can edit themselves and add `*`.

Required outcome:

Grant administration has an explicit permission boundary separate from profile editing. A
principal must never grant an allow broader than their own mintable/delegable authority, remove
a denial they are not authorized to override, or alter their own authority through a weaker
permission. Use one shared grant-management policy for user and role resources.

Do not solve this by hiding the editor while leaving the submit path open.

Proof required:

- Limited admin edits email/display name successfully.
- The same principal cannot add `*`, a permission they do not hold, or a forbidden group.
- A properly authorized grant administrator can perform the operation.
- Direct POSTs are refused identically to rendered UI.

## B2. Deleting the final administrator permanently locks out the installation

Severity: High, release blocker

Current evidence:

- `AuthUsersResource.deleteRow` deletes without checking effective administrators.
- Setup remains marked complete independently of user existence.
- `/setup` refuses while the marker remains.

Required outcome:

Every transaction that deletes, disables, or removes the effective admin authority of a user
must preserve at least one enabled login-capable administrator, or perform an explicit recovery
transition designed for this purpose. The invariant must cover direct grants, group membership,
role edits, wildcard removal, disable actions, and user deletion.

This is a cross-write invariant, not a button-specific confirmation.

Proof required:

Concurrent attempts to remove the last two administrators must leave at least one usable admin.
Test deletion, disable, grant diff, group membership, and role deletion.

## B3. Role slug changes strand and later reattach memberships

Severity: Medium

`AuthRolesResource` edits `slug` directly but does not migrate `group.<oldSlug>` membership
grants. Deletion removes only the current slug. Make role identity immutable or transactionally
migrate every membership reference and reject collisions. Reusing the old slug must never adopt
stale memberships.

References: `AuthRolesResource.java:147-177,180-197`.

## B4. Record capability rules are silent last-wins

Severity: Medium

`RecordGrantCapabilityChecker.declareRules` unconditionally replaces a model's rules. Match
`KnownCapabilities`: equal declaration is idempotent, conflicting declaration is loud, and
deliberate replacement uses an explicit override method.

Reference: `RecordGrantCapabilityChecker.java:37-50`.

## B5. Record grants can be planted for nonexistent records

Severity: Medium

`RecordGrants.grant` validates the declared model and subject but not the target row. A buggy
caller can grant the next generated ID and activate it when the record is later created.

Required outcome:

The public grant API validates target existence through a model-aware declaration or accepts an
already-resolved record/proof. Preserve support for non-integer keys and access-hidden records.
Do not use a public/scoped RecordSource query to decide physical existence.

## B6. Hohenheim type-level manage capability remains incomplete

Severity: Medium/Architecture

The roadmap names `hohenheim.sites.manage_all`, but Hohenheim declares only gate/admin rules.
`managedSiteIds` begins from record-grant candidates, so adding a type-level allow would still
enumerate no records.

Required outcome:

Either complete the planned type-level consumer, including enumeration, or explicitly remove it
from the roadmap and document why record-only grants are the final contract. Do not add an
unwired rule.

## B7. Generic permissions editor ignores read-only state

Severity: Low

`PermissionsEditState.readonly` is not applied by `permissions-edit.hwk`. Render a genuinely
read-only representation, omit the mutation marker when appropriate, and keep server enforcement
authoritative.

## B8. Capability-scoped Hohenheim keys have no real mutation consumer

Severity: Low/Architecture

`manage` is marked delegable and described as view/edit/operate, but the tested API-key path is a
direct checker call and current mutation endpoints still require global admin permission or a
session-only terminal cookie. Add one legitimate capability-gated machine operation or narrow the
documented capability and delegability claim. Do not add a token transport only to satisfy a test.

## B9. Auth resources need transaction fault-injection coverage

Severity: Medium test gap

The user/role save plus grant diff is intended to be one transaction. Add a controlled failure
after the row save but during grant persistence and prove both halves roll back on SQLite and
PostgreSQL.

# Wave C - Migrations, Grants, And Route Ownership

## C1. Applied original M007 never runs the new orphan purge

Severity: High, release blocker

Current evidence:

- Revised M007 contains the purge at `M007_HardenGrantSchemas.java:76-80`.
- `MigrationRunner.java:282-287` skips an applied version.
- Checksum supersession at `:351-365` repairs only history.
- M008 reconciles only the index.
- `StarfleetUpgradeTest` compares schema but seeds no orphan grant data.

Required outcome:

Put the data remediation in a new migration version that every upgraded install executes. Keep a
periodic sweep as defense-in-depth, not as the upgrade mechanism. Fresh and upgraded installs
must converge on identical schema AND data invariants.

Proof required:

Seed an original-M007 history row plus an orphan grant, run the current chain in integrity `fail`
mode, and assert the orphan is gone.

## C2. Interrupted original MySQL M007 cannot recover

Severity: High, release blocker

Original MySQL M007 committed `granted_by` and `expires_at`, then failed on the overlong index.
Revised M007 adds those columns without `ifNotExists`, so retry fails before M008.

Required outcome:

The chain must recover from every prefix of the original non-transactional migration. Model the
actual original operation order and test each committed prefix against real MySQL. Do not weaken
checksums or globally make DDL ignore errors.

## C3. Same-value deny can lose to concurrent revoke plus allow

Severity: High

At `RecordGrants.java:238-245`, an already-equal deny returns without a write. Interleaving:

```text
A reads existing deny
B revokes it
B inserts allow
A returns stale deny without touching storage
```

Required outcome:

A deny request that returns success must establish a stored deny after every concurrent
interleaving. Preserve bounded progress and avoid whole-row stale writes.

Proof required:

A deterministic barrier test for exactly this interleaving on SQLite and PostgreSQL, plus the
widest backend-specific proof feasible for Couchbase.

## C4. Couchbase cannot currently provide the claimed sticky-deny guarantee

Severity: High

`CouchbaseDatasource.updateAll` uses N1QL update without a CAS retry and documents lost-update or
conflict behavior. Either implement a Couchbase-safe conditional CAS/retry operation or narrow the
support contract explicitly. The preferred outcome is the portable guarantee promised by
zenit-auth.

Do not claim all-eight correctness from SQL-only race tests.

## C5. Grant-store outages latch cleanup off permanently

Severity: Medium

`RecordGrantStore` caches `probedAvailable=false` forever for the datasource instance. Its log says
cleanup is disabled only until the store answers again, which never happens without restart/test
reset.

Required outcome:

Use a bounded retry/cooldown or an availability state that re-probes without taxing every delete.
Real cleanup failures must still surface when the store is healthy.

## C6. Orphan sweep misses custom soft deletes and skipped subject cleanup

Severity: High

The sweep treats any physically present target row as live. Hohenheim soft-deletes sites by setting
`deleted_at` without attaching `SoftDeleteBehaviour`, so old/degraded grants survive and revive on
restore. Subject grants skipped during an outage are outside the target-record sweep entirely.

Required outcome:

Grantable-model declarations must define physical liveness, including soft-delete semantics. Add a
separate subject-orphan sweep. The upgrade migration must use the same definition.

## C7. `insertIfAbsent` conflates primary-key and secondary unique conflicts

Severity: Medium

SQL `ON CONFLICT DO NOTHING`, fallback duplicate handling, and Mongo error 11000 all return false
for any unique conflict. The method contract says false means another writer won the same primary
key.

Required outcome:

Return false only for the intended primary-key/declared conflict target. Surface every unrelated
constraint failure as `DuplicateKeyException`. Add a secondary-unique counterexample on all
backends.

## C8. Helper index renames changed checksums of shipped migrations

Severity: Medium

Changes to `createTranslationsTableFor` and `createSchemaTableFor` alter structural checksums of
consumer migrations. Inventory every affected shipped migration and declare exact superseded
checksums plus reconcile migrations where physical names differ. Test strict integrity mode.

Known consumers include zenit-microcopy, zenit-media, Spamservice, and QuirkyQuarters.

## C9. M008 leaves PostgreSQL's truncated legacy index

Severity: Low

JDBC metadata reports the actual 63-byte truncated name, so exact-name guarded drop skips the
65-character requested name. Reconcile both known physical spellings idempotently.

## C10. Index-name guard checks characters, not bytes

Severity: Low

`MAX_INDEX_NAME_LENGTH` is documented as a byte limit, while enforcement uses `String.length()`.
Check encoded identifier bytes under the database identifier encoding and add non-ASCII tests.

## C11. Hohenheim M045 is not covered by the checksum golden

Severity: Low

The golden package filter excludes the server-migration package containing M045. Include every
discovered Hohenheim migration. Also decide how data-operation bodies are versioned: a description-
only checksum cannot detect changed remediation logic. Do not serialize lambdas; use an explicit
stable operation/version identity.

## C12. MySQL duplicate-key attribution mistakes index names for columns

Severity: Low

Explicit/composite index names are reported after `for key`, but `mysqlColumn` treats the suffix as
a field. Preserve typed duplicate classification while returning no field unless a real column can
be identified.

## C13. SQLite thread-local transactions share one JDBC connection

Severity: High, release blocker for Hohenheim concurrency claims

`SqliteDatasource` returns proxies around one `realConnection`, but transaction ownership is a
ThreadLocal. Two threads can commit, rollback, or reset autoCommit under one another.

Required outcome:

Choose one coherent model:

- Serialize transaction ownership for a shared in-memory connection, including all non-transaction
  operations that touch it, or
- Use independent connections to a correctly configured shared in-memory/file database.

The model must support nested transactions in one thread and forbid cross-thread interference.

Proof required:

Barrier tests where two threads overlap transactions and one rolls back. Assert the other cannot
commit it, reset it, or expose partial child writes.

## C14. Route claim uniqueness does not cover overlapping listener sets

Severity: High

`RouteClaims` stores listener restrictions literally, but routing considers sets conflicting when
they overlap. Simultaneous all-interface and single-address claims have different unique keys.

Required outcome:

Represent the actual conflict relation in storage. Likely directions include normalized claim rows
per concrete listener scope or a transactionally serialized route-claim registry. Do not claim an
invariant from an advisory pre-scan.

Proof required:

Concurrent barriers for identical listeners, all-vs-one overlap, wildcard/exact hostname shadowing,
paths, disabled sites, soft-deleted sites, restore, and failed writes.

# Wave D - Secrets, Redaction, And Revision Integrity

## D1. Same-name noncanonical fields bypass RecordSource secret gates

Severity: High, release blocker

Builder methods accept arbitrary `Field` instances. Validation checks that object's flags, while
row lookup resolves by field name. A new non-secret field named like a secret schema field can
project or query the plaintext.

References:

- `RecordSource.java:879-927,1150-1187,1229-1239`
- `Field.java:132-139`

Required outcome:

Every field accepted by a model-bound RecordSource facet must resolve to and use the model schema's
canonical field instance. Reject unknown, foreign, and same-name impostor fields at build time.
Apply this to projection, search, sort, timestamp, bucket, vocabulary, and title derivation.

Proof required:

An anonymous source using a forged field named like a secret column must fail construction before
any request is served.

## D2. FormSecrets misses structural and localized secret shapes

Severity: High

Missing cases:

- Secret parent `Nested`/SchemaField entries.
- ListField whose item field is secret/encrypted.
- `Records` subforms containing secret fields.
- Localized secret maps.
- Dynamic sub-schema switches across those forms.

Required outcome:

Derive masking/restoration from the same structural secret rule used by `FieldRedaction`, adapted to
form-entry transport shapes. Never flatten localized or repeatable structures. New typed values may
survive a rerender; stored values must never echo.

Proof required:

Behavior journeys for create, edit, validation failure, blank keep, explicit clear, rename, locale,
list item, records row, dynamic type switch, and raw browser HTML.

## D3. Restoring a normal revision can un-soft-delete a record

Severity: High

Snapshots of loaded live rows can contain `deleted_at = null`. Restore reapplies it. The current
test for staying trashed forges a snapshot without `deleted_at`, so it does not test real history.

Required outcome:

Restore preserves lifecycle fields owned by behaviors unless a dedicated lifecycle operation says
otherwise. A revision restore is not an undelete.

Proof required:

Create a normal revision from a loaded row, soft-delete it, restore that real revision, and assert
it remains trashed.

## D4. Restore is not atomically bound to `recordId`

Severity: High

The existence lookup uses the snapshot's PK, not the requested `recordId`, and runs before the save
transaction. Missing/mismatched snapshot keys and a delete between check and save can target another
row or trigger update-to-insert resurrection.

Required outcome:

- Treat the method's `recordId` as authoritative.
- Reject a mismatched snapshot primary key as corrupt history.
- Perform existence check and guarded update in one transaction.
- The restore path must never use save's INSERT fallback.

Proof required:

Missing PK, mismatched PK, concurrent hard delete, soft delete, optimistic version change, and
localized-secret cases.

## D5. DataItem exposes secret primary-key and timestamp facets

Severity: Medium

`item()` always emits the primary key and can use it as title fallback. `timestamp(field)` accepts
secret/encrypted fields. Decide and enforce whether secret PKs are legal; otherwise reject them at
model registration. Canonicalize and redact timestamp fields like every other RecordSource facet.

## D6. SecretDisclosures TTL does not bound memory residency

Severity: Low/Decision

Touch-driven pruning means an expired plaintext can remain strongly reachable forever after the last
stash/claim. If the contract says TTL bounds residency, implement cleanup through the existing task
runtime or a narrowly owned server scheduler. If touch-driven expiry is accepted, rename/document the
contract so it does not claim a residency bound.

Do not put an unmanaged timer in common code.

## D7. Historical plaintext remains in revisions and activity rows

Severity: Owner decision, security rollout blocker

Write-time redaction is forward-only. Existing `zenit_revisions` and `zenit_activity` rows can still
contain historical site API keys, dyndns tokens, webhook secrets, and environment values. Backups and
copied databases retain them.

Do not improvise deletion or rewriting. Present the owner with explicit policies:

- Purge affected history entirely.
- Rewrite values in place while preserving non-secret history.
- Rotate all exposed credentials plus rewrite/purge history.
- Declare an accepted retention risk with a runbook.

The chosen remediation must cover backups and credential rotation, not only the live database.

## D8. Table-stored Records secret behavior remains explicitly incomplete

Severity: Medium/Architecture

The secret audit identified Records subforms as outside FormSecrets coverage. Add a concrete consumer
journey and one shared mechanism before claiming the form layer handles every secret field shape.

## D9. At-rest encryption remains incomplete

Severity: Owner decision

Current encryption protects specifically declared fields. Historical and JSON-nested secret values
are not generally encrypted. Keep this separate from redaction: redaction controls derived surfaces,
encryption protects copied storage. Define scope before implementation.

# Wave E - HTTP, CSP, And WebSockets

## E1. Locale prefixes bypass AuthRegistry baselines and public prefixes

Severity: High, release blocker

`HttpConduit` routes against stripped `routeUri`, but zenit-auth reads `conduit.getPath()`. A route
can resolve as `/api/private` while baseline matching sees `/nl/api/private`.

References:

- `AuthorizationMiddleware.java:29-50`
- `AuthRegistry.java:49-68`
- `HttpConduit.java:478-503`

Required outcome:

Authorization baselines and public-prefix ownership use the canonical route path. Logging/assets may
keep the request path. Add a multi-locale test for login-only and permission baselines plus public
prefixes, including unknown prefixes.

## E2. Callback-lane overflow discards WebSocket teardown

Severity: High, release blocker

`SerialExecutor` permanently rejects work after overflow. The overflow callback invokes teardown,
whose CAS marks the connection released before enqueueing `handler.onClose` on that rejected lane.

Required outcome:

Teardown must be exactly once and always, even when the normal callback lane is full, overflowed,
stalled, or throwing. Preserve callback ordering for normal closes but use a teardown backstop that
cannot be rejected by the failed resource it is cleaning.

Proof required:

Fill and overflow the real callback lane with a handler whose close increments a counter and releases
a retained resource. Assert one close after settle, no matter which transport event follows.

## E3. In-band dev-tunnel authentication never revalidates

Severity: Medium/High

The endpoint is anonymous at handshake, then authenticates a token in `handleRegister`. Default
identity revalidation never starts, and the handler has no authorization revalidation.

Required outcome:

After registration, periodically re-resolve the site/token/namespace authorization. Rotation,
disable, delete, and ownership changes must close the tunnel. Use a generic handler-declared
revalidation seam if more than one in-band-auth consumer can need it; otherwise keep wiring thin.

## E4. Contextual route parameters execute before authorization

Severity: Medium

ModelParam can query during matching before middleware authentication. WebSocket matching happens
before even method, Origin, and admission checks.

Required outcome:

Separate cheap structural matching from contextual resolution, then perform expensive resolution
after admission and the appropriate authentication gate. Preserve 404 semantics without creating a
record-existence oracle.

## E5. Bad-Origin WebSocket requests bypass admission while doing synchronous diagnostics

Severity: Medium

The current order preserves a victim's handshake budget but creates an unlimited logging/security-
event path. Design a cheap independent rejection limiter or make diagnostic sinks bounded and async.
Do not move Origin after the expensive authenticator.

## E6. `claimingRoutesOf` is incomplete and overbroad

Severity: Medium

It ignores `Endpoint.getLocalizedRoutes()` and claims an entire subtree from the leading static
prefix. `/login` therefore claims unrelated `/login/*` host routes.

Required outcome:

Derive claims from the exact route matcher or a route-owned predicate, including locale variants,
without claiming dynamic roots. Collision diagnostics must remain loud.

## E7. Terminal CSP predicate is broader than the real subpage

Severity: Low

Any registered panel path ending `/page/processes` gets the terminal policy, including 404s and
future unrelated resources. Bind the variant to the actual registered site-processes subpage or
resolved endpoint/peer rather than suffix shape alone.

## E8. Non-positive global revalidation interval silently opts out

Severity: Low

Validate the setting as positive or require the explicit `.neverRevalidate()` declaration for opt-
out. Add tests for zero, negative, endpoint override, and anonymous sockets.

## E9. Ghostty script failure can hang terminal initialization

Severity: Low

If the script tag already failed or loaded without the expected global, the bridge registers a late
load listener and leaves `_loading` forever. Add `error` handling and completed-tag/global checks.
Failure must be loud and must settle queued callbacks exactly once.

## E10. Hohenheim starts HTTP before installing handlers and panels

Severity: Medium

Production calls `ServerZenitRuntime.main()` before `HohenheimHandlers.init()` and panel construction;
tests wire in the safe opposite order.

Required outcome:

Move all host wiring into MODULES or before `init().join()`. No endpoint may expose a placeholder
null handler while HTTP is accepting requests.

## E11. `NON_INTERACTIVE_ONLY` CSRF exemption also skips Origin

Severity: Owner decision/security design

`CsrfMiddleware.check` returns immediately for an exempt non-interactive principal before the Origin
check. Keeping Origin would add defense for cookie-bearing mistakes but could reject legitimate
cross-origin API clients.

Present the concrete current consumers and choose deliberately. If kept, document that every exempt
credential must be non-browser-ambient. If changed, add cross-origin API compatibility tests.

# Wave F - Compiler, Templates, Lifecycle, Accessibility, And Docs

## F1. List directives erase independently authored disabled state

Severity: Medium

`ListDirectives.applyRenderState` removes `disabled` whenever the list boundary allows the action.
A control disabled by business policy becomes enabled.

Required outcome:

Directive-owned disabled state must compose with authored state. Use a distinct marker/property or
remember only the state the directive itself applied. Add reactive tests where business disabled and
list-boundary disabled change independently.

## F2. `attr:on*` bypasses the inline-handler compiler error

Severity: Medium, CSP correctness

The advisor skips namespaced attributes while `attr:` is the explicit literal-attribute lane.
`attr:onclick="..."` therefore emits raw JavaScript.

Required outcome:

Judge the effective DOM attribute name. Plain and `attr:` spellings of every DOM event handler must
fail with `inline-event-attribute`. Keep custom properties such as `once` legal.

## F3. Directive synthetic methods have incorrect source mappings

Severity: Low

Event/reactive directive methods are emitted without a source marker, so exceptions map to the prior
method. Add a source-map test that throws from a directive and asserts the authored template line.

## F4. Retired attributes are case-sensitive despite HTML semantics

Severity: Low

`DATA-CONFIRM` bypasses a lowercase registry entry but reaches the same HTML attribute. Normalize HTML
attribute retirement keys case-insensitively while preserving case rules for non-HTML namespaces if
needed.

## F5. Confirmation replay loses submitter semantics and can leave a sticky replay marker

Severity: Medium latent

`CmsConfirmFunctions` does not preserve `DominoSubmitEvent.getSubmitter()` through replay. Submitter
name/value and form overrides can be lost. The replay marker is set before `requestSubmit`; constraint
validation can decline the submission and leave the next real submit unconfirmed.

Required outcome:

Replay with the original submitter and consume/clear the marker only around a submission that actually
re-enters the handler. Test two submitters, `formaction`, name/value, invalid required controls, cancel,
and subsequent genuine submit.

## F6. `pl-terminal` does not dispose on unmount

Severity: High

The component calls `Terminal.init` from `@mount` but never calls `Terminal.dispose`. Register cleanup
next to initialization using `Cleanup.on`, keyed if mount can re-run in one connected lifetime.

Proof required:

Soft-navigate away from a real terminal, then assert the browser WebSocket closes, the server handler
detaches, attachments are removed, and a remount creates exactly one new terminal.

## F7. Orcono editor lifecycle leaks across soft navigation

Severity: High

`EditorInitializer` stores per-page state statically, discards update/subscription disposers, never
destroys Textum, and binds the property-panel ref only once.

Required outcome:

Each mounted editor owns an instance-scoped lifecycle object attached to its host. Register every
disposer through the existing cleanup mechanism. Unmount must destroy editor, toolbar, synced-ref
subscription, update listeners, focus listeners, and panel binding.

Proof required:

Soft-navigate page A -> page B -> issue C and edit mentions on each. Detached panels/editors must never
change, and listener counts must remain constant across repeated navigation.

## F8. Settings reset controls have ambiguous accessible names

Severity: Medium accessibility

Every checkbox is named only `Reset` or `Reset staged`. Include the setting label/path in the
accessible name while keeping visible microcopy concise. Add an accessibility-tree assertion for two
settings with distinct names.

## F9. Generic permissions editor visually resets rows on add

Severity: Low/UX

The `pl-permissions-editor` row rendering is non-keyed, so unsynced select/editor state can visually
reset when another row is added. Use stable row identity and a keyed `each`; prove focus and uncommitted
values survive add/remove/reorder.

## F10. Proteus duplicates the generic permissions editor

Severity: Low/Consolidation

Proteus still ships its own editor despite the new generic permissions state/partial. Migrate it only
after confirming all Proteus-specific behavior is expressible through the generic extra-column seam.
Delete the duplicate rather than leave two editable grant UIs.

## F11. PathProbe remains a hand-written platform holder

Severity: Low architecture

Convert `PathProbe.HOLDER` to `PlatformSeam.withDefault()`, keep the browser UNKNOWN default, and retain
self-installing JVM behavior. Delete the old holder API.

## F12. Authoritative documentation still teaches deleted APIs

Severity: Medium process

Known stale references:

- `zenit-cms/CLAUDE.md:228,252` teaches `CmsConfirm.interceptSubmit`.
- `zenit-flow/CLAUDE.md:110` recommends rejected `"" + value` coercion.
- `resources/shortlinker-port/03-port-precedent.md` contains deleted bridge installs,
  final `Panel.peers()` override, and forbidden script/onload examples.
- `zenit-cms/src/common/templates/pages/settings.hwk:10` describes the reset control
  using its old shape.

Update examples to compile under the current mechanisms. Add cheap documentation/example compile
tests where feasible.

## F13. Action state remains unconsumed in production templates

Severity: Medium architecture

Workspace search finds `Action.create/run/provide` only in browser tests. The capability map describes
it as a shipped, wired capability, but no production surface uses it.

Required outcome:

Either wire one real consumer whose async state was previously hand-rolled, or move the mechanism back
behind experimental/test scope. Do not add a fake consumer merely to satisfy this ledger.

## F14. RecordSource explicit registration drops CMS-derived facets silently

Severity: Design concern

An explicit source replacing `CmsRecordSources.registerDefault` does not inherit the resource's access
predicate or inline-create provider. This may be intended replacement semantics, but it is easy to
drop security or functionality accidentally.

Decide whether replacement must be complete-and-explicit with a loud diagnostic, or whether selected
facets compose. Do not silently merge authorization from two owners.

## F15. Remaining string-concat coercion

Severity: Low

Known template occurrences remain in Hawkeye browser fixtures and legacy Alchemy. Judge each one:
keep only deliberate tests of that syntax; replace production coercion with `String.valueOf` or typed
conversion. Do not edit external/legacy trees without confirming they are in scope.

# Wave G - Verified Fixes To Preserve

The following fixes were independently reviewed as substantially correct. Do not redesign them while
fixing adjacent issues. Keep or strengthen their tests.

## G1. Confirmation migration

- Four Hohenheim destructive forms use the directive path.
- Real browser journeys cover cancel and confirm behavior.
- Lowercase `data-confirm` is retired at compile time.
- The skill document now teaches directives rather than dead attributes.

Preserve the typed `ConfirmRequest` channel and directive call-site simplicity.

## G2. Directive method suites

The declaration-role model is sound:

- `void` return: render method, once per element render.
- `DominoNode` return: markup contribution, replayed with content render.
- `event=...` plus trailing `DominoEvent`: event method.

Do not reintroduce `emitsMarkup`, `applyAtRender`, or lifecycle-lane booleans.

## G3. CSP locale claim

`ScopedCspMiddleware` correctly claims against `getRoutePath()` and the existing multi-locale HTTP
test reaches `/en/...`. The separate AuthRegistry path bug in E1 must be fixed without moving assets
or logs onto route paths.

## G4. MySQL INSERT IGNORE removal

MySQL now uses plain INSERT plus typed duplicate handling, so truncation/NOT NULL/conversion errors
surface. Do not replace it with `ON DUPLICATE KEY UPDATE` without resolving Connector/J affected-row
semantics.

## G5. KnownCapabilities collision policy

`KnownCapabilities.register` is idempotent-or-loud and `override` is explicit. Apply this policy to
rules registries; do not weaken the existing implementation.

## G6. Interactive-only CMS routes

All 17 generated CMS routes require interactive login. Permission, panel, peer, access-function, and
operation checks remain separately enforced. Preserve this third credential-kind axis.

## G7. Legacy auth route removal

The hand-built `/admin/users` and `/admin/roles` routes are gone, and the original route shadow/CSP
overlap is closed. Fix the generated resources rather than restoring parallel legacy pages.

## G8. Ghostty asset pin and CSP split

- Ghostty version and SHA-256 are pinned.
- `STRICT_ADMIN` is terminal-free.
- `STRICT_ADMIN_TERMINAL` contains only the terminal concessions.
- The runtime script-injection fallback is removed.

Fix loading/unmount behavior without broadening the base policy.

## G9. WebSocket FIN/RST/server-close behavior

Real-socket tests prove FIN and RST reach teardown, and unacknowledged server-initiated close now
releases the handler. Fix overflow as an additional path; do not discard these tests.

## G10. Other verified closures

- Source-existence oracle flattened.
- `Principal.coversCapability` implemented for API keys.
- Dead public Resource/RecordSource overloads removed or made private.
- `Violation.toString()` no longer prints values into log stringification.
- Dyndns digest lookup indexed; final comparison remains constant-time.
- `AccessContext.of(Conduit)` is the canonical derivation and mismatches fail loudly.
- `EditContext.of(AccessContext.anonymous())` no longer NPEs.
- OIDC form_post inline handler moved to a served asset.
- Production templates no longer carry body `onload="main()"`.
- Compiler terminality analysis moved from textual line shape to structural emission state.
- Settings reset now uses a real form-associated `pl-checkbox` with visible focus.
- Secret StringMap new-key rerender behavior is corrected for current consumers.

# Final Verification Matrix

The final assistant must extend this table with exact commands, dates, commit hashes, pre-fix failure
text, and final results.

| Boundary | Minimum proof |
| --- | --- |
| Build locks/cache | Concurrent clean/test race, cache enabled |
| Platform folding | JVM bytecode and TeaVM bundle inspection |
| Duplicate FQNs | Test/JavaExec/TeaVM worst-order fixtures |
| Hawkeye output | Remove/rename tag incremental and cache restore |
| Auth grant editor | Limited admin cannot self-escalate; authorized admin can edit |
| Last administrator | Delete/disable/grant/role concurrent invariants |
| M007 upgrade | Original applied history with orphan data; interrupted MySQL prefixes |
| Sticky deny | Same-value deny vs revoke+allow barriers |
| Backend support | All eight datasource behavior where contract says all eight |
| SQLite transactions | Cross-thread commit/rollback isolation |
| Route claims | Listener-overlap concurrent writes |
| Revision restore | Real soft-delete snapshot and concurrent hard delete |
| RecordSource secrets | Same-name forged fields and every facet |
| FormSecrets | Nested/list/records/localized/dynamic journeys |
| Locale auth | Multiple locales, baseline and public-prefix cases |
| WebSocket teardown | Queue overflow plus settle and exactly-once assertion |
| Dev tunnel | Token rotation/site disable closes live tunnel |
| Terminal lifecycle | Soft-nav closes browser and server resources |
| Orcono lifecycle | Repeated soft-nav with stable listener/resource counts |
| Accessibility | Distinct reset names in accessibility tree |
| Documentation | Examples compile or are mechanically validated |

## Previously Observed Passing Baselines

These are historical context, not substitutes for final verification:

- Zenit migration/datasource targeted tests: 84 passes.
- Zenit secret/RecordSource targeted tests: 56 passes.
- zenit-auth migration/grant targeted tests: 40 passes.
- Auth CMS resource integration: 6 passes.
- Hawkeye directive browser tests: 7 passes.
- Zenit directive tests: 9 passes.
- zenit-cms confirmation/CSP journeys: 6 passes.
- Hohenheim route/migration tests: 10 passes.
- Hohenheim capability/admin journeys: 13 passes.
- Hohenheim secret journeys: 15 passes.
- Leaf UI/platform targeted tests: 73 passes.

Many defects in this ledger survived those green runs because their interleaving, upgrade state,
unmount path, canonical-field violation, or artifact shape was not asserted.

## Reporting Format

For every completed issue report:

```text
Issue:
User-facing behavior changed:
Architectural responsibility changed:
Files changed:
Tests added or changed:
Observed pre-fix failure:
Verification command and result:
Backend/browser coverage:
Known limitations or decisions:
Commit and push state:
```

Findings and blockers come first. Do not report vague outcomes such as "fixed", "wired", or
"cleaned up" without stating the concrete behavior and mechanism.

## Stop Conditions

Stop and ask the operator only for a genuine decision that code cannot answer, including:

- Historical plaintext retention/purge/rotation policy.
- Whether CSRF-exempt non-interactive requests retain the Origin check.
- The accepted product contract for Hohenheim type-level manage authority.
- A storage design fork for route listener-overlap claims if more than one structurally correct
  model remains after investigation.
- At-rest encryption scope and backup remediation.

Do not stop because the work is large, tests take time, or a green checkpoint exists. Continue until
the requested wave is complete and proven.
