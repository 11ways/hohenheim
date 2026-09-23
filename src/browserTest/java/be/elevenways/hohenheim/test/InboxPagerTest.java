package be.elevenways.hohenheim.test;

import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.comms.server.CommsInbox;
import be.elevenways.zenit.comms.server.CommsInboxModel;
import be.elevenways.zenit.comms.server.CommsInboxOwners;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admin inbox pages through plumage's numbered pager over core's page window, never a
 * prev/next-only nav.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class InboxPagerTest extends HohenheimTestBase {

    private static final String MARKER = "inbox-pager-test";

    @Test
    void theInboxPagesThroughNumberedRungs() throws Exception {
        String owner = CommsInboxOwners.userKey(harnessAdminId());
        try {
            // 1. Exactly one page of items (whatever alerts the shared server already
            //    delivered to this admin count too): the pager renders nothing at all.
            long existing = Models.get(CommsInboxModel.class).unarchivedCountFor(owner);
            assertThat(existing).as("step 1: the harness admin's inbox fits one page")
                .isLessThanOrEqualTo(CommsInbox.DEFAULT_LIMIT);
            seed(owner, (int) (CommsInbox.DEFAULT_LIMIT - existing));
            HttpResponse<String> single = adminGet("/admin/inbox");
            assertThat(single.statusCode()).as("step 1: the inbox renders").isEqualTo(200);
            assertThat(single.body()).as("step 1: one page needs no pager")
                .doesNotContain("page=2");

            // 2. One item more: page 1 links a numbered rung to page 2, and the old
            //    prev/next-only buttons are gone.
            seed(owner, 1);
            HttpResponse<String> first = adminGet("/admin/inbox");
            assertThat(first.body()).as("step 2: page 1 carries the numbered pager")
                .contains("data-admin-inbox-pagination")
                .contains("page=2")
                .doesNotContain("data-admin-inbox-next");

            // 3. Page 2 renders under the same pager (the window has two pages either way).
            HttpResponse<String> second = adminGet("/admin/inbox?page=2");
            assertThat(second.statusCode()).as("step 3: page 2 renders").isEqualTo(200);
            assertThat(second.body()).as("step 3: page 2 still carries the pager")
                .contains("data-admin-inbox-pagination");
        } finally {
            for (Row row : Models.get(CommsInboxModel.class).find()
                    .where(CommsInboxModel.NOTIFICATION_KEY.eq(MARKER)).all()) {
                Models.get(CommsInboxModel.class).delete(row);
            }
        }
    }

    private static long harnessAdminId() {
        Row admin = AuthModels.users().find().where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        assertThat(admin).as("the harness admin exists").isNotNull();
        return ((Integer) admin.get(UserModel.ID)).longValue();
    }

    private static void seed(String owner, int count) {
        for (int i = 0; i < count; i++) {
            Row item = Models.get(CommsInboxModel.class).createEmptyRow();
            item.set(CommsInboxModel.RECIPIENT, owner);
            item.set(CommsInboxModel.NOTIFICATION_KEY, MARKER);
            item.set(CommsInboxModel.TITLE, "Pager item " + i);
            item.set(CommsInboxModel.CREATED_AT, Now.instant());
            item.set(CommsInboxModel.UPDATED_AT, Now.instant());
            Models.get(CommsInboxModel.class).save(item);
        }
    }
}
