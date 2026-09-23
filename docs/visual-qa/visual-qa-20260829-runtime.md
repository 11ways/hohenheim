# Visual QA 2026-08-29: runtime tier on daystrom + nightstrom

Disposable all-roles controller `hohenheim-qa.service` on daystrom (root@10.47.1.99,
Arch, Incus 7.3 + Docker 29.7.1), nightstrom (root@10.47.1.101) enrolled as a remote
Incus host. Built from hohenheim `f1f18d4f` (clean secondary workspace `~/projects/javaweb-qa`,
jar stamped 13/13 clean). Installed under `/opt/hohenheim-qa` (own db, keyring, data,
port 3100, proxy 8081/8444, DNS 127.0.0.1:5354, LE off, nftables on, roles
proxy/dns/firewall/stacks/databases/instances). The old `/opt/hohenheim` install on
daystrom was not touched (its `hohenheim.service` was found ACTIVE on port 3000, not
stopped as the plan recorded; left as found).

The pass was executed by two agents: the first was cut off by a usage limit at the
migration step and left no ledger; the second recovered the running controller from
the hosts and the browser session and finished. Coverage before the cut-off is
reconstructed from the controller's activity log and the daemons, not from notes.

## Environment facts

| Host | Facts |
| --- | --- |
| daystrom | 3907 MB RAM (109 MB free with both controllers + a 6-day-old foreign container `hohenheim-u548fz9h-instance-1`), `/` 59G 29% used, Incus pool `default`, 153 network ACLs (legacy `hohenheim-*` pile from earlier test controllers, reconciler noise, NOT reaped), 6 `hhx-*` bridges |
| nightstrom | 3907 MB RAM (1210 free), 25 ACLs, 0 `hhx-*` bridges, 0 instances, trust certs `hohenheim-daystrom`, `hohenheim-live-workspace`, and the QA controller's `visual-qa-20260829q-controller` |

Hosts as enrolled in the QA controller: `local` (Docker 29.7.1, admitted, shared
containers), `visual-qa-20260829q-daystrom-incus` (unix socket, admitted, VM isolated),
`visual-qa-20260829q-nightstrom` (https://10.47.1.101:8443, admitted, VM isolated).

## Coverage

| Surface | Actions | Result | Evidence |
| --- | --- | --- | --- |
| Hosts list + 3 enrolments, preflight, admit | enrol local Docker, daystrom Incus (unix), nightstrom Incus (https + trust token) | pass (all three Admitted) | `/admin/servers`, activity `update hohenheim:server 1..3` |
| Docker instance `visual-qa-20260829q-web` (nginx:alpine) | create, deploy, stop, restart, exec, files save/delete, snapshot create + restore, expose via site, public port claim (32780, held), destroy with data | pass except F5 | overview activity list; `curl -H Host: visual-qa.daystrom.test http://127.0.0.1:8081/` = 200 with the file edited through Files |
| Incus VM `visual-qa-20260829q-lxc` (512 MiB Alpine, named lxc but kind VM) | create, deploy (2x), overview root-disk meter, console page, files (correctly "not available for this runtime"), shell (correctly refused: root workload), stats, migrate, destroy with data | F1, F2 | journal `InstanceMigrations:615`, `HttpConduit ... [yXfN14lCf24]` |
| Cold migration daystrom -> nightstrom | Migrate to host... > nightstrom "Eligible" > Migrate here | F1 (fail, rolled back cleanly) | alert text, journal |
| Stack `visual-qa-20260829q-stack` (nginx + redis) | create, first deploy (before cut-off), second deploy, services tab, deployments tab with log, delete (cascade) | F3 on the first attempt; second deploy Success 5s; delete removed both containers + the stack network, `stack_services`/`stack_deployments`/`stack_files` = 0 | `docker ps -a`, sqlite counts |
| Database `visual-qa-20260829q-db` (PostgreSQL) | create, attach to the Docker instance (env prefix DB), delete while attached (refused), detach, delete | F4, F6, F7 | journal `DatabaseService:228`, refusal alert |
| Instance schedule | Add schedule from the Schedules tab, fill name/cron/timezone, Save | F8 | `cms.create_submit.save_failed ... zenit_record_schedules` |
| IP bans | create private 10.47.99.99 (refused inline: "private address"), create 203.0.113.5 (nft set `banned_v4` timeout 1d), lift (nft entry gone, `lifted_at` stamped) | pass, F9 (Duration shows None) | `nft list ruleset` |
| Project / environment / Secret variable | create project, environment, `QA_SECRET` (Secret kind) -- done before cut-off; delete order enforced (project refuses while owning an environment, environment refuses while holding a variable) | pass | refusal alerts quoted below |
| DNS zone `visual-qa-20260829q.test` + 1 record | create (before cut-off), typed-confirm delete naming the record count | pass | dialog text |
| Access list `visual-qa-20260829q-acl` | create (before cut-off), delete (dialog names 0 rules / nothing gated) | pass | dialog text |
| Site `visual-qa-20260829q-site` -> instance upstream | create, domain, fetch through the QA proxy, auto-disabled when the instance was destroyed, delete (dialog names hostnames + preview + application) | pass | journal `InstanceExposure:88` |
| Dashboard, all roles on, hosts admitted | attention items (colliding + unmanaged Docker names on local, no off-host backup target, zone without NS), 4 stat tiles, no readiness checklist (all steps done) | pass | screenshot in this session |
| Runtime images (4 seeded), instance templates (2 seeded) | lists render; delete refusals while instanced NOT exercised (no template-derived instance was created) | untested | -- |
| Certificate pin clearing on certificate delete | NOT exercised live (no certificate uploaded); pinned by `RuntimeCascadeTest` | untested | -- |
| Off-host backups, Windows template, Incus on nightstrom beyond enrolment/migration target, preview deployments, game domains, Spamservice | unavailable in this environment | untested | -- |

## Findings, by severity

### F1 HIGH: cold migration to another Incus host fails on the destination's missing isolation ACL

Repro: instance 2 (VM on daystrom) > More actions > Migrate to host... > nightstrom shows
"Eligible" > Migrate here. Expected: the VM stops, exports, imports on nightstrom and
starts. Actual: alert "Migration of visual-qa-20260829q-lxc failed: Incus operation
Restoring backup Failure: Failed importing backup: Failed creating instance record:
Failed initializing instance: Invalid devices: Device validation failed for "eth0":
Network ACL "hohenheim-yi3ormt1-isolation" does not exist". The rollback is correct
(journal `MIGRATE: rolled back interrupted migration ... source host keeps it`; the VM
stayed RUNNING on daystrom). Cause: the destination daemon only carried the controller's
`hohenheim-yi3ormt1-presence` marker ACL, never the `-isolation` ACL the exported
config references; the per-host isolation ACL is created on first placement
(`IncusNetworkPolicy`), and the import path skips that step. Source:
`server/instance/InstanceMigrations.java` (import), `server/incus/IncusNetworkPolicy.java`.
The plan's recorded PASS of `IncusColdMigrationLiveTest` (daystrom -> nightstrom) must
have run with the ACL already present on nightstrom from an earlier placement, so the
test does not cover a fresh destination.

### F2 HIGH: the Stats tab of an Incus VM is a 500

`GET /admin/instances/2/page/stats` answers `{"status":500,"code":"INTERNAL_ERROR",
"reference":"yXfN14lCf24"}`. Journal: `ClassCastException: MutableRef cannot be cast to
java.util.List at Tpl_CmsInstanceStats.branch38 (Tpl_CmsInstanceStats.java:620)`. The
Docker instance's Stats tab renders. Source: the instance stats template's Incus branch
passes a ref where the template expects a list.

### F3 HIGH: a failed stack deploy leaves "Failed" with no deployment row and no reason

After the first deploy attempt (before the cut-off) the stack list said "Failed",
Services said "Missing" for both services, and Deployments said "No deployments yet",
so an operator cannot find out WHAT failed. The second deploy succeeded in 5 s and
recorded a full log, so the first failure was environmental (most likely image pulls on
a cold daemon), but a failure must leave a deployment row with its log exactly like a
success does.

### F4 HIGH: database provisioning fails immediately and the UI stays on "Provisioning"

Create a PostgreSQL database on the Docker host. Journal 100 ms later:
`DatabaseService:228 - DB: provisioning failed for visual-qa-20260829q-db - No managed
database named 'visual-qa-20260829q-db'` (the provisioner looks the record up by name
before the row is visible to it). No container is ever created; the list and the
detail page show "Provisioning" indefinitely with no error, no retry, no attention item.

### F5 HIGH: "Delete with data" keeps the instance's named Docker volumes

Dialog: "This removes the container of 'visual-qa-20260829q-web' AND every volume it
owns. The files are gone for good." Journal: `INSTANCE: destroyed
hohenheim-yi3ormt1-instance-1 - container removed, volumes kept, record soft-deleted`;
`docker volume ls` still lists `hohenheim-yi3ormt1-instance-1-vol-html` afterwards, and
the `deleted_data` activity row has an empty detail. `InstanceService.destroyWithData`
removes `instance_volumes` (host-path volumes) only; the named volumes declared in the
instance's "Volumes" field are not in that table. Either the dialog over-promises or
the destroy under-delivers; today it is a data-retention surprise with the strongest
possible wording. Also: the destroy gives no toast; the page just sits there.

### F6 MEDIUM: database delete is offered alive and generic, then refused after confirmation

The Delete button on `/admin/databases/1` opens "Delete visual-qa-20260829q-db? This
cannot be undone." and only after Confirm answers "Database '...' is attached to
visual-qa-20260829q-web; detach it there first". The row-action doctrine used elsewhere
(host, template, runtime image) is dead-with-reason before the click. The refusal also
says "detach it there" but the instance's pages have no database section; detaching
happens on `/admin/instance-databases`.

### F7 MEDIUM: attached-database record UX

The attachment record is titled by its env prefix ("DB"), its delete dialog says
"Delete DB? This cannot be undone." naming neither the instance nor the database, and
on the edit form the Database combobox rendered empty (only "Clear selection") while the
Instance one kept its value.

### F8 HIGH: "Add schedule" on an instance cannot save

`/admin/instance-schedules/new?record_id=1` renders a generic "New in Schedules" form
with a raw editable "Record ID" textbox, Name, Cron, Timezone, Enabled, and no steps or
action fields; Save answers "Saving failed. Please try again." Journal:
`cms.create_submit.save_failed DataSourceException: Failed to create row in table:
zenit_record_schedules`; `pragma table_info` shows NOT NULL `model` and `record_id`
without defaults, and the form never sets `model`. Reproduced twice (14:17 by the first
agent, 14:58 by me).

### F9 LOW: ban detail shows "Duration: None" for a 24-hour ban

The ban was stored with `expires_at` and enforced in nft with `timeout 1d`, but the
detail page renders Duration "None". After Lift ban the page keeps rendering the ban as
if active (no lifted state, only the toast).

### F10 LOW: copy on the database detail page

"A value is stored, but this field does not show it. Leave blank to keep it, or type a
new value to replace it." is shown on the read-only Details tab where there is no field.

### F11 LOW: environment refusal wording

"This environment is still referenced by instances or variables" does not say which; a
name and a link would let the operator act.

## Passes worth recording

- Docker instance life cycle end to end (deploy, stop, restart, exec, files, snapshot
  restore, site exposure through the proxy, typed-confirm destroy naming the sites it
  disables) worked; the destroy disabled the exposing site as promised.
- Stack cascade (this wave's `StackCascades`): containers, network and all child rows
  went with the stack.
- Database-in-use refusal names the workload; environment and project refusals are
  ordered correctly; zone delete is typed-confirm and names the record count; access
  list delete names its rule count and gating.
- IP ban: private address refused inline, public address enforced in nft within a
  second, lift removes the set element.
- VM runtime: root-disk meter, "Files not available for this runtime" and the
  non-root-only shell rule are explained rather than silently missing.
- Migration page: ineligible host ("Instance kind needs a incus host") and booked
  memory meters are clear; the failure rolled back with the source intact.

## Cleanup ledger

| Object | Result |
| --- | --- |
| instances 1 (web), 2 (lxc VM), 3-4 (stack services) | destroyed through the product; rows soft-deleted; daemons clean of containers/VMs |
| Docker volume `hohenheim-yi3ormt1-instance-1-vol-html` | KEPT by the product (F5); removed by hand at teardown |
| stack, database, attachment, site, DNS zone, access list, ban, variable, environment, project | deleted through the product (see teardown section for the last three) |
| nightstrom trust cert `visual-qa-20260829q-controller` | removed at teardown |
| nightstrom ACL `hohenheim-yi3ormt1-presence` | left for the reaper (presence marker) |
| daystrom ACLs `hohenheim-yi3ormt1-isolation/-presence`, bridges | left for the reaper; the legacy pile predates this pass |
| `hohenheim-qa.service`, `/opt/hohenheim-qa` | stopped, disabled, removed at teardown |
| `~/projects/javaweb-qa` secondary workspace | removed |

## Teardown (executed 2026-08-29 ~15:10 CEST)

- Product deletes, in dependency order: attachment, database, stack (cascade verified),
  instances 1-4 (typed confirm), site, DNS zone (typed confirm), access list, variable
  (dialog names the environment and the next-deploy effect), environment (refused while
  the variable existed, then deleted), project (refused while the environment existed,
  then deleted), ban (lifted). The QA controller's database ended with 0 live instances,
  0 stacks, 0 databases, 0 zones, 0 access lists, 0 active bans; the soft-deleted site
  and instance rows are the documented tombstone shape.
- daystrom: Docker volume `hohenheim-yi3ormt1-instance-1-vol-html` removed by hand (F5);
  `hohenheim-qa.service` disabled and stopped, unit removed, `/opt/hohenheim-qa` removed
  after unmounting the btrfs subvolume the controller mounted at `data/volumes`, and that
  subvolume (`hohenheim-qa-volumes` on the Incus pool) deleted; nothing listens on
  3100/8081/8444; `docker ps -a` shows only the foreign `hohenheim-u548fz9h-instance-1`
  that predates this pass; `incus list` empty. Left for the reaper (its own controller's
  objects, presence stamped 2026-08-29): ACLs `hohenheim-yi3ormt1-isolation` and
  `hohenheim-yi3ormt1-presence`; the legacy unstamped ACL/bridge pile predates this pass.
  The old `/opt/hohenheim` install and its ACTIVE `hohenheim.service` on port 3000 were
  left exactly as found. No authorized_keys line was added on daystrom.
- nightstrom: trust certificate `visual-qa-20260829q-controller` removed; the
  `hohenheim-visual-qa-20260829q-nightstrom` authorized_keys line removed (backup at
  `/root/.ssh/authorized_keys.bak-visual-qa-20260829q`, the pre-existing
  `hohenheim-nightstrom` line kept); ACL `hohenheim-yi3ormt1-presence` left for the
  reaper; no instances.
- Workstation: `~/projects/javaweb-qa` secondary workspace removed and worktrees pruned;
  all QA browser views and the daystrom port forward closed. The `visual-qa-daystrom`
  browser profile holds no live session (the controller is gone).

Host state before/after: daystrom 2 controllers active -> 1 (the old one, untouched);
nightstrom untouched except the two credentials above.
