package be.elevenways.hohenheim.app;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * One protected path of an app (or the site-wide rule for everything else), as its overview's Protection card draws
 * it.
 *
 * @param url       the path's own record page, null when there is none to open
 * @param summary   who gets in, in words
 * @param open      the path names protection that admits everyone: drawn as "Open to everyone", never as protected
 * @param guarded   something stands in front of it; false is the plainly public row ("everything else")
 * @param changeUrl where this viewer changes which list guards it, drawn as a quiet "Change" beside the badge; null
 *                  offers none
 * @author Jelle De Loecker
 * @since  0.1.0
 */
@HawkeyeClass
public record AppProtection(@NonNull String path, @Nullable String url, @NonNull String summary, boolean open,
                            boolean guarded, @Nullable String changeUrl) {

    /** A row offering no change of its list. */
    public AppProtection(@NonNull String path, @Nullable String url, @NonNull String summary, boolean open,
                         boolean guarded) {
        this(path, url, summary, open, guarded, null);
    }

    /** @return this row offering "Change" at that address */
    public @NonNull AppProtection withChange(@Nullable String changeUrl) {
        return new AppProtection(this.path, this.url, this.summary, this.open, this.guarded, changeUrl);
    }
}
