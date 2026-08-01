## B6 — Hohenheim type-level manage capability

### Facts

**Every mention of `hohenheim.sites.manage_all`** (grep over `/home/skerit/projects/hohenext` and `/home/skerit/projects/javaweb`):

- `/home/skerit/projects/hohenext/hohenheim/docs/instance-tier-plan.md:1121-1124` — the only occurrence anywhere:
  > "HohenheimAccess collapses onto the core SPI (gate = `hohenheim.manage.access`, admin = `hohenheim.admin.access`, type-level = new `hohenheim.sites.manage_all`), SiteAccessPage is DELETED for the generic page…"
- Zero occurrences in any `.java`, comment, or CLAUDE.md. The two test hits are *different* strings and are core-framework fixtures, not hohenheim: `/home/skerit/projects/javaweb/zenit-auth/src/test/java/be/elevenways/zenit/auth/server/RecordCapabilityCheckerTest.java:48` (`test.sites.manage_all`) and `/home/skerit/projects/javaweb/zenit/src/test/java/be/elevenways/zenit/security/RecordCapabilityTruthTableTest.java:42` (`hoh.sites.manage_all`).

**What HohenheimAccess actually declares** — `/home/skerit/projects/hohenext/hohenheim/src/server/java/be/elevenways/hohenheim/server/auth/HohenheimAccess.java:55-66`:
```java
RecordGrantCapabilityChecker.declareRules(SiteModel.MODEL_ID,
    RecordCapabilityRules.create()
        .gate(ManagePanel.ACCESS)          // hohenheim.manage.access
        .admin(HohenheimPanel.ACCESS));    // hohenheim.admin.access
```
No `.typeLevel(...)`, no `.ownedBy(...)`. Permission registration is `ServerMain.java:166-172` — only `hohenheim.admin.access` and `hohenheim.manage.access` exist as `KnownPermission`s; `hohenheim.sites.manage_all` is not registered anywhere.

The walk itself does support the row: `RecordCapabilities.java:66-69` (`/home/skerit/projects/javaweb/zenit/src/common/java/.../security/RecordCapabilities.java`) and the record component at `RecordCapabilityRules.java:25,43-46`.

**Enumeration path — proof a type-level allow enumerates nothing.**
`HohenheimAccess.managedSiteIds(ctx)` (`:108-111`) → `confirmedSiteIds` (`:128-145`):
```java
for (String raw : RecordGrants.recordIds(principal, SiteModel.MODEL_ID, MANAGE)) {
    if (!confirmedByWalk.test(raw)) continue;
    ids.add(Integer.parseInt(raw));
}
```
`RecordGrants.recordIds` (`/home/skerit/projects/javaweb/zenit-auth/src/server/java/.../RecordGrants.java:479-509`) reads `auth_record_grants` rows only. The candidate set is grant rows; the walk is only a *filter*. A principal holding a type-level permission but no grant rows yields an empty candidate set → empty result. The javadoc at `:101-106` states this explicitly ("the walk decides per record and offers no enumeration, so candidates come from the grant store").

Downstream consequences of an empty set (all three special-case `isAdmin` but nothing else):
- `ManagePanel.java:80` — panel eligibility (`ManageEligibilityChecker`) is `!managedSiteIds(...).isEmpty()`; a type-level-only holder would be denied the `/manage` panel entirely.
- `ManagePanel.java:132-142` (`siteScope`) — non-admin with empty ids returns `impossible()` (`ID = -1`), so the SiteModel record source returns nothing.
- `ManageDomainResource.java:29-40` — same shape, `SiteDomainModel.ID.eq(-1)`.

So a bare `.typeLevel(...)` addition would make `canManageSite(ctx, id)` true for every site while the panel stays hidden and every list stays empty — strictly worse than today.

**Already-wired type-level consumer (important):** `ApiKeyService.java:132-135` (`/home/skerit/projects/javaweb/zenit-auth/src/server/java/.../ApiKeyService.java`) short-circuits `actorHoldsCapability` on `rules.typeLevelPermission()`. Declaring the rule would *immediately* let a holder mint `cap:hohenheim:site#manage` API keys covering every site, with no record grant and no enumeration fix. That is a real authorization effect of the "unwired rule".

### Options

**Option A — complete the type-level consumer.** Concretely:
1. `ServerMain.java:166-172` — register `KnownPermission.of("hohenheim.sites.manage_all", …)` + microcopy key (~3 lines + 1 catalog entry).
2. `HohenheimAccess.java:62-65` — add `.typeLevel(Permission.of("hohenheim.sites.manage_all"))` (1 line).
3. `HohenheimAccess` — enumeration cannot stay a `Set<Integer>` contract. Either add a tri-state (`managesAllSites(ctx)` / an "unconstrained" answer) or make `confirmedSiteIds` fall back to a full `SiteModel` id query when the type-level permission holds. Note `HohenheimAccess` today performs **zero** model queries (it imports `SiteModel` only for `MODEL_ID`); a full-table id enumeration into `ID IN (...)` is unbounded and is why the admin path returns `null`/`allowAll()` instead. ~30-50 lines, new method + principal-only variant.
4. Call sites that must learn the new "unconstrained" state: `ManagePanel.java:80`, `ManagePanel.java:130-142`, `ManageDomainResource.java:29-40`. Possibly also `SiteDomainsPage.java:63` and `SiteDeploymentsPage.java:82`, which currently use `isAdmin` as an authority test (owner decision: does manage_all imply those UI powers?).
5. Tests: `CapabilityWalkTest`, `ManagePanelTest`, `SiteAccessControlTest` each need a manage_all principal case; `ApiKeyService` minting behaviour under type-level needs a pin.

Scope: 4-6 production files, roughly 60-100 production lines, plus test work; and it introduces a new global permission that grants blanket site authority — a policy decision, not just plumbing.

**Option B — remove from roadmap.** Edit `instance-tier-plan.md:1121-1124` (delete the "type-level = new `hohenheim.sites.manage_all`" clause, replace with a stated decision that record grants are the final contract) and extend the `HohenheimAccess` class javadoc (`:24-37`) and the `managedSiteIds` javadoc (`:101-106`) to say record-only is deliberate and why (no enumeration seam; admin bypass covers the "all sites" case). Scope: 1 doc file, 2 javadoc blocks, ~15 lines total, no behaviour change.

**Factual finding that constrains the decision:** the "all sites" use case is *already* served by `hohenheim.admin.access` (admin bypass at `RecordCapabilities.java:50-52`, and `isAdmin` unconstrained scoping in all three consumers). `manage_all` would only be distinguishable from admin if the owner wants a principal that manages every site but is not a hohenheim admin. There is no code, test, or UI anywhere requesting that today. Also nothing but the one roadmap line ever named it — so Option B costs nothing and breaks nothing.

---

## B8 — Capability-scoped keys have no mutation consumer

### Facts

**Declaration** — `HohenheimAccess.java:41` (`MANAGE = "manage"`), registered at `:57-61`:
```java
KnownCapabilities.register(SiteModel.MODEL_ID,
    KnownCapability.of(MANAGE)
        .label(Microcopy.of("manage").withFilter("scope", "capability"))
        .elevated()
        .asDelegable());
```
Description claim in the class javadoc, `:25-28`: *"v1 uses a SINGLE capability string (MANAGE) on the `hohenheim:site` model covering view, edit and operate together"*. Delegation claim, `:50-53`: *"The capability VOCABULARY (manage is delegable, so a holder may mint the `cap:hohenheim:site#manage` API-key scope)"*.

This is the **only** `KnownCapability.of(...)` registration in the entire workspace (grep over `/home/skerit/projects/javaweb` + `/home/skerit/projects/hohenext`, excluding tests) — hohenheim's `manage` is the sole production capability and the sole delegable one.

**Every consumer of `manage`:**

| Consumer | file:line | Kind |
|---|---|---|
| `refusedSiteAccess` → `canManageSite(conduit, siteId)` | `HohenheimHandlers.java:958-965` | real endpoint gate (session forms) |
| `ProcessTerminalHandler.onOpen` | `process/ProcessTerminalHandler.java:47-55` | real gate, WebSocket, session-cookie principal only |
| `ProcessTerminalHandler.revalidate` | `:86-91` | real, mid-session re-check |
| `ManagePanel.ManageEligibilityChecker` | `ManagePanel.java:66-87` | panel visibility (read) |
| `ManagePanel.siteScope` / `ManageDomainResource.accessFunction` | `ManagePanel.java:130-142`, `ManageDomainResource.java:26-42` | list scoping (read) |
| `RecordGrants.grant(... MANAGE, true)` | `HohenheimHandlers.java:877-878` | grant write, endpoint itself gated by admin permission |
| `CapabilityWalkTest.realManageScopeMintsForAHolderAndOnlyAHolder` | `browserTest/.../CapabilityWalkTest.java:154-200` | **direct checker call** — `ApiKeyService.create(...)` + `Zenit.getWebSocketAuthenticator().hasCapability(key, …)`; no HTTP request is made with the key |

**Every hohenheim endpoint that mutates a site, and what it requires today:**

| Endpoint | declaration | actual requirement |
|---|---|---|
| `POST /sites/{id}/deploy` (`SITES_DEPLOY`) | `HohenheimEndpoints.java:137-143` | no `requiresPermission`; handler `HohenheimHandlers.java:811-821` calls `refusedSiteAccess` → **capability**. But not `csrfExempt` → `CsrfMiddleware.check` (`zenit/src/server/.../CsrfMiddleware.java:61-101`) requires a session-backed token, so **session cookie only** |
| `POST /sites/{id}/deploy/cancel` | `:145-151` / handler `:823-834` | same |
| `POST /sites/{id}/rollback` | `:153-159` / handler `:836-847` | same |
| `POST /sites/{id}/processes/start` | `:162-167` / handler `:751-761` | same |
| `POST /sites/{id}/processes/{pid}/kill` | `:169-175` / handler `:763-777` | same |
| `POST /sites/{id}/processes/{pid}/isolate` | `:177-183` / handler `:779-793` | same |
| `POST /admin/sites/{id}/access/add` | `:186-192` | `.requiresPermission(Permission.of("hohenheim.admin.access"))` — **global admin** |
| `POST /admin/sites/{id}/access/remove` | `:194-200` | **global admin** |
| `POST /api/sites/{id}/deploy` (`API_SITES_DEPLOY`) | `:216-225` | `.requiresPermission("hohenheim.admin.access")` + `.csrfExempt()`; handler `HohenheimHandlers.java:139-143` additionally refuses any non-`ApiKeyPrincipal`. **No capability check at all** |
| `GET /api/sites` | `:209-214` / handler `:113-137` | global admin; returns **all** sites unscoped |
| `WS /ws/terminal/{siteId}/{pid}` | `:304-313` | `.requiresLogin()` + capability in `onOpen`; `AuthWebSocketAuthenticator.authenticate` (`zenit-auth/.../AuthWebSocketAuthenticator.java:35-56`) reads only the **session cookie** → an API key can never open it |

**Proof the capability scope has zero endpoint reach today:** `PermissionResolver.decide` (`zenit-auth/src/server/.../PermissionResolver.java:50-62`) intersects owner authority with the key's scopes via `WildcardPermissions.decide` over dotted permission nodes; a key scoped only to `cap:hohenheim:site#manage` holds **no** `hohenheim.admin.access`, so it is refused by `API_SITES_DEPLOY`. And every capability-gated endpoint is CSRF-protected with no exemption, so the key cannot reach those either. The capability is therefore reachable only through in-process checker calls.

### Options

**Option A — add one legitimate capability-gated machine operation.** The clean candidate is `API_SITES_DEPLOY`: it is already `csrfExempt()` (safe by the `NON_INTERACTIVE_ONLY` mode — `ApiKeyPrincipal.isInteractive()` returns false, `ApiKeyPrincipal.java:32-35`), already refuses session principals in the handler, already rate-limited, and it does exactly what "operate" means. Change: drop `.requiresPermission(Permission.of("hohenheim.admin.access"))` at `HohenheimEndpoints.java:223` (replace with `.requiresLogin()`-equivalent baseline) and add `if (refusedSiteAccess(conduit, siteId)) return null;` in `HohenheimHandlers.java:139-156`. ~5-10 production lines, 2 files, plus an end-to-end browser test that POSTs with a real `znit_` key. Optional companion: scope `GET /api/sites` (`HohenheimHandlers.java:113-137`) through `ManagePanel.siteScope`/`managedSiteIds` so a scoped key lists only its sites — another ~10 lines. Note this *widens* who can trigger a deploy (any manage-grant holder via a key, not just admins) — that is the owner's call.

**Option B — narrow the documented claim.** Concretely: rewrite `HohenheimAccess.java:24-37` (drop "view, edit and operate" → "view and per-site UI authorization"), rewrite `:46-53` (drop or qualify the delegation sentence), and remove `.asDelegable()` at `:61`.

**Factual finding that makes Option B expensive/wrong:** removing `.asDelegable()` deletes the workspace's only production example of the delegable-capability minting mechanism, and directly invalidates `CapabilityWalkTest.java:149-200` — the test that pins `ApiKeyService`'s delegation rule ("a holder may mint, a non-holder may not, an unregistered capability stays unmintable") against real production vocabulary. That test would have to be deleted or moved onto a synthetic fixture, which means the mechanism ships with no real-install coverage. Narrowing only the *prose* (keeping `asDelegable()`) is possible but leaves the gap the issue names.

Also note the description is not literally wrong today for *interactive* users: `refusedSiteAccess` really does gate deploy/rollback/process-start/kill/isolate on the capability (`HohenheimHandlers.java:958-965`). The precise defect is narrower than "no mutation consumer": **no *machine-credential* mutation consumer**. If the owner picks Option B, the honest edit is "delegable, and a minted key currently confers read/enumeration authority only" rather than removing delegability.

---

## Documentation that would need updating either way

- `/home/skerit/projects/javaweb/CLAUDE.md:87` — capability map row: *"Record-scoped grants / multi-tenancy (`RecordGrants` + `auth_record_grants`: per-(subject, model, record, capability) boolean grants … | hohenheim per-site `"manage"` capability (`HohenheimAccess` funnel, `/manage` panel, site access grants UI)"*. Notably this row lists only panel/UI consumers — it makes **no** claim about capability-gated machine mutation, so B8 either way requires at most an addition, not a correction.
- `/home/skerit/projects/javaweb/CLAUDE.md:86` — *"API keys (hashed `znit_` tokens, scopes narrow the owner, origin `"api"` accountability) | `zenit-auth` … | apps' API endpoints (mark them `csrfExempt()`)"*. Under Option A this gains hohenheim's capability-scoped deploy as its first record-scoped consumer.
- `/home/skerit/projects/hohenext/hohenheim/docs/instance-tier-plan.md:1121-1124` — the only `manage_all` claim; must be edited under B6 Option B (and updated to "done" under Option A).
- `/home/skerit/projects/hohenext/hohenheim/docs/instance-tier-plan.md:1005-1037` — the composition-rules / precedence-table section that describes the optional type-level permission generically; accurate as written, no change needed either way.
- `/home/skerit/projects/hohenext/hohenheim/CLAUDE.md` — contains **no** capability or manage claims at all (grep: zero hits). Nothing to update there.
- `HohenheimAccess.java:24-37, 46-53, 101-106` — the three javadoc blocks carrying every substantive claim in both issues.

## Uncommitted-change note

Neither `/home/skerit/projects/javaweb` nor `/home/skerit/projects/hohenext/hohenheim` is a git repository (`git status` yields nothing), so I could not diff for in-flight edits by the other agent. Everything above is read from the working tree as it stands now; the `zenit-auth` files I cited (`RecordGrants.java`, `ApiKeyService.java`, `PermissionResolver.java`, `ApiKeyPrincipal.java`, `AuthWebSocketAuthenticator.java`) are in the directory being actively edited — re-verify those line numbers before acting.