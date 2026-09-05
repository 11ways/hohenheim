#!/usr/bin/env bash
#
# deploy-host.test.sh -- drive tools/deploy-host.sh against FAKE ssh/scp/java/zenit-dev.
#
# Every external command the lane uses is a shim in a temp dir on PATH, so the
# ordering of the steps and the three refusals (dirty stamp, failed rehearsal,
# health never green) are proven with no host, no jar and no network. A real
# deploy is still the real proof; this is the regression net around the script.

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
            "service": "hohenheim"
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
        printf 'Rehearsal complete: 1 applied against /opt/hohenheim-rehearse-x/rehearse.db; the live database was not touched.\n'
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
    *"rm -rf"*)
        log "cleanup"
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
if [ "$calls" = "1" ]; then verdict="local-ahead"; fi
if [ "${FAKE_DEPLOYED:-current}" = "stuck" ]; then verdict="deployed-ahead"; fi
if [ "${FAKE_DEPLOYED:-current}" = "upstream-ahead" ]; then upstream="local-ahead"; fi
cat <<JSON
{
  "target": "testbox",
  "host": "debian@testhost",
  "jar": {"path": "/opt/hohenheim/hohenheim-server.jar"},
  "service": {"name": "hohenheim", "restartPending": false},
  "stamped": true,
  "repos": [
    {"repo": "hohenheim", "verdict": "$verdict", "shortSha": "12345678"},
    {"repo": "zenit", "verdict": "$upstream", "shortSha": "9fd78ec7"}
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
export FAKE_JAR_SHA="$JAR_SHA"
export ZENIT_DEV_CONFIG="$CONFIG"

PASSED=0
FAILED=0
ok() { PASSED=$((PASSED + 1)); printf 'ok   %s\n' "$1"; }
no() { FAILED=$((FAILED + 1)); printf 'FAIL %s\n' "$1"; }

expect() {
    local label="$1" haystack="$2" needle="$3" mode="${4:-yes}"
    if printf '%s' "$haystack" | /usr/bin/grep -qF -- "$needle"; then
        [ "$mode" = "yes" ] && ok "$label" || no "$label (unexpected: $needle)"
    else
        [ "$mode" = "yes" ] && no "$label (missing: $needle)" || ok "$label"
    fi
}

reset_logs() { : > "$SSH_LOG"; : > "$DEPLOYED_CALLS"; : > "$REMOTE_SCRIPTS"; }

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

LOG="$(cat "$SSH_LOG")"
ORDER="$(/usr/bin/grep -v '^scp ' "$SSH_LOG" | tr '\n' ' ')"
expect "the jar is uploaded before anything is verified on the host" "$LOG" "scp "
expect "backup, rehearsal and swap happen in that order" "$ORDER" \
    "verify-upload backup rehearse swap health migrations-after restart2 health cleanup"
if [ "$(wc -l < "$DEPLOYED_CALLS")" = "2" ]; then
    ok "zenit-dev deployed is asked before and after"
else
    no "zenit-dev deployed is asked before and after (got $(wc -l < "$DEPLOYED_CALLS") calls)"
fi

# --- 2. refusal: a dirty stamp ----------------------------------------------

if OUT="$(FAKE_STAMP=dirty run_lane testbox "$JAR" 2>&1)"; then
    no "a DIRTY stamp is refused"
else
    expect "the dirty stamp refusal names it" "$OUT" "carries a DIRTY stamp"
    expect "nothing was uploaded after a dirty stamp" "$(cat "$SSH_LOG")" "scp " no
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
fi

# --- 5. refusal: the host does not report current ---------------------------

if OUT="$(FAKE_DEPLOYED=stuck run_lane testbox "$JAR" 2>&1)"; then
    no "a host that does not report current is refused"
else
    expect "the not-current refusal names the state" "$OUT" "does not report current for every repo"
    expect "it names the repo that is not current" "$OUT" "hohenheim=deployed-ahead"
fi

# An UPSTREAM repo whose local checkout is merely ahead of the shipped jar is the
# clean-workspace lane working as designed: a warning, never a refusal.
OUT="$(FAKE_DEPLOYED=upstream-ahead run_lane testbox "$JAR" 2>&1)" || no "an upstream local-ahead lane exits 0"
expect "an upstream local-ahead is reported as a warning" "$OUT" "WARNING: upstream local checkouts carry unpushed commits"
expect "and names the repo" "$OUT" "pushed heads): zenit"

# --- 6. argument refusals ---------------------------------------------------

if OUT="$(run_lane nosuchbox "$JAR" 2>&1)"; then
    no "an unknown target is refused"
else
    expect "the unknown target lists the known ones" "$OUT" "unknown deployment target"
    expect "the known root target is named" "$OUT" "rootbox"
    expect "the known sudo target is named" "$OUT" "testbox"
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

printf '\n%s passed, %s failed\n' "$PASSED" "$FAILED"
[ "$FAILED" -eq 0 ]
