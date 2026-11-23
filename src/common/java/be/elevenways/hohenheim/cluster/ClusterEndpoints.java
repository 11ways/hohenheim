package be.elevenways.hohenheim.cluster;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.protoblast.common.http.HttpMethod;
import be.elevenways.zenit.common.routing.Endpoint;
import be.elevenways.zenit.common.routing.EndpointRoute;
import be.elevenways.zenit.common.routing.RateLimitPolicy;

import java.time.Duration;

/**
 * The calls one Hohenheim makes to another it is linked to: join, status and leave; and, from a master, the
 * generations and rows of the kinds it keeps a record of, and from a managed server the nudge that it changed.
 *
 * AIDEV-NOTE: machine endpoints whose credential is in the request, so each route authenticates itself (no login
 * gate, no CSRF): the join code for join, the link key ({@code Authorization: Bearer}) for every other call. The
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

    /**
     * Record calls one address may make per minute: a master's first sync reads every kind it keeps, a page at a
     * time, and a managed server nudges after each change.
     */
    private static final RateLimitPolicy RECORDS_LIMIT = RateLimitPolicy.of(1200, Duration.ofMinutes(1))
        .keyBy(RateLimitPolicy.KeyBy.IP)
        .named("hh_cluster_records");

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

    /** A master asks the current generation of every kind this server shares: only its master may. */
    public static final Endpoint<Object> GENERATIONS = Endpoint.<Object>builder()
        .identifier(HohenheimIds.id("cluster_generations"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("api").addDelimiter().addStatic("cluster").addDelimiter().addStatic("generations").build())
        .authenticatesItself()
        .rateLimit(RECORDS_LIMIT)
        .build();

    /** A master asks one page of one shared kind's rows, every value in clear: only its master may. */
    public static final Endpoint<Object> ROWS = Endpoint.<Object>builder()
        .identifier(HohenheimIds.id("cluster_rows"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("api").addDelimiter().addStatic("cluster").addDelimiter().addStatic("rows").build())
        .authenticatesItself()
        .rateLimit(RECORDS_LIMIT)
        .build();

    /** A managed server says it changed a shared kind: its master syncs it. It carries nothing but "ask me". */
    public static final Endpoint<Object> CHANGED = Endpoint.<Object>builder()
        .identifier(HohenheimIds.id("cluster_changed"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("api").addDelimiter().addStatic("cluster").addDelimiter().addStatic("changed").build())
        .authenticatesItself()
        .rateLimit(RECORDS_LIMIT)
        .build();

    private ClusterEndpoints() {
    }
}
