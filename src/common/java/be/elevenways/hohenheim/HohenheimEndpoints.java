package be.elevenways.hohenheim;

import be.elevenways.protoblast.common.http.HttpMethod;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.routing.Endpoint;
import be.elevenways.zenit.common.routing.EndpointRoute;
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

    // --- Dashboard ---
    public static final Endpoint<Object> DASHBOARD = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "dashboard"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET).build())
        .build();

    // --- Sites ---
    public static final Endpoint<Object> SITES_LIST = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "sites_list"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET).addStatic("sites").build())
        .build();

    public static final Endpoint<Object> SITES_CREATE_FORM = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "sites_create_form"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET).addStatic("sites").addDelimiter().addStatic("create").build())
        .build();

    public static final Endpoint<Object> SITES_CREATE = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "sites_create"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST).addStatic("sites").addDelimiter().addStatic("create").build())
        .build();

    // --- Certificates ---
    public static final Endpoint<Object> CERTIFICATES_LIST = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "certificates_list"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET).addStatic("certificates").build())
        .build();

    public static void init() {
        // Static fields are initialized when this method is called
    }
}
