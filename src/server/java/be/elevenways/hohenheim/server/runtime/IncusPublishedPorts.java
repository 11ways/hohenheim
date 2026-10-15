package be.elevenways.hohenheim.server.runtime;

import be.elevenways.hohenheim.server.incus.IncusClient;
import be.elevenways.zenit.common.net.IpRanges;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The port publications of an Incus workload: one NAT {@code proxy} device per publication, forwarding a port of every
 * host address to the guest's eth0, which is pinned to a static address of the managed bridge for it.
 *
 * AIDEV-NOTE: NAT mode is the only proxy mode Incus offers a VM, and it refuses to start a proxy whose connect address
 * is not one of the instance's static IPv4 addresses ("Connect IP ... must be one of the instance's static IPv4
 * addresses", measured on Incus 7.3), which is why the guest is pinned. The daemon validates that only at START: an
 * import checks eth0's address against the bridge subnet ("Device IP address ... not within network ... subnet") and
 * never a proxy's connect, so a restore or migration overrides eth0 alone and rewrites the proxies after the import.
 * NAT mode binds no socket (it writes DNAT rules), so a host port another program listens on is shadowed, not refused:
 * the port ledger is the only guard.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
final class IncusPublishedPorts {

    /** The prefix of every proxy device this driver owns; a converge rewrites exactly these. */
    static final String DEVICE_PREFIX = "hohport-";

    /** The NIC key carrying the pinned guest address. */
    static final String ADDRESS_KEY = "ipv4.address";

    /** The bind a publication listens on: every IPv4 address of the host. */
    static final String WILDCARD = "0.0.0.0";

    private IncusPublishedPorts() {
    }

    /** @return the device name of one publication, derived from it so a converge finds its own */
    static @NonNull String deviceName(@NonNull PortPublication publication) {
        return DEVICE_PREFIX + publication.protocol() + "-" + publication.containerPort();
    }

    /**
     * Replace the managed proxy devices in {@code devices} with one per publication of {@code spec}, forwarding to
     * {@code guestAddress}.
     *
     * AIDEV-NOTE: the host port is the claimed one, else the declared one, else the port the device already listens
     * on (an import of a migration whose port was never declared): the deploy's converge always has a claim, the
     * import lanes may not.
     *
     * @param guestAddress the pinned eth0 address, null only when the spec publishes nothing
     * @throws IOException for a loopback publication (a VM publishes on every host address only), or one whose host
     *                     port nothing names
     */
    static void apply(@NonNull InstanceSpec spec, @Nullable String guestAddress,
                      @NonNull Map<String, Object> devices) throws IOException {
        Map<String, Object> previous = new LinkedHashMap<>();
        devices.forEach((name, device) -> {
            if (name.startsWith(DEVICE_PREFIX)) {
                previous.put(name, device);
            }
        });
        devices.keySet().removeIf(name -> name.startsWith(DEVICE_PREFIX));
        for (PortPublication publication : spec.publications()) {
            if (!publication.publicExposure()) {
                throw new IOException("REFUSED to publish port " + publication.containerPort() + "/"
                    + publication.protocol() + " of '" + spec.handle() + "' on loopback: an Incus workload publishes"
                    + " through a NAT proxy on every address of its host, never on loopback alone");
            }
            String name = deviceName(publication);
            Integer hostPort = publication.preallocatedPort() != null ? publication.preallocatedPort()
                : publication.declaredHostPort() != null ? publication.declaredHostPort()
                : listenPortOf(previous.get(name));
            if (hostPort == null || guestAddress == null) {
                throw new IOException("REFUSED to publish port " + publication.containerPort() + "/"
                    + publication.protocol() + " of '" + spec.handle() + "': no host port was claimed for it");
            }
            Map<String, Object> device = new LinkedHashMap<>();
            device.put("type", "proxy");
            device.put("nat", "true");
            device.put("listen", publication.protocol() + ":" + WILDCARD + ":" + hostPort);
            device.put("connect", publication.protocol() + ":" + guestAddress + ":" + publication.containerPort());
            devices.put(name, device);
        }
    }

    /** @return the bindings the managed proxy devices of one instance definition declare, in device order */
    static @NonNull List<InstanceStatus.PublishedPort> publishedOf(@NonNull Map<String, Object> instance) {
        List<InstanceStatus.PublishedPort> published = new ArrayList<>();
        if (!(instance.get("devices") instanceof Map<?, ?> devices)) {
            return published;
        }
        devices.forEach((name, value) -> {
            if (String.valueOf(name).startsWith(DEVICE_PREFIX) && value instanceof Map<?, ?> device
                    && "proxy".equals(device.get("type"))) {
                Address listen = Address.parse(device.get("listen"));
                Address connect = Address.parse(device.get("connect"));
                if (listen != null && connect != null) {
                    published.add(new InstanceStatus.PublishedPort(connect.port(), listen.protocol(), listen.port(),
                        listen.host()));
                }
            }
        });
        return published;
    }

    /**
     * The guest address eth0 is pinned to on {@code network}: the NIC's current one while it lies in the subnet and no
     * other instance holds it, else the instance's own lease (so pinning a running guest keeps the address it has),
     * else the highest free address of the subnet, skipping the gateway and every lease, static ones included.
     *
     * @param currentNic the instance's eth0 device as the daemon holds it, or null for none (a create, an import)
     * @throws IOException when the bridge has no IPv4 subnet or no address of it is free
     */
    static @NonNull String guestAddress(@NonNull IncusClient incus, @NonNull String network, @NonNull String handle,
                                        @Nullable Object currentNic) throws IOException {
        Map<String, Object> bridge = incus.network(network);
        Subnet subnet = bridge != null && bridge.get("config") instanceof Map<?, ?> config
            ? Subnet.parse(config.get(ADDRESS_KEY)) : null;
        if (subnet == null) {
            throw new IOException("REFUSED to publish ports of '" + handle + "': the managed bridge '" + network
                + "' has no IPv4 subnet to pin the guest's address in");
        }
        Set<Long> others = new HashSet<>();
        Long ownLease = null;
        for (Map<String, Object> lease : incus.networkLeases(network)) {
            Long address = Subnet.toLong(lease.get("address"));
            if (address == null) {
                continue;
            }
            if (handle.equals(lease.get("hostname"))) {
                ownLease = ownLease == null && subnet.contains(address) ? address : ownLease;
            } else {
                others.add(address);
            }
        }
        Long current = currentNic instanceof Map<?, ?> nic ? Subnet.toLong(nic.get(ADDRESS_KEY)) : null;
        if (current != null && subnet.usable(current) && !others.contains(current)) {
            return Subnet.toText(current);
        }
        if (ownLease != null && subnet.usable(ownLease) && !others.contains(ownLease)) {
            return Subnet.toText(ownLease);
        }
        for (long address = subnet.broadcast() - 1; address > subnet.network(); address--) {
            if (subnet.usable(address) && !others.contains(address)) {
                return Subnet.toText(address);
            }
        }
        throw new IOException("REFUSED to publish ports of '" + handle + "': no address of " + network
            + "'s subnet is free to pin the guest to");
    }

    /** The eth0 override an import carries: the address pinned on the destination, or none. */
    static @NonNull Map<String, String> importedNic(@Nullable String guestAddress) {
        // An empty value unsets the key (measured): a source pin in another subnet would refuse the import.
        return Map.of(ADDRESS_KEY, guestAddress == null ? "" : guestAddress);
    }

    private static @Nullable Integer listenPortOf(@Nullable Object device) {
        Address listen = device instanceof Map<?, ?> map ? Address.parse(map.get("listen")) : null;
        return listen == null ? null : listen.port();
    }

    /** A proxy address, {@code <protocol>:<host>:<port>}. */
    private record Address(@NonNull String protocol, @NonNull String host, int port) {

        static @Nullable Address parse(@Nullable Object value) {
            if (!(value instanceof String text)) {
                return null;
            }
            int first = text.indexOf(':');
            int last = text.lastIndexOf(':');
            if (first <= 0 || last <= first) {
                return null;
            }
            try {
                return new Address(text.substring(0, first), text.substring(first + 1, last),
                    Integer.parseInt(text.substring(last + 1)));
            } catch (NumberFormatException malformed) {
                return null;
            }
        }
    }

    /** An IPv4 subnet in CIDR form, its gateway the address the bridge itself carries. */
    private record Subnet(long gateway, long network, long broadcast) {

        static @Nullable Subnet parse(@Nullable Object cidr) {
            if (!(cidr instanceof String text) || text.indexOf('/') < 0) {
                return null;
            }
            Long gateway = toLong(text.substring(0, text.indexOf('/')));
            int prefix;
            try {
                prefix = Integer.parseInt(text.substring(text.indexOf('/') + 1));
            } catch (NumberFormatException malformed) {
                return null;
            }
            if (gateway == null || prefix < 1 || prefix > 30) {
                return null;
            }
            long mask = (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
            long network = gateway & mask;
            return new Subnet(gateway, network, network | (~mask & 0xFFFFFFFFL));
        }

        boolean contains(long address) {
            return address >= this.network && address <= this.broadcast;
        }

        /** Whether a guest may hold the address: inside, neither the network, the broadcast nor the gateway. */
        boolean usable(long address) {
            return address > this.network && address < this.broadcast && address != this.gateway;
        }

        /** @return an IPv4 literal as an unsigned number, or null for anything else (IPv6 leases included) */
        static @Nullable Long toLong(@Nullable Object value) {
            byte[] bytes = value instanceof String text ? IpRanges.parseLiteral(text.trim()) : null;
            if (bytes == null || bytes.length != 4) {
                return null;
            }
            return ((bytes[0] & 0xFFL) << 24) | ((bytes[1] & 0xFFL) << 16) | ((bytes[2] & 0xFFL) << 8)
                | (bytes[3] & 0xFFL);
        }

        static @NonNull String toText(long address) {
            return IpRanges.format(new byte[] {(byte) (address >> 24), (byte) (address >> 16), (byte) (address >> 8),
                (byte) address});
        }
    }
}
