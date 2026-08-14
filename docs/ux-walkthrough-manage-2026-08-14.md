# UX walkthrough: the /manage tenant panel, 2026-08-14

The half the 2026-08-14 admin walkthrough left open ("The /manage tenant panel and
live Incus journeys were NOT walked -- no tenant fixture"). A tenant fixture now
exists and was built through the real UI: a user created in /admin/users, a
one-time password minted with the set-password row action, and a single `view`
grant on one instance handed over on that instance's Access tab. Everything below
was driven headless at 1440x900 against the running app, with screenshots.

Scope walked: login and forced password rotation, the /manage landing, the
instances list, the instance overview / Edit / Provisioning / Stats / Access tabs,
and the certificates list. Not walked: console and file surfaces against a live
container (the demo instance has never deployed), and anything a multi-instance or
site-owning tenant would see.

## The one-sentence answer

The tenant panel is the strongest surface in the product: it scopes correctly, it
refuses without leaking, and its pages explain themselves in plain language -- but
it drops the operator on a contentless link grid, and the one editor it exposes to
a tenant swallows every answer it gives them, success and refusal alike.

## What is genuinely good

- **The scoping is exact.** A tenant holding one `view` grant sees two sidebar
  entries (Instances, Certificates) instead of the admin's 39, one instance in the
  list, and no Server column -- the host name is blanked from the tenant's view of
  the fleet. Nothing had to be asked for; the panel derives it.
- **The write affordances are honest.** A `view`-only tenant's instance row carries
  NO actions at all: no Edit, no Deploy, no Delete. This is the affordance work from
  the authorization wave paying off -- the tenant is never offered a button that
  could only 403.
- **The refusal reaches the tenant, host-free.** The instance overview states "This
  instance cannot start yet / Its host is not accepting new workloads right now.
  Your operator has to clear this." That is the durable on-page refusal AND the
  tenant-safe variant: the admin sees the host name, the tenant sees the condition.
- **The prose is a product, not a form dump.** The root-disk card explains that the
  docker runtime enforces no quota and reports no usage, "the declared contract, not
  a failed reading". The stats page says its readings are live-only and why the
  chart starts empty. The public-endpoint card explains what holding no port claim
  means. A tenant can read these and stop guessing.
- **Forced password rotation explains itself**: "Your password was set by an
  administrator. Choose a new one to continue."
- **Capability-driven tabs work.** Granting Console mid-walk made the Console tab
  appear on the next render; nothing else changed.

## Defects (ranked)

### 1. The record-access page swallows every answer its Save gives

Reproduce: as ADMIN, open `/admin/instances/1/page/access`, set any capability to
Allow, Save. The change persists -- and no toast, banner or inline message appears.
As a TENANT, click Remove on your own row and Save: the row vanishes client-side,
the save is refused server-side, and the row silently returns on the next render
with no explanation.

The server is doing its job. The refusal is a named violation and it is logged:

    cms.record_subpage_submit.rejected subpage zenitauth:record_access
    violations [Violation[path=access, field=access, message=refused]]

The loss is on the way back. It is NOT tenant-specific and NOT refusal-specific:
the admin's SUCCESSFUL save is equally silent. It is specific to this LANE --
a normal resource form Save on the same instance toasts correctly (verified in the
same session: "An image is required before this instance can be deployed").

Already ruled out: `handleRecordSubpageSubmit` does build the toast
(`refusalToast(violations)`); `RecordAccessPageRenderer` does call
`CmsPageContext.putFlashVars`; `record-access.hwk` does render
`zenitcms:flash-toast` inside `block "main"`. So the break is between
`CmsActionResultTranslator.stashFlash` and the redirect render -- the per-tab
bucket (`CmsFlash.PENDING_BY_TAB`, keyed by `Conduit.getTabId()`) disagreeing
between the POST and the follow-up GET on this lane is the first thing to
instrument. The toast is not merely delayed: a later plain reload shows nothing
either, so it is consumed by a render that does not display it.

This is the panel's worst habit -- refusing invisibly -- surviving in the one
editor a tenant is allowed to touch.

### 2. Remove and Save stay live on a row whose every control is disabled

On the tenant's own row all twelve capability selects render `disabled` (correct --
self grant edits are refused, and a disabled select submits no name at all), but
the row's **Remove** button, the **Add user or group...** picker and **Save** are
all enabled and clickable. The same predicate that disabled the selects should
disable Remove. Today the tenant can click Remove, watch their access disappear
from the page, and only discover on reload that nothing happened -- a lying STATE,
which is worse than a lying button.

Note the page itself is correctly offered: the gate is
`isModelAdmin || holdsAnyDelegableCapability`, and a `view` holder may legitimately
delegate `view` to a colleague. It is only the actor's OWN row that is untouchable.

### 3. The landing page is a contentless link grid

`/manage` renders the generic panel index: two cards, "Certificates" and
"Instances", on an otherwise empty page. For a tenant whose entire world is one
instance, that is a wasted click before anything is visible. The admin panel
redirects to a dashboard; /manage has no `DashboardPanelPeer`, so it falls through.
The cards are also alphabetical, putting Certificates (which this tenant has none
of) ahead of Instances (the thing they actually own) -- while the sidebar orders
them COMPUTE-then-PROXY. The two disagree on the same screen.

### 4. "Site management" is the wrong name for an instance tenant

The panel title reads "Site management" for a tenant who has no sites and cannot
get one. The panel projects instances, databases and projects too; the name predates
them.

### 5. The Access grid overflows horizontally with no affordance

Thirteen capability columns (Manage, View, Console, Power, Configure, Destroy, Run
commands, Snapshots, Backups, Run arbitrary images, Read files, Write files) run off
the right edge at 1440px. The columns beyond the fold are reachable only by
scrolling a container that shows no scrollbar hint. The instance TAB strip on the
same page scrolls correctly with end arrows -- this grid needs the same treatment.

### 6. Minor

- The "Edit" tab is offered to a `view`-only tenant. The form correctly renders
  read-only (zero inputs), but the tab is still labelled Edit, promising something
  the tenant cannot do. `RecordTabs.build` adds it first unconditionally.
- The tenant instance row renders an empty overflow-menu element (0 items) because
  every action was filtered out.
- With the access row removed client-side, the 13-column header renders as a bare
  unstyled text row with no table under it.

## Confirmed working from this wave's changes

Both landed changes were observed live in the tenant panel:

- Empty states name their resource: the tenant's certificates list reads **"No
  Certificates yet"** with the resource icon and NO generic second sentence, and no
  create affordance (the tenant cannot create certificates).
- Navigation actions render as buttons: the admin users list shows
  `PL-BUTTON zenitcms:edit` carrying its href, beside its invoke siblings, instead
  of an underlined blue link.

## Suggested order of attack

1. Defect 1 (the swallowed toast) -- it is a framework lane, so it silently affects
   every `RecordScopedPage` that declares `submittable()`, not just this one.
2. Defect 2 (Remove/Save enabled on an untouchable row) -- same predicate, one place.
3. Defect 3 (a tenant landing worth landing on).
4. Defects 4-6 as cleanup.
