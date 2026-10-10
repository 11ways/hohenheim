package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimFormSections;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.instance.ApplicationKind;
import be.elevenways.hohenheim.source.GitSourceSchema;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A git app's Configuration: where the code comes from and how it builds and
 * deploys, then the variables per lane, both open; the details with working defaults folded; a stored secret never
 * echoed back into the form.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
class AppGitConfigJourneyTest extends HohenheimTestBase {

    private static final String SECRET = "whsec-appgitconfig-7f3a91";
    private static Integer applicationId;

    @BeforeAll
    static void seed() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", "alpine");
        settings.put(GitSourceSchema.REPOSITORY_URL, "https://git.example.test/acme/staging-api.git");
        settings.put(GitSourceSchema.BRANCH, "main");
        settings.put(GitSourceSchema.WEBHOOK_SECRET, SECRET);
        settings.put(ApplicationKind.ENVIRONMENT_VARIABLES.getName(), Map.of("LOG_LEVEL", "info-running"));
        settings.put(GitSourceSchema.BUILD_ENVIRONMENT_VARIABLES, Map.of("NODE_ENV", "production-building"));
        settings.put(GitSourceSchema.PREVIEWS_ENABLED, true);
        Row app = Models.get(InstanceModel.class).createEmptyRow();
        app.set(InstanceModel.NAME, "appgitconfig-app");
        app.set(InstanceModel.KIND, ApplicationKind.ID.toString());
        app.set(InstanceModel.SETTINGS, settings);
        app.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
        Models.get(InstanceModel.class).save(app);
        applicationId = app.get(InstanceModel.ID);
    }

    @AfterAll
    static void cleanUp() {
        if (applicationId != null) {
            HardDeletes.byId(Models.get(InstanceModel.class), applicationId);
        }
    }

    @Test
    void theConfigurationLeadsWithSourceAndVariablesAndNeverEchoesTheSecret() throws Exception {
        // 1. The Configuration tab (the record's form) renders.
        HttpResponse<String> page = adminGet("/admin/instances/" + applicationId);
        assertThat(page.statusCode()).as("step 1: the Configuration renders").isEqualTo(200);
        String body = page.body();

        // 2. Source and build first, the variables next, then the folds, in that order.
        int source = sectionAt(body, HohenheimFormSections.SOURCE);
        int variables = sectionAt(body, HohenheimFormSections.VARIABLES);
        int build = sectionAt(body, HohenheimFormSections.BUILD);
        int deployment = sectionAt(body, HohenheimFormSections.DEPLOYMENT);
        assertThat(variables).as("step 2: the variables follow the source section").isGreaterThan(source);
        assertThat(build).as("step 2: the build fold follows the variables").isGreaterThan(variables);
        assertThat(deployment).as("step 2: and the deployment fold after it").isGreaterThan(build);

        // 3. The two decisions are open; what has a working default is folded.
        assertThat(sectionTag(body, source)).as("step 3: the source section is open")
            .doesNotContain("data-collapsed=\"true\"");
        assertThat(sectionTag(body, variables)).as("step 3: the variables section is open")
            .doesNotContain("data-collapsed=\"true\"");
        assertThat(sectionTag(body, build)).as("step 3: the build details are folded")
            .contains("data-collapsed=\"true\"");

        // 4. Each lane lists its own variables by name, their values masked like every stored secret, and the
        //    section says when they take effect.
        assertThat(body)
            .as("step 4: the running lane's variable").contains("LOG_LEVEL")
            .as("step 4: the building lane's variable").contains("NODE_ENV")
            .as("step 4: a running value is never echoed").doesNotContain("info-running")
            .as("step 4: nor a building value").doesNotContain("production-building")
            .as("step 4: the variables say when they apply").contains("Changes apply at the next deploy.");

        // 5. The stored webhook secret is never written back into the form.
        assertThat(body).as("step 5: the secret is not echoed").doesNotContain(SECRET);
    }

    private static int sectionAt(String body, String id) {
        int at = body.indexOf("data-section=\"" + id + "\"");
        assertThat(at).as("the form renders the '%s' section", id).isGreaterThan(0);
        return at;
    }

    /** The opening tag that carries a section's data-section attribute. */
    private static String sectionTag(String body, int at) {
        int open = body.lastIndexOf('<', at);
        int close = body.indexOf('>', at);
        return body.substring(open, close);
    }
}
