package be.elevenways.hohenheim.server.backup;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * The backup-target seam: where an instance export lands and how it comes back.
 * Implementations stream with bounded buffers (never a whole-archive byte[]) and
 * commit atomically -- {@link #store} writes a staging name and renames only after
 * every byte landed, so an interrupted upload can never leave an artifact a later
 * restore would accept.
 *
 * Whether a target is in a DIFFERENT failure domain from the instance host is a
 * deployment fact: a filesystem target on the controller host is NOT off-host, and
 * nothing in this seam can make it so -- the kind's admin help says it out loud.
 */
public interface BackupTarget {

    /**
     * Verify the target is reachable and writable RIGHT NOW (creates the base
     * location when absent). Run before every upload -- a backup that fails at
     * store time already spent the capture downtime.
     *
     * @throws IOException naming what is wrong
     */
    void healthCheck() throws IOException;

    /**
     * Upload a local file under {@code key}, staging-then-commit: the bytes travel
     * to a non-committed staging name, and the committed key appears only via an
     * atomic rename after the full payload landed.
     *
     * @throws IOException on any failure; implementations remove their staging
     *                     debris best-effort before rethrowing
     */
    void store(@NonNull String key, @NonNull Path file) throws IOException;

    /**
     * The sha256 (lowercase hex) of the COMMITTED artifact as the target itself
     * holds it -- read back from target-side state, never echoed from memory, so a
     * green verification means the remote bytes are restorable.
     *
     * @throws IOException when the key does not exist or cannot be read
     */
    @NonNull String storedSha256(@NonNull String key) throws IOException;

    /** Download the committed artifact to a local file. */
    void retrieve(@NonNull String key, @NonNull Path destination) throws IOException;

    /** Whether a committed artifact exists under {@code key}. */
    boolean exists(@NonNull String key) throws IOException;

    /**
     * Every COMMITTED key under a prefix, staging debris excluded.
     *
     * Retention lives here rather than in a local ledger on purpose: a control-plane backup
     * exists for the case where this host's disk is gone, so a table listing what was uploaded
     * is exactly the wrong authority over what the target actually holds.
     *
     * @return the keys, in no defined order; empty when the prefix holds nothing
     */
    @NonNull List<String> list(@NonNull String prefix) throws IOException;

    /** Remove the committed artifact AND any staging debris for {@code key}; absent is success. */
    void delete(@NonNull String key) throws IOException;
}
