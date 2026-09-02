# Phase 0 design: site upstreams, runtime images, workspace + application kinds

Status: DESIGN, 2026-08-22. Decisions marked LOCKED come from the roadmap Jelle
approved; everything else is this document's proposal. No code was changed.
Line numbers refer to hohenheim at `601d9a91` and the javaweb repos at the
HEADs of 2026-08-22.

## 0. What already exists (read before touching anything)

The brief assumed most of this was greenfield. It is not. Hohenheim already ships:

- A health-gated ZERO-DOWNTIME RELEASE ENGINE with rollback for docker-type
  sites: `server/docker/SiteReleases.java` (951 lines: candidate -> probe ->
  switch -> drain -> retain one -> reclaim; every attempt a durable
  `ReleaseOperationModel` row; rollback re-deploys the retained digest-pinned
  instance with no rebuild), `SiteInstances.ensureRunning` (:107, source
  fingerprint fast lane, rollback pin), `InstanceModel.RUNTIME_ROLE`
  serving/candidate/retired (:368-382), the `release_operations` table
  (InitialMigration :1127). It is Docker-only and keyed to the SITE.
- Pterodactyl-style TEMPLATES ("eggs"): `instance_templates` (:842-878: kind,
  settings, install_image/script, update_script, reinstall_policy,
  readiness_line, stop_command, approval, import source/checksum),
  `instance_template_variables` (:986, typed variable schema with defaults),
  `instance_template_files` (:1008, files rendered at boot), `instance_files`
  (:1040), `instance_variables` (:1022, plain/secret, per-instance or
  per-environment). Code: `InstanceTemplates` (create-from-template with
  variable coercion, approval, project ownership), `InstanceVariables`
  (`substitute` does `{$VAR}`-style expansion, layered env), `InstanceInstalls`,
  `InstanceTemplateCapture` (publish a running instance as a template image),
  `CommunityScripts` (template import).
- Sandboxed IMAGE BUILDS from a git checkout: `build/BuildSandbox` +
  `NixpacksBuilder` + `DockerfileBuilder` + `SandboxedBuilds.run(BuildRequest)`
  -> image tar -> `BuildArtifacts.load/pruneSuperseded`, `build_operations`
  table with streamed logs, quota (`BuildQuota`), registry credentials.
- Console hub with readiness-line and stop-command attach decisions
  (`InstanceConsoles` :93-127), Incus websocket console + VM SPICE framebuffer,
  exec support, per-instance stats channel.
- Incus driver with custom volumes, snapshots, export/import, publish-as-image
  (`IncusInstanceRuntime` :904-1369), and a Docker driver with a HostConfig
  allowlist (`ContainerHardening.PERMITTED_KEYS` :220-231).
- Git provisioning: clone/fetch with provider credentials, webhooks with HMAC +
  delivery ledger, deploy statuses back to the forge, preview deployments.

Phase 0 is therefore a CONSOLIDATION: promote the site-keyed Docker release
engine to an instance kind, split the "yolk" (runtime image) out of the "egg"
(template), give sites one typed upstream, replace Docker named volumes with
Hohenheim-owned host directories, and delete the host-user process lane.

## 1. Host survey (daystrom, nightstrom; read-only, 2026-08-22)

Both: Arch Linux, kernel 7.1.5 (idmapped mounts available), 3 vCPU, 3.9 GB,
Incus 7.3 and Docker 29.7.1 both running, `/etc/subuid` = `root:1000000:1000000000`.
Root filesystem ext4 on /dev/sda1 (no project-quota feature enabled). A SEPARATE
btrfs partition /dev/sda2 is the Incus storage pool (`default`, driver btrfs,
mounted at `/var/lib/incus/storage-pools/default`). daystrom has an old
`/opt/hohenheim` install (data, sqlite db, jar); nightstrom has none.

Consequence for the volume design (section 5): neither host can give quota or
snapshot on its ROOT filesystem today. Both have a btrfs device the operator can
carve a subvolume from. The capability probe must therefore be per DATA ROOT, not
per host, and the host page must show what it found and what it refuses.

## 2. Corrections to the brief (evidence-backed)

1. "Docker-type sites generate and hide an instance" undersells it: they run a
   complete release engine (section 0). The brief's Phase 2 ("build the flip and
   rollback") is mostly a RE-KEYING from site to instance, not new work. The
   model must land in Phase 0 so that re-keying is mechanical.
2. Incus system containers keep state in their ROOTFS, not volumes
   (`IncusInstanceRuntime.java:32` AIDEV-NOTE). The workspace kind on Incus is
   NOT "a system container plus a mounted home": it is a system container whose
   rootfs is disposable and whose `/home/site` is a host directory disk device.
   The existing `system_container` kind (rootfs-persistent, Proxmox-style) stays
   as its own kind for people who want a whole pet box.
3. Templates already exist and are per kind (`instance_templates.kind` drives
   `settings` via schemaFrom). Folding "image + start command" into them would
   make every Node 22 image appear twice (workspace template and application
   template). The yolk/egg split (section 4) avoids that vocabulary duplication.
4. `SiteApiKeys` does not carry Alchemy's terminal. The whole control API is ONE
   action, `broadcast` (`ManagedProcessSiteHandler.java:1107-1115`, legacy
   `node_site.js:1304-1330`): fan a message out to every child process over the
   IPC channel. That only means anything in the multi-process host lane. It DIES
   with the lane (section 7); the Janeway console kind is Phase 3 and gets its
   own credential if it needs one.
5. The brief's `hohenheim:release` instance kind conflates two records: the
   thing the operator authors (source, build, template, volumes) and the
   per-deploy container with a runtime role. Today those are `sites` and
   `site_container` instances. They stay two records (section 4.2).

## 3. Sites: one typed upstream (LOCKED shape, proposed names)

`SiteModel` keeps hostnames (site_domains), TLS, access list, auth provider,
quota bucket, revisions. `SITE_TYPE` becomes `UPSTREAM_KIND`
(`RegistryEnumField` over `UpstreamKinds.REGISTRY`, the existing
`SiteTypeRegistry` renamed) and `SETTINGS` stays `schemaFrom("upstream_kind")`.
Members (closed, registry-discovered, one class each under
`server/upstream/kinds/`):

| kind | from | settings | dispatch |
| --- | --- | --- | --- |
| `static` | StaticSiteType | unchanged | `StaticFileHandler` |
| `redirect` | RedirectSiteType | unchanged | unchanged |
| `address` | ProxySiteType | forward_scheme/host/port/socket + the proxy knobs | unchanged (`UpstreamTarget`) |
| `instance` | NEW, absorbs DockerSiteType's serving half | `port` (declared port name on the instance), `scheme`, `websocket_upgrade`, `request_timeout` | resolves the instance's SERVING container's published loopback port at routing-generation build time, the way `DockerSiteRequestHandler` does today |
| `tls_passthrough` | unchanged | unchanged | `TlsPassthroughRoutes` |
| `dev_namespace` | unchanged | unchanged | dev tunnel |

DELETED as site types: `docker`, `node`, `java`, `command`, `alchemy`, `dead`.
(`dead` was a placeholder type; an upstream-less site is now simply a site whose
`instance_id` is null, rendered with an honest empty state.)

New column: `sites.instance_id` (nullable FK -> instances, indexed). It is a
REAL column, not a settings key, because the Instances detail page needs
"Exposed by" (reverse lookup), delete cascades need it, and the tenant scope
(`HohenheimAccess.reachesRecord`) needs to join on it. `upstream_kind=instance`
REQUIRES it non-null (beforeValidate), every other kind requires it null.

Moved OFF the site: `SOURCE`, `SOURCE_SETTINGS` (`GitSourceSchema`),
`site_databases` + `SiteDatabaseResource`/`SiteDatabasesPage` (the instance
already has `instance_databases` + `InstanceDatabaseLinks`; two link tables over
the same relation is duplication), `SiteProcessesPage`, `SiteTerminalCsp`. Site
revisions keep working (the column set shrinks).

[PRESENT TRUTH, 2026-08-31: `SiteTerminalCsp` was not re-homed, it was DELETED
outright (hohenheim `5c3696b2`), and the per-page `STRICT_ADMIN_TERMINAL`
variant with it. The admin panel has ONE policy, zenit's
`ContentSecurityPolicies.STRICT_ADMIN`, which carries `'wasm-unsafe-eval'`
panel-wide with `connect-src 'self'`; ghostty-web loads the pinned same-origin
file `/vendor/ghostty-vt.wasm` instead of a `data:` URL. Nothing installs a CSP
variant for a terminal any more.]

Readers of `site_type` to rewrite (from the map): `HohenheimHandlers:82`,
`api/PaasApi:182,252`, `auth/TenantWrites:514-522`, `process/ReservedEnv`
(deleted), `WorkloadIdentity:113` (deleted), `cms/SiteResource` (:71-557),
`SiteDatabaseResource:120` (deleted), `SiteDatabasesPage:47` (deleted),
`SiteProcessesPage:53` (deleted), `SiteDevSessionsPage:36`, `SiteDomainsPage:45`,
`SiteDomainResource:165`, `CertificateRequestPage:184`,
`tls/CertificateAuthority:208`, `preview/PreviewDeployments:79,181` (hard-coded
`"hohenheim:docker"` -> previews become a feature of the application kind),
`devtunnel/DevTunnelServerHandler:311`, `model/SiteDomainModel:261`.

Drift test: `UpstreamKindVocabularyTest` parses the en/nl microcopy scope
`upstream_kind` AND walks `SiteDispatcher`'s dispatch switch (which must be an
exhaustive switch over the registry members with no default, unknown -> the
existing FAIL_CLOSED route) and fails when the three disagree.

## 4. Instances

### 4.1 Kinds (ids are the stored vocabulary; rename = edit InitialMigration + seeds)

| id | label (en) | authorable | runtime | notes |
| --- | --- | --- | --- | --- |
| `hohenheim:system_container` | System container (LXC) | yes | incus | was `incus_container`; rootfs-persistent pet box |
| `hohenheim:vm` | Virtual machine | yes | incus | was `incus_vm` |
| `hohenheim:docker_container` | Docker container | yes | docker | raw image, unchanged |
| `hohenheim:workspace` | Workspace | yes | docker OR incus | NEW (4.3) |
| `hohenheim:application` | Application | yes | docker now, incus later | NEW: the authorable release-deployed app (4.4) |
| `hohenheim:release` | Release | generated by application | docker | was `site_container`; one per deploy, carries RUNTIME_ROLE |
| `hohenheim:database_container` | Database | generated by database OR by a shared database engine | docker | since 2026-09-02 a `DatabaseEngineModel` row (`database_engines`) owns one too |
| `hohenheim:stack_service` | Stack service | generated by stack | docker | unchanged |

ADDED 2026-09-02: `database_container` has TWO owner models now. A `dedicated`
managed database still generates its own (the shape above); a `shared` one does
not, and the `DatabaseEngineModel` row serving it owns the container instead,
holding many logical databases at once. Server-side every operation resolves the
serving instance through `EngineHost.serving(row)` /
`DatabaseInstances.handleOf(databaseId)` rather than assuming the database record
owns a container. See `docs/shared-database-engines.md`.

Generated kinds stay `generatedOnly()` (write-guarded by `OwnedInstances`) but
are NO LONGER HIDDEN: `InstanceResource:136-141`, `ManageInstanceResource:98`,
`InstanceApi:439,463`, `InstanceFileEndpoints:262` drop the `GENERATED_BY is
null` criterion; the list gains a "Managed by" column (generated_for_model +
link) and a kind filter whose default excludes `release` rows (they are noise
next to their application; the application page lists them). The `InstanceKind`
registry is already the `TypeDefinition`/`RegistryEnumField` shape the
zenit-forms-editing skill prescribes; no new mechanism.

`InstanceKindHandler.requiredRuntime()` becomes `supportedRuntimes()` (a set;
workspace = {docker, incus}); `InstanceKinds.requireRuntimeMatch` reads the set.
The dependent host picker (section 8a) derives its rules from the same set:
adding a runtime to a kind is one edit.

### 4.2 Why application and release are two records

The operator edits ONE thing (source, build, runtime image, template, volumes,
variables, exposed port). Each deploy produces an immutable container that must
keep running while the next candidate is probed, and one retired container is
retained for rollback. Today: `sites` (authored) + `site_container` instances
(per deploy, `RUNTIME_ROLE`). Tomorrow: an `application` instance (authored,
never itself a container; `STATUS` reflects its serving release) + `release`
instances generated for it. `SiteReleases` is re-keyed from `siteId` to the
application instance id; `ReleaseOperationModel` gets `instance_id` instead of
`site_id`; `SiteInstances` becomes `ApplicationReleases` (the convergence
entry); `DockerSiteRequestHandler` becomes the `instance` upstream handler.
Previews (`PreviewDeployments`) become "preview releases" of an application:
same engine, `runtime_role=preview`, own hostnames. Phase 0 lands the model
and the re-keying; the UI of flip/rollback/timeline is Phase 2.

### 4.3 Workspace kind

One persistent container per workspace, on Docker or Incus (operator's host
choice; the kind declares both), started from a RUNTIME IMAGE (4.5), with:

- Data directory `<volume root>/<instance id>/home` mounted at `/home/site`
  (section 5). Everything outside it is disposable and the shell banner says so.
- Process identity: uid `volume_uid_base + instance id` (setting, default 200000,
  below Incus's subuid range 1000000+). Docker: container config `User`, where it is
  a HOST uid. Incus: a NAMESPACE id the image's init drops to, with the volume
  directory chowned to `subuid base + that id`. There is no unix ACCOUNT on the host,
  only a number.
  IMPLEMENTATION NOTE (brief 8, measured on nightstrom 2026-08-22): `raw.idmap` gives
  host/namespace parity and works, but only if the host delegates the workspace uid
  window in `/etc/subuid`; a second range there makes Incus union both into one default
  map with two entries for namespace id 0, after which every container WITHOUT its own
  raw.idmap fails to start. `shift=true` is inert on both twins (the daemon reports an
  empty `kernel_features`). The mapped-owner form needs no host configuration and no
  kernel feature, so it is what shipped.
- Start command from the runtime image (overridable per instance), supervised
  as the container's main process (Docker PID 1 via the image's `tini`-style
  entry; Incus: the image ships a minimal init that runs the command as the
  workspace uid and restarts it per `CRASH_POLICY`).
- Shell: the existing console/exec lane (`InstanceExec`, `InstanceConsoles`)
  into the running container as the workspace uid. Files tab: zenit-forms'
  `FilesystemBrowserSource` over the host data directory (section 8d).
- Git: `GitDeployment` is split into `GitCheckout` (host side: clone/fetch into
  the data directory, credentials from the provider for the duration of the
  command only, `.git` config never stores the token) and `WorkspaceBuilds`
  (build command executed INSIDE the container via `InstanceExec`, streamed to
  the console). Deploy = checkout + build + restart. Webhooks (`GitWebhookHandler`)
  route to the instance instead of the site.
- Readiness: template hooks (4.6) or, absent a template, port-open on the
  declared port with the runtime image's default timeout.

Refusals: a host whose volume backend is `none` cannot place a workspace
(`requirePlaceableOn`); a workspace cannot move runtimes while running
(cold migration between same-runtime hosts stays the existing lane).

### 4.4 Application kind

Source (git, same `GitSourceSchema` minus the host-lane keys), build (Nixpacks
or Dockerfile via the existing sandbox; optional runtime image as the Nixpacks
base), declared port, health path, variables, volumes (section 5; mounted into
every release container; `exclusive` volumes force stop-then-start), retention
(`keep_releases`, default 2: serving + one retired, the current policy), and a
`template_id` for egg-style hooks. Runtime: Docker in Phase 0 (the build lane
is Docker). Incus execution of OCI images (Incus 7.3 can run OCI images through
its `oci:` image server) is a Phase 2 spike, not assumed here.

### 4.5 Runtime images ("yolks"): NEW table `runtime_images`

Columns: id, name, description, icon, `docker_image` (ref), `incus_image`
(alias or fingerprint, nullable), `default_command`, `default_port`,
`default_build_command` (nullable), `workdir`, `shell` (/bin/bash), `uid_mode`
(`mapped` only for now), `builtin` (code-owned, `Seeder.sync`), `enabled`,
timestamps. Seeded members: `node-22`, `node-16`, `node-12`, `node-10` (the
Phoenix migration's legacy Alchemy runtimes), `java-21`, `debian-13`, `static` (an
nginx-style file server for workspaces that only hold files). Image references
are HOHENHEIM-BUILT (Dockerfiles under `images/` in the repo, built by a
`zenit-dev`-driven task and pushed to the configured registry; Incus variants
published with distrobuilder from the same package list). Phase 0 ships the
table, the seeder, the Dockerfiles and the Docker images; the Incus variants are
built in Phase 1 when the Incus workspace lane is exercised.

Workspace and application instances carry `runtime_image_id` (FK). Templates
(4.6) may ALSO name a runtime image as their base, which is how an Alchemy
template says "node-22 plus these hooks" without re-declaring the image.

### 4.6 Templates ("eggs"): extend `instance_templates`, do not replace

Keep every existing column. Add: `runtime_image_id` (nullable FK),
`start_command` (nullable, overrides the image default; `{$VAR}` expansion via
`InstanceVariables.substitute`), `readiness_kind` (closed enum `port` | `http` |
`console_line`; `readiness_line` stays the value for `console_line`, new
`readiness_target` carries the http path or port name), `stop_kind` (`signal` |
`command`; `stop_command` stays), `stop_grace_seconds`, `console_kind`
(`plain` now; `janeway` is Phase 3). `kind` keeps driving `settings` through
schemaFrom; a template's `kind` must be one of workspace/application/system
container/vm (the generated kinds refuse templates).

New child table `instance_template_volumes` (template_id, name, container_path,
quota_bytes, exclusive) = default volume declarations copied onto the instance
at create time (the `instance_template_variables` -> `instance_variables`
precedent, `InstanceTemplates.createFromTemplate`).

Hook kinds are Java enums WITH the behaviour on the member (readiness probe
implementation, stop implementation), exhaustive switches, no default; the
stored strings are those enums' keys via `EnumField.value(...)`; the microcopy
scopes `readiness_kind`/`stop_kind`/`console_kind` are bound by the existing
`StatusPresentationDriftTest` pattern.

## 5. Volumes: Hohenheim-owned host directories (LOCKED substrate)

NEW table `instance_volumes` (instance_id, name, container_path, quota_bytes,
exclusive, `host_path` (derived, stored for evidence), used_bytes, observed_at,
timestamps; unique (instance_id, name)). Host layout:
`<storage.data_path>/volumes/<instance id>/<name>`. The workspace's `home`
volume is simply the row named `home` at `/home/site`, created with the instance.

Mounting: Docker = `Mounts[{Type: bind, Source: host_path, Target:
container_path}]` (Mounts is already a permitted HostConfig key;
`ContainerHardening.refuseEscapes` gains the rule "a bind source must be under
the volume root", which is the only new escape surface). Incus = `disk` device
`source=host_path path=container_path shift=true`.

Volume backend = a HOST CAPABILITY detected by `HostPreflight` for the data root
and stored on `servers.volume_backend`: `btrfs` (subvolume per volume, qgroup
quota, snapshot = `btrfs subvolume snapshot -r`), `zfs` (dataset per volume,
`quota=`, `zfs snapshot`), `xfs_prjquota` (project quota, NO snapshot), `none`.
A new `VolumeBackend` enum carries the behaviour per member (create, setQuota,
usage, snapshot, deleteSnapshot, destroy), exhaustive switches. Placement rule:
workspace and application REQUIRE a backend with quota; application's
pre-deploy snapshot REQUIRES a backend with snapshot, otherwise the deploy is
refused by name unless the application declares no volumes. No tar fallback
(Jelle: no compromises). Operator guidance in the host page: "mount a btrfs or
zfs filesystem at `<data_path>/volumes`" with the probe's finding shown.

`SiteVolumes` (Docker named volumes keyed to the site, 362 lines) is DELETED;
`DatabaseContainerKind.DATA_VOLUME` and `StackServiceKind` mounts migrate to
`instance_volumes` too, so there is ONE volume mechanism (databases gain quota
and snapshot for free; `InstanceBackups` tar-per-volume becomes
snapshot-then-stream).

Localization: volume names and container paths are operator identifiers, never
localized; labels and refusals are microcopy.

## 6. Sites and instances: the UI (Phase 0 scope)

Every page has a one-sentence `description()` hint; every list declares
`listChrome()`; every create path is one screen with cards and a disclosure for
advanced fields. Screenshot review by Jelle is part of done.

- Instances list: all kinds, "Managed by" column, kind/status/host filters,
  search; `release` rows filtered out by default.
- Instance create: `pl-choice-group` of kinds (icon, label, description;
  kinds the selected project may not create are disabled with the reason),
  then dependent pickers: runtime image (filtered to images with an image for
  the chosen runtime), template (filtered by kind), host (filtered by the kind's
  `supportedRuntimes` AND, for workspace/application, `volume_backend != none`).
  Name, project/environment. Advanced disclosure: resources, crash policy,
  variables, volumes (`Records` rows, section 8d).
- Instance detail: status header, tabs: Overview (`RecordDashboardPage`: stats,
  exposed-by sites, quick actions), Console, Files (workspace), Source + Deploys
  (workspace/application), Variables, Volumes, Settings (the form).
- Sites list: hostname, upstream (kind badge + instance link), TLS, access.
- Site form: `pl-choice-group` for upstream kind, schemaFrom settings, the
  instance pick (dependent: instances with a declared port, in the caller's
  scope) with "Create new" OFF (`creatable(false)`).
- "Expose" header action on an instance detail: opens site create with
  `upstream_kind=instance&instance_id=N` prefilled (`createValues(Conduit)`).
- Hosts page: volume backend finding and its consequence, beside admission and
  posture.
- Runtime images resource (admin-only, `ListChrome.MINIMAL`), templates
  resource extended with the hook fields and the volumes `Records`.

## 7. Deletions (the host-user lane) and what survives

Deleted outright, with their tests: `SystemUserModel`, `SystemUserOptions`,
`UpdateSystemUsers`, `SystemUsers`, `ProcessConfinement`, `WorkloadIdentity`
(its uid-claim half; the workload attribution labels already live in
`OwnerLabels`), `ProcessInfrastructure`, `ManagedProcessSiteHandler`,
`ManagedProcess`, `ProcessMonitor`, `ProcessReaper`, `ProcessGroupSupport`,
`ProcessCapacity`, `PortAllocator` and `SocketAllocator` (the `PortLedger` is
the surviving port authority; verify no other consumer of the OS probe first),
`RemoteCache`, `IpcChannel`, `ChildWrapper`, `ProcessTerminalHandler`,
`ReservedEnv`, `SiteApiKeys` + `SiteApiKeySeeder` (section 2.4),
`ProcessDockerTransport`, `ProcessNetworkPolicy`, `SiteProcessesPage`,
`SiteTerminalCsp`, `ProclogModel` + retention/rendering, `NodeVersionModel` +
`NodeVersionOptions` + `UpdateNodeVersions`, site types Node/Java/Command/
Alchemy/Docker/Dead, `GitProvisioner.getSiteDirectory` checkout layout (the
checkout now lives in the workspace volume or the build context),
`SiteVolumes`, `site_databases` + its resource/page, settings groups `Process`
and `Node`, `Roles.processes`, tables `system_users`, `node_versions`,
`proclogs`, `site_databases`.

[PRESENT TRUTH, 2026-08-31: this list is a PLAN and was executed only in part.
`SiteTerminalCsp` is genuinely gone (hohenheim `5c3696b2`), together with the
per-page `STRICT_ADMIN_TERMINAL` policy it installed; the panel now has one
policy, `ContentSecurityPolicies.STRICT_ADMIN` with `'wasm-unsafe-eval'`, and
the ghostty wasm is the pinned same-origin `/vendor/ghostty-vt.wasm`. Still
present in the tree, contrary to this list, are `SystemUserModel` (common
`model/`), `SystemUsers`, `ProcessConfinement`, `ProcessGroupSupport` (server
root and `server/process/`) and `UpdateSystemUsers` (`server/task/`) -- the
setsid+sudo uid-drop chain survives because the
managed spamservice child still uses it. Nothing per-site does.]

Kept and re-homed: `GitDeployment`'s clone/fetch/build-queue/deployment-record
logic (to instance keying), `DeployStatuses`, `GitWebhookHandler`, the build
sandbox, `DockerSiteRequestHandler` (becomes the instance upstream handler),
`SiteReleases`/`SiteInstances` (re-keyed, section 4.2), `InstanceConsoles`
(unchanged), `DatabaseEnvInjection` (now injects into the application/workspace
variables through `InstanceDatabaseLinks`).

Undecided until the implementer measures it: `ProcessCapacity`'s host-memory
reserve behaviour is partly duplicated in `InstanceCapacity`; the implementer
verifies `InstanceCapacity` covers every admission the process twin did before
deleting.

## 8. Framework mechanisms (each with its hohenheim consumer, tests, falsified)

### 8a. Dependent relation pick (zenit + zenit-forms) -- home verdict NEW_MECHANISM, zenit-forms

Cite: `ProviderPick.providerFromSiblings` (zenit-forms `ProviderPick.java:129`),
`SiblingProviderResolver` (:21, DRY `@HawkeyeClass` record, structural equality),
`ProviderPickState` (:26), client re-resolution `ZenitFormsFunctions.pickerProvider`
(:235-253, sibling names resolved relative to the entry's scope prefix), the
live scope `zf-form-scope.hwk`, and `RecordSourceProvider.rules` riding
`POST /zn/records/{source}/query` (`RecordSourceEndpoints:53-69`, validated at
`RecordSourceHandlers:153-158`). `QueryRules.vocabularyFromSibling`
(zenit `QueryRules.java:219`) is the coercion-side precedent.

Spec:
- zenit core `common/edit/SiblingRulesResolver` (interface, DRY-serializable
  record implementations): `@Nullable RuleGroup resolve(Map<String,Object>
  siblingValues)`; null = "no narrowing yet" (picker disabled, like
  ProviderPick's unresolved provider).
- `RelationPick.Builder.rulesFromSiblings(SiblingRulesResolver resolver,
  String... siblingNames)` stored on the entry; `RelationPickState` gains
  `resolver`, `siblingNames`, `siblingValues`, `rules` (the server snapshot).
- `zf-relation-field.hwk` builds its provider through a new
  `ZenitFormsFunctions.relationProvider(entry, scopeValues)` that overlays live
  sibling values, resolves, and returns a `RecordSourceProvider` with those rules
  (value-equal re-derivation = reactive no-op, the documented contract at
  `RecordSourceProvider.java:110`). Unresolved -> `disabled` select with the
  placeholder "choose X first" (microcopy).
- Coercion (`SubmittedValueCoercion` for RelationPick) resolves the same
  resolver against the RAW sibling values of the same scope and verifies the
  submitted id through `RecordSource.buildQuery(..., rules, ..., access)`
  (existence + scope + narrowing in one query); a filtered-out id is a typed
  violation `relation_out_of_scope`. The client filter is never the gate.
- Inline-cell and quick-add lanes: a dependent pick is refused at panel
  registration for `inlineEditableFields` (its sibling is not on the row
  editor) and omitted from quick-add presets; both are loud, not silent.
- Localization: none (ids and rules).
- Tests: zenit `RelationPickDependentCoercionTest` (filtered-out id refused,
  unresolved sibling refused, resolved id accepted), zenit-forms
  `DependentRelationPickBrowserTest` (change kind -> host options narrow, no
  round-trip, select disabled until resolved). Consumer: hohenheim instance
  form (kind -> host by `supportedRuntimes` + volume backend; kind -> template;
  runtime -> runtime image) and the site form (upstream_kind=instance ->
  instances with a declared port in scope).

### 8b. Record-aware FieldAccess at detail render (zenit-forms + zenit-cms) -- verdict EXTEND_EXISTING `FieldAccess`

`FormStateTranslator.translate(...)` gains an overload with `@Nullable Object
record`; `resolveDecision` (:370-381) calls `access.decide(ctx, record)` when a
record is present (the record-aware resolver's null-record rule at
`FieldAccess.java:42-44` keeps CREATE forms failing closed). `ResourceFormPageRenderer`
(:108-112) passes the record it already holds (:68). HIDDEN removes the entry;
the hidden entry's stored value survives the submit untouched because
`enforceFieldAccess` (`ResourcePageEndpoints:2009-2025`) already strips it.
Tests: zenit-forms translator unit (hidden on record A, editable on record B),
zenit-cms `ResourcePageEndpointsTest` journey (render hides, forged POST
stripped). Consumers: `DnsPeerResource` (nameserver peers hide Hohenheim
credentials), `DnsZoneResource` (primaries hide transfer diagnostics), the
instance form (kind-specific entries beyond the schemaFrom block).

### 8c. `pl-choice-group` / `pl-choice-card` (plumage) -- verdict UNCERTAIN between plumage and zenit-forms; decision: plumage component, zenit-forms renders it

Cite `pl-radio-group`/`pl-radio-group-item` (`radio-group.hwk:19/65`:
formAssociated, roving keyboard, Selection engine on `plumage.radio.value`) and
`cms-widget-picker` (zenit-cms `widget-picker.hwk`, a dialog+command catalogue;
the wrong shape for a 4-8 item inline choice). The component is a PRESENTATION
of the radio group: `pl-choice-group` wraps `pl-radio-group` semantics with
`columns` and `pl-choice-card` items carrying `value`, `icon`, `title`,
`description`, `disabled`, `disabledReason` (rendered as `pl-tooltip` and
`aria-describedby`). Styling in the component's own tag styles (cascade layer
rule). Showcase page + axe sweep as the plumage skill requires. zenit-forms:
`Select.Builder.presentation(Select.Presentation.CARDS)` renders the derived
options (icon/label/description from the enum or `TypeDefinition` facets)
through the new component; the stored value and coercion are unchanged.
Consumers: instance kind pick, site upstream-kind pick, runtime image pick.

### 8d. Records rows design pass + first real filesystem-browser consumer (zenit-forms)

`Records` (zenit `Records.java:26`, `zf-records.hwk`) stays THE repeatable-rows
editor (the skill forbids a generic repeatable component). The pass: a labeled
well with a header row, an empty state with the add button inline, per-row
validation placement, `use:List.*` reorder kept. Consumers: template volumes,
instance volumes, template variables (today hand-rendered in
`InstanceTemplateHandlers`; converge onto Records).
`FilesystemBrowserSource.of(id, permission, roots)` (zenit-forms
`FilesystemBrowserSource.java:46`) gets its first production consumer: the
workspace Files tab registers one source per instance volume root, scoped by
the `HohenheimAccess.FILES` capability; `zf-path-input` is the picker, a
`pl-table` listing + `pl-file-upload` (existing) is the tab.

## 9. Migration and deploy consequence

`InitialMigration` edited in place (sites: rename `site_type` -> `upstream_kind`,
drop `source`/`source_settings`, add `instance_id`; instances: nothing removed,
add `runtime_image_id`; new tables `runtime_images`, `instance_volumes`,
`instance_template_volumes`; templates: the 4.6 columns; `release_operations`:
`site_id` -> `instance_id`; drop `system_users`, `node_versions`, `proclogs`,
`site_databases`). starfleet, daystrom, nightstrom are wiped and reinstalled
(Jelle, 2026-08-22); `apex` on starfleet is recreated by hand as a `static`
upstream site. `MigrationIntegrityTest` keeps enforcing the single migration.

## 10. Test plan

Dies with the lane: every test in section 9 of the map under "Host-user process
lane" plus `DockerSiteHandlerTest`, `SiteTypeTest` (rewritten as
`UpstreamKindTest`), `SiteVolumeLiveTest`. Rewritten: `SiteCrudTest`,
`SiteLifecycleTest`, `SiteDispatcherTest`, `ProxyDispatchTest` (address kind),
`SiteReleaseContractTest`/`SiteReleaseLiveTest` (application keying),
`GitDeploymentFlowTest` (workspace + application), `InstanceKindOfferTest`
(new kinds, supportedRuntimes), `InstancePlacementTest` (volume backend
refusal), `StatusPresentationDriftTest` (new scopes), `MicrocopyCatalogParsesTest`.
New journeys: `UpstreamKindVocabularyTest`, `InstanceVolumesTest` (declare ->
host path -> mount spec for both runtimes -> bind-source escape refused),
`VolumeBackendProbeTest` (fixture roots on tmpfs report `none`; btrfs lane is
live-only on daystrom/nightstrom), `WorkspaceLifecycleLiveTest` (create on
Docker AND Incus, shell exec as the uid, file visible on the host path, git
checkout + build + restart, delete removes the container and keeps the
directory until the typed-confirm delete of the volume), `ApplicationReleaseTest`
(re-keyed engine: initial release, changed source, failed candidate keeps
serving, rollback), `SiteInstanceUpstreamTest` (site -> serving release port
resolves, null instance = honest DOWN), `RuntimeImageSeedTest`, plus the three
framework suites in section 8. `ContainerHardeningTest` MUST move into a real
lane (a `docker-live` bucket in `.zenit-dev.json`) before any tenant shell ships.

## 11. Implementation briefs (serialized per repo; framework first)

| # | repo | brief | size |
| --- | --- | --- | --- |
| 1 | plumage | `pl-choice-group`/`pl-choice-card` + showcase + axe | S |
| 2 | zenit | `SiblingRulesResolver`, `RelationPick.rulesFromSiblings`, coercion narrowing + tests | M |
| 3 | zenit-forms | dependent pick render + client re-resolution; `Select.presentation(CARDS)`; Records design pass; translator record overload | M |
| 4 | zenit-cms | pass the record to the translator; dependent-pick refusal in inline/quick-add registration; journey tests | S |
| 5 | hohenheim | model wave: InitialMigration edits, `UpstreamKinds`, `sites.instance_id`, kind renames, `runtime_images` + seeder + Dockerfiles, `instance_volumes` + `VolumeBackend` + preflight probe, template columns; drift tests | L |
| 6 | hohenheim | deletion wave: section 7, then `zd_build` green; `PortLedger` audit | M |
| 7 | hohenheim | release re-keying: `SiteReleases`/`SiteInstances` -> application, `instance` upstream handler, previews; `ApplicationReleaseTest` | L |
| 8 | hohenheim | workspace kind: both runtimes, uid mapping, bind-source hardening rule, `GitCheckout`/`WorkspaceBuilds`, console/exec; live test on daystrom (docker) + nightstrom (incus) | L |
| 9 | hohenheim | UI wave: choice-card pickers, dependent pickers wired, Files/Volumes/Variables tabs, Expose action, host page findings, runtime-image resource, ListChrome judgement; screenshots | L |
| 10 | hohenheim | starfleet/daystrom/nightstrom reinstall runbook update + deploy | S |

Briefs 1-4 can run in parallel with 5 (5 only needs published framework
artifacts for the pickers at brief 9). 6 after 5; 7 and 8 after 6, in that
order (8 reuses 7's volume mounting); 9 after 3, 4 and 8.

## 12. Open decisions for Jelle

1. Naming of the two release records: `application` (authored) + `release`
   (per deploy) as proposed, or the roadmap's single word "release" for the
   authored record. The code needs two names either way.
2. Volume root on the test hosts: carve a btrfs subvolume out of the Incus pool
   partition (/dev/sda2 on both) and mount it at `/opt/hohenheim/data/volumes`,
   or repartition. The probe design is the same; this is host setup.
3. `system_container` (rootfs-persistent, Proxmox-style) stays as a separate
   kind beside `workspace`. Confirm; deleting it would remove the "whole pet
   box with snapshots" story that VMs cover only partially.
4. `docker_container` (raw image, no release engine) stays for one-off
   containers. Confirm, or fold it into `application` with a "no build" source.
5. Runtime image distribution: build and push Hohenheim's own images to a
   registry Jelle controls (which one?), or build locally on each host from the
   in-repo Dockerfiles at first use (slower first create, no registry).
