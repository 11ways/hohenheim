package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.NotificationChannelModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.notification.Alerts;
import be.elevenways.hohenheim.server.notification.NotificationEvents;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.zenit.comms.CommsChannel;
import be.elevenways.zenit.comms.CommsRecipient;
import be.elevenways.zenit.comms.server.Comms;
import be.elevenways.zenit.comms.server.CommsDeliveryModel;
import be.elevenways.zenit.comms.server.CommsDispatcher;
import be.elevenways.zenit.comms.server.transport.TransportTypes;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Platform alerting through zenit-comms: channel-row-to-recipient mapping,
 * event subscriptions, durable delivery rows, and the generic webhook envelope.
 */
class AlertsTest {

    @BeforeAll
    static void boot() throws Exception {
        // The shared browserTest runtime registers models and runs migrations
        // (comms tables included, via migration auto-discovery).
        HohenheimEndpoints.init();
        TestDatabases.freshDatabase();
        HohenheimTestRuntime.ensureBooted();
    }

    @BeforeEach
    void cleanSlate() {
        Models.get(NotificationChannelModel.class).find().delete();
        Models.get(CommsDeliveryModel.class).find().delete();
        // Inline webhook-only dispatcher: assertions run right after send().
        Comms.install(new CommsDispatcher(Map.of(
            CommsChannel.WEBHOOK, List.of(TransportTypes.create("webhook://default"))), 1, true));
    }

    @AfterEach
    void restoreDispatcher() {
        Comms.install(null);
    }

    private static Row addChannel(String name, String format, String url, List<String> events) {
        NotificationChannelModel channels = Models.get(NotificationChannelModel.class);
        Row row = channels.createEmptyRow();
        row.set(NotificationChannelModel.NAME, name);
        row.set(NotificationChannelModel.KIND, NotificationChannelModel.KIND_WEBHOOK);
        row.set(NotificationChannelModel.FORMAT, format);
        row.set(NotificationChannelModel.URL, url);
        row.set(NotificationChannelModel.EVENTS, events);
        channels.save(row);
        return row;
    }

    @Test
    void slackAndDiscordRowsMapToDsnChatRoutes() {
        Row slack = addChannel("ops", NotificationChannelModel.FORMAT_SLACK,
            "https://hooks.slack.com/services/T0/B0/XYZ", null);
        Row discord = addChannel("disc", NotificationChannelModel.FORMAT_DISCORD,
            "https://discord.com/api/webhooks/1234/tok-en", null);
        Row generic = addChannel("gen", NotificationChannelModel.FORMAT_GENERIC,
            "https://example.com/hook", null);

        CommsRecipient slack_recipient = Alerts.recipientFor(slack);
        assertThat(slack_recipient).isNotNull();
        assertThat(slack_recipient.routeFor(CommsChannel.CHAT))
            .isEqualTo("slack://hooks.slack.com/services/T0/B0/XYZ");

        CommsRecipient discord_recipient = Alerts.recipientFor(discord);
        assertThat(discord_recipient).isNotNull();
        assertThat(discord_recipient.routeFor(CommsChannel.CHAT))
            .isEqualTo("discord://1234/tok-en");

        CommsRecipient generic_recipient = Alerts.recipientFor(generic);
        assertThat(generic_recipient).isNotNull();
        assertThat(generic_recipient.routeFor(CommsChannel.WEBHOOK))
            .isEqualTo("https://example.com/hook");
        assertThat(generic_recipient.routeFor(CommsChannel.CHAT)).isNull();
    }

    @Test
    void unmappableRowsAreSkippedNotCrashed() {
        Row bad_slack = addChannel("bad", NotificationChannelModel.FORMAT_SLACK,
            "http://insecure.example.com/hook", null);
        assertThat(Alerts.recipientFor(bad_slack)).isNull();

        Row bad_discord = addChannel("bad2", NotificationChannelModel.FORMAT_DISCORD,
            "https://discord.com/not-a-webhook", null);
        assertThat(Alerts.recipientFor(bad_discord)).isNull();

        // A send with only unmappable channels queues nothing and does not throw.
        assertThat(Alerts.send(NotificationEvents.BACKUP_FAILED, "s", "m")).isZero();
    }

    @Test
    void eventRoutingRespectsChannelSubscriptions() throws Exception {
        Receiver receiver = Receiver.start();
        try {
            addChannel("everything", NotificationChannelModel.FORMAT_GENERIC,
                receiver.url("/all"), null);
            addChannel("deploys-only", NotificationChannelModel.FORMAT_GENERIC,
                receiver.url("/deploys"), List.of(NotificationEvents.DEPLOY_FAILED.token()));

            // A backup event reaches only the receive-all channel.
            assertThat(Alerts.send(NotificationEvents.BACKUP_FAILED, "Backup", "boom")).isEqualTo(1);
            assertThat(receiver.lastPath.get()).isEqualTo("/all");

            // A deploy event reaches both.
            assertThat(Alerts.send(NotificationEvents.DEPLOY_FAILED, "Deploy", "boom")).isEqualTo(2);

            // Every queued delivery ended up SENT in the durable outbox.
            List<Row> rows = Models.get(CommsDeliveryModel.class).find().all();
            assertThat(rows).hasSize(3);
            for (Row row : rows) {
                assertThat((String) row.get(CommsDeliveryModel.STATUS))
                    .as("delivery error: " + row.get(CommsDeliveryModel.ERROR))
                    .isEqualTo("sent");
            }
        } finally {
            receiver.stop();
        }
    }

    @Test
    void genericWebhookCarriesTheAlertEnvelope() throws Exception {
        Receiver receiver = Receiver.start();
        try {
            addChannel("gen", NotificationChannelModel.FORMAT_GENERIC, receiver.url("/g"), null);

            Alerts.send(NotificationEvents.CERT_EXPIRING, "Expiring", "cert x is old");

            String body = receiver.lastBody.get();
            assertThat(body)
                .contains("\"key\":\"hohenheim:cert_expiring\"")
                .contains("\"event\":\"cert_expiring\"")
                .contains("\"subject\":\"Expiring\"")
                .contains("\"message\":\"cert x is old\"");
        } finally {
            receiver.stop();
        }
    }

    @Test
    void testChannelReportsTheInlineOutcome() throws Exception {
        Receiver receiver = Receiver.start();
        try {
            Row good = addChannel("good", NotificationChannelModel.FORMAT_GENERIC,
                receiver.url("/t"), null);
            boolean outcome = Alerts.testChannel(good, "Test", "hello");
            Row delivery = Models.get(CommsDeliveryModel.class).find().first();
            assertThat(outcome)
                .as("delivery row: " + (delivery == null ? "(none)"
                    : delivery.get(CommsDeliveryModel.STATUS) + " / " + delivery.get(CommsDeliveryModel.ERROR)))
                .isTrue();
            assertThat(receiver.lastPath.get()).isEqualTo("/t");

            receiver.stop();
            assertThat(Alerts.testChannel(good, "Test", "hello")).isFalse();
        } finally {
            receiver.stopQuietly();
        }
    }

    /** Tiny loopback HTTP server that records the last POST. */
    private static final class Receiver {
        final HttpServer server;
        final int port;
        final AtomicReference<String> lastPath = new AtomicReference<>();
        final AtomicReference<String> lastBody = new AtomicReference<>();
        private boolean stopped = false;

        private Receiver(HttpServer server, int port) {
            this.server = server;
            this.port = port;
        }

        static Receiver start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            Receiver receiver = new Receiver(server, server.getAddress().getPort());
            server.createContext("/", exchange -> {
                receiver.lastPath.set(exchange.getRequestURI().getPath());
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                exchange.getRequestBody().transferTo(buf);
                receiver.lastBody.set(buf.toString());
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            server.start();
            return receiver;
        }

        String url(String path) {
            return "http://127.0.0.1:" + this.port + path;
        }

        void stop() {
            if (!this.stopped) {
                this.stopped = true;
                this.server.stop(0);
            }
        }

        void stopQuietly() {
            stop();
        }
    }
}
