package be.elevenways.hohenheim.traffic;

import be.elevenways.protoblast.common.http.UserAgentFamily;
import be.elevenways.zenit.traffic.common.LiveHit;
import be.elevenways.zenit.traffic.common.LiveHits;
import be.elevenways.zenit.traffic.common.LiveSource;
import be.elevenways.zenit.traffic.common.StatusClass;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What the live Activity picture shows, out of the live frames: the last minute second by second, the rows a dot
 * leaves from (visitors) and flies to (sites), and the dots each frame gives birth to.
 *
 * AIDEV-NOTE: a second is HEARD when a frame stamped with it arrived; a stamp skipped between two frames is a second
 * NOT heard, which the picture and the figures say, and never count as a quiet second. Counts are the frames' whole
 * counts (a source's every hit); rows, paths and dots read the hits a frame carries, at most the live seam's bound,
 * and the hits it left out are said as "not drawn". The rows are ranked again every {@link #RANK_EVERY_SECONDS} heard
 * seconds of stamps (a gap counts), so a dot already flying keeps its path and rows do not jump at every frame; a
 * dot still waiting when the rows move is dropped.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class ActivityModel {

    /** How many seconds the picture remembers: "the last minute". */
    public static final int WINDOW_SECONDS = 60;

    /** How many visitors get a row of their own, when visitors are shown. */
    public static final int ADDRESS_ROWS = 4;

    /** How many sites get a row of their own; the rest share one. */
    public static final int SITE_ROWS = 7;

    /** How many heard seconds the rows stay as ranked. */
    public static final int RANK_EVERY_SECONDS = 5;

    /** How many dots wait to be born at most, while the picture is not drawn (a hidden page). */
    public static final int MOST_WAITING = 4_096;

    /** Which kind of visitor a row stands for; the members are the rows' order below the visitors of their own. */
    public enum OriginKind {
        /** One visitor, by address. */
        ADDRESS,
        /** Every crawler a user agent family calls a bot. */
        BOTS,
        /** Every client a family calls a tool: scripts, apps, monitors. */
        TOOLS,
        /** Every other visitor: the ones without a row of their own. */
        OTHERS
    }

    /** One row a dot leaves from. */
    public record Origin(@NonNull OriginKind kind, @Nullable String address) {
    }

    /** Which kind of row a site row is. */
    public enum SiteKind {
        /** One site. */
        SITE,
        /** Every site without a row of its own. */
        MORE,
        /** The names no site serves. */
        UNSERVED
    }

    /** One row a dot flies to. */
    public record SiteRow(@NonNull SiteKind kind, @Nullable String source) {
    }

    /**
     * One dot to give birth to: from which row to which row, its answer's class, and how long after its frame is
     * drawn it leaves (where in its second the hit was recorded).
     */
    public record Flight(int origin, int site, @NonNull StatusClass statusClass, double offsetMs) {
    }

    /** One second of the window. */
    private static final class Second {
        long stamp = Long.MIN_VALUE;
        boolean heard;
        long requests;
        long bytes;
        long dropped;
        final long[] classes = new long[StatusClass.values().length];
        final Set<Integer> marks = new HashSet<>();
        boolean marksCut;
        final Map<String, Long> sourceRequests = new HashMap<>();
        final Map<String, Map<String, Integer>> sourcePaths = new HashMap<>();
        final Map<String, Integer> addresses = new HashMap<>();
        final Map<String, Integer> bots = new HashMap<>();
        final Map<String, Integer> tools = new HashMap<>();

        void reset(long stamp, boolean heard) {
            this.stamp = stamp;
            this.heard = heard;
            this.requests = 0;
            this.bytes = 0;
            this.dropped = 0;
            Arrays.fill(this.classes, 0);
            this.marks.clear();
            this.marksCut = false;
            this.sourceRequests.clear();
            this.sourcePaths.clear();
            this.addresses.clear();
            this.bots.clear();
            this.tools.clear();
        }
    }

    private final boolean visitors;
    private final @NonNull String unserved;
    private final @NonNull List<String> named;
    private final Second[] seconds = new Second[WINDOW_SECONDS];
    private final List<Flight> births = new ArrayList<>();
    private List<Origin> origins = List.of();
    private List<SiteRow> sites = List.of();
    private long latest = Long.MIN_VALUE;
    private long first = Long.MIN_VALUE;
    private long rankedAt = Long.MIN_VALUE;
    private long spokenAt = Long.MIN_VALUE;
    private int revision;

    /**
     * @param visitors whether the frames carry visitors' addresses, so each of the busiest gets a row
     * @param unserved the source of the names no site serves
     * @param named    the sources the viewer may see, by their names' order: they rank before sources never heard
     */
    public ActivityModel(boolean visitors, @NonNull String unserved, @NonNull List<String> named) {
        this.visitors = visitors;
        this.unserved = Objects.requireNonNull(unserved, "unserved cannot be null");
        this.named = List.copyOf(named);
        for (int i = 0; i < WINDOW_SECONDS; i++) {
            this.seconds[i] = new Second();
        }
        this.rank();
    }

    /**
     * Takes one frame: its counts join its second, a second skipped since the last is marked not heard, and each hit
     * it carries is queued as a dot (at most {@link #MOST_WAITING}; one past it is counted as not drawn).
     */
    public void accept(@NonNull LiveHits frame) {
        long stamp = frame.second();
        if (this.latest != Long.MIN_VALUE && stamp <= this.latest - WINDOW_SECONDS) {
            return;
        }
        if (this.latest == Long.MIN_VALUE) {
            this.first = stamp;
            this.latest = stamp;
        } else if (stamp > this.latest) {
            for (long missed = Math.max(this.latest + 1, stamp - WINDOW_SECONDS + 1); missed < stamp; missed++) {
                this.slot(missed).reset(missed, false);
            }
            this.latest = stamp;
        }
        Second second = this.slot(stamp);
        if (second.stamp != stamp || !second.heard) {
            second.reset(stamp, true);
        }
        for (LiveSource source : frame.sources()) {
            this.count(second, source);
        }
        if (this.rankedAt == Long.MIN_VALUE || stamp - this.rankedAt >= RANK_EVERY_SECONDS
                || this.unplaced(frame)) {
            this.rankedAt = stamp;
            this.rank();
        }
        for (LiveSource source : frame.sources()) {
            int site = this.siteRowOf(source.source());
            for (LiveHit hit : source.hits()) {
                double offset = Math.max(0, Math.min(999, hit.at() - stamp * 1000));
                if (this.births.size() < MOST_WAITING) {
                    this.births.add(new Flight(this.originRowOf(hit), site, hit.statusClass(), offset));
                } else {
                    second.dropped++;
                }
            }
        }
    }

    /** @return the dots born since the last call, which it hands over once */
    public @NonNull List<Flight> takeBirths() {
        List<Flight> taken = List.copyOf(this.births);
        this.births.clear();
        return taken;
    }

    /** Drops the dots waiting to be born: a paused picture shows none of what happened meanwhile. */
    public void dropBirths() {
        this.births.clear();
    }

    /**
     * @return whether a screen reader is due the last minute: once per {@code every} seconds of stamps, a gap over the
     *         boundary included; true once, then false until the next interval
     */
    public boolean takeSpeech(int every) {
        if (this.latest == Long.MIN_VALUE) return false;
        long interval = Math.floorDiv(this.latest, (long) every);
        if (interval == this.spokenAt) return false;
        this.spokenAt = interval;
        return true;
    }

    /** @return a number that changes whenever the rows change */
    public int revision() {
        return this.revision;
    }

    public @NonNull List<Origin> origins() {
        return this.origins;
    }

    public @NonNull List<SiteRow> sites() {
        return this.sites;
    }

    /** @return the second the last frame was stamped with, {@link Long#MIN_VALUE} before the first */
    public long latest() {
        return this.latest;
    }

    /** @return every request of the window's heard seconds */
    public long requests() {
        long sum = 0;
        for (Second second : this.window()) sum += second.requests;
        return sum;
    }

    public long bytes() {
        long sum = 0;
        for (Second second : this.window()) sum += second.bytes;
        return sum;
    }

    /** @return the window's requests answered in the class */
    public long count(@NonNull StatusClass statusClass) {
        long sum = 0;
        for (Second second : this.window()) sum += second.classes[statusClass.ordinal()];
        return sum;
    }

    /** @return the window's hits no frame carried, and the dots the picture could not keep: counted, never drawn */
    public long notDrawn() {
        long sum = 0;
        for (Second second : this.window()) sum += second.dropped;
        return sum;
    }

    /** @return the distinct visitors of the window, by their marks */
    public long visitors() {
        Set<Integer> marks = new HashSet<>();
        for (Second second : this.window()) marks.addAll(second.marks);
        return marks.size();
    }

    /** @return whether a frame left visitor marks out, so {@link #visitors()} is short */
    public boolean visitorsCut() {
        for (Second second : this.window()) {
            if (second.marksCut) return true;
        }
        return false;
    }

    /** @return the seconds since the first frame, within the window, that no frame was heard for */
    public int missed() {
        int missed = 0;
        for (long stamp = this.windowStart(); this.latest != Long.MIN_VALUE && stamp <= this.latest; stamp++) {
            Second second = this.slot(stamp);
            if (second.stamp != stamp || !second.heard) missed++;
        }
        return missed;
    }

    /** @return the window's requests of one source */
    public long requests(@NonNull String source) {
        long sum = 0;
        for (Second second : this.window()) sum += second.sourceRequests.getOrDefault(source, 0L);
        return sum;
    }

    /** @return the window's requests of a row: one site's, the sources without a row's, or the unserved names' */
    public long requests(@NonNull SiteRow row) {
        if (row.kind() != SiteKind.MORE) {
            return this.requests(Objects.requireNonNull(row.source()));
        }
        long sum = 0;
        for (Second second : this.window()) {
            for (Map.Entry<String, Long> each : second.sourceRequests.entrySet()) {
                if (this.siteRowOf(each.getKey()) == this.sites.indexOf(row)) sum += each.getValue();
            }
        }
        return sum;
    }

    /** @return how many sources the row stands for in the window: one, or the sources without a row */
    public int sourcesOf(@NonNull SiteRow row) {
        if (row.kind() != SiteKind.MORE) return 1;
        Set<String> seen = new HashSet<>(this.named);
        for (Second second : this.window()) seen.addAll(second.sourceRequests.keySet());
        int placed = 0;
        for (String source : seen) {
            if (this.siteRowOf(source) == this.sites.indexOf(row)) placed++;
        }
        return placed;
    }

    /** @return the path a source's drawn hits asked most in the window, null for none */
    public @Nullable String busiestPath(@NonNull String source) {
        Map<String, Integer> paths = new HashMap<>();
        for (Second second : this.window()) {
            Map<String, Integer> asked = second.sourcePaths.get(source);
            if (asked != null) asked.forEach((path, count) -> paths.merge(path, count, Integer::sum));
        }
        return busiest(paths);
    }

    /** @return the window's drawn hits of an origin row */
    public long hits(@NonNull Origin origin) {
        long sum = 0;
        for (Second second : this.window()) {
            sum += switch (origin.kind()) {
                case ADDRESS -> second.addresses.getOrDefault(origin.address(), 0);
                case BOTS -> total(second.bots);
                case TOOLS -> total(second.tools);
                case OTHERS -> {
                    long others = 0;
                    for (Map.Entry<String, Integer> each : second.addresses.entrySet()) {
                        if (!this.hasRow(each.getKey())) others += each.getValue();
                    }
                    yield others;
                }
            };
        }
        return sum;
    }

    /** @return how many distinct visitors without a row of their own the window's drawn hits name */
    public int othersCount() {
        Set<String> others = new HashSet<>();
        for (Second second : this.window()) {
            for (String address : second.addresses.keySet()) {
                if (!address.isEmpty() && !this.hasRow(address)) others.add(address);
            }
        }
        return others.size();
    }

    /** @return the names of the bot or tool families the window's drawn hits name, the busiest first */
    public @NonNull List<String> families(@NonNull OriginKind kind) {
        Map<String, Integer> counted = new HashMap<>();
        for (Second second : this.window()) {
            (kind == OriginKind.BOTS ? second.bots : second.tools).forEach((name, count) ->
                counted.merge(name, count, Integer::sum));
        }
        return busiestFirst(counted);
    }

    /**
     * @return the window's heard and missed seconds oldest first: null for a second not heard (or before the first
     *         frame), else its requests per status class
     */
    public @NonNull List<long @Nullable []> perSecond() {
        List<long[]> out = new ArrayList<>(WINDOW_SECONDS);
        long end = this.latest == Long.MIN_VALUE ? 0 : this.latest;
        for (long stamp = end - WINDOW_SECONDS + 1; stamp <= end; stamp++) {
            Second second = this.latest == Long.MIN_VALUE ? null : this.slot(stamp);
            boolean heard = second != null && second.stamp == stamp && second.heard;
            out.add(heard ? second.classes.clone() : null);
        }
        return Collections.unmodifiableList(out);
    }

    private void count(@NonNull Second second, @NonNull LiveSource source) {
        second.requests += source.requests();
        second.bytes += source.bytesSent();
        second.dropped += source.dropped();
        for (StatusClass each : StatusClass.values()) {
            second.classes[each.ordinal()] += source.count(each);
        }
        second.marks.addAll(source.visitorMarks());
        second.marksCut |= source.marksLeftOut() > 0;
        second.sourceRequests.merge(source.source(), source.requests(), Long::sum);
        Map<String, Integer> paths = second.sourcePaths.computeIfAbsent(source.source(), ignored -> new HashMap<>());
        for (LiveHit hit : source.hits()) {
            paths.merge(hit.path(), 1, Integer::sum);
            UserAgentFamily family = familyOf(hit.agent());
            if (family != null && family.bot()) {
                second.bots.merge(nameOf(family, hit.agent()), 1, Integer::sum);
            } else if (family != null && family.tool()) {
                second.tools.merge(nameOf(family, hit.agent()), 1, Integer::sum);
            } else {
                // Without an address (a listener not shown visitors) every person is one of the others.
                second.addresses.merge(hit.visitor() == null ? "" : hit.visitor(), 1, Integer::sum);
            }
        }
    }

    private void rank() {
        List<Origin> ranked = new ArrayList<>();
        if (this.visitors) {
            Map<String, Integer> addresses = new HashMap<>();
            for (Second second : this.window()) {
                second.addresses.forEach((address, count) -> addresses.merge(address, count, Integer::sum));
            }
            addresses.remove("");
            List<String> busiest = busiestFirst(addresses);
            for (int i = 0; i < Math.min(ADDRESS_ROWS, busiest.size()); i++) {
                ranked.add(new Origin(OriginKind.ADDRESS, busiest.get(i)));
            }
        }
        if (!this.visitors) {
            ranked.add(new Origin(OriginKind.OTHERS, null));
        }
        ranked.add(new Origin(OriginKind.BOTS, null));
        ranked.add(new Origin(OriginKind.TOOLS, null));
        if (this.visitors) {
            ranked.add(new Origin(OriginKind.OTHERS, null));
        }

        Map<String, Long> bySource = new LinkedHashMap<>();
        for (String source : this.named) {
            if (!source.equals(this.unserved)) bySource.put(source, 0L);
        }
        boolean unservedHeard = false;
        for (Second second : this.window()) {
            for (Map.Entry<String, Long> each : second.sourceRequests.entrySet()) {
                if (each.getKey().equals(this.unserved)) {
                    unservedHeard |= each.getValue() > 0;
                } else {
                    bySource.merge(each.getKey(), each.getValue(), Long::sum);
                }
            }
        }
        List<String> order = new ArrayList<>(bySource.keySet());
        List<String> namedOrder = this.named;
        order.sort((a, b) -> {
            int busier = Long.compare(bySource.get(b), bySource.get(a));
            if (busier != 0) return busier;
            int ia = namedOrder.indexOf(a);
            int ib = namedOrder.indexOf(b);
            return ia != ib ? Integer.compare(ia < 0 ? Integer.MAX_VALUE : ia, ib < 0 ? Integer.MAX_VALUE : ib)
                : a.compareTo(b);
        });
        List<SiteRow> rows = new ArrayList<>();
        boolean more = order.size() > SITE_ROWS;
        int own = more ? SITE_ROWS - 1 : order.size();
        for (int i = 0; i < own; i++) {
            rows.add(new SiteRow(SiteKind.SITE, order.get(i)));
        }
        if (more) {
            rows.add(new SiteRow(SiteKind.MORE, null));
        }
        if (unservedHeard) {
            rows.add(new SiteRow(SiteKind.UNSERVED, this.unserved));
        }
        if (!ranked.equals(this.origins) || !rows.equals(this.sites)) {
            this.origins = List.copyOf(ranked);
            this.sites = List.copyOf(rows);
            this.revision++;
            // A waiting dot names rows by place: once the rows moved, it would fly between the wrong ones.
            this.births.clear();
        }
    }

    /** @return whether a frame names a source without a row while rows are free, so it is ranked at once */
    private boolean unplaced(@NonNull LiveHits frame) {
        for (LiveSource source : frame.sources()) {
            if (source.requests() > 0 && this.siteRowOf(source.source()) < 0) return true;
        }
        return false;
    }

    /** @return the row a source's dots fly to: its own, the unserved names', the others', or -1 for none yet */
    private int siteRowOf(@NonNull String source) {
        int more = -1;
        for (int i = 0; i < this.sites.size(); i++) {
            SiteRow row = this.sites.get(i);
            if (row.kind() == SiteKind.MORE) {
                more = i;
            } else if (source.equals(row.source())) {
                return i;
            }
        }
        return source.equals(this.unserved) ? -1 : more;
    }

    private int originRowOf(@NonNull LiveHit hit) {
        UserAgentFamily family = familyOf(hit.agent());
        OriginKind kind = family != null && family.bot() ? OriginKind.BOTS
            : family != null && family.tool() ? OriginKind.TOOLS : null;
        int others = 0;
        for (int i = 0; i < this.origins.size(); i++) {
            Origin origin = this.origins.get(i);
            if (kind != null && origin.kind() == kind) return i;
            if (kind == null && origin.kind() == OriginKind.ADDRESS && Objects.equals(origin.address(), hit.visitor())) {
                return i;
            }
            if (origin.kind() == OriginKind.OTHERS) others = i;
        }
        return others;
    }

    private boolean hasRow(@NonNull String address) {
        for (Origin origin : this.origins) {
            if (origin.kind() == OriginKind.ADDRESS && address.equals(origin.address())) return true;
        }
        return false;
    }

    private @NonNull Second slot(long stamp) {
        return this.seconds[(int) Math.floorMod(stamp, (long) WINDOW_SECONDS)];
    }

    private long windowStart() {
        return Math.max(this.first, this.latest - WINDOW_SECONDS + 1);
    }

    /** @return the window's heard seconds */
    private @NonNull List<Second> window() {
        List<Second> heard = new ArrayList<>();
        if (this.latest == Long.MIN_VALUE) return heard;
        for (long stamp = this.windowStart(); stamp <= this.latest; stamp++) {
            Second second = this.slot(stamp);
            if (second.stamp == stamp && second.heard) heard.add(second);
        }
        return heard;
    }

    private static @Nullable UserAgentFamily familyOf(@NonNull String agent) {
        int named = agent.indexOf(':');
        return UserAgentFamily.forKey(named > 0 ? agent.substring(0, named) : agent);
    }

    /** @return the family's own name, the name a client no family names gave itself, else its key */
    private static @NonNull String nameOf(@NonNull UserAgentFamily family, @NonNull String agent) {
        int named = agent.indexOf(':');
        if (named > 0 && named < agent.length() - 1) return agent.substring(named + 1);
        return family.name() != null ? family.name() : family.key();
    }

    /** @return the keys, the most counted first, equal counts by key */
    private static @NonNull List<String> busiestFirst(@NonNull Map<String, Integer> counts) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort((a, b) -> !b.getValue().equals(a.getValue()) ? Integer.compare(b.getValue(), a.getValue())
            : a.getKey().compareTo(b.getKey()));
        return entries.stream().map(Map.Entry::getKey).toList();
    }

    private static long total(@NonNull Map<String, Integer> counts) {
        long sum = 0;
        for (int count : counts.values()) sum += count;
        return sum;
    }

    private static @Nullable String busiest(@NonNull Map<String, Integer> counts) {
        String best = null;
        int most = 0;
        for (Map.Entry<String, Integer> each : counts.entrySet()) {
            if (each.getValue() > most || (each.getValue() == most && best != null && each.getKey().compareTo(best) < 0)) {
                best = each.getKey();
                most = each.getValue();
            }
        }
        return best;
    }
}
