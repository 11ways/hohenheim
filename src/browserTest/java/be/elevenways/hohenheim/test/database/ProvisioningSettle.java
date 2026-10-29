package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.protoblast.testsupport.time.Poll;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.time.Duration;
import java.util.Collection;

/**
 * Waits for the provisioning pool to settle a test's databases before its teardown removes their rows.
 *
 * AIDEV-NOTE: a provisioning job owns its record until it writes the final status, and the pool is shared by every
 * class in a fork, so a job can start after the class that queued it ended. A teardown sweeping the engine instance
 * or the host while the job still writes them fails on a foreign key or on server_in_use.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class ProvisioningSettle {

    private static final Duration BOUND = Duration.ofSeconds(60);

    private ProvisioningSettle() {
    }

    /** Returns once no database of {@code databaseIds} is still provisioning; a removed one counts as settled. */
    public static void await(@NonNull Collection<Integer> databaseIds) {
        Model databases = Models.get(DatabaseModel.class);
        Poll.until("the background provisioning of databases " + databaseIds + " settled", BOUND, () -> {
            for (Integer id : databaseIds) {
                Row row = databases.findById(id);
                if (row != null && DatabaseModel.STATUS_PROVISIONING.equals(row.get(DatabaseModel.STATUS))) {
                    return false;
                }
            }
            return true;
        });
    }
}
