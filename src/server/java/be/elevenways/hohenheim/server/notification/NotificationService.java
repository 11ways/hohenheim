package be.elevenways.hohenheim.server.notification;

import be.elevenways.hohenheim.model.NotificationChannelModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.util.Json;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Row;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Delivers a notification to every configured channel (webhook POST). Slack and Discord both
 * accept incoming-webhook URLs but want different body shapes; {@code generic} sends a structured
 * JSON envelope for self-hosted receivers. Delivery is best-effort -- failures are logged, not
 * raised, so one bad channel can't break the trigger.
 *
 * @author  Jelle De Loecker
 * @since   0.1.0
 */
public class NotificationService {

    public static final String KIND_WEBHOOK = "webhook";
    public static final String FORMAT_SLACK = "slack";
    public static final String FORMAT_DISCORD = "discord";
    public static final String FORMAT_GENERIC = "generic";

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10)).build();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private final NotificationChannelModel model;

    public NotificationService() {
        this(HohenheimDatabase.datasource());
    }

    public NotificationService(Datasource datasource) {
        this.model = new NotificationChannelModel(datasource);
    }

    /** All persisted channels (admin list). */
    public List<Row> list() {
        return model.find().all();
    }

    /** Register (or update) a channel. */
    public void add(String name, String format, String url) {
        Row row = model.findByName(name);
        if (row == null) {
            row = model.createEmptyRow();
            row.set(NotificationChannelModel.NAME, name);
        }
        row.set(NotificationChannelModel.KIND, KIND_WEBHOOK);
        row.set(NotificationChannelModel.FORMAT, format);
        row.set(NotificationChannelModel.URL, url);
        model.save(row);
    }

    public void remove(String name) {
        model.find().where(NotificationChannelModel.NAME.eq(name)).delete();
    }

    /** Send to every channel best-effort; returns the number of channels successfully delivered to. */
    public int send(String subject, String message) {
        int delivered = 0;
        for (Row row : model.find().all()) {
            if (sendOne(row, subject, message)) {
                delivered++;
            }
        }
        return delivered;
    }

    /** Send to a single channel by name; returns true if delivered (HTTP 2xx). */
    public boolean sendTo(String name, String subject, String message) {
        Row row = model.findByName(name);
        return row != null && sendOne(row, subject, message);
    }

    private static boolean sendOne(Row row, String subject, String message) {
        String name = row.get(NotificationChannelModel.NAME);
        String format = row.get(NotificationChannelModel.FORMAT);
        String url = row.get(NotificationChannelModel.URL);
        String body = buildBody(format, subject, message);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return true;
            }
            Blast.log("NOTIFY: channel", name, "returned HTTP", status, "-", response.body().trim());
            return false;
        } catch (Exception e) {
            Blast.log("NOTIFY: channel", name, "failed -", e.getMessage());
            return false;
        }
    }

    static String buildBody(String format, String subject, String message) {
        String text = subject + (message == null || message.isBlank() ? "" : "\n\n" + message);
        return switch (format) {
            case FORMAT_SLACK -> Json.stringify(Map.of("text", text));
            case FORMAT_DISCORD -> Json.stringify(Map.of("content", text));
            default -> {
                Map<String, Object> envelope = new LinkedHashMap<>();
                envelope.put("subject", subject);
                envelope.put("message", message == null ? "" : message);
                yield Json.stringify(envelope);
            }
        };
    }
}
