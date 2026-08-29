# robbedoes: the sites host, the Phoenix successor

The third public Hohenheim install and the first COMPUTE node: it carries the
proxy, dns, firewall, instances and databases roles, so it can host sites,
workspaces, applications and managed databases. It is also the intended DNS
SECONDARY beside kuifje, the DNS primary (`deploy-kuifje.md`), but no peering was done
here -- a separate lane owns that.

Installed 2026-08-30 by `tools/install-host.sh`, which IS the procedure
(`docs/deploy-native.md`). Nothing on this host was configured by hand except
the first administrator (which the product itself creates), the first
administrator's host admission through the panel, and one settings line
(`network.bind_address`, see "Panel exposure" below).

## Host facts

    ssh              debian@51.255.43.81   (key auth, passwordless sudo -n)
    IPv6             2001:41d0:305:2100::1:4b26
    hostname         robbedoes   (was vps-801b1e2a; renamed 2026-08-30)
    os               Debian GNU/Linux 13 (trixie)
    cpu / ram / disk 6 vCPU / 11683 MB / 99 GB NVMe (5.0 GB used after the install)
    java             Temurin 25.0.4.1 JRE from Adoptium (/usr/lib/jvm/temurin-25-jre-amd64)
    swap             2 GB swapfile at /swapfile, vm.swappiness=10 (created by the installer)

The box shipped with NO java at all, so the installer's Adoptium lane ran here
for the first time on a real host (kuifje already had Debian's own
openjdk 25 and skipped it). No second JDK is installed.

## What is installed

    /opt/hohenheim/hohenheim-server.jar     hohenheim b486427f (starfleet's live build)
    /opt/hohenheim/settings/hohenheim.dry   roles proxy, dns, firewall, instances, databases (0640)
    /opt/hohenheim/settings/local.dry       0600, main_url placeholder, bind_address 127.0.0.1
    /opt/hohenheim/settings/field-encryption.keys   0600, generated at first boot
    /opt/hohenheim/hohenheim.db             sqlite control plane, 46 migrations
    /opt/hohenheim/volumes.btrfs            40 GB loop file
    /opt/hohenheim/data/volumes             the btrfs volume root (fstab, loop,defaults,nofail)
    /etc/systemd/system/hohenheim.service   -Xmx2048m (40% of MemTotal clamped, the installer's rule)
    /etc/sudoers.d/hohenheim-nft            the single nft grant
    /etc/sudoers.d/hohenheim-volumes        btrfs, chown, chmod, mkdir, rm -- written by the installer
    /etc/sysctl.d/99-hohenheim.conf         fs.file-max=200000, vm.swappiness=10
    /etc/systemd/resolved.conf.d/hohenheim.conf   DNSStubListener=no
    /root/hohenheim-admin.txt               0600, the generated admin password

Docker CE 29.7.2 from Docker's own apt repo, cgroup v2, systemd driver.

`network.main_url` is EMPTY and `auth.dry` was never seeded (the installer skips
it without `--main-url`). The box is NAMED `robbedoes` since 2026-08-30, but
`network.main_url` and `auth.external_base_url` still need its PUBLIC name.

`network.trusted_proxies` was left at the installer's `loopback`, untouched.

NOT created, deliberately: no site, no domain, no DNS zone, no certificate, no
DNS peer, no instance, no database, no project.

## Install transcript (2026-08-30)

    tools/install-host.sh --jar /home/debian/hohenheim-server.jar \
        --roles proxy,dns,firewall,instances,databases \
        --with-docker --volume-root-size 40 --swap 2G \
        --admin-email jelle@elevenways.be

First run, all 28 steps executed: base packages
(`gnupg sqlite3 unzip nftables dnsutils`), `installing temurin-25-jre from
Adoptium (trixie)`, `installing docker-ce from Docker's repo (trixie)`, service
user + layout, both sudoers files `parsed OK`, btrfs-progs + the 40 GB loop
file, settings seeded (`skip: auth.dry needs --main-url`),
`switching off systemd-resolved's stub listener (it owns 127.0.0.53:53)` then
`udp/53 is free`, `creating a 2G swapfile at /swapfile`,
`MemTotal 11683MB -> -Xmx2048m`, `Migrations complete 46 applied`, `health: OK`.

Second run, immediately after: 30 `skip:` lines and no restart --
`ActiveEnterTimestamp` stayed at the first run's 22:07:06 UTC while the second
run executed at 22:08. Every step reported its own precondition already
satisfied, including `skip: /opt/hohenheim/data/volumes already mounted` and
`skip: /opt/hohenheim/hohenheim-server.jar is already this build`.

The installer wrote `/etc/sudoers.d/hohenheim-volumes` itself; nothing had to be
added by hand.

## Jar provenance

The jar was COPIED from starfleet's live deployment, never rebuilt:
`zenit-dev deployed starfleet` reported `current` with the service active and no
restart pending, then `scp root@starfleet.life:/opt/hohenheim/hohenheim-server.jar`.

    sha256   f9d9b2d0412438f0537494b14029be6f5d79baee119dd1b019cf8f113c188c07
    bytes    267,596,947
    stamp    hohenheim b486427f, 13/13 modules, all clean

The remote sha was re-read after the copy and matches, so no deploy wave swapped
the file mid-transfer. `--build-info` on this host prints the same 13 clean rows.

## First administrator

Created through the product's own `/setup` page over loopback (curl with the
page's `csrf_token`), never by writing the database. The password was generated
on the host and lives ONLY there:

    /root/hohenheim-admin.txt      (0600, root)

It holds the email (`admin@panel.invalid`), the tunnel URL and the password.
Rotate it once the box has a real name; `--set-password --email <address>` is the
offline recovery lane if it is ever lost.

Reaching the panel until this box has a hostname:

    ssh -L 3000:127.0.0.1:3000 debian@51.255.43.81
    # then http://127.0.0.1:3000/ in the browser

## Panel exposure: `network.bind_address` = 127.0.0.1

The ONE setting changed by hand. After the install, `http://51.255.43.81:3000/`
answered a 302 to the login page FROM THE INTERNET: this provider's firewall
passes every port by default, and the installer binds zenit's HTTP listener to
`0.0.0.0`. An admin login page on a public port is not an acceptable resting
state, and the provider panel is not reachable from here, so the exposure was
closed in the product instead: `"bind_address": "127.0.0.1"` in
`settings/local.dry`, then a restart.

This is safe for every role on this box. The proxy is a SEPARATE Undertow
listener that binds `0.0.0.0:80`/`:443` itself (`ProxyServer`), and the panel is
published later the same way starfleet publishes it -- a site whose upstream is
`127.0.0.1:3000`, which loopback binding does not affect. Undo by removing the
line and restarting.

Note for whoever owns the provider firewalls: kuifje, the DNS primary
(`137.74.171.228:3000`), had the same exposure and was closed the same way on
2026-08-30 (`deploy-kuifje.md`). starfleet's was never exposed. Since 2026-08-30
the installer seeds `network.bind_address` = `127.0.0.1`, so a NEW host arrives
closed; both of these boxes predate that and needed the hand edit.

## Verified after the install

- `systemctl is-active/is-enabled hohenheim` -> active / enabled.
- Listeners: `*:53` udp AND tcp, `*:80`, and `127.0.0.1:3000` ONLY. 443 does not
  listen yet and should not: there is no certificate on a box with no sites.
- From the workstation: `http://51.255.43.81/` answers 404 with Hohenheim's own
  "No site configured" page; `:3000` is `Connection refused`.
- From the workstation, raw DNS queries (python3, no dig needed): an out-of-zone
  `example.com SOA` is `REFUSED` over UDP AND over TCP, aa=0. The authoritative
  server answers and refuses what it does not serve. It serves no zone yet.
- `/` over the ssh forward: 302 to `/login`; a login with the generated password
  is 302 and `/admin/dashboard` renders 200.
- `--build-info` as the service user: `hohenheim b486427f clean` plus the 12
  other module stamps, all clean.
- `roles_captured enabled=[databases, dns, firewall, instances, proxy]` in the
  journal; zero exceptions or errors since the restart.
- `free -m`: 2047 MB swap; `sysctl` reports `fs.file-max = 200000` and
  `vm.swappiness = 10`.
- `/etc/resolv.conf` -> `/run/systemd/resolve/resolv.conf`; host DNS resolves.
- Docker's daemon does NOT squat udp/53: the only listener there is the
  hohenheim java process.
- `docker run --rm hello-world` prints `Hello from Docker!`.
- The btrfs volume root is mounted (`/dev/loop0` on
  `/opt/hohenheim/data/volumes`, 40 GB) and quota-capable: `btrfs quota enable`
  followed by `qgroup show` succeeded and was disabled again.

## Host admission (`/admin/servers`)

The implicit `local` Docker row was taken through the full ceremony in the
panel:

1. Posture set to `shared_container` ("Shared containers (operator risk)") and
   saved -- the workspace kind refuses `trusted_only`.
2. Preflight: PASSED. 9 of 10 checks `pass`, the tenth (`userns_remap`) is the
   expected ADVISORY `warn` ("container root IS host root"). Required checks
   green: `daemon` (Docker 29.7.2 reachable), `api_version` (1.55, minimum
   1.41), `cgroup_pids_controller`, `pids_limit_enforced`, `seccomp`,
   `no_new_privs`, `nftables` ("nft transaction applied and read back from the
   kernel" -- the sudoers grant proving itself), `network_headroom`. State card
   reports `Volume storage: Btrfs`.
3. Admit: "Host local is admitted for placement".
4. "Accept posture risk" (type-the-name confirmation): `Posture risk: Accepted
   by Administrator (warning v1)`.

Capacity reads Booked 0 MB / Budget 10915 MB / Bookable 10915 MB, and nothing
runs on the host.

## Runtime image

`hohenheim/node-22:1` was LOADED, not built here -- the same lane starfleet
used, because a kaniko build on a fresh box is slow and this image already
exists on the workstation:

    docker save hohenheim/node-22:1 | gzip -1   ->  211,516,899 bytes transferred
    gunzip -c node-22.tar.gz | docker load      ->  Loaded image: hohenheim/node-22:1

Image id `da4ccc5030d9`, 853 MB disk / 213 MB content, identical to the
workstation's. `alpine:latest` and `hello-world:latest` are also present:
the preflight probe pulls alpine, and hello-world was the Docker smoke test.

2026-08-30: `hohenheim/node-16:1` and `hohenheim/node-12:1` were loaded the same
way (`docker save <tag> | gzip -1 | ssh ... 'gunzip | sudo docker load'`, one
tag per invocation), so all three runtime images this box can schedule are now
present and their full ids are byte-identical to the workstation's:

    hohenheim/node-12:1   0c94252f7f05   651 MB
    hohenheim/node-16:1   eec8a2ad45e9   761 MB
    hohenheim/node-22:1   da4ccc5030d9   853 MB

Disk went 5.1 GB -> 6.7 GB of 99 GB. Nothing else on the box was touched: no
site, zone, certificate or peer.

## Deploy target

Registered in `~/.config/zenit-dev/config.json` under `deployments` as
`robbedoes` (registered 2026-08-30 as the provisional `sites`, renamed the same
day):

    "robbedoes": {
        "ssh": "debian@51.255.43.81",
        "jar": "/opt/hohenheim/hohenheim-server.jar",
        "service": "hohenheim"
    }

`zenit-dev deployed robbedoes` answers with the stamp: jar 267,596,947 bytes,
service active and running the configured jar, `stamped: true`, 12 of 13 repos
`current`. The 13th is hohenheim itself, reported `local-ahead` because the local
checkout has one commit past `b486427f` -- correct, and the same answer
`deployed starfleet` gives. `unzip -p` and `systemctl show` both work
unprivileged, so the read needs no sudo.

## Rollback

There is nothing to roll back TO -- this was a fresh install, not an upgrade.
Undoing it completely is:

    systemctl disable --now hohenheim
    rm /etc/systemd/system/hohenheim.service /etc/sudoers.d/hohenheim-nft \
       /etc/sudoers.d/hohenheim-volumes /etc/sysctl.d/99-hohenheim.conf \
       /etc/systemd/resolved.conf.d/hohenheim.conf
    systemctl daemon-reload && systemctl restart systemd-resolved
    umount /opt/hohenheim/data/volumes      # and its /etc/fstab line
    swapoff /swapfile && rm /swapfile       # and its /etc/fstab line
    rm -rf /opt/hohenheim /var/log/hohenheim /root/hohenheim-admin.txt
    userdel hohenheim
    # docker CE and temurin-25-jre stay unless purged explicitly

Removing `/opt/hohenheim` destroys the field-encryption keyring together with the
database, which is the right pairing (`deploy-starfleet.md`): a keyring without
its database is useless and a database without its keyring cannot be read. It
also destroys the volume root loop file and every workspace volume in it. Once
this box holds real workloads, back the database and the keyring up together
before touching either.

From the FIRST jar swap onward the ordinary runbook applies
(`deploy-starfleet.md`): preflight copy of the database, an at-swap `.backup`,
the previous jar kept as `hohenheim-server.jar.rollback`, and a rehearsal against
a byte copy whenever the migration diff is non-empty.

## Still to do on this box

- Choose its public name, then set `network.main_url` and
  `auth.external_base_url`, and give it a site + certificate for the panel (an
  address upstream to `127.0.0.1:3000`).
- Open the provider firewall for 53 udp+tcp, 80 and 443, and CLOSE everything
  else. Right now the provider passes every port; 3000 is only safe because the
  listener itself is on loopback.
- Enrol it as a DNS peer of kuifje, the DNS primary, and give it the secondary
  zones it should carry. DONE 2026-08-30 (see "DNS federation, 2026-08-30"):
  peered with both kuifje and starfleet, and carrying `starfleet.life` as a
  secondary.
- Load the node-16 and node-12 runtime images once the lane building them is
  done. DONE 2026-08-30 (see "Runtime image").
- Migrate the Phoenix sites onto it (`docs/legacy-import.md`, `hoh-import-legacy`).

## DNS federation, 2026-08-30

This box joined the federation as a second SECONDARY beside kuifje. Both boxes ran
`b486427f`, the build that carries the health tier (M004/M007), so this is the
first run with three controllers in one federation. Every `visual-qa-*` record
created for it was removed; the peers and the secondary zone are left in place.

Reachability first, from the workstation with a raw python SOA query (no `dig`
here): `51.255.43.81` answers `REFUSED aa=0` for an out-of-zone name over UDP
and TCP, so the provider passes udp/tcp 53 and the authoritative-only refusal is
correct.

`dns.federation_name` was set in the settings editor BEFORE any peering (blank
falls back to the hostname; the announced name is what the receiving side
matches its peer row by). It read `sites` on the day; it is `robbedoes` since
2026-08-30, renamed together with every peer row naming this box.

Peers (`/admin/dns-peers`):

    robbedoes   "kuifje"     NAMESERVER, transfer 137.74.171.228:53   (peer id 1)
    robbedoes   "starfleet"  HOHENHEIM,  transfer 104.223.42.142:53,  (peer id 2)
                             base_url https://admin.starfleet.life + a znit_ key
                             labelled "sites dns federation" (the API key label
                             is not editable in the panel, so it keeps the
                             provisional word), scoped hohenheim.admin.access
    kuifje      "robbedoes"  NAMESERVER, transfer 51.255.43.81:53     (peer id 2)
    starfleet   "robbedoes"  NAMESERVER, transfer 51.255.43.81:53     (peer id 2)

The `kuifje` row here is a NAMESERVER peer for the same reason starfleet's is:
the form refuses a Hohenheim peer without an admin base URL
(`A Hohenheim peer needs an admin base URL`, reproduced deliberately), and
kuifje's panel is `127.0.0.1:3000` with no public hostname. Trap: switching the peer type
back to Nameserver after that refusal CLEARS the TSIG secret field -- retype it
before saving.

KEY NEGOTIATION HAS A DIRECTION and it decided both pairings:

- robbedoes <-> starfleet: negotiated with one click from THIS side ("Negotiate
  transfer key" on its `starfleet` peer), because starfleet is the only box with
  a reachable admin API. It wrote `xfer-sites-starfleet` / hmac-sha256 on both
  sides; starfleet logged `DNS: transfer key xfer-sites-starfleet installed for
  peer sites` and created its own peer row (named `sites` then, `robbedoes`
  since 2026-08-30; a TSIG key name never changes with a rename, which is why
  the key on the wire is still `xfer-sites-starfleet`). The announcement carries
  the NAME only, so the transfer host on starfleet's new row was blank and had
  to be filled in by hand (`51.255.43.81`).
- robbedoes <-> kuifje: NOT negotiable in either direction -- both panels are
  loopback-only, so neither side can reach the other's admin API. The key
  (`xfer-sites-ovh` / hmac-sha256, one secret generated on the workstation) was
  installed BY HAND through both peer forms. It becomes negotiable the moment
  either box gets a public panel hostname; re-negotiating then rotates it.

DEFERRED, deliberately: the admin (edit-forwarding) channel between robbedoes
and kuifje, for the same missing-base-URL reason. Nothing needs it -- neither box owns
a zone the other edits.

Zone: `starfleet.life` linked to peer `robbedoes` on starfleet's Secondaries tab
(`dns_zone_peers` id 2), then created here as role=secondary with owning peer
`starfleet` (zone id 1 on this box).

Verification, all three boxes:

    initial pull       2 s   zone saved 22:21:58Z, transferred 22:22:00Z serial 38
    TXT add            3 s   22:23:35Z click; kuifje 22:23:37.8, robbedoes 22:23:38.0, serial 39
    TXT delete         4 s   22:25:19Z click; kuifje 22:25:24.6, robbedoes 22:25:24.8, serial 40

`hoh-dns-diff compare starfleet.life --old 104.223.42.142 --new 51.255.43.81
--names admin,skeleton,www,ns1,ns2,comms` = IDENTICAL (9 rows: SOA, apex NS,
and the six A records), both sides authoritative. The disposable
`visual-qa-20260830-sites.starfleet.life TXT` was served by all three and, after
the delete, is NODATA-with-SOA at serial 40 on all three.

The primary now traces both peers, which the 2026-08-29 run could not:
`dns.notify_sent` to both peers and `dns.axfr_served` for
`xfer-ovh-starfleet` AND `xfer-sites-starfleet`, outcome `noerror`/`ok`, one per
serial. The zone row action "Check health" reports `Delegation: matches. 2
secondaries probed, 0 behind.` and the Secondaries tab shows both peers
`Current` at served serial 40 with their probe, last-AXFR and last-NOTIFY
stamps filled in.

Note this box is NOT delegated for `starfleet.life`: the registrar still points
at nssl/nssl2.mooo.com. It is a warm replica, not yet a public answer.

## Named `robbedoes`, 2026-08-30

The provisional `sites` is gone. Servers are named after Franco-Belgian comic
heroes in Dutch (`docs/deploy-native.md`, "Naming"). Renamed in one wave, in
this order, because the announced federation name is what a receiver matches
its peer row by:

1. `dns.federation_name` = `robbedoes` on this box (was `sites`), settings
   editor.
2. The peer rows naming this box, on the same day: starfleet peer id 2
   `sites` -> `robbedoes`, kuifje peer id 2 `sites` -> `robbedoes`. This box's
   own `ovh` row (peer id 1) became `kuifje`.
3. OS hostname and the zenit-dev deploy target, both already `robbedoes`.

Nothing else on any row moved: transfer host/port, TSIG key name, algorithm,
base URL and API key are untouched, and a pure rename does NOT clear the stored
TSIG secret (blank means keep; only "Clear stored value" clears it -- the known
clearing trap is the peer TYPE change, not the name). The TSIG key names keep
their old spelling (`xfer-sites-starfleet`, `xfer-sites-ovh`) on purpose: a key
name is wire identity and renaming one would need both sides rekeyed for no
gain. The starfleet API key labelled `sites dns federation` also keeps its
label -- the API keys page offers only Revoke, never an edit, and the key must
not be re-minted for a cosmetic label.

Proven end to end right after: a disposable
`visual-qa-20260830-rename.starfleet.life TXT` added and then deleted on
starfleet moved the serial 40 -> 41 -> 42; all three boxes answered each serial
within seconds, starfleet journaled `dns.notify_sent` naming `kuifje` AND
`robbedoes` (`noerror`), **Check health** reported `Delegation: matches. 2
secondaries probed, 0 behind.`, and the Secondaries tab showed both peers
`Current`. No service was restarted.

## Deploy 2026-08-30 (first jar swap): `17fa6993`, no migration

Shipped hohenheim `17fa6993` (previous `b486427f`, the jar copied from
starfleet at install time) as the third stop of the twelfth starfleet deploy
(`deploy-starfleet.md`): same worktree, stamp 13/13 clean, sha256
`efbf9b35d7ab7f57fc2cedaff128d5670720ec249f95a243918a8e3c1b9d971a`,
267,604,365 bytes, `scp` + the `grep -c false | grep -qx 13` gate. Migration
diff: none. This is the first swap on this box, so the ordinary runbook now
applies here too.

Whole lane `sudo -n`: preflight `/root/hohenheim-preflight-20260830-twelfth/`
(`.pre`, `.at-swap`, `settings/`, `hohenheim-server.jar.rollback`, keyring
sha256 equal); rehearsal as the service user from
`/opt/hohenheim-rehearsal-20260830-twelfth` on a byte copy with hand-written
JSON settings: `Migrations complete 0 applied`, 46 rows, inert boot healthy
after 6 s (`-Xmx1024m`), `/login` 200, 0 exceptions, dir removed. Live: at-swap
`.backup` (ok), `install` beside, stop, `mv`, `--run-migrations` as `hohenheim`
(0 applied, 46 rows), start; 7 s to health (10 s downtime). Second restart 6 s.
0 journal errors, `roles_captured [databases, dns, firewall, instances,
proxy]`, listeners `*:53`, `*:80`, `127.0.0.1:3000`, staged jar removed from
`/home/debian`. `zenit-dev deployed robbedoes` = `current`.

What this jar brings here: the `node-16` and `node-12` runtime image seeds
(`ab922504`); `/admin/runtime-images` over a loopback panel session now lists
`node-22`, `node-16` ("Node.js 16 on Debian, for a legacy app that cannot run
on a current release") and `node-12` ("Node.js 12 on Debian buster, the
oldest runtime still offered"), all enabled, matching the three
`hohenheim/*:1` images already loaded on the daemon.

Federation after the deploy: starfleet's disposable TXT add + delete moved the
serial 42 -> 43 -> 44; this box transferred 43 at 22:58:33.2Z and 44 at
22:59:08.5Z and answers SOA 44 byte-identical to the primary.

ROLLBACK IS JAR ONLY (no schema change); the at-swap copy sits in the
preflight dir regardless.

## Earl staged, 2026-08-30

The FIRST Phoenix site staged on this box. Staged only: no DNS record was
touched anywhere, no certificate was requested, no service was restarted. The
name still resolves to Phoenix, so nothing a visitor sees has moved.

Earl is one static page. On Phoenix it was an Apache proxy with a `Host:
earl.phoenix` rewrite (`hoh site create Earl hohenheim:address ...
settings.rewrite_location=false` in the converter's dry run); here that whole
shape collapses to a `hohenheim:static` site, so the Host header row and the
`rewrite_location=false` it needed are gone.

### The document root

    /opt/hohenheim/public/earl/index.html   hohenheim:hohenheim 644, 1024 bytes
    /opt/hohenheim/public/earl              hohenheim:hohenheim 755
    /opt/hohenheim/public                   hohenheim:hohenheim 750 (unchanged)

Uploaded with a staged temp file + remote sha256 verify + atomic move; the
served bytes hash `c994a6d8a87dd266724d84f209c90cd6a7295b1044ae189a8f3b7abc017c70c5`,
equal to the capture taken from Phoenix's Apache. Same modes as starfleet's
`public/catch-all` (750/755/644, service user throughout), which is the layout
this directory follows.

### The API key for `tools/hoh`

Minted through the product's own `/account/api-keys` form over the loopback
panel (curl with the page's `csrf_token`), never by writing the database.
Label `hoh CLI (robbedoes automation)`, scopes
`hohenheim.admin.access cap:hohenheim:site#manage`, no expiry -- admin because
site create/delete is admin-only, the capability scope because domain rows are
a `manage` affordance. The plaintext is rendered exactly once and lives in TWO
places only:

    /root/hohenheim-hoh-key.txt          on the box, 0600 root:root
    ~/.config/hoh/config.json            on the workstation, 0600, context "robbedoes"

The workstation context's host is `http://127.0.0.1:3000`, so every `hoh` call
needs the panel forward up first (`ssh -L 3000:127.0.0.1:3000
debian@51.255.43.81`); `network.bind_address` is 127.0.0.1 here and there is no
panel site yet. Rotate the key by revoking it in `/account/api-keys` and
re-running `hoh login`.

### The site

    site 1     Earl / earl, hohenheim:static, enabled, status active
               settings {"root_path":"/opt/hohenheim/public/earl",
                         "autoindex":false,"fallback_file":"index.html"}
    domain 1   earl.wcag.be, exact, force_ssl true, certificate_id null,
               exclude_from_letsencrypt false, live true, generated false

Created with `hoh site create Earl hohenheim:static
settings.root_path=/opt/hohenheim/public/earl settings.fallback_file=index.html
settings.autoindex=false` then `hoh site domain add 1 earl.wcag.be`.

### Evidence

`curl -H 'Host: earl.wcag.be' http://51.255.43.81/` answers **503 "HTTPS
required"** (2636 bytes, `Retry-After: 30`), NOT a 301. That is Hohenheim's own
answer for a `force_ssl` row with no certificate, and it is the proof the route
is claimed: an unknown Host on the same port answers 404 "No site configured".
Port 443 is `Connection refused` from outside -- no certificate exists, so the
TLS listener is still not up, exactly as after the install.

Use `--noproxy '*'` when curling this box from the workstation. The shell there
exports `HTTP_PROXY`/`HTTPS_PROXY` to mitmproxy, which makes `--resolve` a lie:
a first `--resolve earl.wcag.be:443:51.255.43.81` came back
`server: Apache/2.4.18 (Ubuntu)` -- that was LIVE PHOENIX answering through the
proxy, not this host.

The static root itself was proven to serve, without touching the real row: a
temporary second domain row `earl-staging.robbedoes.invalid` (`force_ssl=false`,
`exclude_from_letsencrypt=true`, unresolvable `.invalid` name) answered
**200, Content-Length 1024**, byte-identical to the captured file, and was then
removed. `SiteDispatcher` logged the whole arc -- `0 -> 1 -> 2 -> 1 exact
routes` -- and the journal carries no error or exception.

Panel over the forward: `/admin/sites` lists one row, `Earl / earl`, hostname
`earl.wcag.be`, Serves `Static /opt/hohenheim/public/earl`, TLS `HTTPS`,
`1-1 of 1`.

### Cutover steps remaining

1. Delegate `wcag.be` (or at least `earl.wcag.be`) so this box can be validated.
2. Request the certificate for `earl.wcag.be` from `/admin/certificates-request?site=1`.
   `CertificateAuthority.authorize` refuses a SAN no live site-domain row covers,
   and domain 1 is that row, so this order is mandatory.
3. Flip A/AAAA for `earl.wcag.be` to `51.255.43.81` /
   `2001:41d0:305:2100::1:4b26` in the `wcag.be` zone on kuifje.
4. Re-run the curl above: the 503 becomes a 301 to HTTPS, and HTTPS serves the
   page.
5. Retire the Phoenix vhost only after that.
