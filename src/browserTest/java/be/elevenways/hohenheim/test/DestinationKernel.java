package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.server.security.FlowAccounting;
import be.elevenways.hohenheim.server.security.NftRunner;
import be.elevenways.hohenheim.server.security.WorkloadNetwork;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A fake of one host's kernel as the destination reader sees it: the policy table's listing as nft renders it, built
 * from accounting sets and their counted elements, and every ruleset the applier hands it, applied to that state.
 *
 * AIDEV-NOTE: the listing is rendered in the shape nft 1.1 prints (measured: tabs, one element per line after the
 * first, "counter packets N bytes M expires T"); AppDestinationsTest also reads the older counter-after-expiry order.
 * A ruleset applies its add, flush and delete of sets, chains and rules, so a sweep that does not converge shows. A
 * chain listing answers absent, so a teardown's isolation chain removal is an observed no-op.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class DestinationKernel implements NftRunner {

    private final Map<String, Map<String, String>> sets = new LinkedHashMap<>();
    private final Map<String, List<String>> chains = new LinkedHashMap<>();
    private final List<String> applied = new ArrayList<>();
    private int listings;
    private String failure;
    private boolean table = true;

    /** @return the production applier over this fake kernel, enforcement on */
    public WorkloadNetworkPolicy policy() {
        return new WorkloadNetworkPolicy(this, () -> true);
    }

    /** Adds a network's accounting, its sets empty and its chains with exactly the rules the applier writes. */
    public DestinationKernel accounting(WorkloadNetwork network) {
        String key = WorkloadNetworkPolicy.chainKey(network.name());
        for (FlowAccounting.Family family : FlowAccounting.families(network)) {
            for (FlowAccounting.Via via : FlowAccounting.Via.values()) {
                for (FlowAccounting.Counter counter : FlowAccounting.Counter.values()) {
                    this.sets.putIfAbsent(FlowAccounting.setName(via, counter, family, key), new LinkedHashMap<>());
                }
            }
        }
        FlowAccounting.rules(network).forEach((chain, rules) -> this.chains.put(chain, new ArrayList<>(rules)));
        this.table = true;
        return this;
    }

    /** Removes every set and chain of the kernel: a reboot, or a table flushed by another tool. */
    public DestinationKernel clear() {
        this.sets.clear();
        this.chains.clear();
        return this;
    }

    /**
     * Sets one element's counter, creating its set when the network's accounting did not.
     *
     * @param key the network's chain key
     */
    public DestinationKernel element(FlowAccounting.Via via, FlowAccounting.Counter counter, String key,
                                     String source, String destination, String protocol, int port, long packets,
                                     long bytes) {
        FlowAccounting.Family family = source.indexOf(':') >= 0 ? FlowAccounting.Family.V6
            : FlowAccounting.Family.V4;
        String prefix = source + " . " + destination + " . " + protocol + " . " + port;
        this.sets.computeIfAbsent(FlowAccounting.setName(via, counter, family, key), ignored -> new LinkedHashMap<>())
            .put(prefix, prefix + " counter packets " + packets + " bytes " + bytes + " expires 14m58s310ms");
        return this;
    }

    /** Removes one element, as its expiry does. */
    public DestinationKernel expire(FlowAccounting.Via via, FlowAccounting.Counter counter, String key,
                                    String source, String destination, String protocol, int port) {
        FlowAccounting.Family family = source.indexOf(':') >= 0 ? FlowAccounting.Family.V6
            : FlowAccounting.Family.V4;
        Map<String, String> elements = this.sets.get(FlowAccounting.setName(via, counter, family, key));
        if (elements != null) {
            elements.remove(source + " . " + destination + " . " + protocol + " . " + port);
        }
        return this;
    }

    /** Makes every listing fail with this text, null to answer again. */
    public DestinationKernel failing(String text) {
        this.failure = text;
        return this;
    }

    /** @return every ruleset handed to {@code nft -f -}, in order */
    public List<String> applied() {
        return this.applied;
    }

    /** @return how many table listings were asked */
    public int listings() {
        return this.listings;
    }

    /** @return the table as nft lists it */
    public String render() {
        StringBuilder out = new StringBuilder("table inet ").append(WorkloadNetworkPolicy.table()).append(" {\n");
        this.sets.forEach((set, elements) -> {
            out.append("\tset ").append(set).append(" {\n")
                .append("\t\ttype ipv4_addr . ipv4_addr . inet_proto . inet_service\n")
                .append("\t\tsize 16384\t# count ").append(elements.size()).append('\n')
                .append("\t\tflags dynamic,timeout\n\t\tcounter\n\t\ttimeout 15m\n");
            if (!elements.isEmpty()) {
                out.append("\t\telements = { ").append(String.join(",\n\t\t\t     ", elements.values()))
                    .append(" }\n");
            }
            out.append("\t}\n\n");
        });
        this.chains.forEach((chain, rules) -> {
            out.append("\tchain ").append(chain).append(" {\n")
                .append("\t\ttype filter hook forward priority filter + 10; policy accept;\n");
            for (String rule : rules) {
                out.append("\t\t").append(rule).append('\n');
            }
            out.append("\t}\n\n");
        });
        return out.append("}\n").toString();
    }

    @Override
    public Result run(List<String> nftArgs, String stdin) {
        if (nftArgs.equals(List.of("-f", "-"))) {
            this.applied.add(stdin);
            for (String line : stdin.split("\n")) {
                apply(line.split(" ", 6));
            }
            return new Result(0, "", "");
        }
        if (nftArgs.size() >= 2 && nftArgs.get(0).equals("list") && nftArgs.get(1).equals("table")) {
            this.listings++;
            if (this.failure != null) {
                return new Result(1, "", this.failure);
            }
            return this.table ? new Result(0, render(), "") : new Result(1, "", "Error: No such file or directory");
        }
        return new Result(1, "", "Error: No such file or directory");
    }

    /** Applies one ruleset line, its words up to the rule: command, object, family, table, name, rest. */
    private void apply(String[] words) {
        if (words.length >= 2 && (words[0] + " " + words[1]).equals("add table")) {
            this.table = true;
        }
        if (words.length >= 2 && (words[0] + " " + words[1]).equals("delete table")) {
            this.table = false;
            clear();
        }
        if (words.length < 5) {
            return;
        }
        String name = words[4];
        switch (words[0] + " " + words[1]) {
            case "add set" -> this.sets.putIfAbsent(name, new LinkedHashMap<>());
            case "add chain" -> this.chains.putIfAbsent(name, new ArrayList<>());
            case "flush chain" -> this.chains.computeIfPresent(name, (chain, rules) -> new ArrayList<>());
            case "add rule" -> this.chains.computeIfAbsent(name, chain -> new ArrayList<>()).add(words[5]);
            case "delete chain" -> this.chains.remove(name);
            case "delete set" -> this.sets.remove(name);
            default -> { }
        }
    }
}
