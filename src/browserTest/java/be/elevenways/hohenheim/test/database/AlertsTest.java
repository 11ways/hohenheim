package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.NotificationChannelModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.HohenheimSystemPurposes;
import be.elevenways.hohenheim.server.host.HostProbe;
import be.elevenways.hohenheim.server.notification.Alerts;
import be.elevenways.hohenheim.server.notification.NotificationEvents;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.database.DatabaseBackups;
import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.instance.InstanceOperationLock;
import be.elevenways.protoblast.common.i18n.wording.ByteUnit;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.auth.model.GrantModel;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.notifications.server.transport.TransportTypes;
import be.elevenways.zenit.notifications.DeliveryChannel;
import be.elevenways.zenit.notifications.NotificationRecipient;
import be.elevenways.zenit.notifications.NotificationSettings;
import be.elevenways.zenit.notifications.server.*;
import be.elevenways.zenit.common.orm.history.ChangeModel;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.ExecutionIdentity;
import be.elevenways.zenit.server.ServerZenitRuntime;
import be.elevenways.zenit.server.setting.DrySettingsWriter;
import be.elevenways.zenit.server.setting.PrivateNetworkBoot;
import be.elevenways.zenit.test.support.OutboundFixture;
import com.sun.net.httpserver.HttpServer;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Platform alerting through zenit-notifications: channel-row-to-recipient mapping,
 * event subscriptions, durable delivery rows, and the generic webhook envelope.
 */
// Solo: private-network host declarations are process-global, sealed at boot and have no reset.
@Tag("solo")
class AlertsTest {

    @BeforeAll
    static void boot() throws Exception {
        // The test host declares its LAN webhook authority before runtime seals boot declarations.
        ExecutionIdentity.runDetachedAsSystem(HohenheimSystemPurposes.BOOT, () -> {
            new DrySettingsWriter(ServerZenitRuntime.settingsFile(PrivateNetworkBoot.FILE))
                .setPath(NotificationSettings.Webhook.ALLOW_PRIVATE_NETWORKS.configurationPath(), true).persist();
            NotificationSettings.Webhook.ALLOW_PRIVATE_NETWORKS.declareAtBoot(true);
        });
        // The shared browserTest runtime registers models and runs migrations
        // (notifications tables included, via migration auto-discovery).
        HohenheimEndpoints.init();
        TestDatabases.freshDatabase();
        HohenheimTestRuntime.ensureBooted();
    }

    @BeforeEach
    void cleanSlate() {
        Models.get(NotificationChannelModel.class).find().delete();
        Models.get(DeliveryModel.class).find().delete();
        Models.get(NotificationInboxModel.class).find().delete();
        // The inbox fanout is driven by who holds the admin permission, so every
        // method decides that for itself instead of inheriting the previous one's.
        Models.get(GrantModel.class).find().delete();
        Models.get(UserModel.class).find().delete();
        // Inline webhook-only dispatcher: assertions run right after send().
        Notifications.install(new NotificationDispatcher(Map.of(
            DeliveryChannel.WEBHOOK, List.of(TransportTypes.create("webhook://default"))), 1, true));
    }

    @AfterEach
    void restoreDispatcher() {
        Notifications.install(null);
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

        NotificationRecipient slack_recipient = Alerts.recipientFor(slack);
        assertThat(slack_recipient).isNotNull();
        assertThat(slack_recipient.routeFor(DeliveryChannel.CHAT))
            .isEqualTo("slack://hooks.slack.com/services/T0/B0/XYZ");

        NotificationRecipient discord_recipient = Alerts.recipientFor(discord);
        assertThat(discord_recipient).isNotNull();
        assertThat(discord_recipient.routeFor(DeliveryChannel.CHAT))
            .isEqualTo("discord://1234/tok-en");

        NotificationRecipient generic_recipient = Alerts.recipientFor(generic);
        assertThat(generic_recipient).isNotNull();
        assertThat(generic_recipient.routeFor(DeliveryChannel.WEBHOOK))
            .isEqualTo("https://example.com/hook");
        assertThat(generic_recipient.routeFor(DeliveryChannel.CHAT)).isNull();
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
        assertThat(Alerts.send(NotificationEvents.BACKUP_FAILED, Alerts.INSTALLATION, lit("s"), lit("m"))).isZero();
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
            assertThat(Alerts.send(NotificationEvents.BACKUP_FAILED, Alerts.INSTALLATION, lit("Backup"), lit("boom")))
                .isEqualTo(1);
            assertThat(receiver.lastPath.get()).isEqualTo("/all");

            // A deploy event reaches both.
            assertThat(Alerts.send(NotificationEvents.DEPLOY_FAILED, Alerts.INSTALLATION, lit("Deploy"), lit("boom")))
                .isEqualTo(2);

            // Every queued delivery ended up SENT in the durable outbox.
            List<Row> rows = Models.get(DeliveryModel.class).find().all();
            assertThat(rows).hasSize(3);
            for (Row row : rows) {
                assertThat((String) row.get(DeliveryModel.STATUS))
                    .as("delivery error: " + row.get(DeliveryModel.ERROR))
                    .isEqualTo("sent");
            }
        } finally {
            receiver.close();
        }
    }

    @Test
    void genericWebhookCarriesTheAlertEnvelope() throws Exception {
        Receiver receiver = Receiver.start();
        try {
            addChannel("gen", NotificationChannelModel.FORMAT_GENERIC, receiver.url("/g"), null);

            Alerts.send(NotificationEvents.CERT_EXPIRING, Alerts.INSTALLATION, lit("Expiring"), lit("cert x is old"));

            String body = receiver.lastBody.get();
            assertThat(body)
                .contains("\"key\":\"hohenheim:cert_expiring\"")
                .contains("\"event\":\"cert_expiring\"")
                .contains("\"subject\":\"Expiring\"")
                .contains("\"message\":\"cert x is old\"");
        } finally {
            receiver.close();
        }
    }

    @Test
    void testChannelReportsTheInlineOutcome() throws Exception {
        Receiver receiver = Receiver.start();
        try {
            Row good = addChannel("good", NotificationChannelModel.FORMAT_GENERIC,
                receiver.url("/t"), null);
            boolean outcome = Alerts.testChannel(good, "Test", "hello");
            Row delivery = Models.get(DeliveryModel.class).find().first();
            assertThat(outcome)
                .as("delivery row: " + (delivery == null ? "(none)"
                    : delivery.get(DeliveryModel.STATUS) + " / " + delivery.get(DeliveryModel.ERROR)))
                .isTrue();
            assertThat(receiver.lastPath.get()).isEqualTo("/t");

            receiver.stop();
            assertThat(Alerts.testChannel(good, "Test", "hello")).isFalse();
        } finally {
            receiver.stopQuietly();
        }
    }

    /**
     * An alert reaches every administrator's panel inbox with NOTHING configured.
     *
     * AIDEV-NOTE: this is the production defect. All three installations had zero
     * notification_channels rows, so every alert took the "reached nobody" branch and
     * was discarded -- silently, for months. The inbox is a LOCAL channel: no
     * transport, no DSN, no credential, so it is the one lane that cannot be off.
     */
    @Test
    void alertsAlwaysReachEveryAdministratorsInbox() {
        // 1. One enabled administrator and one disabled one, and NOT ONE channel row.
        UUID admin = seedUser("inbox-admin@hohenheim.local", true, true);
        UUID disabled = seedUser("inbox-disabled@hohenheim.local", false, true);
        UUID bystander = seedUser("inbox-bystander@hohenheim.local", true, false);

        assertThat(Alerts.administrators())
            .as("only the enabled holder of the admin permission is an alert recipient")
            .hasSize(1);
        assertThat(Alerts.administrators().get(0).routeFor(DeliveryChannel.INBOX))
            .isEqualTo(NotificationInboxOwners.userKey(admin));

        // 2. The alert is queued for that inbox, and reports it as reached.
        assertThat(Alerts.send(NotificationEvents.BACKUP_FAILED, Alerts.INSTALLATION, lit("Backup failed"),
            lit("disk full")))
            .as("an alert with no configured channel still reaches the administrator")
            .isEqualTo(1);

        // 3. The delivery row is a real, local, terminal delivery.
        Row delivery = Models.get(DeliveryModel.class).find().first();
        assertThat(delivery).isNotNull();
        assertThat((String) delivery.get(DeliveryModel.CHANNEL))
            .isEqualTo(DeliveryChannel.INBOX.getDbValue());
        assertThat((String) delivery.get(DeliveryModel.STATUS))
            .as("a local channel is delivered here, so it really is sent")
            .isEqualTo("sent");
        assertThat((String) delivery.get(DeliveryModel.RECIPIENT))
            .isEqualTo(NotificationInboxOwners.userKey(admin));

        // 4. And the item the administrator will actually read exists, badged.
        List<Row> items = Models.get(NotificationInboxModel.class).find().all();
        assertThat(items).hasSize(1);
        assertThat((String) items.get(0).get(NotificationInboxModel.RECIPIENT))
            .as("neither the disabled account (" + disabled + ") nor the bystander ("
                + bystander + ") receives one")
            .isEqualTo(NotificationInboxOwners.userKey(admin));
        assertThat((String) items.get(0).get(NotificationInboxModel.TITLE)).isEqualTo("Backup failed");
        assertThat((String) items.get(0).get(NotificationInboxModel.IMPORTANCE)).isEqualTo("high");

        // 5. The catalog lane: an alert composed of Microcopy (resolved per reader, never
        //    pre-rendered English) is queued the same way, through the one never-throwing
        //    helper every failure path uses.
        assertThat(Alerts.trySend(NotificationEvents.BACKUP_FAILED, Alerts.about(DatabaseModel.MODEL_ID, "shop"),
                HohenheimMicrocopy.ALERT.of("database_backup_failed_subject")
                    .withArg("name", "shop"),
                HohenheimMicrocopy.ALERT.of("database_backup_failed_body")
                    .withArg("name", "shop").withArg("reason", "disk full")))
            .as("step 5: a Microcopy alert reaches the administrator's inbox too")
            .isEqualTo(1);
        assertThat(Models.get(NotificationInboxModel.class).find().all())
            .as("step 5: as a second inbox item").hasSize(2);

        // 6. A host that stops answering is worded for a person, its technical detail after the words, and its
        //    repeats fold into the one item the administrator still holds: event and subject are its repeat key.
        String phoenix = Alerts.about(ServerModel.MODEL_ID, 41);
        for (int occurrence = 0; occurrence < 3; occurrence++) {
            Alerts.send(NotificationEvents.HOST_UNREACHABLE, phoenix,
                HohenheimMicrocopy.ALERT.of("host_unreachable_subject").withArg("name", "phoenix"),
                HohenheimMicrocopy.ALERT.of("host_unreachable_body")
                    .withArg("failure", HostProbe.FailureKind.UNREACHABLE.label())
                    .withArg("detail", "kex_exchange_identification: read: Connection reset by peer"));
        }
        List<Row> hostItems = Models.get(NotificationInboxModel.class).find()
            .where(NotificationInboxModel.REPEAT_KEY.eq("host_unreachable:" + phoenix)).all();
        assertThat(hostItems).as("step 6: three occurrences about one host are one item").hasSize(1);
        assertThat((Integer) hostItems.get(0).get(NotificationInboxModel.REPEATS))
            .as("step 6: which says how often it came").isEqualTo(3);
        assertThat((String) hostItems.get(0).get(NotificationInboxModel.TITLE))
            .as("step 6: the headline names the host in words, no token in brackets")
            .isEqualTo("phoenix stopped answering");
        assertThat((String) hostItems.get(0).get(NotificationInboxModel.BODY))
            .as("step 6: the raw ssh error stays available as the detail after the words")
            .endsWith(": kex_exchange_identification: read: Connection reset by peer");

        // 7. The same event about another host is its own item, and an alert changes no record of Hohenheim's: what it
        //    records is the notifications module's own bookkeeping (its inbox item and delivery rows, OPTIONAL as that
        //    module declares them), never a change on the host it is about.
        ChangeModel changes = new ChangeModel(Models.get(ServerModel.class).getResolvedDatasource());
        Row newest = changes.find().orderBy(ChangeModel.ID, SortOrder.DESC).first();
        long before = newest == null ? 0L : newest.get(ChangeModel.ID);
        Alerts.send(NotificationEvents.HOST_UNREACHABLE, Alerts.about(ServerModel.MODEL_ID, 42),
            HohenheimMicrocopy.ALERT.of("host_unreachable_subject").withArg("name", "kumulus"), null);
        assertThat(Models.get(NotificationInboxModel.class).find().all())
            .as("step 7: another host's alert stands beside it").hasSize(4);
        assertThat(changes.find().where(ChangeModel.ID.gt(before)).all().stream()
                .map(change -> (String) change.get(ChangeModel.MODEL)).toList())
            .as("step 7: sending it changed no record but the notifications module's own")
            .isSubsetOf(NotificationInboxModel.MODEL_ID.toString(), DeliveryModel.MODEL_ID.toString());
    }

    /** A nightly dump refused at its cap reaches the administrator in words, its sizes worded as sizes. */
    @Test
    void aDumpOverItsCapAlertsTheAdministratorWithItsSizesWorded() throws Exception {
        seedUser("cap-admin@hohenheim.local", true, true);
        Integer previous = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Database.MAX_DUMP_MB);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Database.MAX_DUMP_MB, 1);
        try (DumpTargetFixture target = DumpTargetFixture.install("cap-alert-target")) {
            DumpTargetFixture.databaseRow("shop", "redis");
            // 1. One database's dump is half a megabyte over the cap; the backup refuses it and alerts.
            ScriptedDumpDocker docker = new ScriptedDumpDocker(new byte[(int) (ByteUnit.MEGABYTE.factor() * 3 / 2)]);
            DatabaseService capped = new DatabaseService() {
                @Override
                public Path backupToFile(String name, Path directory, String baseName,
                                         InstanceOperationLock.@NonNull Contention contention) throws IOException {
                    Path target = directory.resolve(baseName + ".rdb");
                    new ManagedDatabase(docker).backupToFile("db-" + name, ManagedDatabase.Engine.REDIS, "user",
                        "secret", name, target);
                    return target;
                }
            };
            String failure = DatabaseBackups.backUpOrAlert(capped, "shop", InstanceOperationLock.Contention.QUEUE);
            assertThat(failure).as("step 1: the run names the refusal technically").contains("max_dump_mb");

            // 2. THE ASSERTION: the administrator reads the dump's size and the cap as sizes, never a hand-built count.
            List<Row> items = Models.get(NotificationInboxModel.class).find().all();
            assertThat(items).as("step 2: one inbox item").hasSize(1);
            assertThat((String) items.get(0).get(NotificationInboxModel.BODY))
                .as("step 2: the reason in words").isEqualTo("The scheduled backup of database shop failed: the dump is"
                    + " 1.5 MB, more than the 1 MB the Max dump size setting allows; raise it to at least 1.5 MB."
                    + " Nothing was transferred.");
        } finally {
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Database.MAX_DUMP_MB, previous);
            Models.get(DatabaseModel.class).find().where(DatabaseModel.NAME.eq("shop")).delete();
        }
    }

    private static Microcopy lit(String text) {
        return Microcopy.literal(text);
    }

    /**
     * Creates a user and, when asked, grants it the panel admin permission.
     *
     * @return the new user's id
     */
    private static UUID seedUser(@NonNull String email, boolean enabled, boolean administrator) {
        Row user = Models.get(UserModel.class).createEmptyRow();
        user.set(UserModel.EMAIL, email);
        user.set(UserModel.DISPLAY_NAME, email);
        user.set(UserModel.ENABLED, enabled);
        user.set(UserModel.CREATED_AT, Now.instant());
        user.set(UserModel.UPDATED_AT, Now.instant());
        Models.get(UserModel.class).save(user);

        UUID id = user.get(UserModel.ID);

        if (administrator) {
            Row grant = Models.get(GrantModel.class).createEmptyRow();
            grant.set(GrantModel.SUBJECT_TYPE, GrantSubjectType.USER.key());
            grant.set(GrantModel.SUBJECT_ID, id);
            grant.set(GrantModel.PERMISSION, HohenheimSources.ADMIN_ACCESS.value());
            grant.set(GrantModel.VALUE, true);
            Models.get(GrantModel.class).save(grant);
        }
        return id;
    }

    /** Tiny loopback HTTP server that records the last POST. */
    private static final class Receiver implements AutoCloseable {
        final HttpServer server;
        final int port;
        final AtomicReference<String> lastPath = new AtomicReference<>();
        final AtomicReference<String> lastBody = new AtomicReference<>();
        private boolean stopped = false;
        private OutboundFixture outbound;

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
            receiver.outbound = OutboundFixture.routeResolved("alerts-lan.example.test",
                InetAddress.getByAddress(new byte[] {10, 0, 0, 7}), receiver.port);
            return receiver;
        }

        String url(String path) {
            return "http://" + this.outbound.host() + ":" + this.port + path;
        }

        void stop() {
            if (!this.stopped) {
                this.stopped = true;
                this.server.stop(0);
            }
        }

        void stopQuietly() {
            close();
        }

        @Override
        public void close() {
            stop();
            this.outbound.close();
        }
    }
}
