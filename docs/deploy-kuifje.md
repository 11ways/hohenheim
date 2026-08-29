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
