package be.elevenways.hohenheim.test.host;

import be.elevenways.hohenheim.host.HostCapacityView;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.instance.InstanceCapacity;
import be.elevenways.hohenheim.server.orm.GeneratedRows;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.ApiKeyService;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The HOST capacity surface: the memory picture that lived only on the admin overview
 * page, which is why an operator placing databases on shared engines was reading
 * {@code docker stats} -- a live-RSS number that answers a DIFFERENT question from the
 * one placement decides on (declared ceilings, charge == cap).
 *
 * ONE journey, and the assertions compare against {@link InstanceCapacity} itself rather
 * than against literals: the claim being pinned is not "the API returns 10915", it is
 * "the API returns whatever the ledger the panel reads says", which a literal cannot
 * distinguish from a second computation that happens to agree today.
 */
class HostApiTest extends HohenheimTestBase {

    private static final String PREFIX = "hostapi-";

    private static String keyAdmin;
    private static String keyOutsider;

    private static Integer hostId;
    private static Integer instanceId;

    @BeforeAll
    static void seed() {
        hostId = host(PREFIX + "host");
        instanceId = instance(PREFIX + "web", hostId);

        int adminId = AuthModels.users().find()
            .where(UserModel.EMAIL.eq("test@hohenheim.local")).first().get(UserModel.ID);
        keyAdmin = ApiKeyService.create(adminId, PREFIX + "admin", List.of("hohenheim.*"), null)
            .plaintext();

        // Scopes NARROW authority, they never grant it: a wildcard-scoped key whose owner
        // holds no admin permission must still be refused.
        int outsiderId = user(PREFIX + "outsider@surface.test", "Host API Outsider");
        keyOutsider = ApiKeyService.create(outsiderId, PREFIX + "outsider",
            List.of("hohenheim.*"), null).plaintext();
    }

    @AfterAll
    static void cleanUp() {
        Model instances = Models.get(InstanceModel.class);
        GeneratedRows.sweeping("test", () -> {
            for (Row row : instances.find().where(InstanceModel.NAME.startsWith(PREFIX)).all()) {
                instances.delete(row.get(InstanceModel.ID));
            }
        });
        Model servers = Models.get(ServerModel.class);
        for (Row row : servers.find().where(ServerModel.NAME.startsWith(PREFIX)).all()) {
            servers.delete(row.get(ServerModel.ID));
        }
    }

    @Test
    void theHostLedgerIsReadableWithoutABrowserAndMatchesTheCapacityService() throws Exception {
        HostCapacityView ledger = InstanceCapacity.viewOf(
            Models.get(ServerModel.class).findById(hostId), hostId);
        long booked = InstanceCapacity.bookedMbOn(hostId);
        long workloadBooked = InstanceCapacity.bookedMbOf(
            Models.get(InstanceModel.class).findById(instanceId));

        // 0. The fixture is only worth something if the ledger has real numbers in it.
        assertThat(ledger.measured())
            .as("step 0: the seeded host carries a fresh memory reading").isTrue();
        assertThat(workloadBooked)
            .as("step 0: and the seeded workload books its declared ceiling")
            .isEqualTo(512);

        // 1. The list: every host with its budget, its booking and what is left.
        HttpResponse<String> list = keyGet(keyAdmin, "/api/v1/hosts");
        assertThat(list.statusCode()).as("step 1: the host list answers: " + list.body())
            .isEqualTo(200);
        assertThat(list.body()).as("step 1: naming the seeded host and its admission state")
            .contains("\"name\":\"" + PREFIX + "host\"")
            .contains("\"admission\":\"" + ServerModel.ADMISSION_ADMITTED + "\"");

        // 2. The single-host form, where the numbers can be pinned exactly: every one of
        //    them is the capacity service's own answer, never a second computation.
        HttpResponse<String> one = keyGet(keyAdmin, "/api/v1/hosts/" + hostId);
        assertThat(one.statusCode()).as("step 2: the host answers: " + one.body())
            .isEqualTo(200);
        assertThat(one.body()).as("step 2: the budget is the ledger's budget")
            .contains("\"measured\":true")
            .contains("\"budget_mb\":" + ledger.budgetMb())
            .contains("\"booked_mb\":" + booked)
            .contains("\"bookable_mb\":" + ledger.bookableMb());
        assertThat(one.body()).as("step 2: and free is what is left to book, the one number "
            + "the panel's three facts leave to arithmetic")
            .contains("\"free_mb\":" + Math.max(0, ledger.bookableMb() - (int) booked));
        assertThat(one.body()).as("step 2: with the declared overcommit and reserve, so a "
            + "budget bigger than the machine is explicable rather than surprising")
            .contains("\"overcommit_ratio\":")
            .contains("\"reserve_mb\":");

        // 3. THE PER-WORKLOAD ROWS: what each workload holds against that budget. This is
        //    the half docker stats cannot answer -- it measures live RSS, while placement
        //    decides on the booked ceiling, and a stopped workload still holds its booking.
        assertThat(one.body()).as("step 3: the workload is listed with what it books")
            .contains("\"workloads\":")
            .contains("\"id\":" + instanceId)
            .contains("\"name\":\"" + PREFIX + "web\"")
            .contains("\"booked_mb\":" + workloadBooked);
        assertThat(one.body()).as("step 3: and no live figure is invented -- nobody is "
            + "watching this workload, so the field is absent")
            .doesNotContain("usage_mb");

        // 4. The door: operator-only on both verbs, and no existence oracle.
        assertThat(keyGet(keyOutsider, "/api/v1/hosts").statusCode())
            .as("step 4: a non-operator key cannot enumerate hosts").isEqualTo(403);
        assertThat(keyGet(keyOutsider, "/api/v1/hosts/" + hostId).statusCode())
            .as("step 4: nor read one").isEqualTo(403);
        assertThat(keyGet(keyAdmin, "/api/v1/hosts/999999").statusCode())
            .as("step 4: and an unknown host is 404").isEqualTo(404);
    }

    // -- fixtures -------------------------------------------------------------

    private static int user(String email, String name) {
        Row row = AuthModels.users().createEmptyRow();
        row.set(UserModel.EMAIL, email);
        row.set(UserModel.DISPLAY_NAME, name);
        row.set(UserModel.ENABLED, true);
        row.set(UserModel.CREATED_AT, Instant.now());
        row.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(row);
        return row.get(UserModel.ID);
    }

    private static int host(String name) {
        Model servers = Models.get(ServerModel.class);
        Row row = servers.createEmptyRow();
        row.set(ServerModel.NAME, name);
        row.set(ServerModel.RUNTIME, ServerModel.RUNTIME_DOCKER);
        row.set(ServerModel.MODE, ServerModel.MODE_LOCAL);
        row.set(ServerModel.POSTURE, ServerModel.POSTURE_SHARED_CONTAINER);
        row.set(ServerModel.ADMISSION, ServerModel.ADMISSION_ADMITTED);
        row.set(ServerModel.PREFLIGHT_OK, true);
        servers.save(row);
        HostFixtures.acknowledgePosture(row);
        HostPreflight.store(name, new HostPreflight.Report(
            List.of(new HostPreflight.Check("daemon", HostPreflight.STATUS_PASS, true, "ok")),
            Map.of("mem_total", 16L * 1024 * 1024 * 1024), true, Instant.now(), null));
        return row.get(ServerModel.ID);
    }

    /** A stopped container declaring 512 MB, so its booking is a number the test knows. */
    private static int instance(String name, Integer serverId) {
        Model instances = Models.get(InstanceModel.class);
        Row row = instances.createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SERVER_ID, serverId);
        row.set(InstanceModel.SETTINGS, Map.of("image", "alpine", "tag", "latest",
            "memory_limit_mb", 512));
        row.set(InstanceModel.STATUS, InstanceModel.STATUS_STOPPED);
        instances.save(row);
        return row.get(InstanceModel.ID);
    }
}
