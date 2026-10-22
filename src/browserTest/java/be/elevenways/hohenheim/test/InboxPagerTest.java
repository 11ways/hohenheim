package be.elevenways.hohenheim.test;

import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.notifications.server.NotificationInbox;
import be.elevenways.zenit.notifications.server.NotificationInboxModel;
import be.elevenways.zenit.notifications.server.NotificationInboxOwners;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admin inbox is zenit-notifications' shared inbox screen (Unread / All, one card per record) and pages through
 * its numbered pager over core's page window.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class InboxPagerTest extends HohenheimHttpTestBase {

    private static final String MARKER = "inbox-pager-test";

    @Test
    void theInboxPagesThroughNumberedRungs() throws Exception {
        String owner = NotificationInboxOwners.userKey(harnessAdminId());
        try {
            // 1. Exactly one page of items (whatever alerts the shared server already
            //    delivered to this admin count too): the pager renders nothing at all.
            long existing = Models.get(NotificationInboxModel.class).unarchivedCountFor(owner);
            assertThat(existing).as("step 1: the harness admin's inbox fits one page")
                .isLessThanOrEqualTo(NotificationInbox.DEFAULT_LIMIT);
            seed(owner, (int) (NotificationInbox.DEFAULT_LIMIT - existing));
            HttpResponse<String> single = adminGet("/admin/inbox");
            assertThat(single.statusCode()).as("step 1: the inbox renders").isEqualTo(200);
            assertThat(single.body()).as("step 1: it is the shared inbox screen, with its Unread / All tabs")
                .contains("<znt-inbox-page")
                .contains("znt-inbox-show-unread")
                .contains("znt-inbox-show-all");
            assertThat(single.body()).as("step 1: one page needs no pager")
                .doesNotContain("<pl-pager")
                .doesNotContain("page=2");
            assertThat(single.body().split("<h1", -1).length - 1)
                .as("step 1: the Activity cluster's member page has one heading, the cluster's").isEqualTo(1);
            assertThat(single.body()).as("step 1: and that heading is the cluster's")
                .contains("data-cms-cluster-title");

            // 2. One item more: page 1 links a numbered rung to page 2.
            seed(owner, 1);
            HttpResponse<String> first = adminGet("/admin/inbox");
            assertThat(first.body()).as("step 2: page 1 carries the numbered pager")
                .contains("<pl-pager")
                .contains("page=2");
            String body = first.body();
            int actions = body.indexOf("<pl-page-header-actions");
            int markAll = body.indexOf("<znt-inbox-mark-all");
            assertThat(actions).as("step 2: the page header has actions").isNotNegative();
            assertThat(markAll).as("step 2: and Mark all read stands in them")
                .isGreaterThan(actions).isLessThan(body.indexOf("</pl-page-header-actions>", actions));

            // 3. Page 2 renders under the same pager (the window has two pages either way).
            HttpResponse<String> second = adminGet("/admin/inbox?page=2");
            assertThat(second.statusCode()).as("step 3: page 2 renders").isEqualTo(200);
            assertThat(second.body()).as("step 3: page 2 still carries the pager")
                .contains("<pl-pager");
        } finally {
            for (Row row : Models.get(NotificationInboxModel.class).find()
                    .where(NotificationInboxModel.NOTIFICATION_KEY.eq(MARKER)).all()) {
                Models.get(NotificationInboxModel.class).delete(row);
            }
        }
    }

    private static UUID harnessAdminId() {
        Row admin = Models.get(UserModel.class).find().where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        assertThat(admin).as("the harness admin exists").isNotNull();
        return admin.get(UserModel.ID);
    }

    private static void seed(String owner, int count) {
        for (int i = 0; i < count; i++) {
            Row item = Models.get(NotificationInboxModel.class).createEmptyRow();
            item.set(NotificationInboxModel.RECIPIENT, owner);
            item.set(NotificationInboxModel.NOTIFICATION_KEY, MARKER);
            item.set(NotificationInboxModel.TITLE, "Pager item " + i);
            item.set(NotificationInboxModel.CREATED_AT, Now.instant());
            item.set(NotificationInboxModel.UPDATED_AT, Now.instant());
            Models.get(NotificationInboxModel.class).save(item);
        }
    }
}
