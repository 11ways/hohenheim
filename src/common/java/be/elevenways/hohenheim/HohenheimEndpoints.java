package be.elevenways.hohenheim;

import be.elevenways.protoblast.common.http.HttpMethod;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.routing.Endpoint;
import be.elevenways.zenit.common.routing.EndpointRoute;
import be.elevenways.zenit.common.routing.ParameterDefinition;
import be.elevenways.zenit.common.routing.WebSocketEndpoint;

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

    // --- Settings write path (static route: wins precedence over the panel's
    // dynamic POST /{panel}/{resource} singleton-submit route) ---
    public static final Endpoint<Object> SETTINGS_UPDATE = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "settings_update"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("admin").addDelimiter().addStatic("settings").build())
        .build();

    // --- Let's Encrypt request (POST for the CMS certificate-request page) ---
    public static final Endpoint<Object> CERTIFICATES_REQUEST = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "certificates_request"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("admin").addDelimiter().addStatic("certificates-request").build())
        .build();

    // --- Certificate PEM bundle download ---
    public static final Endpoint<Object> CERTIFICATES_DOWNLOAD = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "certificates_download"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("certificates").addDelimiter().addParameter(CERT_ID)
            .addDelimiter().addStatic("download").build())
        .build();

    // --- Managed database dump download / restore upload ---
    public static final Endpoint<Object> DATABASES_BACKUP = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "databases_backup"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("databases").addDelimiter().addParameter(DATABASE_NAME)
            .addDelimiter().addStatic("backup").build())
        .build();

    public static final Endpoint<Object> DATABASES_RESTORE = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "databases_restore"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("databases").addDelimiter().addParameter(DATABASE_NAME)
            .addDelimiter().addStatic("restore").build())
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
