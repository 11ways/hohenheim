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

## 30. KVM1 decisions (fork KVM1, 2026-10-08)

The risks a read-only review found in the live VM screen (491030b7), each verified against code and Incus source
before it was fixed.

- Home: `zemble home` answered EXTEND_EXISTING `ScreenSession` in zenit-kvm for the viewer cap, so the cap, the FULL
  status and the rejoin are framework (zenit-kvm); Hohenheim only declares its bound. Pepperglass needed no change:
  `ChannelSettings.videoCodecs` already decides what a session announces.
- Incus operation leak, verified in Incus `cmd/incusd/instance_console.go`: a `type=vga` console operation ends only
  when its CONTROL websocket closes (`doVGA` reads it until it errors, then closes every channel socket). Incus 6.0
  through 6.16 wait for that socket forever, so every screen session left a running operation behind; current main
  fails the operation 10 seconds after start without it and closes the SPICE channels, so there the screen died
  after 10 s. `DELETE /1.0/operations/<id>` cancels such an operation (it has an `onCancel`) and is refused once it
  ended. Fix: `IncusSpice.open` links the control socket right after the POST (cancelling the operation when it cannot
  link); `IncusSpice.Console.close` closes it and then cancels, idempotently. The seam hands over a `VmSpice.Link`
  (options plus release) and `SpiceScreenSource.close` releases it after closing Pepperglass, also when `start`
  failed or raced a close. The text console (`type=console`) needs no change: it ends when its data socket closes.
- One console per VM: `VmScreens.session` gets-or-opens under one lock, so two viewers opening at once share one
  session (journey step 1 opens two concurrently: one operation). The race that remained was a viewer looking a
  session up just before the last viewer's leave ended it: it read ENDED. zenit-kvm now asks the lookup once more
  when a join finds its session ended, so it gets the fresh one.
- Viewer cap: `ScreenOptions.maxViewers` (default 16, refused below 1); a viewer past it reads `ScreenStatus.FULL`,
  worded in en and nl, closes with 1013 and is never counted; its input is dropped. `ScreenStatus.endsScreen()` is the
  one home for "this status ends the screen" (ENDED, UNAVAILABLE, FULL), read by the browser viewer instead of its own
  list. Hohenheim: `VmScreens.MAX_VIEWERS = 4` (at most 128 MiB of queued frames per VM).
- Force stays: Hohenheim cannot tell a console an operator opened with `incus console --type=vga` from one an older
  Hohenheim leaked (those never end on 6.0-6.16), so a new session still forces. It never forces over its own live
  session (one per VM). Open question for the owner below.
- Capabilities: the source declares RESIZE; a controller's size goes to the guest agent as one monitor
  (`Session.setMonitors`), and `hh-vm-screen` sets `remoteResize`, so the guest follows the controller's element
  (as remote-viewer does); a guest without the agent keeps its size. Video: only MJPEG is announced to the SPICE
  server. Pepperglass fixes the announced codecs when the session links, which happens when the first viewer opens,
  before any viewer said what it decodes, and one stream feeds every viewer; VP8/VP9/H.264 would leave a viewer
  without WebCodecs looking at a hole. MJPEG frames go on as JPEG rects every viewer draws, so the source declares no
  VIDEO_* capability and the WebCodecs passthrough code is gone. `viewersChanged` stays the default no-op: nothing the
  link announced can follow the viewer set at runtime.
- Watch-only tier: not added. No Hohenheim capability fits: VIEW is "read a record's own state" and the journey pins
  VIEW-only as refused; letting it watch a live screen would change what every stored VIEW grant means. CONSOLE holders
  all get CONTROL, one driver at a time through the session's handoff.
- Live lane: `VmScreenLiveTest` did not run. Missing: `~/.config/hohenheim-livehost/incus.properties` (no enrolled
  Incus host on this machine), no `incus` binary locally, and Starfleet has neither Incus nor `/dev/kvm` (its servers
  are docker-only: `local`, `phoenix`).
- Look: the Console tab's chrome is unchanged (no screenshot); `remoteResize` changes the guest's size, and FULL is a
  new worded status inside `kvm-screen`, proven in `KvmScreenBrowserTest`.
- Proof: zenit-kvm build 850 and test run 851 (ScreenSessionJourneyTest with the new
  `aFullSessionTurnsTheNextViewerAwayUntilSomeoneLeaves` and `aJoinerOfASessionThatEndedBeforeItsJoinIsGivenTheFreshOne`,
  ScreenEndpointHttpTest, KvmCopyTest, ScreenCodecTest, KvmScreenBrowserTest with
  `aFullScreenKeepsSayingSoAfterItsConnectionCloses`: 31 passed). Hohenheim build 852 and test run 854 (IncusSpiceTest
  on the new `FakeVgaConsoleDaemon`, which models the control socket and cancel, SpiceScreenSourceTest,
  VmScreenJourneyTest with `oneConsoleServesAVmsViewersUpToItsCapAndEndsWithTheLastOneOrAFailedSource`, steps 1-5:
  6 passed).
- Open for the owner: whether a new screen session may take over a VGA console an operator holds (alternatives: try
  without force and say "someone else holds this console" with a take-over button); whether playback should stop
  announcing Opus (a viewer without an Opus AudioDecoder reads AUDIO_UNSUPPORTED; Pepperglass has no playback setting
  yet); whether RESIZE should be an opt-in per VM.

## 31. D10b decisions (fork D10b, 2026-10-08)

Addresses and Databases brought closer to boards Domains and Databases, and three dashboard leftovers of D11.

- Home: `zemble home` answered EXTEND_EXISTING zenit-cms for a list cell naming linked records. zenit-cms gains
  `RecordLinksCell` (`zenitcms:cell/record-links`, `CmsTemplateIds.CELL_RECORD_LINKS`): titles each linked to their
  record's page where the producer says the reader may open it, the titles as export text, and its own empty words
  (`RecordLinksCell.none`), because a renderer column ignores `ColumnSpec.absent`. And `ResourceList.widgetsBelow`, the
  widget slot under the list card (the slot above is `widgets`).
- Addresses: the App cell is a virtual `app` column (a computed cell cannot reflect a field) linking the site to its
  front door, `CmsRoutes.open` (`/admin/sites/<id>/open`, its overview; J1: a site's overview and its workload's are
  one composition), for a reader the Sites entry admits and `reachesRecord` VIEW lets open; the site's own Addresses
  tab hides it. The Domains cluster's tabs follow the board: Addresses, Certificates, DNS zones, Released addresses.
  The create verb reads "Add address" (`FormSpec.createTitle`, both twins).
- Databases: the lead reads "Managed databases your apps use, with their backups." and the create verb "Create
  database" (both twins). Used by names each app linked to its overview (`InstanceParts.recordRoute`, the overview
  card's own link), "No app" when none. The Engines card sits under the list (`widgetsBelow`): every
  `DatabaseEngineModel` row as "MySQL on daystrom", "1 database, active" (the counted noun and the status label's
  sentence variant), opening its engine. The board's per-engine badge and the card-per-engine look are a fact list in
  one card (the generic `FactListWidget`); the engine kind is already in the row's words.
- Dashboard titles by what happened, from the verdict: `AppHealth.Stoppage` is the one declaration of a workload's
  stoppage words, read by the instance verdict's headline and the attention item's title: "Shop stopped after an
  error" (ERROR; deliberately not "after a crash": a failed start, migration or maintenance hold stamps ERROR too) and
  "Shop's last deploy failed" (the stack verdict's "Its last deploy failed"). `instance_crashed` and `deploy` under
  `attention_title` are deleted (replacement: `stopped_after_error`, `deploy_failed` there).
- HTTPS works only where the proxy can serve it: `AppHealth.workingNames()` is the running proxy's certificate store
  (`CertificateStore.servedNames`, the names a loaded certificate answers a handshake for) while it terminates HTTPS,
  nothing while it cannot, and the ACTIVE rows only where no proxy runs in the process. `AppHealth.httpsOf` reads a
  covering certificate stored ACTIVE that the working names leave out as ERROR, and `DomainParts.httpsDetail` says
  why ("A certificate is stored for this name, but the proxy cannot serve it"). Every display reader of the working
  names switched (Addresses cell and lead, Apps list and band, app overview, Sites TLS cell, the verdict, Stop forcing
  HTTPS, the forced-address item). The store also stopped counting a row whose key is missing as loaded.
- Isolation findings in words: `IsolationFindings` keeps two readings. The failure message (the run's stored error,
  the dashboard detail) is copy under `isolation_finding`, resolved in the content locale ("Could not confirm that
  what runs on local is kept apart: per-app firewall rules are switched off there, so its 1 app network can be neither
  checked nor repaired"); the raw lines with their UNCONFIRMED token stay the machine reading, on
  `IsolationUnresolved.findings()`, in the alert body and in the run's reports (`publish(TaskContext)`). The workload
  sweep words why a host is unverifiable (`HostOutcome.why`: enforcement off, daemon unreachable); the Incus sweep's
  one reason is no firewall lane.
- Needs building: the engine kind badge and per-engine cards of the board's Engines card; "Attach to an app" and the
  other database-side items W9a listed stay open.
- Found, not fixed here: `RouteTableBuilder` and `ForceSslLatch` still read ACTIVE rows (`activeNames`), so a name an
  unloadable active certificate covers is still sent to HTTPS by the setting; the request action hides "Get a
  certificate" for such a name (it is the certificate's reissue). The crashed item's detail "The workload exited
  unexpectedly" claims a crash for every ERROR. The database tier's attention items still title themselves "Instance
  Shop" ("Attached database shop is not running"), the same developer shape the crash item had.
- Walk (scratch server from the built jar, fresh database, `/setup`; then by SQL, because hosts cannot be admitted on
  this machine and seeded secrets are encrypted with the scratch key: a Docker workload Shop in status error behind
  a site with shop/www.shop.d10b.test, a stopped Blog, a static Docs site, an ACTIVE certificate row for the Shop
  names without material, a MySQL engine on local with a shared database shop used by Shop and Blog, an unused
  dedicated old-blog; the isolation sweep failed on its own, enforcement being off): shots
  `docs/hohenheim-ux-mockups-2026-09-30/shots/d10b/` (workspace repo). 01 Addresses: the board's tab order, "Add
  address", App links (Shop opens the site's overview), the Shop names "Not working" with why; 02 Databases: the
  board's lead and "Create database", Used by "Shop, Blog" linked and "No app", the Engines card under the list; 03
  the dashboard: "Shop stopped after an error", "Check app isolation failed" with its finding in words, the Apps band
  reading "Not working" for Shop where it read "Works". The run stored the worded error and the raw UNCONFIRMED line
  in its reports. A failed deploy title is proven by the journey only (no application was seeded).
- Proof: zenit-cms test run 862 (ResourceListCopyTest, 1 passed; stale only for another session's zenit-cms files);
  Hohenheim build 861, test run 863 (28 ran, 26 passed: the journeys' markup assumptions, href before class and
  `data-cms-create>`, and "No app" because a renderer column ignores `absent`; the class filter's other classes did
  not run under fail-fast) and run 864 (20 classes, 57 tests, all passed). Journeys: DomainsListJourneyTest (steps
  3, 5 and the new 8), DatabasesPageJourneyTest (new steps 7-8), DashboardAttentionJourneyTest (steps 1, 3, 7),
  IsolationVisibilityTest (steps 2, 4), AdminNavigationJourneyTest (the cluster order), ManagePanelSurfacesBrowserTest
  (baseline hand-edited: the domains list's App column is `app`, no relation), MicrocopyCatalogScopeTest
  (`isolation_finding`) and the other microcopy gates, AppsListJourneyTest, AppOverviewJourneyTest,
  SiteRefusalVerdictJourneyTest, LandingDashboardSurfacesTest, InstanceAttentionTest, InstanceStatusReconcileTest,
  DatabaseAdminTest, AdminPagesTest, SharedDatabaseEngineTest, DashboardVocabularyDriftTest. The walk's jar is the
  build after run 865's thrash refusal (`--thrash-override`, invocation 20261008-112652: the chain had moved under
  another session's zenit and zenit-cms work).

## 32. CORE1 decisions (fork CORE1, 2026-10-08)

The framework defects sections 19, 25 and 29 left open, fixed at their causes in zenit core and zenit-cms
(changelog entries `2026-10-08-flash-worded-arguments`, `-activity-verb-visibility-and-subjects`, `-task-labels`).

- Toasts: `FlashEncoding` carries a `Microcopy` argument, or a list of them, as itself (its DRY text), so Check and
  admit's refusal reads the checks' words in the reader's locale instead of "[check_nftables]". An argument of any
  other type is refused (`flash.encode_refused`), never its toString.
- Sign-ins: `ActivityAction.visibility()` (core's LOGIN/LOGOUT INTERNAL); the list's internal filter hides them, and
  `AdminActivityResource.peopleOnly` now reads core's `ActivityRules.listed()` (models AND verbs) instead of spelling
  the internal model tokens, so the dashboard and record cards leave sign-ins out too. AdminActivityListTest steps
  3 to 5 write a sign-in and prove it is off the list and the dashboard and one toggle away.
- Activity text: a row without a stored title names its record by its current title, or "a removed <model>";
  `ActivityText.headline` falls back to the verb's sentence when the operation has none.
- Task labels: the label lives on core's task declaration (`ScheduledTask.label()`, `TaskDescriptor.label()`); the 21
  Hohenheim tasks declare theirs (scope `hohenheim`, target `task_label`, the same words as D11), the framework's
  tasks ship their own, and `TaskWords` is deleted (replacement: `TaskDescriptor.label()`; a run's failure reads
  `TaskRunErrors.message` then `HohenheimViolations.storedText` in `AttentionCollector.failureOf`). zenit-cms's task
  screens title and list runs by the label and show the failure without its stack trace (folded under "Technical
  details").

## 33. D12 decisions (fork D12, 2026-10-08)

Places where the admin said two different things about one subject (D10b shots 02 and 03), each fixed at its cause.
Section 32 was taken by CORE1 while this fork ran, so D12 is section 33.

- Home: Hohenheim. `zemble home` answered NEW_MECHANISM in apps for the database verdict, the error cause and the
  certificate rule, beside the existing homes it listed (`AppHealth`, `DatabaseAttention`, `CertificateCoverage`,
  `CertificateStore`, `ForceSslLatch`); the cause rides zenit's existing `ActivityLog`, no column and no migration.
- One database verdict: `DatabaseVerdict` (server/cms) reads the record's lifecycle while it is not active, then the
  STORED state of the engine instance serving it (`InstanceStatusReconciler`'s column, the host's recorded probe
  failure, `workload_killed_at`). The record's "active" only says provisioning finished, so it is no longer shown as a
  status: the Databases list (both twins) and the Engines list draw a `state` cell from the verdict where `status`
  was (the stored status stays a hidden, filterable column), and the Engines card, an app's Databases tab, the
  connection card (Restore, tenant Credentials), the host page's workload badges and Back up now's availability read
  it too. Its states are one vocabulary (`DatabaseVerdict.State`: label, badge, whether it serves, its attention title
  and severity); the lifecycle members reuse the `database_status` labels.
- One database item: the database is the root. `DatabaseAttention.failedDatabases` raises one item per database whose
  state carries an attention title ("Database shop is not running", en/nl), with the verdict's reason, "Open the
  database" to its front door and `heldBack` "2 apps use it"; it is `about` `AttentionSubject.database`. The per-app
  items stay, reworded ("Shop cannot use its database shop", detail the verdict's reason), and are `causedBy` the
  database, so the dashboard folds them; the Databases band folds too (`DashboardAttention.band`, the dashboard fold
  without a checklist; the Hosts band stays unfolded). A database still being set up raises no root, so an app waiting
  on it keeps its own item. The setup-failed record's item joined the same loop (title "Database shop could not be set
  up"). Deleted copy: `database_failed`, `database_workload_dead`, `database_status` (attention_detail) and the
  "Instance {$name}" attention title (replacement: the verdict's reasons and `app_database_down`);
  `database_not_running` and `database_unreachable` became the root's titles.
- One certificate rule: `CertificateCoverage.workingNames()` is what the running proxy's store loaded, the ACTIVE rows
  only where no proxy runs in this process. `RouteTableBuilder` reads it for its own store (passed down from
  `ProxyServer` through `SiteDispatcher`), so the global Force HTTPS no longer sends a name whose certificate cannot load
  to HTTPS; "Get a certificate" is hidden only for a name a loaded certificate covers; `AppHealth.workingNames` is that
  rule plus the listener half. `activeNames` is private now. The force-HTTPS latch fires when the proxy's store loads a
  certificate (`ProxyServer.loadCertificatesAndRoutes`, which rebuilds the routes when it forced anything), and on an
  ACTIVE row save only where no proxy runs in the process; an address written under a working certificate is forced
  in the same write as before. A store that fails to load leaves names only the global setting forced on plain HTTP;
  an address's own force_ssl still fails closed.
- Error causes: every ERROR stamp names its cause. `InstanceOperationGuard.stamp` refuses ERROR; `stampError` (and the
  ERROR branches of `stampObserved` and `clearMigration`) records the cause on the workload's activity as one of nine
  new verbs (`HohenheimActivityAction` members with the `ErrorCause` fact: exited with its exit code, crash loop, could
  not be started with the failure's message, never ready, could not stop, could not remove, restore failed, restore
  interrupted by a restart, move lost). `WorkloadErrors.detailOf` reads the newest cause: the crash item's detail and
  the workload verdict's detail are that verb's own sentence ("Shop could not be started: <message>", "Shop exited
  unexpectedly and was not restarted (exit code 137)"), a tenant reading the sentence without the daemon's message,
  and "No cause was recorded for this error; its console output may say what happened" where none is (log off or
  pruned, a status written by hand). `instance_crashed` is deleted (replacement: those sentences). The reconciler keeps
  its `reconciled` accountability row beside the cause row.
- Memo: `StackFailures` keeps its request memo through `CmsSupport.memo` (its private attribute code is gone).
- Walk (scratch server from the jar of build 885, fresh database, `/setup`; then by SQL, because hosts cannot be
  admitted on this machine and a dedicated database needs no encrypted secret: Docker apps Shop (error, with a
  `workload_start_failed` cause row) and Blog (stopped) using a dedicated MySQL database shop whose engine instance is
  stopped, a site Shop on shop.d12.test (not forced) and an ACTIVE certificate row for it without material): shots
  `docs/hohenheim-ux-mockups-2026-09-30/shots/d12/` (workspace repo). 01 the dashboard: one "Database shop is not
  running" item ("Its engine is not running", "2 apps use it", Open the database) and "Shop stopped after an error"
  reading "Shop could not be started: Docker refused ..."; 02 Databases: the band's one item, the row reading "Not
  running" with why and Back up now offered dead; 03 Shop's Databases tab: "Not running"; 04 Addresses: shop.d12.test
  "Not working" and "Get a certificate" offered (dead only because Let's Encrypt is off on the scratch install); 05
  Shop's overview: the Recent card names the cause. A plain HTTP request for shop.d12.test answered the instance
  upstream's own 503 ("No release is serving this application"), not the HTTPS refusal the global setting used to send.
- Found, not fixed here: the Last backup cell still says "Back up now, or wait for the nightly backup" for a database
  that cannot be backed up while it does not run; an app's Databases tab heads its first column "database" in lower
  case and shows the engine token ("mysql"); the attention fold and the Recent card both show a failed start, but
  the host's placement refusal leads the app overview ("Shop cannot start yet"), so the cause sentence shows on the
  workload verdict only once the host takes apps. Other private per-request caches beside `CmsSupport.memo` remain
  (`DeleteImpact`, `ServerParts` run counts, `DnsZoneParts`, `InstanceScheduleStepParts`, `SpamserviceRemoteStore`).
- Proof: build run 885; test run 886 (28 classes, 88 tests: 85 passed; DatabasesPageJourneyTest's two journeys passed
  every step and failed in their cleanup, a generated engine row being deletable only in its owner's scope, now
  `OwnedInstances.inScopeUnchecked`; DatabaseDnsGitSurfacesBrowserTest's baseline still drew Move to a shared engine
  as PRIMARY, a stale fact the journey already pins as DEFAULT, hand-edited) and run 887 (those two classes, 3
  passed). Journeys: DatabasesPageJourneyTest (new `aDatabaseThatDoesNotRunIsOneProblemEverySurfaceReads`, steps
  1-5; steps 1 and 8 of the old one), AttachedDatabaseAttentionTest (steps 3-6), DomainsListJourneyTest (new step 9),
  ForceSslAvailabilityTest (new step 4b), InstancePowerAuditTest (new step 3b), InstanceStatusReconcileTest (new step
  4c), InstanceAttentionTest (step 1), DatabaseDnsGitSurfacesBrowserTest (baseline hand-edited: the state columns, the
  hidden status columns, Back up now dead with `back_up_not_active`), and AdminPagesTest, CertificateRequestJourneyTest,
  NewDomainHttpsJourneyTest, DashboardAttentionJourneyTest, LandingDashboardSurfacesTest, AppOverviewJourneyTest,
  AppsListJourneyTest, SiteRefusalVerdictJourneyTest, ServerOverviewTest, DatabaseAdminTest, SharedDatabaseEngineTest,
  InstanceDatabaseSurfaceTest, TlsCertificateTest, ProxyListenerSupervisionTest, InstanceConsoleCrashRestartTest,
  DashboardVocabularyDriftTest and the four microcopy gates.

## 34. SFTP-W3/W4 decisions (fork SFTP-W3, 2026-10-08)

Hohenheim's own SFTP server for app files, wired over zenit-sftp (W2), and the Files tab's "Connect with SFTP" card.
The as-built record with every mechanism is `docs/sftp-design.md` section 10; this section keeps the decisions.

- Home: zemble `home` answered EXTEND_EXISTING `SftpRealm` (zenit-sftp, high confidence): W3 implements the SPI and
  wires it; nothing new in the module but one cosmetic fix (an `ls -l` long name printed the owner as
  "Owner[getName=1]"; MINA reads the principal's `toString`).
- Settings `hohenheim.sftp.*`, all host-only, off by default, in the Apps section after Files. The server starts last
  in `ServerMain.start` with no role gate (every node that renders the panel serves the Files tab); a bind failure is
  an attention item linking to the setting, never a refused boot.
- One lane, three transports: `FileVerbs` (perform AND record) is now what the Files tab, `/api/v1` and SFTP call;
  `InstanceFiles.reachableInstance` is the one "absent, trashed, generated and not permitted read alike" scope the API
  lane and the SFTP realm share. An SFTP path IS the container path, so History rows name the same paths.
- Bans need no new code: the module's `SSH_*` events (lane `sftp`) reach the existing in-process sink and score as
  `BanScope.SSH`; the SSH ban rule's ports gain `hohenheim.sftp.port` while SFTP is on.
- The card LINKS to `/account/api-keys` and names the scopes to paste, instead of posting the mint itself: the posted
  form minted the key, then hawkeye's client reloaded the Files tab and the one-time key was never shown (a
  cross-shell `use:Zenit.form` response is dropped; reported, not fixed here).
- Decision for Jelle: an operator's files-scoped key reaches nothing (the scope narrows the operator's permissions
  away, so the instance gate refuses), while the same key works for a tenant. Options and measurements:
  `sftp-design.md` section 10. Unchanged here.
- Not built: activity rows grouped per SFTP session (needs a zenit core `ActivityLog` display-group writer and the
  fold; core was held by other sessions), the open-sessions list with End, `spool_budget_mb`.
- Walk (scratch server from the jar of build 905, fresh database, `/setup`, `hohenheim.sftp` on port 4514; by SQL an
  instance Survival with a hand-started labelled container and volume, a tenant account with files grants): shots
  `docs/hohenheim-ux-mockups-2026-09-30/shots/sftp-w3/` (workspace repo). 01 the operator's Files tab card; 02 the
  tenant's /manage twin; 03 History showing "File uploaded, Tara Tenant via API key: SFTP, SFTP,
  /data/hello-from-cli.txt". OpenSSH's `sftp` (10.5) signed in as `tenant@scratch.test.1` with a password minted from
  the card's link, listed `/` (data) and `/data`, uploaded a file the container then held, and downloaded one.
- Proof: builds 892 and 905; test run 907 (SftpJourneyTest, InstanceFilesTabGateTest, InstanceFilesLiveTest,
  ApiWireFilesLiveTest, InstanceFilesContainmentTest, InstanceFileUploadTest, TenantInstanceApiTest,
  SettingsGroupCoverageTest, HohenheimSettingAuthorityTest, the four microcopy gates, ThreatScorerTest, NftServiceTest,
  HohenheimSecurityTest, SlowLaneGuardTest, BrowserTestLaneGuardTest, MigrationIntegrityTest, AdminSettingsMountsTest,
  AdminPagesTest, DashboardAttentionJourneyTest, DashboardRoleGatingTest, DockerArchiveSafetyTest: 87 passed, live
  needs declared); zenit-sftp 906 red, 911 green.

## 35. D13a decisions (fork D13a, 2026-10-09)

DEP10's walk of Starfleet (`docs/deploy-starfleet.md`, last section) against boards Main and Hosts, plus adopting
D13-FW's framework changes (changelogs `2026-10-08-cluster-landing-one-heading`, `-cron-words-and-task-list`,
`-inbox-repeat-key`) and zenit-auth's People/Roles change (`2026-10-08-zenit-auth-people-and-roles`).

- Home: `zemble home` answered NEW_MECHANISM in apps for both the app-name rule and the heartbeat's memory reading,
  next to the existing homes it listed (`AppDirectory`, `DomainParts`; `HostProbe`, `HostPreflight`,
  `InstanceCapacity`); each extends the existing class, nothing new beside it.
- One heading on cluster members: the inbox and the abuse-protection overview render `zenitcms:page-header` (its
  `zenitcms:page-title` draws no h1 while `headedByCluster`), so Activity and Access read as one h1 over their tabs.
- Alerts name what they are about: `Alerts.send/trySend(event, about, subject, message)`; `about` is the subject's
  identity (`Alerts.about(model, id)`, `Alerts.INSTALLATION`), never the wording. `AlertNotification.toInbox` sets the
  repeat key `event + ":" + about`, so phoenix's eight "stopped answering" items are one item saying "8 times". The
  String overloads are gone: every alert is catalog copy in en and nl, a headline for a person and the technical
  detail after the words ("phoenix stopped answering" / "Its host key is not pinned yet. What the connection
  reported: ..."; "Some records in Hohenheim's own database point at records that no longer exist" / "... Found:
  auth_passwords -> auth_users: 5 orphaned rows"; "kuifje has an old copy of starfleet.life" / "kuifje gave no
  answer for the zone (no authoritative SOA answer from ...)"). Real plurals (`HohenheimDatabase.orphanedRows`, the
  CLI too). `SecurityNotifier` carries Microcopy; its production sink is `SecurityNotifier.ALERTS`. An alert's
  delivery rows and inbox items are written under `ActivityLog.suppressed`: inside Probe now they were headed by
  the command's sentence ("Jelle checked whether Comms delivery #3 answers", found on the walk).
- Sidebar counts follow board Main: the Apps entry carries the apps with a problem (`PanelResource.navBadge`,
  `AppDirectory.withProblem`, the same count as the Apps tile through `AppDirectory.Count`, one exhaustive switch over
  `HealthTone`); the inbox's unread count leaves the sidebar (decision: a lasting condition reaches Needs attention
  at its root; the inbox is the history of what was sent). `AppDirectory.read` is memoised per request
  (`CmsSupport.memo`), because the badge, the dashboard and the Apps list of one page all ask. AdminInboxBadgeTest is
  deleted (replacement: DashboardAttentionJourneyTest step 8 asserts both entries' counts).
- A stale DNS secondary is ONE item per zone, its root (`AttentionSubject.zone`), worded by
  `DnsSecondaryFreshness.staleTitle/lagOf`, the same words the DNS_SECONDARY_STALE alert says, with "Open the
  secondaries". Cause of "never reaches Needs attention" on Starfleet: it did, for 39 minutes. kuifje's link was
  behind from before 2026-10-07 03:00 UTC and caught up by AXFR at 03:39 (`behind_since` cleared); the item left with
  the lag, while the alert sat unread in the inbox a day later with no word that it recovered.
- Backups count database dumps: `DatabaseParts.BackupState`/`backupOf` is the one database backup verdict (the Last
  backup cell reads it; TEMPORARY is not counted, DONE is a dump newer than 36 hours). The tile reads "<ok> of
  <total>" over workloads with a backup target plus persistent managed databases, "Newest <ago>, <size>" over both.
  Closes section 28's needs-building item. Empty words: "No app or database is backed up yet".
- One app, one name: `AppDirectory.nameOf(site)` names the app a site belongs to as the Apps list does (the
  workload's name when the site serves a non-generated, live instance, else the site's). Cause of the two names: the
  attention items (`ProxyAttention`), the Addresses App column and lead (`DomainParts`) and the HTTPS-unavailable
  item read `SiteModel.NAME` ("Alchemy skeleton") while the lists name the workload ("alchemy-skeleton"). The
  dispatcher's `ForcedSites` now carries site ids, named by the panel. The /manage Addresses twin names the site for
  a reader who does not reach the workload (`nameOf(site, false)`).
- A pattern's HTTPS is said, never an empty cell: `CertCoverage.PATTERN` ("Per name", "A pattern answers many names;
  HTTPS works for each one a certificate covers."), returned by `AppHealth.httpsOf` for a non-exact name. The
  catch-all's trailing badge on the dashboard and its Addresses HTTPS cell read it; the app overview's pattern badge
  reads the same member (its own "Pattern" copy is deleted).
- Memory reading, the cause: only a full preflight wrote it (`HostPreflight.store`, called by Check again), and no
  task re-runs a preflight. The hourly Docker sweep's heartbeat (`HostProbe.recordSuccess`) wrote only
  `last_seen_at`, and Probe now read the daemon's MemTotal and threw it away. Starfleet's local host was seen every
  hour while its reading aged from 2026-08-29 past the 168-hour bound. Fix: `HostProbe.recordSuccess(name, info)`
  records the daemon's memory total through `HostPreflight.recordMemoryReading` (the fact and its `facts_at` stamp
  only, never a check, `probed_at` or the verdict), from the sweep (`DockerReconciler.sweepAll`, one extra
  `/info` per host per hour) and from Probe now. A quarantined host's answer measures nothing.
- Host words: the gate's refusals no longer name a button or join clauses with ";" ("The memory of local was
  measured too long ago to place a new app there"; the failing-check and kernel-lane refusals likewise); Capacity
  says "Memory last measured <ago>, too long ago to place new apps here." and draws no empty fact list; a host with no
  reading says "No memory reading yet, so no new app is placed here."
- Workloads list each booking once (`ServerOverviewState.workloadsOf`): the instance a database engine or a dedicated
  database runs as is not listed beside its owner; the owner's row shows the owned instance's booked memory (the
  ledger's number), a shared database none. Cause: the list queried instances, databases and engines separately and
  never removed owned instances; the ledger itself always booked the engine once.
- Recent activity on a host: one Check again wrote two rows (the volume probe's save, then the report's); Starfleet's
  ten identical rows were three per preflight (plus the then-unsuppressed heartbeat) from 2026-08-29, before
  operations recorded commands. `VolumeBackends.store` is bookkeeping (`ActivityLog.suppressed`), so a press is one
  row, "Jelle checked local". Old rows are history and stay.
- One clear action on the host page: Check again (board Host-Admit's one verb); Probe now moves to More
  (`inlineOnRecord(false)`) with a hint saying what it does without running the checks.
- Hosts list: a standing that raises an attention item has no reason line in its row (`HostVerdict.cell`); the band
  says it once. "Add host" (`FormSpec.createTitle`), lead "The machines your apps run on.".
- `/admin/hosts` stays a 404: zenit-cms declares no slug aliases and CLAUDE.md forbids them; the list stays at
  `/admin/servers`.
- Task list adoption: SettingsTasksMountTest finds a schedule by its declared label and asserts its cron in words.
  Roles: AdminNavigationJourneyTest expects no Roles tab (roles stay a member, reachable at /admin/roles).
- Framework defects found (not edited here, zenit/zenit-cms are other agents'): (1) an activity card's subtitle is
  `ActivityText.humanizeModelToken` of the stored token ("Server" for `hohenheim:server`), never the model's label
  ("Host"), in zenit core and zenit-cms `ActivitySources.subtitle`; on a record's own Recent card the subtitle
  repeats the page's kind anyway. (2) `cms-nav-badge` renders empty on the server and fills after hydration, so a
  screenshot taken while the page still loads shows no badge.
- Not done, seen on the walk: the "HTTPS is unavailable" item and a site's error-page item both speak for one site
  while no certificate exists (the site's cause is its address, whose own item stays silent while HTTPS is
  unavailable, so the fold finds no shown root); a workload's start-failure detail quotes the daemon/gate English
  verbatim (D12's MESSAGE cause); the probe's "Last error" on the host page is raw English. Alert events with no
  dashboard root while the inbox badge is gone: CERT_EXPIRING (the tile shows the next expiry), STACK_HEALTH,
  AUTO_BAN_BUDGET_EXHAUSTED, DATABASE_MOVE_LEFTOVER.
- Walk (scratch server from the built jar, fresh database, `/setup`; by SQL a Starfleet-like state: local admitted
  with shared containers accepted and a 2026-08-29 memory reading, phoenix never checked, alchemy-skeleton behind the
  site "Alchemy skeleton" forcing HTTPS on skeleton.d13a.test, a catch-all on `**.d13a.test`, the shared engine
  mongo-local with its owned instance dbengine-mongo-local (512 MB) and the database skeleton-mongo with a 16-hour-old
  dump, zone d13a.test with kuifje stale; the engine and database passwords encrypted with the scratch keyring):
  shots `docs/hohenheim-ux-mockups-2026-09-30/shots/d13a/` (workspace repo). 01 dashboard (Apps badge 2, no
  Activity badge, kuifje's item, Backups "1 of 1, Newest 16 hours ago, 4.0 MB", the catch-all "Per name", every
  surface saying alchemy-skeleton); 02 Hosts (Add host, the board's lead, badges without repeated sentences); 03 local
  with the stale reading (Check again plus More, the capacity line, mongo-local booked once); 04 after Probe now
  (31.0 GB measured just now, "Takes new apps", booked 1.0 GB = the two 512 MB bookings); 05/05b after Check again
  (the refusal without "Fix it, then Check again", one "checked local" row); 06 inbox under the Activity heading
  ("3 times", worded headlines with their detail); 07 Addresses (App alchemy-skeleton, catch-all "Per name").
- Verification: hohenheim run 958 (log `20261009-015614`), the 37 classes the wave touches, 139 of 139 passed:
  AlertsTest, DnsFederationHealthTest, DashboardAttentionJourneyTest, DatabasesPageJourneyTest, AppsListJourneyTest,
  HostsPageJourneyTest, ServerOverviewTest, VolumeBackendProbeTest, SettingsTasksMountTest,
  SpamserviceAdminBrowserTest, InboxPagerTest, AdminNavigationJourneyTest, ForceSslAvailabilityTest,
  LandingDashboardSurfacesTest, DashboardVocabularyDriftTest, DeclaredMicrocopyKeysTest, MicrocopyCatalogParsesTest,
  MicrocopyCatalogScopeTest, MicrocopyManifestDriftTest, SpamserviceHealthTest, BanServiceTest,
  SecurityReportEnvTest, CertExpiryAlertTest, IsolationVisibilityTest, DomainsListJourneyTest, AppOverviewJourneyTest,
  AdminPagesTest, HostEvidenceTest, HostCheckAndAdmitJourneyTest, B15BeforeSurfacesTest, InstancePlacementTest,
  ForeignKeyIntegrityTest, OnboardingChecklistTest, DashboardRoleGatingTest, ManagePanelJourneyTest,
  RoutingProblemsTest, SiteRefusalVerdictJourneyTest. Earlier runs proved nothing whole: `20261009-004738` and
  `20261009-010908` were aborted by the default 900s filtered budget (the second had 5 wording failures, fixed:
  the DNS lag's closing period, the pattern's PATTERN coverage, the app-name titles, the hosts row without its
  reason); `20261009-013339` was stopped before its verdict. AlertsTest's solo task printed nothing for 900s in
  `20261009-004738` (the phase's whole budget, no test started); it did not reproduce: in `20261009-013339` and 958
  the same solo task booted and passed all six tests on this tree, so no cause was found and none is claimed. The
  set needs a raised budget (run 958: 1954s, about 550s of it rebuilding upstreams).
- Shot `08-admin-dashboard-final-tree.png` (D13a-finish, jar of build run 965 on the committed tree, a fresh scratch
  with alchemy-skeleton behind "Alchemy skeleton", the catch-all and kuifje stale by SQL): the Apps badge 2 after
  hydration, kuifje's item at its zone with the lag sentence's closing period (01 predates it), every surface saying
  alchemy-skeleton, the catch-all "Per name". Its Backups tile is 0 because this scratch seeded no database.

## 36. D13b decisions (fork D13b, 2026-10-09)

D13a's leftovers on the dashboard (shot `d13a/08`), DEP10's Apps list and app overview defects (shots `dep10/02`,
`dep10/03`, boards Apps-List and App-Overview), and adopting the framework changes of 2026-10-08/09 (changelogs
`zenit-auth-people-columns-and-holdings`, `zenit-auth-person-can-manage`, `activity-batches`, `hidden-entry-stands-under`).

- Home: `zemble home` answered NEW_MECHANISM in apps for the HTTPS root (next to `ProxyAttention`, `AppHealth`,
  `SiteDispatcher`); it extends the existing `AttentionSubject`/`DashboardAttention.fold` (D8/D11), nothing beside it.
  The expiry wording, the stack items and the row fix extend `CertificateParts`, `AppHealth` and `AppDirectory`.
- One HTTPS cause, shown once at its root: `AttentionSubject.httpsTermination()` is the installation's HTTPS (the
  proxy's listener and the certificates it loaded), the one subject no model stores (`AttentionSubject.HTTPS_TERMINATION`,
  id 0). "HTTPS is unavailable" and the failed/degraded HTTPS listener items are `about` it; while this proxy answers
  no HTTPS (`AppHealth.httpsTerminates`, the one reading `forcedWithoutCertificate`'s silence shares), a site forced
  to HTTPS names it as its verdict's cause instead of its address, so its "Visitors of X get an error page" item folds
  under the root. Deleted: `AttentionItem.holding` (replacement: `about(AttentionSubject.httpsTermination(), ...)`).
- Glyph and badge read one verdict: a workload that does not run (held back by its host, stopped) reads its site's
  broken verdict when the site is broken for a cause of its own (a name forced to HTTPS without a certificate, the
  proxy refusing its settings; `AppHealth.siteBrokenOnItsOwn`), so the row's glyph says broken beside "Not working".
  The host item's held-back reason now reads the placement gate's words directly. A site whose stored certificate
  cannot be served is ATTENTION "HTTPS does not work" with the HTTPS cell's own reason (`DomainParts.httpsDetail`),
  never OK beside a red badge (found on the walk: Docs read a green check beside "Not working").
- Routing problems in a person's words: "Nobody can reach X" (unrouted), "X does not answer as it is set up"
  (refusing), the faulted handler's detail "It refuses every request: ..."; a site refusing every visitor is an ERROR
  item, as its verdict is broken (`RoutingProblem.Reason.refusesEveryVisitor`).
- Every alert that describes a lasting condition has an item at its root (none was covered before):
  CERT_EXPIRING `ProxyAttention.expiringCertificates` (the alert's 14-day window, `AcmeService.EXPIRY_ALERT_DAYS`;
  an upload "does not renew itself", a failed renewal says why); STACK_HEALTH `StackAttention.unhealthyStacks`
  ("mailstack stopped after an error" / "Part of metrics is not running", to the stack's services);
  AUTO_BAN_BUDGET_EXHAUSTED `FirewallAttention.autoBanBudget` over `BanService.autoBanBudget()` (the alert's words,
  to the blocked addresses, while the last hour spent the budget); DATABASE_MOVE_LEFTOVER `DatabaseAttention.moveLeftovers`
  (an engine instance generated for a database whose placement is shared, naming it and its host, to the database).
  A failed stack's verdict now tells a failed deploy from services that died after a good one
  (`AppHealth.stackStoppage`: "Its last deploy failed" only when the newest deployment failed).
- Apps list rows offer their fix (board Apps-List): `AppDirectory.App.fix` is the first fix the verdict names that
  the reader may use, offered exactly as the record's band offers it (`RecordActionBands.named` on the workload, the
  site, or the site a workload speaks for), drawn by the framework's own `zenitcms:action/link` and
  `zenitcms:action/invoke` partials in a label-hidden column (`cell/app-fix.hwk`, `AppFix`). A dead operation is passed
  over. Walk: "Get a certificate", "Restart", "Change its configuration", "Check the host".
- One expiry wording, whole days, as the boards say it ("Renews in 12 days"): `CertificateExpiry.of`/`inSentence`
  ("Expires in 35 days", "Expires today", "Expired 3 days ago"; the `case=sentence` variant inside a sentence). Read by
  the HTTPS cell (Apps list, Addresses), the certificates' state cells, the dashboard tile ("Next one expires in 10
  days"), the app overview's address note, the expiring item and the CERT_EXPIRING alert. Deleted copy: `expires`
  (site_domains) and `state_valid_detail` (replacement: `certificate_expiry`). The Certificates list's Expires date
  column stays absolute-first (CertificateDateColumnTest's decision: a calendar column).
- App overview (board App-Overview): Resources draws Memory, Root disk and CPU; memory and CPU are the live stats
  hub's held samples (`InstanceStats.history`, read only while someone watches the Metrics tab, never a stream opened
  by the render; `Sample` carries the online cores), NOT MEASURED in words otherwise. Details add Started (the newest
  `DEPLOYED` activity row, which every container start records; no column, no migration: a restart Docker does on its
  own writes nothing, which no stored fact would have told either), one Database line per attached database
  ("shop-db (MySQL, not running)", linked for the operator) and Backups. Open site already heads the page while the
  app serves (`InstanceActions.OPEN_SITE`, hidden while broken; shot 04 on a serving site). The app pages stand under
  Apps (`standsUnder(AppParts.SLUG)` on sites, instances, stacks, projects; both panels), so the sidebar marks Apps.
- Framework adoptions: capability labels ship the `context: holder` spelling ("Tenant", "Viewer", ...) and the
  `case=sentence` spelling in en and nl, and each level held alone `describe`s what it allows; the tenant's "What you
  can do here" reads one sentence ("Console, read files, share access"). FILES_* verbs are `Grouping.BATCHED` and
  word their batch (`target=activity_batch`: "Uploaded 312 files"); the per-app Recent card folds per command
  (`ActivitySources.onePerCommand()`). AdminNavigationJourneyTest expects zenit-auth's `reach` person tab before
  `credentials`.
- Found, not fixed here (framework, FW6's): the document title of an app page is "Overview - shop - Instances",
  because `CmsDocumentTitle` composes the resource facet from `resource.label()`; it should name the sidebar entry the
  page stands under (`PanelNav.sidebarEntryOf`, now declared by Hohenheim) so it reads "... - Apps". A zenit-cms
  `ResourceHealth` fix column would make the Apps list's row fix generic (Hohenheim's cell is thin wiring over
  `RecordActionBands.named`). Seen on the walk: a start refusal still quotes the gate's English (D12's MESSAGE cause).
- Walk (scratch server from the jar of build run 979, fresh database, `/setup`; by SQL alchemy-skeleton in error
  behind "Alchemy skeleton" forcing HTTPS, a catch-all static site nobody owns, Docs with a stored certificate
  expiring in 35 days, an uploaded certificate expiring in 10, stacks mailstack (failed) and metrics (degraded), shop
  running with a start row 2 hours old and the database shop-db, and archive moved to a shared engine with its old
  engine instance left; local admitted by SQL, its posture accepted and probed in the UI): shots
  `docs/hohenheim-ux-mockups-2026-09-30/shots/d13b/` (workspace repo). 01 dashboard: the expiring certificate, HTTPS
  unavailable with no separate alchemy-skeleton error-page item, "D13b catch-all does not answer as it is set up",
  the leftover engine, both stacks, the tile "Next one expires in 10 days", every Apps row's glyph agreeing with its
  badge; 02 Apps list: Restart, Change its configuration, "Expires in 35 days", Docs attention; 03 shop: Memory, Root
  disk and CPU not measured in words, Started, Database, Backups, Apps marked in the sidebar; 04 Docs: Open site and
  "HTTPS does not work". The seeded shop's upstream answers nothing (no container), so its band says error page.
- Proof: build runs 973 and 979; test run 975 (37 classes, 115 tests: 112 passed) with three causes fixed after it:
  ManagePanelJourneyTest step 7 expected the old capitalized list (now one sentence, "share access" gained its
  `case=sentence` spelling), InstanceOverviewTest step 2 read the whole page where the memory and CPU gauges now say
  not measured (it reads the Root disk gauge alone now), DeclaredMicrocopyKeysTest found four overview keys whose
  scope was applied in a later statement (applied at the key now); run 985 (those classes, the microcopy gates and
  AppOverviewJourneyTest: 21 of 22, the disk gauge's first anchor still met the page's render data first) and run 988
  (InstanceOverviewTest, 6 of 6). The push's duplicate gate found `AppHealth.stackStoppage` re-deriving
  `StackFailures.reasonOf`'s "newest deployment failed" fact; both read `StackFailures.failedDeploymentOf` now, run 995
  (DashboardAttentionJourneyTest, StackDeployFailureRecordTest, AppsListJourneyTest: 6 of 6). Journeys: DashboardAttentionJourneyTest (new `whatAnAlertSaidOnceStaysOnTheDashboardAtItsRoot`
  steps 1-5; step 5 and new 6b of `oneCauseIsOneItemCarryingItsRootsAction`), AppsListJourneyTest (new steps 12-14),
  AppOverviewJourneyTest (new `aWorkloadsOverviewSaysWhatItUsesSinceWhenAndWhatItHolds` steps 1-4),
  AdminNavigationJourneyTest (new steps 8-10), RoutingProblemsTest, SiteRefusalVerdictJourneyTest,
  CertificatesJourneyTest, ManagePanelJourneyTest, InstanceOverviewTest.

## 37. D13c decisions (fork D13c, 2026-10-09)

D13b's dashboard and shop overview (shots `d13b/01`, `d13b/03`, boards Main and App-Overview): one verdict per
subject, one problem shown once at its root with its consequences folded under it.

- Home: no new mechanism. The folds extend D8/D11's `AttentionItem.about/causedBy` and `DashboardAttention.fold`; the
  verdict words extend `AppHealth.Stoppage` and `WorkloadErrors`; the host item lives in `HostAttention` beside
  `hostsTakingNoApps`. New pieces are a typed refusal (`IsolationUnenforceable`), one verb, two facts on the verb
  enum and one `AttentionItem` component (`note`).
- One database, one item. The causal reading of `d13b/01`: archive was moved to a shared engine and has none
  (`database_no_engine`), so the DATABASE is the root. Its old engine db-archive (`DatabaseAttention.leftoverOf`: an
  engine instance generated for a database whose placement is now shared) serves nothing; its stoppage and its being
  left behind are facts about that database, not problems with their own fix (removing it is done from the database,
  never by restarting it). So: while the database's own verdict raises an item, that item carries the leftover as its
  `note` ("Its old engine db-archive on local could not be removed and still holds ...") and `moveLeftovers` raises
  nothing beside it; while the database serves, the leftover item stands, `about` the database. The leftover's own
  ERROR item is `causedBy` the database (`WorkloadErrors.rootOf`, the leftover rule first), so it folds; and with a
  root another record holds, its verdict offers no Restart. A dedicated engine that crashed under a database still
  reads twice (database item and engine item); not changed here: the engine's crash detail would be lost, which needs
  the database's verdict to carry the engine's cause first.
- Visitors of shop get an error page: the causal chain by what the jar records. d13b's shop was never refused for
  its database: `requireDatabasesReady` only checks the database record is active, and the start went on to the
  network policy, which refused it (the D13c walk reproduced it: `REFUSED to deploy 'hohenheim-...-instance-1-net':
  ... security.nftables_enabled is off`). So shop's root is the host (below), and its site's error page folds under
  shop's item (existing `Verdict.causedBy(instance)`), which folds under local's. Where a workload DOES run and its
  site does not answer while a database it uses does not serve, the site's verdict now names that database
  (`DatabaseAttention.firstNotServing`, "Its database shop-db is not running."), so the error page folds under the
  database's item.
- One host cause, one item. `security.nftables_enabled` off refuses every PRIVATE-network workload's start and leaves
  the isolation sweep unable to check the host. `WorkloadNetworkPolicy/ProcessNetworkPolicy.requireEnabled` now throw
  `IsolationUnenforceable` (an `IOException`, same technical message); the start lane records it as
  `WORKLOAD_ISOLATION_REFUSED` (`InstanceOperationGuard.startFailureOf`, cause fact `ErrorCause.HOST_ISOLATION`, no
  detail stored) instead of `WORKLOAD_START_FAILED` with the English. `HostAttention.isolationUnenforced`: one item per
  host without enforcement that refused a start or carries live workloads while the sweep's newest run failed, `about`
  the host: "Per-app firewall rules are switched off on local" / "An app that needs its own network may not start
  there, and the isolation of the apps that run there cannot be checked." / note "Technically: the setting
  security.nftables_enabled is off; switching it on needs passwordless sudo for nft on local." / "1 app could not
  start", action Open settings (the Blocking section's security group). The refused workloads' items and the failed
  `verify_workload_isolation` run are `causedBy` that host while enforcement stays off (`isolationRootOfSweep`). en
  and nl. Decision recorded: the switch is ONE setting for every host today (`forServer` reads the same supplier), so
  N hosts would read N items; they are host-rooted as the deploy lane and the sweep ask it per host, and because the
  other half of the fix (sudo for nft) is per host.
- A failed start reads as one. `HohenheimActivityAction.ErrorPhase` (START for `WORKLOAD_START_FAILED` and
  `WORKLOAD_ISOLATION_REFUSED`) picks `Stoppage.START_FAILED` ("Could not start", "shop could not be started") over
  "Stopped after an error" on the band, the app's verdict and the dashboard item; its detail is why ("What refused
  it: ...", or the host sentence), never the sentence twice. Started (`InstanceOverview.lastStartOf`) names the newest
  DEPLOYED row only when no failed start came after it.
- Status "Running" beside a failed start (`d13b/03`) was a seeded state no code path writes: a failed start stamps
  `error` (`stampError`), the status sweep skips `error`, and a crash restart that a gate refuses first leaves
  `stopped`. Status stays the stored lifecycle badge ("Error"), which now agrees with the band; Started also guards a
  stale running claim.
- Reconcile rows: `RECONCILED` is `ActivityVisibility.INTERNAL`, not batched. A correction is bookkeeping about the
  stored status; what followed (the queued restart's DEPLOYED or failed-start row, a crash cause) is what a person
  reads, and corrections are minutes apart, never one session, so `ActivityLog.inBatch` has nothing to fold. The app
  overview's Recent card now reads the framework's feed rule `ActivitySources.recent()` (listed rows, one per command)
  instead of `onePerCommand()` alone, which is why the d13b card listed them. ServerOverviewTest's band fixture used
  RECONCILED as "a person's verb"; it uses TESTED now.
- Seen, not fixed: a crash restart a placement gate refuses (posture, a failing required check) writes only a log
  line, so the app reads "Not running / Start it" with no word why. The scratch's local also showed a container
  `hohenheim-u2fud3qp-instance-2` from an earlier scratch as a colliding Docker name (not created by D13c).
- Walk (scratch server from the built jar, fresh database, `/setup`; by SQL: local admitted, its required checks
  passing and its posture accepted in the UI, shop-db a dedicated MySQL whose engine is stopped, archive moved to a
  shared placement with no engine and its old engine db-archive left in error with a recorded isolation refusal,
  shop on local using shop-db behind shop.d13c.test with a 2-hour-old start row; nftables off, the scratch default).
  shop's crash restart then really ran into the refusal and recorded `workload_isolation_refused` (the jar of build
  run 1005); the isolation sweep failed for real. Shots `docs/hohenheim-ux-mockups-2026-09-30/shots/d13c/` (workspace
  repo), from the jar of the final tree (`zenit-dev build --skip-deps --thrash-override`, invocation
  20261009-061638-208385): 01 dashboard: "Database shop-db is not running" (1 app uses it), "Database archive is not
  running" with the leftover as its note, "Per-app firewall rules are switched off on local" with its technical note
  and "1 app could not start"; no shop, db-archive or Check app isolation items. 02 shop: "Could not start / Per-app
  firewall rules are switched off on local, so it may not start there.", Status Error, no Started, Recent without
  reconcile rows. 03 shop seeded running (a fresh start row) while shop-db does not run and its upstream does not
  answer: "Visitors get an error page / Its database shop-db is not running.", Started a minute ago; the dashboard
  then had no shop item (it folded under shop-db). The colliding-name and unmanaged-resources items on the shots are
  the dev machine's own Docker state.
- Proof: build runs 1005 (hohenheim against the last green upstream publish, `--skip-deps`: zenit-cms's own worktree,
  another session's, failed its `checkDirectClockReads` gate) and the override build above; test run 1008 (19
  classes, 69 tests: 65 passed) with four causes fixed after it: the new dashboard journey's local was not placeable
  (it makes it so now), the Recent card still used `onePerCommand()` alone, ServerOverviewTest used RECONCILED as a
  person's verb, InstancePowerAuditTest expected the old "could not be started: <reason>" detail; run 1010 (those
  classes plus InstanceOverviewTest, AppsListJourneyTest, ManagePanelJourneyTest, InstanceAttentionTest: 33 of 33).
  Journeys: DashboardAttentionJourneyTest (new `aHostWithoutPerAppFirewallRulesIsTheOneRootOfWhatItRefused` steps
  1-6; `whatAnAlertSaidOnceStaysOnTheDashboardAtItsRoot` step 4 rewritten and new 4b), AppOverviewJourneyTest (new
  `aFailedStartIsOneVerdictWithItsCause` steps 1-4), InstancePowerAuditTest step 3b, ServerOverviewTest step 5.

## 38. D13d decisions (fork D13d, 2026-10-09)

DEP10's Domains defects (shots `dep10/07`, `dep10/07b`; boards Domains, Certificates, Certificate-New, Address-Edit) and
Blocked addresses (shot `dep10/08b`, board Access-Blocked); defect list items 7 and 8 of `deploy-starfleet.md`.

- Home: no new mechanism. `zemble home` for the Blocked cards answered NEW_MECHANISM in apps (BanParts, ThreatScorer,
  NeverBanHostnames named); they extend zenit-cms's `ResourceList.widgetsBelow` (the Databases Engines card's shape)
  and the scorer's own state. "Points here" extends W8a's `HostnameReach`, the machine's addresses come from the
  existing `UpdateSystemIpAddresses` (the own-IP ban guard's list), the certificate state extends W8a's
  `CertificateParts.stateCell`, the legacy ban wording extends D7c's `HohenheimSecurity.EVENT_CAUSES`.
- "Points here" read Unknown on Starfleet because the lookup never compared anything: `HostnameReach.of` answered
  UNKNOWN before resolving whenever the local host declared no public address, and Starfleet's `local` declares none
  (read by SQL on the host: `public_ipv4`/`public_ipv6` empty) while eth0 holds 104.223.42.142. The lookup ran, was
  cached and the cell read the fact it was given; the fact was wrong. Now a name resolving to any address this
  machine holds (loopback and link-local aside, the interface zone stripped) points here, declared or not; a name
  resolving elsewhere is "No" when a public address is declared or held, and "Cannot tell" (with where it points and
  why) only for a host behind private addresses that declares nothing. The resolution happens first, so a name that
  does not resolve says so on any host. `UpdateSystemIpAddresses.ensureDiscovered()` is the one "discover if empty"
  (moved from `HohenheimSecurity.ensureLocalAddresses`, deleted).
- The certificate pre-check (`CertificateOperationHandlers.refuseUnreachable`) keeps its old basis: it refuses
  POINTS_ELSEWHERE and UNRESOLVED only while the host DECLARES a public address (`Reach.declared`), as before. A held
  public address behind a CDN or a floating address must not lose its Let's Encrypt orders to a display improvement
  (run 1024 caught the unresolved half: CertificateAuthorityTest read "does not resolve" before "not served").
- "Not checked yet" (`Verdict.NOT_CHECKED`) is a name with no answer and no lookup running: every lookup place was
  taken, so none started. A lookup that runs past the page budget stays "Checking". A failed lookup reads "does not
  resolve" (it read UNKNOWN). A check can be triggered: the row action "Check again" (`DomainParts.CHECK_REACH`,
  admin only, in the row's menu) forgets the remembered answer (`HostnameReach.forget`) and looks the name up again;
  it records "X checked where Y points".
- The catch-all row: every column says something. Points here asks one sample name a wildcard pattern catches
  (`HostnameReach.sampleOf`, `*`/`**` become `hohenheim-check`): with a wildcard DNS record (Starfleet's zone has
  one) it reads "Yes, names it catches point here (checked with ...)", without one "Per name, each needs its own
  record"; a regex reads "Per name". HTTPS already read "Per name" (b357fa23, after DEP10's jar). The address page's
  lead uses the same answer for a pattern (`address_lead_pattern` deleted; replacement `address_lead`).
- Certificates list all rows; Starfleet holds two certificates (plus the hidden ACME account row), both working, and
  DEP10's list showed both. What was missing were the states: `stateCell` is THE certificate state, read by the list
  and the dashboard tile, and now says "Expiring" inside the expiry alert's window (`AcmeService.EXPIRY_ALERT_DAYS`,
  the dashboard item's window; "Not renewed yet" or "Does not renew itself"), "Failed to load" for an active row the
  running proxy did not load (`CertificateStore.holds`, `CertificateCoverage.loaded`; D11's Shop case read "Works"
  here while the HTTPS cells said broken), "Not issued yet" for a pending order and "Expired 3 days ago". The tile
  counts every state but Works as needing a look, so an expiring certificate is counted where the dashboard item
  names it. The header reads the board's two ways: "Get a certificate" (the request operation, inline in the header)
  and "Upload a certificate" (the create's title); "New certificate" and the `request` copy key are gone
  (replacement: `get_certificate`).
- Old-format automatic bans ("score 26 over threshold", 305 rows on Starfleet) read in the current style from their
  stored event type, without the count they never recorded: "Tried names this server does not serve". Every cause in
  `EVENT_CAUSES` has a `target: uncounted` spelling in en and nl, drift-tested; nothing rewrites stored rows. The
  `legacy` key ("Went over the limit for") is deleted (replacement: the uncounted causes).
- Recent misses: read from the threat scorer that blocks for them (`ThreatScorer.recordMiss`/`recentMisses`), which
  now keeps the distinct names each actor asked for (at most 64, with when). No table: Hohenheim stores no security
  events (Spamservice's are threshold-gated and remote), and the scorer is the state the automatic block acts on. A
  restart forgets misses as it forgets scores. One line per address for the past hour: "Asked for a, b and 38 more,
  10 minutes ago"; the lead states the live threshold and window. Never block: the `security.never_ban` setting as it
  stands plus "this server's own addresses and local networks", with "Change in settings" to the security group
  (`AttentionCollector.securitySettingsTarget`, renamed from `isolationSettingsTarget`). This reverses W8b's "never
  block stays in Settings, not repeated": the board places it here, and the setting stays its one home.
- Found on the walk and fixed: an uploaded certificate stored neither its names nor its dates, so it read "Works"
  with no expiry, could never read "Expiring", and covered no name in the stored rows (the Addresses HTTPS cell drew
  no certificate line for it). The upload now stores what the certificate says (`CertificateParts.describe`: names
  from `CertificateStore.hostnamesOf`, the key store's own reading, made public; issued and expiry dates). The Never
  block card's entries read what they cover ("Every address in this network", "Every address this name resolves
  to", "This server: its own addresses and local networks"); a fact without a value rendered "Unknown".
- Seen, not changed: an inline header invoke is drawn in the list's toolbar row (zenit-cms), so "Get a certificate"
  sits beside Filter rather than beside "Upload a certificate" as on the board; with Let's Encrypt off it is
  disabled. The Certificate-New board's one page for both ways, "Read the certificate" before saving, the Blocked
  list's State column (not on the board) and its "in 47 minutes" wording stay as they are.
- Walk (scratch server from the jar of the override build, the tree before the pre-check fix, which no shot shows; fresh
  database, `/setup`; by SQL: local declaring
  104.223.42.142 because this workstation's resolver strips answers pointing into its own networks, so no name could
  resolve to an address it holds; sites Shop, Docs and a catch-all `**.starfleet.life`; certificates stored-but-
  unloadable, pending, manual DNS, failing three times, expired; an old-format and a new-format automatic ban and a
  manual one; two uploads through "Upload a certificate"; misses as real requests to the proxy with unserved Host
  headers). Shots `docs/hohenheim-ux-mockups-2026-09-30/shots/d13d/` (workspace repo): 01 Addresses: Yes, Not yet, No
  with where it points, the catch-all "Yes, names it catches point here", HTTPS "Per name", the upload's certificate
  line and expiry; 02 Certificates: Not issued yet, Waiting for a DNS record, Expired 3 days ago, Expiring (upload, 9
  days), Renewal failing, Failed to load, Works; header "Upload a certificate" and "Get a certificate"; 03 Blocked
  addresses: the old-format row "Tried names this server does not serve", Recent misses per address, Never block with
  "Change in settings". "Check again" was used from a row's menu ("Looked up again"). The held-address path is the
  journey's step 11 (this machine's own addresses), not the walk.
- Proof: build runs 1020 and the override build (`--skip-deps --thrash-override`: other sessions moved every upstream);
  test run 1024 (29 classes, 144 tests: 141 passed) with three causes fixed after it: the pre-check's unresolved half
  above, the check action's surface target (an invoke route, hand-edited), and `system.txt`'s Blocked addresses cases
  still naming the `active` filter W8b replaced by `blocked_now` (stale since W8b, hand-edited); run 1028
  (ManagePanelSurfacesBrowserTest, SystemSurfacesBrowserTest, CertificateAuthorityTest, DomainsListJourneyTest,
  HostnameReachBudgetTest, PutOnlineJourneyTest: 15 of 15). Journeys: DomainsListJourneyTest (step 1 rewritten for
  the catch-all and a regex, new steps 10 "Check again" and 11 a held address), CertificatesJourneyTest (new
  `everyCertificateIsListedByTheStateTheDashboardCounts` steps 1-5), AccessPagesJourneyTest (step 2 rewritten, new
  steps 6 Recent misses and 7 Never block), SecurityEventTypeLabelsTest (step 3 rewritten, new step 5: every cause
  reads uncounted in en and nl); baselines `certificates.txt`, `manage-slice-three.txt`, `system.txt`.

## 39. D13e decisions (fork D13e, 2026-10-09)

The sidebar seen in D13d's walk, DEP10 defect 10 (Settings, board Settings and Settings-Tasks) and the 2026-10-09
framework adoptions (activity face and subject filter, `standsUnder`, zenit-auth's person name).

- Home: no new mechanism in Hohenheim. `zemble home` for "one settings navigation listing the cluster's record pages,
  an icon on every section and a records card inside a section" answered UNCERTAIN between docs and zenit-cms and
  named `SettingsPage` (consumers Arcana, Thoth, QuirkyQuarters, Hohenheim) as the existing mechanism: the missing
  parts are zenit-cms's, listed under "Framework" below, not built here.
- Sidebar (item 1): Databases and Hosts were missing from D13d's shots because its scratch `local.dry` set
  `roles.databases`, `roles.instances` and `roles.stacks` to false (read from D13d's own walk command). A role that is
  off removes its peers and their routes on purpose (`HohenheimPanel.buildEntries` AIDEV-NOTE, `HohenheimRoles`,
  section 9: "a cluster lists only the members the node registered"; Hosts follows `hostWorkloadsEnabled`). Not
  changed: every role defaults to on, D13c's scratch kept them on and showed all eight rows, and an empty install with
  the default roles shows all eight (walk below). AdminNavigationJourneyTest step 13 pins that Databases and Hosts
  offer their create action.
- Pages of hidden entries stand under the row they are reached from (zenit-cms `PanelEntry.standsUnder`): every
  `showInNav(false)` entry of both panels that is no cluster member names its parent (instances' children under
  Instances, the DNS children (records, peers, zone peers) under DNS zones, the abuse-protection pages under its overview, access rules under
  Access lists, protected paths and previews under Sites, environments under Projects, templates' children and the
  template import and create-from-template pages under App templates, builds, releases, Put something online and the
  tenant's templates and Git connections under Apps, unexpected workloads under Hosts). Where the entry exists on a
  node without the obvious parent (builds and releases are registered for the proxy role too) it stands under Apps,
  which every node registers, since the panel refuses a parent naming no entry. Step 11 of the journey fails any
  entry of either panel whose pages would mark no sidebar row; step 12 pins four titles ending with their row. A
  child resource now declares its parent slug twice (`ResourceParent.of(slug, ...)` and `standsUnder(slug)`), both
  from the same constant; zenit-cms could derive the second from the first (raised, not changed).
- Settings (item 2): "App templates" and "Git connections" are the entries' own labels (plural and singular, en and
  nl, the git field label and the help texts naming the list); the stored model ids and slugs are unchanged. The
  abuse-protection backend's groups carry icons (root robot, scoring gauge, reputation star, events list, an unworded
  remote group folder); every group of Hohenheim's own sections already had one (AdminSettingsMountsTest step 6).
  Settings-Tasks: the six tasks the board lists already carry the board's names (SettingsTasksMountTest step 4);
  nothing else on that board is Hohenheim's to declare.
- Activity (item 3): `AdminActivityResource.TABLE` is `ActivityAdmin.table().toBuilder()` plus the app filter
  (`ActivityAdmin.subjectFilter("app", InstanceModel.MODEL_ID, "App")`), the record-id and the origin filters; the
  list uses `CmsSupport.WIDE_LIST.withFiltersOpen(true)`. An app is its workload's record (J1), so the filter offers
  instances through the instance record source and its row scope; a site-only app is not offered (its rows stay
  reachable by the record filter). The copied column list and the record column's own cell are gone:
  `ActivityRecordCell`, `cms/cell/activity-record.hwk` and `HohenheimTemplateIds.CELL_ACTIVITY_RECORD` are deleted;
  their replacement is the sentence's record link (`ActivityAdmin.sentenceCell`, rendered as
  `a.cms-activity-subject`), which already named and linked the same record.
- `RecordOwners.subjectLabel` reads a user through `UserModel.personName` (zenit-auth b7b39c4) instead of its own
  "name, else email" copy.
- Framework, not built here (zenit-cms unless named): (a) one settings navigation: the Settings cluster draws its
  members as a tab row above `SettingsPage`'s own section list, and the page cannot list sibling entries in that list,
  so the board's single list (sections, then App templates ... Tasks, then Framework) needs a cluster or
  `SettingsPage` option that lists the members in the section list instead of tabs; (b) a section header icon: a
  multi-group section (`SettingsPage.Mount`) has no icon and `SettingsPageRenderer` draws its header row with none
  (General, Backups, Notifications, Apps and deploys, Hosts, Abuse protection, Proxy); (c) zenit-comms' groups under
  Notifications (Channels, Delivery, Hub, Watches) declare no icons; (d) the Backup targets card inside the Backups
  section: a settings section cannot hold a records list, so backup targets stay the cluster's own tab; (e) the
  board's per-filter empty choice ("All apps", "Everyone"): `FacetInputs.anyLabel()` is one "Any" for every
  single-choice filter and `FilterSpec.placeholder` is a literal string, so the pickers read "App: Any"; (f) the
  Recent activity subtitle "Server" under a host row is `ActivitySources.subtitle`, which humanizes the stored model
  token (`ActivityText.humanizeModelToken("hohenheim:server")`, English only), not a label Hohenheim declares; "Host"
  needs the subtitle to read a declared, localized model noun; (g) Settings-Tasks' description line and "Nightly
  03:00" wording: `ScheduledTask.description()` is an unlocalized String the task list never shows.
- Walk (scratch server from the jar of the final tree, default roles, fresh database, `/setup`; by SQL: instances
  shop and blog and a generated engine instance on local, five activity rows by the administrator). Built in the
  pushed workspace (`zenit-dev workspace create`, this tree's diff applied on top): the live worktrees of protoblast,
  hawkeye, zenit and zenit-cms carry FW11's unfinished work, whose jar refused to boot (a new hydration-wire check
  failing on RouteTarget/FormEntryState inputs). Shots `docs/hohenheim-ux-mockups-2026-09-30/shots/d13e/` (workspace
  repo): 01 the empty install's dashboard with all eight rows; 02 Databases empty, "No databases yet" with "Create
  one" and "Create database"; 03 Settings with its tabs "App templates" and "Git connections" (the tab row beside the
  section list is still there, see (a)); 04 Activity in day bands, filters open, the App filter offering only apps
  with entries (shop 2, blog 1; the engine instance has none); 05 the log narrowed to shop; 06 Instance quotas, a
  hidden entry, marking Apps, titled "Instance quotas - Apps"; 07 the seeded dashboard, its Recent card still reading
  "Server" under local (see (f)). No Docker resource was created; the walk's scratch is deleted.
- Proof: build runs 1031 (live tree, which upstream FW11 work made unbootable) and 14 (pushed workspace); test run 15
  (pushed workspace, 21 classes, 50 tests: 49 passed) with one cause fixed after it: step 11 caught DNS records,
  whose admin entry was hidden without a parent (`DnsRecordParts.admin` now stands under DNS zones; the shared entry
  builder stays parentless because /manage shows it); run 17 (AdminNavigationJourneyTest,
  DatabaseDnsGitSurfacesBrowserTest: 2 of 2). Journeys: AdminNavigationJourneyTest (new steps 11-13),
  AdminActivityListTest (steps 1, 2, 7, 7b, 8, 10, 11 rewritten for the derived table and the sentence link; new
  `theAppFilterNarrowsTheLogToWhatHappenedToOneApp` steps 1-3), AdminSettingsMountsTest (new steps 6-7),
  SettingsTasksMountTest (new step 4), OwnerSubjectsTest (new step 1b). No panel-surface baseline changed.
