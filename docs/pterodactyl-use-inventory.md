# The Pterodactyl-use inventory

Closed 2026-08-08. This is the checked-in inventory the Phase 6 gate
(`instance-tier-plan.md:3559`) and the "Pterodactyl-class game panel" definition
(`instance-tier-plan.md:82-91`) both demand: every capability we use a game
panel for, with a DECISION and its evidence.

**Closed does not mean implemented.** Each row is one of:

- **IMPLEMENTED** -- the mechanism exists AND a test asserts STATE about it. The
  row names the implementing class and the test, both at file:line.
- **CLAIMED** -- the code exists and looks right, but nothing asserts state. A
  docblock is not a test. These are listed as CLAIMED on purpose: this repo's
  dominant defect shape is "a step does less than it claims and reports
  success", and its documentary twin is a doc asserting a property the code
  lacks.
- **PARTIAL** -- precisely what works and precisely what does not.
- **OPEN** -- genuinely missing and genuinely wanted, with the slice that owns it.
- **REJECTED** -- a decision, argued from evidence about the shape of THIS
  product. A rejection that cannot be argued is an OPEN row wearing a disguise.

### How this document was built

Every row was derived from CODE, not from the plan's own STATUS notes -- the
plan's reading rules say a LANDED claim is a claim to re-verify. Where a row
cites a test, the test was read for whether it asserts STATE or only a status
code, and for whether it `assumeTrue`s its way to a green skip.

**The `[live]` mark is the most important thing in this document.** 67 of the
243 browserTest classes contain an `assumeTrue`/`Assumptions.assume*` gate, most
of them on a Docker socket, an Incus endpoint, a pulled image or a network
namespace. **A skipped test is a green test.** Any row whose only evidence is
such a test is marked `[live]`, and that mark means the capability has NO
coverage on a machine without that daemon while the suite still reports green.

Verification legend: **[code]** = source read at the cited file:line;
**[test]** = hermetic test, asserts state, no assumption gate; **[live]** =
the only proof is a daemon-gated test that can skip green.

### The counts

Twenty-two numbered items, plus the Phase 6 gate's localization clause as a
twenty-third row:

| verdict | count | items |
| --- | --- | --- |
| IMPLEMENTED | 9 | 2, 4, 6, 7, 8, 11, 12, 13, 14 |
| PARTIAL | 5 | 1, 3, 5, 9, 10 |
| REJECTED | 8 | 15, 16, 17, 18, 19, 20, 21, 22 |
| OPEN | 0 | (was 1: the localization clause, CLOSED 2026-08-08) |
| | | Ranked-open #4, instance-to-database linkage, CLOSED 2026-08-08 (item 10) |
| CLAIMED | 0 as a row | but three sub-verdicts are CLAIMED inside PARTIAL rows: `InstanceBackups.restoreToNew`'s create-story docblock (item 9; NARROWED 2026-08-10 -- backupNow, retention and both refusals are hermetic now, only the restore create-story stays live-only), the durability contract of install (item 3), the file-capability enforcement matrix (item 5) |

Of the 7 IMPLEMENTED rows, all 7 rest on hermetic state-asserting tests. Of the
7 PARTIAL rows, five are partial specifically because the working half is proven
only `[live]`.

---

## 1. Curated templates

**PARTIAL.** The mechanism is complete and hermetically tested. The CATALOG is
nearly empty, and that is a content decision, not an engineering one.

Mechanism: template rows with an APPROVAL gate
(`src/server/java/be/elevenways/hohenheim/server/instance/InstanceTemplates.java:46`
`requireSelectable`), a picker page (`server/cms/InstanceFromTemplatePage.java:38`,
selectability re-checked before render at `:57`), content-pinned import from a
vendored community-script catalog
(`server/instance/CommunityScripts.java:195` `catalogApps`, `:207`
`catalogRevision`, `:250` `importApp`) and export/import portability
(`server/instance/TemplatePortability.java`). **[code]**

Catalog census, counted from the tree rather than assumed:

- Vendored community scripts: **2** -- `gotify` and `adguard`, declared at
  `src/server/resources/community-scripts/catalog/REVISION:4-5`, pinned at
  upstream revision `27f66a80`. The whole tree is 5 files.
- Seeded game templates: **2** -- "Velocity proxy" and "Minecraft server
  (Paper)", `server/game/GameTemplateSeeder.java:36-39` (`:42-65`, `:67-96`).

- Test: `src/browserTest/java/be/elevenways/hohenheim/test/instance/CommunityScriptCatalogTest.java:67`
  asserts the catalog contains exactly those two apps (`:70-72`) and that the
  imported install script equals the vendored bytes (`:97-99`); `:200` asserts an
  upstream edit CANNOT change an approved template (`:234-237`, and that the
  pinned script does not contain the injected marker).
  `src/browserTest/java/be/elevenways/hohenheim/test/instance/InstanceTemplatePolicyTest.java:144`
  round-trips an export/import, verifies the checksum, lands it UNAPPROVED
  (`:214-216`) and asserts a tampered import created NOTHING (`:206-207`).
  `TenantInstanceSurfaceTest.java:561` asserts a tenant cannot introduce a
  catalog script -- the status assertions there are decoration, but each is
  backed by a row-count assertion (`:572-575`, `:600-603`) and by
  `APPROVED_AT` being null (`:623-624`). All hermetic. **[test]**

Honest wording for any public claim: "an operator-picked template catalog with an
approval gate and content-pinned imports, currently holding two vendored
community-script apps and two seeded game templates". Pterodactyl's value here is
partly the size of its egg ecosystem; see the rejection of a large catalog in the
Coolify inventory, which applies identically.

Note: there is no `ServiceTemplate` symbol in this repository. If a plan note
uses that term, it does not name anything here.

## 2. Typed variables

**IMPLEMENTED.** The strongest-evidenced item in this list.

A template variable declares a TYPE, and the type builds a real zenit `Field`, so
coercion and validation are the standard submit pipeline rather than a
hand-rolled regex: `server/instance/variable/VariableTypeHandler.java:19`
(`buildField` at `:27`), with five types -- string, integer (bounds via `Min`/`Max`
at `IntegerVariableType.java:60-65`), boolean, select and secret -- registered
through `variable/VariableTypes.java:30` and
`src/common/java/be/elevenways/hohenheim/instance/VariableTypeRegistry.java`.
Wired at `server/instance/InstanceTemplates.java:101` (`variableFormSpec`),
`:109`, `:149`; stored coerced at `server/instance/InstanceVariables.java:37`. **[code]**

- Test: `src/browserTest/java/be/elevenways/hohenheim/test/instance/InstanceTemplatePolicyTest.java:224`
  -- the refusal carries the FRAMEWORK's typed violation key
  (`SERVER_PORT=zenit.validation.max`, `:252-254`), not a home-rolled message;
  the refused create persisted nothing (`:244-246`); the good value stored
  (`:271-272`); the secret is a `zenc$` envelope AT REST (`:279-282`). Hermetic. **[test]**

## 3. Install and reinstall

**PARTIAL.** The refusal paths are pinned hermetically. The thing that actually
matters -- whether a `clear` reinstall wipes data and a `preserve` one does not --
is pinned ONLY by a `[live]` test. And install/reinstall is operator-only.

- Code: `server/instance/InstanceInstalls.java:62` (`install`), `:85`
  (`reinstall`), the clear branch at `:102-127`, the shared runner at `:133`.
  Policy vocabulary: `model/InstanceTemplateModel.java:41` `REINSTALL_PRESERVE`,
  `:44` `REINSTALL_CLEAR`, field `:111`, default `preserve` `:116`. Install state
  machine: `model/InstanceModel.java:126-138`. The destructive dialog demanding
  the instance's name typed back is at `server/cms/InstanceResource.java:214`. **[code]**
- Test (hermetic, refusals only):
  `src/browserTest/java/be/elevenways/hohenheim/test/instance/CommunityScriptCatalogTest.java:132`
  asserts an unimplemented helper is refused BY NAME and that the install state
  never left `pending` (`:187-190`); `InstanceTemplatePolicyTest.java:290-292`
  pins the deploy-before-install refusal (`install_incomplete`). **[test]**
- Test (live, the data policy):
  `src/browserTest/java/be/elevenways/hohenheim/test/instance/InstanceTemplateInstallLiveTest.java:87`
  -- preserve leaves two install markers (`:191-193`), clear leaves one
  (`:205-207`), and variables survive BOTH (`:209-210`). Three assumption gates
  at `:88`, `:91`, `:93`. **[live]**

`reinstall` has exactly one production call site (`InstanceResource.java:220`)
and one test call site (the live one). **There is no hermetic coverage of
`reinstall` at all.**

Stated limitation: `InstanceInstalls.install`/`reinstall` carry NO capability
gate of their own -- only `InstanceOperationGuard.requireOperable` (`:64`, `:87`).
Authority comes entirely from the admin row action, and `InstanceApi` registers
no install route. So install and reinstall are OPERATOR-ONLY, while the same
minimum-claim sentence also promises a tenant-facing API. In Pterodactyl a
subuser can reinstall. Naming this as a deliberate narrowing rather than an
oversight is the honest close: a reinstall runs template-authored scripts, and
the template approval gate is an operator judgement.

**DRIFT (corrected in this pass):** the class docblock at
`InstanceInstalls.java:30-33` said no path here touches volumes "except the
volume wipe" a clear policy demanded. The clear branch also DESTROYS THE
WORKLOAD (`:116`), not just volumes -- the inline comments at `:104-106` and
`:113-115` were honest about it while the class docblock was not.

## 4. Power and console, separated from arbitrary exec

**IMPLEMENTED.** Three distinct capabilities with three distinct gate call
sites, and the separation is pinned by a hermetic test.

- `console` (`HohenheimAccess.java:83`, registered `:270-273`) -- reaches the
  workload's STDIN, never an arbitrary program. Gated in
  `server/instance/InstanceConsoles.java:256` (`tail`) and `:314`
  (`sendCommand`), plus the websocket handshake
  (`InstanceConsoleHandler.java:38`, `:68`), the framebuffer
  (`VmFramebufferHandler.java:77`, `:192`) and the schedule action
  (`InstanceConsoleCommandAction.java:41`).
- `power` (`:86`, registered `:274-277`) -- gated in
  `server/instance/InstanceService.java:111` (`deploy`) and `:212` (`stop`), i.e.
  ON THE SERVICE every surface funnels through, not on the row action.
- `exec` (`:109`, registered `:292-294`) -- declared `.admin()`: structurally
  non-delegable, never owner-implied, implied by nothing, and unreachable through
  `manage`'s umbrella. Gated as the FIRST statement of
  `server/instance/InstanceExec.java:70`. **[code]**

- Test: `src/browserTest/java/be/elevenways/hohenheim/test/instance/InstanceCapabilitySplitTest.java:182`
  asserts nothing may imply exec (`:207-208`); `:314` asserts a console delegate
  cannot change config, destroy or exec -- and backs each refusal with STATE (the
  name is unchanged `:329-331`, `DELETED_AT` still null `:340-341`); `:443`
  asserts the mirror, a power-only holder refused at `sendCommand` with
  `instance_not_permitted` (`:458-460`). Hermetic; the one status-code assertion
  in the file is explicitly paired with a content assertion (`:287-290`). **[test]**

This is a genuine improvement over Pterodactyl, where console and "send command"
are one permission and there is no arbitrary-exec surface at all to separate.

**DRIFT (corrected in this pass):** the AIDEV-NOTE at `HohenheimAccess.java:320`
said "read is the only ORDINARY capability on this model". Four are:
`view` (`:266`), `console` (`:270`), `power` (`:274`) and `files.read` (`:323`)
all register without `.elevated()`/`.admin()`, and `KnownCapability` defaults to
ORDINARY. The rest of that note (not owner-implied, not implied by write) is
accurate and IS tested (`InstanceCapabilitySplitTest.java:215-219`).

## 5. File management

**PARTIAL.** Full CRUD plus upload and download exists and is capability-gated
in the SERVICE. The lexical containment layer is pinned hermetically; whether a
byte ever actually moved is pinned only `[live]`.

- Code: `server/files/InstanceFiles.java:71` -- `volumeRoots` `:104`, `list`
  `:114`, `read` `:161`, `write` `:193`, `makeDirectory` `:223`, `rename` `:243`,
  `delete` `:273`, with `files.read` asked at `:105`/`:115`/`:162` and
  `files.write` at `:194`/`:224`/`:244`/`:274` -- separately on every path, never
  one implying the other. Path arithmetic in `files/InstanceFilePath.java`;
  endpoints in `files/InstanceFileEndpoints.java:64`/`:81`/`:106`/`:134`/`:153`/`:170`;
  UI at `server/cms/InstanceFilesPage.java:65`. **[code]**
- Test (hermetic): `src/browserTest/java/be/elevenways/hohenheim/test/instance/InstanceFilePathTest.java:24`
  refuses every escape spelling and accepts only the canonical chain (`:88-90`),
  with traversal (`:42-44`, `:51-53`, `:60-62`), symlink-shaped (`:79-81`), depth
  bound (`:98-100`) and root normalization (`:106-108`). Real, but pure path
  arithmetic: it proves nothing about a file. **[test]**
- Test (live): `src/browserTest/java/be/elevenways/hohenheim/test/instance/InstanceFilesLiveTest.java:110`
  is the only test that asserts a byte moved -- and it reads back from INSIDE the
  container, which is the right shape. Its capability/tenancy matrix (`:296`) is
  inside the same method, so the file-capability ENFORCEMENT matrix is `[live]`
  too. Gates at `:111`, `:113`, `:115`. **[live]**

Weakness found and recorded, not fixed: `TenantInstanceSurfaceTest.java:545-549`
asserts a forged write POST is "answered" with `isIn(200, 302, 303)` and does NOT
assert that the file was not created. Its own comment claims the service gate is
the authority, but the assertion cannot distinguish refused from created. That is
the status-code-only shape this document exists to flag. **OPEN slice:** add the
row/absence assertion; the harness already has everything needed.

Policy question this document names rather than answers: a holder of
`files.write` ALONE can write, rename, delete and mkdir without holding
`files.read`, and `rename`'s SOURCE path is never read-gated. The docblock at
`InstanceFiles.java:35-37` states the separation accurately but not this
consequence.

## 6. Port allocations

**IMPLEMENTED.**

Claims are ledgered per host BEFORE the container exists for anything that needs
a stable number, and the daemon's ACTUAL bind is read back and verified against
the declaration before any outcome write:
`server/instance/PortPublications.java:54` (`ensureClaimed`), `:90` (`claimOne`),
`:166` (`verifyPublished`), called from `server/instance/InstanceService.java:130`
and `:154`. Ledger in `src/common/java/be/elevenways/hohenheim/ports/PortLedger.java`
over `model/PortAllocationModel.java` (M051, M052). **[code]**

- Test: `src/browserTest/java/be/elevenways/hohenheim/test/ports/PortLedgerTest.java:327`
  runs a real two-thread race and asserts EXACTLY ONE ledger row exists for the
  contested tuple (`:407-409`), that the loser gets a typed `PortConflict` naming
  the winner (`:413-415`), that a stop's observed release KEEPS the reserved
  number (`:351-353`) and that only end-of-life frees it (`:362-364`); `:287`
  asserts a deleted instance's claims are PARKED in `releasing` (`:308-310`) and
  still refuse a rival (`:313-316`). Hermetic. **[test]**
- `PublicPortLiveTest` additionally proves a real socket is reachable (gates at
  `:475`-`:478`), but the ledger semantics above do not depend on it. **[live]**

Wider than Pterodactyl's allocations in one way that matters here: the ledger
arbitrates across TIERS -- instances, docker sites, managed databases and stacks
share it -- so two products on one host cannot both believe they own a port.

## 7. Subuser capability delegation

**IMPLEMENTED.**

Delegation is the framework's record-grant mechanism, not a panel feature:
`(subject, model, record, capability)` rows expanded through group membership,
funnelled through `HohenheimAccess.requireOperationCapability`. The instance
vocabulary is declared once, with each verb's sensitivity and delegability
stated on the constant.

- Code: `src/server/java/be/elevenways/hohenheim/server/auth/HohenheimAccess.java:60`
  (vocabulary registration `:256-330`). The twelve instance verbs: `manage` :63,
  `view` :66, `console` :83, `power` :86, `config` :92, `destroy` :99, `exec` :109,
  `snapshots` :120, `backups` :123, `files.read` :131, `files.write` :138,
  `image_any` :146. **[code]**
- Test: `src/browserTest/java/be/elevenways/hohenheim/test/instance/InstanceCapabilitySplitTest.java:63`
  -- 7 ordered journeys, no assumption gate. `:182` asserts
  `KnownCapabilities.impliersOf(InstanceModel.MODEL_ID, EXEC)` is EMPTY, i.e.
  nothing may imply exec; `:443` proves a power delegate cannot reach the
  console. **[test]**
- Test: `src/browserTest/java/be/elevenways/hohenheim/test/instance/TenantInstanceSurfaceTest.java:58`
  -- 9 journeys, notably `:271` revoking the grant BETWEEN render and act. **[test]**

This is strictly wider than Pterodactyl's subuser permissions in one respect
that matters: the same grant mechanism covers databases, sites and DNS zones, so
"this tenant lead may restart the backend and edit its SRV record but never run
a shell" is expressible. It is narrower in one: there is no per-subuser
notification of what they may do; the UI simply hides what they cannot.

## 8. Ordered schedules whose tasks carry capability checks

**IMPLEMENTED**, with the mechanism living in the framework rather than here.

Per-record schedules with ordered steps, per-step offsets and failure policies
are `zenit` core (`common/task/record`, `server/task/record`). The step's
`run_as` principal is re-authorized AT EXECUTION, per step, so revoking a grant
stops a stored chain.

- Code (mechanism): `/home/skerit/projects/javaweb/zenit/src/server/java/be/elevenways/zenit/server/task/record/RecordSchedules.java:609`
  -- the per-step `hasCapability` call, refusing with
  `RecordScheduleActions.REFUSAL_CAPABILITY_DENIED`. **[code]**
- Code (wiring): `src/server/java/be/elevenways/hohenheim/server/schedule/InstanceScheduleAction.java:14`
  and the five actions that declare their own `requiredCapability()`:
  `InstancePowerAction.java:50`, `InstanceSnapshotAction.java:40`,
  `InstanceBackupAction.java:40`, `InstanceConsoleCommandAction.java:40`,
  `InstanceAppUpdateAction.java:35`. Authoring is gated on `config`
  (`src/server/java/be/elevenways/hohenheim/server/cms/InstanceScheduleResource.java:196-199`). **[code]**
- Test: `/home/skerit/projects/javaweb/zenit/src/test/java/be/elevenways/zenit/server/task/record/RecordSchedulesTest.java:192`
  asserts step ORDER from persisted rows after inserting out of order; `:295`
  asserts the persisted run is `STATUS_ABORTED` with the step recorded
  `STEP_REFUSED` and the error naming the revoked capability. **[test]**
- The hohenheim end-to-end chain
  (`src/browserTest/java/be/elevenways/hohenheim/test/instance/InstanceScheduleLiveTest.java:214`)
  is **[live]** -- `assumeTrue` on the Docker socket at `:115` and a netns at
  `:116`. Tenant scoping of the schedule list is hermetic
  (`TenantInstanceSurfaceTest.java:353`).

## 9. Backups and restore

**PARTIAL.** The archive and the off-host target layers are proven hermetically,
and since 2026-08-10 so are the BACKUP half of the lane (`InstanceBackupsTest`:
key discipline, retention, boot settle, both refusals); the restore-to-new
JOURNEY is still proven only `[live]`.

Works, with hermetic proof:

- Encrypted archive format with manifest and checksum:
  `src/server/java/be/elevenways/hohenheim/server/backup/BackupArchive.java:45`;
  `src/browserTest/java/be/elevenways/hohenheim/test/backup/BackupArchiveTest.java:49`
  round-trips and `:76`/`:93`/`:106` refuse tampering, a foreign key and a bad
  checksum. **[test]**
- Filesystem off-host target: `FilesystemBackupTarget.java:27`;
  `src/browserTest/java/be/elevenways/hohenheim/test/backup/BackupTargetsTest.java:122`
  asserts the RETRIEVED BYTES equal what was stored, plus a traversal refusal. **[test]**
- Restore capacity gate: `src/browserTest/java/be/elevenways/hohenheim/test/instance/RestoreCapacityTest.java:79`, `:123`. **[test]**
- Backup lane honesty (2026-08-10): same-second captures get DISTINCT remote keys
  and retention never removes the surviving row's artifact; the prune keeps the
  row of an artifact the target refuses to release; interrupted uploads are
  settled FAILED at boot with their artifact removed; the BACKUPS gate fires
  before the target resolves; tenants cannot restore.
  `src/browserTest/java/be/elevenways/hohenheim/test/instance/InstanceBackupsTest.java`. **[test]**

Not proven without a daemon:

- SSH off-host target (`SshBackupTarget.java:48`) -- `BackupTargetsTest.java:159`
  assumes an sshd at `:160`. **[live]**
- The real journey: `LiveOffHostBackupTest.java:166` (4 assumption gates),
  `LiveControlPlaneOffHostBackupTest.java:116`, `InstanceSnapshotBackupLiveTest.java:170`,
  `IncusSnapshotBackupLiveTest`. **[live]**
- `InstanceBackups.java:60` (`backupNow` :82, `restoreToNew` :276,
  `pruneForRetention` :459). **SUPERSEDED 2026-08-10** (this bullet read "has NO
  hermetic state-asserting test" until then, and that gap was not garnish: the
  same-second remote-key collision both snapshot lanes had already fixed sat
  unfixed in this lane precisely because nothing hermetic could catch it --
  retention deleting the older of two same-second rows destroyed the artifact
  the surviving COMPLETE row pointed at). `InstanceBackupsTest` now covers
  backupNow's key discipline under retention, prune keeping the row of an
  undeletable artifact, the boot settle of interrupted uploads, the
  gate-before-target-resolution refusal and the tenant restore refusal, all
  daemon-free. **[test]** The restore path's docblock claims (host admission,
  network policy, hardening, fenced deploy on restore-to-new) are still proven
  only `[live]`; treat THAT half as **CLAIMED** still.

Stated limitation, not a bug: `restoreToNew` refuses non-operators outright
(`backup_restore_operator_only`, `InstanceBackups.java:299`; hermetic since
2026-08-10, `InstanceBackupsTest`). A tenant with the
`backups` capability can TAKE a backup and cannot RESTORE one. Pterodactyl
allows a subuser to restore. This is a deliberate difference (a restore mints a
new instance and therefore spends quota and placement), and it is a real
reduction in delegation.

## 10. Per-instance database allocation

**PARTIAL -- and the plan's own clause is worded more strongly than what
landed.** This row supersedes the assumption that `ccd1bd5` closed the clause
outright.

What DID land (2026-08-08, commit `ccd1bd5`, 19 files, +1820/-43), verified:

- `DatabaseModel` became grantable with its own five-verb vocabulary --
  `manage`/`view`/`credentials`/`backups`/`destroy`, each with an enforcing
  surface, `exec` deliberately excluded --
  `src/server/java/be/elevenways/hohenheim/server/auth/HohenheimAccess.java:362-397`,
  `CREDENTIALS` constant at `:117`. **[code]**
- ONE allocation funnel: `src/server/java/be/elevenways/hohenheim/server/database/TenantDatabases.java:48`
  -- permission `hohenheim.databases.create` (`:56`), per-owner name namespacing
  (`storedNameFor` `:86`), the engine's OWN default image (never a submitted
  one), placement through `InstancePlacement.forActor`, and the creator's
  `manage` grant planted on the new record. **[code]**
- Both write pipelines gated: `TenantWrites.java:61` refuses a create outside
  the funnel (`tenant_database_not_allocatable`) and freezes every column on a
  stored row (`DATABASE_TENANT_WRITABLE` is the EMPTY set). **[code]**
- Tenant surface: `ManageDatabaseResource.java:51` (create submit routes to the
  funnel at `:117`) and the `credentials`-gated
  `ManageDatabaseCredentialsPage.java:33`. **[code]**
- Test: `src/browserTest/java/be/elevenways/hohenheim/test/database/TenantDatabaseSurfaceTest.java:65`
  -- 664 lines, 8 ordered journeys, NO assumption gate, every one asserting
  persisted state through the real HTTP surface. `:250` asserts the record is
  stored under the namespaced name AND that the bare label was not taken
  installation-wide; `:447` attacks every frozen column and asserts each kept
  its stored value; `:556` races two real threads through a `CyclicBarrier` and
  asserts exactly one allocation landed; `:612` asserts a refused destroy left
  the record present ("a refusal that still deleted would pass a status-only
  test"). **[test]**

What did NOT land, and why the clause is only PARTIAL: the allocation is
**per-TENANT, not per-INSTANCE**. There is no instance-to-database link at all
(`InstanceModel` carries no database column; the only link model is
`SiteDatabaseModel`, site_id + database_id). Credential INJECTION exists only
for sites, via `DatabaseEnvInjection.java:178`
(`src/browserTest/java/be/elevenways/hohenheim/test/database/DatabaseEnvInjectionTest.java:94`
proves the prefixed families and primary URL hermetically; the end-to-end
`EnvInjectionFlowTest.java:117` is **[live]**).

So: a tenant can allocate their own database and attach it to a SITE. A game
instance cannot allocate one, and cannot receive its credentials as environment
variables. In Pterodactyl the database is allocated ON a server and its
credentials appear in that server's panel. **OPEN slice:** an
`instance_databases` link with the same two-sided authority rule
`SiteDatabaseModel` already has, plus injection into the instance variable
mechanism.

**STATUS 2026-08-08 -- the clause is now CLOSED, and the OPEN slice above landed
essentially as described.** Supersedes the PARTIAL verdict; the finding stays as
the history that produced it.

- `InstanceDatabaseModel` + `M087_CreateInstanceDatabases` (`instance_databases`:
  instance_id, database_id, env_prefix). **A SECOND model, not a generalization of
  `SiteDatabaseModel`, and the argument is evidence rather than symmetry** (the
  model's own docblock carries it): `site_databases` has fourteen production
  consumers and most are structurally site-shaped -- `SiteReleases` folds the links
  into the release FINGERPRINT, `ProxyReloadHooks` reloads the proxy on a link
  change, `DockerReconciler` resolves orphan ownership by that model id,
  `AttentionCollector`/`DatabaseRestorePage` name the SITE, `SiteModel`'s remove
  hook cascades. An owner-kind column would make every one of them branch. The
  lifetimes differ too (a site link must outlive the release instances a gated
  release mints; an instance link keys the instance), and the link-network handle
  `dblink-<ownerId>-<dbId>` would COLLIDE across owner kinds (site 5 + db 3 versus
  instance 5 + db 3), which is why the instance lane mints `idblink`. What IS
  shared is the derivation: `DatabaseEnvInjection.vars`/`connectionUrl` were already
  owner-agnostic and both lanes now enter through one `envFor`. **[code]**
- Authority: `TenantWrites.requireInstanceLinkAuthority` -- `config` on the
  INSTANCE and `manage` on the DATABASE, both sides re-asked when a stored link is
  re-pointed, enforced on the model write pipeline (never the resource, for the
  reason that class documents). `config` rather than `manage` on the instance is the
  NARROWER reading and is argued from three existing declarations:
  `HohenheimAccess.CONFIG` is "author what the instance IS",
  `GameDomains.requireInstanceAuthority` already asks exactly it for the other
  instance-side join, and `InstanceVariables.requireVariableAuthority` asks it for a
  variable write with the argument that a variable write IS a config write -- an
  attach is a variable write by proxy. On the database side `manage` rather than
  `credentials`: a credentials holder can already READ the password, but only manage
  may make the engine REACHABLE from another workload, which is what the link
  network does. **[code]**
- Injection: `DatabaseEnvInjection.envForInstance` derives the family at
  `InstanceService.resolve`, stored NOWHERE, and rides `Resolved.variables()` so it
  substitutes into `command`, `cloud_init` AND staged config files -- the Pterodactyl
  shape (a start command naming its database). `applyToSettings` grew a third layer:
  derived baseline < settings' own `environment_variables` < declared variables, the
  same "operator-authored overrides" order `SiteInstances` documents for sites. **[code]**
- Network + lifecycle: `InstanceDatabaseNetworks` (one `Egress.NONE` link network per
  pair) REGISTERS as an `InstancePreStartHook` rather than adding a line to
  `deploy`, so the existing completeness test covers it; `DatabaseLinkNetworks`
  extracts what the two tiers share (the published-port correction a membership
  change forces was too subtle to copy); `VerifyWorkloadIsolation` enumerates the new
  handles or the sweep would sever them; `DatabaseService.provisionRuntime` rejoins
  both tiers after a reprovision; `InstanceService.destroy` calls
  `InstanceDatabaseLinks.deleteForInstance` explicitly because destroy SOFT-deletes
  and fires no remove hooks. **[code]**
- The in-use refusal on database delete now counts INSTANCES as well as sites
  (`DatabaseResource.deleteRow`), so a tenant cannot destroy the engine out from
  under their own running server. Without it the workload keeps its derived
  environment until it next resolves and then simply cannot connect. **[code]**
- Test: `src/browserTest/java/be/elevenways/hohenheim/test/database/InstanceDatabaseAttachTest.java`
  -- 4 ordered journeys, hermetic, no assumption gate. Attacks first: a tenant
  attaching a database they do not own, attaching to an instance they do not own, a
  read-only teammate with VIEW on BOTH ends, and an auth ADMINISTRATOR holding no
  hohenheim grant (the layered-enforcer check, so the refusal cannot be zenit-auth's
  panel baseline answering). Every refusal asserts the ROW COUNT, and the credential
  counterfactual asserts the password is absent from `instances.settings` and from
  the instance-variable carrier while present in the derived map. Five gates were
  reverted and observed failing. **[test]**

Still open, and named rather than glossed: nothing asserts the attachment from
INSIDE a running container. The site lane's `docker/SiteDatabaseLinkLiveTest` proves
that end-to-end over the same `LinkNetworkSupport` calls, and the instance lane's
own `InstanceService.destroy` CALL SITE for the cleanup is covered only by the same
kind of live path that `GameDomains.deleteForInstance` is -- an instance-tier live
twin is the honest next slice.

## 11. Resource quotas

**IMPLEMENTED as of 2026-08-08** (was PARTIAL: no per-owner memory cap). Instance
count, MEMORY, disk GB and extra NICs are all enforced and proven -- see the
STATUS block at the end of this item.

- Code: `src/common/java/be/elevenways/hohenheim/model/InstanceQuotaModel.java:23`
  -- `SUBJECTS` :35, `MAX_INSTANCES` :42, `MAX_DISK_GB` :49, `MAX_NICS` :56.
  Defaults at `src/common/java/be/elevenways/hohenheim/HohenheimSettings.java:718-745`.
  Enforcement is in schema write hooks, not surfaces:
  `src/server/java/be/elevenways/hohenheim/server/instance/InstanceQuota.java:118`,
  `InstanceDeviceQuota.java:44`, `InstanceRootDiskQuota.java:48`. **[code]**
- Test: `src/browserTest/java/be/elevenways/hohenheim/test/instance/InstanceQuotaTest.java:86`
  races two creates and asserts exactly one row survives with the used count at
  the limit; `InstanceDeviceQuotaTest.java:93/:168/:228`;
  `RootDiskSizeTest.java:71/:144/:220`;
  `InstanceQuotaAttributionTest.java:169` (an owned instance is charged to the
  owner of the record that owns it). All hermetic. **[test]**

**No memory dimension exists per owner.** Memory is booked PER HOST
(`InstanceCapacity.java`, `InstanceCapacityTest.java:147/:225`, hermetic), so a
single owner can consume an entire host's RAM budget within their instance-count
cap. Pterodactyl's core quota unit is memory. This is a genuine OPEN item, not a
rejection: the ledger and the hook site both already exist, so the work is a
column, a setting and a reserve dimension.

Also stated: `InstanceQuotaModel`'s class docblock (`:16-20`) still describes the
table as a per-owner INSTANCE-COUNT override and names only the one setting,
three caps after the fact. Understated rather than overstated, but it is drift.

**STATUS 2026-08-08: DONE, and the row moves PARTIAL -> IMPLEMENTED.** The memory
dimension exists, is transactional, and books only what is enforced.

- Code: `src/common/java/be/elevenways/hohenheim/model/InstanceQuotaModel.java:67`
  (`MAX_MEMORY_MB`), setting `hohenheim.quota.max_memory_mb_per_owner`
  (`HohenheimSettings.java:756`), the reserve/release/delta dimension in
  `src/server/java/be/elevenways/hohenheim/server/instance/InstanceQuota.java`
  (bucket prefix `:100`, `memoryLimitFor` `:144`, `reserveMemory` `:303`,
  `rebookMemory` `:321`), the booked-amount stamp
  `InstanceModel.QUOTA_MEMORY_MB` (`InstanceModel.java:253`), migration
  `M088_OwnerMemoryQuota` (columns + a heal that prices every live instance into
  its owner's bucket), and the cap on the admin form
  (`cms/InstanceQuotaResource.java`). The 191-char bucket FOLD now has one owner,
  `server/quota/OwnerQuota.java`, which the instance, device and preview
  dimensions all call -- a per-dimension copy of that derivation is how a charge
  and its release end up in different buckets. **[code]**
- Test: `src/browserTest/java/be/elevenways/hohenheim/test/instance/InstanceMemoryQuotaTest.java:82`
  -- one hermetic journey, no daemon, 0 skipped: an UNBOUNDED workload is stamped
  and charged at its kind footprint (512 for `docker_container`), a declared limit
  at what it declares, two racing creates through the real create submit cannot
  both spend the last workload of budget (state: one live row, `used == limit`),
  the loser is refused by the named `memory_quota_reached`, a declared limit over
  the budget is refused with nothing persisted, the soft-delete transition (the
  write `InstanceService.destroy` performs) hands the STAMPED megabytes back, the
  freed budget admits exactly one replacement, a shrink frees the delta and
  re-stamps, a refused grow keeps the old stamp, and the hard-delete pairing
  releases both dimensions. **[test]**

**THE DECISION, since this was deferred once on it:** an unbounded workload
participates AT ITS KIND'S DECLARED FOOTPRINT. `defaultFootprintMb` is not a
per-kind extra to be added -- it is an ABSTRACT method on `InstanceKindHandler`
with no interface default, so every kind already declares one (docker 512, incus
container 512, incus VM 1024, site container 512, stack service 512, managed
database per engine 512..1280) and forgetting it is a compile error. Charging only
DECLARED limits was rejected for the recorded reason: `ResourceLimits` treats
memory as optional, so that budget sums to ZERO for the common workload and a
tenant filling a host reads as holding nothing. Charge == cap survives intact,
because the number booked is the one
`ResourceLimits.fromSettings(settings, defaultFootprintMb(settings))` hands the
driver as the cgroup / VM cap -- the same call the host ledger books through, now
shared as `InstanceCapacity.effectiveFootprintMb` so the two can never disagree.
A kind whose handler class is gone prices at 0 and books nothing, which is the
`ProcessCapacity.reserve` rule: never book what nothing enforces.

## 12. Transfer between eligible hosts

**IMPLEMENTED.**

- Code: `src/server/java/be/elevenways/hohenheim/server/instance/InstanceMigrations.java:58`
  -- `migrateTo(id, destinationHostId)` and `drain(hostId)` returning a typed
  `DrainReport`. **[code]**
- Test: `src/browserTest/java/be/elevenways/hohenheim/test/instance/InstanceMigrationTest.java:159`
  -- hermetic, over an in-memory fake daemon pair. It asserts the SOURCE daemon
  holds nothing under the handle ("a move that leaves the source copy is the
  silent-success shape"), that the data and pool-resident snapshots arrived, and
  that the record names the destination. `:287` proves a killed controller
  settles both crash windows without split ownership; `:367` proves a drain
  refuses without a cordon and then reports moved/refused honestly. **[test]**
- The HOST CAPACITY LEDGER follows the record, since 2026-08-09: the handoff is a
  fenced `updateAll` and fires no write hooks, so the charge used to stay on the
  source forever (a drained host read as fully booked and was then refused
  `no_placement_capacity` -- draining removed it from the pool permanently). The
  destination is now booked when the migration window opens, which is also the only
  memory gate on an operator-named `migrateTo`, and the fenced write that ends the
  window releases the other side. `InstanceMigrationTest:496`. **[test]**
- The Incus DRIVER path is proven only by `IncusColdMigrationLiveTest`
  (`assumeTrue` at `:65`, `:67`). So the orchestration is hermetic and the real
  export/import is **[live]**.

Known limitation carried from `proxmox-use-inventory.md` item 2: a PREPARED
image lives in one daemon's store, so a workload provisioned from one cannot be
moved to a host that lacks it. Placement does not know this and cannot avoid it.

## 13. Live stats and logs

**IMPLEMENTED as of 2026-08-08** (was: PARTIAL, and the single largest evidence gap
in this document -- the STATUS block at the end of this item is what closed it; the
heading itself was still stale on 2026-08-08 and is corrected here).

Stats: `src/server/java/be/elevenways/hohenheim/server/instance/InstanceStats.java:34`,
`InstanceStatsHandler.java:32`, `cms/InstanceStatsPage.java`. **[code]**

`src/browserTest/java/be/elevenways/hohenheim/test/instance/InstanceStatsLiveTest.java:47`
is the ONLY test in the repository that touches `InstanceStats` -- verified by
searching every test source, not assumed. Its single test method assumes THREE
times inside the body: a Docker socket (`:85`), a pulled `alpine:latest`
(`:87`) and a network namespace (`:89`). When it runs its assertions are real
(a spinning container must report CPU above 1%, memory non-zero, and a second
subscriber must get the ring replay). When it skips, the entire live-stats
capability has zero coverage and the suite is green. **[live]**

Logs: console episode capture and retention
(`src/server/java/be/elevenways/hohenheim/server/instance/InstanceConsoleLogs.java:28`,
`InstanceLogModel`, M085). Log AUTHORIZATION is hermetic --
`TenantInstanceSurfaceTest.java:643` inserts rows directly and proves the
console tab answers to `console` and not to `view`. Log CAPTURE and RETENTION
are proven only by `InstanceLogRetentionLiveTest` (`assumeTrue` at `:80`, `:82`,
`:84`). **[live]**

The fix is not more live tests: it is a hermetic fake for the stats stream, the
same shape `FakeNativeDaemons` already provides for the migration journeys.

**STATUS 2026-08-08: DONE, in exactly that shape, and the row moves PARTIAL ->
IMPLEMENTED.** `FakeNativeDaemons` grew the two missing driver CAPABILITIES --
`StatsStreamSupport` and `ConsoleStreamSupport` over a `ScriptedStream` whose frames
the test writes -- because the hub asks the resolved RUNTIME whether it has the
lane, so a separate harness would have had to re-fake the whole runtime to be asked
at all. `FakeVolumeKind` still exposes neither, which is what makes "a driver
without the lane is refused BY NAME" testable.

`src/browserTest/java/be/elevenways/hohenheim/test/instance/InstanceObservabilityContractTest.java`
-- 4 journeys, 34 assertions, under a second, no daemon:

- stats: the decode, one shared stream for many viewers with the ring replayed to a
  joiner, the bounded ring, and the teardown of the DRIVER stream when the last
  viewer leaves (asserted on the fake stream's own closed flag, not on a status).
- the refusals: a driver with no stats lane, a stats read that does not answer, and
  a STOPPED workload are three NAMED failures -- never an empty chart, which reads
  as an idle workload.
- console: redaction AT INGEST (the declared secret reaches neither a viewer nor the
  stored row), one UPSERTED row per episode however often it flushes, a secret
  declared MID-STREAM redacted from there on, and the retention sweep.
- the console refusals: no lane, and an unreadable console -> `logs_unavailable`,
  plus the one-shot tail carrying its own redaction so the automation API is not the
  wider door.

Scripting the frames buys assertions the live test structurally CANNOT make, which
is the point rather than a side effect: exact CPU arithmetic from known counters
(usageDelta/systemDelta * cores * 100 -- a live container cannot be asked for a
round number), a sample SPLIT across a chunk boundary decoding to one sample, an
unparseable line skipped without ending the stream, and the ring bound reached on
purpose. **[test]**

What it CANNOT prove, so `InstanceStatsLiveTest` and `InstanceLogRetentionLiveTest`
both stay: that a REAL daemon's `/stats` stream carries these keys in this NDJSON
framing, and that a container really burning a core reports non-zero CPU. A fake
cannot discover an Engine API change; it can only prove that what we do with the
bytes is right.

**Behaviour defect found by writing it, FIXED: console log retention swept the wrong
column.** `CleanOldInstanceLogs` swept by `created_at`, but an instance log row is
UPSERTED for the whole life of ONE console episode -- so on a workload streaming for
a month, `created_at` is the age of the EPISODE and never the age of its text. The
sweep deleted a row still being written to and the sink's next flush silently started
a new one, losing exactly the history the 30-day window was supposed to still hold.
It now sweeps by `saved_at`, which retires an episode 30 days after its LAST line.

## 14. A tenant-facing API

**IMPLEMENTED.**

- Code: `src/server/java/be/elevenways/hohenheim/server/instance/InstanceApi.java:59`
  -- API-key-only (a browser session is refused), every route resolving its
  instance through `hasInstanceCapability` (`:465`) and answering ONE uniform
  404 for absent, trashed and not-yours. **[code]**
- Test: `src/browserTest/java/be/elevenways/hohenheim/test/instance/TenantInstanceApiTest.java:58`
  -- 5 hermetic journeys: the key-only rule and cross-tenant invisibility
  (`:245`), unowned-equals-absent (`:294`), the API refusing with the SAME named
  violation the HTML surface renders (`:330`, a machine key, not a status code),
  scope narrowing below the owner's own authority (`:403`), and a console-only
  delegate being unable to write the variables that become the command (`:456`). **[test]**
- Documented at `docs/paas-api.md`, with a thin CLI (`tools/hoh`).

## 15. SFTP

**REJECTED as a non-goal** -- which the plan explicitly permits "only if the
browser/API file surface covers our real workflows" (`instance-tier-plan.md:89`).
Confirmed absent: `sftp` appears three times in the repository, all three in
planning prose, zero times in code.

The argument is not effort. SFTP here would be a SECOND authorization system
over the same bytes: its own credential store (zenit-auth password hashes are
not available to it), its own capability mapping, its own path confinement, and
it would be invisible to the activity log that item 7's delegation model depends
on. Pterodactyl demonstrates the cost of two surfaces in its own documentation:
mounts "do not appear in the Panel's file manager, nor are they accessible via
SFTP" -- the two views of one filesystem disagree.

What we accept losing: bulk transfer of large trees and rsync-style workflows.
Source: https://pterodactyl.io/guides/mounts.html

---

## Capabilities Pterodactyl has that we deliberately do not

## 16. Host bind mounts ("Mounts") -- REJECTED

Pterodactyl lets an admin declare a host path and mount it into chosen servers.
Rejected because it is structurally incompatible with the property this tier is
built around: a workload must be MOVABLE. Pterodactyl's own documentation states
that servers sharing a mount only share its contents when they are on the same
node, and that mounts are not synchronized between nodes -- so a mounted server
cannot be transferred without silently changing what it sees. Cold migration and
host drain are load-bearing features here (item 12), and the device mechanism
(`instance_devices`: owner-labelled block volumes and NICs) gives the same
"extra storage" outcome with ownership, quota accounting and a destroy path.

A host bind mount would also put an operator-typed host path inside a tenant
container, which is the escape-hatch shape the confinement work exists to close.

Sources: https://pterodactyl.io/guides/mounts.html

## 17. A Wings-style per-node agent -- REJECTED

Pterodactyl's architecture is panel + Wings daemon per node. We are agentless:
the controller speaks the Docker API and the Incus HTTPS API directly, with
host trust pinned (`HostPins`) and every operation fenced by a host lease
(`HostLeases`). Adding an agent would mean shipping, versioning and upgrading a
second binary across the fleet, and giving it a long-lived credential on every
host -- and it would not remove the need for the fence, because the fence
protects against two CONTROLLERS, not against a slow daemon.

What we accept losing: node-local behaviour that survives a control-plane
outage. A crashed workload is restarted by the controller's crash policy, so a
controller outage means no automatic restarts until it returns.

## 18. Docker Swarm / multi-node orchestration -- REJECTED

There is no Swarm, Kubernetes or scheduler-with-rescheduling anywhere in this
repo, by choice. Placement is explicit and resource-aware
(`HostAdmission`/placement), and a move is an operator act (migrate/drain), not
an automatic reschedule. Automatic rescheduling requires either shared storage
or accepting data loss on move; we have neither, and a scheduler that silently
moves a stateful game server is worse than one that refuses.

## 19. RCON -- REJECTED

No RCON client exists (verified: no word-boundary match for `rcon` in any source
file). Console control is the stdin/stdout console session, which is one
mechanism for every workload kind rather than a per-game protocol, is redaction-
aware at ingest, and is capability-gated as `console` separately from `exec`. An
RCON lane would be a second command path with its own auth (a shared password in
the server config), invisible to the console redaction and to the activity log.

## 20. Billing, suspension and client "resource plans" -- REJECTED

Pterodactyl's ecosystem assumes a billing add-on that suspends servers on
non-payment. Out of scope: quotas here are operator-set caps
(`InstanceQuotaModel`), not a commercial product, and there is no billing tier
to suspend from. A `suspended` state that only billing writes would be a state
machine with one writer and no test.

## 21. Live migration -- REJECTED (cold move is the substitute)

Already argued in `proxmox-use-inventory.md`; restated here because the game
audience feels it most. Live migration needs shared storage or a memory-state
transfer path; the cold move (`InstanceMigrations`) exports, imports,
re-attributes ownership and rejoins isolation, and a game server's players
reconnect. Pterodactyl's own transfer is also a cold copy, and its known defect
(symlinks are not transferred) is one this lane does not have because it moves
the whole instance rather than a file tree.

## 22. "restart" as a primitive -- REJECTED, and the consequence is stated

`InstanceApi`'s power lane and `InstancePowerAction`'s schedule step both spell
restart as `stop` then `deploy`: two separately fenced operations with a window
between them. This is deliberate -- a single fenced "restart" would have to hold
one fence across a create -- but it is a real difference from a panel where
restart is one button with one outcome. It is now VISIBLE rather than hidden: as
of 2026-08-08 the activity log records the two rows the operation actually is.

---

## The localization clause of the Phase 6 gate is NOT met

The gate says "every page and error is localized". It is not. **Ten page classes
across eleven call sites** still build their page title by concatenating an
English literal:

| file | line | expression |
| --- | --- | --- |
| `server/cms/InstanceDevicesPage.java` | 57 | `name + " - Devices"` |
| `server/cms/InstanceSchedulesPage.java` | 58 | `name + " - Schedules"` |
| `server/cms/InstanceScheduleStepsPage.java` | 72 | `name + " - Steps"` |
| `server/cms/DnsZoneSecondariesPage.java` | 59 | `origin + " - Secondaries"` |
| `server/cms/DnsZoneFilePage.java` | 38 | `origin + " - Zone file"` |
| `server/cms/DnsZoneRecordsPage.java` | 71 | `origin + " - Records"` |
| `server/cms/DnsZoneRecordsPage.java` | 137 | `origin + " - Records"` (2nd path) |
| `server/cms/SiteDevSessionsPage.java` | 56 | `name + " - Dev sessions"` |
| `server/cms/SiteDatabasesPage.java` | 83 | `name + " - Databases"` |
| `server/cms/DatabaseRestorePage.java` | 41 | `"Restore " + name` |
| `server/cms/SpamserviceSampleAnalysisPage.java` | 44 | `"Sample " + value(...)` |

The plan's 2026-08-04 STATUS block named NINE of these and deferred them as
"admin-only pages, deliberately out of this wave's scope". The real number is ten
classes: `InstanceDevicesPage` was written after that block and inherited the
defect, which is what an unclosed sweep does.

Adjacent, same defect, different surface -- recorded so the decision is explicit
rather than forgotten:

- `server/cms/SiteProcessesPage.java:119` -- a panel heading, `"PID " + ...`.
- `server/proxy/ErrorPages.java:63` receives hardcoded English status titles from
  its callers (`render("502", "Bad Gateway", ...)` at `:49`). This one is
  PUBLIC-FACING, which is worse than an admin page.
- `server/docker/SiteContainerKind.java` `getDisplayName()` returns the literal
  `"Site container"` (a registry display name, a different mechanism).

A second bucket exists and is deliberately NOT counted here: nine pages that put
a bare record name in the title with no literal at all
(`InstanceConsolePage:73`, `InstanceFilesPage:55`, `InstanceExecPage:59`,
`InstanceStatsPage:49`, `InstanceFramebufferPage:50`,
`InstanceProvisioningPage:86`, `StackServicesPage:86`,
`StackDeploymentsPage:61`, `TemplateContentsPage:67`). Read the gate strictly as
"the title must come from Microcopy" and those are non-compliant too. That
bucket also being nine is the likely source of the "nine pages" figure in
circulation.

**Verdict on the clause: OPEN.** It is one mechanical sweep -- the three pages
fixed in the 2026-08-04 wave are the template -- and until it lands the Phase 6
gate is not met no matter what the rest of this document says.

**STATUS (2026-08-08, later the same day): the clause is now MET, and the sweep
is ENFORCED rather than merely done.** All eleven call sites above plus the two
adjacent ones resolve through `CmsSupport.pageTitle(conduit, scope, name)` --
microcopy key `page_title` in each page's OWN scope, en AND nl. The three pages
the 2026-08-04 wave fixed were converted with them: their
`{page}_title`-in-scope-`site` spelling is DELETED, because two pages of one
record type (a zone's records and its zone file) cannot share a scope, and a
half-converted namespace is exactly what this document exists to refuse.
`SiteProcessesPage`'s `"PID " + ...` panel heading became `stored_log_heading`.

`ErrorPages` -- the public-facing one, and therefore the one that mattered -- is
localized off the visitor's own `Accept-Language`, parsed by the SAME
`AcceptLanguageMiddleware.parse` the framework middleware uses (there is no
Conduit on the proxy's raw Undertow exchange), falling back to the default
content locale. The two operator-authored `proxy.*_message` settings still WIN
when set -- a site owner's own copy is not translatable -- but their shipped
English DEFAULTS are gone: blank now means "use the localized text", which is
what a Dutch visitor to a misconfigured domain was previously denied.

The SECOND bucket (nine pages whose title is a bare record name) is DECIDED, not
deferred: those are COMPLIANT. A title that is nothing but the record's own name
carries no translatable copy, and an instance/site/zone name is user data that is
never translated -- the plan's own localization rule. The guard encodes exactly
that: a title with no string literal at all passes, any literal mixed into one
does not.

Enforcement, because the eleven call sites prove a sweep does not hold on its own
(`InstanceDevicesPage` was written after the nine-page list and inherited the
defect from its neighbours):

- `src/browserTest/java/be/elevenways/hohenheim/test/PageTitleLocalizationTest.java`
  -- a declared `protoblast-source-guard` rule (judge mode) over the whole
  `server/cms` package plus `ErrorPages.java`, and a second test resolving all
  thirteen title scopes and all six proxy-error keys in en AND nl. Hermetic. **[test]**
- `MicrocopyCatalogParsesTest.theEnglishAndDutchCatalogsCoverTheSameKeysAndScopes`
  -- en/nl symmetry per (key, FILTER SET), not per bare key. It immediately found
  a pre-existing asymmetry (`status`, scope `spamservice`, en only), now fixed. **[test]**
- COUNTERFACTUALS: restoring `name + " - Devices"` on `InstanceDevicesPage` and
  `"Bad Gateway"` in `ErrorPages` fails the guard, naming both at file:line;
  deleting one nl entry fails both the resolution test (`nl
  page_title[scope=dns_zone_file] -> 'page_title'`) and the symmetry test.

---

## Genuinely OPEN items, ranked

Value against effort, with prerequisites, for the game-panel claim specifically.

1. **A hermetic fake for the stats and log streams** (item 13). Value: highest --
   live stats has exactly ONE test, that test assumes three times, and a
   regression in it is invisible. Effort: medium. Prerequisite: none;
   `FakeNativeDaemons` is the pattern and it already lives in the same package.
   **DONE 2026-08-08** -- `FakeNativeDaemons` grew the stats and console lanes as
   driver capabilities and `InstanceObservabilityContractTest` covers decode,
   sharing, ring bound, teardown, ingest redaction, the upserted history row, the
   retention sweep and every refusal. It also found and fixed a retention sweep that
   deleted live episodes. Kept in place with its number: the ranking is history, not
   a worklist. See item 13's STATUS block.
2. **Localize the ten page titles** (above). DONE 2026-08-08 -- thirteen call
   sites resolved through one `CmsSupport.pageTitle` seam, the public-facing proxy
   errors negotiated off Accept-Language, and a source guard that fails on the
   next concatenated title. Kept in place with its number: the ranking is history,
   not a worklist. See the localization section above.
3. **A per-owner memory quota** (item 11). Value: high -- without it the
   instance-count cap does not bound what one tenant consumes, and memory is
   Pterodactyl's own quota unit. Effort: low-medium: a column, a setting, one more
   reserve dimension on an existing ledger at an existing hook site.
   Prerequisite: none.
   **DONE 2026-08-08** -- exactly that shape (M088, `MAX_MEMORY_MB`,
   `max_memory_mb_per_owner`, the dimension inside `InstanceQuota`), with the
   deferred question answered: an unbounded workload is charged its KIND's declared
   footprint, which is also the cap its driver applies. Kept in place with its
   number: the ranking is history, not a worklist. See item 11's STATUS block.
4. **Instance-to-database linkage with credential injection** (item 10). Value:
   high for this claim -- a plugin wanting MySQL is the common game case. Effort:
   medium. Prerequisite: `SiteDatabaseModel`'s two-sided authority rule as the
   template and the instance variable mechanism as the injection target.
   **DONE 2026-08-08** -- `instance_databases` (M087) with the two-sided rule
   (`config` on the instance, `manage` on the database), derived-at-resolve
   injection, its own `idblink` link-network lane on the discovered pre-start hook,
   and the in-use refusal widened to workloads. Kept in place with its number: the
   ranking is history, not a worklist. See item 10's STATUS block.
5. **Close the `TenantInstanceSurfaceTest` status-only hole** (item 5). Value:
   medium-high against its cost -- it is the exact shape this document exists to
   flag, sitting in our own suite. Effort: trivial, one absence assertion.
   Prerequisite: none.
6. **Hermetic coverage of `reinstall`** (item 3). Value: medium -- a destructive
   operator-only path with zero non-live proof. Effort: medium; the fake harness
   needs an install-script runner seam. Prerequisite: item 1.
7. **Delegable restore** (item 9). Value: medium -- a tenant who may TAKE a backup
   cannot use one. Effort: medium, but it is a policy decision first (a restore
   spends quota and placement). Prerequisite: a decision, not code.
8. **A `@Tag`-separated live lane** (cross-cutting). Value: medium -- it turns "67
   classes can skip green" from something you must remember into something the
   build reports. Effort: low. Prerequisite: agreement on whether CI should FAIL
   when a live class skips.

---

## Incumbent capabilities that would be actively WRONG here

Not "not yet", but "would make this product worse":

- **Host bind mounts.** They make a workload unmovable, and movability -- drain,
  cold migration, resource-aware placement -- is load-bearing here in a way it is
  not in Pterodactyl. Item 16.
- **SFTP with panel-password credentials.** A second authorization system and a
  second audit blind spot over the same bytes. Item 15.
- **A per-node agent holding a long-lived credential.** It would not remove the
  host-lease fence (which protects against two CONTROLLERS, not a slow daemon)
  and it would add a fleet-wide upgrade obligation. Item 17.
- **RCON.** A second command path authenticated by a shared password in a config
  file, invisible to the console redactor and to the activity log. Item 19.
- **A billing-owned `suspended` state.** A state machine with one writer, no test
  and no product behind it. Item 20.
- **"Restart" as a single fenced primitive.** It would have to hold one fence
  across a container recreate, which is exactly what the fence exists to refuse.
  Item 22.
- **A large egg catalog.** Shipping hundreds of templates means owning CVE
  response and upgrade paths for software we do not operate.


---

## What hohenheim does that Pterodactyl structurally cannot

The plan's framing is "one beautiful whole, not three tools in sequence". These
are the properties that come from being one control plane, and that a panel
bolted next to a proxy next to a DNS provider cannot have at any amount of
effort:

1. **One authorization vocabulary across every tier.** A record grant
   (`RecordGrants`, `(subject, model, record, capability)`) is the SAME mechanism
   for an instance's `console`, a site's `manage`, a database's `credentials` and
   a DNS zone's records. Delegability is declared once per capability
   (`KnownCapability`; `exec` is structurally non-delegable and unreachable
   through `manage`'s umbrella). Pterodactyl's subusers and Coolify's team roles
   each cover one product's objects; neither can express "this player-admin may
   restart the backend and edit its SRV record but never run a shell".

2. **Cross-tier port and route ledgers.** One `PortLedger` arbitrates host ports
   across instances, docker sites, managed databases and stacks, with claims
   parked rather than deleted on an unverifiable outcome. Two products sharing a
   host cannot do this; they collide and the loser fails at container start.

3. **Authoritative DNS in the same transaction as the thing it names.** The DNS
   tier is a real authoritative server (`DnsServer`, DNSSEC signing, AXFR, TSIG,
   dynamic updates), so a game-domain mapping materializes SRV/A records and
   Velocity forced-hosts config from ONE write, with owner+source metadata so
   reconciliation deletes only its own output. Both incumbents delegate DNS to a
   third party and therefore cannot make that atomic or ownership-safe.

4. **Fenced, lease-guarded outcome writes.** Every runtime outcome is written
   conditionally on a host lease fence (`HostLeases`, `InstanceOperationGuard`),
   so two controllers cannot both believe they own a workload. Neither incumbent
   has a fence: Wings is authoritative per node, and Coolify's deploys race.

5. **One audit trail with one attribution model.** `ActivityLog` plus
   `Accountability` stamps actor, label, IP, user agent and ORIGIN (web/api/
   system) on every recorded act across all tiers -- and as of 2026-08-08 the
   same operation records identically whether it came from the panel, `/manage`
   or the API. Products that grow an API later usually audit only one of them;
   that was this product's own defect until this pass fixed it.

6. **One quota ledger.** Instances, disk GB, NICs and databases are charged
   against per-owner caps through the core reservation ledger, and the
   reservation MOVES with a migration. Two products cannot share a budget.

7. **Trust pinning and host admission as a state machine.** Host keys are pinned,
   a contradiction quarantines with its own sticky columns that only a repin
   clears, and an admitted host must have PROVED its kernel-truth lane before it
   may accept tenant workloads. This is not a feature either incumbent models.

8. **Localization as a hard requirement.** Every operator-facing string is a
   microcopy key with en+nl catalogs, scored and filtered. Pterodactyl has
   translations; Coolify is English-first. The gap that used to sit here (page
   titles built by concatenation) is closed and source-guarded as of 2026-08-08.

---

## Findings from writing this document

Four documentation defects were found by reading code rather than plan notes. All
four are docblock or plan-sentence corrections; none was a behaviour change, and
the behaviour changes this pass DID make are in the activity-log commit, not here.

1. `HohenheimAccess.java:320` claimed read was the only ORDINARY capability on
   the instance model. Four are (`view`, `console`, `power`, `files.read`).
   Corrected in place.
2. `InstanceInstalls.java:30-33` claimed no path there touches volumes except a
   clear policy's wipe; the clear branch destroys the WORKLOAD too. Corrected.
3. `InstanceQuotaModel.java:16-20` described the table as an instance-count
   override; it carries three caps. Corrected.
4. (CLOSED 2026-08-08 -- see item 10's STATUS block.) The plan's minimum-claim
   sentence lists "per-instance database allocation" as a
   flat clause. What shipped in `ccd1bd5` is per-TENANT allocation with site-side
   injection and no instance-to-database link at all. Recorded as item 10 PARTIAL
   rather than quietly counted as met.

One figure in circulation was also corrected: the number of browserTest classes
that can skip green is not 33. **Sixty** gate specifically on a Docker socket, an
Incus endpoint or ssh; **sixty-six** including kernel-capability and local-binary
gates; **67** files contain an assume call at all. Several gates sit in
`@BeforeAll`, so one missing image skips an entire class, and several gate on a
PRE-PULLED IMAGE, so a host with a working daemon and a cold cache still reports
green.

**Update 2026-08-08:** those figures are unchanged -- no gate was removed, because a
live class that proves something a fake cannot must keep running where a daemon
exists. What changed is that the three capabilities whose ONLY proof sat behind
those gates (the release state machine, live stats and logs, ACME acquisition) now
also have hermetic twins: three new classes, 11 journeys, ~116 state assertions that
execute on any machine. The cross-cutting `@Tag`-separated live lane is still open,
and is now MORE worth doing, not less: with twins in place, a CI that fails when a
live class skips no longer risks hiding a whole capability behind one missing image.

---

## Verdict

The inventory is CLOSED: every clause of the minimum claim has a decision and its
evidence.

**The Pterodactyl replacement claim has NO remaining blocking clause** as of
2026-08-08. The list below is kept with its original numbering; all three of its
clauses are now closed and say so:

- item 10, per-instance database allocation, was per-TENANT only -- **CLOSED
  2026-08-08** (`instance_databases`, M087; see item 10's STATUS block);
- item 11, resource quotas, had no memory dimension -- **CLOSED 2026-08-08**: a
  per-owner memory budget (M088) charged at the number the driver caps, proven
  hermetically against races, refusals and every release path. This was the last
  blocker on this claim; see item 11's STATUS block, including the argued decision
  that an unbounded workload participates at its kind's declared footprint;
- item 13, live stats and logs, had one test that assumed three times -- **CLOSED
  2026-08-08**: a hermetic fake for both streams, 34 state assertions with no
  daemon, beside the live classes which stay because a fake cannot discover an
  Engine API change. It also fixed a retention sweep that deleted live episodes.
  See item 13's STATUS block for what the hermetic half cannot prove.

The fourth blocker, the Phase 6 gate's localization clause, is MET as of
2026-08-08 (see its section above): thirteen call sites resolved through one
microcopy seam, the public-facing proxy errors negotiated off Accept-Language,
and a source guard that fails on the next concatenated title.

Everything else is either IMPLEMENTED with a hermetic state-asserting test, or a
REJECTION argued from evidence about this product's shape. What would block
calling this a general Pterodactyl replacement for someone ELSE, in one sentence:
SFTP, host mounts and RCON are rejected rather than delivered, and the template
catalog holds four entries against Pterodactyl's egg ecosystem -- both correct for
the fleet we run and wrong for anyone who needs one of them.
