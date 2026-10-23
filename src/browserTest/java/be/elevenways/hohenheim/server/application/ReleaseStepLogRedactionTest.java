package be.elevenways.hohenheim.server.application;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.hohenheim.model.OperationStatus;
import be.elevenways.hohenheim.model.ReleaseOperationModel;
import be.elevenways.hohenheim.server.instance.ApplicationKind;
import be.elevenways.hohenheim.server.instance.InstanceVariables;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.protoblast.common.holder.Subscription;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.KnownValueRedactor;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A release operation's step log is ONE redacting stream: a multi-line secret quoted across several step lines never
 * reaches the stored log or a live frame, and a secret the operation's output was cut off in is masked when it ends.
 *
 * @author Jelle De Loecker
 * @since 0.10.0
 */
class ReleaseStepLogRedactionTest extends HohenheimTestBase {

    private static final String WHOLE_KEY = "-----BEGIN TEST KEY A-----\nMIIBVgIBADANBgkqhkiG9w0B\n"
        + "AQEFAASCAUAwggE8AgEAAkEA\n-----END TEST KEY A-----";

    private static final String CUT_KEY = "-----BEGIN TEST KEY B-----\nZmlyc3QtbGluZS1vZi1iLWtl\n"
        + "c2Vjb25kLWxpbmUtb2YtYi1r\n-----END TEST KEY B-----";

    @Test
    void aMultiLineSecretAcrossStepLinesAndATruncatedOneAtTheEndNeverLeakJourney() {
        Row app = Models.get(InstanceModel.class).createEmptyRow();
        app.set(InstanceModel.NAME, "release-step-redaction");
        app.set(InstanceModel.KIND, ApplicationKind.ID.toString());
        app.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "alpine")));
        app.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
        Models.get(InstanceModel.class).save(app);
        int appId = app.get(InstanceModel.ID);
        new InstanceVariables().setValue(appId, null, "TLS_KEY", InstanceVariableModel.KIND_SECRET, WHOLE_KEY);
        new InstanceVariables().setValue(appId, null, "SIGNING_KEY", InstanceVariableModel.KIND_SECRET, CUT_KEY);
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
            for (Row variable : Models.get(InstanceVariableModel.class).findByInstanceId(appId)) {
                Models.get(InstanceVariableModel.class).delete(variable.get(InstanceVariableModel.ID));
            }
            for (Row op : Models.get(ReleaseOperationModel.class)
                    .findForOwner(InstanceModel.MODEL_ID.toString(), appId, 50)) {
                Models.get(ReleaseOperationModel.class).delete(op.get(ReleaseOperationModel.ID));
            }
            HardDeletes.byId(Models.get(InstanceModel.class), appId);
        }
    }

    private static void assertNoKeyLine(String what, String text, String key) {
        for (String line : key.split("\n")) {
            assertThat(text).as(what + " carries no line of a key").doesNotContain(line);
        }
    }
}
