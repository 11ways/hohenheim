# Loose ends (post re-review) — working report

Date: 2026-08-01. Six items, worked in the assigned priority order.

Items 5 and 6 were done directly (they are not behaviour fixes and needed a
different kind of verification). Items 1-4 are behaviour fixes and were waved to
Fable implementation subagents, each carrying the mandatory pre-fix-failure
standard. Their outcomes are appended below as they land.

---

## Item 5 — thoth commits generated output — DONE (thoth `2ee1ee4`)

### Deployment-path verification (done BEFORE acting, as required)

The question was whether anything reads the committed copy, which would make
untracking a deploy break. Four independent checks say no:

1. **How the file is produced.** `public/thoth-client.js(.map)` is written by
   `copyClientJs`, a `Copy` task auto-registered by the Hawkeye Gradle plugin
   (`hawkeye/hawkeye-compile/src/main/groovy/.../HawkeyeTemplateStage.groovy:244-262`):
   `from(buildDir/generated/teavm/js) { include '*.js'; include '*.js.map' } into(project.file('public'))`.
   It is pure build output, rewritten on every build.
2. **How the file is served.** `AssetMiddleware.publicMiddleware()`
   (`zenit/src/server/java/.../http/AssetMiddleware.java:148-183`) resolves in two
   steps: `findAssetPath(middlePath, ServerZenitRuntime.PATH_PUBLIC)` — i.e.
   `PATH_ROOT.resolve("public")`, the WORKING DIRECTORY's public dir, which
   `copyClientJs` regenerates — then a classpath fallback on `public/<path>`.
   thoth's only reference to the bundle is
   `ServerMain.java:56 Zenit.getHawkeye().setClientScriptLocation("/thoth-client.js")`,
   which sets a URL and reads no file.
3. **No deployment automation reads it.** thoth has no Dockerfile, no `*.sh`, no
   `*.service`, no deploy script (`find thoth -maxdepth 2` for all of those: empty).
   The only `public/` references in the whole repo are `build.gradle:426`
   (`output = 'public/thoth.css'`, the SCSS task's own output) and a comment at
   `build.gradle:476`. No packaging block copies `public/*.js` into any jar.
4. **Four sibling apps already do this and deploy fine.** orcono, spamservice,
   herald and quirkyquarters all gitignore their generated public bundles
   (`quirkyquarters/.gitignore:46-48`, `spamservice/.gitignore:4-5,12-13`,
   `herald/.gitignore:4-5`, `orcono/.gitignore:3,7`). thoth's `.gitignore` had **no**
   `public/` entries at all — it is the outlier, not the pattern.

Churn evidence: the file's own git history is commits like
`91687dc 🔨 Rebuild the bundled client script` and
`56dd9a3 🧹 Drop the dead plumage bridge imports; rebuild the client bundle` —
3.36 MB + 1.09 MB of TeaVM output built with `obfuscated = true`, so essentially
every line moves per rebuild.

### What changed

- `thoth/.gitignore`: added `public/thoth-client.js` and `public/thoth-client.js.map`
  with a comment stating why (build output; the served copy is the working-directory
  one, which the build regenerates).
- `git rm --cached` on both files. **They remain on disk** (verified with `ls -la public/`:
  both present, unchanged mtime), so no running server or dev loop is affected.
- `public/thoth.css` + `.map` left tracked, matching quirkyquarters. Only the JS
  bundle named in the task was untracked.

Commit `2ee1ee44bb4f044f94cdbf602ee2694a8309b20f`
`🙈 Stop tracking the generated TeaVM client bundle`.

### Limitation

If thoth is ever deployed as a bare fat jar with no build step and no `public/`
directory beside it, the bundle would have to be packaged into the jar (the SCSS
half of the Hawkeye plugin already does exactly that for CSS at
`HawkeyeTemplateStage.groovy:348-361`; the JS half has no equivalent). That is
true of every sibling app today and is not a regression introduced here.

---

## Item 6 — permanent scope note for the alchemy false positive — DONE (hawkeye `40534aa`)

### Verification of the false positive

The cited coordinate was slightly off; the real one is
`alchemy/alchemy-form/view/form/inputs/view_inline/boolean.hwk:18` (the `edit/`
variant is 4 lines long and contains nothing of the sort). It is the ONLY `"" +` /
`+ ""` occurrence in any `.hwk` file under `alchemy/` (workspace grep).

It is Hawkejs, not hawkeye, and the surrounding syntax proves it beyond doubt:
`{%t "" + value field_name=... %}` (Hawkejs i18n block), `<% $0.classList.add(...) %>`
(JS expression block), `self.createEmptyValuePlaceholderText()`, and the word
operator `AND` — none of which exist in hawkeye. `alchemy/` sits beside `hawkejs`,
`janeway`, `blessed` and `protoblast` (the Node.js originals), i.e. it is the legacy
JS stack. The expression is not even a String-position coercion: it builds a
translation KEY from a boolean, which is idiomatic JS.

`alchemy/` is confirmed NOT a git repo (`git rev-parse` fatal), so the note cannot
live there.

### Where the note went, and why there

Both candidate homes were checked and neither the workspace `CLAUDE.md` nor
`claude-configs/` is a git repo (`git rev-parse --show-toplevel` fatal in both), so
"durable AND tracked" ruled them out on their own. The note was therefore placed in
two complementary places:

1. **`hawkeye/docs/skills/hawkeye-templates/SKILL.md`** (git-tracked, hawkeye repo,
   commit `40534aa`) — a new subsection `### Never "" + value (and where that rule
   does NOT apply)` under *Expressions and Variables*. This is the doc a reviewer is
   REQUIRED to load before touching `.hwk` files, per the workspace HARD RULE, so it
   is where the rule and its scope belong. It states the rule, names the fixed
   mechanism (`RenderContext.asString`, pinned by `StringPropertyCoercionTest`), and
   then gives the scope carve-out with the exact file, the syntax tells
   (`{%t %}` / `<% %>` / `self.` / `AND`), and the fact that a workspace grep will
   hit `alchemy/` legitimately.
2. **The auto-loaded memory doc** `memory/no-string-concat-coercion.md` — a
   `**SCOPE -- do not re-raise alchemy/ again (settled 2026-08-01, third time).**`
   section, plus the `MEMORY.md` index one-liner amended to carry the carve-out.
   This is the copy that actually loads into every future session's context, which is
   the mechanism that stops a fourth review from spending time on it.

Commit `40534aa1232a165602d27ee434f13b92b677ac47`
`📝 Scope the no-string-concat rule to hawkeye, not Hawkejs`.

---

## Item 4 — two async-timing flakes — DONE (zenit `b5b7761`)

Verified by me: commit is 3 lines, subject 47 chars, subject/body on separate
lines, and touches ONLY the two test files (`git show --stat`) — no production
code, and none of the concurrently-edited files.

### Flake 1 — `WebSocketAdmissionHttpTest.contextualParametersResolveOnlyAfterTheHandshakeGates`
**TEST-side race; production correct.** In
`ZenitHttpServer.completeWebSocketUpgrade` the handler factory runs inside
`VIRTUAL_EXECUTOR.execute(...)` AFTER the 101 is on the wire
(`src/server/java/.../http/ZenitHttpServer.java:609`). Step 4 asserted
`RESOLVED_PARAM` — written by that factory — immediately after the client's
`connect().sendClose().join()`. The client completing its handshake is NOT a
happens-before for server-side handler creation. Nothing in the contract promises
the handler exists before the peer sees 101; per-connection ordering (onOpen before
first message) is separately guaranteed by the serial lane. `CONTEXT_RESOLVES` was
never racy (deferred resolution runs before the 101).

Deterministic pre-fix failure — perturbation `Thread.sleep(300L)` at the top of the
handler-setup virtual-thread task, test unmodified. Log `20260801-160932`:

```
org.opentest4j.AssertionFailedError: [step 4: the handler receives the resolved value]
expected: "row:known"
 but was: null
  at ...WebSocketAdmissionHttpTest.contextualParametersResolveOnlyAfterTheHandshakeGates(WebSocketAdmissionHttpTest.java:330)
```

Same signature as the 2026-08-01 suite flake. Fix: a `HANDLER_RAN`
`AtomicReference<CountDownLatch>` counted down by the CONTEXTUAL handler factory
after it records the parameter; step 4 awaits it (10s, loud on timeout) before
asserting — a real signal, not a longer sleep. AIDEV-NOTE records the missing
happens-before. Post-fix under the SAME 300ms perturbation: log `20260801-161357`,
PASSED (682ms).

### Flake 2 — `ChannelProtocolTest.abruptAnonymousDropReopensFresh`
**TEST-side deadline; no lost signal anywhere.** The failure was a
`TimeoutException` at `link.ready().get(10, SECONDS)` (line 238). A read of the
whole pipeline (`ChannelClient`, `ClientChannelLink`, `ChannelConnection`,
`ChannelGateway`, `ServerWebSocketConnection`) found no lost-signal path: opens are
re-sent on every HELLO_OK, frames before `resumeReceives` are buffered not dropped,
reconnect covers transport failure. The chain is ~6 thread hops (Undertow IO →
upgrade virtual thread → setup virtual thread → serial lane → JDK http-client
executor), all CPU-starvable. The class had already been hardened for this once
(commit `f21a5a9`, the `await()` 20s-ceiling AIDEV-NOTE) but seven waits were left
at 10s; the flake hit a leftover.

Deterministic pre-fix failure — same injection point, `Thread.sleep(11_000L)`,
test unmodified. Log `20260801-161510`:

```
java.util.concurrent.TimeoutException
  at java.base/java.util.concurrent.CompletableFuture.timedGet(CompletableFuture.java:1981)
  at java.base/java.util.concurrent.CompletableFuture.get(CompletableFuture.java:2116)
  at be.elevenways.zenit.channel.ChannelProtocolTest.abruptAnonymousDropReopensFresh(ChannelProtocolTest.java:238)
```

Byte-identical to the recorded flake. That same experiment is the PROOF there is no
lost signal: with an 11s delay injected the pipeline still completes — only the
deadline was short. Fix: one `WAIT_SECONDS = 20` constant across all nine future
waits in the class (matching the existing `await()` ceiling, under the class
`@Timeout(30s)`), with an AIDEV-NOTE recording the experiment. These are futures —
real signals carrying a deadline — not sleeps. Post-fix under the SAME 11s
perturbation (covering both the initial connect AND the post-drop reconnect, 23.2s
total): log `20260801-161645`, PASSED.

### Item 4 sub-question — the G9 `Thread.sleep` settling windows

Classified rather than blanket-replaced:

- **Poll-with-deadline loops** (`ChannelProtocolTest.await`,
  `WebSocketRevalidationHttpTest` 382/616/699/761/799/852,
  `ChannelGatewayRevalidationHttpTest` 180/210, `SyncedRefTest.await`) — correct
  shape already, not settling windows. Kept.
- **Deliberate jitter / work simulation** (`WebSocketOrderingHttpTest:66` random
  0-2ms, `WebSocketTransportLimitsHttpTest:101` sleep(2)) — load-bearing stress by
  design. Kept.
- **The exactly-once "no second teardown" windows**
  (`WebSocketRevalidationHttpTest` 632/707/768,
  `WebSocketTransportLimitsHttpTest:287`, `SyncedRefTest:147`) — these are ABSENCE
  assertions. They can only false-PASS on a slow machine, never false-fail under
  load, so they are not flake-fail risks. No signal is reachable either:
  `WebSocketTeardown`'s CAS refusal of a second release is deliberately silent, so
  "the other path will never fire" has no observable event; exposing the refusal
  would be a production change for testability only, and the guarantee is enforced
  by construction (CAS + `onceOnly`). **Left as-is, on record** — this is the
  "genuinely needs a design change" answer for that sub-item.
- **One residual theoretical false-FAIL, deliberately NOT changed**: the
  counter-freeze checks (`WebSocketRevalidationHttpTest` 641-645, 713-717, 772-776,
  and the budget step at 823-834). `WebSocketRevalidator.stop()` cancels the job but
  an in-flight tick can land increments after the baseline snapshot if it stalls
  >200-400ms mid-evaluate, phase-aligned with the close. Not observed in 75 suite
  runs, and no deterministic reproduction was achievable without modifying the
  assertions themselves — so, per the evidence standard, it was not "fixed" blind.
  Recorded shape if it ever fires: await-quiescence (poll until the counter is
  stable for 2 intervals, loud deadline), which still detects a leaked revalidator.

Final clean run, perturbation reverted: log `20260801-162144`, 9/9 PASSED
(`ChannelProtocolTest` 3 + `WebSocketAdmissionHttpTest` 6). `git diff` on
`ZenitHttpServer.java` empty.

Limitation: the original 11:49/11:56 flake logs were already pruned
(`testLogMax=10`), so the pre-fix evidence is the deterministic reproduction — which
matches the signatures recorded in `reports/testcontainers.md` exactly.

---

## Item 2 — two real gate-sweep bugs — DONE (orcono `56b1ada`, spamservice `8e5f1cd`)

Both verified present at HEAD by me before delegating; both commits verified by me
after (3 lines, gitmoji first, subject/body separate, correct file sets).

### Bug 1 — orcono `byId` guard bypassed the both-orders comparison

`mvp-v01/src/server/java/be/elevenways/orcono/server/ServerMain.java`,
`registerChoosers()`, wrapped both explicit `RecordSourceRegistry.register(...)`
calls in `if (RecordSourceRegistry.INSTANCE.byId(<Model>.MODEL_ID) == null)`. That
defeats the registry's both-boot-orders design: if the zenit-cms-derived default
registers first, the explicit source is silently never offered and no gate
comparison ever runs.

The double-run question mattered and was answered: `registerChoosers()` genuinely
runs TWICE per JVM today — from `OrconoWebModule.init()` (auto-discovered at the
MODULES boot stage) and explicitly from two test hosts
(`OrconoBrowserTestBase.java:106`, `OrconoAuthFoundationTest.java:147`), each of
which also runs `ServerZenitRuntime.init().join()` first. The `byId` probes were
what kept the second call quiet — at the price of the silent-shadowing hole.

Pre-fix failure, test written first, run against unmodified code
(`zenit-dev test --unit --skip-deps --class ChooserRegistrationJourneyTest`):

```
✗ explicitChoosersWinInBothBootOrders                  78ms
step 1: the explicit chooser source must replace the derived default when the default registered first ==> expected: not same but was: <be.elevenways.zenit.common.data.RecordSource@56113384>
```

Fix: probes dropped; `registerChoosers()` is now `synchronized` with a
package-visible `static volatile boolean choosersRegistered` early return — the
QQSources once-flag idiom already established in the ecosystem — plus an AIDEV-NOTE
stating why a registry probe is forbidden here. Post-fix:
`✓ explicitChoosersWinInBothBootOrders  91ms`. Regression neighbour
`OrconoAuthFoundationTest` (the class that boots the runtime AND double-calls
`registerChoosers`): all 11 green alongside the new test in one JVM.

### Bug 2 — spamservice dead `byToken` colon guards

`src/server/java/be/elevenways/spamservice/server/ServerMain.java`,
`configureCms()`, guarded three registrations with
`byToken("spamservice:client")` etc.

**One mechanism correction to the original audit claim, worth recording:**
`Identifier.trySplitOn("spamservice:client", '.')` does NOT return null. With no dot
found it returns `Identifier("elevenways", "spamservice:client")`
(`Identifier.java:31-49`, DEFAULT_NAMESPACE + the whole string as path). That id can
never match the source registered under `spamservice.client`, so `byToken` still
always returns null and the guards are dead exactly as reported — just one step
later than the audit said. The origin of the confusion is visible in the registry's
own refusal message, which prints ids via `Identifier.toString` in COLON form
(`RecordSource 'spamservice:client' is already registered`). `configureCms()` has
exactly one caller (`main()`); no test calls it.

Pre-fix failure (`zenit-dev test --unit --skip-deps --class CmsRegistrationJourneyTest`):

```
✗ configureCmsRegistersOnceAndStaysIdempotent          34ms
step 3: a repeated configureCms() must not re-register the sources ==> Unexpected exception thrown: java.lang.IllegalStateException: RecordSource 'spamservice:client' is already registered; a second registration is refused so a gated source cannot be shadowed by boot order. Use RecordSourceRegistry.override(...) to replace it deliberately.
```

(The first attempt failed on a harness issue — models unregistered in a bare test
JVM — fixed by touching `Blast` to trigger `BlastAutoLoadInit`; the failure above is
the real counterfactual.)

Fix: three dead guards dropped, `static volatile boolean cmsConfigured` early return
+ an AIDEV-NOTE documenting the dot-vs-colon token trap. The `PanelRegistry` guard
was left untouched (different registry, different semantics). Post-fix:
`✓ configureCmsRegistersOnceAndStaysIdempotent  244ms`. The new test also PINS the
dead-guard mechanism (`byToken("spamservice:client")` is null even while the source
IS registered), so it cannot silently return. Regression neighbour
`CmsMutationParityTest` green alongside it.

### Surfaced, not fixed (out of this task's scope)

- orcono `EntityCandidateSource.register()` still uses a `byId` probe. Benign there —
  its custom id `orcono.entity_candidates` can never collide with a cms-derived
  default — but it is the same probe pattern.
- zenit-ai `AiRecordSources.register()` guards with a `byToken` DOT probe. It
  *works*, but it re-introduces registry-probe idempotency where the ecosystem's
  answer is a once-flag.

### Limitation

All runs used `--skip-deps`: a full chain build currently fails on the concurrent
guard-wiring agent's planted `PlantedProbe.java` fixture in zenit-microcopy
(off-limits by instruction). One orcono run also transiently failed on m2 artifact
skew while that agent republished zenit/zenit-forms/zenit-widget mid-flight; a retry
after their publish completed was clean.

---

## Item 3 — test isolation — DONE (protoblast `19a4d95`, zenit `5ca32d5`)

Both commits verified by me (3 lines, gitmoji first, subject/body separate, file
sets correct and confined to the agent's own work).

### Altitude decision: targeted removal, NOT snapshot/restore

`Registry.remove(Identifier)` in protoblast + `Models.unregisterInstance(Model)` /
`Models.unbind(Class)` in zenit. The reasoning, which I accept:

- `Models.registerInstance` uses `putIfAbsent` + a `!contains` guard, and
  `Endpoint.Builder.validate()` throws on a duplicate id — registration in THESE
  registries never REPLACES, so removal is its exact inverse.
  `RecordSourceRegistry` needed snapshot/restore only because explicit sources
  *replace* derived ones there. Different mechanics, different correct inverse.
- Remove-only-what-you-planted is safe against production registrations:
  `unregisterInstance` evicts `INSTANCES` via `remove(class, instance)` and the
  `Registries.MODELS` entry only when it still holds that exact instance.
- A snapshot taken in `@BeforeAll` would silently restore AWAY any registration a
  production static initializer makes mid-class. Remove cannot. (This is the
  BrandTest lesson generalized: the earlier agent's "snapshot good, clear bad" was
  right about `clear()`, and remove is strictly better than both here.)
- One protoblast method serves `MODELS`, `ENDPOINTS`, `SITEMAP_PROVIDERS` and
  `WS_ENDPOINTS` for free.

AIDEV-NOTEs at both seams (Registry.java, Models.java) record why a destructive
`clear()` is never the cleanup answer.

### The finding the probe forced — the briefing's classification was WRONG for 4 of 5

The counterfactual revealed that **every public no-arg test model is auto-registered
at JVM boot** by the generated test-source `BlastAutoLoadInit`
(`zenit/build/generated-sources/blast-autoload/test/.../BlastAutoLoadInit.java`).
Proof: a run of only `RecordSourceBucketsTest` + the probe showed
`bucket_backend_event`, `bucket_performance_event`,
`source_person/titleless/article` registered although their classes never ran.

So those classes' `@BeforeAll registerInstance` calls were **no-ops all along** — the
leak was compile-time model DISCOVERY, not the test code. The split is explained by
class visibility: `ModelParamTest` / `NestedEagerLoadBatchingTest` outer classes are
package-private or take a datasource ctor, so they are not discovered and their
`@BeforeAll` leaks were real.

Fix for that half: the six models were demoted to package-private with the repo's
existing documented marker ("Deliberately non-public: must never be picked up by
compile-time model discovery" — the `SecretDocModel` precedent), which makes their
register/unregister real and test-scoped. Verified absent from the regenerated
`BlastAutoLoadInit`.

This corrects `reports/baseline-brandtest.md`, which listed all four as
`@BeforeAll` leaks.

### Evidence

Pre-fix, perturbed (`maxParallelForks = 1` + temporary `junit-platform.properties`
with `ClassOrderer$DisplayName`, probe class sorting last), log `20260801-161120`,
2 of 78 failed — 15 assertion failures, verbatim extract:

```
org.opentest4j.AssertionFailedError: test-owned model 'zenit_test:batch_author' leaked into Registries.MODELS ==> expected: <false> but was: <true>
... (batch_book, mp_article, secret_doc, bucket_backend_event, bucket_event, bucket_performance_event, source_person, source_titleless, source_article — 10 failures)
org.opentest4j.AssertionFailedError: test-owned endpoint 'zenittest:model_param_article' leaked into Registries.ENDPOINTS ==> expected: <false> but was: <true>
... (model_param_deferred_article, sm_localized_opt_in, sm_not_opted_in, provider sm_collect_provider — 5 failures)
```

Post-fix under the SAME perturbation: log `20260801-162853` — 79 unit tests passed
(all 9 classes including all 8 backends of `RecordSourceBucketsBackendTest`).
Perturbation reverted (`git diff build.gradle` empty, temp resources dir deleted),
final clean run: log `20260801-163049` — PASSED, 79 unit passed. Protoblast
`RegistrySimpleTest` 14/14 (log `20260801-161623`).

### Scope actually covered

Beyond the five briefed classes, the same-shape leaks the earlier sweep MISSED were
fixed too: `RecordSourceBucketsTest`, `RecordSourceBucketsPerformanceTest`,
`RecordSourceTest` (per-invocation). `SitemapsTest` got try/finally + `@AfterAll`.
A permanent guard was added:
`src/test/java/be/elevenways/zenit/registry/ZenitTestRegistryHygieneTest.java`
(leak probe + an order-independent self-test of the seam) — so this class of
regression is now ENFORCED, which was the whole point of the item.

### Surfaced, not fixed (needs an owner decision)

1. ~40 other public test models (`RecordSourceEndpointsTest.ArticleModel`,
   `RelationTest`, `MigrationFoundationTest`, `LocalizedFieldTest`, ...) stay
   autoloaded JVM-wide, several with dead no-op `registerInstance` calls. Two public
   test models ever sharing a model id would silently first-win **at class-load**.
   The real question is whether test source sets should feed model discovery at all.
2. Many test classes register endpoints via `static final` fields that live for the
   fork; `Endpoint.Builder.validate()` at least makes an id collision loud.
3. `Models.clearAll()` is unused anywhere — deletion candidate once someone confirms
   nothing external calls it.

---

## Item 1 — two disagreeing spellings of route identity — DONE (hohenheim `4a4c745`)

Commit verified by me (3 lines, gitmoji first, subject 53 chars, subject/body on
separate lines; two files, worktree otherwise clean).

### Verdict: the two keys should AGREE, and `RouteClaims.keyOf` is the correct one

`RouteClaims` is the WRITE-TIME AUTHORITY — a transactionally serialized scan plus
the M045 UNIQUE index on `SiteDomainModel.LIVE_ROUTE_KEY`. `SiteDispatcher`'s
`claimKey` is a RUNTIME backstop that logs `DUPLICATE route ... IGNORING` and skips
the loser. They disagreed: the dispatcher prefixed the key with the match `kind`,
`RouteClaims` deliberately omits match type.

Match type is not part of route IDENTITY. The tier order (exact, then wildcard, then
regex; longest path within a tier) decides who WINS a contested route, not whether
two rows contest it. The tiered carve-out semantics were explicitly NOT touched: an
exact host inside a covering wildcard (`app.example.com` + `*.example.com`) spells
two DIFFERENT canonical hostnames, so neither key ever conflated them — that is
correct nginx/Caddy behaviour and it still works (the new test pins it).

The colliding case is rows whose canonical hostname, path and listener set are
IDENTICAL but whose match type differs — chiefly an exact row and a match_type=
wildcard row carrying the same metachar-free literal. `WildcardHostname.compile` of
a literal matches exactly that one host, the exact tier wins, so they ARE one
contested route.

Independent confirmations the agent produced:

- `RouteEntry.path` is already `normalizeRoutePath(PATH)` (SiteDispatcher.java:265)
  and `RouteEntry.listenOnAddresses` is already
  `ListenerAddressMatcher.parse(LISTEN_ON)` (:275) — exactly what
  `RouteClaims.keyOf(Row)` recomputes. So dropping `kind` was the ONLY semantic
  change; the `|`-vs-`\n` and `List.toString()`-vs-`String.join(",")` divergences
  vanish with it.
- `RouteOwnershipInvariantTest.wildcardShadowingRestoreAndFailedWritesKeepRouteStorageExact`
  step 2 ALREADY asserts that a wildcard literal shadowing an exact row is refused at
  write time. The runtime backstop was the only component that disagreed with the
  rest of the system.
- The predicted failure mode was reproduced empirically, not just argued: pre-fix, an
  unclaimed legacy exact row **silently took the host** from the wildcard site that
  actually held the claim (exact tier wins), with NO duplicate log at all.

### Recorded pre-fix failure (verbatim, step 4 of the new journey)

```
✗ exactAndWildcardSpellingsOfOneHostnameAreOneContestedRoute 2889ms
  java.lang.AssertionError: [step 4: the contested literal hostname is served by the first claimant, not silently taken over by the unclaimed exact row]
  Expecting actual:
  "HTTP/1.1 200 OK ... owned-by-exact-site
✗ RESULT: FAILED — 1 of 1 browser failed
```

### The change

`SiteDispatcher.claimKey` is now `RouteClaims.keyOf(domain)` — ONE definition of
route identity. The `kind` computation stays (it still drives the tier switch). The
regex case-sensitivity lesson from the old comment ("canonical hostname, NOT a
blanket lowercase") is preserved and folded into a new AIDEV-NOTE that names
`RouteClaims` as THE definition and records WHY match type is absent, so it is not
re-added. No AIDEV-* comment was deleted. The same-site branch
(`owner != null && !owner.equals(siteName)`) is unchanged.

### The test

`SiteDispatcherTest.exactAndWildcardSpellingsOfOneHostnameAreOneContestedRoute`, a
6-step behaviour journey. It plants the LEGACY shape the backstop exists for — a
live exact row on the contested hostname with `LIVE_ROUTE_KEY = null`, i.e. precisely
the `RouteClaims.backfill` loser shape — via a hook-bypassing set-based update (the
`restamp` idiom). It asserts one contested route (marker upstream bodies name who
answered) AND that a genuine carve-out (`app.carve.test` exact inside `*.carve.test`)
still routes per tier, protecting the nginx semantics from a future over-correction.

The saved log confirms the backstop actually fired:
`SiteDispatcher: DUPLICATE route shadow.test (all paths) on site bbb-shadow-exact -- already claimed by site aaa-shadow-wildcard ; IGNORING`
and `loaded 1 exact routes, 2 wildcard routes`.

Post-fix: `✓ exactAndWildcardSpellingsOfOneHostnameAreOneContestedRoute 2811ms`.
Full affected classes re-run: `SiteDispatcherTest` (7) + `RouteOwnershipInvariantTest`
(5) = 12/12 browser tests passed, exit 0 (journal `20260801-163633`, 14:45:41).

### Friction (external, resolved by waiting)

The concurrent guard-wiring agent twice planted `PlantedProbe.java` fixtures
(zenit-microcopy, then hawkeye) that failed `checkLocaleFolds` in the dependency
chain, and once replaced the protoblast m2 jar mid-run, failing zenit's own
`zenitDevTest`. None related to this change; each was waited out and no guard file
was touched.

---

# Summary

All six items closed. Seven commits across six repos:

| # | Item | Repo(s) | Commit(s) |
| --- | --- | --- | --- |
| 1 | Route identity unified on `RouteClaims.keyOf` | hohenheim | `4a4c745` |
| 2 | orcono `byId` guard; spamservice dead `byToken` guards | orcono, spamservice | `56b1ada`, `8e5f1cd` |
| 3 | Test isolation: `Registry.remove` + `Models.unregisterInstance` | protoblast, zenit | `19a4d95`, `5ca32d5` |
| 4 | Two async flakes closed with real signals | zenit | `b5b7761` |
| 5 | thoth generated bundle untracked | thoth | `2ee1ee4` |
| 6 | alchemy/Hawkejs scope note | hawkeye (+ memory) | `40534aa` |

Every behaviour fix (1-4) carries a recorded pre-fix failure run against unmodified
code, with the verbatim output above, and a post-fix pass. Items 3 and 4 additionally
made their flake/leak DETERMINISTIC first (injected delays; forced class order),
because "it passed" proves nothing about a race.

## Three claims in the source reports that turned out to be WRONG

1. **`reports/baseline-brandtest.md` misclassified 4 of the 5 test-isolation leaks.**
   Their `@BeforeAll registerInstance` calls were no-ops: public no-arg test models
   are auto-registered at JVM boot by the generated test-source `BlastAutoLoadInit`.
   The leak was compile-time model DISCOVERY, not test code.
2. **`reports/f6-gate-diff.md`'s mechanism claim for spamservice was imprecise.**
   `Identifier.trySplitOn("spamservice:client", '.')` does not return null; it
   returns `elevenways:spamservice:client`, which simply never matches
   `spamservice.client`. Guards dead as reported, one step later than described.
3. **The item-6 coordinate was wrong.** The `"" + value` is in
   `view_inline/boolean.hwk:18`, not `edit/boolean.hwk:18` (that file is 4 lines).

## Known limitations / still open

- Item 2's runs all used `--skip-deps`, because a full chain build currently fails on
  the concurrent guard-wiring agent's planted `PlantedProbe.java` fixture in
  zenit-microcopy. Worth re-confirming those two repos build clean once that agent's
  work settles.
- Item 4 deliberately left the exactly-once teardown ABSENCE assertions on
  `Thread.sleep` windows: they can only false-pass, never false-fail, and
  `WebSocketTeardown`'s CAS refusal is silent by design, so no signal exists without
  a production change made purely for testability. That is the "needs a design
  change" answer for that sub-item.
- Item 4 also found one theoretical false-FAIL (revalidator counter-freeze checks)
  that could not be reproduced deterministically and was therefore NOT changed, per
  the evidence standard. Fix shape recorded for if it ever fires.
- Item 3 surfaced ~40 other public test models still autoloaded JVM-wide. The real
  question — should test source sets feed model discovery at all? — is an owner
  decision, not something to settle unilaterally.
- Item 3 also notes `Models.clearAll()` is now unused anywhere; deletion candidate.
- Item 2 surfaced two more registry-probe idempotency sites left in place:
  orcono `EntityCandidateSource.register()` (benign, custom id) and zenit-ai
  `AiRecordSources.register()` (a working dot-form probe where the ecosystem answer
  is a once-flag).
- Item 5: if thoth is ever deployed as a bare fat jar with no build step and no
  `public/` beside it, the JS bundle would need jar packaging (the Hawkeye plugin
  does this for CSS but not JS). True of every sibling app; not a new regression.
</content>
