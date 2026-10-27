package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.NotificationChannelModel;
import be.elevenways.hohenheim.server.notification.Alerts;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.zenit.notifications.server.transport.DsnOrigin;
import be.elevenways.zenit.notifications.server.transport.TransportTypes;
import be.elevenways.zenit.notifications.test.support.FakeTransport;
import be.elevenways.zenit.notifications.DeliveryChannel;
import be.elevenways.zenit.notifications.server.DeliveryModel;
import be.elevenways.zenit.notifications.server.NotificationDispatcher;
import be.elevenways.zenit.notifications.server.Notifications;
import be.elevenways.zenit.notifications.server.NotifyOutcome;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The "send test" toast must say which of the two things happened: a transport that
 * OWNS the outcome delivered the message, or a relay merely took it (the delivery row
 * says {@code accepted}) and nothing here knows whether it ever arrived.
 */
class NotificationChannelTestToastTest {

    @BeforeAll
    static void boot() throws Exception {
        HohenheimEndpoints.init();
        TestDatabases.freshDatabase();
        HohenheimTestRuntime.ensureBooted();
    }

    @BeforeEach
    void cleanSlate() {
        Models.get(NotificationChannelModel.class).find().delete();
        Models.get(DeliveryModel.class).find().delete();
    }

    @AfterEach
    void restoreDispatcher() {
        Notifications.install(null);
    }

    private static Row addChannel(String name) {
        NotificationChannelModel channels = Models.get(NotificationChannelModel.class);
        Row row = channels.createEmptyRow();
        row.set(NotificationChannelModel.NAME, name);
        row.set(NotificationChannelModel.KIND, NotificationChannelModel.KIND_WEBHOOK);
        row.set(NotificationChannelModel.FORMAT, NotificationChannelModel.FORMAT_GENERIC);
        row.set(NotificationChannelModel.URL, "https://example.com/hook");
        channels.save(row);
        return row;
    }

    private static void installWebhookTransport(String dsn) {
        FakeTransport.install();
        Notifications.install(new NotificationDispatcher(Map.of(
            DeliveryChannel.WEBHOOK, List.of(TransportTypes.create(dsn, DsnOrigin.OPERATOR))), 1, true));
    }

    @Test
    void aRelayHandoffIsNeverToastedAsADelivery() {
        Row channel = addChannel("relayed");

        // 1. A chain whose only transport RELAYS: it took the message and promised to try.
        installWebhookTransport("fake://relay");
        NotifyOutcome handoff = Alerts.testChannelOutcome(channel, "Test", "hello");
        assertThat(handoff.sent()).as("a handoff still went out").isTrue();
        assertThat(handoff.delivered()).as("a relay proves no delivery").isFalse();

        // 2. The persisted trail agrees: accepted, not sent.
        Row delivery = Models.get(DeliveryModel.class).find().first();
        assertThat(delivery).as("the inline send must have persisted a row").isNotNull();
        assertThat(delivery.get(DeliveryModel.STATUS))
            .as("a relay handoff is recorded as accepted").isEqualTo("accepted");

        // 3. So the toast names a handoff instead of claiming the test was delivered.
        assertThat(NotificationChannelParts.testSucceeded(handoff).key())
            .as("a handoff must not be toasted as a delivery").isEqualTo("test_accepted");

        // 4. A transport that owns the outcome still gets the plain delivered toast.
        Models.get(DeliveryModel.class).find().delete();
        installWebhookTransport("fake://ok");
        NotifyOutcome delivered = Alerts.testChannelOutcome(channel, "Test", "hello");
        assertThat(delivered.delivered()).as("a provider transport proves delivery").isTrue();
        assertThat(Models.get(DeliveryModel.class).find().first().get(DeliveryModel.STATUS))
            .as("a real delivery is recorded as sent").isEqualTo("sent");
        assertThat(NotificationChannelParts.testSucceeded(delivered).key())
            .as("a delivery keeps the plain success toast").isEqualTo("test_ok");
    }
}
