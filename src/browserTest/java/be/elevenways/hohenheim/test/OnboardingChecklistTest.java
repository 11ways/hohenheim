package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.OnboardingState;
import be.elevenways.hohenheim.OnboardingStep;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.cms.AttentionCollector;
import be.elevenways.hohenheim.server.cms.OnboardingCollector;
import be.elevenways.hohenheim.server.database.ControlPlaneBackups;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
            // 1. Nothing runs and no website exists: putting the first app online is still to do.
            assertThat(putOnlineStep().state())
                .as("step 1: an empty install has nothing online").isEqualTo(OnboardingState.TODO);

            // 2. A website with no address yet serves nobody: nothing is online.
            SiteModel sites = Models.get(SiteModel.class);
            Row site = sites.createEmptyRow();
            site.set(SiteModel.NAME, "Checklist redirect");
            site.set(SiteModel.SLUG, "checklist-redirect");
            site.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
            site.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
            site.set(SiteModel.STATUS, SiteModel.STATUS_ACTIVE);
            site.set(SiteModel.ENABLED, true);
            sites.save(site);
            Model domains = Models.get(SiteDomainModel.class);
            Row domain = null;
            try {
                assertThat(putOnlineStep().state())
                    .as("step 2: a website without an address is not online").isEqualTo(OnboardingState.TODO);

                // 3. With an address it serves its visitors, though no workload runs: a redirect or a proxy put
                //    online counts as something online.
                domain = domains.createEmptyRow();
                domain.set(SiteDomainModel.SITE_ID, site.get(SiteModel.ID));
                domain.set(SiteDomainModel.HOSTNAME, "checklist-redirect.example.test");
                domains.save(domain);
                assertThat(putOnlineStep().state())
                    .as("step 3: a serving website completes the put-online step").isEqualTo(OnboardingState.DONE);

                // 4. Switched off, it serves nobody again, and the checklist says so (D7f: the step reflects an app
                //    that serves or runs, never one that once existed).
                site.set(SiteModel.ENABLED, false);
                sites.save(site);
                assertThat(putOnlineStep().state())
                    .as("step 4: a switched-off website is not online").isEqualTo(OnboardingState.TODO);
            } finally {
                if (domain != null) {
                    domains.delete(domain);
                }
                sites.delete(site);
            }
        });
    }

    @Test
    void theBackupsStepReadsTheAttentionItemsFact() {
        Db.run(datasource, () -> {
            // 1. Without an off-host destination the step is to do, before the first app, and it leads to the same
            //    place as the attention item, so the two can never disagree.
            List<OnboardingStep> steps = OnboardingCollector.collect();
            OnboardingStep backups = steps.stream()
                .filter(step -> "checklist_backups".equals(step.title().key()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("step 1: the checklist offers the backups step"));
            assertThat(backups.state()).as("step 1: no destination is chosen on a fresh install")
                .isEqualTo(ControlPlaneBackups.configuredDestinationName() == null
                    ? OnboardingState.TODO : OnboardingState.DONE);
            int putOnline = -1;
            for (int i = 0; i < steps.size(); i++) {
                if ("checklist_put_online".equals(steps.get(i).title().key())) {
                    putOnline = i;
                }
            }
            assertThat(putOnline).as("step 1: the checklist offers the put-online step").isNotNegative();
            assertThat(steps.indexOf(backups)).as("step 1: before putting the first app online")
                .isLessThan(putOnline);
            List<AttentionItem> items = new ArrayList<>();
            AttentionCollector.controlPlaneBackupDestination(items);
            if (!items.isEmpty()) {
                assertThat(Objects.requireNonNull(backups.target()).toUrl())
                    .as("step 1: and leads where the attention item leads")
                    .isEqualTo(Objects.requireNonNull(items.get(0).target()).toUrl());
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
