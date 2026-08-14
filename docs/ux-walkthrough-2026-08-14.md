# UX walkthrough, 2026-08-14

An evidence pass over the REAL panel, not a reading of the templates: fresh dev
database, the server booted via `zenit-dev start`, every journey driven with a
headless browser at 1440x900, screenshots at each step. This answers the owner's
question "is it a useful thing, or a dump of menu items and plain form views" with
observations, click counts and defects -- not taste.

Method: first-run setup, dashboard, instance create (docker kind, nginx:alpine),
deploy attempt, host preflight + admission, instance overview / console / stats /
files tabs, server create, the new Install media tab, settings, and the generated
lists. The /manage tenant panel and live Incus journeys were NOT walked (no tenant
fixture, no local Incus daemon); that half remains open.

Two product defects found by this walkthrough were fixed the same day (commit
`39e4418c`): the Install media tab 500'd on an un-enrolled Incus host instead of
naming the trust refusal, and its H1 rendered the literal `page_title` key.

## The one-sentence answer

The pages are better than the navigation suggests: the bespoke surfaces (server
overview, instance overview, console, settings) are genuinely designed, honest and
few-click -- but the panel currently WELCOMES you with its two weakest surfaces
(a 40-item flat sidebar and a noise-dominated dashboard), and its worst habit is
refusing invisibly: the most important failure of the first session (deploy
refused because the host is not admitted) produces no durable explanation
anywhere the operator is looking.

## Journeys and click counts

| Journey | Interactions | Verdict |
| --- | --- | --- |
| First run -> logged-in admin | 3 fields + 1 click | Excellent. `/admin` redirects to `/setup`, one card, lands on the dashboard. |
| Create a docker instance | 2 clicks + 3 fields + 2 picker clicks + Save | Good. The kind-switched settings sub-form appears instantly, per-field help is real. |
| Deploy it | 1 click -- then a hidden dead end | The refusal (`host_not_admitted`) is a transient toast at best; the page keeps offering Deploy and stays "Created" with no stated reason. |
| Admit the host (the actual prerequisite) | 4 clicks once you KNOW (Servers -> local -> Preflight -> Admit) | The preflight report page is the best surface in the product. Nothing routes you to it. |
| Attach install media (new this wave) | Server tab: 2 fields + Fetch; instance Devices: 1 click + form | Fine; refusals are named and land as flashes on the tab. |

## Defects (ranked)

1. **Instance record tab bar overflows and overlaps.** An instance detail has 14
   tabs (Edit, Overview, Console, Provisioning, Files, Stats, Exec, Snapshots,
   Backups, Schedules, Migrate, History, Access, ...) and at 1440px they render on
   one unwrapped line, labels colliding into an unreadable strip
   ("Instance snaps□fastance back□Schedules□Migrate to hos□HistoryAcces"). Every
   instance page carries it. Needs an overflow mechanism (scroll with fade,
   priority+More menu, or grouping).
2. **Refusals do not land on the page.** The deploy row action's
   `host_not_admitted` violation renders (at most) a toast that is gone before it
   is understood; the overview afterwards looks IDENTICAL to before the click.
   The product's own doctrine ("a failure state an operator must find without
   looking lands on an attention item") stops at the panel edge here. The
   overview's State card should state the blocking condition ("host `local` is
   not admitted -- preflight and admit it") with the link, the same way the
   server overview already explains "never preflighted".
3. **The first-workload path is undiscoverable.** The dashboard's only onboarding
   is "Create your first site"; nothing anywhere says a host must be preflighted
   and admitted before any instance deploys. Combined with defect 2, the first
   session's natural arc (create instance -> deploy -> silence) has no visible
   way forward. An onboarding checklist (host -> preflight -> admit -> create ->
   deploy) on the dashboard would close this and would have made the walkthrough's
   own detour unnecessary.
4. **The instance Kind picker offers kinds that only refuse.** "Site container",
   "Stack service" and "Database container" are `generatedOnly` kinds -- every
   hand-made create is refused by the OwnedInstances write guard -- yet the
   create form's select offers them beside the real choices. The same
   affordance-that-can-only-refuse shape the 2026-08-14 authorization wave
   removed elsewhere; derive the create options from `generatedOnly()`.
5. **Error pages are dead ends.** 404 and 500 render the framework's unbranded
   dark card with no navigation back into the panel, regardless of theme; the 500
   ships a full Java stack trace to the browser. Acceptable for a dev tool,
   wrong for the product's own admin surface.
6. **A client-side render error fires on the setup -> admin transition.**
   Console: `Client-side render failed ... TypeError: Cannot read properties of
   null (reading 'bcb')`, after which navigation recovers with a full render.
   Invisible to the user this time, but it is a real hydration defect worth a
   look (reproducible: complete `/setup`, follow to `/admin`).

## Friction (not broken, but costs every session)

- **The sidebar is a 40-item flat list ordered by module, not by use.** The
  spamservice module contributes eight items to SECURITY at the very top;
  COMPUTE (the reason this product exists now) starts below the fold on a
  1440x900 screen. No collapsing, no pinning. This is the one place the owner's
  "dump of menu items" fear is currently TRUE.
- **The dashboard spends its space on noise.** An empty 30-day bans chart
  occupies ~450 vertical px; Recent activity is dominated by system churn
  ("Updated - System task history" x5) and rows without record names
  ("Created - User / User"). The genuinely useful parts (attention items, stat
  tiles, the site CTA) fit in the first third.
- **After creating a record you land back on its edit form**, with the generic
  toast "The record has been created." The natural next step (overview, deploy)
  is neither taken nor offered. Create should land on the overview for records
  that have one.
- **Generated lists ship a redundant generic empty state** ("Nothing here yet" /
  "There is nothing here yet." / "No records" -- three spellings of nothing, no
  guidance), while the bespoke surfaces show what good looks like (console:
  "Not running -- deploy the instance to attach to its console").
- **The setup card is unbranded** ("Initialize application", "Create
  superuser") -- harmless, but it is the first screen a new operator ever sees
  and it speaks dev-tool, not product.

## What is genuinely good (keep, and imitate elsewhere)

- **The server overview is the model page.** State badges (runtime, Blocked,
  Trusted only, probe age), a per-check preflight report with pass/warn/fail and
  the real detail ("nft add table refused: sudo: a password is required"),
  honest capacity prose ("a zero bar here would wrongly read as an empty host"),
  and the workloads table. The admission journey is two clicks with evidence.
- **Honest state everywhere the page is bespoke**: root disk "Not measured" with
  the reason, "No published port" with what it means, console and stats empty
  states that say what to do.
- **The create forms are real forms**: kind-switched settings appear client-side
  instantly, every field carries help text written by someone who operates the
  thing, validation is inline and named (SSH target format).
- **Settings is the VSCode shape** with search-first navigation and per-key
  provenance -- better than both Proxmox's and Pterodactyl's equivalents.
- **One design system throughout**, light/dark/auto, uniform confirmations with
  destructive styling and typed confirmation for data-losing acts.

## Against the neighbours, honestly

Proxmox never shows you a broken tab bar, but it also never explains a refusal
as well as the preflight report does; its VM wizard prevents the "deploy refused
after the fact" class by making you pick a (working) node first -- that ordering
is worth stealing. Pterodactyl's first-node setup has exactly the same hidden
prerequisite problem this walkthrough hit (its panel/wings pairing is famously
the hard part), and its per-server pages are the polish benchmark: our instance
tabs match its FEATURE set already and lose on the tab chrome itself. Neither
neighbour has anything as good as the preflight report or the settings surface;
both beat the current sidebar and dashboard.

## Suggested order of attack

1. Tab-bar overflow (one component, every record page improves at once).
2. Refusals onto the page: the deploy/`host_not_admitted` case first, as the
   State-card condition + link; audit other row actions whose violations only
   toast.
3. Dashboard: onboarding checklist, curate the activity feed (hide system task
   churn by default, name the records), drop the empty chart until it has data.
4. Sidebar: collapse by group, move spamservice down (or behind its own
   heading), COMPUTE above the fold.
5. Kind picker: exclude `generatedOnly` kinds from the create form.
6. Branded error pages with a way back; keep stack traces out of the browser.

## Scope not covered (open)

- The /manage tenant panel (needs a tenant fixture walk: delegated visibility,
  the narrowed forms, the refusal tone for a view-only delegate).
- Live Incus journeys (framebuffer console, ISO install end-to-end, capture) --
  these need daystrom or an enrolled host; the daemon-truth pages were only seen
  in their refusal states here.
- Mobile/narrow viewports; keyboard-only operation; screen-reader semantics.

Screenshots for every step of this walkthrough were captured; the annotated set
accompanies this document outside the repository (they are session artifacts,
not tracked evidence).
