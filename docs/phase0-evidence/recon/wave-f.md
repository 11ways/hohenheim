## Wave F reconnaissance — verdicts and anchors

All paths absolute. Nothing was modified.

---

### F1. List directives erase authored disabled state — **REAL**

**Evidence** `/home/skerit/projects/javaweb/hawkeye/hawkeye-core/src/common/java/be/elevenways/hawkeye/common/directive/ListDirectives.java:206-225`

```java
if (disabled) { element.setAttribute("disabled", ""); }
else          { element.removeAttribute("disabled"); }   // line 219
```
Unconditional removal. Worst case is `removeRender` (`:154`), which calls `applyRenderState(..., false)` — `use:List.remove` *always* strips `disabled`, boundary or not. `moveUpRender` `:88`, `moveDownRender` `:121`.

**Mechanism** Markers already exist per directive (`MOVE_UP_MARKER` / `MOVE_DOWN_MARKER` / `REMOVE_MARKER`, stamped `:214`); mirror them with a directive-owned disabled marker (e.g. `data-list-disabled`) and only remove `disabled` when that marker is present. Compare the aria-label precedent at `:222` ("author-supplied wins").

**Tests** `/home/skerit/projects/javaweb/hawkeye/hawkeye-core/src/test/java/be/elevenways/hawkeye/ListDirectivesRenderTest.java` (`boundaryGuardsAriaLabelsAndMarkersRenderServerSide:24`, `anAuthorSuppliedAriaLabelWins:57` — the exact pattern to copy for authored `disabled`), browser: `/home/skerit/projects/javaweb/hawkeye/hawkeye-core/src/browserTest/java/be/elevenways/hawkeye/browser/ListDirectiveBrowserTest.java:30` with fixture `/home/skerit/projects/javaweb/hawkeye/hawkeye-core/src/browserTest/resources/templates/directive-list-test.hwk`.

---

### F2. `attr:on*` bypasses `inline-event-attribute` — **REAL**

**Evidence** `/home/skerit/projects/javaweb/hawkeye/hawkeye-core/src/server/java/be/elevenways/hawkeye/server/analysis/TemplateWiringAdvisor.java:538-546`

```java
String namespace = attribute.getNamespace();
if (name == null || (namespace != null && !namespace.isEmpty())) { continue; }
```
Any namespace short-circuits. The retired-attribute rule in the *same file* already does it right (`:505-509`): `... && !"attr".equals(namespace)`. Handler name set at `:568+` (`INLINE_HANDLER_ATTRIBUTES`, matched case-insensitively via `isInlineHandlerName:566`), so the "keep `once` legal" requirement is already satisfied by that closed set.

**Fix shape** Copy the `"attr".equals(namespace)` allowance from `checkRetiredAttributes` into `checkInlineEventAttributes`.

**Tests** `/home/skerit/projects/javaweb/hawkeye/hawkeye-core/src/test/java/be/elevenways/hawkeye/InlineHandlerDiagnosticTest.java` — add `attr:onclick` / `attr:onload` cases beside `anInlineClickHandlerFailsTheBuildByDefault:55`; keep `nonHandlerNamesStartingWithOnStayQuiet:73` and `eventNamespaceBindingsStayQuiet:83` green.

---

### F3. Directive synthetic methods have no source marker — **REAL**

**Evidence** `/home/skerit/projects/javaweb/hawkeye/hawkeye-core/src/server/java/be/elevenways/hawkeye/server/compiler/DirectiveTranspiler.java`
- event lane: `useMethodBuilder(listenerMethod)` at `:141`, body starts immediately with variable declarations — no `emitSourceMarker`.
- reactive lane: `useMethodBuilder(directiveMethod)` at `:202`, likewise.

`SourceMapBuilder` scans the generated Java linearly for `// @hwk:<line>` comments (`/home/skerit/projects/javaweb/hawkeye/hawkeye-core/src/server/java/be/elevenways/hawkeye/server/compiler/SourceMapBuilder.java:26-27,57-78`), and `TemplateSourceMap.getTemplateLine` resolves a Java line to the *preceding* marker — so every line inside a directive method maps to the last marker of the previous method.

**Mechanism to reuse** `IRTranspiler.emitSourceMarker(IRUnit)` at `/home/skerit/projects/javaweb/hawkeye/hawkeye-core/src/server/java/be/elevenways/hawkeye/server/compiler/IRTranspiler.java:1145` (call sites `:1610,1616,1635,1838-1856,1950-1975`). Call `parent.emitSourceMarker(directive)` right after each `useMethodBuilder`.

**Adjacent (same bug class, not in scope but note it)** `/home/skerit/projects/javaweb/hawkeye/hawkeye-core/src/server/java/be/elevenways/hawkeye/server/compiler/FormActionTranspiler.java:109` has the identical omission.

**Test infrastructure** `/home/skerit/projects/javaweb/hawkeye/hawkeye-core/src/test/java/be/elevenways/hawkeye/SourceMapTest.java` — `testSourceMapRegisteredAfterCompilation:111` (compile → `SourceMapRegistry` lookup) and `testExceptionTranslation:130` (asserts `"↳ template: users/detail:10"` in the translated trace). A throwing directive test drops straight into that harness.

---

### F4. Retired attributes are case-sensitive — **REAL**

**Evidence** `/home/skerit/projects/javaweb/hawkeye/hawkeye-core/src/server/java/be/elevenways/hawkeye/server/analysis/RetiredAttributeRegistry.java:49` `RETIRED.put(name, ...)` into a plain `HashMap` (`:21`), and `:57` `return RETIRED.get(attributeName);` — exact-case. The advisor passes the raw IR attribute name (`TemplateWiringAdvisor.java:511`); the template parser does not lowercase attribute names (only tag names, `ElementUnit.java:38,119`). `DATA-CONFIRM` therefore compiles clean while reaching the same HTML attribute. Contrast: the inline-handler set *is* normalized (`TemplateWiringAdvisor.java:566` `name.toLowerCase()`).

**Fix shape** Normalize the key on `register` + `lookup` for HTML-namespace names; report the authored spelling in the message. Retired declaration site to test against: `@HawkeyeRetiredAttribute(name = "data-confirm", ...)` on `/home/skerit/projects/javaweb/zenit-cms/src/common/java/be/elevenways/zenit/cms/common/render/action/CmsConfirmFunctions.java:35`.

**Tests** `/home/skerit/projects/javaweb/hawkeye/hawkeye-core/src/test/java/be/elevenways/hawkeye/RetiredAttributeTest.java` (fixture `/home/skerit/projects/javaweb/hawkeye/hawkeye-core/src/testSupport/java/be/elevenways/hawkeye/testSupport/RetiredAttributeFixture.java`); add an uppercase/mixed-case case next to `aRetiredAttributeFailsTheBuildByDefault:53`, keep `commentsMentioningTheNameStayLegal:83`.

---

### F5. Confirmation replay loses submitter + sticky marker — **REAL (both halves)**

File: `/home/skerit/projects/javaweb/zenit-cms/src/common/java/be/elevenways/zenit/cms/common/render/action/CmsConfirmFunctions.java`

1. **Submitter dropped on the form lane** — `:133` `publish(new ConfirmRequest(confirmation, form, null));`. The directive's `event` param is a `DominoEvent`; on a `<form>` the ACTIVATION binding resolves to `submit` (`DirectiveTranspiler.java:125-128`), so the live event is a `DominoSubmitEvent` carrying `getSubmitter()` (`/home/skerit/projects/javaweb/protoblast/src/main/java/be/elevenways/domino/common/DominoSubmitEvent.java:22`; browser impl `BrowserDominoSubmitEvent.java:48`). Replay goes through `ConfirmRequest.submit()` → `form.requestSubmit(this.submitter)` (`/home/skerit/projects/javaweb/zenit-cms/src/common/java/be/elevenways/zenit/cms/common/render/action/ConfirmRequest.java:33-35`) with `submitter == null`, so submitter name/value and `formaction`/`formmethod` overrides are lost. The click/submitter lane (`:142` → `requestForSubmitter:197-210`) does preserve it — only the form lane is broken.
2. **Sticky replay marker** — `:162-163`
```java
request.form().setAttribute(REPLAY_MARKER, "true");
request.submit();
```
`requestSubmit` runs interactive constraint validation; if it declines, no submit event fires, `hold` never consumes the marker (`:125-127`), and the *next* genuine submit is waved through unconfirmed.

**Mechanism** Pattern-match the event (`event instanceof DominoSubmitEvent se ? se.getSubmitter() : null`) into the existing `ConfirmRequest.submitter` slot; set the marker only around a submission that re-enters, or clear it on a microtask/`invalid` fallback.

**Tests** `/home/skerit/projects/javaweb/zenit-cms/src/browserTest/java/be/elevenways/zenit/cms/test/browser/EditPageDeleteConfirmBrowserTest.java`, plus `ResourceListActionsBrowserTest.java`, `PagePublishFlowBrowserTest.java`, `ActivityHistoryBrowserTest.java`, `MediaLibraryBrowserTest.java`, `ListViewsBrowserTest.java` (all in the same directory). Wave G1 says preserve the typed `ConfirmRequest` channel — the fix fits inside it (fill the existing `submitter` field).

---

### F6. `pl-terminal` never disposes — **REAL**

**Evidence** `/home/skerit/projects/javaweb/plumage/src/common/templates/components/terminal.hwk:33-36`
```
@mount
function initTerminal() {
    Terminal.init(containerEl, rows, cols, fontSize, fontFamily, readOnly, wsUrl)
}
```
No `Cleanup.on`, no `Terminal.dispose` anywhere in the template. The dispose function exists and is wired: `/home/skerit/projects/javaweb/plumage/src/common/java/be/elevenways/plumage/component/TerminalFunctions.java:117-131` (`Terminal.dispose` → `TerminalBridge.SEAM.require().disposeTerminal(container)`).

**Mechanism** `/home/skerit/projects/javaweb/hawkeye/hawkeye-core/src/common/java/be/elevenways/hawkeye/common/CleanupFunctions.java` — `Cleanup.on(owner, disposer)` at `:38-58`; **keyed** form at `:77+` ("registering the same key on the same owner again replaces the previous callback") which is the right one if `@mount` can re-run in one connected lifetime. Backed by `CustomElement.registerDisposer(...)` / `registerDisposer(key, ...)` at `/home/skerit/projects/javaweb/hawkeye/hawkeye-core/src/common/java/be/elevenways/hawkeye/common/customelement/CustomElement.java:229,265`.

**Tests** `/home/skerit/projects/javaweb/plumage/src/browserTest/java/be/elevenways/plumage/test/TerminalTest.java` (`terminalRendersAndBootsItsCanvas:26`), `TerminalCspTest.java`, `TerminalFallbackTest.java`; fixtures `/home/skerit/projects/javaweb/plumage/src/browserTest/templates/test/terminal-test.hwk`, `terminal-no-script-test.hwk`. Seam is `PlatformSeam.required` (`TerminalBridge.java:18`) — a test fake installs through `SEAM.install(...)`.

---

### F7. Orcono editor lifecycle leaks — **REAL (every sub-claim verified)**

File: `/home/skerit/projects/javaweb/orcono/mvp-v01/src/client/java/be/elevenways/orcono/client/EditorInitializer.java`

- **Static per-page state**: `:170-186` — `static TextumEditorElement editorElement`, `static EditorProfile activeProfile`, `static String entityId`, `static int baseRevision`, `static Map mentionLabels`, `static Ref<List<MentionView>> panelMentionsRef`, `static boolean pushingMentions`, plus `:359-360` `lastLabelRefreshAt` / `editorHasFocus`.
- **Editor never destroyed**: `initialize:229-304` builds a new `TextumEditorElement(editorArea, config)` at `:268` on every mount; nothing calls `TextumEditorElement.destroy()` (which exists: `/home/skerit/projects/javaweb/textum/src/browser/java/be/elevenways/textum/browser/element/TextumEditorElement.java:139`).
- **Update-listener disposer discarded**: `setupMentionSync:488-497` — `editorElement.getEditor().registerUpdateListener(...)` return value dropped; the method **returns a `Runnable`** (`/home/skerit/projects/javaweb/textum/src/common/java/be/elevenways/textum/common/TextumEditor.java:376`; correct usage precedents: `TableInteractionManager.java:44/52`, `TextumTreeView.java:40/146`, `HistoryPlugin.java:43`).
- **Synced-ref subscription never closed**: `setupLiveLabelSync:442-459` — `SyncedRefs.subscribe(...)` result is local and dropped; `SyncedRefSubscription.close()` exists at `/home/skerit/projects/javaweb/zenit/src/common/java/be/elevenways/zenit/common/channel/SyncedRefSubscription.java:63`. The inner `subscription.ref().subscribe(...)` (`:449`) returns a `Ref.Subscription` (`/home/skerit/projects/javaweb/protoblast/src/main/java/be/elevenways/protoblast/common/holder/Ref.java:177`, `unsubscribe()`) — also dropped.
- **Panel bound exactly once, forever**: `ensureMentionPanelBound:508-535` — `if (panelMentionsRef != null) return true;` (`:509-511`) then `panelMentionsRef.subscribe(...)` at `:520`. After a soft nav the static ref still points at the *detached* previous page's `property-panel`, and a second subscription is added each time it does rebind.
- **Focus listeners never removed**: `setupLabelRefresh:371-392` — `editorArea.addEventListener("focusin"/"focusout", ...)`; `setupSaveButton:708-717` — `saveBtn.addEventListener("click", ...)`. `DominoElement.removeEventListener` exists (`/home/skerit/projects/javaweb/protoblast/src/main/java/be/elevenways/domino/common/DominoElement.java:631`), but requires holding the listener identity.

**Mechanism** The mount entry points are `initializePage:213` / `initializeIssue:218`, driven by `PageEditorMount.mount` / `IssueEditorMount.mount` (`/home/skerit/projects/javaweb/orcono/mvp-v01/src/common/java/be/elevenways/orcono/common/editor/PageEditorMount.java:45-50` — already a `PlatformSeam.required`). The host element arriving there is the custom element, so `CustomElement.registerDisposer(key, ...)` / `Cleanup.on` is the registration channel; move the seven statics into an instance-scoped lifecycle object attached to that host.

**Tests** `/home/skerit/projects/javaweb/orcono/mvp-v01/src/browserTest/java/be/elevenways/orcono/browsertest/` — `PageEditorRoundTripBrowserTest.java`, `IssueEditorBrowserTest.java`, `LiveMentionSyncBrowserTest.java`, `PropertyPanelBrowserTest.java`, `MentionAutocompleteBrowserTest.java`, base `OrconoBrowserTestBase.java`.

---

### F8. Settings reset controls have ambiguous accessible names — **REAL**

**Evidence** `/home/skerit/projects/javaweb/zenit-cms/src/common/templates/pages/settings.hwk:115-124`
```
<pl-checkbox inputId={% s.entry.path + "__clear" %} name={% s.entry.path + "__clear" %} ...>
<pl-label target={% s.entry.path + "__clear" %} ...>
    <span class="cms-setting-reset-idle">{{ t("reset", scope: "settings") }}</span>
    <span class="cms-setting-reset-staged">{{ t("reset_staged", scope: "settings") }}</span>
```
Every row in the page renders the identical label text; nothing in the accessible name carries `s.entry.path` or the setting's label. (Both spans are in the label, so the computed name may even read "Reset Reset staged" depending on the CSS hiding technique.)

**Mechanism** `pl-label` association is already correct (`target`/`inputId`); add an `ariaLabel` (or visually-hidden span) carrying the setting label/path while keeping the visible microcopy short. Microcopy keys `reset` / `reset_staged`, scope `settings`.

**Tests** `/home/skerit/projects/javaweb/zenit-cms/src/browserTest/java/be/elevenways/zenit/cms/test/browser/SettingsPageBrowserTest.java` (+ `SchemaFromSettingsFormBrowserTest.java`, page type fixture `PlainSettingsPageType.java`); server-side `/home/skerit/projects/javaweb/zenit-cms/src/test/java/be/elevenways/zenit/cms/server/page/SettingsSubmissionTest.java` guards the `__clear` transport.

---

### F9. `pl-permissions-editor` rows are non-keyed — **REAL**

**Evidence** `/home/skerit/projects/javaweb/plumage/src/common/templates/components/permissions-editor.hwk:148`
```
{% each items{:} as idx, entry %}
<tr attr:data-row-index={% idx %}>
```
Body contains `<input type="text" ... bind:value>` (`:152-157`) and `<select ... bind:value>` (`:158-163`) plus extra-column partials (`:164-171`) — exactly the `STATEFUL_ELEMENTS` the advisor's `each-missing-key` INFO rule warns about (`TemplateWiringAdvisor.java:85`, message at `:666-669`). `Add row` (`:181`) and `use:List.remove` (`:173`) both mutate the list.

**Blocker to solve first**: `/home/skerit/projects/javaweb/plumage/src/common/java/be/elevenways/plumage/component/PermissionEntry.java` has no identity field (`permission`, `value`, `extras` only) and new rows start with `permission == ""`, so `key entry.permission` is not stable/unique.

**Mechanism** The workspace idiom is a `__stableId` UUID seeded server-side: `/home/skerit/projects/javaweb/zenit-forms/src/common/java/be/elevenways/zenit/forms/common/render/ZenitFormsFunctions.java:159,166,185,226,245,300`, `FormRenderDefaults.java:448-450`, constant `WidgetTreeEditField.STABLE_ID_KEY`. Keyed-each call sites to copy: `/home/skerit/projects/javaweb/zenit-forms/src/common/templates/form/zf-array.hwk:48`, `zf-records.hwk:99`, `/home/skerit/projects/javaweb/zenit-cms/src/common/templates/form/widget-block-list.hwk:117`. Note the row `name=` attributes are index-derived (`name + "." + idx + ".permission"`) — keying must not break that transport.

**Tests** `/home/skerit/projects/javaweb/plumage/src/browserTest/java/be/elevenways/plumage/test/PermissionsEditorTest.java` (`editorAddsRemovesAndNamesItsRows:17`, `extraColumnsHydrateNameAndPickThroughTheProvider:49`), fixture `/home/skerit/projects/javaweb/plumage/src/browserTest/templates/test/permissions-editor-test.hwk`, showcase `/home/skerit/projects/javaweb/plumage/src/browserTest/java/be/elevenways/plumage/showcase/PermissionsShowcase.java`.

---

### F10. Proteus duplicates the generic permissions editor — **REAL (state + template are near-verbatim clones)**

**The duplicate**
- `/home/skerit/projects/javaweb/proteus/src/common/java/be/elevenways/proteus/common/permission/PermissionsEditState.java` vs `/home/skerit/projects/javaweb/zenit-forms/src/common/java/be/elevenways/zenit/forms/common/render/PermissionsEditState.java` — diff is **package, imports, javadoc, `@since` only**. Identical record components and compact constructor.
- `/home/skerit/projects/javaweb/proteus/src/common/templates/form/permissions-edit.hwk` (16 lines) vs `/home/skerit/projects/javaweb/zenit-forms/src/common/templates/form/permissions-edit.hwk` (21 lines) — differences: generic version adds the `{path}.__present` hidden marker (`:8`) and uses `ZenitForms.text(...)` where proteus uses `Cms.text(...)`.
- Registration: `/home/skerit/projects/javaweb/proteus/src/common/java/be/elevenways/proteus/common/permission/ProteusFormRenderers.java` (77 lines) vs `/home/skerit/projects/javaweb/zenit-auth/src/common/java/be/elevenways/zenit/auth/cms/AuthFormRenderers.java` (56 lines).

**What is genuinely proteus-specific** `/home/skerit/projects/javaweb/proteus/src/common/java/be/elevenways/proteus/common/permission/PermissionsEditField.java` is *not* a clone of `GrantsEditField`: it is `Field<?,?>`-backed (column storage, DRY-encoded entry list matching the backing `TextField`) and adds the realm-scope pick column (`REALMS_COLUMN = "realms"`, `:27-28`), whereas `/home/skerit/projects/javaweb/zenit-auth/src/common/java/be/elevenways/zenit/auth/cms/GrantsEditField.java` is table-backed (`auth_grants`) with the `__present` marker. The realm column is expressible through the generic extra-column seam (`/home/skerit/projects/javaweb/plumage/src/common/java/be/elevenways/plumage/component/PickExtraColumn.java`, `PermissionExtraColumn.java`, `TextExtraColumn.java`, `BooleanExtraColumn.java`). So: delete proteus's `PermissionsEditState` + `permissions-edit.hwk`, keep the field, repoint it at `zenit-forms`' state/template.

**Tests** `/home/skerit/projects/javaweb/proteus/src/test/java/be/elevenways/proteus/PermissionsEditorTest.java` (120 lines) must keep passing; registration precedent in `/home/skerit/projects/javaweb/resources/shortlinker-port/03-port-precedent.md:506` (`ProteusFormRenderers.register()` in the test boot).

---

### F11. `PathProbe.HOLDER` is a hand-written platform holder — **REAL** (note: it lives in **zenit**, not protoblast; `PlatformSeam` is the protoblast side)

**Evidence** `/home/skerit/projects/javaweb/zenit/src/common/java/be/elevenways/zenit/common/validation/PathProbe.java:17,28-50` — `Holder HOLDER = new Holder();` plus a hand-rolled `final class Holder { private PathProbe probe = new PathProbe(){}; get(); install(); }` and static `get()`/`install()` wrappers.

**Target mechanism** `/home/skerit/projects/javaweb/protoblast/src/main/java/be/elevenways/protoblast/common/platform/PlatformSeam.java:46` `withDefault(Class<T>, T)` (`require()` never throws; `:71-95`). Exact precedents to copy: `/home/skerit/projects/javaweb/zenit/src/common/java/be/elevenways/zenit/common/time/ZoneClock.java:21` and `/home/skerit/projects/javaweb/plumage/src/common/java/be/elevenways/plumage/component/DateBridge.java:21`.

**Call sites to migrate** `/home/skerit/projects/javaweb/zenit/src/common/java/be/elevenways/zenit/common/validation/validator/FilesystemPath.java:68,74` (`PathProbe.get().isAbsolute/probe`), `/home/skerit/projects/javaweb/zenit/src/common/java/be/elevenways/zenit/common/orm/field/PathField.java:21,94`, self-install `/home/skerit/projects/javaweb/zenit/src/server/java/be/elevenways/zenit/server/validation/JvmPathProbe.java:18-29` (`@ZenitAutoLoad` + `public static final boolean LOADED = install();` — already the seam convention, just repoint `PathProbe.install` → `SEAM.install`).

**Tests** `/home/skerit/projects/javaweb/zenit/src/test/java/be/elevenways/zenit/edit/PathFieldTest.java:37` installs a fake probe (must be updated); seam semantics pinned by `/home/skerit/projects/javaweb/protoblast/src/test/java/be/elevenways/protoblast/common/platform/PlatformSeamTest.java:65,79`.

---

### F12. Stale documentation — **REAL, all four**

1. `/home/skerit/projects/javaweb/zenit-cms/CLAUDE.md:228` — "The delete form binds `CmsConfirm.interceptSubmit`". **`interceptSubmit` does not exist**: `CmsConfirmFunctions` exposes only the three directives `CmsConfirm.confirm` / `.ask` / `.destructive` (`:72-110`) and the functions `perform` (`:149`) / `confirmation` (`:233`).
2. `/home/skerit/projects/javaweb/zenit-cms/CLAUDE.md:252` — same dead API plus a second wrong shape: "Click-carriers … call `CmsConfirm.ask(confirmation, e.target)`". Current `ask` is an **event directive** `ask(DominoElement element, Microcopy body, DominoEvent event)` (`:94`) taking a `Microcopy`, not a `ConfirmationState`, and no `e.target`. (The `data-cms-confirm-replay` prose is still accurate: `REPLAY_MARKER` at `:61`.)
3. `/home/skerit/projects/javaweb/zenit-flow/CLAUDE.md:110` — "coerce with `"" + x` before passing to a String-typed function parameter". Directly contradicted by `/home/skerit/projects/javaweb/zenit-cms/CLAUDE.md:510` ("never `"" + i`"). Replace with `String.valueOf`.
4. `/home/skerit/projects/javaweb/resources/shortlinker-port/03-port-precedent.md` — three separate deletions:
   - `:277-279` `TerminalFunctions.setBridge(new BrowserTerminalBridge()); SortableFunctions.setBridge(...); TableSelectionFunctions.setBridge(...)`. **No `setBridge` exists anywhere in `plumage/src`**; bridges are `PlatformSeam` self-installers (`/home/skerit/projects/javaweb/plumage/src/common/java/be/elevenways/plumage/component/TerminalBridge.java:18` — "Nothing installs it by hand").
   - `:351` `@Override public List<PanelPeer> peers()`. `Panel.peers()` is **`final`** (`/home/skerit/projects/javaweb/zenit-cms/src/common/java/be/elevenways/zenit/cms/common/panel/Panel.java:89`); the override point is `protected abstract buildPeers()` (`:82`). This example does not compile.
   - `:422,424` `<script src="/cms.js" defer></script>` and `<body onload="main()">` inside a `.hwk` example — both now hard compile errors (`CODE_SCRIPT_ELEMENT`, `CODE_INLINE_EVENT_ATTRIBUTE`; cf. `InlineHandlerDiagnosticTest.aBodyOnloadFailsTheBuildByDefault:46`, `aScriptElementFailsTheBuildByDefault:64`).
5. `/home/skerit/projects/javaweb/zenit-cms/src/common/templates/pages/settings.hwk:10` — **REAL but minor**: header comment says "Reset-to-default is a plain checkbox staging the `__clear`", while the markup is a `pl-checkbox` + `pl-label` pair whose own AIDEV-NOTE (`:103-104`) insists "this is a real pl-checkbox, not a label wrapping a hidden input". Reword when F8 changes the control anyway.

---

### F13. `Action.create/run/provide` production consumers — **facts (confirms the claim)**

Workspace-wide grep for `Action.create|Action.run|Action.provide` (excluding `build/`, `target/`) returns **only**:
- `/home/skerit/projects/javaweb/hawkeye/hawkeye-core/src/browserTest/resources/templates/action-state-test.hwk:52,53,57` (browser-test fixture)
- `/home/skerit/projects/javaweb/plumage/src/browserTest/templates/test/button-test.hwk:109,110,114` (+ prose at `:68,83`)
- definition site `/home/skerit/projects/javaweb/hawkeye/hawkeye-core/src/common/java/be/elevenways/hawkeye/common/ActionFunctions.java`
- two comment-only mentions: `VariableUsageAnalyzer.java:798`, `FormActionFunctions.java:270`

**Important nuance for the decision:** the *statekey half* of the mechanism **is** production-wired independently of `Action.create`. `ActionFunctions.PENDING_STATE_KEY = "hawkeye.action.pending"` (`:37`) is published by the form-action lane at `/home/skerit/projects/javaweb/hawkeye/hawkeye-core/src/common/java/be/elevenways/hawkeye/common/form/FormActionSubmission.java:149`, and consumed by a real component: `/home/skerit/projects/javaweb/plumage/src/common/templates/components/button.hwk:215` (`public state hawkeye.action.pending as actionPending`) → `:221` `public computed busy = loading{:} or actionPending{:}`. So the gap is specifically `Action.create` / `Action.run` / `Action.provide` (the explicit imperative lane), not the pending-state contract.

---

### F14. What explicit `RecordSource` registration drops — **facts**

Derived path: `/home/skerit/projects/javaweb/zenit-cms/src/server/java/be/elevenways/zenit/cms/server/page/CmsRecordSources.java`, `registerFor:69-130`. The facets an explicit `RecordSourceRegistry.INSTANCE.register(...)` does **not** inherit:

| Facet | Line | Consequence if dropped |
|---|---|---|
| `.search()` over schema display fields | `:107` | chooser search dies (guarded by the display-field precondition at `:82-90`) |
| `.editUrl(row -> ResourcePageEndpoints.detailUrl(panel, resource, pk))` | `:108-109` | relation cells / chooser lose the edit link |
| `.permission(peerPermission)` — `resource.requiredPermission()` else `panel.accessPermission()` (`peerPermission:190-193`) | `:110` | **security**: view gate |
| `.accessCriteria(...)` — the resource's `accessFunction().decide(ctx)`, `model.matchNone()` on deny, else `decision.predicate().criteria()` | `:111-118` | **security**: per-row scoping silently widens |
| `.icon(resource.icon().name())` | `:120-122` | cosmetic |
| inline-create provider: `createSpec` = `InlineCreateForms.reduceSpec(resource.formSpec().forView(CREATE))`, `authorizes` = `!accessFunction().decide(ctx).isDenied()`, `create` = `resource.persistRow(...)`, plus `createPermission` | `:124`, `installCreateProvider:146-188` | **security + functionality**: inline-create affordance gone, or (worse, if the explicit source declares `creatable` differently) a different deny site |

Silence mechanics — `/home/skerit/projects/javaweb/zenit/src/common/java/be/elevenways/zenit/common/data/RecordSourceRegistry.java`:
- explicit-before-glue: `CmsRecordSources.registerFor` returns early at `:73` (`byId(modelId) != null`), so the derived source is never even built — **no log at all**.
- explicit-after-glue: `registerAt:83-114` replaces the derived entry at `:113` (`this.entries.set(index, new Entry(source, false))`) — **no log, no diff**.
- The only refusals are "no access declaration" (`:87-93`, `zenit.data.source_undeclared`) and explicit-shadows-explicit (`:108-112`, `zenit.data.source_shadowing_refused`, escape hatch `override:81`). Neither fires here.
- Registration ranks documented `:17-24`, `:58-73`.

**Tests** `/home/skerit/projects/javaweb/zenit-cms/src/test/java/be/elevenways/zenit/cms/test/page/CmsRecordSourcesInlineCreateTest.java`; also `zenit-forms/src/test/java/be/elevenways/zenit/forms/test/render/RelationPickTranslationTest.java`, `RelationMultiPickTranslationTest.java`, `choose/InlineCreateEndpointsTest.java`. Test seam `CmsRecordSources.resetForTests()` at `:65`.

---

### F15. Remaining string-concat coercion in templates — **REAL but nearly closed (2 hits, neither production)**

Grep `""[[:space:]]*+` / `+[[:space:]]*""` across all `.hwk` (excluding `build/`):

1. `/home/skerit/projects/javaweb/alchemy/alchemy-form/view/form/inputs/view_inline/boolean.hwk:18` — `"" + value` inside a `{%t ... %}` legacy Alchemy translation block. **External/legacy tree, not one of the eight in-scope repos** → out of scope per the ledger's own "do not edit external/legacy trees without confirming they are in scope".
2. `/home/skerit/projects/javaweb/hawkeye/hawkeye-core/src/browserTest/resources/templates/tag-child-host.hwk:102` — `mountref = mountref + ""` inside `@watch(open)`. This is **not** a type coercion: `mountref` is already `attribute mountref: String = "unset"` (`:85`), so it is a self-assignment used as a re-trigger. Asserted by `/home/skerit/projects/javaweb/hawkeye/hawkeye-core/src/browserTest/java/be/elevenways/hawkeye/browser/TagChildHydrationTest.java:78,84` and `ComboboxPortHydrationTest.java:30`. Judge on its own merits (it is not the coercion syntax the rule targets); if kept, it deserves a comment saying so.

No production `.hwk` occurrences remain. Two **doc** occurrences remain and are covered by F12: `/home/skerit/projects/javaweb/zenit-flow/CLAUDE.md:110` (teaches it) and `/home/skerit/projects/javaweb/zenit-cms/CLAUDE.md:510` (forbids it — keep).

---

## Cross-cutting mechanism map for implementers

| Need | Use | Anchor |
|---|---|---|
| Teardown next to setup (F6, F7) | `Cleanup.on(owner, fn)` / keyed `Cleanup.on(owner, key, fn)` | `hawkeye/hawkeye-core/src/common/java/be/elevenways/hawkeye/common/CleanupFunctions.java:38,77`; `CustomElement.registerDisposer` `:229,265` |
| Stable row identity (F9) | `key <expr>` on `each` + `__stableId` UUID seeding | `zenit-forms/.../ZenitFormsFunctions.java:159`; `zenit-forms/src/common/templates/form/zf-array.hwk:48` |
| Platform default seam (F11) | `PlatformSeam.withDefault` | `protoblast/.../platform/PlatformSeam.java:46`; models `ZoneClock.java:21`, `DateBridge.java:21` |
| Source markers (F3) | `IRTranspiler.emitSourceMarker(unit)` after `useMethodBuilder` | `IRTranspiler.java:1145`; scanner `SourceMapBuilder.java:26-78` |
| Authored-vs-directive state (F1) | marker attribute pattern already in `ListDirectives` | `ListDirectives.java:214` |
| Namespace-aware advisor rule (F2) | copy `!"attr".equals(namespace)` from the retired rule | `TemplateWiringAdvisor.java:505-509` |