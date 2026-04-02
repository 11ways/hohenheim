package be.elevenways.hohenheim;

import be.elevenways.protoblast.common.http.HttpMethod;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.routing.Endpoint;
import be.elevenways.zenit.common.routing.EndpointRoute;
import be.elevenways.zenit.common.routing.PageEndpoint;
import be.elevenways.zenit.common.routing.ParameterDefinition;

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
