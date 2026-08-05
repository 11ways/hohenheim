package be.elevenways.hohenheim.server.incus;

import be.elevenways.hohenheim.server.runtime.Egress;
import be.elevenways.hohenheim.server.security.TenantNetworkRanges;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * THE per-instance isolation applier for the Incus tier: the daemon-side counterpart of
 * {@code WorkloadNetworkPolicy}, expressed in Incus's OWN firewall vocabulary (a network
 * ACL and a per-NIC device override) rather than in raw nftables, and with the SAME
 * throwing, read-back-verifying contract.
 *
 * AIDEV-NOTE: this is deliberately NOT nftables. Incus manages the bridge's kernel
 * ruleset itself ({@code table inet incus}) and rewrites it on every network reload, so a
 * hohenheim-owned nft table beside it would be flushed out from under us -- exactly the
 * "another tool flushes the table" hazard {@code WorkloadNetworkPolicy}'s read-back
 * guards against, except here it is the daemon we share the host with. The daemon's own
 * ACL mechanism survives its reloads because the daemon writes it; so isolation for Incus
 * rides the daemon, and this class VERIFIES the daemon actually carries what we asked for
 * (an ACL that exists but whose rules read back empty, or a NIC whose config never took,
 * are both refusals -- "the API returned 200" and "the rule is in the daemon" are
 * independent facts here too).
 *
 * AIDEV-NOTE: the isolation ACL is ONE shared object, not one per instance. Its egress
 * REJECT rules name the static tenant ranges ({@link TenantNetworkRanges}) -- the same
 * vocabulary the Docker applier denies -- so they are identical for every instance and
 * every bridge subnet (a managed incusbr0 always sits inside 10/8, 172.16/12 or
 * 192.168/16, and its ULA v6 subnet inside fc00::/7). Rejecting those ranges is what
 * makes a peer, the host and the metadata address unreachable in one static rule set;
 * Incus's implicit DNS/DHCP-to-host allows sit ABOVE the ACL rejects in the generated
 * chain, so name resolution keeps working (verified on a real host). Egress to the public
 * internet is untouched unless the workload DECLARES {@link Egress#NONE}, which flips the
 * NIC's default egress action to drop.
 */
public final class IncusNetworkPolicy {

    /** The one shared isolation ACL every tenant instance's NIC references. */
    public static final String ACL_NAME = "hohenheim-isolation";

    /** The NIC every managed system container attaches (the default profile's device). */
    public static final String NIC = "eth0";

    /**
     * The managed bridge EXTRA NICs attach to. The daemon refuses a second NIC on the
     * instance's primary network ("Instance DNS name conflict", observed live), so
     * additional NICs land on this hohenheim-owned bridge -- created once with the
     * daemon's own auto-assigned subnets and NAT, and every NIC on it carries the same
     * isolation ACL as the primary. 15 chars exactly: the kernel ifname limit.
     */
    public static final String EXTRA_NETWORK = "hohenheim-extra";

    private final @NonNull IncusClient incus;

    public IncusNetworkPolicy(@NonNull IncusClient incus) {
        this.incus = incus;
    }

    /**
     * Ensure the shared isolation ACL exists with exactly the tenant-range egress
     * rejects, VERIFIED by reading it back.
     *
     * @throws IOException when the daemon refuses, or the ACL reads back without a rule
     *                     we just wrote (its ACL support is not doing what it claims)
     */
    public void ensureIsolationAcl() throws IOException {
        List<Map<String, Object>> wantEgress = isolationEgressRules();
        Map<String, Object> existing = this.incus.networkAcl(ACL_NAME);
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("name", ACL_NAME);
        definition.put("description", "hohenheim tenant isolation: deny host, metadata and"
            + " every private range; the internet and DNS stay reachable");
        definition.put("egress", wantEgress);
        definition.put("ingress", List.of());
        definition.put("config", Map.of());

        // AIDEV-NOTE: the write is CONDITIONAL, and that is load-bearing, not an
        // optimization. An ACL update makes the daemon re-trigger a device update on
        // EVERY NIC referencing the ACL, so an unconditional PUT couples one workload's
        // deploy to the health of every other instance on the daemon (observed live:
        // a freshly imported clone still carrying its source's duplicate MAC made the
        // retrigger fail 409 on an UNRELATED instance). When the daemon already carries
        // exactly what we want, nothing is written; the read-back verification below
        // still runs either way.
        if (existing == null) {
            try {
                this.incus.createNetworkAcl(definition);
            } catch (IncusClient.ApiException raced) {
                // AIDEV-NOTE: two deploys on a FRESH host both read "no ACL" and both
                // POST it; the loser gets 400 "already exists" and used to fail the whole
                // install for it (observed live 2026-08-05 with the ACL absent and seven
                // live classes deploying at once: "install_failed ... The network ACL
                // already exists"). Having the ACL is the goal, not having been the one
                // to create it -- and the read-back below is the gate either way, so a
                // winner that wrote something DIFFERENT is still caught. Any other
                // refusal still propagates.
                if (!raced.isAlreadyExists()) {
                    throw raced;
                }
            }
        } else if (!carriesExactly(existing, wantEgress)) {
            this.incus.updateNetworkAcl(ACL_NAME, definition);
        }

        Map<String, Object> readBack = this.incus.networkAcl(ACL_NAME);
        if (readBack == null) {
            throw new IOException("REFUSED to isolate on this Incus host: the daemon accepted"
                + " the '" + ACL_NAME + "' ACL but it does not read back. Its network-ACL"
                + " support is not enforcing what it reports.");
        }
        List<String> present = destinationsOf(readBack.get("egress"));
        for (String range : TenantNetworkRanges.DENIED_V4) {
            requirePresent(present, range);
        }
        for (String range : TenantNetworkRanges.DENIED_V6) {
            requirePresent(present, range);
        }
    }

    /**
     * The device override that attaches the isolation ACL (and the declared egress
     * posture) to one instance's NIC, merged onto the network the default profile's NIC
     * inherits.
     *
     * @param inheritedNetwork the managed network the profile's NIC sits on (incusbr0)
     * @return the {@code devices.eth0} map to place in the instance definition
     */
    public @NonNull Map<String, Object> nicDevice(@NonNull String inheritedNetwork,
                                                  @NonNull Egress egress) {
        Map<String, Object> device = new LinkedHashMap<>();
        device.put("type", "nic");
        device.put("network", inheritedNetwork);
        device.put("security.acls", ACL_NAME);
        // The DEFAULT actions decide what a packet not named by a rule does. Ingress stays
        // allow (an inbound connection's reply leg is handled by the reject rules being
        // egress-only). Egress is allow for OPEN (the internet), drop for NONE.
        device.put("security.acls.default.ingress.action", "allow");
        device.put("security.acls.default.egress.action",
            egress == Egress.NONE ? "drop" : "allow");
        return device;
    }

    /**
     * Read back one instance's NIC and require it to carry the isolation ACL and the
     * declared egress action -- the "the API returned 200 but the config never took"
     * guard.
     *
     * @throws IOException when the instance has no isolating NIC, or its egress action
     *                     disagrees with what was declared
     */
    public void verifyInstance(@NonNull String handle, @NonNull Map<String, Object> instance,
                               @NonNull Egress egress) throws IOException {
        Object devices = instance.get("devices");
        Map<?, ?> nic = devices instanceof Map<?, ?> map && map.get(NIC) instanceof Map<?, ?> eth
            ? eth : null;
        if (nic == null) {
            throw new IOException("REFUSED to run '" + handle + "': its '" + NIC + "' device"
                + " carries no isolation config, so the container would share the bridge with"
                + " every other tenant. The daemon did not apply the device override.");
        }
        if (!ACL_NAME.equals(String.valueOf(nic.get("security.acls")))) {
            throw new IOException("REFUSED to run '" + handle + "': its '" + NIC + "' device"
                + " does not reference the '" + ACL_NAME + "' ACL (security.acls="
                + nic.get("security.acls") + "). The isolation the deploy verified is gone.");
        }
        String wantAction = egress == Egress.NONE ? "drop" : "allow";
        String gotAction = String.valueOf(nic.get("security.acls.default.egress.action"));
        if (!wantAction.equals(gotAction)) {
            throw new IOException("REFUSED to run '" + handle + "': its declared egress ("
                + egress + " => " + wantAction + ") is not what the daemon carries ("
                + gotAction + ").");
        }
    }

    /**
     * Ensure the shared extra-NIC bridge exists and reads back as a managed network
     * with an address to hand out -- the same write-then-verify contract as the ACL.
     *
     * @throws IOException when the daemon refuses or the network reads back unusable
     */
    public void ensureExtraNetwork() throws IOException {
        Map<String, Object> existing = this.incus.network(EXTRA_NETWORK);
        if (existing == null) {
            Map<String, Object> definition = new LinkedHashMap<>();
            definition.put("name", EXTRA_NETWORK);
            definition.put("type", "bridge");
            definition.put("description",
                "hohenheim extra-NIC bridge (isolation ACL rides every NIC)");
            // Empty config on purpose: the daemon auto-assigns free v4/v6 subnets and
            // enables NAT, exactly what `incus network create` does.
            definition.put("config", Map.of());
            this.incus.createNetwork(definition);
        }
        Map<String, Object> readBack = this.incus.network(EXTRA_NETWORK);
        boolean managed = readBack != null && Boolean.TRUE.equals(readBack.get("managed"));
        String ipv4 = readBack != null && readBack.get("config") instanceof Map<?, ?> config
            && config.get("ipv4.address") instanceof String address ? address : null;
        if (!managed || ipv4 == null || ipv4.isBlank() || "none".equals(ipv4)) {
            throw new IOException("REFUSED to attach an extra NIC on this Incus host: the '"
                + EXTRA_NETWORK + "' bridge does not read back as a managed network with an"
                + " IPv4 subnet (managed=" + managed + ", ipv4.address=" + ipv4 + ").");
        }
    }

    /** The device map of one EXTRA NIC on the shared secondary bridge, ACL attached. */
    public @NonNull Map<String, Object> extraNicDevice(@NonNull Egress egress) {
        return nicDevice(EXTRA_NETWORK, egress);
    }

    /**
     * Read back EVERY nic-type device of the instance and require each to carry the
     * isolation ACL and the declared egress action -- an extra NIC must never be a
     * hole in the boundary the primary one enforces.
     *
     * @throws IOException naming the first NIC that is not isolated
     */
    public void verifyAllNics(@NonNull String handle, @NonNull Map<String, Object> instance,
                              @NonNull Egress egress) throws IOException {
        verifyInstance(handle, instance, egress);
        if (!(instance.get("devices") instanceof Map<?, ?> devices)) {
            return;
        }
        for (Map.Entry<?, ?> entry : devices.entrySet()) {
            String name = String.valueOf(entry.getKey());
            if (NIC.equals(name) || !(entry.getValue() instanceof Map<?, ?> device)
                    || !"nic".equals(String.valueOf(device.get("type")))) {
                continue;
            }
            if (!ACL_NAME.equals(String.valueOf(device.get("security.acls")))) {
                throw new IOException("REFUSED to run '" + handle + "': its extra NIC '"
                    + name + "' does not reference the '" + ACL_NAME + "' ACL"
                    + " (security.acls=" + device.get("security.acls") + "). An extra NIC"
                    + " without the isolation ACL is a hole in the tenant boundary.");
            }
            String wantAction = egress == Egress.NONE ? "drop" : "allow";
            String gotAction = String.valueOf(device.get("security.acls.default.egress.action"));
            if (!wantAction.equals(gotAction)) {
                throw new IOException("REFUSED to run '" + handle + "': extra NIC '" + name
                    + "' declares egress " + wantAction + " but the daemon carries "
                    + gotAction + ".");
            }
        }
    }

    /** Remove the shared ACL; only safe once no instance references it (teardown/tests). */
    public void removeIsolationAcl() throws IOException {
        try {
            this.incus.deleteNetworkAcl(ACL_NAME);
        } catch (IncusClient.ApiException e) {
            if (!e.isNotFound()) {
                throw e;
            }
        }
    }

    /** The egress reject rules: one per tenant range, both address families. */
    static @NonNull List<Map<String, Object>> isolationEgressRules() {
        List<Map<String, Object>> rules = new ArrayList<>();
        for (String range : TenantNetworkRanges.DENIED_V4) {
            rules.add(rejectTo(range));
        }
        for (String range : TenantNetworkRanges.DENIED_V6) {
            rules.add(rejectTo(range));
        }
        return rules;
    }

    private static Map<String, Object> rejectTo(String destination) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("action", "reject");
        rule.put("destination", destination);
        rule.put("state", "enabled");
        return rule;
    }

    /** @return whether an existing ACL already carries exactly the wanted egress rejects and no ingress rules */
    static boolean carriesExactly(@NonNull Map<String, Object> existing,
                                  @NonNull List<Map<String, Object>> wantEgress) {
        List<String> want = new ArrayList<>();
        for (Map<String, Object> rule : wantEgress) {
            want.add(String.valueOf(rule.get("destination")));
        }
        List<String> got = destinationsOf(existing.get("egress"));
        int egressCount = existing.get("egress") instanceof List<?> rules ? rules.size() : 0;
        boolean noIngress = !(existing.get("ingress") instanceof List<?> ingress)
            || ingress.isEmpty();
        return noIngress && egressCount == want.size() && got.size() == want.size()
            && got.containsAll(want);
    }

    private static void requirePresent(List<String> present, String range) throws IOException {
        if (!present.contains(range)) {
            throw new IOException("REFUSED to isolate on this Incus host: the '" + ACL_NAME
                + "' ACL read back without the egress reject to '" + range + "'. Something"
                + " on this host rewrote it. Present destinations: " + present);
        }
    }

    private static @NonNull List<String> destinationsOf(@Nullable Object egress) {
        List<String> destinations = new ArrayList<>();
        if (egress instanceof List<?> rules) {
            for (Object entry : rules) {
                // A "disabled" rule reads back but enforces NOTHING, so it does not count.
                if (entry instanceof Map<?, ?> rule && "reject".equals(rule.get("action"))
                        && !"disabled".equals(rule.get("state"))
                        && rule.get("destination") instanceof String destination) {
                    destinations.add(destination);
                }
            }
        }
        return destinations;
    }
}
