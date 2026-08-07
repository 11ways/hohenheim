package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.database.DatabaseContainerKind;
import be.elevenways.hohenheim.server.database.DatabaseInstances;
import be.elevenways.hohenheim.server.instance.OwnedInstances;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;

import java.util.Map;

/**
 * Resolves a managed database's ENGINE CONTAINER handle the way production does: from
 * the owned instance, never from the record name.
 *
 * AIDEV-NOTE: this exists so no test can go back to spelling the handle itself. Before
 * the Phase 7 lowering every test built {@code ControllerScope.handle(KIND_DB, name)} by
 * hand, which is precisely the name-keyed coupling the lowering removed -- a test that
 * kept guessing the name would pass against a container the product no longer manages.
 */
public final class EngineHandles {

    private EngineHandles() {
    }

    /**
     * @return the engine container handle of the database with this name
     * @throws AssertionError when the record or its owned instance does not exist -- a
     *         missing engine must fail loudly, never resolve to a plausible-looking name
     */
    public static String of(String databaseName) {
        Row row = Models.get(DatabaseModel.class).findByName(databaseName);
        if (row == null) {
            throw new AssertionError("no managed database record named '" + databaseName + "'");
        }
        String handle = DatabaseInstances.handleOf(row.get(DatabaseModel.ID));
        if (handle == null) {
            throw new AssertionError("database '" + databaseName
                + "' owns no engine instance; it was never provisioned through the contract");
        }
        return handle;
    }

    /**
     * Plant the OWNED engine instance of a database record without touching any daemon:
     * the fixture for tests that need the ownership relation but no running container.
     *
     * Written through the system scope on purpose -- the generated-only guard refuses a
     * hand-authored {@code database_container} row, and a fixture that could bypass it
     * would be testing a write path production does not have.
     *
     * @param status one of the {@link InstanceModel} status words
     * @return the engine container handle the instance runs under
     */
    public static String plant(int databaseId, String name, String engineToken, String status) {
        try {
            return OwnedInstances.inScope(DatabaseInstances.SOURCE, DatabaseModel.MODEL_ID,
                databaseId, () -> {
                    Row row = Models.get(InstanceModel.class).createEmptyRow();
                    row.set(InstanceModel.NAME, "db-" + name);
                    row.set(InstanceModel.KIND, DatabaseContainerKind.ID.toString());
                    row.set(InstanceModel.SERVER_ID, ServerModel.localServerId());
                    row.set(InstanceModel.STATUS, status);
                    row.set(InstanceModel.SETTINGS, Map.of("engine", engineToken,
                        "ephemeral", true));
                    Models.get(InstanceModel.class).save(row);
                    return ControllerScope.handle(ControllerScope.KIND_INSTANCE,
                        row.get(InstanceModel.ID));
                });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
