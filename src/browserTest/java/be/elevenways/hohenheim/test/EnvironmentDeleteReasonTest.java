package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.EnvironmentModel;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.hohenheim.model.ProjectModel;
import be.elevenways.hohenheim.server.cms.CmsSupport;
import be.elevenways.hohenheim.server.cms.EnvironmentResource;
import be.elevenways.hohenheim.server.project.ProjectGuards;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An environment still in use refuses deletion BY NAME: the delete affordance is dead
 * with the holders on it, and the write gate's refusal names the same holders.
 *
 * Pinned defect (QA 2026-08-29, F11): "still referenced by instances or variables"
 * named nothing the operator could act on.
 */
class EnvironmentDeleteReasonTest extends HohenheimTestBase {

    private static final String KEY = "HOLD_ME_QA";

    private static Integer projectId;
    private static Integer environmentId;
    private static Integer variableId;

    @BeforeAll
    static void seed() {
        Row project = Models.get(ProjectModel.class).createEmptyRow();
        project.set(ProjectModel.NAME, "Delete Reason Probe");
        Models.get(ProjectModel.class).save(project);
        projectId = project.get(ProjectModel.ID);

        Row environment = Models.get(EnvironmentModel.class).createEmptyRow();
        environment.set(EnvironmentModel.PROJECT_ID, projectId);
        environment.set(EnvironmentModel.NAME, "delete-reason-probe");
        Models.get(EnvironmentModel.class).save(environment);
        environmentId = environment.get(EnvironmentModel.ID);

        Row variable = Models.get(InstanceVariableModel.class).createEmptyRow();
        variable.set(InstanceVariableModel.ENVIRONMENT_ID, environmentId);
        variable.set(InstanceVariableModel.KEY, KEY);
        variable.set(InstanceVariableModel.KIND, InstanceVariableModel.KIND_PLAIN);
        variable.set(InstanceVariableModel.PLAIN_VALUE, "held");
        Models.get(InstanceVariableModel.class).save(variable);
        variableId = variable.get(InstanceVariableModel.ID);
    }

    @AfterAll
    static void cleanUp() {
        if (variableId != null) {
            Models.get(InstanceVariableModel.class).delete(variableId);
        }
        if (environmentId != null) {
            Models.get(EnvironmentModel.class).delete(environmentId);
        }
        if (projectId != null) {
            Models.get(ProjectModel.class).delete(projectId);
        }
    }

    @Test
    void theDeadDeleteAndTheRefusalBothNameTheHolders() throws Exception {
        Row admin = AuthModels.users().find().where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        AccessContext operator = AccessContext.of(TenantConduits.stubFor(
            new UserPrincipal(admin.get(UserModel.ID), "Test Admin")));
        Row environment = Models.get(EnvironmentModel.class).findById(environmentId);

        // 1. The resource declares the delete dead, naming the variable that holds it.
        Microcopy reason = new EnvironmentResource().deleteUnavailableReason(environment, operator);
        assertThat(reason).as("step 1: an environment in use has a dead delete").isNotNull();
        assertThat(resolve(reason)).as("step 1: the reason names the holder").contains(KEY);

        // 2. The write gate's own wording is the same sentence.
        ProjectGuards.EnvironmentUsage usage = ProjectGuards.usageOf(environmentId);
        assertThat(usage.variables()).containsExactly(KEY);
        assertThat(usage.instances()).isEmpty();
        assertThat(resolve(usage.refusal())).isEqualTo(resolve(reason));

        // 3. The list renders the reason on the row (a disabled button is announced
        //    by its visible reason, never by a tooltip alone).
        HttpResponse<String> list = adminGet("/admin/environments");
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body()).as("step 3: the reason is on screen").contains(KEY);

        // 4. A direct POST is refused with the same reason and the row survives.
        HttpResponse<String> refused = httpPostForm(
            "/admin/environments/" + environmentId + "/delete", confirmed(""), sessionToken, csrfToken);
        assertThat(refused.statusCode()).as("step 4: the refusal redirects back").isEqualTo(302);
        assertThat(Models.get(EnvironmentModel.class).findById(environmentId))
            .as("step 4: the environment was not deleted").isNotNull();
        var flash = popFlash();
        assertThat(flash).as("step 4: the operator gets an error toast").isNotNull();
        assertThat(resolve(flash.message())).as("step 4: naming the holder").contains(KEY);

        // 5. Once the variable is gone the delete comes alive again.
        Models.get(InstanceVariableModel.class).delete(variableId);
        variableId = null;
        assertThat(new EnvironmentResource().deleteUnavailableReason(environment, operator))
            .as("step 5: nothing holds the environment any more").isNull();
    }

    private static String resolve(Microcopy copy) {
        return CmsSupport.resolvedTextOrDefault(copy);
    }
}
