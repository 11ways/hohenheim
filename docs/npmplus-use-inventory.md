# The NPMPlus-use inventory

Written 2026-10-06. Every capability people use Nginx Proxy Manager (NPM) and its hardened fork NPMPlus for, with
Hohenheim's verdict, its evidence, and where a Hohenheim admin user meets it today. It is the proxy-manager sibling of
`coolify-use-inventory.md` (PaaS) and `pterodactyl-use-inventory.md` (game panel), and it feeds the admin redesign
(`admin-redesign-plan.md`): which features still need building, and which existing features still need a designed
task screen.

Verdicts:

- **IMPLEMENTED** -- the mechanism exists AND a named test asserts it.
- **PARTIAL** -- precisely what works and precisely what does not. Code with no test found is PARTIAL, not
  IMPLEMENTED, and the clause says "untested".
- **OPEN** -- missing and wanted.
- **REJECTED (argued)** -- a decision argued from the shape of this product. Where the argument is this document's
  proposal rather than a recorded owner decision, the clause says so.

### How this document was built

Product sources (fetched 2026-10-06):

- NPMPlus README, feature list: https://github.com/ZoeyVid/NPMplus
- NPM guide and feature list: https://nginxproxymanager.com/guide/
- NPM advanced configuration: https://nginxproxymanager.com/advanced-config/
- NPM's own UI vocabulary (every option a user can tick), from the English locale file:
  https://raw.githubusercontent.com/NginxProxyManager/nginx-proxy-manager/develop/frontend/src/locale/src/en.json

Hohenheim sources: the code at commit `2eb75017`, read file by file. Every `path:line` below was checked
mechanically against `git show 2eb75017:<path>` (the cited line contains the cited symbol). While this was written, a
concurrent session had uncommitted edits in `src/server/java/.../server/cms/` (the Apps list, W4), so line numbers in
`cms/` files may have moved in the working tree; the commit is the reference.

Path abbreviations: `S/` = `src/server/java/be/elevenways/hohenheim/server/`, `C/` =
`src/common/java/be/elevenways/hohenheim/`, `T/` = `src/browserTest/java/be/elevenways/hohenheim/`.

"Surface" names where an admin user meets the capability today and which redesign board (if any) draws it. Board
names follow `admin-redesign-plan.md` section 3: Main, Apps-List, Domains, Online-1..4, App-Overview, App-Problem,
App-Blocked, App-Console, App-Protect, Host-Admit, Activity, Settings. The board generator (`boards.py` in the
workspace's git-ignored `docs/hohenheim-ux-mockups-2026-09-30/`) was read for what each board actually draws; it also
holds boards the plan does not list yet (App-Deploys, Databases, Hosts, Empty-Dashboard, Empty-Apps), named where
relevant. Online-1 ("What") offers, among others: Static files, Redirect, Existing service (an address upstream) and
"Pass encrypted traffic through" (TLS passthrough).

### The counts

| verdict | count | clauses |
| --- | --- | --- |
| IMPLEMENTED | 24 | 1, 2, 3, 6, 7, 8, 11, 13, 14, 16, 17, 18, 19, 20, 22, 23, 24, 25, 32, 38, 39, 41, 44, 47 |
| PARTIAL | 13 | 4, 5, 9, 12, 15, 26, 28, 31, 36, 37, 40, 42, 45 |
| OPEN | 5 | 21 (HTTP/3), 29 (GeoIP), 33 (load balancing, health checks), 34 (mTLS), 46 (NPM import) |
| REJECTED (argued) | 5 | 10 (pass auth upstream), 27 (raw config box), 30 (asset cache), 35 (ECH and TLS extras), 43 (traffic analytics) |

Of the 24 IMPLEMENTED, 10 have no designed task screen yet (2, 6, 13, 14, 17, 19, 22, 24, 25, 38) and 3 need none
(20, 23, 39: always on or framework pages); see the closing list.

---

## Hosts

## 1. Proxy hosts

NPM: a hostname forwards to a scheme, host and port; an https upstream may be used.

**IMPLEMENTED.** The address upstream kind carries the forward scheme, host and port
(`S/upstream/kinds/AddressUpstreamKind.java:50`, `:57`, `:61`) and a switch to skip upstream certificate validation
(`:75`); validated upstream TLS lives in `S/tls/UpstreamTrust.java:9`. The Host header is preserved
(`S/proxy/SiteDispatcher.java:154`) and `X-Forwarded-Proto`/`X-Real-IP` are set (`S/proxy/ForwardingHeaders.java:158`,
`:162`). No custom upstream CA, only on/off validation.

- Tests: `T/test/UpstreamProtocolTest.java:149` (`httpsUpstreamWithUntrustedCertificateIsRejected`).

Surface: `/admin/sites` create/edit form, upstream kind "Address". Boards: Online-1 ("Existing service"),
App-Overview, Apps-List.

## 2. Trusting a front proxy: real client IP, forwarded headers, PROXY protocol

NPM: "Trust Upstream Forwarded Proto Headers"; Cloudflare IP ranges fetched for real-IP. NPMPlus: proxy protocol.

**IMPLEMENTED.** Inbound PROXY v2 from listed sources (`C/HohenheimSettings.java:89`), a trusted-key header for a
fronting Hohenheim (`:165`), and outbound PROXY v2 on passthrough sites
(`S/upstream/kinds/TlsPassthroughUpstreamKind.java:42`). Untrusted forwarding headers are dropped and regenerated.

- Tests: `T/test/ProxyProtocolHttpTest.java:57`, `T/test/SiteDispatcherTest.java:224`
  (`untrustedForwardingHeadersAreRejectedAndRegenerated`).

Surface: `/admin/settings` (proxy group). Board: Settings, but its section list (General, HTTPS, Backups,
Notifications, App templates, Runtime images, Git, Database engines, DNS server, Blocking and firewall, About) names
no proxy or forwarding section [unverified which section it lands in].

## 3. Redirection hosts

NPM: a hostname redirects elsewhere with a chosen status (300, 301, 302, 303, 307, 308), optionally preserving the path.

**IMPLEMENTED.** The redirect upstream kind offers 301, 302, 307 and 308
(`S/upstream/kinds/RedirectUpstreamKind.java:35`, `:38`) and `preserve_path` (`:43`). No 300 or 303; the target scheme
is part of `target_url`. Per-path redirect rules are a Netlify clause (`netlify-use-inventory.md` 17).

- Tests: `T/test/RedirectSiteTypeTest.java:38` (`redirectsWithConfiguredStatusAndTarget`), `:52`
  (`preservePathAppendsPathAndQuery`).

Surface: `/admin/sites`, upstream kind "Redirect". Boards: Online-1 ("Redirect"), Apps-List (kind "Redirect").

## 4. Streams (TCP and UDP forwarding)

NPM: forward an incoming TCP or UDP port to a host and port (SSH, MQTT, game servers, databases).

**PARTIAL.** What works: TLS passthrough routed by SNI on the HTTPS port
(`S/upstream/kinds/TlsPassthroughUpstreamKind.java:23`), and, for workloads Hohenheim itself runs, ledgered TCP/UDP port
publications (`pterodactyl-use-inventory.md` 6; `S/runtime/PortPublication.java:32`). What does not: the public
listeners exist only on the HTTP and HTTPS ports (`S/proxy/ProxyServer.java:301`), so there is no forwarding of an
arbitrary TCP port, and no UDP proxying at all, to a host Hohenheim does not run. Searched `udp`, `DatagramChannel`,
`DatagramSocket`: no proxy hit.

- Tests: `T/test/TlsPassthroughTest.java:81`.

Surface: `/admin/sites`, upstream kind "TLS passthrough"; port publications on the instance form. Boards: Online-1
("Pass encrypted traffic through"); raw TCP/UDP forwarding has none (it does not exist).

## 5. 404 hosts and the default site

NPM: a "404 host" answers 404 for a hostname on purpose; the default site for unknown names is a choice of
congratulations page, 404, 444 (drop), redirect, or custom HTML.

**PARTIAL.** Unknown hostnames redirect to `fallback_address` when set (`C/HohenheimSettings.java:130`), otherwise get
a 404 page (`S/proxy/SiteDispatcher.java:330`) with an operator message (`C/HohenheimSettings.java:144`, `:152`). Not
offered: 444/drop, a custom HTML page, a deliberate per-hostname 404 host, custom error pages per site.

- Tests: `T/test/PathRoutingTest.java:107` (`knownHostnameWithoutMatchingPathIs404NotFallback`),
  `T/test/AdminPagesTest.java:66` (the settings page saves `fallback_address`).

Surface: `/admin/settings`. Board: Settings (section unnamed, see 2).

## 6. Enable and disable a host

NPM: every host has an on/off switch.

**IMPLEMENTED.** `C/model/SiteModel.java:53` (`enabled`); a disabled site leaves the route table but keeps its
certificates.

- Tests: `T/test/DomainEditTest.java:481` (`disabledSiteRowsAreExemptUntilEnabling`),
  `T/test/TlsResilienceTest.java:197`.

Surface: site form and list switches, `/admin/sites` and `/manage`. Boards: none draws a disable action (the app header
draws Open site, Deploy, Restart and a "More actions" overflow whose content is not drawn).

---

## Access control

## 7. Access lists: basic auth and IP rules

NPM: a reusable list of users (basic auth) and allow/deny IP rules, "Satisfy Any", attached to a host.

**IMPLEMENTED.** `C/model/AccessListModel.java:41` (`satisfy`), rule types basic auth, allowed network, denied network
and auth provider (`C/model/AccessRuleModel.java:142`, `:145`, `:148`, `:151`), group rules nest. A whole site takes a
list through `SiteModel.ACCESS_LIST_ID`.

- Tests: `T/test/AccessListEnforcementTest.java:82` (`accessRuleTreeEnforcesOnTheWire`).

Surface: `/admin/access-lists`, `/admin/access-rules`, `/manage` twins, `/api/v1/access-lists`. Board: App-Protect
(password and network, with the shared list as "Use a shared access list instead").

## 8. Access per location

NPMPlus: "multiple access lists per host and per location".

**IMPLEMENTED.** Protected paths guard a path prefix of one site with their own list
(`C/model/ProtectedPathModel.java:34`); the site's Protection tab lists them (`S/cms/SiteParts.java:116`).

- Tests: `T/test/ProtectedPathEnforcementTest.java:83` (`protectedPathsGuardFoldersOnTheWire`).

Surface: site record tab "Protection", `/admin/protected-paths`. Board: App-Protect, App-Problem ("Fix protection").

## 9. Forward auth / SSO in front of a host

NPMPlus: `auth_request` support for common auth providers (Authelia, Authentik and similar).

**PARTIAL.** A rule may require an auth provider, but the provider types are Basic and Proteus only
(`S/auth/types/BasicAuthProviderType.java:35`, `S/auth/types/ProteusAuthProviderType.java:33`). No generic OIDC
relying party and no forward-auth subrequest to an external authenticator (searched `oidc`, `openid`,
`auth_request`, `forward_auth`).

- Tests: `T/test/ProteusAuthGateTest.java:240`.

Surface: `/admin/auth-providers`. Board: App-Protect ("Sign in with an account", which names this installation's
accounts, not an external provider).

## 10. Pass auth to upstream -- REJECTED (argued)

NPM: "Pass Auth to Upstream" forwards the basic-auth header to the app.

**REJECTED.** Hohenheim strips the visitor's `Authorization` header before forwarding
(`S/proxy/ForwardingHeaders.java:188`), on purpose: the credential belongs to the proxy's gate, and handing it to a
tenant app leaks a password the operator issued. Test: `T/test/ForwardedCredentialsTest.java:75`
(`hohenheimsOwnCredentialsNeverReachTheUpstream`). An app that needs the identity should read a header the gate sets,
which is the auth-provider lane (clause 9).

Surface: none (no switch exists).

---

## Certificates

## 11. Let's Encrypt over HTTP-01, with renewal

**IMPLEMENTED.** See `coolify-use-inventory.md` 9 for issuance proof against an in-JVM CA. `C/model/CertificateModel.java:134`
(HTTP-01), renewal at 30 days (`S/tls/AcmeService.java:53`), a reachability pre-check before ordering
(`S/cms/CertificateOperationHandlers.java:229`, `HostnameReach`).

- Tests: `T/test/tls/AcmeIssuanceContractTest.java:110` (`anHttpOrderIssuesARealCertificateAndStoresIt`).

Surface: domain row action "Get a certificate", `/admin/certificates`. Boards: Online-3, Domains, App-Problem.

## 12. DNS-01 with DNS providers

NPM: DNS challenge through dozens of certbot DNS plugins (Cloudflare, Route53, ...), with "Propagation Seconds".

**PARTIAL.** DNS-01 exists (`C/model/CertificateModel.java:136`) with three publishers: manual, internal (zones
Hohenheim itself serves) and a command hook (`:149`; registered at `S/tls/AcmeService.java:137`, `:138`; hook setting
`C/HohenheimSettings.java:286`); propagation wait `C/HohenheimSettings.java:291`. Not offered: any built-in DNS
provider API (searched `cloudflare`, `route53`, `digitalocean`, `hetzner`, `ovh`); an NPM user's "pick Cloudflare,
paste a token" becomes "write a shell hook".

- Tests: `T/test/tls/AcmeIssuanceContractTest.java:200` (`aWildcardDnsOrderPublishesTheDigestAndCleansItUp`).

Surface: certificate request input, `/admin/settings` (hook). Boards: Online-3 and Domains draw the automatic path;
no board draws DNS-01 or the manual TXT "Continue" step (the plan marks that step [unverified], D2).

## 13. Wildcard certificates

**IMPLEMENTED** (DNS-01 only, `S/tls/AcmeService.java:387`).

- Tests: `T/test/tls/AcmeIssuanceContractTest.java:200`, `T/test/TlsCertificateTest.java:279`
  (`wildcardCertResolvesSubdomains`).

Surface: certificate request input. Boards: none draws a wildcard request.

## 14. Custom (uploaded) certificates

**IMPLEMENTED.** The certificate form takes a PEM certificate and key (`S/cms/CertificateParts.java:123`, `:124`),
stored as provider `custom` (`:375`); SNI serves it like an issued one.

- Tests: `T/test/AdminPagesTest.java:389` (`certificateRequestAndUploadFormsRenderAndValidate`).

Surface: `/admin/certificates` create. Board: Online-3 draws the choice "Upload my own certificate"; no board draws the
upload form itself.

## 15. Certificate key type and key reuse

NPM: RSA 2048 or ECDSA 256. NPMPlus: "Reuse Key".

**PARTIAL.** Every certificate key is RSA 2048 (`S/tls/AcmeService.java:906`); there is no ECDSA choice. Key reuse
across renewals: [unverified].

Surface: none.

## 16. Other ACME CAs and staging

NPMPlus: "proper support for other acme servers", short-lived certificates.

**IMPLEMENTED.** `ssl.acme_directory_url` (`C/HohenheimSettings.java:281`) and a staging switch (`:265`). Short-lived
(6-day) profiles: not offered [unverified whether acme4j exposes profiles here].

- Tests: `T/test/tls/AcmeIssuanceContractTest.java:110` runs against an in-JVM CA reached through
  `ssl.acme_directory_url` (`:79`).

Surface: `/admin/settings`. Board: Settings ("HTTPS and certificates").

## 17. Certificate download

**IMPLEMENTED.** Row action `download_certificate` (`S/cms/CertificateParts.java:430`) over
`C/HohenheimEndpoints.java:391`.

- Tests: `T/test/tls/CertificateDownloadCredentialTest.java:91`.

Surface: certificate list row action. Board: none.

## 18. Force SSL

**IMPLEMENTED,** and stricter than NPM: per domain (`C/model/SiteDomainModel.java:187`), switched on by the
certificate latch only once a certificate works (`S/tls/ForceSslLatch.java:28`), fail-closed afterwards.

- Tests: `T/test/TlsCertificateTest.java:368` (`forceSslRedirectsHttpToHttps`),
  `T/test/ForceSslAvailabilityTest.java:78`.

Surface: domain form. Boards: Online-3 (the copy), App-Problem, Domains (HTTPS column).

## 19. HSTS and HSTS subdomains

**IMPLEMENTED** at NPM's level: `C/model/SiteDomainModel.java:218` (`hsts_enabled`), `:223` (`hsts_subdomains`). The
max-age is fixed at one year (`S/proxy/SiteDispatcher.java:464`); there is no preload flag (NPM has none either).

- Tests: `T/test/ProxyDispatchTest.java:155` (`hstsHeaderOnResponse`).

Surface: domain form. Board: none.

---

## Protocols and per-host behaviour

## 20. HTTP/2

**IMPLEMENTED.** `S/proxy/ProxyServer.java:402`. Test: `T/test/ProxyDispatchTest.java:498`
(`httpsListenerNegotiatesH2`). Surface: none (always on, no switch). Board: none needed.

## 21. HTTP/3 (QUIC) -- OPEN

NPMPlus: "HTTP/3 (QUIC), needs https exposed over udp".

**OPEN.** No QUIC listener or `Alt-Svc` (searched `quic`, `http3`, `alt-svc`). Undertow, which the proxy runs on, ships
no HTTP/3 [unverified for the version in use]; this needs a QUIC stack beside it. Value: low to medium (mobile
latency); effort high.

Surface: none.

## 22. Websockets

**IMPLEMENTED.** `S/upstream/kinds/AddressUpstreamKind.java:71` (`websocket_upgrade`, default on).
Test: `T/test/ProxyDispatchTest.java:228` (`websocketUpgradeBlockedWhenDisabled`).
Surface: site form, "Forwarding" section. Board: none.

## 23. gRPC

NPMPlus: grpc upstreams.

**IMPLEMENTED.** HTTP/2 downstream to h2c upstream with trailers. Tests: `T/test/UpstreamProtocolTest.java:394`
(`h2DownstreamGetsGrpcTrailersFromH2cUpstream`), `:914`. Surface: none (protocol choice on the address upstream).
Board: none.

## 24. Custom locations

NPM: a path on a host forwards to a different upstream.

**IMPLEMENTED.** A domain row carries a path prefix (`C/model/SiteDomainModel.java:170`) and `strip_path` (`:175`), so
one hostname routes by longest prefix to different sites. Regex hostnames too (`:55`).

- Tests: `T/test/PathRoutingTest.java:77` (`sameHostnameRoutesByLongestPathPrefix`), `:135`.

Surface: site record tab "Addresses" (domain form). Boards: App-Overview's Addresses card lists rows; no board draws a
path-routed address.

## 25. Custom request and response headers

NPM: through the advanced config box. NPMPlus: security headers always sent.

**IMPLEMENTED.** `C/model/SiteDomainModel.java:229` (`custom_headers`), `:233` (`response_headers`), applied in
`S/proxy/ResponseMutations.java:28`.

- Tests: `T/test/ProxyDispatchTest.java:97`, `:461` (`responseHeadersInjectedAndRemoved`).

Surface: domain form. Board: none.

## 26. Timeouts and body size

**PARTIAL.** Request timeout on the address upstream (`S/upstream/kinds/AddressUpstreamKind.java:67`), connect timeout
on passthrough (`S/upstream/kinds/TlsPassthroughUpstreamKind.java:48`). No per-host maximum request body size
(searched `max_body`, `client_max`, `MAX_ENTITY_SIZE`).

- Tests: `T/test/UpstreamProtocolTest.java:620`.

Surface: site form, "Forwarding" section. Board: none.

## 27. Advanced raw configuration box -- REJECTED (argued)

NPM: "Advanced Nginx configuration available for super users", a free-text config snippet per host.

**REJECTED.** There is no nginx; the proxy is Hohenheim's own, and every route is a typed record that passes
route-claim, certificate-coverage and protected-path invariants. A free-text escape hatch would bypass exactly those.
The things people put in that box (headers, locations, timeouts, websocket, HSTS) are typed fields here (clauses
19, 22, 24, 25, 26); a missing one is an OPEN typed field, not a reason for a text box. This is this document's
argument, not a recorded owner decision.

---

## Security

## 28. Block common exploits, WAF, CrowdSec

NPM: "Block Common Exploits". NPMPlus: CrowdSec (and AppSec) support, rate limits on its own UI.

**PARTIAL.** Hohenheim has a different shape: per-IP threat scoring (`S/security/ThreatScorer.java:20`), automatic
bans (`S/security/BanService.java:42`) enforced in nftables (`S/security/NftService.java:16`) and at the listener,
reputation lookups (`S/security/ReputationBanPolicy.java:23`, Spamservice), an SSH brute-force watcher
(`S/security/SshAuthWatcher.java:24`), and an auth-attempt throttle (`S/proxy/auth/ProxyAuthThrottle.java:29`). Not
offered: request-pattern blocking (SQLi, path traversal), a CrowdSec bouncer, per-site request rate limits
(searched `exploit`, `waf`, `sqli`, `crowdsec`).

- Tests: `T/test/BanEnforcementTest.java:54`, `T/server/security/SshAuthWatchTest.java:274`,
  `T/test/ProxyRateLimitTest.java:65`.

Surface: `/admin/bans`, Spamservice pages, `/admin/settings`. Boards: Settings ("Blocking and firewall"), Activity
(a ban narrated as "was blocked for 1 hour"); the bans list itself has none.

## 29. GeoIP / country blocking -- OPEN

NPM: geoip2 module through custom config. NPMPlus: listed in its config options.

**OPEN.** No country lookup anywhere (searched `geoip`, `maxmind`, `GeoLite`, `country`). It would be an access-rule
type ("country allow/deny") beside the IP rules, so it lands in the existing rule tree and App-Protect's "From certain
networks". Value: medium for small operators (block whole regions of noise); effort medium (a database and its
updates).

Surface: none.

## 30. Cache assets -- REJECTED (argued)

NPM: "Cache Assets", nginx proxy caching of static files.

**REJECTED.** No response cache exists (searched `proxy_cache`, `Cache-Control` in the proxy). A shared cache in front
of tenant apps serves one visitor's response to another the moment an app forgets `Vary` or `private`, and it adds
purge semantics to every deploy. The useful half, validators and cache headers on Hohenheim's own static serving, is
OPEN in `netlify-use-inventory.md` 24. This document's argument, not a recorded owner decision.

## 31. Compression

NPMPlus: zstd and brotli in addition to gzip.

**PARTIAL.** Gzip (and deflate) are always on (`S/proxy/ProxyServer.java:107`); no brotli, no zstd, no per-site switch.

- Tests: `T/test/UpstreamProtocolTest.java:914`.

Surface: none. Board: none needed.

## 32. Serving static files directly

NPMPlus: "serving files or php directly with fancyindex support".

**IMPLEMENTED** for files: the static upstream kind serves a host directory with an index and an optional listing
(`S/upstream/kinds/StaticUpstreamKind.java:40`, `autoindex`). PHP runs as a WordPress-style template instance, not
inside the proxy (`docs/wordpress.md`).

- Tests: `T/test/StaticSiteTypeTest.java:53` (`autoindexIsOnByDefault`), `:88`.

Surface: `/admin/sites`, upstream kind "Static". Board: Online-1 ("Static files").

## 33. Load balancing and upstream health checks -- OPEN

NPMPlus: "loadbalancing over multiple upstreams".

**OPEN.** One backend per upstream (`S/proxy/BackendChoice.java:14`); no round robin (searched `round robin`,
`load balanc`). Health is reported only by instance upstreams (`S/application/InstanceUpstreamHandler.java:102`);
address upstreams default to up (`S/sitetype/SiteRequestHandler.java:29`) and are never probed. Value: medium for
people fronting their own redundant services; low for Hohenheim-run apps, whose health-gated release already avoids
downtime (`coolify-use-inventory.md` 6).

Surface: site list health column only. Board: App-Overview's health line (instance upstreams only).

## 34. Client certificates (mTLS) per host -- OPEN

NPMPlus: "mTLS with client certificates per host".

**OPEN.** Searched `setNeedClientAuth`, `setWantClientAuth`, `mtls`: nothing. It would be an access-rule type
("client certificate from CA X"). Value: low to medium (internal admin tools, device APIs).

Surface: none.

## 35. Encrypted Client Hello and TLS extras -- REJECTED (argued)

NPMPlus: hardened TLS on a patched nginx, enforced cipher order, certificate compression, optional ECH.

**REJECTED** as a goal of its own. TLS terminates in the JVM's TLS stack; the JDK in use offers no ECH
[unverified], and certificate compression is a stack property too. Carrying a second TLS implementation for these is
not worth it; they arrive when the JDK has them. This document's argument.

## 36. Security headers by default

NPMPlus: "security headers always sent, fingerprinting headers stripped".

**PARTIAL.** Response headers can be set and removed per domain row (clause 25), but no default set is sent
(searched `X-Content-Type-Options`, `Referrer-Policy`, `X-Frame-Options`: no hit). An installation-wide default rule
set is OPEN and small.

Surface: domain form, per row. Board: none.

## 37. Internationalized domain names

NPMPlus: punycode domain support.

**PARTIAL.** A hostname label accepts only `a-z`, `0-9` and inner hyphens (`C/net/Hostnames.java:206`, `:213`), so a
punycode name (`xn--...`) is accepted and a Unicode name is refused, with no conversion (searched `IDN.`, `toASCII`).

Surface: domain form. Board: none.

---

## People and records

## 38. Users, roles and per-item permissions

NPM: administrator and standard users, per-area permission levels (view, manage, hidden), "Item Visibility" (all items
or only the user's own).

**IMPLEMENTED,** finer than NPM: zenit-auth users and roles (`S/cms/HohenheimPanel.java:213` at `2eb75017`),
per-record grants with capabilities (`S/auth/HohenheimAccess.java:29`), projects whose members inherit grants
(`coolify-use-inventory.md` 4), and a delegated `/manage` panel showing only what the user may manage
(`S/cms/ManagePanel.java:46`). Whether a creator is granted the record they create, NPM's "only items I created",
is [unverified] here.

- Tests: `T/test/ManagePanelTest.java:158` (`grantingThroughTheAccessTabUnlocksExactlyTheGrantedSite`).

Surface: `/admin/users`, `/admin/roles`, record Access tabs, `/manage`. Boards: App-Protect shows "Who may manage this
app" read-only; no board designs inviting a user or granting access.

## 39. Two-factor authentication

**IMPLEMENTED** in zenit-auth (`docs/hohenext-roadmap.md:35`): TOTP enrolment, login gate, backup codes, on `/account`.
The algorithm is tested in `zenit-auth/src/test/java/be/elevenways/zenit/auth/server/TotpTest.java:43`; the login-gate
test was not located [unverified]. Hohenheim has no test of its own.

Surface: `/account` (framework page). Board: none needed (framework).

## 40. OIDC login for the admin

NPMPlus: "OIDC login for the web UI".

**PARTIAL.** Admin single sign-on is the Proteus provider only (`S/ServerMain.java:412`); no generic OIDC client.

- Tests: `T/test/SsoLoginTest.java:33`.

Surface: `/login`. Board: none.

## 41. Audit log

NPM: created/updated/deleted/enabled/disabled/renewed events.

**IMPLEMENTED.** Framework activity log over every model change plus named actions
(`C/HohenheimActivityAction.java:22`), browsed at `S/cms/AdminActivityResource.java:87`, per record on the History tab.

- Tests: `T/test/AdminActivityListTest.java:177` (`activityListJourney`).

Surface: `/admin/activity`, record History tabs. Board: Activity (narrated per command, gap G4).

## 42. Access and error logs per host

NPM: per-host access and error log files.

**PARTIAL.** One optional combined-format access log for the whole proxy (`S/proxy/AccessLog.java:20`, path setting
`C/HohenheimSettings.java:397`); not per host, and nothing in the admin reads it.

- Tests: `T/server/proxy/AccessLogTest.java:63` (escaping only).

Surface: none (a setting). Board: none.

## 43. Traffic analytics (GoAccess) -- REJECTED (owner decision)

NPMPlus: GoAccess dashboards at `/goaccess`.

**REJECTED** by an owner call: "Per-site traffic stats DECIDED: statistics are out of scope (owner call, 2026-07); the
`StatsCollector` scaffolding was deleted" (`docs/hohenext-roadmap.md:33`). Clause 42's log file is the hand-off point to
an external analyzer.

## 44. Dashboard

NPM: counts of proxy hosts, redirections, streams, 404 hosts.

**IMPLEMENTED.** Stat tiles for sites, certificates, access lists and active bans (`S/cms/AdminDashboard.java:72`), plus
attention items.

- Tests: `T/test/DashboardRoleGatingTest.java:52`.

Surface: `/admin` dashboard. Board: Main, Empty-Dashboard.

## 45. API

NPM: a REST API (NPMPlus adds a Swagger UI at `/api/docs`).

**PARTIAL.** Key-authenticated `/api/v1` lanes for sites (`C/HohenheimEndpoints.java:718`), domains (`:835`), access
lists (`:868`), DNS zones (`:346`) and hosts (`:1200`), documented in `docs/paas-api.md`, plus the `tools/hoh` CLI. Not
offered: updating a site or domain (no PUT/PATCH route in `HohenheimEndpoints`), any certificate call, a published
OpenAPI document.

- Tests: `T/test/SiteApiTest.java:145`, `T/test/AccessListApiTest.java:117`, `T/test/HohCliTest.java:28`.

Surface: API only. Board: none needed.

## 46. Import from NPM -- OPEN

**OPEN.** The only importer reads the Node Hohenheim's Mongo `sites` collection (`docs/legacy-import.md:5`, CLI
`tools/hoh-import-legacy`); nothing reads NPM's database. NPM's model maps cleanly: proxy host to an address site plus
domain rows, redirection host to a redirect site, access list to an access list with basic-auth and IP rules, custom
certificate to a custom certificate. Value: high for adoption (the whole point of this inventory is people moving
from NPM); effort medium, through the existing write lanes and refusing what it cannot model (streams, raw config).

Surface: none.

## 47. Backup and restore of the configuration

NPM: users back up its data volume themselves.

**IMPLEMENTED,** beyond NPM: a nightly control-plane backup (`S/task/BackupControlPlane.java:48`) to a configured target
(`C/HohenheimSettings.java:581`), restored by a command-line flag (`S/cli/RestoreControlPlaneCommand.java:35`).

- Tests: `T/test/database/ControlPlaneBackupTest.java:64`.

Surface: `/admin/backup-targets` and settings; restore is CLI only. Board: Settings ("Backups", with targets and a
target picker).

---

## Findings from writing this document

- `S/cms/HohenheimPanel.java` was being restructured while this was written (an `AppParts` entry, W4), so citations into
  `cms/` are pinned to `2eb75017`.
- Certificate key type is hardcoded RSA 2048 in two order paths (`S/tls/AcmeService.java:906` and `:932`); an ECDSA
  option would be one setting read in both.

## Screens the redesign still lacks

Capabilities that exist (IMPLEMENTED or the working half of a PARTIAL) and that no board draws as a task, ranked by how
often an NPM user would reach for them:

1. Path routing and per-address headers (24, 25, 36): the most-used "advanced" NPM tab. Today a domain form field
   set; no board draws "send /api to another service" or "add a header".
2. Custom certificate upload form (14): Online-3 offers the choice, no board draws the form (paste PEM, see names and
   expiry before saving).
3. Bans list and unban (28): Activity narrates a ban, Settings has "Blocking and firewall", but no board shows who is
   blocked now and lifts a ban; the most common support task after a false positive.
4. Websocket, timeouts, upstream TLS validation (1, 22, 26): the "Forwarding" options of an existing-service app;
   needed on the App page's Configuration, which no board draws.
5. Users, roles and granting access (38): App-Protect shows "Who may manage this app" read-only; inviting a person
   and granting them an app has no board.
6. DNS-01 and wildcard certificate request, including the manual TXT continue step (12, 13).
7. Unknown-host behaviour and proxy trust settings (2, 5): the Settings board has no section that holds them.
8. Enable/disable an app (6): no board has the switch or says what a disabled app answers.
9. HSTS per address (19): belongs on the Domains row or the address card.
10. Certificate download (17): one row action on Domains.
