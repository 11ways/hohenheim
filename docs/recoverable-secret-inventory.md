# Recoverable-secret inventory (0.6c)

Checked in 2026-08-02, the artifact the Phase 2 parallel gate demands. Every
field below was read at its declaration AND at its use site; the posture column
is what the code does today, not what a docblock claims.

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
now `.secret()`. `TotpModel.SECRET` was deliberately NOT touched: the v2 AAD
binds table|column, not row, so a cross-user seed swap still decrypts -- it
needs row-level integrity of its own first.

The organising question is NOT "is this sensitive" but **"must this value be
RECOVERABLE"**. A credential the code only ever COMPARES belongs hashed;
encrypting it is the weaker design because it keeps a reversible copy for no
reason. A credential the code must PRESENT to a third party (a TLS stack, a peer
nameserver, a child process) cannot be hashed, and encryption is the only lever.

Posture legend: HASHED (one-way, correct) | ENCRYPTED (`.secret().encrypted()`) |
SECRET (redacted in derived surfaces, PLAINTEXT in the column) | PLAIN | EXTERNAL.

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
| 5 | `DatabaseModel.DB_PASSWORD` | `DatabaseModel.java:52` | managed tenant database password |
| 6 | `NotificationChannelModel.URL` | `NotificationChannelModel.java:36` | Slack/Discord/webhook URL -- the URL IS the bearer token |
| 7 | `SiteModel.SECURITY_REPORT_TOKEN` | `SiteModel.java:103` | spamservice reporting credential; injected raw into the child env |
| 8 | `SpamserviceInstallationModel.CONTROLLER_KEY` | `SpamserviceInstallationModel.java:40` | local spamservice control API |
| 9 | `StackServiceModel.ENVIRONMENT` | `StackServiceModel.java:110` | **PLAIN today, not even secret.** A `StringMapField` on the MAIN TABLE, so unlike `SiteModel.environment_variables` it is not inside a JSON SchemaField and encryption IS available |
| 10 | `TotpModel.SECRET` (zenit-auth) | `TotpModel.java:25` | **PLAIN today, not even secret.** The worst posture found. BLOCKED ON AAD -- see below |

## Correct as-is -- do not "improve" these

| Field | Posture | Why it stays |
| --- | --- | --- |
| `DnsRecordModel.DYNDNS_TOKEN` | HASHED | Verified only. `DynamicDnsService.java:223` does `where(DYNDNS_TOKEN.eq(digest))` against an index; encrypting it would throw at `SqlCriteriaTranslator.java:92` and `DyndnsTokenIndexTest` pins the index. The exemplar of hashing being stronger |
| `TotpModel.BACKUP_CODES` | HASHED | one-time codes, compared only |
| `PasswordModel.HASH`, `ApiKeyModel.HASH` | HASHED | correct; `ApiKeyModel` is the model to copy |
| Site `api_keys` (`NodeSiteType.java:86`) | HASHED | 0.6b landed this; inside JSON so unencryptable anyway |
| `CertificateModel.CERTIFICATE_PEM`, `DNSSEC_PUBLIC_KEY` | PLAIN | public by definition; encrypting is theatre |
| `SiteDomainModel.LIVE_ROUTE_KEY` | PLAIN | not a credential -- a derived uniqueness key backing a UNIQUE index. Non-deterministic ciphertext would destroy it and break M045 |
| All three `StackModel`/`StackDeploymentModel`/`StackFileModel` encrypted fields | ENCRYPTED | genuinely recoverable; the reference declarations |

## Cannot be encrypted -- JSON sub-fields

`Schema.refuseEncryptedJsonSubFields` (`Schema.java:154`) forbids it. Redaction
is the only lever until each gets a real column or a table-stored sub-schema.

- `GitSourceSchema.WEBHOOK_SECRET` (`GitSourceSchema.java:42`) -- HMAC needs the raw value, so it cannot be hashed either.
- `ProteusAuthProviderType` `access_key` (`ProteusAuthProviderType.java:42`).
- `GitSourceSchema.BUILD_ENVIRONMENT_VARIABLES`, dev `registration_token`.
- **`GitSourceSchema.REPOSITORY_URL` was PLAIN and was missed by every prior category list.** A private-repo clone URL routinely embeds `https://user:TOKEN@host/...`. The minimum fix -- `.secret()` -- LANDED 2026-08-02; the correct eventual fix remains a separate credential field so the URL itself can stay visible and editable.

## Fix by TIGHTENING hashing, not by encrypting

`AccessListModel.BASIC_AUTH_PASS` (`AccessListModel.java:27`) hashes with argon2
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
(`HohenheimSettings.java:154`), the database password (`:441`) and the Proteus
access key (`:611`) are SETTINGS, stored in `.dry` files. Field encryption cannot
reach them and should not claim to.

**And the keyring default path is `settings/field-encryption.keys` -- the SAME
directory as those plaintext settings files.** Encrypting a database column while
the master key sits beside the plaintext secrets it is meant to protect is a
narrow win. Any platform-wide encryption claim must say so.

## Keyring defects found (zenit, `EncryptionKeyring.java` / `FieldEncryption.java`)

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
3. **No AAD.** `FieldEncryption.java:22-28` documents the absence. A valid
   envelope grafts cleanly between rows and columns: site A's TLS key onto site
   B's certificate row, one zone's DNSSEC key onto another, user A's TOTP seed
   onto user B (an authentication bypass). The comment itself names TOTP.
4. **Permissive existing file modes are never inspected.** Creation restricts to
   0600, but `loadOrCreate` reads a pre-existing file with no mode check.
5. **No fsync**, neither the file nor the parent directory. For a file whose loss
   is permanent and which has no second copy, that is under-engineered.
6. `guardAgainstCommit:193-209` writes a `.gitignore` only when NONE exists, so
   in any real repo the guard silently does nothing.

Rotation itself is the best-built part (JVM mutex + cross-process `FileLock`,
re-reads under the lock, regenerates on id collision) -- but there is **no
re-encryption and no key retirement**, so rotation adds a key and never reduces
exposure of anything already written. It is half a rotation story; say so.

## Sequencing, and why AAD goes FIRST

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
4. Backup: one archive carrying DB + keyring with a recorded checksum of each.
   Ordering is easy because the keyring is prepend-only -- snapshot the keyring
   FIRST; a keyring newer than the DB is always safe, older never is.

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

- Whether `Files.move(..., ATOMIC_MOVE)` without `REPLACE_EXISTING` throws on
  Linux. High confidence it does not; confirm with a real two-process test
  before writing the fix.
- Whether an ACME account private key is persisted anywhere. No field exists on
  `CertificateModel`; `AcmeService` was not fully read.
- What `ExternalIdentityModel.CLAIMS` (zenit-auth) can contain. If IdP access or
  refresh tokens land there, it joins the encrypt list.
- The plan asserts `SiteModel` declares `ActivityPolicy.ALL`; a grep over
  hohenheim's models finds no `ActivityPolicy` anywhere. `RevisionableBehaviour`
  is confirmed at `SiteModel.java:112`.
