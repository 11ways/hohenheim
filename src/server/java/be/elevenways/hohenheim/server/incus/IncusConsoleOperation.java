package be.elevenways.hohenheim.server.incus;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One console operation of an Incus instance, serial or VGA, held by its control websocket from the moment it starts
 * until {@link #close}, which closes that socket and cancels the operation.
 *
 * AIDEV-NOTE: an instance has ONE console: while any console operation of it runs, Incus refuses another of either
 * type without force ("Force is required to take it over"), so a serial console Hohenheim forgets keeps the VM's
 * screen and the operator's {@code incus console} out. The daemon ends a console operation when its control websocket
 * closes; one whose control never connected outlives its data socket (Incus 7.3, verified 2026-10-12: still running
 * after the data socket closed in order, gone at once once a linked control closed). So the control links at once and
 * a close releases both.
 *
 * @author Jelle De Loecker
 * @since 0.2.0
 */
public final class IncusConsoleOperation implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(IncusConsoleOperation.class.getName());

    private final @NonNull IncusClient incus;
    private final IncusClient.@NonNull OperationSocket data;
    private final @NonNull IncusWebSocket control;
    private final AtomicBoolean closed = new AtomicBoolean();

    private IncusConsoleOperation(@NonNull IncusClient incus, IncusClient.@NonNull OperationSocket data,
                                  @NonNull IncusWebSocket control) {
        this.incus = incus;
        this.data = data;
        this.control = control;
    }

    /**
     * Links the control socket of a console operation the daemon just started.
     *
     * @param operation the started operation, as the console POST answered it
     * @param what      what the operation is, for errors
     * @throws IOException when the operation carries no sockets or its control cannot link; it is cancelled then
     */
    public static @NonNull IncusConsoleOperation link(@NonNull IncusClient incus, @NonNull Map<String, Object> operation,
                                                      @NonNull String what) throws IOException {
        IncusClient.OperationSocket data = IncusClient.OperationSocket.of(operation, IncusClient.OperationSocket.DATA,
            what);
        IncusWebSocket control;
        try {
            control = incus.operationWebSocket(IncusClient.OperationSocket.of(operation,
                IncusClient.OperationSocket.CONTROL, what));
        } catch (IOException refused) {
            cancel(incus, data.operationPath());
            throw refused;
        }
        return new IncusConsoleOperation(incus, data, control);
    }

    /** @return the operation's data socket; each open is a fresh connection to the console */
    public IncusClient.@NonNull OperationSocket data() {
        return this.data;
    }

    /** Ends the operation: its control socket closes and it is cancelled; only the first call acts. */
    @Override
    public void close() {
        if (this.closed.compareAndSet(false, true)) {
            this.control.close();
            cancel(this.incus, this.data.operationPath());
        }
    }

    /** Cancels an operation that may have ended already; a failure is only logged, the operation ends either way. */
    private static void cancel(@NonNull IncusClient incus, @NonNull String operationPath) {
        try {
            incus.cancelOperation(operationPath);
        } catch (IOException ended) {
            LOG.log(Level.FINE, "Incus did not cancel " + operationPath, ended);
        }
    }
}
