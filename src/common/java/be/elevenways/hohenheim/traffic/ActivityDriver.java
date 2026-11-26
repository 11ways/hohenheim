package be.elevenways.hohenheim.traffic;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.traffic.ActivityModel.Flight;
import be.elevenways.hohenheim.traffic.ActivityModel.Origin;
import be.elevenways.hohenheim.traffic.ActivityModel.OriginKind;
import be.elevenways.hohenheim.traffic.ActivityModel.SiteKind;
import be.elevenways.hohenheim.traffic.ActivityModel.SiteRow;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.math.Easing;
import be.elevenways.zenit.traffic.common.StatusClass;
import be.elevenways.zenit3d.common.input.InputEvent;
import be.elevenways.zenit3d.common.shape.DotBatch;
import be.elevenways.zenit3d.common.shape.Picture;
import be.elevenways.zenit3d.common.shape.RectBatch;
import be.elevenways.zenit3d.common.shape.StrokeBatch;
import be.elevenways.zenit3d.common.shape.TextBatch;
import be.elevenways.zenit3d.common.surface.FrameRest;
import be.elevenways.zenit3d.common.surface.Surface;
import be.elevenways.zenit3d.common.surface.SurfaceDriver;
import be.elevenways.zenit3d.common.text.TextAnchor;
import be.elevenways.zenit3d.common.text.TextLabel;
import be.elevenways.zenit3d.common.text.TextSheet;
import be.elevenways.zenit3d.common.text.TextStyle;
import be.elevenways.zenit.traffic.common.LiveHits;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Draws the live Activity picture: each request a dot flying from its visitor's row to its site's row in its status
 * class's colour, the rows named with their figures; under reduced motion the last minute's requests per second as
 * bars instead, nothing flying.
 *
 * AIDEV-NOTE: every shape goes through zenit-3d's shared batches in one {@link Picture} and the loop rests through
 * {@link FrameRest} whenever no dot flies and nothing new arrived, so a quiet server costs a look every quarter second.
 * The words are the consumer's ({@link Words}, the page's microcopy for its reader), the colours the theme's resolved
 * once at mount ({@link Colours}); this class names neither a token nor a key's text.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class ActivityDriver implements SurfaceDriver {

    /** How long a dot flies from its visitor to its site. */
    public static final double TRAVEL_MS = 1_600;

    /** How many dots fly at once at most; a dot born past it takes the oldest's place. */
    public static final int DOTS = 4_096;

    /** A row's height and the room between rows, in CSS pixels, at most. */
    static final float ROW = 46f;
    static final float GAP = 10f;
    static final float HEAD = 30f;

    private static final Microcopy HEAD_SECONDS = HohenheimMicrocopy.TRAFFIC_SCREEN.of("activity_head_seconds");
    private static final Microcopy HEAD_SITES = HohenheimMicrocopy.TRAFFIC_SCREEN.of("activity_head_sites");
    private static final Microcopy HEAD_VISITORS = HohenheimMicrocopy.TRAFFIC_SCREEN.of("activity_head_visitors");
    private static final Microcopy ORIGIN_BOTS = HohenheimMicrocopy.TRAFFIC_SCREEN.of("activity_origin_bots");
    private static final Microcopy ORIGIN_MORE = HohenheimMicrocopy.TRAFFIC_SCREEN.of("activity_origin_more");
    private static final Microcopy ORIGIN_REQUESTS = HohenheimMicrocopy.TRAFFIC_SCREEN.of("activity_origin_requests");
    private static final Microcopy ORIGIN_TOOLS = HohenheimMicrocopy.TRAFFIC_SCREEN.of("activity_origin_tools");
    private static final Microcopy ORIGIN_VISITORS = HohenheimMicrocopy.TRAFFIC_SCREEN.of("activity_origin_visitors");
    private static final Microcopy RATE = HohenheimMicrocopy.TRAFFIC_SCREEN.of("activity_rate");
    private static final Microcopy SITE_MORE = HohenheimMicrocopy.TRAFFIC_SCREEN.of("activity_site_more");
    private static final Microcopy SITE_NEW = HohenheimMicrocopy.TRAFFIC_SCREEN.of("activity_site_new");
    private static final Microcopy SITE_UNSERVED = HohenheimMicrocopy.TRAFFIC_SCREEN.of("activity_site_unserved");

    /** The words a picture shows, worded for the page's reader. */
    public interface Words {
        /** @param count the number the copy says as {@code count}, null for none */
        @NonNull String word(@NonNull Microcopy copy, @Nullable Long count);
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

    private final RectBatch boxes = new RectBatch();
    private final StrokeBatch guides = new StrokeBatch();
    private final RectBatch bars = new RectBatch();
    private final DotBatch dots = new DotBatch(DOTS);
    private final TextSheet sheet = new TextSheet(1);
    private final TextBatch text = new TextBatch(this.sheet);
    private final Picture picture = new Picture(this.boxes, this.guides, this.bars, this.dots, this.text);
    private final FrameRest rest = new FrameRest();
    private final Set<Long> guided = new HashSet<>();

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
                          @NonNull TextStyle style, @NonNull Map<String, String> names, boolean strip) {
        this.model = Objects.requireNonNull(model, "model cannot be null");
        this.words = Objects.requireNonNull(words, "words cannot be null");
        this.colours = Objects.requireNonNull(colours, "colours cannot be null");
        this.style = Objects.requireNonNull(style, "style cannot be null");
        this.names = Map.copyOf(names);
        this.strip = strip;
    }

    /** Takes a frame at once, so the figures read it even while the picture rests or is paused. */
    public void offer(@NonNull LiveHits frame) {
        this.model.accept(frame);
        if (this.paused) {
            this.model.dropBirths();
        }
    }

    /** Freezes the picture as it stands, or lets it move again; what arrives meanwhile is counted, never flown. */
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
        boolean changed = this.model.latest() != this.drawnSecond || this.model.revision() != this.drawnRevision
            || this.paused != this.drawnPaused || this.sheet.pending() > 0 || this.wordsPending;
        if (this.paused && this.drawnPaused && this.frames > 0) {
            // Paused: the last picture stands; only a new size or motion preference draws it again.
            changed = false;
        }
        if (!this.rest.drawOrRest(surface, !this.paused && !still && this.dots.moving(), changed)) {
            return;
        }
        this.picture.begin(surface);
        this.dots.at(surface);
        this.used = 0;
        this.wordsPending = false;
        float width = this.picture.width();
        float height = this.picture.height();
        if (still) {
            this.dots.clear();
            this.model.dropBirths();
            this.bars(0, 0, width, height);
        } else {
            float flows = this.strip ? height * 0.78f : height;
            this.flows(surface, width, flows);
            if (this.strip) {
                this.bars(0, flows + GAP, width, height - flows - GAP);
            }
        }
        this.picture.render(surface);
        this.frames++;
        this.drawnSecond = this.model.latest();
        this.drawnRevision = this.model.revision();
        this.drawnPaused = this.paused;
        this.rest.drawn(surface, true);
    }

    @Override
    public void input(@NonNull InputEvent event) {
    }

    @Override
    public void detach() {
        this.sheet.detach();
        this.picture.detach();
    }

    /** The rows, their guides and names, and this frame's births flying between them. */
    private void flows(@NonNull Surface surface, float width, float height) {
        List<Origin> origins = this.model.origins();
        List<SiteRow> sites = this.model.sites();
        float originWidth = clamp(width * 0.2f, 140f, 260f);
        float siteWidth = clamp(width * 0.26f, 170f, 320f);
        float small = this.style.sizePx() * 0.85f;
        this.label(this.words.word(HEAD_VISITORS, null), 0, HEAD - 12, TextAnchor.LEFT, this.colours.muted,
            small);
        this.label(this.words.word(HEAD_SITES, null), width, HEAD - 12, TextAnchor.RIGHT,
            this.colours.muted, small);
        float originRow = rowHeight(height, origins.size());
        float siteRow = rowHeight(height, sites.size());
        float fromX = originWidth;
        float toX = width - siteWidth;

        // A faint guide for every pair a dot flew between since the rows were last ranked.
        if (this.model.revision() != this.drawnRevision) {
            this.guided.clear();
        }
        this.guides.style(1f, this.colours.guide);
        for (Flight flight : this.paused ? List.<Flight>of() : this.model.takeBirths()) {
            if (flight.site() < 0 || flight.site() >= sites.size() || flight.origin() >= origins.size()) {
                continue;
            }
            float fromY = HEAD + flight.origin() * (originRow + GAP) + originRow / 2;
            float toY = HEAD + flight.site() * (siteRow + GAP) + siteRow / 2;
            this.guided.add((long) flight.origin() << 32 | flight.site());
            this.dots.style(4.5f, this.colours.of(flight.statusClass())).fade(0.08f, 0.15f).easing(Easing.IN_OUT)
                .add(fromX, fromY, toX, toY, surface.frameMs() + flight.offsetMs(), TRAVEL_MS);
        }
        for (long pair : this.guided) {
            int origin = (int) (pair >>> 32);
            int site = (int) pair;
            this.guides.moveTo(fromX, HEAD + origin * (originRow + GAP) + originRow / 2)
                .lineTo(toX, HEAD + site * (siteRow + GAP) + siteRow / 2);
        }

        for (int i = 0; i < origins.size(); i++) {
            Origin origin = origins.get(i);
            float y = HEAD + i * (originRow + GAP);
            this.boxes.rect(0, y, originWidth, originRow, 8, this.colours.box, this.colours.edge, 1f);
            this.rowWords(this.originName(origin), this.originSub(origin), 12, y, originRow, originWidth - 24);
        }
        for (int i = 0; i < sites.size(); i++) {
            SiteRow row = sites.get(i);
            float y = HEAD + i * (siteRow + GAP);
            boolean none = row.kind() == SiteKind.UNSERVED;
            this.boxes.rect(toX, y, siteWidth, siteRow, 8, none ? 0 : this.colours.box, this.colours.edge, 1f);
            String path = row.kind() == SiteKind.SITE ? this.model.busiestPath(Objects.requireNonNull(row.source()))
                : null;
            this.rowWords(this.siteName(row), path, toX + 12, y, siteRow, siteWidth - 70);
            this.label(this.words.word(RATE, this.model.requests(row)), width - 10,
                y + siteRow / 2 + this.style.sizePx() * 0.35f, TextAnchor.RIGHT, this.colours.muted, small);
        }
    }

    /** The window's requests per second, a bar each, stacked by status class; a second not heard drawn hollow. */
    private void bars(float x, float y, float width, float height) {
        float small = this.style.sizePx() * 0.85f;
        this.label(this.words.word(HEAD_SECONDS, null), x, y + 16, TextAnchor.LEFT, this.colours.muted,
            small);
        float top = y + 26;
        float plot = Math.max(1f, height - 26);
        List<long[]> seconds = this.model.perSecond();
        long most = 1;
        for (long[] second : seconds) {
            if (second == null) continue;
            long sum = 0;
            for (long count : second) sum += count;
            most = Math.max(most, sum);
        }
        float step = width / seconds.size();
        float bar = Math.max(1f, step - 2f);
        for (int i = 0; i < seconds.size(); i++) {
            long[] second = seconds.get(i);
            float left = x + i * step;
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
    private void rowWords(@NonNull String name, @Nullable String sub, float x, float y, float row, float room) {
        this.text.clip(x, y, Math.max(0f, room), row);
        if (sub == null || sub.isEmpty()) {
            this.label(name, x, y + row / 2 + this.style.sizePx() * 0.35f, TextAnchor.LEFT, this.colours.text,
                this.style.sizePx());
        } else {
            this.label(name, x, y + row * 0.45f, TextAnchor.LEFT, this.colours.text, this.style.sizePx());
            this.label(sub, x, y + row * 0.8f, TextAnchor.LEFT, this.colours.muted, this.style.sizePx() * 0.8f);
        }
        this.text.unclipped();
    }

    private @NonNull String originName(@NonNull Origin origin) {
        return switch (origin.kind()) {
            case ADDRESS -> Objects.requireNonNull(origin.address());
            case BOTS -> this.words.word(ORIGIN_BOTS, null);
            case TOOLS -> this.words.word(ORIGIN_TOOLS, null);
            case OTHERS -> this.model.origins().stream().anyMatch(each -> each.kind() == OriginKind.ADDRESS)
                ? this.words.word(ORIGIN_MORE, (long) this.model.othersCount())
                : this.words.word(ORIGIN_VISITORS, null);
        };
    }

    private @NonNull String originSub(@NonNull Origin origin) {
        return switch (origin.kind()) {
            case BOTS, TOOLS -> String.join(", ", this.model.families(origin.kind()).stream().limit(3).toList());
            case ADDRESS, OTHERS -> this.words.word(ORIGIN_REQUESTS, this.model.hits(origin));
        };
    }

    private @NonNull String siteName(@NonNull SiteRow row) {
        return switch (row.kind()) {
            case SITE -> {
                String name = this.names.get(row.source());
                yield name != null ? name : this.words.word(SITE_NEW, null);
            }
            case MORE -> this.words.word(SITE_MORE, (long) this.model.sourcesOf(row));
            case UNSERVED -> this.words.word(SITE_UNSERVED, null);
        };
    }

    /** Draws a label of the pool, made the first time it is needed and kept for every frame after. */
    private void label(@NonNull String words, float x, float baseline, @NonNull TextAnchor anchor, int rgba,
                       float size) {
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

    private static float rowHeight(float height, int rows) {
        if (rows == 0) return ROW;
        return Math.max(18f, Math.min(ROW, (height - HEAD - (rows - 1) * GAP) / rows));
    }

    private static float clamp(float value, float least, float most) {
        return Math.max(least, Math.min(most, value));
    }
}
