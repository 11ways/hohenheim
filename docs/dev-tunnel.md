# Dev tunnel: remote dev sites on wildcard subdomains

Status: SHIPPED (2026-07-17).

## What it does

A "Dev namespace" site in Hohenheim owns a wildcard domain (for example
`*.dev.kumulus.11ways.be`). Remote dev servers -- typically Zenit apps started
with `zenit-dev start` on a laptop or LXC container anywhere -- open an
OUTBOUND WebSocket to Hohenheim's admin server (`/ws/dev-tunnel`), present the
namespace's registration token, and claim a subdomain name. From that moment
`https://<name>.dev.kumulus.11ways.be` is served by that dev machine, with TLS
terminated at Hohenheim by the namespace's wildcard certificate (issue it once
via DNS-01, ideally against the built-in authoritative DNS server).

Multiple names are live concurrently under ONE namespace site; nothing is
provisioned per dev site. A name with no live registration renders a branded
503 "dev site offline" page. Re-registering a name replaces the older
registration (the newest dev server wins; the replaced client is told and
stops reconnecting).

## How traffic flows

The proxy remains a full HTTP reverse proxy: TLS + SNI, hostname routing,
banning, access log and header forwarding all run exactly as for any site.
Undertow's proxy engine forwards the request (with `X-Forwarded-For/Proto/
Host`) to a per-lease loopback bridge (`DevTunnelBridge`); each bridged TCP
connection becomes one multiplexed stream over the registration WebSocket
(4-byte stream id + payload frames, DRY control frames, 512 KiB per-stream
credit windows, TCP half-close propagation). The zenit `DevTunnelClient`
mirrors each stream onto a local TCP connection to the app's own HTTP port, so
HTTP keep-alive and WebSocket upgrades work end-to-end without any HTTP
re-implementation inside the tunnel.

The Zenit app sees an ordinary proxied request from loopback and its existing
trusted-proxy handling does the rest (`getRemoteIp()`, `isEffectivelyHttps()`,
request origin). The client also adopts the announced public origin as
`network.main_url` when unset.

## Operator setup

1. Create a site of type "Dev namespace" (a registration token is minted
   automatically; it is a secret field on the site's settings).
2. Add the wildcard domain (`*.dev.example.com`) to the site.
3. Issue a wildcard certificate for it (DNS-01; the internal publisher makes
   this fully first-party) -- SNI picks it up automatically.
4. On each dev machine, add to `~/.config/zenit-dev/config.json`:
   `{"devTunnel": {"url": "wss://hohenheim.example/ws/dev-tunnel", "token": "zdev_..."}}`.
5. `zenit-dev start` in any Zenit project registers as `<project-name>` and
   prints the public URL (`--tunnel-name=<slug>` overrides, `--no-tunnel`
   skips). Non-zenit-dev apps set the `dev_tunnel.*` Zenit settings (or
   `ZENIT__DEV_TUNNEL__*` env vars) directly.

The live registrations are visible on the site's "Dev sessions" tab.

## Protocol and altitude notes

- The tunnel protocol (control vocabulary, frame codec, stream pump, credit
  window) and the client live in ZENIT (`be.elevenways.zenit.server.devtunnel`)
  as the generic front-proxy-registration mechanism; Hohenheim implements the
  server half (lease registry, bridge, dev-namespace site type). A future
  non-Hohenheim proxy can reuse the client unchanged.
- Registration is in-band (first control frame, constant-time token compare,
  10s auth window); the WS handshake itself is unauthenticated by design.
- Heartbeats: client pings every 30s; either side drops the connection after
  90s of silence. The client reconnects with backoff (1s..30s) until stopped
  or replaced.
- Localization stance: admin UI strings (tab, fields, empty states) are
  microcopy (en+nl). The proxy-side offline page is non-localized English,
  matching the proxy's existing 404/502 pages which render outside any locale
  context.

## Known limits (deliberate)

- The per-lease loopback bridge (like every loopback upstream Hohenheim uses)
  is dialable by any LOCAL process on the Hohenheim host and enters the tunnel
  below the dispatcher's access-list/auth gates. On a single-operator host
  this is the existing posture; treat local shell access on the proxy host as
  equivalent to network access to the registered dev servers.
- Unregistered tunnel connections are capped (32 concurrent) and dropped
  after a 10s auth window; registration refusals stop the client's retry
  loop.

- Leases are in-memory: a Hohenheim restart drops them and clients re-register
  on their next reconnect attempt (within ~30s).
- One WebSocket per dev site; a very chatty site shares that connection's
  ordering lane (head-of-line) -- irrelevant at dev traffic levels.
- The offline page and lease registry are per-instance (no clustering), like
  the rest of Hohenheim's process state.
