package be.elevenways.hohenheim.server.application;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.hohenheim.model.OperationStatus;
import be.elevenways.hohenheim.model.ReleaseOperationModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.server.database.DatabaseEnvInjection;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.instance.ConsoleRedaction;
import be.elevenways.hohenheim.server.instance.SecretRedactor;
import be.elevenways.hohenheim.server.instance.ApplicationKind;
import be.elevenways.hohenheim.server.instance.InstanceVariables;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimHttpTestBase;
import be.elevenways.protoblast.common.holder.Subscription;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.KnownValueRedactor;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A release operation's step log is ONE redacting stream: a multi-line secret quoted across several step lines never
 * reaches the stored log or a live frame, and a secret the operation's output was cut off in is masked when it ends.
 * Its failure reason is redacted where it is written, and no stream outlives the segment of work that opened it.
 *
 * @author Jelle De Loecker
 * @since 0.10.0
 */
class ReleaseStepLogRedactionTest extends HohenheimHttpTestBase {

    private static final String WHOLE_KEY = "-----BEGIN TEST KEY A-----\nMIIBVgIBADANBgkqhkiG9w0B\n"
        + "AQEFAASCAUAwggE8AgEAAkEA\n-----END TEST KEY A-----";

    private static final String CUT_KEY = "-----BEGIN TEST KEY B-----\nZmlyc3QtbGluZS1vZi1iLWtl\n"
        + "c2Vjb25kLWxpbmUtb2YtYi1r\n-----END TEST KEY B-----";

    private static final String DB_PASSWORD = "pg-pass-7f3a9c2e41b8d605";

    @Test
    void aMultiLineSecretAcrossStepLinesAndATruncatedOneAtTheEndNeverLeakJourney() {
        int appId = application("release-step-redaction");
        List<String> frames = new CopyOnWriteArrayList<>();
        Subscription watching = DeployProgress.watch(appId, frames::add);
        try {
            // 1. A step quotes daemon output that echoes a multi-line key, one step line per line of it: no line of
            //    the key is ever stored or streamed, the whole key becomes one placeholder.
            Row op = ReleaseEngine.newOperation(ReleaseOperationModel.KIND_RELEASE, appId, "fp", "fp");
            ReleaseEngine.step(op, "daemon output follows");
            for (String line : WHOLE_KEY.split("\n")) {
                ReleaseEngine.step(op, line);
            }
            ReleaseEngine.step(op, "candidate started");
            String midway = op.get(ReleaseOperationModel.STEP_LOG);
            assertThat(midway).as("step 1: the lines around the key are stored")
                .contains(" daemon output follows\n").contains(" candidate started\n")
                .as("step 1: the key is one placeholder line").contains(" " + KnownValueRedactor.PLACEHOLDER + "\n");
            assertNoKeyLine("step 1: the stored log", midway, WHOLE_KEY);

            // 2. The output is cut off two lines into another key and the operation ends: the held prefix is
            //    masked BEFORE the controller's last line, never stored raw and never lost.
            String[] cut = CUT_KEY.split("\n");
            ReleaseEngine.step(op, cut[0]);
            ReleaseEngine.step(op, cut[1]);
            ReleaseEngine.settle(op, ReleaseOperationModel.LIFECYCLE.stored(OperationStatus.SUCCEEDED), null,
                "release complete", Now.instant());
            Row stored = Models.get(ReleaseOperationModel.class).findById(op.get(ReleaseOperationModel.ID));
            assertThat((String) stored.get(ReleaseOperationModel.STATUS)).as("step 2: the terminal write landed")
                .isEqualTo(ReleaseOperationModel.LIFECYCLE.stored(OperationStatus.SUCCEEDED));
            String log = stored.get(ReleaseOperationModel.STEP_LOG);
            assertNoKeyLine("step 2: the stored log", log, WHOLE_KEY);
            assertNoKeyLine("step 2: the stored log", log, CUT_KEY);
            assertThat(log).as("step 2: the truncated key is masked, then the last line follows on its own line")
                .matches("(?s).* " + Pattern.quote(KnownValueRedactor.PLACEHOLDER) + "\n\\S+ release complete\n");
            assertThat(log.split("\n")).as("step 2: every stored line opens with its timestamp")
                .allMatch(line -> line.matches("\\d{4}-\\d{2}-\\d{2}T\\S+ .*"));

            // 3. Every live frame the viewers heard, the last one being the stored log, carried no line of either key.
            assertThat(frames).as("step 3: the viewers heard the steps").isNotEmpty();
            assertThat(frames.get(frames.size() - 1)).as("step 3: the last frame is the stored log").isEqualTo(log);
            for (String frame : frames) {
                assertNoKeyLine("step 3: a live frame", frame, WHOLE_KEY);
                assertNoKeyLine("step 3: a live frame", frame, CUT_KEY);
            }
        } finally {
            watching.unsubscribe();
            cleanUp(appId);
        }
    }

    @Test
    void aFailureQuotingASecretIsStoredRedactedAndNoSegmentOutlivesItsStreamJourney() throws Exception {
        int appId = application("release-failure-redaction");
        List<String> frames = new CopyOnWriteArrayList<>();
        Subscription watching = DeployProgress.watch(appId, frames::add);
        try {
            // 1. A segment fails with daemon text that quotes a secret environment value, and the engine's catch
            //    settles it with that reason: the stored reason names the failure, never the value.
            Row failed = ReleaseEngine.newOperation(ReleaseOperationModel.KIND_RELEASE, appId, "fp", "fp");
            int failedId = failed.get(ReleaseOperationModel.ID);
            assertThatThrownBy(() -> ReleaseEngine.segment(failed, () -> {
                ReleaseEngine.step(failed, "creating the candidate");
                assertThat(ReleaseStepLog.isOpen(failedId)).as("step 1: the segment's stream is open").isTrue();
                try {
                    throw new IllegalStateException("docker create refused: env DB_PASSWORD=" + DB_PASSWORD
                        + " rejected by the daemon");
                } catch (RuntimeException e) {
                    ReleaseEngine.settle(failed, ReleaseOperationModel.LIFECYCLE.stored(OperationStatus.FAILED),
                        HohenheimViolations.reasonOf(e), "deploy failed", Now.instant());
                    throw e;
                }
            })).isInstanceOf(IllegalStateException.class);
            Row stored = Models.get(ReleaseOperationModel.class).findById(failedId);
            assertThat((String) stored.get(ReleaseOperationModel.FAILURE_REASON))
                .as("step 1: the stored reason still explains the failure").contains("rejected by the daemon")
                .as("step 1: with the value masked").contains(KnownValueRedactor.PLACEHOLDER)
                .doesNotContain(DB_PASSWORD);
            assertThat(ReleaseStepLog.isOpen(failedId)).as("step 1: the settled operation holds no stream").isFalse();

            // 2. The operator's Deploys tab shows the reason as stored, and no live frame carried the value.
            HttpResponse<String> tab = adminGet("/admin/instances/" + appId + "/page/" + HohenheimSlugs.Tab.DEPLOYMENTS);
            assertThat(tab.statusCode()).as("step 2: the Deploys tab renders").isEqualTo(200);
            assertThat(tab.body()).as("step 2: the operator reads the reason").contains("rejected by the daemon")
                .as("step 2: never the secret value").doesNotContain(DB_PASSWORD);
            assertThat(frames).as("step 2: the viewers heard the failed operation").isNotEmpty()
                .noneMatch(frame -> frame.contains(DB_PASSWORD));

            // 3. A segment that fails BEFORE any settle, its output cut off inside a multi-line key: the stream is
            //    gone with the segment, and the prefix it held is stored masked, never lost and never raw.
            Row aborted = ReleaseEngine.newOperation(ReleaseOperationModel.KIND_RELEASE, appId, "fp", "fp");
            int abortedId = aborted.get(ReleaseOperationModel.ID);
            String[] cut = CUT_KEY.split("\n");
            assertThatThrownBy(() -> ReleaseEngine.segment(aborted, () -> {
                ReleaseEngine.step(aborted, cut[0]);
                ReleaseEngine.step(aborted, cut[1]);
                throw new IllegalStateException("controller aborted mid-operation");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(ReleaseStepLog.isOpen(abortedId)).as("step 3: no stream outlives the failed segment").isFalse();
            String abortedLog = Models.get(ReleaseOperationModel.class).findById(abortedId)
                .get(ReleaseOperationModel.STEP_LOG);
            assertNoKeyLine("step 3: the stored log", abortedLog, CUT_KEY);
            assertThat(abortedLog).as("step 3: the held prefix landed masked on a line of its own")
                .endsWith(" " + KnownValueRedactor.PLACEHOLDER + "\n");

            // 4. An operation naming no instance owner has no secret set: its reason is the placeholder, never raw.
            Row orphan = Models.get(ReleaseOperationModel.class).createEmptyRow();
            orphan.set(ReleaseOperationModel.FOR_MODEL, "unknown");
            orphan.set(ReleaseOperationModel.FOR_ID, appId);
            assertThat(ReleaseStepLog.redactedReason(orphan, "refused: " + DB_PASSWORD))
                .as("step 4: an owner-less reason fails closed").isEqualTo(KnownValueRedactor.PLACEHOLDER);
            assertThat(ReleaseStepLog.redactedReason(orphan, null)).as("step 4: no reason stays none").isNull();

            // 5. FAIL CLOSED: an owner whose secrets cannot be read (its record gone, or a model with no rule; the
            //    same catch an unreadable variable table lands in) masks whole, never emits raw text.
            ConsoleRedaction.Secrets gone = ConsoleRedaction.secretsOfOwner(StackModel.MODEL_ID, Integer.MAX_VALUE);
            assertThat(gone.readable()).as("step 5: a gone owner's secrets are unreadable").isFalse();
            assertThat(ConsoleRedaction.secretsOfOwner(ReleaseOperationModel.MODEL_ID, failedId).readable())
                .as("step 5: an owner model with no rule is unreadable").isFalse();
            assertThat(ConsoleRedaction.redactedReason(StackModel.MODEL_ID, Integer.MAX_VALUE, "refused: " + DB_PASSWORD))
                .as("step 5: its failure reason is the placeholder").isEqualTo(KnownValueRedactor.PLACEHOLDER);
            SecretRedactor masking = SecretRedactor.of(gone);
            assertThat(masking.feed("first line\nsecond " + DB_PASSWORD + "\npartial"))
                .as("step 5: a stream over it masks every line it releases")
                .isEqualTo(KnownValueRedactor.PLACEHOLDER + "\n" + KnownValueRedactor.PLACEHOLDER + "\n");
            assertThat(masking.flush()).as("step 5: and the unfinished line at its end")
                .isEqualTo(KnownValueRedactor.PLACEHOLDER);
            assertThat(ConsoleRedaction.secretsOf(appId).readable())
                .as("step 5 anchor: a readable owner is not masked").isTrue();
        } finally {
            watching.unsubscribe();
            cleanUp(appId);
        }
    }

    @Test
    void anAttachedDatabasesDerivedCredentialsNeverLeakRawOrUrlEncodedJourney() {
        int appId = application("release-db-env-redaction");
        String password = "db@pass/w0rd+7f3a";
        int databaseId = attachedDatabase(appId, "release-redaction-db", password);
        try {
            // 1. The family DatabaseEnvInjection derives at resolve time and stores nowhere: the URL carries the
            //    password URL-encoded, which reads differently from the raw value.
            Map<String, String> env = DatabaseEnvInjection.vars(ManagedDatabase.Engine.POSTGRES, "release-db", 5432,
                "appuser", password, "appdb", "DB", true);
            String url = env.get("DATABASE_URL");
            String encoded = DatabaseEnvInjection.urlEncoded(password);
            assertThat(encoded).as("step 1: the encoded form differs from the raw password").isNotEqualTo(password);
            assertThat(url).as("step 1: the URL carries the encoded form only").contains(encoded)
                .doesNotContain(password);
            String printed = "DATABASE_URL=" + url + "\nDB_PASSWORD=" + env.get("DB_PASSWORD") + "\n";

            // 2. The release step log and the failure reason: neither form is stored.
            Row op = ReleaseEngine.newOperation(ReleaseOperationModel.KIND_RELEASE, appId, "fp", "fp");
            for (String line : printed.split("\n")) {
                ReleaseEngine.step(op, line);
            }
            ReleaseEngine.settle(op, ReleaseOperationModel.LIFECYCLE.stored(OperationStatus.FAILED),
                "connect refused for " + url + " with " + password, "deploy failed", Now.instant());
            Row stored = Models.get(ReleaseOperationModel.class).findById(op.get(ReleaseOperationModel.ID));
            assertNeitherForm("step 2: the stored step log", stored.get(ReleaseOperationModel.STEP_LOG), password);
            assertThat((String) stored.get(ReleaseOperationModel.STEP_LOG))
                .as("step 2: the lines are kept around the placeholders").contains("DATABASE_URL=postgres://");
            assertNeitherForm("step 2: the stored failure reason", stored.get(ReleaseOperationModel.FAILURE_REASON),
                password);
            assertThat((String) stored.get(ReleaseOperationModel.FAILURE_REASON))
                .as("step 2: the reason still explains the failure").contains("connect refused for");

            // 3. The console: its ring's stream and a whole stored backlog carry neither form.
            SecretRedactor console = ConsoleRedaction.redactorFor(appId);
            assertNeitherForm("step 3: the console stream", console.feed(printed) + console.flush(), password);
            assertNeitherForm("step 3: a console backlog", ConsoleRedaction.redactWhole(printed, appId), password);

            // 4. FAIL CLOSED: an instance whose record is gone has an unreadable set, and its text is masked whole.
            assertThat(ConsoleRedaction.secretsOf(Integer.MAX_VALUE).readable())
                .as("step 4: a gone instance's secrets are unreadable").isFalse();
            assertThat(ConsoleRedaction.redactWhole("DB_PASSWORD=" + password, Integer.MAX_VALUE))
                .as("step 4: its text is the placeholder").isEqualTo(KnownValueRedactor.PLACEHOLDER);
        } finally {
            for (Row link : Models.get(InstanceDatabaseModel.class).findByInstanceId(appId)) {
                Models.get(InstanceDatabaseModel.class).delete(link.get(InstanceDatabaseModel.ID));
            }
            HardDeletes.byId(Models.get(DatabaseModel.class), databaseId);
            cleanUp(appId);
        }
    }

    /** @return a dedicated database record holding the password, attached to the instance under {@code DB} */
    private static int attachedDatabase(int instanceId, String name, String password) {
        Row database = Models.get(DatabaseModel.class).createEmptyRow();
        database.set(DatabaseModel.NAME, name);
        database.set(DatabaseModel.ENGINE, DatabaseModel.ENGINE_POSTGRES);
        database.set(DatabaseModel.DB_NAME, "appdb");
        database.set(DatabaseModel.DB_USER, "appuser");
        database.set(DatabaseModel.DB_PASSWORD, password);
        database.set(DatabaseModel.PLACEMENT, DatabaseModel.PLACEMENT_DEDICATED);
        database.set(DatabaseModel.STATUS, DatabaseModel.STATUS_ACTIVE);
        Models.get(DatabaseModel.class).save(database);
        int databaseId = database.get(DatabaseModel.ID);
        Row link = Models.get(InstanceDatabaseModel.class).createEmptyRow();
        link.set(InstanceDatabaseModel.INSTANCE_ID, instanceId);
        link.set(InstanceDatabaseModel.DATABASE_ID, databaseId);
        link.set(InstanceDatabaseModel.ENV_PREFIX, "DB");
        Models.get(InstanceDatabaseModel.class).save(link);
        return databaseId;
    }

    private static void assertNeitherForm(String what, String text, String password) {
        assertThat(text).as(what + " carries neither the raw nor the URL-encoded password")
            .doesNotContain(password).doesNotContain(DatabaseEnvInjection.urlEncoded(password));
    }

    /** @return a fresh application carrying the test's secret variables */
    private static int application(String name) {
        Row app = Models.get(InstanceModel.class).createEmptyRow();
        app.set(InstanceModel.NAME, name);
        app.set(InstanceModel.KIND, ApplicationKind.ID.toString());
        app.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "alpine")));
        app.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
        Models.get(InstanceModel.class).save(app);
        int appId = app.get(InstanceModel.ID);
        new InstanceVariables().setValue(appId, null, "TLS_KEY", InstanceVariableModel.KIND_SECRET, WHOLE_KEY);
        new InstanceVariables().setValue(appId, null, "SIGNING_KEY", InstanceVariableModel.KIND_SECRET, CUT_KEY);
        new InstanceVariables().setValue(appId, null, "DB_PASSWORD", InstanceVariableModel.KIND_SECRET, DB_PASSWORD);
        return appId;
    }

    private static void cleanUp(int appId) {
        for (Row variable : Models.get(InstanceVariableModel.class).findByInstanceId(appId)) {
            Models.get(InstanceVariableModel.class).delete(variable.get(InstanceVariableModel.ID));
        }
        for (Row op : Models.get(ReleaseOperationModel.class)
                .findForOwner(InstanceModel.MODEL_ID.toString(), appId, 50)) {
            Models.get(ReleaseOperationModel.class).delete(op.get(ReleaseOperationModel.ID));
        }
        HardDeletes.byId(Models.get(InstanceModel.class), appId);
    }

    private static void assertNoKeyLine(String what, String text, String key) {
        for (String line : key.split("\n")) {
            assertThat(text).as(what + " carries no line of a key").doesNotContain(line);
        }
    }
}
