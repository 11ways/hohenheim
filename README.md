# Hohenheim

Hohenheim is a reverse proxy and site dispatcher. It routes incoming HTTP/HTTPS
requests to backends based on hostname, and manages the lifecycle of those
backends: containers and virtual machines it manages, static files, arbitrary
proxied upstreams, or git-sourced apps it builds and deploys itself.

This is the **Java/Zenit rewrite** of the original Node.js/AlchemyMVC Hohenheim.
It has feature parity with the Node version and goes well beyond it:

- **Reverse proxy + site dispatcher**: a site has one typed **upstream**
  (`static`, `redirect`, `address`, `instance`, `tls_passthrough`,
  `dev-namespace`) and no opinion about how the thing upstream is run.
- **Instances** are the workloads: Docker containers, Incus system containers,
  KVM virtual machines, persistent workspaces and git-sourced applications with
  sandboxed image builds and health-gated releases.
- **Managed databases** (create, back up, attach to an instance).
- **Automatic HTTPS** via Let's Encrypt, including **wildcard certificates**
  through DNS-01.
- **Authoritative DNS server** (optional) — host your own zones, with **DNSSEC**,
  **hidden-primary/secondary federation**, and **dynamic DNS** (dyndns2), so you
  can drop a hosted DNS control panel entirely.
- **Dev tunnel** — expose a dev server running anywhere on a public
  `*.dev.example.com` subdomain with a valid certificate, ngrok-style.
- **Notifications**, an **automation API** (hashed `znit_` keys), **native IP
  banning** (threat scoring + nftables enforcement, spamservice-backed IP
  reputation), and a full admin UI.

Each subsystem has a deeper design doc under [`docs/`](docs/).

## Requirements

### Java

- **JDK 25 or newer.** The server is compiled against Java 25.

### Database

- **SQLite by default.** Bundled via `sqlite-jdbc`; no external DB server. The
  file path is controlled by the `database.path` setting (default:
  `hohenheim.db` in the working directory). SQLite is recommended: Hohenheim is
  a single-node control plane whose whole state is one file (trivial to back up
  and move), and it never needs an external database to boot.
- **Other engines are supported.** Set `database.url` to a JDBC URL and
  Hohenheim uses that instead, inferring the engine from the scheme:
  PostgreSQL (`jdbc:postgresql://…`), MySQL/MariaDB (`jdbc:mysql://…` /
  `jdbc:mariadb://…`), Firebird (`jdbc:firebirdsql://…`), or DuckDB
  (`jdbc:duckdb:…`). Set `database.username` / `database.password` for the
  server engines. For CockroachDB (which shares PostgreSQL's URL scheme) set
  `database.engine = cockroach` explicitly. Use a server engine only if you
  need several Hohenheim instances sharing one config database; otherwise
  SQLite is the better fit.

### Git

- `git` on `PATH` if you plan to use git-sourced applications or workspaces.

### Docker

- Required for the `instances`, `databases` and `stacks` roles: application
  releases, managed database engines and stack services all run as containers.
  `tools/install-host.sh` installs Docker CE from Docker's apt repo whenever one
  of those roles is requested.

## Privileged ports

Hohenheim wants to listen on 80 and 443 (reverse proxy) and, if you enable the
DNS server, UDP **and** TCP 53. On Linux all of these are privileged ports and
a non-root process cannot bind them without help. A single capability,
`CAP_NET_BIND_SERVICE`, covers every one of them (TCP and UDP alike), so the
options below apply whether or not you run the DNS server.

**The JVM binary — not the `.jar` — is what needs permission.** A `.jar` is a
zip; capabilities are filesystem xattrs on the executable inode. You have four
realistic options:

### Option 1: Systemd `AmbientCapabilities` (recommended)

No file modification. systemd grants the capability to the process at exec
time:

```ini
[Service]
AmbientCapabilities=CAP_NET_BIND_SERVICE
```

See the [full unit file](#systemd) below. Survives JRE upgrades automatically.
Works on systemd 229+ (2016 and later).

### Option 2: Dedicated JRE copy with `setcap`

Equivalent of the Node `hohenode` pattern. You **cannot** just copy the `java`
launcher alone — it locates `libjli.so` / `libjvm.so` relative to its own
install directory via `/proc/self/exe`, so a stand-alone copy will fail with
`error while loading shared libraries: libjli.so`.

Instead, copy the whole JDK:

```bash
sudo cp -a /usr/lib/jvm/java-25-openjdk /opt/hohenheim-jdk
sudo setcap 'cap_net_bind_service=+ep' /opt/hohenheim-jdk/bin/java
```

Then point your launcher at `/opt/hohenheim-jdk/bin/java`. Symlinks do **not**
carry capabilities — the real binary must be the capable one. To strip the
capability later:

```bash
sudo setcap -r /opt/hohenheim-jdk/bin/java
```

### Option 3: Firewall redirect

Run Hohenheim unprivileged on high ports and DNAT 80/443 at the kernel:

```bash
# nftables
sudo nft add rule inet nat prerouting tcp dport 80  redirect to :8080
sudo nft add rule inet nat prerouting tcp dport 443 redirect to :8443

# or iptables
sudo iptables -t nat -A PREROUTING -p tcp --dport 80  -j REDIRECT --to-ports 8080
sudo iptables -t nat -A PREROUTING -p tcp --dport 443 -j REDIRECT --to-ports 8443
```

Set `proxy.http_port = 8080` and `proxy.https_port = 8443` in
`settings/hohenheim.dry` (the `proxy.*` group is Hohenheim's own, not Zenit's;
putting it in `local.dry` is ignored). Clients see port 80/443 unchanged.

### Option 4: `authbind`

Legacy fallback: `authbind --deep java -jar hohenheim-server.jar`. Only helps
with `bind()`, nothing else. Option 1 supersedes it.

## Root grants

Hohenheim runs as an unprivileged service user and reaches for root through
`sudo -n` in exactly three places. `tools/install-host.sh` writes the matching
sudoers files and validates each with `visudo -cf`; the grants are narrow, not
the blanket `NOPASSWD: ALL` earlier versions of this document described.

- **nftables** (`proxy` or `firewall` role). `NftRunner` executes
  `sudo -n -- nft ...`; that is the one root-command seam of the ban enforcer.

  ```
  # /etc/sudoers.d/hohenheim-nft
  hohenheim ALL=(root) NOPASSWD: /usr/sbin/nft
  ```

  The installer substitutes whatever `command -v nft` reports, falling back to
  `/usr/sbin/nft`.

- **Volume root** (`instances` role, or `--volume-root-size`). Btrfs subvolume
  and quota operations plus the ownership fixes that go with them:

  ```
  # /etc/sudoers.d/hohenheim-volumes
  hohenheim ALL=(root) NOPASSWD: /usr/bin/btrfs, /usr/bin/chown, /usr/bin/chmod, /usr/bin/mkdir, /usr/bin/rm
  ```

- **The managed spamservice child.** This is the only process Hohenheim still
  spawns under a different unix uid, and the setsid + `sudo --preserve-env -u
  #<uid>` + `prlimit` + `setpriv --no-new-privs` chain in `SystemUsers`
  (`server/SystemUsers.java`) is built for it alone. The uid comes from the
  Spamservice installation singleton's `system_user_id`; there is no per-site
  or per-instance uid switching any more. Containerised workloads are isolated
  by their runtime (Docker or Incus), not by a uid drop on the host.

If you configure that child, `/usr/bin/sudo` must be owned by root with its
setuid bit intact on a filesystem mounted without `nosuid`, the host must
provide util-linux `/usr/bin/setsid` with `--wait`, and the unit must keep
`NoNewPrivileges=false`, because systemd otherwise blocks `sudo` from raising
privileges. Do not narrow `CapabilityBoundingSet` to only
`CAP_NET_BIND_SERVICE`: that also constrains the setuid `sudo` child. The
child's environment is reduced to an explicit map and carried through
`--preserve-env`, so secrets never appear in the inspectable argument vector.

The unit's `KillMode=control-group` asks systemd to reap everything left in the
service cgroup when the service stops. That is lifecycle cleanup, not a
security sandbox.

## File descriptor limits

As a reverse proxy, Hohenheim opens many concurrent connections. The default
per-process limit (usually 1024) is not enough under load. Raise it.

- **Systemd:** `LimitNOFILE=60000` in the `[Service]` block (see below).
- **Shell:** `ulimit -n 60000` before launch.
- **System-wide** (`/etc/security/limits.conf`):
  ```
  hohenheim soft nofile 60000
  hohenheim hard nofile 60000
  ```

## Installation

### 1. Get the source

The Java rewrite lives on the **`java-rewrite`** branch. GitHub's default branch
for this repository is still `master`, which holds the original
Node.js/AlchemyMVC Hohenheim, so a plain `git clone` checks out the wrong
project:

```bash
git clone -b java-rewrite https://github.com/11ways/hohenheim.git
cd hohenheim

# Build (uses zenit-dev; do not invoke ./gradlew directly)
zenit-dev build
```

The build produces `build/libs/hohenheim-<version>-server.jar`.

### 2. Install the host

`tools/install-host.sh` turns a fresh Debian host into a Hohenheim node and is
THE install procedure: it does the host preflight, base packages, JDK, optional
Docker CE, the service user, the directory layout, the narrow sudoers grants,
the btrfs volume root, the seeded settings files, port 53 handling, the systemd
unit (heap sized from `MemTotal`), the migration run, and the service start plus
a `/api/health` wait. Every step checks its own precondition and skips when it is
already satisfied, so re-running it on a live host is a no-op that prints what it
found.

```bash
sudo ./tools/install-host.sh \
    --jar build/libs/hohenheim-<version>-server.jar \
    --roles proxy,dns,firewall,instances \
    --main-url https://panel.example.com \
    --admin-email ops@example.com
```

Run `./tools/install-host.sh --help` for the full option list, and
`--dry-run` (which does not need root) to print the plan without executing
anything. The roles are `proxy`, `dns`, `firewall`, `stacks`, `databases` and
`instances`; an unknown role name is refused rather than ignored. The admin
listener defaults to `127.0.0.1:3000` because the panel is meant to be published
through the proxy, and the first administrator is created afterwards through the
panel's own `/setup` page. The rest of this README documents what the script
automates; [`docs/deploy-native.md`](docs/deploy-native.md) is the long-form
version of the same procedure.

### Directory layout for a production install

```
/opt/hohenheim/
├── hohenheim-server.jar          # fat jar (rename the built artifact)
├── public/                       # static assets (shipped with the repo)
├── settings/
│   ├── local.dry                 # Zenit server overrides (not tracked)
│   ├── local.dry.example         # Zenit reference
│   ├── hohenheim.dry             # Hohenheim proxy/app settings (not tracked)
│   ├── hohenheim.dry.example     # Hohenheim reference
│   └── auth.dry                  # zenit-auth overrides (external_base_url)
├── data/                         # instance volumes, backups, build contexts
├── hohenheim.db                  # SQLite database (auto-created)
└── logs/                         # access + domain-miss logs
```

## Configuration

Zenit server settings, including the admin listener, live in `settings/local.dry`.
Hohenheim's own settings live in `settings/hohenheim.dry`. Copy the matching
[`local.dry.example`](settings/local.dry.example) and
[`hohenheim.dry.example`](settings/hohenheim.dry.example) files and uncomment
what you need. `ZENIT__*` and `HOHENHEIM__*` environment variables override the
corresponding files.

Most-useful keys:

```
{
    // Zenit admin UI
    "network": {
        "port": 3000,                      // admin UI port (default 3000)
        "trusted_proxies": "loopback"      // peers whose forwarded headers are trusted
    }
}
```

The default trusts forwarded headers only from a proxy on the same machine. Before
placing the admin listener behind a proxy on another host, set `trusted_proxies` to
that proxy's literal IP or CIDR. Hostnames are deliberately refused so request-time
trust decisions can never trigger DNS.

`settings/hohenheim.dry`:

```
{
    // Which subsystems this installation runs. A disabled role does not start,
    // declares no scheduled tasks and removes its admin surfaces. All default
    // to true; every one of them requires a restart to change.
    "roles": {
        "proxy":     true,   // public listeners, sites, domains, certificates, access lists
        "dns":       true,   // zone store, federation, authoritative server (dns.enabled still gates the listener)
        "firewall":  true,   // ban enforcement and spamservice reputation
        "stacks":    true,   // managed Docker stacks (needs a reachable Docker daemon)
        "databases": true,   // managed database provisioning and backups
        "instances": true    // the instance tier (needs a reachable Docker daemon)
    },

    // Reverse proxy
    "proxy": {
        "http_port":  80,                  // public HTTP
        "https_port": 443,                 // public HTTPS
        "force_https": true,               // redirect HTTP to HTTPS globally
        "fallback_address": "http://localhost:8081",  // tried when no site matches
        "ipv6_address": "",                // optional extra listener
        "first_port": 4748,                // first port handed to managed child processes
        "trusted_proxy_keys": [],           // secret X-Hohenheim-Key values accepted from HTTP proxies
        "proxy_protocol_trusted_sources": [], // literal IP/CIDR peers allowed to send inbound PROXY v2
        "connection_prologue_timeout_seconds": 5,
        "max_pending_connections": 1024,
        "max_public_connections": 10000
    },

    // Let's Encrypt
    "ssl": {
        "letsencrypt_enabled": true,
        "letsencrypt_email":   "ops@example.com",
        "letsencrypt_staging": false,      // flip to true for testing
        "dns_hook_command":    "",         // script for DNS-01 wildcard certs (optional)
        "dns_propagation_seconds": 30
    },

    // Authoritative DNS server (optional; off by default)
    "dns": {
        "enabled":       false,
        "bind_address":  "0.0.0.0",
        "port":          53,
        "rate_limit_per_second": 20
    },

    // Storage
    "storage": {
        "data_path": "data"                // git checkouts land here
    },
    "database": {
        "path": "hohenheim.db",            // SQLite file (used when url is blank)
        "url":  "",                        // JDBC URL to use another engine, e.g. jdbc:postgresql://host/hohenheim
        "engine":   "auto",                // auto | sqlite | duckdb | postgres | mysql | mariadb | firebird | cockroach
        "username": "",
        "password": ""
    },

    // Security / native IP banning
    "security": {
        "domain_miss_threshold":      5,
        "domain_miss_window_seconds": 300,
        "domain_miss_ban_threshold":  25,
        "domain_miss_decay_per_hit":  2,
        "bans_enabled":               true,
        "never_ban":                  [],  // one operator IP, CIDR or hostname per item
        "nftables_enabled":           false,
        "nftables_ports":             "80,443",
        "auto_ban_ttl_hours":         24,
        "auto_ban_budget_per_hour":   50,  // global cap on automatic bans per sliding hour
        "default_event_weight":       1,
        "reputation_ban_categories":  "spam,auth",
        "reputation_ban_threshold":   25,
        "reputation_positive_event_weight": 10, // same-category negatives offset per positive
        "reputation_ttl_seconds":     300
    },

    // Access & stats logging
    "logging": {
        "access_to_file":     true,
        "access_path":        "/var/log/hohenheim/access.log"
    }
}
```

`trusted_proxy_keys` used to be documented as a comma-delimited string. Existing
files using that representation are read for migration, but all new values are
stored and edited as DRY arrays.

### TLS passthrough and PROXY v2

The `hohenheim:tls_passthrough` upstream kind routes an encrypted TCP stream by the
SNI hostname in its TLS ClientHello. Hohenheim does not terminate, inspect, or
modify the backend TLS session. Its settings are `forward_host`, `forward_port`,
`connect_timeout`, and `proxy_protocol_v2`. The backend owns its
certificate and protocol stack, so passthrough domains cannot use Hohenheim
certificates, ACME issuance, paths, header rules, HSTS, access lists, or HTTP auth.

TCP 443 is handled by one Layer-4 listener. Exact SNI routes win over wildcard
routes, which win over regex routes. Conflicting declarations at the same
precedence reject the connection. Unknown or absent SNI is sent to Hohenheim's
internal TLS-termination listener when certificates are available; otherwise it
is rejected. Route reloads publish the HTTP and pre-TLS tables as one generation.

Inbound PROXY v2 is optional and accepted only when the socket peer matches
`proxy_protocol_trusted_sources`. Entries must be literal IPv4/IPv6 addresses or
CIDRs; hostnames are refused. A peer outside that list that sends a PROXY header is
disconnected. Direct clients remain valid without a header, so deployments that
require every connection to carry proxy identity must firewall the public listener
to the configured proxy peers.

It applies to BOTH public ports, not only TLS. Setting
`proxy_protocol_trusted_sources` to a non-empty list moves the plain HTTP
listener behind the same Layer-4 front: Undertow binds a loopback port and the
public port resolves connection identity first, so the recovered source drives
bans, access logs and `X-Real-IP`/`X-Forwarded-For`, and the recovered
destination drives per-domain `listen_on` matching. With an empty list (the
default) the HTTP port stays a direct Undertow listener with no extra hop.
Unix-socket mode is unaffected: the socket bridge already is that front.

When a passthrough site enables `proxy_protocol_v2`, Hohenheim prepends a PROXY v2
header containing the original source and destination before replaying the exact
ClientHello bytes. Enable this only for a backend listener explicitly configured
to accept PROXY v2; an ordinary TLS listener will reject the prefixed stream.
Connection, DNS, malformed-ClientHello, loop-prevention, and trust-boundary
failures are rate-limited in the Hohenheim log.

File format is Protoblast's **DRY** (extended JSON with comments). See
`HohenheimSettings.java` in the source for the authoritative list.

## Admin UI

Once running, the admin UI is at:

```
http://<host>:3000/
```

(or whatever you set `network.port` to). The nav has four groups: **Deploy**
(projects, sites, instances, stacks, databases, templates, git providers),
**Networking** (DNS zones and records, certificates, access rules, protected
paths, auth providers), **Security** (users, roles, IP bans, spamservice) and
**System**. The per-instance console and shell live on the instance record,
not on a site.

While no user exists the panel redirects to `/setup`, where the first
administrator is created.

## HTTPS & certificates

Two paths:

- **Let's Encrypt (automatic).** Enable with `ssl.letsencrypt_enabled = true`
  and set `ssl.letsencrypt_email`. Certificates are obtained via ACME (acme4j)
  and stored in the SQLite DB. Renewal happens automatically in a scheduled
  task. The request form accepts an optional per-certificate account email;
  each distinct email gets its own ACME account (and key pair), with the
  global email as the default.

- **Upload your own.** Use the Certificates page in the admin UI. Paste PEM
  fullchain + private key, then assign the certificate to a domain. No
  filesystem-path convention is required in the Java port — certificates live
  in the DB, not under `temp/letsencrypt/etc/acme/live/<domain>/`.

- **Wildcard certificates (DNS-01).** A `*.example.com` certificate cannot be
  issued over HTTP-01; it needs a DNS-01 challenge. The certificate-request
  page accepts wildcard hostnames and offers three ways to answer the
  challenge:
  - **Manual** — Hohenheim shows the `_acme-challenge` TXT records to paste at
    your DNS provider, then you click "Verify". One-shot: a manually issued
    wildcard cert does **not** auto-renew.
  - **Command hook** — set `ssl.dns_hook_command` to a script that publishes
    the TXT record (invoked as `command present|cleanup <name> <value>`); this
    auto-renews. `ssl.dns_propagation_seconds` (default 30) is how long to wait
    before asking the CA to validate.
  - **Hosted DNS** — if you run Hohenheim's own [DNS server](#dns-server) and
    it is authoritative for the zone, it publishes the TXT record into its own
    zone, serves it instantly, and auto-renews with no external credentials.

  A single order can carry the apex, `*.example.com`, and `*.dev.example.com`
  as SANs. Note a wildcard covers exactly one label: `*.example.com` matches
  `a.example.com` but not `example.com` itself or `a.b.example.com`.

Per-domain options:

- `force_ssl` — redirect that domain's HTTP to HTTPS.
- `hsts_enabled` — emit `Strict-Transport-Security` (only on HTTPS responses,
  per RFC 6797).
- `exclude_from_letsencrypt` — exclude from ACME enrollment (useful for `localhost`
  or internal hostnames).

## Upstream kinds

A site answers exactly one question: what its hostnames resolve to. The
built-in upstream kinds live in
`src/server/java/be/elevenways/hohenheim/server/upstream/kinds/` and are
discovered at compile time, never registered by hand:

| Kind               | Purpose                                                |
|--------------------|--------------------------------------------------------|
| `static`           | Serves static files from a directory.                  |
| `redirect`         | Sends an HTTP redirect to a target URL.                |
| `address`          | Forwards to an upstream HTTP/HTTPS server (TCP address or unix socket). |
| `instance`         | Serves the hostname from an instance this deployment manages; the site names it through `sites.instance_id` and the routing build resolves that instance's published loopback port. |
| `tls_passthrough`  | Passes the original TLS stream to a backend selected by SNI (see below). |
| `dev-namespace`    | A wildcard namespace (`*.dev.example.com`) that remote dev servers register into over the [dev tunnel](#dev-tunnel). |

See [`docs/architecture-upstream-kinds.md`](docs/architecture-upstream-kinds.md)
for the plugin contract.

The older `node`, `alchemy`, `java`, `command`, `docker` and `dead` site types
are gone: what a site RUNS is no longer a site question. The `proxy` type is now
`address`.

## Instance kinds

What runs is an **instance**. Instance kinds are discovered the same way
(`InstanceKinds` + `InstanceKindHandler`):

| Kind                 | Purpose                                                |
|----------------------|--------------------------------------------------------|
| `application`        | The authored half of a deployed app: source, build, runtime image, variables, volumes, declared port, retention. Each deploy generates a `release` instance. |
| `release`            | One immutable per-deploy container. Health-gated swap, one retired release kept for rollback. |
| `docker_container`   | A Docker container on an inventoried host.             |
| `system_container`   | A system container on an inventoried Incus host.       |
| `vm`                 | A KVM virtual machine on an Incus host, provisioned through cloud-init user-data. |
| `workspace`          | A persistent development box: one container per workspace, on Docker or Incus. |
| `database_container` | The engine container a managed database lowers onto. Written only by the record that owns it: a `dedicated` database, or the shared **database engine** serving many logical databases. |
| `stack_service`      | One service of a compose-style stack.                  |

## Git-backed provisioning

A git source is a property of the **application** or **workspace** instance, not
of the site. An application builds its source into an image in a sandbox on the
control plane (nixpacks or a Dockerfile) and deploys the result as a release; a
workspace checks the source out and builds it inside its own container, as its
own uid.

Webhooks: `POST /api/webhooks/git/<segment>` (`GitWebhookHandler.PREFIX`)
triggers a deploy. The handler is intercepted on the proxy port before
hostname routing, so the forge signature IS the authentication; every refusal
short of a verified signature is the same 404. A push relayed by a forge carries
the `webhook` deploy trigger, which is the one trigger that may not start a
workload an operator stopped.

## Managed databases

Hohenheim can create and manage databases (PostgreSQL, MySQL, Redis, MongoDB) on an
inventoried **server**, attach them to an **instance** (their credentials are
resolved at deploy time and injected into that workload's environment, never
baked into stored settings), back them up on a schedule
(`database.backup_path`, `database.backup_retention`), and restore from an
uploaded dump. A record describes a provisioned container, so name, engine, db
name/user/password, image, ephemeral flag and server are frozen once it exists;
only the resource ceilings can be corrected afterwards. Anything else means
destroy and recreate.

Each record has a **placement**: `dedicated` gives it an engine container of its
own, while `shared` (the default where the engine supports logical databases)
makes it a logical database with its own user on one shared engine process per
host, so ten small databases cost one engine footprint instead of ten. See
[`docs/shared-database-engines.md`](docs/shared-database-engines.md).

## DNS server

Hohenheim can optionally act as an **authoritative** DNS server for zones you
host — never a recursive resolver — so you can stop relying on a hosted DNS
control panel. It is **off by default**. Full design notes:
[`docs/authoritative-dns.md`](docs/authoritative-dns.md) and
[`docs/dns-federation.md`](docs/dns-federation.md).

Enable it in `settings/hohenheim.dry` (the `dns.*` group is Hohenheim's own; a
`dns` block in `local.dry` is ignored):

```
"dns": {
    "enabled":       true,
    "bind_address":  "0.0.0.0",   // interface to bind (UDP + TCP 53)
    "port":          53,          // change if you front it with a redirect
    "rate_limit_per_second": 20   // per client-network response-rate-limit; 0 disables
}
```

Binding port 53 needs the same `CAP_NET_BIND_SERVICE`
[described above](#privileged-ports). Changing `dns.*` requires a restart; zone
and record edits do not.

In the admin UI (Networking -> DNS Zones) you create a zone (origin, SOA
contact, TTLs), then manage A/AAAA/CNAME/NS/MX/TXT/CAA/SRV records on its
**Records** tab. A **Zone file** tab imports and exports standard master-file
text, so you can migrate an existing zone from another provider by pasting its
export.

**What you still need from outside Hohenheim:**

- Delegate the domain at your **registrar** to Hohenheim's nameservers (with a
  **glue record** giving the nameserver's IP, since it lives inside the zone).
- Open **UDP and TCP 53** from the internet to the host.
- A stable public IP and no carrier-grade NAT.
- At least **two** authoritative nameservers for production (see federation).

### DNSSEC

Flip `dnssec` on a zone and Hohenheim signs it online (ECDSA P-256), publishes
the apex DNSKEY, builds an NSEC chain, and serves RRSIG/NSEC only to
DNSSEC-aware resolvers. The **DS record** to lodge with your registrar is shown
on the zone's Zone-file tab; a daily task re-signs before signatures expire.
Enable DNSSEC and verify externally (`dig +dnssec`, an unbound instance) for a
few days **before** lodging the DS — a bad DS takes the domain down for
validating resolvers.

### Federation (hidden primary + secondaries)

For redundancy and to keep your home/office box's port 53 closed to the world,
run a **hidden primary** that owns the zones and a **public secondary** (a VPS
running Hohenheim, or an off-the-shelf NSD/Knot) that answers the internet.
Replication is standard **AXFR/TSIG/NOTIFY**: configure a DNS peer with a TSIG
key and attach it to a zone as a secondary. The primary NOTIFYs on every
change; the secondary pulls. A secondary Hohenheim instance can also act as a
central editing surface, forwarding record edits to the owning primary over an
authenticated HTTPS peer API. See
[`docs/dns-federation.md`](docs/dns-federation.md).

### Dynamic DNS

An A or AAAA record can be marked **dynamic** to get a per-record update token.
Point any dyndns2 client (router firmware, `ddclient`, ex-FreeDNS setups) at:

```
GET /nic/update?hostname=<fqdn>&myip=<ip>
```

with the token as the HTTP Basic password (`myip` is optional — the caller's
public IP is used when omitted). Replies are the standard
`good`/`nochg`/`badauth`/`nohost` lines. The token is stored so it stays
visible on the record's form and can be re-copied any time. The endpoint is
public and rate-limited per IP.

## Dev tunnel

Run a dev server anywhere — a laptop behind NAT, an LXC container, another
machine — and have it appear on a public `https://<name>.dev.example.com`
subdomain with a valid (wildcard) certificate, ngrok-style. Create one
**dev-namespace** site carrying the wildcard domain; it mints a `zdev_`
registration token. Any Zenit app then registers itself over an outbound
WebSocket using that token (via `dev_tunnel.*` settings, or `zenit-dev`'s
machine config) and is instantly reachable. Because Hohenheim remains a true
reverse proxy (TLS terminates at Hohenheim; requests arrive with
`X-Forwarded-*`), the dev app needs no code changes to be HTTPS-aware. Design
notes: [`docs/dev-tunnel.md`](docs/dev-tunnel.md).

## Notifications

Hohenheim can send notifications (certificate expiry, deploy results, site-down
alerts, attached-database problems) to configured channels. Manage them under
the Notifications page; each channel subscribes to a closed set of event types.

## Native IP banning

Hohenheim scores hostile behaviour per source IP and bans natively -- no
fail2ban needed (the old fail2ban domain-miss log was removed).

**Architecture.** A deployed [spamservice](../../javaweb/spamservice) is the
single canonical IP-reputation and security-event authority; Hohenheim keeps
only the BAN side: the ban decision, ban rows, nftables enforcement, the ban
admin UI, and a local threat scorer for the signals only the proxy sees
(domain misses). Managed sites and Hohenheim itself push their security
events to spamservice; Hohenheim additionally PULLS reputation from it to
ban IPs that abuse hosted apps (see below). Hohenheim's own ingest endpoint,
reporter tokens, event store and Security Events admin list were deleted --
spamservice replaced them.

**Local scoring.** Unmatched-domain scans (bots hunting `admin.`,
`wp-login.`, ...) plus this instance's own security events (failed logins,
lockouts, rate-limit and CSRF violations) are weighted and counted per IP in
a sliding window; every real route hit forgives `domain_miss_decay_per_hit`
of the oldest points. Crossing `domain_miss_ban_threshold` inside
`domain_miss_window_seconds` creates an automatic ban (TTL
`auto_ban_ttl_hours`). Manual bans (with a duration or permanent) are created
from the admin UI under Security -> IP Bans, which is also where bans are
lifted; the dashboard charts bans created over the last 30 days.

**Ban granularity.** IPv4 actors are banned by exact address. IPv6 actors are
banned by their **/64 network**: a single v6 actor typically controls the
whole /64, so per-address bans would be useless against address rotation.
Every entry point (threat scorer, reputation policy, manual admin bans)
normalizes a v6 target to its `<network>/64` key -- that CIDR string is what
the ban row stores, what the proxy/TLS checks match (O(1), the incoming v6
address is normalized to the same key), and what the kernel gets as a prefix
element (`banned_v6` carries `flags interval`). Spamservice mirrors this by
rolling reputation up per /64 server-side.

**Reputation-informed banning.** When the local managed Spamservice installation
is enabled and ready, every not-banned client IP seen by the proxy is (asynchronously,
throttled per IP, cached `reputation_ttl_seconds`) looked up against
spamservice. For each category in `reputation_ban_categories` (event-type
category = the prefix before the first dot: `spam`, `auth`, `http`, `ws`,
`proxy`), the contribution is `max(0, negative count - positive count *
reputation_positive_event_weight)`. The category contributions are summed,
and a net score at or above `reputation_ban_threshold` creates a regular
auto-ban row (the /64 for v6). Positive events only offset negatives in the
SAME category, so an auth success cannot forgive spam; the default weight is
10 and 0 disables positive credit. Event recency and dataset risk flags
(vpn/hosting/tor/proxy) are NEVER ban signals -- only behavior reported by
trusted clients counts. The lookup fails open: a slow or down spamservice
never blocks a request and never bans anyone.

**Safety rails.** Loopback, private/link-local ranges, and the server's own
addresses can never be banned. On top of that, `never_ban` is an operator
allowlist of IPs, CIDR ranges and hostnames (e.g. `["203.0.113.7",
"198.51.100.0/24", "home.example.net"]`) that refuses both automatic and manual
bans -- **add your own home/office IPs (or your dynamic-DNS hostname) there
before enabling enforcement in production**. Hostname entries are for
dynamic-IP operators: they are resolved (A + AAAA) ONLY in the background
(immediately after a settings edit, plus the SecuritySweep task at boot and
hourly -- never on a request or ban path); a failed resolution keeps the last
successfully resolved addresses protected, and a resolved IPv6 address protects
its whole /64. Because v6
bans cover a /64, a protected address ANYWHERE inside a /64 vetoes banning
that entire range. Finally, ALL automatic
bans share a global budget of `auto_ban_budget_per_hour` (default 50) per
sliding hour, enforced at the ban-service funnel so every trigger -- the
threat scorer, the spamservice reputation policy, and anything added later --
is covered: a compromised trusted reporter or a spamservice bug cannot
convert into a mass ban of every visitor. Exhausting the budget suppresses
further automatic bans until the window resets and is logged loudly (slog
`security.auto_ban_budget_exceeded`, once per window, plus a tally of the
suppressed attempts) -- and, once per window, notified to the configured
notification channels (`auto_ban_budget_exhausted`). Manual bans from the
admin are never budget-limited.

**Degradation alerts.** Hohenheim fails open everywhere, so silent
degradation is notified instead: sustained spamservice failure (reputation
lookups or per-site report provisioning failing >= 5 consecutive times over
>= 5 minutes) sends ONE `spamservice_outage` notification and ONE
`spamservice_recovered` when calls succeed again -- never a stream.

**Enforcement.** Banned IPs are refused at the proxy (HTTP 403; HTTPS is
refused at the TLS handshake, before a certificate is served). Every
enforced IP has a ban ROW: the threat score only triggers ban creation, so
anything refused is visible and liftable under Security -> IP Bans. ACME
HTTP-01 challenge paths (`/.well-known/acme-challenge/*`) are served even to
banned IPs, so certificate renewal survives a mistaken ban. With
`nftables_enabled` the ban is ALSO installed in the kernel: Hohenheim owns
the nftables table `inet hohenheim` with a chain `banned` (input hook,
priority -10) and two timeout sets, `banned_v4` (exact addresses) and
`banned_v6` (`flags interval`, holding /64 prefix elements). The drop
rule is scoped to the TCP ports in `nftables_ports` (default `80,443`) so
SSH (22) and DNS (53) can never become collateral. Timed bans use per-element
timeouts, so the kernel expires them on its own; the bans table stays the
source of truth and is resynced into the kernel at boot.

**Sudoers requirement.** nftables enforcement shells `sudo -n -- nft ...` as
root, so the Hohenheim user needs passwordless sudo for `nft`
(`/etc/sudoers.d/hohenheim-nft`, see [Root grants](#root-grants)). All
nft failures are logged, never fatal: dev machines without sudo/nft run fine
with `nftables_enabled` off (the default).

**Operator setup.** Configure the Spamservice installation singleton in the
Hohenheim admin: enable it, select the dedicated non-root `spamservice` system
user, choose an unprivileged loopback port, and set a bounded JVM heap. Runtime
data lives under `<storage.data_path>/managed-services/spamservice/instance`;
the root-owned executable lives separately under `runtime` and is never writable
by the child. Hohenheim extracts the embedded distribution by content hash, runs
its migrations, and supervises that one local runtime. Its controller key is
generated automatically and delivered once over stdin, never through argv or the
environment. No external URL, manually-created client, or framework report token
is required. Hohenheim's own events use an installation reporter provisioned by
that manager, which installs zenit's remote sink directly. Those events also
feed the local threat scorer for immediate banning regardless of remote
reporting.

Per-workload reporting (`ZENIT_SECURITY_REPORT_URL` /
`ZENIT_SECURITY_REPORT_TOKEN` injected into a managed zenit app, provisioned
into spamservice under the external id `hohenheim:site:<id>`) is built in
`server/security/SecurityReportEnv` but is **not wired to a spawner today**:
`SecurityReportEnv.forSite` has no caller outside its own test since the
per-site process lane was deleted. Only `reconcilePersistedReporters` runs.

**Migrating from fail2ban.** Remove the old jail so stale bans do not linger:
delete `/etc/fail2ban/jail.d/hohenheim.conf` and
`/etc/fail2ban/filter.d/hohenheim.conf`, run
`fail2ban-client reload` (or stop fail2ban entirely if Hohenheim was its only
jail), and drop the `/var/log/hohenheim/domain-misses.log` entry from
logrotate. Then add your operator IPs as separate `security.never_ban` items and set
`nftables_enabled: true` in production.

**/etc/logrotate.d/hohenheim**

```
/var/log/hohenheim/access.log {
    daily
    rotate 7
    compress
    missingok
    notifempty
    copytruncate
}
```

## Systemd

`tools/install-host.sh` writes this unit for you and sizes the heap from
`MemTotal` (40%, rounded to a 64 MB step, clamped to 512-2048 MB). The example
below is for a host the installer does not cover.

`/etc/systemd/system/hohenheim.service`:

```ini
[Unit]
Description=Hohenheim reverse proxy
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=hohenheim
Group=hohenheim
WorkingDirectory=/opt/hohenheim
ExecStart=/usr/bin/java -jar /opt/hohenheim/hohenheim-server.jar
Restart=always
RestartSec=5
StandardOutput=journal
StandardError=journal
SyslogIdentifier=hohenheim
Environment=JAVA_TOOL_OPTIONS=-Xmx1g

# Privileged ports — option 1 (recommended)
AmbientCapabilities=CAP_NET_BIND_SERVICE

# File descriptors
LimitNOFILE=60000

# Reap the daemon and its remaining descendants when the service stops
KillMode=control-group

# Hardening
NoNewPrivileges=false           # must be false if sudo-based uid switching is used
ProtectSystem=full
ProtectHome=read-only
PrivateTmp=true

[Install]
WantedBy=multi-user.target
```

Enable and start:

```bash
sudo systemctl enable --now hohenheim.service
sudo journalctl -u hohenheim -f
```

Tune:

- `User=` / `Group=`: the unix user Hohenheim runs as (it is the subject of the
  sudoers files under [Root grants](#root-grants), and needs the `docker` group
  when Docker workloads run here).
- `WorkingDirectory=`: where `hohenheim.db`, `settings/`, `data/` live.
- `ExecStart=`: swap in `/opt/hohenheim-jdk/bin/java` if you picked option 2
  for privileged ports.
- `Environment=`: heap size, timezone (`TZ=Europe/Brussels`), proxy vars, etc.

### Keep `NoNewPrivileges` in mind

If you enable `NoNewPrivileges=true` systemd will block `sudo` from raising
privileges. That breaks the `nft` and btrfs grants and the managed spamservice
child's uid drop. Leave it `false`.

## Troubleshooting

- **"Permission denied" binding 80/443.** You didn't pick a privileged-port
  strategy. Re-read that section.
- **`libjli.so: cannot open shared object file`.** You copied only the `java`
  binary. Copy the whole JDK directory (option 2).
- **`sudo: a password is required` in the log.** A sudoers file under
  `/etc/sudoers.d/` is missing or does not cover the binary being run, or
  `NoNewPrivileges=true` is set in the unit. Re-run `tools/install-host.sh`,
  which writes and validates them.
- **`EMFILE: too many open files`.** Raise `LimitNOFILE`.
- **Let's Encrypt fails with rate-limit errors in testing.** Enable
  `ssl.letsencrypt_staging = true`.
- **Site returns 503 for several minutes after a deploy.** The application's
  first build is running in the sandbox; the site starts answering once the
  release container passes its health gate. Watch the build operation on the
  instance record.
- **Admin UI works but proxy doesn't.** Check `proxy.http_port` in
  `settings/hohenheim.dry` — if it's `80` and you didn't grant
  `CAP_NET_BIND_SERVICE`, the listener never came up. `journalctl -u hohenheim`
  will show the `bind` error.

## Development

```bash
zenit-dev build          # compile everything
zenit-dev test           # unit tests
zenit-dev test --browser # Playwright end-to-end tests
zenit-dev start          # run locally
```

Never invoke `./gradlew` directly; `zenit-dev` wires Zenit, Hawkeye, Plumage
and Protoblast together with the right classpath and live-reload hooks.

See [`CLAUDE.md`](CLAUDE.md) for internal architecture notes.

## License

Same as the Node.js original: GPL v3.

## Credits

Originally conceived by [Eleven Ways](https://elevenways.be). Thanks to
[Félix "passcod" Saparelli](https://github.com/passcod) for releasing the
`hohenheim` name on npm.
