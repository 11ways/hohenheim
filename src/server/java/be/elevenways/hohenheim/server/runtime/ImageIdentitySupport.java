package be.elevenways.hohenheim.server.runtime;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;

/**
 * The half of the driver seam that tells which image a created workload actually runs.
 *
 * AIDEV-NOTE: both snapshot halves extend it (the backup manifest records the identity), and the deploy pins it on
 * the record right after create (InstanceService.pinResolvedImage) from THIS interface, so a driver that can say what
 * it runs without snapshotting anything (a test daemon) pins it too.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public interface ImageIdentitySupport {

    /**
     * The image identity the workload actually runs.
     *
     * @throws IOException when the workload is absent or the daemon unreachable
     */
    @NonNull ImageIdentity imageIdentity(@NonNull InstanceSpec spec) throws IOException;
}
