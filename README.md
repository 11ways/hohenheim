# Hohenheim

Hohenheim is a reverse proxy and site dispatcher. It routes incoming HTTP/HTTPS
requests to backends based on hostname, and manages the lifecycle of those
backends — node.js child processes, static files, arbitrary proxied upstreams,
or git-provisioned apps.

This is the **Java/Zenit rewrite** of the original Node.js/AlchemyMVC Hohenheim.
It has feature parity with the Node version and goes well beyond it:

- **Reverse proxy + site dispatcher** — node/alchemy/command/proxy/static/redirect
  site types, per-site process management, git-backed provisioning.
- **Docker** site type and **managed databases** (create, back up, attach to sites).
- **Automatic HTTPS** via Let's Encrypt, including **wildcard certificates**
  through DNS-01.
- **Authoritative DNS server** (optional) — host your own zones, with **DNSSEC**,
  **hidden-primary/secondary federation**, and **dynamic DNS** (dyndns2), so you
  can drop a hosted DNS control panel entirely.
- **Dev tunnel** — expose a dev server running anywhere on a public
  `*.dev.example.com` subdomain with a valid certificate, ngrok-style.
- **Notifications**, an **automation API** (hashed `znit_` keys), IP-reputation
  fail2ban logging, and a full admin UI.

Each subsystem has a deeper design doc under [`docs/`](docs/).

## Requirements

### Java

- **JDK 22 or newer.** The server is compiled against Java 22.

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

- `git` on `PATH` if you plan to use the git-backed site types.

### Node.js (only for Node/Alchemy site types)

- Any node version reachable on `PATH`, or per-site versions managed via
  [`n`](https://github.com/tj/n) — all globally-installed `n` versions are
  auto-discovered by the `UpdateNodeVersions` scheduled task.

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
CapabilityBoundingSet=CAP_NET_BIND_SERVICE
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
sudo cp -a /usr/lib/jvm/java-22-openjdk /opt/hohenheim-jdk
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
`settings/local.dry`. Clients see port 80/443 unchanged.

### Option 4: `authbind`

Legacy fallback: `authbind --deep java -jar hohenheim-server.jar`. Only helps
with `bind()`, nothing else. Option 1 supersedes it.

## uid/gid switching for spawned processes

Hohenheim can run site processes as a different unix user (per-site
`system_user_id`). This uses `sudo -u #<uid>`, so:

- The JVM itself does **not** need `cap_setuid`/`cap_setgid`/`cap_kill`.
- `sudo` is setuid-root on every distro and handles the switch.
- You need a NOPASSWD rule for the hohenheim user:

```
# /etc/sudoers.d/hohenheim
hohenheim ALL=(ALL) NOPASSWD: ALL
```

Tighten to a whitelist if you have a closed set of target users.

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

```bash
git clone https://github.com/11ways/hohenheim.git
cd hohenheim

# Build (uses zenit-dev; do not invoke ./gradlew directly)
zenit-dev build
```

The build produces `build/libs/hohenheim-<version>-server.jar`.

### Directory layout for a production install

```
/opt/hohenheim/
├── hohenheim-server.jar          # fat jar (rename the built artifact)
├── public/                       # static assets (shipped with the repo)
├── settings/
│   ├── local.dry                 # your overrides (not tracked)
│   └── local.dry.example         # reference
├── data/                         # git-provisioned site checkouts
├── hohenheim.db                  # SQLite database (auto-created)
└── logs/                         # access + domain-miss logs
```

## Configuration

All runtime configuration lives in `settings/local.dry`. Copy the
[`local.dry.example`](settings/local.dry.example) file and uncomment what you
need. Values here override Zenit's and Hohenheim's defaults.

Most-useful keys:

```
{
    // Zenit admin UI
    "network": {
        "port": 3000                       // admin UI port (default 3000)
    },

    // Reverse proxy
    "proxy": {
        "http_port":  80,                  // public HTTP
        "https_port": 443,                 // public HTTPS
        "force_https": true,               // redirect HTTP to HTTPS globally
        "fallback_address": "http://localhost:8081",  // tried when no site matches
        "ipv6_address": "",                // optional extra listener
        "first_port": 4748,                // first port handed to managed child processes
        "trusted_proxy_keys": ""           // comma-delimited X-Hohenheim-Key values; a request
                                           // carrying one may pass the real client IP in X-Real-IP
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

    // Security / fail2ban
    "security": {
        "log_domain_misses":          true,
        "domain_miss_threshold":      5,
        "domain_misses_log_path":     "/var/log/hohenheim/domain-misses.log",
        "domain_miss_window_seconds": 300,
        "domain_miss_ban_threshold":  25,
        "domain_miss_decay_per_hit":  2,
        "log_path_and_ua":            true
    },

    // Access & stats logging
    "logging": {
        "access_to_file":     true,
        "access_path":        "/var/log/hohenheim/access.log",
        "access_to_database": false,
        "collect_stats":      true
    }
}
```

File format is Protoblast's **DRY** (extended JSON with comments). See
`HohenheimSettings.java` in the source for the authoritative list.

## Admin UI

Once running, the admin UI is at:

```
http://<host>:3000/
```

(or whatever you set `network.port` to). Site creation, domain mapping,
certificate management, process table, per-site terminal viewer, audit log and
access-list editor all live here.

The first time Hohenheim starts against an empty database it runs a setup
wizard to create the initial admin user.

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
- `ignore_certificates` — exclude from ACME enrollment (useful for `localhost`
  or internal hostnames).

## Site types

Built-in types, registered via `SiteTypeRegistry`:

| Type       | Purpose                                                    |
|------------|------------------------------------------------------------|
| `node`     | Managed Node.js child process. Hohenheim handles port/socket allocation, restart, scaling, logs. |
| `alchemy`  | Extends `node` with `--stream-janeway` and a fork-wrapper that gives older alchemy installs a native `process.on('message')` IPC channel. |
| `command`  | Managed arbitrary process (shell command).                 |
| `docker`   | Managed Docker container on a local or remote engine; Hohenheim maps a container port and proxies to it. |
| `dev-namespace` | A wildcard namespace (`*.dev.example.com`) that remote dev servers register into over the [dev tunnel](#dev-tunnel). |
| `proxy`    | Transparent reverse proxy to a TCP address or unix socket. Regex host captures can be substituted into the socket path, e.g. `/run/{project}.sock`. |
| `static`   | Static-file server, optionally git-provisioned. Directory listings (autoindex) are ON by default since 0.1.0, matching the Node original; untick "Show directory listing" to disable. |
| `redirect` | 30x redirect, with an optional per-request delay.          |
| `dead`     | Returns an error page; for sites temporarily disabled.     |

See `docs/architecture-site-types.md` for the plugin contract.

## Git-backed provisioning

Any site type can source its working tree from git. Configure under the
**Source** tab of the site editor:

- repo URL (HTTPS or SSH)
- branch
- build command (run after each pull; e.g. `bash generate-site.sh`)
- build directory / root path (served from there)

Dual-slot deployment: Hohenheim clones into `<data_path>/git-repos/<id>/a` or
`/b`, runs the build, then atomically flips an `active` symlink. Failed
builds don't take the site down.

Webhooks: `POST /webhook/git/<site-id>` triggers a pull-and-rebuild.

## Managed databases

Hohenheim can create and manage databases (PostgreSQL, MySQL/MariaDB) on a
local or remote **server**, attach them to sites (their credentials are
injected into the site's environment), back them up on a schedule
(`database.backup_path`, `database.backup_retention`), and restore from an
uploaded dump. Managed database records are immutable after creation — destroy
and recreate rather than editing in place.

## DNS server

Hohenheim can optionally act as an **authoritative** DNS server for zones you
host — never a recursive resolver — so you can stop relying on a hosted DNS
control panel. It is **off by default**. Full design notes:
[`docs/authoritative-dns.md`](docs/authoritative-dns.md) and
[`docs/dns-federation.md`](docs/dns-federation.md).

Enable it in `settings/local.dry`:

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

In the admin UI (Infrastructure → DNS Zones) you create a zone (origin, SOA
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

## Fail2ban

Hohenheim logs unmatched-domain scans (bot hunting for `admin.`, `wp-login.`,
etc.) in a format fail2ban can digest.

Log line (the `path=`/`ua=` detail follows `misses=`, so the filter regex below
keeps matching; disable it with `log_path_and_ua`):

```
2026-04-12T14:30:45.123Z DOMAIN_MISS ip=192.168.1.100 domain=unknown.example.com misses=7 path=/wp-login.php ua="Mozilla/5.0 zgrab/0.x"
```

Misses are counted in a sliding window and decayed by successful requests:
an IP is banned at the proxy (HTTP 403; HTTPS is refused at the TLS
handshake) once it exceeds `domain_miss_ban_threshold` misses inside
`domain_miss_window_seconds`, and every real route hit forgives
`domain_miss_decay_per_hit` of its oldest misses.

Settings:

```
"security": {
    "log_domain_misses": true,
    "domain_miss_threshold": 5,
    "domain_misses_log_path": "/var/log/hohenheim/domain-misses.log",
    "domain_miss_window_seconds": 300,
    "domain_miss_ban_threshold": 25,
    "domain_miss_decay_per_hit": 2,
    "log_path_and_ua": true
}
```

**/etc/fail2ban/filter.d/hohenheim.conf**

```ini
[Definition]
failregex = ^.*DOMAIN_MISS ip=<HOST> domain=.* misses=.*$
ignoreregex =
```

**/etc/fail2ban/jail.d/hohenheim.conf**

```ini
[hohenheim]
enabled  = true
filter   = hohenheim
logpath  = /var/log/hohenheim/domain-misses.log
maxretry = 10
findtime = 600
bantime  = 3600
```

**/etc/logrotate.d/hohenheim**

```
/var/log/hohenheim/domain-misses.log
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
CapabilityBoundingSet=CAP_NET_BIND_SERVICE

# File descriptors
LimitNOFILE=60000

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

- `User=` / `Group=`: the unix user Hohenheim runs as (make sure it has
  `sudo` permission if you use per-site uid switching).
- `WorkingDirectory=`: where `hohenheim.db`, `settings/`, `data/` live.
- `ExecStart=`: swap in `/opt/hohenheim-jdk/bin/java` if you picked option 2
  for privileged ports.
- `Environment=`: heap size, timezone (`TZ=Europe/Brussels`), proxy vars, etc.

### Keep `NoNewPrivileges` in mind

If you enable `NoNewPrivileges=true` systemd will block `sudo` from raising
privileges, breaking per-site uid switching. Either leave it `false`, or
don't use the `system_user_id` feature.

## Node versions

Per-site Node version selection:

- System `node` on `PATH` (always available).
- `/usr/bin/node`, `/usr/local/bin/node` if present.
- Any version installed via [`n`](https://github.com/tj/n).

Discovery is handled by the `UpdateNodeVersions` scheduled task; re-run it from
the admin UI after installing a new version.

If a referenced version disappears (uninstalled after a site was configured),
Hohenheim falls back to `node` on `PATH` rather than crashing the spawn.

## Troubleshooting

- **"Permission denied" binding 80/443.** You didn't pick a privileged-port
  strategy. Re-read that section.
- **`libjli.so: cannot open shared object file`.** You copied only the `java`
  binary. Copy the whole JDK directory (option 2).
- **Spawns run as the wrong user.** Either `sudo` NOPASSWD rule missing, or
  `NoNewPrivileges=true` is set in the unit.
- **`EMFILE: too many open files`.** Raise `LimitNOFILE`.
- **Let's Encrypt fails with rate-limit errors in testing.** Enable
  `ssl.letsencrypt_staging = true`.
- **Site returns "Deployment in progress" for several minutes.** First git
  clone + build is running in the background; wait for the `active` symlink
  to appear under `<data_path>/git-repos/<id>/`.
- **Admin UI works but proxy doesn't.** Check `proxy.http_port` in
  `settings/local.dry` — if it's `80` and you didn't grant
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
