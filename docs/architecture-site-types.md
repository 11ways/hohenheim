# Site Type Architecture

## STATUS 2026-08-12 -- full rewrite

This document was rewritten from scratch today. Everything before this date described
the 2024-era THREE-type design (proxy/static/redirect), hand-registration, a hand-built
tabbed `edit.hwk` with `{% if siteType{:} == ... %}` blocks, and a "Migration Path"
section listing work that shipped long ago. None of that describes the code any more:
eleven types are implemented, registration is compile-time discovery, and the admin form
is a generated zenit-cms resource. If you followed an old link here expecting the
four-layer/three-type text, the layer VOCABULARY survived (data model, registry,
request handling, admin UI) but every concrete claim under it was re-derived from source.
Older wording is not preserved -- git history holds it (`git log -- docs/architecture-site-types.md`).

## What a site type is

A site type is one class implementing `SiteTypeHandler`
(`src/server/java/be/elevenways/hohenheim/server/sitetype/SiteTypeHandler.java`), which
extends the common-side `SiteTypeInfo`
(`src/common/java/be/elevenways/hohenheim/sitetype/SiteTypeInfo.java`). It declares:

- its `Identifier typeId()` -- the string form is the stored `sites.site_type` value;
- display facets from `TypeDefinition`: `getDisplayName()`, `getLabel()` (microcopy),
  `getIcon()`, `getColor()`;
- `getDescription()` (SiteTypeInfo:20);
- `Schema getSchema()` -- the settings schema stored polymorphically in `sites.settings`;
- `createHandler(Row site, Map<String, Object> settings)` -- the long-lived request handler.

`SiteTypeInfo` no longer declares `getIcon` itself: the icon and color facets ride the
typed `TypeDefinition` contract (`SiteTypeInfo.java:10-12`), where the accessor is
`default Icon getIcon()`
(`zenit/src/common/java/be/elevenways/zenit/common/orm/field/TypeDefinition.java:41`) --
an `Icon`, not a `String`.

Two capability booleans live on `SiteTypeInfo` and one on `SiteTypeHandler`:

- `supportsEnvInjection()` (`SiteTypeInfo.java:29`) -- may receive managed-database
  connection details as injected environment variables;
- `containerRuntime()` (`SiteTypeInfo.java:39`) -- the runtime is a container, not a host
  process, which decides whether an attached database must be on the SAME server (join its
  link network) or the local one (dial the published loopback port);
- `managedProcessEnvironment()` (`SiteTypeHandler.java:30`) -- the type spawns OS processes
  through the managed-process pipeline (workload identity claims, reserved control variables).

## Discovery: nothing is registered by hand

`SiteTypeHandler` carries
`@BlastDiscoverable(registrar = "be.elevenways.hohenheim.server.sitetype.SiteTypes#register")`
(`SiteTypeHandler.java:16`). The compile-time-generated `BlastAutoLoadInit` calls
`SiteTypes.register(handler)` for every implementation
(`SiteTypes.java:32-36`), which puts the instance in BOTH the common registry
`SiteTypeRegistry.REGISTRY` (`SiteTypeRegistry.java:12-13`) and the server-side
`Map<Identifier, SiteTypeHandler> HANDLERS` (`SiteTypes.java:19`). Adding a site type is
one class and no edit anywhere else.

Both `SiteTypes` and `SiteTypeRegistry` force `BlastAutoLoadInit.loaded` from a static
field that MUST stay LAST in the class, because the loader re-enters `register()` while
the class is still initialising (`SiteTypes.java:21-29`, `SiteTypeRegistry.java:16-22`).
Moving that field up silently empties the registry.

`SiteTypes.boot()` (`SiteTypes.java:48-50`) only initialises the shared process
infrastructure; the model write hooks it used to install now live in the discovered
`HohenheimWriteHooks` ZenitModule -- see the AIDEV-NOTE at `SiteTypes.java:41-46` for why
ordering forced that move.

## Data model

`SiteModel` stores the type in a `RegistryEnumField` fed by the registry and the settings
in a `SchemaField` resolved from it
(`src/common/java/be/elevenways/hohenheim/model/SiteModel.java:44-62`):

```java
public static final EnumField SITE_TYPE = SCHEMA.addField(
    RegistryEnumField.builder("site_type").registry(SiteTypeRegistry.REGISTRY) ... );

public static final SchemaField SETTINGS = SCHEMA.addField(
    SchemaField.builder("settings").schemaFrom("site_type") ... );
```

A second, independent `schemaFrom` pair covers provisioning: `SOURCE` (`local` / `git`)
and `SOURCE_SETTINGS` (`SiteModel.java:65-84`). Site type and source are orthogonal -- a
Docker site can be git-provisioned.

`SECURITY_REPORT_TOKEN` is deliberately a dedicated column and NOT a key inside the
polymorphic settings map, because the dynamic form entry rewrites that map on every admin
save and would drop it (AIDEV-NOTE at `SiteModel.java:100-109`).

## The eleven implemented types

All in `src/server/java/be/elevenways/hohenheim/server/sitetype/types/`. Identifiers are
`hohenheim:<name>`. `StaticFileHandler` in the same package is NOT a site type -- it is the
`SiteRequestHandler` that `StaticSiteType` returns (`StaticFileHandler.java:19`).

| Type | Id | Class | Runtime | env inject | container | managed proc |
| --- | --- | --- | --- | --- | --- | --- |
| Proxy | `proxy` | `ProxySiteType` | forwards HTTP upstream | no | no | no |
| TLS passthrough | `tls_passthrough` | `TlsPassthroughSiteType` | raw TLS relay, no HTTP | no | no | no |
| Static | `static` | `StaticSiteType` | serves files | no | no | no |
| Redirect | `redirect` | `RedirectSiteType` | 3xx response | no | no | no |
| Dead | `dead` | `DeadSiteType` | fixed 404 page | no | no | no |
| Dev namespace | `dev_namespace` | `DevNamespaceSiteType` | routes wildcard labels to dev-tunnel leases | no | no | no |
| Node.js | `node` | `NodeSiteType` | managed node child processes | yes | no | yes |
| Alchemy | `alchemy` | `AlchemySiteType` (extends `NodeSiteType`) | node children with the Alchemy wrapper | yes | no | yes |
| Java / Zenit | `java` | `JavaSiteType` | managed JVM fat-jar processes | yes | no | yes |
| Command | `command` | `CommandSiteType` | arbitrary managed command | yes | no | yes |
| Docker | `docker` | `DockerSiteType` | container per release | yes | yes | no |

### Settings schemas

Field names are the stored setting keys. Defaults are those declared on the field builder.

**Proxy** (`ProxySiteType.java:38-90`): `forward_scheme` (http/https), `forward_host`,
`forward_port`, `upstream_protocol` (http1 / h2-for-gRPC), `request_timeout` (s),
`websocket_upgrade` (default true), `ignore_certificates` (default false),
`rewrite_location` (default true), `socket` (PathField), `delay` (ms).
Socket mode WINS over host/port when set (`ProxySiteType.java:135-139`) and is reached
through a loopback TCP-to-AF_UNIX bridge because Undertow/xnio has no AF_UNIX client;
`{name}`/`{0}` placeholders are substituted per request from regex-host capture groups.
INPUT-TRUST OBLIGATION: those captures come from the untrusted Host header and are
validated before becoming part of a filesystem path.
Missing upstream and unparseable URIs both degrade to a 502 handler rather than throwing
(`ProxySiteType.java:126-154`).

**TLS passthrough** (`TlsPassthroughSiteType.java:25-49`): `forward_host` (required),
`forward_port` (default 443, range 1-65535), `proxy_protocol_v2` (default false),
`connect_timeout` (default 10s, range 1-300). It implements `TlsPassthroughProvider`,
whose `createHandler` throws `UnsupportedOperationException` by design
(`TlsPassthroughProvider.java:12-15`) -- these sites never see an HTTP exchange. Model
validation refuses HTTP-only domain and site options for this type
(`SiteModel.java:143`, `SiteModel.java:173`).

**Static** (`StaticSiteType.java:27-52`): `root_path` (PathField, directory browser),
`autoindex` (default **true** -- matching the Node original's ecstatic behaviour, see the
comment at `StaticSiteType.java:32`), `indexes` (default true), `show_hidden_files`
(default false), `delay` (ms), `fallback_file`. An empty `root_path` yields an empty 200,
not a 500 (`StaticSiteType.java:85-91`).

**Redirect** (`RedirectSiteType.java:25-46`): `target_url`, `http_status` (301/302/307/308,
falls back to 302 when unset or unparseable), `preserve_path` (default false), `delay` (ms).
A target that is not `http://`, `https://` or `/` is refused with a 502 rather than
emitting an open redirect (`RedirectSiteType.java:85-94`).

**Dead** (`DeadSiteType.java:21`): empty schema. Serves a fixed dark 404 page for parking
domains.

**Dev namespace** (`DevNamespaceSiteType.java:38-43`): `registration_token` (secret). The
matched wildcard's first label picks a live `DevLease`; a name with no lease renders the
dev-offline page (`DevNamespaceSiteType.java:83-100`). `SiteResource` mints a token
automatically when the field is left blank (`SiteResource.java:338-345`).

**Node.js** (`NodeSiteType.java:37-117`): `script` (PathField), `node` (RegistryEnumField
over discovered node versions), `wait_for_ready` (default false), `minimum_processes`,
`maximum_processes`, `delay` (ms), `environment_variables` (StringMapField, `secret()`),
`api_keys` (ListField, `secret()`), `user` (RegistryEnumField over discovered system
users), `use_ports` (default false), `memory_limit_mb`, `cpu_limit`.
Read the AIDEV-NOTE at `NodeSiteType.java:67-80` before touching the env map: `secret()`
is its ONLY control (it lives in a JSON SchemaField, so encryption is impossible), which
is why revision snapshots omit it and activity deltas collapse it to one redacted pair.
The AIDEV-NOTE at `NodeSiteType.java:105-110` is equally load-bearing: declaring
`memory_limit_mb` is what puts the site's children on the host memory budget, and a host
that cannot enforce a cgroup cap REFUSES the site rather than booking a paper limit.

**Alchemy** (`AlchemySiteType.java`): inherits Node's schema unchanged -- it overrides only
the facets, `getDefaultArgs()` (`--stream-janeway`), `useChildWrapper()` (true), and
`createHandler`, where `defaultWaitForReady()` flips to true because Alchemy apps always
signal readiness via janeway (`AlchemySiteType.java:42-68`).

**Java / Zenit** (`JavaSiteType.java:37-104`): `jar_path`, `java_binary`, `jvm_args`,
`app_args`, `working_directory`, `wait_for_ready`, `minimum_processes`,
`maximum_processes`, `delay`, `environment_variables` (secret), `api_keys` (secret),
`user`, `memory_limit_mb`, `cpu_limit`. No port argument is threaded onto the command
line: a Zenit app picks its listen port up from the injected `ZENIT__NETWORK__PORT`
override (`JavaSiteType.java:27-31`).

**Command** (`CommandSiteType.java:35-95`): `start_command`, `working_directory`,
`port_argument`, `wait_for_ready`, `minimum_processes`, `maximum_processes`, `delay`,
`environment_variables` (secret), `api_keys` (secret), `user`, `memory_limit_mb`,
`cpu_limit`. The command is tokenised on whitespace and `port_argument` is appended as
`<arg>=<port>` (`CommandSiteType.java:162-176`).

**Docker** (`DockerSiteType.java:28-108`): `image`, `tag`, `container_port`, `command`,
`dockerfile` (git-sourced only), `builder` (Dockerfile / Nixpacks, default Dockerfile),
`build_arguments`, `server` (RegistryEnumField over `ServerOptions`; blank = local daemon),
`environment_variables` (secret), `volumes`, `health_path`, `memory_limit_mb`, `cpu_limit`.
Two AIDEV-NOTEs here are worth reading before changing anything:
`DockerSiteType.java:60-65` explains why `build_arguments` is a SEPARATE field from the
runtime environment (a sandboxed build must never see runtime secrets, and a Dockerfile
ARG lands in image history), and `DockerSiteType.java:84-90` records that volume identity
is keyed to the SITE, not the instance, after a release was found mounting a fresh empty
volume. `getSchema()` refreshes the server registry before returning
(`DockerSiteType.java:131-134`).

Managed-process types (Node/Alchemy/Java/Command) turn a misconfiguration into a
`FaultedSiteHandler` serving an explicit 503 instead of half-starting -- see the identical
catch in each `createHandler`.

## Request handling

`SiteRequestHandler`
(`src/server/java/be/elevenways/hohenheim/server/sitetype/SiteRequestHandler.java`) is one
interface for every type: proxy types call `forwarder.forwardTo(...)`, direct types write
the exchange. Beyond `handleRequest`, it carries `getSiteId()` (default -1),
`getHealth()` (default UP), `mutateResponse(exchange)` (default null -- an optional
`ResponseMutator` applied just before response headers commit) and `destroy()`.

`SiteHealth` is `UP, DOWN, DEGRADED, DEPLOYING, UNKNOWN` (`SiteHealth.java:3-9`) -- the
`DEPLOYING` state is what a release-gated Docker site reports mid-swap.

`UpstreamForwarder` (`UpstreamForwarder.java:10-16`) now takes an `UpstreamTarget`; the
`forwardTo(URI)` overload is a default that wraps the URI. `UpstreamTarget` carries the
protocol choice and the ignore-certificates flag alongside the URI, which is how h2/gRPC
upstreams and per-site TLS trust reach the connector.

Handler lifecycle stays as designed: `onSiteUpdated` defaults to destroy-and-recreate and
`onSiteRemoved` to destroy (`SiteTypeHandler.java:38-49`).

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

There is no hand-built site edit template. `siteType` appears in ZERO of the 37 `.hwk`
files in the repo; the `{% if siteType{:} == "hohenheim:proxy" %}` design was never how
this shipped. The site editor is a generated zenit-cms `RowResource`
(`src/server/java/be/elevenways/hohenheim/server/cms/SiteResource.java:66-79`):

```java
FormSpec.builder()
    .add(SiteModel.NAME)
    .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(SiteModel.SITE_TYPE))
    .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(SiteModel.SETTINGS))
    .add(SiteModel.ENABLED)
    .add(SiteModel.DESCRIPTION)
    .add(RelationPick.of(SiteModel.AUTH_PROVIDER_ID, ...))
    .add(RelationPick.of(SiteModel.ACCESS_LIST_ID, ...))
    .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(SiteModel.SOURCE))
    .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(SiteModel.SOURCE_SETTINGS))
    .build();
```

The type selector is the derived `RegistryEnumField` entry; the settings block is the
derived dynamic `SchemaField` entry, which re-renders from the selected type's schema.
That is why a new type needs no UI work at all: declare labels/help via
`HohenheimFormCopy.label(...)`/`.help(...)` on the schema fields and the form is done.
The "future `<pl-schema-form>`" and "future `{% render templateId %}`" items from the old
text are moot -- the framework's schema-driven form entry is what ships.

The list view filters and sorts on type, enabled and status
(`SiteResource.java:80-97`). Everything that is not per-type lives on record subpages:
domains, databases, processes, deployments, dev sessions, plus the framework-contributed
subpages including the generic record-access tab
(`SiteResource.java:559-567`).

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
  sitetype/SiteTypeInfo.java          -- common metadata contract (extends TypeDefinition)
  sitetype/SiteTypeRegistry.java      -- Registry<SiteTypeInfo>, autoload-forced
  model/SiteModel.java                -- SITE_TYPE + polymorphic SETTINGS

src/server/java/be/elevenways/hohenheim/server/
  sitetype/SiteTypeHandler.java       -- @BlastDiscoverable server contract
  sitetype/SiteTypes.java             -- registrar + handler map + boot()
  sitetype/SiteRequestHandler.java    -- unified request dispatch
  sitetype/SiteHealth.java            -- UP/DOWN/DEGRADED/DEPLOYING/UNKNOWN
  sitetype/FaultedSiteHandler.java    -- explicit 503 for misconfigured sites
  sitetype/TlsPassthroughProvider.java, TlsPassthroughTarget.java
  sitetype/UpstreamForwarder.java, UpstreamTarget.java, UpstreamProtocol.java
  sitetype/UnixSocketBridgeConnection.java, TcpUpstreamConnection.java
  sitetype/types/                     -- the eleven types + StaticFileHandler
  proxy/SiteDispatcher.java           -- route table, generations, header policy
  proxy/PublicTcpListener.java, TlsSniRouter.java, InternalListenerRouter.java
  cms/SiteResource.java               -- the generated admin form + subpages
```

## Adding a site type

1. Write one class in `server/sitetype/types/` implementing `SiteTypeHandler` (or
   `TlsPassthroughProvider` for a pre-HTTP type).
2. Declare `ID`, a `SETTINGS_SCHEMA`, the facets, and `createHandler`.
3. Give each schema field a `HohenheimFormCopy.label`/`help` and add the microcopy keys.
4. Set `supportsEnvInjection` / `containerRuntime` / `managedProcessEnvironment` if the
   type runs a workload.
5. Nothing else. Discovery registers it, the model enum picks it up, and the admin form
   renders the schema.

## Related documents

Multi-container deployments are the STACK tier, deliberately NOT a site type -- a stack
is infrastructure a proxy site points at, not a request handler. See
`architecture-stacks.md`. Game/VM workloads are the INSTANCE tier; see
`instance-tier-plan.md`.
