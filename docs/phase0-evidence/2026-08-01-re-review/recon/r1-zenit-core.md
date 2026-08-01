# Recon r1 - zenit core: findings F1, F6, F8, F11

Repos inspected at HEAD: zenit `8b6a60b`, zenit-auth `af25fa6`, zenit-cms `5408bcd`,
orcono `ecd8707`, hohenext/hohenheim (working tree). No files changed, no builds, no tests run.

---

## F1 - "D1 remains exploitable through explicit vocabularies"

### VERDICT: PARTIALLY REAL as a ledger-compliance gap. FALSIFIED as a security finding.

**The factual half is correct.** `RecordSource.Builder.build()`
(`zenit/src/common/java/be/elevenways/zenit/common/data/RecordSource.java:1146-1256`)
canonicalizes exactly four facets:

```java
1159:  this.projection    = canonicalFields(schema, modelId, "projects", this.projection);
1160:  this.sortable      = canonicalFields(schema, modelId, "offers as sortable", this.sortable);
1161:  this.searchFields  = canonicalFields(schema, modelId, "searches", this.searchFields);
1162-1164:  if (this.timestampField != null) { this.timestampField = canonicalField(...); }
```

The vocabulary handling at `RecordSource.java:1248-1255` only refuses mixing the two
sources and derives from the (already canonical) projection:

```java
1248:  if (this.vocabulary != null && this.vocabularySupplier != null) { ... }
1254:  Vocabulary derived = deriveVocabulary(this.projection != null ? this.projection : List.of());
```

An explicit `vocabulary(Vocabulary)` (`RecordSource.java:911-915`) or
`vocabularyFrom(Supplier)` (`:920-923`) is stored verbatim and never inspected.
`VariableDefinition.forField` (`.../orm/query/rules/VariableDefinition.java:76-78`)
delegates to `FieldRules.define`, which closes over the *supplied instance*
(`FieldRules.java:68-70`, `compile(...)` at `:161-192` calls `field.eq(...)` etc. on it),
so the criteria really is built from whatever `Field` object the declaration handed in,
and the datasource resolves the column by `field.getName()`. Reviewer's line numbers are
accurate. The `canonicalFieldJourney()` test
(`zenit/src/test/java/be/elevenways/zenit/data/RecordSourceTest.java`) does omit vocabulary.
So the fix does not meet D1's literal required outcome, which names vocabulary
(`REMEDIATION-2026-07-31.md:729-730`).

**The security half is wrong, and this is the decisive point.** D1's actual bug class was a
CHECK/USE mismatch: `build()` inspected the passed object's `isSecret()` flag while the
runtime read the row by name. The vocabulary facet has **no check to mismatch with**:

- `Vocabulary.Builder.add(...)` (`.../rules/Vocabulary.java:98-106`) performs no
  secret / encrypted / filterable test at all.
- `VariableDefinition.forField(SECRET_FIELD)` succeeds; `FieldRules.kindFor` only switches
  on the field's Java type.
- Nothing in `build()` inspects an explicit vocabulary's variables.

Therefore a declaration can *already* put the model schema's own canonical `.secret()`
field into an explicit vocabulary and get exactly the same filtering oracle, with no
forgery, no refusal, and no diagnostic. Forging a same-name field buys the author
**zero additional privilege**. Canonicalizing vocabulary fields would close nothing.

This is by design and documented: `RecordSource.java:46-49` and zenit/CLAUDE.md both say the
projection-derived vocabulary is the safe default and that "widening it is an explicit
`vocabulary(...)`/`vocabularyFrom(...)` decision" - the facet IS the escape hatch. It also
accepts hand-written `Compiler` lambdas (`VariableDefinition.java:56-59`) that can emit any
`Criteria` over any field; identity canonicalization is structurally incapable of policing
that lane.

Only `SchemaVocabulary.of` (`.../rules/SchemaVocabulary.java:44-48`) and
`RecordSource.deriveVocabulary` (`RecordSource.java:1311-1324`) apply the
`isFilterable()` gate, and `Field.isFilterable()` (`Field.java:338-344`) hard-returns false
for secret and encrypted fields. Both derived paths are already safe.

### Reachability
`vocabulary(...)` / `vocabularyFrom(...)` on `RecordSource.Builder` has **zero production
consumers** in either workspace. Workspace-wide grep (javaweb + hohenext, excluding
`build/` and tests) returns only the definitions themselves plus the unrelated
`QueryRules.vocabulary*` family and read-side `source.vocabulary()` calls
(`RecordSourceHandlers.java:202`, `RecordsWidget.java:95`, `QueryRules.java:175-177`).
So there is no live instance, forged or canonical.

### Residual real defects (small, non-security)
A non-canonical vocabulary field is still a correctness hazard for the declaring author:
- a name absent from the schema compiles into `WHERE <nonexistent_column> = ?` and fails at
  first query with a 500 rather than at boot;
- a same-name field of a *different type* (e.g. `StringField "id"` over an `IntegerField`
  column) binds the wrong Java type into the criteria - a runtime type confusion, again
  only at first query.

### THE FIX (right-sized, and it is NOT canonicalization-only)
Extend the existing `build()` canonicalization block, not a new mechanism:

1. In `RecordSource.Builder.build()`, after the four existing `canonicalFields` calls, walk
   `this.vocabulary` (the eager form only - a `Supplier` cannot be inspected at build) and
   for every variable whose key names a schema field, require identity via the existing
   private `canonicalField(...)` helper. Same message shape, same exception type.
2. **The security-meaningful half**: also refuse an explicit vocabulary variable whose key
   names a schema field that `FieldRedaction.redactsWholeValue(...)` bars, unless the
   author opts in. This is the gate that is actually missing; today an explicit vocabulary
   can filter on a secret column with no complaint. Use the same `redactedReason(...)`
   wording already in `build()` at `:1299-1309`.
3. For `vocabularyFrom(Supplier)`, apply the same check in `RecordSource.vocabulary()`
   (`:247-256`) where the supplier is resolved, matching the existing null-check there.

Files: `zenit/src/common/java/be/elevenways/zenit/common/data/RecordSource.java` only.

Counterfactual test (add steps to `RecordSourceTest.canonicalFieldJourney()`):
- step: a source declaring `vocabulary(Vocabulary.builder().add(VariableDefinition.forField(
  forgedFieldNamed("secret_token"))))` must throw at `build()`. Fails before, passes after.
- step: a source declaring an explicit vocabulary over the schema's **own** `.secret()`
  field must also throw. **This step fails today and would still fail after a
  canonicalization-only fix** - it is the step that proves the reviewer's diagnosis
  pointed at the wrong lever.
- step: `vocabularyFrom(() -> that same vocabulary)` must throw on first `vocabulary()` call.

Consumers broken: none in either workspace (zero call sites).

### CONFIDENCE
High on the code facts and on the zero-consumer claim (exhaustive grep of both workspaces).
Medium-high on "no other layer stops it": I did not execute a query, so I did not
empirically confirm that `SqlCriteriaTranslator` emits the raw `field.getName()` for a
schema-foreign field - but there is no schema lookup anywhere between `FieldRules.compile`
and the visitor, and the D1 report itself established the by-name read semantics.

---

## F6 - "F14 still allows silent source-gate widening"

### VERDICT: REAL as to the described blind spots. But the reviewer's implied fix
### ("detect a weaker permission") is NOT implementable, and the right fix is different.

`RecordSourceRegistry.reportGateLoss` at
`zenit/src/common/java/be/elevenways/zenit/common/data/RecordSourceRegistry.java:141-167`
(reviewer said 145-167; the method opens at 141) tests exactly three predicates:

```java
144:  if (derived.permission() != null && explicit.permission() == null)
147:  if (derived.hasAccessCriteria() && !explicit.hasAccessCriteria())
150:  if (derived.loginRequired() && !explicit.loginRequired())
```

**Detected:** total disappearance of a permission, of access criteria, of the login
requirement. Reported identically in both boot orders (`registerAt`, `:106-127`), throwing
in strict mode and slogging `zenit.data.source_gate_dropped` otherwise (`refuse`, `:170-176`).

**Not detected:**
- a *different* permission (`Permission.of("cms.tiny.read")` replacing
  `Permission.of("cms.people.read")`) - both non-null, silence;
- a *different* `accessCriteria` lambda, including one that returns null (= no scoping);
- the create gate: `RecordSource.createPermission()` (`RecordSource.java:342-344`) falls
  back to `permission()`, so swapping the permission silently re-gates inline create too;
- loss of `editUrl` and of `creatable(...)` - real functional regressions, but they are
  *narrowings*, not authorization widenings; lumping them in muddles the issue;
- projection / search / vocabulary widening (more columns leaving the server under the
  same gate) - not a "gate" at all under the current model, but arguably the bigger
  disclosure surface.

Test coverage confirms the gap: `RecordSourceTest` step 7 (`:1205-1229`) asserts the
three-absence message and step 8 (`:1231-1238`) asserts a *byte-identical* replacement stays
silent. Nothing tests a changed permission. The AIDEV-NOTE at `:132-140` already states the
limitation openly ("a different permission is a judgement the registry cannot make").

### Is "weaker permission" decidable? NO.
`Permission` (`zenit/src/common/java/be/elevenways/zenit/common/security/Permission.java`)
is an opaque dotted string with `equals`/`hashCode` and nothing else - no lattice, no
`implies`, no parent walk. The only ordering in the ecosystem lives on the **grant** side:
`zenit-auth/.../PermissionResolver.java:26-29` matches wildcard *grants* (`*`, `foo.*`)
against a requested permission string with longest-prefix specificity and negative-beats-
positive. That orders grants, not requirements. Deciding "every subject satisfying P2 also
satisfies P1" needs the live grant graph (users, roles, group expansion, negative grants),
which is runtime data that does not exist at class-load registration time and changes
afterwards. `KnownPermissions` is an autocomplete vocabulary, not a hierarchy.

So the AIDEV-NOTE's premise is correct and must not be re-litigated. What it gets wrong is
the *conclusion*: undecidable weakness does not justify silence. A DIFFERENCE is trivially
decidable.

### THE FIX
Extend `reportGateLoss` in place (do not add a second comparator, do not merge facets -
the complete-replacement doctrine at `:29-33` stays):

1. Rename the concept from "dropped" to "changed": report `permission(X -> Y)` whenever
   `!Objects.equals(derived.permission(), explicit.permission())`, absence included. The
   existing absent-case message stays a special case of that.
2. Report `accessCriteria(replaced)` when both sides declare one and the instances differ
   (identity is the only decidable test on a lambda; that is honest and is the same stance
   `RecordSource.build()` takes on field identity).
3. Report `loginRequired` and `createPermission` deltas the same way. `createPermission()`'s
   fallback to `permission()` means it needs its own line or a permission change silently
   moves two gates.
4. Leave `editUrl` / `creatable` OUT of the gate report; if their loss should be loud, that
   is a separate *capability* diagnostic and must not share the `source_gate_dropped` event
   name, or an operator learns to ignore a security slog.
5. `override(...)` (`:82-84`) remains the single deliberate-acceptance hatch - already wired,
   already tested at step 9.

Files: `zenit/src/common/java/be/elevenways/zenit/common/data/RecordSourceRegistry.java`
(plus the AIDEV-NOTE at `:132-140`, which must be updated, not deleted).

Counterfactual test: a new step in `RecordSourceTest.recordSourceRegistryJourney` (the
method containing steps 7-10) registering a derived default with
`permission("records.people")` and then an explicit source with
`permission("records.anyone")`. Must throw in strict mode naming both permissions;
fails today (silently installs), passes after. Add the mirror step for the opposite boot
order, asserting message equality exactly as step 7 does.

**Consumer risk - this one is real.** Every zenit-cms-derived default carries
`permission(peerPermission(panel, resource))` (`zenit-cms/.../CmsRecordSources.java:95,113`)
and often an `accessCriteria` (`:118-128`). Any app that registers an explicit source for a
model that also has a CMS resource, with a deliberately different permission, would start
failing boot on a `debugging.debug` server. That is the intended behaviour, but it needs a
sweep of explicit `RecordSourceRegistry.register` sites in hohenheim (`HohenheimSources`,
`ManagePanel`), zenit-media, thoth, quirkyquarters, spamservice and proteus before landing,
and each intentional divergence must move to `override(...)`.

### CONFIDENCE
High on what the code does and does not detect, and high on the undecidability argument.
Cannot determine without running code: how many explicit sources in the workspace would
newly trip a "changed permission" report - that needs a boot with strict registration on.

---

## F8 - "E4 still does contextual DB work before path-baseline auth"

### VERDICT: REAL as an architectural gap. LATENT - no exploitable instance exists today.

**The ordering claim is correct.** Deferral is selected solely from the endpoint's own
declaration, `zenit/src/common/java/be/elevenways/zenit/common/routing/Endpoint.java:371-372`
(reviewer said 362-379; the decision is at 371):

```java
RouteMatchContext matchContext = this.requiresAuthorization()
    ? context.deferringContextual() : context;
```

and `requiresAuthorization()` at `:193-195` is `loginRequired || !requiredPermissions.isEmpty()`
- purely endpoint-local. Route resolution runs before the middleware chain:
`HttpConduit.parseRequest()` calls `this.resolveEndpoint()` at
`.../server/http/HttpConduit.java:444`, and only afterwards iterates `Middleware.ordered()`
at `:465-476`. Deferred resolution happens later still, in `processRequest()` at `:683-689`.
zenit-auth's `AuthorizationMiddleware` (weight 30) is inside that middleware loop and is the
only thing that reads `AuthRegistry.baselinesForPath(path)`
(`zenit-auth/.../AuthorizationMiddleware.java:56`). Core cannot see it. So an endpoint gated
*only* by a path baseline matches with `context` un-deferred and its contextual resolvers run
before authentication. Reviewer is right.

**The endpoint shape exists and is idiomatic.** Whole-app catch-all baselines are in
production use:
- `orcono/mvp-v01/src/server/java/be/elevenways/orcono/server/auth/OrconoAuth.java:91` -
  `AuthRegistry.baseline("/", AuthRequirement.requiresLogin())`
- `quirkyquarters/src/main/java/be/elevenways/quirkyquarters/bootstrap/WebModule.java:179` - same
- `/home/skerit/projects/hohenext/hohenheim/src/server/java/be/elevenways/hohenheim/server/ServerMain.java:162` - same
- `zenit-auth/.../ZenitAuth.java:209-211` - `/account`, `/admin`, `/api` baselines

and orcono's own handler docblocks state the pattern explicitly
(`PropertyHandlers.java:25`, `PropertyDefinitionHandlers.java:18`: "the /api requiresLogin
baseline supplies the login check, so these declare no requiresLogin()").

**The concrete named shape** (asked for in the brief):
`/home/skerit/projects/hohenext/hohenheim/src/common/java/be/elevenways/hohenheim/HohenheimEndpoints.java:137-176`
- `SITES_DEPLOY`, `SITES_DEPLOY_CANCEL`, `SITES_ROLLBACK`, `SITES_PROCESS_START`,
`SITES_PROCESS_KILL`, `SITES_PROCESS_ISOLATE`. Each takes the `SITE_ID` route parameter
(`:22`) and declares **no** `requiresLogin()` and **no** `requiresPermission()` - they are
gated exclusively by hohenheim's `baseline("/")` plus in-handler `HohenheimAccess` checks.
`SITE_ID` is today a plain `ParameterDefinition<Integer>`, i.e. no database work. But this
is precisely the shape where the framework's own documented upgrade
(`ModelParam.of(SiteModel.class, SiteModel.ID)`, advertised in zenit/CLAUDE.md as the way to
get the loaded `Row` in the handler) would silently reintroduce the exact pre-E4 defect:
an unauthenticated POST to `/sites/{id}/deploy` would run a `SELECT` before any auth, and
would distinguish an existing site (401 from the baseline middleware) from a missing one
(fall-through / 404) - the record-existence oracle E4 was written to close.

**Why it is latent right now:** `ModelParam` has **zero** production consumers in either
workspace. Grep over javaweb + hohenext (excluding `build/`) returns only
`ModelParam.java` itself, four framework files that mention it in comments/docs
(`ScopedCspMiddleware:185`, `ZenitHttpServer:448`, `Endpoint:363`, `WebSocketEndpoint:109`,
`ParameterDefinition:183`, `RouteMatchContext`, `EndpointRoute:187`) and three test classes
(`AdmissionRateLimitHttpTest`, `WebSocketAdmissionHttpTest`, `ModelParamTest`). This matches
the E4 report's own "the WS half was LATENT" note and is still true at HEAD. Nothing else in
core carries a `contextResolver`. `AdmissionRateLimiter.check(this)` runs first
(`HttpConduit.java:437`) and bounds the *rate* of such queries, but is not an auth gate.

### THE FIX
Extend E4's own seam; do not add a second one and do not teach zenit core about
`AuthRegistry` (a layering violation - zenit-auth is downstream).

Invert the default: make contextual resolution **always** deferred, and make match-time
resolution the explicit opt-in. Concretely, in
`zenit/src/common/java/be/elevenways/zenit/common/routing/Endpoint.java`:
- `:371-372` becomes unconditional `context.deferringContextual()`, unless a new
  `Endpoint.Builder.resolveContextualDuringMatch()` flag is set;
- `WebSocketEndpoint` already behaves this way unconditionally (`:109-111`) - the two
  converge instead of diverging;
- `HttpConduit.processRequest():683-689` and `ZenitHttpServer:594-595` need no change; they
  already handle the deferred case.

Rationale: deferral is safe by default; the *only* behaviour it costs is fall-through to a
competing endpoint when the row is missing, which is an unusual, deliberate need. That need
must be declared, and declaring it is declaring "this route's existence is public".

Counterfactual test: extend
`zenit/src/test/java/be/elevenways/zenit/server/http/AdmissionRateLimitHttpTest`
with a fixture endpoint that takes a `ModelParam` and declares **no** endpoint-level gate,
plus a middleware standing in for a path baseline that refuses anonymous requests. Assert
zero datasource reads on the anonymous refusal, and an identical response for an existing
and a missing id. Fails today (1 read, and two distinguishable answers), passes after.

Consumers this would break: `AdmissionRateLimitHttpTest.missingModelRowFallsThroughToCompetingCandidate`
(the ungated fall-through test) must be updated to declare the new opt-in - that is the
whole population, since `ModelParam` has no production users. No app in either workspace
changes behaviour.

### CONFIDENCE
High. Ordering is read directly off the call sequence in `HttpConduit.parseRequest`; the
zero-consumer claim is an exhaustive two-workspace grep. Not determined without running
code: whether any third-party/custom `ParameterDefinition` with a `contextResolver` exists
outside these two workspaces (the seam is public API, so downstream code could have one).

---

## F11 - "E6 does not apply Endpoint.acceptsMatch()"

### VERDICT: REAL as stated, but PURELY LATENT - unreachable with today's wirings.

**Correct as a code fact.** `ScopedCspMiddleware.claimingRoutesOf`
(`zenit/src/server/java/be/elevenways/zenit/server/http/ScopedCspMiddleware.java:190-211`)
matches structurally and never consults the guard:

```java
195:  RouteMatchContext context = RouteMatchContext.none().deferringContextual();
197:  for (Endpoint<?> endpoint : Registries.ENDPOINTS) { ...
204:      for (EndpointRoute route : endpoint.getRoutes(null)) {
205:          if (route.getParameterMatches(uri, context) != null) { return true; }
```

Real routing does apply it - `HttpConduit.resolveEndpoint()` at
`.../server/http/HttpConduit.java:543-545` (reviewer said 548-553; the check is at 543):

```java
if (match != null && !endpoint.acceptsMatch(match)) {
    continue;
}
```

`acceptsMatch` is defined at `Endpoint.java:232-234`; the guard is installed by
`Endpoint.Builder.matchWhen` (`:856-859`), also surfaced on `PageEndpoint.PageBuilder:152`
and `FormEndpoint.FormBuilder:209`. So yes, the claim predicate is strictly broader than
the route matcher for any guarded endpoint.

**`matchWhen` has a real production consumer**, contradicting any suggestion the mechanism
is dead: `zenit-cms/.../page/ResourcePageEndpoints.java` installs
`matchWhen(ResourcePageEndpoints::panelSlugIsRegistered)` on 18 endpoints (`:176` through
`:509`).

**But the two never meet.** `claimingRoutesOf` has exactly ONE production call site:
`zenit-auth/.../ZenitAuth.java:269-271`, with `AuthEndpoints.NAMESPACE` (`"zenitauth"`).
No zenit-auth endpoint declares `matchWhen` (workspace-wide grep: the only `matchWhen`
call sites are zenit-cms's 18, plus four zenit test fixtures - `MatchGuardHttpTest`,
`AssetShadowingHttpTest`, `LocaleRoutingHttpTest`). The other scoped-CSP wirings do not use
this predicate at all: zenit-cms claims by first segment
(`CmsCspMiddleware.java:35-38` -> `ScopedCspMiddleware.firstSegment`), and hohenheim's
terminal variant uses `installVariant` (`SiteTerminalCsp.java:49`). Nothing in
`/home/skerit/projects/hohenext` calls `claimingRoutesOf`.

So the over-claim requires all four of: a guarded endpoint, in a namespace, that some
wiring claims via `claimingRoutesOf`, on a path another endpoint actually serves. Zero of
those combinations exist. Impact if it did occur is also bounded: the wrong (stricter,
admin) CSP header on a response - a functional breakage of a foreign page's scripts, not a
privilege gain. That is why I grade it latent rather than exploitable.

### THE FIX
One line, in the existing loop - no new mechanism:

```java
// ScopedCspMiddleware.java:204-206
for (EndpointRoute route : endpoint.getRoutes(null)) {
    var match = route.getParameterMatches(uri, context);
    if (match != null && endpoint.acceptsMatch(match)) {
        return true;
    }
}
```

Files: `zenit/src/server/java/be/elevenways/zenit/server/http/ScopedCspMiddleware.java`
(and its AIDEV-NOTE at `:174-189`, which should record that the claim now asks the same two
questions the router asks: does the route match, and does the endpoint accept it).

**One caveat the fix must respect:** `panelSlugIsRegistered`-style guards read a registry
that may be empty at the moment a claim is evaluated. The existing note at `:186-189`
already argues the registry walk stays per-request precisely because of lazy class-load
timing; consulting a guard adds a second lazily-populated dependency. That is the same
answer the router gives at request time, so it is consistent - but it means the claim is now
as time-varying as routing is, which should be stated in the note.

Counterfactual test: add a step to
`zenit/src/test/java/be/elevenways/zenit/server/http/ScopedCspHttpTest.derivedNamespaceClaimsMatchRoutesExactly`
(the existing 5-step journey at `:300`): a module-namespace endpoint with
`matchWhen(match -> "known".equals(match.getParameter(SLUG)))`, then assert
`claims.test("module/known") == true` and `claims.test("module/unknown") == false`.
The second assertion fails today, passes after.

Consumers broken: none. zenit-auth's two pinned assertions
(`AuthCmsResourcesIntegrationTest.java:167-169`: `admin/users` false, `login` true) are
unaffected, since no zenit-auth endpoint has a guard.

### CONFIDENCE
High on all of it - both call-site populations are small and were enumerated exhaustively
across both workspaces.

---

## Cross-cutting note

Three of these four (F1's security claim, F8, F11) are LATENT: the mechanism they concern
has no production consumer at all (`RecordSource.vocabulary`, `ModelParam`,
`claimingRoutesOf`-over-a-guarded-endpoint). F6 is the one with live blast radius, and its
fix is the one that could break existing app boots. Prioritize F6; F8 is the one worth
fixing pre-emptively because the framework actively advertises `ModelParam` as the
idiomatic way to write exactly the endpoint shape that would trip it.
