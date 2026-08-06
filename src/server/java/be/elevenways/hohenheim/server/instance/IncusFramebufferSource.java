package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.incus.IncusClient;
import be.elevenways.hohenheim.server.incus.SpiceConsole;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;

/**
 * Production {@link FramebufferSource}: VGA PNG snapshots from {@link IncusClient} and a
 * live {@link SpiceConsole} for input, both over the enrolled server's pinned Incus lane.
 */
public final class IncusFramebufferSource implements FramebufferSource {

    private final @NonNull IncusClient incus;
    private final @NonNull String handle;
    private final @NonNull SpiceConsole spice;

    private IncusFramebufferSource(@NonNull IncusClient incus, @NonNull String handle,
                                   @NonNull SpiceConsole spice) {
        this.incus = incus;
        this.handle = handle;
        this.spice = spice;
    }

    /** The production factory: resolve the server's Incus client and link the SPICE input. */
    public static @NonNull FramebufferSource open(@NonNull Integer instanceId,
                                                  @NonNull String serverName,
                                                  @NonNull String handle) throws IOException {
        IncusClient incus = new ServerService().incusClientFor(serverName);
        SpiceConsole spice = SpiceConsole.open(incus, handle);
        return new IncusFramebufferSource(incus, handle, spice);
    }

    @Override
    public byte @NonNull [] snapshot() throws IOException {
        return this.incus.vgaScreenshot(this.handle);
    }

    @Override
    public void key(@NonNull String code, boolean down) throws IOException {
        this.spice.key(code, down);
    }

    @Override
    public void mousePosition(int x, int y, int buttonsMask) throws IOException {
        this.spice.mousePosition(x, y, buttonsMask);
    }

    @Override
    public void mouseButton(int button, boolean down, int buttonsMask) throws IOException {
        this.spice.mouseButton(button, down, buttonsMask);
    }

    @Override
    public void close() {
        this.spice.close();
    }
}
