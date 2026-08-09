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
| GET | `/api/v1/instances` | Instances the key holds `manage` on (product-tier-generated ones excluded) |
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
| GET/POST | `/api/v1/environments/{id}/variables[/delete]` | Same shape; requires project membership plus instance-manage scope |

Also present (older lanes, admin-permission-gated): `/api/sites`,
`/api/sites/{id}/deploy`, `/api/dns/...`, and the instance file API under
`/api/v1/instances/{id}/files`.

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
hoh vars env 5 set KEY value          # environment (deploy baseline) values
```

`--json` prints the raw API response of any read. Tests: `node tools/hoh.test.js`
(stub server; proves paths, the key header, the rollback interlock and secret
masking), driven in the verification lane by `HohCliTest`. Server-side coverage:
`PaasApiTest` (browserTest).
