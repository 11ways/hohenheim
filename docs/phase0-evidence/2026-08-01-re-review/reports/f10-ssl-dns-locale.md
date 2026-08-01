# F10 — certificate / DNS / app-tier half of the locale-correctness sweep

Scope: hohenheim (DNS family, ACME, CertificateStore), zenit-oidc
(`RedirectUriMatcher`), zenit-ai (`McpApiKeys`), thoth (`ClaudeProxy` — the recon
filed it under zenit-ai, it lives in `thoth`), spamservice (`SpamWordSeeder`).

Every site touched is in a `src/server` source set, so **`Locale.ROOT` is the legal
spelling at all 44 of them**. No `common`/`browser`/`client` fold exists in any of
these five repos, so `BlastString.lower/upper` was never the applicable spelling here.
Verified after the fact: `grep -rE '\.to(Lower|Upper)Case\(\s*\)'` over
`src/server` + `src/common` in all five repos returns nothing.

## Mid-task discovery: a build gate for this exact bug class landed at 11:00

While I was working, the agent owning the core half added
`CheckLocaleFoldsTask` to the protoblast Gradle plugin
(`protoblast/protoblast-gradle-plugin/src/main/java/be/elevenways/protoblast/gradle/CheckLocaleFoldsTask.java`),
wired via `ProtoblastGradlePlugin.configureLocaleFoldGuard` so that **every non-test
compile in every plugin-using repo fails on a no-arg fold**. Consequences for this
report:

- My scope necessarily widened from the ~14 recon-named sites to **every** no-arg fold
  in my five repos (44 total) — the gate makes anything less unbuildable.
- The gate passing on each repo's compile is itself independent proof the repo is clean.
- Suppression is a `// locale-fold: deliberate` comment on the same line. **I marked
  nothing deliberate**; none of these sites wants locale-sensitivity.
- It also means `zenit` was transiently unbuildable while the other agent swept it, so
  all my builds/tests ran `--skip-deps` against the published chain. That is sound here:
  every change is confined to my own repos' server sources, and every assertion is on a
  pure function inside them. The compiles genuinely re-ran (see the pre-fix failures
  below, produced by the same command that later produced the passes).

---

## Verbatim PRE-FIX failures

### 1. Standalone harness, real `-Duser.language=tr -Duser.country=TR`

`DnsNames.java` and `RedirectUriMatcher.java` copied unmodified into a temp tree with
stub checkerframework annotations, compiled and run twice.

`java -cp classes Proof` (default `en`) — 0 failures.
`java -Duser.language=tr -Duser.country=TR -cp classes Proof`:

```
default locale = tr_TR

[DNS] DnsNames -- RFC 4343 mandates ASCII-only case folding
  FAIL  normalizeOrigin("WIKI.EXAMPLE.COM")
          expected: wiki.example.com
          actual:   null
  FAIL  normalizeOwner("API")
          expected: api
          actual:   null
  FAIL  relative("wiki.example.com", "API.wiki.example.com")
          expected: api
          actual:   apı
  FAIL  zoneContains("wiki.example.com", "API.WIKI.EXAMPLE.COM")
          expected: true
          actual:   false

[OIDC] RedirectUriMatcher -- wildcard host matching
  FAIL  registered *.wiki.example.com vs https://APP.WIKI.EXAMPLE.COM/cb
          expected: true
          actual:   false
  FAIL  registered *.WIKI.example.com vs https://app.wiki.example.com/cb
          expected: true
          actual:   false

[OIDC] can the fold make a match SUCCEED that should fail?
  'I'.toLowerCase() = ı (U+131)
  'i'.toLowerCase() = i
  'İ'.toLowerCase() = "i" len=1
  URI("https://app.wİki.example.com/cb").getHost() = null
     -> matches("https://*.wiki.example.com/cb", ...) = false
  URI("https://app.w%C4%B0ki.example.com/cb").getHost() = null
     -> matches("https://*.wiki.example.com/cb", ...) = false
  matches(*.wiki.example.com, https://app.wIki.example.com/cb) = false

failures = 6
exit=1
```

Two DNS behaviours worth calling out. `normalizeOrigin`/`normalizeOwner` return
**null** — the folded label no longer matches the `[a-z0-9-]+` label regex, so a
perfectly valid zone origin is rejected as malformed. `relative` is worse: it returns
`"apı"`, a *silently wrong owner label*, so an authoritative query for
`API.wiki.example.com` is looked up under an owner that does not exist → NXDOMAIN for a
record that is present.

Post-fix, the same harness against the fixed sources: `failures = 0` under both `tr_TR`
and `en`.

### 2. In-repo, hohenheim (`zenit-dev test --browser --skip-deps --class DnsRecordCodecTest,TlsHostnameFoldingTest`)

Measured by temporarily `git checkout`-ing `AcmeService`, `CertificateStore` and
`DnsNames` back to HEAD and tagging each fold line
`// locale-fold: deliberate (TEMPORARY pre-fix measurement)` so the new gate would let
the pre-fix code compile at all. (The marker changes nothing about the fold; it only
silences the scanner. All three files were restored from saved copies immediately
after, and `grep -rn "TEMPORARY pre-fix" src/` confirms none survived.)

```
  TlsHostnameFoldingTest
    ✗ certificateHostnamesFoldTheSameWayInEveryLocale      84ms
  DnsRecordCodecTest
    ✓ ttlBoundsAreChecked                                  79ms
    ✓ caaValuesParseFlagsTagAndValue                       48ms
    ✓ longTxtValuesChunkWithoutBreakingCharacters          3ms
    ✗ nameFoldingIsAsciiOnlyInEveryLocale                  32ms
    ✓ structuralRulesAreEnforced                           1ms
    ✓ addressValuesMustMatchTheRecordFamily                2ms
    ✓ normalizationCanonicalizesOwnersAndOrigins           4ms
  ✗ 2 of 8 browser tests failed  (only 2 of 125 test classes ran)
    TlsHostnameFoldingTest.certificateHostnamesFoldTheSameWayInEveryLocale()
      [mixed-case hostname accepted for issuance]
      org.opentest4j.AssertionFailedError: [mixed-case hostname accepted for issuance]
      Expecting value to be true but was false
      at app//be.elevenways.hohenheim.server.tls.TlsHostnameFoldingTest.certificateHostnamesFoldTheSameWayInEveryLocale(TlsHostnameFoldingTest.java:42)
    DnsRecordCodecTest.nameFoldingIsAsciiOnlyInEveryLocale()
      [uppercase origin canonicalizes]
      org.opentest4j.AssertionFailedError: [uppercase origin canonicalizes]
      expected: "wiki.example.com"
      but was: null
      at app//be.elevenways.hohenheim.test.DnsRecordCodecTest.nameFoldingIsAsciiOnlyInEveryLocale(DnsRecordCodecTest.java:63)
    TlsHostnameFoldingTest: 0 passed, 1 failed · DnsRecordCodecTest: 6 passed, 1 failed
  ✗ RESULT: FAILED — 2 of 8 browser failed
```

Note the pre-existing `normalizationCanonicalizesOwnersAndOrigins` **passed** through
all of this. It asserts on `"Example.COM"`, which carries no `I`. The recon's claim that
the existing case-insensitivity tests prove nothing under `tr` is confirmed empirically.

### 3. Post-fix, same command

```
  ✓ certificateHostnamesFoldTheSameWayInEveryLocale      95ms
  ✓ nameFoldingIsAsciiOnlyInEveryLocale                  8ms
  ✓ 8 browser tests passed
    DnsRecordCodecTest: 7 passed · TlsHostnameFoldingTest: 1 passed
  ✓ RESULT: PASSED — 8 browser passed
```

---

## The redirect-URI severity determination (asked for explicitly)

**Fail-closed only. A broken login, never an open-redirect primitive.** Determined
empirically, not by reasoning alone — the harness above probes the exploit shapes.

Code path. `matches()` (`RedirectUriMatcher.java:27`) returns
`registered.equals(actual)` byte-for-byte when the registration has no `*` (line 28), so
**an exact registration never folds anything**. Only a wildcard registration reaches the
host comparison: `hostOf(registered)` folds the pattern host (line 106) and
`hostMatches()` folds `target.getHost()` (line 133). The scheme comparison at line 53 is
`equalsIgnoreCase`, which is locale-independent, so scheme is not in play at all.

Why it cannot widen a match:

1. **On ASCII, the Turkish fold is strictly *more* discriminating**, not less:
   `'I' → 'ı'` (U+0131) while `'i' → 'i'`. Under `Locale.ROOT` both collapse to `i`.
   A more discriminating fold can only ever produce *fewer* matches. No two distinct
   ASCII hosts can collide under `tr` that do not already collide under `ROOT`.
2. **The one collapsing fold needs a non-ASCII host.** `'İ'` (U+0130) folds to `"i"`
   (1 char) under `tr` versus `"i̇"` (2 chars) under `ROOT`, so
   `app.wİki.example.com` would fold *into* a registered `*.wiki.example.com` suffix.
   It never gets there: `java.net.URI.getHost()` returns **null** for that host and for
   its percent-encoded spelling `app.w%C4%B0ki.example.com` (both printed as `null` in
   the harness output above), and `matches()` refuses at the
   `target.getHost() == null` check on line 68 — before any folding.

So the live failure is the reverse: a legitimate callback for
`https://APP.WIKI.EXAMPLE.COM/cb` against a registered `https://*.wiki.example.com/cb`
was **denied** under `tr` (proof line 5 above). Fix applied, AIDEV-NOTE records the
determination so a future reader does not re-derive it.

## The ACME / CertificateStore severity determination (asked for explicitly)

The question posed was whether each site means "a cert lookup misses and issuance
loops" or "a stored cert is keyed under a name that never matches again". It is not one
answer for all nine — it depends on whether **both** sides of the comparison carry mixed
case, because a fold is a no-op on an already-lowercase string.

| Site | Both sides folded? | What actually happens under `tr` |
|---|---|---|
| `AcmeService:400` `isValidHostname` | single fold of an operator-entered hostname | **Issuance refused outright.** The folded label fails the `[a-z0-9]` `HOSTNAME_LABEL` pattern, so a valid `WIKI.example.test` is reported invalid. Loudest of the nine; this is the assertion that failed first in the in-repo run. |
| `AcmeService:133` + `:700` | set built from order hostnames (`:700`), query is the Host header off the wire (`:133`) | **Challenge 404s → issuance loops.** A mixed-case hostname on *either* side stops matching, the validation request gets no authorization, the order fails and retries. |
| `AcmeService:832/836/889` `normalizeAccountEmail` / account-key row match | both sides folded within one JVM | Account *identity*. Consistent within a fixed locale; a locale flip re-keys the account and mints a second ACME registration for the same address. |
| `CertificateStore:115` `resolveFromMap` | single fold — the ClientHello SNI name; SAN keys are already lowercase in practice | **Certificate lookup misses.** A client sending mixed-case SNI gets the wrong certificate or none. Live serving break, not merely a locale-flip risk. |
| `CertificateStore:187` `buildPreferredAliases`, `:213` `addCertToKeyStore` | map-build side, from DB `site_domains.hostname` / cert SANs | **Keyed under a name that never matches again.** A mixed-case stored hostname becomes a key no lowercase SNI name can reach. |

Both outcomes the prompt asked about are real, at different sites. All nine now spell
`Locale.ROOT`; an AIDEV-NOTE on `CertificateStore.resolveFromMap` and one on
`AcmeService.getChallengeResponse` record why.

## The seeded-identity migration question (asked for explicitly)

**No migration is needed and no existing install changes. Verified, not assumed.**

`SpamWordSeeder.idFor` feeds `UUID.nameUUIDFromBytes("spamservice.word:" + fold(word))`,
i.e. the fold determines the stored row's primary key. Two independent reasons the fix
cannot move any id:

1. **The catalog input is already lowercase ASCII.** I parsed the bundled
   `src/server/resources/spam_words.json`: 587 entries, **0 non-lowercase words, 0
   non-ASCII words** (236 contain a lowercase `i`, which folds to itself in every
   locale including `tr` — only *uppercase* `I` and `İ` diverge). The fold is therefore
   a literal no-op on every catalog input in every locale, so pre-fix and post-fix ids
   are byte-identical.
2. **The seeder is ledgered.** The whole import is inside
   `ctx.once("spamservice.core-spam-words", ...)`, so it runs at most once ever per
   install; even a hypothetical id change could not re-mint rows on an existing install.

The fix still matters, because `idFor` is public API and the existing test
(`idFor("Viagra") == idFor("viagra")`) uses a word with no `I` and so proved nothing.
Added `wordIdsDoNotDependOnTheDefaultLocale`, which walks `tr-TR` and `lt-LT` and pins
`idFor("CIALIS") == idFor("cialis")` against a reference captured in the default locale.
An AIDEV-NOTE on `idFor` records the "identity, not cosmetics" reason.

## `ClaudeProxy:76` — the recon's "not exploitable today" claim, verified

**Confirmed not exploitable today; fixed anyway.** Three independent reasons:

1. `HOP_BY_HOP` is a set of ASCII-lowercase *source constants*. The fold is applied only
   to the incoming name, so the set itself never changes.
2. Several members do contain a lowercase `i` (`connection`, `trailer`,
   `transfer-encoding`, `proxy-authenticate`, `proxy-authorization`, `accept-encoding`)
   — but lowercase `i` folds to itself under `tr`. Only an **uppercase `I`** in the
   received name escapes, i.e. the upstream must spell `TRANSFER-ENCODING` /
   `CONNECTION`, not the canonical `Transfer-Encoding`.
3. `HttpClient.newHttpClient()` negotiates HTTP/2 by default, and RFC 9113 §8.2.1
   requires lowercase field names.

The residual risk is real but narrow: `CLAUDE_PROXY_UPSTREAM` is an operator setting, so
an HTTP/1.1 upstream emitting an uppercase-I hop-by-hop name on a `tr` JVM would have it
relayed to the client. An allow/deny list must not depend on a JVM flag; AIDEV-NOTE
records exactly this.

## `McpApiKeys:63`

`Key.defaultHeader(name)` looks a pinned virtual header up in a map documented as
carrying "lower-cased names". The only production caller today passes the already-lowercase
constant `McpToolContext.INLINE_IMAGES_HEADER = "x-mcp-inline-images"`, so the fold is
inert *at that one call site* — but `defaultHeader` is public API on a public record and
any caller spelling `X-Mcp-Inline-Images` misses under `tr`. Fixed, and pinned by a new
journey in `McpHostPipelineTest` that asserts canonical-case and all-caps queries both
resolve, under `tr-TR`.

---

## What I swept and found clean

- **The whole DNS surface**, not just the recon's line list. Every file in
  `server/dns/` (22 files: zone store/files/snapshot, responder, AXFR, NOTIFY/secondary,
  TSIG, DNSSEC keys/signer/material, SOA probe, rate limiter, record codec, dynamic DNS,
  token seeder, internal TXT publisher, server, peer API, notifier), plus `server/tls/`
  (9 files) and `server/devtunnel/` (4 files), grepped for the *whole* family of
  locale-sensitive constructs — `toLowerCase`/`toUpperCase`, `String.format`,
  `Collator`, `CASE_INSENSITIVE_ORDER`, `compareToIgnoreCase`, `Locale.getDefault`,
  `new Locale`. After the fix the only surviving hit anywhere in those three trees is
  the word "toLowerCase" inside my own AIDEV-NOTE.
- **DNS name entry points route through one choke point.** Every caller of a DNS name
  normalizer goes through `DnsNames` (`DnsRecordEdits:46`, `HohenheimHandlers:463`,
  `DnsZoneFiles`, `DnsZoneStore`, `InternalDnsTxtPublisher`, `DnsRecordCodec`,
  `DnsZoneResource`, `DynamicDnsService`), so the fix lands at the choke point rather
  than being sprayed across callers.
- **Dynamic DNS hostname matching is clean**: `DynamicDnsService:186` uses
  `equalsIgnoreCase`, which is locale-independent (per-char `Character.toUpperCase`).
  Same for `SniKeyManager`. Left alone.
- **No `common`/`browser`/`client` fold exists in any of the five repos**, so
  `BlastString.lower/upper` was not needed anywhere in my half.

## Out of my scope, noted rather than fixed

- `zenit` core still had **15** unfixed no-arg folds at the time of writing (down from
  28 while I worked), including `SqlMigrationOperationVisitor:69,70,665`,
  `FirebirdDatasource:83,89`, `SqlDatasource:811`, `HttpConduit:428`,
  `DuplicateKeyException:152` (common → needs `BlastString`), `ZenitDirectives:78`
  (common → needs `BlastString`). The gate makes zenit's build fail until these land;
  that is the core-half agent's active work and I did not touch it.
- `protoblast` had ~30 in-flight edits by the same agent (`BlastString`, `Uri`,
  `ServerDominoElement`, `DominoEvaluator`, …). Untouched.

## Every site changed (44), with spelling and source set

All `src/server`, all `toLowerCase(Locale.ROOT)` / `toUpperCase(Locale.ROOT)`.

**hohenheim (29)** — `dns/DnsNames.java:22,42,75`; `dns/AxfrResponder.java:66`;
`dns/SecondaryZoneService.java:132`; `dns/DnsZoneStore.java:289,298`;
`dns/DnsZoneFiles.java:239,244`; `dns/DnsRecordCodec.java:138,170`;
`dns/DnsRateLimiter.java:53,67,73`; `dns/InternalDnsTxtPublisher.java:180`;
`tls/AcmeService.java:133,400,700,832,836,889`; `tls/CertificateStore.java:115,187,213`;
`devtunnel/DevTunnelServerHandler.java:333`; `cms/DnsPeerResource.java:91`;
`cms/DnsZoneResource.java:200`; `cms/SiteResource.java:500`;
`cms/DatabaseResource.java:128,131`; `cms/SiteDatabaseResource.java:160`;
`database/DatabaseEnvInjection.java:96,121`; `database/DatabaseService.java:142,199,220,322`;
`docker/DockerClient.java:749`.
Plus a test seam: `CertificateStore.resolveFromMap` went `private static` →
package-private, so the folding journey can drive it without a DB-backed snapshot.

**spamservice (12)** — `seed/SpamWordSeeder.java:56,78`;
`scoring/GibberishDetector.java:150,161,236`; `scoring/Languages.java:36`;
`iplookup/Ip2ProxyDatabase.java:75,88`; `api/ApiAuth.java:146`;
`api/ApiEndpoints.java:245`; `service/SettingsManagementService.java:82,103`.

**zenit-oidc (2)** — `client/RedirectUriMatcher.java:106,133`.

**zenit-ai (2)** — `mcp/host/McpApiKeys.java:63`; `mcp/host/McpToolSchema.java:173`.

**thoth (1)** — `proxy/ClaudeProxy.java:76`.

`java.util.Locale` imports added to 25 files; `AcmeService` and `CertificateStore`
already had `java.util.*`.

## Tests added or updated

| Repo | Test | What it pins |
|---|---|---|
| hohenheim | `DnsRecordCodecTest.nameFoldingIsAsciiOnlyInEveryLocale` (new, 4-step journey) | origin/owner canonicalization, `relative()`, `zoneContains()` under a forced `tr-TR` default locale |
| hohenheim | `server/tls/TlsHostnameFoldingTest` (new file, 3-step journey) | `isValidHostname`, the HTTP-01 challenge gate via the existing `offerHttpChallenge` seam, and SNI alias resolution incl. the wildcard fallback |
| zenit-oidc | `RedirectUriMatcherTest.hostFoldingIsAsciiOnlyInEveryLocale` (new, 4-step) | both fold directions match, a neighbouring host still does not, and the non-ASCII / percent-encoded exploit shapes are refused at `getHost()` |
| zenit-ai | `McpHostPipelineTest.pinnedHeaderLookupIsLocaleIndependent` (new, 3-step) | canonical-case and all-caps virtual-header queries both resolve under `tr-TR` |
| spamservice | `SpamWordSeederTest.wordIdsDoNotDependOnTheDefaultLocale` (new, 2-step) | the seed UUID is identical across `tr-TR`, `lt-LT` and the default locale |

Each forces the locale with `Locale.setDefault` and restores it in a `finally` /
`@AfterEach`. hohenheim configures no JUnit parallel execution, so this is safe.

## Verification performed

| Repo | Command | Result |
|---|---|---|
| zenit-oidc | `zenit-dev test --unit --skip-deps --class RedirectUriMatcherTest --no-fail-fast` | 21 passed (33s) |
| zenit-ai | `zenit-dev test --unit --skip-deps --class McpHostPipelineTest --no-fail-fast` | 15 passed (29s), was 14 before |
| spamservice | `zenit-dev test --unit --skip-deps --class SpamWordSeederTest --no-fail-fast` | 3 passed (37s) |
| thoth | `zenit-dev build --skip-deps` | ok (243s) — no test exists for `ClaudeProxy`; the compile is the gate check |
| hohenheim | `zenit-dev test --browser --skip-deps --class DnsRecordCodecTest,TlsHostnameFoldingTest --no-fail-fast` | pre-fix 2 of 8 FAILED, post-fix 8 passed (120s) |
| hohenheim | 18 classes: `AcmeAccountEmailTest,AcmeFailureTest,CertExpiryAlertTest,DnsAdminTest,DnsCentralEditTest,DnsFederationTest,DnsRateLimiterTest,DnsSecTest,DnsServerTest,DynamicDnsTest,DyndnsTokenIndexTest,TlsCertificateTest,DatabaseEnvInjectionTest,DatabaseServiceTest,DatabaseAdminTest,SiteCrudTest,DevTunnelTest,DockerClientTest` | **115 passed (192s)** |

The regression run covers every subsystem I touched beyond the named sites: DNS
server/federation/DNSSEC/rate-limit/dyndns, TLS issuance/serving/expiry, database
engine-name round-trips and env-var prefixes, site slugs, dev tunnel, Docker.

**Stale-artifact check** (the 07-31 incident): every result above came from a run that
actually recompiled. The pre-fix hohenheim run and the post-fix run used the identical
command and produced opposite outcomes, which is only possible if both compiled. The
new `checkLocaleFolds` gate runs ahead of every non-test `compileJava`, so a passing
build is also a positive assertion that the repo holds no unmarked no-arg fold.

## Commits

| Repo | Hash | Subject |
|---|---|---|
| zenit-oidc | `e9b2ce5db4b86274aedf08233afd85ae6262db44` | 🔒 Fold redirect-URI hosts with Locale.ROOT |
| zenit-ai | `7b70e74d78b7b0c3b03e81bcbe1bc013b122932e` | 🔒 Fold MCP header and boolean tokens with Locale.ROOT |
| spamservice | `1603dc0f0d2954680d735df8a1db42e765c7bd88` | 🔒 Fold every server-side case comparison with Locale.ROOT |
| thoth | `1770c2ba39751545d9b59659e10ab7fe08bfae60` | 🔒 Fold proxied header names with Locale.ROOT |
| hohenheim | `27447e0814fbfd562b80b294ea4bef6ea19714c9` | 🔒 Fold DNS and certificate hostnames with Locale.ROOT |

All five verified with `git log -1 --format='%s%n---%n%b'`: subject alone on line 1,
under 72 chars, real Unicode gitmoji first, one body line. Staged by explicit path in
every repo; `git status` before and after confirms nothing belonging to another agent
was swept in. Left deliberately unstaged: `thoth/public/thoth-client.js{,.map}` (build
output regenerated by my `zenit-dev build`, not my change).

## Known limitations / follow-ups

- **`AcmeService.normalizeAccountEmail` (`:832/836/889`) has no test of its own.** It
  reads `HohenheimSettings.VALUES`, which needs a booted runtime, so it is out of reach
  of the runtime-free folding journey. `AcmeAccountEmailTest` covers it functionally
  (3 tests, green in the 115-test run) but does not force a locale. The fix is the same
  one-line change as everywhere else and rides the same gate; if a locale assertion is
  wanted there it belongs in `AcmeAccountEmailTest`, which already pays the boot cost.
- **`zenit` core is still red on the gate** (15 sites) at the time of writing. Until the
  core-half agent finishes, `zenit-dev build` without `--skip-deps` fails for the whole
  chain. Nothing in my half depends on that landing; my repos are clean and green.
- Everything else the recon listed under Tier 2/3 (`hawkeye`, `protoblast`, `textum`,
  `zenit-auth`, `zenit-comms`, `zenit-microcopy`, `plumage`, `proteus`, `orcono`,
  `quirkyquarters`, `janeway`, `emberglyph`) is outside my half.
