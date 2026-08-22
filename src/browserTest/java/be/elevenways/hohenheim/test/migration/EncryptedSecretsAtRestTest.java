package be.elevenways.hohenheim.test.migration;

import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.NotificationChannelModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.SpamserviceInstallationModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.model.StackServiceModel;
import be.elevenways.hohenheim.server.instance.ApplicationKind;
import be.elevenways.hohenheim.source.GitSourceSchema;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The at-rest contract for every recoverable secret: the column holds a {@code zenc$}
 * envelope, never the plaintext, and the model API still round-trips the real value.
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
    private static final String PROVIDER_TOKEN = "hh-provider-token-K10";
    private static final String PROVIDER_APP_KEY = "-----BEGIN RSA PRIVATE KEY-----\nhh-app-key-L11\n-----END RSA PRIVATE KEY-----";

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

        // 1. A fresh install migrates cleanly, zenit's keyring marker table included.
        new MigrationRunner(datasource).migrate().requireSuccess();
        // ONE database per test class: the controller identity (and therefore every
        // daemon resource name) resolves through the CURRENT datasource, and a Db scope
        // is thread-local -- so a second, unregistered database would hand any
        // thread-hopping work a different controller's token than the records came from.
        Datasources.register(Datasources.DEFAULT, datasource);
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
            site.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
            site.set(SiteModel.STATUS, SiteModel.STATUS_ACTIVE);
            site.set(SiteModel.ENABLED, false);
            site.set(SiteModel.SECURITY_REPORT_TOKEN, REPORT_TOKEN);
            sites.save(site);

            // The singleton stub is SEEDED (SpamserviceInstallationSeeder), and a bare
            // migrate never runs the SEED boot stage -- so create it the way the seeder does.
            Model spamservice = Models.get(SpamserviceInstallationModel.class);
            Row installation = spamservice.createEmptyRow();
            installation.set(SpamserviceInstallationModel.ID,
                SpamserviceInstallationModel.SINGLETON_ID);
            installation.set(SpamserviceInstallationModel.SYSTEM_USER_ID, 1000);
            installation.set(SpamserviceInstallationModel.CONTROLLER_KEY, CONTROLLER);
            spamservice.save(installation);

            Model providers = Models.get(be.elevenways.hohenheim.model.GitProviderModel.class);
            Row provider = providers.createEmptyRow();
            provider.set(be.elevenways.hohenheim.model.GitProviderModel.NAME, "at-rest provider");
            provider.set(be.elevenways.hohenheim.model.GitProviderModel.KIND, "hohenheim:github");
            provider.set(be.elevenways.hohenheim.model.GitProviderModel.ACCESS_TOKEN, PROVIDER_TOKEN);
            provider.set(be.elevenways.hohenheim.model.GitProviderModel.APP_PRIVATE_KEY_PEM,
                PROVIDER_APP_KEY);
            providers.save(provider);

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
            assertCiphertext(datasource, "git_providers", "access_token", PROVIDER_TOKEN);
            assertCiphertext(datasource, "git_providers", "app_private_key_pem", "hh-app-key-L11");

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
            Row providerBack = providers.findById(
                provider.get(be.elevenways.hohenheim.model.GitProviderModel.ID));
            assertThat((String) providerBack
                .get(be.elevenways.hohenheim.model.GitProviderModel.ACCESS_TOKEN))
                .as("step 5: provider token round-trips").isEqualTo(PROVIDER_TOKEN);
            assertThat((String) providerBack
                .get(be.elevenways.hohenheim.model.GitProviderModel.APP_PRIVATE_KEY_PEM))
                .as("step 5: provider app key round-trips").isEqualTo(PROVIDER_APP_KEY);
        });

        // 6. The declaration contract for the two fields that were not even secret before.
        assertThat(StackServiceModel.ENVIRONMENT.isSecret())
            .as("step 6: stack service environment is secret").isTrue();
        assertThat(StackServiceModel.ENVIRONMENT.isEncrypted())
            .as("step 6: stack service environment is encrypted").isTrue();
        assertThat(ApplicationKind.SETTINGS_SCHEMA.getField(GitSourceSchema.REPOSITORY_URL)
                .isSecret())
            .as("step 6: git repository url (may embed user:TOKEN@) is secret").isTrue();
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
