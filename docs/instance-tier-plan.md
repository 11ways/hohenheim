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

### STATUS: Phase 0 re-verified against code (2026-08-02)

Source-level verification of every item the 2026-07-29 audit reopened, done
against current HEADs rather than against the prose below. CLOSED: 0.1 (the
ingress test now drives a real `ProxyServer` listener over a raw socket, not
env-var planting), 0.2 (uid exclusivity, reserved-env stamping and wrapper
buffering all landed), 0.4, 0.6a, 0.6b, 0.8 (atomic `insertIfAbsent`, deny as
sole unconditional writer), 0.9 for its named attack.

STILL OPEN, with owners in this document:
- **0.6c encryption at rest: UNSTARTED.** Exactly three `.encrypted()` fields
  exist (`StackModel:80`, `StackDeploymentModel:40`, `StackFileModel:35`) and
  all three were born encrypted. `SiteModel.SECURITY_REPORT_TOKEN:103` is still
  `.secret()`-only. The keyring and `database.encryption.key_file` are
  pre-existing framework code, not new work. Owned by the Phase 2 parallel gate.
- **0.5 published shell: NOT EVIDENCE.** The only manifest row with no observed
  counterfactual; never re-run; its detector recognizes `java/lang/Process` and
  `com/pty4j` only, so `Runtime.exec` and JNI evade it.
- **0.7 gate incomplete.** The mechanism landed (admission before
  authentication, `onError` routed to shared teardown) but the audit's actual
  ask -- open a real terminal, log out / revoke / disable / delete, observe 1008
  -- has no test anywhere.

NEW FINDING, same boundary as 0.9, fixed 2026-08-02 (`fef9bde`, `683ebfe`):
route-claim conflict detection compared hostnames by string equality while
`RouteClaims.keyOf` kept the literal hostname, so `*.example.com` (tenant A) and
`foo.example.com` (tenant B) hashed to different keys, both committed, and the
exact-before-wildcard dispatch let B seize A's traffic. Claims now compare
hostname-set INTERSECTION, with ownership scoped to the site's set of truthy
`manage` grant subjects (compared by equality, not overlap, so `{A}` vs `{A,B}`
cannot let B seize). Operator-only sites hold no manage grants and so may still
carve out across sites, which is required: upstream is a site-level setting, so
a cross-site carve-out genuinely needs two sites.

SECURITY THEATER recorded, not yet fixed: `GrantWritePrimitiveGuardTest`
enforces the PRESENCE OF A COMMENT (`// grant-write-guarded:`) within 6 lines of
each `GrantService.createDirectGrant` call site; nothing verifies an
`AdministratorGuard` is on the stack. Writing the comment passes the test, and
that test is what closed a re-review finding about a genuinely unguarded
last-administrator path.

The manifest is stale against its own "Now" column -- every repo has moved past
the hashes it pins (zenit by 31 commits at the time of checking). Treat its
per-row hashes as historical.

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

- DECIDED 2026-08-02, per model. This is the answer the gate demanded; it is
  binding and belongs in the combined product boundary section.

  - **`DnsZoneModel` -- PERMANENT ADMIN-ONLY NON-GOAL.** A zone row is the
    DNSSEC/TSIG trust root (`DNSSEC_PRIVATE_KEY`, `DNSSEC_ALGORITHM`,
    `DNSSEC_KEY_TAG`, plus `ROLE`/`PRIMARY_PEER_ID` binding it to a
    TSIG-holding peer). There is no per-field split that leaves a tenant a safe
    subset: every remaining field is SOA policy whose blast radius is the whole
    zone going dark. Creating a zone also ASSERTS a delegation from the parent
    that hohenheim cannot verify, so only an operator can make it. A tenant
    never sees a zone row -- only names inside one.
  - **`DnsPeerModel` / `DnsZonePeerModel` -- SAME RULING, same reason**
    (`TSIG_SECRET`, `API_KEY`). Named explicitly so they are not left silent.
  - **`DnsRecordModel` -- NARROW VOCABULARY.** `view` (ordinary, delegable,
    owner-implied), `edit` (elevated, delegable, owner-implied) restricted by a
    TYPE ALLOW-LIST of A/AAAA/CNAME/TXT/SRV, and `dyndns` (elevated, NOT
    delegable, not owner-implied). NS/CAA/MX/DS/DNSKEY authoring, `MANAGED_BY`
    mutation and `ZONE_ID` reassignment are not capabilities at all: each is a
    zone-compromise primitive (NS delegates a subtree away, CAA disables or
    redirects issuance for the whole name, moving a row between zones is a
    takeover). `dyndns` is non-delegable because the minted token is a bearer
    credential that SURVIVES grant revocation -- re-delegation would launder a
    permanent capability out of a revocable one. Enforce the allow-list in the
    write pipeline, never in the resource.
  - **`SiteDomainModel` -- NO INDEPENDENT GRANT SURFACE.** Authority over a
    domain is already fully determined by `manage` on its parent site
    (`SITE_ID` -> `HohenheimAccess.canManageSite`). A second grant surface on
    the child row means two authorities that can disagree, and it would spawn a
    second auto-registered `RecordAccessPage`. Tenants may never set
    `LISTEN_ON` (a disjoint listener walks straight past the overlap check),
    `MATCH_TYPE` other than `exact` (a tenant wildcard or regex claims an
    unbounded hostname set), or `CERTIFICATE_ID` (pinning a certificate row
    they do not own).
  - **`CertificateModel` -- VOCABULARY, ORDINARY ONLY.** `view` (ordinary,
    delegable, owner-implied: status, expiry, hostnames, renewal error --
    never PEMs) and `request` (elevated, delegable, NOT owner-implied).
    **Key export is NEVER delegable and never owner-implied**: hohenheim
    terminates TLS itself, so a tenant has no need for the private key, and
    exporting it makes revocation meaningless. Certificate UPLOAD stays
    admin-only -- an uploaded cert is unverified authority over a name.
    ~~The `PROVIDER_ACME_ACCOUNT` row must be excluded by the ACCESS criteria,
    not only by the existing `baseCriteria` filter.~~ **SUPERSEDED 2026-08-02.**
    `baseCriteria` is ANDed into every RecordSource path -- query, resolve,
    exists, buckets -- exactly where `accessCriteria` lands, so the exclusion is
    already total. Restating it as `accessCriteria` was implemented, proved to
    change no observable behaviour on any path, could be given no counterfactual,
    and was reverted; an unfalsifiable second declaration is security theater,
    not defence in depth. `accessCriteria` is for decisions that differ PER
    PRINCIPAL. The framework contract is recorded as an AIDEV-NOTE on the
    certificate source in `HohenheimSources`.

  Registering a vocabulary is NOT free: `AuthRecordAccessBridge` auto-attaches
  a subjects-x-capabilities matrix page to every model that declares one and is
  grantable. Never declare one before that resource's scoping is complete.

  STATUS 2026-08-02 -- LANDED. `HohenheimAccess.declareGrantableModels` declares
  both vocabularies (and an AIDEV-NOTE recording the zone/peer non-goal at the
  one place a future reader would add one). Both blockers landed with them:
  `ManageDnsRecordResource` and `ManageCertificateResource` are /manage peers
  with explicit scoped RecordSource overrides beside the site and domain ones
  (`ManagePanel.dnsRecordScope` / `certificateScope`), so the auto-attached
  matrix page has a tenant-reachable host.

  Two implementation decisions this section did not fix, recorded here:

  - **DNS authority is the HOSTNAME, not the zone and not only the grant.** A
    tenant may author a record whose FQDN is covered by a live domain row of a
    site it manages -- the same `HostnameAuthority` walk a certificate order
    asks, extracted out of `CertificateAuthority` so the two can never answer
    differently. An explicit `edit` grant is the second lane (authority over a
    row that is not under one of its hostnames); `dyndns` alone is narrowed to
    the two dynamic columns. Renaming or creating always needs hostname
    authority over the name being CLAIMED, so a grant cannot launder into a
    takeover. Without this a tenant could author any name in any zone, which is
    what the pre-2026-08-02 code and its test actually allowed.
  - **Certificate `view` scope is requester-or-grant, NOT domain coverage.**
    Coverage is authority to REQUEST and `CertificateAuthority` owns it; making
    it a read scope too would put a second authority beside the capability walk
    the vocabulary declares.
- Domain claim authority: who may bind a hostname, and what stops tenant B from
  claiming a name tenant A already serves or once served. This is the same
  invariant as 0.9 route ownership, one tier up. Reuse the model-level
  invariant 0.9 establishes; do not hand-roll a second check.

  STATUS 2026-08-02: the ALREADY-SERVES half is DONE and is already
  owner-scoped -- `SiteDomainResource.refuseRouteConflicts` compares hostname
  SETS via `HostnamePatterns.intersect` and exempts only same-owner pairs,
  where owner is the site's set of truthy `manage` grant subjects. Site-vs-site
  is just the degenerate operator case (empty subject sets compare equal).

  The ONCE-SERVED half is NOT done and nothing tracks it. There is no release
  ledger anywhere; `LIVE_ROUTE_KEY` simply goes NULL, and an existing test
  (`RouteOwnershipInvariantTest.aDeletedSitesHostnameBecomesClaimableAgain`)
  asserts the OPPOSITE invariant on purpose -- correct for an operator-owned
  install, wrong for multi-tenant. The attack is ordinary subdomain takeover:
  tenant A serves `shop.example.com` and points a CNAME at it from a zone we do
  not host, deletes the site, tenant B claims the name, and A's still-live
  CNAME delivers A's users to B. Refusing the claim is the only lever we have,
  because the dangling pointer is outside our control.

  DECIDED: add a released-claim ledger (hostname set + former owner subject set
  + released_at) written by the same restamp pass that nulls `LIVE_ROUTE_KEY`.
  `refuseRouteConflicts` consults it and refuses a claim by a DIFFERENT owner
  inside a quarantine window, with a recorded admin override. Same owner
  reclaiming is always allowed, and operator-to-operator is unaffected because
  both sides carry empty subject sets -- so the existing test keeps passing and
  is AMENDED rather than deleted.

  RECON 2026-08-03, verified in framework source. The design survives, but by a
  single hook, and the natural implementation gets it wrong:

  ORDERING (the load-bearing fact): the restamp is a beforeWrite hook
  (`SiteResource.java:200,207`); grant revocation is `RecordGrantCleanup`'s
  AFTER_SAVE, registered via `GlobalModelHooks.addAfterSaveHook`
  (`RecordGrantCleanup.java:69`); `Schema` fires beforeWrite hooks at `:693` and
  afterSave hooks at `:707`. So the manage-grant subject set is INTACT when
  restamp runs. THE LEDGER MUST BE WRITTEN FROM THE beforeWrite HOOK. An
  afterSave writer -- the more natural-looking place, since it records something
  that already happened -- races grant cleanup by pure registration order,
  captures an EMPTY subject set, and every released hostname then looks
  operator-owned. Quarantine never fires, for exactly the tenant case it exists
  for, with every test green. Assert the STORED subject set in the test, not
  only the refusal, or the green proves nothing.

  Also: re-saving a still-trashed row RE-FIRES cleanup and restore never
  resurrects grants, so the owner set can only be captured on the FIRST delete
  save. The ledger write is idempotent-on-first-write, never last-write-wins.

  THREE RELEASE PATHS, not one. The restamp covers soft delete and disable.
  UNCOVERED and both must be handled or the ledger is worse than none:
   - domain-row DELETE: `SiteDomainResource` does not override `deleteRow`, so
     it inherits `RowResource.deleteRow`'s plain `model.delete()` -- no hook at
     all. This is the most natural way a tenant abandons one hostname while
     keeping the site.
   - hostname/path/listener CHANGE: `SiteDomainResource.java:175-185`
     unconditionally reassigns `LIVE_ROUTE_KEY`, freeing the departing key with
     nothing observing it. The hook has the stored row and could diff; it does
     not.
  MIRROR-IMAGE TRAP: three paths must NOT write ledger rows or the ledger
  quarantines owners against themselves -- restamp's intra-site swap release
  (`RouteClaims.java:161-168`), the same-site duplicate loser (`:153-160`), and
  the migration backfill (`:228-244`).

  PLACEMENT: the check goes in `refuseRouteConflicts` AND
  `refuseEnableRouteConflicts` (both beforeValidate on `SiteDomainModel`).
  Omitting the enable path is bypassable by a two-step the code already
  documents as LEGAL: stage the hostname on a disabled site (exempt by design),
  then enable. It must be an INDEXED lookup on the claim key -- both functions
  already full-scan every site and every domain row per write, the enable one
  quadratically; do not extend the scan. The refusal belongs in the identical-key
  branch; wildcard-vs-exact OVERLAP against a released claim is a harder question
  and is explicitly OUT of scope rather than half-handled.

  SHAPE: a TABLE (a released claim may have no live row to hang a column on),
  keyed by `RouteClaims.keyOf` -- the same authority, never a re-derivation --
  with the subject set spelled exactly as `HohenheimAccess.java:194-195`. NO
  unique index on the claim key: the same hostname can be released repeatedly,
  and a unique index turns a legitimate re-release into a brick. Mirror
  `RouteClaims`' key-derivation + NAMED conflict + heal-don't-brick discipline,
  not its column. Note `RouteClaims.java:26-42`: the unique index is the
  equality backstop, the serialized scan is the WHOLE guarantee -- a ledger
  mirroring only the index half inherits a check that cannot catch what it
  exists for.

  MICROCOPY: the refusal must NOT name the former owner. Existing refusals name
  the holding SITE because it is live and actionable; a released claim's former
  owner is a deleted tenant, and naming them crosses a tenancy boundary the
  current refusals never cross. Separate keys for the write path and the enable
  path (precedent: `route_taken_other_site` vs `enable_route_conflict`).

  OVERRIDE: the mechanism exists -- `RowAction.Invoke` +
  `ConfirmationSpec.requireTypedConfirmation` with the HOSTNAME as the phrase,
  wrapped in `ActivityLog.withAction`. Visibility is not authorization: the
  handler re-checks admin. Window: a setting in the Security group, declared in
  DAYS, `0` means disabled and says so. 30 days is the industry convention for
  this attack and is a PROPOSAL for Jelle, not a finding.

  RESIDUAL RISK, not closed: same-owner reclaim. A site's manage grant is
  typically applied AFTER the record is created, so a tenant re-claiming via a
  fresh site can present an empty set and be refused as a "different owner".
  Admin-mediated today (`ManageSiteResource.creatable()` is false) but it fires
  the moment tenant self-service creation exists -- which the instance tier
  requires. Design the check so an empty claimant set never satisfies "different
  owner" without also being checked against the actor's own subject identity.

  ADJACENT LATENT BUG, fix in the same commit: a hard-deleted `sites` row leaves
  orphaned `site_domains` rows holding a non-null `live_route_key`. The scan
  skips them (the site lookup returns null, so they read as not-live) but the
  UNIQUE index still enforces, so the next claimant gets a duplicate-key
  conflict whose holder name resolves to null -- the operator is told the route
  is taken by "?", and the hostname is permanently unclaimable with no nameable
  holder. Only tests hard-delete sites today. Cascade the domain rows from a
  beforeRemove hook on `SiteModel`.

  STATUS 2026-08-03 -- LANDED, with ONE correction to the RECON above. The
  ledger is `released_route_claims` (M053) + `ReleasedClaims` (server/proxy);
  all three release paths write it from beforeWrite/beforeRemove; both
  `refuseRouteConflicts` and `refuseEnableRouteConflicts` consult it by INDEXED
  claim key; the window is `security.release_quarantine_days` (30, 0 = off);
  the override is `ReleasedClaimResource`'s hostname-typed `lift_quarantine`
  action, admin-re-checked in the handler; the orphan cascade landed on
  `SiteModel`.

  CORRECTION to "races grant cleanup by pure registration order": it is not a
  race and not registration order. `Schema.afterSave` fires the schema's own
  hooks and THEN `GlobalModelHooks.fireAfterSave` (Schema.java:707-711;
  GlobalModelHooks' own docblock states the tiering), and `RecordGrantCleanup`
  installs on the GLOBAL tier -- so a SCHEMA-level afterSave writer would in
  fact still see the grants. The losing writer is a GLOBAL-tier afterSave hook
  registered after zenit-auth's, and that one loses deterministically. Proven
  both ways: the schema-tier counterfactual passed (vacuous, said so), the
  global-tier one failed on the stored subject set (`expected "user:3", was
  ""`). The beforeWrite placement stays REQUIRED regardless, for a second
  reason the recon did not name: by afterSave `live_route_key` is already NULL,
  so an afterSave writer cannot tell which rows actually held a claim and must
  re-derive keys -- which is also what makes it unable to stay idempotent.

  Also decided while implementing: with the window disabled (0) nothing is
  RECORDED either, so re-enabling never quarantines on stale history; a claimant
  whose grants are unreadable fails CLOSED (an unmatchable owner marker, never an
  empty set, because an empty set reads as operator-owned); and wildcard-vs-exact
  OVERLAP against a released claim stays explicitly OUT of scope -- only the
  identical claim key is quarantined.
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

#### RECON 2026-08-02 -- ground truth for every prerequisite below

Verified in source. Where this block contradicts the prose that follows, this
block is what the code does; amend the prose, do not code against it.

- **Node identity does not exist, at all.** No node id, no lease, no heartbeat,
  no `nodes` table anywhere in zenit. `TaskClaimManager` is a static JVM-wide
  `IdentityHashMap<Datasource, Set<String>>` of held lock strings plus an
  ANONYMOUS `datasource.acquireAdvisoryLock`, and its `Claim` carries only a
  lock id and a boolean -- no owner token, no epoch, no counter, so there is
  NOTHING a runtime operation could record as a fence. `SystemTaskModel` has
  `last_fired_at` and no owner/lease/generation column. When the backend has no
  distributed lock it logs once and RETURNS A CLAIM ANYWAY.
- **`acquireAdvisoryLock` returns a bare `true` while locking nothing** on
  `SqliteDatasource`, `DuckDbDatasource`, `FirebirdDatasource` and
  `CockroachDbDatasource`. Hohenheim runs on SQLite, so in the shipped
  configuration EVERY advisory lock is a no-op. Two live callers reach the API
  without the `supportsDistributedAdvisoryLocks()` guard and inherit the silent
  no-op: `MigrationCapableDatasource.acquireMigrationLock` (concurrent
  migrations unprotected, reported as locked) and `RevisionableBehaviour`, whose
  `if (!acquired) throw revisionLockTimeout(...)` IS A CHECK THAT CANNOT FIRE on
  half the matrix. Verified separately: CockroachDB declares
  `supportsDistributedAdvisoryLocks() -> false`, so the claim manager correctly
  never takes the distributed branch there. `PostgresDatasource` has its own
  hazard -- `activeLocks` is per-datasource-INSTANCE, i.e. process-wide, so a
  second independent caller asking for a held lock is told `true` while holding
  nothing and the first release frees it for both. Build fencing on a ROW plus
  compare-and-set, never on this API.
- **Fencing IS buildable without a distributed lock service.** It needs only
  atomic compare-and-set and a monotonic counter, and the ORM already has both
  portably on all 8 backends (`Model.insertIfAbsent` -- documented never to
  degrade to an UPDATE -- and `find().increment(F).updateAll()`). A
  `controller_leases` row per (host, purpose) with owner token, `fence` bigint
  and expiry, acquired by conditional UPDATE that increments the fence in the
  same statement, is the minimum honest mechanism. It belongs in ZENIT CORE
  beside `TaskClaimManager` (pure ORM mechanics, no product knowledge) with
  `TaskClaimManager` as the wired consumer -- which also finally gives the four
  no-op backends a working coordination primitive.
- **A host record exists but is a stub.** `ServerModel` (table `servers`) has
  only id/name/mode(`local`|`ssh`)/ssh_target/timestamps -- no posture, no
  capabilities, no health, no credential, no cordon/drain, no fingerprint, no
  version. So host enrollment is a stub to GROW, not greenfield. Enrollment is
  today "paste an ssh target" with no probe and no trust ceremony; the transport
  uses `StrictHostKeyChecking=accept-new` (silent TOFU, no pinning);
  `ServerService.remove` deletes a host while stacks and databases still
  reference it BY NAME STRING with no FK, orphaning them silently.
- **`ServerService.infoFor` swallows every exception into `null`** and each
  capacity field then degrades to `0`/`""`, so host-down, wrong-key,
  docker-absent, host-key-changed and DNS failure are one indistinguishable
  state WITH ZEROED CAPACITY. A placement allocator reading `cpus`/`memoryBytes`
  would read `0` for an unreachable host and, depending on the comparison,
  either always refuse or always accept. Replace with a typed outcome before
  anything places workloads.
- **Docker absence IS loud at boot -- but only for stacks.** `DockerHealth`
  exists, is probed from `ServerMain`, has a four-state status and feeds the
  admin attention list. Any note claiming absence is silent is STALE. However
  the probe is gated on `roles.stacks`, while `DockerSiteRequestHandler` and
  `ManagedDatabase` construct clients unconditionally and still degrade
  silently. Phase 3 must not repeat the role gating.
- **Nothing resembling `InstanceRuntime` exists**, and the streaming half cannot
  be layered on: `DockerTransport` is a single-shot `byte[] roundTrip(...)`
  contract that reads to EOF with `Connection: close`, so stats, follow-logs,
  attach-with-stdin and TTY exec are UNREPRESENTABLE in it. That needs a second,
  streaming transport contract, not a patch. `DockerClient` is adaptable for the
  non-streaming half and `StackDeployer`'s label+adoption logic is the right
  pattern to generalize -- but `DockerClient` must NOT itself be the driver
  seam, because Incus (Phase 4) is HTTPS + client certs, a different shape. One
  plan claim is stale: `exchange(...)` already returns a byte-accurate response;
  only the JSON wrapper decodes to String.
- **No quota mechanism exists anywhere**, in either tree. `ResourceLimits`
  carries ONLY memory and cpus. Confirmed that `createPermission` is
  deliberately not record-aware ("creation is checked while no record exists"),
  and it is a plain boolean at form-render and submit, with persistence after --
  so a quota enforced there is a check that cannot fail under exactly the
  concurrency the gate names. The reservation must be transactional and adjacent
  to the write (`Schema.addBeforeWriteHook`/`GlobalModelHooks` inside the save
  pipeline). A generic reservation ledger is a more-than-one-consumer capability
  and belongs in ZENIT CORE, with hohenheim as the first wired consumer in the
  same phase; the limits and policy stay hohenheim.
  - **CORRECTED 2026-08-03, supersedes the "because those are transactional"
    reasoning above:** write hooks are ADJACENT to the write, NOT transactional.
    `Model.save` opens a transaction only when the schema carries
    `RevisionableBehaviour`; `SqlDatasource.doCreate` opens none; the CMS create
    is wrapped in `inMutationTransaction` only because the resource's access
    function returns a non-null predicate (`InstanceResource` does, because
    instances soft-delete) -- change it to allow-all and the transaction silently
    vanishes with green tests. A hook-based reservation must therefore be correct
    with NO ambient transaction: ONE conditional statement, never a
    read-then-write. `Leases` is the WRONG primitive here (it refuses
    in-transaction acquisition on serialized-write engines and deliberately
    commits OUTSIDE the caller's transaction, so a rolled-back create would leave
    the reservation spent); the right primitive is the guarded
    `find().where(cap).increment().updateAll()` shape `AtomicUpdateTest` pins.
  - **LANDED 2026-08-03 (re-verify, do not assume):** the generic ledger is zenit
    core `common/orm/quota` (`Quotas.reserve/release` over `zenit_quotas`,
    bucket-key PK, row birth via `insertIfAbsent`, ONE guarded `updateAll` per
    operation, floor-clamped release; `QuotaLedgerTest` races 20 reservations
    over 5 slots on the 6 multi-connection backends -- SQLite/DuckDB fixtures are
    single-connection and excluded by assumption). hohenheim wires ONE dimension:
    live-instance count per owner (`InstanceQuota` beforeWrite hook, M055,
    `hohenheim.quota.max_instances_per_owner` + per-owner `instance_quotas`
    override rows keyed by the PACKED manage-subject set). Explicitly OUT OF
    SCOPE in this slice: memory/cpu sums, disk, ports, sites, databases, stacks.
    Two contracts to keep honest: (1) the release rides the `deleted_at`
    null->non-null TRANSITION in beforeWrite, because `InstanceService.destroy`
    soft-deletes via save() and the remove hooks NEVER fire on that path (remove
    hooks additionally cover hard deletes); (2) `updateAll` is hook-free BY
    CONTRACT, so any future set-based bulk edit bypasses a hook-based quota --
    destroys must stay on the save path. For the COUNT dimension an ordinary
    update cannot increase consumption (verified: the only consumption-changing
    writes are the two deleted_at transitions, both handled); the moment MEMORY
    becomes a dimension, the CMS update submit
    (`ResourcePageEndpoints.updateRowInScope` -> `Model.save`) is a
    consumption-increasing path and must reserve deltas too.
- **Container hardening is ZERO.** No `CapDrop`, `SecurityOpt`,
  `no-new-privileges`, `Privileged`, `UsernsMode`, `ReadonlyRootfs` or
  `PidsLimit` anywhere in any non-test file. Every container hohenheim runs
  today is a default-privilege Docker container. The presence of a
  `ResourceLimits` type reads as though isolation is configured; it is two
  cgroup knobs. This is parallel work, needed by everything, and independently
  testable -- do it early.
  - **LANDED 2026-08-03 (re-verify, do not assume):** `ContainerHardening` is the
    baseline and it is applied INSIDE `DockerClient.createContainer`, which now has
    no overload without a `Profile` -- so the four authorities cannot omit it and a
    fifth cannot either (removing the argument from one of them is a compile error,
    not an unhardened container). Shipped: drop-ALL capabilities with a per-workload
    DECLARED add-back (`STRICT` = nothing, used by the instance tier;
    `SERVICE` = CHOWN|DAC_OVERRIDE|FOWNER|SETGID|SETUID, declared by stacks, Docker
    sites and each database `Engine`), `no-new-privileges`, `PidsLimit`
    (`security.container_pids_limit`, default 512), and a REFUSAL of `Privileged`,
    `CapAdd`, `SecurityOpt`, `UsernsMode`, devices, host namespaces and host bind
    mounts. Deliberately NOT shipped, with reasons: `ReadonlyRootfs` (not viable for
    arbitrary images; a knob nobody can set is theater), `NET_BIND_SERVICE` (Docker
    sets `net.ipv4.ip_unprivileged_port_start=0` in every container, so it grants
    nothing here) and userns remapping (daemon-level `userns-remap` in daemon.json,
    which hohenheim does not own -- the host preflight this section already demands
    is where it belongs). `ContainerHardeningTest` asserts CapBnd/NoNewPrivs/pids.max
    read from INSIDE the running container, not the spec that was sent. STILL OPEN
    from this bullet: per-stack-SERVICE capability declaration (StackSpec.ServiceSpec
    has no field for it) and the host preflight that verifies userns/seccomp/AppArmor.
  - **SUPERSEDED 2026-08-03 (same day, product decision): the instance tier declares
    `SERVICE`, not `STRICT`.** Generic tenant images must work out of the box, and
    `STRICT` rejects the chown-then-drop-privileges entrypoint that is the archetypal
    game-server (and database, and web-server) image -- measured through the real
    instance kind: postgres:17-alpine dies at entrypoint with `chmod:
    /var/lib/postgresql/data: Operation not permitted` / `error: failed switching to
    'postgres': operation not permitted`. What moved is ONLY the capability add-back
    list, empty -> the same five three other tiers already run with. What did NOT
    move: drop-ALL as the base, `no-new-privileges`, the pids cap and every structural
    refusal, all re-asserted for the instance profile from kernel state inside a
    running container (`ContainerHardeningTest.instanceTierRunsAChownThenDropPrivileges
    Image` incl. a RESTART lap, `theInstanceProfileStillRefusesEveryStructuralEscape`).
    A capability profile is an IMAGE-SHAPE declaration, not a trust declaration: the
    tenant-vs-operator boundary is the structural refusals, the pids cap, and the
    per-tenant network policy in the next bullet, which is still NOT BUILT. Consequence
    to keep honest: NO production kind declares `STRICT` any more -- it survives as the
    unconditional base of every profile and as what the tests pin, not as a policy
    anything uses.
- **Per-tenant networking is essentially absent.** `NftService` covers IP BANS
  only (one input-hook chain, timeout sets) -- no forward chain, no per-tenant
  chain, no egress rule, no metadata-range deny, nothing about container IPv6.
  Two traps in it, both self-documented: it is DEFAULT-OFF
  (`security.nftables_enabled`) and ALL nft failures are logged loudly but NEVER
  thrown. A network policy materialized through that class returns normally on a
  host where nothing was applied, and the container starts unisolated. The
  Phase 3 network applier must be a separate class with a THROWING contract, or
  `NftService`'s contract is fixed and every ban call site re-verified. Only
  stacks get their own Docker network; sites and databases share the default
  bridge.
  - **PARTIALLY LANDED 2026-08-03 for the INSTANCE tier only (re-verify, do not
    assume, and read the scope line):** per-WORKLOAD (not yet per-tenant) private
    networking. Shipped: `WorkloadNetworkPolicy` -- a SEPARATE class from
    `NftService` in its own `inet hohenheim_net` table, every method THROWS on a
    non-zero nft exit, the whole ruleset goes through one atomic `nft -f -`
    transaction, and `apply` returns only after re-listing both chains and finding
    every rule it just wrote (exit 0 is not evidence). Two base chains per workload
    (forward + input, priority -10): established/related accept, own-subnet accept,
    then deny to `169.254.0.0/16`, `10/8`, `172.16/12`, `192.168/16`, plus the
    IPv6 twins (`fc00::/7`, `fe80::/10`) whenever the network has a v6 subnet; the
    input chain denies the workload the host entirely, which is what covers the
    reverse proxy on 80/443 and DNS on 53. `WorkloadNetwork.fromInspect` REFUSES to
    build a policy for a v6-enabled network with no v6 subnet rather than shipping a
    v4-only policy onto a v6-reachable container. One user-defined network per
    instance (`hohenheim-instance-{id}-net`), owner-labelled at creation so the
    reconciler buckets it OWNED, attached in the CREATE body (never a post-hoc
    connect, which would leave the container on the default bridge for an interval),
    with the policy applied and verified BEFORE the container is created; `start`
    re-applies (a reboot keeps the network and drops the nft rules); `destroy`
    removes the chains and the network. When enforcement is off the instance deploy
    REFUSES -- no container, no network -- which is the whole distinction from
    `NftService`. `ContainerHardening` now also refuses `NetworkMode`/`PidMode`/etc
    of the form `container:<id>`, which was the string that opted a workload out of
    all of this.
  - **EXTENDED 2026-08-05 (isolation wave): Docker sites, managed databases and Incus
    system containers now isolated too; egress is a declared fact.** `InstanceNetworks`
    was generalised to `WorkloadNetworks` (unchanged mechanics). `WorkloadNetworkPolicy`
    gained `forServer(name)` -- the nft lane now targets the KERNEL OF THE HOST THE
    WORKLOAD LANDS ON (ssh sudo nft for an SSH-mode host), because a local applier for a
    remote daemon "verifies" rules on the controller while the workload runs wide open;
    `HostPreflight` uses the same `NftRunner.forServer` so what it probes is what a deploy
    relies on. A new `Egress` enum is a KIND-declared fact materialised into the forward
    chain (`OPEN` = restrictive default, `NONE` = a final saddr-scoped drop). `SiteContainerKind`
    is now `PRIVATE`/`OPEN` (a site release container migrates the moment its next release
    deploys -- the release path replaces the container). `ManagedDatabase` takes
    `NetworkPosture`+`WorkloadNetworkPolicy`; the production `DatabaseService` path declares
    `PRIVATE`+`NONE` (an engine has no legitimate outbound-initiated traffic) and REFUSES to
    provision a record-backed DB on a host that cannot enforce -- record-less test/preview
    callers keep `SHARED_BRIDGE`. Incus: `IncusNetworkPolicy` attaches ONE shared
    `hohenheim-isolation` network ACL (egress-reject the same `TenantNetworkRanges`) to every
    instance's `eth0` in the CREATE body, read-back-VERIFIED off the daemon; `IncusPreflight`
    grows a `network_acl` probe (create+read+delete) so a daemon that cannot carry an ACL is
    refused admission; the shared `TenantNetworkRanges` vocabulary is denied identically by
    both backends. Tests: `WorkloadNetworkPolicyTest` gains an egress-NONE journey (netns,
    counterfactual re-declares OPEN); `DatabaseNetworkIsolationTest` (real docker, two redis
    DBs, negative + positive anchor + destroy sweeps the network); `IncusNetworkIsolationLiveTest`
    (daystrom, two subjects, A cannot reach B over v4 AND v6, positive anchor 1.1.1.1, host
    gateway denied -- opt-in via `~/.config/hohenheim-livehost`, skips green elsewhere).
    Proven on daystrom (Incus 7.3, Docker 29.7.1): baseline A<->B reachable v4+v6, ACL applied
    A blocked v4+v6, DNS+internet intact.
  - **STILL OPEN and deliberately so:** STACKS remain on their per-stack network without the
    metadata/host deny policy -- they already get cross-tenant isolation from their own bridge,
    but a stack service can still reach the metadata address and the host; applying the throwing
    policy to stacks means stacks refuse on a host without enforcement (a behaviour change for
    operator workloads that needs netns fixtures across the stack test suite), so it is a named
    separate slice (`StackDeployer.ensureNetwork` is the seam). SUPERSEDED 2026-08-06: the
    `DatabaseEnvInjection` half LANDED -- Docker sites attach databases again via a per-pair
    LINK network (`SiteDatabaseNetworks`, `hohenheim-dblink-{site}-{db}`, Egress NONE, joined
    between create and start on the `InstanceService.deploy` seam; the attachment ROW is the
    authority: the source fingerprint folds the link set so attach/detach converge through a
    release, the drain sweep removes stale networks, and the injected env is CONTAINER_NETWORK
    style: DB container hostname + engine port, never `127.0.0.1`). Cross-host attachments are
    refused BY NAME (validator + deploy path); host-process sites keep the loopback style
    unchanged (`EnvInjectionFlowTest` pins it); proven end-to-end by `SiteDatabaseLinkLiveTest`
    (real client in the site container queries over the injected address; unattached DB stays
    unreachable with a positive anchor). Per-TENANT
    grouping (one network per packed manage-subject set rather than per workload) and a
    per-workload egress ALLOWLIST (beyond the OPEN/NONE binary) remain future tightenings.
    SUPERSEDED 2026-08-06 (second wave, same day): the STACK half LANDED too --
    `StackDeployer` now takes a `WorkloadNetworkPolicy` (routed `forServer(spec.serverName())`
    by `StackRuntime.deployerFor`), `ensureNetwork` refuses FIRST (`requireEnabled` before
    anything reaches the daemon) and applies the verified deny to the per-stack network on
    EVERY deploy, adopted networks included (adoption must never opt a stack out of the
    denies); egress is DECLARED `Egress.OPEN` at the tier (`StackDeployer.EGRESS`, AIDEV-NOTE
    with the reasoning: operator-authored compose content legitimately calls out; the denies
    still hold). Destroy removes the chains; when enforcement is OFF it skips them by
    DECISION so a pre-enforcement stack stays deletable (deploy refuses, stop/status/destroy
    keep working -- pinned by `aPreEnforcementStackStopsAndDestroysButNeverRedeploys`).
    Tests: `StackDeployerTest` (class netns; kernel read-back of the real subnet's chains,
    refusal-with-clean-daemon-state, pre-enforcement journey), `StackRuntimeFlowTest`
    (product lane: FAILED status + refusal in the deployment log + recovery once enforcing),
    `StackNetworkIsolationTest` (REAL packets against the exact chains a REAL deploy applied:
    metadata + host BLOCKED, same-subnet sibling + public REACHABLE, remove-policy
    counterfactual). Stale-claim fix in passing: `NetworkPosture`/`DockerInstanceRuntime`
    docs claimed a migration-stamped per-stack SHARED_BRIDGE posture that was NEVER built;
    corrected -- no per-record posture exists and none is needed (no live installs).
    NAMED, still open, different mechanism required: MANAGED PROCESSES (host-process sites)
    are the remaining tier that can reach the metadata service and host-local services. The
    tenant-network model genuinely cannot cover them -- they live in the host's own netns, so
    there is no subnet to key saddr on and locally-originated traffic never traverses the
    forward hook. A fix is an OUTPUT-hook policy keyed on the per-site run-as uid
    (`meta skuid` against the same `TenantNetworkRanges`) or per-process network namespaces;
    either is its own slice, not a StackDeployer variant.
    Also named 2026-08-06: the REBOOT re-apply gap for STACKS specifically. Docker networks
    survive a reboot, nft chains do not; instances re-apply on `start`, managed-database
    containers carry no restart policy (down after a reboot until re-provisioned, which
    re-applies), but stack services default to `unless-stopped`, so after a host reboot the
    daemon restarts them WITHOUT their chains until the next deploy (a redeploy DOES restore
    them: `ensureNetwork` re-applies on every deploy, including onto an existing owned
    network). A boot/monitor re-apply sweep is the fix; it is a monitor-tick concern
    (`StackRuntime.refreshStatus` is the natural seam), its own slice.
    SUPERSEDED 2026-08-06 (reboot-sweep wave): the sweep SHIPPED, as `VerifyDockerIsolation`
    (boot + every 5 minutes, the VerifyIncusIsolation shape) rather than a
    StackRuntime.refreshStatus hook -- refreshStatus is STACKS-only and record-status-shaped,
    while the reboot window is a KERNEL concern shared by five inventories (stacks, instances,
    site releases, managed databases, both link-network families), so one sweep owns them all.
    What the wave established, in order:
    - PER-TIER SURVEY, measured on daystrom (real Docker daemon, real reboot), not inherited
      from the three waves' notes. A DAEMON RESTART is NOT a hole for any tier: kernel
      nftables state survives it (the table, both chains and every rule still listed by
      nft afterwards), unless-stopped containers are restarted still-policied,
      restartless containers stay down. A HOST REBOOT is the
      hole, for exactly ONE tier's runtime shape: networks and subnets survive, `inet
      hohenheim_net` is GONE ("No such file or directory", captured), and only
      unless-stopped containers -- the stack tier's default -- are restarted by the daemon
      with no code path of ours in the loop. The rebooted stack container fetched HTTP 200
      from a host-bound service that the policy denies; re-applying the exact production
      ruleset text turned the same fetch back into a timeout with 1.1.1.1:443 still
      connecting (the positive anchor). Instances, site releases and managed databases
      carry restart policy "no" (only StackDeployer sets RestartPolicy at all), so they
      come back STOPPED and their next start/deploy re-applies -- a finding, not a gap;
      the sweep covers them anyway because record-says-RUNNING with chains missing is one
      operator `docker start` from live. Link networks re-apply only inside deploy lanes,
      but their members are restartless too, so the reboot exposure is the same
      records-vs-kernel divergence the sweep now bounds. BuildSandbox networks are
      minutes-lived inside one build and excluded by decision.
    - MECHANISM: ONE sweep, `VerifyDockerIsolation` (server/task), records-driven inventory
      per host -- running/starting docker-kind instances via `InstanceService.resolve`
      (posture/egress read off the kind-built `DockerInstanceRuntime`), enabled stacks
      (`StackDeployer.EGRESS`), active managed databases (egress NONE), and both link
      owners' new `liveLinkHandles()` enumerators (`SiteDatabaseNetworks` NONE,
      `GameDomains` OPEN). Kernel truth rides the new `WorkloadNetworkPolicy.isEnforced`
      (re-lists both chains through the SAME NftRunner lane the deploy used --
      `forServer`, never the controller's kernel for a remote host) and repair is the
      existing verified `apply`. Incus hosts are skipped by name: VerifyIncusIsolation
      owns them.
    - REPAIR-FAILURE POLICY, decided per condition: enforcement OFF = report unverifiable,
      touch nothing (the pre-enforcement decision); kernel UNREADABLE = report UNCONFIRMED,
      touch nothing (refusing to answer is not evidence of a leak); divergence OBSERVED and
      re-apply REFUSED = contain -- stacks/instances/databases are STOPPED (stack stop goes
      through StackDeployer directly, deliberately not the per-stack worker lane: an
      emergency stop must not queue behind a wedged deploy), link networks are SEVERED by
      disconnecting their members (both endpoints keep running on their own policied
      networks; the next deploy re-attaches enforced). Repair runs first, so transient
      failures cost no availability.
    - PROVED vs APPROXIMATED: the hole and its close were proven across a REAL host reboot
      of daystrom (fixture: production ruleset text + unless-stopped container). The
      product sweep itself was proven in `VerifyDockerIsolationTest` against a real daemon
      and a real kernel where the reboot's kernel effect is reproduced exactly (delete
      `table inet hohenheim_net` under running containers): all four tiers repaired by one
      sweep, kernel read back, metadata BLOCKED by real packets with the open egress still
      REACHABLE, second tick idempotent; plus the three failure lanes (off/unreadable/
      unrepairable-stops-the-stack, daemon-verified). NOT proven: the product sweep running
      on an actually-rebooted host (the JUnit lane cannot reboot its host), and boot-time
      task scheduling (the framework's bootAndCron contract). Counterfactual: a sweep
      mutated to claim repair without applying fails both tests verbatim (kernel ruleset
      empty where the metadata drop is asserted; no refusal on the record).
    - MANAGED PROCESSES stay a NAMED SEPARATE SLICE, judged again this wave and kept out
      deliberately: the uid-keyed OUTPUT rule is NOT a clean fit for this sweep's unit (a
      policied NETWORK) or its vocabulary -- host processes resolve DNS through
      /etc/resolv.conf, which on real hosts names a PRIVATE-range resolver directly (the
      dev workstation's is 10.47.0.2; daystrom dodges it only because systemd-resolved's
      127.0.0.53 loopback stub fronts its 10.47.0.1 upstream), so a bare `meta skuid` deny
      of TenantNetworkRanges breaks such a host's process-site DNS; the container tiers
      never faced this because Docker's embedded resolver lives inside the container netns. The slice therefore needs a resolver carve-out
      decision (which holes to punch and how they track resolv.conf changes) AND a
      privileged test lane (real uids; `unshare -rn` maps the test user to root, so
      PrivateNetns cannot honestly exercise skuid matching). Seam unchanged:
      `SystemUsers.executionBuilder` uids, OUTPUT-hook chain in the same table, or
      per-process netns.
- **Already done, do not re-schedule:** `KnownCapabilities`/sensitivity classes
  (zenit core, wired -- Phase 3 only needs to REGISTER the instance vocabulary,
  which is an hour, not a workstream); `RecordGrants` + the grant-scoped
  `/manage` resource precedent; control-plane recovery. **Caveat on that last
  one:** `ControlPlaneBackups` exists and is verified, but its destination is a
  LOCAL directory and it explicitly does not claim off-host transfer, while the
  prerequisite below says "off-host target". Either meet it or amend it -- do
  not let the existence of the class close the gate.
- **Still accurate in the plan:** `RecordTabs` hardcodes Edit first, so the
  Overview-home mechanism still needs building; `stack_health` is raised but
  absent from the routable notification vocabulary, so it reaches only channels
  with an empty subscription list (one-line fix, do it in passing).
- **Destroy paths cannot fail today.** `ManagedDatabase.destroy` swallows stop,
  remove AND volume removal, after which `DatabaseService.destroy` deletes the
  record unconditionally -- so an unreachable daemon leaves the container
  running and destroys the only copy of `db_password`. `DockerSiteRequestHandler`
  has the same shape one tier over. Any Phase 3 destroy/drain built by copying
  these inherits a delete that reports success while nothing was reclaimed.

**LANDED 2026-08-03 (re-verify, do not assume): node identity, controller
fencing and host preflight.** Controller identity = the host-lease FENCE (zenit
`Leases`, first production consumer): `HostLeases` holds one lease per host for
the controller's lifetime, every instance runtime-outcome write is ONE guarded
`updateAll` (`claim_fence IS NULL OR claim_fence <= :myFence`, zero rows = hard
`instance_fenced_out` failure, loser aborts WITHOUT touching the ledger), and
`PortAllocator`'s note-token identity is replaced by `controller_fence` on the
claim rows (allocate requires the lease; the boot sweep runs only while holding
it and judges only lower generations -- the live-peer-claim deletion is
structurally gone). Host record grew posture/admission (default BLOCKED,
admit gated on a passing preflight)/capabilities JSON/probed_at/preflight_ok/
last_seen_at/typed last_error(+kind)/host_key_fingerprint (unpopulated, the
pinning wave's home)/controller_version (M056); `ServerService.remove` and every
other delete path refuse while stacks/databases/live instances reference the
host (schema hook -- the FKs are unenforced on the shipped SQLite URL).
`HostPreflight` reads KERNEL truth from a probe container (delegated pids
controller, pids.max == configured, seccomp mode 2, no_new_privs) plus real nft
add/list/delete and a real probe-network create; the report is STORED on the
row. Proven by two-controller counterfactuals (`HostFencingTest`: stalled
deploy cannot stick, with host-state assertions; rival boot sweep deletes
nothing). NOT yet fenced: stack/site/database outcome writes, scheduled-task
outcome writes, ACME/monitor/nft loops; destroy's deleted_at save rides the
save path for the quota hook (fence proven by the guarded stamp immediately
before). Explicit orphan authority: `OrphanActions.removeOrphan`
(re-verified live, volumes refused, ActivityLog) on `ReconcileFindingResource`.

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
  runtime operation records operation id, desired generation and fence.
  CORRECTED 2026-08-02: the original wording here said "a stale controller
  cannot create/start/destroy after losing its lease". That is NOT ACHIEVABLE
  and must not be written into the gate -- the Docker daemon obeys a stale
  controller happily, and no token we mint changes that. The achievable and
  therefore binding claim is: a stale controller cannot create/start/destroy
  AND HAVE IT STICK. The database is the fence (every write recording a runtime
  outcome is conditional on `lease.fence = :myFence`), and the daemon is
  RECONCILED (the winning controller lists resources by ownership label and
  quarantines or adopts what it did not record). A gate step that asserts a
  rejected DB write and calls it proof no container started is the dominant
  defect shape pre-installed into the acceptance criteria -- every runtime gate
  step must assert HOST state (no container, port not bound, no nft rule)
  alongside the API refusal, because in this codebase "the API refused" and
  "nothing happened" are currently independent facts. Reconciliation is only
  possible once containers carry ownership labels, which today only stacks do.
  Operations are idempotent and resumable after crash, with explicit
  retry/cancel/dead-letter states and orphan quarantine when live truth is
  ambiguous.
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

CORRECTED 2026-08-02, and this supersedes the field list above: **`InstanceModel`
gets NO `owner_principal_id` column.** Ownership in this product is ALREADY
grant-derived and has been since Phase 1 -- `HohenheimAccess.sameOwner` compares
the truthy `manage`-grant SUBJECT SETS of two records and treats set equality as
same-owner, and the route-overlap invariant is built on exactly that. An owner
column would be a SECOND authority answering the same question, and it drifts
the first moment a grant is added or revoked without the column following. Quota
per owner, anti-affinity per tenant and placement all key on the SAME
grant-derived owner that `sameOwner` computes, or they will disagree with the
access checks. This also means "decision 7 (tenancy shape)" is not an open
question to be decided fresh -- it is ALREADY ANSWERED IN CODE, and the only
legitimate moves are to ratify that derivation or to deliberately replace it
everywhere at once. Never let a new column become a quiet second answer.

RECON 2026-08-03, verified in source, and it makes the decision above
UNIMPLEMENTABLE AS WRITTEN until one refactor lands: `HohenheimAccess.sameOwner`
is SITE-ONLY. Its signature is `sameOwner(int firstSiteId, int secondSiteId)`
(`HohenheimAccess.java:178`) and its body passes `SiteModel.MODEL_ID` as a
LITERAL (`:191`). `RecordGrants.listForRecord` already takes
`(Identifier model, Object recordId)`, so the fix is small: generalize to
`manageSubjectsOf(Identifier, Object)` + `sameOwner(Identifier, Object, Object)`
with the two-int form delegating, so exactly ONE derivation survives. There are
exactly two production call sites (`SiteDomainResource.java:287` and `:361`).
C7's FIRST step is this refactor -- because the path of least resistance is a
local `instanceSameOwner(int, int)`, and that is precisely the second authority
the no-column decision exists to prevent.

WORSE, and this is the likeliest place C7 becomes security theater: `manage` is
registered as a capability for `SiteModel` ONLY (`HohenheimAccess.java:90-99`;
`DnsRecordModel` at `:115` and `CertificateModel` at `:140` declare
view/edit/dyndns/request and NO manage). Until `InstanceModel` registers a
manage-equivalent capability AND is declared grantable, `sameOwner` on instances
compares two empty sets and answers "same owner" for every pair -- a tenancy
check that CANNOT FAIL. Register the capability in the same commit as the model.

CONSEQUENCE the field list does not draw out: two operator-owned records always
compare same-owner because BOTH carry the empty set (`HohenheimAccess.java:172-177`
documents this deliberately). So a per-owner quota keyed on the grant-derived
owner collapses every operator-owned instance into ONE bucket. That is arguably
correct for an operator install, but the quota key must then BE the canonical
subject set, and "the operator" is one quota subject. State it, or the first
quota test will disagree with the first access test.

STATED AND SHIPPED 2026-08-03: the quota key IS the canonical packed
manage-subject set (`HohenheimAccess.packSubjects`, the ReleasedClaims packing
extracted to the one authority), bucket `hohenheim:instances:<packed>`, and the
empty set -- the operator -- is ONE bucket, deliberately. Two consequences worth
knowing: at CREATE time no record and therefore no grant exists yet, so every
create charges the creation-time owner (today always the operator bucket); and
the bucket a record was CHARGED to is stamped on the row
(`instances.quota_bucket`) so the release stays exact when grants move ownership
afterwards -- that column is reservation bookkeeping, NEVER a second ownership
authority. Follow-up owed with tenant self-service creation: a create flow that
grants in the same pipeline (or migrates the charged bucket on grant changes) so
tenant creates charge tenant buckets.

RECON 2026-08-02: what a new `InstanceModel` COLLIDES with, verified in source.
There are THREE container authorities today and only ONE of them asserts
ownership in-band: `StackDeployer` labels its containers
(`be.elevenways.hohenheim.stack`) and REFUSES to touch a same-named unlabelled
resource, whereas `DockerSiteRequestHandler` (`hohenheim-site-{id}`) and
`ManagedDatabase` (`hohenheim-db-{name}`) carry NO labels and FORCE-REMOVE a
same-named container, swallowing the failure. So an instance named into
collision with a site's container gets that site's container destroyed by
whichever authority deploys second. "Which host" has three incompatible
spellings (`StackModel.SERVER_NAME` string, `DatabaseModel.SERVER_NAME` string,
Docker sites' `settings["server"]` map key) and `ServerModel.ID` is used as a
foreign key by NOTHING -- so a fourth spelling via `server_id` leaves any
placement or capacity allocator blind to three quarters of the load actually
consuming the host. Volumes are owned by naming convention only, so deleting a
site record already orphans `hohenheim-site-{id}-vol-*` with nothing pointing at
it. `DockerReclaim`'s attribution-based image sweep is driven by STACK-declared
image references and does not know instance images could exist.

CONSEQUENCE FOR SEQUENCING -- the smallest honest first slice is NOT "an
instance that starts". A happy-path container start is achievable today by
copying `DockerSiteRequestHandler`, and doing that FIRST is precisely how this
becomes a fourth parallel record type that runs containers alongside Site,
Stack and Deployment. The slice is: ONE persistent `port_allocations` table plus
ONE `be.elevenways.hohenheim.owner = {model}:{id}` label convention, retrofitted
onto all four existing authorities, with a reconciler that lists by label and
reports owned / orphaned / foreign -- and THEN `InstanceModel` as the FOURTH
consumer of that shared machinery, running one container on the local host,
fenced by the core lease. Every prerequisite that slice does not yet satisfy
(multi-host posture, per-tenant networks, quota) is then ADDED TO SHARED
MACHINERY rather than retrofitted across four divergent authorities. This is
what makes the product one whole instead of a well-engineered federation.

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
    CORRECTED 2026-08-03: keyed by the PACKED manage-subject set, never by
    principal -- a principal-keyed row would be the second, disagreeing owner
    derivation the no-owner-column decision bans. Shipped as `instance_quotas`
    (M055) + the `hohenheim.quota.max_instances_per_owner` default; the
    `hohenheim.instances.create` permission and placement authority are STILL
    OPEN.
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

  RECON 2026-08-02, verified in source: `port_allocations` DOES NOT EXIST. It
  occurs exactly once in this repository -- in the future-tense prose above.
  Any handoff or status note claiming it "already absorbed" the other consumers
  is FALSE; nothing has been absorbed. There are FOUR independent port
  authorities today: (1) `PortAllocator` for managed processes -- an in-memory
  `ConcurrentHashMap`, lost on restart, TCP-only, with a real TOCTOU against
  the OS (the probe socket closes before the claim, so `putIfAbsent` guards
  only same-JVM threads) and a singleton only by virtue of one static field
  that `JavaSiteType`/`CommandSiteType` happen to borrow; (2) ephemeral Docker
  publication for Docker sites, read back and never persisted; (3) ephemeral
  Docker publication for managed databases, likewise; (4) operator-declared
  `StackServiceModel.PORT_HOST` for stacks. `StackServiceResource.validatePorts`
  compares a new claim only against OTHER STACKS -- it is blind to the other
  three authorities, and it runs read-then-save on the CMS form path with no
  transaction and no unique constraint, so two concurrent submits both pass.
  That validator reports "no conflict" while three quarters of the load is
  invisible to it: a check that cannot catch the conflicts it exists for.

  RECON 2026-08-02 (second pass) found FIVE more authorities/facts that change
  the design, all verified in source or on the live daemon:

  - **`RouteClaims` is the reference implementation of an exclusive claim in
    this repo and MUST be read before building this.** Derived claim-key column
    + UNIQUE index + NULL-means-no-claim + `DuplicateKeyException` caught and
    rethrown as a NAMED conflict that can say who holds it + heal-don't-brick
    backfill. `M044_SystemUserClaims` is the same shape again. Not reading them
    first is how this ships a third spelling of a solved problem.
  - **`Model.insertIfAbsent` is PRIMARY-KEY-ONLY** (verified: it throws when the
    row carries no explicit PK, and refuses rows with pending localized or
    schema-record writes). `Model.getPrimaryKeyField()` returns ONE field, so a
    unique constraint on `(server, ip, port, protocol)` is NOT reachable by
    `insertIfAbsent` unless that tuple IS the primary key.
    `DuplicateKeyException.isPrimaryKeyConflict` exists specifically to refuse
    attributing a non-PK unique violation to a lost `insertIfAbsent` race.
    `find().increment().updateAll()` has NO role here -- a port claim is an
    exclusivity assertion, not a counter.
  - **`assertUnique` on live data is an availability bug** (`M043`'s own
    AIDEV-NOTE): a pre-existing duplicate becomes a control plane that will not
    boot. Heal-then-constrain via `schema.data(...)`, as `M045` does.
  - **A managed-process port is owned by a PROCESS INSTANCE, not by a site.**
    `allocate` happens inside `startProcessOnce` and `release` on every exit
    path, so a site with capacity N holds N ports and a crash-loop churns them.
    Also: `use_ports` DEFAULTS TO FALSE (the default path is a unix socket), so
    `PortAllocator` is on the minority path.
  - **Two more port consumers no ledger will ever contain:** `IpcChannel` opens
    `new ServerSocket(0, ...)` per managed-process spawn (kernel-ephemeral,
    loopback, outside `PortAllocator` entirely); and the machine's testcontainers
    publish into the kernel ephemeral range 32768-60999 on `0.0.0.0`.
    `PortAllocator`'s window (4748+) does not overlap TODAY, but `FIRST_PORT` is
    operator-configurable and an existing test sets it to 24748. The OS probe is
    the ONLY thing that can see non-hohenheim consumers, and only at the instant
    it runs.
  - **The org already has an owner-label convention:** the live testcontainers
    carry `be.elevenways.zenit.testdatasources` + `.backend`, i.e. scope +
    discriminator -- the same shape `StackDeployer` uses. The reconciler must
    classify those as `foreign-known`, or every dev machine's attention list
    opens with six false alarms and operators learn to ignore it.

  DECIDED 2026-08-02 -- the four forks, resolved. Binding.

  1. **LEDGER TABLE, not a claim-key column**, despite the house precedent. The
     tiebreaker is the managed-process case: a process-instance port has no
     record to hang a column on. A table also carries the `releasing` state,
     cross-authority capacity queries, and ports whose owner is not a record.
     Put a one-line AIDEV-NOTE in `RouteClaims` recording WHY it did not move,
     so the second spelling is visibly deliberate rather than drift.
  2. **RECORD-AFTER is the default; explicit pre-allocation is a DECLARED mode.**
     One ledger, one claim primitive, two acquisition strategies behind an
     explicit discriminator -- never two code paths. Record-after wins by
     default because pre-allocation does not remove the TOCTOU, it adds a second
     one seconds wide (an image pull can sit inside it) and, for a REMOTE host,
     is an unevidenced guess: `isPortFree` binds a local socket, so probing a
     remote host answers a question about the controller. Pre-allocation is
     required where the number must be known in advance (UDP, game servers --
     note `publishedPort` hardcodes `/tcp`, so record-after cannot serve UDP at
     all). **The label must land at container-CREATE time, before the port
     exists** -- that is what makes record-after honest, because it lets the
     reconciler find a container whose claim row was never written.
  3. **RESERVED-UNTIL-OBSERVED, not optimistic release.** Three states: `held`,
     `releasing`, absent. Every swallowed-IOException teardown path lands in
     `releasing`; only the reconciler, having OBSERVED the port free, deletes
     the row. Optimistic release re-creates the exact bug the ledger exists to
     prevent ("we said it was free and it was not") one layer up. A `releasing`
     row that never clears IS the alarm and belongs on the attention list.
     Soft delete must NOT release (a soft-deleted site is restorable, and
     `RouteClaims.isLive` is the precedent for keying on live-ness). Host
     removal moves claims to `releasing`, never deletes them -- a `servers` row
     vanishing does not free ports on the physical machine.
  4. **ONE canonical host-key derivation, AND the FK, in this wave.** The
     concrete live bug is that the local daemon is spelled both `""` and
     `"local"` in three separate normalisations, which would give the ledger two
     disjoint claim sets for one machine while every unique constraint held.
     A canonical token alone fixes that, but "FK later" has a track record here:
     `ServerModel.ID` has been a foreign key to NOTHING since M017. Nothing is
     deployed, so backwards compatibility is not a reason to defer -- do the
     derivation as one function AND migrate `StackModel.SERVER_NAME`,
     `DatabaseModel.SERVER_NAME` and the Docker-site `settings["server"]` key
     onto the FK. Eliminate the spellings; do not add a fourth.

  SEQUENCING -- each commit leaves the tree green and shippable:
  C1 owner labels at every create site, read by nothing (zero risk, starts the
  attribution clock); **C1 restricted to VOLUMES is the smallest first commit
  and the highest value** -- a volume is the only irreversible resource, is
  owned by naming convention alone today, and is the one thing a later
  reconciler cannot retroactively fix. C2 reconciler, REPORT-ONLY, wired to
  `AttentionCollector` + a scheduled task (it will immediately surface the
  `hohenheim-site-{id}-vol-*` orphans nothing has ever named). C3 ledger table +
  canonical host key + FK, with stacks as first consumer, reusing
  `StackServiceResource.portClaim`'s canonical string VERBATIM. C4 record-after
  for the two Docker cases. C5 `PortAllocator` behind the ledger + boot sweep.
  C6 the `releasing` state and destroy paths that stop lying. C7 `InstanceModel`
  as the FOURTH consumer.

  C7 TRAP, verified 2026-08-03: `DockerReconciler.ModelRecords` is a hardcoded
  if-chain, and an unknown model returns `false`, which classifies as ORPHANED
  (`DockerReconciler.java:108-109`, `:266`, `:289`). The moment C7 creates a
  container carrying `OwnerLabels.of(InstanceModel.MODEL_ID, id)`, EVERY LIVE
  INSTANCE is reported as an orphan on the attention list. C7 must teach
  `ModelRecords` the model (with the soft-delete predicate) in the SAME commit.
  A false-positive alarm is how operators learn to ignore the list. Also check
  `classify`'s name-scheme fallbacks: an instance container matches none of the
  `hohenheim-site-` / `-db-` / `-stack-` prefixes.

  C7 ORDER (smallest honest slice, dependency-ordered): generalize `sameOwner`
  first (no instance code); then `InstanceModel` + migration with `kind` as a
  `RegistryEnumField` over an instance-kind registry and `settings` via
  `SchemaField.schemaFrom("kind")` -- ONE discriminator, not kind+runtime, since
  `schemaFrom` takes exactly one sibling, and note `SiteModel.java:96-105` that
  the dynamic form entry REWRITES the whole settings map on every admin save, so
  anything that must survive an admin save is a COLUMN; then declare grantable +
  register the manage capability; then the two `PortLedger` capture/release
  remove hooks; then teach the reconciler; then `InstanceRuntime` (five methods:
  create/start/stop/destroy/status, labels stamped at CREATE, typed outcomes
  where absent != unreachable) plus a Docker driver that WRAPS `DockerClient`
  rather than being it. NO fence or generation columns -- there is nothing to
  fence against yet, and a column that reads like enforcement and enforces
  nothing is the exact defect shape this plan exists to prevent. Streaming
  (stats, follow-logs, attach, TTY exec) is a SECOND transport contract, not a
  patch to `DockerTransport`'s single-shot `byte[] roundTrip`, and it is Phase 6.

  LANDED 2026-08-03 (C7), in the C7 ORDER above, one commit: `sameOwner`/
  `manageSubjectsOf` generalized to `(Identifier, Object)` with the site forms
  delegating; `InstanceModel` (M054, no owner column, no fence columns, kind =
  `hohenheim:docker_container` RegistryEnumField + `schemaFrom("kind")` settings,
  `server_id` FK folded through `canonicalServerId` in beforeValidate);
  grantable + `manage` capability registered in the same commit (counterfactual:
  without it the grant is REFUSED as undeclared and the tenancy check cannot
  fail); the two PortLedger remove hooks (counterfactual: claim stays `held` by
  a ghost); `ModelRecords` resolves instances with the soft-delete predicate
  (counterfactual: live instance classified ORPHANED), and label-less
  `hohenheim-instance-*` names stay FOREIGN_COLLIDING by decision -- the tier
  was born after the labels, so there is deliberately NO name-scheme fallback;
  `InstanceRuntime` seam (five methods, typed `ContainerState` now SHARED with
  `ManagedDatabase.LiveStatus`) + `DockerInstanceRuntime` wrapping DockerClient,
  labels at create on container AND volumes, record-after loopback TCP port;
  `InstanceService` (deploy/stop/verified destroy + soft delete, Violations
  refusals) + admin `InstanceResource` behind a new `roles.instances` (also
  gates the boot daemon probe and joins the reconciler schedule). Proven by a
  real-daemon deploy/stop/redeploy/destroy journey asserting HOST state, plus
  the foreign-name refusal. Still owed from this section: quotas, create
  authority, hardening, per-tenant networks, console/exec, /manage projection,
  `instance_variables`, UDP pre-allocation mode.
  SUPERSEDED (2026-08-04): the UDP pre-allocation debt is CLOSED. M063 added the
  ledger's declared discriminator (`port_allocations.allocation_mode`,
  `preallocated`); the kind settings grew the FULL port requirement
  (`port_protocol`, `port_exposure`, optional `host_port` beside
  `container_port`), and any non-(loopback/tcp/ephemeral) shape claims its host
  port in the ledger BEFORE container create (`PortPublications.ensureClaimed`),
  window `hohenheim.instances.public_port_first/count` (30000+2000 default),
  ledger-then-OS-probe order, LOCAL-only probe (a remote probe answers about the
  controller). The daemon's binding is read back after start and VERIFIED against
  the declaration (`verifyPublished`: wrong bind address or wrong number stops
  the workload and refuses the deploy). Pre-allocated claims survive STOP (the
  stable number is the point), are never parked by failure paths, and die only
  with `releaseOwnerFully` on verified destroy. Loopback stays the default and
  the only record-after lane; proven by `PublicPortLiveTest` (real daemon:
  loopback negative connect, public reach from a non-loopback address, UDP
  round trip, fixed-port ledger refusal with NO container created) and the
  PortLedgerTest race (two concurrent pre-allocations, rows as arbiter).

  REGRESSION CAUGHT AND FIXED 2026-08-03, second pass over the LANDED claim
  above: `roles.instances` defaults to enabled (like every role), and
  `RoleRestrictedBootTest` hand-listed the roles it switched off -- so the
  DNS-only boot silently kept INSTANCES on and the shared daemon probe
  constructed a DockerClient (expected 0 constructions, observed 1). Fix:
  `HohenheimRoles.Role` gained a public `setting()` accessor and the test now
  ENUMERATES `Role.values()` to declare its complete role set, so a future
  role addition can never silently re-enable a subsystem in that boot. The
  three named C7 counterfactuals were also demonstrated for real in this pass
  (capability registration: grant REFUSED as undeclared, fail-closed at the
  framework, so the empty-set theater cannot even be staged; ModelRecords
  branch: live instance ORPHANED; remove hooks: claim stays held by a ghost,
  never parked in releasing).

  RISK: C5's boot sweep is the one most likely to break a dev environment
  SILENTLY. Managed-process children are spawned via `ProcessBuilder` with no
  evidence they die with the controller; if any survive, the sweep frees a port
  still bound, the next allocate hands it out, the child dies EADDRINUSE -- and
  `startProcessOnce` RETRIES on address-in-use, so the symptom is a slower
  startup and a retry log, not a failure. Keep the OS probe AFTER the ledger
  check for exactly this reason.

  RECONCILER AUTHORITY: it may delete `releasing` rows whose port it OBSERVED
  free, and it may report. It may NOT remove any container, volume or network
  (autonomous orphan-volume removal is a data-loss primitive; `DockerReclaim`
  already refuses volumes for this reason) and it may NOT apply labels --
  adoption is an explicit, `ActivityLog`-recorded operator action, per the
  `StackDeployer.adoptResources` precedent. It must run as a SCHEDULED task and
  PERSIST its findings; `AttentionCollector` reads the stored result, because
  that collector's own docblock refuses per-render host probing as an O(hosts)
  network operation.

  COVERAGE HOLE, state it rather than discover it later: every Docker test
  `assumeTrue`s on a daemon socket, so on a machine without one they SKIP and
  the run is green with zero coverage of the entire record-after design.
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

  STATUS (2026-08-03): the SNAPSHOT/BACKUP half LANDED, proven on the DOCKER
  driver (no Incus daemon exists on any machine in the loop -- the Incus driver
  is a separate wave). What shipped: `VolumeSnapshotSupport` (the driver seam's
  snapshot half; a driver that lacks it gets a named `snapshots_unsupported`
  refusal, never a no-op; DockerInstanceRuntime implements it over the archive
  endpoints with owner-label-verified volume removal), `InstanceSnapshots`
  (cold capture -- the EXPLICIT consistency model, workload stopped for the
  copy and redeployed through the ordinary funnel -- plus verified in-place
  restore that REPLACES volumes, never merges), `InstanceBackups` (encrypted
  `.hib` export: zip of `manifest.dry` + volume tars, AES-256-GCM whole-file
  under the field-encryption keyring, per-payload sha256 pins inside the
  manifest; restore-to-NEW runs the FULL create story incl. quota reservation,
  admission and the fenced deploy), the `BackupTarget` seam (staging-then-
  commit, target-side sha verification) with `filesystem` and `ssh` kinds
  (registry-driven type enum, admin resource + live connection test),
  count-based per-instance retention (the database-backup pattern), statuses
  `capturing`/`restoring` gating deploy AND stop (destroy stays ungated:
  cleanup doctrine), `snapshots`/`backups` capabilities registered WITH their
  actions, M058, and the nightly `BackupInstances` task. Proven by
  `InstanceSnapshotBackupLiveTest` (real daemon: marker round trip incl.
  merge-vs-replace, restore-to-new with own id/port claim/quota slot and
  intact secret variables, corrupt-artifact refusal BEFORE any state change,
  interrupted upload leaving a FAILED row and zero artifacts),
  `BackupArchiveTest`, `BackupTargetsTest` (ssh target live against localhost
  sshd -- REAL transport, SIMULATED failure domain; one machine cannot prove
  two). HONEST GAPS, deliberate: `record_schedules` STILL does not exist --
  the nightly task rides the existing TaskService (the BackupDatabases shape),
  per-instance cron waits for the record-schedule mechanism; captures are
  memory-buffered under the `hohenheim.backup.max_archive_mb` cap (true
  streaming is the Phase 6 transport contract); remote-host capacity probes
  ride ssh `df`; consistency hooks beyond cold capture are not built.
  SUPERSEDED (2026-08-04): the `record_schedules` gap is CLOSED -- the
  mechanism landed in zenit core and M061 migrated the nightly-task flag onto
  per-instance backup schedules (see the Phase 5 record-schedule STATUS);
  the other gaps in this list stand unchanged.

  SUPERSEDED (2026-08-05): the `ssh` backup kind no longer addresses a
  free-form `user@host` with its own pasted host-key pin. Its settings are now
  a HOST RECORD reference plus a path, so the destination's identity is the
  host record's pinned, operator-CONFIRMED key and its quarantine state, and
  there is exactly one authority over "is this remote host the one we think it
  is". A quarantined or unconfirmed host is refused as a destination by name
  (`host_quarantined` / `host_key_unverified`), and a non-ssh host is refused
  outright (`host_not_ssh`) rather than selling a controller-local directory as
  off-host. Trust is NOT a second admission axis: a storage host needs the
  TRUST half of the host record only, never a compute preflight or an admit, so
  it stays `blocked` for placement forever and needs no new role column.
  `SshBackupTarget` builds no argv of its own at all -- every exchange goes
  through `HostKeys.sshArgv`, which also brings the per-host client identity,
  so the old ambient-`known_hosts` shape is unexpressible rather than merely
  absent. `BackupTargetsTest` now walks one fixture through unpinned,
  unconfirmed, healthy, quarantined and key-changed against a real sshd;
  `LiveOffHostBackupTest` enrols starfleet as a host record and re-proves the
  off-host round trip through it.

  STATUS (2026-08-05): the INCUS DRIVER half LANDED, proven live against a real
  remote Incus 7.3 daemon (daystrom, https://10.47.1.99:8443). What shipped:
  `IncusClient` over its own transport contract (`IncusTransport`: REST envelope
  + a first-class RFC 6455 websocket lane, hand-rolled because java.net.http
  cannot disable hostname verification per-client nor ride a unix socket;
  HTTPS = pinned server cert + per-host client cert, unix socket locally; the
  raw HTTP/1.1 framing is the shared `Http11` codec DockerClient now also uses);
  the trust ceremony (`IncusTrust` + the shared `HostPins` state machine: scan
  pins UNVERIFIED, typed confirm, mismatch quarantines through the SAME
  HostProbe funnel as ssh, repin lands at the ceremony's bottom; trust-token
  enrollment `POST /1.0/certificates` proven live -- this TLS pair IS the
  node-identity story, see ServerModel.RUNTIME's AIDEV-NOTE); host records
  declare their runtime AS DATA (M070 `runtime`+`incus_url`; client
  construction, `IncusPreflight` (trusted/driver/storage/network checks, the
  observed incus version recorded like docker_version), placement
  (`requiredRuntime` filter), resolve (host_runtime_mismatch) and the admin
  ServerResource all dispatch on it); and `IncusInstanceRuntime` +
  `incus_container` kind: system containers from the images: simplestreams
  server, owner labels on `user.*` config, unprivileged by default with
  privileged as a warned admin flag, ConsoleStreamSupport over the console
  websocket, exit codes REFUSED by name (Incus reports none), snapshots refuse
  `snapshots_unsupported` by name. Proven by `IncusHostLiveTest`,
  `IncusInstanceRuntimeLiveTest` (full funnel deploy/console/stop/redeploy/
  destroy + foreign-name refusal, asserted at the daemon over its own CLI) and
  `HostRuntimeTest`; live tests opt in via
  `~/.config/hohenheim-livehost/incus.properties` (LiveRemoteHost pattern).
  HONEST GAPS, deliberate: the Phase 4 GATE's snapshot/backup half does NOT
  cover incus yet -- VolumeSnapshotSupport's tar-per-volume contract does not
  fit a rootfs-stateful system container; the path is a native-snapshot
  capability seam (Incus snapshots + `/1.0/instances/{n}/backups` export as the
  payload inside the existing encrypted .hib envelope, corruption/interruption
  refusals reused), then the nightly-schedule round trip on a Debian container.
  SUPERSEDED (2026-08-05, second wave): that gap is CLOSED and the Phase 4 gate
  is MET on incus -- see the STATUS below. Still not built, unchanged:
  per-instance network isolation on incus (containers share the
  managed bridge -- the shared_container posture is the operator's declared
  risk), port publications (proxy devices), InstanceFileSupport/FileStaging/
  Install on incus (each refuses by name through the existing funnels).

  STATUS (2026-08-05, second wave): the SNAPSHOT/BACKUP half LANDED ON INCUS and
  the Phase 4 gate is MET, proven live against daystrom (Incus 7.3). What
  shipped: `NativeSnapshotSupport` -- the whole-instance capability seam BESIDE
  `VolumeSnapshotSupport` (a driver implements exactly one; neither = the named
  `snapshots_unsupported` refusal) -- implemented by `IncusInstanceRuntime`:
  pool-resident native snapshots (LIVE, crash-consistent -- the storage driver's
  atomic snapshot is the declared consistency model, vs the volume lane's cold
  capture), whole-instance export (`/1.0/instances/{n}/backups`,
  optimized_storage=false so the tarball restores onto any pool driver;
  temporary daemon backup object deleted after export) STREAMED through new
  transport lanes (Http11 grew a streaming head/body codec;
  upload streams the archive from disk, download caps and streams to disk), and
  import-as-new (`X-Incus-Name`) whose contract INCLUDES re-identification: one
  definition write replaces the source's owner labels with the new record's and
  drops volatile `*.hwaddr` keys (the daemon refuses to start a clone beside its
  still-running source otherwise -- found live, "MAC address already defined").
  PAYLOAD-KIND DECISION: the backup manifest carries an explicit `payload` fact
  (`volume_tars` | `instance_export`) written by the capture seam; restore
  dispatches on IT and refuses unknown values whole -- `kind` stays the
  authority over how the instance RUNS, `payload` over how bytes unpack, one
  authority per fact (AIDEV-NOTE in BackupManifest). Snapshot rows grew
  `native_name` (M071); restore verifies the daemon still HOLDS the snapshot
  before touching live state (capacity deliberately skipped: a pool rollback
  moves no bytes onto the host); `RestoreCapacity` dispatches on the host's
  declared runtime (incus = default-profile root pool's space). DATA-LOSS FIX
  found by the gate: `IncusInstanceRuntime.create` now CONVERGES onto an
  existing OWNED instance (rewrites managed config keys, keeps the rootfs)
  instead of replace-from-image -- the Docker replace semantic was silent rootfs
  loss on every redeploy of a stopped incus instance. Proven by
  `IncusSnapshotBackupLiveTest` (opt-in via the same incus.properties; skips
  green elsewhere): a DEBIAN container, the nightly cron schedule executing the
  snapshot action through the real chain runner, snapshot-restore round trip
  (replace not merge, marker asserted over the host's own CLI, which also
  proves converge -- a recreate would wipe it), OFF-HOST backup (the instance
  host is REMOTE, the filesystem target is the controller: a different failure
  domain by construction, the plan's stated floor), restore-to-NEW (own id, own
  quota slot, settings + secret variable + data intact, daemon attributes the
  import to the NEW record), corrupt-artifact refusal with step 7 as its
  positive anchor (no record, no quota, no daemon instance), interrupted upload
  (FAILED row, no artifact, no .part debris, not restorable, source keeps
  running), snapshot delete verified AT the daemon. Three counterfactuals
  failed as required (no-op restore, label re-stamp dropped -- caught by the
  foreign-instance guard itself -- and cleanup skipped). FIXED IN CORE while
  here: `Violations.getMessage` now carries the message ARGS (the WHY was
  invisible in every log/test report; rejected VALUES stay hidden), pinned by
  ViolationLoggingTest. Also: reinstall now refuses `install_unsupported`
  BEFORE the clear-policy volume branch (the honest refusal for a driver with
  no install lane).

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

  STATUS (2026-08-04): LANDED, mechanism in ZENIT CORE (`common/task/record` +
  `server/task/record`: `zenit_record_schedules`/`_steps`/`_runs`, fenced chain
  executor under per-schedule `Leases`, `RunRecordSchedulesTask` sweeper riding
  the TaskService claim, run_as re-authorized per step via the new
  `StoredPrincipalResolver` + `WebSocketAuthenticator.hasCapability` walk).
  Hohenheim wires the vocabulary (`server/schedule`: console_command/power ->
  manage, backup -> backups, snapshot -> snapshots), the admin surface
  (Schedules tab, schedule/step/run resources) and M061 (backup_enabled ->
  per-instance schedules; `BackupInstances` deleted). Proven by
  `RecordSchedulesTest` (zenit, counterfactualed: revocation, ordering,
  offsets, failure policy, rival fencing) and `InstanceScheduleLiveTest`
  (real daemon: warn-then-restart chain, live grant revocation stops the
  chain in HOST state, backup artifact, schedules die with destroy's
  SOFT-delete). Console/power steps ride `manage` because no narrower
  console/power capabilities exist yet -- when those land WITH their
  enforcing surfaces, the action definitions narrow in one place.
- Tenant database allocation uses the existing managed-database tier through a
  record-scoped quota and ownership link; it never exposes another tenant's host
  or credentials. Instance transfer between eligible hosts is a durable,
  capacity-reserved operation with rollback and port reallocation; cold transfer
  is the required floor, live migration is not implied.
- Define the tenant-facing instance API in the same phase as these actions. It
  uses the exact record-capability funnel and safe projection as /manage; HTML
  routes are not the automation API.

  STATUS (2026-08-04): LANDED, both halves, plus the creation-authority decision
  the plan left open.

  THE `/manage` PROJECTION. `ManageInstanceResource` (grant-scoped list/detail,
  name + crash policy only, no create, no delete, power/snapshot/backup row
  actions), `ManageInstanceScheduleResource` + `...StepResource` (the admin
  editors narrowed by a READ scope they never had -- the base step resource was
  ALLOW_ALL, correct in an admin panel and a cross-tenant list in a delegated
  one), `ManageInstanceSnapshotResource` / `ManageInstanceBackupResource` (scoped
  by the capability their own actions demand, not by manage) and
  `ManageInstanceTemplateResource` (APPROVED templates only, name/description/
  version only -- the install script is the recipe and routinely carries pasted
  credentials). Panel ELIGIBILITY now counts instance grants: keying it on sites
  alone locked a pure instance tenant out of the panel built for them.
  `RecordSourceRegistry.override` for `InstanceModel` and `RecordScheduleModel`
  closes the same two-panel shadowing hazard sites/domains already documented.

  THE FUNNEL. Authorization moved ONTO the services, not into the surfaces:
  `HohenheimAccess.requireOperationCapability` gates
  `InstanceService.deploy/stop/destroy` (manage), `InstanceSnapshots.create/
  restore/delete` (snapshots) and `InstanceBackups.backupNow/delete` (backups)
  for TENANT-ORIGINATED calls only, so the row action, the API and any later
  caller answer to one policy. `TenantWrites.inAuthorizedOperation` is the
  continuation scope a backup's internal stop/redeploy runs in (otherwise a
  backups-only holder could back up a stopped instance and not a running one).
  Refusals never name the missing capability -- that would be a capability
  oracle -- and are the same text a caller gets for a record it cannot see.

  THE API. `/api/v1/instances` (list, detail, power, command, backup, snapshot,
  create), API-KEY ONLY (a session cookie is 403 -- which is what makes the
  csrfExempt declarations safe), per-endpoint rate limits, JSON out and ordinary
  form encoding in so the create feeds the SAME raw-values map the HTML form
  does. `InstanceApi.projection` is a whitelist: settings/image/command,
  variables, the quota bucket and the placement host are absent BY NAME.
  Visibility, absence and refusal all produce the identical 404.
  `restoreToNew` is refused for tenant-originated calls: it creates an instance
  OUTSIDE the creation funnel (no authority, no placement, no creator grant,
  image from the archive manifest), so it stays operator-only until it routes
  through it.

  CREATION AUTHORITY (the open item, now closed). `hohenheim.instances.create`
  is a PERMISSION, not a capability -- there is no record to hold a capability
  on -- and it is eligibility only; the real bounds are the transactional quota,
  the image policy (approved templates only) and PLACEMENT. `InstancePlacement`:
  a tenant NEVER names a host. A submitted `server_id` is honoured for an admin
  and IGNORED outright for everyone else; the chooser takes admitted +
  identity-verified + non-`trusted_only` hosts, treats `dedicated` as exclusive
  to one owner (compared by charged quota bucket), and picks
  fewest-live-instances / lowest id. No eligible host is a NAMED refusal, never
  a fall back to the local daemon. One endpoint (`POST /instances/from-template`,
  requiresLogin) now serves both panels, so the HTML and API creates cannot
  drift; the host select is not rendered for a non-admin, because a control
  whose value is discarded is a control that lies.

  FIXED IN PASSING (a check that could not fail): `InstanceQuota` charged EVERY
  create to the operator bucket, on the reasoning that grants land after the
  record. True while creates were admin-only; the moment a tenant can create it
  meant every tenant shared one bucket with the operator, so the per-owner cap
  could not bind the thing it exists for. It now charges
  `HohenheimAccess.creationOwnerSubjects`, the SAME derivation that plants the
  creator's manage grant a moment later.

  Proven by `TenantInstanceSurfaceTest` (6 journeys) and `TenantInstanceApiTest`
  (4), both counterfactualed: dropping the list scope leaks the other tenant's
  name into the body AND turns its 404 into a 200; dropping the power gate turns
  the post-revocation refusal back into `host_not_admitted`; dropping the
  snapshot gate lets a manage-only holder reach `snapshot_no_volumes`; honouring
  the submitted host lands the instance on the tenant-named one; charging the
  operator bucket produces `hohenheim:instances:` instead of
  `hohenheim:instances:user:N`; answering 403 for an unowned id (instead of 404)
  breaks the indistinguishability assertion; and replacing the API's violation
  key with a generic code breaks the identity comparison against the panel's own
  live refusal. STILL ADMIN-ONLY and stated as such: destroy, reinstall/install,
  restore-to-new, template import/export/approval, host administration, and the
  instance file editor (Phase 6 owns files).
- Game wiring: Minecraft server template + Velocity template; a game-domains
  mapping (domain record -> backend instance) that MATERIALIZES as generated
  Velocity forced-hosts config (via the instance config-file mechanism) and DNS
  records (SRV/A via the existing DNS role) on change. Creating/changing the
  mapping requires authority over BOTH records; generated config/DNS rows carry
  owner+source metadata and reconcile/delete only their own output. Minecraft
  traffic flows through Velocity; no in-house MC protocol.

  STATUS (2026-08-04): LANDED. `GameDomainModel` (M062: `game_domains` +
  attribution columns on `instance_files`) + `GameDomains`, THE write funnel:
  authority = manage on the domain's parent site AND manage on BOTH instances
  (mapping references a SiteDomainModel row, exact match only, same host only).
  Materialization: velocity.toml is generated WHOLLY as an attributed
  `instance_files` row (the shared `GeneratedRows` scope now backs both
  GeneratedDnsRecords and GeneratedInstanceFiles; a hand-authored row on the
  path is refused, never adopted), pushed into a PRESENT container through the
  ownership-verified staging path, plus one generated SRV row per mapping
  (`_minecraft._tcp.<host>`, target = host, port = the proxy's observed
  published port; A rows are NOT generated -- no server-address authority
  exists yet). SUPERSEDED (2026-08-04): the server-address authority now
  exists (`servers.public_ipv4`/`public_ipv6`, M063, declared never probed,
  IP-literal-validated on every write path, editable on the host form incl.
  the otherwise-immutable local host) and DNS generation was reshaped: rows
  materialize ONLY off a PUBLIC pre-allocated proxy port (a loopback proxy
  generates NOTHING -- it would be a dangling pointer), the SRV rides that
  stable public port, and an A (and AAAA) row at the mapped hostname carries
  the host's declared address under the SAME mapping attribution, so the SRV
  target resolves off our own zone. An address change re-reconciles every
  mapping on that host (ServerModel before/after save pairing installed by
  GameDomains.install); hand-authored rows at the same name are never adopted
  or deleted (attribution-scoped, counterfactualed in
  GameDomainAuthorityTest). Velocity reaches its backend over a per-PAIR link network
  (`hohenheim-gamelink-{proxy}-{backend}-net`) carrying the same
  WorkloadNetworkPolicy chains; only the authorized pair is joined, re-attached
  at every deploy between create and start. TRAP recorded: connecting or
  disconnecting a RUNNING container to a second network makes Docker
  re-allocate its ephemeral published host port (same PID, new HostPort) --
  GameDomains re-observes the port and re-reconciles SRV rows after every link
  change. Forwarding secret: minted on the Velocity template's secret variable,
  handed to the backend as an encrypted `VELOCITY_FORWARDING_SECRET` variable,
  rendered into BOTH configs at stage time. `InstanceService.destroy` cleans
  mappings explicitly (soft delete fires no remove hooks); a SiteDomain delete
  cascades via beforeRemove. Starter templates seed via `GameTemplateSeeder`
  (`once`-ledgered, land UNAPPROVED). Proven by `GameDomainAuthorityTest`
  (counterfactualed: authority both ways with mapping-not-created asserted,
  reconciler scope, plaintext hand-off) and `GameDomainLiveTest` (real daemon,
  REAL Velocity: readiness from console, forced-hosts read from INSIDE the
  running container, on-change re-render, link-network reachability, no
  published backend port, cross-tenant unreach, console-command graceful stop,
  destroy leaves nothing -- backend workload is an nginx stand-in rendering the
  Minecraft template's real files; a real Paper boot is NOT exercised).
  Fixed in passing: staged-file tars no longer carry directory entries
  (extraction used to re-own an existing volume root to root:root, bricking
  non-root images), and `stageFiles` now REFUSES a same-named foreign
  container (owner labels verified before any push).
- Localization: game audiences are the LEAST English-safe, and today's /manage
  subpages hardcode English titles by concatenation (`SiteProcessesPage.java:53`,
  `SiteDeploymentsPage.java:53`, `SiteDomainsPage.java:58`). Fix these to
  microcopy in this phase (they are the exact subpages a delegated player-admin
  sees).

  STATUS (2026-08-04): the three named pages now resolve their titles through
  microcopy (`processes_title`/`deployments_title`/`domains_title`, scope
  `site`, `{$name}` arg, en+nl). The SAME concatenation defect remains in
  `SiteDevSessionsPage`, `SiteDatabasesPage`, `DnsZoneFilePage`,
  `DnsZoneRecordsPage`, `DnsZoneSecondariesPage`, `DatabaseRestorePage`,
  `InstanceSchedulesPage`, `InstanceScheduleStepsPage`,
  `SpamserviceSampleAnalysisPage` -- admin-only pages, deliberately out of this
  wave's scope; sweep them when their surfaces are next touched.
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

## Phase 5b -- System-container app catalog (community-scripts adoption)

ADDED 2026-08-04. NORMATIVE. HARD PREREQUISITE: the Phase 4 Incus driver. There
is no system-container tier to install into until it exists, and this section
must not be started before it. It is deliberately placed after Phase 5 because
it is a TEMPLATE CATALOG, not a new mechanism: everything it needs (typed and
secret variables, config-file materialization, resource limits, the operator
approval gate) already shipped in Phase 5.

`community-scripts/ProxmoxVE` (MIT, ~300 apps) is a catalog of app installers
for Proxmox LXC containers. Adopting it gives the system-container tier a real
app catalog on day one instead of an empty picker.

RECON 2026-08-04, verified against the upstream repository at `main`:

- The repo splits into `ct/` (Proxmox container creation), `install/` (the app
  install itself), `vm/` (Proxmox VM creation), `misc/` (the shared shell
  function libraries `build.func`, `install.func`, `core.func`) and `tools/`.
- `install/<app>-install.sh` contains NO `pct`, `pvesm`, `pveam` or `qm` call.
  Its first line is `source /dev/stdin <<<"$FUNCTIONS_FILE_PATH"`: the host
  injects a shell function library through ONE environment variable, and the
  script then calls that vocabulary (`color`, `verb_ip6`, `catch_errors`,
  `setting_up_container`, `network_check`, `update_os`, `msg_info`/`msg_ok`/
  `msg_error`, `setup_deb822_repo`, the `$STD` quiet-runner, `motd_ssh`,
  `customize`, `cleanup_lxc`).
- `ct/<app>.sh` is essentially a declarative manifest -- `var_cpu`, `var_ram`,
  `var_disk`, `var_os`, `var_version`, `var_unprivileged`, `var_tags`,
  `var_gpu` -- plus an `update_script()` function, wrapped around a `source` of
  `build.func`.

DECIDED: **the shim target is `$FUNCTIONS_FILE_PATH`, NOT `pct`.** Shimming
`pct` would mean reimplementing Proxmox's storage, template and idmap layers for
no benefit. Provide our own function library implementing the install-side
vocabulary and unmodified `install/` scripts run verbatim in a Debian/Ubuntu
system container.

DECIDED: **do not reimplement or source `build.func`.** Parse `ct/*.sh` for its
`var_*` manifest and map it onto an instance template. Everything `build.func`
does -- storage selection, template download, whiptail prompting, resource
choice, network setup -- is what hohenheim already owns, and owns better:
quotas, host admission, placement and per-instance networks have no Proxmox
equivalent. A shimmed `build.func` would be a second authority over decisions
the platform already makes.

Binding constraints:

- **Vendor and pin per template. Never source from `main` at deploy time.** The
  helper vocabulary is an undocumented internal contract that upstream changes
  freely, and a live fetch means an upstream edit silently changes what tenants
  are running. Pinning is also what makes the approval gate meaningful.
- **Host-coupled helpers must be real or must refuse by name.** `setup_hwaccel`
  is the worked example: on Proxmox it means GPU passthrough, which edits the
  container's config on the HOST. A stub that returns success while Plex reports
  hardware transcoding as enabled is exactly the signature defect of this
  codebase (a step does less than it claims and reports success). Either
  implement the hohenheim-side counterpart or refuse by name with the path
  written down, following the nixpacks and GitLab precedents.
- Each script is third-party shell running as root inside the tenant's own
  container. That is acceptable for the container; CURATION is the trust
  decision, and it belongs to the existing operator approval gate, with the
  pinned revision recorded per template.
- `update_script()` is a CAPABILITY WORTH ADOPTING, not merely compatibility
  surface: it is an in-place app update path executed inside the container, and
  the Phase 5 template mechanism has no equivalent today. Decide deliberately
  whether to adopt it as a template-declared update action (it maps onto the
  record-schedule action vocabulary) or to declare it out of scope.

Gate: two apps from the upstream catalog install unmodified from their pinned
`install/` scripts into system containers, reachable and functional, with the
`ct/` manifest driving the template's declared resources; a template whose
script calls a host-coupled helper we have not implemented FAILS LOUDLY at
approval or install time rather than installing a silently degraded app; an
upstream edit to `main` provably does not change what an already-approved
template installs; and a tenant with no approval authority cannot introduce a
new script.

STATUS (2026-08-05): LANDED, gate passed on daystrom. The shim library
(`resources/community-scripts/hohenheim-functions.sh`) implements the
install-side vocabulary and DECLARES it on one `HOHENHEIM_FUNCS_VOCABULARY`
line; the static gate (`CommunityScripts`) refuses -- BY NAME, at import,
approval AND install -- any script calling a helper of the pinned upstream
namespace (`upstream-vocabulary.txt` at 27f66a80) the shim lacks, with the
shim's `command_not_found_handle` as the runtime backstop (`setup_hwaccel`
sits in BOTH: statically refused, and a defense-in-depth shell refusal).
Vendored + pinned: gotify and adguard (`catalog/`, byte-identical to
upstream; import copies content into rows, checksummed -- re-import can only
mint a NEW unapproved row). The shim deliberately diverges where upstream
live-fetches main (`update_os` tools fetch, `customize`'s /usr/bin/update)
and where interactivity would block (`network_check`: IP-LITERAL probes,
loud exit). Install on Incus runs INSIDE the instance's own rootfs
(`InstallSupport` on the driver; separate install images refused by name;
clear-reinstall = destroy the rootfs, no volume vocabulary). `update_script()`
ADOPTED: `instance_templates.update_script` (M072), `InstanceAppUpdates`
(manage-funneled), row action on /admin AND /manage, `app_update` schedule
action. The Phase 4 readiness flag is CLOSED: `attachRequiresRunning()` on
the console seam defers the attach to after start, backlog-seeded from the
daemon's console log, so system-container readiness lines work (the console
speaks for the SYSTEM; getty banner is the honest line). TRAPS recorded: an
exec whose process exits 127 comes back as a FAILED operation ("Command not
found") that still carries `return` + output files, and exec output paths
must be used verbatim (`logs/exec-output/...`). Introduction gate is
LAYERED and both layers are counterfactualed: zenit-auth's `/admin` baseline
(`auth.admin.access`) plus the endpoint's own `hohenheim.admin.access`.
Proven by `CommunityScriptCatalogTest`, the strengthened
`TenantInstanceSurfaceTest` journey, and `IncusCommunityAppLiveTest` (real
host: both apps install/answer inside AND from the host, readiness flip +
never-appearing-line ERROR, in-place update runs, unknown helper fails
loudly, daemon left empty).

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

  DECIDED AND FIRST TIER LANDED 2026-08-04 (re-verify, do not assume). The
  canonical relation: the INSTANCE TIER IS the runtime-resource contract --
  `InstanceModel` row = the owned runtime resource, `InstanceService` = the one
  orchestration funnel (fenced outcome writes, verified destroy, ledger,
  admission), `InstanceKindHandler`/`InstanceRuntime` = the driver seam. Product
  records (Site, Database, Stack service) stay product tiers and OWN instances
  through the GeneratedRows attribution discipline (`instances.generated_by/
  _for_model/_for_id/_at`, M064): written only inside the owning tier's system
  scope, refused read-only everywhere else (`SiteInstances.install` guards),
  excluded from the standalone instance UI/API -- ownership stays grant-derived,
  the attribution is a structural parent link, never an owner column. NO
  narrower fifth abstraction: an interface all four tiers "implement" would
  leave four copies of the fence/destroy/ledger discipline. `SiteTypeRegistry`
  STAYS -- site types answer "how are requests served" (8 of 11 run no
  container); instance kinds answer "how does the workload run"; a
  container-running site type keeps its request half and delegates its runtime
  half. LOWERED: Docker sites (`SiteContainerKind` `hohenheim:site_container` +
  `SiteInstances`; `DockerSiteRequestHandler` owns no container, converges an
  owned instance and proxies its published loopback port; two DECLARED kind
  differences: `tenantAuthored()=false` skips host admission, and
  `NetworkPosture.SHARED_BRIDGE` skips the private network -- both
  kind-declared, neither settings-reachable; git builds pin `built_image_id`
  so a new build redeploys and an unchanged reload converges instead of
  restarting every site; site delete = verified `destroyFor`, refused on an
  unconfirmable daemon). NOT YET LOWERED, named paths: managed DATABASES (an
  owned `database_container` kind; obstacles: engine readiness probe must
  become a kind/driver concern, containers are name-keyed not id-keyed,
  `DatabaseEnvInjection` reads live ports at spawn, restore paths exec inside
  the container); STACK SERVICES (one owned instance per service; obstacles:
  per-stack network + DNS aliases vs per-workload networks, `depends_on`
  ordering and health gating are stack-spec semantics with no kind home yet,
  adoption/rollback snapshots re-deploy verbatim -- open decision 5 said
  revisit after real use, unchanged); MANAGED PROCESSES / git deploys of
  process site types (not containers; either a `process` instance kind or
  deliberately outside the contract -- decide with the builders wave, which
  produces images and will make docker-the-runtime the default anyway).
  Projects/environments (open decision 7) meet this at the attribution
  columns: a project tier would OWN instances the same way, no schema change.
- Sandboxed builders run outside the control-plane trust domain with their own
  CPU/memory/disk/time/PID quotas, restricted network, short-lived source and
  registry credentials, no tenant runtime secrets, and immutable artifact
  output pinned by digest. Dockerfile and buildpack/Nixpacks-style builds share
  one build-operation record and log stream.

  FIRST BUILDER KIND LANDED 2026-08-05 (re-verify, do not assume). The daemon's
  own `/build` endpoint is GONE (`DockerClient.buildImage` deleted): it executed
  the tenant's Dockerfile inside the daemon, as root on the host, with no quota
  of any kind -- it IS the control-plane trust domain, and there was no sandbox
  to add to it. Builds now run as a hardened one-shot container of a DAEMONLESS
  builder (`builds.builder_image`, kaniko by default), on its OWN private
  network with the throwing, read-back-verified `WorkloadNetworkPolicy` applied
  BEFORE the container exists -- so a host that cannot enforce it REFUSES the
  build rather than building unprotected. The context goes IN and the artifact
  comes OUT through the archive API; nothing is bind-mounted, and
  `ContainerHardening` refuses host binds structurally, which is what makes the
  socket unreachable rather than merely unmounted. Five quotas, five enforcement
  points: CPU+memory are cgroup caps, PIDs is a TIGHTENING-ONLY parameter on the
  hardening funnel (`applyTo(spec, profile, tighterPidsLimit)`, min of the two --
  it can never widen), TIME is a controller deadline that kills and removes, and
  DISK is a watchdog over the daemon's own `SizeRw` accounting plus hard caps on
  the context pushed in and the artifact read out (there is no disk cgroup
  without xfs prjquota, and a spec-level `--storage-opt` would have been a
  setting that silently does nothing). Artifacts are pinned by DIGEST -- the
  site's `image` setting is now `sha256:...`, never the tag -- and the builder
  runs `--reproducible` because without it the same context yields a new digest
  every time and every routing reload would roll every git-sourced site.
  Credentials go through `BuildCredentials`: per-build, TTL-bounded, revoked in
  a finally block, redacted out of the captured log, never stored in any table.
  Runtime secrets have NO PATH into a build -- `BuildRequest` has no member that
  could carry them, and `build_arguments` is a separate DockerSiteType field
  from `environment_variables`. `BuildOperationModel` is the one record for both
  kinds; NIXPACKS is DECLARED (`Builders.forKind` refuses it by name) with the
  path stated: a detection phase in this same sandbox emitting a Dockerfile into
  the context, after which the dockerfile builder runs unchanged. HONEST GAPS:
  the lease shortens HOHENHEIM's lease, not an upstream provider's credential
  (provider-minted tokens arrive with the git-provider wave and plug into
  `issue` unchanged); egress is RESTRICTIVE, not closed (a per-build allowlist
  is unbuilt); the artifact read is buffered through controller memory
  (`builds.max_artifact_mb`), the same limitation snapshot capture has.
- Projects and environments group applications, databases, domains, variables
  and quotas. This is the point to adopt the hierarchical tenant/project model
  if open decision 7 selects it; do not bolt projects onto URLs while ownership
  remains per-user underneath.

  LANDED 2026-08-04 (re-verify, do not assume), and open decision 7 is CLOSED
  by ratifying the grant derivation: a PROJECT IS AN OWNER, never a foreign
  key. Its identity is a zenit-auth permission GROUP (`ProjectModel.group_id`,
  created/torn down by `ProjectGuards` write hooks); a record belongs to the
  project when its `manage` grant is held by subject `group:<id>` and nothing
  else, membership is a positive `group.<slug>` grant (the resolver's one
  membership walk), so `manageSubjectsOf`/`sameOwner`, the quota bucket
  packing, dedicated-host placement and the released-claim quarantine all kept
  answering from the ONE derivation with zero changes -- mutation-proven: gut
  `manageSubjectsOf` and sameOwner, adoption, the environment guard and the
  project cap all fail together. Creates INTO a project pin the creation owner
  (`HohenheimAccess.withCreationOwner`, set only by the one create funnel
  after a membership check), so the charged bucket, tenant placement and the
  planted group grant cannot diverge. Per-project quota = the existing
  `instance_quotas` row keyed by the project's packed subject (one cap
  authority; `ProjectResource` only reads it); projectless records keep their
  per-user/operator buckets. ENVIRONMENTS (`environments`, M067) are grouping
  WITHIN one owner: `instances.environment_id` is refused unless the
  environment's project OWNS the instance (grouping can never disagree with
  the grants), and environment variables are the SAME `instance_variables`
  mechanism (exactly-one-owner rule; environment values = deploy baseline,
  instance row wins). Migration is the ledgered `ProjectAdoptionSeeder`
  (`hohenheim.project-adoption`): one project per distinct non-empty owner
  set over live sites+instances, members = exactly those subjects (reach
  preserved by construction, walk-verified in ProjectAdoptionTest), direct
  grants revoked, quota override rows + charged buckets + released-claim
  former-owner packs rewritten in the same pass; operator-owned (empty-set)
  records deliberately untouched -- wrapping the operator in a project would
  break the empty-set equality the admin wildcard/carve-out routing rests on.
  The one live install carries ZERO record grants, so the heal is a no-op
  there today. NOT here: per-project quotas for builds/releases, a tenant
  self-service project surface (admin resources + the from-template project
  pick only), sites/databases environment attribution (instances only),
  preview deployments.
- GitHub/GitLab-compatible provider installation, repository/branch selection,
  signed webhooks and deployment status reporting. Preview deployments have
  bounded lifetime/quota, isolated variables and deterministic generated-domain
  ownership/cleanup.

  LANDED 2026-08-04 (re-verify, do not assume), GITHUB END TO END; GitLab is a
  DECLARED kind `GitProviders.clientFor` refuses by name (the NIXPACKS shape),
  its path stated in that refusal's docblock. Providers are `git_providers`
  rows (M068) with `.secret().encrypted()` credentials -- a PAT, or GitHub App
  columns whose presence makes every clone/API operation ride a MINTED
  installation token (RS256 App JWT, ~1h upstream validity, cached to 5 min
  before expiry): the genuinely short-lived upstream credential the builder
  wave deferred, and it reaches git as an `http.<origin>.extraHeader`
  Authorization value through the process ENVIRONMENT only -- never the URL
  (GitRepository still refuses embedded user-info), never the command line.
  Repository/branch selection: `provider_id`+`repository` in GitSourceSchema,
  admin-gated rate-limited listing endpoints (`/admin/git-providers/{id}/
  repositories|branches`); a picker UI over them is unbuilt. Webhooks
  (proxy-port, conduit-less, so core RateLimiter drives the per-IP limit
  directly): ONE 404 for everything short of a verified signature (unknown
  slug, non-git site, missing secret, wrong signature -- byte-identical, no
  existence leak); the delivery id is CLAIMED insert-first against the unique
  `webhook_deliveries` (site, key) index before anything acts (replays fold,
  proven under mutation); a payload that names a repository must name the
  bound one (422 otherwise); only the bound branch deploys; PR events drive
  previews only where `previews_enabled` opted in. Status reporting posts
  pending/success/failure onto the exact sha the deploy checked out
  (sha-less failures deliberately stay local). PREVIEWS: a
  `preview_deployments` row owns -- via GeneratedRows attribution, columns
  added to `site_domains` by M068 with the guard installed -- its
  site_container instance, its generated `<site>--<ref>.<previews.
  base_domain>` hostname row (which passes the NORMAL domain write pipeline:
  conflict scan, live_route_key claim, released-claim quarantine, so an
  expired preview's hostname is same-owner-retakeable and stranger-quarantined
  for free) and its A/AAAA rows in a hosted zone; the sweep removes rows by
  EXACT attribution and a hand-authored row is never adopted or deleted
  (mutation-proven). Lifetime is the STORED `expires_at` enforced by the
  minute `PreviewExpirySweep` task AND a boot sweep -- RecordSchedules was
  deliberately NOT used: it has no one-shot lane (a spent cron stores
  next_fire_at NULL, which findDue reads as due-forever), a framework gap
  noted rather than worked around. Quota = `hohenheim:previews:` buckets over
  the site-owner pack (a project is one owner) through the atomic core
  ledger, cap `previews.max_per_owner`. Variables are isolated STRUCTURALLY:
  the preview spec is built from scratch and only
  `preview_environment_variables` enters it -- production env, database
  injection and volumes have no code path in; resource limits are inherited
  (they cap, never leak). Deploys use the DIRECT lane on purpose (nothing
  production-facing is replaced; the release engine's probe, sandbox build,
  digest pin and InstanceService discipline are reused, its gate is not);
  teardown is verified destroy + attribution sweep + artifact prune, wired
  into PR-close, expiry, the operator row action and site delete. NOT here:
  the GitLab client, a picker UI, per-preview TLS issuance (a wildcard cert
  over the base domain is THE model; generated rows are LE-excluded), remote-
  server previews (loopback probe/serve assumes the local daemon), preview
  webhooks for process/static site types.
- Health-gated zero-downtime release: create candidate, probe, atomically switch
  routing, drain old release, retain rollback target, then reclaim. Failed health
  never replaces the serving release. Rollback is one durable operation over a
  pinned artifact/spec, not a rebuild of mutable source.

  LANDED FOR DOCKER SITES 2026-08-04 (re-verify, do not assume). Every attempt to
  change which release serves is a durable `ReleaseOperationModel` row (the
  BuildOperationModel shape: status walk pending/deploying/probing/switching/
  draining, timestamped step log, M066) driven by `SiteReleases`; instances carry
  `runtime_role` (serving/candidate/retired) so a site owns TWO releases during a
  swap, each with its own container/port claim (the ledger arbitrates). The gate
  is an HTTP probe against the candidate's published loopback port (`health_path`,
  `releases.probe_*`) -- deliberately NOT the console `readiness_line` matcher,
  which stays the template-workload gate; a refused candidate is verified-destroyed
  and the prior release KEEPS SERVING (proven by continuous traffic through a real
  ProxyServer: an answering-but-500 candidate never received one request).
  ATOMICITY comes from the routing generation swap: the engine runs inside the
  incoming generation's construction, only returns fully-probed upstreams, and
  requests pinned to the outgoing generation finish against the old release, which
  keeps running for `releases.drain_seconds` after the switch (counterfactually
  proven: removing the drain window turns a swap into real 503s). RETENTION:
  exactly one retired release per site (row + stopped container, which also pins
  its image); reclaim runs at the end of the NEXT successful operation's drain.
  Rollback redeploys the retained instance's digest-pinned settings -- image-
  sourced sites are now digest-pinned too (`desiredSettings` resolves the tag once,
  `image_ref` keeps the human name) -- proven with the tag moved AND deleted; a
  succeeded-or-draining rollback whose `site_fingerprint` still matches PINS
  convergence so a reload cannot silently undo it, and any source change dissolves
  the pin. `source_fingerprint` (site settings + commit_sha, checkout paths
  excluded) is the new convergence fast lane: an unchanged routing reload of a
  git-sourced site no longer runs a sandbox build at all; commit-less contexts
  never fast-lane. Boot recovery (`SiteReleases.recoverInterrupted`, ServerMain,
  PROXY role) settles in-flight ops: pre-switch loses its candidate and stamps
  interrupted, a half-flipped switch completes, a lost drain finishes. NOT here:
  releases for stacks/databases/processes (their lowering waves), preview
  deployments, per-project quotas.
- Per-deployment build/runtime logs, environment/secret editing, persistent
  storage, managed database links, domains/TLS/DNS and notifications all use the
  existing generated/resource surfaces and shared authorization. Cross-tier
  links require authority over both records and generated DNS/cert records carry
  owner+source metadata for reconciliation and cleanup.
- Ship a documented API/CLI for project, deployment, rollback, logs and secrets;
  API keys are capability/permission narrowed and browser sessions remain CSRF
  protected. Import tooling covers the Hohenheim records predating this model.

  LANDED 2026-08-05 (re-verify, do not assume). The API extends the existing
  `/api/v1` znit_-key lane (`PaasApi` + the shared `ApiConduits` plumbing the
  instance lane now rides too): projects/environments by MEMBERSHIP (gated on
  the key covering site/instance `manage` -- membership is grant-derived, so
  the listing enforces scope narrowing itself, mutation-proven), sites via
  `managedSiteScope`, deploy = the git wrapper's `enqueueDeploy` and rollback =
  the SAME two lanes the UI offers (SiteReleases release-engine for docker,
  previous slot for git process/static -- proven by the engine's own
  `release_no_rollback_target` coming back over the wire), the three
  operation-record lanes (git DeploymentModel, ReleaseOperationModel with step
  log on the detail read, BuildOperationModel with its captured log), instance
  console tail (`InstanceConsoles.tail`, named `logs_unavailable` refusal,
  never an empty success), and variables over the ONE `instance_variables`
  mechanism for instances AND environments -- secrets are WRITE-ONLY over the
  API (`has_value` only; the projection mutation fails PaasApiTest). One
  uniform 404 everywhere, including child records of another site. Rollback
  demands no server-side phrase ON PURPOSE (ConfirmationSpec is a client
  interlock; a server phrase here would be theater the HTML lane lacks) -- the
  CLI owns the human interlock: `tools/hoh` (single-file node, thin client,
  config 0600 via `hoh login`) types back the site slug or refuses
  non-interactively without `--yes`, proven against a stub server
  (`tools/hoh.test.js`: refusal AND no request on the wire). Docs:
  `docs/paas-api.md`. Import tooling for pre-model records is the ledgered
  `ProjectAdoptionSeeder` from the projects wave (`hohenheim.project-adoption`),
  already covering live sites+instances; no second importer was built. NOT
  here: an API create/delete lane for projects/sites (admin resources own
  those), per-preview API surface, streaming log follow (the console WS stays
  the streaming transport).

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

STATUS (2026-08-05): slice 1 LANDED and proven live on daystrom
(IncusVmLiveTest, 13-step journey). The recorded decisions:
- kind=vm is `hohenheim:incus_vm` (IncusVmKind) through the SAME
  IncusInstanceRuntime; the flavour is a declared `IncusWorkloadType`
  (api type, `security.secureboot=false` as a MANAGED key, a longer
  exec-ready window for the agent -- ~4 min observed to agent-up on a small
  host's first boot). A converge onto a same-named workload of the WRONG
  flavour refuses.
- Cloud-init shape: the template mechanism IS the provisioning vocabulary.
  `cloud_init` is a settings field on the VM kind; `{{KEY}}` placeholders
  resolve against instance variables (secret lane included) in
  InstanceVariables.applyToSettings; the spec carries the rendered text and
  the driver writes `cloud-init.user-data`; the Docker driver refuses a
  cloud-init-bearing spec by name. VM kind has no env vars (nothing injects
  into a guest's init) and no privileged flag.
- Image identity: `instances.image_fingerprint` pins the daemon's resolved
  `volatile.base_image` at deploy (fenced write); an ABSENT workload is
  recreated from the pin, never the alias; a declared-image change clears
  the pin (InstanceImagePin hook). TRAP: cloud-init status exits 2 on
  done-with-warnings -- never gate on its exit code.
- Devices: `instance_devices` desired-state rows (M073) reconciled at deploy
  and cleaned at destroy (volumes deleted VERIFIED; destroy soft-deletes, so
  the cleanup is the explicit GameDomains-shape call). DeviceAttachSupport is
  the capability; disks are owner-labelled block custom volumes (resize is
  stopped-only at the daemon: "In use" while running), extra NICs land on the
  managed `hohenheim-extra` bridge because the daemon REFUSES a second NIC on
  the primary network (instance DNS name conflict, verified live), each NIC
  read-back-verified to carry the shared ACL (verifyAllNics).
- Quota: disk-GB and extra-NIC dimensions over the core reservation ledger
  (InstanceDeviceQuota; buckets hohenheim:disk_gb:/hohenheim:nics:), charged
  adjacent to the row write, per-owner overrides on instance_quotas
  (max_disk_gb/max_nics), settings defaults in HohenheimSettings.Quota. The
  VM root disk stays the image default (a root-size knob is the later
  disk/NIC/device-editing inventory item). Counterfactuals run: reservation
  disabled -> the racing-attach test fails (2 rows landed); extra-NIC ACL
  dropped -> the applier's read-back refuses the attach.
- FINDING (2026-08-05, daystrom, incus qemu lane): an UNEXPECTED guest reset
  (nested-KVM panic ~30s into first boot, ~3/5 runs on daystrom; a hostile
  tenant can force the same with sysrq) makes incus restart QEMU in place, and
  in one observed run the eth0 teardown failed ("Failed to detach interface
  ... invalid argument", stale DOWN tap left on the bridge) and the restarted
  VM's live tap carried NO ACL reject rules -- the VM pinged its peer while
  the daemon CONFIG still read back fully isolated. Clean `reboot` and even
  sysrq-b re-applied rules correctly when retried deliberately; the leak needs
  the failed-detach race. This is daemon-internal (below our REST seam): our
  read-back verifies daemon CONFIG, and kernel-truth verification needs host
  access the driver does not have. Track as a named risk in the Phase 8
  bridge/VLAN/firewall inventory item; candidate mitigations: incus upstream
  fix, host-agent kernel readback, or refusing VM kinds on hosts whose incus
  version carries the race.
  SUPERSEDED 2026-08-05 by the kernel-truth wave below: the mitigation shipped,
  and the upstream half of that FINDING is now answered (no upstream fix
  exists, so this is permanent, not version-gated).

STATUS (2026-08-05, kernel-truth wave): the isolation leak above is CLOSED at
the layer we can close it. What was established, in order:

- REPRODUCED, but not the trigger. The security divergence itself is
  reproducible on demand on daystrom and was captured verbatim: with the
  instance's `bridge incus` chains removed while it runs, `incus config show`
  still reports `security.acls: hohenheim-isolation`, `incus network acl show`
  still carries all six tenant rejects, `nft list ruleset` shows
  `table bridge incus { }`, the tap is `master incusbr0 state forwarding`, and
  the VM pings its peer AND the host gateway at 0% loss. What could NOT be
  forced on demand is incus's own EINVAL detach: five injections were tried
  (tap `nomaster` raced to the millisecond against QEMU exit, tap moved to
  another bridge then `incus restart`, SIGKILL of QEMU, guest `sysrq-b` x5,
  guest clean `reboot`, plus an ACL-churn race against the first-boot
  auto-restart) and none of them made incusd log the failed detach. Two
  incidental facts worth keeping: a guest reset (sysrq-b or `reboot`) is
  absorbed by QEMU and does NOT change the tap or the rules, and a fresh
  Alpine VM on daystrom reliably takes an incus-driven full restart ~28s into
  its first boot (new tap every time). So the RACE is reproduced only in its
  end state; the fix does not depend on the trigger, only on config truth and
  kernel truth being independent.
- UPSTREAM VERDICT: not a known defect, not fixed, not version-gated. incus
  v7.3.0 (2026-07-31) is the newest release and is what daystrom runs;
  `nic_bridged.go`, `drivers_nftables.go`, `drivers_nftables_templates.go` and
  `qmp/monitor.go` are byte-identical on `main` as of 2026-08-05, and nothing
  in the lxc/incus or canonical/lxd trackers describes this (bridge-NIC
  `security.acls` is Incus-only since 2024-09, so LXD has no shared ancestry).
  Upgrading fixes nothing. The mechanics: `nicBridged.postStop` detaches the
  tap BEFORE it calls `removeFilters` and RETURNS on the detach error, so a
  failed detach skips the firewall teardown entirely; every generated rule is
  scoped `iifname "tapXXXX"` while the chains are named after instance+device
  and carry `policy accept`. A surviving chain therefore names a DEAD tap, the
  restarted workload's new tap matches nothing, and accept wins. Counting
  chains or rules would report that state as isolated -- which is why the
  verifier keys on the CURRENT tap name.
- MECHANISM: `IncusKernelIsolation` (server/incus) reads `nft list table
  bridge incus` on the daemon's own host through the EXISTING `NftRunner`
  lane, resolves each NIC's live `volatile.<dev>.host_name`, and requires a
  drop/reject naming that tap for every `TenantNetworkRanges` entry in both
  families. It NEVER falls back to the controller's nft.
- SHAPE, and why it closes rather than narrows: verification at start cannot
  see a hole that does not exist yet (the divergence is created inside incusd
  afterwards, on a reset a tenant can force at will), and there is no event to
  hang a re-check on that is not itself racing the daemon's own stop/start.
  Nothing outside incusd can PREVENT the window; a five-minute
  `VerifyIncusIsolation` sweep (boot+cron, INSTANCES role) makes it BOUNDED and
  self-closing -- no state exists in which a diverged workload stays reachable
  indefinitely or invisibly. Deploy-time verification rides the driver's
  `start` as well, for the case where the hole is already open.
- REPAIR LEVER: an ACL config-key bump (`PUT /1.0/network-acls/<name>`).
  Upstream's `common.Update` calls `BridgeUpdateACLs` UNCONDITIONALLY and
  `removeChains` is keyed by instance+device rather than by tap, so the reload
  removes a chain left naming a dead tap and rebuilds it against the live one.
  A device write is NOT equivalent and this was measured live: re-setting the
  identical `security.acls` value repaired nothing (`devicesUpdate` only
  reloads devices whose config actually changed), while the ACL bump restored
  all three chains and the peer went back to 100% loss.
  SUPERSEDED 2026-08-06 by the per-instance lever below: the ACL bump repairs
  correctly but reloads EVERY NIC referencing the shared ACL, so it fails on a
  neighbour's transitioning veth. The finding that an IDENTICAL device write
  repairs nothing still stands and is why the replacement changes a key.
- DECISION on a running workload found diverged: repair first, then STOP. A
  transient daemon race therefore costs no availability at all; only a workload
  the daemon REFUSES to re-isolate is stopped. What is at stake in the other
  direction is not that workload's data but every other tenant's, on the only
  strong boundary shared iron has. A stopped workload is a visible, recoverable
  failure; an unisolated running one is invisible and unrecoverable. A host
  whose kernel cannot be READ is a different case and is never stopped -- it is
  reported unverifiable every sweep, because refusing to answer is not evidence
  of a leak.
- CLOSED 2026-08-05 by the trust-split wave below (was: OPEN -- an https Incus
  daemon had no product shell lane, so its kernel truth was unavailable and the
  live test had to inject the lane through the test seam).
  NOT in slice 1 (later slices): framebuffer console + plumage viewer,
  Windows prepared templates, host drain / cold migration, the closed
  Proxmox-use inventory, and any admin/tenant UI for device editing (the
  mechanism's wired consumers are deploy-reconcile + destroy-cleanup + the
  service lane).
  SUPERSEDED 2026-08-06: of that list only the closed Proxmox-use inventory
  and the device-editing UI remain -- the framebuffer console landed in the
  framebuffer-console wave, drain/cold migration in the cross-host wave, and
  Windows prepared templates in the prepared-template wave below.

STATUS (2026-08-05, trust-split wave): the remote-host gap the kernel-truth wave
left OPEN is closed, and the isolation sweep now verifies daystrom through the
PRODUCTION path. What was established:

- ROOT CAUSE was an overloaded column, not a missing feature. `servers.host_key`
  (+ fingerprint/verified/pinned_at/offered) and `identity_public_key`/
  `identity_private_key` held EITHER ssh material (docker host) OR Incus TLS
  material (incus host). One column cannot be the authority for two independent
  trust relationships, and it had already bitten twice: the backup gate carried
  a `MODE_SSH && !isIncus` guard purely to keep a certificate PEM out of the
  known_hosts writer, and an Incus host could not hold an ssh admin lane at all
  because its certificate occupied the slot that lane needed.
- SHAPE: M074 gives the Incus TLS relationship its own seven columns
  (`incus_server_cert*`, `incus_client_cert`, `incus_client_key`) and leaves the
  ssh columns meaning what their docblocks always said. `HostTrustSlot` (common)
  names the two slots; `HostPins` is the one state machine parameterised over a
  slot, so the ceremonies still cannot drift apart but cannot write into each
  other's columns either. A host now holds BOTH at once, which is what an Incus
  host needs.
- The ssh admin lane walks the SAME ceremony, not a weaker one: scan, out-of-band
  fingerprint confirmation, quarantine on change, per-host client key,
  `HostKeys.sshArgv` for every argv. `ServerModel.hasSshLane` (ssh_target
  non-blank) is the single authority for "there is a shell", deliberately not
  MODE -- MODE is the docker transport discriminator and the host form stamps it
  from the runtime, so an Incus host is always `local` there.
- OPTIONAL by design: `IncusKernelIsolation.available()` answers true only for a
  lane that is declared AND pinned AND confirmed AND unquarantined. Without one,
  the host keeps working for everything else and is reported UNVERIFIABLE every
  sweep -- refusing to answer is still not evidence of a leak. A non-required
  `kernel_isolation_lane` preflight check now says so on the record instead of
  only in a log line.
- EXISTING ROWS: the pin half moves losslessly (plaintext columns). The incus
  CLIENT KEY does not and is cleared: zenit binds table+column into the
  encrypted envelope's AAD, so a copied envelope would not decrypt -- by design,
  since that binding is what stops a cross-column graft. Such a host lands on the
  typed NO_IDENTITY refusal whose recovery is the existing two-click ceremony
  (rotate identity, paste a trust token).
- PROVEN ON DAYSTROM THROUGH THE PRODUCTION PATH: IncusKernelIsolationLiveTest
  installs no test seam. The fixture performs only operator acts (put the
  product-minted public key in authorized_keys, read the host's own
  `ssh-keygen -lf` digest for the out-of-band comparison); every kernel read
  travels `HostKeys.sshArgv` -> `NftRunner.forServer` -> `sudo -n -- nft`. The
  journey asserts lane-less = unavailable + named refusal, unconfirmed =
  unavailable, then drives the same divergence (drop the instance's `bridge
  incus` chains while it runs) and requires `VerifyIncusIsolation.sweep()` --
  the production sweep, not the raw verifier -- to repair the repairable one and
  STOP the unrepairable one.
- The test seam survives for what it is now good for: pointing the verifier at a
  kernel the record does not name. Nothing in the daystrom proof uses it.

STATUS (2026-08-06, cross-tenant lever wave): the repair lever was coupling
tenants together and now does not. Found by the trust-split wave's own live
test under the full nine-class parallel set; the refusal it recorded for OUR
workload named a FOREIGN instance, which is what gave it away.

- MEASURED at the daemon, not inferred from the message. With two workloads
  sharing the `hohenheim-isolation` ACL and one of them looping start/stop,
  21 of 103 ACL config-key bumps FAILED outright ("Failed updating bridge NIC
  ACL: ... Unknown or missing host side veth device") naming the NEIGHBOUR. A
  cleanly STOPPED neighbour is skipped and harmless -- the window is every
  start and every stop, which on a busy host is common, not exotic.
- WHY IT MATTERED: an unrepairable workload is STOPPED by declared policy, so
  one tenant's ordinary churn could have cost every other tenant on the host
  their availability for a fault that was never theirs.
- LEVER NOW: toggle `security.acls.default.egress.logged` on OUR OWN NIC
  device. `false` is the daemon's own default, so the generated ruleset is
  byte-identical with and without it (36-line diff, empty) -- the only thing
  that changes is that the device config differs from what the daemon last
  applied, which is what `devicesUpdate` keys on. `security.acls` is never
  touched, so the NIC never stops declaring isolation for even an instant.
  Restored 3 of 3 chains, and 116 of 116 toggles succeeded under the same
  neighbour churn that broke a fifth of the ACL bumps.
- The SHARED ACL stays. The coupling was in the lever, not in sharing the
  policy object, so isolation semantics are unchanged and no per-workload ACL
  lifecycle was introduced.
- ALSO FIXED, same shape one layer up: two deploys on a FRESH host both read
  "no ACL" and both POST it, and the loser's 400 "already exists" failed a
  tenant's install for another tenant's win. The loser now proceeds to the
  read-back, which stays the gate -- a winner that wrote a weaker ruleset is
  still refused.
- The live test now asserts the property directly: every workload named in a
  sweep's recorded refusals must be OURS.

STATUS (2026-08-06, inert-mechanism wave): `IncusVmLiveTest` step 7 reported
`expected ISOLATED, got REACHED` once in seven full-suite runs. It is a REAL
isolation loss, not a harness artifact, and the reason nothing caught it is that
in that class the kernel-truth mechanism was INERT.

- ROOT CAUSE, and it is the whole finding: the class enrolled its host through
  `LiveIncusHost.enrollThroughProduct`, which leaves `ssh_target` null. For an
  https daemon that makes `IncusKernelIsolation.available()` false, so the
  driver's start-time `requireKernelIsolation` RETURNED SILENTLY and
  `VerifyIncusIsolation` reported the host unverifiable and repaired nothing.
  The VM tier's isolation ran with no kernel verification at any moment. So the
  answer to "did start-time verification run, did it pass, did the divergence
  appear after it" is: it never ran. Verification at start is not what was
  insufficient here -- it was absent.
- THE DAEMON'S OWN RECORD of the failing run (test log 20260806-081326,
  08:13:25-08:27:51 CEST) carries exactly three `level=error` lines, and two of
  them are on that class's own VM `hohenheim-instance-6086423`: at 08:22:56
  `Failed to stop device device=eth0 err="Failed to remove interface
  \"tap1e7cfa55\": no such device"`, and at 08:23:26 the same with
  `device name is empty`. Both are the documented `nicBridged.postStop` early
  return that skips `removeFilters`. The peer `hohenheim-instance-6086424` came
  up at 08:23:23, so step 7's probe ran seconds after the second failed
  teardown. The solo re-run at 08:29 that PASSED carries no such error.
- BASE RATE at the daemon: 2 of 34 hohenheim VM instances in the retained
  journal hit that failure (5.9%), against 1 of 7 for the suite -- same order.
- NOT REPRODUCED ON DEMAND, and that is stated rather than papered over. 14 VM
  lifecycles under neighbour churn and CPU load with continuous kernel-truth
  sampling produced 0 natural divergences, and two deliberate triggers (deleting
  the live tap, and RENAMING it out from under the daemon) did not make incusd
  log the failure: in both, the following start rebuilt the chains against the
  new tap correctly. The END STATE remains reproducible on demand and its
  consequence was measured again (chains dropped: `incus config device get`
  still returns `hohenheim-isolation`, the ACL still reads back all six
  rejects, `table bridge incus` names no live tap, and the workload pings).
- RULED OUT, each by measurement, so a later reader does not re-walk them:
  a shared-ACL reload stripping a BYSTANDER's chains (0 of 40 bystander losses
  while 16 of 40 bumps failed outright under neighbour churn); a SUCCESSFUL ACL
  reload opening a transient hole; the test racing the peer's own filter
  install (chains land 0.35-0.55s BEFORE the peer's IPv4 becomes visible, 6 of
  6); a `LiveIdOffsets` id collision; and a broken neighbour poisoning a fresh
  instance's filter setup.
- CORRECTED BY THE COUNTERFACTUAL, and this is why the counterfactual is now
  IN the test: isolation here is EGRESS-only and one-sided, because
  `IncusNetworkPolicy.nicDevice` sets
  `security.acls.default.ingress.action=allow`. A NIC left at the daemon's own
  default-deny ingress kept answering ISOLATED with the sender completely
  unfiltered -- so a peer probe would have been blind -- while the
  product-configured one answered REACHED. The hypothesis that the peer's chain
  protects the peer is FALSE for this product's configuration, and step 7d is
  what fails if that ingress default ever changes.
- WHAT LANDED: `IncusVmLiveTest` now enrols the ssh admin lane through the
  product ceremony, so the host carries the configuration the Phase 8 claim
  requires and every deploy in the journey runs the real start-time check; step
  7b probes the daemon host's own bridge address (derived from the NIC's
  network, never a hardcoded subnet); step 7c asserts KERNEL truth for the live
  tap; step 7d drops the chains and requires BOTH probes and the verifier to
  catch it; step 7e repairs through the product's per-instance lever and
  re-asserts, including that the NIC still reaches the internet.
- OPEN, stated as open, NOT closed by this wave:
  1. The lane is OPTIONAL by design (trust-split wave), so an Incus host that
     declines it has NO isolation verification at all while still accepting
     tenant workloads. Phase 8's "VMs are the only strong boundary" claim is
     unbacked on such a host. Making the lane a placement REQUIREMENT for a
     posture other than `trusted_only` reverses a recorded decision and is a
     fork for the operator, not a fix to slip in.
  2. The post-start window is still bounded only by the 5-minute sweep, and the
     divergence is created ~28s after start by the daemon's own restart. The
     candidate closure is a listener on the daemon's `/1.0/events` stream
     (the transport already has `openWebSocket`) re-verifying the named
     workload within a second of the lifecycle event that can open the hole,
     with the sweep as the backstop. NOT built here: the trigger was not
     reproduced on demand, and hardening an unreproduced trigger would retire
     the investigation instead of closing the bug.
  3. `VerifyIncusIsolation` treats an instance whose daemon record names no
     live host interface as an ERROR and leaves it RUNNING (`inspect` refuses,
     the loop `continue`s). The 08:23:26 line shows that state is reachable.
     An unreadable HOST is legitimately not evidence of a leak; a RUNNING
     workload the daemon cannot name an interface for is a different case.
  4. `VmFramebufferConsoleLiveTest` and `IncusHostLiveTest` do not call
     `LiveIdOffsets.apply`, so both take `hohenheim-instance-1` and collide
     with each other under parallel forks.

STATUS (2026-08-06, framebuffer-console wave): the rescue console SHIPPED and both
of its gate clauses are closed by tests that RAN (CI and live on daystrom).

- ARCHITECTURE FINDING that shaped the whole slice, measured live: qemu's SPICE
  server GLZ-compresses every display image REGARDLESS of the client's declared
  compression preference (SPICE_MSGC_DISPLAY_PREFERRED_COMPRESSION=OFF was sent
  and 32x16 tiles still arrived ~1.4KB vs 2KB raw), so a live per-region SPICE
  display stream requires implementing SPICE's LZ/GLZ/QUIC image codecs in the
  chain. That is a named follow-up, not this wave. The shipped console is the
  SNAPSHOT lane: the proxy polls the daemon's own VGA screenshot
  (`GET /1.0/instances/{name}/console?type=vga`, PNG, change-detected by digest,
  250ms active / 1s idle) and pushes frames as BINARY websocket frames, while
  INPUT rides a real live SPICE connection (`SpiceConsole`: REDQ link + RSA-OAEP
  empty ticket + mini-header, MAIN for the session id, INPUTS for AT-set-1
  scancodes) over the existing Rfc6455 lane -- never a second websocket client.
  Keyboard is proven (guest screen changed); mouse is best-effort (frames are
  carried, but qemu boots in server mouse mode and no mode negotiation is done).
  Hypervisor-side by construction: the live test attaches BEFORE the agent is up.
- MECHANISM HOMES: protoblast `WebSocketConnection` gained binary frames (send
  byte[], Listener.onBinary -- it was text-only by construction); plumage gained
  `pl-framebuffer`, the protocol-neutral viewer primitive (canvas surface,
  drawRgba/setFrame/fillRect/copyRect, focus + KeyboardEvent.code/pointer capture
  into a FramebufferInputSink, and a wsUrl transport mode riding
  WebSocketConnection: binary frame = encoded snapshot, text frame = status, input
  out as plain-JSON maps -- pl-terminal's shape, but on the framework socket);
  hohenheim owns the SPICE/Incus specifics (SpiceConsole, SpiceScancodes,
  IncusClient.startVgaConsole force=true + vgaScreenshot, VmFramebufferHandler on
  `/ws/instance-framebuffer/{INSTANCE_ID}`, InstanceFramebufferPage visible/404
  by kind). No migration: no new columns, capability is the existing MANAGE.
- AUTHORIZATION is the framework seam end to end: requiresLogin at the handshake,
  per-record MANAGE in onOpen (1008), `revalidate()` re-checking MANAGE under
  core's default-on revalidator at TERMINAL_REVALIDATION_INTERVAL_MS. The
  handler takes a FramebufferSource factory seam so the authorization contract is
  provable without a daemon.
- GATE "use the framebuffer rescue console" + "revoke tenant access mid-console":
  VmFramebufferConsoleLiveTest (daystrom, production endpoint, real socket, real
  VM): PNG framebuffer frame arrives pre-agent, scancodes ride the live SPICE
  input channel, then the MANAGE grant is revoked and the OPEN socket closes
  1008 within two revalidation intervals. VmFramebufferRevocationTest proves the
  same over a real socket in CI (fake source, 100ms interval), and its
  counterfactual was run: with revalidate() bypassed the socket is NEVER closed
  (verbatim: "[a revoked viewer's OPEN console is disconnected, not merely
  refused later] Expecting value to be true but was false") -- the test observes
  socket CLOSURE, not a later refusal. The long-carried "0.7 gate test does not
  exist" note is STALE: ProcessTerminalHandlerTest already walks open-socket ->
  revoke -> 1008 for the 0.7 terminal (passiveViewerIsClosed1008WithinOne
  RevalidationInterval and the active variant), re-run green this wave.
- The plan's interim "raw console websocket for external clients" hatch is
  MOOT-BY-SHIPPING: the framebuffer console landed in the same wave, and the
  serial lane (`/ws/instance-console/{id}`, bidirectional /dev/console on the
  Incus driver) already serves VMs as the text fallback. No separate raw VGA
  endpoint was added; an external SPICE client lane can ride the same handler
  shape later if a concrete consumer appears.

STATUS (2026-08-06, native-shape wave): the PRIMARY deployment shape -- hohenheim
running ON the host it manages, local unix sockets, `NftRunner.Sudo`, no ssh lane
-- was exercised end to end for the first time, live on daystrom (fresh install,
104 migrations, instances-only role set). The repeatable path is
`docs/deploy-native.md`. What it proved and what it broke:

- PROVEN: `IncusKernelIsolation.runnerFor`'s first branch (unix endpoint ->
  local Sudo) is live: preflight `kernel_isolation_lane: pass` with
  `ssh_target` empty, and both `VerifyIncusIsolation` and
  `VerifyDockerIsolation` reported the hosts VERIFIABLE. Both sweeps were
  proven non-vacuous by deleting the kernel tables while workloads ran: within
  one cron tick both logged `repaired [...]` and the kernel was re-verified
  (the Docker-tier leak was demonstrated first: with `inet hohenheim_net`
  gone, a workload fetched the controller's own `/api/health`). Negative
  isolation held on both tiers (tenant-range egress dropped, measured with
  address literals) with `http://1.1.1.1/` as the positive anchor. Note for
  later probes: Docker's own inter-network isolation ALSO blocks
  workload-to-workload on this shape, so the attribution probe for OUR policy
  is workload -> host INPUT, not workload -> workload.
- BROKEN and fixed 1: `ServerResource` was gated on STACKS|DATABASES, so an
  instances-only node had NO host-admission surface at all while placement
  requires an admitted host (HohenheimPanel now gates it on INSTANCES too).
- BROKEN and fixed 2: the local-row identity guard in `ServerResource
  .updateRow` swallowed EVERY non-address field silently -- posture included
  -- while the form reported success, so the one host a single-machine
  install runs on could never leave `trusted_only` and could therefore never
  accept a tenant workload. Posture is now operator-editable on the local row
  (identity stays immutable); ServerAdminTest walks the journey and its
  counterfactual was run (expected shared_container, got trusted_only).
- BROKEN and fixed 3: six microcopy strings carried a literal `{{KEY}}`,
  which `MessageParser` rejects -- rendering ANY form containing those help
  texts (the whole VM instance page) threw MessageParseException. Escaped as
  `\{`; `MicrocopyCatalogParsesTest` now parses every shipped message in both
  locales (counterfactual run: it names the three broken keys verbatim).
  Three missing `plural` scope variants (instance_snapshot, instance_backup,
  backup_target) rendered nav labels as the raw key and were added.
- TWO-CONTROLLERS HAZARD, decided procedurally: a native controller and the
  workstation's live suite are two controllers over one daemon with COLLIDING
  `hohenheim-instance-<id>` handles (each numbers from its own database), so
  each one's sweeps would repair/stop the other's workloads. They run at
  different times, never concurrently; the daystrom service is left STOPPED
  and disabled. See deploy-native.md's hazard section; nothing in the product
  namespaces handles per controller yet.

STATUS (2026-08-06, cross-host wave): the two Phase 8 gate clauses a single host
could never prove -- "restore to a new host" and "drain the source host through
the chosen migration policy" -- are CLOSED, live on two deliberately twinned
Incus hosts (daystrom 10.47.1.99 + nightstrom 10.47.1.101; identical package
sets, bridge subnets differ BY DESIGN, the five cached images pre-seeded
byte-identically on nightstrom before anything was measured).

- POLICY DECISION: COLD migration (stop, whole-instance export, import on the
  destination, start) is THE migration policy. Live migration is REJECTED for
  now -- this is the inventory decision the phase body demands, not an
  omission: incus stateful transfer requires migration.stateful set before
  start, CRIU for containers and matched CPU flags for VMs, plus a
  daemon-to-daemon trust relationship the product holds nowhere; the gate's
  own "restore to a new host" wording implies the cold shape, and drain is an
  operator maintenance operation where bounded downtime is acceptable.
- TRANSPORT: the EXISTING NativeSnapshotSupport export/import pair,
  controller-mediated (daemon A -> controller staging -> daemon B), NOT
  incus's own cross-host copy. The copy lane would be a second transfer path
  riding a daemon-to-daemon trust relationship with its own ceremony and
  columns (the M074 lesson), while export/import already carries
  re-attribution, the MAC strip and the isolation rejoin, and the controller
  already holds pinned trust with each daemon separately. Cross-host incus
  remote trust therefore stays UNCONFIGURED on both hosts, deliberately. The
  migration export packs snapshots (instance_only=false via the new
  withSnapshots parameter; the backup lane keeps its instance-only shape), so
  snapshot records survive the move -- measured: an alpine VM export is ~95MB
  in ~34s on these hosts.
- OWNERSHIP DISCIPLINE (the split-ownership killer): instances.server_id stays
  the SINGLE pointer. M075 adds migrate_target_id; the record's host remains
  the data authority until the source copy is VERIFIED gone, then ONE guarded
  statement (InstanceOperationGuard.handoff) repoints the record, closes the
  window and RE-BASES claim_fence into the destination's lease domain. Every
  guarded stamp now also matches on server_id (hostScope: NULL is a legal
  local spelling), so a stale source-domain write after the handoff matches
  zero rows even though fences from different lease domains are numerically
  incomparable. STATUS_MIGRATING is a protected status (deploy/stop refuse;
  destroy stays the ungated abandon-ship and now also removes an
  already-imported destination copy the daemon attributes to the record).
- KILLED CONTROLLER: InstanceMigrations.recoverInterrupted (boot, INSTANCES
  role, beside SiteReleases.recoverInterrupted) settles every mid-migration
  record from daemon ATTRIBUTION (the new NativeSnapshotSupport.claimOf:
  ABSENT/OURS/FOREIGN -- the same pre-flight that refuses the known
  handle-collision hazard at the destination BEFORE importing over it):
  record's host still holds the workload -> ROLL BACK (delete an OURS
  destination copy); record's host empty but destination holds OURS ->
  COMPLETE the handoff (the copies are equal by construction -- the source
  was stopped before export); neither -> ERROR, loudly; an unreachable daemon
  DEFERS rather than manufacturing a verdict. Recovery never auto-starts: it
  restores one truthful owner, an operator restores service.
- DRAIN is a real product operation: the ServerResource drain row action on a
  CORDONED host (drain_requires_cordon otherwise -- cordon stays the
  reversible pause, drain the move; the ServerModel "no draining token" note
  is superseded in place: drain is an operation, not a stored state) migrates
  every live instance to a placement-chosen host
  (InstancePlacement.chooseForBucket over the stored quota bucket, source
  excluded). A workload that cannot move is REFUSED BY NAME and left exactly
  as it was -- drain is operator convenience, never authority to stop or
  destroy a tenant's workload -- and the report/toast ends loudly INCOMPLETE
  naming the held workloads. Unmovable this wave, each a named refusal:
  device rows (the whole-instance export does not carry custom volumes; the
  destination reconcile would attach FRESH EMPTY disks -- the silent-success
  shape), port publications, non-native drivers (the docker tier).
- PROVEN LIVE (IncusColdMigrationLiveTest, one 13-step journey, VM kind,
  daystrom -> nightstrom, PASSED twice back to back): a 512MiB alpine VM
  running on daystrom with marker data written inside is backed up OFF-HOST
  (filesystem target), mutated, then daystrom is cordoned and DRAINED -- the
  report reads moved 1 / refused 0 / host holds none; the record names
  nightstrom, the VM RUNS there with the post-backup data intact AND its
  pool-resident snapshot carried along, and daystrom's daemon no longer knows
  the handle. Isolation on the destination is asserted in the KERNEL:
  nightstrom's `nft list table bridge incus` names the migrated NIC's live
  tap (printed into the test output) and IncusKernelIsolation.enforce over
  the enrolled ssh admin lane agrees; the NEGATIVE (a peer container on
  nightstrom cannot reach the migrated VM's address) is anchored by the same
  probe reaching 1.1.1.1. Restore-to-new-host: refused by name onto the
  still-cordoned daystrom, then lands there after uncordon with the
  BACKED-UP state (v1, not the migrated v2) -- restoreToNew's serverSpelling
  parameter finally has a proof and a consumer. The killed controller is
  simulated live (crash after the destination import, copies on BOTH real
  daemons, record MIGRATING) and recoverInterrupted rolls back to exactly one
  owner with the data intact. Both hosts end holding zero instances and zero
  trust entries.
- CI (InstanceMigrationTest, in-memory native runtime, 4 journeys, all RAN):
  the move (data + snapshots + one-direction ownership), the refusal set
  (same host, devices, non-native driver, FOREIGN destination untouched,
  MIGRATING blocks deploy/stop), drain reporting (partial then complete), and
  BOTH crash windows. Counterfactuals run and captured verbatim: source
  removal disabled -> "[step 3: the source daemon holds NOTHING under the
  handle (a move that leaves the source copy is the silent-success shape)]
  Expecting value to be false but was true" (3 of 4 journeys fail); recovery's
  forward handoff replaced by a window-close -> "[step 4: the handoff is
  completed onto the destination] expected: 2 but was: 1".
- SECOND HOST for live tests: LiveIncusHost.configuredSecondary() reads
  url_b/fingerprint_b/trust_target_b from the same operator file; single-host
  live classes are untouched and the cross-host class SKIPS (never fails)
  without them.
- NOT here, stated as open: a single-instance "migrate to host X" admin
  surface (drain and the service lane are the wired consumers; an
  explicit-destination row action is UI sugar over migrateTo); transporting
  device volumes (refused, never silently emptied); a docker-tier drain
  (VolumeSnapshotSupport has the pieces, no consumer demanded it); recovery
  does not restart a previously-running workload (see above); the
  two-CONTROLLERS handle-collision hazard is unchanged -- claimOf makes this
  controller refuse a foreign workload instead of converging over it, which
  narrows the blast radius but does not namespace the handles.

STATUS (2026-08-06, prepared-template wave): the last unblocked gate clause --
"Windows from a prepared template" -- is CLOSED, live on daystrom against a real
Windows Server 2025 guest. Only the Proxmox-use inventory remains.

- MECHANISM, and it is deliberately NOT Windows-shaped: `ImageOrigin`
  (server/runtime, beside `Egress`) declares CATALOG -- the public simplestreams
  catalog, the default, producing a byte-identical source map to before -- or
  PREPARED, meaning the image is already in the TARGET DAEMON's own store and is
  never fetched. Everything a prepared image needs that a cloud-init Linux guest
  does not is a DECLARED capability on `IncusVmKind`, following the
  `attachRequiresRunning()` precedent: `image_origin`, `secure_boot`,
  `guest_agent`. No code path anywhere reads "windows"; Windows is one instance
  of the category, not a branch.
- SECURE BOOT was exactly the trap the phase body warned about, and the warning
  was right. The VM kind HARDCODED `security.secureboot=false` because catalog
  Linux builds are unsigned; Microsoft-signed Windows media boots WITH Secure
  Boot on and the whole template was built that way. The key stays MANAGED
  (re-asserted on every converge, so operator drift cannot brick a boot
  silently) -- only its VALUE became the image's own declaration. MEASURED, not
  assumed: no TPM was attached and none is needed, because Windows Server 2025
  has no TPM requirement (Windows 11 client does).
- GUEST AGENT: there is no incus guest agent for Windows, so `incus exec` is
  impossible against this tier forever, not temporarily. `guest_agent=false`
  makes `runInstall` and `runAppUpdate` REFUSE BY NAME instead of burning the
  600s `execReadyTimeoutMs` and reporting a timeout as if the guest were broken;
  `runInstall` refuses BEFORE `create`, so no workload is born just to be torn
  down. `execWhenReady` has exactly ONE caller (`runInstall`), so a plain deploy
  never waited on the agent and an agent-less VM deploys unchanged -- that was
  checked rather than assumed.
- PREFLIGHT, the anti-silent-success guard: a PREPARED alias absent from the
  target daemon is refused by name before the daemon is asked to create
  anything. Without it the daemon answers a generic create failure and the
  operator cannot tell that the alias is simply not published ON THAT HOST --
  which is the common case, since publishing is per daemon. `IncusClient` gained
  exactly one image endpoint for this (`imageFingerprintForAlias`); it had NO
  /1.0/images surface at all.
- AUTHORISATION HOLE CLOSED IN PASSING: `InstanceImagePolicy` matched an
  approved template on kind+image+tag only, so an approved CATALOG template
  authorised a same-named PREPARED alias and vice versa. A prepared alias
  namespace is entirely operator-controlled, so those are different objects.
  Origin is now part of the match; an absent origin reads as catalog on both
  sides, so templates approved before this exist keep authorising exactly what
  they always did.
- OPERATOR STEP, and it is deliberately NOT product: the plan says no in-panel
  OS install initially, so the product accepts the finished image and
  `docs/prepare-windows-template.md` is the reproducible procedure, written the
  way `docs/deploy-native.md` was. It carries the four findings that each cost a
  boot cycle to establish, so nobody re-walks them: (1) the repacked media stops
  at "Press any key to boot from CD" and TIMES OUT to PXE -- swap the El Torito
  image for the `efisys_noprompt.bin` the media already ships, in place, since
  re-mastering is not available; (2) `<DiskConfiguration>` makes Server 2025's
  redesigned Setup fail instantly with `0x80070002 - 0x40030` before it writes a
  partition table -- partition with `RunSynchronous` + `diskpart` instead; (3) a
  Windows ISO is UDF-primary (`install.wim` is 5.27 GB), so `xorriso` adds
  `autounattend.xml` to the ISO 9660/Joliet trees where Windows cannot see it,
  and discards the boot record doing so -- use a second CD; (4) only a device
  carrying `boot.priority` is in the firmware's boot order, so give the DISK the
  higher priority and let the firmware fall through to the CD while the disk is
  blank. RULED OUT and recorded as such: the second CD-ROM device itself, the
  `<ProductKey>` element, the `Microsoft-Windows-TerminalServices-*` components,
  and `/IMAGE/NAME` (Setup DISPLAYS "Standard Evaluation" while the WIM's name
  is "SERVERSTANDARDCORE", so `/IMAGE/INDEX` is the safer spelling, but it was
  not the cause).
- FEASIBILITY, with the numbers, because "it did not fit" was a legitimate
  outcome: it fits. Windows Server 2025 Standard Evaluation (Server Core, build
  26100) installed unattended on daystrom (3 vCPU, 3907 MB RAM, 40 GiB btrfs
  pool) in 22 minutes into a 24 GiB disk with 2560 MiB RAM; sysprep /generalize
  took 13 minutes; `incus publish` produced a 3.58 GiB zstd image in 56 seconds;
  a clone reached RDP-ready in 4 minutes on 2048 MiB. A Desktop Experience
  install was never attempted and is NOT claimed. TRAP for anyone repeating the
  sysprep step: sysprep launched over WinRM dies SILENTLY mid-validate (the log
  stops at `Sysprep_Clean_Validate_Opk` and the process is simply gone); run it
  from a scheduled task as SYSTEM instead.
- PROVEN LIVE: `IncusWindowsTemplateLiveTest` on daystrom against the real
  `win2025-core` image, through the product funnel end to end -- RAN and passed
  in 291s. It SKIPS (never fails) when the prepared alias is not published on the
  host, because the image is an operator fixture this repo cannot mint for
  itself. Nine steps: the absent-alias refusal against the real image store with
  the daemon holding nothing afterwards; deploy; the three declared capabilities
  read back off the daemon (`security.secureboot=true`, NO `cloud-init.user-data`
  key, owner labels); the fingerprint pin equal to the alias's own target; the
  framebuffer console attached and a PNG frame received WHILE Windows was still
  booting; the agent-less exec refusal on a record that was never created;
  DHCP lease then RDP accepted, both probed from the daemon host; kernel truth
  plus the drop-the-chains counterfactual plus the repair; destroy with the
  daemon asked whether the handle is really gone.
- FIXTURE LEFT IN PLACE, deliberately, and it is the one thing this wave left on
  a host: `win2025-core` (3.58 GiB) stays published on daystrom so the live test
  keeps running. daystrom otherwise ends exactly as it started -- 0 instances, 0
  custom volumes, 0 trust entries, one operator ssh key -- and every build
  artifact (13 GiB of ISOs) is deleted. `incus image delete win2025-core` returns
  the host to its 5 cached images and downgrades the test to a SKIP; rebuilding
  it is docs/prepare-windows-template.md, about an hour unattended. nightstrom
  was not touched at all.
- OPEN, stated as open, NOT closed by this wave:
  1. There is NO guest-side egress probe for this tier and there cannot be one:
     isolation here is EGRESS-only and one-sided (the ingress default is allow),
     and every existing VM-tier egress probe pings FROM the guest, which needs
     the agent this tier declares absent. Kernel truth is precisely the layer
     that does not need the guest, and it is what the live test asserts and
     breaks and repairs. But "the Windows guest cannot reach a tenant peer" is
     verified in the KERNEL, not observed from inside the guest.
  2. Nothing writes into a Windows guest. Per-instance configuration would need
     cloudbase-init in the prepared image consuming the existing
     `cloud-init.user-data` the driver already writes; that combination is
     UNTESTED and is not claimed to work.
  3. The image is per DAEMON. `win2025-core` was published on daystrom only, so
     a Windows workload cannot currently be cold-migrated or drained onto
     nightstrom -- the destination would be refused by the new preflight, by
     name, which is the correct failure but is a real placement constraint the
     placement layer does not yet know about.
  4. The evaluation licence expires. Nothing in the product tracks that.

STATUS (2026-08-06, inventory-closure wave): the LAST open Phase 8 gate clause --
the checked-in Proxmox-use inventory -- is CLOSED. It lives at
`docs/proxmox-use-inventory.md`, sixteen items, each with a verdict
(IMPLEMENTED / IMPLEMENTED-NO-OPERATOR-SURFACE / REJECTED / GAP), its evidence,
and how that evidence was checked (code read at file:line, test re-run with
counts, or live re-run). It was built from CODE, not from the STATUS notes above.

- REJECTED, each with reasoning rather than left as an omission: clustering and
  HA (the "runtime = data on the server record" schema bakes in 1:1
  runtime-to-host; adopting a cluster is a schema change plus a placement
  rewrite, and without shared storage plus quorum there is nothing to fail over
  TO that is not a stale copy); live migration (already recorded, restated with
  its substitute); PCI/GPU/USB passthrough (the one device class that is NOT
  safe to hand a hostile tenant -- a passed-through function is DMA-capable);
  ISO install (template-once/clone-many is the shape we operate); clone-of-a-
  running-guest (publish-and-provision or export/import already cover it, with
  the accountability a bare copy lacks); storage-POOL selection (one pool per
  host, inherited from the default profile, refused rather than guessed); VLANs
  (per-workload nft rules verified in the KERNEL are stronger for hostile
  tenants than a trunk whose correctness lives in a switch we do not own); S3
  backup targets.
- SIX honest gaps, each named with its owning slice rather than buried: device
  editing has NO operator surface (the mechanism is complete and live-proven but
  `attachDisk`/`attachNic`/`resizeDisk`/`detach` have no production caller at
  all -- only IncusVmLiveTest); no root-disk size knob; placement is NOT
  resource-aware (it scores by a COUNT of live instance rows -- no CPU, memory
  or disk figure is consulted, and the promised admission-time capacity snapshot
  does not exist, while `RestoreCapacity`, the one capacity check that does
  exist, has NO test and is stubbed out in InstanceMigrationTest); no snapshot
  retention; no host-health heartbeat for Incus hosts; and the kernel-truth ssh
  lane is OPTIONAL, which is the one item that directly weakens "VMs are the
  only strong boundary".
- LIVE RE-VERIFICATION for this wave, not taken on trust: IncusColdMigrationLiveTest
  RAN and PASSED (1 test, 0 skipped, 632s, daystrom -> nightstrom) and
  IncusVmLiveTest RAN and PASSED (1 test, 0 skipped, 94s). IncusWindowsTemplateLiveTest's
  PASSED (not SKIPPED) verdict was confirmed in its own log, 20260806-164723.
  Both hosts end at the declared baseline: 0 instances, 0 custom volumes, 0 trust
  entries, 1 operator key each; daystrom keeps `win2025-core`.
- TWO DEFECTS FOUND BY THE AUDIT and fixed here, each with a counterfactual:
  1. `InstanceQuotaResource` never declared `max_disk_gb`/`max_nics`, so the
     per-owner device caps M073 created -- enforced by the reserve hooks, with
     label and help copy in both locales -- could not be set by anyone: the form
     reported success and dropped the field. Counterfactual: "the submitted disk
     cap is STORED, not dropped by the form ... Expecting actual not to be null".
  2. `DockerReconciler.sweepAll` iterated EVERY host row and called `clientFor`,
     which refuses an Incus host by construction; that refusal was recorded as a
     probe FAILURE, so every Incus host was stamped UNREACHABLE hourly. Because
     `last_error_kind` is also the sticky QUARANTINE token that only a repin may
     clear, a host quarantined by a live TLS-pin contradiction had that verdict
     overwritten within the hour by an unrelated task. Now sweeps
     `ServerService.dockerNames()`. Counterfactual: `expected: "host_key_changed"
     but was: "unreachable"`. NOT fixed and named as open: the general shape --
     ANY weaker `recordFailure`, and `recordSuccess` outright, still clears the
     token. Whether a transient probe outcome may clear a TRUST verdict is a
     security-state-machine decision for the host-trust slice.

STATUS (2026-08-06, device-surface wave): two of the six gaps the Proxmox-use
inventory opened are closed -- device editing is REACHABLE, and the capacity check
that gates every restore and every migration has its first test.

- DEVICE SURFACE. `InstanceDevices.attachDisk/attachNic/resizeDisk/detach` had NO
  production caller; the only callers in the repo were `IncusVmLiveTest`, while
  production reached `reconcile` and `destroyCleanup` alone. Now: a Devices tab on
  every instance (`InstanceDevicesPage` + `cms/instance-devices.hwk`) plus a
  nav-hidden `InstanceDeviceResource` with a `ResourceParent` back to that tab and
  `?instance_id=&type=` prefills -- the InstanceSchedulesPage/InstanceScheduleResource
  shape verbatim, not a new one -- mirrored onto /manage by
  `ManageInstanceDeviceResource` plus the matching `RecordSourceRegistry.override`
  (two derived defaults over one model is the shadowing hazard sites and schedules
  already hit). API: `GET/POST /api/v1/instances/{id}/devices`, `.../devices/resize`,
  `.../devices/detach`, projection whitelisted (no `quota_bucket`). NEITHER surface
  decides authority: both call InstanceDevices, whose every mutator opens with
  `requireOperationCapability(id, MANAGE)`, and the API keeps the
  no-existence-oracle rule -- an ungranted instance answers 404 on the device lane
  too. Detach's confirmation says the volume is deleted instead of "are you sure".
- FRAMEWORK CHANGE (zenit-cms), because the generic path was WRONG here rather
  than merely inconvenient. A scoped create/update ran inside a rollback
  transaction that re-loads the row through the access predicate. For a mutation
  reaching a DAEMON that rollback removes the ROW and orphans the volume; and on a
  single-writer engine it cannot run at all -- `Leases.acquire` refuses by name
  inside an active transaction, so the attach failed with
  `cms.create_submit.save_failed`. New `Resource.verifiesScopeBeforeMutating()`
  (default false) lets a resource that refuses out-of-scope callers BEFORE its
  first write opt out; `InstanceDeviceResource` is its only declarer. Covered on
  both sides:
  `ResourcePageEndpointsTest#aResourceVerifyingScopeItselfMutatesOutsideTheRollbackTransaction`
  (163 RAN in that class) and the hohenheim journey.
- CAPACITY. `RestoreCapacity.require` is split into `availableBytesOn` (the probe)
  and `judge` (the headroom arithmetic and both named refusals), so the DECISION is
  assertable without a daemon while the PROBE is proven against a real one
  (`RestoreCapacityLiveTest`). DEFECT found by writing the test: the refusal
  CONSTRUCTION called `ServerModel.nameOf`, which THROWS on an unknown id -- a host
  row that vanished mid-restore turned a named 422 into a raw
  IllegalStateException 500, i.e. the refusal path was the one path that could
  itself fail. Fixed (`hostLabel`). `InstanceMigrationTest` still stubs the
  `CapacityCheck` seam and should: those journeys are deliberately daemon-free.
- PLACEMENT: resource-aware scoring is DEFERRED WITH REASONS, recorded in
  `docs/proxmox-use-inventory.md` item 12. The blocker is an INPUT problem --
  `ResourceLimits` members are optional and usually absent ("null means
  unlimited"), so a budget summed over declared limits is zero for the common
  workload -- and the missing piece is a DECLARED per-kind footprint, which is a
  product decision, not a placement refactor. Instead of half-building it the
  chooser's current behaviour is PINNED (`InstancePlacementTest`, written as a
  characterization and saying so), and the same slice keeps the prepared-image
  eligibility bug, which outranks the scoring change because it is a wrong
  eligible SET rather than a wrong score.

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

RESOLVED 2026-08-02 -- do not re-raise these three:

- **1 and 13 are MOOT.** Jelle: no Zenit-based project is deployed anywhere, and
  backwards compatibility is never a concern in this workspace. Both decisions
  existed only because they were believed to touch an irreversible live
  database. There is none. Historical plaintext secrets: nothing to remediate.
  Migration checksums: fix any source-side drift by EDITING the migration in
  place and flip `database.migration_integrity` to `fail`; the snapshot /
  capture-at-warn / scratch-JVM procedure is not needed. This deletes the whole
  of Phase 0.B as a gated production operation.
- **14 is STRUCK.** It asked whether to ship a weaker product and fix it later,
  which contradicts the standing instruction to build it properly and never
  work around a gap. It was invented by an earlier session, not by Jelle.

The remainder are derivable from "build it properly" and are NOT blocking:
2 defaults ON (fail closed). 3 yes, interactive-only. 4 decided at the Phase 1
UI stage. 5/7/8/9 resolve to the fuller shape (hierarchical tenancy because
Coolify parity needs projects and environments; both host postures expressible
with the safe one as the default; `record_schedules` a new table). 6/10/11/12
are evidence-gathering at their own phases -- write the Proxmox inventory rather
than asking about it.

A consequence worth stating once: "no installs exist" is a licence to DELETE.
The 2026-08-02 wave removed `WorkloadIdentity.applyLegacyDefault`, which
persisted `process.require_dedicated_user = false` on any install that already
had sites -- so the setting read as enforced in source while being off on every
upgraded box. Prefer deleting such accommodations over carrying them.


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

   CLOSED 2026-08-04: the hierarchical model, implemented by RATIFYING the
   grant derivation -- a project is an OWNER whose identity is its auth
   permission group, never a column or a URL prefix. See the Phase 7
   projects/environments bullet for the landed shape and its limits.
8. Shared-host untrusted multi-tenancy posture: do we ever offer container-only
   isolation to hostile tenants with a per-host operator acknowledgement, or is
   the answer always VM-per-tenant / dedicated host (Phase 8)? This is a product
   risk decision and a Phase 3 ENTRY blocker, not a later deployment toggle.
9. `record_schedules` as a new table vs a facet of `SystemTaskModel` -- leaning
   new table; confirm at Phase 3, while preserving ordered task-chain semantics.
   CONFIRMED (2026-08-04): new tables, in zenit core
   (`zenit_record_schedules` + `_steps` + `_runs`); ordered chains shipped.
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
