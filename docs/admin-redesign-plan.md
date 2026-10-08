# Hohenheim admin redesign: build plan

Draft for review, not committed. Inputs: `docs/ux-review-2026-09-30.md` (defects section 1, P1-P8), the 15 boards
in the workspace root's `docs/hohenheim-ux-mockups-2026-09-30/` (git-ignored, never committed; read from their
generator `boards.py`), the zenit-cms-resources,
zenit-forms-editing, zenit-framework and plumage-components skills, and the code after the module-fit consolidation
(Hohenheim `5e7353c6`).

Settled (not re-asked): one "App" entry over sites, instances and stacks in the admin, data model unchanged; Force
HTTPS switches on automatically once a certificate works; order = defect wave, then the framework record-page shape
(P2, P3), then P1 and P4-P8.

Conventions: a class named with `file:line` was read. `[unverified]` marks a claim not checked against the code or
the running admin. zemble `home` could not be consulted: the daemon was rebuilding the workspace graph for the
whole session ("construction deadline expired" on every call, and a `--no-daemon` run did not finish in 5 minutes).
Each gap below was instead checked by a manual search (named per gap), and each gap's `home` call stays owed before
its design is built.

## 1. Framework inventory this plan builds on

| Need | Existing mechanism | Where |
| --- | --- | --- |
| Read-first record front page | `RecordOverview` (fields and/or a widget tree, `surfaceActions`) | `zenit-cms/.../resource/RecordOverview.java:32`, `widgets` :72 |
| Front door of a record | `ResourceForm.Builder.landingTab(slug)` | `ResourceForm.java:212` |
| Line under the record heading | `ResourceForm` `lead(...)` -> `RecordLead`, drawn by `pages/resource-form.hwk:33,70` | `ResourceForm.java:198` |
| Child records as a tab | `ChildList.of` / `ChildList.sections` (narrowed child lists, header links, visibleWhen) | `ChildList.java:42`, `:77`; used by `SiteParts.java:104` |
| Fewer visible tabs | `RecordTab.secondaryTab()`, `RecordTabs.VISIBLE_TAB_CAP` | `RecordTab.java:77` |
| Action disabled with its reason | `OperationHandlers.attach(op).availability((subject, access) -> reason)` | `zenit/.../operation/OperationHandlers.java:455` |
| Action that asks input | `Operation` + `OperationInput.of(FormSpec, ...)`, `PanelAction.places(...)`, `.inSheet()` | `PanelAction.java:333`; skill "Operation input" |
| Multi-step input (wizard) | `FormSpec.Builder.step(FormStep)`, per-step validation lane `INVOKE_VALIDATE`, final `INVOKE_SUBMIT`, `pl-stepper` | `FormStep.java:25`, `FormSpec.java:666`, `PanelActionInvocations.java:686,740`; proof `PageWizardBrowserTest` |
| Fields that only apply to a choice | `FormSpec.Builder.showWhen(entry, sibling, tokens...)` (`FormCondition`, same parse in browser and coercion) | `FormSpec.java:603`; used by `EnvironmentParts.java:171` |
| Choice offered but dead, with a line | `FieldOption.asDisabled()` / `withDescription(...)`, `OptionSource` dynamic per `EditContext` | `FieldOption.java:78,88`, `OptionSource.java:30` |
| Create from a picker | `RelationPick` + the entry's model source made `creatable` by `CmsRecordSources.installCreateProvider`; reduced form `InlineCreateForms.reduceSpec` | `CmsRecordSources.java:405` |
| Many lists in one sidebar job | `NavGroup` (icon, collapsible, separator), `PanelEntry.showInNav`, `RelatedPage.toPeer` | `NavGroup.java:24`, `PanelEntry.java:93`, `RelatedPage.java:28` |
| List over non-row data | typed store: `SubjectType.of(...)` + `ResourceList.store(table, pages)` + `StorePages.inMemory` | `StorePages.java:44` |
| Dashboard | `PanelDashboard` + zenit-widget builtins (Alert, Stat, Status, Fact/FactList, Records, UsageBar, Chart, Section, Columns, ActionButton) and `RecordActionsWidget` | `PanelDashboard.java:22`; `zenit-widget/.../builtin/` |
| Settings grouping | `SettingsPage.standard` mounts in declared order, `frameworkMount()` position, `SettingGroup.advanced()` (folded group) | `SettingGroup.java:109`; `SettingsPageRenderer.java:278-406` |
| Activity grouped by command | core `ActivityGroup` (entries of one command), `ActivityModel.COMMAND_ID` | `ActivityGroup.java:15`, `ActivityModel.java:65` |
| Progress of a running operation | `OperationCall.progress()` fed by the invoke route (`progress(request.conduit())`) | `OperationCall.java:86`, `PanelActionInvocations.java:347` |
| Plumage parts drawn on the boards | `pl-stepper`, `choice-group`, `usage-bar`, `timeline`, `stat-card`, `alert`, `toggle-group`, `log-view`, `property-list`, `state-glyph`, `status-dot`, `list`, `table` | `plumage/src/common/templates/components/` |

Every board in the mockups uses only these plumage components; none needs a new one.

## 2. Section-1 defects

D1. New domain forces HTTPS with no certificate; green "HTTPS" badge.
- Cause: `SiteDomainModel.FORCE_SSL` `defaultValue(true)` (`SiteDomainModel.java:180-181`); the dispatcher refuses
  with 503 while termination is down and redirects into a certificate-less handshake otherwise
  (`SiteDispatcher.java:358-366`); the site TLS cell shows "forced", not "working" (`cms/cell/site-tls.hwk`).
- Design (settled decision, implementation mine): keep the fail-closed dispatcher unchanged. Add a stored fact
  `force_ssl_auto` (boolean, model default true for new rows, false for existing rows). New domains store
  `force_ssl = false`. The ONE writer that activates a certificate for a hostname sets `force_ssl = true` on every
  domain it covers whose `force_ssl_auto` is true, and clears `force_ssl_auto` (a latch, recorded in activity), so a
  certificate lost later still fails closed, exactly as the AIDEV-NOTE at `SiteDispatcher.java:350` demands.
  Existing domains keep their stored `force_ssl`: no live behaviour changes on upgrade.
- Migration: edit `M011_ReviewHardening` (above `DEPLOYED_THROUGH = "010"`), add the column with default false;
  re-record the test installs' ledgers; extend the upgrade rehearsal fixture.
- Badge: the site and domain HTTPS cells read one Hohenheim `HostnameHttps` state per hostname (works / renews in N
  days / missing / waiting for DNS / not used), built from the existing certificate coverage read
  (`SiteParts.certificateCell`). "Forced" is never a badge again.
- Help copy for `force_ssl` rewritten: what happens without a certificate.
- Attention: `ProxyAttention.httpsUnavailableWithForceSsl` (`ProxyAttention.java:82`) keeps covering the
  termination-down case; a per-hostname "forced, no certificate" item is added for existing rows.

D2. Manual certificate request ignores `ssl.letsencrypt_enabled`, talks to LE production unannounced, wipes the form.
- Today: a hand-rolled `CertificateRequestPage` (`PanelPage`) + `CertificateRequestForm` + host endpoint
  `HohenheimEndpoints.CERTIFICATES_REQUEST` + `cms/certificate-request.hwk`.
- Mechanism: a placed `request_certificate` `Operation` with `OperationInput` (names, challenge type, DNS mode via
  `showWhen`), placed ONE on a domain row ("Get a certificate") and PAGE NONE on the certificate list.
  `.availability(...)` returns "Let's Encrypt is switched off in Settings" while the setting is false; the handler
  refuses the same (the pipeline feeds both). A refused submit rerenders the input inline with the posted values
  (operation pipeline), so nothing is wiped and the domain context is the placement's subject.
- Copy: the input states it uses Let's Encrypt production; a pre-check reads `HostnameReach` (new Hohenheim service,
  see board Online-2) and refuses with "does not point here yet, it points to X"; ACME problem types map to plain
  sentences, the raw detail goes to the log only.
- [unverified] The manual DNS-01 round trip (operator adds a TXT record, comes back) must map onto the certificate
  record: a pending order shown on its overview with a "Continue" operation. Read `CertificateHandlers` before W1.

D3. A protected path whose access list has no rules is open; a new rule starts switched off.
- `AccessRuleModel.ENABLED` already defaults to true (`AccessRuleModel.java:173-174`); the "starts off" sighting
  is [unverified] on the current build (possibly the form's switch rendering). Re-walk it.
- Writes: `ProtectedPathInvariant` (`ProtectedPathInvariant.java`) also refuses a list with no enabled rule, and the
  rule writers refuse disabling or deleting the last enabled rule of a list that protects a path (each refusal
  names the path). One invariant home, every writer.
- Existing empty lists: see fork J2. Until decided, the path shows "Open to everyone" (board App-Problem) and an
  attention item names it.

D4. Host admission: Admit offered first and refused, Preflight hidden, unreadable refusal, refused button turns green.
- Today: separate `preflight_server` and `admit_server` placements (`ServerLifecycleActions.java:79,86`).
- Mechanism: one placed operation `check_host` (PRIMARY, header and row): runs `HostPreflight.runAndStore`; on a
  pending host whose required checks all pass it admits through `HostAdmission`; otherwise it refuses with the
  failed required checks listed. On an admitted host it re-checks only. The host overview shows the stored report
  as "Must pass" and "Advice" (board Host-Admit).
- Copy for the posture refusal rewritten in operator words. The "turns green" sighting is [unverified] after the
  merge; re-walk it.

D5. Access-list picker says "No results found" with no create.
- Mechanism exists: inline create from a `RelationPick` (section 1). Why it is absent here is [unverified]: either
  the pick declares `creatable(false)`, or the access-list create spec has a REQUIRED entry `InlineCreateForms`
  cannot reduce (that refuses the affordance with a slog). Fix in Hohenheim's declaration; if the reduction is the
  cause, the access-list create spec gets a reducible required set. P6 makes this picker the advanced path anyway.

D6. Backup targets page without nav entry; attention item lands on the top of Settings; the setting is a free-text
target name.
- Nav: `BackupTargetParts` is `showInNav(false)` (`BackupTargetParts.java:123`); it moves into the Settings cluster
  (gap G2) or, without G2, gets a visible entry in the System group.
- Deep link: `AttentionCollector.java:201` links `CmsRoutes.list(ADMIN, SettingsPage.DEFAULT_SLUG)`. The settings
  page reads `?section=` (`SettingsPageRenderer.SECTION_PARAM`, `SettingsPageRenderer.java:55`), but there is no
  typed route for it, so every caller would spell the parameter by hand: gap G3b.
- Picker: `hohenheim.backup.control_plane_backup_target` is a `String` name (`HohenheimSettings.java:579`).
  Settings support enum choices only (`SettingDefinition.Builder.choices`, `SettingDefinition.java:430`),
  no record reference: gap G3a. The stored value stays the target's name, so no settings migration.

D7. Secret-field overlap and toasts surviving navigation: fork F2's framework work, excluded here. The access-rule
title reading "Password: Username" is Hohenheim copy in `AccessRuleSummaries` ([unverified] which branch).

D8. Wrong fields.
- Static site enables the Instance picker: `SiteParts.java:129-130` keeps the pick editable on purpose ("hiding it on
  a non-instance site was a one-way door, 2026-09-08"). `showWhen("instance_id", "<upstream kind entry>",
  "<instance kind token>")` is the mechanism; [unverified] whether `FormCondition` keeps an inactive entry's stored
  value or clears it on save. Read `FormConditions` and the coercion lane first; if an inactive entry clears on
  save, that answers the one-way-door note (switching away clears the link, switching back re-asks it).
- LXC and VM offered without an Incus host: the kind choices come from a registry-driven enum; a dynamic
  `OptionSource` marks them `asDisabled()` with "Needs an Incus host" ([unverified] that kind cards honour
  `disabled` and `description`).
- Deploy and Restart enabled on an instance that cannot start: add `.availability(...)` to START, RESTART,
  APP_UPDATE, INSTALL, REINSTALL, ROLLBACK (`InstanceOperationHandlers.java:82-151`) reading the existing
  `InstanceBlockerView` (the reason the overview's "cannot start yet" panel already shows). One reason source for
  panel and buttons.

## 3. Boards

Main (Dashboard)
- Needs: primary "Put something online"; "Needs attention" in plain words, each with its own action; four stat
  cards; an Apps mini-list with HTTPS state; recent activity as sentences.
- Mechanism: `AdminDashboard extends PanelDashboard` (`AdminDashboard.java:44`) with `surfaceActions()` for the
  primary action; Stat and Records widgets; the attention widget (`widget-attention.hwk`) with per-item action
  labels and deep links (copy change in `AttentionItems`); the apps mini-list reads the App directory (board
  Apps-List) through a `DataProvider`; recent activity reads the narrated activity (G4).
- The two checklists (`OnboardingCollector`, `widget-onboarding*.hwk`): each step is mapped to its replacement
  first ([unverified] what each step covers); only steps the flow or an attention item replaces are deleted.

Online-1 What, Online-2 Where, Online-3 HTTPS, Online-4 Going live (P1)
- Needs: one flow creating instance, database, site, domain and certificate; host picked automatically with its
  usage and an override; a "points here" check; HTTPS choice and a summary; live progress that survives leaving.
- Mechanism: the wizard (section 1). Today's `InstanceFromTemplatePage` is already a PAGE placement of
  `CREATE_INSTANCE_FROM_TEMPLATE` with the template chosen first by `selectedBy` (`InstanceFromTemplatePage.java:59-69`).
  P1 generalises it: a PAGE placement of a new `put_online` operation whose input steps are Where (name, address,
  host, options as a `FormSection`) and HTTPS (mode as choice cards, summary). Step What is the chooser that
  `selectedBy` already implies (kind and template in the query), drawn as step 1 of the stepper.
- [unverified] Whether a step's entries can depend on an earlier step's answer (MODULE-FIT-PLAN 6.2 rule 1 says "a
  step's form may be resolved from earlier answers"; no such API was found on `FormSpec`/`FormStep`). The chooser
  design does not need it; template variables stay on the Where step as today.
- Handler: one domain command composing the existing writers (template create through `InstanceTemplates`,
  `TemplateDatabases.allocate`, `SiteWrites.CREATE`, the domain row, the certificate order). The ACME order is
  external: it runs after commit (`DomainEvents.afterCommit`), never inside the transaction.
- Host pick: the existing placement rule (`InstancePlacement`) answers the default; usage bars read the host's
  stored capacity; the override is a `RelationPick` over admitted hosts, non-admitted ones `asDisabled()` with the
  reason.
- "Points here": new Hohenheim service `HostnameReach` (resolve the name, compare with the proxy's public
  addresses, cached briefly); shared by Online-2, D2's pre-check and the Domains board. Product logic, not
  framework: it answers a Hohenheim question with core's resolver.
- Going live: gap G5.
- /manage twin: the tenant panel gets the same placement narrowed by `fixed(...)` as `InstanceFromTemplatePage`
  does today (`ManagePanel.java:143`).

App-Overview, App-Problem, App-Blocked (P2, P3, P5)
- Needs: heading = app name, a lead line ("WordPress, on daystrom, project Webshop"), header actions disabled with
  the reason, ONE health line first, Addresses and Protection cards with their own actions, Resources usage bars,
  Details facts, Recent timeline, about seven tabs.
- Mechanism: `RecordOverview.widgets(...)` (as `InstanceOverview` already does with Alert/Status/UsageBar/Records
  widgets), `ResourceForm.lead`, header and record-band actions with availability, `ChildList` for addresses and
  databases, `landingTab(overview)`.
- The health line is gap G1, so the list's HTTPS column, the overview's first band and the attention item read one
  verdict.
- Which record the App page is: fork J1.

App-Console (P5)
- Needs: Console, Shell and Run a command as modes of one tab.
- Mechanism: one `RecordTab.Rendered` (`InstanceConsoleTab`) drawing a `pl-toggle-group` over the three existing
  views; each mode keeps its own capability check (`InstanceExecPage.visibleFor`, `InstanceShellPage` capability).
  Product rendering, no framework part fits a terminal.

App-Protect (P6)
- Needs: protect a path in place: path, how (password, network, sign-in), who (users added inline), save refused
  while empty, shared list as the advanced option.
- Mechanism: a placed `protect_path` operation on the site (ONE, on the Access tab, `inSheet()` or inline) with
  `OperationInput`: path, method (choice cards), `showWhen` per method, the people as a repeatable entry. The
  handler creates a dedicated access list, its rules and the protected path in one command; the D3 invariant
  refuses an empty list for every writer.
- [unverified] Whether `Records`/`ItemList` work inside an operation input (not table-stored). If not, gap G6.
- "Who may manage this app" reads the existing grants (zenit-auth record grants); read-only here.

Host-Admit (P7)
- Mechanism: D4's `check_host`; the host overview already is a `RecordOverview` with preflight widgets
  (`ServerParts.java:115`, `widget-host-preflight.hwk`); it regroups into Must pass / Advice and adds "How to fix"
  links per check (copy and docs links per check id).

Apps-List (P4)
- Needs: one list over sites, instances and stacks: state glyph, app, address, kind, host, HTTPS state, actions;
  search, kind/host/state filters; trash link.
- Mechanism: a typed-store `PanelResource` (`SubjectType.of(hohenheim:app)`, `ResourceList.store(table, pages)`,
  `StorePages.inMemory`) over a Hohenheim `AppDirectory`: one read that reads the site, instance and stack sources
  through their gated record sources under the caller's access (never raw `Models.find`), joins them (a workload with
  its sites is one app; a site without a workload is one app; stack members are rows of their stack) and feeds both
  this list and the dashboard widget. Rows link with `rowLink` to the record page fork J1 picks.
- Why no framework gap: what makes an app is Hohenheim vocabulary; the generic parts (typed store, in-memory pages,
  `DataProvider`) exist. A union source in core waits for a second consumer.
- /manage twin under `TenantScopes`; `ManageScopeParityTest` must cover it.

Activity (P8)
- Needs: sentences per action ("Jelle put shop.example.com online"), filters by person and app, internal records
  behind a toggle.
- Mechanism: zenit-cms `ActivityAdmin` composed by `AdminActivityResource` (default filter: what a person did).
  Missing: grouping by command with one headline, and the internal-record toggle: gap G4.

Settings (P8)
- Needs: operator sections first (General, HTTPS, Backups, Notifications, templates, runtime images, git, engines,
  DNS, blocking, about), framework behind "Framework (advanced)", backup targets on the Backups section, a target
  picker, Show and Replace on the secret.
- Mechanism: `SettingsPage.standard` with Hohenheim's groups split into operator mounts, `frameworkMount()` last
  and its groups `advanced()`. The resource sections (templates, images, git, engines, notification channels,
  backup targets) are their own entries in the Settings cluster (G2). The target picker is G3a. The secret
  control is F2's.
- [unverified] "68 groups, duplicate names" on the current build: two module-fit changelogs renamed settings group
  labels; recount before W5.

Domains (P4)
- Needs: one nav entry with Addresses, DNS zones, Released addresses as tabs; per address: app, points here, HTTPS
  state, certificate line, "Get a certificate".
- Mechanism: `DomainParts` list with virtual cells reading `HostnameReach` and `HostnameHttps`; D2's operation as
  the row action; the three entries as one cluster (G2).

## 4. Framework gaps (W2)

Each gap: a manual search found no mechanism (named), the proposed home, a short design, and why the inventory
does not fit. Each still owes its zemble `home` verdict before design starts.

G1. Record health verdict. Home: zenit-cms (resource part).
- Searched: no `Health`/`RecordStatus`/status-line type in zenit, zenit-cms, zenit-widget, plumage;
  `StatusWidget` is a badge row, not a verdict.
- Design: `ResourceHealth<T>` part, `PanelResource.Builder.health((record, access) -> Health)`. `Health` = a closed
  tone (`OK`, `ATTENTION`, `BROKEN`, `UNKNOWN`; enum, exhaustive switches), a headline `Microcopy`, an optional
  detail and the ids of placed actions that fix it (offered through the normal action family, so gates and
  availability apply). Readers: `RecordOverview` draws it as its first band; `ColumnSpec.health()` draws it as a list
  cell; a dashboard source lists the records of an entry whose verdict is not OK. Batch form for lists
  (`ResourceReads.pageBatch` shape) so a list page never reads per row.
- Why not existing: an Alert widget inside one overview cannot feed the list cell or the attention list, which is
  how the green badge and the 503 disagreed.

G2. Entry clusters. Home: zenit-cms (panel navigation).
- Searched: no cluster/hub type in zenit-cms; `NavGroup` groups sidebar rows; `RelatedPage` puts siblings in the
  toolbar overflow.
- Design: `PanelCluster` entry (one sidebar row) naming member entry slugs; each member keeps its route, gates and
  breadcrumbs; its pages draw the cluster's secondary nav (tabs) above their own header; the cluster's landing is its
  first admitted member; a member hidden for the viewer is absent from the tabs. Members are declared by the
  cluster, never by a member flag (one declaring home).
- Why not existing: collapsible groups keep every list as a sidebar row, which is the 23-entry problem.
- Alternative without G2: fork J3.

G3. Settings. Home: zenit core (`SettingDefinition`) for a, zenit-cms (`CmsRoutes`) for b.
- a) Record-reference setting: `SettingDefinition.Builder.references(sourceId, keyField)`; stored value stays the
  key the setting stores today (a name here), coercion refuses an unknown key, the editor draws a `RelationPick`
  over that source with the viewer's gates, a missing referent is a `RULE_REFUSED`-style row state. Searched:
  only `choices(StoredEnum[])` and `allowedValues` exist.
- b) Typed deep link: `CmsRoutes.settings(panelSlug, settingsSlug).with(SECTION_PARAM, groupKey)` with the
  parameter as a `ParameterDefinition` in `CmsEndpoints`, replacing the server-only string constant.

G4. Activity narrated per command. Home: zenit core (activity) + zenit-cms (`ActivityAdmin`).
- Searched: `ActivityGroup` exists in core but zenit-cms never reads it; no "internal model" fact on
  `ActivityPolicy`.
- Design: the activity list renders one row per `ActivityGroup`, headed by the operation that ran the command
  (its label with subject and actor; [unverified] whether `COMMAND_ID` identifies the operation, else core records
  the operation id on the group's first row); a model declares its rows internal (an `ActivityPolicy` fact, e.g.
  lease, quota, grant, bootstrap), hidden by default behind one toggle, still searchable and exported.

G5. Progress of a long placed operation that outlives the page. Home: [open] zenit core run record or zenit-cms
invoke UI.
- Searched: the invoke route passes the conduit as the `ProgressSink`, but no zenit-cms template draws operation
  progress; Hohenheim keeps its own step logs (`ReleaseEngine.step(...)` on `ReleaseOperationModel`,
  `InstallMediaFetches.StoredProgress`).
- Design candidate: a stored run of a placed operation (steps with state, live through `Live.watch`), drawn by one
  zenit-cms partial on the invoking page and reachable later from the subject; check task runs (`TaskRunFeed`,
  `cell/task-run.hwk`) for reuse first. Hohenheim's release and fetch step logs become its first consumers only if
  the shape fits; otherwise W6 ends the wizard on the app page and shows progress through the health line.

G6. [conditional] Repeatable entries in an operation input. Home: zenit core edit + zenit-forms. Only if the W5
check shows `Records`/`ItemList` cannot coerce into an operation's input record.

## 5. Waves

Every wave: one targeted test run after all edits; journeys are behaviour journeys (numbered steps, assertion
messages); a deletion's only reason is the named replacement doing the same job; UI waves end with a walk of the
real admin on an empty database.

W1. Hohenheim defect wave (D1-D6, D8; D7 copy only).
- Files: `SiteDomainModel`, `M011_ReviewHardening`, the certificate activation writer ([unverified] in
  `CertificateHandlers`/`AcmeService`), `SiteParts` cells, `ProxyAttention`/`AttentionCollector`, new
  `HostnameHttps`, new `HostnameReach`, new certificate operation in `CertificateParts`/`DomainParts`,
  `ProtectedPathInvariant` + rule writers, `AccessRuleSummaries`, `ProtectedPathParts` pick, `ServerLifecycleActions`,
  `InstanceOperationHandlers`, `SiteWrites` form, instance kind options, `BackupTargetParts` nav, microcopy.
- Tests: `NewDomainHttpsJourneyTest` (new domain: not forced, badge "missing"; certificate activates: forced, latch
  cleared, badge "works"; certificate removed: still refuses), `CertificateRequestJourneyTest` (LE off: offered dead
  with reason, POST refused; name not pointing here: refused, input kept), `ProtectedPathJourneyTest`,
  `HostCheckAndAdmitJourneyTest`, `InstancePowerAvailabilityJourneyTest`, `SiteFormApplicabilityJourneyTest`;
  upgrade rehearsal from the M010 fixture covers the new column.
- Deletes: `CertificateRequestPage`, `CertificateRequestForm`, `cms/certificate-request.hwk`, the
  `CERTIFICATES_REQUEST` endpoint and its handler branch (replacement: `request_certificate` operation; first check
  the API does not call it); the `preflight_server` and `admit_server` placements (replacement: `check_host`).
- Note: G3 is not in W1; the backup deep link and picker land in W2/W5.

W2. Framework gaps G1, G2, G3, G4 (G5 decided after its reuse check; G6 only if needed).
- Files: zenit-cms (`ResourceHealth`, `ColumnSpec.health`, overview band, `PanelCluster` + nav projection +
  breadcrumbs, `CmsRoutes.settings`, `ActivityAdmin` grouping), zenit core (`SettingDefinition.references`,
  activity internal fact), zenit-forms (settings reference control through `RelationPick`), skills and changelog
  entries for each.
- Tests: one journey per mechanism in its owning repo (zenit-cms `RecordHealthJourneyTest`,
  `PanelClusterJourneyTest`, `ActivityByCommandJourneyTest`; zenit `RecordReferenceSettingJourneyTest`).
- Deletes: none in Hohenheim yet; the server-only `SECTION_PARAM` string becomes the typed parameter.

W3. Record-page shape on Hohenheim (P2, P3).
- Sites, instances, hosts, databases, certificates, access lists get a `RecordOverview` landing tab with
  `ResourceHealth` and a `lead`. Instance tabs fold to Overview, Console, Files, Metrics, Backups (ChildList
  sections: snapshots, backups, schedules), Configuration, Access, History; Migrate, Deploy, Restart as header
  actions with availability.
- Deletes [each unverified until read]: `InstanceSnapshotsPage`, `InstanceBackupsPage`, `InstanceArtifactsPage`,
  `instance-artifacts.hwk`, `InstanceSchedulesPage`, `instance-schedules.hwk` (replacement: `ChildList.sections` over
  `InstanceSnapshotParts`/`InstanceBackupParts`/`InstanceScheduleParts`, only if their row actions and restore cover
  the tab's verbs); `InstanceMigratePage` + `instance-migrate.hwk` (replacement: MIGRATE placed in the header with its
  `MigrateInput`); `InstanceExecPage` and `InstanceShellPage` tab registrations (replacement: the console tab's modes).
- Tests: `AppOverviewJourneyTest` (healthy, broken HTTPS, blocked by host), tab-set pin per resource.

W4. Navigation and the Apps list (P4).
- New `AppDirectory`, `AppParts` (typed store, admin + /manage twin), clusters Domains, Access, Settings; nav groups
  reduced to Dashboard, Apps, Databases, Hosts, Domains, Access, Activity, Settings.
- Deletes: none of the member entries (they become cluster members); the curated nav notes in `HohenheimPanel` are
  rewritten.
- Tests: `AdminNavigationJourneyTest` updated (it pins the sidebar), `AppsListJourneyTest`.

W5. Flows (P1, P6, P7 copy, P8).
- `put_online` operation and its placement; `protect_path`; dashboard rebuilt on the App directory; settings mounts
  regrouped, backup section with G3a picker and G3b deep links; activity internal declarations for Hohenheim's
  plumbing models.
- Deletes: `InstanceFromTemplatePage` and `instance-from-template.hwk` only if the put-online placement covers
  every template kind and the /manage twin (replacement: `put_online`); onboarding checklist steps the flow replaces.
- Tests: `PutOnlineJourneyTest` (WordPress template on an admitted host to a live app, address not pointing here,
  LE off), `ProtectPathInPlaceJourneyTest`, `SettingsBackupTargetJourneyTest`.

W6. Going live (G5) and the language pass (P8 copy: no raw keys, no ledger words), then the walk on an empty
database and on a test install with a running workload (needs a login there).

## 6. Decisions (decided by the orchestrator, 2026-10-04: J1, J2 and J3 as recommended below)

Jelle did not want to decide these himself; they are taken as recommended. Revisit only if the built result shows
a problem.

J1. Which record is the App page? Recommended: the workload's record page (instance or stack) when the app has a
    workload, the site's page otherwise; both use one overview composition, so they look identical. The alternative,
    a new App record type with its own URL, needs a second write path over the same rows.
J2. Existing protected paths whose list has no enabled rule: keep serving them open with a loud badge and an
    attention item (recommended: no live behaviour change on upgrade), or fail closed at upgrade.
J3. Domains, Access and Settings as clusters (G2, recommended) or as collapsible nav groups with every list still a
    sidebar row (no framework work, about twice the sidebar rows).

## 7. Unverified, to check before the wave that needs it

- W1: certificate activation writer location; manual DNS-01 round trip; why the access-list picker offers no
  create; `FormCondition` inactive-entry save semantics; kind cards honouring disabled options; rule "starts off"
  and "turns green" sightings on the current build; the `CERTIFICATES_REQUEST` callers.
- W3: what each bespoke instance tab does beyond listing.
- W5: step entries depending on earlier answers; repeatable entries in operation input; settings group duplicates
  after module-fit; onboarding step coverage.
- All gaps: zemble `home` verdicts.

## 8. G5 decision (fork G5, 2026-10-05)

Verdict: NEW core record, built by EXTRACTING the task runs' progress recorder, not a second copy of it. zemble
`home --no-daemon`: NEW_MECHANISM, home zenit (medium); its nearest matches were the task-run family
(`TaskRuns.Run`, `TaskRunFeed`, `TaskAdmin`) and two Hohenheim private copies (`InstallMediaFetches.StoredProgress`,
`InstanceExecResults`).

Why no existing mechanism fits as is:
- Task runs (`system_task_history`, `TaskRuns.Run`, `TaskRunFeed`, `cell/task-run.hwk`) have the right PROGRESS shape
  (fraction, message, parts, throttled writes, abandon sweep, live feed), but the run claim is per task TYPE
  (`TaskService.startTaskManually` refuses a second run of a type cluster-wide, `TaskService.java:721-770`) and the
  row has no subject. Two apps going online at once would refuse each other.
- `OperationRequest.progress(sink)` (`OperationRequest.java:283`) is fed the request conduit by
  `PanelActionInvocations.boundRequest` (`:347`): progress lives only as long as the request. No zenit-cms template
  draws it.
- `RecordScheduleStepRunModel` stores operation runs (operation, input, invocation id, status, outcome) but carries no
  progress and belongs to schedules.
- Protoblast `ProgressSink` is the right reporting vocabulary; nothing persists it outside tasks.

Shape (home: zenit core; rendering: zenit-cms):
- `PersistedProgress` (zenit server): the throttled recorder now private in `TaskRuns.Run` (`progressed()`,
  `trailing()`, interval `tasks.progress_write_interval_ms`), extracted behind a small writer interface. Task runs
  and operation runs both use it: one throttle, one setting.
- `OperationRunModel` (zenit common, table `zenit_operation_runs`): id, operation (registry member like
  `RecordScheduleStepRunModel.OPERATION`), invocation id (the command id, so the run IS the command's receipt
  view), subject model + subject id (nullable until attached), caller principal (PrincipalRef), status (closed
  `OperationRunStatus`: RUNNING, SUCCEEDED, REFUSED, FAILED, ABANDONED), progress, progress message, step index,
  outcome (Microcopy text of the result or refusal), error, claim fence, started/ended/updated. No input column: the
  command already consumed its input; nothing secret is stored.
- Declaration: `Operation.Builder.background(OperationSteps)`: an operation that may outlive its request declares its
  ordered steps (id + Microcopy label each). A step is a progress PART: `addProgressParts(steps)` is implied, the
  handler advances with `reportProgressPart()` and details with `reportProgressMessage(...)`. No new progress verb.
- Execution: the pipeline still authorizes, validates and claims the command in the request (refusals answer
  inline as today). On admission it inserts the run row, returns the run, and runs the handler on JobRunner under the
  caller's `ExecutionIdentity` (the work runs as its caller, the capability map Execution identity row) with the
  run's `PersistedProgress` as `OperationCall.progress()`. The outcome write is fenced by the run claim; a dead holder
  is retired by the same `ClaimedRows.retire` sweep task runs use (ABANDONED). [unverified] which server executor
  JobRunner resolves to under load; check before W6.
- Subject: `OperationCall.attachSubject(ref)` lets an arity NONE create (put online) name the record it made, so the
  run is found from that record afterwards.
- Reads: `OperationRuns.of(runId)`, `OperationRuns.openFor(subjectRef)`, `OperationRuns.recentFor(subjectRef, n)`.
  Readers: the caller, and any viewer who may READ the subject (RecordCapabilities); everything else is concealed.
  Fail closed for a run with no subject: caller only.
- Live: `OperationRunFeed` registered like `TaskRunFeed` (LiveFeeds owned id over `OperationRunModel`), with the
  read rule above as its gate.
- Retention: runs older than `operations.run_retention_days` (default 30) pruned by one core task. [unverified]
  whether `ActivityPruneTask` can take a second model instead.

Rendering (zenit-cms):
- One new partial `operation-run.hwk`: the declared steps as `pl-stepper` (done / current / pending / failed), the
  current message, the outcome or refusal sentence, and the next link (the subject's page). It reuses the gauge of
  `cell/task-run.hwk` for a step with a fraction.
- Invoking a background operation redirects to `CmsRoutes.operationRun(panel, runId)` (typed route, declared
  parameter); leaving and returning shows the same state through the live feed.
- A subject's overview draws its open run as a band above the health line (G1), so an app that is still going live
  says so on its own page and in the list.

Hohenheim mapping:
- Put online (Online-4): the `put_online` operation declares steps Instance, Database, Website, Certificate, Live.
  The ACME order still runs after commit, as its own step's work.
- Install media fetch: `InstallMediaFetches.StoredProgress` is a copy of the throttled persistence. Replacement: a
  placed background operation `fetch_install_media` on the host. Delete `StoredProgress` and the hand-written
  progress/state writes once the host tab reads runs. `InstallMediaFetchModel` stays while it holds the media
  catalogue (name, ready state per host). [unverified] whether anything besides the tab reads its PROGRESS column.
- Release/rollback: `ReleaseOperationModel` is a domain record (phase machine, candidate/retired ids,
  fingerprints) and stays. Its free-text `STEP_LOG` (`ReleaseEngine.step`, `:1050`) duplicates run steps; it is
  replaced only when deploy/rollback become background operations whose steps carry the same lines. Not before W6;
  the live build-log stream (`docs/phase2-release-surface-design.md:292`) must map onto the run feed first.
- Run a command: `InstanceExecResults` stashes the last result in the session for the next render. Candidate
  replacement: the command's run outcome. [unverified] whether the output size cap fits a run outcome column.

Deletion list (each only after its replacement is wired and tested):
- `TaskRuns.Run` throttle code moves into `PersistedProgress` (same job, one home).
- `InstallMediaFetches.StoredProgress` and its private `write(...)` progress path (replacement: background
  operation run).
- Later and conditional: `ReleaseOperationModel.STEP_LOG` writers, `InstanceExecResults`.

Tests:
- zenit `OperationRunJourneyTest`: admitted background op inserts a RUNNING run; steps advance, writes throttled;
  outcome SUCCEEDED with text; refusal before admission creates no run; a killed holder ends ABANDONED by the sweep; a
  viewer without READ on the subject cannot read or watch it; an arity NONE create attaches its subject.
- zenit `TaskRunProgressJourneyTest` must stay green unchanged after the extraction.
- zenit-cms `OperationRunPageJourneyTest` (browser): invoke, see the stepper advance live, navigate away and back,
  the same run is shown and ends on the subject link.
- Hohenheim `PutOnlineJourneyTest` going-live steps; host media fetch journey after the replacement.

Order: W6 as planned. G5 needs nothing from W1-W5 except the `put_online` operation; the extraction of
`PersistedProgress` can land earlier with its task-run regression proof.

## 9. W4 decisions (forks W4 and W4b, 2026-10-06)

- The sidebar is eight rows in one unlabelled block, in board order: Dashboard, Apps, Databases, Hosts, then four
  `PanelCluster`s. Activity is a cluster too (Activity, Inbox, Deliveries), so the eight rows hold.
- Cluster slugs: `domain-names` (Addresses, DNS zones, Certificates, Released hostnames), `access` (Access lists,
  Users, Roles, IP bans, Abuse protection), `log` (Activity, Inbox, Deliveries), `configure` (Settings, Instance
  templates, Runtime images, Git providers, Database engines, Notification channels, Backup targets, Build info).
  Certificates is a tab of Domains, not a row of its own.
- A cluster lists only the members the node registered; a cluster left with none is not added.
- Sites, Instances, Stacks and Projects leave the sidebar and are linked from the Apps list's toolbar; every app
  row opens its record page (J1). The command palette still finds every cluster member (zenit-cms
  `NavItemState.members`).
- The Apps list is a typed store over `AppDirectory`, read through each entry's own panel source. Generated
  instances (database containers, engines, stack services) are never apps. The kind filter is the closed
  Workload / Website / Stack set (`AppDirectory.Source`); the kind column shows the kind's label, not the template.
  The state filter is the framework's `HealthTone`.
- "Put something online" links to the from-template page until W5 builds the real flow.
- "Open site" is hidden while the record's health verdict is BROKEN (Jelle, 2026-10-06).
- The instance, site and host forms are labelled "Configuration"; History stays in the strip.

## 10. W5a decisions (fork W5a, 2026-10-06)

- Two background operations behind one page (`PutOnlinePage`, slug `put-online`): `put_online` over the selected
  template (its input is that template's own variables, resolved per subject by the create-from-template lane's
  `InstanceTemplateHandlers.inputScope`) and `put_address_online` without a subject for an address that needs no
  workload. Both share the website and certificate steps (`PutOnlineHandlers`); the website is the site create
  (`SiteOperationHandlers.createSite`) in its own transaction, the certificate the certificate operation's own order,
  the first start `InstanceService.deploy`. Nothing is a second writer.
- Which upstream kinds are apps of their own is a fact on the kind: `UpstreamKindInfo.offeredAsApp()` (default: not
  instance-backed; the dev namespace says no). The chooser and the address input both read it.
- A run that succeeds says what is left in words: zenit `OperationCall.reportOutcome(Microcopy)` (new), so "live at
  x over HTTP, Let's Encrypt is off" reads in the viewer's locale. Progress messages stay plain text.
- The board's step "What" is the page's chooser, not a stepper step: the stepper is the framework's FormSteps of the
  chosen operation (Where, Options, HTTPS); its "Going live" is the run page.
- An address is an operator act: a tenant's put online has no address and no HTTPS step (fixed by the placement and
  refused by the authorizer). The /manage panel carries the same page.
- `InstanceFromTemplatePage` stays for now: ten test classes and the API-shaped no-script create submit through its
  synchronous invoke route, and putting the same templates online is now `put-online`. The template catalogue's
  "Create instance" row link, the Apps list's toolbar and the dashboard lead to `put-online`.
- Workloads without a template (Git app, container image, Linux container, virtual machine, workspace) open the
  instance create form from the chooser's "Your own code or machine" group until they get a flow of their own.
- The dashboard header carries the primary link through zenit-cms `PanelDashboard.headerLinks(PanelRequest)` (new);
  the onboarding checklist's "create an instance" and "deploy it" steps are one "put your first app online" step,
  and the empty-install card leads to the flow instead of the site form.
- Not built here: the automatic subdomain (Online-2), the live "points here" notice on the Where step (the reach is
  checked by the certificate order itself), the dashboard's Apps mini-list.

## 11. W5c2 decisions (fork W5c2, 2026-10-07)

- The "points here" notice sits in the HTTPS step's summary, not under the address on Where: a step summary is resolved
  on the server once Where validated (W5c1's `summarizedBy`), so the notice needs no live per-keystroke check. It reads
  `HostnameReach.recent`, the one-minute cache at its home; the certificate pre-check keeps calling `HostnameReach.of`.
- The chosen kind rides the address wizard as transport (zenit-cms now carries a single-field select as transport and
  joins transport to every wizard step prefix), so the kind is never asked twice and the settings sub-form it
  discriminates still validates on Where.
- An attention item that leads somewhere names its fix (`AttentionItem.action`); the helper without an action has no
  target, so the compiler finds every targeted item that lacks words.
- The template summary names no template: the operation's input form is shared by every template, and the chosen
  template already heads the page in its card.

## 12. W5b decisions (fork W5b, 2026-10-07)

- Activity: plumbing is decided per MODEL (`ActivityLog.setVisibility`), so a model people also write (hosts, zones,
  sites) stays listed and its bookkeeping writers run under `ActivityLog.suppressed` instead: host probes
  (`HostProbe`), the system-user mirror refresh (`UpdateSystemUsers`), the delegation probe (`DnsDelegationHealth`) and
  the secondary freshness probe (`DnsSecondaryFreshness`). Starfleet's log was 22k secondary-freshness rows, 3.9k
  system-user rows and 1.9k host rows before this; each now reaches operators through its alert or attention item.
- Internal models are declared once in `HohenheimActivity` (port claims, quotas, released claims, reconcile findings,
  proxy sessions, controller identities, install-media fetches, webhook deliveries, system users); grants are
  declared internal at their own home in zenit-auth (`ZenitAuth.registerDefaults`), per G4's examples.
- "What a person did" is one scope, `AdminActivityResource.peopleOnly()`: origin neither system nor seed (or unknown),
  and no internal model. The list opens on it and the dashboard's recent activity reads it. Its internal half is
  spelled over `model`, because the dashboards' `zenit.activity` source derives its vocabulary from its projection
  and has no `internal` variable; both read `ActivityLog.internalModelTokens()`.
- Protect a path: one `protect_path` operation (`ProtectPath`) on the site, placed as a record action in a sheet, that
  writes a dedicated (unshared) access list, its rules and the protected path. The people are a zenit-forms `ItemList`
  inside the operation's input, which coerces there as it does in a form (gap G6 not needed). An empty answer is
  refused on its own entry before anything is written; the protected-path invariant still refuses an open list for
  every writer. The Protection card keeps linking to the site's protected paths: a placed operation opens its sheet
  from its own button, and the invoke route is POST-only.

## 13. W5b2 decisions (fork W5b2, 2026-10-07)

- Recount before the change: the settings tree listed one "Hohenheim" mount of 22 groups, a "Framework" mount of about
  44 groups (with duplicate labels: Storage, Database, Cms, Channels), Communication and Spamservice, about 70 rows.
- Operator sections are mounts in one declaring home, `HohenheimSettingsSections` (General, HTTPS and certificates,
  Backups, Notifications, Apps and deploys, Hosts, DNS server, Blocking and firewall, Abuse protection, Proxy). A section
  of several groups is zenit-cms `SettingsPage.frameworkSection` (new: the groups' parent minus its other child
  groups, so every setting keeps its stored path); a section of one group is `frameworkGroup`, headed by the section's
  own name. `AdminSettingsMountsTest` pins that every Hohenheim group belongs to exactly one section.
- zenit's own settings sit behind one "Framework (advanced)" disclosure: `SettingsPage.Standard.frameworkAdvanced()`
  (new) marks the framework mount advanced, and its groups are listed once the reader opens it, searches, or deep-links
  a group (`SettingsSearch.sectionShown`). The page never opens on a framework group.
- The section key replaces the old `app` mount key in anchors and input names (`setting-backups-database`,
  `backups.database.control_plane_backup_target`); stored setting paths are unchanged, and the anchor format has one
  public home, `SettingsPage.anchor`.
- The boards' resource sections (App templates, Runtime images, Git connections, Database engines, Notification
  channels, Backup targets, Build info) are the Settings cluster's tabs, never duplicated as settings rows. Backup
  targets are records on their own tab (added and edited there, saved immediately, Test connection in each row); the
  control-plane destination picker reads them (`references`) and offers "create" when there is none.
- Tasks: zenit-cms `TaskAdmin.schedules` (Run now) and `TaskAdmin.runs` are Settings cluster tabs, gated by the
  scheduler's own permissions.
- Notifications: channels already carry "Send a test" and list each channel's subscribed events; the board's
  event-by-channel matrix is not built.

## 14. W7a decisions (fork W7a, 2026-10-07)

- App-Deploys is the existing `InstanceDeploymentsPage`, reshaped in place (slug `deployments` kept so every link and
  test route stays): no framework part composes a live band, a release history with logs and a push ledger, so it
  stays a product-rendered record tab. It now sits right after Overview and is no longer a secondary tab.
- The live band reads the instances' roles (`ownedServing`, `newestRetired`), never the operation status; a history
  row is marked Live, Kept for rollback or Replaced from the same roles (`ReleaseMark`, a closed enum). A release
  operation records no commit of its own: the commit is read from its candidate release instance's stored settings,
  trashed instances included.
- Rollback moved from the card into the live band as "Roll back to <commit>", offered only with a kept release and
  nothing in flight. "Deploy now" stays on the history card (the heading also offers Deploy).
- Previews: the application's live previews with their own row actions through `PanelActionOffers.rowsForRender`
  over the previews entry (the access-list rules page precedent), "Details" through the record front door, and
  "Preview a branch" opening the previews create form with the application preset (`HohenheimParams.PREVIEW_APPLICATION`
  read by `PreviewParts.createDefaults`; the queue still asks MANAGE on it).
- Recent pushes read `webhook_deliveries` through `WebhookDeliveryModel.findRecent`; what a delivery caused is now one
  vocabulary, `WebhookOutcome` (stored tokens unchanged), with a sentence per outcome. The push address and secret stay
  operator-only.
- App-Git-Config: the application kind's settings sections are Source and build (open), Variables (open, "Changes
  apply at the next deploy."), then Build details, Deployment and Runtime folded. Source comes BEFORE variables (the
  board draws variables first) because the same schema renders the create form, where the repository is the first
  decision. Field declaration order is unchanged, so stored data and surface baselines' control order are too.
- Needs building (shown on the boards, left out): who or what started a release (operations record no trigger), the
  ref and commit of a push (deliveries record only event and outcome), per-variable secret values and variables
  derived from attached databases or projects, the variables as lane tabs.

## 15. W7b decisions (fork W7b, 2026-10-07)

- App-Metrics: the Metrics tab keeps the existing live lane (the hub's ring seeds the page, the mounted
  `hh-instance-stats` element's channel link keeps it live; no polling, no new socket). Charts are plumage
  `pl-sparkline size="block"`, a new size slot that takes the container's width at 7.5rem tall; the inline default is
  unchanged for every other consumer. CPU and memory sit side by side, received and sent side by side in the network
  card, one column on a narrow screen.
- Received and sent are running totals on the wire. They used to be plotted as cumulative KiB (a line that only ever
  rises); they now plot and read as a rate per second taken between consecutive samples. One home for the rate:
  `HohenheimStatsFunctions.Metric.perSecond` / `rateBetween`, used by the server's seed and the browser's fold alike.
- Every reading is in words under its chart ("Now 50% of 2 cores", "512.0 MB of 1.0 GB", "1.4 MB/s"): CPU against the
  configured `cpu_limit` (or of one core when none), memory against the cap the runtime reports in the sample, sizes
  through zenit's `ByteText`. The page states the live-only contract on every instance, and the root-disk card (the one
  stored figure) moved under the live charts and reads in words.
- App-Config-Address: an address app's Configuration leads with where requests go (scheme, host, port or socket,
  unsectioned), then "Connection" (the former folded "Forwarding options", now open: websocket, rewrite redirects,
  accept any certificate, give up after, wait before forwarding, the protocol pin last). Static files: folder and
  fallback file, then "Folder listings" (open), then "Connection" folded (the delay). Redirect: target, status, keep
  path, then "Connection" folded (the delay). Never the framework's generic `FormSection.advanced` in a site's
  settings sub-form: the site form around it already has its own "Advanced" card, and two would be indistinguishable.
  Field declaration order is unchanged, so stored data and baselines' control order are too.
- Needs building (shown on the boards, left out): "Test the connection" for an address app (no such operation), and
  the board's kind cards at the top of the Configuration tab (the site form's existing kind picker stays).

## 16. W8a decisions (fork W8a, 2026-10-07)

- One per-name HTTPS rule: `AppHealth.httpsOf(domain, passthrough, working)` answers what HTTPS gives one exact name
  (forced without a working certificate is ERROR whatever the certificate's own status says). The Addresses list, a
  site's Addresses tab and the app overview's Addresses card all read it; before, the tab read only the covering
  certificate's status, so a forced name without one could read "pending" there and "Not working" on the overview.
- The Addresses list (board Domains) is the domain entry's own list, so the site's Addresses tab inherits it: Address
  (hostname, path under it), App, Points here, HTTPS (badge, covering certificate linked for who may open it, expiry).
  The child list's private certificate column is deleted; its replacement is the list's own HTTPS column. Match type
  and Force SSL stay in the picker and the filter strip.
- "Points here" reads `HostnameReach.recent` (cached a minute per name), so a render resolves each name at most once a
  minute; a pattern gets no answer. The list renders the HTTPS badge and the certificate in one cell rather than two
  columns, because the certificate line is what the badge is about.
- One list cell for "a word and a line" (`StateLineCell`, `cms/cell/state-line.hwk`): the address's "Points here" and
  the certificate's state both render through it.
- The address page (board Address-Edit): sections Which requests, HTTPS, Headers (folded), replacing the one generic
  Advanced section; the app it serves heads the form. Its heading's lead says what it serves, whether the name points
  here and what HTTPS gives it, the same answers as its list row. Field labels keep their current wording (copy pass).
- Certificates (board Certificates): a State column in words replaces the raw status (failing renewal with its count
  and days left, waiting for a DNS record, being issued, expired, works and for how long); the stored status stays in
  the picker and the filters. The list keeps sorting by expiry: a failing renewal is the soonest to expire in practice,
  and the table has one sort key.
- Needs building: the "Paths on this address" card (routes sharing the hostname), per-path redirect rules (OPEN), one
  request page merging the challenge and publisher into the board's three choices, "Read the certificate" before an
  upload saves, and an inline Reissue on a failing row (the action is in the row's menu).

## 17. W8b decisions (fork W8b, 2026-10-07)

- "Points here" is bounded: one reader waits at most `HostnameReach.LOOKUP_WAIT_MS` (1.5 s), the cells of one
  Addresses page share `DomainParts.REACH_RENDER_BUDGET_MS` (2 s, a request attribute set by the first cell), and a
  name not answered in time reads "Checking" while its lookup keeps running on a virtual thread into the one-minute
  cache. Lookups are single-flight per name and capped at 64 at once. The certificate pre-check keeps the unbounded
  `HostnameReach.of`: it acts on the answer.
- Access area tabs follow the board: Access lists, Blocked addresses, Users, Roles, Sign-in providers, then Abuse
  protection. Sign-in providers stay out of the sidebar (cluster member) and keep their Sites-list header link.
- Access lists read by what they let in ("Password, 2 people", else "N rules") and how many places use them; how the
  root group combines and when it was made move to the picker. The Rules tab gains a "Protects" card (every app or path
  on an app the list gates) and the shared note. The rule tree stays the existing editor.
- Blocked addresses open on "Blocked now" (a removable default filter on `active`); columns read Address (with why),
  By, Until; scope, event and created-at move to the picker and filters. Never-block stays a setting (Settings >
  Blocking), not repeated on this page.
- Users show two-step sign-in (On/Off) from zenit-auth `TwoStepSignIn.isOn`, the same check the login routing reads.
  Sign-in providers show where they are used (rules naming them plus sites they gate).
- Needs building: recent misses on the Blocked page (security events live in spamservice; a domain-miss list needs a
  local store or the spamservice feed filtered), the never-block list shown in place, a Role column and a selected
  person's app access on People, and "Signed in to protected sites" (Node clause 28, OPEN).

## 18. W9a decisions (fork W9a, 2026-10-08)

- Hosts stay a list, not the board's card grid. The only card face zenit-cms has is `CardGridSpec` over plumage's
  `pl-media-grid`, which its own rules reserve for records recognised by a picture ("when the columns are how, it is a
  table"); a status-card list face would be a new zenit-cms face plus a plumage grid, out of proportion for a list of a
  handful of hosts. The board's card content is the list's columns instead: the name with the daemon and when it was
  last seen under it, State (the admission in words with what it means), Memory (a usage bar over the capacity ledger
  placement reads), Who may run here (the posture), Runs ("7 apps, 3 databases", the Apps list's notion of an app).
  Runtime, ssh target, the public address and the raw admission token stay searchable or filterable behind the picker.
- The admission's three words are the board's, at their one home (the `host_admission` catalog entries the enum's
  labels read): Waiting for its checks, Takes new apps, Takes no new apps. Posture is labelled "Who may run here".
- A waiting host says why: the REQUIRED preflight checks that failed, by name (`HostAttention.failedRequiredChecks`,
  read from the stored report the overview already draws). The same sentence is the attention item's detail, and its
  title reads "<name> cannot run apps yet".
- A list leads with its own tier's attention items: `AttentionCollector.hosts()` and `.databases()` are the dashboard's
  collectors split by tier (`collect()` is composed of them), drawn through the dashboard's attention widget by
  `AttentionCollector.band`. The list's widget slot now sits between the page heading and the card (zenit-cms), where the
  board draws its alert; it used to sit above the heading.
- Databases: the name with where it runs under it ("Shared engine X on host" / "Own container on host"), the engine,
  Used by (the live workloads holding its credentials), Last backup. The name inside the engine, host, placement, engine
  row and tmpfs flag move behind the picker. A database row opens its new Overview tab (landing; the form is
  "Configuration"): Connection (engine, where, database name, user, how apps connect), Used by (each workload linked to
  its front door), Backups (retention and every stored dump with its size and time), Recent.
- The stored dumps ARE the backup record: `DatabaseBackups` (server/database) is the one home of where dumps go, what
  is on disk and pruning; there is no backup table, so the page can never claim a dump that is not on disk. The nightly
  task and "Back up now" both go through `DatabaseBackups.backUpOrAlert`, so a failure raises the same BACKUP_FAILED
  alert either way.
- "Back up now" is a placed operation (`back_up_database`) on the row and the record heading, running on the
  provisioning pool as system work; offered dead on a temporary database and on one that is not running.
- The password stays off the overview: the Restore tab (operator) and the Credentials tab (tenant) already show it with
  their own gates, and a fact list has no reveal control.
- The daemon and when it was last seen sit under the host name (the board's card line), so the public address is no
  column any more; it stays searchable. What a host runs ("1 app, 3 databases") sits under "Who may run here", as the
  board's card ends: as a sixth column it slid under the pinned row actions at 1440px.
- The list widget slot (zenit-cms) keeps the dashboard grid, so stat cards still sit side by side; on a railed list the
  heading and the widgets span the rail and the card. A list's attention band claims the grid's full width through
  the same band section the dashboards use (`AdminDashboard.section`, now the one home ManageDashboard calls too).
- Counted copy agrees with its count (MF2 `.match`): "1 required check failed", "1 app, 0 databases", "The newest
  dump". The runs line is two counted parts, because the catalog's `.match` takes one selector.
- A newest dump older than 36 hours (the nightly 03:00 run plus half a day) reads as a warning with "the nightly
  backup has not written a newer one", never as done.
- Open framework defect (zenit-cms row-action register): the pinned inline action lane switches to the compact menu
  only below a 48rem CARD; a wider card whose TABLE still overflows (Hosts at 1440px once a host's status line is
  long, e.g. "probe failed (docker_absent) 6 minutes ago") keeps the opaque lane over the last data column. The
  register has to follow the table's own overflow, with hysteresis (the compact menu frees the room that ended the
  overflow), not the card width alone.
- Needs building (on the boards, left out): the host card's disk bar (no host disk measurement is stored), the
  database "Connect with host:port" line (the address an app gets is resolved per link at start, never stored), Show
  and Copy on the password, "Change password" (rotation), "Attach to an app" from the database side (attaching lives on
  the instance's Databases tab), Restore from a stored dump (restore takes an upload today), the Engines card under the
  Databases list (engines are a Settings tab), the engine and host filters as dropdowns beside search (they are facets
  in the filter bar), and worded names for preflight checks (the list names them by their code, as the overview does).

## 19. D7a decisions (fork D7a, 2026-10-08)

- The Apps list's record link is the app's NAME (board Apps-List), not the health glyph. Cause: zenit-cms linked the
  first column whatever it held. Mechanism (zenit-cms): `ColumnSpec.glyph()`, a column fact; `ResourceHealth.column()`
  declares it, and the title link lands on the first column that is not a glyph (`TableState.ColumnState.title`).
- A workload answering with its site's verdict now offers that site's fixes (board App-Problem: "Get a certificate",
  "Stop forcing HTTPS"). Cause: `AppHealth.runningVerdict` dropped them, because the framework only offered a record's
  OWN row actions. Mechanism (zenit-cms): `RecordHealth.on(entrySlug, key)`, fixes that are another record's row
  actions, offered through that record's action family to a viewer whose read of that entry reaches it; an entry the
  panel does not register fails the band. Hohenheim offers them only where the node registers the site entries
  (`SiteParts.ROLE`/`registered()`, the one fact the panels' `addIf` reads too).
- "Stop forcing HTTPS" is a new site operation (`stop_forcing_https`): it switches force_ssl off on the names forced
  without a working certificate, with a confirmation that it gives up encryption. It is the operator's explicit "off",
  so the certificate latch stays disarmed: forcing again after a certificate works is the operator's choice, not the
  latch's (the model's stated rule that the system never overrules an explicit off).
- The overview's lone "Refresh" button is gone with its no-op operation (`refresh_instance_overview`, handler
  `call -> null`): loading the overview reads the same stored evidence afresh. The board has no such button.
- Every Hohenheim activity verb heads its rows with a sentence (`HohenheimActivityAction.happened()`, en and nl), so a
  row written outside an operation (git-push deploys, old rows) no longer reads "Jelle: Deployed Instance #3".
- zenit-auth's nine operations declare sentences ("Jelle changed who may use alchemy-skeleton" instead of "Jelle:
  Access Instance #3"), with a drift test deriving the set from zenit-auth's sources and the operation registry.
- Not done, needs zenit core (blocked while zenit's push waits on the arcana checkout):
  1. Sign-ins dominating Activity and Recent: the board lists none. Hiding them by default needs a verb-level
     visibility fact on `ActivityAction` (LOGIN/LOGOUT internal), read by the internal filter beside the per-model
     `ActivityVisibility`.
  2. `ActivityText.happened` returns an operation's sentence or null, never falling back to the row verb's own
     sentence: an operation without one hides a verb that has one. Core should fall back.
  3. A framework-wide operation-sentence coverage helper (zenit test support); zenit-cms (`task_run_now`,
     `task_run_again`, `task_stop`, `legacy_dashboard_press`) and zenit-comms (`retry_delivery`, `rotate_token`,
     `create_hub_project`) operations still read the generic headline.
- Seen on the walk, left open: a static site the proxy refuses as misconfigured ("a tenant-owned site may not serve
  files from the host", 503) reads "What it serves does not answer"; the verdict should carry the refusal's own reason.
  An activity row stored without a record title names its subject "Instance #1" (core `ActivityText.subject`); the
  live record's name would read better.
- Pre-existing test defect fixed: hawkeye's server render now stamps an id on the record heading (`<h1 id="ser-..">`),
  so the journeys match the heading by pattern instead of the bare `<h1>name</h1>` literal.

## 20. D7b decisions (fork D7b, 2026-10-08)

- A stored failure reason is the refusal's own sentence. `HohenheimViolations.reasonOf` (43 callers) now reads a
  `Violations` as its messages resolved in the installation's default content locale, never the debug
  `Violations.getMessage()` ("1 violation(s): -> workspace_build_failed {reason=...}") that reached Starfleet's Deploys
  tab. `HohenheimViolations.textOf` is the one content-locale resolver; `DeployStartPolicy.textOf`,
  `InstanceShellHandler.messageOf` and `ReleaseEngine.reasonOf` are deleted in its favour, and the forge commit
  statuses `ApplicationDeploys` reports read the same sentence. The better home is core `Violations` (every consumer
  stores reasons); it moves there once zenit can be pushed again.
- A workspace build failure names its exit code and points at the build log ("The build command stopped with exit
  code 1; its output is in the build log."); the output never rides the reason. A failed checkout quotes at most git's
  last three lines (300 characters). A build that fails after a good checkout records that commit on its row.
- A history row whose source is not a commit (a refused deploy, a failed checkout) reads "<branch>, no commit checked
  out" instead of the branch cut to eight letters; only a hex object name is shortened. Rows written before this keep
  their stored text (Starfleet's month-old alchemy-skeleton rows still carry the old debug reason until pruned).
- States read as the status vocabulary's words on both lanes (`build_status` labels; the release lane's Live/Kept/
  Replaced marks unchanged); When and Took never wrap.
- The live band leads a workspace too: "Live: commit X from <branch>", deployed since its last successful build, and in
  words why there is no way back ("A workspace deploys into its own files, so there is no earlier release to go back
  to."). The history card's lead names how a workspace deploys. Starfleet's alchemy-skeleton showed no band, rollback
  or "Started by" because it is a WORKSPACE (no releases by design, W7a) -- not a defect; "Started by" stays
  needs-building for both lanes (operations record no trigger).
- Metrics: `pl-sparkline` takes `yMin`/`yMax` (plumage; kept exactly as pl-chart keeps its bounds, values beyond drawn
  at the edge), and a constant series with derived bounds is drawn mid-height instead of on the floor. The Metrics tab
  starts every chart at zero, tops CPU at its limit (`InstanceStats.cpuCeiling`) and memory at the cap the runtime
  reports (`InstanceStats.memoryCeiling`); a steady 117.9 MB of 512 MB is a level line a quarter up, not the floor.

## 21. D7c decisions (fork D7c, 2026-10-08)

- An address's HTTPS badge is always the word ("Works", "Not working", "No certificate"), never the covering
  certificate's name; the certificate links on its own line under it with its expiry (`DomainCertCell`,
  `cms/cell/domain-certificate.hwk`). The cell had drawn the certificate's NAME as the badge whenever the reader could
  open it, which is why Starfleet's working names read "admin.starfleet.life" in green instead of "Works".
- A name whose HTTPS does not work says why beside the badge (`DomainParts.httpsDetail`): forced without a working
  certificate; forced and excluded from Let's Encrypt (the name another server holds the certificate for, so the row
  rightly offers no "Get a certificate": the request action's own applicability); no certificate, so plain HTTP; a
  failing certificate; one being issued; a passthrough app handling HTTPS itself. Starfleet's comms.starfleet.life is
  forced and excluded, which is why it read "Not working" with no action.
- A certificate's covered names read as words ("a.test, www.a.test") in its list rows (both twins), through
  `CertificateCoverage.namesOf`: the stored `domain_names_text` stays comma-joined data.
- A cluster member's landing page heads with the cluster's name over its tabs (zenit-cms `ClusterTabsState.title`,
  `cluster-tabs.hwk`). The name is a quiet line, not a heading: the board draws the cluster as the page heading with no
  member heading, but member pages come from several framework templates that each own their h1, so the member keeps
  its h1 and the area's name sits above the strip. Changing every member template's heading is a separate step if the
  quiet line is not enough.
- Copy in the boards' words, en and nl: Blocked addresses (create: "Block an address"), Sign-in providers, Released
  addresses, the access lists' lead "Who may reach a protected path", the cluster hints.
- No quick-add bar on Access lists or Blocked addresses (boards Access-List, Access-Blocked): each makes records
  through its one header action and its form. Lists for one path are made by Protect a path.
- An automatic ban's reason says what tipped it: the threat scorer keeps a ring of the events behind its points and
  hands the trigger how many events of the tipping type fell inside the window (`AutoBanTrigger` gains `events`;
  forgiveness lowers points, never events). The reason is stored resolved in the installation's content locale, like
  every stored reason (`HohenheimViolations.textOf`): "Tried 26 names this server does not serve". The causes derive
  from the same per-type copy key as the event labels (`HohenheimSecurity.EVENT_CAUSES`, drift-tested in en and nl
  beside `EVENT_LABELS`). Automatic bans stored with the old "score N over threshold" line read as their event
  ("Went over the limit for: Unmatched domain request"); nothing rewrites stored rows.
- Blocked on zenit-auth (its push waits on the arcana checkout, and the copy is zenit-auth's catalog): the Users and
  Roles tabs read "People" per the board, and the People list's Role column. Not done: "Recent misses" and the
  never-block list on the Blocked page (W8b's needs-building), "Lift" as the button word.

## 22. D7d decisions (fork D7d, 2026-10-08)

- A host check reads as words wherever it is named: the host page's report, the Hosts list's state line, the attention
  item and the Check and admit refusal. One home: `ServerOverviewState.checkLabel` beside `fixCopy`, keyed
  `check_<name>` (scope `host_check`) for every name the batteries declare; a name no battery declares (an older stored
  report) keeps its stored spelling instead of reading as another check. The verdict badge reads "Passed", "Advice",
  "Failed" from `PreflightStatus.label` (a fact on the member). `HostCheckAndAdmitJourneyTest` binds every declared
  check, verdict and probe failure to copy in en and nl, so a new one fails the build until it is named.
- A probe failure reads as words: `HostProbe.FailureKind.label` ("Docker not found"), with the last contact after it
  when there is one ("Docker not found, last reached 6 minutes ago"), in the host cell and the Check now refusal. A
  stored token this build does not know keeps its spelling. The W9a screenshot's `volume_backend`/`firewall` were the
  journey's fake check names; the journey now seeds declared ones.
- The database page's heading leads with Back up now by position; "Move to shared engine" is neither PRIMARY nor in the
  heading row (`inlineOnRecord(false)`): a placement change done once in a database's life sits in the More menu. The
  board's "Change password" stays needs-building (password rotation).
- A site the proxy turns every visitor away from carries the proxy's OWN reason in its verdict. The faulted handler's
  reason is copy (`FaultedSiteHandler` takes a `Microcopy`, scope `site_fault`); the route load records it on the
  `RoutingProblem` (`cause`), and `AppHealth` reads the site's recorded problems: one whose reason
  `refusesEveryVisitor` (unrouted kinds, a failed or faulted handler, an unbuildable auth provider: a fact on the
  member) makes the verdict "Visitors get an error page" with that reason before any HTTPS or live check; a partial
  one (a duplicate route, an unusable access rule) is an attention verdict "Not everything is routed as set up". The
  visitor's 503 says the same words in the content locale. This replaces the generic "What it serves does not answer"
  for D7a's seeded misconfigured site.
- A stored failure reason written before D7b in Violations' debug rendering reads as its refusal's sentence at read
  time (`HohenheimViolations.storedText`, applied in `WithheldFailure.shown`, the one reader of stored reasons on the
  panel). Only ONE violation is parsed; a key the catalog does not hold, two violations or any other shape keep the
  stored text. A message whose arguments changed since (the build failure took `code` where old rows stored
  `reason`) reads as the row's one stored argument. Stored rows are not rewritten. The API's `failure_reason` stays the
  stored text.
- A block without an expiry reads "Until lifted": a column can now declare what its empty cell MEANS (zenit-cms
  `ColumnSpec.absent(Microcopy)`, read by the translator before the field type's "None"). The row action reads "Lift",
  its dialog "Lift this block".
- "Created a minute from now" (D7a's walk) was the SQL seed, not a timestamp defect: a site created through the admin
  form (fork D7d2, scratch server, 2026-10-08 03:04 UTC) stores `created_at` as `2026-10-08T03:04:08.773103Z` and its
  Details read "2026-10-08 05:04 (Europe/Brussels)", the true local time. D7a's walk had hand-inserted the row.
- The Hosts-list overflow fold (FW-ROWS, zenit-cms dbc3060d) checked on the real list (screenshots `shots/d7d/`): at
  1440px the lane folds into the row menu and every column shows; at 1100px the lane is folded too but the table still
  scrolls sideways ("Who may run here" off to the right, the compact lane over the Memory column's scroll edge). The
  board draws hosts as cards; a narrow-width layout that wraps instead of scrolling stays open.

## 23. W9b decisions (fork W9b, 2026-10-08)

Boards Manage-Home and Manage-App: the tenant panel `/manage`.

- The sidebar is the admin's shape cut to what a tenant holds: Overview, Apps, Databases, Domains, Team, in one
  unlabelled block (no Deploy / Networking groups). Domains (addresses, DNS records, certificates, access lists) and
  Team (projects, members) are `PanelCluster`s built by the admin's own helpers (`HohenheimPanel.cluster/addCluster`,
  now package-private), so the two panels cannot shape clusters differently. Every other /manage entry is
  `showInNav(false)` with a declared way in: Sites, Instances, Templates, Previews and Git connections from the Apps
  list's toolbar (`AppParts.manage(related, putOnline)`, the admin twin's code), the rest from the record that owns
  them. Domains on /manage is a cluster tab now, so its old "no Domains cluster here" hiding note is gone.
  Access lists sit under Domains because the board has no tenant Access row and a list protects an address's path.
- The landing's attention band is the tenant's own apps' verdicts (`AppHealth` through `AppDirectory`, the delegated
  words that name no host): BROKEN is an error item, ATTENTION a warning, OK and UNKNOWN nothing, each with "Open
  <app>". This is the cause of DEP8's "All clear" while the app read "Error page": the band used to ask only the
  placement gate. That bespoke placement loop and its two copy keys are deleted; the instance verdict's "cannot start"
  carries the same refusal in host-free words.
- The landing's lower band is the admin dashboard's Apps band (`HohenheimWidgets.APPS`, `AdminDashboard.summaries`)
  over the /manage directory, replacing the plain "Your instances" records list, beside the new usage card.
- The usage card (`HohenheimWidgets.TENANT_USAGE`, `TenantUsage`) shows each budget the operator CAPPED for the
  tenant's creation owner (`OwnerBudget.limitFor/usedBy` on `creationOwnerSubjects`): apps (instances), memory, disk,
  websites, databases, previews; extra NICs are left out. An uncapped budget is not drawn, so an installation without
  caps draws no card, and an operator sees none. A record the operator made and granted counts against the operator,
  not the tenant: the card answers how much the tenant may still create. Which budgets show is an exhaustive switch
  over `OwnerBudget` (no default), so a new budget breaks the build until it is worded or left out.
- "Put something online" is offered on the landing and the Apps list to whoever may start something from the catalog
  (`InstanceTemplateParts.offersTenantCatalog`, the catalog's own nav rule): an operator always, a tenant only with
  the right to create instances and an approved template.
- No Recent band on the landing: the activity log is the operator's audit trail, which /manage never shows
  (ManageHistoryHiddenTest). A tenant-safe history needs its own read first (needs-building).
- The tenant's app page was already the admin's overview composition (J1) with the delegated censoring; it gains the
  board's "What you can do here" card: the instance vocabulary's own labels (`KnownCapabilities.forModel`) for every
  capability the tenant holds on the record, read through the same walk every tab and action is gated by, and "Up to
  the operator: removing this app and changing who has access" without MANAGE. Tabs and actions were already gated
  per capability; `ManagePanelJourneyTest` now proves it (VIEW alone: no console or files door; with console and
  files.read: both tabs).
- Needs building: the board's players line and other live facts (Pterodactyl group), a tenant-safe Recent band, the
  tenant Resources card (memory and CPU next to disk), the operator's name in "Need more? Ask ...".

### W9b2: the real-app walk (fork W9b2, 2026-10-08)

Scratch server (the built jar, fresh database, `/setup`), seeded through the admin UI: host `local` (not admitted
here: the nft probe needs sudo and the data root is not btrfs, so no instance can run on this machine), instances
Survival and Shop (both "cannot start yet"), a healthy redirect site Docs, the site Survival web serving Survival at
`survival.play.example.test` (no grant for the tenant), a tenant with a grant on Survival (VIEW, then console and
files, then power), the project Network with the tenant as member, and caps of 5 instances, 8 GB memory, 50 GB disk
and 3 databases. Shots: `docs/hohenheim-ux-mockups-2026-09-30/shots/w9b/` (workspace repo), `before-*` and `after-*`.

Seen against the boards, and what was done:

- Manage-Home: flat sidebar (Overview, Apps, Domains, Team for the tenant; Databases too for an operator), the
  attention band carries the tenant's blocked app in host-free words, the usage card bars, "Put something online"
  for the operator and not for the tenant: as the board. The Apps band read "Apps": it now reads "Your apps" (the band
  takes its heading from its placement as data, `AppsBand`, so the operator's dashboard keeps "Apps"). The two
  columns were even: zenit-widget's columns widget gained a `split` (`ColumnSplit`, EVEN or LEAD, one declaring home,
  an unknown token refused), and both dashboards' lower bands lead 3 to 2 as boards Main and Manage-Home draw them;
  narrow screens still collapse to one column.
- Manage-App: tabs follow the grant (VIEW: Overview, Configuration, Metrics, Backups, Access; console and files add
  their tabs); no host name in any tenant page (checked by fetching every tab). Defects fixed: (1) the overview offered
  "Add address", "Protect a path" and a link per address to a tenant who holds no grant on the serving site, each
  answering "page not found"; every door of the app overview (`AppOverview.opens`) is now drawn only when the
  panel's own read of that entry reaches the record and the tab is open for it (`PartsReads.loadRow`,
  `RecordTabGate.judge`), so the operator keeps them and the tenant reads the address as text. (2) "What you can do
  here" said changing access was up to the operator while the Access tab let the VIEW holder share it (every
  instance capability is delegable): the card now asks the tab's own gate
  (`GrantAdministration.mayAdministerRecordAccess`) and says "Share access" / "Removing this app." (3) The board's
  Restart was missing on /manage ("power without restart" carried no reason): the delegated action set now places
  Restart beside Deploy and Stop, gated by POWER like them, its refusal in the delegated words.
- Cluster pages: Domains has four tabs for the operator (two for the tenant without a site grant: certificates and
  access lists), Team has Projects and Members; the Apps toolbar's related-pages menu lists Sites, Instances, Instance
  templates, Preview deployments, Git providers.

Found, not fixed here (each its own issue):

- zenit-auth: the sharing picker (`za-record-sharing`) throws `UnsupportedOperationException` in
  `AuthCapabilityMatrix.addSubject` when the Access tab was reached by a client-side tab switch (the rows or removed
  list arrive immutable); a full page load works. Not fixed: zenit-auth holds unpushed commits of another checkout.
- The /manage instance list's rule-filter vocabulary carries `server_id` ("Host"), so a tenant can filter their own
  instances by host id: the numeric host identity InstanceOverview censors.
- Activity rows name records by id ("changed who may use Instance #1", "set the password of User #2").
- On /manage an operator reads "Your operator has to clear this" about their own app (the delegated words assume a
  tenant reader).
- The /manage instance entry offers no delete, so a DESTROY grant is listed under "You can" with no door.
- The admin dashboard's checklist ticks "Put your first app online" while nothing runs; "Site Survival web / Down" in
  the admin attention band reads raw.
- Needs building (no backing data): the players line, a tenant-safe Recent band, the tenant Resources card, the
  operator's name in "Need more?", a running app's Restart/Stop on this machine (no admissible host here), the
  board's "project Network" lead line on the app page.
- Proof: zenit-widget run 811 (ColumnsWidgetTest, WidgetDisplayRenderTest, WidgetDisplayFunctionsTest); Hohenheim run
  812 (nine classes, one red: InstanceRecordRouteTest pinned the delegated verbs without Restart, updated) and the
  green run 814 (InstanceRecordRouteTest, ManagePanelJourneyTest, LandingDashboardSurfacesTest,
  InstanceSurfacesBrowserTest).

## 24. D7f decisions (fork D7f, 2026-10-08)

The /manage defects W9b2 left (section 23, "Found, not fixed here"), each fixed at its cause.

- Host privacy: the tenant twin never speaks a host. `HostFields` (Hohenheim, one declaring home) names the fields of
  each model that name or select a host: an instance's `server_id` and `migrate_target_id`, a database's `server_id`
  and `engine_id` (one engine per host and engine kind, so its id selects a host). Every /manage twin over such a model
  withholds them through zenit-cms `PanelResource.Builder.withholds` (new): a hidden field access for every viewer, so
  the rule vocabulary, sort, search, facets, cells and the panel record source drop them in one rule.
  `ManagePanel.declareEntries` refuses to boot a twin that does not withhold its model's host fields, and
  ManagePanelJourneyTest fails while a /manage model relates to a host (`ServerModel`, `DatabaseEngineModel`) through
  a field `HostFields` does not name. A tenant's `?q=server_id = N` is refused or ignored, never honoured, and a host
  sort is ignored; the operator's own list still filters by host. The Apps store already left the host out.
- "Open site" hides while the app is not serving: the verdict (`AppHealth`) now carries a serving half beside its
  words, decided at each branch (an ATTENTION verdict can mean "cannot start" or "a path is open"), and Open site reads
  it for sites and instances on both panels. This widens the 2026-10-06 rule (hidden while BROKEN) to an app that
  cannot start, is stopped, failed its install or whose site answers an error page.
- Who clears a placement refusal decides its words (`OwnedInstances.placementReason(refusal, delegated, viewer)`,
  `mayClearPlacement`): on /manage an operator reads "Open the host in the admin panel and use Check and admit", a
  tenant "Your operator has to clear this"; the admin panel keeps the gate's own sentence. The dead power buttons say
  the same through zenit-cms's reader-aware `disabledWhen((row, access) -> ...)` (new; `PanelAction.disabledFor` now
  takes the reader), because a state-only presentation could not name the reader. Check host's own offer asks the same
  predicate.
- Destroy has its door: the /manage instance twin places the verified destroy as its delete, the same operation and
  dialog as the operator's. It is offered live to a holder of `destroy` and dead, in the gate's own
  "instance_not_permitted", to everyone else, as the operation is everywhere: a hiding delete authority was tried and
  dropped, because the API's delete runs through the admin entry and answered a bare 403 instead of the gate's 422
  (InstanceApiTest). The "What you can do here" card leaves to the operator only what the reader cannot do: removal
  without `destroy`, access without the Access tab's gate, and nothing when both are held.
- The first-run checklist's "Put your first app online" is done only while an authored app runs or a website serves
  its visitors (`AppHealth.anyOnline`, the verdict's serving half). This replaces the 2026-10-05 rule that any website,
  even switched off, counted: a site whose workload cannot start ticked the step while nothing ran.
- The /manage landing is headed "Your services" (the panel's title, `ManagePanel.TITLE`), its sidebar row still
  Overview, through zenit-cms `PanelDashboard.heading()` (new). en and nl copy already existed; the new sentences
  (`deploy_blocked_operator`, `operator_decides_access`) are in both.
- Proof: zenit-cms runs 4 (red: two panels registered in one renderer test) and 6 (green: PartsFormListFactsTest,
  DashboardPageRendererTest, RecordHeadJourneyTest, ChildListJourneyTest); Hohenheim runs 5 (red: the hiding delete
  authority, InstanceApiTest; a guessed delete marker) and 7 (green, 21 tests: ManagePanelJourneyTest,
  ManageScopeParityTest, InstancePowerAvailabilityJourneyTest, InstanceDatabaseSurfaceTest,
  InstanceSurfacesBrowserTest, LandingDashboardSurfacesTest, OnboardingChecklistTest, DashboardRoleGatingTest,
  InstanceRecordRouteTest, SiteRefusalVerdictJourneyTest, ManagePanelSurfacesBrowserTest, InstanceApiTest). The runs
  ran in a sibling workspace at the pushed heads (`zenit-workspace-d7f`): the local zenit checkout holds another
  session's unpushed commits and lacks the upstream ones zenit-forms and zenit-cms now compile against. Hand-edited
  baselines: `instances.txt` (the tenant's delete row) and `landing-dashboards.txt` (the /manage title, the checklist
  step TODO).

## 25. D7e decisions (fork D7e, 2026-10-08)

Host page, admin attention band and the faulted site's band, each fixed at its cause.

- What a preflight check found reads as words, through one vocabulary beside the check names: `PreflightFinding`
  (server/host), worded by `finding_<token>` (scope `host_check`) in en and nl. Both batteries build every check with
  `Check.of(name, status, required, finding.with(evidence...))`; `HostPreflight.store` keeps the finding's token and
  its evidence (`finding`, `finding_args`) beside the `detail` text, which is now the finding in the installation's
  content locale (the stored-reason rule of D7b), so the API's text is a sentence too. The host page reads the
  finding in the reader's locale (`PreflightFinding.wordsOf`); a token this build does not declare, a check written
  before findings, or a hand-written fixture check reads its stored text verbatim, never another finding. Evidence
  (versions, a uid map, a probe's error message) travels verbatim; the `failure` argument of an unreachable daemon is
  the probe-failure token and reads as `HostProbe.FailureKind.label`. The raw tokens of the D7d screenshot ("nft
  missing", "no LSM") were a journey's fixture text; the real batteries wrote long English prose ("no LSM confines the
  probe container (pid 1 label: '<none>'); AppArmor/SELinux add no layer"), which is what the operator now reads in
  words. HostCheckAndAdmitJourneyTest binds every finding to copy in en and nl, and HostPreflightBatteryTest fails a
  Docker battery check stored without a finding.
- A measured fact reads by its name in words and its value with its unit: `HostFact` (server/host) is the one home of
  the fact keys both batteries write (`fact_<token>`, `HostFact.Unit`); a byte count reads through `ByteText` ("63.0
  GB"), the formatter the Hosts list's memory bars use. `HostPreflight.MEM_TOTAL_FACT` and
  `IncusPreflight.PROBE_IMAGE_FACT` are aliases of their members. A stored key this build does not declare keeps its
  spelling and raw value. The overview's capacity facts ("Booked", "Budget", "Bookable") now read through the same
  `ServerParts.sizeOfMegabytes` the list uses instead of a hand-written "N MB".
- An advisory check that did not pass reads as advice in the warning tone, never "Failed": the verdict a reader is
  shown is `PreflightStatus.shownFor(required)` (a fact on the member), so the Incus battery's unanswered advisory lsm
  (stored FAIL) reads "Advice" like the board. The report's overall verdict is the same vocabulary's word ("Passed",
  "Failed", `HostPreflightReportView.summaryLabel`) in the card header's action slot, apart from "Last preflight ...";
  the duplicated `verdict_passed`/`verdict_failed` entries of scope `server_overview` are deleted in its favour.
- The admin attention band's down site reads like every other item: "Visitors of <site> get an error page", the
  site verdict's own reason as the detail (`AppHealth.siteHealth`, e.g. why its app cannot start), "Open <site>" as
  the action. A degraded site reads "<site> answers, but not reliably". A site the proxy turns away over its settings
  is DOWN too but already has its routing-problem item, so it is not drawn twice. The old `site`/`down`/`degraded`
  entries of scopes `attention_title`/`attention_detail` are deleted in favour of `site_down`/`site_degraded`.
- A site the proxy turns every visitor away from offers its fix: "Change its configuration" (`SiteActions`
  `site_change_configuration`, a row link to the site's Configuration), shown while the running proxy records a
  refusing problem for it (`AppHealth.refusedBySettings`). Every refusing reason is the site's own settings (what it
  serves, its upstream, its sign-in provider). The app page of a workload that speaks for the site carries it through
  `RecordHealth.on`. /manage's form cannot change what a site serves, so the delegated verdict offers no fix.
- Walk (scratch server from the built jar, fresh database, `/setup`, the local host checked through Check and admit;
  a down site serving an app that cannot start and a faulted site naming no app seeded by SQL): shots
  `docs/hohenheim-ux-mockups-2026-09-30/shots/d7e/` (workspace repo). The real Docker battery on this machine reads
  "nftables refused a firewall change: sudo: a password is required", "Docker 29.8.1 answers", "Room for another
  container network (5 in use)"; the advisory userns/lsm checks read "Advice"; Memory reads "31.0 GB"; the dashboard
  band reads "Visitors of Survival web get an error page / Host local is not admitted for placement; ... / Open
  Survival web"; Shop front's band offers "Change its configuration", which opens its Configuration form.
- Found, not fixed here (each its own issue):
  1. zenit core: a toast drops worded arguments. Check and admit's refusal reads "These checks must pass first:
     [check_nftables]" because `Flash.stash` encodes every message argument as `String.valueOf` (FlashEncoding), so a
     `List<Microcopy>` (or a Microcopy) argument reaches the toast as its key. The fix is zenit's (resolve or encode
     worded arguments structurally); zenit cannot be pushed while it holds another checkout's commits.
  2. A check's "How to fix" is per check, not per finding: Firewall control failing on "sudo: a password is
     required" still says "Install nftables on the host"; the fix should follow the finding (grant the helper's sudo
     line) once findings carry their own fix.
  3. D7d's check name "Root in a container is not root on the host" reads as a claim beside "Advice"; a neutral name
     ("Container root is remapped") would read right in both states.
- Proof: build run 817; Hohenheim test run 818 green on the first run (29 tests: HostCheckAndAdmitJourneyTest,
  HostsPageJourneyTest, SiteRefusalVerdictJourneyTest, HostPreflightBatteryTest, HostEvidenceTest,
  ServerOverviewTest, DeclaredMicrocopyKeysTest, MicrocopyManifestDriftTest, MicrocopyCatalogParsesTest,
  MicrocopyCatalogScopeTest). The slow live classes HostPreflightTest and LiveRemoteHostTest now assert the finding's
  evidence (`found().args()`, the finding member) instead of the retired English prose; they need a live host and
  did not run here.

## 26. D8 decisions (fork D8, 2026-10-08)

The admin dashboard showed one cause several times (D7e shot 04): an unadmitted host in two checklist steps and two
attention items, and the backup destination in a step and an item. Each problem is now shown once, at its root, with
its one action.

- Home: Hohenheim. `zemble home` pointed at generic CMS routing (noise); the attention item, its collectors, the
  checklist and the dashboard are Hohenheim's (`AttentionItem`, `AttentionCollector`, `OnboardingCollector`,
  `AdminDashboard`), and zenit-cms has no attention item (its `RecordHealthReads.Notice` reads one entry's verdicts and
  has no checklist or host relation). The cause joins the verdict as its third half beside D7f's serving half, in
  `AppHealth.Verdict`, not on zenit-cms `RecordHealth`.
- Checklist vs band: the checklist wins while it shows (board Empty-Dashboard draws open steps and no band; board Main,
  without the checklist, draws the backup and the waiting host as attention). One declared relation:
  `OnboardingStage` (common, one member per step) and `AttentionItem.stage`, the stage whose open step states the
  item's condition. An open step presents the first item of its stage (`OnboardingStep.presenting`: the item's
  detail, what it holds back, its target and worded action under the step's own title), and
  `DashboardAttention.fold` leaves that item out of the band, asking the same `OnboardingCollector.presentedBy`. The
  band's all-clear is not drawn under an open checklist (it would contradict the step).
- Root cause: `AttentionItem.about` / `causedBy` (`AttentionSubject`, a model id and a record id). The site-down item
  takes its cause from the app verdict (`AppHealth.Verdict.heldBy`, set at the two placement-refusal branches through
  `OwnedInstances.placementHost`, which Check host's route now reads too); the host item names what it holds back
  (`held_back`: "2 apps wait for it", from `AppHealth.heldBackByHost`, the instance verdicts' cause half). An item
  caused by a shown root (in the band or presented by a step) folds; one whose root nobody shows stays. The app pages,
  the Apps list and the Hosts list's band keep every verdict and item unfolded.
- A host that is admitted (or cordoned) but still refuses the apps placed on it (a posture refusing tenant
  workloads, an unaccepted posture, a required check that no longer passes) is their root too
  (`HostAttention.hostsHoldingAppsBack`, "local cannot run apps yet", the gate's words, "Open local"): the walk found
  its apps' items repeating the host's refusal with no root to fold under. A host holding nothing back raises nothing.
- One step per stage: enrolment is done once a host is stored and names it ("local, Docker", board Empty-Dashboard),
  admission is the one open host step (title "Let it run apps", done words "Its checks passed, so apps can run
  here."); `checklist_host_pending` is deleted (replacement: the admission step presenting the host item). A done
  backups step reads "Copies go to <target>." instead of the to-do sentence. The control-plane backup item reads the
  board's words ("Backups stay on this machine").
- Not changed: the checklist still retires only when every step is done. Board Main has no checklist while backups
  are unconfigured ("Before your first app"), which would retire it at the first app online; that is a product
  decision left open.
- Walk (scratch server from the built jar, fresh database, `/setup`; two Docker apps with sites seeded by SQL on the
  waiting local host): shots `docs/hohenheim-ux-mockups-2026-09-30/shots/d8/` (workspace repo). 01 fresh install: no
  band, enrolment ticked, admission and backups each presenting their item. 02 two apps: "2 apps wait for it" on the
  admission step, no site item in the band. 03 (found in the walk, before the admitted-host root): admitted with a
  trusted-only posture, both sites repeated the host's refusal. 04 the same state after the fix: the step presents the
  host's root ("Open local", 2 apps wait). 05 posture accepted through the UI: the required nftables check (sudo on
  this machine) still refuses, in the gate's words, once. Real Check and admit cannot admit this machine (D7e's
  nftables finding), so the admitted state was set by SQL; the fully placeable state is the journey's step 5.
- Found, not fixed here: the host item's and step's target `CmsRoutes.detail(servers, id)` opens the host's
  Configuration tab, not its Overview where Check and admit's result reads; the gate's preflight-posture refusal
  ("Host 'local' last passed preflight under a different posture: the nftables check FAILED ...") is raw English with
  a check token, not worded copy.
- Proof: Hohenheim test runs 823 (green, 37 tests) and 827 (green, 37 tests, after the walk's admitted-host root and
  journey step 4): DashboardAttentionJourneyTest (new), OnboardingChecklistTest, LandingDashboardSurfacesTest
  (baseline hand-edited: the operator checklist's enrolment ticked, admission and backups presenting their items, no
  band), AdminPagesTest (the waiting host is linked once, by the step or the band), DashboardRoleGatingTest,
  SiteRefusalVerdictJourneyTest, HostsPageJourneyTest, InstancePowerAvailabilityJourneyTest, NavigationTest,
  ManagePanelJourneyTest, DashboardVocabularyDriftTest and the four microcopy gates.

## 27. D9 decisions (fork D9, 2026-10-08)

Places where the admin said something untrue on Starfleet (DEP9), each fixed at its cause.

- A host's standing is ONE verdict of the placement gate: `InstancePlacement.hostRefusal` asks of a host what
  `chooseForBucket` asks of every host (the admission gate for every isolation a kind on its runtime provides, with
  the owner of a workload already there, then the memory budget the chooser rations against), minus the kind's own
  question. `HostVerdict` (server/cms) words it for the Hosts list's state cell, the host page (badge plus a warning
  band with the reason), the host attention item and the checklist's admission step; `HostStanding` (common) is its
  vocabulary: Waiting for its checks, Takes new apps, Cannot take new apps (new: admitted, but the gate refuses), Takes
  no new apps. Starfleet's local host (memory measured 2026-08-29, past the 168-hour bound) read "Takes new apps"; it
  now reads "Cannot take new apps" with the gate's words, and raises its item with "Check again".
- What clears a refusal is a fact on the refusal: `PlacementRefusal` (server/host) declares every placement refusal
  key once with its `Remedy` (Check and admit, Check again, open the host); `HostAdmission`'s throw sites and the
  chooser's capacity refusal word themselves through it. A refusal it does not declare opens the host.
- One host item per host (`HostAttention.hostsTakingNoApps`, replacing `hostsNotAdmitted` and `hostsHoldingAppsBack`):
  a waiting or refusing host always raises it; a cordoned or taking host only while apps wait on it (D8). Its detail
  is the verdict's reason, so the list cell and the item say the same words ("Never checked yet" for phoenix, "1
  required check failed: Firewall control"); the attention-only `host_not_admitted`/`host_checks_failed` details are
  deleted in favour of the `host_list` ones. The item and the step link the host's front door,
  `CmsRoutes.open` (`/admin/servers/<id>/open`, the Overview), not its Configuration form.
- The check-now-required refusal names the check and what it found in words (`HostPreflight.checkLabel`, moved there
  from ServerOverviewState so the gate can use it, and `HostPreflight.storedFinding`): "local does not pass its
  Firewall control check, which must pass for apps to run there: nftables refused a firewall change: sudo: a password
  is required. Fix it, then Check again". The old sentence claimed a posture history the record cannot prove for a
  Docker check.
- "Blocked now" is `BanModel.blockedNow` (enforced, never lifted, not past its expiry; the clock through `Now`): the
  list's filter (a `blocked_now` rule variable in the ban source's vocabulary, replacing the `active` leaf), its
  default, the state cell, Lift's availability, the dashboard's ban tile and BanService's own active-ban reads. An
  expired ban the sweep has not cleared yet reads Expired, offers no Lift and is not under "Blocked now".
- The Apps list's HTTPS column (and the dashboard's Apps band) is the app's main address's HTTPS through the
  Addresses list's own cell (`DomainParts.certificateCell`, `DomainCertCell`): "Works", "Not working", "No
  certificate" with the same reason; never the app verdict's "Error page". A site's all-names summary
  (`SiteTlsCell`) stays the Sites list's.
- The host's hourly "System changed local Server" rows were `HostProbe.recordSuccess`'s heartbeat saves, written
  before W5b declared them internal (`ActivityLog.suppressed`); Starfleet's log has none after 2026-10-07 01:41 UTC
  (the W5b deploy). The rows stay in the log; the host page's Recent card reads what a person did to the host
  (`AdminActivityResource.peopleOnlyFor`, the dashboard's people-only scope narrowed to the record), so they no longer
  fill it.
- Words from copy: the posture reads "Shared containers" (no "(operator risk)"), the accepted risk "Accepted by
  <actor>" (the warning version is bookkeeping), and the daemon line "Docker 20.10.5+dfsg1, seen 3 minutes ago"
  (`HostState.OK` words itself through `state_ok` with the daemon, like a silent host).
- Walk (scratch server from the built jar, fresh database, `/setup`; local admitted with a memory reading from
  2026-08-29, phoenix never checked, an active and an expired-but-active ban, a forced and an unforced address without
  a certificate, hourly system rows and one person row on local, all seeded by SQL): shots
  `docs/hohenheim-ux-mockups-2026-09-30/shots/d9/` (workspace repo).
- Found, not fixed here: the toast of a refusal carrying worded arguments still shows their keys (zenit `Flash`,
  D7e item 1; zenit cannot be pushed from here). The REFUSING item's title keeps D8's "<name> cannot run apps yet",
  which reads oddly for a host already running apps.
- Proof: build run 829; Hohenheim test run 830 (29 classes, 106 tests: 105 passed, ServerOverviewTest step 5 failed
  because its fixture row was system bookkeeping, now a person's act beside a bookkeeping row the card must leave
  out) and run 831 (ServerOverviewTest, 8 passed). Journeys: HostsPageJourneyTest (new
  `aHostThePlacementGateRefusesSaysSoEverywhere`), DashboardAttentionJourneyTest step 6, AccessPagesJourneyTest step
  5, AppsListJourneyTest step 3, AdminPagesTest (the host link is `/open`), BanStateSurfaceTest,
  RecordAwareQueryGateTest, DashboardVocabularyDriftTest; LandingDashboardSurfacesTest's baseline hand-edited (the
  admission step reads "Never checked yet" and links `/admin/servers/#/open`; an app without an address has no HTTPS
  badge). The push's duplicate gate then pointed the ban vocabulary at core's `RuleVocabulary.extend`; run 832
  (AccessPagesJourneyTest, BanStateSurfaceTest, RecordAwareQueryGateTest, SecurityAdminTest, 7 passed) proves it.

## 28. D10a decisions (fork D10a, 2026-10-08)

The admin dashboard and the Apps list brought closer to boards Main, Empty-Dashboard and Apps-List.

- Checklist lifetime (decided by the orchestrator, board Main): the checklist retires once the first app is online,
  whatever its other steps say. `OnboardingCollector.retired()` is the one rule (D7f's `AppHealth.anyOnline`, the
  put-online step's own fact); `DashboardAttention.read(items, retired)` folds against an empty checklist once retired,
  so the fold applies only while the step shows and what an open step stood for (backups that stay on this machine, a
  host that takes no new apps) is the band's item from then on. This closes D8's open product decision. Its heading and
  lead read board Empty-Dashboard's "Before your first app" / "Each step ticks itself off."
- Host attention title (decided): a fact on the standing, `HostStanding.attentionTitle`: WAITING "<host> cannot run apps
  yet", REFUSING and PAUSED "<host> takes no new apps" (`host_takes_no_new_apps`), and TAKING (raised only while the
  gate refuses some apps placed on it) "<host> cannot run some of its apps" (`host_holds_apps_back`, the exhaustive
  member's third wording; an assumption, no board draws it). en and nl.
- Count tiles (board Main): Apps, Hosts, Certificates and Backups replace Sites, Certificates, Access lists and Active
  bans. Hohenheim's own `HohenheimWidgets.STAT` (`DashboardStat`, `widget-stat.hwk`, pl-stat-card's description), built
  by `DashboardStats` through the panel's own lists (`AppDirectory.listed`/`offers`): a tile exists where its list is
  registered and admits the viewer. Lines are facts the counted list shows: Apps "2 live, 2 with a problem" (verdict
  tones, UNKNOWN neither), Hosts "1 takes new apps, 1 refuses new apps, 1 waiting" (`HostStanding.tally`, declaration
  order), Certificates "N need attention" (state cell not SUCCESS) else "Next expiry in N days", Backups "<ok> of
  <total>" over workloads with a backup target, "Newest 8 hours ago, 4.2 GB", or "0" / "No app has a backup target".
  `InstanceBackupModel.newestOf` is the one newest-backup read (InstanceAttention reuses it). The blocked-addresses count
  is the Blocked addresses list's now (SecurityAdminTest updated).
- Copy: the four dashboard details that read "(s)" (`docker_foreign`, `docker_orphans`, `docker_colliding`,
  `ports_releasing`) are `.match` plurals in en and nl; a serving site with only patterns reads "Answers every address
  matching **.example" (`catches`, `AppHealth.liveWords`), never "Live at <pattern>"; the Apps list's website runs on
  the host of the instance serving it (`AppDirectory.servingInstancesOf`, operator panel only) and a website no instance
  serves draws the board's dash (`ColumnSpec.absent`, `host_none`), never "None".
- Needs building (no backing fact today): the board's "succeeded last night" (a backup row knows when it ran, not which
  night's schedule it served); Backups counting managed database dumps and the control-plane archive (the tile counts
  instance backups only); "1 renews in 12 days" (the renewal threshold is AcmeService's private
  RENEWAL_THRESHOLD_DAYS, not a fact a reader can ask, so the tile says the next expiry); the Apps band's trailing
  live facts ("23 players", "Deploying").
- Walk (scratch server from the built jar, fresh database, `/setup`; then by SQL: local admitted with a fresh memory
  reading and shared containers accepted, phoenix admitted with a 2026-08-29 reading, daystrom never checked, a Docker
  app Shop on local with a site, Docs on phoenix with a site, a catch-all redirect on `**.d10a.test`, a redirect
  example.org, one certificate, a backup target with one complete 4.2 GB backup, eight foreign and one orphaned Docker
  finding): shots `docs/hohenheim-ux-mockups-2026-09-30/shots/d10a/` (workspace repo). 01 fresh install (board
  Empty-Dashboard), 02 first app online: no checklist, "phoenix takes no new apps" (1 app waits for it), "daystrom
  cannot run apps yet", the plural Docker details, "Backups stay on this machine" and the four tiles, 03 Apps list
  (dash hosts for the redirects), 04 the catch-all's overview. The Shop container never existed, so the reconciler
  marked it stopped after an error; the online apps of shot 02 are the two redirects.
- Found, not fixed here: the "HTTPS is unavailable" item listed Catch-all and Shop as sites "that force SSL" though
  neither name forces HTTPS in the seed; AppOverviewJourneyTest step 1 had been red since D7f/D9 (a stopped workload on
  an unplaceable host cannot offer Open site), fixed in its fixture; fifteen violation sentences outside the dashboard
  still read "(s)" (`server_in_use`, `template_in_use`, ...).
- Proof: build runs 833 and 837; Hohenheim test run 834 (45 tests, 42 passed: the /manage dashboard NPE from
  `Map.of().get(null)` in the new website host read, the dash assertion's markup, AppOverviewJourneyTest's stale
  fixture), run 835 (8 tests, 7 passed: the host cell's whitespace in the new assertion) and run 836 (AppsListJourneyTest,
  green). Journeys: DashboardAttentionJourneyTest (own database; steps 1/6 titles, 7 retirement, 8 tiles),
  OnboardingChecklistTest (retired), AppsListJourneyTest (steps 9 host, 10 catch-all), HostsPageJourneyTest,
  ForeignResourceAttentionTest (plurals en and nl), AdminPagesTest, NavigationTest, SecurityAdminTest,
  DashboardVocabularyDriftTest, LandingDashboardSurfacesTest (baseline hand-edited: the checklist's board words and the
  four tiles with their lines), ManagePanelJourneyTest, AppOverviewJourneyTest, InstanceAttentionTest and the four
  microcopy gates.

## 29. D11 decisions (fork D11, 2026-10-08)

The dashboard defects D10a's walk left (shot `d10a/02`), each fixed at its cause.

- Home: Hohenheim. `zemble home` pointed a task label at zenit core (`TaskCatalog`/`ScheduledTask`), which this fork
  may not push; the label is Hohenheim copy instead (below). The fold is D8's mechanism, the refusal words D9's.
- One cause, one item: the app verdict's cause half is a record, `AppHealth.Verdict.cause` (`AttentionSubject`:
  a host, a workload, an address), no longer only a host id. A site whose workload does not run names the workload
  (`AttentionSubject.instance`); a site whose address is forced to HTTPS without a certificate names that address
  (`AttentionSubject.address`). The crashed-workload item and the failed-deploy item are `about` their workload and
  say what they keep from visitors (`sites_held_back`, "Visitors of its site get an error page", from
  `AppHealth.sitesHeldBack`); the forced-address item is `about` its address. The site's error-page item folds under
  whichever is shown. A stopped workload raises no item of its own, so its site's item stays (a fold needs a shown
  root). A failed or degraded HTTPS listener is the root of the forced sites' refusal: it carries them as what it
  holds back (`AttentionItem.holding`) and the "HTTPS is unavailable" item stays silent while it speaks.
- HTTPS is unavailable: the list was right about behaviour and wrong in words. `SiteDispatcher.forcesHttps` counts the
  global Force HTTPS setting (on by default) for every pattern and for every exact name an ACTIVE certificate row
  covers, so D10a's catch-all and Shop (a seeded active certificate with no loadable material) did refuse plain HTTP,
  but they force nothing themselves. `SiteDispatcher.forcedSites` splits the sites by what sends them (`own` vs
  `bySetting`), the item names each by its cause (`https_refused_forced` / `_setting` / `_both`), and its detail says
  why nothing can be served: no certificate stored, or N active certificates that could not be loaded.
- Failing task: titled "<task label> failed" (`task_failed`), the label being `TaskWords.label`, catalog copy keyed by
  the task id's path under `task_label`, shipped in en and nl for every task the catalog holds (framework tasks
  included); `DashboardAttentionJourneyTest.everyTaskAndEveryCountReadsInWords` fails on a task without one. The
  detail is the run's failure in its own words (`TaskWords.failure`: the stored error without the exception class or
  stack trace; a stored refusal read back through `HohenheimViolations.storedText`), the action "Show the run" leads
  to the run's page (`TaskAdmin.RUNS_SLUG`), which offers Run now. `task` (attention_title) is deleted (replacement:
  `task_failed`).
- Placement refusals in words: every `PlacementRefusal` message is reworded in en and nl without quotes, check tokens
  or capitals for emphasis (key unverified, quarantined, the postures by their own names, dedicated, kernel lane
  missing/unproven with the check's label, contact lapsed as a plural). Two remedies join `PlacementRefusal.Remedy`:
  `CONFIRM_KEY` ("Confirm its host key", KEY_UNVERIFIED) and `REVIEW_KEY` ("Compare its new host key", QUARANTINED);
  `HostVerdict.remedyAction` words them (exhaustive switch). The item links the host's Overview, where Scan host key
  and Confirm fingerprint head the page beside the SSH host key card.
- Plurals: the fifteen "(s)" sentences (and the DNS health toast's "secundaire(n)") are `.match` plurals; a sentence
  counting several things carries each count as a counted noun (`HohenheimCounts.of`, scope `count`: stacks,
  databases, engines, instances, port_claims, backups, templates, sites, access_rules). The journey scans both shipped
  catalogs for pseudo-plurals.
- Walk (scratch server from the built jar of run 840, fresh database, `/setup`; then by SQL, because hosts cannot be
  admitted on this machine: local admitted with a fresh memory reading and shared containers accepted, phoenix an
  admitted SSH host whose key nobody confirmed, daystrom never checked, a Docker app Shop on local in status error
  with a site on shop.d11.test, a catch-all redirect on `**.d11.test`, a static Docs site forcing HTTPS on
  docs.d11.test, one active certificate row for shop.d11.test without PEM; the isolation task failed on its own):
  shots `docs/hohenheim-ux-mockups-2026-09-30/shots/d11/` (workspace repo). 01 the dashboard: one Shop item (the
  workload's, "Visitors of its site get an error page", Open the console), HTTPS unavailable naming Docs by its own
  forcing and Catch-all, Shop by the setting with why, "phoenix takes no new apps" in words with "Confirm its host
  key", "Check app isolation failed" with its reason and "Show the run"; 02 that run's page with Run now; 03 phoenix's
  Overview. The Docs "refuses requests" item in shot 01 is the seed's (a static site without an owner is a tenant's).
- Found, not fixed here: the task runs and schedules screens (zenit-cms `TaskAdmin`) title rows with the raw task id
  and show the stack trace; the label belongs on zenit core's `TaskDescriptor`, which this fork may not push, and the
  stored error has no message column apart from the trace. The isolation sweeps' own failure text ("isolation
  UNCONFIRMED") is English developer prose that alerts and tests key on. The Apps list's HTTPS badge reads "Works" for
  a name an active certificate row covers even while no certificate could be loaded (the D9 rule reads the row).
- Also: the workload isolation sweep's own "N workload network(s)" reads as a real plural.
- Proof: build run 840; test run 841 and 843 could not run (zenit-cms red upstream of another session, then a stale
  `heldBy()` call in the journey), run 846 (31 classes, 91 tests: 88 passed; DashboardAttentionJourneyTest step 5
  still expected no cause where the site now names its stopped workload, MicrocopyCatalogScopeTest did not claim the
  `count` and `task_label` scopes, ForeignKeyIntegrityTest read the engine count as a number) and run 848
  (DashboardAttentionJourneyTest, MicrocopyCatalogScopeTest, ForeignKeyIntegrityTest: 5 passed). Journeys:
  DashboardAttentionJourneyTest (new `oneCauseIsOneItemCarryingItsRootsAction`, steps 1-8, and
  `everyTaskAndEveryCountReadsInWords`, steps 1-3), InstanceAttentionTest, IsolationVisibilityTest step 6,
  WorkspaceDeploysTabTest step 2b, HostRemovalAgreementTest, BackupTargetRemovalTest and the four microcopy gates.
