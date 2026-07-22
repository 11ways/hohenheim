package be.elevenways.hohenheim.server.security;

import be.elevenways.hohenheim.model.SecurityEventModel;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.QueryBuilder;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Upserts into the aggregated security_events table: one row per
 * (reporter, type, ip, UTC day), count incremented with an atomic set-based
 * update; insert only when no row exists yet.
 */
public final class SecurityEventStore {

    private static final int MAX_DETAIL = 400;

    // AIDEV-NOTE: the increment-then-insert upsert races with itself under
    // concurrent record() calls (two threads both see 0 updated rows and both
    // insert). Hohenheim is a single-JVM writer, so a per-key striped lock is
    // sufficient; a unique index is NOT portable because Couchbase does not
    // enforce uniqueness.
    private static final Object[] LOCKS = new Object[64];

    static {
        for (int i = 0; i < LOCKS.length; i++) {
            LOCKS[i] = new Object();
        }
    }

    private SecurityEventStore() {
    }

    /**
     * Record count occurrences of an event. Best-effort: a storage failure is
     * logged, never thrown into the reporting path.
     */
    public static void record(@Nullable Integer reporterId, @NonNull String type,
                              @NonNull String ip, int count,
                              @NonNull Instant firstAt, @NonNull Instant lastAt,
                              @Nullable String detail) {
        try {
            SecurityEventModel events = Models.get(SecurityEventModel.class);
            LocalDate day = LocalDate.ofInstant(lastAt, ZoneOffset.UTC);
            String shortDetail = detail != null && detail.length() > MAX_DETAIL
                ? detail.substring(0, MAX_DETAIL) : detail;

            String key = reporterId + "|" + type + "|" + ip + "|" + day;
            synchronized (LOCKS[Math.floorMod(key.hashCode(), LOCKS.length)]) {
                QueryBuilder<Row> update = events.find()
                    .where(reporterId != null
                        ? SecurityEventModel.REPORTER_ID.eq(reporterId)
                        : SecurityEventModel.REPORTER_ID.isNull())
                    .where(SecurityEventModel.TYPE.eq(type))
                    .where(SecurityEventModel.IP.eq(ip))
                    .where(SecurityEventModel.DAY.eq(day))
                    .increment(SecurityEventModel.COUNT, count)
                    .assign(SecurityEventModel.LAST_AT, lastAt);
                if (shortDetail != null) {
                    update.assign(SecurityEventModel.LAST_DETAIL, shortDetail);
                }
                if (update.updateAll() > 0) {
                    return;
                }

                Row row = events.createEmptyRow();
                row.set(SecurityEventModel.REPORTER_ID, reporterId);
                row.set(SecurityEventModel.TYPE, type);
                row.set(SecurityEventModel.IP, ip);
                row.set(SecurityEventModel.DAY, day);
                row.set(SecurityEventModel.COUNT, count);
                row.set(SecurityEventModel.FIRST_AT, firstAt);
                row.set(SecurityEventModel.LAST_AT, lastAt);
                row.set(SecurityEventModel.LAST_DETAIL, shortDetail);
                events.save(row);
            }
        } catch (RuntimeException e) {
            Blast.log("SECURITY: event upsert failed for", type, ip, "-", e.getMessage());
        }
    }
}
