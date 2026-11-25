package be.elevenways.hohenheim.test.network;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.host.HostShell;
import be.elevenways.hohenheim.server.runtime.DockerInstanceRuntime;
import be.elevenways.hohenheim.server.runtime.Egress;
import be.elevenways.hohenheim.server.runtime.NetworkSubnets;
import be.elevenways.hohenheim.server.runtime.WorkloadNetworks;
import be.elevenways.hohenheim.server.security.NftRunner;
import be.elevenways.hohenheim.server.security.PublicPorts;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.hohenheim.test.HohenheimServedApp;
import be.elevenways.hohenheim.test.docker.FakeDockerDaemon;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.test.support.ServedApp;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A host whose Docker address pools are spent still gets every network a deploy needs, each a small block of the
 * declared range past what the host already uses, until the range itself is full and the refusal says so in words.
 *
 * AIDEV-NOTE: the daemon refuses as Robbedoes refused on 2026-11-25 (a create that names no subnet gets "all
 * predefined address pools have been fully subnetted"), so step 1 is red on any build that still lets Docker pick.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class NetworkSubnetsTest {

    @RegisterExtension
    static final ServedApp APP = HohenheimServedApp.app();

    /** A route table holding a default route, Docker's own bridge and a LAN route inside the declared range. */
    private static final String ROUTES = "Iface\tDestination\tGateway\tFlags\tRefCnt\tUse\tMetric\tMask\tMTU\tWindow"
        + "\tIRTT\n"
        + "eth0\t00000000\t0100A8C0\t0003\t0\t0\t100\t00000000\t0\t0\t0\n"
        + "docker0\t000011AC\t00000000\t0001\t0\t0\t0\t0000FFFF\t0\t0\t0\n"
        + "eth1\t2000C70A\t00000000\t0001\t0\t0\t0\tE0FFFFFF\t0\t0\t0\n";

    @Test
    void aHostWithSpentPoolsStillGetsSmallNetworksUntilTheRangeIsFull() throws Exception {
        FakeDockerDaemon daemon = new FakeDockerDaemon();
        DockerClient docker = new DockerClient(daemon);
        RememberingKernel kernel = new RememberingKernel();
        HostShell host = (script, timeoutSeconds) -> new HostShell.Result(0, ROUTES);
        WorkloadNetworkPolicy policy = new WorkloadNetworkPolicy(kernel, () -> true, PublicPorts::web, host);
        Map<String, String> owner = OwnerLabels.of(InstanceModel.MODEL_ID, 4101);

        // 1. Robbedoes' shape: the daemon's own pools are spent, another tool's network holds the range's first
        //    block and a LAN route its second. Letting Docker pick is refused, as it was there.
        daemon.limitPoolPicks(0);
        daemon.seedNetwork("someone-elses-net", "10.199.0.0/27");
        assertThat(catchThrowable(() -> docker.createNetwork("pool-picked-net", null, null, null, false)))
            .as("step 1: the daemon refuses a network whose block it must pick itself")
            .hasMessageContaining("all predefined address pools have been fully subnetted");

        // 2. A deploy's own network takes the first block nothing uses, and its firewall is keyed on that block.
        String own = WorkloadNetworks.ensure(docker, policy, "hohenheim-subnets-app", owner, Egress.OPEN);
        assertThat(daemon.subnetOf(own)).as("step 2: past the foreign network and the route")
            .isEqualTo("10.199.0.64/27");
        assertThat(daemon.gatewayOf(own)).as("step 2: the bridge takes the block's first address")
            .isEqualTo("10.199.0.65");
        assertThat(kernel.rulesOf(WorkloadNetworkPolicy.forwardChain(WorkloadNetworkPolicy.chainKey(own))))
            .as("step 2: the app firewall accepts the own block before it drops every private range")
            .containsSubsequence("ip saddr 10.199.0.64/27 ip daddr 10.199.0.64/27 accept",
                "ip saddr 10.199.0.64/27 ip daddr 10.0.0.0/8 drop");

        // 3. Its database link takes the next block.
        String link = new DockerInstanceRuntime(docker, policy)
            .ensureLinkNetwork("hohenheim-subnets-app-idblink-1", owner, Egress.NONE);
        assertThat(daemon.subnetOf(link)).as("step 3: the link takes the next free block")
            .isEqualTo("10.199.0.96/27");
        assertThat(NetworkSubnets.roomOn(docker, host).free())
            .as("step 3: 2048 blocks less the foreign network, the route, the app and its link").isEqualTo(2044);

        // 4. Two creates at the same moment both read the host before either makes its network: Docker refuses the
        //    second's block and it takes the next one.
        CountDownLatch bothRead = new CountDownLatch(2);
        HostShell racing = (script, timeoutSeconds) -> {
            bothRead.countDown();
            try {
                bothRead.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return new HostShell.Result(0, ROUTES);
        };
        int createsBefore = daemon.callCount("api:POST /networks/create");
        List<String> blocks = new ArrayList<>();
        try (ExecutorService both = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> first = both.submit(() -> raced(docker, racing, "race-one-net"));
            Future<String> second = both.submit(() -> raced(docker, racing, "race-two-net"));
            blocks.add(daemon.subnetOf(first.get(30, TimeUnit.SECONDS)));
            blocks.add(daemon.subnetOf(second.get(30, TimeUnit.SECONDS)));
        }
        assertThat(blocks).as("step 4: the two get different blocks, the next two free ones")
            .containsExactlyInAnyOrder("10.199.0.128/27", "10.199.0.160/27");
        assertThat(daemon.callCount("api:POST /networks/create") - createsBefore)
            .as("step 4: one create was refused for the overlap and tried again").isEqualTo(3);

        // 5. A range with no block left is refused in words that name the range, the count and the setting, never
        //    Docker's raw message, and no network is made.
        String range = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Networks.ADDRESS_RANGE);
        try {
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Networks.ADDRESS_RANGE, "10.199.0.0/25");
            Throwable full = catchThrowable(() -> NetworkSubnets.create(docker, host, "one-too-many-net", Map.of(),
                false));
            assertThat(full).as("step 5: the full range is refused in words")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("all 4 networks of /27 in 10.199.0.0/25")
                .hasMessageContaining(HohenheimSettings.Networks.ADDRESS_RANGE.getPath())
                .hasMessageNotContaining("predefined");
            assertThat(daemon.subnetOf("one-too-many-net")).as("step 5: and nothing was made").isNull();
        } finally {
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Networks.ADDRESS_RANGE, range);
        }
    }

    private static @NonNull String raced(@NonNull DockerClient docker, @NonNull HostShell host, @NonNull String name) {
        try {
            NetworkSubnets.create(docker, host, name, Map.of(), false);
            return name;
        } catch (IOException refused) {
            throw new IllegalStateException(refused);
        }
    }

    /** A kernel that keeps every chain applied to it and lists each back as nft renders it. */
    private static final class RememberingKernel implements NftRunner {

        /** chain -> its hook line, then its rules in order. */
        private final Map<String, List<String>> chains = new LinkedHashMap<>();

        @Override
        public synchronized @NonNull Result run(@NonNull List<String> args, @Nullable String stdin) {
            if (args.equals(List.of("-f", "-")) && stdin != null) {
                for (String line : stdin.split("\n")) {
                    String[] words = line.split(" ", 6);
                    if (words.length < 5 || !"inet".equals(words[2])) {
                        continue;
                    }
                    String chain = words[4];
                    switch (words[0] + " " + words[1]) {
                        case "add chain" -> this.chains.computeIfAbsent(chain, name -> new ArrayList<>(List.of(
                            "type filter hook " + words[5].split("hook ")[1].split(" ")[0]
                                + " priority -10; policy accept;")));
                        case "flush chain" -> this.chains.get(chain).subList(1, this.chains.get(chain).size()).clear();
                        case "add rule" -> this.chains.get(chain).add(words[5]);
                        case "delete chain" -> this.chains.remove(chain);
                        default -> {
                        }
                    }
                }
                return new Result(0, "", "");
            }
            if (args.size() == 5 && "list".equals(args.get(0)) && "chain".equals(args.get(1))) {
                List<String> lines = this.chains.get(args.get(4));
                if (lines == null) {
                    return new Result(1, "", "Error: No such file or directory");
                }
                return new Result(0, "table inet " + args.get(3) + " {\nchain " + args.get(4) + " {\n"
                    + String.join("\n", lines) + "\n}\n}\n", "");
            }
            return new Result(0, "", "");
        }

        synchronized @NonNull List<String> rulesOf(@NonNull String chain) {
            List<String> lines = this.chains.getOrDefault(chain, List.of());
            return lines.isEmpty() ? List.of() : List.copyOf(lines.subList(1, lines.size()));
        }
    }
}
