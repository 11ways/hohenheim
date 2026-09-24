package be.elevenways.hohenheim.instance;

import be.elevenways.hawkeye.common.annotation.Arg;
import be.elevenways.hawkeye.common.annotation.HawkeyeFunction;
import be.elevenways.hawkeye.common.render.RenderContext;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.registry.Identifier;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;

/**
 * The Install media tab's live lane (namespace {@code InstallMediaLive}): the live feed its region watches
 * and the re-read that region runs when the feed goes stale.
 *
 * AIDEV-NOTE: this replaced a timer element that re-navigated to the tab every 4 s while a fetch ran. Each
 * of those was a full soft navigation: it PUSHED a history entry every tick (Back walked through a
 * minute of identical tab entries), scrolled to the top, and re-rendered the forms, dropping a half-typed
 * URL and the progress line of an upload in flight. The feed is zenit's live lane (a data-free ping per
 * committed write of the fetch model), and the re-read replaces only the region's own view, gated and
 * authorized by the viewer's own session like the page render was.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class InstallMediaLive {

    /** THE feed id; the server registers it over the fetch model, gated by the tab's own permission. */
    public static final @NonNull Identifier FEED = Identifier.of("hohenheim", "install_media_fetches");

    private InstallMediaLive() {
    }

    /** @return the feed the tab's live region watches */
    @HawkeyeFunction(
        name = "feed",
        namespace = "InstallMediaLive",
        description = "The live feed of the install-media fetches",
        returnType = Identifier.class,
        returnsReference = false,
        arguments = {}
    )
    public static @NonNull Identifier feed() {
        return FEED;
    }

    /**
     * The host's install-media view read afresh over {@link HohenheimEndpoints#SERVERS_MEDIA_VIEW}, with the
     * viewer's own session.
     *
     * @return the fresh view, or null on the server and whenever the read fails (the region then keeps what
     *         it shows until the next change)
     */
    @HawkeyeFunction(
        name = "read",
        namespace = "InstallMediaLive",
        description = "The install-media view of a host, read afresh",
        returnType = InstallMediaView.class,
        returnsReference = false,
        arguments = @Arg(name = "serverId", required = true, type = Integer.class, expectsReference = false,
                         description = "The Incus host whose media and fetches to read")
    )
    public static @Nullable InstallMediaView read(RenderContext context, @Nullable Integer serverId) {
        if (!Blast.IS_TEAVM || serverId == null) {
            return null;
        }
        try {
            return HohenheimEndpoints.SERVERS_MEDIA_VIEW.call(null, Map.of(HohenheimEndpoints.SERVER_ID, serverId));
        } catch (RuntimeException refused) {
            Blast.log("MEDIA: live re-read of host", serverId, "failed -", refused.getMessage());
            return null;
        }
    }
}
