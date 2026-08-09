package be.elevenways.hohenheim;

import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.annotation.ZenitAutoLoad;
import be.elevenways.zenit.common.setting.SettingDefinition;
import be.elevenways.zenit.common.setting.SettingGroup;
import be.elevenways.zenit.common.setting.SettingsContext;
import be.elevenways.zenit.common.validation.PathKind;
import be.elevenways.hohenheim.security.IpAddressSyntax;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * All Hohenheim configuration settings, organized by group.
 */
@ZenitAutoLoad(loadInnerClasses = true)
public class HohenheimSettings {

    // AIDEV-NOTE: The context roots at hohenheim's OWN group (the standard
    // consumer shape; only ServerSettings roots at Zenit.SETTINGS). File keys
    // are RELATIVE to this root, so settings/hohenheim.dry keeps the flat
    // proxy/ssl/... shape. The server loads it via HohenheimSettingsFiles.
    public static final SettingGroup HOHENHEIM = Zenit.SETTINGS.createGroup("hohenheim")
        .label("Hohenheim");

    public static final SettingsContext VALUES = new SettingsContext(HOHENHEIM);

    // Nested groups below are force-loaded at compile time via @ZenitAutoLoad
    // (loadInnerClasses=true): Protoblast's Gradle plugin emits a reference to
    // each into the generated BlastAutoLoadInit, fired on first Blast use. No
    // per-group boilerplate here; adding a group is enough.

    // --- Proxy ---
    public abstract class Proxy {
        public static final SettingGroup GROUP = HOHENHEIM.createGroup("proxy")
            .label("Proxy")
            .describe("The reverse-proxy listeners and routing behaviour")
            .icon("route");

        public static final SettingDefinition<Integer> HTTP_PORT = GROUP.buildSetting("http_port", Integer.class)
            .defaultValue(80)
            .description("HTTP proxy listen port")
            .restartRequired()
            .build();

        public static final SettingDefinition<String> HTTP_SOCKET_PATH = GROUP.buildSetting("http_socket_path", String.class)
            .filesystemPath(HohenheimPaths.SERVER_FILES, PathKind.ANY)
            .description("Optional Unix socket path for the HTTP proxy instead of a public TCP listener. "
                + "The socket bridges to a loopback TCP port, so on a multi-user host any local account "
                + "can still reach the proxy via 127.0.0.1 regardless of the socket permissions")
            .restartRequired()
            .build();

        public static final SettingDefinition<String> HTTP_SOCKET_PERMISSIONS = GROUP.buildSetting("http_socket_permissions", String.class)
            .defaultValue("0660")
            .description("Octal permissions applied to the HTTP proxy Unix socket (limits socket access only, "
                + "not the internal loopback port)")
            .restartRequired()
            .build();

        public static final SettingDefinition<Integer> HTTPS_PORT = GROUP.buildSetting("https_port", Integer.class)
            .defaultValue(443)
            .description("HTTPS proxy listen port")
            .restartRequired()
            .build();

        public static final SettingDefinition<Integer> CONNECTION_PROLOGUE_TIMEOUT_SECONDS = GROUP
            .buildSetting("connection_prologue_timeout_seconds", Integer.class)
            .defaultValue(5)
            .suffix("s")
            .description("Maximum time a public connection may take to send everything Hohenheim must "
                + "read before routing it: an optional PROXY v2 header, plus the TLS ClientHello on "
                + "the HTTPS port")
            .restartRequired()
            .build();

        public static final SettingDefinition<List<String>> PROXY_PROTOCOL_TRUSTED_SOURCES = GROUP
            .buildStringListSetting("proxy_protocol_trusted_sources")
            .defaultValue(List.of())
            .coercer(Proxy::coerceIpNetworks)
            .description("IP addresses and CIDR ranges allowed to supply an inbound PROXY protocol v2 header "
                + "on either public port. A non-empty list also moves the plain HTTP listener behind "
                + "the shared ingress front. Direct clients remain supported; an untrusted header is rejected")
            .build();

        private static SettingDefinition.CoercionResult<List<String>> coerceIpNetworks(Object raw) {
            if (!(raw instanceof List<?> list)) {
                return SettingDefinition.CoercionResult.rejected();
            }
            ArrayList<String> result = new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof String value) || !IpAddressSyntax.isNetwork(value)) {
                    return SettingDefinition.CoercionResult.rejected();
                }
                result.add(value.trim());
            }
            return SettingDefinition.CoercionResult.accepted(List.copyOf(result));
        }

        public static final SettingDefinition<Integer> MAX_PENDING_CONNECTIONS = GROUP
            .buildSetting("max_pending_connections", Integer.class)
            .defaultValue(1024)
            .description("Maximum simultaneous connections per public listener that have not finished their "
                + "prologue; excess connections are closed immediately to bound slow-client memory "
                + "and file-descriptor use")
            .restartRequired()
            .build();

        public static final SettingDefinition<Integer> MAX_PUBLIC_CONNECTIONS = GROUP
            .buildSetting("max_public_connections", Integer.class)
            .defaultValue(10_000)
            .description("Maximum simultaneous client connections accepted by each public listener")
            .restartRequired()
            .build();

        public static final SettingDefinition<String> FALLBACK_ADDRESS = GROUP.buildSetting("fallback_address", String.class)
            .description("Address for requests that match no site")
            .build();

        public static final SettingDefinition<Boolean> FORCE_HTTPS = GROUP.buildSetting("force_https", Boolean.class)
            .defaultValue(true)
            .description("Redirect HTTP to HTTPS globally")
            .build();

        public static final SettingDefinition<String> IPV6_ADDRESS = GROUP.buildSetting("ipv6_address", String.class)
            .description("Optional IPv6 address for dedicated proxy listeners")
            .restartRequired()
            .build();

        public static final SettingDefinition<String> NOT_FOUND_MESSAGE = GROUP.buildSetting("not_found_message", String.class)
            .defaultValue("")
            .description("Error message for unmatched domains. Leave blank for the "
                + "localized default; a value here is the operator's own copy and is "
                + "shown to every visitor in every language")
            .multiline()
            .build();

        public static final SettingDefinition<String> UNREACHABLE_MESSAGE = GROUP.buildSetting("unreachable_message", String.class)
            .defaultValue("")
            .description("Error message for unreachable upstreams. Leave blank for the "
                + "localized default; a value here is the operator's own copy and is "
                + "shown to every visitor in every language")
            .multiline()
            .build();

        public static final SettingDefinition<Integer> FIRST_PORT = GROUP.buildSetting("first_port", Integer.class)
            .defaultValue(4748)
            .description("First TCP port assigned to managed child processes")
            .restartRequired()
            .build();

        public static final SettingDefinition<List<String>> TRUSTED_PROXY_KEYS = GROUP
            .buildStringListSetting("trusted_proxy_keys")
            .defaultValue(List.of())
            .description("X-Hohenheim-Key values accepted from trusted upstream HTTP proxies")
            .secret()
            .coercer(Proxy::coerceTrustedProxyKeys)
            .build();

        private static SettingDefinition.CoercionResult<List<String>> coerceTrustedProxyKeys(Object raw) {
            if (raw instanceof List<?> list) {
                List<String> values = new ArrayList<>(list.size());
                for (Object item : list) {
                    if (!(item instanceof String string)) {
                        return SettingDefinition.CoercionResult.rejected();
                    }
                    values.add(string);
                }
                return SettingDefinition.CoercionResult.accepted(List.copyOf(values));
            }
            if (raw instanceof String legacy) {
                if (legacy.isBlank()) return SettingDefinition.CoercionResult.accepted(List.of());
                return SettingDefinition.CoercionResult.accepted(Arrays.stream(legacy.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .toList());
            }
            return SettingDefinition.CoercionResult.rejected();
        }
    }

    // --- Install roles ---
    // AIDEV-NOTE: restartRequired is ADVISORY metadata (a UI banner, nothing
    // enforces it at runtime). What makes these flags behave consistently is
    // that the server reads them ONCE at boot into the HohenheimRoles snapshot
    // (captured by HohenheimSettingsFiles.load) and every gate reads the
    // snapshot, never the live setting. A live edit therefore changes nothing
    // until the restart the banner asks for.
    public abstract class Roles {
        public static final SettingGroup GROUP = HOHENHEIM.createGroup("roles")
            .label("Install roles")
            .describe("Which subsystems this installation runs; disabled roles do not start, "
                + "declare no scheduled tasks and remove their admin surfaces")
            .icon("server");

        public static final SettingDefinition<Boolean> PROXY = GROUP.buildSetting("proxy", Boolean.class)
            .defaultValue(true)
            .description("Run the reverse proxy: public listeners, sites, domains, certificates "
                + "and access lists")
            .restartRequired()
            .build();

        public static final SettingDefinition<Boolean> DNS = GROUP.buildSetting("dns", Boolean.class)
            .defaultValue(true)
            .description("Run the DNS subsystem: zone store, federation and the authoritative "
                + "server (the listener itself still requires dns.enabled)")
            .restartRequired()
            .build();

        public static final SettingDefinition<Boolean> FIREWALL = GROUP.buildSetting("firewall", Boolean.class)
            .defaultValue(true)
            .description("Run ban enforcement and spamservice reputation; nftables stays behind "
                + "security.nftables_enabled")
            .restartRequired()
            .build();

        public static final SettingDefinition<Boolean> STACKS = GROUP.buildSetting("stacks", Boolean.class)
            .defaultValue(true)
            .description("Run managed Docker stacks; requires a reachable Docker daemon, which "
                + "is probed loudly at boot")
            .restartRequired()
            .build();

        public static final SettingDefinition<Boolean> PROCESSES = GROUP.buildSetting("processes", Boolean.class)
            .defaultValue(true)
            .description("Run managed child processes (Node.js and friends): the process monitor, "
                + "port and socket allocators")
            .restartRequired()
            .build();

        public static final SettingDefinition<Boolean> DATABASES = GROUP.buildSetting("databases", Boolean.class)
            .defaultValue(true)
            .description("Run managed databases: provisioning, backups and the site database links")
            .restartRequired()
            .build();

        public static final SettingDefinition<Boolean> INSTANCES = GROUP.buildSetting("instances", Boolean.class)
            .defaultValue(true)
            .description("Run managed instances (the instance tier): tenant runtime units via "
                + "the instance drivers; requires a reachable Docker daemon, probed loudly at boot")
            .restartRequired()
            .build();
    }

    // --- SSL/TLS ---
    public abstract class Ssl {
        public static final SettingGroup GROUP = HOHENHEIM.createGroup("ssl")
            .label("SSL / TLS")
            .describe("Automatic Let's Encrypt certificate issuance")
            .icon("lock");

        public static final SettingDefinition<Boolean> LETSENCRYPT_ENABLED = GROUP.buildSetting("letsencrypt_enabled", Boolean.class)
            .defaultValue(true)
            .description("Enable automatic Let's Encrypt certificates")
            .build();

        public static final SettingDefinition<String> LETSENCRYPT_EMAIL = GROUP.buildSetting("letsencrypt_email", String.class)
            .description("Email address for Let's Encrypt account")
            .build();

        public static final SettingDefinition<Boolean> LETSENCRYPT_STAGING = GROUP.buildSetting("letsencrypt_staging", Boolean.class)
            .defaultValue(false)
            .description("Use Let's Encrypt staging server")
            .build();

        /**
         * The ACME directory to order from; blank means Let's Encrypt (staging when
         * {@link #LETSENCRYPT_STAGING} is on).
         *
         * AIDEV-NOTE: this exists because the directory used to be a hardcoded
         * {@code acme://letsencrypt.org} in AcmeService.ensureAccount, which made an
         * internal ACME CA (step-ca, an enterprise CA, a Pebble in CI) unreachable at any
         * amount of configuration -- and made every test of issuance either impossible or
         * a request to the real Let's Encrypt. One setting serves both.
         */
        public static final SettingDefinition<String> ACME_DIRECTORY_URL = GROUP
            .buildSetting("acme_directory_url", String.class)
            .description("ACME directory URL of the certificate authority; empty uses "
                + "Let's Encrypt (production or staging per the staging switch)")
            .build();

        public static final SettingDefinition<String> DNS_HOOK_COMMAND = GROUP.buildSetting("dns_hook_command", String.class)
            .description("Executable DNS-01 hook; called as: command present|cleanup record-name record-value")
            .restartRequired()
            .build();

        public static final SettingDefinition<Integer> DNS_PROPAGATION_SECONDS = GROUP.buildSetting("dns_propagation_seconds", Integer.class)
            .defaultValue(30)
            .suffix("s")
            .description("Seconds to wait after publishing DNS-01 TXT records")
            .build();
    }

    // --- Authoritative DNS ---
    public abstract class Dns {
        public static final SettingGroup GROUP = HOHENHEIM.createGroup("dns")
            .label("DNS server")
            .describe("Authoritative DNS for the zones Hohenheim hosts")
            .icon("sitemap");

        public static final SettingDefinition<Boolean> ENABLED = GROUP.buildSetting("enabled", Boolean.class)
            .defaultValue(false)
            .description("Serve the configured DNS zones authoritatively on UDP and TCP. "
                + "The registrar must delegate each zone to this server before answers matter, "
                + "and production delegations need at least two name servers")
            .restartRequired()
            .build();

        public static final SettingDefinition<String> BIND_ADDRESS = GROUP.buildSetting("bind_address", String.class)
            .defaultValue("0.0.0.0")
            .description("Address the DNS listeners bind to")
            .restartRequired()
            .build();

        public static final SettingDefinition<Integer> PORT = GROUP.buildSetting("port", Integer.class)
            .defaultValue(53)
            .description("DNS listen port (UDP and TCP); port 53 usually needs elevated privileges "
                + "or a capability like CAP_NET_BIND_SERVICE")
            .restartRequired()
            .build();

        public static final SettingDefinition<Integer> RATE_LIMIT_PER_SECOND = GROUP
            .buildSetting("rate_limit_per_second", Integer.class)
            .defaultValue(20)
            .suffix("req/s")
            .description("Response-rate-limit per client network and query over UDP, the standard "
                + "mitigation against DNS reflection abuse. Every second limited response is answered "
                + "truncated so legitimate clients retry over TCP (never limited). 0 disables")
            .build();
    }

    // --- Logging ---
    // Note: the admin UI listens on Zenit's ServerSettings.Network.PORT.
    // The previous HohenheimSettings.Admin.PORT was displayed in the UI but
    // wired to nothing, so it was removed to avoid misleading operators.
    public abstract class Logging {
        public static final SettingGroup GROUP = HOHENHEIM.createGroup("logging")
            .label("Logging")
            .describe("Access logging for proxied requests")
            .icon("align-left");

        public static final SettingDefinition<Boolean> ACCESS_TO_FILE = GROUP.buildSetting("access_to_file", Boolean.class)
            .defaultValue(true)
            .description("Log access requests to file")
            .build();

        public static final SettingDefinition<String> ACCESS_PATH = GROUP.buildSetting("access_path", String.class)
            .defaultValue("/var/log/hohenheim/access.log")
            .filesystemPath(HohenheimPaths.SERVER_FILES, PathKind.FILE)
            .description("Access log file path")
            .build();
    }

    // --- Node.js discovery ---
    public abstract class Node {
        public static final SettingGroup GROUP = HOHENHEIM.createGroup("node")
            .label("Node.js")
            .describe("Node.js runtime discovery")
            .icon("code");

        public static final SettingDefinition<String> LOCATIONS = GROUP.buildSetting("locations", String.class)
            .description("Additional N-style version directories, separated by commas or new lines")
            .multiline()
            .build();
    }

    // --- Storage ---
    public abstract class Storage {
        public static final SettingGroup GROUP = HOHENHEIM.createGroup("storage")
            .label("Storage")
            .describe("Filesystem locations for persistent data")
            .icon("folder");

        public static final SettingDefinition<String> DATA_PATH = GROUP.buildSetting("data_path", String.class)
            .defaultValue("data")
            .filesystemPath(HohenheimPaths.SERVER_FILES, PathKind.DIRECTORY)
            .description("Base directory for persistent data (git repos, etc.)")
            .restartRequired()
            .build();
    }

    // --- Stacks ---
    public abstract class Stacks {
        public static final SettingGroup GROUP = HOHENHEIM.createGroup("stacks")
            .label("Stacks")
            .describe("Managed Docker stacks and the disk they reclaim")
            .icon("cubes");

        public static final SettingDefinition<Boolean> RECLAIM_IMAGES = GROUP
            .buildSetting("reclaim_images", Boolean.class)
            .defaultValue(true)
            .description("Periodically remove superseded images of the repositories managed "
                + "stacks declare, on every server that hosts a stack. An image with any "
                + "reference outside those repositories is left alone, an image any container "
                + "still references is never removed, and volumes are never touched here "
                + "(that is the one removal which destroys data)")
            .build();

        public static final SettingDefinition<Boolean> RECLAIM_UNTRACKED = GROUP
            .buildSetting("reclaim_untracked", Boolean.class)
            .defaultValue(false)
            .description("Also remove images that carry no tag or digest at all. Hohenheim "
                + "cannot prove those are its own -- they may be an external build's "
                + "leftovers -- so this is what a shared host keeps off and a dedicated one "
                + "turns on (the equivalent of docker image prune)")
            .build();

        public static final SettingDefinition<Integer> RECLAIM_MIN_AGE_HOURS = GROUP
            .buildSetting("reclaim_min_age_hours", Integer.class)
            .defaultValue(24)
            .suffix("h")
            .description("Images whose build stamp is more recent than this are kept: the "
                + "guard for freshly BUILT images (a pulled image's stamp is its upstream "
                + "build time; deploys are protected by the in-flight check instead). "
                + "Minimum 1")
            .coercer(raw -> {
                Integer value = raw instanceof Number number ? number.intValue()
                    : raw instanceof String text && !text.isBlank() ? Integer.parseInt(text.trim())
                    : null;
                if (value == null) {
                    return SettingDefinition.CoercionResult.rejected();
                }
                if (value < 1) {
                    throw new IllegalArgumentException(
                        "stacks.reclaim_min_age_hours must be at least 1");
                }
                return SettingDefinition.CoercionResult.accepted(value);
            })
            .build();
    }

    // --- Database ---
    public abstract class Database {
        public static final SettingGroup GROUP = HOHENHEIM.createGroup("database")
            .label("Database")
            .describe("Hohenheim's own database and managed-database backups")
            .icon("database");

        public static final SettingDefinition<String> PATH = GROUP.buildSetting("path", String.class)
            .defaultValue("hohenheim.db")
            .filesystemPath(HohenheimPaths.SERVER_FILES, PathKind.FILE)
            .description("SQLite database file path (used when 'url' is blank)")
            .restartRequired()
            .build();

        public static final SettingDefinition<String> URL = GROUP.buildSetting("url", String.class)
            .description("Full SQLite JDBC URL to use instead of 'path', e.g. "
                + "jdbc:sqlite:/var/lib/hohenheim/hohenheim.db. Hohenheim refuses any "
                + "other engine at boot: its migrations contain SQLite-only raw SQL")
            .restartRequired()
            .build();

        public static final SettingDefinition<String> ENGINE = GROUP.buildSetting("engine", String.class)
            .defaultValue("auto")
            .description("Database engine: auto (infer from url) or sqlite. Anything else is "
                + "refused at boot because Hohenheim's migrations contain SQLite-only raw SQL")
            .restartRequired()
            .build();

        public static final SettingDefinition<String> USERNAME = GROUP.buildSetting("username", String.class)
            .description("Database username (server engines only; ignored for sqlite/duckdb)")
            .restartRequired()
            .build();

        public static final SettingDefinition<String> PASSWORD = GROUP.buildSetting("password", String.class)
            .secret()
            .description("Database password (server engines only)")
            .restartRequired()
            .build();

        public static final SettingDefinition<String> BACKUP_PATH = GROUP.buildSetting("backup_path", String.class)
            .defaultValue("data/backups")
            .filesystemPath(HohenheimPaths.SERVER_FILES, PathKind.DIRECTORY)
            .description("Directory for scheduled managed-database dumps")
            .build();

        public static final SettingDefinition<Integer> BACKUP_RETENTION = GROUP.buildSetting("backup_retention", Integer.class)
            .defaultValue(7)
            .description("Number of dumps to keep per managed database")
            .build();

        /**
         * The backup TARGET record (by name) control-plane recovery archives are uploaded to.
         *
         * Required, with no default: a control-plane archive exists for the case where this
         * host is gone, and a local directory does not survive that. Unset means the nightly
         * task FAILS -- loudly, and visibly on the dashboard -- rather than writing an archive
         * to the same disk it is supposed to outlive.
         */
        public static final SettingDefinition<String> CONTROL_PLANE_BACKUP_TARGET =
            GROUP.buildSetting("control_plane_backup_target", String.class)
                .description("Name of the backup target that receives control-plane recovery "
                    + "archives (database + field-encryption keyring). REQUIRED: pick a target "
                    + "in a different failure domain, because a local copy dies with the disk "
                    + "it was protecting against. The archive carries the MASTER KEYS IN THE "
                    + "CLEAR and its manifest is unsigned -- whoever can read the destination "
                    + "can decrypt every secret in the database, so treat it exactly like the "
                    + "keyring file")
                .build();

        public static final SettingDefinition<Integer> MAX_DUMP_MB = GROUP.buildSetting("max_dump_mb", Integer.class)
            .defaultValue(2048)
            .description("Upper bound in MiB for one binary managed-database dump (Redis RDB, "
                + "mongodump archive) fetched out of a container. The fetch is buffered "
                + "through controller memory, so this cap is what protects the heap -- a "
                + "larger dump refuses instead of taking the controller down")
            .build();
    }

    // --- Security ---
    public abstract class Security {
        public static final SettingGroup GROUP = HOHENHEIM.createGroup("security")
            .label("Security")
            .describe("Container isolation, hostname-scan detection, native IP bans, "
                + "spamservice reputation and the released-hostname quarantine")
            .icon("shield");

        // AIDEV-NOTE: the ONLY tunable part of ContainerHardening. Everything else in the
        // baseline (drop-ALL capabilities, no-new-privileges, the escape refusals) is
        // deliberately not a setting: a security policy an operator can switch off from a
        // form is a policy a compromised admin session can switch off.
        public static final SettingDefinition<Integer> CONTAINER_PIDS_LIMIT = GROUP
            .buildSetting("container_pids_limit", Integer.class)
            .defaultValue(512)
            .description("Maximum number of processes a single managed container may have "
                + "(stacks, Docker sites, managed databases and instances alike). A fork "
                + "bomb inside one tenant's container then exhausts its own cgroup instead "
                + "of the host's process table. Raise it only for a workload that genuinely "
                + "forks per connection; 0 or less falls back to the default")
            .build();

        public static final SettingDefinition<Integer> RELEASE_QUARANTINE_DAYS = GROUP.buildSetting("release_quarantine_days", Integer.class)
            .defaultValue(30)
            .suffix("d")
            .description("Days a hostname stays reserved for its former owner after the site "
                + "releasing it is deleted, disabled, renamed or has the domain row removed. "
                + "A DIFFERENT owner claiming it inside this window is refused (an "
                + "administrator can lift a quarantine per hostname); the same owner may "
                + "always re-claim, and operator-owned sites never quarantine each other. "
                + "This is what stops a subdomain takeover through a CNAME that still points "
                + "here from a zone we do not host. 0 disables the quarantine entirely")
            .build();

        public static final SettingDefinition<Integer> DOMAIN_MISS_THRESHOLD = GROUP.buildSetting("domain_miss_threshold", Integer.class)
            .defaultValue(5)
            .description("In-window domain misses before an IP's misses are recorded as security events")
            .build();

        public static final SettingDefinition<Integer> DOMAIN_MISS_WINDOW_SECONDS = GROUP.buildSetting("domain_miss_window_seconds", Integer.class)
            .defaultValue(300)
            .suffix("s")
            .description("Sliding window for counting weighted security events towards a ban")
            .build();

        public static final SettingDefinition<Integer> DOMAIN_MISS_BAN_THRESHOLD = GROUP.buildSetting("domain_miss_ban_threshold", Integer.class)
            .defaultValue(25)
            .description("In-window weighted event score before an IP is auto-banned")
            .build();

        public static final SettingDefinition<Integer> DOMAIN_MISS_DECAY_PER_HIT = GROUP.buildSetting("domain_miss_decay_per_hit", Integer.class)
            .defaultValue(2)
            .description("Score forgiven for each successful route hit")
            .build();

        public static final SettingDefinition<Boolean> BANS_ENABLED = GROUP.buildSetting("bans_enabled", Boolean.class)
            .defaultValue(true)
            .description("Enforce IP bans in the proxy (HTTP refusal and TLS handshake refusal) "
                + "and create automatic bans when the event score crosses the threshold")
            .build();

        public static final SettingDefinition<List<String>> NEVER_BAN = GROUP
            .buildStringListSetting("never_ban")
            .defaultValue(List.of())
            .description("IPs, CIDR ranges and hostnames that may NEVER be banned, automatically "
                + "or manually; add your own operator addresses as separate entries. Hostnames are resolved "
                + "in the background (never on a request), keep their last resolved addresses "
                + "on failure, and resolved IPv6 addresses protect their whole /64")
            .build();

        public static final SettingDefinition<Boolean> NFTABLES_ENABLED = GROUP.buildSetting("nftables_enabled", Boolean.class)
            .defaultValue(false)
            .description("Also enforce bans in the kernel via nftables (requires passwordless "
                + "sudo for nft; the table 'inet hohenheim' is owned by Hohenheim)")
            .build();

        public static final SettingDefinition<String> NFTABLES_PORTS = GROUP.buildSetting("nftables_ports", String.class)
            .defaultValue("80,443")
            .description("Comma-separated TCP ports the nftables ban rule is scoped to; "
                + "never widen this to ports like 22 or 53 that other services depend on")
            .build();

        public static final SettingDefinition<Integer> AUTO_BAN_TTL_HOURS = GROUP.buildSetting("auto_ban_ttl_hours", Integer.class)
            .defaultValue(24)
            .suffix("h")
            .description("Lifetime of an automatically created ban")
            .build();

        public static final SettingDefinition<Integer> AUTO_BAN_BUDGET_PER_HOUR = GROUP.buildSetting("auto_ban_budget_per_hour", Integer.class)
            .defaultValue(50)
            .description("Maximum automatic bans (any trigger: threat scorer, reputation) in a "
                + "sliding hour; when exhausted further auto-bans are suppressed and logged "
                + "loudly, so a runaway trigger becomes a bounded incident instead of a mass "
                + "ban of all visitors (minimum 1; manual bans are never limited)")
            .build();

        public static final SettingDefinition<Integer> DEFAULT_EVENT_WEIGHT = GROUP.buildSetting("default_event_weight", Integer.class)
            .defaultValue(1)
            .description("Ban-score weight for event types without a built-in weight")
            .build();

        public static final SettingDefinition<String> REPUTATION_BAN_CATEGORIES = GROUP.buildSetting("reputation_ban_categories", String.class)
            .defaultValue("spam,auth")
            .description("Comma-separated, case-insensitive behavioral event categories whose spamservice counts "
                + "feed the reputation ban decision; dataset flags (vpn/hosting/tor/proxy) "
                + "NEVER count")
            .build();

        public static final SettingDefinition<Integer> REPUTATION_BAN_THRESHOLD = GROUP.buildSetting("reputation_ban_threshold", Integer.class)
            .defaultValue(25)
            .description("Ban an IP when its weighted net spamservice event count across the "
                + "configured categories reaches this value (minimum 1)")
            .build();

        public static final SettingDefinition<Integer> REPUTATION_POSITIVE_EVENT_WEIGHT = GROUP.buildSetting("reputation_positive_event_weight", Integer.class)
            .defaultValue(10)
            .description("Number of negative events offset by one positive event in the SAME "
                + "category (minimum 0; 0 disables positive credit)")
            .build();

        public static final SettingDefinition<Integer> REPUTATION_TTL_SECONDS = GROUP.buildSetting("reputation_ttl_seconds", Integer.class)
            .defaultValue(300)
            .suffix("s")
            .description("How long a spamservice reputation answer is cached in-process "
                + "(minimum 1 second; changing this requires a restart)")
            .restartRequired()
            .build();

    }

    // --- Managed processes ---
    public abstract class Process {
        public static final SettingGroup GROUP = HOHENHEIM.createGroup("process")
            .label("Processes")
            .describe("Workload identity for managed child processes")
            .icon("terminal");

        // AIDEV-NOTE: enforcement is UNCONDITIONAL by default and there is no
        // "enable it later" gate: a control that refuses to turn the secure state ON
        // while sites are misconfigured only ever keeps an install insecure. A site
        // without its own claimed uid faults individually at handler creation
        // (WorkloadIdentity.forSite -> FaultedSiteHandler), which is the refusal an
        // operator can act on. Turning this OFF stays an explicit operator decision,
        // and never loosens tenant-managed sites (WorkloadIdentity.isTenantManaged).
        public static final SettingDefinition<Boolean> REQUIRE_DEDICATED_USER = GROUP
            .buildSetting("require_dedicated_user", Boolean.class)
            .defaultValue(true)
            .description("Refuse to start a managed site process without its own exclusively-claimed "
                + "system user. With a shared or absent user, same-uid workloads can read each "
                + "other's process environments (IPC tokens, database credentials). Sites another "
                + "tenant manages are ALWAYS required to have a dedicated user, regardless of "
                + "this setting")
            .build();

        // AIDEV-NOTE: the host-process twin of security.container_pids_limit, and a
        // SEPARATE setting rather than a reuse of it: that one caps one container's own
        // pid cgroup, this one caps a site UID whose children are counted by the kernel
        // against the whole host's process table. They are the same number by default and
        // are tuned for different reasons.
        public static final SettingDefinition<Integer> PIDS_LIMIT = GROUP
            .buildSetting("pids_limit", Integer.class)
            .defaultValue(512)
            .description("Maximum number of processes one managed site's child may have "
                + "(TasksMax on its cgroup scope, plus RLIMIT_NPROC on its dedicated "
                + "system user). A fork bomb in a site's code then exhausts its own budget "
                + "instead of the host's process table. 0 or less falls back to the default")
            .build();
    }

    // --- Proteus SSO (optional; password login is always available) ---
    public abstract class AuthProteus {
        public static final SettingGroup GROUP = HOHENHEIM.createGroup("auth_proteus")
            .label("Proteus SSO")
            .describe("Offer Proteus single sign-on next to password login")
            .icon("user");

        public static final SettingDefinition<Boolean> ENABLED = GROUP.buildSetting("enabled", Boolean.class)
            .defaultValue(false)
            .description("Offer Proteus SSO as a login option")
            .restartRequired()
            .build();

        public static final SettingDefinition<String> ENDPOINT = GROUP.buildSetting("endpoint", String.class)
            .description("Proteus realm server URL")
            .restartRequired()
            .build();

        public static final SettingDefinition<String> REALM_CLIENT = GROUP.buildSetting("realm_client", String.class)
            .description("Proteus realm client slug")
            .restartRequired()
            .build();

        public static final SettingDefinition<String> ACCESS_KEY = GROUP.buildSetting("access_key", String.class)
            .description("Proteus realm access key")
            .secret()
            .restartRequired()
            .build();

        public static final SettingDefinition<String> AUTHENTICATOR = GROUP.buildSetting("authenticator", String.class)
            .defaultValue("password")
            .description("Proteus authenticator slug")
            .restartRequired()
            .build();
    }

    // --- Per-site proxy auth (gating proxied upstreams behind a provider) ---
    public abstract class ProxyAuth {
        public static final SettingGroup GROUP = HOHENHEIM.createGroup("proxy_auth")
            .label("Proxy auth")
            .describe("Sessions for auth-provider-gated proxied sites")
            .icon("id-badge");

        public static final SettingDefinition<Long> SESSION_TTL_SECONDS = GROUP.buildSetting("session_ttl_seconds", Long.class)
            .defaultValue(86400L)
            .suffix("s")
            .description("Lifetime of a proxy-auth session (seconds)")
            .build();

        public static final SettingDefinition<Long> PERSISTENT_TTL_SECONDS = GROUP.buildSetting("persistent_ttl_seconds", Long.class)
            .defaultValue(1209600L)
            .suffix("s")
            .description("Lifetime of a proxy-auth persistent (remember-me) cookie (seconds)")
            .build();
    }

    // --- Per-owner consumption caps (the reservation ledger's policy side) ---
    public abstract class Quota {
        public static final SettingGroup GROUP = HOHENHEIM.createGroup("quota")
            .label("Quotas")
            .describe("Per-owner caps on what tenants may consume; the atomic "
                + "reservation ledger enforces them at write time")
            .icon("gauge");

        public static final SettingDefinition<Integer> MAX_INSTANCES_PER_OWNER = GROUP
            .buildSetting("max_instances_per_owner", Integer.class)
            .defaultValue(0)
            .description("Default maximum number of live instances one owner (a manage-grant "
                + "subject set; every operator-owned instance shares one bucket) may hold. "
                + "A per-owner Instance Quota record overrides this. 0 or less means NO cap "
                + "-- explicitly set a cap before exposing instance creation to tenants; "
                + "usage is counted either way, so enabling a cap later takes effect "
                + "immediately")
            .build();

        public static final SettingDefinition<Integer> MAX_MEMORY_MB_PER_OWNER = GROUP
            .buildSetting("max_memory_mb_per_owner", Integer.class)
            .defaultValue(0)
            .label("Max workload memory per owner (MB)")
            .suffix("MB")
            .description("Default cap on the total workload memory one owner may hold, summed "
                + "over their live instances. Each workload is charged the memory its "
                + "driver actually caps it to: its declared memory limit, or its kind's "
                + "declared footprint when it declares none -- so a tenant running only "
                + "unbounded workloads is charged honestly rather than nothing. A per-owner "
                + "Instance Quota record overrides this. 0 or less means NO cap; usage is "
                + "counted either way")
            .build();

        public static final SettingDefinition<Integer> MAX_DISK_GB_PER_OWNER = GROUP
            .buildSetting("max_disk_gb_per_owner", Integer.class)
            .defaultValue(0)
            .description("Default cap on the total attached-disk size (GB, summed over "
                + "instance device rows) one owner may hold. A per-owner Instance Quota "
                + "record overrides this. 0 or less means NO cap; usage is counted "
                + "either way")
            .build();

        public static final SettingDefinition<Integer> MAX_EXTRA_NICS_PER_OWNER = GROUP
            .buildSetting("max_extra_nics_per_owner", Integer.class)
            .defaultValue(0)
            .description("Default cap on the number of extra NICs (beyond the driver's "
                + "primary one) one owner may attach across instances. A per-owner "
                + "Instance Quota record overrides this. 0 or less means NO cap; usage "
                + "is counted either way")
            .build();

        public static final SettingDefinition<Integer> MAX_SITES_PER_OWNER = GROUP
            .buildSetting("max_sites_per_owner", Integer.class)
            .defaultValue(0)
            .label("Max sites per owner")
            .description("Default cap on the number of live sites one owner may hold. "
                + "Counted per site RECORD, not per container: eight of the eleven site "
                + "types run no workload at all, so the instance cap cannot bound them. "
                + "A per-owner Instance Quota record overrides this. 0 or less means NO "
                + "cap; usage is counted either way")
            .build();

        public static final SettingDefinition<Integer> MAX_DATABASES_PER_OWNER = GROUP
            .buildSetting("max_databases_per_owner", Integer.class)
            .defaultValue(0)
            .label("Max managed databases per owner")
            .description("Default cap on the number of managed databases one owner may "
                + "hold. This is charged ON TOP of the instance slot the engine container "
                + "already spends -- an instance slot is a workload slot an owner also "
                + "spends on game servers and stacks, so it cannot express a database "
                + "count. A per-owner Instance Quota record overrides this. 0 or less "
                + "means NO cap; usage is counted either way")
            .build();
    }

    // --- Per-HOST memory capacity (the placement side of the same ledger) ---
    public abstract class Capacity {
        public static final SettingGroup GROUP = HOHENHEIM.createGroup("capacity")
            .label("Host capacity")
            .describe("How much workload memory each host is allowed to carry; placement "
                + "skips a host with no headroom or no recent reading, and the reservation "
                + "ledger enforces the budget at write time")
            .icon("memory");

        public static final SettingDefinition<Integer> HOST_MEMORY_RESERVE_MB = GROUP
            .buildSetting("host_memory_reserve_mb", Integer.class)
            .defaultValue(768)
            .label("Host memory reserve (MB)")
            .description("Memory subtracted from a host's measured total before any "
                + "workload may be booked against it -- the kernel, the container/VM "
                + "daemon and page cache live here. Raise it on a host that also runs "
                + "something else")
            .build();

        public static final SettingDefinition<Double> MEMORY_OVERCOMMIT_RATIO = GROUP
            .buildSetting("memory_overcommit_ratio", Double.class)
            .defaultValue(1.0)
            .label("Memory overcommit ratio")
            .description("Multiplies the bookable memory after the reserve. 1.0 books no "
                + "more than the host physically has. Above 1.0 is a DELIBERATE operator "
                + "bet that workloads will not all use their declared memory at once; the "
                + "kernel OOM killer, not this controller, settles that bet")
            .build();

        public static final SettingDefinition<Integer> FACTS_MAX_AGE_HOURS = GROUP
            .buildSetting("facts_max_age_hours", Integer.class)
            .defaultValue(168)
            .label("Capacity reading maximum age (hours)")
            .description("How old a host's stored preflight memory reading may be before "
                + "it stops counting as a budget. Past this, placement will not choose "
                + "the host and says by name that it needs a preflight; workloads already "
                + "on it are untouched. 0 or less removes the bound -- an explicit choice "
                + "to place against a reading of any age")
            .build();
    }

    // --- Host health: what the stored heartbeat is allowed to be worth ---
    public abstract class Hosts {
        public static final SettingGroup GROUP = HOHENHEIM.createGroup("hosts")
            .label("Host health")
            .describe("How long a host may go without answering before the control plane "
                + "stops treating its stored state as current")
            .icon("heart-pulse");

        public static final SettingDefinition<Integer> CONTACT_MAX_AGE_MINUTES = GROUP
            .buildSetting("contact_max_age_minutes", Integer.class)
            .defaultValue(180)
            .label("Host contact maximum age (minutes)")
            .description("How long ago a host's daemon may last have ANSWERED before new "
                + "workloads stop being placed on it. Every sweep that reaches a daemon "
                + "refreshes this: the Incus presence sweep every 15 minutes, the Docker "
                + "reconcile hourly, plus every preflight and live overview. Past the "
                + "bound placement refuses the host BY NAME and picks another; workloads "
                + "already on it are untouched and every stop, destroy and cleanup path "
                + "stays open. Keep it well above an hour so one missed sweep is not a "
                + "refusal. 0 or less removes the bound -- an explicit choice to place "
                + "onto a host nobody has heard from")
            .build();
    }

    // --- Incus daemons shared with other controllers ---
    public abstract class Incus {
        public static final SettingGroup GROUP = HOHENHEIM.createGroup("incus")
            .label("Incus hosts")
            .describe("What this controller may do to the shared objects OTHER hohenheim "
                + "controllers left on an Incus daemon it also uses")
            .icon("server");

        public static final SettingDefinition<Boolean> REAP_DEPARTED_CONTROLLERS = GROUP
            .buildSetting("reap_departed_controllers", Boolean.class)
            .defaultValue(true)
            .label("Reap departed controllers' shared objects")
            .description("Remove the isolation ACL, extra-NIC bridge and presence marker "
                + "of a controller that left a presence stamp on this daemon, has not "
                + "refreshed it for the grace period, and has nothing referencing those "
                + "objects. Objects with NO presence stamp are never removed by this, "
                + "whatever their age. Off makes every sweep report-only")
            .build();

        public static final SettingDefinition<Integer> CONTROLLER_PRESENCE_GRACE_HOURS = GROUP
            .buildSetting("controller_presence_grace_hours", Integer.class)
            .defaultValue(168)
            .label("Controller presence grace (hours)")
            .description("How long another controller's presence stamp may go unrefreshed "
                + "before its unreferenced shared objects count as abandoned. A live "
                + "controller refreshes its stamp every 30 minutes, so this is also how "
                + "many consecutive refresh failures it may survive. 0 or less disables "
                + "reaping entirely -- expiry can then never be proven")
            .build();
    }

    // --- Instance networking (public game-port pre-allocation) ---
    public abstract class Instances {
        public static final SettingGroup GROUP = HOHENHEIM.createGroup("instances")
            .label("Instances")
            .describe("Instance-tier runtime policy: the host-port window public/UDP "
                + "publications pre-allocate from")
            .icon("box");

        public static final SettingDefinition<Integer> PUBLIC_PORT_FIRST = GROUP
            .buildSetting("public_port_first", Integer.class)
            .defaultValue(30000)
            .description("First host port of the pre-allocation window for declared "
                + "public/UDP instance publications. Keep the window clear of the kernel "
                + "ephemeral range (32768-60999) and of hohenheim.proxy.first_port's "
                + "managed-process window")
            .build();

        public static final SettingDefinition<Integer> PUBLIC_PORT_COUNT = GROUP
            .buildSetting("public_port_count", Integer.class)
            .defaultValue(2000)
            .description("Size of the pre-allocation window; allocation refuses loudly "
                + "when every port in it is claimed or observed bound")
            .build();
    }

    // --- Sandboxed builders ---
    public abstract class Builds {
        public static final SettingGroup GROUP = HOHENHEIM.createGroup("builds")
            .label("Builders")
            .describe("The sandbox every tenant build runs in: builder image, and the "
                + "CPU/memory/disk/time/PID quota one build may consume")
            .icon("hammer");

        // AIDEV-NOTE: a DAEMONLESS builder is the whole point and this setting must never
        // be pointed at one that needs the Docker socket. Kaniko builds a Dockerfile from
        // inside an ordinary container -- no daemon, no privileged mode, no userns -- and
        // was MEASURED to work under the full ContainerHardening baseline (drop-ALL plus
        // the SERVICE set, no-new-privileges, the pids cap). "docker:dind" or anything
        // else that wants /var/run/docker.sock cannot be made to work here: the socket is
        // a host bind mount and createContainer refuses those structurally.
        public static final SettingDefinition<String> BUILDER_IMAGE = GROUP
            .buildSetting("builder_image", String.class)
            .defaultValue("gcr.io/kaniko-project/executor:v1.23.2")
            .description("Daemonless image-builder used for Dockerfile builds. It must be "
                + "able to build without the Docker daemon: the sandbox has no socket, no "
                + "privileges and no route to the control plane")
            .build();

        // AIDEV-NOTE: the DETECTOR image is deliberately a plain base image, not a
        // "nixpacks image": the pinned STATIC nixpacks binary is staged into it at phase
        // start (NixpacksDistribution), so the image only has to provide a shell. The
        // phase runs with NO network at all -- detection parses hostile tenant files and
        // has no business fetching anything.
        public static final SettingDefinition<String> DETECTOR_IMAGE = GROUP
            .buildSetting("detector_image", String.class)
            .defaultValue("alpine:3.21")
            .description("Base image the nixpacks detection phase runs in. It only needs "
                + "a POSIX shell; the pinned nixpacks binary is staged in by the sandbox "
                + "and the phase runs without any network")
            .build();

        public static final SettingDefinition<String> NIXPACKS_VERSION = GROUP
            .buildSetting("nixpacks_version", String.class)
            .defaultValue("1.41.0")
            .description("Pinned nixpacks release the buildpack lane detects with. Bump "
                + "it together with nixpacks_sha256 -- a mismatched pair refuses every "
                + "nixpacks build")
            .build();

        public static final SettingDefinition<String> NIXPACKS_SHA256 = GROUP
            .buildSetting("nixpacks_sha256", String.class)
            .defaultValue("0f55de7874507b9cf7502113120bd96f2ab6979f78d10eaf2eb2ade9207b3af6")
            .description("sha256 of the pinned nixpacks x86_64-musl release archive. The "
                + "download is verified against it before anything is extracted; a "
                + "mismatch refuses the build")
            .build();

        public static final SettingDefinition<Double> CPU_LIMIT = GROUP
            .buildSetting("cpu_limit", Double.class)
            .defaultValue(2.0)
            .description("CPUs one build may use (1.5 = one and a half cores)")
            .build();

        public static final SettingDefinition<Integer> MEMORY_LIMIT_MB = GROUP
            .buildSetting("memory_limit_mb", Integer.class)
            .defaultValue(2048)
            .suffix("MiB")
            .description("Memory ceiling of one build container; the kernel OOM-kills the "
                + "build rather than the host")
            .build();

        public static final SettingDefinition<Integer> DISK_LIMIT_MB = GROUP
            .buildSetting("disk_limit_mb", Integer.class)
            .defaultValue(4096)
            .suffix("MiB")
            .description("Ceiling on the build container's writable layer, on the build "
                + "context pushed in and on the artifact read back out. A watchdog polls "
                + "the daemon's own size accounting and kills a build that crosses it -- "
                + "an unbounded build is a one-line host-filling denial of service")
            .build();

        public static final SettingDefinition<Integer> TIMEOUT_SECONDS = GROUP
            .buildSetting("timeout_seconds", Integer.class)
            .defaultValue(900)
            .suffix("s")
            .description("Wall-clock ceiling on one build; the sandbox kills and removes "
                + "the container when it is crossed and the operation records timed_out")
            .build();

        public static final SettingDefinition<Integer> PIDS_LIMIT = GROUP
            .buildSetting("pids_limit", Integer.class)
            .defaultValue(256)
            .description("Process ceiling inside one build container. This can only ever "
                + "TIGHTEN hohenheim.security.container_pids_limit, never raise it")
            .build();

        public static final SettingDefinition<Integer> MAX_ARTIFACT_MB = GROUP
            .buildSetting("max_artifact_mb", Integer.class)
            .defaultValue(1024)
            .suffix("MiB")
            .description("Upper bound in MiB for the image tar read back out of a build "
                + "sandbox. The read is buffered through controller memory (the same "
                + "limitation snapshot capture has), so this cap is the heap guard: a "
                + "larger artifact FAILS the build instead of taking the controller down")
            .build();

        public static final SettingDefinition<Integer> LOG_LIMIT_KB = GROUP
            .buildSetting("log_limit_kb", Integer.class)
            .defaultValue(512)
            .suffix("KiB")
            .description("Captured build log kept on the build operation. Output past the "
                + "cap is dropped with a marker line -- a build that prints forever must "
                + "not fill the control-plane database")
            .build();

        public static final SettingDefinition<Integer> HISTORY_PER_OWNER = GROUP
            .buildSetting("history_per_owner", Integer.class)
            .defaultValue(50)
            .description("Build operations kept per owning record; older rows are pruned "
                + "when a build completes")
            .build();
    }

    // --- Health-gated releases ---
    public abstract class Releases {
        public static final SettingGroup GROUP = HOHENHEIM.createGroup("releases")
            .label("Releases")
            .describe("The health gate every Docker-site release passes before it may "
                + "take traffic, and how long the superseded release drains before its stop")
            .icon("rocket");

        public static final SettingDefinition<Integer> PROBE_TIMEOUT_SECONDS = GROUP
            .buildSetting("probe_timeout_seconds", Integer.class)
            .defaultValue(60)
            .suffix("s")
            .description("How long a candidate release may take to answer its first "
                + "healthy HTTP response. A candidate that never does is destroyed and "
                + "the operation records failed; the prior release keeps serving")
            .build();

        public static final SettingDefinition<Integer> PROBE_INTERVAL_MS = GROUP
            .buildSetting("probe_interval_ms", Integer.class)
            .defaultValue(500)
            .suffix("ms")
            .description("Pause between health-probe attempts against the candidate's "
                + "published loopback port")
            .build();

        public static final SettingDefinition<Integer> DRAIN_SECONDS = GROUP
            .buildSetting("drain_seconds", Integer.class)
            .defaultValue(15)
            .suffix("s")
            .description("How long the superseded release keeps running after traffic "
                + "switched, so requests pinned to the outgoing routing generation "
                + "finish against a live upstream before the workload stops")
            .build();

        public static final SettingDefinition<Integer> HISTORY_PER_SITE = GROUP
            .buildSetting("history_per_site", Integer.class)
            .defaultValue(50)
            .description("Release operations kept per owning record; older rows are "
                + "pruned when an operation completes")
            .build();
    }

    // --- Preview deployments ---
    public abstract class Previews {
        public static final SettingGroup GROUP = HOHENHEIM.createGroup("previews")
            .label("Preview deployments")
            .describe("Bounded-lifetime per-branch preview environments of git-sourced "
                + "Docker sites, served on generated hostnames under one base domain")
            .icon("flask");

        public static final SettingDefinition<String> BASE_DOMAIN = GROUP
            .buildSetting("base_domain", String.class)
            .description("The domain generated preview hostnames live under "
                + "(<site>--<ref>.<base_domain>). Previews REFUSE to deploy while this "
                + "is unset. TLS rides a wildcard certificate over this domain; previews "
                + "never request their own certificates")
            .build();

        public static final SettingDefinition<Integer> LIFETIME_MINUTES = GROUP
            .buildSetting("lifetime_minutes", Integer.class)
            .defaultValue(1440)
            .suffix("min")
            .description("How long a preview lives before the expiry sweep reclaims its "
                + "instance, hostname and DNS rows. Redeploying the same ref extends the "
                + "window; values below 1 fall back to the default")
            .build();

        public static final SettingDefinition<Integer> MAX_PER_OWNER = GROUP
            .buildSetting("max_per_owner", Integer.class)
            .defaultValue(3)
            .description("Concurrent live previews one owner (a manage-grant subject "
                + "set; a project is one owner) may hold, enforced through the atomic "
                + "quota ledger. 0 or less means NO cap; usage is charged either way")
            .build();
    }

    // --- Instance file manager ---
    public abstract class Files {
        public static final SettingGroup GROUP = HOHENHEIM.createGroup("files")
            .label("Instance files")
            .describe("Bounds on the per-instance file manager (browse, edit, upload, download)")
            .icon("folder-tree");

        public static final SettingDefinition<Integer> MAX_FILE_KB = GROUP
            .buildSetting("max_file_kb", Integer.class)
            .defaultValue(8192)
            .description("Upper bound in KiB for one file read or written through the file "
                + "manager. Enforced DURING the transfer in both directions: an oversized "
                + "download aborts mid-read and an oversized upload is refused before a "
                + "single byte reaches the workload, so a partial file never lands")
            .build();

        public static final SettingDefinition<Integer> MAX_ENTRIES = GROUP
            .buildSetting("max_entries", Integer.class)
            .defaultValue(2000)
            .description("Upper bound on the entries one directory listing returns. A "
                + "directory with more refuses to list rather than returning a truncated "
                + "listing that reads like a complete one")
            .build();
    }

    // --- Instance snapshots and backups ---
    public abstract class Backup {
        public static final SettingGroup GROUP = HOHENHEIM.createGroup("backup")
            .label("Instance backups")
            .describe("Instance snapshots (host-local) and encrypted off-host backup exports")
            .icon("box-archive");

        public static final SettingDefinition<String> SNAPSHOT_PATH = GROUP
            .buildSetting("snapshot_path", String.class)
            .defaultValue("data/snapshots")
            .filesystemPath(HohenheimPaths.SERVER_FILES, PathKind.DIRECTORY)
            .description("Directory for instance snapshots. A snapshot is NOT a backup: it "
                + "lives on the controller host and dies with it; only backup exports "
                + "leave the failure domain")
            .build();

        public static final SettingDefinition<String> STAGING_PATH = GROUP
            .buildSetting("staging_path", String.class)
            .defaultValue("data/backup-staging")
            .filesystemPath(HohenheimPaths.SERVER_FILES, PathKind.DIRECTORY)
            .description("Working directory for building and verifying backup archives "
                + "before they are uploaded to their target")
            .build();

        public static final SettingDefinition<Integer> RETENTION = GROUP
            .buildSetting("retention", Integer.class)
            .defaultValue(7)
            .description("Number of completed backups to keep per instance (the managed-"
                + "database retention pattern); 0 or less keeps everything")
            .build();

        public static final SettingDefinition<Integer> SNAPSHOT_RETENTION = GROUP
            .buildSetting("snapshot_retention", Integer.class)
            .defaultValue(7)
            .description("Number of completed snapshots to keep per instance, pruned when a "
                + "new one completes (the same count-based rule as backup retention); 0 or "
                + "less keeps everything, which on a pool-backed host is a disk-exhaustion "
                + "path nothing else closes")
            .build();

        public static final SettingDefinition<Integer> MAX_ARCHIVE_MB = GROUP
            .buildSetting("max_archive_mb", Integer.class)
            .defaultValue(1024)
            .description("Upper bound in MiB for one captured volume archive. Captures are "
                + "buffered through controller memory (streaming transports are a later "
                + "wave), so this cap is what protects the heap -- a volume larger than "
                + "this refuses to snapshot instead of taking the controller down")
            .build();
    }
}
