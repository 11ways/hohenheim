package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.model.InstanceLogModel;
import be.elevenways.hohenheim.server.orm.RecordStamp;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;

/**
 * The DURABLE half of the console: one UPSERTED {@code instance_logs} row per workload
 * episode, so history outlives the in-memory ring and the controller process.
 *
 * AIDEV-NOTE: the ProclogModel discipline, not a new one -- upsert into the episode's own
 * row (never one row per flush), a periodic flush plus a final one at exit, and
 * {@code CleanOldInstanceLogs} sweeping by age. Retention is therefore bounded on BOTH
 * axes: each row is capped by the ring it mirrors, and the row set is capped by the sweeper.
 *
 * AIDEV-NOTE: what is written is the SESSION RING, which redaction already passed through
 * at ingest. Storing raw text and redacting on read would leave a secret at rest -- a leak
 * regardless of how careful every reader is -- so this class must never be handed anything
 * but already-redacted text.
 */
final class InstanceConsoleLogs {

    /** One episode's write handle; not thread safe on its own, the session serializes it. */
    interface Sink {
        /** Persist the episode's current text (already redacted). */
        void flush(@NonNull String text);
    }

    private InstanceConsoleLogs() {
    }

    /**
     * The sink for one console episode. The datasource is the one captured when the session
     * opened, because the pump runs on a virtual thread with no Db scope of its own.
     */
    static @NonNull Sink sinkFor(int instanceId, @NonNull String handle,
                                 @Nullable Datasource datasource) {
        return new Sink() {

            private @Nullable Integer rowId;

            @Override
            public synchronized void flush(@NonNull String text) {
                if (text.isEmpty()) {
                    return;
                }
                Runnable write = () -> {
                    InstanceLogModel model = Models.get(InstanceLogModel.class);
                    Row row = this.rowId == null ? null : model.findById(this.rowId);
                    if (row == null) {
                        row = model.createEmptyRow();
                        row.set(InstanceLogModel.INSTANCE_ID, instanceId);
                        row.set(InstanceLogModel.HANDLE, handle);
                        row.set(InstanceLogModel.LOG_TEXT, text);
                        row.set(InstanceLogModel.LINE_COUNT, countLines(text));
                        row.set(InstanceLogModel.SAVED_AT, Instant.now());
                        model.save(row);
                    } else {
                        // Every later flush touches the episode's TEXT only: the row is
                        // fully loaded, so a save would restate instance_id and handle.
                        RecordStamp.on(model, row)
                            .set(InstanceLogModel.LOG_TEXT, text)
                            .set(InstanceLogModel.LINE_COUNT, countLines(text))
                            .set(InstanceLogModel.SAVED_AT, Instant.now())
                            .write();
                    }
                    this.rowId = row.get(InstanceLogModel.ID);
                };
                try {
                    if (datasource != null) {
                        Db.run(datasource, write);
                    } else {
                        write.run();
                    }
                } catch (RuntimeException failed) {
                    // A console must keep streaming when its history cannot be written --
                    // but it must say so rather than quietly losing the episode.
                    Blast.log("CONSOLE: could not persist the log of instance", instanceId,
                        "-", failed.getMessage());
                }
            }
        };
    }

    private static int countLines(@NonNull String text) {
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }
}
