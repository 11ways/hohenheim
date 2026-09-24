package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.StoredRows;
import be.elevenways.hohenheim.server.cms.AccessListResource;
import be.elevenways.hohenheim.server.cms.SiteResource;
import be.elevenways.hohenheim.server.instance.ApplicationKind;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.quota.SiteQuota;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.activity.ActivityModel;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.quota.Quotas;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Sites and instances soft-delete through zenit's SoftDeleteBehaviour: a trashed record leaves
 * every default read, keeps its unique slug, comes back through the behaviour's restore with its
 * quota re-booked, and only a forceDelete removes it physically.
 *
 * AIDEV-NOTE: the behaviour replaced a hand-rolled soft delete (a deleted_at stamp plus ~90
 * hand-spelled {@code deleted_at IS NULL} filters). These journeys pin what the switch must not
 * move -- the quota transition, the delete activity, the slug uniqueness -- and the two readings
 * it corrected: the access-list delete dialog named trashed sites as "gated", and an application
 * destroy was recorded as a bare "update" instead of the destroy it is.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class SoftDeleteJourneyTest {

    private static final String PREFIX = "softdel-";
    private static final String SITE_BUCKET = SiteQuota.bucketKeyOf("");

    private static SqlDatasource datasource;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = TestDatabases.freshDatasource();
        HohenheimTestRuntime.ensureBooted();
    }

    @Test
    void aTrashedSiteIsHiddenKeepsItsSlugRestoresAndPurges() {
        Db.run(datasource, () -> {
            SiteModel sites = Models.get(SiteModel.class);
            SiteResource resource = new SiteResource();
            AccessListResource lists = new AccessListResource();
            AccessContext anyone = AccessContext.anonymous();

            // 1. A live site gated by an access list: charged one site slot, and the list's
            //    delete dialog names it as a site the delete would open.
            int listId = accessList(PREFIX + "list");
            long beforeCreate = Quotas.usedOf(SITE_BUCKET);
            int siteId = site(PREFIX + "journey", listId);
            assertThat(Quotas.usedOf(SITE_BUCKET))
                .as("step 1: a live site spends a slot").isEqualTo(beforeCreate + 1);
            assertThat(gatingKey(lists, listId))
                .as("step 1: the list's dialog names the live site it gates")
                .isEqualTo("delete_confirm_gating");

            // 2. The admin delete trashes it through the behaviour: gone from every default
            //    read (find, count, the record page), still stored, the slot handed back and
            //    the delete recorded as the soft delete it is.
            long beforeTrash = Quotas.usedOf(SITE_BUCKET);
            resource.deleteRow(sites.findById(siteId), anyone);
            assertThat(sites.findById(siteId))
                .as("step 2: a default find no longer sees the trashed site").isNull();
            assertThat(sites.find().where(SiteModel.ID.eq(siteId)).count())
                .as("step 2: nor does a default count").isZero();
            assertThat(resource.loadRow(siteId, anyone))
                .as("step 2: the record page loads nothing (a 404)").isNull();
            Row trashed = StoredRows.byId(sites, siteId);
            assertThat(trashed).as("step 2: the row is still stored").isNotNull();
            assertThat((Object) trashed.get(SiteModel.DELETED_AT))
                .as("step 2: stamped trashed").isNotNull();
            assertThat(Quotas.usedOf(SITE_BUCKET))
                .as("step 2: the trash transition handed the slot back").isEqualTo(beforeTrash - 1);
            List<Row> deletes = activityFor(SiteModel.MODEL_ID.toString(), siteId,
                ActivityLog.ACTION_DELETE);
            assertThat(deletes).as("step 2: the trash is recorded as one delete").hasSize(1);
            assertThat((String) deletes.get(0).get(ActivityModel.DETAIL))
                .as("step 2: named as the soft delete it is").isEqualTo("soft-delete");

            // 3. A set-based update is scoped like a find: it cannot reach the trashed row.
            assertThat(sites.find().where(SiteModel.ID.eq(siteId))
                    .assign(SiteModel.DESCRIPTION, "touched").bypassBehaviours().updateAll())
                .as("step 3: an updateAll matches no trashed row").isZero();

            // 4. BUG FIXED: a trashed site gates nothing, so the list's dialog stops naming it.
            assertThat(gatingKey(lists, listId))
                .as("step 4: the list's dialog no longer names the trashed site")
                .isEqualTo("delete_confirm_named");

            // 5. Deleting it again is a no-op, and its slug stays taken: the unique index
            //    sees the trashed row, so a second site with that slug is refused.
            assertThat(sites.delete(siteId))
                .as("step 5: a trashed row is invisible to a second delete").isFalse();
            assertThat(catchThrowable(() -> site(PREFIX + "journey", null)))
                .as("step 5: the trashed site still owns its slug").isNotNull();
            assertThat(sites.find().withTrashed().where(SiteModel.SLUG.eq(PREFIX + "journey")).count())
                .as("step 5: and exactly one row carries it").isEqualTo(1);

            // 6. The behaviour's restore brings it back: visible, the slot re-booked, the
            //    restore on the record's history, and the list gating it again.
            long beforeRestore = Quotas.usedOf(SITE_BUCKET);
            SiteModel.SOFT_DELETE.restore(StoredRows.byId(sites, siteId));
            Row restored = sites.findById(siteId);
            assertThat(restored).as("step 6: a default find sees the restored site").isNotNull();
            assertThat((Object) restored.get(SiteModel.DELETED_AT))
                .as("step 6: no longer stamped").isNull();
            assertThat(Quotas.usedOf(SITE_BUCKET))
                .as("step 6: the restore re-booked its slot").isEqualTo(beforeRestore + 1);
            assertThat(activityFor(SiteModel.MODEL_ID.toString(), siteId, ActivityLog.ACTION_RESTORE))
                .as("step 6: the restore is recorded as one").hasSize(1);
            assertThat(gatingKey(lists, listId))
                .as("step 6: the restored site is gated by the list again")
                .isEqualTo("delete_confirm_gating");

            // 7. PURGE: trash it again, then forceDelete removes the row physically -- and a
            //    purge of a trashed row releases nothing twice.
            assertThat(sites.delete(siteId)).as("step 7: trashed again").isTrue();
            long beforePurge = Quotas.usedOf(SITE_BUCKET);
            assertThat(SiteModel.SOFT_DELETE.forceDelete(StoredRows.byId(sites, siteId)))
                .as("step 7: forceDelete removes the trashed row").isTrue();
            assertThat(StoredRows.byId(sites, siteId))
                .as("step 7: nothing is stored any more").isNull();
            assertThat(Quotas.usedOf(SITE_BUCKET))
                .as("step 7: the purge of a trashed row moves no quota").isEqualTo(beforePurge);

            Models.get(AccessListModel.class).delete(listId);
        });
    }

    @Test
    void destroyingAnApplicationRecordsTheDestroyItIs() {
        Db.run(datasource, () -> {
            Model instances = Models.get(InstanceModel.class);
            int applicationId = application(PREFIX + "application");
            try {
                // 1. An application has no container, so its destroy is the release lane
                //    followed by the trash write.
                new InstanceService().destroy(applicationId);
                assertThat(instances.findById(applicationId))
                    .as("step 1: the destroyed application leaves the default reads").isNull();
                assertThat((Object) StoredRows.byId(instances, applicationId).get(InstanceModel.DELETED_AT))
                    .as("step 1: and is kept trashed").isNotNull();

                // 2. BUG FIXED: the application lane saved deleted_at bare, recorded as an
                //    "update"; it now takes the one trash write every destroy shares.
                List<Row> deletes = activityFor(InstanceModel.MODEL_ID.toString(), applicationId,
                    ActivityLog.ACTION_DELETE);
                assertThat(deletes).as("step 2: the destroy is recorded as one delete").hasSize(1);
                assertThat((String) deletes.get(0).get(ActivityModel.DETAIL))
                    .as("step 2: named as the verified destroy it is")
                    .isEqualTo(InstanceService.ACTIVITY_DESTROY_DETAIL);
                assertThat(activityFor(InstanceModel.MODEL_ID.toString(), applicationId,
                        ActivityLog.ACTION_UPDATE))
                    .as("step 2: and never as a bare update").isEmpty();

                // 3. Destroying it again refuses (there is no live record) and writes nothing.
                assertThat(catchThrowable(() -> new InstanceService().destroy(applicationId)))
                    .as("step 3: a trashed record is not destroyed twice")
                    .isInstanceOf(Violations.class);
                assertThat(activityFor(InstanceModel.MODEL_ID.toString(), applicationId,
                        ActivityLog.ACTION_DELETE))
                    .as("step 3: and nothing more is recorded").hasSize(1);
            } finally {
                HardDeletes.byId(instances, applicationId);
            }
        });
    }

    // -- fixtures ---------------------------------------------------------------

    private static int site(String slug, Integer accessListId) {
        Row row = Models.get(SiteModel.class).createEmptyRow();
        row.set(SiteModel.NAME, slug);
        row.set(SiteModel.SLUG, slug);
        row.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        row.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp/" + slug));
        row.set(SiteModel.STATUS, SiteModel.STATUS_ACTIVE);
        row.set(SiteModel.ENABLED, false);
        row.set(SiteModel.ACCESS_LIST_ID, accessListId);
        Models.get(SiteModel.class).save(row);
        return row.get(SiteModel.ID);
    }

    private static int accessList(String name) {
        Row row = Models.get(AccessListModel.class).createEmptyRow();
        row.set(AccessListModel.NAME, name);
        Models.get(AccessListModel.class).save(row);
        return row.get(AccessListModel.ID);
    }

    /** A release-managed application with no release history (the DeployControlSettleTest shape). */
    private static int application(String name) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, ApplicationKind.ID.toString());
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "alpine", "tag", "latest")));
        row.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    private static String gatingKey(AccessListResource lists, int listId) {
        return lists.deleteConfirmationFor(Models.get(AccessListModel.class).findById(listId))
            .body().key();
    }

    private static List<Row> activityFor(String model, int recordId, String action) {
        return Models.get(ActivityModel.class).find()
            .where(ActivityModel.MODEL.eq(model))
            .where(ActivityModel.RECORD_ID.eq(String.valueOf(recordId)))
            .where(ActivityModel.ACTION.eq(action))
            .all();
    }
}
