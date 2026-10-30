package be.elevenways.hohenheim.dns;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;

import java.util.List;

/**
 * The peer API response to a mirror request: which secondary zones the receiver created and removed, and the zones it
 * left alone because it holds them itself or replicates them from someone else.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
@HawkeyeClass
public record DnsPeerMirrorResponse(String status, List<String> created, List<String> removed,
                                    List<String> conflicts) {
}
