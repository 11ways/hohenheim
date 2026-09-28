package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.ProtectedPathModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
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
import java.util.ArrayList;
import java.util.List;

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
        // The rows a site owns go first: their foreign keys hold the site, trashed or not.
        List<Integer> sites = new ArrayList<>();
        for (Row site : Models.get(SiteModel.class).find().withTrashed()
                .where(SiteModel.NAME.startsWith(PREFIX)).all()) {
            sites.add(site.get(SiteModel.ID));
        }
        if (!sites.isEmpty()) {
            HardDeletes.where(Models.get(ProtectedPathModel.class), ProtectedPathModel.SITE_ID.in(sites));
            HardDeletes.where(Models.get(SiteDomainModel.class), SiteDomainModel.SITE_ID.in(sites));
        }
        HardDeletes.where(Models.get(SiteModel.class), SiteModel.NAME.startsWith(PREFIX));
        HardDeletes.where(Models.get(AccessListModel.class), AccessListModel.NAME.startsWith(PREFIX));
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
        // Asserted on the ROW, never the name: the delete's toast names the site too, and a page requested the
        // instant the previous response arrived can still carry it (the flash is acknowledged after the write).
        assertThat(adminGet("/admin/sites?filter.archived=true").body())
            .as("step 1: the Trash lists the trashed site").contains(rowOf(oak));
        assertThat(adminGet("/admin/sites").body())
            .as("step 1: the live list does not").doesNotContain(rowOf(oak))
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

        // 5. Live, the site's Domains and Protected paths tabs offer their writes (the counterfactual).
        int cedar = site("cedar");
        int list = accessList();
        int hostname = domain(cedar);
        int guard = protectedPath(cedar, list, "/private");
        String domainDelete = "/admin/domains/" + hostname + "/delete";
        String pathDelete = "/admin/protected-paths/" + guard + "/delete";
        assertThat(adminGet(domainsTab(cedar)).body()).as("step 5: a live site's Domains tab offers an add")
            .contains("add-domain-link").as("step 5: and a remove per hostname").contains(domainDelete);
        assertThat(adminGet(pathsTab(cedar)).body()).as("step 5: its Protected paths tab offers an add")
            .contains("add-protected-path-link").as("step 5: and a remove per path").contains(pathDelete);

        // 6. Trashed, everything under the site is read-only: both tabs still render from the Trash with their rows,
        //    offer no add and no remove, and the writes those affordances led to are refused.
        delete(cedar);
        HttpResponse<String> trashedDomains = adminGet(domainsTab(cedar));
        assertThat(trashedDomains.statusCode()).as("step 6: the Domains tab of a trashed site renders").isEqualTo(200);
        assertThat(trashedDomains.body()).as("step 6: with its hostname").contains(PREFIX + "cedar.test")
            .as("step 6: and no add").doesNotContain("add-domain-link")
            .as("step 6: and no remove").doesNotContain(domainDelete);
        HttpResponse<String> trashedPaths = adminGet(pathsTab(cedar));
        assertThat(trashedPaths.statusCode()).as("step 6: the Protected paths tab renders").isEqualTo(200);
        assertThat(trashedPaths.body()).as("step 6: with its path").contains("/private")
            .as("step 6: and no add").doesNotContain("add-protected-path-link")
            .as("step 6: and no remove").doesNotContain(pathDelete);
        assertThat(adminPostForm("/admin/protected-paths/" + guard, "path=%2Felsewhere").statusCode())
            .as("step 6: a path's update is refused").isEqualTo(403);
        assertThat(adminPostForm(pathDelete, confirmed("")).statusCode())
            .as("step 6: a path's delete is refused").isEqualTo(403);
        adminPostForm("/admin/protected-paths/new",
            "site_id=" + cedar + "&access_list_id=" + list + "&path=%2Fnew");
        assertThat(pathsOf(cedar)).as("step 6: no path was written under the trashed site")
            .containsExactly("/private");

        // 7. Restored, the tabs offer their writes again.
        adminPostForm("/admin/sites/" + cedar + "/action/trash_restore", "");
        assertThat(adminGet(domainsTab(cedar)).body()).as("step 7: the Domains tab offers its add again")
            .contains("add-domain-link").contains(domainDelete);
        assertThat(adminGet(pathsTab(cedar)).body()).as("step 7: the Protected paths tab too")
            .contains("add-protected-path-link").contains(pathDelete);
    }

    // -- fixtures ---------------------------------------------------------------

    private int site(String name) throws Exception {
        HttpResponse<String> created = adminPostForm("/admin/sites/new", "name=" + PREFIX + name + "&" + SITE_FORM);
        assertThat(created.statusCode()).as("the site " + name + " is created").isIn(200, 302, 303);
        Row row = Models.get(SiteModel.class).find().where(SiteModel.NAME.eq(PREFIX + name)).first();
        assertThat(row).as("the site " + name + " is stored").isNotNull();
        return row.get(SiteModel.ID);
    }

    /** A list row's own marker, which nothing but the row of that site renders. */
    private static String rowOf(int siteId) {
        return "data-row-key=\"" + siteId + "\"";
    }

    private static String domainsTab(int siteId) {
        return "/admin/sites/" + siteId + "/page/domains";
    }

    private static String pathsTab(int siteId) {
        return "/admin/sites/" + siteId + "/page/protected-paths";
    }

    private static int accessList() {
        Row row = Models.get(AccessListModel.class).createEmptyRow();
        row.set(AccessListModel.NAME, PREFIX + "list");
        Models.get(AccessListModel.class).save(row);
        return row.get(AccessListModel.ID);
    }

    /** One exact hostname, {@code <prefix>cedar.test}, on the site. */
    private static int domain(int siteId) {
        Row row = Models.get(SiteDomainModel.class).createEmptyRow();
        row.set(SiteDomainModel.SITE_ID, siteId);
        row.set(SiteDomainModel.HOSTNAME, PREFIX + "cedar.test");
        row.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        Models.get(SiteDomainModel.class).save(row);
        return row.get(SiteDomainModel.ID);
    }

    private static int protectedPath(int siteId, int listId, String path) {
        Row row = Models.get(ProtectedPathModel.class).createEmptyRow();
        row.set(ProtectedPathModel.SITE_ID, siteId);
        row.set(ProtectedPathModel.ACCESS_LIST_ID, listId);
        row.set(ProtectedPathModel.PATH, path);
        Models.get(ProtectedPathModel.class).save(row);
        return row.get(ProtectedPathModel.ID);
    }

    private static List<String> pathsOf(int siteId) {
        List<String> paths = new ArrayList<>();
        for (Row row : Models.get(ProtectedPathModel.class).findBySiteId(siteId)) {
            paths.add(row.get(ProtectedPathModel.PATH));
        }
        return paths;
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
