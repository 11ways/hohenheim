package be.elevenways.hohenheim.server.incus;

import be.elevenways.hohenheim.model.HostTrustSlot;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.host.HostAdmission;
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
 * own nft. Running the controller's nft and calling it a verification is precisely the
 * defect NftRunner's own note names.
 *
 * AIDEV-NOTE: the REPAIR lever is per-instance on purpose -- a shared-ACL bump reloads
 * every workload on the daemon and fails outright when any neighbour's NIC is mid
 * transition, which would let one tenant's churn get another tenant STOPPED. See
 * {@link #enforce} for the three levers that were measured and the numbers.
 *
 * AIDEV-NOTE: an Incus daemon addressed over https is verifiable exactly when its record
 * declares a TRUSTED ssh admin lane (M074 gave the ssh trust its own columns, so the TLS
 * pin no longer occupies them). A host without one, or with one that is unconfirmed or
 * quarantined, makes {@link #available} answer false, and every caller must treat that as
 * "not verified" -- never as "verified fine". Refusing to answer is not evidence of a
 * leak, which is why an unverifiable host is reported every sweep and never stopped.
 *
 * AIDEV-NOTE: since 2026-08-07 the lane is only optional for a host that does NOT accept
 * tenant workloads. {@link HostAdmission#requireKernelTruth} makes it a placement and
 * admit REQUIREMENT for any posture other than trusted_only, proven by the preflight
 * probe actually transacting on the daemon host's kernel -- a locally addressed daemon
 * satisfies it through {@link NftRunner.Sudo} and needs no ssh at all.
 */
public final class IncusKernelIsolation {

    /** Incus's own bridge-filter table; the daemon owns it and rewrites it per device. */
    public static final String TABLE = "incus";

    /**
     * The NIC device key toggled to make the daemon re-apply THIS instance's filters.
     *
     * AIDEV-NOTE: chosen because it is a semantic NO-OP. {@code false} is the daemon's
     * own default for it, so the generated ruleset is byte-identical whether the key is
     * present or absent (measured on daystrom 2026-08-05: a 36-line diff of the
     * instance's chains with and without it came back empty). What changes is only
     * whether the device config differs from what the daemon last applied, which is the
     * one thing {@code devicesUpdate} keys on. It never touches {@code security.acls}, so
     * the NIC never stops declaring isolation -- there is no window, not even a short
     * one, in which the workload's declared policy is weaker.
     */
    public static final String REAPPLY_KEY = "security.acls.default.egress.logged";

    private final @NonNull IncusClient incus;
    private final @Nullable NftRunner runner;

    /**
     * AIDEV-NOTE: TEST SEAM and the only one, the {@code WorkloadNetworkPolicy
     * .overrideForTest} precedent exactly. It is no longer needed to reach a REMOTE
     * daemon's kernel -- since M074 a host record carries its own pinned ssh admin lane
     * and IncusKernelIsolationLiveTest drives daystrom through that PRODUCTION path. What
     * it remains good for is pointing the verifier at a kernel the record does not name
     * (a lane-less host fixture, an injected failing runner); a test that uses it is
     * proving the MECHANISM, never the deployment.
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
    static @Nullable NftRunner runnerFor(@NonNull Row server) {
        NftRunner installed = runnerOverride;
        if (installed != null) {
            return installed;
        }
        if (!IncusEndpoint.of(server).https()) {
            return new NftRunner.Sudo();
        }
        if (!ServerModel.hasSshLane(server)) {
            return null;
        }
        // The lane must be TRUSTED before it is used, on the ssh slot's own terms: an
        // unconfirmed or quarantined admin lane is not a kernel we may believe. The
        // refusal is swallowed into "unavailable" on purpose -- an unusable lane leaves
        // the host UNVERIFIABLE, which is the honest verdict, never "verified fine".
        try {
            HostAdmission.requireTrustedSlot(server, HostTrustSlot.SSH);
        } catch (RuntimeException untrusted) {
            return null;
        }
        return NftRunner.forServer(server);
    }

    /** @return whether kernel truth can be READ for this host at all */
    public boolean available() {
        return this.runner != null;
    }

    /**
     * The same question as {@link #available()} asked of a RECORD, without contacting the
     * daemon: gates run on hosts whose Incus client may not even be constructible.
     *
     * <p>It answers whether a lane is DECLARED and trusted, never whether it works -- that
     * is what the preflight probe is for.
     */
    public static boolean laneAvailable(@NonNull Row server) {
        return kernelRunner(server) != null;
    }

    /** The nft lane this host's kernel truth is read through, or null when it declares none. */
    public static @Nullable NftRunner kernelRunner(@NonNull Row server) {
        return runnerFor(server);
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
                + " addressed over https and its record declares no TRUSTED ssh admin lane"
                + " (missing ssh target, unconfirmed host key, or quarantined), so hohenheim"
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
     * AIDEV-NOTE: the repair is a PER-INSTANCE device write, and which lever is used is a
     * cross-tenant safety decision, not a style choice. Three were measured live on
     * daystrom (2026-08-05):
     *  - re-writing the IDENTICAL {@code security.acls} value repairs NOTHING (0 of 3
     *    chains restored): {@code devicesUpdate} only reloads devices whose config
     *    actually changed, so a no-op write is a no-op.
     *  - bumping a config key on the SHARED isolation ACL repairs correctly, but
     *    upstream's {@code common.Update} calls {@code BridgeUpdateACLs} for EVERY NIC
     *    referencing that ACL -- which is every workload on the daemon. Measured under a
     *    neighbour starting and stopping in a loop, 21 of 103 bumps FAILED outright with
     *    "Unknown or missing host side veth device" naming the NEIGHBOUR. That makes one
     *    tenant's transitioning NIC able to block another tenant's repair, and since an
     *    unrepairable workload is STOPPED, a single neighbour could have cost every other
     *    tenant on the host their availability for a fault that was never theirs.
     *  - changing a key on OUR OWN NIC device forces the reload for our device only:
     *    3 of 3 chains restored, and 116 of 116 toggles succeeded under the same
     *    neighbour churn that broke a fifth of the ACL bumps.
     * The third is what ships. The shared ACL stays -- the coupling was in the LEVER, not
     * in sharing the policy object -- so nothing about the isolation semantics changes.
     *
     * @throws IOException when the kernel still diverges after the repair; the caller
     *                     decides what happens to the workload
     */
    public void enforce(@NonNull String handle) throws IOException {
        Divergence first = inspect(handle);
        if (first.enforced()) {
            return;
        }
        reapply(handle);
        Divergence second = inspect(handle);
        if (second.enforced()) {
            return;
        }
        throw new IOException("REFUSED to leave '" + handle + "' running: its isolation is"
            + " absent from the daemon host's kernel and the daemon did not restore it."
            + " " + second.describe());
    }

    /**
     * Make the daemon re-apply this ONE workload's NIC filters, by toggling a no-op key
     * on its own NIC devices. Nothing about any other workload is read or written.
     *
     * @throws IOException when the instance has no NIC to re-apply, or the daemon refuses
     */
    public void reapply(@NonNull String handle) throws IOException {
        Map<String, Object> instance = this.incus.instance(handle);
        if (!(instance.get("devices") instanceof Map<?, ?> devices)) {
            throw new IOException("Cannot re-apply isolation for '" + handle
                + "': the daemon reports no devices on it.");
        }
        Map<String, Object> rewritten = new LinkedHashMap<>();
        int toggled = 0;
        for (Map.Entry<?, ?> entry : devices.entrySet()) {
            String name = String.valueOf(entry.getKey());
            if (!(entry.getValue() instanceof Map<?, ?> device)) {
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            device.forEach((key, value) -> copy.put(String.valueOf(key), value));
            if ("nic".equals(String.valueOf(copy.get("type")))) {
                // Present -> absent -> present: either direction is a real config change
                // to the daemon and neither changes a single generated rule.
                if (copy.remove(REAPPLY_KEY) == null) {
                    copy.put(REAPPLY_KEY, "false");
                }
                toggled++;
            }
            rewritten.put(name, copy);
        }
        if (toggled == 0) {
            throw new IOException("Cannot re-apply isolation for '" + handle
                + "': it has no NIC device to re-apply.");
        }
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("architecture", instance.get("architecture"));
        definition.put("config", instance.get("config"));
        definition.put("devices", rewritten);
        definition.put("ephemeral", instance.get("ephemeral"));
        definition.put("profiles", instance.get("profiles"));
        definition.put("description", instance.get("description"));
        this.incus.updateInstance(handle, definition);
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
