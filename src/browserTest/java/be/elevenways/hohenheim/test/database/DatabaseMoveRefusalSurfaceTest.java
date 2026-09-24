package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.common.flash.FlashLevel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The panel's "move onto the shared engine" row action answers a refused CLAIM as a flash
 * refusal on the page, never a 500: the claim is taken synchronously inside the action, so its
 * Violations reach the action lane, which turns a handler's refusal into an error toast.
 *
 * The record is offered the action (it passes {@code DatabaseService.moveRefusal}) yet its claim
 * refuses, because its password is no logical identifier -- the claim's own last-line check, and
 * the one refusal reachable without racing a second operation. No daemon is contacted: the
 * refusal happens before one would be.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class DatabaseMoveRefusalSurfaceTest extends HohenheimTestBase {

    private static final String NAME = "move-refusal-surface";

    @AfterEach
    void removeRecord() {
        Models.get(DatabaseModel.class).find().where(DatabaseModel.NAME.eq(NAME)).delete();
    }

    @Test
    void aRefusedMoveClaimIsAFlashRefusalNotAServerError() throws Exception {
        // 1. An active, dedicated postgres record the panel OFFERS the move to.
        Row record = new DatabaseService().insertRecord(NAME, ManagedDatabase.Engine.POSTGRES, null,
            "app", "not-logical!", "app", false, ServerService.LOCAL_HOST_NAME, ResourceLimits.none(),
            DatabaseModel.STATUS_ACTIVE, DatabaseModel.PLACEMENT_DEDICATED, null);
        assertThat(DatabaseService.moveRefusal(record))
            .as("step 1: the move is offered for this record").isNull();
        popFlash();

        // 2. Driving the action answers the page lane (a redirect or a render), never a 500.
        var moved = adminPostForm("/admin/databases/" + record.get(DatabaseModel.ID)
            + "/action/move_database_shared", confirmed(""));
        assertThat(moved.statusCode())
            .as("step 2: the refused claim is not a server error: " + moved.body())
            .isIn(200, 302, 303);

        // 3. The refusal is an ERROR flash naming the claim's own reason.
        var flash = popFlash();
        assertThat(flash).as("step 3: a flash was stashed").isNotNull();
        assertThat(flash.level()).as("step 3: it is an error").isEqualTo(FlashLevel.ERROR);
        assertThat(flash.message().key())
            .as("step 3: naming why the claim refused").isEqualTo("database_logical_identifier");

        // 4. And the record was left exactly as it was: still active, still dedicated.
        Row after = Models.get(DatabaseModel.class).findByName(NAME);
        assertThat((String) after.get(DatabaseModel.STATUS))
            .as("step 4: the refused claim changed no status").isEqualTo(DatabaseModel.STATUS_ACTIVE);
        assertThat((String) after.get(DatabaseModel.PLACEMENT))
            .as("step 4: and moved nothing").isEqualTo(DatabaseModel.PLACEMENT_DEDICATED);
    }
}
