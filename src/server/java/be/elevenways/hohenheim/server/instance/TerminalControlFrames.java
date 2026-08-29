package be.elevenways.hohenheim.server.instance;

import be.elevenways.protoblast.common.dry.Dry;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;

/**
 * THE reader of {@code pl-terminal}'s control frames on a keystroke socket: one home for
 * the resize frame, shared by the console and the shell handlers.
 *
 * AIDEV-NOTE: the discriminator is STRUCTURAL -- a JSON object carrying
 * {@code type:"resize"} plus numeric cols/rows -- and everything else is keystrokes,
 * verbatim. The ambiguity that leaves is deliberate and bounded: a viewer who pastes
 * exactly that JSON object resizes their OWN terminal instead of typing it. Nothing about
 * another session, another record or another identity is reachable this way, so a second
 * control channel would be cost without a safety gain.
 */
public final class TerminalControlFrames {

    /** The control frame {@code pl-terminal} sends when its geometry changes. */
    static final String RESIZE_TYPE = "resize";

    private TerminalControlFrames() {
    }

    /** {@code {"type":"resize","cols":N,"rows":N}} as [cols, rows], or null for keystrokes. */
    public static int @Nullable [] resizeOf(@NonNull String message) {
        // Cheap reject first: a keystroke stream must not pay for a parse attempt.
        if (message.length() < 2 || message.charAt(0) != '{'
                || !message.contains(RESIZE_TYPE)) {
            return null;
        }
        Object parsed;
        try {
            parsed = new Dry().parse(message);
        } catch (RuntimeException notJson) {
            return null;
        }
        if (!(parsed instanceof Map<?, ?> frame)
                || !RESIZE_TYPE.equals(frame.get("type"))
                || !(frame.get("cols") instanceof Number cols)
                || !(frame.get("rows") instanceof Number rows)) {
            return null;
        }
        return new int[] { cols.intValue(), rows.intValue() };
    }
}
