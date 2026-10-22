package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.EnvironmentModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ProjectModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.StoredRows;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.server.project.Projects;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A record whose unrelated columns stopped satisfying a shape rule stays editable, deletable and restorable: the rule
 * judges what a write moves, and a trash or restore moves nothing but deleted_at.
 *
 * AIDEV-NOTE: observed in a full test run: PaasApiTest's cleanup
 * could not delete an instance whose environment grouping had drifted (its owner grants
 * moved after it was placed), because SoftDeleteBehaviour stamps deleted_at through save()
 * and the grouping guard judged the unchanged environment_id. In production that is an
 * operator unable to delete a record with stale data.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class StaleRecordTrashTest {

    private static final String PREFIX = "stale-trash-";

    private static SqlDatasource datasource;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = TestDatabases.freshDatasource();
        HohenheimTestRuntime.ensureBooted();
    }

    @Test
    void aDriftedGroupingNeverBlocksTheDeleteOrTheRestore() {
        Db.run(datasource, () -> {
            InstanceModel instances = Models.get(InstanceModel.class);
            Row project = project(PREFIX + "project");
            int production = environment(project, PREFIX + "production");
            int staging = environment(project, PREFIX + "staging");
            int instanceId = instance(PREFIX + "drifted");
            try {
                // 1. A project-owned instance is placed in its project's environment.
                Projects.adoptRecord(project, InstanceModel.MODEL_ID, instanceId);
                Row placed = instances.findById(instanceId);
                placed.set(InstanceModel.ENVIRONMENT_ID, production);
                instances.save(placed);
                assertThat((Integer) instances.findById(instanceId).get(InstanceModel.ENVIRONMENT_ID))
                    .as("step 1: the owned instance joined the environment").isEqualTo(production);

                // 2. The grouping drifts the way it does in production: an owner grant moves
                //    and the row is not touched. PLACING it anywhere is still refused -- the
                //    anchor that the guard is alive and would judge a whole-row save.
                UUID userId = ApiSupport.user(PREFIX + "user@stale.test", "Stale User");
                RecordGrants.grant(GrantSubjectType.USER, userId, InstanceModel.MODEL_ID, instanceId,
                    HohenheimCapabilities.MANAGE, true);
                Throwable moved = catchThrowable(() -> {
                    Row move = instances.findById(instanceId);
                    move.set(InstanceModel.ENVIRONMENT_ID, staging);
                    instances.save(move);
                });
                assertThat(moved)
                    .as("step 2: moving the drifted record into an environment is refused")
                    .isInstanceOf(Violations.class)
                    .hasMessageContaining("environment_project_mismatch");

                // 3. BUG FIXED: the soft delete saves the whole row, environment_id included,
                //    and used to be refused by that same guard.
                assertThat(instances.delete(instanceId))
                    .as("step 3: the drifted instance is deleted").isTrue();
                assertThat(instances.findById(instanceId))
                    .as("step 3: and leaves every default read").isNull();
                Row trashed = StoredRows.byId(instances, instanceId);
                assertThat((Object) trashed.get(InstanceModel.DELETED_AT))
                    .as("step 3: kept, stamped trashed").isNotNull();
                assertThat((Integer) trashed.get(InstanceModel.ENVIRONMENT_ID))
                    .as("step 3: with its grouping untouched").isEqualTo(production);

                // 4. The restore writes the same unchanged environment back and is not refused
                //    for a drift it did not cause either.
                InstanceModel.SOFT_DELETE.restore(StoredRows.byId(instances, instanceId));
                Row restored = instances.findById(instanceId);
                assertThat(restored).as("step 4: the restored instance is live again").isNotNull();
                assertThat((Integer) restored.get(InstanceModel.ENVIRONMENT_ID))
                    .as("step 4: in the environment it was deleted from").isEqualTo(production);
            } finally {
                HardDeletes.byId(instances, instanceId);
            }
        });
    }

    @Test
    void aSiteInAShapeTheRulesNowRefuseStaysEditableAndDeletableUntilAWriteMovesItsShape() {
        Db.run(datasource, () -> {
            SiteModel sites = Models.get(SiteModel.class);
            int siteId = staticSite(PREFIX + "legacy");
            try {
                // 1. The stored shape predates the rule: a static site naming an instance, stored raw the way an
                //    older release's data sits. An unrelated edit moves neither shape column, so it saves.
                StaleSiteRows.instanceId(siteId, 424242);
                Row edited = sites.findById(siteId);
                edited.set(SiteModel.DESCRIPTION, "an unrelated edit");
                sites.save(edited);
                assertThat((Object) sites.findById(siteId).get(SiteModel.DESCRIPTION))
                    .as("step 1: the shape rule judges only a write that moves its columns")
                    .isEqualTo("an unrelated edit");

                // 2. The soft delete writes deleted_at alone and is not refused either.
                assertThat(sites.delete(siteId)).as("step 2: the site is deleted").isTrue();
                assertThat(sites.findById(siteId)).as("step 2: and is gone from default reads")
                    .isNull();

                // 3. The restore is as narrow: the site is live again with its stored shape untouched.
                SiteModel.SOFT_DELETE.restore(StoredRows.byId(sites, siteId));
                Row restored = sites.findById(siteId);
                assertThat(restored).as("step 3: the restored site is live again").isNotNull();
                assertThat((Object) restored.get(SiteModel.INSTANCE_ID))
                    .as("step 3: with the shape it was stored in").isEqualTo(424242);

                // 4. A write that moves a shape column is judged against the whole stored shape and refused by name.
                Throwable relink = catchThrowable(() -> {
                    Row row = sites.findById(siteId);
                    row.set(SiteModel.INSTANCE_ID, 424243);
                    sites.save(row);
                });
                assertThat(relink)
                    .as("step 4: moving the instance link of a static site is refused by name")
                    .isInstanceOf(Violations.class)
                    .hasMessageContaining("upstream_instance_unexpected");
                assertThat((Object) sites.findById(siteId).get(SiteModel.INSTANCE_ID))
                    .as("step 4: and the stored link is unchanged").isEqualTo(424242);
            } finally {
                HardDeletes.byId(sites, siteId);
            }
        });
    }

    // -- fixtures ---------------------------------------------------------------

    private static Row project(String name) {
        Row row = Models.get(ProjectModel.class).createEmptyRow();
        row.set(ProjectModel.NAME, name);
        Models.get(ProjectModel.class).save(row);
        return Models.get(ProjectModel.class).findById(row.get(ProjectModel.ID));
    }

    private static int environment(Row project, String name) {
        Row row = Models.get(EnvironmentModel.class).createEmptyRow();
        row.set(EnvironmentModel.PROJECT_ID, project.get(ProjectModel.ID));
        row.set(EnvironmentModel.NAME, name);
        Models.get(EnvironmentModel.class).save(row);
        return row.get(EnvironmentModel.ID);
    }

    private static int instance(String name) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(
            Map.of("image", "alpine", "tag", "latest", "command", "sleep 300")));
        row.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    private static int staticSite(String slug) {
        Row row = Models.get(SiteModel.class).createEmptyRow();
        row.set(SiteModel.NAME, slug);
        row.set(SiteModel.SLUG, slug);
        row.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        row.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp/" + slug));
        row.set(SiteModel.STATUS, SiteModel.STATUS_ACTIVE);
        row.set(SiteModel.ENABLED, false);
        Models.get(SiteModel.class).save(row);
        return row.get(SiteModel.ID);
    }
}
