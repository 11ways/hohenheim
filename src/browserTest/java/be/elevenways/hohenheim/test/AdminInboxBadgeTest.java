package be.elevenways.hohenheim.test;

import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.common.channel.ChannelHub;
import be.elevenways.zenit.common.live.LiveChannel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.comms.server.CommsInboxModel;
import be.elevenways.zenit.comms.server.CommsInboxOwners;
import be.elevenways.zenit.server.live.LiveInvalidations;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.TimeoutError;
import org.junit.jupiter.api.Test;

import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The operator's inbox entry is badged with their unread items, and the badge follows an alert
 * that lands while any page of the panel is open: the page the operator is reading is where a
 * background failure has to find them, not the inbox they have not opened yet.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
class AdminInboxBadgeTest extends HohenheimTestBase {

    private static final String BADGE = "pl-app-sidebar pl-nav-item[href='/admin/inbox'] pl-badge";

    @Test
    void theInboxBadgeFollowsAnAlertWhileThePanelStaysOpen() throws Exception {
        Row admin = AuthModels.users().find().where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        String owner = CommsInboxOwners.userKey(((Integer) admin.get(UserModel.ID)).longValue());
        CommsInboxModel inbox = Models.get(CommsInboxModel.class);
        long baseline = inbox.unreadCountFor(owner);

        // 1. A panel page that is not the inbox: the sidebar badges the entry with what is unread,
        //    and the shell watches the inbox's live feed for it.
        page.setViewportSize(1400, 900);
        navigateToApp("/admin/sites");
        waitForHydration();
        await(() -> ChannelHub.subscriberCount(LiveChannel.CHANNEL,
                LiveInvalidations.topicOf(CommsInboxModel.MODEL_ID)) > 0,
            "step 1: the open panel watches the inbox feed");
        assertThat(badge()).as("step 1: the entry counts what is unread").isEqualTo(baseline);
        page.evaluate("() => { window.__inboxBadgeProbe = 'alive'; }");

        // 2. An alert lands in the operator's inbox from outside every page: the badge counts it
        //    in place.
        Row item = inbox.createEmptyRow();
        item.set(CommsInboxModel.RECIPIENT, owner);
        item.set(CommsInboxModel.NOTIFICATION_KEY, "hohenheim:inbox_badge_test");
        item.set(CommsInboxModel.TITLE, "Certificate renewal failed");
        item.set(CommsInboxModel.CREATED_AT, Now.instant());
        inbox.save(item);
        await(() -> badge() == baseline + 1, "step 2: the badge counts the new alert");
        assertThat(page.evaluate("() => window.__inboxBadgeProbe === 'alive'"))
            .as("step 2: without leaving the page").isEqualTo(true);

        // 3. Reading it takes the badge back down, still in place.
        inbox.markRead(item.get(CommsInboxModel.ID), owner);
        await(() -> badge() == baseline, "step 3: the read alert leaves the badge");
        assertThat(page.evaluate("() => window.__inboxBadgeProbe === 'alive'"))
            .as("step 3: still the same page").isEqualTo(true);
    }

    /** The count on the inbox entry; an entry printing none counts nothing. */
    private long badge() {
        Locator badge = page.locator(BADGE);
        return badge.count() == 0 ? 0 : Long.parseLong(badge.first().textContent().trim());
    }

    /** Waits for a condition, naming the step instead of reporting a bare Playwright timeout. */
    private void await(BooleanSupplier condition, String step) {
        try {
            page.waitForCondition(condition, new Page.WaitForConditionOptions().setTimeout(10_000));
        } catch (TimeoutError timeout) {
            throw new AssertionError(step, timeout);
        }
    }
}
