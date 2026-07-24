# Site Type Architecture

## Problem

Hohenheim must support multiple site types (proxy, static, redirect, node process, compose, etc.), each with different configuration, proxy/handling behavior, and admin UI. Adding a new type must require implementing one class and registering it -- no changes to existing code.

## Design Principles

1. **Use the framework.** Zenit has `RegistryEnumField`, `SchemaField.schemaFrom()`, and `TypeDefinition`. Use them.
2. **Registry-driven.** Site types register themselves. The data model, admin UI, and proxy engine all read from the same registry.
3. **Each type owns its concerns.** A type declares its schema, its form component, and its request handler.
4. **Share what's universal.** Domain management, SSL, headers, enable/disable, audit logging are the same for every type. Only settings and request dispatch differ.
5. **Lifecycle-aware.** Handlers are long-lived, not per-request. They are created when a site is loaded, updated when config changes, and destroyed when a site is removed.

## Four Layers

### Layer 1: Data Model

Single `sites` table with polymorphic `settings` (JSONB):

```java
public static final RegistryEnumField SITE_TYPE = SCHEMA.addField(
    RegistryEnumField.builder("site_type")
        .registry(SiteTypeRegistry.REGISTRY)
        .build());

public static final SchemaField SETTINGS = SCHEMA.addField(
    SchemaField.builder("settings")
        .schemaFrom("site_type")
        .build());
```

Zenit validates and converts `settings` automatically based on `site_type`.

### Layer 2: Site Type Registry

```
SiteTypeRegistry.REGISTRY
  hohenheim:proxy       ProxySiteType
  hohenheim:static      StaticSiteType
  hohenheim:redirect    RedirectSiteType
```

Future types (node, compose) register themselves the same way but are designed separately due to their complexity.

**Common/server split:** The registry is defined in `src/common` and holds `SiteTypeInfo` instances (display name, schema, properties). On the server side, `SiteTypeRegistry` also maintains a parallel `Map<Identifier, SiteTypeHandler>` so the proxy engine can access handler factories. The common-side registry drives the admin UI (type selector dropdown, schema for form rendering). The server-side map drives the proxy engine (handler creation). Both are populated at startup from the same type implementations.

#### SiteTypeInfo (common, no server dependencies)

Declares display metadata and the settings schema. Lives in `src/common` so the client/admin UI can access type information without Undertow dependencies.

```java
public interface SiteTypeInfo extends TypeDefinition {
    // From TypeDefinition:
    //   String getDisplayName()
    //   Schema getSchema()
    //   Map<String, Object> getProperties()

    String getDescription();
    String getIcon();
}
```

#### SiteTypeHandler (server-only, has Undertow dependency)

Extends SiteTypeInfo with server-side handler creation and lifecycle management. Lives in `src/server`.

```java
public interface SiteTypeHandler extends SiteTypeInfo {

    SiteRequestHandler createHandler(Row site, Map<String, Object> settings);

    // Called when a site's config changes. Default: destroy + recreate.
    default SiteRequestHandler onSiteUpdated(SiteRequestHandler existing,
                                              Row site, Map<String, Object> newSettings) {
        existing.destroy();
        return createHandler(site, newSettings);
    }

    // Called when a site is disabled or deleted.
    default void onSiteRemoved(SiteRequestHandler handler) {
        handler.destroy();
    }
}
```

### Layer 3: Request Handling

A single unified interface. No split between "proxy upstream" and "handle directly" -- every type gets the same dispatch method.

```java
public interface SiteRequestHandler {

    // Handle an incoming request for this site.
    // For proxy types: call upstreamForwarder to forward to upstream.
    // For direct types: write the response to the exchange.
    // MUST be thread-safe -- one handler instance serves concurrent requests.
    void handleRequest(HttpServerExchange exchange, UpstreamForwarder forwarder);

    // Report health status for the admin UI.
    // Static/redirect types should return UP (always healthy if configured).
    // Proxy types should return based on upstream reachability.
    default SiteHealth getHealth() { return SiteHealth.UP; }

    // Clean up resources (kill processes, close connections, etc.).
    default void destroy() {}
}

public enum SiteHealth { UP, DOWN, DEGRADED, UNKNOWN }

// Passed to handleRequest. Proxy-type handlers call this to forward to an upstream.
// Named UpstreamForwarder (not ProxyCallback) to avoid collision with
// io.undertow.server.handlers.proxy.ProxyCallback.
public interface UpstreamForwarder {
    void forwardTo(URI upstream);
}
```

A proxy type does `forwarder.forwardTo(uri)`. A static type reads a file and writes to `exchange`. A redirect type sends a 302. The dispatcher doesn't care which.

**Integration with Undertow:** SiteDispatcher no longer implements Undertow's `ProxyClient` interface. Instead, it becomes a top-level `HttpHandler`. When a handler calls `forwarder.forwardTo(uri)`, the forwarder internally uses `UndertowClient` to establish the upstream connection and streams request/response with zero-copy IO (same as ProxyHandler does, but triggered from our dispatch flow). This preserves Undertow's non-blocking streaming and automatic WebSocket upgrade handling.

**Error handling:** The dispatcher wraps every `handleRequest()` call in try-catch. If a handler throws, the dispatcher sends a 502 Bad Gateway response to the client and logs the error with the site name/id.

#### SiteDispatcher Data Structures

Two maps work together:

- `Map<siteId, SiteRequestHandler>` -- handler lifecycle management (create/update/destroy)
- `RouteTable` (hostname+path -> siteId) -- request routing lookup

The RouteTable supports the full priority order:
1. Exact hostname + longest path prefix
2. Exact hostname + shorter/no path
3. Wildcard hostname + longest path prefix
4. Wildcard hostname + shorter/no path
5. Negative cache (5000 entries, 5min TTL)

Route changes are atomic: build a new RouteTable, swap with a single volatile reference. In-flight requests on the old table continue to completion.

#### SiteDispatcher Lifecycle

- **Route loading:** Builds new maps atomically (swap, not clear+rebuild). No 404 window.
- **Site creation:** Incremental -- adds routes for the new site without rebuilding everything.
- **Site update:** Calls `typeHandler.onSiteUpdated(existingHandler, site, newSettings)`.
- **Site deletion/disable:** Calls `typeHandler.onSiteRemoved(handler)`, removes routes.
- **Active connections preserved:** Route swaps don't kill in-flight requests or WebSocket connections.
- **Shutdown:** Handlers are destroyed before Undertow stops, allowing graceful cleanup.

Route loading uses a single JOIN query instead of N+1 (one query for all sites + domains).

### Layer 4: Admin UI

The site edit page has tabs: **General | Settings | Domains | Advanced**

**General tab** (same for all types): name, slug, type selector, enabled toggle, description.

**Settings tab** (type-specific): rendered based on selected type. Uses `{% if siteType{:} == "hohenheim:proxy" %}` conditional blocks with Plumage form components inside. When the type selector changes, the settings section reactively re-renders with the correct fields.

**Domains tab** (same for all types): hostname list management, match type, per-domain SSL, HSTS.

**Advanced tab** (same for all types): request/response header rules, timeout configuration, rate limiting. These are cross-cutting concerns that apply to every site type.

## Cross-Cutting Concerns (Common to All Types)

These live in the common site configuration, NOT in type-specific settings:

### Request/Response Headers
Every site supports configurable header rules:
- Add/remove/rewrite request headers (X-Forwarded-For, X-Real-IP, X-Forwarded-Proto, etc.)
- Add/remove response headers (CORS, security headers, CSP, X-Frame-Options)

Stored in the `site_domains` table's `custom_headers` JSONB field and/or a separate `site_headers` table.

### Path-Based Routing
The `site_domains` table already has `path` and `strip_path` fields. The SiteDispatcher must support path matching:
- Exact hostname match first, then longest-prefix path match
- `strip_path` removes the matched prefix before forwarding

### SSL/TLS Termination
TLS is a per-domain concern managed in the Domains tab:
- Per-domain certificate selection (Let's Encrypt, custom upload, none)
- SNI callback in the HTTPS listener selects the right certificate
- ACME HTTP-01 challenge handling intercepts `/.well-known/acme-challenge/` before normal routing
- This is complex enough to warrant its own design document

### Access Control (future)
Per-site basic auth, IP allowlists, and auth-request integration. Orthogonal to site type.

## Site Type Implementations (v1)

### ProxySiteType

**Settings schema:**
- `forward_scheme` (enum: http/https, default: http)
- `forward_host` (string, required)
- `forward_port` (integer, default: 80)
- `forward_socket_path` (string, alternative to host:port -- requires Java 16+ Unix domain socket support; deferred to v2 if Undertow/XNIO support is insufficient)
- `websocket_upgrade` (boolean, default: true)
- `upstream_protocol` (enum: http1/h2, default: http1) -- h2 dials prior-knowledge h2c on
  http upstreams and ALPN h2 on https upstreams; required for native gRPC backends.
  Response trailers (grpc-status) are captured and forwarded.
- `request_timeout` (integer seconds; absent = 30s, 0 = unlimited for streaming/gRPC/WebSocket sites)
- `ignore_certificates` (boolean, default: false) -- https upstreams are otherwise validated
  against the platform trust store INCLUDING hostname (JDK endpoint identification)

**Handler:** `forwarder.forwardTo(URI(scheme, host, port))`. Upstream connection streams request/response with zero-copy IO. WebSocket upgrades are handled transparently.

### StaticSiteType

**Settings schema:**
- `root_path` (string, required)
- `autoindex` (boolean, default: false)
- `fallback_file` (string, e.g., "index.html" for SPAs)

**Handler:** Serves files from `root_path` via Java NIO. 404 if not found, or fallback_file for SPAs.

### RedirectSiteType

**Settings schema:**
- `target_url` (string, required)
- `http_status` (enum: 301/302/307/308, default: 302)
- `preserve_path` (boolean, default: false)

**Handler:** Sends HTTP redirect. If `preserve_path`, appends the original request path to `target_url`.

### Complex Types (Future -- Separate Design Documents)

**NodeSiteType** and **ComposeSiteType** are NOT simple schema+handler pairs. They require:
- Background threads (process supervision, container monitoring)
- Resource management (port allocation, socket cleanup, container lifecycle)
- IPC protocols (ready signals, health probes)
- Their own admin UI panels (process list, container status, terminal access)

The architecture guarantees these CAN be built on the extension points (registry, handler lifecycle, custom settings templates) but their design is deferred to separate documents.

## Framework Improvements

### Plumage: Schema-driven form component (future)

A `<pl-schema-form>` that renders form fields from a Zenit Schema. Benefits all Zenit apps.

For v1, type-specific settings forms are hand-built with `{% if %}` blocks. This works and ships fast. The schema-driven form replaces the `{% if %}` blocks once proven.

Design considerations for the schema-form component:
- Field-level render overrides (custom widgets for specific fields)
- Conditional field visibility (field A only shown when field B has value X)
- Validation display (server-side errors mapped to specific fields)
- Field ordering and grouping (schema fields have no inherent order -- needs metadata)

### Hawkeye: Dynamic template rendering (future)

A `{% render templateId with variables %}` directive that renders a template by identifier. Would allow type-specific settings panels without `{% if %}` blocks. Significant framework change -- deferred.

### Zenit: JSON path query helper (future)

For operational queries like "find all sites forwarding to host X", the JSONB `settings` field needs database-specific JSON path queries. A portable Zenit API for JSON field queries would help. Deferred -- manual queries suffice for v1.

## File Structure

```
src/common/java/be/elevenways/hohenheim/
  sitetype/
    SiteTypeInfo.java              -- display metadata + schema (common)
    SiteTypeRegistry.java          -- Registry singleton

src/server/java/be/elevenways/hohenheim/server/
  sitetype/
    SiteTypeHandler.java           -- extends SiteTypeInfo with handler lifecycle
    SiteRequestHandler.java        -- unified request dispatch interface
    SiteHealth.java                -- health status enum
    types/
      ProxySiteType.java
      StaticSiteType.java
      RedirectSiteType.java
  proxy/
    SiteDispatcher.java            -- hostname+path matching, handler lifecycle
    ProxyServer.java               -- Undertow listeners (HTTP + HTTPS)

src/componentTemplates/resources/templates/
  hohenheim/sites/
    edit.hwk                       -- tabbed edit form
  components/
    domain-manager.hwk             -- domain list editor (reusable)
```

## Migration Path

1. Update `SiteModel` to use `RegistryEnumField` + `SchemaField.schemaFrom()`.
2. Implement ProxySiteType, StaticSiteType, RedirectSiteType with schemas and handlers.
3. Build the tabbed edit form with `{% if %}` blocks for type-specific settings.
4. Fix SiteDispatcher: atomic route swaps, handler lifecycle, JOIN queries.
5. Build domain management UI component.
6. Design TLS termination (separate document).
7. Design NodeSiteType (separate document).
