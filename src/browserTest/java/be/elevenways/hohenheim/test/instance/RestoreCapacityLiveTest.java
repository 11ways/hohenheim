package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.instance.RestoreCapacity;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.host.LiveIncusHost;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violation;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import org.junit.jupiter.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The PROBE half of the capacity gate against a real Incus daemon: the storage pool the
 * default profile's root device names is queried for real, and the figure it returns is
 * what the restore and migration paths judge against.
 *
 * RestoreCapacityTest owns the arithmetic and both refusal identities daemon-free; this
 * class exists because that split would be worthless if the probe never ran -- a gate
 * whose measurement is only ever a test double measures nothing.
 */
@Tag("slow") // live lane: needs a real daemon/host/image; runs via `zenit-dev test --all`
class RestoreCapacityLiveTest {

    private static final String HOST = "live-capacity-incus";
    private static final String CLIENT = "hohenheim-live-capacity";

    private static SqliteDatasource datasource;
    private static LiveIncusHost remote;

    @BeforeAll
    static void setUp() throws Exception {
        remote = LiveIncusHost.configured();
        LiveLane.require(LiveLane.Need.INCUS_HOST, remote != null,
            "no live incus host enrolled at " + LiveIncusHost.CONFIG);

        File db = File.createTempFile("hohenheim-capacity-live-test", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        // ONE database per test class: the controller identity (and therefore every
        // daemon resource name) resolves through the CURRENT datasource, and a Db scope
        // is thread-local -- so a second, unregistered database would hand any
        // thread-hopping work a different controller's token than the records came from.
        Datasources.register(Datasources.DEFAULT, datasource);
        HohenheimTestRuntime.ensureBooted();
    }

    /**
     * AIDEV-NOTE: this journey used to prove "the refusal quotes the real measurement" by
     * probing the pool, calling {@code require} (which probes AGAIN) and demanding the two
     * figures be BYTE-IDENTICAL. That is only true on a disk nothing else touches: in the
     * parallel live lane another test's cleanup freed ~315 MB between the two reads and the
     * step failed, while the same test passed in isolation. The claim was never wrong, the
     * MEASUREMENT SHAPE was -- one assertion was quietly covering two different facts.
     * They are now separated, and neither can drift: step 3 INJECTS the one measurement
     * into {@link RestoreCapacity#judge} (the decision half is pure, so byte-equality is
     * exact by construction) and step 4 bounds {@code require}'s quote by probes taken on
     * BOTH sides of it (the wiring half, tolerant only of movement the pool really made).
     * Do NOT re-collapse them into one {@code require} call with an equality assertion.
     */
    @Test
    void theRealPoolIsMeasuredAndTheHeadroomIsJudgedAgainstIt() {
        Db.run(datasource, () -> {
            String certificateFingerprint = null;
            try {
                certificateFingerprint = remote.enrollThroughProduct(HOST, CLIENT);
                Row host = Models.get(ServerModel.class).findByName(HOST);
                int serverId = host.get(ServerModel.ID);

                // 1. The probe answers from the DAEMON's own storage pool, not from a
                //    stub and not from the controller's filesystem.
                long available = RestoreCapacity.availableBytesOn(serverId);
                assertThat(available)
                    .as("step 1: the real pool reports free space, and it is a plausible"
                        + " figure rather than a zero that would refuse everything")
                    .isGreaterThan(1024L * 1024L * 1024L);

                // 2. A payload that comfortably fits passes the whole gate.
                assertThat(catchThrowable(() -> RestoreCapacity.require(serverId, 1024L)))
                    .as("step 2: a kilobyte restore is not refused by a host with GBs free")
                    .isNull();

                // 3. Asking for exactly what is free REFUSES: the 1.2x extraction
                //    headroom is judged against the measured figure, so the boundary
                //    case is decided by real data rather than by a chosen constant.
                //    The figure measured in step 1 is INJECTED rather than re-probed --
                //    see the note above the method for why re-probing was wrong.
                Throwable exact = catchThrowable(
                    () -> RestoreCapacity.judge(serverId, available, available));
                assertThat(exact)
                    .as("step 3: a restore the size of the free space is refused")
                    .isInstanceOf(Violations.class);
                Violation refusal = firstRefusal((Violations) exact);
                assertThat(refusal.message().key())
                    .as("step 3: by name").isEqualTo("restore_capacity");
                assertThat(String.valueOf(refusal.message().args().get("available")))
                    .as("step 3: quoting the very figure it judged, to the byte -- a"
                        + " refusal that reports a number it did not decide on is a"
                        + " refusal an operator cannot act on")
                    .isEqualTo(String.valueOf(available));
                assertThat(Long.parseLong(
                        String.valueOf(refusal.message().args().get("needed"))))
                    .as("step 3: against a need that includes the headroom")
                    .isGreaterThan(available);

                // 4. THE WIRING, which step 3 deliberately no longer covers: the figure
                //    `require` puts in its refusal comes from the LIVE PROBE. An absurd
                //    payload is used so the refusal cannot fail to fire whatever the pool
                //    is doing, and the quote is bounded by probes taken on both sides of
                //    the call -- the only movement tolerated is movement that really
                //    happened, so a zero, a constant, the `needed` figure or the
                //    CONTROLLER's own filesystem still fails here.
                long before = RestoreCapacity.availableBytesOn(serverId);
                Throwable absurd = catchThrowable(
                    () -> RestoreCapacity.require(serverId, 1_000_000_000_000_000L));
                long after = RestoreCapacity.availableBytesOn(serverId);
                assertThat(absurd).as("step 4: a petabyte restore is refused")
                    .isInstanceOf(Violations.class);
                Violation absurdRefusal = firstRefusal((Violations) absurd);
                assertThat(absurdRefusal.message().key())
                    .as("step 4: by the same name").isEqualTo("restore_capacity");
                assertThat(Long.parseLong(
                        String.valueOf(absurdRefusal.message().args().get("available"))))
                    .as("step 4: quoting a figure the pool really held while the refusal"
                        + " was built, so the probe is what reaches the operator")
                    .isBetween(Math.min(before, after), Math.max(before, after));
            } finally {
                if (certificateFingerprint != null) {
                    try {
                        System.out.println("=== cleanup: shared objects -> "
                            + remote.releaseControllerSharedObjects());
                        System.out.println("=== cleanup: authorized_keys -> "
                            + remote.releaseAuthorizedKeys());
                        remote.removeTrustEntry(certificateFingerprint);
                    } catch (Exception ignored) {
                        // Reported by the next run's trust listing; never masks a failure.
                    }
                }
                Row host = Models.get(ServerModel.class).findByName(HOST);
                if (host != null) {
                    Models.get(ServerModel.class).delete(host.get(ServerModel.ID));
                }
            }
        });
    }

    private static Violation firstRefusal(Violations violations) {
        assertThat(violations.all()).isNotEmpty();
        return violations.all().get(0);
    }
}
