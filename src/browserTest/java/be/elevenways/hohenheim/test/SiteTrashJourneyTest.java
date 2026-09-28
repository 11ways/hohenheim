package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.quota.SiteQuota;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.activity.ActivityModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.orm.quota.Quotas;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admin sites list's Trash, zenit-cms's generated one: a deleted site is listed there, restored with its quota
 * slot re-booked, and deleted for good, one by one and in bulk, each write attributed to the administrator.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class SiteTrashJourneyTest extends HohenheimTestBase {

    private static final String PREFIX = "trash-journey-";
    private static final String SITE_FORM =
        "upstream_kind=hohenheim%3Aaddress&settings.forward_host=127.0.0.1&settings.forward_port=9090";
    private static final String SITE_BUCKET = SiteQuota.bucketKeyOf("");

    @AfterEach
    void cleanUp() {
        HardDeletes.where(Models.get(SiteModel.class), SiteModel.NAME.startsWith(PREFIX));
    }

    @Test
    void aDeletedSiteIsListedInTheTrashRestoredAndDeletedForGood() throws Exception {
        int oak = site("oak");
        int pine = site("pine");
        int elm = site("elm");

        // 1. The admin delete trashes the site: the Trash lists it, the live list does not.
        long beforeTrash = Quotas.usedOf(SITE_BUCKET);
        delete(oak);
        assertThat((Object) stored(oak).get(SiteModel.DELETED_AT)).as("step 1: the delete trashed the site")
            .isNotNull();
        assertThat(Quotas.usedOf(SITE_BUCKET)).as("step 1: and handed its slot back").isEqualTo(beforeTrash - 1);
        assertThat(adminGet("/admin/sites?filter.archived=true").body())
            .as("step 1: the Trash lists the trashed site").contains(PREFIX + "oak");
        assertThat(adminGet("/admin/sites").body())
            .as("step 1: the live list does not").doesNotContain(PREFIX + "oak")
            .as("step 1: and links the Trash").contains("filter.archived=true");

        // 2. Restore brings it back live, re-books its slot and records the restore under the administrator.
        adminPostForm("/admin/sites/" + oak + "/action/trash_restore", "");
        assertThat((Object) stored(oak).get(SiteModel.DELETED_AT)).as("step 2: the restore untrashed the site")
            .isNull();
        assertThat(Quotas.usedOf(SITE_BUCKET)).as("step 2: its slot is booked again").isEqualTo(beforeTrash);
        Row restore = lastActivity(oak, ActivityLog.ACTION_RESTORE);
        assertThat(restore).as("step 2: the restore is recorded").isNotNull();
        assertThat((String) restore.get(ActivityModel.ACTOR)).as("step 2: attributed to the administrator")
            .isEqualTo(adminId());

        // 3. Trashed again and deleted for good: the row is gone and the purge moves no quota.
        delete(oak);
        long beforePurge = Quotas.usedOf(SITE_BUCKET);
        adminPostForm("/admin/sites/" + oak + "/action/trash_purge", confirmed(""));
        assertThat(stored(oak)).as("step 3: the permanent delete removed the row").isNull();
        assertThat(Quotas.usedOf(SITE_BUCKET)).as("step 3: a purge of a trashed site releases nothing twice")
            .isEqualTo(beforePurge);
        Row purge = lastActivity(oak, ActivityLog.ACTION_DELETE);
        assertThat((String) purge.get(ActivityModel.DETAIL)).as("step 3: recorded as the hard delete it is")
            .isNull();
        assertThat((String) purge.get(ActivityModel.ACTOR)).as("step 3: attributed to the administrator")
            .isEqualTo(adminId());

        // 4. In bulk: two trashed sites come back together, then go for good together.
        delete(pine);
        delete(elm);
        adminPostForm("/admin/sites/bulk/trash_restore", "ids=" + pine + "&ids=" + elm);
        assertThat((Object) stored(pine).get(SiteModel.DELETED_AT)).as("step 4: the first is live again").isNull();
        assertThat((Object) stored(elm).get(SiteModel.DELETED_AT)).as("step 4: the second too").isNull();
        delete(pine);
        delete(elm);
        adminPostForm("/admin/sites/bulk/trash_purge", confirmed("ids=" + pine + "&ids=" + elm));
        assertThat(stored(pine)).as("step 4: the first is gone for good").isNull();
        assertThat(stored(elm)).as("step 4: the second too").isNull();
    }

    // -- fixtures ---------------------------------------------------------------

    private int site(String name) throws Exception {
        HttpResponse<String> created = adminPostForm("/admin/sites/new", "name=" + PREFIX + name + "&" + SITE_FORM);
        assertThat(created.statusCode()).as("the site " + name + " is created").isIn(200, 302, 303);
        Row row = Models.get(SiteModel.class).find().where(SiteModel.NAME.eq(PREFIX + name)).first();
        assertThat(row).as("the site " + name + " is stored").isNotNull();
        return row.get(SiteModel.ID);
    }

    private void delete(int siteId) throws Exception {
        adminPostForm("/admin/sites/" + siteId + "/delete", confirmed(""));
        assertThat((Object) stored(siteId).get(SiteModel.DELETED_AT)).as("site " + siteId + " is trashed")
            .isNotNull();
    }

    private static Row stored(int siteId) {
        return Models.get(SiteModel.class).find().withTrashed().where(SiteModel.ID.eq(siteId)).first();
    }

    private static Row lastActivity(int siteId, String action) {
        return Models.get(ActivityModel.class).find()
            .where(ActivityModel.MODEL.eq(SiteModel.MODEL_ID.toString()))
            .where(ActivityModel.RECORD_ID.eq(String.valueOf(siteId)))
            .where(ActivityModel.ACTION.eq(action))
            .orderBy(ActivityModel.ID, SortOrder.DESC)
            .first();
    }

    private static String adminId() {
        Row admin = AuthModels.users().find().where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        return String.valueOf((Object) admin.get(UserModel.ID));
    }
}
