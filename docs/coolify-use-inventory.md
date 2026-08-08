# The Coolify-use inventory

Closed 2026-08-08. This is the checked-in inventory the Phase 7 gate
(`instance-tier-plan.md:4112`) and the "Coolify / Dokploy-class PaaS" definition
(`instance-tier-plan.md:70-80`) both demand: every capability we use a
Coolify-class PaaS for, with a DECISION and its evidence.

**Closed does not mean implemented.** Each row is one of:

- **IMPLEMENTED** -- the mechanism exists AND a test asserts STATE about it.
- **CLAIMED** -- the code exists and looks right, but nothing asserts state. A
  docblock is not a test.
- **PARTIAL** -- precisely what works and precisely what does not.
- **OPEN** -- genuinely missing and genuinely wanted, with the owning slice.
- **REJECTED** -- a decision argued from evidence about the shape of THIS
  product. A rejection that cannot be argued is an OPEN row wearing a disguise.

### How this document was built

Every row was derived from CODE, not from the plan's STATUS notes. Where a row
cites a test, the test was read for whether it asserts STATE or only a status
code, and for whether it `assumeTrue`s its way to a green skip.

**The `[live]` mark is the most important thing in this document.** 67 of the
243 browserTest classes carry an assumption gate, across 280 call sites, and
there are THREE distinct gates -- a Docker socket, a private network namespace,
and (the one that is easy to miss) a PRE-PULLED IMAGE. A CI host with Docker and
netns but a cold image cache still skips those tests green. There is no `@Tag`
separating the live lane from the hermetic one. **A skipped test is a green
test.** Every capability below except projects/environments and the webhook
receiver has its strongest proof behind one of those gates.

Verification legend: **[code]** = source read at the cited file:line;
**[test]** = hermetic test, asserts state, no assumption gate; **[live]** = the
only proof is a daemon-gated test that can skip green.

**Two clauses of the plan's minimum claim are currently OVERCLAIMED by that
sentence and are corrected here: item 2 (build isolation) and item 3 (provider
flows).** Read those two first.

### The counts

Thirteen numbered clauses, plus three argued rejections and one cross-reference:

| verdict | count | items |
| --- | --- | --- |
| IMPLEMENTED | 7 | 1, 4, 6, 7, 8, 11, 13 |
| PARTIAL | 6 | 2, 3, 5, 9, 10, 12 |
| REJECTED | 3 | A (compose runtime), C (large service marketplace), D (multi-server orchestration) |
| OPEN | 0 as a row | but five OPEN SLICES are named inside PARTIAL rows: the git build lane (2), ACME issuance proof (9), site-keyed volumes (10), site/database count quotas (12), the CLI test wiring (13) |
| CLAIMED | 2 sub-verdicts | Gitea webhook support (item 3), ACME certificate acquisition (item 9) |

Of the 7 IMPLEMENTED rows, **three are proven only `[live]`** (1, 6, 7) -- and
two of those three are the rollout and rollback that are the product's
centrepiece. Section B is a cross-reference, not a rejection.

---

## 1. Dockerfile AND buildpack source builds

**IMPLEMENTED, proven only `[live]`.**

Two builder kinds behind one seam, and only one artifact path:
`Builders.forKind` (`src/server/java/be/elevenways/hohenheim/server/build/Builders.java:50-58`)
dispatches `dockerfile` and `nixpacks`. `DockerfileBuilder.java:26` runs kaniko
(`gcr.io/kaniko-project/executor:v1.23.2`, `:102`) over a pushed context.
`NixpacksBuilder.java:45` is a genuine buildpack lane and NOT a second artifact
path: nixpacks runs as a DETECTION/emit phase only (`:118-127`), emits
`.nixpacks/Dockerfile` into the context, and then delegates to
`new DockerfileBuilder().plan(...)` (`:200`). Wired as an enum on the docker site
type (`sitetype/types/DockerSiteType.java:50-55`), consumed by
`docker/SiteInstances.java:321-324` and by previews
(`preview/PreviewDeployments.java:175-178`). **[code]**

Test: `src/browserTest/java/be/elevenways/hohenheim/test/build/NixpacksBuildLiveTest.java:110`
asserts real state (the built image runs node and answers with a version string,
`:138`) and digest stability across a rebuild (`:210`); `:242` asserts an
undetectable repository is REFUSED by name with a null image id (`:259`, `:276`).
Both methods gate on a Docker socket (`:111`, `:243`) and a netns (`:112`,
`:244`). There is NO hermetic unit test of `NixpacksBuilder` at all -- not of
`detectScript`, `parsePlan`, `planVariables` or `withStableSnapshots`. **[live]**

No drift: the class docblock (`:24-43`) explicitly states that nixpacks' exit
codes are not a detection signal, and `:139` implements exactly that.

## 2. Build isolation away from the control plane and from tenant runtime credentials

**PARTIAL, and the plan's clause as written is FALSE.** There are TWO build
lanes in this product and only one of them is isolated. This is the most
important correction in this document.

### Lane 1, the sandboxed image build -- isolated, as claimed

`server/build/BuildSandbox.java:72` (entry `run()` at `:134`) executes the build
in a SEPARATE container on the Docker daemon, on a private per-build network
(`:123-126`), under `ContainerHardening.SERVICE`: dropped capabilities,
no-new-privileges, nft policy verified in the kernel before the container
exists, and NO bind mounts -- the context is pushed through the archive API
(`BuildRequest.java:84-85`, `BuildSandbox.pushContext:136`), and
`ContainerHardening.java:352-355` refuses a bind mount outright.

Credential separation here is STRUCTURAL rather than a rule: `BuildRequest`
(`server/build/BuildRequest.java:34`) has no field that can carry the workload's
runtime environment; `buildArgs` is a separate map. The docblock says so and the
field list backs it. **[code]**

- Test: `src/browserTest/java/be/elevenways/hohenheim/test/build/SandboxedBuildLiveTest.java:255`
  asserts the tenant's runtime variable arrives EMPTY inside the build (`:318`)
  WITH a positive control proving a real build argument does arrive (`:316`) and
  the runtime container proving the value is genuinely set (`:325`); `:99`
  asserts a socket bind is structurally refused (`:119`) and the daemon socket
  is invisible inside the build (`:138`); `:189` asserts a quota-killed build
  promotes nothing. All three gate on the socket and netns (`:100/:101`,
  `:190/:191`, `:256/:257`). **[live]**
- The credential LEASE lifecycle is proven hermetically:
  `src/browserTest/java/be/elevenways/hohenheim/test/build/BuildCredentialsTest.java:17`
  -- a rival token resolves to null (`:28`). **[test]**

### Lane 2, the legacy git `build_command` -- NOT isolated

`server/source/GitDeployment.java:240` (`runBuild`) executes
`List.of("sh", "-c", buildCommand)` (`:257`) as a HOST PROCESS ON THE CONTROL
PLANE (`SystemUsers.executionBuilder(...)`, `pb.start()` at `:269`, `:273`).
Isolation is a uid drop, a timeout and a process-group kill: no container, no
network policy, no cgroup quota, no capability drop.

Worse for the second half of the clause: `:260` merges the SITE'S RUNTIME
`environment_variables` into that command's environment. That is exactly
"tenant runtime credentials reaching the build", in the opposite direction from
the guarantee `BuildRequest` makes. This lane is reached for non-docker git site
types (static, node); docker-typed git sites go to lane 1.

Its tests (`src/browserTest/.../source/GitDeploymentTest.java:73`, `:118`,
`:141`) assert timeouts and log redaction, not isolation -- because there is no
isolation to assert.

**OPEN slice, ranked first in this document:** either route the git
`build_command` through `BuildSandbox` like the docker lane, or declare the
non-docker git site types a trusted-operator-only tier and say so in the plan.
Nothing today carves this out as a non-goal, so the plan sentence is simply
wrong.

## 3. GitHub/GitLab-compatible provider AND webhook flows

**PARTIAL.** The inbound webhook receiver is the best-tested area in this
document. The outbound provider client is read-and-status only -- there is no
provisioning.

### Provider client: read + commit status, no provisioning

`server/source/GitProviderClient.java:14` declares exactly five methods
(`:32-55`): `listRepositories`, `listBranches`, `cloneUrl`, `cloneCredential`,
`reportStatus`. Implementations `GithubProviderClient.java:27` (PAT lane plus
GitHub App installation-token minting at `:174`) and
`GitlabProviderClient.java:14`, over shared HTTP in `ApiProviderClient.java:23`,
constructed through `GitProviders.java:38-73`. Every outbound URL in both
clients is a repo/branch/status read or write; **there is no `/keys` and no
`/hooks` call anywhere**. The webhook secret is generated locally and the
operator pastes the URL into the provider by hand
(`server/cms/SiteResource.java:321-322`). **[code]**

- Test: `src/browserTest/java/be/elevenways/hohenheim/test/source/GitProviderClientTest.java:238`
  mints a real short-lived installation token against a local fake provider;
  also `:185`, `:220`, `:282`, `:292`, `:304` and
  `GitlabProviderClientTest.java:110/:153/:175/:190`. No assumption gates. **[test]**

### Webhook receiver: IMPLEMENTED

`server/source/GitWebhookHandler.java:38`, signature verification `:339-362`,
replay ledger in `WebhookDeliveries.java`.

- Test: `src/browserTest/java/be/elevenways/hohenheim/test/source/GitWebhookSecurityTest.java:98`
  asserts refused deliveries deploy NOTHING (`:133`), not merely that they got a
  4xx; `:139` asserts a replayed delivery claimed its id exactly once (`:165`);
  `:170`, `:191`, `:220` (`:238`, `:250`) and `:264` (asserting the stamped
  delivery action). No assumption gates anywhere in the file. **[test]**

### Gitea

**CLAIMED, and narrower than it reads.** Gitea is a SIGNATURE-VERIFICATION path
only: `X-Gitea-Signature` (raw hex HMAC-SHA256, no `sha256=` prefix) at
`GitWebhookHandler.java:43` and `:347-352`. It is NOT a provider kind --
`GitProviderModel.java:31,34,45-47` declares exactly `github` and `gitlab`, and
`GitProviders.clientFor` throws `git_provider_kind_unavailable` for anything
else (`GitProviders.java:70-72`). So there is no Gitea repo picker, branch
listing, clone-credential minting or commit-status reporting. Two rough edges:
`deliveryKeyOf` (`:365-375`) knows only the GitHub and GitLab delivery headers,
so a Gitea delivery folds for replay on `"body:" + sha256(body)`; and `eventOf`
(`:377-384`) knows only the GitHub and GitLab event headers. **Zero tests
exercise the Gitea branch** (no match for `Gitea` under `src/browserTest`).

**DRIFT:** `GitProviders.java:18-24`'s docblock lists Gitea alongside GitHub and
GitLab as though it were a supported provider; the class two lines below refuses
it by name. Anyone auditing "which providers do we support" from that docblock
gets the wrong answer.

## 4. Projects and environments

**IMPLEMENTED.** The cleanest capability in this document, and its test is not
live-gated.

`ProjectModel.java:28` deliberately has NO `project_id` column on owned records
(docblock `:23-27`, schema `:33-54` confirms) -- membership is a zenit-auth
group and ownership is grant-derived. `EnvironmentModel.java:21` is a grouping
with `project_id` at `:28`. Write-funnel guards in
`server/project/ProjectGuards.java:33` (re-home `:89-112`, delete `:116-123`,
record-into-environment authority `:132-156`). Variable layering:
`server/instance/InstanceVariables.java:191-202` -- environment values are the
baseline, the instance row wins, secrets ride the same encrypted carrier. **[code]**

- Test: `src/browserTest/java/be/elevenways/hohenheim/test/project/ProjectOwnershipTest.java:412`
  asserts the instance value overrides the environment baseline (`:477`), that
  an environment-only value reaches the instance (`:480`), and the negatives
  `environment_project_mismatch` (`:434`, `:450`) and `environment_in_use`
  (`:488`). Also `:318`, `:371`, `:499`, and `ProjectAdoptionTest.java:214/:347`.
  No assumption gate in any file under `test/project/`. **[test]**

Caveat recorded rather than glossed: the layering is proven at the
`InstanceVariables.valuesFor` map level. Nothing asserts that an
environment-scoped SECRET reaches a RUNNING container's environment through the
full injection path.

## 5. Preview deployments

**PARTIAL -- per-pull-request only. There is no per-branch preview and no manual
create.**

`server/preview/PreviewDeployments.java:72` (`deploy` `:105`, expiry stamped
`:150-154`, one-shot expiry schedule armed `:160`/`:327-331`), with
`PreviewExpireAction`, `PreviewQuota.java:34`, `PreviewDomains`,
`PreviewRequestHandler`, and `PreviewDeploymentModel.java:71` carrying
`EXPIRES_AT`. **[code]**

The only automatic trigger is a change-request event -- `pull_request`
(GitHub/Gitea) or `Merge Request Hook` (GitLab),
`GitWebhookHandler.java:387-390`. A PUSH to a branch never creates a preview
(`:213-249`). Previews are opt-in per site (`previews_enabled`, `:218`) and
refused for anything but `hohenheim:docker` (`PreviewDeployments.java:75`). The
only non-webhook call sites are teardown.

- Test (hermetic): `src/browserTest/java/be/elevenways/hohenheim/test/preview/PreviewMechanicsTest.java:185`
  asserts the one-shot schedule stamps EXPIRED (`:215`), that the generated
  hostname row is gone (`:219`), and the counterfactual that an unexpired
  preview SURVIVED the same sweep (`:226`); `:154` asserts the quota binds
  atomically and releases on teardown; `:259` asserts hostname reclaim. **[test]**
- Test (live): `PreviewDeploymentLiveTest.java:198` asserts the production
  secret has no path into a preview (`:275`) and that the container is gone at
  the daemon (`:298`) -- gated at `:199`, `:200`. **[live]**

DRIFT worth naming: `PreviewDeployments.java:67-71` calls variable isolation
"structural". The code honours it and the assertion exists -- but that assertion
sits behind an `assumeTrue`, so the property is code-true and only live-proven.

## 6. Health-gated zero-downtime rollout

**IMPLEMENTED, proven only `[live]`.**

`server/docker/SiteReleases.java:68` -- candidate beside serving (`gatedSwap`
`:377`, `newInstanceRow` `:692`), probe (`:644`, health path `:685`), atomic
switch and retain (`:377`), drain (`scheduleDrain` `:441`, `completeDrain`
`:466`, depth-one reclaim `:500`), and recovery of an interrupted swap
(`recoverInterrupted` `:535`). **[code]**

- Test: `src/browserTest/java/be/elevenways/hohenheim/test/docker/SiteReleaseLiveTest.java:111`
  hammers the proxy ACROSS the swap and asserts not one request failed (`:156`),
  never-regress per lane (`:166`), the retained container still running the OLD
  digest at the daemon (`:184`), and the drain completing (`:188`). The
  counterfactual is its own test: `:269` proves an unhealthy candidate that
  ANSWERS 500 takes zero proxied requests and is destroyed. `:446` proves
  recovery settles interrupted operations. Every method gates on the Docker
  socket AND a pre-pulled `alpine:latest` (`:112/:114`, `:270/:272`,
  `:363/:365`, `:447/:449`). **[live]**

**There is no hermetic test of `SiteReleases` state at all.** The only other test
referencing it seeds operation rows rather than releasing. On a machine without
Docker or with a cold image cache, the entire rollout claim rests on zero
executed assertions.

## 7. Rollback

**IMPLEMENTED, proven only `[live]`.**

`SiteReleases.rollback` (`:322`) over `newestRetired` (`:739`), with the
anti-flap pin `pinnedByRollback` (`:181`) keyed on the source-identity
fingerprint (`sourceFingerprint` `:89`). Nothing is rebuilt or re-pulled: the
artifact is addressed by digest. **[code]**

- Test: `SiteReleaseLiveTest.java:111` steps 4-7 assert the rolled-back release
  runs the pinned OLD digest (`:203`) and that the pinned reload released
  NOTHING (`:230`); `:362` proves a rollback survives a moved tag and a deleted
  checkout. **[live]**
- The API lane is proven only by its REFUSAL:
  `src/browserTest/java/be/elevenways/hohenheim/test/PaasApiTest.java:395`
  asserts 422 with `rollback_not_available`. No test drives a SUCCESSFUL
  rollback through `/api/v1`.

Accountability note (2026-08-08): the panel row action recorded no activity row
while the API lane did. Recording moved into `SiteReleases.rollback` itself
(`ACTIVITY_ROLLBACK_ACTION`, `:71`) so both surfaces answer identically. That
change is `[live]`-covered only, for the same reason this row is.

## 8. Managed databases and credential injection

**IMPLEMENTED**, and unusually for this document the derivation half has a
hermetic test.

Managed engines: `server/database/DatabaseService.java`, `ManagedDatabase.java`,
`DatabaseInstances.java`, `TenantDatabases.java`, `DatabaseContainerKind.java`.
Injection: `server/database/DatabaseEnvInjection.java:28` with two address
styles documented at `:35-45` (`PUBLISHED_LOOPBACK`, `CONTAINER_NETWORK`),
reaching the consuming workload at `docker/SiteInstances.java:378-383`; network
join in `SiteDatabaseNetworks.java` via `SiteDatabaseLinkHook.java`. **[code]**

- Test (hermetic): `src/browserTest/java/be/elevenways/hohenheim/test/database/DatabaseEnvInjectionTest.java:150`
  asserts the port is the engine's NATIVE port and never the published one
  (`:165`), and that no variable smuggles a loopback address in (`:174`);
  `:94` asserts the prefixed families and the primary URL. **[test]**
- Test (live): `EnvInjectionFlowTest.java:117` asserts the SPAWNED CHILD's own
  env dump carries the derived password (`:165`), gated at `:53`, `:63`, `:75`;
  `docker/SiteDatabaseLinkLiveTest.java:104` runs a real AUTH/SET/GET against
  the injected address (`:159-165`) and proves an UNATTACHED database is
  unreachable (`:179`), gated at `:105`-`:109`. **[live]**

Tenant self-service allocation landed 2026-08-08 (`ccd1bd5`) and is covered in
detail as item 10 of `pterodactyl-use-inventory.md`, with its hermetic
664-line journey test. Not on the API: managed-database CRUD.

## 9. Domains and TLS

**PARTIAL.** Serving and issuance AUTHORIZATION are implemented and proven
hermetically. Actual certificate ACQUISITION is CLAIMED.

Implemented and proven:

- `server/tls/CertificateAuthority.java:47` -- who may obtain a certificate for
  which names, split into a SERVING half and an AUTHORITY half (docblock
  `:21-44`). Test
  `src/browserTest/java/be/elevenways/hohenheim/server/tls/CertificateAuthorityTest.java:157`
  (a wildcard request needs a wildcard claim), `:98` (a hostname this
  installation does not serve is refused EVEN FOR AN ADMIN), `:211` (revoking
  the grant stops the renewal). No assumption gates. **[test]**
- SNI and store: `CertificateStore.java`, `SniKeyManager.java`,
  `CertificateCoverage.java:60` (single-label wildcard match). Test
  `src/browserTest/java/be/elevenways/hohenheim/test/TlsCertificateTest.java:269`
  (a wildcard cert resolves subdomains), `:444` (HTTPS actually accepts TLS
  connections), `:368` (force-SSL redirects). **[test]**

**CLAIMED:** `server/tls/AcmeService.java:42` implements HTTP-01 (`:875`),
DNS-01 (`:786`, wildcard identifier handling `:800-802`, acceptance `:445`) and
a renewal scheduler (`:104-113`, `checkRenewals` `:464`), with DNS-01 publishing
in `CommandDnsTxtPublisher`/`DnsTxtPublishers`. **No test ever obtains a
certificate.** There is no Pebble, step-ca or any local ACME server in the
repository. The only ACME tests shape FAILURE MESSAGES over a hand-built
`FailedOrder` stub (`AcmeFailureTest.java:34`, stub at `:89`) or check input
formatting (`AcmeAccountEmailTest`, `TlsHostnameFoldingTest`).

**OPEN slice:** stand up a Pebble container in the browser lane and issue one
HTTP-01 and one DNS-01 wildcard certificate end to end. Until then "automatic
TLS" is a mechanism we believe rather than a property we have measured, and it
is the highest-value untested mechanism in the product -- an ACME regression is
silent until a certificate expires.

## 10. Persistent storage

**PARTIAL, and a real defect is recorded here rather than in a docblock.**

Named volumes are declared as logical-name-to-container-path on the site type
(`sitetype/types/DockerSiteType.java:82`), lowered onto the kind
(`docker/SiteContainerKind.java:111`) and materialized at
`docker/SiteContainerKind.java:170-174` as
`hohenheim-instance-{instanceId}-vol-{name}`, carried into the release spec at
`docker/SiteInstances.java:385-388`. **[code]**

Bind mounts are REFUSED by construction
(`docker/ContainerHardening.java:355`) -- see the rejection section; this is a
non-goal, and it was not recorded as one anywhere until this document.

- Test: `src/browserTest/java/be/elevenways/hohenheim/test/docker/DockerSiteHandlerTest.java:209`
  asserts the named volume is genuinely mounted at `/data`, read off the real
  daemon's `Mounts` (`:237`). Gated at `:210`, `:212`. **[live]**

**DEFECT (found by writing this document, verified at file:line, NOT fixed
here): a named volume does not survive a health-gated release.** The volume name
is derived from the INSTANCE id. A gated release mints a NEW instance row
(`SiteReleases.newInstanceRow`, `:692-706`), so the candidate computes a
DIFFERENT volume name and mounts an EMPTY volume. The old volume is neither
migrated (no volume handling exists anywhere in `SiteReleases`) nor reclaimed
(`OrphanActions`/`DockerReclaim` refuse volumes by design), so it is silently
orphaned while the new release serves empty state.

The in-place replacement path (`SiteReleases.java:283-299`, taken when nothing
is serving) reuses the serving row and therefore DOES keep the volume, which is
exactly why this is easy to miss -- and why the docblock at
`DockerSiteType.java:79-82` claimed the volume "survives redeploys". That
sentence has been corrected in this pass; the behaviour has not. No test covers
"write to a volume, release, read it back": `DockerSiteHandlerTest:209` never
performs a release swap, and `SiteReleaseLiveTest` declares no volumes.

**OPEN slice, ranked second in this document:** volume identity must be keyed to
the SITE (or to a declared volume record), not to the instance row that happens
to be serving. There are no live installations, so a rename is free.

## 11. Per-deployment logs

**IMPLEMENTED**, with a hermetic test.

Three durable log lanes, each on its own operation record: git deployments
(`model/DeploymentModel.java:38`, `LOG` plus status/reason/commit/slot/duration
at `:28-42`), release operations (`model/ReleaseOperationModel.java:117`
`STEP_LOG`, appended by `SiteReleases.step()` `:764`) and sandbox builds
(`model/BuildOperationModel.java` + `server/build/BuildLog.java`). Viewer:
`server/cms/SiteDeploymentsPage.java:35`. **[code]**

- Test: `src/browserTest/java/be/elevenways/hohenheim/test/GitDeploymentFlowTest.java:182`
  asserts the DURABLE ROW's log contains the clone and build markers and does
  NOT contain the build secret (`:190-195`), plus the rendered per-deployment
  collapsible log (`:220-223`). No assumption gate. **[test]**
- Release step logs additionally in `SiteReleaseLiveTest.java:190-195`
  (**[live]**) and read back over the API in `PaasApiTest.java:497` -- the
  detail carries the step log (`:510`) and the list omits it (`:505`). **[test]**

## 12. Quotas

**PARTIAL.** Instance count and disk GB are enforced and proven hermetically.
Per-owner caps on SITES and on the NUMBER OF MANAGED DATABASES do not exist.

- Instance count: `server/instance/InstanceQuota.java:66`, bucket prefix at
  `:69`. Test `src/browserTest/java/be/elevenways/hohenheim/test/instance/InstanceQuotaTest.java:86`
  asserts used equals limit and not limit-plus-one under real concurrency
  (`:130`) and that the slot comes back (`:156-176`). **[test]**
- Disk GB: `server/instance/InstanceDeviceQuota.java` plus
  `InstanceRootDiskQuota.java` (the root disk charges the SAME owner bucket).
  Test `InstanceDeviceQuotaTest.java:93` asserts an over-cap grow is the named
  refusal `disk_quota_reached` (`:154`). **[test]**
- Concurrent previews: `server/preview/PreviewQuota.java:33`, proven at
  `PreviewMechanicsTest.java:154`. **[test]**
- Per-BUILD (not per-owner) resource ceilings: `server/build/BuildQuota.java:29`.

**OPEN:** a site count cap and a managed-database count cap. A database engine
consumes an INSTANCE slot indirectly (`TenantDatabases.java:54`), which is an
instance-count charge, not a database quota -- so "five databases per tenant" is
not expressible.

**DRIFT (corrected in this pass):** `InstanceQuota.java:32-34`'s docblock said
"ONE dimension for now ... disk, ports, sites and databases are explicitly out
of scope". Disk IS enforced, with a passing test. The docblock was stale in the
conservative direction, which is the safer direction but still misleads an
auditor counting quota dimensions.

## 13. A supported API or CLI for automation

**IMPLEMENTED for the surface it covers**, with two caveats worth stating.

API: `server/api/PaasApi.java:57` over the shared key-only plumbing
(`server/api/ApiConduits.java:23`); routes declared in
`src/common/java/be/elevenways/hohenheim/HohenheimEndpoints.java` -- the PaaS
lane at `:535-660` (projects, sites, deploy, rollback, deployments and their
logs, releases with step logs, builds with logs, environment variables) and the
instance lane at `:395-520` (list/detail/create, power, command, backup,
snapshot, files, logs, variables, devices). Documented at `docs/paas-api.md`.
CLI: `tools/hoh` -- single-file node, stdlib only, `X-Api-Key`, contexts in
`~/.config/hoh/config.json`, and the rollback slug interlock the server
deliberately does not carry. **[code]**

- Test: `src/browserTest/java/be/elevenways/hohenheim/test/PaasApiTest.java:284`
  asserts tenant B's site is NOT in tenant A's response (`:300`) -- projection
  state, not a status code; `:333` asserts unowned and absent answer identically
  AND that no row was minted (`:365`, `:369`); `:431` asserts a secret has no
  representation over the API (`:452`) while env injection still sees it
  (`:455`). No assumption gate. **[test]**

Caveat 1: `PaasApiTest:395` proves deploy and rollback only through their
REFUSAL paths. No test drives a successful deploy or rollback over `/api/v1`.

Caveat 2: `tools/hoh.test.js` is a standalone node script and **no Gradle task
invokes it**, so the CLI's own tests never run in CI. That is a one-line build
wiring gap and it is an OPEN item.

Not on the API at all: certificate issuance, managed-database CRUD, and quota
administration. Those are admin-resource-only, deliberately.

---

## Capabilities Coolify has that we deliberately do not

### A. A docker-compose RUNTIME -- REJECTED

Coolify's headline flexibility is "paste a docker-compose.yml and we run it".
There is no compose parser anywhere in this repo (verified: no word-boundary
match for `docker-compose` or `compose.yml` in any source file). A "stack" here
is a MODELLED set of services (`StackModel`, `StackServiceModel`,
`StackFileModel`) that the control plane owns field by field.

The argument for the modelled form, not against compose: every cross-cutting
guarantee in this product keys on the control plane KNOWING what a workload
declares. Port claims are ledgered per host and refused on collision across
tiers; network isolation is applied per workload; quota is charged per
declaration; DNS and certificates are generated from records with owner+source
metadata so cleanup is ownership-safe. A user-supplied compose file declares
ports, networks, volumes and privileges outside all of that, and honouring it
means either re-deriving every guarantee from arbitrary YAML or dropping the
guarantee for compose workloads. Coolify accepts that trade; we do not.

What we accept losing: one-paste onboarding of upstream compose projects, which
is a genuine adoption cost. An importer that TRANSLATES a compose file into
stack service records (refusing what it cannot model) is the honest middle and
is an open item, not a rejection.

### B. Git provider API clients -- NOT a rejection, cross-referenced here

**NOT A REJECTION.** It sits in this section only because a reader looking for
"why can't I connect a repo in two clicks" will look here. Inbound webhooks
are handled; outbound provider API calls (create a deploy key, list a user's
repositories, register the webhook for them) do not exist. The consequence is
that connecting a repository is an operator paste of a URL and a manual webhook,
not a two-click OAuth flow. See the numbered row for the evidence.

### C. A marketplace of one-click services -- REJECTED at Coolify's scale

Coolify ships hundreds of curated service templates. The catalog here holds two
(`adguard`, `gotify`), and growing it is a content decision, not an engineering
one. Rejected as a CLAIM, not as a direction: shipping a large catalog we do not
run ourselves means shipping upgrade paths and CVE response for software we do
not operate. The template and community-script mechanisms are the extension
point; the catalog stays deliberately small.

### D. Multi-server orchestration and autoscaling -- REJECTED

Same argument as the Pterodactyl inventory's Swarm row: placement is explicit
and a move is an operator act.

---

## Genuinely OPEN items, ranked

Value against effort, with prerequisites, for the PaaS claim specifically.

1. **Isolate (or explicitly quarantine) the git `build_command` lane** (item 2).
   Value: highest -- it is the one place where a documented security property of
   this product is false, and the site's runtime environment variables are handed
   to it. Effort: medium if routed through `BuildSandbox`; trivial if the honest
   answer is to declare the non-docker git site types operator-only and say so.
   Prerequisite: a decision on which of those two it is. This is the only item
   here that is a correctness claim rather than a coverage gap.
2. **Key volume identity to the SITE, not the instance row** (item 10). Value:
   highest -- a named volume silently comes back EMPTY after a health-gated
   release, which is data loss presented as a successful deploy. Effort: medium: a
   naming change plus adoption of the existing volume, and a hermetic-plus-live
   test that writes, releases and reads back. Prerequisite: none -- there are no
   live installations, so a rename is free.
3. **Issue one real certificate in a test** (item 9). Value: high -- ACME is the
   highest-value entirely unmeasured mechanism in the product, and an ACME
   regression stays silent until an expiry. Effort: medium: a Pebble container in
   the browser lane, one HTTP-01 and one DNS-01 wildcard order. Prerequisite: none.
4. **A hermetic test of the release engine** (items 6, 7). Value: high -- the
   zero-downtime swap, the drain and the rollback have NO non-live coverage at
   all. Effort: medium-high; it needs a fake docker runtime the way
   `FakeNativeDaemons` fakes the native one. Prerequisite: none, and it would pay
   for item 2's test as well.
5. **Per-owner site and database count quotas** (item 12). Value: medium-high --
   "five sites, three databases" is not expressible, and a database only charges
   an instance slot indirectly. Effort: low-medium on the existing ledger.
   Prerequisite: none.
6. **Git provider provisioning: deploy keys and webhook registration** (item 3).
   Value: medium -- it converts connecting a repository from a manual paste into a
   flow, which is most of Coolify's perceived polish. Effort: medium per provider.
   Prerequisite: the GitHub App lane already mints installation tokens, so the
   credential path exists.
7. **A compose-to-stack IMPORTER** (rejection A). Value: medium, mostly adoption
   -- it removes the "re-enter every service by hand" migration cost. Effort:
   medium-high, and it must REFUSE what it cannot model rather than approximate
   it. Prerequisite: agreement that refusing is acceptable UX.
8. **Wire `tools/hoh.test.js` into the build** (item 13). Value: low-medium --
   the CLI has tests that never run. Effort: one Gradle task. Prerequisite: none.
9. **Per-branch or manual preview creation** (item 5). Value: low-medium -- today a
   preview exists only for an open pull/merge request. Effort: low. Prerequisite:
   a decision about quota, since branch previews multiply.
10. **A `@Tag`-separated live lane** (cross-cutting). Value: medium -- see the
    Pterodactyl inventory; it is the same item and one fix serves both.

---

## Incumbent capabilities that would be actively WRONG here

- **A docker-compose runtime.** Honouring an arbitrary compose file means either
  re-deriving port arbitration, network isolation, quota accounting and
  ownership-safe DNS/certificate cleanup from user YAML, or dropping every one of
  those guarantees for compose workloads. Rejection A.
- **Host bind mounts.** Refused by construction
  (`docker/ContainerHardening.java:355`) and correctly so: an operator-typed host
  path inside a tenant container is the escape hatch the confinement work exists
  to close. This was never recorded as a non-goal anywhere before this document.
- **Multi-server orchestration with automatic rescheduling.** It needs shared
  storage or accepts data loss on move; a scheduler that silently relocates a
  stateful workload is worse than one that refuses. Rejection D.
- **A hundreds-strong one-click service marketplace.** Rejection C.
- **Deploying straight from a webhook without a health gate.** Coolify's default
  is "restart the container"; here a candidate must pass a probe beside the
  serving release before it may take traffic (item 6). Removing that to gain
  speed would remove the only thing standing between a bad commit and downtime.


---

## What hohenheim does that Coolify structurally cannot

The plan's framing is "one beautiful whole, not three tools in sequence". These
are the properties that come from being one control plane, and that a panel
bolted next to a proxy next to a DNS provider cannot have at any amount of
effort:

1. **One authorization vocabulary across every tier.** A record grant
   (`RecordGrants`, `(subject, model, record, capability)`) is the SAME mechanism
   for an instance's `console`, a site's `manage`, a database's `credentials` and
   a DNS zone's records. Delegability is declared once per capability
   (`KnownCapability`; `exec` is structurally non-delegable and unreachable
   through `manage`'s umbrella). Pterodactyl's subusers and Coolify's team roles
   each cover one product's objects; neither can express "this player-admin may
   restart the backend and edit its SRV record but never run a shell".

2. **Cross-tier port and route ledgers.** One `PortLedger` arbitrates host ports
   across instances, docker sites, managed databases and stacks, with claims
   parked rather than deleted on an unverifiable outcome. Two products sharing a
   host cannot do this; they collide and the loser fails at container start.

3. **Authoritative DNS in the same transaction as the thing it names.** The DNS
   tier is a real authoritative server (`DnsServer`, DNSSEC signing, AXFR, TSIG,
   dynamic updates), so a game-domain mapping materializes SRV/A records and
   Velocity forced-hosts config from ONE write, with owner+source metadata so
   reconciliation deletes only its own output. Both incumbents delegate DNS to a
   third party and therefore cannot make that atomic or ownership-safe.

4. **Fenced, lease-guarded outcome writes.** Every runtime outcome is written
   conditionally on a host lease fence (`HostLeases`, `InstanceOperationGuard`),
   so two controllers cannot both believe they own a workload. Neither incumbent
   has a fence: Wings is authoritative per node, and Coolify's deploys race.

5. **One audit trail with one attribution model.** `ActivityLog` plus
   `Accountability` stamps actor, label, IP, user agent and ORIGIN (web/api/
   system) on every recorded act across all tiers -- and as of 2026-08-08 the
   same operation records identically whether it came from the panel, `/manage`
   or the API. Products that grow an API later usually audit only one of them;
   that was this product's own defect until this pass fixed it.

6. **One quota ledger.** Instances, disk GB, NICs and databases are charged
   against per-owner caps through the core reservation ledger, and the
   reservation MOVES with a migration. Two products cannot share a budget.

7. **Trust pinning and host admission as a state machine.** Host keys are pinned,
   a contradiction quarantines with its own sticky columns that only a repin
   clears, and an admitted host must have PROVED its kernel-truth lane before it
   may accept tenant workloads. This is not a feature either incumbent models.

8. **Localization as a hard requirement.** Every operator-facing string is a
   microcopy key with en+nl catalogs, scored and filtered. Pterodactyl has
   translations; Coolify is English-first. The gap here is our own (see the
   localization row) but the mechanism is present everywhere rather than retrofitted.

---

## Findings from writing this document

One BEHAVIOUR defect and three documentation defects, all found by reading code
rather than plan notes.

**Defect 1 (behaviour, NOT fixed here, ranked second in the OPEN list): a named
volume does not survive a health-gated release.** Volume names are derived from
the instance id (`docker/SiteContainerKind.java:170-174`); a gated release mints a
new instance row (`SiteReleases.newInstanceRow`, `:692-706`); so the candidate
mounts a different, empty volume, and the previous one is orphaned -- neither
migrated nor reclaimed. The in-place path reuses the row and therefore keeps the
volume, which is why this hid. Not fixed in this pass because it is a naming and
adoption change that wants its own test, and because writing an untested fix into
a documentation commit is the shape this document exists to criticize. The
docblock that asserted the opposite HAS been corrected.

Documentation defects, all corrected in place:

2. `sitetype/types/DockerSiteType.java:79-82` said the named volume "survives
   redeploys". False for the gated-release path -- see defect 1.
3. `server/instance/InstanceQuota.java:32-34` said disk was "explicitly out of
   scope". Disk IS enforced per owner, with a passing hermetic test. Stale in the
   conservative direction, but it misleads anyone counting quota dimensions.
4. `server/source/GitProviders.java:18-24` lists Gitea alongside GitHub and GitLab
   as though it were a supported provider; the class two lines below refuses
   `gitea` by name. Anyone auditing provider support from that docblock gets the
   wrong answer.

And the plan sentence itself (`instance-tier-plan.md:71-80`) lists "build
isolation ...", "domains/TLS" and "persistent storage" as flat clauses when all
three are partial. That is recorded here rather than edited into the claim, because
the claim is the thing this inventory gates.

---

## Verdict

The inventory is CLOSED: every clause of the minimum claim has a decision and its
evidence.

**The Coolify replacement claim is NOT yet usable publicly**, and unlike the
Pterodactyl inventory the blocker is not only coverage:

- item 2, build isolation, is FALSE for the git `build_command` lane, which also
  receives the site's runtime environment variables. The claim as written is
  wrong, not merely unproven.
- item 10, persistent storage, silently loses a named volume across a
  health-gated release.
- item 9, automatic TLS, has no test that ever obtains a certificate.
- items 6 and 7, the rollout and rollback that are the product's centrepiece, have
  no hermetic coverage at all -- every assertion about them sits behind a Docker
  socket and a pre-pulled image.
- item 12, quotas, cannot express a site or database count.

What IS solid, with hermetic state-asserting proof: projects and environments,
the webhook receiver, per-deployment logs, credential derivation, the API
projection and scope narrowing, and the preview expiry mechanics. What would
block calling this a general Coolify replacement for someone else, in one
sentence: there is no compose runtime and no provider provisioning flow -- both
argued, both real adoption costs.
