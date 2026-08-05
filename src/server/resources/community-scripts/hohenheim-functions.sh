#!/usr/bin/env bash
# hohenheim-functions.sh -- the function library hohenheim injects through
# $FUNCTIONS_FILE_PATH so unmodified community-scripts/ProxmoxVE install scripts
# run verbatim inside a Debian/Ubuntu system container (Incus tier).
#
# Derived from community-scripts/ProxmoxVE (MIT, Copyright (c) 2021-2026 tteck
# and the community-scripts ORG), pinned revision
# 27f66a801627aad274756519eb040934318a1db1. This is a REIMPLEMENTATION of the
# install-side helper vocabulary, not a copy of build.func: everything the
# upstream host side does (storage, whiptail, resources, networking) is owned
# by the hohenheim platform instead.
#
# AIDEV-NOTE: vocabulary versioning decision (Phase 5b). The upstream helper set
# is an undocumented internal contract that changes freely. This library DECLARES
# the helpers it implements on the single HOHENHEIM_FUNCS_VOCABULARY line below;
# the Java side (CommunityScripts) parses that line and statically refuses -- BY
# NAME -- any script that calls an upstream helper (upstream-vocabulary.txt at
# the pinned revision) missing from it, at import, approval AND install time.
# At runtime command_not_found_handle is the backstop for anything the static
# scan cannot see. Growing the vocabulary = implement the helper here, add its
# name to the line, bump HOHENHEIM_FUNCS_VOCABULARY_VERSION.
#
# AIDEV-NOTE: deliberate divergences from upstream, each because "vendor and pin"
# or "non-interactive" demands it:
#  - update_os does NOT live-fetch tools.func from main (upstream does); the
#    tools vocabulary ships IN this library, which is what makes pinning real.
#  - customize writes a /usr/bin/update that REFUSES with a pointer to the
#    platform's update action instead of curl-ing ct/<app>.sh from main.
#  - network_check never prompts (no /dev/tty): no connectivity = loud exit 122.
#  - setup_hwaccel refuses BY NAME: GPU passthrough edits the container config
#    on the HOST, which this library cannot honestly do from inside.

HOHENHEIM_FUNCS_VOCABULARY_VERSION="1"
HOHENHEIM_FUNCS_VOCABULARY="color color_spinner icons formatting verb_ip6 catch_errors error_handler set_std_mode silent is_alpine is_verbose_mode setting_up_container network_check update_os apt_update_safe msg_info msg_ok msg_error msg_warn msg_custom msg_debug header_info fatal exit_script arch_resolve ensure_dependencies curl_download fetch_and_deploy_gh_release check_for_gh_release setup_deb822_repo check_container_storage check_container_resources motd_ssh customize cleanup_lxc"

# -- defaults the host side would normally negotiate (non-interactive) ---------
APPLICATION="${APPLICATION:-${app:-application}}"
app="${app:-${APPLICATION,,}}"
APP="${APP:-$APPLICATION}"
VERBOSE="${VERBOSE:-no}"
RETRY_NUM="${RETRY_NUM:-10}"
RETRY_EVERY="${RETRY_EVERY:-3}"
PASSWORD="${PASSWORD:-}"
SSH_ROOT="${SSH_ROOT:-no}"
SSH_AUTHORIZED_KEY="${SSH_AUTHORIZED_KEY:-}"
export DEBIAN_FRONTEND=noninteractive
HH_INSTALL_LOG="/var/log/hohenheim-install.log"

# -- presentation (plain-text: output lands in the install record's tail) ------
color() {
  # ANSI stays empty on purpose: the captured output is read in the admin panel.
  CL="" RD="" GN="" YW="" BL="" BGN="" DGN="" BOLD=""
  CM="ok: " CROSS="error: " INFO="info: " NETWORK="network: "
  CREATING="" GATEWAY="" HOSTNAME="" OS="" TAB="  "
}
color_spinner() { :; }
icons() { :; }
formatting() { :; }

msg_info()   { echo "[info] $*"; }
msg_ok()     { echo "[ok] $*"; }
msg_error()  { echo "[error] $*" 1>&2; }
msg_warn()   { echo "[warn] $*" 1>&2; }
msg_custom() { shift 2 2>/dev/null || true; echo "[*] $*"; }
msg_debug()  { [ "$VERBOSE" = "yes" ] && echo "[debug] $*" || true; }
header_info() { echo "== ${1:-$APPLICATION} =="; }

fatal() {
  msg_error "$*"
  exit 1
}
exit_script() { exit 130; }

is_alpine() { [ -f /etc/alpine-release ]; }
is_verbose_mode() { [ "$VERBOSE" = "yes" ]; }

# -- error discipline ----------------------------------------------------------
error_handler() {
  local exit_code=$?
  local line="${BASH_LINENO[0]:-?}"
  local command="${BASH_COMMAND:-?}"
  if [ -s "$HH_INSTALL_LOG" ]; then
    echo "---- last install log lines ----" 1>&2
    tail -n 20 "$HH_INSTALL_LOG" 1>&2
  fi
  msg_error "script failed at line ${line} (exit ${exit_code}): ${command}"
  exit "$exit_code"
}

catch_errors() {
  set -Eeuo pipefail
  trap 'error_handler' ERR
}

# The runtime backstop of the vocabulary gate: an unknown command -- an upstream
# helper this library does not implement included -- is a LOUD, named failure.
command_not_found_handle() {
  echo "[error] command or helper '$1' is not available in this container." 1>&2
  echo "[error] If it is a community-scripts helper, the hohenheim function library" 1>&2
  echo "[error] (vocabulary v${HOHENHEIM_FUNCS_VOCABULARY_VERSION}) does not implement it; this template must not run." 1>&2
  exit 127
}

# -- $STD quiet-runner ---------------------------------------------------------
silent() {
  "$@" >>"$HH_INSTALL_LOG" 2>&1
}
set_std_mode() {
  if [ "$VERBOSE" = "yes" ]; then STD=""; else STD="silent"; fi
}
set_std_mode

# -- container bring-up --------------------------------------------------------
verb_ip6() {
  set_std_mode
  # IPV6_METHOD=disable is honoured like upstream; anything else is a no-op.
  if [ "${IPV6_METHOD:-}" = "disable" ]; then
    msg_info "Disabling IPv6"
    mkdir -p /etc/sysctl.d
    printf 'net.ipv6.conf.all.disable_ipv6 = 1\nnet.ipv6.conf.default.disable_ipv6 = 1\n' \
      >/etc/sysctl.d/99-disable-ipv6.conf
    $STD sysctl -p /etc/sysctl.d/99-disable-ipv6.conf
    msg_ok "Disabled IPv6"
  fi
}

setting_up_container() {
  msg_info "Setting up Container OS"
  local i
  for ((i = RETRY_NUM; i > 0; i--)); do
    if [ "$(hostname -I 2>/dev/null)" != "" ]; then break; fi
    sleep "$RETRY_EVERY"
  done
  if [ "$(hostname -I 2>/dev/null)" = "" ]; then
    msg_error "No network after $RETRY_NUM tries"
    exit 121
  fi
  rm -rf /usr/lib/python3.*/EXTERNALLY-MANAGED
  systemctl disable -q --now systemd-networkd-wait-online.service 2>/dev/null || true
  msg_ok "Set up Container OS"
  msg_ok "Network Connected: $(hostname -I)"
}

_hh_probe() {
  # One IPv4/IPv6 reachability probe against an ADDRESS LITERAL (a hostname would
  # silently succeed over the other family); /dev/tcp covers ping-less images.
  local address="$1"
  if command -v ping >/dev/null 2>&1; then
    ping -c 1 -W 2 "$address" >/dev/null 2>&1 && return 0
  fi
  (exec 3<>"/dev/tcp/$address/53") 2>/dev/null && return 0
  return 1
}

network_check() {
  # Non-interactive by doctrine: no /dev/tty prompt.
  set +e
  trap - ERR
  local ipv4_connected=false ipv6_connected=false
  if _hh_probe 1.1.1.1 || _hh_probe 8.8.8.8 || _hh_probe 9.9.9.9; then
    msg_ok "IPv4 Internet Connected"
    ipv4_connected=true
  else
    msg_error "IPv4 Internet Not Connected"
  fi
  if _hh_probe 2606:4700:4700::1111 || _hh_probe 2001:4860:4860::8888; then
    msg_ok "IPv6 Internet Connected"
    ipv6_connected=true
  else
    msg_warn "IPv6 Internet Not Connected"
  fi
  if [ "$ipv4_connected" = false ] && [ "$ipv6_connected" = false ]; then
    msg_error "No internet connectivity; refusing to continue"
    exit 122
  fi
  local host resolved failed=false
  for host in github.com raw.githubusercontent.com api.github.com; do
    resolved=$(getent hosts "$host" | awk '{ print $1 }' | head -n1)
    if [ -z "$resolved" ]; then
      msg_error "DNS resolution failed for $host"
      failed=true
    fi
  done
  [ "$failed" = true ] && fatal "DNS resolution failed for required hosts"
  set -e
  trap 'error_handler' ERR
}

apt_update_safe() {
  local attempt
  for attempt in 1 2 3; do
    if $STD apt-get update; then return 0; fi
    msg_warn "apt-get update failed (attempt $attempt/3); retrying"
    sleep 3
  done
  msg_error "apt-get update failed after 3 attempts"
  return 100
}

update_os() {
  msg_info "Updating Container OS"
  # Deliberately NO live fetch of tools.func here (upstream does): the tools
  # vocabulary ships in this library, pinned with the template.
  apt_update_safe
  $STD apt-get -o Dpkg::Options::="--force-confold" -y dist-upgrade
  rm -rf /usr/lib/python3.*/EXTERNALLY-MANAGED
  msg_ok "Updated Container OS"
}

# -- architecture and downloads ------------------------------------------------
arch_resolve() {
  local amd64_val="${1:-amd64}"
  local arm64_val="${2:-arm64}"
  local arch
  arch="$(dpkg --print-architecture 2>/dev/null || uname -m)"
  case "$arch" in
  amd64 | x86_64) echo "$amd64_val" ;;
  arm64 | aarch64) echo "$arm64_val" ;;
  *)
    msg_error "Unsupported architecture: $arch"
    return 106
    ;;
  esac
}

ensure_dependencies() {
  local missing=() dep
  for dep in "$@"; do
    command -v "$dep" >/dev/null 2>&1 || missing+=("$dep")
  done
  if [ "${#missing[@]}" -gt 0 ]; then
    msg_info "Installing dependencies: ${missing[*]}"
    apt_update_safe
    $STD apt-get install -y "${missing[@]}"
    msg_ok "Installed dependencies"
  fi
}

curl_download() {
  local destination="$1" url="$2"
  curl -fsSL --retry 3 --retry-delay 2 --connect-timeout 10 -o "$destination" "$url"
}

# -- GitHub releases -----------------------------------------------------------
# Lean reimplementation of the upstream contract the vendored scripts rely on:
# modes tarball|prebuild|singlefile|binary, version ledger at $HOME/.<app_lc>.
_hh_gh_release_json() {
  local repo="$1" version="$2"
  local api_url="https://api.github.com/repos/$repo/releases/latest"
  [ "$version" != "latest" ] && api_url="https://api.github.com/repos/$repo/releases/tags/$version"
  local header=()
  [ -n "${GITHUB_TOKEN:-}" ] && header=(-H "Authorization: token $GITHUB_TOKEN")
  curl -fsSL --retry 3 --retry-delay 2 --max-time 120 "${header[@]}" "$api_url"
}

fetch_and_deploy_gh_release() {
  local appname="$1" repo="$2" mode="${3:-tarball}" version="${4:-latest}"
  local target="${5:-/opt/$appname}" asset_pattern="${6:-}"
  local app_lc
  app_lc=$(echo "${appname,,}" | tr -d ' ')
  local version_file="$HOME/.${app_lc}"
  local current_version=""
  [ -f "$version_file" ] && current_version=$(<"$version_file")

  ensure_dependencies jq curl

  local json tag_name
  json=$(_hh_gh_release_json "$repo" "$version") || {
    msg_error "Failed to fetch release metadata of $repo from the GitHub API"
    return 22
  }
  tag_name=$(echo "$json" | jq -r '.tag_name // .name // empty')
  [[ "$tag_name" =~ ^v[0-9] ]] && version="${tag_name:1}" || version="$tag_name"
  if [ -z "$tag_name" ]; then
    msg_error "No release tag found for $repo"
    return 22
  fi
  if [ "$current_version" = "$version" ]; then
    msg_ok "$appname is already up-to-date (v$version)"
    return 0
  fi

  local tmpdir
  tmpdir=$(mktemp -d) || return 1
  msg_info "Fetching GitHub release: $appname ($version)"

  case "$mode" in
  tarball | source)
    local url="https://github.com/$repo/archive/refs/tags/$tag_name.tar.gz"
    curl_download "$tmpdir/src.tar.gz" "$url" || { msg_error "Download failed: $url"; rm -rf "$tmpdir"; return 250; }
    mkdir -p "$target"
    tar --no-same-owner -xzf "$tmpdir/src.tar.gz" -C "$tmpdir" || { msg_error "Extraction failed"; rm -rf "$tmpdir"; return 251; }
    local inner
    inner=$(find "$tmpdir" -mindepth 1 -maxdepth 1 -type d | head -n1)
    cp -a "$inner"/. "$target"/ || { rm -rf "$tmpdir"; return 251; }
    ;;
  prebuild | singlefile | binary)
    if [ -z "$asset_pattern" ] && [ "$mode" != "binary" ]; then
      msg_error "Mode '$mode' requires an asset filename pattern"
      rm -rf "$tmpdir"
      return 65
    fi
    local asset_url="" u candidate
    for u in $(echo "$json" | jq -r '.assets[].browser_download_url'); do
      candidate="${u##*/}"
      if [ -n "$asset_pattern" ]; then
        # shellcheck disable=SC2254
        case "$candidate" in $asset_pattern) asset_url="$u"; break ;; esac
      elif [[ "$candidate" == *.deb ]]; then
        asset_url="$u"
        break
      fi
    done
    if [ -z "$asset_url" ]; then
      msg_error "No asset matching '${asset_pattern:-*.deb}' found in $repo $tag_name"
      rm -rf "$tmpdir"
      return 252
    fi
    local filename="${asset_url##*/}"
    curl_download "$tmpdir/$filename" "$asset_url" || { msg_error "Download failed: $asset_url"; rm -rf "$tmpdir"; return 250; }
    if [ "$mode" = "binary" ]; then
      SYSTEMD_OFFLINE=1 $STD apt-get install -y "$tmpdir/$filename" || { rm -rf "$tmpdir"; return 100; }
    elif [ "$mode" = "singlefile" ]; then
      mkdir -p "$target"
      cp "$tmpdir/$filename" "$target/$appname"
      chmod +x "$target/$appname"
    else
      mkdir -p "$target"
      case "$filename" in
      *.zip)
        ensure_dependencies unzip
        $STD unzip -o "$tmpdir/$filename" -d "$tmpdir/unpack" || { rm -rf "$tmpdir"; return 251; }
        ;;
      *.tar.* | *.tgz | *.txz)
        mkdir -p "$tmpdir/unpack"
        tar --no-same-owner -xf "$tmpdir/$filename" -C "$tmpdir/unpack" || { rm -rf "$tmpdir"; return 251; }
        ;;
      *)
        msg_error "Unsupported archive format: $filename"
        rm -rf "$tmpdir"
        return 65
        ;;
      esac
      # A single top-level directory unpacks flattened (the upstream convention).
      local entries
      entries=$(find "$tmpdir/unpack" -mindepth 1 -maxdepth 1)
      if [ "$(echo "$entries" | wc -l)" -eq 1 ] && [ -d "$entries" ]; then
        cp -a "$entries"/. "$target"/
      else
        cp -a "$tmpdir/unpack"/. "$target"/
      fi
    fi
    ;;
  *)
    msg_error "Unknown fetch_and_deploy_gh_release mode: $mode"
    rm -rf "$tmpdir"
    return 65
    ;;
  esac
  rm -rf "$tmpdir"
  echo "$version" >"$version_file"
  msg_ok "Deployed $appname $version"
}

check_for_gh_release() {
  local appname="$1" repo="$2" pinned="${3:-}"
  local app_lc
  app_lc=$(echo "${appname,,}" | tr -d ' ')
  local version_file="$HOME/.${app_lc}"
  local current="" latest json
  [ -f "$version_file" ] && current=$(<"$version_file")
  msg_info "Checking for update: $appname"
  ensure_dependencies jq curl
  json=$(_hh_gh_release_json "$repo" "${pinned:-latest}") || {
    msg_error "Failed to query the GitHub API for $repo"
    return 22
  }
  latest=$(echo "$json" | jq -r '.tag_name // .name // empty')
  [[ "$latest" =~ ^v[0-9] ]] && latest="${latest:1}"
  if [ -z "$latest" ]; then
    msg_error "No release found for $repo"
    return 22
  fi
  if [ "$current" = "$latest" ]; then
    msg_ok "$appname is already up-to-date (v$latest)"
    return 1
  fi
  msg_info "Update available for $appname: ${current:-none} -> $latest"
  return 0
}

# -- apt repositories ----------------------------------------------------------
setup_deb822_repo() {
  local name="$1" gpg_url="$2" repo_url="$3" suite="$4" component="${5:-main}"
  local architectures="${6:-}"
  if [ -z "$name" ] || [ -z "$gpg_url" ] || [ -z "$repo_url" ] || [ -z "$suite" ]; then
    msg_error "setup_deb822_repo: missing required parameters"
    return 65
  fi
  ensure_dependencies gpg curl
  mkdir -p /etc/apt/keyrings
  local tmp_gpg
  tmp_gpg=$(mktemp)
  curl_download "$tmp_gpg" "$gpg_url" || { msg_error "Failed to download GPG key from $gpg_url"; rm -f "$tmp_gpg"; return 7; }
  if grep -q "BEGIN PGP" "$tmp_gpg" 2>/dev/null; then
    gpg --dearmor --yes -o "/etc/apt/keyrings/${name}.gpg" <"$tmp_gpg" || { rm -f "$tmp_gpg"; return 251; }
  else
    cp -f "$tmp_gpg" "/etc/apt/keyrings/${name}.gpg"
  fi
  rm -f "$tmp_gpg"
  chmod 644 "/etc/apt/keyrings/${name}.gpg"
  {
    echo "Types: deb"
    echo "URIs: $repo_url"
    echo "Suites: $suite"
    if [[ "$suite" != */ ]] && [ -n "$component" ]; then echo "Components: $component"; fi
    [ -n "$architectures" ] && echo "Architectures: $architectures"
    echo "Signed-By: /etc/apt/keyrings/${name}.gpg"
  } >"/etc/apt/sources.list.d/${name}.sources"
  apt_update_safe
  msg_ok "Configured repository ${name}"
}

# -- update_script environment checks ------------------------------------------
check_container_storage() {
  local usage
  usage=$(df / --output=pcent 2>/dev/null | tail -n1 | tr -dc '0-9')
  if [ -n "$usage" ] && [ "$usage" -ge 90 ]; then
    msg_warn "Storage is ${usage}% full on /"
  fi
}

check_container_resources() {
  local mem_mb
  mem_mb=$(free -m 2>/dev/null | awk '/^Mem:/{print $2}')
  if [ -n "$mem_mb" ] && [ "$mem_mb" -lt 256 ]; then
    msg_warn "Only ${mem_mb} MiB RAM available"
  fi
}

# -- finishing touches ---------------------------------------------------------
motd_ssh() {
  local profile_file="/etc/profile.d/00_lxc-details.sh"
  {
    echo "[ -t 1 ] || return 0"
    echo "echo \"${APPLICATION} system container, managed by hohenheim\""
    echo "echo \"App installed from community-scripts/ProxmoxVE (MIT)\""
  } >"$profile_file"
  chmod -x /etc/update-motd.d/* 2>/dev/null || true
  if [ "$SSH_ROOT" = "yes" ]; then
    sed -i "s/#PermitRootLogin prohibit-password/PermitRootLogin yes/g" /etc/ssh/sshd_config 2>/dev/null || true
    systemctl restart sshd 2>/dev/null || true
  fi
}

customize() {
  # No getty autologin (the platform reaches the container through the daemon,
  # not a console login), and /usr/bin/update must never fetch main: in-place
  # app updates are the platform's own action, running the PINNED update script.
  cat <<'EOF' >/usr/bin/update
#!/usr/bin/env bash
echo "This app is managed by hohenheim; run its 'Update app' action from the panel." 1>&2
echo "Ad-hoc updates from the community-scripts main branch are disabled by design." 1>&2
exit 1
EOF
  chmod +x /usr/bin/update
  if [ -n "$SSH_AUTHORIZED_KEY" ]; then
    mkdir -p /root/.ssh
    echo "$SSH_AUTHORIZED_KEY" >/root/.ssh/authorized_keys
    chmod 700 /root/.ssh
    chmod 600 /root/.ssh/authorized_keys
  fi
}

cleanup_lxc() {
  msg_info "Cleaning up"
  if is_alpine; then
    $STD apk cache clean 2>/dev/null || true
    rm -rf /var/cache/apk/*
  else
    $STD apt-get -y autoremove 2>/dev/null || true
    $STD apt-get -y autoclean 2>/dev/null || true
    $STD apt-get -y clean 2>/dev/null || true
  fi
  find /tmp /var/tmp -type f -name 'tmp*' -delete 2>/dev/null || true
  msg_ok "Cleaned"
}

# -- host-coupled helpers: real or a refusal BY NAME ---------------------------
# setup_hwaccel is DELIBERATELY absent from HOHENHEIM_FUNCS_VOCABULARY: the static
# gate refuses a script calling it at import/approval/install, because it can never
# work here. This function is the runtime defense-in-depth for a call the static
# scan could not see (dynamically built) -- a named refusal, never a silent stub.
setup_hwaccel() {
  msg_error "setup_hwaccel needs GPU passthrough, which is a HOST-side container"
  msg_error "configuration hohenheim does not implement for the Incus tier yet."
  msg_error "Refusing rather than reporting hardware acceleration that does not exist."
  exit 70
}
