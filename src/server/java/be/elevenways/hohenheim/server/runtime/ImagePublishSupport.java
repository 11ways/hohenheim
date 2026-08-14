package be.elevenways.hohenheim.server.runtime;

import java.io.IOException;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Capability: publish a STOPPED workload's current state as an image in the daemon's
 * own store under an alias -- the capture half of the prepared-template lifecycle
 * (install once, capture, clone many). A runtime without it makes template capture
 * refuse BY NAME. The published image lives on ONE daemon: serving other hosts means
 * exporting and importing it under the same alias, which stays an operator act.
 */
public interface ImagePublishSupport {

    /**
     * Publish the workload's current state as an image under {@code alias},
     * read-back verified.
     *
     * @return the published image's fingerprint
     * @throws IOException when the workload is not publishable (running, absent) or
     *                     the daemon refuses
     */
    @NonNull String publishImage(@NonNull InstanceSpec spec, @NonNull String alias,
                                 @Nullable String description) throws IOException;
}
