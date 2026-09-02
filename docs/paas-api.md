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
- Typed refusals are `422` with a FLAT envelope:

  ```json
  {
    "status": 422,
    "code": "zenit.coercion.unknown_field",
    "message": "This field is not part of the form",
    "field": "settings.root_paht",
    "violations": [
      { "code": "zenit.coercion.unknown_field",
        "message": "This field is not part of the form",
        "field": "settings.root_paht" }
    ]
  }
  ```

  `code` is the same microcopy key the HTML surface renders for the same act, and
  `message` is resolved in the caller's locale chain. `field` is the path of the
  refused value -- dotted for nested settings, indexed for rows -- and is ABSENT
  when the refusal is about the submission rather than one value. `code`,
  `message` and `field` restate the FIRST entry of `violations`, which lists every
  refusal; a client that only reads the top three keeps working.
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
| POST | `/api/v1/sites` | Create a site through the admin form's own pipeline (ADMIN-ONLY, 403 otherwise) -- see Sites and domains below |
| POST | `/api/v1/sites/{id}/delete` | Soft-delete a site exactly as the admin form does (ADMIN-ONLY); 404 for a trashed or unknown id |
| GET | `/api/v1/sites/{id}/domains` | The site's hostname rows, oldest first |
| POST | `/api/v1/sites/{id}/domains` | Add a hostname row to the site (whoever holds `manage` on it) |
| POST | `/api/v1/sites/{id}/domains/{domain}/delete` | Remove a hostname row; a row of another site answers 404 |
| GET | `/api/v1/access-lists` | Access lists the key holds `manage` on -- see Access lists below |
| GET | `/api/v1/access-lists/{id}` | One list, with its whole rule tree |
| POST | `/api/v1/access-lists` | Create a list through the panel's own form pipeline (needs the panel permission) |
| POST | `/api/v1/access-lists/{id}/delete` | Delete a list; its rules go with it |
| POST | `/api/v1/access-lists/{id}/rules` | Add one node to the list's rule tree |
| GET | `/api/v1/instances` | Instances the key holds `view` on -- which eight capabilities imply; see Authentication (product-tier-generated ones excluded) |
| GET | `/api/v1/instances/{id}` | One instance |
| GET | `/api/v1/instances/{id}/logs?lines=N` | Console tail (default 200, max 2000); 422 `logs_unavailable` when the daemon cannot answer |
| POST | `/api/v1/instances/{id}/power` | `action=start|stop|restart` |
| POST | `/api/v1/instances/{id}/command` | `command=...` to the console |
| POST | `/api/v1/instances/{id}/backup` / `snapshot` | Capability-gated in the service |
| POST | `/api/v1/instances` | Create one workload. TWO lanes behind one URL, chosen by whether the body carries `template_id`: with one, the tenant's approved-template funnel; without, the admin create form's own pipeline -- see Instances below |
| POST | `/api/v1/instances/{id}/delete` | Destroy and trash a workload, exactly as the form's delete does (the `destroy` capability, asked by the teardown service itself) |
| GET | `/api/v1/instances/{id}/devices` | Attached extra disks and NICs (`name`, `type`, `size_gb`) |
| POST | `/api/v1/instances/{id}/devices` | Attach: `type=disk\|nic`, `name`, `size_gb` (disks). Quota and capability refusals are named: `disk_quota_reached`, `nic_quota_reached`, `devices_unsupported`, `device_exists`, `device_attach_failed` |
| POST | `/api/v1/instances/{id}/devices/resize` | `name`, `size_gb`. Block volumes resize STOPPED only -- a running resize returns the daemon's own "In use" inside `device_resize_failed` |
| POST | `/api/v1/instances/{id}/devices/detach` | `name=...`. DELETES the backing volume; refuses `device_detach_failed` rather than reporting a detach the daemon did not do |
| GET | `/api/v1/instances/{id}/variables` | Variables; secret VALUES are never returned |
| POST | `/api/v1/instances/{id}/variables` | `key`, `value`, `kind=plain|secret` (default plain) |
| POST | `/api/v1/instances/{id}/variables/delete` | `key=...` |
| GET/POST | `/api/v1/environments/{id}/variables[/delete]` | Same shape, but ADMIN-ONLY (`hohenheim.admin.access`, as narrowed by the key's scopes) -- see Environment variables below |

| GET | `/api/v1/databases` | Managed databases the key holds `view` on -- see Managed databases below |
| GET | `/api/v1/databases/{id}` | One managed database, the shape `hoh database wait` polls (`status` + `outcome`) |
| POST | `/api/v1/databases/{id}/move-shared` | Move a dedicated database onto its host's shared engine (ADMIN-ONLY, 403 otherwise); accepted/queued, the record's `status` carries the outcome |
| POST | `/api/v1/databases/{id}/delete` | Destroy a managed database exactly as the panel's delete does (the `destroy` capability, asked by the teardown service itself); 422 `delete_in_use` while a workload holds it |
| GET | `/api/v1/engines` | Shared database engines with their database counts (ADMIN-ONLY) |
| GET | `/api/v1/engines/{id}` | One engine plus the logical databases on it, and its container's live usage when the stats hub has a sample (ADMIN-ONLY) |
| GET | `/api/v1/hosts` | Hosts with their memory ledger: budget, booked, free, overcommit (ADMIN-ONLY) -- see Hosts and capacity below |
| GET | `/api/v1/hosts/{id}` | One host plus every workload booked on it (instances AND database engines) |

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

## Sites and domains

The write lane of the proxy tier, added for the migration of an old installation
(the Phoenix Mongo `sites` collection converts to exactly these calls). It is the
admin panel's own create pipeline reached without a browser: the request body is
the SAME form-encoded transport the site form and the domain form post, coerced
against the SAME `FormSpec` (`SiteResource` / `SiteDomainResource`) through
zenit-cms' `ResourceWrites`, so the route claim, hostname canonicalization, the
tenant column freeze (`TenantWrites`) and the proxy reload all fire as they do for
a form save. There is no JSON body: dotted keys nest (`settings.forward_host`),
and indexed keys make rows (`custom_headers.0.key` / `custom_headers.0.value`).

Rate limit: 120 route writes per minute per principal (`hh_paas_route_write`).

Doors, exactly the panels': sites are created and deleted only in the admin panel
(`ManageSiteResource` is neither creatable nor deletable), so both verbs demand
the admin permission as narrowed by the key's scopes and answer **403** otherwise.
Domain rows are a tenant's own affordance on a site they `manage`: the site must be
visible to the key (the uniform 404 otherwise), and a delegated key is then held
to the delegated columns by the write pipeline (`hostname`, `force_ssl`,
`hsts_enabled`, `hsts_subdomains`, `exclude_from_letsencrypt`; anything else is a
422 naming the column), exact match type only, no path, no listener restriction.

### `POST /api/v1/sites`

| Field | Type | Notes |
| --- | --- | --- |
| `name` | string, required | The slug derives from it (`lowercase`, non-alphanumerics to `-`); `status` is stamped `active` |
| `upstream_kind` | enum, required | `hohenheim:address`, `hohenheim:static`, `hohenheim:redirect`, `hohenheim:instance`, `hohenheim:dev_namespace`, `hohenheim:tls_passthrough` |
| `enabled` | boolean | `true`/`false`; default true. A disabled site's rows claim no route |
| `description` | string | |
| `instance_id` | integer | Required by `hohenheim:instance` (refused on every other kind, `upstream_instance_unexpected`) |
| `auth_provider_id` | integer | An existing site auth provider; refused on `tls_passthrough` |
| `access_list_id` | integer | An existing access list; refused on `tls_passthrough` |
| `settings.*` | per kind | The kind's own schema; an undeclared setting is refused (`zenit.coercion.unknown_field`) |

Settings per kind (every key optional unless said otherwise):

- `hohenheim:address`: `forward_scheme` (`http`/`https`), `forward_host`,
  `forward_port` (integer), `socket` (a unix socket path, instead of host+port),
  `upstream_protocol` (`http1`/`h2`), `request_timeout` (seconds),
  `websocket_upgrade` (default true), `ignore_certificates` (default false),
  `rewrite_location` (default TRUE: upstream `Location` headers are rewritten with
  the forwarded Host, so a domain that rewrites `Host` to an internal name wants
  `settings.rewrite_location=false`), `delay` (ms).
- `hohenheim:static`: `root_path`, `fallback_file` (the SPA fallback, relative to
  the root), `autoindex` (default true), `indexes` (default true),
  `show_hidden_files` (default false), `delay` (ms).
- `hohenheim:redirect`: `target_url`, `http_status` (`301`/`302`/`307`/`308`),
  `preserve_path` (default false), `delay` (ms).

Answer: the site detail projection (as `GET /api/v1/sites/{id}`), which now
carries `domains` (the rows below, empty for a fresh site). A refusal is the usual
422 whose `code` is the violation key: `name_required`,
`zenit.coercion.unknown_field` (a stranger key, top-level or inside `settings`),
`upstream_instance_required`, and the coercion keys of the form.

### `POST /api/v1/sites/{id}/domains`

| Field | Type | Notes |
| --- | --- | --- |
| `hostname` | string, required | Stored canonical: trimmed, lowercased, root dot stripped (regex sources keep their case) |
| `match_type` | enum | `exact` (default), `wildcard`, `regex`; a glob-shaped hostname is stored as `wildcard` whatever this says |
| `listen_on` | string | One of the host's discovered local addresses; blank = every interface |
| `path` | string | Route prefix, canonicalized like the dispatcher (`app` -> `/app`, `/` -> catch-all) |
| `strip_path` | boolean | default false |
| `force_ssl` | boolean | default TRUE |
| `certificate_id` | integer | Pin a certificate; null = platform selection |
| `hsts_enabled`, `hsts_subdomains` | boolean | default false |
| `exclude_from_letsencrypt` | boolean | default false |
| `custom_headers.N.key` / `custom_headers.N.value` | rows | Request headers set on forward (empty value = delete the header). `Host` is honoured: this is the Apache-fallthrough spelling |
| `response_headers.N.key` / `response_headers.N.value` | rows | Response headers |
| `site_id` | integer | Optional; must equal the URL's site (`domain_site_mismatch` otherwise) |

Answer, and each element of `domains`:

```
{"id":21,"site_id":11,"hostname":"earl.example","match_type":"exact","listen_on":"",
 "path":"","strip_path":false,"force_ssl":true,"certificate_id":null,
 "hsts_enabled":false,"hsts_subdomains":false,"exclude_from_letsencrypt":false,
 "custom_headers":{"Host":"earl.phoenix"},"response_headers":{},
 "live":true,"generated":false}
```

`live` is whether the row holds its route claim right now (false on a disabled or
trashed site); `generated` marks a row a system authored (a preview hostname),
which no caller may edit or remove. Refusals: `hostname_required`,
`hostname_invalid`, `hostname_taken` / `route_taken` (same site),
`route_taken_other_site` / `route_overlaps_other_site` (admin reader) or the
neutral `hostname_unavailable` (a tenant reader: byte-identical whether the name
is held or merely covered by a foreign wildcard, so the lane is no hostname
oracle), `route_quarantined` (a released name another owner may not take back
yet), `tenant_*` for a delegated key writing a frozen column.

### Deletes

`POST /api/v1/sites/{id}/delete` runs `SiteResource.deleteRow`: previews reclaimed,
`deleted_at` stamped, the domain rows KEPT for a restore, every claim released. The
site serving the panel itself refuses (`delete_self_lockout`), exactly like the
form. `POST /api/v1/sites/{id}/domains/{domain}/delete` removes the row and
releases its claim into the quarantine ledger: the same owner may re-add the name
at once, a different owner waits out the quarantine.

## Access lists

The other half of the proxy tier's write lane, added for the same migration (an old
installation's htpasswd folders and IP allow-lists convert to one list plus one call
per rule). Same transport as sites: form-encoded, dotted keys nest
(`data.username`), no JSON body. The write goes through the panels' own resources
via zenit-cms' `ResourceWrites`, which is what argon2-hashes a basic-auth password
-- a value written any other way is stored in plaintext and then refuses every
visitor, so there is no second way in.

Rate limit: 120 route writes per minute per principal (`hh_paas_route_write`).

Doors, exactly the panels': BOTH panels create access lists, so this lane is not
admin-only. An admin key writes through the operator form (`AccessListResource`,
`shared` included); every other key writes through the delegated one
(`ManageAccessListResource`), which has no `shared` entry and plants the creator's
`manage` grant -- a tenant owns what it authored. Creating demands the panel
permission the form lives behind (`hohenheim.manage.access`, or the admin one), as
narrowed by the key's scopes, and answers **403** otherwise; every other verb asks
`manage` on the LIST and answers the uniform **404** for a list that is absent, not
yours, or merely SHARED with you (attaching a shared list is a picker's affordance,
not an editing right).

### `POST /api/v1/access-lists`

| Field | Type | Notes |
| --- | --- | --- |
| `name` | string, required | |
| `satisfy` | enum | `any` (default) or `all`; this IS the implicit root group's mode |
| `shared` | boolean | Offer the list to every picker. ADMIN keys only -- a delegated key submitting it is refused `zenit.coercion.unknown_field`, because the /manage form has no such entry |

Answer: the list detail (as `GET /api/v1/access-lists/{id}`), whose `rules` array is
empty for a fresh list.

### `POST /api/v1/access-lists/{id}/rules`

One node of the tree per call, the two steps the Rules tab takes (birth, then
configure) in one request.

| Field | Type | Notes |
| --- | --- | --- |
| `type` | enum, required | `group`, `basic_auth`, `ip_allow`, `ip_deny`, `auth_provider` |
| `parent_id` | integer | An enclosing GROUP of THIS list; absent, blank, or anything else means the implicit root |
| `enabled` | boolean | Absent keeps the birth default: a group is born ON, every leaf OFF |
| `data.*` | per type | The type's own schema; an undeclared key is refused (`zenit.coercion.unknown_field`) |

Settings per type:

- `group`: `data.satisfy` (`any` default / `all`).
- `basic_auth`: `data.username` (no `:`, RFC 7617), `data.password` (typed in
  plaintext, stored as an argon2 hash; blank on a later write keeps the stored one).
- `ip_allow` / `ip_deny`: `data.network`, one literal IP or CIDR (`203.0.113.8`,
  `10.0.0.0/8`) -- never a hostname.
- `auth_provider`: `data.provider_id`, `data.required_permission` (blank = any
  identity that provider authenticates).

Answer, and each element of a list's `rules`:

```
{"id":41,"access_list_id":31,"parent_id":38,"type":"basic_auth","enabled":true,
 "data":{"username":"earl"},"has_password":true}
```

The stored password is absent BY NAME, hash included: it is credential material, and
a value written as a credential has no representation over this API afterwards.
Refusals: `unknown_type`, `zenit.coercion.unknown_field`,
`access_rule_network_invalid`, `access_rule_username_invalid`,
`access_rule_provider_invalid`, and -- only once a rule is switched ON --
`access_rule_credential_incomplete`, `access_rule_provider_missing`. A refusal at the
CONFIGURE half leaves the node behind SWITCHED OFF, exactly as an abandoned add form
does: it enforces nothing, and the next read shows it.

`POST /api/v1/access-lists/{id}/delete` runs the resource's delete: the rule rows
cascade off the model hook, and whatever the list gated stops being gated -- which is
the dangerous direction, because a site with no access list allows everyone.

## Instances

`POST /api/v1/instances` carries TWO lanes, discriminated by whether the body has a
`template_id` KEY (its presence, never whether it resolves -- a typo'd id is still
refused `unknown_template` rather than falling into the other lane):

- WITH `template_id`: unchanged, the tenant's approved-template funnel
  (`InstanceTemplates.createFromTemplate`), which decides create authority, template
  approval, placement, typed variables, image policy and quota.
- WITHOUT: the admin create form's own pipeline over `InstanceResource`. ADMIN-ONLY
  (403 otherwise) for the site-create reason: `ManageInstanceResource` is deliberately
  not creatable, so the only panel with this form is the admin one.

| Field | Type | Notes |
| --- | --- | --- |
| `name` | string, required | |
| `kind` | enum, required | `hohenheim:docker_container`, `hohenheim:workspace`, `hohenheim:application`, `hohenheim:system_container`, `hohenheim:vm` (the authorable set; product-tier kinds are refused) |
| `server_id` | integer | The host. Honoured verbatim for an admin; absent means placement chooses, and refuses by name (`no_placement_capacity`, `host_capacity_unproven`, `no_placement_available`) rather than silently landing on the local daemon |
| `runtime_image_id` | integer | Required by `hohenheim:workspace` (`runtime_image_required`) |
| `environment_id` | integer | Grouping only; the environment's project must have the same owner set (`environment_project_mismatch`) |
| `crash_policy` | enum | `none` (default) or `restart` |
| `backup_target_id` | integer | |
| `settings.*` | per kind | The kind's own schema; an undeclared setting is refused (`zenit.coercion.unknown_field`) |

Settings per kind (every key optional unless said otherwise):

- `hohenheim:docker_container`: `image`, `tag`, `command`, `container_port`,
  `port_protocol` (`tcp`/`udp`), `port_exposure` (`loopback`/`public`), `host_port`,
  `environment_variables` (a map, secret), `volumes` (a map), `memory_limit_mb`,
  `cpu_limit`, `console_kind` (`plain`/`tty`).
- `hohenheim:application`: the git source below plus `image`, `tag`, `builder`
  (`dockerfile`/`nixpacks`), `dockerfile`, `build_arguments` (a map),
  `container_port`, `health_path`, `environment_variables` (secret),
  `keep_releases` (1..10, default 2), `memory_limit_mb`, `cpu_limit`, `console_kind`.
- `hohenheim:workspace`: the git source below plus `start_command`, `container_port`,
  `home_quota_mb`, `environment_variables` (secret), `memory_limit_mb`, `cpu_limit`,
  `console_kind`.
- git source (workspace and application): `repository_url`, `provider_id`,
  `repository`, `branch`, `build_command`, `build_directory`, `build_timeout`,
  `auto_deploy` (default true), `poll_interval`, `webhook_secret` (secret),
  `shallow_clone` (default true), `submodules`, `build_environment_variables`
  (secret), `previews_enabled`, `preview_branches`,
  `preview_environment_variables` (secret).

Map-shaped settings (`volumes`, `environment_variables`, `build_arguments`,
`build_environment_variables`, `preview_environment_variables`) ride the INDEXED ROW
transport the panel's editor posts, never a dotted sub-key: one `key`/`value` pair per
integer row scope. Row numbers only have to be UNIQUE, not contiguous.

```
settings.volumes.0.key=app
settings.volumes.0.value=/home/site
settings.environment_variables.0.key=ALCHEMY_ENV
settings.environment_variables.0.value=live
settings.environment_variables.1.key=LOG_LEVEL
settings.environment_variables.1.value=info
```

```
hoh instance create microcopy docker_container \
  settings.image=alpine settings.tag=latest \
  settings.volumes.0.key=app settings.volumes.0.value=/home/site \
  settings.environment_variables.0.key=ALCHEMY_ENV \
  settings.environment_variables.0.value=live
```

A row scope that is not an integer (`settings.volumes.app=/home/site`), a row that is
not a key/value pair, or a stranger sub-key inside one is refused
`zenit.coercion.unknown_field` naming the offending key, and nothing is written. Until
2026-08-30 the dotted spelling answered **200 with the record created and the map
EMPTY**, which is why the wire shape is documented here now. A submitted empty string
CLEARS the map; omitting the key entirely leaves the stored map alone.

Answer: the instance projection (as `GET /api/v1/instances/{id}`):

```
{"id":51,"name":"earl","kind":"hohenheim:application","status":"created",
 "install_state":"none","crash_policy":"none","template":"","created_at":"..."}
```

A create DEPLOYS NOTHING. The row lands in `created` and the explicit deploy verb
(`POST /api/v1/sites/{id}/deploy`, or the panel's own) starts it -- which is what the
create form does too, so there is no background provisioning to wait for here.
Table-backed variables are a separate call (`POST /api/v1/instances/{id}/variables`)
because they are a separate table; the `settings.environment_variables` map rides the
create.

`POST /api/v1/instances/{id}/delete` runs `InstanceResource.deleteRow`, which IS
`InstanceService.destroy`: the workload is torn down for real and the row soft-deleted.
The authority is the SERVICE's, not this route's -- `requireOperationCapability(...,
destroy)` -- so SEEING an instance (`view`, which eight capabilities imply) is not
enough to destroy it: a caller holding only `view` gets a **422**
`instance_not_permitted` (the refusal never says which capability is missing, so it is
no capability oracle) while an unrelated id gets the uniform 404. A failed teardown is
also a 422 (`instance_destroy_failed`) and leaves the record alive.

## Managed databases

Added 2026-09-02. Until then this tier had **no API verbs at all** -- a teardown
and a move onto a shared engine were browser-only, so an operator scripting a
migration had to drive the panel by hand. The verbs are the panel's own lanes
reached without a browser (`DatabaseApi`), and their doors are the panels':

- `GET /api/v1/databases` -- the `view` scope `ManageDatabaseResource` renders.
  An admin key gets the operator columns (`engine_id`, `server`, `ephemeral`,
  `memory_limit_mb`, `cpu_limit`, `failure_reason`) beside the shared ones (`id`,
  `name`, `engine`, `db_name`, `placement`, `status`, `attached`); a delegated key
  gets the shared ones only. That is not tidiness: a shared engine's name is
  another tenant's neighbour list, which is why the /manage table drops it too.
  The stored CREDENTIALS have no representation on this surface at all -- the
  Credentials tab answers to its own capability.
- `POST /api/v1/databases/{id}/move-shared` -- **ADMIN-ONLY** (403 otherwise),
  because only the admin panel offers the row action; `ManageDatabaseResource`
  drops it. Eligibility is `DatabaseService.moveRefusal`, the one declaration the
  row action's visibility reads as well, so the API refuses exactly what the panel
  would not offer: `database_already_shared`, `database_not_active` (naming the
  status), `database_placement_unsupported` (an engine with no logical databases)
  and `database_ephemeral_shared` (a tmpfs database is its own container by
  definition). Those refusals are made SYNCHRONOUSLY and by name; everything past
  them runs in the BACKGROUND exactly as the row action does, so the answer is
  `{"id", "name", "status": "queued", "watch": "status"}` and the outcome shows up
  on the record's own `status` (provisioning while it works, active when it
  settles, with `failure_reason` filled on a failed move). Block on it with `hoh
  database wait <id>`, which polls `GET /api/v1/databases/{id}` every 2 seconds and
  exits **0** when the record lands active, **1** when it lands failed (printing the
  reason), **2** on timeout (default 600s, `--timeout`) naming the last status it saw.
  Rate limit: the database I/O bucket (5 per
  minute, `hh_db_io`) -- the move dumps and restores a whole database.
- `POST /api/v1/databases/{id}/delete` -- rides `DatabaseResource`'s own delete
  pipeline. Seeing a database (`view`) is not enough to destroy it: the resource
  demands `destroy` on the record and `DatabaseService.destroy` asks the same gate
  again, so a `view`-only key gets **403**. A database a live workload still holds
  is a **422** `delete_in_use` naming the workloads and the page each is detached
  on -- literally the reason the panel's dead Delete button renders, because the
  pipeline asks `deleteUnavailableReason` before it acts. (`deleteRow` refuses the
  same fact as `database_in_use`; the dead-affordance reason is what a caller sees
  first, and the two never disagree.) An
  unverifiable teardown is a 422 `database_destroy_failed` and keeps the record
  (status `destroy_failed`); the force-destroy escape hatch stays panel-only, as
  an operator decision about the operator's own machine.
- `GET /api/v1/databases/{id}` -- the same projection for one record, which is what a
  watcher polls. Beside `status` every projection carries **`outcome`**: `pending`
  (still in flight), `ok` (settled usable) or `failed`. It is derived from the status
  vocabulary's own declaration (`DatabaseModel.outcomeOf`), so no client carries a list
  of status names, and an UNRECOGNISED status reads `pending` -- fail closed: a poller
  keeps waiting and eventually times out naming what it saw, rather than reporting a
  success the vocabulary never declared.
- `GET /api/v1/engines` -- **ADMIN-ONLY**, because a `DatabaseEngineModel` row
  exists on the admin panel alone. `id`, `name`, `engine`, `image`, `server`,
  `memory_limit_mb`, `cpu_limit`, `databases` (the count living on it), `status`,
  `failure_reason`. The engine's superuser credentials are absent BY NAME.
- `GET /api/v1/engines/{id}` -- the same fields plus `logical_databases`: every record
  living on that engine with `id`, `name`, `db_name`, `db_user`, `status`, `outcome`
  and its effective ceiling. `usage_mb` is present only when the stats hub already holds
  a sample for the engine's container (somebody is watching it); this surface never
  opens a daemon stats stream for a REST read, so an absent field means "not being
  watched", never "zero".

### Effective memory, and where the number came from

`memory_limit_mb` is what the RECORD declares, which for two of the three shapes is
nothing at all -- so the column was EMPTY exactly where an operator needed a number.
Every projection therefore also carries:

- `effective_memory_mb` -- the ceiling the database actually runs under. Because
  charge == cap, this is both the cgroup limit and what the host budget is charged.
- `memory_source` -- `declared` (the record's own `memory_limit_mb`), `default` (the
  engine kind's declared footprint, which is what the booking used), or `engine` (a
  SHARED record has no container of its own; the ceiling is its engine's).

Both fields are computed by asking the very handler the capacity hook books through
over the very settings the deploy writes (`DatabaseService.memoryCeilingOf`), so the
panel, the host ledger and the CLI cannot quote three different ceilings. They are
absent -- never guessed -- for a shared record whose engine row has gone missing.

The CLI prints one column with the source MARKED: `512` is declared, `1280*` is the
default the booking used, `->1024` is inherited from the shared engine.

An unknown or out-of-scope id answers the uniform **404** on every verb, exactly
as the instance lane does: absence and denial are one answer.

The CLI mirrors the panel's dialogs: `hoh database move` and `hoh database delete`
both prompt for the database NAME before they act (`--yes` skips it in scripts),
the same client interlock `hoh rollback` carries. The server demands no phrase --
a ConfirmationSpec is a client interlock by design -- so the prompt lives where
the dialog does.

## Hosts and capacity

Added 2026-09-02, for the same reason the database verbs were: the memory picture
existed only on the admin overview page, so an operator deciding where a database
could go was reading `docker stats` -- which answers a DIFFERENT question. `docker
stats` measures live RSS; placement decides on the BOOKED ceiling, and a stopped
workload still holds its booking (a guest that can be started again without asking
anyone must be counted, or the refusal lands on START).

Both verbs are **ADMIN-ONLY** (`hohenheim.admin.access`, as narrowed by the key's
scopes): a host row exists on the operator panel alone, and the single-host form
enumerates every tenant's workload on the machine by name.

- `GET /api/v1/hosts` -- per host: `id`, `name`, `runtime`, `mode`, `admission`
  (`blocked` / `admitted` / `draining`), `preflight_ok`, then the ledger --
  `measured`, `stale`, `budget_mb`, `booked_mb`, `bookable_mb`, `free_mb`,
  `overcommit_ratio`, `reserve_mb`, `measured_at`, `facts_max_age_hours`.
- `GET /api/v1/hosts/{id}` -- the same plus `workloads`: `id`, `kind`, `name`,
  `status`, `booked_mb` and, when available, `usage_mb`. Database engines and
  dedicated databases appear here as `hohenheim:database_container` instances,
  because the tier is lowered onto the instance contract -- one walk enumerates the
  whole ledger.

Three things worth knowing before reading the numbers:

- `measured: false` is an explicit state, never an empty host. A host whose memory
  reading is missing or older than `capacity.facts_max_age_hours` has NO placement
  budget (`InstancePlacement` refuses to choose it), and the budget columns are then
  zero because there is no budget -- not because nothing is booked. `stale` separates
  "the evidence is too old" from "never measured".
- `budget_mb` can exceed the machine: it is `mem_total - capacity.host_memory_reserve_mb`
  multiplied by `capacity.memory_overcommit_ratio`, both of which the response names,
  so an overcommitted host is explicable rather than surprising.
- `usage_mb` is the instance stats hub's LAST SAMPLE, present only while somebody is
  watching that workload. This surface never opens a daemon stats stream (one host
  listing would become N of them, for a number placement does not decide on anyway),
  so an absent field means "nobody is watching", never "zero".

Every number is the ledger the admin overview page reads (`InstanceCapacity.viewOf`,
`InstanceCapacity.bookedMbOf`); nothing is recomputed here, so the panel and the CLI
cannot disagree.

## DNS zones

The DNS half of the migration lane (`docs/dns-migration.md`): a primary zone created
through the admin form's own pipeline (`DnsZoneResource` via zenit-cms
`ResourceWrites`, so the origin is canonicalized, the SOA fields validated and the
apex NS rows SEEDED from the controller's declared nameservers exactly as for a form
save), and the Zone-file tab's import reached with an API key. Every verb demands the
admin permission as narrowed by the key's scopes and answers **403** otherwise: zones
are an operator surface (the tenant panel exposes records under a delegated zone,
never zones). Rate limits: `hh_paas_read` on the list, `hh_paas_route_write` on the
writes.

### The declared nameserver set

Setting `dns.nameservers` (a list, `ns1.example.be` one per line) is THE declared
nameserver set of a controller, with three consumers: a new primary zone gets one
apex NS row per name (seeded once at create, then ordinary operator-editable rows,
never re-asserted); an import replaces the file's apex NS RRset with them unless
told to keep it; the delegation check reports an apex set that disagrees with them
as `apex_undeclared`. Nothing else generates apex NS rows.

### `GET /api/v1/dns/zones`

`{"zones":[...]}`, one element per zone, ordered by origin:

```
{"id":3,"origin":"example.be","role":"primary","enabled":true,"serial":12,
 "soa_primary_ns":"ns1.example.be","soa_contact":"hostmaster@example.be",
 "default_ttl":3600,"delegation_status":"matches",
 "nameservers":["ns1.example.be","ns2.example.be"],"record_count":9}
```

`nameservers` are the zone's ENABLED apex NS row targets in row order (what the
responder serves), never the setting.

### `POST /api/v1/dns/zones`

The zone form's fields, form-encoded:

| Field | Type | Notes |
| --- | --- | --- |
| `origin` | string, required | Stored canonical (lowercased, root dot stripped); `dns_origin_format` / `dns_origin_taken` |
| `enabled` | boolean | default true |
| `role` | enum | `primary` (default) / `secondary`; a secondary needs `primary_peer_id` and is seeded with nothing |
| `soa_primary_ns`, `soa_contact` | string | SOA MNAME / RNAME (`dns_target_format`, `dns_contact_format`) |
| `default_ttl`, `negative_ttl`, `soa_refresh`, `soa_retry`, `soa_expire` | integer | seconds, model defaults apply (`dns_ttl_range`, `dns_interval_range`) |
| `dnssec_enabled` | boolean | default false |

Answer: the zone element above, `nameservers` carrying the declared set. A stranger
key is `zenit.coercion.unknown_field`.

### `POST /api/v1/dns/zones/{id}/import`

| Field | Type | Notes |
| --- | --- | --- |
| `zone_text` | string, required | Standard master-file text (`$ORIGIN`/`$TTL` honoured, `$INCLUDE` refused); `import_empty` when blank |
| `keep_ns` | flag | Any non-blank value keeps the file's apex NS rows; absent = replace them with the declared set |

What it does, exactly: REPLACES every operator-managed row (`managed_by` null) with
the file's rows, keeps Hohenheim-managed rows (ACME), ignores the SOA (the zone row
owns those values) and NAMES the ignored MNAME/RNAME/serial/TTL in `notes`, drops
the file's apex NS rows and writes the declared set (naming the swap in `notes`)
unless `keep_ns`, bumps the serial and reloads the served snapshot. Answer:

```
{"id":3,"origin":"example.be","serial":13,"imported":11,
 "skipped":["mail.example.be. HINFO (unsupported type)"],
 "notes":["SOA ignored: ns1.afraid.org dnsadmin.afraid.org serial 2604070003 ttl 3600 (the zone form owns the SOA values)",
          "apex NS ns1.afraid.org, ns2.afraid.org replaced by the declared ns1.example.be, ns2.example.be"],
 "nameservers":["ns1.example.be","ns2.example.be"]}
```

Refusals (422): `import_nameservers_undeclared` (the file carries a foreign NS set,
nothing is declared and `keep_ns` was not given: the rows are left untouched rather
than published under nobody's nameservers), `import_secondary_zone` (a secondary's
rows are its primary's), `import_failed` (unparseable text, the parser's reason in
the message). An unknown zone is 404.

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
API before storing).

### Contexts

One config file holds every controller you drive, as a named context, plus a
`current` naming the stored default. Selecting a context and MOVING the default
are deliberately two different acts: `hoh context use` moves it, while
`--context <name>` (or `HOH_CONTEXT`) selects a context for a single invocation
and leaves it where it was. That is what lets two lanes on two boxes run
concurrently -- before, each `hoh context use` clobbered the other lane's
default mid-flight, and `HOH_HOST`/`HOH_TOKEN` were the only way out.

Precedence is explicit-beats-ambient:

1. `--context <name>` -- a flag typed on this command line, so it wins outright,
   `HOH_HOST`/`HOH_TOKEN` included. A named context always presents its OWN
   stored key, never the ambient `HOH_TOKEN`.
2. `HOH_HOST`/`HOH_TOKEN` -- bypass the config file entirely.
3. `HOH_CONTEXT` -- names a stored context, same as `--context` but ambient.
4. `current` in the config file, else the context named `default`.

```
hoh context list                      # name, host, whether a key is stored, default, selected
hoh context list --json               # { contexts, current, selected, env_host }
hoh context use staging               # move the stored default (the only thing that does)
hoh --context staging sites           # one command against staging; the default never moves
HOH_CONTEXT=staging hoh sites         # the same, ambient
hoh --context staging login https://staging.example   # store a key UNDER that name
```

`context list` makes no API call, so it still answers when the default context's
host is unreachable. An unknown name is a named refusal naming the config file,
never a crash.

A context whose host is a loopback address means only as much as the ssh forward
behind that port. Every panel binds `127.0.0.1:3000` on its own box, so a context
pointed at `http://127.0.0.1:3000` names whichever box the forward happens to
serve -- on 2026-08-30 the `robbedoes` context resolved to **kuifje** for exactly
that reason. Nothing was written to the wrong controller (an API key is
box-specific, so a misdirected call fails authentication rather than mutating a
foreign box), but the failure mode is confusing rather than loud. Give each
controller its own dedicated local port instead, and open the forward on that
port: `robbedoes` is `127.0.0.1:13020`. A forward that is not open then refuses
the connection, which is the honest answer.

### Commands

```
hoh login https://panel.example       # prompts for the key, hidden
hoh projects | hoh projects 3
hoh sites | hoh site 7
hoh site create Earl hohenheim:address settings.forward_host=127.0.0.1 \
    settings.forward_port=8080 settings.rewrite_location=false   # admin; fields verbatim
hoh site domains 11
hoh site domain add 11 earl.example custom_headers.0.key=Host custom_headers.0.value=earl.phoenix
hoh site domain remove 11 21 [--yes]  # asks for the site slug
hoh site delete 11 [--yes]            # admin; asks for the site slug
hoh deploy 7
hoh rollback 7 [--yes]
hoh releases 7 | hoh release 7 12     # detail prints the step log
hoh builds 7 | hoh build-log 7 4
hoh deployments 7 | hoh deploy-log 7 9
hoh instances | hoh instance 3 | hoh instance show 3
hoh instance create earl hohenheim:application \
    settings.repository_url=https://example.test/earl.git settings.branch=main  # admin
hoh instance delete 3 [--yes]         # destroys the workload; asks for the name
hoh access-list list | hoh access-list 31
hoh access-list create Staff satisfy=all shared=true   # shared is admin-only
hoh access-list rule add 31 basic_auth data.username=earl data.password=hunter2 enabled=true
hoh access-list rule add 31 ip_allow data.network=10.0.0.0/8 enabled=true
hoh access-list delete 31 [--yes]     # takes its rules; asks for the name
hoh database list                     # memory column: 512 declared, 1280* default, ->1024 engine
hoh database wait 61 [--timeout 600]  # polls every 2s: exit 0 active, 1 failed, 2 timed out
hoh database move 61 [--yes]          # admin; onto the shared engine, in the background
hoh database delete 61 [--yes]        # asks for the name; refused while attached
hoh engine list                       # admin; shared engines and their database counts
hoh engine show 71                    # admin; the engine, its databases and its live usage
hoh host list                         # admin; budget, booked, free, overcommit per host
hoh host show 1                       # admin; plus every workload booked on it
hoh logs 3 -n 500
hoh power 3 restart
hoh vars instance 3                   # secrets show "(set)", never the value
hoh vars instance 3 set KEY value
hoh vars instance 3 set TOKEN --secret   # value prompted hidden, off argv
hoh vars instance 3 unset KEY
hoh vars env 5 set KEY value          # environment (deploy baseline) values, ADMIN-ONLY
```

`--json` prints the raw API response of any read; `hoh help` (also `--help`/`-h`)
prints the command list. Tests: `node --test "tools/*.test.js"`
(stub server; proves paths, the key header, the rollback and delete interlocks,
the verbatim field pass-through of the site verbs, secret masking, and that a
`--context`/`HOH_CONTEXT` selection never moves the stored default), driven in the verification lane by `HohCliTest`. Server-side coverage:
`PaasApiTest` and `SiteApiTest` (browserTest).

## The `hoh-import-legacy` converter

`tools/hoh-import-legacy` turns an OLD (Node/Mongo) Hohenheim `sites` collection
into exactly the calls above, driving `hoh` and nothing else. Dry-run by default;
everything the new system cannot express is fail-closed and named in a manifest.
Procedure, mapping table and manifest shape: `docs/legacy-import.md`. Tests:
`node --test "tools/*.test.js"`.
