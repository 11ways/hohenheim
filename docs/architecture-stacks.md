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

## Pieces

| Piece | Role |
| --- | --- |
| `StackModel` / `StackServiceModel` / `StackFileModel` / `StackDeploymentModel` | desired state + history (`M042_CreateStacks`) |
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
- Deploy replaces every service's container (create new after removing old);
  named volumes persist across replacements.
