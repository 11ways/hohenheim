package be.elevenways.hohenheim.traffic;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.i18n.wording.ListStyle;
import be.elevenways.protoblast.common.math.Easing;
import be.elevenways.protoblast.common.math.GlslMath;
import be.elevenways.zenit3d.common.input.InputEvent;
import be.elevenways.zenit3d.common.motion.Tween;
import be.elevenways.zenit3d.common.shape.BandBatch;
import be.elevenways.zenit3d.common.shape.DotBatch;
import be.elevenways.zenit3d.common.shape.Picture;
import be.elevenways.zenit3d.common.shape.RectBatch;
import be.elevenways.zenit3d.common.shape.Rgba;
import be.elevenways.zenit3d.common.surface.FrameRest;
import be.elevenways.zenit3d.common.surface.Surface;
import be.elevenways.zenit3d.common.surface.SurfaceDriver;
import be.elevenways.zenit3d.common.text.TextAnchor;
import be.elevenways.zenit3d.common.text.TextStyle;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Draws the live "Apps sending" picture: the viewer's apps on the left, where they send on the right, each flow a band
 * as wide as what the app sent a second to that place over the last reading, with a mark travelling along it.
 *
 * AIDEV-NOTE: the data is one reading a minute (AppDestinations lists each host's counters once a minute), so the
 * picture invents no second: a band's width is the reading's bytes over the seconds it covers, and it changes only
 * when a reading arrives, swelling or thinning to it over {@link #SWELL_MS}. The mark on a band paces that same rate
 * (one at a time per band, crossing in {@link #SLOWEST_MS} for the thinnest band down to {@link #FASTEST_MS} for the
 * widest); it stands for no packet and no connection. Under reduced motion nothing moves: the bands stand at the
 * reading's widths over the readings heard since the page opened, as bars. Every shape goes through zenit-3d's shared
 * batches in one {@link Picture}, and the loop rests through {@link FrameRest} while no band carries anything.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class AppsSendingDriver implements SurfaceDriver {

    /** How long a band takes to swell or thin to a new reading. */
    public static final double SWELL_MS = 1_200;

    /** How long the mark of the thinnest band takes to cross, and that of the widest. */
    public static final double SLOWEST_MS = 6_000;
    public static final double FASTEST_MS = 1_500;

    /** The readings the bars under a still picture keep, the newest at the right. */
    public static final int READINGS = 30;

    /** The marks that travel at once at most: one per band, and those still fading out. */
    static final int MARKS = 256;

    /** The share of a row's height its bands fill at most, the widest app's or destination's. */
    static final float BAND_SHARE = 0.8f;

    /** How thin a band that carries anything is drawn at least, at the panel's type. */
    static final float LEAST_BAND = 2f;

    /** How strongly a band covers what stands under it. */
    static final float BAND_ALPHA = 0.6f;

    /** The straight pieces a band's edges are drawn in. */
    static final int STEPS = ActivityDriver.GUIDE_STEPS;

    private static final Microcopy HEAD_APPS = HohenheimMicrocopy.TRAFFIC_SCREEN.of("apps_sending_head_apps");
    private static final Microcopy HEAD_PLACES = HohenheimMicrocopy.TRAFFIC_SCREEN.of("apps_sending_head_places");
    private static final Microcopy HEAD_READINGS = HohenheimMicrocopy.TRAFFIC_SCREEN.of("apps_sending_head_readings");
    private static final Microcopy SENDS = HohenheimMicrocopy.TRAFFIC_SCREEN.of("apps_sending_sends");
    private static final Microcopy PICTURE = HohenheimMicrocopy.TRAFFIC_SCREEN.of("apps_sending_picture");
    private static final Microcopy PICTURE_APP = HohenheimMicrocopy.TRAFFIC_SCREEN.of("apps_sending_picture_app");
    private static final Microcopy PICTURE_TO = HohenheimMicrocopy.TRAFFIC_SCREEN.of("apps_sending_picture_to");
    private static final Microcopy PICTURE_NONE = HohenheimMicrocopy.TRAFFIC_SCREEN.of("apps_sending_picture_none");
    private static final Microcopy SPOKEN = HohenheimMicrocopy.TRAFFIC_SCREEN.of("apps_sending_spoken");
    private static final Microcopy SPOKEN_APP = HohenheimMicrocopy.TRAFFIC_SCREEN.of("apps_sending_spoken_app");
    private static final Microcopy SPOKEN_QUIET = HohenheimMicrocopy.TRAFFIC_SCREEN.of("apps_sending_spoken_quiet");

    /** What the page hears of the picture as it is drawn. */
    public interface Shown {

        /**
         * After the draw that first showed a reading: the picture in words, and the one line a screen reader is told.
         */
        void reading(@NonNull String picture, @NonNull String spoken);

        /** After every draw: the marks on their way. */
        void travelling(int marks);

        /** When the height the rows fit at their own size changes, the readings' bars under them when drawn. */
        void fits(float heightPx);
    }

    /**
     * The theme's colours, packed Rgba, resolved once at mount.
     *
     * @param hues each categorical hue's series colour, by its ColorHue token
     */
    public record Colours(@NonNull PictureInk ink, @NonNull Map<String, Integer> hues) {

        public Colours {
            Objects.requireNonNull(ink, "ink cannot be null");
            hues = Map.copyOf(hues);
        }

        int hue(@NonNull String token) {
            Integer colour = this.hues.get(token);
            return colour != null ? colour : this.ink.muted();
        }
    }

    /** One reading as the bars draw it: what each app row sent a second, in its hue. */
    private record Reading(@NonNull Instant at, @NonNull List<String> hues, long @NonNull [] perSecond) {
    }

    private final @NonNull Colours colours;
    private final @NonNull TextStyle style;
    private final @NonNull Shown shown;
    /** How much larger than at {@link ActivityDriver#TYPE_PX} every length is drawn. */
    private final float unit;

    private final RectBatch boxes = new RectBatch();
    private final BandBatch bands = new BandBatch();
    private final RectBatch bars = new RectBatch();
    private final DotBatch marks = new DotBatch(MARKS);
    private final @NonNull TrafficLabels labels;
    private final @NonNull Picture picture;
    private final FrameRest rest = new FrameRest();

    private final float[] xs = new float[STEPS + 1];
    private final float[] tops = new float[STEPS + 1];
    private final float[] bottoms = new float[STEPS + 1];
    private final float[] point = new float[2];
    /** Each flow's rate on its way to the reading's, by its app's and destination's keys. */
    private final Map<String, Tween> rates = new HashMap<>();
    private final List<Reading> readings = new ArrayList<>();

    private @NonNull AppsSendingView view = AppsSendingView.none(true);
    private int revision;
    private int drawnRevision = -1;
    /** The reading the page was last told, so a frame redrawn for its words never tells it again. */
    private int toldRevision = -1;
    /** When each flow of the view sends its next mark. */
    private double[] nextMark = new double[0];
    private float fit = -1f;
    private int frames;
    private boolean wordsPending;
    private boolean paused;
    private boolean drawnPaused;
    /** The newest reading offered while paused, drawn on resume. */
    private @Nullable AppsSendingView held;

    public AppsSendingDriver(TrafficLabels.@NonNull Words words, @NonNull Colours colours, @NonNull TextStyle style,
                             @NonNull Shown shown) {
        this.colours = Objects.requireNonNull(colours, "colours cannot be null");
        this.style = Objects.requireNonNull(style, "style cannot be null");
        this.shown = Objects.requireNonNull(shown, "shown cannot be null");
        this.unit = Math.max(1f, style.sizePx() / ActivityDriver.TYPE_PX);
        this.labels = new TrafficLabels(words, style);
        this.picture = new Picture(this.boxes, this.bands, this.bars, this.marks, this.labels.batch());
    }

    /** Takes a reading at once; the picture swells to it and tells the page at the next draw; paused, it waits. */
    public void offer(@NonNull AppsSendingView view) {
        Objects.requireNonNull(view, "view cannot be null");
        if (this.paused) {
            this.held = view;
            return;
        }
        this.view = view;
        this.nextMark = new double[view.flows().size()];
        this.revision++;
        Instant at = view.at();
        if (at != null && (this.readings.isEmpty()
                || !this.readings.get(this.readings.size() - 1).at().equals(at))) {
            List<String> hues = new ArrayList<>(view.apps().size());
            long[] perSecond = new long[view.apps().size()];
            for (int i = 0; i < view.apps().size(); i++) {
                hues.add(view.apps().get(i).hue());
                perSecond[i] = view.apps().get(i).perSecond();
            }
            this.readings.add(new Reading(at, hues, perSecond));
            if (this.readings.size() > READINGS) {
                this.readings.remove(0);
            }
        }
    }

    /**
     * Freezes the picture as it stands, or lets it move: a reading offered meanwhile waits, and resuming draws the
     * newest one (the requests picture's Pause, one control for both).
     */
    public void pause(boolean paused) {
        this.paused = paused;
        AppsSendingView waiting = this.held;
        this.held = null;
        if (!paused && waiting != null) {
            this.offer(waiting);
        }
    }

    /** @return how many frames this driver drew, for a test asking that it rests */
    public int frames() {
        return this.frames;
    }

    @Override
    public void attach(@NonNull Surface surface) {
        this.picture.attach(surface);
        this.labels.attach(surface);
    }

    @Override
    public void frame(@NonNull Surface surface) {
        boolean still = surface.reducedMotion();
        boolean fresh = this.revision != this.drawnRevision;
        boolean changed = this.frames == 0 || fresh || this.wordsPending || this.labels.painting()
            || this.paused != this.drawnPaused;
        boolean frozen = this.paused && this.drawnPaused && this.frames > 0;
        if (frozen) {
            // Paused: the last picture stands; only a new size or motion preference draws it again.
            changed = false;
        }
        boolean moving = !frozen && !this.paused && !still
            && (!this.view.flows().isEmpty() || this.marks.moving() || this.swelling(surface));
        if (!this.rest.drawOrRest(surface, moving, changed)) {
            return;
        }
        if (fresh) {
            this.retarget(surface);
        }
        this.picture.begin(surface);
        this.marks.at(surface);
        this.labels.begin();
        if (still && this.marks.moving()) {
            this.marks.clear();
        }
        float fit = this.natural() / (still ? ActivityDriver.ROWS_SHARE : 1f);
        if (fit != this.fit) {
            this.fit = fit;
            this.shown.fits(fit);
        }
        float width = this.picture.width();
        float height = this.picture.height();
        float rows = still ? height * ActivityDriver.ROWS_SHARE : height;
        float gap = this.flows(surface, width, rows, still);
        if (still) {
            this.bars(rows + gap, width, height - rows - gap);
        }
        this.picture.render(surface);
        this.wordsPending = this.labels.pending();
        this.frames++;
        this.rest.drawn(surface, true);
        if (this.toldRevision != this.revision && this.view.at() != null) {
            String described = this.labels.word(this.described());
            String spoken = this.labels.word(this.spoken());
            if (described.isEmpty() || spoken.isEmpty()) {
                this.wordsPending = true;
            } else {
                this.shown.reading(described, spoken);
                this.toldRevision = this.revision;
            }
        }
        this.drawnRevision = this.revision;
        this.drawnPaused = this.paused;
        this.shown.travelling(this.marks.flying());
    }

    @Override
    public void input(@NonNull InputEvent event) {
    }

    @Override
    public void detach() {
        this.labels.detach();
        this.picture.detach();
    }

    /** Starts every flow of the new reading on its way from where it stands; a flow gone from it is let go. */
    private void retarget(@NonNull Surface surface) {
        Map<String, Tween> kept = new HashMap<>();
        for (AppsSendingView.Flow flow : this.view.flows()) {
            String key = this.keyOf(flow);
            Tween rate = this.rates.get(key);
            if (rate == null) {
                rate = new Tween().set(0f);
            }
            rate.to(surface, flow.perSecond(), SWELL_MS, Easing.IN_OUT);
            kept.put(key, rate);
        }
        this.rates.clear();
        this.rates.putAll(kept);
    }

    /** @return whether a band still swells or thins */
    private boolean swelling(@NonNull Surface surface) {
        for (Tween rate : this.rates.values()) {
            if (rate.moving(surface)) return true;
        }
        return false;
    }

    /**
     * The rows, each flow's band between them stacked within its app's and its destination's row, and (moving) each
     * band's mark. The rows keep their own size scaled to fill the height they have, as the requests picture's do.
     *
     * @return the gap between rows at this frame
     */
    private float flows(@NonNull Surface surface, float width, float height, boolean still) {
        List<AppsSendingView.App> apps = this.view.apps();
        List<AppsSendingView.Place> places = this.view.places();
        List<AppsSendingView.Flow> flows = this.view.flows();
        float u = this.unit;
        float small = this.style.sizePx() * 0.85f;
        int muted = this.colours.ink().muted();
        this.labels.label(HEAD_APPS, 0, (ActivityDriver.HEAD - 12) * u, TextAnchor.LEFT, muted, small);
        this.labels.label(HEAD_PLACES, width, (ActivityDriver.HEAD - 12) * u, TextAnchor.RIGHT, muted, small);
        float head = ActivityDriver.HEAD * u;
        float scale = GlslMath.clamp((height - head) / Math.max(1f, this.natural() - head),
            ActivityDriver.LEAST_SCALE, ActivityDriver.MOST_SCALE);
        float rowPx = ActivityDriver.ROW * u * scale;
        float gapPx = ActivityDriver.GAP * u * scale;
        int longer = Math.max(1, Math.max(apps.size(), places.size()));
        float span = longer * rowPx + (longer - 1) * gapPx;
        float appRow = apps.isEmpty() ? rowPx : (span - (apps.size() - 1) * gapPx) / apps.size();
        float placeRow = places.isEmpty() ? rowPx : (span - (places.size() - 1) * gapPx) / places.size();
        float column = GlslMath.clamp(width * 0.21f, 150f * u, 260f * u);
        float fromX = column;
        float toX = width - column;

        // One scale for every band: the busiest app's or destination's bands fill BAND_SHARE of its row.
        long[] appTarget = new long[apps.size()];
        long[] placeTarget = new long[places.size()];
        long widest = 0;
        for (AppsSendingView.Flow flow : flows) {
            appTarget[flow.app()] += flow.perSecond();
            placeTarget[flow.place()] += flow.perSecond();
            widest = Math.max(widest, flow.perSecond());
        }
        float perByte = Float.MAX_VALUE;
        for (long sum : appTarget) {
            if (sum > 0) perByte = Math.min(perByte, appRow * BAND_SHARE / sum);
        }
        for (long sum : placeTarget) {
            if (sum > 0) perByte = Math.min(perByte, placeRow * BAND_SHARE / sum);
        }
        float[] thick = new float[flows.size()];
        float[] appStack = new float[apps.size()];
        float[] placeStack = new float[places.size()];
        for (int i = 0; i < flows.size(); i++) {
            AppsSendingView.Flow flow = flows.get(i);
            Tween rate = this.rates.get(this.keyOf(flow));
            float now = rate == null ? flow.perSecond() : rate.at(surface);
            thick[i] = now > 0f ? Math.max(LEAST_BAND * u, now * perByte) : 0f;
            appStack[flow.app()] += thick[i];
            placeStack[flow.place()] += thick[i];
        }
        float[] appOffset = new float[apps.size()];
        float[] placeOffset = new float[places.size()];
        double nowMs = surface.frameMs();
        for (int i = 0; i < flows.size(); i++) {
            AppsSendingView.Flow flow = flows.get(i);
            float t = thick[i];
            if (t <= 0f) continue;
            float fromY = head + flow.app() * (appRow + gapPx) + appRow / 2 - appStack[flow.app()] / 2
                + appOffset[flow.app()] + t / 2;
            float toY = head + flow.place() * (placeRow + gapPx) + placeRow / 2 - placeStack[flow.place()] / 2
                + placeOffset[flow.place()] + t / 2;
            appOffset[flow.app()] += t;
            placeOffset[flow.place()] += t;
            int hue = this.colours.hue(apps.get(flow.app()).hue());
            for (int step = 0; step <= STEPS; step++) {
                DotBatch.along(fromX, fromY, toX, toY, ActivityDriver.BEND, (float) step / STEPS, this.point);
                this.xs[step] = this.point[0];
                this.tops[step] = this.point[1] - t / 2;
                this.bottoms[step] = this.point[1] + t / 2;
            }
            this.bands.style(0f, Rgba.withAlpha(hue, BAND_ALPHA)).band(this.xs, this.tops, this.bottoms, STEPS + 1);
            if (!still && !this.paused && i < this.nextMark.length && nowMs >= this.nextMark[i]) {
                double share = widest > 0 ? (double) flow.perSecond() / widest : 0;
                double travel = SLOWEST_MS - (SLOWEST_MS - FASTEST_MS) * share;
                this.marks.style(GlslMath.clamp(t * 0.35f, 1.5f * u, 5f * u), this.colours.ink().box())
                    .fade(0.1f, 0.1f).easing(Easing.LINEAR).bend(ActivityDriver.BEND)
                    .add(fromX, fromY, toX, toY, nowMs, travel);
                this.nextMark[i] = nowMs + travel;
            }
        }

        PictureInk ink = this.colours.ink();
        for (int i = 0; i < apps.size(); i++) {
            AppsSendingView.App app = apps.get(i);
            float y = head + i * (appRow + gapPx);
            this.boxes.rect(0, y, column, appRow, 8 * u, ink.box(), ink.edge(), u);
            boolean sends = app.perSecond() > 0;
            if (sends) {
                float swatch = 10 * u;
                this.boxes.rect(12 * u, y + appRow * 0.45f - swatch * 0.85f, swatch, swatch, 2 * u,
                    this.colours.hue(app.hue()));
            }
            this.labels.row(app.name(), sends ? SENDS.withArg("bytes", app.perSecond()) : null, 30 * u, y, appRow,
                column - 42 * u, sends ? ink.text() : ink.muted(), ink.muted());
        }
        for (int i = 0; i < places.size(); i++) {
            AppsSendingView.Place place = places.get(i);
            float y = head + i * (placeRow + gapPx);
            this.boxes.rect(toX, y, column, placeRow, 8 * u, ink.box(), ink.edge(), u);
            this.labels.row(place.name(), place.detail().isEmpty() ? null : Microcopy.literal(place.detail()),
                toX + 12 * u, y, placeRow, column - 24 * u, ink.text(), ink.muted());
        }
        return gapPx;
    }

    /**
     * The readings heard since the page opened, a bar each, stacked by app in its hue, the newest at the right: what a
     * still picture shows of the bands' changes.
     */
    private void bars(float y, float width, float height) {
        float u = this.unit;
        float small = this.style.sizePx() * 0.85f;
        this.labels.label(HEAD_READINGS, 0, y + 16 * u, TextAnchor.LEFT, this.colours.ink().muted(), small);
        float top = y + 26 * u;
        float plot = Math.max(1f, height - 26 * u);
        long most = 1;
        for (Reading reading : this.readings) {
            long sum = 0;
            for (long sent : reading.perSecond()) sum += sent;
            most = Math.max(most, sum);
        }
        float step = width / READINGS;
        float bar = Math.max(1f, step - 2f);
        int before = READINGS - this.readings.size();
        for (int i = 0; i < this.readings.size(); i++) {
            Reading reading = this.readings.get(i);
            float base = top + plot;
            for (int app = 0; app < reading.perSecond().length; app++) {
                long sent = reading.perSecond()[app];
                if (sent <= 0) continue;
                float tall = plot * sent / most;
                base -= tall;
                this.bars.rect((before + i) * step, base, bar, tall, 0, this.colours.hue(reading.hues().get(app)));
            }
        }
    }

    /** @return the height the rows fit at their own size: the header and the longer side's rows with their gaps */
    private float natural() {
        int most = Math.max(1, Math.max(this.view.apps().size(), this.view.places().size()));
        return (ActivityDriver.HEAD + most * ActivityDriver.ROW + (most - 1) * ActivityDriver.GAP) * this.unit;
    }

    /** @return the picture in words: every app with what it sent a second to each destination */
    private @NonNull Microcopy described() {
        List<Microcopy> apps = new ArrayList<>();
        for (int i = 0; i < this.view.apps().size(); i++) {
            AppsSendingView.App app = this.view.apps().get(i);
            List<Microcopy> to = new ArrayList<>();
            for (AppsSendingView.Flow flow : this.view.flows()) {
                if (flow.app() != i) continue;
                to.add(PICTURE_TO.withArg("place", this.view.places().get(flow.place()).name())
                    .withArg("bytes", flow.perSecond()));
            }
            apps.add(to.isEmpty() ? PICTURE_NONE.withArg("name", app.name())
                : PICTURE_APP.withArg("name", app.name()).withArg("bytes", app.perSecond())
                    .withArg("places", Microcopy.list(to, ListStyle.UNITS)));
        }
        return PICTURE.withArg("apps", Microcopy.list(apps, ListStyle.UNITS));
    }

    /** @return the one line a screen reader is told of this reading: who sent how much a second */
    private @NonNull Microcopy spoken() {
        Instant at = Objects.requireNonNull(this.view.at(), "a reading is spoken once taken");
        List<Microcopy> sending = new ArrayList<>();
        for (AppsSendingView.App app : this.view.apps()) {
            if (app.perSecond() > 0) {
                sending.add(SPOKEN_APP.withArg("name", app.name()).withArg("bytes", app.perSecond()));
            }
        }
        return sending.isEmpty() ? SPOKEN_QUIET.withArg("at", at)
            : SPOKEN.withArg("at", at).withArg("apps", Microcopy.list(sending, ListStyle.UNITS));
    }
    private @NonNull String keyOf(AppsSendingView.@NonNull Flow flow) {
        return this.view.apps().get(flow.app()).key() + "|" + this.view.places().get(flow.place()).key();
    }

    /** @return a flow's width on its way, in bytes a second: for a test of the swell */
    float rate(@NonNull String appKey, @NonNull String placeKey, @NonNull Surface surface) {
        Tween rate = this.rates.get(appKey + "|" + placeKey);
        return rate == null ? 0f : rate.at(surface);
    }

    /** @return how many readings the bars under a still picture hold */
    int readingsKept() {
        return this.readings.size();
    }
}
