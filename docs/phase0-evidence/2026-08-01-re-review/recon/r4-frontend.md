# R4 — frontend re-review verdicts (F9, F10, F12 + triage)

Verified at HEADs: hawkeye `ab61cb43`, plumage `37bde67`, zenit-cms `5408bcd`,
orcono `ecd8707`, protoblast `c76381a`, zenit `8b6a60b`, textum `6ae80d5`.
All worktrees clean. Nothing built or run (read-only recon).

Numbering note: review-finding **F9 = ledger F1** (List directives), review-finding
**F12 = ledger F7** (orcono lifecycle). The "other proof gaps" list uses LEDGER
numbering (F5 = confirmation replay, F9 = permissions-editor keyed rows, F12 =
documentation drift, F15 = string-concat coercion).

---

## F9 (review) / F1 (ledger) — reactive authored `disabled` vs directive-owned `disabled`

### VERDICT: **REAL but correctly documented — and the reviewer UNDERSTATES it (two leaks, not one). Not a mechanism gap that should be closed.**

Current code, `hawkeye/hawkeye-core/src/common/java/be/elevenways/hawkeye/common/directive/ListDirectives.java:226-245`:

```java
// AIDEV-NOTE: the boundary guard COMPOSES with author-supplied disabled state instead
// of owning the attribute outright: DISABLED_MARKER records that the DIRECTIVE applied
// disabled (value AUTHOR_ALSO_DISABLED when the author had it first, since attributes
// apply before directives), and relaxing only ever removes directive-owned state. An
// author lane that reactively ADDS disabled after the directive claimed ownership is
// still relaxed with the boundary -- one attribute cannot carry two live writers.
String ownership = element.getAttribute(DISABLED_MARKER);

if (disabled) {
    if (ownership == null) {
        element.setAttribute(DISABLED_MARKER,
            element.getAttribute("disabled") != null ? AUTHOR_ALSO_DISABLED : "");
    }
    element.setAttribute("disabled", "");
} else if (ownership != null) {
    element.removeAttribute(DISABLED_MARKER);
    if (!AUTHOR_ALSO_DISABLED.equals(ownership)) {
        element.removeAttribute("disabled");
    }
}
```

**Why the two writers are genuinely independent.** `AttributeTranspiler` emits a
*per-attribute* reactive method (`registerReactiveMethod`, `AttributeTranspiler.java:118-136,
276-287, 899-909`), so `disabled={% locked{:} %}` re-runs **only** when `locked` changes.
`DirectiveTranspiler.generateReactiveDirectiveCode` emits a *separate* reactive method
for the render lane, which re-runs only when the `use:List.*` value (`list{:}`) changes.
Neither lane observes the other. Confirmed, not assumed.

**Failure trace A (documented in the note).** SSR with `locked == false`, index 0:
directive claims ownership, `data-list-disabled=""`, `disabled` set. Author flips
`locked → true`: the attribute lane sets `disabled` (already present — no observable
change, marker unchanged). List mutates so the boundary relaxes: ownership is `""`,
so the directive removes `disabled`. **A policy-disabled control becomes clickable.**

**Failure trace B — NOT in the note, equally real and arguably worse.** SSR with
`locked == true`, index 0: marker records `AUTHOR_ALSO_DISABLED`. Author flips
`locked → false`: the attribute lane calls `applyAttribute("disabled", false)` →
`removeAttribute("disabled")`, erasing the *directive's* boundary guard. The directive
does not re-run (the list did not change), so **the move-up button at index 0 is
clickable until the next list mutation.** The click is harmless (`move()` no-ops
out of range) but the a11y/affordance contract is broken, and the AIDEV-NOTE claims
only the author→directive direction leaks. The erasure is *symmetric*; the note
should say so.

### Is there an implementable design? Named mechanisms checked

- **Domino has no attribute-ownership or layering concept.** `DominoElement`
  (`protoblast/src/main/java/be/elevenways/domino/common/DominoElement.java:178-278`)
  exposes exactly `setAttribute` / `removeAttribute` / `getAttribute` /
  `applyAttribute` over a flat string map. No claims, no layers, no priorities.
  Workspace grep for `AttributeOwner` / `attributeOwnership` / `AttributeLayer`:
  zero hits.
- **Element attachments (`DominoElement.java:551-591`, `TypedKey`) are the closest
  substrate** — the same one `Debounce`/`Cleanup`/`Body.lockScroll` ref-count on — and
  a "disabled reason set" attachment (`{owner → boolean}`, effective = OR) is the
  textbook shape. **It is disqualified by SSR:** attachments are not serialized, so a
  server-rendered claim is gone by hydration. That is precisely why the shipped fix
  uses an *attribute* marker. An attachment-based reason set would regress the
  SSR-composition case the current fix gets right.
- **The only design that actually works** is a serialized per-element claim ledger
  (e.g. `data-hwk-claims="disabled:list,author"`) with the compiler routing **every**
  boolean-attribute write through it (owner = `author`). That is a new protoblast
  mechanism plus an `AttributeTranspiler` change touching every `disabled=` /
  `readonly=` / `hidden=` in every template in the workspace, and it collides head-on
  with the hawkeye guardrail *"Do not add element-specific renderer protocols, magic
  attributes, or one-tag special cases"* (`hawkeye/CLAUDE.md`, Architecture
  Guardrails). Special-casing the *name* `disabled` in the compiler is exactly the
  forbidden shape.

### Production reachability: **ZERO**

Every `use:List.*` call site in both workspaces, grepped and each line inspected:

| Template | Controls | Authors `disabled`? |
|---|---|---|
| `zenit-forms/.../zf-array.hwk:72,76,80` | up/down/remove | no |
| `zenit-forms/.../zf-records.hwk:119,123,127` | up/down/remove | no |
| `zenit-forms/.../zf-key-value.hwk:47` | remove | no |
| `zenit-cms/.../column-picker.hwk:32,36` | up/down | no |
| `zenit-cms/.../inplace/inplace-block.hwk:127,131,171` | up/down/remove | no |
| `zenit-cms/.../form/widget-block-list.hwk:151,154,186` | up/down/remove | no |
| `plumage/.../permissions-editor.hwk:184` | remove | no |
| `quirkyquarters` ×3 (`QqPostProcessingRulesEditor:92`, `QqLoreEntriesEditor:113`, `QqAspectsEditor:183`) | remove | no |

The only authored `disabled` on a `use:List.*` control anywhere is the hawkeye
**test fixture** — and it is placed on `use:List.remove`
(`directive-list-test.hwk:45`, `disabled={% locked{:} %}`), whose render half passes
`disabled = false` unconditionally (`ListDirectives.java:164`), so the directive
**never claims ownership there** and neither leak can fire. The reactive-author case
is therefore not merely unproven — **it is structurally excluded from the fixture.**
That is the one substantive weakness in the F1 proof: the ledger asked for "reactive
tests where business disabled and list-boundary disabled change independently", and
the reactive half is on the one directive that has no boundary.

### Judgement (decisive, as requested)

**One attribute cannot carry two live writers, and the documented limitation is
correct.** Accept it as a documented limitation. Two cheap follow-ups that are *not*
the general mechanism:

1. **Fix the AIDEV-NOTE** — it documents one direction of a symmetric erasure
   (trace B above is undocumented).
2. **Move the fixture's reactive `disabled` onto `guard-up`** (`use:List.moveUp`,
   `directive-list-test.hwk:41-43`) so the untested case is at least *pinned as the
   known-limited behaviour* rather than silently absent. Counterfactual: with the
   attribute on `remove` the assertion is vacuous (passes on any implementation);
   on `moveUp` it fails today and documents exactly which state is lost.

**Confidence: high** on the mechanism analysis and the zero-reachability sweep;
**high** on "no existing ownership mechanism exists"; **medium-high** on the judgement
call that the claim-ledger design should not be built (it is a cost/guardrail call,
not a fact).

---

## F10 — locale-sensitive `toLowerCase()` / `toUpperCase()`

### VERDICT: **REAL** (both cited sites), and the bug class is workspace-wide.

Verified at current HEAD:

- `hawkeye/hawkeye-core/src/server/java/be/elevenways/hawkeye/server/analysis/TemplateWiringAdvisor.java:569`
  ```java
  return INLINE_HANDLER_ATTRIBUTES.contains(name.toLowerCase());
  ```
  `name` is the **authored** attribute name (`checkInlineEventAttributes:540-551`
  passes it through unmodified; the `attr:` lane is judged by the same call). Under
  `-Duser.language=tr`, `"ONCLICK".toLowerCase()` → `"onclıck"` (dotless ı), which is
  not in the closed set, so an authored `ONCLICK="…"` / `attr:ONCLICK="…"` compiles
  clean. HTML attribute names are case-insensitive, so that *is* a live inline handler
  — the exact thing the ERROR exists to kill under a `script-src` CSP.
- `hawkeye/.../analysis/RetiredAttributeRegistry.java:50` (`register`) and `:61`
  (`lookup`) — same fold. Under `tr`, `DATA-CONFIRM` misses the registry (both sides
  normalize identically, so it is the *uppercase-I spelling* that escapes, not a
  register/lookup mismatch).

Both are in **`src/server`** (the compiler JVM), so the legal spelling there is
`toLowerCase(Locale.ROOT)` — the convention `zenit/src/server` already follows
everywhere (`HttpResponse.java:96,101,127,139,178`, `TrustedProxies.java:123,236`,
`DatabaseEngine.java:45,66`, `MimeTypes.java:85`, …).

**Exact fix:** `name.toLowerCase(Locale.ROOT)` at all three sites.
**Counterfactual:** run `InlineHandlerDiagnosticTest` / `RetiredAttributeTest` with an
uppercase spelling under `-Duser.language=tr -Duser.country=TR`; must throw before
(nothing thrown) and after (compile ERROR). Today no test sets a locale, so the
existing case-insensitivity tests pass under `en` and prove nothing here.

**Confidence: high.**

### The two legal spellings (source-set rule)

| Source set | Legal spelling | Why |
|---|---|---|
| `src/server`, `src/main` (JVM-only: janeway, emberglyph, protoblast `.../server/`) | `toLowerCase(Locale.ROOT)` | JVM only; `Locale` is free |
| `src/common`, `src/browser`, `src/client` | **`BlastString.lower(s)` / `BlastString.upper(s)`** | `java.util.Locale` is BANNED — one reference anchors the whole Locale clinit (30 constants + CLDR) into every TeaVM bundle. Documented at `protoblast/src/main/java/be/elevenways/protoblast/common/util/BlastString.java:25-34`; enforcement precedent: `LowerCaseMap.java:44`, and the explicit refusal comment at `hawkeye/.../browser/EventListenerTestElement.java:23` |

### Complete sweep, ranked

250 no-arg call sites across `javaweb` + `hohenext`; 180 outside test/demo source
sets. Ranked below. (Full raw list:
`<scratchpad>/prod.txt`.)

#### Tier 0 — the highest-leverage fix in the entire sweep

**`protoblast/src/main/java/be/elevenways/protoblast/common/util/BlastString.java:239` (`slug`) and `:334,338` (`camelize`)** — `src/main/.../common/` = browser-reachable.
The class that *documents* `lower()` as THE locale-independent fold does not use it
internally. Consequences, all concrete:
- `slug()` is the identity function for zenit-cms **saved views** (`StoredView.slug()`
  — "the slug DERIVES from the view name, never stored", zenit-cms guide),
  `DevTunnelBoot.java:76`, `HubProjectResource.java:71`. A locale-dependent slug is a
  locale-dependent *URL key*.
- Both are **common code compiled for both platforms**. TeaVM's no-arg
  `toLowerCase()` uses its own locale-free mapping; a JVM under a Turkish locale does
  not. So server and browser can produce **different slugs for the same input** —
  which violates hawkeye's hard invariant *"Server and client renders must produce
  identical DOM"*.
- Fix: `BlastString.lower(...)` (self-call). Three characters of risk, zero new API.

#### Tier 1 — sits on a security or identity decision

| Site | Set | Failure | Fix |
|---|---|---|---|
| `protoblast/.../common/http/Uri.java:154` (`setHost`), `:252` (`setHostname`), `:412` (`setValue` scheme/protocol fold), `:842` (`ParsedProtocol.extract` scheme) | common (browser) | Host/scheme canonicalization feeding origin checks, `UrlPolicy`, trusted-proxy matching and every generated link. Under `tr` the server folds `MAIL.EXAMPLE.COM` differently than TeaVM does in the browser → allowlist misses (fail-closed) **and** SSR/hydration href divergence | `BlastString.lower` |
| `zenit-auth/.../AuthHandlers.java:820`, `zenit-auth/.../cms/AuthUsersResource.java:295`, `proteus/.../auth/IdentityEmails.java:96`, `proteus/.../imports/MongoImporter.java:166` | server | Email = account identity. Write and read paths agree *within one JVM*, so this is not a bypass; it is a **uniqueness break across a locale change or a mixed-locale fleet** (two accounts `admin@` and `admın@`) | `Locale.ROOT` |
| `zenit-auth/.../Totp.java:139` (base32 secret), `:106` (submitted code) | server | `toUpperCase()` on a base32 secret under `tr` maps `i → İ` (U+0130), not a base32 alphabet char → secret fails to decode → **2FA permanently broken for every user** (fail-closed DoS) | `Locale.ROOT` |
| `zenit-oidc/.../client/RedirectUriMatcher.java:106,133` | server | OIDC redirect-URI host comparison. Both sides fold identically so it is not a bypass today, but the spec mandates ASCII-case-insensitive host matching and a stored-vs-live locale split would silently deny | `Locale.ROOT` |
| `hohenext/hohenheim/.../tls/AcmeService.java:133` (`validHostnames.contains(...)`), `:400,700,832,836,889`; `tls/CertificateStore.java:115,187,213` | server | Certificate-issuance hostname gate + hostname→alias map. Consistent within a JVM; a locale flip silently detaches every cert whose hostname contains `I` | `Locale.ROOT` |
| `zenit-ai/.../mcp/host/McpApiKeys.java:63` | server | Pinned virtual-header lookup on an API key ("lower-cased names") — the *stored* map keys and the *queried* name are normalized in different places | `Locale.ROOT` |
| `thoth/.../proxy/ClaudeProxy.java:76` (`HOP_BY_HOP.contains(name.toLowerCase())`) | server | Hop-by-hop header stripping in a proxy. No current hop-by-hop name contains `I`, so **not exploitable today** — but it is a header-filter allow/deny list, which is exactly where this must not depend on a JVM flag | `Locale.ROOT` |
| `spamservice/.../seed/SpamWordSeeder.java:56,78` | server | `:78` derives a **deterministic seed UUID** from `word.toLowerCase()`. A locale change re-mints every seeded row id → duplicate seed records against the `zenit_seeds` ledger | `Locale.ROOT` |
| `hohenext/hohenheim/.../dns/DnsNames.java:22,42,75`; `AxfrResponder.java:66`; `SecondaryZoneService.java:132`; `DnsZoneStore.java:289,298`; `DnsZoneFiles.java:239,244`; `DnsRecordCodec.java:138,170`; `DnsRateLimiter.java:53,67,73`; `InternalDnsTxtPublisher.java:180`; `devtunnel/DevTunnelServerHandler.java:333` | server | RFC 4343 requires **ASCII-only** case folding for DNS names. Peer-supplied qnames are folded with the JVM default locale; zone lookup, AXFR authorization and the rate-limit key all ride it | `Locale.ROOT` |
| `hohenext/hohenheim/.../cms/DnsPeerResource.java:91` (TSIG `ALGORITHMS.contains(...)`) | server | Algorithm allowlist on a DNS peer credential | `Locale.ROOT` |

#### Tier 2 — compile diagnostics and build/migration correctness

| Site | Set | Note |
|---|---|---|
| `hawkeye/.../TemplateWiringAdvisor.java:569` | server | **the F10 claim** — CSP-correctness diagnostic |
| `hawkeye/.../RetiredAttributeRegistry.java:50,61` | server | **the F10 claim** — retired-attribute diagnostic |
| `hawkeye/.../common/parser/ast/ElementUnit.java:38,119` (self-closing tags), `common/parser/TemplateParser.java:3291`, `common/api/BindEventResolver.java:57` | **common** | Parser/AST decisions — must use `BlastString.lower`, not `Locale.ROOT` |
| `hawkeye/.../server/compiler/MethodBuilder.java:898`, `TypeUtils.java:154,161` (kebab/pascal), `StatementTranspiler.java:556`, `AttributeTranspiler.java:1036` (namespace), `IRTranspiler.java:1161,1586` (generated **package name**) | server | `IRTranspiler:1161/1586` folds a namespace into a Java package name — a locale-dependent *generated FQN* |
| `hawkeye/hawkeye-lsp/.../HawkeyeTextDocumentService.java:1333,1559` | main | Editor tooling only |
| `zenit/.../common/orm/datasource/sql/SqlMigrationOperationVisitor.java:69,70,665` | server (in `common` package tree) | Table/column existence probes build `{name, lower, upper}` variants — a wrong variant makes an `ifNotExists` guard misfire during **migration** |
| `zenit/.../server/orm/FirebirdDatasource.java:83,89` | server | Identifier quoting/folding — dialect-critical |
| `zenit/.../common/orm/datasource/sql/SqlDatasource.java:811` (`sql.toUpperCase().contains("RETURNING")`) | server | Dialect branch on generated SQL |
| `zenit/.../server/http/HttpConduit.java:428` (`HttpMethod.valueOf(method.toUpperCase())`) | server | Per-request; methods arrive uppercase so it is inert in practice, but it is on the request path |
| `zenit/.../common/orm/datasource/DuplicateKeyException.java:152` | common | Column attribution from a driver message → drives the duplicate→violation mapping |
| `zenit/.../common/routing/ZenitDirectives.java:78` (`tag.toUpperCase()` then `switch`) | common | `use:Zenit.route` tag dispatch (`FORM`/`A`/…). Must be `BlastString.upper` |
| `zenit-comms/.../CommsDispatcher.java:183` | server | DSN scheme → transport routing |
| `zenit-microcopy/.../RemoteMicrocopySync.java:210,215`, `TranslationBundles.java:161` | server | Locale-**tag** folding with the default **Locale** — the one place the irony bites |
| `hohenext/hohenheim/.../database/DatabaseEnvInjection.java:96,121`, `DatabaseService.java:142,199,220,322`, `cms/DatabaseResource.java:128,131`, `cms/SiteDatabaseResource.java:160`, `cms/SiteResource.java:500`, `cms/DnsZoneResource.java:200` | server | Engine-name round-trip (`valueOf(engine.toUpperCase())` throws under `tr` for `firebird`? no `I`; `sqlite`/`mysql` no `I` — but `FIREBIRD`/`MARIADB` are safe while any future engine with `I` is not), env-var prefixes, site slugs |
| `protoblast/.../server/…` (`ServerDominoElement.java:55,620,634`, `ServerDominoElementDynamic.java:11,12`, `ServerDominoDocument.java:328`, `HtmlElementProperties.java:238,240,246`, `select/QueryParser.java:230,281,477`, `select/DominoEvaluator.java` ×17, `parser/html/DominoHtmlParser.java:140,228`) | server | SSR tag-name and CSS-selector folding. **This is the SSR half of the hydration-parity risk**: the browser folds tag names with TeaVM's mapping, the server with the JVM default locale |
| `protoblast/.../common/DominoCustomElement.java:54` | common | Custom-element name registration — `BlastString.lower` |
| `textum/.../common/serialization/HtmlImporter.java:141,336,405,453,536,573,726,809,862`; `common/code/CodeLanguages.java:101,107` | **common** | Paste/import tag dispatch — `BlastString.lower` |
| `textum/.../browser/event/TextumEventHandler.java:464` (`switch (key.toLowerCase())`) | **browser** | Keyboard-shortcut dispatch — `BlastString.lower` |
| `zenit-widget/.../common/WidgetTreeText.java:193,219` | common | HTML-ish tag scan in widget text |
| `hohenext/hohenheim/.../docker/DockerClient.java:749` | server | Response-header/line sniff |

#### Tier 3 — cosmetic / display only

`RedirectMode.java:11`, `SeedProfile.java:24`, `PluralCategory.java:21`,
`SettingsManagementService.java:82,103`, `Languages.java:36`,
`GibberishDetector.java:150,161,236`, `Ip2ProxyDatabase.java:75,88`,
`ApiEndpoints.java:245`, `ApiAuth.java:146` (this is `parseLanguages`, **not** auth
despite the filename), `StaticDataProvider.java:84,88`,
`plumage/DataSelectFunctions.java:50` (common → `BlastString.lower`),
`hawkeye/.../LayerFunctions.java:414`, `NavigationFunctions.java:202,312` (common →
`BlastString.lower`), `hawkeye/.../StringFunctions.java:28,43,58,73` (**deliberate** —
these ARE the template-exposed `str.toUpperCase()` primitives; changing them changes
template semantics, leave alone or make the locale-independence explicit),
`RenderContext.java:786` (error-message text), `janeway/*`, `emberglyph/*`,
`quirkyquarters/*` (mime/extension sniffing), `zenit-microcopy/.../ZnMicrocopyElement.java:38`
(client — `BlastString.upper`), `Totp`-adjacent none, `DominoKeyboardEvent.java:83` /
`DominoMouseEvent.java:116` (`os.name` sniff — JVM-only branch inside common).

### Suggested execution order
1. `BlastString.slug`/`camelize` self-calls (Tier 0) — one file, biggest blast radius.
2. The three hawkeye diagnostic sites (the F10 claim) + a `-Duser.language=tr` test.
3. Tier 1 security/identity sites.
4. Everything else mechanically, one commit per repo.

---

## F12 (review) / F7 (ledger) — orcono detached-page mutation

### VERDICT: **REAL, exactly one site, narrow impact. The guard doctrine is otherwise correct everywhere.**

`orcono/mvp-v01/src/client/java/be/elevenways/orcono/client/EditorSession.java:651-682`:

```java
JobRunner.startVirtualThread(() -> {
    try {
        EditorSaveResponse response = profile.saveEndpoint.call(...);

        if (this.disposed) {          // <-- 657: success lane guarded
            return;
        }
        ...
    } catch (RuntimeException e) {    // <-- 679
        Blast.log("OrcOno: Save failed:", e.getMessage());
        showSaveStatus("Save failed", true);   // <-- 681: UNGUARDED
    }
});
```

`showSaveStatus` (`:684-696`) does `this.scope.querySelector(profile.saveStatusSelector)`
then `setTextContent` + `classList` mutation. `scope` is the session's own
`.page-container`, which soft navigation detaches. So a save that **throws** after
the operator navigated away writes into a detached subtree.

**Concrete failure:** operator hits Save on page A, the request errors (network drop,
5xx surfacing as a RuntimeException from `Endpoint.call`), operator has already
soft-navigated to page B. The write lands on A's detached node — invisible, so the
*user-visible* damage is nil, but (a) it holds the detached tree alive for the
duration, and (b) it violates the ledger's stated proof obligation "*Detached
panels/editors must never change*", which the `EditorSoftNavLifecycleBrowserTest`
asserts only on the success lane.

**Guard used: `disposed`, not `isConnected` — correct.** The trap is explicitly
recorded in the class docblock (`EditorSession.java:64-70`):
*"the `disposed` flag -- never `isConnected()` -- is what cancels work that comes
back late … `@mount` legitimately runs while a soft-nav render is still assembling
DETACHED content, so a connectivity guard would refuse a perfectly live editor."*
Grep confirms **zero** `isConnected` uses in orcono client code.

**Exact fix (extension of the existing mechanism, one line):**
```java
} catch (RuntimeException e) {
    Blast.log("OrcOno: Save failed:", e.getMessage());
    if (this.disposed) {
        return;
    }
    showSaveStatus("Save failed", true);
}
```
Or, more robustly and with no duplication, guard inside `showSaveStatus` itself
(`if (this.disposed) return;` as its first statement) — that closes the lane for any
future caller too, which is the better shape.

**Counterfactual:** extend `EditorSoftNavLifecycleBrowserTest` — point the save
endpoint at a failing route, click Save, soft-navigate before the response settles,
then assert the *detached* container's status element text is unchanged. Must fail
before ("Save failed" appears on the detached node) and pass after.

### Sweep of every other async completion path in the F7 blast radius

The F7 commit (`orcono ecd8707`) touched exactly three files: `EditorInitializer.java`,
`EditorSession.java`, `EditorSoftNavLifecycleBrowserTest.java`. **No textum file was
touched.**

| Path | Line | Guard | Verdict |
|---|---|---|---|
| `save()` success/conflict/failure lanes | 657 | `disposed` | OK |
| `save()` exception lane | 679-681 | **none** | **the defect** |
| `refreshMentionLabels()` completion | 365 | `disposed` | OK |
| `refreshMentionLabels()` catch | 376 | log only, no DOM | OK |
| `applyLabelDelta()` (SyncedRef push, hopped to a virtual thread at 399) | 416 | `disposed` | OK |
| `setupMentionSync` update listener | 440 | `!this.disposed` | OK |
| panel-mentions subscription → `reconcileChipsToMentions` (hopped at 483) | 480 **and** 523 | `disposed` at both the notification and the thread body | OK (double-guarded) |
| `syncMentionsToPanel` | 495 | `disposed` | OK |
| `setupLiveLabelSync().ready().whenComplete` | 402-406 | log only, no DOM | OK |
| `EditorInitializer.provideMentionResults` (async `DataProvider.load` → `callback.accept`) | `EditorInitializer.java:246-256` | **none in orcono** | **safe — falsified** |

The last row deserves the note: it is static, session-less, and calls a callback after
an async load. It is nonetheless correct, because **Textum's `TypeaheadPlugin` owns its
own generation tombstone**: `final int token = ++this.requestToken;` before the call and
`if (token != this.requestToken) return;` inside the callback
(`textum/src/browser/java/be/elevenways/textum/browser/typeahead/TypeaheadPlugin.java:133,249-252`),
with `close()` bumping `requestToken` (`:334-343`) and `close()` registered in the
plugin's cleanup list (`:253-260`), which runs on editor destroy. A stale callback is
already invalidated at the consuming end. **Do not add a second guard here** — that
would be a parallel mechanism.

**Confidence: high** (all ten paths read line by line; the Textum tombstone traced end
to end).

---

## Triage of the smaller claims

### (a) "F5's proof does not exercise `formmethod` / `formnovalidate`"

**VERDICT: FALSIFIED as a defect; REAL but near-worthless as a proof gap.**

The mechanism is complete and the coverage is structural, not incidental:
- `ConfirmRequest.submit()` is `this.form.requestSubmit(this.submitter)`
  (`zenit-cms/.../ConfirmRequest.java:33-35`), and
  `BrowserDominoHtmlFormElement.requestSubmit` delegates straight to the **native**
  `formNode.requestSubmit(submitterNode)`
  (`protoblast/src/main/java/be/elevenways/domino/browser/BrowserDominoHtmlFormElement.java:42-54`).
  `formnovalidate` is therefore honoured by the browser itself — there is no zenit-cms
  code that could get it wrong.
- `formmethod` is honoured by hawkeye's soft-nav interceptor, which reads it **off the
  submitter**: `resolveSubmitterAttribute(submitEvent, "formmethod")`
  (`hawkeye/.../browser/BrowserSpecificHawkeye.java:1239`), alongside `formaction`
  (`:1217`) and `formenctype` (`:1207`).

All three overrides ride the *same single carrier* — `submitEvent.getSubmitter()` —
which is precisely what the F5 fix restored (`CmsConfirmFunctions.java:139,160-162`)
and what the shipped test already asserts via `formaction`. A `formmethod` assertion
would exercise `resolveSubmitterAttribute`'s second call site, i.e. hawkeye, not the
fix. Adding it is cheap and harmless; it is not a gap in the remediation.
**Confidence: high.**

### (b) "F9's proof shows node identity but not focus/caret/popup state across a reorder"

**VERDICT: PARTIALLY REAL — a proof-shape quibble, not a behavioural gap. "Reorder" is not reachable in this component.**

`plumage/src/common/templates/components/permissions-editor.hwk:159`:
`{% each items{:} as idx, entry key entry.stableId %}`. The commit (`37bde67`) also
moved emptiness from an `{% if %}` wrapper to a computed host attribute
(`attribute computed dataEmpty` + `&[data-empty] > table { display:none }`) —
that second half is the load-bearing one, because the `{% if %}` rebuilt the whole
table on every mutation and defeated the key.

Why focus/caret genuinely survive here:
- **The component has no reorder control.** The only row action is
  `use:List.remove` (`:184`). There is no `moveUp`/`moveDown`. So the reorder case the
  reviewer wants proven cannot be triggered by any user action.
- **Neither add nor remove moves a surviving `<tr>`.** Appending inserts at the end;
  removing row *n* deletes one child and leaves every sibling's DOM position untouched.
  No `insertBefore` on a live row ⟹ no removal/re-insertion ⟹ no blur. Focus, caret
  and an open `<datalist>` popup are all properties of the *same unmoved node*, which
  is exactly what the shipped expando probe (`__probeNode`) certifies.
- The reactive `attr:name` rewrite on surviving rows (`:163`, `:169`, `:180`) is an
  attribute write; it does not blur or reset a caret.

The one residue a real assertion would add: `bind:value` writes back into the
`<input>`. Assigning `.value` an *identical* string is a no-op for selection in
Chrome, and hawkeye's ref layer short-circuits equal writes
(`AbstractRef.setStoredValue` early return), so this is safe today — but it is the
only path that *could* move a caret, and only an actual `selectionStart` assertion
would catch a regression there.

**Recommendation:** strengthen, do not re-open. Add to
`PermissionsEditorTest.keyedRowsSurviveAddAndRemoveWithoutLosingTheirDomNodes`:
focus row 2's input, set `selectionStart/End` mid-string, click add, click remove-row-1,
then assert `document.activeElement` is still that input and the selection offsets are
unchanged. Counterfactual: revert the `key entry.stableId` — the probe assertion fails
*and* the focus assertion fails; keep the key but restore the `{% if %}` wrapper — the
focus assertion fails while the node-identity probe may still pass on the add step.
That second case is the one the current proof genuinely cannot distinguish.
**Confidence: high** on "no node move occurs"; **medium** on the exact Chrome caret
semantics of an identical-value `.value` write (browser detail, not code).

### (c) "F12 still has authoritative guidance drift in hohenheim and the uncommittable `resources/` tree"

**VERDICT: guidance-drift half FALSIFIED; `resources/` half REAL (and already reported).**

Every surviving mention of the dead APIs in `hohenext` lives under
`hohenext/hohenheim/docs/phase0-evidence/` — `ORCHESTRATION.md:171`,
`REMEDIATION-2026-07-31.md:1103`, `reports/f5-f8-f12-f15.md:77,78,85,88`,
`recon/wave-f.md:178,179,182`. These are the **archived evidence record**: they quote
the pre-fix state *as the finding being reported*. Rewriting them would falsify the
audit trail. They are not authoritative guidance and no reader would follow them as
API docs. Not drift.

`/home/skerit/projects/javaweb/resources/` is confirmed **not inside any git
repository** (`git rev-parse --show-toplevel` fails there, and at the `javaweb` root).
The two corrected files (`shortlinker-port/03-port-precedent.md`,
`07-architecture.md`) are on disk and uncommittable. That is an **owner decision**
(version the tree, or accept it as scratch), not a remediation defect — and the prior
report already surfaced it. **Confidence: high.**

### (d) "F15 leaves the Alchemy coercion occurrence"

**VERDICT: the occurrence is REAL; the *defect* is FALSIFIED. Not browser-reachable in any Zenit sense, and uncommittable.**

`/home/skerit/projects/javaweb/alchemy/alchemy-form/view/form/inputs/view_inline/boolean.hwk:18`
contains `"" + value`. It is the only remaining `.hwk` occurrence workspace-wide
(grep over both trees).

It is **not a hawkeye template.** `javaweb/alchemy/` is the legacy **Node.js**
Alchemy monorepo (siblings: `hawkejs`, `protoblast` (JS), `janeway`, `blessed`,
`alchemy-chimera`, …). The same file uses Hawkejs-only syntax — `<% $0.classList.add('boolean-' + value) %>`
and the `{%t … %}` translation block — neither of which the hawkeye compiler parses.
The rule being "violated" (*"never `"" + value` in templates; String positions coerce
like prints"*) is a **Java/hawkeye typing rule** about type-checked `var:` passes; in
JavaScript `"" + value` is the idiomatic and correct coercion. There is nothing to fix.

`git -C javaweb/alchemy rev-parse` fails — not a git repository — so even a cosmetic
change would be uncommittable. **Recommendation: record the scope decision explicitly
(legacy JS tree, out of scope, permanently) so this does not resurface in a third
review.** **Confidence: high.**

---

## Summary table

| Item | Verdict | Severity | Fix size |
|---|---|---|---|
| F9 / ledger F1 — reactive authored `disabled` | REAL, correctly documented; **no mechanism exists and none should be built** | Low (0 production call sites) | Note correction + fixture relocation |
| F10 — locale-sensitive case folding | **REAL**, both sites; 180-site workspace class | Medium (compile diagnostics) → High (TOTP, slugs, hydration parity) | ~30 one-line edits, two spellings by source set |
| F12 / ledger F7 — orcono save exception lane | **REAL**, exactly one site; guard doctrine otherwise correct | Low (detached write, invisible) | 1 line |
| F5 `formmethod`/`formnovalidate` | FALSIFIED as defect | — | optional assertion |
| F9 focus/caret/reorder | PARTIALLY REAL (proof shape only; reorder unreachable) | Low | 4-line test extension |
| F12 hohenheim guidance drift | FALSIFIED (evidence archive, not guidance) | — | none |
| F12 `resources/` uncommittable | REAL, owner decision | — | owner |
| F15 alchemy `"" + value` | FALSIFIED (legacy JS/Hawkejs tree, not hawkeye) | — | record scope decision |
