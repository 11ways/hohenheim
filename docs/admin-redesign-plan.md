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
