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

Adding an image is a directory here plus a row in `RuntimeImageSeeder`; the seeder
is `sync`, so its rows are code-owned and an edit to a built-in reverts.
