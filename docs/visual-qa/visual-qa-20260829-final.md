# Starfleet deploy + live re-verification, 2026-08-29 (final wave)

Closing deploy of the day's work and a read-only-plus-disposables pass over the
`/manage` and zenit-auth fixes on the live panel.

## Deploy facts

- Shipped hohenheim `5d07a5f8` (origin/java-rewrite HEAD at build time; the two
  commits that landed afterwards, `fdd68894` and `7b204f2f`, are the runtime-tier
  QA report only). Previous jar: `a7cb2514`.
- Built in a clean secondary workspace (`~/projects/javaweb-deploy-final`, 15
  detached worktrees at each repo's origin HEAD, own maven-local), 616 s; stamp
  13/13 `dirty=false`; sha256
  `0f2a413645c8ca92602b5d33720b09e2ad4568dacb38dd8defc8617d8b09eef1`.
  Upstream shas: protoblast `c202f6a9`, hawkeye `9552aee0`, zenit `3a0461e5`,
  plumage `0064f897`, zenit-forms `07e0fddf`, zenit-widget `4ba14782`,
  zenit-media `47eb2f74`, zenit-cms `e078ba32`, zenit-comms `65b352d3`,
  zenit-auth `baafb285`, zenit-microcopy `998e5904`, spamservice `23f1f187`.
  Workspace deleted afterwards.
- `zd_status` gates: only proteus and quirkyquarters red (`:checkBundleSize`),
  neither in hohenheim's chain. Migration diff `a7cb2514..HEAD`: empty.
- Preflight `/root/hohenheim-preflight-20260829-final/`: `settings/` copy,
  `hohenheim.db.pre` (integrity ok, 41 migrations), `hohenheim.db.at-swap`
  (integrity ok), keyring sha256 unchanged
  (`414e9b13...5167`), previous jar as `hohenheim-server.jar.rollback`.
- Found and killed a STRAY rehearsal JVM (root, pid 2895859, cwd
  `/root/hohenheim-rehearsal (deleted)`, listening on `*:13999`, 318 MB RSS)
  left by the previous lane; available RAM went 736 -> 1028 MB.
- Rehearsal in `/root/hohenheim-rehearsal-final` on a byte copy (every role
  false, LE/nftables/DNS off, panel 13999, proxy 18080/18443):
  `--build-info` 13/13 clean, `--run-migrations` = `Migrations complete 0 applied`,
  integrity ok, inert boot `/api/health` 200 in 20 s, `/login` 200, `/admin` 302,
  0 exceptions. Directory removed, no JVM left.
- Upload via `upload_file` with `verify_command` requiring 13 `false` stamps
  (verified, atomic). `install -o hohenheim -m 644` + `mv`.
- Restart 1: health 200 in ~8 s; restart 2: health 200 in 21 s. After both:
  `https://admin.starfleet.life/` 302 -> `/login` 200, apex 200,
  `https://comms.starfleet.life/health/ready` 200, `dig +norecurse @104.223.42.142
  starfleet.life SOA` flags `qr aa`, listeners 53/80/443/3000 + 8092 loopback,
  journal 0 errors, `roles_captured enabled=[dns, firewall, proxy]`, no
  `settings.unknown_key`. Herald untouched (`active`).
- `zd_deployed starfleet`: every repo `current`; hohenheim `local-ahead` by the two
  report commits above. No restart pending.
- Note: an HTTPS probe with `--resolve host:443:127.0.0.1` from the box answers
  000; probing the public address works. Do not read the loopback failure as an
  outage.
- Comms coupling survived the restart: `/account` -> "Send a confirmation link"
  answers "A confirmation link was already sent. Wait for it to expire before
  asking for another." (the pending delivery from the herald smoke), not
  "cannot send mail yet"; `comms_deliveries` row 7 still `sent` via `hub`.

## Live verification (build `5d07a5f8`)

Fixtures: site 8 `visual-qa-20260829z-a` (static, `/opt/hohenheim/public/catch-all`),
domain 9 `visual-qa-20260829z-a.starfleet.life` (exact, Exclude-from-LE on),
user 5 `visual-qa-20260829z-site@starfleet.life` with `manage = Allow` on site 8,
user 6 `visual-qa-20260829z-none@starfleet.life` with no grants. Passwords minted
through the admin "Set password" action (one-time, forced rotation on first
login worked for both), kept in the scratchpad only.

| Item | Observed | Result |
| --- | --- | --- |
| Admin adds `admin.starfleet.life` to site 8 | "This route is already claimed by site Hohenheim Admin" (detailed sentence, names the site) | pass |
| Tenant `/manage` sidebar | Overview, Sites, Preview deployments, Certificates, Access lists, DNS records; no instance/stack/database tier | pass |
| Tenant probes `/manage/instances`, `/manage/databases` | 404 (role off); `/manage/git-providers` 200 (proxy role) | pass |
| Tenant probes foreign site `/manage/sites/1` vs nonexistent `/manage/sites/999` | both 404, byte-identical 89-byte bodies; `/admin/*` 403 | pass |
| Tenant Domains tab | "Covered, expires 2 months and 29 days from now"; the certificate name "Starfleet catch-all" appears nowhere | pass |
| Tenant adds `visual-qa-20260829z-a2.starfleet.life` (covered only by the operator wildcard) | refused: "This hostname is not available on this installation" | by design (the wildcard owner owns unclaimed names; the brief's expectation of success was inconsistent with the shipped policy) |
| Tenant adds `admin.starfleet.life` (existing foreign claim) | refused: "This hostname is not available on this installation" -- identical to the nonexistent case | pass |
| Tenant authors a TXT record for its own hostname (`HostnameAuthority.canManage` under the wildcard) | created as record 17 | pass |
| Tenant saves its OWN exact domain row 9 unchanged | REFUSED: "This hostname is not available on this installation" | FINDING F1 |
| Tenant Access tab picker | placeholder "Exact email address, name or group name"; empty list on open; typing `a` -> "No user or group matches that exactly", 0 results; typing `admin@starfleet.life` -> exactly 1 result (Jelle De Loecker); no directory listed | pass |
| Grant-less user lands on `/` | "Forbidden. Your account has no access to anything on this installation yet. Ask an administrator to grant you access." with Account link and Sign out form; `/admin` shows the same card | pass |
| Sign-out per identity | tenant and grant-less sessions ended independently of the admin session | pass |

### Findings

- F1 (high, `/manage`): a tenant cannot SAVE its own exact domain row while the
  operator's `*.starfleet.life` wildcard exists: `/manage/domains/9` Save answers
  "This hostname is not available on this installation" with the hostname
  field invalid, even with no change. Creating the row as admin works and the
  tenant CAN author DNS for the name, so `HostnameAuthority.Snapshot.deciding`
  is honoured on the DNS lane but `SiteDomainResource.refuseRouteConflicts` /
  `refuseEnableRouteConflicts` (the update path) still treats the foreign
  wildcard overlap as a conflict for a requester who cannot manage the
  wildcard's site. Expected: the most specific covering row (the tenant's own
  exact row) decides, exactly as routing does; an unchanged save must succeed.
  Repro: as `visual-qa-*-site`, open `/manage/domains/<own exact row>`, Save.
  Screenshot taken (form with the refusal under Hostname).
- F2 (low): "Former owner" on Released hostnames renders the raw subject id
  (`user:5`), the same raw-provenance shape as the Access tab's
  "Granted ... by 1".
- F3 (cosmetic): the Domains tab's coverage cell runs "Covered" and "expires ..."
  together without a separator.
- Note: the neutral refusal is identical for a claimed and an unclaimed name, so
  no existence oracle; the `-a2` refusal is the intended wildcard-ownership
  policy, not a defect, but it means a tenant can only ever get names under
  the operator's wildcard through the operator.

## Cleanup ledger

| Record | Action | Verified |
| --- | --- | --- |
| DNS record 17 (TXT, tenant-created) | deleted via `/admin/dns-records/17` (dialog names record, zone, immediacy) | `dns_records` 0 rows with the prefix |
| domain 9 | deleted via `/admin/domains/9` | `site_domains` 0 rows with the prefix |
| site 8 | deleted via `/admin/sites/8` (dialog: hostnames stop answering, previews destroyed, app keeps running) | 4 live sites (Hohenheim Admin, Starfleet Apex, Starfleet catch-all, Herald comms hub); soft-deleted row kept by design |
| user 5, user 6 | deleted (dialog: direct grants removed, signed out everywhere) | `auth_users` 1, `auth_record_grants` 0 |
| quarantine `visual-qa-20260829z-a.starfleet.life` | lifted (typed confirmation) | Released hostnames 0 |
| profiles `visual-qa-z-site`, `visual-qa-z-none` | reset | |
| admin session | signed out; profile `visual-qa-admin` reset | |
| host | rehearsal dir removed, stray JVM gone, preflight dir kept as rollback | |

Nothing refused during cleanup. Rollback: `/root/hohenheim-preflight-20260829-final/`
(`hohenheim-server.jar.rollback` = `a7cb2514`, db copies, settings, keyring).
