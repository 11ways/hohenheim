# F10 core/frontend locale-fold remediation (2026-08-01)

## Verbatim pre-fix failures (counterfactuals run against UNMODIFIED code under tr_TR)

### Tier 0 — protoblast `LocaleFoldingTest` (log 20260801-105517)
```
step 1: slug of an uppercase-I input must match the TeaVM/ASCII fold ==> expected: <saved-view-title> but was: <saved-v-ew-t-tle>
step 3: hostname fold must be locale-independent ==> expected: <mail.example.com> but was: <maıl.example.com>
step 2: camelCase first word must lowercase I to ASCII i ==> expected: <firstItem> but was: <fırstItem>
```
All three pass after the fix (protoblast unit suite: 2818 passed).

### Tier 1/2 — hawkeye `TurkishLocaleDiagnosticTest` (log 20260801-110421)
```
step 1: ONCLICK= must fail compilation under a Turkish default locale ==> Expected java.lang.Exception to be thrown, but nothing was thrown.
step 2: uppercase-I spelling must hit the retirement registered lowercase ==> expected: not <null>
```
i.e. an authored `ONCLICK="..."` compiled CLEAN past the inline-handler CSP diagnostic.
Both pass after the fix (hawkeye unit suite: 2692 passed).

### Tier 1 — zenit-auth `TotpTurkishLocaleTest` (log 20260801-111740)
```
java.lang.IllegalArgumentException: Invalid Base32 character: İ
  at be.elevenways.zenit.auth.server.Totp.base32Decode(Totp.java:147)
  at be.elevenways.zenit.auth.server.Totp.codeAt(Totp.java:39)
```
2FA permanently broken for any secret containing letter i under tr. Passes after fix
(TotpTurkishLocaleTest + TotpTest: 7 passed).

### Guard counterfactual (live, not synthetic)
The new `checkLocaleFolds` compile gate failed a real build and named two sites the
recon sweep had MISSED (added after recon):
```
Execution failed for task ':checkLocaleFolds' (registered by plugin 'be.elevenways.protoblast').
  .../zenit-microcopy/.../imports/MicrocopyMongoImporter.java:317: return parts[0].toLowerCase();
  .../MicrocopyMongoImporter.java:320: return parts[0].toLowerCase() + "-" + parts[1].toUpperCase();
```
Fixed; build then green. This is the strongest possible proof the class is closed going
forward: the guard caught sites no human list contained.

## Sites changed, spelling per source set

Spelling rule applied: `src/server`/`src/main` (JVM-only) -> `toLowerCase/UpperCase(Locale.ROOT)`;
`common`/`browser`/`client` (TeaVM-reachable) -> `BlastString.lower/upper` (java.util.Locale is
banned there — anchors the Locale clinit into every TeaVM bundle).

### protoblast (commits 535fb5f Tier 0+Uri, 5ad2c1b sweep+guard)
- BlastString.java:239,334,338 (slug/camelize) -> self-call `lower(...)` + AIDEV-NOTE (Tier 0).
- Uri.java:154,252,412,842 (host/hostname/scheme folds) -> `BlastString.lower` + AIDEV-NOTE.
- common (BlastString): DominoCustomElement:54, DominoKeyboardEvent:83, DominoMouseEvent:116, PluralCategory:21.
- server packages (Locale.ROOT): ServerDominoElementDynamic (2), ServerDominoKeyboardEvent (1),
  ServerDominoElement (3), HtmlElementProperties (3), ServerDominoDocument (1), QueryParser (3),
  DominoEvaluator (16), DominoHtmlParser (2) — the SSR half of the hydration-parity risk.
- NEW: protoblast-gradle-plugin `CheckLocaleFoldsTask` + wiring in `ProtoblastGradlePlugin`.
- NEW tests: `LocaleFoldingTest` (tr_TR behaviour), `LocaleFoldGuardTest` (drift scan; protoblast
  does not apply its own plugin).

### hawkeye (commit d5c17f63, branch type-system-v1)
- server (Locale.ROOT): TemplateWiringAdvisor:569 and RetiredAttributeRegistry:50,61 (the two
  reviewer-named sites), MethodBuilder:898, TypeUtils:154,161, StatementTranspiler:556,
  AttributeTranspiler:1036, IRTranspiler:1161,1586 (generated package-name identity).
- main (Locale.ROOT): hawkeye-lsp HawkeyeTextDocumentService:1333,1559.
- common (BlastString): LayerFunctions:414, NavigationFunctions:202,312, TemplateParser:3291,
  ElementUnit:38,119, BindEventResolver:57, RenderContext:786.
- StringFunctions:28,43,58,73 — CONFIRMED DELIBERATE and left untouched: they are the
  template-exposed `String.toUpperCase/toLowerCase` primitives; author-visible default-locale
  behaviour is the contract. AIDEV-NOTE added (including the honest caveat that a non-ROOT
  server locale can diverge from TeaVM at SSR time) + `locale-fold: deliberate` markers so the
  guard and any fourth review skip them knowingly.
- NEW tests: `TurkishLocaleDiagnosticTest`, `LocaleFoldGuardTest` (drift scan of hawkeye-core
  common/browser/server + hawkeye-lsp + hawkeye-compile; hawkeye is plugin-less).

### zenit (commits 50f8148 hotfix, eeb8609 folds+marker)
- server (Locale.ROOT): RedirectMode:11, HttpConduit:428 (via existing `import static
  java.util.Locale.ROOT` — plain import is ambiguous with protoblast's i18n.Locale),
  FirebirdDatasource:83,89, SeedProfile:24, SqlMigrationOperationVisitor:69,70,665 (x2 each),
  SqlDatasource:811.
- common (BlastString): StaticDataProvider:84,88, DuplicateKeyException:152,
  ZenitDirectives:78 (`BlastString.upper`).
- 50f8148: HOTFIX of another agent's commit fc89eaf — `@NonNull com.couchbase.client.java.Collection`
  is an illegal type-annotation position; that commit never compiled and had the whole chain red
  independently of this task.

### zenit-auth (738c7f0)
Totp:106,139 (backup-code lower + base32 upper), AuthUsersResource:295, AuthHandlers:820 — Locale.ROOT.

### proteus (f4c04c8)
IdentityEmails:96, Mnemonic:102, MongoImporter:166 — Locale.ROOT (email identity uniqueness).

### plumage (d1d6c7e) DataSelectFunctions:50 -> BlastString (common).
### textum (e7deb1e) CodeLanguages:101,107 + HtmlImporter x9 (common), TextumEventHandler:464 (browser) -> BlastString.
### zenit-widget (8be1cef) WidgetTreeText:193,219 -> BlastString (common).
### zenit-comms (dcab507) CommsDispatcher:183, HubProjectResource:71 -> Locale.ROOT.
### zenit-microcopy (171fa77) RemoteMicrocopySync:210,215, TranslationBundles:161,
MicrocopyMongoImporter:317,320 (guard-caught) -> java.util.Locale.ROOT (qualified — i18n.Locale
import collision); ZnMicrocopyElement:38 (client) -> BlastString.upper.
### quirkyquarters (a0729b7) AttachmentParts:51 (common, BlastString),
IrcMessageDispatcher, TelegramChatChannel (main, Locale.ROOT).

## The durable guard (decision: BUILT, two-layer)

1. **Mechanism home: protoblast-gradle-plugin** — `CheckLocaleFoldsTask` +
   `ProtoblastGradlePlugin.configureLocaleFoldGuard`. Every non-test `compileJava` of a
   consuming project depends on a source scan that FAILS the build on a no-arg
   `toLowerCase()`/`toUpperCase()` anywhere, and additionally on a `Locale`-argument fold in
   `common`/`browser`/`client` source sets (where BlastString is the only legal spelling).
   Comment lines are skipped; the escape hatch is a `locale-fold: deliberate` comment on the
   line (used exactly 4x: hawkeye StringFunctions). Input-tracked, so incremental builds pay
   nothing; generated sources under build/ are not scanned.
2. **Opt-in per repo** via a committed `locale-folds.guard` marker file (the
   teavm-bundle.budget "no file = no check" convention). Rationale: my first, unconditional
   wiring escaped early via an auto-publish and turned every consumer with pre-existing sites
   red at once (orchestrator confirmed it blocked the SSL/DNS agent until they fixed their 44
   sites). Armed in: zenit, zenit-auth, proteus, plumage, textum, zenit-widget, zenit-comms,
   zenit-microcopy, quirkyquarters (+ whatever the SSL/DNS agent armed). A future repo opts in
   by adding the marker; `gradle checkLocaleFolds` can be run ahead of opting in.
3. **Plugin-less repos** (protoblast itself, hawkeye) get mirrored JUnit drift tests
   (`LocaleFoldGuardTest` in each), same regexes, same marker, with keep-in-sync notes.

## Where I drew the line / deliberately left

- **Left to the SSL/DNS/app-tier agent** (per assignment): hohenext/hohenheim (DNS/ACME/TLS),
  zenit-oidc RedirectUriMatcher, spamservice, zenit-ai McpApiKeys + McpToolSchema, and
  **thoth ClaudeProxy.java:76** (HOP_BY_HOP header list — flagging it here because it is in
  the thoth repo, not zenit-ai, and could fall between the two assignments).
- **Left unfixed, un-gated (Tier 3, JVM-only TUI/display, no plugin so no gate pressure):**
  janeway (6 sites: palette/log-view/config display), emberglyph (2 sites: terminal backend,
  toasts). No security/identity/parity surface; each is a 1-line `Locale.ROOT` fix whenever
  those repos next get touched — the guard marker can be added then.
- **Left untouched on purpose:** hawkeye StringFunctions x4 (deliberate, documented above);
  `alchemy/` legacy JS tree (not Java, not a git repo).
- **Not mine, flagged for the protoblast/classpath-guard agent:** (a) hawkeye browserTest lane
  currently fails their new TeaVM patch-lane ORDER check (TUUID/TConcurrentHashMap carrier
  after upstream jar) — pre-existing classpath order surfaced by their new guard, not a locale
  issue; (b) proteus `generateJavaScript` fails their duplicate-classes guard
  (zenit-cms-server jar duplicating proteus client jar tag classes). Proteus compiles and
  passes checkLocaleFolds; it cannot publish its TeaVM bundle until that packaging issue is
  resolved.

## Verification performed
- Counterfactuals above: fail-before/pass-after under forced tr_TR, one per tier plus the
  named slug/TOTP/diagnostic cases.
- protoblast full unit suite 2818 pass; hawkeye full unit suite 2692 pass; zenit-auth targeted
  Totp suites 7 pass.
- TeaVM/browser: zenit full `assemble` (includes generateJavaScript bundle) green 3x after the
  edits (11:12/11:21/11:24 runs, exit 0) — that bundle compiles protoblast+hawkeye+zenit
  common code under TeaVM, which is the check the "Locale import in common" trap fails;
  quirkyquarters full build (its own TeaVM bundle) green, 256s.
- Final end-to-end chain build from zenit-auth: protoblast -> hawkeye -> ... -> zenit-cms ->
  zenit-auth all green (325s) with the gate armed in every marker repo.
- Residual sweep of all touched repos: zero non-arg fold sites outside comments/deliberate
  markers.
- Every commit checked with `git log -1`: subject standalone, <72 chars, gitmoji first char,
  body on its own line, max 3 lines. Only my files staged; other agents' dirty files left.

## Commits
- protoblast: 535fb5f (Tier 0 + Uri + LocaleFoldingTest), 5ad2c1b (sweep + guard + drift test)
- hawkeye (type-system-v1): d5c17f63
- zenit: 50f8148 (Couchbase annotation hotfix of fc89eaf), eeb8609 (folds + marker)
- zenit-auth: 738c7f0; proteus: f4c04c8; plumage: d1d6c7e; textum: e7deb1e;
  zenit-widget: 8be1cef; zenit-comms: dcab507; zenit-microcopy: 171fa77; quirkyquarters: a0729b7

## Known limitations / follow-ups
- The plugin gate is opt-in by marker; repos without the marker (and future repos) are only
  protected once they add it. Making it default-on is a one-line flip once janeway/emberglyph
  and all hohenext repos are clean — owner call.
- The scanner is line-based: a violation after a `//` inside a string literal on the same line
  would be missed; acceptable for a drift guard, and the marker covers false positives.
- hawkeye browser suite could not run (patch-lane order failure, other agent's domain); TeaVM
  validation for hawkeye common changes rests on zenit's and QQ's green bundle builds.
