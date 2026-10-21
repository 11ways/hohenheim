# Hohenheim history v3 (lane B4) handoff

Workspace: /home/skerit/projects/zenit-workspace-histv3. zenit 45781529 detached, zenit-cms 9d6123b6, zenit-auth
a106ff3, zenit-sftp a86d0dd, spamservice debe7c3 (each its origin/wip/history-v3). Hohenheim branch `history-v3`,
pushed to `origin/wip/history-v3`.

## Done (first half, 2057736c)
- Declarations on all 56 models, HohenheimActivityAction shapes/subjects, every writer moved, system purposes.

## Done (second half)
- 1de76e4a: host trust acts are typed facts on the host's save (host_key_pinned/verified/repinned,
  host_identity_rotated, posture_risk_accepted, admission_cordoned/uncordoned/admitted); a forced database or engine
  delete carries `forced` on its delete Change (DatabaseService.forceDestroyRecord, DatabaseEngines.forceDestroy).
  M018_ConvertHohenheimHistory maps stored "created"/"updated"/"deleted" legacy facts to Outcome.copy(); the three
  legacyKey registrations are gone.
- 11e98104: browserTest helper RecordedChanges (changes + action + facts of a record, over HistoryTestSupport);
  B4 tests ported (InstancePowerAudit, SoftDeleteJourney, HostPostureAcknowledgement, SiteApi, SiteTrashJourney,
  AccessListEnforcement, StackAudit, Alerts, HostHealthFromRecord, DashboardAttention seeding, HohenheimUpgradeJourney
  reads); HistoryDeclarationsDriftTest; InstanceModel.stoppageFact is the one cause-fact builder; release operations
  offer no History tab (untracked); stale ActivityLog doc comments rewritten.

## Done (third wave, against zenit 0e96744b + zenit-cms c7b2dd9b)
- Readers on the CP2d admin API: AdminActivityResource (EntryCells cell with sentenceCell(entry, reader, target)
  linking SUBJECT_MODEL/SUBJECT_ID, ORIGIN/SUBJECT_ID leaves, peopleOnly = origin rule + ActivitySources.recent(),
  no onePerCommand), ManageDashboard/AppOverview/ServerOverviewState sort on ActionModel.CREATED_AT.
- SiteModel RevisionableBehaviour.except(SECURITY_REPORT_TOKEN); SecurityReportEnvTest asserts no site revision
  snapshot holds the minted key (not yet run green: boot blocked, see below).

## Done (fourth wave, f194e6eb, against zenit 45781529 + zenit-cms 9d6123b6)
- 1b2eb465: Recent cards (AppOverview.recent for sites/instances/databases, the host band) read
  AdminActivityResource.recentFor: History.inContext(model, id, reader) narrowed by the activity source's rules
  (ActivitySources.recent() / peopleOnly()), drawn as an ItemListWidget. ActivityRules.forRecord and peopleOnlyFor
  are gone. The admin log's sentence links the action's one change's record when the action has no subject.
- f194e6eb: every browserTest class reads RecordedChanges (now also `about(ds, subject)` and `forget`) or seeds
  HistoryFixtureRows (AdminActivityListTest, LandingDashboardSurfacesTest, ManagePanelJourneyTest, AdminPagesTest
  label probe). SoftDeleteJourneyTest step 3: snapshot assignment refused (SNAPSHOT_ASSIGNMENT), nothing written,
  find scoped, an allowed set-based update (security_report_token) matches 0. The browserTest source set compiles.

## Remaining
0. BOOT BLOCKED upstream (still on zenit 45781529): FieldLabelAudit refuses the admin panel (zenit:activity actor,
   command_type, created_at, origin, subject_id; record page headed "Action"). Folio's zenit wip/history-v3-misc
   ships the labels, ActivityModel's deregistration after M015 and the migrateBefore overload.
1. Once it boots: run the B4 set, then the full browser lane once. LandingDashboardSurfacesTest's stored
   landing-dashboards.txt will need its Recent row re-reviewed (the web action now names the live landing-site).
2. HohenheimUpgradeJourneyTest step 1a/1b: port onto the migrateBefore(set, acknowledgements) overload.
3. Run SecurityReportEnvTest green once the boot is unblocked.
4. HohenheimSettingsBoot:50 ActivityLog.RETENTION_DAYS, AdminPagesTest ActivityLog.ENABLED and SiteWrites:174
   CREATE.happened() stay until core moves them. Migration tests keep raw zenit_activity SQL (old-release seeds).
5. The app filter counts through the change rows since zenit-cms 2e5088b6 (History.subjectFacet). Hohenheim's own
   subject_id text filter still reads ActionModel.SUBJECT_ID, which only an operation stamps: a plain save is in no
   record filter. Known limitation, not ported.

## Full browser lane, run 27 (74b7f547, zenit c20f4060, zenit-cms 2e5088b6): 46 of 1651 red
Lane module heads moved since: zenit-notifications 2dbec9e, zenit-media 77b5b45, zenit-microcopy 1120b88,
zenit-sftp 7a1c74a (they had wip/history-v3 heads the first fetch missed). B4 set run 25/26: SoftDelete,
SiteTrash, AccessListEnforcement, StackAudit, Alerts (B4 cases), OfflineCommandLane, OperationHistorySurfaces,
HostHealthFromRecord, HostPostureAcknowledgement, InstancePowerAudit, SecurityReportEnv, HistoryDeclarationsDrift,
DashboardAttention, SiteHistory green.
COMMITTED 1bf68c67 AND PUSHED, NOT YET RUN: GeneratedRows.enter keeps the outer act's attribution inside a running
transaction or command (fixes ATTRIBUTION_CHANGED for origins acme/game_domain/database/application:
DnsZoneApiTest, DnsEditGrantReadWire, TenantHostnameIsolation, GameDomain*, GameServerPutOnline*, WordPressTemplateSeed,
TenantDatabaseSurface x8, EngineConfigurationSave, DatabaseEngineEnvironmentSeal, SharedDatabaseEngine x2,
ApplicationPushPolicy). Run those first.
Hohenheim-caused, still to fix:
- AppOverviewJourneyTest step 4: "uploaded a file to" appears twice per row; assert the two actions' detail links.
- AdminPagesTest:334: the site create has no subject; list by filter.model=hohenheim:site instead of subject_id.
- ManagePanelJourneyTest step 2: fixture actions have no subject, so no "App" subtitle; seed the subject like
  AdminActivityListTest.act (and core gap below).
- SiteRefusalVerdictJourneyTest:64, StaleRecordTrashTest, RoutingProblemsTest: fixtures updateAll a site snapshot
  field (instance_id, upstream_kind): SNAPSHOT_ASSIGNMENT. Save each row instead.
- InstanceHealthMigrationTest: FrozenModel "zenit_activity" after core M015; use HistoryRows.activityTable() or
  HistoryRows.legacyRow.
- MigrationIntegrityTest step 2, NeverProbedHostMigrationTest step 3: M017_InstanceHealthOnTheRow (main) reads
  zenit_activity, gone once core M015 converts it; M017 must read the v1 table or run before core M015.
- HohenheimUpgradeJourneyTest step 1: "the reconciler rewrote the old spellings" (after the notifications move).
- LandingDashboardSurfacesTest: review build/panel-surfaces/landing-dashboards.txt and update the stored baseline.
- AlertsTest alertsAlwaysReachEveryAdministratorsInbox step 7, PreviewMechanicsTest step 3 (failed vs expired),
  OperatorTrustedTargetsTest step 1 (upsert), PortLedgerOverlapTest (OTHER_DATASOURCE): not yet triaged.
Core / module-caused (report to Folio):
- IntegerField 'id' received non-numeric String in zenit:save_record_sharing (zenit-auth GrantMatrixOperations:138
  passes SharedRecord.recordId as String; Hohenheim sites have Integer ids) and POST /zn/auth/sharing/invite:
  ManagePanelTest x2, ConvertedEntryAccessTabTest step 2, InstanceInviteJourneyTest x2.
- SiteApiTest:162: an API-key site create records origin zenit:web, not zenit:api (ResourceWrites.create from
  SiteApi.java:70; header origin from OpenAction.java:202).
- EnvironmentSecretsTest:213: a secret map's delta is withheld whole ("environment_variables": "[redacted]"), so the
  changed key is no longer named (it was in v1).
- zenit-cms ActivitySources.subtitle (ActivitySources.java:154) reads SUBJECT_MODEL only; a subjectless action has
  no record noun (recordTarget falls back to the one change, the subtitle does not).

## Round 5 (f445000e, pushed; zenit 0038a8ec, zenit-cms 2e5088b6, zenit-auth a106ff3)
- dc0117d1: M017 reads zenit_activity before core M015 and LegacyFacts.rows after (Folio ruling e: two-way read).
  InstanceHealthMigrationTest seeds v1 rows through HistoryRows.legacyRow before M015 and proves both orders.
- ea4747ac: StaleSiteRows writes refused site shapes raw (SNAPSHOT_ASSIGNMENT fixtures: SiteRefusalVerdict,
  StaleRecordTrash, RoutingProblems); AppOverview step 4 counts each upload action's link; AdminPages lists the
  unfiltered log; Alerts step 7 allows only the notifications module's own changes (DESIGN-v3.2 line 825);
  HohenheimUpgrade step 1/6 read stored spellings (OP-5); LandingDashboard fixture uses origin tokens, baseline
  patched (uuid link, clock-rotate-left icon, live title "landing-site"; "Site" subtitle kept, pending core d).
- f445000e: EnvironmentSecretsTest also requires the withheld marker.
- Full lane run 33 (zenit b0e17e23): 12 of 1651 red. Run 34 (zenit 0038a8ec): SiteApi, ConvertedEntryAccessTab,
  InstanceInvite x2, ManagePanel x2 green.
## Red after round 5, all core/module (all green in round 6 run 41)
- LandingDashboardSurfaces, ManagePanelJourney step 2: subtitle noun (d), Proteus.
- EnvironmentSecrets:213: the 0038a8ec secret-map fix does not reach a secret() map nested in a SchemaField
  (InstanceModel.SETTINGS.environment_variables): delta is still environment_variables "[redacted]".
- HohenheimUpgradeJourney step 6: History.java:710 per-record reads match ChangeModel.MODEL.eq(today's id) only;
  converted rows keep "zenit-auth:user" (record id is the UUID), so History.ofRecord(UserModel) finds nothing.
- OperatorTrustedTargets step 1: Model.upsert's recorded branch (Model.java:824-827) skips afterStoredWrites, so
  the row's write intent is never completed.
- PortLedgerOverlap: zenit-auth RecordGrantCleanup (unscoped DoomedRows pair) revokes grants on the ambient
  datasource inside a delete .on(other): OTHER_DATASOURCE.

## Round 6 (eac8ce7f, pushed; zenit a88bd5c6, zenit-cms 59ee5e78, zenit-auth 886e8aa)
- 23a88256: EnvironmentSecretsTest asserts HistoryText.detail(entry, reader) (names SITE_GREETING, "withheld").
- eac8ce7f: landing-dashboards.txt /admin Recent row icon is HistoryText.mark's CREATE "plus" (was the constant
  clock-rotate-left); its subtitle stays "Site" (first listed change's noun). ManagePanelJourney step 2 green.
- Full browser lane run 41: 1651 of 1651 green.
- java-rewrite MERGE (05bc2639, no conflicts) is pushed only to origin/wip/history-v3-java-rewrite: it does not
  compile on the wave (DomainParts:492 Plain.withContextHelp needs zenit master b5a3d6fd, which zenit
  wip/history-v3 lacks; zenit-forms has no wave branch and its checkout lacks 2832a5e). zenit wave + master merge
  cleanly (merge-tree). Once zenit's wave carries b5a3d6fd, wip/history-v3 fast-forwards to 05bc2639.
