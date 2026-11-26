package be.elevenways.hohenheim.server.traffic;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.cms.AttentionCollector;
import be.elevenways.hohenheim.server.security.FlowAccounting;
import be.elevenways.hohenheim.server.security.NftRunner;
import be.elevenways.hohenheim.server.security.WorkloadNetwork;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.hohenheim.test.DestinationKernel;
import be.elevenways.hohenheim.test.HohenheimServedApp;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.test.support.ServedApp;
import be.elevenways.zenit.traffic.common.TrafficFlow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static be.elevenways.hohenheim.server.security.FlowAccounting.Counter.CONNECTIONS;
import static be.elevenways.hohenheim.server.security.FlowAccounting.Counter.RECEIVED;
import static be.elevenways.hohenheim.server.security.FlowAccounting.Counter.SENT;
import static be.elevenways.hohenheim.server.security.FlowAccounting.Via.HOST;
import static be.elevenways.hohenheim.server.security.FlowAccounting.Via.THROUGH;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where apps send data, from a fake of the host's counter listing: two apps and two releases of one talking to one
 * destination, a connection to another site of the same server through its address, IPv6, a counter that went down,
 * a set read for the first time, the database link as an internal line, an app removed, the cost of a listing at 20
 * apps of 500 destinations, and the sweep and reader keeping out of the way: accounting added, kept and removed with
 * the setting, a failing listing told on the dashboard, a teardown taking the accounting with it.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class AppDestinationsTest {

    @RegisterExtension
    static final ServedApp APP = HohenheimServedApp.app();

    private static final WorkloadNetwork OWN = new WorkloadNetwork("hohenheim-dest-own-net", "172.31.1.0/24",
        "172.31.1.1", "fd00:31:1::/64");
    private static final WorkloadNetwork OTHER = new WorkloadNetwork("hohenheim-dest-other-net", "172.31.2.0/24",
        "172.31.2.1", null);
    private static final WorkloadNetwork LINK = new WorkloadNetwork("hohenheim-dest-link-net", "172.31.9.0/24",
        "172.31.9.1", null);

    private static final int SERVER = 9_101;
    private static final int APP_ID = 101;
    private static final int OTHER_APP = 102;
    private static final int DATABASE = 7;
    private static final int MAIL_SITE = 55;

    private static final String BLUE = "172.31.1.2";
    private static final String GREEN = "172.31.1.3";
    private static final String BLUE_V6 = "fd00:31:1::2";
    private static final String NEIGHBOUR = "172.31.2.2";
    private static final String APP_ON_LINK = "172.31.9.2";
    private static final String DATABASE_ON_LINK = "172.31.9.3";
    private static final String INTERNET = "93.184.216.34";
    private static final String SERVER_ADDRESS = "203.0.113.10";

    @TempDir
    Path dir;

    @BeforeEach
    void fresh() {
        AppDestinations.reset();
    }

    @AfterEach
    void forget() {
        AppDestinations.reset();
        WorkloadNetworkPolicy.overrideForTest(null);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Traffic.RECORD_DESTINATIONS, true);
    }

    @Test
    void appsReleasesHairpinIpv6ResetsAndTheDatabaseLinkCountUnderTheRightApp() {
        DestinationKernel kernel = new DestinationKernel().accounting(OWN).accounting(OTHER).accounting(LINK);
        AppDestinations.Plan plan = plan(OWN, OTHER, LINK);
        String own = key(OWN);

        // 1. A first reading of every set only sets its baselines: what the counters held was counted then or never.
        kernel.element(THROUGH, SENT, own, BLUE, INTERNET, "tcp", 443, 4, 1_000)
            .element(THROUGH, SENT, key(OTHER), NEIGHBOUR, INTERNET, "tcp", 443, 2, 500);
        assertThat(read(plan, kernel)).as("step 1: a baseline counts nothing").isEmpty();

        // 2. THE ASSERTION: two releases of one app and another app talk to one destination; the releases add up
        //    under their app, the other app's bytes stay its own.
        kernel.element(THROUGH, SENT, own, BLUE, INTERNET, "tcp", 443, 6, 1_100)
            .element(THROUGH, SENT, own, GREEN, INTERNET, "tcp", 443, 1, 50)
            .element(THROUGH, CONNECTIONS, own, GREEN, INTERNET, "tcp", 443, 1, 60)
            .element(THROUGH, RECEIVED, own, GREEN, INTERNET, "tcp", 443, 3, 900)
            .element(THROUGH, SENT, key(OTHER), NEIGHBOUR, INTERNET, "tcp", 443, 3, 570);
        assertThat(read(plan, kernel)).as("step 2: per app, releases summed, nothing invented").containsExactly(
            "101 internet||93.184.216.34|tcp|443 connections 1 sent 150 received 900",
            "102 internet||93.184.216.34|tcp|443 connections 0 sent 70 received 0");

        // 3. A connection to the server's own address (input hook) is the server's own: its proxy on a web port, a
        //    stream site on that site's port, the server on any other.
        kernel.element(HOST, SENT, own, BLUE, SERVER_ADDRESS, "tcp", 443, 2, 40)
            .element(HOST, SENT, own, BLUE, SERVER_ADDRESS, "tcp", 587, 1, 30)
            .element(HOST, SENT, own, BLUE, SERVER_ADDRESS, "tcp", 2222, 1, 5);
        assertThat(read(plan, kernel)).as("step 3: the server's own ports by what serves them").containsExactly(
            "101 proxy||203.0.113.10|tcp|443 connections 0 sent 40 received 0",
            "101 stream|55|203.0.113.10|tcp|587 connections 0 sent 30 received 0",
            "101 server||203.0.113.10|tcp|2222 connections 0 sent 5 received 0");

        // 4. IPv6 has its own sets; the app's container is known by its IPv6 address too.
        kernel.element(THROUGH, SENT, own, BLUE_V6, "2001:db8::5", "tcp", 443, 1, 64);
        assertThat(read(plan, kernel)).as("step 4: IPv6").containsExactly(
            "101 internet||2001:db8::5|tcp|443 connections 0 sent 64 received 0");

        // 5. The app's connections to its database over their link are an internal line naming the database; a
        //    connection the database's own container opens counts under no app.
        kernel.element(THROUGH, SENT, key(LINK), APP_ON_LINK, DATABASE_ON_LINK, "tcp", 5432, 5, 300)
            .element(THROUGH, RECEIVED, key(LINK), APP_ON_LINK, DATABASE_ON_LINK, "tcp", 5432, 5, 700)
            .element(THROUGH, SENT, key(LINK), DATABASE_ON_LINK, APP_ON_LINK, "tcp", 40_000, 1, 99);
        assertThat(read(plan, kernel)).as("step 5: the database link, under the app alone").containsExactly(
            "101 database|7|172.31.9.3|tcp|5432 connections 0 sent 300 received 700");
        assertThat(DestinationKind.DATABASE.internal()).as("step 5: and internal").isTrue();

        // 6. A counter below its baseline was recreated (its element expired and came back): it counts whole; an
        //    element gone from the listing is forgotten and counts whole when it is back.
        kernel.element(THROUGH, SENT, own, BLUE, INTERNET, "tcp", 443, 1, 30)
            .expire(THROUGH, SENT, own, GREEN, INTERNET, "tcp", 443);
        assertThat(read(plan, kernel)).as("step 6: the reset counts what it holds").containsExactly(
            "101 internet||93.184.216.34|tcp|443 connections 0 sent 30 received 0");
        kernel.element(THROUGH, SENT, own, GREEN, INTERNET, "tcp", 443, 1, 20);
        assertThat(read(plan, kernel)).as("step 6: an element back after its expiry counts whole").containsExactly(
            "101 internet||93.184.216.34|tcp|443 connections 0 sent 20 received 0");

        // 7. An app removed: its network leaves the plan, and its counters count for nobody.
        AppDestinations.Plan without = plan(OWN, LINK);
        kernel.element(THROUGH, SENT, key(OTHER), NEIGHBOUR, INTERNET, "tcp", 443, 9, 9_000);
        assertThat(read(without, kernel)).as("step 7: a removed app's counters count nothing").isEmpty();

        // 8. A network listed for the first time (a new app, a set the sweep just added) sets its baselines first:
        //    what it held before the reading is not known to be new.
        WorkloadNetwork added = new WorkloadNetwork("hohenheim-dest-added-net", "172.31.3.0/24", "172.31.3.1", null);
        AppDestinations.Plan grown = plan(OWN, LINK, added);
        kernel.accounting(added).element(THROUGH, SENT, key(added), "172.31.3.2", INTERNET, "tcp", 443, 1, 80);
        assertThat(read(grown, kernel)).as("step 8: a set new to the listing is a baseline").isEmpty();
        kernel.element(THROUGH, SENT, key(added), "172.31.3.2", INTERNET, "tcp", 443, 2, 100);
        assertThat(read(grown, kernel)).as("step 8: and counts from its next reading, under its network's app")
            .containsExactly("101 internet||93.184.216.34|tcp|443 connections 0 sent 20 received 0");
    }

    @Test
    void anOlderNftsElementOrderAndNumericProtocolsReadAlike() {
        String set = FlowAccounting.setName(THROUGH, SENT, FlowAccounting.Family.V4, key(OWN));
        String listing = "table inet t {\n\tset " + set + " {\n\t\ttype ipv4_addr . ipv4_addr . inet_proto ."
            + " inet_service\n\t\tflags dynamic,timeout\n\t\telements = { 172.31.1.2 . 10.0.0.1 . 6 . 443 expires"
            + " 14m59s counter packets 3 bytes 180, 172.31.1.2 . 10.0.0.2 . 17 . 53 timeout 15m expires 1m counter"
            + " packets 1 bytes 60,\n\t\t\t     not an element }\n\t}\n}\n";
        List<FlowAccounting.Counted> counted = FlowAccounting.parse(listing).counted();
        assertThat(counted).as("both elements, the malformed one skipped").hasSize(2);
        assertThat(counted.get(0).protocol()).isEqualTo("tcp");
        assertThat(counted.get(0).bytes()).isEqualTo(180);
        assertThat(counted.get(1).protocol()).isEqualTo("udp");
        assertThat(counted.get(1).port()).isEqualTo("53");
    }

    @Test
    void aListingOfTwentyAppsOfFiveHundredDestinationsCostsLittle() throws IOException {
        DestinationKernel kernel = new DestinationKernel();
        Map<String, AppDestinations.Accounted> networks = new HashMap<>();
        Map<String, AppDestinations.Party> addresses = new HashMap<>();
        for (int app = 0; app < 20; app++) {
            WorkloadNetwork network = new WorkloadNetwork("hohenheim-cost-" + app + "-net", "172.30." + app + ".0/24",
                "172.30." + app + ".1", null);
            kernel.accounting(network);
            networks.put(key(network), new AppDestinations.Accounted(network, 1_000 + app, null));
            addresses.put("172.30." + app + ".2", new AppDestinations.Party(1_000 + app, null));
        }
        AppDestinations.Plan plan = new AppDestinations.Plan(SERVER, "cost", networks, addresses, Set.of("tcp/443"),
            Map.of());
        grow(kernel, plan, 1);
        String first = kernel.render();
        long parseStarted = System.nanoTime();
        FlowAccounting.Listing listed = FlowAccounting.parse(first);
        long parseNanos = System.nanoTime() - parseStarted;
        assertThat(AppDestinations.flows(plan, listed, Now.instant())).as("the first reading is a baseline").isEmpty();
        grow(kernel, plan, 2);
        String second = kernel.render();

        // The listing through the Subprocess home, the way a host's answer arrives (a pipe of this many bytes).
        Path file = this.dir.resolve("listing.txt");
        Files.writeString(file, second);
        long pipeStarted = System.nanoTime();
        NftRunner.Result piped = NftRunner.Result.of(List.of("cat", file.toString()), null, 20);
        long pipeNanos = System.nanoTime() - pipeStarted;
        assertThat(piped.stdout()).as("the whole listing arrives").hasSize(second.length());

        long readStarted = System.nanoTime();
        List<TrafficFlow> flows = AppDestinations.flows(plan, FlowAccounting.parse(piped.stdout()), Now.instant());
        long readNanos = System.nanoTime() - readStarted;
        assertThat(flows).as("one flow per app and destination").hasSize(20 * 500);
        System.out.println("DESTINATIONS listing of " + listed.counted().size() + " elements, " + second.length()
            + " chars: piped through Subprocess in " + pipeNanos / 1_000_000 + " ms, first parse "
            + parseNanos / 1_000_000 + " ms, parse and count " + readNanos / 1_000_000 + " ms");
        assertThat((pipeNanos + readNanos) / 1_000_000).as("nowhere near its minute, even on a loaded machine")
            .isLessThan(30_000);
    }

    @Test
    void theSweepAndTheReaderKeepOutOfTheWayAndFollowTheSetting() throws IOException {
        DestinationKernel kernel = new DestinationKernel();
        WorkloadNetworkPolicy policy = kernel.policy();
        WorkloadNetworkPolicy.overrideForTest(policy);
        AppDestinations.Plan plan = plan(OWN, LINK);

        // 1. The sweep adds what a counted network lacks, one transaction per network.
        AppDestinations.reconcile(policy, plan);
        assertThat(kernel.applied()).as("step 1: both networks get their accounting").hasSize(2);
        assertThat(String.join("\n", kernel.applied())).as("step 1: sets, chains and rules")
            .contains("add set inet " + WorkloadNetworkPolicy.table() + " " + FlowAccounting.setName(THROUGH, SENT,
                FlowAccounting.Family.V6, key(OWN)))
            .contains("type filter hook input priority 10")
            .contains("ct state new update @" + FlowAccounting.setName(HOST, CONNECTIONS, FlowAccounting.Family.V4,
                key(OWN)));

        // 2. THE ASSERTION: the sweep converges. Accounting carried exactly is left alone, and one no counted network
        //    expects is removed.
        assertThat(FlowAccounting.carries(FlowAccounting.parse(kernel.render()), OWN))
            .as("step 2: the kernel carries what the sweep applied").isTrue();
        kernel.accounting(OTHER);
        AppDestinations.reconcile(policy, plan);
        assertThat(kernel.applied()).as("step 2: only the stale network's removal").hasSize(3);
        assertThat(kernel.applied().getLast()).as("step 2: its chains and sets go")
            .contains("delete chain inet " + WorkloadNetworkPolicy.table() + " acc_fwd_" + key(OTHER))
            .doesNotContain(key(OWN));
        AppDestinations.reconcile(policy, plan);
        assertThat(kernel.applied()).as("step 2: a sweep after it changes nothing").hasSize(3);

        // 3. A network the sweep could not inspect keeps its accounting: an unreadable daemon drops no counters.
        AppDestinations.reconcile(policy, new AppDestinations.Plan(SERVER, "destinations-host",
            Map.of(key(LINK), new AppDestinations.Accounted(LINK, APP_ID, DATABASE)), plan.addresses(),
            plan.webPorts(), plan.streams(), Set.of(key(OWN))));
        assertThat(kernel.applied()).as("step 3: the unread network's accounting stays").hasSize(3);
        AppDestinations.reconcile(policy, plan);

        // 4. The reader lists each planned host once and counts through the store.
        int sweeps = kernel.listings();
        assertThat(AppDestinations.readAll()).as("step 4: a first listing is a baseline").isZero();
        assertThat(kernel.listings() - sweeps).as("step 4: one listing for the one planned host").isEqualTo(1);

        // 5. A failing listing never throws and is told on the dashboard; the next good one clears it.
        kernel.failing("Error: Operation not permitted");
        assertThat(AppDestinations.readAll()).isZero();
        assertThat(said()).as("step 5: the dashboard says so").contains("traffic_destinations_unread");
        assertThat(AppDestinations.problems().getFirst().why()).contains("Operation not permitted");
        kernel.failing(null);
        AppDestinations.readAll();
        assertThat(said()).as("step 5: and stops saying so").doesNotContain("traffic_destinations_unread");

        // 6. Switched off: the reader lists nothing and the sweep removes every accounting chain and set.
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Traffic.RECORD_DESTINATIONS, false);
        int listed = kernel.listings();
        assertThat(AppDestinations.readAll()).isZero();
        assertThat(kernel.listings()).as("step 6: no listing while off").isEqualTo(listed);
        int applied = kernel.applied().size();
        AppDestinations.reconcile(policy, plan);
        List<String> removals = kernel.applied().subList(applied, kernel.applied().size());
        assertThat(String.join("\n", removals)).as("step 6: every network's accounting removed")
            .contains("delete set inet " + WorkloadNetworkPolicy.table() + " " + FlowAccounting.setName(THROUGH,
                SENT, FlowAccounting.Family.V4, key(OWN)))
            .contains("delete chain inet " + WorkloadNetworkPolicy.table() + " acc_out_" + key(LINK))
            .doesNotContain("add rule");
        assertThat(FlowAccounting.parse(kernel.render()).accountedKeys()).as("step 6: none left in the kernel")
            .isEmpty();

        // 7. A teardown takes the network's accounting with it, whatever of it the kernel holds.
        int before = kernel.applied().size();
        policy.remove(OWN.name());
        assertThat(String.join("\n", kernel.applied().subList(before, kernel.applied().size())))
            .as("step 7: the teardown's removal, then the table it emptied")
            .contains("delete chain inet " + WorkloadNetworkPolicy.table() + " acc_fwd_" + key(OWN))
            .contains("delete set inet " + WorkloadNetworkPolicy.table() + " " + FlowAccounting.setName(HOST,
                RECEIVED, FlowAccounting.Family.V6, key(OWN)))
            .contains("delete table inet " + WorkloadNetworkPolicy.table());
    }

    /** Grows every app's 500 destinations by one reading, in all three counters. */
    private static void grow(DestinationKernel kernel, AppDestinations.Plan plan, long by) {
        for (AppDestinations.Accounted accounted : plan.networks().values()) {
            String network = accounted.network().name();
            String source = accounted.network().ipv4Subnet().replace(".0/24", ".2");
            for (int destination = 0; destination < 500; destination++) {
                String address = "198.51." + (destination / 250) + "." + (destination % 250 + 1);
                for (FlowAccounting.Counter counter : FlowAccounting.Counter.values()) {
                    kernel.element(THROUGH, counter, WorkloadNetworkPolicy.chainKey(network), source, address, "tcp",
                        443, by * 2, by * 1_500);
                }
            }
        }
    }

    /** @return the flows one listing of the kernel grew, each "app key connections N sent N received N" */
    private static List<String> read(AppDestinations.Plan plan, DestinationKernel kernel) {
        List<String> said = new ArrayList<>();
        Instant now = Now.instant();
        for (TrafficFlow flow : AppDestinations.flows(plan, FlowAccounting.parse(kernel.render()), now)) {
            said.add(flow.source() + " " + flow.key() + " connections " + flow.connections() + " sent "
                + flow.bytesSent() + " received " + flow.bytesReceived());
        }
        return said;
    }

    /** @return the plan of these networks: the own one and the other app's own, the link to the database */
    private static AppDestinations.Plan plan(WorkloadNetwork... networks) {
        Map<String, AppDestinations.Accounted> counted = new HashMap<>();
        for (WorkloadNetwork network : networks) {
            int app = network == OTHER ? OTHER_APP : APP_ID;
            counted.put(key(network), new AppDestinations.Accounted(network, app, network == LINK ? DATABASE : null));
        }
        Map<String, AppDestinations.Party> addresses = new HashMap<>();
        for (String address : List.of(BLUE, GREEN, BLUE_V6, APP_ON_LINK)) {
            addresses.put(address, new AppDestinations.Party(APP_ID, null));
        }
        addresses.put(NEIGHBOUR, new AppDestinations.Party(OTHER_APP, null));
        addresses.put(DATABASE_ON_LINK, new AppDestinations.Party(null, DATABASE));
        return new AppDestinations.Plan(SERVER, "destinations-host", counted, addresses, Set.of("tcp/80", "tcp/443"),
            Map.of("tcp/587", MAIL_SITE));
    }

    private static String key(WorkloadNetwork network) {
        return WorkloadNetworkPolicy.chainKey(network.name());
    }

    /** @return the keys of the dashboard's traffic items */
    private static List<String> said() {
        List<AttentionItem> items = new ArrayList<>();
        AttentionCollector.traffic(items);
        return items.stream().map(item -> item.title().key()).toList();
    }
}
