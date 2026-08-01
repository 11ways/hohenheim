# F12 / F9 frontend items — report

All three items done, plus the optional focus/caret assertion. Four commits across
three repos. Every behaviour change carries a recorded pre-fix failure.

| Repo | Commit | Subject |
| --- | --- | --- |
| orcono | `f6e37563fe9897c198550f4b80f2cc0b3d7a92eb` | 🐛 Guard the save status write with the disposal tombstone |
| hawkeye | `d8e4294ec89859e5faa2dca0bfe0049a3fc17fbf` | ✅ Put the reactive disabled proof where the directive claims it |
| hawkeye | `7e9ada1ec3f7ee1538752557bd47583b6fe1179b` | 📝 Document both directions of the disabled-attribute erasure |
| plumage | `fa5138af08e44adc70fbd3e73232d808fd1c92ad` | ✅ Assert focus and caret survive the keyed row reconcile |

Every subject verified with `git log -1` to stand alone on its own line, under 72 chars.
Nothing else was staged: other agents were concurrently editing `hawkeye-core/build.gradle`
(left untouched, still dirty) and the whole locale-fold sweep across protoblast/zenit/
zenit-microcopy.

---

## ITEM 1 (F12) — orcono save-failure lane wrote into a detached page

### Re-verification (not re-derived)

Re-read `EditorSession.java` end to end at orcono `ecd8707`. The recon's ten-path sweep
holds: `save()`'s catch lane (`:679-681`) was the only completion path reaching the DOM
without consulting `this.disposed`, while the success lane checks it at `:657`.
`showSaveStatus` (`:686-697`) resolves `this.scope.querySelector(saveStatusSelector)` and
writes `setTextContent` + `classList`; `scope` is the session's own `.page-container`,
which soft navigation detaches.

`EditorInitializer.provideMentionResults:245-260` CONFIRMED safe and deliberately left
untouched: Textum's `TypeaheadPlugin` owns the generation tombstone (`requestToken`
`:133`, checked `:249-252`, bumped by `close()` `:334-343`, registered in the plugin's
cleanup list `:253-260`). A `disposed` guard there would be a second mechanism over a
working one.

Guard doctrine held: the fix is the `disposed` tombstone, never `isConnected`. Orcono
client code still contains zero `isConnected` uses.

### The fix

`if (this.disposed) return;` as the first statement of `showSaveStatus`, not at the one
call site — so the lane is closed for every future caller. It carries an AIDEV-NOTE saying
why the check belongs in the method rather than at the call sites.

### Counterfactual (new test, run against UNMODIFIED code)

`EditorSoftNavLifecycleBrowserTest.aSaveThatFailsAfterASoftNavigationNeverWritesInto
TheDetachedPage`. It holds the save XHR in the browser instead of letting it go
(prototype patch on `XMLHttpRequest.prototype.send`, scoped to `/api/pages/` so
navigation still works), soft-navigates away with the request in flight, then dispatches
the network-error event the real transport reports (`BrowserWebResponse.failWith`), which
is what makes `Endpoint.call` throw. A console tap proves the FAILURE lane actually ran
rather than never arriving — otherwise a green run could mean "nothing happened".

Verbatim pre-fix output:

```
── Browser tests (mvp-v01) ──
  EditorSoftNavLifecycleBrowserTest
    ✗ aSaveThatFailsAfterASoftNavigationNeverWritesIntoTheDetachedPage 4766ms
  ✗ 1 of 1 browser tests failed
    EditorSoftNavLifecycleBrowserTest.aSaveThatFailsAfterASoftNavigationNeverWritesIntoTheDetachedPage()
      [step 3: a save that fails after disposal must not write into the detached page]
      java.lang.AssertionError: [step 3: a save that fails after disposal must not write into the detached page]
      Expecting empty but was: "Save failed"
      at be.elevenways.orcono.browsertest.EditorSoftNavLifecycleBrowserTest.aSaveThatFailsAfterASoftNavigationNeverWritesIntoTheDetachedPage(EditorSoftNavLifecycleBrowserTest.java:240)
```

Every earlier step passed pre-fix, so the counterfactual is exact: the save reached the
transport, the container really was detached, and the failure lane really ran — and then
wrote `"Save failed"` into the detached node.

Post-fix, both journeys in the class:

```
── Browser tests (mvp-v01) ──
  EditorSoftNavLifecycleBrowserTest
    ✓ aSaveThatFailsAfterASoftNavigationNeverWritesIntoTheDetachedPage 2437ms
    ✓ softNavigatingBetweenEditorsDisposesEachOneAndNeverTouchesADetachedPage 2919ms
  ✓ 2 browser tests passed
```

---

## ITEM 2 (F9) — the reactive-disabled proof was structurally vacuous

### What was wrong

`directive-list-test.hwk:45` put `disabled={% locked{:} %}` on `use:List.remove`, whose
render half (`ListDirectives.removeRender:162-165`) passes `disabled = false`
unconditionally. `applyRenderState`'s relax branch is `else if (ownership != null)`, so on
a remove control the directive never records ownership and never touches `disabled` at
all. The attribute there is written by the author lane ALONE — the two-writer interaction
the fixture claimed to exercise was structurally excluded, not merely untested.

### What changed

The reactive policy binding moved onto `guard-up` (`use:List.moveUp`, the g1 branch of the
existing `{% if %}`), where the directive genuinely claims the attribute; `guard-remove`
keeps a plain, unbound control. The journey was rewritten as
`reactiveAuthoredDisabledAndBoundaryDisabledShareOneAttribute` (7 steps, one assertion
message per step).

Counterfactual — the NEW journey run against the OLD fixture placement (production code
untouched, only the `.hwk` reverted):

```
── Browser tests (hawkeye) ──
  ListDirectiveBrowserTest
    ✗ reactiveAuthoredDisabledAndBoundaryDisabledShareOneAttribute 1308ms
    ✓ reorderAndRemoveJourney                              783ms
    ListDirectiveBrowserTest.reactiveAuthoredDisabledAndBoundaryDisabledShareOneAttribute()
      [step 1: the boundary never disables a remove control]
      org.opentest4j.AssertionFailedError: [step 1: the boundary never disables a remove control]
      Expecting value to be false but was true
```

With the fixture restored, green:

```
  ListDirectiveBrowserTest
    ✓ reactiveAuthoredDisabledAndBoundaryDisabledShareOneAttribute 1782ms
    ✓ reorderAndRemoveJourney                              1031ms
  ✓ 2 browser tests passed
```

### What the now-live assertion ACTUALLY pins — honestly

It pins the composition that works **and both documented leaks firing**. I did not paper
over them; steps 6 and 7 assert `isFalse()` where a true-ownership implementation would
assert `isTrue()`, with messages naming them as the documented limitation.

- **Step 1** — SSR, `locked = true`, g1 at index 0: policy and boundary both want disabled;
  the directive records `data-list-disabled="author"`. g2's up is enabled; a remove control
  is never boundary-disabled.
- **Step 2** — reorder relaxes g1's boundary: the authored disabled SURVIVES and the
  directive releases its claim (marker gone). This is the composition the mechanism gets
  right, and it is now proven on a control the directive actually claims.
- **Step 3** — unlocking while g1 is a middle row correctly enables it; g2 stays
  boundary-disabled because the policy never bound to it.
- **Step 4** — g1 moves back to index 0 with the policy relaxed, so the marker is now `""`
  (directive-owned outright). This is the precondition for both leaks.
- **Step 5** — re-locking changes nothing observable; both lanes want disabled.
- **Step 6 — LEAK, author erases directive.** Unlocking runs ONLY the attribute lane, whose
  `applyAttribute("disabled", false)` removes the DIRECTIVE's boundary guard. The directive
  does not re-run (the list did not change), so the index-0 move-up control is clickable.
  The test then clicks it and asserts the list order is unchanged — the leak is
  affordance/a11y only; `move()` still range-checks.
- **Step 7 — LEAK, directive erases author.** Re-locking sets `disabled` again but the
  marker still reads directive-owned, so the next boundary relaxation removes the attribute
  outright and a policy-disabled control becomes clickable.

Both leak steps passed on the first run, i.e. the recon's two failure traces are exactly
what the mechanism does. No production behaviour was changed.

---

## ITEM 3 (F9) — the AIDEV-NOTE now documents both directions

`ListDirectives.applyRenderState` keeps its original ownership note and gains a second
one. Exact added text:

```java
// AIDEV-NOTE: one attribute cannot carry two live writers, and the erasure that follows
// is SYMMETRIC -- the compile emits a per-attribute reactive method for the author's
// disabled and a separate one for the directive lane, so neither observes the other:
//   author -> directive: the author's lane reactively REMOVING disabled (locked -> false)
//     erases the DIRECTIVE's boundary guard, and the directive does not re-run until the
//     list next mutates, so a boundary control is clickable in the interim (harmless --
//     the event method still range-checks -- but the affordance/a11y contract is broken).
//   directive -> author: the author's lane reactively ADDING disabled after the directive
//     claimed ownership leaves the marker directive-owned, so the next boundary relaxation
//     removes the attribute and a policy-disabled control becomes clickable.
// Both directions are pinned by hawkeye's ListDirectiveBrowserTest
// (reactiveAuthoredDisabledAndBoundaryDisabledShareOneAttribute). Closing them needs a
// SERIALIZED per-element claim ledger plus an AttributeTranspiler that routes every
// boolean-attribute write through it -- element attachments cannot do it (they do not
// survive SSR, which is why the claim is an attribute here) -- and that collides with the
// guardrail against magic attributes. No production call site authors disabled on one of
// these controls; this is a documented limitation, not a gap to close.
```

The trailing sentence of the ORIGINAL note ("An author lane that reactively ADDS disabled
after the directive claimed ownership is still relaxed with the boundary -- one attribute
cannot carry two live writers") was folded into the new note so the two do not restate each
other. No attribute-ownership mechanism was built.

---

## Focus / caret assertion (the optional item) — DONE, with a caveat

Added as step 6 of `PermissionsEditorTest.keyedRowsSurviveAddAndRemoveWithoutLosing
TheirDomNodes`: focus the surviving row's input, `setSelectionRange(2, 4)`, mutate the
list, then assert `document.activeElement === el`, `selectionStart/End` unchanged, and the
`__probeNode` expando still present — as one string, so a failure names all three at once.

The mutation is dispatched with `el.click()` on purpose and the comment says why: a POINTER
click focuses the button it lands on, so a real `click(...)` could never show focus
survival for ANY implementation. `el.click()` models every non-pointer mutation (keyboard
shortcut, watch, pushed state change) that reconciles the list under a focused input.
It passes.

**Caveat, reported honestly.** The recon expected this assertion to be the only thing
distinguishing "keyed" from "keyed but the `{% if %}` wrapper came back". I ran both
counterfactuals and that expectation is FALSIFIED — the pre-existing probe already catches
both:

- drop `key entry.stableId`: fails at **step 3** (`expected: <bravo-node> but was: <null>`),
  never reaching step 6.
- keep the key, reintroduce `{% if items{:}.size() > 0 %}` around the table: fails at
  **step 3** too, identically.

So the new assertion is not a stronger discriminator than what was there; it is a
genuinely new fact being pinned (focus and caret, which nothing asserted anywhere) and it
is not vacuous — but the claim "only this distinguishes the two implementations" does not
survive contact. Full class green afterwards: 3/3.

---

## Verification performed

- orcono: `zenit-dev test --browser EditorSoftNavLifecycleBrowserTest` — pre-fix FAILED
  (verbatim above), post-fix 2/2 PASSED.
- hawkeye: `zenit-dev test --browser ListDirectiveBrowserTest` — counterfactual FAILED,
  final 2/2 PASSED with all three hawkeye edits in the tree.
- plumage: `zenit-dev test --browser PermissionsEditorTest` — 3/3 PASSED, plus two
  recorded counterfactual failures.
- All runs via `zenit-dev`; no raw `./gradlew`; no build directories deleted.

### Build freshness

Every run above rebuilt the chain (`zenit-dev` printed its dependency phase and compiled
the edited source sets); the counterfactual/green pairs in hawkeye and plumage differ ONLY
in the edited file and produced different results, which is itself proof the artifacts were
not stale.

### Environment friction (for the record, not a defect in this work)

The concurrent locale-fold sweep broke the shared chain repeatedly during this task:
zenit, then zenit-microcopy, failed `checkLocaleFolds`; `HttpConduit.java` had an
ambiguous `Locale` import mid-edit; and a `TeaVmPatchLane` order violation on
`:hawkeye-core:generateJavaScript` made hawkeye browser tests unbuildable for ~1 hour
(another agent fixed it via `hawkeye-core/build.gradle`, which is still dirty and was
deliberately not staged). Every failure was waited out and retried, never worked around.

## Known limitations / follow-ups

- The two `use:List.*` leaks are now PINNED AS CURRENT BEHAVIOUR. If anyone ever builds the
  serialized claim ledger, steps 6 and 7 of
  `reactiveAuthoredDisabledAndBoundaryDisabledShareOneAttribute` are the assertions that
  must flip; the AIDEV-NOTE says so.
- The orcono save-hold probe patches `XMLHttpRequest.prototype` for the page's lifetime.
  It is scoped to `/api/pages/` and installed inside one test method, but it is a global
  patch: another journey added to that class after it would inherit the hold.
