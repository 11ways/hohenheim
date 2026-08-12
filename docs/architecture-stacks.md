# Managed Docker Stacks

The stack tier runs multi-container deployments (the `ComposeSiteType` the
site-type doc deferred, built as infrastructure instead of a site type): a
compose-shaped authoring surface over named volumes, config files and
dependency-ordered services with health gating. The reference consumer is the
unified NetBird deployment (netbird-server + dashboard + netbird-proxy).

> **REWRITTEN 2026-08-11.** Everything below describes the tier AFTER the Phase 7
> lowering (`b7749a36`, 2026-08-07): a stack service IS an owned instance, and the
> stack's own Docker executor is gone. The previous revision of this document
> described `StackDeployer`, one shared bridge network per stack and an
> `adopt_resources` opt-in -- all three deleted (`StackInstances.java:66-74`
> calls `StackDeployer` "deleted", `M083_DropStackAdoptResources.java:30` drops
> the column). It also stated that at-rest encryption was a stacks-only claim
> and that environment variables were unencrypted; that was already false when
> written and is corrected below. A reader arriving from an old link should
> treat the pre-lowering description as history, not as an alternative.
>
> **AMENDED 2026-08-12:** the encryption half of that last sentence was itself
> too strong. "Environment variables were unencrypted" is FALSE for INSTANCE
> variables (a real table, `.secret().encrypted()`) and TRUE for SITE
> `environment_variables` (a key inside a JSON `SchemaField`, which zenit
> structurally refuses to encrypt). The encryption bullet below is scoped
> accordingly, and its "what at-rest encryption does NOT cover" sub-bullet is
> the binding statement.

## Design decisions

- **A stack service IS an instance.** Every enabled service is deployed and
  destroyed through `InstanceService` as an owned
  `hohenheim:stack_service` instance (`StackInstances.java:34-52`,
  `StackServiceKind.java:62-64`). The stack records keep the PRODUCT half --
  compose-shaped authoring, the dependency graph, deployment history, rollback
  snapshots -- and no longer talk to a daemon about their own containers. What
  the tier gained by lowering, none of which it had before: fenced outcome
  writes, the host lease, host capacity booking, the port ledger's
  claim-before-create with its after-start verification, host placement, the
  reconciler's classification, `InstanceService`'s verified destroy,
  `InstanceOperationGuard`, backups/snapshots and `WorkloadLiveness`
  (`StackInstances.java:38-46`).
- **Records are desired state; deploys are explicit.** Saving a stack, service
  or file record never touches Docker. The Deploy row action
  (`StackResource.java:234`) resolves the records into an immutable `StackSpec`
  and executes it. This is the Dokploy stance, not Coolify's
  save-triggers-deploy magic.
- **Stack services do NOT auto-become proxy targets.** A normal
  `hohenheim:proxy` (or TLS passthrough) site points at a published port. One
  route table, one owner.
- **Two networks per service, not one shared bridge.** Each service keeps its
  own private per-workload network (the instance tier's default posture), and is
  ADDITIONALLY joined to the stack's shared LINK network under its service name
  between container create and start (`StackInstances.java:54-58`, `:290-310`,
  `StackServiceLinkHook.java:14-30`). The link network is owned by the STACK
  record and carries the same verified kernel policy the game-domain and
  site-database lanes use, so the lowering ends up with MORE isolation than the
  pre-lowering single shared bridge, not less. Egress is DECLARED `Egress.OPEN`
  at the tier (`StackServiceKind.java:78-86`): a stack service is the ordinary
  published-image mix that legitimately talks outbound at entrypoint. The
  tenant-range denies still apply -- metadata, host and other tenants stay
  unreachable.
- **Ownership is the instance tier's, not a stack label.** Containers are
  instance-id-keyed and carry instance owner labels; adoption goes through
  `OwnerLabels.removeIfOwnedBy`, which refuses an unattributable container by
  design and has no opt-out. The pre-lowering
  `be.elevenways.hohenheim.stack=<name>` label survives only as
  `StackInstances.LEGACY_LABEL_STACK` (`:74`) for two readers -- the one-shot
  retirement of a pre-lowering container and the reconciler's classification of
  one not yet adopted. **Nothing this tier creates carries it.** The
  `adopt_resources` opt-in is gone with it; adopting a pre-existing VOLUME is
  still expressible per mount via `external_name`, and such volumes are never
  removed.
- **Secrets are encrypted at rest wherever the storage shape allows -- broader
  than stacks, but NOT platform-wide** (narrowed back 2026-08-12; the
  unqualified "platform-wide" wording this bullet carried from 2026-08-11 is
  corrected by the sub-bullet below, which names what is excluded).
  Nineteen column declarations across the model layer carry zenit's
  `.encrypted()` field modifier (AES-256-GCM envelopes, keyring at
  `database.encryption.key_file`), including instance environment variables
  (`InstanceVariableModel.java:67-71`), site secrets
  (`SiteModel.java:109`), certificates, DNSSEC and TSIG keys, database
  passwords and notification channel URLs. On the stack side that is
  `registry_password` (`StackModel.java:70-74`), service `environment`
  (`StackServiceModel.java:114-118`), config-file `content`
  (`StackFileModel.java:34-38`) and the deployment `spec` snapshot
  (`StackDeploymentModel.java:39-41`). `M047_EncryptRecoverableSecrets` folded
  the pre-encryption plaintext of the columns that predate the change into
  envelopes; `EncryptedSecretsAtRestTest` pins that the columns hold `zenc$`
  and still round-trip. Back the keyring file up separately from the database;
  losing it makes those values unreadable.
  - **Encrypted is not the same as redacted.** `.secret()` masks a value on form
    surfaces and keeps it out of derived surfaces; `.encrypted()` is only the
    at-rest representation. Config-file `content`
    (`StackFileModel.java:34-38`) and deployment `spec`
    (`StackDeploymentModel.java:39-41`) are encrypted WITHOUT `.secret()`.
  - **What at-rest encryption does NOT cover (restored 2026-08-12; do not drop
    this sentence again).** Encryption reaches DECLARED main-table and
    table-stored sub-schema columns only. Zenit STRUCTURALLY REFUSES
    `.encrypted()` anywhere under a JSON-serialized sub-schema
    (`Schema.refuseEncryptedJsonSubFields`, zenit
    `common/orm/model/Schema.java:196-207`), so every JSON-nested secret is
    `.secret()`-only and lands in the column as plaintext: site
    `environment_variables` and `build_environment_variables` -- whose own
    AIDEV-NOTE (`NodeSiteType.java:67-83`) calls the map "the largest
    plaintext-secret surface in hohenheim" and states outright that "encryption
    is impossible" -- site `api_keys` (`NodeSiteType.java:87-90`), the git
    `webhook_secret` (`GitSourceSchema.java:63-65`, and see its `:15` note) and
    the dev-namespace `registration_token`
    (`DevNamespaceSiteType.java:38-43`). All of those are keys inside
    `SiteModel.SETTINGS` / `SOURCE_SETTINGS`, which are plain JSON
    `SchemaField`s (`SiteModel.java:58-62`, `:80-84`). Note the pair that is
    easy to conflate: INSTANCE variables are table-stored and encrypted
    (`InstanceVariableModel.java:67-71`), SITE environment variables are not.
    Settings-file secrets (comms DSNs, the database password, the Proteus
    access key) are equally out of reach, and the keyring's DEFAULT path is the
    SAME directory as those plaintext settings files, so one directory read
    yields the key and the settings plaintext together. Three documents forbid
    an unqualified platform-wide claim and they stand:
    `phase0-red-team-manifest.md:885-886`,
    `recoverable-secret-inventory.md:146` and `:327`.
- **Config files travel over the archive API**, staged into the created
  container before start with their declared octal mode
  (`DockerInstanceRuntime.java:395-418`). No host bind mounts, so remote (SSH)
  daemons work identically. A deploy converges the instance's file rows onto the
  SPEC's files, not onto the `stack_files` rows
  (`StackInstances.java:422-458`) -- which is what keeps rollback honest: a
  rollback stages the contents that shipped with that deployment, not whatever
  the rows say today. The stack rows stay the authoring surface; the instance
  file rows are the derived runtime shape.
- **Rollback re-deploys a snapshot.** Every successful deploy stores the fully
  resolved spec (encrypted); rollback re-executes the newest successful one
  verbatim, ignoring current record edits (`StackRuntime.java:114-129`,
  `:521-567`). A snapshot from BEFORE the lowering carries no service record
  ids and is refused by name rather than re-deployed blind
  (`StackInstances.java:175-184`).
- **A deploy CONVERGES on the records.** Workloads of services that left the
  spec are destroyed BEFORE anything is deployed (`StackRuntime.java:613-652`):
  disabled services by id, and -- by ATTRIBUTION, across every stack -- services
  whose record was deleted, which is the only evidence left once the row is
  gone. That ordering matters because a renamed service's old container still
  holds its published host ports. Stop and destroy sweep the owned instances
  the same way, and STATUS reports a workload whose service left the records as
  an `orphaned` entry (`StackRuntime.java:786-808`) -- never good, so a stack
  with an orphan reads degraded, keeps its rename gate closed, and stays visible
  until the next deploy prunes it.
- **Localization:** stacks hold no localized content. Names, images and paths
  are machine identifiers stored once; only their labels and the status,
  container-state and deploy-reason vocabularies are microcopy, so a stack
  renders identically in every locale.

## Pieces

| Piece | Role |
| --- | --- |
| `StackModel` / `StackServiceModel` / `StackFileModel` / `StackDeploymentModel` | desired state + history (`M042_CreateStacks`, `M043_StackUniqueKeys`, `M083_DropStackAdoptResources`, `M084_DropStackSubnet`) |
| `StackSpec` | immutable resolved deploy description; topological service order; map round-trip for snapshots |
| `StackServiceKind` | the instance kind one service lowers onto: image/command/env/mounts/ports/health settings, `SERVICE` hardening baseline plus per-service declared capabilities, `Egress.OPEN`, `generatedOnly()` so a standalone create of the kind is a named refusal |
| `StackInstances` | the wiring between the stack records and the instance contract: converge + deploy one service, stop, verified destroy, config-file sync, the shared link network, volume sweep, and the one-shot retirement of pre-lowering containers/networks |
| `StackServiceLinkHook` | `InstancePreStartHook` (weight 300) that joins the workload to the stack's link network under its compose alias |
| `StackRuntime` | per-stack worker queue, dependency ordering and condition gating, deployment records, status aggregation + `stack_health` alerts, rollback, volume purge, image reclaim wiring |
| `StackDeploymentRecords` | deploy history writes (keeps the newest 50 per stack; a failed write degrades to a log line rather than taking the deploy down) |
| `MonitorStacks` | scheduled status refresh (fallback `*/5 * * * *`, `STACKS` role) |
| `StackResource` + `StackServicesPage` + `StackDeploymentsPage` (+ nav-hidden `StackServiceResource`, `StackFileResource`) | admin surface |

## Semantics worth knowing

- Service names are DNS aliases on the stack's shared link network
  (`hohenheim-<controller>-stack-<stack>`, `ControllerScope.handle`), so
  containers reach each other by service name, compose-style -- and only inside
  that stack.
- `depends_on` conditions gate the DEPLOY order only
  (`StackRuntime.java:654-716`); at runtime the instance tier's crash policy
  recovers crashes. A compose `restart: no` lowers onto `CRASH_NONE`, anything
  else onto `CRASH_RESTART` (`StackInstances.java:197-203`), which is strictly
  better than a daemon restart policy: it has flap protection and re-runs the
  whole fenced deploy.
- A dependency gated on `healthy` whose target declares no health check is
  refused immediately by name, rather than waiting out its deadline
  (`StackRuntime.java:678-682`). Waits are capped: 30s for `started`, 120s plus
  the target's start period for `healthy`.
- A service with a `health_cmd` gets a Docker healthcheck; aggregate status is
  active (all good) / degraded (mixed) / failed (none), with stopped preserved
  after an explicit Stop (`StackRuntime.java:489-513`). `starting` counts as
  good so a warming healthcheck does not flap the stack.
- A per-service capability declaration is folded into the hardening profile at
  the deploy funnel (`StackInstances.java:209-213`); anything outside the closed
  allow-list refuses the DEPLOY by name, before any container exists. What no
  declaration can move: drop-ALL as the base, no-new-privileges, the pids cap,
  the structural refusals and the workload's own network policy.
- Deleting the stack record destroys every owned workload and the shared link
  network but keeps volumes; a data-destroying cleanup is
  `StackRuntime.destroy(id, true)`. Service, file and deployment-history rows are
  deleted with it (no FK cascades exist on those tables, and deployment
  snapshots carry secrets).
- Volumes are stack-NAME scoped (`StackInstances.volumeName`), so they outlive
  any one instance row and a deploy that replaces a container keeps its data.
- Accountability lives in `StackRuntime`, not on the surfaces: a SETTLED
  deploy, rollback, stop or volume purge writes one activity row on the stack
  record (`deployed` / `rolled_back` / `stopped` / `volumes_purged`), so panel,
  adoption and any future API are audited by the same write and `origin` says
  which acted. A failed deploy is answered by its status and its deployment
  record instead. Every operation runs on the stack's worker and
  `Accountability` is a ThreadLocal, so `onWorker` and `submitAsync` snapshot
  the dispatcher's attribution and re-enter it there -- without that carry the
  rows (and the deployment-history rows the worker saves) are unattributed
  system work. Deleting a stack is recorded by the ordinary delete hook.
- Renaming a stack is refused while it still owns live workloads
  (`StackResource.java:150`, `StackRuntime.java:277-291`): the name is embedded
  in the link network and every volume name, so a rename would orphan them. The
  gate is a live count decided on the stack's worker (a status string alone both
  misses orphans and locks out failed-first-deploy stacks); when the host cannot
  answer, the rename is refused rather than assumed safe.
- Every operation (deploy, rollback, stop, destroy, status refresh,
  service-state reads) runs on the stack's single worker lane (a virtual
  thread), so a monitor tick can never stomp a mid-operation status and a
  delete can never race a queued deploy. The lane is NEVER retired -- retiring
  it on destroy opened a second-executor race and a rejected-submission wedge.
  A destroy therefore BLOCKS until a running deploy finishes -- correct, but a
  slow admin request when the deploy is mid health-gate.
- Status writes are one-column atomic updates (`assign().updateAll()`), never
  load-mutate-save: the worker finishing a deploy must not write a stale whole
  row over an admin's concurrent form save.
- A deploy interrupted by a crash or restart leaves its row claiming
  `deploying`, which suppresses status refresh; `StackRuntime
  .resetInterruptedDeploys()` runs at boot ON A VIRTUAL THREAD (the sweep does
  live inspects and must never hold the proxy/DNS off the wire) and recomputes
  those from the live workloads. With the host down the swept stacks read
  `failed` until the monitor's next successful refresh. A crash mid-STOP or
  mid-DESTROY is not swept: the row keeps its pre-operation status and the next
  monitor tick recomputes an honest aggregate from what is actually running.

## Adopting a pre-lowering stack

`StackInstances.adoptExisting()` (`:460-491`) is the documented migration: every
ENABLED stack whose services own no instances yet is re-deployed under the
instance contract, onto the SAME stack-scoped volumes. Each pre-lowering
container is retired one by one and only when the daemon still attributes it to
that stack record (`:365-395`); a same-named container that is NOT ours is never
force-removed, it surfaces through the reconciler as an explicit operator
decision. The pre-lowering shared network is retired only after every service
owns an instance on its own network (`StackRuntime.java:603-609`) -- retiring it
earlier would cut a still-running legacy container off from its siblings. The
pass is idempotent and safe on every boot: an already-lowered stack is skipped,
and a host that cannot answer leaves the stack for the next pass rather than
abandoning a running container.

## Disk reclaim

Two operations reclaim the disk a stack occupies, split by whether the removal
can be undone.

**Images (automatic).** `DockerReclaim` removes the previous pin of a re-pinned
image, so a host that keeps updating stacks does not fill up. It is
ATTRIBUTION-based because Hohenheim shares its daemon: the input is every image
reference the stacks DECLARE on that server (enabled or not -- a disabled
stack's image must stay pinned so re-enabling it does not have to re-pull), an
image is removed only when every reference it carries (tags AND repo digests)
names one of those repositories while no declared reference resolves to it, and
an image referenced by any container (running or stopped) is never touched. All
comparison happens on the HUB-NORMALIZED form (`nginx` ==
`docker.io/library/nginx`; a compose-style `repo:tag@digest` pin canonicalizes
to its digest form), because the daemon and the operator spell the same
reference differently. Deploys are protected by the in-flight check: a server
with a DEPLOYING stack is skipped, and the sweep re-checks before every removal
(a deploy claims its status before it pulls). Two further guards: the age guard
(`stacks.reclaim_min_age_hours`, default 24, floor 1) keeps freshly BUILT images
-- a pulled image's Created stamp is its upstream build time, so it cannot
protect pulls -- and a failure to remove one image is logged and skipped rather
than aborting the sweep. Removal goes by reference, not id, so a multi-tagged
superseded image goes too.

An image carrying NO reference at all cannot be attributed to anyone -- it may be
an external build's leftover -- so it is kept unless
`stacks.reclaim_untracked` is on. That setting is the equivalent of
`docker image prune`, and belongs off on a shared host, on a dedicated one.

The nightly `ReclaimDockerImages` task runs the sweep on every server hosting a
stack (`stacks.reclaim_images`, default on); the `reclaim_images` header action
on the stack list (`StackResource.java:317`) starts the identical sweep in the
background and logs what it freed. Reclaim is per DAEMON, not per stack, so it
deliberately does not run on a stack's worker lane -- there is no stack whose
queue it belongs in.

**Volumes (explicit).** A volume is the one Docker resource whose removal
destroys data that cannot be re-fetched, so nothing removes one automatically.
`StackRuntime.purgeVolumes` (`:380-403`) tears the stack DOWN -- every owned
workload, the shared link network, and every volume the stack's name scopes;
external (adopted) volumes carry an `external_name` outside that prefix and
survive. The teardown is required, not polite: Docker refuses to remove a volume
attached to any container, stopped ones included, so a stop-only purge would
fail on every mounted volume. The next deploy rebuilds the stack from the
records, minus the data. The `purge_stack_volumes` row action
(`StackResource.java:270`) guards it with a typed confirmation demanding the
stack's own name.
