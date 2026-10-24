package be.elevenways.hohenheim.server.application;

import be.elevenways.protoblast.common.i18n.Microcopy;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The caller's own last word on a deploy it queued: asked under the application's operation lock, once every other
 * check passed, right before the deploy starts.
 *
 * AIDEV-NOTE: a deploy accepted now may start much later, queued behind the running operation; whatever made the
 * caller entitled to it (a build hook being live under the token it was accepted with) is asked again HERE, and a
 * decline is recorded exactly like a DeployStartPolicy decline. {@link #settled()} may be called more than once and
 * must act once: a gate may settle itself when asked (a build hook frees its pending slot at start, not at the end),
 * the background deploy settles it when its work ends, and the hand-off settles it when the deploy was refused or
 * never handed off.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public interface DeployStartGate {

    /** The gate of a deploy whose caller has nothing left to ask. */
    DeployStartGate OPEN = () -> null;

    /** @return why the deploy must not start after all, or null when it starts now */
    @Nullable Microcopy declineToStart();

    /** Called at the latest when the deploy ended, started or not, and possibly more than once; must act once. */
    default void settled() {
    }
}
