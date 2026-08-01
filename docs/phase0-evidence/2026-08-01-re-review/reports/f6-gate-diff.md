# F6 — RecordSourceRegistry gate-DIFFERENCE diagnostic

Status: zenit change COMPLETE, committed (zenit 380318d). Consumer verification below.

## Design chosen

`reportGateLoss` becomes a difference reporter (same event, same call sites, same
deliberate/override suppression):

- `permission`: `!Objects.equals` -> `permission(<derived> -> <explicit>)`, null rendered
  as `(none)` (the old absence case is now a special case of the arrow form).
- `accessCriteria`: dropped -> `accessCriteria(...)` (unchanged). Both present ->
  compared by REFERENCE IDENTITY -> `accessCriteria(replaced)`.
  Why identity and not presence-or-value: a lambda has no value equality. In the only
  lane this comparison exists (cms-derived glue vs an app declaration) the instances
  are ALWAYS different code, so identity is DETERMINISTIC there (never boot-to-boot
  flaky), and the repeated-boot-wiring false-positive lane does not exist structurally:
  a second explicit register is refused as shadowing BEFORE any facet comparison, a
  derived default arriving over a derived default returns early, and glue over an
  override is suppressed by the deliberate flag. The one silent case that must stay
  silent — byte-identical replacement — stays silent because a shared/non-capturing
  lambda IS the same instance (pinned by journey step 3).
- `loginRequired`: unchanged (derived-true/explicit-false).
- `createPermission`: compared only when BOTH sides are creatable ->
  `createPermission(<derived> -> <explicit>)`. Because `createPermission()` falls back
  to `permission()`, one view-permission swap on creatable sources reports BOTH lines
  (journey step 5) — the "one swap silently moves two gates" blind spot.
- `editUrl` / inline-create loss: NOT in the gate report (they are capability
  narrowings, not authorization widenings — mixing them in teaches operators to ignore
  a security event). They slog under their own event
  `zenit.data.source_capability_dropped` and NEVER fail boot, strict mode included;
  override(...) suppresses them the same way (same call sites).
- Weakness stays undecidable and undecided: Permission is an opaque dotted string; the
  only ordering in the ecosystem (PermissionResolver wildcards) orders GRANTS and needs
  the live grant graph, absent at registration time. The AIDEV-NOTE keeps that premise
  and replaces the wrong conclusion (silence) with difference reporting.

## Sweep — every explicit RecordSourceRegistry.register site, both workspaces

Net result: ZERO new gate-report firings anywhere; NO real widening found; NO app
changes required. The one genuine prior widening (hohenheim SiteModel ManagePanel) was
already converted to override(...) by yesterday's F14 arc (hohenheim f38c8d9).

### javaweb workspace

| Site | Model(s) | Verdict |
| --- | --- | --- |
| QQSources.java:45,51,56,64 | Being/Character/Lorebook/Memory | NO-COLLISION (peers are `Resource<T>`, not RowResource — glue only walks RowResources) |
| QQSources.java:71 | ActionDefinitionModel | SILENT (perm equal `qq.admin.access` both sides; resource creatable()=false) — capability slog for editUrl only |
| QQSources.java:76,84 | PlatformIdentity/Session | NO-COLLISION (models have no title/name display field -> derived default never registers) |
| AiRecordSources.java:31,36,49 | ModelConfig/ProviderConfig/McpServerConfig | NO-COLLISION (ModelConfig has no title/name field; the other two resources are `Resource<T>`, not RowResource) |
| ThothSources.java:26 | ThothClientModel | SILENT (perm equal, same `ThothPanel.ACCESS` object) — capability slog (editUrl + inline-create) |
| ThothSources.java:30 | ThothRequestModel | NO-COLLISION (no display field) |
| ProteusSources.java:41,51,56,61 | Identity/Realm/RealmClient/RealmPermissionGroup | SILENT (perm equal `proteus.admin.access`) — capability slogs (editUrl + inline-create) |
| ProteusSources.java:68 | LoginAttemptModel | NO-COLLISION (no display field) |
| spamservice ServerMain.java:99,103,110 | Client/Sample/SpamWord | SILENT (perm equal `spamservice.admin.access`) — capability slogs |
| orcono ServerMain.java:118,126 | Client/Project | SILENT (perm equal `orcono.read`) — capability slogs incl. dropped per-model createPermission (orcono.clients.write / orcono.projects.write): now VISIBLE via the capability event instead of silent |
| orcono EntityCandidateSource.java:30 | EntityModel | NO-COLLISION (custom source id) |
| ZenitMediaModule.java:41 | MediaModel | NO-COLLISION in-workspace (no production panel wires MediaResource); if wired, SILENT (MediaResource.requiredPermission() == media.view == the explicit permission) |
| ActivitySources.java:49 | ActivityModel | NO-COLLISION structurally (ActivityModel has no display field -> derived can never register) |

### hohenext workspace (hohenheim)

| Site | Model | Verdict |
| --- | --- | --- |
| HohenheimSources.java:64 | CertificateModel | NO-COLLISION (only `nice_name`, no title/name -> derived never registers; the resource's ACME scope-out therefore never produces a derived accessCriteria to compare) |
| HohenheimSources.java:72,78,83 | AccessList/Database/SiteAuthProvider | SILENT (perm equal `hohenheim.admin.access`) — capability slogs (editUrl + inline-create) |
| HohenheimSources.java:88,93 | SystemUserModel (x2) | NO-COLLISION (no exposing resource; second has custom id) |
| HohenheimSources.java:104,111 | DnsZone/Ban | NO-COLLISION (no title/name display field) |
| ManagePanel.java:124 | SiteModel | SILENT-BY-DESIGN — already `override(...)` (deliberate flag suppresses comparison from BOTH panels in both orders). This is load-bearing: derived permissions are `hohenheim.admin.access` / `hohenheim.manage.access` while the explicit source declares NO permission (accessCriteria-only) — without the override the extended diagnostic would fire twice |

Legitimate-difference-now-declared: none NEW (SiteModel was already declared).
Real-widening-found: none.

### Capability-slog population (deliberate, non-breaking visibility)

Every SILENT row above with "capability slog" starts emitting one
`zenit.data.source_capability_dropped` line per boot naming editUrl and/or
inline-create. This is the intended v1 posture: visible, never fatal, silenced by
override(...) if a maintainer decides the loss is deliberate.

## Side findings surfaced (NOT fixed here — outside this task's file set)

1. orcono ServerMain.java:117,125 wraps its registers in
   `if (RecordSourceRegistry.INSTANCE.byId(...) == null)` — this defeats the registry's
   both-boot-orders comparison design: if the derived default ever registers first, the
   explicit source is silently never offered. Today safe only by module-init ordering.
   The guard should be dropped (plain register(); the registry already refuses
   shadowing).
2. spamservice ServerMain.java:98,102,109 guards with `byToken("spamservice:client")`
   etc. — byToken splits on '.', not ':', so the guard always sees null and the blocks
   re-register on every call; a second configureCms() call would hit the shadowing
   refusal.
3. (pre-existing, from the sweep) hohenheim CertificateModel / DnsZoneModel / BanModel
   have NO derived default at all because their models lack title/name display fields —
   meaning the CMS relation-picker glue never covers them; only their explicit sources
   do. Not a defect, but worth knowing when reading the table above.

## Pre-fix failures (verbatim)

Method: five temporary probe tests (one per blind spot) were added to
`zenit/src/test/java/be/elevenways/zenit/data/RecordSourceTest.java`, run against the
UNMODIFIED registry at zenit HEAD (then b9acefe), recorded, and deleted; the permanent
8-step `gateDifferenceJourney` subsumes them. Run:
`zenit-dev test --unit --skip-deps --class RecordSourceTest --method "probe*"
--no-fail-fast` -> 10 of 10 failed (5 probes x SQLite/PostgreSQL). Verbatim (SQLite
lane; PostgreSQL identical), log `~/.local/share/zenit-dev/test-logs/20260801-111459.log`:

```
RecordSourceTest > [1] SQLite > probePermissionSwapIsDetected() FAILED
    org.opentest4j.AssertionFailedError: probe 1: a swapped permission (records.people -> records.anyone) must be refused ==> Expected java.lang.IllegalStateException to be thrown, but nothing was thrown.
RecordSourceTest > [1] SQLite > probeAccessCriteriaReplacementIsDetected() FAILED
    org.opentest4j.AssertionFailedError: probe 2: a replaced accessCriteria (here: one that scopes NOTHING) must be refused ==> Expected java.lang.IllegalStateException to be thrown, but nothing was thrown.
RecordSourceTest > [1] SQLite > probeCreatePermissionSwapIsDetected() FAILED
    org.opentest4j.AssertionFailedError: probe 3: a swapped createPermission must be refused ==> Expected java.lang.IllegalStateException to be thrown, but nothing was thrown.
RecordSourceTest > [1] SQLite > probePermissionSwapMovesTheCreateGateToo() FAILED
    org.opentest4j.AssertionFailedError: probe 4: one view-permission swap on creatable sources moves TWO gates ==> Expected java.lang.IllegalStateException to be thrown, but nothing was thrown.
RecordSourceTest > [1] SQLite > probeEditUrlAndInlineCreateLossIsVisible() FAILED
    org.opentest4j.AssertionFailedError: probe 5: losing editUrl and inline-create must be slogged: [] ==> expected: <true> but was: <false>
```

i.e. every widening/loss shape went completely UNDETECTED pre-fix: silent install, no
throw, no log line.

## Post-fix verification

- zenit: `zenit-dev test --unit --skip-deps --class RecordSourceTest --no-fail-fast`
  -> 32/32 PASSED (16 journeys x SQLite/PostgreSQL), log 20260801-111646.log. Includes:
  - NEW `gateDifferenceJourney` (8 steps): permission swap loud in BOTH boot orders with
    the identical message `permission(records.people -> records.anyone)`; replaced
    accessCriteria -> `accessCriteria(replaced)`; SAME-instance scope replacement stays
    SILENT (the identical-re-registration pin); createPermission swap named alone
    (unchanged view permission NOT blamed); one view-permission swap on creatable
    sources names BOTH `permission(...)` and `createPermission(...)`; editUrl +
    inline-create loss slogs `zenit.data.source_capability_dropped` WITHOUT throwing
    even in strict mode; `override(...)` accepts a DIFFERENT permission (not just a
    dropped one); lenient mode slogs `zenit.data.source_gate_dropped` and the explicit
    source still wins.
  - Existing `accessDeclarationJourney` steps 7-10 still green (step 7's assertion
    updated to the arrow form `permission(records.people -> (none))`; step 8's
    byte-identical replacement still silent; step 9 override in both orders; step 10
    lenient winners unchanged).
- No downstream test pins the old message/event (workspace grep: zero hits outside
  zenit's own RecordSourceTest).
- zenit-cms consumer run: see Commits section below.
