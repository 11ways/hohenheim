# Native single-host deployment (the shape that ships)

Hohenheim's primary deployment shape is running ON the host it manages: one
machine, Incus over the local unix socket, Docker over the local socket,
nftables written locally through `sudo nft`. This document is the repeatable
path that was exercised end to end on daystrom (Arch, 2026-08-06); a future
wave must be able to redo it from here. The remote (ssh/https) lane is a
DIFFERENT deployment shape and is documented by the live tests
(`LiveIncusHost`, `LiveRemoteHost`).

## Artifact

The distribution is the fat server jar the standard build already produces:

    zenit-dev build            # in the hohenheim repo
    build/libs/hohenheim-0.1.0-SNAPSHOT-server.jar

The jar carries migrations, templates, all module CSS and the settings
defaults. It does NOT carry the TeaVM client bundle: `public/cms.js` (and its
`.map`) must ship alongside, served from `<workdir>/public/` (the asset
middleware reads disk first, then classpath -- the classpath has no cms.js).

## Host prerequisites (Arch spelling; adapt per distro)

    pacman -S --needed jre-openjdk-headless        # Java >= 25 (26 works)
    # docker + incus daemons installed and running; nftables package for nft.
    # Arch's nftables.service stays DISABLED on purpose: its shipped ruleset
    # drops forward traffic and would break both daemons' bridges. Hohenheim
    # writes its own tables through nft directly; no ruleset service needed.

    useradd --system --create-home --home-dir /opt/hohenheim \
        --shell /usr/bin/nologin hohenheim
    usermod -aG docker,incus-admin hohenheim       # both daemon sockets
    printf 'hohenheim ALL=(root) NOPASSWD: /usr/bin/nft\n' \
        > /etc/sudoers.d/hohenheim-nft
    chmod 440 /etc/sudoers.d/hohenheim-nft
    visudo -cf /etc/sudoers.d/hohenheim-nft

`NftRunner.Sudo` invokes `/usr/bin/sudo -n -- nft`; the sudoers line above is
the entire root surface the controller needs. The Incus admin group is
`incus-admin` (full API); the restricted `incus` group is NOT enough for the
instance tier.

## Layout

    /opt/hohenheim/
      hohenheim-server.jar
      public/cms.js            (+ cms.js.map, optional)
      settings/default.dry     copy of the repo's settings/default.dry
      settings/hohenheim.dry   the role + security declaration, see below
      data/                    storage.data_path (created empty)
      hohenheim.db             created by the first boot (104+ migrations)
      settings/field-encryption.keys   auto-generated 0600 on first boot

`settings/hohenheim.dry` for an instances-only compute node:

    {
        "roles": {
            "proxy": false, "dns": false, "firewall": false,
            "stacks": false, "processes": false, "databases": false,
            "instances": true
        },
        "security": { "nftables_enabled": true },
        "ssl": { "letsencrypt_enabled": false }
    }

`security.nftables_enabled` is what turns per-workload kernel enforcement on;
without it the Docker isolation sweep reports every workload unverifiable by
design. The admin listener defaults to port 3000 (`network.port` in
`settings/local.dry` to change it).

## systemd unit (/etc/systemd/system/hohenheim.service)

    [Unit]
    Description=Hohenheim controller (native, manages this host's Docker and Incus)
    After=network-online.target docker.service incus.service
    Wants=docker.service incus.service

    [Service]
    User=hohenheim
    Group=hohenheim
    WorkingDirectory=/opt/hohenheim
    ExecStart=/usr/bin/java -Xmx768m -XX:MaxMetaspaceSize=256m -jar /opt/hohenheim/hohenheim-server.jar
    SuccessExitStatus=143
    Restart=on-failure
    RestartSec=5

    [Install]
    WantedBy=multi-user.target

Sizing: on a 3.9 GB host the JVM peaked at ~820 MB with three workloads (two
containers + one 512 MB VM); -Xmx768m is a comfortable ceiling. The first boot
runs every migration and prints `roles_captured` naming the enabled roles --
verify that line before anything else.

## First-run ceremony (all through the product)

1. `http://host:3000/` -> `/setup` creates the superuser.
2. Servers page: the implicit `local` Docker host row already exists.
   Preflight it (probe container + real nft add/list/delete through sudo),
   Admit it, and set its posture (`shared_container` for hostile containers).
   The local row's identity (name/target/mode/runtime) is immutable; posture
   and public addresses are the operator-editable columns.
3. Enroll the local Incus daemon as a SECOND host row: runtime `incus`,
   `incus_url` = `unix://` (blank also means the default socket), posture
   `vm_isolated`. No trust ceremony applies -- a unix socket has no wire
   identity to pin, and `IncusKernelIsolation.available()` is TRUE through the
   local sudo runner with NO ssh lane (preflight check
   `kernel_isolation_lane: pass`).
4. Preflight + Admit the Incus row, then create instances against either host.

## Verified on daystrom (2026-08-06)

Fresh DB boot with 104 migrations; both preflights green over the local
sockets; container + VM deploys; `VerifyIncusIsolation` and
`VerifyDockerIsolation` (renamed `VerifyWorkloadIsolation` 2026-08-06 when the
host-process tier joined it) both VERIFIABLE (no ssh lane anywhere) and both
observed repairing a deliberately broken kernel (chains deleted while
workloads ran) within one 5-minute sweep; tenant-range egress blocked and
`http://1.1.1.1/` reachable from both tiers; product destroy paths left the
daemons empty.

## HAZARD: never run two controllers against one daemon

A hohenheim ON this host and a workstation test suite driving the same
daemons REMOTELY are two controllers over one resource pool. Workload handles
are `hohenheim-instance-<id>` with ids from each controller's OWN database, so
two controllers collide on names: each one's isolation sweeps will inspect,
"repair", or STOP workloads that belong to the other, and a deploy can
converge onto a same-named foreign workload. Run them at DIFFERENT times:
stop this service (`systemctl stop hohenheim`) before pointing the
workstation's live tests (`~/.config/hohenheim-livehost/*.properties`) at
this machine, and vice versa. This is a procedural rule because nothing in
the product namespaces workload handles per controller yet; if that ever
changes, this section is the place to say so.
