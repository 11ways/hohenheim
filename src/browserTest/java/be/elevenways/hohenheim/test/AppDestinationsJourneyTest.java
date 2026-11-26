package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.mcp.HohenheimMcpModule;
import be.elevenways.hohenheim.server.security.FlowAccounting;
import be.elevenways.hohenheim.server.security.WorkloadNetwork;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.hohenheim.server.traffic.AppDestinations;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServer;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServers;
import be.elevenways.zenit.ai.server.mcp.host.McpHttpSession;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static be.elevenways.hohenheim.server.security.FlowAccounting.Counter.CONNECTIONS;
import static be.elevenways.hohenheim.server.security.FlowAccounting.Counter.RECEIVED;
import static be.elevenways.hohenheim.server.security.FlowAccounting.Counter.SENT;
import static be.elevenways.hohenheim.server.security.FlowAccounting.Via.HOST;
import static be.elevenways.hohenheim.server.security.FlowAccounting.Via.THROUGH;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where an app sends data, read through the real MCP endpoint, from a fake of the host's counter listing read by the
 * real reader into the real store: the internet by address, another app of the server by its container's address
 * (named only to who may view it), the server's proxy through its own address, the app's database link as an
 * internal line; a customer reads his own app's and is refused another's. AppDestinationsCardTest draws the same.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class AppDestinationsJourneyTest extends HohenheimHttpTestBase {

    /** Another app's container on the same server. */
    static final String NEIGHBOUR_ADDRESS = "172.31.2.2";

    /** The database the app's link joins; no record of it, so it reads as the app's database to everyone. */
    static final int DATABASE = 990_001;

    @Test
    void anAssistantReadsWhereAnAppSendsDataAndACustomerOnlyHisOwn() throws Exception {
        String tag = HohenheimMcpTest.letters();
        Deque<Runnable> cleanUp = new ArrayDeque<>();
        try {
            TrafficCountingJourneyTest.countUnder(cleanUp, "destinations-" + tag);
            int app = AppNetworkJourneyTest.application(cleanUp, "sender-" + tag);
            int neighbour = AppNetworkJourneyTest.application(cleanUp, "neighbour-" + tag);
            int foreign = AppNetworkJourneyTest.application(cleanUp, "elsewhere-" + tag);
            counted(cleanUp, app, neighbour);

            // 1. An administrator reads the app's destinations by tool: the internet by address, the other app by
            //    name, the proxy, the database link as an internal line; the most sent first.
            URI endpoint = URI.create(baseUrl() + "/zn/mcp/" + HohenheimMcpModule.SERVER_NAME);
            McpHostServer server = Objects.requireNonNull(McpHostServers.routed(HohenheimMcpModule.SERVER_NAME));
            McpHttpSession admin = HohenheimMcpTest.presetSession(server, endpoint,
                TenantConduits.operatorUser().get(UserModel.ID), "Test Admin");
            AccessContext operator = TenantConduits.operator();
            String appCode = HohenheimMcpTest.code(InstanceModel.MODEL_ID, app, operator);
            String foreignCode = HohenheimMcpTest.code(InstanceModel.MODEL_ID, foreign, operator);
            McpHttpSession.Answer read = admin.call("app_network_read", Map.of("code", appCode, "top",
                "destinations"));
            assertThat(read.error()).as("step 1: " + read.text()).isFalse();
            assertThat(read.text()).as("step 1: THE ASSERTION, each destination as the kernel counted it")
                .contains("2 1500 9000 ").contains("internet 93.184.216.34 443/tcp")
                .contains("app neighbour-" + tag + " " + NEIGHBOUR_ADDRESS + " 8080/tcp")
                .contains("proxy 203.0.113.10 443/tcp")
                .contains("database internal 172.31.9.3 5432/tcp");
            assertThat(read.text().indexOf("93.184.216.34")).as("step 1: the most sent first")
                .isLessThan(read.text().indexOf("203.0.113.10"));
            McpHttpSession.Answer plain = admin.call("app_network_read", Map.of("code", appCode));
            assertThat(plain.text()).as("step 1: no top, no destinations").doesNotContain("destinations,");

            // 2. A wrong table is refused by the argument it names.
            McpHttpSession.Answer pages = admin.call("app_network_read", Map.of("code", appCode, "top", "pages"));
            assertThat(pages.error()).as("step 2: " + pages.text()).isTrue();
            assertThat(pages.text()).as("step 2: the refusal names it").contains("top");

            // 3. A customer who manages the app reads its destinations, the other customer's app by its address
            //    alone, and is refused an app that is not his.
            UUID customer = ApiSupport.user("destinations-" + tag + "@hohenheim.local", "Destinations customer");
            cleanUp.push(() -> Models.get(UserModel.class).delete(customer));
            RecordGrants.grant(GrantSubjectType.USER, customer, InstanceModel.MODEL_ID, app,
                HohenheimCapabilities.MANAGE, true);
            McpHttpSession own = HohenheimMcpTest.presetSession(server, endpoint, customer, "Destinations customer");
            McpHttpSession.Answer his = own.call("app_network_read", Map.of("code", appCode, "top", "destinations"));
            assertThat(his.error()).as("step 3: " + his.text()).isFalse();
            assertThat(his.text()).as("step 3: his app's destinations").contains("internet 93.184.216.34 443/tcp")
                .contains("app " + NEIGHBOUR_ADDRESS + " 8080/tcp").doesNotContain("neighbour-" + tag);
            McpHttpSession.Answer theirs = own.call("app_network_read", Map.of("code", foreignCode, "top",
                "destinations"));
            assertThat(theirs.error()).as("step 3: " + theirs.text()).isTrue();
            assertThat(theirs.text()).as("step 3: nothing of it").doesNotContain("destinations,");
        } finally {
            while (!cleanUp.isEmpty()) cleanUp.pop().run();
        }
    }

    /**
     * Counts the app's destinations through the real reader into the store: a fake kernel, a plan the sweep would
     * publish, one baseline reading and one that grew.
     */
    static void counted(Deque<Runnable> cleanUp, int app, int neighbour) {
        WorkloadNetwork own = new WorkloadNetwork("hohenheim-journey-" + app + "-net", "172.31.1.0/24", "172.31.1.1",
            "fd00:31:1::/64");
        WorkloadNetwork link = new WorkloadNetwork("hohenheim-journey-" + app + "-link-net", "172.31.9.0/24",
            "172.31.9.1", null);
        String ownKey = WorkloadNetworkPolicy.chainKey(own.name());
        String linkKey = WorkloadNetworkPolicy.chainKey(link.name());
        DestinationKernel kernel = new DestinationKernel().accounting(own).accounting(link);
        WorkloadNetworkPolicy policy = kernel.policy();
        WorkloadNetworkPolicy.overrideForTest(policy);
        cleanUp.push(() -> {
            WorkloadNetworkPolicy.overrideForTest(null);
            AppDestinations.retain(Set.of());
        });
        AppDestinations.Plan plan = new AppDestinations.Plan(99_000 + app, "journey-host",
            Map.of(ownKey, new AppDestinations.Accounted(own, app, null),
                linkKey, new AppDestinations.Accounted(link, app, DATABASE)),
            Map.of("172.31.1.2", new AppDestinations.Party(app, null),
                "172.31.9.2", new AppDestinations.Party(app, null),
                "172.31.9.3", new AppDestinations.Party(null, DATABASE),
                NEIGHBOUR_ADDRESS, new AppDestinations.Party(neighbour, null)),
            Set.of("tcp/443"), Map.of());
        AppDestinations.reconcile(policy, plan);
        AppDestinations.readAll();
        kernel.element(THROUGH, SENT, ownKey, "172.31.1.2", "93.184.216.34", "tcp", 443, 20, 1_500)
            .element(THROUGH, RECEIVED, ownKey, "172.31.1.2", "93.184.216.34", "tcp", 443, 30, 9_000)
            .element(THROUGH, CONNECTIONS, ownKey, "172.31.1.2", "93.184.216.34", "tcp", 443, 2, 120)
            .element(THROUGH, SENT, ownKey, "172.31.1.2", NEIGHBOUR_ADDRESS, "tcp", 8080, 3, 600)
            .element(HOST, SENT, ownKey, "172.31.1.2", "203.0.113.10", "tcp", 443, 2, 200)
            .element(THROUGH, SENT, linkKey, "172.31.9.2", "172.31.9.3", "tcp", 5432, 4, 400)
            .element(THROUGH, RECEIVED, linkKey, "172.31.9.2", "172.31.9.3", "tcp", 5432, 4, 800);
        assertThat(AppDestinations.readAll()).as("the reading that grew counts four destinations").isEqualTo(4);
        assertThat(FlowAccounting.parse(kernel.render()).counted()).hasSize(7);
    }
}
