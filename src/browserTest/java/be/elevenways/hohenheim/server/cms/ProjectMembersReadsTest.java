package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.ProjectModel;
import be.elevenways.hohenheim.server.project.Projects;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.zenit.cms.common.resource.CellMapper;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.test.support.DatasourceProbe;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The project member list and the project list's count columns read in a fixed number of statements: two projects
 * and twenty, each with its own member, read the same count, every member shows under its project with its own name,
 * and each project counts its one member.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class ProjectMembersReadsTest extends HohenheimTestBase {

    private static final String PREFIX = "pmreads-" + UUID.randomUUID().toString().substring(0, 8) + "-";

    @Test
    void theMemberListReadsFlatAsProjectsGrow() {
        List<Row> projects = new ArrayList<>();
        try {
            // 1. Two projects, each with one member: the list names both members under their projects.
            for (int i = 0; i < 2; i++) {
                projects.add(project(i));
            }
            Measured two = measure();
            assertMembers("step 1", two.rows(), projects);
            Counted twoCounts = counts("step 1", projects);

            // 2. Twenty projects, each with its own member: the same statement count, every member listed.
            for (int i = 2; i < 20; i++) {
                projects.add(project(i));
            }
            Measured twenty = measure();
            assertMembers("step 2", twenty.rows(), projects);
            assertThat(twenty.statements()).as("step 2: twenty projects read as many statements as two")
                .isEqualTo(two.statements());

            // 3. The project list's count columns (members, owned sites and instances, quota) of twenty projects read
            //    as many statements as those of two.
            Counted twentyCounts = counts("step 3", projects);
            assertThat(twentyCounts.statements()).as("step 3: twenty projects' counts read as many statements as two: "
                + twentyCounts.prepared()).isEqualTo(twoCounts.statements());
        } finally {
            Model model = Models.get(ProjectModel.class);
            for (Row project : projects) {
                HardDeletes.row(model, project);
            }
        }
    }

    private record Measured(List<ProjectMembershipParts.Membership> rows, int statements) {
    }

    private record Counted(int statements, List<String> prepared) {
    }

    /** The list page's count cells of these projects, as its page batch answers them, counting its statements. */
    private static Counted counts(String step, List<Row> projects) {
        AccessContext operator = TenantConduits.operator();
        TableSpec<Row> table = ProjectParts.tableSpec();
        // An empty page first: the quota's default setting is read once per process, never per page.
        ProjectParts.countsPage(List.of(), operator);
        DatasourceProbe probe = DatasourceProbe.on((SqlDatasource) Datasources.getDefault());
        List<Map<String, Object>> cells = new ArrayList<>();
        try (DatasourceProbe.Armed ignored = probe.recordStatements()) {
            probe.reset();
            CellMapper<Row> page = ProjectParts.countsPage(projects, operator);
            for (Row project : projects) {
                Map<String, Object> row = new HashMap<>();
                for (String name : List.of(ProjectParts.MEMBERS_COLUMN, ProjectParts.OWNED_SITES_COLUMN,
                        ProjectParts.OWNED_INSTANCES_COLUMN, ProjectParts.INSTANCE_QUOTA_COLUMN)) {
                    row.put(name, page.cell(project, Objects.requireNonNull(table.column(name))));
                }
                cells.add(row);
            }
        }
        for (int at = 0; at < projects.size(); at++) {
            Map<String, Object> row = cells.get(at);
            String name = projects.get(at).get(ProjectModel.NAME);
            assertThat(row.get(ProjectParts.MEMBERS_COLUMN)).as(step + ": " + name + " counts its one member")
                .isEqualTo(1);
            assertThat(row.get(ProjectParts.INSTANCE_QUOTA_COLUMN)).as(step + ": " + name + " words its quota")
                .isNotNull();
        }
        return new Counted(probe.preparedStatements().size(), List.copyOf(probe.preparedStatements()));
    }

    /** The operator's member list, counting its prepared statements. */
    private static Measured measure() {
        AccessContext operator = TenantConduits.operator();
        DatasourceProbe probe = DatasourceProbe.on((SqlDatasource) Datasources.getDefault());
        List<ProjectMembershipParts.Membership> rows;
        try (DatasourceProbe.Armed ignored = probe.recordStatements()) {
            probe.reset();
            rows = ProjectMembershipParts.memberships(operator);
        }
        return new Measured(rows, probe.preparedStatements().size());
    }

    /** Each project lists exactly its one member, by that member's display name. */
    private static void assertMembers(String step, List<ProjectMembershipParts.Membership> rows, List<Row> projects) {
        for (Row project : projects) {
            int id = project.get(ProjectModel.ID);
            String name = project.get(ProjectModel.NAME);
            assertThat(rows.stream().filter(row -> row.projectId() == id).map(ProjectMembershipParts.Membership::member))
                .as(step + ": " + name + " lists its own member").containsExactly(name + " member");
        }
    }

    /** A project with one user member named after it. */
    private static Row project(int index) {
        Model model = Models.get(ProjectModel.class);
        Row project = model.createEmptyRow();
        project.set(ProjectModel.NAME, PREFIX + index);
        model.save(project);
        UUID member = ApiSupport.user(PREFIX + index + "@members.test", PREFIX + index + " member");
        Projects.addMember(model.findById(project.get(ProjectModel.ID)), member);
        return model.findById(project.get(ProjectModel.ID));
    }
}
