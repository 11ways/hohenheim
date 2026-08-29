package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.OnboardingStep;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.cms.OnboardingCollector;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dashboard checklist's first step is done when a host is ADMITTED, not merely stored:
 * an enrolled-but-blocked host used to render the step green above a blocked second step.
 */
class OnboardingChecklistTest {

    private static SqlDatasource datasource;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = TestDatabases.freshDatasource();
        HohenheimTestRuntime.ensureBooted();
    }

    @Test
    void theHostStepIsDoneOnlyOnceAHostIsAdmitted() {
        Db.run(datasource, () -> {
            ServerModel servers = Models.get(ServerModel.class);
            Row local = servers.findById(ServerModel.localServerId());
            String admission = local.get(ServerModel.ADMISSION);
            try {
                // 1. The only host is enrolled but BLOCKED: the step is still to do, and its
                //    detail says so in those words rather than reading as a fresh install.
                local.set(ServerModel.ADMISSION, ServerModel.ADMISSION_BLOCKED);
                servers.save(local);
                List<OnboardingStep> blocked = OnboardingCollector.collect();
                assertThat(blocked.get(0).state())
                    .as("step 1: an enrolled but unadmitted host does not complete the step")
                    .isEqualTo(OnboardingStep.TODO);
                assertThat(blocked.get(0).detail().key())
                    .as("step 1: and the detail names the pending admission")
                    .isEqualTo("checklist_host_pending");
                assertThat(blocked.get(1).isDone())
                    .as("step 1: the admit step agrees").isFalse();

                // 2. Admitting the host completes the step, with the ordinary detail.
                local.set(ServerModel.ADMISSION, ServerModel.ADMISSION_ADMITTED);
                servers.save(local);
                List<OnboardingStep> admitted = OnboardingCollector.collect();
                assertThat(admitted.get(0).state())
                    .as("step 2: an admitted host completes the step")
                    .isEqualTo(OnboardingStep.DONE);
                assertThat(admitted.get(0).detail().key())
                    .as("step 2: with the plain detail")
                    .isEqualTo("checklist_host_detail");
            } finally {
                local.set(ServerModel.ADMISSION, admission);
                servers.save(local);
            }
        });
    }
}
