package be.elevenways.hohenheim;

import be.elevenways.hawkeye.common.annotation.Arg;
import be.elevenways.hawkeye.common.annotation.HawkeyeFunction;
import be.elevenways.hawkeye.common.customelement.CustomElement;
import be.elevenways.hawkeye.common.lambda.LambdaReference1;
import be.elevenways.hawkeye.common.render.RenderContext;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.channel.ChannelLinks;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Template bridge to the deploy-log channel (namespace {@code DeployLog}) and the one home of its frame encoding: the
 * server writes frames through {@link #frame}, the browser folds them through {@link #buildText}/{@link #stepText}.
 *
 * AIDEV-NOTE: a SNAPSHOT frame replaces what the element holds and any other frame appends to it. The server opens
 * every link with snapshots (the text so far), and a link re-opened after a lost gateway opens with them again, so a
 * reconnect can never double a line.
 *
 * @author Jelle De Loecker
 * @since 0.10.0
 */
public final class HohenheimDeployFunctions {

    /** Wire key of the frame's {@link Source} token. */
    public static final String SOURCE_KEY = "source";

    /** Wire key of the frame's text. */
    public static final String TEXT_KEY = "text";

    /** Wire key of the frame's replace-or-append flag. */
    public static final String SNAPSHOT_KEY = "snapshot";

    private HohenheimDeployFunctions() {
    }

    /** Which of a deploy's two logs a frame belongs to. */
    public enum Source {

        /** The release engine's step log: operator diagnostics, never shown on the delegated panel. */
        STEP("step"),

        /** The build's own output: the tenant's checkout and build, which they need to fix a build. */
        BUILD("build");

        private final @NonNull String token;

        Source(@NonNull String token) {
            this.token = token;
        }

        public @NonNull String token() {
            return this.token;
        }
    }

    /** @return one frame as the channel carries it */
    public static @NonNull Map<String, Object> frame(@NonNull Source source, @NonNull String text, boolean snapshot) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put(SOURCE_KEY, source.token());
        frame.put(TEXT_KEY, text);
        frame.put(SNAPSHOT_KEY, snapshot);
        return frame;
    }

    /**
     * Opens the deploy-log link for an instance and routes every arriving frame to {@code onFrame}, for as long as
     * {@code owner} is connected (the {@code InstanceStats.connect} shape).
     */
    @HawkeyeFunction(
        name = "connect",
        namespace = "DeployLog",
        description = "Open the live deploy log channel for an instance",
        returnType = Void.class,
        returnsReference = false,
        arguments = {
            @Arg(name = "owner", required = true, type = CustomElement.class, expectsReference = false,
                 description = "The element that owns the link"),
            @Arg(name = "instanceId", required = true, type = Integer.class, expectsReference = false,
                 description = "The instance whose deploy to follow"),
            @Arg(name = "onFrame", required = true, type = LambdaReference1.class, expectsReference = false,
                 description = "Called with every frame map that arrives")
        }
    )
    public static void connect(RenderContext context,
                               @Nullable CustomElement owner,
                               @Nullable Integer instanceId,
                               @Nullable LambdaReference1<Object, ?> onFrame) {

        if (!Blast.IS_TEAVM || owner == null || instanceId == null || onFrame == null) {
            return;
        }
        ChannelLinks.openWhileConnected(owner, HohenheimChannels.DEPLOY_LOG, instanceId,
                frame -> onFrame.invoke(context, frame));
    }

    /** @return the build log with one frame folded in */
    @HawkeyeFunction(
        name = "buildText",
        namespace = "DeployLog",
        description = "The build log with one arrived frame folded in",
        returnType = String.class,
        returnsReference = false,
        arguments = {
            @Arg(name = "current", required = false, type = String.class, expectsReference = false,
                 description = "The build log held so far"),
            @Arg(name = "frame", required = true, type = Map.class, expectsReference = false,
                 description = "The arrived frame map")
        }
    )
    public static @NonNull String buildText(@Nullable String current, @Nullable Object frame) {
        return folded(current, frame, Source.BUILD);
    }

    /** @return the step log with one frame folded in */
    @HawkeyeFunction(
        name = "stepText",
        namespace = "DeployLog",
        description = "The release step log with one arrived frame folded in",
        returnType = String.class,
        returnsReference = false,
        arguments = {
            @Arg(name = "current", required = false, type = String.class, expectsReference = false,
                 description = "The step log held so far"),
            @Arg(name = "frame", required = true, type = Map.class, expectsReference = false,
                 description = "The arrived frame map")
        }
    )
    public static @NonNull String stepText(@Nullable String current, @Nullable Object frame) {
        return folded(current, frame, Source.STEP);
    }

    /** @return {@code current} with the frame applied when it belongs to {@code source}, else unchanged */
    static @NonNull String folded(@Nullable String current, @Nullable Object frame, @NonNull Source source) {
        String held = current == null ? "" : current;
        if (!(frame instanceof Map<?, ?> map) || !source.token().equals(map.get(SOURCE_KEY))
                || !(map.get(TEXT_KEY) instanceof String text)) {
            return held;
        }
        return Boolean.TRUE.equals(map.get(SNAPSHOT_KEY)) ? text : held + text;
    }
}
