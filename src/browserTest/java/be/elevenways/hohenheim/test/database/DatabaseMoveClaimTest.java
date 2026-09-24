package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * A move onto a shared engine is CLAIMED atomically: the eligibility check and the
 * provisioning stamp used to be two steps, so a double-submitted action started two moves of
 * one record. The claim is now a conditional update on "still active and dedicated", taken
 * synchronously, so the second submit is refused by name before any daemon work. No daemon
 * is contacted here: the refusal happens before one would be.
 */
class DatabaseMoveClaimTest {

    private static final String NAME = "move-claim";

    @BeforeAll
    static void setUp() throws Exception {
        TestDatabases.freshDatabase();
        HohenheimTestRuntime.ensureBooted();
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestDatabases.freshDatabase();
    }

    @Test
    void aMoveThatAnotherOperationHoldsIsRefusedAndLeavesTheRecordAlone() {
        DatabaseService service = new DatabaseService();
        service.insertRecord(NAME, ManagedDatabase.Engine.POSTGRES, null, "app", "pw", "app", false,
            ServerService.LOCAL_HOST_NAME, ResourceLimits.none(), DatabaseModel.STATUS_ACTIVE,
            DatabaseModel.PLACEMENT_DEDICATED, null);

        // 1. Another move (or any operation) holds the record: it reads provisioning.
        Row held = Models.get(DatabaseModel.class).findByName(NAME);
        held.set(DatabaseModel.STATUS, DatabaseModel.STATUS_PROVISIONING);
        Models.get(DatabaseModel.class).save(held);

        // 2. The panel/API lane refuses SYNCHRONOUSLY, naming why, and queues nothing.
        Violations refused = catchThrowableOfType(
            () -> service.moveToSharedEngineInBackground(NAME), Violations.class);
        assertThat((Throwable) refused).as("step 2: the second submit is refused").isNotNull();
        assertThat(refused.all().get(0).message().key())
            .as("step 2: because the record is not active").isEqualTo("database_not_active");

        // 3. The blocking lane refuses the same way, and neither touched the record.
        assertThatThrownBy(() -> service.moveToSharedEngine(NAME))
            .as("step 3: the blocking lane refuses too")
            .isInstanceOf(IOException.class)
            .hasMessageContaining("database_not_active");
        Row after = Models.get(DatabaseModel.class).findByName(NAME);
        assertThat((String) after.get(DatabaseModel.STATUS))
            .as("step 3: the holder's status is untouched").isEqualTo(DatabaseModel.STATUS_PROVISIONING);
        assertThat((String) after.get(DatabaseModel.PLACEMENT))
            .as("step 3: and it is still dedicated").isEqualTo(DatabaseModel.PLACEMENT_DEDICATED);

        Models.get(DatabaseModel.class).find().where(DatabaseModel.NAME.eq(NAME)).delete();
    }
}
