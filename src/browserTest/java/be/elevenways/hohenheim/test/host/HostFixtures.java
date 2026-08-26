package be.elevenways.hohenheim.test.host;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.host.HostPostureAcknowledgement;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.Accountability;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Test-side operator decisions over the host record: the admission gate refuses
 * instance placement on any host that was not preflighted, admitted AND -- since the
 * posture acknowledgement landed -- whose shared-container risk nobody accepted, so a
 * test that deploys must first do what an operator would.
 */
public final class HostFixtures {

    /** The attribution a fixture acknowledges under; a real one is required, never null. */
    private static final Accountability OPERATOR =
        new Accountability("user:1", "Test operator", null, null, "test");

    private HostFixtures() {
    }

    /**
     * Make the implicit local host PLACEABLE: admitted, acknowledged AND measured.
     *
     * AIDEV-NOTE: admission alone is not placement. The chooser needs a memory budget to
     * ration against and answers {@code host_capacity_unproven} for a host nobody ever
     * measured, so a fixture that only admits leaves every create refused for a reason
     * that has nothing to do with what it is testing. Idempotent, like {@link #admitLocal},
     * and the report goes through {@link HostPreflight#store} rather than hand-writing the
     * capabilities shape.
     *
     * @param memoryMb the measured total the budget is derived from
     */
    public static void makeLocalPlaceable(long memoryMb) {
        admitLocal();
        HostPreflight.store(ServerModel.MODE_LOCAL, new HostPreflight.Report(
            List.of(new HostPreflight.Check("daemon", HostPreflight.STATUS_PASS, true,
                "fixture: reachable")),
            Map.of(HostPreflight.MEM_TOTAL_FACT, memoryMb * 1024L * 1024L),
            true, Instant.now(), null));
    }

    /** Admit the implicit local host for tenant placement (posture shared_container). */
    public static void admitLocal() {
        int id = ServerModel.localServerId();
        Row row = Models.get(ServerModel.class).findById(id);
        row.set(ServerModel.PREFLIGHT_OK, true);
        row.set(ServerModel.ADMISSION, ServerModel.ADMISSION_ADMITTED);
        row.set(ServerModel.POSTURE, ServerModel.POSTURE_SHARED_CONTAINER);
        Models.get(ServerModel.class).save(row);
        acknowledgePosture(row);
    }

    /**
     * ENSURE a host carries a current risk acknowledgement, performing the operator act as
     * a named operator when it does not -- the same production entry point the row action
     * uses.
     *
     * AIDEV-NOTE: idempotent on purpose, because {@link #admitLocal} is. The implicit local
     * host is ONE row shared by every class in a browser-test JVM, so this runs many times
     * against the same record; the production act refuses a redundant acknowledgement by
     * name ({@code posture_already_acknowledged}), which is right for an operator clicking
     * twice and wrong for a fixture asserting a precondition. Ten slow-lane classes failed
     * on exactly that before the guard was added.
     *
     * AIDEV-NOTE: the posture must already be SAVED before this runs. The model's
     * before-validate hook erases an acknowledgement that does not name the row's posture,
     * so stamping one in the same save as the posture change would clear it again -- which
     * is the hook doing its job, not a bug to work around.
     */
    public static void acknowledgePosture(@NonNull Row server) {
        if (!ServerModel.postureNeedsAcknowledgement(server)
                || ServerModel.postureAcknowledged(server)) {
            return;
        }
        Accountability.runAs(OPERATOR, () -> HostPostureAcknowledgement.record(server));
    }
}
