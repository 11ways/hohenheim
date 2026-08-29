#!/usr/bin/env bash
#
# install-host.sh -- turn a fresh Debian host into a Hohenheim node, idempotently.
#
# Every step checks its own precondition and skips when it is already satisfied,
# so re-running the script on a live host is a no-op that prints what it found.
# Nothing here prompts: apt runs with DEBIAN_FRONTEND=noninteractive.
#
# The procedure it automates is the one docs/deploy-native.md and
# docs/deploy-starfleet.md describe; this script IS that procedure now.

set -euo pipefail

# --- defaults ---------------------------------------------------------------

PREFIX="/opt/hohenheim"
SERVICE_USER="hohenheim"
SERVICE_NAME="hohenheim"
PANEL_PORT="3000"
JAVA_MAJOR="25"

JAR_PATH=""
ROLES_RAW=""
MAIN_URL=""
ADMIN_EMAIL=""
VOLUME_ROOT_GB=""
WITH_DOCKER="no"
DRY_RUN="no"

ROLE_NAMES="proxy dns firewall stacks databases instances"
role_proxy="false"; role_dns="false"; role_firewall="false"
role_stacks="false"; role_databases="false"; role_instances="false"

CHANGED_UNIT="no"
CHANGED_JAR="no"

# --- output helpers ---------------------------------------------------------

step() { printf '\n== %s\n' "$*"; }
info() { printf '   %s\n' "$*"; }
skip() { printf '   skip: %s\n' "$*"; }
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# Runs a mutating command, or prints it when --dry-run is in effect.
run() {
    if [ "$DRY_RUN" = "yes" ]; then
        printf '   PLAN: %s\n' "$*"
        return 0
    fi
    "$@"
}

# Writes a file with the given mode/owner, or prints the plan; never clobbers silently.
write_file() {
    local path="$1" mode="$2" owner="$3" content="$4"
    if [ "$DRY_RUN" = "yes" ]; then
        printf '   PLAN: write %s (mode %s, owner %s, %s bytes)\n' \
            "$path" "$mode" "$owner" "${#content}"
        return 0
    fi
    printf '%s' "$content" > "$path"
    chmod "$mode" "$path"
    chown "$owner" "$path"
}

usage() {
    cat <<'USAGE'
Usage: install-host.sh --jar <path> --roles <list> [options]

Required:
  --jar <path>              the built hohenheim-server.jar to install
  --roles <a,b,c>           any of: proxy,dns,firewall,stacks,databases,instances

Options:
  --main-url <url>          public URL of this panel (network.main_url + auth.external_base_url)
  --admin-email <address>   Let's Encrypt registration address
  --with-docker             install Docker CE from Docker's apt repo (implied by
                            the instances/databases/stacks roles)
  --volume-root-size <GB>   create the btrfs loop-file volume root and its sudoers line
  --panel-port <port>       admin listener port (default 3000)
  --prefix <dir>            install root (default /opt/hohenheim)
  --dry-run                 print the plan, execute nothing
  --help                    this text

The first administrator is created through the panel's /setup page after the
service is up; this script prints that step and never writes to the database.
USAGE
}

# --- argument parsing -------------------------------------------------------

while [ $# -gt 0 ]; do
    case "$1" in
        --jar) JAR_PATH="${2:-}"; shift 2 ;;
        --roles) ROLES_RAW="${2:-}"; shift 2 ;;
        --main-url) MAIN_URL="${2:-}"; shift 2 ;;
        --admin-email) ADMIN_EMAIL="${2:-}"; shift 2 ;;
        --volume-root-size) VOLUME_ROOT_GB="${2:-}"; shift 2 ;;
        --panel-port) PANEL_PORT="${2:-}"; shift 2 ;;
        --prefix) PREFIX="${2:-}"; shift 2 ;;
        --with-docker) WITH_DOCKER="yes"; shift ;;
        --dry-run) DRY_RUN="yes"; shift ;;
        --help|-h) usage; exit 0 ;;
        *) fail "Unknown option: $1 (--help for usage)" ;;
    esac
done

[ -n "$JAR_PATH" ] || fail "--jar is required"
[ -f "$JAR_PATH" ] || fail "No such jar: $JAR_PATH"
[ -n "$ROLES_RAW" ] || fail "--roles is required (e.g. --roles proxy,dns,firewall)"

# An unknown role name is refused rather than ignored: a silently dropped role
# is an install that looks complete and runs half the product.
IFS=',' read -r -a requested_roles <<< "$ROLES_RAW"
for role in "${requested_roles[@]}"; do
    role="$(printf '%s' "$role" | tr -d '[:space:]')"
    [ -n "$role" ] || continue
    case " $ROLE_NAMES " in
        *" $role "*) ;;
        *) fail "Unknown role '$role'; known roles: $ROLE_NAMES" ;;
    esac
    eval "role_${role}=true"
done

if [ "$role_instances" = "true" ] || [ "$role_databases" = "true" ] || [ "$role_stacks" = "true" ]; then
    WITH_DOCKER="yes"
fi

if [ "$VOLUME_ROOT_GB" != "" ]; then
    case "$VOLUME_ROOT_GB" in
        ''|*[!0-9]*) fail "--volume-root-size takes whole gigabytes, got '$VOLUME_ROOT_GB'" ;;
    esac
fi

if [ "$DRY_RUN" = "no" ] && [ "$(id -u)" != "0" ]; then
    fail "This installer must run as root (or use --dry-run)"
fi

export DEBIAN_FRONTEND=noninteractive

SETTINGS_DIR="$PREFIX/settings"
JAR_TARGET="$PREFIX/hohenheim-server.jar"
UNIT_PATH="/etc/systemd/system/${SERVICE_NAME}.service"

printf 'Hohenheim host installer%s\n' "$([ "$DRY_RUN" = yes ] && printf ' (dry run)' || true)"
info "prefix=$PREFIX user=$SERVICE_USER panel port=$PANEL_PORT"
info "roles: proxy=$role_proxy dns=$role_dns firewall=$role_firewall stacks=$role_stacks databases=$role_databases instances=$role_instances"

# --- 1. host preflight ------------------------------------------------------

step "Host preflight"
if [ -r /etc/os-release ]; then
    . /etc/os-release
    info "os: ${PRETTY_NAME:-unknown}"
    CODENAME="${VERSION_CODENAME:-bookworm}"
    if [ "${ID:-}" != "debian" ]; then
        info "WARNING: this installer targets Debian; '${ID:-unknown}' is untested"
    fi
else
    CODENAME="bookworm"
    info "WARNING: no /etc/os-release; assuming Debian $CODENAME"
fi
command -v systemctl >/dev/null 2>&1 || info "WARNING: no systemctl found; the unit will not start"

# Reports the codename an apt repository actually publishes, falling back to
# bookworm when a fresh Debian release has no suite there yet.
repo_codename() {
    local base="$1" want="$2"
    if curl -fsI "$base/dists/$want/Release" >/dev/null 2>&1; then
        printf '%s' "$want"
    else
        printf 'bookworm'
    fi
}

# --- 2. base packages -------------------------------------------------------

step "Base packages"
# Each entry is package:command -- the COMMAND decides, because a package name can
# be transitional (dnsutils became bind9-dnsutils) and would reinstall on every run.
BASE_PACKAGES="curl:curl gnupg:gpg sqlite3:sqlite3 unzip:unzip nftables:nft sudo:sudo dnsutils:dig"
missing=""
for entry in $BASE_PACKAGES; do
    pkg="${entry%%:*}"
    cmd="${entry##*:}"
    command -v "$cmd" >/dev/null 2>&1 || missing="$missing $pkg"
done
dpkg -s ca-certificates >/dev/null 2>&1 || missing="$missing ca-certificates"
if [ -n "$missing" ]; then
    info "installing:$missing"
    run apt-get update -qq
    run apt-get install -y -qq $missing
else
    skip "all base packages present"
fi

# --- 3. Java runtime --------------------------------------------------------

step "Java $JAVA_MAJOR runtime"

# Prints the first java binary whose feature version is at least JAVA_MAJOR.
find_java() {
    local candidate version
    for candidate in "$(command -v java || true)" \
        /opt/java/current/bin/java \
        /usr/lib/jvm/temurin-"$JAVA_MAJOR"-jre-*/bin/java \
        /usr/lib/jvm/temurin-"$JAVA_MAJOR"-jdk-*/bin/java \
        /usr/lib/jvm/java-"$JAVA_MAJOR"-openjdk-*/bin/java; do
        [ -x "$candidate" ] || continue
        version="$("$candidate" -version 2>&1 | head -1 | sed -n 's/.*version "\([0-9]*\).*/\1/p')"
        [ -n "$version" ] || continue
        [ "$version" -ge "$JAVA_MAJOR" ] 2>/dev/null || continue
        printf '%s' "$candidate"
        return 0
    done
    return 1
}

JAVA_BIN="$(find_java || true)"
if [ -n "$JAVA_BIN" ]; then
    skip "java $JAVA_MAJOR+ already present at $JAVA_BIN"
else
    ADOPTIUM_BASE="https://packages.adoptium.net/artifactory/deb"
    ADOPTIUM_SUITE="$(repo_codename "$ADOPTIUM_BASE" "$CODENAME")"
    info "installing temurin-$JAVA_MAJOR-jre from Adoptium ($ADOPTIUM_SUITE)"
    run install -m 0755 -d /etc/apt/keyrings
    run bash -c "curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public \
        | gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg"
    run bash -c "printf 'deb [signed-by=/etc/apt/keyrings/adoptium.gpg] %s %s main\n' \
        '$ADOPTIUM_BASE' '$ADOPTIUM_SUITE' > /etc/apt/sources.list.d/adoptium.list"
    run apt-get update -qq
    run apt-get install -y -qq "temurin-$JAVA_MAJOR-jre"
    JAVA_BIN="$(find_java || true)"
    if [ -z "$JAVA_BIN" ]; then
        [ "$DRY_RUN" = "yes" ] || fail "temurin-$JAVA_MAJOR-jre installed but no java $JAVA_MAJOR+ found"
        JAVA_BIN="/usr/lib/jvm/temurin-$JAVA_MAJOR-jre-amd64/bin/java"
    fi
fi
info "java: $JAVA_BIN"

# --- 4. Docker --------------------------------------------------------------

step "Docker engine"
if [ "$WITH_DOCKER" != "yes" ]; then
    skip "not requested by any role"
elif command -v docker >/dev/null 2>&1; then
    skip "docker already installed"
else
    DOCKER_BASE="https://download.docker.com/linux/debian"
    DOCKER_SUITE="$(repo_codename "$DOCKER_BASE" "$CODENAME")"
    info "installing docker-ce from Docker's repo ($DOCKER_SUITE)"
    run install -m 0755 -d /etc/apt/keyrings
    run bash -c "curl -fsSL $DOCKER_BASE/gpg -o /etc/apt/keyrings/docker.asc && chmod a+r /etc/apt/keyrings/docker.asc"
    run bash -c "printf 'deb [arch=%s signed-by=/etc/apt/keyrings/docker.asc] %s %s stable\n' \
        \"\$(dpkg --print-architecture)\" '$DOCKER_BASE' '$DOCKER_SUITE' > /etc/apt/sources.list.d/docker.list"
    run apt-get update -qq
    run apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
    run systemctl enable --now docker
fi

# --- 5. service user --------------------------------------------------------

step "Service user"
if id "$SERVICE_USER" >/dev/null 2>&1; then
    skip "user $SERVICE_USER exists"
else
    run useradd --system --create-home --home-dir "$PREFIX" --shell /usr/sbin/nologin "$SERVICE_USER"
fi
if [ "$WITH_DOCKER" = "yes" ] && getent group docker >/dev/null 2>&1; then
    if id -nG "$SERVICE_USER" 2>/dev/null | tr ' ' '\n' | grep -qx docker; then
        skip "$SERVICE_USER already in the docker group"
    else
        run usermod -aG docker "$SERVICE_USER"
    fi
fi

# --- 6. directory layout ----------------------------------------------------

step "Directory layout"
for dir in "$PREFIX" "$PREFIX/data" "$PREFIX/public" "$PREFIX/logs" "$PREFIX/tmp" "$SETTINGS_DIR"; do
    if [ -d "$dir" ]; then
        skip "$dir exists"
    else
        run install -d -o "$SERVICE_USER" -g "$SERVICE_USER" -m 0750 "$dir"
    fi
done
run chmod 0711 "$PREFIX"
run chmod 0700 "$SETTINGS_DIR"
if [ -d /var/log/hohenheim ]; then
    skip "/var/log/hohenheim exists"
else
    run install -d -o "$SERVICE_USER" -g "$SERVICE_USER" -m 0750 /var/log/hohenheim
fi

# --- 7. sudoers grants ------------------------------------------------------

step "Sudoers grants"
NFT_BIN="$(command -v nft || printf '/usr/sbin/nft')"
write_sudoers() {
    local name="$1" body="$2"
    local path="/etc/sudoers.d/$name"
    if [ -f "$path" ] && [ "$(cat "$path" 2>/dev/null)" = "$(printf '%s' "$body")" ]; then
        skip "$path up to date"
        return 0
    fi
    write_file "$path" 0440 root:root "$body"
    run visudo -cf "$path"
}
if [ "$role_firewall" = "true" ] || [ "$role_proxy" = "true" ]; then
    write_sudoers hohenheim-nft "$SERVICE_USER ALL=(root) NOPASSWD: $NFT_BIN
"
else
    skip "no nft grant needed for these roles"
fi
if [ -n "$VOLUME_ROOT_GB" ] || [ "$role_instances" = "true" ]; then
    write_sudoers hohenheim-volumes "$SERVICE_USER ALL=(root) NOPASSWD: /usr/bin/btrfs, /usr/bin/chown, /usr/bin/chmod, /usr/bin/mkdir, /usr/bin/rm
"
else
    skip "no volume grant needed for these roles"
fi

# --- 8. btrfs volume root ---------------------------------------------------

step "Volume root (btrfs)"
VOLUME_IMAGE="$PREFIX/volumes.btrfs"
VOLUME_MOUNT="$PREFIX/data/volumes"
if [ -z "$VOLUME_ROOT_GB" ]; then
    skip "--volume-root-size not given"
else
    if dpkg -s btrfs-progs >/dev/null 2>&1; then
        skip "btrfs-progs present"
    else
        run apt-get install -y -qq btrfs-progs
    fi
    if [ -f "$VOLUME_IMAGE" ]; then
        skip "$VOLUME_IMAGE exists"
    else
        run truncate -s "${VOLUME_ROOT_GB}G" "$VOLUME_IMAGE"
        run mkfs.btrfs -q "$VOLUME_IMAGE"
    fi
    if [ -d "$VOLUME_MOUNT" ]; then
        skip "$VOLUME_MOUNT exists"
    else
        run install -d -o "$SERVICE_USER" -g "$SERVICE_USER" -m 0750 "$VOLUME_MOUNT"
    fi
    if grep -qF "$VOLUME_IMAGE $VOLUME_MOUNT" /etc/fstab 2>/dev/null; then
        skip "fstab entry present"
    else
        run bash -c "printf '%s %s btrfs loop,defaults,nofail 0 0\n' '$VOLUME_IMAGE' '$VOLUME_MOUNT' >> /etc/fstab"
    fi
    if mountpoint -q "$VOLUME_MOUNT" 2>/dev/null; then
        skip "$VOLUME_MOUNT already mounted"
    else
        run mount "$VOLUME_MOUNT"
        run chown "$SERVICE_USER:$SERVICE_USER" "$VOLUME_MOUNT"
    fi
fi

# --- 9. settings files ------------------------------------------------------

step "Settings files"

# Creates a settings file once; an existing file is never rewritten, because the
# panel's settings editor persists into these same files.
seed_settings() {
    local path="$1" mode="$2" content="$3"
    if [ -f "$path" ]; then
        skip "$(basename "$path") exists (left untouched)"
        return 0
    fi
    write_file "$path" "$mode" "$SERVICE_USER:$SERVICE_USER" "$content"
}

LE_ENABLED="false"
[ -n "$ADMIN_EMAIL" ] && [ "$role_proxy" = "true" ] && LE_ENABLED="true"

seed_settings "$SETTINGS_DIR/hohenheim.dry" 0640 "{
    \"roles\": {
        \"proxy\": $role_proxy,
        \"dns\": $role_dns,
        \"firewall\": $role_firewall,
        \"stacks\": $role_stacks,
        \"databases\": $role_databases,
        \"instances\": $role_instances
    },
    \"proxy\": {
        \"http_port\": 80,
        \"https_port\": 443,
        \"force_https\": false
    },
    \"ssl\": {
        \"letsencrypt_enabled\": $LE_ENABLED,
        \"letsencrypt_email\": \"$ADMIN_EMAIL\",
        \"letsencrypt_staging\": false
    },
    \"dns\": {
        \"enabled\": $role_dns,
        \"bind_address\": \"0.0.0.0\",
        \"port\": 53,
        \"rate_limit_per_second\": 20
    },
    \"storage\": {
        \"data_path\": \"$PREFIX/data\"
    },
    \"database\": {
        \"path\": \"$PREFIX/hohenheim.db\",
        \"engine\": \"sqlite\",
        \"backup_path\": \"$PREFIX/data/backups\",
        \"backup_retention\": 7
    },
    \"logging\": {
        \"access_to_file\": true,
        \"access_path\": \"/var/log/hohenheim/access.log\"
    },
    \"security\": {
        \"bans_enabled\": $role_firewall,
        \"nftables_enabled\": $role_firewall,
        \"nftables_ports\": \"80,443\",
        \"auto_ban_ttl_hours\": 24
    }
}
"

seed_settings "$SETTINGS_DIR/local.dry" 0600 "{
    \"environment\": \"live\",
    \"network\": {
        \"port\": $PANEL_PORT,
        \"main_url\": \"$MAIN_URL\",
        \"trusted_proxies\": \"loopback\"
    },
    \"debugging\": {
        \"expose_error_details\": false
    },
    \"brand\": {
        \"name\": \"Hohenheim\"
    },
    \"activity\": {
        \"enabled\": true
    }
}
"

if [ -n "$MAIN_URL" ]; then
    seed_settings "$SETTINGS_DIR/auth.dry" 0600 "{
    \"external_base_url\": \"$MAIN_URL\"
}
"
else
    skip "auth.dry needs --main-url"
fi

# --- 10. port 53 ------------------------------------------------------------

step "Port 53"
if [ "$role_dns" != "true" ]; then
    skip "dns role not requested"
else
    RESOLVED_DROPIN="/etc/systemd/resolved.conf.d/hohenheim.conf"
    if systemctl is-active --quiet systemd-resolved 2>/dev/null; then
        if [ -f "$RESOLVED_DROPIN" ]; then
            skip "resolved stub listener already disabled"
        else
            info "switching off systemd-resolved's stub listener (it owns 127.0.0.53:53)"
            run install -d -m 0755 /etc/systemd/resolved.conf.d
            write_file "$RESOLVED_DROPIN" 0644 root:root "[Resolve]
DNSStubListener=no
"
            # The uplink file carries the real upstream servers; the stub file
            # would point at the listener that is being switched off.
            run ln -sf /run/systemd/resolve/resolv.conf /etc/resolv.conf
            run systemctl restart systemd-resolved
        fi
    else
        skip "systemd-resolved is not running"
    fi
    if [ "$DRY_RUN" = "no" ] && command -v ss >/dev/null 2>&1; then
        # Our own already-running listener is not a squatter; anything else is.
        own_pid="$(systemctl show -p MainPID --value "$SERVICE_NAME" 2>/dev/null || printf '0')"
        squatter="$(ss -lnup 'sport = :53' 2>/dev/null | tail -n +2 || true)"
        if [ "${own_pid:-0}" != "0" ]; then
            squatter="$(printf '%s' "$squatter" | grep -v "pid=$own_pid," || true)"
        fi
        if [ -n "$squatter" ]; then
            info "WARNING: something still listens on udp/53:"
            printf '   %s\n' "$squatter"
            info "the DNS listener will fail to bind until that process is stopped"
        else
            info "udp/53 is free"
        fi
    fi
fi

# --- 11. kernel limits ------------------------------------------------------

step "Kernel limits"
SYSCTL_FILE="/etc/sysctl.d/99-hohenheim.conf"
if [ -f "$SYSCTL_FILE" ]; then
    skip "$SYSCTL_FILE exists"
else
    # The 2026-08-04 starfleet incident: a low fs.file-max killed the HTTPS listener.
    write_file "$SYSCTL_FILE" 0644 root:root "fs.file-max = 200000
"
    run sysctl -q -p "$SYSCTL_FILE"
fi

# --- 12. the jar ------------------------------------------------------------

step "Application jar"
if [ -f "$JAR_TARGET" ] \
    && [ "$(sha256sum "$JAR_PATH" | cut -d' ' -f1)" = "$(sha256sum "$JAR_TARGET" | cut -d' ' -f1)" ]; then
    skip "$JAR_TARGET is already this build"
else
    run install -o "$SERVICE_USER" -g "$SERVICE_USER" -m 0644 "$JAR_PATH" "$JAR_TARGET"
    CHANGED_JAR="yes"
fi

# --- 13. systemd unit -------------------------------------------------------

step "systemd unit"
MEM_TOTAL_MB=1024
if [ -r /proc/meminfo ]; then
    MEM_TOTAL_MB=$(( $(awk '/^MemTotal:/ {print $2}' /proc/meminfo) / 1024 ))
fi
# Heap rule: 40% of MemTotal, rounded down to a 64 MB step, clamped to 512..2048.
# On starfleet's 1971 MB that is exactly the 768 MB the runbook pins by hand.
HEAP_MB=$(( MEM_TOTAL_MB * 40 / 100 ))
HEAP_MB=$(( HEAP_MB / 64 * 64 ))
[ "$HEAP_MB" -lt 512 ] && HEAP_MB=512
[ "$HEAP_MB" -gt 2048 ] && HEAP_MB=2048
info "MemTotal ${MEM_TOTAL_MB}MB -> -Xmx${HEAP_MB}m"

DOCKER_AFTER=""
DOCKER_GROUP=""
if [ "$WITH_DOCKER" = "yes" ]; then
    DOCKER_AFTER=" docker.service"
    DOCKER_GROUP="SupplementaryGroups=docker
"
fi

UNIT_BODY="[Unit]
Description=Hohenheim controller
After=network-online.target$DOCKER_AFTER
Wants=network-online.target

[Service]
Type=simple
User=$SERVICE_USER
Group=$SERVICE_USER
${DOCKER_GROUP}WorkingDirectory=$PREFIX
ExecStart=$JAVA_BIN -jar $JAR_TARGET
Restart=always
RestartSec=5
SuccessExitStatus=143
TimeoutStopSec=60
Environment=\"JAVA_TOOL_OPTIONS=-Xms128m -Xmx${HEAP_MB}m -XX:MaxMetaspaceSize=256m -XX:+UseSerialGC -Djava.io.tmpdir=$PREFIX/tmp\"
AmbientCapabilities=CAP_NET_BIND_SERVICE
LimitNOFILE=60000
NoNewPrivileges=false
ProtectSystem=full
PrivateTmp=true
KillMode=control-group
UMask=0027
StandardOutput=journal
StandardError=journal
SyslogIdentifier=hohenheim

[Install]
WantedBy=multi-user.target
"
if [ -f "$UNIT_PATH" ] && [ "$(cat "$UNIT_PATH" 2>/dev/null)" = "$(printf '%s' "$UNIT_BODY")" ]; then
    skip "$UNIT_PATH up to date"
else
    write_file "$UNIT_PATH" 0644 root:root "$UNIT_BODY"
    run systemctl daemon-reload
    CHANGED_UNIT="yes"
fi

# --- 14. migrations ---------------------------------------------------------

step "Database migrations"
DB_PATH="$PREFIX/hohenheim.db"
if [ ! -f "$DB_PATH" ] || [ "$CHANGED_JAR" = "yes" ] || [ "$DRY_RUN" = "yes" ]; then
    # Never migrate under a running service: stop it first, the deploy runbook's rule.
    if systemctl is-active --quiet "$SERVICE_NAME" 2>/dev/null; then
        info "stopping $SERVICE_NAME for the migration"
        run systemctl stop "$SERVICE_NAME"
    fi
    run bash -c "cd '$PREFIX' && sudo -u '$SERVICE_USER' '$JAVA_BIN' -jar '$JAR_TARGET' --run-migrations"
else
    skip "database exists and the jar did not change"
fi

# --- 15. service ------------------------------------------------------------

step "Service"
if systemctl is-enabled --quiet "$SERVICE_NAME" 2>/dev/null; then
    skip "$SERVICE_NAME enabled"
else
    run systemctl enable "$SERVICE_NAME"
fi
if ! systemctl is-active --quiet "$SERVICE_NAME" 2>/dev/null; then
    run systemctl start "$SERVICE_NAME"
elif [ "$CHANGED_JAR" = "yes" ] || [ "$CHANGED_UNIT" = "yes" ]; then
    info "jar or unit changed; restarting"
    run systemctl restart "$SERVICE_NAME"
else
    skip "$SERVICE_NAME already running this build"
fi

if [ "$DRY_RUN" = "no" ]; then
    info "waiting for the panel to answer on 127.0.0.1:$PANEL_PORT"
    healthy="no"
    for _ in $(seq 1 60); do
        if curl -fsS -o /dev/null "http://127.0.0.1:$PANEL_PORT/api/health" 2>/dev/null; then
            healthy="yes"
            break
        fi
        sleep 2
    done
    if [ "$healthy" = "yes" ]; then
        info "health: OK"
    else
        fail "the service did not answer /api/health; see: journalctl -u $SERVICE_NAME -n 80"
    fi
fi

# --- 16. what the operator still has to do ----------------------------------

step "Next steps"
cat <<NEXT
   1. Create the first administrator through the panel's own setup page:
        http://127.0.0.1:$PANEL_PORT/    (redirects to /setup while no user exists)
      Tunnel it if this host has no desktop:  ssh -L $PANEL_PORT:127.0.0.1:$PANEL_PORT root@<host>
      There is no offline command for the FIRST user; --set-password only resets
      an existing one:
        sudo -u $SERVICE_USER $JAVA_BIN -jar $JAR_TARGET --set-password --email <address>
   2. Verify the build that is installed:
        cd $PREFIX && sudo -u $SERVICE_USER $JAVA_BIN -jar $JAR_TARGET --build-info
   3. Roles that need more than this script:
      - registrar glue / NS delegation for any zone this host serves
      - the provider firewall panel (open 53 udp+tcp, 80, 443; keep $PANEL_PORT closed)
      - adding this host as a DNS peer on the existing primary
NEXT
