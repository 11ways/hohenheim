package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.instance.InstanceQuota;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.quota.Quotas;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The instance-count quota on the REAL create path (the zenit-cms create submit):
 * two racing creates cannot both spend the last slot, the loser gets the NAMED quota
 * refusal, and the release rides the soft-delete TRANSITION -- the exact write
 * InstanceService.destroy performs -- never the remove hooks, which the destroy path
 * does not fire.
 *
 * Asserts STATE (live rows, the stored used counter), not just responses: a test
 * asserting only the refusal cannot see a reservation that was spent twice or a
 * release that never lands.
 */
class InstanceQuotaTest extends HohenheimTestBase {

    private static final String OPERATOR_BUCKET = InstanceQuota.bucketKeyOf("");
    private static final String NAME_PREFIX = "quota-race-";

    private final List<Integer> createdIds = new ArrayList<>();
    private Integer previousLimit;

    /**
     * The create submit places the record, so the fleet must hold one host that accepts
     * it -- otherwise every create here is refused for a placement reason and the quota
     * this class is about is never reached.
     */
    @BeforeAll
    static void makeSomewhereToPlace() {
        HostFixtures.makeLocalPlaceable(65536);
    }

    @AfterEach
    void cleanUp() {
        // Hard delete: the remove-hook pairing releases whatever is still reserved, so
        // this class leaves the shared server's operator bucket exactly as it found it.
        Model instances = Models.get(InstanceModel.class);
        for (Row row : instances.find().where(InstanceModel.NAME.startsWith(NAME_PREFIX)).all()) {
            instances.delete(row.get(InstanceModel.ID));
        }
        this.createdIds.clear();
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Quota.MAX_INSTANCES_PER_OWNER,
            this.previousLimit == null ? 0 : this.previousLimit);
    }

    private HttpResponse<String> postCreate(String name) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + "/admin/instances/new"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionToken)
            .header("X-Csrf-Token", csrfToken)
            .POST(HttpRequest.BodyPublishers.ofString(
                "name=" + name + "&kind=hohenheim%3Adocker_container"))
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private List<Row> liveRaceRows() {
        return Models.get(InstanceModel.class).find()
            .where(InstanceModel.NAME.startsWith(NAME_PREFIX))
            .and(InstanceModel.DELETED_AT.isNull())
            .all();
    }

    @Test
    void twoRacingCreatesCannotOverspendTheLastSlotAndDestroyFreesIt() throws Exception {
        this.previousLimit = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Quota.MAX_INSTANCES_PER_OWNER);
        long usedBefore = Quotas.usedOf(OPERATOR_BUCKET);
        long limit = usedBefore + 1;   // EXACTLY one remaining operator slot
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Quota.MAX_INSTANCES_PER_OWNER, (int) limit);

        // 1. Two threads behind a barrier both submit the REAL create (the zenit-cms
        //    create submit endpoint), for the SAME owner: at create time no record and
        //    therefore no grant exists, so both charge the operator bucket.
        CyclicBarrier barrier = new CyclicBarrier(2);
        HttpResponse<?>[] responses = new HttpResponse<?>[2];
        Throwable[] failures = new Throwable[2];
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            int slot = i;
            Thread worker = new Thread(() -> {
                try {
                    barrier.await();
                    responses[slot] = postCreate(NAME_PREFIX + slot);
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
        assertThat(failures).as("step 1: both submits completed").containsOnlyNulls();

        // 2. STATE first: exactly one live row exists -- two would mean both creates
        //    spent the same last slot, the defect the reservation exists to prevent.
        List<Row> created = liveRaceRows();
        assertThat(created)
            .as("step 2: exactly one instance row with deleted_at IS NULL for this owner")
            .hasSize(1);
        this.createdIds.add(created.get(0).get(InstanceModel.ID));
        assertThat((String) created.get(0).get(InstanceModel.QUOTA_BUCKET))
            .as("step 2: the winner is stamped with the bucket it was charged to")
            .isEqualTo(OPERATOR_BUCKET);
        assertThat(Quotas.usedOf(OPERATOR_BUCKET))
            .as("step 2: used == limit, not limit + 1")
            .isEqualTo(limit);

        // 3. Exactly one response carries the NAMED quota refusal; the other succeeded.
        int refusals = 0;
        int successes = 0;
        for (HttpResponse<?> response : responses) {
            if (String.valueOf(response.body()).contains("Instance quota reached")) {
                refusals++;
            } else if (response.statusCode() == 200 || response.statusCode() == 302
                    || response.statusCode() == 303) {
                successes++;
            }
        }
        assertThat(refusals).as("step 3: exactly one loser, told WHY (named violation)")
            .isEqualTo(1);
        assertThat(successes).as("step 3: exactly one winner").isEqualTo(1);

        // 4. Destroy the winner: the EXACT write InstanceService.destroy performs (soft
        //    delete through save; beforeRemove/afterRemove never fire on that path).
        //    The release must ride this deleted_at null -> non-null transition.
        Row winner = created.get(0);
        winner.set(InstanceModel.STATUS, InstanceModel.STATUS_STOPPED);
        winner.set(InstanceModel.DELETED_AT, Instant.now());
        Models.get(InstanceModel.class).save(winner);
        assertThat(Quotas.usedOf(OPERATOR_BUCKET))
            .as("step 4: the soft-delete transition hands the slot back")
            .isEqualTo(usedBefore);

        // 5. A THIRD create now succeeds -- the proof the release rode the transition
        //    and not the dead remove hook (which never fired).
        HttpResponse<String> third = postCreate(NAME_PREFIX + "third");
        assertThat(third.statusCode()).as("step 5: the freed slot admits a new create")
            .isIn(200, 302, 303);
        assertThat(third.body()).as("step 5: and it is not a re-rendered refusal")
            .doesNotContain("Instance quota reached");
        assertThat(liveRaceRows()).as("step 5: the third instance is live").hasSize(1);
        assertThat(Quotas.usedOf(OPERATOR_BUCKET))
            .as("step 5: the bucket is exactly full again")
            .isEqualTo(limit);

        // 6. And the bucket refuses a FOURTH create: released headroom is one slot,
        //    never a reset.
        HttpResponse<String> fourth = postCreate(NAME_PREFIX + "fourth");
        assertThat(fourth.body()).as("step 6: the next create is refused by name")
            .contains("Instance quota reached");
        assertThat(liveRaceRows()).as("step 6: no fourth row landed").hasSize(1);
    }
}
