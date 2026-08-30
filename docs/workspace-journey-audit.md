# Workspace journey audit: "I want my own box"

Status: AUDIT, 2026-08-23. Read-only; no code was changed. Line numbers refer to
the hohenheim worktree on 2026-08-23. Method: code trace of the nine journey
steps, cross-checked against docs/phase0-design.md and
docs/phase2-release-surface-design.md so nothing already designed is
re-proposed.

Verification caveat, stated once: `zd_verified` reports NO receipt at the
current fingerprint -- run #4729 (2026-08-22) is PASSED_BUT_STALE because
`LiveNamespaces.java` and four framework files moved since (another agent owns
test infra tonight). Every `*Live*`/`*Incus*` class plus `ContainerHardeningTest`
is declared non-hermetic in `.zenit-dev.json` and can never carry a reusable
receipt; whether they are green on the CURRENT tree is therefore UNVERIFIED
here. Test names below are cited as "the test that proves it" in the sense of
"exists and asserts exactly this", not "ran tonight".

Verdict scale: WORKS (end to end, in code, with a test) / PARTIAL / MISSING.

## The nine steps

### 1. Create a workspace -- WORKS (one screen; two soft spots)

The create form is `InstanceResource.buildFormSpec()`
(`server/cms/InstanceResource.java:113-183`): kind choice CARDS
(`Select.Presentation.CARDS`, options narrowed to authorable kinds :124-128),
name, host `RelationPick` narrowed live by the chosen kind
(`HohenheimPickRules.KindHostRules`, `common/.../HohenheimPickRules.java:60-90`:
runtime in the kind's declared set AND `volume_backend != none` for
volume-mounting kinds), runtime-image pick that only resolves for kinds that
use one (:145-152), environment pick, then the per-kind settings sub-form.
`WorkspaceKind` (`server/instance/WorkspaceKind.java:66-144`) builds its schema
from `GitSourceSchema` (repo/provider/branch) plus start_command,
container_port, home_quota_mb, environment_variables, memory/cpu limits -- with
the long tail FOLDED into named sections (Build / Deployment / Runtime,
:135-144) and crash policy + backup target in an Advanced fold (:179-181).
One screen; the visible decisions are kind, name, host, image, repo, command,
port. That matches the phase-0 section 6 promise.

- Host narrowing is live AND server-enforced: the submit re-narrows
  (`relation_out_of_scope`); proven by
  `InstanceCreateFlowTest.kindCardsDriveTheDependentPicks` and
  `.handPostedOutOfScopeHostIsRefused`
  (`src/browserTest/.../InstanceCreateFlowTest.java:82,169`). Sections fold
  without filtering the submit (same test class :200,:262).
- Templates are deliberately NOT on this form (AIDEV-NOTE at
  `InstanceResource.java:153-158`); template creation is
  `InstanceFromTemplatePage`, which is also the ONLY create lane a tenant has
  (`ManageInstanceResource.java:108` `creatable() == false`;
  `ManagePanel.java:167` mounts the template page). "I want my own box" as a
  SELF-SERVE tenant journey exists only if an operator authored a workspace
  template first.
- Soft spot A: the runtime-image pick is `clearable(true)`
  (`InstanceResource.java:146`), and the requirement is only enforced at deploy
  (`RuntimeImages.requireFor`, `server/instance/RuntimeImages.java:69-84`,
  `runtime_image_required`). A workspace can be saved imageless and its first
  Deploy refuses. Test: `WorkspaceKindTest.aWorkspaceWithoutARuntimeImageRefusesByName`.
- Soft spot B: on a FRESH install the only host is the seeded local Docker
  daemon with `volume_backend = none` (the column default,
  `InitialMigration.java:293`), so the narrowed host picker resolves to ZERO
  options. What the empty narrowed picker renders to the user, and whether it
  names WHY (the reason lives on the Server overview page, not here), is
  UNVERIFIED (framework rendering); nothing in this repo passes a reason into
  it. See defect 6.

Seeded images: node-22 / node-16 / node-12 / node-10 / java-21 / debian-13 / static
(`RuntimeImageSeeder.java:47-66`), code-owned `sync`, LOCAL tags built from
in-repo `images/` contexts at first use on each host
(`RuntimeImages.ensurePresent` :121, Docker build :134, Docker-to-Incus import
:185). Test: `RuntimeImageSeedTest.theBuiltInsAreSeededAndBuildable`.

### 2. Placement vs volume_backend=none -- WORKS (refused with a named reason, and mostly prevented before that)

Three layers, all traced:

1. The FORM never offers such a host: `KindHostRules` adds
   `volume_backend NOT_EQUALS none` for kinds where `supportsVolumes()`
   (`HohenheimPickRules.java:83`, fed from
   `InstanceResource.java:137-140`); a hand-post is `relation_out_of_scope`.
2. The CHOOSER never picks one: `InstanceKindHandler.requirePlaceableOn`'s
   default (`InstanceKindHandler.java:266-271`) calls
   `VolumeBackends.requireQuotaCapableHost`, and
   `InstancePlacement.kindGateFor` (`InstancePlacement.java:312-325`) runs it
   per candidate, KEEPING the named refusal when it is the only reason nothing
   placed (:255-257). Tests:
   `InstancePlacementTest.aHostWithNoVolumeQuotaTakesNoWorkspaceOrApplication`
   (:681), `WorkspaceKindTest.aHostWithNoVolumeQuotaRefusesToCarryAWorkspace`
   (:232).
3. The refusal text is actionable: `host_no_volume_quota` = "Host '{name}'
   cannot enforce a volume quota (backend: {backend}), so it cannot run a
   {kind}. Mount a btrfs or zfs filesystem at the volume root, or pick another
   host." (`server/resources/META-INF/microcopy/en.json`). The host page
   carries the standing notice (`ServerOverviewPage.java:140-150`,
   `volume_backend_none_title/body`: mount btrfs/ZFS at the volumes directory
   and re-run preflight). The backend is DETECTED, never declared: preflight
   probes the real data root (`HostPreflight.java:104`,
   `VolumeBackends.probe/classify` :94-154, fail-closed to NONE).

So: the answer to the brief's question is "narrowed at the picker AND refused
by name at placement", not "noticed on a page only". Two genuine gaps remain:
the empty-picker first-run experience (defect 6) and the zfs lie (defect 4:
the refusal text and the host notice both tell the operator ZFS works, but
`VolumeOperations` refuses ZFS by name -- see step 3).

Note on the admin explicit lane: `InstancePlacement.forActor` returns an
admin's `requested` host without the kind gate (:150-163), but that lane is
only reached by `InstanceTemplates.createFromTemplate` (:231) and
`TenantDatabases`; the plain form create stores the picked host directly and
the picker rule already excluded none-backend hosts, so the admin cannot
reach a none host through any UI. A host whose backend DEGRADES after create
(re-probe) is caught at deploy by `prepareForDeploy` ->
`InstanceVolumes.mountsFor` -> `VolumeOperations` refusing by name.

### 3. The volume -- PARTIAL (btrfs is real end to end; zfs/xfs are declared but refuse; snapshot real on btrfs only)

- Declaration: the home volume is an ordinary `instance_volumes` row named
  `home` mounted at `/home/site`, declared/updated at every deploy
  (`WorkspaceKind.ensureHomeDeclared` :290-296, quota from `home_quota_mb`);
  deliberately NOT a special case, so it gets the Volumes tab, usage, quota and
  snapshot like any volume (AIDEV-NOTE :286-289). Test:
  `WorkspaceKindTest.theHomeVolumeIsAnOrdinaryDeclarationCarryingTheDeclaredQuota` (:361).
- Creation + ownership: `prepareForDeploy` (:256-280) materializes directories
  BEFORE the container exists (the root-owned-bind trap is documented at
  `InstanceKindHandler.java:198-211`), chowns the directory (not recursive --
  `VolumeOperations.own` docblock) to `WorkspaceUids.forInstance(id)` =
  `volume_uid_base(200000) + instance id`, a pure number, range-checked against
  host accounts and Incus subuids (`WorkspaceUids.java:47-72`). On Incus the
  owner is `subuid base + namespace uid` (`incusHostUid` :90, measured
  2026-08-22, AIDEV-NOTEs carry the raw.idmap rejection).
- Mounting: Docker = bind in the spec (`WorkspaceKind.specFor` :221-229,
  container `User` = the uid, `DockerInstanceRuntime.java:985-986`); Incus =
  disk device + the image's `/sbin/hohenheim-init` dropping to the uid, command
  and uid in the environment (`WorkspaceKind.specFor` :233-243,
  `IncusInstanceRuntime.WORKSPACE_INIT` :450-470).
- Quota and snapshot REALITY: `VolumeOperations.forBackend`
  (`server/host/VolumeOperations.java:69-75`) implements ONLY btrfs
  (subvolume + `btrfs quota enable` + `qgroup limit`,
  `BtrfsVolumeOperations.java:45-58`); ZFS, XFS_PRJQUOTA and NONE all get
  `UnimplementedVolumeOperations`, every operation refusing
  `volume_backend_unimplemented` ("only btrfs ... today"). Yet
  `VolumeBackend.ZFS.supportsQuota() == true` and
  `XFS_PRJQUOTA.supportsQuota() == true`
  (`common/.../host/VolumeBackend.java:29-37`), so placement and the picker
  ACCEPT a zfs/xfs-pq host whose first deploy then refuses. Snapshot: real on
  btrfs (`snapshotAll` refuses non-snapshot backends by name,
  `InstanceVolumes.java:174-206`). Tests: `InstanceVolumesTest` (:58,:92,
  derivation + named refusal), `WorkspaceDockerLiveTest` step 3 (real
  subvolume, real qgroup, real ownership) -- live lane, UNVERIFIED as run.
- Path containment: names are single path segments
  (`InstanceVolumes.requirePlainName` :331-338), the bind source must be under
  the volume root (`ContainerHardening.refuseEscapes` bind rule :495-528).

### 4. Start/stop/supervise -- PARTIAL (deliberate machinery exists; the DEFAULT workspace gets none of it)

- Start: Docker runs `bash -lc <start command>` under the image's tini-style
  entrypoint as the workspace uid (`WorkspaceKind.specFor` :244-247); Incus
  boots `hohenheim-init` which runs the command as the uid and restarts per
  crash policy (images/README contract). Command = instance override, else
  image default, else `sleep infinity` so shell/files have something to attach
  to (`startCommandOf` :299-309).
- Supervision: `InstanceConsoles.prepare` (`InstanceConsoles.java:101-136`)
  opens a console watch ONLY when the template declares a readiness line or a
  stop command, or `crash_policy == restart`. With `restart`, ANY exit without
  an observed stop redeploys, flap-protected (:481-505,
  `FLAP_THRESHOLD` :51). Crash policy DEFAULTS to `none`
  (`InstanceModel.java:280-285`).
- "Ready" means: nothing, for the default workspace. Readiness is
  TEMPLATE-declared only (`InstanceReadiness.declaredKind`,
  `InstanceReadiness.java:54-62`: no template -> null -> no probe;
  port/http probes exist for templated records, 90s window). The phase-0
  promise (4.3: "absent a template, port-open on the declared port with the
  runtime image's default timeout") is NOT implemented -- deploy stamps
  RUNNING the moment the container starts. Test of what exists:
  `WorkspaceKindTest.declaredReadinessIsWaitedForAndAnUndeclaredOneIsNot` (:429)
  -- i.e. the gap is TESTED-AS-INTENDED, so closing it means changing that test.
- Consequence: a default workspace (no template, crash none) has NO watch, so
  a crashed process leaves the record stamped `running` forever -- the list
  status pill and the Overview badge both render the STORED column
  (`InstanceOverviewPage.java:247`), and no scheduled task reconciles instance
  status (`server/task/` has no such sweeper). Defect 2.

### 5. Git clone + build -- PARTIAL (the mechanism is complete and well-tested; NO surface can trigger it except a webhook)

`WorkspaceBuilds` (`server/instance/WorkspaceBuilds.java`) is THE workspace
deploy verb: clone/fetch into `/home/site/app` INSIDE the container as the
workspace uid (:127-186), build with the instance's or image's build command
in the checkout (:193-213), restart LAST so no live process reads a
half-written checkout (:97-100), commit statuses via `DeployStatuses`,
20-minute cap, POWER capability gate (:78).

Credentials: provider tokens travel ONLY as `GIT_CONFIG_*` exec environment
(`GitProviders.credentialEnv`, consumed :137-147); the URL carries no
credential and no `-c` is passed to clone because `git clone -c` PERSISTS into
`.git/config` (AIDEV-NOTE :149-153). Nothing persists in the volume;
`WorkspaceKindTest.theGitCredentialTravelsOnlyInTheExecEnvironment` (:293)
greps the volume to prove it, and both live lifecycle tests re-prove it against
real daemons. Residual: a RAW `repository_url` with an embedded
`user:token@` (the provider-less lane, :130-131) WOULD persist in
`.git/config` -- user-supplied foot-gun, the field is at least `secret()`.

The hole: the ONLY caller of `WorkspaceBuilds` outside its own tests is
`GitWebhookHandler` (:269, `deployQuietly`). The Deploys tab hides for
workspaces (`InstanceDeploymentsPage.visibleFor` :56-58 = release-managed
kinds only; `WorkspaceKind` does not override `releaseManaged()`, default
false at `InstanceKindHandler.java:77`). The row-action "Deploy" runs
`InstanceService.deploy` = container create/start ONLY (no checkout -- the
workspace branch in `InstanceService.deploy` :121 fires only for
release-managed kinds). So: a user creates a workspace, fills in the repo,
presses Deploy, and gets a running container with an EMPTY `/home/site` and no
way to pull their code short of configuring a webhook in their forge. Defect 1,
the most user-damaging finding of this audit.

### 6. Shell -- PARTIAL (exec lands as the right uid; there is no interactive shell, and none a tenant may use)

What exists:
- Console tab = attach to the PRIMARY process's stdio (docker attach shape;
  `InstanceConsoleHandler` -> ghostty). For a workspace this is the stdin of
  `npm start`, not a shell.
- Exec tab = ONE-SHOT commands, 2-minute cap, output tail 16KB
  (`InstanceExec.java:37-41`), running as the workload's own uid because
  `DockerInstanceRuntime.runExec` passes `spec.runUser()`
  (:736-742 -- "an exec into a workspace lands as the same uid its files are
  owned by"); Incus exec likewise passes `spec.runUser()`
  (`IncusInstanceRuntime.java:56-71`). But EXEC is declared ADMIN-sensitivity
  with deliberately no /manage surface and no API lane
  (`InstanceExec.java:30-33`), and the tab hides+404s without the capability
  (`InstanceExecPage.java:53-55`).

So the workspace promise "shell into your box" is, today: an admin can run
one-shot non-interactive commands as the right uid; a tenant can type into
their app's stdin. No PTY, no login shell, for anyone. The runtime image even
declares `shell = /bin/bash` (`RuntimeImageSeeder.java:88`) that nothing
launches. Defect 3.

What stops the shell from reaching the host:
- Docker: the spec carries `ContainerHardening.SERVICE`
  (`WorkspaceKind.HARDENING` :79; profile at `ContainerHardening.java:102`,
  capability drops documented per-entry :107-167), `refuseEscapes` runs on
  every create (:362, :451-528): privileged/capAdd/binds/namespace keys are
  case-fold-refused, and a `Mounts` bind source must be under the volume root
  (:495-528). Process identity is a bare high uid with no host account
  (`WorkspaceUids` docblock).
- Incus: unprivileged is the default posture; only the explicit
  `incus-privileged` profile flips `security.privileged`
  (`IncusInstanceRuntime.applyManagedConfig` :443-448); the workspace declares
  `INCUS_HARDENING = "incus-unprivileged"` (`WorkspaceKind.java:82-83`) and the
  uid is a namespace id inside the subuid range.

UNPROVEN today: `ContainerHardeningTest`, `WorkspaceDockerLiveTest`,
`WorkspaceIncusLiveTest` and `InstanceFilesLiveTest` are all non-hermetic
(`.zenit-dev.json`), excluded from the default lane, and `zd_verified` shows
no receipt for the current tree (and can structurally never show one for
them). The live tests DO assert the load-bearing facts (exec as uid, host-side
ownership, git token absent from the volume, btrfs quota) -- but only a
`--all` run on daystrom/nightstrom proves them for this tree. Phase-0 section
10's own condition ("ContainerHardeningTest MUST move into a real lane before
any tenant shell ships") is satisfied in structure, not in evidence.

### 7. Files tab -- WORKS on Docker, honestly ABSENT on Incus

`InstanceFiles` (`server/files/InstanceFiles.java`) is the one funnel:
browse/read/write/upload/download/rename/delete/mkdir inside the instance's
OWN volumes only. Capability split `files.read` vs `files.write` asked
separately on the service (:73-77 and class docblock); the tab hides+404s
without `files.read` (`InstanceFilesPage.visibleFor` :63-71) and renders write
controls only with `files.write` -- affordance on top of the gate, never
instead. Containment is a documented three-layer argument (class docblock
:39-71: daemon-scoped archive API measured against Docker 29.6, lexical
canonical-spelling check in `InstanceFilePath`, lstat walk of every ancestor;
the in-container TOCTOU is argued harmless). Ceilings: editor/read cap
`files.max_file_kb` default 8 MB (:406-417), listing cap `files.max_entries`,
inline editor offered only under 512 KB (`InstanceFilesPage.java:46`); uploads
ride the framework's HTTP body cap (AIDEV-NOTE at
`InstanceFileEndpoints.java:218`). Managed config-file rows render read-only
because deploy re-stages them (Entry.managed :96-100).

Incus: the driver does not implement `InstanceFileSupport`, and the tab says
so as a named state, not an error (`isSupported` :104-116,
`InstanceFilesTabGateTest.anIncusWorkloadStatesTheRuntimeHasNoFileLaneYet`
:140). An Incus workspace therefore has NO file management -- combined with
step 6 (no shell) and defect 1 (no deploy button), an Incus workspace is
currently a box you can neither fill, browse, nor shell into from the product.
Tests: `InstanceFilesTabGateTest` (gates), `InstanceFilesLiveTest` (journey +
containment matrix against the real daemon; live lane), `InstanceFilePathTest`.

### 8. Exposing it -- PARTIAL (one click to a prefilled site form; the HOSTNAME is a second, undirected trip)

The Expose row action (`InstanceResource.exposeAction` :469-487) is offered
exactly where the routing tier can serve (kind declares
`supportsSiteUpstream()`, `WorkspaceKind.java:181`; admin-only) and opens the
site create form with `upstream_kind=hohenheim:instance&instance_id=N`
prefilled (`SiteResource.createValues` :132-149, render-time only, full
coercion on submit). The site form (:89-125) is one screen: name, upstream
kind cards, the instance pick (narrowed to exposable kinds via the SAME
`kindsWhere(supportsSiteUpstream)` declaration -- one vocabulary home),
settings (port name/scheme/websocket/timeout,
`InstanceUpstreamKind.java:41-77`), enabled. Routing resolves the serving
container's published loopback port, honest 503 when nothing serves
(`InstanceUpstreamKind` docblock :28-34, `InstanceUpstreamHandler`).

The gap: a site's HOSTNAMES are `site_domains` child rows
(`SiteResource.java:79`, hostnames a virtual column; subpages :672-679 carry
`SiteDomainsPage`). The create form has NO hostname field and nothing after
save points at the Domains tab, so "expose" actually ends with a site that
serves nothing reachable until the user discovers Domains, adds a hostname,
and DNS/TLS follow. Count: Expose click -> site form -> save -> find Domains
tab -> add hostname = two screens plus one unguided discovery. Defect 5.
Cannot be done FROM the workspace page beyond the first click; the Overview
does show "Exposed by" once sites exist (`exposed_by` microcopy,
`InstanceOverviewPage`).

### 9. Deleting it -- WORKS, with one silent survivor

Two verbs, correctly separated:
- Delete = verified destroy (`InstanceResource.deleteRow` :428-432 ->
  `InstanceService.destroy` :287-353): container removed or observed absent
  (named refusal keeps the record on an unreachable daemon), port claims
  RELEASED FULLY (:320, pre-allocated reservations die with the instance),
  record soft-deleted with the delete recorded as `destroy`
  (`ActivityLog.withAction` :335), record schedules deleted explicitly (:340,
  soft delete fires no remove hooks), game-domain DNS/forced-hosts rows
  deleted (:345), database links + link networks deleted (:350). The
  confirmation states the one fact that matters: data is KEPT
  (`deleteConfirmation` :439-443). Capacity booking is released by the
  beforeWrite hook on the deleted_at transition (AIDEV-NOTE :278-282).
- Delete with data = separate typed-confirm action
  (`destroyWithDataAction` :533-562) -> `destroyWithData` (:682-703), which
  deliberately also works on an ALREADY-destroyed record ("remove its files
  from last week" -- AIDEV-NOTE :691-695), removing every volume via the
  backend (`InstanceVolumes.destroyAll` :242-269). Per-volume typed-confirm
  delete exists on the Volumes tab too (`InstanceVolumeResource.java:165,269`).

Leak check: uid is arithmetic, never ledgered, never reused by design
(`WorkspaceUids` AIDEV-NOTE). Volumes survive an ordinary destroy BY DESIGN
and surface as reconciler orphans. The one silent survivor: any SITE whose
`instance_id` names the destroyed instance stays enabled with its hostname,
DNS and certificate, serving 503 forever -- destroy neither warns about it,
disables it, nor lists it in the confirmation, and nothing on the site side
flags "upstream destroyed" beyond the honest 503. Defect 9.

## Ranked defects (most user-damaging first)

1. A workspace's git deploy is unreachable from any surface. Repro: create a
   workspace with a repository, press Deploy -> running container, empty
   /home/site; the only `WorkspaceBuilds` caller is
   `GitWebhookHandler.java:269`. Fix belongs: a workspace Source/Deploys tab
   (or a deploy-now row action) calling `WorkspaceBuilds.deploy`; the natural
   seam is widening `InstanceDeploymentsPage.visibleFor`
   (`server/cms/InstanceDeploymentsPage.java:56`) with a workspace branch in
   its deploy control, or a sibling page. Size: M. (Phase-2's release-surface
   design covers APPLICATIONS only; it does not close this.)
2. A crashed default workspace reports Running forever. Repro: workspace with
   crash_policy none (the default) and no template; kill the process; list
   pill and Overview badge (`InstanceOverviewPage.java:247`) keep the stamped
   status because no watch opened (`InstanceConsoles.prepare`
   :101-136 returns null) and no task reconciles instance status. Fix
   belongs: either always-watch for workspace kinds in
   `InstanceConsoles.prepare`, or a status-reconcile sweeper beside
   `server/task/ReconcileDockerResources.java`. Size: M.
3. No interactive shell, and nothing a tenant may run at all. The design's
   "shell into the box as the workspace uid" is today an admin-only one-shot
   exec (`InstanceExec.java:30-41`) plus app-stdin console. Fix belongs: a
   PTY exec lane (docker exec -it shape) on `ExecSupport` +
   `InstanceConsoleHandler`, gated by a NEW delegable capability distinct from
   admin `exec` -- this is a security-boundary decision for Jelle, not
   something to slip in. Size: L.
4. ZFS and XFS-prjquota are offered by placement and refused by deploy.
   Repro: host whose data root probes `zfs` -> picker offers it, placement
   accepts it (`VolumeBackend.java:33-37` declares quota support), first
   deploy dies with `volume_backend_unimplemented` ("only btrfs") from
   `VolumeOperations.java:73`; the refusal text and the host notice both
   RECOMMEND zfs. Fix belongs: one fact home -- either implement
   `ZfsVolumeOperations` or add `isImplemented()` to `VolumeBackend` and make
   `requireQuotaCapableHost` (`VolumeBackends.java:162`) and the microcopy
   read it. Size: S to gate honestly, M to implement zfs.
5. Expose ends without a hostname and without a pointer to one. Repro: Expose
   -> fill site form -> save -> nothing serves; the hostname lives on the
   Domains tab (`SiteResource.java:672-679`) and no message says so. Fix
   belongs: a first-hostname field on the site create (minting the first
   `site_domains` row), or at minimum a post-create redirect/toast into the
   Domains tab; `SiteResource.createValues`/create flow. Size: M.
6. Fresh-install dead end: the seeded local host is `volume_backend none`, so
   the workspace host picker narrows to zero options with no reason shown at
   the form (the explanation lives on `ServerOverviewPage.java:140-150`).
   What an empty resolved dependent pick renders is framework behavior --
   UNVERIFIED here, but nothing passes a reason into it. Fix belongs: an
   empty-state message on the narrowed pick naming the volume-backend
   requirement (framework: dependent-pick empty state; wiring: KindHostRules).
   Size: S-M (framework touch).
7. Tenant self-serve requires an operator-authored template. Not a bug
   (placement authority is deliberate, `ManageInstanceResource.java:108`), but
   the product story "I want my own box" is admin-mediated until either a
   seeded workspace template ships or /manage gains a curated workspace-create
   lane. Decision for Jelle; recording so no one "fixes" it by opening
   `creatable()`. Size: S (seed a template) once decided.
8. Imageless workspace saves, first deploy refuses. `InstanceResource.java:146`
   clearable pick vs `RuntimeImages.requireFor` deploy gate. Fix: require the
   image at coercion time for kinds where `usesRuntimeImage()`. Size: S.
9. Destroy leaves exposing sites live at a permanent 503 with DNS and certs.
   `InstanceService.destroy` cleans schedules/game domains/db links but never
   consults `sites.instance_id`. Fix belongs: destroy-time notice listing
   exposing sites (and an offer to disable them), `InstanceService.destroy` +
   the resource's dynamic confirmation. Size: S.
10. Provider-less `repository_url` with an inline token would persist in
    `.git/config` (only the provider lane gets env credentials,
    `WorkspaceBuilds.java:130-147`). A refusal of userinfo in the URL would
    close it. Size: S.

## Already GOOD -- do not rebuild

- The one-screen create with kind cards, live dependent host/image narrowing,
  server-side re-narrowing, and folded named sections
  (`InstanceResource.buildFormSpec`, `HohenheimPickRules`,
  `HohenheimFormSections`) -- exists, tested (`InstanceCreateFlowTest`,
  `WorkspaceKindTest`).
- The volume mechanism: `instance_volumes` rows, derived host paths, btrfs
  subvolume+qgroup+snapshot, per-volume typed-confirm delete, Volumes tab
  gated on `supportsVolumes()` (`InstanceVolumes`, `BtrfsVolumeOperations`,
  `InstanceVolumesPage`, `InstanceVolumeResource`).
- The uid story: arithmetic uid, Docker `User`, Incus namespace mapping with
  the measured raw.idmap rejection recorded in AIDEV-NOTEs
  (`WorkspaceUids`, `IncusInstanceRuntime.applyRunUser`).
- The volume-backend probe: kernel-truth detection per data root, fail-closed,
  stored with evidence, refusals naming the fix
  (`VolumeBackends`, `ServerOverviewPage` notices).
- Git credential hygiene: env-only tokens with a grep-the-volume test
  (`WorkspaceBuilds`, `WorkspaceKindTest:293`).
- The file manager's three-layer containment argument and its capability split
  (`InstanceFiles`), with a live containment matrix test.
- Both end-to-end live lifecycle journeys (`WorkspaceDockerLiveTest`,
  `WorkspaceIncusLiveTest`): create -> image built/imported on host ->
  subvolume+quota+ownership -> exec-as-uid -> host-visible writes -> checkout
  and build inside -> delete keeps the directory.
- The Expose affordance and the `instance` upstream with its honest 503
  (`exposeAction`, `InstanceUpstreamKind`, `SiteResource.createValues`).
- Delete vs delete-with-data separation with typed confirmation and the
  works-after-destroy data removal (`InstanceService.destroy/destroyWithData`).

## Duplication / two mechanisms over one job

- Two "deploy" verbs on one button vocabulary: `InstanceService.deploy`
  (container up) and `WorkspaceBuilds.deploy` (checkout+build+restart). For
  release-managed kinds the service already folds the second into the first
  (`InstanceService.java:121`); the workspace does NOT get that fold, which is
  defect 1's root. Recommendation: keep `InstanceService.deploy` as the one
  funnel and give it the same kind-branch for workspaces with a repository
  (canonical: the service funnel; `WorkspaceBuilds` stays the mechanism).
- Volume capability declared twice: `VolumeBackend.supportsQuota()` (facts)
  vs `VolumeOperations.forBackend` (implementations) disagree for ZFS/XFS.
  The enum is the declared vocabulary home; make implementedness a fact ON the
  member (or implement the members) so the picker, placement and deploy read
  one answer. (Defect 4.)
- Phase-0 8d proposed the Files tab as zenit-forms `FilesystemBrowserSource`
  over the HOST directory; what shipped is `InstanceFiles` through the
  daemon's archive/exec API with its own containment proof. The shipped shape
  is the better one (works for Docker named volumes too, no host-path
  authority in the web tier) -- treat phase-0 8d's Files-tab paragraph as
  superseded; do not build the FilesystemBrowserSource variant beside it.
- Readiness: `InstanceReadiness` (template hooks) and the runtime image's
  `default_port` are two half-vocabularies of "when is it up"; the phase-0
  promised image-default port probe was never wired. If defect 2 is fixed via
  a watch, fold the image-default probe in there rather than a third
  mechanism.
