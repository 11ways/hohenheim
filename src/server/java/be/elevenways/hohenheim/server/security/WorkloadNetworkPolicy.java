package be.elevenways.hohenheim.server.security;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.runtime.Egress;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * THE host-firewall applier for per-workload network policy: default-deny towards the
 * host, the cloud metadata range and every private range except the workload's own
 * network, applied to the kernel through nftables and VERIFIED by reading the chains back
 * out of it.
 *
 * AIDEV-NOTE: this is deliberately a SEPARATE class from {@link NftService} and it has the
 * opposite failure contract. NftService is default-off, logs every failure and returns
 * success anyway, which is correct for IP bans and catastrophic for isolation: a policy
 * materialized through it returns normally on a host where nothing was applied and the
 * container starts unisolated. Here EVERY method throws on a non-zero nft exit, and
 * {@link #apply} additionally re-lists both chains and parses them, because "nft exited 0"
 * and "the rule is in the kernel" are independent facts on a host where another tool
 * flushes tables. Do not extend NftService instead; do not inherit its silence.
 *
 * AIDEV-NOTE: the whole ruleset for one workload goes through ONE {@code nft -f -}
 * transaction. Issuing flush-then-add as separate commands leaves a window in which a
 * RUNNING workload has no policy at all, which matters on re-apply.
 *
 * AIDEV-NOTE: two base chains PER WORKLOAD rather than jump rules in one shared chain.
 * Base chains are independent -- adding or removing a workload cannot disturb another
 * one's rules, and removal is a chain delete instead of a rule-handle lookup in shared
 * output. The cost is one extra chain evaluation per packet per workload; revisit the
 * shape (a shared dispatch chain with a verdict map) only when a host runs hundreds of
 * workloads, and re-verify the read-back story if you do.
 */
public final class WorkloadNetworkPolicy {

    /** Our own table, NOT NftService's {@code inet hohenheim}: two owners, no shared flush. */
    public static final String TABLE = "hohenheim_net";

    private final @NonNull NftRunner runner;
    private final @NonNull BooleanSupplier enabled;

    /**
     * AIDEV-NOTE: TEST SEAM and the only one. Production code never calls
     * {@link #overrideForTest}; it exists because the alternative is that every test that
     * deploys an instance SKIPS on a machine without passwordless root nft, and a skipped
     * test is a green test. The override is what lets a test point the production applier
     * at a real nftables instance in a private network namespace.
     */
    private static volatile @Nullable WorkloadNetworkPolicy override;

    /** The fleet-wide enforcement switch; per-host CAPABILITY is the preflight's job. */
    private static final BooleanSupplier ENABLED_SETTING = () -> Boolean.TRUE.equals(
        HohenheimSettings.VALUES.getValue(HohenheimSettings.Security.NFTABLES_ENABLED));

    private static final WorkloadNetworkPolicy PRODUCTION = new WorkloadNetworkPolicy(
        new NftRunner.Sudo(), ENABLED_SETTING);

    public WorkloadNetworkPolicy(@NonNull NftRunner runner, @NonNull BooleanSupplier enabled) {
        this.runner = runner;
        this.enabled = enabled;
    }

    /**
     * The applier for one inventoried host's OWN kernel; the test override when one is
     * installed. There is deliberately no local-only accessor: a runtime built for a
     * remote daemon with a local applier isolates nothing while verifying everything
     * (the rules land in the controller's kernel), so the server name travels or the
     * policy does not get built.
     */
    public static @NonNull WorkloadNetworkPolicy forServer(@NonNull String serverName) {
        WorkloadNetworkPolicy installed = override;
        if (installed != null) {
            return installed;
        }
        Row server;
        try {
            server = Models.get(ServerModel.class).findByName(serverName);
        } catch (RuntimeException noInventory) {
            // No server inventory in scope (a record-less caller that never touches a
            // remote host, e.g. a SHARED_BRIDGE test): there is only the local daemon,
            // whose applier is the local sudo one. A real remote deploy always has the
            // model registered, so this fallback can never silently mis-target a host.
            return PRODUCTION;
        }
        if (server == null || !ServerModel.MODE_SSH.equals(server.get(ServerModel.MODE))) {
            return PRODUCTION;
        }
        return new WorkloadNetworkPolicy(NftRunner.forServer(server), ENABLED_SETTING);
    }

    /** @param policy the applier {@link #current()} returns, or null to restore production */
    public static void overrideForTest(@Nullable WorkloadNetworkPolicy policy) {
        override = policy;
    }

    /** @return whether kernel enforcement is switched on for this host */
    public boolean isEnabled() {
        return this.enabled.getAsBoolean();
    }

    /**
     * The refusal that distinguishes this applier from {@link NftService}: a tenant
     * workload does not start unprotected.
     *
     * AIDEV-NOTE: decided 2026-08-05 -- there is deliberately NO host-level
     * "operator-only install, enforcement off" escape from this refusal. The declared
     * posture mechanism already exists one level up ({@code NetworkPosture}, chosen
     * per KIND where the kind builds its driver, never tenant-reachable): a workload
     * that legitimately needs no per-workload policy declares SHARED_BRIDGE there and
     * never reaches this class. Any host-level off-switch would be a second, wider
     * spelling of the same thing that also disarms the kinds that DID declare
     * PRIVATE, which is exactly the silent fallback the isolation wave exists to
     * forbid. Tests run the production applier against a real nftables in a private
     * namespace instead ({@code PrivateNetns} in browserTest).
     *
     * @param workload named in the refusal so the operator knows what did not deploy
     * @throws IOException when kernel enforcement is switched off on this host
     */
    public void requireEnabled(@NonNull String workload) throws IOException {
        if (!isEnabled()) {
            throw new IOException("REFUSED to deploy '" + workload + "': per-workload network"
                + " policy is not enforceable on this host (security.nftables_enabled is off)."
                + " A tenant workload that starts without it can reach the host, the cloud"
                + " metadata service and every other container on the daemon, so it does not"
                + " start at all. Enable nftables enforcement (passwordless sudo for nft) or"
                + " run this workload on a host that has it.");
        }
    }

    /**
     * Apply (or re-apply, idempotently) the deny policy for one workload network and
     * VERIFY it landed.
     *
     * @throws IOException when enforcement is off, nft refuses, or the chains read back
     *                     without a rule we just applied
     */
    public void apply(@NonNull WorkloadNetwork network, @NonNull Egress egress)
            throws IOException {
        requireEnabled(network.name());

        String key = chainKey(network.name());
        List<String> forward = forwardRules(network, egress);
        List<String> input = inputRules(network);

        StringBuilder ruleset = new StringBuilder();
        ruleset.append("add table inet ").append(TABLE).append('\n');
        ruleset.append("add chain inet ").append(TABLE).append(' ').append(forwardChain(key))
            .append(" { type filter hook forward priority -10 ; policy accept ; }\n");
        ruleset.append("add chain inet ").append(TABLE).append(' ').append(inputChain(key))
            .append(" { type filter hook input priority -10 ; policy accept ; }\n");
        ruleset.append("flush chain inet ").append(TABLE).append(' ').append(forwardChain(key))
            .append('\n');
        ruleset.append("flush chain inet ").append(TABLE).append(' ').append(inputChain(key))
            .append('\n');
        for (String rule : forward) {
            ruleset.append("add rule inet ").append(TABLE).append(' ').append(forwardChain(key))
                .append(' ').append(rule).append('\n');
        }
        for (String rule : input) {
            ruleset.append("add rule inet ").append(TABLE).append(' ').append(inputChain(key))
                .append(' ').append(rule).append('\n');
        }

        NftRunner.Result applied = this.runner.run(List.of("-f", "-"), ruleset.toString());
        if (!applied.ok()) {
            throw new IOException("REFUSED to deploy '" + network.name() + "': nft rejected the"
                + " network policy (exit " + applied.exitCode() + "): " + applied.failureText());
        }

        verifyChain(network.name(), forwardChain(key), "forward", forward);
        verifyChain(network.name(), inputChain(key), "input", input);
    }

    /**
     * KERNEL-truth check for one workload network: whether both chains exist, are hooked,
     * and carry every rule {@link #apply} would write for the given egress. The answer
     * comes from re-listing the chains through the runner -- never from bookkeeping --
     * because a host reboot erases nftables state while Docker networks and
     * restart-policy containers come back on their own (measured on daystrom
     * 2026-08-06: after a reboot the unless-stopped container served traffic with
     * {@code nft list table inet hohenheim_net} answering "No such file or directory").
     *
     * @return false when a chain is absent, unhooked, or missing an expected rule
     * @throws IOException when enforcement is off, or the kernel cannot be READ at all
     *                     (an unreadable kernel is "unverifiable", never "unenforced")
     */
    public boolean isEnforced(@NonNull WorkloadNetwork network, @NonNull Egress egress)
            throws IOException {
        requireEnabled(network.name());
        String key = chainKey(network.name());
        return chainSatisfies(forwardChain(key), "forward", forwardRules(network, egress))
            && chainSatisfies(inputChain(key), "input", inputRules(network));
    }

    /**
     * Remove a workload's chains, VERIFIED absent afterwards; a policy that was never
     * applied is an observed no-op.
     *
     * @throws IOException when nft refuses, or a chain survives the delete
     */
    public void remove(@NonNull String networkName) throws IOException {
        requireEnabled(networkName);
        String key = chainKey(networkName);
        for (String chain : List.of(forwardChain(key), inputChain(key))) {
            if (!chainExists(chain)) {
                continue;
            }
            NftRunner.Result removed = this.runner.run(List.of("-f", "-"),
                "flush chain inet " + TABLE + ' ' + chain + '\n'
                    + "delete chain inet " + TABLE + ' ' + chain + '\n');
            if (!removed.ok()) {
                throw new IOException("Could not remove the network policy chain '" + chain
                    + "' (exit " + removed.exitCode() + "): " + removed.failureText());
            }
            if (chainExists(chain)) {
                throw new IOException("The network policy chain '" + chain + "' still exists"
                    + " after nft reported a successful delete; the kernel state of this host"
                    + " is not what nft says it is.");
            }
        }
    }

    /** @return the nft chain identifier suffix for a Docker network name */
    public static @NonNull String chainKey(@NonNull String networkName) {
        StringBuilder key = new StringBuilder(networkName.length());
        for (int i = 0; i < networkName.length(); i++) {
            char c = networkName.charAt(i);
            boolean safe = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '_';
            key.append(safe ? c : '_');
        }
        return key.toString();
    }

    public static @NonNull String forwardChain(@NonNull String key) {
        return "fwd_" + key;
    }

    public static @NonNull String inputChain(@NonNull String key) {
        return "inp_" + key;
    }

    /**
     * The forward-hook rules: what the workload may send THROUGH the host.
     *
     * AIDEV-NOTE: order is the policy. Established/related first, or the reply leg of an
     * inbound connection to a published port dies. The workload's OWN subnet is accepted
     * before the RFC1918 denies, because that subnet sits inside 172.16/12 and would
     * otherwise be denied to itself. With {@link Egress#OPEN} everything not named here
     * falls through to the chain's accept policy; {@link Egress#NONE} appends a final
     * saddr-scoped drop so nothing the workload initiates leaves at all -- the kind
     * DECLARES which, there is no ambient default.
     */
    static @NonNull List<String> forwardRules(@NonNull WorkloadNetwork network,
                                              @NonNull Egress egress) {
        List<String> rules = new ArrayList<>();
        rules.add("ct state established,related accept");
        rules.add("ip saddr " + network.ipv4Subnet() + " ip daddr " + network.ipv4Subnet()
            + " accept");
        for (String denied : TenantNetworkRanges.DENIED_V4) {
            rules.add("ip saddr " + network.ipv4Subnet() + " ip daddr " + denied + " drop");
        }
        String v6 = network.ipv6Subnet();
        if (v6 != null) {
            rules.add("ip6 saddr " + v6 + " ip6 daddr " + v6 + " accept");
            for (String denied : TenantNetworkRanges.DENIED_V6) {
                rules.add("ip6 saddr " + v6 + " ip6 daddr " + denied + " drop");
            }
        }
        if (egress == Egress.NONE) {
            rules.add("ip saddr " + network.ipv4Subnet() + " drop");
            if (v6 != null) {
                rules.add("ip6 saddr " + v6 + " drop");
            }
        }
        return List.copyOf(rules);
    }

    /**
     * The input-hook rules: what the workload may send TO THE HOST ITSELF.
     *
     * AIDEV-NOTE: the bridge gateway IS the host, so this is one drop, not a per-service
     * list -- every host service bound to 0.0.0.0 (the reverse proxy on 80/443 with the
     * admin panel behind it, authoritative DNS on 53) is reachable from a container
     * without it. Docker's embedded resolver lives INSIDE the container's namespace at
     * 127.0.0.11, so container DNS does not traverse this hook and is unaffected.
     */
    static @NonNull List<String> inputRules(@NonNull WorkloadNetwork network) {
        List<String> rules = new ArrayList<>();
        rules.add("ct state established,related accept");
        rules.add("ip saddr " + network.ipv4Subnet() + " drop");
        String v6 = network.ipv6Subnet();
        if (v6 != null) {
            rules.add("ip6 saddr " + v6 + " drop");
        }
        return List.copyOf(rules);
    }

    private boolean chainExists(String chain) throws IOException {
        return readChain(chain) != null;
    }

    /**
     * The kernel's own rendering of one chain as normalized lines, or null when the
     * chain (or the whole table) does not exist.
     *
     * @throws IOException when nft fails for any reason other than absence
     */
    private @Nullable List<String> readChain(String chain) throws IOException {
        NftRunner.Result listed = this.runner.run(
            List.of("list", "chain", "inet", TABLE, chain), null);
        if (!listed.ok()) {
            if (listed.failureText().contains("No such file or directory")) {
                return null;   // observed absent (the whole point of a kernel-truth check)
            }
            throw new IOException("Could not read back the network policy chain '" + chain
                + "' (exit " + listed.exitCode() + "): " + listed.failureText());
        }
        List<String> present = new ArrayList<>();
        for (String line : listed.stdout().split("\n")) {
            present.add(line.trim().replaceAll("\\s+", " "));
        }
        return present;
    }

    /** Whether the chain exists, hooks the given hook, and carries every expected rule. */
    private boolean chainSatisfies(String chain, String hook, List<String> expected)
            throws IOException {
        List<String> present = readChain(chain);
        if (present == null) {
            return false;
        }
        boolean hooked = present.stream().anyMatch(line -> line.startsWith("type filter hook "
            + hook + " ") && line.contains("policy accept"));
        if (!hooked) {
            return false;
        }
        return present.containsAll(expected);
    }

    /**
     * Re-list one chain and require every rule we applied to be present in the kernel's own
     * rendering of it.
     */
    private void verifyChain(String networkName, String chain, String hook, List<String> expected)
            throws IOException {
        List<String> present;
        try {
            present = readChain(chain);
        } catch (IOException unreadable) {
            throw new IOException("REFUSED to deploy '" + networkName + "': nft accepted the"
                + " policy but the " + hook + " chain '" + chain + "' cannot be read back: "
                + unreadable.getMessage());
        }
        if (present == null) {
            throw new IOException("REFUSED to deploy '" + networkName + "': nft accepted the"
                + " policy but the " + hook + " chain '" + chain + "' does not exist in the"
                + " kernel. Something on this host is flushing our table.");
        }
        boolean hooked = present.stream().anyMatch(line -> line.startsWith("type filter hook "
            + hook + " ") && line.contains("policy accept"));
        if (!hooked) {
            throw new IOException("REFUSED to deploy '" + networkName + "': chain '" + chain
                + "' exists but is not hooked into " + hook + "; it would filter nothing."
                + " Kernel state: " + String.join(" | ", present));
        }
        for (String rule : expected) {
            if (!present.contains(rule)) {
                throw new IOException("REFUSED to deploy '" + networkName + "': nft reported"
                    + " success but the kernel does not carry the rule '" + rule + "' in chain '"
                    + chain + "'. Something on this host is flushing our table. Kernel state: "
                    + String.join(" | ", present));
            }
        }
    }
}
