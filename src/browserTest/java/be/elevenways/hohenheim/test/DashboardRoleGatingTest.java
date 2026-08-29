package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.OnboardingStep;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.ReconcileFindingModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.hohenheim.server.HohenheimRoles.Role;
import be.elevenways.hohenheim.server.cms.AttentionCollector;
import be.elevenways.hohenheim.server.cms.OnboardingCollector;
import be.elevenways.hohenheim.server.docker.DockerHealth;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dashboard offers only what an ENABLED role can act on: with the workload tiers off,
 * the readiness checklist and every host-tier attention item are ABSENT, while the proxy
 * tier's own items still speak; a dead Docker daemon is reported by every role that needs one.
 *
 * AIDEV-NOTE: observed live on starfleet -- instances/stacks/databases off, proxy on, and
 * the dashboard still said "Enrol a host" and listed Docker reconcile findings whose links
 * 404 (the sidebar had correctly dropped both lists). The collectors are asserted directly
 * rather than through a rendered page, because the role snapshot is process-global and this
 * flips it; the render half of both widgets is proven by AdminPagesTest.
 */
class DashboardRoleGatingTest {

    private static SqlDatasource datasource;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = TestDatabases.freshDatasource();
        HohenheimTestRuntime.ensureBooted();
    }

    @Test
    void everyDashboardOfferIsGatedOnTheRoleThatCanActOnIt() {
        Db.run(datasource, () -> {

            Set<Role> booted = EnumSet.noneOf(Role.class);
            for (Role role : Role.values()) {
                if (HohenheimRoles.enabled(role)) {
                    booted.add(role);
                }
            }

            ServerModel servers = Models.get(ServerModel.class);
            Row local = servers.findById(ServerModel.localServerId());
            String admission = local.get(ServerModel.ADMISSION);
            Row certificate = errorCertificate();
            Row finding = orphanFinding();

            try {
                local.set(ServerModel.ADMISSION, ServerModel.ADMISSION_BLOCKED);
                servers.save(local);

                // 1. A full node: the whole checklist, the host-tier attention items and
                //    the proxy tier's certificate failure all speak.
                roles(EnumSet.allOf(Role.class));
                assertThat(OnboardingCollector.collect())
                    .as("step 1: a full node walks all four readiness steps")
                    .hasSize(4);
                assertThat(titleKeys(AttentionCollector.collect()))
                    .as("step 1: host-tier and proxy-tier items alike are collected")
                    .contains("docker_orphans", "host_not_admitted", "certificate");

                // 2. The starfleet shape: proxy/DNS/firewall on, every workload tier off.
                //    No checklist at all, no reconciler findings, no unadmitted-host row --
                //    and the certificate failure the proxy role CAN act on stays.
                roles(EnumSet.of(Role.PROXY, Role.DNS, Role.FIREWALL));
                List<OnboardingStep> appliance = OnboardingCollector.collect();
                assertThat(appliance)
                    .as("step 2: a workload-less node is offered no readiness step")
                    .isEmpty();
                assertThat(OnboardingCollector.hasWork(appliance))
                    .as("step 2: so the checklist widget is not rendered at all")
                    .isFalse();
                List<String> applianceItems = titleKeys(AttentionCollector.collect());
                assertThat(applianceItems)
                    .as("step 2: no attention item points at a list this node has no route for")
                    .doesNotContain("docker_orphans", "docker_colliding", "docker_foreign",
                        "host_not_admitted");
                assertThat(applianceItems)
                    .as("step 2: the proxy tier's own failure is untouched")
                    .contains("certificate");

                // 3. A stacks-only node has hosts to enrol and admit, but no instance to
                //    create or deploy: the host half of the checklist returns, alone.
                roles(EnumSet.of(Role.STACKS));
                assertThat(titles(OnboardingCollector.collect()))
                    .as("step 3: the host steps belong to every workload tier")
                    .containsExactly("checklist_host", "checklist_admit");
                assertThat(titleKeys(AttentionCollector.collect()))
                    .as("step 3: and the host-tier attention items come back with them")
                    .contains("docker_orphans", "host_not_admitted");

                // 4. The instance tier alone restores the two steps only it can finish.
                roles(EnumSet.of(Role.INSTANCES));
                assertThat(titles(OnboardingCollector.collect()))
                    .as("step 4: the instance steps are the instance role's")
                    .containsExactly("checklist_host", "checklist_admit",
                        "checklist_create_instance", "checklist_deploy");

                // 5. A dead daemon is the instance tier's problem as much as the stack
                //    tier's, and a proxy-only node never asks. The process-wide probe
                //    cannot be made to fail here without a daemon, so the decision is
                //    exercised through an injected probe whose client cannot connect.
                assertThat(HohenheimRoles.dockerRequired())
                    .as("step 5: an instances-only node requires a daemon")
                    .isTrue();
                DockerHealth dead = new DockerHealth(() -> true, () -> {
                    throw new IllegalStateException("connect ECONNREFUSED /var/run/docker.sock");
                });
                dead.probe();
                AttentionItem daemon = AttentionCollector.dockerUnreachable(dead);
                assertThat(daemon)
                    .as("step 5: an unreachable daemon is a red item carrying the reason")
                    .isNotNull();
                assertThat(daemon.title().key())
                    .as("step 5: the item is the daemon-unreachable one")
                    .isEqualTo("docker_unreachable");
                roles(EnumSet.of(Role.PROXY));
                assertThat(HohenheimRoles.dockerRequired())
                    .as("step 5: a proxy-only node never asks after a daemon")
                    .isFalse();
                DockerHealth notNeeded = new DockerHealth(HohenheimRoles::dockerRequired, () -> {
                    throw new IllegalStateException("never constructed");
                });
                assertThat(notNeeded.probe())
                    .as("step 5: so its probe declares docker DISABLED without a client")
                    .isEqualTo(DockerHealth.Status.DISABLED);
                assertThat(AttentionCollector.dockerUnreachable(notNeeded))
                    .as("step 5: and no item is raised")
                    .isNull();
            } finally {
                roles(booted);
                local.set(ServerModel.ADMISSION, admission);
                servers.save(local);
                Models.get(CertificateModel.class).delete(certificate);
                Models.get(ReconcileFindingModel.class).delete(finding);
            }
        });
    }

    /** Declare the node's role set and snapshot it, the way a boot's settings load does. */
    private static void roles(Set<Role> enabled) {
        for (Role role : Role.values()) {
            HohenheimSettings.VALUES.setValue(role.setting(), enabled.contains(role));
        }
        HohenheimRoles.capture();
    }

    private static List<String> titleKeys(List<AttentionItem> items) {
        List<String> keys = new ArrayList<>();
        for (AttentionItem item : items) {
            keys.add(item.title().key());
        }
        return keys;
    }

    private static List<String> titles(List<OnboardingStep> steps) {
        List<String> keys = new ArrayList<>();
        for (OnboardingStep step : steps) {
            keys.add(step.title().key());
        }
        return keys;
    }

    private static Row errorCertificate() {
        Row row = Models.get(CertificateModel.class).createEmptyRow();
        row.set(CertificateModel.NICE_NAME, "role-gating.example");
        row.set(CertificateModel.DOMAIN_NAMES_TEXT, "role-gating.example");
        row.set(CertificateModel.PROVIDER, CertificateModel.PROVIDER_CUSTOM);
        row.set(CertificateModel.STATUS, CertificateModel.STATUS_ERROR);
        row.set(CertificateModel.RENEWAL_ERROR, "the ACME server refused the order");
        Models.get(CertificateModel.class).save(row);
        return row;
    }

    private static Row orphanFinding() {
        Row row = Models.get(ReconcileFindingModel.class).createEmptyRow();
        row.set(ReconcileFindingModel.SERVER_NAME, "local");
        row.set(ReconcileFindingModel.KIND, "container");
        row.set(ReconcileFindingModel.RESOURCE_NAME, "hh-role-gating");
        row.set(ReconcileFindingModel.BUCKET, ReconcileFindingModel.BUCKET_ORPHANED);
        row.set(ReconcileFindingModel.EVIDENCE, "owner_label");
        Models.get(ReconcileFindingModel.class).save(row);
        return row;
    }
}
