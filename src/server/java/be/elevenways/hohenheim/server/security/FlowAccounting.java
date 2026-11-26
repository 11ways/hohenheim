package be.elevenways.hohenheim.server.security;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * THE kernel half of counting where a workload network's connections go: per network, chains and dynamic sets of
 * their own in the controller's policy table, one element per (source, destination, protocol, port) with a counter,
 * the rules that fill them, and the reading of a table listing back into those counters.
 *
 * AIDEV-NOTE: the accounting chains are SEPARATE base chains ({@code acc_fwd_}, {@code acc_inp_}, {@code acc_out_}),
 * never rules in the isolation chains, so the verifier's exact read of {@code fwd_}/{@code inp_} stays exact and an
 * accounting failure can never refuse a deploy. They hook at priority 10, after the isolation chains (-10) and the
 * filter priority (0), so a packet the isolation or the host's own filter drops is never counted. Only connections a
 * workload OPENS are counted: the sent and connection rules match its source address in the original direction, the
 * received rule its address as the destination of the reply direction, so a visitor's connection into the workload
 * counts in no set. Through the host (forward) and to the host itself (input, its replies leaving through output)
 * fill sets of their own, so a connection to another site on the same server through its public address is known as
 * one without knowing the server's addresses. A connection is counted by its opening packets (conntrack state new):
 * an opening sent again before an answer, a TCP retransmitted SYN or a UDP query asked again, counts again. TCP and
 * UDP only. Elements expire after {@link #IDLE} without a packet: a reading every minute reads every growth before
 * that, so an expiry loses nothing unless the readings stop for that long; a set that is full counts no new
 * destination until elements expire.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class FlowAccounting {

    /** What every accounting object's name starts with, so no other owner of the table is ever touched. */
    public static final String PREFIX = "acc_";

    /** How long an element lives without a packet. */
    static final String IDLE = "15m";

    /** The most elements one set holds. */
    static final int SIZE = 16384;

    /** The priority the accounting chains hook at: after the isolation chains and the filter. */
    static final int PRIORITY = 10;

    /** Where a counted packet went. */
    public enum Via {
        /** Through the host: the internet, or another container over a bridge. */
        THROUGH("t"),
        /** To the host itself: a port its proxy or a stream site serves. */
        HOST("h");

        private final @NonNull String token;

        Via(@NonNull String token) {
            this.token = token;
        }
    }

    /** What one set counts. */
    public enum Counter {
        /** The bytes the workload sent: an element's byte counter. */
        SENT("s"),
        /** The bytes it received in reply: an element's byte counter. */
        RECEIVED("r"),
        /** The connections it opened: an element's packet counter of opening packets. */
        CONNECTIONS("c");

        private final @NonNull String token;

        Counter(@NonNull String token) {
            this.token = token;
        }
    }

    /** An address family, as nft spells it. */
    public enum Family {
        V4("4", "ip", "ipv4_addr"),
        V6("6", "ip6", "ipv6_addr");

        private final @NonNull String token;
        private final @NonNull String match;
        private final @NonNull String type;

        Family(@NonNull String token, @NonNull String match, @NonNull String type) {
            this.token = token;
            this.match = match;
            this.type = type;
        }
    }

    /** The three base chains, by the hook each sits on. */
    enum Hook {
        FORWARD("fwd", "forward"),
        INPUT("inp", "input"),
        OUTPUT("out", "output");

        private final @NonNull String token;
        private final @NonNull String hook;

        Hook(@NonNull String token, @NonNull String hook) {
            this.token = token;
            this.hook = hook;
        }
    }

    /**
     * One counted element of a listing.
     *
     * @param key         the chain key of the network whose set holds it
     * @param source      the workload's address
     * @param destination the address it connected to
     * @param protocol    tcp or udp, as nft renders it
     * @param port        the destination's port, as nft renders it
     */
    public record Counted(@NonNull Via via, @NonNull Counter counter, @NonNull Family family, @NonNull String key,
                          @NonNull String source, @NonNull String destination, @NonNull String protocol,
                          @NonNull String port, long packets, long bytes) {

        /** @return what the counter counts: bytes for sent and received, opening packets for connections */
        public long value() {
            return this.counter == Counter.CONNECTIONS ? this.packets : this.bytes;
        }
    }

    /**
     * A table listing as the accounting reads it: every chain's rule lines and every accounting set's elements.
     *
     * @param chains  every chain of the table by name, its rule lines as NftChains.normalized compares them
     * @param sets    the name of every accounting set the table holds
     * @param counted every element of those sets with its counter
     */
    public record Listing(@NonNull Map<String, List<String>> chains, @NonNull Set<String> sets,
                          @NonNull List<Counted> counted) {

        /** The listing of a table that does not exist. */
        public static final Listing ABSENT = new Listing(Map.of(), Set.of(), List.of());

        /** @return the chain key of every network with any accounting chain or set in the table */
        public @NonNull Set<String> accountedKeys() {
            Set<String> keys = new LinkedHashSet<>();
            for (String chain : this.chains.keySet()) {
                String key = keyOfChain(chain);
                if (key != null) keys.add(key);
            }
            for (String set : this.sets) {
                String key = keyOfSet(set);
                if (key != null) keys.add(key);
            }
            return keys;
        }
    }

    private FlowAccounting() {
    }

    /** @return the name of one network's set */
    public static @NonNull String setName(@NonNull Via via, @NonNull Counter counter, @NonNull Family family,
                                          @NonNull String key) {
        return PREFIX + via.token + counter.token + family.token + "_" + key;
    }

    /** @return the name of one network's chain on one hook */
    static @NonNull String chainName(@NonNull Hook hook, @NonNull String key) {
        return PREFIX + hook.token + "_" + key;
    }

    /** @return the families a network carries: IPv4 always, IPv6 when it has a subnet of its own */
    public static @NonNull List<Family> families(@NonNull WorkloadNetwork network) {
        return network.ipv6Subnet() == null ? List.of(Family.V4) : List.of(Family.V4, Family.V6);
    }

    /** @return each accounting chain of the network with the rules it carries, in order */
    public static @NonNull Map<String, List<String>> rules(@NonNull WorkloadNetwork network) {
        String key = WorkloadNetworkPolicy.chainKey(network.name());
        Map<String, List<String>> chains = new LinkedHashMap<>();
        for (Hook hook : Hook.values()) {
            chains.put(chainName(hook, key), new ArrayList<>());
        }
        for (Family family : families(network)) {
            String subnet = family == Family.V4 ? network.ipv4Subnet() : network.ipv6Subnet();
            String opened = family.match + " saddr " + subnet + " meta l4proto { tcp, udp } ";
            String answered = family.match + " daddr " + subnet + " meta l4proto { tcp, udp } ";
            String outward = " { " + family.match + " saddr . " + family.match + " daddr . meta l4proto . th dport }";
            String back = " { " + family.match + " daddr . " + family.match + " saddr . meta l4proto . th sport }";
            for (Via via : Via.values()) {
                List<String> opens = chains.get(chainName(via == Via.THROUGH ? Hook.FORWARD : Hook.INPUT, key));
                opens.add(opened + "ct direction original update @" + setName(via, Counter.SENT, family, key)
                    + outward);
                opens.add(opened + "ct state new update @" + setName(via, Counter.CONNECTIONS, family, key)
                    + outward);
                chains.get(chainName(via == Via.THROUGH ? Hook.FORWARD : Hook.OUTPUT, key))
                    .add(answered + "ct direction reply update @" + setName(via, Counter.RECEIVED, family, key) + back);
            }
        }
        Map<String, List<String>> frozen = new LinkedHashMap<>();
        chains.forEach((chain, list) -> frozen.put(chain, List.copyOf(list)));
        return frozen;
    }

    /**
     * The one {@code nft -f -} transaction that applies (or re-applies, idempotently) a network's accounting: its
     * sets, its chains emptied, then their rules; counters already in a set survive it.
     */
    public static @NonNull String ruleset(@NonNull String table, @NonNull WorkloadNetwork network) {
        String key = WorkloadNetworkPolicy.chainKey(network.name());
        StringBuilder ruleset = new StringBuilder();
        declare(ruleset, table, key, families(network));
        for (Hook hook : Hook.values()) {
            ruleset.append("flush chain inet ").append(table).append(' ').append(chainName(hook, key)).append('\n');
        }
        rules(network).forEach((chain, rules) -> {
            for (String rule : rules) {
                ruleset.append("add rule inet ").append(table).append(' ').append(chain).append(' ').append(rule)
                    .append('\n');
            }
        });
        return ruleset.toString();
    }

    /**
     * The transaction that removes one network's accounting whatever of it exists: every chain and set it can have
     * declared (a no-op for one that exists, so nothing absent fails it), then the chains deleted before the sets
     * their rules name.
     */
    public static @NonNull String removal(@NonNull String table, @NonNull String key) {
        StringBuilder removal = new StringBuilder();
        declare(removal, table, key, List.of(Family.values()));
        for (Hook hook : Hook.values()) {
            String chain = chainName(hook, key);
            removal.append("flush chain inet ").append(table).append(' ').append(chain).append('\n');
            removal.append("delete chain inet ").append(table).append(' ').append(chain).append('\n');
        }
        for (Family family : Family.values()) {
            for (Via via : Via.values()) {
                for (Counter counter : Counter.values()) {
                    removal.append("delete set inet ").append(table).append(' ')
                        .append(setName(via, counter, family, key)).append('\n');
                }
            }
        }
        return removal.toString();
    }

    /** Declares the table, the sets of these families and the three chains, as ruleset and removal both do. */
    private static void declare(@NonNull StringBuilder out, @NonNull String table, @NonNull String key,
                                @NonNull List<Family> families) {
        out.append("add table inet ").append(table).append('\n');
        for (Family family : families) {
            for (Via via : Via.values()) {
                for (Counter counter : Counter.values()) {
                    out.append("add set inet ").append(table).append(' ').append(setName(via, counter, family, key))
                        .append(" { type ").append(family.type).append(" . ").append(family.type)
                        .append(" . inet_proto . inet_service ; flags dynamic,timeout ; timeout ").append(IDLE)
                        .append(" ; size ").append(SIZE).append(" ; counter ; }\n");
                }
            }
        }
        for (Hook hook : Hook.values()) {
            out.append("add chain inet ").append(table).append(' ').append(chainName(hook, key))
                .append(" { type filter hook ").append(hook.hook).append(" priority ").append(PRIORITY)
                .append(" ; policy accept ; }\n");
        }
    }

    /** @return whether the listing carries the network's accounting exactly: every set, every chain, its rules alone */
    public static boolean carries(@NonNull Listing present, @NonNull WorkloadNetwork network) {
        String key = WorkloadNetworkPolicy.chainKey(network.name());
        for (Family family : families(network)) {
            for (Via via : Via.values()) {
                for (Counter counter : Counter.values()) {
                    if (!present.sets().contains(setName(via, counter, family, key))) return false;
                }
            }
        }
        for (Map.Entry<String, List<String>> chain : rules(network).entrySet()) {
            if (!chain.getValue().equals(present.chains().get(chain.getKey()))) return false;
        }
        return true;
    }

    /**
     * Reads a table listing ({@code nft list table inet <table>}, as the privileged helper's nft-list prints it).
     *
     * AIDEV-NOTE: an element renders as its key's parts joined by " . ", then its counter and expiry in an order nft
     * versions differ on ({@code counter packets 3 bytes 180 expires 14m58s} or the expiry first), so the key ends at
     * the first word that is no part of it and the counter is found by its words. A protocol nft renders by number
     * (6, 17) is read as its name; a line or element of another shape is skipped, never guessed.
     */
    public static @NonNull Listing parse(@NonNull String listing) {
        Map<String, List<String>> chains = new LinkedHashMap<>();
        Set<String> sets = new LinkedHashSet<>();
        List<Counted> counted = new ArrayList<>();
        String chain = null;
        String set = null;
        boolean inRules = false;
        StringBuilder elements = null;
        for (String raw : listing.split("\n")) {
            String line = NftChains.normalized(raw);
            if (elements != null) {
                elements.append(' ').append(line);
                if (line.endsWith("}")) {
                    elements(set, elements.toString(), counted);
                    elements = null;
                }
                continue;
            }
            if (line.startsWith("chain ") && line.endsWith(" {")) {
                chain = line.substring("chain ".length(), line.length() - 2);
                chains.put(chain, new ArrayList<>());
                inRules = false;
                continue;
            }
            if (line.startsWith("set ") && line.endsWith(" {")) {
                String name = line.substring("set ".length(), line.length() - 2);
                set = keyOfSet(name) != null ? name : null;
                if (set != null) sets.add(set);
                continue;
            }
            if (line.equals("}")) {
                chain = null;
                set = null;
                inRules = false;
                continue;
            }
            if (chain != null) {
                if (!inRules) {
                    inRules = line.startsWith("type ");
                } else if (!line.isEmpty()) {
                    chains.get(chain).add(line);
                }
                continue;
            }
            if (set != null && line.startsWith("elements = {")) {
                elements = new StringBuilder(line);
                if (line.endsWith("}")) {
                    elements(set, elements.toString(), counted);
                    elements = null;
                }
            }
        }
        return new Listing(chains, sets, counted);
    }

    /** Reads one set's {@code elements = { ... }} text into its counted elements. */
    private static void elements(@NonNull String set, @NonNull String text, @NonNull List<Counted> counted) {
        SetName name = SetName.of(set);
        if (name == null) return;
        int open = text.indexOf('{');
        int close = text.lastIndexOf('}');
        if (open < 0 || close <= open) return;
        for (String element : text.substring(open + 1, close).split(",")) {
            String[] words = element.trim().split(" ");
            List<String> parts = new ArrayList<>(4);
            int at = 0;
            while (at < words.length && !isElementWord(words[at])) {
                if (!words[at].equals(".") && !words[at].isEmpty()) parts.add(words[at]);
                at++;
            }
            Long packets = null;
            Long bytes = null;
            for (; at + 1 < words.length; at++) {
                if (words[at].equals("packets")) packets = number(words[at + 1]);
                if (words[at].equals("bytes")) bytes = number(words[at + 1]);
            }
            if (parts.size() != 4 || packets == null || bytes == null) continue;
            counted.add(new Counted(name.via(), name.counter(), name.family(), name.key(), parts.get(0),
                parts.get(1), protocol(parts.get(2)), parts.get(3), packets, bytes));
        }
    }

    private static boolean isElementWord(@NonNull String word) {
        return word.equals("counter") || word.equals("expires") || word.equals("timeout") || word.equals("comment");
    }

    private static @Nullable Long number(@NonNull String word) {
        try {
            return Long.parseLong(word);
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /** @return tcp or udp for the numbers older nft renders them as, else the word itself */
    private static @NonNull String protocol(@NonNull String word) {
        return switch (word) {
            case "6" -> "tcp";
            case "17" -> "udp";
            default -> word;
        };
    }

    /** @return the network key an accounting chain's name carries, null for a chain of another owner */
    static @Nullable String keyOfChain(@NonNull String chain) {
        for (Hook hook : Hook.values()) {
            String prefix = PREFIX + hook.token + "_";
            if (chain.startsWith(prefix) && chain.length() > prefix.length()) {
                return chain.substring(prefix.length());
            }
        }
        return null;
    }

    /** @return the network key an accounting set's name carries, null for a set of another owner */
    static @Nullable String keyOfSet(@NonNull String set) {
        SetName name = SetName.of(set);
        return name == null ? null : name.key();
    }

    /** An accounting set's name, read back into what it counts. */
    private record SetName(@NonNull Via via, @NonNull Counter counter, @NonNull Family family, @NonNull String key) {

        static @Nullable SetName of(@NonNull String set) {
            for (Via via : Via.values()) {
                for (Counter counter : Counter.values()) {
                    for (Family family : Family.values()) {
                        String prefix = PREFIX + via.token + counter.token + family.token + "_";
                        if (set.startsWith(prefix) && set.length() > prefix.length()) {
                            return new SetName(via, counter, family, set.substring(prefix.length()));
                        }
                    }
                }
            }
            return null;
        }
    }
}
