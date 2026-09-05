#!/usr/bin/env bash
#
# deploy-host.sh -- ONE invocation performs the whole Hohenheim deploy lane for one box.
#
# The lane used to be hand-typed shell, three near-identical transcriptions per
# wave, and every transcription was a chance to lose a step: a `stat` under
# `set -e` once aborted a wave between the `mv` and the `systemctl start`, and
# the health probes carried hardcoded try counts that outlived their boxes.
# This script IS that procedure now (docs/deploy-starfleet.md, "Deploy
# procedure"); tools/install-host.sh is its install-time sibling.
#
# Its main gates are:
#   1. a local jar whose build stamp is DIRTY, unstamped or inconsistent,
#   2. a migration rehearsal that does not succeed against a byte copy,
#   3. a health probe that never turns green (it then STOPS and prints the
#      rollback commands; it never rolls back on its own).
# A failed swap restarts the installed jar before returning failure. This is
# service recovery, not an automatic jar or database rollback.
#
# The host, jar path and service name come from the SAME `deployments` config
# `zenit-dev deployed` reads: ~/.config/zenit-dev/config.json, key
# "deployments", overlaid by the project's own .zenit-dev.json.

set -euo pipefail

# --- defaults ---------------------------------------------------------------

TARGET=""
LOCAL_JAR=""
SERVICE_USER="hohenheim"
PREFIX=""
DB_PATH=""
HEALTH_URL="http://127.0.0.1:3000/api/health"
HEALTH_TIMEOUT="120"
PREFLIGHT_DIR=""
REHEARSE_DIR=""
JAVA_BIN="java"
STAMP="$(date -u +%Y%m%d-%H%M%S)"
DRY_RUN="no"
MODE="deploy"
ZENIT_DEV="${ZENIT_DEV_BIN:-zenit-dev}"
CONFIG_PATH="${ZENIT_DEV_CONFIG:-$HOME/.config/zenit-dev/config.json}"
REPO_CONFIG="$(cd "$(dirname "$0")/.." && pwd)/.zenit-dev.json"

SSH_TARGET=""
REMOTE_JAR=""
SERVICE=""
SUDO=""
RUN_AS=""

JAR_SHA=""
JAR_SIZE=""
MIGRATIONS_BEFORE=""
MIGRATIONS_AFTER=""
ROLLBACK_JAR=""

# --- output helpers ---------------------------------------------------------

step() { printf '\n== %s\n' "$*"; }
info() { printf '   %s\n' "$*"; }
warn() { printf '   WARNING: %s\n' "$*"; }
fail() { printf 'REFUSED: %s\n' "$*" >&2; exit 1; }

usage() {
    cat <<'USAGE'
Usage: deploy-host.sh <target> <local-jar> [options]
       deploy-host.sh --rollback <target> --preflight <dir> [options]

<target> is a name in the zenit-dev `deployments` config (starfleet, kuifje,
robbedoes, ...); it supplies the ssh destination, the jar path on the host and
the systemd unit. <local-jar> is the freshly built fat jar.

Options:
  --user <name>          service user on the host (default hohenheim)
  --prefix <dir>         install root (default: the jar's own directory)
  --db <path>            sqlite control plane (default <prefix>/hohenheim.db)
  --health-url <url>     probed after every restart
                         (default http://127.0.0.1:3000/api/health)
  --health-timeout <s>   give up after this many seconds (default 120, polled
                         every 2s); on give-up the lane STOPS and prints the
                         rollback commands -- it never rolls back by itself
  --preflight <dir>      preflight directory on the host
                         (default /root/hohenheim-preflight-<stamp>)
  --java <path>          local java used for the stamp check (default java)
  --dry-run              print the plan, execute nothing
  --rollback             swap <preflight>/rollback.jar back in and restart
  --help                 this text

There is deliberately NO flag to skip the second restart: a jar that survives
one restart and not the next is the failure this lane exists to catch.
USAGE
}

# --- argument parsing -------------------------------------------------------

positional=()
while [ $# -gt 0 ]; do
    case "$1" in
        --user) SERVICE_USER="${2:-}"; shift 2 ;;
        --prefix) PREFIX="${2:-}"; shift 2 ;;
        --db) DB_PATH="${2:-}"; shift 2 ;;
        --health-url) HEALTH_URL="${2:-}"; shift 2 ;;
        --health-timeout) HEALTH_TIMEOUT="${2:-}"; shift 2 ;;
        --preflight) PREFLIGHT_DIR="${2:-}"; shift 2 ;;
        --java) JAVA_BIN="${2:-}"; shift 2 ;;
        --rollback) MODE="rollback"; shift ;;
        --dry-run) DRY_RUN="yes"; shift ;;
        --help|-h) usage; exit 0 ;;
        -*) fail "Unknown option: $1 (--help for usage)" ;;
        *) positional+=("$1"); shift ;;
    esac
done

TARGET="${positional[0]:-}"
LOCAL_JAR="${positional[1]:-}"

[ -n "$TARGET" ] || fail "name a deployment target (--help for usage)"
case "$HEALTH_TIMEOUT" in
    ''|*[!0-9]*) fail "--health-timeout takes whole seconds, got '$HEALTH_TIMEOUT'" ;;
esac

if [ "$MODE" = "deploy" ]; then
    [ -n "$LOCAL_JAR" ] || fail "name the local jar to deploy (--help for usage)"
    [ -f "$LOCAL_JAR" ] || fail "No such jar: $LOCAL_JAR"
else
    [ -n "$PREFLIGHT_DIR" ] || fail "--rollback needs --preflight <dir> (where rollback.jar lives)"
fi

# --- the deployments config -------------------------------------------------

# Reads one target out of the zenit-dev config, repo declaration overlaid on the
# global one -- the same merge lib/deployed.js performs, so both tools can never
# disagree about which host a name means.
read_target() {
    python3 - "$CONFIG_PATH" "$REPO_CONFIG" "$TARGET" <<'PY'
import json, sys

def deployments(path):
    try:
        with open(path) as handle:
            parsed = json.load(handle)
    except Exception:
        return {}
    got = parsed.get('deployments') if isinstance(parsed, dict) else None
    return got if isinstance(got, dict) else {}

merged = {}
merged.update(deployments(sys.argv[1]))
merged.update(deployments(sys.argv[2]))
name = sys.argv[3]
spec = merged.get(name)
if not isinstance(spec, dict):
    known = ', '.join(sorted(merged)) or '(none configured)'
    sys.exit('unknown deployment target "%s" (known: %s)' % (name, known))
ssh = spec.get('ssh')
jar = spec.get('jar')
service = spec.get('service') or ''
if not isinstance(ssh, str) or not ssh:
    sys.exit('deployment "%s" declares no ssh (user@host)' % name)
if not isinstance(jar, str) or not jar.startswith('/'):
    sys.exit('deployment "%s" must declare an absolute jar path on the host' % name)
print(ssh)
print(jar)
print(service)
PY
}

TARGET_FACTS="$(read_target 2>&1)" || fail "$TARGET_FACTS"
SSH_TARGET="$(printf '%s\n' "$TARGET_FACTS" | sed -n 1p)"
REMOTE_JAR="$(printf '%s\n' "$TARGET_FACTS" | sed -n 2p)"
SERVICE="$(printf '%s\n' "$TARGET_FACTS" | sed -n 3p)"
[ -n "$SERVICE" ] || fail "deployment \"$TARGET\" declares no systemd service; this lane restarts one"

[ -n "$PREFIX" ] || PREFIX="$(dirname "$REMOTE_JAR")"
[ -n "$DB_PATH" ] || DB_PATH="$PREFIX/hohenheim.db"
[ -n "$PREFLIGHT_DIR" ] || PREFLIGHT_DIR="/root/hohenheim-preflight-$STAMP"
[ -n "$REHEARSE_DIR" ] || REHEARSE_DIR="/opt/hohenheim-rehearse-$STAMP"
SETTINGS_DIR="$PREFIX/settings"
KEYRING="$SETTINGS_DIR/field-encryption.keys"
STAGED_JAR="hohenheim-deploy-$STAMP.jar"
ROLLBACK_JAR="$PREFLIGHT_DIR/rollback.jar"

# Starfleet is root@; kuifje and robbedoes are debian@ with NOPASSWD sudo. The
# whole lane goes through this one prefix, so a box's ssh identity is the only
# thing that decides it.
case "$SSH_TARGET" in
    root@*) SUDO="" ;;
    *) SUDO="sudo -n" ;;
esac
# Running a command AS the service user is a second, separate need: root has no
# sudo prefix but still cannot spell `-u` on its own (the first starfleet run died
# on exactly that), so root drops to the user through runuser.
if [ -z "$SUDO" ]; then
    RUN_AS="runuser -u '$SERVICE_USER' --"
else
    RUN_AS="$SUDO -u '$SERVICE_USER'"
fi

[ "$HEALTH_TIMEOUT" -gt 0 ] || fail "--health-timeout must be greater than zero"

# --- remote plumbing --------------------------------------------------------

# Runs a POSIX sh script on the host; the boxes' remote shell is sh, so nothing
# here may be a bashism.
remote() {
    if [ "$DRY_RUN" = "yes" ]; then
        # To stderr: most call sites capture stdout, and a plan nobody sees is
        # not a plan.
        printf '   PLAN: ssh %s <<sh\n%s\n   sh\n' "$SSH_TARGET" "$1" >&2
        return 0
    fi
    ssh -o BatchMode=yes -o ConnectTimeout=15 "$SSH_TARGET" "$1"
}

# The output of one @@-delimited section of a remote report.
section() {
    printf '%s\n' "$1" | awk -v want="@@$2" '
        $0 == want { grab = 1; next }
        /^@@/ { grab = 0 }
        grab { print }'
}

deployed_json() {
    if [ "$DRY_RUN" = "yes" ]; then
        printf '   PLAN: %s deployed %s --json\n' "$ZENIT_DEV" "$TARGET" >&2
        return 0
    fi
    "$ZENIT_DEV" deployed "$TARGET" --json
}

# Prints the per-repo verdicts of a `deployed --json` report and exits non-zero
# when it is not the finished state: hohenheim itself current, no restart pending,
# and no repo diverged/deployed-ahead/unknown/undiffable/inconsistent. An UPSTREAM
# repo reading local-ahead is a warning, not a refusal: the jar is built from the
# PUSHED heads in a clean secondary workspace on purpose, so another session's
# unpushed upstream commits are exactly what this lane keeps out of production.
judge_deployed() {
    python3 - "$1" <<'PY'
import json, sys
report = json.loads(sys.argv[1])
service = report.get('service') or {}
print('   verdict: %s   jar %s' % (report.get('verdict'), (report.get('jar') or {}).get('path')))
bad = []
ahead = []
for repo in report.get('repos') or []:
    verdict = repo.get('verdict')
    name = repo.get('repo')
    print('   %-22s %s %s' % (name, verdict, repo.get('shortSha') or ''))
    if verdict == 'current':
        continue
    if verdict == 'local-ahead' and name != 'hohenheim':
        ahead.append(name)
        continue
    bad.append('%s=%s' % (name, verdict))
if ahead:
    print('   WARNING: upstream local checkouts carry unpushed commits the shipped jar deliberately'
          ' lacks (built from the pushed heads): ' + ', '.join(ahead))
if service.get('restartPending'):
    bad.append('RESTART PENDING (the jar on disk is newer than the running process)')
for problem in report.get('problems') or []:
    print('   problem: %s' % problem)
    bad.append(problem)
if bad:
    print('NOT-FINISHED: ' + '; '.join(bad))
    sys.exit(1)
PY
}

printf 'Hohenheim deploy lane%s\n' "$([ "$DRY_RUN" = yes ] && printf ' (dry run)' || true)"
info "target=$TARGET ssh=$SSH_TARGET service=$SERVICE"
info "jar=$REMOTE_JAR prefix=$PREFIX db=$DB_PATH user=$SERVICE_USER"
info "preflight=$PREFLIGHT_DIR health=$HEALTH_URL (up to ${HEALTH_TIMEOUT}s)"
[ -n "$SUDO" ] && info "privilege: $SUDO (the ssh user is not root)" || info "privilege: none needed (root over ssh)"

probe_health() {
    remote "i=0
deadline=\$((\$(date +%s) + $HEALTH_TIMEOUT))
while :; do
  remaining=\$((deadline - \$(date +%s)))
  [ \$remaining -gt 0 ] || break
  request_timeout=2
  [ \$remaining -ge 2 ] || request_timeout=\$remaining
  code=\$(curl --connect-timeout 2 --max-time \"\$request_timeout\" -s -o /dev/null -w '%{http_code}' '$HEALTH_URL' 2>/dev/null || true)
  echo \"try \$i: \$code\"
  if [ \"\$code\" = 200 ]; then echo '@@healthy'; exit 0; fi
  i=\$((i+1))
  remaining=\$((deadline - \$(date +%s)))
  [ \$remaining -gt 0 ] || break
  pause=2
  [ \$remaining -ge 2 ] || pause=\$remaining
  sleep \$pause
done
echo '@@unhealthy'
$SUDO journalctl -u '$SERVICE' -n 40 --no-pager 2>&1 | tail -40"
}

# --- rollback mode ----------------------------------------------------------

if [ "$MODE" = "rollback" ]; then
    step "Rollback: $ROLLBACK_JAR -> $REMOTE_JAR"
    OUT="$(remote "set -e
$SUDO test -f '$ROLLBACK_JAR' || { echo '@@missing'; exit 3; }
$SUDO install -o '$SERVICE_USER' -g '$SERVICE_USER' -m 0644 '$ROLLBACK_JAR' '$PREFIX/hohenheim-server.jar.rollingback'
trap '$SUDO systemctl start \"$SERVICE\"' 0
$SUDO systemctl stop '$SERVICE'
$SUDO mv '$PREFIX/hohenheim-server.jar.rollingback' '$REMOTE_JAR'
set +e
echo '@@jar'
stat -c '%n %s %U:%G %a' '$REMOTE_JAR'
sha256sum '$REMOTE_JAR'
$SUDO systemctl start '$SERVICE'
start_rc=\$?
trap - 0
echo \"@@start \$start_rc\"")" || fail "the rollback swap failed on the host"
    if [ "$DRY_RUN" = "no" ]; then
        printf '%s\n' "$OUT" | sed 's/^/   /'
    fi
    step "Health probe after the rollback"
    if [ "$DRY_RUN" = "no" ]; then
        HEALTH="$(probe_health || true)"
        printf '%s\n' "$HEALTH" | sed 's/^/   /'
        if ! printf '%s\n' "$HEALTH" | /usr/bin/grep -qx '@@healthy'; then
            fail "rollback health never answered 200; inspect $SERVICE and restore the database deliberately if migrations require it"
        fi
    fi
    info "the database is NOT restored by this mode: a migration applied by the newer jar"
    info "makes the older one refuse to boot, so restore it deliberately:"
    info "  $SUDO systemctl stop $SERVICE"
    info "  $SUDO cp $PREFLIGHT_DIR/hohenheim.db.at-swap $DB_PATH"
    info "  $SUDO rm -f $DB_PATH-wal $DB_PATH-shm && $SUDO systemctl start $SERVICE"
    exit 0
fi

# --- 1. the local jar's build stamp -----------------------------------------

step "1. Local build stamp ($LOCAL_JAR)"
JAR_SHA="$(sha256sum "$LOCAL_JAR" | cut -d' ' -f1)"
JAR_SIZE="$(stat -c %s "$LOCAL_JAR")"
info "sha256 $JAR_SHA"
info "size   $JAR_SIZE bytes"

BUILD_INFO="$("$JAVA_BIN" -jar "$LOCAL_JAR" --build-info 2>&1)" \
    || fail "java -jar $LOCAL_JAR --build-info failed:
$BUILD_INFO"
printf '%s\n' "$BUILD_INFO" | sed 's/^/   /'

if printf '%s\n' "$BUILD_INFO" | /usr/bin/grep -q '^unstamped:'; then
    fail "the jar carries no build stamp; a build nobody can identify is never deployed"
fi
if printf '%s\n' "$BUILD_INFO" | /usr/bin/grep -q 'INCONSISTENT'; then
    fail "the jar mixes builds of one repo; rebuild from a single clean worktree"
fi
if printf '%s\n' "$BUILD_INFO" | /usr/bin/grep -q ' DIRTY'; then
    fail "the jar carries a DIRTY stamp: it was built from an uncommitted worktree, so its sha describes nothing. Commit, rebuild, retry."
fi
CLEAN_ROWS="$(printf '%s\n' "$BUILD_INFO" | /usr/bin/grep -c ' clean ' || true)"
[ "$CLEAN_ROWS" -gt 0 ] || fail "no clean stamp rows found in --build-info output"
info "$CLEAN_ROWS repos stamped clean"

# --- 2. what runs there now -------------------------------------------------

step "2. What runs on $TARGET right now"
BEFORE_JSON="$(deployed_json)" || warn "zenit-dev deployed could not answer; recording that and continuing"
if [ "$DRY_RUN" = "no" ] && [ -n "$BEFORE_JSON" ]; then
    judge_deployed "$BEFORE_JSON" || info "(that is the state BEFORE the deploy; local-ahead is what a pending deploy looks like)"
fi

# --- 3. upload ---------------------------------------------------------------

step "3. Upload the jar and verify its sha256 on the host"
if [ "$DRY_RUN" = "yes" ]; then
    info "PLAN: scp $LOCAL_JAR $SSH_TARGET:$STAGED_JAR"
else
    scp -q -o BatchMode=yes "$LOCAL_JAR" "$SSH_TARGET:$STAGED_JAR" || fail "upload failed"
fi
UPLOAD="$(remote "sha256sum '$STAGED_JAR' | cut -d' ' -f1")"
if [ "$DRY_RUN" = "no" ]; then
    REMOTE_SHA="$(printf '%s\n' "$UPLOAD" | tr -d '[:space:]')"
    [ "$REMOTE_SHA" = "$JAR_SHA" ] \
        || fail "the uploaded jar hashes $REMOTE_SHA, expected $JAR_SHA -- the transfer is corrupt"
    info "sha256 matches on the host"
fi

# --- 4. backups into the preflight directory --------------------------------

step "4. Preflight backups into $PREFLIGHT_DIR"
BACKUP="$(remote "set -e
$SUDO mkdir -p '$PREFLIGHT_DIR'
$SUDO chmod 0700 '$PREFLIGHT_DIR'
$SUDO sqlite3 '$DB_PATH' \".backup '$PREFLIGHT_DIR/hohenheim.db.pre'\"
echo '@@integrity'
$SUDO sqlite3 '$PREFLIGHT_DIR/hohenheim.db.pre' 'PRAGMA integrity_check;'
echo '@@migrations'
$SUDO sqlite3 '$PREFLIGHT_DIR/hohenheim.db.pre' \"select version || ' ' || name from zenit_migrations order by version;\"
echo '@@settings'
$SUDO rm -rf '$PREFLIGHT_DIR/settings'
$SUDO cp -a '$SETTINGS_DIR' '$PREFLIGHT_DIR/settings'
settings_files=\$($SUDO find '$PREFLIGHT_DIR/settings' -type f -printf '.\\n')
printf '%s\\n' \"\$settings_files\" | /usr/bin/grep -c '^\\.$' || true
echo '@@keyring'
if $SUDO test -f '$KEYRING'; then
  a=\$($SUDO sha256sum '$KEYRING' | cut -d' ' -f1)
  b=\$($SUDO sha256sum '$PREFLIGHT_DIR/settings/field-encryption.keys' | cut -d' ' -f1)
  if [ \"\$a\" = \"\$b\" ]; then echo \"match \$a\"; else echo \"MISMATCH \$a \$b\"; fi
else
  echo 'absent'
fi
echo '@@rollback'
$SUDO cp -p '$REMOTE_JAR' '$ROLLBACK_JAR'
$SUDO sha256sum '$ROLLBACK_JAR' | cut -d' ' -f1
echo '@@end'")" || fail "the preflight backup failed on the host; nothing has been swapped"

if [ "$DRY_RUN" = "no" ]; then
    INTEGRITY="$(section "$BACKUP" integrity | tr -d '[:space:]')"
    [ "$INTEGRITY" = "ok" ] || fail "PRAGMA integrity_check on the backup says '$INTEGRITY', not ok"
    info "database backed up, integrity ok"
    MIGRATIONS_BEFORE="$(section "$BACKUP" migrations)"
    info "$(printf '%s\n' "$MIGRATIONS_BEFORE" | /usr/bin/grep -c . || true) migrations applied before the swap"
    info "settings: $(section "$BACKUP" settings | tr -d '[:space:]') files copied"
    KEYRING_STATE="$(section "$BACKUP" keyring)"
    case "$KEYRING_STATE" in
        match*) info "keyring sha256 matches the copy" ;;
        absent) warn "no keyring at $KEYRING; this install has no encrypted columns yet" ;;
        *) fail "the keyring copy does not match the original: $KEYRING_STATE" ;;
    esac
    info "rollback jar: $ROLLBACK_JAR ($(section "$BACKUP" rollback | tr -d '[:space:]'))"
fi

# --- 5. migration rehearsal on a byte copy ----------------------------------

step "5. Rehearse the migrations against a byte copy (never the live file)"
# The rehearsal dir lives under /opt, NOT under the preflight dir: the service
# user cannot traverse /root, and `Unable to access jarfile` there reads like a
# jar problem while it is a permission one.
REHEARSAL="$(remote "set -e
$SUDO install -d -o '$SERVICE_USER' -g '$SERVICE_USER' -m 0755 '$REHEARSE_DIR'
$SUDO install -o '$SERVICE_USER' -g '$SERVICE_USER' -m 0644 '$STAGED_JAR' '$REHEARSE_DIR/new.jar'
$SUDO sqlite3 '$DB_PATH' \".backup '$REHEARSE_DIR/rehearse.db'\"
$SUDO chown '$SERVICE_USER:$SERVICE_USER' '$REHEARSE_DIR/rehearse.db'
JAVA=\$($SUDO systemctl show '$SERVICE' -p ExecStart --value 2>/dev/null | sed -n 's/.*path=\\([^ ;]*\\).*/\\1/p')
[ -x \"\$JAVA\" ] || JAVA=\$(command -v java)
echo \"@@java \$JAVA\"
set +e
cd '$PREFIX' && $RUN_AS \"\$JAVA\" -jar '$REHEARSE_DIR/new.jar' --rehearse-migrations '$REHEARSE_DIR/rehearse.db' 2>&1
echo \"@@rehearse \$?\"")" || fail "the rehearsal could not run on the host"

if [ "$DRY_RUN" = "no" ]; then
    printf '%s\n' "$REHEARSAL" | /usr/bin/grep -v '^@@' | sed 's/^/   /'
    REHEARSE_EXIT="$(printf '%s\n' "$REHEARSAL" | sed -n 's/^@@rehearse //p' | tail -1)"
    [ "$REHEARSE_EXIT" = "0" ] \
        || fail "--rehearse-migrations exited $REHEARSE_EXIT against the copy; the live database was not touched and nothing was swapped"
    info "rehearsal green"
fi

# --- 6. the swap ------------------------------------------------------------

step "6. Swap the jar and restart $SERVICE"
# Arm restart recovery before stopping: a failed backup or move must not leave
# the installed jar stopped. After the move, inspection failures are reported
# without preventing the explicit start; health is judged by the caller.
SWAP="$(remote "set -e
$SUDO install -o '$SERVICE_USER' -g '$SERVICE_USER' -m 0644 '$REHEARSE_DIR/new.jar' '$PREFIX/hohenheim-server.jar.new'
trap '$SUDO systemctl start \"$SERVICE\"' 0
$SUDO systemctl stop '$SERVICE'
$SUDO sqlite3 '$DB_PATH' \".backup '$PREFLIGHT_DIR/hohenheim.db.at-swap'\"
$SUDO mv '$PREFIX/hohenheim-server.jar.new' '$REMOTE_JAR'
set +e
echo '@@stat'
stat -c '%s %U:%G %a' '$REMOTE_JAR'
echo '@@sha'
sha256sum '$REMOTE_JAR' | cut -d' ' -f1
echo '@@start'
$SUDO systemctl start '$SERVICE'
start_rc=\$?
trap - 0
echo \"rc=\$start_rc\"
echo '@@end'")" || fail "the swap failed on the host; check $SERVICE and $PREFLIGHT_DIR before retrying"

if [ "$DRY_RUN" = "no" ]; then
    info "in place: $(section "$SWAP" stat)"
    SWAPPED_SHA="$(section "$SWAP" sha | tr -d '[:space:]')"
    [ "$SWAPPED_SHA" = "$JAR_SHA" ] || warn "the jar in place hashes $SWAPPED_SHA, expected $JAR_SHA"
    START_RC="$(section "$SWAP" start | sed -n 's/^rc=//p')"
    [ "$START_RC" = "0" ] || warn "systemctl start exited $START_RC; the health probe decides"
fi

# --- 7. health probe --------------------------------------------------------

# Prints the manual rollback the operator now owns; this lane never rolls back
# on its own, because an automatic rollback of a migrated database is a second
# unattended write on a box that just proved it cannot boot.
print_rollback() {
    printf '\nROLLBACK (run these by hand, in this order):\n'
    printf '  ssh %s\n' "$SSH_TARGET"
    printf '  %s systemctl stop %s\n' "$SUDO" "$SERVICE"
    printf '  %s cp %s %s\n' "$SUDO" "$ROLLBACK_JAR" "$REMOTE_JAR"
    printf '  # only when this deploy APPLIED migrations (the older jar refuses an unknown version):\n'
    printf '  %s cp %s/hohenheim.db.at-swap %s\n' "$SUDO" "$PREFLIGHT_DIR" "$DB_PATH"
    printf '  %s rm -f %s-wal %s-shm\n' "$SUDO" "$DB_PATH" "$DB_PATH"
    printf '  %s systemctl start %s\n' "$SUDO" "$SERVICE"
    printf '  # or: tools/deploy-host.sh --rollback %s --preflight %s\n' "$TARGET" "$PREFLIGHT_DIR"
}

step "7. Health probe (up to ${HEALTH_TIMEOUT}s, every 2s)"
HEALTH="$(probe_health || true)"
if [ "$DRY_RUN" = "no" ]; then
    printf '%s\n' "$HEALTH" | sed 's/^/   /'
    if ! printf '%s\n' "$HEALTH" | /usr/bin/grep -qx '@@healthy'; then
        print_rollback
        fail "$HEALTH_URL never answered 200 within ${HEALTH_TIMEOUT}s; the journal lines are above and NOTHING was rolled back"
    fi
    info "healthy"
fi

# --- 8. what the boot migrated ----------------------------------------------

step "8. Migrations the boot applied"
# There is no --run-migrations step: the service migrates at boot. What this
# lane owes is proof of WHAT it applied, read out of the ledger either side.
# Read-only URI: the service is running again by now, and a read that opens the
# file read-write would leave a -wal behind under the service user's nose.
AFTER="$(remote "$SUDO sqlite3 \"file:$DB_PATH?mode=ro\" \"select version || ' ' || name from zenit_migrations order by version;\"")" \
    || fail "could not read zenit_migrations after the boot; migration outcome is unknown"
if [ "$DRY_RUN" = "no" ]; then
    MIGRATIONS_AFTER="$AFTER"
    BEFORE_COUNT="$(printf '%s\n' "$MIGRATIONS_BEFORE" | /usr/bin/grep -c . || true)"
    AFTER_COUNT="$(printf '%s\n' "$MIGRATIONS_AFTER" | /usr/bin/grep -c . || true)"
    info "zenit_migrations: $BEFORE_COUNT before, $AFTER_COUNT after"
    APPLIED="$(comm -13 <(printf '%s\n' "$MIGRATIONS_BEFORE" | sort) <(printf '%s\n' "$MIGRATIONS_AFTER" | sort) | /usr/bin/grep . || true)"
    if [ -n "$APPLIED" ]; then
        printf '%s\n' "$APPLIED" | sed 's/^/   applied: /'
        info "pin these: java -jar <new jar> --migration-checksums, paste the lines into"
        info "src/browserTest/resources/migration-pins.txt and raise MigrationIntegrityTest.DEPLOYED_THROUGH"
    else
        info "no migrations applied by this deploy"
    fi
fi

# --- 9. second restart ------------------------------------------------------

step "9. Second restart (mandatory) and second health probe"
remote "$SUDO systemctl restart '$SERVICE'" || fail "the second restart failed"
HEALTH2="$(probe_health || true)"
if [ "$DRY_RUN" = "no" ]; then
    printf '%s\n' "$HEALTH2" | sed 's/^/   /'
    if ! printf '%s\n' "$HEALTH2" | /usr/bin/grep -qx '@@healthy'; then
        print_rollback
        fail "the service did not come back after the SECOND restart; the journal lines are above"
    fi
    info "healthy after the second restart"
fi

# --- 10. the deploy is finished only when the stamp says so -----------------

step "10. zenit-dev deployed $TARGET must answer current"
AFTER_JSON="$(deployed_json)" || fail "zenit-dev deployed could not answer after the deploy"
if [ "$DRY_RUN" = "no" ]; then
    judge_deployed "$AFTER_JSON" || fail "the host does not report current for every repo (or a restart is pending); the deploy is not finished"
    info "current, no restart pending"
fi

# --- 11. cleanup and the runbook entry --------------------------------------

step "11. Clean up the staged copies"
remote "$SUDO rm -rf '$REHEARSE_DIR'; rm -f '$STAGED_JAR'" || warn "could not clean the staged jar / rehearsal dir"

step "Runbook entry (paste into docs/deploy-$TARGET.md)"
cat <<ENTRY
## Deploy $(date -u +%Y-%m-%d) ($TARGET): <commit>, <what shipped>

Swapped to hohenheim \`<commit>\` (jar sha256 \`$JAR_SHA\`, $JAR_SIZE bytes,
stamp $CLEAN_ROWS/$CLEAN_ROWS \`dirty=false\`). Deployed with
\`tools/deploy-host.sh $TARGET <jar>\`. Preflight \`$PREFLIGHT_DIR/\`
(\`hohenheim.db.pre\` integrity ok, \`hohenheim.db.at-swap\`, \`settings/\`,
keyring: ${KEYRING_STATE:-unknown}, rollback jar \`rollback.jar\`). Migrations rehearsed
against a byte copy through \`--rehearse-migrations\`, then applied at boot:
$(printf '%s' "${APPLIED:-none}" | sed 's/^/  /'). Healthy after both restarts;
\`zenit-dev deployed $TARGET\` = current.
ROLLBACK: \`tools/deploy-host.sh --rollback $TARGET --preflight $PREFLIGHT_DIR\`
(plus \`hohenheim.db.at-swap\` when migrations were applied).
ENTRY
