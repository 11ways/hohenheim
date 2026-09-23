# Visual QA 2026-08-29: `/manage` capability isolation

Live pass against `https://admin.starfleet.life` (hohenheim `12490d6e` deployed,
roles proxy/dns/firewall) with three disposable narrow identities, one browser
profile each, all fixtures prefixed `visual-qa-20260829m-`. Read-only except the
fixtures below; every fixture was deleted afterwards and the lists are back at
their baseline. Sketerm headless engine, 1440x900, light theme.

## Identities and fixtures

| Fixture | Id | Purpose |
| --- | --- | --- |
| site `visual-qa-20260829m-a` (redirect) + domain `visual-qa-20260829m-a.starfleet.life` (exclude LE, force SSL off) | site 4, domain 5 | the site the site-scoped user manages |
| site `visual-qa-20260829m-b` (redirect) + domain `visual-qa-20260829m-b.starfleet.life` | site 6, domain 6 | the FOREIGN site nobody in the pass manages |
| project `visual-qa-20260829m-project` (managed group `group.project-...`) | project 5 | the project-scoped identity's membership |
| user `visual-qa-20260829m-site@` with `manage` on site 4 only (record grant via the site's Access tab) | user 2 | site-only tenant |
| user `visual-qa-20260829m-project@` in the project group, no record grants | user 3 | project-only tenant |
| user `visual-qa-20260829m-none@`, no grants, no groups | user 4 | logged-in nobody |

No role was needed: a walk-confirmed record grant or a project membership makes
a user eligible for `/manage` on its own (`ManagePanel.ManageEligibilityChecker`).
Passwords were minted with the admin "Set password" action (one-time value shown
once) and rotated by each user on first sign-in; they are recorded nowhere.

Site 5 (`Starfleet catch-all`, domain `*.starfleet.life`) was created
concurrently by another agent and is NOT part of this pass; it does, however,
change what the pass observed (findings 1-3).

## Unavailable scopes

Instance-, database-, schedule-, snapshot-, backup-, device-, preview- and
template-scoped identities cannot be exercised: the instances/stacks/databases
roles are off on starfleet and no such record exists. Git-provider-scoped
identity: no disposable provider was created (it needs a forge credential).
`SITES_MANAGE_ALL` (every-site authority without admin) was not exercised.

## Coverage

| Identity | Surface | What was done | Result |
| --- | --- | --- | --- |
| site | login with `_return=/manage/sites/6`, forced rotation | rotation page keeps `_return`; lands on the foreign site id | pass (404, see below) |
| site | sidebar | Overview, Sites, Preview deployments, Certificates, Access lists, DNS records; no admin links, no "Switch panel" | pass |
| site | `/manage/sites` | exactly site 4; row menu offers only Disable (confirmed dialog naming the hostname; cancelled) | pass |
| site | `/manage/sites/4` | name/enabled/description form, tabs Domains / Protected paths / Access; upstream not editable | pass |
| site | foreign + nonexistent deep links | `/manage/sites/6`, `/999`, `/6/page/domains`, `/manage/domains/6`, `/999`, `/manage/projects/5`, `/manage/certificates/1` all 404 with BYTE-IDENTICAL bodies (114529 / 114506 chars) | pass, no oracle |
| site | `/admin/*` (12 urls incl. `/admin/sites/6` vs `/999`, `/admin/certificates/request`, `/admin/build-info`) | 403, identical 2815-char page, no existence signal | pass, no oracle |
| site | `/api/v1/sites`, `/api/v1/instances/1` vs `/999` | 403 JSON `{"status":403,"code":"FORBIDDEN",...}` identical | pass |
| site | `/manage/certificates`, `/manage/access-lists`, `/manage/dns-records` | empty; the operator's two certificates and the zone's 8 records are NOT listed | pass |
| site | DNS record create, foreign FQDN vs nonexistent FQDN | identical "You do not manage a hostname that covers this name" | pass, no oracle |
| site | DNS record create, OWN FQDN | REFUSED with the same message | issue F1 |
| site | DNS record create, relative owner name | "No hosted zone contains that name" while the help text says to type a relative name | issue F5 |
| site | add domain to site 4: hostname of the foreign site | refused, but the refusal NAMES the foreign site | issue F2 |
| site | add domain to site 4: unused hostname | refused, naming the catch-all site and its wildcard row | issue F2/F3 |
| site | Site picker on the domain form | 1 result (site 4 only) | pass |
| site | Access tab | own row shown, self-edit disabled (Allow combobox disabled); "Add user or group" picker lists EVERY user (admin included) and every group | issue F4 |
| site | Access tab copy | "Granted 2026-08-29T11:31:38.091Z by 1" | issue F8 |
| site | Domains tab | Certificate column shows "Starfleet catch-all" for the tenant's hostname | issue F6 |
| site | Overview | "Your instances / No records found" although the instances role is off | issue F7 |
| site | `/manage/instances`, `/manage/databases`, `/manage/git-providers` | 200 empty lists, not in the sidebar, roles off | issue F7 |
| site | `/account`, `/account/sessions` | own account only; sign out lands on `/login` | pass |
| project | login, forced rotation, `_return=/manage` | lands on Overview | pass |
| project | sidebar | Overview, Members, Projects, Certificates, Access lists | pass |
| project | `/manage/projects` | only project 5; `/manage/projects/1` and `/999` identical 404s | pass, no oracle |
| project | `/manage/sites/4` vs `/999` | identical 404 | pass |
| project | Members | only itself (Kind "user") | pass |
| none | login with `_return=/manage` | forced rotation, then 403 "Access denied" with Home links to `/`, which is ALSO 403 | issue F9 |
| none | login without `_return` | lands on `/` = 403 | issue F9 |
| none | `/manage/*`, `/`, `/admin` | 403; `/account` 200 | pass |
| cross | site user Sign out | project user's session still answers 200 | pass |
| cross | none user "Log out everywhere", tenant password rotation | admin session unaffected (checked before/after) | pass |
| admin | fixtures | created through the panel; grant through the site Access tab | pass |

Evidence: screenshots of the tenant 404 page, the `/admin` 403 card, the
`/manage` 403 page, the tenant Overview, the project Members page and the
Access-tab picker leak were captured during the pass (Sketerm view
screenshots, not stored in the repo).

## Findings, by severity

### F1 (high): a catch-all wildcard row blocks every tenant's DNS authoring on its own hostname

Repro: as the site user, `/manage/dns-records/new`, name
`visual-qa-20260829m-a.starfleet.life` (the hostname the user's site owns),
type A, value `104.223.42.142`, Save.
Expected: the record is created (the user manages the exact hostname).
Actual: "You do not manage a hostname that covers this name".
Cause: `HostnameAuthority.canManage` (`server/auth/HostnameAuthority.java:136-152`)
requires EVERY covering domain row to belong to a site the caller manages; the
operator's `*.starfleet.life` catch-all row also covers the name, so the tenant
never qualifies. The comment calls this deliberate ("a name two owners answer
for"), but with a wildcard catch-all in place it means no tenant can ever author
DNS, and (same predicate) no tenant can order a certificate. Routing already
gives the exact row precedence over the wildcard; authority should too, or the
catch-all must be excluded from the ownership question.

### F2 (high): domain refusals name other tenants' sites

Repro: as the site user, `/manage/domains/new?site_id=4`, hostname
`visual-qa-20260829m-b.starfleet.life`, Save -> "This route is already claimed
by site visual-qa-20260829m-b". Hostname `visual-qa-20260829m-zzz.starfleet.life`
-> "This hostname overlaps *.starfleet.life, routed by site Starfleet catch-all".
Expected: a refusal that does not reveal whether the hostname is taken, by whom,
or which wildcard rows the installation carries.
Actual: the foreign site's NAME and the operator's wildcard pattern are printed,
so a tenant can enumerate every hostname and site name on the box by probing.
Source: `SiteDomainResource.java:436-448` (`route_overlaps_other_site`,
`route_taken_other_site` with `$site`), microcopy `en.json:5126-5138`. The
`CertificateAuthority` AIDEV-NOTE already names this oracle class for
NOT_SERVED/NOT_MANAGED; this is the same class one resource over, and it is
reachable by a tenant today.

### F3 (high, product): with a catch-all site a tenant cannot add any hostname at all

Every `*.starfleet.life` name overlaps the catch-all row, so the `/manage`
domain form refuses everything (see F2's second message). Combined with F1 the
delegated site tier is inert on an installation that uses a catch-all wildcard.
Decision needed: does an operator-owned wildcard count as a competing owner for
exact names beneath it? Today's answer (yes) contradicts the routing precedence.

### F4 (high): the tenant Access tab lists every user and group on the installation

Repro: as the site user, `/manage/sites/4/page/access`, open "Add user or
group". Actual: all four users with display name AND email (the administrator
included) plus every group. Expected: at most the subjects the tenant already
shares something with, or a search that answers only exact matches. Source:
zenit-auth `RecordAccessPage` subject picker (its subject source is not scoped
by the viewer's authority). `manage` is delegable by design, so the page must
exist for tenants; the directory behind it must not.

### F5 (medium): tenant DNS form asks for a relative owner name but only accepts an FQDN

The help text says "Owner name relative to the zone: @ for the zone itself, www
for a subdomain"; a tenant has no zone context, and typing the relative name
yields "No hosted zone contains that name". Only the FQDN reaches the authority
check. Source: `ManageDnsRecordResource` form + `en.json:3386-3390`.

### F6 (medium): tenant Domains tab shows the name of a certificate the tenant cannot see

The Certificate column reads "Starfleet catch-all" (the operator's wildcard
certificate, named after the operator's site) while `/manage/certificates` is
empty and `/manage/certificates/<id>` is 404 for the same user. Either the
column should show the tenant-safe fact ("platform certificate") or the
certificate should be viewable.

### F7 (medium): `/manage` ignores the disabled install roles

The Overview renders "Your instances / No records found", and
`/manage/instances`, `/manage/databases`, `/manage/git-providers` answer 200
with empty lists, on a node whose instances/stacks/databases roles are off (the
admin panel and the admin dashboard hide those groups since `767be086`).
`ManagePanel.buildPeers` and `ManageDashboard` carry no role predicate.

### F8 (low): grant provenance is rendered raw

"Granted 2026-08-29T11:31:38.091Z by 1": ISO timestamp with milliseconds and
the actor's numeric id, on both the admin and the tenant Access tab (zenit-auth
`en.json:686`, `Granted {$date} by {$actor}`). Every other date on the panel is
localized and every other actor is a name.

### F9 (medium, UX): a signed-in user with no grants is dead-ended

`visual-qa-20260829m-none@` lands on `/` = 403 "Access denied" after login
(and after the forced rotation with `_return=/manage`). The 403 page's only
links ("Home", "Go to the home page") point at `/`, which is itself 403; the
only page that works, `/account`, is not linked, and there is no sign-out. The
`/admin` 403 is a different, plainer card ("Forbidden / You do not have
permission to access this page") with no links at all. One refusal page, with
an account link and a sign-out, would fix both.

### F10 (low): disabled-role peers still reachable, sidebar-hidden

Covered by F7; listed separately because it is also a consistency gap with the
admin panel's role gating.

## Not a finding

- Foreign-vs-nonexistent ids: 404 bodies byte-identical on every `/manage`
  route probed; `/admin` 403 bodies byte-identical; `/api/v1` JSON identical.
- DNS refusal for a foreign FQDN equals the refusal for a nonexistent FQDN.
- The tenant's Site picker, Members list, certificate list and access-list list
  leak nothing.
- Self-grant editing on the Access tab is disabled for the tenant.
- Sessions are per identity: signing out one profile or "log out everywhere"
  on one user left the other identities' sessions intact.

Observed once, not reproduced: the administrator's browser session ended
between 11:31Z and 11:45Z during the pass (redirect to `/login?_return=...`);
neither a tenant password rotation nor a tenant "Log out everywhere" reproduced
it afterwards. Possibly an idle timeout; not attributed.

## Cleanup ledger

| Record | Result |
| --- | --- |
| users 2, 3, 4 | deleted ("also removes their direct grants and signs them out everywhere") |
| project 5 (+ managed group) | deleted |
| domains 5, 6 | deleted |
| sites 4, 6 | deleted (soft), released-hostname rows for both hostnames lifted with the typed confirmation |
| DNS records | none were created (every tenant attempt was refused) |
| browser | all views closed; profiles `visual-qa-m-site`, `visual-qa-m-project`, `visual-qa-m-none` erased; admin signed out |

Final counts: sites 3 (Starfleet Apex, Hohenheim Admin, Starfleet catch-all),
domains 4, users 1, projects 0, roles 0, access lists 0, released hostnames 0.
