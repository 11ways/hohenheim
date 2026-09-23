package be.elevenways.hohenheim.server.notification;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.comms.CommsChannel;
import be.elevenways.zenit.comms.CommsRecipient;
import be.elevenways.zenit.comms.CommsTexts;
import be.elevenways.zenit.comms.Importance;
import be.elevenways.zenit.comms.Notification;
import be.elevenways.zenit.comms.message.ChatMessage;
import be.elevenways.zenit.comms.message.InboxMessage;
import be.elevenways.zenit.comms.message.WebhookMessage;
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
 * @author  Jelle De Loecker
 * @since   0.2.0
 */
public final class AlertNotification extends Notification {

    private final String event;
    private final Microcopy subject;
    private final @Nullable Microcopy message;

    public AlertNotification(@NonNull String event, @NonNull Microcopy subject, @Nullable Microcopy message) {
        this.event = event;
        this.subject = subject;
        this.message = message;
    }

    @Override
    public @NonNull Identifier getKey() {
        return Identifier.of("hohenheim", this.event);
    }

    /** A platform alert is what an operator is meant to act on, so it badges in the inbox. */
    @Override
    public @NonNull Importance getImportance() {
        return Importance.HIGH;
    }

    /** Only the channels this recipient actually routes: a chat-only endpoint must never produce webhook rows. */
    @Override
    public @NonNull List<CommsChannel> via(@NonNull CommsRecipient recipient) {
        // The panel inbox is LOCAL: it needs no transport and no operator configuration,
        // which is what makes it the lane an alert can always be seen on.
        if (recipient.routeFor(CommsChannel.INBOX) != null) {
            return List.of(CommsChannel.INBOX);
        }
        if (recipient.routeFor(CommsChannel.CHAT) != null) {
            return List.of(CommsChannel.CHAT);
        }
        if (recipient.routeFor(CommsChannel.WEBHOOK) != null) {
            return List.of(CommsChannel.WEBHOOK);
        }
        return List.of();
    }

    @Override
    public @Nullable InboxMessage toInbox(@NonNull CommsRecipient recipient) {
        return new InboxMessage()
            .setTitle(this.subject)
            .setBody(this.message)
            .setIcon("bell");
    }

    @Override
    public @Nullable ChatMessage toChat(@NonNull CommsRecipient recipient) {
        return new ChatMessage().setTitle(this.subject).setBody(this.message);
    }

    @Override
    public @Nullable WebhookMessage toWebhook(@NonNull CommsRecipient recipient) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("subject", CommsTexts.plain(this.subject));
        String message = CommsTexts.plain(this.message);
        data.put("message", message == null ? "" : message);
        return new WebhookMessage().setEvent(this.event).setData(data);
    }
}
