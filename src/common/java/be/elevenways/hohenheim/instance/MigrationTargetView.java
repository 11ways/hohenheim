package be.elevenways.hohenheim.instance;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * One candidate destination host on the instance migrate page.
 *
 * {@code measured} false is an EXPLICIT state, exactly like the host overview's capacity
 * card: a host whose memory was never read shows "not measured" rather than a zero bar
 * that reads as an empty machine.
 *
 * @param refusal the resolved reason this host is not eligible, blank when it is
 */
@HawkeyeClass
public record MigrationTargetView(
    int serverId,
    @NonNull String name,
    boolean eligible,
    @NonNull String refusal,
    boolean measured,
    int bookedMb,
    int bookableMb
) {
}
