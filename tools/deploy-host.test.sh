#!/usr/bin/env bash
#
# deploy-host.test.sh -- drive tools/deploy-host.sh against FAKE ssh/scp/java/zenit-dev.
#
# Every external command the lane uses is a shim in a temp dir on PATH, so the
# ordering of the steps and the refusals (dirty stamp, no room on the host,
# failed rehearsal, health never green) are proven with no host, no jar and no
# network. A real deploy is still the real proof; this is the regression net
# around the script.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="$HERE/deploy-host.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

BIN="$WORK/bin"
mkdir -p "$BIN"

JAR="$WORK/hohenheim-server.jar"
printf 'not really a jar\n' > "$JAR"
JAR_SHA="$(sha256sum "$JAR" | cut -d' ' -f1)"

CONFIG="$WORK/config.json"
cat > "$CONFIG" <<CONFIG
{
    "deployments": {
        "testbox": {
            "ssh": "debian@testhost",
            "jar": "/opt/hohenheim/hohenheim-server.jar",
            "service": "hohenheim"
        },
        "rootbox": {
            "ssh": "root@roothost",
            "jar": "/opt/hohenheim/hohenheim-server.jar",
            "service": "hohenheim",
            "keepPreflights": 2
        },
        "badkeep": {
            "ssh": "root@roothost",
            "jar": "/opt/hohenheim/hohenheim-server.jar",
            "service": "hohenheim",
            "keepPreflights": 0
        }
    }
}
CONFIG

SSH_LOG="$WORK/ssh.log"
DEPLOYED_CALLS="$WORK/deployed.calls"

# --- the shims --------------------------------------------------------------

cat > "$BIN/java" <<'SHIM'
#!/usr/bin/env bash
# Only --build-info is ever invoked locally by the lane.
for arg in "$@"; do
    if [ "$arg" = "--build-info" ]; then
        if [ "${FAKE_STAMP:-clean}" = "dirty" ]; then
            printf 'hohenheim 12345678 master DIRTY 2026-09-02T10:00:00+02:00 [hohenheim-server]\n'
            printf 'zenit 9fd78ec7 master clean 2026-09-01T10:00:00+02:00 [zenit-common, zenit-server]\n'
            exit 0
        fi
        if [ "${FAKE_STAMP:-clean}" = "unstamped" ]; then
            printf 'unstamped: no META-INF/blast/build-info.tsv on the classpath\n'
            exit 0
        fi
        printf 'hohenheim 12345678 master clean 2026-09-02T10:00:00+02:00 [hohenheim-server]\n'
        printf 'zenit 9fd78ec7 master clean 2026-09-01T10:00:00+02:00 [zenit-common, zenit-server]\n'
        exit 0
    fi
done
printf 'fake java: unexpected invocation: %s\n' "$*" >&2
exit 3
SHIM

cat > "$BIN/scp" <<'SHIM'
#!/usr/bin/env bash
printf 'scp %s\n' "$*" >> "$SSH_LOG"
exit 0
SHIM

cat > "$BIN/ssh" <<'SHIM'
#!/usr/bin/env bash
# The remote script is the last argument; answer by what it asks for.
script="${@: -1}"
printf '%s\n' "$script" >> "$REMOTE_SCRIPTS"
log() { printf '%s\n' "$1" >> "$SSH_LOG"; }

case "$script" in
    *"@@space"*)
        log "space"
        root_kib=10000000
        opt_kib=10000000
        case "${FAKE_SPACE:-room}" in
            short) root_kib=500000; opt_kib=500000 ;;
            opt-short) opt_kib=500000 ;;
        esac
        printf '@@space\n'
        printf 'db 600000000\nwal 0\nsettings 100\noldjar 268000000\n'
        printf 'df preflight /dev/vda1 41000000 30000000 %s 80%% /\n' "$root_kib"
        printf 'df rehearse /dev/vdb1 41000000 30000000 %s 80%% /opt\n' "$opt_kib"
        printf 'df staging /dev/vda1 41000000 30000000 %s 80%% /\n' "$root_kib"
        printf 'df install /dev/vdb1 41000000 30000000 %s 80%% /opt\n' "$opt_kib"
        printf '@@end\n'
        ;;
    *"@@integrity"*)
        log "backup"
        printf '@@integrity\nok\n'
        printf '@@migrations\n001 Initial schema\n002 Managed database failure reason\n'
        printf '@@settings\n4\n'
        printf '@@keyring\nmatch deadbeef\n'
        printf '@@rollback\ncafebabe\n'
        printf '@@end\n'
        ;;
    *"--rehearse-migrations"*)
        log "rehearse"
        printf '@@java /usr/bin/java\n'
        printf '@@jvm %s\n' "${FAKE_JVM_OPTS--Xms128m -Xmx768m -XX:+UseSerialGC}"
        printf 'Rehearsal complete: 1 applied against /opt/hohenheim-rehearse-x/rehearse.db; the live database was not touched.\n'
        printf '@@seconds %s\n' "${FAKE_REHEARSE_SECONDS:-170}"
        printf '@@rehearse %s\n' "${FAKE_REHEARSE_EXIT:-0}"
        ;;
    *"@@stat"*)
        log "swap"
        printf '%s\n' "$script" > "$SWAP_SCRIPT"
        printf '@@stat\n268192262 hohenheim:hohenheim 644\n'
        printf '@@sha\n%s\n' "$FAKE_JAR_SHA"
        printf '@@start\nrc=0\n'
        printf '@@end\n'
        ;;
    *curl*)
        log "health"
        printf '%s\n' "$script" > "$HEALTH_SCRIPT"
        if [ "${FAKE_HEALTH:-green}" = "never" ]; then
            printf 'try 0: 000\ntry 1: 000\n@@unhealthy\n'
            printf 'Sep 02 10:00:00 host hohenheim[1]: BOOT FAILED: something\n'
        else
            printf 'try 0: 000\ntry 1: 200\n@@healthy\n'
        fi
        ;;
    *"zenit_migrations"*)
        log "migrations-after"
        [ "${FAKE_MIGRATIONS:-ok}" = ok ] || exit 7
        printf '001 Initial schema\n002 Managed database failure reason\n009 Shared database engines\n'
        ;;
    *"systemctl restart"*)
        log "restart2"
        ;;
    *"rollback.jar"*|*"rollingback"*)
        log "rollback-swap"
        printf '@@jar\n/opt/hohenheim/hohenheim-server.jar 267595641 hohenheim:hohenheim 644\n'
        printf 'cafebabe  /opt/hohenheim/hohenheim-server.jar\n'
        printf '@@start 0\n'
        ;;
    *sha256sum*)
        log "verify-upload"
        printf '%s\n' "$FAKE_JAR_SHA"
        ;;
    *"@@prune"*)
        log "prune"
        printf '%s\n' "$script" > "$PRUNE_SCRIPT"
        printf '@@prune\nkeep /root/hohenheim-preflight-20261011-120000\n'
        printf 'pruned /root/hohenheim-preflight-20260901-old\n@@end\n'
        ;;
    *"rm -rf"*)
        log "cleanup"
        printf '%s\n' "$script" > "$CLEANUP_SCRIPT"
        ;;
    *)
        log "other"
        ;;
esac
exit 0
SHIM

cat > "$BIN/zenit-dev" <<'SHIM'
#!/usr/bin/env bash
printf 'deployed\n' >> "$DEPLOYED_CALLS"
calls="$(wc -l < "$DEPLOYED_CALLS")"
verdict="current"
upstream="current"
unpushed=0
# The first call is the BEFORE report: the host still runs the old jar, so every repo
# with commits since then reads pushed-ahead, upstream ones included.
if [ "$calls" = "1" ]; then verdict="pushed-ahead"; upstream="pushed-ahead"; fi
if [ "${FAKE_DEPLOYED:-current}" = "stuck" ]; then verdict="deployed-ahead"; fi
if [ "${FAKE_DEPLOYED:-current}" = "upstream-ahead" ]; then upstream="pushed-ahead"; fi
if [ "${FAKE_DEPLOYED:-current}" = "unpushed" ]; then unpushed=2; fi
cat <<JSON
{
  "target": "testbox",
  "host": "debian@testhost",
  "jar": {"path": "/opt/hohenheim/hohenheim-server.jar"},
  "service": {"name": "hohenheim", "restartPending": false},
  "stamped": true,
  "repos": [
    {"repo": "hohenheim", "verdict": "$verdict", "shortSha": "12345678", "local": {"unpushed": $unpushed, "behind": 0}},
    {"repo": "zenit", "verdict": "$upstream", "shortSha": "9fd78ec7", "local": {"unpushed": 0, "behind": 3}}
  ],
  "verdict": "$verdict",
  "problems": []
}
JSON
SHIM

chmod +x "$BIN"/*
export PATH="$BIN:$PATH"
export SSH_LOG DEPLOYED_CALLS
export REMOTE_SCRIPTS="$WORK/remote-scripts"
export SWAP_SCRIPT="$WORK/swap.sh"
export HEALTH_SCRIPT="$WORK/health.sh"
export PRUNE_SCRIPT="$WORK/prune.sh"
export CLEANUP_SCRIPT="$WORK/cleanup.sh"
export FAKE_JAR_SHA="$JAR_SHA"
export ZENIT_DEV_CONFIG="$CONFIG"

# shellcheck source=test-assert.sh
. "$HERE/test-assert.sh"

reset_logs() { : > "$SSH_LOG"; : > "$DEPLOYED_CALLS"; : > "$REMOTE_SCRIPTS"; rm -f "$PRUNE_SCRIPT" "$CLEANUP_SCRIPT"; }

run_lane() {
    reset_logs
    bash "$SCRIPT" "$@" 2>&1
}

# --- 1. the whole lane, green ------------------------------------------------

OUT="$(run_lane testbox "$JAR")" || no "the green lane exits 0"
ok "the green lane ran to the end"
expect "reads the ssh target out of the deployments config" "$OUT" "ssh=debian@testhost"
expect "a non-root ssh user means sudo -n" "$OUT" "privilege: sudo -n"
expect "step 1 verifies the local stamp" "$OUT" "1. Local build stamp"
expect "counts the clean repos" "$OUT" "2 repos stamped clean"
expect "step 2 asks what runs there now" "$OUT" "2. What runs on testbox right now"
expect "step 3 checks the host has room first" "$OUT" "room for the backup and rehearsal copies"
expect "step 3 names each filesystem's free and needed space" "$OUT" "/opt: 9.5 GB free"
expect "step 3 verifies the uploaded sha256" "$OUT" "sha256 matches on the host"
expect "step 4 checks the backup's integrity" "$OUT" "database backed up, integrity ok"
expect "step 4 compares the keyring" "$OUT" "keyring sha256 matches the copy"
expect "step 4 keeps a rollback jar" "$OUT" "rollback jar: /root/hohenheim-preflight-"
expect "step 5 rehearses" "$OUT" "rehearsal green"
expect "step 7 probes health" "$OUT" "healthy"
expect "step 8 diffs the migration ledger" "$OUT" "zenit_migrations: 2 before, 3 after"
expect "step 8 names what the boot applied" "$OUT" "applied: 009 Shared database engines"
expect "step 8 points at the pin lane" "$OUT" "--migration-checksums"
expect "step 9 restarts a second time" "$OUT" "9. Second restart (mandatory)"
expect "step 10 demands current" "$OUT" "current, no restart pending"
expect "prints a runbook skeleton" "$OUT" "## Deploy"
expect "the skeleton carries the jar sha" "$OUT" "$JAR_SHA"
expect "the skeleton names the rollback command" "$OUT" "--rollback testbox --preflight"
expect "step 11 keeps the default five preflights" "$OUT" "keeping the 5 newest /root/hohenheim-preflight-*"
expect "step 11 names what it pruned" "$OUT" "pruned /root/hohenheim-preflight-20260901-old"
expect "the default retention is five" "$(cat "$PRUNE_SCRIPT")" "keep=5"
expect "the scratch cleanup never touches a preflight" "$(cat "$CLEANUP_SCRIPT")" "hohenheim-preflight-" no

SCRIPTS="$(cat "$REMOTE_SCRIPTS")"
expect "the rehearsal copies the settings directory" "$SCRIPTS" \
    "cp -a '/opt/hohenheim/settings' '/opt/hohenheim-rehearse-"
expect "the rehearsal boots on that copy, never the live settings" "$SCRIPTS" \
    "-Dzenit.settings.root='/opt/hohenheim-rehearse-"
expect "the rehearsal reads the service unit's environment" "$SCRIPTS" \
    "systemctl show 'hohenheim' -p Environment --value"
expect "and runs the JVM with the unit's JAVA_TOOL_OPTIONS" "$SCRIPTS" \
    "env JAVA_TOOL_OPTIONS=\"\$JVM_OPTS\" \"\$JAVA\""
expect "the rehearsal's JVM options are printed" "$OUT" \
    "rehearsal JVM options (the hohenheim unit's JAVA_TOOL_OPTIONS): -Xms128m -Xmx768m -XX:+UseSerialGC"
expect "the rehearsal is timed on the host" "$SCRIPTS" "echo \"@@seconds"
expect "the health budget derives from the rehearsal's wall time and says why" "$OUT" \
    "health budget 460s = 2 x 170s rehearsal + 120s (the boot migrates; no --health-timeout given)"
expect "the probe uses that derived budget" "$(cat "$HEALTH_SCRIPT")" "+ 460))"
expect "step 7 names the derived budget" "$OUT" "7. Health probe (up to 460s"

# The JVM options must survive the unquoting the host applies to systemctl's output.
UNIT_ENV='"JAVA_TOOL_OPTIONS=-Xms128m -Xmx768m -Djava.io.tmpdir=/opt/hohenheim/tmp" TZ=Europe/Brussels'
PARSED="$(printf '%s\n' "$UNIT_ENV" | xargs -n1 printf '%s\n' | sed -n 's/^JAVA_TOOL_OPTIONS=//p' | tail -n 1)"
if [ "$PARSED" = "-Xms128m -Xmx768m -Djava.io.tmpdir=/opt/hohenheim/tmp" ]; then
    ok "a quoted JAVA_TOOL_OPTIONS assignment is read whole"
else
    no "a quoted JAVA_TOOL_OPTIONS assignment is read whole (got '$PARSED')"
fi

LOG="$(cat "$SSH_LOG")"
ORDER="$(/usr/bin/grep -v '^scp ' "$SSH_LOG" | tr '\n' ' ')"
expect "the jar is uploaded before anything is verified on the host" "$LOG" "scp "
expect "backup, rehearsal and swap happen in that order, pruning only at the very end" "$ORDER" \
    "space verify-upload backup rehearse swap health migrations-after restart2 health cleanup prune"
if [ "$(wc -l < "$DEPLOYED_CALLS")" = "2" ]; then
    ok "zenit-dev deployed is asked before and after"
else
    no "zenit-dev deployed is asked before and after (got $(wc -l < "$DEPLOYED_CALLS") calls)"
fi
STEP2="$(printf '%s\n' "$OUT" | sed -n '/^== 2\./,/^== 3\./p')"
expect "step 2 names the repos the pending deploy replaces" "$STEP2" \
    "the host lacks pushed commits of upstream repos (the deploy is pending): zenit"
expect "step 2 never reads the OLD jar's pushed-ahead as pushed after this build" "$OUT" \
    "pushed after this build" no

# An explicit --health-timeout wins over the derived budget; a quick rehearsal still gets the base 120s.
OUT="$(run_lane testbox "$JAR" --health-timeout 900)" || no "the lane with an explicit budget exits 0"
expect "an explicit --health-timeout wins" "$OUT" "health budget 900s, as --health-timeout named"
expect "and the probe uses it" "$(cat "$HEALTH_SCRIPT")" "+ 900))"
OUT="$(FAKE_REHEARSE_SECONDS=0 FAKE_JVM_OPTS= run_lane testbox "$JAR")" || no "the lane with a quick rehearsal exits 0"
expect "a quick rehearsal keeps the base budget" "$OUT" "health budget 120s = 2 x 0s rehearsal + 120s"
expect "a unit without JAVA_TOOL_OPTIONS is warned about" "$OUT" "declares no JAVA_TOOL_OPTIONS"

# --- 2. refusal: a dirty stamp ----------------------------------------------

if OUT="$(FAKE_STAMP=dirty run_lane testbox "$JAR" 2>&1)"; then
    no "a DIRTY stamp is refused"
else
    expect "the dirty stamp refusal names it" "$OUT" "carries a DIRTY stamp"
    expect "nothing was uploaded after a dirty stamp" "$(cat "$SSH_LOG")" "scp " no
    expect "a lane refused before the upload has nothing to clean" "$(cat "$SSH_LOG")" "cleanup" no
fi

if OUT="$(FAKE_STAMP=unstamped run_lane testbox "$JAR" 2>&1)"; then
    no "an unstamped jar is refused"
else
    expect "the unstamped refusal names it" "$OUT" "carries no build stamp"
fi

# --- 3. refusal: the rehearsal fails ----------------------------------------

if OUT="$(FAKE_REHEARSE_EXIT=1 run_lane testbox "$JAR" 2>&1)"; then
    no "a failed migration rehearsal is refused"
else
    expect "the rehearsal refusal names the exit code" "$OUT" "--rehearse-migrations exited 1"
    expect "the rehearsal refusal says the live database is untouched" "$OUT" "the live database was not touched and nothing was swapped"
    expect "no swap happened after a failed rehearsal" "$(cat "$SSH_LOG")" "swap" no
    expect "a refused lane removes its own scratch copies" "$(cat "$SSH_LOG")" "cleanup"
    expect "the scratch it removes is the rehearsal copy" "$(cat "$CLEANUP_SCRIPT")" "rm -rf '/opt/hohenheim-rehearse-"
    expect "and the uploaded jar" "$(cat "$CLEANUP_SCRIPT")" "rm -f 'hohenheim-deploy-"
    expect "a refused lane keeps its preflight backup" "$(cat "$CLEANUP_SCRIPT")" "hohenheim-preflight-" no
    expect "a refused lane prunes no preflight" "$(cat "$SSH_LOG")" "prune" no
fi

# --- 4. refusal: health never turns green -----------------------------------

if OUT="$(FAKE_HEALTH=never run_lane testbox "$JAR" --health-timeout 4 2>&1)"; then
    no "a health probe that never turns green is refused"
else
    expect "the health refusal names the url and the budget" "$OUT" "never answered 200 within 4s"
    expect "the journal lines are shown" "$OUT" "BOOT FAILED"
    expect "it says nothing was rolled back" "$OUT" "NOTHING was rolled back"
    expect "it prints the rollback commands instead" "$OUT" "ROLLBACK (run these by hand"
    expect "the rollback names the preflight jar" "$OUT" "rollback.jar"
    expect "it never restarted a second time" "$(cat "$SSH_LOG")" "restart2" no
    expect "a failed deploy prunes no preflight" "$(cat "$SSH_LOG")" "prune" no
fi

# --- 5. refusal: the host does not report current ---------------------------

if OUT="$(FAKE_DEPLOYED=stuck run_lane testbox "$JAR" 2>&1)"; then
    no "a host that does not report current is refused"
else
    expect "the not-current refusal names the state" "$OUT" "does not report current for every repo"
    expect "it names the repo that is not current" "$OUT" "hohenheim=deployed-ahead"
    expect "a deploy that is not current prunes no preflight" "$(cat "$SSH_LOG")" "prune" no
fi

# An UPSTREAM repo pushed after the build fetched it is a warning, never a refusal:
# the jar is exactly the pushed heads it was built from. Only step 10, where the host
# runs this build, can say so.
OUT="$(FAKE_DEPLOYED=upstream-ahead run_lane testbox "$JAR" 2>&1)" || no "an upstream pushed-ahead lane exits 0"
STEP10="$(printf '%s\n' "$OUT" | sed -n '/^== 10\./,/^== 11\./p')"
expect "an upstream pushed-ahead after the swap is reported as a warning" "$STEP10" \
    "WARNING: upstream repos were pushed after this build"
expect "and names the repo" "$STEP10" "ships them): zenit"
if [ "$(printf '%s\n' "$OUT" | /usr/bin/grep -c 'pushed after this build')" = "1" ]; then
    ok "the pushed-after-build warning comes from step 10 alone"
else
    no "the pushed-after-build warning comes from step 10 alone"
fi

# Unpushed local commits (hohenheim's own included) are in no deploy of pushed heads:
# a warning naming the count, never a refusal; a lagging checkout is not even that.
OUT="$(FAKE_DEPLOYED=unpushed run_lane testbox "$JAR" 2>&1)" || no "a lane beside unpushed local commits exits 0"
expect "unpushed local commits are a warning" "$OUT" "WARNING: local checkouts hold unpushed commits"
expect "naming repo and count" "$OUT" "pushed heads carries: hohenheim (2)"

# --- 5b. refusal: the host lacks room for the copies -------------------------

if OUT="$(FAKE_SPACE=short run_lane testbox "$JAR" 2>&1)"; then
    no "a host without room for the copies is refused"
else
    expect "the space refusal says why" "$OUT" "REFUSED: not enough free space on the host for the backup and rehearsal copies"
    expect "the space refusal names free and needed" "$OUT" "/: 488 MB free, 1.6 GB needed"
    expect "nothing was uploaded without room" "$(cat "$SSH_LOG")" "scp " no
    expect "no backup was started without room" "$(cat "$SSH_LOG")" "backup" no
fi
if OUT="$(FAKE_SPACE=opt-short run_lane testbox "$JAR" 2>&1)"; then
    no "a host whose /opt lacks room is refused"
else
    REFUSAL="$(printf '%s\n' "$OUT" | /usr/bin/grep '^REFUSED')"
    expect "a separate filesystem is judged on its own" "$REFUSAL" "/opt: 488 MB free"
    expect "a filesystem with room is not named short" "$REFUSAL" " /: " no
fi

# --- 6. argument refusals ---------------------------------------------------

if OUT="$(run_lane nosuchbox "$JAR" 2>&1)"; then
    no "an unknown target is refused"
else
    expect "the unknown target lists the known ones" "$OUT" "unknown deployment target"
    expect "the known root target is named" "$OUT" "rootbox"
    expect "the known sudo target is named" "$OUT" "testbox"
fi

if OUT="$(run_lane badkeep "$JAR" 2>&1)"; then
    no "a retention below one is refused"
else
    expect "the bad retention is named" "$OUT" "keepPreflights must be a whole number of at least 1"
fi

if OUT="$(run_lane testbox "$WORK/absent.jar" 2>&1)"; then
    no "an absent jar is refused"
else
    expect "the absent jar is named" "$OUT" "No such jar"
fi

if OUT="$(run_lane testbox 2>&1)"; then
    no "a missing jar argument is refused"
else
    expect "the missing jar argument is named" "$OUT" "name the local jar"
fi

if OUT="$(run_lane testbox "$JAR" --health-timeout soon 2>&1)"; then
    no "a non-numeric health timeout is refused"
else
    expect "the bad timeout is named" "$OUT" "whole seconds"
fi

if OUT="$(run_lane testbox "$JAR" --skip-second-restart 2>&1)"; then
    no "there is no way to skip the second restart"
else
    expect "an unknown flag is refused" "$OUT" "Unknown option: --skip-second-restart"
fi

# --- 7. root targets skip sudo ----------------------------------------------

OUT="$(run_lane rootbox "$JAR")" || no "the root lane exits 0"
expect "a root ssh user needs no sudo" "$OUT" "privilege: none needed"
expect "a target's own retention is used" "$(cat "$PRUNE_SCRIPT")" "keep=2"
OUT="$(run_lane rootbox "$JAR" --dry-run)" || no "the root dry run exits 0"
expect "root still drops to the service user for the rehearsal" "$OUT" "runuser -u 'hohenheim' -- "
OUT="$(run_lane testbox "$JAR" --dry-run)" || no "the sudo dry run exits 0"
expect "a sudo box runs the rehearsal as the service user through sudo" "$OUT" "sudo -n -u 'hohenheim' "

# --- 8. the dry run executes nothing ----------------------------------------

OUT="$(run_lane testbox "$JAR" --dry-run)" || no "the dry run exits 0"
expect "the dry run prints its plan" "$OUT" "PLAN: ssh debian@testhost"
expect "the dry run plans the rehearsal" "$OUT" "--rehearse-migrations"
if [ -s "$SSH_LOG" ]; then
    no "the dry run executed nothing (ssh.log is not empty)"
else
    ok "the dry run executed nothing"
fi

# --- 9. rollback mode -------------------------------------------------------

OUT="$(run_lane --rollback testbox --preflight /root/hohenheim-preflight-20260902-wave4)" \
    || no "rollback mode exits 0"
expect "rollback swaps the preflight jar back" "$OUT" "Rollback: /root/hohenheim-preflight-20260902-wave4/rollback.jar"
expect "rollback probes health too" "$OUT" "Health probe after the rollback"
expect "rollback says the database is not restored for you" "$OUT" "the database is NOT restored by this mode"
expect "rollback did the swap" "$(cat "$SSH_LOG")" "rollback-swap"
expect "rollback restores the settings the older jar read" "$(cat "$REMOTE_SCRIPTS")" \
    "cp -a '/root/hohenheim-preflight-20260902-wave4/settings' '/opt/hohenheim/settings'"
expect "rollback keeps the newer settings aside" "$(cat "$REMOTE_SCRIPTS")" \
    "mv '/opt/hohenheim/settings' '/opt/hohenheim/settings.rolled-back-"

if OUT="$(run_lane --rollback testbox 2>&1)"; then
    no "rollback without --preflight is refused"
else
    expect "rollback names the missing flag" "$OUT" "--rollback needs --preflight"
fi

# --- 10. failed rollback and unreadable migration ledger are not success -----

if OUT="$(FAKE_HEALTH=never run_lane --rollback testbox --preflight /root/test)"; then
    no "an unhealthy rollback fails"
else
    expect "rollback failure names recovery" "$OUT" "rollback health never answered 200"
fi

if OUT="$(FAKE_MIGRATIONS=unreadable run_lane testbox "$JAR")"; then
    no "an unreadable migration ledger fails"
else
    expect "the ledger failure is unknown, not no migrations" "$OUT" "migration outcome is unknown"
    expect "no false migration success" "$OUT" "no migrations applied by this deploy" no
fi

# --- 11. execute the generated swap under POSIX sh, with failing host tools ---
# These functions replace every mutating command; no host or real service exists.
OUT="$(run_lane testbox "$JAR")" || no "capture the generated swap"
cat > "$WORK/execute-swap.sh" <<'REMOTE'
set -e
sudo() { shift; "$@"; }
install() { :; }
systemctl() { printf 'service %s\n' "$1"; }
sqlite3() { [ "$BREAK_AT" != backup ]; }
mv() { [ "$BREAK_AT" != move ]; }
stat() { return 1; }
sha256sum() { printf 'testhash jar\n'; }
. "$SWAP_SCRIPT"
REMOTE
for failure in backup move; do
    if OUT="$(BREAK_AT="$failure" sh "$WORK/execute-swap.sh" 2>&1)"; then
        no "$failure failure propagates"
    else
        expect "$failure failure restarts the installed jar" "$OUT" "service start"
        expect "$failure failure happened after the stop" "$OUT" "service stop"
    fi
done
OUT="$(BREAK_AT=none sh "$WORK/execute-swap.sh" 2>&1)" || no "a failed stat still starts the service"
expect "post-swap inspection failure still starts" "$OUT" "service start"
if [ "$(printf '%s\n' "$OUT" | /usr/bin/grep -c '^service start$')" = 1 ]; then
    ok "a successful swap starts only once"
else
    no "a successful swap starts only once"
fi
expect "settings inventory uses the same privilege as the copy" "$(cat "$REMOTE_SCRIPTS")" "sudo -n find "
expect "keyring existence is checked with privilege" "$(cat "$REMOTE_SCRIPTS")" "if sudo -n test -f "
OUT="$(run_lane --rollback testbox --preflight /root/test)" || no "capture the rollback"
expect "rollback can inspect its root-only preflight directory" "$(cat "$REMOTE_SCRIPTS")" "sudo -n test -f '/root/test/rollback.jar'"
expect "health requests have a network timeout" "$(cat "$REMOTE_SCRIPTS")" '--connect-timeout 2 --max-time "$request_timeout"'

# A responding server that never finishes its request must still exhaust the
# wall-clock budget. Exercise the emitted POSIX shell with a timeout-aware curl.
OUT="$(run_lane testbox "$JAR" --health-timeout 1)" || no "capture a one-second probe"
cat > "$WORK/execute-health.sh" <<'REMOTE'
sudo() { shift; "$@"; }
journalctl() { printf 'test journal\n'; }
curl() {
    while [ "$#" -gt 0 ]; do
        if [ "$1" = --max-time ]; then sleep "$2"; printf 000; return 28; fi
        shift
    done
    return 2
}
. "$HEALTH_SCRIPT"
REMOTE
START_SECONDS=$SECONDS
OUT="$(sh "$WORK/execute-health.sh")"
expect "a timed-out request is reported unhealthy" "$OUT" "@@unhealthy"
expect "a curl failure does not duplicate its 000 status" "$OUT" "000000" no
if [ "$((SECONDS - START_SECONDS))" -le 2 ]; then
    ok "one-second health budget bounds a hanging request"
else
    no "one-second health budget bounds a hanging request"
fi

# --- 12. execute the generated prune: the N newest stay, the current always ----
# The current preflight carries the OLDEST mtime here, so only its name keeps it.
OUT="$(run_lane testbox "$JAR" --preflight /root/hohenheim-preflight-20261012-040000)" || no "capture the prune"
cat > "$WORK/execute-prune.sh" <<'REMOTE'
sudo() { shift; "$@"; }
find() {
    printf '%s\n' '1000.5 /root/hohenheim-preflight-20261012-040000' \
        '9500.0 /root/not-a-preflight' \
        '9000.0 /root/hohenheim-preflight-20261011-a' '8000.0 /root/hohenheim-preflight-20261010-b' \
        '4000.0 /root/hohenheim-preflight-20261006-f' '7000.0 /root/hohenheim-preflight-20261009-c' \
        '6000.0 /root/hohenheim-preflight-20261008-d' '5000.0 /root/hohenheim-preflight-20261007-e'
}
rm() { printf 'rm %s\n' "$*"; }
. "$PRUNE_SCRIPT"
REMOTE
OUT="$(sh "$WORK/execute-prune.sh" 2>&1)" || no "the prune script runs under POSIX sh"
expect "the oldest beyond five is pruned" "$OUT" "rm -rf -- /root/hohenheim-preflight-20261006-f"
expect "the next oldest beyond five is pruned" "$OUT" "rm -rf -- /root/hohenheim-preflight-20261007-e"
expect "the fourth newest other is kept" "$OUT" "rm -rf -- /root/hohenheim-preflight-20261008-d" no
expect "the newest other is kept" "$OUT" "rm -rf -- /root/hohenheim-preflight-20261011-a" no
expect "the current preflight is never pruned" "$OUT" "rm -rf -- /root/hohenheim-preflight-20261012-040000" no
expect "a directory outside the pattern is never pruned" "$OUT" "not-a-preflight" no
if [ "$(printf '%s\n' "$OUT" | /usr/bin/grep -c '^rm ')" = 2 ]; then
    ok "exactly two preflights are pruned"
else
    no "exactly two preflights are pruned"
fi
expect "a pruned path is reported" "$OUT" "pruned /root/hohenheim-preflight-20261006-f"

report
