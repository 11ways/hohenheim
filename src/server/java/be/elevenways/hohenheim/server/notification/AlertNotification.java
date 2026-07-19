package be.elevenways.hohenheim.server.notification;

import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.comms.CommsChannels;
import be.elevenways.zenit.comms.CommsRecipient;
import be.elevenways.zenit.comms.Notification;
import be.elevenways.zenit.comms.message.ChatMessage;
import be.elevenways.zenit.comms.message.WebhookMessage;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One platform alert (cert expiry, backup failure, ...) rendered per channel.
 *
 * @author  Jelle De Loecker
 * @since   0.2.0
 */
public final class AlertNotification extends Notification {

    private final String event;
    private final String subject;
    private final @Nullable String message;

    public AlertNotification(@NonNull String event, @NonNull String subject, @Nullable String message) {
        this.event = event;
        this.subject = subject;
        this.message = message;
    }

    @Override
    public @NonNull Identifier getKey() {
        return Identifier.of("hohenheim", this.event);
    }

    /** Only the channels this recipient actually routes: a chat-only endpoint must never produce webhook rows. */
    @Override
    public @NonNull List<Identifier> via(@NonNull CommsRecipient recipient) {
        if (recipient.routeFor(CommsChannels.CHAT) != null) {
            return List.of(CommsChannels.CHAT);
        }
        if (recipient.routeFor(CommsChannels.WEBHOOK) != null) {
            return List.of(CommsChannels.WEBHOOK);
        }
        return List.of();
    }

    @Override
    public @Nullable ChatMessage toChat(@NonNull CommsRecipient recipient) {
        return new ChatMessage().setTitle(this.subject).setBody(this.message);
    }

    @Override
    public @Nullable WebhookMessage toWebhook(@NonNull CommsRecipient recipient) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("subject", this.subject);
        data.put("message", this.message == null ? "" : this.message);
        return new WebhookMessage().setEvent(this.event).setData(data);
    }
}
