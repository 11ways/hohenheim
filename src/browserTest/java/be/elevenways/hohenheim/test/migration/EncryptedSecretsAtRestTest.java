package be.elevenways.hohenheim.test.migration;

import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.NotificationChannelModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.SpamserviceInstallationModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.model.StackServiceModel;
import be.elevenways.hohenheim.server.migration.M047_EncryptRecoverableSecrets;
import be.elevenways.hohenheim.source.GitSourceSchema;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.migration.Migration;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.crypto.EncryptionKeyring;
import be.elevenways.zenit.server.orm.crypto.FieldEncryption;
import be.elevenways.zenit.server.orm.crypto.KeyringGuard;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The at-rest contract for every recoverable secret: the column holds a {@code zenc$}
 * envelope, never the plaintext, and the model API still round-trips the real value.
 * Also proves the M047 heal converts pre-encryption plaintext and is re-runnable.
 */
class EncryptedSecretsAtRestTest {

    private static final String CERT_KEY = "-----BEGIN RSA PRIVATE KEY-----\nhh-cert-secret-A1\n-----END RSA PRIVATE KEY-----";
    private static final String DNSSEC_KEY = "hh-dnssec-private-B2";
    private static final String TSIG = "hh-tsig-secret-C3";
    private static final String PEER_KEY = "hh-peer-api-key-D4";
    private static final String DB_PASS = "hh-db-password-E5";
    private static final String HOOK_URL = "https://hooks.example.com/services/hh-hook-token-F6";
    private static final String REPORT_TOKEN = "hh-report-token-G7";
    private static final String CONTROLLER = "hh-controller-key-H8";
    private static final String ENV_VALUE = "hh-env-secret-J9";

    @BeforeAll
    static void installTempKeyring() throws Exception {
        FieldEncryption.installKeyring(EncryptionKeyring.loadOrCreate(
            Files.createTempDirectory("hh-secrets-enc").resolve("keys.dry")));
    }

    @AfterAll
    static void revertKeyring() {
        FieldEncryption.installKeyring(null);
    }

    @Test
    void everyRecoverableSecretIsCiphertextAtRestAndRoundTrips() throws Exception {
        SqliteDatasource datasource = emptyDatabase("at-rest");

        // 1. A fresh install migrates cleanly, including M047's (empty) heal and
        //    zenit's keyring marker table.
        new MigrationRunner(datasource).migrate().requireSuccess();
        assertThat(scalar(datasource, "SELECT COUNT(*) AS c FROM sqlite_master"
                + " WHERE type = 'table' AND name = 'zenit_encryption_keyring'"))
            .as("step 1: zenit's M001 marker table is created by a hohenheim migrate,"
                + " so the boot guard has something to check")
            .isEqualTo(1L);

        // 2. The keyring boot guard actually ENGAGES for this database: nothing is
        //    reported unchecked, and the check recorded the active key id.
        Db.run(datasource, () -> {
            List<String> unchecked = KeyringGuard.runPerRegisteredModels();
            assertThat(unchecked)
                .as("step 2: no datasource with encrypted fields goes unchecked")
                .isEmpty();
        });
        assertThat(scalar(datasource, "SELECT COUNT(*) AS c FROM zenit_encryption_keyring"))
            .as("step 2: the guard recorded a keyring marker").isEqualTo(1L);

        Db.run(datasource, () -> {
            // 3. Write a real value through the model API for each of the nine fields.
            Model certs = Models.get(CertificateModel.class);
            Row cert = certs.createEmptyRow();
            cert.set(CertificateModel.NICE_NAME, "at-rest cert");
            cert.set(CertificateModel.PRIVATE_KEY_PEM, CERT_KEY);
            certs.save(cert);

            Model zones = Models.get(DnsZoneModel.class);
            Row zone = zones.createEmptyRow();
            zone.set(DnsZoneModel.ORIGIN, "example.test");
            zone.set(DnsZoneModel.DNSSEC_PRIVATE_KEY, DNSSEC_KEY);
            zones.save(zone);

            Model peers = Models.get(DnsPeerModel.class);
            Row peer = peers.createEmptyRow();
            peer.set(DnsPeerModel.NAME, "at-rest peer");
            peer.set(DnsPeerModel.TSIG_SECRET, TSIG);
            peer.set(DnsPeerModel.API_KEY, PEER_KEY);
            peers.save(peer);

            Model databases = Models.get(DatabaseModel.class);
            Row database = databases.createEmptyRow();
            database.set(DatabaseModel.DB_USER, "tenant");
            database.set(DatabaseModel.DB_PASSWORD, DB_PASS);
            databases.save(database);

            Model channels = Models.get(NotificationChannelModel.class);
            Row channel = channels.createEmptyRow();
            channel.set(NotificationChannelModel.NAME, "at-rest hook");
            channel.set(NotificationChannelModel.KIND, NotificationChannelModel.KIND_WEBHOOK);
            channel.set(NotificationChannelModel.FORMAT, NotificationChannelModel.FORMAT_SLACK);
            channel.set(NotificationChannelModel.URL, HOOK_URL);
            channels.save(channel);

            Model sites = Models.get(SiteModel.class);
            Row site = sites.createEmptyRow();
            site.set(SiteModel.NAME, "At-rest site");
            site.set(SiteModel.SLUG, "at-rest-site");
            site.set(SiteModel.SITE_TYPE, "hohenheim:dead");
            site.set(SiteModel.STATUS, SiteModel.STATUS_ACTIVE);
            site.set(SiteModel.ENABLED, false);
            site.set(SiteModel.SECURITY_REPORT_TOKEN, REPORT_TOKEN);
            sites.save(site);

            // M041 seeds the spamservice singleton stub; update it in place.
            Model spamservice = Models.get(SpamserviceInstallationModel.class);
            Row installation = spamservice.find().first();
            assertThat(installation).as("step 3: the M041 singleton stub exists").isNotNull();
            installation.set(SpamserviceInstallationModel.SYSTEM_USER_ID, 1000);
            installation.set(SpamserviceInstallationModel.CONTROLLER_KEY, CONTROLLER);
            spamservice.save(installation);

            Model stacks = Models.get(StackModel.class);
            Row stack = stacks.createEmptyRow();
            stack.set(StackModel.NAME, "at-rest-stack");
            stacks.save(stack);

            Model services = Models.get(StackServiceModel.class);
            Row service = services.createEmptyRow();
            service.set(StackServiceModel.STACK_ID, stack.get(StackModel.ID));
            service.set(StackServiceModel.NAME, "web");
            service.set(StackServiceModel.IMAGE, "alpine:latest");
            service.set(StackServiceModel.ENVIRONMENT, Map.of("SECRET_TOKEN", ENV_VALUE));
            services.save(service);

            // 4. Raw columns hold envelopes, never the plaintext.
            assertCiphertext(datasource, "certificates", "private_key_pem", "hh-cert-secret-A1");
            assertCiphertext(datasource, "dns_zones", "dnssec_private_key", DNSSEC_KEY);
            assertCiphertext(datasource, "dns_peers", "tsig_secret", TSIG);
            assertCiphertext(datasource, "dns_peers", "api_key", PEER_KEY);
            assertCiphertext(datasource, "managed_databases", "db_password", DB_PASS);
            assertCiphertext(datasource, "notification_channels", "url", "hh-hook-token-F6");
            assertCiphertext(datasource, "sites", "security_report_token", REPORT_TOKEN);
            assertCiphertext(datasource, "spamservice_installations", "controller_key", CONTROLLER);
            assertCiphertext(datasource, "stack_services", "environment", ENV_VALUE);

            // 5. The model API still yields the real values (the secrets stay USABLE).
            assertThat((String) certs.findById(cert.get(CertificateModel.ID))
                .get(CertificateModel.PRIVATE_KEY_PEM))
                .as("step 5: certificate key round-trips").isEqualTo(CERT_KEY);
            assertThat((String) zones.findById(zone.get(DnsZoneModel.ID))
                .get(DnsZoneModel.DNSSEC_PRIVATE_KEY))
                .as("step 5: dnssec key round-trips").isEqualTo(DNSSEC_KEY);
            Row peerBack = peers.findById(peer.get(DnsPeerModel.ID));
            assertThat((String) peerBack.get(DnsPeerModel.TSIG_SECRET))
                .as("step 5: tsig secret round-trips").isEqualTo(TSIG);
            assertThat((String) peerBack.get(DnsPeerModel.API_KEY))
                .as("step 5: peer api key round-trips").isEqualTo(PEER_KEY);
            assertThat((String) databases.findById(database.get(DatabaseModel.ID))
                .get(DatabaseModel.DB_PASSWORD))
                .as("step 5: db password round-trips").isEqualTo(DB_PASS);
            assertThat((String) channels.findById(channel.get(NotificationChannelModel.ID))
                .get(NotificationChannelModel.URL))
                .as("step 5: webhook url round-trips").isEqualTo(HOOK_URL);
            assertThat((String) sites.findById(site.get(SiteModel.ID))
                .get(SiteModel.SECURITY_REPORT_TOKEN))
                .as("step 5: report token round-trips").isEqualTo(REPORT_TOKEN);
            assertThat((String) spamservice.find().first()
                .get(SpamserviceInstallationModel.CONTROLLER_KEY))
                .as("step 5: controller key round-trips").isEqualTo(CONTROLLER);
            Map<String, String> env = services.findById(service.get(StackServiceModel.ID))
                .get(StackServiceModel.ENVIRONMENT);
            assertThat(env)
                .as("step 5: service environment round-trips")
                .containsEntry("SECRET_TOKEN", ENV_VALUE);
        });

        // 6. The declaration contract for the two fields that were not even secret before.
        assertThat(StackServiceModel.ENVIRONMENT.isSecret())
            .as("step 6: stack service environment is secret").isTrue();
        assertThat(StackServiceModel.ENVIRONMENT.isEncrypted())
            .as("step 6: stack service environment is encrypted").isTrue();
        assertThat(GitSourceSchema.REPOSITORY_URL.isSecret())
            .as("step 6: git repository url (may embed user:TOKEN@) is secret").isTrue();
    }

    @Test
    void healConvertsPreExistingPlaintextAndIsReRunnable() throws Exception {
        SqliteDatasource datasource = emptyDatabase("heal");

        // 1. Migrate everything EXCEPT M047: the pre-flip schema a populated install has.
        List<Supplier<Migration>> withoutHeal = new ArrayList<>();
        for (Supplier<Migration> supplier : MigrationRunner.discoverMigrations("default")) {
            if (!(supplier.get() instanceof M047_EncryptRecoverableSecrets)) {
                withoutHeal.add(supplier);
            }
        }
        new MigrationRunner(datasource, withoutHeal).migrate().requireSuccess();

        // 2. Plant plaintext rows raw, exactly what a pre-encryption install holds.
        datasource.rawUpdate("INSERT INTO certificates (id, private_key_pem) VALUES (1, ?)", "PLAIN-cert");
        datasource.rawUpdate("INSERT INTO dns_zones (id, origin, dnssec_private_key) VALUES (1, 'z.test', ?)", "PLAIN-dnssec");
        datasource.rawUpdate("INSERT INTO dns_peers (id, name, tsig_secret, api_key) VALUES (1, 'p', ?, ?)", "PLAIN-tsig", "PLAIN-peer-key");
        datasource.rawUpdate("INSERT INTO managed_databases (id, db_password) VALUES (1, ?)", "PLAIN-db-pass");
        datasource.rawUpdate("INSERT INTO notification_channels (id, name, url) VALUES (1, 'hook', ?)", "https://hooks.example.com/PLAIN-hook");
        datasource.rawUpdate("INSERT INTO sites (id, name, slug, security_report_token) VALUES (1, 's', 's', ?)", "PLAIN-report");
        datasource.rawUpdate("UPDATE spamservice_installations SET controller_key = ?", "PLAIN-controller");
        datasource.rawUpdate("INSERT INTO stacks (id, name) VALUES (1, 'st')");
        datasource.rawUpdate("INSERT INTO stack_services (id, stack_id, name, environment) VALUES (1, 1, 'web', ?)",
            "{\"A\":\"PLAIN-env\"}");

        // 3. The full migrate applies ONLY M047, and its heal encrypts every column.
        //    AIDEV-NOTE: integrity is relaxed to "warn" for this one call because step 1
        //    built an out-of-order install ON PURPOSE -- every migration after M047 is
        //    already recorded while M047 is still pending, which is exactly the shipped
        //    integrity check's out-of-order finding. Under the default "fail" posture this
        //    fixture cannot migrate at all, and the heal it exists to exercise never runs.
        MigrationIntegrityTest.withIntegrityMode("warn",
            () -> new MigrationRunner(datasource).migrate().requireSuccess());
        List<String> envelopes = allEnvelopes(datasource);
        assertThat(envelopes).as("step 3: nine healed envelopes").hasSize(9);
        for (String envelope : envelopes) {
            assertThat(envelope).as("step 3: healed value is an envelope").startsWith("zenc$");
            assertThat(envelope).as("step 3: healed value hides the plaintext").doesNotContain("PLAIN-");
        }

        // 4. The healed values are readable AND correct through the (now encrypted) model API.
        Db.run(datasource, () -> {
            assertThat((String) Models.get(CertificateModel.class).findById(1)
                .get(CertificateModel.PRIVATE_KEY_PEM))
                .as("step 4: healed certificate key decrypts").isEqualTo("PLAIN-cert");
            assertThat((String) Models.get(SiteModel.class).findById(1)
                .get(SiteModel.SECURITY_REPORT_TOKEN))
                .as("step 4: healed report token decrypts").isEqualTo("PLAIN-report");
            Map<String, String> env = Models.get(StackServiceModel.class).findById(1)
                .get(StackServiceModel.ENVIRONMENT);
            assertThat(env).as("step 4: healed environment map decrypts")
                .containsEntry("A", "PLAIN-env");
        });

        // 5. Re-running the heal is a no-op: every envelope string stays IDENTICAL,
        //    proving the zenc$ prefix check really short-circuits.
        M047_EncryptRecoverableSecrets.healAll(datasource);
        assertThat(allEnvelopes(datasource))
            .as("step 5: a re-run rewrites nothing")
            .containsExactlyElementsOf(envelopes);
    }

    // -- helpers --------------------------------------------------------------

    private static void assertCiphertext(SqliteDatasource datasource, String table, String column,
                                         String plaintextProbe) {
        List<Row> rows = datasource.rawQuery(
            "SELECT " + column + " FROM " + table + " WHERE " + column + " IS NOT NULL");
        assertThat(rows).as("step 4: %s.%s holds a value", table, column).isNotEmpty();
        for (Row row : rows) {
            String stored = String.valueOf(row.get(column));
            assertThat(stored).as("step 4: %s.%s is an envelope at rest", table, column)
                .startsWith("zenc$");
            assertThat(stored).as("step 4: %s.%s never leaks the plaintext", table, column)
                .doesNotContain(plaintextProbe);
        }
    }

    /** The nine healed column values, in the stable TARGETS order. */
    private static List<String> allEnvelopes(SqliteDatasource datasource) {
        List<String> result = new ArrayList<>();
        for (M047_EncryptRecoverableSecrets.Target target : M047_EncryptRecoverableSecrets.TARGETS) {
            Field<?, ?> field = target.field();
            for (Row row : datasource.rawQuery("SELECT " + field.getName() + " FROM " + target.table()
                    + " WHERE " + field.getName() + " IS NOT NULL ORDER BY id")) {
                result.add(String.valueOf(row.get(field.getName())));
            }
        }
        return result;
    }

    private static long scalar(SqliteDatasource datasource, String sql) {
        return ((Number) datasource.rawQuery(sql).get(0).get("c")).longValue();
    }

    private static SqliteDatasource emptyDatabase(String label) throws Exception {
        File db = File.createTempFile("hohenheim-secrets-" + label, ".db");
        db.delete();
        db.deleteOnExit();
        return new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
    }
}
