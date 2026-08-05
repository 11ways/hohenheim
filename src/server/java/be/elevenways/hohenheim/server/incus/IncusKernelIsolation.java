package be.elevenways.hohenheim.server.incus;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.security.NftRunner;
import be.elevenways.hohenheim.server.security.TenantNetworkRanges;
import be.elevenways.zenit.common.orm.datasource.Row;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * KERNEL-truth verification of the Incus tier's isolation: what {@code nft} on the
 * daemon's own host actually carries for the live tap of every NIC, not what the daemon
 * says it configured.
 *
 * AIDEV-NOTE: this class exists because {@link IncusNetworkPolicy}'s read-back verifies
 * the DAEMON'S CONFIGURATION, and on 2026-08-05 those two facts were observed to
 * disagree on daystrom: after an unexpected guest reset incusd restarted QEMU in place,
 * the NIC teardown failed ("Failed to detach interface ... invalid argument") and the
 * restarted VM's live tap carried no reject rules at all, while {@code incus config
 * show} still reported {@code security.acls: hohenheim-isolation} and the ACL still read
 * back with every tenant-range reject. The VM reached its peer AND the host. Verified
 * live: config isolated, {@code table bridge incus} empty, ping 0% loss both ways.
 *
 * AIDEV-NOTE: the upstream mechanics, read out of incus v7.3.0 (the newest release; the
 * code is byte-identical on main as of 2026-08-05, so this is NOT a version-gated
 * bridge and upgrading does not fix it). {@code nicBridged.postStop} detaches the tap
 * BEFORE it removes the firewall chains, and returns on the detach error, so a failed
 * detach skips {@code removeFilters} entirely. Every generated rule is scoped to the tap
 * name ({@code iifname "tapXXXX"}) while the chains are named after instance+device and
 * carry {@code policy accept} -- so a chain surviving a failed teardown still names the
 * DEAD tap, the restarted workload's new tap matches nothing, and the accept policy
 * wins. That is why this verifier keys on the CURRENT tap name and not on chain
 * existence: a table full of rules for a tap that no longer exists is the failure mode,
 * and counting rules would report success.
 *
 * AIDEV-NOTE: the runner is obtained through {@link NftRunner#forServer} -- the same lane
 * HostPreflight and WorkloadNetworkPolicy use -- and NEVER falls back to the controller's
 * own nft. An Incus daemon addressed over https has no shell lane on its host record
 * (the ssh columns carry the daemon's TLS material), so kernel truth is UNAVAILABLE
 * there: {@link #available} answers false and every caller must treat that as "not
 * verified", never as "verified fine". Running the controller's nft and calling it a
 * verification is precisely the defect NftRunner's own note names.
 */
public final class IncusKernelIsolation {

    /** Incus's own bridge-filter table; the daemon owns it and rewrites it per device. */
    public static final String TABLE = "incus";

    /** The ACL config key bumped to make the daemon re-apply every referencing NIC. */
    public static final String REAPPLY_KEY = "user.hohenheim-reapply";

    private final @NonNull IncusClient incus;
    private final @Nullable NftRunner runner;

    /**
     * AIDEV-NOTE: TEST SEAM and the only one, the {@code WorkloadNetworkPolicy
     * .overrideForTest} precedent exactly. The live Incus host is reached over https and
     * therefore has no product shell lane, so without this every kernel-truth test would
     * SKIP -- and a skipped test is a green test. The override lets a live test point the
     * PRODUCTION verifier at the real daemon host's real nftables.
     */
    private static volatile @Nullable NftRunner runnerOverride;

    public IncusKernelIsolation(@NonNull IncusClient incus, @Nullable NftRunner runner) {
        this.incus = incus;
        this.runner = runner;
    }

    /** @param runner the nft lane every verifier uses, or null to restore production */
    public static void overrideRunnerForTest(@Nullable NftRunner runner) {
        runnerOverride = runner;
    }

    /**
     * The verifier for one inventoried Incus host.
     *
     * @throws IllegalArgumentException when the record does not declare the incus runtime
     */
    public static @NonNull IncusKernelIsolation forServer(@NonNull Row server) {
        return new IncusKernelIsolation(IncusClients.forServer(server), runnerFor(server));
    }

    /**
     * The nft lane that provably reaches the DAEMON'S kernel, or null when the record
     * offers none.
     *
     * <p>A unix-socket endpoint means the daemon runs on this machine, so the local sudo
     * runner is that daemon's kernel. An https endpoint needs a declared ssh lane; the
     * local runner would be the controller's kernel and would verify nothing.
     */
    private static @Nullable NftRunner runnerFor(@NonNull Row server) {
        NftRunner installed = runnerOverride;
        if (installed != null) {
            return installed;
        }
        if (!IncusEndpoint.of(server).https()) {
            return new NftRunner.Sudo();
        }
        if (ServerModel.MODE_SSH.equals(server.get(ServerModel.MODE))) {
            return NftRunner.forServer(server);
        }
        return null;
    }

    /** @return whether kernel truth can be READ for this host at all */
    public boolean available() {
        return this.runner != null;
    }

    /** What one workload's isolation looks like in the kernel; empty {@code missing} = enforced. */
    public record Divergence(@NonNull String handle, @NonNull List<String> taps,
                             @NonNull List<String> missing) {

        public boolean enforced() {
            return this.missing.isEmpty();
        }

        /** The operator-facing sentence; never a bare count. */
        public @NonNull String describe() {
            return "'" + this.handle + "' live taps " + this.taps + " are missing kernel"
                + " isolation rules: " + this.missing;
        }
    }

    /**
     * Read the kernel and report which tenant-range rules are missing for the workload's
     * CURRENT taps.
     *
     * @throws IOException when no kernel lane exists, or nft cannot be read -- an
     *                     unreadable kernel is never a pass
     */
    public @NonNull Divergence inspect(@NonNull String handle) throws IOException {
        NftRunner nft = this.runner;
        if (nft == null) {
            throw new IOException("REFUSED to report on '" + handle + "': this Incus host is"
                + " addressed over https and its record carries no ssh lane, so hohenheim"
                + " cannot read the daemon host's nftables. Running the controller's own nft"
                + " here would verify the wrong kernel.");
        }
        List<String> taps = liveTaps(handle);
        if (taps.isEmpty()) {
            throw new IOException("REFUSED to report on '" + handle + "': the daemon names no"
                + " live host interface for any of its NICs, so there is nothing to check the"
                + " kernel against. A running workload always has one.");
        }
        NftRunner.Result listed = nft.run(List.of("list", "table", "bridge", TABLE), null);
        if (!listed.ok()) {
            throw new IOException("REFUSED to report on '" + handle + "': nft could not list"
                + " 'table bridge " + TABLE + "' on the daemon's host (exit "
                + listed.exitCode() + "): " + listed.failureText());
        }
        return new Divergence(handle, taps, missingRules(listed.stdout(), taps));
    }

    /**
     * Verify kernel truth, REPAIR it once through the daemon's own lever when it
     * diverges, and re-verify.
     *
     * AIDEV-NOTE: decided 2026-08-05 -- the repair is an ACL UPDATE, not a device write.
     * Upstream's {@code common.Update} calls {@code BridgeUpdateACLs} unconditionally
     * (it does not diff), which reloads every running bridge NIC referencing the ACL, and
     * {@code removeChains} is keyed by instance+device rather than by tap, so the reload
     * removes a chain left naming a dead tap and rebuilds it against the live one. A
     * device write is NOT equivalent: {@code devicesUpdate} only reloads devices whose
     * config actually changed, so re-writing the same value is a no-op -- measured live
     * on daystrom, where re-setting the identical {@code security.acls} value repaired
     * nothing and the ACL bump repaired all three chains. The known cost is the fanout
     * IncusNetworkPolicy's note already records: the reload touches every referencing
     * NIC. For a repair that is the point, not a hazard -- one bump repairs every
     * diverged workload on the daemon.
     *
     * @throws IOException when the kernel still diverges after the repair; the caller
     *                     decides what happens to the workload
     */
    public void enforce(@NonNull String handle) throws IOException {
        Divergence first = inspect(handle);
        if (first.enforced()) {
            return;
        }
        reapply();
        Divergence second = inspect(handle);
        if (second.enforced()) {
            return;
        }
        throw new IOException("REFUSED to leave '" + handle + "' running: its isolation is"
            + " absent from the daemon host's kernel and the daemon did not restore it."
            + " " + second.describe());
    }

    /**
     * Make the daemon re-apply the isolation ACL to every NIC referencing it, by writing
     * a changed config key onto the ACL (the only supported re-apply lever Incus has).
     */
    public void reapply() throws IOException {
        Map<String, Object> existing = this.incus.networkAcl(IncusNetworkPolicy.ACL_NAME);
        if (existing == null) {
            throw new IOException("Cannot re-apply isolation: the '"
                + IncusNetworkPolicy.ACL_NAME + "' ACL does not exist on this daemon.");
        }
        Map<String, Object> config = new LinkedHashMap<>();
        if (existing.get("config") instanceof Map<?, ?> current) {
            current.forEach((key, value) -> config.put(String.valueOf(key), value));
        }
        config.put(REAPPLY_KEY, String.valueOf(System.currentTimeMillis()));

        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("name", IncusNetworkPolicy.ACL_NAME);
        definition.put("description", existing.get("description"));
        definition.put("egress", existing.get("egress"));
        definition.put("ingress", existing.get("ingress"));
        definition.put("config", config);
        this.incus.updateNetworkAcl(IncusNetworkPolicy.ACL_NAME, definition);
    }

    /** The host interface names the daemon currently has bound to this workload's NICs. */
    private @NonNull List<String> liveTaps(@NonNull String handle) throws IOException {
        Map<String, Object> instance = this.incus.instance(handle);
        List<String> names = new ArrayList<>();
        if (!(instance.get("devices") instanceof Map<?, ?> devices)
                || !(instance.get("config") instanceof Map<?, ?> config)) {
            return names;
        }
        for (Map.Entry<?, ?> entry : devices.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> device)
                    || !"nic".equals(String.valueOf(device.get("type")))) {
                continue;
            }
            Object host = config.get("volatile." + entry.getKey() + ".host_name");
            if (host instanceof String name && !name.isBlank()) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * Every (tap, range) pair the kernel does NOT carry a drop/reject rule for.
     *
     * AIDEV-NOTE: the match is deliberately three-way -- the rule must name the tap as
     * {@code iifname}, name the range as the destination AND end in a drop or reject.
     * Incus's generated chains also carry ACCEPT rules naming the same tap and the same
     * address family, so anything looser reports a leaking workload as isolated.
     */
    static @NonNull List<String> missingRules(@NonNull String ruleset,
                                              @NonNull List<String> taps) {
        List<String> lines = new ArrayList<>();
        for (String line : ruleset.split("\n")) {
            lines.add(line.trim().replaceAll("\\s+", " "));
        }
        List<String> missing = new ArrayList<>();
        for (String tap : taps) {
            for (String range : TenantNetworkRanges.DENIED_V4) {
                if (!carries(lines, tap, "ip daddr " + range)) {
                    missing.add(tap + " -> " + range);
                }
            }
            for (String range : TenantNetworkRanges.DENIED_V6) {
                if (!carries(lines, tap, "ip6 daddr " + range)) {
                    missing.add(tap + " -> " + range);
                }
            }
        }
        return List.copyOf(missing);
    }

    private static boolean carries(@NonNull List<String> lines, @NonNull String tap,
                                   @NonNull String destination) {
        String iif = "iifname \"" + tap + "\"";
        for (String line : lines) {
            if (line.contains(iif) && line.contains(destination)
                    && (line.contains(" drop") || line.contains(" reject"))) {
                return true;
            }
        }
        return false;
    }
}
