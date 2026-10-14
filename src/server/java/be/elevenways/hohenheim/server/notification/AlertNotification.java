package be.elevenways.hohenheim.server.notification;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.notifications.Importance;
import be.elevenways.zenit.notifications.Notification;
import be.elevenways.zenit.notifications.DeliveryChannel;
import be.elevenways.zenit.notifications.NotificationRecipient;
import be.elevenways.zenit.notifications.NotificationTexts;
import be.elevenways.zenit.notifications.envelope.ChatEnvelope;
import be.elevenways.zenit.notifications.envelope.InboxEnvelope;
import be.elevenways.zenit.notifications.envelope.WebhookEnvelope;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One platform alert (cert expiry, backup failure, ...) rendered per channel.
 *
 * AIDEV-NOTE: subject and message are Microcopy IDENTITY, never pre-rendered English: the
 * inbox stores the identity and re-resolves it per viewer, chat resolves it in the
 * recipient's locale at send time, and the webhook body carries the default-locale reading.
 * A caller holding only free text (an exception message, an operator-authored string) wraps
 * it as {@code Microcopy.literal}, which never touches the resolver.
 *
 * AIDEV-NOTE: the inbox item's repeat key is the event and what the alert is about, never its wording: a host that
 * stops answering eight times is one item that says "8 times" (zenit-notifications folds it), and an archived item is
 * closed, so the next occurrence opens a new one.
 *
 * @author  Jelle De Loecker
 * @since   0.2.0
 */
public final class AlertNotification extends Notification {

    private final String event;
    private final String about;
    private final Microcopy subject;
    private final @Nullable Microcopy message;

    /** @param about what the alert is about ({@link Alerts#about}), the scope of its repeat key */
    public AlertNotification(@NonNull String event, @NonNull String about, @NonNull Microcopy subject,
                             @Nullable Microcopy message) {
        this.event = event;
        this.about = about;
        this.subject = subject;
        this.message = message;
    }

    @Override
    public @NonNull Identifier getKey() {
        return HohenheimIds.id(this.event);
    }

    /** A platform alert is what an operator is meant to act on, so it badges in the inbox. */
    @Override
    public @NonNull Importance getImportance() {
        return Importance.HIGH;
    }

    /** Only the channels this recipient actually routes: a chat-only endpoint must never produce webhook rows. */
    @Override
    public @NonNull List<DeliveryChannel> via(@NonNull NotificationRecipient recipient) {
        // The panel inbox is LOCAL: it needs no transport and no operator configuration,
        // which is what makes it the lane an alert can always be seen on.
        if (recipient.routeFor(DeliveryChannel.INBOX) != null) {
            return List.of(DeliveryChannel.INBOX);
        }
        if (recipient.routeFor(DeliveryChannel.CHAT) != null) {
            return List.of(DeliveryChannel.CHAT);
        }
        if (recipient.routeFor(DeliveryChannel.WEBHOOK) != null) {
            return List.of(DeliveryChannel.WEBHOOK);
        }
        return List.of();
    }

    @Override
    public @Nullable InboxEnvelope toInbox(@NonNull NotificationRecipient recipient) {
        return new InboxEnvelope()
            .setTitle(this.subject)
            .setBody(this.message)
            .setIcon("bell")
            .setRepeatKey(this.event + ":" + this.about);
    }

    @Override
    public @Nullable ChatEnvelope toChat(@NonNull NotificationRecipient recipient) {
        return new ChatEnvelope().setTitle(this.subject).setBody(this.message);
    }

    @Override
    public @Nullable WebhookEnvelope toWebhook(@NonNull NotificationRecipient recipient) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("subject", NotificationTexts.plain(this.subject));
        String message = NotificationTexts.plain(this.message);
        data.put("message", message == null ? "" : message);
        return new WebhookEnvelope().setEvent(this.event).setData(data);
    }
}
