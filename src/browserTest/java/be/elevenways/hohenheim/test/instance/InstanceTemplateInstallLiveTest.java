package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceFileModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateFileModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.InstanceTemplateVariableModel;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.instance.InstanceInstalls;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.InstanceTemplates;
import be.elevenways.hohenheim.server.instance.InstanceVariables;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.server.runtime.InstanceNetworks;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.hohenheim.test.network.PrivateNetns;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The install/reinstall lifecycle against a REAL daemon, as one journey: create from a
 * template (generated secret variable, config file with placeholders), interrupt the
 * install and PROVE the variables/credentials survive (the failure class this wave
 * exists to kill), resume it, deploy with env injection + staged files asserted from
 * INSIDE the container, then reinstall under preserve and under clear policies and
 * assert what happened to the data volume.
 */
class InstanceTemplateInstallLiveTest {

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);

    private static SqliteDatasource datasource;
    private static PrivateNetns netns;

    @BeforeAll
    static void setUp() throws Exception {
        File db = File.createTempFile("hohenheim-template-live-test", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        HohenheimTestRuntime.ensureBooted();
        if (PrivateNetns.available()) {
            netns = new PrivateNetns();
            WorkloadNetworkPolicy.overrideForTest(netns.enforcingPolicy());
        }
    }

    @AfterAll
    static void tearDown() {
        WorkloadNetworkPolicy.overrideForTest(null);
        if (netns != null) {
            netns.close();
            netns = null;
        }
    }

    @Test
    void installLifecycleSurvivesInterruptionAndHonorsReinstallPolicy() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(SOCKET),
            "Docker socket not present");
        DockerClient docker = new DockerClient();
        org.junit.jupiter.api.Assumptions.assumeTrue(imagePresent(docker, "alpine:latest"),
            "alpine:latest not present locally");
        org.junit.jupiter.api.Assumptions.assumeTrue(netns != null,
            "no private netns: the instance tier refuses to deploy unprotected");

        Db.run(datasource, () -> {
            HostFixtures.admitLocal();

            // 1. The template: alpine workload with a data volume, an install step that
            //    appends to /data/runs, a generated secret + a defaulted plain variable,
            //    and a config file with {{}} placeholders.
            int templateId = template();
            Row template = Models.get(InstanceTemplateModel.class).findById(templateId);

            int id = new InstanceTemplates().createFromTemplate(template,
                "template-live", null,
                Map.of("GREETING", "hello-hohenheim"), null);
            String handle = "hohenheim-instance-" + id;
            String volumeName = handle + "-vol-data";
            InstanceService service = new InstanceService();

            try {
                Row instance = Models.get(InstanceModel.class).findById(id);
                assertThat((String) instance.get(InstanceModel.INSTALL_STATE))
                    .as("step 1: a template with an install step creates install-PENDING")
                    .isEqualTo(InstanceModel.INSTALL_PENDING);
                Map<String, String> variables = new InstanceVariables().valuesFor(id);
                String token = variables.get("SHARED_TOKEN");
                assertThat(token)
                    .as("step 1: the blank secret variable was generated").isNotBlank();
                assertThat(variables.get("GREETING"))
                    .as("step 1: the plain variable stored the submitted value")
                    .isEqualTo("hello-hohenheim");

                // 2. Deploy REFUSES before install: HOST state must show no container
                //    was created -- refusal and nothing-happened asserted separately.
                Throwable tooEarly = catchThrowable(() -> service.deploy(id));
                assertThat(tooEarly).as("step 2: deploy before install is a refusal")
                    .isInstanceOf(Violations.class);
                assertThat(catchThrowable(() -> docker.inspectContainer(handle)))
                    .as("step 2: and the daemon has NO container for it")
                    .isInstanceOfSatisfying(DockerClient.ApiException.class,
                        e -> assertThat(e.isNotFound()).isTrue());

                // 3. INTERRUPT the install (the injected crash fires after the durable
                //    'installing' stamp): the record shows the interruption, and --
                //    the counterfactual this wave exists for -- the STORED variables,
                //    the secret's value included, are UNTOUCHED.
                InstanceInstalls interrupted = new InstanceInstalls(service, () -> {
                    throw new RuntimeException("simulated controller crash mid-install");
                });
                Throwable crash = catchThrowable(() -> interrupted.install(id));
                assertThat(crash).as("step 3: the interruption propagates")
                    .hasMessageContaining("simulated controller crash");
                assertThat((String) Models.get(InstanceModel.class).findById(id)
                        .get(InstanceModel.INSTALL_STATE))
                    .as("step 3: the interruption left visible evidence (installing)")
                    .isEqualTo(InstanceModel.INSTALL_INSTALLING);
                Map<String, String> afterCrash = new InstanceVariables().valuesFor(id);
                assertThat(afterCrash.get("SHARED_TOKEN"))
                    .as("step 3: the SECRET SURVIVES the interrupted install, byte for byte")
                    .isEqualTo(token);
                assertThat(afterCrash.get("GREETING"))
                    .as("step 3: the plain variable survives too")
                    .isEqualTo("hello-hohenheim");

                // 4. RESUME: the ordinary install call retries, completes, stamps
                //    installed.
                new InstanceInstalls().install(id);
                assertThat((String) Models.get(InstanceModel.class).findById(id)
                        .get(InstanceModel.INSTALL_STATE))
                    .as("step 4: the resumed install completes")
                    .isEqualTo(InstanceModel.INSTALL_INSTALLED);

                // 5. Deploy now runs: the variable is IN the container env, the config
                //    file is staged WITH its placeholders substituted, and the install
                //    step ran exactly once against the persistent volume -- all read
                //    from INSIDE the container.
                assertThat(service.deploy(id).state())
                    .as("step 5: deploy after install runs")
                    .isEqualTo(ContainerState.RUNNING);
                assertThat(exec(docker, handle, "printenv", "SHARED_TOKEN"))
                    .as("step 5: the secret variable reaches the workload env")
                    .isEqualTo(token);
                String conf = exec(docker, handle, "cat", "/etc/app/app.conf");
                assertThat(conf)
                    .as("step 5: the staged config file substituted both placeholders")
                    .contains("greeting=hello-hohenheim")
                    .contains("token=" + token);
                assertThat(exec(docker, handle, "cat", "/data/runs"))
                    .as("step 5: the resumed install ran the script ONCE onto the volume")
                    .isEqualTo("install-ran");

                // 6. Reinstall under PRESERVE: the volume's data survives, the script
                //    appends a second line.
                service.stop(id);
                new InstanceInstalls().reinstall(id);
                assertThat(service.deploy(id).state())
                    .as("step 6: redeploy after preserve-reinstall runs")
                    .isEqualTo(ContainerState.RUNNING);
                assertThat(exec(docker, handle, "cat", "/data/runs"))
                    .as("step 6: PRESERVE kept the volume -- both runs are on it")
                    .isEqualTo("install-ran\ninstall-ran");

                // 7. Reinstall under CLEAR: the volume is wiped (owner-verified) before
                //    the step re-runs, so exactly one line remains.
                template.set(InstanceTemplateModel.REINSTALL_POLICY,
                    InstanceTemplateModel.REINSTALL_CLEAR);
                Models.get(InstanceTemplateModel.class).save(template);
                service.stop(id);
                new InstanceInstalls().reinstall(id);
                assertThat(service.deploy(id).state())
                    .as("step 7: redeploy after clear-reinstall runs")
                    .isEqualTo(ContainerState.RUNNING);
                assertThat(exec(docker, handle, "cat", "/data/runs"))
                    .as("step 7: CLEAR wiped the volume -- only the fresh run is on it")
                    .isEqualTo("install-ran");
                Map<String, String> afterClear = new InstanceVariables().valuesFor(id);
                assertThat(afterClear.get("SHARED_TOKEN"))
                    .as("step 7: even a CLEAR reinstall never touches the variables")
                    .isEqualTo(token);

                service.stop(id);
            } finally {
                cleanup(docker, handle, volumeName);
            }
        });
    }

    // -- fixtures -------------------------------------------------------------

    private static int template() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", "alpine");
        settings.put("tag", "latest");
        settings.put("command", "sleep 300");
        settings.put("volumes", Map.of("data", "/data"));

        Row template = Models.get(InstanceTemplateModel.class).createEmptyRow();
        template.set(InstanceTemplateModel.NAME, "template-live-fixture");
        template.set(InstanceTemplateModel.KIND, "hohenheim:docker_container");
        template.set(InstanceTemplateModel.SETTINGS, settings);
        template.set(InstanceTemplateModel.INSTALL_IMAGE, "alpine");
        // printf, not echo -n: BusyBox echo has no portable -n contract in sh.
        template.set(InstanceTemplateModel.INSTALL_SCRIPT,
            "if [ -s /data/runs ]; then printf '\\ninstall-ran' >> /data/runs;"
                + " else printf 'install-ran' > /data/runs; fi");
        template.set(InstanceTemplateModel.REINSTALL_POLICY,
            InstanceTemplateModel.REINSTALL_PRESERVE);
        // The operator approval a real catalog entry would carry -- the approval GATE
        // itself is proven by InstanceTemplatePolicyTest.
        template.set(InstanceTemplateModel.APPROVED_AT, java.time.Instant.now());
        Models.get(InstanceTemplateModel.class).save(template);
        int templateId = template.get(InstanceTemplateModel.ID);

        Row secret = Models.get(InstanceTemplateVariableModel.class).createEmptyRow();
        secret.set(InstanceTemplateVariableModel.TEMPLATE_ID, templateId);
        secret.set(InstanceTemplateVariableModel.KEY, "SHARED_TOKEN");
        secret.set(InstanceTemplateVariableModel.TYPE, "hohenheim:secret");
        secret.set(InstanceTemplateVariableModel.REQUIRED, true);
        secret.set(InstanceTemplateVariableModel.SETTINGS,
            Map.of("generate", true, "generate_bytes", 24));
        Models.get(InstanceTemplateVariableModel.class).save(secret);

        Row greeting = Models.get(InstanceTemplateVariableModel.class).createEmptyRow();
        greeting.set(InstanceTemplateVariableModel.TEMPLATE_ID, templateId);
        greeting.set(InstanceTemplateVariableModel.KEY, "GREETING");
        greeting.set(InstanceTemplateVariableModel.TYPE, "hohenheim:string");
        greeting.set(InstanceTemplateVariableModel.DEFAULT_VALUE, "hi");
        greeting.set(InstanceTemplateVariableModel.SETTINGS, Map.of());
        Models.get(InstanceTemplateVariableModel.class).save(greeting);

        Row file = Models.get(InstanceTemplateFileModel.class).createEmptyRow();
        file.set(InstanceTemplateFileModel.TEMPLATE_ID, templateId);
        file.set(InstanceTemplateFileModel.CONTAINER_PATH, "/etc/app/app.conf");
        file.set(InstanceTemplateFileModel.CONTENT,
            "greeting={{GREETING}}\ntoken={{SHARED_TOKEN}}\n");
        file.set(InstanceTemplateFileModel.MODE, "0600");
        Models.get(InstanceTemplateFileModel.class).save(file);
        return templateId;
    }

    // -- plumbing -------------------------------------------------------------

    private static String exec(DockerClient docker, String container, String... command) {
        try {
            return docker.exec(container, List.of(command)).output().trim();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void cleanup(DockerClient docker, String container, String volume) {
        for (String name : new String[] { container + "-install", container }) {
            try {
                docker.removeContainer(name, true);
            } catch (IOException ignored) {
                // already gone
            }
        }
        try {
            docker.removeNetwork(InstanceNetworks.networkName(container));
        } catch (IOException ignored) {
            // a deploy that never reached the network has none to remove
        }
        try {
            docker.removeVolume(volume, true);
        } catch (IOException ignored) {
            // already gone
        }
    }

    private static boolean imagePresent(DockerClient docker, String tag) throws IOException {
        for (Object image : docker.listImages()) {
            Object repoTags = ((Map<?, ?>) image).get("RepoTags");
            if (repoTags instanceof List<?> tags && tags.contains(tag)) {
                return true;
            }
        }
        return false;
    }
}
