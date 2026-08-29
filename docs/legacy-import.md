# Importing an old Hohenheim installation

The servers still running the old Node Hohenheim (phoenix, merlina, blackblock,
...) keep their configuration in a Mongo `sites` collection. `tools/hoh-import-legacy`
converts that collection into calls of the documented write lane
(`docs/paas-api.md`, "Sites and domains") and drives them through `tools/hoh`.

It is a TRANSLATOR and a REPORT, never a guesser. Everything it cannot express
in the new system is FAIL CLOSED: the site is not converted, and the manifest
names the site, the field and the reason. That includes an unknown `site_type`
and an unknown settings key, so a legacy shape nobody anticipated is loud rather
than silently dropped.

## 1. Export the old collection

On the old box, in the Hohenheim checkout:

```
grep -A10 mongo app/config/live/database.js     # host, database, user, password
mongoexport -d hohenheim -c sites --jsonArray -o sites.json \
    [-u <user> -p <password> --authenticationDatabase <db>]
```

The other collections (`acl_groups`, `acl_rules`, `users`) are not read by the
converter; export them only if you want to rebuild the access lists by hand.

**The export contains basic-auth passwords in plaintext.** Keep it off the
repository and out of any shared directory, and delete it when the import is
done. The converter never writes a password to its manifest, with or without
`--include-secrets`.

## 2. Write the mapping file

Three things the converter cannot create for itself:

```json
{
    "instances":   { "Microcopy": 12, "Udesign Live": 13 },
    "accessLists": { "Udesign Preview": 3 },
    "listenOn":    "203.0.113.20"
}
```

- `instances` -- the new instance id for an `alchemy`/`node` site. Create the
  instance first, from the blueprint the manifest prints for it.
- `accessLists` -- the new access-list id for a site that had `basic_auth`. The
  passwords do not carry over: set them on the list.
- `listenOn` -- one address of the NEW host, or `""` (every interface). The old
  server's `listen_on` addresses are its own and are always dropped.

An unknown key in the map file is refused.

## 3. Dry run, read the manifest, then apply

```
node tools/hoh-import-legacy sites.json --map map.json \
    --skip @skip.txt --manifest phoenix-manifest          # --dry-run is the default
node tools/hoh-import-legacy sites.json --map map.json \
    --skip @skip.txt --manifest phoenix-manifest --apply
```

| Option | Meaning |
| --- | --- |
| `--map <file>` | `instances` / `accessLists` / `listenOn` |
| `--only <a,b>` / `--skip <a,b>` | legacy site NAMES, case-insensitive; `@file` reads one name per line (`#` comments) |
| `--dry-run` | print the `hoh` invocations, execute nothing (DEFAULT) |
| `--apply` | execute them through `tools/hoh`, stopping at the first refusal |
| `--manifest <base>` | manifest base path (`<base>.json` and `<base>.txt`) |
| `--include-secrets` | keep environment-variable and api-key VALUES in the manifest |
| `--hoh <path>` | the `hoh` executable to drive (default: beside the script) |

A selector matching no site is REPORTED, never silently ignored -- the old names
are prose ("Skerit blog", "11 Ways Staging"), so a skip list built from an audit
usually needs a pass to fix up.

`--apply` reads `hoh sites --json` first and skips any site whose legacy name a
site already carries, so a run interrupted halfway is resumed by running it
again. It stops at the first refusal and prints the refusal code; the manifest
records what was created before it stopped.

Authentication, host and context are `hoh`'s own (`~/.config/hoh/config.json`,
or `HOH_HOST`/`HOH_TOKEN`). Creating a site is admin-only.

## The mapping table

| Legacy | New | Notes |
| --- | --- | --- |
| `proxy`, `proxy_site` + `settings.url` | `hohenheim:address` with `forward_scheme`/`forward_host`/`forward_port` | a unix socket path becomes `settings.socket`; a url with a PATH is unmappable (the new upstream is host+port), a missing port takes the scheme default |
| `redirect` + `target_url` | `hohenheim:redirect`, `http_status` 301 if `is_permanent` else 302 | `preserve_path=false` is ASSUMED: the old redirect sent every request to the target as it stood |
| `static` + `path`/`fallback_file` | `hohenheim:static` with `root_path`/`fallback_file` | the new fallback is RELATIVE to the root, so an absolute one is rewritten; a fallback outside the root is unmappable |
| `alchemy`, `node` | `hohenheim:instance` with `instance_id` | requires `instances[<name>]`; without it the site is listed with its blueprint and skipped |
| `settings.delay` | `settings.delay` | the instance kind declares no delay, so it is dropped there |
| `domain[].hostname[]` | one `hoh site domain add` per hostname | `match_type` by SHAPE: `*` -> wildcard, regex metacharacters -> regex (verify those by hand) |
| `domain[].headers[]` | `custom_headers.N.key` / `.value` | a repeated header name becomes ONE row |
| a `Host` header | `settings.rewrite_location=false` on the site | TRAP: the default TRUE rewrites the upstream's `Location` headers with the rewritten internal Host and leaks it to the browser |
| `domain[].listen_on[]` | the map's `listenOn` | the old server's addresses never carry over |
| `settings.basic_auth` | `access_list_id` | requires `accessLists[<name>]`; the manifest lists the USER NAMES, never the passwords |
| `settings.letsencrypt_force` | nothing | certificates are explicit records now. `false` means the site wanted NO certificate: do not issue one |
| `settings.letsencrypt_email` | nothing | the contact is account-level now |
| `node`, `script`, `user`, `environment_variables`, `api_keys`, `minimum_processes`, `maximum_processes`, `wait_for_ready` | the instance blueprint | manifest data for the instance the operator creates by hand |
| an unknown `site_type` or settings key | nothing | the site is UNMAPPABLE and named |

A known settings key that the site's own type never consumed (the old panel kept
a site's earlier settings when its type was switched: a `redirect` carrying a
`script`, a `proxy` carrying `node`) is reported as dropped with that reason.

## The manifest

Always written, in both modes, as `<base>.json` and `<base>.txt`. Per site:

- the verdict: `converted`, `needs-instance`, `needs-access-list`, `unmappable`;
- `reasons` -- why it is not converted, one line each;
- `notes` -- every assumption taken (the `preserve_path` one, a rewritten
  fallback path, a defaulted port, the `rewrite_location` trap);
- `dropped` -- every legacy field that did not travel, with why;
- `instance_blueprint` -- for an `alchemy`/`node` site: node version, script,
  uid, process bounds, `wait_for_ready`, environment-variable NAMES and the api
  key count (values redacted unless `--include-secrets`);
- `commands` -- the exact `hoh` argv, and `applied` after an `--apply` run.

## Per server

1. Audit the old sites first and decide, per site: moved elsewhere, gone, third
   party, broken, or migrating. Only the last group is imported; write the rest
   into a `skip.txt`.
2. Export the collection (step 1) onto the machine running `hoh`.
3. Dry-run with no map. Read the manifest: it tells you exactly which instances
   and access lists to create.
4. Create those instances (from the blueprints) and access lists, write the map
   file, dry-run again until nothing you care about is left unconverted.
5. `--apply`. Fix each refusal it stops on and run again -- created sites are
   skipped by name.
6. Certificates and DNS are separate and deliberate: nothing in the export maps
   to them.

## Tests

`node --test "tools/*.test.js"` (or `node --test tools/hoh-import-legacy.test.js`).
`tools/legacy-sites.fixture.json` is an ANONYMIZED export covering every legacy
site type plus one carrying an unknown key; the real exports are never committed.
