package be.elevenways.hohenheim.traffic;

import be.elevenways.domino.common.DominoElement;
import be.elevenways.hawkeye.common.annotation.Arg;
import be.elevenways.hawkeye.common.annotation.HawkeyeFunction;
import be.elevenways.hawkeye.common.customelement.CustomElement;
import be.elevenways.hawkeye.common.lambda.LambdaReference1;
import be.elevenways.hawkeye.common.render.RenderContext;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.key.IdentityKey;
import be.elevenways.zenit.common.ui.ColorHue;
import be.elevenways.zenit3d.common.shape.ThemeColors;
import be.elevenways.zenit3d.common.surface.SurfaceOptions;
import be.elevenways.zenit3d.common.surface.Surfaces;
import be.elevenways.zenit3d.common.text.TextStyle;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Template bridge of the live "Apps sending" picture (namespace {@code AppsSending}): mounts the picture's driver on
 * its surface with the reading the page was rendered with, hands it each reading the page's live part re-reads, and
 * tells the page the picture in words after the draw that shows a reading.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class AppsSendingFunctions {

    /** The driver an element draws with, for the readings that follow. */
    private static final IdentityKey<AppsSendingDriver> DRIVER = IdentityKey.create("hohenheim.traffic.apps_sending");

    /** The surface's property holding how many marks travelled at the last draw, as the host's frames3d counts. */
    public static final String TRAVELLING_PROPERTY = "travellingMarks3d";

    /** The custom property the surface's height reads in its place: the height its rows fit. */
    public static final String FIT_PROPERTY = "--hh-apps-sending-fit";

    /** The custom property each categorical hue's series colour stands in, on the element, by its token. */
    public static final String HUE_PROPERTY = "--hh-apps-sending-";

    private AppsSendingFunctions() {
    }

    /** Mounts the picture on the surface for as long as {@code owner} is connected, drawing {@code view} first. */
    @HawkeyeFunction(
        name = "watch",
        namespace = "AppsSending",
        description = "Draw the live picture of where apps send data, starting from the reading given",
        returnType = Void.class,
        returnsReference = false,
        arguments = {
            @Arg(name = "owner", required = true, type = CustomElement.class, expectsReference = false,
                 description = "The element that owns the picture"),
            @Arg(name = "surface", required = true, type = DominoElement.class, expectsReference = false,
                 description = "The z3d-surface the picture draws on, styled with the picture's font"),
            @Arg(name = "view", required = true, type = AppsSendingView.class, expectsReference = false,
                 description = "The reading the page was rendered with"),
            @Arg(name = "onTold", required = true, type = LambdaReference1.class, expectsReference = false,
                 description = "Called with the picture in words (an AppsSendingTold) after each reading's draw")
        }
    )
    public static void watch(RenderContext context, @Nullable CustomElement owner, @Nullable DominoElement surface,
                             @Nullable AppsSendingView view, @Nullable LambdaReference1<Object, ?> onTold) {
        if (!Blast.IS_TEAVM || owner == null || surface == null || view == null || onTold == null) {
            return;
        }
        AppsSendingDriver driver = new AppsSendingDriver(ActivityFunctions.words(context), colours(surface),
            TextStyle.of(surface), new AppsSendingDriver.Shown() {
                @Override
                public void reading(@NonNull String picture, @NonNull String spoken) {
                    onTold.invoke(context, new AppsSendingTold(picture, Microcopy.literal(spoken)));
                }

                @Override
                public void travelling(int marks) {
                    surface.setPropertyData(TRAVELLING_PROPERTY, marks);
                }

                @Override
                public void fits(float heightPx) {
                    surface.getStyle().setProperty(FIT_PROPERTY, Math.round(heightPx) + "px");
                }
            });
        driver.offer(view);
        owner.setAttachment(DRIVER, driver);
        Surfaces.mount(surface, driver, SurfaceOptions.TRANSPARENT);
    }

    /** Hands the owner's picture a reading its live part re-read; the bands swell to it. */
    @HawkeyeFunction(
        name = "offer",
        namespace = "AppsSending",
        description = "Hand the live picture of where apps send data a new reading",
        returnType = Void.class,
        returnsReference = false,
        arguments = {
            @Arg(name = "owner", required = true, type = CustomElement.class, expectsReference = false,
                 description = "The element that owns the picture"),
            @Arg(name = "view", required = true, type = AppsSendingView.class, expectsReference = false,
                 description = "The reading to draw")
        }
    )
    public static void offer(@Nullable CustomElement owner, @Nullable AppsSendingView view) {
        AppsSendingDriver driver = owner == null ? null : owner.getAttachment(DRIVER);
        if (driver != null && view != null) {
            driver.offer(view);
        }
    }

    /** @return the theme's colours as they stand on the surface now, every rotating hue's series colour among them */
    private static AppsSendingDriver.@NonNull Colours colours(@NonNull DominoElement surface) {
        Map<String, Integer> hues = new HashMap<>();
        for (ColorHue hue : ColorHue.values()) {
            if (hue.rotates()) {
                hues.put(hue.token(), ThemeColors.resolve(surface, "var(" + HUE_PROPERTY + hue.token() + ")"));
            }
        }
        return new AppsSendingDriver.Colours(PictureInk.of(surface), hues);
    }
}
