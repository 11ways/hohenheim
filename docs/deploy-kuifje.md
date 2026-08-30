# kuifje: the DNS primary

The second public Hohenheim install, and the machine that finally gives
`authoritative-dns.md` its two-nameserver threshold a second name server to
point at. It is a DNS PRIMARY ONLY: it hosts no sites, and it is deliberately
not a Phoenix successor -- it is too small for that.

Installed 2026-08-29 by `tools/install-host.sh`, which IS the procedure
(`docs/deploy-native.md`). Nothing on this host was configured by hand except
the first administrator, which the product itself creates.

## Host facts

    ssh              debian@137.74.171.228   (key auth, passwordless sudo -n)
    IPv6             2001:41d0:305:2100::1:4afe
    hostname         kuifje   (was vps-0ffe31ed; renamed 2026-08-30)
    os               Debian GNU/Linux 13 (trixie)
    ram / disk       3826 MB / 40 GB (4.3 GB used after the install)
    java             Debian's own openjdk 25.0.4.1 (/usr/bin/java)
    swap             2 GB swapfile at /swapfile, vm.swappiness=10 (created by the installer)

Debian 13 ships openjdk 25, so the installer's Adoptium lane never ran here.
The deployed jar is Java 25 bytecode (`sourceCompatibility = VERSION_25`) and
was proven to run on the distro JDK with a `--build-info` invocation BEFORE the
install; no second JDK was installed and none is needed.

## What is installed

    /opt/hohenheim/hohenheim-server.jar     hohenheim d17494d2 (starfleet's live build)
    /opt/hohenheim/settings/hohenheim.dry   roles proxy, dns, firewall (0640)
    /opt/hohenheim/settings/local.dry       0600, main_url placeholder
    /opt/hohenheim/settings/auth.dry        0600, external_base_url placeholder
    /opt/hohenheim/settings/field-encryption.keys   0600, generated at first boot
    /opt/hohenheim/hohenheim.db             sqlite control plane, 43 migrations
    /etc/systemd/system/hohenheim.service   -Xmx1472m (40% of MemTotal, the installer's rule)
    /etc/sudoers.d/hohenheim-nft            the single nft grant
    /etc/sysctl.d/99-hohenheim.conf         fs.file-max=200000, vm.swappiness=10
    /etc/systemd/resolved.conf.d/hohenheim.conf   DNSStubListener=no
    /root/hohenheim-admin.txt               0600, the generated admin password

`main_url` and `auth.external_base_url` are both the placeholder
`https://panel.invalid` (RFC 2606, unmistakably not a real name) because the
naming decision for this box is the owner's and is still open. Set both when
the name is chosen; `main_url` is what the dyndns hint URL is built from and
`external_base_url` is what mailed links use.

`network.trusted_proxies` was left at the installer's `loopback`, untouched.

NOT installed, deliberately: Docker, the btrfs volume root, and the
`instances`/`databases`/`stacks` roles. The first plan for this box included
them; the owner corrected it to DNS-primary-only before the install ran, so
there was nothing to remove -- no docker package was ever installed here and no
loop file or fstab line was ever created.

The proxy role IS enabled and is not optional: the admin panel route and the
ACME HTTP-01 lane both live in it.

## Install transcript (2026-08-29)

    tools/install-host.sh --jar hohenheim-server.jar \
        --roles proxy,dns,firewall \
        --main-url https://panel.invalid \
        --admin-email hostmaster@panel.invalid \
        --swap 2G

First run: base packages (`gnupg sqlite3 unzip nftables dnsutils`), java
skipped (`java 25+ already present at /usr/bin/java`), docker skipped (no role
asked for it), volume root skipped, settings seeded,
`switching off systemd-resolved's stub listener (it owns 127.0.0.53:53)` then
`udp/53 is free`, `creating a 2G swapfile at /swapfile`,
`MemTotal 3826MB -> -Xmx1472m`, `Migrations complete 43 applied`, `health: OK`.

Second run, immediately after: 25 `skip:` lines, no restart
(`ActiveEnterTimestamp` stayed at the first run's 19:15:33 UTC while the second
run executed at 19:17). The installer is safe to re-run on this box.

## First administrator

Created through the product's own `/setup` page over loopback (curl with the
page's `csrf_token`), never by writing the database. The password was generated
on the host and lives ONLY there:

    /root/hohenheim-admin.txt      (0600, root)

It holds the email (`admin@panel.invalid`), the URL and the password. Rotate it
once the box has a real name, and note that `--set-password --email <address>`
is the offline recovery lane if it is ever lost.

Reaching the panel until this box has a hostname:

    ssh -L 3000:127.0.0.1:3000 debian@137.74.171.228
    # then http://127.0.0.1:3000/ in the browser

Port 3000 is a listener on `*:3000` but there is no site, certificate or
firewall rule pointing at it; do not expose it -- put it behind a real hostname
on the proxy when the name exists.

## Verified after the install

- `systemctl is-active/is-enabled hohenheim` -> active / enabled.
- Listeners: `*:53` udp AND tcp, `*:80`, `*:3000`. 443 does not listen yet and
  should not: the journal says `Proxy HTTPS not started: no certificates
  available`, which is correct on a box with no sites.
- `dig +norecurse @127.0.0.1 example.com SOA` and the same over `+tcp`:
  `status: REFUSED`, i.e. the authoritative server answers and refuses
  out-of-zone names. It serves no zone yet by design.
- `/` over the ssh forward: 302 to `/login`, the sign-in page renders 200.
  A login with the generated password lands on `/admin/dashboard` (200).
- `--build-info` as the service user: `hohenheim d17494d2 clean` plus the 12
  other module stamps, all clean.
- `free -m`: 2047 MB swap, `vm.swappiness` = 10.
- `/etc/resolv.conf` -> `/run/systemd/resolve/resolv.conf` and host DNS still
  resolves (`deb.debian.org`).
- `roles_captured enabled=[dns, firewall, proxy]` in the journal. The `[ERR]`
  lines around it are Undertow's INFO-on-stderr, the same noise starfleet logs.

Nothing else was created: no site, no domain, no DNS zone, no certificate.

## Rollback

There is nothing to roll back TO -- this was a fresh install, not an upgrade.
Undoing it completely is:

    systemctl disable --now hohenheim
    rm /etc/systemd/system/hohenheim.service /etc/sudoers.d/hohenheim-nft \
       /etc/sysctl.d/99-hohenheim.conf /etc/systemd/resolved.conf.d/hohenheim.conf
    systemctl daemon-reload && systemctl restart systemd-resolved
    swapoff /swapfile && rm /swapfile      # and its /etc/fstab line
    rm -rf /opt/hohenheim /var/log/hohenheim /root/hohenheim-admin.txt
    userdel hohenheim

Removing `/opt/hohenheim` destroys the field-encryption keyring together with
the database, which is the right pairing (`deploy-starfleet.md`): a keyring
without its database is useless and a database without its keyring cannot be
read. Once this box holds a zone, back both up together before touching either.

From the FIRST jar swap onward the ordinary runbook applies
(`deploy-starfleet.md`): preflight copy of the database, an at-swap `.backup`,
the previous jar kept as `hohenheim-server.jar.rollback`, and a rehearsal
against a byte copy whenever the migration diff is non-empty.

## DNS federation with starfleet, 2026-08-29

First real federation between two Hohenheim controllers, exercised end to end
against `starfleet.life`. Both boxes ran build `d17494d2` (clean). Left in
place as the desired end state; every `visual-qa-*` record created for it was
removed.

Reachability first: `dig @137.74.171.228 SOA example.com` answers REFUSED, so
kuifje's provider firewall already passes udp/tcp 53 inbound and the
authoritative-only refusal is correct. starfleet answered its own zone at
serial 30.

Peers (`/admin/dns-peers`), one row per side, both id 1:

    starfleet   "kuifje"     NAMESERVER, transfer 137.74.171.228:53
    kuifje      "starfleet"  HOHENHEIM,  transfer 104.223.42.142:53,
                             base_url https://admin.starfleet.life + a znit_ key
                             scoped hohenheim.admin.access

TRAP, and the reason the two rows have different peer types: the peer form
REFUSES a HOHENHEIM peer without an admin base URL
(`dns_peer_base_url_required`). kuifje's panel listens on 127.0.0.1:3000 only and
its `network.main_url` is still `https://panel.invalid`, so starfleet has no
admin URL to store for it. That is not a blocker in this topology: the admin
channel only ever runs secondary -> primary, so the peer row on the PRIMARY
only needs the transfer channel and is correctly a plain NAMESERVER peer.

Key negotiation therefore has a direction: it must be started on the side that
can REACH the other's admin API, i.e. from kuifje. Set `dns.federation_name` to
`kuifje` first (blank falls back to the system hostname, and the announced
name is what the receiving side matches its peer row by, so the wrong name
silently creates a SECOND peer row on starfleet). Then the "Negotiate transfer
key" row action on kuifje's `starfleet` peer wrote `xfer-ovh-starfleet` /
hmac-sha256 on BOTH sides in one click; no secret was ever copied by hand.
starfleet logged `DNS: transfer key xfer-ovh-starfleet installed for peer ovh`
(the peer was still named `ovh` then; the TSIG key name never changes with a
rename, so `xfer-ovh-starfleet` is still the key on the wire).

Zone: `starfleet.life` attached to peer `kuifje` on the primary's Secondaries
tab (`dns_zone_peers` id 1), then created on kuifje as role=secondary with peer
`starfleet` (zone id 1). Verified identical answers on both servers, all with
aa=1: SOA, apex NS (nssl/nssl2.mooo.com), `admin`, `skeleton`, `www`, `ns1`,
`ns2`, a random label answered by the `*` wildcard, and NODATA-with-SOA for an
absent type.

Timings (UTC, from zone creation and from each edit):

    initial pull            18 s   serial 30, REFUSED -> aa=1 in one tick
    TXT add on primary       3 s   serial 31, NOTIFY-driven
    TXT delete on primary    1 s   serial 32
    TXT add via forwarding   3 s   serial 33
    TXT delete via forward   1 s   serial 34

Everything after the first pull is NOTIFY-driven, far below the 30 s refresh
poll. kuifje journals one `dns.secondary_transfer` per serial.

The admin channel works despite the placeholder `main_url`: kuifje's Records tab
on the secondary zone says "This zone is owned by peer "starfleet": the records
below are read live from it and edits are forwarded there", lists starfleet's
live rows, and its "Save on peer" / "Delete this record on the owning peer?"
round-tripped a create and a delete to the primary, which bumped the serial and
NOTIFYed the replica back. No refusal of any kind. `main_url` is only the
instance's own public name; it is never the credential for an OUTBOUND call.

Two observations, neither a blocker:

- The PRIMARY journals nothing for a served AXFR or a sent NOTIFY. Over the
  whole window starfleet logged only the key install; all transfer evidence is
  on the secondary or on the wire. Debugging a silent secondary from the
  primary's logs is currently not possible.
- The Secondaries tab shows only peer name and transfer host: no freshness
  pill, served serial or probe time. The secondary-health work
  (`M004_DnsFederationHealth`, `ProbeDnsSecondaries`) that
  `dns-federation.md` describes is UNCOMMITTED in the worktree and absent from
  `d17494d2`, so both live boxes predate it. The doc is ahead of the deployed
  build.

## Still to do on this box

- The box is NAMED (`kuifje`, 2026-08-30: OS hostname, `dns.federation_name`,
  the zenit-dev deploy target and the peer rows on the other two boxes). Still
  open: set `network.main_url` and `auth.external_base_url` to its public name,
  and give it a site + certificate for the panel.
- Delegate at the registrar with matching glue -- the peering and the
  `starfleet.life` replica are done (see above), the delegation is not.
- Open the provider firewall for 80 and 443; keep 3000 closed. 53 udp+tcp is
  already open and proven. The 3000 exposure was closed IN THE PRODUCT on
  2026-08-30 (see "Panel exposure" below); the firewall work for 80/443 is
  still to do.
- Add it to `~/.config/zenit-dev/config.json` under `deployments` so
  `zenit-dev deployed <name>` can read its build stamp over ssh. DONE
  2026-08-29, renamed to target `kuifje` on 2026-08-30 (`debian@137.74.171.228`; `unzip -p` and
  `systemctl show` both work unprivileged, so no sudo is needed for the read).

## Deploy 2026-08-29 (first jar swap): `1c8a8a8b`, migrations M004 + M006

Shipped hohenheim `1c8a8a8b` (previous `d17494d2`) right after starfleet got
the same jar (`deploy-starfleet.md`, tenth deploy): the isolated worktree
`build-worktrees/deploy-20260829-1c8a8a8b`, stamp 13/13 clean, sha256
`5ff10f8b9e0cb947a13ac4ffda07e3ce489232940e7baa0f7cac383c07483b2b`,
267,595,641 bytes, gated by `upload_file`'s `grep -c false | grep -qx 13`.

This box is not root over ssh, so the whole lane is `sudo -n`: preflight
`/root/hohenheim-preflight-20260829-tenth/` (`hohenheim.db.pre`, `.at-swap`,
`settings/`, `hohenheim-server.jar.rollback`, keyring sha256 equal), rehearsal
as the service user from `/opt/hohenheim-rehearsal-20260829-tenth` (a byte
copy; `--run-migrations` printed `Running migration 004 DNS federation health`,
`006 Template-declared managed databases`, `Migrations complete 2 applied`, 45
rows; inert boot on 13999 healthy after 11 s, `roles_captured enabled=[]`, 0
exceptions; the dir was removed afterwards). TRAP: the live `hohenheim.dry`
here is DRY text written by the panel's settings editor (`i443`-style integer
prefixes), NOT JSON; a rehearsal copy is easiest written by hand as plain JSON,
which the loader also accepts.

Live: at-swap `.backup` (integrity ok), `install` beside, `systemctl stop`,
`mv`, `--run-migrations` as `hohenheim` from `/opt/hohenheim` (2 applied, 45
rows), `systemctl start`; 15 s to health. Second restart 11 s. Verified: 0
journal errors, `roles_captured [dns, firewall, proxy]`, listeners 53/80/3000,
`SecondaryZoneService: restored persisted replica of starfleet.life serial 34`
on the first boot, `dig @127.0.0.1 starfleet.life SOA` = the primary's serial,
google REFUSED, login page 200 over loopback, panel over the forward: dashboard
attention is only the pre-existing "No off-host backup destination", peer
`starfleet` listed, zone `starfleet.life` Secondary / Transferred.
`zenit-dev deployed kuifje` = `current` 13/13.

Federation after the deploy (the first run of the health tier on a real WAN):
a TXT added then deleted on starfleet moved the serial 34 -> 35 -> 36; this box
journaled `transferred secondary zone starfleet.life serial 35` within 3 s of
the add and `serial 36` within 3 s of the delete, `hoh-dns-diff compare` was
IDENTICAL at every step, and starfleet's Secondaries tab filled its new
columns (see its runbook).

ROLLBACK IS DB + JAR (the M002 rule from starfleet): stop, restore
`/root/hohenheim-preflight-20260829-tenth/hohenheim.db.at-swap` over
`hohenheim.db` (drop `-wal`/`-shm`), restore `hohenheim-server.jar.rollback`,
start. The only writes since the swap are federation bookkeeping.

## Deploy 2026-08-29 (second jar swap): `b486427f`, migration M007

Shipped hohenheim `b486427f` (previous `1c8a8a8b`) right after starfleet
(`deploy-starfleet.md`, eleventh deploy): same worktree, stamp 13/13 clean,
sha256 `f9d9b2d0412438f0537494b14029be6f5d79baee119dd1b019cf8f113c188c07`,
267,596,947 bytes, gated by `upload_file`'s `grep -c false | grep -qx 13`.

The whole lane is `sudo -n`, as before: preflight
`/root/hohenheim-preflight-20260830-eleventh/` (`.pre`, `.at-swap`, `settings/`,
`hohenheim-server.jar.rollback`, keyring sha256 equal), rehearsal as the
service user from `/opt/hohenheim-rehearsal-20260830-eleventh` on a byte copy
with a hand-written JSON `hohenheim.dry` (the live one is panel-written DRY
text): `Running migration 007 DNS NOTIFY serial trace`, `Migrations complete 1
applied`, 46 rows, `last_notify_serial` present, inert boot on 13999 healthy
after 10 s with `-Xmx512m`, `/login` 200, 0 exceptions, dir removed.

Live: at-swap `.backup` (integrity ok), `install` beside, `systemctl stop`,
`mv`, `--run-migrations` as `hohenheim` (1 applied, 46 rows), `systemctl
start`; 15 s to health. Second restart 10 s. Verified: 0 journal errors
(`journalctl -q -p err`), `roles_captured [dns, firewall, proxy]`, listeners
53/80/3000, `restored persisted replica of starfleet.life serial 36` on the
first boot, `dig @127.0.0.1 starfleet.life SOA` = the primary's serial, google
REFUSED, login page 200 over loopback; over a loopback curl session the
dashboard still carries only the pre-existing "No off-host backup destination"
attention item and `/admin/dns-zones` shows `starfleet.life Secondary
Transferred`. `zenit-dev deployed kuifje` = `current` 13/13. The staged jar in
`/home/debian` was removed.

Federation after the deploy: starfleet's disposable TXT add + delete moved the
serial 36 -> 37 -> 38; this box journaled `transferred secondary zone
starfleet.life serial 37` and `serial 38` within 3 s of each, served the TXT in
between and answers SOA 38 now, byte-identical to the primary.

ROLLBACK IS DB + JAR: `/root/hohenheim-preflight-20260830-eleventh/hohenheim.db.at-swap`
plus `hohenheim-server.jar.rollback`, as for the first swap.

## Panel exposure closed, 2026-08-30: `network.bind_address` = 127.0.0.1

`http://137.74.171.228:3000/` answered its login page FROM THE INTERNET. This
provider passes every port by default and the installer bound zenit's HTTP
listener to `0.0.0.0`, so an admin login page sat on a raw public port. Closed
exactly as robbedoes was (`deploy-robbedoes.md`, "Panel exposure"), in the
product rather than at a firewall this box cannot reach.

The live `settings/local.dry` here is still the installer's plain JSON (unlike
`hohenheim.dry`, which the panel's settings editor has since rewritten as DRY
text with `i443`-style prefixes -- CHECK BOTH before editing a settings file by
hand). One line added inside `network`, beside `port`:

    "bind_address": "127.0.0.1",

A copy of the pre-edit file is at `/root/hohenheim-local.dry.bak-20260830`, kept
OUT of `settings/` so the loader never sees it. Then `systemctl restart
hohenheim`. Undo by removing the line and restarting.

Verified after the restart:

- `ss -ltnp`: 3000 is now `[::ffff:127.0.0.1]:3000` only; `*:80` and `*:53`
  (udp+tcp) are unchanged, which is correct -- the proxy and the DNS server are
  separate listeners that loopback binding does not touch.
- From the workstation, `curl -m 5 http://137.74.171.228:3000/` fails to
  connect (exit 7) in 11 ms.
- Over loopback on the host, `/` still 302s to `/login`.
- `systemctl is-active` = active, `journalctl -u hohenheim -p err` = 0 lines,
  `roles_captured enabled=[dns, firewall, proxy]`,
  `restored persisted replica of starfleet.life serial 38` on the first boot.
- The secondary still serves: `starfleet.life` SOA serial 38 from this box and
  from the primary (104.223.42.142), identical; and a raw UDP AND TCP SOA query
  from the workstation to `137.74.171.228:53` answers `rcode=0 aa=1` with the
  same serial as the primary's, so nothing about the DNS role moved.
- `zenit-dev deployed kuifje` reads the same as before the restart: 12/13 repos
  `current`, `hohenheim local-ahead` (the pre-existing 2 undeployed commits),
  same jar mtime.

Since 2026-08-30 `tools/install-host.sh` seeds `network.bind_address` =
`127.0.0.1` (`--panel-bind` to override), so a NEW host arrives closed. That
does not help this box or robbedoes: the installer never rewrites an
existing settings file, which is why both needed the hand edit above.

## Named `kuifje`, 2026-08-30

The provisional `ovh` is gone. Servers are named after Franco-Belgian comic
heroes in Dutch (`docs/deploy-native.md`, "Naming"). Renamed in one wave, in
this order, because the announced federation name is what a receiver matches
its peer row by:

1. `dns.federation_name` = `kuifje` on this box (was `ovh`), settings editor.
2. The peer rows naming this box, on the same day: starfleet peer id 1
   `ovh` -> `kuifje`, robbedoes peer id 1 `ovh` -> `kuifje`. This box's own
   `sites` row (peer id 2) became `robbedoes`.
3. OS hostname and the zenit-dev deploy target, both already `kuifje`.

Nothing else on any row moved: transfer host/port, TSIG key name, algorithm,
base URL and API key are untouched, and a pure rename does NOT clear the stored
TSIG secret (the form's secret field stays blank-means-keep; only the "Clear
stored value" checkbox clears it -- the known clearing trap is the peer TYPE
change, not the name). The TSIG key names deliberately keep their old spelling
(`xfer-ovh-starfleet`, `xfer-sites-ovh`): a key name is wire identity, and
renaming one would need both sides rekeyed for no gain.

Proven end to end right after: a disposable
`visual-qa-20260830-rename.starfleet.life TXT` added and then deleted on
starfleet moved the serial 40 -> 41 -> 42, and all three boxes answered each
serial within seconds (`dig +norecurse` against 104.223.42.142, 137.74.171.228
and 51.255.43.81 agreed at every step, the TXT served then NXDOMAIN-free gone).
starfleet journaled `dns.notify_sent` naming `kuifje` AND `robbedoes`
(`noerror`) for both serials, `dns.axfr_served` for `xfer-ovh-starfleet` and
`xfer-sites-starfleet` (`ok`), the zone's **Check health** answered
`Delegation: matches. 2 secondaries probed, 0 behind.`, and its Secondaries tab
listed both peers `Current` at the new serial. No service was restarted.

## Deploy 2026-08-30 (third jar swap): `17fa6993`, no migration

Shipped hohenheim `17fa6993` (previous `b486427f`) right after starfleet
(`deploy-starfleet.md`, twelfth deploy): same worktree, stamp 13/13 clean,
sha256 `efbf9b35d7ab7f57fc2cedaff128d5670720ec249f95a243918a8e3c1b9d971a`,
267,604,365 bytes, `scp` + the `grep -c false | grep -qx 13` gate. Migration
diff: none.

Whole lane `sudo -n`: preflight `/root/hohenheim-preflight-20260830-twelfth/`
(`.pre`, `.at-swap`, `settings/`, `hohenheim-server.jar.rollback`, keyring
sha256 equal); rehearsal as the service user from
`/opt/hohenheim-rehearsal-20260830-twelfth` on a byte copy with hand-written
JSON settings: `Migrations complete 0 applied`, 46 rows, inert boot healthy
after 10 s (`-Xmx512m`), `/login` 200, 0 exceptions, dir removed. Live: at-swap
`.backup` (ok), `install` beside, stop, `mv`, `--run-migrations` as `hohenheim`
(0 applied, 46 rows), start; 10 s to health (15 s downtime). Second restart
13 s. 0 journal errors, `roles_captured [dns, firewall, proxy]`, listeners
`*:53`, `*:80`, `127.0.0.1:3000` (the loopback bind survived the swap),
`restored persisted replica of starfleet.life serial 42` on both boots,
`hoh-dns-diff compare` IDENTICAL against the primary, staged jar removed from
`/home/debian`. `zenit-dev deployed kuifje` = `current`.

Federation after the deploy: starfleet's disposable TXT add + delete moved the
serial 42 -> 43 -> 44; this box transferred 43 at 22:58:33.1Z and 44 at
22:59:08.3Z, each within 2 s of the edit, and answers SOA 44 byte-identical to
the primary.

ROLLBACK IS JAR ONLY (no schema change); the at-swap copy sits in the
preflight dir regardless.

## Nameserver zone deloecker.eu, 2026-08-30

The box got the domain its own nameserver names live in. `deloecker.eu` is
registered at Hetzner Robot and still hosted on Hetzner DNS; the decision was
`ns1.deloecker.eu` and `ns2.deloecker.eu`, BOTH pointing at kuifje for now (a
second address follows when robbedoes gets its own name). Nothing at the
registrar was touched: this wave only makes kuifje serve a faithful copy that
already carries the nameserver names.

Setting first (`dns.nameservers`, settings editor, DNS server group), verified
on disk in `/opt/hohenheim/settings/hohenheim.dry`:

    "dns":{...,"nameservers":["ns1.deloecker.eu","ns2.deloecker.eu"]}

### What public DNS held, 2026-08-30

Delegation (`hoh-dns-diff delegation deloecker.eu`): parent `eu.` publishes
`ns.second-ns.com`, `ns1.your-server.de`, `ns3.second-ns.de`, no glue (all
out-of-bailiwick), all three authoritative at serial `2026011500`, no DS.
VERDICT: DELEGATION OK.

AXFR is REFUSED at Hetzner, so the zone was RECONSTRUCTED by probing ~110
labels x 9 types against all three authoritative servers plus `1.1.1.1`; all
four agreed on every question. The draft is
`scratchpad/zones/deloecker.eu.zone`. Found, and nothing else: apex `A
213.133.104.4`, apex `MX 10 mail.deloecker.eu.`, apex `TXT "v=spf1 +a +mx
?all"`, `www A` and `mail A` (same address), `autoconfig CNAME
mail.your-server.de.`, `smtp`/`imap`/`pop CNAME mail.deloecker.eu.`, `ftp CNAME
www.deloecker.eu.`, `_autodiscover._tcp SRV 0 100 443 mail.your-server.de.`,
all TTL 7200. NO AAAA anywhere, no CAA, no `_dmarc`, no DKIM selector that any
of the ~15 guessed names hit, no wildcard (`randomprobe*.deloecker.eu` is
NXDOMAIN), no `ns1`/`ns2` today.

THIS COPY IS PROVISIONAL. A probe cannot enumerate a zone: any owner nobody
guessed -- a DKIM selector, a third-party verification TXT -- is missing from
it and would silently disappear at the cutover. The real Hetzner export must
be pasted into the zone's Zone-file tab before the delegation moves; the import
REPLACES every operator row, so it heals this copy rather than merging with it.

### The zone

Created through the panel as `deloecker.eu`, role primary, SOA MNAME
`ns1.deloecker.eu`, contact `hostmaster@deloecker.eu` -- kuifje zone id 2
(`starfleet.life` remains zone id 1). The create seeded exactly two apex NS
rows from the declared set (`ns1.deloecker.eu`, `ns2.deloecker.eu`), which the
Records tab showed before anything was imported.

Then the Zone-file tab's import ("Imported 17 records.", serial 1 -> 2) with
the 15 data rows above plus the four nameserver address rows; the checkbox was
left unchecked, so the file's apex NS set (deliberately absent from the pasted
text) was replaced by the declared one. The served zone file now reads:

    deloecker.eu.  3600 IN SOA ns1.deloecker.eu. hostmaster.deloecker.eu. 2 7200 3600 1209600 300
    deloecker.eu.  7200 IN A 213.133.104.4
    deloecker.eu.  7200 IN MX 10 mail.deloecker.eu.
    deloecker.eu.  7200 IN TXT "v=spf1 +a +mx ?all"
    deloecker.eu.  3600 IN NS ns1.deloecker.eu.
    deloecker.eu.  3600 IN NS ns2.deloecker.eu.
    _autodiscover._tcp.  7200 IN SRV 0 100 443 mail.your-server.de.
    autoconfig.    7200 IN CNAME mail.your-server.de.
    ftp.           7200 IN CNAME www.deloecker.eu.
    imap.          7200 IN CNAME mail.deloecker.eu.
    mail.          7200 IN A 213.133.104.4
    ns1.           3600 IN A 137.74.171.228
    ns1.           3600 IN AAAA 2001:41d0:305:2100::1:4afe
    ns2.           3600 IN A 137.74.171.228
    ns2.           3600 IN AAAA 2001:41d0:305:2100::1:4afe
    pop.           7200 IN CNAME mail.deloecker.eu.
    smtp.          7200 IN CNAME mail.deloecker.eu.
    www.           7200 IN A 213.133.104.4

Both nameserver names resolve to the SAME host today, exactly the shape
`dns-migration.md` calls out for `starfleet.life`: two NAMES, one HOST, no
redundancy until robbedoes gets an address of its own here.

### Replication

`dns_zone_peers` id 1 on kuifje links the zone to peer `robbedoes` (transfer
host 51.255.43.81), riding the TSIG key negotiated for `sites` earlier. On
robbedoes the same origin was created with role secondary and primary peer
`kuifje` -- robbedoes zone id 2 -- and pulled within 20 s of the save. Its
Records tab correctly says the primary peer has no admin base URL (kuifje's
panel is loopback-only), so records are shown read-only from the replica; that
is the known asymmetry from the starfleet federation, not a fault.

`compare` against the old provider (`--old 213.133.100.102 --new
137.74.171.228`, name list @,www,ns1,ns2,mail,smtp,imap,pop,ftp,autoconfig,
_dmarc,_autodiscover._tcp plus the draft zone file) is DIFFERENT (5), and every
one of the five is expected:

    deloecker.eu.  NS    apex-ns    ns.second-ns.com. | ns1.your-server.de. | ns3.second-ns.de.  ->  ns1.deloecker.eu. | ns2.deloecker.eu.
    deloecker.eu.  SOA   differs    ns1.your-server.de. postmaster.your-server.de. 2026011500 ...  ->  ns1.deloecker.eu. hostmaster.deloecker.eu. 2 ...
    ns1.deloecker.eu.  A     only-new  137.74.171.228
    ns1.deloecker.eu.  AAAA  only-new  2001:41d0:305:2100::1:4afe
    ns2.deloecker.eu.  A     only-new  137.74.171.228
    ns2.deloecker.eu.  AAAA  only-new  2001:41d0:305:2100::1:4afe

Every other question is `identical`; the only warnings are the apex NS/SOA TTL
(7200 vs 3600) and the serial, both reported and neither a difference.

`compare --old 137.74.171.228 --new 51.255.43.81 --strict` is IDENTICAL on all
17 questions, apex NS and SOA included. Both servers answer `aa=1` over UDP AND
TCP.

### Health

The zone row action **Check health** on kuifje answers:

    Delegation: delegated_not_listed. 1 secondaries probed, 0 behind.

with findings (zone form, Advanced, checked 2026-08-30 01:27):

    listed_not_delegated ns1.deloecker.eu
    listed_not_delegated ns2.deloecker.eu
    delegated_not_listed ns1.your-server.de
    delegated_not_listed ns.second-ns.com
    delegated_not_listed ns3.second-ns.de

That is the CORRECT pre-cutover verdict `dns-federation.md` predicts: the
parent still names Hetzner's three servers and we still publish only our two.
It becomes `matches` at the registrar step and not before. The Secondaries tab
shows `robbedoes` Current at served serial 2, probed just now, last AXFR served
serial 2. No service was restarted anywhere.

### Still pending at the registrar (NOT done here)

1. Paste the real Hetzner zone export into the Zone-file tab and re-run
   `compare` -- until then this zone is a probe reconstruction.
2. Register `ns1.deloecker.eu` and `ns2.deloecker.eu` as host objects / glue at
   Hetzner Robot (both in-bailiwick, so the `.eu` registry DEMANDS glue:
   137.74.171.228 + 2001:41d0:305:2100::1:4afe).
3. Change the delegation from Hetzner DNS to those two names, then
   `hoh-dns-diff delegation deloecker.eu --expect-ns ns1.deloecker.eu,ns2.deloecker.eu`
   and `compare --strict`, and keep Hetzner's zone alive for its SOA expire
   (3600000 s = 41 days) afterwards.
4. Give ns2 a genuinely separate host (robbedoes) so the two names stop sharing
   one machine.

## deloecker.eu zone removed, declared nameservers moved to mooo.com, 2026-08-30

`deloecker.eu` is out of scope again, so the zone created above is GONE: deleted
through the panel on kuifje (zone id 2, "Its 13 stored records go with it") and
on robbedoes (zone id 2, the replica, 0 stored records). Both boxes now answer
REFUSED for `deloecker.eu` and stay authoritative (`aa=1`) for `starfleet.life`,
which was not touched. No service was restarted.

The nameserver names moved off `deloecker.eu` before that: Hetzner konsoleH
registers no glue for a `.eu` domain, so in-bailiwick `ns1/ns2.deloecker.eu`
were impossible there. The declared set is now the pair Jelle created at
afraid.org -- `nskuifje.mooo.com` (kuifje, 137.74.171.228 /
2001:41d0:305:2100::1:4afe) and `nsrobbedoes.mooo.com` (robbedoes,
51.255.43.81 / 2001:41d0:305:2100::1:4b26) -- set as `dns.nameservers` on BOTH
controllers (robbedoes had an empty set before) and verified in
`/opt/hohenheim/settings/hohenheim.dry` on each:

    "dns":{"nameservers":["nskuifje.mooo.com","nsrobbedoes.mooo.com"], ...}

Neither name resolves yet: `ENOTFOUND` at 1.1.1.1 and `REFUSED` at
ns1.afraid.org, both at the start and at the end of this wave. A zone created
on either controller from now on gets these two apex NS rows, and a zone-file
import substitutes them, so the next migration inherits the new names with no
extra step. Nothing at any registrar was touched.

## Zone wcag.be staged, 2026-08-30

The second zone reconstructed onto these controllers, and the first created
under the `mooo.com` nameserver pair. `wcag.be` is registered elsewhere and
hosted on Hetzner DNS today; nothing at the registrar or at Hetzner was
touched. This wave only makes kuifje and robbedoes serve a faithful copy.

### What public DNS held, 2026-08-30

`hoh-dns-diff delegation wcag.be`: parent `be.` (answered by 194.0.6.1)
publishes `ns.second-ns.com`, `ns1.your-server.de`, `ns3.second-ns.de`, no glue
(all out-of-bailiwick), all three authoritative at serial `2026011500`, parent
set == apex set, no DS. VERDICT: DELEGATION OK. The IPv6 address of each of the
three was not probed (no route from the workstation), which is a warning about
the probe, not the zone.

AXFR is REFUSED (rcode 5) by all three, so the zone was RECONSTRUCTED by
probing 169 candidate owners x 9 types = 1521 questions against
213.239.204.242, 213.133.100.102, 193.47.99.4 and 1.1.1.1. All four agreed on
every question (0 disagreements, after retrying the queries Hetzner rate-limited
away on the first pass). Draft: `scratchpad/zones/wcag.be.zone`.

Found, and nothing else: apex `A 213.239.210.245` (merlina), apex `AAAA
2a01:4f8:a0:948c::2`, apex `TXT "v=spf1 +a +mx ?all"`, `www` and `api` CNAME to
the apex, `earl` (TTL 700) and `invulassistent` CNAME to `phoenix.develry.be.`
-- the two hostnames still on Phoenix. NO MX anywhere, even though the SPF says
`+mx`; no `_dmarc`, no DKIM selector among 15 guesses, no CAA, no SRV, no
`mail`/`smtp`/`imap`/`pop`/`autoconfig`/`autodiscover` owner, no AAAA below the
apex, and no wildcard (`randomprobe-a7f3q9` and `zz-wildcard-test-4471` are both
NXDOMAIN on all three servers). Everything except `earl` is TTL 7200.

THIS COPY IS PROVISIONAL, exactly as `deloecker.eu` was: a probe cannot
enumerate a zone. The real Hetzner export must be pasted into the Zone-file tab
before the delegation moves; the import REPLACES every operator row, so it heals
this copy rather than merging with it.

### The zones

kuifje zone id 3 (`starfleet.life` is 1; 2 was the deleted `deloecker.eu`),
role primary, SOA MNAME `nskuifje.mooo.com`, contact `hostmaster@wcag.be`. The
create seeded exactly two apex NS rows from the declared set --
`nskuifje.mooo.com` and `nsrobbedoes.mooo.com` -- which the Records tab showed
before anything was imported. Both names are OUT of bailiwick, so this zone
needs no glue rows and none were added.

Then the Zone-file tab's import ("Imported 9 records.", serial 1 -> 2) with the
7 data rows; the keep-nameservers checkbox was left unchecked and the pasted
text deliberately carried no apex NS set. The served zone file:

    wcag.be.                3600 IN SOA nskuifje.mooo.com. hostmaster.wcag.be. 2 7200 3600 1209600 300
    wcag.be.                7200 IN A 213.239.210.245
    wcag.be.                7200 IN AAAA 2a01:4f8:a0:948c:0:0:0:2
    wcag.be.                7200 IN TXT "v=spf1 +a +mx ?all"
    wcag.be.                3600 IN NS nskuifje.mooo.com.
    wcag.be.                3600 IN NS nsrobbedoes.mooo.com.
    api.wcag.be.            7200 IN CNAME wcag.be.
    earl.wcag.be.            700 IN CNAME phoenix.develry.be.
    invulassistent.wcag.be. 7200 IN CNAME phoenix.develry.be.
    www.wcag.be.            7200 IN CNAME wcag.be.

`dns_zone_peers` id 2 on kuifje links the zone to peer `robbedoes` (transfer
host 51.255.43.81), riding the TSIG key negotiated for `sites` earlier. On
robbedoes the same origin was created with role secondary and primary peer
`kuifje` -- robbedoes zone id 3 -- and pulled 6 seconds after the save
(`DNS: transferred secondary zone wcag.be serial 2`, `transfer_status ok`). Its
Records tab shows the replica read-only, the known asymmetry (kuifje's panel is
loopback-only, so the peer has no admin base URL).

### Compare

`compare wcag.be --old 213.133.100.102 --new 137.74.171.228 --zone-file
<draft> --names @,www,api,earl,invulassistent,mail,autoconfig,_dmarc,
_autodiscover._tcp` is DIFFERENT (1), and both non-identical rows are expected:

    wcag.be.  NS   apex-ns  ns.second-ns.com. | ns1.your-server.de. | ns3.second-ns.de.  ->  nskuifje.mooo.com. | nsrobbedoes.mooo.com.
    wcag.be.  SOA  differs  ns1.your-server.de. postmaster.your-server.de. 2026011500 ...  ->  nskuifje.mooo.com. hostmaster.wcag.be. 2 ...

Every other question is `identical` (apex A/AAAA/TXT, www/api/earl/
invulassistent CNAME); the only warnings are the apex NS/SOA TTL (7200 vs 3600)
and the serial, both reported and neither a difference.

`compare --old 137.74.171.228 --new 51.255.43.81 --strict` is IDENTICAL on all
9 questions, apex NS and SOA included. Both servers answer `rcode=0 aa=1` for
the zone SOA over UDP AND TCP from the workstation.

### Health

The zone row action **Check health** on kuifje answers, verbatim:

    Delegation: delegated_not_listed. 1 secondaries probed, 0 behind.

with findings (zone form, Advanced, checked 2026-08-30 01:50):

    listed_not_delegated nskuifje.mooo.com
    listed_not_delegated nsrobbedoes.mooo.com
    delegated_not_listed ns.second-ns.com
    delegated_not_listed ns1.your-server.de
    delegated_not_listed ns3.second-ns.de

That is the CORRECT pre-cutover verdict: the parent still names Hetzner's three
servers and we publish only our two. It becomes `matches` at the registrar step
and not before. The Secondaries tab shows `robbedoes` Current at served serial
2, probed 35 seconds earlier, last AXFR served serial 2.

NEITHER NAMESERVER NAME RESOLVES YET: `nskuifje.mooo.com` and
`nsrobbedoes.mooo.com` are both NXDOMAIN for A and AAAA at 1.1.1.1, measured at
the end of this wave -- the same state the deloecker.eu wave recorded an hour
earlier. Moving the delegation before they resolve would take the domain
offline, so step 3 below is genuinely blocking.

No service was restarted anywhere; no site, certificate or registrar setting was
touched.

### Still pending (NOT done here)

1. Paste the real Hetzner zone export into the Zone-file tab and re-run
   `compare` -- until then this zone is a probe reconstruction.
2. Make `nskuifje.mooo.com` and `nsrobbedoes.mooo.com` resolve at afraid.org.
3. Jelle sets those two names as `wcag.be`'s nameservers in konsoleH (no glue:
   out-of-bailiwick).
4. `hoh-dns-diff delegation wcag.be --expect-ns nskuifje.mooo.com,nsrobbedoes.mooo.com`
   and `propagate www.wcag.be CNAME --expect wcag.be.`, then `compare --strict`
   against a Hetzner server, and keep Hetzner's zone alive for its SOA expire
   (3600000 s = 41 days) afterwards.

## Zone tavernetomberg.be staged, 2026-08-30

The third zone reconstructed onto these controllers. `tavernetomberg.be` is
registered elsewhere and hosted on Hetzner DNS today; nothing at the registrar
or at Hetzner was touched. This wave only makes kuifje and robbedoes serve a
faithful copy.

### THE DECLARED NAMESERVERS MOVED MID-WAVE -- read this first

`dns.nameservers` was `nskuifje.mooo.com` / `nsrobbedoes.mooo.com` on both
controllers when this wave started (verified in
`/opt/hohenheim/settings/hohenheim.dry` at 23:55 UTC). ANOTHER SESSION changed
it to `ns1.elevenways.de` / `ns2.elevenways.de` on kuifje at 23:57:21 UTC and on
robbedoes at 23:58 UTC -- between that check and the first zone create -- and
created kuifje zone id 4, `elevenways.de`, the zone those names will live in.
So the two zones below carry `ns1/ns2.elevenways.de` as their apex NS set, and
their SOA MNAME was set to `ns1.elevenways.de` to match. That is the DECLARED
set doing exactly what it is for: a zone created now inherits the current names
and a zone-file import substitutes them. That session then moved `wcag.be`
(zone 3) onto the same pair -- verified here after the fact, `wcag.be` and
`elevenways.de` both answer `ns1.elevenways.de. | ns2.elevenways.de.` from
kuifje -- so all four zones staged on these controllers now publish ONE
nameserver naming. (`starfleet.life`, zone 1, is a secondary of the starfleet
federation and keeps its own `nssl/nssl2.mooo.com` pair; that is not ours.)

Measured at the end of this wave, and it is the wrong way round today:

    nskuifje.mooo.com     A 137.74.171.228  AAAA 2001:41d0:305:2100::1:4afe
    nsrobbedoes.mooo.com  A 51.255.43.81    AAAA (no data)
    ns1.elevenways.de     NXDOMAIN (A and AAAA)
    ns2.elevenways.de     NXDOMAIN (A and AAAA)

The mooo.com pair now RESOLVES (it did not an hour earlier, which is what the
wcag.be section recorded), except that `nsrobbedoes.mooo.com` has no AAAA --
confirmed NODATA at 1.1.1.1, 8.8.8.8 and 9.9.9.9. The pair these two zones
publish does not resolve at all yet. Moving either delegation before its names
resolve takes the domain offline, so step 2 below is genuinely blocking.

### What public DNS held, 2026-08-30

`hoh-dns-diff delegation tavernetomberg.be`: parent `be.` (answered by
194.0.6.1) publishes `ns.second-ns.com`, `ns1.your-server.de`,
`ns3.second-ns.de`, no glue (all out-of-bailiwick), all three authoritative at
serial `2026011500`, parent set == apex set, no DS. VERDICT: DELEGATION OK. The
IPv6 address of each of the three was not probed (no route from the
workstation), which is a warning about the probe, not the zone.

AXFR is REFUSED (rcode 5) by all three, so the zone was RECONSTRUCTED by probing
226 candidate owners x 9 types = 2034 questions against 213.239.204.242,
213.133.100.102, 193.47.99.4 and 1.1.1.1 -- 8136 answers, all four agreeing on
every question (0 disagreements, after retrying what Hetzner rate-limited away).
Draft: `scratchpad/zones/tavernetomberg.be.zone`.

Found, and nothing else: apex `A 144.76.30.204` (Phoenix), apex `AAAA
2a01:4f8:191:21cb::2`, apex `MX 10 calamity.develry.be.`, apex `TXT "v=spf1 +a
+mx ?all"`, `www` CNAME to the apex, and three client-autoconfig SRV records --
`_autodiscover._tcp 0 100 443`, `_imaps._tcp 0 100 993`, `_submission._tcp
0 100 587`, all to `calamity.develry.be.`. Everything is TTL 7200.

This zone CARRIES MAIL, so the mail owners were probed exhaustively, and the
absences are facts rather than omissions: `mail`, `smtp`, `imap`, `pop`,
`webmail`, `mx`, `autoconfig` and `autodiscover` are all NXDOMAIN on all three
authoritative servers, so the MX and the three SRV targets are the ONLY mail
names this zone publishes. No `_dmarc` (NXDOMAIN), and no DKIM selector among 48
guesses (`google`, `k1`, `k2`, `default`, `mail`, `selector1/2`, `dkim`,
`s1/s2`, `pm`, `mailcoach`, `postmark`, the dated `YYYYMMDD...pm` shapes, ...).
No `mta-sts`, `_mta-sts` or `_smtp._tls`. No CAA, no `google-site-verification`
TXT at the apex (the apex TXT is SPF only), no AAAA below the apex, and no
wildcard (`randomprobe-a7f3q9` and `zz-wildcard-test-4471` are both NXDOMAIN on
all three servers).

THIS COPY IS PROVISIONAL, exactly as `wcag.be` was: a probe cannot enumerate a
zone. The real Hetzner export must be pasted into the Zone-file tab before the
delegation moves; the import REPLACES every operator row, so it heals this copy
rather than merging with it.

### The zones

kuifje zone id 5 (1 `starfleet.life`, 3 `wcag.be`, 4 the other session's
`elevenways.de`), role primary, contact `hostmaster@tavernetomberg.be`. The
create seeded exactly two apex NS rows from the declared set --
`ns1.elevenways.de` and `ns2.elevenways.de` -- which the Records tab showed
before anything was imported. Both names are OUT of bailiwick (a different
domain from this zone), so this zone needs no glue rows and none were added.

Then the Zone-file tab's import ("Imported 10 records.", serial 1 -> 2) with the
8 data rows; the keep-nameservers checkbox was left unchecked and the pasted
text deliberately carried no apex NS set. A first import attempt FAILED with
`Import failed: <none>:1: expected EOL or EOF` -- the browser driver's
set_value collapses newlines in a textarea, so the whole file arrived as one
line; setting `textarea.value` directly and dispatching `input` is the way in.
The SOA MNAME was then corrected from the create-form default to
`ns1.elevenways.de` so the MNAME names a server the NS set actually lists
(serial 2 -> 3). The served zone file:

    tavernetomberg.be.                   3600 IN SOA ns1.elevenways.de. hostmaster.tavernetomberg.be. 3 7200 3600 1209600 300
    tavernetomberg.be.                   7200 IN A 144.76.30.204
    tavernetomberg.be.                   7200 IN AAAA 2a01:4f8:191:21cb:0:0:0:2
    tavernetomberg.be.                   7200 IN MX 10 calamity.develry.be.
    tavernetomberg.be.                   7200 IN TXT "v=spf1 +a +mx ?all"
    tavernetomberg.be.                   3600 IN NS ns1.elevenways.de.
    tavernetomberg.be.                   3600 IN NS ns2.elevenways.de.
    _autodiscover._tcp.tavernetomberg.be. 7200 IN SRV 0 100 443 calamity.develry.be.
    _imaps._tcp.tavernetomberg.be.       7200 IN SRV 0 100 993 calamity.develry.be.
    _submission._tcp.tavernetomberg.be.  7200 IN SRV 0 100 587 calamity.develry.be.
    www.tavernetomberg.be.               7200 IN CNAME tavernetomberg.be.

`dns_zone_peers` id 3 on kuifje links the zone to peer `robbedoes` (transfer
host 51.255.43.81). On robbedoes the same origin was created with role secondary
and primary peer `kuifje` -- robbedoes zone id 4 -- and pulled within a minute.
Its Records tab shows the replica read-only, the known asymmetry (kuifje's panel
is loopback-only, so the peer has no admin base URL).

### Compare

`compare tavernetomberg.be --old 213.133.100.102 --new 137.74.171.228
--zone-file <draft> --names @,www,mail,smtp,imap,pop,webmail,mx,autoconfig,
autodiscover,_dmarc,mta-sts,_mta-sts,_smtp._tls,google._domainkey,
k1._domainkey,default._domainkey,selector1._domainkey,_autodiscover._tcp,
_imaps._tcp,_submission._tcp` is DIFFERENT (1) over 21 names, and both
non-identical rows are expected:

    tavernetomberg.be.  NS   apex-ns  ns.second-ns.com. | ns1.your-server.de. | ns3.second-ns.de.  ->  ns1.elevenways.de. | ns2.elevenways.de.
    tavernetomberg.be.  SOA  differs  ns1.your-server.de. postmaster.your-server.de. 2026011500 ...  ->  ns1.elevenways.de. hostmaster.tavernetomberg.be. 3 ...

Every other question is `identical` -- apex A/AAAA/MX/TXT, `www` CNAME and all
three SRV rows, printed in full:

    _autodiscover._tcp.tavernetomberg.be.  SRV  identical  0 100 443 calamity.develry.be.
    _imaps._tcp.tavernetomberg.be.         SRV  identical  0 100 993 calamity.develry.be.
    _submission._tcp.tavernetomberg.be.    SRV  identical  0 100 587 calamity.develry.be.
    tavernetomberg.be.                     MX   identical  10 calamity.develry.be.
    tavernetomberg.be.                     TXT  identical  "v=spf1 +a +mx ?all"

Every mail owner in the `--names` list produced NO ROW at all, which is the
proof they are absent on BOTH sides. The only warnings are the apex NS/SOA TTL
(7200 vs 3600) and the serial, both reported and neither a difference.

`compare --old 137.74.171.228 --new 51.255.43.81 --strict` is IDENTICAL on all
10 questions, apex NS and SOA included. Both servers answer `rcode=0 aa=1` for
the zone SOA over UDP AND TCP from the workstation.

### Health

The zone row action **Check health** on kuifje answers, verbatim:

    Delegation: delegated_not_listed. 1 secondaries probed, 0 behind.

with findings (zone form, Advanced, checked 2026-08-30 02:03):

    listed_not_delegated ns1.elevenways.de
    listed_not_delegated ns2.elevenways.de
    delegated_not_listed ns.second-ns.com
    delegated_not_listed ns1.your-server.de
    delegated_not_listed ns3.second-ns.de

That is the CORRECT pre-cutover verdict: the parent still names Hetzner's three
servers and we publish only our two. It becomes `matches` at the registrar step
and not before. The Secondaries tab shows `robbedoes` Current at served serial
3, probed 38 seconds earlier, last AXFR served serial 3.

No service was restarted anywhere; no site, certificate or registrar setting was
touched, and no record VALUE was changed -- the apex still points at Phoenix.

### Still pending (NOT done here)

1. Paste the real Hetzner zone export into the Zone-file tab and re-run
   `compare` -- until then this zone is a probe reconstruction.
2. Make `ns1.elevenways.de` and `ns2.elevenways.de` resolve -- they were still
   NXDOMAIN at 1.1.1.1 when this wave ended. That is the other session's
   `elevenways.de` wave; see its own section above.
3. Jelle sets the resolved pair as `tavernetomberg.be`'s nameservers at the
   registrar (out-of-bailiwick, so no glue).
4. `hoh-dns-diff delegation tavernetomberg.be --expect-ns <pair>` and
   `propagate www.tavernetomberg.be CNAME --expect tavernetomberg.be.`, then
   `compare --strict` against a Hetzner server, and keep Hetzner's zone alive
   for its SOA expire (3600000 s = 41 days) afterwards.

## Zone elevenways.be staged, 2026-08-30

The company zone, and by far the richest reconstructed here. `elevenways.be` is
hosted on Hetzner DNS today; nothing at the registrar or at Hetzner was touched.
The declared-nameserver change described in the `tavernetomberg.be` section
above applies to this zone identically -- it publishes `ns1/ns2.elevenways.de`,
which do not resolve yet. NOTE that `elevenways.be` (this zone, a `.be`) and
`elevenways.de` (kuifje zone 4, the other session's nameserver-hosting zone) are
DIFFERENT domains; the nameserver names are therefore still out of bailiwick
here and this zone needs no glue rows.

### What public DNS held, 2026-08-30

`hoh-dns-diff delegation elevenways.be`: parent `be.` (answered by 194.0.6.1)
publishes `ns.second-ns.com`, `ns1.your-server.de`, `ns3.second-ns.de`, no glue,
all three authoritative at serial `2026081300`, parent set == apex set, no DS.
VERDICT: DELEGATION OK. IPv6 not probed (no route from the workstation).

AXFR is REFUSED (rcode 5) by all three, so the zone was RECONSTRUCTED by probing
in THREE passes -- 521 candidate owners x 9 types = 4689 questions against
213.239.204.242, 213.133.100.102, 193.47.99.4 and 1.1.1.1, so 18756 answers,
all four agreeing on every question (0 disagreements). Draft:
`scratchpad/zones/elevenways.be.zone`.

The passes were driven by what the previous one found, and that is the method
worth reusing. Pass 1 (233 owners, the generic vocabulary plus the names in the
brief) hit `merlina`, `alchemy`, `thoth`, `microcopy`, `spamservice`, `matrix`,
`staging` and `clients` -- which says the zone names PROJECTS and HOSTS. Pass 2
(174 owners: the develry host names and the whole workspace project vocabulary)
added `protoblast`, `proteus`, `arcana` and the BIMI record. Pass 3 (114 owners,
the remaining project names) found NOTHING new, which is the closest thing to
convergence a probe can offer. A single generic pass would have missed four
real records.

Found, and nothing else -- everything TTL 600:

    @              A     213.239.210.245        (merlina)
    @              AAAA  2a01:4f8:a0:948c::2
    @              MX    5 alt1.aspmx.l.google.com. / 5 alt2.aspmx.l.google.com. /
                         10 aspmx.l.google.com. / 10 aspmx2.googlemail.com. /
                         10 aspmx3.googlemail.com.
    @              TXT   "v=spf1 mx a ptr include:my.billit.be include:_spf.google.com -all"
    @              TXT   "MS=EFC28C80EE7E3B211078D4A9E0CC5D974460942E"
    @              TXT   "apple-domain-verification=AnYtarspxURaWI04FuQBeYv1NbeVQjh_whfBFaB9iJ4"
    @              TXT   "asv=781516fc186a8f448a767cc7019b7c60"
    @              TXT   "google-site-verification=2ECHKyakip-Pm5sy9UPWgpi9P-gfIDfWx3zS-Epo9m8"
    @              TXT   "google-site-verification=Cbf6qAArtUNlqealDb5Z2fGh8O4FY5nzT5S9JuqrOUA"
    @              TXT   "mailcoach-verification=249ee053-ecea-498c-bc13-24ce458be37b"
    @              TXT   "openai-domain-verification=dv-y2UVRFgAxCiU3yv2ywHJaHKh"
    _dmarc         TXT   "v=DMARC1; p=quarantine; rua=mailto:jelle+dmarc@elevenways.be; ruf=mailto:jelle+dmarc@elevenways.be; pct=100"
    default._bimi  TXT   "v=BIMI1;l=https://s3.wasabisys.com/elevenways-manual/bimi/elevenways.svg"
    google._domainkey TXT  a two-string DKIM1 RSA key (2048-bit, split across two
                           character-strings as the wire format requires)
    matrix         A     213.239.210.245
    matrix         AAAA  2a01:4f8:a0:948c::2
    www            CNAME merlina.develry.be.
    alchemy arcana clients merlina protoblast proteus spamservice staging thoth
                   CNAME merlina.develry.be.
    microcopy      CNAME phoenix.develry.be.

This zone carries mail on Google Workspace and was probed exhaustively for it.
The absences are facts: `mail`, `smtp`, `imap`, `pop`, `webmail`, `mx`,
`autoconfig`, `autodiscover` are all NXDOMAIN on all three authoritative
servers, and there is no `_autodiscover._tcp` SRV -- Workspace clients discover
Google directly, so nothing is missing. No `mta-sts`, `_mta-sts`, `_smtp._tls`
or `_tlsrpt`. `google` is the ONLY DKIM selector: 60+ others were asked,
including `k1/k2/k3`, `default`, `mail`, `selector1/2`, `dkim`, `s1/s2`, `pm`,
`mailcoach`, `postmark`, the dated `YYYYMMDD...pm` shapes and per-service
guesses (`billit`, `spamservice`, `merlina`). No CAA anywhere and no SRV of any
kind -- the `_matrix._tcp` family included, even though `matrix.elevenways.be`
exists. Of the names named in the brief, `qr`, `herald`, `comms`, `api`, `app`,
`admin`, `git`, `ci`, `vpn`, `ns1`, `ns2`, `calendar`, `docs`, `drive`, `cdn`
and `preview` are all NXDOMAIN. No AAAA except at the apex and on `matrix`, and
no wildcard (`randomprobe-a7f3q9`, `zz-wildcard-test-4471` and
`zz-third-pass-55817` are all NXDOMAIN on all three servers).

THIS COPY IS PROVISIONAL. Three converging passes raise the confidence; they do
not make it an enumeration. The real Hetzner export must be pasted into the
Zone-file tab before the delegation moves.

### The zones

kuifje zone id 6, role primary, SOA MNAME `ns1.elevenways.de`, contact
`hostmaster@elevenways.be`. The create seeded the two declared apex NS rows.
Then the Zone-file tab's import ("Imported 33 records.", serial 1 -> 2) with the
31 data rows; the keep-nameservers checkbox was left unchecked and the pasted
text carried no apex NS set. The two-string DKIM value survived the round trip
byte-for-byte, which the compare below proves.

`dns_zone_peers` id 5 on kuifje links the zone to peer `robbedoes`. On robbedoes
the same origin was created with role secondary and primary peer `kuifje` --
robbedoes zone id 6 -- and answered REFUSED for two polls before going
authoritative at serial 2 roughly 25 seconds after the save.

### Compare

`compare elevenways.be --old 213.133.100.102 --new 137.74.171.228 --zone-file
<draft>` over 52 names (the draft's owners plus every mail, DKIM and brief-named
label) is DIFFERENT (1), and both non-identical rows are expected:

    elevenways.be.  NS   apex-ns  ns.second-ns.com. | ns1.your-server.de. | ns3.second-ns.de.  ->  ns1.elevenways.de. | ns2.elevenways.de.
    elevenways.be.  SOA  differs  ns1.your-server.de. postmaster.your-server.de. 2026081300 ...  ->  ns1.elevenways.de. hostmaster.elevenways.be. 2 ...

Every other question is `identical`. The mail rows explicitly:

    elevenways.be.                    MX   identical  10 aspmx.l.google.com. | 10 aspmx2.googlemail.com. | 10 aspmx3.googlemail.com. | 5 alt1.aspmx.l.google.com. | 5 alt2.aspmx.l.google.com.
    elevenways.be.                    TXT  identical  "MS=EFC28C80EE7E3B211078D4A9E0CC5D974460942E" | "apple-domain-verification=AnYtarspxURaWI04FuQBeYv1NbeVQjh_whfBFaB9iJ4" | "asv=781516fc186a8f448a767cc7019b7c60" | "google-site-verification=2ECHKyakip-Pm5sy9UPWgpi9P-gfIDfWx3zS-Epo9m8" | "google-site-verification=Cbf6qAArtUNlqealDb5Z2fGh8O4FY5nzT5S9JuqrOUA" | "mailcoach-verification=249ee053-ecea-498c-bc13-24ce458be37b" | "openai-domain-verification=dv-y2UVRFgAxCiU3yv2ywHJaHKh" | "v=spf1 mx a ptr include:my.billit.be include:_spf.google.com -all"
    _dmarc.elevenways.be.             TXT  identical  "v=DMARC1; p=quarantine; rua=mailto:jelle+dmarc@elevenways.be; ruf=mailto:jelle+dmarc@elevenways.be; pct=100"
    default._bimi.elevenways.be.      TXT  identical  "v=BIMI1;l=https://s3.wasabisys.com/elevenways-manual/bimi/elevenways.svg"
    google._domainkey.elevenways.be.  TXT  identical  "v=DKIM1; k=rsa; p=MIIBIjANBgkq..." "fJHG95/Nr1fthon4..."

and the host rows: `www`, `alchemy`, `arcana`, `clients`, `merlina`, `proteus`,
`protoblast`, `spamservice`, `staging`, `thoth` all `identical
merlina.develry.be.`, `microcopy` `identical phoenix.develry.be.`, `matrix`
`identical` on A and AAAA. Every absent label produced no row on either side.
The only warnings are the apex NS/SOA TTL (600 vs 3600) and the serial.

`compare --old 137.74.171.228 --new 51.255.43.81 --strict` is IDENTICAL on all
22 questions, apex NS and SOA included -- the DKIM row included, so the split
character-strings replicate intact. Both servers answer `rcode=0 aa=1` for the
zone SOA over UDP AND TCP.

### Health

**Check health** on kuifje answers, verbatim:

    Delegation: delegated_not_listed. 1 secondaries probed, 0 behind.

with findings (zone form, Advanced, checked 2026-08-30 02:11):

    listed_not_delegated ns1.elevenways.de
    listed_not_delegated ns2.elevenways.de
    delegated_not_listed ns.second-ns.com
    delegated_not_listed ns1.your-server.de
    delegated_not_listed ns3.second-ns.de

The CORRECT pre-cutover verdict. The Secondaries tab shows `robbedoes` Current
at served serial 2, probed just now, last AXFR served serial 2.

No service was restarted anywhere; no site, certificate or registrar setting was
touched, and no record VALUE was changed -- the apex and `www` still point at
merlina and `microcopy` still points at Phoenix.

### Still pending (NOT done here)

1. Paste the real Hetzner zone export into the Zone-file tab and re-run
   `compare` -- until then this zone is a probe reconstruction, however well the
   three passes converged.
2. Settle the nameserver names (see the `tavernetomberg.be` section): today this
   zone publishes `ns1/ns2.elevenways.de` and neither resolves.
3. Jelle sets the resolved pair as `elevenways.be`'s nameservers at the
   registrar. This is the COMPANY zone and it carries Google Workspace mail, so
   it should be the LAST of the three to move, after `wcag.be` and
   `tavernetomberg.be` have proven the lane.
4. `hoh-dns-diff delegation elevenways.be --expect-ns <pair>` and
   `propagate www.elevenways.be CNAME --expect merlina.develry.be.`, then
   `compare --strict` against a Hetzner server, and keep Hetzner's zone alive
   for its SOA expire (3600000 s = 41 days) afterwards.

## Nameserver zone elevenways.de, 2026-08-30

The domain that carries the nameserver NAMES. `elevenways.de` is registered at
Hetzner (konsoleH); the registrar REFUSED the two glue lines
(`ns1.elevenways.de 137.74.171.228 2001:41d0:305:2100::1:4afe`,
`ns2.elevenways.de 51.255.43.81 2001:41d0:305:2100::1:4b26`) because DENIC's
pre-delegation check (NAST) demands that both listed nameservers already answer
the zone authoritatively over those glue addresses. Nothing served it. This wave
makes both controllers serve it. No registrar setting was touched.

### Declared nameservers, BOTH boxes

`dns.nameservers` moved from the `mooo.com` pair to `ns1.elevenways.de`,
`ns2.elevenways.de` through the settings editor on kuifje AND robbedoes, each
verified in `/opt/hohenheim/settings/hohenheim.dry`:

    "dns":{"nameservers":["ns1.elevenways.de","ns2.elevenways.de"], ...}

Every primary zone created from now on seeds those two apex NS rows, and a
zone-file import substitutes them.

### The zones

kuifje zone id 4, role primary, SOA MNAME `ns1.elevenways.de`, contact
`hostmaster@elevenways.de`; the create seeded exactly the two declared apex NS
rows. robbedoes zone id 5, role secondary, primary peer `kuifje`;
`dns_zone_peers` id 4 on kuifje links zone 4 to peer `robbedoes`. The replica
pulled within a minute of the save and has tracked every serial since.

SOA retry was lowered 3600 -> 1800 on the zone form (Advanced): DENIC's own
check WARNED `110 Retry value out of range, expected [900..2400], found 3600`.
That was the only complaint of the first run and it is gone.

### THE TRAP: this is NOT an empty nameserver-only domain

`elevenways.de` is delegated at DENIC to Hetzner DNS today (`ns.second-ns.com`,
`ns1.your-server.de`, `ns3.second-ns.de`, serial 2026082900) and that zone
SERVES A LIVE SITE AND MAIL. Under `.de` there is no separate host object: glue
for an in-bailiwick nameserver is carried in the domain's OWN nserver list, so
entering those two glue lines IS a delegation change. Had this zone stayed the
4 nameserver address rows the brief asked for, the site and the mail would have
gone dark the moment the registrar accepted them.

AXFR is REFUSED at Hetzner, so the zone was probed (40 candidate labels x 9
types against 213.133.100.102). Found, and nothing else: apex `A
88.198.219.246`, apex `AAAA 2a01:4f8:d0a:27bd::2`, apex `MX 10
www4.your-server.de.`, apex `TXT "v=spf1 +a +mx ?all"`, `www A`/`www AAAA`
(same addresses as the apex), `autoconfig CNAME mail.your-server.de.`,
`_autodiscover._tcp SRV 0 100 443 mail.your-server.de.`. No CAA, no `_dmarc`,
no `mail`/`smtp`/`imap`/`pop`, no DKIM selector among the guesses, no `ns1`/`ns2`
address rows at Hetzner at all. All 7 data rows were imported into zone 4
alongside the 4 nameserver address rows ("Imported 14 records.", serial 6 -> 7):

    elevenways.de.        3600 IN SOA ns1.elevenways.de. hostmaster.elevenways.de. 7 7200 1800 1209600 300
    elevenways.de.        3600 IN A 88.198.219.246
    elevenways.de.        3600 IN AAAA 2a01:4f8:d0a:27bd:0:0:0:2
    elevenways.de.        3600 IN MX 10 www4.your-server.de.
    elevenways.de.        3600 IN TXT "v=spf1 +a +mx ?all"
    elevenways.de.        3600 IN NS ns1.elevenways.de.
    elevenways.de.        3600 IN NS ns2.elevenways.de.
    _autodiscover._tcp.   3600 IN SRV 0 100 443 mail.your-server.de.
    autoconfig.           3600 IN CNAME mail.your-server.de.
    ns1.                  3600 IN A 137.74.171.228
    ns1.                  3600 IN AAAA 2001:41d0:305:2100:0:0:1:4afe
    ns2.                  3600 IN A 51.255.43.81
    ns2.                  3600 IN AAAA 2001:41d0:305:2100:0:0:1:4b26
    www.                  3600 IN A 88.198.219.246
    www.                  3600 IN AAAA 2a01:4f8:d0a:27bd:0:0:0:2

THIS COPY IS PROVISIONAL, exactly as `wcag.be` and `deloecker.eu` were: a probe
cannot enumerate a zone. Paste the real konsoleH export into the Zone-file tab
(the import REPLACES every operator row, so it heals this copy) before or right
after the glue lines land.

`compare --old 213.133.100.102 --new 137.74.171.228` (Hetzner vs kuifje) is
DIFFERENT (5) and all five are the intended ones: the apex NS set, the SOA, and
the four `ns1`/`ns2` address rows as `only-new`. Every data row is `identical`;
the only warnings are the TTL (7200 -> 3600) and the serial.

### wcag.be moved to the new names

kuifje zone 3: both apex NS rows edited to `ns1.elevenways.de` /
`ns2.elevenways.de` (records 29 and 30) and the zone form's Primary name server
to `ns1.elevenways.de`; serial 2 -> 5, robbedoes transferred within 10 s.
`compare wcag.be --old 137.74.171.228 --new 51.255.43.81 --strict` is IDENTICAL
on all 5 questions, apex NS and SOA included.

### Verification

`compare elevenways.de --old 137.74.171.228 --new 51.255.43.81 --strict
--names @,ns1,ns2,www,autoconfig,_autodiscover._tcp` is IDENTICAL on all 14
questions.

The DENIC-style matrix, run FROM each box (`dig +norec`, UDP and TCP, 6
questions each: zone SOA, zone NS, ns1 A/AAAA, ns2 A/AAAA):

    from      target                      v4 udp  v4 tcp  v6 udp  v6 tcp
    kuifje    kuifje                      aa=1    aa=1    aa=1    aa=1
    kuifje    robbedoes                   aa=1    aa=1    ---     ---
    robbedoes robbedoes                   aa=1    aa=1    aa=1    aa=1
    robbedoes kuifje                      aa=1    aa=1    ---     ---

Every answered cell is `rcode=0 aa=1`, same serial, same two NS names, addresses
matching the glue.

IPv6 IS listening and IS NOT the blocker. `dns.bind_address` is `0.0.0.0` on
both boxes, but `DnsServer.start` binds `InetAddress.getByName("0.0.0.0")`,
which is the ANY address: on a dual-stack JVM that is one wildcard socket
serving both families, and `ss -lunp`/`ss -ltnp` show `*:53` (not
`0.0.0.0:53`) for UDP and TCP on both hosts. Each box answers authoritatively
over its OWN global IPv6. `::` was NOT set and no service was restarted.

The `---` cells are an OVH LINK fact, not a server fact: both boxes hold a /128
out of `2001:41d0:305:2100::/64` with that /64 on-link, and neighbour discovery
for the peer FAILS (`ip -6 neigh` = FAILED, `/dev/tcp` = "No route to host"),
so box-to-box IPv6 is dead in both directions while both reach the wider IPv6
internet fine (gateway `2001:41d0:305:2100::1` REACHABLE, Cloudflare v6 pings).
A temporary host route via the gateway was added and removed on kuifje and did
not help (same link, same NDP). It costs nothing operationally -- AXFR runs over
IPv4 -- but it means a cross-box IPv6 probe times out and must not be read as
"the nameserver is down".

The authority is DENIC's own tool, `https://nast.denic.de/`, run with exactly
the refused glue lines: **Check successful**, and after the SOA retry fix with
ZERO messages. NAST probes from the outside over both families, so that is the
external IPv6 proof this workstation (no IPv6 route) cannot give.

`starfleet.life` is untouched and still served by both boxes (SOA serial 44,
identical), 0 journal errors on either, and neither service was restarted
(`ActiveEnterTimestamp` still 2026-08-29 22:56).

### For Jelle

RETRY THE KONSOLEH GLUE LINES NOW. DENIC's pre-delegation check passes clean
against exactly those two names and four addresses. Be aware that accepting
them MOVES the delegation off Hetzner DNS onto these two boxes; the live site
(88.198.219.246 / 2a01:4f8:d0a:27bd::2), the `www4.your-server.de` MX and the
autoconfig/autodiscover records are already copied here, but paste the real
konsoleH zone export into the Zone-file tab afterwards -- a probe reconstruction
can always be missing a row nobody guessed.

## elevenways.de bootstrap step 1 (mooo.com delegation), 2026-08-30

The glue lines still cannot be entered at Hetzner. konsoleH's own acceptance
check resolves the nameserver NAMES through public DNS before it will take them,
and `ns1.elevenways.de` / `ns2.elevenways.de` are NXDOMAIN until the delegation
moves -- a chicken-and-egg the DENIC pre-delegation check (which passed clean on
2026-08-30, see the section above) cannot break, because it is the REGISTRAR
refusing, not DENIC.

So the cutover is TWO steps, and this section is step 1: delegate the domain to
OUT-OF-BAILIWICK names that already resolve, then move the delegation back
in-bailiwick once the zone we serve publishes the address rows.

### Step 1, done here: apex NS + SOA MNAME on the mooo.com pair

kuifje zone 4 `elevenways.de` ONLY. The two apex NS record VALUES were edited in
place through the Records tab (inline value popover, records 111 and 112 in the
UI order), and the zone form's Primary name server (the SOA MNAME) with them:

    elevenways.de.  3600 IN NS  nskuifje.mooo.com.
    elevenways.de.  3600 IN NS  nsrobbedoes.mooo.com.
    elevenways.de.  3600 IN SOA nskuifje.mooo.com. hostmaster.elevenways.de. 10 7200 1800 1209600 300

Serial 7 -> 10 (each save bumps), robbedoes zone 5 transferred within seconds.
`dns.nameservers` was NOT touched on either box and still declares
`ns1.elevenways.de`, `ns2.elevenways.de` -- that is the setting for zones created
FROM NOW ON and it is right for step 2; wcag.be, tavernetomberg.be and
elevenways.be keep the in-bailiwick pair and were not opened. The four `ns1`/`ns2`
A+AAAA rows STAY in this zone: they are dead weight today (nothing resolves the
names) and they are exactly the glue step 2 needs the moment the delegation
lands.

The mooo.com names resolve today and are what makes this work:

    nskuifje.mooo.com     A 137.74.171.228   AAAA 2001:41d0:305:2100::1:4afe
    nsrobbedoes.mooo.com  A 51.255.43.81     (no AAAA yet)

### Verification

`hoh-dns-diff compare elevenways.de --old 137.74.171.228 --new 51.255.43.81
--strict --names @,ns1,ns2,www,autoconfig,_autodiscover._tcp` -> IDENTICAL on
all 14 questions, apex NS and SOA included. Both boxes answer `rcode=0 aa=1`
for the zone NS and SOA over UDP AND TCP, same serial 10, same pair of names.

DENIC's own tool, `https://nast.denic.de/`, domain `elevenways.de`, nameservers
`nskuifje.mooo.com` and `nsrobbedoes.mooo.com` with NO IP addresses (they are
out of bailiwick, so the domain carries no glue for them), answers verbatim:

    Check successful.
    Domain checked: elevenways.de

with zero messages, and the Nameserver Details table lists both names with an
empty IPs column, which is the correct out-of-bailiwick shape.

No service was restarted, no site or certificate touched, no registrar setting
changed, and no record VALUE other than the two NS names and the MNAME moved --
the apex still points at Phoenix (88.198.219.246 / 2a01:4f8:d0a:27bd::2) and the
`www4.your-server.de` MX is unchanged.

### For Jelle: step 1 at the registrar

Enter `nskuifje.mooo.com` and `nsrobbedoes.mooo.com` as `elevenways.de`'s
nameservers in konsoleH. No glue, no IP addresses -- Hetzner's check resolves
both names today, so it has nothing to refuse. Accepting them MOVES the
delegation off Hetzner DNS onto these two boxes; the live site, the MX and the
autoconfig/autodiscover records are already served here (still a probe
reconstruction -- paste the real konsoleH export into the Zone-file tab).

### Step 2, AFTER the delegation has propagated

1. Confirm the parent publishes the mooo pair:
   `hoh-dns-diff delegation elevenways.de --expect-ns nskuifje.mooo.com,nsrobbedoes.mooo.com`
   and that `ns1.elevenways.de` / `ns2.elevenways.de` now resolve at 1.1.1.1 --
   they will, because WE answer for them and the parent now points at us.
2. Edit the two apex NS rows and the SOA MNAME back to `ns1.elevenways.de` /
   `ns2.elevenways.de` (the `ns1`/`ns2` address rows are already there), verify
   `compare --strict` IDENTICAL and NAST again, this time WITH the four glue
   addresses.
3. Then enter the glue lines in konsoleH:
   `ns1.elevenways.de 137.74.171.228 2001:41d0:305:2100::1:4afe` and
   `ns2.elevenways.de 51.255.43.81 2001:41d0:305:2100::1:4b26`. Hetzner's check
   accepts them now: the names resolve.
4. Only after step 3 do the other zones (`wcag.be`, `tavernetomberg.be`,
   `elevenways.be`), which already publish the in-bailiwick pair, get their own
   registrar move.

## elevenways.de: back to ns1/ns2 (no mooo), 2026-08-30

Jelle's decision: NO `mooo.com` names anywhere in `elevenways.de`. The step-1
mooo delegation described in the previous section is REVERTED here -- it was
never entered at the registrar, so nothing had to unwind. Instead of moving the
delegation to out-of-bailiwick names, Jelle breaks the chicken-and-egg from the
other side: he adds the four `ns1`/`ns2` A+AAAA rows to the CURRENT,
Hetzner-hosted `elevenways.de` zone, which makes the names resolve publicly and
lets konsoleH accept the glue lines.

### What changed on the controllers

kuifje zone 4 `elevenways.de` ONLY. The two apex NS record values were edited in
place through the Records tab (inline value popover) and the zone form's Primary
name server (the SOA MNAME) with them:

    elevenways.de.  3600 IN NS  ns1.elevenways.de.
    elevenways.de.  3600 IN NS  ns2.elevenways.de.
    elevenways.de.  3600 IN SOA ns1.elevenways.de. hostmaster.elevenways.de. 13 7200 1800 1209600 300

Serial 10 -> 13 (each save bumps), robbedoes zone 5 transferred within seconds.
The four `ns1`/`ns2` A+AAAA rows were already present and were not touched; no
other record value moved (apex still 88.198.219.246 / 2a01:4f8:d0a:27bd::2, MX
still `www4.your-server.de`). `dns.nameservers` was not touched on either box
and still declares the in-bailiwick pair. `wcag.be`, `tavernetomberg.be` and
`elevenways.be` already publish that pair and were not opened.

`starfleet.life` still carries `nssl.mooo.com` / `nssl2.mooo.com`: it is a
SECONDARY zone here, owned elsewhere, out of scope for the no-mooo decision, and
it was not touched (serial 44 on both boxes).

### Verification

`hoh-dns-diff compare elevenways.de --old 137.74.171.228 --new 51.255.43.81
--strict --names @,ns1,ns2,www,autoconfig,_autodiscover._tcp` -> IDENTICAL on
all 14 questions, apex NS and SOA included, both sides serial 13.

`dig +norec` matrix run FROM each box, 6 questions each (zone NS, zone SOA,
ns1 A/AAAA, ns2 A/AAAA), UDP and TCP, against both IPv4 addresses and the
querying box's OWN IPv6: 36 answered cells, every one `rcode=NOERROR aa=1` with
the same pair of NS names and addresses matching the glue. Cross-box IPv6 is not
probed -- it is the known dead OVH link documented above, not a server fact.

DENIC's own tool, `https://nast.denic.de/`, domain `elevenways.de`, with exactly
the four glue lines Jelle will enter, answers verbatim:

    Check successful.
    Domain checked: elevenways.de

with ZERO messages, and the Nameserver Details table lists

    ns1.elevenways.de   137.74.171.228   2001:41d0:305:2100::1:4afe
    ns2.elevenways.de   51.255.43.81     2001:41d0:305:2100::1:4b26

No service was restarted (`ActiveEnterTimestamp` still 2026-08-29 22:56 on both
boxes), 0 journal errors, no site, certificate or registrar setting touched.

### Public DNS poll: the Hetzner records had NOT appeared

`ns1.elevenways.de` and `ns2.elevenways.de`, A and AAAA, polled every 60 s at
`1.1.1.1` and at `ns1.your-server.de` (the authoritative Hetzner server for the
live zone) from 00:25:35Z to 00:45:43Z, 21 rounds: **NXDOMAIN in all 84
answers**. The Hetzner zone's SOA serial stayed 2026082900 and the parent still
delegates to `ns.second-ns.com` / `ns1.your-server.de` / `ns3.second-ns.de`, so
the four records were not yet published when the window closed. Re-poll before
retrying the glue lines -- konsoleH resolves the names before it will accept them.

## wcag.be delegated (mooo pair), 2026-08-30

Jelle moved the registrar delegation of `wcag.be` to `nskuifje.mooo.com` /
`nsrobbedoes.mooo.com`. The zone we serve still published `ns1.elevenways.de` /
`ns2.elevenways.de` (the section above moved it there), and NEITHER of those
names resolves publicly -- so the zone had to follow the registrar immediately.

### What changed on the controllers

kuifje zone 3 `wcag.be` ONLY. The two apex NS record VALUES were edited in place
through the Records tab (inline value popover) and the zone form's Primary name
server (the SOA MNAME) with them:

    wcag.be.  3600 IN NS  nskuifje.mooo.com.
    wcag.be.  3600 IN NS  nsrobbedoes.mooo.com.
    wcag.be.  3600 IN SOA nskuifje.mooo.com. hostmaster.wcag.be. 8 7200 3600 1209600 300

Serial 5 -> 8 (each save bumps), robbedoes zone 3 transferred within seconds
(Secondaries tab: `robbedoes` **Current**, served serial 8). No other record
value or TTL moved, no other zone was opened, no service restarted, no site or
certificate touched.

The nameserver names resolve, which is what makes this safe:

    nskuifje.mooo.com     A 137.74.171.228   AAAA 2001:41d0:305:2100::1:4afe
    nsrobbedoes.mooo.com  A 51.255.43.81     (no AAAA)

### The data rows already matched Hetzner's export

Checked row by row against the export before touching anything, values AND TTLs:
apex `A 213.239.210.245`, apex `AAAA 2a01:4f8:a0:948c::2`, apex
`TXT "v=spf1 +a +mx ?all"`, `api`/`www` CNAME `wcag.be.`, `earl` (TTL 700) and
`invulassistent` CNAME `phoenix.develry.be.`, everything else TTL 7200. Nine
records, no extras, nothing missing. NOTHING was corrected -- the probe
reconstruction turned out to be exact.

### Verification

`hoh-dns-diff compare wcag.be --old 137.74.171.228 --new 51.255.43.81 --strict
--names @,www,api,earl,invulassistent` -> **IDENTICAL** on all 9 questions, apex
NS and SOA included, both sides serial 8.

Zone row action **Check health** on kuifje, verbatim:

    Delegation: delegated_not_listed. 1 secondaries probed, 0 behind.

which is the CORRECT verdict while the parent still names Hetzner's three
servers; it becomes `matches` once `.be` publishes the mooo pair.

The site is unaffected -- only the nameservers moved, every A/CNAME value is
unchanged -- and `curl -sI` from the workstation gets HTTP 200 from
`https://wcag.be/`, `https://www.wcag.be/` and `https://earl.wcag.be/`.
