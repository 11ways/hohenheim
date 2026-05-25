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
matching, unix-socket upstreams with regex placeholder substitution, scheduled
maintenance tasks, system-user discovery + per-site uid execution). Remaining
items worth verifying/closing, roughly in value order:

- Custom error-page templates (proxy error pages are hardcoded HTML; move to Hawkeye templates)
- IPv6 dedicated listener (`proxy.ipv6_address` is wired but the listener is missing)
- SNI cache staggered refresh / fetch backoff / in-flight dedup
- Persistent remember-me cookies; SSO integration (defer if not needed yet)

## Phase 1 — Container layer (PaaS cornerstone) ← STARTED

Everything in the PaaS direction depends on talking to a container engine. Built
here first as a concrete consumer; promote to the framework once stable.

- [x] **`DockerClient`** — HTTP/1.1 over the daemon unix socket (no new deps),
      with a per-request watchdog timeout and chunked decoding. (`server/docker/DockerClient.java`)
  - [x] Daemon: `ping`, `version`
  - [x] Images: `listImages`, `pullImage`
  - [x] Containers: `createContainer`, `startContainer`, `stopContainer` (grace),
        `removeContainer`, `inspectContainer`, `listContainers`
  - [ ] Image `inspect`/`remove`; container `logs` (follow); `exec`; networks + volumes
- [x] **`DockerSiteType`** — `hohenheim:docker` site type (registered, 8 types now).
      `DockerSiteRequestHandler` pulls the image if missing, creates+starts a container
      publishing the app port to an ephemeral `127.0.0.1` host port, reverse-proxies via
      `UpstreamForwarder`, reports health from `inspectContainer` State, and stop+removes
      on `destroy()`. Integration-tested against a live daemon.
  - [ ] Follow-ups: admin UI settings form (`hh-docker-settings.hwk` + dispatcher entry);
        async container start (don't block site-load on a slow pull, like GitDeployment's
        queue); optional shared-network mode instead of host-port publishing.

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
- [ ] **Git → build → run integration** ← NEXT — let a `docker` site source from git
      (reuse `GitSiteRequestHandler`/`GitDeployment`): on deploy, clone, `buildImage`
      from the checkout's Dockerfile to a per-site tag, then run a container from that
      image (via `DockerSiteRequestHandler`). Old container/image cleaned up after swap.
- [ ] Buildpacks/Nixpacks (no-Dockerfile builds); optional local registry; zero-downtime swap.

## Phase 3 — Database management

- Provision Postgres/MySQL/Redis/Mongo as managed containers (Phase 1).
- Backups + restore + scheduling; connection info surfaced in the admin UI.

## Phase 4 — Multi-server

- SSH client (Protoblast) → drive a remote Docker daemon over SSH.
- Server inventory + resource monitoring (Sentinel-style lightweight agent).

## Phase 5 — Platform services

- Notifications: email / Slack / Discord / Telegram / webhooks.
- Background job/queue + real-time logs (WebSocket or SSE) — Zenit capabilities.
- Auth: OIDC + TOTP/2FA, RBAC (design already in `../../research/zenit-auth-architecture.md`).

## Cross-cutting framework work (pulled in on demand)

| Need | Home | Notes |
|------|------|-------|
| Docker API client | started in-app | promote to Protoblast/Zenit once stable |
| SSH client | Protoblast | Phase 4 |
| WebSocket / SSE | Zenit | real-time logs/stats, Phase 1+ |
| Background job/queue | Zenit | deploys, builds, backups |
| Plumage components | Plumage | terminal viewer, data table, toast, stepper, … (see framework-opportunities.md) |
