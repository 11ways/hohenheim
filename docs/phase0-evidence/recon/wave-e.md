# Wave E Reconnaissance Report (HEAD, 2026-07-31)

Repos: `/home/skerit/projects/javaweb` (multi-module) and `/home/skerit/projects/hohenext/hohenheim`.

---

## E1 — Locale prefixes bypass AuthRegistry baselines/public prefixes — **REAL**

**Evidence**
- `/home/skerit/projects/javaweb/zenit-auth/src/server/java/be/elevenways/zenit/auth/server/AuthorizationMiddleware.java:30` — `String path = conduit.getPath();` then used for `AuthRegistry.isPublicPath(path)` (:32, :36), the `/public/` check (:32) and `AuthRegistry.baselinesForPath(path)` (:50).
- `/home/skerit/projects/javaweb/zenit/src/server/java/be/elevenways/zenit/server/http/HttpConduit.java:387` — `getPath()` returns `this.uri.getPathname()` (raw, prefixed).
- `HttpConduit.java:403` — `getRoutePath()` returns `this.routeUri.getPathname()`; `applyRouteLocale()` at `:479-500` sets `this.routeUri = new Uri(match.pathname())` only when `match.prefixed()`, with the in-file note "*Only this.routeUri is stripped; this.uri (getPath, assets, logging) keeps the original prefixed path*" (:483-485).
- `AuthRegistry.java:87-92` — `matchesPrefix` is a literal `path.equals(prefix) || path.startsWith(prefix + "/")`, so `/nl/api/private` matches neither `/api` baselines nor `/api` public prefixes.
- Contrast with the already-fixed consumer: `ScopedCspMiddleware.java:127` `String path = conduit.getRoutePath();` with the AIDEV-NOTE at `:113-125` documenting exactly this defect class.

**Mechanism to reuse**
- `HttpConduit.getRoutePath()` exists but is **not on the `Conduit` interface** (only 3 call sites repo-wide: `ScopedCspMiddleware.java:127`, `ResponseCache.java:224`, the definition). `Middleware.Handler.handle(String middlePath, HttpConduit conduit)` (`zenit/.../http/Middleware.java:87`) already hands a concrete `HttpConduit`, and zenit-auth's registration lambda is `ZenitAuth.java:253-255` → so the fix can pass the route path down without touching the `Conduit` interface. Alternative seam: add a `ConduitAttributes` key next to `ROUTE_LOCALE` (`ConduitAttributes.java:26`).
- Do **not** move asset/log paths (G3 constraint) — `Middleware.handle` derives `middlePath` from `getPath()` at `Middleware.java:80`; leave that alone.

**Tests**
- `zenit-auth/src/test/java/be/elevenways/zenit/auth/AuthRegistryTest.java` — unit-level prefix/baseline semantics (`publicPrefixMatchesNestedPaths`, `rootBaselineIsCatchAll`, `globStarPrefixNormalizesToCatchAll`, `baselineCollectsAllMatchingPrefixes`). No locale coverage.
- Multi-locale real-HTTP fixture pattern (G3): `zenit/src/test/java/be/elevenways/zenit/server/http/LocaleRoutingHttpTest.java` — static `Endpoint` fields, `RouteLocales` config, `java.net.http.HttpClient` against a real `ZenitHttpServer`. Also `ScopedCspHttpTest.localePrefixedPathsStayInsideTheClaimedScope` (`zenit/src/test/.../ScopedCspHttpTest.java:187`) is the exact assertion shape to mirror for baselines.
- Core middleware unit fixture: `zenit/src/test/java/be/elevenways/zenit/security/AuthorizationMiddlewareTest.java` (`TestConduit` hand-rolled `Conduit`, `FakePrincipal`/`InteractivePrincipal`).

---

## E2 — Callback-lane overflow discards WebSocket teardown — **REAL**

**Evidence**
- `zenit/src/server/java/be/elevenways/zenit/server/http/ZenitHttpServer.java:601-607` — the overflow callback is `() -> closeConnection(teardownRef, channel, TRY_AGAIN_LATER, "WebSocket callback queue overloaded")`; `closeConnection` (`:835-840`) calls `teardown.closeWith(code, reason)`.
- `WebSocketTeardown.java:121-135` — `release()` does `released.compareAndSet(false,true)` **first**, then `this.lane.execute(...)` where `lane` is the very `SerialExecutor` that just overflowed.
- `ZenitHttpServer.java:246-249` — `SerialExecutor.execute` begins `synchronized (queue) { if (overflowed) { return; } ... }`, and `overflowed` was set to `true` at `:252-258` *before* `onOverflow.run()` at `:275`. So `handler.onClose` is silently dropped; the later `channel.addCloseTask(closed -> teardown.transportClosed())` (`:615`) hits the already-consumed CAS and returns false. **onClose never runs on the overflow path.**

**Mechanism to reuse**
- `WebSocketTeardown` already owns a non-rejectable lane for its own scheduling: `private static final JobRunner RUNNER = JobRunner.create("zenit-ws-teardown")` (`WebSocketTeardown.java:41`) used for the close-handshake grace abort (`:100-105`). This is the natural backstop executor.
- Ordering constraint to preserve: normal closes must stay behind queued callbacks on the serial lane (`ZenitHttpServer.java:208-213` doc comment: "Runs queued tasks strictly in submission order").
- Note the pause/resume + `drain()` bookkeeping (`ZenitHttpServer.java:284-320`) skips accounting when `overflowed` — any "accept teardown even when overflowed" change must not corrupt `unfinishedTasks`/`unfinishedBytes`.

**Tests**
- **Overflow already has a real-socket harness**: `zenit/src/test/java/be/elevenways/zenit/server/http/WebSocketTransportLimitsHttpTest.java:202` `stalledHandlerCannotOverflowTheCallbackLaneByBytes` — `configure(64,64,8,8)`, a blocking handler with `STALL_STARTED`/`STALL_RELEASE` latches, `assertClose(client, 1013)`. Extend it with an onClose counter + retained-resource release assertion.
- Teardown exactness fixtures (**G9 real-socket tests — do not discard**): `zenit/src/test/java/be/elevenways/zenit/server/http/WebSocketRevalidationHttpTest.java`
  - `:490` `aReceiveErrorStopsTheRevalidatorAndRunsHandlerCleanup` (raw `java.net.Socket`, hand-written handshake, unmasked frame → protocol error, asserts `error:` then `close:1011`, then "exactly once" re-check after `INTERVAL_MS*4`).
  - `:562` `aTransportDropWithoutACloseFrameStillRunsHandlerCleanupExactlyOnce` → `assertAbruptDropRunsCleanupExactlyOnce(false/true)` — FIN and RST (`socket.setSoLinger(true, 0)`).
  - `:633` `aServerInitiatedCloseTheClientNeverAcknowledgesStillRunsCleanup` — wedged peer, reads opcode 0x8.
  - Helpers to reuse: `readHttpHead(InputStream)`, `awaitAtLeast(AtomicInteger, int)`, `closeEvents(List<String>)`, per-endpoint event lists (`RELEASE_EVENTS`, `ABRUPT_EVENTS`, `WEDGED_EVENTS`).

---

## E3 — In-band dev-tunnel auth never revalidates — **REAL**

**Evidence**
- `hohenheim/src/common/java/be/elevenways/hohenheim/HohenheimEndpoints.java:316-321` — `DEV_TUNNEL` declares **no** `requiresLogin()`, **no** `requiresPermission`, **no** `revalidateEvery(...)`; only `.handler(session -> null)` placeholder.
- `zenit/src/server/java/be/elevenways/zenit/server/http/WebSocketRevalidator.java:73-86` `intervalFor`: declared interval is `REVALIDATION_INTERVAL_DEFAULT` (-1), so it falls to `identityBound = endpoint.requiresAuthorization() || (principal != null && !principal.isAnonymous())` → **false** for the anonymous handshake → `return 0` → `ZenitHttpServer.java:628-631` creates **no revalidator**.
- Authentication happens in-band afterwards: `hohenheim/src/server/.../devtunnel/DevTunnelServerHandler.java:160-241` `handleRegister` (token compared at `:252-268` `findNamespaceSite`, constant-time). The handler has only an auth *timeout* (`onOpen` at `:78-98`: `AUTH_TIMEOUT_MS` one-shot + a 30s idle sweep) — **no periodic re-authorization**.
- Contrast, the same file shows the endpoint that does it right: `HohenheimEndpoints.java:305-313` `PROCESS_TERMINAL` uses `.requiresLogin().revalidateEvery(TERMINAL_REVALIDATION_INTERVAL_MS)`.

**Mechanism to reuse**
- `WebSocketRevalidator.start(endpoint, session, handler, intervalMs)` + `WebSocketTeardown.attachRevalidator` (`ZenitHttpServer.java:628-632`) and `WebSocketRevalidator.invalidate()` (`:102-113`, sets `session.setAuthorizationValid(false)` and closes 1008). The handler-declared hook already exists (`WebSocketHandler` revalidate hook, exercised by `/ws-reval-hooked` in the revalidation test) — a "handler-declared revalidation seam" would extend `intervalFor` to consult the *handler*, not just the endpoint/principal.
- Consumer count for the "generic seam vs thin wiring" decision: `DEV_TUNNEL` is currently the **only** in-band-authenticated WS endpoint in either repo (`hohenheim` grep for `DEV_TUNNEL`: `HohenheimHandlers.java:935`, `DevTunnelTest.java:94`, `HohenheimEndpoints.java:316`).

**Tests**
- `hohenheim/src/browserTest/java/be/elevenways/hohenheim/test/DevTunnelTest.java` (wires `HohenheimEndpoints.DEV_TUNNEL.setHandlerFactory(DevTunnelServerHandler::new)` at `:94`, mirroring the one line of `HohenheimHandlers.init`).
- Revalidation-semantics fixtures: `WebSocketRevalidationHttpTest` (`STUB` `WebSocketAuthenticator` with a mutable `sessionAlive`/`wedgedAllowed` flag, `RecordingClient implements WebSocket.Listener`, `INTERVAL_MS` short cadence). Also `zenit-auth/src/test/java/be/elevenways/zenit/auth/server/WebSocketAuthIntegrationTest.java`.

---

## E4 — Contextual route parameters execute before authorization — **REAL**

**Evidence**
- `zenit/src/common/java/be/elevenways/zenit/common/routing/ParameterDefinition.java:185-189` — `parse(value, context)` calls `this.context_resolver.apply(value, context)` first; invoked from `EndpointRoute.java:344` (`var parsed = definition.parse(value, context);`) **inside** match.
- `zenit/src/common/java/be/elevenways/zenit/common/routing/ModelParam.java:70` wires `contextResolver(... loadRow ...)`; `loadRow` (`:80-118`) runs `model.find().where(field.eq(typed)).first()` and, for localized fields, **one query per chain locale**. Class javadoc (:21-23) states the row "auto-loads during route matching".
- HTTP order: `HttpConduit.parseRequest()` (`:422-470`) → `AdmissionRateLimiter.check` → `this.resolveEndpoint()` (`:437`) → **then** the middleware chain (`:456-467`) where session/auth resolution lives. So DB queries precede authentication (admission does precede matching here).
- WebSocket order is worse: `ZenitHttpServer.java:426-451` matching (`wsEndpoint.getParameterMatches(uri)`, i.e. `RouteMatchContext.none()` via `EndpointRoute.java:181-183`) runs **before** the method check (:462), Origin (:499), admission (:519) and the authenticator (:536). The in-file AIDEV-NOTE at `:428-439` already documents this precisely and says "No WS route declares one today".

**Mechanism**
- Split point is `ParameterDefinition.parse` vs a new structural-only path; `EndpointRoute.MatchResult`/`getParameters()` (`HttpConduit.java:566-570`) is where resolved values land. `ConduitAttributes.RESOLVED_ROUTE_IS_CATCH_ALL` (`HttpConduit.java:440-452`) shows the existing pattern of stamping match metadata.
- 404-semantics constraint: today a null resolve makes the route **not match** (ModelParam javadoc `:75-77`), which is what avoids the existence oracle.

**Tests**
- `zenit/src/test/java/be/elevenways/zenit/routing/ParameterDefinitionTest.java:103` (a `contextResolver` fixture), `RoutingTieBreakHttpTest`, `MatchGuardHttpTest`, `LocaleRoutingHttpTest`, `zenit/src/test/java/be/elevenways/zenit/routing/LocaleRouteVariantTest.java`.

---

## E5 — Bad-Origin WS requests bypass admission while doing synchronous diagnostics — **REAL**

**Evidence**
- `ZenitHttpServer.java:499-513` — Origin refusal does `Blast.log("http.websocket.origin_refused", ...)` **and** `reportWebSocketRefusal(SecurityEventTypes.WS_ORIGIN_REFUSED, ...)` and returns, **before** `WebSocketAdmission.check(...)` at `:519`. The AIDEV-NOTE at `:491-498` explicitly defends this ordering ("Do not move admission back in front of it").
- The diagnostic path is synchronous and unbounded: `reportWebSocketRefusal` (`:780-784`) → `SecurityEvents.report` → `zenit/src/server/java/be/elevenways/zenit/server/security/SecurityEvents.java:59-72`, a straight `for (SecurityEventSink sink : SINKS) sink.accept(event)` on the calling thread. Sinks are installed at boot (`ServerStages.java:71` `SecurityEventSinks.install()`); hohenheim installs a DB/native sink via `HohenheimSecurity.boot()`.

**Mechanism**
- Existing limiter substrate to copy: `WebSocketAdmission.check(endpoint, clientIp)` returning a `Refusal(bucket, retryAfterSeconds)` and `AdmissionRateLimiter` / `RateLimiter` (`zenit/src/test/.../RateLimiterTest.java` covers it). A "cheap independent rejection limiter" fits the same `Refusal` shape.
- Bounded-async sink option belongs in `SecurityEvents` (add before the `SINKS` loop) — it already truncates detail (`sanitizeDetail`, `MAX_DETAIL_ENTRIES` :82-99) and guards throwing sinks with `BlastLog.distinctProblem` (:68).

**Tests**
- `zenit/src/test/java/be/elevenways/zenit/server/http/WebSocketAdmissionHttpTest.java:209` `crossOriginHandshakesDoNotConsumeTheAdmissionBudget` (the invariant that must be preserved), `:261` `wrongMethodUpgradesAreRefusedBeforeAdmission` (the control-probe pattern), `:144` `floodIsRefusedBeforeTheAuthenticationQuery`, `:346` remote-IP/trusted-proxy walk.
- `zenit/src/test/java/be/elevenways/zenit/server/http/WebSocketAuthHttpTest.java:233` `originPolicyGuardsTheUpgrade`, `:392` `refusedUpgradesReportSecurityEvents` (the security-event assertion seam).

---

## E6 — `claimingRoutesOf` incomplete and overbroad — **REAL (both halves)**

**Evidence**
- `zenit/src/server/java/be/elevenways/zenit/server/http/ScopedCspMiddleware.java:181-198` — iterates `endpoint.getRoutes()` only. `Endpoint.java:294-296` `getRoutes()` returns the **base** route set; the locale variants live in a separate map, `Endpoint.java:301-303` `getLocalizedRoutes()` (and `Endpoint.java:318-329` `getRoutes(Locale)` is the request-time accessor). So `/nl/aanmelden`-style variants are never claimed.
- `ScopedCspMiddleware.java:205-224` `claimsRoute` builds the **leading static prefix** and returns `path.equals(claim) || path.startsWith(claim + "/")` — a full subtree claim. A `/login` endpoint therefore claims any host route under `/login/*`.
- Only consumer in production: `zenit-auth/.../ZenitAuth.java:268-270` (`AuthEndpoints.NAMESPACE`).

**Mechanism**
- Reuse the real matcher: `EndpointRoute.getParameterMatches(Uri, RouteMatchContext)` (`EndpointRoute.java:181-189`) — the predicate receives a claim path (route path minus leading `/`, `ScopedCspMiddleware.claimPathOf` :126-129), so it can be turned into a `Uri` and matched exactly. Locale coverage via `getLocalizedRoutes()` / `getRoutes(locale)`.
- Diagnostics that must stay loud: `reportOverlap` + the `LOG.warning("Two scoped-CSP wirings claim the same responses...")` at `:147-155`, and the tiering `WEIGHT` / `VARIANT_WEIGHT = WEIGHT + 1` (`:46`).

**Tests**
- `zenit/src/test/java/be/elevenways/zenit/server/http/ScopedCspHttpTest.java` — `claimedPathCarriesTheScopedPolicy` (:151), `claimedPathOverridesTheGlobalCsp` (:159), `unclaimedPathGetsNoScopedPolicy` (:172), `blankPolicyStampsNothing` (:179), `localePrefixedPathsStayInsideTheClaimedScope` (:187), `overlappingClaimsAreReportedLoudly` (:230), `terminalConcessionsRideOnlyTheTerminalRoute` (:273).
- `zenit-auth/src/test/java/be/elevenways/zenit/auth/server/cms/AuthCmsResourcesIntegrationTest.java:167-170` asserts `claimingRoutesOf("zenitauth").test("admin/users")` is false and `.test("login")` is true — the regression anchor.

---

## E7 — Terminal CSP predicate broader than the real subpage — **REAL**

**Evidence**
- `hohenheim/src/server/java/be/elevenways/hohenheim/server/cms/SiteTerminalCsp.java:62-68`:
  ```java
  if (!path.endsWith("/page/" + SiteProcessesPage.SLUG)) return false;
  String slug = ScopedCspMiddleware.firstSegment(path);
  return !slug.isEmpty() && PanelRegistry.getBySlug(slug) != null;
  ```
  Purely suffix + first-segment-is-a-registered-panel. `/admin/anything/at/all/page/processes` (including 404s and any future resource) gets `STRICT_ADMIN_TERMINAL`.
- The policy widening itself is `SiteTerminalCsp.policy()` (:51-59), only applied when `CmsSettings.CSP` still equals `ContentSecurityPolicies.STRICT_ADMIN`.
- Route slug source: `hohenheim/src/server/.../cms/SiteProcessesPage.java:36` "*The route slug, shared with the terminal's scoped-CSP claim.*"

**Mechanism**
- Bind to the resolved endpoint/peer instead: `ConduitAttributes.RESOLVED_ENDPOINT` (`ConduitAttributes.java:19`) is stamped before the middleware chain (`HttpConduit.java:438`), and `RESOLVED_ROUTE_IS_CATCH_ALL` (:22) exists as prior art for match-derived attributes. Alternatively resolve through `PanelRegistry` to the registered `RecordScopedPage`/subpage rather than string shape.
- Install seam unchanged: `ScopedCspMiddleware.installVariant(ID, policy, claims)` at `SiteTerminalCsp.java:45-48`, weight `VARIANT_WEIGHT` (`ScopedCspMiddleware.java:46`) — must stay a variant so the overlap diagnostic doesn't fire.

**Tests**
- `hohenheim/src/browserTest/java/be/elevenways/hohenheim/test/TerminalCspClaimTest.java:22` `terminalConcessionsRideOnlyTheProcessesSubpage` — 4 numbered steps over the real app (`/admin/sites` strict, `/admin/sites/1/page/processes` terminal, `/admin/sites/1/page/domains` strict, non-panel path unclaimed), via `HohenheimTestBase` + `HttpClient` with `Redirect.NEVER`, asserting the `Content-Security-Policy` header on anonymous requests. Add the "unregistered resource / 404 ending in `/page/processes`" case here.
- Plumage side: `plumage/src/browserTest/java/be/elevenways/plumage/test/TerminalCspTest.java`.

---

## E8 — Non-positive global revalidation interval silently opts out — **REAL**

**Evidence**
- `zenit/src/server/java/be/elevenways/zenit/server/http/WebSocketRevalidator.java:83-85` — `return Math.max(0L, configured == null ? 30_000L : configured);` — an operator setting `0` or `-1` in `network.websocket_revalidation_interval_ms` collapses to 0.
- `ZenitHttpServer.java:628-631` — `revalidationIntervalMs > 0 ? WebSocketRevalidator.start(...) : null` → silent global opt-out.
- The endpoint override is already strict: `WebSocketEndpoint.java:210-216` `revalidateEvery` throws `IllegalArgumentException("Revalidation interval must be positive")`; the explicit opt-out is `neverRevalidate()` (:224-227, sets 0). Also `intervalFor` line `:75-77` does `Math.max(0L, declared)` on the declared value — harmless today because the builder validates, but it is the same silent-clamp shape.
- Setting definition has **no** validation: `zenit/src/server/java/be/elevenways/zenit/server/setting/ServerSettings.java:183-188` — only `defaultValue(30_000L).suffix("ms").description(...)`.

**Mechanism**
- `SettingDefinition.Builder.coercer(Function<Object, CoercionResult<T>>)` (`SettingDefinition.java:357`) is the validation seam; prior art in the same file, `ServerSettings.java:65` uses a rejecting coercer.
- The "declared vs default" sentinel is `WebSocketEndpoint.REVALIDATION_INTERVAL_DEFAULT = -1` (`WebSocketEndpoint.java:33`).

**Tests**
- `WebSocketRevalidationHttpTest.endpointWithoutRevalidateEveryIsUntouched` (:443) and `oneRevalidationTickCostsOneIdentityCheckAndOneCheckPerDeclaredPermission` (:694) — the anonymous-socket and budget cases. Settings-coercer test patterns: `zenit/src/test/java/be/elevenways/zenit/setting/SettingTests.java:273,297` and `SettingsEditorTest.java:61`.

---

## E9 — Ghostty script failure can hang terminal initialization — **REAL**

**Evidence** — `plumage/src/client/java/be/elevenways/plumage/component/BrowserTerminalBridge.java:70-100` (`awaitGhosttyWeb` `@JSBody`):
- `:95-99` — when the tag exists but the global is absent, it sets `__ghosttyWeb = { _loading: true, ... _pendingCallbacks: [onReady] }` and only `existing.addEventListener('load', boot)`. **No `error` listener**, and **no check that the tag already finished** (already-errored or already-loaded-without-global tags never fire `load` again) → `_loading` stays `true` forever and every subsequent call takes the `:74-76` branch, queueing callbacks that never settle.
- `:79-81` — inside `boot`, `if (!lib || !lib.init) { console.error(...); return; }` returns **without** clearing `_loading` or draining `_pendingCallbacks`.
- `:88` — `.catch(function(e) { console.error('[pl-terminal] WASM init failed:', e); })` likewise leaves `_loading === true` and the queue undrained.
- Java-side caller: `initTerminal` (`:132-176`) throws only on the `return false` (tag missing) branch (`:170-176`); every other failure is silent.

**Mechanism**
- The existing loud-failure pattern is the `IllegalStateException` at `:170-175` (and the deliberate "no script-injection fallback" AIDEV-NOTE at `:62-68` — do not reintroduce injection).
- Script emission is declarative: `terminal.hwk` (`plumage/src/common/templates/components/terminal.hwk`) emits `{% script(src: Terminal.scriptSrc(), async: true) %}`; `TerminalBridge.GHOSTTY_SCRIPT_SRC` in `plumage/src/common/java/be/elevenways/plumage/component/TerminalBridge.java`.

**Tests**
- `plumage/src/browserTest/java/be/elevenways/plumage/test/TerminalFallbackTest.java:20` `missingScriptTagFailsLoudlyInsteadOfInjecting` (fixture template `plumage/src/browserTest/templates/test/terminal-no-script-test.hwk`) — the direct extension point for "tag present but broken".
- `plumage/src/browserTest/java/be/elevenways/plumage/test/TerminalTest.java:26` `terminalRendersAndBootsItsCanvas` (happy path).

---

## E10 — Hohenheim starts HTTP before installing handlers and panels — **REAL**

**Production order** — `hohenheim/src/server/java/be/elevenways/hohenheim/server/ServerMain.java:47-145`:
1. `:55-60` early `--run-migrations` exit path.
2. `:64` `SiteTypes.boot()` · `:69` `HohenheimSettingsFiles.load()`
3. `:71` `HohenheimEndpoints.init()` — **this is where `PROCESS_TERMINAL` and `DEV_TUNNEL` come into existence with `.handler(session -> null)` placeholders** (`HohenheimEndpoints.java:312`, `:320`).
4. `:73` force-load `ResourcePageEndpoints.LIST` · `:74` `HohenheimDatabase.init()`
5. `:78` `ZenitAuth.init(...)` · `:81` disable CMS_AUTO_PANEL · `:82` `installAuthBaselines()` · `:83` `registerProteusIfConfigured()`
6. `:88-89` `WorkloadIdentity.applyLegacyDefault` / `installSettingsGate`
7. **`:92` `ServerZenitRuntime.main(args)`** → `ServerZenitRuntime.java:72-74` `init().join()` → `Zenit.ROOT_STAGE.launch()` (`:163`) → `ServerStages` boot stages: `ROUTES_MIDDLEWARE` (`ServerStages.java:69-82`, incl. `CsrfMiddleware.logExemptEndpoints()`) then **`STARTHTTP` (`ServerStages.java:84-89`, weight 50) → `ServerZenitRuntime.startHttp()` (`:196-217`) binds the listener and calls `DevTunnelBoot.startIfConfigured`**. HTTP is **accepting requests when `main()` returns**.
8. `:93` `setClientScriptLocation` · **`:98` `HohenheimHandlers.init()`** (which at `HohenheimHandlers.java:935` finally does `HohenheimEndpoints.DEV_TUNNEL.setHandlerFactory(DevTunnelServerHandler::new)`)
9. `:104` interrupted-deploy sweep · `:105` shutdown hook · `:108` `SpamserviceManager.get().boot()` · **`:109-110` `new HohenheimPanel(); new ManagePanel();`**
10. `:114` `HohenheimSecurity.boot()` · `:116-118` proxy · `:126-138` DNS/zones.

So the window between step 7 and steps 8-9 exposes: WS endpoints whose handler factory returns `null` (→ `ZenitHttpServer.java:574-576` `throw new IllegalStateException("WebSocket handler factory returned null")`), unhandled HTTP endpoints, and panels/resources not yet registered.

**Test wiring (the safe opposite order)** — `hohenheim/src/browserTest/java/be/elevenways/hohenheim/test/HohenheimTestBase.java:54-118` `startServer()`:
- settings temp file → `HohenheimSettingsFiles.load()` (:73) → `SiteTypes.boot()` (:75) → `HohenheimEndpoints.init()` (:76) → `ResourcePageEndpoints.LIST` (:78) → `TestDatabases.freshDatabase()` (:82) → **`HohenheimTestRuntime.ensureBooted()` (:89) with HTTP auto-start disabled** ("*HTTP auto-start is disabled inside ensureBooted() since this base binds its own server below*") → `ZenitAuth.init` (:93) → `installAuthBaselines()` (:97) → **`HohenheimHandlers.init()` (:98)** → **`new HohenheimPanel(); new ManagePanel();` (:99-100)** → `HohenheimSecurity.boot()` (:105) → seed admin (:107) → disable rate limits (:112) → **only then `ServerZenitRuntime.createServer(0); zenitServer.start();` (:114-116)**.
- The auto-start gate is `ServerSettings.Network.AUTO_START_HTTP`, read in `ServerStages.java:85-88`.
- `DevTunnelTest.java:93-94` repeats just the DEV_TUNNEL handler-factory line ("*The targeted wiring HohenheimHandlers.init() would do for this endpoint*") — i.e. a second place that compensates for the split.

**Required-outcome mechanism**: move host wiring into a `ZenitModule` running at the **MODULES** stage (the pattern `ServerMain.java:119-123` already documents for `HohenheimWriteHooks`), or simply hoist `HohenheimHandlers.init()` + panel construction above `ServerZenitRuntime.main(args)`. Watch the ordering constraint noted at `ServerMain.java:96-97`: "*the panel's resources resolve model singletons from the MODELS stage*" — panels need MODELS done, so MODULES/`beforeHttp` staging (not raw pre-`main()` placement) is the safe seam.

---

## E11 — `NON_INTERACTIVE_ONLY` also skips the Origin check (owner decision)

**Code** — `zenit/src/server/java/be/elevenways/zenit/server/http/CsrfMiddleware.java:61-77`: `check()` returns `null` at `:68-70` (`if (isExempt(conduit)) return null;`) **before** `isCrossOrigin(conduit)` at `:72`. `isExempt` (`:133-155`): `PROTOCOL_COOKIE` → unconditional true; otherwise `principal == null || !principal.isInteractive()` (`Principal.isInteractive()` defaults false — `Principal.java:29`). Origin logic itself: `isCrossOrigin` (:184-199), rejects only a definite cross-origin. Boot-time enumeration already exists: `CsrfMiddleware.logExemptEndpoints()` (:157-181, `http.csrf.exempt_endpoint` slog), called from `ServerStages.java:79`.

**Concrete current consumers of the NON_INTERACTIVE_ONLY exemption** (plain `.csrfExempt()`; the two `PROTOCOL_COOKIE` ones are listed separately and are *not* affected):

| Module | Endpoint id(s) | File:line | Credential |
|---|---|---|---|
| spamservice | `api_check`, `api_iplookup`, `api_events`, `api_reputation`, `api_ensure_client`, `health_live`, `health_ready`, `legacy_check`, `legacy_iplookup` | `spamservice/src/server/java/be/elevenways/spamservice/server/api/ApiEndpoints.java:82-149` | API key / public health |
| spamservice | `manage_sample` + all `/v1/manage/...` builders (8 factories) | `.../api/ManageEndpoints.java:55-123` | management API key |
| thoth | `Identifier.of("thoth", id)` proxy `/v1/...` factory | `thoth/src/server/java/be/elevenways/thoth/server/proxy/ProxyEndpoints.java:60-67` | bearer |
| zenit-a2ui | `zenita2ui/action` (`/zn/a2ui/.../action`) | `zenit-a2ui/src/server/java/be/elevenways/zenit/a2ui/server/A2uiEndpoints.java:53-65` | A2UI client (comment :21) |
| zenit-comms | `zenit-comms/hub_status_event` | `.../hub/HubStatusReceiver.java:35-41` | hub credential |
| zenit-comms | `zenit-comms/hub_send`, `zenit-comms/hub_status` | `.../hub/HubEndpoints.java:46-62` | hub credential |
| zenit-oidc | `post_token`, `options_token`, `post_userinfo`, `options_userinfo`, `post_par` | `zenit-oidc/src/common/java/be/elevenways/zenit/oidc/OidcEndpoints.java:79-111` | client_secret / bearer (**legit cross-origin API clients live here — CORS preflight OPTIONS included**) |
| zenit-microcopy | `zenit-microcopy/sync_batch` | `.../MicrocopySyncApi.java:46-53` | sync token |
| proteus | `proteus/realm_api` | `proteus/src/common/java/be/elevenways/proteus/common/ProteusApiEndpoints.java:30-41` | realm API key |
| zenit-ai | `mcp_message`, `mcp_stream`, `mcp_terminate`, `mcp_upload`, `mcp_download` (`/zn/mcp/...`) | `.../mcp/host/McpHostEndpoints.java:41-80` | MCP session/bearer |
| hohenheim | `api_sites_deploy` | `hohenheim/src/common/java/be/elevenways/hohenheim/HohenheimEndpoints.java:216-226` | `znit_` API key (comment: "*the handler refuses non-API-key principals*") |
| hohenheim | `api_dns_record_create`, `api_dns_record_update`, `api_dns_record_delete` | `HohenheimEndpoints.java:239-270` | `znit_` API key |
| hohenheim | `dyndns_update` (`GET /nic/update`) | `HohenheimEndpoints.java:283-291` | HTTP Basic token; **GET, so `check()` returns at `:64-66` anyway** |

**Explicit `PROTOCOL_COOKIE` (unaffected, listed for contrast):** `zenit-oidc` `post_authorize` (`OidcEndpoints.java:60-62`) and `post_end_session` (`:124-126`).

**Notes for the owner decision**
- The riskiest "cookie-bearing mistake" surface is `zenita2ui/action` (the escalation the AIDEV-NOTE at `CsrfMiddleware.java:146-152` was written for) and the hohenheim `/api/...` routes, which also carry `requiresPermission(hohenheim.admin.access)` — an ambient admin session cookie is exactly the shape Origin would catch.
- The strongest argument *against* adding Origin: `zenit-oidc` token/userinfo/PAR + their `OPTIONS` twins are designed for cross-origin browser-based OIDC clients.
- Existing declaration-invariant tests to extend either way: `zenit/src/test/java/be/elevenways/zenit/security/CsrfMiddlewareTest.java:282-286`, `zenit-a2ui/src/test/java/be/elevenways/zenit/a2ui/A2uiEndpointDeclarationTest.java:31-35`, `zenit-oidc/src/test/java/be/elevenways/zenit/oidc/OidcEndpointDeclarationTest.java:41-47`.

---

### Cross-cutting fixture notes
- Real-HTTP zenit tests all live in `zenit/src/test/java/be/elevenways/zenit/server/http/` and follow: static `Endpoint`/`WebSocketEndpoint` fields (`@SuppressWarnings("unused")`, registered at class-load into `Registries`), `@BeforeAll startServer()` binding `ZenitHttpServer` on port 0, `java.net.http.HttpClient` / `WebSocket`, `@AfterAll stopServer()`.
- Raw-socket WebSocket work uses `java.net.Socket` + a hand-written `GET ... Upgrade: websocket` head and `readHttpHead` (`WebSocketRevalidationHttpTest`); frames are written as raw bytes (`{0x81, len, ...}`).
- Hohenheim browser/app-level tests extend `HohenheimTestBase` (`src/browserTest/java/be/elevenways/hohenheim/test/`), which owns the whole boot order described in E10.