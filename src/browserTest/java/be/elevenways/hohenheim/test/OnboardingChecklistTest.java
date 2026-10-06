package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.OnboardingState;
import be.elevenways.hohenheim.OnboardingStep;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.cms.OnboardingCollector;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
                    .isEqualTo(OnboardingState.TODO);
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
                    .isEqualTo(OnboardingState.DONE);
                assertThat(admitted.get(0).detail().key())
                    .as("step 2: with the plain detail")
                    .isEqualTo("checklist_host_detail");
            } finally {
                local.set(ServerModel.ADMISSION, admission);
                servers.save(local);
            }
        });
    }

    @Test
    void anAddressPutOnlineCompletesThePutOnlineStepWithoutAWorkload() {
        Db.run(datasource, () -> {
            // 1. Nothing runs and no website is enabled: putting the first app online is still to do.
            assertThat(putOnlineStep().state())
                .as("step 1: an empty install has nothing online").isEqualTo(OnboardingState.TODO);

            // 2. A redirect or proxy put online is a website with no workload; it counts as something online.
            SiteModel sites = Models.get(SiteModel.class);
            Row site = sites.createEmptyRow();
            site.set(SiteModel.NAME, "Checklist redirect");
            site.set(SiteModel.SLUG, "checklist-redirect");
            site.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
            site.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
            site.set(SiteModel.STATUS, SiteModel.STATUS_ACTIVE);
            site.set(SiteModel.ENABLED, true);
            sites.save(site);
            try {
                assertThat(putOnlineStep().state())
                    .as("step 2: an enabled website completes the put-online step").isEqualTo(OnboardingState.DONE);
            } finally {
                sites.delete(site);
            }
        });
    }

    private static OnboardingStep putOnlineStep() {
        return OnboardingCollector.collect().stream()
            .filter(step -> "checklist_put_online".equals(step.title().key()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the checklist offers the put-online step"));
    }
}
