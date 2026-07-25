package be.elevenways.hohenheim.server.proxy;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;

/** Chooses the backend for one accepted public connection, after any trusted PROXY v2 prologue. */
interface ConnectionRouter {

    byte[] NOTHING_CONSUMED = new byte[0];

    /** Every byte the router consumed is replayed verbatim; a null backend closes the connection. */
    record Decision(@Nullable BackendChoice backend, byte[] replayBytes) {

        public Decision {
            if (replayBytes == null) {
                throw new IllegalArgumentException("replayBytes is required");
            }
        }

        static Decision reject() {
            return new Decision(null, NOTHING_CONSUMED);
        }
    }

    /**
     * @param source      the public client address, already resolved through any trusted PROXY header
     * @param destination the public listener address the client aimed at
     */
    Decision route(BufferedInputStream input, InetSocketAddress source, InetSocketAddress destination)
        throws IOException;
}
