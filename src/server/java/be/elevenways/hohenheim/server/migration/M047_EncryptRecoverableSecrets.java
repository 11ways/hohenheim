package be.elevenways.hohenheim.server.migration;

import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.NotificationChannelModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.SpamserviceInstallationModel;
import be.elevenways.hohenheim.model.StackServiceModel;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.hohenheim.migration.HohenheimMigration;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;
import be.elevenways.zenit.server.orm.crypto.EncryptionScope;
import be.elevenways.zenit.server.orm.crypto.FieldEncryption;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

/**
 * Folds the pre-encryption plaintext of every recoverable-secret column into its
 * {@code zenc$} envelope, in the same release that declared those fields {@code .encrypted()}.
 *
 * @author Jelle De Loecker
 * @since  0.6.0
 */
public class M047_EncryptRecoverableSecrets extends HohenheimMigration {

    /** One plaintext column to fold into the encrypted-envelope path. */
    public record Target(@NonNull String table, @NonNull Field<?, ?> field, boolean jsonColumn) {}

    /** Kept in step with the {@code .encrypted()} declarations this migration ships beside. */
    public static final List<Target> TARGETS = List.of(
        new Target("certificates", CertificateModel.PRIVATE_KEY_PEM, false),
        new Target("dns_zones", DnsZoneModel.DNSSEC_PRIVATE_KEY, false),
        new Target("dns_peers", DnsPeerModel.TSIG_SECRET, false),
        new Target("dns_peers", DnsPeerModel.API_KEY, false),
        new Target("managed_databases", DatabaseModel.DB_PASSWORD, false),
        new Target("notification_channels", NotificationChannelModel.URL, false),
        new Target("sites", SiteModel.SECURITY_REPORT_TOKEN, false),
        new Target("spamservice_installations", SpamserviceInstallationModel.CONTROLLER_KEY, false),
        new Target("stack_services", StackServiceModel.ENVIRONMENT, true));

    public M047_EncryptRecoverableSecrets() {
        // AIDEV-NOTE: 100047, not 000047: zenit ships 2026_08_02_100000 (task declared_by), and
        // an install that applied it before upgrading to this build would otherwise trip the
        // out-of-order integrity finding. The heal must sort after every migration shipped today.
        super("2026_08_02_100047", "Encrypt pre-existing recoverable secrets at rest");
    }

    @Override
    public void up(@NonNull MigrationBuilder builder) {
        builder.data("Fold plaintext recoverable-secret columns into zenc$ envelopes", "1",
            M047_EncryptRecoverableSecrets::healAll);
    }

    /**
     * Encrypt every plaintext value in the target columns; rows already carrying a
     * {@code zenc$} envelope are left untouched, so re-running is safe.
     */
    // AIDEV-NOTE: raw SQL on purpose. The model constants now carry .encrypted(), so a read
    // through the typed accessor THROWS (from inside row hydration) on exactly the plaintext
    // rows this step exists to fix -- the heal MUST read raw. That abandons the eight-backend
    // portability builder.data() otherwise gives; acceptable only because hohenheim is
    // SQLite-only (SqliteOnlyDatabaseGuardTest pins that).
    public static void healAll(@NonNull Datasource datasource) {
        SqlDatasource sql = datasource.unwrap(SqlDatasource.class);
        for (Target target : TARGETS) {
            heal(sql, target);
        }
    }

    private static void heal(@NonNull SqlDatasource sql, @NonNull Target target) {
        String column = target.field().getName();
        List<Row> rows = sql.rawQuery(
            "SELECT id, " + column + " FROM " + target.table() + " WHERE " + column + " IS NOT NULL");
        for (Row row : rows) {
            String raw = String.valueOf(row.get(column));
            if (raw.startsWith(FieldEncryption.PREFIX)) {
                continue;
            }
            // JSON columns hold the DRY/JSON text of the intermediate; the envelope encrypts
            // the intermediate itself, so parse first (mirrors SqlDatasource.deserializeJson).
            Object intermediate = target.jsonColumn() ? Zenit.DRY.parse(raw) : raw;
            String envelope = FieldEncryption.encryptIntermediate(
                new EncryptionScope(target.table(), column), target.field(), intermediate);
            sql.rawUpdate(
                "UPDATE " + target.table() + " SET " + column + " = ? WHERE id = ?",
                envelope, row.get("id"));
        }
    }

    @Override
    public void down(@NonNull MigrationBuilder builder) {
        // Deliberately keeps the ciphertext: this build's field declarations still carry
        // .encrypted(), so writing plaintext back would make every read throw.
    }
}
