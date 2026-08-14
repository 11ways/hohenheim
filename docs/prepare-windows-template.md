# Preparing a Windows instance template

This is an OPERATOR procedure, not a product feature. Hohenheim provisions a Windows
VM from an image that already exists in the target Incus daemon's own image store
(`image_origin=prepared`); it does not install an operating system. Producing that
image is what this document describes, end to end, with the commands that were
actually run.

UPDATE 2026-08-14: the product now carries an in-panel counterpart for most of
this. The server record's Install media tab fetches ISOs onto a host, an
`image_origin=install_media` VM is created EMPTY, the ISO attaches as a cdrom
device (boot order encoded from step 5's finding), the framebuffer console drives
the interactive install, and the instance's "Capture as template" action replaces
step 7's hand-run `incus publish` (unapproved template minted, host-pinned). The
media REPACKING in steps 1-4 (virtio injection, noprompt boot, the answer-file CD)
and step 6's sysprep remain hand work this document still owns.

Everything below was executed on `daystrom` (Arch, incus 7.3, 3 vCPU, 3907 MB RAM,
40 GiB btrfs pool) on 2026-08-06 and produced a working, generalized Windows Server
2025 Standard Evaluation (Server Core) template. Timings are from that run.

## Why a prepared template rather than an in-panel install

- A Windows guest has no cloud-init. The Linux VM tier's provisioning vocabulary
  (`cloud_init` on the VM kind, `{{KEY}}` substituted from instance variables) has no
  counterpart the guest will read.
- Windows Setup will not see a virtio disk or a virtio NIC unless the drivers are in
  the install media. `distrobuilder repack-windows` injects them into `boot.wim` and
  every `install.wim` index.
- The product therefore takes the finished image. What a prepared image needs that a
  cloud-init Linux guest does not is DECLARED, never inferred, on the VM kind:
  `image_origin`, `secure_boot`, `guest_agent`. See `IncusVmKind`.

## What you need on the Incus host

`distrobuilder` (3.3.1 here), `wimlib` (`wimlib-imagex`, pulled in by distrobuilder),
`cdrtools`/`cdrkit` for `genisoimage`, and about 20 GiB free on the filesystem holding
the working directory, plus ~25 GiB in the storage pool for the build VM.

```bash
mkdir -p /root/win-template && cd /root/win-template
```

## 1. Download the media

Microsoft publishes Windows Server evaluation ISOs for exactly this purpose. The
`fwlink` below resolved to
`26100.1742.240906-0331.ge_release_svc_refresh_SERVER_EVAL_x64FRE_en-us.iso`.

```bash
curl -L -o win2025-eval.iso 'https://go.microsoft.com/fwlink/p/?linkid=2293312'
curl -L -o virtio-win.iso \
  'https://fedorapeople.org/groups/virt/virtio-win/direct-downloads/stable-virtio/virtio-win.iso'
```

Verified sha256 of the two files used here (the virtio one is the stable pointer,
which resolved to virtio-win-0.1.285; it moves, so treat it as a record of what was
tested, not as a pin to enforce):

```
d0ef4502e350e3c6c53c15b1b3020d38a5ded011bf04998e950720ac8579b23d  win2025-eval.iso
e14cf2b94492c3e925f0070ba7fdfedeb2048c91eea9c5a5afb30232a3976331  virtio-win.iso
```

## 2. Inject the virtio drivers

```bash
distrobuilder repack-windows win2025-eval.iso win2025-incus.iso \
  --drivers virtio-win.iso --windows-version 2k25 --windows-arch amd64
```

About 60 seconds. It mounts both ISOs, modifies `boot.wim` indexes 1-2 and all four
`install.wim` indexes, and regenerates the ISO.

## 3. Make the media boot without a keypress

TRAP, and it costs a whole boot cycle to discover: the repacked media still boots
through `cdboot.efi`, which prints "Press any key to boot from CD or DVD" and, with
nobody to press one, TIMES OUT and falls through to PXE. Observed verbatim on the
framebuffer console:

```
BdsDxe: failed to start Boot0002 "UEFI QEMU QEMU CD-ROM " ... : Time out
>>Start PXE over IPv4.
```

Windows media ships the fix alongside the problem: `efisys_noprompt.bin` is the same
El Torito FAT image built around `cdboot_noprompt.efi`. Both are exactly 1474560
bytes, so the boot image can be replaced in place rather than by re-mastering a 6 GiB
ISO (which you cannot do casually anyway -- see the UDF note below).

```bash
mkdir -p /mnt/iso && mount -o loop,ro win2025-incus.iso /mnt/iso
cp /mnt/iso/efi/microsoft/boot/efisys_noprompt.bin .
umount /mnt/iso

# Find the LBA of the EFI El Torito boot image, then overwrite it.
python3 - <<'EOF'
import struct
iso = 'win2025-incus.iso'
with open(iso, 'rb') as f:
    for sector in range(16, 25):
        f.seek(sector * 2048)
        d = f.read(2048)
        if d[:7] == b'\x00CD001\x01' and b'EL TORITO' in d:
            catalog = struct.unpack('<I', d[71:75])[0]
            f.seek(catalog * 2048)
            c = f.read(2048)
            # offset 96 is the EFI (platform 0xef) section entry
            print('efi boot image LBA:', struct.unpack('<I', c[104:108])[0])
            break
EOF
# LBA was 1688 here; 1474560 bytes = 720 ISO sectors.
dd if=efisys_noprompt.bin of=win2025-incus.iso bs=2048 seek=1688 count=720 conv=notrunc
dd if=win2025-incus.iso bs=2048 skip=1688 count=720 status=none | cmp - efisys_noprompt.bin \
  && echo PATCH-VERIFIED
```

DO NOT try to add `autounattend.xml` to this ISO instead of using a second CD. A
Windows install ISO is UDF-primary (`install.wim` is 5.27 GB, which ISO 9660 cannot
hold in one extent), and `xorriso` only edits the ISO 9660/Joliet trees. The file
appears in `xorriso -find` and is invisible to Windows. It also discards the El
Torito boot record on commit.

## 4. Build the answer-file CD

WARNING about the checked-in answer files: both carry the literal administrator
password `Hohenheim!Tpl1` in plain text, because an unattended install has nowhere else
to put it. That is a THROWAWAY value for a lab template. Change it before building any
template a tenant will touch, and treat the resulting image as holding a known
credential until you do.

Two findings shaped this file, both of them a full boot cycle each to establish:

- **`<DiskConfiguration>` is fatal on Server 2025.** The classic element makes the
  redesigned Setup fail immediately with `Error code: 0x80070002 - 0x40030`, before
  it writes a partition table (verified: the disk still had no GPT). Partition with
  `RunSynchronous` + `diskpart` instead. A minimal answer file with only
  `Microsoft-Windows-International-Core-WinPE` proved the file itself was being read
  correctly, which is what isolated this.
- **Use `/IMAGE/INDEX`, not `/IMAGE/NAME`.** Setup's image picker displays
  "Windows Server 2025 Standard Evaluation" while the WIM's internal name is
  "Windows Server 2025 SERVERSTANDARDCORE"; index 1 is unambiguous. (This was not
  the cause of the error above, but it is the safer spelling.)

Ruled out along the way, so nobody re-walks them: the second CD-ROM device itself
(a dummy ISO with no answer file installs fine), the `<ProductKey>` element, and the
`Microsoft-Windows-TerminalServices-*` components.

The file that produced this template is checked in at
`docs/windows-template/autounattend.xml` -- copy it, change the password, and skip the
description below unless something breaks. It contains:

- `windowsPE`: `Microsoft-Windows-International-Core-WinPE` (locales) and
  `Microsoft-Windows-Setup` containing, IN THIS ORDER (the component's children are a
  schema SEQUENCE), `ImageInstall` (`/IMAGE/INDEX` = 1, `InstallTo` disk 0 partition
  3), `RunSynchronous`, `UserData` (`AcceptEula`). The `RunSynchronous` block is nine
  commands: eight `cmd /c echo <line> >> X:\dp.txt` writing the diskpart script, then
  `cmd /c diskpart /s X:\dp.txt`. The script is:

  ```
  select disk 0
  clean
  convert gpt
  create partition efi size=256
  format quick fs=fat32 label=System
  create partition msr size=16
  create partition primary
  format quick fs=ntfs label=Windows
  ```

- `specialize`: `Microsoft-Windows-Shell-Setup` with `ComputerName` and `TimeZone`.
- `oobeSystem`: `Microsoft-Windows-Shell-Setup` with `UserAccounts` /
  `AdministratorPassword`, the `OOBE` hide flags, `AutoLogon`, and
  `FirstLogonCommands` that enable RDP (`fDenyTSConnections=0`, the "remote desktop"
  firewall group, an explicit 3389 inbound rule, an inbound ICMPv4 rule for
  reachability probes, `sc config TermService start= auto`, `net start TermService`).

RDP is enabled through `FirstLogonCommands` rather than the
`Microsoft-Windows-TerminalServices-*` components deliberately: the registry write is
one less thing that can go missing from a component manifest.

```bash
genisoimage -quiet -J -r -V UNATTEND -o unattend.iso unattend/
```

## 5. Install

```bash
incus init --empty --vm winbuild -c limits.cpu=3 -c limits.memory=2560MiB -d root,size=24GiB
incus config device add winbuild install  disk source=/root/win-template/win2025-incus.iso
incus config device add winbuild unattend disk source=/root/win-template/unattend.iso
# The firmware only lists a CD that carries a boot.priority. Give the DISK the higher
# priority: while it is blank the firmware falls through to the CD, and the moment
# Windows makes it bootable the installer stops being re-entered from the CD.
incus config device set winbuild root    boot.priority=10
incus config device set winbuild install boot.priority=5
incus start winbuild
```

Do NOT give the install CD the higher priority. It boots the CD again after the
first-phase reboot and Setup stops on "It looks like you started an upgrade and booted
from installation media", which no answer file covers. If you have to restart an
install, wipe the disk first (`dd if=/dev/zero of=<pool>/virtual-machines/winbuild/root.img
bs=1M count=200 conv=notrunc`), or Setup finds the leftover `$Windows.~BT` and asks
the same question.

Secure Boot: left at the incus default (ON). Microsoft-signed Windows media boots
under it. This is why `secure_boot` is a declared setting on the VM kind rather than
the hardcoded `false` the Linux catalog images need. No TPM was attached and none is
needed -- Windows Server 2025 has no TPM requirement (Windows 11 client does).

Watch it without any client software; the daemon renders the framebuffer for you:

```bash
incus query /1.0/instances/winbuild/console?type=vga --raw > shot.png
```

About 22 minutes on this host, unattended, from `incus start` to a logged-in SConfig
screen. Verify:

```bash
incus list winbuild -c ns4      # an IPv4 lease means the virtio NIC works
timeout 5 bash -c '</dev/tcp/<ip>/3389' && echo "RDP open"
```

## 6. Generalize

A template that is not generalized clones the same SID and computer name into every
tenant. Sysprep it, with an answer file so each clone finishes OOBE by itself instead
of stopping at a password prompt no one can answer.

There is no incus guest agent for Windows, so there is no `incus exec` here. The
build VM's own WinRM (SConfig reports "Remote management: Enabled", port 5985) is the
lane, from the host:

```bash
python3 -m venv /root/winrmenv && /root/winrmenv/bin/pip install pywinrm requests_ntlm
```

Push `docs/windows-template/sysprep-unattend.xml` to
`C:\Windows\System32\Sysprep\unattend.xml` (base64 in chunks over WinRM; a single
oversized WinRM command fails). It contains:

- `generalize`: `Microsoft-Windows-PnpSysprep` with `PersistAllDeviceInstalls=true`,
  so the injected virtio drivers survive (the target hardware is the same qemu).
- `specialize`: `ComputerName` of `*` (a random name per clone -- incus refuses a
  second NIC on the primary network over an instance DNS-name conflict, so identical
  names across tenants are a real hazard), `TimeZone`.
- `oobeSystem`: the same `UserAccounts` / `OOBE` / `AutoLogon` / `FirstLogonCommands`
  RDP block as the install answer file. Setting `AdministratorPassword` is also what
  re-enables the built-in Administrator, which generalize disables.

Then:

```bash
# via WinRM, as Administrator
Start-Process -FilePath C:\Windows\System32\Sysprep\sysprep.exe `
  -ArgumentList '/generalize','/oobe','/shutdown','/quiet',`
                '/unattend:C:\Windows\System32\Sysprep\unattend.xml'
```

Sysprep takes several minutes on this host and powers the VM off when done. Wait for
`incus list winbuild -c s` to read STOPPED -- do not force-stop it.

## 7. Publish the image

```bash
incus config device remove winbuild install
incus config device remove winbuild unattend
incus publish winbuild --alias win2025-core --compression zstd \
  description="Windows Server 2025 Standard Eval (Core), virtio + RDP, sysprepped"
incus image list win2025-core
```

The alias is what the product's instance settings name. Publishing is per DAEMON:
an image published on one host is not on another. Repeat step 7 against each host, or
`incus image export` / `incus image import` the tarball, and keep the alias identical
so one approved template serves every host.

Delete the build VM and the working files when you are done; the ISOs are 13 GiB.

```bash
incus delete winbuild
rm -rf /root/win-template
```

## 8. Declare it in hohenheim

Create an instance template of kind `hohenheim:incus_vm` and approve it (an
unapproved template is not tenant-selectable, and `InstanceImagePolicy` authorises a
tenant's image only against an approved template's kind + image + tag + origin).
Settings:

| setting | value | why |
| --- | --- | --- |
| `image` | `win2025-core` | the alias published in step 7 |
| `image_origin` | `prepared` | resolve in the daemon's own image store, never fetch |
| `secure_boot` | `true` | Microsoft-signed media; the Linux default of `false` is wrong here |
| `guest_agent` | `false` | there is no incus guest agent for Windows |
| `cloud_init` | empty | a stock Windows image reads nothing from the config drive |
| `memory_limit_mb` | at least `2048` | |

`image_origin=prepared` is checked before the daemon is asked to create anything: a
missing alias is refused by name rather than surfacing as a generic create failure.
`guest_agent=false` makes any exec-driven operation (template install script, app
update) refuse by name instead of waiting out the 600-second agent-ready window and
reporting a timeout as if the guest were broken.

## What the product gives a Windows tenant, and what it does not

- Provisioning from the prepared image under the quota ledger, with owner labels, the
  image-fingerprint pin, and the shared isolation ACL verified in the host KERNEL.
- The framebuffer rescue console. This is hypervisor-side and is the ONLY way in when
  the guest has no network, no drivers or a failed boot -- exactly the Windows failure
  modes. RDP is guest-side and is not a substitute for it.
- No `incus exec`, so no install script, no app update, no in-guest file operations.
  Every one of those refuses by name.
- Nothing writes into the guest. If a tenant needs per-instance configuration inside
  Windows, the image must carry cloudbase-init and the template must declare the
  `cloud_init` text; that combination is untested here.
