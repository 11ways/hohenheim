# Phase 0.A red-team manifest

The artifact the Phase 0.A gate in `instance-tier-plan.md` requires: one row per
boundary, naming the test that closes it, the command that runs it, the commit
each repo was at when it was verified, and the OBSERVED failure text from
defeating the fix.

Written 2026-07-29. Every counterfactual below was observed by reverting the fix,
running the test, capturing the text, restoring, and confirming green. A row
without an observed counterfactual is not evidence -- five of the nine gates in
the previous arc were reported green while the defect was still reachable,
because the tests asserted paths the defect did not travel.

**This manifest covers 0.A (code) only.** 0.B (live-install checksum stamping,
historical secret remediation) is a separate rollout half, gated on open
decisions 1 and 13, and is NOT recorded here.

> **Corrected 2026-08-01.** Every hash below moved. The counterfactuals in the
> gate rows were observed against the code named in the "0.A" column and remain
> the record for THAT code; they were not all re-observed at the current hashes.
> Read the 2026-08-01 section at the end before treating any row as current
> evidence. Nothing in the original text has been deleted.

## Tested commits

Original table (2026-07-29), preserved, plus what those repos are at now. A
moved hash means the row's counterfactual was observed against different code.

| Repo | 0.A (2026-07-29) | Remediation baseline (2026-07-31) | Now (2026-08-01) | Commits since 0.A | Of those, from the remediation |
| --- | --- | --- | --- | --- | --- |
| zenit | `3ca4a7b` | `d721844` | `8b6a60b` | 25 | 17 |
| zenit-auth | `cceeb72` | `857fcb3` | `af25fa6` | 9 | 6 |
| zenit-cms | `b3698d5` | `98f4573` | `5408bcd` | 6 | 4 |
| zenit-oidc | `4bd72b6` | `1f005c8` | `1f005c8` | 1 | 0 |
| zenit-a2ui | `bf4bac2` | `332f997` | `332f997` | 1 | 0 |
| zenit-media | `d865358` | `befd2dc` | `ecde56d` | 3 | 2 |
| zenit-forms | `5fd3893` | `9fae394` | `59f6f57` | 3 | 1 |
| zenit-widget | `9db0b60` | `6451aaf` | `7ffe1de` | 2 | 1 |
| zenit-flow | `cb1e436` | `f11b9da` | `03e0914` | 2 | 1 |
| plumage | `83b64dd` | `64e8f14` | `37bde67` | 3 | 2 |
| hohenheim | `acfff9a` | `690ef94` | `f38c8d9` | 13 | 9 |

zenit-oidc and zenit-a2ui moved BETWEEN 2026-07-29 and the remediation baseline,
not during it. The remediation left them untouched.

Repos the remediation changed that the 0.A table never listed:

| Repo | Baseline | Now | Remediation commits |
| --- | --- | --- | --- |
| protoblast | `8b66d50` | `c76381a` | 2 |
| hawkeye | `cd993fa4` | `ab61cb43` | 9 |
| zenit-microcopy | `0334a1d` | `3ab242c` | 1 |
| textum | `f6b360a` | `6ae80d5` | 2 |
| orcono | `c5e4cbe` | `ecd8707` | 2 |
| herald | `2ea85ae` | `ab55f14` | 1 |
| spamservice | `4165126` | `2f43b7c` | 2 |
| proteus | `e60cc0e` | `35f28ae` | 1 |
| quirkyquarters | `b944bbd` | `36ab28c` | 1 |
| thoth | `0d57429` | `4cdb2b6` | 1 |

Untouched by the remediation, still at their pre-remediation HEADs: zenit-ai
`13b5248`, zenit-oidc `1f005c8`, zenit-pages `feb6aa5`, zenit-comms `404cb45`,
zenit-a2ui `332f997`, janeway `fd170c9`, duiventil `9fb69b7`.

Nothing is pushed. `zenit-dev` is the only build/test entry point used.

## Gate rows

### Stored XSS / CSP (boundary 3)

- **Test:** `ProclogProxyIngressTest` (hohenheim, browserTest), plus the retained
  `ProclogRenderingTest`.
- **Command:** `zenit-dev test --browser --class ProclogProxyIngressTest`
- **What it proves:** an ANONYMOUS raw-socket request through a live `ProxyServer`
  listener plants `<script>` in the request path and `<img onerror>` in the
  User-Agent; the payload lands in the stored proclog; the admin log viewer
  renders both as escaped text with zero elements in the log body, no dialogs
  fire, and zero CSP violations are collected.
- **Why it replaced the old shape:** the previous test injected payloads via child
  ENVIRONMENT VARIABLES, so the ingress claim -- that an unauthenticated visitor
  can plant this -- was never exercised.
- **Counterfactual observed:** renderer reverted to `{%= selectedLogText %}` ->
  `[both proxy-planted payloads render as escaped text] Expecting actual:
  "...<div class="hh-proclog-content">` containing the raw payload.
- **Also pinned here:** `hohenheimPanelsCarryTheAdminCspAndBootstrapExactlyOnce`
  (hohenheim's `/admin`, `/admin/sites`, `/manage` carry the CMS CSP, an
  unclaimed public path carries none, exactly one `/_hawkeye/boot.js`, no inline
  `onload=`). The auth route families are pinned in zenit-auth
  (`authRoutesCarryTheScopedCspAndPublicRoutesDoNot`) and CSP liveness in
  zenit-cms (`CspPanelBrowserTest`); not duplicated.

### Process IPC (boundary 2)

- **Tests:** `WorkloadIdentityTest`, `ReservedEnvJourneyTest`,
  `ChildWrapperIpcJourneyTest`, `IpcChannelChildJourneyTest`, `IpcChannelAuthTest`
  (hohenheim, browserTest).
- **Command:** `zenit-dev test --browser --class WorkloadIdentityTest --class
  ReservedEnvJourneyTest --class ChildWrapperIpcJourneyTest --class
  IpcChannelChildJourneyTest --class IpcChannelAuthTest --no-fail-fast`
- **What it proves:** identity is ENFORCED, not documented -- a uid is claimed
  exclusively (`system_users.site_id`, M044, unique index), a second claimant
  faults and PROVABLY does not spawn, a userless site is refused under
  `process.require_dedicated_user`, and a tenant-managed site is refused
  unconditionally regardless of that setting. Reserved control variables are
  stamped LAST so an operator-configured `HOHENHEIM_IPC_TOKEN`/`PORT`/
  `ZENIT_SECURITY_REPORT_TOKEN` cannot replace the generated value. A real
  one-shot child's `auth` is the observed first line on the wire and its single
  `ready` survives an initial refusal exactly once. Eight pre-auth stalls coexist
  with an attached child.
- **Counterfactuals observed:**
  - identity: `[2. the second claimant faults instead of spawning] Expecting
    actual: NodeSiteType$NodeProcessHandler@... to be an instance of:
    FaultedSiteHandler` -- note this asserts NO SPAWN, not merely a 503.
  - wrapper: `[2. the one-shot ready arrives exactly once after the initial
    refusals] expected: 1L but was: 0L`.
  - reserved env: `Timed out waiting for: 2. the child authenticated with the
    generated IPC pair`.
  - channel contract: type check removed -> `java.net.SocketTimeoutException:
    Read timed out` (the parent kept a token-smuggling first line attached).
- **Known residual:** auto-provisioning of system users is OUT of scope (a host
  mutation, and Jelle's decision). The operator runbook is in the 0.2 report:
  create one system user per managed-process site, select it, then enable
  `process.require_dedicated_user` -- the settings gate refuses to flip while any
  site would fault and lists the offenders.

### API-key authority (boundary 5)

- **Tests:** `AuthFlowIntegrationTest` (zenit-auth), `CsrfMiddlewareTest` +
  `TrustedProxiesTest` (zenit), `OidcEndpointDeclarationTest` (zenit-oidc),
  `A2uiEndpointDeclarationTest` (zenit-a2ui).
- **Command:** `zenit-dev test --unit --class AuthFlowIntegrationTest
  --no-fail-fast` (zenit-auth); `zenit-dev test --unit --class CsrfMiddlewareTest
  --class TrustedProxiesTest --no-fail-fast` (zenit)
- **What it proves:** a `csrfExempt()` endpoint no longer exempts an INTERACTIVE
  session principal -- the exemption now covers only credentials that cannot
  carry a token. The authenticated session cookie's `Secure` attribute comes from
  the one authoritative `Conduit.isEffectivelyHttps()` decision (direct TLS,
  `network.assume_https`, or a proto forwarded by a TRUSTED peer), never from a
  raw header. `rotate()` refuses a non-interactive principal. Blank API-key
  scopes are refused at the service boundary and legacy blank-scope rows are
  inert.
- **Counterfactuals observed:**
  - CSRF narrowing: `step 1: a cross-site interactive POST to an exempt endpoint
    must be refused ==> expected: <403> but was: <200>` -- the pre-fix attack.
  - cookie: `step 3: an untrusted X-Forwarded-Proto must NOT mark the cookie
    Secure -- cookie: zenit_sid=...; SameSite=Lax; Secure; Max-Age=86400`.
  - rotate: `step 2: an API-key rotate must mint no session cookie ==> expected:
    <true> but was: <false>`.
- **The live defect this closed:** `POST /zn/a2ui/{surface}/action` was
  `csrfExempt()` with no login/permission declaration, and QQ's surface handler
  gates on a PERMISSION rather than credential origin -- so a logged-in admin
  visiting a hostile page could have their session drive an agent action
  cross-site. `CsrfMiddleware.isExempt` returned before the Origin check, so the
  exemption disabled two defenses, not one.
- **Non-default declarations:** only zenit-oidc `POST /oidc/auth` and
  `POST /oidc/session/end` carry `PROTOCOL_COOKIE` (the OP session cookie IS the
  protocol). Every other exempt endpoint in both trees authenticates a header
  credential and is safe on the default.

### RecordSource (boundary 4)

- **Tests:** `RecordSourceTest`, `RecordSourceBucketsTest` (zenit);
  `RecordsWidgetCmsWiringTest`, `RecordsWidgetEditorBrowserTest`,
  `ServerOnlySourceSoftNavBrowserTest` (zenit-cms); `MediaHttpTest` (zenit-media);
  `QueryRulesTranslationTest` (zenit-forms); `FlowConditionTest` (zenit-flow).
- **Command:** `zenit-dev test --unit --class RecordSourceTest --class
  RecordSourceBucketsTest --no-fail-fast` (zenit)
- **What it proves:** the guarantee is STRUCTURAL, not per-caller. Rows are
  stamped with a private per-source proof token at materialization, and
  `project`/`item` refuse any row lacking it -- so a row that could not have been
  loaded through the source's scoped queries cannot be translated. Sort fields
  and bucket date fields are validated against the source whitelist IN PROCESS,
  not only at the HTTP layer. The viewer-facing vocabulary seam
  (`viewerVocabularyFor`) gates through the source and fails closed on a null
  viewer; the stored-config seam (`vocabularyFor`) keeps full authority so
  revival of valid stored config still works for a low-permission viewer.
- **Counterfactuals observed:**
  - `step 1: an arbitrary row must not translate ==> Expected
    java.lang.IllegalArgumentException to be thrown, but nothing was thrown.`
  - `step 2: an unoffered sort field must be refused ==> Expected
    java.lang.IllegalArgumentException to be thrown, but nothing was thrown.`
  - `step 2: no variable names leak past a login-required source's gate ==>
    expected: <true> but was: <false>`
  - media (the break this caused, then fixed): `500 ... "Record source
    'zenitmedia:media' refuses to translate a row that was not loaded through
    this source's scoped queries"`.
- **Known residual:** the 2-arg `project`/`item` compatibility shim still exists.
  Every production caller in both trees is on the 3-arg access-aware form; the
  last 2-arg caller is an orcono TEST. Retiring that lets the shim be deleted.

### Published shell (boundary 1/3)

- **Test:** `PublishedEndpointSafetyTest` (plumage, browserTest).
- **Command:** `zenit-dev test --browser --class PublishedEndpointSafetyTest`
- **What it proves:** the published `plumage-server` artifact contains no
  self-registering endpoint and no process spawner; the only terminal endpoint
  left is a browser-test echo fixture. Hohenheim's real terminal route stays
  uniquely owned.
- **Status:** this gate PASSED the fourth audit unchanged and was not reworked.
  Its detector recognizes `java/lang/Process` and `com/pty4j`; a future spawner
  using `Runtime.exec`, JNI or another library would evade it. No such code
  exists.

### Secrets (boundaries 1, 4)

- **Tests:** `SecretRedactionJourneyTest`, `DisplayTitleTest` (zenit);
  `SecretToastTest`, `EnvironmentSecretsTest`, `SiteApiKeyTest`, `DynamicDnsTest`
  (hohenheim); `CmsFlashSecretArgsTest` (zenit-cms).
- **Command:** `zenit-dev test --browser --class EnvironmentSecretsTest --class
  SiteApiKeyTest --class DynamicDnsTest --no-fail-fast` (hohenheim);
  `zenit-dev test --unit --class SecretRedactionJourneyTest --class
  DisplayTitleTest --no-fail-fast` (zenit)
- **What it proves:** a legacy snapshot can no longer reactivate a credential
  (every snapshot key is judged against the CURRENT schema before it is applied,
  at every depth). A restored null cannot wipe a current secret, and a
  discriminator change refuses cross-schema grafting. Unknown historical nested
  keys over-redact on derived surfaces and keep-current on restore -- two
  directions, deliberately not merged. A REAL `DATABASE_PASSWORD` in a real
  site's environment is absent from real revision snapshots and activity deltas.
  A minted secret rides a single-use `SecretDisclosures` handle, so it never
  rests in `auth_sessions.data`. Digest markers validate the complete
  `sha256:` + 64 lowercase hex shape. A secret field can never become a display
  title.
- **Counterfactuals observed:**
  - env maps: `[3. revision 1 must not contain any password plaintext] Expecting
    actual: "...{"environment_variables":{"DATABASE_PASSWORD":
    "pw-v1-hunter2-9f8e7d6c5b4a",...}..." not to contain
    "pw-v1-hunter2-9f8e7d6c5b4a"` -- a real password in a real `zenit_revisions`
    snapshot.
  - one-shot disclosure: durable flash payload read
    `"api_key_minted SUCCESS scope=site +key=hhk_I01DrtF_cb_xzmD63XlPLS9MiKNfRKgc"`
    where a `znsd_` handle was required.
  - legacy restore: `step 2: a field that is secret TODAY is never applied from a
    snapshot that carries it ==> expected: <live-token> but was:
    <legacy-plaintext-token>`.
  - digest shape: `[1. a plaintext key that merely starts with sha256: is NOT a
    digest] Expecting value to be false but was true`.
- **Deliberate tradeoff:** env maps use the `.secret()` shape, so PER-KEY env
  diffs are lost while the fact that env changed stays visible and every other
  settings key still diffs per key. Typed rows (the Phase 3 `instance_variables`
  shape) were rejected for now because no heuristic can safely classify which
  EXISTING values are secret.
- **NOT closed here:** 0.6c at-rest encryption remains an unstarted workstream,
  and historical plaintext already in production `zenit_revisions`/`zenit_activity`
  is open decision 1. The restore path can no longer reactivate those values,
  which is what made "leave and document" unsafe before.

### WebSocket (boundaries 1, 4)

- **Tests:** `WebSocketRevalidationHttpTest`, `WebSocketAdmissionHttpTest`,
  `WebSocketTransportLimitsHttpTest` (zenit).
- **Command:** `zenit-dev test --unit --class WebSocketRevalidationHttpTest
  --class WebSocketAdmissionHttpTest --no-fail-fast`
- **What it proves:** a receive error now releases the SAME resources a normal
  close does -- one shared teardown (CAS-guarded, runs exactly once) stops the
  revalidator, notifies the handler (`onError` then always `onClose`, the hook
  hohenheim detaches its process-log listener from), and closes the transport.
  One revalidation tick costs exactly one identity check plus one permission
  check per declared permission, and both counters freeze after close.
- **Counterfactual observed:** `[step 4: a receive error must notify onError AND
  onClose, in that order] Expected size: 2 but was: 1 in: ["error:IOException"]`.
- **Race found and fixed while doing it:** the naive error-path close clobbered
  the transport limiter's 1009 with a 1011; caught by the pre-existing
  `fragmentedMessageIsLimitedAcrossAllFragments` (`expected: 1009 but was: 1011`)
  and fixed by yielding to an already-initiated intentional close.
- **NOT closed here:** the real "log out and watch a live hohenheim terminal
  close 1008" integration journey, and the datastore-level query-count pin for
  one real terminal tick (~6 queries). The SPI-invocation budget IS pinned; the
  datastore budget needs a zenit-auth test.

### Grants (boundary 4/6)

- **Tests:** `InsertIfAbsentTest`, `DuplicateKeyTest` (zenit); `RecordGrantsTest`,
  `RecordGrantsPostgresTest`, `PermissionResolverTest` (zenit-auth).
- **Command:** `zenit-dev test --unit --class InsertIfAbsentTest --class
  DuplicateKeyTest --no-fail-fast` (zenit); `zenit-dev test --unit --class
  RecordGrantsTest --class PermissionResolverTest --no-fail-fast` (zenit-auth)
- **What it proves:** deny beats allow ALWAYS, including concurrently, on all 8
  backends. `Model.insertIfAbsent` is atomic per dialect and NEVER degrades to an
  UPDATE; `RecordGrants` collapsed from two code paths to one, and the
  row-locking branch plus `grantWithConflictRecovery` are deleted. A negative
  `group.<slug>` membership beats a POSITIVE DUPLICATE of the same tuple in the
  resolver check path.
- **Counterfactuals observed:**
  - primitive: `step 2: exactly one racing caller must return true (left=true,
    right=true)` -- 24/24 failed across all 8 backends.
  - consumer: `step 3: deny beats allow, always: the surviving value must be the
    deny ==> expected: <false> but was: <true>`, with the allow deliberately
    writing LAST behind a barrier placed AFTER the absent read.
  - group fold: `step 2: deny-wins must be order-independent, a later positive
    row must not expand the group ==> expected: <false> but was: <true>`.
- **Why the old test could not fail:** it had no barrier between the absent read
  and the write, and four deny workers that would overwrite any transient allow.
- **Design note worth keeping:** an allow's ONLY write is a creation
  (`applyGrantValue` returns an existing row untouched), so the deny is the sole
  unconditional writer -- that is what makes one atomic primitive sufficient
  instead of a general compare-and-set layer.
- **Backend coverage:** the primitive is parameterized over all 8; the consumer
  sequencing runs on SQLite (genuinely non-locking) and PostgreSQL.
  `TestDatasources` is not published, so widening consumer coverage would mean
  publishing it -- not done for one consumer.
- **Phase 1 carries:** the heal-then-unique migration over
  `(subject_type, subject_id, permission)`. The check-path rule is NOT retired
  when it lands, because Mongo and Couchbase enforce no constraint.

### Route ownership (boundary 4)

- **Test:** `RevisionRestoreTakeoverTest` (hohenheim, browserTest).
- **Command:** `zenit-dev test --browser --class RevisionRestoreTakeoverTest`
- **What it proves:** the enable/route-conflict invariant lives in the SiteModel
  WRITE PIPELINE (a schema before-write hook installed by the discovered
  `HohenheimWriteHooks` module), so form save, toggle, delegated `/manage` save,
  seeds and the framework's generic `RESTORE_REVISION` endpoint all pass through
  exactly one enforcement point. All three resource-layer call sites were
  REMOVED. Staged attack: site A enabled on H, A disabled, B enabled on H, then
  a DELEGATED tenant restores A's enabled revision -- refused, A stays disabled,
  and the hostname keeps exactly one owner. Admin path covered too, plus a
  positive control (once B stands down the same restore succeeds).
- **Counterfactual observed:** invariant not installed -> `[A stays disabled
  after the /manage restore attempt] Expecting value to be false but was true`.
- **Companion fix:** zenit-cms `handleRestoreRevision` caught a thrown
  `Violations` under its generic `RuntimeException` catch and reported a domain
  refusal as a server error. Now surfaces the specific violation
  (`expected: <enable_route_conflict> but was: <restore_failed>`). A sibling
  audit confirmed restore was the ONLY such gap.

## Defects found and fixed that were NOT on the original gate list

- **The Phase 0.1 CSP broke the live terminal.** `STRICT_ADMIN` allowed
  `'wasm-unsafe-eval'` in `script-src`, but ghostty's wasm is a
  `data:application/wasm` URI and FETCHING it is governed by `connect-src`.
  Observed: `Refused to connect ... data:application/wasm ... violates
  connect-src 'self'`. Fixed in zenit (`connect-src 'self' data:`), pinned by a
  plumage test that boots a REAL terminal under the real policy. The vendored
  bundle is generated by a gradle download task, so patching it would be
  silently reverted by a vendor refresh -- recorded beside the policy.
- **`FormSecrets.maskMap` corrupted secret key/value rows on rerender.** A
  validation-failure rerender flattened `{"0":{key,value},...}` into
  `{"0":"", "__marker":""}`, so a blind resubmit REPLACED the stored map. This
  became reachable BECAUSE env maps are now `.secret()`. Fixed in zenit, pinned
  by an endpoint-level round trip through the failing path in zenit-cms.
- **zenit-dev reported green targeted runs as FAILED.** `:zenit-gradle-plugin:test`
  hard-failed any `--class` filter it could not match, and an UP-TO-DATE main
  task then parsed zero events and fell into "no tests matched". Baselines are
  now scope-stamped so a wrong-filter pass can never be re-reported. hohenheim
  (no `src/test`) no longer fails its unit phase.

## Open, and deliberately not closed by 0.A

1. Historical plaintext secrets already in production `zenit_revisions` /
   `zenit_activity` (open decision 1). Restore can no longer reactivate them.
2. Live-install migration checksum stamping and the flip to
   `migration_integrity=fail` (open decision 13). This is 0.B.
3. WebSocket revalidation default-on (open decision 2).
4. `/admin/**` GETs interactive-only (open decision 3).
5. 0.6c at-rest encryption -- a scheduled workstream, not a Phase 0 blocker, but
   no text may claim platform-wide encryption until it lands.
6. `AdminPagesTest.settingsPageRendersSavesResetsAndRefusesInvalidValues` fails
   on the pristine tree (proven by a stash run). Predates this arc; likely
   zenit-cms `c5dc2d7` changed settings-reset semantics.
7. Three defects reported during the arc and not fixed: Records sub-schemas are
   not walked by `FormSecrets` (a secret there would be echoed, not flattened);
   secret `ListField` masks wholesale by design; and `StringMapField` loses key
   order on JSON-column read-back because DRY/JSON parse materializes `HashMap`s.

# 2026-08-01 -- cross-repository remediation, and what it did to the rows above

The ledger `/home/skerit/projects/javaweb/REMEDIATION-2026-07-31.md` (Waves A-G,
57 numbered issues) was worked to completion on 2026-07-31/08-01. It produced
**65 commits across 19 repositories**, none pushed. Its per-issue reports carry
the observed pre-fix failure text and the verification command for each fix.

This section is an APPEND. Nothing above was rewritten. The counterfactuals above
remain the record for the code they were observed against; several of them were
observed against code that no longer exists.

## What was NOT re-run -- read this first

**The nine 0.A gate counterfactuals were not all individually re-observed at the
new hashes.** No agent reverted `ProclogProxyIngressTest`'s renderer, the five
IPC fixes, or the published-shell detector again. Where a gate is still standing
on its 2026-07-29 observation, this section says so in those words. Three
evidence tiers are used below, and they are not interchangeable:

- **(a) fresh counterfactual** -- this remediation observed a NEW pre-fix failure
  in the same boundary, at code that is in the current tree. Strongest.
- **(b) Wave G structural verification** -- the audit re-read the fix and its
  assertions at the current HEADs and recorded file:line evidence that both are
  real. It proves the fix and its test are still there and still assert; it does
  NOT prove the defect is still unreachable by a route nobody wrote a test for.
- **(c) original 2026-07-29 observation** -- unchanged, against older code. If
  the repo's hash moved, the code under the row moved with it.

## Wave G: the ten retained fixes, re-verified INTACT

Report: `reports/wave-g-audit.md`. All ten (G1-G10, including all twelve G10
sub-items) verified at the current HEADs, with file:line evidence per item, and
the audit states the assertions were checked to be REAL rather than merely
present. This is the closest thing to a re-verification of the original gates
that exists, and it is tier (b), not tier (a).

Load-bearing entries for the rows above: G3 (`ScopedCspMiddleware` claims by
`getRoutePath()`), G6 (all 17 generated CMS routes still `requiresInteractiveLogin`),
G8 (ghostty pin, `STRICT_ADMIN` still terminal-free, no runtime script injection),
G9 (WebSocket FIN/RST/wedged-server-close tests present and unweakened -- the audit
checked `git show --stat` and found `9fc2846` ADDED 93 lines to
`WebSocketRevalidationHttpTest` and deleted none), and G10's source-existence
oracle, `Violation.toString()`, `AccessContext.of(Conduit)` and settings-reset
entries.

Two observations the audit raised as NOT regressions, both pre-existing:

1. G9's exactly-once assertions rest on `Thread.sleep(INTERVAL_MS * 4)` settling
   windows. Real assertions, but timing-shaped: a slow box weakens them into
   passing early.
2. G3's claim DERIVATION was rewritten by zenit `9fc2846` (E6), but the locale
   property G3 protects is unchanged and its test grew a companion rather than
   being replaced.

## Gate rows: which underlying code changed, and what each now rests on

### Stored XSS / CSP (boundary 3) -- code changed, counterfactual NOT re-observed

Rests on **(c)** for the proclog ingress claim itself and **(b)** for the policy.

- The CSP CLAIM DERIVATION was rewritten. zenit `9fc2846` (E6) deleted
  `claimsRoute()` and derives claims by running the real route matcher over
  `endpoint.getRoutes(null)`, so a module's policy now covers its locale variants
  and no longer covers foreign routes under its path. Fresh counterfactual:
  `[step 2: a declared locale route variant must be claimed] Expecting value to
  be true but was false` and, the other half, `Expecting value to be false but
  was true` (`reports/e4-e5-e6-e8.md`).
- The hohenheim TERMINAL VARIANT predicate was rewritten. hohenheim `b772041`
  (E7) resolves the registered panel/peer/subpage instead of matching a URL
  suffix. Fresh counterfactual: a 404 under a registered panel was served
  `STRICT_ADMIN_TERMINAL` (`reports/e3-e7-e10.md`).
- The compile-time rules the row's "no inline `onload=`" assertion leans on were
  widened: hawkeye `01c63dec` (F2) makes `attr:onclick` the same
  `inline-event-attribute` ERROR as the plain spelling, and `4d05e77f` (F4) makes
  retired attributes case-insensitive, both with fresh counterfactuals
  (`reports/f1-f4-f11.md`). A workspace sweep found ZERO `attr:on*` occurrences.
- **Not re-verified at this hash:** `ProclogProxyIngressTest`,
  `ProclogRenderingTest`, and `hohenheimPanelsCarryTheAdminCspAndBootstrapExactlyOnce`
  were not individually re-run with the fix reverted. They passed inside the
  hohenheim full browser suite at `dc68e5b` (578 passed, 124 of 124 classes,
  `reports/e3-e7-e10.md`) -- which is a green run, not a counterfactual, and
  predates `b772041` and `f38c8d9`.

### Process IPC (boundary 2) -- code changed underneath, counterfactuals NOT re-observed

Rests on **(c)**.

None of the five IPC fixes were reverted again. But the ground beneath them moved
twice:

- zenit `6125ef1` (C13) replaced `SqliteDatasource`'s shared-connection design
  with dedicated pooled connections (`:memory:` now maps to a private temp FILE;
  WAL, `busy_timeout=5000`, `transaction_mode=IMMEDIATE`). Every hohenheim test,
  including these five, now runs on a different transaction engine.
- hohenheim `dc68e5b` (E10) moved all request-facing wiring into a discovered
  `HohenheimHostWiring` module at MODULES(200) and REMOVED the compensating lines
  the browser harness used to carry. The harness these five classes run under was
  rewritten.

They passed in the post-E10 full suite (578 / 124 classes). The `[2. the second
claimant faults instead of spawning]` counterfactual and its four siblings were
not re-observed. The row's known residual (auto-provisioning of system users is
out of scope) is unchanged.

### API-key authority (boundary 5) -- adjacent hole found and closed; row's own claims NOT re-observed

Rests on **(c)** for the three counterfactuals it lists, plus **(a)** for a
defect in the same tier that the 0.A gate did not reach.

- `CsrfMiddleware` itself was NOT changed. E11 (does the `NON_INTERACTIVE_ONLY`
  exemption keep the Origin check?) was deliberately left as an owner decision --
  see below. So the row's "the exemption now covers only credentials that cannot
  carry a token" claim is unchanged code.
- **Fresh counterfactual, new defect, same boundary:** zenit-auth `af25fa6` +
  zenit `01afdf6` (E1). `AuthorizationMiddleware` read `conduit.getPath()` while
  routing resolved against the stripped route path, so prefixing any URL with a
  configured locale disabled the whole AuthRegistry tier -- login-only baselines,
  permission baselines, the setup gate and public prefixes. Observed pre-fix:
  `step 2: a locale prefix must not walk past the login-only baseline ==>
  expected: <302> but was: <200>` (`reports/e1-e2.md`). `/nl/admin/...` served
  admin pages to an anonymous visitor while `/admin/...` refused them.
- zenit-auth also gained a grant-administration boundary it did not have
  (`auth.grants.manage`, non-delegable) and a last-administrator invariant, both
  with fresh counterfactuals (`reports/b1-b2-b3-b9.md`). MIGRATION IMPACT:
  installs whose admins hold enumerated `auth.*` permissions rather than the
  wildcard lose grant editing until granted `auth.grants.manage`.
- `AuthFlowIntegrationTest` re-ran green post-change (26 passed alongside
  `WebSocketAuthIntegrationTest` and `LocaleAuthorizationHttpTest`,
  `reports/e1-e2.md`). **Not re-verified at this hash:**
  `OidcEndpointDeclarationTest` (zenit-oidc) and `A2uiEndpointDeclarationTest`
  (zenit-a2ui) -- both repos were untouched and neither test appears in any
  remediation report.

### RecordSource (boundary 4) -- substantially rewritten, fresh counterfactuals

Rests on **(a)**, and the row now understates the guarantee.

- zenit `0fd1705` (D1) binds every model-bound facet to the model schema's OWN
  field instance at `build()`. The 0.A row's structural proof token stopped a row
  that came from elsewhere; it did NOT stop a brand-new non-secret `Field` NAMED
  like a secret column, because validation read the passed object's flags while
  `Field.getValue(Row)` reads by name. Observed pre-fix: `step 1: a forged
  same-name projection must not build ==> Expected java.lang.IllegalArgumentException
  to be thrown, but nothing was thrown.` (`reports/d1-d5-d6.md`).
- Same commit (D5): secret primary keys are now ILLEGAL at `Models.registerInstance`;
  `timestamp(field)` is canonicalized and redaction-gated; and a REAL reachable
  leak was found -- the title fallback stringified a `SchemaField` raw, handing
  back the exact secret sub-key `project()` had just stripped. Observed pre-fix:
  `the secret sub-key must never become the display title:
  {nickname=, api_key=sk-live-title-leak} ==> expected: <false> but was: <true>`.
- zenit `8b6a60b` + zenit-cms `5408bcd` + hohenheim `f38c8d9` (F14): an explicit
  source that replaces a CMS-derived default now REPORTS the gates it drops
  (permission / accessCriteria / loginRequired) in both boot orders, and a
  deliberately wider audience must say `override(...)`. hohenheim's manage site
  source is declared such an override.
- **Not re-verified at this hash:** the row's known residual -- the 2-arg
  `project`/`item` shim and the claim that the last 2-arg caller is an orcono
  TEST. orcono moved (`ecd8707`, `36ae266`); nobody re-counted.

### Published shell (boundary 1/3) -- NOT re-verified at this hash

Rests on **(c)**.

plumage moved `83b64dd` -> `37bde67`, including `4a47471`, which rewrote
`BrowserTerminalBridge` (terminal dispose on unmount, loud ghostty failure) and
rewrote the `terminal-test.hwk` fixture, which was found to be DEAD -- a tag
declaration nothing instantiated, so any test pointed at it would trivially pass
(`reports/e9-f6.md`). No remediation report re-runs `PublishedEndpointSafetyTest`.
The row's own stated blind spot (a spawner using `Runtime.exec`, JNI or another
library would evade the detector) is unchanged.

### Secrets (boundaries 1, 4) -- substantially rewritten, fresh counterfactuals

Rests on **(a)** for the form and revision halves, **(c)** for parts of the
hohenheim half.

- zenit `24d40d4` + zenit-cms `5a37138` (D2/D8) rewrote `FormSecrets` as an
  exhaustive pattern switch over the sealed `FormEntry` set, so a new entry kind
  is a COMPILE error rather than a silent fall-through. Observed pre-fix, four
  distinct shapes: `step 1: the en translation survives a keep-blank submit ==>
  expected: <sk-live-en> but was: <>` (a localized secret's stored translations
  were WIPED by a blind resubmit -- data loss), `step 1: the stored sub-value is
  marked, never echoed ==> expected: < __stored_secret__ > but was: <tok-live-42>`,
  `step 1: the list of secret items is emptied ==> expected: <[]> but was:
  <[recovery-1, recovery-2]>`, and `step 1: the row's secret is blanked ==>
  expected: <> but was: <tok-live-7>`.
- zenit `b4822a6` + hohenheim `1a56057` (D3/D4) rewrote revision restore: it is
  now bound to the requested `recordId`, refuses a snapshot whose PK disagrees,
  runs existence-check and guarded update in ONE transaction, and never falls
  back to INSERT. Lifecycle columns are declared by behaviour
  (`ModelBehaviour.lifecycleFieldNames`) and screened out of both snapshot and
  restore. Observed pre-fix: `step 4: restoring a revision must not untrash the
  record ==> expected: not <null>` and `step 2: the record the snapshot NAMES
  must never be written by a restore of another record ==> expected:
  <eight-oh-two> but was: <hijacked>`. Proven on ALL 8 backends (56 tests).
  hohenheim `SiteModel` has NO `SoftDeleteBehaviour` (it hand-stamps
  `deleted_at`), so the framework fix ALONE would not have protected the only
  production consumer; `SCHEMA.addLifecycleField(DELETED_AT)` was wired.
- zenit `4353a29` (D6) makes the `SecretDisclosures` TTL actually bound residency
  (a narrow self-terminating server sweeper). Observed pre-fix: `[1. the TTL must
  bound residency without any further use] expected: 0 but was: 1`.
- `SecretRedactionJourneyTest` CHANGED: `restoringWhenTheCurrentRowCannotBeLoaded`
  now takes a REAL revision from a loaded live row and restores it after the soft
  delete. The first hohenheim version of that test PASSED pre-fix, because the
  CREATE-path revision carries no `deleted_at` key at all -- recorded here because
  it is exactly the failure mode this manifest exists to catch.
- Re-run green during the work: `EnvironmentSecretsTest`, `SecretFieldsTest`,
  `SecretToastTest`, `SecretFieldFormTest`, `SecretsTest` (hohenheim, 6 passed),
  `SecretRedactionJourneyTest`. **Not re-verified at this hash:** `DisplayTitleTest`,
  `CmsFlashSecretArgsTest`, `SiteApiKeyTest` and `DynamicDnsTest` are not named in
  any remediation report.
- Behavioural change to record: new snapshots no longer contain `version`,
  `deleted_at` or publish-state columns, so revision DIFFS stop showing them (old
  snapshots still render theirs). Publish/unpublish and delete remain fully
  visible in the activity log.

### WebSocket (boundaries 1, 4) -- fresh counterfactuals, one residual now half-closed

Rests on **(a)** plus **(b)** for G9.

- zenit `e11d1ae` + `9fc2846` (E2): the row's shared teardown was exactly-once
  but not ALWAYS. A callback-lane overflow marked the connection released and
  then enqueued `handler.onClose` on the lane that had just refused it, so a
  connection dropped for overload (1013) leaked its retained resources for the
  process lifetime. Observed pre-fix: `[step 3: onClose must run even though the
  callback lane overflowed] Expecting value to be true but was false`. Two
  further holes were found while fixing: overflow CLEARS the queue (a release
  queued by an earlier close was discarded), and a throwing `onError` skipped
  `onClose` entirely.
- zenit `9fc2846` (E8): `network.websocket_revalidation_interval_ms = 0` can no
  longer switch revalidation off process-wide. **Setting contract change** -- a
  deployment with the key set to 0 now FAILS settings loading loudly. No app sets
  it (grepped).
- zenit `9fc2846` (E4/E5): contextual route parameters no longer resolve before
  the endpoint's own gate (`[step 1: refusing must cost no database query at all]
  expected: 0 but was: 1`), and cross-origin refusal DIAGNOSTICS are bounded per
  IP without touching the victim's handshake budget.
- hohenheim `e9a0eb7` (E3): the dev tunnel authenticated in-band and then never
  revalidated. It now closes within a tick on token rotation, site disable,
  soft-delete, retype or wildcard-domain withdrawal. Observed pre-fix: the tunnel
  served traffic for a 60.5s window with a ROTATED token. Ownership/grant changes
  do NOT close it (token-to-site auth, no principal) -- stated, not implied.
- The row's residual "the real log out and watch a live hohenheim terminal close
  1008 integration journey" is still NOT closed. plumage `4a47471` (F6) closed
  the browser half only: soft-navigating away now closes the socket and releases
  the server handler exactly once, proven in plumage against a real
  `WebSocketEndpoint`. The datastore-level query-count pin for one real terminal
  tick is also still open.

### Grants (boundary 4/6) -- substantially rewritten, fresh counterfactuals

Rests on **(a)**.

- zenit-auth `0dc57fc` (C3): the row's "deny beats allow ALWAYS" had a hole. An
  already-equal deny returned from its own read snapshot without writing, so a
  concurrent revoke+allow landing after that read left an ALLOW stored while the
  caller was told its deny stood. Observed pre-fix on SQLite and PostgreSQL:
  `step 3: the STORED value must be the deny, not the allow that landed mid-write
  ==> expected: <false> but was: <true>`. The deny is again the sole
  unconditional writer.
- zenit `fc89eaf` (C4): the portable guarantee did not hold on Couchbase.
  `updateAll` used one N1QL UPDATE with no CAS. Observed on live Couchbase 7.6.1:
  50 concurrent increments landed **11**, silently. Now a CAS-guarded KV
  read-modify-write with bounded jittered retries; the guarantee HOLDS on all
  eight and no contract was narrowed. This RETIRES C4 as an owner decision.
- zenit `fc89eaf` (C7): `insertIfAbsent` returned false for ANY unique conflict,
  not just the primary key. Observed pre-fix on all 7 enforcing backends;
  Couchbase passed pre-fix, confirming its documented exemption.
- zenit-auth `0dc57fc` (C5/C6): a grant-store outage no longer latches cleanup
  off forever, grants on hand-soft-deleted records are swept (hohenheim's trashed
  sites survived every sweep and REVIVED on restore), and a separate
  subject-orphan sweep exists.
- zenit-auth `4e458f4` (B4/B5): capability rules are idempotent-or-loud with an
  explicit `overrideRules`; a grant can no longer be planted on a record that
  does not exist. NOTE `declareGrantable` stays deliberately last-wins (a
  liveness predicate is a lambda, not value-comparable) -- flagged, not widened.
- The row's "Phase 1 carries: the heal-then-unique migration" is unchanged and
  **not re-verified**; what landed instead is zenit-auth `M009_PurgeOrphanRecordGrants`
  (C1), because the applied original M007 never ran the new purge. Observed
  pre-fix: `step 5a: the orphan grant must have been purged by the upgrade ==>
  expected: <0> but was: <1>`.

### Route ownership (boundary 4) -- rewritten, fresh counterfactual

Rests on **(a)**.

- hohenheim `a19e1dd` (C14): the write-pipeline invariant the 0.A row proved was
  real, but its uniqueness did not cover OVERLAPPING listener sets. Two writers
  claiming one hostname+path with an all-interfaces row and a single-address row
  could both commit, and the dispatcher silently dropped one tenant's route.
  Observed pre-fix: `[step 5: exactly one stored claim on the contested route --
  an all-interfaces and a single-address claim must never coexist] expected: 1L
  but was: 2L`. Route ownership is now a transactionally SERIALIZED claim
  registry: `SiteModel.save` and `SiteDomainModel.save` DECLARE a write
  transaction, so scan, stamp and write are one unit. `SiteDomainModel.save`
  previously ran with NO transaction at all, and `SiteModel`'s transactionality
  was an undeclared `RevisionableBehaviour` side effect.
  **The guarantee is engine-scoped:** it relies on SQLite's `BEGIN IMMEDIATE`
  single writer; hohenheim refuses non-SQLite at boot (`requireSqlite`).
- The row's companion zenit-cms `handleRestoreRevision` fix was re-pinned in
  passing: D4's first `updateExisting` bypassed the overridable `Model.save`, and
  `ResourcePageEndpointsTest.restoreRefusedByTheModelWritePipelineReportsTheSpecificViolation`
  caught it (`expected: <enable_route_conflict> but was: <restored>`) -- which
  also means hohenheim's route invariant would have been skipped.
- `RevisionRestoreTakeoverTest` re-ran green under D3/D4 (`reports/d3-d4.md`).

## Corrections to the "Open, and deliberately not closed by 0.A" list

- **Item 7 is now two-thirds false.** "Records sub-schemas are not walked by
  `FormSecrets`" is FIXED (D8, zenit `24d40d4` + zenit-cms `5a37138`), with the
  honest caveat that NO production Records-row secret consumer exists anywhere in
  the workspace -- the enumeration was done and the grep returned zero, so the
  mechanism is wired against a test-level consumer and a real browser, and the
  absence is stated rather than papered over. "Secret `ListField` masks wholesale
  by design" is also superseded: a `ListField` whose ITEM field is secret now
  masks, marks, keeps and clears like a secret list. The third clause
  (`StringMapField` loses key order on JSON-column read-back) is UNCHANGED and
  still stands.
- **Item 1 (historical plaintext) is unchanged and still open.** It is
  ledger D7. The affected surfaces were enumerated (`OWNER-DECISIONS.md`):
  `zenit_revisions` carries it only for hohenheim `SiteModel`, the only
  production model with `RevisionableBehaviour`; `zenit_activity` can carry it
  for every model with secret/encrypted fields, and the full list is recorded.
  Four policies were put to the owner (purge / rewrite-in-place / rotate+rewrite /
  accept with a runbook). Nothing was improvised.
- **Item 2 (0.B: live-install checksum stamping, `migration_integrity=fail`) is
  unchanged and still open.** The remediation did not touch it and no
  owner-decision entry was raised for it. See the reconciliation below.
- **Item 5 (0.6c at-rest encryption) is unchanged and still open.** It is ledger
  D9. Facts assembled: encryption today covers only specifically declared
  main-table and table-stored sub-schema fields (hohenheim `StackModel`,
  `StackFileModel`, `StackDeploymentModel` are the whole corpus);
  `refuseEncryptedJsonSubFields` means EVERY JSON-nested secret is
  `.secret()`-only and never encrypted. Scope must be defined before
  implementation. No text may claim platform-wide encryption.
- **Item 6 (`AdminPagesTest.settingsPageRendersSavesResetsAndRefusesInvalidValues`
  fails on the pristine tree) was NOT revisited.** No remediation report mentions
  it. Assume it still fails.
- **Items 3 and 4 are unchanged.** E8 hardened the revalidation interval setting
  (non-positive is now refused loudly) but did not decide open decision 2, and
  G6's audit confirms the 17 generated CMS routes are still interactive-only
  without deciding open decision 3.

### Reconciling the header's "open decisions 1 and 13" with the ledger

- **Open decision 1 = ledger D7** (historical plaintext in `zenit_revisions` /
  `zenit_activity`). The mapping is determinable: the manifest's own open item 1
  and D7 describe the same rows in the same words. STILL OPEN.
- **Open decision 13 = live-install migration checksum stamping and the flip to
  `migration_integrity=fail`.** This has NO counterpart in the remediation's
  owner-decision file; the ledger never raised it. It is unchanged and still
  open. So **0.B's gate is unchanged**, and the header's statement still holds.
- **Ledger D9 (at-rest encryption scope) is NOT one of the two 0.B gates.** It
  maps to the manifest's open item 5 (0.6c), which the header does not name as a
  0.B blocker. Recorded here so the two numbering schemes are not conflated.

### New open owner decisions this remediation raised

These are additions to the list above, not corrections to it. Full facts in
`OWNER-DECISIONS.md`.

- **E11** -- does the `NON_INTERACTIVE_ONLY` CSRF exemption keep the Origin
  check? `CsrfMiddleware.check` still returns before `isCrossOrigin`. The
  consumer inventory was captured (34 exempt endpoints across 9 repos). Strongest
  argument FOR adding Origin: `zenita2ui/action` and the hohenheim `/api/*`
  routes also carry `requiresPermission(hohenheim.admin.access)`, which is
  exactly the ambient-admin-cookie shape Origin would catch. Strongest argument
  AGAINST: zenit-oidc token/userinfo/PAR and their OPTIONS twins are DESIGNED for
  cross-origin browser clients. **This bears directly on the API-key authority
  row above.**
- **B6** -- complete `hohenheim.sites.manage_all` or delete it from the roadmap.
  Proof recorded that merely declaring the rule today would let a holder mint
  `cap:hohenheim:site#manage` keys covering EVERY site while the UI enumerates
  nothing, i.e. strictly worse than the current state.
- **B8** -- hohenheim's `manage` capability is delegable and documented as
  view/edit/operate, but no MACHINE-credential mutation consumer exists.
- Two smaller ones: zenit-cms's `blockedChanges` access check still runs OUTSIDE
  the new restore transaction (D4), and the `resources/` and `references/` trees
  are not git repositories, so F12's documentation fixes are on disk and
  uncommittable.
- **C4 is no longer an owner decision** -- implemented as the ledger's preferred
  outcome and proven on a live container.

## Corrections to "Defects found and fixed that were NOT on the original gate list"

The third bullet (zenit-dev reported green targeted runs as FAILED) is joined by
four more build-integrity defects, all with fresh counterfactuals
(`reports/a1-a10.md`, `a2-a5-a9.md`, `a3-a4-a7-a8.md`, `a6-a11.md`). They matter
here because every "verified" line in this manifest was produced by that
toolchain:

- `zenit-dev build --clean` deleted build output BEFORE taking the per-directory
  Gradle lock. Observed pre-fix: a live test run's generated sources were deleted
  underneath it (`'DELETED', expected 'SURVIVED'`).
- Optional-dependency builds were flat and non-topological, so a change two hops
  away was invisible and the consumer was recorded FRESH.
- A hawkeye compiler change did not repackage the protoblast plugin fat jar, so
  consumers could compile with a stale bundled compiler. Live drift reproduced:
  the plugin jar was 9 hours older than `hawkeye-compile`.
- Stale Hawkeye generated classes survived tag removal and rename -- they stayed
  in the output tree, compiled, were packed into build-cache entries and SHIPPED
  IN PUBLISHED ARTIFACTS. Behaviour with no source. Observed pre-fix: `step 3:
  the removed tag's interface must not survive in generated output ==> expected:
  <false> but was: <true>`.
- TeaVM classpaths carried duplicate common/platform FQNs (textum: 116;
  orcono: 3766; spamservice/thoth/herald: ~3626 each, all from reusing the TeaVM
  plugin's own `teavmClasspath` configuration). The spamservice deployable jar
  held 36 duplicate paths, 31 with divergent bytes; thoth's fat jar had silently
  DROPPED every dependency module's admin microcopy.

A process failure inside the remediation itself is recorded because it is the
same failure mode this manifest exists to name: **zenit did not compile at
`e11d1ae`** (a `@NonNull` on a qualified nested type). The agent that wrote that
commit verified against a stale artifact and reported it green. It was caught by
the next agent's clean build and fixed in `9fc2846`.

## Environmental caveat on the final verification

The final chain build and the broad suites ran on a host whose root filesystem
reached 100% mid-session (since expanded). One browserTest binary results file
was corrupted and had to be regenerated. Treat any single green suite line from
that window as weaker than a targeted run with a recorded counterfactual, which
is the standard this document is written to anyway.

Two further caveats already recorded in the reports: repeated Couchbase
testcontainer failures under full-suite load (`Failed to ensure collection
exists`, `ServerOutOfMemoryException`) that pass in isolation, and a
`ChannelProtocolTest.broadcastReachesEveryOpenLink` timeout that passes on its
own. Neither touches changed code, and neither is a substitute for the targeted
runs.

## Ledger issues with no per-issue report

F13 (Action-state mechanism unconsumed in production) and F14 (explicit
RecordSource registration silently drops CMS-derived facets) were implemented --
zenit `8b6a60b`, zenit-cms `5408bcd`, zenit-widget `7ffe1de`, hohenheim
`f38c8d9` -- but no per-issue report file was written for them. Their commit
messages are the only prose record. Read the diffs, not the subjects.

Nothing is pushed. Eight commits from this remediation have their subject and
body collapsed onto one overlong line (a heredoc newline-loss issue): hawkeye
`c7903b19`, `6c443d84`, `d7a71b35`, `4d05e77f`, `01c63dec`, `9cf4cd41`, and
zenit `2c7d8fb`, `efb7c6e`. All unpushed, so an interactive rebase to split them
is still safe.
