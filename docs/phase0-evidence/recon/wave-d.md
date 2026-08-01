## Wave D Reconnaissance Report (read-only, current HEAD)

Repos: `/home/skerit/projects/javaweb` (zenit\*), `/home/skerit/projects/hohenext/hohenheim`. All zenit paths below are relative to `/home/skerit/projects/javaweb`.

---

### D1. Same-name noncanonical fields bypass RecordSource secret gates — **REAL**

Evidence
- `zenit/src/common/java/be/elevenways/zenit/common/data/RecordSource.java:879` `project(Field...)` → `this.projection = List.of(fields);` — no schema lookup, no identity check.
- Same for `:908 sortable(...)`, `:922 search(...)`, `:996 timestamp(field)` (`this.timestampField = Objects.requireNonNull(field, ...)` only).
- `build()` at `:1126` does `M model = Models.get(this.modelClass);` (line 1127) and then validates the *passed* objects (`:1150-1171` search gates, `:1180-1186` projection gate via `FieldRedaction.redactsWholeValue(field)`) — the resolved model is used for `getSchema().getDisplayFields()` (derived search only) and never to canonicalize explicit fields.
- Read side is name-based: `Field.getValue(Row)` at `zenit/.../orm/field/Field.java:133-138` → `row.get(this.name)`; `Row.get(Field)` at `zenit/.../orm/datasource/Row.java:180-182` → `field.getValue(this)`. So `project()` (`RecordSource.java:604-622`, `row.get(field)` / `row.getLocalized(field, chain)` / `out.put(field.getName(), ...)`) reads the schema column by *name* while the gate inspected the impostor's flags.
- Derived vocabulary (`:1229-1241`) and title fallback (`:753-778`) walk the same unvalidated lists.
- `buildQuery` sort check at `:430` (`this.sortableFieldNamed(sortField.getName()) != sortField`) is identity-based but against the source's own possibly-forged list.

Mechanism to reuse
- Canonicalization seam: in `Builder.build()` (already holds `model` at `:1127`) map every declared field through `model.getSchema().getField(name)` (`zenit/.../orm/model/Schema.java:182`, backed by `getFields()` at `:171`) and reject `null` / `!=` identity.
- Exact precedent to copy, message shape included: `Model.updateAll` assignment check at `zenit/.../orm/model/Model.java:1240-1247` — `if (schema.getFields().get(fieldName) != assignment.getField()) throw new IllegalArgumentException("Assignment field '" + fieldName + "' is not part of model '" + ... + "'")`.
- Facets that need it: `project`, `sortable`, `search`, `timestamp`, plus derived vocabulary (`deriveVocabulary`, `:1229`) and the title-derivation lists (`displayTitle`/`firstNonBlankValue`, `:734-778`). "Bucket" == the sortable whitelist (`sortableFieldNamed`, `:364`).

Tests
- `zenit/src/test/java/be/elevenways/zenit/data/RecordSourceTest.java:1259-1345` `secretGateJourney()` (`@DisplayName "Secret gate: ..."`), parameterized SQLite+PostgreSQL (`datasources()` at `:92`, fixture models `PersonModel`/`TitlelessModel`/`ArticleModel` registered via `Models.registerInstance` at `:86-88`, migration built in `setup`). `PersonModel.SECRET_TOKEN`, `PersonModel.RECOVERY_CODES` (list w/ secret item), `PersonModel.INTERNAL_RANK` (filterable(false)) are the existing fixtures a forged-field step should extend.
- `zenit/src/test/java/be/elevenways/zenit/data/RecordSourceEncryptionTest.java:35,45,55,69,82` — build-refusal patterns for encrypted search/sortable/projection.

---

### D2. FormSecrets misses structural and localized secret shapes — **REAL**

File: `zenit/src/common/java/be/elevenways/zenit/common/edit/FormSecrets.java`

What it walks today
- `mask` (`:78-136`): `Nested` (incl. dynamic `schemaFrom` via `resolveSubSpec`, `:439-452`) → recursion; then `isSecret(entry)`; then `ListField` (`:107`), `StringMapField` flat + row shape (`:113`, `maskMap` `:327`, `maskRowMap` `:359`), then scalar (`:123-133`).
- `restore` (`:170-243`): identical entry-kind set + `{name}__clear` companion (`:200-206`), list keep/clear (`:210-221`), map restore (`:223-232`, `restoreMap` `:414`).
- Secret predicate: `isSecret(FormEntry)` at `:294-297` = `field != null && field.isSecret()` — **`isEncrypted()` is not considered**, diverging from `FieldRedaction.isRedacted` (`zenit/.../orm/field/FieldRedaction.java:56-58`) and `redactsWholeValue` (`:64-69`).

Confirmed gaps
1. **Secret parent Nested/SchemaField**: `if (entry instanceof Nested nested) { ...; continue; }` at `:84-101` (and `:178-195`) short-circuits *before* the `isSecret(entry)` test at `:103`/`:200`. A `.secret()` SchemaField rendered as `Nested` is never masked as a whole; if `resolveSubSpec` returns null (`:90-92`) the whole scope passes through unmasked.
2. **ListField with secret ITEM field**: `:107` only fires when the *entry* field is secret; `FieldRedaction.redactsWholeValue`'s list rule (`FieldRedaction.java:68`) is not applied.
3. **`Records` subforms**: explicitly excluded — class doc `FormSecrets.java:30-32` "Records rows and Embedded entries are not walked ... no Records consumer carries a secret yet". `Records.subSpec()` (`zenit/.../edit/Records.java:61-73`) is the sub-spec seam that must be walked per row (`Records.ID_KEY` marks the row's id).
4. **Localized secret maps**: `Localized<T>` (`zenit/.../edit/Localized.java:29,53`) is a first-class `FormEntry` (`FormEntry.java:22` permits list) whose value is a locale-keyed map. It falls into the scalar branch: `isPopulated` (`:139-149`) returns **false** for any `Map` (`return !(value instanceof Map<?,?>)`), so no `STORED_MARKER`; then `:131-132` `result.put(entry.name(), "")` **flattens the locale map to an empty string**. `restore` (`:232-241`) never restores it because `submitted` is a Map, not a blank String → a keep-blank submit wipes stored localized secrets.
5. **Dynamic switches across those shapes**: `resolveSubSpec` (`:439`) exists only for `Nested`; `Records`/`Localized`/list-of-schema have no equivalent.

Mechanism to reuse
- `FieldRedaction` is THE structural rule (`FieldRedaction.java:16-42` class doc; `isRedacted:56`, `redactsWholeValue:64`, `redactSubValues:83`, `withoutSecretSubValues:93`, `mergeSecretSubValues:114`). Adapt to transport shapes rather than re-deriving.
- Entry kinds to dispatch on: `FormEntry` sealed set at `FormEntry.java:21-23` — `Plain, Select, Array, Nested, Records, Localized, Computed, RelationPick, RelationMultiPick, Upload, QueryRules, FormEntry.Embedded`.
- Sub-spec derivation: `FieldFormEntryRegistry.INSTANCE.deriveSpec(schema)` (used at `FormSecrets.java:450`), `Nested.resolveSubSchema(sibling)` (`Nested.java:101`), `Records.subSpec()` (`Records.java:61`).
- Call sites that must stay consistent: `zenit-cms/.../page/ResourceFormPageRenderer.java:109` (mask), `SingletonPageRenderer.java:117` (mask), `SettingsPageRenderer.java:164,196` (mask + synthetic stored snapshot), `ResourcePageEndpoints.java:1779` (restore, before validation), `FormValidator.java:237` (`unrestorableSecretMapKeys`), renderer side `zenit-forms/.../render/FormEntryState.java:51` (`hasStoredSecret` maps `STORED_MARKER` to blank).

Tests
- `zenit/src/test/java/be/elevenways/zenit/edit/FormSecretsTest.java` — `flatSpec()` `:38`, `dynamicSpec()` `:45-68` (EnumField discriminator + `SchemaField.schemaFrom` + `Nested.of(settings).schemaFrom("type")`); journeys: `scalarSecretJourney:72`, `dynamicNestedSecretJourney:141`, `secretMapJourney:178`, `secretMapRowShapeJourney:221`, `newSecretMapKeySurvivesRerenderJourney:300`, `renamedSecretMapKeyJourney:354`, `secretListJourney:419`.
- `zenit-ai/src/test/java/be/elevenways/zenit/ai/common/storage/SecretMapFormJourneyTest.java:60,158` (real-model consumer journeys).
- `zenit-cms/src/test/java/be/elevenways/zenit/cms/test/page/SecretKeyValueRerenderRoundTripTest.java:120` (raw HTTP rerender round trip — the "raw browser HTML" pattern).
- Browser: `hohenheim/src/browserTest/java/be/elevenways/hohenheim/test/SecretFieldsTest.java`, `SecretToastTest.java`.

---

### D3. Restoring a normal revision can un-soft-delete a record — **REAL**

Evidence
- Snapshot capture: `zenit/.../orm/behaviour/RevisionableBehaviour.java:321-340` iterates **all** `schema.getFields()`, skipping only localized / `FieldRedaction.redactsWholeValue` / `!row.has(name)`. `deleted_at` is an ordinary `DateTimeField` added by the behaviour (`SoftDeleteBehaviour.java:40-41,57-62` → `adoptOrAddDeletedAt` → `schema.addField(DateTimeField.builder().name(OrmConstants.DELETED_AT_COLUMN)...)`), so a loaded live row snapshots `deleted_at = null`.
- Restore application: `RevisionableBehaviour.java:540-550` — `Field field = schema.getField(name); if (field == null || redactsWholeValue) continue; row.set(name, entry.getValue());` → writes `deleted_at = null` → un-trashes. Also applies to `version` / `updated_at` / publish-state columns (`PublishableBehaviour`) by the same rule.
- The existing "stays trashed" assertion is against a **forged** snapshot: `zenit/src/test/java/be/elevenways/zenit/orm/SecretRedactionJourneyTest.java:800-815` `forgeTrashRevision(...)` writes only `fields("id","title","config")`; assertion at `:882-885` therefore never exercises a real `deleted_at`-carrying snapshot.

Mechanism to extend
- Lifecycle-field ownership lives in the behaviours: `SoftDeleteBehaviour.deletedAtField()` (`:75-80`), `isTrashed(Row)` (`:96`), `restore(Row)` (`:109` — the *dedicated* untrash operation), `forceDelete` (`:120`), `isForceDelete(QueryContext)` (`:179`); `OrmConstants.DELETED_AT_COLUMN` / `VERSION_COLUMN`. Version already has a special case at `RevisionableBehaviour.java:695-698` (adopt current version) — the same "behaviour-owned field" list is the natural extension point, applied in both `snapshot()` (`:321`) and the restore apply-loop (`:540`).

Tests
- `zenit/src/test/java/be/elevenways/zenit/orm/RevisionableBehaviourTest.java` — `revisionHistoryJourney:102`, `diffAndUnknownRevisionJourney:162`, `pruningCapsHistory:192`, `encryptedFieldsStayOutOfSnapshotsAndSurviveRestore:227`; fixtures `RevisionedModel:258`, `CappedModel:286`, migration applied in `applyMigration:55`.
- `SecretRedactionJourneyTest.java:832 restoringWhenTheCurrentRowCannotBeLoaded()` (uses `TrashVaultModel` with `tableBuilder.softDeletes()` at `:87`, helpers `storedTrashRow:826`, `trashRevisionsOf:818`) — the test to make real.
- `zenit/src/test/java/be/elevenways/zenit/orm/RevisionConcurrencyTest.java`, `RevisionUniquenessMigrationTest.java`.

---

### D4. Restore is not atomically bound to `recordId` — **REAL**

Evidence (`RevisionableBehaviour.restore`, `:507-703`)
- `recordKey = String.valueOf(recordId)` (`:510`) is used only to *fetch* the revision row (`:513-517`).
- Existence lookup uses the **snapshot's** PK: `:582-583` `Object primaryKeyValue = primaryKey != null ? row.get(primaryKey.getName()) : null;` then `:585-592` `model.find().withoutFindHooks().where(pk.eq(primaryKeyValue)).first()` — no comparison with `recordId`, so a corrupt/mismatched snapshot PK targets another row.
- If the snapshot carries **no PK** (`primaryKeyValue == null`), the whole `if` block is skipped: no existence check at all, and `model.save(row)` (`:702`) falls through to its INSERT fallback → resurrection/insert of a new row (the very hazard the comment at `:594-600` describes).
- Check-then-save is not one transaction: the `first()` at `:592` and `ActivityLog.withAction(..., () -> model.save(row))` at `:701-702` are separate; a concurrent hard delete between them re-enters the INSERT fallback.
- The soft-delete lookup deliberately uses `withoutFindHooks()` (`:575-581` AIDEV note) — preserve that when re-shaping.
- Caller: `zenit-cms/.../page/ResourcePageEndpoints.java:1197` `revisionable.restore(rowResource.model(), primaryKey, revisionNumber)` with `RevisionRestoreAccess.blockedChanges(...)` pre-check at `:1184-1186` (another check-then-act window).

---

### D5. DataItem exposes secret primary-key and timestamp facets — **REAL**

Evidence
- `RecordSource.item(Row, LocaleChain)` `:650-676`: `Object pk = row.get(model.getPrimaryKeyField().getName());` (`:654`) — unconditional, lands in `DataItem.value` (`zenit/.../data/DataItem.java:22`) which is `@NonNull`. No secret/encrypted screening of the PK anywhere.
- `Object stamp = this.timestampField != null ? row.get(this.timestampField) : null;` (`:666`) → `stamp.toString()` into `DataItem.timestamp` (`:672`). Builder `timestamp(...)` at `:996-999` has **no** redaction/canonicalization gate (unlike project/sortable/search).
- Title fallback: `displayTitle` `:734-751` → `firstNonBlankValue` `:753-778`, whose guard at `:760` is `field.isSecret()` only — not `FieldRedaction.isRedacted`/`redactsWholeValue`; also `model.getDisplayTitle(row, chain)` final fallback (`:750`) is the "Model #pk" shape.
- Existing registration-time precedent for PK policy: `zenit/.../orm/model/Models.java:130-142` already refuses an `.encrypted()` primary key at `registerInstance` — the exact seam for a secret-PK decision. `Schema.java:158` refuses encryption in unsupported placements.

Tests: `RecordSourceTest.itemTranslationJourney:451` (facet emission), `secretGateJourney:1261` step 5 (title never leaks a secret), `translationBoundaryJourney:585`.

---

### D6. SecretDisclosures TTL does not bound memory residency — **REAL (as documented)**

Evidence: `zenit/src/server/java/be/elevenways/zenit/server/security/SecretDisclosures.java`
- `:67-74` AIDEV note: "the TTL only makes a claim MISS; the entry itself (and so the PLAINTEXT) stays reachable until something removes it, and the cache has no ticker -- expiry has to be driven by use ... after the LAST stash/claim an expired entry lingers until the next one."
- Pruning is only at `stash` (`:74 this.entries.prune()`) and `claim` (`:89`). Backing store `Cache<String, Disclosure>` (protoblast `common.cache.Cache`), bounded LRU `1024` / `120_000L` ms at `:26`, `:43`.
- Auditing hook already exists: `residentEntries()` `:105-107` → `entries.residentSize()`.
- Class doc `:8-16` claims single-use/TTL custody; the wording is the thing to reconcile if touch-driven expiry is accepted.

Tests: `zenit/src/test/java/be/elevenways/zenit/server/security/SecretDisclosuresTest.java` — `stashClaimJourney:15`, `anotherSessionCannotClaimAndBurnsTheEntry:48`, `expiredEntriesAnswerNull:64`, `anExpiredUnclaimedDisclosureStopsHoldingItsPlaintext:82` (this is the residency test), `ghostSessionsAndBadBoundsAreRefused:121`.

---

### D8. Table-stored Records secret behaviour explicitly incomplete — **REAL (self-declared)**

- `FormSecrets.java:30-32`: "Records rows and Embedded entries are not walked: an Embedded editor owns its own transport, and no Records consumer carries a secret yet."
- `Records` is a full `FormEntry` with a walkable sub-spec: `zenit/.../edit/Records.java:26,48 field()`, `:61-73 subSpec()` (explicit override or derived, cached in `derivedSubSpec:34`), `ID_KEY` row identity (`:75`), sub-spec entry validation in `Builder.build` (`:133`).
- No `Records` branch exists in either `mask` (`:83-134`) or `restore` (`:176-241`); no test in `FormSecretsTest` covers `Records`.
- Consumer journey candidates for the "concrete consumer" requirement: hohenheim `DnsRecordResource`/`SpamserviceClientKeysResource` (`hohenheim/src/server/java/be/elevenways/hohenheim/server/cms/`), zenit-ai `McpServerConfigModel` secret maps.

---

### D7 (owner decision) — concrete affected data surfaces

**`zenit_revisions`** (table created by `zenit/.../orm/revision/M001_CreateRevisionsTable.java`, model `RevisionModel.java`; snapshot column `RevisionModel.SNAPSHOT`, DRY-stringified at `RevisionableBehaviour.java:141`). Revisions are opt-in per model; the **only production model with `RevisionableBehaviour`** in either repo is:
- `hohenheim/src/common/java/be/elevenways/hohenheim/model/SiteModel.java:111-112` (`RevisionableBehaviour.create(50)`). Historically-plaintext fields inside its snapshots:
  - `SiteModel.SECURITY_REPORT_TOKEN` (`SiteModel.java:102-103`, `.secret()`).
  - `SiteModel.SETTINGS` JSON (`:52-57`, dynamic `SchemaField` per site type) → secret sub-fields declared by `sitetype/types/JavaSiteType.java:74,79`, `CommandSiteType.java:65,70`, `NodeSiteType.java:83,90`, `DockerSiteType.java:55`, `DevNamespaceSiteType.java:37` (environment variables maps, dyndns/API-key style tokens).
  - `SiteModel.SOURCE_SETTINGS` JSON (`:74-79`) → `source/GitSourceSchema.java:43` `webhook_secret`, `:59` `build_environment_variables`.
- (Only test fixtures elsewhere: `RevisionableBehaviourTest.RevisionedModel/CappedModel`, `SecretRedactionJourneyTest.VaultModel/TrashVaultModel`.)

**`zenit_activity`** (`ActivityLog`, `zenit/.../orm/activity/ActivityLog.java`; global hooks installed at `:117-127`, deltas built at `:445-480` with forward-only redaction; `ENABLED` setting `:63-68`, `RETENTION_DAYS` `:71-76`, prune job `hohenheim/.../task/CleanOldActivity.java:49`). Because the hooks are **global**, historical rows can contain deltas for *every* model with secret/encrypted fields:
- hohenheim: `SiteModel` (above), `DnsPeerModel.API_KEY:28`, `DnsPeerModel.TSIG_SECRET:39`, `DnsRecordModel:83` (dyndns token), `DnsZoneModel.dnssec_private_key:73`, `NotificationChannelModel.URL:36` (webhook URLs), `CertificateModel:59` (private key), `DatabaseModel:53`, `AccessListModel:30`, `SpamserviceInstallationModel.controller_key:41`, `StackModel:79-80`, `StackFileModel:35`, `StackDeploymentModel:40`, `ProteusAuthProviderType.ACCESS_KEY` (`server/auth/types/ProteusAuthProviderType.java:42`), settings in `HohenheimSettings.java:155,386,577`.
- framework/other: `zenit-auth ApiKeyModel.HASH:32`, `zenit-ai ProviderConfigModel.api_key:39`, `McpServerConfigModel.env:35` / `headers:42`, `ModelProviderLinkModel.extra_headers:47`, `proteus RealmClientModel.API_KEY:31` + `AuthenticatorTypeDefinition client_secret:34`, `spamservice ClientKeyResource:48` / `SpamserviceSettings:82,90,98`, `zenit-comms CommsSettings:34,43,52,87`, `zenit ServerSettings:215,370`, quirkyquarters `QQSettings` + IRC/Telegram type definitions.
- Redaction is write-time only: `ActivityLog.java:79-82` "Existing activity rows are not rewritten."

Also in scope for the owner: settings stores rendering `STORED_MARKER` (`zenit-cms/.../SettingsPageRenderer.java:164`) and `hohenheim.db` / backups.

### D9 (owner decision) — encryption scope surfaces

- Declared-field encryption only: `Field.Builder.encrypted()` → `FieldAttributes.java:53`; envelope codec `zenit/src/server/java/be/elevenways/zenit/server/orm/crypto/FieldEncryption.java`; keyring setting `ServerSettings.java:454-462`.
- Structural limits already enforced: `Schema.java:158` ("only main-table fields and table-stored sub-schema fields support `.encrypted()`"), `Schema.refuseEncryptedJsonSubFields` (`Schema.java:~150-163`) — i.e. **every JSON-nested secret is `.secret()`-only, never encrypted** (`FieldRedaction.java:22-26`). Current encrypted fields in the corpus: `hohenheim StackModel:79-80`, `StackFileModel:35`, `StackDeploymentModel:40`.
- Consequently the unencrypted-at-rest set = all `.secret()`-only fields listed under D7 plus all sub-schema secrets under site-type/git-source schemas.

---

### Cross-cutting mechanism map (for implementers)

| Mechanism | Location |
|---|---|
| Canonical field resolution | `Models.get(Class)` `orm/model/Models.java:52`; `Model.getSchema()/requireSchema()`; `Schema.getField(name)` `Schema.java:182`, `Schema.getFields()` `:171`, `getLocalizedFields()` `:187`; identity-check precedent `Model.java:1240-1247`; registration-time policy hook `Models.registerInstance` `:129-148` |
| Structural secret rule | `FieldRedaction`: `isRedacted:56`, `redactsWholeValue:64` (incl. ListField item rule `:68`), `redactSubValues:83` (marker mode), `withoutSecretSubValues:93` (omit mode), `mergeSecretSubValues:114` (restore graft + discriminator disagreement), `REDACTED:49` |
| Form-entry walking | `FormSecrets.mask:78` / `restore:170` / `unrestorableSecretMapKeys:263`; entry kinds `FormEntry.java:21-23`; sub-spec seams `Nested.resolveSubSchema:101`, `Records.subSpec:61`, `FieldFormEntryRegistry.deriveSpec` |
| Revision snapshot/restore | `RevisionableBehaviour.snapshot:321`, `captureRevision:107`, `restore:507`, apply-loop `:540-550`, existence check `:582-606`, graft loops `:640-690`, version adoption `:695-698`, save `:701-702`; per-record lock `acquireRevisionLock:156`; reads `snapshotOf:424`, `diff:467` |
| Soft-delete contract | `SoftDeleteBehaviour`: `attached:40`, `adoptOrAddDeletedAt:57`, `deletedAtField:75`, `isTrashed:86/96`, `restore(Row):109` (the only legitimate untrash), `forceDelete:120`, `softDeleteByQuery:148`, `isForceDelete:179`; column `OrmConstants.DELETED_AT_COLUMN` |
| Derived-surface consumers | `ActivityLog:445-480`; `zenit-cms/.../page/RevisionHistoryPageRenderer.java`, `RevisionRestoreAccess.java`, `render/revision/RevisionDiffRowState.java` |

### Test-coverage index

- Secrets/redaction core: `zenit/src/test/java/be/elevenways/zenit/orm/SecretRedactionJourneyTest.java` (5 journeys, `:157,367,485,647,832`; fixtures `VaultModel`/`TrashVaultModel`, forged-history helpers `forgeLegacyRevision`, `forgeTrashRevision:801`).
- Forms: `zenit/src/test/java/be/elevenways/zenit/edit/FormSecretsTest.java`; `zenit-ai/.../SecretMapFormJourneyTest.java`; `zenit-cms/.../page/SecretKeyValueRerenderRoundTripTest.java`; `zenit-forms/src/test/java/be/elevenways/zenit/forms/test/render/FormStateTranslatorTest.java`.
- RecordSource: `RecordSourceTest.java` (parameterized SQLite+Postgres), `RecordSourceEncryptionTest.java`, `RecordSourceEndpointsTest.java`.
- Revisions: `RevisionableBehaviourTest.java`, `RevisionConcurrencyTest.java`, `RevisionUniquenessMigrationTest.java`; CMS `zenit-cms/src/browserTest/.../RevisionHistoryBrowserTest.java`; hohenheim `src/browserTest/.../RevisionRestoreTakeoverTest.java:106`, `SiteHistoryTest.java`.
- Disclosure/flash: `SecretDisclosuresTest.java`, `zenit-cms/.../flash/CmsFlashSecretArgsTest.java`, hohenheim `SecretToastTest.java`, `SecretFieldsTest.java`.
- Diff rendering: `zenit-cms/.../page/DiffRenderingRedactionTest.java:65,128`.