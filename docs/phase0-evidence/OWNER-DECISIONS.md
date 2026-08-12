# Owner decisions required - remediation 2026-07-31

The ledger's Stop Conditions say these are decisions code cannot answer. Facts are
assembled here; NOTHING has been improvised on them.

## VERDICT PASS 2026-08-12

This file had never been verdicted against code since it was written on 2026-07-31,
and it had gone stale in BOTH directions: items silently resolved and items silently
still open. Every item below now carries a dated VERDICT block stating CLOSED, STILL
OPEN or PARTIALLY RESOLVED with file:line evidence. Original prose is untouched;
verdicts supersede, they do not rewrite.

Rules the pass followed: verdicts come from reading CODE, never from another document;
a confirmed STILL OPEN is as valuable as a closure; anything that cannot be settled
without executing something (a test run, a live database, a real host) is labelled as
such instead of guessed. NOTHING was executed during this pass.

Summary:

| Item | Verdict 2026-08-12 |
| --- | --- |
| D7 historical plaintext | STILL OPEN |
| D9 at-rest encryption scope | STILL OPEN, but its factual base was FALSIFIED |
| E11 CSRF-exempt Origin check | PARTIALLY RESOLVED, and its stated tension was FALSE when written |
| B6 `hohenheim.sites.manage_all` | STILL OPEN, blocker MOVED, and one FACT stated in it is FALSE |
| B8 capability-scoped keys | PARTIALLY RESOLVED: instance half superseded, SITE half untouched |
| C4 Couchbase sticky-deny | CLOSED, re-verified against code |
| D4 `blockedChanges` outside the restore transaction | CLOSED, with a named residual |
| D3 lifecycle columns out of snapshots | CONFIRMED, unchanged |
| Manifest item 2 / open decision 13 (NEW here) | PARTIALLY RESOLVED |
| resources/ + references/ not git repos | STILL OPEN |
| Non-ledger follow-ups (5 bullets) | 1 CLOSED, 3 STILL OPEN, 1 unsettleable from source |

---

## D7. Historical plaintext in revisions and activity rows (security rollout blocker)

Write-time redaction is FORWARD-ONLY (ActivityLog.java:79-82 "Existing activity rows are
not rewritten."). Existing rows can still contain plaintext secrets. Backups and copied
databases retain them.

### Concretely affected data

`zenit_revisions` (snapshot column, DRY-stringified). Revisions are opt-in per model, and
the ONLY production model carrying RevisionableBehaviour is hohenheim SiteModel
(SiteModel.java:111-112, keeps 50 revisions). Historically-plaintext fields inside those
snapshots:
- SiteModel.SECURITY_REPORT_TOKEN (.secret())
- SiteModel.SETTINGS JSON -> per-site-type secret sub-fields: JavaSiteType:74,79,
  CommandSiteType:65,70, NodeSiteType:83,90, DockerSiteType:55, DevNamespaceSiteType:37
  (environment variable maps, dyndns/API-key style tokens)
- SiteModel.SOURCE_SETTINGS JSON -> GitSourceSchema:43 webhook_secret,
  GitSourceSchema:59 build_environment_variables

`zenit_activity` (ActivityLog global hooks, so EVERY model with secret/encrypted fields
can appear in historical deltas):
- hohenheim: SiteModel (above), DnsPeerModel.API_KEY:28, DnsPeerModel.TSIG_SECRET:39,
  DnsRecordModel:83 (dyndns token), DnsZoneModel.dnssec_private_key:73,
  NotificationChannelModel.URL:36 (webhook URLs), CertificateModel:59 (private key),
  DatabaseModel:53, AccessListModel:30, SpamserviceInstallationModel.controller_key:41,
  StackModel:79-80, StackFileModel:35, StackDeploymentModel:40,
  ProteusAuthProviderType.ACCESS_KEY:42, HohenheimSettings:155,386,577
- framework/apps: zenit-auth ApiKeyModel.HASH:32, zenit-ai ProviderConfigModel.api_key:39
  + McpServerConfigModel.env:35/headers:42 + ModelProviderLinkModel.extra_headers:47,
  proteus RealmClientModel.API_KEY:31 + client_secret:34, spamservice
  ClientKeyResource:48 + SpamserviceSettings:82,90,98, zenit-comms CommsSettings:34,43,52,87,
  zenit ServerSettings:215,370, quirkyquarters QQSettings + IRC/Telegram type definitions

Also in scope: hohenheim.db itself and any backups.

### Options (ledger requires the choice cover BACKUPS and CREDENTIAL ROTATION, not just
the live database)

1. Purge affected history entirely (simplest, loses audit trail).
2. Rewrite values in place, preserving non-secret history (keeps audit value, more code,
   must handle DRY snapshots + JSON sub-fields).
3. Rotate all exposed credentials AND rewrite/purge history (strongest).
4. Declare an accepted retention risk with a documented runbook.

RECOMMENDATION IF YOU WANT ONE: option 3 for anything that ever touched the public
internet (site API keys, dyndns tokens, webhook secrets, TSIG/DNSSEC keys), option 2 or 4
for the rest. But this is yours to decide.

### VERDICT 2026-08-12: STILL OPEN, and it now matters MORE than it read

Re-verified against code, not against any document. Nothing has softened.

- Redaction is still FORWARD-ONLY. `zenit .../common/orm/activity/ActivityLog.java:80`
  still says, verbatim, "Existing activity rows are not rewritten."
- The revision surface is still live and still SINGULAR. `SiteModel.java:129-130` still
  carries `SCHEMA.addBehaviour(RevisionableBehaviour.create(50))`, and a workspace sweep
  for `RevisionableBehaviour.create` across zenit, zenit-auth, zenit-cms, zenit-pages,
  zenit-ai, zenit-comms, orcono, quirkyquarters, proteus, spamservice, thoth, herald and
  hohenheim returns exactly two files: hohenheim `SiteModel.java` and the behaviour class
  itself. SiteModel remains the ONLY production model with revisions. (The ledger cited
  `SiteModel.java:111-112`; the declaration has since moved to `:129-130`.)
- NO purge migration exists. hohenheim's migration directory runs to
  `M091_TypedDnsRecordData.java` and not one migration mentions `zenit_revisions` or
  `zenit_activity`. `M047_EncryptRecoverableSecrets.java` heals LIVE columns only
  (`TARGETS` at `:37-46`, all nine of them main-table columns); it never touches history.

What CHANGED since 07-31, and it cuts both ways:

- Going FORWARD the bleed has stopped for the top-level fields. Those columns are now
  `.encrypted()` (see the D9 verdict), and `RevisionableBehaviour.java:240-251` excludes
  secret AND encrypted fields from every new snapshot. So the affected set is FROZEN at
  the rows written before those declarations landed; it no longer grows.
- The JSON sub-field half is NOT frozen and cannot be. `Schema.refuseEncryptedJsonSubFields`
  keeps every JSON-nested secret `.secret()`-only, and those ARE still redacted rather than
  encrypted: `JavaSiteType.java:77,82`, `CommandSiteType.java:68,73`, `NodeSiteType.java:83,90`,
  `DockerSiteType.java:79`, `DevNamespaceSiteType.java:40`, `GitSourceSchema.java:64` (webhook
  secret) and `:80` (build environment variables). Redaction covers the DERIVED surface;
  it does not rewrite a row already written.

Why the severity is higher than the 07-31 text conveys: `docs/deploy-starfleet.md`
documents a live PUBLIC install standing since 2026-07-21, whose database therefore
predates redaction. Site API keys, dyndns tokens and TSIG/DNSSEC keys are all on the list
above. This is not a hypothetical retention question about a dev database.

State of the remedy: a purge MECHANISM is queued to be built in a later wave. It will NOT
be run against the live database by any agent. That trigger is Jelle's alone, and option 3
(rotate + purge) for the internet-facing credentials still stands as the recommendation.

CANNOT BE SETTLED FROM SOURCE: what those rows actually contain today. That is a fact about
a running database. Closing D7 requires someone with access to the live install to inspect
and then act.

---

## D9. At-rest encryption scope (owner decision)

Current: encryption protects SPECIFICALLY DECLARED fields only. Structural limits are
already enforced - Schema.java:158 permits .encrypted() only on main-table fields and
table-stored sub-schema fields; refuseEncryptedJsonSubFields means EVERY JSON-nested
secret is .secret()-only and NEVER encrypted.

Encrypted today (whole corpus): hohenheim StackModel:79-80, StackFileModel:35,
StackDeploymentModel:40.

So the unencrypted-at-rest set = every .secret()-only field listed under D7, plus all
sub-schema secrets under site-type and git-source schemas.

Keep separate from redaction: redaction controls DERIVED surfaces, encryption protects
COPIED storage. Scope must be defined before implementation.

### VERDICT 2026-08-12: STILL OPEN, but the sentence "Encrypted today (whole corpus)" above is FALSE

The DECISION is open: nobody has declared what the encryption scope should be. The FACTS
the decision rested on are stale, and badly.

The corpus is not three fields. Counting DECLARATIONS (a raw grep also matches prose in
comments such as `M047_EncryptRecoverableSecrets.java:36`), hohenheim carries 19
`.encrypted()` declarations across 16 models:

`SiteModel.java:109` (security_report_token), `DatabaseModel.java:64`, `StackModel.java:72`,
`StackFileModel.java:35`, `StackServiceModel.java:116`, `StackDeploymentModel.java:40`,
`InstanceFileModel.java:39`, `InstanceTemplateFileModel.java:38`, `InstanceVariableModel.java:69`,
`GitProviderModel.java:69` and `:88`, `DnsZoneModel.java:73` (dnssec_private_key),
`DnsPeerModel.java:28` and `:39`, `CertificateModel.java:59`, `NotificationChannelModel.java:36`,
`ServerModel.java:250` and `:285`, `SpamserviceInstallationModel.java:41`.

`M047_EncryptRecoverableSecrets.java` is the migration that carried existing rows into that
state; its `TARGETS` list (`:37-46`) is explicitly kept in step with the declarations
(`:36`). Outside hohenheim the chain adds 9 declarations in zenit, 3 in zenit-comms and 1
in zenit-oidc.

Consequence for the paragraph above: "the unencrypted-at-rest set = every `.secret()`-only
field listed under D7" no longer holds. Most of the D7 top-level list is now encrypted at
rest. Independently counted for this pass and it matches the manifest's own correction at
`../phase0-red-team-manifest.md:886-913` exactly (19 declarations, 16 models, same file:line
set) -- two independent counts, one answer.

What did NOT change, and is the actual open scope question: encryption still covers only
specifically DECLARED main-table and table-stored sub-schema fields.
`Schema.refuseEncryptedJsonSubFields` still means EVERY JSON-nested secret is
`.secret()`-only and never encrypted, which is why the largest plaintext surface in the
product -- the site types' `environment_variables` maps -- is redaction-only (AIDEV-NOTE at
`NodeSiteType.java:67-80`). Whether that stays the contract is still yours to decide.

The prohibition is unchanged: no text may claim platform-wide encryption.

---

## E11. Does the NON_INTERACTIVE_ONLY CSRF exemption keep the Origin check?

CsrfMiddleware.check returns at :68-70 for an exempt principal BEFORE isCrossOrigin at
:72. Keeping Origin would add defense against cookie-bearing mistakes but could reject
legitimate cross-origin API clients.

Current consumers of the plain .csrfExempt() (NON_INTERACTIVE_ONLY) lane:
- spamservice ApiEndpoints:82-149 (9 endpoints, API key / public health),
  ManageEndpoints:55-123 (management API key)
- thoth ProxyEndpoints:60-67 (/v1 relay, bearer)
- zenit-a2ui A2uiEndpoints:53-65 (zenita2ui/action)
- zenit-comms HubStatusReceiver:35-41, HubEndpoints:46-62 (hub credentials)
- zenit-oidc OidcEndpoints:79-111 (post_token, options_token, post_userinfo,
  options_userinfo, post_par) - client_secret/bearer
- zenit-microcopy MicrocopySyncApi:46-53 (sync token)
- proteus ProteusApiEndpoints:30-41 (realm API key)
- zenit-ai McpHostEndpoints:41-80 (5 MCP endpoints, session/bearer)
- hohenheim HohenheimEndpoints:216-226 (api_sites_deploy), :239-270 (3 DNS API
  endpoints), :283-291 (dyndns_update - GET, so CSRF returns early anyway)

Unaffected (explicit PROTOCOL_COOKIE): zenit-oidc post_authorize:60-62,
post_end_session:124-126.

Tension:
- STRONGEST ARGUMENT FOR adding Origin: zenita2ui/action and the hohenheim /api/*
  routes also carry requiresPermission(hohenheim.admin.access) - an ambient admin session
  cookie is exactly the shape Origin would catch.
- STRONGEST ARGUMENT AGAINST: zenit-oidc token/userinfo/PAR plus their OPTIONS twins are
  DESIGNED for cross-origin browser-based OIDC clients; adding Origin risks breaking real
  clients.

If KEPT as-is: document that every exempt credential must be non-browser-ambient.
If CHANGED: cross-origin API compatibility tests are required.
A per-endpoint opt-in/opt-out is a third option (more machinery, most precise).

### VERDICT 2026-08-12: PARTIALLY RESOLVED, and the "STRONGEST ARGUMENT FOR" above was already FALSE when it was written

The mechanical claim is unchanged and re-verified: `CsrfMiddleware.check` still returns at
`:68-70` for an exempt request, BEFORE `isCrossOrigin` at `:72`. Those line numbers have not
moved.

What the 07-31 text missed is what `isExempt` now does. `CsrfMiddleware.java:133-153`:
a `PROTOCOL_COOKIE` endpoint is exempt outright, but a `NON_INTERACTIVE_ONLY` endpoint is
exempt ONLY when the principal is absent or `!principal.isInteractive()`. A session-cookie
principal is interactive by construction -- `zenit-auth .../model/UserPrincipal.java:24-26`,
"Only ever minted by the session resolver from a logged-in session cookie, so this is THE
interactive principal" -- and `Principal.isInteractive()` defaults to FALSE
(`zenit .../security/Principal.java:29-31`), so an unknown credential type fails closed.

Therefore the ledger's STRONGEST ARGUMENT FOR ("zenita2ui/action and the hohenheim /api/*
routes also carry requiresPermission(hohenheim.admin.access) - an ambient admin session
cookie is exactly the shape Origin would catch") describes a request that is NOT exempt
today. It falls through `isExempt`, meets `isCrossOrigin` at `:72` AND the token check at
`:79-96`. The AIDEV-NOTE at `:144-150` names that exact escalation as the reason the
narrowing exists.

This was not a change made after the ledger: the narrowing landed in zenit `d3a126b`
(2026-07-29 14:57), two days BEFORE this file was written. The E11 tension section was
stale on the day it was authored.

WHAT REMAINS GENUINELY OPEN, and it is much narrower: should a NON-browser-ambient exempt
credential (API key, bearer, hub token, MCP session) also face the Origin check? The
argument AGAINST is unchanged and still real -- `zenit-oidc .../OidcEndpoints.java:81,88,99,
105,111` are five plain `csrfExempt()` token/userinfo/PAR endpoints DESIGNED for
cross-origin browser-based OIDC clients, while `:62` and `:136` are the explicit
`PROTOCOL_COOKIE` twins. hohenheim now declares 21 `csrfExempt()` endpoints in
`HohenheimEndpoints.java` (it declared 5 when this item was written), so the surface grew;
the exposure shape did not.

If KEPT as-is the documentation burden is now smaller than stated: the interactive-cookie
case is already refused in code, so what must be documented is only that an exempt
credential must not be browser-AMBIENT.

---

## B6. Hohenheim type-level manage capability (hohenheim.sites.manage_all)

Full assessment: scratchpad/recon/b6-b8-assessment.md

FACTS:
- `hohenheim.sites.manage_all` appears in exactly ONE place in either workspace:
  hohenheim/docs/instance-tier-plan.md:1121-1124 (a roadmap line). Zero occurrences in
  any .java file, comment, or CLAUDE.md. It is not a registered KnownPermission.
- HohenheimAccess.java:55-66 declares only .gate(hohenheim.manage.access) and
  .admin(hohenheim.admin.access). No .typeLevel(...).
- PROOF a bare type-level allow enumerates nothing: managedSiteIds -> confirmedSiteIds
  (:128-145) takes its CANDIDATE set from RecordGrants.recordIds (grant rows only); the
  capability walk is only a FILTER. No grant rows => empty set. Consequences: the /manage
  panel would be hidden (ManagePanel.java:80), and every list scoped to id -1
  (ManagePanel.java:132-142, ManageDomainResource.java:29-40). So adding the rule alone
  makes canManageSite() true for every site while every UI shows nothing - strictly WORSE
  than today.
- REAL AUTHORIZATION EFFECT of the "unwired rule": ApiKeyService.java:132-135
  short-circuits on rules.typeLevelPermission(), so merely DECLARING it would immediately
  let a holder mint cap:hohenheim:site#manage keys covering EVERY site, with no grant and
  no enumeration fix.
- The "all sites" use case is ALREADY served by hohenheim.admin.access (admin bypass at
  RecordCapabilities.java:50-52). manage_all is only distinguishable from admin if you
  want a principal that manages every site but is NOT a hohenheim admin. Nothing in code,
  tests, or UI requests that today.

OPTIONS:
A. Complete it: register the permission, declare .typeLevel, and rework enumeration to a
   tri-state/unconstrained answer (HohenheimAccess performs ZERO model queries today, by
   design - a full-table id enumeration into IN(...) is unbounded, which is why admin
   returns allowAll instead). 4-6 production files, ~60-100 lines + tests, and it
   introduces a new global blanket-authority permission = a policy decision.
B. Remove from the roadmap: edit that one doc line + two javadoc blocks (~15 lines),
   documenting record-only grants as the final contract. Costs nothing, breaks nothing.

RECOMMENDATION: B, unless you specifically want a non-admin who manages every site.

### VERDICT 2026-08-12: STILL OPEN, the blocker MOVED, and the "REAL AUTHORIZATION EFFECT" bullet above is HALF FALSE

Three separate findings. Read all three; they point in different directions.

**1. The permission still does not exist.** `grep manage_all src/` in hohenheim returns
nothing. `HohenheimAccess.java:184-188` still registers `MANAGE` on `SiteModel` with
`.elevated().asDelegable()` and no `.typeLevel(...)`; the declared rules at `:189-192` are
still only `.gate(ManagePanel.ACCESS)` and `.admin(HohenheimPanel.ACCESS)`. Neither branch
of the 07-31 recommendation has been taken. The only surviving occurrence of the name is the
roadmap line at `docs/instance-tier-plan.md:1410`.

**2. The blocker MOVED: it is no longer "the mechanism does not exist at any layer".**
Option A above says enumeration would have to be "reworked to a tri-state/unconstrained
answer". That rework SHIPPED in the framework today:

- zenit `7823789` adds `RecordCapabilityScope` (`zenit .../common/security/RecordCapabilityScope.java`),
  an ALL / NONE / SET tri-state whose `recordIds()` THROWS on ALL (`:99-107`) precisely so a
  consumer that forgets `isAll()` fails loudly instead of silently denying, plus
  `RecordCapabilities.scope` (`RecordCapabilities.java:84-115`), which evaluates the
  record-INDEPENDENT prefix `decideWholeModel` (`:123-162`) once and only then confirms
  candidates. Gate denial (`:151-154`) still precedes the type-level row (`:156-159`), so the
  ALL answer cannot be reached by a gate-denied holder.
- zenit-auth `1413dfb` feeds both candidate halves from `RecordGrantCapabilityChecker` and
  carries the conduit-less face on `AuthWebSocketAuthenticator`.
- The seams are `AccessContext.capabilityScope` (`AccessContext.java:176`) and
  `WebSocketAuthenticator.capabilityScope` (`WebSocketAuthenticator.java:68`).

hohenheim is NOT wired to it. `HohenheimAccess.grantScope` (`:812-825`) still funnels through
`grantedRecordIds` -> `enumerateGrantedIds` (`:904-909`) -> `confirmedRecordIds` (`:925-945`),
whose candidate set is `RecordGrants.recordIds(...)` -- grant rows only. So B6's remaining
cost is WIRING, not invention: declare the permission, add `.typeLevel(...)`, and move
`grantScope`/`managedSiteIds` onto `capabilityScope` with an `isAll()` branch.

**3. CORRECTION: the "REAL AUTHORIZATION EFFECT" bullet is half false, and the half that is
false is the scary half.**

The bullet claims declaring the permission "would immediately let a holder mint
cap:hohenheim:site#manage keys covering EVERY site". Verified against code, split in two:

- The MINT half is TRUE. `zenit-auth .../ApiKeyService.java:129-135`, `actorHoldsCapability`,
  short-circuits `return true` on `rules.typeLevelPermission()` before ever consulting a
  grant row. A holder could mint the scope with no grant.
- The COVERAGE half is FALSE. Such a key resolves to nothing. An `ApiKeyPrincipal`'s dotted
  authority is the INTERSECTION of owner authority and scopes:
  `PermissionResolver.decide` (`:50-62`) takes the owner decision and then requires
  `WildcardPermissions.decide(permission, node -> scopes.contains(node) ? TRUE : null)` to
  agree. `WildcardPermissions.decide` (`zenit .../security/WildcardPermissions.java:26-56`)
  probes only the exact dotted node and its `a.b.*`, `a.*`, `*` ancestors. A capability scope
  token is `cap:<model>#<name>` (`zenit-auth .../CapabilityScopes.java:25,28,39-49`) -- the
  `cap:` prefix exists specifically so capability scopes "never collide with dotted permission
  scopes" (`:16-17`). `cap:hohenheim:site#manage` therefore equals no probe on that walk by
  construction, `scoped` comes back null, and `decide` returns FALSE. The key does not hold
  `hohenheim.sites.manage_all`, so the type-level row never fires for it, so it holds nothing
  on any site: 404 by id, empty list.

The REAL naive-implementation defect has the OPPOSITE sign -- silent UNDER-report, for an
INTERACTIVE holder. That defect is exactly what the bullet two above it already describes,
re-verified here:

- `canManageSite` (`HohenheimAccess.java:550`) goes through the per-record walk, so the
  type-level row fires and a record page 200s by id.
- Every LIST is empty: `confirmedRecordIds` (`:925-945`) draws candidates from
  `RecordGrants.recordIds`, and a type-level holder has no grant rows.
- `/manage` 403s: `ManagePanel.java:113-122` gates panel access on those enumerated sets
  being non-empty, and `:365-366` returns early on an empty managed set.

So the honest statement of the risk is: naively declaring the permission produces a user who
can reach any site by id while the UI shows them nothing, and a mintable key that does
nothing at all. Both are wrong; neither is the blanket-authority leak the bullet claims. Do
not carry that claim forward.

Both branches (complete it, or delete the roadmap line) remain untaken. The recommendation
is unchanged, but option A is now materially cheaper than the 07-31 estimate.

---

## B8. Hohenheim capability-scoped keys have no MACHINE mutation consumer

Full assessment: scratchpad/recon/b6-b8-assessment.md

FACTS:
- hohenheim's `manage` (HohenheimAccess.java:41,57-61, .elevated().asDelegable()) is the
  ONLY production KnownCapability in the entire workspace, and the only delegable one.
- The claim is NOT simply false: refusedSiteAccess (HohenheimHandlers.java:958-965) really
  does gate deploy/rollback/process start/kill/isolate on the capability for INTERACTIVE
  users. The precise defect is narrower than the ledger states: there is no MACHINE-
  CREDENTIAL mutation consumer.
- Proof the capability scope has zero endpoint reach for an API key: every
  capability-gated endpoint is CSRF-protected with no exemption (session cookie only),
  and the one csrfExempt API mutation (API_SITES_DEPLOY, HohenheimEndpoints.java:216-225)
  requires global hohenheim.admin.access and does NO capability check. A key scoped only
  to cap:hohenheim:site#manage holds no admin permission, so it is refused.
- The WS terminal can never be reached by a key at all (AuthWebSocketAuthenticator reads
  the session cookie only).

OPTIONS:
A. Wire one legitimate capability-gated machine operation. Clean candidate:
   API_SITES_DEPLOY - already csrfExempt, already refuses session principals, already
   rate-limited, and "deploy" is exactly what operate means. Drop the global-admin
   requirement, add refusedSiteAccess in the handler (~5-10 lines, 2 files) + an
   end-to-end test posting with a real znit_ key. Optional companion: scope GET /api/sites
   through managedSiteIds so a scoped key lists only its sites. NOTE this WIDENS who can
   trigger a deploy (any manage-grant holder via a key, not just admins) - your call.
B. Narrow the documented claim (rewrite the two javadoc blocks, drop .asDelegable()).
   FACTUAL COST: removing .asDelegable() deletes the workspace's only production example
   of delegable-capability minting and invalidates CapabilityWalkTest:149-200, the test
   that pins the delegation rule against real vocabulary - it would have to move to a
   synthetic fixture, leaving the mechanism with no real-install coverage.

RECOMMENDATION: A (it is small, and it is the honest reading of "operate"). If you prefer
B, the honest edit is "delegable, and a minted key currently confers read/enumeration
authority only" - keep .asDelegable().

### VERDICT 2026-08-12: PARTIALLY RESOLVED. The INSTANCE half is superseded; the SITE half is untouched

Both halves verified against code. They must not be conflated, and the manifest's blanket
"B8 -- superseded, the premise no longer holds"
(`../phase0-red-team-manifest.md:935-950`) is too broad on its own.

**SUPERSEDED half.** The premise "no MACHINE-CREDENTIAL mutation consumer exists" is dead for
instances. The tenant instance API v1 (`HohenheimEndpoints.java:375-394` for the design note,
`:395` onward for the endpoints) is a capability-walk machine surface: every handler in
`InstanceApi.java` opens with `ApiConduits.requireKey(conduit)` (`:66, :78, :90, :126, :154,
:174, :195, :221, :239` ...) and then MUTATES, with authorization enforced inside
`InstanceService`/`InstanceSnapshots`/`InstanceBackups` rather than by a route permission.
The endpoint comment states the reasoning explicitly (`:383-387`): "No requiresPermission: a
record capability is not a permission, and demanding a type-level one here would either lock
tenants out or hand them everything." The vocabulary those keys narrow to is registered at
`HohenheimAccess.java:258-262` onward under the 2026-08-08 umbrella decision. So the
mechanism is no longer consumer-less and `.asDelegable()` is no longer decorative.

**UNTOUCHED half.** The SITE deploy surface is EXACTLY as B8 described it:

- `HohenheimEndpoints.java:364-373` still declares `API_SITES_DEPLOY` with
  `.requiresPermission(Permission.of("hohenheim.admin.access"))`, `.csrfExempt()` and a rate
  limit. `API_SITES` at `:356-361` likewise requires global admin.
- Its handler (`HohenheimHandlers.java:175-189`) checks only
  `conduit.getAttribute(PRINCIPAL) instanceof ApiKeyPrincipal` and then enqueues the deploy.
  There is NO `refusedSiteAccess` call and no capability check anywhere in it.
- `MANAGE` on `SiteModel` is still `.elevated().asDelegable()`
  (`HohenheimAccess.java:184-188`).

So a key scoped only to `cap:hohenheim:site#manage` still cannot deploy a site it manages,
and only a global hohenheim admin can. Option A as written (drop the global-admin
requirement, add `refusedSiteAccess` in the handler, end-to-end test with a real `znit_` key,
optionally scope `GET /api/sites` through `managedSiteIds`) is unchanged and still owed. It
still WIDENS who may trigger a deploy, so it is still your call.

Option B's factual cost is now GONE: removing `.asDelegable()` would no longer leave the
mechanism without a real-install consumer, because the instance vocabulary provides one. But
B is now also the wrong trade, since it would narrow a claim the instance API has already
made true.

---

## C4. Couchbase sticky-deny contract - RESOLVED, NO DECISION NEEDED

Implemented as the ledger's PREFERRED outcome (option 1): CouchbaseDatasource.updateAll
now selects ids by N1QL then rewrites each document through a CAS-guarded KV
read-modify-write with bounded jittered retries and a loud throw on exhaustion. The
portable guarantee HOLDS. Proven on a live Couchbase 7.6.1 container: pre-fix, 50
concurrent increments landed only 11, silently. No contract was narrowed.

Cost, accepted and documented: bulk criteria updates on Couchbase are materially slower
than the single statement they replaced.

--- original framing, kept for the record ---

CouchbaseDatasource.updateAll uses N1QL UPDATE with NO CAS retry and self-documents
lost-update/conflict behavior (:970-982). zenit-auth promises a portable sticky-deny
guarantee. Ledger's preferred outcome is implementing a Couchbase-safe conditional
CAS/retry. The Wave C agent will attempt that; if it proves infeasible, the fallback is
narrowing the support contract explicitly - that narrowing is an OWNER decision and will
be escalated here rather than decided by an agent.

### VERDICT 2026-08-12: CLOSED, re-verified against code

The in-file RESOLVED claim was checked rather than believed.
`zenit .../server/orm/CouchbaseDatasource.java:989-1011` documents and implements the
id-scan-then-CAS-rewrite shape; `:1046-1097` is the per-document guarded read-modify-write,
bounded by `CAS_UPDATE_ATTEMPTS = 64` (`:97`) with jittered backoff capped at
`CAS_BACKOFF_CEILING_MS = 16` (`:100`), re-checking the criteria against the version the CAS
pins (`:1174-1190`) and throwing loudly on exhaustion (`:1097`). The replace carries the CAS
(`:1084`). No narrowing of the portable guarantee is present anywhere in the file. No owner
decision is outstanding.

---

## Manifest open item 2 / open decision 13. Live-install migration checksum stamping

ADDED 2026-08-12. This item never had an entry here: the ledger raised the settings side but
never the install side, so `../phase0-red-team-manifest.md:730-732` records "no owner-decision
entry was raised for it". It is a decision code cannot answer, so it belongs in this file.

### VERDICT 2026-08-12: PARTIALLY RESOLVED. Do NOT mark it closed on the settings flip

**The SETTINGS half IS shipped.**
`zenit .../server/setting/ServerSettings.java:471-475` declares `migration_integrity` with
`.defaultValue("fail")` and `.allowedValues("off", "warn", "fail")`, and the AIDEV-NOTE
immediately above it (`:465-470`) records why `warn` was rejected: "a checksum that only logs
enforces nothing, and modified after applied means the live schema no longer matches what the
migration says it built. ... Lowering this to warn re-opens the hole, it does not relax
anything." The two declared escape hatches exist and are named there:
`Migration.supersedesChecksums(...)` for a legitimately revised shipped migration, and
`MigrationRunner.acknowledgeMissingMigrationVersions(...)` for a deliberately uninstalled
module's retained history.

**The LIVE-INSTALL half is the genuine owner action, and it CANNOT be established from
source at all.** "Checksum stamping on a live install" is a claim about the rows in a running
database's migration table. There is no file in any repo that can evidence it. A tree that
defaults to `fail` and a live install whose stamped checksums match are two different claims,
and only the first has evidence.

Closing this needs someone with access to the live install to boot it and record the outcome.
Marking the whole item closed on the settings default alone would be exactly the
silent-success shape this project keeps finding: a step that did less than it claimed and
reported success.

---

## Small decision surfaced by D4 (not in the ledger)

zenit-cms ResourcePageEndpoints:1197 calls RevisionRestoreAccess.blockedChanges(...) at :1184
BEFORE restore(...), outside the new restore transaction. A concurrent edit between that
field-level access check and the restore is still possible. Closing it means moving the access
decision INSIDE the restore transaction - a zenit-cms change that was out of D3/D4 scope.
Low severity (it narrows to a race between an access check and a restore, and the restore
itself is now guarded). Your call whether to schedule it.

### VERDICT 2026-08-12: CLOSED in zenit-cms `0433720`, with a NAMED residual, and the severity above was recorded TOO LOW

FIXED 2026-08-12, zenit-cms `0433720` "Decide revision restore inside the restore
transaction". The check and the write now run inside ONE transaction with the record's row
lock taken BEFORE the check:

- `ResourcePageEndpoints.restoreRevisionUnderLock` (`:997-1037`) opens
  `resource.inMutationTransaction`, calls `lockRecordForRestore` FIRST (`:1005`), re-reads
  the record through the resource's scoped contract under that lock (`:1011`), and only then
  calls `RevisionRestoreAccess.blockedChanges(...)` (`:1018-1019`) and
  `revisionable.restore(...)` (`:1028`). Refusals are thrown, not returned
  (`RestoreRefused`, `:1071-1078`), so a refusal structurally leaves nothing written.
- `lockRecordForRestore` (`:1049-1062`) takes `find().withoutFindHooks().where(pk).forUpdate()
  .first()`, mirroring the locked read `RevisionableBehaviour.restore` already performs
  (`RevisionableBehaviour.java:580-582`). restore's own `withTransaction` nests into this
  scope and its `forUpdate` re-acquires a lock this transaction already holds.
- `RevisionRestoreAccess.java:30-35` now carries the contract as an AIDEV-NOTE: `blockedChanges`
  "may only be called from INSIDE the transaction that performs the restore, with the record's
  row lock already taken".

CORRECTION to the severity recorded above. "Low severity (... the restore itself is now
guarded)" reads as though the guard covered the exposure. It did not: that guard only
prevented resurrecting a hard-deleted record. It did nothing for the AUTHORIZATION decision,
and TWO live-state reads move in the window, both named in the fix's own AIDEV-NOTE
(`ResourcePageEndpoints.java:972-986`): the record a record-AWARE `FieldAccess` judges, and
the revision HEAD the target snapshot is diffed against -- a rival save appends one, so a
column that was byte-identical at check time becomes one the restore would rewind. The cited
concrete reach was hohenheim's `access_list_id`.

RESIDUAL, stated because the fix does not claim otherwise. The lock is conditional:
`lockRecordForRestore` returns without locking when
`!model.getResolvedDatasource().supportsRowLocking()` (`:1053-1055`). That default is FALSE
(`zenit .../orm/datasource/Datasource.java:232-235`) and only two datasources override it to
true: `PostgresDatasource.java:222-227` (whose comment notes it also covers CockroachDB, which
is driven through it) and `MySqlDatasource.java:143`. So:

- Postgres / CockroachDB / MySQL: window CLOSED by the row lock.
- SQLite / DuckDB: no row lock. They fall back to engine single-writer serialization, which
  is real -- `serializesWriteTransactions()` is true only on those two
  (`SqliteDatasource.java:899`, `DuckDbDatasource.java:210`) and it is the same exclusion
  `RevisionableBehaviour` relies on there (`RevisionableBehaviour.java:171-178`). hohenheim
  is SQLite-only, so this is hohenheim's lane. The window is NARROWED, not closed by a lock.
- Firebird / MongoDB / Couchbase: NEITHER row locking NOR write serialization. On those the
  cross-transaction window is unchanged. No hohenheim exposure (SQLite-only, pinned by
  `SqliteOnlyDatabaseGuardTest`), but the framework contract should not be read as universal.

NOT SETTLED BY THIS PASS: `ResourcePageEndpointsTest` gained 163 lines in `0433720`. Whether
they pass was not established -- this pass ran nothing.

Also from D3: new snapshots no longer contain version/deleted_at/publish-state columns, so
revision DIFFS stop showing them (old snapshots still render theirs). Publish/unpublish and
delete remain fully visible in the activity log. Flagged as a deliberate behavioural change.

### VERDICT 2026-08-12 (D3 paragraph): CONFIRMED, unchanged

`RevisionableBehaviour.java:244-251` still excludes lifecycle columns from every snapshot
(`Schema.getLifecycleFieldNames` owns the list) and the AIDEV-NOTE there still records both
halves: the exclusion reason ("they are not the record's CONTENT ... replaying that would
undelete a trashed record") and the fact that restore screens them too "because history
written before this rule still holds them". The deliberate behavioural change stands as
described. No decision outstanding.

## Small decision: the resources/ and references/ trees are NOT git repositories

F12 required edits to resources/shortlinker-port/03-port-precedent.md and 07-architecture.md
(they taught deleted APIs and examples that no longer compile). Those edits are saved on disk
but CANNOT be committed: neither the workspace root, resources/, nor references/ is a git
repo. Same situation as alchemy/ and arcana/. Your call whether that documentation tree should
be versioned.

### VERDICT 2026-08-12: STILL OPEN, unchanged

Re-checked directly: `resources/`, `references/`, `alchemy/`, `arcana/` and the workspace root
`/home/skerit/projects/javaweb` all lack a `.git` directory. Nothing has been versioned since
07-31 and no decision has been recorded. This is also loose end 8 of the 2026-08-01 re-review
(`2026-08-01-re-review/ORCHESTRATION.md:315-316`), where it is called a standing owner
decision. It is one decision, not two.

## Non-ledger follow-ups discovered during remediation (not decisions, just news)

- arcana is NOT a git repository: its TeaVM classpath fix (A4) exists only as an
  uncommitted worktree edit.
- Published hawkeye-server / zenit-server / textum-server jars ship Gradle's
  previous-compilation-data.bin at jar root (compile-task outputs packed wholesale).
  Excluded at fat-jar level; the upstream jar specs should be fixed separately.
- hohenheim build.gradle:230,318 still has the INCLUDE + failOnDuplicateEntries=false
  fat-jar pattern that A6 just fixed in spamservice/thoth.
- spamservice/thoth/herald had reused the TeaVM plugin's OWN 'teavmClasspath'
  configuration, carrying the entire server stack into the browser input (3626 duplicate
  FQNs). Fixed to an app-owned teavmInput. These apps had not been built since the A4
  guard landed.
- B1 migration impact: installs whose admins hold enumerated auth.* permissions rather
  than the wildcard lose grant editing until granted auth.grants.manage.

### VERDICT 2026-08-12 (all five bullets)

1. **arcana is NOT a git repository, A4 fix uncommitted -- STILL OPEN.**
   `git status` in `/home/skerit/projects/javaweb/arcana` answers "fatal: not a git
   repository". Whether the A4 worktree edit is still present is a fact about an unversioned
   directory and cannot be evidenced by history; the versioning decision is the same standing
   one recorded above.

2. **Published server jars ship `previous-compilation-data.bin` at jar root -- STILL OPEN
   upstream, and the fat-jar mitigation is CONFIRMED.**
   Inspected the published artifacts directly: `~/.m2/repository/be/elevenways/hawkeye-server`,
   `zenit-server` and `textum-server` each still contain exactly one
   `previous-compilation-data.bin` entry. The fat-jar exclusion is real and generalized --
   `protoblast/protoblast-gradle-plugin/src/main/resources/be/elevenways/protoblast/gradle/assembly.gradle:58-59`
   excludes it by name with a comment naming the cause
   ("build/tmp/compileServerJava/previous-compilation-data.bin at jar root"). The upstream jar
   specs are unfixed. Cosmetic, not a security item.

3. **hohenheim `build.gradle` INCLUDE + `failOnDuplicateEntries=false` fat-jar pattern --
   CLOSED.**
   `build.gradle` contains zero occurrences of `DuplicatesStrategy.INCLUDE`,
   `duplicatesStrategy` or `failOnDuplicateEntries`. The single remaining mention is `:205`,
   documenting that the whole assembly doctrine including `DuplicatesStrategy.FAIL` and the
   zero-duplicate check now comes from the protoblast plugin's `blastServerJar`/`blastTeavmJar`
   lanes, with only hohenheim's own content and assertions left in the file
   (`:210-216` and the app-specific integrity checks at `:224` onward).

4. **spamservice/thoth/herald TeaVM `teavmClasspath` reuse -- CLOSED.**
   The app-owned configuration is in place and documented: `spamservice/build.gradle:124` and
   `herald/build.gradle:109` both carry the "The TeaVM input jar, its teavmInput configuration
   and the Generate* task" note; thoth has no `teavmClasspath` reference at all. No repo
   references the TeaVM plugin's own `teavmClasspath` any more.

5. **B1 migration impact -- SHIPPED in code; the install-side consequence CANNOT be settled
   from source.**
   The permission exists and is enforced: `zenit-auth .../ZenitAuth.java:332` registers
   `describedPermission("auth.grants.manage").nonDelegable()`, `GrantAdministration.java:66`
   gates every widening on it, `AdministratorGuard.java:44` wires it into the lockout
   invariant, and `AuthUsersResource.java:193` / `AuthRolesResource.java:183` bind the grants
   editor behind `FieldAccess.requirePermission`. Whether any real install's admins actually
   lost grant editing is a fact about rows in a running permission store. Nobody can answer it
   by reading source, and this pass ran nothing.
