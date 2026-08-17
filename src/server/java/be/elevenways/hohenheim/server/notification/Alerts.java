package be.elevenways.hohenheim.server.notification;

import be.elevenways.hohenheim.model.NotificationChannelModel;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.comms.AdHocRecipient;
import be.elevenways.zenit.comms.CommsChannel;
import be.elevenways.zenit.comms.CommsRecipient;
import be.elevenways.zenit.comms.server.Comms;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Platform alerting on top of zenit-comms: resolves the admin-managed
 * notification channels into recipients and hands delivery to the durable
 * outbox (retries and failure rows replace the old best-effort HTTP posts).
 *
 * @author  Jelle De Loecker
 * @since   0.2.0
 */
public final class Alerts {

    private static final Pattern DISCORD_WEBHOOK =
        Pattern.compile("/api/webhooks/([^/]+)/([^/?#]+)");

    private Alerts() {
    }

    /**
     * Queue an alert for every channel subscribed to the event.
     *
     * @return the number of channels the alert was queued for
     */
    public static int send(@NonNull String event, @NonNull String subject, @Nullable String message) {
        AlertNotification notification = new AlertNotification(event, subject, message);
        int queued = 0;

        for (Row row : Models.get(NotificationChannelModel.class).find().all()) {
            if (!subscribes(row, event)) {
                continue;
            }

            CommsRecipient recipient = recipientFor(row);

            if (recipient == null) {
                continue;
            }
            Comms.notify(notification, recipient);
            queued++;
        }

        if (queued == 0) {
            // An alert that reached nobody must not vanish silently: an operator
            // can otherwise believe alerting works while receiving nothing.
            Blast.slog("hohenheim.notification.undelivered", Map.of(
                "event", event,
                "subject", subject,
                "reason", "no_subscribed_channels"));
        }
        return queued;
    }

    /**
     * Deliver a test alert to one channel inline.
     *
     * @return true when the delivery went out
     */
    public static boolean testChannel(@NonNull Row row, @NonNull String subject, @Nullable String message) {
        CommsRecipient recipient = recipientFor(row);

        if (recipient == null) {
            return false;
        }
        return Comms.notifyNow(new AlertNotification("test", subject, message), recipient);
    }

    private static boolean subscribes(@NonNull Row channel, @NonNull String event) {
        List<String> events = channel.get(NotificationChannelModel.EVENTS);
        return events == null || events.isEmpty() || events.contains(event);
    }

    /**
     * Map a channel row onto a comms recipient: slack/discord URLs become
     * DSN-shaped CHAT routes (each names its own transport), generic URLs
     * become plain WEBHOOK routes delivered by the configured webhook chain.
     *
     * @return null (with a slog) when the row's URL cannot be mapped
     */
    public static @Nullable CommsRecipient recipientFor(@NonNull Row row) {
        String name = row.get(NotificationChannelModel.NAME);
        String format = row.get(NotificationChannelModel.FORMAT);
        String url = row.get(NotificationChannelModel.URL);

        if (url == null || url.isBlank() || format == null) {
            slogUnmappable(name, format, "missing url or format");
            return null;
        }

        String route;
        switch (format) {
            case NotificationChannelModel.FORMAT_SLACK -> {
                route = slackRoute(url);
                if (route == null) {
                    slogUnmappable(name, format, "not an https webhook URL");
                    return null;
                }
                return new AdHocRecipient().route(CommsChannel.CHAT, route).setDisplayName(name);
            }
            case NotificationChannelModel.FORMAT_DISCORD -> {
                route = discordRoute(url);
                if (route == null) {
                    slogUnmappable(name, format, "not a discord /api/webhooks/ID/TOKEN URL");
                    return null;
                }
                return new AdHocRecipient().route(CommsChannel.CHAT, route).setDisplayName(name);
            }
            default -> {
                return AdHocRecipient.webhook(url).setDisplayName(name);
            }
        }
    }

    /** {@code https://hooks.slack.com/services/T/B/X} to {@code slack://hooks.slack.com/services/T/B/X}. */
    static @Nullable String slackRoute(@NonNull String url) {
        if (url.startsWith("https://")) {
            return "slack://" + url.substring("https://".length());
        }
        return null;
    }

    /** {@code https://discord.com/api/webhooks/ID/TOKEN} to {@code discord://ID/TOKEN}. */
    static @Nullable String discordRoute(@NonNull String url) {
        Matcher matcher = DISCORD_WEBHOOK.matcher(url);

        if (!matcher.find()) {
            return null;
        }
        return "discord://" + matcher.group(1) + "/" + matcher.group(2);
    }

    private static void slogUnmappable(@Nullable String name, @Nullable String format, @NonNull String reason) {
        Blast.slog("hohenheim.notification.unmappable_channel", Map.of(
            "channel", name != null ? name : "(unnamed)",
            "format", format != null ? format : "(none)",
            "reason", reason));
    }
}
