package be.elevenways.hohenheim.server.traffic;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.security.FlowAccounting;
import be.elevenways.hohenheim.server.security.PublicPorts;
import be.elevenways.hohenheim.server.security.WorkloadNetwork;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.hohenheim.server.sitetype.ServedStreams;
import be.elevenways.hohenheim.server.sitetype.StreamTarget;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.net.IpRanges;
import be.elevenways.zenit.common.orm.field.StorageKeyed;
import be.elevenways.zenit.traffic.common.TrafficFlow;
import be.elevenways.zenit.traffic.common.TrafficTable;
import be.elevenways.zenit.traffic.server.TrafficStore;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * THE turning of the kernel's flow counters (FlowAccounting) into where each app's outgoing connections went: the
 * isolation sweep publishes each host's plan and brings its accounting in line, and one listing of each planned host
 * per minute grows every app's destinations table (TrafficTable.DESTINATIONS of the apps store) by what its counters
 * grew since the listing before.
 *
 * AIDEV-NOTE: the AppCounters rules, per element: a set listed for the first time (a start of Hohenheim, a new
 * network) only sets its elements' baselines, since what they held was counted before or never; an element new in a
 * set listed before was created since and counts whole; a counter below its baseline was recreated (its element
 * expired and came back, its set was flushed) and counts whole; nothing between is guessed. An element recreated
 * between two readings (a host reboot, readings stopped for longer than its expiry) that already counts above its
 * old baseline is short by at most that baseline, the one case this cannot tell. A failed listing keeps the
 * baselines, so the next one counts on from them, and a network the sweep could not inspect keeps its accounting and
 * its last plan. The source address in an element's key names the container:
 * an address the sweep found on one of the host's networks counts under that container's app (releases and services
 * sum under their app), an address it did not find under the network's own app, and a database's own container
 * counts under no app. A destination is named here, once, from what the sweep found (DestinationKind), never by a
 * lookup: never a reverse DNS query. Listings run on the reader's task, never under the table lock and never in a
 * request; a listing that fails, or takes longer than {@link #SLOW}, is told on the dashboard (problems()).
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class AppDestinations {

    /** A listing taking longer than this is told on the dashboard as slow. */
    public static final Duration SLOW = Duration.ofSeconds(5);

    /** What separates a destination key's parts; no address, protocol or port holds it. */
    private static final String SEPARATOR = "|";

    /**
     * Who an address on one of the host's networks belongs to.
     *
     * @param appId      the app its container counts under, null for a database's own container
     * @param databaseId the database whose container it is, null for an app's
     */
    public record Party(@Nullable Integer appId, @Nullable Integer databaseId) {
    }

    /**
     * One network whose connections are counted.
     *
     * @param appId      the app its connections count under when the source address names no container
     * @param databaseId the database it links the app to, null for the app's own network
     */
    public record Accounted(@NonNull WorkloadNetwork network, int appId, @Nullable Integer databaseId) {
    }

    /**
     * What one sweep found on one host: the networks whose connections are counted, who every address on its
     * networks belongs to, and which ports of the host's own address its proxy and stream sites serve.
     *
     * @param networks  the counted networks by their chain key
     * @param addresses who each address on the host's networks belongs to, by its canonical form
     * @param webPorts  the host's web ports, each {@code tcp/<port>}
     * @param streams   the stream site serving each other public port of the host, by {@code <protocol>/<port>}
     * @param kept      the chain keys of networks the sweep could not inspect: their accounting stays as it is
     */
    public record Plan(int serverId, @NonNull String hostName, @NonNull Map<String, Accounted> networks,
                       @NonNull Map<String, Party> addresses, @NonNull Set<String> webPorts,
                       @NonNull Map<String, Integer> streams, @NonNull Set<String> kept) {

        public Plan {
            networks = Map.copyOf(networks);
            Map<String, Party> canonical = new HashMap<>();
            addresses.forEach((address, party) -> canonical.put(
                Objects.requireNonNullElse(AppDestinations.canonical(address), address), party));
            addresses = Map.copyOf(canonical);
            webPorts = Set.copyOf(webPorts);
            streams = Map.copyOf(streams);
            kept = Set.copyOf(kept);
        }

        /** A plan of networks every one of which the sweep inspected. */
        public Plan(int serverId, @NonNull String hostName, @NonNull Map<String, Accounted> networks,
                    @NonNull Map<String, Party> addresses, @NonNull Set<String> webPorts,
                    @NonNull Map<String, Integer> streams) {
            this(serverId, hostName, networks, addresses, webPorts, streams, Set.of());
        }
    }

    /**
     * One destination as its key stores it.
     *
     * @param ref      the record it names: an app, a database, a stream site; null for none
     * @param protocol tcp or udp
     */
    public record Destination(@NonNull DestinationKind kind, @Nullable Integer ref, @NonNull String address,
                              @NonNull String protocol, @NonNull String port) {

        /** @return the key the destinations table counts it under */
        public @NonNull String key() {
            return String.join(SEPARATOR, this.kind.storageKey(), this.ref == null ? "" : String.valueOf(this.ref),
                this.address, this.protocol, this.port);
        }

        /** @return the destination a key stores, its address alone for a kind this build lacks; null for none */
        public static @Nullable Destination of(@NonNull String key) {
            String[] parts = key.split("\\|", -1);
            if (parts.length != 5) {
                return null;
            }
            DestinationKind kind = Objects.requireNonNullElse(
                StorageKeyed.findOrNull(Arrays.asList(DestinationKind.values()), parts[0]), DestinationKind.INTERNET);
            Integer ref = null;
            try {
                ref = parts[1].isEmpty() || kind == DestinationKind.INTERNET ? null : Integer.valueOf(parts[1]);
            } catch (NumberFormatException foreign) {
                kind = DestinationKind.INTERNET;
            }
            return new Destination(kind, ref, parts[2], parts[3], parts[4]);
        }
    }

    /** What a problem stopped: the sweep bringing the accounting in line, or the reader's listing. */
    public enum Stage {
        ACCOUNTING,
        LISTING
    }

    /**
     * Why one host's destinations are not read as they should be.
     *
     * @param took how long a listing that answered, only slowly, took; null for a failure
     * @param why  the failure in the words nft or the host gave; null for a slow listing
     */
    public record Problem(@NonNull String host, @NonNull Stage stage, @Nullable Duration took, @Nullable String why) {
    }

    /** A host's baselines: every element's counter at the last listing, and the sets that listing held. */
    private record Baselines(@NonNull Map<String, Long> elements, @NonNull Set<String> sets) {
    }

    private static final Map<Integer, Plan> PLANS = new ConcurrentHashMap<>();
    private static final Map<Integer, Baselines> BASELINES = new ConcurrentHashMap<>();
    private static final Map<Integer, Map<Stage, Problem>> PROBLEMS = new ConcurrentHashMap<>();

    private AppDestinations() {
    }

    /** @return whether Hohenheim records where apps send data: one setting for every host */
    public static boolean recording() {
        return HohenheimSettings.isOn(HohenheimSettings.Traffic.RECORD_DESTINATIONS);
    }

    /**
     * Builds one host's plan from what the sweep inspected, network by network.
     *
     * AIDEV-NOTE: a container's address on a link network is known by its id, which its own network's inspection
     * attributes, so every own network is added before the plan is built and the order of add calls does not matter.
     */
    public static final class Planner {

        private final int serverId;
        private final @NonNull String hostName;
        private final @NonNull Supplier<PublicPorts> ports;
        private final Map<String, Accounted> networks = new HashMap<>();
        private final Map<String, Party> byContainer = new HashMap<>();
        private final Map<String, Party> addresses = new HashMap<>();
        private final Map<String, String> linkMembers = new HashMap<>();
        private final Set<String> unread = new HashSet<>();

        /** @param ports the host's public ports, read when the plan is built */
        public Planner(int serverId, @NonNull String hostName, @NonNull Supplier<PublicPorts> ports) {
            this.serverId = serverId;
            this.hostName = hostName;
            this.ports = ports;
        }

        /**
         * Adds one inspected network; never throws, a network it cannot read is kept as unread.
         *
         * @param inspect    the daemon's inspection of it, whose members' addresses the plan learns
         * @param party      who its members are when it is a workload's own network, null for a link network
         * @param countedFor the app its connections count under, null when they are not counted
         * @param database   the database it links that app to, null for an own network
         */
        public void network(@NonNull WorkloadNetwork network, @NonNull Map<String, Object> inspect,
                            @Nullable Party party, @Nullable Integer countedFor, @Nullable Integer database) {
            try {
                learn(network, inspect, party, countedFor, database);
            } catch (RuntimeException unreadable) {
                unread(network.name());
            }
        }

        /** Keeps a network the sweep could not inspect as its last plan had it, its accounting untouched. */
        public void unread(@NonNull String networkName) {
            this.unread.add(WorkloadNetworkPolicy.chainKey(networkName));
        }

        private void learn(@NonNull WorkloadNetwork network, @NonNull Map<String, Object> inspect,
                           @Nullable Party party, @Nullable Integer countedFor, @Nullable Integer database) {
            if (countedFor != null) {
                this.networks.put(WorkloadNetworkPolicy.chainKey(network.name()),
                    new Accounted(network, countedFor, database));
            }
            if (!(inspect.get("Containers") instanceof Map<?, ?> members)) {
                return;
            }
            for (Map.Entry<?, ?> member : members.entrySet()) {
                if (!(member.getValue() instanceof Map<?, ?> endpoint)) continue;
                String container = String.valueOf(member.getKey());
                for (String field : List.of("IPv4Address", "IPv6Address")) {
                    String address = canonical(endpoint.get(field) instanceof String text ? text : null);
                    if (address == null) continue;
                    if (party != null) {
                        this.addresses.put(address, party);
                    } else {
                        this.linkMembers.put(address, container);
                    }
                }
                if (party != null) {
                    this.byContainer.put(container, party);
                }
            }
        }

        /** @return the plan of every network added, an unread one as the host's last plan had it */
        public @NonNull Plan build() {
            Map<String, Party> addresses = new HashMap<>(this.addresses);
            this.linkMembers.forEach((address, container) -> {
                Party party = this.byContainer.get(container);
                if (party != null) addresses.putIfAbsent(address, party);
            });
            Map<String, Accounted> networks = new HashMap<>(this.networks);
            Plan last = PLANS.get(this.serverId);
            if (last != null && !this.unread.isEmpty()) {
                for (String key : this.unread) {
                    Accounted kept = last.networks().get(key);
                    if (kept != null) networks.putIfAbsent(key, kept);
                }
                last.addresses().forEach(addresses::putIfAbsent);
            }
            Set<String> web = new HashSet<>();
            for (int port : PublicPorts.web().tcp()) {
                web.add("tcp/" + port);
            }
            PublicPorts ports = this.ports.get();
            Map<String, Integer> streams = new HashMap<>();
            Set<String> served = new HashSet<>();
            for (int port : ports.tcp()) served.add("tcp/" + port);
            for (int port : ports.udp()) served.add("udp/" + port);
            served.removeAll(web);
            if (!served.isEmpty()) {
                for (ServedStreams.Served stream : ServedStreams.enabled((name, refused) -> { })) {
                    for (StreamTarget target : stream.targets()) {
                        for (String protocol : List.of("tcp", "udp")) {
                            boolean forwards = protocol.equals("tcp") ? target.protocol().forwardsTcp()
                                : target.protocol().forwardsUdp();
                            String port = protocol + "/" + target.listenPort();
                            if (forwards && served.contains(port)) {
                                streams.putIfAbsent(port, stream.site().get(SiteModel.ID));
                            }
                        }
                    }
                }
            }
            return new Plan(this.serverId, this.hostName, networks, addresses, web, streams, this.unread);
        }
    }

    /**
     * The sweep's half for one host: publishes its plan and brings its kernel's accounting in line with it, adding
     * what a counted network lacks or carries otherwise, and removing every accounting no counted network expects
     * (every one while recording is off); never throws, a failure is told on the dashboard.
     */
    public static void reconcile(@NonNull WorkloadNetworkPolicy policy, @NonNull Plan plan) {
        PLANS.put(plan.serverId(), plan);
        boolean recording = recording();
        try {
            String listed = policy.listTable();
            FlowAccounting.Listing present = listed == null ? FlowAccounting.Listing.ABSENT
                : FlowAccounting.parse(listed);
            Set<String> wanted = recording ? plan.networks().keySet() : Set.of();
            for (String key : present.accountedKeys()) {
                if (!wanted.contains(key) && !(recording && plan.kept().contains(key))) policy.stopAccounting(key);
            }
            if (recording) {
                for (Map.Entry<String, Accounted> accounted : plan.networks().entrySet()) {
                    if (FlowAccounting.carries(present, accounted.getValue().network())) continue;
                    try {
                        policy.account(accounted.getValue().network());
                    } catch (IOException refused) {
                        // A set an older build declared otherwise refuses its re-declaration: drop all of it, so the
                        // next sweep declares it afresh.
                        policy.stopAccounting(accounted.getKey());
                        throw refused;
                    }
                }
            }
            solved(plan.serverId(), Stage.ACCOUNTING);
        } catch (IOException | RuntimeException failed) {
            told(plan, Stage.ACCOUNTING, null, String.valueOf(failed.getMessage()));
            Blast.log("DESTINATIONS: the flow accounting of", plan.hostName(), "is not in line -",
                failed.getMessage());
        }
    }

    /** The sweep's end: forgets the plans, baselines and problems of every host it did not sweep. */
    public static void retain(@NonNull Set<Integer> swept) {
        PLANS.keySet().retainAll(swept);
        BASELINES.keySet().retainAll(swept);
        PROBLEMS.keySet().retainAll(swept);
    }

    /**
     * Lists every planned host once and counts what its counters grew; never throws.
     *
     * @return the flows counted, none on a quiet minute
     */
    public static int readAll() {
        boolean recording = recording();
        int counted = 0;
        for (Plan plan : List.copyOf(PLANS.values())) {
            if (!recording) {
                BASELINES.remove(plan.serverId());
                solved(plan.serverId(), Stage.LISTING);
                continue;
            }
            try {
                counted += read(plan, WorkloadNetworkPolicy.forHost(plan.hostName()));
            } catch (RuntimeException unreachable) {
                told(plan, Stage.LISTING, null, String.valueOf(unreachable.getMessage()));
            }
        }
        return counted;
    }

    /**
     * Lists one host once and counts what its counters grew; never throws.
     *
     * @return the flows counted
     */
    static int read(@NonNull Plan plan, @NonNull WorkloadNetworkPolicy policy) {
        if (!policy.isEnabled()) {
            return 0;
        }
        Instant started = Now.instant();
        FlowAccounting.Listing listing;
        try {
            String listed = policy.listTable();
            listing = listed == null ? FlowAccounting.Listing.ABSENT : FlowAccounting.parse(listed);
        } catch (IOException | RuntimeException unread) {
            told(plan, Stage.LISTING, null, String.valueOf(unread.getMessage()));
            return 0;
        }
        Instant now = Now.instant();
        Duration took = Duration.between(started, now);
        if (took.compareTo(SLOW) > 0) {
            told(plan, Stage.LISTING, took, null);
        } else {
            solved(plan.serverId(), Stage.LISTING);
        }
        List<TrafficFlow> flows;
        try {
            flows = flows(plan, listing, now);
        } catch (RuntimeException uncounted) {
            told(plan, Stage.LISTING, null, String.valueOf(uncounted));
            return 0;
        }
        TrafficStore store = flows.isEmpty() ? null : HohenheimTraffic.store(HohenheimTraffic.Counted.APPS);
        for (TrafficFlow flow : flows) {
            try {
                if (store == null) {
                    throw new IllegalStateException("traffic counting is stopped");
                }
                store.record(flow);
            } catch (RuntimeException uncounted) {
                Blast.slog("traffic.app_destination_not_counted", Map.of("source", flow.source(),
                    "destination", flow.key(), "reason", String.valueOf(uncounted)));
            }
        }
        return flows.size();
    }

    /**
     * What one listing grew every app's destinations by since the host's listing before, its baselines moved on.
     *
     * @return one flow per app and destination that grew, stamped {@code now}
     */
    static @NonNull List<TrafficFlow> flows(@NonNull Plan plan, FlowAccounting.@NonNull Listing listing,
                                           @NonNull Instant now) {
        Baselines before = BASELINES.getOrDefault(plan.serverId(), new Baselines(Map.of(), Set.of()));
        Map<String, Long> elements = new HashMap<>();
        Map<String, long[]> grown = new LinkedHashMap<>();
        for (FlowAccounting.Counted counted : listing.counted()) {
            String identity = identity(counted);
            long value = counted.value();
            elements.put(identity, value);
            Long baseline = before.elements().get(identity);
            long growth;
            if (baseline == null) {
                growth = before.sets().contains(setOf(counted)) ? value : 0;
            } else {
                growth = AppCounters.grownBy(baseline, value);
            }
            if (growth <= 0) {
                continue;
            }
            Accounted network = plan.networks().get(counted.key());
            if (network == null) {
                continue;
            }
            String sourceAddress = canonical(counted.source());
            Party source = sourceAddress == null ? null : plan.addresses().get(sourceAddress);
            Integer app = source == null ? Integer.valueOf(network.appId()) : source.appId();
            if (app == null) {
                continue;
            }
            Destination destination = destination(plan, network, app, counted);
            long[] sums = grown.computeIfAbsent(app + SEPARATOR + destination.key(), ignored -> new long[3]);
            sums[counted.counter().ordinal()] += growth;
        }
        BASELINES.put(plan.serverId(), new Baselines(elements, listing.sets()));
        List<TrafficFlow> flows = new ArrayList<>(grown.size());
        grown.forEach((appAndKey, sums) -> {
            int split = appAndKey.indexOf(SEPARATOR);
            flows.add(new TrafficFlow(now, HohenheimTraffic.appSource(Integer.parseInt(appAndKey.substring(0, split))),
                TrafficTable.DESTINATIONS, appAndKey.substring(split + 1),
                sums[FlowAccounting.Counter.CONNECTIONS.ordinal()], sums[FlowAccounting.Counter.SENT.ordinal()],
                sums[FlowAccounting.Counter.RECEIVED.ordinal()]));
        });
        return flows;
    }

    /**
     * Names what one counted element went to, from what the sweep found: to the host itself by the port its proxy or
     * a stream site serves; through it by who holds the address on the host's networks, a member of a database link
     * being that database; else the address alone.
     */
    static @NonNull Destination destination(@NonNull Plan plan, @NonNull Accounted network, int app,
                                            FlowAccounting.@NonNull Counted counted) {
        String address = Objects.requireNonNullElse(canonical(counted.destination()), counted.destination());
        String protocol = counted.protocol();
        String port = counted.port();
        if (counted.via() == FlowAccounting.Via.HOST) {
            String served = protocol + "/" + port;
            if (plan.webPorts().contains(served)) {
                return new Destination(DestinationKind.PROXY, null, address, protocol, port);
            }
            Integer stream = plan.streams().get(served);
            return stream != null ? new Destination(DestinationKind.STREAM, stream, address, protocol, port)
                : new Destination(DestinationKind.SERVER, null, address, protocol, port);
        }
        Party holder = plan.addresses().get(address);
        if (holder != null && holder.databaseId() != null) {
            return new Destination(DestinationKind.DATABASE, holder.databaseId(), address, protocol, port);
        }
        if (holder != null && holder.appId() != null) {
            return holder.appId() == app ? new Destination(DestinationKind.OWN, app, address, protocol, port)
                : new Destination(DestinationKind.APP, holder.appId(), address, protocol, port);
        }
        if (network.databaseId() != null && within(address, network.network())) {
            return new Destination(DestinationKind.DATABASE, network.databaseId(), address, protocol, port);
        }
        if (within(address, network.network())) {
            return new Destination(DestinationKind.OWN, app, address, protocol, port);
        }
        return new Destination(DestinationKind.INTERNET, null, address, protocol, port);
    }

    /** @return the hosts whose destinations are not read as they should be, by host name */
    public static @NonNull List<Problem> problems() {
        List<Problem> problems = new ArrayList<>();
        PROBLEMS.values().forEach(stages -> problems.addAll(stages.values()));
        problems.sort(Comparator.comparing(Problem::host).thenComparing(Problem::stage));
        return problems;
    }

    /** Forgets every plan, baseline and problem: a test's fresh start. */
    static void reset() {
        PLANS.clear();
        BASELINES.clear();
        PROBLEMS.clear();
    }

    private static void told(@NonNull Plan plan, @NonNull Stage stage, @Nullable Duration took, @Nullable String why) {
        PROBLEMS.computeIfAbsent(plan.serverId(), ignored -> new ConcurrentHashMap<>())
            .put(stage, new Problem(plan.hostName(), stage, took, why));
    }

    private static void solved(int serverId, @NonNull Stage stage) {
        Map<Stage, Problem> stages = PROBLEMS.get(serverId);
        if (stages != null) {
            stages.remove(stage);
        }
    }

    /** @return whether the address lies in one of the network's subnets */
    private static boolean within(@NonNull String address, @NonNull WorkloadNetwork network) {
        byte[] literal = IpRanges.parseLiteral(address);
        for (String subnet : new String[] {network.ipv4Subnet(), network.ipv6Subnet()}) {
            IpRanges.Range range = subnet == null ? null : AccessRuleModel.parseNetwork(subnet);
            if (range != null && range.matches(literal)) {
                return true;
            }
        }
        return false;
    }

    /** @return an address in its one canonical form, its prefix length cut off; null for none or no address */
    private static @Nullable String canonical(@Nullable String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        int slash = address.indexOf('/');
        byte[] literal = IpRanges.parseLiteral(slash < 0 ? address.trim() : address.substring(0, slash).trim());
        return literal == null ? null : IpRanges.format(literal);
    }

    private static @NonNull String identity(FlowAccounting.@NonNull Counted counted) {
        return String.join(SEPARATOR, setOf(counted), counted.source(), counted.destination(), counted.protocol(),
            counted.port());
    }

    private static @NonNull String setOf(FlowAccounting.@NonNull Counted counted) {
        return FlowAccounting.setName(counted.via(), counted.counter(), counted.family(), counted.key());
    }
}
