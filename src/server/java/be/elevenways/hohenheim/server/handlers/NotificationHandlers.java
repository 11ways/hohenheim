package be.elevenways.hohenheim.server.handlers;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.AuditLogModel;
import be.elevenways.hohenheim.model.NotificationChannelModel;
import be.elevenways.hohenheim.server.notification.NotificationService;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.result.RenderTemplateResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Notification channel request handlers.
 */
public final class NotificationHandlers {

    private NotificationHandlers() {
    }

    public static void init() {
        NotificationService notifications = new NotificationService();

        // List
        HohenheimEndpoints.NOTIFICATIONS.setHandler(conduit -> {
            List<Map<String, Object>> items = new ArrayList<>();
            for (Row row : notifications.list()) {
                Map<String, Object> item = new HashMap<>();
                item.put("name", row.get(NotificationChannelModel.NAME));
                item.put("format", row.get(NotificationChannelModel.FORMAT));
                item.put("url", row.get(NotificationChannelModel.URL));
                items.add(item);
            }
            String test = conduit.getQueryParam("test");
            String channel = conduit.getQueryParam("channel");
            return new RenderTemplateResult(
                Identifier.of("hohenheim", "hohenheim/notifications/list"),
                Map.of("channels", items,
                    "testResult", test != null ? test : "",
                    "testChannel", channel != null ? channel : "")
            );
        });

        // Create form
        HohenheimEndpoints.NOTIFICATIONS_CREATE_FORM.setHandler(conduit ->
            new RenderTemplateResult(
                Identifier.of("hohenheim", "hohenheim/notifications/create"),
                Map.of("error", "")
            )
        );

        // Create (POST)
        HohenheimEndpoints.NOTIFICATIONS_CREATE.setHandler(conduit -> {
            Map<String, String> form = HandlerSupport.formMap(conduit);
            String name = form.getOrDefault("name", "").trim();
            String format = form.getOrDefault("format", "").trim().toLowerCase();
            String url = form.getOrDefault("url", "").trim();

            String error = validateNotificationForm(name, format, url);
            if (error != null) {
                return HandlerSupport.renderUntyped(Identifier.of("hohenheim", "hohenheim/notifications/create"),
                    HandlerSupport.errorVars(error, form));
            }
            notifications.add(name, format, url);
            HandlerSupport.audit(conduit, AuditLogModel.ACTION_CREATED,
                AuditLogModel.RESOURCE_NOTIFICATION_CHANNEL, name, name);
            return HandlerSupport.redirectUntyped("/notifications");
        });

        // Test send (POST)
        HohenheimEndpoints.NOTIFICATIONS_TEST.setHandler(conduit -> {
            String name = conduit.getParameter(HohenheimEndpoints.NOTIFICATION_NAME);
            boolean delivered = notifications.sendTo(name,
                "Hohenheim test", "If you can read this, the channel works.");
            HandlerSupport.audit(conduit, AuditLogModel.ACTION_TESTED,
                AuditLogModel.RESOURCE_NOTIFICATION_CHANNEL, name, name);
            return HandlerSupport.redirectUntyped(
                "/notifications?test=" + (delivered ? "ok" : "failed") + "&channel=" + name);
        });

        // Delete (POST)
        HohenheimEndpoints.NOTIFICATIONS_DELETE.setHandler(conduit -> {
            String name = conduit.getParameter(HohenheimEndpoints.NOTIFICATION_NAME);
            notifications.remove(name);
            HandlerSupport.audit(conduit, AuditLogModel.ACTION_DELETED,
                AuditLogModel.RESOURCE_NOTIFICATION_CHANNEL, name, name);
            return HandlerSupport.redirectUntyped("/notifications");
        });
    }

    private static String validateNotificationForm(String name, String format, String url) {
        String nameError = HandlerSupport.validateName(name);
        if (nameError != null) return nameError;
        if (!NotificationService.FORMAT_SLACK.equals(format)
            && !NotificationService.FORMAT_DISCORD.equals(format)
            && !NotificationService.FORMAT_GENERIC.equals(format)) {
            return "Format must be slack, discord, or generic";
        }
        if (url.isEmpty() || !(url.startsWith("http://") || url.startsWith("https://"))) {
            return "URL must start with http:// or https://";
        }
        return null;
    }
}
