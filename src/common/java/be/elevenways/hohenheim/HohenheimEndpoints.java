package be.elevenways.hohenheim;

import be.elevenways.protoblast.common.http.HttpMethod;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.routing.Endpoint;
import be.elevenways.zenit.common.routing.EndpointRoute;
import be.elevenways.zenit.common.routing.PageEndpoint;
import be.elevenways.zenit.common.routing.ParameterDefinition;
import be.elevenways.zenit.common.routing.WebSocketEndpoint;

/**
 * HTTP endpoint definitions for the Hohenheim admin interface.
 */
public class HohenheimEndpoints {

    // --- Parameters ---
    public static final ParameterDefinition<Integer> SITE_ID = ParameterDefinition.builder(Integer.class)
        .name("siteId")
        .stringResolver(Integer::parseInt)
        .build();

    public static final ParameterDefinition<Integer> DOMAIN_ID = ParameterDefinition.builder(Integer.class)
        .name("domainId")
        .stringResolver(Integer::parseInt)
        .build();

    // --- Dashboard ---
    public static final PageEndpoint DASHBOARD = Endpoint.pageBuilder()
        .identifier(Identifier.of("hohenheim", "dashboard"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET).build())
        .build();

    // --- Sites ---
    public static final PageEndpoint SITES_LIST = Endpoint.pageBuilder()
        .identifier(Identifier.of("hohenheim", "sites_list"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET).addStatic("sites").build())
        .build();

    public static final PageEndpoint SITES_CREATE_FORM = Endpoint.pageBuilder()
        .identifier(Identifier.of("hohenheim", "sites_create_form"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET).addStatic("sites").addDelimiter().addStatic("create").build())
        .build();

    // POST returns either RenderTemplateResult or RedirectResult (incompatible generic types)
    public static final Endpoint<Object> SITES_CREATE = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "sites_create"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST).addStatic("sites").addDelimiter().addStatic("create").build())
        .build();

    public static final PageEndpoint SITES_EDIT = Endpoint.pageBuilder()
        .identifier(Identifier.of("hohenheim", "sites_edit"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET).addStatic("sites").addDelimiter().addParameter(SITE_ID).build())
        .build();

    public static final Endpoint<Object> SITES_UPDATE = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "sites_update"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST).addStatic("sites").addDelimiter().addParameter(SITE_ID).build())
        .build();

    public static final Endpoint<Object> SITES_TOGGLE = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "sites_toggle"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("sites").addDelimiter().addParameter(SITE_ID)
            .addDelimiter().addStatic("toggle").build())
        .build();

    public static final Endpoint<Object> SITES_DELETE = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "sites_delete"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST).addStatic("sites").addDelimiter().addParameter(SITE_ID).addDelimiter().addStatic("delete").build())
        .build();

    // --- Site Domains ---
    public static final Endpoint<Object> SITES_ADD_DOMAIN = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "sites_add_domain"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("sites").addDelimiter().addParameter(SITE_ID)
            .addDelimiter().addStatic("domains").build())
        .build();

    public static final Endpoint<Object> SITES_EDIT_DOMAIN = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "sites_edit_domain"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("sites").addDelimiter().addParameter(SITE_ID)
            .addDelimiter().addStatic("domains").addDelimiter().addParameter(DOMAIN_ID)
            .build())
        .build();

    public static final Endpoint<Object> SITES_UPDATE_DOMAIN = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "sites_update_domain"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("sites").addDelimiter().addParameter(SITE_ID)
            .addDelimiter().addStatic("domains").addDelimiter().addParameter(DOMAIN_ID)
            .build())
        .build();

    public static final Endpoint<Object> SITES_DELETE_DOMAIN = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "sites_delete_domain"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("sites").addDelimiter().addParameter(SITE_ID)
            .addDelimiter().addStatic("domains").addDelimiter().addParameter(DOMAIN_ID)
            .addDelimiter().addStatic("delete").build())
        .build();

    // --- Certificates ---
    public static final PageEndpoint CERTIFICATES_LIST = Endpoint.pageBuilder()
        .identifier(Identifier.of("hohenheim", "certificates_list"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET).addStatic("certificates").build())
        .build();

    public static final PageEndpoint CERTIFICATES_UPLOAD_FORM = Endpoint.pageBuilder()
        .identifier(Identifier.of("hohenheim", "certificates_upload_form"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET).addStatic("certificates").addDelimiter().addStatic("upload").build())
        .build();

    public static final Endpoint<Object> CERTIFICATES_UPLOAD = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "certificates_upload"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST).addStatic("certificates").addDelimiter().addStatic("upload").build())
        .build();

    public static final ParameterDefinition<Integer> CERT_ID = ParameterDefinition.builder(Integer.class)
        .name("certId")
        .stringResolver(Integer::parseInt)
        .build();

    public static final Endpoint<Object> CERTIFICATES_DELETE = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "certificates_delete"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("certificates").addDelimiter().addParameter(CERT_ID)
            .addDelimiter().addStatic("delete").build())
        .build();

    // --- Settings ---
    public static final PageEndpoint SETTINGS = Endpoint.pageBuilder()
        .identifier(Identifier.of("hohenheim", "settings"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET).addStatic("settings").build())
        .build();

    public static final Endpoint<Object> SETTINGS_UPDATE = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "settings_update"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST).addStatic("settings").build())
        .build();

    // --- Audit Log ---
    public static final PageEndpoint AUDIT_LOG = Endpoint.pageBuilder()
        .identifier(Identifier.of("hohenheim", "audit_log"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET).addStatic("audit").build())
        .build();

    // --- Auth ---
    public static final Endpoint<Object> LOGIN = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "login"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET).addStatic("login").build())
        .build();

    public static final Endpoint<Object> LOGIN_POST = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "login_post"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST).addStatic("login").build())
        .build();

    public static final Endpoint<Object> LOGOUT = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "logout"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST).addStatic("logout").build())
        .build();

    public static final Endpoint<Object> SETUP = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "setup"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET).addStatic("setup").build())
        .build();

    public static final Endpoint<Object> SETUP_POST = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "setup_post"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST).addStatic("setup").build())
        .build();

    // --- Access Lists ---
    public static final ParameterDefinition<Integer> ACCESS_LIST_ID = ParameterDefinition.builder(Integer.class)
        .name("accessListId")
        .stringResolver(Integer::parseInt)
        .build();

    public static final PageEndpoint ACCESS_LISTS = Endpoint.pageBuilder()
        .identifier(Identifier.of("hohenheim", "access_lists"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET).addStatic("access-lists").build())
        .build();

    public static final PageEndpoint ACCESS_LISTS_CREATE_FORM = Endpoint.pageBuilder()
        .identifier(Identifier.of("hohenheim", "access_lists_create_form"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("access-lists").addDelimiter().addStatic("create").build())
        .build();

    public static final Endpoint<Object> ACCESS_LISTS_CREATE = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "access_lists_create"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("access-lists").addDelimiter().addStatic("create").build())
        .build();

    public static final Endpoint<Object> ACCESS_LISTS_EDIT = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "access_lists_edit"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("access-lists").addDelimiter().addParameter(ACCESS_LIST_ID).build())
        .build();

    public static final Endpoint<Object> ACCESS_LISTS_UPDATE = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "access_lists_update"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("access-lists").addDelimiter().addParameter(ACCESS_LIST_ID).build())
        .build();

    public static final Endpoint<Object> ACCESS_LISTS_DELETE = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "access_lists_delete"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("access-lists").addDelimiter().addParameter(ACCESS_LIST_ID)
            .addDelimiter().addStatic("delete").build())
        .build();

    // --- Process control ---
    public static final ParameterDefinition<Long> PID = ParameterDefinition.builder(Long.class)
        .name("pid")
        .stringResolver(Long::parseLong)
        .build();

    public static final Endpoint<Object> SITES_PROCESSES = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "sites_processes"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("sites").addDelimiter().addParameter(SITE_ID)
            .addDelimiter().addStatic("processes").build())
        .build();

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

    // --- Certificate Let's Encrypt request ---
    public static final PageEndpoint CERTIFICATES_REQUEST_FORM = Endpoint.pageBuilder()
        .identifier(Identifier.of("hohenheim", "certificates_request_form"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET).addStatic("certificates").addDelimiter().addStatic("request").build())
        .build();

    public static final Endpoint<Object> CERTIFICATES_REQUEST = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "certificates_request"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST).addStatic("certificates").addDelimiter().addStatic("request").build())
        .build();

    // --- Health check ---
    public static final Endpoint<Object> HEALTH = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "health"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("api").addDelimiter().addStatic("health").build())
        .build();

    // --- Site clone ---
    public static final Endpoint<Object> SITES_CLONE = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "sites_clone"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("sites").addDelimiter().addParameter(SITE_ID)
            .addDelimiter().addStatic("clone").build())
        .build();

    // --- Certificate download ---
    public static final Endpoint<Object> CERTIFICATES_DOWNLOAD = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "certificates_download"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("certificates").addDelimiter().addParameter(CERT_ID)
            .addDelimiter().addStatic("download").build())
        .build();

    // --- WebSocket endpoints ---
    public static final WebSocketEndpoint DASHBOARD_LIVE = WebSocketEndpoint.builder()
        .identifier(Identifier.of("hohenheim", "dashboard_live"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("ws").addDelimiter().addStatic("dashboard").build())
        .handler(session -> null) // Placeholder: set in HohenheimHandlers.init()
        .build();

    public static final WebSocketEndpoint PROCESS_TERMINAL = WebSocketEndpoint.builder()
        .identifier(Identifier.of("hohenheim", "process_terminal"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("ws").addDelimiter().addStatic("terminal")
            .addDelimiter().addParameter(SITE_ID)
            .addDelimiter().addParameter(PID).build())
        .handler(session -> null) // Placeholder: set in HohenheimHandlers.init()
        .build();

    // --- Test-only endpoint (throws to verify error handling) ---
    public static final PageEndpoint TEST_ERROR = Endpoint.pageBuilder()
        .identifier(Identifier.of("hohenheim", "test_error"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("_test").addDelimiter().addStatic("error").build())
        .build();

    public static void init() {
        // Static fields are initialized when this method is called
    }
}
