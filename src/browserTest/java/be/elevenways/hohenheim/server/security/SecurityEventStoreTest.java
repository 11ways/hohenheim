package be.elevenways.hohenheim.server.security;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.SecurityEventModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The upsert race: concurrent record() calls for one (reporter, type, ip, day)
 * key must produce exactly ONE row with the summed count (the striped lock
 * closes the increment-then-insert window).
 */
class SecurityEventStoreTest {

    private static boolean initialized = false;

    @BeforeAll
    static void initDb() throws Exception {
        if (initialized) return;
        initialized = true;
        File db = File.createTempFile("hohenheim-events-test", ".db");
        db.delete();
        db.deleteOnExit();
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.PATH, db.getAbsolutePath());
        HohenheimDatabase.init();
        HohenheimTestRuntime.ensureBooted();
    }

    @Test
    void concurrentRecordsProduceOneRowWithTheSummedCount() throws Exception {
        int threads = 8;
        int perThread = 25;
        Instant now = Instant.now();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<Thread> workers = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        SecurityEventStore.record(null, "test.concurrent", "203.0.113.240",
                            1, now, now, null);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            worker.start();
            workers.add(worker);
        }

        start.countDown();
        done.await();
        for (Thread worker : workers) {
            worker.join();
        }

        List<Row> rows = Models.get(SecurityEventModel.class).find()
            .where(SecurityEventModel.TYPE.eq("test.concurrent"))
            .where(SecurityEventModel.IP.eq("203.0.113.240"))
            .all();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get(SecurityEventModel.COUNT)).isEqualTo(threads * perThread);
    }
}
