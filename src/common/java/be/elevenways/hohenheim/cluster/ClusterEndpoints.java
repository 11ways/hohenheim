package be.elevenways.hohenheim.cluster;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.protoblast.common.http.HttpMethod;
import be.elevenways.zenit.common.routing.Endpoint;
import be.elevenways.zenit.common.routing.EndpointRoute;
import be.elevenways.zenit.common.routing.RateLimitPolicy;

import java.time.Duration;

/**
 * The three calls one Hohenheim makes to another it is linked to: join, status and leave.
 *
 * AIDEV-NOTE: machine endpoints whose credential is in the request, so each route authenticates itself (no login
 * gate, no CSRF): the join code for join, the link key ({@code Authorization: Bearer}) for status and leave. The
 * handlers (server ClusterHandlers) answer JSON and nothing a key does not name; docs/cluster.md is the wire.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class ClusterEndpoints {

    /** Joins one address may ask per minute; each wrong code also spends the code's own failure budget. */
    private static final RateLimitPolicy JOIN_LIMIT = RateLimitPolicy.of(12, Duration.ofMinutes(1))
        .keyBy(RateLimitPolicy.KeyBy.IP)
        .named("hh_cluster_join");

    /** Status and leave calls one address may make per minute: a probe asks once a minute per link. */
    private static final RateLimitPolicy LINK_LIMIT = RateLimitPolicy.of(60, Duration.ofMinutes(1))
        .keyBy(RateLimitPolicy.KeyBy.IP)
        .named("hh_cluster_link");

    /** A master asks this server to be managed by it, presenting a join code this server issued. */
    public static final Endpoint<Object> JOIN = Endpoint.<Object>builder()
        .identifier(HohenheimIds.id("cluster_join"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("api").addDelimiter().addStatic("cluster").addDelimiter().addStatic("join").build())
        .authenticatesItself()
        .rateLimit(JOIN_LIMIT)
        .build();

    /** A linked server asks who answers here: name, controller token, version and time, nothing else. */
    public static final Endpoint<Object> STATUS = Endpoint.<Object>builder()
        .identifier(HohenheimIds.id("cluster_status"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("api").addDelimiter().addStatic("cluster").addDelimiter().addStatic("status").build())
        .authenticatesItself()
        .rateLimit(LINK_LIMIT)
        .build();

    /** A linked server ends the link: this side forgets the server whose key it is. */
    public static final Endpoint<Object> LEAVE = Endpoint.<Object>builder()
        .identifier(HohenheimIds.id("cluster_leave"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("api").addDelimiter().addStatic("cluster").addDelimiter().addStatic("leave").build())
        .authenticatesItself()
        .rateLimit(LINK_LIMIT)
        .build();

    private ClusterEndpoints() {
    }
}
