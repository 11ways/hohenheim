# Hohenext Implementation Roadmap

Hohenext is the evolution of this codebase from a reverse proxy / site dispatcher
(feature parity with the Node.js Hohenheim) into a Coolify/Dokploy-class
self-hosted platform. This is the **execution plan** — a dependency-ordered build
order. For the feature *inventory* and comparative analysis see `../../research/`
(feature-matrix, data-models, ui-inventory, framework-opportunities).

Guiding principle (from the project CLAUDE.md): improve the framework rather than
work around it. New cross-cutting capabilities land in Zenit/Protoblast/Plumage
once they have a real consumer here and have stabilized — not speculatively.

## Phase 0 — Finish the port (near-term parity)

The current port already closed most of the original gap audit (regex host
matching, scheduled maintenance tasks, system-user discovery + per-site uid
execution). Done since: custom error pages (Hawkeye `ErrorPages`), IPv6
dedicated listener (`ProxyServer.addIpv6Listener`), and connection-stage IP
reputation rejection.

CORRECTION (2026-08-10): the ban is enforced at `PublicTcpListener.handle`, on the real
(or PROXY-v2-declared) source, BEFORE any TLS bytes reach the internal HTTPS listener.
`SniKeyManager`'s own handshake-stage check reads `engine.getPeerHost()`, which in the
shipped topology is always the loopback hop (HTTPS terminates on `127.0.0.1` behind
`PublicTcpListener`), so it is defense-in-depth for a hypothetical direct-bind topology,
not the live enforcer.

Remaining parity items, roughly in value order:

- ~~Unix-socket upstreams/transport~~ WIRED: `use_ports=false` allocates a socket,
  children get `PATH_TO_SOCKET`, ProxySite `socket` upstreams (with regex placeholders) dial
  through `UnixSocketBridge`. Test-pinned.
- ~~Per-site traffic stats~~ DECIDED: statistics are out of scope (owner call, 2026-07);
  the `StatsCollector` scaffolding was deleted rather than wired.
- ~~TOTP/2FA~~ DONE in zenit-auth (2026-07-08): RFC 6238 enrollment/login gate/backup codes,
  `auth_totp` table; available on /account for every consumer app.
- ~~Stale-cert cleanup~~ DONE (`CleanOrphanCertificates`); ~~brute-force hostname guards~~
  DONE (`RegexHostnameGuardTest`). Still open: SNI cache staggered refresh / backoff /
  in-flight dedup (optimization).
- Persistent remember-me cookies + SSO — see Phase 5 (belongs in `zenit-auth`, not this app).

Completed in the 2026-07 parity/UX pass:

- Legacy nested ready IPC plus the current flat form, a 60-second ready timeout,
  uid+gid+HOME run-as identity, and Unix-socket process transport as the default.
- Optional front-proxy AF_UNIX listening (with configurable socket permissions),
  configurable additional Node.js discovery roots, trusted client-IP reuse for
  process affinity, and same-SAN ACME order serialization.
- Framework-backed immutable resource detail views, guided site/source forms,
  closed notification-event choices, localized custom admin tabs/attention
  messages, relation labels and first-run onboarding.
- HTTP-01 plus DNS-01 certificate requests. Wildcard names use DNS-01; manual
  TXT publication is one-shot/no-renewal, while the command publisher supports
  automatic issuance and renewal for any DNS provider with an operator hook.

Deliberately deferred (owner): per-request DB hit logging (was not performant in Node; revisit later).

## Phase 1 — Container layer (PaaS cornerstone) ← STARTED

Everything in the PaaS direction depends on talking to a container engine. Built
here first as a concrete consumer; promote to the framework once stable.

- [x] **`DockerClient`** — HTTP/1.1 over the daemon unix socket (no new deps),
      with a per-request watchdog timeout and chunked decoding. (`server/docker/DockerClient.java`)
  - [x] Daemon: `ping`, `version`
  - [x] Images: `listImages`, `pullImage`
  - [x] Containers: `createContainer`, `startContainer`, `stopContainer` (grace),
        `removeContainer`, `inspectContainer`, `listContainers`
  - [x] `containerLogs` (snapshot) + `exec` (stdout/stderr separated, exit code, env) —
        built on a shared multiplexed-stream demux + a binary-safe raw response path.
  - [x] Image `inspect`; container `logs` (follow/stream); networks; volume `create`/`list`
        STATUS 2026-08-12: all four shipped, box checked today. `inspectImage`
        (`DockerClient.java:252`), `followLogs` returning a `ContainerStream`
        (`DockerClient.java:735`), `createNetwork`/`listNetworks`
        (`DockerClient.java:461`/`:497`), `createVolume`/`listVolumes`
        (`DockerClient.java:407`/`:428`).
- [x] **`DockerSiteType`** — `hohenheim:docker` site type (registered; 11 site types now,
      see `architecture-site-types.md`).
      `DockerSiteRequestHandler` pulls the image if missing, creates+starts a container
      publishing the app port to an ephemeral `127.0.0.1` host port, reverse-proxies via
      `UpstreamForwarder`, reports health from `inspectContainer` State, and stop+removes
      on `destroy()`. Integration-tested against a live daemon.
  - [x] Admin UI settings form (`docker-settings.hwk` + dispatcher entry).
  - [ ] Follow-ups: async container start (don't block site-load on a slow pull, like
        GitDeployment's queue); optional shared-network mode instead of host-port publishing.

  STATUS 2026-08-12 (supersedes the two bullets above on their DETAIL, not their state):

  - The handler description is stale ARCHITECTURE. `DockerSiteRequestHandler` owns no
    container any more: `SiteInstances.ensureRunning` converges the site's
    `site_container` instance through the instance tier and the handler only reads the
    published loopback port back as its upstream, with no fallback container path
    (`DockerSiteRequestHandler.java:18-31`, `:46-67`).
  - There is no `docker-settings.hwk`, and no per-type settings template of any kind --
    the settings form is the derived dynamic `SchemaField` entry in `SiteResource`
    (`SiteResource.java:68-78`). The checkbox stays checked: the capability shipped, the
    named file never existed.
  - Async start is still genuinely OPEN -- construction converges synchronously and a long
    pull/build still blocks that site's load (AIDEV-NOTE at
    `DockerSiteRequestHandler.java:27-30`).
  - Shared-network mode: the DATABASE half shipped (a release container joins each attached
    database's link network, `docker/SiteDatabaseNetworks.java`, and `DockerSiteType`
    declares `containerRuntime()`), but the site's own upstream is still a published
    `127.0.0.1` port. The bullet stays unchecked for that half.

### Git provisioning: slot ownership model

When a git site sets `system_user_id`, both `git` and the build run as that uid
(so a compromised repo can't execute as the sudo-capable Hohenheim user). The
filesystem ownership is split so both users can operate:

- The per-site `data/<id>` dir (and the `active` symlink) stay owned by Hohenheim,
  which creates/flips the symlink and serves files.
- Each fresh slot (`a`/`b`) dir is created by Hohenheim, then `chown`ed to the site
  uid so the uid-dropped clone/build can write into it.
- Slot cleanup clears site-owned contents as the site uid (`sudo -u`), then removes
  the now-empty dir as Hohenheim (which has parent-dir write). This handles slots
  with mixed ownership left by older deploys.

Requires the Hohenheim user's NOPASSWD sudo (already needed for per-site process
uid switching). uid `0` (no `system_user_id`) keeps the original all-Hohenheim path.

## Phase 2 — Deployment pipeline

- [x] **Image build-from-source** — `DockerClient.buildImage(contextDir, tag, dockerfile)`
      tars a context and POSTs `/build`; `removeImage` for lifecycle. Pull/build run on a
      long (10 min) timeout; the transport handles binary bodies and Docker's
      RST-after-response on `/build`. Integration-tested (FROM alpine + RUN).
- [x] **Git → build → run integration** — a `docker` site sourced from git builds its
      image from the checkout's Dockerfile (`build_context` injected by `GitDeployment`)
      to a per-site tag and runs a container from it; falls back to pulling a remote
      `image` when not git-sourced. Integration-tested.
- [ ] Follow-ups: zero-downtime swap (unique container names + label-based orphan sweep,
      currently brief downtime on redeploy via stable name); buildpacks/Nixpacks
      (no-Dockerfile builds — needs the `pack` CLI); optional local registry.

  STATUS 2026-08-12: two of those three shipped; the box stays open only for the local
  registry. Zero-downtime swap is `SiteReleases.gatedSwap` (`docker/SiteReleases.java:393`,
  entered from `:319`/`:369`), which health-gates a candidate instance on the site's
  `health_path` before it takes traffic, with owner labels driving the orphan sweep
  (`docker/OwnerLabels.java`, `docker/OrphanActions.java`). Buildpacks shipped WITHOUT the
  `pack` CLI: `build/NixpacksBuilder.java` runs a sandboxed nixpacks DETECTION phase that
  emits a Dockerfile and then reuses the ordinary Dockerfile lane -- read the AIDEV-NOTE at
  `NixpacksBuilder.java:30-43` for why CNB was rejected and why exit codes are not a
  detection signal. It is operator-selectable per site through `DockerSiteType`'s `builder`
  field (`DockerSiteType.java:51-58`).

## Phase 3 — Database management ← COMPLETE (2026-08-12: marker corrected)

- [x] Provision Postgres/MySQL/Redis/Mongo as managed containers (built on Phase 1):
      `ManagedDatabase` runs the engine container with generated credentials + published
      port and exposes connection info. Readiness is probed via the engine itself
      (`pg_isready` etc. over TCP, inside the container) — a docker-published port accepts
      via docker-proxy before the DB can serve queries, so a port check is not enough.
      Postgres integration-tested; the other engines share the same machinery.
  - [x] **Ephemeral (tmpfs) data mode** — data dir on a RAM mount instead of a named
        volume (no host disk I/O; Postgres `initdb` no longer fsync-storms btrfs). Doubles
        as a CI/preview-DB feature; tests use it.
- [x] **Backup** — `backup`/`backupToFile` run the engine's dump tool (`pg_dump`/
      `mysqldump`) via `exec`, capturing clean stdout (creds via exec env). Postgres tested.
- [x] **Restore** — `restore`/`restoreFromFile` upload the dump via `PUT /archive`
      (`DockerClient.putArchiveFromDirectory`) and load it (`psql -f` / `mysql source`,
      ON_ERROR_STOP). Backup->drop->restore round-trip tested on Postgres and MySQL.
- [x] **Persistence + orchestration** — `DatabaseModel` (+ M015 `managed_databases`) stores
      desired config; `DatabaseService` ties it to `ManagedDatabase`: create provisions +
      records, list reads records, backup/restore resolve engine+credentials by name, destroy
      removes container and record. Model round-trip + Docker/DB integration tested.
- [x] **Admin UI** — `/databases` pages on `DatabaseService`: list (with live status), detail
      (full connection info), create form (provisions + persists; user/password optional and
      auto-generated, then shown on the detail page), per-row backup download, delete. Sidebar
      link + render tests.
- [x] **Backup scheduling** — daily `BackupDatabases` task dumps each running database to a
      timestamped file under `database.backup_path`, pruning to `database.backup_retention`.
- [x] **Redis/Mongo binary backup** — `getArchiveFile` fetches the native dump (redis `--rdb`,
      `mongodump --archive`) from the container's `/tmp` (writable layer; the archive API can't
      read tmpfs/volume mounts). All four engines back up; the scheduled task covers them all.
- [x] **Mongo restore** — `mongorestore --archive --drop`, binary-safe round-trip tested.
- [x] **Redis restore** — an RDB loads only at container startup, so the restore swaps the RDB
      into the data volume (exec `cp`; the archive API can't write into mounts) and restarts the
      server around it (`SHUTDOWN NOSAVE` + container start). Persistent databases only: a tmpfs
      data dir is wiped by the restart, so ephemeral redis rejects restore up-front. RDB magic is
      validated before anything destructive. Round-trip tested.
- [x] **Async provisioning** — create persists the record as "provisioning" and provisions in a
      background pool (`DatabaseService.createAsync`), so the request returns immediately; status
      (provisioning/active/failed) is tracked (M016) and shown in the list and detail pages.
- [x] **Binary backup download in the admin UI** — the Backup button covers all four engines via
      `DatabaseService.backupDownload` (SQL text or native binary dump, correct MIME/extension),
      on a new binary-safe response path (`Conduit.endWithBytes`, added to Zenit).
- [x] **Restore upload in the admin UI** — the detail page has a Restore card (multipart file
      upload to `/databases/:name/restore`); outcome is surfaced back on the detail page via
      query-string tokens. Audit-logged as `restored`. End-to-end tested against a live container.

- [x] **Sites ↔ databases: env injection** — `site_databases` join table (M032,
      `SiteDatabaseModel`: site, database, per-link `env_prefix`); `DatabaseEnvInjection`
      derives `{PREFIX}_HOST/PORT/USER/PASSWORD/NAME/URL` (plus `DATABASE_URL` for the site's
      FIRST link) at every process spawn from live Docker state — nothing baked into stored
      settings, so re-provisioning/rotation is picked up on the next start. Operator-authored
      env vars override injected ones. Reachability enforced at LINK time (site type must run
      host processes — `SiteTypeInfo.supportsEnvInjection`; database must be on the local
      server): docker-site containers can't reach a 127.0.0.1-published host port until
      shared-network mode lands. Unresolvable links inject NOTHING (loud
      `hohenheim.db_injection.unresolved` slog + dashboard attention item; an unavailable
      primary never hands `DATABASE_URL` to another database). Admin: Databases tab on
      env-capable sites (attach/detach, variable preview), used-by on the restore tab,
      delete guard on attached databases. End-to-end tested (real container → linked site →
      spawned child's env).

Phase 3 is complete: provision, persistence, orchestration, admin UI (backup download + restore
upload), scheduled backups, backup + restore for all four engines (redis restore: persistent
only), and site attachment with derived-env injection.

STATUS 2026-08-12: the section heading carried "IN PROGRESS" while its own closing paragraph
said complete; the heading was the stale half and is now COMPLETE. Two details in the env
bullet above have since moved: a DOCKER site CAN now receive injected credentials (its
release container joins each attached database's link network,
`docker/SiteDatabaseNetworks.java`, and `DockerSiteType.supportsEnvInjection()` returns true
-- `DockerSiteType.java:143`), so "host processes only" no longer holds, and the reachability
rule is now expressed as `SiteTypeInfo.containerRuntime()` (same server for a container,
local server for a host process -- `SiteTypeInfo.java:33-41`). The genuinely active work is
NOT a numbered phase in this file: it is the INSTANCE tier plus the Phase 0 security
baseline, both tracked in `instance-tier-plan.md`.

## Phase 4 — Multi-server ← COMPLETE (SSH-transport remote half untestable locally)

- [x] **Remote Docker over SSH** — pluggable `DockerTransport` (unix socket vs process-stdio);
      `DockerClient.overSsh(target)` drives a remote daemon via `ssh <host> docker system
      dial-stdio` (no new deps -- shells out to ssh like tar/git). `ProcessDockerTransport` keeps
      stdin open while reading (dial-stdio truncates the response on stdin EOF otherwise). Tested
      end-to-end against local `docker system dial-stdio`. Promote the transport to Protoblast once stable.
- [x] **Server inventory** — `ServerModel` (M017) persists Docker hosts; `ServerService` ensures
      the implicit `local` host, builds a `DockerClient` per server (local socket or SSH), and
      reports reachability (ping). Admin UI at `/servers` lists hosts with reachability, adds remote
      SSH servers, and removes them (not local).
- [x] **Route managed databases to a chosen host** — `managed_databases.server_name` (M018);
      `DatabaseService` resolves the `ManagedDatabase` per record via `ServerService` (local socket
      or remote SSH); create form offers a host dropdown; list/detail show the host. (Local path
      fully tested; the remote path shares the code with a different transport.)
- [x] **Route Docker sites to a chosen host** — `DockerSiteType` has a `server` setting; the
      handler builds its client via `ServerService` for a named remote host (local stays a direct
      client). The docker site form exposes the field.
- [x] **Resource monitoring** — `DockerClient.info()` host snapshot; `ServerService.summaries`
      reports CPUs, memory, container counts, and images per server (one call that also serves as
      the reachability probe). Shown on `/servers`. (Follow-up: per-container `/stats` deltas, disk.)

Phase 4 is functionally complete: register remote hosts, see their reachability + resources, and
provision databases and Docker sites on a chosen host. The SSH transport's remote leg is tested
only via the equivalent local `docker system dial-stdio` (no remote host available here).

## Phase 5 — Platform services

- [x] Notifications: webhook channels (Slack / Discord / generic JSON), admin UI + test send.
      Distinct kinds (email / Telegram) still open.
- [x] WebSocket + scheduled tasks are Zenit core capabilities now and consumed here (the
      process terminal rides `WebSocketEndpoint`; all maintenance tasks extend `ScheduledTask`).
      Still open as a CONSUMER feature: live-streamed deploy/build logs (stored logs only today).
- [x] **Auth via `zenit-auth`** — hohenheim's hand-rolled auth is replaced by the framework module.
  Native email/password login plus an optional Proteus SSO provider (registered from
  `auth_proteus.*` settings when enabled). `ZenitAuth.init` installs the session store + CSRF +
  `/login`/`/setup`/`/account`/`/admin`; admin areas gated via `AuthRegistry` baselines; all POST
  forms carry `{% csrf() %}`. Password login + SSO-provider integration are tested (fake provider).
  - Follow-ups: per-site SSO gate for proxied sites (Node's `proteus_realm_permission` -> 403),
    persistent remember-me cookie, OIDC id_token signature verification (needs a JOSE dep),
    reskin zenit-auth's auth templates to the hohenheim layout, and a `users`->`auth_users`
    backfill if any pre-existing local accounts must survive (cutover assumed fresh `/setup`).
  - **Build chain:** `zenit-auth` is not yet known to `zenit-dev`; it was published to mavenLocal
    via its own gradle. Teach `zenit-dev` about it (chain: protoblast..zenit -> zenit-auth -> app)
    so consumer rebuilds refresh it.

## Cross-cutting framework work (pulled in on demand)

| Need | Home | Notes |
|------|------|-------|
| Docker API client | started in-app | promote once a SECOND consumer exists (likely another hohenext tier) |
| SSH client | n/a | Docker-over-SSH shells out to `ssh docker system dial-stdio`; no SSH library to promote |
| WebSocket / SSE | DONE (zenit core) | `WebSocketEndpoint` + `WebSocketHandler`; terminal consumes it |
| Background job/queue | zenit tasks DONE; deploy queue stays app | `ScheduledTask`/cron in core; the coalescing per-site deploy queue needs a second consumer |
| Plumage components | Plumage | terminal viewer, data table, toast, stepper, … (see framework-opportunities.md) |

## Optional authoritative DNS

Designed in [authoritative-dns.md](authoritative-dns.md); delivery phases 1-3
are SHIPPED (2026-07-17):

- `DnsZoneModel`/`DnsRecordModel` (M034), validation through `DnsRecordCodec`,
  immutable `DnsZoneSnapshot`s swapped atomically by `DnsZoneStore`, and
  framework-managed SOA serials (every zone/record mutation bumps + reloads).
- Authoritative-only UDP+TCP listeners (`DnsServer`, `dns.enabled` off by
  default, `dns.bind_address`/`dns.port` settings): AA answers, NXDOMAIN vs
  NODATA with SOA authority, empty non-terminals, wildcard synthesis, in-zone
  CNAME chasing, referrals for delegated children, EDNS 1232 with UDP
  truncation, REFUSED for out-of-zone names/recursion/transfers. Wire parsing
  is dnsjava; lookup/authority/policy are Hohenheim's.
- Admin: DNS Zones resource (+ nav), zone-scoped Records and Zone-file tabs,
  zone-file export/import (import replaces operator rows, ACME rows survive),
  attention items for failed listeners and NS-less zones.
- Internal ACME TXT publisher (`dns_publisher=internal`): DNS-01 wildcard
  certificates issue and renew against the hosted zones with no provider
  credentials or shell hook; the propagation wait is skipped because the
  snapshot swap serves immediately.

Still open (phase 4-5, the production-redundancy threshold): AXFR + TSIG +
NOTIFY with an independent secondary, secondary-freshness UI, and DNSSEC as a
separate project. A one-box home deployment works but the registrar delegation
still applies and a single server remains a single point of failure.

STATUS 2026-08-12 -- SUPERSEDED, and it contradicted the very next section of this same
file. The "DNS federation / hidden primary" section below already reported AXFR + TSIG +
NOTIFY as shipped; the code agrees with the LATER section, so treat this paragraph as
history. Evidence: `server/dns/AxfrResponder.java`, `server/dns/DnsTsig.java`,
`server/dns/DnsNotifier.java`, `server/dns/SecondaryZoneService.java` (SOA
refresh/retry/expire and NOTIFY-triggered pulls), with the secondaries admin at
`server/cms/DnsZoneSecondariesPage.java` and `DnsZonePeerResource.java`. DNSSEC is no
longer a separate future project either: `server/dns/DnsSecSigner.java`,
`DnsSecKeys.java`, `DnsSecMaterial.java`, migration `M037_AddDnssec.java`, and signing is
wired into the zone snapshot build (`DnsZoneStore.java:219`). What remains true: the
registrar delegation is still yours to configure, and one box is still one box.

## Dev tunnel (2026-07-17): SHIPPED

Remote dev sites under one wildcard "Dev namespace" site: dev servers register
outbound over `/ws/dev-tunnel` (ngrok-style, but first-party and
wildcard-TLS-correct), claim `<name>.<namespace-domain>`, and are served
through the real proxy pipeline via multiplexed tunnel streams. The generic
mechanism (protocol, client, credit-window streams) lives in zenit
(`server/devtunnel`); Hohenheim ships the dev-namespace site type, lease
registry, bridge, offline page, and the Dev sessions tab. `zenit-dev start`
auto-registers when `devTunnel` is configured in its machine config. See
`docs/dev-tunnel.md`.

## DNS federation / hidden primary (2026-07-17): SHIPPED (replication core)

Standards-based zone replication so Hohenheim can run as a hidden primary
(port 53 closed at the office) with a public secondary (VPS Hohenheim, or
NSD/Knot) meeting the two-nameserver production threshold. TSIG-authenticated
AXFR both directions, NOTIFY, a secondary-zone subsystem (SOA
refresh/retry/expire, NOTIFY-triggered pulls), per-zone primary/secondary
roles, a peer registry + secondaries admin, and an ACME propagation wait so
DNS-01 issuance blocks until secondaries serve the challenge. Proven over real
sockets (DnsFederationTest). See docs/dns-federation.md.

Central editing SHIPPED too (2026-07-17): an owner-side records API
(/api/dns/zones/{origin}/records, znit_ keys, primary-only, shared
validation pipeline) plus read-through + edit-forwarding on a secondary
zone's Records tab (replica read-only fallback when the owner is down).
One instance is now the single pane for every federated zone. Remaining:
phase 5 (DNSSEC) and response-rate-limiting.

STATUS 2026-08-12: that last sentence is spent -- both landed. DNSSEC signing runs on every
zone snapshot build (`DnsZoneStore.java:219` calling `DnsSecSigner.sign`, keys in
`DnsSecKeys`/`DnsSecMaterial`, storage in `M037_AddDnssec`), and response-rate-limiting is
`server/dns/DnsRateLimiter.java` (classic BIND/NSD RRL semantics, IPv4 /24 and IPv6 /56
buckets) checked on both the FORMERR and the answer path in `DnsServer.java:207` and `:221`,
test-pinned by `DnsRateLimiterTest`.
