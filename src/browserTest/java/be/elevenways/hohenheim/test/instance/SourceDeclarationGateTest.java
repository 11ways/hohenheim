package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.SourceBuildDetail;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * What a git source may DECLARE: the write gate refuses a branch git would read as an
 * option, a build directory outside the checkout and a controller path on a record a tenant
 * owns, and the build detail that used to be stored and ignored is read by one helper.
 */
class SourceDeclarationGateTest extends HohenheimTestBase {

    @Test
    void theBuildDetailIsReadAndBoundedByTheHost() {
        Map<String, Object> settings = new LinkedHashMap<>();

        // 1. Nothing declared: the checkout root, the host's own cap, no build environment.
        assertThat(SourceBuildDetail.workingDirectory(settings, "/home/app"))
            .as("step 1: no build_directory builds in the checkout root")
            .isEqualTo("/home/app");
        assertThat(SourceBuildDetail.timeoutMs(settings, 600_000))
            .as("step 1: no build_timeout keeps the host cap").isEqualTo(600_000);
        assertThat(SourceBuildDetail.environment(settings))
            .as("step 1: no build environment").isEmpty();

        // 2. Declared: a subdirectory, a SHORTER timeout, a build-only variable.
        settings.put("build_directory", "web/app");
        settings.put("build_timeout", 120);
        settings.put("build_environment_variables", Map.of("NODE_ENV", "production"));
        assertThat(SourceBuildDetail.workingDirectory(settings, "/home/app"))
            .as("step 2: the build runs in the declared subdirectory")
            .isEqualTo("/home/app/web/app");
        assertThat(SourceBuildDetail.timeoutMs(settings, 600_000))
            .as("step 2: a shorter declared timeout wins").isEqualTo(120_000);
        assertThat(SourceBuildDetail.environment(settings))
            .as("step 2: the build environment is handed over")
            .containsEntry("NODE_ENV", "production");

        // 3. The host still caps: a longer declaration cannot widen the quota.
        settings.put("build_timeout", 86_400);
        assertThat(SourceBuildDetail.timeoutMs(settings, 600_000))
            .as("step 3: the host's build quota still applies").isEqualTo(600_000);

        // 4. A directory that climbs out of the checkout, or an absolute one, is refused.
        for (String escape : new String[] {"../../etc", "/etc", "web/../../x"}) {
            settings.put("build_directory", escape);
            assertThat(catchThrowable(() ->
                    SourceBuildDetail.workingDirectory(settings, "/home/app")))
                .as("step 4: " + escape + " is refused by name")
                .isInstanceOf(Violations.class)
                .hasMessageContaining("source_build_directory_invalid");
        }
    }

    @Test
    void theWriteGateRefusesWhatGitOrTheBuildWouldMisread() {
        // 1. An option-shaped branch never becomes a stored source.
        Map<String, Object> optionBranch = source("https://git.example.test/team/app.git");
        optionBranch.put("branch", "--upload-pack=touch /tmp/x");
        assertThat(catchThrowable(() -> application("gate-option", optionBranch)))
            .as("step 1: a branch git would read as an option is refused where it is typed")
            .isInstanceOf(Violations.class)
            .hasMessageContaining("source_branch_invalid");

        // 2. Nor does a build directory outside the checkout.
        Map<String, Object> climbing = source("https://git.example.test/team/app.git");
        climbing.put("build_directory", "../../etc");
        assertThat(catchThrowable(() -> application("gate-climb", climbing)))
            .as("step 2: a build directory outside the checkout is refused")
            .isInstanceOf(Violations.class)
            .hasMessageContaining("source_build_directory_invalid");

        // 3. An operator-owned record may name a controller path (the local-source lane).
        int applicationId = application("gate-local", source("/srv/repos/app.git"));
        assertThat(applicationId).as("step 3: the operator's local source is stored")
            .isPositive();

        // 4. Once a tenant owns the record, the same path can no longer be written.
        RecordGrants.grant(GrantSubjectType.USER, ApiSupport.user("gate-tenant@hohenheim.local"),
            InstanceModel.MODEL_ID, applicationId, HohenheimAccess.MANAGE, true);
        Row owned = Models.get(InstanceModel.class).findById(applicationId);
        owned.set(InstanceModel.SETTINGS, source("file:///srv/repos/app.git"));
        assertThat(catchThrowable(() -> Models.get(InstanceModel.class).save(owned)))
            .as("step 4: a tenant-owned record cannot declare a controller path")
            .isInstanceOf(Violations.class)
            .hasMessageContaining("repository_url_local_refused");

        // 5. Falsified: a remote URL on the same tenant-owned record is accepted.
        Row remote = Models.get(InstanceModel.class).findById(applicationId);
        remote.set(InstanceModel.SETTINGS, source("https://git.example.test/team/app.git"));
        Models.get(InstanceModel.class).save(remote);
        assertThat(Models.get(InstanceModel.class).findById(applicationId)
                .get(InstanceModel.SETTINGS).toString())
            .as("step 5: the remote source is what is stored now")
            .contains("git.example.test");
    }

    private static Map<String, Object> source(String url) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("repository_url", url);
        settings.put("branch", "main");
        return settings;
    }

    private static int application(String name, Map<String, Object> settings) {
        var instances = Models.get(InstanceModel.class);
        Row application = instances.createEmptyRow();
        application.set(InstanceModel.NAME, name);
        application.set(InstanceModel.KIND, "hohenheim:application");
        application.set(InstanceModel.SETTINGS, new LinkedHashMap<>(settings));
        instances.save(application);
        return application.get(InstanceModel.ID);
    }
}
