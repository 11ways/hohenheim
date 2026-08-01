# Wave B recon report (zenit-auth @ 857fcb3) - verified 2026-07-31

All of B1, B2, B3, B4, B5, B7, B9 are REAL at current HEAD. B6/B8 skipped (owner decisions).

## B1 - self-escalation to wildcard admin - REAL
- AuthUsersResource.java:51-55 grants editor (GrantsEditField) in ordinary user form.
- :81-89 one permission auth.users.edit covers profile AND grants; no per-op overrides.
- :249-257 applyGrantDiff has no actor param; AccessContext available in persistRow/updateRow (:166-183) but never passed down.
- AuthGrantsBinding.java:68-82 applyDiff writes any permission string verbatim via GrantService.createDirectGrant; no authority check.
- PermissionResolver.java:258-260 wildcard honored. No self-edit guard anywhere.
- Mirror defect in AuthRolesResource.java:44-49,81-83,200-208 via auth.roles.edit (role grants apply to members => escalation via group.<slug>).

Reuse:
- Per-field gating exists server-side: Resource.fieldBindings()/fieldAccessFor at zenit-cms Resource.java:644-651,711-727; forged submits stripped by ResourcePageEndpoints.enforceFieldAccess:1880-1897 (called from create :624, update :666). FieldAccess factories zenit .../edit/FieldAccess.java:67-90.
- Per-operation hooks: Resource.createPermission()/updatePermission()/deletePermission() Resource.java:320-366; enforced ResourcePageEndpoints.java:597,611,655,686,1142 via checkPermission:1918-1925.
- Delegability precedent (capabilities only): ApiKeyService.requireMintableCapabilityScopes ApiKeyService.java:86-127 + actorHoldsCapability:129-145. Permission-shaped scopes pass through untouched (javadoc :76-77) - no permission authority model today.
- KnownPermission has NO authority/delegability field (zenit KnownPermission.java:18-21); KnownCapability does (:24-31, ADMIN+delegable unrepresentable :41-44). Permission-side delegability must be ADDED, modelled on KnownCapability/KnownCapabilities.
- Actor authority: PermissionResolver.decide(principal, permission) :50; specificity/deny-wins :253-274. New auth.grants.manage constant belongs in AuthEndpoints.java:34-37.

Tests: AuthCmsResourcesIntegrationTest grantsEditorRoundTripPersistsTheDiffOnStorage:283, rolesJourney...:373, adminScopedApiKey...:442; real HTTP TestClient :622. CMS precedents: ResourcePageEndpointsTest.java:1306, :3453, :2340. Mint policy: ApiKeyMintTest.java.

## B2 - last-administrator lockout - REAL
- AuthUsersResource.deleteRow:189-201 no admin count check.
- Setup marker independent of users: AuthHandlers.java:92-95 write, :126 latch, ZenitAuth.java:84 load; /setup refuses AuthHandlers.java:60-62, POST 409 :68-72.
- No countAdministrators helper exists.

Write paths that can remove admin authority (all unguarded):
1. User delete AuthUsersResource.java:189-201 + AuthGrantsBinding.deleteSubjectGrants:85-90
2. User disable toggle - OUTSIDE inMutationTransaction, bare model().save(row) - AuthUsersResource.java:205-230 (:217-229)
3. User grant diff AuthUsersResource.java:249-257 -> AuthGrantsBinding.applyDiff:68-82 -> GrantService.createDirectGrant:38-62 / grants().delete :79
4. Group-membership removal = case 3 with group.<slug> (expanded PermissionResolver.java:93-103,142-150)
5. Role grant diff AuthRolesResource.java:200-208
6. Role slug edit AuthRolesResource.java:146-159 in updateRow:171-178 (see B3)
7. Role delete AuthRolesResource.java:185-198 + deleteMembershipGrants AuthGrantsBinding.java:93-97
8. GrantService.deleteDirectGrant:81-87 (public API, no CMS caller)
9. Adjacent: login lockout AuthHandlers.registerFailedLogin:700-716 (decision)

No generic bulk delete (only resource-declared bulkActions; neither auth resource declares any). Single delete funnel Resource.deleteRow (ResourcePageEndpoints.java:690).
Reuse: RowResource.inMutationTransaction zenit-cms RowResource.java:84-88; Violations.ofForm/ofField refusal channel rendered by delete endpoint (ResourcePageEndpoints.java:689-696); PermissionResolver.decide is THE effective-admin oracle.
Concurrency test precedent: RecordGrantsPostgresTest.java:100-130, RecordGrantsTest.java:139.

## B3 - role slug rename strands/reattaches memberships - REAL
- AuthRolesResource.java:146-159 writes SLUG directly; updateRow:171-178 saves; nothing rewrites group.<oldSlug> rows.
- deleteRow:185-198 deletes memberships by CURRENT slug only (AuthGrantsBinding.java:93-97).
- Slug resolved at read time (PermissionResolver.java:93-103,142-150) => rename-back re-adopts stranded grants. Confirmed.
- slug IS DB-unique (M001_CreateAuthTables.java:48) but model field declares no uniqueness (PermissionGroupModel.java:23) => collision = raw DuplicateKeyException not field violation.
Reuse: inMutationTransaction wraps updateRow already; Violations.ofField("slug",...) shape at AuthRolesResource.java:210-216; DuplicateKeyException handled in GrantService.java:4,54.
Test: rolesJourney...:373 covers delete-cleans-memberships, NOT rename.

## B4 - capability rules silent last-wins - REAL
- RecordGrantCapabilityChecker.java:46-51 RULES.put unconditional; javadoc "Declare (or replace)"; clearRules:53-56; no override method.
- Rules feed mint path (ApiKeyService.java:108-115,132-134).
Reuse verbatim: KnownCapabilities.java:47-84 (register/override/equal-no-op :72-73/loud IllegalStateException :75-80, describe() :90-95). RecordCapabilityRules is a record => structural equals free.
Consumer needing idempotent re-registration: hohenheim HohenheimAccess.java:55-62 declareGrantableModels(), called ServerMain.java:165 AND WorkloadIdentityTest.java:54.
Golden test to copy: zenit KnownCapabilitiesTest.java:99-124. Checker tests: RecordCapabilityCheckerTest.java.

## B5 - grants for nonexistent records - REAL
- RecordGrants.java:122-136 validates model (requireGrantable:320-327) + subject (requireSubject:334-345) but never the target row; recordId stringified blindly :146.
- ZERO production callers of RecordGrants.grant - only tests (RecordGrantsTest.java:74,88,109..., GrantMigrationChainMySqlTest.java:113) granting on synthetic ids (7,8,42) with no backing rows. Existence validation WILL break those tests; they need real rows or a resolved-record overload.
- Related: RecordGrantCleanup.java:35,166; RecordGrants.revokeAllForRecord(s):519,530.
Reuse: GRANTABLE set (RecordGrants.java:58,70-79) is the natural place for a liveness/existence declaration (shared with C6 - design once as GrantableModel declaration). Model->PK resolution precedent WITHOUT scoped RecordSource: RecordGrantCapabilityChecker.ownerOf:69-104 (Models.get, getPrimaryKeyField, SubmittedValueCoercion.coerceFieldOrThrow, owned.find().where(pk.eq(coerced)).first()).
Tests: RecordGrantsTest.java (:74 undeclared-model refusal), RecordGrantCleanupTest, RecordGrantOrphanPurgeTest, RecordGrantsPostgresTest.

## B7 - permissions editor ignores readonly - REAL
- PermissionsEditState.java:39 declares readonly; populated at AuthFormRenderers.java:51.
- zenit-forms permissions-edit.hwk (21 lines) never references entry.readonly; unconditionally emits __present hidden marker :8 and editable pl-permissions-editor :9-16.
- Sibling templates honor it: array.hwk:7 (readonly branch -> zf-array-readonly-item :19), key-value.hwk:8, zf-array.hwk:99.
- plumage pl-permissions-editor has NO readonly/disabled support (permissions-editor.hwk inputs :149-158).
- __present marker while readonly is the sharp edge (GrantsEditField.java:34-35,76-81 leave-alone semantics).
- Same defect in proteus permissions-edit.hwk and quirkyquarters action-permissions-edit.hwk.
- NO tests reference PermissionsEditState/permissions-edit. Nearest: plumage PermissionsEditorTest.java + PermissionsShowcase.java + permissions-editor-test.hwk. CMS readonly goldens: ResourcePageEndpointsTest.java:3453,:2632.

## B9 - transaction fault-injection gap - REAL
- Transaction real: AuthUsersResource.java:166-183, AuthRolesResource.java:161-178 wrap super + applyGrantDiff in inMutationTransaction (RowResource.java:84-88); AuthGrantsBinding javadoc :20-23 states contract.
- No failure-injecting test: only rollback/withTransaction hit in tests is RecordGrantsPostgresTest.java:109 (unrelated).
- Toggle-enabled path (B2 #2) outside any transaction - second gap.
- mutateRowInScope (ResourcePageEndpoints.java:1849-1869) opens its OWN inMutationTransaction around create when accessFunction() exists => nesting; SQLite nesting suspect per C13.
Infra: GrantAuthorizationPolicyTest.java:44-51 (SQLite in-mem + MigrationRunner), RecordGrantsPostgresTest.java:29-40 (Testcontainers), AuthTestHarness/AuthTestClient in zenit-auth-test-support, fake-resource inMutationTransaction override precedent ResourcePageEndpointsTest.java:5494.

## Cross-cutting
- One shared grant-management policy class in zenit-auth server/, consumed by both applyGrantDiff sites, threaded the AccessContext already in persistRow/updateRow signatures (AuthUsersResource.java:167,177).
- B2 invariant + B1 policy share PermissionResolver.decide and inMutationTransaction; implement admin-count check INSIDE existing transactions; add transaction to toggle-enabled handler.
- B4 = mechanical port of KnownCapabilities.put semantics + overrideRules.
- B5 existence + C6 liveness want the same GrantableModel declaration - design once.
