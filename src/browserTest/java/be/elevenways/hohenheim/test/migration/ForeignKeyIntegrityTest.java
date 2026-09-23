package be.elevenways.hohenheim.test.migration;

import be.elevenways.hohenheim.model.DatabaseEngineModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.cli.ForeignKeyOrphansCommand;
import be.elevenways.hohenheim.server.database.ControlPlaneBackups;
import be.elevenways.hohenheim.server.task.CheckForeignKeys;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.cli.OfflineCommandContext;
import be.elevenways.zenit.server.cli.OfflineCommandException;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Foreign keys are ENFORCED on the control plane (zenit opens SQLite with foreign_keys=on and
 * hohenheim keeps it), a delete that other rows reference is refused BY NAME before the raw
 * constraint fires, and the orphans a database collected while enforcement was off are
 * reported until an operator repairs them -- never deleted by anything else.
 */
class ForeignKeyIntegrityTest {

    private static final String SERVER = "fk-host";

    @BeforeAll
    static void setUp() throws Exception {
        TestDatabases.freshDatabase();
        HohenheimTestRuntime.ensureBooted();
        CheckForeignKeys.forgetTransitionStateForTest();
    }

    @AfterAll
    static void tearDown() throws Exception {
        CheckForeignKeys.forgetTransitionStateForTest();
        TestDatabases.freshDatabase();
    }

    @Test
    void foreignKeysAreEnforcedReportedAndRepairedOnlyOnTheOperatorsWord() throws Exception {
        // 1. A fresh database is clean, and the daily check says so by not failing.
        assertThat(HohenheimDatabase.foreignKeyViolations())
            .as("step 1: a migrated database violates no declared foreign key").isEmpty();
        assertThatCode(CheckForeignKeys::check)
            .as("step 1: a clean check does not fail its run").doesNotThrowAnyException();

        // 2. A host a shared database ENGINE still lives on is refused by name: the engine
        //    used to be missing from the count, so this delete reached the constraint and
        //    failed as a raw "FOREIGN KEY constraint failed".
        Row server = Models.get(ServerModel.class).createEmptyRow();
        server.set(ServerModel.NAME, SERVER);
        server.set(ServerModel.RUNTIME, ServerModel.RUNTIME_DOCKER);
        server.set(ServerModel.MODE, ServerModel.MODE_LOCAL);
        server.set(ServerModel.ADMISSION, ServerModel.ADMISSION_ADMITTED);
        server.set(ServerModel.POSTURE, ServerModel.POSTURE_SHARED_CONTAINER);
        Models.get(ServerModel.class).save(server);
        int serverId = server.get(ServerModel.ID);
        Row engine = Models.get(DatabaseEngineModel.class).createEmptyRow();
        engine.set(DatabaseEngineModel.NAME, "postgres-" + SERVER);
        engine.set(DatabaseEngineModel.ENGINE, DatabaseModel.ENGINE_POSTGRES);
        engine.set(DatabaseEngineModel.SERVER_ID, serverId);
        engine.set(DatabaseEngineModel.ROOT_USER, "root");
        engine.set(DatabaseEngineModel.ROOT_PASSWORD, "fk-root-password");
        engine.set(DatabaseEngineModel.STATUS, DatabaseModel.STATUS_ACTIVE);
        Models.get(DatabaseEngineModel.class).save(engine);
        int engineId = engine.get(DatabaseEngineModel.ID);

        Violations refused = catchThrowableOfType(
            () -> Models.get(ServerModel.class).delete(serverId), Violations.class);
        assertThat((Throwable) refused)
            .as("step 2: the delete is a named refusal, not a constraint error").isNotNull();
        assertThat(refused.all().get(0).message().key())
            .as("step 2: the refusal is server_in_use").isEqualTo("server_in_use");
        assertThat(refused.all().get(0).message().args().get("engines"))
            .as("step 2: and it counts the engine").isEqualTo(1L);
        assertThat(Models.get(ServerModel.class).findById(serverId))
            .as("step 2: the host survives the refused delete").isNotNull();

        Models.get(DatabaseEngineModel.class).delete(engineId);
        Models.get(ServerModel.class).delete(serverId);
        assertThat(Models.get(ServerModel.class).findById(serverId))
            .as("step 2: with nothing referencing it, the host goes").isNull();

        // 3. An orphan written the way production wrote them before enforcement (a
        //    connection with foreign_keys=off) is REPORTED: the boot check lists it and the
        //    daily task fails its run naming the table pair.
        SqliteDatasource legacy = new SqliteDatasource(
            "jdbc:sqlite:" + ControlPlaneBackups.databaseFile() + "?foreign_keys=off");
        try {
            legacy.rawUpdate("INSERT INTO site_domains (site_id, hostname) VALUES (?, ?)",
                987654, "orphan.example");
        } finally {
            legacy.close();
        }
        List<HohenheimDatabase.ForeignKeyViolation> violations = HohenheimDatabase.foreignKeyViolations();
        assertThat(violations)
            .as("step 3: the orphan is found")
            .singleElement()
            .satisfies(violation -> {
                assertThat(violation.table()).isEqualTo("site_domains");
                assertThat(violation.parent()).isEqualTo("sites");
            });
        assertThatThrownBy(CheckForeignKeys::check)
            .as("step 3: the check fails its run, which is what reaches the dashboard")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("site_domains -> sites");

        // 4. Nothing repaired itself; a dry run lists and changes nothing.
        List<String> out = new ArrayList<>();
        new ForeignKeyOrphansCommand().run(OfflineCommandContext.parse(new String[] {
            ForeignKeyOrphansCommand.FLAG, "site_domains", "--dry-run"}, out::add));
        assertThat(String.join("\n", out))
            .as("step 4: the dry run names what it would delete").contains("would delete site_domains");
        assertThat(HohenheimDatabase.foreignKeyViolations())
            .as("step 4: and deleted nothing").hasSize(1);

        // 5. A table with no orphan is refused by name rather than touched.
        assertThatThrownBy(() -> new ForeignKeyOrphansCommand().run(OfflineCommandContext.parse(
                new String[] {ForeignKeyOrphansCommand.FLAG, "sites"}, line -> { })))
            .as("step 5: only a table that holds orphans can be repaired")
            .isInstanceOf(OfflineCommandException.class);

        // 6. The operator names the table: exactly its orphans go, and the check is clean.
        new ForeignKeyOrphansCommand().run(OfflineCommandContext.parse(new String[] {
            ForeignKeyOrphansCommand.FLAG, "site_domains"}, line -> { }));
        assertThat(HohenheimDatabase.foreignKeyViolations())
            .as("step 6: the named repair removed the orphan").isEmpty();
        assertThatCode(CheckForeignKeys::check)
            .as("step 6: and the next run is clean again").doesNotThrowAnyException();
    }
}
