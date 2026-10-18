package be.elevenways.hohenheim.test.project;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.instance.VariableKind;
import be.elevenways.hohenheim.model.EnvironmentModel;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.hohenheim.model.ProjectModel;
import be.elevenways.hohenheim.server.project.Projects;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.ResourceForm;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A project's and an environment's pages carry what those records hold: a project its environments and its members, an
 * environment its variables (a plain value shown, a secret never), and a new variable starts as a plain one.
 *
 * @author Jelle De Loecker
 * @since 0.10.0
 */
class ProjectPagesJourneyTest extends HohenheimTestBase {

    private static final String PREFIX = "ppj-";

    /** A stored subject key as a table cell would draw it, which a reader must never see. */
    private static final String RAW_SUBJECT_CELL = ">\\s*user\\s*<";

    private static Integer projectId;
    private static Integer environmentId;
    private static String memberSession;

    @BeforeAll
    static void seed() {
        UUID memberId = ApiSupport.user("ppj-member@project.test", PREFIX + "Member");
        memberSession = sessionFor(memberId).token();
        projectId = project(PREFIX + "webshop");
        Integer otherId = project(PREFIX + "elsewhere");
        Projects.addMember(Models.get(ProjectModel.class).findById(projectId), memberId);
        environmentId = environment(projectId, PREFIX + "production");
        environment(otherId, PREFIX + "staging");
        variable(VariableKind.PLAIN, "PPJ_CURRENCY", PREFIX + "plain-value");
        variable(VariableKind.SECRET, "PPJ_STRIPE", PREFIX + "secret-value");
    }

    @AfterAll
    static void cleanUp() {
        Model variables = Models.get(InstanceVariableModel.class);
        for (Row row : variables.find().where(InstanceVariableModel.KEY.startsWith("PPJ_")).all()) {
            variables.delete(row.get(InstanceVariableModel.ID));
        }
        Model environments = Models.get(EnvironmentModel.class);
        for (Row row : environments.find().where(EnvironmentModel.NAME.startsWith(PREFIX)).all()) {
            environments.delete(row.get(EnvironmentModel.ID));
        }
        Model projects = Models.get(ProjectModel.class);
        for (Row row : projects.find().where(ProjectModel.NAME.startsWith(PREFIX)).all()) {
            projects.delete(row.get(ProjectModel.ID));
        }
        Model users = Models.get(UserModel.class);
        for (Row row : users.find().where(UserModel.DISPLAY_NAME.startsWith(PREFIX)).all()) {
            users.delete(row.get(UserModel.ID));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void aProjectAndItsEnvironmentReadAsWhatTheyHold() throws Exception {
        // 1. The project's Environments tab lists its own environment, linked, and never another project's.
        HttpResponse<String> environments = httpGet(projectTab(HohenheimSlugs.ADMIN, "environments"), sessionToken);
        assertThat(environments.statusCode()).as("step 1: a project has an Environments tab").isEqualTo(200);
        assertThat(environments.body()).as("step 1: listing its own environment, linked to its page")
            .contains(PREFIX + "production").contains("/admin/environments/" + environmentId);
        assertThat(environments.body()).as("step 1: and never another project's")
            .doesNotContain(PREFIX + "staging");

        // 2. Its Members tab names the member as a person, never as the stored subject key, and points at the People
        //    page where someone is given the project's role.
        HttpResponse<String> members = httpGet(projectTab(HohenheimSlugs.ADMIN, "members"), sessionToken);
        assertThat(members.statusCode()).as("step 2: a project has a Members tab").isEqualTo(200);
        assertThat(members.body()).as("step 2: naming its member as a person")
            .contains(PREFIX + "Member").contains("Person").doesNotContainPattern(RAW_SUBJECT_CELL);
        assertThat(members.body()).as("step 2: with the way to add someone").contains("/admin/users");

        // 3. The environment's Variables tab shows a plain value and never a secret one, which reads as not shown
        //    rather than as an empty "None".
        String variables = httpGet("/admin/environments/" + environmentId + "/page/variables", sessionToken).body();
        assertThat(variables).as("step 3: both keys are listed").contains("PPJ_CURRENCY").contains("PPJ_STRIPE");
        assertThat(variables).as("step 3: the plain value is readable in the list")
            .contains(PREFIX + "plain-value");
        assertThat(variables).as("step 3: the secret value never is, and says it is withheld")
            .doesNotContain(PREFIX + "secret-value").contains("Not shown");

        // 4. The environment's page leads back through its project, not straight to Apps.
        assertThat(variables).as("step 4: the trail names the project and links its Environments tab")
            .contains(PREFIX + "webshop").contains(projectTab(HohenheimSlugs.ADMIN, "environments"));

        // 5. A new variable starts as a plain one, so its value field is there before anything is picked.
        PanelResource<Row> variableEntry = (PanelResource<Row>) Objects.requireNonNull(
            PanelRegistry.getBySlug(HohenheimSlugs.ADMIN)).entryBySlug(HohenheimSlugs.ENVIRONMENT_VARIABLES);
        ResourceForm<Row> form = Objects.requireNonNull(Objects.requireNonNull(variableEntry).form());
        Map<String, Object> defaults = form.createDefaults(TenantConduits.operator(),
            Objects.requireNonNull(PanelRegistry.getBySlug(HohenheimSlugs.ADMIN)));
        assertThat(defaults).as("step 5: the kind starts plain")
            .containsEntry(InstanceVariableModel.KIND.getName(), InstanceVariableModel.KIND_PLAIN);

        // 6. The projects list words a quota nobody set as no limit, never as a dash.
        String list = httpGet("/admin/projects", sessionToken).body();
        assertThat(list).as("step 6: an unset instance quota reads as no limit")
            .contains("0, no limit").doesNotContain("0 / -");

        // 7. The member sees the same roster on their own project's page and in the Team roster, in the same words.
        HttpResponse<String> tenantMembers = httpGet(projectTab(HohenheimSlugs.MANAGE, "members"), memberSession);
        assertThat(tenantMembers.statusCode()).as("step 7: the tenant's project page has a Members tab")
            .isEqualTo(200);
        assertThat(tenantMembers.body()).as("step 7: naming the member as a person")
            .contains(PREFIX + "Member").contains("Person").doesNotContainPattern(RAW_SUBJECT_CELL);
        assertThat(httpGet("/manage/" + HohenheimSlugs.PROJECT_MEMBERS, memberSession).body())
            .as("step 7: the Team roster reads the kind in words too")
            .contains("Person").doesNotContainPattern(RAW_SUBJECT_CELL);
    }

    private static String projectTab(String panel, String tab) {
        return "/" + panel + "/" + HohenheimSlugs.PROJECTS + "/" + projectId + "/page/" + tab;
    }

    private static Integer project(String name) {
        Model projects = Models.get(ProjectModel.class);
        Row row = projects.createEmptyRow();
        row.set(ProjectModel.NAME, name);
        projects.save(row);
        return row.get(ProjectModel.ID);
    }

    private static Integer environment(Integer project, String name) {
        Model environments = Models.get(EnvironmentModel.class);
        Row row = environments.createEmptyRow();
        row.set(EnvironmentModel.PROJECT_ID, project);
        row.set(EnvironmentModel.NAME, name);
        environments.save(row);
        return row.get(EnvironmentModel.ID);
    }

    private static void variable(VariableKind kind, String key, String value) {
        Model variables = Models.get(InstanceVariableModel.class);
        Row row = variables.createEmptyRow();
        row.set(InstanceVariableModel.ENVIRONMENT_ID, environmentId);
        row.set(InstanceVariableModel.KEY, key);
        row.set(InstanceVariableModel.KIND, kind.token());
        row.set(kind.isSecret() ? InstanceVariableModel.SECRET_VALUE : InstanceVariableModel.PLAIN_VALUE, value);
        variables.save(row);
    }
}
