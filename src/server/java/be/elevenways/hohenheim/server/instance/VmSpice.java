package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.incus.IncusClient;
import be.elevenways.hohenheim.server.incus.IncusSpice;
import be.elevenways.pepperglass.session.SessionOptions;
import be.elevenways.protoblast.common.platform.PlatformSeam;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;

/**
 * How a virtual machine's SPICE server is reached: in production through its host's Incus daemon.
 *
 * @author Jelle De Loecker
 * @since 0.2.0
 */
@FunctionalInterface
public interface VmSpice {

    /** The seam a test points at a scripted SPICE server. */
    PlatformSeam<VmSpice> SEAM = PlatformSeam.withDefault(VmSpice.class,
        (serverName, handle) -> incus(new ServerService().incusClientFor(serverName), handle));

    /**
     * Opens the instance's VGA console on its Incus daemon, released by ending that console.
     *
     * @throws IOException when the daemon refuses the console
     */
    static @NonNull Link incus(@NonNull IncusClient incus, @NonNull String handle) throws IOException {
        IncusSpice.Console console = IncusSpice.open(incus, handle);
        return new Link(console.options(), console::close);
    }

    /**
     * Called on the screen session's start thread, once per session.
     *
     * @throws IOException when the host refuses the console
     */
    @NonNull Link connect(@NonNull String serverName, @NonNull String handle) throws IOException;

    /**
     * One way into a VM's SPICE server, held until the screen session ends.
     *
     * @param options how the SPICE session links
     * @param release ends what the host opened for it; called once, when the screen session ends
     */
    record Link(SessionOptions.@NonNull Builder options, @NonNull Runnable release) {

        /** @return a link to a SPICE server reached directly, with nothing to end on the host */
        public static @NonNull Link direct(SessionOptions.@NonNull Builder options) {
            return new Link(options, () -> {
            });
        }
    }
}
