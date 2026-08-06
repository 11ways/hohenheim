package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.instance.RestoreCapacity;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violation;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The capacity gate that stands in front of EVERY restore and every migration, which
 * until now had no test at all: both of its named refusals, the headroom arithmetic
 * that makes "it just fits" refuse anyway, and the fail-closed contract that an
 * unmeasurable host is a refusal rather than a pass.
 *
 * AIDEV-NOTE: this asserts the REAL RestoreCapacity, not the CapacityCheck seam that
 * InstanceMigrationTest stubs out -- the probe half is the only daemon-bound part and
 * it is proven separately in RestoreCapacityLiveTest.
 */
class RestoreCapacityTest extends HohenheimTestBase {

    private static final String NAME_PREFIX = "capacity-test-";

    private final List<Integer> createdHosts = new java.util.ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Integer id : this.createdHosts) {
            Models.get(ServerModel.class).delete(id);
        }
        this.createdHosts.clear();
    }

    private int host(String name, String runtime) {
        Row row = Models.get(ServerModel.class).createEmptyRow();
        row.set(ServerModel.NAME, NAME_PREFIX + name);
        row.set(ServerModel.RUNTIME, runtime);
        row.set(ServerModel.MODE, ServerModel.MODE_SSH);
        // .invalid never resolves, so the probe fails FAST and deterministically --
        // an unroutable IP would make this test a connect-timeout stopwatch.
        row.set(ServerModel.SSH_TARGET, "root@capacity-test.invalid");
        row.set(ServerModel.ADMISSION, ServerModel.ADMISSION_ADMITTED);
        row.set(ServerModel.POSTURE, ServerModel.POSTURE_SHARED_CONTAINER);
        Models.get(ServerModel.class).save(row);
        int id = row.get(ServerModel.ID);
        this.createdHosts.add(id);
        return id;
    }

    private static Violation refusalOf(Throwable thrown, String key, String description) {
        assertThat(thrown).as(description).isInstanceOf(Violations.class);
        Violation named = null;
        for (Violation violation : ((Violations) thrown).all()) {
            if (key.equals(violation.message().key())) {
                named = violation;
            }
        }
        assertThat(named)
            .as("%s -- expected the NAMED refusal '%s', got %s", description, key,
                ((Violations) thrown).all().stream()
                    .map(v -> v.message().key()).toList())
            .isNotNull();
        return named;
    }

    /**
     * The decision half over real numbers: headroom included, the operator told what is
     * needed and what is free, and a fit that only fits WITHOUT headroom still refuses.
     */
    @Test
    void theHeadroomFactorDecidesAndTheRefusalNamesBothFigures() {
        int serverId = host("judge", ServerModel.RUNTIME_INCUS);
        String hostName = ServerModel.nameOf(serverId);

        // 1. Comfortably enough space: no refusal at all.
        assertThat(catchThrowable(() -> RestoreCapacity.judge(serverId, 10_000L, 1_000L)))
            .as("step 1: 10x the payload passes the gate")
            .isNull();

        // 2. EXACTLY the payload is NOT enough -- the 1.2x extraction headroom is the
        //    whole point of the check, and a gate that passed here would let a restore
        //    fill the pool and fail halfway through extraction.
        Throwable exact = catchThrowable(
            () -> RestoreCapacity.judge(serverId, 1_000L, 1_000L));
        Violation refusal = refusalOf(exact, "restore_capacity",
            "step 2: a restore that fits only WITHOUT headroom is refused");

        // 3. The refusal is actionable: it names the host and both figures, so an
        //    operator can see how much short they are without reading the source.
        assertThat(String.valueOf(refusal.message().args().get("server")))
            .as("step 3: the refusal names the host it judged")
            .isEqualTo(hostName);
        assertThat(String.valueOf(refusal.message().args().get("needed")))
            .as("step 3: 'needed' is the payload WITH headroom applied, not the raw size")
            .isEqualTo("1200");
        assertThat(String.valueOf(refusal.message().args().get("available")))
            .as("step 3: 'available' is what the probe measured")
            .isEqualTo("1000");

        // 4. The boundary is inclusive on the passing side: exactly needed is enough.
        assertThat(catchThrowable(() -> RestoreCapacity.judge(serverId, 1_200L, 1_000L)))
            .as("step 4: exactly the headroomed figure passes")
            .isNull();
        assertThat(catchThrowable(() -> RestoreCapacity.judge(serverId, 1_199L, 1_000L)))
            .as("step 4: one byte under it does not")
            .isInstanceOf(Violations.class);
    }

    /**
     * The fail-closed half: a host whose storage cannot be measured REFUSES. A capacity
     * check that silently passes when it cannot measure is a check that cannot fail,
     * and this is the lane that carries every unreachable and every misconfigured host.
     */
    @Test
    void anUnmeasurableHostRefusesByNameOnBothRuntimes() {
        // 1. requiredBytes 0 is not a capacity question at all: no probe, no refusal,
        //    even on a host nothing can reach. (A restore of an empty payload must not
        //    be blocked by an unreachable daemon.)
        int incusId = host("unreachable-incus", ServerModel.RUNTIME_INCUS);
        assertThat(catchThrowable(() -> RestoreCapacity.require(incusId, 0L)))
            .as("step 1: a zero-byte requirement short-circuits before the probe")
            .isNull();

        // 2. An incus host at a black-hole address cannot answer: refused BY NAME,
        //    never passed through.
        Throwable incus = catchThrowable(() -> RestoreCapacity.require(incusId, 1_000L));
        Violation incusRefusal = refusalOf(incus, "restore_capacity_unknown",
            "step 2: an unmeasurable incus host is refused, not admitted");
        assertThat(String.valueOf(incusRefusal.message().args().get("server")))
            .as("step 2: the refusal names the host that could not be measured")
            .isEqualTo(ServerModel.nameOf(incusId));
        assertThat(String.valueOf(incusRefusal.message().args().get("reason")))
            .as("step 2: and carries the underlying reason instead of swallowing it")
            .isNotBlank()
            .isNotEqualTo("null");

        // 3. The docker lane fails closed the same way -- the dispatch on the host's
        //    DECLARED runtime is what picks the probe, and neither branch may pass.
        int dockerId = host("unreachable-docker", ServerModel.RUNTIME_DOCKER);
        Throwable docker = catchThrowable(() -> RestoreCapacity.require(dockerId, 1_000L));
        refusalOf(docker, "restore_capacity_unknown",
            "step 3: an unmeasurable docker host is refused too");

        // 4. A host id that does not resolve to a row at all is still a NAMED refusal.
        //    This is the path that used to throw a raw IllegalArgumentException out of
        //    the refusal CONSTRUCTION itself (nameOf on a vanished row), turning a 422
        //    an API caller can read into an opaque 500.
        Throwable missing = catchThrowable(() -> RestoreCapacity.require(-424242, 1_000L));
        Violation missingRefusal = refusalOf(missing, "restore_capacity_unknown",
            "step 4: an unresolvable host id refuses BY NAME, and the refusal itself"
                + " does not blow up while naming a host that is no longer there");
        assertThat(String.valueOf(missingRefusal.message().args().get("server")))
            .as("step 4: the vanished host is spelled by id rather than crashing")
            .isEqualTo("#-424242");
    }
}
