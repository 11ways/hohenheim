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
# The panel is published through the proxy as a site whose upstream is
# 127.0.0.1:3000, so the admin listener has no reason to answer the internet;
# the proxy's own 0.0.0.0:80/:443 listener is a separate Undertow instance.
PANEL_BIND="127.0.0.1"
JAVA_MAJOR="25"

JAR_PATH=""
ROLES_RAW=""
MAIN_URL=""
ADMIN_EMAIL=""
VOLUME_ROOT_GB=""
VOLUME_ROOT_DIR=""
SWAP_SIZE=""
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
# The content lands in a dot-named temp file beside the target, created 0600 by mktemp,
# and only a finished file with its final mode and owner is renamed into place: the file
# is never readable under root's umask and never half-written (a reader sees old or new).
write_file() {
    local path="$1" mode="$2" owner="$3" content="$4" tmp
    if [ "$DRY_RUN" = "yes" ]; then
        printf '   PLAN: write %s (mode %s, owner %s, %s bytes)\n' \
            "$path" "$mode" "$owner" "${#content}"
        return 0
    fi
    tmp="$(mktemp "$(dirname "$path")/.$(basename "$path").XXXXXX")"
    if ! { printf '%s' "$content" > "$tmp" && chmod "$mode" "$tmp" && chown "$owner" "$tmp" \
            && mv -f "$tmp" "$path"; }; then
        rm -f "$tmp"
        fail "could not write $path"
    fi
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
  --volume-root-size <GB>   create the btrfs loop-file volume root (at --volume-root)
  --volume-root <dir>       the volume root the privileged helper confines volume work to
                            (default <prefix>/data/volumes; must equal storage.volume_root
                            when that setting is set)
  --swap <size>             create a swapfile of this size (e.g. 2G) with vm.swappiness=10
  --panel-port <port>       admin listener port (default 3000)
  --panel-bind <addr>       admin listener address (default 127.0.0.1; the panel
                            is reached through the proxy, so binding it publicly
                            puts a login page on a raw port)
  --prefix <dir>            install root (default /opt/hohenheim)
  --dry-run                 print the plan, execute nothing
  --help                    this text

Root surface: the service user gets NO unrestricted root binary. nft (proxy and
firewall roles) is granted as-is; every volume and Spamservice ownership operation
goes through /usr/local/libexec/hohenheim/hohenheim-helper, which confines its
arguments to the volume root and the managed Spamservice directory, and is the only
file the hohenheim-helper sudoers line names. The firewall role also creates the
dedicated 'spamservice' account and the grant to launch the managed Spamservice as it.
A re-run replaces the pre-helper hohenheim-volumes grant with the narrow one.
Every sudoers file is validated with visudo -cf BEFORE it is installed.

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
        --volume-root) VOLUME_ROOT_DIR="${2:-}"; shift 2 ;;
        --swap) SWAP_SIZE="${2:-}"; shift 2 ;;
        --panel-port) PANEL_PORT="${2:-}"; shift 2 ;;
        --panel-bind) PANEL_BIND="${2:-}"; shift 2 ;;
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

if [ -n "$SWAP_SIZE" ]; then
    case "$SWAP_SIZE" in
        [1-9]*[MG]) ;;
        *) fail "--swap takes a size like 2G or 512M, got '$SWAP_SIZE'" ;;
    esac
fi

if [ "$DRY_RUN" = "no" ] && [ "$(id -u)" != "0" ]; then
    fail "This installer must run as root (or use --dry-run)"
fi

export DEBIAN_FRONTEND=noninteractive

SETTINGS_DIR="$PREFIX/settings"
VOLUME_ROOT_DIR="${VOLUME_ROOT_DIR:-$PREFIX/data/volumes}"
SPAMSERVICE_USER="spamservice"
SPAMSERVICE_ROOT="$PREFIX/data/managed-services/spamservice"
HELPER_DIR="/usr/local/libexec/hohenheim"
HELPER_PATH="$HELPER_DIR/hohenheim-helper"

# The helper bakes these roots in between single quotes; anything but a plain absolute
# path is refused rather than quoted, because the helper is root code.
for root_dir in "$VOLUME_ROOT_DIR" "$SPAMSERVICE_ROOT"; do
    case "$root_dir" in
        /*) ;;
        *) fail "'$root_dir' is not an absolute path" ;;
    esac
    case "$root_dir" in
        *[!A-Za-z0-9._/-]*|*//*|*/|*/./*|*/../*) fail "'$root_dir' must be a plain absolute path ([A-Za-z0-9._/-])" ;;
    esac
done
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
# The SSH brute-force watcher tails sshd's journal; without this group journalctl
# shows the service user nothing and security.ssh_watch_enabled bans nobody.
if [ "$role_firewall" = "true" ] && getent group systemd-journal >/dev/null 2>&1; then
    if id -nG "$SERVICE_USER" 2>/dev/null | tr ' ' '\n' | grep -qx systemd-journal; then
        skip "$SERVICE_USER already in the systemd-journal group"
    else
        run usermod -aG systemd-journal "$SERVICE_USER"
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

# --- 7. sudoers grants and the privileged helper ---------------------------

step "Sudoers grants"
NFT_BIN="$(command -v nft || printf '/usr/sbin/nft')"
# Installs one sudoers drop-in. The body is VALIDATED in a dot-named temp file (sudo
# ignores any name carrying a dot, so it is never live) and only a file visudo accepted
# is renamed into place, root-owned 0440: a syntax error can never break sudo host-wide.
write_sudoers() {
    local name="$1" body="$2"
    local path="/etc/sudoers.d/$name" tmp
    if [ -f "$path" ] && [ "$(cat "$path" 2>/dev/null)" = "$(printf '%s' "$body")" ]; then
        skip "$path up to date"
        return 0
    fi
    if [ "$DRY_RUN" = "yes" ]; then
        printf '   PLAN: validate with visudo -cf, then install %s (mode 0440, owner root:root):\n' "$path"
        printf '         %s' "$body"
        return 0
    fi
    tmp="$(mktemp "/etc/sudoers.d/.$name.XXXXXX")"
    if ! { printf '%s' "$body" > "$tmp" && chmod 0440 "$tmp" && chown root:root "$tmp"; }; then
        rm -f "$tmp"
        fail "could not stage $path"
    fi
    if ! visudo -cf "$tmp" >/dev/null; then
        rm -f "$tmp"
        fail "visudo refused the $name grant; nothing was installed"
    fi
    mv -f "$tmp" "$path"
    info "installed $path"
}

NEED_VOLUMES="no"
if [ -n "$VOLUME_ROOT_GB" ] || [ "$role_instances" = "true" ]; then
    NEED_VOLUMES="yes"
fi
NEED_SPAMSERVICE="no"
if [ "$role_firewall" = "true" ]; then
    # SpamserviceManager boots under the firewall role only.
    NEED_SPAMSERVICE="yes"
fi

if [ "$role_firewall" = "true" ] || [ "$role_proxy" = "true" ]; then
    write_sudoers hohenheim-nft "$SERVICE_USER ALL=(root) NOPASSWD: $NFT_BIN
"
else
    skip "no nft grant needed for these roles"
fi

# The helper script: THE root surface of the volume and Spamservice lanes (the Java side
# is be.elevenways.hohenheim.server.host.PrivilegedHelper; PrivilegedHelperDriftTest binds
# the path, the verbs and the uid floor below to it).
HELPER_BODY="$(cat <<'HELPER'
#!/bin/bash
# hohenheim-helper -- installed by tools/install-host.sh; do not edit, re-run the installer.
#
# The ONLY root surface the Hohenheim service user is granted for volume and Spamservice
# ownership work. Every verb confines its paths to its own root, refuses a symlink and any
# unsafe component, and works from a working directory it entered PHYSICALLY and verified
# with pwd -P, so an ancestor renamed after the check cannot redirect the operation.
set -euo pipefail
shopt -s inherit_errexit
set -f
umask 022
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
export LC_ALL=C

BELOW=""
VOLUME_ROOT='@VOLUME_ROOT@'
SPAMSERVICE_ROOT='@SPAMSERVICE_ROOT@'
SPAMSERVICE_USER='@SPAMSERVICE_USER@'
MIN_OWNER_UID=100000
MAX_OWNER_UID=4294967294

refuse() {
    printf 'hohenheim-helper: refused: %s\n' "$*" >&2
    exit 77
}

usage() {
    printf 'usage: hohenheim-helper volume-create|volume-usage|volume-destroy <path>\n' >&2
    printf '       hohenheim-helper volume-quota <path> <bytes|none>\n' >&2
    printf '       hohenheim-helper volume-snapshot <path> <target>\n' >&2
    printf '       hohenheim-helper volume-own <path> <uid>\n' >&2
    printf '       hohenheim-helper spamservice-own <path>\n' >&2
    exit 64
}

# Sets BELOW to PATH relative to ROOT when it is ROOT/<c1>/.../<cN>, MIN <= N <= MAX,
# every component plain (not empty, not . or .., no leading dash, no newline).
# A global, never a command substitution: a refusal inside $(...) would end only the
# subshell, and the verb would carry on with an empty path.
below() {
    local root="$1" path="$2" min="$3" max="$4" rel part count=0
    case "$path" in
        "$root"/*) ;;
        *) refuse "'$path' is not below $root" ;;
    esac
    rel="${path#"$root"/}"
    case "$rel" in
        ''|/*|*/|*//*) refuse "'$path' is not a plain path" ;;
    esac
    case "$rel" in
        *$'\n'*) refuse "a path may not carry a newline" ;;
    esac
    local IFS=/
    for part in $rel; do
        count=$((count + 1))
        case "$part" in
            .|..|-*) refuse "'$path' has an unsafe component '$part'" ;;
        esac
    done
    if [ "$count" -lt "$min" ] || [ "$count" -gt "$max" ]; then
        refuse "'$path' is not $min..$max levels below $root"
    fi
    BELOW="$rel"
}

# cd, physically, into ROOT/REL (REL may be empty), refusing a symlink on the way.
enter() {
    local root="$1" rel="$2" real part
    [ -d "$root" ] || refuse "$root is not a directory"
    cd -P -- "$root" || refuse "cannot enter $root"
    real="$(pwd -P)"
    [ -n "$rel" ] || return 0
    local IFS=/
    for part in $rel; do
        [ ! -L "$part" ] || refuse "'$part' is a symlink"
        [ -d "$part" ] || refuse "'$part' is not a directory"
        cd -P -- "$part" || refuse "cannot enter '$part'"
        real="$real/$part"
        [ "$(pwd -P)" = "$real" ] || refuse "'$part' moved while it was being entered"
    done
}

# Creates ROOT/NAME as a directory unless it is one already; never follows a symlink.
ensure_dir() {
    local name="$1"
    [ ! -L "$name" ] || refuse "'$name' is a symlink"
    [ -d "$name" ] || mkdir -- "./$name"
}

# Sets BELOW to a volume: <instance>/<name> below the volume root, never the snapshot tree.
volume() {
    below "$VOLUME_ROOT" "$1" 2 2
    case "$BELOW" in
        .snapshots/*) refuse "'$1' is in the snapshot tree" ;;
    esac
}

verb="${1:-}"
[ -n "$verb" ] || usage
shift

case "$verb" in
    volume-create)
        [ $# -eq 1 ] || usage
        volume "$1"
        rel="$BELOW"
        enter "$VOLUME_ROOT" ""
        ensure_dir "${rel%/*}"
        enter "$VOLUME_ROOT" "${rel%/*}"
        leaf="${rel##*/}"
        [ ! -L "$leaf" ] || refuse "'$leaf' is a symlink"
        btrfs subvolume show "./$leaf" >/dev/null 2>&1 || btrfs subvolume create "./$leaf"
        ;;
    volume-quota)
        [ $# -eq 2 ] || usage
        volume "$1"
        rel="$BELOW"
        case "$2" in
            none) ;;
            ''|*[!0-9]*) refuse "'$2' is not a byte count or none" ;;
        esac
        btrfs quota enable "$VOLUME_ROOT" >/dev/null 2>&1 || true
        enter "$VOLUME_ROOT" "${rel%/*}"
        leaf="${rel##*/}"
        [ ! -L "$leaf" ] || refuse "'$leaf' is a symlink"
        btrfs qgroup limit "$2" "./$leaf"
        ;;
    volume-usage)
        [ $# -eq 1 ] || usage
        volume "$1"
        rel="$BELOW"
        enter "$VOLUME_ROOT" "${rel%/*}"
        leaf="${rel##*/}"
        [ ! -L "$leaf" ] || refuse "'$leaf' is a symlink"
        btrfs qgroup show --raw -f "./$leaf"
        ;;
    volume-snapshot)
        [ $# -eq 2 ] || usage
        volume "$1"
        rel="$BELOW"
        below "$VOLUME_ROOT/.snapshots" "$2" 2 2
        target="$BELOW"
        enter "$VOLUME_ROOT" ""
        ensure_dir ".snapshots"
        enter "$VOLUME_ROOT" ".snapshots"
        ensure_dir "${target%/*}"
        enter "$VOLUME_ROOT" ".snapshots/${target%/*}"
        target_dir="$(pwd -P)"
        [ ! -e "${target##*/}" ] && [ ! -L "${target##*/}" ] || refuse "'$2' already exists"
        enter "$VOLUME_ROOT" "${rel%/*}"
        leaf="${rel##*/}"
        [ ! -L "$leaf" ] || refuse "'$leaf' is a symlink"
        btrfs subvolume snapshot -r "./$leaf" "$target_dir/${target##*/}"
        ;;
    volume-destroy)
        [ $# -eq 1 ] || usage
        case "$1" in
            "$VOLUME_ROOT/.snapshots"/*)
                below "$VOLUME_ROOT/.snapshots" "$1" 2 2
                rel=".snapshots/$BELOW"
                ;;
            *)
                volume "$1"
                rel="$BELOW"
                ;;
        esac
        # Nothing to destroy under an absent parent: the legacy rm -rf succeeded there too.
        [ -d "$VOLUME_ROOT/${rel%/*}" ] || exit 0
        enter "$VOLUME_ROOT" "${rel%/*}"
        leaf="${rel##*/}"
        if [ -L "$leaf" ]; then
            rm -f -- "./$leaf"
        elif btrfs subvolume show "./$leaf" >/dev/null 2>&1; then
            btrfs subvolume delete "./$leaf"
        else
            rm -rf -- "./$leaf"
        fi
        ;;
    volume-own)
        [ $# -eq 2 ] || usage
        volume "$1"
        rel="$BELOW"
        case "$2" in
            ''|*[!0-9]*) refuse "'$2' is not a numeric uid" ;;
        esac
        if [ "${#2}" -gt 10 ] || [ "$2" -lt "$MIN_OWNER_UID" ] || [ "$2" -gt "$MAX_OWNER_UID" ]; then
            refuse "uid $2 is outside $MIN_OWNER_UID..$MAX_OWNER_UID"
        fi
        enter "$VOLUME_ROOT" "$rel"
        chown "$2:$2" .
        chmod 0700 .
        ;;
    spamservice-own)
        [ $# -eq 1 ] || usage
        below "$SPAMSERVICE_ROOT" "$1" 1 2
        rel="$BELOW"
        case "$rel" in
            instance|instance/data|instance/settings|instance/tmp) ;;
            *) refuse "'$1' is not a managed Spamservice directory" ;;
        esac
        owner_uid="$(id -u "$SPAMSERVICE_USER")" || refuse "no '$SPAMSERVICE_USER' account"
        owner_gid="$(id -g "$SPAMSERVICE_USER")" || refuse "no '$SPAMSERVICE_USER' account"
        [ "$owner_uid" != "0" ] || refuse "'$SPAMSERVICE_USER' is root"
        enter "$SPAMSERVICE_ROOT" "$rel"
        chown -h "$owner_uid:$owner_gid" .
        ;;
    *)
        usage
        ;;
esac
HELPER
)"
HELPER_BODY="${HELPER_BODY//@VOLUME_ROOT@/$VOLUME_ROOT_DIR}"
HELPER_BODY="${HELPER_BODY//@SPAMSERVICE_ROOT@/$SPAMSERVICE_ROOT}"
HELPER_BODY="${HELPER_BODY//@SPAMSERVICE_USER@/$SPAMSERVICE_USER}
"

if [ "$NEED_VOLUMES" = "yes" ] || [ "$NEED_SPAMSERVICE" = "yes" ]; then
    # Grant first, helper second: the controller switches to the helper the moment the
    # file is executable, so the narrow grant must already be live by then.
    write_sudoers hohenheim-helper "$SERVICE_USER ALL=(root) NOPASSWD: $HELPER_PATH
"
    if [ -d "$HELPER_DIR" ]; then
        skip "$HELPER_DIR exists"
    else
        run install -d -o root -g root -m 0755 "$HELPER_DIR"
    fi
    if [ -f "$HELPER_PATH" ] && [ "$(cat "$HELPER_PATH" 2>/dev/null)" = "$(printf '%s' "$HELPER_BODY")" ]; then
        skip "$HELPER_PATH up to date"
    else
        write_file "$HELPER_PATH" 0755 root:root "$HELPER_BODY"
    fi
else
    skip "no privileged helper needed for these roles"
fi

# The pre-helper grant (btrfs, chown, chmod, mkdir, rm with ANY argument) was root for
# anyone who could run a command as the service user. It is removed on every run, after
# the helper above is in place.
if [ -f /etc/sudoers.d/hohenheim-volumes ]; then
    info "removing the unrestricted pre-helper grant /etc/sudoers.d/hohenheim-volumes"
    run rm -f /etc/sudoers.d/hohenheim-volumes
else
    skip "no pre-helper volume grant present"
fi

# The managed Spamservice runs as its own unprivileged account: SystemUsers launches it
# with sudo -n --preserve-env -u #uid -g #gid -- /usr/bin/prlimit ..., which needs this
# run-as grant WITH SETENV (--preserve-env hands over the explicit, secret-free env the
# controller built). The account is unprivileged, so the grant widens nothing beyond it.
if [ "$NEED_SPAMSERVICE" = "yes" ]; then
    if id "$SPAMSERVICE_USER" >/dev/null 2>&1; then
        skip "user $SPAMSERVICE_USER exists"
    else
        run useradd --system --user-group --no-create-home --home-dir "$SPAMSERVICE_ROOT/instance" \
            --shell /usr/sbin/nologin "$SPAMSERVICE_USER"
    fi
    write_sudoers hohenheim-spamservice "$SERVICE_USER ALL=($SPAMSERVICE_USER : $SPAMSERVICE_USER) NOPASSWD:SETENV: /usr/bin/prlimit
"
else
    skip "no spamservice account needed for these roles"
fi

# --- 8. btrfs volume root ---------------------------------------------------

step "Volume root (btrfs)"
VOLUME_IMAGE="$PREFIX/volumes.btrfs"
VOLUME_MOUNT="$VOLUME_ROOT_DIR"
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
    # The image holds every tenant volume; a 0644 image lets any local user read all of
    # them past the 0750 on the mounted subvolumes.
    run chmod 0600 "$VOLUME_IMAGE"
    if [ -d "$VOLUME_MOUNT" ]; then
        skip "$VOLUME_MOUNT exists"
    else
        run install -d -o root -g root -m 0755 "$VOLUME_MOUNT"
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
    fi
    # ROOT-owned, 0755: every volume directory below it is created and handed out by the
    # privileged helper, and a root owned by the service user would let that user rename
    # an entry away between the helper's check and its act. Nothing writes here as the
    # service user; docker and incus only need to traverse it.
    if [ "$DRY_RUN" = "no" ] && [ "$(stat -c '%U:%G %a' "$VOLUME_MOUNT" 2>/dev/null)" = "root:root 755" ]; then
        skip "$VOLUME_MOUNT is root-owned 0755"
    else
        run chown root:root "$VOLUME_MOUNT"
        run chmod 0755 "$VOLUME_MOUNT"
    fi
fi

# --- 9. settings files ------------------------------------------------------

step "Settings files"

info "panel listener: $PANEL_BIND:$PANEL_PORT (--panel-bind to change)"
if [ "$PANEL_BIND" != "127.0.0.1" ] && [ "$PANEL_BIND" != "::1" ] && [ "$PANEL_BIND" != "localhost" ]; then
    info "WARNING: the admin login page will answer on a public port"
fi
info "an existing settings file is never rewritten; edit it by hand to change a seeded value"

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

# A placeholder address does not merely look untidy: Let's Encrypt REFUSES an
# account contact whose domain has no valid public suffix ("contact email has
# invalid domain"), so every certificate request on the host fails, months after
# the install, with an error that names the contact rather than the install.
# Measured 2026-08-30 on kuifje, installed with hostmaster@panel.invalid: the
# first certificate request failed and the box had no HTTPS listener at all.
# Refuse here instead, where the operator can still supply a real address.
case "$ADMIN_EMAIL" in
    "") : ;;
    *@*.invalid|*@*.local|*@*.localhost|*@*.test|*@*.example|*@example.com|*@example.org|*@example.net)
        fail "--admin-email $ADMIN_EMAIL is a reserved/placeholder domain; Let's Encrypt refuses it as an account contact. Give a real address, or omit the flag to register with no contact." ;;
    *@*.*) : ;;
    *) fail "--admin-email $ADMIN_EMAIL is not an email address" ;;
esac

LE_ENABLED="false"
[ -n "$ADMIN_EMAIL" ] && [ "$role_proxy" = "true" ] && LE_ENABLED="true"

# Where the control-plane database is named. A FRESH install (neither settings file
# exists yet) names it the framework's way: zenit's database.url in local.dry. An
# existing host is never re-pointed: its hohenheim.dry carries the deprecated
# database.path the server still honours as its fallback, and seeding a database.url
# beside it would silently win over a path the operator may have changed. So a host
# that already has hohenheim.dry gets no database.url, even when local.dry is new.
if [ ! -f "$SETTINGS_DIR/hohenheim.dry" ] && [ ! -f "$SETTINGS_DIR/local.dry" ]; then
    info "fresh install: the control-plane database is database.url = jdbc:sqlite:$PREFIX/hohenheim.db in local.dry"
    LOCAL_DATABASE_BLOCK="
    \"database\": {
        \"url\": \"jdbc:sqlite:$PREFIX/hohenheim.db\"
    },"
else
    info "existing install: the database keeps the location its settings already name"
    LOCAL_DATABASE_BLOCK=""
fi

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
        \"nftables_ssh_ports\": \"22\",
        \"ssh_watch_enabled\": $role_firewall,
        \"auto_ban_ttl_hours\": 24
    }
}
"

seed_settings "$SETTINGS_DIR/local.dry" 0600 "{
    \"environment\": \"live\",$LOCAL_DATABASE_BLOCK
    \"network\": {
        \"port\": $PANEL_PORT,
        \"bind_address\": \"$PANEL_BIND\",
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

step "Swap"
SWAP_FILE="/swapfile"
if [ -z "$SWAP_SIZE" ]; then
    skip "--swap not given"
elif [ "$(awk 'NR>1 {print $3; exit}' /proc/swaps 2>/dev/null || true)" != "" ]; then
    skip "swap is already active"
elif [ -f "$SWAP_FILE" ]; then
    skip "$SWAP_FILE exists"
else
    info "creating a $SWAP_SIZE swapfile at $SWAP_FILE"
    run fallocate -l "$SWAP_SIZE" "$SWAP_FILE"
    run chmod 0600 "$SWAP_FILE"
    run mkswap -q "$SWAP_FILE"
    run swapon "$SWAP_FILE"
    if grep -qF "$SWAP_FILE none swap" /etc/fstab 2>/dev/null; then
        skip "fstab swap entry present"
    else
        run bash -c "printf '%s none swap sw 0 0\n' '$SWAP_FILE' >> /etc/fstab"
    fi
fi

step "Kernel limits"
SYSCTL_FILE="/etc/sysctl.d/99-hohenheim.conf"
# The 2026-08-04 starfleet incident: a low fs.file-max killed the HTTPS listener.
SYSCTL_BODY="fs.file-max = 200000
"
if [ -n "$SWAP_SIZE" ]; then
    # A server heap should page out reluctantly; swap is a safety net, not a tier.
    SYSCTL_BODY="${SYSCTL_BODY}vm.swappiness = 10
"
fi
if [ -f "$SYSCTL_FILE" ] && [ "$(cat "$SYSCTL_FILE" 2>/dev/null)" = "$(printf '%s' "$SYSCTL_BODY")" ]; then
    skip "$SYSCTL_FILE up to date"
else
    write_file "$SYSCTL_FILE" 0644 root:root "$SYSCTL_BODY"
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
# Declared on the unit as well as through usermod: systemd resolves supplementary
# groups itself, so the sshd-journal read survives a service user re-creation.
JOURNAL_GROUP=""
if [ "$role_firewall" = "true" ]; then
    JOURNAL_GROUP="SupplementaryGroups=systemd-journal
"
fi

# --sun-misc-unsafe-memory-access=allow: undertow frees its pooled direct buffers
# through sun.misc.Unsafe::invokeCleaner (DirectByteBufferDeallocator, every release
# through 2.4.x), and JDK 24+ prints a four-line warning the first time that runs --
# on a box with traffic, seconds after boot. Every other Unsafe caller was removed at
# the library (classgraph, jboss-threads); this one has no library fix, so the JVM's
# own switch is the honest answer, and it stays here rather than on the command line
# so an offline command run by hand is quiet too.
UNIT_BODY="[Unit]
Description=Hohenheim controller
After=network-online.target$DOCKER_AFTER
Wants=network-online.target

[Service]
Type=simple
User=$SERVICE_USER
Group=$SERVICE_USER
${DOCKER_GROUP}${JOURNAL_GROUP}WorkingDirectory=$PREFIX
ExecStart=$JAVA_BIN -jar $JAR_TARGET
Restart=always
RestartSec=5
SuccessExitStatus=143
TimeoutStopSec=60
Environment=\"JAVA_TOOL_OPTIONS=-Xms128m -Xmx${HEAP_MB}m -XX:MaxMetaspaceSize=256m -XX:+UseSerialGC --sun-misc-unsafe-memory-access=allow -Djava.io.tmpdir=$PREFIX/tmp\"
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
# The migration above runs under THIS shell's umask, not the unit's UMask=0027, so a
# first install leaves a 0664 database (certificate keys, TSIG secrets, sessions) that
# every local user can read; SQLite copies the main file's mode onto -wal and -shm.
for db_file in "$DB_PATH" "$DB_PATH-wal" "$DB_PATH-shm"; do
    if [ -f "$db_file" ]; then
        run chmod 0640 "$db_file"
    fi
done

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

# The address the panel answers on: the bound one, with a wildcard bind probed over the
# matching loopback and an IPv6 literal bracketed.
panel_probe_host() {
    case "$PANEL_BIND" in
        ''|0.0.0.0) printf '127.0.0.1' ;;
        ::|'[::]') printf '[::1]' ;;
        \[*) printf '%s' "$PANEL_BIND" ;;
        *:*) printf '[%s]' "$PANEL_BIND" ;;
        *) printf '%s' "$PANEL_BIND" ;;
    esac
}
PANEL_PROBE="http://$(panel_probe_host):$PANEL_PORT/api/health"
if [ "$DRY_RUN" = "no" ]; then
    info "waiting for the panel to answer on $PANEL_PROBE"
    healthy="no"
    for _ in $(seq 1 60); do
        if curl -fsS -o /dev/null "$PANEL_PROBE" 2>/dev/null; then
            healthy="yes"
            break
        fi
        sleep 2
    done
    if [ "$healthy" = "yes" ]; then
        info "health: OK"
    else
        fail "the service did not answer $PANEL_PROBE; see: journalctl -u $SERVICE_NAME -n 80"
    fi
else
    printf '   PLAN: poll %s for up to 120s\n' "$PANEL_PROBE"
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
