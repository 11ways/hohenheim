package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.ports.StreamProtocol;
import be.elevenways.protoblast.common.i18n.Microcopy;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * A stream port the proxy could not open, in the operator's words, and how many reconciles in a row failed it.
 *
 * @param words    which port, who holds it and what to do ({@code ListenerBindFailures.explainStream})
 * @param attempts the failed binds of this same port and protocol in a row
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public record StreamBindFailure(int siteId, @NonNull String name, int port, @NonNull StreamProtocol protocol,
                                @NonNull Microcopy words, int attempts) {
}
