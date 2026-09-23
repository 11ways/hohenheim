package be.elevenways.hohenheim.server.security;

import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The empty-table removal against a concurrent deploy, over a fake kernel that renders tables
 * and chains the way nft does: the list-then-delete window can no longer swallow a policy
 * that was applied inside it.
 */
class NftTableMutationLockTest {

    private static boolean initialized = false;

    @BeforeAll
    static void initDb() throws Exception {
        if (initialized) return;
        initialized = true;
        TestDatabases.freshDatabase();
        HohenheimTestRuntime.ensureBooted();
    }

    @Test
    void aDeployCannotLandBetweenTheEmptinessCheckAndTheTableDelete() throws Exception {
        FakeKernel kernel = new FakeKernel();
        String table = ProcessNetworkPolicy.table();
        ProcessNetworkPolicy remover = new ProcessNetworkPolicy(kernel, () -> true,
            Path.of("/nonexistent/hohenheim-test/resolv.conf"));
        // A SECOND applier instance on the same kernel: appliers are built per call, so the
        // exclusion must not depend on sharing one object.
        ProcessNetworkPolicy deployer = new ProcessNetworkPolicy(kernel, () -> true,
            Path.of("/nonexistent/hohenheim-test/resolv.conf"));

        // 1. The table exists and is empty: the last workload in it is gone.
        kernel.run(List.of("-f", "-"), "add table inet " + table + "\n");

        // 2. The remover's emptiness listing is held open -- the race window.
        kernel.holdNextTableListing();
        AtomicReference<Throwable> removerFailure = new AtomicReference<>();
        Thread removing = Thread.ofPlatform().start(() -> {
            try {
                remover.remove(1001, "gone");
            } catch (Throwable failure) {
                removerFailure.set(failure);
            }
        });
        assertThat(kernel.listingHeld.await(5, TimeUnit.SECONDS))
            .as("step 2: the remover has seen an empty table and not yet deleted it").isTrue();

        // 3. A deploy starts inside that window. It must wait, not write.
        AtomicReference<Throwable> deployFailure = new AtomicReference<>();
        Thread deploying = Thread.ofPlatform().start(() -> {
            try {
                deployer.apply(1002, "fresh");
            } catch (Throwable failure) {
                deployFailure.set(failure);
            }
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (deploying.getState() != Thread.State.WAITING
                && deploying.isAlive() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(kernel.sawChain("out_uid_1002"))
            .as("step 3: the deploy did not write while the removal was in flight").isFalse();
        assertThat(deploying.getState())
            .as("step 3: it is parked on the table lock").isEqualTo(Thread.State.WAITING);

        // 4. The removal finishes (deleting the empty table), THEN the deploy runs.
        kernel.releaseListing.countDown();
        removing.join(5_000);
        deploying.join(5_000);
        assertThat(removerFailure.get()).as("step 4: the removal succeeded").isNull();
        assertThat(deployFailure.get()).as("step 4: the deploy verified its own chain").isNull();

        // 5. The deploy's policy survived: table and chain are in the kernel.
        assertThat(deployer.isEnforced(1002, "fresh"))
            .as("step 5: the fresh workload is still isolated after the concurrent removal")
            .isTrue();
    }

    /** A kernel that stores what nft -f applies and lists it back in nft's own shape. */
    private static final class FakeKernel implements NftRunner {

        private final Object state = new Object();
        private boolean tableExists;
        private final Map<String, String> hooks = new LinkedHashMap<>();
        private final Map<String, List<String>> rules = new LinkedHashMap<>();
        private final List<String> seenChains = new ArrayList<>();

        final CountDownLatch listingHeld = new CountDownLatch(1);
        final CountDownLatch releaseListing = new CountDownLatch(1);
        private volatile boolean holdNext;

        void holdNextTableListing() {
            this.holdNext = true;
        }

        boolean sawChain(String chain) {
            synchronized (this.state) {
                return this.seenChains.contains(chain);
            }
        }

        @Override
        public NftRunner.Result run(List<String> args, @Nullable String stdin) {
            if (args.equals(List.of("-f", "-"))) {
                synchronized (this.state) {
                    for (String line : stdin.split("\n")) {
                        apply(line.trim());
                    }
                }
                return new NftRunner.Result(0, "", "");
            }
            if (args.size() == 5 && args.get(0).equals("list") && args.get(1).equals("chain")) {
                synchronized (this.state) {
                    String chain = args.get(4);
                    if (!this.tableExists || !this.hooks.containsKey(chain)) {
                        return absent();
                    }
                    return new NftRunner.Result(0, "table inet " + args.get(3) + " {\n"
                        + renderChain(chain) + "}\n", "");
                }
            }
            if (args.size() == 4 && args.get(0).equals("list") && args.get(1).equals("table")) {
                NftRunner.Result listed;
                synchronized (this.state) {
                    if (!this.tableExists) {
                        listed = absent();
                    } else {
                        StringBuilder body = new StringBuilder("table inet " + args.get(3) + " {\n");
                        for (String chain : this.hooks.keySet()) {
                            body.append(renderChain(chain));
                        }
                        listed = new NftRunner.Result(0, body.append("}\n").toString(), "");
                    }
                }
                // The answer is computed BEFORE the hold: that is exactly the stale view a
                // real list-then-delete acts on.
                if (this.holdNext) {
                    this.holdNext = false;
                    this.listingHeld.countDown();
                    try {
                        this.releaseListing.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return listed;
            }
            return new NftRunner.Result(1, "", "unexpected nft call " + args);
        }

        private void apply(String line) {
            String[] words = line.split("\\s+");
            if (line.isEmpty()) {
                return;
            }
            if (line.startsWith("add table")) {
                this.tableExists = true;
            } else if (line.startsWith("add chain")) {
                String chain = words[4];
                String hook = words[words.length - 1].equals("}")
                    ? line.replaceAll(".*hook (\\w+).*", "$1") : "";
                this.hooks.putIfAbsent(chain, hook);
                this.rules.putIfAbsent(chain, new ArrayList<>());
                this.seenChains.add(chain);
            } else if (line.startsWith("flush chain")) {
                this.rules.getOrDefault(words[4], new ArrayList<>()).clear();
            } else if (line.startsWith("add rule")) {
                String prefix = String.join(" ", List.of(words).subList(0, 5)) + " ";
                this.rules.get(words[4]).add(line.substring(prefix.length()));
            } else if (line.startsWith("delete chain")) {
                this.hooks.remove(words[4]);
                this.rules.remove(words[4]);
            } else if (line.startsWith("delete table")) {
                this.tableExists = false;
                this.hooks.clear();
                this.rules.clear();
            }
        }

        private String renderChain(String chain) {
            StringBuilder out = new StringBuilder("\tchain " + chain + " {\n");
            out.append("\t\ttype filter hook ").append(this.hooks.get(chain))
                .append(" priority -10; policy accept;\n");
            for (String rule : this.rules.get(chain)) {
                out.append("\t\t").append(rule).append('\n');
            }
            return out.append("\t}\n").toString();
        }

        private static NftRunner.Result absent() {
            return new NftRunner.Result(1, "", "Error: No such file or directory");
        }
    }
}
