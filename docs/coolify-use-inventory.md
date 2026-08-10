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

**Two clauses of the plan's minimum claim were OVERCLAIMED by that sentence when
this document was written: item 2 (build isolation) and item 3 (provider flows).**
Item 2 was FIXED on 2026-08-08 and now states the boundary of each build lane by
name; item 3 is still the correction it was. Read those two first.

### The counts

Thirteen numbered clauses, plus three argued rejections and one cross-reference:

| verdict | count | items |
| --- | --- | --- |
| IMPLEMENTED | 12 | 1, 2, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13 |
| PARTIAL | 1 | 3 |
| REJECTED | 3 | A (compose runtime), C (large service marketplace), D (multi-server orchestration) |
| OPEN | 0 as a row | ONE OPEN SLICE remains, named inside a row: the CLI test wiring (13). Item 12's site/database count quotas CLOSED 2026-08-08 |
| CLAIMED | 0 sub-verdicts | ACME acquisition (item 9) moved CLAIMED -> IMPLEMENTED 2026-08-08; Gitea (item 3) did the same |

Counts updated 2026-08-10: item 5 moved PARTIAL -> IMPLEMENTED when per-branch
and manual preview creation shipped (its STATUS block records the quota decision).
Previous update 2026-08-08: items 2 and 10 moved PARTIAL -> IMPLEMENTED when the
two defects this document found were fixed (build isolation, and a named volume
surviving a health-gated release). Each row keeps the original finding as history
above its resolution -- do not delete those paragraphs, they are why the fix looks
the way it does.

Of the 12 IMPLEMENTED rows, **one is proven only `[live]`** (1, the buildpack
build). Items 6 and 7 -- the rollout and rollback that are the product's
centrepiece -- gained a hermetic STATE-MACHINE twin on 2026-08-08 beside their live
class, item 9 gained hermetic ACME issuance against an in-JVM CA, and items 2 and
10 were already `[test]`. Where a row now carries BOTH marks, both classes are
load-bearing and each row says what the hermetic half cannot prove. Section B is a
cross-reference, not a rejection.

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

**IMPLEMENTED as of 2026-08-08, with the boundary of each lane NAMED.** There are
TWO build lanes in this product; they are isolated DIFFERENTLY and deliberately,
and neither one hands a build the workload's runtime credentials any more. The
paragraphs below keep the original finding as history -- when this row said
PARTIAL, lane 2 ran a shell command on the controller with the site's runtime
`environment_variables` merged in.

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

### Lane 2, the git `build_command` -- a HOST process, confined as one

Reached by the non-docker git site types (static, node); docker-typed git sites
go to lane 1. It executes `List.of("sh", "-c", buildCommand)` as a host process
under the site's uid (`server/source/GitDeployment.java`, `runBuild`), and the
DECISION recorded there is that it stays one: BuildSandbox produces a docker image
from a declared builder image, while these types declare no image and need the
checkout's FILES in their slot. What it gained on 2026-08-08:

- The site's runtime `environment_variables` are no longer merged in at all. Only
  `build_environment_variables` (`GitSourceSchema.java:78-80`, declared
  `.secret()`) reaches a build. This mattered even though the command is
  operator-authored: the REPO is not, and a dependency's postinstall script had a
  path from the site's `DATABASE_PASSWORD` into a deploy log any tenant holding
  site `manage` can read over the PaaS API.
- `ProcessNetworkPolicy.apply` on the build's run-as uid -- the same chain
  `ManagedProcessSiteHandler.isolate` applies for the site's runtime process, so
  the build is denied the tenant network vocabulary (metadata service, RFC1918)
  its own workload is denied. A build that cannot be isolated is REFUSED.
- The `ProcessConfinement` cgroup scope (memory/cpu/TasksMax) sized by
  `BuildQuota.fromSettings` -- the same quota lane 1 uses. Unenforceable host =
  refusal, which is BuildSandbox's own doctrine ("a build that starts unprotected
  is worse than a build that does not start").
- `build_timeout` may only TIGHTEN `builds.timeout_seconds`. It used to override
  it, so the operator's global build time cap enforced nothing on this lane.

Still NOT true of this lane, and stated on the method so nobody reads more into
it: no container, no filesystem namespace, no capability bounding set (sudo has
already dropped CAP_SETPCAP by the time the child could ask). It is the
host-process tier's floor, not the sandbox's.

- Test: `src/browserTest/java/be/elevenways/hohenheim/server/source/GitDeploymentTest.java`
  -- `theBuildSeesBuildTimeVariablesOnlyAndIsRefusedWhenItCannotBeConfined` reads
  the build's OWN environment back off disk (a build-time variable arrives as the
  positive control; the runtime secret is absent), then proves both refusals
  (unenforceable quota, unenforceable network policy) leave the command unrun, and
  that the applied ruleset denies the tenant ranges for THAT uid;
  `aSiteDeclaredBuildTimeoutCannotOutlastTheHostsBuildTimeQuota` is killed at the
  host's 10s, not the site's 600s. Hermetic, no assumption gate. **[test]**

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

**STATUS 2026-08-08 -- CLOSED, and the half-wiring turned out to hide a
PRODUCTION-DEPLOY defect that was never Gitea-specific.** Supersedes the two
paragraphs above; they stay as the finding that led here.

- Gitea is now a real provider kind: `GitProviderModel.KIND_GITEA` with its enum
  value, `server/source/GiteaProviderClient.java` on the shared
  `ApiProviderClient`, and one branch in `GitProviders.clientFor`. Everything
  downstream was already kind-agnostic, so the repository/branch pickers, the
  connection test and `credentialEnv` needed NO change -- which is the evidence
  that the gap really was one missing branch. Wire shapes were taken from the
  Gitea source, not assumed from GitHub's: `page`+`limit` pagination (not
  `per_page`), `/api/v1/user/repos`, plain (never percent-encoded) `owner/repo`
  segments with a THIRD segment refused because Gitea has no subgroups, and
  `CommitStatusState` = pending/success/error/failure/warning so FAILURE is
  `"failure"` (GitHub's word, not GitLab's `"failed"`). **[code]**
- A blank `base_url` is a NAMED refusal (`git_provider_base_url_required`), not a
  default. github.com and gitlab.com are hosts an operator obviously meant;
  gitea.com is not, so defaulting would aim a stored token at a third-party forge
  nobody named. **[code]**
- The clone credential puts the token in the PASSWORD position with a fixed
  username, per `services/auth/basic.go` ("Assume password is token": any
  non-empty password that is not the literal `x-oauth-basic` is looked up as an
  access token, whatever the username). **[code]**
- **Correction to this document:** the claim above that "a Gitea delivery folds
  for replay on `body:` + sha256(body)" and that `eventOf` cannot see its events
  was WRONG for any current Gitea. `services/webhook/deliver.go`'s
  `addDefaultHeaders` emits the Gitea, Gogs AND GitHub-compatible header sets on
  every delivery -- `X-GitHub-Event`, `X-GitHub-Delivery` and
  `X-Hub-Signature-256: sha256=<hex>` included -- so Gitea deliveries already rode
  the GitHub branches of `validateSignature`, `deliveryKeyOf` and `eventOf`, and
  `X-Gitea-Signature` (raw hex, no prefix -- that part of the claim IS right) was
  effectively unreachable. The vendor headers are now read anyway, for a forge or
  a proxy that forwards only its own set. **[code]**
- **Live defect found and FIXED (this is the important half): any event whose
  payload carries no `ref` queued a PRODUCTION deploy.** `handlePush` gated the
  branch comparison on `!pushedRef.isEmpty()`, so a correctly signed delivery of
  an unmodelled event -- an issue, a release, a star, a review comment -- skipped
  the branch check entirely and reached `enqueueDeploy("webhook")`. Reachable on
  ALL THREE providers (GitHub `issues`, GitLab `Issue Hook`, Gitea `release`), so
  the GitLab merge-request mapping recorded at item 5 closed one event and left the
  fall-through open. A missing `ref` is now `ignored_not_a_push`: every provider's
  push payload carries one, so its absence means "not a push", never "any branch".
  **[code]**
- **Second defect FIXED: Gitea's pull-request commit action is spelled
  `synchronized`**, not GitHub's `synchronize` (`HookIssueSynchronized`), so a new
  commit on a Gitea pull request answered 200 `ignored_pr_action` and rebuilt
  nothing -- the preview stayed pinned at the sha it was opened with. Silent
  success, the same shape as the unmapped-event defect one layer down. **[code]**
- Test: `src/browserTest/java/be/elevenways/hohenheim/test/source/GiteaProviderClientTest.java`
  (7 tests, fake v1 API, asserted on captured traffic; `everyDeclaredProviderKindConstructsAClient`
  is the anti-drift guard that makes "declared in the dropdown, refused by the
  funnel" fail from now on) plus two journeys in `GitWebhookSecurityTest`
  (`anEventCarryingNoRefIsNotAPushAndNeverQueuesAProductionDeploy` asserting the
  deployment table stayed EMPTY, and
  `giteaDeliveriesReachTheSameLanesUnderItsOwnHeadersAndSpellings`). Every gate has
  a reverted-and-observed counterfactual; the no-ref one answered
  `{"status":"queued"}` before the fix. All hermetic, none skipped. **[test]**

The item-3 sub-verdict for Gitea therefore moves CLAIMED -> IMPLEMENTED. Still
genuinely OPEN in item 3, unchanged: outbound PROVISIONING (deploy keys, webhook
registration) for every provider.

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

**IMPLEMENTED (2026-08-10; was PARTIAL -- per-pull-request only, no per-branch
preview and no manual create. The original finding is kept below; the STATUS
block at the end of this row is the resolution).**

`server/preview/PreviewDeployments.java:72` (`deploy` `:105`, expiry stamped
`:150-154`, one-shot expiry schedule armed `:160`/`:327-331`), with
`PreviewExpireAction`, `PreviewQuota.java:34`, `PreviewDomains`,
`PreviewRequestHandler`, and `PreviewDeploymentModel.java:71` carrying
`EXPIRES_AT`. **[code]**

The only automatic trigger is a change-request event -- `pull_request`
(GitHub/Gitea) or `Merge Request Hook` (GitLab),
`GitWebhookHandler.java:387-390`. (2026-08-08: Gitea's `pull_request` reached this
lane but its REDEPLOY action, spelled `synchronized`, did not; see item 3's STATUS
block. Previews on Gitea were created once and never rebuilt.) A PUSH to a branch never creates a preview
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

**STATUS 2026-08-10: the two missing lanes shipped, and the row moves PARTIAL ->
IMPLEMENTED.** Per-BRANCH previews: a site declares glob patterns
(`GitSourceSchema.PREVIEW_BRANCHES`, matched by `server/preview/PreviewBranches`),
and a push to a matching non-production branch runs the same
`PreviewDeployments.deployQuietly` lifecycle the PR lane uses
(`GitWebhookHandler.handlePush`); a branch DELETE tears its preview down, and --
found while building this -- a delete push of the PRODUCTION branch used to pass
the ref equality check and queue a production deploy of a branch that no longer
exists (now `ignored_deleted_ref`). MANUAL creation: the admin previews resource
and the new `/manage` projection (`ManagePreviewDeploymentResource`, scoped and
gated by the site's existing `manage` capability -- no new verb) create for a
chosen ref through `PreviewDeployments.queue`, which claims the quota-charged row
SYNCHRONOUSLY (refusals land on the form by name) and builds in the background.

QUOTA DECISION (recorded here and in `PreviewDeployments.queue`): every lane
charges the SITE's manage-grant owner bucket -- the `PreviewQuota` before-write
hook fires on the row save regardless of surface, so charge == cap holds
structurally. Branch previews are OPT-IN PER PATTERN (empty = PR previews only;
the production branch never gets one). At the cap every lane REFUSES BY NAME
(`preview_quota_reached`; webhook lanes also report a commit-status FAILURE when
a sha is known); eviction of the oldest preview was REJECTED because it destroys
a running environment to serve a push nobody is watching.

- Test (hermetic): `GitWebhookSecurityTest.java`
  `aPushToADeclaredPreviewBranchDrivesThePreviewLifecycleNotProduction` -- the
  matching push queues a preview (pre-fix counterfactual: it answered
  `ignored/branch`), an undeclared branch stays ignored, a branch delete reclaims
  the preview row, a production-branch delete deploys NOTHING (pre-fix it
  deployed), and the production lane still deploys. **[test]**
- Test (hermetic): `PreviewCreationLanesTest.java` -- the glob vocabulary incl.
  the empty-set opt-in default; `queue` claims synchronously, charges the owner
  bucket, refuses over-cap BY NAME without evicting, records the async build
  failure on the row, releases on destroy; and the /manage gate, with the
  counterfactual that the ungated admin resource creates for the same stranger.
  What this file cannot prove: a manual preview reaching RUNNING (live lane).
  **[test]**

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

**STATUS 2026-08-08 (supersedes the paragraph above): the state machine now has a
hermetic twin, and the live class STAYS.**
`src/browserTest/java/be/elevenways/hohenheim/test/docker/SiteReleaseContractTest.java`
runs the whole engine with NO daemon, NO netns and NO image, against
`test/docker/FakeDockerDaemon.java` -- one in-memory daemon with two faces onto the
same state (an `InstanceRuntime` the site kind deploys through, and a
`DockerTransport` every `new DockerClient()` speaks raw HTTP/1.1 to, because
`SiteInstances.reusableStatus` reads the daemon directly while the deploy goes
through the runtime). A running fake container really listens on loopback, so the
engine's health probe is a genuine HTTP round trip and an unhealthy candidate is a
real 503. 54 assertions, 4 journeys, ~5 seconds. **[test]**

What the hermetic twin adds over the live one: the refused candidate's whole life
at the daemon as a recorded CALL SEQUENCE (create, start, destroy -- never
stopped-and-kept the way a RETAINED release is), the reuse lane not minting an
operation for an unchanged site, and boot recovery's **half-flipped switch**
branch (`recoverOne`, candidate already serving and the superseded row still
serving) -- which had NO coverage at all, live or hermetic.

What it CANNOT prove, and therefore what keeps `SiteReleaseLiveTest` mandatory:
zero dropped requests across the swap (no proxy, no traffic), `DockerInstanceRuntime`
/ `WorkloadNetworks` / the nftables workload policy (the fake stands in for the
kind's runtime), the image fingerprint pin, and that a real registry produced the
digest. Both classes are load-bearing; neither replaces the other.

**Behaviour defect found by writing that twin, FIXED: a serving release whose
WORKLOAD was killed inside a running container was reported converged forever.**
`SiteInstances.reusableStatus` already refused to reuse an OOM-killed container,
but `SiteReleases.release` then computed `protecting` from `running() &&
publishedPort() != null` alone, took the fingerprint-adopt branch, and returned the
same dead release with the operation stamped SUCCEEDED ("spec unchanged;
fingerprint adopted without a deploy"). The refusal one layer up achieved nothing.
`protecting` now also requires `!oldLive.workloadDead()`, so the honest thing
happens instead: redeploy in place, no probe gate, because there is no live traffic
to gate against.

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

**STATUS 2026-08-08: rollback and its pin are hermetically covered too.**
`SiteReleaseContractTest.aHealthySwapDrainsRetainsRollsBackAndReclaims` walks
release -> swap -> drain -> retain-exactly-one -> rollback -> reclaim -> pin held
across an unchanged converge -> pin dissolved by a source change, asserting
PERSISTED roles and settings rather than a status, and asserting that the rollback
pulled NOTHING (the fake CA of images counts `/images/create` calls: an artifact
addressed by content needs no registry). The API lane is still refusal-only. **[test]**

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

**STATUS 2026-08-08 -- the "credential injection" half of this clause now covers
INSTANCES too, which is what it was missing.** Injection was site-only: a database
attached to a game server injected NOTHING, so half of Coolify's clause was met by
one workload tier and unmet by the other. `instance_databases` (M087) closes it with
the two-sided authority rule, resolve-time derivation into command/cloud-init/config
files, its own `idblink` link-network lane, and an in-use refusal that now counts
workloads rather than sites. Full argument (including why it is a SECOND join model
and not a generalization) is item 10 of `pterodactyl-use-inventory.md`.

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

**STATUS 2026-08-08: CLOSED, and NOT with Pebble.** The OPEN slice above named a
Pebble container; that was rejected on the evidence this document itself collects,
because a container puts the proof behind a Docker socket and a pre-pulled image --
the exact gate that made ACME unmeasured in the first place. What shipped instead
is `src/browserTest/java/be/elevenways/hohenheim/test/tls/FakeAcmeServer.java`: a
real RFC 8555 CA IN THIS JVM (directory, nonces, account, orders, authorizations,
challenge validation, finalize, and a chain signed by its own throwaway CA from the
client's REAL PKCS#10 CSR). The client half is entirely genuine -- acme4j signs real
JWS, and the product parses a real X.509 chain and reads its notAfter.

`test/tls/AcmeIssuanceContractTest.java` issues one HTTP-01 certificate and one
WILDCARD DNS-01 certificate, and asserts the stored row: status active, a parsed
leaf whose SANs are exactly the ordered names, the expiry read OFF the certificate,
the account key persisted once and REUSED by the second order (a new account per
order is how a CA rate-limits you out), and for DNS-01 that the TXT record is
published under the fully qualified BASE identifier
(`_acme-challenge.wild.test.`, wildcard label dropped, 43-char base64url digest)
and REMOVED again. The refusals are covered too: a validation the CA fails and an
order the CA rejects at finalize both leave no certificate, a row stamped error,
the CA's own problem detail recorded and a backed-off retry. 31 assertions, ~20
seconds, no daemon. **[test]**

**This required a production change, and it is a product feature rather than a test
seam:** the ACME directory was hardcoded to `acme://letsencrypt.org` inside
`ensureAccount`, so an internal ACME CA (step-ca, an enterprise CA) was unreachable
at any amount of configuration. It is now `ssl.acme_directory_url` (blank = Let's
Encrypt, production or staging per the existing switch), read through
`AcmeService.directoryUri`.

What this CANNOT prove, so nothing reads it as total coverage: that the REAL Let's
Encrypt accepts our requests (a fake directory cannot discover a tightened
validation, a new required field or a rate limit -- only a staging order can, and
that is deliberately not a test), that the CA can REACH us (the HTTP-01 validator
calls the challenge responder in-process, so DNS, port 80 and the proxy's
`.well-known` route are untouched), that a DNS-01 value is queryable on the
internet, and certificate TRUST. The row therefore moves CLAIMED -> IMPLEMENTED for
the ACQUISITION mechanism, not for interoperability with a specific public CA.

## 10. Persistent storage

**IMPLEMENTED as of 2026-08-08 (the defect recorded below is FIXED; the finding
stays as history).**

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

**DEFECT (found by writing this document, FIXED 2026-08-08 -- see the resolution
below): a named volume did not survive a health-gated release.** The volume name
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

**RESOLUTION (2026-08-08).** Volume identity is keyed to the SITE:
`server/docker/SiteVolumes.java` mints
`hohenheim-{token}-site-{siteId}-vol-{mount}` (the `DatabaseInstances.dataVolumeOf`
precedent), `SiteInstances.desiredSettings` resolves the declared mounts through
it, and `SiteContainerKind.specFor` mounts the stored names VERBATIM instead of
re-deriving them from the instance handle. Two consequences worth stating:

- The volume is CREATED by `SiteVolumes`, not by the mount's `VolumeOptions`, so
  its owner labels name the SITE. Docker labels a volume at birth only, so a
  mount-created volume stayed attributed to whichever release row happened to
  create it -- and once that row is reclaimed the reconciler reads live site data
  as an orphan. `DockerReconciler.classifyByNamingScheme` already had a
  `site-{id}-vol-{mount}` branch with nothing minting it; it is live now.
- ADOPTION is performed, not assumed away: when the site volume is absent and one
  of the site's OWN live instance rows names an existing instance-keyed volume,
  its data is copied in (entries counted on both sides from inside the copy
  container, refusing and removing the half-copy rather than deploying an empty
  volume). The legacy volume is left in place on purpose -- autonomous volume
  removal is a declared non-primitive here. Every live instance row of the site is
  then rewritten to the resolved name AFTER the release writes its own rows: that
  ordering is load-bearing and was measured, because `gatedSwap` flips the
  superseded row's role from a Row loaded BEFORE spec resolution and
  `Models.save` writes the whole row, so an earlier heal was clobbered while
  reporting success.

- Test: `src/browserTest/java/be/elevenways/hohenheim/test/docker/SiteVolumeLiveTest.java`
  -- `tenantStateInANamedVolumeSurvivesAGatedReleaseAndARollback` writes state
  into the volume, releases forward through the health gate, rolls back, and reads
  every byte back from INSIDE the container that is serving (plus: exactly one
  site volume, no instance-keyed volume, every row storing the site-keyed name);
  `aLegacyInstanceKeyedVolumeIsAdoptedWithItsDataAndTheRowIsHealed` covers the
  adoption and the row heal. Counterfactual (naming reverted): the swap answers
  `cat: can't open '/data/state.txt': No such file or directory`. Gated on the
  socket and a local alpine image. **[live]**

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

**IMPLEMENTED as of 2026-08-08** (was PARTIAL: no site or database count). Instance
count, instance MEMORY, disk GB, extra NICs, SITES and managed DATABASES are all
per-owner dimensions on one transactional ledger -- see the STATUS block at the end
of this item.

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

**STATUS 2026-08-08: DONE, and the row moves PARTIAL -> IMPLEMENTED.** Both counts
exist as transactional reservations on the write funnel, not as surface checks.

- Code: `src/server/java/be/elevenways/hohenheim/server/quota/SiteQuota.java:51`
  and `DatabaseQuota.java:51`, both over the shared
  `server/quota/OwnerQuota.java` (the one owner of the bucket FOLD, the
  override/default limit rule and the named refusal). Caps at
  `InstanceQuotaModel.java:89` (`MAX_SITES`) and `:96` (`MAX_DATABASES`),
  settings `hohenheim.quota.max_sites_per_owner`
  (`HohenheimSettings.java:788`) and `max_databases_per_owner` (`:799`),
  charged-bucket stamps `SiteModel.QUOTA_BUCKET` / `DatabaseModel.QUOTA_BUCKET`,
  migration `M089_SiteAndDatabaseQuotas` (columns + a heal that seeds the
  existing population), installed in `HohenheimWriteHooks`, both caps on the
  admin form (`cms/InstanceQuotaResource.java`). **[code]**
- Test: `src/browserTest/java/be/elevenways/hohenheim/test/quota/SiteAndDatabaseQuotaTest.java:81`
  and `:180` -- hermetic, 0 skipped. Sites: a CONTAINERLESS proxy site still
  spends a slot (the whole point), two racing creates through the real
  `/admin/sites/new` submit cannot both spend the last one (state: one live row,
  `used == limit`), the loser gets the named `site_quota_reached`, the trash write
  `SiteResource.deleteRow` performs hands the slot back, the freed slot admits
  exactly one replacement, reviving a trashed site over the cap is refused, and the
  hard-delete pairing releases too. Databases: the dimension has its own bucket
  namespace, a record spends a slot and is stamped, an over-cap create is refused
  by name with NOTHING persisted while the instance cap is wide open, the hard
  delete (the only release lane -- databases have no `deleted_at`) hands the slot
  back, and the freed slot admits a new allocation. **[test]**

**IS A SEPARATE DATABASE COUNT NEEDED? YES, and the instance charge was verified
rather than assumed.** `TenantDatabases.allocate` -> `DatabaseInstances
.reserveEngineRow` really does charge the engine container to the DATABASE's owner
(the `ce8ccb5` attribution), so a database already costs one instance slot. That
does not make a database count redundant: an instance slot is a WORKLOAD slot the
same owner also spends on game servers, stacks and site containers, so "three
databases per tenant" cannot be expressed through it at any cap -- and a database
carries costs the instance count knows nothing about (credentials, a data volume,
backups, a restore surface). The two are charged together and both must fit.

**ONE HONEST LIMIT of the site count today:** the only lane that creates a site is
the ADMIN panel (`ManageSiteResource.creatable()` is false and the PaaS API has no
site create), so today every site charges the operator bucket. The gate is on the
write funnel anyway, which is the point: the tenant lane that arrives later
inherits it with no second copy of the rule, and it already uses the same
`HohenheimAccess.creationOwnerSubjects` derivation the charge does. The inventory's
original wording -- "so a tenant can create unbounded sites" -- was WRONG about
today: a tenant cannot create a site at all.

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

Caveat 2 (CLOSED 2026-08-09): `tools/hoh.test.js` used to be a standalone node
script no Gradle task invoked, so the CLI's own tests never ran. `HohCliTest`
(browserTest) now runs it in the verification lane and fails on its exit code.

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
   **DONE 2026-08-08** (`5d3a0ed`) -- see item 2's row: both lanes now state their
   boundary by name and neither receives the workload's runtime credentials. Kept
   in place with its number: the ranking is history, not a worklist.
2. **Key volume identity to the SITE, not the instance row** (item 10). Value:
   highest -- a named volume silently comes back EMPTY after a health-gated
   release, which is data loss presented as a successful deploy. Effort: medium: a
   naming change plus adoption of the existing volume, and a hermetic-plus-live
   test that writes, releases and reads back. Prerequisite: none -- there are no
   live installations, so a rename is free.
   **DONE 2026-08-08** (`f357bbf`) -- volumes are keyed to the SITE and the existing
   one is adopted; see item 10's row. Kept in place with its number.
3. **Issue one real certificate in a test** (item 9). Value: high -- ACME is the
   highest-value entirely unmeasured mechanism in the product, and an ACME
   regression stays silent until an expiry. Effort: medium: a Pebble container in
   the browser lane, one HTTP-01 and one DNS-01 wildcard order. Prerequisite: none.
   **DONE 2026-08-08, and the Pebble half of that plan was REJECTED** -- a container
   puts the proof behind the very gate that made ACME unmeasured. An in-JVM RFC 8555
   CA (`FakeAcmeServer`) issues both certificates instead, and the hardcoded
   directory became `ssl.acme_directory_url`. See item 9's STATUS block for what it
   deliberately does not prove. Kept in place with its number.
4. **A hermetic test of the release engine** (items 6, 7). Value: high -- the
   zero-downtime swap, the drain and the rollback have NO non-live coverage at
   all. Effort: medium-high; it needs a fake docker runtime the way
   `FakeNativeDaemons` fakes the native one. Prerequisite: none, and it would pay
   for item 2's test as well.
   **DONE 2026-08-08, exactly that shape** -- `FakeDockerDaemon` fakes the
   site_container kind's runtime (and the local daemon's HTTP transport, because the
   reuse lane reads the daemon directly), and `SiteReleaseContractTest` covers the
   state machine including boot recovery's half-flipped switch, which nothing
   covered before. The live class stays: see items 6 and 7 for the split. Writing it
   found and fixed a converge-reports-success-over-a-dead-workload defect. Kept in
   place with its number.
5. **Per-owner site and database count quotas** (item 12). Value: medium-high --
   "five sites, three databases" is not expressible, and a database only charges
   an instance slot indirectly. Effort: low-medium on the existing ledger.
   Prerequisite: none.
   **DONE 2026-08-08** -- both dimensions on the same ledger (M089, `SiteQuota`,
   `DatabaseQuota`, the shared `OwnerQuota`), each with a race, a named refusal and
   every release path proven, and the database count argued NOT redundant with the
   instance slot its engine spends. Kept in place with its number: the ranking is
   history, not a worklist. See item 12's STATUS block.
6. **Git provider provisioning: deploy keys and webhook registration** (item 3).
   Value: medium -- it converts connecting a repository from a manual paste into a
   flow, which is most of Coolify's perceived polish. Effort: medium per provider.
   Prerequisite: the GitHub App lane already mints installation tokens, so the
   credential path exists.
7. **A compose-to-stack IMPORTER** (rejection A). Value: medium, mostly adoption
   -- it removes the "re-enter every service by hand" migration cost. Effort:
   medium-high, and it must REFUSE what it cannot model rather than approximate
   it. Prerequisite: agreement that refusing is acceptable UX.
8. ~~**Wire `tools/hoh.test.js` into the build** (item 13).~~ DONE 2026-08-09:
   `HohCliTest` runs it in the browserTest lane; the suite was green on first run.
9. ~~**Per-branch or manual preview creation** (item 5).~~ DONE 2026-08-10: branch
   previews are opt-in glob patterns, manual creation exists on /admin and /manage,
   and the quota decision (site-owner charge, refuse-by-name at the cap, never
   evict) is recorded in the row and in `PreviewDeployments.queue`.
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
   translations; Coolify is English-first. The gap that used to sit here (page
   titles built by concatenation) is closed and source-guarded as of 2026-08-08 --
   see the localization section of the Pterodactyl inventory.

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
   wrong answer. **CLOSED 2026-08-08 the other way round: rather than narrow the
   docblock, Gitea became a real kind (see item 3's STATUS block). Hunting for the
   half-wiring is what surfaced defect 5 below.**

**Defect 5 (behaviour, FIXED 2026-08-08, found while closing item 3's Gitea gap):
an event carrying no `ref` queued a PRODUCTION deploy.** `GitWebhookHandler`'s
push lane compared the branch only `if (!pushedRef.isEmpty())`, so a correctly
signed delivery of any event this handler does not model -- GitHub `issues`,
GitLab `Issue Hook`, Gitea `release` -- fell through to `enqueueDeploy("webhook")`
on the site's bound branch. The GitLab merge-request mapping (item 5) had closed
exactly one event of this family and left the fall-through itself open. Fixed with
a test whose counterfactual observed the pre-fix `{"status":"queued"}`.

And the plan sentence itself (`instance-tier-plan.md:71-80`) lists "build
isolation ...", "domains/TLS" and "persistent storage" as flat clauses when all
three are partial. That is recorded here rather than edited into the claim, because
the claim is the thing this inventory gates.

---

## Verdict

The inventory is CLOSED: every clause of the minimum claim has a decision and its
evidence.

**VERDICT SUPERSEDED 2026-08-08 (second block of the day).** The list below was
written before that day's fixes and then went STALE IN THE UNDERSTATING DIRECTION:
it kept naming items 2 and 10 as blockers after both had been fixed and their ROWS
updated (`5d3a0ed`, `f357bbf`), and it named items 6, 7 and 9 as having no hermetic
coverage before they got it. A verdict wrong about what is closed is exactly how a
real gap gets ignored -- which is the reason this document exists. The original text
is kept below it as history.

**VERDICT SUPERSEDED AGAIN 2026-08-08 (third block of the day), and this one moves
the standing rather than only correcting it.**

**Current standing: NO clause of the minimum claim is unmet.** The last one, item
12's site and database counts, closed with M089 -- two transactional dimensions on
the existing ledger, each proven against a race, a named refusal and every release
path, plus the argued finding that a database count is NOT redundant with the
instance slot its engine container spends. One limitation is stated in that row and
not hidden here: sites can only be created by an admin today, so the site cap
currently binds the operator bucket; the gate lives on the write funnel so the
tenant lane inherits it.

Closed since the original verdict, each with a dated STATUS block in its own row:

- item 5, previews: CLOSED 2026-08-10 -- per-branch previews (opt-in glob
  patterns, branch-delete teardown, and the production-branch-delete deploy bug
  fixed on the way) and manual creation on /admin and /manage
  (synchronous quota-charged claim, site-owner charge, refuse-by-name at the
  cap). The one remaining preview limitation stays stated in the row: a manual
  preview reaching RUNNING is live-lane-only proof. **[test]**
- item 12, quotas: CLOSED 2026-08-08 -- per-owner SITE and managed-DATABASE counts
  (`SiteQuota`, `DatabaseQuota`, `OwnerQuota`, M089), beside the per-owner MEMORY
  budget that closed the Pterodactyl inventory's last blocker the same day. **[test]**

- item 2, build isolation: FIXED `5d3a0ed` -- both lanes name their boundary and
  neither receives the workload's runtime credentials. **[test]**
- item 10, persistent storage: FIXED `f357bbf` -- volumes are keyed to the SITE and
  the existing one is adopted. **[test]**
- items 6 and 7, the rollout and rollback: a hermetic STATE-MACHINE twin now runs
  with no daemon (`SiteReleaseContractTest` over `FakeDockerDaemon`), BESIDE the
  live class, which stays because zero-dropped-requests through a real proxy is not
  something a fake can assert. Writing it found and fixed a converge that reported
  success over a workload the daemon had killed. **[test] + [live]**
- item 9, automatic TLS: a real RFC 8555 CA in the test JVM issues one HTTP-01 and
  one wildcard DNS-01 certificate, refusals included; the hardcoded Let's Encrypt
  directory became `ssl.acme_directory_url`, which also makes an internal ACME CA
  reachable in production. Never touches the real Let's Encrypt. **[test]**

Each of those rows states, in its own words, what the hermetic half CANNOT prove.
Read none of them as total coverage: three of them keep a `[live]` class precisely
because a fake cannot discover a daemon or a public CA changing under us.

What IS solid, with hermetic state-asserting proof: projects and environments,
the webhook receiver, per-deployment logs, credential derivation, the API
projection and scope narrowing, the preview expiry mechanics and all three
preview creation lanes (pull-request, branch, manual), build isolation,
persistent storage, the release state machine and certificate acquisition. What
would block calling this a general Coolify replacement for someone else, in one
sentence: there is no compose runtime and no provider provisioning flow -- both
argued, both real adoption costs.

---

The original verdict of 2026-08-08, kept as history:

> **The Coolify replacement claim is NOT yet usable publicly**, and unlike the
> Pterodactyl inventory the blocker is not only coverage:
>
> - item 2, build isolation, is FALSE for the git `build_command` lane, which also
>   receives the site's runtime environment variables. The claim as written is
>   wrong, not merely unproven.
> - item 10, persistent storage, silently loses a named volume across a
>   health-gated release.
> - item 9, automatic TLS, has no test that ever obtains a certificate.
> - items 6 and 7, the rollout and rollback that are the product's centrepiece, have
>   no hermetic coverage at all -- every assertion about them sits behind a Docker
>   socket and a pre-pulled image.
> - item 12, quotas, cannot express a site or database count.
