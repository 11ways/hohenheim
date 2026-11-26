package be.elevenways.hohenheim.traffic;

import be.elevenways.domino.common.DominoElement;
import be.elevenways.hawkeye.common.annotation.Arg;
import be.elevenways.hawkeye.common.annotation.HawkeyeFunction;
import be.elevenways.hawkeye.common.customelement.CustomElement;
import be.elevenways.hawkeye.common.lambda.LambdaReference1;
import be.elevenways.hawkeye.common.render.RenderContext;
import be.elevenways.hohenheim.HohenheimChannels;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.key.IdentityKey;
import be.elevenways.zenit.common.channel.ClientChannelLink;
import be.elevenways.zenit.common.channel.ChannelLinks;
import be.elevenways.zenit.traffic.common.LiveHits;
import be.elevenways.zenit.traffic.common.StatusClass;
import be.elevenways.zenit3d.common.shape.ThemeColors;
import be.elevenways.zenit3d.common.surface.SurfaceOptions;
import be.elevenways.zenit3d.common.surface.Surfaces;
import be.elevenways.zenit3d.common.text.TextStyle;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Template bridge of the live Activity picture (namespace {@code TrafficActivity}): opens the live traffic link the
 * view asks for, mounts the picture's driver on its surface and hands the page the last minute after every draw that
 * shows a new one, so the page's words and the picture are one count.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class ActivityFunctions {

    /** The driver an Activity element draws with, for its pause. */
    private static final IdentityKey<ActivityDriver> DRIVER = IdentityKey.create("hohenheim.traffic.activity");

    /** The surface's property holding how many dots flew at the last draw, as the host's frames3d counts frames. */
    public static final String FLYING_PROPERTY = "flyingDots3d";

    /** The custom property the surface's height reads in its place: the height its rows fit (ActivityDriver). */
    public static final String FIT_PROPERTY = "--hh-activity-fit";

    private ActivityFunctions() {
    }

    /**
     * Opens the link and mounts the picture for as long as {@code owner} is connected; {@code onSummary} hears the
     * last minute after each draw that shows a new one, and once more with {@code ended} when the server ended the
     * link.
     */
    @HawkeyeFunction(
        name = "watch",
        namespace = "TrafficActivity",
        description = "Open the live traffic picture the view asks for, for as long as the owner is connected",
        returnType = Void.class,
        returnsReference = false,
        arguments = {
            @Arg(name = "owner", required = true, type = CustomElement.class, expectsReference = false,
                 description = "The element that owns the link and the picture"),
            @Arg(name = "surface", required = true, type = DominoElement.class, expectsReference = false,
                 description = "The z3d-surface the picture draws on, styled with the picture's font"),
            @Arg(name = "view", required = true, type = ActivityView.class, expectsReference = false,
                 description = "What the picture asks for and the names of the sites it may show"),
            @Arg(name = "onSummary", required = true, type = LambdaReference1.class, expectsReference = false,
                 description = "Called with the last minute (an ActivitySummary) after every draw that shows a new one")
        }
    )
    public static void watch(RenderContext context, @Nullable CustomElement owner, @Nullable DominoElement surface,
                             @Nullable ActivityView view, @Nullable LambdaReference1<Object, ?> onSummary) {
        if (!Blast.IS_TEAVM || owner == null || surface == null || view == null || onSummary == null) {
            return;
        }
        ActivityModel model = new ActivityModel(view.visitors(), view.unserved(), view.order());
        Watch watch = new Watch(context, model, surface, onSummary);
        ActivityDriver driver = new ActivityDriver(model, words(context), colours(surface), TextStyle.of(surface),
            view.names(), view.strip(), watch);
        owner.setAttachment(DRIVER, driver);
        Surfaces.mount(surface, driver, SurfaceOptions.TRANSPARENT);
        ClientChannelLink<Object, LiveHits> link = ChannelLinks.openWhileConnected(owner,
            HohenheimChannels.TRAFFIC_LIVE, view.open(), driver::offer);
        if (link != null) {
            // Refused at its open or cut later (a revoked TV link): nothing more arrives, and the page says so.
            link.onClose(refusal -> {
                if (refusal != null) {
                    watch.end();
                }
            });
            link.ready().whenComplete((ready, refused) -> {
                if (refused != null) {
                    watch.end();
                }
            });
        }
    }

    /** What one picture tells its page: the minute it drew, the dots flying, and that its link ended. */
    private static final class Watch implements ActivityDriver.Shown {

        private final @NonNull RenderContext context;
        private final @NonNull ActivityModel model;
        private final @NonNull DominoElement surface;
        private final @NonNull LambdaReference1<Object, ?> onSummary;
        private @Nullable String picture;
        private boolean ended;

        Watch(@NonNull RenderContext context, @NonNull ActivityModel model, @NonNull DominoElement surface,
              @NonNull LambdaReference1<Object, ?> onSummary) {
            this.context = context;
            this.model = model;
            this.surface = surface;
            this.onSummary = onSummary;
        }

        @Override
        public void minute(@NonNull String picture) {
            this.picture = picture;
            if (!this.ended) {
                this.onSummary.invoke(this.context, ActivitySummary.of(this.model, picture, false));
            }
        }

        @Override
        public void flying(int dots) {
            this.surface.setPropertyData(FLYING_PROPERTY, dots);
        }

        @Override
        public void fits(float heightPx) {
            this.surface.getStyle().setProperty(FIT_PROPERTY, Math.round(heightPx) + "px");
        }

        void end() {
            if (!this.ended) {
                this.ended = true;
                this.onSummary.invoke(this.context, ActivitySummary.of(this.model, this.picture, true));
            }
        }
    }

    /** Freezes the owner's picture as it stands, or lets it move again. */
    @HawkeyeFunction(
        name = "pause",
        namespace = "TrafficActivity",
        description = "Freeze the live traffic picture as it stands, or let it move again",
        returnType = Void.class,
        returnsReference = false,
        arguments = {
            @Arg(name = "owner", required = true, type = CustomElement.class, expectsReference = false,
                 description = "The element that watches"),
            @Arg(name = "paused", required = true, type = Boolean.class, expectsReference = false,
                 description = "True to freeze the picture, false to let it move")
        }
    )
    public static void pause(@Nullable CustomElement owner, @Nullable Boolean paused) {
        ActivityDriver driver = owner == null ? null : owner.getAttachment(DRIVER);
        if (driver != null) {
            driver.pause(Boolean.TRUE.equals(paused));
        }
    }

    /** @return the picture's words, each resolved for the page's reader when drawn */
    private static ActivityDriver.@NonNull Words words(@NonNull RenderContext context) {
        return copy -> copy.resolve(context);
    }

    /** @return the theme's colours as they stand on the surface now */
    private static ActivityDriver.@NonNull Colours colours(@NonNull DominoElement surface) {
        List<Integer> classes = new ArrayList<>();
        for (StatusClass statusClass : StatusClass.values()) {
            classes.add(ThemeColors.resolve(surface, ActivityPalette.colour(statusClass)));
        }
        return new ActivityDriver.Colours(ThemeColors.resolve(surface, "var(--pl-color-surface-raised)"),
            ThemeColors.resolve(surface, "var(--pl-color-border)"),
            ThemeColors.resolve(surface, "var(--pl-color-border-muted)"),
            ThemeColors.resolve(surface, "var(--pl-color-text)"),
            ThemeColors.resolve(surface, "var(--pl-color-text-muted)"), classes);
    }
}
