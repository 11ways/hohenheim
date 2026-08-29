# Phase 2 design: the release surface

Status: DESIGN, 2026-08-23. No code was changed. Line numbers refer to hohenheim
and the javaweb repos at the HEADs of 2026-08-23. This is the product FACE over
the engine phase 0 landed (phase0-design.md section 4.2: "the UI of
flip/rollback/timeline is Phase 2").

Skills read before designing: zenit-cms-resources (peer types, record tabs,
action bands, CmsConfirm), plumage-components (component inventory, log-view
gap below), zenit-forms-editing (no new form mechanism is needed -- every
declaration surface already exists on the instance Settings form).

## 0. What already exists (verified in code -- the design builds on, never beside)

- Release engine: `server/application/ReleaseEngine.java` -- candidate/probe/
  switch/drain/retain/reclaim, every attempt a durable `ReleaseOperationModel`
  row with a timestamped `STEP_LOG` (`step()` :904), `rollback(applicationId)`
  (:357) over `newestRetired` (:862, retention keeps exactly ONE), history
  pruned to `releases.history_per_record` (:946). Statuses and kinds are RICH
  enum values with icon+color declared on the model
  (`ReleaseOperationModel.STATUS` :77-92, `KIND` :65).
- Deploy verbs: `ApplicationDeploys.deploy(applicationId, ref, reason)` (:53,
  checkout + converge + `DeployStatuses` commit statuses + ActivityLog);
  handlers in `SiteControlHandlers.initDeployControl()` (:44-62) behind
  `refusedInstancePower`, redirecting through `ReturnTarget`.
- Builds: `SandboxedBuilds.run` (:70) writes a durable `build_operations` row
  (running -> terminal), captures output in `BuildLog` (bounded, VALUE-redacting,
  truncation marker) and persists it ONLY at `finish` (:180,
  `BuildOperationModel.LOG`). There is NO live tap today -- "streamed logs" in
  phase 0 means the daemon streams INTO BuildLog, not out to a page.
- Existing surface: `InstanceDeploymentsPage` (RecordScopedPage, slug
  `deployments`, `visibleFor` release-managed kinds :56), registered on BOTH
  panels (`InstanceResource.subpages()` :611, `ManageInstanceResource` :128 --
  tenant parity is structural). Template
  `src/common/templates/cms/instance-deployments.hwk`: deploy-now/rollback
  forms, a history table, per-row collapsible build log (rendered from the
  operation STEP_LOG), admin-only webhook card. Known presentation defect: the
  "Trigger" column renders the operation KIND (page :93 puts KIND under
  "reason") and the "Commit" column renders a truncated IMAGE digest (:94) --
  nothing stores the real trigger today.
- Channels: `HohenheimChannels.INSTANCE_STATS` + `InstanceStatsHandler` is the
  in-repo production precedent for zenit's channel layer (declare in common,
  handler factory at MODULES, per-record capability decided in `onOpen` with
  the mid-stream re-check cadence, `ChannelException` = the same refusal for
  missing and forbidden). `HohenheimStatsFunctions.series` is the browser
  precedent: a `@HawkeyeFunction` returning a `MutableRef` seeded with the
  SSR history, followed over `ChannelClient.shared().open(...)` only under
  `Blast.IS_TEAVM`. The console (`InstanceConsoleHandler`) stays a raw
  WebSocket because it feeds ghostty; `InstanceConsoles.subscribe` documents
  the backlog contract this design copies: "replays the session ring, then
  attaches -- no gap, no double".
- Previews: `PreviewDeployments` -- keyed to the APPLICATION, quota-charged
  row (`PreviewQuota`, owner bucket, refuse-by-name at cap, no eviction),
  deterministic hostname `<site-slug>--<ref-slug>.<base>` (:689, 63-char
  digest guard), direct deploy lane + `ReleaseEngine.probe`, one-shot expiry
  via `RecordSchedules.armOnce` (:457), teardown by exact `GeneratedRows`
  attribution (domain + DNS rows), commit statuses via `DeployStatuses`
  (pending/ready/failed/refused). Surfaces: `PreviewDeploymentResource`
  (admin, creatable site+ref manual lane, destroy row action) and
  `ManagePreviewDeploymentResource` (tenant).
- Webhooks: `GitWebhookHandler` (PREFIX :48 `/api/webhooks/git/{applicationId}`),
  signature = authentication, replay ledger `WebhookDeliveries.claim` (:46,
  unique (instance_id, delivery_key), 30-day retention) with `stampAction`
  recording what each delivery caused (`deploy_queued`, `ignored_branch`,
  `preview_queued`, `repository_mismatch`, ...). The automation DECLARATION
  already lives on the application's source settings (`GitSourceSchema`:
  BRANCH :25, AUTO_DEPLOY :29 default true, WEBHOOK_SECRET :31 secret,
  PREVIEWS_ENABLED :35, PREVIEW_BRANCHES :36) and is edited on the instance
  Settings form via the ordinary schemaFrom sub-form -- no new form mechanism.
- Release spec contents: `ApplicationReleases.desiredSettings` (:339) -- image
  (digest-pinned), commit_sha, container_port, health_path,
  environment_variables (operator vars OVER `DatabaseEnvInjection` credentials
  -- the stored map CONTAINS database passwords), volume mounts, cpu/memory
  limits. Stored verbatim on each release instance row; retired rows beyond
  the newest are DESTROYED at reclaim, so historical specs do not survive as
  instance rows.

## 1. Design overview: one tab, four quiet cards

Everything lands on the EXISTING Deploys tab (`InstanceDeploymentsPage`,
peer type `RecordScopedPage<Row>` -- cited from the zenit-cms-resources skill;
it stays a primary tab, `secondaryTab()` false, Overview stays the
`landingSubpage`). No new panel peer, no second UI over the same records
(`ReleaseOperationResource` stays the hidden read-only audit list). The tab is
renamed in presentation only ("Releases"); slug `deployments` stays.

Vertical order and information hierarchy (each card states what it hides):

1. SERVING strip -- what runs now. One line: status dot + "Serving commit
   a1b2c3d4, built 2h ago" + the ONE primary action (Deploy now). Roll back
   lives here as the single secondary action WHEN a retained release exists,
   with a confirmation that NAMES what changes (section 3). Hidden:
   fingerprints, instance ids, image digests (digest behind a tooltip on the
   commit code element).
2. LIVE ACTIVITY card -- rendered ONLY while an operation is in flight (or for
   ~the first render after one finished, see backlog rules). The streaming
   build/step log (section 4). Hidden entirely when idle: an empty terminal is
   clutter.
3. TIMELINE -- one row per release operation, newest first (section 2).
   Hidden: step logs (disclosure per row), spec snapshots (behind Compare),
   owner/spec fingerprints, candidate/retired ids (never shown).
4. AUTOMATION card (admin/manage-write only) -- one sentence of state, the
   webhook coordinates, last delivery + last failure facts, recent deliveries
   behind a disclosure (section 6). Hidden: the full delivery ledger, the
   secret until "reveal" (copy button works without revealing).
5. PREVIEWS card -- ONLY rendered when previews are enabled OR live previews
   exist (section 5). Hidden otherwise; absence, not an empty state.

Calmness rules applied everywhere: one primary action per screen (Deploy now);
destructive and secondary verbs in confirmations/overflow; cards that have
nothing to say do not render; counts and sentences over tables wherever a
table is not the point.

## 2. The release timeline

### 2.1 Data: what a row needs, and the two columns that do not exist yet

A timeline row = one `release_operations` row, showing: when (STARTED_AT,
pl-relative-time), status badge (already declared on the enum), kind badge
(release/rollback), commit (the REAL commit, see below), duration
(DURATION_MS; the operation duration INCLUDES the build -- the spec is
resolved inside the op, ReleaseEngine :270), who/what triggered it, failure
reason when failed, step log behind a disclosure.

Two facts are not stored today and cannot be derived honestly:

- TRIGGER. `ApplicationDeploys.deploy` receives a `reason` string ("manual",
  "webhook", "api", "boot"...) and records it on the ActivityLog, but the
  operation row never sees it. Joining activity rows by time is guesswork.
- COMMIT. The op stores only IMAGE_ID (a digest). The commit lives in the
  candidate instance's settings, and reclaimed instances take it with them.

New columns on `release_operations` (an APPENDED `M0xx_` migration, up() AND
down() -- since 2026-08-29 `InitialMigration` is frozen and checksum-pinned by
MigrationIntegrityTest, see CLAUDE.md):

| column | type | filled by |
| --- | --- | --- |
| `trigger` | STRING (closed enum, see below) | `ReleaseEngine.newOperation` via `DeployAttribution` |
| `triggered_by` | STRING nullable | same; the acting principal's label when a conduit exists, null for webhook/boot |
| `commit_sha` | STRING nullable | `ReleaseEngine` stamps it beside IMAGE_ID at the same writes (:229, :328, :446) from `desired.get("commit_sha")` |
| `build_operation_id` | INTEGER nullable | threaded out of `ApplicationReleases.desiredSettings` (build.buildId() is already in hand :349) |
| `spec_snapshot` | TEXT nullable | the REDACTED spec digest map, section 2.3 |

`trigger` is a closed vocabulary with ONE declaring home:
`ReleaseOperationModel.TRIGGER` as an `EnumField` with members
`manual` / `webhook` / `api` / `boot` / `reload` / `rollback` / `preview`,
each carrying icon+color facets (the rich-enum pattern; unknown/blank renders
secondary -- the statusVariant precedent at InstanceDeploymentsPage :170).
The strings ApplicationDeploys/GitWebhookHandler/PaasApi pass today as free
`reason` text become references to these enum keys -- one edit per caller,
`ReleaseOperationTriggerDriftTest` (below) breaks the build if a caller
invents a new string.

Threading: `ReleaseEngine.newOperation` is reached through
`ApplicationReleases.converge`, five frames from the callers that know the
trigger. Do NOT widen five signatures: add a scoped context holder
`server/application/DeployAttribution` (the `GeneratedRows.as` precedent, same
file layout: a record `Attribution(String trigger, String actor)` + `as(attr,
work)` + `current()`). Callers wrap: `SiteControlHandlers` deploy handler
(manual + conduit principal label), rollback handler (rollback + principal),
`GitWebhookHandler` (webhook, null actor), `PaasApi` (api + key label),
`BootSettle`/routing reload (boot/reload), `PreviewDeployments` does not
create release ops (direct lane) so nothing to do there. Absent context =
trigger `reload` with a one-time slog naming the call stack -- fail visible,
not closed, because refusing a deploy over missing attribution would turn
bookkeeping into an outage.

### 2.2 Rendering

The existing table becomes a quieter list (still `pl-table striped`, the
current shape is fine): Started | Status | Trigger | Commit | Duration, with
the detail row (error + step-log disclosure) unchanged. Changes:

- Trigger cell: enum badge from the new TRIGGER field, with `triggered_by`
  as subtext when present ("manual -- jelle").
- Commit cell: `commit_sha` short form in `<code>`, image digest tooltip.
  Image-sourced applications (no commit) show the pinned `image_ref` tag.
- The RETAINED release's most recent successful op row carries a subtle
  "rollback target" tag (derived: op whose CANDIDATE_INSTANCE_ID ==
  `ReleaseEngine.newestRetired(id)` instance). No per-row rollback buttons up
  and down the list -- the engine can only roll back to the ONE retained
  release, so the verb lives once, on the serving strip.
- Per-row overflow (ghost ellipsis, the cms action-band language): "Compare
  with serving" (section 2.3), "View build log" (jumps to the persisted build
  log when `build_operation_id` is set -- rendered inline in the detail row,
  same disclosure pattern as the step log).

Deliberately NOT shown: owner/spec fingerprints, candidate/retired instance
ids, the operation id (the row is addressed by time), per-step timing.

### 2.3 Spec snapshots and the diff

Retired instances are reclaimed, so a diff between two arbitrary releases
needs the spec captured on the operation row. But the spec CONTAINS secrets
(injected database credentials inside environment_variables). Rule, fail
closed, no denylist heuristics: at snapshot time EVERY environment variable
VALUE is replaced by `sha256:<first 12 hex>` of the value; every other spec
key (image, image_ref, commit_sha, container_port, health_path, command,
memory_limit_mb, cpu_limit, volume mount paths) is stored verbatim -- none of
those is ever a secret, and the env fingerprints still answer
changed/unchanged exactly. Snapshot format: DRY-encoded map (house rule:
prefer DRY over JSON), written by `ReleaseEngine` in the same stamp that
records IMAGE_ID. Home: `server/application/SpecSnapshots`
(`snapshot(Map desired) -> String`, `diff(String a, String b) -> SpecDiff`).

`SpecDiff` is a typed `@HawkeyeClass` record: scalar changes as
(key, oldValue, newValue) for the verbatim keys; variable changes as three
name lists (added, removed, changed) -- names only, never values, because the
stored values are fingerprints anyway. Mount changes as path lists.

UI: "Compare with serving" navigates to the same tab with
`?compare=<opId>` (addressable UI state, the `?_sheet=` stance). The page
renders ONE extra card above the timeline: "Release <time> vs serving" --
scalars as a two-column mini table, variables as three chip runs ("3 changed:
DB_URL, REDIS_URL, FOO"). Rows whose op predates the snapshot column render
the honest empty state ("No snapshot was recorded for this release"). A
`compare` id that is not one of this application's ops: ignored, no card
(never an oracle).

### 2.4 Rollback: the confirmation names what changes

The serving strip's Roll back form keeps riding `use:CmsConfirm.destructive`
(the shipped shape, instance-deployments.hwk :53), but the message becomes a
computed sentence the page assembles server-side from `SpecSnapshots.diff`
over the LIVE serving and retained instances' stored settings (both rows
exist by definition when the button renders):

    rollback_confirm_named = "Serving commit {$current} will be replaced by
    the retained release ({$target}). {$changes}"

where `{$changes}` is itself resolved microcopy: "No configuration changes." /
"3 variables change: DB_URL, REDIS_URL, FOO." / "Image and 2 variables
change." The whole sentence is one `t(...)` with args, so translations own
word order. The button stays one click + one confirm; the engine's gate
(`release_no_rollback_target`, probe refusal keeping the current release
serving) is the enforcement -- the dialog is UX only, exactly like the typed
delete guard.

## 3. Live build log (the channel surface)

### 3.1 Mechanism decision

Use zenit's channel layer (`common/channel`), NOT a new WebSocket endpoint:
`InstanceStatsHandler` is the in-repo template (one multiplexed socket,
per-record capability decided in `onOpen`, revalidation cadence, the same
refusal for absent and forbidden), and `HohenheimStatsFunctions.series` the
client template (SSR seed + `Blast.IS_TEAVM`-guarded follow, zero `<script>`).
The console's raw WS is not reused: it exists for ghostty TTY semantics and
workspace/console streams; a build log is text into template reactivity.
`SyncedRefs` was considered and rejected: it pushes a server-authoritative
VALUE, and re-publishing a growing log string re-ships the whole log per line;
we want append frames plus a snapshot-on-open, which is the plain
handler/link shape.

### 3.2 Server side: the tap that does not exist yet

`BuildLog` is an in-memory StringBuilder, persisted only at finish. New,
inside the existing classes (no parallel log mechanism):

- `BuildLog` gains listeners: `subscribe(Consumer<String>) -> Subscription`
  where subscribe ATOMICALLY (under the BuildLog's monitor) delivers the text
  so far, then attaches -- the `InstanceConsoles.subscribe` contract, "no gap,
  no double". `append`/`line` notify after storing (redaction and the cap
  therefore apply BEFORE anything reaches a viewer -- a subscriber can never
  see a secret the persisted log would not contain).
- `server/build/LiveBuildLogs` (new, ~80 lines): a static registry
  `(forModel, forId) -> (buildId, BuildLog)` that `SandboxedBuilds.run`
  registers right after `start(...)` and unregisters in the existing
  `finally` (:123). Lookup by owner, because the page follows an APPLICATION,
  not a build id it cannot know in advance.
- `server/application/DeployProgress` (new): the per-application event hub
  the channel handler subscribes to. Publishers: `ReleaseEngine.step/
  transition/finish` (one added call inside the existing `step(RecordStamp,
  line)` funnel :908 -- every write already goes through it) and
  `LiveBuildLogs` (build chunks forwarded with their owner key). Frame type
  (typed `@HawkeyeClass` record, DRY over the channel):
  `DeployFrame(String source /* step|build */, String text, String status,
  String failureReason)` -- status non-null only on transition/terminal
  frames.

### 3.3 The channel

`HohenheimChannels.DEPLOY_LOG` (`hohenheim:deploy_log`, `requiresLogin()` --
the per-record VIEW capability is decided in the handler, same reasoning as
the stats channel's docblock). Handler `server/application/DeployLogHandler`
(the InstanceStatsHandler shape, including the 15s revalidation cadence and
`ChannelException("Not permitted")` for absent-or-forbidden):

- `onOpen(openData = applicationId)`: capability walk; then BACKLOG, then
  attach. Backlog rules:
  - An operation in flight (`ReleaseOperationModel` in-flight statuses):
    send its STEP_LOG so far as one `step` frame, then -- when `LiveBuildLogs`
    holds a live log for this application -- the build text so far as one
    `build` frame (the atomic subscribe), then live frames as they happen.
  - Nothing in flight: send the NEWEST operation's terminal frame (status +
    failure reason + step log) and, when its `build_operation_id` resolves,
    the persisted `BuildOperationModel.LOG` as one `build` frame. Then stay
    attached: a deploy triggered while the page is open starts streaming with
    no reload ("build finished before the page opened" and "page open before
    the build" are the same code path).
- Terminal frames carry the final status; `failed` carries FAILURE_REASON
  verbatim (operator diagnostics, deliberately not localized -- the stored
  step/build logs are evidence, same stance the step log already takes).

Reconnect: `ChannelClient` owns the jittered reconnect; on a resumed link the
server handler survives and nothing re-sends; on a re-OPEN after a lost
gateway (server restart), `onOpen` runs again and re-sends the snapshot. The
client therefore treats EVERY snapshot frame as REPLACE-buffer, and append
frames as append -- snapshot frames are marked (`DeployFrame.snapshot`
boolean). `ClientChannelLink.onReopen` re-requests nothing; open semantics do
it.

### 3.4 Client + UI

`HohenheimDeployFunctions.follow(applicationId, seedFrames)` -- the
`InstanceStats.series` shape: returns a `MutableRef<List<DeployFrame>>` (or
two refs: text ref + status ref; implementer's choice, keep it to one
function) seeded with the SSR-rendered backlog so the card paints before any
socket exists; under TeaVM it opens the link and folds frames in (new list
per push, the reactivity-observes-the-ref rule).

Rendering: a new plumage component `pl-log-view` (framework gap, brief 1):
plumage owns anything rendered, and a follow-tail log pane is generic (thoth
request viewer, QQ task output are waiting consumers). Contract: monospace
scroll region, `text` property (String), auto-follow tail UNLESS the user
scrolled up, a quiet "jump to latest" affordance when not following, bounded
by the caller (BuildLog already caps), `aria-live="polite"` off by default
(logs are too chatty for a live region; the STATUS line is the announced
part). Showcase page + axe sweep as the plumage skill requires. Until brief 1
lands, the hohenheim card may ship with the existing `<pre class=
"hh-deploy-log">` and swap to `pl-log-view` in the same wave -- do not block
the tab on the component.

The LIVE ACTIVITY card renders: status line (current op status enum badge +
its label), the log view, nothing else. It exists in the DOM only while
in-flight or when `?op=` explicitly addresses a finished op ("View build log"
row action sets it); otherwise the timeline's per-row disclosures carry
finished logs.

## 4. Preview environments: the product face

The lifecycle is COMPLETE (create/refresh/expiry/teardown/quota/statuses);
what is missing is discoverability from the application record and the
capacity story. No engine changes.

### 4.1 The Previews card on the Releases tab

Rendered only when `previews_enabled` is true OR live previews exist:

- One fact line: "Previews: 2 running -- 3 of 5 slots used" (`PreviewQuota
  .limit()` + the owner bucket's live count; uncapped renders "3 running").
- One compact list of LIVE previews (status running/deploying/failed):
  hostname as an external link (https, opens the preview), ref + PR number
  as subtext, expiry as `pl-relative-time`, status badge. Failed previews
  show LAST_ERROR in the detail line.
- Per-row overflow: "Rebuild" (re-queues via `PreviewDeployments.queue` --
  the manual lane, extends the window), "Destroy now" (destructive confirm,
  same wording as the resource's row action).
- Footer link: "All previews" -> the `PreviewDeploymentResource` list
  (admin) / `ManagePreviewDeploymentResource` (manage), filtered by
  application (`?filter.application_id=<id>` -- the declared filter URL
  shape). ONE list mechanism stays the authority; the card is a scoped
  window onto it, not a second list UI (generated-pages-are-the-floor rule:
  the resource list keeps sort/filter/paging, the card deliberately has
  none).

How a preview is created (unchanged, now discoverable): webhook PR events /
branch patterns (declared on the Settings form), or the manual lane on the
preview resource. The card's empty-but-enabled state is one sentence: "No
previews are running. Open a pull request, or create one from the previews
list." with the list link -- no create form embedded in the card (the
resource's create form is the manual lane; two create surfaces would drift).

What a preview costs: the quota fact line IS the capacity story
(`preview_quota_reached` refusals already name the cap; the card shows the
budget before the refusal happens). Resource limits are inherited from the
application (PreviewDeployments.desiredSettings copies memory/cpu caps), so
one preview costs one release-sized container -- stated in the card's
tooltip on the fact line, not as a paragraph.

### 4.2 Hostname + teardown (verified, displayed, not redesigned)

Hostname: `PreviewDeployments.hostnameFor` (site slug + ref slug + base
domain, collision-proofed). The card shows it as the link; the derivation is
not explained in UI. Teardown: PR close/merge, branch delete, expiry
schedule, operator destroy, application/site delete cascades -- the card's
expiry column plus the destroy action surface all of it that an operator
needs; the rest stays engine behaviour.

## 5. Automation / auto-deploy UX

### 5.1 Where the declaration lives (unchanged, stated)

"Branch X of this repo deploys to this application" is ALREADY one
declaration: the application instance's source settings (GitSourceSchema
BRANCH + AUTO_DEPLOY on the Settings form's schemaFrom sub-form). The
automation card does NOT grow a second editor -- it renders the STATE as one
sentence and links to Settings:

    "Pushes to main deploy automatically."            (auto_deploy true)
    "Auto-deploy is off. Pushes to main are ignored." (false, warning tint)

with a quiet "Change" link to the Settings tab. One declaring home, the card
is a satellite that only reads.

### 5.2 Webhook state: last delivery, last failure

`webhook_deliveries` already records every claimed delivery with EVENT +
ACTION + RECEIVED_AT per application. New read helpers on `WebhookDeliveries`
(currently package-private; widen to public statics, no new table):
`latestFor(instanceId)`, `latestProblemFor(instanceId)` (action in the
problem subset -- see below), `recentFor(instanceId, n)`.

"Problem" is NOT a string denylist scattered in the page: the action
vocabulary gets a declaring home. Today `stampAction` receives free strings
from 12 call sites. Brief 2 introduces `WebhookDeliveryModel.ACTION` as an
EnumField whose members carry a `problem` boolean facet
(`repository_mismatch` true; the `ignored_*` family false; `deploy_queued`/
`preview_queued`/`preview_teardown_queued` false), and every `stampAction`
call site references the enum key. `WebhookActionVocabularyTest` walks the
GitWebhookHandler source's stampAction calls against the enum (the
UpstreamKindVocabularyTest pattern) so a new action is one edit or the build
breaks. Unknown stored values render as plain text, never crash (the
enum-badge degrade rule).

Card content (admin + manage-write, replacing the current webhook card's
bottom half):

- Payload URL + secret rows (existing; the secret gains a reveal toggle --
  copy works without reveal, `pl-copy-button` already takes the value).
- Fact: "Last delivery: 2h ago -- push, deploy queued" (event + action
  labels from the enum).
- Fact, only when a problem exists NEWER than the last good deploy-queued
  delivery: "Last failure: repository mismatch, 3d ago" in warning tint.
  Absent otherwise -- no green "everything fine" row; silence is the calm
  signal.
- Disclosure "Recent deliveries" (last 10): time, event, action badge. No
  paging, no filters; the 30-day ledger is diagnostics, not a workspace.
- Manual re-deploy: that is the serving strip's Deploy now -- the card links
  the words "deploy manually" to it rather than growing a second button.

A deploy that a webhook queued but that FAILED shows up in the timeline
(trigger `webhook`, status failed) -- the card does not duplicate operation
state; it only covers the stretch BEFORE an operation exists (bad signature
= no row at all + 404 by design and NOT surfaced per-application, because
unauthenticated probes must not write tenant-visible state; mismatches and
ignores ARE claimed deliveries and do surface).

## 6. Localization

- Every user-visible string is microcopy: short keys + scope filters, en +
  nl (`src/server/resources/META-INF/microcopy/{en,nl}.json`), nl values
  \uXXXX-escaped, placeholders `{$name}`. New keys ride the existing
  `deployments` scope (title, serving, deploy_now, rollback,
  rollback_confirm_named, changes_none, changes_variables, compare_title,
  no_snapshot, live_title, ...), `previews` additions on the existing
  `preview_deployment` scope (slots_used, rebuild, all_previews,
  none_running, ...), webhook keys on `deployments` (last_delivery,
  last_failure, recent_deliveries) and the two new enum vocabularies get
  scopes `release_trigger` and `webhook_action` (bound by the drift tests).
- NOT localized, by declared design: step logs, build logs, failure reasons
  and delivery ACTION raw storage -- stored operator evidence rendered
  verbatim (the ReleaseEngine step-log precedent). The LABELS around them
  (badges, card titles) are microcopy.
- `pl-log-view` (plumage) ships its one string ("Jump to latest") as plumage
  microcopy like every component string there.

## 7. Schema changes and the deploy consequence

An APPENDED `M0xx_` migration (never an edit of `InitialMigration`, which is
frozen and checksum-pinned by MigrationIntegrityTest since 2026-08-29; new
columns on existing tables must be nullable or defaulted):

- `release_operations`: add `trigger` STRING, `triggered_by` STRING nullable,
  `commit_sha` STRING nullable, `build_operation_id` INTEGER nullable,
  `spec_snapshot` TEXT nullable. up() and down() both touched (the table is
  created whole, so this is editing the createTable block; down already drops
  the table).
- No other tables change. `webhook_deliveries.action` stays a STRING column;
  the EnumField declaration is a model-level vocabulary over the same column.

Operator consequence: there are NO production installations
(no-zenit-project-is-live); starfleet/daystrom/nightstrom are wiped-and-
redeployed hosts. A dev database that predates the edit refuses to boot
(`database.migration_integrity = fail`) and is deleted and recreated -- the
operator loses local release history and webhook ledger rows, which were
already prunable diagnostics. Pre-existing rows are impossible post-wipe, so
no renderer needs a legacy-null branch beyond the honest em-dash for a null
trigger/snapshot (kept anyway: recovery-created ops (`recoverInterrupted`)
run without attribution and stamp `reload`).

## 8. Test plan

Behaviour journeys (numbered steps, one assertion message per step), unit
lane unless marked browser. Every new guard names its falsification.

1. `ReleaseTimelineJourneyTest` (server, SQLite):
   1. Create an application + first deploy through `ApplicationDeploys`
      under `DeployAttribution.as(manual, "tester")` -- assert "operation
      records trigger=manual and triggered_by=tester".
   2. Deploy changed source -- assert "second op stores commit_sha and
      build_operation_id resolving to a succeeded build row".
   3. Render `InstanceDeploymentsPage` -- assert "serving strip names the
      serving commit" and "timeline rows carry trigger badges, not kinds".
   4. Roll back -- assert "rollback op stamped trigger=rollback" and "the
      strip's confirmation sentence names both commits and the changed
      variable names".
   5. Render with `?compare=<op1>` -- assert "compare card lists scalar
      changes verbatim and variable names only".
2. `SpecSnapshotRedactionTest`:
   1. Snapshot a spec whose env contains an injected DB password -- assert
      "snapshot stores the key with a sha256: fingerprint, and the plaintext
      value appears nowhere in the stored text".
   FALSIFY: in `SpecSnapshots.snapshot`, return the env value verbatim
   instead of fingerprinting; the "appears nowhere" assertion fails; restore.
3. `BuildLogTapTest`:
   1. Subscribe while another thread appends 500 chunks -- assert "the
      subscriber's replay+stream equals the final text exactly once (no gap,
      no double)".
   2. Append past the cap -- assert "subscriber sees the truncation marker
      exactly once".
   3. Register a redacted secret, append text containing it -- assert
      "subscriber never receives the secret".
   FALSIFY: move the listener attach outside the monitor in
   `BuildLog.subscribe` (snapshot then attach non-atomically); assertion 1's
   exactly-once fails under the concurrent append; restore.
4. `DeployLogHandlerTest` (FakeChannelLink -- the conduit-less handler seam):
   1. Open for an application with only a FINISHED op -- assert "backlog =
      one snapshot step frame + one build frame from the persisted LOG +
      terminal status".
   2. Open mid-build (LiveBuildLogs holds a log) -- assert "backlog is the
      text so far, then a live append arrives as a non-snapshot frame".
   3. Open for a foreign instance id and for a missing id -- assert "both
      refusals are the identical ChannelException message".
   4. Revoke the view capability, advance past the cadence, push a frame --
      assert "the link is closed".
   FALSIFY (refusal oracle): make the missing-id branch throw a different
   message; step 3's identical-message assertion fails; restore.
5. `WebhookDeliverySurfaceTest`:
   1. Claim deliveries deploy_queued then repository_mismatch -- assert
      "latestProblemFor returns the mismatch and latestFor the mismatch as
      newest".
   2. Claim a newer deploy_queued -- assert "the card model reports no
      last-failure fact (problem older than last success)".
6. `WebhookActionVocabularyTest` + `ReleaseOperationTriggerDriftTest`
   (drift): parse GitWebhookHandler/`DeployAttribution` call sites for the
   stamped strings, compare against the enum members and the en/nl scopes.
   FALSIFY: add `stampAction(claimed, "ignored_new_thing")` with no enum
   member; the test fails naming it; restore.
7. `PreviewCardTest`: enabled-no-previews renders the one-sentence empty
   state; two live previews render quota fact "2 of N"; disabled + none
   renders NO card element at all.
   FALSIFY (absence rule): render the card unconditionally; the no-card
   assertion fails; restore.
8. `ReleaseSurfaceBrowserTest` (browser, default lane -- no live hosts:
   docker work is faked at the InstanceService seam like the existing
   deployments tests):
   1. Open the Releases tab as admin -- assert "one primary button (Deploy
      now) and no rollback button before a retained release exists".
   2. With a retained release, click Roll back -- assert "the confirm dialog
      body names the target commit".
   3. Trigger a fake in-flight op + registered live BuildLog, push appends
      -- assert "the live card's log view grows without reload and
      auto-follows".
   4. Scroll the log up, push more -- assert "the view stops following and
      shows the jump-to-latest affordance" (this is `pl-log-view`'s
      plumage-side browser test; repeated here only as integration).
9. plumage `LogViewTest` (browser, in plumage): follow-tail, scroll-up
   detach, jump-to-latest, bounded text swap; showcase page enters the axe
   sweep automatically (fail-closed page enumeration).

Coverage stance: targeted classes above ARE the verification; no full-suite
run is owed (the only cross-repo risk is brief 1's plumage component, proven
by its own showcase tests).

## 9. Implementation briefs (ordered)

Serialized per repo; 1 is the only framework brief and only brief 5 consumes
it (5 can start on the `<pre>` fallback in parallel).

| # | repo | brief | size |
| --- | --- | --- | --- |
| 1 | plumage | `pl-log-view`: monospace follow-tail log pane. Property `text` (String), auto-follow unless user-scrolled, "jump to latest" (microcopy), no live-region on the body. New component template under `src/common/templates/components/`, tag styles in-component (cascade layer rule), showcase page `src/browserTest/templates/test/log-view-test.hwk` (axe swept fail-closed), `LogViewTest`. Acceptance: test plan item 9 green; llms.md regenerated. | S |
| 2 | hohenheim | Model + attribution wave. Append the section 7 migration (never edit `InitialMigration`). `ReleaseOperationModel`: TRIGGER EnumField (7 members with facets), TRIGGERED_BY, COMMIT_SHA, BUILD_OPERATION_ID, SPEC_SNAPSHOT fields. New `server/application/DeployAttribution` (GeneratedRows.as shape) read by `ReleaseEngine.newOperation` (:869); stamp commit_sha beside the three IMAGE_ID writes (:229, :328, :446); thread `build_operation_id` out of `ApplicationReleases.desiredSettings` (:349 -- return the buildId beside the map, or stash it on the map under a reserved key stripped before store; pick the former). New `SpecSnapshots` (snapshot at the same stamps; diff). Wrap callers: `SiteControlHandlers` (:44-62), `GitWebhookHandler` (:267 deploy, :361 preview, no-op), `PaasApi`, boot/reload converge path. `WebhookDeliveryModel.ACTION` enum + call-site sweep. Tests: plan items 2, 5, 6 + extend `ApplicationReleaseTest`. Verify with `zd_test --class` on the touched suites. | M |
| 3 | hohenheim | Live tap + hub. `BuildLog.subscribe` (atomic replay+attach, notify after redact/cap), `server/build/LiveBuildLogs` registered/unregistered inside `SandboxedBuilds.run` (:79/:123 finally), `server/application/DeployProgress` hub + publish calls in `ReleaseEngine.step(RecordStamp,line)` (:908) and finish (:930). `DeployFrame` @HawkeyeClass record in common. Tests: plan item 3. | M |
| 4 | hohenheim | Channel. `HohenheimChannels.DEPLOY_LOG` (+ init forced like INSTANCE_STATS), `server/application/DeployLogHandler` (InstanceStatsHandler shape: onOpen capability walk via `HohenheimAccess.hasInstanceCapability(principal, id, VIEW)`, backlog per section 3.3, 15s revalidation, onClose unsubscribes), factory install at MODULES beside `InstanceStatsHandler.init`. Client `HohenheimDeployFunctions.follow` (HohenheimStatsFunctions.series shape). Tests: plan item 4 (FakeChannelLink). | M |
| 5 | hohenheim | The Releases tab. Rework `InstanceDeploymentsPage` + `instance-deployments.hwk` into the five-card layout (section 1): serving strip (one primary Deploy now; Roll back with the named confirmation via `t("rollback_confirm_named", ...)` args computed from `SpecSnapshots.diff` of serving vs `ReleaseEngine.newestRetired`), live activity card (SSR backlog seed + `HohenheimDeployFunctions.follow` + `pl-log-view`, `<pre class="hh-deploy-log">` until brief 1 publishes), timeline (trigger/commit columns from the new fields, per-row overflow Compare + View build log, retained-release tag), `?compare=` card. Keep the existing `use:Zenit.form` + `ReturnTarget` wiring untouched. Microcopy en+nl for every new key. Tests: plan item 1; browser item 8 lands here. Screenshot review by Jelle is part of done (phase-0 section 6 rule). | L |
| 6 | hohenheim | Automation card. `WebhookDeliveries` public read helpers (latestFor/latestProblemFor/recentFor -- problem = the enum facet from brief 2), card render per section 5.2 (state sentence + Settings link, last delivery fact, conditional last-failure fact, recent-deliveries disclosure, secret reveal toggle), admin-only vars stay inside `putAdminOnlyVars` (:134 -- the allowlist rule in its docblock). Tests: plan item 5's card-model half + template assertions in plan item 1 step 3. | S |
| 7 | hohenheim | Previews card. Card per section 4.1 on the Releases tab (render condition: previews_enabled OR live rows; quota fact via `PreviewQuota.limit()` + owner bucket count; rows from `PreviewDeploymentModel.findLiveByApplicationId`; Rebuild via `PreviewDeployments.queue`, Destroy via `.destroy` with the resource's confirm wording; "All previews" link to the panel-appropriate resource list with `?filter.application_id=`). Tenant parity is free (same RecordScopedPage on both panels) but VERIFY the manage list link targets `ManagePreviewDeploymentResource`'s panel slug. Tests: plan item 7 + a journey step asserting the manage panel renders the card for a granted tenant. | M |

Dependency notes: 2 first (every later brief reads its columns). 3 and 4 after
2, in order. 5 after 4 (and consumes 1 when published; the `<pre>` fallback
keeps 1 off the critical path). 6 and 7 after 5 (they render into its layout),
either order.

## 10. Framework gaps found (and where each lands)

1. No follow-tail log component anywhere in plumage -- hohenheim's
   `<pre class="hh-deploy-log">` is the hand-rolled shape the plumage skill
   forbids. Mechanism home: plumage (`pl-log-view`, brief 1). Generic
   consumers waiting: thoth's request viewer, QQ task output.
2. No live tap on `BuildLog` / no in-flight build registry -- the phase-0
   "streamed logs" stream into memory only. Home: hohenheim `server/build`
   (briefs 3) -- deliberately NOT a framework mechanism yet; the channel layer
   is the framework half, and a second consumer of "tail a bounded redacted
   log over a channel" would be the trigger to promote the tap shape upward.
3. No trigger/actor on release operations and no commit identity surviving
   reclaim -- product-visible history holes, hohenheim-local columns (brief 2).
4. Webhook delivery ACTION was a free-string vocabulary with 12 writers and
   no declaring home -- closed as an EnumField with facets + drift test
   (brief 2). Same class of fix for the deploy `reason` strings (TRIGGER).

Everything else the surface needs already existed: channels (zenit
common/channel, hohenheim precedent), RecordScopedPage tabs on both panels,
CmsConfirm with computed messages, rich enums with badge facets,
pl-relative-time/copy-button/collapsible/empty-state, ReturnTarget, the
preview lifecycle, and the webhook ledger.
