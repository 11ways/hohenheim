package be.elevenways.hohenheim;

/**
 * The one declaring home of every route slug Hohenheim spells: its two panels, their entries, sidebar clusters and
 * record tabs.
 *
 * AIDEV-NOTE: a constant is named after its value (upper case, dashes as underscores), so a slug has exactly one
 * spelling and a name says what the URL reads. Entries and clusters share one namespace per panel; a record tab is
 * local to the record it sits on, so tabs live in {@link Tab} and may repeat across records ("deployments"). Every
 * route, tab and entry declaration reads a constant here, never a literal and never a per-class alias (DD8). It lives
 * in common so endpoint paths, sources and the server panels read the same declaration. Framework slugs (the record
 * overview, settings, task admin) stay their owners'.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class HohenheimSlugs {

    /** The operator panel, served at /admin. */
    public static final String ADMIN = "admin";

    /** The delegated tenant panel, served at /manage. */
    public static final String MANAGE = "manage";

    public static final String ACCESS_LISTS = "access-lists";
    public static final String ACCESS_RULES = "access-rules";
    public static final String APPS = "apps";
    public static final String AUTH_PROVIDERS = "auth-providers";
    public static final String BACKUP_TARGETS = "backup-targets";
    public static final String BANS = "bans";
    public static final String BUILDS = "builds";
    public static final String CERTIFICATES = "certificates";
    public static final String DASHBOARD = "dashboard";
    public static final String DATABASE_ENGINES = "database-engines";
    public static final String DATABASES = "databases";
    public static final String DNS_PEERS = "dns-peers";
    public static final String DNS_RECORDS = "dns-records";
    public static final String DNS_ZONE_PEERS = "dns-zone-peers";
    public static final String DNS_ZONES = "dns-zones";
    public static final String DOMAINS = "domains";
    public static final String ENVIRONMENT_VARIABLES = "environment-variables";
    public static final String ENVIRONMENTS = "environments";
    public static final String GAME_DOMAINS = "game-domains";
    public static final String GIT_PROVIDERS = "git-providers";
    public static final String INBOX = "inbox";
    public static final String INSTANCE_BACKUPS = "instance-backups";
    public static final String INSTANCE_DATABASES = "instance-databases";
    public static final String INSTANCE_DEVICES = "instance-devices";
    public static final String INSTANCE_FILES = "instance-files";
    public static final String INSTANCE_QUOTAS = "instance-quotas";
    public static final String INSTANCE_SCHEDULE_RUNS = "instance-schedule-runs";
    public static final String INSTANCE_SCHEDULE_STEPS = "instance-schedule-steps";
    public static final String INSTANCE_SCHEDULES = "instance-schedules";
    public static final String INSTANCE_SNAPSHOTS = "instance-snapshots";
    public static final String INSTANCE_TEMPLATE_DATABASES = "instance-template-databases";
    public static final String INSTANCE_TEMPLATE_FILES = "instance-template-files";
    public static final String INSTANCE_TEMPLATE_VARIABLES = "instance-template-variables";
    public static final String INSTANCE_TEMPLATE_VOLUMES = "instance-template-volumes";
    public static final String INSTANCE_TEMPLATES = "instance-templates";
    public static final String INSTANCE_TEMPLATES_IMPORT = "instance-templates-import";
    public static final String INSTANCE_VARIABLES = "instance-variables";
    public static final String INSTANCE_VOLUMES = "instance-volumes";
    public static final String INSTANCES = "instances";
    public static final String INSTANCES_FROM_TEMPLATE = "instances-from-template";
    public static final String NOTIFICATIONS = "notifications";
    public static final String PREVIEWS = "previews";
    public static final String PROJECT_MEMBERS = "project-members";
    public static final String PROJECTS = "projects";
    public static final String PROTECTED_PATHS = "protected-paths";
    public static final String PUT_ONLINE = "put-online";
    public static final String RECONCILE_FINDINGS = "reconcile-findings";
    public static final String RELEASED_CLAIMS = "released-claims";
    public static final String RELEASES = "releases";
    public static final String RUNTIME_IMAGES = "runtime-images";
    public static final String SERVERS = "servers";
    public static final String SITES = "sites";
    public static final String SPAMSERVICE = "spamservice";
    public static final String SPAMSERVICE_CLIENTS = "spamservice-clients";
    public static final String SPAMSERVICE_INSTALLATION = "spamservice-installation";
    public static final String SPAMSERVICE_KEYS = "spamservice-keys";
    public static final String SPAMSERVICE_REPUTATION = "spamservice-reputation";
    public static final String SPAMSERVICE_SAMPLES = "spamservice-samples";
    public static final String SPAMSERVICE_SECURITY_EVENTS = "spamservice-security-events";
    public static final String SPAMSERVICE_WORDS = "spamservice-words";
    public static final String STACK_FILES = "stack-files";
    public static final String STACK_SERVICES = "stack-services";
    public static final String STACKS = "stacks";

    private HohenheimSlugs() {
    }

    /** The sidebar clusters: each is the row's URL, which lands on the first member the viewer may open. */
    public static final class Cluster {

        public static final String ACCESS = "access";
        public static final String CONFIGURE = "configure";
        public static final String DOMAIN_NAMES = "domain-names";
        public static final String LOG = "log";
        public static final String TEAM = "team";

        private Cluster() {
        }
    }

    /** The record tabs, each local to the record it sits on. */
    public static final class Tab {

        public static final String ANALYSIS = "analysis";
        public static final String BACKUPS = "backups";
        public static final String CONSOLE = "console";
        public static final String CONTENTS = "contents";
        public static final String CREDENTIALS = "credentials";
        public static final String DATABASES = "databases";
        public static final String DEPLOYMENTS = "deployments";
        public static final String DEV_SESSIONS = "dev-sessions";
        public static final String DEVICES = "devices";
        public static final String EXEC = "exec";
        public static final String FILES = "files";
        public static final String FRAMEBUFFER = "framebuffer";
        public static final String INSTALL_MEDIA = "install-media";
        public static final String KEYS = "keys";
        public static final String MIGRATE = "migrate";
        public static final String PROVISIONING = "provisioning";
        public static final String RECORDS = "records";
        public static final String RESTORE = "restore";
        public static final String RULES = "rules";
        public static final String SECONDARIES = "secondaries";
        public static final String SERVICES = "services";
        public static final String SHELL = "shell";
        public static final String STATS = "stats";
        public static final String STEPS = "steps";
        public static final String VOLUMES = "volumes";
        public static final String ZONEFILE = "zonefile";

        private Tab() {
        }
    }
}
