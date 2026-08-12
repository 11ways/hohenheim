package be.elevenways.hohenheim.test.host;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.host.HostPostureAcknowledgement;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.Accountability;
import org.checkerframework.checker.nullness.qual.NonNull;

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
