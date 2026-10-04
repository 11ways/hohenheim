package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The stored connection facts of a managed database, as the {@code hohenheim:cms/database-connection} card both the
 * admin Restore tab and the tenant Credentials tab draw.
 *
 * AIDEV-NOTE: this reads the plaintext password. Each caller is the gate: the Restore tab sits on the admin panel, the
 * Credentials tab answers only to the {@code credentials} capability ({@link ManageDatabaseCredentialsPage#visibleFor}).
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
final class DatabaseConnectionCard {

    private DatabaseConnectionCard() {
    }

    /** @return the card's variables, a missing value as empty text */
    static @NonNull Map<String, Object> facts(@NonNull Row database) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("name", text(database.get(DatabaseModel.NAME)));
        facts.put("engine", text(database.get(DatabaseModel.ENGINE)));
        facts.put("status", text(database.get(DatabaseModel.STATUS)));
        facts.put("dbName", text(database.get(DatabaseModel.DB_NAME)));
        facts.put("dbUser", text(database.get(DatabaseModel.DB_USER)));
        facts.put("dbPassword", text(database.get(DatabaseModel.DB_PASSWORD)));
        return facts;
    }

    private static @NonNull String text(@Nullable Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
