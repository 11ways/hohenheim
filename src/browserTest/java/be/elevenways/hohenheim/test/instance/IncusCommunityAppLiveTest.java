package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.incus.IncusClient;
import be.elevenways.hohenheim.server.instance.CommunityScripts;
import be.elevenways.hohenheim.server.instance.InstanceAppUpdates;
import be.elevenways.hohenheim.server.instance.InstanceConsoles;
import be.elevenways.hohenheim.server.instance.InstanceInstalls;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.InstanceTemplates;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.LiveIdOffsets;
import be.elevenways.hohenheim.test.host.LiveIncusHost;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Phase 5b's gate against the REAL Incus host: two UNMODIFIED community-scripts apps
 * (Gotify, AdGuard Home) install from their PINNED install scripts through the
 * {@code $FUNCTIONS_FILE_PATH} shim into Debian system containers, come up reachable
 * and functional (asserted from inside the container AND from the host), the deferred
 * console readiness matcher flips starting->running (and errors on a line that never
 * appears), the in-place update_script lane runs, and the vocabulary gate's RUNTIME
 * backstop fails an unknown helper loudly. One container at a time (3.9 GiB host);
 * everything created is destroyed and absence is asserted at the daemon.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IncusCommunityAppLiveTest {

    private static final String HOST = "live-incus-apps";

    private static SqliteDatasource datasource;
    private static LiveIncusHost remote;
    private static String enrolledFingerprint;

    @BeforeAll
    static void setUp() throws Exception {
        remote = LiveIncusHost.configured();
        assumeTrue(remote != null, "no live incus host enrolled at " + LiveIncusHost.CONFIG);

        File db = File.createTempFile("hohenheim-incus-community-live", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        LiveIdOffsets.apply(datasource);
        HohenheimTestRuntime.ensureBooted();
        Db.run(datasource, () -> enrolledFingerprint =
            remote.enrollThroughProduct(HOST, "hohenheim-live-community"));
    }

    @AfterAll
    static void tearDown() {
        InstanceConsoles.overrideTimingsForTest(null, null);
        if (remote != null && enrolledFingerprint != null) {
            try {
                remote.removeTrustEntry(enrolledFingerprint);
            } catch (IOException ignored) {
                // nothing enrolled, nothing to remove
            }
        }
    }

    @Test
    @Order(1)
    void gotifyInstallsRunsAndUpdatesThroughThePinnedScripts() {
        Db.run(datasource, () -> {
            // 1. Import from the vendored catalog and approve (operator act; the
            //    approval GATE itself is proven by CommunityScriptCatalogTest and
            //    TenantInstanceSurfaceTest). The readiness line targets the getty
            //    banner: on a system container the CONSOLE speaks for the SYSTEM.
            int templateId = CommunityScripts.importApp("gotify");
            Row template = Models.get(InstanceTemplateModel.class).findById(templateId);
            template.set(InstanceTemplateModel.APPROVED_AT, Instant.now());
            template.set(InstanceTemplateModel.READINESS_LINE, "Debian GNU/Linux");
            Models.get(InstanceTemplateModel.class).save(template);

            int id = new InstanceTemplates().createFromTemplate(template,
                "community-gotify", null, Map.of(), null);
            String handle = "hohenheim-instance-" + id;
            InstanceService service = new InstanceService();
            IncusClient incus = new ServerService().incusClientFor(HOST);

            try {
                assertThat((String) Models.get(InstanceModel.class).findById(id)
                        .get(InstanceModel.INSTALL_STATE))
                    .as("step 1: a catalog template creates install-PENDING")
                    .isEqualTo(InstanceModel.INSTALL_PENDING);

                // 2. The REAL pinned install script runs verbatim through the shim.
                new InstanceInstalls().install(id);
                assertThat((String) Models.get(InstanceModel.class).findById(id)
                        .get(InstanceModel.INSTALL_STATE))
                    .as("step 2: the unmodified gotify install script completed")
                    .isEqualTo(InstanceModel.INSTALL_INSTALLED);
                assertThat(service.liveStatus(id).state())
                    .as("step 2: install leaves the system container STOPPED")
                    .isEqualTo(ContainerState.STOPPED);

                // 3. Deploy: the DEFERRED console attach (Incus refuses a console on a
                //    stopped instance) arms the matcher after start, backlog-seeded, and
                //    the record must flip starting -> running off the console banner.
                assertThat(service.deploy(id).state())
                    .as("step 3: deploy starts the system container")
                    .isEqualTo(ContainerState.RUNNING);
                awaitStatus(id, InstanceModel.STATUS_RUNNING, 60_000,
                    "step 3: the readiness line flipped starting -> running");
                assertThat(execIn(handle, "test -x /opt/gotify/gotify-linux-amd64"
                        + " && echo deployed"))
                    .as("step 3: the release binary landed in the rootfs")
                    .contains("deployed");
                assertThat(execIn(handle, "cat /usr/bin/update"))
                    .as("step 3: /usr/bin/update refuses instead of curl-ing main")
                    .contains("managed by hohenheim");

                // 4. Functional and reachable: the app answers ON LOCALHOST inside the
                //    container AND from the HOST over the bridge address.
                String inside = execIn(handle,
                    "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1/version");
                assertThat(inside)
                    .as("step 4: gotify answers inside the container").contains("200");
                String address = containerIpv4(incus, handle);
                assertThat(hostCurl("curl", "-s", "--max-time", "10",
                        "http://" + address + "/version"))
                    .as("step 4: gotify is reachable from the host at " + address)
                    .contains("\"version\"");

                // 5. The adopted update_script() capability: the in-place update lane
                //    runs the PINNED update script inside the RUNNING system. Freshly
                //    installed means the honest outcome is up-to-date, asserted on the
                //    script's own output, and the service must still answer after.
                String updateOutput = new InstanceAppUpdates().update(id);
                assertThat(updateOutput)
                    .as("step 5: the update script reports the version verdict")
                    .containsIgnoringCase("up-to-date");
                assertThat(execIn(handle,
                        "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1/version"))
                    .as("step 5: gotify still answers after the update run")
                    .contains("200");

                // 6. COUNTERFACTUAL of the readiness matcher: a line that never appears
                //    must land the record in ERROR, never a hopeful RUNNING.
                service.stop(id);
                template.set(InstanceTemplateModel.READINESS_LINE,
                    "NEVER-PRINTED-LINE-73246");
                Models.get(InstanceTemplateModel.class).save(template);
                InstanceConsoles.overrideTimingsForTest(8_000L, null);
                service.deploy(id);
                awaitStatus(id, InstanceModel.STATUS_ERROR, 30_000,
                    "step 6: an unobserved readiness line stamps ERROR");
                InstanceConsoles.overrideTimingsForTest(null, null);

                // 7. Verified teardown: absent at the daemon, record soft-deleted.
                service.destroy(id);
                assertThat(catchThrowable(() -> incus.instance(handle)))
                    .as("step 7: the instance is ABSENT at the daemon")
                    .isInstanceOfSatisfying(IncusClient.ApiException.class,
                        e -> assertThat(e.isNotFound()).isTrue());
            } finally {
                InstanceConsoles.overrideTimingsForTest(null, null);
                remote.forceDelete(handle);
            }
        });
    }

    @Test
    @Order(2)
    void adguardInstallsAndAnswersAsTheSecondCatalogApp() {
        Db.run(datasource, () -> {
            int templateId = CommunityScripts.importApp("adguard");
            Row template = Models.get(InstanceTemplateModel.class).findById(templateId);
            template.set(InstanceTemplateModel.APPROVED_AT, Instant.now());
            Models.get(InstanceTemplateModel.class).save(template);

            int id = new InstanceTemplates().createFromTemplate(template,
                "community-adguard", null, Map.of(), null);
            String handle = "hohenheim-instance-" + id;
            InstanceService service = new InstanceService();
            IncusClient incus = new ServerService().incusClientFor(HOST);

            try {
                // 1. The second unmodified app installs from its pinned script.
                new InstanceInstalls().install(id);
                assertThat((String) Models.get(InstanceModel.class).findById(id)
                        .get(InstanceModel.INSTALL_STATE))
                    .as("step 1: the unmodified adguard install script completed")
                    .isEqualTo(InstanceModel.INSTALL_INSTALLED);

                // 2. Deploy and answer: AdGuard's setup wizard listens on 3000.
                assertThat(service.deploy(id).state())
                    .as("step 2: deploy starts the container")
                    .isEqualTo(ContainerState.RUNNING);
                String code = execIn(handle, "for i in $(seq 1 30); do"
                    + " c=$(curl -s -o /dev/null -w '%{http_code}'"
                    + " http://127.0.0.1:3000/); [ \"$c\" = 200 ] || [ \"$c\" = 302 ]"
                    + " && { echo answered-$c; exit 0; }; sleep 1; done; echo gave-up-$c");
                assertThat(code)
                    .as("step 2: adguard answers inside the container")
                    .contains("answered");
                String address = containerIpv4(incus, handle);
                assertThat(hostCurl("curl", "-s", "-o", "/dev/null", "-w",
                        "%{http_code}", "--max-time", "10",
                        "http://" + address + ":3000/"))
                    .as("step 2: adguard is reachable from the host at " + address)
                    .containsAnyOf("200", "302");

                // 3. Teardown, verified at the daemon.
                service.stop(id);
                service.destroy(id);
                assertThat(catchThrowable(() -> incus.instance(handle)))
                    .as("step 3: the instance is ABSENT at the daemon")
                    .isInstanceOfSatisfying(IncusClient.ApiException.class,
                        e -> assertThat(e.isNotFound()).isTrue());
            } finally {
                remote.forceDelete(handle);
            }
        });
    }

    /**
     * The RUNTIME backstop of the vocabulary gate: a helper the static scan cannot
     * know (not in the pinned upstream namespace) must fail the install LOUDLY with
     * the shim's named message -- never a silently degraded app.
     */
    @Test
    @Order(3)
    void unknownHelperFailsTheInstallLoudlyAtRuntime() {
        Db.run(datasource, () -> {
            Row template = Models.get(InstanceTemplateModel.class).createEmptyRow();
            template.set(InstanceTemplateModel.NAME, "community-unknown-helper");
            template.set(InstanceTemplateModel.KIND, "hohenheim:incus_container");
            template.set(InstanceTemplateModel.SETTINGS,
                Map.of("image", "debian/13", "memory_limit_mb", 256));
            template.set(InstanceTemplateModel.INSTALL_SCRIPT,
                "source /dev/stdin <<<\"$FUNCTIONS_FILE_PATH\"\n"
                    + "color\ncatch_errors\nhh_helper_that_never_existed\n"
                    + "echo THIS-MUST-NEVER-PRINT\n");
            template.set(InstanceTemplateModel.APPROVED_AT, Instant.now());
            Models.get(InstanceTemplateModel.class).save(template);

            int id = new InstanceTemplates().createFromTemplate(template,
                "community-unknown-helper", null, Map.of(), null);
            String handle = "hohenheim-instance-" + id;

            try {
                Throwable failed = catchThrowable(() -> new InstanceInstalls().install(id));
                assertThat(failed)
                    .as("step 1: the install run FAILS instead of degrading silently")
                    .isInstanceOf(Violations.class);
                Row instance = Models.get(InstanceModel.class).findById(id);
                assertThat((String) instance.get(InstanceModel.INSTALL_STATE))
                    .as("step 1: the record carries the failure")
                    .isEqualTo(InstanceModel.INSTALL_FAILED);
                assertThat((String) instance.get(InstanceModel.INSTALL_ERROR))
                    .as("step 2: the output NAMES the unimplemented helper and never"
                        + " reached the code after it")
                    .contains("hh_helper_that_never_existed")
                    .contains("does not implement it")
                    .doesNotContain("THIS-MUST-NEVER-PRINT");
            } finally {
                try {
                    new InstanceService().destroy(id);
                } catch (RuntimeException alreadyGone) {
                    // the daemon-side force delete below is the authority
                }
                remote.forceDelete(handle);
            }
        });
    }

    /** After everything: no hohenheim-instance-* residue remains AT THE DAEMON. */
    @Test
    @Order(4)
    void theDaemonIsEmptyOfEverythingThisSuiteCreated() {
        Db.run(datasource, () -> {
            IncusClient incus = new ServerService().incusClientFor(HOST);
            try {
                assertThat(incus.instances())
                    .as("no instance created by this suite survives at the daemon")
                    .noneMatch(instance -> String.valueOf(instance.get("name"))
                        .startsWith("hohenheim-instance-"));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    // -- plumbing -------------------------------------------------------------

    private static String hostCurl(String... command) {
        try {
            return remote.hostCommand(command);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String execIn(String handle, String command) {
        try {
            return remote.exec(handle, command);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Poll the RECORD's status (the fenced writes are async off the console pump). */
    private static void awaitStatus(int instanceId, String expected, long timeoutMs,
                                    String description) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String last = "";
        while (System.currentTimeMillis() < deadline) {
            last = Models.get(InstanceModel.class).findById(instanceId)
                .get(InstanceModel.STATUS);
            if (expected.equals(last)) {
                return;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(last).as(description).isEqualTo(expected);
    }

    /** The container's bridge IPv4, read from the daemon's state object. */
    private static String containerIpv4(IncusClient incus, String handle) {
        try {
            Map<String, Object> state = incus.instanceState(handle);
            if (state.get("network") instanceof Map<?, ?> network) {
                for (Object value : network.values()) {
                    if (!(value instanceof Map<?, ?> iface)
                            || !(iface.get("addresses") instanceof List<?> list)) {
                        continue;
                    }
                    for (Object entry : list) {
                        if (entry instanceof Map<?, ?> addr
                                && "inet".equals(addr.get("family"))
                                && "global".equals(addr.get("scope"))) {
                            return String.valueOf(addr.get("address"));
                        }
                    }
                }
            }
            throw new IllegalStateException("no global IPv4 on " + handle + ": " + state);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
