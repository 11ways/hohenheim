package be.elevenways.hohenheim.server.instance;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;

/**
 * The seam {@link VmFramebufferHandler} drives: one framebuffer snapshot down, input up.
 * Production is {@link IncusFramebufferSource} (VGA screenshots + a live {@link
 * be.elevenways.hohenheim.server.incus.SpiceConsole}); a test supplies a fake so the
 * grant/revocation contract can be proven over a real socket without a live daemon.
 */
public interface FramebufferSource extends AutoCloseable {

    /** Open a source for one instance handle (VM already verified running). */
    interface Factory {
        @NonNull FramebufferSource open(@NonNull Integer instanceId, @NonNull String serverName,
                                        @NonNull String handle) throws IOException;
    }

    /** One PNG snapshot of the current framebuffer. */
    byte @NonNull [] snapshot() throws IOException;

    /** Press or release a key named by its {@code KeyboardEvent.code}. */
    void key(@NonNull String code, boolean down) throws IOException;

    /** Absolute pointer position in surface coordinates. */
    void mousePosition(int x, int y, int buttonsMask) throws IOException;

    /** Mouse button press/release; {@code button} is the DOM button number. */
    void mouseButton(int button, boolean down, int buttonsMask) throws IOException;

    @Override
    void close();
}
