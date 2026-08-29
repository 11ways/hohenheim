# Visual QA 2026-08-29, wave 3: deploy and live re-verification

Third remediation wave after the 2026-08-27 QA pass. Fixes were landed by
repo, deployed to starfleet from a clean secondary workspace, and re-verified
on the live panel with the Sketerm browser tools (headless, 1440x900, profile
`visual-qa-admin`, signed out and the profile erased afterwards).

## What was deployed

hohenheim `871275c5` (previously `5a030dcb`), built 13/13 clean:

| Repo | Sha | What this wave changed |
| --- | --- | --- |
| protoblast | `c202f6a` | boot registrations can `require` classes to be present before they link |
| hawkeye | `9552aee0` | the template-boot index derives those requirements from each template's imports (bridge version 5) |
| zenit-cms | `cc7bdff` | `Labels.inSentence`: resource labels composed mid-sentence in their sentence spelling (`case: sentence` variant) |
| zenit-auth | `94149fd` | the browser-test bundle is cms-less again (workaround reverted); users/roles sentence variants |
| hohenheim | `17c32501`, `871275c5` | runtime-tier deletes cascade or refuse at the model funnel; onboarding host step; 96+96 sentence-form label variants with a drift test |

Repos unchanged since the previous deploy but carried: zenit `63cc9290`,
plumage `1af1495`, zenit-forms `07e0fdd`, zenit-widget `4ba1478`,
zenit-media `47eb2f7`, zenit-microcopy `998e590`, zenit-comms `65b352d`
(inbox paging, first time on the box), spamservice `23f1f18`.

Lane: `docs/deploy-starfleet.md`, entry "Deploy 2026-08-29 (second)".
Migration diff empty; rehearsal 0 applied; two restarts; `zd_deployed`
current for all 13 repos.

## Settings changed on the host

| File | Change | Effect |
| --- | --- | --- |
| `/opt/hohenheim/settings/local.dry` | `brand.name = Hohenheim` | login card and shell brand read "Hohenheim" (was "Zenit") |
| `/opt/hohenheim/settings/local.dry` | `activity.enabled = true` | model writes are recorded; the Activity page and dashboard show them |
| `/opt/hohenheim/settings/hohenheim.dry` | `roles.processes` removed | the `settings.unknown_key` line for it is gone from boot |

Not changed, reported: `local.dry` carries a root-level `security` block
(`never_ban` with two hostnames, `nftables_enabled`) that the runtime
logs as `settings.unknown_key key=security` and ignores; hohenheim's
security group is `hohenheim.security` in `hohenheim.dry`. Whether hostnames
belong in `never_ban` at all is a decision, so the block was left as it was.

## Verified live

| Item | Observed |
| --- | --- |
| Login card brand | "Hohenheim" above "Sign in" (screenshot) |
| Empty-state copy | Stacks: "No stacks yet"; Databases: "No databases yet"; Git providers: "No Git providers yet" (acronym kept); after cleanup also "No projects yet", "No environments yet", "No notification channels yet", "No released hostnames yet" |
| Create titles | document titles "New stack - Stacks", "New database - Databases", "New Git provider - Git providers"; heading "New stack" (screenshot) |
| Onboarding "Enrol a host" | `data-state=todo`, plain server icon, copy "A host is enrolled but not admitted yet, so nothing can run on it."; the next step is `blocked` with the warning icon (screenshot) |
| Activity recording | `/admin/activity` lists `create hohenheim:project 4` and `delete hohenheim:project 4` by Jelle De Loecker, origin web, plus the login and the boot-time seed updates; dashboard Recent activity shows the same |
| Account sessions soft navigation | Revoke swaps the body to "Revoke this session?" inside the same document (no reload), Cancel swaps back to the list |
| Certificate delete dialog | on the real `starfleet.life` certificate: "Delete the certificate starfleet.life? Its private key is destroyed and these names stop serving HTTPS until another certificate covers them: starfleet.life, www.starfleet.life." Cancelled; focus returned to the row menu (screenshot) |
| Access-list delete dialog | on the disposable list: "Delete the access list visual-qa-20260825-r2-acl? Its 0 rules go with it. Nothing is gated by it right now." Confirmed |
| Environment delete dialog | "Delete this environment? An environment that still holds variables or workloads is refused, so move or remove those first." |
| Notification channel delete dialog | "Delete this channel? The events it delivers stop being sent, with nothing left to report the gap." |
| Project delete dialog | "Delete this project? Its membership group and access grants are removed with it. A project still owning records or environments is refused." The managed role went with the project (Roles list has no QA row) |
| DNS record delete dialog | "Delete visual-qa-20260825-r2.starfleet.life A 192.0.2.26 from zone starfleet.life? Authoritative answers change immediately. This cannot be undone." Confirmed; 8 records remain (6 A, 2 NS), apex/NS/SOA untouched |
| Dashboard stat tiles | Sites 2, Certificates 2, Access lists 0, Active bans 8 after cleanup |

## Untested

- Dutch copy: `/nl/...` answers 404 (content locales are `en` only on starfleet), so the
  Dutch sentence-form variants are pinned by the catalog drift test only.
- Certificate delete cascade (the pin on `site_domain.certificate_id` is cleared): only the
  dialog was opened; never on a real certificate. Pinned by `RuntimeCascadeTest`.
- Runtime-image and instance-template dead-with-reason deletes, stack/service cascades,
  database in-use refusals: no such records exist on starfleet. Pinned by `RuntimeCascadeTest`.
- Environment variable delete still reads "Delete VISUAL_QA_R2? This cannot be undone." --
  the generic dialog; a variable has no dependents, so the wording is complete, but it is
  the one remaining generic sentence on a hohenheim resource.
- Everything the earlier reports list as needing an admitted host or narrow identities.

## Cleanup ledger

| Record | Origin | Result |
| --- | --- | --- |
| project `visual-qa-20260829b-project` (id 4) | this pass | created, deleted; both writes in Activity |
| access list `visual-qa-20260825-r2-acl` (id 2) | 08-25 leftover | deleted |
| notification channel `visual-qa-20260825-r2-channel` | 08-25 leftover | deleted |
| DNS record `visual-qa-20260825-r2 A 192.0.2.26` | 08-25 leftover | deleted |
| environment variable `VISUAL_QA_R2` (id 1) | 08-25 leftover | deleted |
| environment `visual-qa-20260825-r2-env` (id 2) | 08-25 leftover | deleted after its variable |
| project `visual-qa-20260825-r2-project` (id 2) | 08-25 leftover | deleted after its environment; its managed role cascaded |
| released hostnames | -- | none created, list empty |

Lists after cleanup: Projects 0, Environments 0, Environment variables 0,
Access lists 0, Notification channels 0, Roles no QA row, Released
hostnames 0. Sessions: signed out through the user menu; browser views
closed; profile `visual-qa-admin` erased.

## Rollback

`/root/hohenheim-preflight-20260829-093220/` on starfleet: previous jar as
`hohenheim-server.jar.rollback`, `hohenheim.db.pre` and `hohenheim.db.atswap`
(both `PRAGMA integrity_check` ok, 41 migrations), `settings/` as they were
before the three edits, keyring sha256-matched.
