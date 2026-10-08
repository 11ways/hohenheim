package be.elevenways.hohenheim;

import be.elevenways.protoblast.common.registry.Identifier;

/**
 * Canonical Hawkeye template identifiers Hohenheim's Java code renders.

 * AIDEV-NOTE: template ids keep the project token and dashed paths (hawkeye:template is an ANY registry), so they are
 * spelled with Identifier.of here, the one class the literal-namespace guard exempts for them; list-cell renderers are
 * strings because a column names its renderer as one.

 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class HohenheimTemplateIds {

    /** The proxy's branded error page. */
    public static final Identifier ERROR_PAGE = Identifier.of("hohenheim", "hohenheim/error");

    // Admin pages under cms/.
    public static final Identifier ACCESS_LIST_RULES = Identifier.of("hohenheim", "cms/access-list-rules");
    public static final Identifier DATABASE_CREDENTIALS = Identifier.of("hohenheim", "cms/database-credentials");
    public static final Identifier DATABASE_RESTORE = Identifier.of("hohenheim", "cms/database-restore");
    public static final Identifier DNS_ZONE_FILE = Identifier.of("hohenheim", "cms/dns-zone-file");
    public static final Identifier DNS_ZONE_RECORDS = Identifier.of("hohenheim", "cms/dns-zone-records");
    public static final Identifier DNS_ZONE_REMOTE_RECORDS = Identifier.of("hohenheim", "cms/dns-zone-remote-records");
    public static final Identifier DNS_ZONE_SECONDARIES = Identifier.of("hohenheim", "cms/dns-zone-secondaries");
    public static final Identifier INBOX = Identifier.of("hohenheim", "cms/inbox");
    public static final Identifier INSTANCE_CONSOLE = Identifier.of("hohenheim", "cms/instance-console");
    public static final Identifier INSTANCE_DATABASES = Identifier.of("hohenheim", "cms/instance-databases");
    public static final Identifier INSTANCE_DEPLOYMENTS = Identifier.of("hohenheim", "cms/instance-deployments");
    public static final Identifier INSTANCE_DEVICES = Identifier.of("hohenheim", "cms/instance-devices");
    public static final Identifier INSTANCE_EXEC = Identifier.of("hohenheim", "cms/instance-exec");
    public static final Identifier INSTANCE_FILES = Identifier.of("hohenheim", "cms/instance-files");
    public static final Identifier INSTANCE_FRAMEBUFFER = Identifier.of("hohenheim", "cms/instance-framebuffer");
    public static final Identifier INSTANCE_FROM_TEMPLATE = Identifier.of("hohenheim", "cms/instance-from-template");
    public static final Identifier PUT_ONLINE = Identifier.of("hohenheim", "cms/put-online");
    public static final Identifier INSTANCE_MIGRATE = Identifier.of("hohenheim", "cms/instance-migrate");
    public static final Identifier INSTANCE_PROVISIONING = Identifier.of("hohenheim", "cms/instance-provisioning");
    public static final Identifier INSTANCE_SCHEDULE_STEPS = Identifier.of("hohenheim", "cms/instance-schedule-steps");
    public static final Identifier INSTANCE_SHELL = Identifier.of("hohenheim", "cms/instance-shell");
    public static final Identifier INSTANCE_STATS = Identifier.of("hohenheim", "cms/instance-stats");
    public static final Identifier INSTANCE_VOLUMES = Identifier.of("hohenheim", "cms/instance-volumes");
    public static final Identifier SERVER_MEDIA = Identifier.of("hohenheim", "cms/server-media");
    public static final Identifier SITE_DEV_SESSIONS = Identifier.of("hohenheim", "cms/site-dev-sessions");
    public static final Identifier SPAMSERVICE_OVERVIEW = Identifier.of("hohenheim", "cms/spamservice-overview");
    public static final Identifier SPAMSERVICE_REPUTATION = Identifier.of("hohenheim", "cms/spamservice-reputation");
    public static final Identifier SPAMSERVICE_SAMPLE_ANALYSIS =
        Identifier.of("hohenheim", "cms/spamservice-sample-analysis");
    public static final Identifier STACK_DEPLOYMENTS = Identifier.of("hohenheim", "cms/stack-deployments");
    public static final Identifier STACK_SERVICES = Identifier.of("hohenheim", "cms/stack-services");
    public static final Identifier TEMPLATE_CONTENTS = Identifier.of("hohenheim", "cms/template-contents");
    public static final Identifier TEMPLATE_IMPORT = Identifier.of("hohenheim", "cms/template-import");

    // Dashboard and overview widget display templates (HohenheimWidgets).
    public static final Identifier WIDGET_ATTENTION = Identifier.of("hohenheim", "cms/widget-attention");
    public static final Identifier WIDGET_ONBOARDING = Identifier.of("hohenheim", "cms/widget-onboarding");
    public static final Identifier WIDGET_ONBOARDING_CHECKLIST =
        Identifier.of("hohenheim", "cms/widget-onboarding-checklist");
    public static final Identifier WIDGET_HOST_STATE = Identifier.of("hohenheim", "cms/widget-host-state");
    public static final Identifier WIDGET_HOST_TRUST = Identifier.of("hohenheim", "cms/widget-host-trust");
    public static final Identifier WIDGET_HOST_PREFLIGHT = Identifier.of("hohenheim", "cms/widget-host-preflight");
    public static final Identifier WIDGET_HOST_WORKLOADS = Identifier.of("hohenheim", "cms/widget-host-workloads");
    public static final Identifier WIDGET_INSTANCE_ENDPOINTS =
        Identifier.of("hohenheim", "cms/widget-instance-endpoints");
    public static final Identifier WIDGET_APP_ADDRESSES = Identifier.of("hohenheim", "cms/widget-app-addresses");
    public static final Identifier WIDGET_APP_PROTECTION = Identifier.of("hohenheim", "cms/widget-app-protection");
    public static final Identifier WIDGET_APPS = Identifier.of("hohenheim", "cms/widget-apps");
    public static final Identifier WIDGET_TENANT_USAGE = Identifier.of("hohenheim", "cms/widget-tenant-usage");

    // List-cell partials; a column names one through its String renderer.
    public static final String CELL_ACTIVITY_RECORD = "hohenheim:cms/cell/activity-record";
    public static final String CELL_BAN_STATE = "hohenheim:cms/cell/ban-state";
    public static final String CELL_DOMAIN_CERTIFICATE = "hohenheim:cms/cell/domain-certificate";
    public static final String CELL_STATE_LINE = "hohenheim:cms/cell/state-line";
    public static final String CELL_HOST_STATUS = "hohenheim:cms/cell/host-status";
    public static final String CELL_HOST_MEMORY = "hohenheim:cms/cell/host-memory";
    public static final String CELL_MANAGED_BY = "hohenheim:cms/cell/managed-by";
    public static final String CELL_INSTALL_STATE = "hohenheim:cms/cell/install-state";
    public static final String CELL_SITE_HOSTNAMES = "hohenheim:cms/cell/site-hostnames";
    public static final String CELL_SITE_TLS = "hohenheim:cms/cell/site-tls";
    public static final String CELL_SITE_UPSTREAM = "hohenheim:cms/cell/site-upstream";

    private HohenheimTemplateIds() {
    }
}
