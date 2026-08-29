# starfleet.life: the live public deployment

The public reference deployment of the PROXY + DNS roles (as opposed to
`deploy-native.md`, which documents the instances/compute shape on daystrom).
One Debian 11 KVM VPS, 1971 MB RAM, public IPv4 `104.223.42.142`, running the
fat server jar under systemd as the `hohenheim` user.

Standing since 2026-07-21. This file records the operational facts a future
session needs and the one incident that has already bitten.

## Layout

    /opt/hohenheim/hohenheim-server.jar     the fat server jar
    /opt/hohenheim/settings/hohenheim.dry   role + security declaration (untracked)
    /opt/hohenheim/settings/local.dry       environment + admin listener
    /opt/hohenheim/settings/field-encryption.keys   0600, NOT recreatable
    /opt/hohenheim/hohenheim.db             sqlite control plane
    /etc/systemd/system/hohenheim.service   enabled, Restart=always
    /etc/sysctl.d/99-hohenheim.conf         fs.file-max

The keyring and the database are a PAIR: a fresh keyring cannot read the
existing encrypted columns. Never replace one without the other.

## Roles actually enabled here

Proxy (80/443) and authoritative DNS (53 udp+tcp). Since the 2026-08-14 clean
rebuild (fresh database + keyring; the old install is backed up at
`/root/starfleet-preflight-20260815-110419`) the site set is two: the admin
panel on `admin.starfleet.life` and the static apex on `starfleet.life` +
`www.`. The NetBird stack, the node test site, the managed spamservice and the
orphaned `/opt/spamservice` were all deliberately removed in that rebuild;
sections below describing them are history, not current state.

## DNS: authoritative, and reached by the public (corrected 2026-08-11)

Hohenheim holds `starfleet.life` as a primary zone and answers it correctly and
authoritatively on the public IP over both UDP and TCP, refusing out-of-zone
names. The registrar delegates the domain to `nssl.mooo.com` / `nssl2.mooo.com`
(FreeDNS), and the zone's own NS RRset mirrors those names.

This file used to claim "public resolvers therefore never reach this server".
That is WRONG and was corrected on 2026-08-11: both FreeDNS names now resolve
to `104.223.42.142`, this host. Recursives that follow the delegation therefore
land HERE, and `starfleet.life` resolves through 1.1.1.1, 8.8.8.8 and 9.9.9.9
off this server's own answers. Treat the zone as LIVE and publicly served: an
outage of this process is an outage of the zone for the whole internet.

The two-nameserver threshold in `authoritative-dns.md` is still not met -- both
delegated names point at one machine. That is the standing gap, not the
delegation itself.

`ns1`/`ns2.starfleet.life` A records already point at the host, so the glue is
ready. Cutting over is a registrar action plus rewriting the apex NS RRset --
it is deliberately NOT done, because a single nameserver on one host does not
meet the two-nameserver production threshold described in
`authoritative-dns.md`. Do the secondary first.

## Memory

The box is oversubscribed by design and swaps: three long-lived heaps
(hohenheim, the managed spamservice at `-Xmx256m`, and NetBird's Go server)
share 1971 MB. Hohenheim runs with

    JAVA_TOOL_OPTIONS=-Xms128m -Xmx768m -XX:MaxMetaspaceSize=256m -XX:+UseSerialGC

`-Xmx768m` is a ceiling the process has never approached: measured RSS was
333 MB fresh, 355 MB fresh again on 2026-08-11, and 553 MB after a 13-day
uptime under real internet traffic, with `VmSwap: 0` for the JVM itself.
SerialGC is the right collector at this size. Do not raise the ceiling; if this
box gains another workload, lower the managed-service heaps before touching
this one.

The managed spamservice child is the OTHER big heap and it is not small: 361 MB
RSS despite `-Xmx256m`. Two ~355 MB JVMs plus NetBird's Go server is what keeps
`available` near 450-600 MB. Budget for both before adding anything.

## Incident 2026-08-04: HTTPS silently down for six days

A system-wide file-descriptor exhaustion (`fs.file-max` had been pinned to
100000, below the kernel's own default for this RAM) produced `ENFILE` while
the public listeners were accepting. The HTTPS listener died:

    ProxyServer:328 - PROXY HTTPS LISTENER FAILED: Too many open files in system

and never came back. HTTP on 80 kept serving normally, so nothing looked
broken from the outside except that every `https://` name stopped answering.

Fixed on the host by raising `fs.file-max` to 200000 (the kernel default scale
for 2 GB) and restarting the unit; 443 rebound immediately with both
certificates.

The PRODUCT half WAS the real gap: `handleHttpsListenerFailure` recorded
`httpsState = FAILED` and logged, but the only code that ever retried lived
inside the certificate-reload reconcile. With no periodic retry, a transient
accept-time failure took HTTPS down until something happened to reload
certificates -- which, with certs renewing ~30 days before expiry, can be weeks.

Fixed and deployed to this host on 2026-08-11: the accept loop now survives
transient errors instead of dying, listener restarts are bounded and observable,
and `SuperviseProxyListeners` runs MINUTELY (`* * * * *`, BOOT_AND_CRON) to heal
a listener that died anyway. Verify it is live with

    sqlite3 file:/opt/hohenheim/hohenheim.db?mode=ro \
      "select type, frequency, enabled from system_task where type like '%Supervise%';"

## Incident 2026-08-15: the panel ran a five-day-old front-end

The 2026-08-14 rebuild swapped the jar but left `/opt/hohenheim/public/`
holding the PREVIOUS deployment's `cms.js`, `cms.js.map` and `hohenheim.css`
(Aug 10). The client bundle only ever lived in the project `public/` dir (the
dev lane), never in the jar, so a jar-only deploy could not update it -- and an
authored file in the served public dir shadows the jar's classpath asset
forever. Result: an Aug 15 server rendering markup for an Aug 10 client
runtime -- hydration failures, dead components, emptied SSR slots.

Fixed structurally on 2026-08-15: the fat jar now CONTAINS the front-end
(`public/cms.js`, `public/cms.js.map`, `public/hohenheim.css` -- packed by the
protoblast assembly lane, pinned by a serverJar integrity check that fails the
build if any is missing). Consequence for this host: `/opt/hohenheim/public/`
must hold ONLY genuinely authored content (the apex site under `public/apex`).
Never place `cms.js` or `hohenheim.css` there -- they would shadow every future
jar's copy.

## Off-host control-plane backups (since 2026-08-15)

`database.control_plane_backup_target` is set to the backup target `phoenix`:
an SSH target on the enrolled host record `phoenix` (skerit@phoenix.develry.be,
pinned fingerprint SHA256:CI6pAvXx4nyZFTE/21aOQD4xRwn+ePXa82rlWzL0rps, verified
out of band), directory `/home/skerit/starfleet-backups`. The nightly
BackupControlPlane task (02:30) uploads the recovery archive (database +
field-encryption keyring) there; the client key is IP-restricted to this host
in phoenix's authorized_keys. The archive carries the MASTER KEYS IN THE CLEAR
-- treat that directory like the keyring file. Manual run:
`sudo -u hohenheim java -jar hohenheim-server.jar --backup-control-plane`.

## What is deployed? (`zenit-dev deployed starfleet`, since 2026-08-24)

Every jar built since 2026-08-24 carries `META-INF/blast/build-info.tsv`: one
line per bundled module naming the git repo, commit, branch, dirty flag and
commit date (the protoblast Gradle plugin stamps every module jar; the fat-jar
lane line-merges them, so the deployable names protoblast/hawkeye/zenit/... beside
hohenheim's own commit). Three readers of that one file:

- `zenit-dev deployed starfleet` (MCP `zd_deployed`): reads the stamp out of the
  jar over ssh with `unzip -p` -- the application is NEVER started on the host --
  and diffs every repo against the local checkout. Per-repo verdicts: `current`,
  `local-ahead` (a deploy is pending), `deployed-ahead`, `diverged`,
  `unknown-commit` (fetch first), `unknown-repo`, `undiffable` (built from a
  DIRTY worktree: the sha does not describe it), `inconsistent` (one repo, several
  builds). It also reads the service's main-process start time, so a jar swapped
  on disk but not restarted shows as RESTART PENDING. `unstamped` means the jar
  predates the stamps (true of everything deployed before 2026-08-24) and nothing
  can be diffed -- never read it as current. The host comes from
  `~/.config/zenit-dev/config.json`:
  `"deployments": { "starfleet": { "ssh": "root@starfleet.life", "jar":
  "/opt/hohenheim/hohenheim-server.jar", "service": "hohenheim" } }`.
- On the host: `java -jar hohenheim-server.jar --build-info` (an offline command,
  no database, no HTTP; run it from a scratch dir as in step 4 below, never as
  root beside the live service).
- In the panel: System > Build info (`/admin/build-info`), authenticated like every
  other panel page. There is deliberately no public version endpoint.

Run `zenit-dev deployed starfleet` BEFORE a deploy (is the fix already live? is the
host running something local HEAD does not have?) and AFTER the restart (does the
host now report `current` for every repo?). A `dirty` stamp on a deployed build is
a process failure: deploy from a committed worktree, always.

## Deploy procedure (as exercised 2026-08-11)

0. `zenit-dev deployed starfleet` -- know what runs before touching it.
1. Build in an ISOLATED worktree (`git worktree add --detach <path> HEAD`),
   never the main one, via `zenit-dev build`. The jar lands in
   `build/libs/hohenheim-0.1.0-SNAPSHOT-server.jar` and contains the whole
   app, front-end included (see the 2026-08-15 incident above).
2. Back up FIRST, into `/root/hohenheim-preflight-<stamp>/`: the database via
   `sqlite3 .backup` plus `PRAGMA integrity_check`, the whole settings
   directory, and the keyring with a sha256 comparison against the original.
   Copy the running jar aside as the rollback: that is the whole rollback, and
   it recovers in ~90 seconds.
3. Compare the repo's migration versions against `zenit_migrations` BEFORE
   deciding anything. A deploy that adds no migrations cannot damage the
   database, and that is worth knowing up front.
4. REHEARSE against a byte copy, never the live file. Two lanes, both cheap:
   `java -jar <newjar> --run-migrations` from a scratch directory whose
   `settings/hohenheim.dry` repoints `database.path`, `storage.data_path` and
   `database.backup_path` at the copy; then a full inert boot of the same
   directory with every `roles.*` false, `dns.enabled` false,
   `ssl.letsencrypt_enabled` false, nftables/bans off and the ports moved
   (13999/18080/18443). The inert boot binds nothing the live process owns and
   proves the jar boots and routes resolve. Kill it before deploying -- RAM.
5. Swap the jar (`install -o hohenheim -g hohenheim -m 644`, then `mv` into
   place) and `systemctl restart hohenheim`.
6. Verify from OUTSIDE, then restart AGAIN and verify again.
7. `zenit-dev deployed starfleet` must now answer `current` for every repo with
   no RESTART PENDING warning; anything else is the deploy not being finished.

## Verification commands

Authoritative DNS, straight at the host (no recursion, so the `aa` flag is
meaningful):

    dig +norecurse @104.223.42.142 starfleet.life SOA
    dig +norecurse +tcp @104.223.42.142 starfleet.life NS
    dig +norecurse @104.223.42.142 google.com A        # must be REFUSED

This workstation has NO `dig`, `drill`, `host` or `nslookup`. Either install
one or query with a raw-socket script; the `aa` flag and the REFUSED rcode are
the load-bearing parts, and `getent`/`curl` show neither.

TLS, from outside (`--noproxy '*'`, or the workstation proxy answers instead):

    curl --noproxy '*' -sv https://admin.starfleet.life/   # issuer: Let's Encrypt
    openssl s_client -connect vpn.starfleet.life:443 -servername vpn.starfleet.life

The public names since the 2026-08-14 rebuild are `starfleet.life`, `www.`
and `admin.`; all three answer `ssl_verify_result=0` and all three 301 from
HTTP (`node.` and `vpn.` were dropped with their sites).

## Things that LOOK broken and are not

`127.0.0.1:8093` (the managed spamservice) is loopback-only BY DESIGN. An
external timeout there is not a defect -- check `ss -lntp` on the host before
concluding anything. It has cost a session real time already.

`/admin/instances` listing empty, and `/admin/instances/1/page/overview`
returning 404, is also correct here: this host's only instance is
stack-generated, and `InstanceResource.accessFunction()` deliberately hides
instances with a non-null `generated_by` (they are managed through the owning
stack's surface). The record-scoped pages inherit that gate, hence the 404.

The `settings.unknown_key` warnings for `hohenheim.security.spamservice_url`
and `spamservice_key` still fire on every boot: the keys sit in
`settings/hohenheim.dry` but no longer exist as declared settings (the managed
spamservice supplies its own URL and key). Harmless, but they are dead keys.

`/opt/spamservice/` is an orphaned STANDALONE install from 2026-07-23. The
service that actually runs lives under
`/opt/hohenheim/data/managed-services/spamservice/`. Nothing reads
`/opt/spamservice/` any more; it is ~660 MB of stale jars.

## Deploy 2026-08-28: the QA remediation batch, built from a CLEAN parallel workspace

Shipped hohenheim `b6b2077f` at 19:10Z. This is the first deploy built from a
SECONDARY WORKSPACE rather than a hohenheim-only worktree, and that is the
lesson worth keeping: three framework repos (plumage, hawkeye, zenit) carried
another session's uncommitted work, so the ordinary lane -- a hohenheim
worktree resolving its chain from the main checkouts -- would have baked that
in-progress code into a production jar and stamped those repos `dirty`. The
`.zenit-dev.json` workspace-config mechanism exists for exactly this: a
directory holding `git worktree add --detach` checkouts of the WHOLE chain at
their committed HEADs plus

    {"workspaceRoot": ".", "mavenRepoLocal": "./.m2",
     "externalRepos": {"hohenheim": "./hohenheim"}}

is its own workspace, with its own maven-local and its own journal, and
`zenit-dev build` there resolves nothing from the live checkouts. Cost: one
cold chain build (~11 min warm, ~25 cold). It is now the lane to use whenever
`zd_deployed` reports upstream repos dirty.

Two things it exposed that the ordinary lane hides:

- `gradle.properties` is TRACKED and zenit-dev rewrites its managed
  `org.gradle.daemon.idletimeout` on every build, so eight repos were
  permanently dirty and every stamp built from them said `dirty=true`
  (undiffable by construction). Eight of them still carried the reverted
  20-minute experiment's `1200000` while the rest carried the shipped
  `120000`; aligning them (one commit per repo) is what made a 13-of-13
  `dirty=false` jar possible at all. Do not "fix" this with a workspace
  `daemonIdleTimeoutMs` override -- that only moves which half is dirty.
- spamservice's own `:checkBundleSize` was RED at its committed sha, which
  blocks the whole chain because hohenheim depends on it. Both it and
  hohenheim needed a bundle re-baseline: cms.js grew +282679 bytes raw
  (+64694 gzip) and spamservice's +284546, the same framework-wide growth
  (typed number/money inputs, the form-section state carrier, the keyboard
  activation lane, focus restoration). `updateBundleBudget` writes
  `ceil(measured * 1.05)`; the same numbers can be written by hand into
  `teavm-bundle.budget` when running that task is inconvenient.

Migration diff: EMPTY across every upgraded repo (only zenit's
`TableBuilder.money` refactor, which emits identical columns), so the lane
could have skipped step 4 -- but the live `zenit_migrations` table has no
`version_stream` column, so the rehearsal ran anyway to prove the new jar does
not rewrite it. It does not: `--run-migrations` against a byte copy reported
"Migrations complete 0 applied" with integrity enforcement at its default
`fail`, i.e. all 41 recorded checksums still verify. The inert boot of the same
scratch directory (every role false, DNS/LE/nftables off, ports 13999/18080/
18443) answered `/api/health` 200, `/login` 200 and `/admin` 302 in 26s.

Preflight backup `/root/hohenheim-preflight-20260828-190811/`: database via
`sqlite3 .backup` (integrity ok, 41 migrations / 3 sites / 3 domains / 9 DNS
records / 3 certificates, the extra rows being soft-deleted QA leftovers), an
at-swap second copy, `settings/` and the keyring sha256-compared, and the
running jar copied aside as `hohenheim-server.jar.rollback`. The new jar was
uploaded with `upload_file`, which verifies the remote SHA-256 and runs a
pre-move check -- here `unzip -p {} META-INF/blast/build-info.tsv | grep -c
false` had to equal 13, so a jar carrying a dirty stamp could not have been
moved into place at all.

Both restarts came up clean: 0 journal errors, listeners 53/80/443/3000, `aa`
SOA on the public IP, admin + apex 200 over HTTPS, HTTP 301, RSS well inside
the 768 MB cap, and `zenit-dev deployed starfleet` answering `current` for all
13 repos with no RESTART PENDING. The one blip -- a single `000` on the first
`/login` probe -- was the probe racing the HTTPS listener, which the log times
at 19:11:22, three seconds later.

## Deploy 2026-08-27: two rounds of live-check fixes (plain jar-swap lane, twice)

Shipped `419b9274` at 01:49Z and `3472cafa` at 06:05Z. Both were the plain
jar-swap lane: the migration diff was EMPTY each time, so step 4 (rehearse
against a byte copy) was skipped by the lane's own rule -- a deploy that adds
no migrations cannot damage the database, and the inert boot exists to prove
the migration lane, not the jar. Everything else ran unchanged: preflight
backup (`/root/hohenheim-preflight-20260827-014800/` and
`/root/hohenheim-preflight-20260827-060430/`: database via `sqlite3 .backup`
plus integrity check, `settings/`, keyring sha256-compared, the running jar
copied aside as `hohenheim-server.jar.rollback`), install + swap, restart,
verify from outside, restart AGAIN and verify again. Both deploys came up
clean on both restarts: 0 exceptions, listeners 53 udp+tcp / 80 / 443 / 3000,
`aa` SOA + NS + REFUSED on the public IP, all three names `ssl_verify_result=0`
(200 / 200 / 302, HTTP 301), RSS 314-340 MB, `/opt/hohenheim/public/` holding
only `apex`, `/cms.js` served at the jar's byte count (4891544, then 4896463),
and `zenit-dev deployed starfleet` answering `current` for all 13 repos.

The first deploy carried the 08-26 QA batch's second half (microcopy audit:
plumage's parallel `PlumageText` face deleted, relative-time keys, enum labels
across every repo, `<html lang>` naming a served locale, HttpRefusal copy). A
live browser check of THAT deploy found five defects the suites could not see:
a row menu clipped 15px behind the scrollbar (hawkeye measured the viewport
with `innerWidth`), an unavailable-action reason printed over its own label,
a disabled dependent picker flashing the raw key `relation_unresolved` on the
first post-hydration paint, an instance create form reporting one refusal per
submit and dropping the typed URL, and a delete from a zone's Records tab
landing on the global record list. Fixing the picker flash exposed a latent
hawkeye compiler defect (a tag member body's `let` landed in the SHARED render
context and read the enclosing `{% each %}` loop variable). All fixed across
hawkeye, plumage, zenit-cms and hohenheim, each with a browser test that is red
before the fix; the second deploy shipped them.

Lane notes:

- The 08-26 `gradle.properties` fix held: the jar's `META-INF/blast/
  build-info.tsv` read clean (every module at its committed sha, no
  `dirty=true`) on the FIRST build from the isolated worktree, both times.
- No stale rehearsal JVM held 13998/13999 (none was started; the 08-26 rule
  is now habit).
- The migration gate `git diff <deployed>..HEAD --stat -- '*igration*'`
  false-positives on `src/browserTest/.../test/migration/*Test.java`. Read the
  hit: only `src/common/java/.../migration/*.java` and
  `src/server/java/.../instance/InstanceMigrations.java` decide the lane. A
  test under the `migration` package is not a schema change.
- A green suite is not a live check. Three of the five defects were geometry
  or first-paint timing that only a real browser at a real viewport, with a
  real scrollbar (Playwright's default `--hide-scrollbars` hid the clamp defect
  outright) and a warm catalog, can show. Run the visual pass after every
  deploy that touches plumage/zenit-cms chrome.

## Deploy 2026-08-26: the QA-pass batch + protected paths (the 08-23 lane, second run)

Shipped `0cde277c` (QA-pass fixes across hawkeye/zenit/zenit-cms/plumage/
zenit-microcopy/zenit-auth plus the protected-paths feature). The feature edits
`InitialMigration` in place (new table `protected_paths`, new column
`access_lists.shared`), so this was the 08-23 lane again, unchanged in shape:
`/root/hh-rehearse-20260826/` (fresh `--run-migrations`, `import.py` over the
preflight copy, an inert boot, then a proxy+dns boot on 18080/18443/15353 with
the `aa` SOA, NS, admin A, out-of-zone REFUSED, both 301s and HTTPS 200 under
the real Let's Encrypt certificate -- the keyring pairing proven on a copy) and
`/root/hh-cutover-20260826/cutover.sh` (fresh database staged BEFORE the stop,
at-swap backup + integrity check, import, count gates against at-swap, swap,
start). Window 12:22:51Z to 12:23:21Z. Rollback lives in
`/root/hohenheim-preflight-20260826-115540/` (`hohenheim-server.jar.rollback`,
`hohenheim.db` pre-deploy, `at-swap.db`, `hohenheim.db.pre-cutover`, settings,
keyring). Verified after both restarts: 0 exceptions, listeners 53 udp+tcp / 80 /
443 / 3000, `aa` SOA + NS + REFUSED on the public IP, all three names
`ssl_verify_result=0` (200 / 200 / 200, HTTP 301), RSS 316 MB, `/opt/hohenheim/
public/` holding only `apex`, `/cms.js` served at the jar's 4856538 bytes, and
`zenit-dev deployed starfleet` answering `current` for all 13 repos.

Two things this deploy fixed in the LANE itself:

- A stale rehearsal JVM (`hh-inert-*`, user `hohenheim`, 31 hours old, 323 MB
  RSS) from the 08-25 deploy was still holding port 13998, so the first inert
  boot failed with `BindException`. Kill every rehearsal boot before leaving a
  deploy; check `ss -lntp | grep 13998` before starting one.
- The first jar built from a committed worktree still stamped every framework
  module with its PRE-commit sha and `dirty=true`: zenit-dev's publish freshness
  was content-only, so a commit that changed no bytes never re-ran the stamp
  task, and zenit-dev rewrote the tracked `gradle.properties` inside the deploy
  worktree with a directory-derived `ErrorFile` name, which made the worktree
  itself dirty. Both fixed in zenit `a73554a5` (provenance is now a second
  fingerprint facet; managed settings are keyed by the repo identity of the main
  checkout). Read the stamp out of the jar (`unzip -p <jar>
  META-INF/blast/build-info.tsv`) BEFORE uploading; a `true` in the dirty column
  means the runbook rule was violated, whatever `git status` says.

## Deploy 2026-08-23: the Phase 0 schema cutover (a repeatable lane)

hohenheim ships ONE migration edited in place, so a Phase 0-sized schema change
makes the live database refuse the new jar, and `--repair-migration-checksums`
correctly refuses real drift. The 2026-08-19 deploy hand-carried a delta because
the reshaped tables happened to be empty. That does not generalize. This one did:

    fresh schema  = the new jar's own `--run-migrations` into an empty database
    live content  = a column-INTERSECTION copy of every non-empty table

The copier lives at `/root/hh-rehearse-20260823/import.py` on the host (keep it;
it is the lane, not a one-off). It carries every table present in both schemas,
leaves behind columns the new schema dropped, lets added columns take their
declared default, and REFUSES loudly on an unmapped value rather than guessing.
Tables the new build regenerates are deliberately not carried: `zenit_migrations`
(the fresh ledger is the authority), `zenit_seeds` (so every once-seed re-runs
against the new shape), `system_task` + `system_task_history`, `zenit_leases`,
`reconcile_findings`, and the instance templates (re-seeded by game-templates).
The only carried table needing a value map was `sites`: `site_type` became
`upstream_kind`, `hohenheim:proxy` -> `hohenheim:address`.

Keep the KEYRING. A fresh keyring cannot read carried encrypted columns
(certificate private keys, host identity keys, incus client keys). The database
is new; the keyring is not; that pairing is the whole point.

Rehearsal that actually proves something (all of it on copies, service running):
  1. `--run-migrations` into the scratch dir, then import, then an INERT boot
     (every role false, ports moved) -- proves the jar boots on the new data.
  2. A SECOND boot with proxy+dns roles TRUE but ports moved (18080/18443,
     127.0.0.1:15353) and firewall/stacks/databases/instances FALSE -- the role
     set that must never touch the live Docker daemon or the live spamservice.
     Then query it: `dig +norecurse -p 15353 @127.0.0.1 starfleet.life SOA`
     (expect the `aa` flag), the NS RRset, an out-of-zone name (expect REFUSED),
     and `curl -H 'Host: admin.starfleet.life' http://127.0.0.1:18080/` (expect
     301). That is the difference between "the jar started" and "the zone and the
     routes survived the migration".
Downtime was ~1 minute because the fresh-migrated database was staged BEFORE the
service stopped; only the import and the two moves happen inside the window.
`cutover.sh` in `/root/hh-cutover-20260823/` restarts the OLD service untouched
on any failure before the swap.

Rollback: `/root/hohenheim-preflight-20260823-011900/` holds the old jar
(`hohenheim-server.jar.rollback`), the pre-deploy database, the at-swap database,
the settings directory and the keyring. `hohenheim.db.pre-phase0` in that same
directory is the live file as it stood at the swap.

Verified after both restarts: 0 exceptions, listeners on 53 udp+tcp / 80 / 443 /
3000, `aa` SOA + NS + admin A over TCP + out-of-zone REFUSED on the public IP,
all three public names `ssl_verify_result=0` from outside (302 / 200 / 200, HTTP
301), RSS 324 MB, `/opt/hohenheim/public/` holding only `apex`, and `/cms.js`
served at exactly the jar's byte count (4655418) -- the Aug 15 shadowing check.

## Deploy 2026-08-29: the second remediation wave

Shipped hohenheim `5a030dcb` at 23:57 CEST. Same clean-secondary-workspace lane as
2026-08-28, and for the same reason: zenit-comms carried another session's unfinished
inbox work, so the main checkouts could not build a release jar. Every OTHER chain repo
happened to be clean this time -- the lane was still the right call, because the jar must
carry only committed state and one dirty repo is enough to spoil it.

Preconditions checked before touching the host: no migration diff between the deployed
`b6b2077f` and HEAD (`git diff --stat b6b2077f..HEAD -- '*igration*'` empty), and every
chain repo's HEAD recorded. Build: 802s, `checkBundleSize` green (hohenheim's budget was
re-baselined on 2026-08-28 and this wave did not move it; zenit-auth's own TEST bundle
budget did move, for reasons recorded in that repo).

The jar was stamped 13/13 `clean` before it left the workstation, and `upload_file`
enforced that remotely as well -- `unzip -p {} META-INF/blast/build-info.tsv | sort -u |
grep -c false | grep -qx 13` as the verify_command, so a jar with any dirty stamp could not
have been moved into place.

Rehearsal against a byte copy of the live database (never the live one): `--build-info`
listed all modules clean, and `--run-migrations` reported `Migrations complete 0 applied`,
which also verifies every stored checksum.

Two restarts. Boot takes ~20s, so the first health probe at 12s legitimately refuses --
poll for 200 rather than sleeping a fixed interval. Verified after the second restart:
`/api/health` 200, the panel 200 over public HTTPS, `aa` on the SOA from 104.223.42.142,
listeners on 53/80/443/3000, no `[ERR]` lines that were not Undertow's INFO-on-stderr, and
`zd_deployed starfleet` = `current` for all 13 repos with no restart pending.

Rollback, if it is ever needed: `/root/hohenheim-preflight-20260828-234105/` holds the
previous jar as `hohenheim-server.jar.rollback`, a `.backup`ed database that passed
`PRAGMA integrity_check`, the settings directory and a sha256-matched keyring copy.

## Deploy 2026-08-29 (second): the third remediation wave, plus three settings edits

Shipped hohenheim `871275c5` at 09:44Z. Same clean-secondary-workspace lane. Every chain
repo was clean and committed this time, but protoblast (`c202f6a`) and hawkeye (`9552aee0`)
both moved (the boot-index presence requirements and the bridge version bump to 5), so the
whole chain republished: 586s warm. Migration diff between the deployed `5a030dcb` and HEAD:
EMPTY. Rehearsal against a byte copy: `--build-info` 13/13 clean, `--run-migrations`
`Migrations complete 0 applied`, inert boot answered `/api/health` 200 in 20s.

Three settings edits went in BEFORE the first restart, copies of both files kept in the
preflight directory:

- `local.dry` (the file the panel's settings editor persists to, root-scoped): added
  `brand.name = Hohenheim` (live setting; the login card had read "Zenit") and
  `activity.enabled = true` (restart-required; the audit trail had been off since install).
- `hohenheim.dry` (the `hohenheim.*` group): removed the obsolete `roles.processes` key,
  which every boot since `5a030dcb` logged as `settings.unknown_key`.

Still logged at boot, deliberately NOT touched: `settings.unknown_key key=security group=""`.
That is `local.dry`'s root-level `security` block (`never_ban` hostnames, `nftables_enabled`),
which has been silently ignored all along -- hohenheim's security group lives at
`hohenheim.security`, i.e. in `hohenheim.dry`, where a `security` block already exists with
two IPs. Moving the hostnames over changes ban behaviour and needs a decision on whether
hostnames are even valid `never_ban` entries.

Two restarts (26s and 22s to health). Verified after the second: `/api/health` 200, panel and
apex 200 over public HTTPS, the login page reads "Hohenheim", `aa` on the SOA from
104.223.42.142, listeners 53/80/443/3000, no application errors, and `zd_deployed starfleet`
= `current` for all 13 repos with no restart pending. The panel's Recent activity showed the
login and the first writes within the minute, so activity recording is on.

Rollback: `/root/hohenheim-preflight-20260829-093220/` (previous jar as
`hohenheim-server.jar.rollback`, `hohenheim.db.pre` and `hohenheim.db.atswap` both
integrity-checked, `settings/` with the pre-edit files and the sha256-matched keyring).
