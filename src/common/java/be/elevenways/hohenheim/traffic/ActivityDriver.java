package be.elevenways.hohenheim.traffic;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.traffic.ActivityModel.Flight;
import be.elevenways.hohenheim.traffic.ActivityModel.Origin;
import be.elevenways.hohenheim.traffic.ActivityModel.OriginKind;
import be.elevenways.hohenheim.traffic.ActivityModel.SiteKind;
import be.elevenways.hohenheim.traffic.ActivityModel.SiteRow;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.i18n.wording.ListStyle;
import be.elevenways.protoblast.common.math.Easing;
import be.elevenways.zenit.traffic.common.LiveHits;
import be.elevenways.zenit.traffic.common.StatusClass;
import be.elevenways.zenit3d.common.input.InputEvent;
import be.elevenways.zenit3d.common.motion.Tween;
import be.elevenways.zenit3d.common.shape.DotBatch;
import be.elevenways.zenit3d.common.shape.Picture;
import be.elevenways.zenit3d.common.shape.RectBatch;
import be.elevenways.zenit3d.common.shape.Rgba;
import be.elevenways.zenit3d.common.shape.StrokeBatch;
import be.elevenways.zenit3d.common.shape.TextBatch;
import be.elevenways.zenit3d.common.surface.FrameRest;
import be.elevenways.zenit3d.common.surface.Surface;
import be.elevenways.zenit3d.common.surface.SurfaceDriver;
import be.elevenways.zenit3d.common.text.TextAnchor;
import be.elevenways.zenit3d.common.text.TextLabel;
import be.elevenways.zenit3d.common.text.TextSheet;
import be.elevenways.zenit3d.common.text.TextStyle;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Draws the live Activity picture: each request a dot flying on a level curve from its visitor's row to its site's
 * row in its status class's colour, the rows named with their figures, a site row lighting up in the colour of what
 * arrives with the path asked beside it; under reduced motion the same rows and each site's busiest path of the
 * minute, nothing flying, over the last minute's requests per second as bars.
 * AIDEV-NOTE: every shape goes through zenit-3d's shared batches in one {@link Picture} and the loop rests through
 * {@link FrameRest} whenever no dot flies and nothing new arrived, so a quiet server costs a look every quarter second.
 * A frame's figures reach the page only through {@link Shown#minute}, after the draw that shows them, so the page's
 * line and the picture's rows are one count at every moment: a paused, hidden or resting picture holds both. The
 * words are the consumer's ({@link Words}, the page's microcopy for its reader), the colours the theme's resolved once
 * at mount ({@link Colours}); this class names neither a token nor a key's text.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class ActivityDriver implements SurfaceDriver {

    /** How long a dot flies from its visitor to its site. */
    public static final double TRAVEL_MS = 1_600;

    /** How many dots fly at once at most; a dot born past it takes the oldest's place. */
    public static final int DOTS = 4_096;

    /** How far across the control points of a flight's level curve stand: an even S. */
    static final float BEND = 0.5f;

    /** The share of its travel a dot's tail reaches back over. */
    static final float TRAIL = 0.05f;

    /** The straight pieces a guide's curve is drawn in. */
    static final int GUIDE_STEPS = 24;

    /** How many bot or tool families a row names before it counts the rest. */
    static final int FAMILIES_NAMED = 2;

    /** A row's height and the room between rows, in CSS pixels at the panel's type, as the rows fit their picture. */
    static final float ROW = 46f;
    static final float GAP = 10f;
    static final float HEAD = 30f;

    /** How much the rows and their gaps shrink or grow at most to fill the picture's height (a wall screen). */
    static final float LEAST_SCALE = 0.4f;
    static final float MOST_SCALE = 1.5f;

    /** How long a site row glows in the colour of what arrived, and how long the path asked stays beside it. */
    static final double PULSE_MS = 600;
    static final double PATH_MS = 1_800;

    /** How strongly a site row's glow covers its box at the moment of an arrival. */
    static final float PULSE_ALPHA = 0.35f;

    /** The site rows a picture draws at most: its own and the others' rows, and the names no site serves. */
    static final int SITE_ROWS = ActivityModel.SITE_ROWS + 1;

    /**
     * The type size every length here is drawn for: a picture whose surface sets larger type (a wall screen sizes it
     * from the screen) draws its rows, gaps and dots that much larger, never smaller.
     */
    static final float TYPE_PX = 14f;

    /** The share of the height the rows take when the minute's bars stand under them. */
    static final float ROWS_SHARE = 0.72f;

    private static final Microcopy HEAD_SECONDS = HohenheimMicrocopy.TRAFFIC_SCREEN.of("activity_head_seconds");
    private static final Microcopy HEAD_SITES = HohenheimMicrocopy.TRAFFIC_SCREEN.of("activity_head_sites");
    private static final Microcopy HEAD_VISITORS = HohenheimMicrocopy.TRAFFIC_SCREEN.of("activity_head_visitors");
    private static final Microcopy ORIGIN_BOTS = HohenheimMicrocopy.TRAFFIC_SCREEN.of("activity_origin_bots");
    private static final Microcopy ORIGIN_MORE = HohenheimMicrocopy.TRAFFIC_SCREEN.of("activity_origin_more");
    private static final Microcopy ORIGIN_REQUESTS = HohenheimMicrocopy.TRAFFIC_SCREEN.of("activity_origin_requests");
    private static final Microcopy ORIGIN_TOOLS = HohenheimMicrocopy.TRAFFIC_SCREEN.of("activity_origin_tools");
    private static final Microcopy ORIGIN_VISITOR = HohenheimMicrocopy.TRAFFIC_SCREEN.of("activity_origin_visitor");
    private static final Microcopy ORIGIN_VISITORS = HohenheimMicrocopy.TRAFFIC_SCREEN.of("activity_origin_visitors");
    private static final Microcopy FAMILIES_MORE = HohenheimMicrocopy.TRAFFIC_SCREEN.of("activity_families_more");
    private static final Microcopy RATE = HohenheimMicrocopy.TRAFFIC_SCREEN.of("activity_rate");
    private static final Microcopy SITE_MORE = HohenheimMicrocopy.TRAFFIC_SCREEN.of("activity_site_more");
    private static final Microcopy SITE_NEW = HohenheimMicrocopy.TRAFFIC_SCREEN.of("activity_site_new");
    private static final Microcopy SITE_UNSERVED = HohenheimMicrocopy.TRAFFIC_SCREEN.of("activity_site_unserved");
    private static final Microcopy PICTURE = HohenheimMicrocopy.TRAFFIC_SCREEN.of("activity_picture");
    private static final Microcopy PICTURE_ORIGIN = HohenheimMicrocopy.TRAFFIC_SCREEN.of("activity_picture_origin");
    private static final Microcopy PICTURE_SITE = HohenheimMicrocopy.TRAFFIC_SCREEN.of("activity_picture_site");

    /** The words a picture shows, worded for the page's reader; empty while its catalog is on its way. */
    public interface Words {
        @NonNull String word(@NonNull Microcopy copy);
    }

    /** What the page hears of the picture as it is drawn. */
    public interface Shown {

        /**
         * After a draw that showed a minute the picture had not shown yet: its figures, rows and words.
         *
         * @param picture the picture in words, for a reader who cannot see it
         */
        void minute(@NonNull String picture);

        /** After every draw: the dots on their way. */
        void flying(int dots);

        /**
         * When the height the rows fit at their own size changes: the rows of the longer side, and the minute's bars
         * under them when they are drawn, so a picture in its place is as tall as what it shows.
         */
        void fits(float heightPx);
    }

    /**
     * The theme's colours, packed Rgba, resolved once at mount.
     *
     * @param classes each status class's colour, in its declaration order
     */
    public record Colours(int box, int edge, int guide, int text, int muted, @NonNull List<Integer> classes) {

        public Colours {
            classes = List.copyOf(classes);
            if (classes.size() != StatusClass.values().length) {
                throw new IllegalArgumentException("A picture colours every status class");
            }
        }

        int of(@NonNull StatusClass statusClass) {
            return this.classes.get(statusClass.ordinal());
        }
    }

    private final @NonNull ActivityModel model;
    private final @NonNull Words words;
    private final @NonNull Colours colours;
    private final @NonNull TextStyle style;
    private final @NonNull Map<String, String> names;
    private final boolean strip;
    /** How much larger than at {@link #TYPE_PX} every length is drawn. */
    private final float unit;
    private final @NonNull Shown shown;

    private final RectBatch boxes = new RectBatch();
    private final StrokeBatch guides = new StrokeBatch();
    private final RectBatch bars = new RectBatch();
    private final DotBatch dots = new DotBatch(DOTS);
    private final TextSheet sheet = new TextSheet(1);
    private final TextBatch text = new TextBatch(this.sheet);
    private final Picture picture = new Picture(this.boxes, this.guides, this.bars, this.dots, this.text);
    private final FrameRest rest = new FrameRest();
    private final Set<Long> guided = new HashSet<>();
    private final float[] point = new float[2];

    private final Tween[] pulses = new Tween[SITE_ROWS];
    private final Tween[] pathFades = new Tween[SITE_ROWS];
    private final int[] pulseColours = new int[SITE_ROWS];
    private final @Nullable String[] shownPaths = new String[SITE_ROWS];
    private final List<Map<String, Integer>> arrivedPaths = new ArrayList<>(SITE_ROWS);
    /** The dots on their way, as their site rows will see them arrive: when, where, in which colour, asking what. */
    private final double[] arriveMs = new double[DOTS];
    private final int[] arriveRow = new int[DOTS];
    private final int[] arriveColour = new int[DOTS];
    private final @Nullable String[] arrivePath = new String[DOTS];
    private int arriving;
    /** The rows' height and gap at this frame, and where the site rows stand. */
    private float rowPx = ROW;
    private float gapPx = GAP;
    private float fromX;
    private float toX;
    private float siteWidth;
    private float fit = -1f;
    /** Whether the last frame drew a glow or a fading path. */
    private boolean lit;
    private TextLabel[] labels = new TextLabel[0];
    private int used;
    private boolean paused;
    private boolean drawnPaused;
    private long drawnSecond = Long.MIN_VALUE;
    private int drawnRevision = -1;
    private int frames;
    /** Whether a word drawn last came back empty (its catalog still on its way), so the next look draws again. */
    private boolean wordsPending;

    /**
     * @param names each source's name, for the sources the viewer may see
     * @param strip whether the last minute's bars stand under the flying dots too (a wall screen)
     */
    public ActivityDriver(@NonNull ActivityModel model, @NonNull Words words, @NonNull Colours colours,
                          @NonNull TextStyle style, @NonNull Map<String, String> names, boolean strip,
                          @NonNull Shown shown) {
        this.model = Objects.requireNonNull(model, "model cannot be null");
        this.words = Objects.requireNonNull(words, "words cannot be null");
        this.colours = Objects.requireNonNull(colours, "colours cannot be null");
        this.style = Objects.requireNonNull(style, "style cannot be null");
        this.names = Map.copyOf(names);
        this.strip = strip;
        this.unit = Math.max(1f, style.sizePx() / TYPE_PX);
        this.shown = Objects.requireNonNull(shown, "shown cannot be null");
        for (int i = 0; i < SITE_ROWS; i++) {
            this.pulses[i] = new Tween().set(0f);
            this.pathFades[i] = new Tween().set(0f);
            this.arrivedPaths.add(new HashMap<>());
        }
    }

    /** Takes a frame at once; the picture and the page show it at the next draw. */
    public void offer(@NonNull LiveHits frame) {
        this.model.accept(frame);
        if (this.paused) {
            this.model.dropBirths();
        }
    }

    /** Freezes the picture and its figures as they stand, or lets them move; what arrives meanwhile is never flown. */
    public void pause(boolean paused) {
        this.paused = paused;
        this.model.dropBirths();
    }

    public boolean paused() {
        return this.paused;
    }

    /** @return how many frames this driver drew, for a test asking that it rests */
    public int frames() {
        return this.frames;
    }

    /** @return the dots flying now that took a living dot's place */
    public long overwritten() {
        return this.dots.dropped();
    }

    @Override
    public void attach(@NonNull Surface surface) {
        this.picture.attach(surface);
        this.sheet.attach(surface);
    }

    @Override
    public void frame(@NonNull Surface surface) {
        boolean still = surface.reducedMotion();
        boolean minute = this.frames == 0 || this.wordsPending || this.model.latest() != this.drawnSecond
            || this.model.revision() != this.drawnRevision;
        boolean changed = minute || this.paused != this.drawnPaused || this.sheet.pending() > 0;
        if (this.paused && this.drawnPaused && this.frames > 0) {
            // Paused: the last picture stands; only a new size or motion preference draws it again.
            minute = false;
            changed = false;
        }
        // A glow drawn last frame is drawn once more, so its fade ends on its zero, never resting faintly lit.
        boolean moving = !this.paused && !still && (this.dots.moving() || this.arriving > 0
            || this.glowing(surface) || this.lit);
        if (!this.rest.drawOrRest(surface, moving, changed)) {
            return;
        }
        this.picture.begin(surface);
        this.dots.at(surface);
        this.used = 0;
        this.wordsPending = false;
        this.lit = false;
        float width = this.picture.width();
        float height = this.picture.height();
        if (this.model.revision() != this.drawnRevision || still) {
            // The rows moved (a dot's row is a place) or nothing flies: nothing waits to arrive, nothing glows.
            this.quiet();
        }
        if (still && this.dots.moving()) {
            this.dots.clear();
        }
        boolean withBars = still || this.strip;
        float fit = this.natural() / (withBars ? ROWS_SHARE : 1f);
        if (fit != this.fit) {
            this.fit = fit;
            this.shown.fits(fit);
        }
        float rows = withBars ? height * ROWS_SHARE : height;
        if (!still && !this.paused) {
            this.arrive(surface);
        }
        this.flows(surface, width, rows, still);
        if (withBars) {
            this.bars(0, rows + this.gapPx, width, height - rows - this.gapPx);
        }
        this.paths(surface, still);
        this.picture.render(surface);
        this.frames++;
        this.drawnSecond = this.model.latest();
        this.drawnRevision = this.model.revision();
        this.drawnPaused = this.paused;
        this.rest.drawn(surface, true);
        if (minute) {
            String described = this.words.word(this.described());
            if (described.isEmpty()) {
                // Its catalog is on its way: the next look draws and hands the page the minute then.
                this.wordsPending = true;
            } else {
                this.shown.minute(described);
            }
        }
        this.shown.flying(this.dots.flying());
    }

    @Override
    public void input(@NonNull InputEvent event) {
    }

    @Override
    public void detach() {
        this.sheet.detach();
        this.picture.detach();
    }

    /**
     * The rows, the guides of the paths taken, their names, a site row's glow, and (moving) this frame's births
     * flying between them. The rows and their gaps keep their own size scaled to fill the height the rows have,
     * within {@link #LEAST_SCALE} and {@link #MOST_SCALE}: a picture in its place fits them, a wall screen fills.
     */
    private void flows(@NonNull Surface surface, float width, float height, boolean still) {
        List<Origin> origins = this.model.origins();
        List<SiteRow> sites = this.model.sites();
        float u = this.unit;
        float originWidth = clamp(width * 0.2f, 140f * u, 260f * u);
        this.siteWidth = clamp(width * 0.26f, 170f * u, 320f * u);
        float small = this.style.sizePx() * 0.85f;
        this.label(HEAD_VISITORS, 0, (HEAD - 12) * u, TextAnchor.LEFT, this.colours.muted, small);
        this.label(HEAD_SITES, width, (HEAD - 12) * u, TextAnchor.RIGHT, this.colours.muted, small);
        float scale = clamp((height - HEAD * u) / (this.natural() - HEAD * u), LEAST_SCALE, MOST_SCALE);
        this.rowPx = ROW * u * scale;
        this.gapPx = GAP * u * scale;
        this.fromX = originWidth;
        this.toX = width - this.siteWidth;
        float fromX = this.fromX;
        float toX = this.toX;
        // A faint guide for every pair a dot flew between since the rows were last ranked.
        if (this.model.revision() != this.drawnRevision) {
            this.guided.clear();
        }
        for (Flight flight : this.paused ? List.<Flight>of() : this.model.takeBirths()) {
            if (flight.site() < 0 || flight.site() >= sites.size() || flight.origin() >= origins.size()) {
                continue;
            }
            this.guided.add((long) flight.origin() << 32 | flight.site());
            if (!still) {
                // A dot of a crowded moment flies off the middle of its rows by its lane, on both ends.
                float lane = flight.lane() * this.rowPx;
                double born = surface.frameMs() + flight.offsetMs();
                int colour = this.colours.of(flight.statusClass());
                this.dots.style(4.5f * u, colour).fade(0.08f, 0.15f).easing(Easing.IN_OUT).bend(BEND).trail(TRAIL)
                    .add(fromX, this.rowMiddle(flight.origin()) + lane, toX, this.rowMiddle(flight.site()) + lane,
                        born, TRAVEL_MS);
                this.awaitArrival(born + TRAVEL_MS, flight.site(), colour, flight.path());
            }
        }
        this.guides.style(u, this.colours.guide);
        for (long pair : this.guided) {
            float fromY = this.rowMiddle((int) (pair >>> 32));
            float toY = this.rowMiddle((int) pair);
            // The very curve the dots fly on (DotBatch.along), in straight pieces.
            this.guides.moveTo(fromX, fromY);
            for (int step = 1; step <= GUIDE_STEPS; step++) {
                DotBatch.along(fromX, fromY, toX, toY, BEND, (float) step / GUIDE_STEPS, this.point);
                this.guides.lineTo(this.point[0], this.point[1]);
            }
        }

        for (int i = 0; i < origins.size(); i++) {
            Origin origin = origins.get(i);
            float y = this.rowTop(i);
            this.boxes.rect(0, y, originWidth, this.rowPx, 8 * u, this.colours.box, this.colours.edge, u);
            this.rowWords(this.originName(origin), this.originSub(origin), 12 * u, y, this.rowPx,
                originWidth - 24 * u);
        }
        for (int i = 0; i < sites.size(); i++) {
            SiteRow row = sites.get(i);
            float y = this.rowTop(i);
            boolean none = row.kind() == SiteKind.UNSERVED;
            this.boxes.rect(toX, y, this.siteWidth, this.rowPx, 8 * u, none ? 0 : this.colours.box, this.colours.edge,
                u);
            float glow = still || i >= SITE_ROWS ? 0f : this.pulses[i].at(surface);
            if (glow > 0f) {
                this.lit = true;
                this.boxes.rect(toX, y, this.siteWidth, this.rowPx, 8 * u,
                    Rgba.withAlpha(this.pulseColours[i], PULSE_ALPHA * glow));
            }
            this.rowWords(this.siteName(row), null, toX + 12 * u, y, this.rowPx, this.siteWidth - 70 * u);
            this.label(this.rate(row), width - 10 * u, y + this.rowPx / 2 + this.style.sizePx() * 0.35f,
                TextAnchor.RIGHT, this.colours.muted, small);
        }
    }

    /** @return the height the rows fit at their own size: the header and the longer side's rows with their gaps */
    private float natural() {
        int most = Math.max(1, Math.max(this.model.origins().size(), this.model.sites().size()));
        return (HEAD + most * ROW + (most - 1) * GAP) * this.unit;
    }

    /** Keeps a dot's arrival for its site row; past the arrivals kept, the oldest is let go. */
    private void awaitArrival(double atMs, int site, int colour, @NonNull String path) {
        if (site >= SITE_ROWS) return;
        if (this.arriving == DOTS) {
            System.arraycopy(this.arriveMs, 1, this.arriveMs, 0, DOTS - 1);
            System.arraycopy(this.arriveRow, 1, this.arriveRow, 0, DOTS - 1);
            System.arraycopy(this.arriveColour, 1, this.arriveColour, 0, DOTS - 1);
            System.arraycopy(this.arrivePath, 1, this.arrivePath, 0, DOTS - 1);
            this.arriving--;
        }
        this.arriveMs[this.arriving] = atMs;
        this.arriveRow[this.arriving] = site;
        this.arriveColour[this.arriving] = colour;
        this.arrivePath[this.arriving] = path;
        this.arriving++;
    }

    /**
     * The dots that reached their site row by this frame: the row glows in the colour of the latest, and beside it
     * stands the path asked most among what arrived while the last path still showed.
     */
    private void arrive(@NonNull Surface surface) {
        double now = surface.frameMs();
        int kept = 0;
        for (int i = 0; i < this.arriving; i++) {
            if (this.arriveMs[i] > now) {
                this.arriveMs[kept] = this.arriveMs[i];
                this.arriveRow[kept] = this.arriveRow[i];
                this.arriveColour[kept] = this.arriveColour[i];
                this.arrivePath[kept] = this.arrivePath[i];
                kept++;
                continue;
            }
            int site = this.arriveRow[i];
            Map<String, Integer> asked = this.arrivedPaths.get(site);
            if (!this.pathFades[site].moving(surface)) {
                asked.clear();
            }
            asked.merge(Objects.requireNonNull(this.arrivePath[i]), 1, Integer::sum);
            this.shownPaths[site] = ActivityModel.busiestFirst(asked).get(0);
            this.pulseColours[site] = this.arriveColour[i];
            this.pulses[site].set(1f).to(surface, 0f, PULSE_MS, Easing.OUT);
            this.pathFades[site].set(1f).to(surface, 0f, PATH_MS, Easing.IN);
        }
        for (int i = kept; i < this.arriving; i++) {
            this.arrivePath[i] = null;
        }
        this.arriving = kept;
    }

    /** @return whether a site row still glows or shows a path that fades */
    private boolean glowing(@NonNull Surface surface) {
        for (int i = 0; i < SITE_ROWS; i++) {
            if (this.pathFades[i].moving(surface) || this.pulses[i].moving(surface)) return true;
        }
        return false;
    }

    /** Lets every awaited arrival, glow and path go. */
    private void quiet() {
        for (int i = 0; i < this.arriving; i++) {
            this.arrivePath[i] = null;
        }
        this.arriving = 0;
        for (int i = 0; i < SITE_ROWS; i++) {
            this.pulses[i].set(0f);
            this.pathFades[i].set(0f);
            this.shownPaths[i] = null;
            this.arrivedPaths.get(i).clear();
        }
    }

    /**
     * The path beside each site row, drawn after every other label so the pool keeps their places: moving, the one
     * shown since the latest arrival, fading; still, the row's busiest path of the minute.
     */
    private void paths(@NonNull Surface surface, boolean still) {
        List<SiteRow> sites = this.model.sites();
        float u = this.unit;
        float small = this.style.sizePx() * 0.85f;
        float room = this.toX - this.fromX - 20 * u;
        for (int i = 0; i < sites.size() && i < SITE_ROWS && room > 0; i++) {
            String path;
            int tint;
            if (still) {
                path = this.model.busiestPath(sites.get(i));
                tint = this.colours.muted;
            } else {
                float shown = this.pathFades[i].at(surface);
                path = shown > 0f ? this.shownPaths[i] : null;
                this.lit |= path != null;
                tint = Rgba.withAlpha(this.colours.muted, shown);
            }
            if (path == null || path.isEmpty()) continue;
            float y = this.rowTop(i);
            this.text.clip(this.toX - 10 * u - room, y, room, this.rowPx);
            this.label(Microcopy.literal(path), this.toX - 10 * u, y + small + 2 * u, TextAnchor.RIGHT, tint, small);
            this.text.unclipped();
        }
    }

    /**
     * The window's requests per second, a bar each, stacked by status class, the newest at the right; a second not
     * heard is drawn hollow, a second before the picture opened not at all.
     */
    private void bars(float x, float y, float width, float height) {
        float small = this.style.sizePx() * 0.85f;
        this.label(HEAD_SECONDS, x, y + 16 * this.unit, TextAnchor.LEFT, this.colours.muted, small);
        float top = y + 26 * this.unit;
        float plot = Math.max(1f, height - 26 * this.unit);
        List<long[]> seconds = this.model.perSecond();
        long most = 1;
        for (long[] second : seconds) {
            if (second == null) continue;
            long sum = 0;
            for (long count : second) sum += count;
            most = Math.max(most, sum);
        }
        float step = width / ActivityModel.WINDOW_SECONDS;
        float bar = Math.max(1f, step - 2f);
        int before = ActivityModel.WINDOW_SECONDS - seconds.size();
        for (int i = 0; i < seconds.size(); i++) {
            long[] second = seconds.get(i);
            float left = x + (before + i) * step;
            if (second == null) {
                // Not heard: a hollow bar the whole height, never a quiet zero.
                this.bars.rect(left, top, bar, plot, 2, 0, this.colours.edge, 1f);
                continue;
            }
            float base = top + plot;
            for (StatusClass statusClass : ActivityPalette.LEGEND) {
                long count = second[statusClass.ordinal()];
                if (count == 0) continue;
                float tall = plot * count / most;
                base -= tall;
                this.bars.rect(left, base, bar, tall, 0, this.colours.of(statusClass));
            }
        }
    }

    /** A row's name, and its line under it when it has one, cut at {@code room} so they never reach its figure. */
    private void rowWords(@NonNull Microcopy name, @Nullable Microcopy sub, float x, float y, float row, float room) {
        this.text.clip(x, y, Math.max(0f, room), row);
        if (sub == null) {
            this.label(name, x, y + row / 2 + this.style.sizePx() * 0.35f, TextAnchor.LEFT, this.colours.text,
                this.style.sizePx());
        } else {
            this.label(name, x, y + row * 0.45f, TextAnchor.LEFT, this.colours.text, this.style.sizePx());
            this.label(sub, x, y + row * 0.8f, TextAnchor.LEFT, this.colours.muted, this.style.sizePx() * 0.8f);
        }
        this.text.unclipped();
    }

    /** @return the picture in words: every row as drawn, visitors then sites with their rates */
    private @NonNull Microcopy described() {
        List<Microcopy> origins = new ArrayList<>();
        for (Origin origin : this.model.origins()) {
            Microcopy sub = this.originSub(origin);
            origins.add(sub == null ? this.originName(origin)
                : PICTURE_ORIGIN.withArg("name", this.originName(origin)).withArg("detail", sub));
        }
        List<Microcopy> sites = new ArrayList<>();
        for (SiteRow row : this.model.sites()) {
            sites.add(PICTURE_SITE.withArg("name", this.siteName(row)).withArg("count", this.model.requests(row)));
        }
        return PICTURE.withArg("visitors", Microcopy.list(origins, ListStyle.UNITS))
            .withArg("sites", Microcopy.list(sites, ListStyle.UNITS));
    }

    private @NonNull Microcopy originName(@NonNull Origin origin) {
        return switch (origin.kind()) {
            case ADDRESS -> this.model.showsAddresses() ? Microcopy.literal(Objects.requireNonNull(origin.address()))
                : ORIGIN_VISITOR.withArg("number", this.model.number(origin));
            case BOTS -> ORIGIN_BOTS;
            case TOOLS -> ORIGIN_TOOLS;
            case OTHERS -> this.model.origins().stream().anyMatch(each -> each.kind() == OriginKind.ADDRESS)
                ? ORIGIN_MORE.withArg("count", this.model.othersCount())
                : ORIGIN_VISITORS;
        };
    }

    /** @return the line under a row: its requests, or the families it stands for; null for none */
    private @Nullable Microcopy originSub(@NonNull Origin origin) {
        return switch (origin.kind()) {
            case BOTS, TOOLS -> {
                List<String> families = this.model.families(origin.kind());
                if (families.isEmpty()) yield null;
                List<Microcopy> named = new ArrayList<>();
                for (String family : families.subList(0, Math.min(FAMILIES_NAMED, families.size()))) {
                    named.add(Microcopy.literal(family));
                }
                Microcopy list = Microcopy.list(named, ListStyle.UNITS);
                yield families.size() <= FAMILIES_NAMED ? list
                    : FAMILIES_MORE.withArg("names", list).withArg("count", families.size() - FAMILIES_NAMED);
            }
            case ADDRESS -> ORIGIN_REQUESTS.withArg("count", this.model.hits(origin));
            // Beside rows of their own, the others are counted in their name; alone, the row says their requests.
            case OTHERS -> this.model.origins().stream().anyMatch(each -> each.kind() == OriginKind.ADDRESS) ? null
                : ORIGIN_REQUESTS.withArg("count", this.model.hits(origin));
        };
    }

    private @NonNull Microcopy siteName(@NonNull SiteRow row) {
        return switch (row.kind()) {
            case SITE -> {
                String name = this.names.get(row.source());
                yield name != null ? Microcopy.literal(name) : SITE_NEW;
            }
            case MORE -> SITE_MORE.withArg("count", this.model.sourcesOf(row));
            case UNSERVED -> SITE_UNSERVED;
        };
    }

    private @NonNull Microcopy rate(@NonNull SiteRow row) {
        return RATE.withArg("count", this.model.requests(row));
    }

    /** Draws a label of the pool, made the first time it is needed and kept for every frame after. */
    private void label(@NonNull Microcopy copy, float x, float baseline, @NonNull TextAnchor anchor, int rgba,
                       float size) {
        String words = this.words.word(copy);
        if (words.isEmpty()) {
            this.wordsPending = true;
            return;
        }
        if (this.used == this.labels.length) {
            TextLabel[] grown = new TextLabel[Math.max(16, this.labels.length * 2)];
            System.arraycopy(this.labels, 0, grown, 0, this.labels.length);
            this.labels = grown;
        }
        TextLabel label = this.labels[this.used];
        if (label == null || label.style().sizePx() != size) {
            if (label != null) {
                this.sheet.release(label);
            }
            label = this.sheet.label(this.style.sized(size));
            this.labels[this.used] = label;
        }
        this.used++;
        label.set(words);
        this.text.text(label, x, baseline, anchor, rgba);
    }

    /** @return the top of a row of either side, at this frame's row size */
    private float rowTop(int row) {
        return HEAD * this.unit + row * (this.rowPx + this.gapPx);
    }

    private float rowMiddle(int row) {
        return this.rowTop(row) + this.rowPx / 2;
    }

    /** @return the path shown beside a site row now, null for none: for a test of the arrival it follows */
    @Nullable String shownPath(int site, @NonNull Surface surface) {
        return site < SITE_ROWS && this.pathFades[site].at(surface) > 0f ? this.shownPaths[site] : null;
    }

    /** @return how strongly a site row glows now, 0 to 1: for a test of the arrival it follows */
    float glow(int site, @NonNull Surface surface) {
        return site < SITE_ROWS ? this.pulses[site].at(surface) : 0f;
    }

    private static float clamp(float value, float least, float most) {
        return Math.max(least, Math.min(most, value));
    }
}
