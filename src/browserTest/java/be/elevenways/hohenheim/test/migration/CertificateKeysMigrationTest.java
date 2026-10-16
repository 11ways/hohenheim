package be.elevenways.hohenheim.test.migration;

import be.elevenways.hohenheim.server.migration.M016_CertificateKeysOutOfPlainText;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.field.TextField;
import be.elevenways.zenit.common.orm.migration.FrozenModel;
import be.elevenways.zenit.common.orm.migration.MigrationResult;
import be.elevenways.zenit.common.orm.migration.MigrationRunnerResult;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.crypto.EncryptionKeyring;
import be.elevenways.zenit.server.orm.crypto.EncryptionScope;
import be.elevenways.zenit.server.orm.crypto.FieldEncryption;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static be.elevenways.hohenheim.test.migration.InstallsAt.withIntegrityMode;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * An install through M015 whose certificate texts still hold their private keys upgrades to texts of certificate
 * blocks only, each key sealed in the encrypted key column unless that column already held one.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class CertificateKeysMigrationTest {

    private static final String CERT = "-----BEGIN CERTIFICATE-----\nMIIBcertificatebody\n-----END CERTIFICATE-----";
    private static final String KEY = "-----BEGIN PRIVATE KEY-----\nMIIEkeybody\n-----END PRIVATE KEY-----";
    private static final String OTHER_KEY = "-----BEGIN EC PRIVATE KEY-----\nMHcotherkey\n-----END EC PRIVATE KEY-----";

    private static final IntegerField ID = IntegerField.builder().name("id").build();
    private static final StringField NAME = StringField.builder().name("nice_name").build();
    private static final TextField CERTIFICATE = TextField.builder("certificate_pem").nullable(true).build();
    private static final TextField SEALED = TextField.builder("private_key_pem").nullable(true).build();

    /** The key column as CertificateModel declares it, for sealing and opening its envelopes. */
    private static final TextField KEY_FIELD =
        TextField.builder("private_key_pem").nullable(true).secret().encrypted().build();
    private static final EncryptionScope SCOPE = new EncryptionScope("certificates", "private_key_pem");

    private static final FrozenModel CERTIFICATES = new FrozenModel("certificates", ID, NAME, CERTIFICATE, SEALED);

    @BeforeAll
    static void installTempKeyring() throws Exception {
        FieldEncryption.installKeyring(EncryptionKeyring.loadOrCreate(
            Files.createTempDirectory("hh-cert-keys").resolve("keys.dry")));
    }

    @AfterAll
    static void revertKeyring() {
        FieldEncryption.installKeyring(null);
    }

    @Test
    void anInstallThroughM015KeepsNoKeyInItsPlainCertificateText() throws Exception {
        File file = File.createTempFile("hohenheim-cert-keys", ".db");
        file.delete();
        file.deleteOnExit();

        // 1. An install through M015 with a bundle whose key column is empty, a bundle whose key column already holds
        //    another key, and a certificate stored as its chain only.
        InstallsAt.migrateThrough(file.toPath(), "015");
        SqliteDatasource datasource = new SqliteDatasource("jdbc:sqlite:" + file.getAbsolutePath());
        int bundled = certificate(datasource, "bundled", CERT + "\n" + KEY + "\n", null);
        String otherEnvelope = FieldEncryption.encryptIntermediate(SCOPE, KEY_FIELD, OTHER_KEY);
        int keyed = certificate(datasource, "keyed", KEY + "\n" + CERT + "\n", otherEnvelope);
        int clean = certificate(datasource, "clean", CERT + "\n", null);

        // 2. The upgrade under integrity=fail applies M016 alone.
        withIntegrityMode("fail", () -> {
            MigrationRunnerResult upgrade = new MigrationRunner(datasource,
                InstallsAt.discoveredThrough(datasource.getDatasourceIdentifier(), "016")).migrate();
            assertThat(upgrade.isSuccess()).as("step 2: the upgrade from 015 succeeds").isTrue();
            assertThat(upgrade.getResults().stream().filter(MigrationResult::isApplied)
                .map(MigrationResult::getVersion).toList()).as("step 2: it applies exactly M016")
                .containsExactly("016");
        });

        // 3. The bundle's text is its certificate only, and its key is sealed in the key column as an upload's is.
        Row first = row(datasource, bundled);
        assertThat(first.get(CERTIFICATE)).as("step 3: the plain text keeps the certificate only")
            .isEqualTo(CERT + "\n");
        String sealed = first.get(SEALED);
        assertThat(sealed).as("step 3: the key column holds an envelope, never the key")
            .startsWith(FieldEncryption.PREFIX).doesNotContain("MIIEkeybody");
        assertThat(FieldEncryption.decryptStored(SCOPE, KEY_FIELD, sealed, null)).as("step 3: it opens to the key")
            .isEqualTo(KEY + "\n");

        // 4. A row that already had a key keeps that key and only loses the plain copy.
        Row second = row(datasource, keyed);
        assertThat(second.get(CERTIFICATE)).as("step 4: the plain copy is gone").isEqualTo(CERT + "\n");
        assertThat((String) second.get(SEALED)).as("step 4: the stored key is untouched").isEqualTo(otherEnvelope);

        // 5. A certificate stored without a key is not touched.
        assertThat(row(datasource, clean).get(CERTIFICATE)).as("step 5: a clean row stays").isEqualTo(CERT + "\n");

        // 6. Running the step again changes nothing, and a rollback is refused rather than writing a key back.
        M016_CertificateKeysOutOfPlainText.moveKeys(datasource);
        assertThat((String) row(datasource, bundled).get(SEALED)).as("step 6: a second run keeps the sealed key")
            .isEqualTo(sealed);
        assertThat(row(datasource, keyed).get(CERTIFICATE)).as("step 6: and the stripped text")
            .isEqualTo(CERT + "\n");
        assertThat(new M016_CertificateKeysOutOfPlainText().getIrreversibleReason())
            .as("step 6: the migration declares itself irreversible").isNotBlank();
        datasource.close();
    }

    private static int certificate(SqliteDatasource datasource, String name, String text, String sealed) {
        List<Integer> ids = new ArrayList<>();
        Db.run(datasource, () -> {
            Row row = CERTIFICATES.createEmptyRow();
            row.set(NAME, name);
            row.set(CERTIFICATE, text);
            row.set(SEALED, sealed);
            ids.add(CERTIFICATES.save(row).get(ID));
        });
        return ids.get(0);
    }

    private static Row row(SqliteDatasource datasource, int id) {
        List<Row> found = new ArrayList<>();
        Db.run(datasource, () -> found.add(CERTIFICATES.find().where(ID.eq(id)).first()));
        return found.get(0);
    }
}
