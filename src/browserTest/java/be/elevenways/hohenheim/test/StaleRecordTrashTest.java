package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.EnvironmentModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ProjectModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.StoredRows;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A soft delete is a SAVE of the whole row, so a record whose unrelated columns stopped
 * satisfying a shape rule must still be deletable: the rule judges what a write moves, and
 * a pure trash moves nothing but deleted_at.
 *
 * AIDEV-NOTE: observed in the final --all run of the soft-delete wave: PaasApiTest's cleanup
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
                int userId = ApiSupport.user(PREFIX + "user@stale.test", "Stale User");
                RecordGrants.grant(GrantSubjectType.USER, userId, InstanceModel.MODEL_ID, instanceId,
                    HohenheimAccess.MANAGE, true);
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
    void aSiteInAShapeTheRulesNowRefuseIsStillDeletable() {
        Db.run(datasource, () -> {
            SiteModel sites = Models.get(SiteModel.class);
            int siteId = staticSite(PREFIX + "legacy");
            try {
                // 1. The stored shape predates the rule: a static site naming an instance,
                //    written around the save pipeline the way an older release's data sits.
                sites.find().where(SiteModel.ID.eq(siteId))
                    .assign(SiteModel.INSTANCE_ID, 424242).bypassBehaviours().updateAll();
                Throwable edit = catchThrowable(() -> {
                    Row row = sites.findById(siteId);
                    row.set(SiteModel.DESCRIPTION, "an unrelated edit");
                    sites.save(row);
                });
                assertThat(edit)
                    .as("step 1: the shape rule judges every whole-row save of it")
                    .isInstanceOf(Violations.class)
                    .hasMessageContaining("upstream_instance_unexpected");

                // 2. BUG FIXED: the soft delete is such a save, and is no longer refused.
                assertThat(sites.delete(siteId)).as("step 2: the site is deleted").isTrue();
                assertThat(sites.findById(siteId)).as("step 2: and is gone from default reads")
                    .isNull();

                // 3. The exemption is the TRASH alone: a restore brings the record back live
                //    and is judged as a live record, so the stale shape is named, and the row
                //    stays trashed.
                Throwable restore = catchThrowable(() ->
                    SiteModel.SOFT_DELETE.restore(StoredRows.byId(sites, siteId)));
                assertThat(restore)
                    .as("step 3: a restore of the stale shape is refused by name")
                    .isInstanceOf(Violations.class)
                    .hasMessageContaining("upstream_instance_unexpected");
                assertThat((Object) StoredRows.byId(sites, siteId).get(SiteModel.DELETED_AT))
                    .as("step 3: and the site stays trashed").isNotNull();
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
