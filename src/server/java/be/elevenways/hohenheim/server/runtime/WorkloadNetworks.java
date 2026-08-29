package be.elevenways.hohenheim.server.runtime;

import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.security.WorkloadNetwork;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.util.Map;

/**
 * THE private-network half of every Docker-backed workload tier (instances, site
 * release containers, managed databases, build sandboxes): one user-defined Docker
 * network per workload, owner-labelled at birth, with the host deny policy applied to
 * the kernel BEFORE any container is created on it.
 *
 * AIDEV-NOTE: network-per-WORKLOAD, not network-per-tenant. It is the shape StackDeployer
 * already proved (per-stack network, labels at creation, attached in the create body) and
 * it introduces no new concept. Per-tenant grouping layers on top later by keying the
 * network on the packed manage-subject set instead of the handle -- the ownership key
 * exists (HohenheimAccess.packSubjects, already the quota ledger's bucket key); it is
 * simply not what the first slice needs.
 *
 * AIDEV-NOTE: network-per-workload has a HOST CEILING and it is Docker's, not ours. The
 * daemon's default address pool ({@code 172.17.0.0/12} in /16 chunks plus a 192.168 range)
 * runs out after roughly thirty user-defined networks, after which network creation fails
 * with "all predefined address pools have been fully subnetted" -- which surfaces as a
 * REFUSED deploy, loudly, rather than as a container quietly sharing someone else's
 * network. A host expected to run more instances than that needs
 * {@code default-address-pools} in daemon.json sized for it (e.g. base 10.0.0.0/8 with
 * size 24); that is a host-preflight item, and hohenheim does not own daemon.json.
 *
 * AIDEV-NOTE: the policy is applied BEFORE the container exists, which is strictly
 * stronger than the create-stopped/apply/start ordering: there is no moment at which a
 * container of ours is attached to a network whose policy has not landed. Re-apply on
 * start covers the host that rebooted -- Docker networks survive a reboot, nftables rules
 * do not.
 */
public final class WorkloadNetworks {

    /** Suffix that turns a workload handle into its network name. */
    public static final String SUFFIX = "-net";

    private WorkloadNetworks() {}

    /** @return the private network name of one instance handle */
    public static @NonNull String networkName(@NonNull String handle) {
        return handle + SUFFIX;
    }

    /**
     * Ensure the instance's private network exists, is ours, and has a verified kernel
     * policy; create it when absent.
     *
     * @param ownerLabels the owning record's labels, which the network must carry
     * @return the network name to attach the container to
     * @throws IOException when policy enforcement is off, the daemon refuses, a
     *                     same-named network is not attributably ours, or the policy could
     *                     not be verified in the kernel
     */
    public static @NonNull String ensure(@NonNull DockerClient docker,
                                         @NonNull WorkloadNetworkPolicy policy,
                                         @NonNull String handle,
                                         @NonNull Map<String, String> ownerLabels,
                                         @NonNull Egress egress)
            throws IOException {
        return ensure(docker, policy, handle, ownerLabels, egress, false);
    }

    /**
     * {@link #ensure(DockerClient, WorkloadNetworkPolicy, String, Map, Egress)} with the
     * network's Docker {@code Internal} flag declared.
     *
     * AIDEV-NOTE: a LINK network is always internal. A member sits on it BESIDE its own
     * per-instance network, and Docker hands a container's default route to whichever of
     * its networks sorts FIRST BY NAME (`idblink-...` before `instance-...`), so a
     * non-internal link network became the workspace's default gateway on starfleet
     * (2026-08-29) and every packet to the internet died in the link chain's deliberate
     * egress drop: no DNS, no git clone, `source_checkout_failed`. An internal network is
     * excluded from gateway selection, has no masquerade, and carries exactly what a link
     * means: member-to-member traffic. An existing network of ours whose flag disagrees is
     * recreated when nothing is attached and refused by name otherwise, because the flag
     * is immutable on a live network.
     */
    public static @NonNull String ensure(@NonNull DockerClient docker,
                                         @NonNull WorkloadNetworkPolicy policy,
                                         @NonNull String handle,
                                         @NonNull Map<String, String> ownerLabels,
                                         @NonNull Egress egress,
                                         boolean internal)
            throws IOException {
        String name = networkName(handle);
        // Loudest, cheapest refusal first: nothing reaches the daemon on a host that
        // cannot enforce the policy.
        policy.requireEnabled(name);

        Map<String, Object> existing = docker.findNetworkByName(name);
        boolean created = false;
        if (existing != null) {
            requireOwnedBy(name, existing.get("Labels"), ownerLabels);
            if (Boolean.TRUE.equals(existing.get("Internal")) != internal) {
                if (existing.get("Containers") instanceof Map<?, ?> members && !members.isEmpty()) {
                    throw new IOException("Network '" + name + "' exists with Internal="
                        + Boolean.TRUE.equals(existing.get("Internal")) + " but must be "
                        + internal + "; it still has " + members.size()
                        + " member(s) attached, so redeploy those first and it is recreated");
                }
                docker.removeNetwork(name);
                existing = null;
            }
        }
        if (existing == null) {
            docker.createNetwork(name, ownerLabels, null, null, false, internal);
            created = true;
        }

        try {
            policy.apply(WorkloadNetwork.fromInspect(docker.inspectNetwork(name)), egress);
        } catch (IOException e) {
            if (created) {
                // An unenforced network of ours is debris, not a resource: remove what this
                // call made rather than leaving a usable unprotected network behind.
                try {
                    docker.removeNetwork(name);
                } catch (IOException ignored) {
                    // the throw below is the outcome; a stuck network is the reconciler's
                }
            }
            throw e;
        }
        return name;
    }

    /**
     * Re-apply the policy for an already-created instance before it is started.
     *
     * @throws IOException when enforcement is off, the network is gone, it is not
     *                     attributably ours, or the policy could not be verified
     */
    public static void ensureForStart(@NonNull DockerClient docker,
                                      @NonNull WorkloadNetworkPolicy policy,
                                      @NonNull String handle,
                                      @NonNull Egress egress) throws IOException {
        String name = networkName(handle);
        policy.requireEnabled(name);

        Map<String, Object> existing = docker.findNetworkByName(name);
        if (existing == null) {
            throw new IOException("REFUSED to start '" + handle + "': its private network '"
                + name + "' does not exist, so the container would fall back to no isolation"
                + " at all. Redeploy the instance.");
        }
        OwnerLabels.Owner owner = OwnerLabels.parse(
            existing.get("Labels") instanceof Map<?, ?> labels ? labels : null);
        if (!OwnerLabels.isOurs(owner)) {
            throw new IOException("REFUSED to start '" + handle + "': the network '" + name
                + "' is not attributably ours (" + (owner == null
                    ? "no hohenheim owner labels"
                    : "owned by controller '" + owner.controller() + "'")
                + "), and we will not enforce a tenant policy onto another controller's or a"
                + " stranger's network.");
        }
        policy.apply(WorkloadNetwork.fromInspect(docker.inspectNetwork(name)), egress);
    }

    /**
     * Remove the kernel policy and the network itself, VERIFIED: an absent network is an
     * observed no-op, an undeletable one is an error.
     *
     * @throws IOException when the policy chains or the network survive
     */
    public static void teardown(@NonNull DockerClient docker,
                                @NonNull WorkloadNetworkPolicy policy,
                                @NonNull String handle) throws IOException {
        String name = networkName(handle);
        policy.remove(name);
        try {
            docker.removeNetwork(name);
        } catch (DockerClient.ApiException e) {
            if (!e.isNotFound()) {
                throw e;   // still attached, or the daemon refused: NOT gone
            }
        }
    }

    private static void requireOwnedBy(String name, Object labels, Map<String, String> expected)
            throws IOException {
        Map<?, ?> map = labels instanceof Map<?, ?> found ? found : Map.of();
        boolean ours = expected.entrySet().stream()
            .allMatch(entry -> entry.getValue().equals(map.get(entry.getKey())));
        if (!ours) {
            throw new IOException("REFUSED to use network '" + name + "': it exists but is not"
                + " attributably ours (labels: " + map + "). A same-named foreign network is a"
                + " name collision, not an isolation boundary we may enforce policy onto;"
                + " remove it explicitly if it is debris.");
        }
    }
}
