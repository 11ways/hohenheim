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
 * view asks for, mounts the picture's driver on its surface and hands the page the last minute after every frame.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class ActivityFunctions {

    /** The driver an Activity element draws with, for its pause. */
    private static final IdentityKey<ActivityDriver> DRIVER = IdentityKey.create("hohenheim.traffic.activity");

    private ActivityFunctions() {
    }

    /**
     * Opens the link and mounts the picture for as long as {@code owner} is connected; {@code onSummary} hears the
     * last minute after each frame, and once more with {@code ended} when the server ended the link.
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
                 description = "Called with the last minute (an ActivitySummary) after every frame")
        }
    )
    public static void watch(RenderContext context, @Nullable CustomElement owner, @Nullable DominoElement surface,
                             @Nullable ActivityView view, @Nullable LambdaReference1<Object, ?> onSummary) {
        if (!Blast.IS_TEAVM || owner == null || surface == null || view == null || onSummary == null) {
            return;
        }
        ActivityModel model = new ActivityModel(view.visitors(), view.unserved(), view.order());
        ActivityDriver driver = new ActivityDriver(model, words(context), colours(surface), TextStyle.of(surface),
            view.names(), view.strip());
        owner.setAttachment(DRIVER, driver);
        Surfaces.mount(surface, driver, SurfaceOptions.TRANSPARENT);
        ClientChannelLink<Object, LiveHits> link = ChannelLinks.openWhileConnected(owner,
            HohenheimChannels.TRAFFIC_LIVE, view.open(), frame -> {
                driver.offer(frame);
                onSummary.invoke(context, ActivitySummary.of(model, view.names(), false));
            });
        if (link != null) {
            // Refused at its open or cut later (a revoked TV link): nothing more arrives, and the page says so.
            link.onClose(refusal -> {
                if (refusal != null) {
                    onSummary.invoke(context, ActivitySummary.of(model, view.names(), true));
                }
            });
            link.ready().whenComplete((ready, refused) -> {
                if (refused != null) {
                    onSummary.invoke(context, ActivitySummary.of(model, view.names(), true));
                }
            });
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
        return (copy, count) -> (count == null ? copy : copy.withArg("count", count)).resolve(context);
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
