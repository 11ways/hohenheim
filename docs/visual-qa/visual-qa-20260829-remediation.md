# Remediation of the 2026-08-28 post-deploy findings

Follow-up to `visual-qa-20260828.md`, which re-tested the live starfleet panel after the
first remediation wave and closed 15 of its 16 findings while opening three new ones. This
document records the second wave: the verification gap the deploy left open, the three new
findings, and the delete confirmations deferred from the first wave.

## 1. The verification gap: plumage's keyboard lanes

The first wave changed hawkeye's `Nav.activate` (a script-dispatched click ran a form
submitter's default action before the confirm directive's green-thread body could prevent
it). Plumage's own components ride that same activation path, but its keyboard lanes were
never run against the change: at the time that worktree carried another session's
uncompilable work.

Run: plumage run #42, all green, nothing changed to make it so.

    AppShellTest 4 · CommandTest 5 · ContextMenuTest 5 · DropdownMenuTest 9
    SelectTest 5 · TreeTest 2

## 2. N2 - a one-word stat-card label ran under the icon (plumage)

`pl-stat-card` reserves the icon's lane with `padding-inline-end` on the label and value
rows, which holds back any text that has somewhere to wrap. A label that is a single word
("Certificates") has no break opportunity, so it overflowed its own padding and ran under
the circle - measured on the live dashboard at 1024 (label text right 585, icon left 570)
and reproduced in the browser at a 170px tile (125.8 vs 113.0).

Fix: `overflow-wrap: break-word` on the label and the value, plus `min-width: 0` on the
value inside its flex row. `break-word` is a last resort, so a label that fits is never
broken.

- `plumage/src/common/templates/components/stat-card.hwk`
- `StatCardTest.aSingleLongWordBreaksInsteadOfRunningUnderTheIcon` (red, then green)

## 3. N3 - a refused directory left the path browser unusable (zenit-forms)

Opening a directory the backend refuses set `failed`, and the failed arm REPLACED the
listing. The operator was left with an alert, no filter to focus (so focus fell to the
body), and no way back except the close button; the refusal also outlived the dialog.

Fix: the refusal now joins the listing instead of replacing it, and closing the dialog
clears it. The `@watch(dialogOpen, when: true)` seeding became a plain `@watch(dialogOpen)`
so the close direction has somewhere to live.

- `zenit-forms/src/common/templates/form/zf-path-input.hwk`
- `zenit-cms` `PathBrowserRefusalBrowserTest` - the journey lives in zenit-cms because
  zenit-forms owns the element but has no browser lane, and the settings page is the
  shipped consumer of exactly this pipeline.

## 4. N1 - account pages did not swap content on soft navigation (zenit-auth)

`/account/sessions` -> Revoke changed the URL and the document title while leaving the
previous page's body on screen. The account pages are framed by `auth/card-frame.hwk`,
which rendered its content inline and declared no `he-block`. Hawkeye's soft navigation
repopulates named block contents only, so there was nothing for it to swap. The frame's own
comment says these pages run without client JS, but they carry plumage custom elements, and
the render engine injects the bundle whenever one is present - so the links do soft
navigate.

The card frame is where the account pages genuinely live: zenit-auth `bff5174` (U-07)
deliberately stopped asking `PageFrames` for the host frame, because zenit-cms answers with
"the shell of the first panel this viewer may open" and a read-only viewer changing their
password met an admin sidebar listing someone else's resources. So the fix belongs in the
card, not in a frame negotiation.

Fix: the card and its exit link moved inside `<he-block block-name="main">` +
`{% block "main" %}`, the shape a standalone page that soft-navigates has to have.

- `zenit-auth/src/common/templates/auth/card-frame.hwk`
- proven in hohenheim's `AccountShellTest`, where the pages actually live and a real admin
  session exists: /account, /account/sessions and the revoke confirmation the sessions list
  links to each anchor `he-block block-name="main"`.

That test had to be repaired first, and its previous content was itself a finding: it still
asserted that the account pages ride the ADMIN SHELL, which zenit-auth reverted on
2026-08-27. Nothing had re-run it since, so a cross-repo behaviour change sat unnoticed in a
green-looking suite.

Two things this uncovered, both pre-existing and NOT fixed here:

- zenit-auth's own browser lane cannot build from CLEAN. Its client fold compiles against
  `zenit-cms-common` as `compileOnly` (Option D1: absent at runtime unless the HOST ships
  zenit-cms), but the `zenitauth` namespace registers every template it owns with the client
  loader, including the CMS-side ones -- so `:generateJavaScript` for its OWN test bundle
  cannot link `CmsConfirmFunctions`. It only stayed green because that task was never re-run
  from scratch; a `--clean` there surfaces it immediately. Adding the cms client to the test
  bundle does not fix it (the generated registrations then collide), so this needs a
  deliberate decision about which templates belong in a cms-less bundle.
- `PageFrames.render` has no production caller left, and `CmsPageFrameProvider` therefore
  answers nobody. The contract is deliberate (a host that wants its own account chrome has
  a seam) but it is currently unreachable, which is worth knowing before someone relies on
  it.

## 5. Finding 13 - the four dialogs that said only "this cannot be undone"

An access list, a host, an environment and a notification channel all reached the generic
CMS delete confirmation. The access list is the one that mattered most, and the first wave
raised its stakes: its rules now genuinely cascade, and a site whose access list is gone
compiles to a null rule tree - which `AccessListGate` treats as ALLOW. Deleting the list
does not break the gate, it silently opens it.

- Access list: names the list, how many rules go with it, and every site and protected path
  that stops being gated (two bodies, gated / not gated).
- Host: the dialog says what the record is - an inventory entry, whose machine and workloads
  are untouched. Separately, its delete is now offered DEAD WITH THE REASON in two cases:
  the local host (previously enforced only at submit, so the button was offered and always
  failed), and a host still named by stored instances, stacks or databases, which would
  otherwise be orphaned. That refusal is the `ProjectGuards` policy one tier down.
- Environment: states the refusal the write funnel already enforces, so the operator learns
  the order of operations from the warning rather than from a violation.
- Notification channel: names the one consequence, which is silent by nature.

Also: an access list could be saved with an empty name and then titled itself after the
model and its id on every surface; `AccessListModel.NAME` is now required.

- `AccessListResource`, `ServerResource`, `EnvironmentResource`,
  `NotificationChannelResource`, `DeleteImpact` (three new request-scoped snapshots),
  `AccessListModel`, both microcopy catalogs
- `DeleteConfirmationTest.thePreviouslyGenericDialogsNameTheirOwnConsequences`

## 6. Verification

| Repo | Run | Result |
| --- | --- | --- |
| plumage | 42 | the five keyboard lanes + AppShellTest green against the new hawkeye activation; the new one-word stat-card test RED (label text right 125.8 vs icon left 113.0) |
| plumage | 45 | StatCardTest 3/3 green after the wrap fix |
| zenit-cms | 47 | PathBrowserRefusalBrowserTest RED on the fixture (a `pl-command-item`'s value is a hydration PROPERTY, never an attribute to select on) |
| zenit-cms | 48 | PathBrowserRefusalBrowserTest green |
| hohenheim | 65 | AccessRuleCascadeTest 2, AccessRuleEditorTest 3 and the original DeleteConfirmationTest green (so the now-required access-list name breaks nothing); the two new tests red on their own fixtures (a `template-id` attribute on the block element, and a host name the boot already owns) |
| hohenheim | 80 | AccountShellTest 1 and DeleteConfirmationTest 2 green |

Two of the fixes are proven structurally rather than by an observed red run, and the reason
is in the diff rather than in a test log: the old path browser's failed arm was an
`{% elseif %}` that EXCLUDED the listing, so "the listing is still rendered" could not have
held; and the old card frame declared no `he-block` at all, so "the frame anchors a named
block" could not have held either. Both are now asserted.

## 7. Second pass: the two structural problems this wave uncovered

**zenit-auth's browser lane builds from clean again.** The cause was ONE line added
2026-08-27 (`7301196`): `auth/cms/user-credentials.hwk:60` carries
`use:CmsConfirm.destructive`, which bakes a zenit-cms class reference into the generated
template. The `zenitauth` namespace registers every template it owns with the client loader
unconditionally (a template with addressable content gets `*=` in the boot index, per
`ProjectCompiler.getTemplateBootIndexLines`), so the bundle cannot LINK without zenit-cms.

The bundle this repo builds is its OWN browser-test bundle, and `blastTeavmJar` feeds it
from `clientRuntimeClasspath` -- which is why declaring the dependency on
`browserTestCommonImplementation` (the obvious guess, and what zenit-cms appears to do)
changes nothing. It is now a `clientRuntimeOnly` entry: `publishModule('client', ...)`
declares zenit-auth-client's POM dependencies by hand and zenit-cms is deliberately not
among them, so this never becomes a published edge. Verified: `OidcLoginBrowserTest` 2/2
green (run 24), where before the lane could not produce a bundle at all.

Cost, and it is not small: the test bundle went 4.7 MB -> 16.5 MB, because zenit-cms brings
the widget/forms/media client stack. Re-baselined with the reason in `teavm-bundle.budget`.
For scale, zenit-cms's own test bundle is 22 MB, so this is now what a CMS host measures.

THE BETTER FIX, deliberately not taken tonight: hawkeye should emit a GUARD rather than `*=`
for a template whose generated code references a class from an optional module -- the
conditional-registration index already supports `guardClass=holder`, and a template that
cannot link without zenit-cms is exactly what it is for. That is a compiler change in a repo
another session was actively committing to all evening, and it changes registration for every
consumer's bundle, so it wants its own wave. Note also that the template's directive is a
DUPLICATE declaration: `AuthUsersResource:507` already declares
`ConfirmationSpec.destructive(mc("set_password_confirm"))` on the same action, with the same
microcopy key.

**`PageFrames.render` has no production caller** and `CmsPageFrameProvider` therefore answers
nobody (zenit-auth `bff5174`/U-07 stopped negotiating). Both are now documented as dormant at
the class level, so a registered provider is not mistaken for a rendered effect.

## 8. Dangling-reference survey of the remaining tiers

Method: every foreign-key column against the models that carry a remove hook. Result -- one
new gap, and it is NOT the runtime tier:

- **Deleting a certificate leaves `site_domain.certificate_id` dangling.** `CertificateModel`
  carries no remove hook at all, and `CertificateStore.loadFromDatabase` selects domains by
  `CERTIFICATE_ID.isNotNull()`, so such a row is loaded and silently maps to nothing. The
  shipped confirmation already promises the right semantics ("the names it covers stop
  serving HTTPS until another certificate covers them") -- so the fix is to CLEAR the
  reference, never to refuse the delete. Not landed here: it needs either a new
  `BelongsTo` on `SiteDomainModel` or a fifth copy of the private `doomedRows(context)`
  idiom this repo already wants unified, and it is the TLS tier of a box that is live
  authoritative DNS and proxy. It is testable (both models exist in the browser-test
  datasource), so it wants a red-then-green test, not a late-night patch.
- **Deleting a stack orphans `stack_deployments` and `stack_services`** (no hook on
  `StackModel`, and no soft delete). Not exercisable here -- no admitted host.
- Everything else is covered: instances (6 hooks), site domains (3), instance databases (3),
  databases (3), sites (2), projects (2), preview deployments (2), instance devices (2), DNS
  records (2), access rules and lists (2 each, this wave), protected paths, permission
  groups, instance variables, environments, dyndns credentials.

## 9. Deliberately not changed

The generated list at `/admin/access-rules` heads its empty state "No Rules yet" while the
record tab says "No rules yet". The generated one composes the resource's plural label into
zenit-cms's `"No {$name} yet"`, and labels are Title Case. There is no safe mechanical
casing rule (an acronym label must keep its case, and Dutch capitalises differently), so
fixing it means changing that generic sentence for every application that renders a CMS list
- a copy decision for Jelle, not a drive-by. The record tab's copy is the better of the two
and was left alone.

Surfaced, not fixed: deleting a host used to orphan every instance, stack and database row
naming it. This wave refuses that delete instead, but the same shape may exist elsewhere in
the runtime tier, which no test environment here can exercise.
