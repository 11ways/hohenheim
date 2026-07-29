# Hohenheim platform plan: websites, DNS, PaaS, game servers and compute

Decided 2026-07-28, amended 2026-07-29 after the independent completion audit.
Scope: Hohenheim becomes one coherent replacement for the parts of Coolify,
Pterodactyl and Proxmox we actually operate, while retaining its website,
reverse-proxy and authoritative-DNS responsibilities. This document is the
authoritative umbrella roadmap. `hohenext-roadmap.md` remains useful as the
historical inventory of the PaaS work already built, but this document wins on
scope, security boundaries, dependencies and completion gates.

The unifying insight from the gap analysis remains: game servers and virtualized
compute share a missing tier -- a first-class INSTANCE record (container, later
VM, or supervised process) that is grant-scoped, console-attachable,
port-allocated, backup-able and schedulable, with NO domain attachment unless
one is explicitly linked. The PaaS tier is related but not identical: a deployed
application is a release-oriented workload with source builds, health-gated
rollout and rollback. It may lower onto an instance, but it is not silently
declared equivalent before that ownership model is designed.

**Context change that reframes everything (2026-07-28): the project is going
PUBLIC.** Other operators will run it against tenants they have not vetted.
Every earlier design note that assumed a trusted-friends deployment is void.
This plan is written for HOSTILE tenants. The consequences are concrete:

- The plan needs a stated threat model. It is the first section below, and it
  is load-bearing: every unresolved argument in the old plan (tenant-supplied
  images, whether `exec` is an ordinary capability, free-form env, advisory vs
  enforced ports, shared hosts) is really a question the threat model answers.
- Publishing the source publishes the vulnerabilities. The live defects that a
  friends-only deployment could carry as maintenance debt are now RELEASE
  BLOCKERS. They are Phase 0, and Phase 0 gates any public tag.

Mechanism-home rule applies throughout (see `/home/skerit/projects/javaweb/CLAUDE.md`
capability-to-home map): a generic MECHANISM lands as close to core as its
mechanics allow, WITH hohenheim as the first wired consumer in the same phase.
Nothing ships unwired. Nothing lands without a test.

### How to read this document

It carries three kinds of text and they do not have equal weight:

- **NORMATIVE:** the threat model, the replacement definitions, phase bodies,
  prerequisite blocks and every phase gate. These are the requirements. If two
  passages disagree, the GATE wins -- it is the thing a test can fail.
- **HISTORY:** blocks labelled `STATUS`, `AUDIT STATUS`, `FIRST-PASS STATUS`,
  `FOURTH-AUDIT`, `LESSON` or dated. These record what was found and when, so a
  later reader inherits the reasoning instead of rediscovering it. They are
  evidence, never authority. A `LANDED` note is a CLAIM to re-verify, and the
  track record says so: the third audit found 0.3 outright false, and the
  fourth found live bypasses still open in 0.2, 0.6, 0.8 and 0.9 plus
  incomplete gates in 0.1, 0.3, 0.4 and 0.7 -- all of them behind green tests.
- **RECON:** blocks labelled `RECON CORRECTION`, verified against code at a
  stated date with file:line. Trust these over the prose they correct, but
  re-check the file:line before acting -- the code moves.

Never delete a HISTORY or RECON block to tidy up; supersede it in place with a
dated line. Never state a finding twice in one section -- a second copy is how
"already fixed" and "still open" end up on the same page.

---

## Replacement targets and honest definitions of done

Platform names are direction, not acceptance criteria. We do not claim a
replacement merely because one happy-path container or VM can start. Before the
corresponding public claim, the operator-facing inventory of features we actually
use from the replaced product must be checked into this document and every item
must either have a passing gate or be an explicit non-goal.

### Coolify / Dokploy-class PaaS

The existing site, git deployment, database and stack tiers are the foundation,
not a completed replacement. The minimum claim requires: Dockerfile AND
buildpack/Nixpacks-style source builds; build isolation away from the control
plane and tenant runtime credentials; GitHub/GitLab-compatible provider and
webhook flows; projects/environments; preview deployments; health-gated
zero-downtime rollout; rollback; managed databases and credential injection;
domains/TLS; persistent storage; per-deployment logs; quotas; and a supported API
or CLI for automation. A feature we deliberately do not need is recorded as a
non-goal rather than disappearing from the inventory.

### Pterodactyl-class game panel

The minimum claim requires: curated templates; typed variables; install and
reinstall; power and console separated from arbitrary exec; file management;
port allocations; subuser capability delegation; ordered schedules whose tasks
carry capability checks; backups and restore; per-instance database allocation;
resource quotas; transfer between eligible hosts; live stats and logs; and a
tenant-facing API. SFTP may remain a stated non-goal only if the browser/API file
surface covers our real workflows. One Minecraft+Velocity journey proves the
architecture, not the replacement claim by itself.

### Proxmox replacement

"Everything we use Proxmox for" must be enumerated before the VM phase starts.
At minimum the inventory must decide: VM/container provisioning; templates and
clones; disks and storage pools; bridges/VLANs/firewall rules; cloud-init;
snapshots; off-host backups and restore; console and rescue access; host drain;
workload migration or an explicit cold-move substitute; device passthrough;
capacity/placement; node failure recovery; and whether clustering/HA is required.
The current Incus VM sketch is not called a general Proxmox replacement until
that inventory is closed.

### Combined product boundary

Sites, domains, DNS records, certificates, databases, deployments and instances
must share one tenant/accountability model. Generated records carry ownership and
cleanup metadata. A grant over an instance never implicitly grants a DNS zone or
domain; every cross-tier link checks authority over both sides. Existing
admin-only surfaces remain usable, but a public delegated tenant must be unable
to enumerate or mutate another tenant's object in ANY tier.

Owning phases, so this promise is not orphaned: sites are Phase 1, domains/DNS/
certificates are the Phase 2 parallel gate below, instances are Phase 3, game
domain mappings are Phase 5, and projects/deployments are Phase 7. Every tier
named in the paragraph above appears in exactly one of those. If a tier is
deliberately never delegated, it is recorded HERE as an admin-only non-goal
rather than left without an owner.

---

## Threat model and trust boundaries

This section is normative. Later phases refer back to it by name. If a phase
cannot state which boundary it defends, it is not ready to build.

### Actors

- **Operator / admin.** Runs the hohenheim install. Holds `hohenheim.admin.access`.
  Trusted with the host. Not the threat.
- **Delegated tenant.** A logged-in user granted record capabilities over some
  instances (a game-server renter, a sub-admin, a customer). ADVERSARIAL. May
  control the software running inside their own instance. May be many, may
  collude, may register freely if the operator opens signup.
- **Anonymous internet.** Visitors to any site hohenheim proxies, and callers
  of any public endpoint (webhooks, API, MCP host, ACME). ADVERSARIAL and
  unauthenticated.
- **Guest workload.** Code running INSIDE an instance (a game server, a build,
  a tenant container). Assume fully attacker-controlled: a tenant who can run a
  container can run arbitrary native code as the container's root.

### Boundaries and what defends each

1. **Host <- guest workload.** The strongest boundary we must hold. A tenant's
   code must not reach the host, other tenants' data, or the control plane.
   - A Docker/Incus SYSTEM container is NOT a security boundary against a
     determined hostile root-in-container. Shared kernel, historical escape
     surface. **State this in every place the plan offers containers to
     untrusted tenants.** The isolation we actually rely on for hostile
     multi-tenant is one of: (a) user-namespace-remapped, capability-dropped,
     seccomp/apparmor-confined containers on a host that runs NOTHING else of
     value, or (b) a VM per tenant (Phase 8), or (c) one dedicated host per
     tenant. The control plane must be able to REFUSE to co-locate untrusted
     tenants on a host that also runs the hohenheim control process or another
     tenant, unless the operator explicitly accepts the risk per host.
   - Concretely for Phase 3: the Docker driver runs tenant containers with
     `--cap-drop=ALL` plus a minimal add-back set, `--security-opt=no-new-privileges`,
     a seccomp profile, read-only rootfs where the template allows, no host
     bind mounts (the stack tier already forbids these -- reuse), user-ns
     remap on for tenant-owned instances. Incus (Phase 4) gets unprivileged
     containers by default; privileged containers are an admin-only template
     flag with a stated warning.

2. **Control plane <- guest workload.** The IPC channel, the shared cache, the
   port allocator, the metrics stream. A guest must not be able to read or
   corrupt another tenant's state or the control plane's. (Live defect: the
   process IPC channel is unauthenticated -- Phase 0.)

3. **Admin session <- anonymous internet.** An unauthenticated visitor to a
   hosted site must not be able to run script in an admin's browser or ride an
   admin's credentials. (Live defect: stored XSS in the log viewer -- Phase 0.)

4. **Tenant A data <- tenant B.** Record grants are the boundary. It must be
   impossible for a tenant to see, enumerate, or act on a record they hold no
   grant for -- including via ungated RecordSources, revision/activity
   subpages, WebSocket handshakes, or route-conflict takeover. (Live defects
   4, 6, 9 -- Phase 0/1.)

5. **Owner authority <- API key.** A scoped API key must never exceed the
   authority its owner delegated, and must never be a path to
   security-sensitive account changes (TOTP re-enroll, session revoke).
   (Live defect 3 -- Phase 0.)

6. **Federatable authority <- app-local authority.** The two authz systems
   stay separate by tuple position (see doctrines). A record capability must
   never resolve through PermissionResolver and never enter KnownPermissions.

### Explicit non-goals / accepted risks (state them, do not pretend)

- We do not defend a shared host against a hostile tenant with pure container
  isolation. That configuration is offered only with an explicit per-host
  operator acknowledgement, and the VM tier (Phase 8) is the real answer.
- We do not sandbox the game/app code a tenant chooses to run inside their own
  instance. The blast radius of that code is the instance and whatever the
  instance can reach; the boundaries above bound the reach.
- Denial of service by resource exhaustion is bounded by quotas and resource
  limits, not eliminated.

### Capability sensitivity classes (drives Phase 3 capability design)

- `view`, `console` (stdin/stdout of the instance's OWN primary process),
  `power` (start/stop/restart), `files.read` -- ordinary tenant capabilities.
- `files.write`, `snapshots`, `backups`, `config`, `access.manage` (edit the
  record's own grants -- delegation of delegation, Pterodactyl's most
  sensitive subuser permission) -- elevated tenant capabilities (can change
  what runs, exfiltrate, or widen access).
- `exec` (run an ARBITRARY command as an arbitrary user inside the container)
  -- this is effectively root-in-container and therefore a host-escape
  amplifier. It is NOT an ordinary capability and is NOT the same thing as
  `console`. It is admin/type-level by default; delegating it to a tenant is a
  deliberate operator choice with the escape risk stated. The old plan
  conflated console and exec; that conflation is corrected here and is the
  reason the Phase 3 gate ("delegate console+power, prove they cannot change
  config") is only meaningful once the two are distinct.
- Tenant-supplied arbitrary images are equivalent to `exec`: a hostile image
  is arbitrary native code. Tenants create instances from TEMPLATES (curated,
  operator-approved image sources) by default. Pulling an arbitrary image is
  an admin/type-level capability (`hohenheim.instances.image_any` or similar),
  never a default tenant grant.

---

## Doctrines already settled (do not re-litigate)

- Plain Debian is the canonical host; hohenheim is the SOLE manager of any
  host it runs on. There are TWO install kinds, and the doctrine "never take an
  LXC guest" applies only to the first:
  - **Managed compute node:** owns its host, runs instances, needs Docker/Incus,
    ports, nftables. Never itself an LXC guest on someone else's Proxmox.
  - **Control-plane / auxiliary appliance:** a DNS-only or proxy-only or
    control-only install that legitimately runs INSIDE an LXC guest on
    someone else's Proxmox and manages compute nodes remotely or nothing at
    all. This is the "DNS-only LXC guest" scenario, and it is an appliance,
    not a compute node. Install roles (Phase 2) are what make the two kinds
    one codebase.
  Proxmox, where it exists, sits BELOW a compute node and hands over full VMs.
- The site stops being the only spine. Sites remain the HTTP/domain tier;
  instances are the compute tier; a domain link between them is optional.
- Minecraft traffic always flows through Velocity (it is also the chat layer).
  Hohenheim owns the domain-to-backend DATA and materializes it as generated
  Velocity config and DNS records. No Minecraft protocol handling in-house.
- Two authorization systems stay side by side (adversarially validated,
  PASS-WITH-CONDITIONS): dotted permissions (federatable, wildcard, Proteus-
  sourced) and record grants (app-local, exact-match, per-record). The real
  boundary is FEDERATABLE vs APP-LOCAL, not type-level vs record-level.
  Separation is by tuple position, not syntax: record capabilities MAY be
  dotted (`files.read`) but never resolve through PermissionResolver and never
  enter KnownPermissions.
- Runtime support is a PER-HOST seam: docker | incus | (later) proxmox-lxc,
  plus the existing managed-process supervisor. The runtime is DATA on the
  server record.
  - Docker transport: hand-rolled HTTP over the local socket or over SSH (the
    existing DockerClient pattern). SSH host-key pinning is REQUIRED (see the
    Phase 0 note: `accept-new` with no fingerprint column is a live gap).
  - Incus transport is NOT "the DockerClient pattern". Corrected doctrine:
    Incus is a unix socket locally and HTTPS with a TLS CLIENT CERTIFICATE on
    port 8443 remotely, enrolled with a trust token. This is good news: Incus
    supplies node identity and enrollment for free, so we do NOT build a
    bespoke Wings-style node agent. Node identity = Incus TLS enrollment for
    Incus hosts, SSH host-key pin for Docker hosts.
- Containers first, VMs later -- with the threat-model caveat that VMs are the
  only strong isolation boundary against hostile tenants on shared iron.

---

## Phase 0 -- Public-launch security baseline (RELEASE BLOCKERS)

None of these are new product surface. All are live, exploitable-today defects
verified at file:line. Going public without them ships the exploit alongside
the source. Phase 0 gates the first public tag. Each item names the boundary
it defends (see threat model) and the structural fix, not a spot patch.

Ordering within Phase 0 is by blast radius, not dependency; they are largely
independent.

**STATUS: REOPENED 2026-07-29.** A fourth independent audit found live bypasses
in 0.2, 0.6, 0.8 and 0.9, plus incomplete structural gates in 0.1, 0.3, 0.4
and 0.7. The former COMPLETE claim is retained below only as historical context.
No public tag is allowed until the replacement gate at the end of this section
passes against the current commits.

### 0.1 Stored XSS in the admin log viewer (boundary 3)

`ManagedProcessSiteHandler.java:941-943` stores raw child stdout;
`cms/site-processes.hwk:146` renders it with `{%=` (raw HTML). An anonymous
visitor plants a payload via request path / User-Agent; it runs in the admin
session, on a page carrying the CSRF token. The in-code comment claiming
ghostty renders it is STALE -- this is the only `{%=` in hohenheim, and there
are none anywhere in zenit/zenit-cms/zenit-forms/plumage/zenit-widget/zenit-auth.

- Structural fix (mechanism, zenit / hawkeye): there is NO HTML sanitizer in
  the stack and `security_headers.csp` defaults to empty. Both are platform
  gaps a public product cannot have. Add (a) a default non-empty CSP that
  forbids inline script for the admin panel, set by zenit-cms / applied by
  hohenheim, and (b) treat log content as TEXT, not HTML -- the proclog viewer
  renders ANSI client-side into escaped nodes, never via `{%=`. The `{%=`
  usage is deleted.
- CSP RECON (verified 2026-07-28): a strict `script-src 'self'` is ONE LINE
  away from shippable, and that line fails SILENTLY.
  - THE BLOCKER: `zenit-cms/src/common/templates/shell.hwk:27` is
    `<body onload="main()">`, and that inline handler is the ONLY thing that
    starts the TeaVM bundle (it ends with `$rt_exports.main = ...` and never
    self-invokes). Under `script-src 'self'` every admin page renders correctly
    and is completely INERT: no hydration, no custom elements, no soft
    navigation, no confirm dialogs. Every hohenheim admin template extends
    `zenitcms:shell`, so that is 100% of the admin surface. Fix by moving the
    bootstrap to where bundle injection already lives
    (`RenderEngine.preloadScriptsToElement`, `RenderEngine.java:409-429`, which
    already emits a clean same-origin `<script src>`), then update the ~40
    test templates that copy the `onload` shape so production and tests do not
    diverge.
  - Everything else in the stack is already CSP-clean: zero `<script>` tags,
    zero `javascript:` URLs, zero other `on*=` handlers in production
    templates; the hydration payload is an `application/dry` data block which
    CSP does not police (expect scanners to flag it anyway).
  - Concessions that must be accepted, not fought: `style-src` keeps
    `'unsafe-inline'` because `style:` bindings serialize to `style="..."` in
    SSR and the framework itself does it -- `RenderContext.java:2684` puts
    `position:fixed` on the `<he-bottom>` portal target, so blocking it
    corrupts layout on EVERY overlay-bearing page. `script-src` needs
    `'wasm-unsafe-eval'` for `pl-terminal`'s ghostty wasm (used at
    `cms/site-processes.hwk:97`), and `img-src` needs `data:` for
    `Brand.BLANK_ICON`.
  - Shippable policy: `default-src 'self'; script-src 'self' 'wasm-unsafe-eval';
    style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self';
    connect-src 'self'; frame-ancestors 'none'; base-uri 'self';
    object-src 'none'; form-action 'self'`. This DOES stop the 0.1 payload:
    an injected `<script>` has no `'unsafe-inline'` and an injected
    `<img onerror>` has no `'unsafe-hashes'`.
  - MECHANISM HOME: NOT a global zenit default. A global CSP would leak the
    admin policy onto public zenit-pages surfaces on a shared host. The correct
    home is a panel-prefix `Middleware` in zenit-cms (weight 1, path prefix =
    panel slug; `Middleware` already supports prefixes and null-return
    chaining) plus a `cms.csp` setting. Hohenheim's tenant proxy is a separate
    raw TCP layer that never passes through `HttpConduit`, so proxied tenant
    sites are unaffected either way.
- Consumer: hohenheim proclog + live-process viewer.
- Gate: a browser test plants `<script>`/`<img onerror>` through a proxied
  request's path and User-Agent, opens the log viewer as admin, asserts no
  script execution and escaped rendering.

  AUDIT STATUS: IMPLEMENTATION SAFE, GATE INCOMPLETE. The current browser test
  injects payloads through child environment variables, not an anonymous request
  traversing the production proxy listener. Keep the existing renderer/CSP
  assertions and add the stated proxy-ingress journey. Also pin every auth route
  family claimed by the scoped CSP (`login`, `logout`, `setup`, `account`,
  `admin`), an unclaimed public path, exactly one `/_hawkeye/boot.js`, and exactly
  one `main()` invocation.

### 0.2 Process IPC channel is unauthenticated (boundary 2)

`IpcChannel.java:51` `ServerSocket(0,1,loopback)`, `:78` a single `accept()`,
no token, port handed to the child via `HOHENHEIM_IPC_PORT`. Processes run
under DISTINCT system users, so this is cross-tenant: another tenant on the
box reads/writes a victim's shared cache (`remcache_*`), forces
`markAddressInUse()`+`kill()`, or connects first to permanently wedge the
victim (accept() never runs again).

- Structural fix: adopt the dev tunnel's proven shape verbatim
  (`DevTunnelServerHandler.java:43,251-265`): a per-child secret passed
  alongside the port, constant-time compared, a small pre-auth connection cap,
  and re-accept after a failed/closed peer so one bad client cannot wedge.
- Gate: a test connects without the token (refused, victim survives), with the
  token (works), and a second connection cannot starve the first.

  AUDIT STATUS: REOPENED. The server token/cap/re-accept mechanism landed, but
  three contracts remain false:
  - Distinct UIDs are assumed, not enforced. The site `user` setting is optional
    and multiple sites may share one UID; same-UID workloads can potentially read
    each other's process environments and steal the token. Enforce a workload
    identity policy (unique tenant UID or a stronger isolated transport) instead
    of documenting distinct users as fact.
  - `ManagedProcessSiteHandler` writes the reserved IPC variables before
    operator/injected environment maps, so a configured
    `HOHENHEIM_IPC_PORT`/`HOHENHEIM_IPC_TOKEN` can replace them. Reserved control
    variables must be stamped LAST or rejected at validation.
  - The Node wrapper assigns its socket before the connect callback writes auth.
    A fast child's one-shot `ready` can queue BEFORE auth and get the peer
    refused; messages emitted during reconnect are dropped. Buffer child messages
    until auth has been written, flush in order after connect, and retain them
    across bounded reconnects.
  Replacement gate: a REAL one-shot child emits `ready` immediately; auth is the
  observed first line, ready arrives exactly once after an initial refusal, all
  eight pre-auth stalls coexist while an authenticated child remains attached,
  and two tenant workloads are refused from sharing an IPC-readable identity.

### 0.3 API-key privilege escalation (boundary 5)

Confirmed end to end: `ApiKeyPrincipal` is non-anonymous; `/account/**` is
`requiresLogin()` only; core authz accepts any non-anonymous principal;
`ApiKeyResolverMiddleware` runs weight 12 on prefix `/`. An API-key GET
manufactures a session (`SessionCsrfTokenStore.issueOrRead` ->
`conduit.sessionOrCreate()`), returning both the CSRF token (rendered at
`auth/api-keys.hwk:49`) and the `Set-Cookie`. Blank scope == full owner
authority (`parseScopes` accepts blank; empty scopes satisfy every check).
`AuthFlowIntegrationTest.java:878-880` already proves the pivot. An API key can
also silently re-enroll the owner's TOTP (no current-password check, unlike the
password/disable paths) and revoke all sessions.

- Structural fixes (zenit-auth):
  1. Security-sensitive account routes (TOTP enroll/disable, session revoke,
     password, API-key management) require an INTERACTIVE session principal,
     not merely non-anonymous. Add a route/endpoint marker
     ("requiresInteractiveLogin" or an `ApiKeyPrincipal`-excluding guard);
     `ApiKeyPrincipal` is refused there regardless of scope.
  2. CSRF token issuance must not mint a session for a non-interactive
     principal. An API-key request gets no session cookie and no token.
  3. Blank/empty scope stops meaning "everything". Empty scope is refused at
     parse time; a key's authority is the explicit intersection of owner
     authority and declared scopes. This dovetails with the scope-coherence
     work in Phase 1 (`cap:` prefix). Until Phase 1 lands the prefix, the
     minimum here is: empty scope == no authority, not full authority.
     EXISTING keys with blank scopes get stated migration semantics, not a
     silent behavior flip: on upgrade they are treated as no-authority until
     the owner re-scopes them (surfaced on the api-keys page), never silently
     kept at full authority.
- Gate: extend `AuthFlowIntegrationTest` -- an API key gets 401/403 on TOTP
  enroll, session revoke, and any `/account` mutation; a blank-scope key is
  rejected at creation.

  STATUS: 0.3 LANDED IN TWO PARTS. The first pass (`9bdd914`, `644f4ac`,
  `d0fea22`) marked the 13 `/account` routes interactive-only, stopped
  `issueOrRead` minting a session for a non-interactive principal, and made
  blank scopes inert. A hostile RE-REVIEW on 2026-07-29 found that pass left
  the escalation HALF OPEN, and the plan wrongly recorded it as closed:
  - `verify()` was never gated, only `issueOrRead`. It compared the submitted
    token against `conduit.session()` without binding it to the principal.
  - An anonymous `GET /login` mints a session AND a CSRF token (`AuthHandlers`
    calls `conduit.csrfToken()` with no principal), `SessionResolverMiddleware`
    attaches an anonymous session without setting a principal, so
    `ApiKeyResolverMiddleware` then installs the `ApiKeyPrincipal`.
  - The five state-changing `/admin` POSTs never got the marker, carrying only
    `requiresPermission`. So a key whose owner holds `auth.roles.edit` could
    replay the anonymous cookie+token beside the key and grant itself any
    permission in three requests -- a LARGER escalation than the account
    routes the first pass closed.
  - The five POST assertions meant to pin the markers were VACUOUS: the helper
    sent no cookie and no token, so `CsrfMiddleware` (weight 25) answered 403
    before authorization (weight 50) ever ran, and the test asserted only the
    bare status. Reverting the markers left it green. Both middlewares return
    403, which is exactly how the defect hid.
  FIXED (`2a5d36a`, `6208824`, `a0ea02d`, `7b2d15a`, zenit `761caff`):
  `verify()` now mirrors the `issueOrRead` guard; the five `/admin` mutations
  are interactive-only; `Principal.isInteractive()` now defaults to FALSE so an
  implementation that never considered the question fails CLOSED (sweep found
  exactly three implementors ecosystem-wide, all handled); the test walks the
  real attack shape and asserts the specific refusal code plus the absence of
  the grant row, with an interactive positive control. Compatibility checked:
  every API-key POST surface in every repo is already `csrfExempt()`, which
  short-circuits before `verify()`, so no real consumer regresses.
  DELIBERATELY NOT DONE: the `/admin` GETs stay reachable by an admin-scoped
  key. Whether they become interactive-only is open decision (3) below.
  LESSON: two layers each assuming the other covers the case. Assert the
  SPECIFIC refusal code whenever two middlewares can answer with the same
  status, or the test cannot tell you which one refused.

  FOURTH-AUDIT ADDENDUM: the cookie+token+key escalation itself is closed, but
  the auth transport still has release-blocking hardening work:
  - `AuthCookieSupport.secureAttribute` duplicates HTTPS detection and ignores
    direct TLS, `network.assume_https`, and trusted-proxy evaluation. Login can
    therefore issue an authenticated cookie without `Secure` on an effectively
    HTTPS request. Expose/reuse the one `HttpConduit.isEffectivelyHttps` decision
    through the conduit contract and pin direct TLS, assume-HTTPS, trusted and
    untrusted forwarded-proto cases.
  - `SessionCsrfTokenStore.rotate()` must mirror `issueOrRead`/`verify` and refuse
    a non-interactive principal even though no production caller reaches it yet.
  - `ApiKeyService.create` normalizes every scope (trim, reject blank, deduplicate)
    at the service boundary, not only in the HTML handler.
  - A `csrfExempt()` endpoint must declare its credential mode. It cannot accept
    an interactive cookie principal merely because session resolution wins over
    an API-key header. `zenit-a2ui` action dispatch is the current counterexample:
    external API callers need the exemption; browser sessions need CSRF.
  The `/admin/**` GET decision remains product policy, not part of the mutation
  escalation closure.

### 0.4 Ungated RecordSources leak installation data (boundary 4)

`RecordSource` defaults to login-only, no permission (`RecordSource.java:238-247,706-709`;
`authorizes()` at `:238` is the ONLY gate). `HohenheimSources.java:51-105`
registers 11 sources with no permission and no accessCriteria (the audit said
10; it missed `AccessListModel` and `SiteAuthProviderModel`); `zenit.activity`
(`ActivitySources.java:46-56`) has no permission parameter at all and exposes
the whole audit log to any `/manage` operator. Same class in proteus, orcono,
zenit-media. This is a default that leans wrong, not one careless author.

RECON CORRECTION (verified 2026-07-28) -- the shadowing works the OPPOSITE way
from the original finding, and boot order cannot fix it:

- `RecordSourceRegistry.register` (`:32-37`) IS silent last-write-wins, but that
  is not what causes the hohenheim leak. The GATED registrar is FIRST-write-wins:
  `CmsRecordSources.java:73-75` returns early when an id is already registered.
  Hohenheim registers at the MODULES boot stage, BEFORE `ensureRegistered`
  (`CmsBoot.java:31`), so hohenheim's ungated sources permanently shadow the
  permission+accessCriteria sources the CMS would otherwise install. Both
  directions need fixing.
- Four call sites reach a source with NO gate at all (they never call
  `authorizes()`): `MediaEndpointHandlers.java:115-116`,
  `FormRenderDefaults.java:229,264,485`, `SubmittedValueCoercion.java:589,637`,
  `ResourceListPageRenderer.java:388-389`.
- The VOCABULARY endpoint (`RecordSourceHandlers.java:199`) passes NO access
  context, leaking the queryable variable set to anyone past the coarse gate.
  `vocabulary()`, `project()` and `item()` have no access-aware overload at all.
- The non-access overloads of `buildQuery/resolveRow/resolveRows/existsId`
  (`:350,428,456,511`) are still public and silently skip `accessCriteria`.
  Gating only the access-aware overloads leaves the bypass wide open.

- Structural fix (mechanism, zenit core `common/data`): make the wrong thing
  impossible to declare silently.
  1. Registration requires an EXPLICIT access decision: a permission, an
     `accessCriteria`, or an explicit `.internalOnly()` / `.publicRead()`
     opt-out. A source with none refuses to register (loud boot failure in
     dev; logged-and-denied in prod). The permissive default is removed.
  2. `RecordSourceRegistry.register` stops being silent last-write-wins: a
     second registration for an id must be an explicit override or it is
     refused, so a gated source can never be shadowed by an ungated one
     regardless of boot order.
  3. `zenit.activity` and every framework-owned source declares its gate.
- Consumers migrated in the same phase: hohenheim's 10 sources
  (`hohenheim.ban`, sites, dns zones, system users, databases, certificates,
  activity) get permission + accessCriteria; zenit-media, proteus, orcono
  sources get theirs. QQ/thoth/spamservice/zenit-ai already gate correctly and
  serve as the reference.
- Also: `RecordSource` gains a `.secret()` gate (finding 28 -- it guards
  `isEncrypted()` only) so a projected secret is neither returned nor turned
  into a queryable rule variable (the `icontains` value oracle). The mechanism
  already exists: `Field.isFilterable()` (`Field.java:338-345`) is already
  `isSecret() || isEncrypted() -> false`, and `SchemaVocabulary.java:41-45` is
  the reference consumer. The secret surface is WIDER than deriveVocabulary:
  `project()` (`:533-546`) and `item()` (`:554-581`) copy every projected field
  verbatim; derived `sortable` (`:115-118`) and `search()` (`:962-964`) filter
  encrypted but NOT secret, giving order-by, bucket-count (the sortable
  whitelist doubles as bucketable, `RecordSourceHandlers.java:76-81`) and
  `icontains` oracles; and the `displayTitle` fallback (`:604-614`) can emit a
  secret as a record's title. Fix all of them through `isFilterable()`.
- Gate: a test asserting a source with no access declaration fails to register;
  a logged-in non-operator gets 403/empty on each hohenheim source; a re-register
  attempt without explicit override is refused.

  FIRST-PASS STATUS: 0.4 LANDED (zenit 3180c23, zenit-cms 83ea08d, zenit-media 991b468,
  proteus e5b6095, orcono 580af4f, hohenheim d905bd2). Shape notes:
  - Opt-outs are `.openToAllLoggedIn()` / `.openToAnonymous()`; an explicit
    `loginRequired(...)` call also counts as a declaration (kept the untouched
    test fixtures of sibling repos green). Refusals are slog-and-skip by
    default and THROW when `debugging.debug` is true (`setStrictRegistration`,
    set by ServerZenitRuntime before ROOT_STAGE).
  - Shadowing: `register` refuses a held id, `override` replaces deliberately,
    `registerDefault` (the zenit-cms auto-glue rank) yields to any existing
    registration and is replaced by a later explicit one -- explicit beats
    derived in either boot order.
  - The non-access overloads were KEPT and answer as the ANONYMOUS audience;
    `authorizes()` is enforced inside every query/resolve/exists/vocabulary
    call, so the four ungated call sites are closed in the mechanism, not per
    caller.
  - The `hohenheim.manage_site` source was DELETED: the SiteModel DEFAULT
    source (ManagePanel.registerSiteSource) now carries the grant scope for
    admins and /manage tenants alike.
  - Known follow-ups in repos outside this phase's edit set: 3 zenit-forms and
    2 zenit-widget TESTS pin the removed permissive default or rely on silent
    same-id re-registration (one-line fixes: add a declaration / use
    `override(...)`): InlineCreateEndpointsTest.loginRequiredSourceRejects
    AnonymousCreates, RelationPickTranslationTest.thumbnailAndEntryTemplate...,
    RelationMultiPickTranslationTest.accessScopedMultiPick...,
    RecordsAccessThreadingTest.sourcePermissionIsRequiredBeforeQuerying,
    RecordsWidgetTest.aSortOutsideTheSortableWhitelistIsRejected.
  - Pre-existing failure NOT from this phase (stash-bisected): AdminPagesTest.
    settingsPageRendersSavesResetsAndRefusesInvalidValues (array-editor reset,
    the 0.6a/settings workstream's surface).

  FOURTH-AUDIT STATUS: CURRENT HTTP DATA PATHS ARE SCOPED, STRUCTURAL CLAIM
  INCOMPLETE. Registration/ranking and the Hohenheim query/item endpoints held
  up, but public `project(row)` and `item(row)` still accept arbitrary rows with
  no access context; in-process `buildQuery` accepts an arbitrary sort field;
  `RecordSourceBuckets.countPerDay` accepts an arbitrary DateTimeField; and
  `Model.resolveDisplayTitle` can resolve a secret display field before the
  source fallback's secret check. Add access-aware translation paths, validate
  all in-process field arguments against the source declaration, and refuse a
  secret/encrypted schema display field at declaration or title resolution.
  Widget editor vocabularies also fail open outside their server ThreadLocal
  scope by design. Split stored-config validation from viewer-facing vocabulary
  resolution in core instead of relying on ambient scope; the browser must not
  reveal source choices or variable names the viewer cannot use.

### 0.5 Plumage publishes an unauthenticated root shell (boundary 1/3)

`plumage/.../TerminalEndpoint.java` declares `/ws/terminal` with no login, no
permission, no revalidation, and spawns `$SHELL` on a PTY with the server's
full environment. It is in the published `plumage-server` artifact. Dormant
only because nothing loads the class outside plumage's own browserTest, and
endpoints self-register from static initialisers -- one class-load away in any
consumer, and its route collides with hohenheim's real terminal.

- Structural fix: the demo terminal endpoint must not be in the published
  server artifact. Move it to plumage's test source set (browserTest), or gut
  it to a fake that does not spawn a shell. A published framework artifact
  never ships a self-registering endpoint that spawns a shell with no auth.
- Gate: a test asserting no endpoint spawns a shell without a permission check
  in the published artifact; the route is free for hohenheim's real terminal.

  AUDIT STATUS: PASS. The only terminal endpoint left in plumage is a
  browser-test echo fixture; the published server artifact contains no endpoint
  or process spawner. Keep the artifact-level test.

### 0.6 Secrets at rest and in derived surfaces (boundaries 1, 4)

Two problems, one theme: secret handling is not a platform property yet, and
the instance tier will multiply it.

RECON CORRECTION (verified 2026-07-28): "encrypt these fields" is NOT
per-field work. Phase 0.6 splits into three parts of very different size, and
only the first two are release blockers.

- **0.6a -- Redaction in derived surfaces (CHEAP, and the ONLY protection some
  fields can ever have). RELEASE BLOCKER.**
  Key everything off `isSecret()`, not `isEncrypted()`: they are orthogonal
  (`Field.java:316-331`) -- secret is a presentation contract, encrypted is a
  storage representation, and every field at risk is `.secret()`-only.
  `ActivityLog.isRedacted` (`:481-483`) already has the right predicate
  (`isSecret() || isEncrypted()`); the other three sites must adopt it.
  - `RevisionableBehaviour.java:319` and `:329` skip localized+encrypted but
    NOT secret. SiteModel is the only revisionable hohenheim model and keeps 50
    revisions, so `zenit_revisions` holds `security_report_token`, the whole
    settings map and source_settings in CLEARTEXT today.
  - `DiffRendering.java:38-55` has the `Field` in hand and never checks. This
    is a SECOND independent leak: a raw revision snapshot flows through it even
    if the stored delta were redacted. Consumers: `RevisionHistoryPageRenderer.java:139`,
    `ActivityHistoryPageRenderer.java:129`, `ActivityDetailPageRenderer.java:80`.
  - `ActivityLog.computeDelta` (`:431-472`) redacts TOP-LEVEL only, with no
    recursion. A SchemaField is one entry, so a non-secret parent carries every
    `.secret()` sub-field into the delta verbatim. CONFIRMED leaking today: git
    `webhook_secret`, dev-namespace `registration_token`. CORRECTION: Proteus
    `access_key` does NOT leak today -- deltas need `ActivityPolicy.ALL` and
    hohenheim sets that for SiteModel only (`HohenheimSources.java:109`). It is
    one `setPolicy` line from leaking; fix it, but do not cite it in a gate test.
  - This part is HIGHER priority than the plan implied, because sub-schema
    fields CANNOT be encrypted at all (see 0.6c) -- recursive redaction is
    their only control, ever.

- **0.6b -- Hash what should never have been stored recoverably. RELEASE BLOCKER.**
  Site `api_keys` (confirmed on all four counts: `ListField<String>` with no
  `.secret()` in `NodeSiteType:72-74`/`JavaSiteType:74-76`/`CommandSiteType:64-66`,
  compared with `HashSet.contains` at `ManagedProcessSiteHandler.java:773` which
  is `String.equals` and not constant-time, no throttle anywhere on that path,
  cloned verbatim at `SiteResource.java:276`). Fix by copying `ApiKeyService`'s
  shape (mint once, store `sha256Hex`, constant-time compare, one-shot
  disclosure -- `SpamserviceClientKeysResource.java:47-51` is the in-repo
  precedent for showing a raw key once). Note `cloneSite` already regenerates
  `webhook_secret` at `:283` but not this -- a half-fix that reads as handled.
  Also one-word fixes: `DnsRecordModel.java:75-76` `DYNDNS_TOKEN` is a bearer
  credential that is not even `.secret()` (missed by the original list, cheapest
  fix in the phase). CORRECTION (git blame, 2026-07-28):
  `NotificationChannelModel.URL` has been `.secret()` since `69be8b6` and
  `AccessListModel.BASIC_AUTH_PASS` since `284ffab` -- that half of the bullet
  was stale.

  FIRST-PASS STATUS: 0.6b LANDED (`9ef5c2f`, `6f133b0`). Findings worth carrying forward:
  - The plaintext-key defect was LATENT, not live: `SiteDispatcher.continueAfterAuth`
    strips `X-Hohenheim-Key` from the request headers BEFORE `dispatchToRoute`,
    so the managed-process control API is currently UNREACHABLE through the
    public proxy listener. The strip was deliberately NOT removed -- doing so
    would make a privileged endpoint publicly reachable, the opposite of a
    security fix. AIDEV-NOTEs mark both sites so whoever revisits does it
    deliberately. Treat the hashing as defence in depth for whoever makes that
    path reachable again.
  - Core `Endpoint.rateLimit` / `rate_limit.*` / the weight-15 middleware CANNOT
    reach this path: `SiteDispatcher` is an Undertow handler on the proxy
    listener and never runs the zenit conduit middleware chain. Core's
    `RateLimiter` primitive was used directly instead, keyed on site + resolved
    client IP. Any future proxy-path throttling has the same constraint.
  - Existing configured keys were preserved: hashing a plaintext key IN PLACE
    keeps it valid, swept once via `SeedContext.once` (a MigrationBuilder
    migration cannot do it -- the keys live in a JSON SchemaField, `execute` has
    no read-back, and SQLite has no sha256).
  - OLD REVISIONS AND ACTIVITY DELTAS STILL CONTAIN THE PLAINTEXT KEYS written
    before the change. Hashing forward does not scrub history -- that is 0.6a's
    job, and it is a second reason 0.6a is the higher-priority half.

- **0.6c -- At-rest encryption. A WORKSTREAM, NOT A FLAG. Schedule deliberately.**
  Adding `.encrypted()` to a populated column THROWS on every read:
  `FieldEncryption.java:93-102` refuses any stored value lacking the `zenc$`
  envelope, with no lenient mode and no fallback. There is NO framework
  backfill helper, and `MigrationBuilder` cannot do it (`execute(String sql)`
  is fire-and-forget; encryption needs the Java keyring + AES + DRY stringify).
  `.encrypted()` exists nowhere else in the tree except three Stack fields that
  were BORN encrypted, so there is zero precedent to copy. Per field the work
  is: widen the column to TEXT (MigrationBuilder only auto-selects TEXT for
  freshly CREATED tables, `:157,210,259`; envelope overhead is ~35 chars plus
  4/3 expansion, so a VARCHAR(255) overflows), then a Java backfill that runs
  BEFORE the field constant carries `.encrypted()`, then deploy ordering that
  guarantees the keyring file exists on the box before the new jar boots.
  - `SiteModel.SECURITY_REPORT_TOKEN` is the trap: SiteModel is Revisionable
    AND `ActivityPolicy.ALL`, and both hooks load the prior row before EVERY
    save (`ActivityLog.java:283`). An un-migrated row becomes unsaveable, not
    merely unreadable. Migrate it first or not at all.
  - HARD LIMIT: `Schema.refuseEncryptedJsonSubFields` (`Schema.java:151-163`)
    means site `api_keys`, git `webhook_secret`, dev `registration_token`,
    Proteus `access_key` and every `environment_variables` map CANNOT be
    encrypted while they live inside JSON SchemaFields. For them the answer is
    0.6a redaction plus, eventually, real columns or table-stored sub-schemas
    (the Phase 3 `instance_variables` shape).
  - Correct declaration for a secret column is `.secret().encrypted()` TOGETHER
    (the `StackModel.java:78-81` precedent); encryption alone does not hide a
    value from editors.
  - Operational: `FieldEncryption` auto-generates the keyring on first use, so
    it must be backed up WITH the database or encrypted rows are permanently
    unrecoverable (this is the cross-cutting control-plane backup item, and
    0.6c is blocked on it). Also `FieldEncryption.java:22-28` states there is NO
    AAD, so an attacker with DB write access can graft an envelope between rows
    or columns -- swapping one site's TLS private key for another's. Decide that
    explicitly for TLS/TSIG keys rather than inheriting it silently.

- **`environment_variables` is the largest plaintext-secret surface in
  hohenheim and the original list missed it entirely.** Present on every site
  type (`NodeSiteType:67-69`, `JavaSiteType:70-72`, `CommandSiteType:60-62`,
  `DockerSiteType:52-54`) plus `build_environment_variables`: no `.secret()`,
  unencryptable (JSON), copied by `cloneSite`, and written verbatim into both
  `zenit_activity` deltas and 50 retained `zenit_revisions` snapshots on every
  save. 0.6a is what MUST contain it; the fourth audit proved it does not yet.

- `architecture-stacks.md:24-27`'s "secrets are encrypted at rest" is true for
  stacks and misleading as a platform claim -- update it now to state that
  narrow scope, before the scheduled 0.6c workstream lands.
- WHY 0.6a is a HARD Phase 3 prerequisite and not merely hygiene: Phase 3 makes
  instances a GENERATED resource, and a generated RowResource gets revision +
  activity subpages by DEFAULT (`RowResource.java:189-198`). Today tenants are
  spared only by accident (`ManageSiteResource.subpages()` omits
  `frameworkSubpages()`). Do not rely on that accident.

(Two bullets that restated the 0.6a revision/delta fixes and the 0.6b api_keys
hashing as open work were deleted on 2026-07-29: both are covered above, and
0.6b has landed. Do not reintroduce a second copy of a finding in this section
-- the FOURTH-AUDIT STATUS block below is the current truth for 0.6.)

  FOURTH-AUDIT STATUS: 0.6a AND 0.6b REOPENED; 0.6c REMAINS UNSTARTED.
  - Legacy snapshots are ACTIVE input, not only copied-database exposure.
    `RevisionableBehaviour.restore` loads pre-redaction maps verbatim. A secret
    key already present is not grafted from the current row, so restoring an old
    revision can reactivate a historical API key, webhook token, registration
    token or security-report token (the API-key write hook hashes the restored
    plaintext and makes it valid again). Until history is scrubbed, legacy
    restore must redact against the CURRENT schema before applying and must
    never revive a historical credential. This changes open decision (1):
    "leave and document" is not safe while restore consumes the rows.
  - `environment_variables` and `build_environment_variables` are still ordinary
    `StringMapField`s. Recursive schema redaction therefore preserves every map
    value, exactly as the pre-fix code did. Make their whole value secret for
    derived surfaces, or replace them with typed rows that distinguish plain and
    secret values. Pin real SiteModel revisions and activity deltas containing a
    `DATABASE_PASSWORD`, not only a synthetic schema with declared secret fields.
  - A restored null carrier currently wins before secret grafting, and dynamic
    schemas resolve from the restored discriminator only. Null-to-secret and
    discriminator-change restores need conservative refusal tests.
  - Unknown historical nested keys are currently preserved. A removed/renamed
    nested secret field can therefore render or enter a new delta. Stored history
    needs schema-version metadata or a conservative unknown-key policy before
    "missing field over-redacts" is a true claim.
  - One-shot plaintext toasts are session data. With the supported datasource
    session store they persist base64-encoded in `auth_sessions.data`; secret
    disclosures need an ephemeral server-side channel or an explicit encrypted,
    short-lived representation.
  - Fast SHA-256 is safe for generated high-entropy tokens, not arbitrary weak
    operator-entered strings. New keys are minted only; legacy adopted values
    need a minimum-entropy transition policy. Digest markers must validate the
    complete lowercase-hex shape instead of accepting any `sha256:` prefix.
  - Install every secret-normalization hook before STARTHTTP. Dyndns hashing is
    currently installed after `ServerZenitRuntime.main()` has bound the server.
  0.6c is not a Phase 0 release blocker by itself, but no text may claim
  platform-wide at-rest encryption until its backfills, keyring backup/restore
  and field inventory are complete.

### 0.7 WebSocket admission and revalidation (boundaries 1, 4)

WS upgrades bypass `HttpConduit` (`ZenitHttpServer.java:91-101`): no admission
limiter, no rate limiter, no CSRF, and `WebSocketEndpoint` has no `rateLimit`
builder. Terminal opens cost a session lookup + grants query BEFORE the 1008
refusal, uncapped -- a cheap DoS and a pre-auth resource cost. And
`WebSocketRevalidator` never re-checks session validity, logout,
`UserModel.ENABLED`, or user existence; with no FK on grants, a socket outlives
a deleted user.

- Structural fix (mechanism, zenit core): a pre-upgrade admission + rate limit
  for WS handshakes (reuse the HTTP limiter), a `WebSocketEndpoint.Builder.rateLimit`,
  and a revalidation path that re-checks session/enabled/existence, not just
  declared permissions. `AuthWebSocketAuthenticator` stays session-cookie-only
  (correct).
- This is a Phase 0 blocker because Phase 3's console is another WS terminal
  and multiplies the surface.
- Gate: a test flooding handshakes is capped before the auth query; logging out
  drops a live terminal within the revalidation window.

  AUDIT STATUS: MECHANISM MOSTLY LANDED, GATE INCOMPLETE. Admission runs before
  authentication and Hohenheim opts into 15-second identity/grant revalidation.
  Add the exact integration journey: open a real Hohenheim terminal with a real
  auth session, log out/revoke/disable/delete the user in separate steps, and
  observe close 1008 within the interval. `ZenitHttpServer.onError` must stop the
  revalidator, close/abort the channel, and invoke handler cleanup; today its
  override only calls `handler.onError`, so process log listeners can survive a
  receive error. Decide default-on revalidation before any new gated WebSocket
  endpoint lands, and pin the query budget of one terminal revalidation tick.

### 0.8 Authorization correctness bugs (boundary 4/6)

- Null grant `value` reads as ALLOW (`RecordGrants.java:211-218,259-264`;
  `PermissionResolver.java:124,131`). SQL `DEFAULT true` masks it; Mongo and
  Couchbase have no DDL defaults, so a null-valued grant on those backends is a
  silent allow. Fix: null == deny in the check path, independent of backend
  DDL.
- `grantWithConflictRecovery` (`RecordGrants.java:99-110`) lets the losing
  thread of a concurrent re-grant overwrite the winner, so a deliberate DENY
  can be silently upgraded to ALLOW. Fix: the recovery path must re-read and
  refuse to weaken an existing deny (deny beats allow, always).

  AUDIT STATUS: NULL-DENY LANDED; CONCURRENT STICKY-DENY DID NOT. On a datasource
  without row locking, both callers can observe no row. Because the tuple has a
  deterministic primary key and `Model.save` is update-first, the losing allow
  can UPDATE the deny row successfully; no insert conflict reaches the recovery
  catch. Use a datasource-level atomic insert-if-absent/conditional update or a
  portable transaction/lock primitive; do not call update-first save for tuple
  creation. The test must barrier both workers AFTER the absent read and BEFORE
  the write, run allow last deliberately, and prove the final value is deny on
  every non-locking backend. Also resolve duplicate positive+negative
  `group.<slug>` rows -- in BOTH places, not either/or. Phase 0 owns the CHECK
  PATH: `PermissionResolver` expansion must let a negative membership beat a
  positive duplicate, testable today on a two-row fixture. Phase 1 owns the
  SCHEMA: the heal-then-unique migration that makes the duplicate
  unrepresentable. The check-path fix is the one that holds on Mongo and
  Couchbase, which enforce no constraint, so it is not retired when the
  migration lands.

### 0.9 Route-conflict takeover via toggleAction (boundary 4)

`SiteResource.updateRow:143-151` calls `refuseEnableRouteConflicts` on
disabled->enabled and explains why; `toggleAction:236-251` just flips and
saves; toggle is the ONLY row action a `/manage` tenant has. A delegated
operator enabling a staged site can seize another tenant's hostname
(disabled sites are exempt from the cross-site conflict check). Fix: toggle
enable runs the same conflict invariant as updateRow.

  AUDIT STATUS: FORM AND TOGGLE LANDED; REVISION RESTORE BYPASSES BOTH.
  `RESTORE_REVISION` is callable for every revisionable RowResource even when its
  revision subpage is hidden. `ManageSiteResource` marks `enabled` editable, and
  the endpoint calls `RevisionableBehaviour.restore -> model.save` directly.
  A tenant can restore a formerly enabled revision after another site has taken
  the hostname. Move the invariant to the SiteModel write pipeline (where every
  transition to enabled must pass), or add a framework restore-through-resource
  hook that all resource invariants share. Gate the exact `/manage/.../revision/
  {n}/restore` attack and assert the proxy route table still has one owner.

### STATUS: Phase 0 REOPENED after independent completion audit (2026-07-29)

The earlier statement that all nine items landed and a 21-project integration
pass proved the phase is WITHDRAWN. Targeted tests are green, but several assert
only the paths they know about and the retained zenit-dev journal does not carry
a reproducible post-hardening 21-project manifest tied to tested Git SHAs. A
green test is evidence for its exact counterfactual, never for an unenumerated
route or transition.

HISTORICAL THIRD-AUDIT NOTE (earlier on 2026-07-29): a hostile review of the whole
Phase 0 diff: six of seven surfaces held up under adversarial reading, but 0.3
was found HALF OPEN and is now genuinely fixed -- see the 0.3 STATUS block for
the chain, the fix, and the vacuous test that hid it. Treat the per-item
"LANDED" notes as claims to re-verify, not as evidence; this one was wrong.
Also corrected in that pass: two migration comments asserted behaviour the code
does not have (`37623ee` M043's residual-collision case is a silent rename, not
the promised loud constraint error, and the outcome depends on row id ordering;
`991cf6a` HohenheimDatabase claimed any relational engine works while M025 uses
SQLite `json_type` and M043 uses `||`, which is boolean OR on MySQL and would
silently write 0/1 into `name`).

What the reviews and the integration pass caught that the per-item tests did
NOT -- these are the lessons worth carrying into later phases:

- **The IPC fix initially downgraded a permanent wedge to a FLOOD wedge.** The
  pre-auth counter conflated pre-auth with connected, so an attached child held
  a slot for life. Fixed by releasing on auth + a bounded wrapper reconnect.
  Residual, stated honestly: a determined co-located tenant can still degrade
  reconnect LATENCY. The real boundary for hostile co-tenancy is the isolation
  tier (boundary 1), never a loopback port.
- **The `.ifNotExists()` retrofit poisoned the integrity baseline** with ~15
  self-inflicted checksum findings that would have blocked the very flip to
  `fail` it was working toward. Fixed by excluding `ifNotExists` from the
  STRUCTURAL signature (it changes idempotency, never resulting schema) plus a
  legacy-compat clause, because zenit-ai M003/M004 already carried the flag at
  apply time and a naive removal would have manufactured the same defect class
  on thoth. Consequence: the ~15 migrations revert to their ORIGINAL checksums,
  so NO hand-stamping is needed for the retrofit. The prepared procedure now
  covers only the 26 NULL rows and the 5 genuinely drifted migrations.
- **Redaction can DESTROY data, not just leak it.** Omitting secrets from
  snapshots wipes them on restore unless the current values are grafted back.
  The rule: graft only where correspondence is UNAMBIGUOUS (single-element
  lists, per-locale pairing); otherwise REFUSE and keep the current value. A
  positional graft over a reordered list would move a secret BETWEEN records,
  which is worse than losing it.
- **A sourceset move broke the browser.** 0.4 moved the site RecordSource to
  server-only; `WidgetInstance` DRY revival validates widget config sources
  against the BROWSER registry, so soft-nav to the dashboard died while hard
  loads worked. Root cause was a FRAMEWORK defect: 0.4 legitimately made the
  browser registry a SUBSET of the server's, but three browser paths still
  treated their partial registry as authority on existence -- and even past the
  crash, `Records.count` would have silently rendered 0. Now registry
  membership is server-authoritative and the browser delegates by token.
  spamservice had the identical latent bug and was rescued with no app change.
- **Two tests were passing VACUOUSLY** (one registered a source that was
  silently refused, so it re-asserted a leftover). Every implementing agent was
  required to revert its own fix and confirm the test failed; that is what
  caught them.

Known pre-existing and NOT from this arc: hohenheim
`AdminPagesTest.settingsPageRendersSavesResetsAndRefusesInvalidValues`
(settings array-editor reset). Next bisect point if chased: zenit-cms `c5dc2d7`.

Decisions still open for Jelle: (1) HOW to scrub historical plaintext secrets
from production `zenit_revisions`/`zenit_activity` -- leaving them untouched is
no longer an option while restore can reactivate them; (2) make WS revalidation
default-on for authorization-gated endpoints; (3) whether `/admin/**` GETs
should be interactive-only (an admin-scoped API key can read admin HTML today);
(13) live checksum stamping plus the `migration_integrity=fail` flip; (14) the
release posture of the first public tag, which decides how much of this phase
is on the critical path. Of these, only (1) and (13) block the 0.B rollout half;
(14) decides the SIZE of the public tag, not the content of 0.A.

### Phase 0 gate

Phase 0 has TWO halves and they close independently. Conflating them is what
let the last arc report a phase complete on the strength of code alone.

- **The CODE half (0.A) is the public-tag blocker.** It is fully achievable in
  the repos, with no production operation and no decision from Jelle. Every row
  in the table below belongs to it.
- **The ROLLOUT half (0.B) is the live-install blocker.** It touches the one
  running production database, needs a snapshot taken first, and is gated on
  open decisions 1 and 13. It does NOT block the public tag of a fresh install
  (a clean install already passes `migration_integrity=fail`); it blocks
  declaring the existing install healthy. Its requirements are listed after the
  table.

0.A completes only when ALL rows below have a named automated test, the
counterfactual has been demonstrated by reverting/defeating the fix, and the
checked-in red-team manifest records the command plus tested commit for each
repository. The manifest does not exist yet; create it in this repo. Assert
specific refusal codes and persisted state, never a bare status when more than
one middleware can answer it.

| Boundary | Required discriminating gate (0.A, code) |
| --- | --- |
| Stored XSS/CSP | Anonymous proxy request plants path+User-Agent payload; admin log renders text; no execution; scoped CSP on every claimed family and absent on public paths; bootstrap exactly once |
| Process IPC | Real one-shot child authenticates first, survives initial refusal and eight stalls, preserves messages across reconnect, reserved env cannot be replaced, workload identity policy refuses same-identity tenants |
| API-key authority | Every account/admin mutation receives anonymous-cookie+token+key attack, exact refusal code, no target mutation, interactive positive; effective-HTTPS cookie matrix; csrfExempt credential-mode test |
| RecordSource | Every production source query/item/vocabulary/buckets as unauthorized viewer; direct project/item/sort/bucket/title bypass counterfactuals; browser editor reveals no denied source or variable |
| Published shell | Published plumage artifact contains no self-registering shell/process endpoint; Hohenheim route remains uniquely owned |
| Secrets | Real SiteModel env/API/webhook values absent from every new revision/delta; legacy restore cannot reactivate; null/type/list/locale restore matrix; one-shot disclosure absent from durable session data |
| WebSocket | Flood stops before auth query; real session logout/disable/delete closes a live terminal 1008; receive error releases handler listener and revalidation job |
| Grants | NULL denies on all backends; deterministic post-read race leaves deny on every locking and non-locking backend; a negative `group.<slug>` row beats a positive DUPLICATE of the same tuple in the resolver check path |
| Route ownership | Form, toggle, row action, revision restore and every future enabled transition run one model-level invariant; conflicting restore leaves one route owner |

DUPLICATE-GRANT SPLIT (do not merge these): Phase 0 owns the CHECK PATH only --
`PermissionResolver` must let a negative membership row beat a positive
duplicate, which is a code fix testable on a fixture with both rows present.
Phase 1 owns the SCHEMA -- the heal-then-unique migration over
`(subject_type, subject_id, permission)` that makes duplicates unrepresentable.
Phase 0 must not wait on that migration, and Phase 1 must not assume the check
path stops mattering once uniqueness exists (Mongo/Couchbase do not enforce it).

0.B -- rollout requirements against the live install (blocked on decisions 1
and 13, NOT on the public tag):

- Snapshot the live DB and the keyring off-host FIRST (the file named by
  `database.encryption.key_file`, today `settings/field-encryption.keys`).
- Capture one real boot's findings at `warn` and remediate against that list,
  never an inferred one; verify the live schema per table before blessing.
- Acknowledge the three retired versions; repair the 26 NULL checksums and the
  5 genuinely drifted migrations (M003-M006, M026).
- Flip `database.migration_integrity` to `fail` in the SAME change that stamps.
- Execute the chosen historical-secret remediation (decision 1). Until it runs,
  the 0.A redact-on-restore fix is what keeps legacy rows from reactivating a
  credential -- that fix is 0.A, not 0.B, and does not wait for this.

Phase 0.A remains the precondition for any public tag. The already-landed CSP,
published-shell removal, API-key mutation guard, registration rules, hashing,
WebSocket admission and null-deny behavior are foundations to keep, not reasons
to weaken the reopened gates.

---

## Phase 1 -- Record-capability auth foundations (framework)

The mechanism the whole instance arc depends on. No new product surface; the
wired consumer is hohenheim's EXISTING site access feature, migrated onto the
new mechanisms. This is where the record-grant system becomes a real,
composable, public-grade authorization primitive.

zenit core (`common/security`):

- Promote `PermissionChecker` and the whole decision path to TRI-STATE
  (allow / deny / abstain). Precedent exists: `WildcardPermissions.decide`
  already returns nullable Boolean, and zenit-auth's `PermissionResolver.decide`
  is already tri-state while `GrantAuthorizationPolicy` throws the third state
  away. Today hohenheim recovers the third state by MUTATING the process-global
  checker from a Panel CONSTRUCTOR (`ManagePanel.java:38-67`) and reaching
  around the SPI. That hack is deleted here.
- `AccessContext` gains a record notion: `hasCapability(model, recordId, capability)`
  (it has none today, 93 lines). Backed by a new `RecordCapabilityChecker` SPI
  (the CsrfTokenStore pattern): core interface, deny-all default, zenit-auth
  installs the RecordGrants-backed impl. `WebSocketAuthenticator` gets the
  matching default method.
- `KnownCapabilities`: model-Identifier-keyed registry of capability entries
  with Microcopy descriptions (the KnownPermissions emergent contract: absence
  never denies). This is what UIs enumerate. Capabilities are NOT permissions
  and never enter KnownPermissions. Each entry also declares its sensitivity
  class, whether a non-admin may delegate it, and whether record ownership
  implies it. That metadata is authorization input, not decorative UI copy:
  `exec` and `image_any` are non-delegable and NOT owner-implied by default,
  while ordinary/elevated tenant capabilities may be owner-implied explicitly.
- COMPOSITION RULES live in the SPI, declared not prose. Per model: a gate
  permission (global deny kills all grants; any grant satisfies the gate -- the
  ManagePanel pattern, now named), an admin permission (bypasses grants), an
  optional type-level permission ("all records of this model"), and an optional
  owner-field declaration (owner lives ON the record, never as grant rows;
  ownership implies only capabilities whose definitions opt in, never the
  entire vocabulary).
- Precedence is a TRUTH TABLE with a test, NOT the old prose "chain". The old
  chain (lines 83-85 of the previous plan) mixed subject-expansion order with
  decision precedence, put admin bypass last, and referenced an "everyone"
  subject that does not exist (`Subject` is user|group only). Replace it with:
  for a given (principal, model, record, capability), evaluate admin bypass
  first (allow), then an EXPLICIT gate denial (deny before every grant), then
  type-level permission (allow), then owner match ONLY when this capability is
  owner-implied (allow), then negative grants (deny), then positive grants via
  expanded subjects (allow), else deny. Gate allow/abstain alone never grants a
  record capability; a positive record grant may satisfy an absent gate, but it
  can never override an explicit gate denial. Pin every row with a test,
  including explicit-gate-deny + positive-grant and owner + admin-only-capability.
- Record-aware overloads where the record is ALREADY loaded at the enforcement
  point (so they cost a parameter, not a load): `Resource.writePermission/
  updatePermission/deletePermission`, `RecordScopedPage.
  requiredPermission()`/`visibleFor(record, context)`, `FieldAccess.decide`
  (`dispatchRow:1644-1661` already loads the row first). `createPermission`
  remains type-level because no record exists; quotas/placement own create.

zenit-auth (RecordGrants hardening -- builds on the Phase 0 correctness fixes):

- Grant lifecycle: `revokeAllForRecord(model, id)` + `revokeAllForSubject(type,
  id)`; wire record cleanup through `GlobalModelHooks.addAfterRemoveHook`
  (which DOES carry criteria + count -- the audit claim that it cannot supply
  ids is wrong; before-capture/after-consume is the documented pattern
  `ActivityLog.java:107-108,327-374` already uses). The real gap: soft-deletable
  models fire NO remove hooks (`Model.java:619-627`) and hohenheim soft-deletes
  sites by hand-stamping (`SiteResource.java:215-225`), so grant cleanup on
  soft delete needs an explicit hook, not the remove hook. Without cleanup,
  hard-delete + id reuse = privilege resurrection.
- API-key scope coherence (completes Phase 0.3): record capabilities in scopes
  ride a distinguishing prefix so a key's reach is explicit and cannot collide
  with permission scopes. GRAMMAR NOTE: model Identifiers themselves contain
  `:`, so `cap:<model>:<name>` is ambiguous -- pick a separator that appears in
  neither part (e.g. `cap:<model>#<name>`, capability names are dotted words,
  never `#`) and pin it with a parse test before any key is minted. The two matchers
  (PermissionResolver's specificity tie-break vs WildcardPermissions'
  exact-node-first) diverge in principle but never on the same string set
  today; document the divergence with a pinning test rather than force a risky
  unification, and note the stores that CAN hold false through WildcardPermissions
  (`ProteusPermissions`, `McpApiKeys`).
  A key may receive a capability scope only when its interactive creator
  currently holds that capability and the capability is delegable (admin bypass
  excepted). Parsing a scope is not authorization to mint it. Key creation pins
  the same `access.manage`-cannot-mint-`exec` negative journey as the matrix.
- New migration (NOT an edit to M006 -- `MigrationChecksum` detects post-apply
  edits and an applied migration never re-runs): covering index
  `(subject_type, subject_id, model, capability)`, columns `granted_by` and
  nullable `expires_at`. Expiry is enforced in the check path; a cluster-claimed
  `TaskService` task prunes in Phase 1, but cleanup is not part of authorization
  correctness. Note: `(subject_type, subject_id)` and
  `(model, record_id)` indexes already exist (M006), so the finding is
  "not covering", not "no index". A cascade FK is NOT available (the subject
  column is polymorphic; siblings `auth_grants` also lack one; FKs are
  unenforced on Mongo/Couchbase) -- lifecycle hooks are the only cleanup path.
- The same new migration heals duplicate ordinary `auth_grants` tuples with
  DENY winning, then adds uniqueness over `(subject_type, subject_id,
  permission)`. This is the SCHEMA half of the duplicate-grant split; Phase 0
  already fixed the resolver check path so a negative membership beats a
  positive duplicate. Keep both: uniqueness is unenforced on Mongo and
  Couchbase, so the check-path rule is the one that holds everywhere and its
  test stays. The write service becomes the only mutation path.
- Batch the subject walk (expand once, one `SUBJECT_ID.in(...)` per subject
  type) and cache the expansion on the conduit for the request. Instrument the
  three production `managedSiteIds` callers and pin a query budget before and
  after; do not quote the disproven new-Model/cache explanation below. This
  matters more once every instance list page runs the same path. Revocation
  stays next-request-effective.

zenit-auth + plumage (the generic UI, via the bridge -- NOT in zenit-cms):

- CORRECTION to the old plan: the generic record-access page cannot live in
  zenit-cms, because zenit-cms must not depend on zenit-auth (verified: zero
  references). It consumes RecordGrants, so it belongs in zenit-auth,
  contributed through the `@ZenitAutoLoad(whenPresent=...)` bridge
  (`AuthCmsBridge` pattern, `zenit-auth/build.gradle:149-157`). The matrix
  EDITOR component (subjects x capabilities) lands in plumage; the page
  mechanism lands in zenit-auth and attaches to any Resource via the subpages
  seam. Features: add/remove, `granted_by` provenance, expiry, preset support
  (presets expand to concrete rows at grant time, never stored as a name), and
  a "who can touch this record" cross-tier view.
- The matrix is not authority by itself. EVERY cell mutation is re-checked on
  the server. A non-admin may add, remove or change a grant only when the target
  capability is declared delegable AND the acting principal currently holds
  that capability on the record; `access.manage` alone never widens authority.
  Removing a deny is constrained by the same rule because it can reveal a
  positive grant underneath. Admin bypass may manage every registered
  capability. Unknown capabilities are refused, never accepted as free text.
  Presets are filtered through the identical per-capability check before rows
  are written. Pin attempts by an `access.manage`-only principal to grant or
  un-deny `exec` and `image_any`.
- Record-scoped POST: `RecordScopedPage` is GET-only by policy, which is why
  hohenheim's grant editor posts to hand-written endpoints with a hardcoded
  redirect. The missing piece is small: `RowAction.Invoke` is ALREADY a generic
  per-record POST carrying the full form body, and `RowAction.visibleFor(row,
  AccessContext)` is already enforced as a 404 on invoke. Add one POST route +
  a `submit` default method rather than a whole new mechanism.

hohenheim (consumer proof):

- Sites migrate onto the new mechanisms: KnownCapabilities registers the site
  vocabulary ("manage"), HohenheimAccess collapses onto the core SPI (gate =
  `hohenheim.manage.access`, admin = `hohenheim.admin.access`, type-level =
  new `hohenheim.sites.manage_all`), SiteAccessPage is DELETED for the generic
  page, ManagePanel's constructor hack and `impossible()` move onto the core
  helpers (no side-effecting Panel constructor; `Model.matchNone()`).
- Post-login routing for delegated users (finding: `/` -> `/admin` -> bare 403
  for a manage-only principal): a manage-only principal lands on `/manage`, not
  a 403. `PanelNav.mayAccess` must HIDE (not show-empty) peers a principal has
  no scope for, so an instance-only user does not see empty Sites/Domains nav.
- Gate: all existing site-access tests pass; a delegated user logs in and lands
  on a working /manage with only the peers they can touch.

RECON CORRECTIONS (verified 2026-07-28, against POST-Phase-0 code):

- **RISKIEST ITEM, with the mitigation that makes it safe.** Promoting
  `PermissionChecker` to tri-state is the one change that can cause a SILENT
  authorization regression. It is a process-global mutable singleton with a
  `@FunctionalInterface` contract, and ~30 call sites across zenit, zenit-cms,
  zenit-forms, zenit-media, zenit-widget, QQ and proteus build it as a two-arg
  lambda. No existing test would catch abstain-meaning-something-other-than-deny,
  because every current test installs a TOTAL (never-abstaining) checker.
  Required shape: keep `hasPermission` as the boolean method so every lambda
  still compiles, add `decide` as a DEFAULT returning `hasPermission(...) ?
  TRUE : null` -- never `FALSE` from a boolean-only impl, since that is the only
  encoding that cannot silently convert a legacy deny into an abstain. Pin the
  precedence truth table with a test BEFORE any consumer reads `decide`.
- **`AuthModels` refactor is WASTED WORK -- do not do it.** The plan blamed
  new-Model-per-call for defeating the query cache. Wrong: `Model.queryCache()`
  resolves through the process-global `Caches` registry BY MODEL ID
  (`Model.java:292-295`, `Caches.java:41-45`), so every instance shares one
  cache. The cache is dead because `model_query_cache_duration_ms` DEFAULTS TO 0
  (`DataSettings.java:18-23`) and neither grant model overrides it. Only batching
  helps. Also `managedSiteIds` has 3 production call sites, not 5 -- instrument
  before quoting a per-render number.
- **There is NO subpage registry. This is unbudgeted Phase 1 work.**
  `subpages()` is an override list scanned linearly (`findSubpage`,
  `ResourcePageEndpoints.java:1180`); grep finds no registry of any kind. So
  "attaches to any Resource via the subpages seam" is a mechanism to BUILD
  (a model-Identifier-keyed registry consulted by `RowResource.frameworkSubpages()`),
  not one to use. It blocks the generic access page.
- **The matrix is a NEW plumage component, not a variant.**
  `pl-permissions-editor` is a ONE-dimensional list of free-text permission
  strings with a single allow/deny select. A subjects x capabilities matrix needs
  an enumerated capability axis, a subject picker, and allow/deny/unset cells.
  Reusable: the `PermissionExtraColumn` open-column seam and the datalist
  suggestion pattern. Not the layout -- do not bend it.
- **Grant cleanup on soft delete is worse than stated.** `SiteModel` has NO
  `SoftDeleteBehaviour` at all (`SiteModel.java:112` adds only Revisionable);
  the soft delete is hand-rolled in `SiteResource.deleteRow:233-241` via
  `save()`. So it fires WRITE hooks, never remove hooks -- an after-remove hook
  is useless there. Needs an explicit call in `deleteRow` or a save-hook that
  detects the `deleted_at` transition. GOOD NEWS: the audit claim that
  `addAfterRemoveHook` cannot supply ids is WRONG -- `RemoveFromDatasource`
  carries the query context and count, and the same instance is passed to
  before- and after-hooks, so `ActivityLog.java:327-377`'s before-capture /
  after-consume pattern is a straight copy for HARD deletes.
- **`createPermission` CANNOT be record-aware** -- it is checked at
  `ResourcePageEndpoints.java:566,580` with no record in existence. Drop it from
  the record-aware overload list; create authority is Phase 3's quota problem.
  The claim holds for update/delete/subpage/row-action/field-access, all of
  which run inside `dispatchRow` with the row already loaded.
- **zenit-auth has no server-side zenit-cms compile path.** `build.gradle:153`
  is `commonCompileOnly` only, and zenit-auth has never registered a CMS
  Resource or page -- `AuthCmsBridge` is a slot contribution. Add
  `serverCompileOnly zenit-cms-common`. This is new ground, not a copy.
- `RecordScopedPage.visibleFor(T)` today takes the record but NOT an
  `AccessContext`; the record-aware overload must add it.
- M006 has THREE indexes (`M006:32-34`, incl. `(model, capability)`), not two.
  "Not covering" still holds.
- `ManagePanel`'s constructor hack has an ordering BUG worth naming when it is
  deleted: `ZenitAuth.configureDataBoundServices` (`ZenitAuth.java:82`)
  overwrites the wrapper unconditionally, so any test rebind or re-init silently
  un-installs it.
- Phase 0.3's mutation escalation is landed. Phase 0.8's NULL-deny half is
  landed, but its non-locking sticky-deny race is reopened and must close before
  Phase 1 consumes grants as a public authorization primitive.

Phase 0 work unrelated to grants can interleave with Phase 1, but the Phase 0.8
race is a hard dependency for Phase 1. The completed record-capability SPI is a
hard dependency for Phase 3.

Phase gate: the full admin/gate/type/owner/negative/positive/abstain truth table
passes before and after the tri-state migration; the deterministic concurrent
deny/allow race and duplicate-group migration pass on every supported datasource;
an ordinary delegate can grant only a capability they hold and may delegate,
while `access.manage` cannot mint or un-deny `exec` through the matrix, API key or
preset; soft and hard deletion remove grants; expiry and revocation take effect
on the next request; query count is bounded; and a localized browser journey
uses the generic access page on both a site and a second fixture model without
leaking an unauthorized peer or record.

---

## Phase 2 -- Install roles (hohenheim + one zenit core change)

- Settings group `roles.*`: proxy, dns, firewall, stacks, processes, databases
  -- each a boolean with restartRequired. These realize the two install kinds
  from the doctrine (compute node vs control-plane appliance). The `instances`
  and `game` role flags land WITH their consumers (Phases 3 and 5) per the
  no-unwired rule -- Phase 2 only builds the gating mechanism they plug into.
- BLOCKER the old plan missed (zenit core change, required BEFORE roles work):
  `TaskService.reconcileDeclaredSchedules:134-201` DELETES schedule rows whose
  checksum is absent from the BOOTING node's catalog. A role-gated node that
  omits a task therefore disables that task CLUSTER-WIDE. Roles cannot be done
  by trimming the catalog per node. Fix in core: scoped reconciliation -- a
  node reconciles only the schedules it declares/owns and never deletes another
  node's, or schedules carry an owning-node/role scope. This is a genuine core
  design point; resolve it before shipping roles.
- Boot ordering fixes (`ServerMain.java:46-123`): `SiteTypes.boot()` runs
  before settings load (load settings FIRST so a role can gate a boot step),
  and handlers/panels register AFTER `init().join()`, i.e. after the HTTP
  server accepts requests (move wiring into a discovered `ZenitModule.init()`).
  Note `Panel.peers()` is memoized, so roles are boot-time-immutable -- a role
  change is a restart, which matches restartRequired.
- Boot becomes role-conditional: no Docker probe / port-53 bind / nftables
  wiring for disabled roles; attention collectors and scheduled tasks gate on
  their role; admin nav hides panels of absent roles.
- Gate: a DNS-only appliance install boots green with no Docker socket, no
  proxy ports, no nftables, its admin shows only DNS/domains/settings, and it
  does NOT delete other nodes' schedule rows.

### Phase 2 parallel gate -- control-plane recovery and 0.6c

This workstream runs after Phase 0's no-new-leaks closure and before Phase 3
stores fleet credentials. It is not part of install-role mechanics, but it is a
Phase 3 dependency and therefore has to be scheduled here rather than left as
an unowned cross-cutting note.

- First implement and exercise an off-host, atomic backup/restore of the
  Hohenheim database plus `database.encryption.key_file`. Restoring only one half
  must refuse loudly. Record backup generation/checksums and verify a fresh
  controller can decrypt all encrypted fixture columns.
- Check in the recoverable-secret inventory: certificate private keys, DNSSEC/
  TSIG material, database passwords, notification URLs, security-report token,
  auth TOTP secrets, provider/API credentials and every other concrete field.
  For each, record hash vs `.secret().encrypted()` vs external secret reference,
  migration order, and AAD/tamper posture. JSON environment maps are NOT silently
  included; Phase 0 contains their derived surfaces and typed secret rows replace
  them when the owning product tier migrates.
- Add a framework Java backfill mechanism that widens columns first, encrypts
  under the loaded keyring before declarations flip, checkpoints progress and is
  resumable/idempotent. Deployment ordering is tested on populated data; there
  is no plaintext fallback after the field declaration changes.
- Harden keyring operations: reject/fix permissive existing-file modes, fsync the
  file and parent directory, prove concurrent first creation cannot replace the
  winner, and provide rotate + resumable re-encrypt + retire-old-key operations.
  Decide whether a versioned AAD-bound envelope is required for fields where DB
  grafting is in scope; document accepted no-AAD fields explicitly.

Gate: on a populated pre-encryption fixture, back up DB+keyring, migrate every
inventoried field, prove raw storage contains no plaintext, rotate and re-encrypt,
restore onto a fresh controller, and read every value. Missing keyring, permissive
permissions, interrupted backfill, concurrent key creation, tampered envelope and
DB-only restore all fail loudly without making records unsaveable.

### Phase 2 parallel gate -- tenant authority over domains, DNS and certificates

ADDED 2026-07-29. The "combined product boundary" section at the top of this
document promises that a delegated tenant cannot mutate another tenant's object
in ANY tier, and that a grant over an instance never implies a DNS zone or
domain. NO phase delivered that for the DNS tier: zones, records and
certificates are admin-only sources today, Phase 5 scopes only game-domain
mappings, and Phase 7 scopes only PaaS-generated records. A promise with no
owning phase is how the last arc produced a false completion claim, so it gets
an owner here.

Consumes the Phase 1 record-capability SPI; must land before ANY tenant-facing
cross-tier link (Phase 5 game-domain mapping, Phase 7 project domains), which is
why it sits beside Phase 2 rather than inside a phase that may be interleaved
past. It is small if Phase 1 is done right: it is a consumer, not a mechanism.

- Decide per model, and record it: `DnsZoneModel`, `DnsRecordModel`,
  `SiteDomainModel` and the certificate records either get a capability
  vocabulary in `KnownCapabilities` (with owner-implied and delegable flags),
  or they are recorded as a permanent ADMIN-ONLY non-goal in the combined
  product boundary section. Silence is not an option; either answer is.
- Domain claim authority: who may bind a hostname, and what stops tenant B from
  claiming a name tenant A already serves or once served. This is the same
  invariant as 0.9 route ownership, one tier up. Reuse the model-level
  invariant 0.9 establishes; do not hand-roll a second check.
- Generated records (ACME challenge records, Velocity forced-hosts, SRV/A rows
  materialized from a mapping) carry owner + source metadata and reconcile or
  delete ONLY their own output. A generated row is never adopted by whoever
  happens to hold the zone next.
- Certificate issuance is authority over the domain, not over the zone: a
  tenant who may serve `a.example.com` must not thereby control
  `example.com`'s DNSSEC or TSIG material (both are recoverable secrets in the
  Phase 2 inventory above).

Gate: a delegated tenant with authority over one domain creates, edits and
deletes exactly its own records, is 404 on every other zone/record/certificate
through every RecordSource, subpage and API path, cannot claim a hostname owned
or previously owned by another tenant, cannot reach zone-level DNSSEC/TSIG, and
a revoked grant leaves the generated rows correctly attributed and cleanable.
If the decision above lands as admin-only, the gate instead proves no tenant
path reaches the DNS tier at all, and the boundary section is amended to say so.

---

## Phase 3 -- Instance tier core: Docker driver, ports, console, quotas

### Mandatory admission prerequisites

These are Phase 3 entry criteria, not later polish. The hostile-workload threat
model is false without them, even if Docker itself starts correctly.

FOUR OPEN DECISIONS GATE THIS PHASE'S ENTRY and they are not independent of the
work below -- each one changes a table shape or an enforcement point, so
answering them after the code exists means rewriting it: decision 8 (shared-host
posture -- determines whether host posture classes have a container-only option
at all), decision 7 (tenancy shape -- fixes the ownership key in EVERY new table
here), decision 2 (WS revalidation default -- must be settled before the console
endpoint is registered), and decision 5 (stack ownership adapter -- decides what
"canonical workload ownership" actually migrates). Decision 9 (`record_schedules`
shape) is confirmable during the phase. Do not start Phase 3 with 7 or 8 open.

- **Host posture and workload trust are data.** Every compute host declares one
  enforceable posture: trusted-only; dedicated-to-one-tenant; shared-container
  with explicit operator risk acknowledgement; or VM-isolated multi-tenant.
  Every workload declares trusted/operator-owned vs hostile-tenant. Placement
  refuses a hostile workload when the host posture cannot satisfy it. The
  acknowledgement records actor, timestamp and warning version; a boolean hidden
  in settings is not sufficient. Tenant anti-affinity/dedication is enforced by
  the allocator, not operator memory. Host preflight verifies userns remap,
  seccomp/AppArmor, cgroup version/controllers, daemon reachability and required
  nftables support before the host accepts tenant placement.
- **Host lifecycle is an owned product flow.** Enrollment uses a short-lived
  bootstrap or an operator-pinned credential, never a pasted permanent root
  secret with implicit trust. Host records expose capabilities/version, health,
  credential rotation, cordon, drain and remove. Removal refuses while owned
  resources or reservations remain; drain uses the durable transfer/stop policy
  supported by that runtime. Controller/runtime-endpoint upgrades have an
  explicit protocol compatibility window and rollback path. Every enrollment,
  trust change, credential rotation and removal is accountable.
- **Network isolation is enforcement, not port bookkeeping.** Define the minimal
  Phase 3 network model before the first hostile container: per-tenant or
  per-instance private networks; default-deny tenant-to-tenant traffic; explicit
  ingress bound to persistent port allocations; egress policy; control-plane,
  host-service and cloud-metadata deny ranges; anti-spoofing; IPv4 AND IPv6; DNS
  behavior; and bandwidth/connection limits where the runtime supports them.
  Hohenheim materializes the policy into Docker/Incus networking plus nftables
  and reconciles drift. The public-backend-is-unreachable gate in Phase 5 must
  follow from this mechanism, not an ad-hoc Minecraft rule.
- **One fenced controller owns each host mutation.** A process-local worker lane
  is not enough once role-separated/control-plane installs share a database.
  Host-controller leases carry monotonically increasing fencing tokens; every
  runtime operation records operation id, desired generation and fence. A stale
  controller cannot create/start/destroy after losing its lease. Operations are
  idempotent and resumable after crash, with explicit retry/cancel/dead-letter
  states and orphan quarantine when live truth is ambiguous.
- **Quota is a transactional reservation system.** Count/cpu/memory/disk/port
  reservations are claimed atomically before create and adjusted atomically on
  resize, restore, clone and destroy. Concurrent creates cannot both spend the
  same headroom. Runtime limits also cover pids, logs and ephemeral disk; later
  snapshot/backup/file/image quotas plug into the same ledger when their
  consumers land. Declared allocation and observed usage are distinct.
- **Control-plane recovery precedes encrypted instance secrets.** Back up the
  Hohenheim database AND field-encryption keyring atomically to an off-host
  target, document restore on a fresh controller, and exercise it before Phase 3
  writes the first encrypted instance variable. Phase 4 may expand this system
  for workload backups; it may not be the first time the controller is recoverable.
- **Canonical workload ownership is decided.** Docker sites, managed processes,
  managed databases, stacks and instances may remain different product records,
  but every runtime resource has exactly one owner tuple and one shared policy
  path for ports, host posture, quotas, labels, destructive operations and
  reconciliation. Document which existing records migrate, adapt, or remain
  admin-only legacy. Two independent authorities may not allocate the same port
  or manage the same container.

Prerequisite gate: enroll a fresh compute host, pin its identity, rotate its
credential and refuse its removal while it owns a fixture; two hostile tenant
fixtures are refused co-location on an unacknowledged container host; tenant A
cannot reach tenant B, the host, control plane or metadata addresses over IPv4
or IPv6; only allocated ingress is open; two concurrent creates cannot overspend
one remaining quota slot; a stale fenced controller is refused; a crash at every
create/destroy boundary reconciles to one owned resource; and a DB+keyring
restore on a fresh controller decrypts a fixture secret. Use a real daemon for
the network/runtime half.

The fork. New model `InstanceModel` (instances): name, kind (`container` now;
`vm` reserved), server_id (host), runtime (`docker` now), image/source config
(SchemaField by runtime type -- the site_type pattern), resource limits,
restart policy, status, owner principal id, workload trust class, desired
generation and current operation id/fence. Soft delete (with the grant-cleanup
hook from Phase 1). Localized: labels/descriptions from microcopy; instance
names are user data (not localized).

Design gaps the old Phase 2 left open, now resolved for a public product:

- **Create authority and quota (the whole abuse story).** Record capabilities
  govern records that already exist; NOBODY says who may CREATE an instance, on
  which host, or how many. For a public product this is mandatory, not
  deferrable. Introduce:
  - a `hohenheim.instances.create` permission (federatable, who may create at
    all), plus the transactional per-owner QUOTA/reservation mechanism above.
    Quota lives on a per-owner record (simplest: an
    `instance_quota` row keyed by principal, admin-editable). This is the
    minimum tenancy; the full aggregate project/tenant model with hierarchical
    quotas can stay later (see cross-cutting), but a public host cannot ship
    without a create gate and a per-owner cap.
  - Placement authority: which hosts a creator may place on. A creator without
    an explicit host grant cannot pick an arbitrary server.
- **Capabilities (KnownCapabilities registers the instance vocabulary),
  enforced by an InstanceAccess funnel on the core SPI**, split by the threat
  model's sensitivity classes:
  - ordinary in Phase 3: `view`, `power`, `console` (stdin/stdout of the
    instance's own primary process).
  - elevated in Phase 3: `config`, `destroy` (the old plan offered delete via
    the generated resource with no matching capability -- fixed: `destroy` is
    its own elevated capability), and
    `access.manage` (open the record's access page and edit its grants; the
    generic Phase 1 access page gates on it -- owner and admin hold it
    implicitly, nobody else by default).
  - admin/type-level, NOT default tenant grants: `exec` (arbitrary command in
    the container -- root-in-container, host-escape amplifier), and
    `image_any` (pull an arbitrary, non-template image -- equivalent to exec).
  `snapshots`/`backups` register with Phase 4, and `files.read`/`files.write`
  with Phase 6, when their actions exist. This preserves the no-unwired rule.
  Per-capability action gating uses the zenit-cms permission seams (Phase 1
  record-aware overloads), not hand-rolled ifs.
- **Instances join /manage as a GENERATED grant-scoped resource** (accessCriteria
  = `RecordGrants.recordIds -> ID.in(...)`, the `ManagePanel.siteScope` pattern;
  `AccessDecision.ALL/NONE/criteria` reused for list/count/load, out-of-scope
  ids as 404). The admin panel gets the full resource. The /manage resource is
  a SAFE PROJECTION (the `ManageSiteResource` precedent): no server id, no
  socket/daemon addresses, no host filesystem paths, no raw runtime errors, no
  other tenants' usage -- field-level, not just the wire path. CRITICAL: because it is
  generated, it inherits revision + activity subpages by DEFAULT -- so the
  Phase 0.6 secret-redaction fixes are a hard prerequisite here, and the
  resource must NOT expose a whole-Row wire path (none exists today; keep it
  that way). An "Overview" home instead of the edit form as record home needs a
  new mechanism (`RecordTabs.java:34-62` hardcodes Edit first) -- add it here.
- **Env / secrets: table-backed, not a free map.** The free env map cannot hold
  secrets because zenit REFUSES `.encrypted()` inside JSON sub-schemas by design
  (`Schema.java:143-163`, a loud refusal). Instance variables are therefore
  table-backed rows (`instance_variables`: instance_id, key, kind, plain_value,
  secret_value). `secret_value` is a statically declared `.secret().encrypted()`
  column; the write service enforces exactly one value carrier according to
  `kind`, so a runtime flag never pretends to change a field declaration. Plain
  values may remain visible/revisionable; secret values never enter revision,
  activity, logs or durable one-shot session data. Free plain env can stay a map;
  secret env cannot.

Driver and infrastructure:

- `InstanceRuntime` driver seam (server): create/start/stop/kill/destroy,
  status, stats, console attach, exec, logs-follow. First driver wraps
  DockerClient, hardened per the threat model (cap-drop, no-new-privileges,
  seccomp, user-ns remap for tenant-owned, no host bind mounts). DockerClient
  gains the missing primitives: `/containers/{id}/stats`, follow-logs streaming,
  attach (stdin/TTY), TTY exec. Note the current `DockerTransport` buffers every
  response to EOF, re-encodes through a String (3-4x resident), and opens a new
  connection per call -- streaming primitives require fixing that, not layering
  on it. SSH host-key PINNING (a fingerprint column on the server record;
  `accept-new` is a live gap). Containers carry hohenheim instance labels (the
  stack ownership pattern). The driver consumes a precomputed isolation/network
  policy; it never invents a permissive default when host preflight or policy
  materialization fails.
- **PERSISTENT, single-authority port allocation** (replaces the in-memory
  `PortAllocator`, lost on restart, TCP-only, racy -- and note the container
  tier currently uses a SECOND, unrelated authority: `ManagedDatabase` reads
  back Docker's published port, so the two can collide). One table
  `port_allocations` (server_id, ip nullable, port, protocol, owner model+record,
  note), claim/release API, OS-probe on claim, remote-host aware. All existing
  consumers migrate (managed processes, docker sites, stacks validation, and
  the database tier's published-port readback). UDP is a protocol value; games
  need allocation bookkeeping, not proxying.
- **Console vs exec are DISTINCT handlers.** Console is a WS terminal over the
  driver's ATTACH to the instance's primary process (stdin to a game server),
  grant-checked `console`, over the hardened WS admission/revalidation from
  Phase 0.7. Exec is a separate WS/handler requiring `exec`, admin/type-level.
  Both speak the existing pl-terminal wire contract (the ProcessTerminalHandler
  pattern generalized). Per-record WS handshake gating: `WebSocketEndpoint.Builder`
  supports only static login+dotted permissions today, but `WebSocketHandshake`
  already carries route params and the upgrade path can return a pre-upgrade
  403; add a declarative per-record handshake gate so authorization happens
  BEFORE resource acquisition, not after (today hohenheim completes the upgrade
  and closes 1008 in onOpen).
- **Per-record scheduling reuses the existing cluster-safe claim protocol.**
  Zenit already has `SystemTaskModel`, `CronExpression`, and atomic cluster
  claiming in `TaskService`. The old plan's "ONE global sweeper ScheduledTask"
  MUST run through that claim protocol or it loses multi-process safety that
  already exists. A `record_schedules` table (model, record_id, cron, TIMEZONE
  -- new, "restart nightly at 4am" needs it, nothing has one today, action
  token, payload, enabled); the executor is a TaskService task using the
  existing claim, not a naked loop. Action vocabulary registered per model with
  per-action capability requirements (scheduling an action requires the
  capability the action itself needs -- the Pterodactyl lesson). Decide at
  build time whether this is a new table or a facet of `SystemTaskModel`
  (leaning new table: record schedules are user data, system tasks are
  code-declared).
- Stacks remain separate product records (a stack is a multi-service deployment
  unit; an instance is a single runtime unit with delegation), but they migrate
  onto the shared runtime-resource ownership, ports, host posture and operation
  fencing mechanisms defined above. "Separate" must not mean a second authority.
  Fix in passing: `StackRuntime`'s
  `stack_health` alert string is not in `NotificationEvents.ALL` and is
  therefore unroutable -- register it so admins can route it.
- Destructive-operation safety (the tier multiplies today's database-tier bugs:
  `ManagedDatabase.status` conflates absent with unreachable, `destroy` swallows
  IOException and deletes the row regardless, no per-name lock, backups read
  wholly into memory). The instance driver's destroy MUST distinguish
  unreachable from absent, hold a per-instance lock, and never delete the record
  (with its only copy of credentials) on an ambiguous failure. See cross-cutting
  "durable operations".

Phase gate: the prerequisite gate above is green; create a Debian container
instance from the admin as a quota-limited creator; the container runs
cap-dropped/no-new-privileges under the declared host posture and network policy;
delegate console+power (NOT exec, NOT config) to a second user; that user operates
it from /manage through the live terminal but PROVABLY cannot change config, run
exec, delegate exec, reach another tenant/control plane, or exceed quota under a
concurrent create. A controller crash and host reboot survive (fenced operation
resumes, allocations persist, containers re-adopted by label); full browser plus
real-daemon journey. Prove a tenant cannot enumerate another tenant's instance
via any RecordSource, subpage, activity/revision route or WebSocket handshake.

---

## Phase 4 -- Incus driver, snapshots, backups

- `IncusClient`: HTTPS + TLS client certificate on port 8443 remotely, unix
  socket locally, trust-token enrollment (NOT the DockerClient socket/SSH
  pattern -- corrected doctrine). Incus supplies node identity and enrollment,
  so this driver also gives us the node-identity story the audits wanted a
  bespoke agent for. Driver #2: system containers -- images from image servers,
  profiles, limits, exec/console websockets (the TTY comes almost free).
  Unprivileged containers by default; privileged is an admin template flag with
  a stated escape warning (threat model boundary 1).
- Server records declare their runtimes as data (docker socket/ssh + host-key
  pin, incus socket / https+cert). The per-host seam is fully data-driven.
- Snapshots: driver-level (Incus native; Docker driver = volume archive via the
  existing archive API), surfaced as capability-gated actions + rows. A
  snapshot is NOT a backup: it lives in the same storage pool as the instance
  and dies with the host/pool. Snapshot rows and backup rows are distinct
  records with distinct capabilities.
- Backups EXPORT OFF-HOST: per-instance scheduled backups ride Phase 3's
  record schedules and produce a portable export (Incus `export` / Docker
  volume archive) written to a backup target in a DIFFERENT failure domain from
  the instance host. A directory on a separate backed-up control-plane host is
  the floor; a local directory on a combined control+compute host is NOT
  off-host. Ship a target seam with at least filesystem and one remote/object
  implementation, target health checks and bounded streaming. Retention per
  instance follows the database-backup pattern; restore flow uses settle-then-
  refuse status guards (a
  protected status gates power actions -- the Pterodactyl restoring_backup
  lesson) and restore-to-NEW-instance supported, not just in-place.
- Every backup carries a versioned manifest: runtime/template version, image
  digest, instance config, variables and secret references, volume inventory,
  ownership, resource limits and required port semantics. Payloads and manifest
  are checksummed; sensitive metadata and data are encrypted; interrupted
  uploads are quarantined/removed; application-consistency hooks are explicit;
  restore verifies checksums and available capacity before changing live state.
- Gate: an Incus Debian container with a nightly snapshot schedule, a
  snapshot-restore round trip, AND an off-host backup exported then restored
  to a NEW instance with its manifest/config/data intact -- proven in a browser
  test on a real Incus daemon. Corrupt one payload and interrupt one upload;
  restore must refuse corruption and cleanup must leave no valid-looking backup.
  Use a privileged testcontainer or dedicated CI host; if neither is feasible,
  use a fake plus one live smoke script and state that honestly (open decision 6).

---

## Phase 5 -- Templates and the game surface

- Instance TEMPLATES (the egg analogue, hohenheim-owned): runtime+image/source,
  typed variable schema (zenit-forms fields -- real typed validation instead of
  Pterodactyl's rule-strings), port requirements, config files (StackFileModel
  generalized to instances), startup/env mapping, optional install step, and
  console line matchers -- readiness ("done" -> Running) and graceful-stop
  (console command or signal), running on the driver's log stream; an observed
  stop command suppresses crash detection. Crash policy per instance
  (clean-exit-as-crash default for game templates, flap protection -- the
  supervisor already has the pattern).
- Templates are the DEFAULT source of instance images (threat model: tenants
  create from operator-approved templates; arbitrary images need `image_any`).
- Template catalog admin + "create instance from template" flow (variables
  render as a normal zenit-form). Templates have versioned import/export with
  signed/checksummed source metadata; a public replacement cannot strand its
  catalog as unportable rows. Operator approval is required before an imported
  image/install source becomes tenant-selectable.
- Install and reinstall are durable operations. Reinstall preserves or clears
  data only according to an explicit template policy and typed confirmation;
  interrupted install/reinstall resumes or rolls back without deleting the only
  record of variables/credentials.
- Record schedules grow ordered task chains with offsets, failure policy and
  per-step capability requirements (send console command, power action, backup,
  etc.), rather than one action token per cron row. The simple one-action form
  remains the one-step specialization.
- Tenant database allocation uses the existing managed-database tier through a
  record-scoped quota and ownership link; it never exposes another tenant's host
  or credentials. Instance transfer between eligible hosts is a durable,
  capacity-reserved operation with rollback and port reallocation; cold transfer
  is the required floor, live migration is not implied.
- Define the tenant-facing instance API in the same phase as these actions. It
  uses the exact record-capability funnel and safe projection as /manage; HTML
  routes are not the automation API.
- Game wiring: Minecraft server template + Velocity template; a game-domains
  mapping (domain record -> backend instance) that MATERIALIZES as generated
  Velocity forced-hosts config (via the instance config-file mechanism) and DNS
  records (SRV/A via the existing DNS role) on change. Creating/changing the
  mapping requires authority over BOTH records; generated config/DNS rows carry
  owner+source metadata and reconcile/delete only their own output. Minecraft
  traffic flows through Velocity; no in-house MC protocol.
- Localization: game audiences are the LEAST English-safe, and today's /manage
  subpages hardcode English titles by concatenation (`SiteProcessesPage.java:53`,
  `SiteDeploymentsPage.java:53`, `SiteDomainsPage.java:58`). Fix these to
  microcopy in this phase (they are the exact subpages a delegated player-admin
  sees).
- Velocity's player-info forwarding secret rides the Phase 3 secret-variable
  mechanism (an encrypted instance variable materialized into both configs),
  never a plaintext config literal.
- Gate: Velocity + one Minecraft backend from templates, reachable through a
  domain, readiness detected from console, graceful stop via console command, a
  delegated player-admin operates the backend console from a fully localized
  /manage -- AND the negative half: a direct connection to the backend
  instance's port from outside FAILS (the Velocity-fronts-everything doctrine
  is proven, not assumed). Also reinstall a disposable fixture, execute a
  two-step scheduled backup+restart chain, allocate one tenant database, cold-
  transfer the backend to another eligible host, and repeat the ordinary power
  action through the tenant API. Every step proves a second tenant gets 404 and
  an `access.manage`-only user cannot grant exec.

---

## Phase 6 -- Files, live stats, polish

- File manager over the driver seam (list/read/write/upload/download/rename/
  delete), capabilities `files.read`/`files.write`, size caps, per-template
  denylist. `zenit-forms` `FilesystemBrowserRegistry`/`FilesystemBrowserSource`
  already exists and hohenheim already wires it, but it takes a flat Permission,
  not a record capability -- EXTEND it to accept a record-capability gate (or
  state why a new source is warranted). SFTP OUT of scope (revisit with a
  concrete need).
- Live per-instance stats (docker stats / Incus metrics) streamed to the detail
  page (pl-chart/pl-sparkline); servers page refines the Phase 3 admission
  snapshot with live storage/capacity awareness and alerts. Placement never
  waits until Phase 6 to know whether declared capacity exists.
- Follow logs with bounded server/client buffers, retention and tenant-safe
  redaction; output/PID/file-transfer limits prevent a tenant from turning the
  observability surface into control-plane resource exhaustion.
- Attention collectors for instances (crashed, backup failed, disk high).

Phase gate: separate users with `files.read` and `files.write` prove the full
read/write negative matrix, including traversal, symlink escape, oversized
upload and template denylist; a reconnecting live log/stat stream stays bounded;
actual disk growth trips quota/attention without corrupting the instance; every
page and error is localized. Close the Pterodactyl replacement inventory here:
each remaining item is passing or an explicit non-goal.

---

## Phase 7 -- PaaS / Coolify-class completion

This phase closes the separate `hohenext-roadmap.md` PaaS track against the
replacement definition at the top of this document. It may interleave after
Phases 1-3 because it does not depend on VMs, but it uses the same host posture,
network, quota, ownership, secret and durable-operation mechanisms.

- Decide the canonical relation between Site, Stack, Deployment and Instance.
  Preserve product-level records where useful, but lower every running release
  onto one owned runtime-resource contract. Migrate/adapt existing Docker sites,
  git deploys, managed databases and stacks without creating a second UI over
  the same records.
- Sandboxed builders run outside the control-plane trust domain with their own
  CPU/memory/disk/time/PID quotas, restricted network, short-lived source and
  registry credentials, no tenant runtime secrets, and immutable artifact
  output pinned by digest. Dockerfile and buildpack/Nixpacks-style builds share
  one build-operation record and log stream.
- Projects and environments group applications, databases, domains, variables
  and quotas. This is the point to adopt the hierarchical tenant/project model
  if open decision 7 selects it; do not bolt projects onto URLs while ownership
  remains per-user underneath.
- GitHub/GitLab-compatible provider installation, repository/branch selection,
  signed webhooks and deployment status reporting. Preview deployments have
  bounded lifetime/quota, isolated variables and deterministic generated-domain
  ownership/cleanup.
- Health-gated zero-downtime release: create candidate, probe, atomically switch
  routing, drain old release, retain rollback target, then reclaim. Failed health
  never replaces the serving release. Rollback is one durable operation over a
  pinned artifact/spec, not a rebuild of mutable source.
- Per-deployment build/runtime logs, environment/secret editing, persistent
  storage, managed database links, domains/TLS/DNS and notifications all use the
  existing generated/resource surfaces and shared authorization. Cross-tier
  links require authority over both records and generated DNS/cert records carry
  owner+source metadata for reconciliation and cleanup.
- Ship a documented API/CLI for project, deployment, rollback, logs and secrets;
  API keys are capability/permission narrowed and browser sessions remain CSRF
  protected. Import tooling covers the Hohenheim records predating this model.

Phase gate: two mutually hostile projects deploy from separate repositories;
one uses a Dockerfile and one a buildpack; a preview is created and expires;
a failed candidate never receives production traffic; a healthy candidate swaps
without a failed request and rolls back to the pinned prior artifact; builders
cannot reach runtime secrets/control plane/other project; project quota holds
under concurrent builds; domain/DNS/certificate cleanup is ownership-safe; the
same flow works through UI and API. Close every item in the Coolify replacement
inventory before using that claim publicly.

---

## Phase 8 -- VMs (deferred until Jelle green-lights)

VMs are the ONLY strong isolation boundary against hostile tenants on shared
iron (threat model boundary 1), so this phase is what makes multi-tenant-hostile
shared hosting actually safe -- it is deferred in ORDER, not in importance.

- Incus VM support through the same driver (kind=vm), cloud-init for Linux;
  Windows via PREPARED TEMPLATES (virtio + RDP pre-enabled) -- template-based
  provisioning, no in-panel OS install initially.
- Framebuffer console: grant-checked WS proxy over Incus VGA/SPICE + a plumage
  viewer component (the one genuinely new UI primitive; SPICE/VNC hypervisor-
  side is the requirement, RDP is guest-side and not a substitute). Until it
  ships, a raw console websocket for external clients is the rescue hatch.
- Complete the checked-in Proxmox-use inventory: disk/NIC/device editing,
  storage-pool placement and capacity, templates/clones, bridge/VLAN/firewall
  policy, snapshots, off-host backup/restore, host drain, cold migration and
  failure recovery are implemented or explicitly rejected. PCI/GPU passthrough,
  ISO install, guest agent, clustering, HA and live migration are decisions in
  that inventory, not assumed omissions.
- Proxmox driver only when a concrete shared-iron host needs it; Incus remains
  the primary mechanism and no lowest-common-denominator driver API hides
  runtime-specific capabilities.

Phase gate: provision Linux from cloud-init and Windows from a prepared template;
attach/resize a disk and NIC under quota; enforce the network policy; snapshot;
export an off-host backup; restore to a new host; use the framebuffer rescue
console; drain the source host through the chosen migration policy; revoke tenant
access mid-console; and recover from a killed controller without split ownership.
The checked-in Proxmox-use inventory is fully closed before the replacement claim.

---

## Cross-cutting foundations (missing from the old plan, needed for public)

These are not phases; they are models/mechanisms several phases depend on.
Each names its home and its first consumer.

- **Durable operations + reconciliation.** Hohenheim has TWO precedents to
  generalize (`DeploymentModel`, `StackDeploymentModel`): a record of
  desired-vs-actual with a worker lane, interrupted-op recovery at boot, and
  status recompute from live truth. The instance tier needs the same shape
  (a create/start/destroy that survives a control-plane crash without orphaning
  a container or deleting the only record of its credentials). Generalize the
  stack worker-lane + `resetInterruptedDeploys` pattern into an instance
  operation model in Phase 3, not a bespoke retry. Process-local serialization
  is only one layer: host leases, fencing tokens, operation ids and desired
  generations from the Phase 3 prerequisites prevent two controllers from
  mutating the same daemon concurrently.
- **Image identity.** Mutable Docker tags and Incus aliases are NOT deployment
  identities. An instance pins a digest (the `DockerReclaim` code already
  canonicalizes to digest form -- reuse). Record the resolved digest per
  instance so "what is actually running" is answerable and reclaim is safe.
- **Secret model.** Phase 0.6 redacts fields DECLARED secret/encrypted from new
  derived surfaces and hashes selected bearer credentials; it does NOT encrypt
  all existing secrets and does not yet contain free environment maps or legacy
  restore. Phase 0 closes those release blockers; the Phase 2 parallel 0.6c
  workstream backfills recoverable columns deliberately. The instance tier adds
  table-backed secret variables (Phase 3). State once, centrally, that new
  secrets are encrypted columns, never JSON sub-schema fields, never revisioned/
  logged in cleartext, and that the DB+keyring backup gate precedes their first
  write.
- **Host capacity / placement.** Quota and placement use an admission-time
  per-host capacity snapshot in Phase 3; Phase 6 live stats refine it
  and detect drift. Placement authority (which creator on which host) is part
  of the create story and reservations prevent concurrent over-placement.
- **Storage / network models.** Named volumes already exist for stacks and the
  initial instance storage vocabulary reuses them. Full IPAM may wait for a
  concrete multi-host need, but the enforceable Phase 3 network-isolation model
  may not: default-deny tenant boundaries and allocated ingress are prerequisites.
- **Control-plane backup.** The Hohenheim database plus encryption keyring hold
  fleet credentials; losing the controller must not mean losing all of them.
  A scheduled, documented backup of DB + keyring to an off-host target, plus an
  exercised fresh-controller restore, lands BEFORE Phase 3 secret variables.
  Phase 4 extends the target/retention machinery for workload backups.
- **Rollout / upgrade for existing installs.** Once other people run installs,
  migrations and behavior changes must be safe on live data. RECON CORRECTION
  (verified 2026-07-28) -- integrity is NOT off, and the blocker is not what the
  audit said:
  - `database.migration_integrity` is `off|warn|fail` and defaults to **warn**
    (`ServerSettings.java:404-408`); hohenheim never overrides it. Every install
    today LOGS its findings and boots anyway. For a public product, silently
    continuing past "your schema does not match your migrations" is arguably
    worse than either extreme -- the shipped default is a deliberate decision,
    not an inherited one.
  - The real blocker to `fail` is **26 of 56 history rows carrying NULL
    checksums** (only `2026_07_07_000025` onward have any). The runner skips
    comparison entirely on null (`MigrationRunner.java:338-343`), so drift is
    invisible for that reason, not because checking is disabled.
  - "Six migrations edited after apply" is misleading: 10 files changed
    post-creation, but 5 of those edits are `down()`-only and the checksum
    covers `up()` only. Five surviving migrations have real `up()` drift
    (M003-M006, M026).
  - CONFIRMED: M001/M002/M007 deleted (commit `3678bd9`) with history rows
    remaining and `acknowledgeMissingMigrationVersions` never called; `M042` is
    the SOLE migration building DDL from a live model (`new StackServiceModel()`);
    2 of 46 alter-table `addColumn` sites carry `.ifNotExists()`.
  - `M043`'s `assertUnique` is an AVAILABILITY bug, not hygiene: it throws on
    the first duplicate, propagates through `.requireSuccess()`
    (`HohenheimDatabase.java:39`) and KILLS BOOT, telling the operator to
    renumber history from a dead control plane. Fix regardless of this arc.
  - ORDERING TRAP: `.ifNotExists()` is stamped INTO the checksum
    (`MigrationChecksum.java:206-209`). Retrofitting it across the 46 sites
    changes the checksum of every migration touched, so doing it AFTER enabling
    integrity manufactures ~21 false findings. Retrofit and checksum-stamping
    are one coupled step, in that order.
  - Also unguarded and not re-run safe: `M041:30-31` raw `INSERT`, `M025`/`M026`
    raw `schema.execute` DDL/DML, `M026:56` `DROP TABLE audit_log`. And
    `database.schema_drift_check` (`ServerSettings.java:410-414`, also `warn`)
    is a second independent drift signal nobody is watching -- it would catch
    the M042 class of problem from the other side.
  - Remediation ORDER: snapshot the live DB first; capture one real boot's
    findings at `warn` (remediate against the real list, not an inferred one);
    acknowledge the three deleted versions; freeze M042 to literal DDL; fix
    M043; then the coupled ifNotExists + `repairNullChecksums()` step (which
    BLESSES current source as truth -- verify the live schema per table first);
    only then consider `fail`. No foreign installs exist yet, which makes this
    repair cheap now and expensive later.
  - Unrelated but found: 7 browserTest classes hand-list migrations, violating
    the auto-discovery hard rule. Not release-blocking; fix opportunistically.

  STATUS (2026-07-29): the CODE half of this workstream LANDED. What changed:
  - `M043` heals instead of asserting: duplicate `(stack_id, name)` /
    `(stack_service_id, container_path)` rows are renamed to `<value>__dup<id>`
    before the unique index is created, and `assertUnique` is gone. An install
    carrying duplicates now boots.
  - The three retired versions are acknowledged in `HohenheimDatabase` from a
    public `RETIRED_MIGRATION_VERSIONS` constant (also consumed by the test).
  - `M042` is frozen to literal DDL. Its structural checksum is UNCHANGED
    (`cd499511042cc811111f668ee815a9e1548861bd486aec244548c3ddb67397b4`, pinned
    by a test), so the freeze costs the live install nothing.
  - All 46 alter-table `addColumn` sites carry `.ifNotExists()`.
  - `database.migration_integrity` is now pinned explicitly at `warn` in
    `settings/default.dry`, so hohenheim's posture no longer silently follows
    whatever zenit's default happens to be.
  - The 7 hand-listing browserTest classes use auto-discovery.
  - New `MigrationIntegrityTest` (browserTest) covers: fresh migrate + re-migrate
    no-op + a green run at `migration_integrity=fail`; replaying every ALTER-only
    migration onto an existing schema; the duplicate-rows heal; retired-version
    acknowledgement in both directions; the M042 checksum pin.

  STILL OPEN, deliberately NOT done here (they need Jelle):
  - The checksum stamping against the live database. `repairNullChecksums()`
    covers the 26 NULL rows only. Editing `M043` and the 8 alter migrations that
    already carry checksums (M029/M030/M031/M033/M035/M037/M038/M039) produced
    "modified after applied" findings that NO framework API can clear -- zenit
    has half of Flyway `repair`. Snapshot the DB, capture one real boot's
    findings at `warn`, verify the live schema per table, then stamp.
  - `migration_integrity=fail` as the shipped default. It is the right
    destination and a clean install already passes it, but flipping it today
    would refuse to boot the one live install. Flip it in the same change that
    stamps the checksums.
  - FRAMEWORK GAP found: `MigrationBuilder.createTable` has no `ifNotExists`
    option, so a migration that CREATES a table can never be replayed onto an
    existing schema. That caps the column-level `.ifNotExists()` work at
    ALTER-only migrations, and is why `M041`'s INSERT was left unguarded (its
    CREATE dies first, so the guard could never be reached or tested).
- **Destructive-operation audit.** Every destroy/purge/snapshot-restore is a
  data-loss surface. Reuse the typed-confirmation + ownership-label pattern the
  stack tier already proved (`purge_stack_volumes`), and log every destructive
  op with accountability.

---

## Cross-cutting rules

- Every phase lands with tests at the level it changes (unit + browser journeys
  for UI; driver seams get fakes plus at least one real-daemon journey each --
  the DockerReclaimTest precedent).
- Test budget: the house rule is a 5-minute targeted suite. The per-phase
  real-daemon and browser journeys go in an OPT-IN suite (the `--datasources
  all` precedent), not the default run. All-8-backend grant coverage needs
  `TestDatasources` promoted from `zenit/src/test` to a published test-support
  artifact (it is not published today) -- do that when Phase 1 touches grants
  on non-SQLite/Postgres backends.
- Localization: all new UI copy through microcopy (short keys + filters);
  instance/template/server names are user data and never localized; template
  descriptions ARE localizable. Every new capability states its localized
  behavior before it ships. The hardcoded-English /manage subpage titles are
  fixed in Phase 5.
- Docs/skills: zenit-cms-resources skill gains the record-access page; a new
  hohenheim skill for the instance/driver seam when Phase 3 lands; update
  `architecture-stacks.md` now to state its three encrypted columns narrowly;
  update any platform-wide encryption claim only after the 0.6c workstream lands.
- Order is dependency order. Phase 0.A (code) gates any public tag; Phase 0.B
  (live-install rollout, decisions 1 and 13) gates declaring the EXISTING install
  healthy and runs alongside, not inside, the tag. Phase 0 work unrelated to
  grants may interleave with Phase 1, but Phase 0.8 closes first. Phases 3+ each
  assume the previous gate, and Phase 3 additionally requires decisions 7 and 8.
  Phase 7 may interleave after Phase 3 because PaaS completion does not depend on
  Incus/game/files/VM work, but it must consume the same foundations rather than
  fork them.

## Open decisions (need Jelle, flagged not assumed)

1. Historical plaintext secret remediation: choose scrub-in-place with explicit
   restore semantics vs dropping pre-fix revisions. "Leave and document" is no
   longer safe because restore can reactivate credentials. Snapshot production
   first; the operation is destructive and auditable.
2. Make WebSocket revalidation default-on for every authorization-gated
   endpoint, or require an explicit opt-out with a build/test warning. Decide
   before any Phase 3 endpoint is registered.
3. Whether `/admin/**` GETs are interactive-only. Today an admin-scoped API key
   can read auth admin HTML and its user/role inventory, although mutations are
   interactive-only.
4. Capability naming in UI copy ("record access" vs a localized term) -- pick at
   the Phase 1 UI stage.
5. Whether stack services eventually BECOME instances or remain a separate
   product tier over the shared runtime-resource mechanisms. Decide the Phase 3
   ownership adapter now; revisit full convergence after Phase 4 with real use.
6. Incus testing strategy if no daemon runs in CI (fake + live smoke vs
   privileged testcontainer) -- decide at Phase 4 start.
7. The tenancy boundary shape: per-owner quota (Phase 3 minimum) vs a full
   hierarchical project/tenant model with aggregate quotas. The public shift
   makes the create-gate + reservation-backed per-owner cap non-negotiable; the
   PaaS project/environment model makes the aggregate shape likely. Decide
   before Phase 3 fixes ownership keys into every new table.
8. Shared-host untrusted multi-tenancy posture: do we ever offer container-only
   isolation to hostile tenants with a per-host operator acknowledgement, or is
   the answer always VM-per-tenant / dedicated host (Phase 8)? This is a product
   risk decision and a Phase 3 ENTRY blocker, not a later deployment toggle.
9. `record_schedules` as a new table vs a facet of `SystemTaskModel` -- leaning
   new table; confirm at Phase 3, while preserving ordered task-chain semantics.
10. Incus CLUSTERING is a stated deferral, not an omission: "runtime = data on
    the server record" bakes in a 1:1 runtime-to-host assumption that an Incus
    cluster breaks (placement, quorum, shared storage). Standalone daemons only
    until a concrete cluster need exists; revisit the schema assumption then.
11. Check in the actual Proxmox-use inventory before Phase 8 and decide which of
    clustering/HA/live migration/shared storage/device passthrough/ISO install
    are requirements. Without that inventory, "replacement" has no testable
    meaning.
12. Confirm SFTP as a Pterodactyl non-goal after the Phase 6 browser/API file
    manager is used on real game workloads; do not declare it unnecessary from
    design preference alone.
13. Migration checksum stamping against the LIVE install, and the flip of
    `database.migration_integrity` to `fail` in that same change. This is the
    0.B rollout half of the Phase 0 gate. It was described in the cross-cutting
    section but was never a numbered decision, so it had no owner. The procedure
    is prepared (snapshot, capture one real boot's findings at `warn`, verify
    the live schema per table, acknowledge retired versions, repair 26 NULL
    checksums plus 5 drifted migrations, flip). It needs a scratch JVM on the
    app classpath -- no CLI exists. Blocked on Jelle because it touches the one
    running production database.
14. Release posture for the FIRST public tag: does it ship with tenant
    delegation enabled, or delegation-disabled-by-default with hostile
    multi-tenancy declared unsupported until the Phase 3 admission
    prerequisites land? This is the only scope lever in the document and it is
    currently absent, which means the plan implicitly chose the expensive
    answer. Publishing the source is not the same event as operating untrusted
    tenants: a tag that ships `/manage` delegation off, with the site-access
    grants admin-only, needs Phase 0.A and nothing else. A tag that ships
    delegation on inherits the full hostile-tenant boundary immediately. Decide
    this BEFORE scheduling Phase 3, because it determines whether the Phase 3
    prerequisite block is on the critical path to a public release or parallel
    to it.
