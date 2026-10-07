# SFTP for app files: design

Status: design, 2026-10-08 (fork SFTP-D). No code yet. Jelle approved SFTP on 2026-10-08: Hohenheim runs its OWN
SFTP server on its own port, never the host sshd or host Linux accounts, gated by the browser file manager's
capabilities and logged in the same activity log.

Board: `App-Files-SFTP` in `docs/hohenheim-ux-mockups-2026-09-30/boards.py` (workspace root, git-ignored).

## 0. Why the 2026-08 rejection no longer holds

`docs/pterodactyl-use-inventory.md:876-892` rejected SFTP as a SECOND authorization system: its own credential
store, its own capability mapping, its own path confinement, invisible to the activity log. This design removes
each objection instead of arguing past it:

| Objection (inventory :883-889) | Answer here |
| --- | --- |
| own credential store | none: zenit-auth API keys and account SSH keys (section 3) |
| own capability mapping | none: every SFTP verb calls `InstanceFiles`, which asks `files.read` / `files.write` itself |
| own path confinement | none: the same `InstanceFilePath` + per-component lstat walk (`InstanceFiles.java:41-64`) |
| invisible to activity | the same `HohenheimActivityAction.FILES_*` verbs, one display group per session (section 2.4) |

## 1. Home

zemble `home` ("an SFTP/SSH file-transfer server over a gated virtual file tree, account auth by password and SSH
public key", 2026-10-08): verdict NEW_MECHANISM, home `apps` (score 0.88), because every SSH-shaped hit lives in
Hohenheim (`SshAuthWatcher`, `HostKeys`, `SshAuthLine`). That score measures where SSH code is today, not where a
server belongs. The capability-map rule decides: "A capability several consumers could want is core or its own
`zenit-*` module, never private to `zenit-cms` or an app." zenit-auth already anticipates hosts that verify their
own credential for a file client: `HostCredentialPrincipals` is documented as "an app password for a media, WebDAV
or CalDAV client" (`zenit-auth/.../HostCredentialPrincipals.java:13-14`) and has NO production caller yet
(graph callers: tests only). zenit-media (`ContentStore`) and zenit-folio (`FileSets`) are plausible second mounts.

Decision, split by mechanics:

| Part | Home | Why |
| --- | --- | --- |
| Account SSH public keys (a credential kind) | zenit-auth (existing repo) | zenit-auth owns every account credential: passwords, TOTP, API keys, host credential principals |
| Transport-neutral failure budget (no `Conduit`) | zenit core server (existing repo) | `FailureBudget.begin` takes only a `Conduit` (`zenit/.../FailureBudget.java:42,58`); a raw TCP login has none |
| SSH/SFTP server: lifecycle, host key store, auth bridge, gated tree SPI, per-operation revalidation, session registry | NEW module `zenit-sftp` | generic, needs a heavy dependency (MINA SSHD) that zenit core must not carry for one consumer |
| Instance file trees, settings, ban ports, UI | Hohenheim | product wiring |

A new module means a new GitHub repo: that is Jelle's call (open question Q1). The design stops the module at its
interface (section 2.2). If Jelle declines a repo, the fallback is the same interface under
`be.elevenways.hohenheim.server.sftp` with an `AIDEV-NOTE` naming the extraction, and the host key table then comes
from editing M011 (section 4.3).

## 2. The mechanism

### 2.1 Today's file manager (what SFTP reuses)

- ONE service, `InstanceFiles` (`server/files/InstanceFiles.java:80`): list, read, write, mkdir, rename, delete
  inside an instance's declared volumes only. Authorization is ON THE SERVICE:
  `HohenheimAccess.requireOperationCapability(instanceId, READ|WRITE)` (`:330-333`, `:205`), so every lane that
  calls it is gated identically. Capabilities: `files.read` (`HohenheimAccess.java:159`, ordinary tenant
  capability) and `files.write` (`:166`, elevated, not implied by read).
- Containment: lexical (`InstanceFilePath`) plus a per-ancestor LSTAT walk (`InstanceFiles.java:351-361`); symlink
  leaves are refused for read and write (`:185-189`); declared config files are read-only (`:403-415`).
- Driver seam `InstanceFileSupport` (`server/runtime/InstanceFileSupport.java:25-94`). ONLY the Docker driver
  implements it (`DockerInstanceRuntime.java:56`); Incus (LXC and VM) refuses with `files_unsupported`
  (`InstanceFiles.java:115-124`).
- Docker transport: the Engine API over the local unix socket, or a remote daemon over SSH with the pinned host key
  (`docker/DockerClient.java:31-33`). So remote ssh-enrolled Docker hosts already work through the same lane; SFTP
  adds no host access of its own.
- read/write/stat use the archive API and work on a STOPPED container; list/mkdir/rename/delete run constant shell
  scripts via `docker exec` and need a RUNNING workload with a POSIX shell
  (`DockerInstanceRuntime.java:524-549`, refusal `files_workload_not_browsable`, `InstanceFiles.java:470-476`).
- Size: the seam moves whole `byte[]` (`InstanceFileSupport.java:66,73`), capped by `hohenheim.files.max_file_kb`
  (default 8192 KiB, `HohenheimSettings.java:1334-1341`). The Docker client can already stream: `getArchiveFileTo`
  goes socket-to-disk (`DockerClient.java:1216-1240`) and `putArchiveFiles` tars from a host directory
  (`:1065-1076`). So a large upload CAN avoid the heap; it needs a disk-spool variant of the seam (W3).
- Activity: recorded by the two transports, not the service: `ActivityLog.record(InstanceModel, id, verb, path)`
  (`InstanceFileEndpoints.java:95,164,183`) with `FILES_SAVE/UPLOAD/MKDIR/RENAME/DELETE/WRITE`
  (`HohenheimActivityAction.java:61-66`). Reads are not recorded. The verb dispatch `perform` (`:197-225`) is
  private to the endpoints.

### 2.2 Module interface (`zenit-sftp`, stopped at the boundary)

```java
SftpServer.on(SftpConfig config)          // port, bind address, limits, idle timeout
    .hostKeys(SftpHostKeyStore store)     // load-or-generate; the module's own encrypted table
    .realm(SftpRealm realm)               // the consumer: what a username's mount name means
    .start();                             // a boot stage; stop() on shutdown

interface SftpRealm {
    /** @return null for absent, trashed AND not permitted alike: one uniform refusal */
    @Nullable SftpMount mount(@NonNull Principal principal, @NonNull String mountName);
    /** Scopes an SSH-key login is narrowed to (the realm's file capabilities only). */
    @NonNull List<String> keyScopes();
}

interface SftpMount {                     // every call may throw Violations; the module maps them to status codes
    List<SftpEntry> list(String path);  SftpEntry stat(String path);
    long readTo(String path, Path spool, long maxBytes);
    void writeFrom(String path, Path spool, boolean replace);
    void makeDirectory(String path);  void rename(String from, String to);  void delete(String path);
    void setMode(String path, String octal);  void setModified(String path, long epochSeconds);
}
```

The module, not the consumer, guarantees per operation:
1. `PrincipalRevalidation.holds(principal)` (`zenit/.../PrincipalRevalidation.java:24`) before every request; a
   false ends the session (revoked key, disabled account, expired key: `ScopedPrincipalRevalidationTest:60-93`).
2. The request runs under `ExecutionContext` with the principal's `ExecutionIdentity` and an `Accountability` whose
   origin is `sftp`, ip the peer address, user agent the client's SSH version string. Hohenheim's gates fail closed
   on bare work (`apps/hohenheim/CLAUDE.md`, Tasks section), so this is load-bearing, not cosmetic.
3. A `Violations` refusal becomes `SSH_FX_PERMISSION_DENIED` / `NO_SUCH_FILE` / `FAILURE` with the refusal's text
   resolved in the ACCOUNT's locale. Filenames are user data, never translated.
4. A session registry (who, from where, since, counts) for the "Open sessions" card and an `end(sessionId)` verb.

### 2.3 Hohenheim mount

`InstanceSftpRealm.mount(principal, "42")` resolves instance 42 under `InstanceModel.liveAuthored()` and requires
`files.read`, exactly as the API lane's uniform 404 (`InstanceFileEndpoints.java:299-317`). The mount delegates
every verb to `InstanceFiles` and resolves the instance FRESH on every call (never caches the handle), so a moved
instance is reached on its new host at the next operation.

The verb dispatch moves out of `InstanceFileEndpoints.perform` into one shared home beside `InstanceFiles` (a
`FileVerbs` that performs AND records), so the HTML lane, the API lane and SFTP cannot drift on what an action
means or which verb it records. That is the duplicate-gate answer, decided now rather than found at push.

Streaming: `InstanceFileSupport` gains `readFileTo(handle, path, Path out, long max)` and
`writeFileFrom(handle, path, Path source, mode, ownerLabels)`; Docker implements them on `getArchiveFileTo` and
`putArchiveFiles`. The `byte[]` methods stay for the editor. Cap: `hohenheim.sftp.max_file_mb` (default 4096),
separate from the browser's 8 MiB because the browser lane buffers in memory and SFTP spools to disk.

SFTP write semantics: an OPEN for write spools to `<data>/sftp/spool/<session>/<n>` (append or resume first pulls
the current file into the spool), WRITE at offsets goes to the spool, CLOSE commits through `writeFrom` and records
one activity row. A failed commit answers the CLOSE with an error and lands nothing (the archive put is
all-or-nothing). Reads pull the whole file into the spool at OPEN and serve ranges from it. Spool files die with
the handle; a boot sweep deletes orphans.

### 2.4 Activity

Same verbs as the browser (`FILES_UPLOAD` for a committed file, `FILES_MKDIR`, `FILES_RENAME`, `FILES_DELETE`),
one row per mutation so the audit is complete. To keep a 5000-file sync from flooding the list, all rows of one
SFTP session share a display group: `ActivityModel.SESSION_ID` is already "an opaque display group"
(`zenit/.../ActivityModel.java:57-58`), today written only by editing sessions (`ActivityLog.java:735-737`). W1
verifies the activity list folds rows by it (if it only folds by `COMMAND_ID`, that is a zenit-cms change in W1).
Reads are not recorded, same as the browser. The sentence reads "Jelle uploaded 312 files to Survival over SFTP".

## 3. Authentication

- A zenit-auth account holds NO SSH public keys today: no model, migration or service mentions one (zenit-auth
  credential classes: `PasswordService`, `Totp`, `TwoStepSignIn`, `ApiKeyService`, `HostCredentialPrincipals`).
- Username: `<account email>.<instance id>`, split at the LAST dot (an instance id has none). Instance names are
  user words, not unique (`InstanceModel.java:137-140`), and there is no slug column; the id needs no schema change.
  An unknown or not-permitted id gets the same refusal as a wrong credential, so probing ids reveals nothing.
- Never the login password. It would bypass two-step sign-in and the login lockout, and OIDC/Proteus accounts have
  none. Two ways in, both minted from a signed-in (two-step-passed) browser session:
  1. **SFTP password** = a zenit-auth API key presented as the password (`ApiKeyService.authenticate`,
     `:187`), minted by "Create an SFTP password" with scopes `cap:hohenheim:instance#files.read` and
     `#files.write` (grammar: `docs/paas-api.md:23-28`), labelled "SFTP", revocable and optionally expiring. It is
     an app password without a second store.
  2. **SSH public key** registered on the account (new zenit-auth credential, W1). A key login yields
     `HostCredentialPrincipals.forAccount(userId, "SFTP key <label>", realm.keyScopes())`
     (`HostCredentialPrincipals.java:34`), so a key can do files and nothing else.
- Tenants: no special case. A tenant's grant on an instance carries `files.read` / `files.write`
  (Starfleet's tenant holds both on instance 3); `InstanceFiles` asks them live on every call, so revoking the grant
  ends access at the next operation, and `PrincipalRevalidation` ends a session whose key or account goes away.

## 4. Operations

### 4.1 Settings (`hohenheim.sftp`, all host-only per the 2026-10-07 setting-authority change)

`enabled` (false: the operator opts in), `port` (2022, Pterodactyl's), `bind_address` (blank = all),
`public_host` (the address the UI shows; blank = the admin host), `max_file_mb` (4096), `max_sessions` (32),
`max_sessions_per_account` (4), `idle_timeout_minutes` (15), `spool_budget_mb` (refuse new uploads when the spool
holds this much). Starts in a boot stage on the node that serves the admin panel; which node that is in a multi-node
install is checked in W3 (`HohenheimRoles.Role` has no panel role, `HohenheimRoles.java:30-36`).

### 4.2 Firewall and bans

- Hohenheim opens no accept rules (nftables carries only ban sets), so `tools/install-host.sh` needs no rule; the
  install doc states the port must be reachable.
- Failed logins feed the SAME `ThreatScorer` the sshd watcher feeds (`SshAuthWatcher.java:26-31,100`,
  `HohenheimSecurity.java:28,39`) with the existing `SecurityEventTypes.SSH_INVALID_USER / SSH_PASSWORD_FAILED /
  SSH_PUBLICKEY_FAILED` (`zenit/.../SecurityEventTypes.java:58-63`): no second vocabulary.
- The SSH ban set's ports (`security.nftables_ssh_ports`, `HohenheimSettings.java:738-746`, parsed at
  `NftService.java:208-211`) gain the SFTP port DERIVED from `hohenheim.sftp.port` while SFTP is enabled, never a
  second hand-kept list.
- Per-IP lockout through the transport-neutral `FailureBudget` overload (W1), checked BEFORE verifying anything,
  as the HTTP lane does (`FailureBudget.java:15-28`).

### 4.3 Host key

Ed25519, generated by MINA at first start, stored ENCRYPTED in the controller database so the control-plane backup
(database plus field-encryption keyring, `task/BackupControlPlane.java:18-21`) restores the same fingerprint. With
the module, its own migration stream owns the one table; without it, M011 gains it (the newest unpinned migration).
The UI shows the fingerprint next to the connection details.

### 4.4 Limits and lifecycle

- Concurrency: global and per-account session caps (refused at auth with a worded reason), MINA's per-session
  channel limit at 1 SFTP subsystem, idle timeout.
- Stopped instance: read/write work (archive API), list/mkdir/rename/delete refuse with the worded
  `files_workload_not_browsable`. Clients need listings, so in practice a stopped app is read-by-path only; the
  board's text says to start the app. Known limitation, stated, not hidden.
- Moving host: resolved fresh per operation (section 2.3). Whether an operation during a move refuses cleanly is
  checked in W3 against the migrate lease.
- Incus (LXC, VM): no file lane yet, so SFTP refuses exactly as the Files tab does. W5 adds an Incus
  `InstanceFileSupport` (the Incus files API; a VM needs its guest agent), which serves the Files tab and SFTP at once.

## 5. Library

Apache MINA SSHD is NOT in the catalog (`protoblast/zenit-versions/libs.versions.toml`: no `sshd`; BouncyCastle
1.81 at `:70`). Pick `org.apache.sshd:sshd-core` + `sshd-sftp` **2.19.0** (Maven Central, published 2026-06-30; the
newest stable; 3.0.0 is still at milestone M6 of 2026-09-26). It compiles for Java 8 (`java.sdk.version` 8) and runs
on JDK 25. Use its default NIO2 transport (no MINA core dependency). Ed25519 needs an EdDSA provider: use the
catalog's BouncyCastle, never `net.i2p.crypto:eddsa`. W2 proves an ed25519 user key and host key in the journey,
and records any JDK 25 warning it prints.

## 6. UI

- The Files tab of the app (`cms/InstanceFilesPage.java`) gets a "Connect with SFTP" card: address, port,
  username (copy), what you can sign in with, "Create an SFTP password" (shown once), "Add an SSH key" (the account
  page), the host key fingerprint, and "Open sessions" with End. Write affordances follow `files.write` as the
  page already does (`InstanceFilesPage.java:41-42,95`).
- `/manage`: the same tab for tenants, same card. Board: `App-Files-SFTP`.
- When `hohenheim.sftp.enabled` is off, the card says SFTP is off and, for an operator, links to the setting.

## 7. Tests the implementation must have

`SftpJourneyTest` (Hohenheim, MINA's own client against a local Docker instance with a volume, `@Tag("slow")`
through `LiveLane` per `apps/hohenheim/CLAUDE.md`):
1. A login without `files.read` on the instance is refused, with the same answer as an unknown instance id.
2. With `files.read`: list and read work; a write is refused (`PERMISSION_DENIED`) and lands nothing.
3. With `files.write`: upload (larger than `max_file_kb`, to prove the spool lane), mkdir, rename, delete work;
   a declared config file stays read-only.
4. Each mutation wrote its `FILES_*` activity row with origin `sftp`, the actor, and the session's display group.
5. The grant is revoked mid-session: the NEXT operation is refused. The SFTP password (API key) is revoked
   mid-session: the session ends.
6. An ed25519 SSH key login works and its principal cannot do anything but files.

Module (`zenit-sftp`) journey against a fake in-memory `SftpMount`: lifecycle, host key persistence across restart
(same fingerprint), failure-budget lockout and ThreatScorer feed, session caps, revalidation. zenit-auth: SSH key
add/list/remove plus revalidation. zenit: transport-neutral `FailureBudget`.

## 8. Waves (2-4 items each)

- **SFTP-W1, framework prerequisites** (zenit-auth, zenit, zenit-cms if needed): account SSH public keys
  (model, account page section, lookup, revalidation registration); `FailureBudget` overload keyed by an explicit
  client address; activity list folds a `SESSION_ID` display group. Each with its journey and changelog entry.
- **SFTP-W2, the module** (`zenit-sftp`, after Q1): MINA server lifecycle and host key store; auth bridge (API key
  password, SSH key, username split, uniform refusal); `SftpRealm`/`SftpMount` with per-operation revalidation,
  execution identity and status mapping; session registry; the module journey.
- **SFTP-W3, Hohenheim wiring**: streaming seam (`readFileTo`/`writeFileFrom`, plus `setMode`/`setModified`) on
  the Docker driver; `FileVerbs` shared by HTML, API and SFTP; `InstanceSftpRealm`, settings group, ban-port
  derivation, ThreatScorer feed; `SftpJourneyTest`.
- **SFTP-W4, UI**: the Files tab card and `/manage` twin, "Create an SFTP password", open sessions with End,
  `docs/deploy-native.md` port note; screenshots against `App-Files-SFTP`.
- **SFTP-W5, Incus file lane**: `InstanceFileSupport` for LXC and VM over the Incus files API, which lights up the
  Files tab and SFTP for them together.

## 9. Open questions for Jelle

- **Q1. A new repo `11ways/zenit-sftp`?** Recommended: yes (section 1). Alternative: keep the server inside
  Hohenheim behind the same interface and extract it when a second consumer appears.
