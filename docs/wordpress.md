# WordPress on Hohenheim

WordPress runs as an ordinary `docker_container` instance created from one of the seeded
**WordPress (PHP x.y)** templates: the official `wordpress:<tag>` Apache image with its
docroot on a managed volume, a managed MySQL database the template DECLARES and the
create step allocates, and the reverse-proxy HTTPS fix baked into `wp-config.php`. A
proxy site with an `instance` upstream fronts it. Nothing here is WordPress-specific
machinery; it is the instance-template tier plus one new declaration table, which any
database-backed template (Nextcloud, Gitea, ...) uses the same way.

## The template

Seeded by `WordPressTemplateSeeder` (ledgered `once`, like the game templates: operator
edits and deletions stick, and every template lands UNAPPROVED until an operator approves
it). One template per member of `WordPressPhp`, the one declaring home of the PHP
vocabulary; the seeder derives the templates from it and nothing else spells an image tag.

| Template | Image | Status |
| --- | --- | --- |
| WordPress (PHP 8.1) | `wordpress:php8.1-apache` | maintained upstream (WordPress 6.9 at seed time) |
| WordPress (PHP 7.4) | `wordpress:php7.4-apache` | FROZEN at WordPress 6.1.1 (2022-11-16); import-only |

What every template carries:

- `container_port` 80, loopback publication, 512 MB memory cap.
- Volume `html` at `/var/www/html`, the docroot. The image populates an EMPTY volume
  with its bundled WordPress at first start and never touches a populated one, which is
  what makes an imported docroot the site's own WordPress version regardless of the tag.
- Env `WORDPRESS_CONFIG_EXTRA` = the proxy fix: `$_SERVER['HTTPS'] = 'on'` when
  `X-Forwarded-Proto` is `https`. Hohenheim's proxy terminates TLS and regenerates that
  header (`ForwardingHeaders`); without the fix WordPress builds http URLs and loops on
  its own force-SSL redirect.
- Typed variable `WORDPRESS_TABLE_PREFIX` (default `wp_`, `[A-Za-z0-9_]+`, max 32),
  which the official image reads.
- Readiness `http` on `/`: a fresh docroot answers 302 to the installer, an imported one
  200; both are "below 500".
- A DECLARED database: engine `mysql`, env prefix `WORDPRESS_DB`.

## Declared databases (`instance_template_databases`)

A template may declare the managed databases an instance made from it needs
(`InstanceTemplateDatabaseModel`: engine, env prefix, optional engine-image override).
`InstanceTemplates.createFromTemplate` allocates one database per declaration through
`TenantDatabases.allocate`, THE database-allocation funnel (per-owner namespaced name,
generated credentials, placement, quota charge, creator grant), pinned to the instance's
own host because injection dials the engine over a link network that exists only on the
daemon both share, and attaches it as an `instance_databases` row under the declared
prefix. The injected family `WORDPRESS_DB_HOST/PORT/USER/PASSWORD/NAME/URL` is derived at
every deploy (`DatabaseEnvInjection`) and spells exactly what the official entrypoint
reads, so there is no mapping layer. The database is named
`<instance-slug>-<prefix-slug>` (`anymedia-wordpress-db`), inside the funnel's 32-character
label ceiling.

Since 2026-09-02 the allocated database lands on the host's SHARED MySQL engine by
default: one engine process per host holds every template-declared MySQL database as a
logical database of its own, with its own user and a grant on that database only. The
engine is created on demand by the same funnel, so nothing about the recipe changes. The
WordPress image reads exactly the same `WORDPRESS_DB_HOST/PORT/USER/PASSWORD/NAME/URL`
family; only the host it is pointed at differs (the engine's container handle instead of
the database's own). A record may still be created `dedicated` from the Placement select
on the Databases create form. See `docs/shared-database-engines.md`.

The cheap refusals (unknown engine, a kind without link networks, a database label
already taken) run BEFORE the instance row is written. A refusal after it (quota,
capacity) destroys the never-deployed instance and rethrows; inside the panel's mutation
transaction the rollback covers both. A tenant's database that the chooser lands on a
different host than the instance is refused by name
(`template_database_host_mismatch`), never attached.

Declarations travel in the template export/import document as `databases`
(`TemplatePortability`), die with their template (`InstanceCatalogGuards`), and are
authored on the template's Contents tab (`InstanceTemplateDatabaseResource`, which
needs both the instances and the databases role).

The database is allocated `provisioning` and provisions in the background (image pull,
engine readiness). Deploying the instance before it is `active` is REFUSED, on every
surface, with `database_not_ready` naming the database and its state (a `failed` database
names its failure reason instead): injection fail-softs, so a deploy that got through
would leave the `WORDPRESS_DB_*` family out of the environment and WordPress would answer
"Error establishing a database connection" while the instance looked healthy. The refusal
rides one resolver (`InstanceDatabaseLinks.notReadyReason`), so the Deploy row action
renders DEAD with the reason on screen and the POST refuses with the same sentence. Wait
for the database to turn active, then deploy.

The fail-soft in `DatabaseEnvInjection` stays for the cases the refusal deliberately does
not cover: a link whose database RECORD is gone, and an active database whose container is
not running (`hohenheim.db_injection.unresolved`, surfaced by the dashboard attention
panel).

## Fresh site

1. Approve the template (once).
2. Create an instance from it; pick the host; keep or change the table prefix.
3. Wait until the allocated database is `active` on the Databases list.
4. Deploy the instance; open its published port or front it with a proxy site
   (`instance` upstream) and finish the WordPress installer.

## Importing an existing site (the Phoenix WordPress sites)

Two moves, both through existing lanes; nothing WordPress-specific was added for them.

1. **Dump.** On the old host: `mysqldump --single-transaction --default-character-set=utf8mb4
   <db> > site.sql`. Create the instance from the matching PHP template and wait for its
   database to be active. Restore the dump through the database's **Restore** action
   (`/databases/<name>/restore`, multipart upload; `DatabaseRestoreUploadTest` is the
   contract). The dump lands in the database the template allocated, whatever the old
   database was called; the WordPress table prefix inside the dump is whatever the old
   site used, so set the instance's `WORDPRESS_TABLE_PREFIX` variable to match BEFORE
   the first deploy (the image writes it into `wp-config.php` once). A shared database
   is dumped and restored exactly the same way from the panel: the client runs inside the
   shared engine's container with the engine's root credentials, scoped to that one
   logical database. The two WordPress records staged on robbedoes
   (`anymedia-wordpress-db`, `diax-wordpress-db`) stay DEDICATED until an operator moves
   them with the "Move to shared engine" row action.
2. **Docroot.** Deploy the instance once so the volume exists, stop it, then copy the old
   docroot INTO the volume with the archive lane the instance tier already uses
   (`docker cp old-docroot/. <handle>:/var/www/html/` against a stopped container works
   because the volume is mounted; the handle is `hohenheim-<controller>-instance-<id>`,
   shown on the instance overview). Remove the OLD `wp-config.php` from the copy first:
   the official image generates a fresh one from the injected variables ONLY when the
   file is absent, and an old file would point at the old host's database. Anything the
   old file carried beyond the database block (salts, `WP_MEMORY_LIMIT`, custom
   constants) goes into the instance's `WORDPRESS_CONFIG_EXTRA` variable, appended to the
   seeded HTTPS fix. Then `chown -R www-data:www-data` inside the container
   (`docker exec <handle> chown -R www-data:www-data /var/www/html`) and start it.

Hostname change: WordPress stores its URLs in `wp_options` (`siteurl`, `home`) and in
serialized content. Same hostname (the normal cutover) needs nothing. A renamed site
runs `wp search-replace old.example new.example --all-tables` (wp-cli inside the
container, or the equivalent SQL on the two options) before it goes live.

PHP version rule: an import lands on the template whose PHP the site's plugins and theme
were running (`7.4` for the di-ax sites, `8.1` for Anymedia/ConnectedPrint). The 7.4
image's bundled WordPress is irrelevant to an import (the docroot brings its own), so the
frozen tag is only a PHP runtime; move the site to 8.1 by re-creating from that template
with the same dump and docroot once its plugins allow it. Never start a NEW site on 7.4.

## Not built, on purpose

- No site-URL knob: WordPress owns its URLs in the database and the import section covers
  the rename; a second writer would fight it.
- No health check beyond the tier's readiness probe: `docker_container` has no
  healthcheck setting today, and adding one for WordPress alone would be a private
  mechanism.
- No wp-cli lane and no upload surface for a docroot: the archive API the instance tier
  already exposes (`docker cp`) is the honest minimum until a second consumer needs more.
- The proxy site fronting the instance is created by the operator (or the sites API), as
  for every other instance; a template does not own a hostname.
