package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.server.docker.ServerService;
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
        (serverName, handle) -> IncusSpice.options(new ServerService().incusClientFor(serverName), handle));

    /**
     * Called on the screen session's start thread, once per session.
     *
     * @throws IOException when the host refuses the console
     */
    SessionOptions.@NonNull Builder connect(@NonNull String serverName, @NonNull String handle) throws IOException;
}
