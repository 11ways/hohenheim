package be.elevenways.hohenheim.server.notification;

import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.model.NotificationChannelModel;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.auth.server.PermissionHolders;
import be.elevenways.zenit.comms.AdHocRecipient;
import be.elevenways.zenit.comms.CommsChannel;
import be.elevenways.zenit.comms.CommsRecipient;
import be.elevenways.zenit.comms.server.Comms;
import be.elevenways.zenit.comms.server.CommsInboxOwners;
import be.elevenways.zenit.comms.server.NotifyOutcome;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
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
     * Queue an alert for every administrator's panel inbox and every channel
     * subscribed to the event.
     *
     * AIDEV-NOTE: the inbox fanout is UNCONDITIONAL and needs no configured row. Every
     * production installation had zero notification_channels, so every alert took the
     * "reached nobody" branch and was discarded -- an alerting system that is silent
     * until an operator configures an external endpoint is an alerting system nobody
     * knows is off. The inbox is a LOCAL channel: no transport, no DSN, no credential.
     *
     * @return the number of deliveries the alert was queued for, inbox included
     */
    public static int send(@NonNull NotificationEvents event, @NonNull String subject,
                           @Nullable String message) {
        AlertNotification notification = new AlertNotification(event.token(), subject, message);
        int queued = 0;

        for (CommsRecipient administrator : administrators()) {
            Comms.notify(notification, administrator);
            queued++;
        }

        for (Row row : Models.get(NotificationChannelModel.class).find().all()) {
            if (!subscribes(row, event.token())) {
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
                "event", event.token(),
                "subject", subject,
                "reason", "no_recipients"));
        }
        return queued;
    }

    /**
     * The panel inbox of every enabled administrator, as comms recipients.
     *
     * A revoked or disabled account drops out on the next alert because the holder list
     * is read fresh; a boot without the auth tables degrades to no inbox recipients
     * rather than dropping the alert on the external channels too.
     *
     * @return one INBOX-routed recipient per administrator, never null
     */
    public static @NonNull List<CommsRecipient> administrators() {
        List<CommsRecipient> recipients = new ArrayList<>();

        try {
            for (Integer id : PermissionHolders.userIdsHolding(HohenheimSources.ADMIN_ACCESS)) {
                recipients.add(new AdHocRecipient()
                    .route(CommsChannel.INBOX, CommsInboxOwners.userKey(id)));
            }
        } catch (Exception unavailable) {
            Blast.slog("hohenheim.notification.inbox_unavailable", Map.of(
                "reason", String.valueOf(unavailable.getMessage())));
            return List.of();
        }
        return recipients;
    }

    /**
     * Deliver a test alert to one channel inline.
     *
     * @return true when the delivery went out
     */
    public static boolean testChannel(@NonNull Row row, @NonNull String subject, @Nullable String message) {
        return testChannelOutcome(row, subject, message).sent();
    }

    /**
     * The reason-carrying form of {@link #testChannel}, so the CMS test button
     * can name the failure instead of only reporting one.
     */
    public static @NonNull NotifyOutcome testChannelOutcome(@NonNull Row row, @NonNull String subject,
                                                            @Nullable String message) {
        CommsRecipient recipient = recipientFor(row);

        if (recipient == null) {
            return NotifyOutcome.failed("Channel row has no usable url or format");
        }
        return Comms.notifyNowWithReason(new AlertNotification("test", subject, message), recipient);
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
            case NotificationChannelModel.FORMAT_GENERIC -> {
                return AdHocRecipient.webhook(url).setDisplayName(name);
            }
            // AIDEV-NOTE: this arm used to be `default`, so ANY format outside the
            // vocabulary -- a typo, a hand-edited row, a format added to the model without
            // a branch here -- was silently POSTed as a generic JSON body to a receiver
            // expecting something else, and the operator saw a queued alert either way.
            // Fail closed and say which format was refused.
            default -> {
                slogUnmappable(name, format, "unknown channel format");
                return null;
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
