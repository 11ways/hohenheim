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
dedicated listener (`ProxyServer.addIpv6Listener`), and TLS-handshake-stage IP
reputation rejection (`SniKeyManager` refuses a cert to a banned peer).

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
  - [ ] Image `inspect`; container `logs` (follow/stream); networks; volume `create`/`list`
- [x] **`DockerSiteType`** — `hohenheim:docker` site type (registered, 8 types now).
      `DockerSiteRequestHandler` pulls the image if missing, creates+starts a container
      publishing the app port to an ephemeral `127.0.0.1` host port, reverse-proxies via
      `UpstreamForwarder`, reports health from `inspectContainer` State, and stop+removes
      on `destroy()`. Integration-tested against a live daemon.
  - [x] Admin UI settings form (`docker-settings.hwk` + dispatcher entry).
  - [ ] Follow-ups: async container start (don't block site-load on a slow pull, like
        GitDeployment's queue); optional shared-network mode instead of host-port publishing.

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

## Phase 3 — Database management ← IN PROGRESS

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

Phase 3 is complete: provision, persistence, orchestration, admin UI (backup download + restore
upload), scheduled backups, and backup + restore for all four engines (redis restore: persistent
only).

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
- Background job/queue + real-time logs (WebSocket or SSE) — Zenit capabilities.
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
| Docker API client | started in-app | promote to Protoblast/Zenit once stable |
| SSH client | Protoblast | Phase 4 |
| WebSocket / SSE | Zenit | real-time logs/stats, Phase 1+ |
| Background job/queue | Zenit | deploys, builds, backups |
| Plumage components | Plumage | terminal viewer, data table, toast, stepper, … (see framework-opportunities.md) |
