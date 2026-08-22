package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.NotificationChannelModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.notification.NotificationEvents;
import be.elevenways.hohenheim.server.upstream.UpstreamKindHandlers;
import be.elevenways.hohenheim.server.tls.AcmeService;
import be.elevenways.zenit.comms.CommsChannel;
import be.elevenways.zenit.comms.server.Comms;
import be.elevenways.zenit.comms.server.CommsDispatcher;
import be.elevenways.zenit.comms.server.transport.TransportTypes;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;

import java.util.Map;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The expiring-soon certificate alert: fires once per expiry cycle for certs
 * inside the alert window, routes through the event subscription, and re-arms
 * itself when a renewal pushes expires_on forward.
 */
class CertExpiryAlertTest {

    private static boolean initialized = false;

    @BeforeAll
    static void initRuntime() throws Exception {
        if (initialized) return;
        initialized = true;

        UpstreamKindHandlers.boot();
        HohenheimEndpoints.init();
        TestDatabases.freshDatabase();
        HohenheimTestRuntime.ensureBooted();
    }

    @Test
    void alertsOncePerExpiryCycleAndReArmsAfterRenewal() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        receiver.start();
        try {
            // Inline delivery so the receiver's hit count is settled when the sweep returns.
            Comms.install(new CommsDispatcher(Map.of(
                CommsChannel.WEBHOOK, List.of(TransportTypes.create("webhook://default"))), 1, true));

            NotificationChannelModel channels = Models.get(NotificationChannelModel.class);
            Row channel = channels.createEmptyRow();
            channel.set(NotificationChannelModel.NAME, "expiry-watch");
            channel.set(NotificationChannelModel.KIND, NotificationChannelModel.KIND_WEBHOOK);
            channel.set(NotificationChannelModel.FORMAT, NotificationChannelModel.FORMAT_GENERIC);
            channel.set(NotificationChannelModel.URL,
                "http://127.0.0.1:" + receiver.getAddress().getPort() + "/hook");
            channel.set(NotificationChannelModel.EVENTS, List.of(NotificationEvents.CERT_EXPIRING.token()));
            channels.save(channel);

            CertificateModel certModel = Models.get(CertificateModel.class);
            Row cert = certModel.createEmptyRow();
            cert.set(CertificateModel.NICE_NAME, "expiring.example.com");
            cert.set(CertificateModel.PROVIDER, CertificateModel.PROVIDER_CUSTOM);
            cert.set(CertificateModel.STATUS, CertificateModel.STATUS_ACTIVE);
            Instant now = Instant.now();
            cert.set(CertificateModel.EXPIRES_ON, now.plus(5, ChronoUnit.DAYS));
            certModel.save(cert);

            AcmeService.checkExpiryAlerts(certModel, now);
            assertThat(hits.get()).as("first sweep alerts").isEqualTo(1);

            // The dedup stamp suppresses the alert for the rest of this expiry cycle.
            AcmeService.checkExpiryAlerts(certModel, now.plus(6, ChronoUnit.HOURS));
            assertThat(hits.get()).as("second sweep stays quiet").isEqualTo(1);

            // A renewal moves expires_on forward past the window: no alert while healthy...
            Row renewed = certModel.findById(cert.get(CertificateModel.ID));
            renewed.set(CertificateModel.EXPIRES_ON, now.plus(60, ChronoUnit.DAYS));
            certModel.save(renewed);
            AcmeService.checkExpiryAlerts(certModel, now);
            assertThat(hits.get()).isEqualTo(1);

            // ...and the stale stamp re-arms once the new expiry enters the window.
            AcmeService.checkExpiryAlerts(certModel, now.plus(50, ChronoUnit.DAYS));
            assertThat(hits.get()).as("re-armed for the next cycle").isEqualTo(2);
        } finally {
            Comms.install(null);
            receiver.stop(0);
        }
    }
}
