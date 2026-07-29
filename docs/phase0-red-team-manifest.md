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

## Tested commits

| Repo | Commit |
| --- | --- |
| zenit | `3ca4a7b` |
| zenit-auth | `cceeb72` |
| zenit-cms | `b3698d5` |
| zenit-oidc | `4bd72b6` |
| zenit-a2ui | `bf4bac2` |
| zenit-media | `d865358` |
| zenit-forms | `5fd3893` |
| zenit-widget | `9db0b60` |
| zenit-flow | `cb1e436` |
| plumage | `83b64dd` |
| hohenheim | `acfff9a` |

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
