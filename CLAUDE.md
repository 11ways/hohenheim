# Hohenheim

Reverse proxy / app manager. Starts and supervises backend processes (node, static sites, proxied upstreams), assigns ports/sockets, handles Let's Encrypt TLS, shared caching, auth, and a web admin UI. This is the Zenit-based rewrite of the original Node.js/AlchemyMVC Hohenheim.

## Stack

- Java 25, Gradle (source sets: `common`, `client`, `server`, `browserTest`)
- Zenit (web framework), zenit-cms (admin panel), zenit-forms, zenit-auth,
  Hawkeye (templates), Protoblast (utilities), Plumage (UI components)
- TeaVM compiles `client` + `common` to `public/cms.js` (the zenit-cms shell
  hardcodes `<script src="/cms.js">`)
- SQLite via `sqlite-jdbc` (file: `hohenheim.db`)
- acme4j for Let's Encrypt, BouncyCastle for PEM
- Playwright for browser tests

## Layout

- `src/common/java` shared between server and client (TeaVM)
- `src/common/templates` Hawkeye `.hwk` files, namespace `hohenheim`
- `src/common/scss` styles
- `src/client/java` browser code (TeaVM-compiled)
- `src/server/java` Undertow-backed server, entry point `be.elevenways.hohenheim.server.ServerMain`
- `src/browserTest/java` Playwright end-to-end tests
- `docs/` design notes (see `architecture-site-types.md` for the site-type registry)

## Build and run

Use `zenit-dev` for all build/test/run cycles. Do not invoke `./gradlew` directly
(a harness hook refuses it; do not work around the hook).

- Prefer the zenit-dev MCP tools (`zd_build`, `zd_test`, `zd_verified`,
  `zd_test_log`, `zd_status`, `zd_wait`): they return the verdict as DATA.
  Never judge success from output text, and never pipe `zenit-dev` through
  `tail`/`grep` -- the pipe replaces the load-bearing exit code.
- Before running any test, check `zd_verified` / `zenit-dev verified`: a green
  receipt at the current worktree fingerprint IS the verification, including one
  produced by another agent. `reused: true` in a result IS a pass; bypass only
  with `--repeat <mode> --reason "..."` when investigating flakiness or
  environment drift.
- "attached to j-..." means an equivalent run is already executing and this one
  shares its result; never kill or re-issue it.
- verdict PASSED_BUT_STALE means sources changed mid-run: the result proves the
  tree the run STARTED from. Finish edits, then run once.

### The live lane (a skip is not a pass)

Most of this suite needs a real host: a Docker socket, a private netns, an
enrolled Incus or ssh host. Those tests gate themselves, and a gate that
silently aborts used to make a run of nothing look green.

- Ask through `test/live/LiveLane` -- `require(Need.X, condition, reason)` or
  `requireImage(docker, ref)` -- NEVER a bare `assumeTrue`. The need is what
  makes the skip visible; a prose-only abort is invisible to the report by
  construction (it lands under `unclassified`).
- Every forked JVM prints a LIVE LANE REPORT naming what it skipped and why,
  and `zd_test` reports the skip COUNT as data. Read both.
- `requireImage` PULLS a missing image rather than skipping: a cold image cache
  on a working daemon is one command away from running the test.
- A host that can satisfy a need DECLARES it:
  `zenit-dev test ... --define hohenheim.live.require=docker-socket,netns` (MCP:
  `zd_test` `define: ["hohenheim.live.require=docker-socket,netns"]`) turns a skip
  for those needs into a FAILURE. It must be `--define`: a bare `-D` or
  `GRADLE_OPTS` reaches the gradle daemon only, never the forked test worker, so
  the declared need silently stays a skip and the run still reports green. A
  declared property is part of the run identity, so a declaring run never reuses a
  plain green receipt. Unset means report-only, which is the default
  because whether a skip is a defect is a property of the HOST, not the test.
- Every live-capable test class carries `@Tag("slow")` and is listed (by name or
  by the `*Live*`/`*Incus*` globs) in `.zenit-dev.json` nonHermeticClasses.
  Ordinary `zenit-dev test` excludes them (the default lane finishes in
  minutes); `zenit-dev test --all` runs everything, and any `--class` filter
  also runs slow classes. Run the full `--all` suite before a release or after
  touching the docker/incus/remote-host layers -- a live test nobody runs is
  worse than a slow one. `SlowLaneGuardTest` enforces the tag + declaration
  pair, so a new live test cannot silently land in the default lane.

## Architecture notes

- The admin UI is a zenit-cms panel served at `/admin` (`server/cms/HohenheimPanel`):
  typed `RowResource` peers for sites/domains/certificates/access lists/auth
  providers/databases/servers/notification channels, zenit-cms's readonly
  `ActivityResource` over the framework activity log, the framework
  `SettingsPage` at `/admin/settings` (two mounts: the hohenheim context
  editing `settings/hohenheim.dry`, plus zenit's `ServerSettings` editing
  `settings/local.dry`; DIFF-based save, secrets masked, restartRequired
  metadata drives the restart toast), and `RecordScopedPage` tabs on sites
  (Domains, Protected paths on every non-passthrough site, Deployments on a site whose
  instance carries a git source, Dev sessions)
  and databases (Restore). `HohenheimSettings` roots at its OWN `hohenheim`
  group (the standard consumer shape); its file keys stay flat
  (`proxy.http_port`) because the context root maps the file root. Tests
  redirect the editable file via `-Dhohenheim.settings`. Mutations are
  recorded by zenit's `ActivityLog` (enabled in `settings/default.dry`;
  behaviour verbs via `ActivityLog.withAction`) and routing-relevant writes
  rebuild the proxy via `ProxyReloadHooks` on the global model-hook tier --
  there is no per-resource audit/reload plumbing. Host-declared endpoints
  beside the panel (downloads, uploads, process control, terminal WS) live in
  `HohenheimEndpoints` + `HohenheimHandlers`.
- A site has ONE typed UPSTREAM: what its hostnames resolve to. Upstream kinds are
  registered via `UpstreamKinds` (common) + `UpstreamKindHandlers` (server); each kind
  declares its settings schema (which drives the discriminated settings sub-form in the
  CMS) and its request handler. Adding a kind means implementing one class -- no edits to
  existing dispatch code. See `docs/architecture-upstream-kinds.md`.
- The six kinds live in `src/server/java/be/elevenways/hohenheim/server/upstream/kinds/`:
  `StaticUpstreamKind`, `RedirectUpstreamKind`, `AddressUpstreamKind`,
  `InstanceUpstreamKind`, `TlsPassthroughUpstreamKind` (a `TlsPassthroughProvider`, not an
  HTTP handler) and `DevNamespaceUpstreamKind`. The request-handling PLUMBING stays in
  `server/sitetype/` (forwarder, targets, health, bridges, `StaticFileHandler`) -- read
  that package name as "request handling", not as "site type".
- What a site RUNS is no longer a site question: the workload kinds (`docker`, `node`,
  `java`, `command`, `alchemy`) were deleted on 2026-08-22 and their successor is the
  INSTANCE kind (`hohenheim:workspace`, `hohenheim:application`, ...). A site that serves
  one names it through the real `sites.instance_id` column, and the git SOURCE lives on
  that instance's settings, never on the site.
- The console of an instance is the KIND SETTING `console_kind` (`ConsoleKind`, one home):
  `plain` = output on the socket, commands through the form; `tty` = the primary process
  behind a real pseudo-terminal, keystrokes + resize on the socket (the Janeway console).
  See `docs/interactive-console.md`; never a second interactive lane beside
  `InstanceConsoles.attach`.
- A template DECLARES the managed databases its instances need
  (`instance_template_databases`); create-from-template allocates them through
  `TenantDatabases.allocate` on the instance's host and attaches them under the declared
  env prefix. The seeded WordPress templates are the reference consumer
  (`docs/wordpress.md`); never a hand-attached database in a seeder or a second
  allocation lane.
- Handlers are long-lived: created when a site loads, updated on config change, destroyed on removal. Not per-request.
- ClientMain MUST call `HohenheimModels.registerAll()` + `HohenheimSources.register()` before `ClientZenitRuntime.main` (the browser has no MODELS/MODULES boot stage).

## Tasks, roles and operator visibility

A role-owned `ScheduledTask` declares its schedules through
`HohenheimRoles.schedulesWhen(List.of(...), Role...)`, never a bare list. A bare
list compiles, discovers and reconciles fine and then RUNS on nodes that do not
host the capability -- `TaskService` knows nothing about roles and almost no
executor self-guards. The two genuinely node-agnostic tasks
(`BackupControlPlane`, `CleanOldActivity`) use a bare list on purpose. Reading
roles before `HohenheimSettingsFiles.load()` throws; tests get their snapshot
from `HohenheimTestRuntime.ensureBooted()` unless they need a restricted set,
which they capture themselves before booting.

Declaring a task obliges naming it in `HohenheimTaskBootstrapTest`: its `PINNED`
list is compared for EXACT equality against the discovered `TaskCatalog`, so a
new task fails the suite until a human has looked at it. Nothing else there is
hand-maintained -- which tasks must reconcile into `system_task`, which must fire
at boot and which must stay quiet are all derived from the tasks' own
`ScheduleKind` declarations, so a pin and a declaration cannot disagree.

A failure state an operator must find without looking lands on an
`AttentionCollector` item or `Alerts.send`. A getter, a log line or an internal
state field is not visibility: the Aug 2026 six-day HTTPS outage had accurate
state in `getHttpsState()` that reached neither (it now feeds
`AttentionCollector.failedProxyListeners`). Status pages, the activity log and
flash toasts are operator-visible but pull-only or action-scoped -- they do not
make a background failure find anyone.

A new alert event is a constant plus an `ALL` entry, and that is the WHOLE
declaration -- the OPPOSITE of the task pin. Coverage is derived:
`NotificationAdminTest` asserts the rendered vocabulary `isEqualTo(ALL.size())`,
and the admin select and validation both read `ALL`, so no test edit is owed. To
test one end-to-end: inline `CommsDispatcher` + `webhook://default` + a local
`HttpServer`, per `CertExpiryAlertTest`.

## Migrations

The INSTALL schema is one migration,
`src/common/java/be/elevenways/hohenheim/migration/InitialMigration.java`
(version `001`), which creates the final schema directly. The M003..M092 chain
was folded into it on 2026-08-13 (`docs/migration-consolidation-2026-08-13.md`
carries the schema-diff proof); older docs in `docs/` still cite `M0xx` class
names as provenance for when something landed, and those citations are history,
not files you will find.

- Every APPLIED migration is FROZEN (2026-08-29), not just `InitialMigration`.
  A deployed install's `zenit_migrations` row stores each applied migration's
  structural checksum, so editing an operation in one makes the next
  `--run-migrations` rehearsal REFUSE under `database.migration_integrity=fail`.
  The rule is ONE declared fact plus a committed table:
  `MigrationIntegrityTest.DEPLOYED_THROUGH` is the highest version every deployed
  install has applied (`003` today), and `src/browserTest/resources/migration-pins.txt`
  carries one `<class><TAB><digest>` line per migration at or below it. Editing a
  pinned migration fails the build naming both digests; a migration below the mark
  with no pin fails it as "deployed but unpinned" and the failure prints the lines
  to paste. A migration ABOVE the mark is deliberately unpinned -- nothing has
  applied it, so editing it is still free. A deploy that applied migrations raises
  the mark and adds the pins (`docs/deploy-starfleet.md` step 8); a pin is never
  regenerated to make a red build green. Comments and formatting are outside the
  digest.
- A schema change APPENDS a migration in the same package and stream, numbered
  from `M002_` upwards (`M002_ManagedDatabaseFailureReason` is the first), using
  the ORM DSL (`alterTable` + `addColumn`/`dropColumn`), never raw SQL. Existing
  rows are real now, so a column added to a populated table is nullable or
  carries a default, and a backfill is a `builder.data(...)` step -- never a
  hand-written `execute(sql)`.
- Adding a TABLE means editing BOTH `up()` and `down()` of the migration that
  creates it. `MigrationIntegrityTest` rolls the install schema back and counts
  what survived, so a table created and not dropped fails the suite by name.
- Default records are the `Seeder` SPI's job (SEED boot stage), never an
  INSERT in the migration -- `SpamserviceInstallationSeeder`, `RuntimeImageSeeder` and
  `LocalServerSeeder` are the examples.

## Conventions

- Follow the Hawkeye skill for `.hwk` templates; follow the Zenit skill for endpoints, models, migrations, settings.
- Never duplicate document-level boilerplate across templates — use `extend`.
- Before using a framework feature, find an existing working example in this repo or in the Zenit/Hawkeye/Plumage sources.
- Fix the framework rather than working around it in app code.
