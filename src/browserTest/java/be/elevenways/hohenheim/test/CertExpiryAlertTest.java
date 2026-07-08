package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.notification.NotificationEvents;
import be.elevenways.hohenheim.server.notification.NotificationService;
import be.elevenways.hohenheim.server.sitetype.SiteTypes;
import be.elevenways.hohenheim.server.tls.AcmeService;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
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

        File db = File.createTempFile("hohenheim-expiry-test", ".db");
        db.delete();
        db.deleteOnExit();
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.PATH, db.getAbsolutePath());

        SiteTypes.register();
        HohenheimEndpoints.init();
        HohenheimDatabase.init();
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
            new NotificationService().add("expiry-watch", NotificationService.FORMAT_GENERIC,
                "http://127.0.0.1:" + receiver.getAddress().getPort() + "/hook",
                List.of(NotificationEvents.CERT_EXPIRING));

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
            receiver.stop(0);
        }
    }
}
