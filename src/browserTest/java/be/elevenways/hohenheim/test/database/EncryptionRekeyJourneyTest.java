package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.server.source.GithubProviderKind;
import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.GitProviderModel;
import be.elevenways.hohenheim.model.NotificationChannelModel;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.crypto.EncryptionKeyring;
import be.elevenways.zenit.server.orm.crypto.EncryptionRekey;
import be.elevenways.zenit.server.orm.crypto.FieldEncryption;
import be.elevenways.zenit.server.orm.crypto.KeyringGuard;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Phase 2 parallel-gate clause "rotate and re-encrypt", wired to hohenheim's REAL encrypted
 * columns: a webhook URL, a TSIG secret and a git access token written under one key must all
 * end up under the next one, and the old key may only be retired once a real walk of this
 * database proves nothing still needs it.
 *
 * The mechanism is zenit core's; this proves the hohenheim consumer -- 20-odd {@code
 * .encrypted()} declarations across 14 models, all of them main-table columns -- is genuinely
 * covered rather than merely compatible.
 */
class EncryptionRekeyJourneyTest {

    private static final String HOOK_URL = "https://hooks.example.com/services/rekey-token-Q4";
    private static final String TSIG = "c2VjcmV0LXRzaWctbWF0ZXJpYWw=";
    private static final String GIT_TOKEN = "ghp_rekeyJourneyToken00000000000000000000";

    @AfterAll
    static void revertKeyring() {
        FieldEncryption.installKeyring(null);
    }

    @Test
    void everyEncryptedColumnMovesToTheNewKeyBeforeTheOldOneCanBeRetired() throws Exception {
        Path workspace = Files.createTempDirectory("hh-rekey");
        Path keyringFile = workspace.resolve("field-encryption.keys");
        EncryptionKeyring keyring = EncryptionKeyring.loadOrCreate(keyringFile);
        FieldEncryption.installKeyring(keyring);

        SqliteDatasource datasource = new SqliteDatasource(
            "jdbc:sqlite:" + workspace.resolve("hohenheim.db").toAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        Datasources.register(Datasources.DEFAULT, datasource);

        try {
            String firstKey = keyring.activeKeyId();

            // 1. Three different real secrets, on three different tables, under the first key.
            Db.run(datasource, () -> {
                KeyringGuard.runPerRegisteredModels();

                Row channel = Models.get(NotificationChannelModel.class).createEmptyRow();
                channel.set(NotificationChannelModel.NAME, "rekey hook");
                channel.set(NotificationChannelModel.KIND, NotificationChannelModel.KIND_WEBHOOK);
                channel.set(NotificationChannelModel.FORMAT, NotificationChannelModel.FORMAT_SLACK);
                channel.set(NotificationChannelModel.URL, HOOK_URL);
                Models.get(NotificationChannelModel.class).save(channel);

                Row peer = Models.get(DnsPeerModel.class).createEmptyRow();
                peer.set(DnsPeerModel.NAME, "rekey-peer");
                peer.set(DnsPeerModel.TRANSFER_HOST, "192.0.2.10");
                peer.set(DnsPeerModel.TSIG_SECRET, TSIG);
                Models.get(DnsPeerModel.class).save(peer);

                Row provider = Models.get(GitProviderModel.class).createEmptyRow();
                provider.set(GitProviderModel.NAME, "rekey-provider");
                provider.set(GitProviderModel.KIND, GithubProviderKind.ID.toString());
                provider.set(GitProviderModel.ACCESS_TOKEN, GIT_TOKEN);
                Models.get(GitProviderModel.class).save(provider);
            });

            EncryptionRekey.Survey before = EncryptionRekey.survey(datasource);
            assertThat(before.valuesUnder(firstKey))
                .as("step 1: every encrypted value in this control plane is under the first key")
                .isEqualTo(3);

            // 2. Rotation on its own leaves all three exactly as exposed as before.
            String secondKey = keyring.rotate();
            assertThat(EncryptionRekey.survey(datasource).valuesUnder(firstKey))
                .as("step 2: rotating did not re-encrypt anything already stored").isEqualTo(3);

            // 3. Retirement is refused BY NAME, and it names the hohenheim columns at stake.
            assertThatThrownBy(() -> EncryptionRekey.retire(firstKey))
                .as("step 3: the old key cannot be retired while real secrets still need it")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(firstKey)
                .hasMessageContaining("hohenheim:notification_channel.url")
                .hasMessageContaining("hohenheim:dns_peer.tsig_secret")
                .hasMessageContaining("hohenheim:git_provider.access_token");
            assertThat(keyring.containsKey(firstKey))
                .as("step 3: and the refused retirement left the key in place").isTrue();

            // 4. The pass moves everything, and the survey (a real walk, not a flag) says so.
            EncryptionRekey.Report report = EncryptionRekey.reencrypt(datasource);
            assertThat(report.activeKeyId()).as("step 4: the pass targets the new key")
                .isEqualTo(secondKey);
            assertThat(report.rowsRewritten()).as("step 4: and rewrote the three secret-bearing rows")
                .isEqualTo(3);

            EncryptionRekey.Survey after = EncryptionRekey.survey(datasource);
            assertThat(after.valuesUnder(firstKey))
                .as("step 4: nothing reads under the old key any more").isEqualTo(0);
            assertThat(after.valuesUnder(secondKey))
                .as("step 4: all three read under the new one").isEqualTo(3);

            // 5. Retire for real, then read every secret back as its exact plaintext with the
            //    old key genuinely gone from the ring -- the proof the values MOVED rather than
            //    the keyring merely having been trimmed.
            EncryptionRekey.retire(firstKey);
            assertThat(keyring.containsKey(firstKey))
                .as("step 5: the old key is gone").isFalse();
            assertThat(KeyringGuard.markerKeyId(datasource))
                .as("step 5: and the boot marker names a key the keyring still has")
                .isEqualTo(secondKey);

            Db.run(datasource, () -> {
                KeyringGuard.runPerRegisteredModels();
                assertThat((String) Models.get(NotificationChannelModel.class).find().first()
                        .get(NotificationChannelModel.URL))
                    .as("step 5: the webhook URL still decrypts").isEqualTo(HOOK_URL);
                assertThat((String) Models.get(DnsPeerModel.class).find().first()
                        .get(DnsPeerModel.TSIG_SECRET))
                    .as("step 5: the TSIG secret still decrypts").isEqualTo(TSIG);
                assertThat((String) Models.get(GitProviderModel.class).find().first()
                        .get(GitProviderModel.ACCESS_TOKEN))
                    .as("step 5: the git access token still decrypts").isEqualTo(GIT_TOKEN);
            });

            // 6. A second pass is a cheap no-op: nothing left to move.
            EncryptionRekey.Report again = EncryptionRekey.reencrypt(datasource);
            assertThat(again.rowsRewritten())
                .as("step 6: re-running the pass rewrites nothing").isEqualTo(0);
        } finally {
            datasource.close();
        }
    }
}
