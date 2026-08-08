package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.instance.InstanceQuota;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.quota.Quotas;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The per-OWNER memory budget: an UNBOUNDED workload is charged its kind's declared
 * footprint (not nothing), racing creates cannot both spend the last megabyte, a declared
 * limit over the remaining budget is refused BY NAME with the numbers in it, and every
 * terminating path hands the memory back so an owner is never progressively locked out.
 *
 * AIDEV-NOTE: the RACE step pins TWO racers against a budget of exactly one workload. A
 * green run is not proof the race fired -- two threads can serialize -- so the step also
 * asserts the ledger STATE (used == limit, never limit + 512) and the live-row count, which
 * are wrong under a lost race whether or not the threads actually interleaved. Raise the
 * racer count if this ever needs to be more aggressive; do not weaken the state assertions.
 *
 * AIDEV-NOTE: hermetic on purpose -- no daemon anywhere. The instances carry no host, so
 * InstanceCapacity's per-HOST booking is a no-op here and the numbers asserted are the
 * OWNER budget's alone. That separation is the point: the two ledgers book the same amount
 * and must not be read through each other.
 */
class InstanceMemoryQuotaTest extends HohenheimTestBase {

    private static final String PREFIX = "mem-quota-";

    /** DockerContainerKind.defaultFootprintMb -- what an unbounded container is admitted as. */
    private static final int FOOTPRINT_MB = 512;

    private static final String MEMORY_BUCKET = InstanceQuota.memoryBucketOf("");
    private static final String COUNT_BUCKET = InstanceQuota.bucketKeyOf("");

    private Integer previousMemoryCap;
    private Integer previousCountCap;

    @AfterEach
    void cleanUp() {
        // Hard delete: the remove pairing releases both dimensions, so this class leaves the
        // shared server's operator buckets exactly as it found them.
        Model instances = Models.get(InstanceModel.class);
        for (Row row : instances.find().withTrashed()
                .where(InstanceModel.NAME.startsWith(PREFIX)).all()) {
            instances.delete(row.get(InstanceModel.ID));
        }
        if (this.previousMemoryCap != null) {
            HohenheimSettings.VALUES.setValue(
                HohenheimSettings.Quota.MAX_MEMORY_MB_PER_OWNER, this.previousMemoryCap);
            this.previousMemoryCap = null;
        }
        if (this.previousCountCap != null) {
            HohenheimSettings.VALUES.setValue(
                HohenheimSettings.Quota.MAX_INSTANCES_PER_OWNER, this.previousCountCap);
            this.previousCountCap = null;
        }
    }

    @Test
    void anOwnerMemoryBudgetBindsUnboundedWorkloadsAndComesBackOnEveryExit() throws Exception {
        this.previousMemoryCap = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Quota.MAX_MEMORY_MB_PER_OWNER);
        this.previousCountCap = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Quota.MAX_INSTANCES_PER_OWNER);
        long baseline = Quotas.usedOf(MEMORY_BUCKET);

        // 1. THE ITEM'S CORE CLAIM: a workload that declares NO memory_limit_mb is charged
        //    its kind's declared footprint, not zero. Charging zero is what made a
        //    limits-summed budget decoration -- a host full of unbounded tenant workloads
        //    would read as an empty budget.
        Row unbounded = save(row(PREFIX + "unbounded", null));
        assertThat((Integer) unbounded.get(InstanceModel.QUOTA_MEMORY_MB))
            .as("step 1: an unbounded workload is STAMPED with its kind footprint")
            .isEqualTo(FOOTPRINT_MB);
        assertThat(Quotas.usedOf(MEMORY_BUCKET))
            .as("step 1: and the owner's budget really holds those megabytes")
            .isEqualTo(baseline + FOOTPRINT_MB);

        // 2. A DECLARED limit is charged as declared -- the same ledger, the same bucket.
        Row declared = save(row(PREFIX + "declared", 256));
        assertThat((Integer) declared.get(InstanceModel.QUOTA_MEMORY_MB))
            .as("step 2: a declared limit is booked as declared").isEqualTo(256);
        assertThat(Quotas.usedOf(MEMORY_BUCKET))
            .as("step 2: the owner now holds both workloads' memory")
            .isEqualTo(baseline + FOOTPRINT_MB + 256);

        // 3. Cap the owner with room for EXACTLY one more unbounded workload, then race two
        //    creates through the REAL create submit. The instance COUNT is left uncapped, so
        //    the only thing that can refuse either create is the memory budget.
        long limit = Quotas.usedOf(MEMORY_BUCKET) + FOOTPRINT_MB;
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Quota.MAX_MEMORY_MB_PER_OWNER, (int) limit);
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Quota.MAX_INSTANCES_PER_OWNER, 0);

        CyclicBarrier barrier = new CyclicBarrier(2);
        HttpResponse<?>[] responses = new HttpResponse<?>[2];
        Throwable[] failures = new Throwable[2];
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            int slot = i;
            Thread worker = new Thread(() -> {
                try {
                    barrier.await();
                    responses[slot] = postCreate(PREFIX + "race" + slot);
                } catch (Throwable error) {
                    failures[slot] = error;
                }
            });
            worker.start();
            threads.add(worker);
        }
        for (Thread thread : threads) {
            thread.join();
        }
        assertThat(failures).as("step 3: both submits completed").containsOnlyNulls();

        // 4. STATE first: exactly ONE racing row landed and the budget is exactly full.
        //    Two rows, or used == limit + 512, would both mean the same last megabyte was
        //    spent twice -- the defect a form-render check cannot catch.
        List<Row> raced = liveNamed(PREFIX + "race");
        assertThat(raced).as("step 4: exactly one racing create landed").hasSize(1);
        assertThat(Quotas.usedOf(MEMORY_BUCKET))
            .as("step 4: used == limit, not limit + one footprint").isEqualTo(limit);

        // 5. The loser was told WHY, by name, with the numbers in it.
        int refusals = 0;
        int successes = 0;
        for (HttpResponse<?> response : responses) {
            if (String.valueOf(response.body()).contains("Memory quota reached")) {
                refusals++;
            } else if (response.statusCode() == 200 || response.statusCode() == 302
                    || response.statusCode() == 303) {
                successes++;
            }
        }
        assertThat(refusals).as("step 5: exactly one loser, refused by the NAMED violation")
            .isEqualTo(1);
        assertThat(successes).as("step 5: exactly one winner").isEqualTo(1);

        // 6. A workload DECLARING more than the remaining budget is refused too, and the
        //    refusal names the dimension rather than a bare status code.
        Throwable overBudget = catchThrowable(() -> save(row(PREFIX + "greedy", 4096)));
        assertThat(violationKeyOf(overBudget))
            .as("step 6: a declared limit over the budget is refused by name")
            .isEqualTo("memory_quota_reached");
        assertThat(liveNamed(PREFIX + "greedy"))
            .as("step 6: and no row landed -- a refusal that still persisted would pass a "
                + "status-only test").isEmpty();

        // 7. THE LOCKOUT TEST: destroy performs a soft delete through save(), so the release
        //    must ride the deleted_at transition (the remove hooks never fire there). The
        //    freed megabytes must be exactly what the row was STAMPED with, or an owner
        //    loses budget one destroy at a time.
        Row winner = raced.get(0);
        winner.set(InstanceModel.STATUS, InstanceModel.STATUS_STOPPED);
        winner.set(InstanceModel.DELETED_AT, Instant.now());
        Models.get(InstanceModel.class).save(winner);
        assertThat(Quotas.usedOf(MEMORY_BUCKET))
            .as("step 7: the soft-delete transition hands the stamped megabytes back")
            .isEqualTo(limit - FOOTPRINT_MB);

        // 8. POSITIVE ANCHOR: the freed budget admits a new workload of the same size.
        Row replacement = save(row(PREFIX + "replacement", null));
        assertThat(Quotas.usedOf(MEMORY_BUCKET))
            .as("step 8: the freed budget is exactly one workload, not a reset")
            .isEqualTo(limit);
        assertThat(catchThrowable(() -> save(row(PREFIX + "onemore", null))))
            .as("step 8: and the next one is refused again").isInstanceOf(Violations.class);

        // 9. THE DELTA BRANCH: a live row that stays live can change what it declares. A
        //    shrink frees the difference and re-stamps; the count never moved.
        replacement.set(InstanceModel.SETTINGS, settings(128));
        Models.get(InstanceModel.class).save(replacement);
        assertThat((Integer) replacement.get(InstanceModel.QUOTA_MEMORY_MB))
            .as("step 9: the shrunk row is re-stamped at what it now declares")
            .isEqualTo(128);
        assertThat(Quotas.usedOf(MEMORY_BUCKET))
            .as("step 9: and the difference came back to the budget")
            .isEqualTo(limit - FOOTPRINT_MB + 128);

        // 10. A grow beyond the budget is refused, and the row keeps its old declaration --
        //     a refused grow that still re-stamped would drift the ledger permanently.
        Row toGrow = Models.get(InstanceModel.class).findById(replacement.get(InstanceModel.ID));
        toGrow.set(InstanceModel.SETTINGS, settings(8192));
        assertThat(violationKeyOf(catchThrowable(() ->
            Models.get(InstanceModel.class).save(toGrow))))
            .as("step 10: a grow past the budget is refused by name")
            .isEqualTo("memory_quota_reached");
        assertThat((Integer) Models.get(InstanceModel.class)
                .findById(replacement.get(InstanceModel.ID)).get(InstanceModel.QUOTA_MEMORY_MB))
            .as("step 10: the stored stamp still says what the row really holds")
            .isEqualTo(128);

        // 11. The HARD delete pairing releases too (the criteria-delete lane), and the
        //     count bucket is left exactly as this test found it.
        long countBefore = Quotas.usedOf(COUNT_BUCKET);
        Models.get(InstanceModel.class).delete(replacement.get(InstanceModel.ID));
        assertThat(Quotas.usedOf(MEMORY_BUCKET))
            .as("step 11: a hard delete hands the memory back as well")
            .isEqualTo(limit - FOOTPRINT_MB);
        assertThat(Quotas.usedOf(COUNT_BUCKET))
            .as("step 11: and its instance slot with it").isEqualTo(countBefore - 1);
    }

    // -- helpers --------------------------------------------------------------

    private static Map<String, Object> settings(Integer memoryLimitMb) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", "alpine");
        if (memoryLimitMb != null) {
            settings.put("memory_limit_mb", memoryLimitMb);
        }
        return settings;
    }

    private static Row row(String name, Integer memoryLimitMb) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SETTINGS, settings(memoryLimitMb));
        return row;
    }

    /** The write funnel every writer goes through -- the hook under test rides it. */
    private static Row save(Row row) {
        return Models.get(InstanceModel.class).save(row);
    }

    private static List<Row> liveNamed(String prefix) {
        return Models.get(InstanceModel.class).find()
            .where(InstanceModel.NAME.startsWith(prefix))
            .and(InstanceModel.DELETED_AT.isNull())
            .all();
    }

    private HttpResponse<String> postCreate(String name) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + getServerPort() + "/admin/instances/new"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionToken)
            .header("X-Csrf-Token", csrfToken)
            .POST(HttpRequest.BodyPublishers.ofString(
                "name=" + name + "&kind=hohenheim%3Adocker_container"))
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String violationKeyOf(Throwable thrown) {
        assertThat(thrown)
            .as("the write was refused with Violations (a write that SUCCEEDS here means the"
                + " memory budget let it through)")
            .isInstanceOf(Violations.class);
        return ((Violations) thrown).all().get(0).message().key();
    }
}
