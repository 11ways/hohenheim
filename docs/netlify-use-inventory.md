# The Netlify-use inventory

Written 2026-10-06. Every capability people use Netlify for, with Hohenheim's verdict, its evidence, and where a
Hohenheim admin user meets it today. It sits beside `coolify-use-inventory.md` (PaaS), `pterodactyl-use-inventory.md`
(game panel) and `npmplus-use-inventory.md` (proxy manager), and it feeds the admin redesign
(`admin-redesign-plan.md`): which features still need building, and which existing features still need a designed
task screen.

Many PaaS capabilities Netlify shares with Coolify (git webhooks, previews, health-gated atomic rollout, rollback,
deploy logs, API) are already proven in `coolify-use-inventory.md`. Those clauses here cite the Coolify clause for the
mechanism and add only what is Netlify-specific and what changed since (the git source moved from the site to the
instance on 2026-08-22, and the release engine moved to `S/application/`).

Verdicts:

- **IMPLEMENTED** -- the mechanism exists AND a named test asserts it.
- **PARTIAL** -- precisely what works and precisely what does not. Code with no test found is PARTIAL, and the
  clause says "untested".
- **OPEN** -- missing and wanted.
- **REJECTED (argued)** -- a decision argued from the shape of this product. Where the argument is this document's
  proposal rather than a recorded owner decision, the clause says so.

### How this document was built

Product sources (fetched 2026-10-06):

- Documentation map: https://docs.netlify.com/
- Deploys (atomic deploys, contexts, previews, branch deploys, rollback, locking, permalinks, notifications):
  https://docs.netlify.com/deploy/deploy-overview/
- Build configuration (base, publish, command, image, dependency cache, build hooks, stop builds):
  https://docs.netlify.com/build/configure-builds/overview/
- Environment variables (scopes, deploy contexts, secrets, shared variables):
  https://docs.netlify.com/build/environment-variables/overview/
- Redirects and rewrites: https://docs.netlify.com/routing/redirects/
- Security and access (password protection, firewall traffic rules, rate limiting, RBAC, audit log):
  https://docs.netlify.com/manage/security/overview/
- Monitoring (observability, web analytics, logs, log drains, split testing, notifications):
  https://docs.netlify.com/manage/monitoring/overview/

Hohenheim sources: the code at commit `2eb75017`, read file by file. Every `path:line` below was checked
mechanically against `git show 2eb75017:<path>`. While this was written, a concurrent session had uncommitted edits
in `src/server/java/.../server/cms/`, so line numbers in `cms/` files may have moved in the working tree; the commit
is the reference.

Path abbreviations: `S/` = `src/server/java/be/elevenways/hohenheim/server/`, `C/` =
`src/common/java/be/elevenways/hohenheim/`, `T/` = `src/browserTest/java/be/elevenways/hohenheim/`.

"Surface" names where an admin user meets the capability today and which redesign board (if any) draws it. Board
names follow `admin-redesign-plan.md` section 3 (Main, Apps-List, Domains, Online-1..4, App-Overview, App-Problem,
App-Blocked, App-Console, App-Protect, Host-Admit, Activity, Settings). The board generator (`boards.py`, git-ignored,
in the workspace's `docs/hohenheim-ux-mockups-2026-09-30/`) also holds an **App-Deploys** board ("App: deploys,
previews and rollback": the deploy list with logs, "Roll back to" a named commit, a Previews card with "Preview a branch",
and an "Automatic deploys" card with the source, the push behaviour, the builder and the webhook address) that the
plan's section 3 does not list yet; it is named below because it is the Netlify-shaped board.

### The counts

| verdict | count | clauses |
| --- | --- | --- |
| IMPLEMENTED | 18 | 1, 4, 6, 7, 8, 10, 13, 14, 17, 20, 24, 29, 30, 32, 39, 40, 44, 46 |
| PARTIAL | 14 | 2, 3, 9, 11, 12, 16, 18, 21, 25, 26, 33, 35, 38, 50 |
| OPEN | 9 | 5 (build cache), 15 (build hooks), 22 (repo config file), 23 (redirect rules), 27 (custom 404), 28 (cache headers), 31 (auto subdomain), 34 (preview-only protection), 36 (rate limits) |
| REJECTED (argued) | 9 | 19 (permalinks), 37 (analytics), 41 (forms), 42 (functions), 43 (edge functions), 45 (split testing), 47 (blobs), 48 (CDN), 49 (identity) |

Of the 18 IMPLEMENTED, 4 have no designed task screen (20, 24, 39, 44) and 2 need none (14, 17); see the closing
list. Defects found while writing are under "Findings".

---

## Deploy pipeline

## 1. Git-connected continuous deploy

Netlify: connect a repository, every push to the production branch builds and publishes.

**IMPLEMENTED** for applications and workspaces; mechanism and proof in `coolify-use-inventory.md` 3. Since that
clause, the git source lives on the instance kind's settings (`C/source/GitSourceSchema.java:63`, added by
`S/instance/ApplicationKind.java:50` and `S/instance/WorkspaceKind.java:73`), the receiver is
`S/source/GitWebhookHandler.java:62` (`/api/webhooks/git/`), and a push dispatches to the workspace build (`:410`) or
the application release (`:413`).

- Tests: `T/test/source/GitWebhookSecurityTest.java:123`, `T/test/application/ApplicationPushPolicyTest.java:99`.

Surface: instance create/edit form (git fields), instance tab "Deployments" (shows the webhook URL), git providers at
`/admin/git-providers`. Boards: Online-1 ("App from a Git repository"), App-Deploys ("Automatic deploys" card),
Settings ("Git connections").

## 2. A static site built from git and published

Netlify's core case: build a repository, publish one output folder.

**PARTIAL.** What works: a workspace instance with the seeded `static` runtime image serves the files of its data
directory (`S/instance/RuntimeImageSeeder.java:95`) and builds its checkout under `app/`
(`S/instance/WorkspaceBuilds.java:59`), so a git push rebuilds a static site. What does not: there is no "publish
directory" (the image serves the home folder, not the build output, so the operator arranges the path by hand
[unverified how existing users do it]); the site-level static kind serves a host directory, is not git-fed and is
refused for tenant sites (`S/upstream/kinds/StaticUpstreamKind.java:112`); a workspace deploy has no rollback by design
(`S/cms/InstanceDeploymentsPage.java:54`, "its home volume IS its state"); whether the old files keep serving until a
build succeeds is [unverified].

Surface: instance form plus a site with an Instance upstream. Board: Online-1 ("Static files": "Serves a folder of
HTML, CSS and images", which reads as the host-directory kind, not a git build).

## 3. Build settings

Netlify: base directory, build command, publish directory, package directory for monorepos, build timeout.

**PARTIAL.** Build command (`C/source/GitSourceSchema.java:29`), a build directory that is only the command's working
directory (`:30`, applied at `S/instance/WorkspaceBuilds.java:353`), build-time variables separate from runtime ones
(`C/source/GitSourceSchema.java:134`), Docker build arguments (`S/instance/ApplicationKind.java:93`), and a timeout
that may only tighten the host cap (`S/application/ApplicationReleases.java:576`). No publish/output directory (see 2).

- Tests: `T/test/instance/SourceDeclarationGateTest.java:29` (`theBuildDetailIsReadAndBoundedByTheHost`).

Surface: instance form, source section. Boards: App-Deploys shows "Build: Nixpacks, detected" read-only; no board edits
build settings.

## 4. Build image and runtime choice

Netlify: build image selection, pinned language versions.

**IMPLEMENTED,** proven only live: Dockerfile or Nixpacks per application (`S/instance/ApplicationKind.java:70`),
runtime images for workspaces. Mechanism and proof in `coolify-use-inventory.md` 1
(`T/test/build/NixpacksBuildLiveTest.java:101`).

Surface: instance form; `/admin/runtime-images`. Boards: Settings ("Runtime images"), App-Deploys (read-only).

## 5. Dependency cache between builds -- OPEN

**OPEN.** Builds run cold on purpose today: kaniko with `--no-push-cache` (`S/build/DockerfileBuilder.java:56`) and
nixpacks with `--no-cache` (`S/build/NixpacksBuilder.java:234`). Whether that is a recorded decision (cache
poisoning across tenants) is [unverified]. A per-application cache volume would cut build minutes; it must stay
keyed to the application so one tenant's cache never feeds another's build. Value: medium (build time on every push).

## 6. Build and deploy logs

**IMPLEMENTED** (stored logs); mechanism in `coolify-use-inventory.md` 11. The build log is written when the build ends
(`S/build/SandboxedBuilds.java:180`) and the release step log per step (`S/application/ReleaseEngine.java:1056`). Live
streaming while a build runs is still open (`docs/hohenext-roadmap.md:265`); the redesign's G5 operation runs are the
planned carrier (`admin-redesign-plan.md` section 8).

- Tests: `T/test/PaasApiTest.java:515`, `T/test/instance/WorkspaceDeploysTabTest.java:63`.

Surface: instance tab "Deployments" (`S/cms/InstanceDeploymentsPage.java:61`), `/admin` build and release history,
API (`S/api/PaasApi.java:436`). Board: App-Deploys (a "Log of" card per commit, with Download).

## 7. Deploy previews per pull request

**IMPLEMENTED** for applications; mechanism, quota decision and proof in `coolify-use-inventory.md` 5. Opt-in per
source (`S/source/GitWebhookHandler.java:482`), hostname `<site>--<ref>.<base_domain>`
(`S/preview/PreviewDeployments.java:782`, `C/HohenheimSettings.java:1293`), its own secret variable set (clause 21).

**DEFECT:** the workspace kind shows the same `previews_enabled` switch (the shared `C/source/GitSourceSchema.java:38`,
added to workspaces at `S/instance/WorkspaceKind.java:73`, and pinned in the workspace's deployment fold by
`T/test/instance/InstanceSettingsSectionTest.java:109`), but a preview requires a release-managed kind
(`S/preview/PreviewDeployments.java:212` through `S/application/ApplicationReleases.java:707`), so a workspace preview
is refused; the switch should not be offered there, or workspaces should get previews.

Fork pull requests: the preview lane reads `pull_request.head.ref` (`S/source/GitWebhookHandler.java:565`) and
[unverified] checks that branch name out of the site's own repository, so a fork's code is not built; Netlify's
"deploy request policy" for outside contributors has no counterpart because the case does not arise. Verify before
relying on it.

- Tests: `T/test/preview/PreviewMechanicsTest.java:129`, `T/test/preview/PreviewCreationLanesTest.java:298`.

Surface: `/admin/previews`, `/manage` twin, site related page. Board: App-Deploys ("Previews" card).

## 8. Branch deploys

**IMPLEMENTED:** opt-in branch globs (`S/source/GitWebhookHandler.java:365`), manual "preview a branch"
(`coolify-use-inventory.md` 5 STATUS block).

- Tests: `T/test/preview/PreviewCreationLanesTest.java:90` (`branchPatternsAreAnOptInGlobVocabulary`).

Surface: instance source settings, `/admin/previews`. Board: App-Deploys ("Preview a branch").

## 9. Preview link on the pull request

Netlify: posts the preview URL as a PR comment and a commit status.

**PARTIAL.** A commit status "Preview ready" carries the preview URL (`S/preview/PreviewDeployments.java:326`); no PR
comment (no comment call in `S/source/`).

Surface: the git provider's PR page. Board: none needed.

## 10. Atomic deploys

Netlify: a deploy goes live all at once, never half-uploaded.

**IMPLEMENTED** for applications, stronger than Netlify: a candidate release must answer its health probe beside the
live one before traffic moves (`S/application/ReleaseEngine.java:403`); mechanism and proof in
`coolify-use-inventory.md` 6. Workspaces (and therefore git-built static sites, clause 2) are not release-managed.

- Tests: `T/test/application/ApplicationReleaseContractTest.java:365`.

Surface: Deploy action on the instance. Boards: App-Overview and App-Deploys (Deploy, "Deploy now").

## 11. Instant rollback and publishing an older deploy

Netlify: any earlier deploy in the history can be published again instantly.

**PARTIAL.** One step back: rollback (`S/application/ReleaseEngine.java:338`) targets the newest retired release
(`:1008`); older ones are reclaimed (`:607`). Publishing any older deploy does not exist.

**DEFECT:** the application form offers `keep_releases` (default 2, `S/instance/ApplicationKind.java:128`), but nothing
under `src/server/` or `src/common/` reads it beyond the form layout (`S/instance/ApplicationKind.java:164`); the engine keeps exactly one retired release
whatever the value says. Either honour it (and make "roll back to any kept release" real) or remove the field.

- Tests: `T/test/application/ApplicationReleaseContractTest.java:365`, `T/test/PaasApiTest.java:376`.

Surface: Deployments tab rollback, API, `tools/hoh rollback`. Board: App-Deploys ("Roll back to 9c1e0d3"; its own
copy says "One previous release is kept for rollback", matching the code, not the field).

## 12. Stop auto publishing (lock deploys)

**PARTIAL, untested.** `auto_deploy` off makes the webhook record and ignore a push
(`S/source/GitWebhookHandler.java:388`, stamped `ignored_auto_deploy`); no test asserts it. The field's help text
still says "webhook or polling" (`src/server/resources/META-INF/microcopy/en.json:185`) although polling is retired
(`C/source/GitSourceSchema.java:49`).

Surface: instance source settings. Board: App-Deploys ("On every push" line, read-only); no board toggles it.

## 13. Deploy history

**IMPLEMENTED.** Per instance on the Deployments tab (applications: releases; workspaces: builds), plus the admin
release and build history lists.

- Tests: `T/test/instance/WorkspaceDeploysTabTest.java:63`, `T/test/PaasApiTest.java:515`.

Surface: instance tab "Deployments", `/admin` release and build history. Board: App-Deploys ("Deploys" table).

## 14. Commit statuses

**IMPLEMENTED** for GitHub, GitLab and Gitea: `S/source/GitProviderClient.java:52`, contexts `hohenheim/deploy` and
`hohenheim/preview` (`S/source/DeployStatuses.java:18`, `:21`).

- Tests: `T/test/source/GitProviderClientTest.java:228`.

Surface: the git provider. Board: none needed (App-Deploys' log shows "Reported failed to GitHub").

## 15. Build hooks -- OPEN

Netlify: a secret URL that triggers a build when POSTed (a headless CMS publishing, a nightly rebuild).

**OPEN.** Searched `build_hook`, `deploy_hook`, `trigger_url`: nothing. The nearest is `POST /api/v1/sites/{id}/deploy`
with an API key (`S/api/PaasApi.java:156`), which needs a key holder, not a pasteable URL, and a nightly rebuild can
be an instance schedule only for the actions schedules know (clause 46). A per-instance hook token beside the webhook
secret, through the same deploy lane and replay ledger, is small. Value: medium.

## 16. Manual deploy without git

Netlify: drag-and-drop a folder, or `netlify deploy` from a local directory.

**PARTIAL.** An artifact upload exists (`S/api/PaasApi.java:171`, receipts in `C/model/ArtifactOperationModel.java:12`,
promotion after health in `C/model/ArtifactSourceModel.java:12`), but it takes a JAR only
(`S/application/ArtifactDeploys.java:70`); there is no folder or archive upload for a static site, and `tools/hoh`
has no upload command.

- Tests: `T/test/PaasApiTest.java:644`.

Surface: API only. Board: none.

## 17. CLI and API

**IMPLEMENTED;** see `coolify-use-inventory.md` 13 and `docs/paas-api.md`. `tools/hoh` deploys (`tools/hoh:682`),
rolls back (`:688`), reads logs (`:777`) and variables.

- Tests: `T/test/HohCliTest.java:28`, `T/test/PaasApiTest.java:376`.

Surface: API and CLI. Board: none needed.

## 18. Deploy notifications

Netlify: notify on deploy started, succeeded, failed (email, Slack, webhook).

**PARTIAL, with a defect.** Webhook channels in Slack, Discord or generic JSON (`C/model/NotificationChannelModel.java:24`,
`:27`); email is open (`docs/hohenext-roadmap.md:262`). There is no deploy-succeeded event.

**DEFECT:** `DEPLOY_FAILED` is declared (`S/notification/NotificationEvents.java:25`) and selectable on a channel, but no
production code raises it; the only `Alerts.send(NotificationEvents.DEPLOY_FAILED, ...)` is in
`T/test/database/AlertsTest.java:160`. An operator who subscribes to failed deploys hears nothing.

Surface: `/admin/notifications`. Board: Settings ("Notifications").

## 19. Deploy permalinks -- REJECTED (argued)

Netlify: every deploy keeps its own URL forever.

**REJECTED** for applications: a kept release is a running container (memory, ports, a database connection), not a
folder of files, so keeping every deploy addressable multiplies the workload. Previews (clauses 7, 8) are the bounded,
quota-charged form. For git-built static sites the argument is weaker; revisit if clause 2 grows a real publish step.
This document's argument.

---

## Configuration

## 20. Environment variables and secrets

Netlify: site and shared (team) variables, secret values, UI/CLI/API.

**IMPLEMENTED.** Instance variables layered over environment-level ones (`S/instance/InstanceVariables.java:249`;
environments belong to a project, `C/model/EnvironmentModel.java:29`), a secret kind
(`C/model/InstanceVariableModel.java:41`) that never comes back over the API. See `coolify-use-inventory.md` 4.

- Tests: `T/test/project/ProjectOwnershipTest.java:391`, `T/test/PaasApiTest.java:448`.

Surface: `/admin/environment-variables`, instance variables, API, `tools/hoh vars`. Boards: none (P5 names a
Configuration tab on the app page; no board draws it).

## 21. Values per deploy context

Netlify: different values for production, deploy previews, branch deploys, a named branch, local dev; scopes for
builds, functions, runtime.

**PARTIAL.** Three of the contexts exist: runtime values, build-time values (`C/source/GitSourceSchema.java:134`) and a
secret preview-only set (`:157`, used at `S/preview/PreviewDeployments.java:580`). Not offered: per-branch values, or
branch deploys distinct from PR previews.

- Tests: `T/test/preview/PreviewMechanicsTest.java:540`.

Surface: instance source settings. Board: none.

## 22. Configuration in the repository (netlify.toml, _redirects, _headers) -- OPEN

**OPEN** (low). Searched `netlify`, `_redirects`, `_headers`: nothing; every setting lives in the control plane. If
built, a repo file must pass through the same write lanes and refusals as the admin (route claims, header rules,
protected paths), and a refusal must fail the deploy by name, never be half-applied. Value: low to medium (people
migrating from Netlify bring these files).

---

## Routing and static serving

## 23. Per-path redirect rules -- OPEN

Netlify: ordered rules with status codes, splats, placeholders, query matching, country/language/role conditions.

**OPEN.** Redirects are whole-host only (`S/upstream/kinds/RedirectUpstreamKind.java:30`, `npmplus-use-inventory.md` 3);
a path can only be redirected by giving it its own domain row and redirect site. No splats, placeholders or
conditions. Value: high for anyone migrating a site (old URLs must keep working); effort medium, as a rule list on
the site evaluated before the upstream.

## 24. Rewrites and proxying a path

Netlify: a 200 rewrite serves another path or proxies to an external URL.

**IMPLEMENTED** at path-prefix granularity: a domain row's path prefix routes to any site, including an address
upstream pointing anywhere (`npmplus-use-inventory.md` 24; `C/model/SiteDomainModel.java:170`).

- Tests: `T/test/PathRoutingTest.java:77`.

Surface: site tab "Addresses". Board: none draws a path-routed address.

## 25. Custom headers per path

**PARTIAL.** Request and response header rules exist per domain row (`C/model/SiteDomainModel.java:229`, `:233`;
`S/proxy/ResponseMutations.java:14`), and a row may carry a path prefix, so headers per path work only by splitting a
path into its own row. No glob paths.

- Tests: `T/test/ProxyDispatchTest.java:461`.

Surface: domain form. Board: none.

## 26. SPA fallback

Netlify: `/* /index.html 200`.

**PARTIAL, untested.** The site-level static kind has `fallback_file` (`S/upstream/kinds/StaticUpstreamKind.java:56`,
served at `S/sitetype/StaticFileHandler.java:92`); no test covers it, and it is not available to the git-built static
image, whose nginx answers `try_files $uri $uri/ =404` (`images/static/nginx.conf:29`).

Surface: static site form. Board: none.

## 27. Custom 404 page -- OPEN

**OPEN.** A missing file is a bare 404 (`S/sitetype/StaticFileHandler.java:97`); the fallback file answers 200, so it
cannot double as a 404 page. Value: medium; effort low (a `not_found_file` beside `fallback_file`, and the same in the
static image).

## 28. Cache headers, validators, pretty URLs -- OPEN

**OPEN.** The static handler sends content type and length only (`S/sitetype/StaticFileHandler.java:117`): no ETag,
Last-Modified, Cache-Control or Range, no pretty-URL or trailing-slash normalization. Every visit re-downloads every
asset. Value: medium (page speed and bandwidth); effort low to medium. This is the useful half of the asset cache
NPM offers (`npmplus-use-inventory.md` 30).

---

## Domains and HTTPS

## 29. Custom domains with automatic HTTPS

**IMPLEMENTED;** `coolify-use-inventory.md` 9 and `npmplus-use-inventory.md` 11 to 18. Force HTTPS switches on only
once a certificate works (`C/model/SiteDomainModel.java:199`, `S/tls/ForceSslLatch.java:28`).

- Tests: `T/test/CertificateRequestJourneyTest.java:55`.

Surface: site tab "Addresses", `/admin/domains`, `/admin/certificates`. Boards: Online-2, Online-3, Domains,
App-Overview, App-Problem.

## 30. DNS hosting

**IMPLEMENTED,** beyond Netlify DNS: authoritative zones (`S/dns/DnsServer.java:39`, `C/model/DnsZoneModel.java:19`),
DNSSEC, secondaries over AXFR/TSIG/NOTIFY, ACME DNS-01 against hosted zones (`docs/hohenext-roadmap.md`, "Optional
authoritative DNS").

- Tests: `T/test/DnsServerTest.java:137`.

Surface: `/admin/dns-zones` with Records and Zone-file tabs. Board: Domains ("DNS zones" tab), Settings ("DNS server").

## 31. Automatic subdomain per site -- OPEN

Netlify: every site gets `<name>.netlify.app` before it has a domain.

**OPEN.** Only previews get a generated hostname under `base_domain` (clause 7); a production site has no address until
someone adds one (searched for other hostname generators: previews, the dev-tunnel namespace and game domains only).
For "Put something online" this is the missing "try it before DNS points here" step. Value: high for the redesign's
P1 flow; effort low (the preview hostname composer and a wildcard certificate already exist).

---

## Access and security

## 32. Password protection

Netlify: a site-wide password or basic auth.

**IMPLEMENTED:** a site-wide access list (`SiteModel.ACCESS_LIST_ID`) or a protected path, with basic-auth and IP rules
(`npmplus-use-inventory.md` 7, 8).

- Tests: `T/test/AccessListEnforcementTest.java:82`, `T/test/ProtectedPathEnforcementTest.java:83`.

Surface: site tab "Protection", `/admin/access-lists`. Board: App-Protect.

## 33. Team login (SSO) in front of a site

**PARTIAL:** the only sign-in providers are Basic and the Proteus realm (`npmplus-use-inventory.md` 9); no SAML or
generic OIDC.

Surface: `/admin/auth-providers`. Board: App-Protect ("Sign in with an account").

## 34. Protect only the previews -- OPEN

Netlify: password-protect deploy previews and branch deploys while production stays public.

**OPEN.** A preview inherits the site's own gate (`S/proxy/RouteTableBuilder.java:361`), so the choice is "everything
protected" or "previews as public as production". Value: medium (unreleased work on a guessable hostname); effort low
(an access-list pick on the preview settings, applied by the same route builder).

## 35. Firewall traffic rules

Netlify: allow or block by IP and by country.

**PARTIAL.** IP allow/deny rules (clause 32) and automatic bans (`npmplus-use-inventory.md` 28) exist; country rules do
not (`npmplus-use-inventory.md` 29).

Surface: access lists, `/admin/bans`. Boards: App-Protect ("From certain networks"), Settings ("Blocking and firewall").

## 36. Rate limiting -- OPEN

**OPEN.** Only auth attempts and webhooks are throttled (`S/proxy/auth/ProxyAuthThrottle.java:29`,
`S/source/GitWebhookHandler.java:92`); no per-site or per-path request rate limit. Value: medium (a login form or an
expensive endpoint); effort low on the existing `RateLimitPolicy`.

---

## Observability and team

## 37. Web analytics and observability dashboards -- REJECTED (owner decision)

**REJECTED:** "statistics are out of scope (owner call, 2026-07)" (`docs/hohenext-roadmap.md:33`). Live per-instance
CPU, memory and network exist (`S/cms/InstanceStatsPage.java:29`, "LIVE OBSERVATION ONLY, by decision").

## 38. Request logs and log drains

**PARTIAL.** One optional combined-format access log for the whole proxy (`S/proxy/AccessLog.java:45`), not per site,
not shown in the admin; no log drains (searched `syslog`, `loki`, `datadog`, `log_drain`). A drain is OPEN and would
also serve clause 37's rejection (analytics happen elsewhere).

Surface: none (a setting). Board: none.

## 39. Team members and roles

**IMPLEMENTED:** projects with grant-derived membership (`C/model/ProjectModel.java:25`), per-record capabilities,
a read-only membership roster in `/manage` (`S/cms/ProjectMembershipParts.java:37`); see `npmplus-use-inventory.md` 38.

- Tests: `T/test/project/ProjectOwnershipTest.java:231`.

Surface: `/admin/users`, `/admin/roles`, `/admin/projects`, record Access tabs, `/manage`. Boards: App-Protect ("Who may
manage this app", read-only); no board invites a member.

## 40. Audit log

**IMPLEMENTED;** `npmplus-use-inventory.md` 41. Tests: `T/test/AdminActivityListTest.java:177`. Surface:
`/admin/activity`. Board: Activity.

---

## Platform primitives Netlify sells

## 41. Forms -- REJECTED (argued)

Netlify: HTML form submissions captured without a backend, with spam filtering and notifications.

**REJECTED.** Searched `form_submission`, `FormSubmission`: nothing (Spamservice is visitor reputation, not form
capture). Hohenheim runs backends; a form handler is application code that can run as an app or a template. Storing
visitor submissions would make the proxy a store of third parties' personal data with its own retention and export
duties. This document's argument.

## 42. Serverless functions -- REJECTED (argued)

**REJECTED** as a runtime model: the unit of compute here is an instance (application, workspace, container),
long-running and health-gated, and a path can route to it (clause 24). A per-request function runtime is a second
product. This document's argument.

## 43. Edge functions -- REJECTED (argued)

**REJECTED:** there is no edge network; one proxy per installation. Same argument as 48.

## 44. Scheduled and background work

Netlify: scheduled functions, background functions.

**IMPLEMENTED** in Hohenheim's shape: per-instance schedules of ordered steps (power, snapshot, backup, console
command, app update), re-authorized per step; mechanism and proof in `pterodactyl-use-inventory.md` 8
(`S/cms/InstanceScheduleParts.java:50`). Background work is simply a long-running instance.

- Tests: `T/test/instance/InstanceScheduleJourneyTest.java:73`.

Surface: instance tab "Backups" (schedules section), `/manage` twin. Board: none draws schedules.

## 45. Split testing -- REJECTED (argued)

Netlify: split traffic between branch deploys.

**REJECTED.** The release engine serves exactly one release per application by design (clause 10); splitting traffic
means two production releases sharing one database and sticky visitor assignment, a large design change for a rare
need (searched `split`, `weighted`, `canary`: nothing). This document's argument.

## 46. Managed database

Netlify: Netlify Database (managed Postgres).

**IMPLEMENTED;** `coolify-use-inventory.md` 8 and `docs/shared-database-engines.md` (`C/model/DatabaseModel.java:214`,
placement dedicated or shared). Surface: `/admin/databases`. Board: Databases (not in the plan's section 3 list).

## 47. Blobs (key-value object store) -- REJECTED (argued)

**REJECTED:** an application gets volumes and managed databases (including Redis); an object-store API is application
territory. This document's argument.

## 48. CDN, image CDN, asset optimization, cache purge -- REJECTED (argued)

**REJECTED:** Hohenheim is one origin proxy, not a global network (searched `CDN`, `purge`: only archive purge).
Putting a CDN in front of Hohenheim stays possible because forwarded headers and PROXY protocol are honoured
(`npmplus-use-inventory.md` 2). The origin's own cache headers are clause 28.

## 49. Identity (visitor sign-up for apps) -- REJECTED (argued)

**REJECTED:** visitor accounts for a tenant's app belong to the app. Hohenheim's own accounts gate visitors only as
the operator's access control (clause 33).

## 50. Usage limits

Netlify: plan limits on bandwidth, build minutes, function invocations.

**PARTIAL.** Per-owner count quotas for sites, databases and instances (`S/quota/SiteQuota.java:9`,
`coolify-use-inventory.md` 12) and a per-workload bandwidth rate cap on Incus (`S/instance/NetworkBandwidth.java:12`);
no metering of bytes served or build minutes (searched `bytesSent`, `bytes_out`).

- Tests: `T/test/quota/SiteAndDatabaseQuotaTest.java:77`.

Surface: `/admin/instance-quotas`. Board: none.

---

## Findings from writing this document

1. **`DEPLOY_FAILED` is never raised** (clause 18). Subscribing a channel to it delivers nothing.
2. **`keep_releases` is a dead field** (clause 11). The form promises N kept releases; the engine keeps one.
3. **Previews are offered on workspaces and refused there** (clause 7).
4. **`auto_deploy` help text mentions polling**, which is retired (clause 12).
5. **`docs/paas-api.md:95` is stale:** it says a static/process site rolls back to the "previous checkout slot"; the
   code says that lane died (`S/api/PaasApi.java:260`).
6. **`coolify-use-inventory.md:386` cites a test method that no longer exists**
   (`aPushToADeclaredPreviewBranchDrivesThePreviewLifecycleNotProduction`; no match under `src/` at `2eb75017`). The
   branch-preview lane is still covered by `PreviewCreationLanesTest`; that clause's evidence needs re-pointing.
7. **"Stop auto publishing" has no test** (clause 12).

## Screens the redesign still lacks

Capabilities that exist (IMPLEMENTED or the working half of a PARTIAL) and that no board draws as a task, ranked by how
often a Netlify-style user would need them:

1. Environment variables and secrets, including the build-time and preview-only sets (20, 21): needed on every git
   app; P5 names a Configuration tab, no board draws it.
2. Build and source settings: repository, branch, build command, directory, builder, auto-deploy switch, preview
   branches (3, 12): App-Deploys shows them read-only; nothing edits them.
3. A git-built static site end to end (2): Online-1's "Static files" card reads as a host folder; the git-fed static
   path (workspace plus static image) has no designed flow.
4. Previews settings: enable PR previews, branch globs, preview variables (7, 8, 21): App-Deploys lists previews but
   not how to turn them on.
5. Domain-level path routing and headers (24, 25): see `npmplus-use-inventory.md`'s list, item 1.
6. Notification channels per event, including deploy events (18): Settings has a "Notifications" section; no board
   draws choosing which events reach which channel.
7. Team members and project membership (39): no board invites a person or adds them to a project.
8. Instance schedules (44): no board draws "every night, do X".
9. Usage and quotas (50): no board shows an owner's sites, databases and instances against their caps.
10. Commit statuses and webhook delivery history (14, 1): the delivery ledger exists (`WebhookDeliveryModel`); no board
    answers "why did my push not deploy" (ignored branch, auto-deploy off, bad signature).
