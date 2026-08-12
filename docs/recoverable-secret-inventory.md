# Recoverable-secret inventory (0.6c)

Checked in 2026-08-02, the artifact the Phase 2 parallel gate demands. Every
field below was read at its declaration AND at its use site; the posture column
is what the code does today, not what a docblock claims.

## Correction 2026-08-11 -- TWO SECURITY CLAIMS BELOW WERE INVERTED

Read this before anything else. This document was last touched 2026-08-07; the
backup redesign landed 2026-08-08 (`7201269a`) and the rekey lane landed with it.
Two statements here asserted the OPPOSITE of the code, which is worse than
staleness because both were being quoted as known limits:

1. **"Rotation prepends a key with no re-encryption and no key retirement"**
   (2026-08-07 block, and the sequencing section). FALSE.
   `EncryptionRekey` (zenit, `server/orm/crypto/EncryptionRekey.java:38-42`)
   does exactly the two missing halves: `reencrypt` rewrites every stored
   envelope under the ACTIVE key (resumable from a durable per-(key, model)
   cursor, envelope shape preserved), and `retire` refuses to drop a key until a
   SURVEY -- a real walk that decrypts every stored value and counts the key ids
   it read -- proves nothing is left under it. CLI-wired in
   `ServerMain.java:378` (`--reencrypt-secrets`), `:384`
   (`--encryption-key-survey`) and `:392` (`--retire-encryption-key <id>`), with
   `--rotate-encryption-key` deliberately NOT bundled with them
   (`ServerMain.java:348`: one flag that hides the only destructive step behind
   the two safe ones would be the trap). `EncryptionRekeyJourneyTest` pins the
   journey. Rotation is now a whole story; stop offering it as half a
   mitigation. The sentences making the old claim are struck in place below.
2. **"The default backup destination is a LOCAL directory ... off-host transfer
   remains a deployment concern"** (sequencing item 4). FALSE, and inverted:
   the code REFUSES to run without a remote target. `ControlPlaneBackups
   .destinationName():194-207` throws "There is deliberately no local default",
   and `HohenheimSettings.java:487-503` declares
   `database.control_plane_backup_target` with NO default at all. The local
   `.zrec` is staging only (`ControlPlaneBackups.java:64`, `:217-221`), deleted
   in a `finally` (`:122`) that fires on upload failure too; after upload the
   archive is re-hashed ON THE TARGET and deleted there on mismatch
   (`:125-132`); retention prunes on the REMOTE (`:133`, `:149-161`). What DOES
   survive from the old text: the retention VALUE is still
   `database.backup_retention` (`:84`), and the archive still carries the master
   keys in the clear -- so the destination is now a security boundary of its own,
   which is why `BackupTargetModel` joins the inventory below.

Also corrected in this pass: the posture legend (ENCRYPTED did not distinguish
`.secret().encrypted()` from bare `.encrypted()`), the `DnsRecordModel
.DYNDNS_TOKEN` row (that field no longer exists), two of the three "named
unknowns" (both answerable by reading code), and five secret-bearing or
secret-adjacent records that were missing from the inventory entirely.

## Re-verification 2026-08-07 -- READ THIS BEFORE THE 2026-08-02 TEXT

**This document was stale in BOTH directions.** It listed as OPEN five keyring
defects that are FIXED, and its field sweep undercounted the encrypted surface
by a factor of six -- and a doc that is wrong about what is CLOSED is how a real
gap gets ignored. The 2026-08-02 sections below are preserved as history; where
they disagree with this block, this block wins.

Everything in this block was re-verified by reading the code on 2026-08-07, not
carried over from a "LANDED" note.

### The five keyring defects listed below as OPEN are all CLOSED

Verified in `zenit/src/server/java/.../orm/crypto/EncryptionKeyring.java`:

| Old defect | State on 2026-08-07 |
| --- | --- |
| 1. concurrent creation may clobber the winner | FIXED. Creation now claims the path INSIDE `underFileLock` -- the same cross-process lock rotation uses -- and re-checks `Files.exists` under it (`loadOrCreate:88-114`). The non-replacing `ATOMIC_MOVE` is gone; the AIDEV-NOTE records the empirical confirmation on OpenJDK 25 that the old exception never fired |
| 2. a missing keyring MINTS A NEW ONE and boots green | FIXED. `KeyringGuard` plus a marker table record the active key id and REFUSE boot when the loaded keyring cannot satisfy it; wired at `ServerStages.java:46` (`KeyringGuard.runPerRegisteredModels()`), so it is a boot-stage check and not an opt-in |
| 4. permissive existing file modes never inspected | FIXED. `verifyPermissions:162-193` inspects, REPAIRS by removing every non-owner bit, RE-READS to confirm the repair stuck, and throws when it did not. It also logs `orm.encryption.keyring_permissions_repaired` telling the operator to assume a leak and rotate |
| 5. no fsync | FIXED. `writeFile` forces the temp file's channel before the rename (`:519-524`) and `syncDirectory:557-568` forces the parent directory afterwards |
| 6. `.gitignore` written only when absent | FIXED. `guardAgainstCommit:354-393` reads the existing file, checks the key AND the `.lock` name separately, and APPENDS the missing entries to the operator's own file; a failure throws rather than silently leaving the key committable |

Defect 3 (AAD) was already recorded as LANDED and still is.

~~What is still HALF a story, unchanged: rotation prepends a key and there is no
re-encryption and no key retirement, so nothing already written becomes less
exposed by rotating. Say so whenever rotation is offered as mitigation.~~
**STRUCK 2026-08-11: false as of the rekey lane.** `EncryptionRekey.reencrypt`
rewrites every stored envelope under the active key and `EncryptionRekey.retire`
drops an old key only after a survey proves nothing still reads under it
(`EncryptionRekey.java:38-42`, `ServerMain.java:378,384,392`). Rotating and then
running those two DOES reduce the exposure of what was already written.

### The field sweep was wrong by a factor of six

The 2026-08-02 text says a workspace-wide sweep found `.encrypted()` in
"exactly two places" (the zenit mechanism, hohenheim's three Stack fields), and
builds a "near-zero blast radius" sequencing argument on it. **That argument is
void.** Counted on 2026-08-07 by grepping declaration sites (comments and the
M047 migration excluded):

- **hohenheim: 19 field declarations** carrying `.encrypted()`.
- zenit 16 files, zenit-auth 2 files, zenit-cms 1 file also reference it.

Of hohenheim's 19, twelve are the ones this document already covers (rows 1-9
plus the three Stack fields). **Seven landed AFTER this document was written and
appear nowhere in it:**

| Field | Declared | What it holds |
| --- | --- | --- |
| `GitProviderModel.ACCESS_TOKEN` | `GitProviderModel.java:68` | git-forge API token |
| `GitProviderModel.APP_PRIVATE_KEY_PEM` | `GitProviderModel.java:87` | git-forge app private key |
| `ServerModel.IDENTITY_PRIVATE_KEY` | `ServerModel.java:250` | host SSH identity private key |
| `ServerModel.INCUS_CLIENT_KEY` | `ServerModel.java:285` | Incus client TLS private key |
| `InstanceFileModel.CONTENT` | `InstanceFileModel.java:38` | rendered instance file bodies (configs carry secrets) |
| `InstanceTemplateFileModel.CONTENT` | `InstanceTemplateFileModel.java:37` | the template side of the same |
| `InstanceVariableModel.SECRET_VALUE` | `InstanceVariableModel.java:67` | per-instance secret env values |

Consequence for anyone reading the sequencing section: introducing a new
envelope version is no longer near-zero blast radius in hohenheim, and any
envelope change must be judged against 19 live columns, not three.

### One field is missing from the "cannot be encrypted, JSON sub-fields" list

`IncusVmKind.CLOUD_INIT` (`server/instance/IncusVmKind.java:81`) is a
`TextField` on a JSON `SETTINGS_SCHEMA`, so `Schema.refuseEncryptedJsonSubFields`
forbids `.encrypted()` there. It gained `.secret()` in `a2122de` -- verified at
the declaration -- which is the only lever that tier has: redaction on derived
surfaces plus the FormSecrets mask/keep-on-blank/`__clear` lane. Cloud-init
user-data routinely carries injected credentials, so it belongs on that list
beside `GitSourceSchema.WEBHOOK_SECRET` and the Proteus `access_key`.

### What field encryption does and does not buy, given where the key lives

VERIFIED ON DISK 2026-08-07 in this worktree:

```
settings/default.dry              (tracked)
settings/hohenheim.dry            (gitignored since 2026-08-07)
settings/local.dry                (gitignored, 0600)
settings/field-encryption.keys    (0600, gitignored)
```

The keyring's default path is `settings/field-encryption.keys`
(`zenit .../ServerSettings.java:502`) -- **the same directory as the `.dry`
settings files**. `settings/hohenheim.dry.example:12` shows `trusted_proxy_keys`
being configured there, and `HohenheimSettings` declares the database
`PASSWORD`, the Proteus access key and the comms DSNs as settings whose home is
that same directory. So:

- Encryption BUYS protection against a read-only exposure of the DATABASE alone:
  a stolen `.db` file, a read replica, a SELECT-only injection. That is a real
  and common attacker class and it justifies the 19 flips on its own.
- Encryption does NOT buy protection against anything that can read the
  application's settings directory. **One directory read yields the ciphertext's
  key and the plaintext settings secrets together.** Any claim of "platform-wide
  encryption at rest" that does not say this out loud is theater.
- The recovery archive deliberately packs the database AND the keyring into one
  zip, in the clear, so the same statement applies to every backup destination.

An honest next step is separating the keyring's directory from the settings
directory (the setting already exists; only the DEFAULT co-locates them) -- but
that is a deployment decision, not a code change, and it is not made here.

### Flagged 2026-08-02, RESOLVED 2026-08-07

`settings/hohenheim.dry` was TRACKED IN GIT while
`settings/hohenheim.dry.example` documents `trusted_proxy_keys` as belonging in
it. It is now gitignored, matching `local.dry`: `git rm --cached` kept every
existing working copy in place, and the `.example` stays the template.

Nothing moved to `settings/default.dry`, because nothing in the tracked file was
a default: the tracked content was `proxy.http_port: 8080` and
`ssl.letsencrypt_enabled: false`, while `HohenheimSettings` already declares 80,
443 and Let's-Encrypt-on in code. The tracked file held only a developer's
overrides of the production defaults, and `default.dry` is the ZENIT settings
file anyway -- `HohenheimSettingsFiles.load()` reads only `hohenheim.dry` plus
`HOHENHEIM__*` env, so a `proxy.*` key placed in `default.dry` would be ignored.

A missing file is not a boot failure: `DryFileSource.snapshot()` treats absence
as the normal case (empty map plus a `settings.source_missing` slog event), and
every declared setting falls back to its code default.

## Status 2026-08-02: the encryption wave LANDED

Fields 1-9 below are now `.secret().encrypted()` (field 9 gained BOTH flags).
`M047_EncryptRecoverableSecrets` ships the idempotent heal in the same release:
a `builder.data(...)` step that reads the columns RAW (the typed accessors
throw on plaintext once `.encrypted()` is declared) and folds every
non-`zenc$` value into an envelope. Raw reads abandon `data()`'s
eight-backend portability -- acceptable only because hohenheim is SQLite-only,
stated in an AIDEV-NOTE at the call site. M047's version is `2026_08_02_100047`
so it sorts AFTER zenit's `2026_08_02_100000`, keeping the out-of-order
integrity finding quiet on an install that applied zenit's migration first.
`EncryptedSecretsAtRestTest` pins ciphertext at rest (raw-column asserts per
field), the model round-trip, the heal (plaintext planted pre-M047, healed by
the full migrate, re-run leaves envelopes byte-identical), and that zenit's
keyring marker table is created by a hohenheim migrate with the KeyringGuard
check ENGAGING (empty unchecked list, marker row recorded).

Also landed: the `SiteDispatcher` plaintext-compare fallback is DELETED (a
non-argon2 stored `basic_auth_pass` is refused loudly, pinned by
`BasicAuthPasswordVerificationTest`), and `GitSourceSchema.REPOSITORY_URL` is
now `.secret()`.

`TotpModel.SECRET` is now ROW-BOUND encrypted (2026-08-02): the zenit envelope
grew a per-field opt-in, `Field.Builder.encryptedBoundTo(rowKeyField)`, that
authenticates the owning row's identity (the app-assigned primary key) into the
GCM additional data on top of table|column. `TotpModel.SECRET.encryptedBoundTo(
USER_ID)` uses it; `auth_totp.secret` widened STRING(64)->TEXT (M004 edited in
place, nothing deployed). What each layer buys, stated honestly:
encryption fixes READ-ONLY exposure (a backup leak, a read replica, a
SELECT-only injection -- a strictly larger and more common attacker class, and
that alone justifies encrypting the seed); ROW-BINDING converts a silent
write-tampering seed graft (pasting user A's ciphertext onto user B's row -- an
authentication BYPASS, because B could then log in with A's authenticator) into
a loud, fail-closed refusal on read; NEITHER prevents a write-capable attacker
who ALSO holds the keyring from re-encrypting a seed under the correct AAD, and
no design can (they own both halves). The refusal is enforced by DECLARATION,
not by the stored bytes: a row-bound field refuses the AAD-less v1 envelope and
a column-only v2 envelope alike, so the binding cannot be downgraded away.
`confirmed_at` is deliberately OUT OF SCOPE: a write attacker who nulls it only
downgrades the account to password-only (the login skips the second factor), and
that same attacker achieves the identical effect more cheaply with `DELETE FROM
auth_totp`. It is not an impersonation bypass -- the password is still required
-- so it does not warrant its own integrity field. The end-to-end refusal, the
victim's-own-row still decrypting, the downgrade/column-only refusals and the
admin recovery path are pinned by `EncryptedFieldTest` (zenit, 8 backends) and
`AuthFlowIntegrationTest.totpSeedGraftAcrossUsersIsRefusedAtLoginAndAdminReset...`
(zenit-auth).

The organising question is NOT "is this sensitive" but **"must this value be
RECOVERABLE"**. A credential the code only ever COMPARES belongs hashed;
encrypting it is the weaker design because it keeps a reversible copy for no
reason. A credential the code must PRESENT to a third party (a TLS stack, a peer
nameserver, a child process) cannot be hashed, and encryption is the only lever.

Posture legend (CORRECTED 2026-08-11 -- it used to equate ENCRYPTED with
`.secret().encrypted()`, which hid the difference below):

- **HASHED** -- one-way, compared only. Correct for anything never presented.
- **ENCRYPTED+SECRET** -- `.secret().encrypted()`. Ciphertext at rest AND
  redacted on every derived surface (forms, exports, activity diffs).
- **ENCRYPTED ONLY** -- bare `.encrypted()`, no `.secret()`. Ciphertext at rest,
  NOT redacted on derived surfaces. Exactly four fields, all of them
  operator-authored BODIES the admin must be able to read back:
  `StackDeploymentModel.SPEC` (`StackDeploymentModel.java:39-41`),
  `StackFileModel.CONTENT` (`StackFileModel.java:34-38`),
  `InstanceFileModel.CONTENT` (`InstanceFileModel.java:38-42`) and
  `InstanceTemplateFileModel.CONTENT` (`InstanceTemplateFileModel.java:37-41`).
  A config file body and a deploy snapshot routinely CARRY credentials, so treat
  them as secret-bearing even though the declaration does not say so.
  (`InstanceVariableModel.SECRET_VALUE` is NOT in this class -- it is
  `.secret().encrypted()` at `InstanceVariableModel.java:67-71`.)
- **SECRET** -- redacted in derived surfaces, PLAINTEXT in the column.
- **PLAIN** | **EXTERNAL**.

## Must be encrypted -- recoverable, main-table, no query breakage

Each was traced for queryability: every use is `row.get(...)` on an already
loaded row (the two writes via `assignIfNull(...).updateAll()` and the one
`isNotNull()` criterion stay legal under encryption). None appears in a value
criterion, ordering, grouping or aggregate, so `.encrypted()` breaks nothing.
Rows 1-9 are ENCRYPTED as of 2026-08-02; the table below keeps the original
pre-wave posture notes for the record.

| # | Field | Declared | Protects |
| --- | --- | --- | --- |
| 1 | `CertificateModel.PRIVATE_KEY_PEM` | `CertificateModel.java:58` | TLS private key for every proxied domain -- the highest-value single secret in the database |
| 2 | `DnsZoneModel.DNSSEC_PRIVATE_KEY` | `DnsZoneModel.java:72` | zone signing key |
| 3 | `DnsPeerModel.TSIG_SECRET` | `DnsPeerModel.java:39` | AXFR/IXFR transfer auth to peer nameservers |
| 4 | `DnsPeerModel.API_KEY` | `DnsPeerModel.java:28` | peer nameserver control API (outbound bearer) |
| 5 | `DatabaseModel.DB_PASSWORD` | `DatabaseModel.java:62` | managed tenant database password |
| 6 | `NotificationChannelModel.URL` | `NotificationChannelModel.java:36` | Slack/Discord/webhook URL -- the URL IS the bearer token |
| 7 | `SiteModel.SECURITY_REPORT_TOKEN` | `SiteModel.java:108` | spamservice reporting credential; injected raw into the child env |
| 8 | `SpamserviceInstallationModel.CONTROLLER_KEY` | `SpamserviceInstallationModel.java:40` | local spamservice control API |
| 9 | `StackServiceModel.ENVIRONMENT` | `StackServiceModel.java:114` | **PLAIN today, not even secret.** A `StringMapField` on the MAIN TABLE, so unlike `SiteModel.environment_variables` it is not inside a JSON SchemaField and encryption IS available |
| 10 | `TotpModel.SECRET` (zenit-auth) | `TotpModel.java:36` | ROW-BOUND ENCRYPTED as of 2026-08-02 (`encryptedBoundTo(USER_ID)`). Was the worst posture found (PLAIN, not even secret); a plain `.encrypted()` would have been a half-fix (still cross-row graftable). See the intro for the full framing |

## Correct as-is -- do not "improve" these

| Field | Posture | Why it stays |
| --- | --- | --- |
| ~~`DnsRecordModel.DYNDNS_TOKEN`~~ -> `DnsDyndnsCredentialModel.TOKEN_DIGEST` | HASHED | MOVED (2026-08-11 note): the field left `DnsRecordModel` in `M091_TypedDnsRecordData` and is now its own table -- `DnsDyndnsCredentialModel.java:30-31`, `.secret()`, sha256 digest only, one row per dynamic A/AAAA record (the row's EXISTENCE is the dynamic flag, so revoking deletes it). `DynamicDnsService.java:251` still looks it up with `where(TOKEN_DIGEST.eq(digest))` against an index, so the reasoning is unchanged: encrypting it would throw at `SqlCriteriaTranslator.java:92`. M091 hashed the legacy plaintext tokens during the copy and refused to carry over inert tokens on non-dynamic rows. Still the exemplar of hashing being stronger |
| `TotpModel.BACKUP_CODES` | HASHED | one-time codes, compared only |
| `PasswordModel.HASH`, `ApiKeyModel.HASH` | HASHED | correct; `ApiKeyModel` is the model to copy |
| Site `api_keys` (`NodeSiteType.java:88`) | HASHED | 0.6b landed this; inside JSON so unencryptable anyway |
| `CertificateModel.CERTIFICATE_PEM`, `DNSSEC_PUBLIC_KEY` | PLAIN | public by definition; encrypting is theatre |
| `SiteDomainModel.LIVE_ROUTE_KEY` | PLAIN | not a credential -- a derived uniqueness key backing a UNIQUE index. Non-deterministic ciphertext would destroy it and break M045 |
| All three `StackModel`/`StackDeploymentModel`/`StackFileModel` encrypted fields | ENCRYPTED | genuinely recoverable; the reference declarations |

## Added 2026-08-11 -- records the inventory never covered

Each read at its declaration AND at its use site, like the rows above.

| Field | Declared | Posture | Verdict |
| --- | --- | --- | --- |
| `DnsDyndnsCredentialModel.TOKEN_DIGEST` | `DnsDyndnsCredentialModel.java:30-31` | HASHED + SECRET | Correct. sha256 of the plaintext the router presents, looked up by digest (`DynamicDnsService.java:251`); the plaintext is disclosed once by the mint action and never stored. The row's existence IS the dynamic flag, so revocation is a delete and a released hostname's token dies with it |
| `ControllerIdentityModel.TOKEN` | `ControllerIdentityModel.java:27-28` | PLAIN | Correct, and NOT a credential despite the name: the lowercase alphanumeric namespace token every daemon resource name carries, on a single-row table. It authenticates nothing; encrypting or redacting it would only make container names unreadable in the admin |
| `BackupTargetModel.SETTINGS` | `BackupTargetModel.java:42-46` | PLAIN (JSON `SchemaField`, `schemaFrom("kind")`) | Correct as data, load-bearing as SECURITY. It holds no credential of its own -- the ssh kind stores a `servers` record reference and a path (`SshTargetKind.java:39`, `:46`) and borrows that host's `ServerModel.IDENTITY_PRIVATE_KEY`, which IS encrypted. But this record now DECIDES WHERE the control-plane recovery archive lands, and that archive carries the field-encryption keyring in the clear. Write access to a backup-target row is therefore equivalent to exfiltration of every encrypted column, and it is not a "settings" decision guarded like one |
| `InstanceBackupModel.REMOTE_KEY` | `InstanceBackupModel.java:63-64` | PLAIN | Correct. The committed object key on the target (the `.part` staging key is deliberately never recorded); it names an artifact, it does not open one. The payload itself is encrypted WHOLE under a keyring key -- `HIB1` magic, key id, 12-byte IV, AES-256-GCM with magic and key id as additional data (`BackupArchive.java:30-37`, `:192-223`) -- and `SUMMARY` is a deliberately non-sensitive manifest excerpt, with settings and secret variables living only inside the ciphertext |
| `ExternalIdentityModel.CLAIMS` (zenit-auth) | `ExternalIdentityModel.java:29` | PLAIN | **CONDITIONALLY WRONG -- the 2026-08-02 "named unknown" is answered, and badly.** A plain `TextField`, written as `identity.claims().toString()` (`IdentityLoginService.java:84,104-109`). For an OIDC provider WITH a userinfo endpoint the map is the userinfo response and holds no tokens. For one WITHOUT, `OidcIdentityProvider.java:152-156` sets `claims = new LinkedHashMap<>(tokenResponse)` -- **the whole token response, `access_token` and any `refresh_token`/`id_token` included** -- and that is what lands in the column, in plaintext, as a Java `Map.toString()`. Proteus copies only the identity map (`ProteusIdentityProvider.java:143`) and is unaffected. This is a zenit-auth defect, not a hohenheim one: the right fix is to stop copying the token response into claims at all (a userinfo-less provider needs the id_token decoded, not the bearer stored), not to encrypt the column afterwards. **FIXED 2026-08-12 in zenit-auth `4a33483`, the day after this row was written -- do not quote the row above as a live leak.** The fix landed exactly where the row said it belonged: `OidcIdentityProvider` (now at `server/identity/oidc/`) strips `access_token`, `refresh_token` and `id_token` through `withoutBearerCredentials(claims)` before the map ever leaves the provider, and it is applied to BOTH branches so a provider echoing a token in its userinfo response cannot smuggle one past the second code path. `token_type`/`expires_in`/`scope` are kept: metadata about a credential no longer retained. The column stays a plain `TextField` deliberately -- encrypting it would have encrypted data that must never have been stored |

## Cannot be encrypted -- JSON sub-fields

`Schema.refuseEncryptedJsonSubFields` (`Schema.java:196`) forbids it. Redaction
is the only lever until each gets a real column or a table-stored sub-schema.

- `IncusVmKind.CLOUD_INIT` (`IncusVmKind.java:81`) -- SECRET as of `a2122de` (verified 2026-08-07). Cloud-init user-data routinely carries injected credentials; `.secret()` plus the FormSecrets mask/keep-on-blank/`__clear` lane is all this tier has.
- `GitSourceSchema.WEBHOOK_SECRET` (`GitSourceSchema.java:63`) -- HMAC needs the raw value, so it cannot be hashed either.
- `ProteusAuthProviderType` `access_key` (`ProteusAuthProviderType.java:42`).
- `GitSourceSchema.BUILD_ENVIRONMENT_VARIABLES`, dev `registration_token`.
- **`GitSourceSchema.REPOSITORY_URL` was PLAIN and was missed by every prior category list.** A private-repo clone URL routinely embeds `https://user:TOKEN@host/...`. The minimum fix -- `.secret()` -- LANDED 2026-08-02; the correct eventual fix remains a separate credential field so the URL itself can stay visible and editable.

## Fix by TIGHTENING hashing, not by encrypting

`AccessListModel.BASIC_AUTH_PASS` (`AccessListModel.java:35`) hashes with argon2
when the value starts with `$argon2`, and otherwise USED TO fall back to a
constant-time PLAINTEXT compare in `SiteDispatcher`. That legacy branch existed
only for pre-hash values; nothing was deployed, so it was DELETED (2026-08-02):
`SiteDispatcher.verifyBasicAuthPassword` now refuses any non-argon2 stored value
with a loud log line. NOTE the sibling `BasicAuthProviderType.verify` still
constant-time-compares plaintext -- that one is DELIBERATE (provider credentials
are operator-visible/editable by design, pinned by `SecretFieldsTest`), with an
argon2 branch for legacy hashed rows.

## Not columns at all -- state this honestly

Comms DSNs (`CommsSettings.java:30,39,48`), `trusted_proxy_keys`
(`HohenheimSettings.java:157`), the database password (`:470`) and the Proteus
access key (`:707`) are SETTINGS, stored in `.dry` files. Field encryption cannot
reach them and should not claim to.

**And the keyring default path is `settings/field-encryption.keys` -- the SAME
directory as those plaintext settings files.** Encrypting a database column while
the master key sits beside the plaintext secrets it is meant to protect is a
narrow win. Any platform-wide encryption claim must say so.

## Keyring defects found (zenit, `EncryptionKeyring.java` / `FieldEncryption.java`)

**SUPERSEDED 2026-08-07: every defect below is FIXED. See the re-verification
block at the top of this document; what follows is the HISTORY it supersedes and
must not be quoted as an open finding.**

These are prerequisites, not follow-ups. Four of the five are the project's
dominant defect shape.

1. **Concurrent creation may silently clobber the winner.** `loadOrCreate:74-90`
   claims the file with `Files.move(..., ATOMIC_MOVE)` WITHOUT `REPLACE_EXISTING`
   and catches `FileAlreadyExistsException` to detect a lost race. On Linux
   `ATOMIC_MOVE` is `rename(2)`, which silently replaces the destination, and the
   JDK's target-exists check lives only in the NON-atomic branch -- so that
   exception is likely never thrown and the loser overwrites the winner. The
   docblock at `:66-72` promises the opposite. UNCONFIRMED (needs a two-process
   test); the fix regardless is to claim with `CREATE_NEW` on the real path.
   `EncryptionKeyringTest:133-145` covers the ROTATION race, never creation.
2. **A missing keyring MINTS A NEW ONE and boots green.** `requireKeyring:154-168`
   calls `loadOrCreate`, so a database restored without its keyring starts
   cleanly, writes new rows under a new key, and only fails later per-row on the
   first read of an old envelope. New and unrecoverable rows interleave. There is
   no boot-time check anywhere.
3. **AAD.** LANDED. The v2 envelope binds table|column, refusing cross-COLUMN
   grafts (site A's TLS key onto site B's certificate row, one zone's DNSSEC key
   onto another). Cross-ROW grafts within one column stay possible for a plain
   `.encrypted()` field BY DESIGN (the auto-increment pk is not in hand at encrypt
   time), which is why the TOTP seed -- where the cross-row swap IS the attack --
   uses the per-field `encryptedBoundTo(pkField)` row binding (LANDED 2026-08-02,
   see intro). None of hohenheim's ten fields can adopt row binding: every one
   sits on an auto-increment `id` pk, which is DB-assigned and therefore refused
   as a binding key. That is correct -- their attack model is the read-only leak,
   not a targeted intra-column swap.
4. **Permissive existing file modes are never inspected.** Creation restricts to
   0600, but `loadOrCreate` reads a pre-existing file with no mode check.
5. **No fsync**, neither the file nor the parent directory. For a file whose loss
   is permanent and which has no second copy, that is under-engineered.
6. `guardAgainstCommit:193-209` writes a `.gitignore` only when NONE exists, so
   in any real repo the guard silently does nothing.

Rotation itself is the best-built part (JVM mutex + cross-process `FileLock`,
re-reads under the lock, regenerates on id collision) -- ~~but there is **no
re-encryption and no key retirement**, so rotation adds a key and never reduces
exposure of anything already written. It is half a rotation story; say so.~~
**STRUCK 2026-08-11**, see the correction block at the top: `EncryptionRekey`
supplies both missing halves, and `--rotate-encryption-key` deliberately does
NOT bundle them (`ServerMain.java:348`) so the destructive step stays a separate,
explicit decision.

## Sequencing, and why AAD goes FIRST

**SUPERSEDED 2026-08-07: the sweep this section's premise rests on was wrong --
hohenheim alone carries 19 `.encrypted()` declarations, not three. Steps 1, 2 and
4 have all LANDED. See the re-verification block at the top.**

A workspace-wide sweep found `.encrypted()` in exactly two places: the zenit
mechanism itself and hohenheim's three Stack fields. **No other app uses it.** So
a `zenc$2$` envelope binding `table|column|primaryKey` as AAD can be introduced
now at near-zero blast radius -- the version field is already checked
(`FieldEncryption.java:109-112`) and v1 stays readable. After the ten fields
above are encrypted, that stops being true.

1. Keyring hardening + the v2 AAD envelope (zenit).
2. The generation marker: record the active key id on first encrypted write and
   REFUSE BOOT when the loaded keyring cannot satisfy it. This converts defect 2
   from silent corruption into the loud refusal the gate asks for, and it is far
   cheaper than atomic backup.
3. The field flips above, each with a raw-column test proving ciphertext at rest
   (`StackRuntimeFlowTest:165-172` is the template).
4. Backup: LANDED 2026-08-02. `RecoveryArchive` (zenit, `server/orm/backup`) is
   the mechanism: one zip carrying a `VACUUM INTO` snapshot of the database plus
   the keyring file, with a DRY manifest recording a SHA-256 + size of each half
   and the key-id list; `create` re-reads and re-hashes the written file before
   reporting success, `verify`/`restore` refuse tampered, truncated or
   half-missing archives whole (`RecoveryArchiveException` problem tokens).
   NOTE the ordering the original sketch here had BACKWARDS: because the keyring
   is prepend-only, the DATABASE is snapshotted FIRST and the keyring file read
   SECOND -- that guarantees the archived keyring is at least as new as the
   archived DB (newer is always safe, older never is). Restore writes keyring
   first, database second, MERGES an existing target keyring (never discards
   keys), and is offline-by-design. Hohenheim wiring: `ControlPlaneBackups` +
   the role-free daily `BackupControlPlane` task (archives under
   `database.backup_path`/control-plane, `database.backup_retention` applies)
   and the `--restore-control-plane <archive>` boot argument. ~~HONEST LIMIT: the
   default destination is a LOCAL directory -- this protects against a lost or
   corrupt working copy, not against losing the host, and the archive holds the
   master keys in the clear. Off-host transfer remains a deployment concern.~~
   **STRUCK 2026-08-11 -- inverted since the 2026-08-08 backup redesign
   (`7201269a`).** There is no local destination to default to: the target is a
   `BackupTargetModel` row named by `database.control_plane_backup_target`, a
   setting declared with NO default (`HohenheimSettings.java:487-503`), and an
   unset one throws "There is deliberately no local default"
   (`ControlPlaneBackups.java:194-207`) so the nightly task fails loudly instead
   of writing to the disk it exists to outlive. The local `.zrec` is staging
   (`:64`, `:217-221`), removed in a `finally` (`:122`) that fires on upload
   failure too; the uploaded artifact is re-hashed ON THE TARGET and deleted
   there on mismatch (`:125-132`); retention prunes REMOTE keys (`:133`,
   `:149-161`) using `database.backup_retention` (`:84`). What survives from the
   struck text is its important half: the archive still holds the master keys in
   the CLEAR, so the target is now a security boundary of its own -- read access
   to it IS read access to every encrypted column. Restoring THROUGH the target
   needs a readable control-plane database (it resolves a `backup_targets` row
   and its `servers` host), so a total host loss is recovered by copying the
   artifact down by hand and passing it to `--restore-control-plane <path>`.
   Also landed: `KeyringGuard` now ADVANCES the marker to the active key on
   every passing boot, so restoring a keyring from before the last rotation
   refuses instead of passing on the original key alone, and the refusal
   message points at the recovery archive.

## What the plan's 0.6c text got wrong, and the one hazard that survives

MOOT, because nothing is deployed: the checkpointed/resumable/progress-recording
backfill, the per-field VARCHAR-to-TEXT widening migrations (`MigrationBuilder`
already selects TEXT for encrypted fields on fresh tables), and the
keyring-must-exist-before-the-new-jar deploy ordering.

STALE: the plan says `MigrationBuilder` cannot express a backfill. `execute(String)`
cannot, but `builder.data(description, bodyVersion, action)` (`MigrationBuilder.java:163`)
takes a Java action, runs on all eight backends, and is checksum-signed.

STILL REQUIRED, and subtle: declaring `.encrypted()` over a column holding
plaintext makes every read THROW -- `decryptStored` (`FieldEncryption.java:93`)
rejects any non-null value not starting with `zenc$`, from inside row hydration,
so the whole query dies and the record becomes unloadable AND unsaveable. So each
flip needs a small idempotent `builder.data(...)` heal step (re-runnable via the
`zenc$` prefix check) shipping in the SAME release as the declaration.

**The hazard an implementer will hit:** that heal cannot read through the model's
own typed accessor, because by then the constant carries `.encrypted()` and the
read throws on exactly the rows being fixed. It must read raw. Hohenheim is
SQLite-only, so `rawQuery` is acceptable here -- but it abandons the
multi-backend property `data()` otherwise gives you, and that trade must be
stated at the call site.

## Named unknowns

- ~~Whether `Files.move(..., ATOMIC_MOVE)` without `REPLACE_EXISTING` throws on
  Linux.~~ ANSWERED: it does not. The AIDEV-NOTE at `EncryptionKeyring
  .loadOrCreate:94-100` records the empirical confirmation on OpenJDK 25, and
  the code no longer relies on it.
- ~~Whether an ACME account private key is persisted anywhere. No field exists on
  `CertificateModel`; `AcmeService` was not fully read.~~ ANSWERED 2026-08-11,
  and the question was MALFORMED: it needs no field of its own. Each account key
  is a `CertificateModel` ROW with `provider = 'acme_account'`, stored in
  `PRIVATE_KEY_PEM` (`AcmeService.loadOrCreateAccountKeyPair:971-1008`, written
  at `:1001`) -- the same column as every leaf key, so it is already
  `.secret().encrypted()` (`CertificateModel.java:58-59`) and already covered by
  row 1. Nothing to add to the encrypt list.
- ~~What `ExternalIdentityModel.CLAIMS` (zenit-auth) can contain. If IdP access or
  refresh tokens land there, it joins the encrypt list.~~ ANSWERED 2026-08-11:
  they DO land there, for an OIDC provider configured without a userinfo
  endpoint. See its row in "Added 2026-08-11" above -- the fix belongs in
  zenit-auth's provider, not in this column's declaration.
- ~~The plan asserts `SiteModel` declares `ActivityPolicy.ALL`; a grep over
  hohenheim's models finds no `ActivityPolicy` anywhere.~~ ANSWERED 2026-08-11:
  the grep looked in the wrong place. Policies are not declared on the model,
  they are REGISTERED -- `HohenheimSources.java:143` is
  `ActivityLog.setPolicy(SiteModel.MODEL_ID, ActivityPolicy.ALL)`, with
  `ActivityPolicy.NONE` registered beside it for the high-volume log-shaped
  models (`:148-150`, `:156`). The plan was right. `RevisionableBehaviour` is
  confirmed at `SiteModel.java:129-130` (drifted from the `:112` recorded here).
