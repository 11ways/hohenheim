# Upstream Kind Architecture

## STATUS 2026-08-22 -- renamed and narrowed

This document was `architecture-site-types.md` until the phase-0 upstream rename
(`docs/phase0-design.md` section 3). The old `site_type` vocabulary answered TWO questions
at once -- "where do requests go" and "what workload runs" -- and the second one moved to
the INSTANCE kind. What survives here describes the first question only: a site has exactly
one typed UPSTREAM and no opinion about how the thing upstream is run.

Deleted with the rename, and no longer described below: the `docker`, `node`, `java`,
`command`, `alchemy` and `dead` site types, `sites.source` / `sites.source_settings` (a git
source is a property of the application or workspace instance a site exposes), and the
site-databases and site-processes record subpages. Git history holds the old text
(`git log --follow -- docs/architecture-upstream-kinds.md`).

## What an upstream kind is

An upstream kind is one class implementing `UpstreamKindHandler`
(`src/server/java/be/elevenways/hohenheim/server/upstream/UpstreamKindHandler.java`), which
extends the common-side `UpstreamKindInfo`
(`src/common/java/be/elevenways/hohenheim/upstream/UpstreamKindInfo.java`). It declares:

- its `Identifier typeId()` -- the string form is the stored `sites.upstream_kind` value;
- display facets from `TypeDefinition`: `getDisplayName()`, `getLabel()` (microcopy),
  `getDescription()` (microcopy, drawn by card presentations), `getIcon()`, `getColor()`;
- `Schema getSchema()` -- the settings schema stored polymorphically in `sites.settings`;
- `createHandler(Row site, Map<String, Object> settings)` -- the long-lived request handler;
- `requiresInstance()` -- whether this kind resolves to an instance record, which is what
  makes `sites.instance_id` required (and, for every other kind, forbidden). The invariant
  is enforced once, in `SiteModel`'s before-validate hook.

`managedProcessEnvironment()` still exists on `UpstreamKindHandler` and no shipped kind
answers true: it survives only until the deletion wave removes its last two readers.

## Discovery: nothing is registered by hand

`UpstreamKindHandler` carries
`@BlastDiscoverable(registrar = "be.elevenways.hohenheim.server.upstream.UpstreamKindHandlers#register")`.
The compile-time-generated `BlastAutoLoadInit` calls `UpstreamKindHandlers.register(handler)`
for every implementation, which puts the instance in BOTH the common registry
`UpstreamKinds.REGISTRY` and the server-side `Map<Identifier, UpstreamKindHandler> HANDLERS`.
Adding an upstream kind is one class and no edit anywhere else.

Both classes force `BlastAutoLoadInit.loaded` from a static field that MUST stay LAST in the
class, because the loader re-enters `register()` while the class is still initialising.
Moving that field up silently empties the registry.

`UpstreamKindHandlers.boot()` only initialises the shared process infrastructure; the model
write hooks it used to install live in the discovered `HohenheimWriteHooks` ZenitModule.

## Data model

`SiteModel` stores the kind in a `RegistryEnumField` fed by the registry and the settings in
a `SchemaField` resolved from it:

```java
public static final EnumField UPSTREAM_KIND = SCHEMA.addField(
    RegistryEnumField.builder("upstream_kind").registry(UpstreamKinds.REGISTRY) ... );

public static final SchemaField SETTINGS = SCHEMA.addField(
    SchemaField.builder("settings").schemaFrom("upstream_kind") ... );

public static final IntegerField INSTANCE_ID = SCHEMA.addField(
    IntegerField.builder().name("instance_id") ... );
```

`INSTANCE_ID` is a REAL column and not a settings key: the instance detail page needs the
reverse "exposed by" lookup, delete cascades need it, and the tenant scope joins on it.

`SECURITY_REPORT_TOKEN` is deliberately a dedicated column and NOT a key inside the
polymorphic settings map, because the dynamic form entry rewrites that map on every admin
save and would drop it (AIDEV-NOTE in `SiteModel`).

## The six implemented kinds

All in `src/server/java/be/elevenways/hohenheim/server/upstream/kinds/`. Identifiers are
`hohenheim:<name>`. `StaticFileHandler` lives in `server/sitetype/` and is NOT a kind -- it
is the `SiteRequestHandler` that `StaticUpstreamKind` returns.

| Kind | Id | Class | Resolves to |
| --- | --- | --- | --- |
| Address | `address` | `AddressUpstreamKind` | a host/port or unix socket you name |
| TLS passthrough | `tls_passthrough` | `TlsPassthroughUpstreamKind` | raw TLS relay, no HTTP |
| Static | `static` | `StaticUpstreamKind` | files on disk |
| Redirect | `redirect` | `RedirectUpstreamKind` | a 3xx response |
| Instance | `instance` | `InstanceUpstreamKind` | an instance this deployment manages |
| Dev namespace | `dev_namespace` | `DevNamespaceUpstreamKind` | a live dev-tunnel lease |

A site with no upstream at all is simply a site whose `instance_id` is null on a kind that
wants one -- there is no `dead` kind any more.

### Settings schemas

Field names are the stored setting keys. Defaults are those declared on the field builder.

**Address** (was Proxy): `forward_scheme` (http/https), `forward_host`, `forward_port`,
`upstream_protocol` (http1 / h2-for-gRPC), `request_timeout` (s), `websocket_upgrade`
(default true), `ignore_certificates` (default false), `rewrite_location` (default true),
`socket` (PathField), `delay` (ms).
Socket mode WINS over host/port when set and is reached through a loopback TCP-to-AF_UNIX
bridge because Undertow/xnio has no AF_UNIX client; `{name}`/`{0}` placeholders are
substituted per request from regex-host capture groups.
INPUT-TRUST OBLIGATION: those captures come from the untrusted Host header and are
validated before becoming part of a filesystem path.
Missing upstream and unparseable URIs both degrade to a 502 handler rather than throwing.

**TLS passthrough**: `forward_host` (required), `forward_port` (default 443, range
1-65535), `proxy_protocol_v2` (default false), `connect_timeout` (default 10s, range
1-300). It implements `TlsPassthroughProvider`, whose `createHandler` throws
`UnsupportedOperationException` by design -- these sites never see an HTTP exchange. Model
validation refuses HTTP-only domain and site options for this kind.

**Static**: `root_path` (PathField, directory browser), `autoindex` (default **true** --
matching the Node original's ecstatic behaviour), `indexes` (default true),
`show_hidden_files` (default false), `delay` (ms), `fallback_file`. An empty `root_path`
yields an empty 200, not a 500.

**Redirect**: `target_url`, `http_status` (301/302/307/308, falls back to 302 when unset or
unparseable), `preserve_path` (default false), `delay` (ms). A target that is not
`http://`, `https://` or `/` is refused with a 502 rather than emitting an open redirect.

**Instance**: `port` (which declared port of the instance to serve; blank = its single
publication), `scheme` (http/https), `websocket_upgrade` (default true), `request_timeout`
(s). It absorbs the serving half of the deleted `docker` site type. `InstanceUpstreamHandler`
RESOLVES the serving release's published loopback port off the port ledger and forwards
there; it converges nothing, so a routing reload can no longer block on a build. The
resolution is generation-keyed (`ApplicationUpstreams`): a release flip bumps the
application's generation and the handler re-resolves on its next request, which is what
makes a swap visible without rebuilding the route table. A site whose `instance_id` is null
is a `FaultedSiteHandler`; an application with nothing serving answers 503 and reports DOWN.
`request_timeout` rides the generic `RouteEntry` reading of the site settings.

**Dev namespace**: `registration_token` (secret). The matched wildcard's first label picks a
live `DevLease`; a name with no lease renders the dev-offline page. `SiteResource` mints a
token automatically when the field is left blank.

## Request handling

`SiteRequestHandler`
(`src/server/java/be/elevenways/hohenheim/server/sitetype/SiteRequestHandler.java`) is one
interface for every type: proxy types call `forwarder.forwardTo(...)`, direct types write
the exchange. Beyond `handleRequest`, it carries `getSiteId()` (default -1),
`getHealth()` (default UP), `mutateResponse(exchange)` (default null -- an optional
`ResponseMutator` applied just before response headers commit) and `destroy()`.

`SiteHealth` is `UP, DOWN, DEGRADED, DEPLOYING, UNKNOWN` (`SiteHealth.java:3-9`) -- the
`DEPLOYING` state is what a release-gated instance upstream reports mid-swap.

`UpstreamForwarder` (`UpstreamForwarder.java:10-16`) now takes an `UpstreamTarget`; the
`forwardTo(URI)` overload is a default that wraps the URI. `UpstreamTarget` carries the
protocol choice and the ignore-certificates flag alongside the URI, which is how h2/gRPC
upstreams and per-site TLS trust reach the connector.

Handler lifecycle stays as designed: `onSiteUpdated` defaults to destroy-and-recreate and
`onSiteRemoved` to destroy (`UpstreamKindHandler`).

### Dispatch

`server/proxy/SiteDispatcher.java` owns hostname+path resolution over an immutable
`RouteTable` (`SiteDispatcher.java:195-222`) swapped through one volatile reference under
`generationLock` (`SiteDispatcher.java:647-659`). In-flight requests lease their
generation to completion (`SiteDispatcher.java:734-749`). The negative cache is capped at
5000 entries with a 5 minute TTL (`SiteDispatcher.java:226-227`) and only a fully unknown
hostname is cached negatively (`SiteDispatcher.java:1082-1085`). A positive regex-match
cache sits beside it with the same capping stance (AIDEV-NOTE at `SiteDispatcher.java:230-234`).

`REWRITE_LOCATION` is an exchange attachment (`SiteDispatcher.java:980`) that proxy and
dev-namespace handlers set to opt into upstream `Location` rewriting.

## Admin UI -- the real story

There is no hand-built site edit template. No `.hwk` file in the repo branches on the upstream kind; the
`{% if siteType{:} == ... %}` design was never how this shipped. The site editor is a generated zenit-cms `RowResource`
(`src/server/java/be/elevenways/hohenheim/server/cms/SiteResource.java:66-79`):

```java
FormSpec.builder()
    .add(SiteModel.NAME)
    .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(SiteModel.UPSTREAM_KIND))
    .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(SiteModel.SETTINGS))
    .add(SiteModel.ENABLED)
    .add(SiteModel.DESCRIPTION)
    .add(RelationPick.of(SiteModel.AUTH_PROVIDER_ID, ...))
    .add(RelationPick.of(SiteModel.ACCESS_LIST_ID, ...))
    .add(RelationPick.of(SiteModel.INSTANCE_ID, InstanceModel.MODEL_ID).creatable(false).build())
    .build();
```

The upstream selector is the derived `RegistryEnumField` entry; the settings block is the
derived dynamic `SchemaField` entry, which re-renders from the selected type's schema.
That is why a new kind needs no UI work at all: declare labels/help via
`HohenheimFormCopy.label(...)`/`.help(...)` on the schema fields and the form is done.
The "future `<pl-schema-form>`" and "future `{% render templateId %}`" items from the old
text are moot -- the framework's schema-driven form entry is what ships.

**AMENDED 2026-08-12: the old text deferred THREE framework items, not two, and
the third was dropped without a verdict.** The missing one is **"Zenit: JSON
path query helper"** -- a portable API for querying inside a JSON `settings`
column, wanted for operational questions like "find every site forwarding to
host X", deferred in the original because manual per-dialect queries sufficed
for v1. It is neither moot nor built: zenit's ORM exposes no JSON-path query
helper today, so such a question still needs a hand-written per-backend
predicate, which the no-raw-SQL rule makes awkward. Recording it OPEN rather
than silently dropping it: it is a framework capability, so its home is zenit
core (`common/orm/query`), not this app.

The list view filters and sorts on upstream kind, enabled and created-at. Everything that is not per-type lives on record subpages:
domains, deployments, dev sessions, plus the framework-contributed subpages including the
generic record-access tab. The databases and processes subpages were deleted with the
rename.

## Cross-cutting concerns (identical for every type)

### Request/response headers

Custom header rules are NOT the mechanism for the forwarding trust boundary. In
`SiteDispatcher.continueAfterAuth` the custom rules run FIRST and are then deliberately
overridden for the forwarding family: `X-Forwarded-Proto`, `X-Forwarded-Host`,
`X-Real-IP` and `X-Forwarded-For` are regenerated from hohenheim's own
`X-Hohenheim-Key`-authenticated decision (see the comment at `SiteDispatcher.java:119`),
and the wider client-asserted family (RFC 7239 `Forwarded`, the `X-Forwarded-*` aliases,
CDN client-IP headers, the IIS URL-rewrite pair) is stripped unconditionally. A header
rule targeting any of those names is silently discarded on the forward path -- author
rules for application headers only.

### Path-based routing

`site_domains` carries `path` and `strip_path`; resolution is exact hostname first, then
longest path prefix, then wildcard hostname, then the negative cache.

### TLS termination and passthrough

`PublicTcpListener` is the shared public-port front: it accepts an optional PROXY v2
header from a configured peer, hands the stream to a `ConnectionRouter`, and relays to the
chosen backend. Two routers exist. `TlsSniRouter` parses only the bounded ClientHello
needed for SNI and then either replays the exact bytes to a `hohenheim:tls_passthrough`
backend or connects to an internal loopback Undertow TLS listener for HTTP/1.1, HTTP/2,
gRPC and WebSockets. `InternalListenerRouter` consumes nothing and is what puts the plain
HTTP port behind the same ingress when trusted peers are configured. All of these live in
`server/proxy/`.

Identity survives the loopback hop through `ConnectionIdentities`, keyed by the internal
socket's ephemeral source address, so Undertow exchanges see the public source and
destination. HTTP and pre-TLS routes share one immutable generation. QUIC/HTTP3 will
require a parallel datagram transport; it must reuse route policy and this identity seam
rather than being folded into the TCP listener.

## File map

```
src/common/java/be/elevenways/hohenheim/
  upstream/UpstreamKindInfo.java      -- common metadata contract (extends TypeDefinition)
  upstream/UpstreamKinds.java         -- Registry<UpstreamKindInfo>, autoload-forced
  model/SiteModel.java                -- UPSTREAM_KIND + polymorphic SETTINGS + INSTANCE_ID

src/server/java/be/elevenways/hohenheim/server/
  upstream/UpstreamKindHandler.java   -- @BlastDiscoverable server contract
  upstream/UpstreamKindHandlers.java  -- registrar + handler map + boot()
  upstream/kinds/                     -- the six kinds
  sitetype/SiteRequestHandler.java    -- unified request dispatch
  sitetype/SiteHealth.java            -- UP/DOWN/DEGRADED/DEPLOYING/UNKNOWN
  sitetype/StaticFileHandler.java     -- the handler StaticUpstreamKind returns
  sitetype/FaultedSiteHandler.java    -- explicit 503 for misconfigured sites
  sitetype/TlsPassthroughProvider.java, TlsPassthroughTarget.java
  sitetype/UpstreamForwarder.java, UpstreamTarget.java, UpstreamProtocol.java
  sitetype/UnixSocketBridgeConnection.java, TcpUpstreamConnection.java
  proxy/SiteDispatcher.java           -- route table, generations, header policy
  proxy/PublicTcpListener.java, TlsSniRouter.java, InternalListenerRouter.java
  cms/SiteResource.java               -- the generated admin form + subpages
```

AIDEV-NOTE: the handler PLUMBING still lives in `server/sitetype/` while the kind
DECLARATIONS moved to `server/upstream/`. That split is deliberate for now -- the plumbing
(forwarder, targets, health, bridges) is shared by tiers that are not upstream kinds at all
-- but it is the one place where the old name survives, so read `sitetype` there as
"request handling", not as "site type".

## Adding an upstream kind

1. Write one class in `server/upstream/kinds/` implementing `UpstreamKindHandler` (or
   `TlsPassthroughProvider` for a pre-HTTP kind).
2. Declare `ID`, a `SETTINGS_SCHEMA`, the facets, and `createHandler`.
3. Give each schema field a `HohenheimFormCopy.label`/`help` and add the microcopy keys,
   plus the `upstream_kind` and `upstream_kind_description` entries in en and nl --
   `UpstreamKindVocabularyTest` fails until both languages carry them.
4. Declare `requiresInstance()` if the kind resolves to an instance record.
5. Nothing else. Discovery registers it, the model enum picks it up, and the admin form
   renders the schema.

## Related documents

Multi-container deployments are the STACK tier, deliberately NOT an upstream kind -- a stack
is infrastructure a proxy site points at, not a request handler. See
`architecture-stacks.md`. Game/VM workloads are the INSTANCE tier; see
`instance-tier-plan.md`.
