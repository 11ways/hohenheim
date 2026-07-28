# Managed Docker Stacks

The stack tier runs multi-container deployments (the `ComposeSiteType` the
site-type doc deferred, built as infrastructure instead of a site type): one
private bridge network, named volumes, config files, and dependency-ordered
services with health gating. The reference consumer is the unified NetBird
deployment (netbird-server + dashboard + netbird-proxy).

## Design decisions

- **Records are desired state; deploys are explicit.** Saving a stack, service
  or file record never touches Docker. The Deploy row action resolves the
  records into an immutable `StackSpec` and executes it. This is the Dokploy
  stance, not Coolify's save-triggers-deploy magic.
- **Stack services do NOT auto-become proxy targets.** A normal
  `hohenheim:proxy` (or TLS passthrough) site points at a published port. One
  route table, one owner.
- **Ownership labels are the safety boundary.** Everything the deployer creates
  carries `be.elevenways.hohenheim.stack=<name>`. Same-named containers,
  networks or volumes WITHOUT the label are refused unless the stack opts into
  `adopt_resources` (the explicit takeover of a pre-existing deployment).
  External volumes are adopted per mount via `external_name` and are never
  removed.
- **Secrets are encrypted at rest.** `registry_password`, config file
  `content`, and deployment `spec` snapshots use zenit's `.encrypted()` field
  modifier (AES-256-GCM envelopes, keyring at
  `database.encryption.key_file`). Back the keyring file up separately from
  the database; losing it makes those values unreadable.
- **Config files travel over the archive API**, uploaded into the created
  container before start with their declared octal mode. No host bind mounts,
  so remote (SSH) daemons work identically.
- **Rollback re-deploys a snapshot.** Every successful deploy stores the fully
  resolved spec (encrypted); rollback re-executes the newest successful one
  verbatim, ignoring current record edits.
- **A deploy CONVERGES on the records.** Containers carrying the stack's
  ownership label whose service is no longer in the spec (deleted, disabled or
  renamed) are pruned BEFORE anything is created (a renamed service's old
  container still holds its published host ports), stop/destroy sweep by label
  too (destroy also sweeps owned networks and, with removeVolumes, owned
  volumes by label), and STATUS reports such strays as `orphaned` entries --
  never good, so a stack with an orphan reads degraded, keeps its rename gate
  closed, and stays visible until the next deploy prunes it.
- **Localization:** stacks hold no localized content. Names, images and paths
  are machine identifiers stored once; only their labels and the status,
  container-state and deploy-reason vocabularies are microcopy, so a stack
  renders identically in every locale.

## Pieces

| Piece | Role |
| --- | --- |
| `StackModel` / `StackServiceModel` / `StackFileModel` / `StackDeploymentModel` | desired state + history (`M042_CreateStacks`, `M043_StackUniqueKeys`) |
| `StackSpec` | immutable resolved deploy description; topological service order; map round-trip for snapshots |
| `StackDeployer` | executes a spec against one Docker daemon: network, volumes, image pulls (registry auth), replace, file upload, condition gating (`started`/`healthy`), stop/destroy/status |
| `StackRuntime` | per-stack worker queue, deployment records, status aggregation + `stack_health` alerts, rollback |
| `MonitorStacks` | scheduled status refresh (fallback `*/5 * * * *`) |
| `StackResource` + `StackServicesPage` + `StackDeploymentsPage` (+ nav-hidden `StackServiceResource`, `StackFileResource`) | admin surface |

## Semantics worth knowing

- Service names are DNS aliases on the stack network (`hohenheim-stack-<stack>`),
  so containers reach each other by service name, compose-style.
- `depends_on` conditions gate the DEPLOY order only; at runtime Docker's own
  restart policies recover crashes (there is no supervisor loop).
- A service with a `health_cmd` gets a Docker healthcheck; aggregate status is
  active (all good) / degraded (mixed) / failed (none), with stopped preserved
  after an explicit Stop.
- Deleting the stack record destroys owned containers and the network but
  keeps volumes; a data-destroying cleanup is `StackRuntime.destroy(id, true)`.
  Service, file and deployment-history rows are deleted with it (no FK
  cascades exist on those tables, and deployment snapshots carry secrets).
- Deploy replaces every service's container (create new after removing old);
  named volumes persist across replacements.
- Renaming a stack is refused while it still OWNS containers: the name is
  embedded in every container, network and volume name plus the ownership
  label, so a rename would orphan them all. The gate is a live owned-container
  count decided on the stack's worker (a status string alone both misses
  orphans and locks out failed-first-deploy stacks); when Docker cannot
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
  live Docker inspects and must never hold the proxy/DNS off the wire) and
  recomputes those from the live containers. With Docker down the swept stacks
  read `failed` until the monitor's next successful refresh. A crash mid-STOP
  or mid-DESTROY is not swept: the row keeps its pre-operation status and the
  next monitor tick recomputes an honest aggregate from what is actually
  running.

## Disk reclaim

Two operations reclaim the disk a stack occupies, split by whether the removal
can be undone.

**Images (automatic).** `DockerReclaim` removes the previous pin of a re-pinned
image, so a host that keeps updating stacks does not fill up. It is
ATTRIBUTION-based because Hohenheim shares its daemon: the input is every image
reference the stacks DECLARE on that server, an image is removed only when every
reference it carries (tags AND repo digests) names one of those repositories
while no declared reference resolves to it, and an image referenced by any
container (running or stopped) is never touched. Two further guards: the age
guard (`stacks.reclaim_min_age_hours`, default 24, floor 1) protects an image a
concurrent deploy has just pulled but not yet started, and a failure to remove
one image is logged and skipped rather than aborting the sweep.

An image carrying NO reference at all cannot be attributed to anyone -- it may be
an external build's leftover -- so it is kept unless
`stacks.reclaim_untracked` is on. That setting is the equivalent of
`docker image prune`, and belongs off on a shared host, on a dedicated one.

The nightly `ReclaimDockerImages` task runs the sweep on every server hosting a
stack (`stacks.reclaim_images`, default on); the `reclaim_images` header action
on the stack list runs the identical sweep on demand and reports what it freed.
Reclaim is per DAEMON, not per stack, so it deliberately does not run on a
stack's worker lane -- there is no stack whose queue it belongs in, and a
concurrent deploy is safe by the two guards above.

**Volumes (explicit).** A volume is the one Docker resource whose removal
destroys data that cannot be re-fetched, so nothing removes one automatically.
`StackRuntime.purgeVolumes` stops the stack and then removes every volume
carrying its ownership label; external (adopted) volumes never carry the label
and survive. Stopping first is required, not polite -- Docker refuses to remove
an attached volume, so a purge that skipped the stop would silently reclaim
nothing. The `purge_stack_volumes` row action guards it with a typed
confirmation demanding the stack's own name.
