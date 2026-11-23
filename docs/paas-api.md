# PaaS API v1 and the `hoh` CLI

The automation seam over the PaaS machinery: projects/environments, sites,
deploy/rollback, four operation-record lanes (git deployments, releases,
sandbox builds, artifact uploads), per-instance logs and the variable mechanism. The CLI is a
thin client of this API; nothing is reachable through it that the API does not
offer, and nothing in the API is a wider door than the admin/manage UI.

PaaS ("Platform as a Service") names Hohenheim's existing application-hosting
machinery, not a separately installed platform. Artifact upload is another source
for that machinery: a normal Zenit endpoint and zenit-auth principal reach
`ArtifactDeploys`, then `ApplicationReleases` / `ReleaseEngine` and `InstanceService`.
Runtime isolation, volumes and proxy handoff remain owned by those existing layers;
there is no Microcopy-specific deployment or authentication path.

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

Sites are visible exactly where the key's owner holds the `manage` record
capability AND the key's scopes cover that vocabulary; instances resolve
visibility at `view` (below). Project
listings additionally require the key to cover site or instance `manage`
(membership is grant-derived, so the listing enforces the scope itself).

**Instance VISIBILITY resolves at `view`, not `manage`.**
`InstanceApi.visibleInstances`/`visibleInstance` ask
`HohenheimCapabilities.VIEW` and nothing else, by design -- visibility answers "may
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
    "code": "unknown_field",
    "message": "This field is not part of the form",
    "field": "settings.root_paht",
    "violations": [
      { "code": "unknown_field",
        "message": "This field is not part of the form",
        "field": "settings.root_paht" }
    ]
  }
  ```

  `code` is the same microcopy key the HTML surface renders for the same act, and
  `message` is resolved in the caller's locale chain. Framework refusals carry
  zenit's SHORT keys (`unknown_field`, `max`, `required`, `invalid_integer`, ...);
  the `zenit.coercion.*` / `zenit.validation.*` spellings were retired upstream
  (zenit 383bcc89) and Hohenheim no longer answers them. `field` is the path of the
  refused value -- dotted for nested settings, indexed for rows -- and is ABSENT
  when the refusal is about the submission rather than one value. `code`,
  `message` and `field` restate the FIRST entry of `violations`, which lists every
  refusal; a client that only reads the top three keeps working.
- Responses are enumerated whitelists, never row dumps. Site settings
  (environment maps, webhook secrets, api keys) never appear.
- Rate limits: reads 120/min, variable writes 30/min, deploy/rollback 10/min,
  per principal. The DNS record API (`/api/dns/zones/{origin}/records*`) is limited
  per KEY for every key that is no admin key (`hh_dns_record_api`, below).

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
| POST | `/api/v1/sites/{id}/artifact` | Raw JAR upload; returns **202 pending**, never a deployment-success claim |
| GET | `/api/v1/sites/{id}/artifact/{op}` | Durable artifact operation scoped to the exact site and application |
| GET | `/api/v1/sites/{id}/artifact` | Currently serving artifact, not the latest uploaded artifact |
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
| GET | `/api/v1/instances/{id}/volumes` | The app's declared volumes (whoever may see the app) -- see App volumes below |
| POST | `/api/v1/instances/{id}/volumes` | Declare a volume: `name`, `container_path`, optional `quota_mb`, `exclusive` (admin, with the app's `config` capability) |
| POST | `/api/v1/instances/{id}/volumes/update` | Redeclare the volume `name`: only the fields sent change |
| POST | `/api/v1/instances/{id}/volumes/delete` | Delete the volume `name` WITH its data |
| POST | `/api/v1/instances/{id}/volumes/put?name=...&path=...` | Put the raw body as one file into the volume `name` at `path` (admin, with the app's `files.write` capability), also before the first deploy |
| GET/POST | `/api/v1/environments/{id}/variables[/delete]` | Same shape, but ADMIN-ONLY (`hohenheim.admin.access`, as narrowed by the key's scopes) -- see Environment variables below |

| GET | `/api/v1/databases` | Managed databases the key holds `view` on -- see Managed databases below |
| GET | `/api/v1/databases/{id}` | One managed database, the shape `hoh database wait` polls (`status` + `outcome`) |
| POST | `/api/v1/databases/{id}/move-shared` | Move a dedicated database onto its host's shared engine (ADMIN-ONLY, 403 otherwise); accepted/queued, the record's `status` carries the outcome |
| POST | `/api/v1/databases/{id}/delete` | Destroy a managed database exactly as the panel's delete does (the `destroy` capability, asked by the teardown service itself); 422 `delete_in_use` while a workload holds it |
| GET | `/api/v1/engines` | Shared database engines with their database counts (ADMIN-ONLY) |
| GET | `/api/v1/engines/{id}` | One engine plus the logical databases on it, and its container's live usage when the stats hub has a sample (ADMIN-ONLY) |
| GET | `/api/v1/hosts` | Hosts with their memory ledger: budget, booked, free, overcommit (ADMIN-ONLY) -- see Hosts and capacity below |
| GET | `/api/v1/hosts/{id}` | One host plus every workload booked on it (instances AND database engines) |
| GET | `/api/v1/certificates` | Certificates the key may read (the `/manage` certificate scope: the ones it ordered, plus `view` grants) -- see Certificates from a signing request below |
| POST | `/api/v1/certificates` | Order a certificate from the caller's own signing request: `csr` (PEM), `hostnames`, optional `name`; the same key for the same names again answers the existing certificate |
| GET | `/api/v1/certificates/{id}` | Status: names, holder, expiry, the issued key's pin, the last failure |
| GET | `/api/v1/certificates/{id}/chain` | The public chain (what the admin chain download serves); 404 before issuance |
| POST | `/api/v1/certificates/{id}/signing-request` | Re-order a requester-held certificate from a NEW request (`csr`, optional `hostnames`): the only way its key changes |
| POST | `/api/v1/sites/{id}/domains/{domain}/certificate` | Order the certificate the address row's "Get a certificate" orders, whose key Hohenheim holds (ADMIN-ONLY, 403 otherwise) -- see Certificates for an address below |

Also present (older lanes, admin-permission-gated): `/api/sites` and
`/api/sites/{id}/deploy`. The DNS record API `/api/dns/zones/{origin}/records[/{id}[/delete]]`
takes any key and judges a key that is no admin key by the /manage tenant gate
(`docs/dns-federation.md`, Central editing); its rate limit is `hh_dns_record_api`,
60 requests per minute PER KEY (an anonymous caller per address), shared by its four
routes, answered 429 with `Retry-After` once spent. An admin key -- every DNS peer's
edit-forwarding key is one -- is never limited: `DnsRecordApiHandlers` registers a
`RateLimitPolicies` resolver that lifts the policy for it, zenit's per-request seam, so
the exemption is the framework's rate-limit lane and no handler counts anything.

The instance file API under `/api/v1/instances/{id}/files` is a v1 route
(`API_INSTANCE_FILES` and `API_INSTANCE_FILE_CONTENT`,
`HohenheimEndpoints.java:481-500`) and is not admin-permission-gated: the read
lane asks `InstanceFiles` for `files.read` and the write lane for `files.write`,
both INSIDE the service, which is exactly what stops this surface and the Files
tab holding different policies. The path always travels as the `path`
QUERY PARAMETER, never as a route segment (a segment would be split and
reassembled, and a second decode is how a normalized traversal slips in), and
the lane carries its own read rate limit.

## Artifact deployments

The site must expose an application. Uploads require both site `manage` and the
application's `config` capability, with the same gates checked again after the
bounded upload. A site manager does not thereby gain authority over its application.

The POST carries raw JAR bytes. The acceptance response contains `operation_id`,
`status: pending` and `artifact_sha256`. Poll the operation until `succeeded`,
`failed` or `interrupted`; `pending` and `running` are not successful deployments.
The operation also carries `instance_id`, `image_id` and a safe `error` code.
A controller restart marks unfinished old operations interrupted.

JARs are content-addressed and immutable. A per-application lock serializes artifact
builds and release handoff with ordinary deploys and backups. The accepted
source pointer advances only after the requested artifact AND resolved runtime
settings pass the release health gate. A refused candidate leaves the old serving
release and accepted source intact. Ordinary deploys and stopped restarts reuse the
persisted source; uploading the JAR again is not required.

The selected runtime image is the artifact's base image. Its command template,
workdir and port are inherited unless the application explicitly overrides them.
Application variables participate in source identity; generated releases snapshot
them through the encrypted variable carrier rather than plaintext settings. Health
requires a complete HTTP response with a 2xx or 3xx status.

The current-artifact GET reports `status: running|stopped|absent`,
`artifact_sha256`, `instance_id`, `image_id` and raw `stamps` text (nullable for
unstamped artifacts). It follows the serving release through rollback. `running`
describes workload state, not a fresh HTTP health probe.

`zenit-dev deploy` waits for the operation and verifies its identity against this
current report. Failure, interruption, timeout and supersession are not reported as
deployed. The client refuses dirty or invalid provenance before uploading; dry runs
perform no build, credential read or network request.

The trust boundary is deployment authority, not artifact safety: a caller allowed
to replace the executable can run code that reads the application's credentials
and data. Container isolation remains the boundary to the host and other workloads.
The endpoint uses the existing deployment rate limit and bounded body streaming;
JAR validation bounds directory metadata and inflation and checks member integrity
without executing the application. These controls are not malware detection or an
exhaustive security-audit claim.

Provenance checks are a CLIENT workflow guard, not server-enforced attestation.
An appropriately authorized direct caller can upload a valid unstamped JAR.
SHA-256 identifies the uploaded bytes; build stamps describe provenance, but neither
is a trusted-builder signature. The server does not require signed artifacts.

Application backups use the existing encrypted archive and backup-target mechanism.
They include the serving JAR, exported image, application volumes, authored profile
and variable inventory, and restore into a NEW application. Unapplied secret,
command or runtime-default changes are refused before stopping the workload:
pairing the old image with newer configuration is not a coherent recovery point.
Code-only rollback remains backupable through the serving artifact's identity.

`ApplicationBackupRecoveryTest` exercises restore after source/image loss, secret
restoration and failed-capture handling with real archive encryption and HTTP health
probes over a controlled, stateless runtime fixture. It does not prove live volume
contents, kernel quotas or a production off-host disaster-recovery exercise.

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

### Where a site connects: the operator tier

Some targets dial with any-address reach (a loopback or LAN backend, a controller
path), so WHERE an operator-owned record connects is written under
`hohenheim.admin.system` alone (`OperatorTrustedWrites`): a site's `upstream_kind`,
`settings` and `trusted_upstream`, a git provider's base URL, an app's local source.
`hohenheim.admin.system` is NOT delegable, unlike `hohenheim.admin.access`: a delegated
admin keeps every other column, and the installation operator (the bootstrap account,
which holds `*`) is the one who holds it. A site the API or the panel creates as an
admin is operator-owned (no one is granted `manage` on it), so creating one that serves
an app (`hohenheim:instance`), or an address upstream, is that tier's act; a write
without it is the 422 `operator_trusted_target` on the first target column it touches
("Only the installation operator can change where this record connects").

A key reaches exactly the panel's decision, because a key's authority is its OWNER's
grants intersected with its SCOPES (`PermissionResolver`). The panel session of the
operator carries `*` unnarrowed, so the panel passes; the operator's key passes only
when its scopes cover `hohenheim.admin.system`. A key scoped `hohenheim.admin.access`
alone is an admin key for every admin-only route and is still refused here -- that is
the refusal an operator key met on a live install. To automate site creation, the
operator mints a key on their own account (`/account/api-keys`) whose scopes include
`hohenheim.admin.system` beside `hohenheim.admin.access` (or `hohenheim.*`). Nobody
else can mint that authority: a delegated admin's key with the same scope carries
nothing its owner lacks, so it is refused exactly as its owner is in the panel, and a
tenant's key never reaches the admin-only create at all (403). The rule is the same for
every upstream kind and every guarded record. `SiteApiTest.anAppSiteNeedsTheOperatorTierOnTheKeyAsOnThePanel`
pins all four answers.

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
| `settings.*` | per kind | The kind's own schema; an undeclared setting is refused (`unknown_field`) |

Settings per kind (every key optional unless said otherwise):

- `hohenheim:address`: `forward_scheme` (`http`/`https`), `forward_host`,
  `forward_port` (integer), `socket` (a unix socket path, instead of host+port),
  `upstream_protocol` (`http1`/`h2`), `request_timeout` (seconds, 0 to 3600, 0 = no
  limit), `idle_timeout` (seconds, 0 to 3600: ends a request on which no byte moved in
  either direction for that long; unset or 0 = no idle limit; not applied once a
  connection is upgraded), `websocket_upgrade` (default true), `ignore_certificates` (default false),
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
`unknown_field` (a stranger key, top-level or inside `settings`),
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
| `shared` | boolean | Offer the list to every picker. ADMIN keys only -- a delegated key submitting it is refused `unknown_field`, because the /manage form has no such entry |

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
| `data.*` | per type | The type's own schema; an undeclared key is refused (`unknown_field`) |

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
Refusals: `unknown_type`, `unknown_field`,
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
| `image_app_id` | integer | `hohenheim:docker_container` only: the app whose serving release's image the container runs instead of its own `settings.image`/`settings.tag` (both must be empty, `image_and_image_from_app`). Same server and same owner as the app (`image_from_app_other_server`, `image_from_app_other_owner`); operator-only, a tenant write is refused (`tenant_field_frozen`). See "A container that follows an app" below |
| `settings.*` | per kind | The kind's own schema; an undeclared setting is refused (`unknown_field`) |

Settings per kind (every key optional unless said otherwise):

- `hohenheim:docker_container`: `image`, `tag`, `command`, `container_port`,
  `port_protocol` (`tcp`/`udp`), `port_exposure` (`loopback`/`public`), `host_port`,
  `environment_variables` (a map, secret), `volumes` (a map), `memory_limit_mb`,
  `cpu_limit`, `console_kind` (`plain`/`tty`).
- `hohenheim:application`: the git source below plus `image`, `tag`, `builder`
  (`dockerfile`/`nixpacks`), `dockerfile`, `build_arguments` (a map),
  `container_port`, `health_path`, `environment_variables` (secret),
  `pre_deploy_command`, `pre_deploy_timeout` (10..1800 seconds, empty is 300),
  `drain_seconds` (0..600, empty uses the installation's `releases.drain_seconds`),
  `keep_releases` (1..10, default 2), `memory_limit_mb`, `cpu_limit`, `console_kind`.
  For an application, `build_directory` is the build CONTEXT of a Dockerfile or
  Nixpacks build (the `dockerfile` path is read inside it; empty is the repository
  root), not a build command's working directory. `pre_deploy_command` runs once
  before every forward deploy in a one-shot container of the new release (split on
  spaces, no shell, `{{KEY}}` not replaced, the variables in its environment); a
  non-zero exit (`release_pre_deploy_failed`) or a time-out
  (`release_pre_deploy_timed_out`) stops the deploy and the current release keeps
  serving. It never runs on a rollback, a restore, a preview or a restart, and
  changing only it makes the next deploy a new release. `drain_seconds` is how long
  the superseded release keeps running after a deploy or rollback switched traffic;
  a newer deploy, a rollback, a Stop or a controller restart ends it early. All
  three, and `build_directory`, are operator-only: a tenant write is refused
  (`tenant_field_frozen`).
- `hohenheim:workspace`: the git source below plus `start_command`, `container_port`,
  `home_quota_mb`, `environment_variables` (secret), `memory_limit_mb`, `cpu_limit`,
  `console_kind`.
- git source (workspace and application): `repository_url`, `provider_id`,
  `repository`, `branch`, `build_command`, `build_directory`, `build_timeout`,
  `auto_deploy` (default true), `poll_interval` (RETIRED: still accepted and stored so an
  existing client keeps working, but nothing reads it; a new revision arrives by the webhook),
  `webhook_secret` (secret),
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
`unknown_field` naming the offending key, and nothing is written, so a wrong
spelling can never create a record with an EMPTY map. A submitted empty string
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

### A container that follows an app

A container with `image_app_id` set runs the image of the release its app serves at each start, and is stopped and
started again on the new version after every deploy or rollback of the app (`followed_app` on its activity), unless
it was stopped by hand. There is never more than one container per record. What a client does NOT get: the stop
grace is 10 seconds, the restart follows a few seconds after the app's switch (not atomically), the container keeps
running when the app is stopped, and it cannot be migrated (`migrate_follows_app`). While a container follows an app,
that app's delete (`app_delete_has_followers`), server change (`app_server_change_has_followers`) and kind change
(`app_kind_change_has_followers`) are refused, and a backup of the container cannot be restored to a new instance
(`restore_follows_app`). A start before the app's first deploy is refused `image_from_app_not_deployed`.

## App volumes

An app's volumes are its Volumes tab over the API: the SAME operations
(`hohenheim:declare_volume`, `hohenheim:redeclare_volume`, `hohenheim:destroy_volume`,
`hohenheim:put_volume_file`, `VolumeOperations`) run through the operation pipeline, so their own gates decide: the
admin permission, and the app's `config` capability for a declaration, a redeclaration and a delete, asked again by
the handlers (`VolumeDeclarationOperations`). The route resolves the app at `view` first, as
the instance lane does, so an app the key may not see is the uniform 404; a key that may
see it reads its volumes (the /manage twin shows them read-only) and is refused every write
with 403. A volume is named in the body (`name`), as a device is; the refusal on the app is
asked BEFORE the name is looked up, so a refused key gets the same 403 for a declared and an
undeclared name, and a name the app does not declare is the 404 only to a key that may write.
Rate limits: `hh_paas_read` on the list, `hh_paas_write` on the writes.

| Field | Type | Notes |
| --- | --- | --- |
| `name` | string, required | One plain path segment (`volume_name_invalid` otherwise); the host directory is `<data_path>/volumes/<app id>/<name>` |
| `container_path` | string, required | Where the app's releases mount it; two volumes at one path refuse `volume_container_path_conflict` at deploy |
| `quota_mb` | integer | The size limit; positive (`volume_quota_invalid`), absent = no limit |
| `exclusive` | boolean | At most one workload holds it at a time (a release drains the old one first) |

The app is the ROUTE's: `instance_id` is fixed by the route, so a declaration naming
another app is refused `fixed`, and a redeclaration naming one `volume_rehome_unsupported`.
On a redeclaration and a delete, `name` SELECTS the volume, so it never renames one. A kind
that mounts no volumes refuses `volume_kind_unsupported`. Each answer carries the volume:

```
{"id":51,"status":"declared","volume":{"name":"data","container_path":"/data",
 "quota_mb":512,"exclusive":false,"used_bytes":null,"observed_at":null}}
```

`used_bytes` and `observed_at` are the last measurement, null until one was taken. The
host directory is absent by name, as the instance projection keeps the host out.

`POST .../volumes/delete` DELETES THE DATA: the directory goes at the host first, then the
declaration, so a removal the host refuses keeps the record (`volume_backend_unimplemented`
and the backend's own refusals are 422). A workspace's `home` refuses
`volume_home_protected`. The server demands no phrase -- the dialog's typed name is a
client interlock -- so `hoh volume delete` asks for the volume's name (`--yes` skips it).

A declaration creates the volume's directory on the app's host first, with its quota and
its kind's volume owner (`InstanceVolumes.declareOnHost`), then writes the record; a host
whose volume backend cannot deliver a volume refuses the declaration by name
(`volume_backend_unimplemented`, 422) instead of the first deploy.

### Putting a file into a volume

`POST /api/v1/instances/{id}/volumes/put?name=data&path=app.sqlite` writes the raw
request body (any content type; `--data-binary @file`) as ONE file into the volume
`name`, at `path` below the volume's `container_path`, over a file already there. It is
`hohenheim:put_volume_file` through the pipeline: the admin permission, then the app's `files.write` capability, both
asked before the volume is even looked up (a refused key gets the same 403 for a declared and an undeclared name) and
BEFORE a byte of the body is read; a volume the app does not declare is the 404. Rate
limit `hh_instance_files_write`.

| Parameter | Notes |
| --- | --- |
| `name` | The declared volume, as on the other volume routes |
| `path` | Relative, one canonical spelling (`a/b.db`, never `/a`, `a//b`, `./a` or `../a`), its parent directories already in the volume; anything else, a symlink anywhere on the way and a path landing in another volume nested inside refuse `files_path_refused` (422) |

The body is streamed to disk, never buffered, and capped by the file manager's own limit
(`hohenheim.files.max_file_kb`, 8 MiB by default): over it is `files_too_large` (422). The
file is written by the file manager's write lane (`InstanceFiles.putFrom`): a file there
keeps its mode, a new one is `0644`, and it is given to the owner of the directory it lands
in, as the Files tab does. A path a declared config file manages refuses
`files_managed_config`. The answer:

```
{"id":51,"volume":"data","path":"app.sqlite","bytes":40960,"status":"written"}
```

While a release serves the app, the file goes in through that release's container, as the
file API (`/api/v1/instances/{id}/files`) does. While NOTHING serves it (before the first
deploy, or after a failed one), Hohenheim starts a short-lived helper container on the
app's host for the one write: the pinned Alpine image, no network, binding ONLY the app's
declared volume directories at their container paths (the same set and directories the
first release mounts), owner-labelled as the app so the container hardening admits exactly
those binds, and removed when the write is done. No host path outside the app's volumes is
ever reachable, and nothing is written on the host directly. So the order "declare, put the
database and the key file, deploy" works with no host shell: `hoh volume declare 51 data
/data`, `hoh volume put 51 data ./app.sqlite app.sqlite`, then deploy.

The file API's other verbs (list, read, mkdir, rename, delete) still need a serving release
(`app_not_serving`); only the put starts a helper.

## Managed databases

An operator scripting a migration (a teardown, a move onto a shared engine) needs
these without driving the panel by hand. The verbs are the panel's own lanes
reached without a browser (`DatabaseApi`), and their doors are the panels':

- `GET /api/v1/databases` -- the `view` scope `DatabaseParts.manage()` renders.
  An admin key gets the operator columns (`engine_id`, `server`, `ephemeral`,
  `memory_limit_mb`, `cpu_limit`, `failure_reason`) beside the shared ones (`id`,
  `name`, `engine`, `db_name`, `placement`, `status`, `attached`); a delegated key
  gets the shared ones only. That is not tidiness: a shared engine's name is
  another tenant's neighbour list, which is why the /manage table drops it too.
  The stored CREDENTIALS have no representation on this surface at all -- the
  Credentials tab answers to its own capability.
- `POST /api/v1/databases/{id}/move-shared` -- **ADMIN-ONLY** (403 otherwise),
  because only the admin panel offers the row action; `DatabaseParts.manage()`
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
- `POST /api/v1/databases/{id}/delete` -- rides the `DatabaseParts` entry's own delete
  operation. Seeing a database (`view`) is not enough to destroy it: the resource
  demands `destroy` on the record and `DatabaseService.destroy` asks the same gate
  again, so a `view`-only key gets **403**. A database a live workload still holds
  is a **422** `delete_in_use` naming the workloads and the page each is detached
  on -- literally the reason the panel's dead Delete button renders, because the
  delete operation's `availability` (`DatabaseParts`) answers it before it acts. (The destroy refuses the
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

These exist for the same reason the database verbs do: without them the memory
picture lives only on the admin overview page, and an operator deciding where a
database could go reads `docker stats` -- which answers a DIFFERENT question. `docker
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
key is `unknown_field`.

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

## Certificates from a signing request

For a service that terminates TLS itself and must keep its key (a mail server pinning it in DANE TLSA records). The
caller sends a PKCS#10 signing request; Hohenheim orders from it, stores the chain and the request and NO private key,
renews from the SAME request (so the key never changes behind a pin), and never loads the certificate into its proxy
(it has no key to serve it with). The key bundle download is not offered for such a certificate and stays
interactive-only for every other.

Who may order a name is the certificate authority's ordinary answer: every name must be a domain of a live site the
key's owner manages (a mail host's own site declares its mail names as domains). A key that is no admin is refused
every name it does not answer for with ONE neutral answer, `hostname_unavailable`, whether the name is unknown,
another site's or opted out by another site's row; an admin key is told `hostname_not_served`,
`hostname_not_managed` or `excluded_hostnames`. The names are authorized before anything else is said about them.

The request must be signed by the key it carries (`signing_request_bad_signature`), carry exactly the requested names
as its common name and DNS alternative names (`signing_request_names_differ`), an RSA 2048/3072/4096 or ECDSA
P-256/P-384 key (`signing_request_weak_key`, `signing_request_unsupported_key`), a SHA-2 signature
(`signing_request_weak_signature`) and nothing Hohenheim would not honour: no subject attribute but the common name,
no extension but the alternative names (`signing_request_unsupported_content`). Each refusal is 422 on the `csr`
field.

The challenge is chosen for the caller: DNS-01 through the internal publisher when this controller runs DNS and may
write the zone of every name (a stream-only name needs no route), else HTTP-01, which the proxy answers for any
hostname that reaches its port 80; a wildcard needs DNS-01. The order is synchronous: **201** with the status when
issued, **202** with the status when the order failed (the row exists, `failure` says why, and the renewal sweep
retries it with the usual backoff). Rate limit: `hh_certificate_order` (20 per 10 minutes) on both writes,
`hh_paas_read` on the reads.

One certificate per name set: a key's owner holds at most one certificate from its own signing request for an exact
set of names (compared case-folded, in any order), the one the renewal sweep renews. Ordering it again with the SAME
key (a request over the same public key, whatever its bytes: a re-signed ECDSA request is the same order) is a
replay: **200** with that certificate's status as it stands, whatever it is, and nothing is ordered (a failed first
order is retried by the sweep, not by asking again; a new `name` is ignored). Ordering it with ANOTHER key is
**409** `certificate_names_held`, nothing ordered, whose `subject` names the certificate to rotate instead
(`{"status":409,"code":"certificate_names_held","message":"...","subject":{"kind":"hohenheim:certificate","id":"42",
"label":"mx.example.be"}}`). A rotation onto a name set another of the owner's certificates holds is refused the
same way, and the rotated certificate stays as it was. Another name set (a subset, a superset) is another
certificate. The names are still authorized first, so a replay is refused like an order once the owner no longer
answers for a name. Certificates whose key Hohenheim holds are no part of the rule.

```
{"id":42,"name":"mx.example.be","hostnames":["mx.example.be","mail.example.be"],"status":"active",
 "key_holder":"requester","auto_renew":true,"challenge":"http","issued_on":"2026-11-19T12:00:00Z",
 "expires_on":"2027-02-17T12:00:00Z","next_attempt_at":null,
 "public_key_sha256":"<hex SHA-256 of the leaf's SubjectPublicKeyInfo>","failure":null}
```

`public_key_sha256` is what a DANE `3 1 1` record publishes. `failure`, when set, is `{code, message, at}`. Rotation
(`POST .../signing-request`) re-orders onto the same certificate and touches it only when the new chain is issued,
so publish the new key's TLSA record first.


## Certificates for an address

`POST /api/v1/sites/{id}/domains/{domain}/certificate` is the address row's "Get a
certificate": the SAME operation (`hohenheim:request_domain_certificate`) through the
pipeline, with the dialog's input, so its decisions are the row action's. It is offered for
an exact, included address of a site this proxy terminates TLS for; it is ADMIN-ONLY (the
operation's authorizer: certificates are installation administration, so a key that is no
admin key is refused 403 even on an address its owner manages, and the same 403 for an address of that site that does
not exist, as the refusal is asked before the address is looked up); it is unavailable while
Let's Encrypt is switched off (422 `letsencrypt_disabled`); and who may obtain each name is
the certificate authority's answer inside the order. A site the key does not manage is the uniform 404; so, for an
admin key, is an address the site does not hold, and an address of a managed site
when the key's scopes leave out the address view (`hohenheim:site_domain#view`), because the
operation reads its address through that scope. The key is Hohenheim's and the proxy
serves the certificate; nothing about it is a requester-held order.

| Field | Type | Notes |
| --- | --- | --- |
| `domains` | string, repeatable | The names; absent = the address itself |
| `nice_name` | string | The certificate's name; absent = the first name |
| `letsencrypt_email` | string | The ACME account email; absent = the installation's (`invalid_email`) |
| `challenge_type` | enum | `http` (HTTP-01, the default) or `dns` (DNS-01; a wildcard needs it, `wildcard_requires_dns`) |
| `dns_publisher` | enum | For DNS-01: `manual` (the default: the order waits for TXT records published by hand, shown on the certificate's page in the panel), `internal` (a zone this controller is primary for: `zone_not_hosted`, `zone_not_primary`, `dns_server_disabled`) or `command` (`hook_not_configured`) |

The answer is the certificate projection above: **201** when issued, **202** when the
order is written and waiting (a manual DNS-01 order). Poll `GET /api/v1/certificates/{id}`
(`hoh certificate <id>`). A refused order is a 422 whose code is the dialog's: the input
refusals above, `invalid_hostnames`, `does_not_point_here` / `does_not_resolve` (an
HTTP-01 name that visibly points elsewhere), `proxy_unavailable`, the authority's
`hostname_not_served` / `hostname_not_managed` / `excluded_hostnames`, and the CA's own
failure in its sentence. Rate limits: `hh_certificate_order` on the route, beside the
operation's own `hh_le_request` budget (5 per hour per principal) the panel's orders spend.
## Environment variables are admin-only

Project membership plus instance-manage scope would NOT be enough here.
Membership is grant-derived and a capability scope token only NARROWS a key --
neither one is authority over a record. An environment value is not scoped to
the environment: `InstanceVariables.valuesFor` folds it in as the deploy
baseline of every instance grouped under it, and it OVERRIDES that instance's
own `environment_variables` entry. So a membership gate would let a project member
author what a workload runs with on instances it held no capability over, which is the
one thing this document's opening promise forbids.

It is gated on `HohenheimAccess.isAdmin` -- the very permission
(`hohenheim.admin.access`) that guards `HohenheimPanel`, where the only
environment-variable UI, `EnvironmentParts.variables()`, is registered. That is
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
hoh site domain certificate 11 21 challenge_type=http   # admin; the address row's "Get a certificate"
hoh certificates | hoh certificate 81  # status of what the key may read
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
hoh volume list 51
hoh volume declare 51 data /data quota_mb=512 [exclusive=true]   # admin, with the app's config capability
hoh volume update 51 data quota_mb=1024
hoh volume delete 51 data [--yes]     # deletes the data; asks for the volume name
hoh volume put 51 data ./app.sqlite app.sqlite   # admin; also before the first deploy
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
