package be.elevenways.hohenheim.test.project;

import be.elevenways.hohenheim.model.EnvironmentModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.ProjectModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceTemplates;
import be.elevenways.hohenheim.server.project.Projects;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.GrantService;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violation;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * No existence oracle on the create-from-template funnel: an unknown project, a project the actor is not
 * a member of, an unknown environment and another project's environment are ONE answer
 * ({@code project_member_required}), on the field the submit named and carrying only the value it
 * submitted -- never the real project id behind a foreign environment.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class ProjectCreateOneAnswerTest extends HohenheimTestBase {

    private static final String PREFIX = "proj-one-answer-";

    /** An id no project and no environment carries. */
    private static final int ABSENT_ID = 900_000_200;

    private static AccessContext member;
    private static Row template;
    private static int ownProjectId;
    private static int foreignProjectId;
    private static int foreignEnvironmentId;

    @BeforeAll
    static void seed() {
        int memberId = ApiSupport.user("member@one-answer.test", "One Answer Member");
        // May create instances at all, so the refusal below is the PROJECT gate, not the
        // create-authority one in front of it.
        GrantService.createDirectGrant(GrantSubjectType.USER, memberId,
            HohenheimAccess.INSTANCES_CREATE.value(), true);
        member = AccessContext.of(TenantConduits.stubFor(new UserPrincipal(memberId, "One Answer Member")));

        ownProjectId = project(PREFIX + "own");
        foreignProjectId = project(PREFIX + "foreign");
        Projects.addMember(Models.get(ProjectModel.class).findById(ownProjectId), memberId);

        Row environment = Models.get(EnvironmentModel.class).createEmptyRow();
        environment.set(EnvironmentModel.PROJECT_ID, foreignProjectId);
        environment.set(EnvironmentModel.NAME, PREFIX + "foreign-env");
        Models.get(EnvironmentModel.class).save(environment);
        foreignEnvironmentId = environment.get(EnvironmentModel.ID);

        Row row = Models.get(InstanceTemplateModel.class).createEmptyRow();
        row.set(InstanceTemplateModel.NAME, PREFIX + "template");
        row.set(InstanceTemplateModel.KIND, "hohenheim:docker_container");
        row.set(InstanceTemplateModel.SETTINGS, new LinkedHashMap<>(
            Map.of("image", "alpine", "tag", "latest", "command", "sleep 300")));
        row.set(InstanceTemplateModel.APPROVED_AT, Now.instant());
        row.set(InstanceTemplateModel.APPROVED_BY_USER_ID, 1L);
        Models.get(InstanceTemplateModel.class).save(row);
        template = row;
    }

    @AfterAll
    static void cleanUp() {
        Model environments = Models.get(EnvironmentModel.class);
        for (Row row : environments.find().where(EnvironmentModel.NAME.startsWith(PREFIX)).all()) {
            environments.delete(row.get(EnvironmentModel.ID));
        }
        Model projects = Models.get(ProjectModel.class);
        for (Row row : projects.find().where(ProjectModel.NAME.startsWith(PREFIX)).all()) {
            projects.delete(row.get(ProjectModel.ID));
        }
        Model templates = Models.get(InstanceTemplateModel.class);
        for (Row row : templates.find().where(InstanceTemplateModel.NAME.startsWith(PREFIX)).all()) {
            templates.delete(row.get(InstanceTemplateModel.ID));
        }
    }

    @Test
    void aCreateIntoAProjectTheActorCannotReachIsOneAnswer() {
        assertThat(Models.get(ProjectModel.class).findById(ABSENT_ID)).as("fixture: no such project").isNull();
        assertThat(Models.get(EnvironmentModel.class).findById(ABSENT_ID)).as("fixture: no such environment").isNull();

        // 1. By project: one that does not exist and one the member is not in answer alike.
        assertThat(answerOf(Map.of("project_id", String.valueOf(ABSENT_ID))))
            .as("step 1: an unknown project is refused as not-a-member")
            .isEqualTo("project_id|project_member_required|" + ABSENT_ID);
        assertThat(answerOf(Map.of("project_id", String.valueOf(foreignProjectId))))
            .as("step 1: a real foreign project gets the SAME answer")
            .isEqualTo("project_id|project_member_required|" + foreignProjectId);

        // 2. By environment alone: unknown and foreign alike, and the refusal never carries the
        //    foreign environment's real project id.
        assertThat(answerOf(Map.of("environment_id", String.valueOf(ABSENT_ID))))
            .as("step 2: an unknown environment is refused as not-a-member")
            .isEqualTo("environment_id|project_member_required|" + ABSENT_ID);
        assertThat(answerOf(Map.of("environment_id", String.valueOf(foreignEnvironmentId))))
            .as("step 2: another project's environment gets the SAME answer")
            .isEqualTo("environment_id|project_member_required|" + foreignEnvironmentId);

        // 3. The member's OWN project with an environment it cannot pair with: still the one
        //    answer, whether that environment is unknown or belongs elsewhere.
        assertThat(answerOf(Map.of("project_id", String.valueOf(ownProjectId),
                "environment_id", String.valueOf(ABSENT_ID))))
            .as("step 3: an unknown environment under the member's own project")
            .isEqualTo("project_id|project_member_required|" + ownProjectId);
        assertThat(answerOf(Map.of("project_id", String.valueOf(ownProjectId),
                "environment_id", String.valueOf(foreignEnvironmentId))))
            .as("step 3: a mismatched environment answers the same")
            .isEqualTo("project_id|project_member_required|" + ownProjectId);

        // 4. None of the refused creates wrote a record.
        assertThat(Models.get(InstanceModel.class).find()
                .where(InstanceModel.NAME.eq(PREFIX + "instance")).count())
            .as("step 4: nothing was created").isZero();
    }

    /** The refusal of one create as {@code field|key|value}: the whole of what a caller learns. */
    private static String answerOf(Map<String, Object> placement) {
        Map<String, Object> form = new LinkedHashMap<>(placement);
        Throwable refused = catchThrowable(() -> new InstanceTemplates().createFromTemplate(
            template, PREFIX + "instance", null, form, member));
        assertThat(refused).as("the create is refused").isInstanceOf(Violations.class);
        List<Violation> all = ((Violations) refused).all();
        assertThat(all).as("with exactly one violation").hasSize(1);
        Violation only = all.get(0);
        return only.fieldName() + "|" + only.message().key() + "|" + only.value();
    }

    private static int project(String name) {
        Row row = Models.get(ProjectModel.class).createEmptyRow();
        row.set(ProjectModel.NAME, name);
        Models.get(ProjectModel.class).save(row);
        return row.get(ProjectModel.ID);
    }
}
