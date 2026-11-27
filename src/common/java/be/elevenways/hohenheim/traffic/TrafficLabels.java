package be.elevenways.hohenheim.traffic;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit3d.common.shape.TextBatch;
import be.elevenways.zenit3d.common.surface.Surface;
import be.elevenways.zenit3d.common.text.TextAnchor;
import be.elevenways.zenit3d.common.text.TextLabel;
import be.elevenways.zenit3d.common.text.TextSheet;
import be.elevenways.zenit3d.common.text.TextStyle;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Objects;

/**
 * The words of a live traffic picture, drawn through one pool of zenit-3d labels in the page's words for its reader.
 *
 * AIDEV-NOTE: a frame draws its labels in the same order every time ({@link #begin}, then {@link #label}s), so a label
 * keeps its place in the pool and is repainted only when its words change. A word that comes back empty (its catalog
 * still on its way) is left undrawn and {@link #pending} says so, so the picture draws again at its next look.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class TrafficLabels {

    /** The words a picture shows, worded for the page's reader; empty while its catalog is on its way. */
    public interface Words {
        @NonNull String word(@NonNull Microcopy copy);
    }

    private final @NonNull Words words;
    private final @NonNull TextStyle style;
    private final TextSheet sheet = new TextSheet(1);
    private final TextBatch text = new TextBatch(this.sheet);
    private TextLabel[] labels = new TextLabel[0];
    private int used;
    private boolean pending;

    public TrafficLabels(@NonNull Words words, @NonNull TextStyle style) {
        this.words = Objects.requireNonNull(words, "words cannot be null");
        this.style = Objects.requireNonNull(style, "style cannot be null");
    }

    /** @return the layer the labels draw in, for the picture's batches */
    public @NonNull TextBatch batch() {
        return this.text;
    }

    public @NonNull TextStyle style() {
        return this.style;
    }

    public void attach(@NonNull Surface surface) {
        this.sheet.attach(surface);
    }

    public void detach() {
        this.sheet.detach();
    }

    /** @return whether a label made for a frame waits to be painted, so the picture draws again */
    public boolean painting() {
        return this.sheet.pending() > 0;
    }

    /** Starts a frame's labels from the first of the pool. */
    public void begin() {
        this.used = 0;
        this.pending = false;
    }

    /** @return whether a word of this frame came back empty, its catalog on its way */
    public boolean pending() {
        return this.pending;
    }

    /** @return the copy in the reader's words, empty while its catalog is on its way */
    public @NonNull String word(@NonNull Microcopy copy) {
        return this.words.word(copy);
    }

    public void clip(float x, float y, float width, float height) {
        this.text.clip(x, y, Math.max(0f, width), height);
    }

    public void unclipped() {
        this.text.unclipped();
    }

    /** Draws a label of the pool, made the first time it is needed and kept for every frame after. */
    public void label(@NonNull Microcopy copy, float x, float baseline, @NonNull TextAnchor anchor, int rgba,
                      float size) {
        String said = this.words.word(copy);
        if (said.isEmpty()) {
            this.pending = true;
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
        label.set(said);
        this.text.text(label, x, baseline, anchor, rgba);
    }

    /**
     * A row's name, and its line under it when it has one, cut at {@code room} so they never reach what stands
     * beside them.
     */
    public void row(@NonNull Microcopy name, @Nullable Microcopy sub, float x, float y, float row, float room,
                    int ink, int muted) {
        this.clip(x, y, room, row);
        if (sub == null) {
            this.label(name, x, y + row / 2 + this.style.sizePx() * 0.35f, TextAnchor.LEFT, ink, this.style.sizePx());
        } else {
            this.label(name, x, y + row * 0.45f, TextAnchor.LEFT, ink, this.style.sizePx());
            this.label(sub, x, y + row * 0.8f, TextAnchor.LEFT, muted, this.style.sizePx() * 0.8f);
        }
        this.unclipped();
    }
}
