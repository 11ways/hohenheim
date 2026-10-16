package be.elevenways.hohenheim.server.migration;

import be.elevenways.hohenheim.migration.HohenheimMigration;
import be.elevenways.hohenheim.server.tls.CertificatePem;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.TextField;
import be.elevenways.zenit.common.orm.migration.FrozenModel;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.server.orm.crypto.EncryptionScope;
import be.elevenways.zenit.server.orm.crypto.FieldEncryption;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

/**
 * Takes the private key out of every stored certificate text that still holds one, keeping that text's certificate
 * blocks only and moving the key into the encrypted key column when that column is empty.
 *
 * AIDEV-NOTE: before 8ef477ac an upload stored a key pasted under its certificate in the PLAIN certificate column. The
 * key is sealed exactly as an upload's key is (core's envelope bound to certificates.private_key_pem, the frozen
 * field below as CertificateModel declares it), written to the raw column so the ORM seals nothing twice. A row whose
 * key column already holds a key only loses its plain copy. Running it again finds no key block and changes nothing.
 * Irreversible: putting a key back into plain text is the leak this removes. Only counts are logged, never a key.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public class M016_CertificateKeysOutOfPlainText extends HohenheimMigration {

    static final String CERTIFICATES = "certificates";
    static final String CERTIFICATE_PEM = "certificate_pem";
    static final String PRIVATE_KEY_PEM = "private_key_pem";

    private static final String EVENT = "hohenheim.migration.certificate_keys_out_of_plain_text";

    private static final int BATCH_SIZE = 200;

    public M016_CertificateKeysOutOfPlainText() {
        super("016", "Certificate keys out of the plain certificate text");
        irreversible("a private key is never written back into the plain certificate column");
    }

    @Override
    public void up(@NonNull MigrationBuilder schema) {
        schema.resumableData("move private keys out of the plain certificate text", "1",
            M016_CertificateKeysOutOfPlainText::moveKeys);
    }

    @Override
    public void down(@NonNull MigrationBuilder schema) {
        // Irreversible (declared in the constructor): the runner refuses a rollback before this is reached.
    }

    /** Strips every key block from the plain certificate texts, sealing the key into an empty key column. */
    public static void moveKeys(@NonNull Datasource datasource) {
        IntegerField id = IntegerField.builder("id").build();
        TextField certificate = TextField.builder(CERTIFICATE_PEM).nullable(true).build();
        TextField sealed = TextField.builder(PRIVATE_KEY_PEM).nullable(true).build();
        TextField key = TextField.builder(PRIVATE_KEY_PEM).nullable(true).secret().encrypted().build();
        EncryptionScope scope = new EncryptionScope(CERTIFICATES, PRIVATE_KEY_PEM);
        int[] counts = new int[2];
        Db.run(datasource, () -> {
            FrozenModel certificates = new FrozenModel(CERTIFICATES, id, certificate, sealed);
            Integer cursor = null;
            while (true) {
                var query = certificates.find().where(certificate.isNotNull());
                if (cursor != null) {
                    query.and(id.gt(cursor));
                }
                List<Row> batch = query.orderBy(id, SortOrder.ASC).limit(BATCH_SIZE).all();
                if (batch.isEmpty()) {
                    break;
                }
                for (Row row : batch) {
                    String text = row.get(certificate);
                    String keyText = CertificatePem.keyText(text);
                    if (keyText.isEmpty()) {
                        continue;
                    }
                    String stored = row.get(sealed);
                    boolean keyless = stored == null || stored.isBlank();
                    var update = certificates.find().where(id.eq(row.get(id)))
                        .assign(certificate, CertificatePem.chainText(text));
                    if (keyless) {
                        update.assign(sealed, FieldEncryption.encryptIntermediate(scope, key, keyText));
                    }
                    update.updateAll();
                    counts[keyless ? 0 : 1]++;
                }
                cursor = batch.getLast().get(id);
            }
        });
        Blast.log(EVENT, "moved", counts[0], "dropped", counts[1]);
    }
}
