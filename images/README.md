# Runtime images ("yolks")

Each directory here is the build context of one built-in `runtime_images` row
(`RuntimeImageSeeder`). Jelle's 2026-08-22 decision on phase-0 open question 5:
**there is no registry**. Every host builds these locally from this tree at first
use, so the first create on a fresh host is slower and nothing has to be pushed,
pulled or authenticated anywhere.

Conventions every image must keep, because the workspace/application lanes rely on
them rather than on inspecting the image:

- A `tini`-style PID 1 that reaps children and forwards SIGTERM.
- `/home/site` exists and is the working directory: it is the ONLY path a workspace
  volume is mounted at, and everything outside it is disposable.
- The workload runs as the numeric uid the runtime passes in. There is deliberately
  no unix ACCOUNT for it on the host or in the image, only a number.
- `/bin/bash` present, because that is the shell `runtime_images.shell` names.

A file at the ROOT of this directory is SHARED: `RuntimeImages.materializeContext`
copies it into every build context before the image's own files, which is how
`hohenheim-init` exists once instead of once per Dockerfile. A Docker build context
still cannot reach outside itself; the sharing happens before the build, not during it.

`hohenheim-init` is the INCUS lane's PID 1. Docker starts the workload command itself
(tini as the entrypoint, the container's `User` field for the identity); an Incus
system container execs `/sbin/init`, so the controller points `lxc.init.cmd` at this
script and hands it `HOHENHEIM_START_COMMAND`, `HOHENHEIM_RUN_UID` and
`HOHENHEIM_WORKDIR` through the container environment. On Incus the uid is a
NAMESPACE id: the volume directory on the host belongs to the host uid it maps to
(`WorkspaceUids.incusHostUid`), which needs no `/etc/subuid` change and no kernel
idmapped-mount support.

The Incus variant of an image is the SAME image, converted on the host:
`docker export` of a container created from the built image is a rootfs tarball, and
that plus a generated `metadata.yaml` is what `incus image import` takes
(`RuntimeImages.ensureIncusImage`). There is one build and one package list for both
runtimes -- no registry, no second distrobuilder definition to keep in step.

Adding an image is a directory here plus a row in `RuntimeImageSeeder`; the seeder
is `sync`, so its rows are code-owned and an edit to a built-in reverts.
