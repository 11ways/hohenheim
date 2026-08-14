package be.elevenways.hohenheim.instance;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * A DECLARED precondition that will refuse this instance's next deploy, stated on the
 * record's own page instead of only in the toast that follows the click.
 *
 * {@code blocked} false is the ordinary state, not an absence: an instance whose host is
 * fine carries this view with nothing to say.
 *
 * @param reason       the already-resolved refusal sentence, blank when not blocked
 * @param hostLinkable whether the surface may point at the host that must be fixed --
 *                     false on the delegated panel, where the host is operator inventory
 */
@HawkeyeClass
public record InstanceBlockerView(
    boolean blocked,
    @NonNull String reason,
    boolean hostLinkable
) {

    public static final InstanceBlockerView CLEAR = new InstanceBlockerView(false, "", false);
}
