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

2026-08-30, later: `hohenheim/node-10:1` (Udesign Preview runs 10.15.3) was
built ON THIS BOX from the committed `images/node-10` context rather than
saved-and-loaded -- the workstation's outbound HTTPS goes through mitmproxy and
its disk is near full. Image `be529adc128d`, 674 MB disk / 165 MB content;
`node --version` v10.24.1, `npm` 6.14.12, and `npm install mmmagic` compiles and
loads (g++ 8.3.0, python 2.7.16 + 3.7.3 both present). The seed row lands with
the next deploy of the jar.

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

## Taverne Tomberg staged, 2026-08-30

The first Alchemy/Node app from Phoenix, staged on robbedoes WITHOUT a cutover:
`tavernetomberg.be` still resolves to Phoenix; robbedoes answers the same site
when asked by `Host` header. Everything below is proven live.

### What it is

`/home/www-data/tomberg` on Phoenix (git `skerit/tomberg`, last commit
`1180a4a` 2017-02-20, so the working copy IS the source): `alchemymvc
1.1.7-alpha` installed from GitHub with its own nested `node_modules` (3.3 GB of
the 4.7 GB: hawkejs 1 GB, janeway/protoblast/sputnik 0.6 GB each, each carrying
its git history), Node 12.18.2 on Phoenix, `settings.port = 3000` in
`app/config/default.js`, Mongo `tomberg` on `127.0.0.1` in
`app/config/live/database.js`, mailer through `calamity.develry.be:587`
(unchanged, reachable from the container). No `.phoenix` hostname and no
absolute path in `app/config`. `temp/imagecache` (492 MB) is a regenerable
cache; `files/` (27 MB) holds the uploads.

### Rows on robbedoes

| Object | Id | Notes |
| --- | --- | --- |
| managed database `tomberg-mongo` | 1 | engine mongo, image default `mongo:7` (robbedoes has AVX), db `tomberg`, user `tomberg`, host `local`; container `hohenheim-luguij0q-instance-1` (row instance 1 `db-tomberg-mongo`) |
| instance `taverne-tomberg` | 3 | `hohenheim:workspace` on runtime image `node-12` (id 6), NO git source, `start_command = node server.js`, `container_port 3000`, `console_kind tty`, `memory_limit_mb 1024`, `auto_deploy false`; container `hohenheim-luguij0q-instance-3`, home volume `/opt/hohenheim/data/volumes/3/home` -> `/home/site`, uid 200003, published `127.0.0.1:32773->3000` |
| link `instance_databases` | 1 | instance 3 -> database 1, prefix `TOMBERG_DB`; injects `TOMBERG_DB_*` AND the bare `DATABASE_URL` (first link) |
| site `Taverne Tomberg` | 2 | `hohenheim:instance`, instance 3 |
| domains | 3, 4 | `tavernetomberg.be`, `www.tavernetomberg.be`, exact, `force_ssl=false` for the staging check (robbedoes has no TLS listener yet), both `live` |

### Exact lane

1. Dump on Phoenix as `skerit` (credentials read from `app/config/live/database.js`,
   never printed): `mongodump --host 127.0.0.1 --db tomberg -u tomberg -p ... --out
   ~/tomberg-dump` (988 KB, 15 collections), tarred. Temp files removed afterwards.
2. Code streamed Phoenix -> workstation -> robbedoes with NO local copy:
   `ssh phoenix 'tar cf - --exclude=.git --exclude=.nyc_output --exclude=coverage
   --exclude=tomberg/temp/imagecache tomberg' | ssh robbedoes 'tar xf - -C
   ~/tomberg-stage'` (3.9 GB after the excludes). `node_modules` was kept as-is:
   Node 12.18.2 and the image's 12.22.12 share ABI 72, and buster's glibc 2.28 is
   newer than xenial's 2.23, so no native module needed a rebuild (mmmagic loads).
3. `app/config/live/database.js` in the staged copy rewritten to
   `Datasource.create('mongo', 'default', { uri: process.env.DATABASE_URL })`
   (alchemy 1.1's Mongo datasource honours `options.uri`, see its
   `mongo_datasource.js:30`); `temp/imagecache` recreated empty.
4. API key `tomberg staging` (scope `hohenheim.*`, 30 days) minted on
   `/account/api-keys` over loopback; `hoh` driven from the workstation over an
   ssh forward (`HOH_HOST=http://127.0.0.1:13001`). Database created through the
   panel form `POST /admin/databases/new` (no API for databases yet).
5. `hoh instance create taverne-tomberg hohenheim:workspace server_id=1
   runtime_image_id=6 settings.start_command='node server.js'
   settings.container_port=3000 settings.console_kind=tty
   settings.memory_limit_mb=1024 settings.auto_deploy=false`, the attachment
   through `POST /admin/instance-databases/new` (database 1, prefix `TOMBERG_DB`),
   `hoh power 3 start`.
6. Restore: `docker cp` the dump into the database container, then inside it
   `mongorestore --username $MONGO_INITDB_ROOT_USERNAME ... --authenticationDatabase
   admin --db tomberg --drop /tmp/tomberg-dump` (the `mongo:7` image ships
   `mongorestore` and `mongosh`; the restore-from-upload lane was not used because
   its accepted shape for Mongo was not verified). Result: 15 collections, 10
   pages, 241 products. Temp dir removed from the container.
7. Code into the home volume on the host: `tar cf - . | tar xf - -C
   /opt/hohenheim/data/volumes/3/home` then `chown -R 200003:200003` (there is no
   rsync on robbedoes). `hoh power 3 restart`.
8. `hoh site create "Taverne Tomberg" hohenheim:instance instance_id=3`, then
   `hoh site domain add 2 tavernetomberg.be force_ssl=false` and the `www` twin.

### Proof

- Inside the container: `tini -- bash -lc node server.js`, `node server.js`
  listening on `*:3000` as uid 200003.
- Direct: `curl http://127.0.0.1:32773/` on robbedoes = 200, 145 409 bytes,
  `<title>Taverne Tomberg</title>`, same `<h1>` sequence as the live site
  (`Taverne Tomberg`, `Update november 2024: ... Een nieuw seizoen, een warm
  welkom!`, `Openingsuren`), i.e. the restored database is what renders.
- Through the proxy from the workstation: `curl --noproxy '*' -H 'Host:
  tavernetomberg.be' http://51.255.43.81/` = 200 / 145 412 bytes, the `www`
  twin 200 / 145 416, `/menu` 200, the page's first stylesheet 200. Live Phoenix
  (`https://tavernetomberg.be/` fetched from Phoenix itself) = 200 / 145 413
  bytes, identical title and headings.
- `docker stats`: instance 3 96 MiB / 1 GiB, the Mongo 121 MiB / 1.25 GiB.
- `SiteDispatcher` journal: `loaded 1 -> 2 -> 3 exact routes` as the site and its
  two domains landed; no error or exception.

### What differs from Phoenix

Node 12.22.12 instead of 12.18.2 (same major, same ABI); TCP port 3000 instead of
the hohenchild UNIX socket (`alchemy.js:452-465` only binds a socket when
`--port=<path>` is passed, which nothing passes now); Mongo 7 instead of 3.4 (the
dump restored cleanly; the app's driver speaks the wire protocol fine); the
database is reached by the link network handle instead of `127.0.0.1`; the
image cache started empty.

### Traps hit

- A workspace WITHOUT a git source runs `bash -lc <start_command>` directly in
  `/home/site` (the `if [ ! -d /home/site/app ]` idle wrapper belongs to the
  source lane only), so the code goes in the volume ROOT, not under `app/`.
  The first start exited 1 with `Cannot find module '/home/site/server.js'`
  before the copy, and `InstanceStatusReconciler` moved the row to `stopped`
  within a minute; a restart after the copy was enough.
- The streamed tar's second member (the dump tgz) never arrived; copied it with
  `scp` separately.
- `~/.config/hoh/config.json` is shared by every agent on the workstation and a
  concurrent lane overwrote the default context (`hoh` then said `ECONNREFUSED
  127.0.0.1:3000`); drive `hoh` with explicit `HOH_HOST`/`HOH_TOKEN` when more
  than one lane runs.
- `hoh help` prints the help and then crashes (`handler(positional).catch` on an
  undefined return), harmless but ugly.
- `hoh logs 3` printed nothing for the tty console (the console history of a
  TTY workload is raw ANSI and Janeway draws on the alternate screen); `docker
  top`/`ss` inside the container and a curl are the honest checks.

### Cutover steps remaining (in order)

1. Zone `tavernetomberg.be` onto kuifje (primary; robbedoes secondary), import
   from the Hetzner export, `hoh-dns-diff compare` IDENTICAL, registrar NS
   change, `delegation` OK. The zone carries MX to calamity: the MX/TXT lines
   are the gate.
2. Certificate for `tavernetomberg.be` + `www` requested on robbedoes via DNS-01
   through kuifje (needs kuifje's admin channel reachable from robbedoes) or via
   HTTP-01 right after step 3; then set `force_ssl=true` on domains 3 and 4.
3. Lower the A/AAAA TTL, flip both names to `51.255.43.81` /
   `2001:41d0:305:2100::1:4b26` in the kuifje zone, `hoh-dns-diff propagate`.
4. Re-run the Host-header curl over HTTPS; watch Phoenix's access log for
   `tavernetomberg.be` going quiet; Phoenix stays the rollback (its process and
   Mongo are untouched) until then.
5. Rotate the `tomberg staging` API key or let it expire (30 days).

## Microcopy staged, 2026-08-30

The Alchemy app merlina calls on every page render (`microcopy.elevenways.be`,
342k requests/quarter from `2a01:4f8:a0:948c::2`), staged on robbedoes as a
`docker_container` on `hohenheim/node-16:1`; NOT cut over. Phoenix keeps serving.

Records: managed database 2 `microcopy-mongo` (`mongo:7`, db `11ways_microcopy`,
user `microcopy`, host `local`; its container is `hohenheim-luguij0q-instance-4`),
instance 2 `microcopy` (`hohenheim:docker_container`, image `hohenheim/node-16`
tag `1`, command `node server.js`, container port 3000, volume `app` ->
`/home/site` = `hohenheim-luguij0q-instance-2-vol-app`, env `ALCHEMY_ENV=live`,
console `tty`, 512 MB), attachment 2 (prefix `DB`, so `DB_HOST/PORT/USER/PASSWORD/
NAME/URL` + `DATABASE_URL` are injected), site 3 `Microcopy` (instance upstream),
domain 6 `microcopy.elevenways.be` (exact, `force_ssl=false` until a certificate
exists; domain 5 was the first, force_ssl=true, removed).

Source: `/home/11ways/microcopy` on Phoenix read as `skerit` (575 MB = 325 MB
`node_modules` + 250 MB `node_modules_old`; the app itself is 188 KB), tarred
without both `node_modules*` and `temp/imagecache`, plus `mongodump` of
`11ways_microcopy` with the app's own credentials (152 KB: 589 microcopies, 4
users, 3 acl groups, 2 acl rules, 22 persistent cookies). Both restored on
robbedoes from `/home/debian/microcopy-stage/`; the Phoenix temp dir was removed.
Nothing on Phoenix was modified.

What differs from Phoenix:

- Node 16.20.2 (image) instead of 16.13.2; same major, native modules rebuilt.
- The app listened on a UNIX socket handed over by hohenchild (`--port=<socket>`);
  without it alchemy falls back to `settings.port` = 3000 over TCP. No `PORT` env
  is read by alchemy 1.2.5-alpha.
- `app/config/live/database.js` rewritten (the only app edit): prefers
  `DATABASE_URL` as a full uri (alchemy's mongo datasource accepts `options.uri`),
  falls back to the `DB_*` family. The uri is REQUIRED here, not a nicety: the
  managed user is the engine's ROOT user living in the `admin` database, and the
  injected uri carries `authSource=admin`; the host/login/password form
  authenticates against the target db and fails with `AuthenticationFailed`.
- Dependencies: `npm ci` refuses the 2022 lockfile under npm 8's strict peer
  rules (`--legacy-peer-deps` needed), and `@11ways/exiv2` has no prebuilt binary
  and needs `libexiv2-dev`, which the runtime image does not ship. Installed with
  `npm ci --legacy-peer-deps --ignore-scripts`, then `npm rebuild mmmagic canvas
  bcrypt sass-embedded` (canvas + bcrypt fetch prebuilts, mmmagic compiles), then
  `node node_modules/sass-embedded/download-compiler-for-end-user.js` (its
  postinstall fetches the dart-sass binary). exiv2 stays unbuilt: alchemy-media
  loads it through `alchemy.use` and degrades to no EXIF extraction, which is
  what Phoenix effectively did for a text-only app. 287 modules, 323 MB in the
  volume, 78 MB RSS at idle (mongo 103 MB).
- The mongodump was restored with `docker cp` + `mongorestore --drop
  --authenticationDatabase admin` inside the database container (the panel's
  Restore lane was not tried; the dump is a directory archive).

Procedure that worked (the copy-into-volume shape from `docs/wordpress.md`):
create the instance through the API, attach the database, deploy once (the
container exits 1: empty volume), then a helper container `docker run --rm -v
<volume>:/home/site -v <stage>:/src:ro hohenheim/node-16:1` copies the tree and
installs, then `hoh power 2 restart`.

Verification (2026-08-30 01:5x CEST):

- Inside the container `curl -H 'Host: microcopy.elevenways.be'
  http://127.0.0.1:3000/` -> 200, 6699 bytes, `<title>Microcopy | Eleven Ways</title>`
  (Phoenix answers 200, 6700 bytes, same title).
- Through robbedoes' proxy from the workstation `curl -H 'Host:
  microcopy.elevenways.be' http://51.255.43.81/` -> 200, 6699 bytes, same title.
  With `force_ssl=true` and no certificate the proxy answered `503 HTTPS
  required`, which is why domain 6 carries `force_ssl=false` for now.
- merlina's real calls replayed (`GET /api/microcopy/{key}?locales[0]=en&locales
  [1]=en&locales[2]=fi` for `proud-partner-of`, `switch-light-dark`,
  `skip-to-main-content`, plus `proud-partner-of?locales[0]=nl`): Phoenix and
  robbedoes answer BYTE-IDENTICAL bodies (484/491/501/486 bytes, DRY-JSON with the
  same ObjectIDs). The endpoint returns `null` unless the request carries either
  a `Referer` header or the `access-key` header matching the plugin's
  `wanted_key` (alchemy-i18n `bootstrap.js:18-22`); merlina sends the key, the
  replay used a Referer.
- Panel: Overview `Running`, `Status confirmed`; the Console tab renders the
  terminal ("This workload runs in a real terminal"). `docker logs` of the tty
  container is EMPTY (Janeway draws on the PTY's alternate screen); a plain
  `docker exec ... node server.js` is how the boot errors above were read.

Traps and product findings:

- `hoh instance create ... settings.volumes.app=/home/site
  settings.environment_variables.ALCHEMY_ENV=live` was ACCEPTED and both maps
  landed EMPTY (`"volumes":{},"environment_variables":{}` in the row): the dotted
  map spelling is neither the StringMapField transport nor refused as an unknown
  key. Silent drop; the panel form's Add-row was used instead. Worth a refusal.
- The instance row read `running` while the first container had exited 1;
  `InstanceStatusReconciler` corrected it a minute later.
- `hoh` reads `~/.config/hoh/config.json`, shared by every lane on the
  workstation; drive it with `HOH_HOST`/`HOH_TOKEN` per call.

### Cutover steps remaining (in order)

1. Zone `elevenways.be` onto kuifje (primary; robbedoes secondary), import from
   the Hetzner export (it carries Google MX + SPF/DKIM: the mail lines are the
   gate), `hoh-dns-diff compare` IDENTICAL, registrar NS change, `delegation` OK.
2. Certificate for `microcopy.elevenways.be` on robbedoes (DNS-01 through kuifje
   once its admin channel is reachable, else HTTP-01 right after step 3), then
   `force_ssl=true` on domain 6.
3. Lower the TTL, flip A/AAAA of `microcopy.elevenways.be` to `51.255.43.81` /
   `2001:41d0:305:2100::1:4b26` in the kuifje zone, `hoh-dns-diff propagate`.
   THIS is the moment merlina's hard dependency moves: merlina calls over IPv6,
   so the AAAA must be right, and the `access-key` it sends must still match the
   plugin config (unchanged, copied verbatim).
4. Watch Phoenix's access log for `microcopy.elevenways.be` going quiet; Phoenix
   stays the rollback until then.

## Earl LIVE (first hostname cutover), 2026-08-30

The `.be` parent published the mooo pair at ~01:09Z (checked 01:04Z: still
Hetzner; 01:09Z: `parent NS nskuifje.mooo.com., nsrobbedoes.mooo.com.`,
`hoh-dns-diff delegation wcag.be` VERDICT DELEGATION OK, both servers
authoritative at serial 8; the kuifje AAAA is unprobeable from the
workstation, which has no IPv6 route -- not a server fault).

Order of operations, all on kuifje zone 3 through the panel:

1. `earl CNAME phoenix.develry.be` (TTL 700) deleted, then `earl A
   51.255.43.81` and `earl AAAA 2001:41d0:305:2100::1:4b26` added, both TTL
   700. (Adding A beside the CNAME is refused first: "A CNAME owner cannot
   hold any other record" -- delete-then-add is the required order, the
   authoritative gap lasted seconds and caches held the 700s CNAME.)
2. `compare wcag.be --old 137.74.171.228 --new 51.255.43.81 --strict` ->
   **IDENTICAL**, both sides serial 11 (robbedoes transferred within seconds).
3. `propagate earl.wcag.be A --expect 51.255.43.81` -> **PROPAGATED** on
   1.1.1.1 / 8.8.8.8 / 9.9.9.9 by round 3.
4. Certificate 1 `Earl` requested ONCE from
   `/admin/certificates-request?site=1` (HTTP-01, prefilled by `?site=1`).
   Journal: `ACME: account ready (global, production)` 01:20:53Z,
   `ACME: certificate issued for earl.wcag.be` 01:21:01Z, `Proxy HTTPS
   listening on port 443 (1 certificates)`. Issuer Let's Encrypt YR1, valid
   2026-08-30 00:22:30Z to 2026-11-28 00:22:29Z, auto-renew.

Verification, byte-level:

    curl --resolve earl.wcag.be:443:51.255.43.81 https://earl.wcag.be/
      -> 200, 1024 bytes, ssl_verify_result 0
    sha256 c994a6d8a87dd266724d84f209c90cd6a7295b1044ae189a8f3b7abc017c70c5
      == the page Phoenix served BEFORE the flip (captured first): cmp equal
    from phoenix (has IPv6): --resolve ...:[2001:41d0:305:2100::1:4b26]
      -> 200, 1024 bytes; 1.1.1.1 answers A 51.255.43.81 + the AAAA
    http://earl.wcag.be/ -> 302 https:// (force_ssl)

TRAP: a plain `curl https://earl.wcag.be/` from the workstation right after
the flip still hit Phoenix (`remote_ip 144.76.30.204`, resolver cache, TTL
700) while presenting robbedoes' NEW certificate story is impossible -- the
matching notBefore was the tell to re-check; always verify a cutover with
`--resolve` against the new address, never through the resolver.

Zone row action Check health on kuifje: `Delegation: apex_undeclared. 1
secondaries probed, 0 behind.` -- correct while the apex NS is the interim
mooo pair instead of the declared `ns1/ns2.elevenways.de`; it becomes
`matches` when the zone moves to the final nameserver names.

Rollback (not needed): put `earl` back to `CNAME phoenix.develry.be.`;
Phoenix still serves the site and keeps its own certificate.
