package be.elevenways.hohenheim.instance;

import be.elevenways.protoblast.common.i18n.Microcopy;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * THE vocabulary of a stored install-media fetch's {@code state}, with whether it is still in flight and
 * how the Install media tab draws it as facts on the member.
 *
 * AIDEV-NOTE: the tokens are STORED in {@code install_media_fetches.state}, so they never change. A
 * reader fails closed: {@link #of} maps a null or unknown token to {@link #FAILED}, which is neither
 * active (the tab never polls for it forever) nor ready (it never claims a medium exists).
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public enum InstallMediaFetchState {

    /** Accepted by the request, not yet picked up by its background job. */
    PENDING("pending", true, "default",
        Microcopy.of("fetch_state_pending").withFilter("scope", "server_media")),

    /** The ISO is streaming onto the controller; the stored fraction moves when the size is known. */
    DOWNLOADING("downloading", true, "default",
        Microcopy.of("fetch_state_downloading").withFilter("scope", "server_media")),

    /** The download finished and the ISO is streaming into the host's managed pool. */
    IMPORTING("importing", true, "default",
        Microcopy.of("fetch_state_importing").withFilter("scope", "server_media")),

    /** The medium reads back on the host. */
    READY("ready", false, "success",
        Microcopy.of("fetch_state_ready").withFilter("scope", "server_media")),

    /** The fetch refused or failed; the stored reason says why. */
    FAILED("failed", false, "destructive",
        Microcopy.of("fetch_state_failed").withFilter("scope", "server_media")),

    /** The controller stopped while the fetch was in flight; nothing will finish it. */
    INTERRUPTED("interrupted", false, "warning",
        Microcopy.of("fetch_state_interrupted").withFilter("scope", "server_media"));

    private final String token;
    private final boolean active;
    private final String variant;
    private final Microcopy label;

    InstallMediaFetchState(@NonNull String token, boolean active, @NonNull String variant,
                           @NonNull Microcopy label) {
        this.token = token;
        this.active = active;
        this.variant = variant;
        this.label = label;
    }

    /** @return the stored column value */
    public @NonNull String token() {
        return this.token;
    }

    /** @return whether a job is (or should be) still working on the fetch */
    public boolean active() {
        return this.active;
    }

    /** @return the pl-badge variant the tab draws the state with */
    public @NonNull String variant() {
        return this.variant;
    }

    /** @return the state's name on the Install media tab */
    public @NonNull Microcopy label() {
        return this.label;
    }

    /** @return the member stored as {@code token}; null and unknown tokens read as {@link #FAILED} */
    public static @NonNull InstallMediaFetchState of(@Nullable Object token) {
        if (token != null) {
            String text = token.toString();
            for (InstallMediaFetchState state : values()) {
                if (state.token.equals(text)) {
                    return state;
                }
            }
        }
        return FAILED;
    }
}
