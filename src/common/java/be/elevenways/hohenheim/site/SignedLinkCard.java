package be.elevenways.hohenheim.site;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.zenit.cms.common.render.action.PageFormState;
import be.elevenways.zenit.common.security.ShownPerson;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A signed-link path's tab: its secrets by their last characters, the way a link is made, and the form that makes
 * one; a new secret and a made link shown once to the viewer they were made for.
 *
 * @param newSecret the secret made for this viewer and not shown yet; empty for none
 * @param secrets   the secrets in force, the current one first
 * @param replace   the replace form, null when the viewer may not replace
 * @param howTo     the exact explanation of a link, with a worked example signed with the current secret
 * @param make      the make form, null when the viewer may not make a link
 * @param madeLink  the link made for this viewer and not shown yet; empty for none
 * @param linkUntil when that link stops working, null without one
 * @author Jelle De Loecker
 * @since  0.1.0
 */
@HawkeyeClass
public record SignedLinkCard(@NonNull String newSecret, @NonNull List<Key> secrets, @Nullable PageFormState replace,
                             @NonNull String howTo, @Nullable PageFormState make, @NonNull String madeLink,
                             @Nullable Instant linkUntil) {

    public SignedLinkCard {
        Objects.requireNonNull(newSecret, "newSecret cannot be null");
        secrets = List.copyOf(secrets);
        Objects.requireNonNull(howTo, "howTo cannot be null");
        Objects.requireNonNull(madeLink, "madeLink cannot be null");
    }

    /**
     * One secret in force, never its value.
     *
     * @param endsIn its last four characters
     * @param madeAt when it was made, null when unknown
     * @param madeBy who made it, as the viewer is shown people
     * @param until  when it stops working, null for the current one
     * @param end    the form that ends it now, null for the current one or a viewer who may not
     */
    @HawkeyeClass
    public record Key(@NonNull String endsIn, @Nullable Instant madeAt, @NonNull ShownPerson madeBy,
                      @Nullable Instant until, @Nullable PageFormState end) {

        public Key {
            Objects.requireNonNull(endsIn, "endsIn cannot be null");
            Objects.requireNonNull(madeBy, "madeBy cannot be null");
        }
    }
}
