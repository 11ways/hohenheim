package be.elevenways.hohenheim;

import be.elevenways.protoblast.common.http.HttpMethod;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.routing.Endpoint;
import be.elevenways.zenit.common.routing.EndpointRoute;
import be.elevenways.zenit.common.routing.ParameterDefinition;
import be.elevenways.zenit.common.routing.RateLimitPolicy;
import be.elevenways.zenit.common.routing.WebSocketEndpoint;
import be.elevenways.zenit.common.security.Permission;

import java.time.Duration;

/**
 * Host-declared endpoints that complement the zenit-cms panel routes: file
 * downloads/uploads, process control, the terminal WebSocket, the settings
 * write path, and the health check. Regular admin CRUD is owned by zenit-cms.
 */
public class HohenheimEndpoints {

    // --- Parameters ---
    public static final ParameterDefinition<Integer> SITE_ID = ParameterDefinition.builder(Integer.class)
        .name("siteId")
        .stringResolver(Integer::parseInt)
        .build();

    public static final ParameterDefinition<Long> PID = ParameterDefinition.builder(Long.class)
        .name("pid")
        .stringResolver(Long::parseLong)
        .build();

    public static final ParameterDefinition<Integer> CERT_ID = ParameterDefinition.builder(Integer.class)
        .name("certId")
        .stringResolver(Integer::parseInt)
        .build();

    public static final ParameterDefinition<String> DATABASE_NAME = ParameterDefinition.builder(String.class)
        .name("databaseName")
        .stringResolver(value -> value)
        .build();

    public static final ParameterDefinition<Integer> ZONE_ID = ParameterDefinition.builder(Integer.class)
        .name("zoneId")
        .stringResolver(Integer::parseInt)
        .build();

    public static final ParameterDefinition<String> DNS_ORIGIN = ParameterDefinition.builder(String.class)
        .name("dnsOrigin")
        .stringResolver(value -> value)
        .build();

    public static final ParameterDefinition<Integer> DNS_RECORD_ID = ParameterDefinition.builder(Integer.class)
        .name("dnsRecordId")
        .stringResolver(Integer::parseInt)
        .build();

    // --- Rate limits: expensive or upstream-quota-bound operations. ---
    // The LE request burns Let's Encrypt quota; db dump/restore stream whole
    // databases; deploys spawn builds. Keyed per principal (per IP for
    // anonymous rejects) so one runaway client cannot starve the rest.
    private static final RateLimitPolicy LE_REQUEST_LIMIT =
        RateLimitPolicy.of(5, Duration.ofHours(1))
            .keyBy(RateLimitPolicy.KeyBy.PRINCIPAL_OR_IP)
            .named("hh_le_request");

    private static final RateLimitPolicy DOWNLOAD_LIMIT =
        RateLimitPolicy.of(30, Duration.ofMinutes(1))
            .keyBy(RateLimitPolicy.KeyBy.PRINCIPAL_OR_IP)
            .named("hh_download");

    private static final RateLimitPolicy DATABASE_IO_LIMIT =
        RateLimitPolicy.of(5, Duration.ofMinutes(1))
            .keyBy(RateLimitPolicy.KeyBy.PRINCIPAL_OR_IP)
            .named("hh_db_io");

    private static final RateLimitPolicy DEPLOY_LIMIT =
        RateLimitPolicy.of(10, Duration.ofMinutes(1))
            .keyBy(RateLimitPolicy.KeyBy.PRINCIPAL_OR_IP)
            .named("hh_deploy");

    // The dyndns endpoint is public (token in HTTP Basic auth); keyed per IP so a
    // credential-guessing client cannot grind tokens. Routers poll every few
    // minutes, so a generous per-minute budget still leaves ample headroom.
    private static final RateLimitPolicy DYNDNS_LIMIT =
        RateLimitPolicy.of(30, Duration.ofMinutes(1))
            .keyBy(RateLimitPolicy.KeyBy.IP)
            .named("hh_dyndns");

    // --- Let's Encrypt request (POST for the CMS certificate-request page) ---
    public static final Endpoint<Object> CERTIFICATES_REQUEST = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "certificates_request"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("admin").addDelimiter().addStatic("certificates-request").build())
        .requiresPermission(Permission.of("hohenheim.admin.access"))
        .rateLimit(LE_REQUEST_LIMIT)
        .build();

    // --- DNS zone-file import (POST for the CMS zone-file tab) ---
    public static final Endpoint<Object> DNS_ZONE_IMPORT = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "dns_zone_import"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("admin").addDelimiter().addStatic("dns-zones").addDelimiter()
            .addParameter(ZONE_ID).addDelimiter().addStatic("zonefile").build())
        .requiresPermission(Permission.of("hohenheim.admin.access"))
        .build();

    // --- Certificate PEM bundle download ---
    public static final Endpoint<Object> CERTIFICATES_DOWNLOAD = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "certificates_download"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("certificates").addDelimiter().addParameter(CERT_ID)
            .addDelimiter().addStatic("download").build())
        .requiresPermission(Permission.of("hohenheim.admin.access"))
        .rateLimit(DOWNLOAD_LIMIT)
        .build();

    // --- Managed database dump download / restore upload ---
    public static final Endpoint<Object> DATABASES_BACKUP = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "databases_backup"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("databases").addDelimiter().addParameter(DATABASE_NAME)
            .addDelimiter().addStatic("backup").build())
        .requiresPermission(Permission.of("hohenheim.admin.access"))
        .rateLimit(DATABASE_IO_LIMIT)
        .build();

    public static final Endpoint<Object> DATABASES_RESTORE = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "databases_restore"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("databases").addDelimiter().addParameter(DATABASE_NAME)
            .addDelimiter().addStatic("restore").build())
        .requiresPermission(Permission.of("hohenheim.admin.access"))
        .rateLimit(DATABASE_IO_LIMIT)
        .build();

    // --- Deploy control (forms on the site Deployments tab) ---
    public static final Endpoint<Object> SITES_DEPLOY = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "sites_deploy"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("sites").addDelimiter().addParameter(SITE_ID)
            .addDelimiter().addStatic("deploy").build())
        .rateLimit(DEPLOY_LIMIT)
        .build();

    public static final Endpoint<Object> SITES_DEPLOY_CANCEL = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "sites_deploy_cancel"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("sites").addDelimiter().addParameter(SITE_ID)
            .addDelimiter().addStatic("deploy").addDelimiter().addStatic("cancel").build())
        .rateLimit(DEPLOY_LIMIT)
        .build();

    public static final Endpoint<Object> SITES_ROLLBACK = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "sites_rollback"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("sites").addDelimiter().addParameter(SITE_ID)
            .addDelimiter().addStatic("rollback").build())
        .rateLimit(DEPLOY_LIMIT)
        .build();

    // --- Process control (forms on the site Processes tab) ---
    public static final Endpoint<Object> SITES_PROCESS_START = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "sites_process_start"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("sites").addDelimiter().addParameter(SITE_ID)
            .addDelimiter().addStatic("processes").addDelimiter().addStatic("start").build())
        .build();

    public static final Endpoint<Object> SITES_PROCESS_KILL = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "sites_process_kill"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("sites").addDelimiter().addParameter(SITE_ID)
            .addDelimiter().addStatic("processes").addDelimiter().addParameter(PID)
            .addDelimiter().addStatic("kill").build())
        .build();

    public static final Endpoint<Object> SITES_PROCESS_ISOLATE = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "sites_process_isolate"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("sites").addDelimiter().addParameter(SITE_ID)
            .addDelimiter().addStatic("processes").addDelimiter().addParameter(PID)
            .addDelimiter().addStatic("isolate").build())
        .build();

    // --- Automation API (znit_ bearer keys via zenit-auth) ---
    public static final Endpoint<Object> API_SITES = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "api_sites"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("api").addDelimiter().addStatic("sites").build())
        .requiresPermission(Permission.of("hohenheim.admin.access"))
        .build();

    /** csrfExempt is safe: the handler refuses non-API-key principals, so an ambient session cookie can never act here. */
    public static final Endpoint<Object> API_SITES_DEPLOY = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "api_sites_deploy"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("api").addDelimiter().addStatic("sites")
            .addDelimiter().addParameter(SITE_ID)
            .addDelimiter().addStatic("deploy").build())
        .requiresPermission(Permission.of("hohenheim.admin.access"))
        .csrfExempt()
        .rateLimit(DEPLOY_LIMIT)
        .build();

    // --- DNS records peer/automation API (znit_ bearer keys; the edit-forwarding
    //     channel other Hohenheim instances use to edit zones this instance owns) ---
    public static final Endpoint<Object> API_DNS_RECORDS = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "api_dns_records"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("api").addDelimiter().addStatic("dns").addDelimiter().addStatic("zones")
            .addDelimiter().addParameter(DNS_ORIGIN)
            .addDelimiter().addStatic("records").build())
        .requiresPermission(Permission.of("hohenheim.admin.access"))
        .build();

    /** csrfExempt is safe: the handlers refuse non-API-key principals, so an ambient session cookie can never act here. */
    public static final Endpoint<Object> API_DNS_RECORD_CREATE = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "api_dns_record_create"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("api").addDelimiter().addStatic("dns").addDelimiter().addStatic("zones")
            .addDelimiter().addParameter(DNS_ORIGIN)
            .addDelimiter().addStatic("records").build())
        .requiresPermission(Permission.of("hohenheim.admin.access"))
        .csrfExempt()
        .build();

    public static final Endpoint<Object> API_DNS_RECORD_UPDATE = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "api_dns_record_update"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("api").addDelimiter().addStatic("dns").addDelimiter().addStatic("zones")
            .addDelimiter().addParameter(DNS_ORIGIN)
            .addDelimiter().addStatic("records").addDelimiter().addParameter(DNS_RECORD_ID).build())
        .requiresPermission(Permission.of("hohenheim.admin.access"))
        .csrfExempt()
        .build();

    public static final Endpoint<Object> API_DNS_RECORD_DELETE = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "api_dns_record_delete"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("api").addDelimiter().addStatic("dns").addDelimiter().addStatic("zones")
            .addDelimiter().addParameter(DNS_ORIGIN)
            .addDelimiter().addStatic("records").addDelimiter().addParameter(DNS_RECORD_ID)
            .addDelimiter().addStatic("delete").build())
        .requiresPermission(Permission.of("hohenheim.admin.access"))
        .csrfExempt()
        .build();

    // --- Remote-record edit forwarding (admin form POST on a SECONDARY zone's
    //     Records tab; forwards to the owning peer's API above) ---
    public static final Endpoint<Object> DNS_REMOTE_RECORD = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "dns_remote_record"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("admin").addDelimiter().addStatic("dns-zones").addDelimiter()
            .addParameter(ZONE_ID).addDelimiter().addStatic("remote-records").build())
        .requiresPermission(Permission.of("hohenheim.admin.access"))
        .build();

    // --- Dynamic DNS (dyndns2 update protocol; public, token in HTTP Basic auth) ---
    // No requiresPermission: the token IS the credential, verified by the handler.
    // csrfExempt because ddclient/routers cannot carry a CSRF token (GET, no cookie).
    public static final Endpoint<Object> DYNDNS_UPDATE = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "dyndns_update"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("nic").addDelimiter().addStatic("update").build())
        .csrfExempt()
        .rateLimit(DYNDNS_LIMIT)
        .build();

    // --- Health check ---
    public static final Endpoint<Object> HEALTH = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "health"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("api").addDelimiter().addStatic("health").build())
        .build();

    // --- Interactive process terminal ---

    /** How often a live terminal session's per-site manage grant is re-checked (revoked = 1008). */
    public static final long TERMINAL_REVALIDATION_INTERVAL_MS = 15_000;

    public static final WebSocketEndpoint PROCESS_TERMINAL = WebSocketEndpoint.builder()
        .identifier(Identifier.of("hohenheim", "process_terminal"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("ws").addDelimiter().addStatic("terminal")
            .addDelimiter().addParameter(SITE_ID)
            .addDelimiter().addParameter(PID).build())
        .requiresLogin()
        .revalidateEvery(TERMINAL_REVALIDATION_INTERVAL_MS)
        .handler(session -> null) // Placeholder: set in HohenheimHandlers.init(), at the MODULES stage
        .build();

    // --- Dev-tunnel registration (remote dev servers; token-authenticated in-band) ---

    /**
     * How often a registered tunnel's site/token/namespace authorization is
     * re-resolved (revoked = 1008).
     *
     * AIDEV-NOTE: the endpoint is deliberately DECLARED even though the handshake
     * is anonymous. This connection authenticates IN BAND (the register frame's
     * token, DevTunnelServerHandler.handleRegister), so
     * WebSocketRevalidator.intervalFor's identity test -- requiresAuthorization or a
     * non-anonymous handshake principal -- is false here and would start NO
     * revalidator at all, leaving a rotated token, a disabled site or a deleted site
     * with a live tunnel forever. Declaring an interval is what reaches the handler's
     * revalidate() hook, which is where the in-band credential is re-checked.
     */
    public static final long DEV_TUNNEL_REVALIDATION_INTERVAL_MS = TERMINAL_REVALIDATION_INTERVAL_MS;

    public static final WebSocketEndpoint DEV_TUNNEL = WebSocketEndpoint.builder()
        .identifier(Identifier.of("hohenheim", "dev_tunnel"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("ws").addDelimiter().addStatic("dev-tunnel").build())
        .revalidateEvery(DEV_TUNNEL_REVALIDATION_INTERVAL_MS)
        .handler(session -> null) // Placeholder: set in HohenheimHandlers.init(), at the MODULES stage
        .build();

    public static void init() {
        // Static fields are initialized when this method is called
    }
}
