package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.zenit.cms.common.render.table.EnumBadgeState;
import be.elevenways.zenit.common.orm.datasource.Row;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The stored connection facts of a managed database, as the {@code hohenheim:cms/database-connection} card both the
 * admin Restore tab and the tenant Credentials tab draw: the engine and the database's state as badges, where it runs
 * (the Overview's own words, {@link DatabaseOverview#runsOn}), its names, and the password masked behind a reveal with
 * its copy button.
 *
 * AIDEV-NOTE: this reads the plaintext password. Each caller is the gate: the Restore tab sits on the admin panel, the
 * Credentials tab answers only to the {@code credentials} capability
 * ({@link ManageDatabaseCredentialsPage#visibleFor}).
 *
 * AIDEV-TODO: the Overview's Connection card (DatabaseOverview.connectionFacts) should draw this same card, minus the
 * password; DatabaseOverview was held by another session when the two were last reconciled.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
final class DatabaseConnectionCard {

    private DatabaseConnectionCard() {
    }

    /**
     * @param operator whether the reader is the operator, who is told where the database runs; a tenant never is (the
     *                 host is operator inventory)
     * @return the card's variables, a missing name as empty text
     */
    static @NonNull Map<String, Object> facts(@NonNull Row database, boolean operator) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("name", Objects.toString(database.get(DatabaseModel.NAME), ""));
        facts.put("engine", EnumBadgeState.ofNullable(DatabaseModel.ENGINE, database.get(DatabaseModel.ENGINE)));
        // What the database does (DatabaseVerdict, the badge its list and host page draw), never the stored token.
        facts.put("status", DatabaseVerdict.ofDatabase(database).badge());
        if (operator) {
            facts.put("runsOn", DatabaseOverview.runsOn(database));
        }
        facts.put("dbName", Objects.toString(database.get(DatabaseModel.DB_NAME), ""));
        facts.put("dbUser", Objects.toString(database.get(DatabaseModel.DB_USER), ""));
        facts.put("dbPassword", Objects.toString(database.get(DatabaseModel.DB_PASSWORD), ""));
        return facts;
    }
}
