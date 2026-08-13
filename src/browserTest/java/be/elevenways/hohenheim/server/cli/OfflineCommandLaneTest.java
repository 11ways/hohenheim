package be.elevenways.hohenheim.server.cli;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.HohenheimSettingsFiles;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.PasswordService;
import be.elevenways.zenit.auth.server.SetPasswordOfflineCommand;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.server.cli.OfflineCommandException;
import be.elevenways.zenit.server.cli.OfflineCommands;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The break-glass lane as an OPERATOR reaches it: through hohenheim's own production
 * dispatch, not through the framework registry directly.
 *
 * AIDEV-NOTE: this class exists because the framework's coverage of {@code OfflineCommands}
 * was ITSELF the only consumer. zenit's OfflineCommandsTest and zenit-auth's
 * PasswordAdministrationTest both call {@code OfflineCommands.runIfRequested} directly, so
 * both stayed green for months while NO application dispatched it -- {@code --set-password}
 * was unreachable in every deployed jar and {@code --offline-help} printed nothing anywhere.
 * A test that calls the registry proves the registry; only a test that goes through
 * {@link OfflineBoot} proves that this host has the lane at all. Never "simplify" this into
 * a direct OfflineCommands call.
 */
class OfflineCommandLaneTest {

    private static final String EMAIL = "locked-out-admin@hohenheim.local";
    private static final String FORGOTTEN = "the forgotten password";
    private static final String CHOSEN = "chosen offline password";

    private static String previousSettingsProperty;
    private static Path settingsFile;
    private static Datasource fixtureDatasource;

    @BeforeAll
    static void setUp() throws Exception {
        TestDatabases.freshDatabase();
        HohenheimTestRuntime.ensureBooted();
        // The pool zenit-auth's models captured; the lane opens and closes its OWN, so this
        // one survives the whole class and must be released by hand at the end.
        fixtureDatasource = HohenheimDatabase.datasource();

        // OfflineBoot loads the real settings chain, which is the point -- but it must not
        // read the developer's own settings/hohenheim.dry. An empty file keeps the load
        // REAL while leaving the programmatic database.path this fixture installed intact
        // (loadFrom applies a source's keys; it never resets the ones the source omits).
        settingsFile = Files.createTempFile("hohenheim-offline-settings", ".dry");
        Files.writeString(settingsFile, "{}");
        previousSettingsProperty = System.getProperty("hohenheim.settings");
        System.setProperty("hohenheim.settings", settingsFile.toString());
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (previousSettingsProperty == null) {
            System.clearProperty("hohenheim.settings");
        } else {
            System.setProperty("hohenheim.settings", previousSettingsProperty);
        }
        if (settingsFile != null) {
            Files.deleteIfExists(settingsFile);
        }
        // The lane CLOSES the datasource it opened, which is correct for a process about to
        // exit and fatal for the next class in a shared JVM. Hand the fork a live one back.
        TestDatabases.freshDatabase();
        if (fixtureDatasource != null) {
            fixtureDatasource.close();
        }
    }

    @Test
    void theHostDispatchesEveryOfflineCommandAndRecoversALockedOutAdministrator()
            throws IOException {

        // 1. A normal boot pays for none of it: no discovery scan, no settings reload, no
        //    pool. The lane only engages when the invocation names an option.
        Datasource beforeAnything = Datasources.getDefault();
        assertThat(OfflineBoot.runIfRequested(new String[0], line -> { }))
            .as("step 1: an argument-less boot must not claim the run")
            .isFalse();
        assertThat(OfflineBoot.runIfRequested(new String[] {"serve"}, line -> { }))
            .as("step 1: a non-option argument must not claim the run")
            .isFalse();
        assertThat(Datasources.getDefault())
            .as("step 1: a normal boot must not have opened a second datasource")
            .isSameAs(beforeAnything);

        // 2. --offline-help is how an operator FINDS the lane, and it must list hohenheim's
        //    own migrated commands next to the framework's recovery command. This is the
        //    assertion that was impossible before ServerMain dispatched anything at all.
        List<String> help = new ArrayList<>();
        assertThat(OfflineBoot.runIfRequested(new String[] {OfflineCommands.HELP_FLAG}, help::add))
            .as("step 2: --offline-help must claim the run")
            .isTrue();
        assertThat(String.join("\n", help))
            .as("step 2: every offline command must be listed")
            .contains(SetPasswordOfflineCommand.FLAG)
            .contains(BackupControlPlaneCommand.FLAG)
            .contains(RestoreControlPlaneCommand.FLAG)
            .contains(ListControlPlaneBackupsCommand.FLAG)
            .contains(EncryptionKeyLane.ROTATE)
            .contains(EncryptionKeyLane.REENCRYPT)
            .contains(EncryptionKeyLane.SURVEY)
            .contains(EncryptionKeyLane.RETIRE)
            .contains(HistorySecretSurveyCommand.FLAG)
            .contains(PurgeHistorySecretsCommand.FLAG);

        // 3. THE recovery path this whole lane exists for: an installation whose only
        //    administrator password is lost, with no HTTP server and no mail lane.
        int userId = createUser();
        PasswordService.setPassword(userId, FORGOTTEN);
        assertThat(PasswordService.verifyPassword(userId, FORGOTTEN))
            .as("step 3: the fixture password must be installed before recovery")
            .isTrue();

        assertThat(OfflineBoot.runIfRequested(new String[] {
            SetPasswordOfflineCommand.FLAG,
            SetPasswordOfflineCommand.EMAIL_OPTION, EMAIL,
            SetPasswordOfflineCommand.PASSWORD_OPTION, CHOSEN
        }, line -> { })).as("step 3: --set-password must claim the run").isTrue();

        // The DATABASE decides whether this worked, never the return value: a lane that
        // reported success while changing nothing is exactly the shape being fixed here.
        assertThat(PasswordService.verifyPassword(userId, CHOSEN))
            .as("step 3: the new password must be stored through the product's own hasher")
            .isTrue();
        assertThat(PasswordService.verifyPassword(userId, FORGOTTEN))
            .as("step 3: the lost password must no longer work")
            .isFalse();
        assertThat(PasswordService.mustRotate(userId))
            .as("step 3: an operator-set password must be provisional")
            .isTrue();

        // 4. The ORDERING requirement: the datasource is registered BEFORE dispatch, so a
        //    command sees the same ORM a booted server would. Proven by a command whose
        //    refusal can only be COMPOSED by querying backup_targets -- "(none configured)"
        //    is the result of a live ORM read, not a constant.
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Database.CONTROL_PLANE_BACKUP_TARGET, "");
        Datasource beforeLane = Datasources.getDefault();
        assertThatThrownBy(() -> OfflineBoot.runIfRequested(
                new String[] {ListControlPlaneBackupsCommand.FLAG}, line -> { }))
            .as("step 4: the command must reach the database-backed destination check")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Configured targets: (none configured)");
        assertThat(Datasources.getDefault())
            .as("step 4: the lane must have registered its own datasource before dispatching")
            .isNotSameAs(beforeLane)
            .isSameAs(HohenheimDatabase.datasource());

        // 5. An ambiguous key-rotation invocation is REFUSED, never silently narrowed. The
        //    framework runs exactly one command per invocation and picks it in discovery
        //    order, so without this guard a rotate+re-encrypt pair would perform an
        //    arbitrary half and report success -- and the operator would then retire a key
        //    that is still decrypting live rows.
        assertThatThrownBy(() -> OfflineBoot.runIfRequested(new String[] {
                EncryptionKeyLane.ROTATE, EncryptionKeyLane.REENCRYPT}, line -> { }))
            .as("step 5: two key-rotation steps in one invocation must refuse")
            .isInstanceOf(OfflineCommandException.class)
            .hasMessageContaining("only ONE key-rotation step runs per invocation");

        // 5b. The SAME trap one level up, and the reason the guard had to stop being
        //     per-lane: a pair that spans two lanes is picked apart by the very same
        //     discovery-order dispatch, and the lane-local STEPS list cannot see a flag
        //     that is not one of its four. Whichever of these two wins discovery, the
        //     invocation is refused instead of half-performed.
        assertThatThrownBy(() -> OfflineBoot.runIfRequested(new String[] {
                PurgeHistorySecretsCommand.FLAG, EncryptionKeyLane.ROTATE}, line -> { }))
            .as("step 5b: two commands from DIFFERENT lanes must refuse just as loudly")
            .isInstanceOf(OfflineCommandException.class)
            .hasMessageContaining("Exactly ONE offline command runs per invocation");
        assertThatThrownBy(() -> OfflineBoot.runIfRequested(new String[] {
                HistorySecretSurveyCommand.FLAG, PurgeHistorySecretsCommand.FLAG}, line -> { }))
            .as("step 5b: survey plus purge is ambiguous and must never silently pick one")
            .isInstanceOf(OfflineCommandException.class)
            .hasMessageContaining("Exactly ONE offline command runs per invocation");

        // 6. An option value is REQUIRED where the flag needs one, and the refusal names
        //    the flag instead of running against nothing.
        assertThatThrownBy(() -> OfflineBoot.runIfRequested(
                new String[] {RestoreControlPlaneCommand.FLAG}, line -> { }))
            .as("step 6: restore without an archive pointer must refuse by name")
            .isInstanceOf(OfflineCommandException.class)
            .hasMessageContaining(RestoreControlPlaneCommand.FLAG);

        // 7. The settings file the lane loaded is the one the property named: this is what
        //    makes a database.encryption.key_file override in settings/local.dry apply to a
        //    break-glass command exactly as it does to a boot.
        assertThat(HohenheimSettingsFiles.settingsFile())
            .as("step 7: the lane must honour the settings-file override")
            .isEqualTo(settingsFile);

        // 8. THE wiring itself. Everything above proves OfflineBoot works; this proves the
        //    application ENTRY POINT reaches it, which is the half that was missing. The
        //    same method main() calls, so no dispatch can exist in a test and not in the
        //    deployed jar -- and a normal boot still falls through it untouched.
        assertThat(ServerMain.runCommandLineOnly(new String[] {OfflineCommands.HELP_FLAG}))
            .as("step 8: the entry point must claim an offline invocation")
            .isTrue();
        assertThat(ServerMain.runCommandLineOnly(new String[0]))
            .as("step 8: the entry point must fall through to a normal boot without flags")
            .isFalse();
    }

    private static int createUser() {
        Row user = AuthModels.users().createEmptyRow();
        user.set(UserModel.EMAIL, EMAIL);
        user.set(UserModel.DISPLAY_NAME, "Locked Out Admin");
        user.set(UserModel.ENABLED, true);
        user.set(UserModel.CREATED_AT, Instant.now());
        user.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(user);
        return user.get(UserModel.ID);
    }
}
