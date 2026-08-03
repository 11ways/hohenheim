package be.elevenways.hohenheim.server.runtime;

import java.io.IOException;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * THE driver seam of the instance tier: five single-shot operations, typed outcomes,
 * NO streaming. Streaming (stats, follow-logs, attach, TTY exec) is a SECOND transport
 * contract (Phase 6) -- it cannot be patched into the single-shot Docker transport and
 * must never be bolted onto this interface method by method.
 *
 * AIDEV-NOTE: this seam exists so the next driver (Incus: HTTPS + client certs, a
 * different shape entirely) is an implementation, not a rewrite. DockerClient must
 * never BE the seam; DockerInstanceRuntime wraps it.
 */
public interface InstanceRuntime {

    /**
     * Create the workload WITHOUT starting it. The spec's owner labels are stamped here,
     * at create -- onto the workload AND every named volume's creation options -- because
     * a crash between create and the port readback must still leave the resource
     * attributable, and a volume keeps the labels it was born with forever.
     *
     * @return the runtime handle ({@code spec.handle()} confirmed by the daemon)
     * @throws IOException when the daemon refuses, an unowned same-named resource exists,
     *                     or the daemon cannot be reached
     */
    @NonNull String create(@NonNull InstanceSpec spec) throws IOException;

    /** @throws IOException when the daemon did not confirm the start */
    void start(@NonNull String handle) throws IOException;

    /** @throws IOException when the daemon did not confirm the stop within the grace window */
    void stop(@NonNull String handle, int graceSeconds) throws IOException;

    /**
     * Remove the workload, VERIFIED: the daemon confirmed removal or answered "not found"
     * (observed absent). Named volumes are NOT removed -- a volume is the one
     * unrecoverable resource, and its removal stays an explicit operator decision.
     *
     * @throws IOException when the daemon is UNREACHABLE or refuses -- absent is success,
     *                     unreachable never is; a caller must not treat this as gone
     */
    void destroy(@NonNull String handle) throws IOException;

    /** Typed live status; never throws (see {@link ContainerState}). */
    @NonNull InstanceStatus status(@NonNull String handle);
}
