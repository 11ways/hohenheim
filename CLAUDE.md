# Hohenheim

Reverse proxy / app manager. Starts and supervises backend processes (node, static sites, proxied upstreams), assigns ports/sockets, handles Let's Encrypt TLS, shared caching, auth, and a web admin UI. This is the Zenit-based rewrite of the original Node.js/AlchemyMVC Hohenheim.

## Stack

- Java 25, Gradle (source sets: `common`, `client`, `server`, `browserTest`)
- Zenit (web framework), zenit-cms (admin panel), zenit-forms, zenit-auth,
  Hawkeye (templates), Protoblast (utilities), Plumage (UI components)
- TeaVM compiles `client` + `common` to `public/cms.js` (the zenit-cms shell
  hardcodes `<script src="/cms.js">`)
- SQLite via `sqlite-jdbc` (file: `hohenheim.db`)
- acme4j for Let's Encrypt, BouncyCastle for PEM
- Playwright for browser tests

## Layout

- `src/common/java` shared between server and client (TeaVM)
- `src/common/templates` Hawkeye `.hwk` files, namespace `hohenheim`
- `src/common/scss` styles
- `src/client/java` browser code (TeaVM-compiled)
- `src/server/java` Undertow-backed server, entry point `be.elevenways.hohenheim.server.ServerMain`
- `src/browserTest/java` Playwright end-to-end tests
- `docs/` design notes (see `architecture-site-types.md` for the site-type registry)

## Build and run

Use `zenit-dev` for all build/test/run cycles. Do not invoke `./gradlew` directly.

## Architecture notes

- The admin UI is a zenit-cms panel served at `/admin` (`server/cms/HohenheimPanel`):
  typed `RowResource` peers for sites/domains/certificates/access lists/auth
  providers/databases/servers/notification channels, zenit-cms's readonly
  `ActivityResource` over the framework activity log, the framework
  `SettingsPage` at `/admin/settings` (two mounts: the hohenheim context
  editing `settings/hohenheim.dry`, plus zenit's `ServerSettings` editing
  `settings/local.dry`; DIFF-based save, secrets masked, restartRequired
  metadata drives the restart toast), and `RecordScopedPage` tabs on sites
  (Domains, Processes incl. terminal + proclogs, Deployments on git sites)
  and databases (Restore). `HohenheimSettings` roots at its OWN `hohenheim`
  group (the standard consumer shape); its file keys stay flat
  (`proxy.http_port`) because the context root maps the file root. Tests
  redirect the editable file via `-Dhohenheim.settings`. Mutations are
  recorded by zenit's `ActivityLog` (enabled in `settings/default.dry`;
  behaviour verbs via `ActivityLog.withAction`) and routing-relevant writes
  rebuild the proxy via `ProxyReloadHooks` on the global model-hook tier --
  there is no per-resource audit/reload plumbing. Host-declared endpoints
  beside the panel (downloads, uploads, process control, terminal WS) live in
  `HohenheimEndpoints` + `HohenheimHandlers`.
- Site types are registered via `SiteTypeRegistry`. Each type declares its schema (which drives the type-discriminated settings sub-form in the CMS) and its request handler. Adding a type means implementing one class and registering it — no edits to existing dispatch code. See `docs/architecture-site-types.md`.
- Current types in `src/server/java/be/elevenways/hohenheim/server/sitetype/types/`: `AlchemySiteType`, `CommandSiteType`, `DeadSiteType`, `DockerSiteType`, `NodeSiteType`, `ProxySiteType`, `RedirectSiteType`, `StaticSiteType`.
- Site-type settings store env vars/headers/credentials as `StringMapField`
  maps and reference discovered users/node versions via `RegistryEnumField`
  keys (`hohenheim:<username>` / `hohenheim:<version>`, registries refreshed
  by the discovery tasks). M025 migrated the legacy list/id shapes; readers
  stay tolerant of both.
- Handlers are long-lived: created when a site loads, updated on config change, destroyed on removal. Not per-request.
- ClientMain MUST call `HohenheimModels.registerAll()` + `HohenheimSources.register()` before `ClientZenitRuntime.main` (the browser has no MODELS/MODULES boot stage).

## Conventions

- Follow the Hawkeye skill for `.hwk` templates; follow the Zenit skill for endpoints, models, migrations, settings.
- Never duplicate document-level boilerplate across templates — use `extend`.
- Before using a framework feature, find an existing working example in this repo or in the Zenit/Hawkeye/Plumage sources.
- Fix the framework rather than working around it in app code.
