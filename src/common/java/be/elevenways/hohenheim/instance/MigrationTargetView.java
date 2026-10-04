package be.elevenways.hohenheim.instance;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.zenit.cms.common.render.action.PageFormState;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * One candidate destination host on the instance migrate page.
 *
 * {@code measured} false is an EXPLICIT state, exactly like the host overview's capacity
 * card: a host whose memory was never read shows "not measured" rather than a zero bar
 * that reads as an empty machine.
 *
 * @param refusal the resolved reason this host is not eligible, blank when it is
 * @param form    the migrate operation's form preset with this host, null when the host cannot take the move
 */
@HawkeyeClass
public record MigrationTargetView(
    int serverId,
    @NonNull String name,
    boolean eligible,
    @NonNull String refusal,
    boolean measured,
    int bookedMb,
    int bookableMb,
    @Nullable PageFormState form
) {
}
