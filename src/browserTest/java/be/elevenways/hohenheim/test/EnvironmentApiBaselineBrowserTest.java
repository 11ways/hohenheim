package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.EnvironmentModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.hohenheim.model.ProjectModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.project.Projects;
import be.elevenways.hohenheim.server.util.Json;
import be.elevenways.zenit.auth.CapabilityScopes;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.ApiKeyService;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The /api/v1 answers over projects, environments and environment variables, stored before the environment admin
 * entries moved onto PanelResource parts and compared byte for byte after it.
 *
 * AIDEV-NOTE: the stored transcript ({@code /api-v1/environments.txt}) is the behaviour of the legacy
 * EnvironmentResource and EnvironmentVariableResource era; a failing comparison is a changed answer, never a file to
 * refresh, and the complete current transcript is written under {@code build/api-v1/} for review. Generated ids and
 * timestamps are written as placeholders, and a JSON body's keys are written sorted: the API builds several bodies
 * with {@code Map.of}, whose iteration order is randomized per JVM, so the raw key order differs between two runs of
 * the same code. A non-JSON answer (the uniform 404) is compared by status alone, since its page chrome is not the
 * API's.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
class EnvironmentApiBaselineBrowserTest extends HohenheimTestBase {

    private static final String PREFIX = "env-api-baseline-";
    private static final String VAR_PREFIX = "ENV_API_BASELINE_";
    private static final String STORED = "/api-v1/environments.txt";

    private static int memberId;
    private static int projectId;
    private static int environmentId;
    private static int emptyEnvironmentId;
    private static String memberKey;
    private static String adminKey;

    @BeforeAll
    static void seed() {
        memberId = ApiSupport.user(PREFIX + "member@surface.test", "Env Api Baseline Member");
        Model projects = Models.get(ProjectModel.class);
        Row project = projects.createEmptyRow();
        project.set(ProjectModel.NAME, PREFIX + "project");
        project.set(ProjectModel.DESCRIPTION, "Baseline project");
        projects.save(project);
        projectId = project.get(ProjectModel.ID);
        Projects.addMember(projects.findById(projectId), memberId);
        environmentId = environment(PREFIX + "production", "Live traffic");
        emptyEnvironmentId = environment(PREFIX + "staging", null);
        Row seeded = Models.get(InstanceVariableModel.class).createEmptyRow();
        seeded.set(InstanceVariableModel.ENVIRONMENT_ID, environmentId);
        seeded.set(InstanceVariableModel.KEY, VAR_PREFIX + "SEEDED");
        seeded.set(InstanceVariableModel.KIND, InstanceVariableModel.KIND_PLAIN);
        seeded.set(InstanceVariableModel.PLAIN_VALUE, "seeded-value");
        Models.get(InstanceVariableModel.class).save(seeded);

        memberKey = ApiKeyService.create(memberId, PREFIX + "member",
            List.of(CapabilityScopes.format(SiteModel.MODEL_ID, HohenheimAccess.MANAGE),
                CapabilityScopes.format(InstanceModel.MODEL_ID, HohenheimAccess.MANAGE)), null).plaintext();
        int admin = AuthModels.users().find().where(UserModel.EMAIL.eq("test@hohenheim.local")).first()
            .get(UserModel.ID);
        adminKey = ApiKeyService.create(admin, PREFIX + "admin", List.of("hohenheim.*"), null).plaintext();
    }

    @AfterAll
    static void cleanUp() {
        for (Row variable : Models.get(InstanceVariableModel.class).find()
                .where(InstanceVariableModel.KEY.startsWith(VAR_PREFIX)).all()) {
            HardDeletes.byId(Models.get(InstanceVariableModel.class), variable.get(InstanceVariableModel.ID));
        }
        HardDeletes.byId(Models.get(EnvironmentModel.class), environmentId);
        HardDeletes.byId(Models.get(EnvironmentModel.class), emptyEnvironmentId);
        HardDeletes.byId(Models.get(ProjectModel.class), projectId);
    }

    @Test
    void theEnvironmentApiAnswersWhatItAnsweredBeforeTheMove() throws Exception {
        List<String> transcript = new ArrayList<>();
        String variables = "/api/v1/environments/" + environmentId + "/variables";
        String emptyVariables = "/api/v1/environments/" + emptyEnvironmentId + "/variables";

        // 1. A project member reads its project with both environments, listed and alone.
        transcript.add(exchange("member GET /api/v1/projects/{project}",
            keyGet(memberKey, "/api/v1/projects/" + projectId)));
        transcript.add(exchange("member GET /api/v1/projects (own project)", ownProject(
            keyGet(memberKey, "/api/v1/projects"))));

        // 2. The admin lane reads, sets (plain and secret), deletes and is refused by name.
        transcript.add(exchange("admin GET {environment}/variables", keyGet(adminKey, variables)));
        transcript.add(exchange("admin GET {empty_environment}/variables", keyGet(adminKey, emptyVariables)));
        transcript.add(exchange("admin POST set plain", keyPost(adminKey, variables,
            "key=" + VAR_PREFIX + "PLAIN&value=plain-value")));
        transcript.add(exchange("admin POST set secret", keyPost(adminKey, variables,
            "key=" + VAR_PREFIX + "SECRET&kind=secret&value=secret-value")));
        transcript.add(exchange("admin POST overwrite plain", keyPost(adminKey, variables,
            "key=" + VAR_PREFIX + "PLAIN&value=plain-value-2")));
        transcript.add(exchange("admin GET after sets", keyGet(adminKey, variables)));
        transcript.add(exchange("admin POST delete secret", keyPost(adminKey, variables + "/delete",
            "key=" + VAR_PREFIX + "SECRET")));
        transcript.add(exchange("admin POST delete secret again", keyPost(adminKey, variables + "/delete",
            "key=" + VAR_PREFIX + "SECRET")));
        transcript.add(exchange("admin POST unknown kind", keyPost(adminKey, variables,
            "key=" + VAR_PREFIX + "X&kind=mystery&value=v")));
        transcript.add(exchange("admin POST missing key", keyPost(adminKey, variables, "value=v")));
        transcript.add(exchange("admin GET after deletes", keyGet(adminKey, variables)));
        transcript.add(exchange("admin GET missing environment",
            keyGet(adminKey, "/api/v1/environments/999999999/variables")));

        // 3. The member is refused the environment lane uniformly, read and write.
        transcript.add(exchange("member GET {environment}/variables", keyGet(memberKey, variables)));
        transcript.add(exchange("member POST set", keyPost(memberKey, variables,
            "key=" + VAR_PREFIX + "MEMBER&value=v")));

        // 4. The transcript is the stored one, byte for byte.
        String current = String.join("\n", transcript) + "\n";
        String stored = stored();
        if (!current.equals(stored)) {
            Path written = Path.of(System.getProperty("user.dir"), "build", "api-v1", "environments.txt");
            Files.createDirectories(written.getParent());
            Files.writeString(written, current, StandardCharsets.UTF_8);
        }
        assertThat(current).as("step 4: the /api/v1 transcript is the stored one (current at build/api-v1)")
            .isEqualTo(stored);
    }

    /** The project list narrowed to the fixture project, so other classes' projects never enter the transcript. */
    private static Object ownProject(HttpResponse<String> response) {
        Map<?, ?> body = Zenit.DRY.fromJson(response.body(), Map.class);
        List<Object> own = new ArrayList<>();
        for (Object project : (List<?>) body.get("projects")) {
            if (project instanceof Map<?, ?> entry && Integer.valueOf(projectId).equals(number(entry.get("id")))) {
                own.add(project);
            }
        }
        return new Answer(response.statusCode(), Map.of("projects", own));
    }

    private record Answer(int status, Object body) {
    }

    private static String exchange(String label, Object answer) {
        boolean projectSubject = label.contains("/api/v1/projects");
        if (answer instanceof Answer parsed) {
            return label + "\n  " + parsed.status() + " "
                + Json.stringify(canonical(null, parsed.body(), projectSubject));
        }
        HttpResponse<?> response = (HttpResponse<?>) answer;
        String type = response.headers().firstValue("Content-Type").orElse("");
        if (!type.contains("json")) {
            return label + "\n  " + response.statusCode();
        }
        Object body = Zenit.DRY.fromJson(String.valueOf(response.body()), Map.class);
        return label + "\n  " + response.statusCode() + " " + Json.stringify(canonical(null, body, projectSubject));
    }

    /**
     * Sorted keys, and every generated id or timestamp as its fixture placeholder.
     *
     * AIDEV-NOTE: an id is named by WHERE it sits, never by its number alone: the fixture project and environment are
     * fresh rows of two tables, so their numbers can be equal.
     *
     * @param project whether an id at this level names a project (a project answer's own id) rather than an
     *                environment (a variables answer's id, every entry of an environments list)
     */
    private static Object canonical(String key, Object value, boolean project) {
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((name, child) -> sorted.put(String.valueOf(name),
                canonical(String.valueOf(name), child, project && !"environments".equals(String.valueOf(name)))));
            return sorted;
        }
        if (value instanceof List<?> list) {
            List<Object> items = new ArrayList<>();
            list.forEach(item -> items.add(canonical(key, item, project)));
            return items;
        }
        if ("created_at".equals(key)) {
            return "{created_at}";
        }
        Integer number = number(value);
        if ("id".equals(key) && number != null) {
            if (project && number == projectId) return "{project}";
            if (!project && number == environmentId) return "{environment}";
            if (!project && number == emptyEnvironmentId) return "{empty_environment}";
        }
        return value;
    }

    private static Integer number(Object value) {
        return value instanceof Number number ? Integer.valueOf(number.intValue()) : null;
    }

    private static String stored() {
        try (InputStream in = EnvironmentApiBaselineBrowserTest.class.getResourceAsStream(STORED)) {
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static int environment(String name, String description) {
        Model environments = Models.get(EnvironmentModel.class);
        Row row = environments.createEmptyRow();
        row.set(EnvironmentModel.PROJECT_ID, projectId);
        row.set(EnvironmentModel.NAME, name);
        row.set(EnvironmentModel.DESCRIPTION, description);
        environments.save(row);
        return row.get(EnvironmentModel.ID);
    }
}
