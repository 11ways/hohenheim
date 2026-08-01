# Owner decisions required - remediation 2026-07-31

The ledger's Stop Conditions say these are decisions code cannot answer. Facts are
assembled here; NOTHING has been improvised on them.

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

---

## Small decision surfaced by D4 (not in the ledger)

zenit-cms ResourcePageEndpoints:1197 calls RevisionRestoreAccess.blockedChanges(...) at :1184
BEFORE restore(...), outside the new restore transaction. A concurrent edit between that
field-level access check and the restore is still possible. Closing it means moving the access
decision INSIDE the restore transaction - a zenit-cms change that was out of D3/D4 scope.
Low severity (it narrows to a race between an access check and a restore, and the restore
itself is now guarded). Your call whether to schedule it.

Also from D3: new snapshots no longer contain version/deleted_at/publish-state columns, so
revision DIFFS stop showing them (old snapshots still render theirs). Publish/unpublish and
delete remain fully visible in the activity log. Flagged as a deliberate behavioural change.

## Small decision: the resources/ and references/ trees are NOT git repositories

F12 required edits to resources/shortlinker-port/03-port-precedent.md and 07-architecture.md
(they taught deleted APIs and examples that no longer compile). Those edits are saved on disk
but CANNOT be committed: neither the workspace root, resources/, nor references/ is a git
repo. Same situation as alchemy/ and arcana/. Your call whether that documentation tree should
be versioned.

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
