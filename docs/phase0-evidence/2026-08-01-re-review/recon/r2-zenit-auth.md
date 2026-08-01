# R2 - F2 and F3 verification (zenit-auth af25fa6, zenit 8b6a60b)

Read-only recon. No files changed, no builds, no suites run.

---

# F2 - "GrantService.createDirectGrant can overwrite a wildcard grant with false
# without invoking AdministratorGuard"

## VERDICT: FALSIFIED (as a security finding). Accurate as a statement about the
## method in isolation; wrong about reachability, and its implied fix is harmful.

### What the code actually says

`zenit-auth/src/server/java/be/elevenways/zenit/auth/server/GrantService.java:38-62`

```java
public static Row createDirectGrant(String subjectType, int subjectId, String permission, boolean value) {
    Row existing = findDirectGrant(subjectType, subjectId, permission);
    if (existing != null) {
        return updateValue(existing, value);
    }
    ...
}
```

`GrantService.java:88-97` (the guarded sibling)

```java
public static boolean deleteDirectGrant(String subjectType, int subjectId, int grantId) {
    boolean[] deleted = new boolean[1];
    AuthModels.grants().getResolvedDatasource().withTransaction(transaction ->
        AdministratorGuard.enforce(() -> deleted[0] = AuthModels.grants().find()
            .where(GrantModel.ID.eq(grantId))
            ...
```

So yes: `createDirectGrant` contains no `AdministratorGuard` call. That much of the
finding is literally true and is exactly what the B2 report already declared as a
deliberate decision ("GrantService.createDirectGrant is NOT guarded ... the diff
callers are guarded as a whole", prior/reports/b1-b2-b3-b9.md B2 "Known limitations").

### Does flipping a wildcard to false actually remove administrator authority?

Yes. `AdministratorGuard.java:41-44` defines the oracle as the three-permission
conjunction `auth.admin.access` + `auth.users.edit` + `auth.grants.manage`, decided
per stored user id via `PermissionResolver.decideStoredUser`
(`AdministratorGuard.java:98-105`). `PermissionResolver.permissionSpecificity`
(`PermissionResolver.java:270-272`) gives `*` specificity 0 for every permission, and
`decideFrom` (`:85-103`) returns that grant's value when nothing more specific
matches. A user whose only grant is `*` therefore flips from TRUE to FALSE on all
three permissions the moment the row's value becomes `false` -> `holdsAdminAuthority`
returns false -> `countAdministrators()` drops. The reviewer's mechanism claim is
sound in principle.

### Every caller, both workspaces

Grep over `/home/skerit/projects/javaweb` and `/home/skerit/projects/hohenext`
(excluding `build/`) for `createDirectGrant`:

PRODUCTION (2):

1. `zenit-auth/.../server/cms/AuthGrantsBinding.java:73` inside `applyDiff`.
   `applyDiff` has exactly two production callers:
   - `AuthUsersResource.java:278` (from `applyGrantDiff`, called at `:187` and `:197`)
   - `AuthRolesResource.java:233` (from `applyGrantDiff`, called at `:190` and `:200`)
   Both call sites are lexically inside
   `this.inMutationTransaction(() -> AdministratorGuard.enforce(() -> { ... }))`
   at `AuthUsersResource.java:185`, `:195` and `AuthRolesResource.java:188`, `:198`.
   GUARDED, and guarded at the right granularity (whole diff).

2. `zenit-auth/.../server/identity/AutoProvisioningSink.java:77`
   ```java
   int userId = row.get(UserModel.ID);
   for (String permission : this.defaultPermissions) {
       GrantService.createDirectGrant("user", userId, permission, true);
   }
   ```
   Value is hardcoded `true`, on a user row created three lines earlier (`:67-73`).
   A positive grant on a brand-new subject cannot lower `countAdministrators()` under
   `decideFrom`'s specificity/deny-wins fold, so this path is incapable of the
   mutation the finding describes. NOT A HOLE.

TEST-ONLY (the rest): `AuthResourceTransactionTest:73,101`,
`AuthGrantAdministrationTest:390`, `GrantHealMigrationTest:104`,
`AdministratorGuardPostgresTest:133`, `OrconoBrowserTestBase:99`,
`OrconoAuthFoundationTest:128,133,135,137`, hohenext
`CapabilityWalkTest:116`, `ManagePanelTest:322,351,372`.

### Other reachable mutation paths checked and excluded

- No other production writer of `auth_grants` exists. The only `grants().save`/
  `delete` sites are `GrantService.java:52,68`, `AuthGrantsBinding.java:79`
  (inside `applyDiff`, guarded), and `AuthHandlers.java:108-114`.
- `AuthHandlers.java:108-114` is the `/setup` bootstrap: it writes the initial
  `*`=true row directly, bypassing `GrantService` entirely. It is unreachable once
  seeded (`AuthHandlers.java:69-72` returns 409) and only ADDS authority. It is also
  the exact "bootstrap path that legitimately runs before any administrator exists"
  the assignment asked about, and it is already immune because `enforce` is
  PRESERVING, not asserting (`AdministratorGuard.java:71`: `if (before > 0 && ...)`).
- No CMS Resource exists over `GrantModel`. `AuthCmsPanelBridge.java:64` registers
  exactly `new AuthUsersResource(), new AuthRolesResource()`. No HTTP endpoint,
  API-key route, MCP tool, seeder or importer writes ordinary grants.
- `GrantModel`, `UserModel` and `PermissionGroupModel` declare no behaviours - no
  revisionable, no soft delete - so there is no revision-restore path that could
  replay a grant row. Confirmed by reading `GrantModel.java:20-44` and grepping the
  other two for `Behaviour|Revision|SoftDelete` (no hits).
- Neither auth resource declares bulk actions (confirmed in the prior recon and
  unchanged: `rowActions()` at `AuthUsersResource.java:220-267` declares only
  toggle-enabled and logout-everywhere; toggle-enabled is itself guarded at `:237`).

### Why moving the guard INTO GrantService would be a regression, not a fix

Three independent reasons:

1. **It would not even cover the operation it claims to.** The other half of a diff
   is `AuthGrantsBinding.java:76-81`, which deletes removed permissions through
   `AuthModels.grants().delete(stored)` - it never touches `GrantService`. Guarding
   `createDirectGrant` leaves the delete half unguarded, so the "every caller
   inherits it" property is false by construction.

2. **It judges intermediate states and produces false refusals.** `applyDiff` upserts
   desired entries in SUBMITTED order (`AuthGrantsBinding.java:71-74`). A legitimate
   diff on the sole administrator that replaces `*` with the three explicit admin
   permissions, submitted with the `*`=false entry first, would momentarily leave
   zero administrators and be refused even though the END state has one. This is
   precisely the "a per-entry guard inside a diff would judge intermediate states"
   decision recorded in the B2 report.

3. **Nesting cost / redundancy.** `enforce` (`AdministratorGuard.java:65-76`) does a
   `SELECT ... FOR UPDATE` over every candidate user plus two full
   `countAdministrators()` walks. Per grant entry that is O(entries) admin walks
   inside an already-guarded transaction. It is not a deadlock (lock acquisition is
   in primary-key order, `:136-140`, and re-locking rows the same transaction already
   holds is a no-op) and not infinite recursion (the guard's own reads/locks never
   call `GrantService`), so it is "merely" wasteful and wrong, not fatal.

### Residual, honest exposure

`GrantService` is public API. A future app or module could call
`createDirectGrant(subjectType, id, "*", false)` outside any guard and lock the
install out. That is a *hardening* opportunity, not a live defect: there is no such
caller today in either workspace. If it is worth closing, the correct shape is NOT
a guard inside `createDirectGrant` (see above) but the same shape
`deleteDirectGrant` already uses - a guarded PUBLIC entry point covering the whole
unit of work, e.g. `GrantService.applyGrants(subjectType, id, entries)` wrapping
`AuthGrantsBinding.applyDiff` in `withTransaction(() -> AdministratorGuard.enforce(...))`,
with `createDirectGrant` kept as the unguarded primitive the guarded paths compose
from (exactly the current internal split, just given a guarded public face). No
parallel API: it is one more method on the same class, and the CMS resources would
be its second consumer only if we wanted to move the guard down a level - they do
not need to change.

### Counterfactual test (only if the hardening above is adopted)

`AuthGrantAdministrationTest`, new step in
`everyWritePathPreservesOneEnabledAdministrator`:
step 9 - with `rootId` the only administrator, assert
`assertThrows(Violations.class, () -> GrantService.applyGrants("user", rootId, List.of(entry("*", false))))`
and `assertEquals(Boolean.TRUE, grantValue("user", rootId, "*"))`.
That test does not exist today and would fail against any implementation that keeps
`createDirectGrant` as the only public write.

### Consumers a change here could break

`AuthGrantsBinding.applyDiff` (the sole production diff writer), both auth CMS
resources, `AutoProvisioningSink`, and 12 test call sites across zenit-auth, orcono
and hohenheim that call `createDirectGrant` directly as a fixture helper - all of
which would start paying a guard they do not want if the guard moved into the
primitive. `AuthGrantAdministrationTest.grant()` (`:389-391`) and
`AdministratorGuardPostgresTest:133` build their fixtures with it *before* any
administrator exists; they survive only because the guard is preserving.

### Confidence: HIGH

The caller set is small and exhaustively enumerated; the guard placement at both
diff call sites is lexically verifiable; the "unguarded reachable path" the finding
asserts does not exist.

---

# F3 - "self-edit refusal covers only direct user targets, so a role member can
# self-pin a delegable permission into their own role"

## VERDICT: REAL

### What the code actually says

`zenit-auth/.../server/GrantAdministration.java:63-65`

```java
if ("user".equals(subjectType) && Long.valueOf(subjectId).equals(actor.principalId())) {
    throw refusal(entryName, mc("refused", "grant_self"));
}
```

The subject-type literal `"user"` is the whole of the self-edit rule. Role diffs
reach the identical policy with `"group"`:

`AuthRolesResource.java:229-234`

```java
if (desired instanceof List<?>) {
    List<GrantsEditField.Entry> entries = GrantsEditField.entriesFromValue(desired);
    GrantAdministration.requireAuthorizedDiff(accessContext, "group", roleId, GRANTS, entries);
    AuthGrantsBinding.applyDiff("group", roleId, entries);
}
```

and the actor's membership is never consulted. The class's own AIDEV-NOTE
(`GrantAdministration.java:41-47`) names the escalation it intends to close:

```
 * AIDEV-NOTE: The self-edit refusal covers the PINNING escalation, not only
 * the obvious one: containment already stops an actor from adding authority
 * it lacks, but writing a permission it currently holds THROUGH A GROUP into
 * its own direct grants would survive the group membership being revoked.
 * Grant administration of your own authority is another administrator's job.
```

Pinning is exactly what the role path still permits, so the finding is a gap against
the mechanism's OWN stated invariant, not an outside opinion.

### Is the actor's role membership knowable here? Yes.

`PermissionResolver.expandSubjects(String subjectType, int subjectId)`
(`PermissionResolver.java:112-114`) returns "the subjects in walk order, starting
with the given subject itself" - user first, then every permission group reachable
through positive `group.<slug>` grants, breadth-first and cycle-guarded. It is
public static, and `GrantAdministration` sits in the same package
(`be.elevenways.zenit.auth.server`), so even the package-private uncached
`PermissionResolver.walk(...)` (`:159`) is reachable. `AccessContext.principalId()`
already supplies the actor id and is already used at `GrantAdministration.java:63`.
Note: `expandSubjects` goes through `cachedWalk` (`:134-152`), the per-request memo;
the guard-vs-memo lesson at `PermissionResolver.java:71-79` argues for using the
uncached `walk(...)` here for the same reason `decideStoredUser` does.

### Is it an ESCALATION or merely persistence of authority already held?

This is the load-bearing question, so, explicitly:

At the instant of the write it is **not** a widening. `GrantAdministration`'s
containment loop (`:76-90`) already requires `actor.hasPermission(permission)` and
`KnownPermissions.isDelegable(permission)` for every widening change, so the actor
can only pin a permission it already effectively holds and could already confer on
any other subject. Effective authority of every principal is unchanged immediately
after the write.

The escalation is **temporal**: it moves the actor's authority onto a source the
revoking administrator will not look at. Concretely, both of these work today:

- Actor holds `records.write` as a DIRECT grant and is a member of `group.editors`.
  Actor widens `group.editors` with `records.write`. An administrator later revokes
  the direct grant through `/admin/users/<actor>` - the grants editor shows the
  permission gone - and the actor still holds it via the role.
- Actor is a member of `group.a` (which grants `records.write`) and `group.b`.
  Actor widens `group.b` with `records.write`. Revoking the `group.a` membership no
  longer removes it.

(A same-group pin is a no-op: pinning into the very group the permission came from
dies with that membership. The vector needs two distinct sources, which is trivially
arranged.)

So the naive framing "cannot widen your own effective authority" is **too weak** -
it is precisely the framing under which pinning is invisible, which is how the role
path slipped through. And "cannot edit a group you are a member of" is **too
strong** - it would also block NARROWING, contradicting the deliberate relaxation at
`GrantAdministration.java:69-75` (narrowing needs only the boundary permission) and
freezing any role whose members include every grant administrator.

**The correct invariant** is the generalization of the rule that is already shipped:

> An actor may not perform a grant diff that WIDENS the authority of any subject its
> own effective authority flows through - i.e. any subject in
> `expandSubjects("user", actor.principalId())`.

The currently-enforced rule is the degenerate case of this: `expandSubjects(...)[0]`
is always `Subject("user", actorId)`. The generalization is not a new policy, it is
the same policy applied to the whole expansion instead of its first element. It
states the property that actually matters: *the actor must not be able to make its
own authority depend on a subject whose grants it is writing*.

### Does refusing member-actor role edits lock out realistic setups?

It would, if applied bluntly. The failure mode: an install whose administrators are
members of an `admins` role rather than holding a direct wildcard. Under a blanket
refusal nobody could ever widen that role again.

Two properties keep the proposed invariant usable:

1. **Narrowing stays allowed.** Only widening changes are refused, matching the
   existing asymmetry. Cleanup and revocation of a role you belong to still work.
2. **Root keeps its bypass.** `GrantAdministration.java:67` already computes
   `boolean root = actor.hasPermission(WILDCARD)` and `:77` lets root skip
   containment entirely. The membership rule must skip for root too. Rationale: a
   `*` holder's authority is unconditional, so pinning into a role it belongs to
   confers nothing it does not already hold from every angle; and the
   frozen-admin-role scenario is exactly the case where the editors ARE wildcard
   holders. The `/setup` bootstrap (`AuthHandlers.java:108-114`) always mints a
   DIRECT `*` user, so an install always has a root actor able to widen any role.

Honest residual: exempting root leaves root-level pinning possible (a `*`-via-role
actor pinning into a second role). That is inside the trust model the code already
declares - only a root holder may confer non-delegable permissions
(`GrantAdministration.java:35-36, 77, 87-89`) - and the alternative is the lockout
described above. The B1 threat model is the LIMITED admin; that is what this closes.

The existing absolute self-USER refusal (`:63-65`, both directions, root included)
should be left exactly as it is - it is shipped, tested and asserted over HTTP.

### The exact fix, as an extension of the existing mechanism

In `GrantAdministration.requireAuthorizedDiff`, between the change computation
(`:58-61`) and the widening loop (`:76-90`):

- Keep `:63-65` unchanged.
- After `boolean root = actor.hasPermission(WILDCARD);` (`:67`), compute
  `boolean ownAuthority = !root && flowsThroughActor(actor, subjectType, subjectId);`
  where the helper resolves `actor.principalId()`, calls the uncached subject walk
  (`PermissionResolver.walk("user", actorId).subjects()`, same package) and matches
  `Subject.type()`/`Subject.id()` against `subjectType`/`subjectId`. Anonymous /
  null `principalId()` -> false (the boundary check at `:54-56` has already refused
  that actor).
- Inside the widening loop, before the containment check:
  `if (ownAuthority) throw refusal(entryName, mc("refused", "grant_self_group"));`
  (or reuse the existing `grant_self` target if a new microcopy key is unwanted;
  a distinct target reads better in the form and matches the
  short-key-plus-filters rule).

No new class, no parallel API: one helper on the class that already owns the policy,
using the group-membership walk that CLAUDE.md already names as THE shared expansion
mechanism.

### Counterfactual test that fails today and passes after

New journey in
`zenit-auth/src/test/java/be/elevenways/zenit/auth/server/AuthGrantAdministrationTest.java`,
`aGrantAdminCannotPinItsOwnAuthorityIntoARoleItBelongsTo`:

1. `pinnerId` = user with `auth.admin.access`, `auth.users.edit`,
   `auth.grants.manage`, a DIRECT `records.write`=true grant, and
   `group.editors`=true; `roleId = createRole("editors")`.
2. **Step that fails today:** `this.roles.updateRow(roleRow(roleId),
   roleValues("editors", entry("records.write", true)), actor(pinnerId))` must throw
   `Violations` with `path() == "grants"`, and
   `assertNull(grantValue("group", roleId, "records.write"))`.
   At HEAD this call SUCCEEDS and the row is written - that is the observed
   pre-fix failure.
3. Narrowing regression pin: with `grant("group", roleId, "records.read", true)`
   pre-seeded, the same member-actor submitting `roleValues("editors")` (empty
   grants) must still SUCCEED and the row must be gone.
4. Root regression pin: `actor(this.rootId)` made a member via
   `grant("user", rootId, "group.editors", true)` must still be able to widen the
   role.
5. Non-member regression pin: a limited grant admin NOT in `group.editors` must
   still be able to widen it with a permission it holds.

Steps 3-5 are what prove the fix is the right invariant and not the blunt one.

### Every consumer the fix could break

- `AuthRolesResource.applyGrantDiff` (`:224-235`) - the intended behaviour change.
- `AuthUsersResource.applyGrantDiff` (`:269-280`) - unchanged in practice: the only
  way a user subject enters the actor's own expansion is `subjectId ==
  principalId()`, already refused at `:63-65`.
- `AuthGrantAdministrationTest` - existing tests unaffected. The role diffs at `:181-185`
  (actor `editorId` lacks the boundary, refused earlier) and `:266-279` (actor
  `memberId` IS a member of `group.admins`, but both are DELETE / emptying, i.e.
  narrowing, and are expected to be refused by `AdministratorGuard` anyway) keep
  their current outcomes.
- `AuthCmsResourcesIntegrationTest` - its admin holds a direct `*`
  (`:117 grant("user", userId("admin@example.com"), "*", true)`) and is a member of
  no role, so it is root-exempt AND non-member. `rolesJourneyCreateEditGrantsDelete
  CleansMemberships` (`:373`) is unaffected. The limited-admin journey (`:542-606`)
  only edits USER subjects.
- orcono `OrconoAuthFoundationTest` / `OrconoBrowserTestBase` and hohenheim
  `ManagePanelTest` / `CapabilityWalkTest` call `GrantService` directly and never
  reach `GrantAdministration`. Unaffected.
- Cost: one extra subject walk per grant diff (a handful of indexed queries), on an
  admin form submit. Negligible.

### Confidence: HIGH on the defect and on the invariant; MEDIUM-HIGH on the root
### exemption

The defect is a literal reading of `:63` against `AuthRolesResource:232` plus the
class's own AIDEV-NOTE, and the missing test is verifiable by inspection. The root
exemption is a judgement call trading a root-level pinning residue for avoiding a
frozen-role lockout; it is consistent with the line the file already draws at `:67`
and `:77`, but it is the one part an owner may want to decide differently (the
alternative - no exemption - is defensible if every install is guaranteed a
direct-wildcard user, which `/setup` does in fact guarantee at creation time but not
forever).

---

## Summary table

| Finding | Verdict | Core reason |
| --- | --- | --- |
| F2 | FALSIFIED | Both production callers are guarded (`AuthGrantsBinding.applyDiff` under `AdministratorGuard.enforce` at `AuthUsersResource:185,195` / `AuthRolesResource:188,198`; `AutoProvisioningSink:77` writes only `true` on a new user). The proposed guard placement would miss the delete half of a diff and produce false refusals on ordered diffs. |
| F3 | REAL | `GrantAdministration.java:63` gates on `"user"` only; `AuthRolesResource.java:232` calls the same policy with `"group"` and never consults membership. Correct invariant: refuse WIDENING of any subject in `expandSubjects("user", actorId)`, root exempt, narrowing still allowed. |
