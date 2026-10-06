# The Node-original parity inventory

Written 2026-10-06. Every user-facing capability of the ORIGINAL Node.js Hohenheim
(`origin/master`): does the Java rewrite offer it, where does an admin meet it today, and
does a board of the admin redesign (`docs/admin-redesign-plan.md` section 3) cover it.
The owner's bar is that the rewrite offers everything the original did; this decides
which original features are missing and which exist without a designed task screen.

Verdicts (same meaning as `docs/coolify-use-inventory.md`):

- **IMPLEMENTED** -- the mechanism exists in the rewrite; the cited code was read.
  A named test was confirmed to EXIST; its body was not read unless the clause says so.
- **PARTIAL** -- precisely what works and precisely what does not.
- **OPEN** -- missing, and no recorded decision rejects it.
- **REJECTED** -- an owner decision or an argued design record says the rewrite does
  not do this. A rejection with no recorded argument is filed OPEN, not REJECTED.

### How this document was built

- Node original: `origin/master` at `d5de49030d4555ea289c772e0afbb7ac214a6944`
  (2026-02-16, "Add `collect_site_stats` setting ..."), version 0.7.1-alpha. Extracted
  read-only with `git archive origin/master` into `/tmp/claude-1000/hoh-node-orig/`
  (never executed). Every file under `app/`, plus `server.js`, `package.json`,
  `README.md`, `CHANGELOG.md` and its `CLAUDE.md`, was read; `app/lib/stats_collector.js`,
  the dashboard elements and `app/task/aggregate_site_stats.js` were skimmed only (the
  capability they serve is owner-rejected, clause 22). Citations are `O:path:line`
  (= `origin/master:path:line`).
- Java rewrite: local `master` (tracking `origin/java-rewrite`) at `2eb75017`
  (2026-10-06). Citations use three prefixes:
  `S/` = `src/server/java/be/elevenways/hohenheim/server/`,
  `C/` = `src/common/java/be/elevenways/hohenheim/`,
  `B/` = `src/browserTest/java/be/elevenways/hohenheim/`.
- CAVEAT: another agent was editing `S/cms/*` (the W4 navigation: `HohenheimPanel`,
  `DomainParts`, `BanParts`, `AppOverview`, ...) while this was written. Line numbers in
  those files are the working tree of 2026-10-06 and may drift; the W4 cluster layout
  quoted below (`S/cms/HohenheimPanel.java:213-224`) is UNCOMMITTED work.
- Prior work built on, not redone: `docs/hohenext-roadmap.md` Phase 0
  (`:13-56`), `docs/gap-audit-2026-08-12.md`, `docs/phase0-design.md`,
  `docs/legacy-import.md`. Parity items those already closed, re-checked here:
  regex host matching, scheduled maintenance tasks, custom error pages, IPv6 listener,
  connection-stage IP rejection (roadmap `:15-26`); stale-cert cleanup and brute-force
  hostname guards (`:37-38`); TOTP (`:35-36`); per-site SSO gate (gap audit `:239`,
  "roadmap Phase 5 follow-ups ... all shipped"); statistics DECIDED out of scope
  (`:33-34`); per-request DB logging deferred by the owner (`:56`). The roadmap's
  "system-user discovery + per-site uid execution" and "Unix-socket upstreams" closures
  were later partly UNDONE by the host-user lane deletion (`docs/phase0-design.md:356-385`);
  clauses 35-40 carry the current truth. The roadmap's only remaining Phase 0 item, "SNI
  cache staggered refresh / backoff / in-flight dedup (optimization)" (`:38-39`), is
  discussed under clause 12.
- Tools: grep over both trees, zemble `search` for framework mechanisms (TaskAdmin,
  TranslationResource). Nothing was built, run or tested.
- Excluded as not user-facing: the `Domain` model (`O:app/model/domain_model.js:41-48`),
  loaded at boot but never consulted (no `getDomain` caller) and not in the sidebar; the
  dev-only `/dev/login` (`O:app/config/routes.js:9-16`); the README's install notes
  (`O:README.md:28-78`), which `tools/install-host.sh` owns now.

### The counts

46 numbered clauses; boards cited: Main, Apps-List, Domains, Online-1..4, App-Overview,
App-Problem, App-Blocked, App-Console, App-Protect, Host-Admit, Activity, Settings.

| verdict | count | clauses |
| --- | --- | --- |
| IMPLEMENTED | 31 | 1-9, 11, 13-18, 20, 23-27, 29, 34, 36, 37, 41, 42, 44, 45, 46 |
| PARTIAL | 6 | 10 (fallback), 12 (automatic certificates), 19 (fail2ban log), 32 (database tools), 38 (keeping an app up), 40 (process control screen) |
| OPEN | 5 | 28 (Proteus site users list), 30 (tasks screen), 31 (microcopy editor), 33 (Sentry), 39 (several processes per app) |
| REJECTED | 4 | 21 (DB request log, owner-deferred), 22 (statistics, owner), 35 (run as any host user), 43 (broadcast API) |

---

## A. Sites and hostnames

### 1. Sites as typed records with per-type settings -- IMPLEMENTED

Original: a `Site` has name, slug (Sluggable), `site_type` (enum over the `site_type`
class group) and a `settings` sub-schema chosen by the type
(`O:app/model/site_model.js:35-83`); Chimera list shows type/name/slug, edit has tabs
General, Domains, Security, Control (`:92-133`). Saving rebuilds the dispatcher
(`:145-177`).
Rewrite: `SiteModel` with NAME, SLUG, UPSTREAM_KIND, ENABLED, SETTINGS
(`schemaFrom` the kind), INSTANCE_ID, ACCESS_LIST_ID, AUTH_PROVIDER_ID, DELETED_AT
(`C/model/SiteModel.java:37-152`); kinds registered under `S/upstream/kinds/`;
routing-relevant writes rebuild the proxy through `ProxyReloadHooks` (CLAUDE.md).
Extras the original lacked: enable/disable, trash, revisions, quota bucket.

Surface: Sites list and site record (`S/cms/SiteParts.java`: landing tab
`AppOverview`, tabs Overview, Domains, Protected paths at `:150`); in the W4 working
tree reached through the Apps list (`AppParts`, `S/cms/HohenheimPanel.java:213-215`).
Boards: Apps-List, App-Overview, App-Problem, App-Blocked.

### 2. Hostname matching: exact, wildcard, regex with capture groups -- IMPLEMENTED

Original: exact hostnames, `*`/`?` wildcards and `/regex/` entries
(`O:app/lib/site.js:383-387`, `:421-431`); named groups returned for socket
placeholders (`:220-229`); brute-force guards (no `git.`/`gitlab.`, `www.www.`,
`notexist`, `.www`, `wwww`, dot-count ceiling, `project` group without a dot;
`:182-226`); negative and positive regex caches
(`O:app/lib/site_dispatcher.js:108-112`, `:1372-1432`).
Rewrite: `SiteDomainModel.MATCH_TYPE` exact/wildcard/regex
(`C/model/SiteDomainModel.java:28-38`, `:161`); the same guards, labelled "faithful to
the Node original" (`S/proxy/RouteResolver.java:132-177`); groups attached as
`SiteDispatcher.MATCHED_GROUPS` (`S/proxy/SiteDispatcher.java:295-297`); regex match
cache `S/proxy/CachedRegexMatches.java`. Tests: `B/test/RegexHostnameGuardTest`,
`B/test/WildcardMatchTest`, `B/test/WildcardSpecificityTest`, `B/test/RegexCaseFoldTest`.

Surface: the domain form (Domains tab of a site; Domains list `domains`). Board: Domains
(the list); the add/edit form itself has no board (see section "Screens").

### 3. Per-hostname listen address (`listen_on`) -- IMPLEMENTED

Original: each domain entry picks local IPs (or "Any IP") it answers on; stale IPs shown
as "Old:" (`O:app/model/site_model.js:55`, `O:app/lib/site.js:144-168`,
`O:app/lib/site_dispatcher.js:515-566`).
Rewrite: `SiteDomainModel.LISTEN_ON` (`C/model/SiteDomainModel.java:166`), matched by
`S/proxy/ListenerAddressMatcher.java:40-51` (unknown listener address fails closed);
the form offers the discovered local addresses (`S/cms/DomainParts.java:228`). Test:
`B/server/proxy/ListenerAddressMatcherTest`.

Surface: domain form, advanced field. Board: none names it.

### 4. Per-hostname request headers, set or unset -- IMPLEMENTED

Original: `domain[].headers[]` name/value; an empty value deletes the header
(`O:app/lib/site_dispatcher.js:1886-1900`).
Rewrite: `CUSTOM_HEADERS` plus `RESPONSE_HEADERS` (`C/model/SiteDomainModel.java:229-236`);
"a blank value means remove" (`S/proxy/HeaderRule.java:3`). Response headers and HSTS
(`:218-227`) go beyond the original.

Surface: domain form. Board: none.

### 5. Excluding a hostname from Let's Encrypt -- IMPLEMENTED (meaning shifted)

Original: `exclude_from_letsencrypt` dropped the hostname from the automatic certificate
list (`O:app/model/site_model.js:64`, `:253-283`).
Rewrite: `EXCLUDE_FROM_LETSENCRYPT` (`C/model/SiteDomainModel.java:237`). Because issuance
is now an explicit order (clause 12), the flag REFUSES an order naming the host
(`S/tls/CertificateAuthority.java:228-235`) and hides "Get a certificate" on that domain
(`S/cms/CertificateOperationHandlers.java:78-85`).

Surface: domain form. Board: Domains (the dead row action), not the flag.

## B. Site kinds

### 6. Proxy site: URL, unix socket with placeholders, ignore certificates, Location rewrite -- IMPLEMENTED

Original: `url`, `socket` (with `{group}` placeholders from regex hostnames),
`ignore_certificates` (`O:app/lib/site/proxy_site.js:22-93`); Location headers pointing
at the upstream host are rewritten to a path (`:122-162`).
Rewrite: the `address` kind: forward_scheme/host/port, websocket_upgrade,
ignore_certificates, rewrite_location, socket, request_timeout, delay
(`S/upstream/kinds/AddressUpstreamKind.java:50-90`); socket placeholders resolved from
`MATCHED_GROUPS` (`:295-328`). Tests: `B/test/ProxySocketDispatchTest`,
`B/test/UnixSocketBridgeTest`. A legacy URL carrying a path is unmappable, but the
original ignored that path too (`proxy_site.js:76-81` keeps hostname/port/protocol only).

Surface: site form, settings sub-form of the kind. Boards: Apps-List, App-Overview;
whether Online-1 "What" offers "forward to an address" is [unverified].

### 7. Static site -- IMPLEMENTED

Original: `path`, `indexes`, `autoindex`, `show_hidden_files`, `fallback_file`
(`O:app/lib/site/static_site.js:63-116`).
Rewrite: `static` kind with root_path, autoindex, indexes, show_hidden_files, delay,
fallback_file (relative to the root) (`S/upstream/kinds/StaticUpstreamKind.java:35-57`,
`:93-97`). Test: `B/test/StaticSiteTypeTest`.

Surface: site form. Boards: Apps-List, App-Overview.

### 8. Redirect site -- IMPLEMENTED

Original: `target_url`, `is_permanent` (301/302), path NOT preserved
(`O:app/lib/site/redirect_site.js:24-59`).
Rewrite: `redirect` kind: target_url, http_status, preserve_path (default false),
delay (`S/upstream/kinds/RedirectUpstreamKind.java:30-47`, `:83-85`). Test:
`B/test/RedirectSiteTypeTest`.

Surface: site form. Boards: Apps-List, App-Overview.

### 9. Per-site forwarding delay -- IMPLEMENTED

Original: `settings.delay` ms before forwarding (`O:app/lib/site.js:110-112`, `:767-773`).
Rewrite: `UpstreamSettings.delay()` (`S/upstream/kinds/UpstreamSettings.java:63-68`) on
address/static/redirect, honoured by the dispatcher's delay scheduler
(`S/proxy/SiteDispatcher.java:544-554`). The instance kind declares no delay
(`docs/legacy-import.md:92`).

Surface: site form. Board: none.

## C. The proxy itself

### 10. Fallback address for unmatched hostnames -- PARTIAL

Original: an unmatched request (and websocket) is PROXIED to `fallback_address`, e.g.
"your apache server" at `http://localhost:8080` (`O:README.md:98-100`,
`O:app/lib/site_dispatcher.js:1708-1710`, `:1477-1479`).
Rewrite: setting `proxy.fallback_address` exists (`C/HohenheimSettings.java:130`), but an
unmatched hostname gets a 302 REDIRECT to it (`S/proxy/SiteDispatcher.java:333-337`),
pinned by `B/test/PathRoutingTest` (`:107-131`, read). A browser sent to
`http://localhost:8080` reaches nothing, so the "Apache behind Hohenheim" topology the
original documented is not reproducible through this setting; it needs one address site
per hostname or a catch-all regex site.

Surface: Settings (proxy group). Board: Settings (General) [unverified that the field
lands in an operator section].

### 11. Force HTTPS, globally and per site -- IMPLEMENTED

Original: global `force_https` (default true) or per-site `letsencrypt_force`; 302 to the
HTTPS port (`O:app/config/bootstrap.js:46-51`, `O:app/lib/site_dispatcher.js:1716-1734`).
Rewrite: per-domain FORCE_SSL plus the FORCE_SSL_AUTO latch
(`C/model/SiteDomainModel.java:187-202`); global `proxy.force_https` (default true,
`C/HohenheimSettings.java:134-137`) applies only once a working certificate covers the
name; force fails closed while HTTPS termination is down
(`S/proxy/SiteDispatcher.java:344-363`). Stricter and safer than the original.

Surface: domain form, Settings. Boards: Online-3, Domains (HTTPS state), Settings.

### 12. Automatic Let's Encrypt certificates -- PARTIAL

Original: Greenlock issues a certificate ON DEMAND at the first TLS hello for any
matched hostname, regex/wildcard matches included (each concrete hostname gets its own
certificate), with per-site `letsencrypt_email`, staging switch, in-flight dedup and a
5 min to 2 h error backoff (`O:app/lib/site_dispatcher.js:833-880`, `:940-1128`,
`:990-1010`, `:24-31`); HTTP-01 served at `/.well-known/acme-challenge/`
(`:1182-1219`). No click: adding a hostname was enough.
Rewrite, what works: acme4j issuance with HTTP-01 and DNS-01 (manual TXT, command hook,
internal zone publisher), per-certificate email and challenge
(`C/model/CertificateModel.java:72-84`, `:127`, `:154-180`); automatic RENEWAL with
expiry alerts and error backoff (`S/tls/AcmeService.java:638-660`, `:807`); staging and a
custom directory (`C/HohenheimSettings.java:256-281`); a reachability pre-check before
HTTP-01 (`S/cms/CertificateOperationHandlers.java:130-132`). Test:
`B/test/CertificateRequestJourneyTest`.

What does not: there is no automatic FIRST issuance. Every certificate is an explicit
order (the only caller of `requestCertificate` is
`S/cms/CertificateOperationHandlers.java:155`, behind the REQUEST /
REQUEST_FOR_DOMAIN operations at `:46-52`). A regex or wildcard hostname can only be
covered by a DNS-01 wildcard order (`:79`, `:107-108`); the original's per-hostname
HTTP-01 certificates for regex-matched names have no equivalent. The roadmap's open
"SNI in-flight dedup/backoff" item (`docs/hohenext-roadmap.md:38-39`) is largely moot:
the SNI path only resolves stored certificates (`S/tls/SniKeyManager.java:17`).
The planned `put_online` flow (board Online-3/Online-4) orders the certificate as one of
its steps, which restores "no extra click" for apps created that way only.

Surface: "Get a certificate" row action on a domain, Certificates list (W4: Domains
cluster). Boards: Domains, Online-3, Online-4.

### 13. Bring your own certificate -- IMPLEMENTED

Original: drop PEM files into Greenlock's store directory (`O:README.md:142-157`).
Rewrite: Certificates create form stores a `custom` provider row from pasted PEMs,
validated, auto_renew off (`S/cms/CertificateParts.java:369-378`).

Surface: Certificates list, create. Board: none (Domains shows only the certificate line).

### 14. Cleaning up certificates nobody uses -- IMPLEMENTED

Original: task `CleanupGreenlockDomains` every 6 h (`O:app/task/cleanup_greenlock_domains.js:22`).
Rewrite: task `CleanOrphanCertificates`, daily 04:45 (`S/task/CleanOrphanCertificates.java:30-47`).

Surface: none (background). Board: none needed.

### 15. HTTP/2, websockets, keep-alive, long timeouts -- IMPLEMENTED

Original: HTTP/2 with HTTP/1 fallback on HTTPS, websocket proxying, 10 min proxy timeout
(`O:app/lib/site_dispatcher.js:751-756`, `:1446-1497`, `:1798-1799`).
Rewrite: `ENABLE_HTTP2` (`S/proxy/ProxyServer.java:233`), `S/sitetype/WebSocketUpgrades.java`,
per-site `websocket_upgrade` and `request_timeout` up to 3600 s
(`S/upstream/kinds/UpstreamSettings.java:23-58`).

Surface: site form. Board: none needed.

### 16. Forwarding headers, chained Hohenheims, loop detection -- IMPLEMENTED

Original: X-Forwarded-For/Host/Proto, X-Real-IP, X-Proxied-By/X-Hohenheim-Id; a request
carrying a key from `remote_proxy_keys` keeps its original client chain; 508 on a loop
(`O:app/lib/site_dispatcher.js:1820-1880`, `:1679-1682`).
Rewrite: `S/proxy/ForwardingHeaders.java:22-33`, loop check `:87`, 508 at
`S/proxy/SiteDispatcher.java:267-270`; `proxy.trusted_proxy_keys`
(`C/HohenheimSettings.java:164-170`, `S/proxy/ProxyScheme.java:119-146`); PROXY protocol
v2 trusted sources added (`C/HohenheimSettings.java:89`). Test:
`B/test/ForwardedCredentialsTest`.

Surface: Settings only. Board: Settings.

### 17. Error pages with operator messages -- IMPLEMENTED

Original: 404 "no such domain", 502 unreachable/refused, rendered from
`O:app/view/static/error.hwk` with `not_found_message`, `unreachable_message`,
`base_url_for_template` (`O:app/lib/site_dispatcher.js:1548-1645`,
`O:app/config/bootstrap.js:53-72`).
Rewrite: `S/proxy/ErrorPages.java:41-88` (404, 502, 503 HTTPS-required, dev offline),
localized, with the two message overrides (`C/HohenheimSettings.java:144-158`).
`base_url_for_template` is gone (the page is self-contained); "refused" and
"unreachable" are one 502. Test: `B/test/ErrorHandlingTest`.

Surface: Settings only. Board: Settings.

### 18. IP reputation: drop scanners -- IMPLEMENTED

Original: per-IP reputation counting unique hostname misses; more than 25 drops the
connection before TLS (`O:app/lib/reputation.js:73-80`, `:102-166`,
`O:app/lib/site_dispatcher.js:759-766`).
Rewrite: weighted threat score with domain-miss events, auto-ban past
`domain_miss_ban_threshold` (default 25), decay per hit, never-ban list, nftables
programming, manual ban/lift (`S/proxy/SiteDispatcher.java:247-253`, `:302-323`;
`S/security/ThreatScorer.java:73`, `:183-185`; `C/HohenheimSettings.java:659-690`;
`C/model/BanModel.java:31-85`; `S/cms/BanParts.java:53-58`). Enforced at the TCP
listener before TLS (`docs/hohenext-roadmap.md:21-26`). Tests: `B/test/BanEnforcementTest`,
`B/test/TlsReputationTest`.

Surface: Bans list (`bans`; W4: Access cluster), security settings. Board: Settings names
a "blocking" section [unverified whether that is the ban list or the settings group].

### 19. fail2ban domain-miss log -- PARTIAL

Original: a threshold-filtered `DOMAIN_MISS ip=... domain=...` line in
`/var/log/hohenheim/domain-misses.log` for an external fail2ban jail, with documented
filter/jail/logrotate (`O:app/lib/site_dispatcher.js:357-454`, `O:README.md:230-312`).
Rewrite: the same threshold lands a `DOMAIN_MISS` security event in the database
(`S/proxy/SiteDispatcher.java:307-322`) and the in-process ban (clause 18) replaces the
jail. No log file is written: an operator who kept fail2ban gets nothing to match. No
document records the file as rejected.

Surface: Spamservice security events list (`spamservice-security-events`). Board: none.

### 20. Access log to file -- IMPLEMENTED (format changed)

Original: `log_access_to_file` to `/var/log/hohenheim/access.log`, line
`host: ip - - [date] "METHOD path PROTO" status size "referer" "ua"`
(`O:app/model/request_model.js:63-94`, `O:app/config/bootstrap.js:137-149`).
Rewrite: `logging.access_to_file`/`access_path` (`C/HohenheimSettings.java:392-400`),
escaped combined-style line `ip - - [instant] "METHOD path PROTO" status size "host" "ua"`
(`S/proxy/AccessLog.java:57-62`), logrotate-safe writer (`:117-154`). The vhost moved from
a prefix into the referer slot and the Referer is no longer logged. Test:
`B/server/proxy/AccessLogTest`.

Surface: Settings only; no in-admin viewer. Board: none.

### 21. Access log to the database (`Request` model) -- REJECTED (owner, deferred)

Original: `log_access_to_database` saved a `Request` row per hit (`O:app/model/request_model.js:38-67`).
Rewrite: "Deliberately deferred (owner): per-request DB hit logging (was not performant
in Node; revisit later)" (`docs/hohenext-roadmap.md:56`).

### 22. Traffic statistics and the live dashboard -- REJECTED (owner)

Original: per-site incoming/outgoing bytes and hit counters (`O:app/lib/site.js:647-743`),
a live Chimera dashboard (requests/s, total requests, connections, active sites,
processes, traffic chart, sites overview, activity feed;
`O:app/controller/dashboard_controller.js:27-74`,
`O:app/element/he_stats_overview_element.js:20-24`), `SiteStats` persistence with
minute/hour/day rollup and retention tasks (`O:app/model/site_stats_model.js:20-53`,
`O:app/task/aggregate_site_stats.js:20`, `O:app/task/cleanup_site_stats.js:19-49`).
Rewrite: "statistics are out of scope (owner call, 2026-07); the `StatsCollector`
scaffolding was deleted rather than wired" (`docs/hohenext-roadmap.md:33-34`). What
exists instead is per-INSTANCE live CPU, memory and network
(`S/cms/InstanceStatsPage.java:26-61`), which board App-Overview draws as Resources.

## D. Listeners and access control

### 23. Listening on a unix socket front -- IMPLEMENTED

Original: `proxyPort` may be a socket path, chmod from `socketfile_chmod`
(`O:app/lib/site_dispatcher.js:609-638`).
Rewrite: `proxy.http_socket_path` and `http_socket_permissions`
(`C/HohenheimSettings.java:54-70`), refusing to start without a trusted proxy key and
failing closed on unauthenticated requests (`S/proxy/SiteDispatcher.java:192-207`).
Test: `B/test/SocketFrontModeTest`.

Surface: Settings only. Board: Settings.

### 24. Dedicated IPv6 listener -- IMPLEMENTED

Original: `ipv6_address` adds a second HTTP server (`O:app/lib/site_dispatcher.js:645-648`).
Rewrite: `proxy.ipv6_address` (`C/HohenheimSettings.java:139-142`),
`S/proxy/ProxyServer.java:307-314`. No test references the setting (grep of `B/`).

Surface: Settings only. Board: Settings.

### 25. Basic auth per site -- IMPLEMENTED

Original: `basic_auth` array of `user:pass` strings on the site's Security tab; 401 with
the site name as realm (`O:app/model/site_model.js:76-80`, `O:app/lib/site.js:584-633`).
Rewrite: access lists of typed rules (basic auth, allowed/denied network, auth provider,
groups with satisfy any/all) (`C/model/AccessRuleModel.java:137-155`), attached to a site
or a protected path (`S/proxy/SiteDispatcher.java:412-429`); passwords are hashed
(argon2, `:404-410`). Legacy passwords do not migrate (`docs/legacy-import.md:27-30`, `:47`).
Tests: `B/test/AccessListEnforcementTest`, `B/test/SiteAccessControlTest`.

Surface: Access lists (W4: Access cluster), site form `access_list_id`, Protected paths tab.
Board: App-Protect.

### 26. Proteus SSO per site with a permission check and remember-me -- IMPLEMENTED (default differs)

Original: a site names a Proteus realm and a permission string, defaulting to
`hohenheim.site.<slug>`; no permission means 403; `acpl` persistent cookie on "remember"
(`O:app/model/site_model.js:68-74`, `:158-162`, `O:app/lib/site.js:489-575`,
`O:app/controller/proxied_acl_static_controller.js:50-77`,
`O:app/model/proteus_persistent_cookie_model.js:19-116`).
Rewrite: per-site auth provider gate (`S/proxy/SiteDispatcher.java:365-392`), Proteus
provider type (`S/auth/types/ProteusAuthProviderType.java:33-53`), required permission
on the provider or per access rule (`C/model/SiteAuthProviderModel.java:47-55`,
`C/model/AccessRuleModel.java:100-113`), persistent `acpl` cookie
(`S/proxy/auth/ProxyAuthKeys.java:29-30`) with session and persistent TTL settings
(`C/HohenheimSettings.java:870-885`). Test: `B/test/ProxyAuthGateTest`.
Difference: a blank required permission means ANY identity the realm authenticates
(`C/model/SiteAuthProviderModel.java:48`); there is no `hohenheim.site.<slug>` default.

Surface: site form `auth_provider_id`, access rules. Board: App-Protect ("sign-in").

### 27. Proteus realms registry -- IMPLEMENTED

Original: `ProteusRealm` records (title, endpoint, realm_client, private access_key)
in the sidebar (`O:app/model/proteus_realm_model.js:22-70`, `O:app/config/routes.js:92-95`).
Rewrite: `SiteAuthProviderModel` rows of type `proteus` with endpoint, realm_client,
secret access_key, authenticator (`S/auth/types/ProteusAuthProviderType.java:35-53`),
plus a `basic` provider type.

Surface: Auth providers list (`auth-providers`); in the W4 working tree it is in no
cluster (`S/cms/HohenheimPanel.java:216-224`), so it is reached from a site form or
rule. Board: none.

### 28. Who signed in to my protected sites (Proteus site users) -- OPEN

Original: `ProteusSiteUser` per realm (handle, names, read-only permissions), listed in
the sidebar (`O:app/model/proteus_site_user_model.js:21-91`, `O:app/config/routes.js:96-99`).
Rewrite: no equivalent model or list; proxy sessions live in `SiteSessionModel`
(`C/model/SiteSessionModel.java:18-22`), which no admin resource reads (grep of `S/cms`).

Surface: none. Board: none.

### 29. Admin login, users and permission groups -- IMPLEMENTED

Original: alchemy-acl users and permission groups, default `admin:admin`
(`O:README.md:138-140`, `O:app/config/routes.js:88-103`), per-route permissions such as
`hohenheim.site.kill` (`:18-64`).
Rewrite: zenit-auth with first-run `/setup`, TOTP (`docs/hohenext-roadmap.md:35-36`),
users and roles admin (`S/cms/HohenheimPanel.java`, `AuthAdminParts.users/roles`),
record grants and delegated `/manage` panel.

Surface: Users, Roles (W4: Access cluster). Board: none.

## E. Operating the installation

### 30. Tasks screen with "run now" -- OPEN

Original: `System.Task` in the sidebar (`O:app/config/routes.js:80-83`) listing the six
Hohenheim tasks and their schedules.
Rewrite: zenit-cms ships `TaskAdmin` ("parts a host adds to its panel": schedules with
Run now, live runs with Stop; `zenit-cms/src/server/java/be/elevenways/zenit/cms/server/task/TaskAdmin.java:41-43`),
but no panel in the workspace mounts it (its only references are itself and its own
browser test). A failed run surfaces as a dashboard attention item with no link
(`S/cms/AttentionCollector.java:252-275`).

Surface: none. Board: none.

### 31. Microcopy (interface text) editor -- OPEN

Original: `Microcopy` in the sidebar (`O:app/config/routes.js:84-87`).
Rewrite: zenit-cms has a "host-constructed translation editor"
(`zenit-cms/src/server/java/be/elevenways/zenit/cms/server/resource/TranslationResource.java:30-31`)
that `HohenheimPanel` does not mount. Low operator value; recorded so it is decided,
not forgotten.

Surface: none. Board: none.

### 32. Database tools -- PARTIAL [unverified]

Original: a sidebar link to Chimera's `/chimera/tools/database` (`O:app/config/routes.js:104-107`);
what that alchemy-chimera tool offers is [unverified] (the dependency is not in the repo).
Rewrite: control-plane backup and restore as offline commands
(`S/cli/BackupControlPlaneCommand.java:27`, `S/cli/RestoreControlPlaneCommand.java:35`) and
the `BackupControlPlane` task with a backup target setting; no in-admin database tool.

Surface: Settings (backup target), CLI. Board: Settings (Backups).

### 33. Error reporting to Sentry -- OPEN

Original: `alchemy-sentry` when `settings.sentry.endpoint` is set (`O:app/config/bootstrap.js:210-217`).
Rewrite: no Sentry (or other error-reporting) integration in hohenheim, zenit or
protoblast sources (grep). No recorded decision.

Surface: none. Board: none.

### 34. Local IP discovery -- IMPLEMENTED

Original: hourly `UpdateIpAddresses` task (`O:app/task/update_system_ip_addresses.js:19`).
Rewrite: `S/task/UpdateSystemIpAddresses.java:19-24`, consumed by the domain form and
the ban guard (`S/cms/DomainParts.java:228`).

Surface: indirect (domain form). Board: none needed.

### 35. Running a site as any host user -- REJECTED (argued design)

Original: hourly `/etc/passwd` scan (`O:app/lib/site_dispatcher.js:463-506`,
`O:app/task/update_system_users.js:19`), Node site field `user`, child spawned with that
uid/gid and HOME unset (`O:app/lib/site/node_site.js:55`, `:748-755`).
Rewrite: the host-user process lane was deleted; workloads run in containers under a
mapped uid with no host account (`docs/phase0-design.md:205-218`, `:356-374`).
`SystemUsers`/`UpdateSystemUsers` survive only for the managed spamservice child
(`:376-385`).

### 36. Choosing the Node.js version -- IMPLEMENTED (by runtime images)

Original: versions discovered from `which node`, `/usr/bin/node`, `/usr/local/bin/node`,
`n` and `n_locations` (`O:README.md:314-323`, `O:app/lib/site/node_site.js:95-330`,
`O:app/task/update_node_versions.js:19`).
Rewrite: seeded runtime images `node-22`, `node-16`, `node-12`, `node-10`
(`S/instance/RuntimeImageSeeder.java:50-71`), operator-extensible (`S/cms/RuntimeImageParts.java`).

Surface: Runtime images (W4: Settings cluster); picked on instance create. Boards:
Settings, Online-1.

## F. Running Node and Alchemy apps

### 37. Running an app: script, environment, readiness, port or socket -- IMPLEMENTED (by the instance tier)

Original: Node site fields script, node, user, wait_for_ready, environment_variables,
use_ports; child gets `PORT`/`PATH_TO_SOCKET` (`O:app/lib/site/node_site.js:49-85`,
`:700-760`); Alchemy sites add `--stream-janeway` and wait for ready by default
(`O:app/lib/site/alchemy_site.js:13-30`).
Rewrite: `workspace` and `application` instance kinds served by the `instance` upstream
(`S/upstream/kinds/InstanceUpstreamKind.java:94-110`), instance variables, template
readiness lines and start commands (`docs/phase0-design.md:269-290`). Legacy sites map to
an instance blueprint (`docs/legacy-import.md:91`, `:100`).

Surface: Instances (W4: through Apps), instance tabs. Boards: Online-1..4, App-Overview.

### 38. Keeping an app up: start on first request, minimum processes, restart after a crash -- PARTIAL

Original: no process runs until the first request starts one and holds the request up
to 60 s (`O:app/lib/site/node_site.js:1191-1218`); `minimum_processes` are started on
save and kept (`:1231-1267`); every exit triggers a restart, throttled to a 3 s pause
when more than 5 exits come fast (`:1006-1077`).
Rewrite: start/stop/restart operations (`C/instance/InstanceOperations.java:57-78`);
`crash_policy` `restart`, flap-protected, but default `none` and "only enforced while a
console session is attached" (`C/model/InstanceModel.java:281-305`); templates and
database containers set `restart` (`S/instance/InstanceTemplates.java:324`,
`S/database/DatabaseInstances.java:232`). A stopped workload answers 503
(`S/application/InstanceUpstreamHandler.java:91`); nothing starts it on demand.
Whether every application release has an observing session by default is [unverified].

Surface: instance header actions, instance settings. Boards: App-Overview, App-Blocked.

### 39. Several processes per app: min/max, CPU scaling, idle reap, sticky clients, isolate -- OPEN

Original: `minimum_processes`/`maximum_processes`; a process above 50% CPU for 15 s
starts another, an idle one for 3 min is killed above the minimum
(`O:app/lib/site/node_site.js:936-993`); requests are spread from a random start, skipping
isolated and >92% CPU processes, and a client fingerprint (IP + UA + language) sticks to
its process (`:1090-1189`); "isolate" stops new clients reaching a process and kills it
once its fingerprints expire (`:941-947`, `O:app/controller/static_controller.js:198-236`).
Rewrite: one serving container per application (`docs/phase0-design.md:184-198`); no
replica count, balancing or affinity anywhere in `S/` or `C/` (grep for replicas,
sticky, minimum_processes). The nearest recorded decision,
`docs/coolify-use-inventory.md:850-853`, rejects multi-SERVER autoscaling ("placement is
explicit"), not several replicas on one host, so it does not settle this.

Surface: none. Board: none.

### 40. The site Control tab: processes, start, kill, isolate, terminal, byte counters -- PARTIAL

Original: per process pid, start time, port, CPU, memory, status
(starting/ready/isolated), fingerprint count; "Start new process"; Kill, Isolate,
Terminal row actions; incoming/outgoing bytes
(`O:app/controller/static_controller.js:71-271`, `O:app/view/elements/site_control.hwk:4-33`,
`O:app/element/site_control_element.js:83-110`).
Rewrite: one workload per instance with Start/Stop/Restart/Deploy/Rollback, live CPU,
memory and network (`S/cms/InstanceStatsPage.java:26-61`), console, shell and exec tabs.
Missing: a per-process list and isolate (clause 39); byte counters (clause 22).

Surface: instance record tabs. Boards: App-Overview (Resources), App-Console.

### 41. Janeway terminal in the browser -- IMPLEMENTED

Original: xterm.js linkup to the child's Janeway stream with geometry and redraw
(`O:app/controller/static_controller.js:368-440`, `O:server.js:23-36`).
Rewrite: `console_kind` = `tty` runs the primary process behind a pseudo-terminal with
keystrokes and resize on the socket (`C/instance/ConsoleKind.java:30-33`,
`docs/interactive-console.md` "Running an Alchemy app").

Surface: instance Console tab (`console`). Board: App-Console.

### 42. Process output logs (Proclog) -- IMPLEMENTED

Original: each process's stdout saved as ANSI-to-HTML, 500 lines, throttled saves; the
last 5 logs per site viewable (`O:app/lib/site/node_site.js:529-600`,
`O:app/controller/static_controller.js:280-359`, `O:app/model/proclog_model.js:21-28`).
Rewrite: `InstanceLogModel` console episodes (`C/model/InstanceLogModel.java:37-51`),
listed on the console page (`S/cms/InstanceConsolePage.java:157-162`), pruned by
`S/task/CleanOldInstanceLogs.java`.

Surface: instance Console tab. Board: App-Console (whether past episodes are drawn there
is [unverified]).

### 43. Site API broadcast to all processes -- REJECTED (argued design)

Original: `X-Hohenheim-Key` + `X-Hohenheim-Action: broadcast` fans the body out to every
child over IPC (`O:app/lib/site/node_site.js:1279-1364`).
Rewrite: "It DIES with the lane" -- only meaningful with several host processes
(`docs/phase0-design.md:79-83`); `ForwardingHeaders` strips the key header
(`S/proxy/ForwardingHeaders.java:143-145`).

## G. Admin shell

### 44. Activity feed -- IMPLEMENTED (different shape)

Original: in-memory ring of "Site registered", "Process started", "Process crashed"
on the dashboard (`O:app/lib/stats_collector.js:104`, `:129-191`).
Rewrite: zenit `ActivityLog` over every mutation, "Recent activity" on the dashboard
(`S/cms/AdminDashboard.java:114`, `:129-150`), activity admin, and crash-loop alerts
(`S/notification/NotificationEvents.java:27-28`).

Surface: Dashboard, Activity (W4: Activity cluster). Boards: Main, Activity.

### 45. Editable settings -- IMPLEMENTED

Original: the `hohenheim` setting group, 25 settings (`O:app/config/bootstrap.js:15-191`).
Rewrite: the `hohenheim` group in `C/HohenheimSettings.java` (22 groups, among them proxy, ssl, logging and security) on the framework `SettingsPage`
(`S/cms/HohenheimPanel.java`, `settingsPage()`). `first_port`, `redirect_host`,
`n_locations` and `letsencrypt_challenge` left with what they served
(`C/HohenheimSettings.java:160-162`; challenge is per certificate now).

Surface: Settings (W4: Settings cluster). Board: Settings.

### 46. Operator console of Hohenheim itself -- IMPLEMENTED (different shape)

Original: the Janeway REPL of the running server, reached with `screen -r hohenheim`
(`O:README.md:204-228`).
Rewrite: no REPL; offline commands (`S/cli/*Command.java`, e.g. `--backup-control-plane`,
`--rehearse-migrations`) and the `tools/hoh` automation CLI over the API
(`docs/paas-api.md`).

Surface: CLI. Board: none needed.

---

## Screens the redesign still lacks

Capabilities that EXIST in the rewrite but have no designed task screen in
`docs/admin-redesign-plan.md` section 3. Ranked by how often an operator of the original
reached for them (judgement, from what the original put on its first screens and what a
proxy operator touches weekly).

1. **The address (domain) editor.** Clauses 2-5, 9, 11: match type, listen address,
   request and response headers, HSTS, path routing, strip path, force HTTPS, exclude
   from Let's Encrypt. The Domains board designs the LIST (app, points here, HTTPS
   state, certificate line); the form behind "Add address" and a row edit has no board.
   Every new hostname passes through it.
2. **Certificates.** Clauses 12-13: the list, a certificate's renewal error and next
   attempt, Reissue, Continue DNS, custom PEM upload. The Domains board shows one
   certificate line per address and "Get a certificate"; triaging a failing renewal or
   uploading an own certificate has no screen.
3. **Site upstream settings for address/static/redirect apps.** Clauses 6-9, 15: target,
   socket, timeout, delay, websocket, rewrite_location, ignore_certificates, root path,
   fallback file, redirect status. App-Overview has a Details card and W3 lists a
   Configuration tab for INSTANCES; whether a site-only app gets an editable
   Configuration board is [unverified].
4. **IP bans.** Clause 18: list with enforced/lifted/expired state, "Ban an IP", lift. In
   the W4 Access cluster but no board; the Settings board's "blocking" is ambiguous.
5. **Users, roles and 2FA.** Clause 29. In the W4 Access cluster, no board.
6. **Auth providers (Proteus realms).** Clause 27. App-Protect picks "sign-in" but the
   provider records themselves have no board and, in the W4 working tree, no cluster.
7. **Console log history.** Clause 42: past console episodes of an instance. App-Console
   designs Console, Shell and Run a command as modes; reading yesterday's crash output
   is not named.
8. **Security events (domain misses).** Clause 19: the replacement for the fail2ban log
   lives in the Spamservice security-events list, with no board and outside the clusters.
9. **Live instance stats.** Clause 40: App-Overview draws Resources usage bars; the
   live CPU/memory/network charts of the Stats tab are not on any board.
10. **Access lists as shared objects.** Clause 25: App-Protect makes the per-path list
    and calls the shared list "the advanced option"; editing a shared list's rule tree
    (groups, satisfy any/all, network rules) has no board.

## Original behaviour an operator would miss

Things the original made easy that the rewrite makes harder, even where the capability
is technically present.

1. **HTTPS with zero clicks.** The original issued a certificate at the first visit of
   any hostname it routed, regex and wildcard matches included. The rewrite needs an
   explicit order per name, and a regex or wildcard site needs a DNS-01 wildcard
   (clause 12). Until `put_online` lands, adding a domain leaves it on HTTP.
2. **"Everything else goes to Apache."** `fallback_address` used to PROXY unknown
   hostnames to a local backend; it now 302-redirects the browser to that URL, which
   for `http://localhost:8080` is a dead link (clause 10).
3. **Apps that just stay up.** A Node site started on its first request and restarted
   after every crash with no setting. An application instance defaults to crash policy
   `none`, enforced only with a console session attached, and a stopped one answers 503
   (clause 38).
4. **Spreading load over several processes**, with sticky clients and "isolate before
   kill" for draining one process (clause 39). There is one serving container per app.
5. **One glance at what is running.** The Control tab showed every process with CPU,
   memory and status plus the site's byte counters; the dashboard showed requests per
   second. Per-instance stats remain; traffic numbers are gone by owner decision
   (clauses 22, 40).
6. **Proteus permission by default.** A site with a realm and no permission string
   still required `hohenheim.site.<slug>`. In the rewrite a blank permission admits any
   identity the realm authenticates (clause 26). A migrated site loses that default
   unless the permission is typed in.
7. **Basic auth in one field.** The original took `user:pass` lines on the site's
   Security tab. The rewrite needs an access list, a rule and an attachment, and legacy
   passwords do not import (clause 25). App-Protect (P6) is designed to close this.
8. **fail2ban keeps working.** The domain-miss log file is no longer written, so an
   existing fail2ban jail silently stops banning (clause 19); the in-process bans
   replace it only once the operator trusts them.
9. **Access-log tooling.** The access log moved the vhost into the referer slot and
   dropped the Referer (clause 20); a parser written for the old line misreads it.
10. **Run as any Unix user.** The original's user picker over `/etc/passwd` is gone by
    design (clause 35); file ownership on the host is now a mapped uid, which matters
    to an operator who edits app files over SSH.
11. **Tasks list with a schedule.** The original listed its tasks; the rewrite only
    raises an attention item when one fails, with no way to see or run them (clause 30).
