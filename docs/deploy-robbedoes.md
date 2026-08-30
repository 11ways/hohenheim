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

## Deploy 2026-08-30 (second jar swap): e6d15bf1, no migration

Same jar again (sha256 `605f5303...`, stamp 13/13 clean), deployed AFTER kuifje
and only once kuifje was verified answering -- these two are each other's only
nameserver for two live zones, so they are never restarted together.

Migration diff NONE; `--run-migrations` run with an explicit `cd /opt/hohenheim`
reported `Migrations complete 0 applied` (see the kuifje runbook for why the
working directory is load-bearing).

Stop 01:49:48Z, start 01:49:51Z, healthy 01:50:00Z -- 12 s downtime, the
shortest of the three boxes. Second restart 01:50:18Z, healthy 01:50:28Z (10 s).
Both: 0 journal errors, RSS 383 MB, listeners 53/80/443 plus 3000 on loopback,
`CertificateStore: loaded 1 certificates, 1 hostname mappings` and
`Proxy HTTPS listening on port 443 (1 certificates...)`. Row counts identical
before and after: 46 migrations, 5 DNS zones (all secondary), 3 sites,
4 domains, 2 certificates, 4 instances, 2 managed databases.

EARL SURVIVED THE SWAP UNCHANGED. Captured before the deploy and again after
both restarts: `https://earl.wcag.be/` = 200, 1024 bytes, sha256
`c994a6d8a87dd266724d84f209c90cd6a7295b1044ae189a8f3b7abc017c70c5`, `cmp`
byte-identical, `ssl_verify_result 0`; certificate still `CN=earl.wcag.be`,
issuer Let's Encrypt YR1, `notAfter Nov 28 00:22:29 2026 GMT`; HTTP 301. The
probe was run both with `--resolve` against 51.255.43.81 and through public
DNS, which answered `remote_ip 51.255.43.81` -- the real path.

All four instance containers kept running across both restarts, and
`/opt/hohenheim/staging/phoenix/` (1.5 GB, the ten Phoenix app + mongo
tarballs) was untouched.

Neighbouring live sites unaffected, still served by their old homes:
`wcag.be` and `www.wcag.be` 200 from merlina (213.239.210.245),
`tavernetomberg.be` and `www.` 200 from phoenix (144.76.30.204).

`zenit-dev deployed robbedoes` = the jar at `e6d15bf1`, all 12 upstream repos
`current`. Rollback jar only:
`/root/hohenheim-preflight-20260830-thirteenth/`.

## Cutover TTLs lowered on kuifje, 2026-08-30

Before any further hostname cutover, the records that will move got a 300s TTL
so a flip (and a rollback) takes effect quickly. Only the TTL changed; no value
was touched, and no traffic moved.

| zone | record | TTL before | TTL after |
| --- | --- | --- | --- |
| wcag.be (kuifje zone 3) | `invulassistent` CNAME | 7200 | 300 |
| tavernetomberg.be (zone 5) | `@` A | 7200 | 300 |
| tavernetomberg.be | `@` AAAA | 7200 | 300 |
| tavernetomberg.be | `www` CNAME | 7200 | 300 |

Serials: wcag.be 11 -> 12, tavernetomberg.be 6 -> 9 (each save bumps).
robbedoes transferred both within seconds. `hoh-dns-diff compare --strict`
IDENTICAL on both zones, apex NS and SOA included. The mail rows are
deliberately untouched and verified still at TTL 7200 on the wire:
`tavernetomberg.be MX 10 calamity.develry.be` and
`_imaps._tcp SRV 0 100 993 calamity.develry.be`. Both zones still report
`Delegation: matches`.

## WCAG-EM Auditexport staged, 2026-08-30

`auditexport.di-ax.be` (20,673 requests/quarter on phoenix), staged on
robbedoes WITHOUT a cutover: the hostname still resolves to phoenix, and
robbedoes answers the same site when asked by `Host` header. The `di-ax.be`
zone is not ours yet, so no certificate and no DNS change is possible here.

### What it is

`/home/diax/auditexport` on phoenix (uid 4016, Node 16.13.2, `node server.js`,
`settings.port = 3000` in `app/config/default.js`, `environment: 'live'` in
`app/config/local.js`). `alchemymvc ~1.2.0` with alchemy-acl/chimera/form/i18n/
media/menu/styleboost/widget, `web-resource-inliner` and `jszip`. The app tree
is 672 KB; the 602 MB on phoenix was `node_modules` + `node_modules_old`, both
excluded from the tarball and reinstalled here.

### Rows on robbedoes

| Object | Id | Notes |
| --- | --- | --- |
| managed database `auditexport-mongo` | 3 | engine mongo, image default `mongo:7`, db `diax_auditexport`, user `auditexport`, host `local`; container `hohenheim-luguij0q-instance-5` |
| instance `auditexport` | 6 | `hohenheim:workspace` on runtime image `node-16` (id 5), no git source, `start_command = node server.js`, `container_port 3000`, `console_kind tty`, `memory_limit_mb 1024`, `auto_deploy false`; container `hohenheim-luguij0q-instance-6`, volume `/opt/hohenheim/data/volumes/6/home` -> `/home/site`, uid 200006, published `127.0.0.1:32778->3000` |
| attachment `instance_databases` | 3 | instance 6 -> database 3, prefix `DB`; first attachment, so `DATABASE_URL` is injected too |
| instance variable | - | `LD_LIBRARY_PATH=/home/site/node_modules/canvas/build/Release` (set with `hoh vars instance 6 set`, NOT the dotted map spelling that silently drops) |
| site `WCAG-EM Auditexport` | 4 | `hohenheim:instance`, instance 6 |
| domain | 7 | `auditexport.di-ax.be`, exact, `force_ssl=false` (no certificate is possible until the zone moves) |

### Exact lane

1. Code and dump taken from the staged tarballs in
   `/opt/hohenheim/staging/phoenix/` (nothing re-fetched from phoenix).
2. `app/config/live/database.js` rewritten to
   `Datasource.create('mongo', 'default', { uri: process.env.DATABASE_URL })` --
   the single app edit, same as Tomberg and Microcopy. The managed user is the
   engine's root user in `admin`, and the injected uri carries `authSource=admin`.
3. Database created through the panel form (`/admin/databases/new`; there is
   still no database API), then `docker cp` + `mongorestore --drop
   --authenticationDatabase admin` inside the container.
4. `hoh instance create auditexport hohenheim:workspace server_id=1
   runtime_image_id=5 settings.start_command='node server.js'
   settings.container_port=3000 settings.console_kind=tty
   settings.memory_limit_mb=1024 settings.auto_deploy=false`, attachment through
   `/admin/instance-databases/new`, `hoh vars instance 6 set LD_LIBRARY_PATH ...`.
5. `hoh power 6 start` to create the volume (the container exits 1 on an empty
   volume, as documented), code copied in as root and `chown -R 200006:200006`,
   `npm ci --legacy-peer-deps --ignore-scripts` then `npm rebuild canvas bcrypt
   mmmagic` in a helper container as uid 200006, `hoh power 6 restart`.
6. `hoh site create "WCAG-EM Auditexport" hohenheim:instance instance_id=6`,
   `hoh site domain add 4 auditexport.di-ax.be force_ssl=false`.

### Proof

Restore: 10 collections (`acl_groups`, `acl_persistent_cookies`, `acl_rules`,
`media_files`, `menus`, `report_languages`, `report_types`, `reports`, `themes`,
`users`), 265 documents, `reports=221 users=4`.

Native modules in the container: `canvas ok`, `bcrypt ok`, `mmmagic ok`.

Inside the container `node server.js` listens on `*:3000` as uid 200006.

Through robbedoes' proxy from the workstation with the `Host` header, against
phoenix fetched with `--resolve`:

| path | phoenix | robbedoes |
| --- | --- | --- |
| `/` | 200, 0 bytes, `etag: S0-00`, `x-history-url: /` | 200, 0 bytes, same headers |
| `/login` | 200, 8138 bytes, `<title>Login \| Auditexport</title>` | 200, 8137 bytes, same title |
| `/chimera` | 401, 7579 bytes | 401, 7579 bytes (exactly equal) |
| `/media` | 404, 41 bytes | 404, 41 bytes |

The empty 200 at `/` is what phoenix serves too, not a fault. The `/login`
byte difference is three render-time nonces and nothing else, proven by a diff
with hex-like runs normalised: the `hawkejs/static.js?i=` cache buster (per
boot), the `data-hid` timestamp id, and inside `window._initHawkejs` the request
URL and the client's user-agent version (curl 8.21 on phoenix, 8.14.1 here).
Every template, block and title is identical, so the restored database and the
rendered app are the same.

`docker stats`: instance 6 107 MiB / 1 GiB, its Mongo 165 MiB / 1.25 GiB.

### What differs from phoenix

Node 16.20.2 instead of 16.13.2 (same major); Mongo 7 instead of phoenix's
engine; the database is reached over the link network by uri instead of
`127.0.0.1` with a hardcoded login; `@11ways/exiv2` stays unbuilt (the runtime
image ships no `libexiv2-dev`), so alchemy-media degrades to no EXIF
extraction, exactly as Microcopy does here.

### Cutover steps remaining (in order)

1. The `di-ax.be` zone onto kuifje -- BLOCKED: Jelle does not have DNS access
   for `di-ax.be` yet. Nothing else can proceed until then.
2. Certificate for `auditexport.di-ax.be`, then `force_ssl=true` on domain 7.
3. Flip the hostname to `51.255.43.81` / `2001:41d0:305:2100::1:4b26`.

## Invulassistent staged, 2026-08-30

`invulassistent.wcag.be` (31,322 requests/quarter on phoenix), staged on
robbedoes WITHOUT a cutover. Unlike Auditexport this one is CUTOVER-READY: the
`wcag.be` zone is already ours on kuifje and its record is already at TTL 300.

### What it is

`/home/invulassistent/invulassistent` on phoenix (uid 4015, `node server.js`,
`environment: 'live'`, port 3000). `alchemymvc ~1.3.15` with the alchemy-acl/
chimera/form/i18n/media/menu/styleboost/widget family, `csv-parser`, and
`scaffold` from the PRIVATE GitHub repo `11ways/scaffold`. The app tree is
118 KB; the 725 MB on phoenix is `node_modules`. It has NO basic auth of its
own -- the 401 the audit recorded is the app's own login gate, so no access
list is needed.

### Rows on robbedoes

| Object | Id | Notes |
| --- | --- | --- |
| managed database `invulassistent-mongo` | 4 | `mongo:7`, db `invulassistent`, user `invulassistent`, host `local`; container `hohenheim-luguij0q-instance-7` |
| instance `invulassistent` | 8 | `hohenheim:workspace` on runtime image `node-12` (id 6), no git source, `start_command = node server.js`, `container_port 3000`, `console_kind tty`, `memory_limit_mb 1024`, `auto_deploy false`; container `hohenheim-luguij0q-instance-8`, volume `/opt/hohenheim/data/volumes/8/home`, uid 200008, published `127.0.0.1:32781->3000` |
| attachment `instance_databases` | 4 | instance 8 -> database 4, prefix `DB` (+ `DATABASE_URL`) |
| site `Invulassistent` | 5 | `hohenheim:instance`, instance 8 |
| domain | 8 | `invulassistent.wcag.be`, exact, `force_ssl=false` until a certificate exists |

### The dependency problem, and why phoenix's `node_modules` was copied

`npm ci` refuses: the committed `package-lock.json` (2022) predates its own
`package.json` (2026) -- the lock pins alchemy-media 0.6.1, alchemy-menu 0.6.1,
alchemy-styleboost 0.4.4 and alchemy-widget 0.1.4 against ranges `~0.7.5`,
`~0.6.4`, `~0.4.6` and `~0.2.8`. `npm install` then fails at
`scaffold: 11ways/scaffold`, which is a PRIVATE repository: npm resolves it as
`ssh://git@github.com/11ways/scaffold.git`, the runtime image ships no ssh
client, and rewriting the URL to https only gets `Password authentication is
not supported`. No credential for it exists here and none was invented.

So the Tomberg route was taken instead: phoenix's own `node_modules` (266
modules, 210 MB compressed) streamed phoenix -> workstation -> robbedoes with
no local copy, unpacked into the volume. That is more faithful than resolving
fresh, because it is the exact tree phoenix runs.

### A finding about phoenix, worth keeping

Phoenix's native modules in that tree are compiled for **NODE_MODULE_VERSION
93 (Node 16)** while the live phoenix process is
`/usr/local/n/versions/node/12.16.2/bin/node` (pid 30519, ABI 72). So
`mmmagic` and `canvas` ALREADY fail to load on phoenix and the app tolerates
it -- alchemy-media degrades. They were deliberately NOT rebuilt here: doing so
would make robbedoes behave differently from the site being replaced.
`bcrypt` loads (it is the one that matters for the login gate).

### Proof

Restore: 17 collections, 104,681 documents, `scanresults=103300`, `scans=206`,
`users=83`, dataSize 5089 MB / storageSize 937 MB compressed. `mongorestore`
ran from a helper `mongo:7` container on the database's own link network with
the dump bind-mounted read-only, so the 5 GB was never copied into the
container's writable layer.

Inside the container `node server.js` listens on `*:3000` as uid 200008.

| path | phoenix | robbedoes (direct) | robbedoes (proxy, Host header) |
| --- | --- | --- | --- |
| `/` | 401, 8950 bytes | 401, 8941 | 401, 8949 |
| `/login` | 200, 9104, `<title>Login \| Invulassistent</title>` | 200, 9095, same title | 200, 9103, same title |
| `/chimera` | 401, 8367 | 401, 8367 (exactly equal) | - |

A normalised diff of `/login` (hex-like runs and URLs collapsed) leaves exactly
TWO differing lines: the `hawkejs/static.js?i=` cache buster and the request
URL plus client user-agent inside `window._initHawkejs`. Everything else,
including the `nl` locale and every template, is identical.

`docker stats`: instance 8 99 MiB / 1 GiB, its Mongo 656 MiB / 1.25 GiB.

### What differs from phoenix

Node 12.22.12 instead of 12.16.2 (same major, same ABI 72); Mongo 7; the
database is reached by injected uri over the link network; TCP 3000 instead of
the hohenchild unix socket. `package-lock.json` is untouched (the failed npm
runs did not rewrite it, md5 `ca0122e7d8f3b9de1f45af576f867167`).

### Cutover steps remaining (in order)

1. Certificate for `invulassistent.wcag.be` on robbedoes (HTTP-01, right after
   the flip, the way Earl was done), then `force_ssl=true` on domain 8.
2. Flip `invulassistent` in kuifje zone 3 from `CNAME phoenix.develry.be` to
   A `51.255.43.81` + AAAA `2001:41d0:305:2100::1:4b26` (delete-then-add: a
   CNAME owner cannot hold other records). TTL is already 300.
3. Verify with `curl --resolve` against the NEW address, never through the
   resolver; watch phoenix's access log go quiet. Phoenix stays the rollback.

## Standing rule: copy phoenix's `node_modules`, never resolve fresh

Established 2026-08-30 after it bit two apps in a row. These apps are six to
ten years old. Their `package.json` ranges no longer resolve to the tree that
actually runs: Invulassistent's lock file predates its own manifest and its
`scaffold` dependency is a PRIVATE GitHub repo npm cannot reach, and Oogfonds
resolved to a plugin set that refuses to boot (`The alchemy-form plugin has to
be loaded AFTER alchemy-widget`). The tree on phoenix is the one serving
production today, so it is the only faithful input.

The lane, per app: `sudo tar --numeric-owner -czf` the `node_modules` on
phoenix (never `node_modules_old`), stream `ssh phoenix 'cat' | ssh robbedoes
'cat >'` from the workstation with NO local copy (the workstation disk is at
98%), unpack into the instance volume, `chown` to the instance uid. Verify by
module COUNT and size against phoenix -- NOT by comparing a re-tarred sha256,
because `tar czf` stamps the gzip header with the time and a second tar of the
same tree is a different byte stream.

Native modules survive the move: a tree built on phoenix's Ubuntu 16.04 loads
on the buster/bullseye images for the same Node major (buster's glibc is
newer). Measured this wave: Oogfonds' `mmmagic`, `bcrypt` and `canvas` all
loaded untouched, and NO package needed `npm rebuild`. Where a `.node` binary
does refuse, rebuild ONLY that package in a helper container; never reinstall
the tree.

## Vlaams Oogfonds - Staging staged, 2026-08-30

`oogfonds.clients.11ways.be` + `test.vlaamsoogfonds.be` (22,543 requests/quarter
on phoenix, all 401 -- it is behind basic auth), staged on robbedoes WITHOUT a
cutover.

### What it is

`/home/oogfonds/oogfonds-staging` on phoenix (uid 4006, Node 16.13.2,
`node server.js`, `environment: 'preview'`, port 3000). `alchemymvc ~1.2.1`
with the alchemy plugin family plus `nodemailer`. No lock file. 147 MB of
`files/` uploads, included. On phoenix it has `min_processes: 0`, so it was not
even running when the tarball was taken.

### Rows on robbedoes

| Object | Id | Notes |
| --- | --- | --- |
| managed database `oogfonds-staging-mongo` | 6 | `mongo:7`, db `oogfonds-staging`, user `oogfonds`, host `local`, **memory limit 512 MB set explicitly**; container `hohenheim-luguij0q-instance-10` |
| instance `oogfonds-staging` | 11 | `hohenheim:workspace` on runtime image `node-16` (id 5), `start_command = node server.js`, `container_port 3000`, `console_kind tty`, `memory_limit_mb 512`, `auto_deploy false`; container `hohenheim-luguij0q-instance-11`, volume `/opt/hohenheim/data/volumes/11/home`, uid 200011 |
| attachment `instance_databases` | 5 | instance 11 -> database 6, prefix `DB` (+ `DATABASE_URL`) |
| instance variables | - | `MAILER_PASSWORD` and `MICROCOPY_KEY`, both stored as SECRET (write-only) |
| access list `Oogfonds staging` | 1 | one `basic_auth` rule, satisfy `any`; attached SITE-WIDE on the site's Advanced -> Access list, which is the faithful mapping of phoenix's site-level `basic_auth` (a protected path would only cover a prefix) |
| site `Vlaams Oogfonds - Staging` | 6 | `hohenheim:instance`, instance 11 |
| domains | 9, 10 | `oogfonds.clients.11ways.be`, `test.vlaamsoogfonds.be`, exact, `force_ssl=false` |

### Secrets moved out of the source tree

`app/config/local.js` on phoenix carries an inline SMTP password
(`calamity.develry.be:587`, user `output@develry.be`) and a `microcopy_key`.
Both now come from instance variables: the file reads
`password : process.env.MAILER_PASSWORD` and
`microcopy_key: process.env.MICROCOPY_KEY`, and the values were transferred
without ever being printed -- read off the host into a 0600 file, piped into
`hoh vars instance 11 set ... --secret`, then the file was shredded.

**Not fixed, worth a decision:** `app/config/default.js:78` still contains a
different inline mailer password committed in the source tree. It is overridden
by `local.js` at runtime. It was left alone (out of this lane's scope), but it
is a credential sitting in a repository.

### Proof

Restore: 16 collections, 1418 documents.

Native modules from phoenix's tree, loaded untouched in the node-16 container:
`mmmagic ok`, `bcrypt ok`, `canvas ok`. `@11ways/exiv2` fails on
`libexiv2.so.14`, which the runtime image does not ship -- the same degradation
Microcopy and Auditexport have here.

| request | phoenix | robbedoes (proxy, Host header) |
| --- | --- | --- |
| `/` with no credentials | 401 | **401** |
| `/` with `test:secret` | 200, **111389 bytes** | 200, **111380 bytes** |
| `/login` with credentials | 200, 14043 bytes | 200, 14064 bytes (direct) |

Fetched directly from the container before the site existed, `/` was
**111389 bytes -- byte-for-byte equal to phoenix**. The 9-byte difference
through the proxy is the request URL and user-agent embedded in the rendered
`window._initHawkejs`, the same nonce family as the other apps.

`docker stats`: instance 11 113 MiB / 512 MiB, its Mongo 119 MiB / 512 MiB.

### Host memory budget, and what it forced

`hoh instance create` refused with `host_capacity_reached`: a managed database
reserves **1280 MB by default** and five of them had eaten the budget. The
database detail page is READ-ONLY after creation (only Delete is offered), so
an existing database's limit cannot be lowered -- database 5 was deleted while
still empty and recreated as database 6 with an explicit 512 MB limit through
the new-database form's Advanced section. Worth remembering: size a managed
database at creation, because it cannot be resized afterwards.

### Cutover steps remaining (in order)

1. The `11ways.be` zone onto kuifje (it is at afraid.org today, with a
   `* CNAME apex` wildcard, so it needs the FreeDNS web UI export) and
   `vlaamsoogfonds.be` for the second hostname.
2. Certificate, then `force_ssl=true` on domains 9 and 10.
3. Flip the hostnames to `51.255.43.81` / `2001:41d0:305:2100::1:4b26`.

## Udesign Live staged, 2026-08-30

`udesign.world` + `www.udesign.world` (47,131 + 23,454 requests/quarter on
phoenix), staged on robbedoes WITHOUT a cutover. The largest of the four: 748 MB
of `files/` uploads travel with the code.

### What it is

`/home/udesign/live` on phoenix (uid 4011, Node 16.13.2, `node server.js`,
`environment: 'live'`, port 3000). EVERY dependency is a GitHub repo
(`skerit/alchemy`, `skerit/alchemy-acl`, `skerit/alchemy-chimera`,
`skerit/alchemy-ajatar-theme`, ... plus `nodemailer`), which is why the
copy-phoenix's-`node_modules` rule above is not optional here: a fresh resolve
cannot even be attempted meaningfully.

### Rows on robbedoes

| Object | Id | Notes |
| --- | --- | --- |
| managed database `udesign-live-mongo` | 7 | `mongo:7`, db `udesign_live`, user `udesign`, memory limit 512 MB set at creation; container `hohenheim-luguij0q-instance-12` |
| instance `udesign-live` | 13 | `hohenheim:workspace` on runtime image `node-16` (id 5), `start_command = node server.js`, `container_port 3000`, `console_kind tty`, `memory_limit_mb 512`; container `hohenheim-luguij0q-instance-13`, volume `/opt/hohenheim/data/volumes/13/home` (1.1 GB incl. uploads), uid 200013 |
| attachment `instance_databases` | 6 | instance 13 -> database 7, prefix `DB` (+ `DATABASE_URL`) |
| site `Udesign Live` | 7 | `hohenheim:instance`, instance 13 |
| domains | 11, 12 | `udesign.world`, `www.udesign.world`, exact, `force_ssl=false` |

### Proof

Restore: 15 collections, 2183 documents. `node_modules` from phoenix: **288
modules on both sides**, no package needed rebuilding.

| request | phoenix | robbedoes |
| --- | --- | --- |
| `/` direct from the container | 200, **209127 bytes**, `<title>Udesign.world</title>` | 200, **209128 bytes**, same title |
| `/` through the proxy (`Host: udesign.world`) | - | 200, 209011 bytes |
| `/` through the proxy (`Host: www.udesign.world`) | - | 200, 208994 bytes |
| `/nl` | 404, 41 bytes | 404, 41 bytes |

A normalised diff (hex runs, URLs, hydration ids and whitespace collapsed)
leaves TWO differences: the `hawkejs/static.js` cache buster, and the
`window._initHawkejs` payload, which embeds the request URL. One line the raw
diff flagged -- the `arrow-green-floater2.svg` preload -- is **byte-identical**
and only sits at a different line number (24 vs 26): the preload block ordering
is non-deterministic across renders, which is a known SSR property and not a
difference in content.

`docker stats`: instance 13 78 MiB / 512 MiB, its Mongo 93 MiB / 512 MiB.

## Phoenix staging wave complete, 2026-08-30

Four apps staged in one wave, none cut over, no DNS value moved, no
certificate issued, `force_ssl=false` everywhere.

| app | instance | image | database | site | hostnames | proof |
| --- | --- | --- | --- | --- | --- | --- |
| WCAG-EM Auditexport | 6 | node-16 | 3 `diax_auditexport`, 10 collections | 4 | `auditexport.di-ax.be` | `/login` 8137 vs phoenix 8138; `/chimera` 401 exactly equal |
| Invulassistent | 8 | node-12 | 4 `invulassistent`, 17 collections / 103,300 scanresults / 5089 MB | 5 | `invulassistent.wcag.be` | `/login` 9103 vs 9104; `/chimera` 401 exactly equal |
| Vlaams Oogfonds - Staging | 11 | node-16 | 6 `oogfonds-staging`, 16 collections | 6 | `oogfonds.clients.11ways.be`, `test.vlaamsoogfonds.be` | 401 without credentials, 200 with; root 111389 bytes = phoenix exactly |
| Udesign Live | 13 | node-16 | 7 `udesign_live`, 15 collections | 7 | `udesign.world`, `www.udesign.world` | root 209128 vs 209127 |

Udesign Preview was deliberately EXCLUDED: it needs Node 10, and although
`hohenheim/node-10:1` is built on this box its seeded runtime-image row only
becomes visible after the next jar deploy.

Disk on robbedoes: 16 GB -> 21 GB of 99 GB (the 5 GB Invulassistent dump was
extracted and deleted again; peak was 23 GB). Memory: 3.1 GB used of 11.7 GB,
every container far below its cap -- the largest is Invulassistent's Mongo at
656 MiB.

Ready to cut over as soon as their zones are ours: **Invulassistent only**
(`wcag.be` is already delegated to kuifje and its record is at TTL 300). The
other three wait on `di-ax.be`, `11ways.be`/`vlaamsoogfonds.be` and
`udesign.world` respectively -- and `udesign.world` is at COMBELL with live
Microsoft 365 mail, so that zone needs its real export before anything moves.

## Invulassistent LIVE (second hostname cutover), 2026-08-30

`invulassistent.wcag.be` now answers from robbedoes (instance 8, database 4,
site 5, domain 8). Phoenix keeps serving the same site untouched and remains the
rollback.

### Order of operations

The certificate was requested BEFORE `force_ssl` was turned on, deliberately:
with `force_ssl` on and no certificate, port 80 redirects to a 443 that has no
certificate for the hostname, which takes the site down. Earl tolerated the
other order because it was flipped and certified within the same minute; the
safe general order is DNS -> certificate -> force_ssl.

1. Phoenix baseline captured FIRST, over `--resolve ...:443:144.76.30.204`:
   `/` 401 / 8950 / sha256 `1881b5db...`, `/login` 200 / 9104 / `119ca912...`,
   `/chimera` 401 / 8367 / `b9168d74...`.
2. kuifje zone 3: record 28 `invulassistent CNAME phoenix.develry.be` (TTL 300)
   DELETED, then `invulassistent A 51.255.43.81` and `invulassistent AAAA
   2001:41d0:305:2100::1:4b26` added, both TTL 300 (delete-then-add: a CNAME
   owner cannot hold any other record). Serial 12 -> 15, robbedoes transferred
   within seconds. `earl` and every other row untouched.
3. `compare wcag.be --old 137.74.171.228 --new 51.255.43.81 --strict --names
   @,www,api,earl,invulassistent` -> **IDENTICAL**, all 11 questions, apex NS and
   SOA included, both sides serial 15.
4. `propagate invulassistent.wcag.be A --expect 51.255.43.81` -> **PROPAGATED**
   on 1.1.1.1 / 8.8.8.8 / 9.9.9.9 in round 1.
5. Certificate 2 `Invulassistent` requested ONCE from
   `/admin/certificates-request?site=5` (HTTP-01; the form is prefilled by
   `?site=5` with the name and the hostname). Journal: `ACME: account ready
   (global, production)` 02:43:44Z, `ACME: certificate issued for
   invulassistent.wcag.be` 02:43:52Z, `CertificateStore: loaded 2 certificates,
   2 hostname mappings`. Issuer Let's Encrypt **YR2**, valid 2026-08-30
   01:45:20Z to 2026-11-28 01:45:19Z.
6. `force_ssl` switched on for domain 8 and saved. The domain's Certificate
   field is deliberately left blank, exactly as Earl's is: the store maps by
   hostname, and HTTPS already verified before the field was ever touched.

### Verification, byte-level

    curl --resolve invulassistent.wcag.be:443:51.255.43.81
      /        -> 401, 8950 bytes, ssl_verify_result 0
      /login   -> 200, 9104 bytes, ssl_verify_result 0
      /chimera -> 401, 8367 bytes, ssl_verify_result 0
    all three EXACTLY the byte size phoenix served, and a diff against the
    captured baseline leaves exactly ONE differing line per page:
      < <script src="/hawkejs/static.js?v=0.2.0&i=99211c">   (phoenix)
      > <script src="/hawkejs/static.js?v=0.2.0&i=01db5a">   (robbedoes)
    i.e. the per-process hawkejs cache buster. Nothing else differs.

    http://invulassistent.wcag.be/  -> 301 https://invulassistent.wcag.be/
    through PUBLIC DNS, no --resolve -> 200, remote_ip 51.255.43.81 (the real
      path, not a pinned probe)
    openssl s_client -servername invulassistent.wcag.be
      -> subject CN=invulassistent.wcag.be, issuer Let's Encrypt YR2
    IPv6: --resolve ...:443:[2001:41d0:305:2100::1:4b26] -> 200, 9104, ssl 0
    listeners `*:80` and `*:443` (one dual-stack socket each)
    in-container: `tini -- bash -lc node server.js`; direct
      `http://127.0.0.1:32781/login` -> 200, 9095 bytes

### TRAP: phoenix is not a valid vantage point yet

An IPv6 probe run FROM phoenix answered `remote_ip 2a01:4f8:191:21cb::2` --
phoenix's own address -- and `getent ahostsv6` there still returned canonical
name `phoenix.develry.be`. Phoenix's resolver had cached the CNAME while it was
still TTL **7200**, before the TTL-lowering pass, so it keeps answering the old
chain for up to two hours no matter what the record says now. Nothing was wrong
with the cutover. Phoenix's `curl` is 7.47.0 (2016) and supports neither
bracketed IPv6 `--resolve` nor `--connect-to`, so it silently fell through to
normal DNS instead of failing loudly. Lower a TTL well BEFORE the day of a
cutover, and never use a box whose resolver is warm on the old value to verify
the new one.

### Rollback (not needed)

Delete the two address records and re-add `invulassistent CNAME
phoenix.develry.be.` (TTL 300), and switch `force_ssl` back off on domain 8.
Phoenix still runs the app (pid 30519) and keeps its own certificate.
