# PaaS API v1 and the `hoh` CLI

The automation seam over the PaaS machinery: projects/environments, sites,
deploy/rollback, the three operation-record lanes (git deployments, releases,
sandbox builds), per-instance logs and the variable mechanism. The CLI is a
thin client of this API; nothing is reachable through it that the API does not
offer, and nothing in the API is a wider door than the admin/manage UI.

## Authentication

Every call needs a zenit-auth API key (`znit_` token), sent as `X-Api-Key: <key>`
or `Authorization: Bearer <key>`. Mint keys under `/account/api-keys`. Browser
sessions are refused on every route here (403), which is what keeps the
CSRF-exempt POSTs safe.

Scopes narrow a key below its owner's authority:

- capability scopes, exact-match: `cap:hohenheim:site#manage`,
  `cap:hohenheim:instance#manage`, `cap:hohenheim:instance#snapshots`, ...
- permission scopes, wildcards allowed: `hohenheim.instances.create`,
  `hohenheim.databases.create`, `hohenheim.admin.access`.

Sites and instances are visible exactly where the key's owner holds the
`manage` record capability AND the key's scopes cover that vocabulary. Project
listings additionally require the key to cover site or instance `manage`
(membership is grant-derived, so the listing enforces the scope itself).

**CORRECTED 2026-08-12: instance VISIBILITY resolves at `view`, not `manage`.**
`InstanceApi.visibleInstances`/`visibleInstance` ask
`HohenheimAccess.VIEW` and nothing else, by design -- visibility answers "may
you see this record", never "may you do this to it", and every mutating handler
reaches a service that asks its own capability. The `/manage` UI resolves the
same way (`ManageInstanceResource.java:91`, `:143`). This is NOT a wider-door
violation: it is the same gate on both surfaces, which is the property this
document promises. What it DOES mean, and what nobody delegating a capability
is currently told: `view` is `impliedBy` EIGHT capabilities -- `manage`,
`console`, `power`, `config`, `destroy`, `files.read`, `snapshots`, `backups`
(`HohenheimAccess.java:274-278`). An operator who grants only `power` so a
tenant can restart one workload has also handed over the instance listing and
its projected fields, PLAIN variable values included (secret values are never
returned). Say so when delegating; the grant UI does not.

## Conventions

- Reads are GET; mutations are form-encoded POST.
- "Absent", "trashed" and "not yours" are ONE byte-identical 404. The API is
  never an existence oracle.
- Typed refusals are `422` with `{"error":{"code":"<machine key>","message":"..."}}`;
  the code is the same microcopy key the HTML surface renders for the same act.
- Responses are enumerated whitelists, never row dumps. Site settings
  (environment maps, webhook secrets, api keys) never appear.
- Rate limits: reads 120/min, variable writes 30/min, deploy/rollback 10/min,
  per principal.

## Endpoints

| Method | Path | Notes |
| --- | --- | --- |
| GET | `/api/v1/projects` | Projects the key's owner belongs to (admins: all), with environments |
| GET | `/api/v1/projects/{id}` | One project |
| GET | `/api/v1/sites` | Sites the key holds `manage` on |
| GET | `/api/v1/sites/{id}` | Site detail: health, git state, latest release, `rollback_available` |
| POST | `/api/v1/sites/{id}/deploy` | Queue a git deploy (422 `deploy_not_available` otherwise) |
| POST | `/api/v1/sites/{id}/rollback` | Docker sites: health-gated release-engine rollback to the retained digest-pinned release; git process/static sites: previous checkout slot |
| GET | `/api/v1/sites/{id}/deployments` | Git deployment records (newest 50) |
| GET | `/api/v1/sites/{id}/deployments/{dep}/log` | One git deploy's captured log |
| GET | `/api/v1/sites/{id}/releases` | Release/rollback operations (no step logs) |
| GET | `/api/v1/sites/{id}/releases/{op}` | One operation, with its step log |
| GET | `/api/v1/sites/{id}/builds` | Sandbox build operations |
| GET | `/api/v1/sites/{id}/builds/{build}/log` | One build's captured log (build credentials were redacted at capture) |
| GET | `/api/v1/instances` | Instances the key holds `view` on -- which eight capabilities imply; see Authentication (product-tier-generated ones excluded) |
| GET | `/api/v1/instances/{id}` | One instance |
| GET | `/api/v1/instances/{id}/logs?lines=N` | Console tail (default 200, max 2000); 422 `logs_unavailable` when the daemon cannot answer |
| POST | `/api/v1/instances/{id}/power` | `action=start|stop|restart` |
| POST | `/api/v1/instances/{id}/command` | `command=...` to the console |
| POST | `/api/v1/instances/{id}/backup` / `snapshot` | Capability-gated in the service |
| POST | `/api/v1/instances` | Create from an approved template (same funnel as the create page) |
| GET | `/api/v1/instances/{id}/devices` | Attached extra disks and NICs (`name`, `type`, `size_gb`) |
| POST | `/api/v1/instances/{id}/devices` | Attach: `type=disk\|nic`, `name`, `size_gb` (disks). Quota and capability refusals are named: `disk_quota_reached`, `nic_quota_reached`, `devices_unsupported`, `device_exists`, `device_attach_failed` |
| POST | `/api/v1/instances/{id}/devices/resize` | `name`, `size_gb`. Block volumes resize STOPPED only -- a running resize returns the daemon's own "In use" inside `device_resize_failed` |
| POST | `/api/v1/instances/{id}/devices/detach` | `name=...`. DELETES the backing volume; refuses `device_detach_failed` rather than reporting a detach the daemon did not do |
| GET | `/api/v1/instances/{id}/variables` | Variables; secret VALUES are never returned |
| POST | `/api/v1/instances/{id}/variables` | `key`, `value`, `kind=plain|secret` (default plain) |
| POST | `/api/v1/instances/{id}/variables/delete` | `key=...` |
| GET/POST | `/api/v1/environments/{id}/variables[/delete]` | Same shape, but ADMIN-ONLY (`hohenheim.admin.access`, as narrowed by the key's scopes) -- see Environment variables below |

Also present (older lanes, admin-permission-gated): `/api/sites`,
`/api/sites/{id}/deploy`, `/api/dns/...`, and the instance file API under
`/api/v1/instances/{id}/files`.

**CORRECTED 2026-08-12: the instance file API belongs in neither half of that
sentence.** It is not older -- it is a v1 route (`API_INSTANCE_FILES` and
`API_INSTANCE_FILE_CONTENT`, `HohenheimEndpoints.java:481-500`) -- and it is not
admin-permission-gated: the read lane asks `InstanceFiles` for `files.read` and
the write lane for `files.write`, both INSIDE the service, which is exactly what
stops this surface and the Files tab holding different policies. Two details
worth keeping when this line is rewritten: the path always travels as the `path`
QUERY PARAMETER, never as a route segment (a segment would be split and
reassembled, and a second decode is how a normalized traversal slips in), and
the lane carries its own read rate limit.

## Environment variables are admin-only

**CORRECTED 2026-08-13: this lane used to require "project membership plus
instance-manage scope", and both the sentence and the code were wrong.**
Membership is grant-derived and a capability scope token only NARROWS a key --
neither one is authority over a record. An environment value is not scoped to
the environment: `InstanceVariables.valuesFor` folds it in as the deploy
baseline of every instance grouped under it, and it OVERRIDES that instance's
own `environment_variables` entry. So the old gate let a project member author
what a workload runs with on instances it held no capability over, which is the
one thing this document's opening promise forbids.

It is now gated on `HohenheimAccess.isAdmin` -- the very permission
(`hohenheim.admin.access`) that guards `HohenheimPanel`, where the only
environment-variable UI, `EnvironmentVariableResource`, is registered. That is
the promise read literally: `ManagePanel` offers no environment peer at all (its
project tier is a deliberately read-only tenant projection), so ANY tenant write
here would be a wider door than the UI by construction. Reads answer to the same
gate, because a lane you may not author is not one you may enumerate either.

Being admin-only is not theater against a narrowed key: `PermissionResolver`
intersects a key's dotted permission scopes with its owner's authority, so an
admin's key scoped `cap:hohenheim:instance#manage` is refused here exactly like
a tenant's.

Why not a per-instance capability walk instead, which would have given tenants a
real environment lane? Because "the affected instances" cannot be bounded: an
environment value applies to every instance grouped under it *including ones
added after the write*, and a fresh environment has none at all, so the walk
would be vacuous precisely where it needs to bite. `ProjectGuards` keeps
grouping and ownership equal at each instance write (an instance may only sit in
an environment whose project owns it), which is why the ordinary flow never
exposed this -- but a grant revoked afterwards moves ownership without
re-validating the grouping, and that drift is what the counterfactual in
`PaasApiTest.theEnvironmentLaneIsNoWiderThanItsAdminUi` reproduces.

Per-project environment variables for tenants therefore remain UNBUILT rather
than half-built; when they land they need a tenant UI and an ownership rule for
late-joining instances, not a widened API gate.

## Secrets are write-only

A variable written with `kind=secret` is stored in the encrypted column and its
value has NO representation over the API afterwards: reads return
`{"key":..., "kind":"secret", "has_value":true}`. The one legitimate reader is
deploy-time env injection. Recovery from a lost value is re-setting it. This is
deliberate: a GET that echoes secrets turns every CI log, shell history and
proxy cache into a leak surface. Variable changes apply at the next deploy of
the workload; they do not restart anything by themselves.

## Rollback and deploy explicitness

The server treats the explicit POST as the confirmation, matching the product's
ConfirmationSpec doctrine (typed confirmations are a client-side interlock; the
phrase never reaches the server). The CLI supplies the human interlock: `hoh
rollback` demands the site slug typed back interactively and refuses in
non-interactive contexts unless `--yes` is passed.

## The `hoh` CLI

`tools/hoh` -- single-file node (>= 20), stdlib only. Configuration lives in
`~/.config/hoh/config.json` (written 0600 by `hoh login`, verified against the
API before storing); `HOH_HOST`/`HOH_TOKEN`/`HOH_CONTEXT` override it.

```
hoh login https://panel.example       # prompts for the key, hidden
hoh projects | hoh projects 3
hoh sites | hoh site 7
hoh deploy 7
hoh rollback 7 [--yes]
hoh releases 7 | hoh release 7 12     # detail prints the step log
hoh builds 7 | hoh build-log 7 4
hoh deployments 7 | hoh deploy-log 7 9
hoh instances | hoh instance 3
hoh logs 3 -n 500
hoh power 3 restart
hoh vars instance 3                   # secrets show "(set)", never the value
hoh vars instance 3 set KEY value
hoh vars instance 3 set TOKEN --secret   # value prompted hidden, off argv
hoh vars instance 3 unset KEY
hoh vars env 5 set KEY value          # environment (deploy baseline) values, ADMIN-ONLY
```

`--json` prints the raw API response of any read. Tests: `node tools/hoh.test.js`
(stub server; proves paths, the key header, the rollback interlock and secret
masking), driven in the verification lane by `HohCliTest`. Server-side coverage:
`PaasApiTest` (browserTest).
