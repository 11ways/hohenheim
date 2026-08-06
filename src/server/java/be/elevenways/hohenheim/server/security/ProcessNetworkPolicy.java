package be.elevenways.hohenheim.server.security;

import be.elevenways.hohenheim.HohenheimSettings;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * THE isolation applier for the managed-process tier: an OUTPUT-hook chain keyed on a
 * site's run-as uid, denying the same {@link TenantNetworkRanges} vocabulary every
 * container tier is denied, applied to the kernel through nftables and VERIFIED by
 * reading the chain back out of it.
 *
 * AIDEV-NOTE: this tier needs its own applier because the tenant-NETWORK model cannot
 * reach it. A managed process is a host process ({@code SystemUsers.executionBuilder} ->
 * sudo-as-uid -> ProcessBuilder), so it lives in the host's own network namespace: there
 * is no subnet to key {@code saddr} on and locally-originated traffic never traverses the
 * forward hook where {@link WorkloadNetworkPolicy}'s rules live. The identity that DOES
 * distinguish one process site from another on that host is its per-site run-as uid, which
 * {@code WorkloadIdentity} already makes exclusive (no two sites share a system user, and
 * never the daemon's own). {@code meta skuid} is the kernel's name for that identity.
 * The failure contract is {@link WorkloadNetworkPolicy}'s and deliberately not
 * {@link NftService}'s: every method throws, and a site whose policy cannot be applied
 * does not spawn.
 *
 * AIDEV-NOTE: DECIDED 2026-08-06 -- the DNS carve-out, the one decision the container
 * tiers never had to make. A container resolves through Docker's embedded resolver at
 * 127.0.0.11 INSIDE its own namespace, so denying RFC1918 costs it no DNS. A host process
 * resolves through /etc/resolv.conf, which on a real host commonly names a PRIVATE-range
 * resolver directly (this workstation: {@code nameserver 10.47.0.2}), and denying
 * 10.0.0.0/8 outright would break every process site's name resolution. So: the nameservers
 * of /etc/resolv.conf are read at apply time and each one that falls inside a denied range
 * gets an accept rule scoped to THAT address and to port 53 (udp and tcp) only, emitted
 * before the drops. Consequences, stated so nobody has to rediscover them:
 * (1) on a host whose resolver is a loopback stub (systemd-resolved's 127.0.0.53, daystrom)
 * the carve-out set is EMPTY -- loopback is not in the denied vocabulary, so the ruleset is
 * maximally tight and the stub's own upstream traffic belongs to systemd-resolved's uid,
 * not the site's; (2) on a host whose resolver is a private address the carve-out is
 * exactly one address on one port, so the resolver host stays reachable for DNS and for
 * nothing else -- a process site cannot use the hole to reach the admin panel or a database
 * that happens to run on the same machine; (3) an operator whose resolver sits in a denied
 * range and who wants NO hole punched at all points /etc/resolv.conf at a loopback stub
 * (systemd-resolved, dnsmasq, unbound) and the carve-out disappears by itself.
 * DoT/DoH resolvers (853/443) are NOT carved out: a private-range resolver reached over
 * those ports stays denied, and that is deliberate rather than overlooked -- port 443 to a
 * private address is exactly the hole this tier exists to close.
 * TRACKING: resolv.conf is re-read on every apply, and every apply happens on every process
 * spawn plus every sweep tick, so a changed resolver converges within one restart or one
 * five-minute tick; nothing caches it.
 *
 * AIDEV-NOTE: this tier needs nft >= 1.0 and fails CLOSED without it. nftables prints
 * {@code meta skuid} as the /etc/passwd NAME when uid/gid resolution is on -- opt-in via
 * {@code -u} since 1.0.0 (measured on 1.1.6: plain {@code list chain} prints the number
 * even for a uid that has a passwd entry), but the DEFAULT on 0.9.x. There is no numeric
 * flag we can pass to force it: every {@code -n} level also renders {@code ct state
 * established,related} as {@code ct state 0x2,0x4}, so the flag would break the read-back
 * of every chain instead of one rule. On an nft that resolves names the read-back simply
 * does not carry the rule we wrote, {@link #apply} refuses, and the site does not start
 * with the kernel state printed in the refusal -- loud and safe, never a silent pass.
 *
 * AIDEV-NOTE: LOOPBACK is deliberately NOT denied, and this is the tier's named residual.
 * 127.0.0.0/8 is not in {@link TenantNetworkRanges} for any tier, but only here can a
 * workload use it to reach the host: a managed process CAN still reach a host service bound
 * to 127.0.0.1. It has to be able to -- its own IPC channel is a loopback port
 * ({@code HOHENHEIM_IPC_PORT}), its upstream listener is a loopback port, and attached
 * managed databases are injected into host-process sites as loopback addresses by design
 * ({@code DatabaseEnvInjection}, pinned by EnvInjectionFlowTest). Closing that would mean a
 * per-process netns, which is the other mechanism this slice weighed and rejected as much
 * larger. What IS closed is every host service reachable on the host's own private
 * addresses, the whole rest of the private network, and the metadata service.
 */
public final class ProcessNetworkPolicy {

    /** The same table the network-keyed policy owns: one table, per-workload base chains. */
    public static final String TABLE = WorkloadNetworkPolicy.TABLE;

    /** The DNS port the resolver carve-out is scoped to; nothing else is punched through. */
    private static final int DNS_PORT = 53;

    private static final Path SYSTEM_RESOLV_CONF = Path.of("/etc/resolv.conf");

    private final @NonNull NftRunner runner;
    private final @NonNull NftChains chains;
    private final @NonNull BooleanSupplier enabled;
    private final @NonNull Path resolvConf;

    /** The same TEST SEAM, and the same reason: see {@link WorkloadNetworkPolicy}. */
    private static volatile @Nullable ProcessNetworkPolicy override;

    private static final BooleanSupplier ENABLED_SETTING = () -> Boolean.TRUE.equals(
        HohenheimSettings.VALUES.getValue(HohenheimSettings.Security.NFTABLES_ENABLED));

    private static final ProcessNetworkPolicy PRODUCTION = new ProcessNetworkPolicy(
        new NftRunner.Sudo(), ENABLED_SETTING, SYSTEM_RESOLV_CONF);

    public ProcessNetworkPolicy(@NonNull NftRunner runner, @NonNull BooleanSupplier enabled,
                                @NonNull Path resolvConf) {
        this.runner = runner;
        this.chains = new NftChains(runner, TABLE);
        this.enabled = enabled;
        this.resolvConf = resolvConf;
    }

    /**
     * The applier for the managed-process tier.
     *
     * AIDEV-NOTE: there is no {@code forServer} counterpart and there must not be one.
     * Managed processes are children of THIS controller ({@code ProcessBuilder}), so the
     * kernel that must carry their rules is always the local one -- the remote-host
     * mis-targeting {@code NftRunner.forServer} exists to prevent cannot arise here.
     */
    public static @NonNull ProcessNetworkPolicy current() {
        ProcessNetworkPolicy installed = override;
        return installed != null ? installed : PRODUCTION;
    }

    /** @param policy the applier {@link #current()} returns, or null to restore production */
    public static void overrideForTest(@Nullable ProcessNetworkPolicy policy) {
        override = policy;
    }

    /** @return whether kernel enforcement is switched on for this host */
    public boolean isEnabled() {
        return this.enabled.getAsBoolean();
    }

    /**
     * The refusal that keeps this tier's contract identical to every other one: a site
     * process that cannot be isolated does not start, and the refusal names it.
     *
     * @throws IOException when kernel enforcement is switched off on this host
     */
    public void requireEnabled(@NonNull String site) throws IOException {
        if (!isEnabled()) {
            throw new IOException("REFUSED to start site '" + site + "': per-process network"
                + " policy is not enforceable on this host (security.nftables_enabled is off)."
                + " A site process runs in the host's own network namespace, so without it the"
                + " site can reach the cloud metadata service and every service on the host's"
                + " private network. Enable nftables enforcement (passwordless sudo for nft) or"
                + " run this site on a host that has it.");
        }
    }

    /**
     * Apply (or re-apply, idempotently) the deny policy for one site's run-as uid and
     * VERIFY it landed.
     *
     * @param uid  the site's run-as uid, the identity {@code meta skuid} matches on
     * @param site named in every refusal so the operator knows what did not start
     * @throws IOException when enforcement is off, nft refuses, or the chain reads back
     *                     without a rule we just applied
     */
    public void apply(int uid, @NonNull String site) throws IOException {
        requireEnabled(site);
        String chain = outputChain(uid);
        List<String> rules = outputRules(uid, resolvers());

        StringBuilder ruleset = new StringBuilder();
        ruleset.append("add table inet ").append(TABLE).append('\n');
        ruleset.append("add chain inet ").append(TABLE).append(' ').append(chain)
            .append(" { type filter hook output priority -10 ; policy accept ; }\n");
        ruleset.append("flush chain inet ").append(TABLE).append(' ').append(chain).append('\n');
        for (String rule : rules) {
            ruleset.append("add rule inet ").append(TABLE).append(' ').append(chain)
                .append(' ').append(rule).append('\n');
        }

        NftRunner.Result applied = this.runner.run(List.of("-f", "-"), ruleset.toString());
        if (!applied.ok()) {
            throw new IOException("REFUSED to start site '" + site + "': nft rejected the"
                + " per-process network policy for uid " + uid + " (exit " + applied.exitCode()
                + "): " + applied.failureText());
        }
        this.chains.verify("REFUSED to start site '" + site + "'", chain, "output", rules);
    }

    /**
     * KERNEL-truth check for one run-as uid: whether the chain exists, is hooked, and
     * carries every rule {@link #apply} would write right now. The answer comes from
     * re-listing the chain, never from bookkeeping.
     *
     * @return false when the chain is absent, unhooked, or missing an expected rule
     * @throws IOException when enforcement is off, or the kernel cannot be READ at all
     */
    public boolean isEnforced(int uid, @NonNull String site) throws IOException {
        requireEnabled(site);
        return this.chains.satisfies(outputChain(uid), "output", outputRules(uid, resolvers()));
    }

    /**
     * Remove one uid's chain, VERIFIED absent afterwards.
     *
     * @throws IOException when enforcement is off, nft refuses, or the chain survives
     */
    public void remove(int uid, @NonNull String site) throws IOException {
        requireEnabled(site);
        this.chains.remove(outputChain(uid));
    }

    /** @return the nft chain carrying one run-as uid's denies */
    public static @NonNull String outputChain(int uid) {
        return "out_uid_" + uid;
    }

    /**
     * The output-hook rules: what a process running as this uid may send anywhere.
     *
     * AIDEV-NOTE: order is the policy. Established/related first, or the reply leg of an
     * inbound connection to the site's listener dies. The resolver carve-outs come next,
     * because the addresses they name sit INSIDE the ranges the drops below deny. Nothing
     * else the process sends is touched: this tier declares open egress (a site legitimately
     * calls out to the internet), and what it loses is the private network it shares with
     * the control plane.
     */
    static @NonNull List<String> outputRules(int uid, @NonNull List<InetAddress> resolvers) {
        String owner = "meta skuid " + uid + ' ';
        List<String> rules = new ArrayList<>();
        rules.add("ct state established,related accept");
        for (InetAddress resolver : resolvers) {
            String family = resolver instanceof Inet4Address ? "ip" : "ip6";
            String daddr = family + " daddr " + resolver.getHostAddress();
            rules.add(owner + daddr + " udp dport " + DNS_PORT + " accept");
            rules.add(owner + daddr + " tcp dport " + DNS_PORT + " accept");
        }
        for (String denied : TenantNetworkRanges.DENIED_V4) {
            rules.add(owner + "ip daddr " + denied + " drop");
        }
        for (String denied : TenantNetworkRanges.DENIED_V6) {
            rules.add(owner + "ip6 daddr " + denied + " drop");
        }
        return List.copyOf(rules);
    }

    /**
     * The nameservers of this host's resolv.conf that need a carve-out: the ones a denied
     * range would otherwise swallow, deduplicated, in file order.
     *
     * AIDEV-NOTE: an unreadable resolv.conf yields NO carve-out rather than an exception.
     * The tight ruleset is the safe answer -- a host with no readable resolver
     * configuration has no resolver to protect, and refusing to apply any policy because
     * one file could not be read would trade the whole boundary for DNS convenience.
     */
    public @NonNull List<InetAddress> resolvers() {
        List<String> lines;
        try {
            lines = Files.readAllLines(this.resolvConf);
        } catch (IOException | RuntimeException unreadable) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        List<InetAddress> carved = new ArrayList<>();
        for (String line : lines) {
            String text = line.trim();
            if (text.startsWith("#") || text.startsWith(";") || !text.startsWith("nameserver")) {
                continue;
            }
            String[] parts = text.split("\\s+");
            if (parts.length < 2) {
                continue;
            }
            InetAddress address = TenantNetworkRanges.parseLiteral(parts[1]);
            if (address == null || !TenantNetworkRanges.denies(address)) {
                continue;   // absent, not a literal, or already reachable: no hole needed
            }
            if (seen.add(address.getHostAddress())) {
                carved.add(address);
            }
        }
        return List.copyOf(carved);
    }
}
