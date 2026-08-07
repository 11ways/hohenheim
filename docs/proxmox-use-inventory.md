# The Proxmox-use inventory

Closed 2026-08-06. This is the checked-in inventory the Phase 8 gate and the
"Proxmox replacement" definition in `instance-tier-plan.md` both demand:
every capability we use Proxmox for, with a DECISION and its evidence.

**Closed does not mean implemented.** Each row is one of:

- **IMPLEMENTED** -- the mechanism exists and a test proves it. The row names the
  file, the test, and whether that test RAN or SKIPPED when it was last checked.
- **IMPLEMENTED, NO OPERATOR SURFACE** -- the mechanism exists and is tested, but
  nothing a human can reach calls it. This is a real limitation and it is stated
  as one, not filed under "done".
- **REJECTED** -- a decision, with the reasoning. A well-argued rejection closes
  an item; a silent omission does not.
- **GAP** -- genuinely missing, genuinely wanted, named here with the slice that
  owns it.

### How this document was built

Every row was re-derived from CODE, not from the plan's own STATUS notes -- the
plan's reading rules say a LANDED claim is a claim to re-verify, and the wave
that discovered `IncusKernelIsolation` had been inert on the VM tier for three
sessions is why. Where a row cites a test, the test was read for whether it
asserts STATE or only a status code, and whether it `assumeTrue`s its way to a
green skip. Where a row cites a LIVE proof, the run is dated and its
RAN-vs-SKIPPED count is given. Two audit fixes came out of this pass and are in
the Findings section at the bottom.

Verification legend: **[code]** = source read at the cited file:line;
**[test]** = test class re-run or its log inspected, with counts; **[live]** =
re-run against the real Incus hosts daystrom (10.47.1.99) / nightstrom
(10.47.1.101) on 2026-08-06.

---

## 1. VM and container provisioning

**IMPLEMENTED.**

One driver seam, two runtimes. `InstanceRuntime` is the interface;
`IncusInstanceRuntime` (system containers and VMs) and `DockerInstanceRuntime`
(application containers) implement it. A VM is not a separate tier: kind
`hohenheim:incus_vm` (`IncusVmKind`) rides the SAME `IncusInstanceRuntime`, with
the flavour declared as an `IncusWorkloadType` (api type `virtual-machine`, a
longer exec-ready window, secure-boot as a MANAGED config key). Converging onto a
same-named workload of the wrong flavour refuses.

- Code: `server/runtime/IncusInstanceRuntime.java`, `server/runtime/DockerInstanceRuntime.java`,
  `server/instance/IncusVmKind.java`, `server/runtime/IncusWorkloadType.java`. **[code]**
- Live: `IncusVmLiveTest` (13-step VM journey on daystrom) **re-run for this audit
  2026-08-06: 1 test, PASSED, 0 skipped, 94s**; `IncusInstanceRuntimeLiveTest`,
  `IncusCommunityAppLiveTest`. **[live]**
- CI: `InstanceRuntimeLiveTest`, `PreparedImageTest`.

Image identity is pinned, not aliased: `instances.image_fingerprint` stores the
daemon's resolved `volatile.base_image` at deploy (fenced write); an ABSENT
workload is recreated from the PIN, never from the mutable alias
(`InstanceImagePin`, M073). **[code]**

## 2. Templates and clones

**IMPLEMENTED for templates. Clone-of-a-running-instance REJECTED.**

Provisioning vocabulary is the template mechanism (`InstanceTemplateModel`,
typed variables, `InstanceImagePolicy` approving kind+image+tag+origin).
`ImageOrigin` declares CATALOG (public simplestreams, the default) or PREPARED
(the image is already in the TARGET daemon's own store and is never fetched) --
this is how a Windows guest is provisioned, and it is deliberately not
Windows-shaped: no code path anywhere reads "windows".

- Code: `server/runtime/ImageOrigin.java`, `server/instance/InstanceTemplates.java`,
  `server/instance/InstanceImagePolicy.java`. **[code]**
- Live: `IncusWindowsTemplateLiveTest` -- 9 steps against the real `win2025-core`
  image on daystrom. **RAN and PASSED**, 348s, log `20260806-164723` (verified in
  the log rather than assumed: the class reports `PASSED`, not `SKIPPED`). It
  SKIPS, never fails, when the operator fixture image is not published. **[live]**
- Live: `InstanceTemplateInstallLiveTest`; CI: `InstanceTemplatePolicyTest`,
  `PreparedImageTest`.
- Operator procedure for minting a prepared image: `docs/prepare-windows-template.md`.

**Clone** (in the Proxmox sense: full/linked copy of an existing guest) is
REJECTED as redundant. The two things Proxmox clones are used for are covered by
mechanisms that already exist and carry the accountability a bare copy does not:
publish an image and provision from it (PREPARED origin), or export/import an
instance (the migration and backup lane, `NativeSnapshotSupport.exportBackup` /
`importBackup`, which re-attributes ownership, strips the MAC and rejoins
isolation). A `clone` verb would be a third path onto the same daemon call with
its own ownership and quota questions.

**Known limitation, stated:** a prepared image lives in ONE daemon's store.
`win2025-core` is published on daystrom only, so a Windows workload cannot today
be drained or cold-migrated onto nightstrom -- the destination is refused by
name by the prepared-alias preflight, which is the correct failure, but the
placement layer does not know about it and so cannot avoid choosing that host.
Owning slice: placement (item 12).

## 3. Disks, and disk/NIC/device editing

**IMPLEMENTED, mechanism AND surface** (surface added 2026-08-06; this row read
"NO OPERATOR SURFACE" until then, and the paragraph that said so is kept below as
the record of what was missing).

The mechanism is real, complete and tested:

- `instance_devices` desired-state rows (M073), reconciled at deploy and cleaned
  at destroy; `DeviceAttachSupport` is the driver capability
  (`ensureDisk`/`resizeDisk`/`ensureNic`/`removeDevice`/`deleteVolumes`).
  Disks are owner-labelled block custom volumes; resize is stopped-only at the
  daemon ("In use" while running) and that refusal is surfaced verbatim, not
  swallowed. `InstanceDevices` orchestrates row-then-daemon with the row deleted
  on a daemon refusal. **[code]** `server/runtime/DeviceAttachSupport.java`,
  `server/instance/InstanceDevices.java`, `server/runtime/IncusInstanceRuntime.java:709-880`.
- Quota: disk-GB and extra-NIC dimensions over the core reservation ledger,
  charged adjacent to the row write. **[test]** `InstanceDeviceQuotaTest` -- 3
  tests, all RAN 2026-08-06.
- Live: `IncusVmLiveTest` steps 8-13 attach a disk, prove the guest sees it,
  prove a running resize is refused and a stopped one lands, prove the over-cap
  refusals by name, attach an extra NIC on the managed `hohenheim-extra` bridge
  with the isolation ACL read back, and prove destroy removes the volume.
  **Re-run 2026-08-06 for this audit: PASSED, 0 skipped.** **[live]**

**What was missing until 2026-08-06:** `InstanceDevices.attachDisk` / `attachNic` /
`resizeDisk` / `detach` had NO production caller. A repo-wide search for callers
returned `IncusVmLiveTest` and nothing else; production called only `reconcile`
(at deploy) and `destroyCleanup` (at destroy). There was no admin resource, no row
action, no `.hwk` page and no API endpoint that wrote an `instance_devices` row.

**The surface, added in the device-surface wave:**

- **Panel**: a `Devices` tab on every instance (`InstanceDevicesPage`, a
  `RecordScopedPage` rendering `cms/instance-devices.hwk`) plus a nav-hidden
  `InstanceDeviceResource` (`RowResource`, `parent()` back to the instance's
  Devices tab, `createValues` reading `?instance_id=&type=`) -- the SCHEDULES
  shape, not a second one. Present on `/admin` and, through
  `ManageInstanceDeviceResource`, on the grant-scoped `/manage` panel.
- **API**: `GET/POST /api/v1/instances/{id}/devices`, plus `.../devices/resize`
  and `.../devices/detach` (`InstanceApi.initDeviceLane`). The list projection is
  a whitelist (`name`, `type`, `size_gb`); `quota_bucket` is absent by name.
- **Authority is not re-implemented anywhere.** Both surfaces call
  `InstanceDevices`, whose every mutator opens with
  `HohenheimAccess.requireOperationCapability(instanceId, MANAGE)`. `PaasApi`'s
  rule holds: the API's only own check is per-record VISIBILITY, and an instance
  the caller holds nothing on answers 404 exactly like one that does not exist.
- **Detach carries a DESTRUCTIVE confirmation naming what it destroys**
  (`deleteConfirmation()`): "detaching deletes its backing volume at the daemon",
  not the generic "are you sure".

**Framework change this required** (`zenit-cms`): a scoped create/update used to
run inside a rollback transaction that re-loads the row through the access
predicate. That is wrong for a mutation reaching a DAEMON -- a rollback removes
the row and orphans the volume -- and on a single-writer engine it cannot even
run: `Leases.acquire` refuses by name inside an active transaction, so the attach
failed outright. `Resource.verifiesScopeBeforeMutating()` (default false) lets a
resource that refuses out-of-scope callers BEFORE its first write opt out of the
wrapper; `InstanceDeviceResource` declares it.

- **[test]** `InstanceDeviceSurfaceTest` -- 3 journeys, all RAN 2026-08-06: the tab
  attaches/resizes/detaches AT THE FAKE DAEMON (not merely in a row), every refusal
  lane (capability, quota, daemon) is named and leaves NO device row and an
  untouched ledger, and the API lane drives the same devices with the
  no-existence-oracle rule intact.

**Still open in this row:** the root-disk size knob (below), and there is no
single "migrate this device" or reorder affordance (devices are not ordered).

**Root-disk size knob: GAP.** The VM root disk is inherited from the daemon's
`default` profile and there is no size field anywhere in `InstanceSpec` or on any
kind. Growing a guest's root disk is not expressible. Owning slice: the VM-spec
slice that would add a size to `InstanceSpec` and a knob to `IncusVmKind`; the
device surface above deliberately does NOT grow one, because a root disk is not an
`instance_devices` row and pretending it is would give it the wrong quota, the
wrong detach semantics and the wrong reconcile.

## 4. Storage pools: placement and capacity

**Pool SELECTION rejected. Pool CAPACITY partially implemented (restore-side only).**

There is exactly one pool per host and the product does not choose it: it is
whatever the daemon's `default` profile puts its root disk on
(`IncusInstanceRuntime.managedPoolName`), and a host without one is REFUSED for
disk devices rather than guessed at. **[code]** That is a deliberate rejection of
multi-pool placement: a pool is a host-shaping decision the operator makes when
they build the host, and a per-workload pool picker would need its own capacity,
quota and migration semantics for a choice we do not make in practice.

Capacity is consulted in ONE direction: `RestoreCapacity.require` reads the pool's
`space.total - space.used` with a 1.2x headroom factor and refuses
`restore_capacity` / `restore_capacity_unknown`. It gates backup restore,
snapshot restore and cold migration. **[code]** `server/instance/RestoreCapacity.java:43-96`,
call sites `InstanceBackups.java:309`, `InstanceSnapshots.java:247`,
`InstanceMigrations.java:223`.

**TESTED 2026-08-06** (this row read "GAP, named: RestoreCapacity has NO test"
until then -- no test class referenced it, neither named refusal was asserted
anywhere, and `InstanceMigrationTest` stubs it out through the `CapacityCheck`
seam, which it still does because those journeys are deliberately daemon-free).

`require` is now split into `availableBytesOn` (the probe) and `judge` (the
headroom arithmetic and both refusals), so the decision is assertable without a
daemon and the probe is proven against a real one:

- **[test]** `RestoreCapacityTest` -- 2 journeys, both RAN: a payload that fits
  only WITHOUT the 1.2x headroom is refused (the boundary the constant exists
  for), the refusal quotes the host and both figures, and an unmeasurable host
  refuses `restore_capacity_unknown` on BOTH runtime branches rather than passing.
- **[live]** `RestoreCapacityLiveTest` -- the real pool on daystrom is queried,
  a kilobyte passes, and a restore the size of the measured free space is refused
  by name quoting the daemon's own figure.

**Defect found by that test and fixed:** the refusal CONSTRUCTION called
`ServerModel.nameOf`, which THROWS on an unknown id -- so a host row that vanished
mid-restore turned a named 422 into a raw `IllegalStateException` 500. The refusal
path is now the one path that cannot itself fail (`hostLabel`, id spelling
fallback).

Preflight reports pools as a fact and passes iff at least one pool is `Created`
(`server/host/IncusPreflight.java:174-190`, covered by `IncusHostLiveTest` step 8).

## 5. Bridges, VLANs and firewall policy

**Firewall policy IMPLEMENTED and kernel-verified. Bridge selection REJECTED.
VLANs GAP (rejected for now).**

The tenant boundary is `IncusNetworkPolicy`: one shared `hohenheim-isolation`
ACL carrying six reject rules over `TenantNetworkRanges` (link-local metadata
plus RFC1918 plus ULA/link-local v6), attached to every NIC, read back after
every write, with disabled rules explicitly not counting as carried. **[code]**

The network is NOT operator-selectable and that is the decision: the primary NIC
inherits the daemon's `default` profile network, and extra NICs land on the
product-managed `hohenheim-extra` bridge because the daemon refuses a second NIC
on the primary network. `NetworkPosture` is declared by the KIND, never a
per-record knob, so no form can opt a workload out of isolation. **[code]**
`server/runtime/NetworkPosture.java:4-7`.

Config truth is not trusted on its own. `IncusKernelIsolation` reads
`nft list table bridge incus` on the daemon's own host and requires a drop/reject
naming the NIC's CURRENT `volatile.<dev>.host_name` tap for every tenant range in
both families -- because the upstream defect this defends against
(`nicBridged.postStop` returning on a failed detach and skipping `removeFilters`)
leaves a chain naming a DEAD tap, which any rule-counting check would call
isolated. Repair is a per-instance device-key toggle, never the shared ACL.
`VerifyIncusIsolation` sweeps every five minutes (boot + cron, INSTANCES role);
an unrepairable running workload is STOPPED, an unreadable HOST is only reported.
**[code]** `server/incus/IncusKernelIsolation.java`, `server/task/VerifyIncusIsolation.java:73-75`.

- CI: `IncusNetworkPolicyAclTest` (2), `IncusKernelIsolationTest` (1) -- the last
  asserts kernel truth is judged against the live tap, not chain existence.
- Live: `IncusKernelIsolationLiveTest` drives a real divergence and requires the
  production SWEEP to repair one workload and stop another;
  `IncusNetworkIsolationLiveTest`; `IncusVmLiveTest` steps 7b-7e. **[live]**
- Destination-side kernel truth after a migration is asserted live in
  `IncusColdMigrationLiveTest` -- **RAN and PASSED 2026-08-06, 1 test, 0 skipped,
  632s, daystrom -> nightstrom.** **[live]**

**VLANs: GAP, deliberately not built.** There is no VLAN support of any kind --
zero occurrences of `vlan` or `trunk` in `src/` (verified by grep, the one repo
hit is a vendored word list). We do not operate tagged VLANs on these hosts, and
the isolation model we DO run is stronger for hostile tenants than VLAN
separation: per-workload nft rules verified in the kernel, rather than a trunk
whose correctness lives in a switch we do not own. If a concrete need appears the
owning surface is `IncusNetworkPolicy.nicDevice` plus an `IncusVmKind` capability,
in the shape `ImageOrigin` established.

**RISK, permanent, carried openly:** the upstream failed-detach race is not a
known defect upstream, not fixed on `main`, and not version-gated. Nothing outside
incusd can PREVENT the window; the five-minute sweep makes it bounded and
self-closing rather than invisible. The post-start window is still bounded only by
that sweep (candidate closure: a `/1.0/events` listener; not built, because the
trigger has not been reproduced on demand and hardening an unreproduced trigger
retires the investigation instead of closing the bug).

**Also open, and it is the one that most weakens the Phase 8 claim:** the ssh
admin lane that kernel truth is read through is OPTIONAL by design. An Incus host
that declines it has NO isolation verification at all while still accepting
tenant workloads, and is reported UNVERIFIABLE every sweep. Making the lane a
placement REQUIREMENT for any posture other than `trusted_only` reverses a
recorded decision and is an operator fork, not a fix to slip in.

**SUPERSEDED 2026-08-07 (trust-state-machine wave): CLOSED, the fork was taken.**
Kernel-truth verification is now an ADMISSION requirement for any posture other
than `trusted_only`, proven by a real nft transaction through the verifier's own
lane rather than by `available()`, and refused by name at both admit and
placement. A local daemon satisfies it with no ssh at all, and a host that
accepts no tenant workloads still enrols, confirms and holds backups without a
lane. See the dated STATUS block in docs/instance-tier-plan.md for the full
decision, including what happens to an already-admitted host that cannot
verify.

## 6. Cloud-init

**IMPLEMENTED for Linux. Windows guest configuration REJECTED for now.**

`cloud_init` is a settings field on the VM kind; `{{KEY}}` placeholders resolve
against instance variables (secret lane included) in
`InstanceVariables.applyToSettings`; the spec carries the rendered text and the
driver writes `cloud-init.user-data` as a MANAGED key. The Docker driver refuses
a cloud-init-bearing spec BY NAME before touching the daemon. The VM kind has no
env vars, because nothing injects into a guest's init. **[code]**
`server/instance/IncusVmKind.java:66-72`, `server/instance/InstanceVariables.java:201-231`,
`server/runtime/IncusInstanceRuntime.java:219-231`, `server/runtime/DockerInstanceRuntime.java:89-95`.

- Live: `IncusVmLiveTest` asserts the key landed with `{{MARK}}` SUBSTITUTED and
  that cloud-init actually ran inside the guest. **[live]**
- **TRAP, recorded:** `cloud-init status` exits 2 on done-with-warnings. Never
  gate on its exit code.
- **Test gap, named:** the Docker cloud-init refusal has no test of its own (the
  sibling PREPARED refusal does, in `PreparedImageTest` step 7), and there is no
  CI-level test of `applyToSettings` substituting into `cloud_init` -- the only
  assertion of that lives in the live VM test.

Nothing writes into a Windows guest. Per-instance Windows configuration would
need cloudbase-init inside the prepared image consuming the same
`cloud-init.user-data` the driver already writes; that combination is UNTESTED
and is not claimed to work.

## 7. Snapshots

**IMPLEMENTED. Retention GAP.**

Two capabilities, one per driver family: `NativeSnapshotSupport` (Incus:
create/exists/restore/delete plus export/import) and `VolumeSnapshotSupport`
(Docker: cold volume capture, caller stops the workload). A driver implements
exactly one; a driver with neither refuses `snapshots_unsupported` by name.
Records live in `instance_snapshots` (M058, M071). Snapshots are schedulable
through the core per-record schedule vocabulary (`hohenheim:snapshot`), so
cadence is operator-authored per instance with a capability check per step.
**[code]**

- Live: `InstanceSnapshotBackupLiveTest` (Docker tier, 3 journeys),
  `IncusSnapshotBackupLiveTest` (Incus tier: schedule + snapshot + restore +
  off-host backup + both refusals).
- Snapshots survive a cold migration (the migration export packs them,
  `withSnapshots`), proven live in `IncusColdMigrationLiveTest`. **[live]**

**GAP, named:** there is no snapshot retention or pruning. Backups have retention
(`hohenheim.backup.retention`, default 7, applied in `InstanceBackups`); snapshots
accumulate forever. On a pool-backed host that is a slow disk-exhaustion path.
Owning slice: the retention machinery that already exists for backups, extended
to snapshots. **Also:** every snapshot test needs a live daemon; there is no
daemon-free test of `InstanceSnapshots.create/restore/delete`.

## 8. Off-host backup and restore

**IMPLEMENTED, including restore to a DIFFERENT host.**

Backup targets are a registry (`BackupTargetKinds`) with two kinds:
`hohenheim:filesystem` (honest about sharing the controller's failure domain) and
`hohenheim:ssh` (argv pinned to the host record's confirmed identity, never
ambient ssh trust). A backup destination passes a TRUST gate only -- pinned,
operator-confirmed, unquarantined -- deliberately not the compute ADMISSION gate,
so a storage-only host never needs a runtime or an admit. Archives are encrypted
and manifest-checksummed. `restoreToNew` takes a destination host spelling.
**[code]** `server/backup/*`, `server/host/HostAdmission.java:112-121`,
`server/instance/InstanceBackups.java:246-309`.

- CI: `BackupArchiveTest` (4, including a flipped-ciphertext refusal and a
  foreign-keyring refusal), `BackupTargetsTest`, `ControlPlaneBackupTest` (3).
- Live: `LiveOffHostBackupTest` (backup to another machine and restore from it),
  `InstanceSnapshotBackupLiveTest`, `IncusSnapshotBackupLiveTest`.
- **Restore to a NEW host proven live 2026-08-06** in `IncusColdMigrationLiveTest`:
  refused by name onto a still-cordoned daystrom, then landing there after
  uncordon with the BACKED-UP state (v1, not the migrated v2). **[live]**

Control-plane backup (the DB plus the encryption keyring, which together hold
every fleet credential) is a scheduled task with its own retention and an
exercised restore. **[code]** `server/database/ControlPlaneBackups.java`,
`server/task/BackupControlPlane.java`.

**Rejected:** S3/object-storage targets. The two kinds cover the real workflows
and the registry is the extension point if that changes; adding a third kind
speculatively is scaffolding.

## 9. Console and rescue access

**IMPLEMENTED, both lanes.**

- **Serial/text**: `/ws/instance-console/{id}`, read-only stream plus a separate
  MANAGE-checked POST for commands (never raw keystrokes over the socket). Serves
  VMs as the text fallback. **[code]**
- **Framebuffer rescue console**: hypervisor-side by construction. The proxy polls
  the daemon's own VGA screenshot (PNG, digest-change-detected, 250ms active /
  1s idle) and pushes BINARY websocket frames, while INPUT rides a real live SPICE
  connection (REDQ link, RSA-OAEP empty ticket, MAIN for the session id, INPUTS
  for AT-set-1 scancodes) over the existing socket -- never a second client.
  Viewer is plumage's protocol-neutral `pl-framebuffer`. **[code]**
  `server/instance/VmFramebufferHandler.java`, `server/incus/SpiceConsole.java`.
- Authorization is the framework seam end to end: `requiresLogin` at handshake,
  per-record MANAGE in `onOpen` (1008), and `revalidate()` re-checking MANAGE under
  core's default-on revalidator, so a REVOKED viewer's OPEN socket is closed
  rather than merely refused later.
- CI: `VmFramebufferHandlerTest` (4), `VmFramebufferRevocationTest` (1, over a
  real socket, counterfactual recorded in the plan). Live:
  `VmFramebufferConsoleLiveTest`, and `IncusWindowsTemplateLiveTest` receives a
  PNG frame while Windows is still booting -- i.e. before any guest agent exists.

**ARCHITECTURE FINDING, recorded so nobody re-walks it:** qemu's SPICE server
GLZ-compresses every display image regardless of the client's declared
compression preference, so a live per-region SPICE display stream requires
implementing SPICE's LZ/GLZ/QUIC image codecs. That is why display is the
snapshot lane and input is the live lane. Named follow-up, not a gap in the gate.

Mouse is best-effort: frames are carried, but qemu boots in server mouse mode and
no mode negotiation is done. Keyboard is proven (the guest screen changed).

**Test gap, named:** `SpiceConsole` and `SpiceScancodes` have no dedicated test;
the handshake and scancode mapping are covered only incidentally by the live
framebuffer tests. The serial console handler has no CI-only test either.

## 10. Guest agent

**REJECTED as a requirement; DECLARED as a per-image capability.**

There is no incus guest agent for Windows, so `incus exec` against that tier is
impossible forever, not temporarily. Rather than branch on the OS, `guest_agent`
is a declared capability on the kind: `false` makes `runInstall` and
`runAppUpdate` REFUSE BY NAME instead of burning the 600s exec-ready timeout and
reporting a timeout as though the guest were broken, and `runInstall` refuses
BEFORE `create`, so no workload is born just to be torn down. `execWhenReady` has
exactly one caller, so a plain deploy never waited on the agent and an agent-less
VM deploys unchanged -- that was checked, not assumed. **[code]**
- Live: `IncusWindowsTemplateLiveTest` asserts the agent-less exec refusal on a
  record that was never created. **[live]**

**Consequence, stated:** there is NO guest-side egress probe for the agent-less
tier and there cannot be one -- every existing VM-tier egress probe pings FROM
the guest. "The Windows guest cannot reach a tenant peer" is verified in the
KERNEL, which is precisely the layer that does not need the guest, but it is not
observed from inside the guest.

## 11. Host drain, and workload migration

**IMPLEMENTED. Live migration REJECTED.**

- **Migration policy: COLD.** Stop, whole-instance export, import on the
  destination, start. Live migration is REJECTED and this is the decision the
  gate asks for: incus stateful transfer requires `migration.stateful` set before
  start, CRIU for containers and matched CPU flags for VMs, plus a
  daemon-to-daemon trust relationship the product deliberately holds nowhere.
  The gate's own "restore to a new host" wording implies the cold shape, and drain
  is operator maintenance where bounded downtime is acceptable. **[code]**
  `server/instance/InstanceMigrations.java:36-56`. Verified by grep: no
  `migration.stateful` and no CRIU code exists, only that rejection docblock.
- **Transport** is the EXISTING export/import pair, controller-mediated (daemon A
  -> controller staging -> daemon B), not incus's own cross-host copy -- the copy
  lane would be a second transfer path riding a second trust ceremony, while
  export/import already carries re-attribution, the MAC strip and the isolation
  rejoin. Cross-host incus remote trust stays UNCONFIGURED on both hosts,
  deliberately.
- **Ownership discipline:** `instances.server_id` stays the single pointer; M075
  adds `migrate_target_id`; the record's host remains the data authority until the
  source copy is VERIFIED gone, then ONE guarded statement
  (`InstanceOperationGuard.handoff`) repoints the record and RE-BASES the claim
  fence into the destination's lease domain. `STATUS_MIGRATING` is protected
  (deploy/stop refuse). **[code]**
- **Drain** is a real operator action: a row action on a CORDONED host
  (`drain_requires_cordon` otherwise) that migrates every live instance to a
  placement-chosen host. A workload that cannot move is REFUSED BY NAME and left
  exactly as it was -- drain is convenience, never authority to stop or destroy a
  tenant's workload -- and the report ends loudly INCOMPLETE naming the held
  workloads. Currently unmovable, each a named refusal: device rows (the
  whole-instance export does not carry custom volumes, so the destination would
  attach FRESH EMPTY disks -- the silent-success shape), port publications, and
  non-native drivers (the docker tier). **[code]** `server/cms/ServerResource.java:634-705`,
  `server/instance/InstanceMigrations.java:291-331`.
- CI: `InstanceMigrationTest` -- 4 journeys, all RAN, with both counterfactuals
  recorded in the plan (source removal disabled -> 3 of 4 journeys fail).
- **Live: `IncusColdMigrationLiveTest` re-run 2026-08-06 for this audit -- 1 test,
  PASSED, 0 skipped, 632s, daystrom -> nightstrom.** It moves a running VM with
  marker data, asserts the destination kernel names the migrated NIC's live tap,
  proves the negative (a peer cannot reach it) anchored by a positive that can
  reach 1.1.1.1, restores to a new host, and simulates a killed controller. **[live]**

**Open, stated:** there is no single-instance "migrate to host X" admin surface.
`migrateTo(instanceId, targetServerId)` has no UI caller; drain and the service
lane are its only production consumers. Device volumes are refused rather than
silently emptied. There is no docker-tier drain (the pieces exist in
`VolumeSnapshotSupport`; no consumer demanded it).

## 12. Capacity and placement

**IMPLEMENTED. Superseded 2026-08-07 -- see the block at the end of this item;
everything between here and it is the HISTORY it supersedes.**

~~**IMPLEMENTED as an ADMISSION gate. Resource-aware placement is a GAP.**~~

Placement is `InstancePlacement.chooseForBucket`: iterate hosts by id, skip the
excluded one, skip a runtime mismatch, skip a host that does not
`acceptsTenantWorkload` (admission must be `admitted`, posture must not be
`trusted_only`, identity re-verified, `dedicated` posture exclusive to one quota
bucket), then pick the lowest score. **[code]** `server/instance/InstancePlacement.java:92-148`.

**The score is a COUNT of live instance rows.** Verified by reading it: no CPU,
memory or disk figure is consulted at placement time. `ServerModel` carries no
capacity columns at all; the probe does read `NCPU` and `MemTotal` into the
display facts, and nothing reads them back for a decision. Per-workload
`limits.memory` / `limits.cpu` are applied to the daemon but never summed against
a host budget. So a host with 4 GB and one large VM outranks a host with 128 GB
and two small ones.

That is a GAP, not a rejection: the plan's own cross-cutting section promises an
"admission-time per-host capacity snapshot", and it does not exist. What DOES
exist is `RestoreCapacity`, a disk-only headroom check on the restore and
migration paths (see item 4) -- it is not called from the create path.

**DECISION 2026-08-06: resource-aware placement is DEFERRED, deliberately, and it
is deferred on an input problem rather than on effort.**

1. **The input does not exist in a usable form.** The per-workload figures a host
   budget would sum are `ResourceLimits`, and that type says in its own docblock
   that its members are "OPTIONAL, OPERATOR-CONFIGURED" and that "null or
   non-positive members mean unlimited". Exactly three kinds read it, all through
   `fromSettings`, and none requires it. So a budget summed over DECLARED limits
   is zero for the common workload -- a gate whose denominator is usually zero is
   decoration, and worse, it LOOKS like a gate.
2. **Making it real is a product decision, not a placement refactor.** It needs a
   DECLARED per-kind footprint ("what does a game server cost when the operator
   set no limit?"), which changes what a create form must ask for and what an
   admission promises. That is a fork for Jelle, not a scoring tweak.
3. **The reservation would have to be transactional, adjacent to the write, and
   MOBILE.** Per-host ledger buckets over the core reservation ledger can carry
   it, but a migration would have to move the charge between host buckets inside
   the same guarded handoff `InstanceOperationGuard.handoff` already performs, and
   drain would have to unwind it -- otherwise the budget drifts every drain. "A
   quota that cannot fail under concurrency is not a quota" is already a recorded
   lesson here; half of one is worse than none.
4. **There is a correctness bug on the same slice that outranks the scoring
   improvement**: the prepared-image constraint (item 2) makes placement CHOOSE a
   host whose deploy will then refuse by name. That is a wrong ELIGIBLE SET, not a
   wrong score, and it should land first.

What was done instead of half-building it: the capacity check that DOES exist now
has its test and one real defect fixed (item 4), and the chooser's actual
behaviour is pinned so a future resource-aware change has a counterfactual --
**[test]** `InstancePlacementTest`, 2 journeys, both RAN 2026-08-06: the eligible
set, the fewest-live-instances score, the lowest-id tie-break, the exclude
argument, and the dedicated posture's exclusivity (including that the operator's
own empty-set bucket gets no pass onto a taken dedicated host). That test is
written as a CHARACTERIZATION and says so: when a declared footprint lands, it is
the thing that must change with it.

Owning slice, unchanged: the capacity/placement slice -- land the declared
footprint, the admission-time snapshot, the host-bucket reservation, and the
prepared-image eligibility fix together.

Quota is a separate axis and IS implemented: per-owner instance counts, disk GB
and extra-NIC slots over the core reservation ledger, with per-owner overrides
and settings defaults. **[test]** `InstanceDeviceQuotaTest` (3 tests, RAN),
`InstanceQuotaTest`.

**Untested placement behaviour, named:** nothing asserts the fewest-instances
ordering or the lowest-id tie-break, and nothing asserts `POSTURE_DEDICATED`
exclusivity -- the posture appears in tests only as a form-edit assertion.

### SUPERSEDED 2026-08-07: resource-aware placement is IMPLEMENTED

The 2026-08-06 deferral above stands as HISTORY and its four reasons were each
answered rather than waived. What shipped:

**1. The product decision, which is what the deferral was really blocked on.**
A workload is admitted as its DECLARED `memory_limit_mb`; when it declares none
it is admitted as its KIND's DECLARED footprint
(`InstanceKindHandler.defaultFootprintMb()`, abstract with no interface default:
docker container 512, incus container 512, incus VM 1024, site container 512).
So the denominator is never zero, which is the exact defect that made a
limits-summed budget decoration. **[code]**
`server/instance/InstanceKindHandler.java`, the four kind classes.

The half that makes it a gate rather than a planning number: the SAME number is
applied as the real cgroup / VM memory cap, through
`ResourceLimits.fromSettings(settings, defaultFootprintMb())`. Charge == cap. A
workload physically cannot grow past what the ledger booked for it, so
"the footprints fit but the host is really full" cannot silently happen for
booked memory. It can still happen for what is NOT booked -- page cache, the
daemon itself, a stack container, disk, CPU -- which is what
`capacity.host_memory_reserve_mb` exists for and is the honest, stated limit of
this gate.

WHAT WAS REJECTED, and why:

- **A per-TEMPLATE footprint.** Templates are one create surface among several
  (the CMS create and the site tier author instances with no template at all), so
  a footprint only some creates carry leaves the others at zero -- the same
  defect in a smaller box. A template still sets `memory_limit_mb` like any other
  settings key, which gives per-template sizing without a second mechanism.
- **"Unbounded workloads do not participate in capacity."** This is the status
  quo with a rule written next to it. A host full of unbounded workloads would
  read as empty, and the gate would report success while doing nothing -- the
  silent-success shape.
- **Charging CPU as well.** CPU is timeshared and overcommitting it degrades;
  memory overcommit OOM-kills. One booked dimension, named, beats two where one
  is decorative. `cpu_limit` is deliberately NOT defaulted for the same reason: a
  surprise CPU cap would throttle workloads against a budget that does not exist.
- **Overcommit forbidden.** It is a legitimate operator policy, so it is a
  setting (`capacity.memory_overcommit_ratio`, default 1.0) whose own description
  says the kernel OOM killer settles the bet, not this controller.

**2. The eligible set is now the DEPLOY PATH'S OWN AUTHORITY**, which was the
defect the deferral ranked above the scoring change. `acceptsTenantWorkload` used
to re-state a SUBSET of `HostAdmission.requireInstancePlacement` inline, so every
gate added to the deploy path since -- kernel truth most recently -- was missing
from the chooser and placement could CHOOSE a host whose deploy then refused by
name. It now CALLS that gate. The prepared-image constraint rides a second seam,
`InstanceKindHandler.requirePlaceableOn`, whose Incus implementation calls
`IncusInstanceRuntime.requirePreparedImagePresent` -- the same method `create()`
calls, extracted rather than copied. A catalog image short-circuits before any
daemon call, so the common create path pays nothing.

**3. The reservation is the CORE LEDGER, transactional and adjacent to the
write**: `InstanceCapacity` over zenit `Quotas`, bucket `hohenheim:host_mem_mb:<id>`,
installed beside `InstanceQuota` on the same beforeWrite hook. It is MOBILE --
a `server_id` change releases the source bucket and books the destination in the
same write, so a drain does not drift the budget -- and the booked amount is
STAMPED on the row (`instances.capacity_mb`, M080) so a release hands back
exactly what was taken even after the settings change. Every terminating path was
audited: create, soft delete (the only lane `InstanceService.destroy` takes, and
the one the remove hooks never fire on), restore, host change, footprint change,
hard delete.

**4. The input, and its freshness.** The budget is the STORED preflight fact
`mem_total`, minus `capacity.host_memory_reserve_mb`, times
`capacity.memory_overcommit_ratio`. The Incus battery never stored it (only
`ServerService`'s in-memory summary probe read `/1.0/resources`), so it now does,
under the same fact names as the Docker battery, with a REQUIRED `resources`
check -- a host whose inventory cannot be read fails preflight rather than
silently having no budget.

**WHERE the budget is ENFORCED, which is a scope decision and was corrected mid-wave
against evidence.** The first shape refused every write onto a host with no usable
reading (`host_capacity_unproven`, the RestoreCapacity stance). Running the full
suite against it produced 31 failures across 26 classes, and reading them showed
the stance was wrong rather than the tests: it also refused the site tier's
lowered containers on the implicit LOCAL daemon, which is never admitted and never
preflighted, so a fresh install could not run a single site until someone ran
preflight on the operator's own machine. What ships instead:

- The RESERVATION books on any host and judges against the budget when one exists,
  against nothing when it does not -- usage is counted either way, exactly the
  `max_instances_per_owner` semantics, so the ledger holds honest numbers the
  moment a preflight lands.
- The CHOOSER never picks an unmeasured host. So the only ways to land on one are
  an operator naming it explicitly and the site tier's own daemon -- both explicit
  operator choices about the operator's own machine.
- Consequence: on every host placement can choose, the denominator is a real
  measurement and the gate CAN fail. That is the property that was at stake; the
  unmeasured host is not a passing capacity check, it is a host outside the
  rationing, said out loud.
- `host_capacity_unproven` survives as the PLACEMENT refusal: when eligible hosts
  exist but none is measured, the refusal names the host to preflight instead of
  the generic "nothing accepts this workload". FOUR named placement refusals now
  exist and they have four different fixes, checked in this order: the KIND's own
  refusal (`host_prepared_image_missing` -- publish that image on that host; it is
  first because such a host passed every admission gate, so it is the one
  actionable sentence in the walk, and swallowing it is what would have made that
  microcopy key unreachable), `no_placement_capacity` (free memory or admit a
  host), `host_capacity_unproven` (run preflight on {name}), and
  `no_placement_available` (admit a host or widen a posture). A host whose daemon
  cannot be ASKED is excluded silently and falls to the generic refusal: a
  transient connection error is not the placement reason.

The last wave's known limitation -- stored preflight verdicts have no freshness
bound -- was bounded HERE and only here: `capacity.facts_max_age_hours` (default
168) makes a stale reading unusable for placement, by name, telling the operator
to re-run preflight; 0 removes the bound as an explicit choice. The ADMISSION
gate's staleness is deliberately NOT bounded in the same move: that would
silently cordon hosts already carrying production work, which is an availability
decision an operator makes, not a wave.

**[code]** `server/instance/InstanceCapacity.java`,
`server/instance/InstancePlacement.java`, `common/HohenheimSettings.java`
(`Capacity` group), `server/migration/M080_InstanceCapacity.java`,
`server/host/IncusPreflight.java`.
**[test]** `InstancePlacementTest` (3 journeys, RAN 2026-08-07) -- the eligible
set as HostAdmission's own, the booked-memory score with a positive anchor, the
capacity-versus-availability refusal split, the unmeasured host, the dedicated
posture. `InstanceCapacityTest` (2 journeys, RAN 2026-08-07) -- every terminating
and moving path, six racing creates against one host's last megabyte, both
refusal identities, the freshness bound and its opt-out.

**Known limitations, stated:**

- The booking is MEMORY only. Disk headroom is still `RestoreCapacity` on the
  restore/migration paths and is NOT consulted at create; CPU is not booked at all
  and that is a decision, not an omission (see above).
- A workload is booked on its RECORD'S EXISTENCE, not on whether it is running. A
  stopped guest can be started again without asking anyone, so booking only running
  workloads would move the refusal to START, after the operator already believed the
  guest existed. Consequence, intended and visible: a fleet of defined-but-stopped
  guests consumes the host budget, and freeing it means destroying a record or
  shrinking its declared memory. This is what `IncusWindowsTemplateLiveTest` hit --
  three 2 GB VM records on a 3907 MB host, only one of which ever ran; the ledger
  refused the third correctly (`host_capacity_reached needed=2048 free=1280`) and the
  fixture now sizes the two that never boot honestly.
- Nothing REBALANCES an existing fleet; the gate decides where the NEXT workload goes.
  Drain moves the charge with the workload but chooses no better destination than
  the ordinary chooser would.
- The stored reading is a MEASUREMENT of the machine, not of what is running on it.
  Booked memory and actual RSS can differ; the reserve exists for the gap.

## 13. Node failure recovery

**Controller failure IMPLEMENTED. HOST failure REJECTED (no automatic failover).**

The two are different and the product only claims one.

- **Killed controller:** `InstanceMigrations.recoverInterrupted` runs at boot
  (INSTANCES role) and settles every mid-migration record from DAEMON
  ATTRIBUTION (`claimOf`: ABSENT / OURS / FOREIGN): source still holds it -> roll
  back; source empty and destination holds ours -> complete the handoff; neither
  -> ERROR, loudly; an unreachable daemon DEFERS rather than manufacturing a
  verdict. Recovery restores one truthful owner; it never auto-starts anything.
  Host leases with fencing tokens stop two controllers from mutating one daemon.
  **[code]** `server/instance/InstanceMigrations.java:346-436`, `server/host/HostLeases.java`.
  **[live]** the killed controller is simulated on two real daemons in
  `IncusColdMigrationLiveTest` (re-run 2026-08-06).
- **Dead HOST: there is no automatic restart-elsewhere, deliberately.** A dead
  host's workloads stay assigned to it; the operator cordons and drains, or
  restores a backup elsewhere. Automatic failover without shared storage means
  restoring a workload from a backup at some earlier point in time while the
  original may still be running -- a split-brain data-loss machine, which is
  exactly why Proxmox HA needs a quorum and fencing we do not have (see item 14).
  A crashed WORKLOAD is restarted on the same host by `CRASH_RESTART`; that is a
  different thing and it is not host failover.

**GAP found during this audit (see Finding 2 below):** there is no scheduled
host-health task at all. The only recurring liveness signal is a side effect of
the hourly Docker reconcile, which means **an Incus-only host had NO heartbeat**
-- and worse, was being stamped as a failure by that sweep. Half of that is now
fixed; the missing half is that Incus hosts still have no positive heartbeat.
Owning slice: a host-health task, or an Incus-side equivalent of the reconcile
sweep.

## 14. Clustering and HA

**REJECTED. Standalone daemons only.**

Verified by grep: the string `cluster` does not appear anywhere in `src/`, and
neither does `quorum`.

The reasoning, and it is a schema decision rather than a feature preference:
"runtime = data on the server record" bakes in a 1:1 runtime-to-host assumption
that an Incus cluster breaks in three places at once -- placement (a cluster
member is not a host row), storage (pools become member-scoped, and
`RestoreCapacity.rootPoolOf` would be answering for the wrong member), and quorum
(a cluster has an availability model of its own that our lease/fence discipline
does not compose with). Adopting clustering is a schema change plus a placement
rewrite, not a driver flag.

HA follows from that: without shared storage and a quorum there is nothing to
fail a workload over TO that would not be a stale copy. The honest substitute we
DO ship is cold migration plus drain plus off-host backup with restore-to-a-new-host,
all proven live on two hosts.

Revisit when a concrete cluster need exists; the schema assumption is the thing to
revisit first.

## 15. PCI / GPU / USB passthrough

**REJECTED for now, and it is a genuine capability gap rather than a redundancy.**

Verified by grep: no `gpu`, no `unix-char`, no `unix-block`, no device
passthrough of any kind (the `passthrough` hits in `src/` are all TLS SNI
passthrough in the proxy). The device vocabulary is closed at two values, disk
and nic (`InstanceDeviceModel.TYPE_DISK` / `TYPE_NIC`).

Rejected because we do not currently run a workload that needs it, and because
passthrough is the one device class that is NOT safe to hand a hostile tenant:
a passed-through PCI function gives the guest DMA-capable access to real hardware,
which undoes the isolation boundary VMs exist to provide. If it lands, it lands
operator-only, on a `dedicated`-posture host, as a third `DeviceAttachSupport`
device type -- the extension point exists.

## 16. ISO install

**REJECTED, consistent with the phase body ("no in-panel OS install initially").**

Verified by grep: no `cdrom` device, no `boot.priority`, no ISO upload surface.
Guests come from images only. The substitute is PREPARED templates (item 2), and
the operator-side procedure for producing one -- including the four findings that
each cost a boot cycle -- is `docs/prepare-windows-template.md`.

This is a real reduction in operator freedom compared to Proxmox and it is
accepted: an in-panel ISO installer means accepting operator-supplied bootable
media, an interactive install console as a REQUIRED path rather than a rescue
hatch, and boot-order editing. Template-once/clone-many is the shape we actually
operate.

---

## Findings from this audit

Two defects were found by reading the code rather than the plan. Both were fixed
in this pass, each with a counterfactual.

### Finding 1: the per-owner disk and NIC quota overrides could not be set by anyone

`InstanceQuotaResource`'s form and table listed only `SUBJECTS` and
`MAX_INSTANCES`. M073 added `max_disk_gb` and `max_nics`;
`InstanceDeviceQuota.diskLimitFor` / `nicLimitFor` read them ahead of the global
default; both columns carry label and help microcopy in both locales. But the
generated form never declared them, and a zenit form drops what it does not
declare -- so the submission SUCCEEDED and the value vanished. Enforced,
documented, copy-written, unreachable.

Fixed by adding both fields to the form and the table, with an AIDEV-NOTE naming
the shape. New test `InstanceDeviceQuotaTest#perOwnerCapsSubmittedThroughTheAdminFormAreWhatTheReserveHooksRead`:
submits both caps through the real resource route, asserts the stored row, asserts
`diskLimitFor`/`nicLimitFor` return exactly what was submitted, then updates to 0
and asserts 0 survives as 0 (that "this owner gets nothing" is not confused with
"no override").

Counterfactual (fields removed again), verbatim:

```
[step 1: the submitted disk cap is STORED, not dropped by the form]
java.lang.AssertionError: [step 1: the submitted disk cap is STORED, not dropped by the form]
Expecting actual not to be null
```

Restored: 3 of 3 RAN and passed.

### Finding 2: the hourly Docker sweep was stamping every Incus host as UNREACHABLE

`DockerReconciler.sweepAll` iterated `ServerService.names()` -- EVERY host row --
and called `clientFor(name)`, which refuses an Incus host by construction
("declares the incus runtime; it has no Docker daemon to address"). That refusal
landed in the `catch`, where `HostProbe.recordFailure` classified it as
`UNREACHABLE` and wrote it to the host record. Every hour, on every Incus host.

The comment at the call site says this sweep "doubles as the host heartbeat", and
that is exactly why it mattered: a structural non-answer was being recorded as a
probe verdict. Consequences, in order of severity:

1. `last_error_kind` is also the STICKY QUARANTINE TOKEN. `HostPins.isQuarantined`
   returns true on `host_key_changed`, and only `HostPins.repin` is supposed to
   clear it -- the AIDEV-NOTE on `HostProbe.quarantine` says out loud that there
   is "deliberately no matching automatic upgrade". An Incus host quarantined by a
   live TLS-pin contradiction (which records the kind but no offered material) had
   that verdict overwritten with `unreachable` within the hour, by an unrelated
   task, with no operator act. The counterfactual below is that overwrite, observed.
2. The host's real last error was destroyed hourly, so the admin surface showed
   every Incus host as permanently unreachable.

Fixed by adding `ServerService.dockerNames()` (docker-runtime hosts only) and
sweeping that. New test
`DockerReconcilerTest#theDockerSweepNeverProbesOrRestampsAnIncusHost`: asserts the
inventory still contains the docker host (so a fix that swept NOTHING would fail),
that the sweep's result map does not name the incus host, that its typed verdict,
its error text and its never-set `last_seen_at` are untouched, and that
`HostPins.isQuarantined` still answers true afterwards.

Counterfactual (`names()` restored), verbatim:

```
[step 2: the docker sweep must not restamp an incus host's typed verdict (this is
the quarantine token; only a repin clears it)]
org.opentest4j.AssertionFailedError: expected: "host_key_changed" but was: "unreachable"
```

Restored: 9 RAN and passed, 1 SKIPPED (the live-daemon orphan sweep, no Docker
socket on this machine).

**NOT fixed here, named as open:** the general shape behind consequence 1 is that
ANY `HostProbe.recordFailure` with a weaker kind, and `recordSuccess` outright,
clears the quarantine token. Removing the Docker sweep removes the automatic
hourly trigger, but a host that key-changes and is then merely powered off still
loses its verdict on the next probe. Whether a transient probe outcome may clear a
trust verdict is a security-state-machine decision, not a cleanup -- it belongs to
whoever owns the host trust slice.

**SUPERSEDED 2026-08-07 (trust-state-machine wave): CLOSED.** M078 gives the
quarantine verdict its own columns (`quarantined_at` + `quarantine_reason`,
backfilled), no probe path writes them, and only `HostPins.repin` clears them.
`isQuarantined` still asks both markers, because a disagreeing rescan and a
refused connection are still different writers. Pinned by
`HostQuarantineStickinessTest`.

---

## Verdict

The inventory is CLOSED: every item the plan enumerates has a decision and
evidence.

**Four of the six gaps this document opened are closed.** Device-surface wave,
2026-08-06: device editing HAS an operator surface (item 3, panel + API +
`InstanceDeviceSurfaceTest`), and `RestoreCapacity` has its test plus a fixed
defect (item 4). Trust-state-machine wave, 2026-08-07: the optional kernel-truth
lane (item 5) is now an admission REQUIREMENT. Capacity wave, 2026-08-07:
placement is resource-aware (item 12). Three remain, each with its owning slice:

1. **No root-disk size knob** (item 3).
2. **No snapshot retention** (item 7).
3. **No host-health heartbeat for Incus hosts** (item 13).
4. ~~**The kernel-truth ssh lane is optional** (item 5)~~ -- CLOSED 2026-08-07:
   verification is an admission requirement for any tenant-accepting posture,
   proven at preflight and refused by name at admit and placement. See the
   superseding block under item 5 above.
5. ~~**Placement is not resource-aware** (item 12)~~ -- CLOSED 2026-08-07: a
   workload is admitted as its declared memory limit or its KIND's declared
   footprint, that same number is the cap the daemon applies, the host budget is
   the measured `mem_total` under a freshness bound, the reservation rides the
   core ledger and moves with a migration, and the eligible set is
   HostAdmission's own rather than a copy of a subset of it. See the superseding
   block under item 12 above.

None of these is a hidden gap; all are written down with an owner. What would
still block calling this a general Proxmox replacement, in one sentence: clustering,
HA, live migration, passthrough and ISO install are rejected rather than delivered,
which is the right answer for the fleet we run and the wrong answer for someone who
needs any of them. "An operator cannot add a disk or a NIC from the panel" and
"placement does not know how big a host is" were the other two sentences here and
no longer are.
