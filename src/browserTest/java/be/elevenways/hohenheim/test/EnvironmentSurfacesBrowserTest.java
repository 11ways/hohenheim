package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.instance.VariableKind;
import be.elevenways.hohenheim.model.EnvironmentModel;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.hohenheim.model.ProjectModel;
import be.elevenways.hohenheim.server.cms.EnvironmentParts;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.cms.common.render.table.SynthesizedRowActions;
import be.elevenways.zenit.cms.test.support.PanelSurfaces;
import be.elevenways.zenit.cms.test.support.PlacedOperationMoves;
import be.elevenways.zenit.cms.test.support.SurfaceBaselines;
import be.elevenways.zenit.cms.test.support.SurfaceCase;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.security.AccessContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Frozen environment and variable surfaces, including both value carriers and a used environment's delete gate.
 *
 * AIDEV-NOTE: the stored set ({@code /panel-surfaces/environments.txt}) is the behaviour captured on the legacy
 * EnvironmentResource and EnvironmentVariableResource before they moved onto EnvironmentParts. A failing comparison is
 * a changed surface, never a file to refresh; an accepted difference is declared as a move.
 *
 * @author Jelle De Loecker
 * @since 0.9.0
 */
class EnvironmentSurfacesBrowserTest extends HohenheimTestBase {

    private static final String ENVIRONMENTS = "environments";
    private static final String VARIABLES = "environment-variables";
    private static String projectId;
    private static String emptyId;
    private static String usedId;
    private static String plainId;
    private static String secretId;
    private static AccessContext operator;
    private static AccessContext tenant;

    @BeforeAll
    static void seed() {
        operator = TenantConduits.operator();
        int user = ApiSupport.user("environment-surfaces@hohenheim.local", "Environment Surfaces Tenant");
        tenant = AccessContext.of(TenantConduits.stubFor(new UserPrincipal(user, "Environment Surfaces Tenant")));
        Model projects = Models.get(ProjectModel.class);
        Row project = projects.createEmptyRow();
        project.set(ProjectModel.NAME, "surface-project");
        projects.save(project);
        projectId = String.valueOf((Integer) project.get(ProjectModel.ID));
        emptyId = environment("surface-empty");
        usedId = environment("surface-used");
        plainId = variable("SURFACE_PLAIN", VariableKind.PLAIN, "surface-plain-value");
        secretId = variable("SURFACE_SECRET", VariableKind.SECRET, "surface-secret-value");
    }

    @Test
    void theEnvironmentEntriesKeepTheirCapturedSurfaces() {
        SurfaceBaselines before = SurfaceBaselines.load(EnvironmentSurfacesBrowserTest.class,
            "/panel-surfaces/environments.txt")
            // Each entry's synthesized delete became its placed delete operation: the environment's
            // delete_environment (its in-use refusal the operation's availability) and core's canonical row delete
            // over the variable model; every other fact compares exactly.
            .placedOperations(PlacedOperationMoves.of()
                .synthesized(ENVIRONMENTS, SynthesizedRowActions.DELETE, EnvironmentParts.DELETE.id())
                .synthesized(VARIABLES, SynthesizedRowActions.DELETE, EnvironmentParts.DELETE_VARIABLE.id()));
        // 1. Installation administration admits its operator and refuses an ordinary account.
        for (String entry : List.of(ENVIRONMENTS, VARIABLES)) {
            before.check(capture(SurfaceCase.of(HohenheimSlugs.ADMIN, entry, "operator", operator)));
            before.check(capture(SurfaceCase.of(HohenheimSlugs.ADMIN, entry, "tenant", tenant)
                .refusedFor(ZenitRefusalReason.FORBIDDEN)));
        }
        // 2. The empty and used environments carry their actual delete availability and confirmations.
        before.check(capture(SurfaceCase.of(HohenheimSlugs.ADMIN, ENVIRONMENTS, "operator", operator)
            .onRecord(emptyId, "empty")));
        before.check(capture(SurfaceCase.of(HohenheimSlugs.ADMIN, ENVIRONMENTS, "operator", operator)
            .onRecord(usedId, "used")));
        before.check(capture(SurfaceCase.of(HohenheimSlugs.ADMIN, ENVIRONMENTS, "operator", operator)
            .selecting(List.of(emptyId, usedId), "selection")));
        // 3. Each variable kind exposes only its own carrier; full-form and inline declarations stay captured.
        before.check(capture(SurfaceCase.of(HohenheimSlugs.ADMIN, VARIABLES, "operator", operator)
            .onRecord(plainId, "plain")));
        before.check(capture(SurfaceCase.of(HohenheimSlugs.ADMIN, VARIABLES, "operator", operator)
            .onRecord(secretId, "secret")));
        before.check(capture(SurfaceCase.of(HohenheimSlugs.ADMIN, VARIABLES, "operator", operator)
            .selecting(List.of(plainId, secretId), "selection")));
        // 4. The shared comparer requires every stored case and every captured case.
        before.finish();
    }

    private static PanelSurfaces capture(SurfaceCase fixture) {
        return PanelSurfaces.capture(fixture.key("projects", "project", projectId)
            .key("project_id", "project", projectId).key(ENVIRONMENTS, "empty", emptyId)
            .key(ENVIRONMENTS, "used", usedId).key("environment_id", "empty", emptyId)
            .key("environment_id", "used", usedId).key(VARIABLES, "plain", plainId)
            .key(VARIABLES, "secret", secretId));
    }

    private static String environment(String name) {
        Model environments = Models.get(EnvironmentModel.class);
        Row row = environments.createEmptyRow();
        row.set(EnvironmentModel.PROJECT_ID, Integer.parseInt(projectId));
        row.set(EnvironmentModel.NAME, name);
        row.set(EnvironmentModel.DESCRIPTION, "Surface evidence");
        environments.save(row);
        return String.valueOf((Integer) row.get(EnvironmentModel.ID));
    }

    private static String variable(String key, VariableKind kind, String value) {
        Model variables = Models.get(InstanceVariableModel.class);
        Row row = variables.createEmptyRow();
        row.set(InstanceVariableModel.ENVIRONMENT_ID, Integer.parseInt(usedId));
        row.set(InstanceVariableModel.KEY, key);
        row.set(InstanceVariableModel.KIND, kind.token());
        if (kind.isSecret()) {
            row.set(InstanceVariableModel.SECRET_VALUE, value);
        } else {
            row.set(InstanceVariableModel.PLAIN_VALUE, value);
        }
        variables.save(row);
        return String.valueOf((Integer) row.get(InstanceVariableModel.ID));
    }
}
