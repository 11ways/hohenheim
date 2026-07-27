package be.elevenways.hohenheim.server.proxy;

import io.undertow.server.HttpServerExchange;
import org.xnio.conduits.StreamSinkConduit;

import java.io.IOException;

/**
 * Commits a proxied response as soon as the upstream headers arrive, instead of waiting
 * for the first body byte.
 *
 * <p>AIDEV-NOTE: Undertow will NOT emit a proxied response's headers until something
 * writes. {@code ProxyHandler.ResponseCallback} copies the status and headers and then
 * calls {@code Transfer.initiateTransfer}, which writes nothing when the upstream has no
 * DATA ready yet (it reads 0 bytes and breaks out), and
 * {@code HttpServerExchange.getResponseChannel()} only marks the response as started.
 * Every long-lived streaming response therefore stalled: gRPC servers send opening
 * metadata and then stay quiet until a message exists, so a client waiting for that
 * metadata to consider the stream established hung forever. NetBird's SignalExchange sat
 * for 50 seconds and gave up with "didn't receive a registration header from the Signal
 * server", while the same upstream committed its headers in 7ms when dialed directly.
 *
 * <p>ProxyHandler offers no hook for this -- it is {@code final}, its response callback is
 * {@code private}, and Undertow has no eager-flush option -- so the flush is scheduled
 * from a response wrapper. The wrapper runs inside {@code getResponseChannel()}, i.e.
 * after the headers were copied and before the transfer starts, and the queued IO-thread
 * task therefore runs immediately after the transfer returns. A bare {@code flush()}
 * commits headers on both protocols: HTTP/1.1 serializes the status line and headers in
 * {@code HttpResponseConduit.processWrite}, and HTTP/2 queues the HEADERS frame because
 * {@code Http2DataStreamSinkChannel.isFlushRequiredOnEmptyBuffer()} is true for the first
 * frame.
 *
 * <p>Responses whose body was already available are unaffected: the transfer writes first,
 * which commits the headers, and the scheduled flush is then a no-op.
 */
final class EagerResponseCommit {

    private EagerResponseCommit() {
    }

    /**
     * Must be called BEFORE the proxy handler runs: {@code addResponseWrapper} throws once
     * the response channel has been handed out.
     */
    static void install(HttpServerExchange exchange) {
        exchange.addResponseWrapper((factory, ex) -> {
            StreamSinkConduit conduit = factory.create();
            ex.getIoThread().execute(() -> {
                try {
                    conduit.flush();
                } catch (IOException | RuntimeException ignored) {
                    // The exchange is already failing or finished; ProxyHandler owns that
                    // path and a failed courtesy flush must never surface as a new error.
                }
            });
            return conduit;
        });
    }
}
