# Visual QA remediation - 2026-08-28

Remediation of the findings in `visual-qa-20260827.md`, framework-first: a defect
whose cause lives in a zenit-* module was fixed there once, with a red-then-green
test in the owning repo, and hohenheim only carries its own wiring. Nothing is
committed; every repo below holds the work in its worktree.

## Findings, by report number

| # | Finding | Owner | Fix | Proof |
| --- | --- | --- | --- | --- |
| 1 | Site Disable/Enable unconfirmed, panel lockout | hohenheim | `toggle_site` carries a per-row confirmation naming the hostnames; Disable/Delete are dead-with-reason on the site serving the panel's own hostname (`panelLockoutReason`, `DeleteImpact.adminHostnameOfSite`); `cordon_server` confirmed | `SitePanelLockoutTest`, `SiteLifecycleTest` |
| 2 | Enter on a row-menu item navigates AND confirms | hawkeye | `Nav.activate` runs a two-phase activation for a form-submitter carrier: the synthetic click's submit behaviour is switched off and `requestSubmit` replays from a fresh green thread after every listener body, so a directive that prevented the click cancels the submit | hawkeye `NavActivateSubmitterTest`; zenit-cms `ResourceListActionsBrowserTest.keyboardActivationOfAConfirmedMenuItemOpensOnlyTheDialog` |
| 3 | Secret env vars cannot be created | hohenheim | Kind = Secret on the create form stores the typed value in the encrypted column | `VariableCarrierAndKindChoiceTest` |
| 4 | 422 re-render loses switches, rows, Advanced state, `?site_id` | zenit-forms / zenit / zenit-cms | Switches and repeatable rows were already fixed upstream (zenit-forms f9974a3, after the deployed build). New: every collapsible section posts its fold state under `{scope}.__sections.{id}` (`FormSection.STATE_KEY`, `zf-form-section` tag) and re-renders as the operator left it; a create form's submit URL keeps the create route's own query (`withCreatingContext`) | zenit-forms `RefusedSubmitRoundTripTest`; zenit-cms `CollapsedSectionRefusedSubmitBrowserTest`, `aCreateFormKeepsItsCreatingQueryOnTheSubmitUrlAcrossARefusedSubmit` |
| 5 | False "changed by someone else" after a refused save | zenit-cms | The refusal lane re-renders from a RE-READ of the record (`storedRecordForRerender`) instead of the in-memory row `updateRow` had already overlaid, so the token, heading, breadcrumb and title all describe what is stored (also fixes #12's refused-value-in-heading) | `aRefusedPersistRerendersTheStoredTokenAndTitleSoTheNextValidSubmitSucceeds` |
| 6 | No cascade on list/site delete; released claim shows an id | hohenheim | `AccessRuleCascades` (rules die with their list and their group); a soft-deleted site's domains are hidden through `liveSiteScope()` (kept on purpose: they are what a restore brings back); released claims show the former site's stored name | `AccessRuleCascadeTest` |
| 7 | Shell scrolls as one document, sticky never engages | plumage | The DOCUMENT is the scrollport by design (hawkeye's soft navigation reads and restores `window.scrollY`); the defect was `pl-app-content`'s non-scrolling `overflow-y: auto`, which captured every sticky descendant. Removed; the sidebar is a sticky viewport-tall self-scrolling panel (`100dvh`). zenit-cms comments and the settings nav `100dvh` updated | plumage `AppShellTest.documentScrollsWhileTheBandSidebarAndStickyContentStayPinned`; zenit-cms `SettingsPageBrowserTest.groupTreeAndSaveBarStayInsideTheViewportAtTheLastGroup` |
| 8 | Test toast leaks `java.net.ConnectException` | zenit-comms + hohenheim | `DeliveryFailure` is the one home of transport failure families (connect / unknown host / timeout / TLS / HTTP status / other) rendered as Microcopy sentences; `NotifyOutcome.reason` is a Microcopy; hohenheim passes a Microcopy fallback so the toast resolves in the viewer's locale. A loopback URL stays accepted (a legitimate local target) | zenit-comms `DeliveryFailureReasonTest` |
| 9 | Clipping / overlap | zenit-cms / hohenheim | List cells and head labels clamp to `--cms-cell-clamp` with an ellipsis and a `title`, so an unbreakable value never widens the table under the pinned Actions column (1440 Host column, "Source"); onboarding rows wrap below `sm` so "Open" never overprints a title at 390. Stat-tile icon lane was already reserved in plumage (2026-08-25) | zenit-cms `ListCellClampBrowserTest` |
| 10 | Focus gaps | zenit-forms / zenit-cms | Path picker claims focus on its filter after the listing arrives and after every directory change (`zf-path-input`); a row-action refresh returns focus to the activating control, else the row, else the list heading (`CmsFocusFunctions`); inline-cell Enter commit now confirms with a success toast (the reopen could not be reproduced) | hohenheim `AdminPagesTest.settingsPageRendersSavesResetsAndRefusesInvalidValues`; zenit-cms `focusReturnsToTheActivatingControlAfterARefresh`, `InlineCellEditBrowserTest.enterCommitsClosesTheEditorAndConfirmsTheWrite` |
| 11 | Return targets after child writes | hohenheim | CMS contract confirmed: UPDATE stays on the record and CREATE lands on the new record, both relaying `_return`; DELETE follows it. The tab pages now bind `_return` on their edit and add links (rules tab, domains tab), so a record's Cancel/Delete returns to the tab | `DomainEditTest`, `RoutedLinkTargetsTest`, `AccessRuleEditorTest` |
| 12 | Rules copy vs behaviour | hohenheim / zenit-cms | Note says a new group starts on; Move up/down are dead-with-reason at the edge; refused value no longer printed in heading/title (#5) | `AccessRuleEditorTest` |
| 13 | Generic confirmations | - | Not changed: the host/environment/channel/rule dialogs still use the generic destructive confirmation. Migrating them to `CmsConfirmations` bodies is a copywriting pass per resource, listed below as open |
| 14 | Copy leaks | zenit / zenit-cms / plumage | Refusals speak the field LABEL ("Name is required"); `match_count` was the empty-locale-chain SSR defect hawkeye fixed on 2026-08-27 (now pinned); "1 result" pluralises through an MF2 selector; `pruneByRetention()` rewritten as operator copy. The login card brand reads `brand.name`, which the starfleet deployment does not set (environment, see below) | zenit `FormValidatorTest`; zenit-cms `settingsSearchStatusResolvesItsCountSentenceAtSsr`; plumage `SelectProviderTest` |
| 15 | Silent session recovery; `_return` lost on failed login | zenit-auth | `LoginReason` (closed vocabulary on `?reason=`) renders "Your session ended. Sign in again to continue." when a presented cookie no longer resolves; the login form action carries the sanitized `_return` through a refused attempt | `AuthFlowIntegrationTest` |
| 16 | Dashboard omits reconcile findings | hohenheim | One informational row per host with foreign Docker resources, counting them and linking to Reconcile findings; orphans and collisions stay warnings | `DockerReconcilerTest.storeReplacesPerServerAndAttentionSurfacesOnlyAlarms` |
| Low | Assorted | hohenheim / zenit-auth / zenit-cms / hawkeye | Cert "Next attempt" absence sentence follows the auto-renew switch; request page title unified; private-key help says when to leave it blank; "New user" / "New role" headings; password hint on the user form; roles list drops the duplicated Description column; false booleans wear a neutral badge; soft redirects to another URL land at the top; settings search survives a refused save | `AdminPagesTest`, zenit-auth `AuthCmsResourcesIntegrationTest`, zenit-cms `aFalseBooleanCellIsANeutralBadge`, hawkeye `SoftRedirectScrollResetTest` |

## Verification

- hohenheim: run 5 (46/50, the four reds were assertions pinning the pre-fix
  shapes), run 12, run 21, run 31 (11/12) and run 35 (the last red method, green):
  `AdminPagesTest`, `NotificationAdminTest`, `RoutedLinkTargetsTest`,
  `DomainEditTest`, `DockerReconcilerTest`, `AccessRuleEditorTest`,
  `NavigationTest` all pass; wave 1 (`SitePanelLockoutTest`,
  `AccessRuleCascadeTest`, `SiteLifecycleTest`, `VariableCarrierAndKindChoiceTest`)
  proven at run 78. Every run built with `--skip-deps` against the published chain
  because another session's plumage worktree did not compile.
- The browser lane's `:checkBundleSize` task failed at the time of writing and
  was RESOLVED the same evening during the deploy: cms.js measured 5107962 bytes
  raw (+282679 since the 2026-08-25 baseline) and 1211115 gzip (+64694) in an
  honest build from committed state, spamservice measured +284546 from the same
  upstream, so the growth is the framework-wide change this wave landed (typed
  number/money inputs, the form-section state carrier, the keyboard activation
  lane, focus restoration) rather than a new reachability anchor. Both budgets
  were re-baselined to `ceil(measured * 1.05)` and committed (hohenheim
  `b6b2077f`, spamservice `23f1f187`). NOTE: an earlier measurement of 5108666
  taken from the MAIN worktree was inflated by another session's uncommitted
  plumage components and should not be used.
- Framework repos: plumage run 116 (`AppShellTest`, `AccessibilityAuditTest`,
  `SelectProviderTest`), zenit run 108, zenit-forms run 110, zenit-comms run 107,
  zenit-auth runs 123/125, zenit-cms runs 139-148, hawkeye runs 128/130. All
  published to maven-local.

## Still open

- Generic destructive confirmations on host, environment, access list, channel
  and rule deletes (#13): each needs a body naming its consequences, the way the
  site/zone/certificate dialogs do.
- Login card brand on starfleet: `brand.name` is set in the repo's
  `settings/default.dry` but not in `/opt/hohenheim/settings/local.dry`; set it
  there (environment, not code).
- Telling "signed out from another device" apart from expiry needs a session
  tombstone (`revoked_at`) -- a schema decision, not done.
- Instance picker offered for every site kind; no disabled-site cue in the Sites
  list; Access tab heading; "Shared" badge wording; zone Import button colour;
  empty Remark column; build-info blank branch; mobile re-check of the dashboard
  at 390/1024 after the shell change.
- plumage's keyboard lanes (`DropdownMenuTest`, `ContextMenuTest`, `CommandTest`,
  `SelectTest`, `TreeTest`) were not re-run against the new hawkeye activation:
  another session's plumage worktree did not compile at the time.

## Environment findings (unchanged)

All six install roles enabled on starfleet against the documented Proxy+DNS
shape; no wildcard certificate; activity recording off; admin email unconfirmed;
`visual-qa-20260825-r2-*` leftovers.
