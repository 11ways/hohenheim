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

`NftRunner.Sudo` invokes `/usr/bin/sudo -n -- nft`. A host that places
WORKSPACES or APPLICATIONS (a btrfs volume root) needs one more line, because
the volume lane is root work by nature -- chown to the workspace's foreign uid,
qgroup limits, subvolume snapshot and delete all refuse to an ordinary user --
and `BtrfsVolumeOperations` elevates each of those binaries with `sudo -n`
whenever the shell is not root (`HostShell.elevated`):

    printf 'hohenheim ALL=(root) NOPASSWD: /usr/bin/btrfs, /usr/bin/chown, /usr/bin/chmod, /usr/bin/mkdir, /usr/bin/rm\n' \
        > /etc/sudoers.d/hohenheim-volumes
    chmod 440 /etc/sudoers.d/hohenheim-volumes
    visudo -cf /etc/sudoers.d/hohenheim-volumes

Those two lines are the entire root surface the controller needs; a missing
grant surfaces as `volume_own_failed` (or a sibling) carrying sudo's own
"a password is required" text, never as a silent success. The Incus admin group is
`incus-admin` (full API); the restricted `incus` group is NOT enough for the
instance tier.

### Host-process sites (`roles.processes`) only

A node that runs managed child processes needs two more things, and NEITHER is
a wider root grant:

    loginctl enable-linger hohenheim        # a persistent systemd USER manager
    # util-linux must provide setsid, setpriv and prlimit (it does by default)

`ProcessConfinement` puts every child that declares `memory_limit_mb` in a
cgroup v2 scope through `systemd-run --user --scope`, which needs the daemon
user's own systemd manager to be running (lingering) -- a root daemon uses a
system scope instead and needs nothing. This is deliberately NOT
`NOPASSWD: /usr/bin/systemd-run`: that would be arbitrary code as root, an
enormously wider grant than the nft line, for a knob the user manager already
delegates. Without it, a site that declares a memory limit REFUSES to start
and says so by name (the limit would otherwise be booked against the host
budget without being enforced); a site that declares no limit is unaffected,
runs unbounded and is booked for nothing.

`SystemUsers.executionBuilder` additionally wraps every spawn in
`prlimit --nproc` (only where the site has its own dedicated uid -- the limit
is per-UID) and `setpriv --no-new-privs`. The per-site run-as user still needs
the controller's existing NOPASSWD sudo for `-u #uid`, which this tier has
always required.

## Layout

    /opt/hohenheim/
      hohenheim-server.jar
      public/cms.js            (+ cms.js.map, optional)
      settings/default.dry     copy of the repo's settings/default.dry
      settings/hohenheim.dry   the role + security declaration, see below
                               (GITIGNORED in the repo -- it is per-deployment;
                                start from settings/hohenheim.dry.example)
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
   identity to pin, and kernel truth is read through the local sudo runner with
   NO ssh lane. Since 2026-08-07 that lane is a placement REQUIREMENT for any
   posture other than `trusted_only`, and the preflight check
   `kernel_isolation_lane` PROVES it by running a real nft
   add/list/delete on this machine -- so the sudoers line above is what makes
   `kernel_isolation_lane: pass`, and without it the Incus row cannot be
   admitted at all.
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

## Two controllers on one daemon: NAMESPACED, no longer a procedural rule

Superseded 2026-08-06 (controller-namespace wave). The temporal-separation rule
that used to live here is LIFTED: a hohenheim ON this host and a workstation
suite driving the same daemons remotely may now run CONCURRENTLY.

What changed: every name hohenheim writes onto a shared resource pool carries
this controller's identity token, minted once into its own control-plane
database (`controller_identity`, M077) -- the same database that allocates the
record ids. Handles are `hohenheim-<token>-instance-<id>`; the Docker/Incus
networks, volumes and image tags derive from those handles; the Incus isolation
ACL is `hohenheim-<token>-isolation`, the extra bridge `hhx-<token>`, and the
nftables tables `hohenheim_<token>` / `hohenheim_net_<token>`. Docker resources
additionally carry a third owner label, `be.elevenways.hohenheim.owner.controller`,
so ownership is decided by (controller, model, id) and never by (model, id)
alone. Two controllers therefore mint DIFFERENT names for their respective
record #1, each one's sweeps see only its own workloads, and each one's
ownership guard REFUSES the other's by name and by label. Proven live on one
real daemon by `TwoControllerCollisionLiveTest`, counterfactual included.

There is deliberately NO default identity: a controller that cannot read or
mint its row refuses to name anything rather than falling back to a shared
value. That fallback is exactly how this hazard existed.

What is still shared, by design, and what it means:

- The HOST PORTS a workload publishes. Two controllers cannot arbitrate a port
  between them (neither ledger can see the other's), so the second bind simply
  fails. Loud, at the daemon, not a silent overwrite.
- Docker's default address pool (~30 user-defined networks per host) is spent
  by both controllers together.
- A pre-namespace resource (a bare `hohenheim-instance-N` from before this
  wave) is attributable to NO controller. It is never adopted and never
  removed automatically: the reconciler reports it as FOREIGN_COLLIDING with a
  "pre-namespace" detail, and clearing it is an explicit operator removal.
  Both daystrom and nightstrom were verified to hold zero such resources when
  the namespace landed.

## Reclaiming per-controller leftovers on a shared daemon

Every controller that ever touched a shared Incus daemon leaves at most three
shared objects there: `hohenheim-<token>-isolation`, `hhx-<token>` and
`hohenheim-<token>-presence`. With ephemeral controllers (every live test fork
mints a fresh identity) these accumulate; measured on daystrom 2026-08-10
before the presence-on-deploy fix: 93 ACLs across 80 distinct tokens, only 13
of them stamped.

How they are reclaimed, in order of preference:

1. AUTOMATICALLY. The `ReapIncusControllers` sweep (every 15 minutes on any
   controller with the INSTANCES role and the host enrolled) removes a foreign
   controller's shared objects only when three facts agree: the object carries
   another controller's token, its `used_by` is empty on a re-read at delete
   time, and its controller's presence stamp expired past
   `hohenheim.incus.controller_presence_grace_hours` (default 168h). Since the
   presence-on-deploy fix, every deploy stamps the presence marker, so ANY
   controller that ever deployed becomes reapable this way after the grace.
   The live test lane additionally runs this sweep at every host enrollment
   and hands its own shared objects back at teardown, so a cleanly finished
   test fork leaves nothing and a killed one is reaped after the grace.

2. BY OPERATOR ACTION, for objects with NO presence stamp (controllers older
   than the presence mechanism, or forks killed before their first deploy).
   The admin panel's server row action "Reap controller objects" runs
   `ReapIncusControllers.reapIncludingUnstamped` on ONE named host: same
   classification, but unstamped objects go too, still re-verifying `used_by`
   per object at the daemon. NEVER run it while live test suites are running
   against that daemon -- a concurrent fork's freshly minted, not-yet-stamped
   ACL is indistinguishable from legacy garbage in that window.

3. NEVER by hand-deleting at the daemon while any hohenheim controller might
   be mid-deploy against it.

Docker daemons have no cross-controller presence mechanism (a known gap:
labels are immutable, so a heartbeat needs its own design). A dead ephemeral
controller's record-scoped Docker leftovers (`hohenheim-<token>-...` networks,
volumes, containers) are reported by the reconciler as foreign and must be
reclaimed manually, e.g. after confirming no live fork owns the token:

    docker network ls --format '{{.Name}}' | grep '^hohenheim-<token>-' | xargs -r docker network rm
    docker volume ls -q | grep '^hohenheim-<token>-' | xargs -r docker volume rm

## Host script: incus-docker-forward.sh

Both Incus hosts run `/usr/local/sbin/incus-docker-forward.sh` from a oneshot
systemd unit (`incus-docker-forward.service`) because Docker sets the IPv4
FORWARD policy to DROP, which silently blackholes forwarded IPv4 for Incus
bridges while IPv6 keeps working -- the classic half-masked failure. The
canonical script lives in this repo at `docs/host-scripts/incus-docker-forward.sh`.
It matches bridges by iptables interface WILDCARD (`incusbr+`, `hhx-+`,
`hohenheim-extra`), never by a hand list: `hhx-<token>` bridges are created and
deleted at runtime, so any boot-time enumeration goes stale the moment a new
controller deploys an extra NIC.
