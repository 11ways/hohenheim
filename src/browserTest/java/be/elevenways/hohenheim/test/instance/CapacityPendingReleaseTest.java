package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.instance.InstanceCapacity;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The host-capacity credit a workload replacement declares for the memory it is about
 * to release: it widens exactly one reservation on exactly one host by exactly that
 * amount, on this thread, and nothing after the scope.
 */
class CapacityPendingReleaseTest {

    private static SqlDatasource datasource;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = TestDatabases.freshDatasource();
        HohenheimTestRuntime.ensureBooted();
    }

    @Test
    void theCreditAdmitsExactlyTheReleasingAmountOnTheDeclaredHostAndOnlyInsideTheScope() {
        Db.run(datasource, () -> {
            int local = ServerModel.localServerId();
            int reserve = HohenheimSettings.VALUES.getValue(
                HohenheimSettings.Capacity.HOST_MEMORY_RESERVE_MB);
            // 1. A host whose budget is exactly 1000 MB, booked to the last megabyte.
            HostFixtures.makeLocalPlaceable(1000 + reserve);
            long already = InstanceCapacity.bookedMbOn(local);
            long room = 1000 - already;
            assertThat(room).as("step 1: the fixture host has room to fill").isPositive();
            InstanceCapacity.reserve(local, room);
            try {
                assertThat(refusalOf(() -> InstanceCapacity.reserve(local, 1)))
                    .as("step 1: one more megabyte is refused by name")
                    .isEqualTo("host_capacity_reached");

                // 2. Inside a 100 MB pending-release scope, 100 MB fits and 101 does not:
                //    the credit is the declared amount, never "some headroom".
                InstanceCapacity.withPendingRelease(local, 100, () -> {
                    assertThat(refusalOf(() -> InstanceCapacity.reserve(local, 101)))
                        .as("step 2: the credit is exact -- one megabyte over it is refused")
                        .isEqualTo("host_capacity_reached");
                    InstanceCapacity.reserve(local, 100);
                    return null;
                });
                assertThat(InstanceCapacity.bookedMbOn(local))
                    .as("step 2: the bucket really carries the extra 100 MB -- a credit on the"
                        + " limit, never a release")
                    .isEqualTo(1100);

                // 3. Outside the scope the host is over budget and refuses everything again;
                //    another host's credit never reaches this one.
                assertThat(refusalOf(() -> InstanceCapacity.reserve(local, 1)))
                    .as("step 3: the credit died with its scope").isEqualTo("host_capacity_reached");
                assertThat(refusalOf(() -> InstanceCapacity.withPendingRelease(local + 1000, 500,
                        () -> {
                            InstanceCapacity.reserve(local, 1);
                            return null;
                        })))
                    .as("step 3: a credit declared for another host does not apply here")
                    .isEqualTo("host_capacity_reached");
            } finally {
                InstanceCapacity.release(local, room + 100);
                HostFixtures.makeLocalPlaceable(16384);
            }
        });
    }

    private static String refusalOf(Runnable body) {
        Violations refused = catchThrowableOfType(body::run, Violations.class);
        assertThat((Throwable) refused).as("the reservation must be refused").isNotNull();
        return refused.all().get(0).message().key();
    }
}
