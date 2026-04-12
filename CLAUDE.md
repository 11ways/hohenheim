# Hohenheim

Reverse proxy / app manager. Starts and supervises backend processes (node, static sites, proxied upstreams), assigns ports/sockets, handles Let's Encrypt TLS, shared caching, auth, and a web admin UI. This is the Zenit-based rewrite of the original Node.js/AlchemyMVC Hohenheim.

## Stack

- Java 22, Gradle (source sets: `common`, `client`, `server`, `browserTest`)
- Zenit (web framework), Hawkeye (templates), Protoblast (utilities), Plumage (UI components)
- TeaVM compiles `client` + `common` to JS for the browser
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

- Site types are registered via `SiteTypeRegistry`. Each type declares its schema, admin form, and request handler. Adding a type means implementing one class and registering it — no edits to existing dispatch code. See `docs/architecture-site-types.md`.
- Current types in `src/server/java/be/elevenways/hohenheim/server/sitetype/types/`: `AlchemySiteType`, `CommandSiteType`, `DeadSiteType`, `NodeSiteType`, `ProxySiteType`, `RedirectSiteType`, `StaticSiteType`.
- Common vs server split: `SiteTypeInfo` lives in `common` (drives admin UI and schema), `SiteTypeHandler` factories live on the server (drive the proxy engine).
- Handlers are long-lived: created when a site loads, updated on config change, destroyed on removal. Not per-request.

## Conventions

- Follow the Hawkeye skill for `.hwk` templates; follow the Zenit skill for endpoints, models, migrations, settings.
- Never duplicate document-level boilerplate across templates — use `extend`.
- Before using a framework feature, find an existing working example in this repo or in the Zenit/Hawkeye/Plumage sources.
- Fix the framework rather than working around it in app code.
