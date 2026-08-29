# The OVH VPS: the DNS primary

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
    hostname         vps-0ffe31ed
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

## Still to do on this box

- Choose its public name, then set `network.main_url`,
  `auth.external_base_url`, and give it a site + certificate for the panel.
- Register it as a DNS peer of starfleet and mirror the zones it should serve
  (`docs/dns-federation.md`), then delegate at the registrar with matching glue
  -- that is the whole point of this machine.
- Open the provider firewall for 53 udp+tcp, 80 and 443; keep 3000 closed.
- Add it to `~/.config/zenit-dev/config.json` under `deployments` so
  `zenit-dev deployed <name>` can read its build stamp over ssh.
