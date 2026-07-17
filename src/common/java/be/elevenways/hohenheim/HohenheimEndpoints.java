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

    // --- Let's Encrypt request (POST for the CMS certificate-request page) ---
    public static final Endpoint<Object> CERTIFICATES_REQUEST = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "certificates_request"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("admin").addDelimiter().addStatic("certificates-request").build())
        .rateLimit(LE_REQUEST_LIMIT)
        .build();

    // --- DNS zone-file import (POST for the CMS zone-file tab) ---
    public static final Endpoint<Object> DNS_ZONE_IMPORT = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "dns_zone_import"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("admin").addDelimiter().addStatic("dns-zones").addDelimiter()
            .addParameter(ZONE_ID).addDelimiter().addStatic("zonefile").build())
        .build();

    // --- Certificate PEM bundle download ---
    public static final Endpoint<Object> CERTIFICATES_DOWNLOAD = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "certificates_download"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("certificates").addDelimiter().addParameter(CERT_ID)
            .addDelimiter().addStatic("download").build())
        .rateLimit(DOWNLOAD_LIMIT)
        .build();

    // --- Managed database dump download / restore upload ---
    public static final Endpoint<Object> DATABASES_BACKUP = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "databases_backup"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("databases").addDelimiter().addParameter(DATABASE_NAME)
            .addDelimiter().addStatic("backup").build())
        .rateLimit(DATABASE_IO_LIMIT)
        .build();

    public static final Endpoint<Object> DATABASES_RESTORE = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "databases_restore"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("databases").addDelimiter().addParameter(DATABASE_NAME)
            .addDelimiter().addStatic("restore").build())
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

    // --- Root: the admin panel IS the app, so / lands on it ---
    public static final Endpoint<Object> ROOT = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "root"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET).build())
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

    // --- Health check ---
    public static final Endpoint<Object> HEALTH = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "health"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("api").addDelimiter().addStatic("health").build())
        .build();

    // --- Interactive process terminal ---
    public static final WebSocketEndpoint PROCESS_TERMINAL = WebSocketEndpoint.builder()
        .identifier(Identifier.of("hohenheim", "process_terminal"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("ws").addDelimiter().addStatic("terminal")
            .addDelimiter().addParameter(SITE_ID)
            .addDelimiter().addParameter(PID).build())
        .handler(session -> null) // Placeholder: set in HohenheimHandlers.init()
        .build();

    public static void init() {
        // Static fields are initialized when this method is called
    }
}
