package be.elevenways.hohenheim.traffic;

import be.elevenways.protoblast.common.http.UserAgentFamily;
import be.elevenways.protoblast.common.math.Scatter;
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
 * counts (a source's every hit); rows and dots read the hits a frame carries, at most the live seam's bound,
 * and the hits it left out are said as "not drawn". The rows are ranked again every {@link #RANK_EVERY_SECONDS} heard
 * seconds of stamps (a gap counts), and at once when a frame names a site or a visitor without a row while one is
 * free, so a dot already flying keeps its path and rows do not jump at every frame; a dot still waiting when the rows
 * move is dropped. Requests of one visitor to one site at the same moment ({@link #MOMENT_MS}) are spread
 * ({@link Flight#lane}, a later birth), so several read as several dots; a site row takes at most
 * {@link #DOTS_PER_ROW_SECOND} dots a second, the rest counted as not drawn.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class ActivityModel {

    /** How many seconds the picture remembers: "the last minute". */
    public static final int WINDOW_SECONDS = 60;

    /** How many visitors get a row of their own: by address when addresses are shown, else numbered by their mark. */
    public static final int ADDRESS_ROWS = 4;

    /** How many sites get a row of their own; the rest share one. */
    public static final int SITE_ROWS = 7;

    /** How many heard seconds the rows stay as ranked. */
    public static final int RANK_EVERY_SECONDS = 5;

    /** How many dots wait to be born at most, while the picture is not drawn (a hidden page). */
    public static final int MOST_WAITING = 4_096;

    /**
     * How many dots a site row takes a second at most, so a site's live second carries no more
     * (HohenheimTraffic.LIVE_HITS_PER_SECOND); past it a hit is counted as not drawn.
     */
    public static final int DOTS_PER_ROW_SECOND = 60;

    /** How close in time two hits of one visitor to one site are drawn as one moment, so spread apart. */
    public static final double MOMENT_MS = 80;

    /** How far across its row a spread dot leaves and arrives at most, as a share of the row's height either way. */
    public static final float LANE_SPREAD = 0.3f;

    /** How much later a spread dot leaves at most, so dots of one moment also stand apart along their curve. */
    public static final double SPREAD_MS = 250;

    /** Which kind of visitor a row stands for; the members are the rows' order below the visitors of their own. */
    public enum OriginKind {
        /** One visitor, by address, or (addresses not shown) by its mark, worded as a number. */
        ADDRESS,
        /** Every crawler a user agent family calls a bot. */
        BOTS,
        /** Every client a family calls a tool: scripts, apps, monitors. */
        TOOLS,
        /** Every other visitor: the ones without a row of their own. */
        OTHERS
    }

    /**
     * One row a dot leaves from.
     *
     * @param address the visitor's address, or {@code #} and its mark when addresses are not shown; null for a group
     */
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
     * One dot to give birth to: from which row to which row, its answer's class, how long after its frame is drawn it
     * leaves (where in its second the hit was recorded), where across the rows it flies, and the path it asked.
     *
     * @param lane the share of a row's height the dot leaves and arrives off the rows' middle, 0 on the guide
     */
    public record Flight(int origin, int site, @NonNull StatusClass statusClass, double offsetMs, float lane,
                         @NonNull String path) {
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
        final Map<String, Integer> addresses = new HashMap<>();
        final Map<String, Integer> bots = new HashMap<>();
        final Map<String, Integer> tools = new HashMap<>();
        /** Each source's carried hits by path. */
        final Map<String, Map<String, Integer>> paths = new HashMap<>();
        /** Each site row's dots born for this second. */
        final Map<Integer, Integer> rowDots = new HashMap<>();

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
            this.addresses.clear();
            this.bots.clear();
            this.tools.clear();
            this.paths.clear();
            this.rowDots.clear();
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
    /** Each visitor row's number, while addresses are not shown: kept while the visitor keeps a row. */
    private final Map<String, Integer> numbers = new HashMap<>();
    private long first = Long.MIN_VALUE;
    private long rankedAt = Long.MIN_VALUE;
    private long spokenAt = Long.MIN_VALUE;
    private int revision;
    /** How many dots were spread so far: the seed of the next spread. */
    private int spread;

    /**
     * @param visitors whether the frames carry visitors' addresses, so a visitor's row names its address
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
        int ranked = this.revision;
        if (this.rankedAt == Long.MIN_VALUE || stamp - this.rankedAt >= RANK_EVERY_SECONDS
                || this.unplaced(frame)) {
            this.rankedAt = stamp;
            this.rank();
        }
        if (this.revision != ranked) {
            // The rows moved: a row's place names another row now, so its bound starts again.
            second.rowDots.clear();
        }
        // Hits of one visitor to one site within MOMENT_MS of the visitor's one before are a moment: spread apart.
        Map<String, Double> lastOffset = new HashMap<>();
        for (LiveSource source : frame.sources()) {
            int site = this.siteRowOf(source.source());
            for (LiveHit hit : source.hits()) {
                double offset = Math.max(0, Math.min(999, hit.at() - stamp * 1000));
                int origin = this.originRowOf(hit);
                int dots = second.rowDots.getOrDefault(site, 0);
                if (this.births.size() >= MOST_WAITING || dots >= DOTS_PER_ROW_SECOND) {
                    second.dropped++;
                    continue;
                }
                second.rowDots.put(site, dots + 1);
                Double before = lastOffset.put(this.keyOf(hit) + "|" + site, offset);
                float lane = 0f;
                double leaves = offset;
                if (before != null && Math.abs(offset - before) < MOMENT_MS) {
                    lane = (Scatter.unit(this.spread++) * 2f - 1f) * LANE_SPREAD;
                    leaves += Scatter.unit(this.spread++) * SPREAD_MS;
                }
                this.births.add(new Flight(origin, site, hit.statusClass(), leaves, lane, hit.path()));
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

    /** @return whether a visitor's row names its address; otherwise it is numbered ({@link #number}) */
    public boolean showsAddresses() {
        return this.visitors;
    }

    /** @return a visitor row's number when addresses are not shown, from 1; 0 for any other row */
    public int number(@NonNull Origin origin) {
        return origin.kind() != OriginKind.ADDRESS || this.visitors ? 0
            : this.numbers.getOrDefault(origin.address(), 0);
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

    /**
     * @return the path the window's carried hits of a site row asked most (equal counts by path), null for none
     */
    public @Nullable String busiestPath(@NonNull SiteRow row) {
        int index = this.sites.indexOf(row);
        Map<String, Integer> counted = new HashMap<>();
        for (Second second : this.window()) {
            for (Map.Entry<String, Map<String, Integer>> each : second.paths.entrySet()) {
                if (this.siteRowOf(each.getKey()) != index) continue;
                each.getValue().forEach((path, count) -> counted.merge(path, count, Integer::sum));
            }
        }
        List<String> busiest = busiestFirst(counted);
        return busiest.isEmpty() ? null : busiest.get(0);
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
     * @return the window's seconds since the first frame, oldest first: null for a second not heard, else its
     *         requests per status class; a page opened less than a minute ago has fewer than a minute of them
     */
    public @NonNull List<long @Nullable []> perSecond() {
        List<long[]> out = new ArrayList<>(WINDOW_SECONDS);
        if (this.latest == Long.MIN_VALUE) return Collections.unmodifiableList(out);
        for (long stamp = this.windowStart(); stamp <= this.latest; stamp++) {
            Second second = this.slot(stamp);
            boolean heard = second.stamp == stamp && second.heard;
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
        for (LiveHit hit : source.hits()) {
            second.paths.computeIfAbsent(source.source(), any -> new HashMap<>()).merge(hit.path(), 1, Integer::sum);
            OriginKind group = groupOf(hit.agent());
            if (group == null) {
                second.addresses.merge(this.keyOf(hit), 1, Integer::sum);
            } else {
                String name = nameOf(Objects.requireNonNull(familyOf(hit.agent())), hit.agent());
                (group == OriginKind.BOTS ? second.bots : second.tools).merge(name, 1, Integer::sum);
            }
        }
    }

    private void rank() {
        List<Origin> ranked = new ArrayList<>();
        Map<String, Integer> addresses = new HashMap<>();
        for (Second second : this.window()) {
            second.addresses.forEach((address, count) -> addresses.merge(address, count, Integer::sum));
        }
        List<String> busiest = busiestFirst(addresses);
        List<String> kept = busiest.subList(0, Math.min(ADDRESS_ROWS, busiest.size()));
        for (String address : kept) {
            ranked.add(new Origin(OriginKind.ADDRESS, address));
        }
        // A visitor keeps its number while it keeps a row; a new one takes the lowest number free.
        this.numbers.keySet().retainAll(kept);
        for (String address : kept) {
            if (this.numbers.containsKey(address)) continue;
            int free = 1;
            while (this.numbers.containsValue(free)) free++;
            this.numbers.put(address, free);
        }
        ranked.add(new Origin(OriginKind.BOTS, null));
        ranked.add(new Origin(OriginKind.TOOLS, null));
        ranked.add(new Origin(OriginKind.OTHERS, null));

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

    /**
     * @return whether a frame names a source without a row, or a person without a row while a visitor row is free, so
     *         it is ranked at once
     */
    private boolean unplaced(@NonNull LiveHits frame) {
        int addressRows = 0;
        for (Origin origin : this.origins) {
            if (origin.kind() == OriginKind.ADDRESS) addressRows++;
        }
        for (LiveSource source : frame.sources()) {
            if (source.requests() > 0 && this.siteRowOf(source.source()) < 0) return true;
            if (addressRows >= ADDRESS_ROWS) continue;
            for (LiveHit hit : source.hits()) {
                if (groupOf(hit.agent()) == null && !this.hasRow(this.keyOf(hit))) {
                    return true;
                }
            }
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
        OriginKind kind = groupOf(hit.agent());
        int others = 0;
        for (int i = 0; i < this.origins.size(); i++) {
            Origin origin = this.origins.get(i);
            if (kind != null && origin.kind() == kind) return i;
            if (kind == null && origin.kind() == OriginKind.ADDRESS && this.keyOf(hit).equals(origin.address())) {
                return i;
            }
            if (origin.kind() == OriginKind.OTHERS) others = i;
        }
        return others;
    }

    /** @return the visitor a hit names: its address when addresses are shown, else {@code #} and its mark */
    private @NonNull String keyOf(@NonNull LiveHit hit) {
        return this.visitors && hit.visitor() != null ? hit.visitor() : "#" + hit.mark();
    }

    /** @return the row kind a client stands in by its family: bots, tools, or null for a person */
    private static @Nullable OriginKind groupOf(@NonNull String agent) {
        UserAgentFamily family = familyOf(agent);
        return family != null && family.bot() ? OriginKind.BOTS
            : family != null && family.tool() ? OriginKind.TOOLS : null;
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
    static @NonNull List<String> busiestFirst(@NonNull Map<String, Integer> counts) {
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
}
