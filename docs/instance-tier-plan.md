# Instance tier: the Proxmox-replacement / game-panel plan

Decided 2026-07-28. Scope: Hohenheim becomes (a) a full Proxmox replacement for
everything we use Proxmox for, and (b) a Pterodactyl-class game server panel.
The unifying insight from the gap analysis: both are the same missing tier --
a first-class INSTANCE record (container, later VM, or supervised process)
that is grant-scoped, console-attachable, port-allocated, backup-able and
schedulable, with NO domain attachment unless one is explicitly linked.

Doctrines already settled (do not re-litigate):

- Plain Debian is the canonical host; hohenheim is the SOLE manager of any
  host it runs on. Proxmox, where it exists, sits BELOW and hands over full
  VMs (never LXC guests, never a shared host).
- The site stops being the only spine. Sites remain the HTTP/domain tier;
  instances are the compute tier; a domain link between them is optional.
- Minecraft traffic always flows through Velocity (it is also the chat layer).
  Hohenheim owns the domain-to-backend DATA and materializes it as generated
  Velocity config and DNS records. No Minecraft protocol handling in-house.
- Two authorization systems stay side by side (adversarially validated,
  PASS-WITH-CONDITIONS): dotted permissions (federatable, wildcard, Proteus-
  sourced) and record grants (app-local, exact-match, per-record). The real
  boundary is FEDERATABLE vs APP-LOCAL, not type-level vs record-level.
  Separation is by tuple position, not syntax: record capabilities MAY be
  dotted (`files.read`) but never resolve through PermissionResolver and
  never enter KnownPermissions.
- Runtime support is a PER-HOST seam: docker | incus | (later) proxmox-lxc,
  plus the existing managed-process supervisor. Incus is the LXC integration
  point (REST over unix socket / SSH -- the DockerClient pattern), and later
  provides KVM VMs through the same API.

Mechanism-home rule applies throughout: generic mechanisms land in zenit core /
zenit-auth / zenit-cms / plumage WITH hohenheim as the first wired consumer in
the same phase. Nothing ships unwired.

---

## Phase 0 -- Auth foundations and live-defect fixes (framework)

Everything here is either a live defect or a mechanism the whole arc depends
on. No new product surface; the wired consumer is hohenheim's EXISTING site
access feature, migrated onto the new mechanisms.

zenit-auth:

- Grant lifecycle (review C1, HIGH): `RecordGrants.revokeAllForRecord(model,
  id)` + `revokeAllForSubject(type, id)`; wire record cleanup through
  `GlobalModelHooks.addAfterRemoveHook`, subject cleanup into user/group
  deletion. Without this, hard-delete + id reuse = privilege resurrection.
- API-key scope coherence (C3, HIGH): one scope list currently has two
  matchers (`["*"]` = all permissions, zero capabilities). Decide: record
  capabilities in scopes ride a distinguishing prefix (`cap:<model>:<name>`,
  wildcardable) so a scoped key's reach is explicit and consistent.
- Covering index `(subject_type, subject_id, model, capability)` on
  auth_record_grants; batch the subject walk (expand once, one
  `SUBJECT_ID.in(...)` query per subject type) and cache the expansion on the
  conduit for the request (C5). Revocation stays next-request-effective.
- Columns `granted_by` and nullable `expires_at` now, while the table is
  young (C8); expiry enforced in the check path, sweeper prunes.
- Matcher unification (C6): PermissionResolver adopts WildcardPermissions'
  tie-break (or documents the divergence with a test pinning it).
- Record-id contract (C9): grant() validates the record exists (symmetric
  with requireSubject) and the stringified pk is deterministic and <= 64.

zenit core (`common/security`):

- `KnownCapabilities`: model-Identifier-keyed registry of capability entries
  with Microcopy descriptions (the KnownPermissions emergent contract:
  absence never denies). This is what UIs enumerate.
- `RecordCapabilityChecker` SPI (the CsrfTokenStore pattern): core interface,
  deny-all default, zenit-auth installs the RecordGrants-backed impl.
  `AccessContext.hasCapability(model, recordId, capability)` rides it, and
  `WebSocketAuthenticator` gains the matching default method.
- The COMPOSITION RULES live in this SPI, declared not prose (C2): per model,
  a gate permission (global deny kills all grants; possession of any grant
  satisfies the gate -- the ManagePanel pattern, now named), an admin
  permission (bypasses grants; stated divergence from IAM's deny-wins), and
  an optional type-level permission meaning "all records of this model"
  (C7 -- the funnel's unrestricted branch keys on it, not on admin).
  Ownership: an optional owner-field declaration on the model gives the
  owning principal full capabilities implicitly -- owner lives ON the record,
  never as grant rows.
- Publish the precedence chain as one ordered list in the javadoc + docs
  (everyone -> groups -> group grants -> user grants -> negative grants ->
  gate deny -> type-level permission -> owner -> admin bypass).

zenit-cms + plumage:

- Generic record-access page: a subjects-x-capabilities matrix over
  `RecordGrants.listForRecord` + `KnownCapabilities`, attachable to any
  Resource (the subpages seam), with add/remove/expiry, preset support
  (presets expand to concrete rows at grant time, never stored as a name),
  and a "who can touch this record" view spanning both tiers (the expand
  equivalent, C8). Component work (matrix editor) lands in plumage.
- Naming discipline (C11): API identifiers always record-scoped
  (hasRecordCapability, KnownCapabilities); admin UI says "record access",
  never bare "capabilities".

hohenheim (consumer proof):

- Sites migrate: KnownCapabilities registers the site vocabulary ("manage"
  today), HohenheimAccess collapses onto the core SPI (gate =
  hohenheim.manage.access, admin = hohenheim.admin.access, type-level =
  NEW hohenheim.sites.manage_all), SiteAccessPage is DELETED in favor of the
  generic page, ManagePanel's checker/impossible() move onto the core
  helpers (C10: no side-effecting Panel constructor, Model.matchNone()).
- All existing site-access tests keep passing; that is the phase gate.

## Phase 1 -- Install roles (hohenheim)

- Settings group `roles.*`: proxy, dns, firewall, stacks, instances,
  processes, game -- each a boolean with restartRequired.
- Boot becomes role-conditional: no Docker probe / port-53 bind / nftables
  wiring for disabled roles; attention collectors and scheduled tasks gate
  on their role; admin nav hides panels of absent roles.
- Phase gate: a DNS-only install (the "LXC guest on an existing Proxmox"
  scenario) boots green with no Docker socket, no proxy ports, no nftables,
  and its admin shows only DNS/domains/settings.

## Phase 2 -- Instance tier core, Docker driver, ports, console

The fork itself. New model `InstanceModel` (instances): name, kind
(`container` now; `vm` reserved), server_id (host), runtime (`docker` now),
image/source config (SchemaField by runtime type -- the site_type pattern),
env (typed via template later; free map v1), resource limits, restart policy,
status, owner principal id. Soft delete. Localized: labels/descriptions come
from microcopy; instance names are user data (not localized) -- stated per
the localization rule.

- `InstanceRuntime` driver seam (server): create/start/stop/kill/destroy,
  status, stats, console attach, exec, logs-follow. First driver wraps
  DockerClient, which gains the missing primitives: `/containers/{id}/stats`,
  follow-logs streaming, attach (stdin/TTY) and TTY exec. Containers carry
  hohenheim instance labels (the stack ownership pattern).
- PERSISTENT port allocation registry (replaces the in-memory PortAllocator,
  which is lost on restart -- a live defect): table `port_allocations`
  (server_id, ip nullable, port, protocol, owner model+record, note),
  claim/release API, OS-probe on claim, existing consumers (managed
  processes, docker sites, stacks validation) migrate onto it. UDP is just a
  protocol value -- games need allocation bookkeeping, not proxying.
- Capabilities: KnownCapabilities registers the instance vocabulary -- view,
  power, console, files.read, files.write, snapshots, backups, config --
  enforced by an InstanceAccess funnel on the core SPI. Instances join the
  /manage tenant panel as a generated grant-scoped resource (accessCriteria);
  the admin panel gets the full resource. Per-capability action gating uses
  the zenit-cms permission seams, not hand-rolled ifs.
- Console: a terminal WebSocket handler over the driver's attach/exec,
  speaking the existing pl-terminal wire contract, grant-checked
  (capability `console`) with revalidateEvery -- the ProcessTerminalHandler
  pattern generalized. Process instances reuse the existing handler.
- Per-record scheduling (mechanism in zenit core `server/task`): a
  `record_schedules` table (model, record_id, cron, action token, payload,
  enabled) executed by ONE global sweeper ScheduledTask; action vocabulary
  registered per model (power actions, backup) with per-action capability
  requirements (the Pterodactyl schedule-authorization lesson: scheduling an
  action requires the capability the action itself needs). Consumer:
  instance nightly restart/backup.
- Stacks stay untouched: a stack remains the multi-service deployment unit
  (NetBird-class). An instance is a SINGLE runtime unit with delegation.
  Later phases may link a stack service to an instance view; not now.
  (Fix in passing: StackRuntime's `stack_health` alert string joins
  NotificationEvents.ALL.)
- Phase gate: create a Debian container instance from the admin, delegate
  console+power (not config) to a second user, that user operates it from
  /manage through the live terminal, reboot survives (allocations persist,
  containers re-adopted), full browser-test journey.

## Phase 3 -- Incus driver, snapshots, backups

- `IncusClient` (hand-rolled REST over unix socket / SSH, the DockerClient
  pattern; no SDK). Driver #2: system containers -- images from image
  servers, profiles, limits, exec/console websockets (this driver gets the
  TTY almost free).
- Server records declare their runtimes (docker socket/ssh, incus
  socket/ssh) -- the per-host seam becomes data.
- Snapshots: driver-level (Incus native; Docker driver = volume archive via
  the existing archive API), surfaced as capability-gated actions + rows.
- Backups: per-instance scheduled backups ride Phase 2's record schedules;
  retention per instance (the database backup retention pattern); restore
  flow with the settle-then-refuse status guards (the Pterodactyl
  restoring_backup lesson: a protected status gates power actions).
- Phase gate: an Incus Debian container with nightly snapshot schedule,
  snapshot-restore round trip proven in a browser test on a real Incus
  daemon (testcontainer or dedicated CI host; if neither is feasible the
  driver gets a fake + one live smoke script, stated honestly).

## Phase 4 -- Templates and the game surface

- Instance TEMPLATES (the egg analogue, hohenheim-owned): a template
  declares runtime+image/source, typed variable schema (zenit-forms fields
  -- real typed validation instead of Pterodactyl's rule-strings), port
  requirements, config files (StackFileModel generalized to instances),
  startup/env mapping, optional install step, and console line matchers:
  readiness ("done" line -> status Running) and graceful-stop (console
  command or signal) -- the config_startup/config_stop analogue, running on
  the driver's log stream; an observed stop command suppresses crash
  detection. Crash policy per instance (clean-exit-as-crash default for game
  templates, flap protection -- the supervisor already has the pattern).
- Template catalog admin + "create instance from template" flow (variables
  render as a normal zenit-form). Distribution format can wait; catalogs are
  rows first.
- Game wiring: Minecraft server template + Velocity template; a
  game-domains mapping (domain record -> backend instance) that MATERIALIZES
  as generated Velocity forced-hosts config (through the instance config-file
  mechanism) and DNS records (SRV/A via the existing DNS role) on change.
- Phase gate: Velocity + one Minecraft backend created from templates,
  reachable through a domain, readiness detected from console, graceful stop
  via console command, delegated player-admin operates the backend's console
  from /manage.

## Phase 5 -- Files, live stats, polish

- File manager over the driver seam (list/read/write/upload/download/rename/
  delete, capability files.read/files.write, size caps, per-template
  denylist), UI in zenit-cms/plumage as a generic component; SFTP is OUT of
  scope (revisit only with a concrete need).
- Live per-instance stats (docker stats / Incus metrics) streamed to the
  detail page (pl-chart/pl-sparkline); servers page gains storage/capacity
  awareness.
- Attention collectors for instances (crashed, backup failed, disk high).

## Phase 6 -- VMs (deferred until Jelle green-lights)

- Incus VM support through the same driver (kind=vm), cloud-init for Linux;
  Windows via PREPARED TEMPLATES (virtio + RDP pre-enabled) -- template-based
  provisioning, no in-panel OS install initially.
- Framebuffer console: grant-checked WS proxy over Incus VGA/SPICE + a
  plumage viewer component (the one genuinely new UI primitive). Until it
  ships, option 2 (raw console websocket for external clients) is the
  rescue hatch.
- Proxmox driver only when a concrete shared-iron host needs it.

---

## Cross-cutting rules

- Every phase lands with tests at the level it changes (unit + browser
  journeys for UI; the driver seams get fakes plus at least one real-daemon
  journey each, the DockerReclaimTest precedent).
- Localization: all new UI copy through microcopy catalogs (short keys +
  filters); instance/template names are user data and never localized;
  template descriptions ARE localizable (Microcopy on the template row).
- Docs/skills: zenit-cms-resources skill gains the record-access page;
  a new hohenheim skill for the instance/driver seam when Phase 2 lands.
- Order within the plan is dependency order, but Phase 0 and Phase 1 are
  independent and can interleave; Phases 4+ each assume the previous gate.

## Open decisions (need Jelle, flagged not assumed)

1. Capability naming in UI copy ("record access" vs "toegangsrechten" etc.)
   -- pick at Phase 0 UI time.
2. Whether stack services eventually BECOME instances or stay a separate
   tier forever -- deliberately deferred; revisit after Phase 3 with real
   usage.
3. Incus testing strategy if no daemon can run in CI (fake + live smoke vs
   testcontainer-in-privileged) -- decide at Phase 3 start.
