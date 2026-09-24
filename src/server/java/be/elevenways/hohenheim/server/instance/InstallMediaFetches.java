package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.instance.InstallMediaFetchState;
import be.elevenways.hohenheim.model.InstallMediaFetchModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.HandlerSupport;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.async.ProgressSink;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.QueryBuilder;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * THE background lane of an install-media fetch: the request stores a {@code pending} row and returns,
 * a job downloads and imports, and the row carries the state, the downloaded fraction and the failure
 * reason the Install media tab renders.
 *
 * AIDEV-NOTE: a job that dies with its controller cannot write its own ending, and a sweep that settles
 * "rows written before this process started" is wrong across several controllers over one database
 * (the BootSettle caveat). So an in-flight row is settled ON READ instead, by a bound no live job can
 * outlast: {@link #LIVE_BOUND} past its creation it can only be a corpse and reads as INTERRUPTED. A
 * job that somehow outlived the bound still writes its real ending afterwards, which wins.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class InstallMediaFetches {

    /** At most this many fetches in flight installation-wide: each one can fill 16 GiB of temp space. */
    public static final int MAX_ACTIVE = 2;

    /**
     * The longest a live fetch can take: the fetcher's whole-exchange deadline plus a generous bound
     * on the daemon import that follows it.
     */
    static final Duration LIVE_BOUND = InstallMedia.FETCH_DEADLINE.plus(Duration.ofHours(1));

    /** How long a finished fetch stays on the tab. */
    static final Duration SHOWN_FOR = Duration.ofDays(1);

    /** A failure reason is operator prose; the column is not a log. */
    private static final int MAX_REASON = 500;

    /** Serializes the running-check-then-insert of {@link #start} within this process. */
    private static final Object START_LOCK = new Object();

    /** The work of one fetch, told where to report progress. */
    @FunctionalInterface
    public interface Transfer {

        /** @throws IOException naming what failed; its message becomes the stored reason */
        void run(@NonNull StoredProgress progress) throws IOException;
    }

    private InstallMediaFetches() {
    }

    /**
     * Store a {@code pending} fetch of {@code name} onto {@code server} and hand {@code transfer} to a
     * background job. The caller has already run every synchronous refusal
     * ({@link InstallMedia#requireFetchable}).
     *
     * AIDEV-NOTE: the running-check and the insert are one step only within THIS process; two
     * controllers accepting the same name in the same instant both start, and the second import then
     * fails by name at the daemon ({@code already exists}), which the tab shows. The cap is a resource
     * bound, not a security boundary.
     *
     * @return the stored fetch id
     * @throws Violations {@code media_fetch_running} while one of that name runs on the host,
     *         {@code media_fetch_busy} while {@link #MAX_ACTIVE} fetches run
     */
    public static int start(@NonNull Row server, @NonNull String name, @NonNull Transfer transfer) {
        int serverId = server.get(ServerModel.ID);
        InstallMediaFetchModel model = Models.get(InstallMediaFetchModel.class);
        int fetchId;
        synchronized (START_LOCK) {
            List<Row> active = settled(model.find()
                .where(InstallMediaFetchModel.STATE.in(activeTokens())));
            for (Row row : active) {
                if (Integer.valueOf(serverId).equals(row.get(InstallMediaFetchModel.SERVER_ID))
                        && name.equals(row.get(InstallMediaFetchModel.NAME))) {
                    throw Violations.ofField("name", name, violationText("media_fetch_running")
                        .withArg("media", name));
                }
            }
            if (active.size() >= MAX_ACTIVE) {
                throw Violations.ofForm(violationText("media_fetch_busy")
                    .withArg("count", String.valueOf(MAX_ACTIVE)));
            }
            // An earlier ending of the same name is superseded by this attempt.
            model.find()
                .where(InstallMediaFetchModel.SERVER_ID.eq(serverId))
                .where(InstallMediaFetchModel.NAME.eq(name))
                .delete();
            Row row = model.createEmptyRow();
            row.set(InstallMediaFetchModel.SERVER_ID, serverId);
            row.set(InstallMediaFetchModel.NAME, name);
            row.set(InstallMediaFetchModel.STATE, InstallMediaFetchState.PENDING.token());
            model.save(row);
            fetchId = row.get(InstallMediaFetchModel.ID);
        }
        HandlerSupport.inBackground(() -> run(fetchId, serverId, name, transfer));
        return fetchId;
    }

    /**
     * The fetches the Install media tab shows for one host, newest first: every in-flight one and
     * every ending of the last {@link #SHOWN_FOR}; an in-flight row past {@link #LIVE_BOUND} is
     * settled to INTERRUPTED first.
     */
    public static @NonNull List<Row> shownFor(int serverId) {
        Instant since = Now.instant().minus(LIVE_BOUND).minus(SHOWN_FOR);
        List<Row> shown = new ArrayList<>();
        for (Row row : settled(Models.get(InstallMediaFetchModel.class).find()
                .where(InstallMediaFetchModel.SERVER_ID.eq(serverId))
                .where(InstallMediaFetchModel.CREATED_AT.gt(since))
                .orderBy(InstallMediaFetchModel.ID, SortOrder.DESC))) {
            Instant finished = row.get(InstallMediaFetchModel.FINISHED_AT);
            if (stateOf(row).active() || finished == null
                    || finished.isAfter(Now.instant().minus(SHOWN_FOR))) {
                shown.add(row);
            }
        }
        return shown;
    }

    /** @return the row's state; an unknown token fails closed as FAILED */
    public static @NonNull InstallMediaFetchState stateOf(@NonNull Row row) {
        return InstallMediaFetchState.of(row.get(InstallMediaFetchModel.STATE));
    }

    /** The job body: every accepted fetch reaches a stored ending, whatever the transfer throws. */
    static void run(int fetchId, int serverId, @NonNull String name, @NonNull Transfer transfer) {
        StoredProgress progress = new StoredProgress(fetchId);
        try {
            write(fetchId, InstallMediaFetchState.DOWNLOADING, null, null, false);
            transfer.run(progress);
            write(fetchId, InstallMediaFetchState.READY, 1.0, null, true);
            ActivityLog.record(Models.get(ServerModel.class), serverId, "media_fetched", name);
        } catch (IOException | RuntimeException failure) {
            String reason = failure.getMessage() != null && !failure.getMessage().isBlank()
                ? failure.getMessage() : failure.getClass().getSimpleName();
            Blast.log("MEDIA: fetching", name, "onto host", serverId, "failed -", reason);
            write(fetchId, InstallMediaFetchState.FAILED, null,
                reason.length() > MAX_REASON ? reason.substring(0, MAX_REASON) : reason, true);
        }
    }

    /**
     * THE ProgressSink of one stored fetch: the fraction lands on the row at most every
     * {@link #PERSIST_INTERVAL_NANOS}, and the import phase is announced once.
     */
    public static final class StoredProgress implements ProgressSink {

        /** Two seconds: a tab refreshing every few seconds needs no finer grain than that. */
        private static final long PERSIST_INTERVAL_NANOS = 2_000_000_000L;

        private final int fetchId;
        private long lastPersisted;
        private boolean persistedOnce;
        private int parts;
        private int partsDone;

        StoredProgress(int fetchId) {
            this.fetchId = fetchId;
        }

        /** The download is complete; the ISO now streams into the host's pool. */
        public void importing() {
            write(this.fetchId, InstallMediaFetchState.IMPORTING, 1.0, null, false);
        }

        @Override
        public void reportProgress(double fraction) {
            long now = System.nanoTime();
            if (this.persistedOnce && now - this.lastPersisted < PERSIST_INTERVAL_NANOS) {
                return;
            }
            this.persistedOnce = true;
            this.lastPersisted = now;
            write(this.fetchId, InstallMediaFetchState.DOWNLOADING,
                Math.max(0.0, Math.min(1.0, fraction)), null, false);
        }

        @Override
        public void reportProgress(double fraction, @Nullable String message) {
            reportProgress(fraction);
        }

        /** The tab words every state itself, so a free-text message has nowhere to go. */
        @Override
        public void reportProgressMessage(@NonNull String message) {
        }

        @Override
        public void addProgressParts(int parts) {
            this.parts += Math.max(0, parts);
        }

        @Override
        public void reportProgressPart() {
            if (this.parts > 0) {
                this.partsDone = Math.min(this.parts, this.partsDone + 1);
                reportProgress((double) this.partsDone / this.parts);
            }
        }
    }

    // -- plumbing -------------------------------------------------------------

    /**
     * One state write, by id: a host deleted mid-fetch cascades its rows away, and the write then
     * matches nothing instead of resurrecting one.
     */
    private static void write(int fetchId, @NonNull InstallMediaFetchState state,
                              @Nullable Double progress, @Nullable String error, boolean finished) {
        try {
            QueryBuilder<Row> update = Models.get(InstallMediaFetchModel.class).find()
                .where(InstallMediaFetchModel.ID.eq(fetchId))
                .assign(InstallMediaFetchModel.STATE, state.token())
                .assign(InstallMediaFetchModel.PROGRESS, progress)
                .assign(InstallMediaFetchModel.ERROR, error);
            if (finished) {
                update.assign(InstallMediaFetchModel.FINISHED_AT, Now.instant());
            }
            update.updateAll();
        } catch (RuntimeException unwritten) {
            // A progress write that fails must not abort the transfer it reports on; the ending
            // write is retried by nothing, which the on-read settle then covers.
            Blast.log("MEDIA: could not store fetch", fetchId, "as", state.token(), "-",
                unwritten.getMessage());
        }
    }

    /** The query's rows, with every in-flight row past {@link #LIVE_BOUND} settled to INTERRUPTED. */
    private static @NonNull List<Row> settled(@NonNull QueryBuilder<Row> query) {
        Instant deadline = Now.instant().minus(LIVE_BOUND);
        List<Row> rows = new ArrayList<>();
        for (Row row : query.all()) {
            Instant created = row.get(InstallMediaFetchModel.CREATED_AT);
            if (stateOf(row).active() && (created == null || created.isBefore(deadline))) {
                int fetchId = row.get(InstallMediaFetchModel.ID);
                write(fetchId, InstallMediaFetchState.INTERRUPTED, null, null, true);
                row = Models.get(InstallMediaFetchModel.class).findById(fetchId);
                if (row == null) {
                    continue;
                }
            }
            rows.add(row);
        }
        return rows;
    }

    private static @NonNull List<String> activeTokens() {
        List<String> tokens = new ArrayList<>();
        for (InstallMediaFetchState state : InstallMediaFetchState.values()) {
            if (state.active()) {
                tokens.add(state.token());
            }
        }
        return tokens;
    }

    private static @NonNull Microcopy violationText(@NonNull String key) {
        return Microcopy.of(key).withFilter("scope", "violations");
    }
}
