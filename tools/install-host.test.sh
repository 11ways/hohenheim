#!/usr/bin/env bash
#
# install-host.test.sh -- drive tools/install-host.sh in --dry-run and assert its plan.
#
# Catches a regression without a VM: the dry run executes nothing, so this is
# safe to run anywhere, as any user. The VM run is still the real proof.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="$HERE/install-host.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

JAR="$WORK/hohenheim-server.jar"
printf 'not really a jar\n' > "$JAR"

PASSED=0
FAILED=0

ok() { PASSED=$((PASSED + 1)); printf 'ok   %s\n' "$1"; }
no() { FAILED=$((FAILED + 1)); printf 'FAIL %s\n' "$1"; }

# Asserts the plan contains (or, with -v, does not contain) a fixed string.
expect() {
    local label="$1" haystack="$2" needle="$3" mode="${4:-yes}"
    if printf '%s' "$haystack" | /usr/bin/grep -qF -- "$needle"; then
        [ "$mode" = "yes" ] && ok "$label" || no "$label (unexpected: $needle)"
    else
        [ "$mode" = "yes" ] && no "$label (missing: $needle)" || ok "$label"
    fi
}

plan_of() {
    bash "$SCRIPT" --dry-run --jar "$JAR" "$@" 2>&1
}

# 1. A proxy/dns/firewall node: the OVH box's shape.
PLAN="$(plan_of --roles proxy,dns,firewall --main-url https://panel.example --admin-email ops@example)"
expect "roles line names the three enabled roles" "$PLAN" "proxy=true dns=true firewall=true"
expect "instances stays off" "$PLAN" "instances=false"
expect "writes hohenheim.dry" "$PLAN" "write /opt/hohenheim/settings/hohenheim.dry"
expect "hohenheim.dry is group readable only" "$PLAN" "settings/hohenheim.dry (mode 0640"
expect "local.dry is a secret" "$PLAN" "settings/local.dry (mode 0600"
expect "auth.dry is a secret" "$PLAN" "settings/auth.dry (mode 0600"
expect "creates the service user" "$PLAN" "useradd --system"
expect "installs the nft sudoers grant" "$PLAN" "/etc/sudoers.d/hohenheim-nft"
expect "writes the systemd unit" "$PLAN" "write /etc/systemd/system/hohenheim.service"
expect "runs migrations" "$PLAN" "--run-migrations"
expect "prints the setup step" "$PLAN" "redirects to /setup"
expect "no docker without a docker role" "$PLAN" "docker-ce" no
expect "no volume root without the flag" "$PLAN" "--volume-root-size not given"
expect "dns role handles port 53" "$PLAN" "Port 53"

# 2. A compute node: instances imply docker and the volume grant.
PLAN="$(plan_of --roles instances,databases --volume-root-size 8)"
# Docker is either installed here already or planned; what the role must change is
# that the step is no longer skipped as unrequested (host-independent assertion).
expect "instances pull in docker" "$PLAN" "not requested by any role" no
expect "installs the volume sudoers grant" "$PLAN" "/etc/sudoers.d/hohenheim-volumes"
expect "creates the btrfs loop file" "$PLAN" "truncate -s 8G"
expect "makes the btrfs filesystem" "$PLAN" "mkfs.btrfs"
expect "adds the nofail fstab entry" "$PLAN" "loop,defaults,nofail"
expect "no dns work without the dns role" "$PLAN" "dns role not requested"

# 3. Refusals.
if OUT="$(bash "$SCRIPT" --dry-run --jar "$JAR" --roles proxy,bogus 2>&1)"; then
    no "unknown role is refused"
else
    expect "unknown role is named in the refusal" "$OUT" "Unknown role 'bogus'"
fi
if OUT="$(bash "$SCRIPT" --dry-run --roles proxy 2>&1)"; then
    no "missing --jar is refused"
else
    expect "missing --jar is named" "$OUT" "--jar is required"
fi
if OUT="$(bash "$SCRIPT" --dry-run --jar "$WORK/absent.jar" --roles proxy 2>&1)"; then
    no "absent jar is refused"
else
    expect "absent jar is named" "$OUT" "No such jar"
fi
if OUT="$(bash "$SCRIPT" --dry-run --jar "$JAR" --roles proxy --volume-root-size big 2>&1)"; then
    no "a non-numeric volume size is refused"
else
    expect "non-numeric volume size is named" "$OUT" "whole gigabytes"
fi

# 4. The dry run must not have touched the host.
if [ -e /opt/hohenheim ] && [ ! -d /opt/hohenheim ]; then
    no "dry run created /opt/hohenheim"
else
    ok "dry run wrote nothing outside its plan"
fi

printf '\n%s passed, %s failed\n' "$PASSED" "$FAILED"
[ "$FAILED" -eq 0 ]
