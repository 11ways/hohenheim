package be.elevenways.hohenheim.test.host;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;

/**
 * Test-side operator decisions over the host record: the admission gate refuses
 * instance placement on any host that was not preflighted and admitted, so a test
 * that deploys must first do what an operator would.
 */
public final class HostFixtures {

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
    }
}
