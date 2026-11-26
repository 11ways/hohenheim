package be.elevenways.hohenheim.traffic;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.zenit.cms.common.render.action.PageFormState;
import be.elevenways.zenit.common.security.ShownPerson;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The Activity tab's TV page card: the form that makes a TV link, the links made, and the addresses just made for
 * this viewer, shown once.
 *
 * @param make      the make form, null when the viewer may not make one
 * @param links     every live TV link, the newest first
 * @param disclosed the addresses made for this viewer and not shown yet, one per line; empty for none
 * @author Jelle De Loecker
 * @since  0.1.0
 */
@HawkeyeClass
public record TvLinksCard(@Nullable PageFormState make, @NonNull List<Link> links, @NonNull String disclosed) {

    public TvLinksCard {
        links = List.copyOf(links);
        Objects.requireNonNull(disclosed, "disclosed cannot be null");
    }

    /**
     * One TV link as the card lists it; never its address.
     *
     * @param code   its code (SL4)
     * @param shows  what it shows: every site, or the one site's name
     * @param madeBy who made it, as the viewer is shown people
     * @param madeAt when it was made, null when unknown
     * @param end    the form that ends it, null when the viewer may not
     */
    @HawkeyeClass
    public record Link(@NonNull String code, @NonNull String shows, @NonNull ShownPerson madeBy,
                       @Nullable Instant madeAt, @Nullable PageFormState end) {

        public Link {
            Objects.requireNonNull(code, "code cannot be null");
            Objects.requireNonNull(shows, "shows cannot be null");
            Objects.requireNonNull(madeBy, "madeBy cannot be null");
        }
    }
}
