# F3 - role self-pinning fix (zenit-auth)

Commit: zenit-auth e27ba75b947539949eb46fc81ba9d0126e31eec3
("Refuse widening any role the actor's own authority flows through"), on top of
738c7f0 (the locale-fold sweep that landed mid-task). `zenit-dev verify-head`: ok.

## Recorded pre-fix failure (verbatim)

Counterfactual test written FIRST and run against unmodified code
(run 20260801-105338.log, zenit-auth at af25fa6):

```
AuthGrantAdministrationTest.aGrantAdminCannotPinItsOwnAuthorityIntoARoleItBelongsTo()
  step 1: pinning a directly held permission into an own role must be refused ==> Expected be.elevenways.zenit.common.validation.Violations to be thrown, but nothing was thrown.
  org.opentest4j.AssertionFailedError: step 1: pinning a directly held permission into an own role must be refused ==> Expected be.elevenways.zenit.common.validation.Violations to be thrown, but nothing was thrown.
```

i.e. at HEAD the member-actor's widening of its own role SUCCEEDED. Post-fix the
same journey passes (run 20260801-134537.log, 17/17 green).

## The invariant as implemented, and why that exact one

An actor may not WIDEN the authority of any subject in its own membership
expansion (`PermissionResolver.walk("user", actorId).subjects()` - itself plus
every role its authority flows through), unless it holds the root wildcard.

- "cannot widen your own effective authority" is TOO WEAK: containment already
  requires the actor to hold the permission at write time, so pinning is never a
  widening at that instant - it is a TEMPORAL escalation (authority moved onto a
  source the revoking administrator will not look at). Verified against
  GrantAdministration's containment loop (:76-90 pre-edit).
- "cannot edit a group you are a member of" is TOO STRONG: it would block
  narrowing (contradicting the deliberate B1 relaxation) and freeze any role
  containing all the grant admins.
- The shipped self-user refusal (:63-65) is the degenerate first element of the
  walk; the fix generalizes the same policy to the whole expansion. That rule is
  untouched.

Implementation: `GrantAdministration.flowsThroughActor(actor, subjectType,
subjectId)` + `ownAuthority = !root && flowsThroughActor(...)`, refused INSIDE
the widening loop (so narrowing entries never trip it), microcopy target
`refused/grant_self_role`. The walk is the UNCACHED `PermissionResolver.walk`
- I verified the recon's claim and corrected it: `walk` was `private`, not
package-private; I widened it to package-private with a docblock note. The
uncached choice mirrors `decideStoredUser`: a policy decided inside a mutating
transaction must not trust the per-request memo.

## The journey and its four regression pins

`AuthGrantAdministrationTest.aGrantAdminCannotPinItsOwnAuthorityIntoARoleItBelongsTo`
(one behaviour journey, numbered steps, assertion message per step):

1. PRIMARY: member grant-admin pins directly-held `records.write` into
   `group.editors` -> Violations on `"grants"`, no row written. (The recorded
   pre-fix failure.)
2. SECOND VECTOR: permission derived from `group.readers` pinned into
   `group.editors` -> refused, nothing written. Proves the fix catches
   effective (group-derived) authority, not only direct grants - the check is
   on the TARGET subject's membership, independent of where the permission
   comes from.
3. NARROW pin: the same member EMPTIES its own role's grants -> succeeds.
   Proves narrowing is untouched (the refusal sits inside the widening loop).
4. ROOT pin: root made a member of `group.editors` still widens it -> succeeds.
   Proves the wildcard bypass (:67/:77) is preserved - the anti-lockout case.
5. NON-MEMBER pin: a limited grant admin outside the role still widens it with
   a permission it holds -> succeeds. Proves the rule is membership-scoped, not
   a blanket role freeze.

## Breakage scan

- Grep both `/home/skerit/projects/javaweb` and `/home/skerit/projects/hohenext`
  (excluding build/): `GrantAdministration`/`requireAuthorizedDiff` is consumed
  ONLY by AuthUsersResource and AuthRolesResource. `PermissionResolver.walk` /
  `expandSubjects` external consumer: `RecordGrants:526` (cached path,
  untouched). orcono/hohenheim call `GrantService` directly and never reach the
  policy.
- Verified green post-fix (run 20260801-134537.log): AuthGrantAdministrationTest
  (5), AuthCmsResourcesIntegrationTest (9, real HTTP - its admin is direct-`*`
  and role-less, so root-exempt AND non-member), AuthResourceTransactionTest
  Sqlite+Postgres, AdministratorGuardPostgresTest. 17/17 passed.
- AuthUsersResource is unchanged in practice: a user subject enters the actor's
  expansion only as itself, already refused at :63-65.

## The applyGrants question: NOT landed, reasons

The recon's F2 residual (a guarded public `GrantService.applyGrants(...)`) would
ship UNWIRED: every production caller of `createDirectGrant` in both workspaces
is either inside an `AdministratorGuard.enforce` transaction (both CMS diff
paths) or writes `value=true` on a brand-new subject (`AutoProvisioningSink`).
Routing the CMS resources through it would move the guard down a level for zero
behavior change and double-pay the enforce walk; the 12 test fixtures want the
unguarded primitive. Per the workspace rule (no mechanism without a wired
consumer), I did not build it. Instead the residual is now DOCUMENTED at the
trap site: an AIDEV-NOTE on `createDirectGrant` declaring it the unguarded
primitive, requiring callers to wrap their whole unit of work, pointing at
`deleteDirectGrant` as the guarded shape. If a future app needs an unguarded
public write path, that note is where the applyGrants design gets picked up.

## Residual documented, not fixed

Root-level pinning stays possible (a `*`-holder pinning into a role it belongs
to). Exempting root is what prevents the frozen-admin-role lockout, `/setup`
always mints a direct-`*` user, and only root may confer non-delegable
permissions anyway - inside the trust model the class already declares. Recorded
in the `flowsThroughActor` AIDEV-NOTE.

## Files changed (all zenit-auth, commit e27ba75)

- src/server/java/be/elevenways/zenit/auth/server/GrantAdministration.java
  (ownAuthority check + flowsThroughActor + docblock)
- src/server/java/be/elevenways/zenit/auth/server/PermissionResolver.java
  (walk private -> package-private, docblock)
- src/server/java/be/elevenways/zenit/auth/server/GrantService.java
  (AIDEV-NOTE on the unguarded primitive - the F2 residual)
- src/server/resources/META-INF/microcopy/en.json (refused/grant_self_role)
- src/test/java/be/elevenways/zenit/auth/server/AuthGrantAdministrationTest.java
  (the journey)

## Environment note

Mid-task the chain went red under a concurrent locale-fold/BlastCompileGuard
arc; I waited (no competing runs killed) and re-verified from a REAL rebuild on
the fresh chain (protoblast 04e3c9c, zenit 660d671, zenit-auth 738c7f0). The
recompile is behaviorally proven: the identical test class failed pre-fix and
passed post-fix, and `verify-head` confirms HEAD content builds. zenit was NOT
touched.
