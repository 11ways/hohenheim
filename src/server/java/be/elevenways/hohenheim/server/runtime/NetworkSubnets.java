package be.elevenways.hohenheim.server.runtime;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.host.HostShell;
import be.elevenways.hohenheim.server.security.IpLiterals;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.net.IpRanges;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * THE address block of every Docker network Hohenheim creates: the lowest free block of
 * {@code hohenheim.networks.prefix_length} inside {@code hohenheim.networks.address_range}, past every block a Docker
 * network on that host uses and every route that host has.
 *
 * AIDEV-NOTE: Docker's own pools hold 31 networks (172.17-172.31 as /16s, then 192.168/16 as sixteen /20s), and
 * every app takes one plus one per database link, so a host refused its next deploy with "all predefined address
 * pools have been fully subnetted" at about thirty (Robbedoes, 2026-11-25). Asking for a small block ourselves lifts
 * that ceiling without any daemon setting. Networks that exist keep whatever Docker gave them.
 *
 * AIDEV-NOTE: the host's routes are skipped because Docker checks an explicitly asked block against its own networks
 * only, never against the host's routes: a block that shadows a LAN or VPN route cuts the host off from it.
 *
 * AIDEV-NOTE: two creates at once may pick the same block. Docker refuses the second ("Pool overlaps with other one
 * on this address space"); the loser re-reads the host and takes the next block, which also covers two controllers
 * sharing one daemon. IPv4 only: every network Hohenheim creates says {@code EnableIPv6: false}.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class NetworkSubnets {

    /** How often one create re-reads the host after Docker refused a block another create took first. */
    static final int ATTEMPTS = 8;

    /** The host kernel's IPv4 route table: a header line, then one route per line. */
    static final String ROUTE_TABLE = "cat /proc/net/route";

    private NetworkSubnets() {
    }

    /**
     * How the declared range divides on one host.
     *
     * @param range  the declared range
     * @param prefix the declared block size
     * @param first  the lowest free block, null when none is left
     * @param free   how many blocks are free
     * @param total  how many blocks the range holds
     */
    public record Room(IpRanges.@NonNull Range range, int prefix, IpRanges.@Nullable Range first, int free,
                       int total) {

        /** @return the range as {@code address/prefix} */
        public @NonNull String rangeText() {
            return IpLiterals.cidr(this.range);
        }
    }

    /**
     * Create a bridge network on the lowest free block of the declared range.
     *
     * @param host the shell of the host the daemon runs on, whose routes the block must not overlap
     * @return the new network's id
     * @throws IOException when the range has no free block left (in words, naming the setting that widens it), the
     *                     host's routes cannot be read, or the daemon refuses
     */
    public static @NonNull String create(@NonNull DockerClient docker, @NonNull HostShell host, @NonNull String name,
                                         @NonNull Map<String, String> labels, boolean internal) throws IOException {
        DockerClient.ApiException lastOverlap = null;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            Room room = roomOn(docker, host);
            IpRanges.Range block = room.first();
            if (block == null) {
                throw new IOException("REFUSED to create network '" + name + "': all " + room.total()
                    + " networks of /" + room.prefix() + " in " + room.rangeText() + " on this host are taken by"
                    + " its Docker networks or overlap its routes. Widen "
                    + HohenheimSettings.Networks.ADDRESS_RANGE.getPath() + " (or remove networks nothing uses) to make"
                    + " room.");
            }
            try {
                return docker.createNetwork(name, labels, IpLiterals.cidr(block), gatewayOf(block), false, internal);
            } catch (DockerClient.ApiException refused) {
                if (!overlapRefusal(refused)) {
                    throw refused;
                }
                lastOverlap = refused;
            }
        }
        throw new IOException("REFUSED to create network '" + name + "': " + ATTEMPTS + " free networks in a row were"
            + " taken by another network before this one could be made; try again", lastOverlap);
    }

    /**
     * Read how the declared range divides on one host now.
     *
     * @throws IOException when the declared range or size cannot be used, the daemon cannot list its networks or the
     *                     host's routes cannot be read
     */
    public static @NonNull Room roomOn(@NonNull DockerClient docker, @NonNull HostShell host) throws IOException {
        List<IpRanges.Range> taken = new ArrayList<>(subnetsOf(docker.listNetworks()));
        taken.addAll(routesOf(host));
        String text = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Networks.ADDRESS_RANGE);
        int prefix = HohenheimSettings.positiveOrDefault(HohenheimSettings.Networks.PREFIX_LENGTH);
        String problem = HohenheimSettings.Networks.problemWith(text, prefix);
        IpRanges.Range range = AccessRuleModel.parseNetwork(text);
        if (problem != null || range == null) {
            throw new IOException("REFUSED to choose a network block: " + problem);
        }
        return roomIn(range, prefix, taken);
    }

    /** @return whether fewer networks than {@code hohenheim.networks.free_warning} are free; 0 never warns */
    public static boolean runningOut(int free) {
        Integer warning = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Networks.FREE_WARNING);
        return free < (warning != null ? warning : HohenheimSettings.Networks.FREE_WARNING.getDefaultValue());
    }

    /** @return how {@code range} divides into blocks of {@code prefix} once every taken block is skipped */
    static @NonNull Room roomIn(IpRanges.@NonNull Range range, int prefix, @NonNull List<IpRanges.Range> taken) {
        int total = 1 << (prefix - range.prefixLength());
        int base = toInt(range.network()) & mask(range.prefixLength());
        int step = 1 << (32 - prefix);
        IpRanges.Range first = null;
        int free = 0;
        for (int i = 0; i < total; i++) {
            IpRanges.Range block = new IpRanges.Range(toBytes(base + i * step), prefix);
            if (taken.stream().noneMatch(other -> overlaps(block, other))) {
                free++;
                if (first == null) {
                    first = block;
                }
            }
        }
        return new Room(range, prefix, first, free, total);
    }

    /** @return every IPv4 subnet the listed Docker networks use */
    static @NonNull List<IpRanges.Range> subnetsOf(@NonNull List<Object> networks) {
        List<IpRanges.Range> subnets = new ArrayList<>();
        for (Object network : networks) {
            Object ipam = network instanceof Map<?, ?> map ? map.get("IPAM") : null;
            Object configs = ipam instanceof Map<?, ?> map ? map.get("Config") : null;
            if (!(configs instanceof List<?> list)) {
                continue;
            }
            for (Object config : list) {
                IpRanges.Range subnet = config instanceof Map<?, ?> entry
                    && entry.get("Subnet") instanceof String text ? AccessRuleModel.parseNetwork(text) : null;
                if (subnet != null && subnet.network().length == 4) {
                    subnets.add(subnet);
                }
            }
        }
        return subnets;
    }

    /**
     * The host's IPv4 routes, its default route left out.
     *
     * @throws IOException when the route table cannot be read: a block chosen blind could cut the host off a network
     */
    static @NonNull List<IpRanges.Range> routesOf(@NonNull HostShell host) throws IOException {
        HostShell.Result read = host.run(ROUTE_TABLE);
        if (!read.ok()) {
            throw new IOException("REFUSED to choose a network block: the host's routes could not be read ("
                + read.text().trim() + ")");
        }
        return parseRoutes(read.text());
    }

    /** @return the routes of a {@code /proc/net/route} listing, whose addresses are little-endian hex */
    static @NonNull List<IpRanges.Range> parseRoutes(@NonNull String table) {
        List<IpRanges.Range> routes = new ArrayList<>();
        for (String line : table.split("\n")) {
            String[] fields = line.trim().split("\\s+");
            if (fields.length < 8 || fields[0].toLowerCase(Locale.ROOT).equals("iface")) {
                continue;
            }
            int destination;
            int netmask;
            try {
                destination = Integer.reverseBytes(Integer.parseUnsignedInt(fields[1], 16));
                netmask = Integer.reverseBytes(Integer.parseUnsignedInt(fields[7], 16));
            } catch (NumberFormatException unreadable) {
                continue;
            }
            int prefix = Integer.bitCount(netmask);
            if (prefix > 0) {
                routes.add(new IpRanges.Range(toBytes(destination & netmask), prefix));
            }
        }
        return routes;
    }

    /** @return whether a Docker refusal says the block overlaps a network another create made first */
    private static boolean overlapRefusal(DockerClient.@NonNull ApiException refused) {
        String message = String.valueOf(refused.getMessage()).toLowerCase(Locale.ROOT);
        return message.contains("overlap");
    }

    private static boolean overlaps(IpRanges.@NonNull Range one, IpRanges.@NonNull Range other) {
        return one.matches(other.network()) || other.matches(one.network());
    }

    /** @return the block's first host address, which Docker gives the bridge */
    private static @NonNull String gatewayOf(IpRanges.@NonNull Range block) {
        return IpRanges.format(toBytes(toInt(block.network()) + 1));
    }

    private static int mask(int prefix) {
        return prefix == 0 ? 0 : -1 << (32 - prefix);
    }

    private static int toInt(byte @NonNull [] address) {
        return ByteBuffer.wrap(address).getInt();
    }

    private static byte @NonNull [] toBytes(int address) {
        return ByteBuffer.allocate(4).putInt(address).array();
    }
}
