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
8. If the deploy APPLIED migrations, raise `MigrationIntegrityTest.DEPLOYED_THROUGH`
   to the highest applied version and paste the pin lines the test prints into
   `src/browserTest/resources/migration-pins.txt`; those migrations are frozen now.

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

## Operations 2026-08-29: the security block, the install roles, the wildcard (not issued)

No jar changed. Preflight `/root/hohenheim-preflight-20260829-095543/` holds the pre-edit
`settings/` directory and `hohenheim.db.pre` (`PRAGMA integrity_check` ok). One restart.

- `local.dry`: the root-level `security` block is GONE. It was never a declared group (the
  boot logged `settings.unknown_key key=security group=""` since install), so its
  `never_ban` hostnames had never protected anyone. Its `nftables_enabled: true` was already
  set in `hohenheim.dry`; its three `never_ban` entries were merged into
  `hohenheim.dry`'s `security.never_ban`, which now reads `31.70.71.228`, `77.109.82.95`,
  `kumulus.11ways.be`, `loeckout.be` (hostnames are valid entries: `NeverBanHostnames`
  resolves them in the background; `kumulus.11ways.be` resolves to 77.109.82.95, the
  operator address the ssh session came from, `loeckout.be` to 77.109.112.183). The
  settings page shows all four after the restart and the boot logs no unknown key.
- `hohenheim.dry` roles: `stacks`, `databases`, `instances` are now `false`; `proxy`, `dns`
  and `firewall` stay `true`. Firewall is KEPT on purpose: it owns ban enforcement (8 active
  auto-bans, 91 rows, the `inet hohenheim` nft table) and spamservice reputation. Boot logged
  `hohenheim.roles_captured enabled=[dns, firewall, proxy]` and one `role_disabled` line per
  disabled role. The sidebar lost Hosts, Instances, Runtime images, Stacks, Databases and
  Instance templates; Dashboard, Projects, Sites, Git providers, DNS zones, Certificates,
  Access lists, Released hostnames, Users, Roles, Abuse protection, IP bans, Activity,
  Notification channels, Settings and Build info remain. Health 200 after 23s, panel and
  apex 200, `aa` on the SOA, listeners 53/80/443/3000, no application errors.
  Rows that stay in the database behind the disabled roles: 4 seeded runtime images, 2
  seeded instance templates, the backup target `phoenix` (still used by the control-plane
  backup, whose task `BackupControlPlane` carries no role gate and reads the model
  directly, so nightly backups are unaffected; only its admin surface is hidden), and one
  instance row `visual-qa-20260826-invalid` (id 1, no host, state `created`) that the
  Instances list already did not show before the role was disabled: QA debris from the
  2026-08-26 pass, deletable only through the database or by re-enabling the role.
  Two dashboard oddities to know: the onboarding checklist still shows the instance-tier
  steps ("Create an instance", "Deploy it") and Needs attention still lists Docker reconcile
  findings for `local`/`phoenix` although no role that acts on them is enabled.
- Wildcard certificate: NOT requested. `CertificateAuthority.authorize` refuses a hostname no
  site domain row covers (`NOT_SERVED`, before the admin bypass), and a `*.starfleet.life`
  SAN is covered only by a site domain row that is itself `*.starfleet.life`
  (`HostnamePatterns.covers`). The three live domain rows are `admin.starfleet.life`,
  `starfleet.life`, `www.starfleet.life` (all `exact`), so issuing the wildcard first needs a
  decision on which site owns `*.starfleet.life` (a wildcard domain row routes every unmatched
  subdomain to that site's upstream instead of today's "404 - No site configured"). The
  request page itself is ready for it: DNS-01 with "Hosted DNS" available because this server
  serves the zone, and the form copy says a wildcard does not cover the apex (already on the
  `starfleet.life,www.starfleet.life` certificate).
- Admin email confirmation: read-only assessment. `auth_users.email_verified_at` is NULL for
  `admin@starfleet.life`; `/account` shows "Your email address has not been confirmed yet"
  with a "Send a confirmation link" button. That button cannot work here: `EmailVerification
  .issueAndSend` requires `auth.external_base_url` (unset on this box, and hohenheim sets
  none) and a configured mail transport. The mail SENDER itself is already installed:
  zenit-auth's `AuthCommsBridge` autoloads because hohenheim ships zenit-comms, so
  `AuthMail.isAvailable()` is true; what is unset is `comms.channels.mail_transports`
  (one smtp DSN per line, `from=` required). Consequence worth knowing: `PasswordReset`
  refuses a reset for an unverified address, so forgot-password does not work for the
  administrator either; `--set-password` (offline command) is the recovery path. To confirm
  the address: set `auth.external_base_url = https://admin.starfleet.life` and
  `comms.channels.mail_transports` (keep `comms.delivery.synchronous` off, or forgot-password
  latency becomes an address oracle), then click the button and open the mailed link; or
  accept the unverified state.

## Deploy 2026-08-29 (third): the dashboard respects the disabled roles

Deployed hohenheim `12490d6e` at 12:38 CEST (`767be086` gates the readiness checklist,
the Docker/host attention items and the Reconcile-findings peer on
`HohenheimRoles.hostWorkloadsEnabled()`; `12490d6e` adds `dockerRequired()` so a dead
daemon is reported by stacks OR instances, the pair the boot probe already used). This
closes the two dashboard oddities the previous entry recorded. Upstream chain unchanged
since the second deploy of the day (`zd_deployed` was `current` for the 12 other repos).

The MAIN hohenheim worktree cannot stamp clean: `git status --porcelain` counts the
untracked harness files (`.claude/`, `.zembleignore`), so the first jar read
`hohenheim ... dirty=true` although HEAD was committed. Built again in the secondary
workspace (`~/projects/javaweb-deploy`, 15 detached worktrees at HEAD, own maven-local;
592 s), stamp 13/13 clean, sha256 `7e719678...bf80571`, uploaded with the 13-`false`
`verify_command`. Migration diff `871275c5..HEAD` EMPTY. Rehearsal on a byte copy in
`/root/hohenheim-rehearsal` (every role false, LE and DNS off, ports 18080/18443/15353,
panel 13999): `--build-info` 13/13 clean, `--run-migrations` `0 applied`, integrity ok,
inert boot health 200 in 20 s, 0 exceptions, no `settings.unknown_key`. Two restarts
(health 200 after 28 s and 27 s); after each: panel and apex 200 over public HTTPS, `aa`
on the SOA from 104.223.42.142, listeners 53/80/443/3000, journal free of errors,
`roles_captured enabled=[dns, firewall, proxy]`. `zd_deployed starfleet` = `current`
13/13, no restart pending. Live: the dashboard shows no readiness checklist, "All clear -
nothing needs attention", the four proxy/firewall tiles (Sites 2, Certificates 2, Access
lists 0, Active bans 8), and the sidebar without the runtime groups.

Rollback: `/root/hohenheim-preflight-20260829-102626/` (previous jar as
`hohenheim-server.jar.rollback`, `hohenheim.db.pre` + `hohenheim.db.at-swap` with
integrity ok, `settings/`, `keyring.sha256` over `settings/field-encryption.keys`, which
IS the keyring on this install). The rehearsal dir and the staged jar were removed; no
stray JVM. The secondary workspace was deleted afterwards. Note for the next deploy:
`~/projects/hohenext/build-worktrees/` still registers eight detached worktrees from the
08-23..08-27 deploys; they are not this lane's and were left alone.

## Operations 2026-08-29 (catch-all + wildcard): the wildcard certificate exists

No jar change; the running build is `12490d6e`. Preflight copy of the control-plane
database in `/root/hohenheim-preflight-20260829-catchall/hohenheim.db.pre` (integrity ok).

Why a site first: `CertificateAuthority.authorize` refuses any SAN no live site-domain
row covers (`NOT_SERVED`, checked before the admin bypass), and `HostnamePatterns.covers`
lets a `*.starfleet.life` SAN be covered only by a row that is itself a leading-`*.`
wildcard on that base. The proxy also selects the certificate at the handshake by the
hostname it serves, so a wildcard with no wildcard row would never be presented. The
zone already carried a `*` A record (id 2, `104.223.42.142`), so names resolved before
this change; they just landed on the proxy's "No site configured" 404.

Created, in this order:

- `/opt/hohenheim/public/catch-all/index.html` (owner `hohenheim:hohenheim`, 755/644):
  an ASCII placeholder page ("starfleet.life -- Nothing is configured at this address
  yet."). Sibling of `public/apex`; the rule that `public/` holds only authored content
  still holds.
- Site 5 `Starfleet catch-all`, upstream `hohenheim:static`, document root that path,
  directory listing OFF, index files on, fallback file `index.html` (so every path on an
  unclaimed subdomain renders the placeholder), enabled.
- Site domain 7 on site 5: `*.starfleet.life`, match type `wildcard`, Exclude from Let's
  Encrypt OFF, Force SSL OFF at creation and switched ON after issuance.
- Certificate 4 `Starfleet catch-all`: SAN `*.starfleet.life` only (the apex and `www`
  keep certificate 3), DNS-01 through Hosted DNS (this server), requested exactly once
  from `/admin/certificates-request?site=5`. Journal: `ACME: account ready (global,
  production)` 11:30:48Z, `ACME: certificate issued for *.starfleet.life` 11:30:56Z --
  eight seconds, the validation TXT record was published and removed by the hosted zone
  (zero TXT rows remain). Issuer Let's Encrypt YR2, valid 2026-08-29 10:32:24Z to
  2026-11-27 10:32:23Z, `auto_renew` 1, `dns_publisher` internal.

Routing precedence, proven from the host with `curl --resolve`/`Host:` against
127.0.0.1: `visual-qa-20260829d.starfleet.life` and `foo.bar.starfleet.life/some/path`
answer the placeholder (200); `admin.starfleet.life` still 301s to HTTPS and serves the
panel (200); the apex answers 200. `SiteDispatcher` logged `5 exact routes, 1 wildcard
routes` and resolves exact first (`RouteResolver`), so an exact row always beats the
wildcard row. TLS: `openssl s_client -servername visual-qa-20260829d.starfleet.life`
presents `CN=*.starfleet.life` with SAN `DNS:*.starfleet.life`; `curl` verifies against
the system CA store (`verify=0`), and the existing `admin.starfleet.life` and
`starfleet.life` certificates are byte-unchanged (same notBefore/notAfter). After Force
SSL: plain HTTP on a subdomain 301s to HTTPS, HTTPS 200.

How to add a subdomain site from now on: create the site, add its hostname as an EXACT
domain row (it wins over the wildcard row for routing), leave Exclude from Let's Encrypt
off and the certificate blank -- the wildcard covers it at the handshake, so no
per-subdomain certificate request is needed; only a name with more than one label under
`starfleet.life` (`a.b.starfleet.life`) falls outside the wildcard SAN and needs its
own certificate. Never give another site a second `*.starfleet.life` row: `RouteClaims`
refuses the overlap, and the wildcard certificate is authorised by this row.

Residue noted, not touched: sites 4 and 6 (`visual-qa-20260829m-a/b`, exact domain rows
5 and 6, Exclude-from-LE on) are still live rows from an earlier QA pass this day and
show in the Sites list; the dispatcher logs them as having no routable domain.

## Operations 2026-08-29 (comms hub): herald deployed, hohenheim coupled

Herald now runs beside hohenheim as the central comms hub (its own runbook:
`herald/docs/deploy-starfleet.md`, "Deploy 2026-08-29"). What changed on THIS
install:

- Site 7 `Herald comms hub`, address upstream HTTP `127.0.0.1:8092`, domain row 8
  `comms.starfleet.life` (exact, Force SSL on, Exclude from Let's Encrypt on --
  the `*.starfleet.life` wildcard covers it). Sites are now 4, domains 5.
- `settings/auth.dry` = `{"external_base_url": "https://admin.starfleet.life"}`
  and `settings/comms.dry` = `{"channels": {"mail_transports": "hub://zcm_...@127.0.0.1:8092?insecure=true"}}`,
  both 0600 owner hohenheim. The earlier 2026-08-29 entry's remedy ("set them in
  local.dry") was WRONG: zenit-auth reads `settings/auth.dry` (keys relative to
  the `auth` group) and zenit-comms has a context of its own that hohenheim
  never loaded from any file, so a `comms.*` key in `local.dry` is accepted and
  inert. `HohenheimCommsSettings` (hohenheim `a7cb2514`) now loads
  `settings/comms.dry` + `COMMS__*` env before the dispatcher is built and the
  settings page carries a Communication mount editing it.
- Deployed hohenheim `a7cb2514` (13/13 clean stamp; migration diff
  `12490d6e..a7cb2514` empty; rehearsal on a byte copy `0 applied`, health in
  20 s, 0 exceptions; ONE restart, health in 22 s, panel/apex/comms 200, `aa`
  SOA, listeners 53/80/443/3000 + herald on loopback 8092). RAM after: hohenheim
  RSS 360 MB, herald 340 MB, 746 MB available. Rollback for this swap:
  `/root/herald-preflight-20260829-115128/hohenheim-server.jar.rollback` with
  `hohenheim.db.at-swap`; the previous jar was `12490d6e`.
- Smoke: `/account` "Send a confirmation link" is accepted; the delivery is
  `sent` here (transport `hub`) and `queued` on the hub with
  `No transports configured for channel MAIL` until an smtp DSN is set ON THE
  HUB. Forgot-password now passes its 503 gate as well (base URL + lane).
- The `settings.source_missing /opt/hohenheim/settings/default.dry` boot line
  is pre-existing (the live install never had that file); harmless.

Still pending: the smtp DSN on herald (Settings -> Communication), then click
the button once more and open the mailed link to confirm the admin address.

## Deploy 2026-08-29 (fourth): the /manage isolation fixes and the auth picker

Shipped hohenheim `5d07a5f8` (previous `a7cb2514`) from a clean secondary
workspace, 13/13 clean stamp, migration diff empty, rehearsal `0 applied`,
two restarts (8 s / 21 s to health). Upstreams: zenit `3a0461e5`, zenit-cms
`e078ba32`, zenit-auth `baafb285`, plumage `0064f897` (the exact-match subject
lookup + unified 403 card), hawkeye `9552aee0`, protoblast `c202f6a9`.
Preflight `/root/hohenheim-preflight-20260829-final/`. Before the swap a STRAY
rehearsal JVM from the previous lane (root, cwd `/root/hohenheim-rehearsal
(deleted)`, `*:13999`, 318 MB) was still running and was killed; check
`ps -eo pid,user,args | grep java` for anything that is not the service user's
absolute-path jar before every rehearsal. The comms coupling (settings/comms.dry)
survived the restart. Live pass and cleanup ledger: `visual-qa-20260829-final.md`;
one high finding (F1: a tenant cannot save its own exact domain row under the
operator's wildcard) is open there.

## Deploy 2026-08-29 (fifth): the runtime-tier fixes and the first appended migration

Shipped hohenheim `c4e08045` (previous `5d07a5f8`) from an isolated detached
worktree (`build-worktrees/deploy-20260829-c4e08045`, deleted afterwards) with
every chain repo clean at its committed sha, so the main checkouts resolved the
chain (410 s, zenit-forms `e4b00b28` republished, the rest stamp-only). Stamp
13/13 clean, sha256 `b57e192e0a1f2e7ce0b52319ff70506eebac4dc948662cc8803045217fe6889b`,
267,477,750 bytes. Commits carried: `c4102b83` (tenant domain save under the
wildcard), `631b8a98` + `b3e9e840` (runtime-tier QA fixes), `4c69811a` +
`c4e08045` (M002 instead of an edited install migration).

THE FIRST REAL MIGRATION SINCE THE 08-23 CUTOVER. `git diff 5d07a5f8..HEAD --stat
-- '*igration*'` showed `M002_ManagedDatabaseFailureReason.java` (new) and a
comment-only edit of `InitialMigration.java`; the live `zenit_migrations` row for
001 carried checksum `0c97fcc11994...`, the digest `MigrationIntegrityTest` pins.
Rehearsal on a byte copy (as the service user, from `/opt/hohenheim-rehearsal-
20260829-fifth`, NOT under `/root`: the service user cannot traverse `/root`, so
`Unable to access jarfile` there is a permissions symptom, not a jar problem):
`--run-migrations` printed `Running migration 002 Managed database failure
reason` ... `Migrations complete 1 applied` with `database.migration_integrity`
at its default `fail`; `PRAGMA table_info(managed_databases)` listed
`failure_reason TEXT` nullable; the inert boot answered `/api/health` 200 after
26 s, `/login` 200, 0 exceptions, `roles_captured enabled=[]`.

Live lane, because the service does not need to be the one applying it: at-swap
`.backup` (integrity ok), `install` the staged jar beside the live one,
`systemctl stop hohenheim`, `mv` into place, `java -jar ... --run-migrations` as
`hohenheim` from `/opt/hohenheim` (`Migrations complete 1 applied`, 42 rows,
`002|Managed database failure reason`), `systemctl start`. Downtime 43 s to
health. Second restart 27 s. Verified after both: panel, apex, `comms.
starfleet.life`, a wildcard subdomain all 200 over public HTTPS; `aa` SOA;
listeners 53/80/443/3000 + 8092 loopback; 0 errors in the journal;
`roles_captured [dns, firewall, proxy]`; no unknown keys; `zenit-dev deployed
starfleet` = `current` for all 13 repos, no restart pending. Read-only live check:
`/admin/sites` (4 rows), `/admin/released-claims` (empty state "No released
hostnames yet"; the former-owner column had no row to render), `/account` still
reports the pending confirmation mail ("A confirmation link was already sent").

ROLLBACK IS NOW DB + JAR, NOT JAR ALONE. `MigrationRunner.checkIntegrity` treats
an applied migration that is absent from the discovered set as a finding, and at
`fail` (the default, no override in the settings files) the previous jar
`5d07a5f8` refuses to boot a database carrying 002 unless that version is
acknowledged in code. To roll back: stop, restore
`/root/hohenheim-preflight-20260829-fifth/hohenheim.db.at-swap` (taken
immediately before the migration; `.pre` is the earlier copy) over
`hohenheim.db` (remove `-wal`/`-shm`), restore `hohenheim-server.jar.rollback`,
start. Writes made after the swap are lost by that restore; `failure_reason` is
only ever written by a database provision, which the disabled databases role
makes impossible here.

## Deploys 2026-08-29 (sixth to ninth): the interactive console, and the first Alchemy app on starfleet

Four deploys in one session, each through the isolated-worktree lane (every
chain repo clean at its committed sha; `build-worktrees/deploy-20260829-<sha>`,
removed afterwards), each with a 13/13 clean stamp gated by `upload_file`'s
`verify_command`, a preflight dir under `/root/hohenheim-preflight-20260829-
tty{,2,3,4}/` (`.pre`, `.at-swap`, `settings/`, `hohenheim-server.jar.rollback`),
two restarts (32/22 s, 32/22 s, 32/22 s, 33/25 s to health), the outside-in
checks (panel 302, apex 200, `aa` SOA, google REFUSED, 0 journal errors) and
`zenit-dev deployed starfleet` = `current` 13/13 after each.

1. `f5b672e3` -- `console_kind = tty` (the Janeway console, `docs/interactive-
   console.md`) and **M003** dropping the unread `instance_templates.console_kind`.
   Rehearsed as the service user from `/opt/hohenheim-rehearsal-20260829-tty`:
   `Migrations complete 1 applied`, 43 rows, column gone, integrity ok, inert
   boot healthy after 26 s. Live lane as for M002 (stop, mv, `--run-migrations`
   as `hohenheim`, start). The SAME restart enabled roles `instances` and
   `databases` in `settings/hohenheim.dry` (`roles_captured [databases, dns,
   firewall, instances, proxy]`). ROLLBACK IS DB + JAR (the M002 rule):
   `/root/hohenheim-preflight-20260829-tty/hohenheim.db.at-swap`.
   TRAP: the inert rehearsal boot has a RELATIVE cmdline (`java -jar
   hohenheim-server.jar`), so `pkill -f <absolute path>` misses it; it sat on
   :13999 for 20 minutes. Kill by `readlink /proc/$pid/cwd` or by port.
2. `25179ee9` -- the Mongo readiness probe picks `mongosh` or the legacy `mongo`
   shell (starfleet's QEMU CPU has no AVX, so only `mongo:4.4` runs there; its
   first provision timed out on a missing `mongosh`). No migration.
3. `2336ef52` -- the btrfs volume lane elevates its root work with `sudo -n`
   when the controller is not root (`volume_own_failed`: chown to the workspace
   uid). No migration. Starfleet's blanket sudoers grant covers it; the narrow
   line is in `docs/deploy-native.md`.
4. `d17494d2` -- link networks are Docker-internal: the `idblink` network sorted
   before the workspace's own and became its default route, and every packet
   out died in the link's egress drop (`source_checkout_failed`, no DNS). No
   migration. The pre-fix link network was detached from both members and
   removed by hand; the next deploy recreated it internal.

Host changes beside the jar: `btrfs-progs` installed; an 8 GB loop file
`/opt/hohenheim/volumes.btrfs` mounted at `/opt/hohenheim/data/volumes`
(fstab, `loop,defaults,nofail`) because workspaces refuse a host without a
quota-capable volume root; host `local` preflighted (all required checks pass,
`Volume storage: Btrfs`), admitted, posture `shared_container` with the risk
acknowledged (the workspace kind refuses `trusted_only`).

Records created: database 2 `skeleton-mongo` (`mongo:4.4`, 512 MB, active;
record 1 was the failed first provision, deleted), instance 3
`alchemy-skeleton` (workspace, node-22, `https://github.com/skerit/alchemy-
skeleton.git` branch `hohenheim`, `npm install --no-audit --no-fund`, `node
server.js`, port 3000, 512 MB, console `tty`, env `ALCHEMY_SKIP_LOCAL_CONFIG=1`
`ALCHEMY_ENV=live`), attachment 1 (prefix `DB`), site 9 `Alchemy skeleton`
(instance upstream), domain 10 `skeleton.starfleet.life` (exact, wildcard
certificate). PROVEN: `https://skeleton.starfleet.life/` answers 200 with the
Hawkejs page; the Console tab renders Janeway (Mongo connected, HTTP on 3000)
and `1+41` sent as keystrokes plus `\r` over the console socket printed `42`.
Note for headless QA: the sketerm headless engine emits no terminal data for
the Enter key on ghostty-web (printable keys work), so prove Enter by sending
`\r` over the page's WebSocket; Janeway also ignores an Enter within 24 ms of
the previous key and lets the first Enter pick an open autocomplete.

Memory after the session: ~880 MB available with the JVM, herald, mongo and the
workspace running; the runtime image `hohenheim/node-22:1` was loaded from a
workstation build (`docker save | docker load`) rather than built by kaniko on
this 1-vCPU box.

## Deploy 2026-08-29 (tenth): the rollout mechanisms, M004 + M006, and kuifje's first swap

Shipped hohenheim `1c8a8a8b` (previous `d17494d2`) to starfleet AND, for the
first time, to kuifje, the DNS primary (`deploy-kuifje.md`). Isolated worktree
`build-worktrees/deploy-20260829-1c8a8a8b` (every chain repo clean at its
pushed sha, zenit-cms `b59c63df` republished; 98 s warm build, removed
afterwards), stamp 13/13 clean, sha256
`5ff10f8b9e0cb947a13ac4ffda07e3ce489232940e7baa0f7cac383c07483b2b`,
267,595,641 bytes, `upload_file` gated on `grep -c false | grep -qx 13`.
Commits carried (19): the site/domain, access-list, instance and DNS-zone
create APIs over zenit-cms `ResourceWrites`, `hoh` verbs, `hoh-import-legacy`,
`hoh-dns-diff`, the WordPress templates and template-declared databases
(**M006**), the fresh-host installer, DNS federation health (**M004**), the
declared nameserver set + NS-swapping zone import, the deploy-while-database-
provisioning refusal, subject labels through one home, the access-rule reload
hook, the API activity-detail fix, the migration pin rule.

Migration diff `d17494d2..1c8a8a8b`: `M004_DnsFederationHealth` (12 nullable
columns on `dns_zone_peers`/`dns_zones`) and `M006_TemplateDatabases` (a new
table). Rehearsed as the service user from
`/opt/hohenheim-rehearsal-20260829-tenth` on a byte copy: `Migrations complete
2 applied`, 45 rows, the new columns present, inert boot on 13999 healthy after
23 s with `-Xmx384m` (the box had ~610 MB available beside the JVM, herald,
mongo and the workspace; a bounded heap is what makes the rehearsal safe
here), 0 exceptions, killed by port. Live lane as for M002: at-swap `.backup`
(integrity ok), stop, `mv`, `--run-migrations` as `hohenheim` (2 applied),
start; 33 s to health. Second restart 23 s. Preflight
`/root/hohenheim-preflight-20260829-tenth/` (`.pre`, `.at-swap`, `settings/`,
keyring sha256 equal, `hohenheim-server.jar.rollback`).

Verified after both restarts: panel 302/200, apex 200, wildcard 200,
`comms.starfleet.life` 200, `skeleton.starfleet.life` 200 with the Hawkejs page
(one probe answered 202 during the instance supervisor's re-attach right after
the first start; 200 thereafter) and its Console tab renders the terminal;
listeners 53/80/443/3000 + 8092 loopback; 0 journal errors; `roles_captured
[databases, dns, firewall, instances, proxy]`; both instance containers kept
running; `zenit-dev deployed starfleet` = `current` 13/13.

THE DNS HEALTH TIER ON THE REAL FEDERATION: zone row action "Check health"
(it lands on the record page; the header button runs it) wrote
`delegation_status = matches` (the declared `dns.nameservers` set is empty on
this box, and an empty set is deliberately no finding) and probed the `kuifje`
link (then still named `ovh`) at served serial 34; the Secondaries tab showed
Freshness "Current", served 34, probed "just now". A disposable `visual-qa-20260829-deploy TXT
"deploy"` added then deleted through the Records tab moved the serial to 35
then 36; the primary journaled `dns.notify_sent` (peer kuifje, noerror) and
`dns.axfr_served` (key `xfer-ovh-starfleet`, ok) for each, kuifje transferred
each serial within 3 s, `hoh-dns-diff compare` was IDENTICAL for the TXT and
the SOA, and the Secondaries tab filled "Last AXFR served 36 just now" and
"Last NOTIFY noerror just now". Both records are gone. One oddity to keep an
eye on: the first `dns.notify_sent` carried serial 34 while the zone was
already at 35 (the delete's carried 36 correctly).

Pins: `MigrationIntegrityTest.DEPLOYED_THROUGH` raised to `006` and the M004
+ M006 digests added to `migration-pins.txt` (step 8 of the procedure, first
exercised here).

ROLLBACK IS DB + JAR: `/root/hohenheim-preflight-20260829-tenth/hohenheim.db.at-swap`
plus `hohenheim-server.jar.rollback`, exactly as for the fifth deploy.

## Deploy 2026-08-29 (eleventh): the DNS trace fixes and the delete gate, M007

Shipped hohenheim `b486427f` (previous `1c8a8a8b`) to starfleet and then kuifje
(`deploy-kuifje.md`, second jar swap). Isolated worktree
`build-worktrees/deploy-20260829-b486427f` (chain clean at the deployed shas,
so the main checkouts resolved it; 96 s warm build, removed afterwards), stamp
13/13 clean, sha256
`f9d9b2d0412438f0537494b14029be6f5d79baee119dd1b019cf8f113c188c07`,
267,596,947 bytes, `upload_file` gated on `grep -c false | grep -qx 13`.
Commits carried (5): `c30eac16` (one resolver for the instance destroy gate),
`525dbcbe` (DNS test fixtures), `742251b1` (pins for M004/M006), `fe15825f`
(NOTIFY announces the serial it just published; `last_notify_serial` column =
**M007**), `b486427f` (the served zone view is published on commit, not on
write -- a rolled-back CMS edit used to keep being served).

Migration diff `1c8a8a8b..b486427f`: `M007_DnsNotifySerial` only (one nullable
INTEGER column on `dns_zone_peers`). Rehearsed as the service user from
`/opt/hohenheim-rehearsal-20260830-eleventh` on a byte copy of the preflight
`.pre`: `Migrations complete 1 applied`, 46 rows, `last_notify_serial` present,
inert boot on 13999 healthy after 22 s with `-Xmx384m`, `/login` 200, 0
exceptions, `roles_captured enabled=[]`, killed by port, dir removed. Live lane:
at-swap `.backup` (integrity ok), `install` beside, stop, `mv`,
`--run-migrations` as `hohenheim` (`Running migration 007 DNS NOTIFY serial
trace`, 1 applied, 46 rows), start; 31 s to health. Second restart 30 s.
Preflight `/root/hohenheim-preflight-20260830-eleventh/` (`.pre`, `.at-swap`,
`settings/`, keyring sha256 equal, `hohenheim-server.jar.rollback`).

Verified after both restarts: panel 302, apex 200, wildcard 200,
`comms.starfleet.life` 200, `skeleton.starfleet.life` 200 (202 once during the
supervisor re-attach, as on the tenth deploy) and its Console tab renders the
terminal (canvas 890x384 + input textarea); listeners 53/80/443/3000; 0
journal errors at priority err (`journalctl -q`: the `-- No entries --` header
is NOT an error line, count with `-q`); `roles_captured [databases, dns,
firewall, instances, proxy]`; both instance containers kept running;
`zenit-dev deployed starfleet` = `current` 13/13.

THE SERIAL TRACE FIX ON THE REAL FEDERATION: a disposable
`visual-qa-20260829-wave2 TXT "wave2"` added then deleted through the Records
tab moved the serial 36 -> 37 -> 38. Each time the primary journaled
`dns.notify_sent` with the NEW serial (37, then 38; the tenth deploy had logged
the pre-bump serial), `dns.axfr_served` for the same serial, and
`dns_zone_peers.last_notify_serial` read 37 then 38 (the column M007 added; it
used to be lost entirely because the stamp write raced the open mutation
transaction). kuifje journaled `transferred secondary zone starfleet.life serial
37` and `38` within 3 s of each edit, served the TXT and then no longer did,
and both boxes answered SOA 38. `dns_records` holds 0 `visual-qa-%` rows.

Pins: `MigrationIntegrityTest.DEPLOYED_THROUGH` raised to `007` and the M007
digest added to `migration-pins.txt`.

ROLLBACK IS DB + JAR: `/root/hohenheim-preflight-20260830-eleventh/hohenheim.db.at-swap`
plus `hohenheim-server.jar.rollback`.

## Deploy 2026-08-30 (twelfth): the first three-controller wave, no migration

Shipped hohenheim `17fa6993` (previous `b486427f`) to starfleet, then kuifje
(`deploy-kuifje.md`, third jar swap) and, for the first time, robbedoes
(`deploy-robbedoes.md`, first jar swap). Isolated worktree
`build-worktrees/deploy-20260830-17fa6993` (chain clean at the pushed shas,
95 s warm build, removed afterwards), stamp 13/13 clean, sha256
`efbf9b35d7ab7f57fc2cedaff128d5670720ec249f95a243918a8e3c1b9d971a`,
267,604,365 bytes, `scp` + a remote `grep -c false | grep -qx 13` gate on
each host. Commits carried (7): `ab922504` (node-16 + node-12 runtime image seeds),
`e938ade9` + `d2f60c20` (the robbedoes runbook and its federation entry),
`f1e0c019` (loopback panel default in the installer), `c666a877` (pin mark
007), `5778cc65` (the names kuifje and robbedoes), `17fa6993` (TSIG
negotiation announces the transfer endpoint). Migration diff `b486427f..17fa6993`: NONE (only
`MigrationIntegrityTest` + `migration-pins.txt`), so no pin change is owed.

Rehearsed anyway, on every host, as the service user from
`/opt/hohenheim-rehearsal-20260830-twelfth` on a byte copy of the preflight
`.pre` with a hand-written JSON settings pair (all roles off, listener
127.0.0.1:13999): `Migrations complete 0 applied`, 46 rows, inert boot healthy
after 30 s (`-Xmx384m`, starfleet), `/login` 200, 0 exceptions, killed by
port, dirs removed. Preflight `/root/hohenheim-preflight-20260830-twelfth/`
(`.pre`, `.at-swap` integrity ok, `settings/`, keyring sha256 equal,
`hohenheim-server.jar.rollback`) on all three.

Live lane on starfleet: at-swap `.backup`, `install` beside, stop, `mv`,
`--run-migrations` as `hohenheim` (0 applied, 46 rows), start; 30 s to health
(45 s downtime). Second restart 28 s. 0 journal errors, `roles_captured
[databases, dns, firewall, instances, proxy]`, listeners 53/80/443/3000, both
instance containers kept running, panel 302, apex 200, wildcard 200,
`comms.starfleet.life` 200, `skeleton.starfleet.life` 200 (202 once during the
supervisor re-attach, as before) and its Console tab renders the terminal
(canvas 890x384 + input textarea). `zenit-dev deployed starfleet` = `current`.

THE FEDERATION WITH TWO SECONDARIES: a disposable `visual-qa-20260830-wave3
TXT "wave3"` added through the Records tab at 22:58:31.6Z moved the serial
42 -> 43; the primary journaled `dns.notify_sent` for `kuifje` AND `robbedoes`
(both `noerror`) and `dns.axfr_served` for both keys; kuifje transferred 43 at
22:58:33.1 and robbedoes at 22:58:33.2 (~1.6 s), all three served the TXT. The
delete (confirm at 22:59:07.0Z) moved it to 44: same four trace lines, both
secondaries at 44 by 22:59:08.5, the TXT NODATA everywhere,
`dns_zone_peers.last_notify_serial` = 44 for both links, `dns_records` holds 0
`visual-qa-%` rows. (`served_serial` still read 42 right after: the 5-minute
probe had not run yet, which is the cadence, not a defect.)

Leftover noticed, not touched: `/opt/hohenheim-rehearsal-20260829-tenth` on
this box was never removed by the tenth deploy; reclaim it with the next lane.

ROLLBACK IS JAR ONLY this time (no schema change), but the at-swap copy is
there regardless: `/root/hohenheim-preflight-20260830-twelfth/`.

## Deploy 2026-08-30 (thirteenth): the DNS/hoh fix batch, no migration, STARFLEET ONLY

Shipped hohenheim `e6d15bf1` (previous `17fa6993`) to starfleet ALONE. kuifje
and robbedoes were deliberately NOT deployed in this wave: the registrar
delegation of `tavernetomberg.be` had just moved to the mooo pair while
kuifje's zone still published `ns1`/`ns2.elevenways.de`, and another lane was
editing kuifje's panel to fix that -- a restart mid-edit would have collided,
and kuifje + robbedoes are each other's only nameserver. The build workspace is
kept alive so those two boxes swap the SAME jar without rebuilding.

Isolated worktree `build-worktrees/deploy-20260830-e6d15bf1` (detached at the
pushed HEAD, 109 s warm build), stamp 13/13 `dirty=false`, sha256
`605f53038b182deb4e7bb5151bc31ccf8405a16fc39e9b165edb0398f13cf417`,
267,611,836 bytes, `upload_file` with the remote
`unzip -p {} META-INF/blast/build-info.tsv | grep -c false | grep -qx 13` gate.

20 commits carried, of which FOUR touch shipped code -- `90f0e031` (a blank SOA
MNAME defaults to the first declared nameserver), `b4e96035` (DNS-01 never
publishes a challenge into a replicated zone: forward to the owning primary or
refuse `zone_not_primary`), `d7b45842` (a nameserver-type peer keeps no
edit-forwarding credentials), `b091de4c` (`hoh` help crash + per-invocation
`--context`) -- plus `7f7a68ea` (map-setting wire doc + its refusal test) and
15 runbook commits. Four UPSTREAM repos moved with it and were checked before
shipping: zenit `ab6baa0a` and protoblast `c01a49bd` are documentation only,
zenit-cms `c8753bfd` is a test only, and only zenit-forms `fa8378d1` (a
key/value map refuses a shape it cannot store) is production code.

Worth knowing: zenit-dev resolved zenit-forms as `stamp rewritten 0b2944b ->
fa8378d, gradle skipped`. That is the stamp-only republish path and it is
CORRECT -- the KeyValueField change had already been published before it was
committed, so the commit moved provenance and not content.

Migration diff `17fa6993..e6d15bf1`: NONE. Top migration is still M007, the pin
mark stays `DEPLOYED_THROUGH = "007"`, and `--run-migrations` reported
`Migrations complete 0 applied`. No rehearsal boot was run (no schema change);
the jar was instead proved runnable locally with `--build-info`, which printed
all 13 stamps clean.

Preflight `/root/hohenheim-preflight-20260830-thirteenth/`: `hohenheim.db.pre`
and a second `hohenheim.db.at-swap` (both `PRAGMA integrity_check` = ok),
`settings/` (keyring `field-encryption.keys` sha256 EQUAL to the original,
`414e9b13...`), and `hohenheim-server.jar.rollback` (sha256 `efbf9b35...`,
i.e. exactly the twelfth deploy's jar).

Live lane: stop 01:28:37Z, `mv`, `--run-migrations` as `hohenheim` (0 applied),
start 01:28:50Z, healthy 01:29:19Z -- 42 s downtime. Second restart 01:30:43Z,
healthy 01:31:11Z (28 s). Both restarts: 0 journal errors, listeners
53/80/443/3000, `roles_captured [databases, dns, firewall, instances, proxy]`,
RSS 336 MB. Row counts identical before and after (46 migrations, 9 sites,
6 domains, 4 certificates, 1 DNS zone, 8 DNS records).

Verified from outside (`--noproxy '*'`): apex and `www` 200, `admin` 302,
`comms.starfleet.life` 200, `skeleton.starfleet.life` 200, HTTP 301. Authoritative
DNS straight at the host: `starfleet.life` SOA and NS both `aa`, `google.com`
REFUSED (rcode 5). `zenit-dev deployed starfleet` = `current`, 13/13, no
RESTART PENDING.

The Alchemy skeleton and its interactive console survived untouched: both
containers kept their original start times across both restarts
(`instance-3` 2026-08-29T17:28:31Z, `instance-4` 16:51:20Z) and
`docker inspect` still reports `Config.Tty=true` on instance-3, which is the
console's substrate. `skeleton.starfleet.life` answered 202 on the first probe
after each restart and 200 seconds later -- the supervisor re-attach, exactly as
the twelfth deploy recorded, not a defect.

Housekeeping: `/opt/hohenheim-rehearsal-20260829-tenth` (flagged as a leftover
by the twelfth deploy) is GONE -- nothing to reclaim. A zero-byte root-owned
`/opt/hohenheim/data/hohenheim.db` was created by this lane's own first
`sqlite3` probe (the configured path is `/opt/hohenheim/hohenheim.db`) and was
removed again; `sqlite3` CREATES an empty database when handed a path that does
not exist, so never probe a guessed database path on a live host.

ROLLBACK IS JAR ONLY (no schema change): copy
`/root/hohenheim-preflight-20260830-thirteenth/hohenheim-server.jar.rollback`
back over `/opt/hohenheim/hohenheim-server.jar` and restart.

## Deploy 2026-08-30 (fourteenth): the database resize + node-10 seed, all three boxes, no migration

Shipped hohenheim `0782eb8b` (previous `e6d15bf1`) to starfleet, kuifje and
robbedoes, one box at a time. ONE build, reused byte-identically everywhere:
isolated workspace `build-worktrees/deploy-20260830-0782eb8b`, sha256
`b42c0ff25379876ea6f12d186b67402c9a8d5bfc7f5d3dd57d5c595173be4bbd`,
267,615,488 bytes, stamp **13/13 `dirty=false`**, the remote
`unzip -p {} META-INF/blast/build-info.tsv | grep -c false | grep -qx 13` gate
inside `upload_file` on every host before the move.

16 commits carried (`e6d15bf1..0782eb8b`), of which THREE touch shipped code:
`b73e35a1` (an operator can resize a managed database in place -- `updatable()`
opened, container-describing fields frozen with a record-aware `FieldAccess`,
the ceilings rebooked inline so `host_capacity_reached` refuses on the form),
`7d718e15` (the node-10 runtime image SEED ROW) and `edf598fa`/`99b57bcf`
(the node-10/12/16 Dockerfiles gaining graphicsmagick+imagemagick -- build
context only, no jar effect). The other 12 are runbook commits. No upstream
repo moved: all 12 were already `current` and clean at their pushed heads.

Migration diff `e6d15bf1..0782eb8b`: NONE -- nothing under `migration/`, top is
still M007, pin mark stays `DEPLOYED_THROUGH = "007"`. No rehearsal boot
(no schema change); the jar was proved runnable locally with `--build-info`,
which printed all 13 stamps clean. `--run-migrations` was run from an explicit
`cd /opt/hohenheim` on every box and reported `0 applied` each time.

THE WORKSPACE NEEDED FIFTEEN WORKTREES, NOT THIRTEEN. The build stamp has 13
rows, but the dependency chain is
`protoblast -> emberglyph -> hawkeye -> janeway -> zenit -> ...`: **emberglyph
and janeway carry no build-info stamp and so appear in no stamp listing**, yet
zenit does not resolve without them. A 13-worktree workspace with its own empty
`./.m2` fails at zenit with `Could not find be.elevenways:janeway:0.1.0-SNAPSHOT`,
and the surrounding "Daemon pool over its cap" lines are noise, not the cause --
read the gradle log, not the pool chatter. Adding the two worktrees fixed it;
the chain then built in 557 s with 0 errors (88 warnings, all pre-existing
upstream a11y/reactive advisories).

Live lane on starfleet: preflight `/root/hohenheim-preflight-20260830-fourteenth/`
(`.pre` + `.at-swap`, both `integrity_check` ok, `settings/`, keyring sha256
EQUAL at `414e9b13...`, `hohenheim-server.jar.rollback` = `605f5303...`).
Stop 03:39:29.2Z, start 03:39:45.0Z, healthy 03:40:13.0Z -- 44 s downtime.
Second restart 03:40:37.0Z, healthy 03:41:09.3Z (32 s). Both: 0 real journal
errors, listeners 53/80/443/3000, roles `[databases, dns, firewall, instances,
proxy]`, RSS 327 MB. Row counts identical (46 migrations, 9 sites, 6 domains,
4 certificates, 1 zone, 8 records). Apex 200, `www` 200, `admin` 302,
`comms` 200, HTTP 301, all `ssl_verify_result 0`; authoritative DNS at the host
`aa=True` for SOA and NS, `google.com` REFUSED (rcode 5). Both instance
containers kept their original start times (17:28:29Z, 16:51:19Z).

TWO READING TRAPS THIS LANE HIT, both benign, both worth keeping:

- **`journalctl -p err` counting non-errors.** Undertow's JDK logger emits its
  INFO lines at `[ERR]` level, and a naive `| wc -l` also counts journalctl's
  own `-- Journal begins --` header. `-p err` itself reported "No entries" the
  whole time. Filter `JDKLogger` and the header before counting, or read the
  count as zero.
- **`skeleton.starfleet.life` answering 202 is the APP, not the proxy and not a
  supervisor re-attach.** The container was never restarted (up 10 h), and a
  probe straight at its port `127.0.0.1:49160` also returned 202/2114. The body
  is the Alchemy skeleton's own `<title>Please Wait...</title>` queue page; the
  very next request answered 200/5359 and stayed there. Earlier entries called
  this "the supervisor re-attach" -- that was the symptom, this is the cause.

`zenit-dev deployed starfleet` = `current`, 13/13, no RESTART PENDING.

ROLLBACK IS JAR ONLY (no schema change): copy
`/root/hohenheim-preflight-20260830-fourteenth/hohenheim-server.jar.rollback`
(sha `605f5303...`, i.e. `e6d15bf1`) back into place and restart. Same path
exists on kuifje and robbedoes.

## Deploy 2026-08-30 (fifteenth): the CSP-boundary soft-nav fix + the pinned-cell hover fade, all three boxes, no migration

Shipped hohenheim `166180fe` (previous `0782eb8b`) to robbedoes, kuifje and
starfleet, in that order, one box at a time. ONE build, reused byte-identically:
isolated workspace `build-worktrees/deploy-20260830-166180fe` (15 detached
worktrees incl. emberglyph + janeway, per the fourteenth entry), sha256
`528e7bb7759f46712ebcdb9ebb94223f72128bdd1046d2f73300b513b0d05957`,
267,619,547 bytes, stamp **13/13 `dirty=false`**, `--build-info` printed all 13
clean, the remote `unzip -p {} META-INF/blast/build-info.tsv | grep -c false |
grep -qx 13` gate inside `upload_file` on every host. Chain build 598 s.

Hohenheim's own 6 commits since `0782eb8b` are runbook, Dockerfiles and the
installer -- no jar effect. THE POINT OF THE WAVE is upstream: hawkeye
`01848811` + zenit `461d31d9` + zenit-cms `1c04255` (a soft navigation whose
target carries a different Content-Security-Policy than the live document
degrades to a full page load, both directions -- the Janeway console tab was
unreachable by soft navigation because the document kept the STRICT_ADMIN
policy and ghostty's `fetch(data:application/wasm...)` was refused) and plumage
`b06fde5a` (the pinned `[data-sticky="end"]` actions cell now fades its hover
tint on the same transition as the row instead of snapping ahead of it).

Migration diff `0782eb8b..166180fe`: NONE (top M007, pin mark stays 007);
`--run-migrations` from an explicit `cd /opt/hohenheim` reported `0 applied`
on every box. No rehearsal boot (no schema change).

Starfleet lane: preflight `/root/hohenheim-preflight-20260830-fifteenth/`
(`.pre` + `.at-swap`, both `integrity_check` ok, `settings/`, keyring sha256
EQUAL at `414e9b13...`, `hohenheim-server.jar.rollback` = `b42c0ff2...`, i.e.
`0782eb8b`). Stop 08:52:40.6Z, start 08:52:57.4Z, `/api/health` 200 at
08:53:23.7Z (26 s), proxy + DNS listeners bound at 08:54:12Z. Second restart
08:53:38Z, healthy 08:54:09Z (30 s). Both: 0 real journal errors, RSS 324 MB,
listeners 53/80/443/3000, roles `[databases, dns, firewall, instances, proxy]`,
`CertificateStore: loaded 3 certificates`, `SiteDispatcher: loaded 5 exact
routes, 1 wildcard`. Row counts identical (46 migrations, 9 sites, 6 domains,
4 certificates, 1 zone, 8 records, 4 instances). Apex 200, `www` 200, `admin`
302, `comms` 200, `skeleton` 202 (the app's own queue page, see the fourteenth
entry), all `ssl_verify_result 0`; DNS `aa=True` for the SOA, `google.com`
REFUSED. Both containers kept their uptimes (15 h, 16 h).

TWO READING TRAPS: (1) `/api/health` answers 200 up to ~15 s BEFORE the proxy
and DNS listeners bind (`ProxyServer: listening` came 49 s after start here), so
a curl fired the moment health turns green reports `000` on 443 -- wait for the
`ProxyServer`/`DnsServer` journal lines or re-probe. (2) starfleet's Debian 11
journalctl cannot parse a fractional-second `--since` (`Failed to parse
timestamp: ...T08:52:40.6Z`) and then prints a count of 0 that means nothing;
use `YYYY-MM-DD HH:MM:SS` there.

`zenit-dev deployed starfleet` = `current`, 13/13, no RESTART PENDING.
ROLLBACK IS JAR ONLY: copy the `.rollback` back and restart. Workspace deleted.

## Deploy 2026-08-30 (sixteenth): 5c3696b2, one admin CSP + same-origin wasm, all three boxes, no migration

Same jar as robbedoes/kuifje (`a439c66a...`, 13/13 clean; chain hawkeye
`e0160bdf`, zenit `8130bcf4`, zenit-cms `ddcf03bb`, plumage `8afb1d94`,
carrying: one admin CSP with `'wasm-unsafe-eval'`, terminal CSP variant
deleted, ghostty-web 0.4.0 with the wasm as a pinned same-origin asset, the
event-based script-fate bridge, the re-landed dormant document-policy boundary
mechanism, the pinned-cell hover fade). Preflight
`/root/hohenheim-preflight-20260830-sixteenth/` (integrity ok, rollback =
`528e7bb7...`/`166180fe`). Stop 14:07:01Z, healthy 14:07:44Z (43 s), listeners
bound 14:07:52Z, second restart -> healthy2 14:08:14Z (21 s), 0 journal
errors, `0 applied`, row counts identical, both containers kept 21 h uptimes.
Apex/www 200, admin 302, comms 200, skeleton 202 (the app's own queue page),
ssl=0 everywhere; `hoh-dns-diff delegation starfleet.life` OK.
`zenit-dev deployed` = current 13/13 on all three targets.

## Deploy 2026-09-01: a7d65f01, the audit fix wave

Same jar as robbedoes/kuifje (`111e8cd5...`, 13/13 clean). Preflight
`/root/hohenheim-preflight-20260901-wave/`. M008 applied directly
(`1 applied`). Healthy after both restarts (~25s boot on this 2 GB box;
first health probe at 12s answers 000, that is boot time, not failure).
Unit gained `SupplementaryGroups=docker systemd-journal` (backup
`/root/hohenheim.service.bak-20260901`); heap stays `-Xmx768m`.
`security.ssh_watch_enabled` deliberately left OFF here: fail2ban already
guards sshd on this box, and two ban authorities on one port is the
duplication the watcher exists to avoid elsewhere.
Sites: apex 200, admin 302, comms hub 200; DNS SOA serving; `zd_deployed`
current 13/13.

## Deploy 2026-09-01 (wave 6): c817760a

Same jar as robbedoes/kuifje (`567fbc26...`, 13/13 clean). Preflight
`/root/hohenheim-preflight-20260901-wave2/` (db ok/47, rollback jar
`a7d65f01`). `0 applied`; healthy after both restarts (first probe green at
try 9 -- the ~25 s boot), 0 journal errors. Live: admin login 200, apex 200,
skeleton 202 (its own answer), herald active (its /api/health 302 -> /login is
herald's standing behaviour, untouched). Site 1 untouched. ROLLBACK:
preflight jar + restart.
