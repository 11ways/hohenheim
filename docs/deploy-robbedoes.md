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

Every jar swap on this box is ONE invocation of
`tools/deploy-host.sh robbedoes <jar>`, whose numbered lane is documented once
in `deploy-starfleet.md` ("Deploy procedure"). It reads this host out of the
`deployments` entry above, and because the ssh identity is `debian@` rather
than `root@` it runs the whole lane through `sudo -n` by itself -- the
hand-typed transcription the deploy entries below describe is retired. It
refuses a jar whose build stamp is DIRTY or unstamped, a
`--rehearse-migrations` run that does not succeed against a byte copy, and a
`/api/health` probe that never turns green (it then prints the journal lines
and the exact rollback commands and stops, rather than rolling back on its
own). The second restart is mandatory and has no skip flag.
`--rollback robbedoes --preflight <dir>` swaps `rollback.jar` back in;
restoring the database stays a deliberate manual step, because a migration
applied by the newer jar makes the older one refuse to boot.

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

## Taverne Tomberg LIVE (third hostname cutover), 2026-08-30

`tavernetomberg.be` and `www.tavernetomberg.be` now answer from robbedoes
(instance 3, database 1, site 2, domains 3 and 4). This is the first cutover of
a zone that carries live mail, and the first of an apex.

### The media gate caught a real defect: no GraphicsMagick in the runtime images

The pre-cutover gate was to prove the site's uploaded media serves from
robbedoes. It did not: EVERY `/media/image/...` request returned **500**, 212
bytes, while the HTML rendered perfectly.

    Error: Could not execute GraphicsMagick/ImageMagick: gm "identify" "-ping"
    "-format" "%wx%h" "/home/site/files/2017/02/5898f3449f3b0c780fc9112e"
    this most likely means the gm/convert binaries can't be found

alchemy-media shells out to `gm` for every derivative. Phoenix carries
GraphicsMagick 1.3.23 and ImageMagick system-wide, so the apps never declared
the dependency and nothing in the migration surfaced it -- the HTML is
byte-identical either way, and only an actual image fetch shows the failure.
**Udesign Live was measured broken the same way** (`/media/image/...` 500), so
this was never Tomberg-specific.

Fixed in the images, not in the app: `graphicsmagick imagemagick` added to the
apt line of `images/node-12`, `images/node-16` and `images/node-10`, all three
rebuilt on robbedoes. Versions in the images: gm 1.3.35 (node-12, node-10),
gm 1.4 snapshot (node-16), plus `convert` in each.

The `files/` tree was NOT the problem and was never missing: it is 27 MB with a
single `2017/` directory on BOTH phoenix and robbedoes. An earlier note that
Tomberg carries "4.7 GB of uploads" was wrong -- that figure is `node_modules`
(4.1 GB) plus `temp/` (492 MB), neither of which is site data.

**A `hoh power <id> restart` RECREATES the container**, so it picks up a rebuilt
image: instance 3's image id moved `0c94252f...` -> `d1533013...` across the
restart and `gm` appeared inside it. That is how a runtime-image fix reaches a
running workload; no redeploy is needed.

After the rebuild, all four sampled derivatives are **byte-identical to
phoenix**, same sha256, across two different GraphicsMagick versions:

    5898f37e9f3b0c780fc91145  phoenix 57141/200  robbedoes 57141/200  IDENTICAL
    5898f3899f3b0c780fc91148  phoenix 22991/200  robbedoes 22991/200  IDENTICAL
    5898f3919f3b0c780fc9114c  phoenix 21544/200  robbedoes 21544/200  IDENTICAL
    5898f3989f3b0c780fc91150  phoenix 17635/200  robbedoes 17635/200  IDENTICAL

### The cutover

1. Baseline captured from phoenix for both hostnames x `/`, `/menu`, `/contact`
   (145413 / 461711 / 142557 bytes on the apex; the `www` twin is 4 bytes larger
   on each, its own hostname being longer).
2. kuifje zone 5, two plain VALUE edits through the record forms (no
   delete-then-add: neither owner is a CNAME): apex `A 144.76.30.204` ->
   `51.255.43.81`, apex `AAAA 2a01:4f8:191:21cb::2` ->
   `2001:41d0:305:2100::1:4b26`, TTL 300 unchanged on both. `www` was left
   alone: it is a CNAME to the apex and follows automatically. Serial 9 -> 11.
3. **The MX, the three SRV rows and the SPF TXT were not touched**, and were
   re-verified identical on both nameservers after the change: `MX 10
   calamity.develry.be`, `_autodiscover._tcp 0 100 443`, `_imaps._tcp 0 100
   993`, `_submission._tcp 0 100 587`, `"v=spf1 +a +mx ?all"`. Mail is intact.
   Note the SPF's `+a` now authorises robbedoes instead of phoenix, which is
   correct and follows the app automatically -- the `+mx` term still covers
   calamity, which is what actually sends.
4. `compare --strict` -> **IDENTICAL** on all 10 questions, both sides serial 11.
   `propagate tavernetomberg.be A --expect 51.255.43.81` -> **PROPAGATED** on
   1.1.1.1 / 8.8.8.8 / 9.9.9.9 by round 4.
5. Certificate 3 `Taverne Tomberg` requested ONCE (HTTP-01, both hostnames
   prefilled from `?site=2`). Journal `ACME: certificate issued for
   tavernetomberg.be, www.tavernetomberg.be` 02:58:38Z; `CertificateStore:
   loaded 3 certificates, 4 hostname mappings`. Issuer Let's Encrypt YR1, SAN
   `DNS:tavernetomberg.be, DNS:www.tavernetomberg.be`, valid to 2026-11-28
   02:00:05Z. `force_ssl` switched on for domains 3 and 4 only AFTER issuance.

### Verification

    https://tavernetomberg.be/         200, 145413 bytes, ssl_verify_result 0
    https://www.tavernetomberg.be/     200, 145417 bytes, ssl 0
    /menu, /contact                    200 on both hostnames
    http://  -> 301 https:// on both
    media over https                   200, 57141 bytes
    from robbedoes through PUBLIC DNS  200 on both, remote_ip
                                       2001:41d0:305:2100::1:4b26 (IPv6 path)

Content equality: with the render nonces normalised (`eval_<ms>` hawkejs
evaluation ids and the `hawkejs/static.js?i=` cache buster) **five of the six
captures are byte-identical to phoenix, and the sixth resolves to a cold-render
artifact**: the apex `/menu` fetched seconds after the container restart
differed, while a second fetch of the same URL matched phoenix exactly (phoenix
`/menu` vs robbedoes `/menu`, both warm: 0 differing lines). Byte sizes were
identical throughout. Take a comparison capture only after the first request has
warmed a freshly restarted instance.

### TRAP repeated: two stale vantage points

Neither this workstation nor phoenix could confirm the flip through normal DNS
afterwards -- both kept answering 144.76.30.204 long past the 300 s TTL, because
they had cached the record while it was still TTL **7200**, before the
TTL-lowering pass an hour earlier. The public resolvers and robbedoes itself
answer robbedoes. Lower a TTL a day ahead, not an hour, and verify from a
resolver that was never warm on the old value.

### Rollback (not needed)

Set the apex `A` back to `144.76.30.204` and `AAAA` to `2a01:4f8:191:21cb::2`,
and switch `force_ssl` off on domains 3 and 4. Phoenix still runs the app and
keeps its own certificate; it served the site correctly throughout.

### Still to do for the other staged apps

Udesign Live, Oogfonds Staging and Auditexport run on `node-16`, whose image now
has `gm` -- but their containers are still on the pre-fix image. One
`hoh power <id> restart` each fixes their media. They were deliberately NOT
restarted here: their zones are not ours yet, so they carry no traffic, and
touching them was outside this lane.

## Staged apps recreated on the GraphicsMagick images, 2026-08-30

The three staged apps left behind by the Tomberg cutover -- plus Microcopy,
which turned out to be in the same state -- were recreated so their containers
run the rebuilt runtime images that carry `gm`. None of them carries public
traffic: their zones are not delegated to us. `earl.wcag.be`,
`invulassistent.wcag.be` and `tavernetomberg.be` were not touched.

### Image ids before and after

`hoh power <id> restart` recreates the container, so the image id must move.
It did, on all four:

    id  instance          before        after         image
    --  ----------------  ------------  ------------  -----------------
    2   microcopy         eec8a2ad45e9  8646597d5f54  hohenheim/node-16:1
    6   auditexport       eec8a2ad45e9  8646597d5f54  hohenheim/node-16:1
    11  oogfonds-staging  eec8a2ad45e9  8646597d5f54  hohenheim/node-16:1
    13  udesign-live      eec8a2ad45e9  8646597d5f54  hohenheim/node-16:1

All four now answer `gm version` -> `GraphicsMagick 1.4 snapshot-20210721 Q16`;
before the restart `/usr/bin/gm` and `/usr/bin/convert` were both absent.

### Media proof

Before the restart, every derivative (a `/media/image/...` with resize
parameters) returned **500, 212 bytes, text/plain**; the unresized original
returned 200, because alchemy-media only shells out to `gm` when it has to
produce a derivative. After:

    udesign  /media/image/5d89fee8e7acbf36becddbd8?width=400   200  43829  image/jpeg  JPEG 400x533
    udesign  /media/image/5d89fee8e7acbf36becddbd8 (original)  200 302044  image/jpeg  JPEG 1224x1632
    oogfonds /media/image/5c2f5592b37372369f8f0753?width=720   200  47739  JPEG 720x480
    oogfonds /media/static/logo-vlaamsoogfonds.png?width=200   200  10238  PNG 199x73
    oogfonds /media/image/5cd55ccabb8f1c4cc2ac2773?w=40&h=40   200    860  JPEG 40x40

**Udesign's own pages reference no resized derivative at all** -- every
`/media/image/...` in its markup, stylesheet and script is the unresized
original, which answered 200 before the restart too. Its resize lane was
nonetheless broken and is now proven working with `?width=400` on an image the
homepage does reference. Oogfonds is where the defect was actually visible: its
markup carries `?width=720`, `?width=1500&height=680` and a 40x40 avatar thumb.

**Auditexport has an empty media library** (`db.media_files.countDocuments({})`
= 0 in `diax_auditexport`), so no real derivative URL exists to fetch. Its
resize path is proven instead by the media route itself: a request for a
nonexistent id with `?width=100` returns alchemy-media's placeholder **scaled
to 100px** (200, 1382 bytes, PNG), which only a working `gm` can produce.
Microcopy answers that same probe identically.

### Against phoenix

Same derivative, fetched from phoenix (144.76.30.204) over https, sha256:

    udesign  ?width=400                       IDENTICAL (43829 bytes both)
    oogfonds ?width=720                       IDENTICAL (47739 bytes both)
    oogfonds /media/static/...png?width=200   DIFFERENT (robbedoes 10238, phoenix 8403)

The two `/media/image/` derivatives are byte-identical. The `/media/static/`
PNG differs in size and hash; both are valid 199x73 PNGs. Recorded as a fact,
not a verdict -- a different GraphicsMagick build can legitimately choose
different PNG encoding parameters, and this is the static-file resize path
rather than the media-library one.

### Pages still answer

    udesign.world  / /aanbod /ambassadeurs /vragen-en-contact   200
    auditexport.di-ax.be  /  200 (empty body by design)  /login  200
    oogfonds.clients.11ways.be  /  200
    microcopy.elevenways.be  /  200
    earl.wcag.be 200, invulassistent.wcag.be 401 (its basic auth),
    tavernetomberg.be 200, www.tavernetomberg.be 200 -- all unchanged

### DEFECT FOUND: invulassistent is still on a pre-fix image

`invulassistent` (instance 8) runs `hohenheim/node-12:1` image id
`0c94252f7f05`, created 02:21:06Z -- but node-12 was rebuilt with `gm` at
02:51:32Z and is now `d1533013b76a`. The container has no `/usr/bin/gm`. It was
restarted BEFORE the image rebuild, so the earlier note that it is on the fixed
image is wrong. It was deliberately NOT restarted here: it carries live traffic
and that was outside this lane. It needs one `hoh power 8 restart` in a lane
that owns live traffic. Taverne Tomberg (instance 3) IS correctly on the
rebuilt `hohenheim/node-12:1` `d1533013b76a` and has `gm`.

## Invulassistent recreated on the GraphicsMagick image, 2026-08-30

The one instance the previous section left on a pre-`gm` image. `hoh power 8
restart` recreates the container, which is the mechanism that picks up a
rebuilt tag. This lane owned live traffic, so both sides of the restart were
measured.

### Image and `gm`

| | before | after |
| --- | --- | --- |
| container image | `0c94252f7f05` (built 02:21:06Z) | `d1533013b76a` (built 02:51:32Z) |
| `/usr/bin/gm` | absent (`command -v gm` -> nothing) | present, `GraphicsMagick 1.3.35 2020-02-23 Q16` |

`hohenheim/node-12:1` resolves to `d1533013b76a`, so the container is now on the
current tag rather than a dangling copy of the old one.

### Measured downtime

Polled `https://invulassistent.wcag.be/login` every ~0.37s across the restart
(`--resolve` to 51.255.43.81, no proxy):

    03:18:28.284  200 9104   <- last good
    03:18:28.666  503        <- restart issued 03:18:28.554
    ...           503        (12 consecutive, 4 of them the 38-byte body)
    03:18:32.663  503        <- last bad
    03:18:33.033  200 9104   <- back

**Unavailable 4.0 seconds** (first 503 to last 503); the outage cannot have
been longer than 4.75s (last 200 to first 200). Every failed request was a
503 from the hohenheim proxy, not a timeout.

### Site answers, before vs after

| path | before | after |
| --- | --- | --- |
| `/` | 401, 8950 bytes | 401, 8950 bytes |
| `/login` | 200, 9104 bytes | 200, 9104 bytes |
| `/chimera` | 401, 8367 bytes | 401, 8367 bytes |

`ssl_verify_result 0` on all three; `http://invulassistent.wcag.be/` still
answers `301 -> https://invulassistent.wcag.be/`.

The sha256 of each body CHANGED at identical byte length. That is the app's
per-boot asset id: the markup carries `?v=0.2.0&i=3d002c`, a fixed-width hex
stamp that moves with the process. Two consecutive fetches after the restart
are byte-identical, so nothing else moved.

### Media proof: the placeholder probe, and why it does not answer here

`db.media_files.countDocuments({})` in `invulassistent` is **0**, so there is no
real derivative URL to fetch and the substitute probe applies. It does NOT
answer on this app, before or after the restart:

    before  /media/image/000000000000000000000000?width=100  504 after 30s (proxy timeout)
    after   /media/image/000000000000000000000000?width=100  504 after 30s (proxy timeout)

Hitting the container directly (`127.0.0.1:32795`) hangs past 60s and logs
nothing, with OR without `?width`, so it is not the resize step. The cause is
visible on `/media/placeholder`, which fails fast:

    500: Error: The module '/home/site/node_modules/canvas/build/Release/canvas.node'
    was compiled against a different Node.js version using
    NODE_MODULE_VERSION 93. This version of Node.js requires
    NODE_MODULE_VERSION 72.

`canvas` in this app's `node_modules` was built for Node 16; the container runs
Node 12.22.12. alchemy-media's placeholder generator needs `canvas`, so the
missing-record path throws (and, for `/media/image/...`, hangs instead of
answering). **Pre-existing, unrelated to GraphicsMagick, unchanged by this
restart** -- the probe returned the same 504 at the same 30s before it.

`gm` itself is proven working inside the container, which is what this restart
was for:

    # docker exec ... gm convert -size 320x200 gradient:blue-red /tmp/gmtest.jpg
    /tmp/gmtest.jpg    JPEG 320x200+0+0 DirectClass 8-bit 1.5Ki
    # docker exec ... gm convert /tmp/gmtest.jpg -resize 100x /tmp/gmtest100.jpg
    /tmp/gmtest100.jpg JPEG 100x63+0+0 DirectClass 8-bit 466

Sibling apps on the same host answer the placeholder probe normally, so the
probe method is sound and the defect is app-local: microcopy (instance 2) and
auditexport (instance 6) both return `200, 1382 bytes, PNG 100x300`.

Note for the record: instance 8's Mongo is **instance 7**
(`DB_HOST=hohenheim-luguij0q-instance-7`, database `invulassistent`), not
instance 4 -- instance 4 is microcopy's Mongo.

### Open item

`invulassistent`'s `canvas` native module is built for the wrong Node ABI. It
only surfaces on media paths, and the media library is empty, so nothing a
visitor sees is affected today. Fixing it means rebuilding `canvas` against
Node 12 in the app volume, or dropping the dependency -- deliberately not done
in this lane.

## Deploy 2026-08-30 (second jar swap): 0782eb8b, no migration, node-10 row live

Swapped the same jar starfleet and kuifje took -- sha256
`b42c0ff25379876ea6f12d186b67402c9a8d5bfc7f5d3dd57d5c595173be4bbd`,
267,615,488 bytes, stamp 13/13 `dirty=false`, no rebuild, uploaded behind the
remote `grep -c false | grep -qx 13` gate. Preflight
`/root/hohenheim-preflight-20260830-fourteenth/`: `.pre` and `.at-swap` both
`integrity_check` ok, `settings/`, keyring sha256 EQUAL at `cd3a6ca0...`,
`hohenheim-server.jar.rollback` = `605f5303...` (`e6d15bf1`).

Migration diff NONE (top M007, pin 007); `--run-migrations` from an explicit
`cd /opt/hohenheim` reported `0 applied`.

Stop 03:45:58.5Z, start 03:46:01.6Z, healthy 03:46:10.6Z -- **12 s downtime**,
the shortest of the three. Second restart 03:47:14.9Z, healthy 03:47:21.6Z
(7 s). Both: 0 real journal errors, RSS 375 MB, listeners 53/80/443 plus 3000
on loopback, roles `[databases, dns, firewall, instances, proxy]`,
`CertificateStore: loaded 3 certificates, 4 hostname mappings`,
`SiteDispatcher: loaded 10 exact routes`. Row counts identical across the swap:
7 sites, 10 domains, 4 certificates, 13 instances, 6 managed databases. **All
12 containers kept running with unchanged uptimes**, and
`/opt/hohenheim/staging/phoenix/` is untouched at 1.6 GB / 12 files.

THE POINT OF THE WAVE: `runtime_images` now carries row 7 `node-10` (the
`7d718e15` seed row, applied by the seeder on boot), and
`hohenheim/node-10:1` is on the box as `3e74aa99cbf9` (789 MB -- the id moved
from the original build because `99b57bcf` rebuilt it with GraphicsMagick).
Udesign Preview is therefore unblocked. Nothing was staged in this lane.

THE FOUR LIVE HOSTNAMES, re-verified after the second restart with `--resolve`
against 51.255.43.81 (a plain curl hits a cached answer and lies):

    earl.wcag.be            200  1024 bytes  sha256 c994a6d8...  byte-IDENTICAL to the pre-deploy capture
    invulassistent.wcag.be  /  401/8950   /login 200/9104   /chimera 401/8367   (exact match)
    tavernetomberg.be       200  145413 bytes
    www.tavernetomberg.be   200  145417 bytes

all `ssl_verify_result 0`, all 301 from HTTP, and both Tomberg names also 200
through public DNS with `remote_ip 51.255.43.81`. Certificates unchanged:
`CN=earl.wcag.be` and `CN=invulassistent.wcag.be` and `CN=tavernetomberg.be`,
all `notAfter Nov 28 2026`.

Tomberg's page hash moves across a restart while its byte LENGTH does not, and
that is not a content change: a normalised diff leaves exactly ONE differing
line, the `window._initHawkejs` payload, whose `eval_<epoch-ms>` template names
carry the render timestamp (`eval_1788061414498` -> `eval_1788061587110`).
Media is healthy on the new jar -- real derivatives
`/media/image/5898f37e9f3b0c780fc91145?width=50%&dpr=1|2` and
`/media/image/5898f3899f3b0c780fc91148?width=50%&dpr=1` all return 200 JPEGs,
not the 500s that the pre-GraphicsMagick images produced.

## Udesign Preview BLOCKED on the host memory budget, 2026-08-30

`staging.udesign.world`, the fifth and last phoenix Node app, could NOT be
staged. Its inputs are ready and verified; the wall is the host's admission
budget, and clearing it means re-sizing workloads this lane was told not to
touch. Nothing was left half-built: the failed database record was deleted and
the host is back to exactly the twelve workloads it carried before.

### The wall, verbatim

The database was created through the form (name `udesign-preview-mongo`, engine
MongoDB, db `udesign_preview`, user `udesign`, image `mongo:7`, memory limit
**512 MB set explicitly at creation**, the sibling shape). It saved as record 8
and then failed to provision:

    Database 'udesign-preview-mongo' could not be deployed: 1 violation(s):
      -> host_capacity_reached {name=local, needed=512, free=163}

Host `local`, from its Overview: **Booked 10752 MB of a 10915 MB budget (98%)**,
so 163 MB free. `mem_total` is 12250845184 (11683 MB); the budget is that minus
the reserve. The record was then deleted (`udesign-preview-mongo has been
deleted.`) and the page re-read: booked back to 10752 MB, twelve workloads, no
orphan.

### Why the budget is full: bookings, not use

Booked is the sum of DECLARED limits. Measured against `docker stats` at the
same moment, the twelve containers were using **~2.1 GB in total** and the host
had 8.4 GB available. The four databases created before the explicit-sizing
rule each book 1280 MB (the default) while using a tenth of it:

| workload | booked | actually using |
| --- | --- | --- |
| db-tomberg-mongo (instance 1) | 1280 MB | 143 MiB |
| db-microcopy-mongo (instance 4) | 1280 MB | 124 MiB |
| db-auditexport-mongo (instance 5) | 1280 MB | 187 MiB |
| db-invulassistent-mongo (instance 7) | 1280 MB | **681 MiB** |
| taverne-tomberg / auditexport / invulassistent | 1024 MB each | 148 / 174 / 109 MiB |
| microcopy, oogfonds x2, udesign-live x2 | 512 MB each | 88-128 MiB |

Only Invulassistent's Mongo (the 5 GB dataset) is anywhere near its cap. The
other three 1280 MB bookings are pure default.

### The remedy, and why this lane did not apply it

Udesign Preview needs 512 MB for its database plus 512 MB for its instance.
Re-sizing `microcopy-mongo` and `auditexport-mongo` from 1280 MB to 512 MB
frees 1536 MB, which is enough with headroom -- and that is now a supported
in-place edit (`b73e35a1`, deployed in the fourteenth wave), not a
delete-and-recreate. Both apps are STAGED, carry no traffic and hold no live
hostname, so the container recreate each edit performs is cheap.

It was not done here because this lane's brief forbids touching the other
staged instances. It is a one-line authorisation for whoever owns them, and
after it the remaining work is the sibling recipe end to end.

### What IS ready

`node_modules` copied from phoenix per the standing rule (every Udesign
dependency is a GitHub repo, so a fresh resolve is not even meaningful):
`/opt/hohenheim/staging/phoenix/udesign-preview-node_modules.tgz`, 59,025,040
bytes, streamed phoenix -> workstation -> robbedoes with no local copy. Verified
by MEMBER COUNT and CONTENT BYTES rather than a re-tarred sha256 (a second
`tar czf` of the same tree is a different byte stream):

    phoenix   find node_modules | wc -l        19375     file bytes 212255890
    robbedoes tar tzvf ... | wc -l             19375     file bytes 212255890

Exact match on both. Top-level entries 411 on phoenix (`ls -A`, `.bin`
included), 411 in the archive. The `/tmp` artefact on phoenix was deleted.

Phoenix baselines for the eventual proof, captured today through
`--resolve staging.udesign.world:443:144.76.30.204`:

| request | result |
| --- | --- |
| `/` no credentials | 401, 12 bytes |
| `/` with `preview:preview` | 200, **167576 bytes**, `<title>Udesign.world</title>`, sha256 `21bee53f01c0257c…` |
| `/media/image/5d89fee8e7acbf36becddbd8?width=400` | 200, 43829 bytes, JPEG 400x533, sha256 `e668f9e1f9f9e738…` |

That derivative hash is the SAME one Udesign Live serves from robbedoes, so the
media proof has a known-good target. The code and dump tarballs
(`phoenix-udesign-preview.tgz` 54,417,951 bytes, `phoenix-mongo-udesign-preview.tgz`
53,088 bytes, 11 collections) were already on the box and were not touched.

### Facts confirmed in passing

The node-10 seed row IS live (runtime image row 7) and `hohenheim/node-10:1` is
on the box, so nothing about Node 10 blocks this app. Disk on robbedoes: 28 GB
of 99 GB before and after this lane (the `node_modules` tarball added 59 MB).

## Two databases resized in place, and Udesign Preview staged, 2026-08-30

The blocked lane above was unblocked by an explicit authorisation to re-size two
STAGED databases. This is the **first production use of the in-place resize**
(`b73e35a1`, deployed in the fourteenth wave), and it did exactly what it says.

### The resize

`microcopy-mongo` (database 2) and `auditexport-mongo` (database 3), both
1280 MB by DEFAULT -- the edit form showed **blank** memory and CPU fields, so
the 1280 was never a stored value, it is what an UNDECLARED limit books. Set to
512 MB each.

The form froze everything a container's identity depends on -- name, engine,
database name, user, password, host, status are rendered as plain text, only
Memory limit and CPU limit are editable, and Delete is disabled with
`Database 'microcopy-mongo' is attached to microcopy (...). Detach it on each
instance's Databases tab first.` Save answered
`Your changes to microcopy-mongo have been saved.` and the status went to
**Provisioning**, so the recreate is scheduled after the commit rather than
inside it.

| database | container before | container after | cap before | cap after |
| --- | --- | --- | --- | --- |
| microcopy-mongo | instance-4, created 2026-08-29 23:45:50 | instance-4, created 2026-08-30 03:59:50 | 1.25 GiB | **512 MiB** |
| auditexport-mongo | instance-5, created 2026-08-30 01:56:37 | instance-5, created 2026-08-30 04:00:06 | 1.25 GiB | **512 MiB** |

**The engine container IS recreated** (both creation timestamps moved) and the
data survived it -- the volume is keyed to the record's name, not the container.
Verified collection by collection, before and after:

    11ways_microcopy   collections=7  docs=620  (acl_groups=3, acl_persistent_cookies=22,
                       acl_rules=2, media_files=0, menus=0, microcopies=589, users=4)
    diax_auditexport   collections=10 docs=265  (acl_groups=3, acl_persistent_cookies=19,
                       acl_rules=2, media_files=0, menus=0, report_languages=2,
                       report_types=2, reports=221, themes=12, users=4)

Identical on both sides. (Note for the record: the brief's "10 collections /
265 docs" describes auditexport; microcopy is 7 collections / 620 docs.) Both
apps still answer 200 through the proxy afterwards.

Booked memory on host `local`: **10752 MB -> 9216 MB**, a drop of exactly
1536 MB, 98% -> 84%.

### Udesign Preview

`staging.udesign.world`, the fifth and last phoenix Node app.

| Object | Id | Notes |
| --- | --- | --- |
| managed database `udesign-preview-mongo` | 10 | **`mongo:4.4`**, db `udesign_preview`, user `udesign`, memory 512 MB at creation; container `hohenheim-luguij0q-instance-16` |
| instance `udesign-preview` | 15 | `hohenheim:workspace` on runtime image **node-10** (id 7), `start_command = node server.js`, `container_port 3000`, `console_kind tty`, `memory_limit_mb 512`; container `hohenheim-luguij0q-instance-15`, volume `/opt/hohenheim/data/volumes/15/home`, uid 200015 |
| attachment `instance_databases` | 8 | instance 15 -> database 10, prefix `DB` (+ `DATABASE_URL`) |
| instance variable | - | `NODE_ENV=production` (plain) -- see below |
| access list `Udesign preview` | 2 | one `basic_auth` rule (`preview`), satisfy `any`, attached SITE-WIDE on the site's Advanced -> Access list, the Oogfonds precedent |
| site `Udesign Preview` | 8 | `hohenheim:instance`, instance 15 |
| domain | 13 | `staging.udesign.world`, exact, **`force_ssl=false`** |

`node_modules` came from phoenix per the standing rule (every Udesign dependency
is a GitHub repo). Verified by member count and content bytes, not a re-tarred
sha256: **19375 members / 212255890 file bytes on both sides**, 411 top-level
entries. No package needed `npm rebuild`.

### MongoDB 7 CANNOT serve this app -- use 4.4

The first attempt built the database on `mongo:7`, the sibling default, and the
app would not boot:

    MongoError: Unsupported OP_QUERY command: count. The client driver may
    require an upgrade. code: 352, codeName: UnsupportedOpQueryCommand
      at /home/site/node_modules/mongodb-core/lib/connection/pool.js:581:63

This app's 2020-era `mongodb-core` speaks the legacy OP_QUERY wire protocol,
which MongoDB removed. Udesign LIVE (node-16, newer alchemy) is fine on
`mongo:7`; this one is not. **The container image is frozen after creation** --
only memory and CPU are editable -- so the database had to be detached, deleted
and recreated on `mongo:4.4` (v4.4.30, the last release still serving OP_QUERY).
Restore then read back **11 collections / 464 documents**. Pin `mongo:4.4` for
any app of this vintage.

### `NODE_ENV=production` is NOT set by default, and phoenix sets it

Old Hohenheim passes `NODE_ENV=production` to every child (read off the live
phoenix process). A Hohenheim workspace instance passes none, so alchemy renders
in development mode. Setting it on instance 15 cut the page from 241392 to
223985 bytes. **Every other migrated app on this box is missing it too**
(instances 2, 3, 6, 11, 13 all report `NODE_ENV` unset) -- not fixed here,
because they were out of this lane's scope, but they should get it.

### Proof against phoenix

| request | phoenix | robbedoes (proxy, `Host:` + `preview:preview`) |
| --- | --- | --- |
| `/` no credentials | 401, **12 bytes** | 401, **12 bytes** |
| `/` with credentials | 200, `<title>Udesign.world</title>` | 200, same title |
| `/media/image/5d89fee8e7acbf36becddbd8?width=400` | 200, 43829 bytes, JPEG 400x533, sha256 `e668f9e1f9f9e738...` | 200, 43829 bytes, JPEG 400x533, sha256 **`e668f9e1f9f9e738...`** |

The media derivative is **byte-identical** to phoenix, which also proves the
GraphicsMagick lane end to end on the node-10 image.

Page structure, measured:

    field        phoenix   robbedoes
    body           20382       20384      (2 bytes)
    expose         89502       89502      (identical)
    _initHawkejs   55175      117464      (the whole difference)
    imgs              21          21
    unique media       6           6
    sections           4           4

So the RENDERED page is equivalent; the difference is entirely the hydration
payload, which on robbedoes carries **20 extra `MediaFile` records** with
EXIF-shaped fields (`ExifTag`, `DateTimeOriginal`, `XResolution`, ...) that
phoenix's payload does not.

**The cause is NOT determined, and the obvious explanation is wrong.** The EXIF
is not stored data -- `media_raws.extra` holds only `width,height`, and zero
documents carry `ExifTag`. And the native-module inventory runs the OTHER way:

    module          phoenix (node 10)   robbedoes node-10 image
    exiv2           OK                  FAIL: libexiv2.so.14 missing
    mmmagic         OK                  OK
    canvas          OK                  OK

So phoenix can read EXIF and robbedoes cannot, yet robbedoes is the one shipping
EXIF-shaped fields. Left as an open question rather than a guess. Two things are
worth acting on separately: `libexiv2` is missing from the runtime images (the
same class of gap as the GraphicsMagick one fixed earlier today), and a ~62 KB
hydration payload on every page is worth understanding before this hostname is
cut over.

### State

Host `local`: booked **10240 MB of 10915 (93%)**, 14 workloads, all Running or
Active. Disk 28 GB -> 29 GB of 99 GB. Instance 15 uses 96 MiB of its 512, its
Mongo 94 MiB. Nothing was cut over: no DNS record, no certificate, no
`force_ssl`. The four live hostnames were re-checked afterwards and are
unchanged (`earl` 200/1024, `invulassistent` 401/8950, `tavernetomberg` and
`www` 200), as are the four staged siblings.

### Two UI observations

Deleting a database or an attachment re-renders the record it just deleted
instead of navigating to the list (the record IS gone -- a follow-up read
returns 403). And the attachment delete dialog shows the raw catalog key
`delete_confirm` where its confirmation sentence belongs.

## NODE_ENV and libexiv2 across the migrated apps, 2026-08-30

Two gaps left by the migration wave, closed together so each instance took one
restart.

### NODE_ENV=production was missing on every migrated instance

Old Hohenheim exported `NODE_ENV=production` into every child; the Java lane
does not, so six apps were running with it unset. Set as a plain instance
variable (`hoh vars instance <id> set NODE_ENV production`) on 2 microcopy,
3 taverne-tomberg, 6 auditexport, 8 invulassistent, 11 oogfonds-staging and
13 udesign-live; 15 udesign-preview already carried it. Verified in each
container's PID 1 environment, not just in the stored record.

**It changed no rendered page.** Every app's byte size is identical before and
after, and a normalised diff of the two live sites (stripping the per-boot
`?v=...&i=<hex>` asset stamp) is EMPTY -- zero lines. That is consistent with
the Alchemy environment being chosen by `app/config/local.js` (`environment:`),
not by `NODE_ENV`; the variable is set for parity with Phoenix, and should not
be expected to shrink a payload. The earlier "setting it cut the page by 17 KB"
reading on Udesign Preview does not reproduce and is explained below.

### libexiv2: buster yes, bullseye deliberately not

`require('exiv2')` threw `libexiv2.so.14: cannot open shared object file` in
instance 15. `ldd` on the module confirms it links `libexiv2.so.14`.

| base | package available | soname | shipped |
| --- | --- | --- | --- |
| buster (node-10, node-12) | `libexiv2-14` 0.25-4+deb10u4 | `libexiv2.so.14` | YES |
| bullseye (node-16) | `libexiv2-27` 0.27.3-3+deb11u2 | `libexiv2.so.27` | **NO** |

Bullseye ships no `.so.14`, so installing `libexiv2-27` on node-16 would add
weight and satisfy nothing. Measured: no node-16 app on this box carries an
exiv2 module at all -- only instance 15 (node-10) does. An app needing exiv2 on
bullseye must have the module rebuilt against 0.27, which the
copy-`node_modules`-from-Phoenix rule deliberately avoids. The Dockerfile says
this where a reader will hit it.

After the rebuild: `require('exiv2')` -> `OK, keys:
getImageTags,setImageTags,deleteImageTags,getImagePreviews,getDate`.

Images rebuilt on the box: node-10 `3e74aa99cbf9` -> `a989f9341913` (792 MB),
node-12 `d1533013b76a` -> `05c9d9ec1bde` (769 MB). node-16 unchanged
(`8646597d5f54`), so instances 2/6/11/13 keep that image id across their
restart -- an unmoved image id there is correct, not a failed recreate.

### The ~62 KB payload gap does not exist

It was measurement noise, and the noise has a name: `__debuglog`, a per-request
timing block Alchemy embeds in the hydration payload. Its size tracks how many
marks the render logged, and it varies request to request on BOTH boxes. Eight
consecutive authenticated fetches of `staging.udesign.world/`:

    robbedoes  230771/15  229640/13  233140/19  230805/15
               230771/15  240053/31  229655/13  236534/25     (bytes / __debuglog)
    phoenix    230772/15  229627/13  230772/15  230875/15
               230772/15  230772/15  230772/15  230806/15

Same mode on both (15 entries, ~230,77x bytes); robbedoes simply varies more.
`MediaFile` occurrences are **75 on both** and EXIF-shaped mentions **34 on
both** -- the earlier claim that one box shipped ~20 extra MediaFile records was
a single cold render taken seconds after a restart (the same trap that produced
the "sixth capture" anomaly during the Tomberg cutover). Adding libexiv2 did
NOT change those counts either: the module now loads, which is worth having on
its own, but it does not alter this page.

Cold renders right after a restart are not comparable to warm ones. Take the
mode of several fetches, never one.

### Restarts and downtime (polled, 0.3 s interval)

| instance | image before -> after | downtime | verification |
| --- | --- | --- | --- |
| 2 microcopy | 8646597d5f54 (unchanged) | staged, not measured | 200/6699 |
| 6 auditexport | 8646597d5f54 (unchanged) | staged, not measured | 200/0 |
| 11 oogfonds | 8646597d5f54 (unchanged) | staged, not measured | 401/12 |
| 13 udesign-live | 8646597d5f54 (unchanged) | staged, not measured | 200/209082 |
| 15 udesign-preview | 3e74aa99cbf9 -> a989f9341913 | staged, not measured | 401/12, exiv2 loads |
| **3 taverne-tomberg (live)** | d1533013b76a -> 05c9d9ec1bde | **1.5 s** (04:29:40.182 -> 41.638; back 43.503) | 200/145413 + www 200/145417 |
| **8 invulassistent (live)** | d1533013b76a -> 05c9d9ec1bde | **2.2 s** (04:30:48.961 -> 51.135; back 52.975) | 401/8950, 200/9104, 401/8367 |

Every failure during both windows was a 503 from the proxy, never a timeout.
`earl.wcag.be` (untouched) stayed 200/1024 sha `c994a6d8...`, and the Udesign
media derivative still returns 200/43829 sha `e668f9e1f9f9e738...` -- the
Phoenix-identical hash, so GraphicsMagick survived both image rebuilds.

Host after: disk 30 GB of 99 GB, memory 4.0 GB of 11.4 GB used.

### Deploy note

The Dockerfile change is build context only -- **no jar deploy is needed**. The
images live per box, so kuifje and starfleet would need their own rebuild if
they ever run these workloads; today neither does.

## cwebp: every image was broken in a BROWSER only, 2026-08-30

Jelle reported tavernetomberg.be serving no photos, with two errors from the
instance log: "Could not find cwebp path" and the GraphicsMagick one. The second
was STALE (from before the gm rebuild at 02:51); the first was live and was the
whole defect.

**alchemy-media negotiates WebP by USER-AGENT.** With a Chrome UA the media route
answers `500: Error: Could not find cwebp path`; with curl's UA the same URL
returns a perfectly good JPEG. That asymmetry is why the cutover's byte-for-byte
comparison against Phoenix passed: every proof this migration made was a curl.

    UA bisect on /media/image/5898f37e9f3b0c780fc91145?width=50%25&dpr=1
      bare / accept-avif-webp / accept-encoding / referer / sec-fetch  -> 200 244587b
      chrome-UA                                                        -> 500 38b

`webp` (the package carrying `/usr/bin/cwebp`) added to node-10, node-12 and
node-16 beside graphicsmagick; all three rebuilt on the box; all seven app
containers recreated through `hoh power <id> restart`. After it, the four
live hostnames answer unchanged and every derivative returns real WebP bytes
(border-v2-bg.png 19074b, dots.png 908b, the four homepage figures 177590 /
79040 / 70386 / 31498b), and the browser renders the photos.

**The lesson worth keeping: verify a migrated site with a real browser UA, not
curl.** A curl-only proof is blind to every content negotiation the app does,
and this class of defect renders a page that looks perfect in HTML and carries
no image.

## Deploy 2026-08-30 (third jar swap): 166180fe, the console tab works by soft navigation

Swapped the fifteenth-wave jar (sha256 `528e7bb7...`, 267,619,547 bytes, stamp
13/13 `dirty=false`, `grep -c false | grep -qx 13` gate on upload). Preflight
`/root/hohenheim-preflight-20260830-fifteenth/`: `.pre` and `.at-swap` both
`integrity_check` ok, `settings/`, keyring sha256 EQUAL at `cd3a6ca0...`,
`hohenheim-server.jar.rollback` = `b42c0ff2...` (`0782eb8b`). Migration diff
NONE (top M007); `--run-migrations` from `cd /opt/hohenheim`: `0 applied`.

Stop 08:49:31.2Z, start 08:49:35.8Z, healthy 08:49:43.1Z -- **12 s downtime**.
Second restart 08:49:56.4Z, healthy 08:50:03.1Z (7 s). Both: 0 real journal
errors, RSS 350 MB, listeners 53/80/443 + 3000 loopback, roles `[databases,
dns, firewall, instances, proxy]`, `CertificateStore: loaded 4 certificates, 5
hostname mappings`, `SiteDispatcher: loaded 12 exact routes`. Row counts
identical: 9 sites, 12 domains, 5 certificates, 16 instances, 7 managed
databases, 46 migrations, 5 zones. **All 14 containers kept running with
unchanged uptimes.** The four live hostnames re-verified with `--resolve`
against 51.255.43.81: `earl.wcag.be` 200/1024, `invulassistent.wcag.be`
401/8950, `tavernetomberg.be` 200/145413, `www.tavernetomberg.be` 200/145417,
all `ssl_verify_result 0`; panel login 200.

THE POINT OF THE WAVE, proven live in a headless Chromium (profile
`robbedoes-admin`): hard-load `/admin/instances/3` -> `document.body`'s
`data-he-document-policy` = the STRICT_ADMIN string (`connect-src 'self'`);
click the Console tab -> the console logs `Hawkeye: /admin/instances/3/page/
console is served under '...'wasm-unsafe-eval'; connect-src 'self' data:...'
but this document is under '...connect-src 'self'...'` (the mirror's line cap
ate the "loading it as a full page" tail), the snapshot reports `previous
document dropped` (a real page load), NO `Fetch API cannot load data:`, NO
`WASM init failed`, then `[pl-terminal] WebSocket connected`, and the stamp
now equals the terminal policy. Click Overview -> the mirror log line, another
document load, and the STRICT_ADMIN stamp is back. Wire evidence:
`curl -sI -H 'x-hawkeye-request: true' .../page/console` answers
`content-type: text/event-stream` with `content-security-policy` AND
`x-hawkeye-document-policy` both = STRICT_ADMIN_TERMINAL; the same probe on
`/admin/instances/3` carries STRICT_ADMIN in both. Before this wave the same
click produced `Fetch API cannot load data:application/wasm...` and
`ghostty-web unavailable: WASM init failed (...): Failed to fetch WASM: 404`.

[PRESENT TRUTH, 2026-08-31: nothing above this line describes current
mechanism. The per-page terminal CSP variant recorded here -- `SiteTerminalCsp`
and the `STRICT_ADMIN_TERMINAL` policy -- was deleted in hohenheim `5c3696b2`
and zenit-cms, exactly because the variant made every terminal tab a document
boundary no soft navigation could cross. There is now ONE admin policy:
zenit's `ContentSecurityPolicies.STRICT_ADMIN`, which carries
`'wasm-unsafe-eval'` panel-wide while `connect-src` stays `'self'`. The wasm is
no longer a `data:` URL: ghostty-web loads `/vendor/ghostty-vt.wasm`, a
same-origin file whose sha256 is pinned in
`plumage/gradle/vendor-assets.properties`. The next section is the deploy that
proved it.]

The pinned actions cell's hover fade (plumage `b06fde5a`) is in this jar too;
`/admin/instances` renders unchanged, and the fade TIMING cannot be measured
headlessly -- an eyeball on a hovered row is the proof.

## Deploy 2026-08-30 (fourth jar swap): 5c3696b2, one admin CSP, same-origin wasm, soft-nav console

Swapped to hohenheim `5c3696b2` (jar sha256 `a439c66a...`, 267,861,427 bytes,
stamp 13/13 `dirty=false`: hawkeye `e0160bdf`, zenit `8130bcf4`, zenit-cms
`ddcf03bb`, plumage `8afb1d94`). Migration diff NONE (top M007); `0 applied`.
Preflight `/root/hohenheim-preflight-20260830-sixteenth/` (`.pre` + `.at-swap`
integrity ok, settings, rollback jar = `528e7bb7...` i.e. `166180fe`).
Stop 11:04:39Z, healthy 11:04:55.8Z (17 s; the swap script aborted once on a
`stat` permission check between mv and start -- resumed within seconds).
Second restart 11:05:09.4Z, healthy 11:05:17.1Z (7 s). 0 journal errors, RSS
371 MB, listeners 53/80/443 + 3000 loopback, 4 certificates / 12 exact routes,
row counts identical, all 14 containers kept their uptimes.

THE POINT: the per-page terminal CSP variant is GONE (zenit-cms/hohenheim);
STRICT_ADMIN carries `'wasm-unsafe-eval'` panel-wide, `connect-src` stays
`'self'`, and ghostty-web 0.4.0 loads `/vendor/ghostty-vt.wasm` as a pinned
same-origin asset (200, `application/wasm`, 423,045 bytes) -- no `data:` fetch
exists. Proven live both ways:
- Headless Chromium: `window.__probe` set on `/admin/instances/3` SURVIVED the
  click into Console (same document, rev bump only; the tab navigation is the
  `?__hawkeye=1` xhr in the network log), body policy stamp = the one
  STRICT_ADMIN on every tab, `[pl-terminal] WebSocket connected`, wasm fetched
  as same-origin xhr, zero console errors, zero "loading it as a full page";
  clicking Overview kept the probe alive too.
- Real Firefox 153 (the browser that surfaced both defects, cache disabled):
  hard load of the console page -> `WebSocket connected`, no error lines (the
  old bridge printed "script tag already settled without defining the global"
  here on every fresh load); Edit -> Console soft navigation -> `[pl-terminal]
  disposed` then `WebSocket connected` again, terminal canvas rendered.
- Wire: the console page's CSP header = STRICT_ADMIN with `'wasm-unsafe-eval'`
  and WITHOUT `data:`; `x-hawkeye-document-policy` announced on soft-nav
  answers (the dormant boundary mechanism; nothing in the panel differs, so it
  never fires here).
Live hostnames re-verified via `--resolve`: earl 200/1024, invulassistent
401/8950, tavernetomberg 200/145413, www 200/145417, ssl=0 everywhere.
ROLLBACK IS JAR ONLY: the preflight `.rollback` back into place and restart.

## Deploy 2026-09-01 (fifth jar swap): a7d65f01, the audit fix wave

Swapped to hohenheim `a7d65f01` (jar sha256 `111e8cd5...`, 268,096,840 bytes,
stamp 13/13 `dirty=false`: hawkeye `1218defa`, zenit `9fd78ec7`, zenit-cms
`380f48f9`, zenit-microcopy `6f32289e`, plumage `5adb654b`, zenit-forms
`abcc2118`, zenit-comms `1f0725f2`). Built from a clean secondary workspace
(`~/projects/javaweb-deploy`, 15 detached worktrees, deleted afterwards).
Migration diff: M008 (bans.scope). Preflight
`/root/hohenheim-preflight-20260901-wave/` (.pre + .at-swap, settings,
rollback jar = `a439c66a...` i.e. `5c3696b2`). Stop 10:49:03Z, healthy
10:49:15Z; second restart 10:53:55Z, healthy 10:54:08Z. 0 journal errors.

REHEARSAL-LANE DEFECT, recorded: `--run-migrations` from a scratch cwd holding
a db byte copy still migrated the LIVE database (the jar resolves its db path
independent of cwd), so the "rehearsal" applied M008 for real while the old jar
was serving (risk-free here: additive nullable column, transactional) and the
real run reported `0 applied`. Until the rehearsal lane gets an explicit
db-path override, a byte-copy rehearsal proves nothing; kuifje and starfleet
skipped it and applied M008 directly (`1 applied` each).
[Fixed the same day: `--rehearse-migrations <db-copy>` is now the rehearsal
lane -- an offline command that migrates the named copy through its own
unregistered datasource and REFUSES a target that is the configured live file
(by file identity, symlinks included). Usage:
`sqlite3 /opt/hohenheim/hohenheim.db ".backup /tmp/rehearse.db" &&
sudo -u hohenheim java -jar <new jar> --rehearse-migrations /tmp/rehearse.db`.
Pinned by `OfflineCommandLaneTest.aMigrationRehearsalMigratesTheCopyAndRefusesTheLiveFile`.]

Unit changes (backup `/root/hohenheim.service.bak-20260901`):
`SupplementaryGroups=docker systemd-journal` (the SSH watcher reads journald),
`-Xmx2048m -> -Xmx1024m` + `-Xlog:gc:file=/opt/hohenheim/logs/gc.log` (RSS
after restart 388 MB vs 2.29 GB before; jcmd is unavailable on the JRE, the gc
log is what makes the live set measurable from now on; rollback = restore the
backup line + restart).

Settings: `security.ssh_watch_enabled=true` via the panel
(`nftables_ssh_ports` stays default 22). Proven live at the second restart:
`hohenheim.ssh_watch_started`, `banned_ssh_v4`/`banned_ssh_v6` sets + port-22
drop rules programmed beside the untouched 80/443 set.

Crash policy: instances 2, 3, 6, 8, 11, 13, 15 (all app workloads) flipped to
`restart` via the panel; DB-generated instances pick it up from code at their
next reprovision and are covered by the crash->error attention item meanwhile.

Boot self-heal observed: `QUOTA: reconciled bucket hohenheim:instances: from
15 to 14` and `owner_mem_mb: from 8448 to 7936` -- the refused-create leak
corrected itself as designed. Dashboard now shows the previously-dead
failed-task attention items (BackupControlPlane, BackupDatabases) and the
Inbox surface; alerts fan out to admin inboxes with no channel rows.

Live checks: earl 200, invulassistent 401 (its gate), tavernetomberg + www
200, panel 302, health 200, `zd_deployed` current 13/13.
ROLLBACK: preflight jar back into place + unit backup line + restart (M008 is
additive; the pre-migration db copy is `.pre`).

## Udesign Preview retired; Udesign Live synced with phoenix, 2026-09-01

Jelle: the preview/staging site is no longer required. Deleted, in order: site 8
(`udesign-preview`, soft-delete), instance 15 (container + volume
`/opt/hohenheim/data/volumes/15/home` destroyed), access list 2 (`Udesign
preview`), database 10 (`udesign-preview-mongo`, container `instance-16`, via
the panel -- databases have no API delete verb). Frees 1024 MB of booking.
Verified: no `instance-(15|16)` containers, volume dir gone, udesign-live 200.

Same day Jelle edited `app/view/partials/ontwer_block.hwk` on phoenix (new
SurveyMonkey CTA) and restarted the app there. Synced to instance 13's volume
byte-identically (md5 `5ba49356cced3e32bb7e6e9cc8f69248`, owner 200013, backup
`/tmp/ontwer_block.hwk.bak-20260901` on the box), `hoh power 13 restart`,
rendered proof on both sides (`nl.surveymonkey.com/r/LG59W3V` present).

ACCESS NOTE: skerit on phoenix has no passwordless sudo, but IS in the `docker`
group -- a read-only bind mount (`docker run --rm -v <dir>:/mnt:ro --entrypoint
sh <image> -c 'base64 /mnt/<file>'`) is the sanctioned read path for another
user's home there.

## Deploy 2026-09-01 (sixth jar swap): c817760a, the deferred-ledger wave

Swapped to hohenheim `c817760a` (jar sha256 `567fbc26...`, 268,147,196 bytes,
stamp 13/13 `dirty=false`: protoblast `68ca411d`, plumage `f378488d`,
zenit-forms `797e77cd`, zenit-comms `aea74567`, zenit-cms `b111b65c`, rest
unchanged). Clean secondary workspace `~/projects/javaweb-deploy` (17 detached
worktrees -- the chain also needs emberglyph and janeway; a workspace without
them fails resolving `be.elevenways:janeway`), deleted afterwards. No new
migration (`0 applied`); preflight `/root/hohenheim-preflight-20260901-wave2/`
(db `.pre` integrity ok/47 rows, settings, rollback jar = `111e8cd5...` i.e.
`a7d65f01`). Stop 15:23:46Z, healthy, second restart healthy, 0 journal errors.

Live: earl 200, tavernetomberg + www 200, invulassistent 401 (its gate),
udesign via Host 200, panel 302, both nameservers serial-matched (wcag.be 15).
Browser proofs (headless Chromium, fresh profile): activity list's default
"Origin: People only" chip rendered with `data-chip-default`, removing it put
`filter.origin.__cleared=1` in the URL and revealed system rows; required
markers live on the template-volume form (`data-required` labels, `"*"` glyph,
`aria-required` on name + container_path; the databases form declares no
Required validators, so its unmarked fields are correct, not a regression);
Inbox renders; template Contents tab shows Declared volumes + Add volume;
Console tab by SOFT navigation (`window.__probe` survived, `[pl-terminal]
WebSocket connected`).

Migration pin raised to `DEPLOYED_THROUGH = "008"` + M008 digest in
migration-pins.txt (the fifth wave's forgotten step 8), red-then-green runs
22/23. ROLLBACK: preflight jar + restart (no migration in the delta).

## Review pass 2026-09-01 evening: the SSH watcher was banning nobody; file modes

`journalctl -u ssh -o json | grep -o '"SYSLOG_IDENTIFIER":"[^"]*"' | sort | uniq -c`
over 24h: 12 `sshd`, 24,598 `sshd-session`, 63 `unix_chkpwd`. The watcher tailed
`-t sshd` only, so `ssh_watch_started` was true and `bans` held 33 rows, all scope
`web`, none `ssh`. Fixed in `c6713cf5` (`-t sshd -t sshd-session -t sshd-auth`);
rides the next jar. Until then the port-22 sets stay empty by construction.

Modes fixed on the box (no restart): `hohenheim.db` + `-wal` + `-shm` 0664 -> 0640
(the installer's `--run-migrations` created it under root's umask, not the unit's
`UMask=0027`; SQLite copies the main file's mode onto -wal/-shm, so all three moved
together), `volumes.btrfs` 0644 -> 0600 (root-owned image of every tenant volume),
`/opt/hohenheim/staging` 0755 -> 0700 (Phoenix app + mongo dumps, 1.6 GB). Loop
mount and sites unaffected (tavernetomberg 301 via loopback after the change).
Installer now applies both modes itself (same commit).

## Deploy 2026-09-01 (seventh jar swap): 1cbc83a1, the review-pass wave

Swapped to hohenheim `1cbc83a1` (jar sha256 `167b0e58...`, 268,148,442 bytes,
stamp 13/13 `dirty=false`: zenit-cms `0e158045`, rest as wave 6). Clean secondary
workspace `~/projects/javaweb-deploy` (16 detached worktrees incl. emberglyph +
janeway, deleted afterwards), chain build 639 s. No new migration (`0 applied`).
Preflight `/root/hohenheim-preflight-20260901-wave3/` (db `.pre` integrity ok /
47 migrations, `.at-swap`, settings, rollback jar = `567fbc26...` i.e.
`c817760a`). Stop 21:44:40Z, healthy 7 s after start; second restart healthy
7 s; 0 warnings beyond the JDK's Unsafe/native-access notices.

Carried: the SSH watcher identifier fix -- the child is now
`journalctl -f -n 0 -o cat -t sshd -t sshd-session -t sshd-auth` (verified in
`ps`); the DNS zones list (Serial + Enabled to the picker, Records action to the
row menu) and the two-line absolute datetime cell. Live: earl 200, tavernetomberg
+ www 200, invulassistent 401 (its gate), udesign + microcopy 200 over HTTP (no
certificates yet, by design), panel 302, wcag.be SOA serial 15. `zd_deployed`
current 13/13.

Browser (headless Chromium 1440x900): DNS zones table 1134/1134 px, no
`data-overflow-inline-end`, all seven columns visible. Certificates table
1207/1134 px -- still 73 px over because of Created at; the date cells render
correctly on two lines. Follow-up committed for the NEXT wave: Created at hidden
by default, default sort = expires_on ascending.

ROLLBACK: preflight jar back into place + restart (no migration in the delta).

## The two Phoenix WordPress sites staged, 2026-09-02 (~22:10-22:30Z)

Both `WordPress (PHP x.y)` templates approved (panel row action). Lane exactly as
`docs/wordpress.md` describes, with one deviation named below.

| | Anymedia / ConnectedPrint | Diax |
| --- | --- | --- |
| phoenix | `/home/anymedia/any-media.be`, PHP 8.1 FastCGI, db `anymediawp`, WP 7.1, 252 MB (uploads 42 MB), 19 tables / 2.8 MB, placeholder salts in wp-config | `/home/diax/diax.be`, PHP 7.4 FastCGI, db `diaxwp`, WP 7.0.2, 677 MB of which `.git` 160 MB (NOT copied) and uploads 130 MB, 229 tables / 110 MB; compromise remediation 2026-08-07 (forensic tarballs in `/home/diax`, hardened `.htaccess` copied along) |
| template | 3, WordPress (PHP 8.1) | 4, WordPress (PHP 7.4) |
| instance | 17 `anymedia`, container `instance-17`, port 32827 | 19 `diax`, container `instance-19`, port 32828 |
| database | 11 `anymedia-wordpress-db` (instance 18, mysql:8.0, 1024 MB) | 12 `diax-wordpress-db` (instance 20, mysql:8.0, 1024 MB; 524 MiB RSS after import, so 512 would not have fit) |
| CONFIG_EXTRA | seeded HTTPS fix + `WP_HOME`/`WP_SITEURL` https://any-media.be + `FORCE_SSL_ADMIN` | seeded fix + `WP_HOME`/`WP_SITEURL` https://www.di-ax.be + `DISALLOW_FILE_EDIT` + `WP_AUTO_UPDATE_CORE minor` + the cookie path defines |
| site | 10 `Anymedia WordPress`, domains 15-22: any-media.be, www, connectedprint.org/.be/.eu + www (all `force_ssl=false`, no certificate possible before the DNS flip) | 11 `Diax WordPress` (domain 23 www.di-ax.be) + 12 `Diax redirect` (hohenheim:redirect -> https://www.di-ax.be, 301, preserve_path; domains 24-26 di-ax.be, diax-centre.be, www.diax-centre.be) |

Read path on phoenix: `/home/anymedia` is world-readable; `/home/diax` (0750) was read
through `docker run --rm --user 0 -v /home/diax:/mnt:ro` (skerit is in the docker
group; `--user 0` is what the earlier ACCESS NOTE lacked -- the image's default user
gets Permission denied). Dumps: `mysqldump --single-transaction
--default-character-set=utf8mb4` against 127.0.0.1 with the wp-config credentials
(MariaDB 10.5 dumps restored into mysql:8.0 without a single error). Files streamed
phoenix -> workstation -> robbedoes (`ssh cat | ssh cat`), sha256 equal on both ends;
phoenix's staging copy deleted, robbedoes keeps `/home/debian/wp-stage/` (0700) for
the cutover's delta.

DEVIATION: the dumps were restored with `docker exec -i <db container> mysql` using
the instance's injected `WORDPRESS_DB_*` env (the same client command
`ManagedDatabase.restoreCommand` runs), not through the panel's Restore upload: the
headless browser cannot hand a local file to a file input, and there is no API
restore verb. Docroots went straight into the volume dir
(`/var/lib/docker/volumes/<handle>-vol-html/_data`) while the container ran:
everything except the image-generated `wp-config.php` removed, tar extracted,
`chown -R 33:33`. Reason for not stopping the container as the doc says:
`crash_policy=restart` treats an unobserved stop as a crash and would redeploy under
the copy. The image's wp-config.php reads every `WORDPRESS_*` value via
`getenv_docker()` at request time (`eval` for CONFIG_EXTRA), so a CONFIG_EXTRA edit
needs only a restart, never a regenerated file.

Proof (browser UA, `X-Forwarded-Proto: https` at the published port so WordPress
believes it is behind TLS): any-media.be 200 / 58,504 B on BOTH boxes, identical
href/src inventory (0 diff lines), an upload 200 image/png, wp-login 200; www.di-ax.be
200 / 32,710 B on both, 0 diff lines, uploads svg 200, wp-login 200. Through the proxy
(port 80): every hostname 301s to its https canonical, di-ax.be/over-ons/ ->
https://www.di-ax.be/over-ons/ (preserve_path), connectedprint.org -> any-media.be
(WordPress' own canonical redirect, as on phoenix).

Booking after this: 9,984 / 10,915 MB (WP 512 x2 + mysql 1024 x2 = 3,072 added).
Smell: the panel's Deploy POST blocks while the image pulls and the panel proxy gave
up first (`UT005028: Proxy request to /admin/instances/17/action/deploy_instance
failed`) although the deploy completed -- a long deploy should answer and progress
out of band.

CUTOVER (Combell, not ours): lower TTLs, point any-media.be + www + connectedprint.*
and www.di-ax.be / di-ax.be / diax-centre.be (Hetzner NS, so this one can move to our
nameservers first) at 51.255.43.81 / 2001:41d0:305:2100::1:4b26, request HTTP-01
certificates, set `force_ssl=true`, and BEFORE flipping re-sync the delta: fresh
mysqldump into the managed db + rsync of `wp-content/uploads`. The diax `.git`
stays on phoenix (repo, not runtime).

## Deploy 2026-09-02 (wave 4): 4551de29, shared database engines, M009, five of six Mongo records moved

Swapped to hohenheim `4551de29` (jar sha256 `9f8eb1a8...`, 268,192,262 bytes,
stamp 13/13 `dirty=false`; chain as wave 3). Built in `~/projects/javaweb-deploy`
(16 chain worktrees + hohenheim detached). Preflight
`/root/hohenheim-preflight-20260902-wave4/` (db `.pre` ok / 47, settings,
rollback jar `167b0e58...` = `1cbc83a1`). M009 rehearsed on a byte copy via
`--rehearse-migrations` (1 applied against the copy, live untouched), then: stop
06:47:50Z, `1 applied` (48), healthy at try 2 (06:47:59Z); second restart healthy
at try 2; no warnings. Live after each: panel 302, earl 200, invulassistent 401
(its gate), tavernetomberg + www 200, udesign / microcopy / any-media (301) /
www.di-ax.be (301) over HTTP, wcag.be SOA 15.

### The migration onto one shared Mongo engine (panel, one record at a time)

Booking before: 9,984 / 10,915 MB. A 1024 MB engine beside the six dedicated
containers did not fit by 93 MB, so `capacity.memory_overcommit_ratio` went
1.0 -> 1.5 for the duration (back to 1.0 at the end; the key is gone from
`hohenheim.dry`). Every move: Databases list -> row menu -> "Move to shared
engine" -> confirm; each dump kept under `data/backups/moves/<name>/`, each old
data volume kept, each site checked with a browser UA afterwards.

| record | moved (Z) | proof |
| --- | --- | --- |
| tomberg-mongo (1) | 06:49:37 | engine `mongo-local` minted on demand (mongo:7, 1024 MB, instance 21); tavernetomberg.be 200 `<title>Taverne Tomberg` |
| microcopy-mongo (2) | 06:50:14 | microcopy.elevenways.be 200 `Microcopy | Eleven Ways` |
| auditexport-mongo (3) | 06:50:51 | auditexport.di-ax.be 200 |
| oogfonds-staging-mongo (6) | 07:07:06 | oogfonds.clients.11ways.be 401 (its gate) |
| udesign-live-mongo (7) | 07:09:36 | udesign.world 200 `<title>Udesign.world` |

Booking after: 8,448 MB (engine 1024 + `invulassistent-mongo` still dedicated at
1280); it lands at 7,168 once invulassistent moves. Two Mongo containers remain
on the box (the engine and invulassistent's).

### invulassistent-mongo (4): two refusals, two product defects, NOT moved yet

1. First attempt 06:52: refused honestly -- `Dump of 'hohenheim-luguij0q-instance-7'
   exceeds the configured cap of 2048 MB (setting database.max_dump_mb)` (1.3 GB on
   disk in WiredTiger is a 5.3 GB uncompressed BSON archive). DEFECT: the failure
   path redeployed the stopped consumers BEFORE resetting the record to active,
   so the deploy refused with `database_not_ready {state=provisioning}` and
   instance 8 stayed stopped: invulassistent.wcag.be 503 for ~2.5 minutes until
   `hoh power 8 start`. `database.max_dump_mb` raised to 8192 via the panel.
2. Second attempt 06:54: the dump succeeded (5.3 GB, streamed to
   `data/backups/moves/invulassistent-mongo/20260902-065400.archive`, KEPT), then
   the RESTORE loaded the whole archive into the controller heap
   (`restoreFromFile` -> `Files.readAllBytes`): gc.log shows back-to-back full GCs
   at 955M->955M at 06:55:49 and a drop to 23M at 06:55:58 -- an
   `OutOfMemoryError` on the move thread. The move's compensation caught only
   exceptions, so the Error skipped it: record stuck `provisioning`, instance 8
   stopped, NO log line anywhere. Recovered by hand at ~07:05 (`UPDATE
   managed_databases SET status='active', failure_reason=... WHERE id=4 AND
   status='provisioning' AND placement IS NULL`, then `hoh power 8 start`;
   invulassistent 401 again). Total site outage of the second incident ~13 min.

Both fixed in code for the next jar: the restore streams the file into the
engine's own client over an exec's stdin with a byte-counted EOF (`head -c
<size> | mongorestore --archive`; no copy in the heap, none in the container's
writable layer), the compensation catches `Error` and resets the record BEFORE
redeploying the consumers; `SharedDatabaseEngineLiveTest` step 2b pins the
refusal ordering (workload running again after a refused move). invulassistent
moves with that jar.

Also fixed in code from this wave's observations: pre-M009 rows rendered
Placement "None" (a null column now READS as dedicated at load), the boot line
`source_capability_dropped` for the engine source, and the stale "buffered through
controller memory" text on `database.max_dump_mb`.
ROLLBACK: preflight jar + `.pre` copy + restart; every moved record keeps its
dump and its old data volume.

## Deploy 2026-09-02 (wave 4b): 7b0ed91d, the streaming restore; invulassistent moved

Swapped to hohenheim `7b0ed91d` (jar `c05ea944...`, 268,195,785 bytes, 13/13
clean; carries `2f05a1dd` restore-over-exec-stdin + Error-safe compensation,
`6eb28b0b` placement read rule + engine source, the M009 pin). No migration
(`0 applied`); `.at-swap2` + `rollback2.jar` (= `4551de29`) in the wave-4
preflight dir. Stop 07:40:37Z, healthy at try 2; second restart at try 3; no
warnings. The `source_capability_dropped` line for the engine source is gone
(five pre-existing ones remain: ban, dns_zone, runtime_image, server,
site_auth_provider -- same shape, on the ledger).

invulassistent-mongo moved on this jar, 07:41:43Z -> 07:45:10Z: the 5.3 GB dump
streamed to `data/backups/moves/invulassistent-mongo/20260902-074143.archive`,
the restore ran as `sh -c 'head -c "$1" | mongorestore ...' hohenheim-restore
5336385232 invulassistent invulassistent` INSIDE the engine container (visible in
`docker top`), and the controller heap never left its 96M->47M young-GC rhythm
(RSS 443 MB) while it ran. Fingerprint matched, instance 7 destroyed (volume
kept), instance 8 redeployed: `DATABASE_URL=mongodb://invulassistent:***@
hohenheim-luguij0q-instance-21:27017/invulassistent?authSource=invulassistent`,
invulassistent.wcag.be 401 (its gate). Engine `listDatabases`: all six
(invulassistent 942 MB on disk), 17 collections.

Final picture: ONE Mongo container on the box (was six), booking 7,168 / 10,915
MB (was 9,984), instances bucket 11, engine at 490 MiB of its 1 GiB, host 8.2 GB
available. `capacity.memory_overcommit_ratio` back at the default. Every moved
record keeps its dump under `data/backups/moves/` and its old data volume.
Deploy workspace `~/projects/javaweb-deploy` deleted afterwards.

## Deploy 2026-09-02 (wave 5): 78e6cfac, the annoyance wave via tools/deploy-host.sh

Swapped to hohenheim `78e6cfac` (jar sha256
`fc7e6bdcedbb41f90f21ef273747b300848d97526f648fa68ab47fa42543fb22`, 268326427
bytes, stamp 13/13 `dirty=false`; same jar as starfleet's wave 5, which also
carries `e211afd3`, the release-aware engine booking + pre-transfer dump-size
refusal). Deployed with `tools/deploy-host.sh robbedoes <jar>` in one run, exit
0. Preflight `/root/hohenheim-preflight-20260902-092645/` (`hohenheim.db.pre`
integrity ok, `hohenheim.db.at-swap`, `settings/`, no keyring on this install
yet, rollback jar `rollback.jar` = `7b0ed91d`). `--rehearse-migrations` on a
byte copy: 0 applied; boot applied none (top stays M009). Healthy after both
restarts. Verified after: browser-UA `--resolve` at 51.255.43.81 gives
earl.wcag.be 200, invulassistent.wcag.be 401 (its own gate), tavernetomberg.be
200; `/health` 200 on the panel; 11 containers up; 0 `source_capability_dropped`
lines in the boot journal. udesign.world and microcopy.elevenways.be still
resolve to Phoenix (144.76.30.204, 200 there), untouched by this wave.
`zenit-dev deployed robbedoes` = hohenheim current, no restart pending.
ROLLBACK: `tools/deploy-host.sh --rollback robbedoes --preflight /root/hohenheim-preflight-20260902-092645`.

## 2026-09-02: the two WordPress MySQL records moved onto one shared engine; the `app` user collision

Both dedicated MySQL records moved through the new API verb (`hoh database move
11 --yes`, then `12`), each landing `active shared` on engine 2 `mysql-local`
(mysql:8.0, 1024 MB) in about 20 s: anymedia-wordpress-db at 10:07:08Z, then
diax-wordpress-db at 10:07:42Z. Dumps kept under
`/opt/hohenheim/data/backups/moves/<name>/`, old data volumes kept. Containers
11 -> 10; booking: two dedicated MySQLs at the 1 GiB default gave way to one
engine at 1024 MB (net -1024 MB); the engine sits at ~548 MiB with both
databases. Both sites were verified before and after through their containers
with a browser UA and `X-Forwarded-Proto: https` (the staged hostnames have no
certificate on robbedoes yet, so a public HTTPS probe cannot be used): 200 with
the real titles.

INCIDENT (no visitor impact, staged sites): after the SECOND move
any-media.be answered "Database Error". Both records carried the same logical
user `app` (each dedicated container had its own), and MySQL users are
ENGINE-GLOBAL: the second create script's `ALTER USER 'app'@'%' IDENTIFIED BY`
re-credentialed Anymedia's user with DiAX's password, and `app` then held
grants on BOTH databases. Hand recovery (10:15Z): one user per logical database
on the engine (`anymedia_wordpress_db`, `diax_wordpress_db`, each with its
record's own stored password read from the container env, `GRANT ALL` on its
own database only), `managed_databases.db_user = db_name` for 11 and 12 by
SQL, `hoh power 17|19 restart` so the env re-injects, both sites 200 again,
then `DROP USER 'app'@'%'`. Verified: `select user,host from mysql.user` lists
exactly the two record users plus root.

The mechanism fix that followed (same day): shared placement refuses a logical
name or user another record on the engine holds
(`database_logical_name_taken` / `database_logical_user_taken`), the move lane
renames a taken user to the database name, and a TENANT allocation names both
its logical database and its user after the NAMESPACED stored name -- the bare
label used to make two tenants' "blog" ONE database on the shared engine.
Pinned by `SharedDatabaseEngineTest` step 4b, `TenantDatabaseSurfaceTest` steps
6/7 and `SharedDatabaseEngineLiveTest` (squatter + same-user refusal), runs
126 and 131.
