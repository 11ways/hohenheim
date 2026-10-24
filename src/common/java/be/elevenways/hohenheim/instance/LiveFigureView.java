package be.elevenways.hohenheim.instance;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;

/**
 * What a service's live figure element is drawn from, on its overview and as its list cell: the instance whose figure
 * link the element holds while it is shown, and the figure the hub held when the page rendered.
 *
 * @param instanceId the instance whose live figure link the element opens
 * @param seed       the newest figure the hub held, in its wire shape, or null when nobody was watching
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
@HawkeyeClass
public record LiveFigureView(int instanceId, @Nullable Map<String, Object> seed) {
}
